package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** {@link SlayerMaster}'s names by SLAYER_MASTER value and {@link TaskType}'s derivation from them. */
public class SlayerMasterTest
{
	@Test
	public void namesFollowTheWikiTable()
	{
		assertEquals("Turael/Aya", SlayerMaster.displayName(1));
		assertEquals("Mazchna", SlayerMaster.displayName(2));
		assertEquals("Vannaka", SlayerMaster.displayName(3));
		assertEquals("Chaeldar", SlayerMaster.displayName(4));
		assertEquals("Duradel/Kuradal", SlayerMaster.displayName(5));
		assertEquals("Nieve/Steve", SlayerMaster.displayName(6));
		assertEquals("Krystilia", SlayerMaster.displayName(SlayerMaster.KRYSTILIA));
		assertEquals("Konar", SlayerMaster.displayName(SlayerMaster.KONAR));
		assertEquals("Spria", SlayerMaster.displayName(9));
		assertEquals("Mortimer", SlayerMaster.displayName(SlayerMaster.MORTIMER));
	}

	@Test
	public void valuesOutsideTheTableAreNeverGuessed()
	{
		assertEquals("Unknown master (11)", SlayerMaster.displayName(11));
		assertEquals("Unknown master (-3)", SlayerMaster.displayName(-3));
		assertFalse(SlayerMaster.hasName(11));
		assertTrue(SlayerMaster.isKnown(11));
	}

	@Test
	public void noMasterHasNoName()
	{
		assertNull(SlayerMaster.displayName(null));
		assertNull(SlayerMaster.displayName(SlayerMaster.NONE));
		assertFalse(SlayerMaster.isKnown(SlayerMaster.NONE));
		assertFalse(SlayerMaster.hasName(null));
	}

	@Test
	public void typeFollowsTheMaster()
	{
		assertEquals(TaskType.WILDERNESS, TaskType.of(SlayerMaster.KRYSTILIA, 42));
		assertEquals(TaskType.KONAR, TaskType.of(SlayerMaster.KONAR, 42));
		assertEquals(TaskType.REGULAR, TaskType.of(6, 42));
		assertEquals(TaskType.REGULAR, TaskType.of(SlayerMaster.MORTIMER, 42));
	}

	@Test
	public void bossTasksAreBossesWhoeverAssignedThem()
	{
		int bossKey = SessionTracker.creatureKey(SessionTracker.BOSS_TASK_TARGET, 12);
		assertEquals(TaskType.BOSS, TaskType.of(SlayerMaster.KONAR, bossKey));
		assertEquals(TaskType.BOSS, TaskType.of(null, bossKey));
		assertEquals(TaskType.BOSS, TaskType.of(5, SessionTracker.creatureKey(SessionTracker.BOSS_TASK_TARGET, 0)));
	}

	@Test
	public void unknownOrUnmappedMasterHasNoType()
	{
		assertNull(TaskType.of(null, 42));
		assertNull(TaskType.of(SlayerMaster.NONE, 42));
		assertNull("a master missing from the table could be any kind", TaskType.of(11, 42));
		assertNull(TaskType.of(null, null));
		assertNull(TaskType.of(null, SessionTracker.NO_CREATURE));
	}

	@Test
	public void typeLabels()
	{
		assertEquals("Regular", TaskType.REGULAR.toString());
		assertEquals("Wilderness", TaskType.WILDERNESS.toString());
		assertEquals("Konar", TaskType.KONAR.toString());
		assertEquals("Boss", TaskType.BOSS.toString());
	}
}
