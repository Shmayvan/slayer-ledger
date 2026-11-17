package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.SupplyTrackerTest.PRAYER_4;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * The overlay's tick snapshot ({@link OverlayFeed}), from a real {@link SessionTracker} and
 * {@link SupplyTracker}: the assignment's and the session's identity, the superior moment's 17 ticks and the runway at
 * the history's rate, then the task's.
 */
public class OverlayFeedTest
{
	private static final long ACCOUNT = 555L;
	private static final int DEMONS = 42;
	private static final String PRAYER = SupplyTracker.familyKey("Prayer potion");

	private long now = 1_000_000L;
	private SessionTracker tracker;
	private SupplyTracker supplies;
	private final OverlayFeed feed = new OverlayFeed();

	@Before
	public void setUp()
	{
		tracker = new SessionTracker(() -> now);
		supplies = new SupplyTracker(SupplyTrackerTest.CLASSIFIER);
		tracker.observe("Abyssal demons", DEMONS, 50, 150, false);
	}

	private OverlayRows.Snapshot update(List<SupplyRunway.Supply> carried, OverlayRows.HistoricalPace pace, int tick)
	{
		return update(new SupplyRunway.Carrying(carried, true, false), pace, tick);
	}

	private OverlayRows.Snapshot update(SupplyRunway.Carrying carried, OverlayRows.HistoricalPace pace, int tick)
	{
		return feed.update(tracker, SlayerSpots.NONE, null, carried, supplies::supplyKey, pace, tick);
	}

	private OverlayRows.Snapshot update(int tick)
	{
		return update(Collections.emptyList(), null, tick);
	}

	/** {@code kills} more task kills. */
	private void kill(int kills)
	{
		now += 30_000L * kills;
		tracker.observe("Abyssal demons", DEMONS, tracker.getCurrentRemaining() - kills, 150, false);
	}

	private static List<SupplyRunway.Supply> prayer(long doses)
	{
		return Collections.singletonList(new SupplyRunway.Supply(PRAYER, PRAYER_4, SupplyRunway.Kind.PRAYER, doses));
	}

	@Test
	public void anAssignmentWithoutASessionHasItsIdentitySizeAndKillsLeft()
	{
		OverlayRows.Snapshot waiting = update(1);

		assertNull(waiting.getSessionId());
		assertEquals("Abyssal demons", waiting.getTaskName());
		assertEquals(TaskIcons.itemId("Abyssal demons"), waiting.getIconItemId());
		assertEquals(150, waiting.getAssigned());
		assertEquals(50, waiting.getRemaining());
		assertTrue(waiting.getTags().isEmpty());
		assertNull(waiting.getRunway());
		assertFalse(waiting.isCaveTask());

		// No task: nothing.
		tracker.observe(null, SessionTracker.NO_CREATURE, 0, false);
		for (int i = 0; i < SessionTracker.MAX_TRANSIENT_ZERO_OBSERVATIONS + 1; i++)
		{
			tracker.observe(null, SessionTracker.NO_CREATURE, 0, false);
		}
		assertNull(update(2));
	}

	@Test
	public void aSessionsSnapshot()
	{
		assertTrue(tracker.startNow(ACCOUNT));
		kill(5);
		assertTrue(tracker.addSuperiorSpawn());

		OverlayRows.Snapshot snapshot = update(1);

		assertEquals(tracker.getSessionId(), snapshot.getSessionId());
		assertEquals("Abyssal demons", snapshot.getTaskName());
		assertEquals(150, snapshot.getAssigned());
		assertEquals("a session's kills left are read live", OverlayRows.UNKNOWN, snapshot.getRemaining());
		assertEquals(1, snapshot.getSuperiors());
		assertEquals(0, snapshot.getXp());
		assertNull("no kill tallied at a place", snapshot.getSpotName());
		assertTrue("style and gear unknown, no cannon", snapshot.getTags().isEmpty());
	}

	@Test
	public void theMomentShowsForSeventeenTicksAfterASuperior()
	{
		assertTrue(tracker.startNow(ACCOUNT));
		assertNull(update(100).getMoment());

		tracker.addSuperiorSpawn();
		assertEquals("Superior spawned.", update(101).getMoment());
		assertEquals("Superior spawned.", update(101 + OverlayFeed.MOMENT_TICKS - 1).getMoment());
		assertNull("about 10 seconds", update(101 + OverlayFeed.MOMENT_TICKS).getMoment());
		assertEquals(17, OverlayFeed.MOMENT_TICKS);

		tracker.addSuperiorSpawn();
		assertEquals("Another superior (2nd).", update(200).getMoment());

		// Another session, first seen with superiors already counted: none for them.
		tracker.stopAndReset();
		tracker.observe("Abyssal demons", DEMONS, 50, 150, false);
		assertTrue(tracker.startNow(ACCOUNT));
		tracker.addSuperiorSpawn();
		assertNull(update(201).getMoment());
	}

	@Test
	public void theRunwayAtTheHistorysRateThenTheTasks()
	{
		assertTrue(tracker.startNow(ACCOUNT));
		kill(5);
		// The history: half a dose a kill. 20 doses: 40 kills, under the 45 left.
		OverlayRows.HistoricalPace pace = new OverlayRows.HistoricalPace(tracker.getSessionId(), 60_000L,
			Collections.singletonMap(PRAYER_4, 0.5));
		SupplyRunway.Warning history = update(prayer(20), pace, 1).getRunway();
		assertEquals(new SupplyRunway.Warning(SupplyRunway.Kind.PRAYER, PRAYER_4, 40), history);

		// Another session's figures don't count.
		assertNull(update(prayer(20), new OverlayRows.HistoricalPace("other", 60_000L,
			Collections.singletonMap(PRAYER_4, 0.5)), 2).getRunway());

		// 10 kills, which used 30 doses: this task's 3 a kill. 20 doses last 6 kills: critical.
		ItemTally used = new ItemTally();
		used.add(PRAYER_4, 30);
		tracker.addSupplies(used, null, Collections.singletonMap(PRAYER_4, 4));
		kill(5);
		SupplyRunway.Warning own = update(prayer(20), pace, 3).getRunway();
		assertEquals(6, own.getKills());
		assertTrue(own.isCritical());

		// The containers not read on a tick: none.
		assertNull(update(SupplyRunway.Carrying.UNSEEN, pace, 4).getRunway());
	}

	@Test
	public void aSupplyThatRunsOutKeepsItsRunwayAtNoneUntilCarriedAgain()
	{
		assertTrue(tracker.startNow(ACCOUNT));
		kill(10);
		// This task's rate: 30 doses in 10 kills, 3 a kill. 20 doses last 6 kills.
		ItemTally used = new ItemTally();
		used.add(PRAYER_4, 30);
		tracker.addSupplies(used, null, Collections.singletonMap(PRAYER_4, 4));
		assertEquals(6, update(prayer(20), null, 1).getRunway().getKills());

		// All drunk: the warning stays, at none, critical and with the potion's icon.
		SupplyRunway.Warning out = update(Collections.emptyList(), null, 2).getRunway();
		assertEquals(new SupplyRunway.Warning(SupplyRunway.Kind.PRAYER, PRAYER_4, 0), out);
		assertTrue(out.isCritical());
		assertEquals("Prayer: 0 left", OverlayRows.runwayText(out, true));
		assertEquals("still, a tick later", out, update(Collections.emptyList(), null, 3).getRunway());
		// A tick whose containers weren't read says nothing about it.
		assertNull(update(SupplyRunway.Carrying.UNSEEN, null, 4).getRunway());
		assertEquals(out, update(Collections.emptyList(), null, 5).getRunway());

		// Carried again: what it lasts, at least 1 kill while any is carried (a dose is a third of a kill).
		assertEquals(1, update(prayer(1), null, 6).getRunway().getKills());
		assertEquals(5, update(prayer(15), null, 7).getRunway().getKills());

		// Another session forgets what the last one carried.
		tracker.stopAndReset();
		tracker.observe("Abyssal demons", DEMONS, 40, 150, false);
		assertTrue(tracker.startNow(ACCOUNT));
		kill(10);
		tracker.addSupplies(used, null, Collections.singletonMap(PRAYER_4, 4));
		assertNull(update(Collections.emptyList(), null, 8).getRunway());
	}

	@Test
	public void aSupplyThatRunsOutClearsWhenTheRateNoLongerUsesIt()
	{
		assertTrue(tracker.startNow(ACCOUNT));
		kill(5);
		OverlayRows.HistoricalPace pace = new OverlayRows.HistoricalPace(tracker.getSessionId(), 60_000L,
			Collections.singletonMap(PRAYER_4, 0.5));
		assertEquals(40, update(prayer(20), pace, 1).getRunway().getKills());
		assertEquals(0, update(Collections.emptyList(), pace, 2).getRunway().getKills());

		// The history's figures no longer use prayer potions: nothing ran out that is used.
		OverlayRows.HistoricalPace without = new OverlayRows.HistoricalPace(tracker.getSessionId(), 60_000L,
			Collections.singletonMap(SupplyTrackerTest.SHARK, 1.0));
		assertNull(update(Collections.emptyList(), without, 3).getRunway());

		// The overlay turned off forgets what was carried (the plugin resets the feed).
		assertEquals(0, update(Collections.emptyList(), pace, 4).getRunway().getKills());
		feed.reset();
		assertNull(update(Collections.emptyList(), pace, 5).getRunway());
	}

	@Test
	public void aSupplyThatLeavesOnATickLeftOutAsAWholeIsForgottenNotRunOut()
	{
		assertTrue(tracker.startNow(ACCOUNT));
		kill(10);
		ItemTally used = new ItemTally();
		used.add(PRAYER_4, 30);
		tracker.addSupplies(used, null, Collections.singletonMap(PRAYER_4, 4));
		assertEquals(6, update(prayer(20), null, 1).getRunway().getKills());

		// The bank: every prayer potion deposited on a tick the supply counting left out. Forgotten, not run out.
		feed.suppliesLeftOut(true, null);
		assertNull(update(Collections.emptyList(), null, 2).getRunway());
		assertNull("still, a tick later", update(Collections.emptyList(), null, 3).getRunway());

		// Withdrawn again, then drunk outside the bank: run out.
		assertEquals(6, update(prayer(20), null, 4).getRunway().getKills());
		assertEquals(new SupplyRunway.Warning(SupplyRunway.Kind.PRAYER, PRAYER_4, 0),
			update(Collections.emptyList(), null, 5).getRunway());

		// A bank visit after that: what is carried after it is all that was carried earlier, so nothing ran out.
		feed.suppliesLeftOut(true, Collections.emptySet());
		assertNull(update(Collections.emptyList(), null, 6).getRunway());

		// Said on ticks without a snapshot (a login's settle ticks): kept until the next one, and used once.
		assertEquals(6, update(prayer(20), null, 7).getRunway().getKills());
		feed.suppliesLeftOut(true, null);
		feed.suppliesLeftOut(false, null);
		assertNull(update(Collections.emptyList(), null, 8).getRunway());
		assertEquals(6, update(prayer(20), null, 9).getRunway().getKills());
		assertEquals(0, update(Collections.emptyList(), null, 10).getRunway().getKills());
	}

	@Test
	public void aDroppedSupplyIsForgottenNotRunOutAndOnlyIt()
	{
		assertTrue(tracker.startNow(ACCOUNT));
		kill(10);
		// 3 doses and a shark a kill.
		ItemTally used = new ItemTally();
		used.add(PRAYER_4, 30);
		used.add(SupplyTrackerTest.SHARK, 10);
		tracker.addSupplies(used, null, Collections.singletonMap(PRAYER_4, 4));
		SupplyRunway.Supply sharks = new SupplyRunway.Supply(SupplyTracker.itemKey(SupplyTrackerTest.SHARK),
			SupplyTrackerTest.SHARK, SupplyRunway.Kind.FOOD, 10);
		List<SupplyRunway.Supply> both = new ArrayList<>(prayer(20));
		both.add(sharks);
		assertEquals("prayer lasts 6 kills, the sharks 10", 6, update(both, null, 1).getRunway().getKills());

		// The sharks dropped: forgotten, not run out.
		feed.suppliesLeftOut(false, Collections.singleton(SupplyTrackerTest.SHARK));
		assertEquals(new SupplyRunway.Warning(SupplyRunway.Kind.PRAYER, PRAYER_4, 6),
			update(prayer(20), null, 2).getRunway());
		assertEquals(6, update(prayer(20), null, 3).getRunway().getKills());

		// The last dose drunk, then its vial dropped: the vial says nothing about prayer, which ran out.
		SupplyRunway.Warning out = new SupplyRunway.Warning(SupplyRunway.Kind.PRAYER, PRAYER_4, 0);
		assertEquals(out, update(Collections.emptyList(), null, 4).getRunway());
		feed.suppliesLeftOut(false, Collections.singleton(SupplyTrackerTest.VIAL));
		assertEquals(out, update(Collections.emptyList(), null, 5).getRunway());
		// An item left out that wasn't carried on the last tick (a charge window lists every material it could touch)
		// didn't leave on this one: prayer stays run out.
		feed.suppliesLeftOut(false, Collections.singleton(PRAYER_4));
		assertEquals("not carried, so it didn't leave", out, update(Collections.emptyList(), null, 6).getRunway());

		// A prayer potion of any size dropped forgets the family: 3 doses carried, then the potion dropped.
		assertEquals(1, update(Collections.singletonList(new SupplyRunway.Supply(PRAYER, PRAYER_4,
			SupplyRunway.Kind.PRAYER, 3)), null, 7).getRunway().getKills());
		feed.suppliesLeftOut(false, Collections.singleton(SupplyTrackerTest.PRAYER_3));
		assertNull(update(Collections.emptyList(), null, 8).getRunway());
	}

	@Test
	public void theMomentIsForgottenOnReset()
	{
		assertTrue(tracker.startNow(ACCOUNT));
		assertNull(update(100).getMoment());

		// A superior while the overlay is off (the plugin resets the feed on those ticks): no moment once it is back.
		feed.reset();
		tracker.addSuperiorSpawn();
		feed.reset();
		assertNull(update(102).getMoment());
		assertEquals(1, update(103).getSuperiors());
		assertNull(update(104).getMoment());

		tracker.addSuperiorSpawn();
		assertEquals("Another superior (2nd).", update(105).getMoment());
	}

	@Test
	public void aJadTaskIsACaveTask()
	{
		tracker.observe("TzTok-Jad", 99, 1, 1, false);
		assertTrue(update(1).isCaveTask());
		assertTrue(TaskCaves.isCaveTask("TzKal-Zuk"));
		assertTrue(TaskCaves.isCaveTask("tztok-jad"));
		assertFalse(TaskCaves.isCaveTask("Tzhaar"));
		assertFalse(TaskCaves.isCaveTask(null));
	}
}
