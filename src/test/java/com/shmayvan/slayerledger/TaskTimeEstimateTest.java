package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.OptionalLong;
import org.junit.Test;

/**
 * The live "time left" estimate ({@link TaskTimeEstimate}) and how the panel words it.
 */
public class TaskTimeEstimateTest
{
	private static final long MINUTE = 60_000;

	@Test
	public void noKillsGivesNoEstimate()
	{
		assertFalse(TaskTimeEstimate.remainingMillis(10 * MINUTE, 0, 100).isPresent());
		assertFalse(TaskTimeEstimate.killsPerHour(10 * MINUTE, 0).isPresent());
		assertFalse(TaskTimeEstimate.averageKillMillis(10 * MINUTE, 0).isPresent());
	}

	@Test
	public void belowKillThresholdGivesNoEstimate()
	{
		assertFalse(TaskTimeEstimate.remainingMillis(10 * MINUTE, TaskTimeEstimate.MIN_KILLS - 1, 100).isPresent());
		assertTrue(TaskTimeEstimate.remainingMillis(10 * MINUTE, TaskTimeEstimate.MIN_KILLS, 100).isPresent());
	}

	@Test
	public void belowTimeThresholdGivesNoEstimate()
	{
		assertFalse(TaskTimeEstimate.remainingMillis(TaskTimeEstimate.MIN_ACTIVE_MILLIS - 1, 10, 100).isPresent());
		assertTrue(TaskTimeEstimate.remainingMillis(TaskTimeEstimate.MIN_ACTIVE_MILLIS, 10, 100).isPresent());
	}

	@Test
	public void normalCaseIsAverageKillTimesKillsLeft()
	{
		// 10 kills in 5 minutes: 30 s a kill, 120 kills/hour; 40 left is 20 minutes.
		assertEquals(OptionalLong.of(20 * MINUTE), TaskTimeEstimate.remainingMillis(5 * MINUTE, 10, 40));
		assertEquals(120d, TaskTimeEstimate.killsPerHour(5 * MINUTE, 10).getAsDouble(), 1e-9);
		assertEquals(OptionalLong.of(30_000), TaskTimeEstimate.averageKillMillis(5 * MINUTE, 10));
	}

	@Test
	public void resultIsRounded()
	{
		// 100,000 ms / 3 kills * 1 = 33,333.33 ms.
		assertEquals(OptionalLong.of(33_333), TaskTimeEstimate.remainingMillis(100_000, 3, 1));
		// 100,000 ms / 3 kills * 2 = 66,666.67 ms.
		assertEquals(OptionalLong.of(66_667), TaskTimeEstimate.remainingMillis(100_000, 3, 2));
	}

	@Test
	public void nothingRemainingGivesNoEstimate()
	{
		assertFalse(TaskTimeEstimate.remainingMillis(10 * MINUTE, 10, 0).isPresent());
		assertFalse(TaskTimeEstimate.remainingMillis(10 * MINUTE, 10, -1).isPresent());
	}

	@Test
	public void hugeValuesSaturate()
	{
		assertEquals(OptionalLong.of(Long.MAX_VALUE),
			TaskTimeEstimate.remainingMillis(Long.MAX_VALUE, TaskTimeEstimate.MIN_KILLS, Integer.MAX_VALUE));
		// Large but representable: one kill an hour over 1,000 kills, Integer.MAX_VALUE left.
		long hour = 60 * MINUTE;
		assertEquals(OptionalLong.of(hour * Integer.MAX_VALUE),
			TaskTimeEstimate.remainingMillis(1_000 * hour, 1_000, Integer.MAX_VALUE));
		assertTrue(TaskTimeEstimate.averageKillMillis(Long.MAX_VALUE, TaskTimeEstimate.MIN_KILLS).getAsLong() > 0);
		// The panel still formats a saturated estimate.
		assertTrue(LiveSessionSection.timeLeftText(Long.MAX_VALUE, TaskTimeEstimate.MIN_KILLS, Integer.MAX_VALUE)
			.startsWith("Time left: ~"));
	}

	@Test
	public void restoredSessionUsesItsRestoredKillsAndTime()
	{
		// A session restored after a logout: 60 kills in 45 minutes of active time, 90 left. Only the inputs differ.
		assertEquals(OptionalLong.of(67 * MINUTE + 30_000), TaskTimeEstimate.remainingMillis(45 * MINUTE, 60, 90));
	}

	@Test
	public void taskExtensionRaisesTheEstimate()
	{
		// 20 kills in 10 minutes; the task count goes from 30 left to 130 left after an extension.
		OptionalLong before = TaskTimeEstimate.remainingMillis(10 * MINUTE, 20, 30);
		OptionalLong after = TaskTimeEstimate.remainingMillis(10 * MINUTE, 20, 130);
		assertEquals(OptionalLong.of(15 * MINUTE), before);
		assertEquals(OptionalLong.of(65 * MINUTE), after);
	}

	@Test
	public void panelTextEstimatesThenCountsDown()
	{
		assertEquals(LiveSessionSection.TIME_LEFT_ESTIMATING, LiveSessionSection.timeLeftText(MINUTE, 2, 50));
		assertEquals(LiveSessionSection.TIME_LEFT_ESTIMATING, LiveSessionSection.timeLeftText(30_000, 5, 50));
		assertEquals("Time left: ~20m", LiveSessionSection.timeLeftText(5 * MINUTE, 10, 40));
		assertEquals("Time left: ~1h 08m", LiveSessionSection.timeLeftText(45 * MINUTE, 60, 90));
	}

	@Test
	public void panelTextAfterCompletionOrWithoutTask()
	{
		assertEquals(LiveSessionSection.TIME_LEFT_NONE, LiveSessionSection.timeLeftText(10 * MINUTE, 40, 0));
		assertEquals("no task: nothing to estimate even with no kills", LiveSessionSection.TIME_LEFT_NONE,
			LiveSessionSection.timeLeftText(0, 0, 0));
	}

	@Test
	public void panelTooltipShowsPace()
	{
		String tooltip = LiveSessionSection.timeLeftTooltip(5 * MINUTE, 10);
		assertTrue(tooltip, tooltip.contains("Kills per hour: 120"));
		assertTrue(tooltip, tooltip.contains("Average kill: 30s"));
		assertEquals("Estimated after 3 kills and 1 minute of active time.",
			LiveSessionSection.timeLeftTooltip(30_000, 10));
	}
}
