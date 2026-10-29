package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;

/** {@link SessionTracker#takeSessionTaskKeys()}: which tasks a session held since the last call. */
public class SessionTrackerTaskKeysTest
{
	private static final int DEMONS = 42;
	private static final int GARGOYLES = 46;
	private static final long ACCOUNT = 555L;

	private long now = 1_000_000L;
	private SessionTracker tracker;

	@Before
	public void setUp()
	{
		tracker = new SessionTracker(() -> now);
		tracker.observe("Abyssal demons", DEMONS, 100, false);
	}

	@Test
	public void noSessionHoldsNoTask()
	{
		assertTrue(tracker.takeSessionTaskKeys().isEmpty());
	}

	@Test
	public void anActiveSessionsTaskIsReportedEveryCall()
	{
		assertTrue(tracker.play(ACCOUNT));
		assertEquals(Collections.singleton(DEMONS), tracker.takeSessionTaskKeys());
		assertEquals(Collections.singleton(DEMONS), tracker.takeSessionTaskKeys());
		tracker.pause();
		assertEquals("paused too", Collections.singleton(DEMONS), tracker.takeSessionTaskKeys());
	}

	@Test
	public void aSessionThatEndedBeforeTheCallIsStillReportedOnce()
	{
		assertTrue(tracker.play(ACCOUNT));
		tracker.stopAndReset();
		assertEquals(Collections.singleton(DEMONS), tracker.takeSessionTaskKeys());
		assertTrue(tracker.takeSessionTaskKeys().isEmpty());
	}

	@Test
	public void aTaskAdoptedLaterIsReported()
	{
		tracker.observe(null, SessionTracker.NO_CREATURE, 0, false);
		assertTrue(tracker.play(ACCOUNT));
		assertTrue("no task yet", tracker.takeSessionTaskKeys().isEmpty());
		tracker.observe("Gargoyles", GARGOYLES, 150, false);
		assertEquals(Collections.singleton(GARGOYLES), tracker.takeSessionTaskKeys());
	}

	@Test
	public void aHeldCompletionIsReported()
	{
		assertTrue(tracker.play(ACCOUNT));
		tracker.takeSessionTaskKeys();
		assertEquals(SessionTracker.ObserveResult.COMPLETED, tracker.observe(null, SessionTracker.NO_CREATURE, 0, true));
		assertTrue(tracker.holdCompletion());
		assertEquals(Collections.singleton(DEMONS), tracker.takeSessionTaskKeys());
		tracker.takePendingCompletion();
		assertTrue(tracker.takeSessionTaskKeys().isEmpty());
	}

	@Test
	public void aRestoredSessionIsReported()
	{
		SessionSnapshot snapshot = SessionSnapshot.fromSummary(startedSummary(), null, now);
		snapshot.setAccountHash(ACCOUNT);
		assertEquals(SessionTracker.RestoreResult.RESTORED, tracker.restore(snapshot, null));
		assertEquals(Collections.singleton(DEMONS), tracker.takeSessionTaskKeys());
	}

	/** A stopped session on the demons task, as saved at a logout. */
	private SessionTracker.Summary startedSummary()
	{
		SessionTracker other = new SessionTracker(() -> now);
		other.observe("Abyssal demons", DEMONS, 100, false);
		other.play(ACCOUNT);
		return other.stopAndReset();
	}
}
