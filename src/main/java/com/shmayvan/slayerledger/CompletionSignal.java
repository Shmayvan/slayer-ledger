package com.shmayvan.slayerledger;

import java.util.Arrays;

/**
 * Detects a Slayer task completion from the game's task-completion counters (regular, Wilderness and Mortimer
 * streaks). A completion increments one counter by exactly one. To avoid mistaking a login resync for a completion:
 * <ul>
 *     <li>the first reading after {@link #reset()} only sets the baseline;</li>
 *     <li>an increase of more than one in any counter is treated as a resync, not a completion;</li>
 *     <li>decreases (for example a streak reset) are never completions.</li>
 * </ul>
 * Not thread-safe; used on the client thread only.
 */
final class CompletionSignal
{
	/** No single counter went up by one in the last reading; see {@link #incrementedCounter()}. */
	static final int NO_COUNTER = -1;

	private int[] last;
	private int incremented = NO_COUNTER;

	/** Forget the baseline, e.g. after logout, world hop or connection loss. */
	void reset()
	{
		last = null;
		incremented = NO_COUNTER;
	}

	/**
	 * Index of the counter whose increase made the last {@link #update} a completion (the streak that completion
	 * raised), or {@link #NO_COUNTER} when the last update was not a completion or more than one counter went up.
	 */
	int incrementedCounter()
	{
		return incremented;
	}

	/**
	 * @param counters current counter values (the same order every call)
	 * @return whether this reading signals a task completion
	 */
	boolean update(int... counters)
	{
		int[] previous = last;
		last = Arrays.copyOf(counters, counters.length);
		incremented = NO_COUNTER;
		if (previous == null || previous.length != counters.length)
		{
			return false;
		}
		int raised = NO_COUNTER;
		int raisedCount = 0;
		for (int i = 0; i < counters.length; i++)
		{
			int delta = counters[i] - previous[i];
			if (delta > 1)
			{
				return false;
			}
			if (delta == 1)
			{
				raised = i;
				raisedCount++;
			}
		}
		if (raisedCount == 0)
		{
			return false;
		}
		incremented = raisedCount == 1 ? raised : NO_COUNTER;
		return true;
	}
}
