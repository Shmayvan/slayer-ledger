package com.shmayvan.slayerledger;

import java.util.Locale;
import java.util.function.IntFunction;
import lombok.Value;

/**
 * Rules for a task's location name: how a typed name is cleaned up and which name the panel shows. A task's
 * location is its Slayer spot ({@link SlayerSpots}, decided from its kills); the name shown is, in order, the player's
 * own name for the task ({@link LedgerRecord#getLocation}, "Edit location"), the player's name for its map region (kept
 * per RuneScape profile: named in 1.x, or an unlisted region renamed since; not for a region several spots share,
 * {@link #spotName}), the spot table's name ("Region N" for an unlisted region), and a Konar or Wilderness task's
 * assigned area when its spot is Unknown. Names never decide the spot: tasks group by spot id. Pure (no RuneLite
 * types).
 */
final class TaskLocation
{
	/** Longest location name kept, in characters; longer names are cut. */
	static final int MAX_NAME_LENGTH = 40;
	/** Shown for a record without a location (older records without a region). */
	static final String UNKNOWN = "Unknown";

	private TaskLocation()
	{
	}

	/**
	 * A location name as stored: cleaned up like a note ({@link TaskNote#normalize(String, int)}: whitespace runs become
	 * one space, the ends are trimmed) and cut to {@link #MAX_NAME_LENGTH} characters.
	 *
	 * @return the name, or {@code null} when nothing is left (an empty name is never stored)
	 */
	static String normalizeName(String raw)
	{
		return TaskNote.normalize(raw, MAX_NAME_LENGTH);
	}

	/**
	 * The location a history row shows ({@link #recordLocation}), or {@link #UNKNOWN}.
	 *
	 * @param regionNames region id to the player's name for it, {@code null} when unnamed or not known yet
	 */
	static String displayName(LedgerRecord record, IntFunction<String> regionNames, SlayerSpots spots)
	{
		final String name = recordLocation(record, regionNames, spots);
		return name != null ? name : UNKNOWN;
	}

	/**
	 * A saved record's location name: its own name (the player's for this task; a 1.x record's name as saved), else
	 * its spot's name ({@link #spotName}: its region's name, else the table's), else its assigned area (an older Konar
	 * or Wilderness record with no spot). The spot is {@link SlayerSpots#recordSpot}: the stored one, or for a record
	 * saved before spots, its region's.
	 *
	 * @return the name, or {@code null} when none is known (never {@link #UNKNOWN})
	 */
	static String recordLocation(LedgerRecord record, IntFunction<String> regionNames, SlayerSpots spots)
	{
		final String own = normalizeName(record.getLocation());
		if (own != null)
		{
			return own;
		}
		final SlayerSpots table = spots == null ? SlayerSpots.NONE : spots;
		final String spot = spotName(table.recordSpot(record), record.getLocationRegion(), regionNames, table);
		return spot != null ? spot : normalizeName(record.getTaskLocation());
	}

	/**
	 * The location a Task history row shows on its second line: {@link #recordLocation}'s name, with
	 * the spot table's short name ({@link SlayerSpots#shortName}, "Catacombs") in place of its full name ("Catacombs
	 * of Kourend") when that is the name shown. A name the player gave (the task's own, or its region's) is shown as
	 * given; the row cuts a long one short and its tooltip has the full location.
	 *
	 * @return the name, or {@code null} when none is known
	 */
	static String shortName(LedgerRecord record, IntFunction<String> regionNames, SlayerSpots spots)
	{
		final String name = recordLocation(record, regionNames, spots);
		if (name == null || spots == null)
		{
			return name;
		}
		final String spot = spots.recordSpot(record);
		if (spot == null || !name.equals(normalizeName(spots.name(spot))))
		{
			return name;
		}
		final String shortName = spots.shortName(spot);
		return shortName != null ? shortName : name;
	}

	/**
	 * A spot's name without a task's own name: the player's name for {@code region} (one they gave it in 1.x, or the
	 * name of an unlisted region they renamed), else the table's name of {@code spotKey} ({@link SlayerSpots#name}:
	 * "Region N" for an unlisted region). The region's name is used only for a region with at most one spot in the
	 * table (V2 plan 4.5), or for the Unknown spot, which has no table name: a 1.x name for a region several spots share
	 * (Kraken Cove and its boss room, Karuulm and the Hydra's lair) can't tell which of them it meant, so each of them
	 * shows its table name.
	 *
	 * @param spotKey the spot key, or {@code null} for the Unknown spot
	 * @param region  the region the task was saved with or is decided from, or {@code null}
	 * @return the name, or {@code null} when neither is known
	 */
	static String spotName(String spotKey, Integer region, IntFunction<String> regionNames, SlayerSpots spots)
	{
		final SlayerSpots table = spots == null ? SlayerSpots.NONE : spots;
		if (region != null && LocationTally.isRegion(region) && regionNames != null
			&& (spotKey == null || table.spotsInRegion(region).size() <= 1))
		{
			final String named = normalizeName(regionNames.apply(region));
			if (named != null)
			{
				return named;
			}
		}
		return spots == null ? null : normalizeName(spots.name(spotKey));
	}

	/**
	 * The live session's location: the name of its spot ({@link #spotName} of its {@link TaskSpot}).
	 *
	 * @param spot the session's spot so far ({@link TaskSpot#UNKNOWN} with no kill tallied)
	 * @return the name, or {@code null} while unknown
	 */
	static String liveName(TaskSpot spot, IntFunction<String> regionNames, SlayerSpots spots)
	{
		return spot == null ? null : spotName(spot.getSpotId(), spot.getRegion(), regionNames, spots);
	}

	/**
	 * The current task's location, for same-location estimates (by spot): the active session's
	 * spot, named as {@link #liveName} names it; else its assigned area (Konar, Wilderness), with the spot the table
	 * gives that name ({@link SlayerSpots#spotNamed}, none when it has no such spot); else the spot the player is in
	 * now, named as {@link #spotName} names it. A session whose spot is Unknown (a shared region its kills don't decide)
	 * is passed over like no session.
	 *
	 * @param sessionSpot   the active session's spot, or {@link TaskSpot#UNKNOWN}
	 * @param currentRegion the region the player is in now, or {@link LocationTally#NO_REGION}
	 * @param currentSpot   the spot the player is in now, or {@code null}
	 * @return the location; {@link Here#UNKNOWN} while neither is known
	 */
	static Here current(String taskLocation, TaskSpot sessionSpot, int currentRegion, String currentSpot,
		IntFunction<String> regionNames, SlayerSpots spots)
	{
		final SlayerSpots table = spots == null ? SlayerSpots.NONE : spots;
		if (sessionSpot != null && sessionSpot.getSpotId() != null)
		{
			return new Here(sessionSpot.getSpotId(), liveName(sessionSpot, regionNames, table));
		}
		final String area = normalizeName(taskLocation);
		if (area != null)
		{
			return new Here(table.spotNamed(area), area);
		}
		return new Here(currentSpot, spotName(currentSpot, LocationTally.isRegion(currentRegion) ? currentRegion : null,
			regionNames, table));
	}

	/**
	 * Where the current task is, for the same-location statistics (the live card's time left and the overlay's pace):
	 * its spot key ({@link SlayerSpots}), which the saved tasks are matched by ({@link SlayerSpots#recordSpot}), and
	 * its name, which the time left's tooltip shows. Immutable; compares by value, so it can be part of a cache key.
	 */
	@Value
	static class Here
	{
		/** No location known: every task counts. */
		static final Here UNKNOWN = new Here(null, null);

		/** The spot key, or {@code null} when no spot is known (then no task is matched by location). */
		String spot;
		/**
		 * The name the time left's tooltip shows ("from 1 completed task at Smoke Dungeon"), or {@code null} when none
		 * is known.
		 */
		String name;

		/** Whether tasks can be matched by location: the spot and its name are known. */
		boolean isKnown()
		{
			return spot != null && name != null;
		}
	}

	/**
	 * A location name as names are compared: {@link #normalizeName}d and lower case, so names that differ only in
	 * case or spacing are the same place.
	 *
	 * @return the key, or {@code null} for no name
	 */
	static String matchKey(String name)
	{
		final String normalized = normalizeName(name);
		return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
	}

	/** Whether two location names are the same place ({@link #matchKey}); no name never matches. */
	static boolean sameLocation(String left, String right)
	{
		final String key = matchKey(left);
		return key != null && key.equals(matchKey(right));
	}

	/** The location line of the live task box and the history tooltip: {@code Location: Smoke Dungeon}. */
	static String format(String name)
	{
		return "Location: " + (name == null ? UNKNOWN : name);
	}

	/** The question of the history's "Edit location" dialog. */
	static String editPrompt(LedgerRecord record)
	{
		final int creatureId = record.getCreatureId() == null ? SessionTracker.NO_CREATURE : record.getCreatureId();
		return "Location of this " + LedgerRecord.displayName(record.getMonster(), creatureId)
			+ " task only (empty to remove its own name):";
	}

	/** The question of the history's "Rename place" dialog, for an unlisted region ({@link SlayerSpots#regionKey}). */
	static String renamePrompt(int region)
	{
		return "Name of " + SlayerSpots.regionName(region) + ", for every task there:";
	}
}
