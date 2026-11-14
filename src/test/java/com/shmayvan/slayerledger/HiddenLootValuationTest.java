package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Loot Ground Items hides is left out of the loot value: saved tasks ({@link HistoryPricing}) and everything
 * built on them (the monster ranking, Overall totals, the statistics, the chat line), and the live session
 * ({@link SessionValuation}). The records themselves are never changed, so with nothing hidden the values come back.
 */
public class HiddenLootValuationTest
{
	private static final int RUNE = 1;
	private static final int BONES = 2;
	private static final int UNPRICED = 3;
	private static final int POTION = 4;
	private static final HiddenLoot HIDE_BONES = HiddenLoot.of(Collections.singleton(BONES));

	private static Map<Integer, Long> map(long... idValue)
	{
		Map<Integer, Long> map = new HashMap<>();
		for (int i = 0; i < idValue.length; i += 2)
		{
			map.put((int) idValue[i], idValue[i + 1]);
		}
		return map;
	}

	/**
	 * A completed hour-long task: loot 2 runes (GE 100, HA 60), 10 bones (GE 50, HA 5) and an item without a GE price
	 * (HA 300); supplies 600 gp. Totals as {@link LedgerRecord#captureValues} saves them.
	 */
	private static LedgerRecord record()
	{
		LedgerRecord r = new LedgerRecord();
		r.setId("a");
		r.setMonster("Kurask");
		r.setCreatureId(410);
		r.setKills(10);
		r.setActiveMillis(3_600_000L);
		r.setStartedAt(1_000L);
		r.setEndedAt(3_601_000L);
		r.setCompleted(true);
		r.setPartial(false);
		r.setLoot(map(RUNE, 2, BONES, 10, UNPRICED, 1));
		r.setSupplies(map(POTION, 3));
		r.setSupplyDoses(Collections.singletonMap(POTION, 4));
		r.setUnitGePrices(map(RUNE, 100, BONES, 50, POTION, 800));
		r.setUnitHaPrices(map(RUNE, 60, BONES, 5, UNPRICED, 300, POTION, 100));
		r.setLootGeValue(700L);
		r.setLootHaValue(470L);
		r.setSuppliesGeValue(600L);
		r.setSuppliesHaValue(75L);
		r.setLootUnpricedItems(1);
		return r;
	}

	@Test
	public void savedPricesLeaveHiddenItemsOutOfLootAndProfit()
	{
		HistoryPricing.Values all = HistoryPricing.value(record(), LootPriceMode.GE, null, HiddenLoot.NONE);
		HistoryPricing.Values hidden = HistoryPricing.value(record(), LootPriceMode.GE, null, HIDE_BONES);
		assertEquals(Long.valueOf(700), all.getLootValue());
		assertEquals("10 bones at 50 gp left out", Long.valueOf(200), hidden.getLootValue());
		assertEquals(Long.valueOf(200), hidden.getLootGe());
		assertEquals("high alch too", Long.valueOf(420), hidden.getLootHa());
		assertEquals("supplies unchanged", all.getSuppliesGe(), hidden.getSuppliesGe());
		assertEquals(Long.valueOf(-400), hidden.getProfit());
		assertTrue("the unpriced item still counts", hidden.isLootApproximate());

		HistoryPricing.Values ha = HistoryPricing.value(record(), LootPriceMode.HIGH_ALCH, null, HIDE_BONES);
		assertEquals(Long.valueOf(420), ha.getLootValue());
		assertEquals(Long.valueOf(-180), ha.getProfit());
	}

	@Test
	public void aHiddenUnpricedItemNoLongerMakesTheValueApproximate()
	{
		HistoryPricing.Values v = HistoryPricing.value(record(), LootPriceMode.GE, null,
			HiddenLoot.of(Collections.singleton(UNPRICED)));
		assertEquals(Long.valueOf(700), v.getLootValue());
		assertFalse(v.isLootApproximate());
		assertEquals(Long.valueOf(470 - 300), v.getLootHa());
	}

	@Test
	public void todaysPricesLeaveHiddenItemsOutToo()
	{
		Map<Integer, Long> today = map(RUNE, 150, BONES, 80, POTION, 800);
		assertEquals(Long.valueOf(300 + 800), HistoryPricing.value(record(), LootPriceMode.GE, today,
			HiddenLoot.NONE).getLootValue());
		assertEquals(Long.valueOf(300), HistoryPricing.value(record(), LootPriceMode.GE, today, HIDE_BONES)
			.getLootValue());
	}

	@Test
	public void todaysPricesInHighAlchLeaveHiddenItemsOutOfTheLootNotTheSupplies()
	{
		Map<Integer, Long> today = map(RUNE, 150, BONES, 80, POTION, 1_600);
		HistoryPricing.Values ha = HistoryPricing.value(record(), LootPriceMode.HIGH_ALCH, today, HIDE_BONES);
		assertEquals("2 runes at 60 and the 300 item; the bones (5 each) left out", Long.valueOf(420),
			ha.getLootValue());
		assertEquals("3 doses of a 1,600 gp 4-dose potion, today", Long.valueOf(1_200), ha.getSuppliesGe());
		assertEquals(Long.valueOf(420 - 1_200), ha.getProfit());
		assertEquals(Long.valueOf(470), HistoryPricing.value(record(), LootPriceMode.HIGH_ALCH, today,
			HiddenLoot.NONE).getLootValue());
	}

	@Test
	public void aRecordWithoutUnitPricesLeavesHiddenItemsOutOnlyAtGe()
	{
		LedgerRecord old = record();
		old.setUnitGePrices(null);
		old.setUnitHaPrices(null);
		HistoryPricing.Values v = HistoryPricing.value(old, LootPriceMode.GE, null, HIDE_BONES);
		assertEquals("at saved prices, nothing per item to leave out", Long.valueOf(700), v.getLootValue());
		assertEquals(Long.valueOf(470), v.getLootHa());

		// At today's prices its items are valued, so the bones are left out of the GE value; its high alch
		// value is its saved total, hidden items included.
		HistoryPricing.Values today = HistoryPricing.value(old, LootPriceMode.GE, map(RUNE, 150, BONES, 80),
			HIDE_BONES);
		assertEquals("2 runes at 150; the unpriced item left out and marked", Long.valueOf(300), today.getLootValue());
		assertFalse(today.isSavedTotals());
		assertTrue(today.isLootApproximate());
		assertEquals(Long.valueOf(470), today.getLootHa());

		// Saved without items: its saved totals, hidden items included, marked ~.
		old.setLoot(null);
		old.setSupplies(null);
		HistoryPricing.Values totals = HistoryPricing.value(old, LootPriceMode.GE, map(RUNE, 150, BONES, 80),
			HIDE_BONES);
		assertEquals(Long.valueOf(700), totals.getLootValue());
		assertTrue(totals.isSavedTotals());
		assertTrue(totals.isLootApproximate());
	}

	@Test
	public void nothingHiddenIsTheValueAsBefore()
	{
		for (LootPriceMode mode : LootPriceMode.values())
		{
			HistoryPricing.Values before = HistoryPricing.value(record(), mode, null);
			HistoryPricing.Values none = HistoryPricing.value(record(), mode, null, HiddenLoot.NONE);
			HistoryPricing.Values other = HistoryPricing.value(record(), mode, null,
				HiddenLoot.of(Collections.singleton(995)));
			for (HistoryPricing.Values v : new HistoryPricing.Values[]{none, other})
			{
				assertEquals(before.getLootValue(), v.getLootValue());
				assertEquals(before.getLootHa(), v.getLootHa());
				assertEquals(before.getLootUnpriced(), v.getLootUnpriced());
				assertEquals(before.getLootGeUnpriced(), v.getLootGeUnpriced());
			}
		}
		LedgerRecord record = record();
		HistoryPricing.value(record, LootPriceMode.GE, null, HIDE_BONES);
		assertEquals("the record is not changed", Long.valueOf(10), record.getLoot().get(BONES));
	}

	@Test
	public void rankingAndTotalsDropByTheHiddenValue()
	{
		List<LedgerRecord> records = Collections.singletonList(record());
		// Today's prices are the ones the task was saved with.
		Map<Integer, Long> saved = map(RUNE, 100, BONES, 50, POTION, 800);

		List<TaskRanking.Entry> ranking = TaskRanking.rank(records, TaskRanking.Metric.PROFIT, LootPriceMode.GE, saved,
			HIDE_BONES);
		assertEquals("profit/h: 200 - 600 over an hour", Long.valueOf(-400), ranking.get(0).getRatePerHour());
		assertEquals(Long.valueOf(100), TaskRanking.rank(records, TaskRanking.Metric.PROFIT, LootPriceMode.GE, saved,
			HiddenLoot.NONE).get(0).getRatePerHour());

		List<OverallTotals.Entry> totals = OverallTotals.summarize(records, OverallTotals.Grouping.ALL,
			LootPriceMode.GE, saved, HIDE_BONES, ZoneOffset.UTC);
		assertEquals(Long.valueOf(-400), totals.get(0).getProfit());
		assertEquals("the summed loot items still list the bones (the grid leaves them out)", 3,
			totals.get(0).getLoot().size());
		assertEquals(Long.valueOf(100), OverallTotals.summarize(records, OverallTotals.Grouping.ALL,
			LootPriceMode.GE, saved, HiddenLoot.NONE, ZoneOffset.UTC).get(0).getProfit());

		TaskAdvisor.Result advice = TaskAdvisor.advise(records, 410, "Kurask", TaskRanking.Metric.PROFIT,
			LootPriceMode.GE, saved, HIDE_BONES);
		assertEquals("one monster is not enough to advise on", null, advice);
	}

	// ---- Coins always count in the value (owner decision, 2026-10-28) ----

	private static final int COINS = ItemValuation.COINS;
	/** Coins and bones hidden, as Ground Items' built-in list hides them. */
	private static final HiddenLoot HIDE_COINS_AND_BONES = HiddenLoot.of(Arrays.asList(COINS, BONES));

	/** {@link #record()} with 500 coins more in the loot, priced at face value as {@link ItemValuation} saves them. */
	private static LedgerRecord recordWithCoins()
	{
		LedgerRecord r = record();
		r.setLoot(map(RUNE, 2, BONES, 10, UNPRICED, 1, COINS, 500));
		r.setUnitGePrices(map(RUNE, 100, BONES, 50, POTION, 800, COINS, 1));
		r.setUnitHaPrices(map(RUNE, 60, BONES, 5, UNPRICED, 300, POTION, 100, COINS, 1));
		r.setLootGeValue(1_200L);
		r.setLootHaValue(970L);
		return r;
	}

	@Test
	public void hiddenCoinsStillCountInSavedTasksValues()
	{
		HistoryPricing.Values ge = HistoryPricing.value(recordWithCoins(), LootPriceMode.GE, null,
			HIDE_COINS_AND_BONES);
		assertEquals("only the 10 bones at 50 gp are left out", Long.valueOf(1_200 - 500), ge.getLootValue());
		assertEquals(Long.valueOf(970 - 50), ge.getLootHa());
		assertEquals(Long.valueOf(700 - 600), ge.getProfit());

		HistoryPricing.Values ha = HistoryPricing.value(recordWithCoins(), LootPriceMode.HIGH_ALCH, null,
			HIDE_COINS_AND_BONES);
		assertEquals(Long.valueOf(920), ha.getLootValue());

		HistoryPricing.Values coinsOnly = HistoryPricing.value(recordWithCoins(), LootPriceMode.GE, null,
			HiddenLoot.of(Collections.singleton(COINS)));
		assertEquals("hidden coins alone change no value", Long.valueOf(1_200), coinsOnly.getLootValue());
		assertEquals(Long.valueOf(970), coinsOnly.getLootHa());

		Map<Integer, Long> today = map(RUNE, 150, BONES, 80, POTION, 800, COINS, 1);
		assertEquals("today's prices: coins at face value, bones left out", Long.valueOf(300 + 500),
			HistoryPricing.value(recordWithCoins(), LootPriceMode.GE, today, HIDE_COINS_AND_BONES).getLootValue());

		String line = TaskCompleteMessage.format(recordWithCoins(), LootPriceMode.GE, HIDE_COINS_AND_BONES, true);
		assertTrue(line, line.endsWith(", ~+100. Gz."));
	}

	@Test
	public void hiddenCoinsStillCountInTheRankingAndTotals()
	{
		List<LedgerRecord> records = Collections.singletonList(recordWithCoins());
		// Today's prices are the ones the task was saved with.
		Map<Integer, Long> today = map(RUNE, 100, BONES, 50, POTION, 800, COINS, 1);
		assertEquals("profit/h: 700 - 600 over an hour", Long.valueOf(100), TaskRanking.rank(records,
			TaskRanking.Metric.PROFIT, LootPriceMode.GE, today, HIDE_COINS_AND_BONES).get(0).getRatePerHour());

		OverallTotals.Entry totals = OverallTotals.summarize(records, OverallTotals.Grouping.ALL, LootPriceMode.GE,
			today, HIDE_COINS_AND_BONES, ZoneOffset.UTC).get(0);
		assertEquals(Long.valueOf(100), totals.getProfit());
		assertEquals("the summed loot items still list coins and bones (the grid hides them)", 4,
			totals.getLoot().size());
	}

	@Test
	public void hiddenCoinsStillCountInTheLiveValues()
	{
		SessionValuation valuation = new SessionValuation();
		ItemTally loot = new ItemTally();
		loot.add(BONES, 10);
		loot.add(RUNE, 1);
		loot.add(COINS, 2_500);
		SessionValuation.Values v = valuation.update("s1", loot, null, null, LootPriceMode.GE,
			HIDE_COINS_AND_BONES, new Lookup(), 0);
		assertEquals("the rune and the coins; the bones are left out", 1_000 + 2_500, v.getLootValue());
		assertEquals(600 + 2_500, v.getLootHa());
		assertEquals(3_500, v.getProfit());
		assertEquals("the grid still gets coins to hide", HIDE_COINS_AND_BONES, v.getHiddenLoot());
		assertEquals(1, HIDE_COINS_AND_BONES.visibleRows(v.getItems().lootRows(LootPriceMode.GE, null)).size());
	}

	// ---- The advisor ----

	/** A completed hour-long task of {@code monster} with {@code bones} bones at 1,000 gp and 1,000 gp of runes. */
	private static LedgerRecord advisorTask(String id, int creatureId, String monster, long bones)
	{
		LedgerRecord r = new LedgerRecord();
		r.setId(id);
		r.setMonster(monster);
		r.setCreatureId(creatureId);
		r.setKills(10);
		r.setActiveMillis(3_600_000L);
		r.setStartedAt(1_000L);
		r.setEndedAt(3_601_000L);
		r.setCompleted(true);
		r.setPartial(false);
		r.setLoot(bones > 0 ? map(RUNE, 10, BONES, bones) : map(RUNE, 10));
		r.setUnitGePrices(map(RUNE, 100, BONES, 1_000));
		r.setUnitHaPrices(map(RUNE, 60, BONES, 5));
		r.setLootGeValue(1_000L + bones * 1_000L);
		r.setLootHaValue(600L + bones * 5L);
		r.setSuppliesGeValue(0L);
		return r;
	}

	@Test
	public void theAdvisorRanksWithHiddenLootLeftOut()
	{
		List<LedgerRecord> records = new ArrayList<>();
		// Kurask earns its profit from bones; five other monsters earn 2,000 to 6,000 gp/h without any.
		for (int t = 0; t < 3; t++)
		{
			records.add(advisorTask("k" + t, 410, "Kurask", 100));
			for (int m = 1; m <= 5; m++)
			{
				LedgerRecord other = advisorTask("m" + m + t, 410 + m, "Monster " + m, 0);
				other.setLoot(map(RUNE, 10 + 10 * m));
				other.setLootGeValue(1_000L + 1_000L * m);
				records.add(other);
			}
		}

		TaskAdvisor.Result all = TaskAdvisor.advise(records, 410, "Kurask", TaskRanking.Metric.PROFIT,
			LootPriceMode.GE, null, HiddenLoot.NONE);
		assertEquals(TaskAdvisor.Advice.DO, all.getAdvice());
		assertEquals(101_000L, all.getRatePerHour());
		assertEquals("the highest of six", 6, all.getPosition());

		TaskAdvisor.Result hidden = TaskAdvisor.advise(records, 410, "Kurask", TaskRanking.Metric.PROFIT,
			LootPriceMode.GE, null, HIDE_BONES);
		assertEquals("only the runes count", 1_000L, hidden.getRatePerHour());
		assertEquals("now the lowest of six", 1, hidden.getPosition());
		assertEquals(6, hidden.getMonsters());
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, hidden.getAdvice());
	}

	@Test
	public void theTaskCompleteLineLeavesHiddenLootOut()
	{
		String all = TaskCompleteMessage.format(record(), LootPriceMode.GE);
		String hidden = TaskCompleteMessage.format(record(), LootPriceMode.GE, HIDE_BONES, true);
		assertTrue(all, all.endsWith(", ~+100. Gz."));
		assertTrue(hidden, hidden.endsWith(", ~-400. Gz."));
	}

	// ---- The live session ----

	/** GE 100 a bone, 1,000 a shark; high alch 5 a bone. */
	private static final class Lookup implements ItemValuation.PriceLookup
	{
		@Override
		public int canonicalize(int itemId)
		{
			return itemId;
		}

		@Override
		public long gePrice(int canonicalId)
		{
			return canonicalId == BONES ? 100 : canonicalId == RUNE ? 1_000 : 0;
		}

		@Override
		public long haPrice(int canonicalId)
		{
			return canonicalId == BONES ? 5 : 600;
		}

		@Override
		public boolean isTradeable(int canonicalId)
		{
			return true;
		}
	}

	@Test
	public void liveValuesLeaveHiddenLootOutAndFollowTheList()
	{
		SessionValuation valuation = new SessionValuation();
		Lookup lookup = new Lookup();
		ItemTally loot = new ItemTally();
		loot.add(BONES, 10);
		loot.add(RUNE, 1);
		SessionValuation.Values all = valuation.update("s1", loot, null, null, LootPriceMode.GE, HiddenLoot.NONE,
			lookup, 0);
		assertEquals(2_000, all.getLootValue());
		assertEquals(HiddenLoot.NONE, all.getHiddenLoot());

		SessionValuation.Values hidden = valuation.update("s1", loot, null, null, LootPriceMode.GE, HIDE_BONES,
			lookup, 1);
		assertNotEquals("another hidden list is valued again", all, hidden);
		assertEquals(1_000, hidden.getLootValue());
		assertEquals(1_000, hidden.getLootGe());
		assertEquals(600, hidden.getLootHa());
		assertEquals(1_000, hidden.getProfit());
		assertEquals(HIDE_BONES, hidden.getHiddenLoot());
		assertEquals("the item details still list the bones", 2,
			hidden.getItems().lootRows(LootPriceMode.GE, null).size());
		assertEquals("the tally is not changed", 10, loot.get(BONES));

		SessionValuation.Values ha = valuation.update("s1", loot, null, null, LootPriceMode.HIGH_ALCH, HIDE_BONES,
			lookup, 2);
		assertEquals(600, ha.getLootValue());

		SessionValuation.Values back = valuation.update("s1", loot, null, null, LootPriceMode.GE, HiddenLoot.NONE,
			lookup, 3);
		assertEquals(all, back);
	}
}
