package com.shmayvan.slayerledger;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * The advisor card's badge for the current task: Do, Consider skip or Consider block, from how the monster's rate
 * on the goal metric compares with the player's other monsters'. Advice only. Pure: no RuneLite types.
 *
 * <p>The rates are {@link TaskRanking}'s (records only, completed and not
 * partial, a rate from 1 minute of active time, Points/h only from tasks tracked from the start of the assignment and
 * not bonus tasks). Whole-monster history, not the current location's, as the rankings. How often a task is assigned
 * is not used (owner decision).</p>
 *
 * <p><b>Qualifying monsters</b> (owner decision): a monster takes part only with a rate on the metric from
 * at least {@link #MIN_TASKS} tasks with that metric and at least {@link #MIN_ACTIVE_MILLIS} of their pooled active
 * time. One that falls short gets no advice and does not count in the ranking.</p>
 *
 * <p><b>Pools</b>: boss tasks ({@link TaskType#BOSS}, creature keys below {@link SessionTracker#NO_CREATURE})
 * are ranked only against boss tasks, and every other monster (Jad and Zuk included, which are Regular tasks, and
 * older records grouped by name) only against the others.</p>
 *
 * <p><b>Position from the bottom:</b> among the {@code m} qualifying monsters of the current monster's pool, its
 * position {@code p} is the number of them whose rate is <em>at or below</em> its own (itself included), so tied
 * monsters share the highest position of their tie and a tie is never pushed down. It is in the bottom 10% when
 * {@code p <= m * 10%} and in the bottom 25% when {@code p <= m * 25%}: bottom 10% is "Consider block", otherwise
 * bottom 25% is "Consider skip", otherwise "Do". So under 10 monsters in the pool none is "Consider block" (from 10
 * the lowest is), and with 6 or 7 only the lowest can be "Consider skip" (from 8 the two lowest). "Consider block"
 * also needs at least {@link #MIN_BLOCK_TASKS} of the monster's own tasks; with fewer it is "Consider skip".</p>
 *
 * <p><b>Minimum data</b> (owner decision): the current monster qualifies and at least {@link #MIN_MONSTERS} monsters
 * of its pool, itself included, qualify; otherwise no advice.</p>
 *
 * <p><b>Catacombs variants and cannon</b> (owner decision): the advice pools a task's standard and
 * Catacombs variant records ({@link CatacombsVariants}) and its tasks with and without a cannon ({@link CannonTasks}),
 * as before. The 1.x "Better:" line, which compared the versions and the tasks with and without a cannon, went with
 * the advisor card: its options are split by spot and cannon use, and its counterpart is the best
 * setup with the other cannon choice ({@link TaskPlanner}).</p>
 *
 * <p><b>Metric</b> (V2 plan 6): the badge follows the advisor's metric ({@link AdvisorMetric}): it ranks by
 * Slayer XP per hour under {@link AdvisorMetric#FASTEST_FINISH} and by profit per hour under the others
 * ({@link #badgeMetric}). The methods that take a {@link TaskRanking.Metric} rank by that rate; the plugin's views pass
 * them {@link #badgeMetric} of the "Advisor metric" setting (the 1.x "Task advisor goal" setting is gone).</p>
 */
final class TaskAdvisor
{
	/** Qualifying monsters in the current monster's pool, itself included, needed for any advice. */
	static final int MIN_MONSTERS = 6;
	/** Tasks with the metric a monster needs to qualify. */
	static final int MIN_TASKS = 3;
	/** Pooled active time on the metric a monster needs to qualify: 30 minutes. */
	static final long MIN_ACTIVE_MILLIS = 30 * 60_000L;
	/** The monster's own tasks needed for "Consider block"; with fewer it is "Consider skip". */
	static final int MIN_BLOCK_TASKS = 5;

	enum Advice
	{
		DO("Do"),
		CONSIDER_SKIP("Consider skip"),
		CONSIDER_BLOCK("Consider block");

		private final String displayName;

		Advice(String displayName)
		{
			this.displayName = displayName;
		}

		@Override
		public String toString()
		{
			return displayName;
		}
	}

	private TaskAdvisor()
	{
	}

	/**
	 * The rate the badge ranks by under the advisor's {@code metric}: Slayer XP per hour for
	 * {@link AdvisorMetric#FASTEST_FINISH} (and {@code null}, its default), profit per hour for the others.
	 */
	static TaskRanking.Metric badgeMetric(AdvisorMetric metric)
	{
		return AdvisorMetric.orDefault(metric) == AdvisorMetric.FASTEST_FINISH ? TaskRanking.Metric.XP
			: TaskRanking.Metric.PROFIT;
	}

	/**
	 * The advice for the current task ({@code creatureId}, {@code monster}) from {@code records} on {@code metric},
	 * valued as {@link TaskRanking#rank} values it; {@code null} without enough data.
	 */
	static Result advise(Collection<LedgerRecord> records, int creatureId, String monster, TaskRanking.Metric metric,
		LootPriceMode mode, Map<Integer, Long> currentGe)
	{
		return advise(records, creatureId, monster, metric, mode, currentGe, HiddenLoot.NONE);
	}

	/**
	 * As {@link #advise(Collection, int, String, TaskRanking.Metric, LootPriceMode, Map)}, with the loot items
	 * {@code hidden} hides left out of the profit.
	 */
	static Result advise(Collection<LedgerRecord> records, int creatureId, String monster, TaskRanking.Metric metric,
		LootPriceMode mode, Map<Integer, Long> currentGe, HiddenLoot hidden)
	{
		if (records == null || records.isEmpty() || metric == null)
		{
			return null;
		}
		TaskRanking.TaskKey current = TaskRanking.currentTaskKey(records, creatureId, monster);
		// The task's Catacombs variant and cannon records are pooled with its others here, as before.
		return current == null ? null
			: advise(TaskRanking.rank(records, metric, mode, currentGe, hidden), current, metric);
	}

	/** The advice for the monster {@code current} in a {@link TaskRanking#rank} ranking; {@code null} without data. */
	static Result advise(List<TaskRanking.Entry> ranking, TaskRanking.TaskKey current, TaskRanking.Metric metric)
	{
		boolean boss = isBoss(current);
		TaskRanking.Entry own = null;
		int monsters = 0;
		for (TaskRanking.Entry entry : ranking)
		{
			if (!qualifies(entry) || isBoss(entry.getKey()) != boss)
			{
				continue;
			}
			monsters++;
			if (entry.getKey().equals(current))
			{
				own = entry;
			}
		}
		if (own == null || monsters < MIN_MONSTERS)
		{
			return null;
		}
		long rate = own.getRatePerHour();
		int position = 0;
		for (TaskRanking.Entry entry : ranking)
		{
			if (qualifies(entry) && isBoss(entry.getKey()) == boss && entry.getRatePerHour() <= rate)
			{
				position++;
			}
		}
		Advice advice = classify(position, monsters);
		boolean blockNeedsTasks = advice == Advice.CONSIDER_BLOCK && own.getSampleCount() < MIN_BLOCK_TASKS;
		if (blockNeedsTasks)
		{
			advice = Advice.CONSIDER_SKIP;
		}
		return Result.builder()
			.advice(advice)
			.metric(metric)
			.ratePerHour(rate)
			.approximate(own.isApproximate())
			.position(position)
			.monsters(monsters)
			.tasks(own.getSampleCount())
			.boss(boss)
			.blockNeedsTasks(blockNeedsTasks)
			.build();
	}

	/**
	 * Whether {@code entry} takes part in the advice: a rate from at least {@link #MIN_TASKS} tasks with the
	 * metric and {@link #MIN_ACTIVE_MILLIS} of their pooled active time.
	 */
	static boolean qualifies(TaskRanking.Entry entry)
	{
		return entry.getRatePerHour() != null && entry.getSampleCount() >= MIN_TASKS
			&& entry.getActiveMillis() >= MIN_ACTIVE_MILLIS;
	}

	/** Whether the group is a boss task's ({@link TaskType#BOSS}); a name group (no creature key) is not. */
	static boolean isBoss(TaskRanking.TaskKey key)
	{
		return key.getCreatureId() != null && TaskType.of(null, key.getCreatureId()) == TaskType.BOSS;
	}

	/**
	 * The advice for position {@code position} from the bottom (1 is the lowest) of {@code monsters}: bottom 10%
	 * ({@code position <= monsters / 10}, not rounded) is "Consider block", bottom 25% "Consider skip", else "Do".
	 */
	static Advice classify(int position, int monsters)
	{
		if (position * 10L <= monsters)
		{
			return Advice.CONSIDER_BLOCK;
		}
		if (position * 4L <= monsters)
		{
			return Advice.CONSIDER_SKIP;
		}
		return Advice.DO;
	}

	/** "1st", "2nd", "3rd", "4th", "11th", "21st". */
	static String ordinal(int n)
	{
		int lastTwo = n % 100;
		if (lastTwo >= 11 && lastTwo <= 13)
		{
			return n + "th";
		}
		switch (n % 10)
		{
			case 1:
				return n + "st";
			case 2:
				return n + "nd";
			case 3:
				return n + "rd";
			default:
				return n + "th";
		}
	}

	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	static final class Result
	{
		private final Advice advice;
		private final TaskRanking.Metric metric;
		private final long ratePerHour;
		private final boolean approximate;
		private final int position;
		private final int monsters;
		private final int tasks;
		private final boolean boss;
		private final boolean blockNeedsTasks;

		Advice getAdvice()
		{
			return advice;
		}

		long getRatePerHour()
		{
			return ratePerHour;
		}

		/** The position from the bottom: how many monsters' rates are at or below this one's (itself included). */
		int getPosition()
		{
			return position;
		}

		/** Qualifying monsters in this one's pool (boss or not), this one included. */
		int getMonsters()
		{
			return monsters;
		}

		/** The monster's tasks with the metric: the sample size {@code n}. */
		int getTasks()
		{
			return tasks;
		}

		/** Whether this is a boss task, ranked only against boss tasks. */
		boolean isBoss()
		{
			return boss;
		}

		/** {@code Advice: Consider skip (n=3)}, or {@code Advice: Do} (no sample size, to keep it short). */
		String text()
		{
			return "Advice: " + advice + (advice == Advice.DO ? "" : " (n=" + tasks + ")");
		}

		/**
		 * Why: {@code Profit (GE)/h 210K: 5th lowest of your 24 monsters (bottom 25%)}, the metric named by
		 * {@link TaskRanking.Metric#label}, the rate compact with {@code ~} when some prices were unavailable. In the
		 * upper half ({@code position * 2 > monsters}) it counts from the top instead: 20th of 24 is
		 * {@code 5th highest of your 24 monsters}. A boss task says {@code bosses}; a bottom-10% monster with too few
		 * tasks to block says {@code (bottom 10%, block from 5 tasks)}.
		 */
		String reason(LootPriceMode mode)
		{
			String bottom;
			switch (advice)
			{
				case CONSIDER_BLOCK:
					bottom = " (bottom 10%)";
					break;
				case CONSIDER_SKIP:
					bottom = blockNeedsTasks
						? " (bottom 10%, block from " + MIN_BLOCK_TASKS + " tasks)"
						: " (bottom 25%)";
					break;
				default:
					bottom = "";
					break;
			}
			String place = position * 2 > monsters
				? ordinal(monsters - position + 1) + " highest"
				: ordinal(position) + " lowest";
			return metric.label(mode) + " " + LedgerHistory.formatAmount(ratePerHour, approximate) + ": " + place
				+ " of your " + monsters + (boss ? " bosses" : " monsters") + bottom;
		}
	}
}
