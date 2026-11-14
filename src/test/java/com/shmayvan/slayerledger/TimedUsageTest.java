package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;

/** {@link TimedUsage}: supplies, XP and loot by arrival time, taken by window and bounded (V2 plan 4.1). */
public class TimedUsageTest
{
	private static final int SHARK = 385;
	private static final int DART = 811;
	private static final int BONES = 526;
	private static final long MINUTE = 60_000L;

	private static ItemTally tally(int id, long quantity)
	{
		final ItemTally tally = new ItemTally();
		tally.add(id, quantity);
		return tally;
	}

	private static Map<String, Long> xp(long amount)
	{
		final Map<String, Long> xp = new TreeMap<>();
		xp.put("ATTACK", amount);
		return xp;
	}

	@Test
	public void additionsAtOneTimeShareAnEntry()
	{
		final TimedUsage usage = new TimedUsage();
		usage.addSupplies(1_000, tally(SHARK, 1), null, null);
		usage.addXp(1_000, xp(10));
		usage.addLoot(1_000, tally(BONES, 1), null);
		usage.addSupplies(1_000, tally(SHARK, 2), tally(DART, 3), null);
		assertEquals(1, usage.size());
		usage.addXp(1_001, xp(10));
		assertEquals(2, usage.size());

		final List<TimedUsage.Entry> taken = usage.take(0, 0);
		assertEquals(2, taken.size());
		assertEquals(3, taken.get(0).getUsed().get(SHARK));
		assertEquals(3, taken.get(0).getReturned().get(DART));
		assertEquals(Long.valueOf(10), taken.get(0).getXp().get("ATTACK"));
		assertEquals(1, taken.get(0).getLoot().get(BONES));
		assertTrue(usage.isEmpty());
	}

	@Test
	public void nothingToAddMakesNoEntry()
	{
		final TimedUsage usage = new TimedUsage();
		usage.addSupplies(1_000, new ItemTally(), null, null);
		usage.addSupplies(1_000, null, new ItemTally(), null);
		usage.addXp(1_000, Collections.emptyMap());
		usage.addXp(1_000, null);
		usage.addLoot(1_000, null, Collections.emptyList());
		assertTrue(usage.isEmpty());
	}

	@Test
	public void takeSplitsSuppliesFromXpAndLootByTheirOwnWindows()
	{
		final TimedUsage usage = new TimedUsage();
		for (long at : new long[]{1_000, 2_000, 3_000})
		{
			usage.addSupplies(at, tally(SHARK, 1), null, null);
			usage.addXp(at, xp(at));
			usage.addLoot(at, tally(BONES, 1), null);
		}

		// Supplies from 2,000, XP and loot from 3,000.
		final List<TimedUsage.Entry> taken = usage.take(2_000, 3_000);
		assertEquals("oldest first", 2_000, taken.get(0).getAt());
		assertEquals(1, taken.get(0).getUsed().get(SHARK));
		assertNull("its XP stays", taken.get(0).getXp());
		assertEquals(3_000, taken.get(1).getAt());
		assertEquals(Long.valueOf(3_000), taken.get(1).getXp().get("ATTACK"));
		assertEquals(1, taken.get(1).getLoot().get(BONES));

		// What is left: all of 1,000 and the XP and loot of 2,000; a later, earlier window takes them.
		assertEquals(2, usage.size());
		final List<TimedUsage.Entry> rest = usage.take(1_500, 1_500);
		assertEquals(1, rest.size());
		assertNull(rest.get(0).getUsed());
		assertEquals(Long.valueOf(2_000), rest.get(0).getXp().get("ATTACK"));
		assertEquals(1, usage.size());
	}

	@Test
	public void takeAfterGivesNewestFirstAndLeavesTheRest()
	{
		final TimedUsage usage = new TimedUsage();
		usage.addXp(1_000, xp(1));
		usage.addXp(2_000, xp(2));
		usage.addXp(3_000, xp(3));
		final List<TimedUsage.Entry> taken = usage.takeAfter(1_000);
		assertEquals(2, taken.size());
		assertEquals(3_000, taken.get(0).getAt());
		assertEquals(2_000, taken.get(1).getAt());
		assertEquals(1, usage.size());

		// Added elsewhere at their own times.
		final TimedUsage buffer = new TimedUsage();
		buffer.add(taken.get(1));
		buffer.add(taken.get(0));
		assertEquals(2, buffer.size());
		assertEquals(Long.valueOf(3), buffer.take(3_000, 3_000).get(0).getXp().get("ATTACK"));
	}

	@Test
	public void dropBeforeAndUpTo()
	{
		final TimedUsage usage = new TimedUsage();
		usage.addXp(1_000, xp(1));
		usage.addXp(2_000, xp(2));
		usage.addXp(3_000, xp(3));
		usage.dropBefore(2_000);
		assertEquals(2, usage.size());
		usage.dropUpTo(2_000);
		assertEquals(1, usage.size());
		usage.clear();
		assertTrue(usage.isEmpty());
	}

	@Test
	public void aClockThatWentBackStopsTheDropsButNotTheWindows()
	{
		final TimedUsage usage = new TimedUsage();
		usage.addXp(1_000, xp(1));
		usage.addXp(3_000, xp(3));
		// The clock went back.
		usage.addXp(2_000, xp(2));

		// The drops go from the front and stop at the first newer entry.
		usage.dropUpTo(2_000);
		assertEquals(2, usage.size());
		usage.dropBefore(3_000);
		assertEquals(2, usage.size());

		// What is taken still goes by each entry's own time.
		final List<TimedUsage.Entry> after = usage.takeAfter(2_000);
		assertEquals(1, after.size());
		assertEquals(3_000, after.get(0).getAt());
		assertEquals(Long.valueOf(2), usage.take(2_000, 2_000).get(0).getXp().get("ATTACK"));
		assertTrue(usage.isEmpty());
	}

	@Test
	public void entriesOlderThanSixtyMinutesAreDropped()
	{
		final TimedUsage usage = new TimedUsage();
		final long start = 1_000_000_000L;
		usage.addXp(start, xp(1));
		usage.addXp(start + 1, xp(1));
		usage.addXp(start + TimedUsage.MAX_AGE_MILLIS, xp(1));
		assertEquals("exactly 60 minutes old is kept", 3, usage.size());
		usage.addXp(start + TimedUsage.MAX_AGE_MILLIS + 1, xp(1));
		assertEquals(3, usage.size());
		usage.addXp(start + 2 * TimedUsage.MAX_AGE_MILLIS + 2, xp(1));
		assertEquals("only the newest left", 1, usage.size());
	}

	@Test
	public void atMostMaxEntriesAreKept()
	{
		final TimedUsage usage = new TimedUsage();
		final long start = 1_000_000_000L;
		for (int i = 0; i < TimedUsage.MAX_ENTRIES + 500; i++)
		{
			usage.addSupplies(start + i, tally(SHARK, 1), null, null);
		}
		assertEquals(TimedUsage.MAX_ENTRIES, usage.size());
		assertEquals("the oldest went first", start + 500, usage.take(0, 0).get(0).getAt());
	}

	@Test
	public void anHourOfUseOneEntryPerGameTickStaysBounded()
	{
		final TimedUsage usage = new TimedUsage();
		final long start = 1_000_000_000L;
		// Two hours of a game tick each 600 ms, every tick with XP: only the last hour is kept.
		for (long at = start; at < start + 120 * MINUTE; at += 600)
		{
			usage.addXp(at, xp(1));
		}
		assertTrue("an hour of ticks at most (" + usage.size() + ")",
			usage.size() <= TimedUsage.MAX_AGE_MILLIS / 600 + 1);
		assertTrue(usage.size() <= TimedUsage.MAX_ENTRIES);
	}

	@Test
	public void dosesAndPetsAreKept()
	{
		final TimedUsage usage = new TimedUsage();
		usage.addSupplies(1_000, tally(2434, 1), null, Collections.singletonMap(2434, 4));
		final NotableDrop pet = NotableDrop.pet(NotableDrop.Pet.RECEIVED, 1_000);
		usage.addLoot(1_000, null, Collections.singletonList(pet));
		final TimedUsage.Entry entry = usage.take(0, 0).get(0);
		assertEquals(Integer.valueOf(4), entry.getDoses().get(2434));
		assertEquals(Collections.singletonList(pet), entry.getDrops());
		assertNull(entry.getLoot());
	}
}
