package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/** {@link AssignmentOutcome#classify}: every outcome, and the ambiguous cases that must stay unknown. */
public class AssignmentOutcomeTest
{
	private static AssignmentOutcome classify(boolean completed, Integer points, boolean blocked, boolean streakDropped)
	{
		return AssignmentOutcome.classify(completed, points, blocked, streakDropped);
	}

	@Test
	public void pointsSpentWithoutABlockIsASkip()
	{
		assertEquals(AssignmentOutcome.SKIPPED, classify(false, -30, false, false));
		// Costs vary by master; any amount counts.
		assertEquals(AssignmentOutcome.SKIPPED, classify(false, -100, false, false));
		assertEquals(AssignmentOutcome.SKIPPED, classify(false, -1, false, false));
	}

	@Test
	public void pointsSpentWithABlockChangeIsABlock()
	{
		assertEquals(AssignmentOutcome.BLOCKED, classify(false, -100, true, false));
		assertEquals(AssignmentOutcome.BLOCKED, classify(false, -40, true, false));
	}

	@Test
	public void aStreakDropWithNoPointsSpentIsAReset()
	{
		assertEquals(AssignmentOutcome.RESET, classify(false, 0, false, true));
	}

	@Test
	public void aCompletionIsCompleted()
	{
		assertEquals(AssignmentOutcome.COMPLETED, classify(true, 15, false, false));
		assertEquals("no points (an early task)", AssignmentOutcome.COMPLETED, classify(true, 0, false, false));
		assertEquals("points not read", AssignmentOutcome.COMPLETED, classify(true, null, false, false));
	}

	@Test
	public void nothingChangedIsUnknown()
	{
		assertEquals(AssignmentOutcome.UNKNOWN, classify(false, 0, false, false));
	}

	@Test
	public void ambiguousSignalsAreUnknown()
	{
		assertEquals("completion and streak reset", AssignmentOutcome.UNKNOWN, classify(true, 15, false, true));
		assertEquals("completion and block", AssignmentOutcome.UNKNOWN, classify(true, -85, true, false));
		assertEquals("points spent and streak reset", AssignmentOutcome.UNKNOWN, classify(false, -30, false, true));
		assertEquals("points spent, block and streak reset", AssignmentOutcome.UNKNOWN,
			classify(false, -100, true, true));
		assertEquals("points up without a completion", AssignmentOutcome.UNKNOWN, classify(false, 20, false, false));
		assertEquals("a block without points spent", AssignmentOutcome.UNKNOWN, classify(false, 0, true, false));
		assertEquals("reset with a block change", AssignmentOutcome.UNKNOWN, classify(false, 0, true, true));
		assertEquals("points unknown", AssignmentOutcome.UNKNOWN, classify(false, null, false, false));
		assertEquals("points unknown with a block", AssignmentOutcome.UNKNOWN, classify(false, null, true, false));
		assertEquals("points unknown with a streak drop", AssignmentOutcome.UNKNOWN, classify(false, null, false, true));
	}

	@Test
	public void aMissingOutcomeReadsAsUnknown()
	{
		assertEquals(AssignmentOutcome.UNKNOWN, AssignmentOutcome.orUnknown(null));
		assertEquals(AssignmentOutcome.BLOCKED, AssignmentOutcome.orUnknown(AssignmentOutcome.BLOCKED));
		assertEquals("Ended (reason unknown)", AssignmentOutcome.UNKNOWN.getDescription());
	}
}
