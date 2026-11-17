package com.shmayvan.slayerledger;

import java.util.*;
import java.util.function.*;
import lombok.*;

/**
 * The Task history's filters: Monster, Location and Slayer master, each {@link LedgerHistory#ALL} or one value.
 * A record is shown when it matches every filter set (AND). Each filter's choices are the values in the records, so a
 * choice that matches nothing alone never appears; together the filters can still match nothing. Immutable and pure
 * (no Swing or RuneLite types).
 *
 * <ul>
 *   <li><b>Monster</b>: {@link LedgerHistory#monster} (the record's group name: Catacombs variant, cannon), exactly.</li>
 *   <li><b>Location</b>: the record's Slayer spot ({@link SlayerSpots#recordSpot}), so a task renamed on its own
 *   still groups with its spot's other tasks; each choice is shown as the location name
 *   ({@link TaskLocation#recordLocation}) of its newest record without a name of its own ({@link #locationChoices}).
 *   A record without a spot but with a name of its own (an older task named by hand, or an older Konar task with no
 *   region) groups by that name, compared by {@link TaskLocation#matchKey}; one with neither is
 *   {@link #NO_LOCATION}, shown as {@link #NO_LOCATION_LABEL}.</li>
 *   <li><b>Master</b>: {@link SlayerMaster#displayName} of the master who assigned the task; {@link #UNKNOWN_MASTER}
 *   for a record without one or with a value not in {@link SlayerMaster}'s table.</li>
 * </ul>
 *
 * <p>The setup filters (V2 plan 5) are {@link #ANY_LABEL "Any"} ({@code null}) or one value, each with a fixed
 * list of choices: <b>Task type</b> ({@link #TASK_TYPE_CHOICES}, {@link TaskType#of} the record's master and creature
 * key), <b>Cannon</b> ({@link #CANNON_CHOICES}), <b>Style</b> ({@link #STYLE_CHOICES}) and <b>Gear</b>
 * ({@link #GEAR_CHOICES}), the last three the record's effective tags ({@link SetupTags#of}: the player's override,
 * else the detected value). A record whose value is unknown (no task type, an Unknown style or gear) matches only
 * "Any", and an estimated value is its value ({@code ~Barrage} is Barrage). Set them with {@link #withTaskType},
 * {@link #withCannon}, {@link #withStyle} and {@link #withGear}.</p>
 */
final class HistoryFilter
{
	/** How the setup filters show "no filter" ({@code null}). */
	static final String ANY_LABEL = "Any";
	/** The Task type filter's choices, in order; {@code null} is "Any". Unmodifiable. */
	static final List<TaskType> TASK_TYPE_CHOICES = choices(null, TaskType.REGULAR, TaskType.KONAR,
		TaskType.WILDERNESS, TaskType.BOSS);
	/** The Cannon filter's choices, in order: "Any" ({@code null}), "Yes", "No". Unmodifiable. */
	static final List<Boolean> CANNON_CHOICES = choices(null, Boolean.TRUE, Boolean.FALSE);
	/** The Style filter's choices, in order; {@code null} is "Any" (Unknown is not a choice). Unmodifiable. */
	static final List<SetupStyle> STYLE_CHOICES = choices(null, SetupStyle.MELEE, SetupStyle.RANGED,
		SetupStyle.BARRAGE, SetupStyle.MAGIC, SetupStyle.MIXED);
	/** The Gear filter's choices, in order; {@code null} is "Any" (Unknown is not a choice). Unmodifiable. */
	static final List<GearFocus> GEAR_CHOICES = choices(null, GearFocus.PRAYER, GearFocus.STRENGTH, GearFocus.MIXED);

	/**
	 * Location choice of the records without a location (older records without a region), shown as
	 * {@link #NO_LOCATION_LABEL}. Empty, so it never equals a spot or a name key.
	 */
	static final String NO_LOCATION = "";
	/** How the Location filter shows {@link #NO_LOCATION}. */
	static final String NO_LOCATION_LABEL = "No location";
	/**
	 * Start of the Location choice of records without a spot that have a name of their own: {@code name:} and the name's
	 * {@link TaskLocation#matchKey}. Spot keys never contain it (table ids have no colon; unlisted regions' keys start
	 * {@link SlayerSpots#REGION_PREFIX}).
	 */
	static final String NAME_PREFIX = "name:";
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
	/** The Task type choice, or {@code null} for "Any". */
	private final TaskType taskType;
	/** The Cannon choice, or {@code null} for "Any". */
	private final Boolean cannon;
	/** The Style choice, or {@code null} for "Any"; never {@link SetupStyle#UNKNOWN}. */
	private final SetupStyle style;
	/** The Gear choice, or {@code null} for "Any"; never {@link GearFocus#UNKNOWN}. */
	private final GearFocus gear;

	/**
	 * The Monster, Location and Master filters, with every setup filter "Any".
	 *
	 * @param monster  a Monster choice, or {@code null} for "All"
	 * @param location a Location choice ({@link #locationKey}; {@link #NO_LOCATION} included), or {@code null} for "All"
	 * @param master   a Master choice ({@link #UNKNOWN_MASTER} included), or {@code null} for "All"
	 */
	HistoryFilter(String monster, String location, String master)
	{
		this(monster, location, master, null, null, null, null);
	}

	private HistoryFilter(String monster, String location, String master, TaskType taskType, Boolean cannon,
		SetupStyle style, GearFocus gear)
	{
		this.monster = monster;
		this.location = location;
		this.master = master;
		this.taskType = taskType;
		this.cannon = cannon;
		this.style = style == SetupStyle.UNKNOWN ? null : style;
		this.gear = gear == GearFocus.UNKNOWN ? null : gear;
	}

	/** This filter with the Task type choice {@code type} ({@code null}: "Any"). */
	HistoryFilter withTaskType(TaskType type)
	{
		return new HistoryFilter(monster, location, master, type, cannon, style, gear);
	}

	/** This filter with the Cannon choice {@code withCannon} ({@code null}: "Any"). */
	HistoryFilter withCannon(Boolean withCannon)
	{
		return new HistoryFilter(monster, location, master, taskType, withCannon, style, gear);
	}

	/** This filter with the Style choice {@code choice} ({@code null}, and Unknown, which is no choice: "Any"). */
	HistoryFilter withStyle(SetupStyle choice)
	{
		return new HistoryFilter(monster, location, master, taskType, cannon, choice, gear);
	}

	/** This filter with the Gear choice {@code choice} ({@code null}, and Unknown, which is no choice: "Any"). */
	HistoryFilter withGear(GearFocus choice)
	{
		return new HistoryFilter(monster, location, master, taskType, cannon, style, choice);
	}

	/** How the Monster and Master filters show {@code choice}: {@link #NO_LOCATION_LABEL} for {@link #NO_LOCATION}. */
	static String label(String choice)
	{
		return NO_LOCATION.equals(choice) ? NO_LOCATION_LABEL : choice;
	}

	/**
	 * How a setup filter shows {@code choice}: {@link #ANY_LABEL} for {@code null}, "Yes" or "No" for a cannon choice
	 * ({@link SetupTags#cannonLabel}), and a type's, style's or gear focus's own label ("Konar", "Barrage", "Prayer").
	 */
	static String setupLabel(Object choice)
	{
		if (choice == null)
		{
			return ANY_LABEL;
		}
		if (choice instanceof Boolean)
		{
			return SetupTags.cannonLabel((Boolean) choice);
		}
		if (choice instanceof SetupStyle)
		{
			return ((SetupStyle) choice).getLabel();
		}
		if (choice instanceof GearFocus)
		{
			return ((GearFocus) choice).getLabel();
		}
		return choice.toString();
	}

	@SafeVarargs
	private static <T> List<T> choices(T... values)
	{
		return Collections.unmodifiableList(Arrays.asList(values));
	}

	/**
	 * A record's Location choice: its spot's key ({@link SlayerSpots#recordSpot}), else {@link #NAME_PREFIX} and its own
	 * name's {@link TaskLocation#matchKey}, else {@link #NO_LOCATION}.
	 */
	static String locationKey(LedgerRecord record, SlayerSpots spots, IntFunction<String> regionNames)
	{
		final SlayerSpots table = spots == null ? SlayerSpots.NONE : spots;
		final String spot = table.recordSpot(record);
		if (spot != null)
		{
			return spot;
		}
		final String name = TaskLocation.recordLocation(record, regionNames, table);
		return name == null ? NO_LOCATION : NAME_PREFIX + TaskLocation.matchKey(name);
	}

	/** The master who assigned the task, as the Master filter lists it: {@link #UNKNOWN_MASTER} when not known. */
	static String master(LedgerRecord record)
	{
		return SlayerMaster.hasName(record.getSlayerMaster()) ? SlayerMaster.displayName(record.getSlayerMaster())
			: UNKNOWN_MASTER;
	}

	/**
	 * The Location filter's choices: {@link LedgerHistory#ALL}, each location key in the records ({@link #locationKey})
	 * alphabetically by its label, then {@link #NO_LOCATION} when a record has none. Each key's label is the location
	 * name of its newest record without a name of its own (records are newest first): its region's name or the table's
	 * name, since a per-task rename names that task only (V2 plan 4.5). Only when every record of the key has its own
	 * name (1.x tasks named by hand) is it the newest record's own name. {@link #NO_LOCATION}'s is
	 * {@link #NO_LOCATION_LABEL}.
	 */
	static LocationChoices locationChoices(Collection<LedgerRecord> records, SlayerSpots spots,
		IntFunction<String> regionNames)
	{
		// Each key's newest known name from a record without a name of its own, and from one with.
		final Set<String> found = new HashSet<>();
		final Map<String, String> placeNames = new HashMap<>();
		final Map<String, String> ownNames = new HashMap<>();
		boolean none = false;
		for (LedgerRecord record : records)
		{
			if (record == null)
			{
				continue;
			}
			final String key = locationKey(record, spots, regionNames);
			if (NO_LOCATION.equals(key))
			{
				none = true;
				continue;
			}
			found.add(key);
			final String name = TaskLocation.recordLocation(record, regionNames, spots);
			if (name != null)
			{
				final boolean ownName = TaskLocation.normalizeName(record.getLocation()) != null;
				(ownName ? ownNames : placeNames).putIfAbsent(key, name);
			}
		}
		final Map<String, String> labels = new HashMap<>();
		for (String key : found)
		{
			final String place = placeNames.get(key);
			final String own = ownNames.get(key);
			labels.put(key, place != null ? place : own != null ? own : TaskLocation.UNKNOWN);
		}
		final List<String> keys = new ArrayList<>(labels.keySet());
		keys.sort(Comparator.comparing((String key) -> labels.get(key), ALPHABETICAL)
			.thenComparing(Comparator.naturalOrder()));
		final List<String> options = new ArrayList<>(keys.size() + 2);
		options.add(LedgerHistory.ALL);
		options.addAll(keys);
		final Map<String, String> allLabels = new HashMap<>(labels);
		if (none)
		{
			options.add(NO_LOCATION);
			allLabels.put(NO_LOCATION, NO_LOCATION_LABEL);
		}
		return new LocationChoices(options, allLabels);
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
		TreeSet<String> sorted = new TreeSet<>(ALPHABETICAL);
		sorted.addAll(names);
		List<String> options = new ArrayList<>(sorted.size() + 2);
		options.add(LedgerHistory.ALL);
		options.addAll(sorted);
		if (unknown)
		{
			options.add(UNKNOWN_MASTER);
		}
		return options;
	}

	/**
	 * The Location choice to keep after the choices change ({@code options} start with "All"): {@code selection} while
	 * it is still a choice (a spot keeps its key when its tasks are renamed), otherwise {@code null} ("All").
	 */
	static String keepLocation(List<String> options, String selection)
	{
		return selection != null && options.subList(Math.min(1, options.size()), options.size()).contains(selection)
			? selection : null;
	}

	/** Whether {@code record} matches every filter set. */
	boolean matches(LedgerRecord record, SlayerSpots spots, IntFunction<String> regionNames)
	{
		if (monster != null && !monster.equals(LedgerHistory.monster(record)))
		{
			return false;
		}
		if (master != null && !master.equals(master(record)))
		{
			return false;
		}
		if (!matchesSetup(record))
		{
			return false;
		}
		return location == null || location.equals(locationKey(record, spots, regionNames));
	}

	/**
	 * Whether {@code record} matches the setup filters (Task type, Cannon, Style, Gear): each "Any", or the record's
	 * value. An unknown value matches only "Any"; an estimated value counts as its value.
	 */
	boolean matchesSetup(LedgerRecord record)
	{
		if (taskType != null && taskType != TaskType.of(record.getSlayerMaster(), record.getCreatureId()))
		{
			return false;
		}
		if (cannon == null && style == null && gear == null)
		{
			return true;
		}
		final SetupTags tags = SetupTags.of(record);
		return (cannon == null || cannon == tags.isCannon()) && (style == null || style == tags.getStyle())
			&& (gear == null || gear == tags.getGear());
	}

	/** The records matching every filter set, in their order; nulls are left out. */
	List<LedgerRecord> apply(List<LedgerRecord> records, SlayerSpots spots, IntFunction<String> regionNames)
	{
		List<LedgerRecord> result = new ArrayList<>();
		for (LedgerRecord record : records)
		{
			if (record != null && matches(record, spots, regionNames))
			{
				result.add(record);
			}
		}
		return result;
	}

	/** The Location filter's choices and how each is shown. Immutable. */
	@Getter
	static final class LocationChoices
	{
		/** {@link LedgerHistory#ALL}, the location keys, then {@link #NO_LOCATION} if any record has none. */
		private final List<String> options;
		/** Each key's label (not {@link LedgerHistory#ALL}'s). */
		private final Map<String, String> labels;

		private LocationChoices(List<String> options, Map<String, String> labels)
		{
			this.options = Collections.unmodifiableList(options);
			this.labels = Collections.unmodifiableMap(labels);
		}

		/** How the filter shows {@code key}: its label, else the key itself ("All"). */
		String label(String key)
		{
			final String label = labels.get(key);
			return label != null ? label : key;
		}
	}
}
