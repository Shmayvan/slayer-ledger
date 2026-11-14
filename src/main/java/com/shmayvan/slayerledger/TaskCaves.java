package com.shmayvan.slayerledger;

/**
 * Jad and Zuk tasks (V2 plan 4.1): their only task kill comes at the end of a long run, so being inside the Fight
 * Caves or the Inferno with that task assigned is Running. This tells whether the player is inside the cave of the
 * assigned task. Pure (no RuneLite types).
 *
 * <p>The tasks are recognised by their name, the one the Slayer task DB table gives (as core SlayerPlugin names them
 * in its {@code Task} enum, RuneLite 1.13.4): "TzTok-Jad" and "TzKal-Zuk". These are their own task types, apart from
 * the "Tzhaar" task. The regions are the ones core TimersAndBuffsPlugin checks (RuneLite 1.13.4,
 * {@code FIGHT_CAVES_REGION_ID} and {@code INFERNO_REGION_ID}); both caves are instances, so the region is the one
 * the instance copies, as the plugin reads it for kill locations.</p>
 */
final class TaskCaves
{
	/** The Fight Caves' map region (core TimersAndBuffsPlugin's {@code FIGHT_CAVES_REGION_ID}, RuneLite 1.13.4). */
	static final int FIGHT_CAVES_REGION = 9551;
	/** The Inferno's map region (core TimersAndBuffsPlugin's {@code INFERNO_REGION_ID}, RuneLite 1.13.4). */
	static final int INFERNO_REGION = 9043;
	/** The Jad task's name (core SlayerPlugin's {@code Task.JAD}). */
	static final String JAD_TASK = "TzTok-Jad";
	/** The Zuk task's name (core SlayerPlugin's {@code Task.ZUK}). */
	static final String ZUK_TASK = "TzKal-Zuk";

	private TaskCaves()
	{
	}

	/**
	 * Whether {@code task} is a Jad or Zuk task, whose session restarts when the player goes into its cave (the overlay's
	 * state line says so). Names are compared as {@link #isInside} compares them.
	 */
	static boolean isCaveTask(String task)
	{
		final String name = TaskStatistics.normalize(task);
		return name != null && (name.equals(TaskStatistics.normalize(JAD_TASK))
			|| name.equals(TaskStatistics.normalize(ZUK_TASK)));
	}

	/**
	 * Whether the player, in map region {@code region}, is inside the cave of the task named {@code task}: the Fight
	 * Caves on a Jad task, the Inferno on a Zuk task. Names are compared ignoring capitals and outer spaces.
	 *
	 * @param task   the assigned task's name, or {@code null} when there is none or it is not known yet
	 * @param region the player's map region (an instance's template region), or {@link LocationTally#NO_REGION}
	 */
	static boolean isInside(String task, int region)
	{
		final String name = TaskStatistics.normalize(task);
		if (name == null || !LocationTally.isRegion(region))
		{
			return false;
		}
		if (name.equals(TaskStatistics.normalize(JAD_TASK)))
		{
			return region == FIGHT_CAVES_REGION;
		}
		return name.equals(TaskStatistics.normalize(ZUK_TASK)) && region == INFERNO_REGION;
	}
}
