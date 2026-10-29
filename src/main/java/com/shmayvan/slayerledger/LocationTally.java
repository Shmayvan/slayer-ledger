package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.List;

/**
 * Rules for a session's kills per map region: each counted kill is added to the region the local player stood in
 * when the task count went down, in the order regions are first seen. The task's location is the region with the most
 * kills; ties go to the region seen first. Pure (no RuneLite types); the lists passed in are never changed.
 */
final class LocationTally
{
	/** Value used for "no region known" (no local player or scene yet). Valid region ids are never negative. */
	static final int NO_REGION = -1;
	/** Most regions tallied per session; kills in further regions are still counted, only not tallied. */
	static final int MAX_REGIONS = 64;

	private LocationTally()
	{
	}

	/** Whether {@code region} is a region id (not {@link #NO_REGION} or another negative value). */
	static boolean isRegion(int region)
	{
		return region >= 0;
	}

	/**
	 * A copy of {@code tally} with {@code kills} more kills in {@code region} (saturating). Nothing is added for an
	 * unknown region, no kills, or a new region once {@link #MAX_REGIONS} are tallied.
	 *
	 * @param tally the tally so far, or {@code null} for none
	 */
	static List<LocationKills> add(List<LocationKills> tally, int region, int kills)
	{
		final List<LocationKills> result = sanitize(tally);
		if (!isRegion(region) || kills <= 0)
		{
			return result;
		}
		for (int i = 0; i < result.size(); i++)
		{
			final LocationKills entry = result.get(i);
			if (entry.getRegion() == region)
			{
				result.set(i, new LocationKills(region, saturatedAdd(entry.getKills(), kills)));
				return result;
			}
		}
		if (result.size() < MAX_REGIONS)
		{
			result.add(new LocationKills(region, kills));
		}
		return result;
	}

	/**
	 * A clean copy of a tally (for example one read from a file): entries without a region or kills are dropped, a
	 * repeated region is merged into its first entry, and at most {@link #MAX_REGIONS} regions are kept. Never
	 * {@code null}.
	 */
	static List<LocationKills> sanitize(List<LocationKills> tally)
	{
		final List<LocationKills> result = new ArrayList<>();
		if (tally == null)
		{
			return result;
		}
		for (LocationKills entry : tally)
		{
			if (entry == null || !isRegion(entry.getRegion()) || entry.getKills() <= 0)
			{
				continue;
			}
			boolean merged = false;
			for (int i = 0; i < result.size(); i++)
			{
				if (result.get(i).getRegion() == entry.getRegion())
				{
					result.set(i, new LocationKills(entry.getRegion(),
						saturatedAdd(result.get(i).getKills(), entry.getKills())));
					merged = true;
					break;
				}
			}
			if (!merged && result.size() < MAX_REGIONS)
			{
				result.add(new LocationKills(entry.getRegion(), entry.getKills()));
			}
		}
		return result;
	}

	/** Whether {@code tally} has kills in {@code region}. */
	static boolean contains(List<LocationKills> tally, int region)
	{
		if (tally == null || !isRegion(region))
		{
			return false;
		}
		for (LocationKills entry : tally)
		{
			if (entry != null && entry.getRegion() == region && entry.getKills() > 0)
			{
				return true;
			}
		}
		return false;
	}

	/** The region with the most kills, the earliest seen among equals, or {@code null} when nothing is tallied. */
	static Integer dominantRegion(List<LocationKills> tally)
	{
		if (tally == null)
		{
			return null;
		}
		LocationKills best = null;
		for (LocationKills entry : tally)
		{
			if (entry == null || !isRegion(entry.getRegion()) || entry.getKills() <= 0)
			{
				continue;
			}
			// Strictly more, so the earliest seen region wins a tie.
			if (best == null || entry.getKills() > best.getKills())
			{
				best = entry;
			}
		}
		return best == null ? null : best.getRegion();
	}

	private static int saturatedAdd(int a, int b)
	{
		final long sum = (long) a + b;
		return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
	}
}
