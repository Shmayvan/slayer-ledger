package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * Saved tasks valued for the history: the price mode at saved prices, and re-pricing at today's GE prices,
 * which is what the sidebar always shows.
 */
public class HistoryPricingTest
{
	private static final int RUNE = 1;
	private static final int NO_GE = 3;
	private static final int POTION = 4;
	private static final int KEY = 23083;

	private static Map<Integer, Long> prices(long... idPrice)
	{
		Map<Integer, Long> map = new HashMap<>();
		for (int i = 0; i < idPrice.length; i += 2)
		{
			map.put((int) idPrice[i], idPrice[i + 1]);
		}
		return map;
	}

	/**
	 * Loot: 2 runes (GE 100, HA 60) and an item without a GE price (HA 300). Supplies: 3 doses of a 4-dose potion
	 * (GE 800, HA 100). Totals as {@link LedgerRecord#captureValues} saves them.
	 */
	private static LedgerRecord record()
	{
		LedgerRecord r = new LedgerRecord();
		r.setId("a");
		r.setMonster("Kurask");
		r.setLoot(prices(RUNE, 2, NO_GE, 1));
		r.setSupplies(prices(POTION, 3));
		r.setSupplyDoses(Collections.singletonMap(POTION, 4));
		r.setUnitGePrices(prices(RUNE, 100, POTION, 800));
		r.setUnitHaPrices(prices(RUNE, 60, NO_GE, 300, POTION, 100));
		r.setLootGeValue(200L);
		r.setLootHaValue(420L);
		r.setSuppliesGeValue(600L);
		r.setSuppliesHaValue(75L);
		r.setLootUnpricedItems(1);
		return r;
	}

	@Test
	public void geAtSavedPricesUsesTheSavedTotals()
	{
		HistoryPricing.Values v = HistoryPricing.value(record(), LootPriceMode.GE, null);
		assertTrue(v.isValued());
		assertFalse(v.isCurrentPrices());
		assertEquals(Long.valueOf(200), v.getLootValue());
		assertTrue("one loot item had no GE price", v.isLootApproximate());
		assertEquals(Long.valueOf(600), v.getSuppliesGe());
		assertFalse(v.isSuppliesApproximate());
		assertEquals(Long.valueOf(-400), v.getProfit());
		assertTrue(v.isProfitApproximate());
	}

	@Test
	public void highAlchUsesTheSavedHighAlchTotal()
	{
		HistoryPricing.Values v = HistoryPricing.value(record(), LootPriceMode.HIGH_ALCH, null);
		assertEquals(Long.valueOf(420), v.getLootValue());
		assertEquals("every loot item has a high alch value", 0, v.getLootUnpriced());
		assertFalse(v.isLootApproximate());
		assertEquals("supplies stay at GE", Long.valueOf(600), v.getSuppliesGe());
		assertEquals(Long.valueOf(-180), v.getProfit());
	}

	@Test
	public void recordWithoutUnitPricesUsesItsTotals()
	{
		LedgerRecord r = record();
		r.setUnitGePrices(null);
		r.setUnitHaPrices(null);
		r.setLootUnpricedItems(null);
		HistoryPricing.Values ge = HistoryPricing.value(r, LootPriceMode.GE, null);
		assertEquals(Long.valueOf(200), ge.getLootValue());
		assertFalse("the saved GE total is exact", ge.isLootApproximate());
		HistoryPricing.Values ha = HistoryPricing.value(r, LootPriceMode.HIGH_ALCH, null);
		assertEquals(Long.valueOf(420), ha.getLootValue());
		assertTrue("no high alch unit values: its loot items are unpriced, so ~", ha.isLootApproximate());
	}

	@Test
	public void emptyLootIsZeroInEveryMode()
	{
		LedgerRecord r = record();
		r.setLoot(Collections.emptyMap());
		r.setLootGeValue(0L);
		r.setLootHaValue(0L);
		r.setLootUnpricedItems(null);
		for (LootPriceMode mode : LootPriceMode.values())
		{
			HistoryPricing.Values v = HistoryPricing.value(r, mode, prices());
			assertEquals(mode.name(), Long.valueOf(0), v.getLootValue());
			assertFalse(mode.name(), v.isLootApproximate());
		}
	}

	@Test
	public void recordSavedBeforeValuesHasNoneInAnyModeOrPrices()
	{
		LedgerRecord old = new LedgerRecord();
		old.setMonster("Kurask");
		old.setLoot(prices(RUNE, 2));
		for (LootPriceMode mode : LootPriceMode.values())
		{
			for (Map<Integer, Long> current : Arrays.asList(null, prices(RUNE, 100)))
			{
				HistoryPricing.Values v = HistoryPricing.value(old, mode, current);
				assertFalse(v.isValued());
				assertNull(v.getLootValue());
				assertNull(v.getSuppliesGe());
				assertNull(v.getProfit());
				assertEquals(LedgerHistory.lootLabel(mode) + "-", LedgerHistory.formatLoot(v));
				assertEquals(LedgerHistory.profitLabel(mode) + "-", LedgerHistory.formatProfit(v));
			}
		}
	}

	@Test
	public void todaysPricesRepriceLootAndSuppliesAtGe()
	{
		LedgerRecord r = record();
		LedgerRecord saved = record();
		Map<Integer, Long> today = prices(RUNE, 150, NO_GE, 500, POTION, 1_000);
		HistoryPricing.Values v = HistoryPricing.value(r, LootPriceMode.GE, today);
		assertTrue(v.isCurrentPrices());
		assertEquals(Long.valueOf(800), v.getLootValue());
		assertEquals(Long.valueOf(800), v.getLootGe());
		assertFalse("every item has a price today", v.isLootApproximate());
		assertEquals("3 doses of a 1,000 gp 4-dose potion", Long.valueOf(750), v.getSuppliesGe());
		assertEquals(Long.valueOf(50), v.getProfit());
		assertEquals("high alch values don't change", Long.valueOf(420), v.getLootHa());
		assertEquals(Long.valueOf(75), v.getSuppliesHa());
		assertEquals("the record is never changed", saved, r);
	}

	@Test
	public void priceModeAppliesOnTopOfTodaysPrices()
	{
		Map<Integer, Long> today = prices(RUNE, 150, NO_GE, 500, POTION, 1_000);
		assertEquals(Long.valueOf(420),
			HistoryPricing.value(record(), LootPriceMode.HIGH_ALCH, today).getLootValue());
		// Runes at today's GE (2 x 150), the other item at today's GE (500).
		assertEquals(Long.valueOf(800), HistoryPricing.value(record(), LootPriceMode.GE, today).getLootValue());
	}

	@Test
	public void unavailableTodaysPricesAreLeftOutAndMarked()
	{
		// Logged out or prices not loaded yet: nothing is priced.
		HistoryPricing.Values none = HistoryPricing.value(record(), LootPriceMode.GE, prices());
		assertEquals(Long.valueOf(0), none.getLootValue());
		assertEquals(2, none.getLootUnpriced());
		assertEquals(1, none.getSuppliesUnpriced());
		assertTrue(none.isProfitApproximate());
		assertEquals("Loot (GE): ~0", LedgerHistory.formatLoot(none));
		assertEquals("Supplies: ~0", LedgerHistory.formatSupplies(none));

		// In "High alch", the loot doesn't need today's GE prices.
		HistoryPricing.Values ha = HistoryPricing.value(record(), LootPriceMode.HIGH_ALCH, prices(POTION, 1_000));
		assertEquals(Long.valueOf(420), ha.getLootValue());
		assertFalse(ha.isLootApproximate());
		assertFalse(ha.isSuppliesApproximate());
	}

	@Test
	public void highAlchAtTodaysPricesKeepsTheLootAndRevaluesTheSupplies()
	{
		Map<Integer, Long> today = prices(RUNE, 150, NO_GE, 500, POTION, 1_000);
		HistoryPricing.Values ha = HistoryPricing.value(record(), LootPriceMode.HIGH_ALCH, today);
		assertTrue(ha.isCurrentPrices());
		assertEquals("the saved high alch values", Long.valueOf(420), ha.getLootValue());
		assertFalse(ha.isLootApproximate());
		assertEquals("supplies at today's GE price", Long.valueOf(750), ha.getSuppliesGe());
		assertEquals(Long.valueOf(420 - 750), ha.getProfit());
		assertEquals("the GE figure follows today's prices too", Long.valueOf(800), ha.getLootGe());
		assertEquals("Loot (HA): 420", LedgerHistory.formatLoot(ha));
		assertEquals("Profit (HA): -330", LedgerHistory.formatProfit(ha));
	}

	@Test
	public void aRecordWithoutUnitPricesIsRevaluedFromItsItems()
	{
		// An older task saved without per-item prices, but with its items: its GE values are worked out from them at
		// today's prices, as its item details show them; its high alch values are its saved totals.
		LedgerRecord r = record();
		r.setUnitGePrices(null);
		r.setUnitHaPrices(null);
		r.setLootUnpricedItems(null);
		Map<Integer, Long> today = prices(RUNE, 150, NO_GE, 500, POTION, 1_000);
		HistoryPricing.Values ge = HistoryPricing.value(r, LootPriceMode.GE, today);
		assertFalse(ge.isSavedTotals());
		assertTrue(ge.isCurrentPrices());
		assertEquals("2 runes at 150 and the other item at 500", Long.valueOf(800), ge.getLootValue());
		assertEquals("3 doses of a 4-dose potion at 1,000", Long.valueOf(750), ge.getSuppliesGe());
		assertFalse(ge.isProfitApproximate());
		assertEquals("Loot (GE): 800", LedgerHistory.formatLoot(ge));
		assertEquals("Supplies: 750", LedgerHistory.formatSupplies(ge));
		assertEquals("Profit (GE): 50", LedgerHistory.formatProfit(ge));
		String tooltip = LedgerHistory.rowTooltip(r, ge, null);
		assertTrue(tooltip, tooltip.contains(LedgerHistory.CURRENT_PRICES));
		assertFalse(tooltip, tooltip.contains(LedgerHistory.SAVED_TOTALS));

		// High alch values never change: the saved totals, the loot marked ~ (no high alch unit values, so its items
		// are unpriced); the supplies at today's GE price.
		HistoryPricing.Values ha = HistoryPricing.value(r, LootPriceMode.HIGH_ALCH, today);
		assertEquals(Long.valueOf(420), ha.getLootValue());
		assertEquals(Long.valueOf(75), ha.getSuppliesHa());
		assertEquals(Long.valueOf(750), ha.getSuppliesGe());
		assertTrue(ha.isLootApproximate());
		assertFalse(ha.isSuppliesApproximate());

		// An item without today's price is left out and marked, as for any record.
		HistoryPricing.Values missing = HistoryPricing.value(r, LootPriceMode.GE, prices(RUNE, 150, POTION, 1_000));
		assertEquals(Long.valueOf(300), missing.getLootValue());
		assertEquals(1, missing.getLootUnpriced());
		assertTrue(missing.isLootApproximate());
	}

	@Test
	public void aRowWithoutUnitPricesAgreesWithItsItemDetails()
	{
		LedgerRecord r = new LedgerRecord();
		r.setMonster("Blue dragons");
		r.setLoot(Collections.singletonMap(ItemID.DRAGON_BONES, 10L));
		r.setLootGeValue(20_000L);
		r.setLootHaValue(0L);
		Map<Integer, Long> today = Collections.singletonMap(ItemID.DRAGON_BONES, 3_000L);

		HistoryPricing.Values ge = HistoryPricing.value(r, LootPriceMode.GE, today);
		assertEquals("Loot (GE): 30K", LedgerHistory.formatLoot(ge));
		assertEquals("Profit (GE): 30K", LedgerHistory.formatProfit(ge));
		assertFalse(ge.isProfitApproximate());
		ItemDetails.Row row = ItemDetails.of(r).withGePrices(today).lootRows(LootPriceMode.GE, null).get(0);
		assertEquals("the item grid shows the same value", ge.getLootValue(), row.getValue());
	}

	@Test
	public void highAlchOfARecordWithoutHighAlchUnitValuesIsMarkedLikeItsItemDetails()
	{
		// Saved with its loot items and GE unit prices, but no high alch unit values.
		LedgerRecord r = record();
		r.setUnitHaPrices(null);
		Map<Integer, Long> today = prices(RUNE, 150, NO_GE, 500, POTION, 1_000);
		HistoryPricing.Values ha = HistoryPricing.value(r, LootPriceMode.HIGH_ALCH, today);
		assertEquals("the saved high alch total", Long.valueOf(420), ha.getLootValue());
		assertEquals("both loot items unpriced", 2, ha.getLootUnpriced());
		assertTrue(ha.isLootApproximate());
		assertEquals("Loot (HA): ~420", LedgerHistory.formatLoot(ha));
		for (ItemDetails.Row row : ItemDetails.of(r).withGePrices(today).lootRows(LootPriceMode.HIGH_ALCH, null))
		{
			assertNull("the grid shows the item unpriced", row.getValue());
		}

		// GE values are worked out from the items and stay exact.
		HistoryPricing.Values ge = HistoryPricing.value(r, LootPriceMode.GE, today);
		assertEquals(Long.valueOf(800), ge.getLootValue());
		assertFalse(ge.isLootApproximate());

		// A record with no loot items has nothing unpriced: its saved high alch total stays exact.
		r.setLoot(null);
		assertFalse(HistoryPricing.value(r, LootPriceMode.HIGH_ALCH, today).isLootApproximate());
	}

	@Test
	public void aRecordWithoutItemsKeepsItsSavedTotalsMarkedApproximate()
	{
		// An older task saved with its totals only: today's prices can't revalue it.
		LedgerRecord r = record();
		r.setLoot(null);
		r.setSupplies(null);
		r.setSupplyDoses(null);
		r.setUnitGePrices(null);
		r.setUnitHaPrices(null);
		r.setLootUnpricedItems(null);
		Map<Integer, Long> today = prices(RUNE, 150, NO_GE, 500, POTION, 1_000);
		HistoryPricing.Values ge = HistoryPricing.value(r, LootPriceMode.GE, today);
		assertTrue(ge.isSavedTotals());
		assertFalse(ge.isCurrentPrices());
		assertEquals("the saved loot total", Long.valueOf(200), ge.getLootValue());
		assertEquals("the saved supplies total", Long.valueOf(600), ge.getSuppliesGe());
		assertTrue("not at today's prices: ~", ge.isLootApproximate());
		assertTrue(ge.isSuppliesApproximate());
		assertEquals("Loot (GE): ~200", LedgerHistory.formatLoot(ge));
		assertEquals("Supplies: ~600", LedgerHistory.formatSupplies(ge));
		assertEquals("Profit (GE): ~-400", LedgerHistory.formatProfit(ge));
		assertTrue(LedgerHistory.rowTooltip(r, ge, null).contains(LedgerHistory.SAVED_TOTALS));

		// High alch values never change: the saved total is exact, but the supplies are still saved GE.
		HistoryPricing.Values ha = HistoryPricing.value(r, LootPriceMode.HIGH_ALCH, today);
		assertEquals(Long.valueOf(420), ha.getLootValue());
		assertFalse(ha.isLootApproximate());
		assertTrue(ha.isProfitApproximate());

		// Unit prices without items change nothing: there is still nothing to revalue.
		r.setUnitGePrices(prices(RUNE, 100));
		HistoryPricing.Values withUnits = HistoryPricing.value(r, LootPriceMode.GE, today);
		assertTrue(withUnits.isSavedTotals());
		assertEquals(Long.valueOf(200), withUnits.getLootValue());

		// At the prices saved with it (the task-complete chat line), nothing is marked.
		assertFalse(HistoryPricing.value(record(), LootPriceMode.GE, null).isSavedTotals());
	}

	@Test
	public void aValuedRecordWithNothingToPriceSaysNothingAboutSavedTotals()
	{
		// Empty loot, no supplies, no per-item prices and saved GE totals of 0: nothing to value, nothing marked.
		LedgerRecord r = new LedgerRecord();
		r.setMonster("Kurask");
		r.setLoot(Collections.emptyMap());
		r.setLootGeValue(0L);
		r.setLootHaValue(0L);
		Map<Integer, Long> today = prices(RUNE, 150);
		for (LootPriceMode mode : LootPriceMode.values())
		{
			HistoryPricing.Values v = HistoryPricing.value(r, mode, today);
			assertFalse(mode.toString(), v.isSavedTotals());
			assertEquals(LedgerHistory.lootLabel(mode) + "0", LedgerHistory.formatLoot(v));
			assertEquals("Supplies: 0", LedgerHistory.formatSupplies(v));
			assertFalse(v.isProfitApproximate());
			String tooltip = LedgerHistory.rowTooltip(r, v, null);
			assertFalse(tooltip, tooltip.contains(LedgerHistory.SAVED_TOTALS));
			assertFalse(tooltip, tooltip.contains("~"));
		}

		// No loot map at all, the same.
		r.setLoot(null);
		HistoryPricing.Values none = HistoryPricing.value(r, LootPriceMode.GE, today);
		assertFalse(none.isSavedTotals());
		assertFalse(LedgerHistory.rowTooltip(r, none, null).contains(LedgerHistory.SAVED_TOTALS));
	}

	/**
	 * Like the owner's saved Wyrms task: 7 Brimstone keys saved at GE 0 (untradeable) and high alch 48,000, one rune
	 * (GE 100, HA 60), and the stored GE total that leaves the keys at 0.
	 */
	private static LedgerRecord keyRecord()
	{
		LedgerRecord r = new LedgerRecord();
		r.setId("k");
		r.setMonster("Wyrms");
		r.setLoot(prices(KEY, 7, RUNE, 1));
		r.setSupplies(prices(POTION, 4));
		r.setSupplyDoses(Collections.singletonMap(POTION, 4));
		r.setUnitGePrices(prices(KEY, 0, RUNE, 100, POTION, 800));
		r.setUnitHaPrices(prices(KEY, 48_000, RUNE, 60, POTION, 100));
		r.setLootGeValue(100L);
		r.setLootHaValue(336_060L);
		r.setSuppliesGeValue(800L);
		r.setSuppliesHaValue(100L);
		return r;
	}

	@Test
	public void aSavedGePriceOfZeroCountsAtHighAlchInEveryMode()
	{
		LedgerRecord r = keyRecord();
		LedgerRecord saved = keyRecord();
		HistoryPricing.Values ge = HistoryPricing.value(r, LootPriceMode.GE, null);
		assertEquals("recomputed from the unit prices, not the stored total", Long.valueOf(336_100), ge.getLootValue());
		assertEquals(Long.valueOf(336_100), ge.getLootGe());
		assertEquals(0, ge.getLootUnpriced());
		assertFalse(ge.isLootApproximate());
		assertEquals(Long.valueOf(336_100 - 800), ge.getProfit());
		assertEquals("Loot (GE): 336K", LedgerHistory.formatLoot(ge));
		String tooltip = LedgerHistory.rowTooltip(r, ge);
		assertTrue(tooltip, tooltip.contains("Loot: 336,100 gp GE, 336,060 gp high alch"));
		assertFalse(tooltip, tooltip.contains("unavailable"));
		assertEquals(Long.valueOf(336_060), HistoryPricing.value(r, LootPriceMode.HIGH_ALCH, null).getLootValue());
		assertEquals("the record is never changed", saved, r);
	}

	@Test
	public void aNewRecordShowsLikeAnOldOneWithUnitPrices()
	{
		LedgerRecord r = new LedgerRecord();
		r.setLoot(prices(KEY, 7, RUNE, 1));
		r.setSupplies(prices(POTION, 4));
		r.setSupplyDoses(Collections.singletonMap(POTION, 4));
		r.captureValues(new ItemValuation.PriceLookup()
		{
			@Override
			public int canonicalize(int itemId)
			{
				return itemId;
			}

			@Override
			public long gePrice(int canonicalId)
			{
				return canonicalId == RUNE ? 100 : canonicalId == POTION ? 800 : 0;
			}

			@Override
			public boolean isTradeable(int canonicalId)
			{
				return canonicalId != KEY;
			}

			@Override
			public long haPrice(int canonicalId)
			{
				return canonicalId == KEY ? 48_000 : canonicalId == RUNE ? 60 : 100;
			}
		});
		assertEquals("the stored GE total stays at the captured GE prices", Long.valueOf(100), r.getLootGeValue());
		assertEquals(Long.valueOf(0), r.getUnitGePrices().get(KEY));
		assertEquals(keyRecord().getLootHaValue(), r.getLootHaValue());
		assertEquals(keyRecord().getSuppliesGeValue(), r.getSuppliesGeValue());
		assertNull(r.getLootUnpricedItems());
		for (LootPriceMode mode : LootPriceMode.values())
		{
			HistoryPricing.Values fresh = HistoryPricing.value(r, mode, null);
			HistoryPricing.Values old = HistoryPricing.value(keyRecord(), mode, null);
			assertEquals(mode.toString(), old.getLootValue(), fresh.getLootValue());
			assertEquals(mode.toString(), old.getLootGe(), fresh.getLootGe());
			assertEquals(mode.toString(), old.getProfit(), fresh.getProfit());
		}
	}

	@Test
	public void aRecordWithoutUnitPricesKeepsItsStoredGeTotal()
	{
		LedgerRecord r = keyRecord();
		r.setUnitGePrices(null);
		r.setUnitHaPrices(null);
		HistoryPricing.Values ge = HistoryPricing.value(r, LootPriceMode.GE, null);
		assertEquals(Long.valueOf(100), ge.getLootValue());
		assertEquals(Long.valueOf(100), ge.getLootGe());
		assertFalse(ge.isLootApproximate());
	}

	@Test
	public void todaysGePriceOfZeroCountsAtHighAlch()
	{
		Map<Integer, Long> today = prices(KEY, 0, RUNE, 150, POTION, 1_000);
		HistoryPricing.Values ge = HistoryPricing.value(keyRecord(), LootPriceMode.GE, today);
		assertTrue(ge.isCurrentPrices());
		assertEquals("keys at the saved high alch, the rune at today's GE", Long.valueOf(336_150), ge.getLootValue());
		assertEquals(Long.valueOf(336_150), ge.getLootGe());
		assertEquals(0, ge.getLootUnpriced());
		assertEquals("supplies at today's GE, unchanged by the rule", Long.valueOf(1_000), ge.getSuppliesGe());

		// Today's price of the keys not available: unpriced, as before.
		today.remove(KEY);
		HistoryPricing.Values missing = HistoryPricing.value(keyRecord(), LootPriceMode.GE, today);
		assertEquals(Long.valueOf(150), missing.getLootValue());
		assertEquals(1, missing.getLootUnpriced());
	}

	@Test
	public void itemIdsAreTheDistinctLootAndSupplyIds()
	{
		LedgerRecord other = new LedgerRecord();
		other.setLoot(prices(RUNE, 1, 7, 1));
		assertEquals(new TreeSet<>(Arrays.asList(RUNE, NO_GE, POTION, 7)),
			HistoryPricing.itemIds(Arrays.asList(record(), other, null, new LedgerRecord())));
	}
}
