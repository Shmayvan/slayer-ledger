package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class ItemValuationTest
{
	private static final int BONES = 526;
	private static final int RUNE_SCIMITAR = 1333;
	/** Fake noted id for RUNE_SCIMITAR. */
	private static final int RUNE_SCIMITAR_NOTED = 1334;
	/** Untradeable: no GE price, but an alchemy value. */
	private static final int UNTRADEABLE = 20_000;
	private static final int UNKNOWN = 30_000;

	/** Fake price source; records which ids were priced. */
	private static class FakeLookup implements ItemValuation.PriceLookup
	{
		final Map<Integer, Integer> noted = new HashMap<>();
		final Map<Integer, Long> ge = new HashMap<>();
		final Map<Integer, Long> ha = new HashMap<>();

		FakeLookup()
		{
			noted.put(RUNE_SCIMITAR_NOTED, RUNE_SCIMITAR);
			ge.put(BONES, 100L);
			ha.put(BONES, 0L);
			ge.put(RUNE_SCIMITAR, 15_000L);
			ha.put(RUNE_SCIMITAR, 15_360L);
			ha.put(UNTRADEABLE, 500L);
			// Coins' own alchemy value is not face value; the helper must override it.
			ge.put(ItemValuation.COINS, 1L);
			ha.put(ItemValuation.COINS, 0L);
		}

		@Override
		public int canonicalize(int itemId)
		{
			return noted.getOrDefault(itemId, itemId);
		}

		@Override
		public long gePrice(int canonicalId)
		{
			if (noted.containsKey(canonicalId))
			{
				throw new AssertionError("priced a noted id");
			}
			return ge.getOrDefault(canonicalId, 0L);
		}

		@Override
		public long haPrice(int canonicalId)
		{
			if (noted.containsKey(canonicalId))
			{
				throw new AssertionError("priced a noted id");
			}
			return ha.getOrDefault(canonicalId, 0L);
		}

		@Override
		public boolean isTradeable(int canonicalId)
		{
			return canonicalId != UNTRADEABLE;
		}
	}

	private static ItemTally tally(int... idQty)
	{
		ItemTally t = new ItemTally();
		for (int i = 0; i < idQty.length; i += 2)
		{
			t.add(idQty[i], idQty[i + 1]);
		}
		return t;
	}

	@Test
	public void totalsGeAndHa()
	{
		ItemTally loot = tally(BONES, 10, RUNE_SCIMITAR, 2);
		ItemValuation.UnitPrices prices = ItemValuation.capturePrices(loot, new FakeLookup());
		assertEquals(Long.valueOf(100), prices.getGe().get(BONES));
		assertEquals(Long.valueOf(15_360), prices.getHa().get(RUNE_SCIMITAR));
		assertEquals(10 * 100 + 2 * 15_000, ItemValuation.total(loot, prices.getGe()));
		assertEquals(2 * 15_360, ItemValuation.total(loot, prices.getHa()));
	}

	@Test
	public void coinsAndPlatinumAreFaceValue()
	{
		ItemTally loot = tally(ItemValuation.COINS, 5_000, ItemValuation.PLATINUM, 3);
		ItemValuation.UnitPrices prices = ItemValuation.capturePrices(loot, new FakeLookup());
		assertEquals(5_000 + 3_000, ItemValuation.total(loot, prices.getGe()));
		assertEquals(5_000 + 3_000, ItemValuation.total(loot, prices.getHa()));
	}

	@Test
	public void notedItemsUseUnnotedPricesButKeepTheirId()
	{
		ItemTally loot = tally(RUNE_SCIMITAR_NOTED, 4);
		ItemValuation.UnitPrices prices = ItemValuation.capturePrices(loot, new FakeLookup());
		assertEquals(Long.valueOf(15_000), prices.getGe().get(RUNE_SCIMITAR_NOTED));
		assertEquals(Long.valueOf(15_360), prices.getHa().get(RUNE_SCIMITAR_NOTED));
		assertEquals(60_000, ItemValuation.total(loot, prices.getGe()));
		assertEquals(61_440, ItemValuation.total(loot, prices.getHa()));
	}

	@Test
	public void untradeableIsZeroButTradeableWithoutPriceIsMissing()
	{
		ItemTally loot = tally(UNTRADEABLE, 2, UNKNOWN, 7, BONES, 1);
		ItemValuation.UnitPrices prices = ItemValuation.capturePrices(loot, new FakeLookup());
		// Untradeable: a known GE value of 0, alchemy value still counts.
		assertEquals(Long.valueOf(0), prices.getGe().get(UNTRADEABLE));
		// Tradeable at 0: the price is unavailable (not loaded, or not in the price list), not worthless.
		assertFalse(prices.getGe().containsKey(UNKNOWN));
		assertTrue(prices.getGeMissing().contains(UNKNOWN));
		assertEquals("its alchemy value is still known", Long.valueOf(0), prices.getHa().get(UNKNOWN));
		assertEquals(1, ItemValuation.countUnpriced(loot, prices.getGe()));
		assertEquals(100, ItemValuation.total(loot, prices.getGe()));
		assertEquals(1_000, ItemValuation.total(loot, prices.getHa()));
		// An id absent from the price map, or a null map, is 0.
		assertEquals(0, ItemValuation.total(tally(99, 5), prices.getGe()));
		assertEquals(0, ItemValuation.total(loot, null));
		assertEquals(0, ItemValuation.total(null, prices.getGe()));
	}

	@Test
	public void negativePricesAndFailingLookupsAreZero()
	{
		ItemValuation.PriceLookup broken = new ItemValuation.PriceLookup()
		{
			@Override
			public int canonicalize(int itemId)
			{
				if (itemId == UNKNOWN)
				{
					throw new IllegalStateException("no definition");
				}
				return itemId;
			}

			@Override
			public long gePrice(int canonicalId)
			{
				return -1;
			}

			@Override
			public long haPrice(int canonicalId)
			{
				return 7;
			}

			@Override
			public boolean isTradeable(int canonicalId)
			{
				return true;
			}
		};
		ItemTally loot = tally(UNKNOWN, 1, BONES, 2);
		ItemValuation.UnitPrices prices = ItemValuation.capturePrices(loot, broken);
		// A negative GE price of a tradeable item and a failing definition lookup are both unavailable.
		assertNull(prices.getGe().get(BONES));
		assertNull(prices.getGe().get(UNKNOWN));
		assertNull("a failed lookup has no alchemy value either", prices.getHa().get(UNKNOWN));
		assertEquals(Long.valueOf(7), prices.getHa().get(BONES));
		assertEquals(2, ItemValuation.countUnpriced(loot, prices.getGe()));
		assertEquals(0, ItemValuation.total(loot, prices.getGe()));
		assertEquals(14, ItemValuation.total(loot, prices.getHa()));
	}

	@Test
	public void totalSaturatesOnOverflow()
	{
		ItemTally loot = tally(BONES, Integer.MAX_VALUE);
		Map<Integer, Long> prices = new HashMap<>();
		prices.put(BONES, Long.MAX_VALUE / 2);
		assertEquals(Long.MAX_VALUE, ItemValuation.total(loot, prices));
	}

	/** Prices not loaded yet: ItemManager returns 0 for every item. */
	private static final class UnloadedLookup extends FakeLookup
	{
		@Override
		public long gePrice(int canonicalId)
		{
			return 0;
		}
	}

	@Test
	public void unloadedPricesAreMissingButCoinsAndPlatinumAreKnown()
	{
		ItemTally loot = tally(BONES, 3, RUNE_SCIMITAR, 1, ItemValuation.COINS, 500, ItemValuation.PLATINUM, 2);
		ItemValuation.UnitPrices prices = ItemValuation.capturePrices(loot, new UnloadedLookup());
		assertEquals(2, prices.getGe().size());
		assertEquals(Long.valueOf(1), prices.getGe().get(ItemValuation.COINS));
		assertEquals(Long.valueOf(ItemValuation.PLATINUM_VALUE), prices.getGe().get(ItemValuation.PLATINUM));
		assertTrue(prices.getGeMissing().contains(BONES));
		assertTrue(prices.getGeMissing().contains(RUNE_SCIMITAR));
		assertEquals("totals sum the known prices", 500 + 2_000, ItemValuation.total(loot, prices.getGe()));
		assertEquals(2, ItemValuation.countUnpriced(loot, prices.getGe()));
		// High alchemy comes from the item definition, which is available.
		assertEquals(Long.valueOf(15_360), prices.getHa().get(RUNE_SCIMITAR));
	}

	@Test
	public void recapturingReplacesAMissingPrice()
	{
		ItemTally loot = tally(BONES, 3);
		ItemValuation.UnitPrices prices = ItemValuation.capturePrices(loot, new UnloadedLookup());
		assertTrue(prices.getGeMissing().contains(BONES));
		ItemValuation.capturePrices(loot, new FakeLookup(), prices);
		assertEquals(Long.valueOf(100), prices.getGe().get(BONES));
		assertTrue(prices.getGeMissing().isEmpty());
		// And the other way: a price that becomes unavailable is removed, never left stale.
		ItemValuation.capturePrices(loot, new UnloadedLookup(), prices);
		assertFalse(prices.getGe().containsKey(BONES));
		assertTrue(prices.getGeMissing().contains(BONES));
	}

	@Test
	public void countUnpricedHandlesNulls()
	{
		assertEquals(0, ItemValuation.countUnpriced(null, new HashMap<>()));
		assertEquals(0, ItemValuation.countUnpriced(new ItemTally(), null));
		assertEquals(2, ItemValuation.countUnpriced(tally(BONES, 1, RUNE_SCIMITAR, 1), null));
	}
}
