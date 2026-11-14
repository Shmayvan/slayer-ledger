package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;

/** {@link TaskCaves}: which task's cave the player is in (V2 plan 4.1). */
public class TaskCavesTest
{
	@Test
	public void eachTaskHasItsOwnCave()
	{
		assertTrue(TaskCaves.isInside("TzTok-Jad", TaskCaves.FIGHT_CAVES_REGION));
		assertTrue(TaskCaves.isInside("TzKal-Zuk", TaskCaves.INFERNO_REGION));
		assertFalse(TaskCaves.isInside("TzTok-Jad", TaskCaves.INFERNO_REGION));
		assertFalse(TaskCaves.isInside("TzKal-Zuk", TaskCaves.FIGHT_CAVES_REGION));
	}

	@Test
	public void namesIgnoreCapitalsAndOuterSpaces()
	{
		assertTrue(TaskCaves.isInside(" tztok-jad ", TaskCaves.FIGHT_CAVES_REGION));
		assertTrue(TaskCaves.isInside("TZKAL-ZUK", TaskCaves.INFERNO_REGION));
	}

	@Test
	public void otherTasksNoTaskAndNoRegionAreNeverInside()
	{
		assertFalse("the TzHaar task is another task", TaskCaves.isInside("Tzhaar", TaskCaves.FIGHT_CAVES_REGION));
		assertFalse(TaskCaves.isInside("Abyssal demons", TaskCaves.INFERNO_REGION));
		assertFalse(TaskCaves.isInside(null, TaskCaves.FIGHT_CAVES_REGION));
		assertFalse(TaskCaves.isInside("", TaskCaves.FIGHT_CAVES_REGION));
		assertFalse(TaskCaves.isInside("TzTok-Jad", LocationTally.NO_REGION));
		assertFalse(TaskCaves.isInside("TzTok-Jad", 9808));
	}

	/**
	 * The regions are core TimersAndBuffsPlugin's, and the task names core SlayerPlugin's. Both are package-private in
	 * RuneLite, so they are read by reflection here (test code only; the plugin can't), so a RuneLite change fails the
	 * build.
	 */
	@Test
	public void theRegionsAndNamesAreRuneLitesOwn() throws ReflectiveOperationException
	{
		final Class<?> timers = Class.forName("net.runelite.client.plugins.timersandbuffs.TimersAndBuffsPlugin");
		assertEquals(TaskCaves.FIGHT_CAVES_REGION, staticInt(timers, "FIGHT_CAVES_REGION_ID"));
		assertEquals(TaskCaves.INFERNO_REGION, staticInt(timers, "INFERNO_REGION_ID"));

		final Class<?> task = Class.forName("net.runelite.client.plugins.slayer.Task");
		final Method getName = task.getDeclaredMethod("getName");
		getName.setAccessible(true);
		assertEquals(TaskCaves.JAD_TASK, getName.invoke(enumConstant(task, "JAD")));
		assertEquals(TaskCaves.ZUK_TASK, getName.invoke(enumConstant(task, "ZUK")));
	}

	private static int staticInt(Class<?> type, String name) throws ReflectiveOperationException
	{
		final Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		return field.getInt(null);
	}

	private static Object enumConstant(Class<?> type, String name)
	{
		for (Object constant : type.getEnumConstants())
		{
			if (((Enum<?>) constant).name().equals(name))
			{
				return constant;
			}
		}
		throw new AssertionError("no " + name + " in " + type);
	}
}
