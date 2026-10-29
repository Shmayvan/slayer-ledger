package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.SessionTracker.ObserveResult;
import static com.shmayvan.slayerledger.SessionTracker.State;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class SessionTrackerTest
{
	private static final int DEMON_ID = 42;
	private static final int DRAGON_ID = 43;
	private static final long ACCOUNT = 555L;
	private static final long OTHER_ACCOUNT = 777L;

	private long now;
	private SessionTracker tracker;

	@Before
	public void setUp()
	{
		now = 1_000_000L;
		tracker = new SessionTracker(() -> now);
		// Simulates the first post-login observation (no task yet) so Play is allowed.
		noTask();
	}

	private ObserveResult noTask()
	{
		return tracker.observe(null, SessionTracker.NO_CREATURE, 0, false);
	}

	private ObserveResult demons(int remaining)
	{
		return tracker.observe("Abyssal demons", DEMON_ID, remaining, false);
	}

	@Test
	public void transitions()
	{
		assertEquals(State.IDLE, tracker.getState());
		assertTrue(tracker.play(ACCOUNT));
		assertEquals(State.RUNNING, tracker.getState());
		assertTrue(tracker.pause());
		assertEquals(State.PAUSED, tracker.getState());
		assertTrue(tracker.play(ACCOUNT));
		assertEquals(State.RUNNING, tracker.getState());
		assertTrue(tracker.stop());
		assertEquals(State.STOPPED, tracker.getState());
		assertTrue(tracker.reset());
		assertEquals(State.IDLE, tracker.getState());
	}

	@Test
	public void stopFromPaused()
	{
		tracker.play(ACCOUNT);
		tracker.pause();
		assertTrue(tracker.stop());
		assertEquals(State.STOPPED, tracker.getState());
	}

	@Test
	public void invalidTransitionsAreNoOps()
	{
		assertFalse(tracker.pause());
		assertFalse(tracker.stop());
		assertFalse(tracker.autoPause());
		assertFalse(tracker.reset());
		assertNull(tracker.stopAndReset());
		assertEquals(State.IDLE, tracker.getState());

		tracker.play(ACCOUNT);
		assertFalse(tracker.play(ACCOUNT));
		assertFalse(tracker.reset());
		assertEquals(State.RUNNING, tracker.getState());

		tracker.pause();
		assertFalse(tracker.pause());
		assertFalse(tracker.autoPause());
		assertEquals(State.PAUSED, tracker.getState());

		tracker.stop();
		assertFalse(tracker.play(ACCOUNT));
		assertFalse(tracker.pause());
		assertFalse(tracker.stop());
		assertEquals(State.STOPPED, tracker.getState());
	}

	@Test
	public void elapsedOnlyAccumulatesWhileRunning()
	{
		now += 5_000; // idle time is not counted
		tracker.play(ACCOUNT);
		now += 10_000;
		assertEquals(10_000, tracker.getActiveMillis());
		tracker.pause();
		now += 60_000;
		assertEquals(10_000, tracker.getActiveMillis());
		tracker.play(ACCOUNT);
		now += 2_500;
		assertEquals(12_500, tracker.getActiveMillis());
		tracker.stop();
		now += 30_000;
		assertEquals(12_500, tracker.getActiveMillis());
		assertEquals(12_500, tracker.summary().getActiveMillis());
	}

	@Test
	public void countsDecreasesOnlyWhileRunning()
	{
		demons(100);
		demons(99); // before session start: ignored
		tracker.play(ACCOUNT);
		assertEquals(0, tracker.getKills());
		demons(97);
		assertEquals(2, tracker.getKills());
		tracker.pause();
		demons(90); // paused: ignored, but baseline moves
		assertEquals(2, tracker.getKills());
		tracker.play(ACCOUNT);
		demons(89);
		assertEquals(3, tracker.getKills());
		SessionTracker.Summary summary = tracker.stopAndReset();
		assertNotNull(summary);
		assertEquals(99, summary.getInitialAmount());
		assertEquals(3, summary.getKills());
		assertEquals("Abyssal demons", summary.getMonster());
		assertEquals(DEMON_ID, summary.getCreatureId());
		assertFalse(summary.isCompleted());
		assertEquals(State.IDLE, tracker.getState());
		assertEquals(0, tracker.getKills());
	}

	@Test
	public void increasesAreNotKills()
	{
		tracker.play(ACCOUNT);
		demons(50); // first observation: baseline only
		assertEquals(0, tracker.getKills());
		demons(60); // e.g. extended task
		assertEquals(0, tracker.getKills());
		demons(58);
		assertEquals(2, tracker.getKills());
	}

	@Test
	public void sessionStartedWithoutTaskAdoptsFirstTask()
	{
		tracker.play(ACCOUNT);
		tracker.observe(null, SessionTracker.NO_CREATURE, 0, false);
		assertFalse(tracker.hasSessionTask());
		demons(120);
		assertTrue(tracker.hasSessionTask());
		demons(119);
		assertEquals(1, tracker.getKills());
		assertEquals(120, tracker.summary().getInitialAmount());
	}

	@Test
	public void autoPause()
	{
		demons(10);
		tracker.play(ACCOUNT);
		now += 1_000;
		assertTrue(tracker.autoPause());
		assertEquals(State.PAUSED, tracker.getState());
		now += 5_000;
		assertEquals(1_000, tracker.getActiveMillis());
		// resume is manual: further observations do not resume
		demons(10);
		assertEquals(State.PAUSED, tracker.getState());
	}

	@Test
	public void completionWithSignal()
	{
		demons(3);
		tracker.play(ACCOUNT);
		demons(1);
		assertEquals(ObserveResult.COMPLETED, tracker.observe(null, SessionTracker.NO_CREATURE, 0, true));
		assertTrue(tracker.isCompleted());
		assertEquals(3, tracker.getKills());
		SessionTracker.Summary summary = tracker.stopAndReset();
		assertTrue(summary.isCompleted());
		assertEquals(3, summary.getKills());
	}

	@Test
	public void completionSignalOnLaterObservation()
	{
		demons(2);
		tracker.play(ACCOUNT);
		assertEquals(ObserveResult.TASK_CLEARED, tracker.observe(null, SessionTracker.NO_CREATURE, 0, false));
		assertFalse(tracker.isCompleted());
		assertEquals(ObserveResult.COMPLETED, tracker.observe(null, SessionTracker.NO_CREATURE, 0, true));
		assertTrue(tracker.isCompleted());
		assertEquals(2, tracker.getKills());
	}

	@Test
	public void clearedWithoutCompletionIsNotCompleted()
	{
		demons(80);
		tracker.play(ACCOUNT);
		demons(75);
		// cancelled task: count drops to zero without a completion signal
		assertEquals(ObserveResult.TASK_CLEARED, tracker.observe(null, SessionTracker.NO_CREATURE, 0, false));
		assertEquals(5, tracker.getKills());
		assertFalse(tracker.isCompleted());
		// then a new task is assigned
		assertEquals(ObserveResult.TASK_CHANGED, tracker.observe("Black dragons", DRAGON_ID, 20, false));
		SessionTracker.Summary summary = tracker.stopAndReset();
		assertFalse(summary.isCompleted());
		assertEquals(5, summary.getKills());
		assertEquals("Abyssal demons", summary.getMonster());
		// the new task is current for the next session
		assertEquals("Black dragons", tracker.getCurrentMonster());
		assertEquals(20, tracker.getCurrentRemaining());
	}

	@Test
	public void taskChangeDuringSession()
	{
		demons(50);
		tracker.play(ACCOUNT);
		demons(45);
		assertEquals(ObserveResult.TASK_CHANGED, tracker.observe("Black dragons", DRAGON_ID, 30, false));
		// change of task does not count as kills
		assertEquals(5, tracker.getKills());
		SessionTracker.Summary summary = tracker.stopAndReset();
		assertEquals("Abyssal demons", summary.getMonster());
		tracker.play(ACCOUNT);
		assertEquals("Black dragons", tracker.summary().getMonster());
		assertEquals(30, tracker.summary().getInitialAmount());
	}

	@Test
	public void completionWhilePausedDoesNotAddKills()
	{
		demons(1);
		tracker.play(ACCOUNT);
		tracker.pause();
		assertEquals(ObserveResult.COMPLETED, tracker.observe(null, SessionTracker.NO_CREATURE, 0, true));
		assertTrue(tracker.isCompleted());
		assertEquals(0, tracker.getKills());
	}

	@Test
	public void partialFlagOnRecord()
	{
		demons(10);
		tracker.play(ACCOUNT);
		now += 3_000;
		demons(8);
		SessionTracker.Summary summary = tracker.stopAndReset();

		LedgerRecord partial = LedgerRecord.fromSummary(summary, true);
		assertTrue(partial.isPartial());
		assertFalse(partial.isCompleted());
		assertEquals(2, partial.getKills());
		assertEquals(10, partial.getInitialAmount());
		assertEquals(3_000, partial.getActiveMillis());
		assertEquals(Integer.valueOf(DEMON_ID), partial.getCreatureId());
		assertEquals(LedgerRecord.CURRENT_SCHEMA_VERSION, partial.getSchemaVersion());
		assertNotNull(partial.getId());
		assertEquals(1_000_000L, partial.getStartedAt());
		assertEquals(1_003_000L, partial.getEndedAt());
	}

	@Test
	public void playRefusedUntilTaskObserved()
	{
		SessionTracker fresh = new SessionTracker(() -> now);
		assertFalse(fresh.canPlay(ACCOUNT));
		assertFalse(fresh.play(ACCOUNT));
		assertEquals(State.IDLE, fresh.getState());
		fresh.observe("Abyssal demons", DEMON_ID, 10, false);
		assertTrue(fresh.canPlay(ACCOUNT));
		assertFalse(fresh.canPlay(SessionTracker.NO_ACCOUNT));
		assertFalse(fresh.play(SessionTracker.NO_ACCOUNT));
		assertTrue(fresh.play(ACCOUNT));
	}

	@Test
	public void clearCurrentTaskForgetsStaleTaskAndBlocksPlay()
	{
		demons(50);
		tracker.clearCurrentTask();
		assertNull(tracker.getCurrentMonster());
		assertEquals(0, tracker.getCurrentRemaining());
		assertFalse(tracker.isTaskObserved());
		assertFalse(tracker.canPlay(ACCOUNT));
		assertFalse(tracker.play(ACCOUNT));

		// first observation on the new login enables Play with the fresh task
		tracker.observe("Black dragons", DRAGON_ID, 12, false);
		assertTrue(tracker.play(OTHER_ACCOUNT));
		assertEquals("Black dragons", tracker.summary().getMonster());
		assertEquals(12, tracker.summary().getInitialAmount());
	}

	@Test
	public void clearCurrentTaskBlocksResumeUntilObserved()
	{
		demons(50);
		tracker.play(ACCOUNT);
		tracker.autoPause();
		tracker.clearCurrentTask();
		assertFalse(tracker.play(ACCOUNT));
		demons(50);
		assertTrue(tracker.play(ACCOUNT));
	}

	@Test
	public void sessionAccountIsCapturedAtomically()
	{
		demons(50);
		tracker.play(ACCOUNT);
		assertEquals(ACCOUNT, tracker.getSessionAccountHash());
		tracker.pause();
		// resuming with another account is refused
		assertFalse(tracker.canPlay(OTHER_ACCOUNT));
		assertFalse(tracker.play(OTHER_ACCOUNT));
		assertNull(tracker.stopAndResetIfOtherAccount(ACCOUNT));
		assertEquals(State.PAUSED, tracker.getState());

		SessionTracker.Summary summary = tracker.stopAndResetIfOtherAccount(OTHER_ACCOUNT);
		assertNotNull(summary);
		assertEquals(ACCOUNT, summary.getAccountHash());
		assertEquals(State.IDLE, tracker.getState());
		assertEquals(SessionTracker.NO_ACCOUNT, tracker.getSessionAccountHash());
		assertNull(tracker.stopAndResetIfOtherAccount(OTHER_ACCOUNT));
	}

	@Test
	public void sameTaskReturningAfterTransientZeroResumes()
	{
		demons(10);
		tracker.play(ACCOUNT);
		demons(8);
		assertEquals(ObserveResult.TASK_CLEARED, noTask());
		// the same task comes back: not a change, and the drop is not counted
		assertEquals(ObserveResult.NONE, demons(8));
		assertEquals(2, tracker.getKills());
		demons(7);
		assertEquals(3, tracker.getKills());
		// a completion signal while the task is still active is not a completion
		assertEquals(ObserveResult.NONE, tracker.observe("Abyssal demons", DEMON_ID, 7, true));
		assertFalse(tracker.isCompleted());
		assertEquals(ObserveResult.COMPLETED, tracker.observe(null, SessionTracker.NO_CREATURE, 0, true));
		assertEquals(10, tracker.getKills());
	}

	@Test
	public void lateCounterSyncWhileClearedIsNotCompletionAfterTaskReturns()
	{
		demons(10);
		tracker.play(ACCOUNT);
		noTask();
		demons(10);
		// even if a signal arrives now, the task is active again so it is not a completion
		assertEquals(ObserveResult.NONE, tracker.observe("Abyssal demons", DEMON_ID, 10, true));
		assertFalse(tracker.isCompleted());
	}

	@Test
	public void originalAmountIsTracked()
	{
		tracker.observe("Abyssal demons", DEMON_ID, 120, 150, false);
		tracker.play(ACCOUNT);
		assertEquals(150, tracker.summary().getOriginalAmount());
		// extension changes the assigned size
		tracker.observe("Abyssal demons", DEMON_ID, 119, 200, false);
		SessionTracker.Summary summary = tracker.stopAndReset();
		assertEquals(200, summary.getOriginalAmount());
		assertEquals(Integer.valueOf(200), LedgerRecord.fromSummary(summary, false).getOriginalAmount());
	}

	@Test
	public void unknownOriginalAmountIsNullOnRecord()
	{
		demons(20);
		tracker.play(ACCOUNT);
		SessionTracker.Summary summary = tracker.stopAndReset();
		assertEquals(SessionTracker.UNKNOWN_AMOUNT, summary.getOriginalAmount());
		assertNull(LedgerRecord.fromSummary(summary, true).getOriginalAmount());
	}

	@Test
	public void unknownNameDoesNotLookLikeTaskChange()
	{
		tracker.observe(null, DEMON_ID, 30, false);
		tracker.play(ACCOUNT);
		// lookup succeeds later: same creature id, so same task, and the name is filled in
		assertEquals(ObserveResult.NONE, demons(29));
		assertEquals(1, tracker.getKills());
		assertEquals("Abyssal demons", tracker.summary().getMonster());
		assertEquals(ObserveResult.TASK_CHANGED, tracker.observe(null, DRAGON_ID, 30, false));
	}

	@Test
	public void unknownNameFallbackOnRecord()
	{
		tracker.observe(null, DEMON_ID, 30, false);
		tracker.play(ACCOUNT);
		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), true);
		assertEquals("Unknown task (" + DEMON_ID + ")", record.getMonster());
	}

	@Test
	public void shortZeroGapResumesSameTask()
	{
		demons(10);
		tracker.play(ACCOUNT);
		demons(8);
		for (int i = 0; i < SessionTracker.MAX_TRANSIENT_ZERO_OBSERVATIONS; i++)
		{
			noTask();
		}
		// even with a higher count, a short gap is a glitch rather than a reassignment
		assertEquals(ObserveResult.NONE, demons(9));
		assertEquals(2, tracker.getKills());
	}

	@Test
	public void longZeroGapWithSameCreatureIsTaskChange()
	{
		tracker.observe("Abyssal demons", DEMON_ID, 10, 150, false);
		tracker.play(ACCOUNT);
		tracker.observe("Abyssal demons", DEMON_ID, 8, 150, false);
		for (int i = 0; i <= SessionTracker.MAX_TRANSIENT_ZERO_OBSERVATIONS; i++)
		{
			noTask();
		}
		// cancelled then reassigned to the same creature with a new size
		assertEquals(ObserveResult.TASK_CHANGED, tracker.observe("Abyssal demons", DEMON_ID, 120, 120, false));
		SessionTracker.Summary summary = tracker.stopAndReset();
		assertEquals(2, summary.getKills());
		assertFalse(summary.isCompleted());
	}

	@Test
	public void longZeroGapWithHigherRemainingIsTaskChangeEvenWithSameSize()
	{
		tracker.observe("Abyssal demons", DEMON_ID, 10, 150, false);
		tracker.play(ACCOUNT);
		for (int i = 0; i <= SessionTracker.MAX_TRANSIENT_ZERO_OBSERVATIONS; i++)
		{
			noTask();
		}
		assertEquals(ObserveResult.TASK_CHANGED, tracker.observe("Abyssal demons", DEMON_ID, 150, 150, false));
	}

	@Test
	public void longZeroGapWithUnchangedSizeAndNoIncreaseResumes()
	{
		tracker.observe("Abyssal demons", DEMON_ID, 10, 150, false);
		tracker.play(ACCOUNT);
		tracker.observe("Abyssal demons", DEMON_ID, 8, 150, false);
		for (int i = 0; i < 10; i++)
		{
			noTask();
		}
		assertEquals(ObserveResult.NONE, tracker.observe("Abyssal demons", DEMON_ID, 8, 150, false));
		tracker.observe("Abyssal demons", DEMON_ID, 6, 150, false);
		assertEquals(4, tracker.getKills());
	}

	@Test
	public void longZeroGapWithUnknownSizeIsTaskChange()
	{
		demons(10);
		tracker.play(ACCOUNT);
		for (int i = 0; i <= SessionTracker.MAX_TRANSIENT_ZERO_OBSERVATIONS; i++)
		{
			noTask();
		}
		assertEquals(ObserveResult.TASK_CHANGED, demons(5));
	}

	@Test
	public void bossTasksUseDistinctCreatureKeys()
	{
		int zulrah = SessionTracker.creatureKey(SessionTracker.BOSS_TASK_TARGET, 21);
		int vorkath = SessionTracker.creatureKey(SessionTracker.BOSS_TASK_TARGET, 22);
		assertEquals(DEMON_ID, SessionTracker.creatureKey(DEMON_ID, 0));
		assertTrue(zulrah != vorkath);
		assertTrue(zulrah < SessionTracker.NO_CREATURE);
		assertTrue(SessionTracker.creatureKey(SessionTracker.BOSS_TASK_TARGET, 0) != SessionTracker.NO_CREATURE);

		// boss-to-boss change with failed name lookups is still a task change
		tracker.observe(null, zulrah, 5, false);
		tracker.play(ACCOUNT);
		assertEquals(ObserveResult.NONE, tracker.observe(null, zulrah, 4, false));
		assertEquals(ObserveResult.TASK_CHANGED, tracker.observe(null, vorkath, 5, false));

		SessionTracker.Summary summary = tracker.stopAndReset();
		LedgerRecord record = LedgerRecord.fromSummary(summary, true);
		assertEquals(Integer.valueOf(zulrah), record.getCreatureId());
		assertEquals("Unknown boss task (21)", record.getMonster());
	}

	@Test
	public void liveTaskKnowsTheKillsLeftOnlyWhileTheSessionsTaskIsObserved()
	{
		assertNull("idle", tracker.liveTask());
		assertTrue(tracker.play(ACCOUNT));
		assertNull("a session without a task", tracker.liveTask());

		demons(20);
		now += 60_000;
		demons(17);
		SessionTracker.LiveTask live = tracker.liveTask();
		assertEquals(State.RUNNING, live.getState());
		assertEquals(tracker.getSessionId(), live.getSessionId());
		assertEquals("Abyssal demons", live.getMonster());
		assertEquals(DEMON_ID, live.getCreatureId());
		assertEquals(3, live.getKills());
		assertEquals(60_000, live.getActiveMillis());
		assertEquals(17, live.getRemaining());

		// Logout: paused, and the task is not read again until the next observation.
		assertTrue(tracker.autoPause());
		tracker.clearCurrentTask();
		assertEquals(State.PAUSED, tracker.liveTask().getState());
		assertEquals(SessionTracker.LiveTask.UNKNOWN_REMAINING, tracker.liveTask().getRemaining());
		demons(17);
		assertEquals(17, tracker.liveTask().getRemaining());

		// Another task observed while paused is not the session's.
		tracker.observe("Black dragons", DRAGON_ID, 30, false);
		assertEquals(SessionTracker.LiveTask.UNKNOWN_REMAINING, tracker.liveTask().getRemaining());
		demons(17);

		// A cleared count waiting for its completion message.
		assertTrue(tracker.play(ACCOUNT));
		assertEquals(ObserveResult.TASK_CLEARED, demons(0));
		assertEquals(SessionTracker.LiveTask.UNKNOWN_REMAINING, tracker.liveTask().getRemaining());

		assertTrue(tracker.stop());
		assertNull("stopped", tracker.liveTask());
	}
}
