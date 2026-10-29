package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.List;

/**
 * Rules for a session's kills per NPC name: each death of an NPC the player damaged while the session ran is
 * added to its name, in the order names are first seen. Names are matched by {@link TaskStatistics#normalize}; the
 * first spelling seen is kept. Pure (no RuneLite types); the lists passed in are never changed.
 */
final class NpcKillTally
{
	/**
	 * Most names tallied per session; kills of further names are not tallied (the task count is unaffected), except a
	 * Catacombs variant's name ({@link CatacombsVariants#isVariantNpcName}), which is always tallied, so a session
	 * that met many other NPCs first still decides its variant. At most {@code MAX_NAMES} plus the variants' names.
	 */
	static final int MAX_NAMES = 32;

	private NpcKillTally()
	{
	}

	/**
	 * A copy of {@code tally} with one more kill of {@code name} (saturating). Nothing is added for a blank name, or a
	 * new name other than a Catacombs variant's once {@link #MAX_NAMES} are tallied.
	 *
	 * @param tally the tally so far, or {@code null} for none
	 */
	static List<NpcKills> add(List<NpcKills> tally, String name)
	{
		final List<NpcKills> result = sanitize(tally);
		merge(result, name, 1);
		return result;
	}

	/**
	 * A clean copy of a tally (for example one read from a file): entries without a name or kills are dropped, a
	 * repeated name is merged into its first entry, and at most {@link #MAX_NAMES} names are kept, plus any Catacombs
	 * variant's names. Never {@code null}.
	 */
	static List<NpcKills> sanitize(List<NpcKills> tally)
	{
		final List<NpcKills> result = new ArrayList<>();
		if (tally == null)
		{
			return result;
		}
		for (NpcKills entry : tally)
		{
			if (entry != null)
			{
				merge(result, entry.getName(), entry.getKills());
			}
		}
		return result;
	}

	/** The kills tallied for {@code name} (matched by {@link TaskStatistics#normalize}); 0 for none. */
	static int killsOf(List<NpcKills> tally, String name)
	{
		final String key = TaskStatistics.normalize(name);
		if (tally == null || key == null)
		{
			return 0;
		}
		int kills = 0;
		for (NpcKills entry : tally)
		{
			if (entry != null && entry.getKills() > 0 && key.equals(TaskStatistics.normalize(entry.getName())))
			{
				kills = saturatedAdd(kills, entry.getKills());
			}
		}
		return kills;
	}

	/** Every kill in the tally; 0 for none. */
	static int total(List<NpcKills> tally)
	{
		if (tally == null)
		{
			return 0;
		}
		int kills = 0;
		for (NpcKills entry : tally)
		{
			if (entry != null && TaskStatistics.normalize(entry.getName()) != null && entry.getKills() > 0)
			{
				kills = saturatedAdd(kills, entry.getKills());
			}
		}
		return kills;
	}

	private static void merge(List<NpcKills> result, String name, int kills)
	{
		final String key = TaskStatistics.normalize(name);
		if (key == null || kills <= 0)
		{
			return;
		}
		for (int i = 0; i < result.size(); i++)
		{
			final NpcKills entry = result.get(i);
			if (key.equals(TaskStatistics.normalize(entry.getName())))
			{
				result.set(i, new NpcKills(entry.getName(), saturatedAdd(entry.getKills(), kills)));
				return;
			}
		}
		if (result.size() < MAX_NAMES || CatacombsVariants.isVariantNpcName(name))
		{
			result.add(new NpcKills(name.trim(), kills));
		}
	}

	private static int saturatedAdd(int a, int b)
	{
		final long sum = (long) a + b;
		return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
	}
}
