package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class TrackingNudgeTest
{
	private static final long ACCOUNT = 1L;
	private static final SessionTracker.State IDLE = SessionTracker.State.IDLE;
	private static final SessionTracker.State RUNNING = SessionTracker.State.RUNNING;
	private static final SessionTracker.State PAUSED = SessionTracker.State.PAUSED;

	private TrackingNudge nudge;

	@Before
	public void setUp()
	{
		nudge = new TrackingNudge();
	}

	private TrackingNudge.Line read(int remaining, SessionTracker.State state)
	{
		return read("Wyrms", 42, remaining, 100, state);
	}

	private TrackingNudge.Line read(String monster, int creatureId, int remaining, int amount,
		SessionTracker.State state)
	{
		return nudge.update(ACCOUNT, monster, creatureId, remaining, amount, state, false);
	}

	@Test
	public void firstReadingIsABaseline()
	{
		assertEquals(TrackingNudge.Line.NONE, read(50, IDLE));
		assertFalse(nudge.isRowShown());
	}

	@Test
	public void killWhileIdleNudgesOncePerAssignment()
	{
		read(50, IDLE);
		assertEquals(TrackingNudge.Line.IDLE, read(49, IDLE));
		assertTrue(nudge.isRowShown());
		assertEquals(TrackingNudge.Line.NONE, read(48, IDLE));
		assertEquals("no kill", TrackingNudge.Line.NONE, read(48, IDLE));
		assertTrue(nudge.isRowShown());
	}

	@Test
	public void killWhileRunningNeverNudges()
	{
		read(50, RUNNING);
		assertEquals(TrackingNudge.Line.NONE, read(49, RUNNING));
		assertFalse(nudge.isRowShown());
		// The assignment wasn't nudged yet, so a later idle kill still does.
		assertEquals(TrackingNudge.Line.IDLE, read(48, IDLE));
	}

	@Test
	public void killWhilePausedPostsThePausedLineButNoRow()
	{
		read(50, PAUSED);
		assertEquals(TrackingNudge.Line.PAUSED, read(49, PAUSED));
		assertFalse(nudge.isRowShown());
		assertEquals(TrackingNudge.Line.NONE, read(48, PAUSED));
	}

	@Test
	public void oneLinePerAssignmentWhateverTheState()
	{
		read(50, IDLE);
		assertEquals(TrackingNudge.Line.IDLE, read(49, IDLE));
		read(45, RUNNING);
		assertFalse("a session clears the row", nudge.isRowShown());
		assertEquals(TrackingNudge.Line.NONE, read(44, PAUSED));
		// After a stop, another untracked kill shows the row again, without a line.
		assertEquals(TrackingNudge.Line.NONE, read(43, IDLE));
		assertTrue(nudge.isRowShown());
	}

	@Test
	public void completingKillNeverNudges()
	{
		read(1, IDLE);
		assertEquals(TrackingNudge.Line.NONE, read(0, IDLE));
		assertFalse(nudge.isRowShown());
	}

	@Test
	public void zeroCountHidesTheRow()
	{
		read(50, IDLE);
		read(49, IDLE);
		assertEquals(TrackingNudge.Line.NONE, read(0, IDLE));
		assertFalse(nudge.isRowShown());
		// The same assignment back (a transient zero) with a kill: the row, no second line.
		assertEquals(TrackingNudge.Line.NONE, read(48, IDLE));
		assertTrue(nudge.isRowShown());
	}

	@Test
	public void newAssignmentOrResetSetsTheBaselineAndCanNudgeAgain()
	{
		read(50, IDLE);
		assertEquals(TrackingNudge.Line.IDLE, read(49, IDLE));
		read(0, IDLE);
		// Another task with fewer kills than the old one had left: a new assignment, not a kill.
		assertEquals(TrackingNudge.Line.NONE, read("Black demons", 43, 30, 30, IDLE));
		assertFalse(nudge.isRowShown());
		assertEquals(TrackingNudge.Line.IDLE, read("Black demons", 43, 29, 30, IDLE));
	}

	@Test
	public void countGoingUpIsANewAssignment()
	{
		read(10, IDLE);
		assertEquals(TrackingNudge.Line.IDLE, read(9, IDLE));
		// Same monster and size reassigned: the count went up.
		assertEquals(TrackingNudge.Line.NONE, read(100, IDLE));
		assertFalse(nudge.isRowShown());
		assertEquals(TrackingNudge.Line.IDLE, read(99, IDLE));
	}

	@Test
	public void otherSizeIsANewAssignment()
	{
		read(50, IDLE);
		assertEquals(TrackingNudge.Line.IDLE, read(49, IDLE));
		assertEquals(TrackingNudge.Line.NONE, read("Wyrms", 42, 40, 120, IDLE));
		assertEquals(TrackingNudge.Line.IDLE, read("Wyrms", 42, 39, 120, IDLE));
	}

	@Test
	public void resetMakesTheNextReadingABaselineButKeepsTheAssignment()
	{
		read(50, IDLE);
		assertEquals(TrackingNudge.Line.IDLE, read(49, IDLE));
		nudge.reset();
		nudge.hideRow();
		assertFalse(nudge.isRowShown());
		assertEquals("baseline", TrackingNudge.Line.NONE, read(47, IDLE));
		assertFalse(nudge.isRowShown());
		assertEquals("same assignment: no second line", TrackingNudge.Line.NONE, read(46, IDLE));
		assertTrue(nudge.isRowShown());
	}

	@Test
	public void anotherAccountIsAnotherAssignment()
	{
		read(50, IDLE);
		assertEquals(TrackingNudge.Line.IDLE, read(49, IDLE));
		nudge.reset();
		assertEquals(TrackingNudge.Line.NONE, nudge.update(2L, "Wyrms", 42, 49, 100, IDLE, false));
		assertEquals(TrackingNudge.Line.IDLE, nudge.update(2L, "Wyrms", 42, 48, 100, IDLE, false));
	}

	@Test
	public void suppressedKillMovesTheBaselineWithoutNudging()
	{
		read(50, IDLE);
		assertEquals(TrackingNudge.Line.NONE, nudge.update(ACCOUNT, "Wyrms", 42, 49, 100, IDLE, true));
		assertFalse(nudge.isRowShown());
		assertEquals(TrackingNudge.Line.NONE, read(49, IDLE));
		assertEquals(TrackingNudge.Line.IDLE, read(48, IDLE));
	}

	@Test
	public void messages()
	{
		assertEquals("Kill not counted: Slayer Ledger isn't tracking. Press Play in the sidebar.",
			TrackingNudge.message(TrackingNudge.Line.IDLE));
		assertEquals("Kill not counted: Slayer Ledger is paused. Press Resume in the sidebar.",
			TrackingNudge.message(TrackingNudge.Line.PAUSED));
		assertEquals(null, TrackingNudge.message(TrackingNudge.Line.NONE));
	}
}
