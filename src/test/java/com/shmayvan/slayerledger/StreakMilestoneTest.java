package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** {@link StreakMilestone}: the next bonus-points task of a streak and which counter a master's streak is. */
public class StreakMilestoneTest
{
	@Test
	public void nextBonusIsTheNextMultipleOfTen()
	{
		assertEquals("Next bonus: task 10 (in 10)", StreakMilestone.format(0));
		assertEquals("Next bonus: task 10 (in 1)", StreakMilestone.format(9));
		assertEquals("strictly above: task 10 is done", "Next bonus: task 20 (in 10)", StreakMilestone.format(10));
		assertEquals("Next bonus: task 50 (in 3)", StreakMilestone.format(47));
		assertEquals("Next bonus: task 1000 (in 1)", StreakMilestone.format(999));
		assertEquals("Next bonus: task 1010 (in 10)", StreakMilestone.format(1000));
		assertEquals("Next bonus: task 12350 (in 5)", StreakMilestone.format(12345));
	}

	@Test
	public void shortFormForTheTaskBox()
	{
		assertEquals("bonus in 3", StreakMilestone.formatShort(47));
		assertEquals("bonus in 10", StreakMilestone.formatShort(10));
		assertNull(StreakMilestone.formatShort(null));
		assertNull(StreakMilestone.formatShort(Integer.MAX_VALUE));
	}

	@Test
	public void largeStreaksDontOverflow()
	{
		assertEquals(Integer.valueOf(2_000_000_000), StreakMilestone.nextMilestone(1_999_999_999));
		assertEquals(Integer.valueOf(2_147_483_640), StreakMilestone.nextMilestone(Integer.MAX_VALUE - 10));
		assertNull(StreakMilestone.nextMilestone(Integer.MAX_VALUE - 9));
		assertNull(StreakMilestone.format(Integer.MAX_VALUE));
	}

	@Test
	public void unknownStreakHasNoHint()
	{
		assertNull(StreakMilestone.format(null));
		assertNull(StreakMilestone.format(-1));
		assertNull(StreakMilestone.nextMilestone(Integer.MIN_VALUE));
	}

	@Test
	public void milestoneIsAPositiveMultipleOfTen()
	{
		for (int streak : new int[]{10, 50, 100, 250, 1000, 1010})
		{
			assertTrue("streak " + streak, StreakMilestone.isMilestone(streak));
		}
		for (int streak : new int[]{0, 1, 9, 11, 99, 101, -10})
		{
			assertFalse("streak " + streak, StreakMilestone.isMilestone(streak));
		}
		assertFalse("unknown", StreakMilestone.isMilestone(null));
	}

	@Test
	public void counterFollowsTheMaster()
	{
		assertEquals(StreakMilestone.WILDERNESS, StreakMilestone.counterFor(SlayerMaster.KRYSTILIA));
		assertEquals(StreakMilestone.MORTIMER, StreakMilestone.counterFor(SlayerMaster.MORTIMER));
		for (int master : new int[]{1, 2, 3, 4, 5, 6, SlayerMaster.KONAR, 9})
		{
			assertEquals("master " + master, StreakMilestone.REGULAR, StreakMilestone.counterFor(master));
		}
	}

	@Test
	public void unknownMasterHasNoCounter()
	{
		assertEquals(StreakMilestone.NO_COUNTER, StreakMilestone.counterFor(null));
		assertEquals(StreakMilestone.NO_COUNTER, StreakMilestone.counterFor(SlayerMaster.NONE));
		assertEquals("not in the table", StreakMilestone.NO_COUNTER, StreakMilestone.counterFor(11));
		assertEquals(StreakMilestone.NO_COUNTER, StreakMilestone.counterFor(-3));
	}
}
