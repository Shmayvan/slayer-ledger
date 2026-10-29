package com.shmayvan.slayerledger;

import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * Estimates how long the rest of a Slayer task will take from the session's pace so far: the average active time per
 * kill times the kills left. Pure; no RuneLite types.
 *
 * <p>No estimate is given until the session has at least {@link #MIN_KILLS} kills and {@link #MIN_ACTIVE_MILLIS} of
 * active time, so the first kills (or a quick first kill) do not produce a wild figure.</p>
 */
final class TaskTimeEstimate
{
	/** Kills needed before an estimate is given. */
	static final int MIN_KILLS = 3;
	/** Active time needed before an estimate is given. */
	static final long MIN_ACTIVE_MILLIS = 60_000;

	private static final double MILLIS_PER_HOUR = 3_600_000d;

	private TaskTimeEstimate()
	{
	}

	/**
	 * Estimated active time left on the task.
	 *
	 * @param activeMillis the session's active (running) time so far
	 * @param kills        kills in the session so far
	 * @param remaining    kills left on the task
	 * @return {@code round(activeMillis / kills * remaining)}, saturated at {@link Long#MAX_VALUE}; empty when there
	 * is too little data ({@link #isEstimable}) or nothing is left ({@code remaining <= 0})
	 */
	static OptionalLong remainingMillis(long activeMillis, int kills, int remaining)
	{
		if (!isEstimable(activeMillis, kills) || remaining <= 0)
		{
			return OptionalLong.empty();
		}
		// Math.round saturates at Long.MAX_VALUE, and the double cannot overflow for int and long inputs.
		return OptionalLong.of(Math.round(activeMillis / (double) kills * remaining));
	}

	/** Kills per hour of active time, or empty when there is too little data ({@link #isEstimable}). */
	static OptionalDouble killsPerHour(long activeMillis, int kills)
	{
		if (!isEstimable(activeMillis, kills))
		{
			return OptionalDouble.empty();
		}
		return OptionalDouble.of(kills * MILLIS_PER_HOUR / activeMillis);
	}

	/** Average active time per kill, or empty when there is too little data ({@link #isEstimable}). */
	static OptionalLong averageKillMillis(long activeMillis, int kills)
	{
		if (!isEstimable(activeMillis, kills))
		{
			return OptionalLong.empty();
		}
		return OptionalLong.of(Math.round(activeMillis / (double) kills));
	}

	/** Whether the session has enough kills and active time for an estimate. */
	static boolean isEstimable(long activeMillis, int kills)
	{
		return kills >= MIN_KILLS && activeMillis >= MIN_ACTIVE_MILLIS;
	}
}
