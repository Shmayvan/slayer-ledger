package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** {@link TaskRewards}: points earned and streak of a completion, and {@link CompletionSignal#incrementedCounter()}. */
public class TaskRewardsTest
{
	private static final int[] COUNTERS = {43, 7, 2};

	@Test
	public void pointsEarnedIsTheIncrease()
	{
		assertEquals(Integer.valueOf(15), TaskRewards.pointsEarned(100, 115));
		assertEquals("Turael's tasks and early streaks give none", Integer.valueOf(0), TaskRewards.pointsEarned(100, 100));
	}

	@Test
	public void pointsEarnedIsNeverNegativeOrGuessed()
	{
		assertNull(TaskRewards.pointsEarned(100, 40));
		assertNull(TaskRewards.pointsEarned(null, 40));
		assertNull(TaskRewards.pointsEarned(40, null));
	}

	@Test
	public void completionUsesTheReadingBeforeTheCompletionTick()
	{
		TaskRewards rewards = new TaskRewards();
		rewards.update(100);
		rewards.update(115);
		rewards.startCompletion("s1", COUNTERS, 0);
		assertTrue(rewards.isFollowing());
		assertEquals("s1", rewards.getSessionId());
		assertEquals(Integer.valueOf(15), rewards.earned());
		assertEquals(Integer.valueOf(43), rewards.streak());
	}

	@Test
	public void pointsSpentEarlierInTheTaskDontMatter()
	{
		TaskRewards rewards = new TaskRewards();
		rewards.update(500);
		// Spent 400 points at a master mid-task.
		rewards.update(100);
		rewards.update(100);
		rewards.update(118);
		rewards.startCompletion("s1", COUNTERS, 0);
		assertEquals(Integer.valueOf(18), rewards.earned());
	}

	@Test
	public void pointsArrivingAfterTheCompletionTickAreFollowed()
	{
		TaskRewards rewards = new TaskRewards();
		rewards.update(100);
		rewards.update(100);
		rewards.startCompletion("s1", COUNTERS, 0);
		assertEquals(Integer.valueOf(0), rewards.earned());
		rewards.update(115);
		assertEquals(Integer.valueOf(15), rewards.earned());
		assertEquals("the streak is fixed at the completion", Integer.valueOf(43), rewards.streak());
	}

	@Test
	public void spendingRightAfterTheCompletionIsNotTrusted()
	{
		TaskRewards rewards = new TaskRewards();
		rewards.update(100);
		rewards.update(115);
		rewards.startCompletion("s1", COUNTERS, 0);
		rewards.update(10);
		assertNull(rewards.earned());
	}

	@Test
	public void firstReadingAfterResetIsOnlyABaseline()
	{
		TaskRewards rewards = new TaskRewards();
		rewards.update(100);
		rewards.reset();
		// Login resync: the first reading has nothing before it.
		rewards.update(900);
		rewards.startCompletion("s1", COUNTERS, 0);
		assertNull(rewards.earned());
		rewards.reset();
		assertFalse("reset also stops following", rewards.isFollowing());
	}

	@Test
	public void stoppedFollowingReportsNothing()
	{
		TaskRewards rewards = new TaskRewards();
		rewards.update(100);
		rewards.update(115);
		rewards.startCompletion("s1", COUNTERS, 1);
		rewards.stopFollowing();
		assertFalse(rewards.isFollowing());
		assertNull(rewards.getSessionId());
		assertNull(rewards.earned());
		assertNull(rewards.streak());
	}

	@Test
	public void streakIsTheCounterThatWentUp()
	{
		assertEquals(Integer.valueOf(43), TaskRewards.streakAfter(COUNTERS, 0));
		assertEquals(Integer.valueOf(7), TaskRewards.streakAfter(COUNTERS, 1));
		assertEquals(Integer.valueOf(2), TaskRewards.streakAfter(COUNTERS, 2));
		assertNull(TaskRewards.streakAfter(COUNTERS, CompletionSignal.NO_COUNTER));
		assertNull(TaskRewards.streakAfter(COUNTERS, 3));
		assertNull(TaskRewards.streakAfter(null, 0));
	}

	@Test
	public void completionSignalNamesTheIncrementedCounter()
	{
		CompletionSignal signal = new CompletionSignal();
		signal.update(42, 6, 2);
		assertEquals(CompletionSignal.NO_COUNTER, signal.incrementedCounter());
		assertTrue(signal.update(42, 7, 2));
		assertEquals("Wilderness", 1, signal.incrementedCounter());
		assertFalse(signal.update(42, 7, 2));
		assertEquals(CompletionSignal.NO_COUNTER, signal.incrementedCounter());
		assertTrue(signal.update(42, 7, 3));
		assertEquals("Mortimer", 2, signal.incrementedCounter());
		assertTrue(signal.update(43, 7, 3));
		assertEquals("regular", 0, signal.incrementedCounter());
	}

	@Test
	public void completionSignalHasNoCounterWhenTwoWentUp()
	{
		CompletionSignal signal = new CompletionSignal();
		signal.update(42, 6, 2);
		assertTrue(signal.update(43, 7, 2));
		assertEquals(CompletionSignal.NO_COUNTER, signal.incrementedCounter());
		assertFalse("a resync", signal.update(50, 7, 2));
		assertEquals(CompletionSignal.NO_COUNTER, signal.incrementedCounter());
		signal.reset();
		assertEquals(CompletionSignal.NO_COUNTER, signal.incrementedCounter());
	}
}
