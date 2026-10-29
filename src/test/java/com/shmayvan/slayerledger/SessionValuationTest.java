package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Live session valuation for the panel: totals, per-dose potions, unit-price caching, retries and resets.
 */
public class SessionValuationTest
{
	private static final int BONES = 526;
	private static final int SHARK = 385;
	private static final int PRAYER_4 = 2434;

	/** Counts GE lookups per id; prices can be changed or made unavailable (0) between calls. */
	private static final class CountingLookup implements ItemValuation.PriceLookup
	{
		final Map<Integer, Long> ge = new HashMap<>();
		final Map<Integer, Integer> lookups = new HashMap<>();

		CountingLookup()
		{
			ge.put(BONES, 100L);
			ge.put(SHARK, 1_000L);
			ge.put(PRAYER_4, 8_000L);
		}

		@Override
		public int canonicalize(int itemId)
		{
			return itemId;
		}

		@Override
		public long gePrice(int canonicalId)
		{
			lookups.merge(canonicalId, 1, Integer::sum);
			return ge.getOrDefault(canonicalId, 0L);
		}

		@Override
		public long haPrice(int canonicalId)
		{
			return canonicalId == SHARK ? 180 : 0;
		}

		@Override
		public boolean isTradeable(int canonicalId)
		{
			return true;
		}

		int lookups(int id)
		{
			return lookups.getOrDefault(id, 0);
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

	private final SessionValuation valuation = new SessionValuation();
	private final CountingLookup lookup = new CountingLookup();

	@Test
	public void valuesLootSuppliesAndProfitWithPotionsPerDose()
	{
		SessionValuation.Values v = valuation.update("s1", tally(BONES, 10, ItemValuation.COINS, 500),
			tally(SHARK, 3, PRAYER_4, 5), Collections.singletonMap(PRAYER_4, 4), lookup, 0);
		assertEquals(10 * 100 + 500, v.getLootGe());
		assertEquals(500, v.getLootHa());
		// 3 sharks + 5 doses of a 4-dose potion (8,000 * 5 / 4).
		assertEquals(3_000 + 10_000, v.getSuppliesGe());
		assertEquals(540, v.getSuppliesHa());
		assertEquals("a loss", 1_500 - 13_000, v.getProfitGe());
		assertEquals(0, v.getLootUnpriced());
		assertEquals(0, v.getSuppliesUnpriced());
	}

	@Test
	public void emptySessionIsZero()
	{
		SessionValuation.Values v = valuation.update("s1", null, null, null, lookup, 0);
		assertEquals(new SessionValuation.Values("s1", 0, 0, 0, 0, 0, 0), v);
	}

	@Test
	public void unitPricesAreCachedForTheSession()
	{
		valuation.update("s1", tally(BONES, 1), tally(SHARK, 1), null, lookup, 0);
		SessionValuation.Values v = valuation.update("s1", tally(BONES, 5), tally(SHARK, 2, PRAYER_4, 1),
			Collections.singletonMap(PRAYER_4, 4), lookup, 2);
		assertEquals(1, lookup.lookups(BONES));
		assertEquals(1, lookup.lookups(SHARK));
		assertEquals(1, lookup.lookups(PRAYER_4));
		assertEquals(500, v.getLootGe());
		assertEquals(2_000 + 2_000, v.getSuppliesGe());
	}

	@Test
	public void unchangedTalliesReturnTheSameValuesWithoutLookups()
	{
		SessionValuation.Values first = valuation.update("s1", tally(BONES, 1), new ItemTally(), null, lookup, 0);
		lookup.ge.put(BONES, 999L);
		SessionValuation.Values second = valuation.update("s1", tally(BONES, 1), new ItemTally(), null, lookup, 50);
		assertSame(first, second);
		assertEquals(1, lookup.lookups(BONES));
	}

	@Test
	public void newSessionDropsTheCache()
	{
		valuation.update("s1", tally(BONES, 1), null, null, lookup, 0);
		lookup.ge.put(BONES, 200L);
		SessionValuation.Values v = valuation.update("s2", tally(BONES, 1), null, null, lookup, 2);
		assertEquals(200, v.getLootGe());
		assertEquals(2, lookup.lookups(BONES));
	}

	@Test
	public void resetDropsTheCache()
	{
		valuation.update("s1", tally(BONES, 1), null, null, lookup, 0);
		valuation.reset();
		lookup.ge.put(BONES, 300L);
		assertEquals(300, valuation.update("s1", tally(BONES, 1), null, null, lookup, 2).getLootGe());
	}

	@Test
	public void unavailablePricesAreCountedAndRetriedOnACadence()
	{
		lookup.ge.put(SHARK, 0L);
		SessionValuation.Values v = valuation.update("s1", tally(BONES, 1), tally(SHARK, 4), null, lookup, 100);
		assertEquals(100, v.getLootGe());
		assertEquals("known prices still sum", 0, v.getSuppliesGe());
		assertEquals(1, v.getSuppliesUnpriced());
		assertEquals(1, lookup.lookups(SHARK));

		// Prices load meanwhile, but nothing changed and the retry window has not passed.
		lookup.ge.put(SHARK, 1_000L);
		valuation.update("s1", tally(BONES, 1), tally(SHARK, 4), null, lookup, 100 + SessionValuation.RETRY_TICKS - 1);
		assertEquals(1, lookup.lookups(SHARK));

		v = valuation.update("s1", tally(BONES, 1), tally(SHARK, 4), null, lookup, 100 + SessionValuation.RETRY_TICKS);
		assertEquals(2, lookup.lookups(SHARK));
		assertEquals(4_000, v.getSuppliesGe());
		assertEquals(0, v.getSuppliesUnpriced());
		assertEquals("found prices are not looked up again", 1, lookup.lookups(BONES));
	}

	@Test
	public void missingItemsNoLongerInTheTalliesAreNotRetried()
	{
		lookup.ge.put(SHARK, 0L);
		valuation.update("s1", tally(BONES, 1), tally(SHARK, 1), null, lookup, 0);
		// The shark was "returned" (supplies back to zero): it is gone from the tally.
		SessionValuation.Values v = valuation.update("s1", tally(BONES, 1), new ItemTally(), null, lookup,
			SessionValuation.RETRY_TICKS);
		assertEquals(1, lookup.lookups(SHARK));
		assertEquals(0, v.getSuppliesUnpriced());
	}

	@Test
	public void profitSaturates()
	{
		SessionValuation.Values v = new SessionValuation.Values("s1", Long.MAX_VALUE, 0, -1, 0, 0, 0);
		assertEquals(Long.MAX_VALUE, v.getProfitGe());
	}

	// ---- Loot price mode ----

	@Test
	public void lootFollowsThePriceModeAndSuppliesStayAtGe()
	{
		// Shark: GE 100, high alch 180. Bones: GE 100, high alch 0.
		lookup.ge.put(SHARK, 100L);
		ItemTally loot = tally(SHARK, 2, BONES, 1);
		ItemTally supplies = tally(SHARK, 1);

		SessionValuation.Values ge = valuation.update("s1", loot, supplies, null, LootPriceMode.GE, lookup, 0);
		assertEquals(LootPriceMode.GE, ge.getLootPriceMode());
		assertEquals(300, ge.getLootValue());
		assertEquals(200, ge.getProfit());

		SessionValuation.Values ha = valuation.update("s1", loot, supplies, null, LootPriceMode.HIGH_ALCH, lookup, 1);
		assertEquals(LootPriceMode.HIGH_ALCH, ha.getLootPriceMode());
		assertEquals(360, ha.getLootValue());
		assertEquals("supplies stay at GE", 100, ha.getSuppliesGe());
		assertEquals(260, ha.getProfit());
		assertEquals("the GE figures are kept", 300, ha.getLootGe());
		assertEquals(200, ha.getProfitGe());

		SessionValuation.Values back = valuation.update("s1", loot, supplies, null, LootPriceMode.GE, lookup, 2);
		assertEquals(LootPriceMode.GE, back.getLootPriceMode());
		assertEquals(300, back.getLootValue());
		assertEquals("a mode change reuses the cached prices", 1, lookup.lookups(SHARK));
		assertEquals(1, lookup.lookups(BONES));

		assertSame("unchanged mode and tallies", back,
			valuation.update("s1", loot, supplies, null, LootPriceMode.GE, lookup, 3));
	}

	@Test
	public void highAlchCountsOnlyItemsWithoutAHighAlchValueAsUnpriced()
	{
		lookup.ge.put(SHARK, 0L);
		SessionValuation.Values v = valuation.update("s1", tally(SHARK, 1, BONES, 1), null, null,
			LootPriceMode.HIGH_ALCH, lookup, 0);
		assertEquals("the shark at its high alch value", 180, v.getLootValue());
		assertEquals("both items have a high alch value", 0, v.getLootValueUnpriced());
		assertEquals("the shark's GE price is unavailable", 1, v.getLootUnpriced());
		assertEquals("the ~ follows the shown (high alch) figure", "Loot (HA): 180 gp", LiveSessionSection.liveLootText(v));
		String tooltip = LedgerHistory.valuesTooltip(v);
		// The GE figure misses the shark, so it is marked and counted in the note.
		assertTrue(tooltip, tooltip.contains("Loot: ~100 gp GE, 180 gp high alch"));
		assertTrue(tooltip, tooltip.contains("~ Some prices were unavailable (loot at high alch: 0, loot at GE: 1,"
			+ " supplies: 0), so the figures marked ~ may be too low."));

		SessionValuation.Values ge = valuation.update("s1", tally(SHARK, 1, BONES, 1), null, null, LootPriceMode.GE,
			lookup, 1);
		String geTooltip = LedgerHistory.valuesTooltip(ge);
		assertTrue(geTooltip, geTooltip.contains("Loot: ~100 gp GE, 180 gp high alch"));
		assertTrue("GE mode keeps its note", geTooltip.contains("(loot: 1, supplies: 0), so totals may be too low."));
	}

	@Test
	public void highAlchTooltipHasNoGeNoteWhenEveryGePriceIsKnown()
	{
		lookup.ge.put(SHARK, 100L);
		SessionValuation.Values v = valuation.update("s1", tally(SHARK, 1), tally(BONES, 1), null,
			LootPriceMode.HIGH_ALCH, lookup, 0);
		String tooltip = LedgerHistory.valuesTooltip(v);
		assertTrue(tooltip, tooltip.contains("Loot: 100 gp GE, 180 gp high alch"));
		assertFalse(tooltip, tooltip.contains("~"));
	}

	@Test
	public void anUntradeableItemCountsAtHighAlchInEveryMode()
	{
		// 7 Brimstone keys: untradeable (GE 0), high alch 48,000. One more item at GE 100 / high alch 60.
		final int key = 23083;
		final int rune = 1;
		ItemValuation.PriceLookup keys = new ItemValuation.PriceLookup()
		{
			@Override
			public int canonicalize(int itemId)
			{
				return itemId;
			}

			@Override
			public long gePrice(int canonicalId)
			{
				return canonicalId == rune ? 100 : 0;
			}

			@Override
			public boolean isTradeable(int canonicalId)
			{
				return canonicalId != key;
			}

			@Override
			public long haPrice(int canonicalId)
			{
				return canonicalId == key ? 48_000 : 60;
			}
		};
		ItemTally loot = tally(key, 7, rune, 1);
		// An untradeable supply stays at its GE price of 0: supplies are not part of the rule.
		ItemTally supplies = tally(key, 1);

		SessionValuation.Values ge = valuation.update("s1", loot, supplies, null, LootPriceMode.GE, keys, 0);
		assertEquals(336_100, ge.getLootValue());
		assertEquals(336_100, ge.getLootGe());
		assertEquals(0, ge.getLootValueUnpriced());
		assertEquals(0, ge.getLootUnpriced());
		assertEquals(0, ge.getSuppliesGe());
		assertEquals(336_100, ge.getProfit());

		SessionValuation.Values ha = valuation.update("s1", loot, supplies, null, LootPriceMode.HIGH_ALCH, keys, 1);
		assertEquals(336_060, ha.getLootValue());
		assertEquals("no ~", "Loot (HA): 336K gp", LiveSessionSection.liveLootText(ha));
		String tooltip = LedgerHistory.valuesTooltip(ha);
		assertTrue(tooltip, tooltip.contains("Loot: 336,100 gp GE, 336,060 gp high alch"));
		assertTrue(tooltip, tooltip.contains("Loot value (High alch): 336,060 gp"));
	}

	@Test
	public void liveLootAndProfitLinesAreLabelledWithThePriceMode()
	{
		lookup.ge.put(SHARK, 100L);
		ItemTally loot = tally(SHARK, 2);
		SessionValuation.Values ge = valuation.update("s1", loot, null, null, LootPriceMode.GE, lookup, 0);
		assertEquals("Loot (GE): 200 gp", LiveSessionSection.liveLootText(ge));
		assertEquals("Profit (GE): 200 gp", LiveSessionSection.liveProfitText(ge));
		String geTooltip = LedgerHistory.valuesTooltip(ge);
		assertTrue("both prices stay in the tooltip", geTooltip.contains("Loot: 200 gp GE, 360 gp high alch"));
		assertTrue(geTooltip, geTooltip.contains("Profit: 200 gp GE"));

		SessionValuation.Values ha = valuation.update("s1", loot, null, null, LootPriceMode.HIGH_ALCH, lookup, 1);
		assertEquals("Loot (HA): 360 gp", LiveSessionSection.liveLootText(ha));
		assertEquals("Profit (HA): 360 gp", LiveSessionSection.liveProfitText(ha));
		String tooltip = LedgerHistory.valuesTooltip(ha);
		assertTrue(tooltip, tooltip.contains("Loot: 200 gp GE, 360 gp high alch"));
		assertTrue(tooltip, tooltip.contains("Loot value (High alch): 360 gp"));
		assertTrue(tooltip, tooltip.contains("Profit: 360 gp (loot at high alch, supplies at GE)"));
	}

	@Test
	public void liveProfitIsApproximateWhenASupplyIsUnpriced()
	{
		lookup.ge.put(SHARK, 0L);
		SessionValuation.Values v = valuation.update("s1", tally(BONES, 1), tally(SHARK, 1), null, LootPriceMode.GE,
			lookup, 0);
		assertEquals(1, v.getSuppliesUnpriced());
		String profit = LiveSessionSection.liveProfitText(v);
		assertTrue(profit, profit.startsWith("Profit (GE): ~"));
	}
}
