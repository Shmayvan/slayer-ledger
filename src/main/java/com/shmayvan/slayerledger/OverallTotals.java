package com.shmayvan.slayerledger;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

/**
 * Overall and grouped totals from completed played-task records. XP is the total of every skill in a record's
 * {@code xpGained} ({@link SkillXp#total}; owner decision), not only Slayer XP. Each group also sums its tasks'
 * supplies and loot item by item ({@link ItemSums}), from the rows their history details show
 * ({@link ItemDetails}) at the same prices as the profit; a task saved without item data adds none.
 *
 * <p>Each group also has the figures the By monster and By master groupings show: kills, averages per
 * task, profit per kill and per hour, Slayer XP and Slayer points. The rates follow the Trends rules
 * ({@link TaskRanking}): pooled over the group's task samples ({@link TaskRanking#isTaskSample}) that recorded the
 * figure, an hourly rate only from {@link TaskTimeEstimate#MIN_ACTIVE_MILLIS} of their active time, and Slayer XP only
 * (the {@link SkillXp#SLAYER} entry). A task that didn't record a figure is left out of it rather than counted as 0.
 * By master groups also count the master's assignments per monster ({@link Assignment}): its completed played tasks
 * plus the assignment log's entries ({@link AssignmentEntry}: skipped, blocked, reset, untracked and other ended
 * assignments).</p>
 */
final class OverallTotals
{
	enum Grouping
	{
		ALL("Overall"),
		MASTER("By master"),
		MONTH("By month"),
		/** One group per monster as the history names it ({@link LedgerRecord#groupName}). */
		MONSTER("By monster");

		private final String label;

		Grouping(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	static final String UNKNOWN_MASTER = "Unknown master";
	static final String UNKNOWN_DATE = "Unknown date";

	/** Sort rank of a group: named groups first, then masters with an unnamed id, then no master or date. */
	private static final int NAMED = 0;
	private static final int UNNAMED_ID = 1;
	private static final int UNKNOWN = 2;

	/** Most assigned first, then by name ignoring case, then by name. */
	private static final Comparator<Assignment> ASSIGNMENT_ORDER = Comparator
		.comparingInt(Assignment::getCount).reversed()
		.thenComparing(Assignment::getName, String.CASE_INSENSITIVE_ORDER)
		.thenComparing(Assignment::getName);

	private OverallTotals()
	{
	}

	/**
	 * Aggregates completed records, leaving profit and XP unknown unless the record captured them. Monthly groups use
	 * each task's local completion month; records without an end time are kept in an unknown-date group. Items are
	 * summed in the order of {@code records} (the history's: newest first), which picks the unit prices a summed row's
	 * tooltip shows ({@link ItemSums}).
	 *
	 * @param currentGe today's GE unit prices the records are valued at ({@link HistoryPricing}); never {@code null}
	 */
	static List<Entry> summarize(Collection<LedgerRecord> records, Grouping grouping, LootPriceMode mode,
		Map<Integer, Long> currentGe, ZoneId zone)
	{
		return summarize(records, Collections.emptyList(), grouping, mode, currentGe, HiddenLoot.NONE, zone);
	}

	/**
	 * As {@link #summarize(Collection, Grouping, LootPriceMode, Map, ZoneId)}, with the loot items {@code hidden} hides
	 * left out of the profit ({@link HistoryPricing}). The summed loot items still list them; the panel leaves them out
	 * of its grid.
	 */
	static List<Entry> summarize(Collection<LedgerRecord> records, Grouping grouping, LootPriceMode mode,
		Map<Integer, Long> currentGe, HiddenLoot hidden, ZoneId zone)
	{
		return summarize(records, Collections.emptyList(), grouping, mode, currentGe, hidden, zone);
	}

	/**
	 * As {@link #summarize(Collection, Grouping, LootPriceMode, Map, HiddenLoot, ZoneId)}; By master groups also count
	 * their assignments per monster ({@link Entry#getAssignments}) from the completed records and {@code assignments},
	 * the assignment log's entries. Only masters with a group (a completed played task) are counted.
	 */
	static List<Entry> summarize(Collection<LedgerRecord> records, Collection<AssignmentEntry> assignments,
		Grouping grouping, LootPriceMode mode, Map<Integer, Long> currentGe, HiddenLoot hidden, ZoneId zone)
	{
		Objects.requireNonNull(currentGe, "currentGe");
		if (records == null || records.isEmpty() || grouping == null)
		{
			return Collections.emptyList();
		}
		ZoneId localZone = zone == null ? ZoneId.systemDefault() : zone;
		Map<String, Aggregate> groups = new HashMap<>();
		for (LedgerRecord record : records)
		{
			if (!isCompletedTask(record))
			{
				continue;
			}
			String key = groupKey(record, grouping, localZone);
			int rank = groupRank(record, grouping);
			// By monster: keyed by the normalized name, labelled as the first (newest) record names it, and its icon is
			// that record's assigned task's, so a Catacombs variant or cannon task shows its task's icon.
			String label = grouping == Grouping.MONSTER ? monsterLabel(record) : key;
			String task = grouping == Grouping.MONSTER ? record.getMonster() : null;
			Aggregate aggregate = groups.computeIfAbsent(key, k -> new Aggregate(label, rank, task));
			aggregate.add(record, mode, currentGe, hidden);
		}
		if (grouping == Grouping.MASTER)
		{
			countAssignments(records, assignments, groups);
		}

		List<Entry> entries = new ArrayList<>();
		for (Map.Entry<String, Aggregate> group : groups.entrySet())
		{
			entries.add(group.getValue().toEntry());
		}
		entries.sort(comparator(grouping));
		return Collections.unmodifiableList(entries);
	}

	private static boolean isCompletedTask(LedgerRecord record)
	{
		return record != null && record.isCompleted() && !record.isPartial() && record.getKills() > 0;
	}

	private static String groupKey(LedgerRecord record, Grouping grouping, ZoneId zone)
	{
		switch (grouping)
		{
			case MASTER:
				return masterKey(record.getSlayerMaster());
			case MONTH:
				return record.getEndedAt() <= 0 ? UNKNOWN_DATE
					: YearMonth.from(Instant.ofEpochMilli(record.getEndedAt()).atZone(zone)).toString();
			case MONSTER:
				return TaskStatistics.normalize(monsterLabel(record));
			default:
				return "Overall";
		}
	}

	/**
	 * The By monster name of a record: the name the history shows and groups it under
	 * ({@link LedgerRecord#groupName}, with its Catacombs variant and cannon use), trimmed;
	 * {@link LedgerHistory#UNKNOWN_MONSTER} when blank.
	 */
	private static String monsterLabel(LedgerRecord record)
	{
		String name = LedgerHistory.monster(record).trim();
		return name.isEmpty() ? LedgerHistory.UNKNOWN_MONSTER : name;
	}

	/** The By master group of a SLAYER_MASTER value: its name, "Unknown master (N)" or {@link #UNKNOWN_MASTER}. */
	private static String masterKey(Integer master)
	{
		String masterName = SlayerMaster.displayName(master);
		return masterName == null ? UNKNOWN_MASTER : masterName;
	}

	private static int groupRank(LedgerRecord record, Grouping grouping)
	{
		switch (grouping)
		{
			case MASTER:
				if (SlayerMaster.hasName(record.getSlayerMaster()))
				{
					return NAMED;
				}
				return SlayerMaster.displayName(record.getSlayerMaster()) == null ? UNKNOWN : UNNAMED_ID;
			case MONTH:
				return record.getEndedAt() <= 0 ? UNKNOWN : NAMED;
			default:
				return NAMED;
		}
	}

	/**
	 * Months newest first and masters by name ignoring case; in both, unknown groups come last ("Unknown master (N)"
	 * groups, then "Unknown master"; "Unknown date"). Monsters by task count, most first, then by name ignoring case.
	 */
	private static Comparator<Entry> comparator(Grouping grouping)
	{
		Comparator<Entry> byRank = Comparator.comparingInt(entry -> entry.rank);
		if (grouping == Grouping.MONTH)
		{
			// "yyyy-MM" labels sort by date as text.
			return byRank.thenComparing(Entry::getLabel, Comparator.reverseOrder());
		}
		if (grouping == Grouping.MONSTER)
		{
			return Comparator.comparingInt(Entry::getTaskCount).reversed()
				.thenComparing(Entry::getLabel, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(Entry::getLabel);
		}
		return byRank.thenComparing(Entry::getLabel, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(Entry::getLabel);
	}

	/**
	 * Counts each By master group's assignments per monster: every completed task the groups count
	 * ({@link #isCompletedTask}, so a completed record without kills is not one) and every assignment log entry,
	 * under its master. A task is counted under its assigned name ({@link LedgerRecord#getMonster}), so a Catacombs
	 * variant or cannon task counts as its task. Entries and records of a master without a group are left out.
	 */
	private static void countAssignments(Collection<LedgerRecord> records, Collection<AssignmentEntry> assignments,
		Map<String, Aggregate> groups)
	{
		for (LedgerRecord record : records)
		{
			if (isCompletedTask(record))
			{
				Aggregate group = groups.get(masterKey(record.getSlayerMaster()));
				if (group != null)
				{
					group.assignments.add(record.getMonster(), null);
				}
			}
		}
		if (assignments == null)
		{
			return;
		}
		for (AssignmentEntry entry : assignments)
		{
			if (entry != null)
			{
				Aggregate group = groups.get(masterKey(entry.getSlayerMaster()));
				if (group != null)
				{
					group.assignments.add(entry.getMonster(), AssignmentOutcome.orUnknown(entry.getOutcome()));
				}
			}
		}
	}

	/**
	 * {@code 12 / 80 (15%)}: a monster's assignments out of the master's. The share is rounded to a whole percent
	 * (half up); a share that rounds to 0 shows as {@code <1%}.
	 */
	static String shareText(int count, int total)
	{
		return count + " / " + total + " (" + percentText(count, total) + ")";
	}

	private static String percentText(int count, int total)
	{
		if (total <= 0)
		{
			return LedgerHistory.NO_VALUE;
		}
		long percent = Math.round(count * 100d / total);
		return percent == 0 && count > 0 ? "<1%" : percent + "%";
	}

	/** {@code total / n} rounded, or {@code null} without samples. */
	private static Long average(long total, long n)
	{
		return n <= 0 ? null : Math.round(total / (double) n);
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

	/**
	 * One monster in a By master group's assignments: how often the master assigned it, of how many, and how
	 * they ended. Immutable; equal when every field is.
	 */
	@Value
	static class Assignment
	{
		/** The task's name as the newest of them names it. */
		String name;
		int count;
		/** All the master's assignments counted. */
		int total;
		/** Assignments played to completion with a saved task the groups count (with kills). */
		int completed;
		/** Assignment log entries by outcome; unmodifiable, without zero counts. */
		Map<AssignmentOutcome, Integer> outcomes;

		/** {@code 12 / 80 (15%)} ({@link #shareText}). */
		String text()
		{
			return shareText(count, total);
		}

		/**
		 * HTML tooltip: the name, the count of the master's assignments with the exact share, then how they ended
		 * ({@code Completed: 8}, then each outcome of the log's entries, as their rows label them).
		 */
		String tooltip(String master)
		{
			StringBuilder sb = new StringBuilder("<html>").append(LedgerHistory.escapeHtml(name))
				.append("<br>").append(count).append(" of ").append(total)
				.append(total == 1 ? " assignment" : " assignments").append(" from ")
				.append(LedgerHistory.escapeHtml(master));
			if (total > 0)
			{
				sb.append(String.format(Locale.ROOT, " (%.1f%%)", count * 100d / total));
			}
			if (completed > 0)
			{
				sb.append("<br>Completed: ").append(completed);
			}
			for (Map.Entry<AssignmentOutcome, Integer> outcome : outcomes.entrySet())
			{
				sb.append("<br>").append(outcome.getKey().getLabel()).append(": ").append(outcome.getValue());
			}
			return sb.append("</html>").toString();
		}
	}

	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	static final class Entry
	{
		private final String label;
		/**
		 * By monster: the assigned task name ({@link LedgerRecord#getMonster}) of the group's newest task, whose icon
		 * ({@link TaskIcons}) the group shows; {@code null} in other groupings.
		 */
		private final String task;
		private final int rank;
		private final int taskCount;
		private final long activeMillis;
		private final Long profit;
		private final int profitSampleCount;
		private final boolean profitApproximate;
		private final Long xp;
		private final int xpSampleCount;
		/** Summed supplies, unnamed and unsorted ({@link ItemSums#rows()}); empty without item data. */
		private final List<ItemDetails.Row> supplies;
		/** Summed loot, unnamed, unsorted and without the alch hint ({@link ItemSums#rows()}). */
		private final List<ItemDetails.Row> loot;
		private final long kills;
		private final Long millisPerKill;
		private final Long profitPerKill;
		private final Long profitPerHour;
		private final boolean profitRateApproximate;
		private final Long slayerXp;
		private final int slayerXpSampleCount;
		private final Long slayerXpPerHour;
		private final Long points;
		private final int pointsSampleCount;
		/** Points of the tasks with recorded points that are not bonus (milestone) tasks, for the average. */
		private final long regularPoints;
		private final int regularPointsSampleCount;
		/** By master: the master's assignments per monster, most first; empty otherwise. */
		private final List<Assignment> assignments;
		private final int assignmentTotal;

		String getLabel()
		{
			return label;
		}

		/**
		 * By monster: the assigned task name of the group's newest task, without its Catacombs variant or cannon use
		 *, for its icon ({@link TaskIcons}); {@code null} in other groupings or when the task had no name.
		 */
		String getTask()
		{
			return task;
		}

		int getTaskCount()
		{
			return taskCount;
		}

		long getActiveMillis()
		{
			return activeMillis;
		}

		/** Average active time per task. */
		long getAverageActiveMillis()
		{
			return taskCount <= 0 ? 0 : activeMillis / taskCount;
		}

		Long getProfit()
		{
			return profit;
		}

		int getProfitSampleCount()
		{
			return profitSampleCount;
		}

		boolean isProfitApproximate()
		{
			return profitApproximate;
		}

		/** Average profit per task over the {@link #getProfitSampleCount} tasks that have one, or {@code null}. */
		Long getAverageProfit()
		{
			return profit == null ? null : average(profit, profitSampleCount);
		}

		/** XP of every skill ({@link SkillXp#total}) over the records with {@code xpGained}, or {@code null}. */
		Long getXp()
		{
			return xp;
		}

		int getXpSampleCount()
		{
			return xpSampleCount;
		}

		/**
		 * The group's supplies summed item by item, valued at GE prices as the profit counts them; not named, so name
		 * them on the EDT ({@link ItemDetails#named}). Empty when none of its tasks used any or has item data.
		 */
		List<ItemDetails.Row> getSupplies()
		{
			return supplies == null ? Collections.emptyList() : supplies;
		}

		/**
		 * The group's loot summed item by item, in the Loot price mode as the profit counts it; not named and without
		 * the alch hint ({@link ItemDetails#named}). Empty when none of its tasks has any.
		 */
		List<ItemDetails.Row> getLoot()
		{
			return loot == null ? Collections.emptyList() : loot;
		}

		/** Task kills of all the group's tasks. */
		long getKills()
		{
			return kills;
		}

		/** Active time per kill over the group's task samples, or {@code null} without any. */
		Long getMillisPerKill()
		{
			return millisPerKill;
		}

		/**
		 * Profit per kill as Trends pools it: the profit of the task samples that have one over their kills, or
		 * {@code null} without any.
		 */
		Long getProfitPerKill()
		{
			return profitPerKill;
		}

		/**
		 * Profit per hour as Trends pools it: the profit of the task samples that have one over their active time, or
		 * {@code null} under {@link TaskTimeEstimate#MIN_ACTIVE_MILLIS} of it.
		 */
		Long getProfitPerHour()
		{
			return profitPerHour;
		}

		/** Whether {@link #getProfitPerKill} and {@link #getProfitPerHour} are approximate. */
		boolean isProfitRateApproximate()
		{
			return profitRateApproximate;
		}

		/** Slayer XP only, over the tasks with recorded XP, or {@code null} when none has any. */
		Long getSlayerXp()
		{
			return slayerXp;
		}

		int getSlayerXpSampleCount()
		{
			return slayerXpSampleCount;
		}

		/** Average Slayer XP per task over {@link #getSlayerXpSampleCount} tasks, or {@code null}. */
		Long getAverageSlayerXp()
		{
			return slayerXp == null ? null : average(slayerXp, slayerXpSampleCount);
		}

		/**
		 * Slayer XP per hour as Trends pools it (the task samples with XP over their active time), or {@code null}
		 * under {@link TaskTimeEstimate#MIN_ACTIVE_MILLIS} of it.
		 */
		Long getSlayerXpPerHour()
		{
			return slayerXpPerHour;
		}

		/** Slayer points of the tasks that recorded them (bonus tasks included), or {@code null} when none did. */
		Long getPoints()
		{
			return points;
		}

		int getPointsSampleCount()
		{
			return pointsSampleCount;
		}

		/**
		 * Average points per task over the {@link #getAveragePointsSampleCount} tasks with recorded points that are not
		 * bonus tasks ({@link StreakMilestone#isMilestone}, as Points/h leaves them out), or {@code null} without any.
		 * {@link #getPoints} still counts the bonus tasks.
		 */
		Long getAveragePoints()
		{
			return average(regularPoints, regularPointsSampleCount);
		}

		/** The tasks {@link #getAveragePoints} averages over. */
		int getAveragePointsSampleCount()
		{
			return regularPointsSampleCount;
		}

		/** By master: each monster's assignments, most first ({@link Assignment}); empty in other groupings. */
		List<Assignment> getAssignments()
		{
			return assignments == null ? Collections.emptyList() : assignments;
		}

		/** By master: all the master's assignments counted; 0 in other groupings. */
		int getAssignmentTotal()
		{
			return assignmentTotal;
		}
	}

	/** A pooled rate's totals, as {@link TaskRanking} pools a metric: its total, active time and kills. */
	private static final class Pool
	{
		private long total;
		private long activeMillis;
		private long kills;
		private boolean approximate;

		private void add(long value, LedgerRecord record, boolean isApproximate)
		{
			total = saturatedAdd(total, value);
			activeMillis = saturatedAdd(activeMillis, record.getActiveMillis());
			kills = saturatedAdd(kills, record.getKills());
			approximate |= isApproximate;
		}

		private Long perHour()
		{
			return activeMillis <= 0 ? null : TaskRanking.perHour(total, activeMillis);
		}
	}

	/** A By master group's assignments per monster, by normalized name. */
	private static final class AssignmentTally
	{
		private final Map<String, Counts> monsters = new LinkedHashMap<>();
		private int total;

		/** Counts one assignment of {@code monster}: a completed task ({@code outcome} {@code null}) or a log entry. */
		private void add(String monster, AssignmentOutcome outcome)
		{
			String name = monster == null || TaskStatistics.normalize(monster) == null
				? LedgerHistory.UNKNOWN_MONSTER : monster.trim();
			Counts counts = monsters.computeIfAbsent(TaskStatistics.normalize(name), k -> new Counts(name));
			counts.count++;
			if (outcome == null)
			{
				counts.completed++;
			}
			else
			{
				counts.outcomes.merge(outcome, 1, Integer::sum);
			}
			total++;
		}

		private List<Assignment> rows()
		{
			List<Assignment> rows = new ArrayList<>();
			for (Counts counts : monsters.values())
			{
				rows.add(new Assignment(counts.name, counts.count, total, counts.completed,
					Collections.unmodifiableMap(new EnumMap<>(counts.outcomes))));
			}
			rows.sort(ASSIGNMENT_ORDER);
			return Collections.unmodifiableList(rows);
		}

		private static final class Counts
		{
			private final String name;
			private int count;
			private int completed;
			private final Map<AssignmentOutcome, Integer> outcomes = new EnumMap<>(AssignmentOutcome.class);

			private Counts(String name)
			{
				this.name = name;
			}
		}
	}

	private static final class Aggregate
	{
		private final String label;
		private final int rank;
		/** By monster: the newest task's assigned name ({@link Entry#getTask}); {@code null} otherwise. */
		private final String task;
		private int taskCount;
		private long activeMillis;
		private long profit;
		private int profitSampleCount;
		private boolean profitApproximate;
		private long xp;
		private int xpSampleCount;
		private final ItemSums supplies = new ItemSums();
		private final ItemSums loot = new ItemSums();
		private long kills;
		/** The task samples' time and kills, for the time per kill. */
		private final Pool time = new Pool();
		private final Pool profitRate = new Pool();
		private long slayerXp;
		private int slayerXpSampleCount;
		private final Pool slayerXpRate = new Pool();
		private long points;
		private int pointsSampleCount;
		private long regularPoints;
		private int regularPointsSampleCount;
		private final AssignmentTally assignments = new AssignmentTally();

		private Aggregate(String label, int rank, String task)
		{
			this.label = label;
			this.rank = rank;
			this.task = task;
		}

		private void add(LedgerRecord record, LootPriceMode mode, Map<Integer, Long> currentGe, HiddenLoot hidden)
		{
			taskCount++;
			activeMillis = saturatedAdd(activeMillis, Math.max(0, record.getActiveMillis()));
			kills = saturatedAdd(kills, record.getKills());
			// The rates pool only the task samples, as Trends does (a task with no active time adds none).
			boolean sample = TaskRanking.isTaskSample(record);
			if (sample)
			{
				time.add(0, record, false);
			}

			if (LedgerHistory.isValued(record))
			{
				HistoryPricing.Values values = HistoryPricing.value(record, mode, currentGe, hidden);
				if (values.getProfit() != null)
				{
					profit = saturatedAdd(profit, values.getProfit());
					profitSampleCount++;
					profitApproximate |= values.isProfitApproximate();
					if (sample)
					{
						profitRate.add(values.getProfit(), record, values.isProfitApproximate());
					}
				}
			}

			Map<String, Long> recordXp = record.getXpGained();
			if (recordXp != null)
			{
				xp = saturatedAdd(xp, SkillXp.total(recordXp));
				xpSampleCount++;
				// As Trends: XP recorded without a Slayer entry means no Slayer XP was gained.
				Long slayer = recordXp.get(SkillXp.SLAYER);
				long slayerValue = slayer == null ? 0 : Math.max(0, slayer);
				slayerXp = saturatedAdd(slayerXp, slayerValue);
				slayerXpSampleCount++;
				if (sample)
				{
					slayerXpRate.add(slayerValue, record, false);
				}
			}

			Integer earned = record.getPointsEarned();
			if (earned != null && earned >= 0)
			{
				points = saturatedAdd(points, earned);
				pointsSampleCount++;
				// The average leaves out bonus tasks, whose points would swamp it.
				if (!StreakMilestone.isMilestone(record.getTaskStreak()))
				{
					regularPoints = saturatedAdd(regularPoints, earned);
					regularPointsSampleCount++;
				}
			}

			addItems(record, mode, currentGe);
		}

		/**
		 * Adds the record's supplies and loot as its history details show them (at the same prices: today's GE prices,
		 * as {@link HistoryPricing} values the profit); nothing without item data.
		 */
		private void addItems(LedgerRecord record, LootPriceMode mode, Map<Integer, Long> currentGe)
		{
			ItemDetails items = ItemDetails.of(record);
			if (items == null)
			{
				return;
			}
			items = items.withGePrices(currentGe);
			supplies.add(items.supplyRows(null));
			loot.add(items.lootRows(mode, null));
		}

		private Entry toEntry()
		{
			return Entry.builder()
				.label(label)
				.task(task)
				.rank(rank)
				.taskCount(taskCount)
				.activeMillis(activeMillis)
				.profit(profitSampleCount == 0 ? null : profit)
				.profitSampleCount(profitSampleCount)
				.profitApproximate(profitApproximate)
				.xp(xpSampleCount == 0 ? null : xp)
				.xpSampleCount(xpSampleCount)
				.supplies(supplies.rows())
				.loot(loot.rows())
				.kills(kills)
				.millisPerKill(TaskRanking.perKill(time.activeMillis, time.kills))
				.profitPerKill(TaskRanking.perKill(profitRate.total, profitRate.kills))
				.profitPerHour(profitRate.perHour())
				.profitRateApproximate(profitRate.approximate)
				.slayerXp(slayerXpSampleCount == 0 ? null : slayerXp)
				.slayerXpSampleCount(slayerXpSampleCount)
				.slayerXpPerHour(slayerXpRate.perHour())
				.points(pointsSampleCount == 0 ? null : points)
				.pointsSampleCount(pointsSampleCount)
				.regularPoints(regularPoints)
				.regularPointsSampleCount(regularPointsSampleCount)
				.assignments(assignments.rows())
				.assignmentTotal(assignments.total)
				.build();
		}
	}
}
