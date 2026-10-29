package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * The supplies and loot each Overall totals group sums item by item ({@link ItemSums}), as
 * {@link OverallTotals#summarize} works them out and the panel names them ({@link ItemDetails#named}).
 */
public class OverallTotalsItemsTest
{
	private static final int SHARK = ItemID.SHARK;
	private static final int PRAYER_POTION_4 = ItemID._4DOSEPRAYERRESTORE;
	private static final int RUNE_PLATEBODY = ItemID.RUNE_PLATEBODY;
	private static final int DRAGON_BONES = ItemID.DRAGON_BONES;
	private static final int NOTED_DRAGON_BONES = ItemID.Cert.DRAGON_BONES;
	private static final ZoneId UTC = ZoneId.of("UTC");

	private static final Map<Integer, String> NAMES = new HashMap<>();

	static
	{
		NAMES.put(SHARK, "Shark");
		NAMES.put(PRAYER_POTION_4, "Prayer potion(4)");
		NAMES.put(RUNE_PLATEBODY, "Rune platebody");
		NAMES.put(DRAGON_BONES, "Dragon bones");
		NAMES.put(NOTED_DRAGON_BONES, "Dragon bones");
	}

	private static final ItemDetails.ItemNames LOOKUP = NAMES::get;

	@Test
	public void severalTasksAddUpItemByItemAsTheProfitCountsThem()
	{
		// Newest first, as the history holds them; each task was saved at other prices, but is valued at today's.
		LedgerRecord newer = valued(map(SHARK, 5), map(RUNE_PLATEBODY, 1), map(SHARK, 1_200, RUNE_PLATEBODY, 38_000),
			map(SHARK, 50, RUNE_PLATEBODY, 39_000));
		LedgerRecord older = valued(map(SHARK, 3), map(RUNE_PLATEBODY, 2, DRAGON_BONES, 4),
			map(SHARK, 1_000, RUNE_PLATEBODY, 36_000, DRAGON_BONES, 2_000),
			map(SHARK, 50, RUNE_PLATEBODY, 39_000, DRAGON_BONES, 1));

		Map<Integer, Long> today = map(SHARK, 1_100, RUNE_PLATEBODY, 37_000, DRAGON_BONES, 2_000);
		OverallTotals.Entry total = all(Arrays.asList(newer, older), LootPriceMode.GE, today);

		List<ItemDetails.Row> supplies = ItemDetails.named(total.getSupplies(), LOOKUP, null, false);
		assertEquals(Collections.singletonList("Shark | x8 | 8,800"), texts(supplies));
		List<ItemDetails.Row> loot = ItemDetails.named(total.getLoot(), LOOKUP, null, true);
		// 3 x 37,000; 4 x 2,000.
		assertEquals(Arrays.asList("Rune platebody | x3 | 111K", "Dragon bones | x4 | 8,000"), texts(loot));
		assertTrue(loot.get(0).tooltip(), loot.get(0).tooltip().contains("GE: 37,000 gp each"));
		assertTrue(loot.get(0).tooltip(), loot.get(0).tooltip().contains("Value (GE): 111,000 gp"));
		assertEquals(sum(loot) - sum(supplies), (long) total.getProfit());
	}

	@Test
	public void dosesAddUpAsDosesAndNotedItemsStayTheirOwnRows()
	{
		LedgerRecord newer = valued(map(PRAYER_POTION_4, 6), map(DRAGON_BONES, 2, NOTED_DRAGON_BONES, 10),
			map(PRAYER_POTION_4, 8_000, DRAGON_BONES, 2_000, NOTED_DRAGON_BONES, 2_000), null);
		newer.setSupplyDoses(Collections.singletonMap(PRAYER_POTION_4, 4));
		LedgerRecord older = valued(map(PRAYER_POTION_4, 7), map(NOTED_DRAGON_BONES, 5),
			map(PRAYER_POTION_4, 8_000, NOTED_DRAGON_BONES, 2_000), null);
		older.setSupplyDoses(Collections.singletonMap(PRAYER_POTION_4, 4));

		OverallTotals.Entry total = all(Arrays.asList(newer, older), LootPriceMode.GE,
			map(PRAYER_POTION_4, 8_000, DRAGON_BONES, 2_000, NOTED_DRAGON_BONES, 2_000));

		// 6 doses = 12,000 and 7 doses = 14,000 (8,000 per 4 doses).
		List<ItemDetails.Row> supplies = ItemDetails.named(total.getSupplies(), LOOKUP, null, false);
		assertEquals(Collections.singletonList("Prayer potion | 13 doses | 26K"), texts(supplies));
		assertEquals(ItemDetails.Unit.DOSES, supplies.get(0).getUnit());
		assertTrue(supplies.get(0).tooltip(), supplies.get(0).tooltip().contains("GE: 8,000 gp each (4 doses)"));
		assertEquals(Arrays.asList("Dragon bones | x15 | 30K", "Dragon bones | x2 | 4,000"),
			texts(ItemDetails.named(total.getLoot(), LOOKUP, null, true)));
		assertEquals(NOTED_DRAGON_BONES, ItemDetails.named(total.getLoot(), LOOKUP, null, true).get(0).getItemId());
	}

	@Test
	public void tasksWithoutItemDataAddNothing()
	{
		LedgerRecord itemized = valued(map(SHARK, 2), map(RUNE_PLATEBODY, 1), map(SHARK, 1_000, RUNE_PLATEBODY, 38_000),
			null);
		// Saved before loot and supplies were tracked: a value but no items.
		LedgerRecord old = record(SlayerMaster.KONAR);
		old.setLootGeValue(50_000L);
		old.setSuppliesGeValue(1_000L);
		// Saved before values were tracked at all.
		LedgerRecord older = record(SlayerMaster.KONAR);

		Map<Integer, Long> today = map(SHARK, 1_000, RUNE_PLATEBODY, 38_000);
		OverallTotals.Entry total = all(Arrays.asList(itemized, old, older), LootPriceMode.GE, today);
		assertEquals(3, total.getTaskCount());
		assertEquals(Collections.singletonList("Shark | x2 | 2,000"),
			texts(ItemDetails.named(total.getSupplies(), LOOKUP, null, false)));
		assertEquals(Collections.singletonList("Rune platebody | x1 | 38K"),
			texts(ItemDetails.named(total.getLoot(), LOOKUP, null, true)));

		OverallTotals.Entry none = all(Arrays.asList(old, older), LootPriceMode.GE, today);
		assertTrue(none.getSupplies().isEmpty());
		assertTrue(none.getLoot().isEmpty());

		// By master, only the groups with item data have items.
		LedgerRecord otherMaster = record(SlayerMaster.KRYSTILIA);
		otherMaster.setLootGeValue(10L);
		List<OverallTotals.Entry> masters = OverallTotals.summarize(Arrays.asList(itemized, otherMaster),
			OverallTotals.Grouping.MASTER, LootPriceMode.GE, today, UTC);
		assertEquals(Arrays.asList("Konar", "Krystilia"),
			masters.stream().map(OverallTotals.Entry::getLabel).collect(Collectors.toList()));
		assertEquals(1, masters.get(0).getLoot().size());
		assertTrue(masters.get(1).getLoot().isEmpty());
		assertTrue(masters.get(1).getSupplies().isEmpty());
	}

	@Test
	public void lootFollowsTheLootPriceModeAndTodaysPrices()
	{
		LedgerRecord newer = valued(map(SHARK, 2), map(RUNE_PLATEBODY, 1), map(SHARK, 1_000, RUNE_PLATEBODY, 38_000),
			map(SHARK, 50, RUNE_PLATEBODY, 39_000));
		LedgerRecord older = valued(map(SHARK, 1), map(RUNE_PLATEBODY, 1), map(SHARK, 900, RUNE_PLATEBODY, 37_000),
			map(SHARK, 50, RUNE_PLATEBODY, 39_000));
		List<LedgerRecord> records = Arrays.asList(newer, older);

		// High alch beats today's GE price of 38,000.
		OverallTotals.Entry ha = all(records, LootPriceMode.HIGH_ALCH, map(SHARK, 1_000, RUNE_PLATEBODY, 38_000));
		List<ItemDetails.Row> haLoot = ItemDetails.named(ha.getLoot(), LOOKUP, 100L, true);
		assertEquals(Collections.singletonList("Rune platebody | x2 | 78K | (alch)"), texts(haLoot));
		assertTrue(haLoot.get(0).tooltip(), haLoot.get(0).tooltip().contains("Value (high alch): 78,000 gp"));
		// Supplies are GE-valued in every mode.
		assertEquals(Collections.singletonList("Shark | x3 | 3,000"),
			texts(ItemDetails.named(ha.getSupplies(), LOOKUP, 100L, false)));
		assertEquals(sum(haLoot) - 3_000, (long) ha.getProfit());

		// Other prices today: every task at today's GE price; high alch stays as saved.
		Map<Integer, Long> today = map(SHARK, 1_100, RUNE_PLATEBODY, 50_000);
		OverallTotals.Entry ge = all(records, LootPriceMode.GE, today);
		List<ItemDetails.Row> geLoot = ItemDetails.named(ge.getLoot(), LOOKUP, 100L, true);
		assertEquals("at 50,000 on the GE, alching no longer pays",
			Collections.singletonList("Rune platebody | x2 | 100K"), texts(geLoot));
		assertTrue(geLoot.get(0).tooltip(), geLoot.get(0).tooltip().contains("GE: 50,000 gp each"));
		assertEquals(Collections.singletonList("Shark | x3 | 3,300"),
			texts(ItemDetails.named(ge.getSupplies(), LOOKUP, 100L, false)));
		assertEquals(100_000L - 3_300L, (long) ge.getProfit());
	}

	@Test
	public void aPriceMissingInSomeTasksMakesTheValueApproximate()
	{
		// Summed straight from each task's rows at its own prices: at today's prices an item is priced in all tasks
		// or in none (below).
		LedgerRecord priced = valued(null, map(RUNE_PLATEBODY, 1), map(RUNE_PLATEBODY, 38_000), null);
		LedgerRecord unpriced = valued(null, map(RUNE_PLATEBODY, 2), null, null);
		ItemSums sums = new ItemSums();
		sums.add(ItemDetails.of(priced).lootRows(LootPriceMode.GE, null));
		sums.add(ItemDetails.of(unpriced).lootRows(LootPriceMode.GE, null));

		ItemDetails.Row row = ItemDetails.named(sums.rows(), LOOKUP, null, true).get(0);
		assertEquals(3, row.getQuantity());
		assertEquals(Long.valueOf(38_000L), row.getValue());
		assertTrue(row.isApproximate());
		assertEquals("~38K", row.valueText());
		assertTrue(row.tooltip(), row.tooltip().contains("Value (GE): ~38,000 gp"));

		// No price today: no value, and nothing approximate about it.
		ItemDetails.Row none = all(Arrays.asList(priced, unpriced), LootPriceMode.GE, Collections.emptyMap()).getLoot()
			.get(0);
		assertEquals(3, none.getQuantity());
		assertNull(none.getValue());
		assertFalse(none.isApproximate());
	}

	@Test
	public void namedRowsGetNamesAndTheAlchHintOnlyForLoot()
	{
		LedgerRecord record = valued(map(RUNE_PLATEBODY, 1), map(RUNE_PLATEBODY, 1), map(RUNE_PLATEBODY, 38_000),
			map(RUNE_PLATEBODY, 39_000));
		OverallTotals.Entry total = all(Collections.singletonList(record), LootPriceMode.GE,
			map(RUNE_PLATEBODY, 38_000));

		assertEquals("Item " + RUNE_PLATEBODY, total.getLoot().get(0).getName());
		assertFalse("no hint before the nature rune price is known", total.getLoot().get(0).isAlchHint());
		assertTrue(ItemDetails.named(total.getLoot(), LOOKUP, 100L, true).get(0).isAlchHint());
		assertFalse(ItemDetails.named(total.getLoot(), LOOKUP, null, true).get(0).isAlchHint());
		assertFalse(ItemDetails.named(total.getSupplies(), LOOKUP, 100L, false).get(0).isAlchHint());
		assertEquals("Item " + RUNE_PLATEBODY, ItemDetails.named(total.getLoot(), id -> null, 100L, true).get(0)
			.getName());
	}

	private static OverallTotals.Entry all(List<LedgerRecord> records, LootPriceMode mode, Map<Integer, Long> today)
	{
		return OverallTotals.summarize(records, OverallTotals.Grouping.ALL, mode, today, UTC).get(0);
	}

	/** A completed task with items, valued at {@code ge} and {@code ha} as the plugin saves it. */
	private static LedgerRecord valued(Map<Integer, Long> supplies, Map<Integer, Long> loot, Map<Integer, Long> ge,
		Map<Integer, Long> ha)
	{
		LedgerRecord record = record(SlayerMaster.KONAR);
		record.setSupplies(supplies);
		record.setLoot(loot);
		record.setUnitGePrices(ge);
		record.setUnitHaPrices(ha);
		Map<Integer, Integer> doses = record.getSupplyDoses();
		ItemTally lootTally = loot == null ? new ItemTally() : ItemTally.fromMap(loot);
		record.setLootGeValue(LootPricing.total(lootTally, LootPriceMode.GE, ge, ha, null));
		record.setLootHaValue(ha == null ? 0L : ItemValuation.total(lootTally, ha, null));
		record.setSuppliesGeValue(supplies == null ? 0L : ItemValuation.total(ItemTally.fromMap(supplies), ge, doses));
		return record;
	}

	private static LedgerRecord record(Integer master)
	{
		LedgerRecord record = new LedgerRecord();
		record.setSlayerMaster(master);
		record.setEndedAt(1);
		record.setKills(10);
		record.setActiveMillis(60_000L);
		record.setCompleted(true);
		record.setPartial(false);
		return record;
	}

	private static Map<Integer, Long> map(long... idValue)
	{
		Map<Integer, Long> m = new TreeMap<>();
		for (int i = 0; i < idValue.length; i += 2)
		{
			m.put((int) idValue[i], idValue[i + 1]);
		}
		return m;
	}

	private static long sum(List<ItemDetails.Row> rows)
	{
		long sum = 0;
		for (ItemDetails.Row row : rows)
		{
			sum += row.getValue();
		}
		return sum;
	}

	/** Each row as "name | quantity | value", with " | (alch)" when the hint is shown. */
	private static List<String> texts(List<ItemDetails.Row> rows)
	{
		return rows.stream()
			.map(r -> r.getName() + " | " + r.quantityText() + " | " + r.valueText()
				+ (r.isAlchHint() ? " | " + ItemDetails.ALCH_MARKER : ""))
			.collect(Collectors.toList());
	}
}
