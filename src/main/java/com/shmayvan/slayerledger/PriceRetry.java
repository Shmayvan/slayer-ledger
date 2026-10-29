package com.shmayvan.slayerledger;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Decides when to look up again the items whose GE price was unavailable when the history asked for today's prices
 *. Pure: the plugin tells it which ids came back without a price and which were found, and asks it on every
 * game tick which ids to look up again. Not thread-safe (the plugin uses it on the client thread only).
 *
 * <p>Rules:</p>
 * <ul>
 *     <li>RuneLite's {@code ItemManager} has no "prices loaded" flag, so an item asked for before its price list was
 *     fetched comes back unavailable (see {@link ItemValuation}). Each such id is tried again every
 *     {@link #INTERVAL_TICKS} game ticks, at most {@link #MAX_ATTEMPTS} times, then dropped: it is only asked for
 *     again when the history next asks for it (the records change).</li>
 *     <li>An id that comes back unavailable from a new request (not a retry) gets {@link #MAX_ATTEMPTS} tries again,
 *     so a later request can never be cut short by an earlier one, while retries alone can never loop.</li>
 *     <li>An id whose price is found is never tried again.</li>
 * </ul>
 */
final class PriceRetry
{
	/** Game ticks (3 seconds) between tries of the unavailable prices. */
	static final int INTERVAL_TICKS = 5;
	/** Tries per unavailable price after the request that missed it (30 seconds in all). */
	static final int MAX_ATTEMPTS = 10;

	/** Tries left per id still unavailable. */
	private final Map<Integer, Integer> attemptsLeft = new TreeMap<>();
	/** Game ticks until the next try, while there are ids to try. */
	private int ticksLeft;

	/**
	 * Ids a request (not a retry) found unavailable: each is tried again {@link #MAX_ATTEMPTS} times, the first time
	 * {@link #INTERVAL_TICKS} ticks from now (or with the next try already counting down).
	 */
	void missed(Collection<Integer> ids)
	{
		for (Integer id : ids)
		{
			if (id != null)
			{
				attemptsLeft.put(id, MAX_ATTEMPTS);
			}
		}
		if (!attemptsLeft.isEmpty() && ticksLeft <= 0)
		{
			ticksLeft = INTERVAL_TICKS;
		}
	}

	/** Ids whose price is now known: they are not tried again. */
	void found(Collection<Integer> ids)
	{
		attemptsLeft.keySet().removeAll(ids);
		if (attemptsLeft.isEmpty())
		{
			ticksLeft = 0;
		}
	}

	/**
	 * One game tick: the ids to look up again now, or an empty set. Each id returned has used up one try; an id
	 * without tries left is dropped. The caller reports the ones it found through {@link #found}.
	 */
	Set<Integer> tick()
	{
		if (attemptsLeft.isEmpty() || --ticksLeft > 0)
		{
			return Collections.emptySet();
		}
		final Set<Integer> due = new TreeSet<>(attemptsLeft.keySet());
		attemptsLeft.replaceAll((id, left) -> left - 1);
		attemptsLeft.values().removeIf(left -> left <= 0);
		ticksLeft = attemptsLeft.isEmpty() ? 0 : INTERVAL_TICKS;
		return due;
	}

	/** Whether any id is waiting for another try. */
	boolean isEmpty()
	{
		return attemptsLeft.isEmpty();
	}

	/** Forgets every id (a new run of the plugin). */
	void clear()
	{
		attemptsLeft.clear();
		ticksLeft = 0;
	}
}
