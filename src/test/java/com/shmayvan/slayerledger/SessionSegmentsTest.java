package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.SessionTracker.ObserveResult;
import static com.shmayvan.slayerledger.SessionTracker.State;
import static com.shmayvan.slayerledger.SessionTracker.Status;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;

/**
 * The automatic session lifecycle (V2 plan 4.1) on the tracker alone: a session's time is the sum of its
 * segments; a task kill (re)starts it at its resume point, clamped to the assignment, the previous session's end and the
 * close of the previous segment; the stops; Away; End task and Track again. {@code PluginHarness} tests cover the same
 * rules through the plugin's events ({@code HarnessAutomaticTrackingTest}).
 */
public class SessionSegmentsTest
{
	private static final int DEMON_ID = 42;
	private static final int DRAGON_ID = 43;
	private static final long ACCOUNT = 555L;
	private static final long MINUTE = 60_000L;

	private long now;
	private SessionTracker tracker;

	@Before
	public void setUp()
	{
		now = 100 * MINUTE;
		tracker = new SessionTracker(() -> now);
		tracker.setAccount(ACCOUNT);
	}

	private ObserveResult demons(int remaining)
	{
		return demons(remaining, KillPairing.NONE);
	}

	private ObserveResult demons(int remaining, KillPairing pairing)
	{
		return tracker.observe("Abyssal demons", DEMON_ID, remaining, 50, TaskContext.NONE, LocationTally.NO_REGION,
			false, pairing);
	}

	private ObserveResult complete(KillPairing pairing)
	{
		return tracker.observe(null, SessionTracker.NO_CREATURE, 0, SessionTracker.UNKNOWN_AMOUNT, TaskContext.NONE,
			LocationTally.NO_REGION, true, pairing);
	}

	/** NPCs whose earliest engagement started at {@code start}, named Abyssal demon. */
	private static KillPairing fightFrom(long start)
	{
		return KillPairing.of(start, Collections.singletonList("Abyssal demon"));
	}

	// ---- Starts ----

	@Test
	public void theFirstKillStartsTheSessionBackdatedToItsFight()
	{
		demons(50);
		assertEquals(Status.WAITING_FOR_FIRST_KILL, tracker.getStatus());
		assertNull(tracker.liveTask());

		now += 10 * MINUTE;
		demons(49, fightFrom(now - 40_000));
		assertEquals(State.RUNNING, tracker.getState());
		assertEquals(1, tracker.getKills());
		assertEquals("the first fight counts", 40_000, tracker.getActiveMillis());
		assertEquals(now - 40_000, tracker.summary().getStartedAt());
		assertEquals(50, tracker.summary().getInitialAmount());
		assertEquals(ACCOUNT, tracker.getSessionAccountHash());
		assertEquals(Collections.singletonList("abyssal demon"), tracker.getTaskNpcNames());
	}

	@Test
	public void aKillWithNothingPairedStartsAtTheKill()
	{
		demons(50);
		now += MINUTE;
		// A cannon-only kill: no NPC the player hit died.
		demons(48);
		assertEquals(State.RUNNING, tracker.getState());
		assertEquals(2, tracker.getKills());
		assertEquals(0, tracker.getActiveMillis());
		now += 5_000;
		assertEquals(5_000, tracker.getActiveMillis());
	}

	@Test
	public void theResumePointIsNeverBeforeTheAssignment()
	{
		demons(50);
		final long assigned = now;
		now += MINUTE;
		// Hits from before the assignment (a previous task's fight) never count.
		demons(49, fightFrom(assigned - 5 * MINUTE));
		assertEquals(MINUTE, tracker.getActiveMillis());
		assertEquals(assigned, tracker.summary().getStartedAt());
	}

	@Test
	public void noSessionStartsWithoutAnAccountOrWhileAStartIsBlockedOrOnTheFirstReading()
	{
		SessionTracker noAccount = new SessionTracker(() -> now);
		noAccount.observe("Abyssal demons", DEMON_ID, 50, false);
		noAccount.observe("Abyssal demons", DEMON_ID, 49, false);
		assertEquals(State.IDLE, noAccount.getState());

		demons(50);
		demons(49, KillPairing.NONE.withStartBlocked());
		assertEquals("a restore check is pending", State.IDLE, tracker.getState());

		tracker.clearCurrentTask();
		demons(45);
		assertEquals("the first reading after a login is never a kill", State.IDLE, tracker.getState());
		demons(44);
		assertEquals(State.RUNNING, tracker.getState());
		assertEquals(1, tracker.getKills());
	}

	@Test
	public void aNewAssignmentIsNotAKill()
	{
		demons(3);
		tracker.observe("Black dragons", DRAGON_ID, 2, 40, false);
		assertEquals(State.IDLE, tracker.getState());
		assertEquals(Status.WAITING_FOR_FIRST_KILL, tracker.getStatus());
	}

	@Test
	public void aFirstKillThatCompletesTheTaskStartsAndCompletesTheSession()
	{
		demons(1);
		now += MINUTE;
		assertEquals(ObserveResult.COMPLETED, complete(fightFrom(now - 20_000)));
		assertEquals(State.RUNNING, tracker.getState());
		assertEquals(1, tracker.getKills());
		assertEquals(20_000, tracker.getActiveMillis());
		assertTrue(tracker.holdCompletion());
		assertTrue(tracker.takePendingCompletion().isCompleted());
	}

	@Test
	public void aFirstKillToZeroWaitsForItsCompletion()
	{
		demons(1);
		now += MINUTE;
		// The count reads 0 a tick before the completion: a cancelled task does too, so nothing starts yet.
		assertEquals(ObserveResult.NONE, tracker.observe(null, SessionTracker.NO_CREATURE, 0, false));
		assertEquals(State.IDLE, tracker.getState());
		now += 600;
		assertEquals(ObserveResult.COMPLETED, complete(KillPairing.NONE));
		assertEquals(1, tracker.getKills());
		assertEquals("started at the kill, a tick before", 600, tracker.getActiveMillis());

		// A cancelled task (no completion) starts nothing.
		SessionTracker other = new SessionTracker(() -> now);
		other.setAccount(ACCOUNT);
		other.observe("Abyssal demons", DEMON_ID, 30, false);
		other.observe(null, SessionTracker.NO_CREATURE, 0, false);
		other.observe("Black dragons", DRAGON_ID, 20, false);
		assertEquals(State.IDLE, other.getState());
	}

	// ---- The next tick's deaths ----

	@Test
	public void deathsOnTheTickAfterTheKillStillBackdateItsRestart()
	{
		demons(50);
		now += MINUTE;
		demons(49);
		assertEquals(0, tracker.getActiveMillis());
		final long killAt = tracker.getLastKillAt();

		now += 600;
		tracker.pairKill(killAt, fightFrom(killAt - 30_000));
		assertEquals(30_600, tracker.getActiveMillis());
		assertEquals(killAt - 30_000, tracker.summary().getStartedAt());
		assertEquals(Collections.singletonList("abyssal demon"), tracker.getTaskNpcNames());

		// Still clamped: never before the assignment.
		tracker.pairKill(killAt, fightFrom(0));
		assertEquals(MINUTE + 600, tracker.getActiveMillis());
		// Another kill's pairing never moves it.
		tracker.pairKill(killAt - 1, fightFrom(0));
		assertEquals(MINUTE + 600, tracker.getActiveMillis());
	}

	@Test
	public void deathsOnTheTickAfterTheFinalKillBackdateTheHeldCompletion()
	{
		demons(2);
		assertTrue(tracker.startNow(ACCOUNT));
		now += MINUTE;
		tracker.loggedOut();
		now += 10 * MINUTE;
		assertEquals(ObserveResult.COMPLETED, complete(KillPairing.NONE));
		final long killAt = tracker.getLastKillAt();
		assertTrue(tracker.holdCompletion());

		tracker.pairKill(killAt, fightFrom(killAt - 45_000));
		SessionTracker.Summary held = tracker.takePendingCompletion();
		assertEquals(MINUTE + 45_000, held.getActiveMillis());
	}

	// ---- Stops and restarts ----

	@Test
	public void pauseThenResumeOpensASegmentAtOnce()
	{
		demons(50);
		demons(49);
		now += MINUTE;
		assertTrue(tracker.pause());
		assertEquals(Status.PAUSED, tracker.getStatus());
		now += 30 * MINUTE;
		assertEquals(MINUTE, tracker.getActiveMillis());
		assertTrue(tracker.resume(ACCOUNT));
		now += MINUTE;
		assertEquals(2 * MINUTE, tracker.getActiveMillis());
	}

	@Test
	public void aKillRestartAfterAPauseIsClampedToThePause()
	{
		demons(50);
		demons(49);
		now += MINUTE;
		tracker.pause();
		final long paused = now;
		now += 5 * MINUTE;
		// The fight began before the pause: only the part after it counts.
		demons(48, fightFrom(paused - 30_000));
		assertEquals(State.RUNNING, tracker.getState());
		assertEquals(2, tracker.getKills());
		assertEquals(MINUTE + 5 * MINUTE, tracker.getActiveMillis());

		tracker.pause();
		now += MINUTE;
		demons(47, fightFrom(now - 20_000));
		assertEquals("after the pause, the fight counts in full", 6 * MINUTE + 20_000, tracker.getActiveMillis());
	}

	@Test
	public void aLoginIsNotARestartAndTheNextKillIsClampedToTheLogout()
	{
		demons(50);
		demons(49);
		now += MINUTE;
		assertTrue(tracker.loggedOut());
		assertEquals(Status.WAITING_FOR_NEXT_KILL, tracker.getStatus());
		tracker.clearCurrentTask();
		assertEquals(Status.LOGGED_OUT, tracker.getStatus());
		now += 60 * MINUTE;

		demons(49);
		assertEquals("a login is not a restart", State.LOGGED_OUT, tracker.getState());
		assertEquals(Status.WAITING_FOR_NEXT_KILL, tracker.getStatus());
		now += MINUTE;
		demons(48, fightFrom(now - 30_000));
		assertEquals(State.RUNNING, tracker.getState());
		assertEquals("the fight after the login counts, the logged-out hour doesn't", MINUTE + 30_000,
			tracker.getActiveMillis());

		// The clamp: a resume point before the logout is held to it. (The plugin forgets every hit at a login, so
		// there it can't happen; after a pause or Away it can.)
		SessionTracker other = new SessionTracker(() -> now);
		other.setAccount(ACCOUNT);
		other.observe("Abyssal demons", DEMON_ID, 10, false);
		other.observe("Abyssal demons", DEMON_ID, 9, false);
		other.loggedOut();
		final long otherLogout = now;
		now += MINUTE;
		other.observe("Abyssal demons", DEMON_ID, 8, 50, TaskContext.NONE, LocationTally.NO_REGION, false,
			fightFrom(otherLogout - 5 * MINUTE));
		assertEquals("clamped to the logout", now - otherLogout, other.getActiveMillis());
	}

	@Test
	public void theFinalKillAfterALogoutRestartsBeforeItCompletes()
	{
		demons(2);
		demons(1);
		now += MINUTE;
		tracker.loggedOut();
		tracker.clearCurrentTask();
		now += 60 * MINUTE;
		demons(1);
		now += 2 * MINUTE;
		assertEquals(ObserveResult.COMPLETED, complete(fightFrom(now - 90_000)));
		assertEquals(2, tracker.getKills());
		assertEquals("the backdated last fight counts", MINUTE + 90_000, tracker.getActiveMillis());
		assertTrue("restarted, so held for its loot", tracker.holdCompletion());
	}

	@Test
	public void aPauseBetweenADropToZeroAndItsCompletionCountsNoTimeTwice()
	{
		demons(2);
		demons(1);
		final long started = now;
		now += MINUTE;
		// The final kill: the count reads 0 before the completion confirms it.
		assertEquals(ObserveResult.TASK_CLEARED, tracker.observe(null, SessionTracker.NO_CREATURE, 0, false));
		now += 300;
		assertTrue(tracker.pause());
		now += 300;
		// The restart for the final kill opens at the pause, not at the kill before it.
		assertEquals(ObserveResult.COMPLETED, complete(KillPairing.NONE));
		assertTrue(tracker.holdCompletion());
		SessionTracker.Summary held = tracker.takePendingCompletion();
		assertEquals(2, held.getKills());
		assertEquals("no more than the wall clock", now - started, held.getActiveMillis());
	}

	@Test
	public void aBraceletOfSlaughterKillRestartsWithoutCountingAKill()
	{
		demons(50);
		demons(49);
		now += MINUTE;
		tracker.pause();
		now += 10 * MINUTE;
		assertTrue(tracker.braceletKill(fightFrom(now - 15_000)));
		assertEquals(State.RUNNING, tracker.getState());
		assertEquals("the kills follow the task count", 1, tracker.getKills());
		assertEquals(MINUTE + 15_000, tracker.getActiveMillis());

		// While running it is activity: it resets the Away clock.
		now += 9 * MINUTE;
		assertTrue(tracker.braceletKill(KillPairing.NONE));
		now += 9 * MINUTE;
		assertFalse(tracker.checkAway());

		// On an assignment waiting for its first kill it starts the session.
		SessionTracker waiting = new SessionTracker(() -> now);
		waiting.setAccount(ACCOUNT);
		waiting.observe("Abyssal demons", DEMON_ID, 20, false);
		assertTrue(waiting.braceletKill(KillPairing.NONE));
		assertEquals(State.RUNNING, waiting.getState());
		assertEquals(0, waiting.getKills());
		assertEquals(20, waiting.summary().getInitialAmount());
	}

	// ---- Away ----

	@Test
	public void aBreakOverTheLimitGoesAwayAndTheClockStepsBackOnce()
	{
		demons(50);
		demons(49);
		now += 2 * MINUTE;
		demons(48);
		final long lastKill = now;
		now += SessionTracker.DEFAULT_AWAY_MINUTES * MINUTE - 1;
		assertFalse(tracker.checkAway());
		assertEquals(State.RUNNING, tracker.getState());
		assertEquals(12 * MINUTE - 1, tracker.getActiveMillis());

		now += 1;
		assertTrue(tracker.checkAway());
		assertEquals(Status.AWAY, tracker.getStatus());
		assertEquals("the segment closes at the last kill", 2 * MINUTE, tracker.getActiveMillis());
		now += 60 * MINUTE;
		assertFalse("once", tracker.checkAway());
		assertEquals(2 * MINUTE, tracker.getActiveMillis());

		// The next kill restarts it at its fight, held to no earlier than the Away close.
		demons(47, fightFrom(lastKill - MINUTE));
		assertEquals(State.RUNNING, tracker.getState());
		assertEquals("clamped to the Away close", 2 * MINUTE + (now - lastKill), tracker.getActiveMillis());
	}

	@Test
	public void aBankTripUnderTheLimitIsCounted()
	{
		demons(50);
		demons(49);
		now += 9 * MINUTE;
		assertFalse(tracker.checkAway());
		demons(48);
		assertEquals(9 * MINUTE, tracker.getActiveMillis());
	}

	@Test
	public void hitsOnAKnownTaskNpcKeepALongKillFromGoingAway()
	{
		demons(50);
		demons(49, fightFrom(now));
		for (int i = 0; i < 15; i++)
		{
			now += MINUTE;
			assertTrue(tracker.taskNpcHit("Abyssal demon"));
			assertFalse(tracker.checkAway());
		}
		assertFalse("not a known task NPC", tracker.taskNpcHit("Chicken"));
		assertEquals(15 * MINUTE, tracker.getActiveMillis());
		now += 10 * MINUTE;
		assertTrue(tracker.checkAway());
		assertEquals("closed at the last hit", 15 * MINUTE, tracker.getActiveMillis());
	}

	@Test
	public void theAwayLimitIsASetting()
	{
		tracker.setAwayMinutes(2);
		demons(50);
		demons(49);
		now += 2 * MINUTE;
		assertTrue(tracker.checkAway());
		assertEquals(0, tracker.getActiveMillis());
	}

	// ---- End task and Track again ----

	@Test
	public void endTaskStopsTrackingTheAssignmentUntilTrackAgain()
	{
		demons(50);
		now += MINUTE;
		demons(49, fightFrom(now - 10_000));
		now += MINUTE;
		SessionTracker.Summary ended = tracker.endTask();
		assertNotNull(ended);
		assertEquals(1, ended.getKills());
		assertEquals(State.IDLE, tracker.getState());
		assertEquals(Status.ENDED, tracker.getStatus());
		final long endedAt = now;

		now += MINUTE;
		demons(48);
		demons(47);
		assertEquals("no kill restarts an Ended assignment", State.IDLE, tracker.getState());
		assertFalse(tracker.startNow(ACCOUNT));
		assertTrue(tracker.isEnded());

		assertTrue(tracker.trackAgain());
		assertEquals(Status.WAITING_FOR_FIRST_KILL, tracker.getStatus());
		now += MINUTE;
		demons(46, fightFrom(endedAt - MINUTE));
		assertEquals(State.RUNNING, tracker.getState());
		assertEquals("never before the ended session's end", now - endedAt, tracker.getActiveMillis());
		assertEquals(1, tracker.getKills());
	}

	@Test
	public void endTaskWithoutASessionEndsTheWaitingAssignment()
	{
		demons(50);
		assertTrue(tracker.canEndTask());
		assertNull(tracker.endTask());
		assertEquals(Status.ENDED, tracker.getStatus());
		assertFalse(tracker.canEndTask());
		demons(49);
		assertEquals(State.IDLE, tracker.getState());
	}

	@Test
	public void aNewAssignmentAfterEndTaskIsTrackedNormally()
	{
		demons(3);
		demons(2);
		tracker.endTask();
		tracker.observe(null, SessionTracker.NO_CREATURE, 0, false);
		tracker.observe("Black dragons", DRAGON_ID, 20, 20, false);
		assertFalse(tracker.isEnded());
		assertEquals(Status.WAITING_FOR_FIRST_KILL, tracker.getStatus());
		tracker.observe("Black dragons", DRAGON_ID, 19, 20, false);
		assertEquals(State.RUNNING, tracker.getState());
		assertEquals("Black dragons", tracker.getSessionMonster());

		// The same creature assigned again (a higher count) is a new assignment too.
		tracker.endTask();
		tracker.observe("Black dragons", DRAGON_ID, 25, 25, false);
		assertFalse(tracker.isEnded());
	}

	@Test
	public void theEndedFlagSurvivesAClientRestartThroughItsMarker()
	{
		demons(50);
		final long assigned = now;
		demons(49);
		now += MINUTE;
		tracker.endTask();
		final int[] counters = {3, 0, 0};
		SessionSnapshot marker = tracker.endedMarker(counters, now);
		assertNotNull(marker);
		assertTrue(marker.isEndedMarker());
		assertTrue(marker.getSessionId().startsWith(SessionSnapshot.ENDED_ID_PREFIX));
		assertEquals(Long.valueOf(assigned), marker.getAssignedAt());
		assertEquals(Long.valueOf(now), marker.getPreviousSessionEnd());
		assertEquals(49, marker.getRemaining());

		SessionTracker restarted = new SessionTracker(() -> now);
		restarted.setAccount(ACCOUNT);
		restarted.observe("Abyssal demons", DEMON_ID, 49, 50, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, restarted.restoreEnded(marker, counters));
		assertEquals(Status.ENDED, restarted.getStatus());
		restarted.observe("Abyssal demons", DEMON_ID, 48, 50, false);
		assertEquals(State.IDLE, restarted.getState());

		// Another task by then: nothing to restore.
		SessionTracker newTask = new SessionTracker(() -> now);
		newTask.observe("Black dragons", DRAGON_ID, 20, false);
		assertEquals(SessionTracker.RestoreResult.MISMATCH, newTask.restoreEnded(marker, counters));
		assertFalse(newTask.isEnded());
		assertNull("nothing Ended, no marker", newTask.endedMarker(counters, now));
	}

	@Test
	public void aSnapshotCarriesTheClampsAndTheStateAndRestoresAsLoggedOut()
	{
		demons(50);
		final long assigned = now;
		now += MINUTE;
		demons(49, fightFrom(now - 10_000));
		now += MINUTE;
		tracker.pause();
		final long paused = now;
		now += MINUTE;
		tracker.loggedOut();
		SessionSnapshot snapshot = SessionSnapshot.fromSummary(tracker.activeSummary(), null, now);
		assertEquals("LOGGED_OUT", snapshot.getTrackingState());
		assertEquals(Long.valueOf(paused), snapshot.getSegmentClosedAt());
		assertEquals(Long.valueOf(assigned), snapshot.getAssignedAt());
		assertEquals(Collections.singletonList("abyssal demon"), snapshot.getTaskNpcs());

		SessionTracker restored = new SessionTracker(() -> now);
		restored.observe("Abyssal demons", DEMON_ID, 49, 50, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, restored.restore(snapshot, null));
		assertEquals(State.LOGGED_OUT, restored.getState());
		assertEquals(Collections.singletonList("abyssal demon"), restored.getTaskNpcNames());
		now += MINUTE;
		restored.observe("Abyssal demons", DEMON_ID, 48, 50, TaskContext.NONE, LocationTally.NO_REGION, false,
			fightFrom(paused - MINUTE));
		assertEquals("clamped to the restored segment close", 70_000 + (now - paused), restored.getActiveMillis());
		assertEquals(2, restored.getKills());
	}
}
