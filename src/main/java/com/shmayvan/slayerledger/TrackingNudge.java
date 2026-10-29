package com.shmayvan.slayerledger;

import java.util.Objects;

/**
 * The "not tracking" nudge: a task kill while no session is running (IDLE) or while the session is paused. Starting
 * and resuming stay manual (owner decision), so the plugin only tells the player: one chat line per assignment, and an
 * overlay row ({@link OverlayRows#NOT_TRACKING_LABEL}) while IDLE. Pure (no RuneLite types) and not thread-safe; the
 * plugin uses it on the client thread only, every settled tick, after the tracker's decision for that tick.
 *
 * <p><b>Kills.</b> A kill is the Slayer count going down on the same assignment while it stays above zero. The kill that
 * completes a task (the count drops to 0) never nudges: the task is over and its completion is being handled. A count
 * change from a new assignment or a reset (another creature key, name or assigned size, or a count that went up) only
 * sets the baseline. So does the first reading after a login, hop or lost connection ({@link #reset()}), and readings
 * while the plugin is still checking for a session to restore.</p>
 *
 * <p><b>Once per assignment.</b> The chat line is posted on the first kill of an assignment seen while IDLE or PAUSED,
 * whichever comes first, and never again for that assignment. Only the last assignment read is remembered (one account
 * at a time), across logins, hops and plugin restarts in the same client run, so a relogin onto the same assignment
 * doesn't repeat the line. A new assignment, or a reading from another account, replaces it and can nudge again.</p>
 *
 * <p><b>Overlay row.</b> Shown from a kill seen while IDLE until a session starts (any state but IDLE), the count reads 0
 * (the task ended, or a transient zero), another assignment is read, or the player logs out or the plugin starts ({@link #hideRow()}). A
 * later kill while IDLE on the same assignment shows it again, without another chat line. While PAUSED the overlay's
 * Paused row already says it isn't tracking, so no second warning row is shown.</p>
 */
final class TrackingNudge
{
	/** Which chat line to post, if any. */
	enum Line
	{
		NONE,
		/** A kill while no session is running. */
		IDLE,
		/** A kill while the session is paused. */
		PAUSED
	}

	/** The account of the remembered assignment. */
	private long account;
	/** Whether an assignment is remembered (a reading with a task was taken). */
	private boolean hasAssignment;
	private String monster;
	private int creatureId;
	private int originalAmount;
	/** The count at the last reading of the remembered assignment. */
	private int remaining;
	/** Whether the remembered assignment's chat line was posted. */
	private boolean lineSent;
	/** Whether the next reading with a task only sets the baseline. */
	private boolean baseline = true;
	/** Whether the overlay row is shown (still only while IDLE; see {@link #isRowShown}). */
	private boolean rowShown;

	/** After a login, hop or lost connection: the next reading with a task is a baseline. The assignment is kept. */
	void reset()
	{
		baseline = true;
	}

	/** At the login screen and at plugin start: hides the overlay row (the next login may be another account). */
	void hideRow()
	{
		rowShown = false;
	}

	/**
	 * Takes one settled tick's reading, after the tracker's decision for the tick.
	 *
	 * @param accountHash    the logged-in account
	 * @param monster        the task name, or {@code null} when unknown
	 * @param creatureId     the task's creature key
	 * @param remaining      SLAYER_COUNT (0 when there is no task)
	 * @param originalAmount the assigned size, or {@link SessionTracker#UNKNOWN_AMOUNT}
	 * @param state          the session state after this tick's decision
	 * @param suppressed     whether kills must not nudge now (a restore check is pending); the baseline still moves
	 * @return the chat line to post for this tick (at most one per assignment)
	 */
	Line update(long accountHash, String monster, int creatureId, int remaining, int originalAmount,
		SessionTracker.State state, boolean suppressed)
	{
		if (state != SessionTracker.State.IDLE)
		{
			rowShown = false;
		}
		if (remaining <= 0)
		{
			// The task ended (or a transient zero). The assignment is kept: if the same one comes back with no more kills
			// left, it is still the same assignment.
			rowShown = false;
			return Line.NONE;
		}

		final int amount = originalAmount > 0 ? originalAmount : SessionTracker.UNKNOWN_AMOUNT;
		final boolean same = hasAssignment && accountHash == account && creatureId == this.creatureId
			&& (monster == null || this.monster == null || Objects.equals(monster, this.monster))
			&& (amount == SessionTracker.UNKNOWN_AMOUNT || this.originalAmount == SessionTracker.UNKNOWN_AMOUNT
			|| amount == this.originalAmount)
			&& remaining <= this.remaining;
		final boolean kill = same && !baseline && remaining < this.remaining;
		if (same)
		{
			if (this.monster == null)
			{
				this.monster = monster;
			}
			if (this.originalAmount == SessionTracker.UNKNOWN_AMOUNT)
			{
				this.originalAmount = amount;
			}
		}
		else
		{
			hasAssignment = true;
			account = accountHash;
			this.monster = monster;
			this.creatureId = creatureId;
			this.originalAmount = amount;
			lineSent = false;
			rowShown = false;
		}
		this.remaining = remaining;
		baseline = false;

		if (!kill || suppressed
			|| (state != SessionTracker.State.IDLE && state != SessionTracker.State.PAUSED))
		{
			return Line.NONE;
		}
		if (state == SessionTracker.State.IDLE)
		{
			rowShown = true;
		}
		if (lineSent)
		{
			return Line.NONE;
		}
		lineSent = true;
		return state == SessionTracker.State.IDLE ? Line.IDLE : Line.PAUSED;
	}

	/**
	 * Whether the overlay shows the not-tracking row. The caller also checks that the session is still IDLE, so the row
	 * goes as soon as Play is pressed, before the next tick.
	 */
	boolean isRowShown()
	{
		return rowShown;
	}

	/** The chat line for {@code line}, or {@code null} for {@link Line#NONE}. */
	static String message(Line line)
	{
		switch (line)
		{
			case IDLE:
				return "Kill not counted: Slayer Ledger isn't tracking. Press Play in the sidebar.";
			case PAUSED:
				return "Kill not counted: Slayer Ledger is paused. Press Resume in the sidebar.";
			default:
				return null;
		}
	}
}
