package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Ranks monsters by productivity metrics from completed task history, for the task advisor's skip / block badge
 * ({@link TaskAdvisor}); its rates ({@link #perHour}, {@link #perKill}) and samples ({@link #isTaskSample}) are shared
 * with Overall totals and the advisor's plan. Pure: no RuneLite types.
 *
 * <p>Samples are records only (never assignment entries) that are completed, not partial, with kills and active time.
 * Each hourly metric pools its totals and active time over the samples that have that metric; a monster gets no rate
 * for it until that pooled time reaches {@link TaskTimeEstimate#MIN_ACTIVE_MILLIS} (it is still listed, last).
 * {@link Metric#PROFIT_PER_KILL} pools profit and kills instead, with no minimum time.</p>
 *
 * <p><b>Catacombs variants and cannon tasks</b>: a record done on its task's Catacombs variant
 * ({@link LedgerRecord#getVariant}) or with a cannon ({@link LedgerRecord#cannonTask}) is pooled with the task's other
 * records, as the advisor has always ranked them. (The ranking that kept them apart went with Trends; Overall
 * totals by monster groups by {@link LedgerRecord#groupName} itself.)</p>
 */
final class TaskRanking
{
	enum Metric
	{
		/** Loot in the "Loot price" mode minus supplies at GE, per hour. */
		PROFIT,
		/**
		 * The same profit as {@link #PROFIT}, per kill: the profit of the tasks that have one over their kills.
		 * The task advisor's badge never ranks by it ({@link TaskAdvisor#badgeMetric}).
		 */
		PROFIT_PER_KILL,
		/** Slayer XP only (the {@link SkillXp#SLAYER} entry of a record's XP), per hour. */
		XP,
		/** Slayer points per hour, over records that tracked the whole assignment and are not bonus tasks only. */
		POINTS;

		/**
		 * The metric's name as shown: "Profit (GE)/h" or "Profit (HA)/h", "Profit (GE)/kill" or "Profit (HA)/kill",
		 * "Slayer XP/h", "Points/h".
		 */
		String label(LootPriceMode mode)
		{
			switch (this)
			{
				case XP:
					return "Slayer XP/h";
				case POINTS:
					return "Points/h";
				case PROFIT_PER_KILL:
					return LedgerHistory.profitName(mode == null ? LootPriceMode.GE : mode) + "/kill";
				default:
					return LedgerHistory.profitName(mode == null ? LootPriceMode.GE : mode) + "/h";
			}
		}

		/** Whether the metric is per kill rather than per active hour, so it needs no minimum active time. */
		boolean isPerKill()
		{
			return this == PROFIT_PER_KILL;
		}

		@Override
		public String toString()
		{
			return label(LootPriceMode.GE);
		}
	}

	/** Rate first (highest first, no rate last), then name ignoring case, then name. */
	private static final Comparator<Entry> ORDER = Comparator
		.comparing(Entry::getRatePerHour, Comparator.nullsLast(Comparator.<Long>reverseOrder()))
		.thenComparing(Entry::getMonster, String.CASE_INSENSITIVE_ORDER)
		.thenComparing(Entry::getMonster);

	private TaskRanking()
	{
	}

	/**
	 * Builds a pooled hourly rate for each monster (a task's Catacombs variant and cannon records pooled with its
	 * others): metric totals divided by the active time of records that contain that metric, with {@code hidden}'s loot
	 * left out of the profit. Every metric reports its own sample count. Outliers are not trimmed. A monster whose
	 * pooled active time for the metric is under {@link TaskTimeEstimate#MIN_ACTIVE_MILLIS} has a {@code null} rate and
	 * sorts last. A {@linkplain Metric#isPerKill per-kill} metric divides by the kills of the same records instead,
	 * with no minimum time.
	 */
	static List<Entry> rank(Collection<LedgerRecord> records, Metric metric, LootPriceMode mode,
		Map<Integer, Long> currentGe, HiddenLoot hidden)
	{
		if (records == null || records.isEmpty() || metric == null)
		{
			return Collections.emptyList();
		}

		Map<String, Set<Integer>> creatureIdsByName = creatureIdsByName(records);
		Map<TaskKey, Aggregate> aggregates = new TreeMap<>();
		for (LedgerRecord record : records)
		{
			if (!isTaskSample(record))
			{
				continue;
			}
			TaskKey key = taskKey(record, creatureIdsByName);
			if (key == null)
			{
				continue;
			}
			Aggregate aggregate = aggregates.computeIfAbsent(key, k -> new Aggregate());
			aggregate.name.offer(record, key);
			aggregate.add(record, metric, mode, currentGe, hidden);
		}

		List<Entry> entries = new ArrayList<>();
		for (Map.Entry<TaskKey, Aggregate> aggregate : aggregates.entrySet())
		{
			Entry entry = aggregate.getValue().entry(aggregate.getKey(), metric);
			if (entry != null)
			{
				entries.add(entry);
			}
		}
		entries.sort(ORDER);
		return Collections.unmodifiableList(entries);
	}

	/** A completed, not partial record with kills and active time: the samples rankings and charts use. */
	static boolean isTaskSample(LedgerRecord record)
	{
		return record != null && record.isCompleted() && !record.isPartial() && record.getKills() > 0
			&& record.getActiveMillis() > 0;
	}

	/** The creature ids seen for each normalized name among the task samples, to place records without an id. */
	static Map<String, Set<Integer>> creatureIdsByName(Collection<LedgerRecord> records)
	{
		Map<String, Set<Integer>> creatureIdsByName = new HashMap<>();
		for (LedgerRecord record : records)
		{
			if (isTaskSample(record) && record.getCreatureId() != null)
			{
				String name = TaskStatistics.normalize(record.getMonster());
				if (name != null)
				{
					creatureIdsByName.computeIfAbsent(name, n -> new HashSet<>()).add(record.getCreatureId());
				}
			}
		}
		return creatureIdsByName;
	}

	/**
	 * Whether the record's session tracked the whole assignment (it started at the assigned size), so its points
	 * belong to all of the task's active time. Owner decision: a task whose session started part-way through
	 * is left out of Points/h only.
	 */
	static boolean coversWholeTask(LedgerRecord record)
	{
		Integer original = record.getOriginalAmount();
		return original != null && record.getInitialAmount() >= original;
	}

	/**
	 * Whether a record's points count for Points/h (the task advisor): known,
	 * non-negative points, a task tracked from the full assigned amount ({@link #coversWholeTask}), and not a
	 * bonus-points task ({@link StreakMilestone#isMilestone}). A record without a stored streak still counts.
	 * A record's own points (History tooltip) are unaffected.
	 */
	static boolean countsForPointsRate(LedgerRecord record)
	{
		Integer points = record.getPointsEarned();
		return points != null && points >= 0 && coversWholeTask(record)
			&& !StreakMilestone.isMilestone(record.getTaskStreak());
	}

	/**
	 * The group the task advisor's ranking ({@link #rank}) puts the current task's records in, whichever version
	 * and cannon use: its creature key, or with none
	 * ({@link SessionTracker#NO_CREATURE}) the group its name gets among {@code records}, as {@link #taskKey} places a
	 * record without one. When no task sample is in the creature key's group (the monster's history is only older
	 * records without an id), its name key instead, so it matches what the per-task statistics count. {@code null} for
	 * no creature key and a blank name.
	 */
	static TaskKey currentTaskKey(Collection<LedgerRecord> records, int creatureId, String monster)
	{
		Map<String, Set<Integer>> creatureIdsByName = records == null
			? Collections.emptyMap()
			: creatureIdsByName(records);
		if (creatureId != SessionTracker.NO_CREATURE)
		{
			TaskKey creature = TaskKey.forCreature(creatureId);
			String name = TaskStatistics.normalize(monster);
			if (name == null || records == null)
			{
				return creature;
			}
			for (LedgerRecord record : records)
			{
				if (isTaskSample(record) && creature.equals(taskKey(record, creatureIdsByName)))
				{
					return creature;
				}
			}
			return TaskKey.forName(name);
		}
		LedgerRecord current = new LedgerRecord();
		current.setMonster(monster);
		return taskKey(current, creatureIdsByName);
	}

	/**
	 * The record's group, whichever version and cannon use: its creature key, or for a record without
	 * one, the single creature key its name has among {@code creatureIdsByName}, else its normalized name. {@code null}
	 * for a blank name without a creature key.
	 */
	static TaskKey taskKey(LedgerRecord record, Map<String, Set<Integer>> creatureIdsByName)
	{
		Integer creatureId = record.getCreatureId();
		String name = TaskStatistics.normalize(record.getMonster());
		if (creatureId != null)
		{
			return TaskKey.forCreature(creatureId);
		}
		if (name == null)
		{
			return null;
		}
		Set<Integer> ids = creatureIdsByName.get(name);
		return ids != null && ids.size() == 1
			? TaskKey.forCreature(ids.iterator().next())
			: TaskKey.forName(name);
	}

	private static long saturatedAdd(long left, long right)
	{
		try
		{
			return Math.addExact(left, right);
		}
		catch (ArithmeticException e)
		{
			return right >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
		}
	}

	/** As {@link SkillXp#perHour}: no rate until there is {@link TaskTimeEstimate#MIN_ACTIVE_MILLIS} of time. */
	static Long perHour(long total, long activeMillis)
	{
		if (activeMillis < TaskTimeEstimate.MIN_ACTIVE_MILLIS)
		{
			return null;
		}
		return Math.round(total * 3_600_000d / activeMillis);
	}

	/** {@code total} per kill, or {@code null} without kills; no minimum time. */
	static Long perKill(long total, long kills)
	{
		return kills <= 0 ? null : Math.round(total / (double) kills);
	}

	static final class Entry
	{
		private final TaskKey key;
		private final String monster;
		private final Long ratePerHour;
		private final int sampleCount;
		private final long activeMillis;
		private final boolean approximate;

		private Entry(TaskKey key, String monster, Long ratePerHour, int sampleCount, long activeMillis,
			boolean approximate)
		{
			this.key = key;
			this.monster = monster;
			this.ratePerHour = ratePerHour;
			this.sampleCount = sampleCount;
			this.activeMillis = activeMillis;
			this.approximate = approximate;
		}

		/** The group the entry's records are in ({@link #taskKey}). */
		TaskKey getKey()
		{
			return key;
		}

		String getMonster()
		{
			return monster;
		}

		/**
		 * The pooled rate per hour, or {@code null} while the metric has under a minute of active time; for a
		 * {@linkplain Metric#isPerKill per-kill} metric, the pooled value per kill instead.
		 */
		Long getRatePerHour()
		{
			return ratePerHour;
		}

		int getSampleCount()
		{
			return sampleCount;
		}

		/** The pooled active time of the samples with the metric (the rate's denominator), in milliseconds. */
		long getActiveMillis()
		{
			return activeMillis;
		}

		boolean isApproximate()
		{
			return approximate;
		}
	}

	/** A group's display name: the trimmed task name of a record with a creature id when there is one. */
	private static final class GroupName
	{
		private String monster;
		/** Whether {@link #monster} came from a record with a creature id (preferred over a name-only one). */
		private boolean monsterFromCreatureId;

		/** Takes the record's trimmed name, until there is one from a record with a creature id. */
		void offer(LedgerRecord record, TaskKey key)
		{
			boolean fromCreatureId = record.getCreatureId() != null;
			if (monster != null && (monsterFromCreatureId || !fromCreatureId))
			{
				return;
			}
			String trimmed = record.getMonster() == null ? null : record.getMonster().trim();
			int creatureId = key.creatureId == null ? SessionTracker.NO_CREATURE : key.creatureId;
			monster = LedgerRecord.displayName(trimmed == null || trimmed.isEmpty() ? null : trimmed, creatureId);
			monsterFromCreatureId = fromCreatureId;
		}

		String get()
		{
			return monster;
		}
	}

	private static final class Aggregate
	{
		private final GroupName name = new GroupName();
		private long activeMillis;
		private long kills;
		private long total;
		private int samples;
		private boolean approximate;

		private void add(LedgerRecord record, Metric metric, LootPriceMode mode, Map<Integer, Long> currentGe,
			HiddenLoot hidden)
		{
			long value;
			boolean isApproximate = false;
			switch (metric)
			{
				case PROFIT:
				case PROFIT_PER_KILL:
					if (!LedgerHistory.isValued(record))
					{
						return;
					}
					HistoryPricing.Values prices = HistoryPricing.value(record, mode, currentGe, hidden);
					// A valued record counts only with both a loot and a supplies value.
					if (prices.getLootValue() == null || prices.getSuppliesGe() == null || prices.getProfit() == null)
					{
						return;
					}
					value = prices.getProfit();
					isApproximate = prices.isProfitApproximate();
					break;
				case XP:
					Map<String, Long> xp = record.getXpGained();
					if (xp == null)
					{
						return;
					}
					// XP recorded without a Slayer entry means no Slayer XP was gained.
					Long slayer = xp.get(SkillXp.SLAYER);
					value = slayer == null ? 0 : Math.max(0, slayer);
					break;
				case POINTS:
					if (!countsForPointsRate(record))
					{
						return;
					}
					value = record.getPointsEarned().longValue();
					break;
				default:
					return;
			}
			total = saturatedAdd(total, value);
			activeMillis = saturatedAdd(activeMillis, record.getActiveMillis());
			kills = saturatedAdd(kills, record.getKills());
			samples++;
			approximate |= isApproximate;
		}

		private Entry entry(TaskKey key, Metric metric)
		{
			if (samples == 0 || activeMillis <= 0)
			{
				return null;
			}
			Long rate = metric.isPerKill() ? perKill(total, kills) : perHour(total, activeMillis);
			return new Entry(key, name.get(), rate, samples, activeMillis, approximate);
		}
	}

	/** A ranking group: a creature key, or a normalized name for records without one. */
	static final class TaskKey implements Comparable<TaskKey>
	{
		private final Integer creatureId;
		private final String name;

		private TaskKey(Integer creatureId, String name)
		{
			this.creatureId = creatureId;
			this.name = name;
		}

		private static TaskKey forCreature(int creatureId)
		{
			return new TaskKey(creatureId, null);
		}

		private static TaskKey forName(String name)
		{
			return new TaskKey(null, name);
		}

		/** The creature key ({@code < -1} for a boss task), or {@code null} for a name group. */
		Integer getCreatureId()
		{
			return creatureId;
		}

		@Override
		public int compareTo(TaskKey other)
		{
			if (creatureId != null && other.creatureId == null)
			{
				return -1;
			}
			if (creatureId == null && other.creatureId != null)
			{
				return 1;
			}
			return creatureId != null ? creatureId.compareTo(other.creatureId) : name.compareTo(other.name);
		}

		@Override
		public boolean equals(Object other)
		{
			if (this == other)
			{
				return true;
			}
			if (!(other instanceof TaskKey))
			{
				return false;
			}
			TaskKey that = (TaskKey) other;
			return Objects.equals(creatureId, that.creatureId) && Objects.equals(name, that.name);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(creatureId, name);
		}
	}
}
