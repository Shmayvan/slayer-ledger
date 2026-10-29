package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class OverallTotalsTest
{
	@Test
	public void totalsOnlyIncludeCompletedPlayedTasksAndKeepUnknownMetricsUnknown()
	{
		LedgerRecord first = record(8, Instant.parse("2025-01-15T12:00:00Z").toEpochMilli(), 60_000L);
		first.setLootGeValue(200L);
		first.setSuppliesGeValue(50L);
		first.setXpGained(Collections.singletonMap("SLAYER", 100L));

		LedgerRecord legacy = record(null, Instant.parse("2025-01-20T12:00:00Z").toEpochMilli(), 120_000L);
		legacy.setLootGeValue(null);
		legacy.setXpGained(null);

		LedgerRecord partial = record(8, 0, 5_000L);
		partial.setPartial(true);
		LedgerRecord incomplete = record(8, 0, 5_000L);
		incomplete.setCompleted(false);
		LedgerRecord noKills = record(8, 0, 5_000L);
		noKills.setKills(0);

		OverallTotals.Entry total = OverallTotals.summarize(
			Arrays.asList(first, legacy, partial, incomplete, noKills),
			OverallTotals.Grouping.ALL, LootPriceMode.GE, Collections.emptyMap(), ZoneId.of("UTC")).get(0);

		assertEquals("Overall", total.getLabel());
		assertEquals(2, total.getTaskCount());
		assertEquals(180_000L, total.getActiveMillis());
		assertEquals(Long.valueOf(150L), total.getProfit());
		assertEquals(1, total.getProfitSampleCount());
		assertEquals(Long.valueOf(100L), total.getXp());
		assertEquals(1, total.getXpSampleCount());
	}

	@Test
	public void groupsByMasterAndLocalCompletionMonthWithUnknownBuckets()
	{
		LedgerRecord konar = record(SlayerMaster.KONAR, Instant.parse("2025-01-01T00:30:00Z").toEpochMilli(),
			60_000L);
		LedgerRecord unknownMasterAndDate = record(null, 0, 120_000L);

		List<OverallTotals.Entry> masters = OverallTotals.summarize(Arrays.asList(konar, unknownMasterAndDate),
			OverallTotals.Grouping.MASTER, LootPriceMode.GE, Collections.emptyMap(), ZoneId.of("America/Los_Angeles"));
		assertEquals(2, masters.size());
		assertEquals("Konar", masters.get(0).getLabel());
		assertEquals("Unknown master", masters.get(1).getLabel());

		List<OverallTotals.Entry> months = OverallTotals.summarize(Arrays.asList(konar, unknownMasterAndDate),
			OverallTotals.Grouping.MONTH, LootPriceMode.GE, Collections.emptyMap(), ZoneId.of("America/Los_Angeles"));
		assertEquals(2, months.size());
		assertEquals("2024-12", months.get(0).getLabel());
		assertEquals("Unknown date", months.get(1).getLabel());
		assertEquals(0, OverallTotals.summarize(Collections.emptyList(), OverallTotals.Grouping.ALL,
			LootPriceMode.GE, Collections.emptyMap(), ZoneId.of("UTC")).size());
	}

	@Test
	public void selectedProfitModeAndCurrentPricesAreUsed()
	{
		LedgerRecord record = record(SlayerMaster.KONAR, 1, 60_000L);
		record.setLoot(Collections.singletonMap(5, 2L));
		record.setSupplies(Collections.singletonMap(6, 1L));
		record.setUnitGePrices(Collections.singletonMap(5, 10L));
		record.setUnitHaPrices(Collections.singletonMap(5, 20L));
		record.setLootGeValue(20L);
		record.setLootHaValue(40L);
		record.setSuppliesGeValue(0L);

		// At the prices it was saved with (item 6 at 0).
		Map<Integer, Long> then = Map.of(5, 10L, 6, 0L);
		OverallTotals.Entry thenGe = OverallTotals.summarize(Collections.singletonList(record),
			OverallTotals.Grouping.ALL, LootPriceMode.GE, then, ZoneId.of("UTC")).get(0);
		assertEquals(Long.valueOf(20L), thenGe.getProfit());

		OverallTotals.Entry thenHa = OverallTotals.summarize(Collections.singletonList(record),
			OverallTotals.Grouping.ALL, LootPriceMode.HIGH_ALCH, then, ZoneId.of("UTC")).get(0);
		assertEquals(Long.valueOf(40L), thenHa.getProfit());

		OverallTotals.Entry today = OverallTotals.summarize(Collections.singletonList(record),
			OverallTotals.Grouping.ALL, LootPriceMode.GE, Map.of(5, 100L, 6, 5L),
			ZoneId.of("UTC")).get(0);
		assertEquals(Long.valueOf(195L), today.getProfit());
		assertNull(today.getXp());
		assertEquals(0, today.getXpSampleCount());
	}

	@Test
	public void unknownMastersSortAfterNamedMasters()
	{
		// Vannaka is SLAYER_MASTER 3; 99 is not in the name table.
		LedgerRecord unknown = record(null, 1, 60_000L);
		LedgerRecord none = record(SlayerMaster.NONE, 1, 60_000L);
		LedgerRecord unnamed = record(99, 1, 60_000L);
		LedgerRecord vannaka = record(3, 1, 60_000L);
		LedgerRecord konar = record(SlayerMaster.KONAR, 1, 60_000L);

		List<OverallTotals.Entry> masters = OverallTotals.summarize(Arrays.asList(unknown, none, unnamed, vannaka,
			konar), OverallTotals.Grouping.MASTER, LootPriceMode.GE, Collections.emptyMap(), ZoneId.of("UTC"));

		assertEquals(4, masters.size());
		assertEquals("Konar", masters.get(0).getLabel());
		assertEquals("Vannaka", masters.get(1).getLabel());
		assertEquals("Unknown master (99)", masters.get(2).getLabel());
		assertEquals("Unknown master", masters.get(3).getLabel());
		assertEquals(2, masters.get(3).getTaskCount());
	}

	@Test
	public void monthsAreNewestFirstWithUnknownDateLast()
	{
		LedgerRecord unknown = record(null, 0, 60_000L);
		LedgerRecord older = record(null, Instant.parse("2024-11-15T12:00:00Z").toEpochMilli(), 60_000L);
		LedgerRecord newer = record(null, Instant.parse("2025-02-15T12:00:00Z").toEpochMilli(), 60_000L);

		List<OverallTotals.Entry> months = OverallTotals.summarize(Arrays.asList(unknown, older, newer),
			OverallTotals.Grouping.MONTH, LootPriceMode.GE, Collections.emptyMap(), ZoneId.of("UTC"));

		assertEquals(3, months.size());
		assertEquals("2025-02", months.get(0).getLabel());
		assertEquals("2024-11", months.get(1).getLabel());
		assertEquals("Unknown date", months.get(2).getLabel());
	}

	@Test
	public void highAlchValuesAGeZeroItemAtItsAlchValue()
	{
		// Item 7 is untradeable (GE price 0, alch 30); item 5 is tradeable. Supplies are GE-valued in every mode.
		LedgerRecord record = record(SlayerMaster.KONAR, 1, 60_000L);
		Map<Integer, Long> loot = new HashMap<>();
		loot.put(5, 2L);
		loot.put(7, 3L);
		record.setLoot(loot);
		record.setSupplies(Collections.singletonMap(6, 1L));
		Map<Integer, Long> ge = new HashMap<>();
		ge.put(5, 10L);
		ge.put(6, 4L);
		ge.put(7, 0L);
		record.setUnitGePrices(ge);
		Map<Integer, Long> ha = new HashMap<>();
		ha.put(5, 20L);
		ha.put(7, 30L);
		record.setUnitHaPrices(ha);
		record.setLootGeValue(110L);
		record.setLootHaValue(130L);
		record.setSuppliesGeValue(4L);

		// Today's prices are the saved ones.
		OverallTotals.Entry highAlch = OverallTotals.summarize(Collections.singletonList(record),
			OverallTotals.Grouping.ALL, LootPriceMode.HIGH_ALCH, ge, ZoneId.of("UTC")).get(0);
		assertEquals(Long.valueOf(2 * 20 + 3 * 30 - 4), highAlch.getProfit());
		assertFalse(highAlch.isProfitApproximate());

		// GE mode values the GE-0 item at its alch value too (owner decision, 2026-10-25).
		OverallTotals.Entry grandExchange = OverallTotals.summarize(Collections.singletonList(record),
			OverallTotals.Grouping.ALL, LootPriceMode.GE, ge, ZoneId.of("UTC")).get(0);
		assertEquals(Long.valueOf(2 * 10 + 3 * 30 - 4), grandExchange.getProfit());
	}

	@Test
	public void xpIsTheTotalOfAllSkills()
	{
		LedgerRecord record = record(SlayerMaster.KONAR, 1, 60_000L);
		Map<String, Long> xp = new HashMap<>();
		xp.put("SLAYER", 100L);
		xp.put("HITPOINTS", 40L);
		xp.put("RANGED", 120L);
		record.setXpGained(xp);

		OverallTotals.Entry total = OverallTotals.summarize(Collections.singletonList(record),
			OverallTotals.Grouping.ALL, LootPriceMode.GE, Collections.emptyMap(), ZoneId.of("UTC")).get(0);
		assertEquals(Long.valueOf(260L), total.getXp());
		assertEquals(1, total.getXpSampleCount());
	}

	private static LedgerRecord record(Integer master, long endedAt, long activeMillis)
	{
		LedgerRecord record = new LedgerRecord();
		record.setSlayerMaster(master);
		record.setEndedAt(endedAt);
		record.setKills(10);
		record.setActiveMillis(activeMillis);
		record.setCompleted(true);
		record.setPartial(false);
		return record;
	}
}
