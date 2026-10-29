package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

public class ItemDetailsTest
{
	private static final int SHARK = ItemID.SHARK;
	private static final int PRAYER_POTION_4 = ItemID._4DOSEPRAYERRESTORE;
	private static final int WHIP = ItemID.ABYSSAL_WHIP;
	private static final int RUNE_PLATEBODY = ItemID.RUNE_PLATEBODY;
	private static final int COINS = ItemID.COINS;
	/** An untradeable drop: GE 0, valued at high alch. */
	private static final int KEY = ItemID.KONAR_KEY;
	private static final int UNPRICED = 99_999;

	private static final Map<Integer, String> NAMES = new HashMap<>();

	static
	{
		NAMES.put(SHARK, "Shark");
		NAMES.put(PRAYER_POTION_4, "Prayer potion(4)");
		NAMES.put(WHIP, "Abyssal whip");
		NAMES.put(RUNE_PLATEBODY, "Rune platebody");
		NAMES.put(COINS, "Coins");
		NAMES.put(KEY, "Brimstone key");
		NAMES.put(ItemID.ANCHOVIE_PIZZA, "Anchovy pizza");
		NAMES.put(ItemID.AMULET_OF_GLORY_6, "Amulet of glory(6)");
	}

	/** The nature rune's GE price in these tests. */
	private static final Long NATURE_RUNE = 100L;
	private static final ItemDetails.ItemNames LOOKUP = NAMES::get;

	private static ItemTally tally(long... idQuantity)
	{
		ItemTally t = new ItemTally();
		for (int i = 0; i < idQuantity.length; i += 2)
		{
			t.add((int) idQuantity[i], idQuantity[i + 1]);
		}
		return t;
	}

	private static Map<Integer, Long> prices(long... idPrice)
	{
		Map<Integer, Long> m = new TreeMap<>();
		for (int i = 0; i < idPrice.length; i += 2)
		{
			m.put((int) idPrice[i], idPrice[i + 1]);
		}
		return m;
	}

	/** Each row as "name | quantity | value", with " | (alch)" when the hint is shown. */
	private static List<String> texts(List<ItemDetails.Row> rows)
	{
		return rows.stream()
			.map(r -> r.getName() + " | " + r.quantityText() + " | " + r.valueText()
				+ (r.isAlchHint() ? " | " + ItemDetails.ALCH_MARKER : ""))
			.collect(Collectors.toList());
	}

	@Test
	public void suppliesAreSortedByValueWithPotionsInDoses()
	{
		ItemDetails details = ItemDetails.of(new ItemTally(), tally(SHARK, 14, PRAYER_POTION_4, 22),
			Collections.singletonMap(PRAYER_POTION_4, 4), prices(SHARK, 1_000, PRAYER_POTION_4, 8_000),
			prices(SHARK, 50, PRAYER_POTION_4, 100));

		// 22 doses at 8,000 per 4 doses = 44,000; 14 sharks = 14,000.
		assertEquals(Arrays.asList("Prayer potion | 22 doses | 44K", "Shark | x14 | 14K"),
			texts(details.supplyRows(LOOKUP)));
	}

	@Test
	public void rowValuesAddUpToTheTotalsWithDoseRounding()
	{
		ItemTally supplies = tally(SHARK, 3, PRAYER_POTION_4, 7);
		Map<Integer, Integer> doses = Collections.singletonMap(PRAYER_POTION_4, 4);
		Map<Integer, Long> ge = prices(SHARK, 999, PRAYER_POTION_4, 8_003);
		long sum = 0;
		for (ItemDetails.Row row : ItemDetails.of(null, supplies, doses, ge, null).supplyRows(LOOKUP))
		{
			sum += row.getValue();
		}
		assertEquals(ItemValuation.total(supplies, ge, doses), sum);
	}

	@Test
	public void bitesAndChargesHaveTheirOwnWords()
	{
		Map<Integer, Integer> doses = new HashMap<>();
		doses.put(ItemID.ANCHOVIE_PIZZA, 2);
		doses.put(ItemID.AMULET_OF_GLORY_6, 6);
		doses.put(PRAYER_POTION_4, 4);
		ItemDetails details = ItemDetails.of(null,
			tally(ItemID.ANCHOVIE_PIZZA, 3, ItemID.AMULET_OF_GLORY_6, 1, PRAYER_POTION_4, 1), doses,
			prices(ItemID.ANCHOVIE_PIZZA, 400, ItemID.AMULET_OF_GLORY_6, 12_000, PRAYER_POTION_4, 8_000), null);

		assertEquals(Arrays.asList("Amulet of glory | 1 charge | 2,000", "Prayer potion | 1 dose | 2,000",
			"Anchovy pizza | 3 bites | 600"), texts(details.supplyRows(LOOKUP)));
		assertEquals(ItemDetails.Unit.BITES, ItemDetails.doseUnit(ItemID.ANCHOVIE_PIZZA));
		assertEquals(ItemDetails.Unit.CHARGES, ItemDetails.doseUnit(ItemID.AMULET_OF_GLORY_6));
		assertEquals(ItemDetails.Unit.DOSES, ItemDetails.doseUnit(PRAYER_POTION_4));
	}

	@Test
	public void unpricedItemsShowAQuestionMarkAndComeLast()
	{
		ItemDetails details = ItemDetails.of(tally(UNPRICED, 5, SHARK, 1), tally(UNPRICED, 2, SHARK, 1), null,
			prices(SHARK, 1_000), prices(SHARK, 50));

		assertEquals(Arrays.asList("Shark | x1 | 1,000", "Item 99999 | x2 | ?"), texts(details.supplyRows(LOOKUP)));
		assertEquals(Arrays.asList("Shark | x1 | 1,000", "Item 99999 | x5 | ?"),
			texts(details.lootRows(LootPriceMode.GE, LOOKUP)));
		assertTrue(details.supplyRows(LOOKUP).get(1).tooltip().contains("Value (GE): unavailable"));
	}

	@Test
	public void lootFollowsThePriceModeAndUntradeablesCountAtHighAlch()
	{
		ItemDetails details = ItemDetails.of(tally(WHIP, 1, RUNE_PLATEBODY, 2, KEY, 3, COINS, 5_000), null, null,
			prices(WHIP, 1_500_000, RUNE_PLATEBODY, 38_000, KEY, 0, COINS, 1),
			prices(WHIP, 72_000, RUNE_PLATEBODY, 39_000, KEY, 2_000, COINS, 1)).withNatureRunePrice(NATURE_RUNE);

		assertEquals(Arrays.asList("Abyssal whip | x1 | 1.5M", "Rune platebody | x2 | 76K | (alch)",
			"Brimstone key | x3 | 6,000", "Coins | x5,000 | 5,000"), texts(details.lootRows(LootPriceMode.GE, LOOKUP)));
		assertEquals(Arrays.asList("Rune platebody | x2 | 78K | (alch)", "Abyssal whip | x1 | 72K",
			"Brimstone key | x3 | 6,000", "Coins | x5,000 | 5,000"),
			texts(details.lootRows(LootPriceMode.HIGH_ALCH, LOOKUP)));
	}

	@Test
	public void alchHintNeedsHighAlchLessANatureRuneAboveAKnownNonZeroGePrice()
	{
		assertTrue(ItemDetails.alchBeatsGe(38_000L, 39_000L, NATURE_RUNE));
		assertFalse("equal is not better", ItemDetails.alchBeatsGe(38_900L, 39_000L, NATURE_RUNE));
		assertFalse("law rune: high alch beats GE, but not after the nature rune",
			ItemDetails.alchBeatsGe(117L, 144L, NATURE_RUNE));
		assertFalse(ItemDetails.alchBeatsGe(1_500_000L, 72_000L, NATURE_RUNE));
		assertFalse("untradeable: already valued at high alch", ItemDetails.alchBeatsGe(0L, 2_000L, NATURE_RUNE));
		assertFalse("GE unavailable", ItemDetails.alchBeatsGe(null, 2_000L, NATURE_RUNE));
		assertFalse("high alch unavailable", ItemDetails.alchBeatsGe(1_000L, null, NATURE_RUNE));
		assertFalse("nature rune price unknown", ItemDetails.alchBeatsGe(38_000L, 39_000L, null));

		ItemDetails details = ItemDetails.of(tally(RUNE_PLATEBODY, 1), null, null, prices(RUNE_PLATEBODY, 38_000),
			prices(RUNE_PLATEBODY, 39_000));
		assertFalse("no hint until the nature rune price is known", details.lootRows(LootPriceMode.GE, LOOKUP).get(0)
			.isAlchHint());
		assertFalse("an unavailable price (0) is unknown", details.withNatureRunePrice(0L)
			.lootRows(LootPriceMode.GE, LOOKUP).get(0).isAlchHint());
		assertTrue(details.withNatureRunePrice(NATURE_RUNE).lootRows(LootPriceMode.GE, LOOKUP).get(0).isAlchHint());
	}

	@Test
	public void suppliesNeverGetTheAlchHint()
	{
		ItemDetails details = ItemDetails.of(null, tally(RUNE_PLATEBODY, 1), null, prices(RUNE_PLATEBODY, 38_000),
			prices(RUNE_PLATEBODY, 39_000));
		assertFalse(details.supplyRows(LOOKUP).get(0).isAlchHint());
	}

	@Test
	public void alchHintTooltipExplainsIt()
	{
		ItemDetails details = ItemDetails.of(tally(RUNE_PLATEBODY, 2), null, null, prices(RUNE_PLATEBODY, 38_000),
			prices(RUNE_PLATEBODY, 39_000)).withNatureRunePrice(NATURE_RUNE);
		String tooltip = details.lootRows(LootPriceMode.GE, LOOKUP).get(0).tooltip();
		assertTrue(tooltip, tooltip.contains("Rune platebody<br>Quantity: 2<br>Value (GE): 76,000 gp"));
		assertTrue(tooltip, tooltip.contains("GE: 38,000 gp each<br>High alch: 39,000 gp each"));
		assertTrue(tooltip, tooltip.contains("(alch) High alch, less a nature rune, is worth more than<br>the GE price."));
	}

	@Test
	public void namesAreLookedUpAndFallBackToTheId()
	{
		Map<Integer, String> names = new HashMap<>();
		names.put(SHARK, "<b>Shark</b>");
		ItemDetails details = ItemDetails.of(tally(SHARK, 1, WHIP, 1), null, null, prices(SHARK, 1, WHIP, 1), null);
		List<ItemDetails.Row> rows = details.lootRows(LootPriceMode.GE, names::get);
		assertEquals("<b>Shark</b>", rows.get(0).getName());
		assertTrue("names are escaped in the tooltip", rows.get(0).tooltip().contains("&lt;b&gt;Shark"));
		assertEquals("Item " + WHIP, rows.get(1).getName());
		assertEquals("Item 7", ItemDetails.displayName(7, null, false));
		assertEquals("Item 7", ItemDetails.displayName(7, id -> " ", false));
		assertEquals("Prayer potion", ItemDetails.displayName(1, id -> "Prayer potion(4)", true));
		assertEquals("Prayer potion(4)", ItemDetails.displayName(1, id -> "Prayer potion(4)", false));
		assertEquals("Amulet of glory", ItemDetails.displayName(1, id -> "Amulet of glory(t6)", true));
		assertEquals("Ring of wealth", ItemDetails.displayName(1, id -> "Ring of wealth (i5)", true));
		assertEquals("Fungicide spray", ItemDetails.displayName(1, id -> "Fungicide spray 10", true));
		assertEquals("not counted per charge", "Black mask (10)", ItemDetails.displayName(1, id -> "Black mask (10)", false));
	}

	@Test
	public void tiesAreSortedByNameThenId()
	{
		Map<Integer, String> names = new HashMap<>();
		names.put(1, "b");
		names.put(2, "A");
		names.put(3, "A");
		ItemDetails details = ItemDetails.of(tally(1, 1, 3, 1, 2, 1), null, null, prices(1, 5, 2, 5, 3, 5), null);
		List<Integer> ids = new ArrayList<>();
		for (ItemDetails.Row row : details.lootRows(LootPriceMode.GE, names::get))
		{
			ids.add(row.getItemId());
		}
		assertEquals(Arrays.asList(2, 3, 1), ids);
	}

	@Test
	public void oldRecordsHaveNoItemDetails()
	{
		LedgerRecord old = new LedgerRecord();
		assertNull(ItemDetails.of(old));

		LedgerRecord empty = new LedgerRecord();
		empty.setLootGeValue(0L);
		ItemDetails details = ItemDetails.of(empty);
		assertNotNull("a valued task with nothing in it still has (empty) lists", details);
		assertTrue(details.supplyRows(LOOKUP).isEmpty());
		assertTrue(details.lootRows(LootPriceMode.GE, LOOKUP).isEmpty());
	}

	@Test
	public void savedRecordsUseTheirStoredPrices()
	{
		LedgerRecord record = new LedgerRecord();
		record.setLoot(prices(RUNE_PLATEBODY, 1, KEY, 1));
		record.setSupplies(prices(PRAYER_POTION_4, 6));
		record.setSupplyDoses(Collections.singletonMap(PRAYER_POTION_4, 4));
		record.setUnitGePrices(prices(RUNE_PLATEBODY, 38_000, KEY, 0, PRAYER_POTION_4, 8_000));
		record.setUnitHaPrices(prices(RUNE_PLATEBODY, 39_000, KEY, 2_000, PRAYER_POTION_4, 100));
		record.setLootGeValue(38_000L);

		ItemDetails details = ItemDetails.of(record).withNatureRunePrice(NATURE_RUNE);
		assertEquals(Arrays.asList("Rune platebody | x1 | 38K | (alch)", "Brimstone key | x1 | 2,000"),
			texts(details.lootRows(LootPriceMode.GE, LOOKUP)));
		assertEquals(Collections.singletonList("Prayer potion | 6 doses | 12K"), texts(details.supplyRows(LOOKUP)));
		assertEquals(new java.util.TreeSet<>(Arrays.asList(RUNE_PLATEBODY, KEY, PRAYER_POTION_4)), details.itemIds());

		// Today's GE prices: the GE side changes, high alch stays (as the history totals); a missing price is "?".
		ItemDetails today = details.withGePrices(prices(RUNE_PLATEBODY, 40_000, PRAYER_POTION_4, 10_000));
		assertEquals(Arrays.asList("Rune platebody | x1 | 40K", "Brimstone key | x1 | ?"),
			texts(today.lootRows(LootPriceMode.GE, LOOKUP)));
		assertEquals(Arrays.asList("Rune platebody | x1 | 39K | (alch)", "Brimstone key | x1 | 2,000"),
			texts(details.lootRows(LootPriceMode.HIGH_ALCH, LOOKUP)));
		assertEquals(Collections.singletonList("Prayer potion | 6 doses | 15K"), texts(today.supplyRows(LOOKUP)));
	}

	@Test
	public void rowValuesMatchTheHistoryTotals()
	{
		LedgerRecord record = new LedgerRecord();
		record.setLoot(prices(RUNE_PLATEBODY, 3, KEY, 2, COINS, 777));
		record.setUnitGePrices(prices(RUNE_PLATEBODY, 38_000, KEY, 0, COINS, 1));
		record.setUnitHaPrices(prices(RUNE_PLATEBODY, 39_000, KEY, 2_000, COINS, 1));
		record.setLootGeValue(38_000L * 3 + 777);
		record.setLootHaValue(39_000L * 3 + 4_000 + 777);
		for (LootPriceMode mode : LootPriceMode.values())
		{
			long sum = 0;
			for (ItemDetails.Row row : ItemDetails.of(record).lootRows(mode, LOOKUP))
			{
				sum += row.getValue();
			}
			assertEquals(mode.name(), HistoryPricing.value(record, mode, null).getLootValue().longValue(), sum);
		}
	}

	@Test
	public void longListsShowTheTopRowsUntilAskedForAll()
	{
		List<Integer> rows = new ArrayList<>();
		for (int i = 0; i < 13; i++)
		{
			rows.add(i);
		}
		assertEquals(ItemDetails.MAX_ROWS, ItemDetails.visible(rows, false).size());
		assertEquals(Integer.valueOf(9), ItemDetails.visible(rows, false).get(9));
		assertEquals(13, ItemDetails.visible(rows, true).size());
		assertEquals(3, ItemDetails.hiddenCount(13));
		assertEquals(0, ItemDetails.hiddenCount(10));
		assertEquals(10, ItemDetails.visible(rows.subList(0, 10), false).size());
	}

	@Test
	public void quantitiesAreShortenedLikeStackSizes()
	{
		ItemDetails details = ItemDetails.of(tally(COINS, 123_456), null, null, prices(COINS, 1), prices(COINS, 1));
		ItemDetails.Row row = details.lootRows(LootPriceMode.GE, LOOKUP).get(0);
		assertEquals("x123K", row.quantityText());
		assertEquals("123K", row.valueText());
		assertTrue(row.tooltip(), row.tooltip().contains("Quantity: 123,456"));
	}

	@Test
	public void emptyDetailsHaveNoRows()
	{
		assertTrue(ItemDetails.EMPTY.itemIds().isEmpty());
		assertTrue(ItemDetails.EMPTY.lootRows(null, LOOKUP).isEmpty());
		assertEquals(ItemDetails.EMPTY, ItemDetails.of(new ItemTally(), new ItemTally(), null, null, null));
	}
}
