package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Before;
import org.junit.Test;

/**
 * XP gained per task in {@link SessionTracker#observeXp}: counted only while RUNNING, against the previous
 * reading, never across a reset baseline, and carried through summaries, snapshots and records.
 */
public class SessionTrackerXpTest
{
	private static final int DEMON_ID = 42;
	private static final long ACCOUNT = 555L;
	private static final int[] COUNTERS = {100, 2, 0};

	private long now;
	private SessionTracker tracker;
	/** The player's XP, as the plugin would read it each tick. */
	private final Map<String, Integer> stats = new HashMap<>();

	@Before
	public void setUp()
	{
		now = 1_000_000L;
		tracker = new SessionTracker(() -> now);
		stats.put("SLAYER", 1_000_000);
		stats.put("HITPOINTS", 2_000_000);
		stats.put("MAGIC", 3_000_000);
		tracker.observe("Abyssal demons", DEMON_ID, 50, 150, false);
	}

	/** One tick's reading of {@link #stats}. */
	private boolean read()
	{
		return tracker.observeXp(new HashMap<>(stats));
	}

	private void gain(String skill, int xp)
	{
		stats.merge(skill, xp, Integer::sum);
	}

	private static Map<String, Long> xp(Object... skillAndXp)
	{
		Map<String, Long> map = new TreeMap<>();
		for (int i = 0; i < skillAndXp.length; i += 2)
		{
			map.put((String) skillAndXp[i], ((Number) skillAndXp[i + 1]).longValue());
		}
		return map;
	}

	// ---- Baseline and diff ----

	@Test
	public void xpAfterPlayIsCountedAgainstTheReadingBeforeIt()
	{
		read();
		gain("SLAYER", 500);
		assertFalse("before Play nothing counts", read());
		assertTrue(tracker.play(ACCOUNT));
		gain("SLAYER", 120);
		gain("HITPOINTS", 40);
		assertTrue(read());
		gain("SLAYER", 80);
		read();
		assertEquals(xp("SLAYER", 200, "HITPOINTS", 40), tracker.getXpGained());
		assertEquals(xp("SLAYER", 200, "HITPOINTS", 40), tracker.activeSummary().getXpGained());
	}

	@Test
	public void theFirstReadingOnlySetsTheBaseline()
	{
		assertTrue(tracker.play(ACCOUNT));
		assertFalse(read());
		gain("SLAYER", 50);
		read();
		assertEquals(xp("SLAYER", 50), tracker.getXpGained());
	}

	@Test
	public void noChangeAddsNothing()
	{
		tracker.play(ACCOUNT);
		read();
		assertFalse(read());
		assertTrue(tracker.getXpGained().isEmpty());
	}

	// ---- Pause and resume ----

	@Test
	public void xpWhilePausedIsNotCountedAndResumeCountsFromTheLatestReading()
	{
		tracker.play(ACCOUNT);
		read();
		gain("SLAYER", 100);
		read();
		tracker.pause();
		gain("SLAYER", 5_000);
		gain("MAGIC", 9_000);
		assertFalse(read());
		read();
		assertTrue(tracker.play(ACCOUNT));
		gain("MAGIC", 30);
		read();
		assertEquals(xp("SLAYER", 100, "MAGIC", 30), tracker.getXpGained());
	}

	@Test
	public void autoPauseStopsCountingToo()
	{
		tracker.play(ACCOUNT);
		read();
		tracker.autoPause();
		gain("SLAYER", 100);
		read();
		assertTrue(tracker.getXpGained().isEmpty());
	}

	// ---- Re-login, hop and account switch ----

	@Test
	public void aResetBaselineNeverCountsTheNextReading()
	{
		tracker.play(ACCOUNT);
		read();
		gain("SLAYER", 10);
		read();
		// Login, hop or lost connection: the stats are re-sent, possibly with XP the session never saw being gained.
		tracker.resetXpBaseline();
		gain("SLAYER", 400_000);
		assertFalse(read());
		gain("SLAYER", 25);
		read();
		assertEquals(xp("SLAYER", 35), tracker.getXpGained());
	}

	@Test
	public void anUnsyncedReadingDropsTheBaseline()
	{
		tracker.play(ACCOUNT);
		read();
		// Hitpoints XP read 0: the caller passes null and the stats then arrive in a burst.
		assertFalse(tracker.observeXp(null));
		gain("SLAYER", 1_000);
		assertFalse("the login burst is only a baseline", read());
		assertTrue(tracker.getXpGained().isEmpty());
	}

	@Test
	public void aDecreaseIsAnotherAccountAndOnlyMovesTheBaseline()
	{
		tracker.play(ACCOUNT);
		read();
		Map<String, Integer> other = new HashMap<>();
		other.put("SLAYER", 13_034_431);
		other.put("HITPOINTS", 1_154);
		other.put("MAGIC", 0);
		assertFalse(tracker.observeXp(other));
		other.put("SLAYER", 13_034_500);
		assertTrue(tracker.observeXp(other));
		assertEquals(xp("SLAYER", 69), tracker.getXpGained());
	}

	// ---- Grace window ----

	@Test
	public void aHeldCompletionGetsNoXpAfterItsCompletionTick()
	{
		tracker.observe("Abyssal demons", DEMON_ID, 1, 150, false);
		tracker.play(ACCOUNT);
		read();
		// The completion tick: the plugin reads XP before observing the task, so the final kill's XP counts.
		gain("SLAYER", 300);
		read();
		assertEquals(SessionTracker.ObserveResult.COMPLETED, tracker.observe("Abyssal demons", DEMON_ID, 0, 150, true));
		assertTrue(tracker.holdCompletion());

		gain("SLAYER", 999);
		assertFalse(read());
		assertNull(tracker.tickCompletion());
		gain("PRAYER", 5);
		read();
		SessionTracker.Summary saved = tracker.tickCompletion();
		assertNotNull(saved);
		assertEquals(xp("SLAYER", 300), saved.getXpGained());
		assertEquals(xp("SLAYER", 300), LedgerRecord.fromSummary(saved, false).getXpGained());
	}

	@Test
	public void aNewSessionStartsWithNoXpAndCountsItsOwn()
	{
		tracker.play(ACCOUNT);
		read();
		gain("SLAYER", 100);
		read();
		SessionTracker.Summary first = tracker.stopAndReset();
		assertEquals(xp("SLAYER", 100), first.getXpGained());
		assertTrue(tracker.getXpGained().isEmpty());
		gain("SLAYER", 50);
		read();
		tracker.play(ACCOUNT);
		gain("SLAYER", 7);
		read();
		assertEquals(xp("SLAYER", 7), tracker.getXpGained());
	}

	// ---- Snapshot and records ----

	@Test
	public void xpSurvivesASnapshotRoundTripAndTheRestoredSessionKeepsCounting()
	{
		tracker.play(ACCOUNT);
		read();
		gain("SLAYER", 400);
		gain("MAGIC", 1_200);
		read();
		tracker.autoPause();
		SessionSnapshot snapshot = SessionSnapshot.fromSummary(tracker.activeSummary(), COUNTERS, now);
		assertEquals(xp("SLAYER", 400, "MAGIC", 1_200), snapshot.getXpGained());
		Gson gson = new Gson();
		SessionSnapshot read = gson.fromJson(gson.toJson(snapshot), SessionSnapshot.class);
		assertEquals(snapshot, read);

		SessionTracker restored = new SessionTracker(() -> now);
		restored.observe("Abyssal demons", DEMON_ID, 50, 150, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, restored.restore(read, COUNTERS));
		assertEquals(xp("SLAYER", 400, "MAGIC", 1_200), restored.getXpGained());
		// The restored tracker has no baseline: the login's first reading only sets it.
		restored.observeXp(new HashMap<>(stats));
		assertTrue(restored.play(ACCOUNT));
		gain("SLAYER", 100);
		restored.observeXp(new HashMap<>(stats));
		assertEquals(xp("SLAYER", 500, "MAGIC", 1_200), LedgerRecord.fromSummary(restored.stopAndReset(), false)
			.getXpGained());
	}

	@Test
	public void aSnapshotFromBeforeXpWasTrackedRestoresWithNoneAndCountsFromThere()
	{
		tracker.play(ACCOUNT);
		tracker.autoPause();
		SessionSnapshot snapshot = SessionSnapshot.fromSummary(tracker.activeSummary(), COUNTERS, now);
		snapshot.setXpGained(null);
		Gson gson = new Gson();
		String json = gson.toJson(snapshot);
		assertFalse("an older file has no field", json.contains("xpGained"));
		SessionSnapshot old = gson.fromJson(json, SessionSnapshot.class);

		// Saved as partial (it no longer matches): the XP is unknown, not 0.
		assertNull(old.toSummary().getXpGained());
		assertNull(LedgerRecord.fromSummary(old.toSummary(), true).getXpGained());

		SessionTracker restored = new SessionTracker(() -> now);
		restored.observe("Abyssal demons", DEMON_ID, 50, 150, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, restored.restore(old, COUNTERS));
		assertEquals(Collections.emptyMap(), restored.getXpGained());
	}

	@Test
	public void aRecordWithNoXpGainedStoresAnEmptyMap()
	{
		tracker.play(ACCOUNT);
		read();
		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), false);
		assertEquals(Collections.emptyMap(), record.getXpGained());
		assertEquals("XP: 0", LedgerHistory.formatXp(record));
	}

	@Test
	public void anOldRecordShowsADash()
	{
		LedgerRecord old = new Gson().fromJson("{\"schemaVersion\":2,\"id\":\"a\",\"monster\":\"Abyssal demons\","
			+ "\"kills\":5,\"activeMillis\":600000}", LedgerRecord.class);
		assertNull(old.getXpGained());
		assertEquals("XP: -", LedgerHistory.formatXp(old));
		assertEquals("", LedgerHistory.formatXpRate(old));
	}

	@Test
	public void aRecordShowsItsTotalAndRate()
	{
		LedgerRecord record = new Gson().fromJson("{\"schemaVersion\":2,\"id\":\"a\",\"monster\":\"Abyssal demons\","
			+ "\"kills\":5,\"activeMillis\":1800000,\"xpGained\":{\"SLAYER\":15000,\"HITPOINTS\":5000}}",
			LedgerRecord.class);
		assertEquals(xp("SLAYER", 15_000, "HITPOINTS", 5_000), record.getXpGained());
		assertEquals("XP: 20K", LedgerHistory.formatXp(record));
		assertEquals("XP/h: 40K", LedgerHistory.formatXpRate(record));
	}
}
