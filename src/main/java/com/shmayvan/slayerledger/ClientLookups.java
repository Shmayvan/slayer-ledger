package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;

/**
 * The plugin's client-thread lookup services: today's GE prices for the history ({@link PriceRetry} included),
 * item names for the panel's item lists, and the player's names for map regions, with their caches; plus the
 * {@code ItemManager} adapters the plugin prices and filters loot with.
 *
 * <p>Threading is the plugin's: the {@code request...} methods are called on the EDT (by the {@link LedgerHistoryModel}'s
 * callbacks) and only queue one client-thread task each; {@code ItemManager} and {@code ConfigManager} are only read on
 * the client thread; answers go to the history model on the EDT ({@link SwingUtilities#invokeLater}). Everything else is
 * client thread only. The caches live as long as the plugin instance and are cleared by {@link #reset()}, which the
 * plugin calls in its startUp client-thread block.
 *
 * <p>Injected by Guice into the plugin, as RuneLite plugins inject their overlays and helpers.
 */
@Slf4j
class ClientLookups
{
	private final ClientThread clientThread;
	private final ItemManager itemManager;
	private final ConfigManager configManager;

	/** Added to a noted item's name in the panel's item names ({@link #requestItemNames}). */
	static final String NOTED_SUFFIX = " (noted)";

	/** The plugin's current history model (or {@code null} while stopped), read when an answer is ready. */
	private volatile Supplier<LedgerHistoryModel> historyModel = () -> null;

	/**
	 * Today's GE unit prices the history is valued at, by item id as tallied, looked up at most once per item
	 * while the plugin runs (unavailable prices are not kept, so they are tried again). Client thread only.
	 */
	private final Map<Integer, Long> currentGePrices = new HashMap<>();
	/** When to look up again the today's prices that were unavailable. Client thread only. */
	private final PriceRetry currentPriceRetry = new PriceRetry();
	/**
	 * Whether {@code ItemManager} has returned a positive GE price in this run of the plugin, so its price list is
	 * loaded (it loads all prices at once). Until then a tradeable item at 0 may only be waiting for the list, and the
	 * history waits for it to be looked up again; afterwards it is really unpriced. Coins and platinum tokens don't
	 * count: their values are fixed. Client thread only; cleared by {@link #reset()}.
	 */
	private boolean priceListSeen;
	/**
	 * Item names for the panel's item lists, by item id, looked up at most once per item while the plugin runs (a
	 * failed lookup is not kept). Client thread only.
	 */
	private final Map<Integer, String> itemNames = new HashMap<>();
	/**
	 * The player's region names read from (or saved to) the RSProfile config
	 * ({@link SlayerLedgerPlugin#LOCATION_NAME_KEY_PREFIX}), by region id; only named regions. Kept until another
	 * account logs in or another RuneScape profile is active, so a session saved while logging out (when the config has
	 * no profile) still gets its name. Client thread only.
	 */
	private final Map<Integer, String> locationNames = new HashMap<>();

	/**
	 * Client thread only. Prices items the way core LootTrackerPlugin does (1.13.3): GE via
	 * {@link ItemManager#getItemPrice} (which already un-notes, values coins at 1 and untradeables at 0) and high
	 * alchemy via {@code ItemComposition#getHaPrice()}. Ids are canonicalized first so noted items use the unnoted
	 * item's high-alchemy value too.
	 */
	private final ItemValuation.PriceLookup priceLookup = new ItemValuation.PriceLookup()
	{
		@Override
		public int canonicalize(int itemId)
		{
			return itemManager.canonicalize(itemId);
		}

		@Override
		public long gePrice(int canonicalId)
		{
			final long price = itemManager.getItemPrice(canonicalId);
			if (price > 0)
			{
				priceListSeen = true;
			}
			return price;
		}

		@Override
		public long haPrice(int canonicalId)
		{
			return itemManager.getItemComposition(canonicalId).getHaPrice();
		}

		/**
		 * ItemManager (1.13.3) has no "prices loaded" flag: getItemPrice returns 0 both before its price list is
		 * fetched and for an item missing from it. A tradeable item at 0 is therefore an unavailable price, see
		 * {@link ItemValuation}.
		 */
		@Override
		public boolean isTradeable(int canonicalId)
		{
			return itemManager.getItemComposition(canonicalId).isTradeable();
		}
	};

	/** Client thread. Item facts for {@link LootFilter}, from the item compositions. */
	private final LootFilter.ItemNames lootItemNames = new LootFilter.ItemNames()
	{
		@Override
		public int unnoted(int itemId)
		{
			final ItemComposition comp = itemManager.getItemComposition(itemId);
			return comp.getNote() != -1 ? comp.getLinkedNoteId() : itemId;
		}

		@Override
		public String name(int itemId)
		{
			return itemManager.getItemComposition(itemId).getName();
		}
	};

	@Inject
	ClientLookups(ClientThread clientThread, ItemManager itemManager, ConfigManager configManager)
	{
		this.clientThread = clientThread;
		this.itemManager = itemManager;
		this.configManager = configManager;
	}

	/** Where answers go: the plugin's current history model, or {@code null} while it has none. Any thread. */
	void setHistoryModelSource(Supplier<LedgerHistoryModel> source)
	{
		historyModel = source;
	}

	/** The {@code ItemManager} price lookup for saved records, live values and notable drops; client thread. */
	ItemValuation.PriceLookup priceLookup()
	{
		return priceLookup;
	}

	/** The {@code ItemManager} item facts for {@link LootFilter} and notable-drop names; client thread. */
	LootFilter.ItemNames lootItemNames()
	{
		return lootItemNames;
	}

	/**
	 * Client thread, in the plugin's startUp block. Today's prices are looked up afresh in each run of the plugin, and
	 * so are item and location names.
	 */
	void reset()
	{
		currentGePrices.clear();
		currentPriceRetry.clear();
		priceListSeen = false;
		itemNames.clear();
		locationNames.clear();
	}

	/**
	 * EDT. Looks up today's GE price of {@code ids} (item ids as tallied) to value the history at, in one
	 * client-thread task, and sends the available ones to the history model on the EDT. Items already priced this run
	 * are not looked up again.
	 */
	void requestCurrentPrices(Set<Integer> ids)
	{
		final Set<Integer> requested = new TreeSet<>(ids);
		clientThread.invoke(() -> lookUpCurrentPrices(requested));
	}

	/**
	 * Client thread. Prices the items of {@code requested} not cached yet through {@code ItemManager} (the same rules
	 * as saved records, see {@link ItemValuation}) and always answers the history model; a price that is unavailable
	 * (for example not loaded yet) is left out, so the history marks it with {@code ~}, and is tried again a few times
	 * on later ticks ({@link PriceRetry}, {@link #retryCurrentPrices}) and on a later request. Until RuneLite's price
	 * list is seen loaded ({@link #priceListSeen}), the model is told which ids are tried again, so a history waiting
	 * for its prices waits for those too (within its time limit); once it is loaded, a missing price is really
	 * unavailable and holds nothing back.
	 */
	private void lookUpCurrentPrices(Set<Integer> requested)
	{
		final Map<Integer, Long> prices = priceCurrent(requested);
		final Set<Integer> missing = new TreeSet<>(requested);
		missing.removeAll(prices.keySet());
		currentPriceRetry.found(prices.keySet());
		currentPriceRetry.missed(missing);
		final Set<Integer> retrying = priceListSeen ? Collections.emptySet() : missing;
		final LedgerHistoryModel m = historyModel.get();
		if (m != null)
		{
			SwingUtilities.invokeLater(() -> m.addCurrentPrices(requested, prices, retrying));
		}
	}

	/**
	 * Client thread, on each game tick. Looks up again the today's prices that came back unavailable, when
	 * {@link PriceRetry} says one is due, and sends the history model the ones found now (nothing when none was found,
	 * so the history is not redrawn for nothing). The model never asked for these, so it is not waiting for them.
	 */
	void retryCurrentPrices()
	{
		final Set<Integer> due = currentPriceRetry.tick();
		if (due.isEmpty())
		{
			return;
		}
		final Map<Integer, Long> prices = priceCurrent(due);
		currentPriceRetry.found(prices.keySet());
		final LedgerHistoryModel m = historyModel.get();
		if (m != null && !prices.isEmpty())
		{
			final Set<Integer> found = new TreeSet<>(prices.keySet());
			SwingUtilities.invokeLater(() -> m.addCurrentPrices(found, prices));
		}
	}

	/**
	 * Client thread. Today's GE prices of the ids of {@code ids} that have one, looking up those not cached yet and
	 * caching the available ones for the plugin's run.
	 */
	private Map<Integer, Long> priceCurrent(Set<Integer> ids)
	{
		final ItemValuation.UnitPrices looked = new ItemValuation.UnitPrices();
		for (int id : ids)
		{
			if (!currentGePrices.containsKey(id))
			{
				ItemValuation.capturePrice(id, priceLookup, looked);
			}
		}
		currentGePrices.putAll(looked.getGe());
		final Map<Integer, Long> prices = new TreeMap<>();
		for (int id : ids)
		{
			final Long price = currentGePrices.get(id);
			if (price != null)
			{
				prices.put(id, price);
			}
		}
		return prices;
	}

	/**
	 * EDT. Looks up the names of {@code ids} (item ids as tallied) for the panel's item lists, in one client-thread
	 * task, and sends them to the history model on the EDT. Names are never looked up on the EDT.
	 */
	void requestItemNames(Set<Integer> ids)
	{
		final Set<Integer> requested = new TreeSet<>(ids);
		clientThread.invoke(() -> lookUpItemNames(requested));
	}

	/**
	 * Client thread. Names the items of {@code requested} through {@code ItemManager}, caching them for the plugin's
	 * run; a noted item is named "&lt;name&gt; (noted)". An item whose lookup fails is left out (the panel shows its
	 * id).
	 */
	private void lookUpItemNames(Set<Integer> requested)
	{
		final Map<Integer, String> names = new TreeMap<>();
		for (int id : requested)
		{
			String name = itemNames.get(id);
			if (name == null)
			{
				name = lookUpItemName(id);
				if (name != null)
				{
					itemNames.put(id, name);
				}
			}
			if (name != null)
			{
				names.put(id, name);
			}
		}
		final LedgerHistoryModel m = historyModel.get();
		if (m != null)
		{
			// The alch hint subtracts the nature rune a cast uses; 0 means its price is unavailable.
			final long natureRunePrice = itemManager.getItemPrice(ItemID.NATURERUNE);
			final Long natureRune = natureRunePrice > 0 ? natureRunePrice : null;
			SwingUtilities.invokeLater(() -> m.addItemNames(requested, names, natureRune));
		}
	}

	/** Client thread. One item's name, or {@code null} when it has none or the lookup fails. */
	private String lookUpItemName(int itemId)
	{
		try
		{
			final ItemComposition comp = itemManager.getItemComposition(itemId);
			final String name = comp.getName();
			if (name == null || name.trim().isEmpty() || "null".equalsIgnoreCase(name.trim()))
			{
				return null;
			}
			return comp.getNote() != -1 ? name + NOTED_SUFFIX : name;
		}
		catch (RuntimeException e)
		{
			log.debug("Unable to look up the name of item {}", itemId, e);
			return null;
		}
	}

	/**
	 * Client thread. The player's name for map region {@code region} in the active RuneScape profile, or {@code null}
	 * when it has none.
	 */
	String locationName(int region)
	{
		final String known = locationNames.get(region);
		if (known != null || !LocationTally.isRegion(region))
		{
			return known;
		}
		final String saved = TaskLocation.normalizeName(configManager.getRSProfileConfiguration(
			SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX + region));
		if (saved != null)
		{
			locationNames.put(region, saved);
		}
		return saved;
	}

	/**
	 * EDT. Looks up the player's names for {@code regions} in one client-thread task and sends them to the history
	 * model on the EDT; a region without a name is left out.
	 */
	void requestLocationNames(Set<Integer> regions)
	{
		final Set<Integer> requested = new TreeSet<>(regions);
		clientThread.invoke(() ->
		{
			final Map<Integer, String> names = new TreeMap<>();
			for (int region : requested)
			{
				final String name = locationName(region);
				if (name != null)
				{
					names.put(region, name);
				}
			}
			final LedgerHistoryModel m = historyModel.get();
			if (m != null)
			{
				SwingUtilities.invokeLater(() -> m.addLocationNames(requested, names));
			}
		});
	}

	/**
	 * Client thread. Saves {@code normalized} as the player's name for {@code region} in the RSProfile config and the
	 * cache. The config is written first: the write may create the profile, and the profile change it posts clears the
	 * cache.
	 */
	void saveLocationName(int region, String normalized)
	{
		configManager.setRSProfileConfiguration(SlayerLedgerConfig.GROUP,
			SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX + region, normalized);
		locationNames.put(region, normalized);
	}

	/**
	 * Client thread. Whether the player chose Skip for map region {@code region}'s name in the active RuneScape profile
	 *; {@code false} without a profile.
	 */
	boolean isLocationSkipped(int region)
	{
		return LocationTally.isRegion(region) && Boolean.parseBoolean(configManager.getRSProfileConfiguration(
			SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.LOCATION_SKIP_KEY_PREFIX + region));
	}

	/**
	 * Client thread. Keeps the player's Skip for {@code region} in the active RuneScape profile's config, so it is not
	 * asked about again after a restart. The write may create the profile, as for {@link #saveLocationName}.
	 */
	void saveLocationSkip(int region)
	{
		configManager.setRSProfileConfiguration(SlayerLedgerConfig.GROUP,
			SlayerLedgerPlugin.LOCATION_SKIP_KEY_PREFIX + region, true);
	}

	/**
	 * Client thread. Removes every skipped region from the active RuneScape profile's config.
	 *
	 * @return whether there was a profile to clear
	 */
	boolean clearLocationSkips()
	{
		final String profile = configManager.getRSProfileKey();
		if (profile == null)
		{
			return false;
		}
		final List<String> keys = configManager.getRSProfileConfigurationKeys(SlayerLedgerConfig.GROUP, profile,
			SlayerLedgerPlugin.LOCATION_SKIP_KEY_PREFIX);
		if (keys != null)
		{
			for (String key : keys)
			{
				configManager.unsetRSProfileConfiguration(SlayerLedgerConfig.GROUP, key);
			}
		}
		return true;
	}

	/** Client thread. Another account or RuneScape profile is active: its region names are read afresh. */
	void forgetLocationNames()
	{
		locationNames.clear();
	}

	/**
	 * Client thread. Every name the player gave a region in the active RuneScape profile (the prompt suggests them, as
	 * a place can span several regions); empty without a profile.
	 */
	List<String> profileLocationNames()
	{
		final String prefix = SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX;
		final List<String> names = new ArrayList<>();
		final List<String> keys = configManager.getRSProfileConfigurationKeys(SlayerLedgerConfig.GROUP,
			configManager.getRSProfileKey(), prefix);
		if (keys == null)
		{
			return names;
		}
		for (String key : keys)
		{
			try
			{
				final String name = locationName(Integer.parseInt(key.substring(prefix.length())));
				if (name != null)
				{
					names.add(name);
				}
			}
			catch (RuntimeException e)
			{
				log.debug("Ignoring a location name key that is not a region: {}", key);
			}
		}
		return names;
	}
}
