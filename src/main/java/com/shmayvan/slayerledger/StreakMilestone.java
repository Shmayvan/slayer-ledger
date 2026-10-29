package com.shmayvan.slayerledger;

/**
 * The next bonus-points task of a Slayer task streak. Pure; no RuneLite types.
 *
 * <p>Per the OSRS Wiki "Slayer reward point" (revision 15372070, 2026-10-13,
 * https://oldschool.runescape.wiki/w/Slayer_reward_point?oldid=15372070), bonus points are awarded after every 10th,
 * 50th, 100th, 250th and 1,000th completed task in a row, and Krystilia (Wilderness) has a streak of her own. Every one
 * of those is a multiple of 10, so the next bonus task is always the next multiple of 10 (the larger tiers only change
 * how many points it gives, which this does not show). Turael, Aya and Spria give no points, even on a bonus task.</p>
 *
 * <p>The streak counters are those {@link CompletionSignal} reads, in its order: regular ({@link #REGULAR}),
 * Wilderness ({@link #WILDERNESS}) and Mortimer ({@link #MORTIMER}).</p>
 */
final class StreakMilestone
{
	/** No streak counter applies: the master is unknown. */
	static final int NO_COUNTER = CompletionSignal.NO_COUNTER;
	static final int REGULAR = 0;
	static final int WILDERNESS = 1;
	static final int MORTIMER = 2;

	/** Bonus points come every this many tasks in a row. */
	static final int INTERVAL = 10;

	private StreakMilestone()
	{
	}

	/**
	 * The streak counter for a task from {@code master} (a SLAYER_MASTER value): Wilderness for Krystilia, Mortimer's
	 * for Mortimer, regular for the other masters in {@link SlayerMaster}'s table, and {@link #NO_COUNTER} for no
	 * master or one not in the table.
	 */
	static int counterFor(Integer master)
	{
		if (!SlayerMaster.hasName(master))
		{
			return NO_COUNTER;
		}
		switch (master)
		{
			case SlayerMaster.KRYSTILIA:
				return WILDERNESS;
			case SlayerMaster.MORTIMER:
				return MORTIMER;
			default:
				return REGULAR;
		}
	}

	/**
	 * The next bonus task after {@code streak} completed tasks in a row: the smallest multiple of {@link #INTERVAL}
	 * above it, or {@code null} when the streak is unknown ({@code null}, negative) or the next one would overflow.
	 */
	static Integer nextMilestone(Integer streak)
	{
		if (streak == null || streak < 0 || streak > Integer.MAX_VALUE - INTERVAL)
		{
			return null;
		}
		return (streak / INTERVAL + 1) * INTERVAL;
	}

	/**
	 * Whether {@code streak}, a record's streak after its completion ({@link LedgerRecord#getTaskStreak()}), makes that
	 * task a bonus-points task: a positive multiple of {@link #INTERVAL}. {@code false} when the streak is unknown
	 * ({@code null}), 0 or negative. Points/h leaves these tasks out (owner decision): which monster lands on a
	 * bonus task is streak luck, not the monster.
	 */
	static boolean isMilestone(Integer streak)
	{
		return streak != null && streak > 0 && streak % INTERVAL == 0;
	}

	/**
	 * {@code Next bonus: task 50 (in 3)}: the next bonus task and how many tasks are left to it (the current task, when
	 * one is assigned, is the first of them), or {@code null} when the streak is unknown.
	 */
	static String format(Integer streak)
	{
		final Integer next = nextMilestone(streak);
		if (next == null)
		{
			return null;
		}
		return "Next bonus: task " + next + " (in " + (next - streak) + ")";
	}

	/**
	 * {@code bonus in 3}: the short form of {@link #format} for the task box's second line, or {@code null} when the
	 * streak is unknown.
	 */
	static String formatShort(Integer streak)
	{
		final Integer next = nextMilestone(streak);
		if (next == null)
		{
			return null;
		}
		return "bonus in " + (next - streak);
	}
}
