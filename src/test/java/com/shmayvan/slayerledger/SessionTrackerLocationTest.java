package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;

/**
 * Kills per region in {@link SessionTracker}: tallied exactly when kills are counted, kept by snapshots and
 * restores, and saved as the record's dominant region.
 */
public class SessionTrackerLocationTest
{
	private static final int DEMON_ID = 42;
	private static final long ACCOUNT = 555L;
	private static final int[] COUNTERS = {100, 2, 0};
	private static final int SMOKE = 12_946;
	private static final int CATACOMBS = 6_557;
	private static final TaskContext KONAR = TaskContext.of(SlayerMaster.KONAR, 7, "Kraken Cove", null, null);

	private long now;
	private SessionTracker tracker;

	@Before
	public void setUp()
	{
		now = 1_000_000L;
		tracker = new SessionTracker(() -> now);
		observe(50, SMOKE);
	}

	private SessionTracker.ObserveResult observe(int remaining, int region)
	{
		return observe(remaining, region, TaskContext.NONE, false);
	}

	private SessionTracker.ObserveResult observe(int remaining, int region, TaskContext context, boolean completed)
	{
		return tracker.observe("Abyssal demons", DEMON_ID, remaining, 150, context, region, completed);
	}

	@Test
	public void killsAreTalliedWhereTheyAreCounted()
	{
		assertTrue(tracker.play(ACCOUNT));
		observe(47, SMOKE);
		observe(45, CATACOMBS);
		observe(44, SMOKE);
		assertEquals(Arrays.asList(new LocationKills(SMOKE, 4), new LocationKills(CATACOMBS, 2)),
			tracker.getLocationKills());
		assertEquals(Integer.valueOf(SMOKE), tracker.getDominantRegion());
		assertEquals(6, tracker.getKills());
		assertEquals(SMOKE, tracker.takeRegionToName());
		assertEquals("taken once", LocationTally.NO_REGION, tracker.takeRegionToName());
	}

	@Test
	public void killsWithoutARegionStillCountUntallied()
	{
		assertTrue(tracker.play(ACCOUNT));
		observe(48, LocationTally.NO_REGION);
		assertEquals(2, tracker.getKills());
		assertTrue(tracker.getLocationKills().isEmpty());
		assertNull(tracker.getDominantRegion());
		assertEquals(LocationTally.NO_REGION, tracker.takeRegionToName());
	}

	@Test
	public void pausedOrIdleKillsAreNotTallied()
	{
		observe(48, SMOKE);
		assertTrue(tracker.play(ACCOUNT));
		observe(47, SMOKE);
		tracker.pause();
		observe(40, CATACOMBS);
		assertEquals(Collections.singletonList(new LocationKills(SMOKE, 1)), tracker.getLocationKills());
		assertEquals(SMOKE, tracker.takeRegionToName());
		assertEquals(LocationTally.NO_REGION, tracker.takeRegionToName());
	}

	@Test
	public void theFinalKillIsTalliedWhereTheCountDropped()
	{
		assertTrue(tracker.play(ACCOUNT));
		observe(2, SMOKE);
		// The count drops to 0 in the Catacombs; the completion is confirmed a tick later, after walking away.
		assertEquals(SessionTracker.ObserveResult.TASK_CLEARED, observe(0, CATACOMBS));
		assertEquals(SessionTracker.ObserveResult.COMPLETED, observe(0, SMOKE, TaskContext.NONE, true));
		assertEquals(Arrays.asList(new LocationKills(SMOKE, 48), new LocationKills(CATACOMBS, 2)),
			tracker.getLocationKills());

		assertTrue(tracker.holdCompletion());
		assertEquals("the tally is the held session's", Integer.valueOf(SMOKE),
			tracker.takePendingCompletion().getDominantRegion());
		assertTrue(tracker.getLocationKills().isEmpty());
		assertEquals("the region to name outlives the session", CATACOMBS, tracker.takeRegionToName());
	}

	@Test
	public void aCompletionOnTheSameTickIsTallied()
	{
		assertTrue(tracker.play(ACCOUNT));
		assertEquals(SessionTracker.ObserveResult.COMPLETED, observe(0, CATACOMBS, TaskContext.NONE, true));
		assertEquals(Collections.singletonList(new LocationKills(CATACOMBS, 50)), tracker.getLocationKills());
	}

	@Test
	public void aRegionPastTheLimitIsNotOfferedForNaming()
	{
		tracker = new SessionTracker(() -> now);
		observe(140, SMOKE);
		assertTrue(tracker.play(ACCOUNT));
		for (int i = 0; i < LocationTally.MAX_REGIONS; i++)
		{
			observe(139 - i, 1_000 + i);
			assertEquals(1_000 + i, tracker.takeRegionToName());
		}
		observe(139 - LocationTally.MAX_REGIONS, SMOKE);
		assertEquals("not in the tally, so never asked about", LocationTally.NO_REGION, tracker.takeRegionToName());
		assertEquals(LocationTally.MAX_REGIONS + 1, tracker.getKills());
		assertEquals(LocationTally.MAX_REGIONS, tracker.getLocationKills().size());

		observe(138 - LocationTally.MAX_REGIONS, 1_000);
		assertEquals("a tallied region still is", 1_000, tracker.takeRegionToName());
	}

	@Test
	public void aTaskWithAnAssignedAreaHasNoRegionToName()
	{
		observe(50, SMOKE, KONAR, false);
		assertTrue(tracker.play(ACCOUNT));
		observe(49, SMOKE, KONAR, false);
		assertEquals("still tallied", Integer.valueOf(SMOKE), tracker.getDominantRegion());
		assertEquals("Kraken Cove", tracker.getSessionTaskLocation());
		assertEquals(LocationTally.NO_REGION, tracker.takeRegionToName());

		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), true);
		assertEquals(Integer.valueOf(SMOKE), record.getLocationRegion());
		assertEquals("the area is the location", "Kraken Cove", record.getLocation());
	}

	@Test
	public void theRecordKeepsTheDominantRegionWithoutAName()
	{
		assertTrue(tracker.play(ACCOUNT));
		observe(49, CATACOMBS);
		observe(47, SMOKE);
		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), true);
		assertEquals(Integer.valueOf(SMOKE), record.getLocationRegion());
		assertNull("named when saved", record.getLocation());

		tracker.reset();
		assertTrue(tracker.play(ACCOUNT));
		LedgerRecord none = LedgerRecord.fromSummary(tracker.stopAndReset(), true);
		assertNull(none.getLocationRegion());
		assertNull(none.getLocation());
	}

	@Test
	public void snapshotsAndRestoresKeepTheTally()
	{
		assertTrue(tracker.play(ACCOUNT));
		observe(47, CATACOMBS);
		observe(46, SMOKE);
		SessionSnapshot snapshot = SessionSnapshot.fromSummary(tracker.activeSummary(), COUNTERS, now);
		assertEquals(Arrays.asList(new LocationKills(CATACOMBS, 3), new LocationKills(SMOKE, 1)),
			snapshot.getLocationKills());
		Gson gson = new Gson();
		SessionSnapshot read = gson.fromJson(gson.toJson(snapshot), SessionSnapshot.class);
		assertEquals(snapshot, read);
		assertEquals("a partial from the snapshot keeps it", Integer.valueOf(CATACOMBS),
			LedgerRecord.fromSummary(read.toSummary(), true).getLocationRegion());

		SessionTracker restored = new SessionTracker(() -> now);
		restored.observe("Abyssal demons", DEMON_ID, 46, 150, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, restored.restore(read, COUNTERS));
		assertTrue(restored.play(ACCOUNT));
		restored.observe("Abyssal demons", DEMON_ID, 43, 150, TaskContext.NONE, SMOKE, false);
		assertEquals(Arrays.asList(new LocationKills(CATACOMBS, 3), new LocationKills(SMOKE, 4)),
			restored.getLocationKills());
		assertEquals(Integer.valueOf(SMOKE), restored.getDominantRegion());
	}

	@Test
	public void anOlderSnapshotRestoresWithNoTally()
	{
		assertTrue(tracker.play(ACCOUNT));
		SessionSnapshot snapshot = SessionSnapshot.fromSummary(tracker.activeSummary(), COUNTERS, now);
		assertNull("none yet, so not written", snapshot.getLocationKills());
		SessionTracker restored = new SessionTracker(() -> now);
		restored.observe("Abyssal demons", DEMON_ID, 50, 150, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, restored.restore(snapshot, COUNTERS));
		assertTrue(restored.getLocationKills().isEmpty());
	}

	@Test
	public void theCurrentRegionIsTheLatestObservedOneUntilTheTaskIsCleared()
	{
		assertEquals("observed while idle too", SMOKE, tracker.getCurrentRegion());
		observe(50, CATACOMBS);
		assertEquals(CATACOMBS, tracker.getCurrentRegion());
		observe(50, LocationTally.NO_REGION);
		assertEquals(LocationTally.NO_REGION, tracker.getCurrentRegion());
		observe(50, SMOKE);
		tracker.clearCurrentTask();
		assertEquals(LocationTally.NO_REGION, tracker.getCurrentRegion());
	}

	@Test
	public void aNewSessionStartsWithNoTally()
	{
		assertTrue(tracker.play(ACCOUNT));
		observe(49, SMOKE);
		tracker.stopAndReset();
		assertTrue(tracker.getLocationKills().isEmpty());
		assertTrue(tracker.play(ACCOUNT));
		assertNull(tracker.getDominantRegion());
	}
}
