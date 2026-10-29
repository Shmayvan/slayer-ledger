package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import org.junit.Before;
import org.junit.Test;

/**
 * The note on a session: when it can be set (running, paused, or held for late loot), and how it travels through
 * snapshots, restores, records and JSON.
 */
public class SessionTrackerNoteTest
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
	public void setWhileRunningOrPausedAndNormalized()
	{
		assertFalse("no session", tracker.setNote(null, "Barrage"));
		assertTrue(tracker.play(ACCOUNT));
		String id = tracker.getSessionId();
		assertFalse("another session", tracker.setNote("other", "Barrage"));
		assertTrue(tracker.setNote(id, "  Barrage\n "));
		assertEquals("Barrage", tracker.getNote());
		assertEquals("Barrage", tracker.activeSummary().getNote());

		tracker.pause();
		assertTrue(tracker.setNote(id, "Prayer gear"));
		assertEquals("Prayer gear", tracker.getNote());
		assertTrue("cleared", tracker.setNote(id, "   "));
		assertNull(tracker.getNote());
	}

	@Test
	public void clearedWhenTheSessionEndsAndRefusedAfterwards()
	{
		tracker.play(ACCOUNT);
		String id = tracker.getSessionId();
		tracker.setNote(id, "Cannon");
		SessionTracker.Summary ended = tracker.stopAndReset();
		assertEquals("Cannon", ended.getNote());
		assertNull(tracker.getNote());
		assertFalse("the session is gone", tracker.setNote(id, "Barrage"));

		tracker.play(ACCOUNT);
		assertNull("a new session has no note", tracker.activeSummary().getNote());
	}

	@Test
	public void aNoteSetDuringTheGraceWindowReachesTheHeldSession()
	{
		tracker.play(ACCOUNT);
		String id = tracker.getSessionId();
		tracker.setNote(id, "Range pray");
		tracker.observe("Abyssal demons", DEMON_ID, 0, 150, true);
		assertTrue(tracker.holdCompletion());
		assertTrue(tracker.setNote(id, "Barrage"));

		// A new session started during the grace window keeps its own note.
		tracker.observe("Abyssal demons", DEMON_ID, 30, 30, false);
		assertTrue(tracker.play(ACCOUNT));
		String next = tracker.getSessionId();
		assertTrue(tracker.setNote(next, "Cannon"));
		assertTrue(tracker.setNote(id, "Barrage, burst"));
		ItemTally loot = new ItemTally();
		loot.add(526, 1);
		tracker.addLoot(loot);

		SessionTracker.Summary held = tracker.takePendingCompletion();
		assertEquals("Barrage, burst", held.getNote());
		assertEquals("late loot keeps the note", 1, held.getLoot().get(526));
		assertEquals("Cannon", tracker.getNote());
		assertFalse("no longer held", tracker.setNote(id, "x"));
	}

	@Test
	public void recordCarriesTheNoteThroughJson()
	{
		tracker.play(ACCOUNT);
		tracker.setNote(tracker.getSessionId(), "Prayer gear");
		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), false);
		assertEquals("Prayer gear", record.getNote());
		String json = gson.toJson(record);
		assertTrue(json, json.contains("\"note\":\"Prayer gear\""));
		LedgerRecord read = gson.fromJson(json, LedgerRecord.class);
		assertEquals(record, read);
		assertEquals(2, read.getSchemaVersion());
	}

	@Test
	public void recordWithoutANoteOmitsIt()
	{
		tracker.play(ACCOUNT);
		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), false);
		assertNull(record.getNote());
		String json = gson.toJson(record);
		assertFalse(json, json.contains("note"));
		assertEquals(record, gson.fromJson(json, LedgerRecord.class));
	}

	@Test
	public void olderRecordsHaveNoNote()
	{
		LedgerRecord old = gson.fromJson("{\"schemaVersion\":2,\"id\":\"x\",\"monster\":\"Abyssal demons\",\"kills\":5}",
			LedgerRecord.class);
		assertNull(old.getNote());
		SessionSnapshot oldSnapshot = gson.fromJson("{\"schemaVersion\":2,\"sessionId\":\"s\",\"accountHash\":555,"
			+ "\"creatureId\":42,\"remaining\":50,\"kills\":3}", SessionSnapshot.class);
		assertNull(oldSnapshot.getNote());
		SessionTracker restored = new SessionTracker(() -> now);
		restored.observe("Abyssal demons", DEMON_ID, 50, 150, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, restored.restore(oldSnapshot, COUNTERS));
		assertNull(restored.getNote());
		assertTrue("a note can be added after the restore", restored.setNote("s", "Cannon"));
	}

	@Test
	public void snapshotRoundTripAndRestoreCarryTheNote()
	{
		tracker.play(ACCOUNT);
		tracker.setNote(tracker.getSessionId(), "Strength gear");
		tracker.autoPause();
		SessionSnapshot snapshot = SessionSnapshot.fromSummary(tracker.activeSummary(), COUNTERS, now);
		assertEquals("Strength gear", snapshot.getNote());
		SessionSnapshot read = gson.fromJson(gson.toJson(snapshot), SessionSnapshot.class);
		assertEquals(snapshot, read);

		SessionTracker restored = new SessionTracker(() -> now);
		restored.observe("Abyssal demons", DEMON_ID, 50, 150, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, restored.restore(read, COUNTERS));
		assertEquals("Strength gear", restored.getNote());
		assertEquals("as a partial record when it cannot be restored", "Strength gear",
			LedgerRecord.fromSummary(read.toSummary(), true).getNote());
	}

	@Test
	public void aHandEditedSnapshotNoteIsNormalized()
	{
		tracker.play(ACCOUNT);
		tracker.autoPause();
		SessionSnapshot snapshot = SessionSnapshot.fromSummary(tracker.activeSummary(), COUNTERS, now);
		snapshot.setNote("  Barrage \n");
		SessionTracker restored = new SessionTracker(() -> now);
		restored.observe("Abyssal demons", DEMON_ID, 50, 150, false);
		restored.restore(snapshot, COUNTERS);
		assertEquals("Barrage", restored.getNote());
		assertEquals("Barrage", snapshot.toSummary().getNote());
	}
}
