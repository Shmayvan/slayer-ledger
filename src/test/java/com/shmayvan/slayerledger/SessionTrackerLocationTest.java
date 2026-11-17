package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.TestSpots.CATACOMBS;
import static com.shmayvan.slayerledger.TestSpots.CATACOMBS_ID;
import static com.shmayvan.slayerledger.TestSpots.SMOKE;
import static com.shmayvan.slayerledger.TestSpots.SMOKE_ID;
import static com.shmayvan.slayerledger.TestSpots.TABLE;
import static com.shmayvan.slayerledger.TestSpots.TOWER;
import static com.shmayvan.slayerledger.TestSpots.TOWER_ID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;

/**
 * Kills per place in {@link SessionTracker}: tallied exactly when kills are counted, kept by snapshots and
 * restores, and saved as the record's spot and region.
 */
public class SessionTrackerLocationTest
{
	private static final int DEMON_ID = 42;
	private static final String DEMONS = "Abyssal demons";
	private static final long ACCOUNT = 555L;
	private static final int[] COUNTERS = {100, 2, 0};
	private static final TaskContext KONAR = TaskContext.of(SlayerMaster.KONAR, 7, "Kraken Cove", null, null);

	private long now;
	private SessionTracker tracker;

	@Before
	public void setUp()
	{
		now = 1_000_000L;
		tracker = newTracker();
		observe(50, SMOKE);
	}

	private SessionTracker newTracker()
	{
		SessionTracker t = new SessionTracker(() -> now);
		t.setSpots(TABLE);
		return t;
	}

	/** Observes at the centre of {@code region}, as the plugin reads the player's tile. */
	private SessionTracker.ObserveResult observe(int remaining, int region)
	{
		return observe(remaining, region, TaskContext.NONE, false);
	}

	private SessionTracker.ObserveResult observe(int remaining, int region, TaskContext context, boolean completed)
	{
		KillPlace place = LocationTally.isRegion(region) ? TestSpots.centre(region) : KillPlace.NONE;
		return tracker.observe(DEMONS, DEMON_ID, remaining, 150, context, place, completed, KillPairing.NONE);
	}

	@Test
	public void killsAreTalliedWhereTheyAreCounted()
	{
		assertTrue(tracker.startNow(ACCOUNT));
		observe(47, SMOKE);
		observe(45, CATACOMBS);
		observe(44, SMOKE);
		assertEquals("the most recent place last",
			Arrays.asList(new LocationKills(CATACOMBS, CATACOMBS_ID, 2), new LocationKills(SMOKE, SMOKE_ID, 4)),
			tracker.getLocationKills());
		assertEquals(new TaskSpot(SMOKE_ID, SMOKE), tracker.getTaskSpot());
		assertEquals(6, tracker.getKills());
	}

	@Test
	public void killsWithoutARegionStillCountUntallied()
	{
		assertTrue(tracker.startNow(ACCOUNT));
		observe(48, LocationTally.NO_REGION);
		assertEquals(2, tracker.getKills());
		assertTrue(tracker.getLocationKills().isEmpty());
		assertEquals(TaskSpot.UNKNOWN, tracker.getTaskSpot());
	}

	@Test
	public void killsWithoutASessionAreNotTalliedButAKillThatRestartsOneIs()
	{
		// No account is known, so these kills start no session.
		observe(49, CATACOMBS);
		observe(48, SMOKE);
		assertTrue(tracker.startNow(ACCOUNT));
		observe(47, SMOKE);
		assertEquals(Collections.singletonList(new LocationKills(SMOKE, SMOKE_ID, 1)), tracker.getLocationKills());

		// A kill while paused restarts the session and is tallied where it happened.
		tracker.pause();
		observe(40, CATACOMBS);
		assertEquals(Arrays.asList(new LocationKills(SMOKE, SMOKE_ID, 1), new LocationKills(CATACOMBS, CATACOMBS_ID, 7)),
			tracker.getLocationKills());
		assertEquals(CATACOMBS_ID, tracker.getTaskSpot().getSpotId());
	}

	@Test
	public void theFinalKillIsTalliedWhereTheCountDropped()
	{
		assertTrue(tracker.startNow(ACCOUNT));
		observe(2, SMOKE);
		// The count drops to 0 in the Catacombs; the completion is confirmed a tick later, after walking away.
		assertEquals(SessionTracker.ObserveResult.TASK_CLEARED, observe(0, CATACOMBS));
		assertEquals(SessionTracker.ObserveResult.COMPLETED, observe(0, SMOKE, TaskContext.NONE, true));
		assertEquals(Arrays.asList(new LocationKills(SMOKE, SMOKE_ID, 48), new LocationKills(CATACOMBS, CATACOMBS_ID, 2)),
			tracker.getLocationKills());

		assertTrue(tracker.holdCompletion());
		assertEquals("the spot is the held session's", new TaskSpot(SMOKE_ID, SMOKE),
			tracker.takePendingCompletion().getTaskSpot());
		assertTrue(tracker.getLocationKills().isEmpty());
	}

	@Test
	public void aCompletionOnTheSameTickIsTallied()
	{
		assertTrue(tracker.startNow(ACCOUNT));
		assertEquals(SessionTracker.ObserveResult.COMPLETED, observe(0, CATACOMBS, TaskContext.NONE, true));
		assertEquals(Collections.singletonList(new LocationKills(CATACOMBS, CATACOMBS_ID, 50)),
			tracker.getLocationKills());
	}

	@Test
	public void placesPastTheLimitStillCountKills()
	{
		tracker = newTracker();
		observe(140, SMOKE);
		assertTrue(tracker.startNow(ACCOUNT));
		for (int i = 0; i < LocationTally.MAX_REGIONS; i++)
		{
			observe(139 - i, 1_000 + i);
		}
		observe(139 - LocationTally.MAX_REGIONS, SMOKE);
		assertEquals(LocationTally.MAX_REGIONS + 1, tracker.getKills());
		assertEquals(LocationTally.MAX_REGIONS, tracker.getLocationKills().size());
	}

	@Test
	public void aTaskWithAnAssignedAreaIsStillPlacedByItsKills()
	{
		observe(50, SMOKE, KONAR, false);
		assertTrue(tracker.startNow(ACCOUNT));
		observe(49, SMOKE, KONAR, false);
		assertEquals(new TaskSpot(SMOKE_ID, SMOKE), tracker.getTaskSpot());
		assertEquals("Kraken Cove", tracker.getSessionTaskLocation());

		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), true);
		assertEquals(Integer.valueOf(SMOKE), record.getLocationRegion());
		assertEquals(SMOKE_ID, record.getSpotId());
		assertNull("no name is saved", record.getLocation());
		assertEquals("the area is kept apart", "Kraken Cove", record.getTaskLocation());
	}

	@Test
	public void theRecordKeepsTheSpotAndItsRegionWithoutAName()
	{
		assertTrue(tracker.startNow(ACCOUNT));
		observe(49, CATACOMBS);
		observe(47, SMOKE);
		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), true);
		assertEquals(Integer.valueOf(SMOKE), record.getLocationRegion());
		assertEquals(SMOKE_ID, record.getSpotId());
		assertNull(record.getLocation());

		tracker.reset();
		assertTrue(tracker.startNow(ACCOUNT));
		LedgerRecord none = LedgerRecord.fromSummary(tracker.stopAndReset(), true);
		assertNull(none.getLocationRegion());
		assertNull(none.getSpotId());
		assertNull(none.getLocation());
	}

	@Test
	public void snapshotsAndRestoresKeepTheTally()
	{
		assertTrue(tracker.startNow(ACCOUNT));
		observe(47, CATACOMBS);
		observe(46, SMOKE);
		SessionSnapshot snapshot = SessionSnapshot.fromSummary(tracker.activeSummary(), COUNTERS, now);
		assertEquals(Arrays.asList(new LocationKills(CATACOMBS, CATACOMBS_ID, 3), new LocationKills(SMOKE, SMOKE_ID, 1)),
			snapshot.getLocationKills());
		Gson gson = new Gson();
		SessionSnapshot read = gson.fromJson(gson.toJson(snapshot), SessionSnapshot.class);
		assertEquals(snapshot, read);
		LedgerRecord partial = LedgerRecord.fromSummary(read.toSummary(SlayerSpots.NONE), true);
		assertEquals("a partial from the snapshot keeps its spot", CATACOMBS_ID, partial.getSpotId());
		assertEquals(Integer.valueOf(CATACOMBS), partial.getLocationRegion());

		SessionTracker restored = newTracker();
		restored.observe(DEMONS, DEMON_ID, 46, 150, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, restored.restore(read, COUNTERS));
		assertTrue(restored.startNow(ACCOUNT));
		restored.observe(DEMONS, DEMON_ID, 43, 150, TaskContext.NONE, TestSpots.centre(SMOKE), false, KillPairing.NONE);
		assertEquals(Arrays.asList(new LocationKills(CATACOMBS, CATACOMBS_ID, 3), new LocationKills(SMOKE, SMOKE_ID, 4)),
			restored.getLocationKills());
		assertEquals(new TaskSpot(SMOKE_ID, SMOKE), restored.getTaskSpot());
	}

	@Test
	public void aTallyKeptBeforeSpotsIsPlacedByItsRegions()
	{
		// Kills placed by their regions alone, as a 1.x active session kept them.
		assertTrue(tracker.startNow(ACCOUNT));
		tracker.observe(DEMONS, DEMON_ID, 47, 150, TaskContext.NONE, TOWER, false);
		tracker.observe(DEMONS, DEMON_ID, 45, 150, TaskContext.NONE, CATACOMBS, false);
		SessionSnapshot snapshot = SessionSnapshot.fromSummary(tracker.activeSummary(), COUNTERS, now);
		assertEquals(Arrays.asList(new LocationKills(TOWER, 3), new LocationKills(CATACOMBS, 2)),
			snapshot.getLocationKills());
		SessionTracker restored = newTracker();
		restored.observe(DEMONS, DEMON_ID, 45, 150, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, restored.restore(snapshot, COUNTERS));
		assertEquals(new TaskSpot(TOWER_ID, TOWER), restored.getTaskSpot());
		assertTrue(restored.startNow(ACCOUNT));
		assertEquals(TOWER_ID, LedgerRecord.fromSummary(restored.stopAndReset(), true).getSpotId());
	}

	@Test
	public void anOlderSnapshotRestoresWithNoTally()
	{
		assertTrue(tracker.startNow(ACCOUNT));
		SessionSnapshot snapshot = SessionSnapshot.fromSummary(tracker.activeSummary(), COUNTERS, now);
		assertNull("none yet, so not written", snapshot.getLocationKills());
		SessionTracker restored = newTracker();
		restored.observe(DEMONS, DEMON_ID, 50, 150, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, restored.restore(snapshot, COUNTERS));
		assertTrue(restored.getLocationKills().isEmpty());
	}

	@Test
	public void theCurrentPlaceIsTheLatestObservedOneUntilTheTaskIsCleared()
	{
		assertEquals("observed while idle too", SMOKE, tracker.getCurrentRegion());
		assertEquals(SMOKE_ID, tracker.getCurrentSpot());
		observe(50, CATACOMBS);
		assertEquals(CATACOMBS, tracker.getCurrentRegion());
		assertEquals(CATACOMBS_ID, tracker.getCurrentSpot());
		observe(50, LocationTally.NO_REGION);
		assertEquals(LocationTally.NO_REGION, tracker.getCurrentRegion());
		assertNull(tracker.getCurrentSpot());
		// A place known by its region alone takes its region's spot.
		tracker.observe(DEMONS, DEMON_ID, 50, 150, TaskContext.NONE, TOWER, false);
		assertEquals(TOWER_ID, tracker.getCurrentSpot());
		tracker.clearCurrentTask();
		assertEquals(LocationTally.NO_REGION, tracker.getCurrentRegion());
		assertNull(tracker.getCurrentSpot());
	}

	@Test
	public void aNewSessionStartsWithNoTally()
	{
		assertTrue(tracker.startNow(ACCOUNT));
		observe(49, SMOKE);
		tracker.stopAndReset();
		assertTrue(tracker.getLocationKills().isEmpty());
		assertTrue(tracker.startNow(ACCOUNT));
		assertEquals(TaskSpot.UNKNOWN, tracker.getTaskSpot());
	}
}
