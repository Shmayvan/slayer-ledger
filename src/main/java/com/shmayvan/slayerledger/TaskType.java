package com.shmayvan.slayerledger;

/**
 * Kind of Slayer task, derived from the assigning master and the task (never stored, so it can't disagree with the
 * raw values it comes from). Pure; no RuneLite types.
 *
 * <p>A boss task (SLAYER_TARGET {@value SessionTracker#BOSS_TASK_TARGET}) is {@link #BOSS} whoever assigned it.
 * Otherwise Krystilia's tasks are {@link #WILDERNESS}, Konar's are {@link #KONAR} and every other known master's are
 * {@link #REGULAR}. Without a known master (or with one missing from {@link SlayerMaster}'s table) a non-boss task
 * has no type. Whether a task was extended is not part of the
 * type: the plugin has no reliable way to tell (see docs/COUNTING_RULES.md).</p>
 */
enum TaskType
{
	REGULAR("Regular"),
	WILDERNESS("Wilderness"),
	KONAR("Konar"),
	BOSS("Boss");

	private final String label;

	TaskType(String label)
	{
		this.label = label;
	}

	/**
	 * @param master     SLAYER_MASTER value, or {@code null} when unknown
	 * @param creatureId creature key ({@link SessionTracker#creatureKey}), or {@code null} or
	 *                   {@link SessionTracker#NO_CREATURE} when unknown
	 * @return the task's type, or {@code null} when it can't be told
	 */
	static TaskType of(Integer master, Integer creatureId)
	{
		if (creatureId != null && creatureId < SessionTracker.NO_CREATURE)
		{
			// Boss keys are -2 - bossId, always below NO_CREATURE.
			return BOSS;
		}
		if (!SlayerMaster.isKnown(master))
		{
			return null;
		}
		switch (master)
		{
			case SlayerMaster.KRYSTILIA:
				return WILDERNESS;
			case SlayerMaster.KONAR:
				return KONAR;
			default:
				// A master missing from the name table could be any kind (a new Wilderness master, say).
				return SlayerMaster.hasName(master) ? REGULAR : null;
		}
	}

	@Override
	public String toString()
	{
		return label;
	}
}
