package com.shmayvan.slayerledger;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * The bundled Slayer spot table (V2 plan 4.5): each named Slayer spot's stable id, its full and short names, the
 * map regions it covers (with the plane or bounds that decide a region it shares with other spots) and the tasks done
 * there. It names a task's location from where its kills happened. An instance holds one validated snapshot
 * ({@link #load}); the lookups are pure (no RuneLite types) and the instance is immutable, so any thread may use it.
 *
 * <p><b>Spot keys.</b> A spot is keyed by its table id ({@code catacombs-of-kourend}) or, for a map region the table
 * doesn't list, by {@link #regionKey} ({@code region:7238}): an unlisted region is a spot of its own. {@code null} is
 * the Unknown spot. While the table is unavailable ({@link #NONE}: missing or invalid), listed and unlisted regions
 * can't be told apart, so no key is given at all.</p>
 *
 * <p><b>Matching</b> (the table's {@code matching} rule): a point's region is
 * {@code ((x >> 6) << 8) | (y >> 6)}, as {@code WorldPoint.getRegionID()} gives it. A region listed by one spot
 * belongs to that spot on every plane. In a region listed by several spots, a spot matches only if its {@code plane} is
 * {@code null} or the point's plane, and its {@code bounds} are {@code null} or one inclusive {@code [x1, y1, x2, y2]}
 * box contains the point; plane and bounds are used only in such shared regions.</p>
 *
 * <p><b>Data and licence.</b> Adapted from the Old School RuneScape Wiki by Old School RuneScape Wiki contributors,
 * under CC BY-NC-SA 3.0 (the file's {@code attribution}; each spot lists the Wiki revisions it was read from).
 * {@code mejrs/data_osrs} was used to verify the coordinates and translate relocated Wiki map pins, and a few region ids
 * were derived from it (the file's {@code verifiedWith}; the spots that rest on it cite it); none of its records are
 * copied. The README credits both.</p>
 */
@Slf4j
final class SlayerSpots
{
	/** The bundled snapshot, relative to this class. */
	static final String SNAPSHOT_RESOURCE = "slayer-spots.json";
	/** The only snapshot schema version this build reads. */
	static final int SNAPSHOT_VERSION = 1;
	/** The longest short name ({@link Spot#getShortName}), in characters. */
	static final int MAX_SHORT_NAME_LENGTH = 15;
	/** Prefix of an unlisted region's spot key ({@link #regionKey}). */
	static final String REGION_PREFIX = "region:";
	/** The table when the bundled one could not be read: no spots, and no key is ever given. */
	static final SlayerSpots NONE = new SlayerSpots();

	private final Snapshot snapshot;
	/** Spots by id. */
	private final Map<String, Spot> byId;
	/** The spots listing each region, in table order. */
	private final Map<Integer, List<Spot>> byRegion;
	/** The ids of the spots with each full or short name, by {@link TaskLocation#matchKey}. */
	private final Map<String, Set<String>> byName;
	/** Unmodifiable, in table order. */
	private final List<Spot> spots;

	private SlayerSpots()
	{
		snapshot = null;
		byId = Collections.emptyMap();
		byRegion = Collections.emptyMap();
		byName = Collections.emptyMap();
		spots = Collections.emptyList();
	}

	private SlayerSpots(Snapshot snapshot)
	{
		this.snapshot = snapshot;
		Map<String, Spot> ids = new HashMap<>();
		Map<Integer, List<Spot>> regions = new HashMap<>();
		Map<String, Set<String>> names = new HashMap<>();
		for (Spot spot : snapshot.spots)
		{
			spot.taskKeys = new HashSet<>();
			for (String task : spot.tasks)
			{
				spot.taskKeys.add(TaskStatistics.normalize(task));
			}
			ids.put(spot.id, spot);
			for (int region : spot.regions)
			{
				regions.computeIfAbsent(region, r -> new ArrayList<>()).add(spot);
			}
			for (String name : new String[]{spot.name, spot.shortName})
			{
				names.computeIfAbsent(TaskLocation.matchKey(name), n -> new HashSet<>()).add(spot.id);
			}
		}
		byId = ids;
		byRegion = regions;
		byName = names;
		spots = Collections.unmodifiableList(new ArrayList<>(snapshot.spots));
	}

	/**
	 * Reads and validates the snapshot {@code resource} (relative to this class) with {@code gson}, the client's
	 * injected instance. Returns {@link #NONE}, logged at debug level, when the resource is missing, unreadable,
	 * malformed or invalid, so bad data never breaks the plugin.
	 */
	static SlayerSpots load(Gson gson, String resource)
	{
		if (gson == null)
		{
			return NONE;
		}
		try (InputStream stream = SlayerSpots.class.getResourceAsStream(resource))
		{
			if (stream == null)
			{
				log.debug("Slayer spots {} are missing", resource);
				return NONE;
			}
			return parse(gson, new InputStreamReader(stream, StandardCharsets.UTF_8));
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Unable to load the Slayer spots {}", resource, e);
			return NONE;
		}
	}

	/** Parses and validates a snapshot. Throws a {@link RuntimeException} when it is malformed or invalid. */
	static SlayerSpots parse(Gson gson, Reader reader)
	{
		Snapshot snapshot = gson.fromJson(reader, Snapshot.class);
		validate(snapshot);
		return new SlayerSpots(snapshot);
	}

	/** Whether the table was read; {@code false} for {@link #NONE}. */
	boolean isAvailable()
	{
		return snapshot != null;
	}

	/** Every spot, in table order. Unmodifiable. */
	List<Spot> spots()
	{
		return spots;
	}

	/** The spot with table id {@code id}, or {@code null} (an unlisted region's key, an unknown id, {@code null}). */
	Spot spot(String id)
	{
		return id == null ? null : byId.get(id);
	}

	/** The spots listing {@code region}, in table order. Unmodifiable; empty when it is unlisted. */
	List<Spot> spotsInRegion(int region)
	{
		List<Spot> listed = byRegion.get(region);
		return listed == null ? Collections.emptyList() : Collections.unmodifiableList(listed);
	}

	/** The map region of tile {@code (x, y)}, as {@code WorldPoint.getRegionID()} gives it. */
	static int regionOf(int x, int y)
	{
		return ((x >> 6) << 8) | (y >> 6);
	}

	/** The spot key of unlisted region {@code region}: {@code region:N}. */
	static String regionKey(int region)
	{
		return REGION_PREFIX + region;
	}

	/** The region of an unlisted region's spot key ({@link #regionKey}), or {@code null} for any other key. */
	static Integer keyRegion(String spotKey)
	{
		if (spotKey == null || !spotKey.startsWith(REGION_PREFIX))
		{
			return null;
		}
		try
		{
			int region = Integer.parseInt(spotKey.substring(REGION_PREFIX.length()));
			return LocationTally.isRegion(region) ? region : null;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/**
	 * The spot of a kill at tile {@code (x, y)} on {@code plane} (instances as their template tile), by the table's
	 * matching rule: the spot listing its region, or in a shared region the one whose plane and bounds match; an
	 * unlisted region's key ({@link #regionKey}) when no spot lists it.
	 *
	 * @return the spot key, or {@code null} when no spot of a shared region matches or the table is unavailable
	 */
	String spotAt(int x, int y, int plane)
	{
		if (!isAvailable())
		{
			return null;
		}
		final int region = regionOf(x, y);
		final List<Spot> listed = byRegion.get(region);
		if (listed == null)
		{
			return regionKey(region);
		}
		if (listed.size() == 1)
		{
			return listed.get(0).id;
		}
		for (Spot spot : listed)
		{
			if (spot.matches(x, y, plane))
			{
				return spot.id;
			}
		}
		return null;
	}

	/**
	 * The spot of map region {@code region} when only the region is known (a record saved before spots, or a kill
	 * tallied by region alone), for a task of {@code task}: the spot listing it; in a region shared by several spots the
	 * one shared spot whose tasks include {@code task}; an unlisted region's key ({@link #regionKey}) when no spot lists
	 * it.
	 *
	 * @param region the map region, or {@code null}
	 * @param task   the task's name (matched by {@link TaskStatistics#normalize}), or {@code null}
	 * @return the spot key, or {@code null} (Unknown) for no region, a shared region that none or several of its spots'
	 * tasks decide, or an unavailable table
	 */
	String spotForRegion(Integer region, String task)
	{
		if (region == null || !LocationTally.isRegion(region) || !isAvailable())
		{
			return null;
		}
		final List<Spot> listed = byRegion.get(region);
		if (listed == null)
		{
			return regionKey(region);
		}
		if (listed.size() == 1)
		{
			return listed.get(0).id;
		}
		Spot found = null;
		for (Spot spot : listed)
		{
			if (spot.hasTask(task))
			{
				if (found != null)
				{
					return null;
				}
				found = spot;
			}
		}
		return found == null ? null : found.id;
	}

	/**
	 * A saved record's spot: its stored {@link LedgerRecord#getSpotId() spotId}, or for a record without one (saved
	 * before spots, V2 plan 4.5) the spot of its {@link LedgerRecord#getLocationRegion() locationRegion}
	 * ({@link #spotForRegion}, with its task). A stored id this table doesn't know ({@link #knows}: one a later table
	 * dropped or renamed) is treated as no id, so the record takes its region's spot. Worked out each time it is read;
	 * the file is not changed. While the table is unavailable ({@link #NONE}), a stored id is kept as it is.
	 *
	 * @return the spot key, or {@code null} (Unknown)
	 */
	String recordSpot(LedgerRecord record)
	{
		if (record == null)
		{
			return null;
		}
		final String stored = record.getSpotId() == null ? null : record.getSpotId().trim();
		if (stored != null && !stored.isEmpty() && (!isAvailable() || knows(stored)))
		{
			return stored;
		}
		return spotForRegion(record.getLocationRegion(), record.getMonster());
	}

	/**
	 * Whether {@code spotKey} is a spot of this table: one of its ids, or the key of a region it doesn't list. A
	 * {@code region:N} key of a region a later table lists is not known, so its task moves to that region's spot.
	 */
	boolean knows(String spotKey)
	{
		if (spot(spotKey) != null)
		{
			return true;
		}
		final Integer region = keyRegion(spotKey);
		return region != null && spotsInRegion(region).isEmpty();
	}

	/**
	 * The table's name of spot {@code spotKey} ("Catacombs of Kourend"), or {@code "Region N"} for an unlisted
	 * region's key.
	 *
	 * @return the name, or {@code null} for the Unknown spot or an id the table doesn't have
	 */
	String name(String spotKey)
	{
		final Integer region = keyRegion(spotKey);
		if (region != null)
		{
			return regionName(region);
		}
		final Spot spot = spot(spotKey);
		return spot == null ? null : spot.name;
	}

	/**
	 * The short name of spot {@code spotKey} for narrow places such as the overlay ("Catacombs"), at most
	 * {@link #MAX_SHORT_NAME_LENGTH} characters; {@code "Region N"} for an unlisted region's key.
	 *
	 * @return the short name, or {@code null} for the Unknown spot or an id the table doesn't have
	 */
	String shortName(String spotKey)
	{
		final Integer region = keyRegion(spotKey);
		if (region != null)
		{
			return regionName(region);
		}
		final Spot spot = spot(spotKey);
		return spot == null ? null : spot.shortName;
	}

	/**
	 * The spot the table calls {@code name} (its full or short name, compared as {@link TaskLocation#matchKey}
	 * compares names, so case and spacing don't matter), for a place known only by name, such as Konar's assigned area.
	 *
	 * @return the spot's id, or {@code null} when no spot, or more than one, has that name
	 */
	String spotNamed(String name)
	{
		final Set<String> ids = byName.get(TaskLocation.matchKey(name));
		return ids == null || ids.size() != 1 ? null : ids.iterator().next();
	}

	/** How an unlisted region is named until the player names it: {@code Region 7238}. */
	static String regionName(int region)
	{
		return "Region " + region;
	}

	/** Whether spot {@code spotKey}'s task list includes {@code task}; never for an unlisted region or no spot. */
	boolean listsTask(String spotKey, String task)
	{
		final Spot spot = spot(spotKey);
		return spot != null && spot.hasTask(task);
	}

	/** Snapshot metadata, for attribution. {@code null} for {@link #NONE}. */
	String getRetrieved()
	{
		return snapshot == null ? null : snapshot.retrieved;
	}

	String getSource()
	{
		return snapshot == null ? null : snapshot.source;
	}

	String getLicense()
	{
		return snapshot == null ? null : snapshot.license;
	}

	String getAttribution()
	{
		return snapshot == null ? null : snapshot.attribution;
	}

	String getVerifiedWith()
	{
		return snapshot == null ? null : snapshot.verifiedWith;
	}

	private static void validate(Snapshot snapshot)
	{
		if (snapshot == null || snapshot.version != SNAPSHOT_VERSION || snapshot.spots == null
			|| snapshot.spots.isEmpty() || isBlank(snapshot.source) || isBlank(snapshot.license)
			|| isBlank(snapshot.licenseUrl) || isBlank(snapshot.contributors) || isBlank(snapshot.retrieved)
			|| isBlank(snapshot.attribution) || isBlank(snapshot.matching))
		{
			throw new IllegalStateException("Slayer spots metadata is missing or unsupported.");
		}
		Set<String> ids = new HashSet<>();
		for (Spot spot : snapshot.spots)
		{
			// No colon, so an id is never an unlisted region's key or another kind of key (HistoryFilter.NAME_PREFIX).
			if (spot == null || isBlank(spot.id) || !spot.id.equals(spot.id.trim()) || spot.id.indexOf(':') >= 0
				|| !ids.add(spot.id) || isBlank(spot.name) || isBlank(spot.shortName)
				|| spot.shortName.length() > MAX_SHORT_NAME_LENGTH)
			{
				throw new IllegalStateException("Slayer spots have an invalid or duplicate spot.");
			}
			if (spot.regions == null || spot.regions.isEmpty() || spot.tasks == null || spot.sources == null
				|| spot.sources.isEmpty() || (spot.plane != null && (spot.plane < 0 || spot.plane > 3)))
			{
				throw new IllegalStateException("Slayer spot " + spot.id + " has no regions, tasks or sources.");
			}
			for (Integer region : spot.regions)
			{
				if (region == null || !LocationTally.isRegion(region))
				{
					throw new IllegalStateException("Slayer spot " + spot.id + " has an invalid region.");
				}
			}
			for (String task : spot.tasks)
			{
				if (isBlank(task))
				{
					throw new IllegalStateException("Slayer spot " + spot.id + " has a blank task.");
				}
			}
			if (spot.bounds != null)
			{
				for (int[] box : spot.bounds)
				{
					if (box == null || box.length != 4 || box[0] > box[2] || box[1] > box[3])
					{
						throw new IllegalStateException("Slayer spot " + spot.id + " has an invalid bounds box.");
					}
				}
			}
		}
	}

	private static boolean isBlank(String value)
	{
		return value == null || value.trim().isEmpty();
	}

	private static final class Snapshot
	{
		private int version;
		private String source;
		private String license;
		private String licenseUrl;
		private String contributors;
		private String retrieved;
		private String attribution;
		private String verifiedWith;
		private String matching;
		private List<Spot> spots;
	}

	/** One Slayer spot of the table. Read by Gson; never changed once loaded. */
	static final class Spot
	{
		private String id;
		private String name;
		@SerializedName("short")
		private String shortName;
		private List<Integer> regions;
		/** The plane that decides a shared region, or {@code null}. */
		private Integer plane;
		/** Inclusive {@code [x1, y1, x2, y2]} boxes that decide shared regions, or {@code null}. */
		private List<int[]> bounds;
		private List<String> tasks;
		private List<String> sources;
		private String confidence;
		/** {@link #tasks}, {@link TaskStatistics#normalize}d; set once when the table is built. */
		private transient Set<String> taskKeys;

		/** The stable id, for example {@code catacombs-of-kourend}. */
		String getId()
		{
			return id;
		}

		/** The full name, for example "Catacombs of Kourend". */
		String getName()
		{
			return name;
		}

		/** The short name, at most {@link #MAX_SHORT_NAME_LENGTH} characters, for example "Catacombs". */
		String getShortName()
		{
			return shortName;
		}

		/** The regions, in table order. Unmodifiable. */
		List<Integer> getRegions()
		{
			return Collections.unmodifiableList(regions);
		}

		/** The plane that decides a shared region, or {@code null}. */
		Integer getPlane()
		{
			return plane;
		}

		/** Copies of the bounds boxes, or an empty list when the spot has none. */
		List<int[]> getBounds()
		{
			List<int[]> copy = new ArrayList<>();
			if (bounds != null)
			{
				for (int[] box : bounds)
				{
					copy.add(box.clone());
				}
			}
			return copy;
		}

		/** The task names, as the table spells them. Unmodifiable. */
		List<String> getTasks()
		{
			return Collections.unmodifiableList(tasks);
		}

		/** The source URLs. Unmodifiable. */
		List<String> getSources()
		{
			return Collections.unmodifiableList(sources);
		}

		/** {@code high}, {@code medium} or {@code low}. */
		String getConfidence()
		{
			return confidence;
		}

		/** Whether the task list includes {@code task} (matched by {@link TaskStatistics#normalize}). */
		boolean hasTask(String task)
		{
			final String key = TaskStatistics.normalize(task);
			return key != null && taskKeys.contains(key);
		}

		/** Whether a point in a shared region is this spot's: its plane, if set, and one of its boxes, if any. */
		boolean matches(int x, int y, int pointPlane)
		{
			if (plane != null && plane != pointPlane)
			{
				return false;
			}
			if (bounds == null)
			{
				return true;
			}
			for (int[] box : bounds)
			{
				if (x >= box[0] && x <= box[2] && y >= box[1] && y <= box[3])
				{
					return true;
				}
			}
			return false;
		}
	}
}
