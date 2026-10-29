package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

/**
 * The late-loot grace window for a running session whose task completes ({@link SessionTracker#holdCompletion()}).
 * The plugin calls {@code tickCompletion()} once per game tick before observing, holds on a running completion, and
 * releases any held session with {@code takePendingCompletion()} on every other end path.
 */
public class CompletionGraceTest
{
	private static final int DEMON_ID = 42;
	private static final int DRAGON_ID = 43;
	private static final long ACCOUNT = 555L;
	private static final int BONES = 526;
	private static final int ASHES = 592;

	private long now;
	private SessionTracker tracker;

	@Before
	public void setUp()
	{
		now = 1_000_000L;
		tracker = new SessionTracker(() -> now);
	}

	private static ItemTally tally(int id, int qty)
	{
		ItemTally t = new ItemTally();
		t.add(id, qty);
		return t;
	}

	/** Starts a 3-kill demon task, kills all three over 60 seconds and completes it on the last observation. */
	private void runAndCompleteTask()
	{
		tracker.observe("Abyssal demons", DEMON_ID, 3, 3, false);
		assertTrue(tracker.play(ACCOUNT));
		tracker.addLoot(tally(BONES, 1));
		now += 30_000;
		tracker.observe("Abyssal demons", DEMON_ID, 1, 3, false);
		now += 30_000;
		assertEquals(SessionTracker.ObserveResult.COMPLETED, tracker.observe(null, SessionTracker.NO_CREATURE, 0, true));
	}

	/** Mirrors the plugin's endSession: a held completion is released first, then the active session is stopped. */
	private SessionTracker.Summary[] endSession()
	{
		return new SessionTracker.Summary[]{tracker.takePendingCompletion(), tracker.stopAndReset()};
	}

	@Test
	public void lootDuringGraceWindowIsIncluded()
	{
		runAndCompleteTask();
		assertTrue(tracker.holdCompletion());
		assertTrue(tracker.hasPendingCompletion());
		// Same tick as the completion, after the task count update.
		assertTrue(tracker.addLoot(tally(BONES, 1)));

		assertNull("tick 1 of 2", tracker.tickCompletion());
		assertTrue(tracker.addLoot(tally(ASHES, 2)));

		SessionTracker.Summary summary = tracker.tickCompletion();
		assertNotNull("released after " + SessionTracker.COMPLETION_LOOT_GRACE_TICKS + " ticks", summary);
		assertFalse(tracker.hasPendingCompletion());
		assertTrue(summary.isCompleted());
		assertEquals(2, summary.getLoot().get(BONES));
		assertEquals(2, summary.getLoot().get(ASHES));
		assertEquals(SaveDecision.Action.SAVE_COMPLETED,
			SaveDecision.decide(summary, SaveDecision.EndReason.SYSTEM, true));
		assertNull("released once", tracker.tickCompletion());
		assertNull(tracker.takePendingCompletion());
	}

	@Test
	public void lootAfterGraceWindowIsNotIncluded()
	{
		runAndCompleteTask();
		assertTrue(tracker.holdCompletion());
		tracker.tickCompletion();
		SessionTracker.Summary summary = tracker.tickCompletion();
		assertNotNull(summary);
		assertFalse("idle, no session", tracker.addLoot(tally(ASHES, 1)));
		assertEquals(1, summary.getLoot().get(BONES));
		assertEquals(0, summary.getLoot().get(ASHES));
	}

	@Test
	public void durationAndKillsAreFrozenAtCompletion()
	{
		runAndCompleteTask();
		final long completedAt = now;
		assertTrue(tracker.holdCompletion());
		assertEquals("the panel shows the session as ended", SessionTracker.State.IDLE, tracker.getState());
		assertNull(tracker.activeSummary());

		now += 600;
		tracker.tickCompletion();
		// A stray count change during the window does not touch the held session.
		tracker.observe("Abyssal demons", DEMON_ID, 5, 5, false);
		now += 600;
		SessionTracker.Summary summary = tracker.tickCompletion();
		assertNotNull(summary);
		assertEquals(3, summary.getKills());
		assertEquals(60_000, summary.getActiveMillis());
		assertEquals(completedAt, summary.getEndedAt());
	}

	@Test
	public void holdIsRefusedUnlessRunningAndCompleted()
	{
		assertFalse("idle", tracker.holdCompletion());
		tracker.observe("Abyssal demons", DEMON_ID, 3, 3, false);
		assertTrue(tracker.play(ACCOUNT));
		assertFalse("running, not completed", tracker.holdCompletion());
		assertEquals(SessionTracker.State.RUNNING, tracker.getState());
		assertNull(tracker.tickCompletion());
	}

	@Test
	public void pausedCompletionIsNotHeldAndSavesAsBefore()
	{
		tracker.observe("Abyssal demons", DEMON_ID, 3, 3, false);
		assertTrue(tracker.play(ACCOUNT));
		now += 10_000;
		tracker.observe("Abyssal demons", DEMON_ID, 2, 3, false);
		tracker.pause();
		now += 10_000;
		assertEquals(SessionTracker.ObserveResult.COMPLETED, tracker.observe(null, SessionTracker.NO_CREATURE, 0, true));

		assertFalse(tracker.holdCompletion());
		assertFalse(tracker.hasPendingCompletion());
		assertEquals(SessionTracker.State.PAUSED, tracker.getState());
		assertFalse("paused", tracker.addLoot(tally(BONES, 1)));

		SessionTracker.Summary[] ended = endSession();
		assertNull(ended[0]);
		assertNotNull(ended[1]);
		assertTrue(ended[1].isCompleted());
		assertEquals(1, ended[1].getKills());
		assertEquals(10_000, ended[1].getActiveMillis());
		assertEquals(SaveDecision.Action.SAVE_COMPLETED,
			SaveDecision.decide(ended[1], SaveDecision.EndReason.SYSTEM, true));
	}

	@Test
	public void completionAfterAwaitedZeroIsHeldToo()
	{
		tracker.observe("Abyssal demons", DEMON_ID, 2, 2, false);
		assertTrue(tracker.play(ACCOUNT));
		assertEquals(SessionTracker.ObserveResult.TASK_CLEARED, tracker.observe(null, SessionTracker.NO_CREATURE, 0, false));
		assertEquals(SessionTracker.ObserveResult.COMPLETED, tracker.observe(null, SessionTracker.NO_CREATURE, 0, true));
		assertTrue(tracker.holdCompletion());
		tracker.addLoot(tally(BONES, 1));
		tracker.tickCompletion();
		SessionTracker.Summary summary = tracker.tickCompletion();
		assertEquals(2, summary.getKills());
		assertEquals(1, summary.getLoot().get(BONES));
	}

	@Test
	public void endPathsDuringWindowSaveOnceAsCompleted()
	{
		for (SaveDecision.EndReason reason : new SaveDecision.EndReason[]{SaveDecision.EndReason.LOGOUT,
			SaveDecision.EndReason.PLAYER_STOP, SaveDecision.EndReason.SHUTDOWN, SaveDecision.EndReason.ACCOUNT_SWITCH,
			SaveDecision.EndReason.SYSTEM})
		{
			setUp();
			runAndCompleteTask();
			assertTrue(tracker.holdCompletion());
			tracker.addLoot(tally(ASHES, 1));
			tracker.autoPause();
			// Logout persists only an active session; a held completion is never one.
			assertNull(reason.name(), tracker.activeSummary());

			SessionTracker.Summary[] ended = endSession();
			assertNotNull(reason.name(), ended[0]);
			assertNull(reason.name() + ": no active session", ended[1]);
			assertTrue(ended[0].isCompleted());
			assertEquals(1, ended[0].getLoot().get(ASHES));
			// The plugin saves a released completion with SYSTEM, but any reason saves a completed summary.
			assertEquals(reason.name(), SaveDecision.Action.SAVE_COMPLETED,
				SaveDecision.decide(ended[0], reason, true));

			// Nothing left to save twice: not by a later tick, end path or shutdown.
			assertNull(tracker.tickCompletion());
			assertNull(tracker.tickCompletion());
			SessionTracker.Summary[] again = endSession();
			assertNull(again[0]);
			assertNull(again[1]);
		}
	}

	@Test
	public void newSessionDuringWindowDoesNotStealLoot()
	{
		runAndCompleteTask();
		assertTrue(tracker.holdCompletion());
		tracker.observe("Black dragons", DRAGON_ID, 10, 10, false);
		assertTrue("Play is allowed during the window", tracker.play(ACCOUNT));
		assertTrue(tracker.addLoot(tally(ASHES, 1)));
		assertTrue("the new session gets none of it", tracker.getLoot().isEmpty());

		SessionTracker.Summary[] ended = endSession();
		assertEquals(1, ended[0].getLoot().get(ASHES));
		assertEquals(DEMON_ID, ended[0].getCreatureId());
		assertEquals(DRAGON_ID, ended[1].getCreatureId());
		assertTrue(ended[1].getLoot().isEmpty());
	}

	@Test
	public void newSessionGetsLootAfterWindow()
	{
		runAndCompleteTask();
		assertTrue(tracker.holdCompletion());
		tracker.observe("Black dragons", DRAGON_ID, 10, 10, false);
		assertTrue(tracker.play(ACCOUNT));
		tracker.tickCompletion();
		assertNotNull(tracker.tickCompletion());
		assertTrue(tracker.addLoot(tally(ASHES, 1)));
		assertEquals(1, tracker.getLoot().get(ASHES));
	}

	@Test
	public void secondCompletionIsNotHeldWhileOneIsPending()
	{
		runAndCompleteTask();
		assertTrue(tracker.holdCompletion());
		tracker.observe("Black dragons", DRAGON_ID, 1, 1, false);
		assertTrue(tracker.play(ACCOUNT));
		assertEquals(SessionTracker.ObserveResult.COMPLETED, tracker.observe(null, SessionTracker.NO_CREATURE, 0, true));
		assertFalse(tracker.holdCompletion());
		// The plugin then ends the session normally, which releases the held one first.
		SessionTracker.Summary[] ended = endSession();
		assertEquals(DEMON_ID, ended[0].getCreatureId());
		assertEquals(DRAGON_ID, ended[1].getCreatureId());
		assertTrue(ended[0].isCompleted());
		assertTrue(ended[1].isCompleted());
	}
}
