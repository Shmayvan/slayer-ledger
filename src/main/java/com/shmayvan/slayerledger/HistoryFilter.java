package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.IntFunction;

/**
 * The Task history's filters: Monster, Location and Slayer master, each {@link LedgerHistory#ALL} or one value.
 * A record is shown when it matches every filter set (AND). Each filter's choices are the values in the records, so a
 * choice that matches nothing alone never appears; together the filters can still match nothing. Immutable and pure
 * (no Swing or RuneLite types).
 *
 * <ul>
 *   <li><b>Monster</b>: {@link LedgerHistory#monster} (the record's group name: Catacombs variant, cannon), exactly.</li>
 *   <li><b>Location</b>: the name the history shows ({@link TaskLocation#recordLocation}: the record's own name, else
 *   its assigned area, else its region's current name), compared as {@link TaskLocation#sameLocation} does (ignoring
 *   case and spacing); {@link #NO_LOCATION} for a record without one, shown as {@link #NO_LOCATION_LABEL}.</li>
 *   <li><b>Master</b>: {@link SlayerMaster#displayName} of the master who assigned the task; {@link #UNKNOWN_MASTER}
 *   for a record without one or with a value not in {@link SlayerMaster}'s table.</li>
 * </ul>
 */
final class HistoryFilter
{
	/**
	 * Location choice of the records without a location (older records, or a region nobody named), shown as
	 * {@link #NO_LOCATION_LABEL}. Empty, so it never equals a real name (names are never empty,
	 * {@link TaskLocation#normalizeName}): a place the player named "No location" stays a choice of its own.
	 */
	static final String NO_LOCATION = "";
	/** How the Location filter shows {@link #NO_LOCATION}. */
	static final String NO_LOCATION_LABEL = "No location";
	/**
	 * Master choice of the records whose master is not known. Shown as it is: masters' names come from
	 * {@link SlayerMaster}'s table, so none can be the same.
	 */
	static final String UNKNOWN_MASTER = "Unknown master";

	/** Alphabetical, ignoring case first, as the Monster filter sorts. */
	private static final Comparator<String> ALPHABETICAL = String.CASE_INSENSITIVE_ORDER
		.thenComparing(Comparator.naturalOrder());

	private final String monster;
	private final String location;
	private final String master;

	/**
	 * @param monster  a Monster choice, or {@code null} for "All"
	 * @param location a Location choice ({@link #NO_LOCATION} included), or {@code null} for "All"
	 * @param master   a Master choice ({@link #UNKNOWN_MASTER} included), or {@code null} for "All"
	 */
	HistoryFilter(String monster, String location, String master)
	{
		this.monster = monster;
		this.location = location;
		this.master = master;
	}

	/** How a filter shows {@code choice}: {@link #NO_LOCATION_LABEL} for {@link #NO_LOCATION}, others as they are. */
	static String label(String choice)
	{
		return NO_LOCATION.equals(choice) ? NO_LOCATION_LABEL : choice;
	}

	/** The location a history row shows, as the Location filter lists it: {@link #NO_LOCATION} when it has none. */
	static String location(LedgerRecord record, IntFunction<String> regionNames)
	{
		String name = TaskLocation.recordLocation(record, regionNames);
		return name == null ? NO_LOCATION : name;
	}

	/** The master who assigned the task, as the Master filter lists it: {@link #UNKNOWN_MASTER} when not known. */
	static String master(LedgerRecord record)
	{
		return SlayerMaster.hasName(record.getSlayerMaster()) ? SlayerMaster.displayName(record.getSlayerMaster())
			: UNKNOWN_MASTER;
	}

	/**
	 * Location choices: {@link LedgerHistory#ALL}, each location in the records alphabetically (names that are the
	 * same place, {@link TaskLocation#sameLocation}, once, as the newest record spells it; records are newest first),
	 * then {@link #NO_LOCATION} when a record has none.
	 */
	static List<String> locationOptions(Collection<LedgerRecord> records, IntFunction<String> regionNames)
	{
		Map<String, String> byKey = new LinkedHashMap<>();
		boolean none = false;
		for (LedgerRecord record : records)
		{
			if (record == null)
			{
				continue;
			}
			String name = location(record, regionNames);
			if (NO_LOCATION.equals(name))
			{
				none = true;
			}
			else
			{
				byKey.putIfAbsent(TaskLocation.matchKey(name), name);
			}
		}
		return options(byKey.values(), none, NO_LOCATION);
	}

	/**
	 * Master choices: {@link LedgerHistory#ALL}, each master in the records alphabetically, then
	 * {@link #UNKNOWN_MASTER} when a record's master is not known.
	 */
	static List<String> masterOptions(Collection<LedgerRecord> records)
	{
		List<String> names = new ArrayList<>();
		boolean unknown = false;
		for (LedgerRecord record : records)
		{
			if (record == null)
			{
				continue;
			}
			String name = master(record);
			if (UNKNOWN_MASTER.equals(name))
			{
				unknown = true;
			}
			else
			{
				names.add(name);
			}
		}
		return options(names, unknown, UNKNOWN_MASTER);
	}

	/** {@link LedgerHistory#ALL}, the distinct {@code names} alphabetically, then {@code last} when {@code addLast}. */
	private static List<String> options(Collection<String> names, boolean addLast, String last)
	{
		TreeSet<String> sorted = new TreeSet<>(ALPHABETICAL);
		sorted.addAll(names);
		List<String> options = new ArrayList<>(sorted.size() + 2);
		options.add(LedgerHistory.ALL);
		options.addAll(sorted);
		if (addLast)
		{
			options.add(last);
		}
		return options;
	}

	/**
	 * The Location choice to keep after the choices change ({@code options} start with "All"): the choice that is still
	 * the same place as {@code selection} (as it is spelled now), {@link #NO_LOCATION} while it is still a choice,
	 * otherwise {@code null} ("All").
	 */
	static String keepLocation(List<String> options, String selection)
	{
		if (selection == null)
		{
			return null;
		}
		if (NO_LOCATION.equals(selection))
		{
			return options.contains(NO_LOCATION) ? NO_LOCATION : null;
		}
		for (String option : options.subList(Math.min(1, options.size()), options.size()))
		{
			if (TaskLocation.sameLocation(option, selection))
			{
				return option;
			}
		}
		return null;
	}

	/**
	 * Whether two Location selections ({@code null} for "All") pick the same records: equal, or the same place
	 * spelled another way ({@link TaskLocation#sameLocation}).
	 */
	static boolean sameLocationChoice(String left, String right)
	{
		return Objects.equals(left, right) || TaskLocation.sameLocation(left, right);
	}

	/** Whether {@code record} matches every filter set. */
	boolean matches(LedgerRecord record, IntFunction<String> regionNames)
	{
		if (monster != null && !monster.equals(LedgerHistory.monster(record)))
		{
			return false;
		}
		if (master != null && !master.equals(master(record)))
		{
			return false;
		}
		if (location == null)
		{
			return true;
		}
		String name = location(record, regionNames);
		return NO_LOCATION.equals(location) ? NO_LOCATION.equals(name) : TaskLocation.sameLocation(location, name);
	}

	/** The records matching every filter set, in their order; nulls are left out. */
	List<LedgerRecord> apply(List<LedgerRecord> records, IntFunction<String> regionNames)
	{
		List<LedgerRecord> result = new ArrayList<>();
		for (LedgerRecord record : records)
		{
			if (record != null && matches(record, regionNames))
			{
				result.add(record);
			}
		}
		return result;
	}
}
