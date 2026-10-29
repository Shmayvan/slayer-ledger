package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Rules for a task's location name: how a typed name is cleaned up, which name a record is saved with, and which
 * name the panel shows. A region's name is the player's own (named once, kept per RuneScape profile); a Konar or
 * Wilderness task's assigned area ({@code taskLocation}) is its location without naming. Pure (no RuneLite types).
 */
final class TaskLocation
{
	/** Longest location name kept, in characters; longer names are cut. */
	static final int MAX_NAME_LENGTH = 40;
	/** Shown for a record without a location (older records, or a region nobody named). */
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
	 * The location name a record is saved with: the task's assigned area when it has one (Konar, Wilderness), otherwise
	 * the dominant region's name.
	 *
	 * @param taskLocation the task's assigned area name, or {@code null}
	 * @param regionName   the dominant region's name, or {@code null} when unnamed or there is no region
	 * @return the name, or {@code null} when neither is known
	 */
	static String recordName(String taskLocation, String regionName)
	{
		final String area = normalizeName(taskLocation);
		return area != null ? area : normalizeName(regionName);
	}

	/**
	 * The location a history row shows: the record's own name, else its assigned area (an older Konar or Wilderness
	 * record), else the current name of its region (named after the record was saved; display only, the file is not
	 * changed), else {@link #UNKNOWN}.
	 *
	 * @param regionNames region id to its name, {@code null} when unnamed or not known yet
	 */
	static String displayName(LedgerRecord record, IntFunction<String> regionNames)
	{
		final String name = recordLocation(record, regionNames);
		return name != null ? name : UNKNOWN;
	}

	/**
	 * A saved record's location, as {@link #displayName} finds it: its own name, else its assigned area, else its
	 * region's current name (a record saved before its region was named).
	 *
	 * @return the name, or {@code null} when none is known (never {@link #UNKNOWN})
	 */
	static String recordLocation(LedgerRecord record, IntFunction<String> regionNames)
	{
		final String own = normalizeName(record.getLocation());
		if (own != null)
		{
			return own;
		}
		return liveName(record.getTaskLocation(), record.getLocationRegion(), regionNames);
	}

	/**
	 * The current task's location, for same-location estimates (Part B): its assigned area (Konar, Wilderness),
	 * else the name of the running or paused session's region with the most kills, else the name of the region the
	 * player is in now.
	 *
	 * @param sessionRegion the running or paused session's dominant region, or {@code null}
	 * @param currentRegion the region the player is in now, or {@link LocationTally#NO_REGION}
	 * @return the name, or {@code null} while unknown
	 */
	static String currentName(String taskLocation, Integer sessionRegion, int currentRegion,
		IntFunction<String> regionNames)
	{
		final String name = liveName(taskLocation, sessionRegion, regionNames);
		if (name != null || !LocationTally.isRegion(currentRegion) || regionNames == null)
		{
			return name;
		}
		return normalizeName(regionNames.apply(currentRegion));
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

	/**
	 * The names the "Name this location" prompt suggests: the Wiki's locations of the task, in the Wiki's order, then
	 * the player's own names (for other regions), sorted ignoring case. Each is {@link #normalizeName}d; blank names and
	 * repeats ({@link #sameLocation}, the first spelling kept) are left out.
	 */
	static List<String> suggestions(Collection<String> bundled, Collection<String> own)
	{
		final List<String> suggestions = new ArrayList<>();
		final Set<String> seen = new HashSet<>();
		addSuggestions(suggestions, seen, bundled);
		if (own != null)
		{
			final List<String> sorted = new ArrayList<>();
			for (String name : own)
			{
				if (name != null)
				{
					sorted.add(name);
				}
			}
			sorted.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
			addSuggestions(suggestions, seen, sorted);
		}
		return Collections.unmodifiableList(suggestions);
	}

	private static void addSuggestions(List<String> suggestions, Set<String> seen, Collection<String> names)
	{
		if (names == null)
		{
			return;
		}
		for (String raw : names)
		{
			final String name = normalizeName(raw);
			if (name != null && seen.add(matchKey(name)))
			{
				suggestions.add(name);
			}
		}
	}

	/**
	 * The live session's location: its assigned area, else the name of its dominant region.
	 *
	 * @param region the session's dominant region, or {@code null} when no kill was tallied yet
	 * @return the name, or {@code null} while unknown
	 */
	static String liveName(String taskLocation, Integer region, IntFunction<String> regionNames)
	{
		final String area = normalizeName(taskLocation);
		if (area != null)
		{
			return area;
		}
		if (region == null || !LocationTally.isRegion(region) || regionNames == null)
		{
			return null;
		}
		return normalizeName(regionNames.apply(region));
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
			+ " task (empty to remove its own name):";
	}
}
