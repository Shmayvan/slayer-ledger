package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.SessionTracker.RestoreResult;
import static com.shmayvan.slayerledger.SessionTracker.State;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import org.junit.Before;
import org.junit.Test;

/**
 * Snapshot / restore of in-progress sessions (persisted on logout, client exit, plugin shutdown, account switch).
 */
public class SessionRestoreTest
{
	private static final int DEMON_ID = 42;
	private static final int DRAGON_ID = 43;
	private static final long ACCOUNT = 555L;
	private static final int[] COUNTERS = {100, 2, 0};

	private long now;
	private SessionTracker tracker;

	@Before
	public void setUp()
	{
		now = 1_000_000L;
		tracker = new SessionTracker(() -> now);
	}

	/** A session with 7 kills and 60s of active time, snapshotted while paused at 43 remaining. */
	private SessionSnapshot snapshotOfSession()
	{
		SessionTracker original = new SessionTracker(() -> now);
		original.observe("Abyssal demons", DEMON_ID, 50, 150, false);
		original.play(ACCOUNT);
		now += 60_000;
		original.observe("Abyssal demons", DEMON_ID, 43, 150, false);
		original.autoPause();
		now += 5_000;
		SessionTracker.Summary summary = original.activeSummary();
		assertNotNull(summary);
		return SessionSnapshot.fromSummary(summary, COUNTERS, now);
	}

	@Test
	public void snapshotCapturesSession()
	{
		SessionSnapshot s = snapshotOfSession();
		assertNotNull(s.getSessionId());
		assertEquals(ACCOUNT, s.getAccountHash());
		assertEquals("Abyssal demons", s.getMonster());
		assertEquals(DEMON_ID, s.getCreatureId());
		assertEquals(50, s.getInitialAmount());
		assertEquals(Integer.valueOf(150), s.getOriginalAmount());
		assertEquals(7, s.getKills());
		assertEquals(60_000, s.getActiveMillis());
		assertEquals(1_000_000L, s.getStartedAt());
		assertEquals(43, s.getRemaining());
		assertArrayEquals(COUNTERS, s.getCompletionCounters());
		assertEquals(SessionSnapshot.CURRENT_SCHEMA_VERSION, s.getSchemaVersion());
	}

	@Test
	public void snapshotSurvivesJsonRoundTrip()
	{
		Gson gson = new Gson();
		SessionSnapshot s = snapshotOfSession();
		assertEquals(s, gson.fromJson(gson.toJson(s), SessionSnapshot.class));
	}

	@Test
	public void restoresMatchingTaskAsPausedAndIgnoresUntrackedKills()
	{
		SessionSnapshot s = snapshotOfSession();
		// 3 kills happened while the plugin was not watching
		tracker.observe("Abyssal demons", DEMON_ID, 40, 150, false);
		assertEquals(RestoreResult.RESTORED, tracker.restore(s, COUNTERS));
		assertEquals(State.PAUSED, tracker.getState());
		assertTrue(tracker.isRestoredPending());
		assertEquals(7, tracker.getKills());
		assertEquals(60_000, tracker.getActiveMillis());
		assertEquals(ACCOUNT, tracker.getSessionAccountHash());

		// resume: baseline is the current remaining count
		assertTrue(tracker.play(ACCOUNT));
		assertFalse(tracker.isRestoredPending());
		now += 10_000;
		tracker.observe("Abyssal demons", DEMON_ID, 38, 150, false);
		SessionTracker.Summary summary = tracker.stopAndReset();
		assertEquals(9, summary.getKills());
		assertEquals(70_000, summary.getActiveMillis());
		assertEquals(s.getSessionId(), summary.getSessionId());
		assertEquals(50, summary.getInitialAmount());
		assertEquals(150, summary.getOriginalAmount());
		assertEquals(1_000_000L, summary.getStartedAt());
		assertEquals(s.getSessionId(), LedgerRecord.fromSummary(summary, true).getId());
	}

	@Test
	public void restoreRefusedBeforeObservationOrWithActiveSession()
	{
		SessionSnapshot s = snapshotOfSession();
		assertEquals(RestoreResult.REFUSED, tracker.restore(s, COUNTERS));
		tracker.observe("Abyssal demons", DEMON_ID, 43, 150, false);
		tracker.play(ACCOUNT);
		assertEquals(RestoreResult.REFUSED, tracker.restore(s, COUNTERS));
		assertEquals(State.RUNNING, tracker.getState());
	}

	@Test
	public void differentCreatureIsMismatch()
	{
		SessionSnapshot s = snapshotOfSession();
		tracker.observe("Black dragons", DRAGON_ID, 20, 20, false);
		assertEquals(RestoreResult.MISMATCH, tracker.restore(s, COUNTERS));
		assertEquals(State.IDLE, tracker.getState());
	}

	@Test
	public void noTaskIsMismatch()
	{
		SessionSnapshot s = snapshotOfSession();
		tracker.observe(null, SessionTracker.NO_CREATURE, 0, false);
		assertEquals(RestoreResult.MISMATCH, tracker.restore(s, COUNTERS));
	}

	@Test
	public void higherRemainingIsMismatch()
	{
		SessionSnapshot s = snapshotOfSession();
		tracker.observe("Abyssal demons", DEMON_ID, 44, 150, false);
		assertEquals(RestoreResult.MISMATCH, tracker.restore(s, COUNTERS));
	}

	@Test
	public void changedAssignedSizeIsMismatch()
	{
		SessionSnapshot s = snapshotOfSession();
		tracker.observe("Abyssal demons", DEMON_ID, 30, 120, false);
		assertEquals(RestoreResult.MISMATCH, tracker.restore(s, COUNTERS));
	}

	@Test
	public void completionInBetweenIsMismatch()
	{
		SessionSnapshot s = snapshotOfSession();
		tracker.observe("Abyssal demons", DEMON_ID, 30, 150, false);
		assertEquals(RestoreResult.MISMATCH, tracker.restore(s, new int[]{101, 2, 0}));
		// unknown counters on either side do not block
		assertEquals(RestoreResult.RESTORED, tracker.restore(s, null));
	}

	@Test
	public void unknownSizeOrCountersStillRestore()
	{
		SessionSnapshot s = snapshotOfSession();
		s.setOriginalAmount(null);
		s.setCompletionCounters(null);
		tracker.observe("Abyssal demons", DEMON_ID, 43, false);
		assertEquals(RestoreResult.RESTORED, tracker.restore(s, COUNTERS));
	}

	@Test
	public void noAccountIsMismatch()
	{
		SessionSnapshot s = snapshotOfSession();
		s.setAccountHash(SessionTracker.NO_ACCOUNT);
		tracker.observe("Abyssal demons", DEMON_ID, 43, 150, false);
		assertEquals(RestoreResult.MISMATCH, tracker.restore(s, COUNTERS));
	}

	@Test
	public void mismatchSummaryIsIncompleteWithSnapshotValues()
	{
		SessionSnapshot s = snapshotOfSession();
		SessionTracker.Summary summary = s.toSummary();
		assertTrue(summary.hasTask());
		assertFalse(summary.isCompleted());
		assertEquals(7, summary.getKills());
		assertEquals(s.getSavedAt(), summary.getEndedAt());
		assertEquals(s.getSessionId(), summary.getSessionId());
		assertEquals(SaveDecision.Action.SAVE_PARTIAL_WITH_NOTICE,
			SaveDecision.decide(summary, SaveDecision.EndReason.RESTORE_MISMATCH, true));
		LedgerRecord record = LedgerRecord.fromSummary(summary, true);
		assertEquals(s.getSessionId(), record.getId());
		assertTrue(record.isPartial());
		assertEquals(Integer.valueOf(150), record.getOriginalAmount());
	}

	@Test
	public void activeSummaryIsNullWhenIdleAndKeepsSessionInMemory()
	{
		assertNull(tracker.activeSummary());
		tracker.observe("Abyssal demons", DEMON_ID, 10, false);
		tracker.play(ACCOUNT);
		tracker.autoPause();
		assertNotNull(tracker.activeSummary());
		// taking a snapshot on logout does not end the in-memory session
		assertEquals(State.PAUSED, tracker.getState());
	}

	@Test
	public void snapshotDuringPendingZeroResumesFromCountBeforeTheZero()
	{
		tracker.observe("Abyssal demons", DEMON_ID, 10, false);
		tracker.play(ACCOUNT);
		tracker.observe("Abyssal demons", DEMON_ID, 8, false);
		tracker.observe(null, SessionTracker.NO_CREATURE, 0, false);
		SessionSnapshot s = SessionSnapshot.fromSummary(tracker.activeSummary(), null, now);
		assertEquals(8, s.getRemaining());
		assertEquals(2, s.getKills());
	}

	@Test
	public void newSessionsGetDistinctIds()
	{
		tracker.observe("Abyssal demons", DEMON_ID, 10, false);
		tracker.play(ACCOUNT);
		String first = tracker.stopAndReset().getSessionId();
		tracker.play(ACCOUNT);
		String second = tracker.stopAndReset().getSessionId();
		assertNotNull(first);
		assertFalse(first.equals(second));
	}

	@Test
	public void completionCounterDecreaseIsMismatch()
	{
		SessionSnapshot s = snapshotOfSession();
		tracker.observe("Abyssal demons", DEMON_ID, 30, 150, false);
		// Turael reset / cancellation lowers the streak
		assertEquals(RestoreResult.MISMATCH, tracker.restore(s, new int[]{50, 2, 0}));
		assertEquals(RestoreResult.MISMATCH, tracker.restore(s, new int[]{100, 1, 0}));
		assertEquals(RestoreResult.RESTORED, tracker.restore(s, COUNTERS));
	}

	@Test
	public void allZeroCountersAreTreatedAsNotYetSynced()
	{
		SessionSnapshot s = snapshotOfSession();
		tracker.observe("Abyssal demons", DEMON_ID, 30, 150, false);
		assertEquals(RestoreResult.RESTORED, tracker.restore(s, new int[]{0, 0, 0}));
	}

	/** A session kept in memory across logout: log out at 43 left, then log back in. */
	private SessionSnapshot logOutWithSessionInMemory()
	{
		tracker.observe("Abyssal demons", DEMON_ID, 50, 150, false);
		tracker.play(ACCOUNT);
		tracker.observe("Abyssal demons", DEMON_ID, 43, 150, false);
		tracker.autoPause();
		SessionSnapshot s = SessionSnapshot.fromSummary(tracker.activeSummary(), COUNTERS, now);
		tracker.clearCurrentTask();
		return s;
	}

	@Test
	public void reloginWithSameTaskStillMatches()
	{
		SessionSnapshot s = logOutWithSessionInMemory();
		tracker.observe("Abyssal demons", DEMON_ID, 43, 150, false);
		assertTrue(tracker.activeSessionStillMatches(s, COUNTERS));
		assertEquals(State.PAUSED, tracker.getState());
	}

	@Test
	public void reloginAfterFinishingAndGettingSameCreatureIsMismatch()
	{
		SessionSnapshot s = logOutWithSessionInMemory();
		// finished the task elsewhere, then got the same creature again with a new size
		tracker.observe("Abyssal demons", DEMON_ID, 180, 180, false);
		assertFalse(tracker.activeSessionStillMatches(s, new int[]{101, 2, 0}));
		// even without size or counter evidence, the higher remaining count gives it away
		assertFalse(tracker.activeSessionStillMatches(s, null));
	}

	@Test
	public void reloginWithStreakDecreaseIsMismatch()
	{
		SessionSnapshot s = logOutWithSessionInMemory();
		tracker.observe("Abyssal demons", DEMON_ID, 40, 150, false);
		assertFalse(tracker.activeSessionStillMatches(s, new int[]{60, 2, 0}));
		// a counter reading zero is unknown (possibly unsynced), not a reset
		assertTrue(tracker.activeSessionStillMatches(s, new int[]{0, 2, 0}));
	}

	@Test
	public void perCounterUnknownHandling()
	{
		// zero now, non-zero before: unknown
		assertFalse(SessionTracker.counterChanged(100, 0));
		// unchanged
		assertFalse(SessionTracker.counterChanged(100, 100));
		assertFalse(SessionTracker.counterChanged(0, 0));
		// increases (including from zero) and non-zero decreases are changes
		assertTrue(SessionTracker.counterChanged(100, 101));
		assertTrue(SessionTracker.counterChanged(0, 1));
		assertTrue(SessionTracker.counterChanged(100, 40));
	}

	@Test
	public void oneUnsyncedCounterDoesNotHideAnotherCounterChange()
	{
		SessionSnapshot s = snapshotOfSession();
		tracker.observe("Abyssal demons", DEMON_ID, 30, 150, false);
		assertEquals(RestoreResult.RESTORED, tracker.restore(s, new int[]{0, 2, 0}));
		tracker.stopAndReset();
		assertEquals(RestoreResult.MISMATCH, tracker.restore(s, new int[]{0, 3, 0}));
	}

	@Test
	public void restoreCheckDefersWhileCountReadsZeroUpToCap()
	{
		assertTrue(SessionTracker.shouldDeferRestoreCheck(0, 0));
		assertTrue(SessionTracker.shouldDeferRestoreCheck(0, SessionTracker.RESTORE_SYNC_MAX_TICKS - 1));
		assertFalse(SessionTracker.shouldDeferRestoreCheck(0, SessionTracker.RESTORE_SYNC_MAX_TICKS));
		assertFalse(SessionTracker.shouldDeferRestoreCheck(5, 0));
		assertFalse(SessionTracker.shouldDeferRestoreCheck(1, 3));
	}

	@Test
	public void zeroCountAfterCapIsMismatch()
	{
		SessionSnapshot s = snapshotOfSession();
		tracker.observe(null, SessionTracker.NO_CREATURE, 0, false);
		assertFalse(SessionTracker.shouldDeferRestoreCheck(0, SessionTracker.RESTORE_SYNC_MAX_TICKS));
		assertEquals(RestoreResult.MISMATCH, tracker.restore(s, COUNTERS));
	}

	@Test
	public void reloginCheckIgnoresOtherOrNoSession()
	{
		SessionSnapshot s = logOutWithSessionInMemory();
		tracker.observe("Black dragons", DRAGON_ID, 20, false);
		SessionSnapshot other = snapshotOfSession();
		assertTrue(tracker.activeSessionStillMatches(other, COUNTERS));
		assertTrue(tracker.activeSessionStillMatches(null, COUNTERS));
		tracker.stopAndReset();
		assertTrue(tracker.activeSessionStillMatches(s, COUNTERS));
	}
}
