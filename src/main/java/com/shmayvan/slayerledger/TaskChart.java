package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-completed-task metric points for a selected monster. Pure: no RuneLite types.
 *
 * <p>Samples, grouping, names and metrics follow {@link TaskRanking}: records only that are completed, not partial,
 * with kills, grouped by creature key, with Profit/h and Profit/kill in the Loot price mode, Slayer XP/h, and Points/h
 * only for records that tracked the whole assignment and are not bonus tasks ({@link TaskRanking#countsForPointsRate}).
 * Each point is one task, so a task also needs a completion date ({@code endedAt > 0}) and, for an hourly metric, at
 * least {@link TaskTimeEstimate#MIN_ACTIVE_MILLIS} of active time; a per-kill point is that task's value over its
 * kills, with no minimum time.</p>
 */
final class TaskChart
{
	private static final Comparator<Point> POINT_ORDER = Comparator.comparingLong(Point::getEndedAt)
		.thenComparing(point -> point.getRecordId() == null ? "" : point.getRecordId());

	private TaskChart()
	{
	}

	/** Monsters with at least one plotted task for {@code metric}, by label ignoring case. */
	static List<Monster> monsters(Collection<LedgerRecord> records, TaskRanking.Metric metric, LootPriceMode mode,
		Map<Integer, Long> currentGe)
	{
		return monsters(records, metric, mode, currentGe, HiddenLoot.NONE);
	}

	/** As {@link #monsters(Collection, TaskRanking.Metric, LootPriceMode, Map)}, with {@code hidden}'s loot left out. */
	static List<Monster> monsters(Collection<LedgerRecord> records, TaskRanking.Metric metric, LootPriceMode mode,
		Map<Integer, Long> currentGe, HiddenLoot hidden)
	{
		Map<TaskRanking.TaskKey, Group> groups = groups(records, metric, mode, currentGe, hidden);
		List<Group> plotted = new ArrayList<>();
		for (Group group : groups.values())
		{
			if (!group.points.isEmpty())
			{
				plotted.add(group);
			}
		}
		List<Monster> result = new ArrayList<>();
		for (Group group : plotted)
		{
			result.add(new Monster(group.key, label(group, plotted), group.name.getTask()));
		}
		result.sort(Comparator.comparing(Monster::getLabel, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(Monster::getLabel)
			.thenComparing(Monster::getKey));
		return Collections.unmodifiableList(result);
	}

	/** One dated metric point per plotted task of {@code monster}, oldest first. */
	static List<Point> points(Collection<LedgerRecord> records, Monster monster, TaskRanking.Metric metric,
		LootPriceMode mode, Map<Integer, Long> currentGe)
	{
		return points(records, monster, metric, mode, currentGe, HiddenLoot.NONE);
	}

	/**
	 * As {@link #points(Collection, Monster, TaskRanking.Metric, LootPriceMode, Map)}, with {@code hidden}'s loot left
	 * out.
	 */
	static List<Point> points(Collection<LedgerRecord> records, Monster monster, TaskRanking.Metric metric,
		LootPriceMode mode, Map<Integer, Long> currentGe, HiddenLoot hidden)
	{
		if (monster == null)
		{
			return Collections.emptyList();
		}
		Group group = groups(records, metric, mode, currentGe, hidden).get(monster.key);
		if (group == null)
		{
			return Collections.emptyList();
		}
		List<Point> result = new ArrayList<>(group.points);
		result.sort(POINT_ORDER);
		return Collections.unmodifiableList(result);
	}

	/** Every group of task samples (named from all of them), with the points for {@code metric}. */
	private static Map<TaskRanking.TaskKey, Group> groups(Collection<LedgerRecord> records,
		TaskRanking.Metric metric, LootPriceMode mode, Map<Integer, Long> currentGe, HiddenLoot hidden)
	{
		if (records == null || records.isEmpty() || metric == null)
		{
			return Collections.emptyMap();
		}
		Map<String, Set<Integer>> idsByName = TaskRanking.creatureIdsByName(records);
		Map<TaskRanking.TaskKey, Group> groups = new LinkedHashMap<>();
		for (LedgerRecord record : records)
		{
			if (!TaskRanking.isTaskSample(record))
			{
				continue;
			}
			TaskRanking.TaskKey key = TaskRanking.taskKey(record, idsByName);
			if (key == null)
			{
				continue;
			}
			Group group = groups.computeIfAbsent(key, Group::new);
			group.name.offer(record, key);
			if (record.getEndedAt() <= 0
				|| !metric.isPerKill() && record.getActiveMillis() < TaskTimeEstimate.MIN_ACTIVE_MILLIS)
			{
				continue;
			}
			MetricValue value = metricValue(record, metric, mode, currentGe, hidden);
			if (value != null)
			{
				// A task sample has kills, so a per-kill value is never null.
				long rate = metric.isPerKill()
					? TaskRanking.perKill(value.value, record.getKills())
					: Math.round(value.value * 3_600_000d / record.getActiveMillis());
				group.points.add(new Point(record.getEndedAt(), rate, value.approximate, record.getNote(),
					record.getId()));
			}
		}
		return groups;
	}

	/**
	 * The group's name, with a suffix when another plotted group has the same name: {@code (boss)}, {@code (older
	 * records)} for a name group of records without a creature key, or {@code (task)}; the id only as a last resort,
	 * when that still leaves two the same.
	 */
	private static String label(Group group, List<Group> plotted)
	{
		String name = group.name.get();
		Map<String, Integer> kinds = new HashMap<>();
		boolean shared = false;
		for (Group other : plotted)
		{
			if (other.name.get().equalsIgnoreCase(name))
			{
				shared |= other != group;
				kinds.merge(kind(other.key), 1, Integer::sum);
			}
		}
		if (!shared)
		{
			return name;
		}
		String kind = kind(group.key);
		Integer creatureId = group.key.getCreatureId();
		if (kinds.get(kind) > 1 && creatureId != null)
		{
			return name + " (" + kind + " " + (creatureId < SessionTracker.NO_CREATURE ? -2 - creatureId : creatureId)
				+ ")";
		}
		return name + " (" + kind + ")";
	}

	private static String kind(TaskRanking.TaskKey key)
	{
		Integer creatureId = key.getCreatureId();
		if (creatureId == null)
		{
			return "older records";
		}
		return creatureId < SessionTracker.NO_CREATURE ? "boss" : "task";
	}

	/** The task's metric total as {@link TaskRanking} counts it, or {@code null} when the task has no such data. */
	private static MetricValue metricValue(LedgerRecord record, TaskRanking.Metric metric, LootPriceMode mode,
		Map<Integer, Long> currentGe, HiddenLoot hidden)
	{
		switch (metric)
		{
			case PROFIT:
			case PROFIT_PER_KILL:
				if (!LedgerHistory.isValued(record))
				{
					return null;
				}
				HistoryPricing.Values values = HistoryPricing.value(record, mode, currentGe, hidden);
				// As TaskRanking: a valued record counts only with both a loot and a supplies value.
				if (values.getLootValue() == null || values.getSuppliesGe() == null || values.getProfit() == null)
				{
					return null;
				}
				return new MetricValue(values.getProfit(), values.isProfitApproximate());
			case XP:
				Map<String, Long> xp = record.getXpGained();
				if (xp == null)
				{
					return null;
				}
				// XP recorded without a Slayer entry means no Slayer XP was gained.
				Long slayer = xp.get(SkillXp.SLAYER);
				return new MetricValue(slayer == null ? 0 : Math.max(0, slayer), false);
			case POINTS:
				return TaskRanking.countsForPointsRate(record)
					? new MetricValue(record.getPointsEarned(), false) : null;
			default:
				return null;
		}
	}

	private static final class Group
	{
		private final TaskRanking.TaskKey key;
		private final TaskRanking.GroupName name = new TaskRanking.GroupName();
		private final List<Point> points = new ArrayList<>();

		private Group(TaskRanking.TaskKey key)
		{
			this.key = key;
		}
	}

	static final class Monster
	{
		private final TaskRanking.TaskKey key;
		private final String label;
		/** The assigned task name of the record that names the group, for its icon; not part of equality. */
		private final String task;

		private Monster(TaskRanking.TaskKey key, String label, String task)
		{
			this.key = key;
			this.label = label;
			this.task = task;
		}

		/**
		 * A choice that only sizes the Monster box ({@code JComboBox#setPrototypeDisplayValue}): never in its list,
		 * never compared or hashed (it has no key).
		 */
		static Monster prototype(String label)
		{
			return new Monster(null, label, null);
		}

		String getLabel()
		{
			return label;
		}

		/**
		 * The assigned task name ({@link TaskRanking.GroupName#getTask}): a Catacombs variant or cannon group's task,
		 * whose icon the Monster choice shows; {@code null} when it had none.
		 */
		String getTask()
		{
			return task;
		}

		TaskRanking.TaskKey getKey()
		{
			return key;
		}

		@Override
		public String toString()
		{
			return label;
		}

		@Override
		public boolean equals(Object other)
		{
			return other instanceof Monster && Objects.equals(key, ((Monster) other).key);
		}

		@Override
		public int hashCode()
		{
			return Objects.hashCode(key);
		}
	}

	static final class Point
	{
		private final long endedAt;
		private final long ratePerHour;
		private final boolean approximate;
		private final String note;
		private final String recordId;

		private Point(long endedAt, long ratePerHour, boolean approximate, String note, String recordId)
		{
			this.endedAt = endedAt;
			this.ratePerHour = ratePerHour;
			this.approximate = approximate;
			this.note = note;
			this.recordId = recordId;
		}

		long getEndedAt()
		{
			return endedAt;
		}

		/** The task's rate per hour, or its value per kill for a per-kill metric. */
		long getRatePerHour()
		{
			return ratePerHour;
		}

		boolean isApproximate()
		{
			return approximate;
		}

		String getNote()
		{
			return note;
		}

		String getRecordId()
		{
			return recordId;
		}
	}

	private static final class MetricValue
	{
		private final long value;
		private final boolean approximate;

		private MetricValue(long value, boolean approximate)
		{
			this.value = value;
			this.approximate = approximate;
		}
	}
}
