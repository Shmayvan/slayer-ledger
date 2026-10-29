package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import org.junit.Before;
import org.junit.Test;

/**
 * Superior spawns, bank trips and deaths on the session: RUNNING-only counting, the completion grace window, reset on session
 * end, and how they travel through snapshots, restores, records and JSON.
 */
public class SessionTrackerCountersTest
{
	private static final int DEMON_ID = 42;
	private static final long ACCOUNT = 555L;
	private static final int[] COUNTERS = {100, 2, 0};

	private final Gson gson = new Gson();
	private long now;
	private SessionTracker tracker;

	@Before
	public void setUp()
	{
		now = 1_000_000L;
		tracker = new SessionTracker(() -> now);
		tracker.observe("Abyssal demons", DEMON_ID, 50, 150, false);
	}

	@Test
	public void countedOnlyWhileRunning()
	{
		assertFalse("idle", tracker.addSuperiorSpawn());
		assertFalse("idle", tracker.addBankTrip());
		assertFalse("idle", tracker.addDeath());
		assertTrue(tracker.play(ACCOUNT));
		assertTrue(tracker.addSuperiorSpawn());
		assertTrue(tracker.addBankTrip());
		assertTrue(tracker.addBankTrip());
		assertTrue(tracker.addDeath());
		tracker.pause();
		assertFalse("paused", tracker.addSuperiorSpawn());
		assertFalse("paused", tracker.addBankTrip());
		assertFalse("paused", tracker.addDeath());
		assertEquals(1, tracker.getSuperiorsSpawned());
		assertEquals(2, tracker.getBankTrips());
		assertEquals(1, tracker.getDeaths());
		SessionTracker.Summary summary = tracker.activeSummary();
		assertEquals(Integer.valueOf(1), summary.getSuperiorsSpawned());
		assertEquals(Integer.valueOf(2), summary.getBankTrips());
		assertEquals(Integer.valueOf(1), summary.getDeaths());
	}

	@Test
	public void resetWhenTheSessionEnds()
	{
		tracker.play(ACCOUNT);
		tracker.addSuperiorSpawn();
		tracker.addBankTrip();
		tracker.addDeath();
		SessionTracker.Summary ended = tracker.stopAndReset();
		assertEquals(Integer.valueOf(1), ended.getSuperiorsSpawned());
		assertEquals(Integer.valueOf(1), ended.getBankTrips());
		assertEquals(Integer.valueOf(1), ended.getDeaths());
		assertEquals(0, tracker.getSuperiorsSpawned());
		assertEquals(0, tracker.getBankTrips());
		assertEquals(0, tracker.getDeaths());

		tracker.play(ACCOUNT);
		SessionTracker.Summary next = tracker.activeSummary();
		assertEquals("a new session starts at 0, not unknown", Integer.valueOf(0), next.getSuperiorsSpawned());
		assertEquals(Integer.valueOf(0), next.getBankTrips());
		assertEquals(Integer.valueOf(0), next.getDeaths());
	}

	@Test
	public void notAddedToACompletedSessionHeldForLateLoot()
	{
		tracker.play(ACCOUNT);
		tracker.addSuperiorSpawn();
		tracker.addBankTrip();
		tracker.observe("Abyssal demons", DEMON_ID, 0, 150, true);
		tracker.addDeath();
		assertTrue(tracker.holdCompletion());
		assertFalse(tracker.addSuperiorSpawn());
		assertFalse(tracker.addBankTrip());
		assertFalse(tracker.addDeath());
		SessionTracker.Summary held = tracker.takePendingCompletion();
		assertEquals(Integer.valueOf(1), held.getSuperiorsSpawned());
		assertEquals(Integer.valueOf(1), held.getBankTrips());
		assertEquals(Integer.valueOf(1), held.getDeaths());
	}

	@Test
	public void lateLootKeepsTheCounts()
	{
		tracker.play(ACCOUNT);
		tracker.addSuperiorSpawn();
		tracker.observe("Abyssal demons", DEMON_ID, 0, 150, true);
		tracker.holdCompletion();
		ItemTally loot = new ItemTally();
		loot.add(526, 1);
		tracker.addLoot(loot);
		assertEquals(Integer.valueOf(1), tracker.takePendingCompletion().getSuperiorsSpawned());
	}

	@Test
	public void recordCarriesTheCounts()
	{
		tracker.play(ACCOUNT);
		tracker.addSuperiorSpawn();
		tracker.addSuperiorSpawn();
		tracker.addBankTrip();
		tracker.addDeath();
		tracker.addDeath();
		tracker.addDeath();
		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), true);
		assertEquals(Integer.valueOf(2), record.getSuperiorsSpawned());
		assertEquals(Integer.valueOf(1), record.getBankTrips());
		assertEquals(Integer.valueOf(3), record.getDeaths());
		LedgerRecord read = gson.fromJson(gson.toJson(record), LedgerRecord.class);
		assertEquals(record, read);
		assertEquals(LedgerRecord.CURRENT_SCHEMA_VERSION, read.getSchemaVersion());
		assertEquals(2, LedgerRecord.CURRENT_SCHEMA_VERSION);
	}

	@Test
	public void zeroCountsAreSavedAsZeroNotUnknown()
	{
		tracker.play(ACCOUNT);
		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), true);
		String json = gson.toJson(record);
		assertTrue(json, json.contains("\"superiorsSpawned\":0"));
		assertTrue(json, json.contains("\"bankTrips\":0"));
		assertTrue(json, json.contains("\"deaths\":0"));
	}

	@Test
	public void olderRecordsReadAsUnknown()
	{
		LedgerRecord old = gson.fromJson("{\"schemaVersion\":2,\"id\":\"x\",\"monster\":\"Abyssal demons\",\"kills\":5}",
			LedgerRecord.class);
		assertNull(old.getSuperiorsSpawned());
		assertNull(old.getBankTrips());
		assertNull(old.getDeaths());
		String json = gson.toJson(old);
		assertFalse("unknown counts are not written", json.contains("superiorsSpawned"));
		assertFalse(json.contains("bankTrips"));
		assertFalse(json.contains("deaths"));
	}

	@Test
	public void snapshotRoundTripAndRestoreCarryTheCounts()
	{
		tracker.play(ACCOUNT);
		tracker.addSuperiorSpawn();
		tracker.addBankTrip();
		tracker.addBankTrip();
		tracker.addDeath();
		tracker.autoPause();
		SessionSnapshot snapshot = SessionSnapshot.fromSummary(tracker.activeSummary(), COUNTERS, now);
		assertEquals(Integer.valueOf(1), snapshot.getSuperiorsSpawned());
		assertEquals(Integer.valueOf(2), snapshot.getBankTrips());
		assertEquals(Integer.valueOf(1), snapshot.getDeaths());
		SessionSnapshot read = gson.fromJson(gson.toJson(snapshot), SessionSnapshot.class);
		assertEquals(snapshot, read);

		SessionTracker restored = new SessionTracker(() -> now);
		restored.observe("Abyssal demons", DEMON_ID, 50, 150, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, restored.restore(read, COUNTERS));
		assertEquals(1, restored.getSuperiorsSpawned());
		assertEquals(2, restored.getBankTrips());
		assertEquals(1, restored.getDeaths());
		assertTrue(restored.play(ACCOUNT));
		restored.addSuperiorSpawn();
		restored.addDeath();
		LedgerRecord record = LedgerRecord.fromSummary(restored.stopAndReset(), true);
		assertEquals(Integer.valueOf(2), record.getSuperiorsSpawned());
		assertEquals(Integer.valueOf(2), record.getBankTrips());
		assertEquals(Integer.valueOf(2), record.getDeaths());
	}

	@Test
	public void olderSnapshotRestoresFromZeroAndMismatchKeepsUnknown()
	{
		tracker.play(ACCOUNT);
		tracker.autoPause();
		SessionSnapshot snapshot = SessionSnapshot.fromSummary(tracker.activeSummary(), COUNTERS, now);
		snapshot.setSuperiorsSpawned(null);
		snapshot.setBankTrips(null);
		snapshot.setDeaths(null);
		SessionSnapshot old = gson.fromJson(gson.toJson(snapshot), SessionSnapshot.class);
		assertNull(old.getSuperiorsSpawned());

		// Saved as a partial record when it cannot be restored: the counts stay unknown.
		LedgerRecord partial = LedgerRecord.fromSummary(old.toSummary(), true);
		assertNull(partial.getSuperiorsSpawned());
		assertNull(partial.getBankTrips());
		assertNull(partial.getDeaths());

		SessionTracker restored = new SessionTracker(() -> now);
		restored.observe("Abyssal demons", DEMON_ID, 50, 150, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, restored.restore(old, COUNTERS));
		assertEquals(0, restored.getSuperiorsSpawned());
		assertEquals(0, restored.getBankTrips());
		assertEquals(0, restored.getDeaths());
		assertNotNull(restored.activeSummary().getBankTrips());
		assertNotNull(restored.activeSummary().getDeaths());
	}

	@Test
	public void snapshotToSummaryKeepsTheCounts()
	{
		tracker.play(ACCOUNT);
		tracker.addSuperiorSpawn();
		tracker.addBankTrip();
		tracker.addDeath();
		SessionSnapshot snapshot = SessionSnapshot.fromSummary(tracker.activeSummary(), COUNTERS, now);
		SessionTracker.Summary summary = snapshot.toSummary();
		assertEquals(Integer.valueOf(1), summary.getSuperiorsSpawned());
		assertEquals(Integer.valueOf(1), summary.getBankTrips());
		assertEquals(Integer.valueOf(1), summary.getDeaths());
	}
}
