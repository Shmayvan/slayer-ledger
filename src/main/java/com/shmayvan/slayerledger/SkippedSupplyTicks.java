package com.shmayvan.slayerledger;

/**
 * Notices a long stretch of game ticks on which a running session's supplies were not counted, so the plugin can leave
 * a log line saying why (on 2026-11-08 supply counting stopped silently for hours). Pure; the plugin calls it
 * once per tick on the client thread. Not thread-safe.
 *
 * <p>The first line is due after {@link #FIRST_REPORT_TICKS} skipped ticks in a row, then one every
 * {@link #REPEAT_REPORT_TICKS} more while the stretch lasts: a bank visit of a minute or more leaves one line, an
 * exclusion that never ends one line every ten minutes.</p>
 */
final class SkippedSupplyTicks
{
	/** Skipped ticks in a row (one minute) before the first report. */
	static final int FIRST_REPORT_TICKS = 100;
	/** Further skipped ticks (ten minutes) between reports while the stretch lasts. */
	static final int REPEAT_REPORT_TICKS = 1_000;

	/** Ticks skipped in a row so far. */
	private int length;

	/**
	 * A running session's tick whose supplies were not counted.
	 *
	 * @return whether the stretch is due to be reported now ({@link #length()} long)
	 */
	boolean skipped()
	{
		if (length < Integer.MAX_VALUE)
		{
			length++;
		}
		return length == FIRST_REPORT_TICKS
			|| (length > FIRST_REPORT_TICKS && (length - FIRST_REPORT_TICKS) % REPEAT_REPORT_TICKS == 0);
	}

	/** Ticks skipped in a row so far. */
	int length()
	{
		return length;
	}

	/** Supplies were counted on this tick, or no session is running: the stretch ends. */
	void reset()
	{
		length = 0;
	}
}
