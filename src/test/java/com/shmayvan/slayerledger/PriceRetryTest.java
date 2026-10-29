package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/** When unavailable today's prices are looked up again: the interval, the cap and what resets them. */
public class PriceRetryTest
{
	private final PriceRetry retry = new PriceRetry();

	/** Ticks until an id is due, at most {@code max} ticks; the ids due, or empty when none was. */
	private Set<Integer> tickUntilDue(int max)
	{
		for (int i = 0; i < max; i++)
		{
			Set<Integer> due = retry.tick();
			if (!due.isEmpty())
			{
				return due;
			}
		}
		return Collections.emptySet();
	}

	@Test
	public void nothingIsDueWithoutMissedIds()
	{
		assertTrue(retry.isEmpty());
		assertEquals(Collections.emptySet(), tickUntilDue(100));
		retry.missed(Collections.emptySet());
		assertEquals(Collections.emptySet(), tickUntilDue(100));
	}

	@Test
	public void missedIdsAreDueEveryIntervalTicks()
	{
		retry.missed(Arrays.asList(1, 2));
		for (int i = 1; i < PriceRetry.INTERVAL_TICKS; i++)
		{
			assertEquals("tick " + i, Collections.emptySet(), retry.tick());
		}
		assertEquals(new TreeSet<>(Arrays.asList(1, 2)), retry.tick());
		for (int i = 1; i < PriceRetry.INTERVAL_TICKS; i++)
		{
			assertEquals(Collections.emptySet(), retry.tick());
		}
		assertEquals(new TreeSet<>(Arrays.asList(1, 2)), retry.tick());
	}

	@Test
	public void foundIdsAreNotTriedAgain()
	{
		retry.missed(Arrays.asList(1, 2));
		assertEquals(2, tickUntilDue(PriceRetry.INTERVAL_TICKS).size());
		retry.found(Collections.singleton(1));
		assertEquals(Collections.singleton(2), tickUntilDue(PriceRetry.INTERVAL_TICKS));
		retry.found(Collections.singleton(2));
		assertTrue(retry.isEmpty());
		assertEquals(Collections.emptySet(), tickUntilDue(100));
	}

	@Test
	public void anIdIsTriedAtMostMaxAttemptsTimes()
	{
		retry.missed(Collections.singleton(7));
		int tries = 0;
		for (int tick = 0; tick < PriceRetry.INTERVAL_TICKS * (PriceRetry.MAX_ATTEMPTS + 5); tick++)
		{
			tries += retry.tick().size();
		}
		assertEquals(PriceRetry.MAX_ATTEMPTS, tries);
		assertTrue("dropped after its last try", retry.isEmpty());
	}

	@Test
	public void aNewMissResetsTheTriesButKeepsTheCountdown()
	{
		retry.missed(Collections.singleton(7));
		for (int i = 0; i < PriceRetry.MAX_ATTEMPTS - 1; i++)
		{
			assertEquals(Collections.singleton(7), tickUntilDue(PriceRetry.INTERVAL_TICKS));
		}
		// Two ticks into the countdown, a new request misses 7 again and also 8.
		retry.tick();
		retry.tick();
		retry.missed(Arrays.asList(7, 8));
		for (int i = 2; i < PriceRetry.INTERVAL_TICKS - 1; i++)
		{
			assertEquals(Collections.emptySet(), retry.tick());
		}
		assertEquals("due when the running countdown ends", new TreeSet<>(Arrays.asList(7, 8)), retry.tick());
		int tries = 1;
		for (int tick = 0; tick < PriceRetry.INTERVAL_TICKS * (PriceRetry.MAX_ATTEMPTS + 5); tick++)
		{
			tries += retry.tick().contains(7) ? 1 : 0;
		}
		assertEquals("7 got a full set of tries again", PriceRetry.MAX_ATTEMPTS, tries);
	}

	@Test
	public void clearForgetsEverything()
	{
		retry.missed(Collections.singleton(7));
		retry.clear();
		assertTrue(retry.isEmpty());
		assertEquals(Collections.emptySet(), tickUntilDue(100));
		retry.missed(Collections.singleton(8));
		assertFalse(retry.isEmpty());
		assertEquals(Collections.singleton(8), tickUntilDue(PriceRetry.INTERVAL_TICKS));
	}
}
