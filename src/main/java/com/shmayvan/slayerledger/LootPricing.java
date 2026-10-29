package com.shmayvan.slayerledger;

import java.util.Map;
import java.util.TreeMap;

/**
 * Pure loot valuation in a {@link LootPriceMode}, from per-item GE and high-alchemy unit prices (keyed by the id as
 * tallied, as in {@link ItemValuation.UnitPrices}). No RuneLite dependencies.
 *
 * <p>Rules:</p>
 * <ul>
 *     <li>An item whose GE unit price is exactly 0 (untradeable, see {@link ItemValuation}) is valued at its
 *     high-alchemy unit value in every mode (owner decision, 2026-10-25). It has a known price, so it is not
 *     unpriced. An item <em>without</em> a GE price (unavailable) is not affected by this rule.</li>
 *     <li>{@link LootPriceMode#GE GE}: the GE unit price; an item without one is unpriced (left out, counted).</li>
 *     <li>{@link LootPriceMode#HIGH_ALCH High alch}: the high-alchemy unit value; an item without one is unpriced.</li>
 *     <li>Quantities in doses or bites are valued per dose ({@link ItemValuation#total(ItemTally, Map, Map)}).</li>
 * </ul>
 */
final class LootPricing
{
	private LootPricing()
	{
	}

	/**
	 * The unit price of every id in {@code ge} or {@code ha} in {@code mode}, leaving out ids without one. Either map
	 * may be {@code null} (no prices of that kind).
	 */
	static Map<Integer, Long> unitPrices(LootPriceMode mode, Map<Integer, Long> ge, Map<Integer, Long> ha)
	{
		Map<Integer, Long> result = new TreeMap<>();
		switch (mode == null ? LootPriceMode.GE : mode)
		{
			case HIGH_ALCH:
				putAll(result, ha);
				break;
			default:
				result.putAll(geUnitPrices(ge, ha));
				break;
		}
		return result;
	}

	/**
	 * The GE unit price of every id in {@code ge}, with a price of exactly 0 replaced by the id's high-alchemy value
	 * from {@code ha} when it has one (see the class rules). Ids without a GE price are left out. Either map may be
	 * {@code null}.
	 */
	static Map<Integer, Long> geUnitPrices(Map<Integer, Long> ge, Map<Integer, Long> ha)
	{
		Map<Integer, Long> result = new TreeMap<>();
		putAll(result, ge);
		if (ha != null)
		{
			for (Map.Entry<Integer, Long> e : result.entrySet())
			{
				Long alch = ha.get(e.getKey());
				if (e.getValue() == 0 && alch != null && alch > 0)
				{
					e.setValue(alch);
				}
			}
		}
		return result;
	}

	private static void putAll(Map<Integer, Long> into, Map<Integer, Long> from)
	{
		if (from == null)
		{
			return;
		}
		for (Map.Entry<Integer, Long> e : from.entrySet())
		{
			if (e.getKey() != null && e.getValue() != null)
			{
				into.put(e.getKey(), e.getValue());
			}
		}
	}

	/** Total value of {@code tally} in {@code mode}; see the class rules. Saturates at {@link Long#MAX_VALUE}. */
	static long total(ItemTally tally, LootPriceMode mode, Map<Integer, Long> ge, Map<Integer, Long> ha,
		Map<Integer, Integer> dosesPerItem)
	{
		return ItemValuation.total(tally, unitPrices(mode, ge, ha), dosesPerItem);
	}

	/**
	 * Distinct items of {@code tally} whose value in {@code mode} is unknown: without the mode's price. An item with a
	 * GE price of 0 has a known price in GE mode, so it is not counted.
	 */
	static int countUnpriced(ItemTally tally, LootPriceMode mode, Map<Integer, Long> ge, Map<Integer, Long> ha)
	{
		return ItemValuation.countUnpriced(tally, mode == LootPriceMode.HIGH_ALCH ? ha : ge);
	}
}
