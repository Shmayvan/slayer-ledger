package com.shmayvan.slayerledger;

import java.util.*;
import java.util.function.*;
import lombok.*;

/**
 * The names the shown history needs from the plugin (split from {@link LedgerHistoryModel}): the player's region
 * names and item names, which are asked for and which could not be found, and the nature rune's GE price for the alch
 * hint (it comes with the item names); with the bundled Slayer spot table, the locations they name.
 *
 * <p>Threading: EDT only, like the model that owns it. It only keeps the names and asks for missing ones, each once;
 * the model decides what an arrival means (dropping the statistics, asking for the advisor's plan again) and tells
 * its listener. Callers write through the model.</p>
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class NameBook
{
	/** Asks the plugin for the names of regions (EDT). */
	private final Consumer<Set<Integer>> locationNameRequests;
	/** Asks the plugin for the names of item ids, and the nature rune's price (EDT). */
	private final Consumer<Set<Integer>> itemNameRequests;
	/** Set by {@link #close}: nothing is asked for any more. */
	private boolean closed;

	// Region names
	/**
	 * The player's region names received from the plugin (the active RuneScape profile's), by region id; kept until the
	 * plugin says the profile changed ({@link #clearLocationNames}).
	 */
	private final Map<Integer, String> locationNames = new HashMap<>();
	/** Regions whose names were asked for and have not arrived yet, so they are not asked for twice. */
	private final Set<Integer> locationNamesPending = new HashSet<>();
	/** Regions without a name when last asked; asked again only after the profile changes or one is named. */
	private final Set<Integer> locationNamesMissing = new HashSet<>();
	/** The bundled Slayer spot table; {@link SlayerSpots#NONE} until set. */
	private SlayerSpots spots = SlayerSpots.NONE;

	// Item names
	/** Item names from the plugin, by item id as tallied. */
	private final Map<Integer, String> itemNames = new HashMap<>();
	/** Read-only view of {@link #itemNames}. */
	private final Map<Integer, String> itemNamesView = Collections.unmodifiableMap(itemNames);
	/** Ids asked for whose answer has not arrived yet, so they are not asked for twice. */
	private final Set<Integer> itemNamesPending = new HashSet<>();
	/** Ids the plugin could not name; never asked for again. */
	private final Set<Integer> itemNamesFailed = new HashSet<>();
	/** The nature rune's GE price for the alch hint, or {@code null} while unknown. */
	private Long natureRunePrice;
	/** Whether the nature rune's price was asked for (with the first batch of names). */
	private boolean natureRunePriceAsked;
	/** Bumped whenever item names arrive, so views that show them are worked out again. */
	private long itemNamesVersion;

	/** The plugin stopped: nothing is asked for again. */
	void close()
	{
		closed = true;
	}

	// ---- Region names ----

	/** The player's name for {@code region}, or {@code null} when it has none or it is not known yet. */
	String locationName(Integer region)
	{
		return locationNames.get(region);
	}

	/** Sets the bundled Slayer spot table; {@code null} is {@link SlayerSpots#NONE}. */
	void setSpots(SlayerSpots spots)
	{
		this.spots = spots == null ? SlayerSpots.NONE : spots;
	}

	/** The bundled Slayer spot table; never {@code null}. */
	SlayerSpots spots()
	{
		return spots;
	}

	/**
	 * A saved record's location name as the history shows it ({@link TaskLocation#recordLocation}), with the region
	 * names known so far, or {@code null} when none is known.
	 */
	String recordLocation(LedgerRecord record)
	{
		return TaskLocation.recordLocation(record, this::locationName, spots);
	}

	/** Asks the plugin (once, in one batch) for the names of {@code regions} not known, asked for or missing yet. */
	void requestLocationNames(Set<Integer> regions)
	{
		if (closed)
		{
			return;
		}
		Set<Integer> missing = new HashSet<>();
		for (Integer region : regions)
		{
			if (region != null && LocationTally.isRegion(region) && !locationNames.containsKey(region)
				&& !locationNamesPending.contains(region) && !locationNamesMissing.contains(region))
			{
				missing.add(region);
			}
		}
		if (!missing.isEmpty())
		{
			locationNamesPending.addAll(missing);
			locationNameRequests.accept(missing);
		}
	}

	/**
	 * Adds region names from the plugin ({@code names}, for the regions in {@code requested} that have one; also sent
	 * when the player names one). Requested regions without a name are not asked for again. Returns whether a name
	 * changed.
	 */
	boolean addLocationNames(Set<Integer> requested, Map<Integer, String> names)
	{
		locationNamesPending.removeAll(requested);
		boolean changed = false;
		for (Integer region : requested)
		{
			String name = names.get(region);
			if (name == null)
			{
				locationNamesMissing.add(region);
			}
			else
			{
				locationNamesMissing.remove(region);
				changed |= !name.equals(locationNames.put(region, name));
			}
		}
		return changed;
	}

	/** Forgets every region name (another RuneScape profile is active): they are asked for again as needed. */
	void clearLocationNames()
	{
		locationNames.clear();
		locationNamesPending.clear();
		locationNamesMissing.clear();
	}

	// ---- Item names ----

	/** The name of item {@code id} (as tallied), or {@code null} when it is not known (yet). */
	String itemName(Integer id)
	{
		return itemNames.get(id);
	}

	/**
	 * The item's own name, as Ground Items matches it: {@link #itemName} without the
	 * {@link ClientLookups#NOTED_SUFFIX} a note is shown with (a note's own name is its unnoted item's); {@code null}
	 * when not known (yet).
	 */
	String itemBaseName(Integer id)
	{
		String name = itemNames.get(id);
		return name != null && name.endsWith(ClientLookups.NOTED_SUFFIX)
			? name.substring(0, name.length() - ClientLookups.NOTED_SUFFIX.length()) : name;
	}

	/** Every known item name, by item id; a read-only view. */
	Map<Integer, String> itemNames()
	{
		return itemNamesView;
	}

	/** The nature rune's GE price for the alch hint, or {@code null} while unknown. */
	Long natureRunePrice()
	{
		return natureRunePrice;
	}

	/** Whether the plugin could not name {@code id}. */
	boolean isItemNameFailed(Integer id)
	{
		return itemNamesFailed.contains(id);
	}

	/**
	 * Asks the plugin (once, in one batch) for the names of {@code ids} not known, asked for or failed yet; it looks
	 * them up on the client thread and answers through {@link LedgerHistoryModel#addItemNames}. The nature rune price for the alch hint
	 * comes with the first batch, and is asked for again with later batches while it is unknown.
	 */
	void requestItemNames(Set<Integer> ids)
	{
		if (closed)
		{
			return;
		}
		Set<Integer> missing = new HashSet<>(ids);
		missing.removeAll(itemNames.keySet());
		missing.removeAll(itemNamesPending);
		missing.removeAll(itemNamesFailed);
		if (!missing.isEmpty() || !natureRunePriceAsked)
		{
			natureRunePriceAsked = true;
			itemNamesPending.addAll(missing);
			itemNameRequests.accept(missing);
		}
	}

	/**
	 * Adds item names looked up by the plugin ({@code names}, for the ids in {@code requested} that have one) and the
	 * nature rune's GE price ({@code null} when unavailable); ids without a name are not asked for again. Bumps
	 * {@link #itemNamesVersion}.
	 */
	void addItemNames(Set<Integer> requested, Map<Integer, String> names, Long natureRune)
	{
		if (natureRune != null)
		{
			natureRunePrice = natureRune;
		}
		itemNamesPending.removeAll(requested);
		itemNames.putAll(names);
		for (Integer id : requested)
		{
			if (!names.containsKey(id))
			{
				itemNamesFailed.add(id);
			}
		}
		itemNamesVersion++;
	}

	/** Bumped whenever item names arrive; never goes back. */
	long itemNamesVersion()
	{
		return itemNamesVersion;
	}
}
