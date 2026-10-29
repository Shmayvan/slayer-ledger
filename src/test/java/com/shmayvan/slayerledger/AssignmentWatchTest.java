package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link AssignmentWatch}: which assignment ends produce an entry, with which outcome, and the guards (played tasks,
 * transient zeros, logins). The readings come from a small fake of the game's values, with a real
 * {@link CompletionSignal} as in the plugin.
 */
public class AssignmentWatchTest
{
	private static final int DEMONS = 42;
	private static final int GARGOYLES = 46;

	private final AssignmentWatch watch = new AssignmentWatch();
	private final CompletionSignal completionSignal = new CompletionSignal();
	private final List<AssignmentWatch.Ended> ended = new ArrayList<>();
	private long now = 1_000_000L;

	// The fake game's values.
	private String monster;
	private int creatureId = SessionTracker.NO_CREATURE;
	private int remaining;
	private int amount;
	private int master = SlayerMaster.NONE;
	private int points = 500;
	private int[] counters = {10, 0, 0};
	private int[] blocked = {0, 0, 0};
	/** Keys a session held this tick. */
	private Set<Integer> sessionKeys = Collections.emptySet();

	@Before
	public void setUp()
	{
		// The first readings after a start only sync; with no task, the sync ends after SYNC_MAX_TICKS.
		clearTask();
		ticks(AssignmentWatch.SYNC_MAX_TICKS);
		// Then the task is assigned while watched.
		assign("Abyssal demons", DEMONS, 120, 5);
		tick();
		assertTrue(watch.hasCurrent());
		assertTrue(ended.isEmpty());
	}

	private void assign(String name, int key, int count, int byMaster)
	{
		monster = name;
		creatureId = key;
		remaining = count;
		amount = count;
		master = byMaster;
	}

	private void clearTask()
	{
		monster = null;
		creatureId = SessionTracker.NO_CREATURE;
		remaining = 0;
		amount = 0;
		master = SlayerMaster.NONE;
	}

	private void tick()
	{
		final boolean completion = completionSignal.update(counters);
		final TaskContext context = remaining > 0 ? TaskContext.assignment(master, 0, null) : TaskContext.NONE;
		ended.addAll(watch.update(AssignmentWatch.Reading.builder().monster(monster).creatureId(creatureId)
			.remaining(remaining).originalAmount(amount).context(context).points(points).counters(counters.clone())
			.blockedValues(blocked.clone()).completionSignaled(completion).build(), sessionKeys, now));
		now += 600;
	}

	private void ticks(int n)
	{
		for (int i = 0; i < n; i++)
		{
			tick();
		}
	}

	/** Ticks out the decision window. */
	private void settle()
	{
		ticks(AssignmentWatch.END_WINDOW_TICKS + 1);
	}

	private void login()
	{
		watch.reset();
		completionSignal.reset();
	}

	private AssignmentEntry only()
	{
		assertEquals(ended.toString(), 1, ended.size());
		return ended.get(0).getEntry();
	}

	@Test
	public void aTaskAssignedWhileWatchedIsStartedOnce()
	{
		assertTrue(watch.takeStarted());
		assertFalse(watch.takeStarted());
		tick();
		assertFalse(watch.takeStarted());
	}

	@Test
	public void theSameTaskBackAfterAZeroIsNotStartedAgain()
	{
		watch.takeStarted();
		remaining = 80;
		tick();
		clearTask();
		ticks(2);
		assign("Abyssal demons", DEMONS, 120, 5);
		remaining = 80;
		tick();
		assertFalse("transient zero", watch.takeStarted());
		clearTask();
		ticks(20);
		assign("Abyssal demons", DEMONS, 120, 5);
		remaining = 79;
		tick();
		assertFalse("long zero", watch.takeStarted());
	}

	@Test
	public void aDirectChangeToAnotherTaskIsStarted()
	{
		watch.takeStarted();
		assign("Gargoyles", GARGOYLES, 150, 5);
		tick();
		assertTrue(watch.takeStarted());
		assertFalse(watch.takeStarted());
	}

	@Test
	public void aTaskSeenAfterAForgetOrLoginIsNotStarted()
	{
		watch.takeStarted();
		watch.forget();
		tick();
		assertFalse("account change", watch.takeStarted());
		login();
		ticks(AssignmentWatch.SYNC_MAX_TICKS);
		assertFalse("login", watch.takeStarted());
	}

	@Test
	public void aSkipIsRecordedWithThePointsSpent()
	{
		remaining = 80;
		tick();
		final long endTime = now;
		clearTask();
		points -= 30;
		settle();

		AssignmentEntry e = only();
		assertEquals(AssignmentOutcome.SKIPPED, e.getOutcome());
		assertEquals(Integer.valueOf(-30), e.getPointsChange());
		assertEquals("Abyssal demons", e.getMonster());
		assertEquals(Integer.valueOf(DEMONS), e.getCreatureId());
		assertEquals(Integer.valueOf(120), e.getOriginalAmount());
		assertEquals(Integer.valueOf(5), e.getSlayerMaster());
		assertEquals("the first tick it was gone", endTime, e.getEndedAt());
		assertNotNull(e.getId());
		assertFalse("watched from its start", ended.get(0).isCheckRecords());
	}

	@Test
	public void theOutcomeIsDecidedAfterTheWindow()
	{
		clearTask();
		points -= 30;
		ticks(AssignmentWatch.END_WINDOW_TICKS);
		assertTrue("not yet", ended.isEmpty());
		tick();
		assertEquals(AssignmentOutcome.SKIPPED, only().getOutcome());
	}

	@Test
	public void pointsArrivingLateInTheWindowStillCount()
	{
		clearTask();
		tick();
		points -= 30;
		settle();
		assertEquals(AssignmentOutcome.SKIPPED, only().getOutcome());
		assertEquals(Integer.valueOf(-30), only().getPointsChange());
	}

	@Test
	public void aSkipFollowedQuicklyByANewTaskIsStillASkip()
	{
		clearTask();
		points -= 30;
		tick();
		assign("Gargoyles", GARGOYLES, 150, 5);
		settle();
		assertEquals(AssignmentOutcome.SKIPPED, only().getOutcome());
		assertEquals("Abyssal demons", only().getMonster());
		assertTrue("the new task is watched", watch.hasCurrent());
	}

	@Test
	public void aBlockIsRecorded()
	{
		clearTask();
		points -= 100;
		blocked[1] = DEMONS;
		settle();
		AssignmentEntry e = only();
		assertEquals(AssignmentOutcome.BLOCKED, e.getOutcome());
		assertEquals(Integer.valueOf(-100), e.getPointsChange());
	}

	@Test
	public void aTuraelResetIsRecorded()
	{
		// Turael gives a new task straight away and the streak drops to 0; no points are spent.
		assign("Cows", 6, 20, 1);
		counters[0] = 0;
		settle();
		AssignmentEntry e = only();
		assertEquals(AssignmentOutcome.RESET, e.getOutcome());
		assertEquals("Abyssal demons", e.getMonster());
		assertEquals(Integer.valueOf(0), e.getPointsChange());
	}

	@Test
	public void aCompletionWithoutASessionIsRecorded()
	{
		remaining = 1;
		tick();
		clearTask();
		counters[0]++;
		points += 15;
		settle();
		AssignmentEntry e = only();
		assertEquals(AssignmentOutcome.COMPLETED, e.getOutcome());
		assertEquals(Integer.valueOf(15), e.getPointsChange());
	}

	@Test
	public void aStreakArrivingAfterTheCountStillCountsAsACompletion()
	{
		clearTask();
		tick();
		counters[0]++;
		points += 15;
		settle();
		assertEquals(AssignmentOutcome.COMPLETED, only().getOutcome());
	}

	@Test
	public void anEndThatFitsNothingIsUnknownOnceTheNextTaskAppears()
	{
		// Count to 0 with nothing else changing (for example the task was stored).
		clearTask();
		settle();
		assertTrue("an unknown end by a 0 count waits", ended.isEmpty());
		ticks(20);
		assertTrue(ended.isEmpty());
		assign("Gargoyles", GARGOYLES, 150, 5);
		tick();
		AssignmentEntry e = only();
		assertEquals(AssignmentOutcome.UNKNOWN, e.getOutcome());
		assertEquals(Integer.valueOf(0), e.getPointsChange());
	}

	@Test
	public void aDirectChangeThatFitsNothingIsUnknown()
	{
		assign("Gargoyles", GARGOYLES, 150, 5);
		settle();
		assertEquals(AssignmentOutcome.UNKNOWN, only().getOutcome());
	}

	@Test
	public void theUnknownOutcomeUsesTheWindowsReadings()
	{
		clearTask();
		settle();
		// Points spent long after the end (a reward bought) don't turn it into a skip.
		points -= 30;
		ticks(5);
		assign("Gargoyles", GARGOYLES, 150, 5);
		tick();
		assertEquals(AssignmentOutcome.UNKNOWN, only().getOutcome());
		assertEquals(Integer.valueOf(0), only().getPointsChange());
	}

	@Test
	public void aTransientZeroIsNotAnEnd()
	{
		remaining = 80;
		tick();
		clearTask();
		ticks(2);
		assign("Abyssal demons", DEMONS, 120, 5);
		remaining = 80;
		settle();
		assertTrue(ended.isEmpty());
		assertFalse(watch.hasPending());
	}

	@Test
	public void theSameTaskAfterALongZeroIsNotAnEnd()
	{
		remaining = 80;
		tick();
		clearTask();
		ticks(20);
		assign("Abyssal demons", DEMONS, 120, 5);
		remaining = 79;
		settle();
		assertTrue(ended.isEmpty());
	}

	@Test
	public void theSameCreatureWithMoreLeftAfterALongZeroIsANewAssignment()
	{
		remaining = 80;
		tick();
		clearTask();
		ticks(20);
		assign("Abyssal demons", DEMONS, 120, 5);
		tick();
		assertEquals(AssignmentOutcome.UNKNOWN, only().getOutcome());
	}

	@Test
	public void killsOnTheTaskAreNotAnEnd()
	{
		for (int i = 0; i < 10; i++)
		{
			remaining--;
			tick();
		}
		settle();
		assertTrue(ended.isEmpty());
	}

	@Test
	public void aPlayedTaskIsNeverRecorded()
	{
		sessionKeys = Collections.singleton(DEMONS);
		tick();
		sessionKeys = Collections.emptySet();
		clearTask();
		points -= 30;
		settle();
		assertTrue("skipped after a session", ended.isEmpty());
	}

	@Test
	public void aCompletedPlayedTaskIsNeverRecorded()
	{
		sessionKeys = Collections.singleton(DEMONS);
		clearTask();
		counters[0]++;
		tick();
		sessionKeys = Collections.emptySet();
		settle();
		assertTrue(ended.isEmpty());
	}

	@Test
	public void aSessionTakingTheTaskOnTheEndTickStillCounts()
	{
		clearTask();
		points -= 30;
		// Reported on the tick the count dropped (for example Play pressed and Stopped within one tick).
		sessionKeys = Collections.singleton(DEMONS);
		tick();
		sessionKeys = Collections.emptySet();
		settle();
		assertTrue(ended.isEmpty());
	}

	@Test
	public void aSessionOnAnotherTaskDoesNotMarkThisOne()
	{
		sessionKeys = new HashSet<>(Collections.singletonList(GARGOYLES));
		tick();
		sessionKeys = Collections.emptySet();
		clearTask();
		points -= 30;
		settle();
		assertEquals(AssignmentOutcome.SKIPPED, only().getOutcome());
	}

	@Test
	public void theFirstReadingAfterALoginProducesNothing()
	{
		login();
		// The task was skipped while logged out (on another client): the login resync shows a new task.
		points -= 30;
		assign("Gargoyles", GARGOYLES, 150, 5);
		tick();
		settle();
		assertTrue(ended.isEmpty());
		assertTrue(watch.hasCurrent());
	}

	@Test
	public void aZeroCountWhileTheLoginSyncsProducesNothing()
	{
		login();
		clearTask();
		points = 0;
		ticks(3);
		assign("Abyssal demons", DEMONS, 120, 5);
		points = 500;
		settle();
		assertTrue(ended.isEmpty());
	}

	@Test
	public void anAssignmentActiveAtLogoutKeepsItsPlayedFlag()
	{
		sessionKeys = Collections.singleton(DEMONS);
		tick();
		sessionKeys = Collections.emptySet();
		login();
		tick();
		clearTask();
		points -= 30;
		settle();
		assertTrue("played before the logout", ended.isEmpty());
	}

	@Test
	public void anUndecidedEndIsDroppedByALogin()
	{
		clearTask();
		points -= 30;
		tick();
		login();
		settle();
		assertTrue(ended.isEmpty());
		assertFalse(watch.hasPending());
	}

	@Test
	public void anAssignmentFirstSeenAtAStartIsCheckedAgainstTheRecords()
	{
		watch.forget();
		completionSignal.reset();
		tick();
		clearTask();
		points -= 30;
		settle();
		assertEquals(AssignmentOutcome.SKIPPED, only().getOutcome());
		assertTrue(ended.get(0).isCheckRecords());
	}

	@Test
	public void forgettingDropsThePlayedFlag()
	{
		sessionKeys = Collections.singleton(DEMONS);
		tick();
		sessionKeys = Collections.emptySet();
		// Another account logged in.
		watch.forget();
		tick();
		clearTask();
		points -= 30;
		settle();
		assertEquals(1, ended.size());
	}

	@Test
	public void aSecondEndInsideTheWindowDecidesTheFirstAtOnce()
	{
		// Turael reset to cows, then the cows task is skipped straight away.
		assign("Cows", 6, 20, 1);
		counters[0] = 0;
		tick();
		clearTask();
		points -= 30;
		tick();
		assertEquals(1, ended.size());
		assertEquals("Abyssal demons", ended.get(0).getEntry().getMonster());
		assertEquals("judged before the skip", AssignmentOutcome.RESET, ended.get(0).getEntry().getOutcome());
		settle();
		assertEquals(2, ended.size());
		assertEquals("Cows", ended.get(1).getEntry().getMonster());
		assertEquals(AssignmentOutcome.SKIPPED, ended.get(1).getEntry().getOutcome());
	}

	@Test
	public void noTaskAtAllProducesNothing()
	{
		clearTask();
		login();
		ticks(AssignmentWatch.SYNC_MAX_TICKS + 5);
		assertTrue(ended.isEmpty());
		assertFalse(watch.hasCurrent());
	}

	@Test
	public void unknownPartsAreStoredAsNull()
	{
		watch.forget();
		completionSignal.reset();
		monster = null;
		amount = 0;
		master = SlayerMaster.NONE;
		tick();
		clearTask();
		points -= 30;
		settle();
		AssignmentEntry e = only();
		assertNull(e.getMonster());
		assertNull(e.getOriginalAmount());
		assertNull(e.getSlayerMaster());
	}
}
