package com.shmayvan.slayerledger;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Pure item valuation: captures per-item unit prices through a {@link PriceLookup} and totals a tally against them.
 * No RuneLite dependencies; the plugin supplies a lookup backed by {@code ItemManager} on the client thread.
 *
 * <p>Rules (matching how core RuneLite values loot in 1.13.3):</p>
 * <ul>
 *     <li>Coins are valued at face value (1 gp each) for both GE and high alchemy, and platinum tokens at 1,000 gp.
 *     {@code ItemManager#getItemPrice} already does this for GE; it is applied here for high alchemy too, because the
 *     coin item's own high-alchemy value is not its face value.</li>
 *     <li>Prices are looked up by the canonical id ({@link PriceLookup#canonicalize}, which un-notes noted items), so
 *     a noted drop is valued like the unnoted item. Unit prices are keyed by the id as tallied.</li>
 *     <li>A GE price is <em>unavailable</em> when the lookup throws, or when a tradeable item's GE price is 0 or
 *     less. {@code ItemManager} (1.13.3) has no "prices loaded" flag: until its price list has been fetched, and for
 *     an item missing from it, {@code getItemPrice} simply returns 0, the same as for an untradeable item. So a
 *     tradeable item at 0 is treated as unavailable rather than worthless. An unavailable GE price is left out of
 *     {@link UnitPrices#getGe()} (and recorded in {@link UnitPrices#getGeMissing()}), so it can be told apart from a
 *     real 0. An untradeable item with no GE price is a known 0.</li>
 *     <li>A high-alchemy value of 0 or less is a known 0; it is unavailable only when the lookup throws.</li>
 *     <li>Totals sum the known prices; {@link #countUnpriced} tells how many items of a tally were left out.</li>
 * </ul>
 */
final class ItemValuation
{
	/** {@code net.runelite.api.gameval.ItemID.COINS} (1.13.3). */
	static final int COINS = 995;
	/** {@code net.runelite.api.gameval.ItemID.PLATINUM} (1.13.3). */
	static final int PLATINUM = 13204;
	static final long PLATINUM_VALUE = 1000L;

	/**
	 * Price source. Implementations may only be valid on a particular thread (the plugin's runs on the client thread).
	 */
	interface PriceLookup
	{
		/** The id to price: the unnoted id for a noted item, otherwise the id itself. */
		int canonicalize(int itemId);

		/** Grand Exchange unit price of a canonical id, or 0 / negative when unknown. */
		long gePrice(int canonicalId);

		/**
		 * Whether a canonical id can be traded on the Grand Exchange; a tradeable item without a GE price has an
		 * unavailable price rather than a value of 0.
		 */
		boolean isTradeable(int canonicalId);

		/** High-alchemy unit value of a canonical id, or 0 / negative when unknown. */
		long haPrice(int canonicalId);
	}

	/** Unit prices for a set of items, keyed by the id as tallied. */
	static final class UnitPrices
	{
		private final Map<Integer, Long> ge = new TreeMap<>();
		private final Map<Integer, Long> ha = new TreeMap<>();
		private final Set<Integer> geMissing = new TreeSet<>();

		/** GE unit prices of the ids whose price was available. */
		Map<Integer, Long> getGe()
		{
			return ge;
		}

		/** High-alchemy unit values of the ids whose value was available. */
		Map<Integer, Long> getHa()
		{
			return ha;
		}

		/** Ids looked up whose GE price was unavailable (absent from {@link #getGe()}). */
		Set<Integer> getGeMissing()
		{
			return geMissing;
		}
	}

	private ItemValuation()
	{
	}

	/**
	 * Adds the GE and high-alchemy unit price of every item in the tally to {@code into}. Items already present are
	 * looked up again; an item whose GE price is unavailable (see the class rules) is removed from the GE prices and
	 * added to {@link UnitPrices#getGeMissing()}, and likewise for a high-alchemy lookup that throws.
	 */
	static void capturePrices(ItemTally tally, PriceLookup lookup, UnitPrices into)
	{
		if (tally == null)
		{
			return;
		}
		for (int id : tally.itemIds())
		{
			capturePrice(id, lookup, into);
		}
	}

	/** Looks up one id's prices into {@code into}, as {@link #capturePrices(ItemTally, PriceLookup, UnitPrices)}. */
	static void capturePrice(int id, PriceLookup lookup, UnitPrices into)
	{
		Long ge = null;
		Long ha = null;
		try
		{
			int canonical = lookup.canonicalize(id);
			if (canonical == COINS)
			{
				ge = 1L;
				ha = 1L;
			}
			else if (canonical == PLATINUM)
			{
				ge = PLATINUM_VALUE;
				ha = PLATINUM_VALUE;
			}
			else
			{
				ge = gePrice(canonical, lookup);
				ha = haPrice(canonical, lookup);
			}
		}
		catch (RuntimeException e)
		{
			// Unknown item definition; both prices are unavailable.
		}
		if (ge != null)
		{
			into.getGe().put(id, ge);
			into.getGeMissing().remove(id);
		}
		else
		{
			into.getGe().remove(id);
			into.getGeMissing().add(id);
		}
		if (ha != null)
		{
			into.getHa().put(id, ha);
		}
		else
		{
			into.getHa().remove(id);
		}
	}

	/** The GE price, 0 for an untradeable item without one, or {@code null} when unavailable. */
	private static Long gePrice(int canonical, PriceLookup lookup)
	{
		try
		{
			long price = lookup.gePrice(canonical);
			if (price > 0)
			{
				return price;
			}
			return lookup.isTradeable(canonical) ? null : 0L;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/** The high-alchemy value (at least 0), or {@code null} when the lookup fails. */
	private static Long haPrice(int canonical, PriceLookup lookup)
	{
		try
		{
			return Math.max(0, lookup.haPrice(canonical));
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/**
	 * Number of distinct items in {@code tally} without an entry in {@code unitPrices} (their price was unavailable),
	 * or 0 when the tally is empty. Every item counts as unpriced when {@code unitPrices} is {@code null}.
	 */
	static int countUnpriced(ItemTally tally, Map<Integer, Long> unitPrices)
	{
		if (tally == null)
		{
			return 0;
		}
		int count = 0;
		for (int id : tally.itemIds())
		{
			if (unitPrices == null || !unitPrices.containsKey(id))
			{
				count++;
			}
		}
		return count;
	}

	/** Captures unit prices for one tally. */
	static UnitPrices capturePrices(ItemTally tally, PriceLookup lookup)
	{
		UnitPrices prices = new UnitPrices();
		capturePrices(tally, lookup, prices);
		return prices;
	}

	/**
	 * Total value of a tally at the given unit prices. Items without a price are left out (count as 0); the total
	 * saturates at {@link Long#MAX_VALUE}.
	 */
	static long total(ItemTally tally, Map<Integer, Long> unitPrices)
	{
		return total(tally, unitPrices, null);
	}

	/**
	 * Total value of a tally whose quantities are in doses for some items: for an id with an entry {@code n} in
	 * {@code dosesPerItem}, the quantity counts doses and each is worth {@code unitPrice / n} (the id's price is for one
	 * {@code n}-dose item). The per-item product is rounded to the nearest gp. Other items are valued as in
	 * {@link #total(ItemTally, Map)}.
	 */
	static long total(ItemTally tally, Map<Integer, Long> unitPrices, Map<Integer, Integer> dosesPerItem)
	{
		if (tally == null || unitPrices == null)
		{
			return 0;
		}
		long total = 0;
		for (int id : tally.itemIds())
		{
			Long price = unitPrices.get(id);
			if (price == null || price <= 0)
			{
				continue;
			}
			Integer doses = dosesPerItem == null ? null : dosesPerItem.get(id);
			try
			{
				long value = Math.multiplyExact(price, tally.get(id));
				if (doses != null && doses > 1)
				{
					value = value / doses + (value % doses * 2 >= doses ? 1 : 0);
				}
				total = Math.addExact(total, value);
			}
			catch (ArithmeticException e)
			{
				return Long.MAX_VALUE;
			}
		}
		return total;
	}
}
