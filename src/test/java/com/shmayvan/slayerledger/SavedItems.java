package com.shmayvan.slayerledger;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;

/**
 * Saved-record fixtures for tests: gives a record built from totals the items those totals stand for, so it is
 * valued at today's prices like the tasks the plugin saves, and a {@code ~} only ever means a price that is really
 * unavailable. The loot is coins (always 1 gp, in {@link PluginHarness} too, without a definition); the supplies are
 * manta rays at {@link #SUPPLY_PRICE} gp, which a harness test defines with {@link #define}.
 */
final class SavedItems
{
	/** The loot item: coins, worth 1 gp each at GE and high alch. */
	static final int LOOT = ItemValuation.COINS;
	/** The supply item, worth {@link #SUPPLY_PRICE} gp at GE today. */
	static final int SUPPLY = ItemID.MANTARAY;
	static final long SUPPLY_PRICE = 50;

	private SavedItems()
	{
	}

	/** Defines the supply item in {@code harness} at {@link #SUPPLY_PRICE} gp. */
	static void define(PluginHarness harness)
	{
		harness.defineItem(SUPPLY, "Manta ray", SUPPLY_PRICE, 0);
	}

	/** Today's GE prices of the fixture items, for valuations worked out without the plugin. */
	static Map<Integer, Long> todaysPrices()
	{
		Map<Integer, Long> prices = new HashMap<>();
		prices.put(LOOT, 1L);
		prices.put(SUPPLY, SUPPLY_PRICE);
		return prices;
	}

	/**
	 * Gives {@code record} loot and supply items worth its saved loot and supplies GE totals at today's prices, and
	 * saves their unit prices with it; its high alch totals are kept as they are. A supplies total must be a whole
	 * number of {@link #SUPPLY_PRICE}. Returns {@code record}.
	 */
	static LedgerRecord withItems(LedgerRecord record)
	{
		long loot = record.getLootGeValue() == null ? 0 : record.getLootGeValue();
		long supplies = record.getSuppliesGeValue() == null ? 0 : record.getSuppliesGeValue();
		if (supplies % SUPPLY_PRICE != 0)
		{
			throw new IllegalArgumentException("supplies of " + supplies + " gp are not a whole number of items");
		}
		Map<Integer, Long> lootItems = new HashMap<>();
		if (loot > 0)
		{
			lootItems.put(LOOT, loot);
		}
		record.setLoot(lootItems);
		if (supplies > 0)
		{
			Map<Integer, Long> supplyItems = new HashMap<>();
			supplyItems.put(SUPPLY, supplies / SUPPLY_PRICE);
			record.setSupplies(supplyItems);
		}
		record.setUnitGePrices(todaysPrices());
		Map<Integer, Long> ha = new HashMap<>();
		ha.put(LOOT, 1L);
		ha.put(SUPPLY, 0L);
		record.setUnitHaPrices(ha);
		return record;
	}
}
