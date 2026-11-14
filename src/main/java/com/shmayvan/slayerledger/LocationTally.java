package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rules for a session's kills per place: each counted kill is added to the place (map region and Slayer
 * spot, {@link KillPlace}) the local player stood on when the task count went down. The tally is kept in the order the
 * places were last killed at, the most recent last. The task's spot is the spot with the most kills ({@link #decide}).
 * Pure (no RuneLite types); the lists passed in are never changed.
 */
final class LocationTally
{
	/** Value used for "no region known" (no local player or scene yet). Valid region ids are never negative. */
	static final int NO_REGION = -1;
	/** Most places tallied per session; kills at further places are still counted, only not tallied. */
	static final int MAX_REGIONS = 64;

	private LocationTally()
	{
	}

	/** Whether {@code region} is a region id (not {@link #NO_REGION} or another negative value). */
	static boolean isRegion(int region)
	{
		return region >= 0;
	}

	/** {@link #add(List, KillPlace, int)} for a place known only by its region. */
	static List<LocationKills> add(List<LocationKills> tally, int region, int kills)
	{
		return add(tally, KillPlace.ofRegion(region), kills);
	}

	/**
	 * A copy of {@code tally} with {@code kills} more kills at {@code place} (saturating), moved to the end: the place
	 * killed at most recently is last. Nothing is added for an unknown place, no kills, or a new place once
	 * {@link #MAX_REGIONS} are tallied.
	 *
	 * @param tally the tally so far, or {@code null} for none
	 */
	static List<LocationKills> add(List<LocationKills> tally, KillPlace place, int kills)
	{
		final List<LocationKills> result = sanitize(tally);
		if (place == null || !place.isKnown() || kills <= 0)
		{
			return result;
		}
		for (int i = 0; i < result.size(); i++)
		{
			final LocationKills entry = result.get(i);
			if (samePlace(entry, place.getRegion(), place.getSpot()))
			{
				result.remove(i);
				result.add(new LocationKills(entry.getRegion(), entry.getSpot(), saturatedAdd(entry.getKills(), kills)));
				return result;
			}
		}
		if (result.size() < MAX_REGIONS)
		{
			result.add(new LocationKills(place.getRegion(), place.getSpot(), kills));
		}
		return result;
	}

	/**
	 * A clean copy of a tally (for example one read from a file): entries without a region or kills are dropped, a
	 * repeated place is merged into its first entry, and at most {@link #MAX_REGIONS} places are kept. A blank spot
	 * reads as none. Never {@code null}.
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
			final String spot = entry.getSpot() == null || entry.getSpot().trim().isEmpty() ? null
				: entry.getSpot().trim();
			boolean merged = false;
			for (int i = 0; i < result.size(); i++)
			{
				if (samePlace(result.get(i), entry.getRegion(), spot))
				{
					result.set(i, new LocationKills(entry.getRegion(), spot,
						saturatedAdd(result.get(i).getKills(), entry.getKills())));
					merged = true;
					break;
				}
			}
			if (!merged && result.size() < MAX_REGIONS)
			{
				result.add(new LocationKills(entry.getRegion(), spot, entry.getKills()));
			}
		}
		return result;
	}

	/**
	 * The task's spot from its tally (V2 plan 4.5): each entry's spot is its own, or for one without, the spot of its
	 * region for {@code task} ({@link SlayerSpots#spotForRegion}); entries with no spot that way count for none. The
	 * spot with the most kills wins; on a tie, the spot whose task list includes {@code task}, then the spot killed at
	 * most recently. The region saved with it is the spot's region with the most kills (on a tie, the most recent);
	 * with no spot, the region with the most kills of all.
	 *
	 * @param tally the tally, in the order {@link #add} keeps (most recent last), or {@code null}
	 * @param spots the spot table ({@link SlayerSpots#NONE} when unavailable)
	 * @param task  the session's task name, or {@code null}
	 * @return the spot and region, {@link TaskSpot#UNKNOWN} when nothing is tallied
	 */
	static TaskSpot decide(List<LocationKills> tally, SlayerSpots spots, String task)
	{
		final List<LocationKills> entries = sanitize(tally);
		if (entries.isEmpty())
		{
			return TaskSpot.UNKNOWN;
		}
		final SlayerSpots table = spots == null ? SlayerSpots.NONE : spots;
		final Map<String, Group> groups = new LinkedHashMap<>();
		final Group all = new Group(null);
		for (int i = 0; i < entries.size(); i++)
		{
			final LocationKills entry = entries.get(i);
			all.add(entry, i);
			final String key = entry.getSpot() != null ? entry.getSpot()
				: table.spotForRegion(entry.getRegion(), task);
			if (key != null)
			{
				groups.computeIfAbsent(key, Group::new).add(entry, i);
			}
		}
		Group best = null;
		boolean bestListsTask = false;
		for (Group group : groups.values())
		{
			final boolean listsTask = table.listsTask(group.key, task);
			if (best == null || group.kills > best.kills
				|| (group.kills == best.kills && listsTask && !bestListsTask)
				|| (group.kills == best.kills && listsTask == bestListsTask && group.last > best.last))
			{
				best = group;
				bestListsTask = listsTask;
			}
		}
		return best == null ? new TaskSpot(null, all.topRegion()) : new TaskSpot(best.key, best.topRegion());
	}

	private static boolean samePlace(LocationKills entry, int region, String spot)
	{
		return entry.getRegion() == region && Objects.equals(entry.getSpot(), spot);
	}

	private static int saturatedAdd(int a, int b)
	{
		final long sum = (long) a + b;
		return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
	}

	/** One spot's entries while deciding: its kills, its latest entry and its kills per region. */
	private static final class Group
	{
		private final String key;
		private int kills;
		/** Index of its most recent entry. */
		private int last = -1;
		/** Kills per region, and each region's most recent entry index. */
		private final Map<Integer, int[]> regions = new LinkedHashMap<>();

		private Group(String key)
		{
			this.key = key;
		}

		private void add(LocationKills entry, int index)
		{
			kills = saturatedAdd(kills, entry.getKills());
			last = Math.max(last, index);
			final int[] region = regions.computeIfAbsent(entry.getRegion(), r -> new int[]{0, -1});
			region[0] = saturatedAdd(region[0], entry.getKills());
			region[1] = Math.max(region[1], index);
		}

		/** The region with the most kills, the most recent among equals. */
		private Integer topRegion()
		{
			Integer best = null;
			int bestKills = -1;
			int bestLast = -1;
			for (Map.Entry<Integer, int[]> region : regions.entrySet())
			{
				final int[] counts = region.getValue();
				if (counts[0] > bestKills || (counts[0] == bestKills && counts[1] > bestLast))
				{
					best = region.getKey();
					bestKills = counts[0];
					bestLast = counts[1];
				}
			}
			return best;
		}
	}
}
