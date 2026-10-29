package com.shmayvan.slayerledger;

import java.util.Objects;

/**
 * Decides which bank openings start a new bank trip. Pure: the plugin passes in the interface events and tick numbers
 * it sees on the client thread, and adds a trip to the session when {@link #bankOpened} says so. Not thread-safe.
 *
 * <p>Rules:</p>
 * <ul>
 *     <li>A bank interface ({@link #isBankInterface}: the bank, a deposit box or group storage, the same interfaces
 *     {@link SupplyExclusions} leaves out of supply counting) opening while the session is RUNNING is a trip.</li>
 *     <li>Opening one again within {@link #SAME_TRIP_TICKS} game ticks of the last time one was open (opened or closed)
 *     is the same trip, so going back for a forgotten item, or from a deposit box to the bank, counts once.</li>
 *     <li>Opening a bank while paused or with no session is never a trip, but still starts the window, so re-opening
 *     right after resuming does not count either.</li>
 *     <li>The window belongs to one session: the first opening in a new session always counts.</li>
 * </ul>
 * The trip count itself is kept by {@link SessionTracker} (RUNNING only, cleared when the session ends).
 */
final class BankTripCounter
{
	/** Re-opening a bank within this many game ticks (30 seconds) of it last being open is the same trip. */
	static final int SAME_TRIP_TICKS = 50;

	private static final int[] BANK_INTERFACES = SupplyExclusions.bankInterfaces();

	/** Last tick a bank interface was opened or closed, or {@code null} for none. */
	private Integer lastBankTick;
	/** Session (running or paused) active when a bank was last opened, or {@code null} for none. */
	private String lastSessionId;

	/** Whether {@code groupId} is the bank, a deposit box or group storage. */
	static boolean isBankInterface(int groupId)
	{
		for (int group : BANK_INTERFACES)
		{
			if (group == groupId)
			{
				return true;
			}
		}
		return false;
	}

	/** A copy of the bank interface group ids. */
	static int[] bankInterfaces()
	{
		return BANK_INTERFACES.clone();
	}

	/**
	 * A bank interface opened on {@code tick}.
	 *
	 * @param running   whether the session is RUNNING
	 * @param sessionId the running or paused session's id, or {@code null} when there is none
	 * @return whether this opening is a new trip to add to the running session
	 */
	boolean bankOpened(int tick, boolean running, String sessionId)
	{
		final boolean sameTrip = withinWindow(tick) && Objects.equals(sessionId, lastSessionId);
		lastBankTick = tick;
		lastSessionId = sessionId;
		return running && sessionId != null && !sameTrip;
	}

	/** A bank interface closed on {@code tick}; the same-trip window runs from here. */
	void bankClosed(int tick)
	{
		if (lastBankTick != null)
		{
			lastBankTick = tick;
		}
	}

	/** Forgets the last bank visit (plugin start). */
	void reset()
	{
		lastBankTick = null;
		lastSessionId = null;
	}

	/**
	 * Whether {@code tick} is within the window of the last visit. A tick before the last visit (the tick count
	 * restarted, for example after a login) is outside it.
	 */
	private boolean withinWindow(int tick)
	{
		return lastBankTick != null && tick >= lastBankTick && (long) tick - lastBankTick <= SAME_TRIP_TICKS;
	}
}
