package com.shmayvan.slayerledger;

/**
 * How a Slayer assignment ended without a saved session (see {@link AssignmentEntry}), worked out from what changed
 * around its end. Pure; no RuneLite types.
 *
 * <p>The signals, compared between the last settled tick the assignment was active and the end of the decision
 * window ({@link AssignmentWatch}):</p>
 * <ul>
 *     <li>a task completion ({@link CompletionSignal}: one completion counter went up by exactly one);</li>
 *     <li>the change in Slayer points (the SLAYER_POINTS varbit);</li>
 *     <li>whether any of the blocked-task values changed (the SLAYER_REWARDS_BLOCKED* varps and the per-master
 *     SLAYER_BLOCKED_* varbits the plugin reads);</li>
 *     <li>whether a completion counter (task streak) went down.</li>
 * </ul>
 *
 * <p>Cancel and block costs vary by master (OSRS wiki "Slayer Rewards", read 2026-10-26: cancel 30-100, block
 * 40-120), so no amount is assumed: any drop in points counts as points spent, and the observed change is stored.
 * The blocked-task values are not decoded (which slot holds which task is not documented in the API); only "one of
 * them changed at the same moment" is used. When the signals don't fit one outcome cleanly, the outcome is
 * {@link #UNKNOWN}: never a guess.</p>
 */
enum AssignmentOutcome
{
	/** Cancelled for points: points went down, no block changed, no completion, no streak drop. */
	SKIPPED("Skipped", "Skipped (cancelled for points)"),
	/** Blocked: points went down and a blocked-task value changed, no completion, no streak drop. */
	BLOCKED("Blocked", "Blocked"),
	/** Replaced with the streak reset (a Turael or Spria reset): a streak went down and no points were spent. */
	RESET("Reset", "Reset (streak reset, no points spent)"),
	/** Completed without a session: a completion was signalled, and no streak went down. */
	COMPLETED("Completed (untracked)", "Completed without a tracked session"),
	/** The signals fit none of the above (or are missing). */
	UNKNOWN("Ended", "Ended (reason unknown)");

	private final String label;
	private final String description;

	AssignmentOutcome(String label, String description)
	{
		this.label = label;
		this.description = description;
	}

	/** Short label for the history row, e.g. "Skipped". */
	String getLabel()
	{
		return label;
	}

	/** Longer text for the row's tooltip, e.g. "Ended (reason unknown)". */
	String getDescription()
	{
		return description;
	}

	/** A saved outcome, or {@link #UNKNOWN} for {@code null} (for example a value written by a newer build). */
	static AssignmentOutcome orUnknown(AssignmentOutcome outcome)
	{
		return outcome == null ? UNKNOWN : outcome;
	}

	/**
	 * @param completed           whether a task completion was signalled around the end
	 * @param pointsChange        points after minus points before, or {@code null} when either reading is missing
	 * @param blockedValuesChanged whether any blocked-task value changed
	 * @param streakDropped       whether any completion counter went down
	 * @return the outcome; {@link #UNKNOWN} whenever the signals are ambiguous
	 */
	static AssignmentOutcome classify(boolean completed, Integer pointsChange, boolean blockedValuesChanged,
		boolean streakDropped)
	{
		if (completed)
		{
			// A completion with a streak reset (or a block) at the same moment doesn't fit one story.
			return streakDropped || blockedValuesChanged ? UNKNOWN : COMPLETED;
		}
		if (pointsChange == null || streakDropped && pointsChange != 0)
		{
			return UNKNOWN;
		}
		if (pointsChange < 0)
		{
			return blockedValuesChanged ? BLOCKED : SKIPPED;
		}
		if (pointsChange == 0 && streakDropped && !blockedValuesChanged)
		{
			return RESET;
		}
		// Points went up without a completion, a block with no points spent, or nothing changed at all.
		return UNKNOWN;
	}
}
