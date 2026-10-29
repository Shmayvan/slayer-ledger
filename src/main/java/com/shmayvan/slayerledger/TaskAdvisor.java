package com.shmayvan.slayerledger;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * The task box's advice for the current task: Do, Consider skip or Consider block, from how the monster's rate
 * on the goal metric compares with the player's other monsters'. Advice only. Pure: no RuneLite types.
 *
 * <p>The rates are {@link TaskRanking}'s, exactly as the Trends list shows them (records only, completed and not
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
 * <p><b>Catacombs variants</b> (owner decision): the advice above pools a task's standard and Catacombs variant
 * records, as before. Beside it, for a task with a variant ({@link CatacombsVariants}), {@link #adviseVariant} says
 * which version has the better rate on the goal metric, when both versions qualify on their own records (at least
 * {@link #MIN_TASKS} tasks and {@link #MIN_ACTIVE_MILLIS} of active time each, as above), whatever the other monsters.
 * Equal rates, or either version short of data, give no variant advice.</p>
 *
 * <p><b>Cannon</b> (owner decision): likewise the advice above pools a task's tasks with and without a cannon
 * ({@link CannonTasks}), and {@link #adviseCannon} says which has the better rate on the goal metric, when both qualify
 * on their own records, whatever the other monsters. The variant advice pools cannon use and the cannon advice pools
 * the versions, so each compares one thing; they are worked out independently and the task box shows them together
 * ({@link #betterText}).</p>
 */
final class TaskAdvisor
{
	/** The cannon advice's label when cannon tasks are better. */
	static final String WITH_CANNON = "with cannon";
	/** The cannon advice's label when tasks without a cannon are better. */
	static final String WITHOUT_CANNON = "without cannon";
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
			: advise(TaskRanking.rank(records, metric, mode, currentGe, hidden, TaskRanking.Split.NONE), current,
				metric);
	}

	/**
	 * The advice ({@link #advise(Collection, int, String, TaskRanking.Metric, LootPriceMode, Map, HiddenLoot)}), the
	 * variant advice ({@link #adviseVariant}) and the cannon advice ({@link #adviseCannon}) for the current task, worked
	 * out together (the task box shows them all), each with the loot items {@code hidden} hides left out of the profit.
	 */
	static Outcome adviseAll(Collection<LedgerRecord> records, int creatureId, String monster,
		TaskRanking.Metric metric, LootPriceMode mode, Map<Integer, Long> currentGe, HiddenLoot hidden)
	{
		return new Outcome(advise(records, creatureId, monster, metric, mode, currentGe, hidden),
			adviseVariant(records, creatureId, monster, metric, mode, currentGe, hidden),
			adviseCannon(records, creatureId, monster, metric, mode, currentGe, hidden));
	}

	/**
	 * Which version of the current task, standard or Catacombs variant, has the better rate on {@code metric},
	 * from {@code records} split by version with cannon use pooled ({@link TaskRanking.Split#VARIANT}); {@code null} for
	 * a task without a variant, when either version doesn't {@link #qualifies qualify}, or when their rates are equal.
	 * Its label is the better version's name, for example "Deviant spectres". The loot items {@code hidden} hides are
	 * left out of the profit.
	 */
	static VersionAdvice adviseVariant(Collection<LedgerRecord> records, int creatureId, String monster,
		TaskRanking.Metric metric, LootPriceMode mode, Map<Integer, Long> currentGe, HiddenLoot hidden)
	{
		CatacombsVariants.Variant variant = CatacombsVariants.forTask(monster);
		if (variant == null || records == null || records.isEmpty() || metric == null)
		{
			return null;
		}
		TaskRanking.TaskKey standardKey = TaskRanking.currentTaskKey(records, creatureId, monster, null, false);
		TaskRanking.TaskKey variantKey = TaskRanking.currentTaskKey(records, creatureId, monster, variant.getName(),
			false);
		return compare(TaskRanking.rank(records, metric, mode, currentGe, hidden, TaskRanking.Split.VARIANT), metric,
			variantKey, null, standardKey, null);
	}

	/**
	 * Whether the current task's tasks with a cannon or without one have the better rate on {@code metric}, from
	 * {@code records} split by cannon use with the versions pooled ({@link TaskRanking.Split#CANNON}); {@code null}
	 * when either doesn't {@link #qualifies qualify}, or when their rates are equal. Its label is
	 * {@link #WITH_CANNON} or {@link #WITHOUT_CANNON}; for a task with a Catacombs variant its reason names both groups
	 * as pooled across versions. The loot items {@code hidden} hides are left out of the profit.
	 */
	static VersionAdvice adviseCannon(Collection<LedgerRecord> records, int creatureId, String monster,
		TaskRanking.Metric metric, LootPriceMode mode, Map<Integer, Long> currentGe, HiddenLoot hidden)
	{
		if (records == null || records.isEmpty() || metric == null)
		{
			return null;
		}
		TaskRanking.TaskKey withoutKey = TaskRanking.currentTaskKey(records, creatureId, monster, null, false);
		TaskRanking.TaskKey withKey = TaskRanking.currentTaskKey(records, creatureId, monster, null, true);
		VersionAdvice advice = compare(
			TaskRanking.rank(records, metric, mode, currentGe, hidden, TaskRanking.Split.CANNON), metric, withKey,
			WITH_CANNON, withoutKey, WITHOUT_CANNON);
		if (advice == null || CatacombsVariants.forTask(monster) == null)
		{
			return advice;
		}
		// Both groups hold the Catacombs variant's tasks too, so the reason says so rather than naming them after the
		// standard monster: "Aberrant spectres, any version, with cannon".
		String task = (WITH_CANNON.equals(advice.getLabel()) ? advice.other : advice.better).getMonster();
		String better = task + ", any version, " + advice.getLabel();
		String other = task + ", any version, " + (WITH_CANNON.equals(advice.getLabel()) ? WITHOUT_CANNON
			: WITH_CANNON);
		return new VersionAdvice(metric, advice.better, advice.other, advice.getLabel(), better, other);
	}

	/**
	 * The better of the groups {@code firstKey} and {@code secondKey} in {@code ranking}, labelled
	 * {@code firstLabel} or {@code secondLabel} ({@code null}: the group's name); {@code null} when either is missing
	 * or doesn't {@link #qualifies qualify}, or their rates are equal.
	 */
	private static VersionAdvice compare(List<TaskRanking.Entry> ranking, TaskRanking.Metric metric,
		TaskRanking.TaskKey firstKey, String firstLabel, TaskRanking.TaskKey secondKey, String secondLabel)
	{
		if (firstKey == null || secondKey == null)
		{
			return null;
		}
		TaskRanking.Entry first = null;
		TaskRanking.Entry second = null;
		for (TaskRanking.Entry entry : ranking)
		{
			if (entry.getKey().equals(firstKey))
			{
				first = entry;
			}
			else if (entry.getKey().equals(secondKey))
			{
				second = entry;
			}
		}
		if (first == null || second == null || !qualifies(first) || !qualifies(second))
		{
			return null;
		}
		int compare = Long.compare(first.getRatePerHour(), second.getRatePerHour());
		if (compare == 0)
		{
			return null;
		}
		return compare > 0
			? new VersionAdvice(metric, first, second, firstLabel != null ? firstLabel : first.getMonster())
			: new VersionAdvice(metric, second, first, secondLabel != null ? secondLabel : second.getMonster());
	}

	/**
	 * The task box's line for the variant and cannon advice, plain text: {@code Better: Deviant spectres},
	 * {@code Better: with cannon}, or both in one line, {@code Better: Deviant spectres, without cannon}; {@code null}
	 * when both are {@code null}. The two are separate comparisons (each pools the other dimension).
	 */
	static String betterText(VersionAdvice variant, VersionAdvice cannon)
	{
		if (variant == null && cannon == null)
		{
			return null;
		}
		if (variant == null || cannon == null)
		{
			return "Better: " + (variant != null ? variant : cannon).getLabel();
		}
		return "Better: " + variant.getLabel() + ", " + cannon.getLabel();
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

	/** The task box's two pieces of advice for the current task; either may be {@code null}. Immutable. */
	static final class Outcome
	{
		private final Result advice;
		private final VersionAdvice variant;
		private final VersionAdvice cannon;

		Outcome(Result advice, VersionAdvice variant, VersionAdvice cannon)
		{
			this.advice = advice;
			this.variant = variant;
			this.cannon = cannon;
		}

		/** The advice on the task ({@link #advise}), or {@code null} without enough data. */
		Result getAdvice()
		{
			return advice;
		}

		/** Which version of the task is better ({@link #adviseVariant}), or {@code null}. */
		VersionAdvice getVariant()
		{
			return variant;
		}

		/** Whether the task is better with or without a cannon ({@link #adviseCannon}), or {@code null}. */
		VersionAdvice getCannon()
		{
			return cannon;
		}
	}

	/**
	 * Which of two groups of a task's records has the better rate on the goal metric: its standard monster or its
	 * Catacombs variant ({@link #adviseVariant}), or its tasks with or without a cannon (* {@link #adviseCannon}). The two groups' entries in a split {@link TaskRanking#rank}, the better first, and the
	 * better one's label. Immutable.
	 */
	static final class VersionAdvice
	{
		private final TaskRanking.Metric metric;
		private final TaskRanking.Entry better;
		private final TaskRanking.Entry other;
		private final String label;
		/** What the reason calls the better group: its name in the ranking unless given. */
		private final String betterName;
		/** What the reason calls the other group: its name in the ranking unless given. */
		private final String otherName;

		VersionAdvice(TaskRanking.Metric metric, TaskRanking.Entry better, TaskRanking.Entry other, String label)
		{
			this(metric, better, other, label, better.getMonster(), other.getMonster());
		}

		VersionAdvice(TaskRanking.Metric metric, TaskRanking.Entry better, TaskRanking.Entry other, String label,
			String betterName, String otherName)
		{
			this.metric = metric;
			this.better = better;
			this.other = other;
			this.label = label;
			this.betterName = betterName;
			this.otherName = otherName;
		}

		/** What the task box says is better: "Deviant spectres", {@link #WITH_CANNON} or {@link #WITHOUT_CANNON}. */
		String getLabel()
		{
			return label;
		}

		/**
		 * Why, plain text: {@code Profit (GE)/h: Deviant spectres 450K (n=4), Aberrant spectres 300K (n=5)}, each rate
		 * as Trends shows it, with {@code ~} when some prices were unavailable. The cannon advice on a task with a
		 * Catacombs variant names its groups as pooled across versions: {@code Aberrant spectres, any version, with
		 * cannon}.
		 */
		String reason(LootPriceMode mode)
		{
			return metric.label(mode) + ": " + part(betterName, better) + ", " + part(otherName, other);
		}

		private static String part(String name, TaskRanking.Entry entry)
		{
			return name + " " + LedgerHistory.formatAmount(entry.getRatePerHour(), entry.isApproximate())
				+ " (n=" + entry.getSampleCount() + ")";
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
		 * Why: {@code Profit (GE)/h 210K: 5th lowest of your 24 monsters (bottom 25%)}, the metric named as in Trends
		 * ({@link TaskRanking.Metric#label}), the rate compact with {@code ~} when some prices were unavailable. In the
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
