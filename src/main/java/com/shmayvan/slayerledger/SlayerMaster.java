package com.shmayvan.slayerledger;

/**
 * Names of the Slayer masters by the game's SLAYER_MASTER varbit value (4067), which says which master assigned the
 * current task. Pure; no RuneLite types.
 *
 * <p>The values come from the OSRS wiki's page for varbit 4067 ("RuneScape:Varbit/4067", read 2026-10-26). Core
 * RuneLite SlayerPlugin (1.13.4) uses the same values for Krystilia (7) and Mortimer (10) to pick the task streak.
 * Some values are shared by two masters that assign the same tasks (Turael and Aya, for example), so the name shows
 * both. A value not in the table shows as "Unknown master (n)", never as a guessed name.</p>
 */
final class SlayerMaster
{
	/** SLAYER_MASTER value when no task has been assigned (and the value before it has synced). */
	static final int NONE = 0;
	static final int KRYSTILIA = 7;
	static final int KONAR = 8;
	static final int MORTIMER = 10;

	/** Names by SLAYER_MASTER value; index 0 (no task) has none. */
	private static final String[] NAMES = {
		null,
		"Turael/Aya",
		"Mazchna",
		"Vannaka",
		"Chaeldar",
		"Duradel/Kuradal",
		"Nieve/Steve",
		"Krystilia",
		"Konar",
		"Spria",
		"Mortimer",
	};

	private SlayerMaster()
	{
	}

	/** Whether {@code master} names a master: a known (non-{@code null}) value other than {@link #NONE}. */
	static boolean isKnown(Integer master)
	{
		return master != null && master != NONE;
	}

	/**
	 * The master's name, "Unknown master (n)" for a value not in the table, or {@code null} when no master is known
	 * ({@code null} or {@link #NONE}).
	 */
	static String displayName(Integer master)
	{
		if (!isKnown(master))
		{
			return null;
		}
		if (master > 0 && master < NAMES.length)
		{
			return NAMES[master];
		}
		return "Unknown master (" + master + ")";
	}

	/** Whether the value is in the name table (so {@link #displayName} is a real name). */
	static boolean hasName(Integer master)
	{
		return isKnown(master) && master > 0 && master < NAMES.length;
	}
}
