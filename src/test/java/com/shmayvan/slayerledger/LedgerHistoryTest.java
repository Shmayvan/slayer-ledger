package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import org.junit.Test;

public class LedgerHistoryTest
{
	/** No region names: the Monster filter tests don't need them. */
	private static final IntFunction<String> NO_NAMES = region -> null;

	private static LedgerRecord record(String id, String monster, long endedAt)
	{
		LedgerRecord r = new LedgerRecord();
		r.setId(id);
		r.setMonster(monster);
		r.setKills(10);
		r.setStartedAt(endedAt - 1_000L);
		r.setEndedAt(endedAt);
		return r;
	}

	private static List<String> ids(List<LedgerRecord> records)
	{
		List<String> ids = new ArrayList<>();
		for (LedgerRecord r : records)
		{
			ids.add(r.getId());
		}
		return ids;
	}

	// ---- Sorting ----

	@Test
	public void sortsNewestFirstByEndTime()
	{
		List<LedgerRecord> records = Arrays.asList(
			record("old", "Gargoyles", 1_000L),
			record("new", "Kurask", 3_000L),
			record("mid", "Gargoyles", 2_000L));
		assertEquals(Arrays.asList("new", "mid", "old"), ids(LedgerHistory.sortNewestFirst(records)));
	}

	@Test
	public void sortIsStableForEqualEndTimesAndSkipsNulls()
	{
		LedgerRecord a = record("a", "Gargoyles", 5_000L);
		a.setStartedAt(1_000L);
		LedgerRecord b = record("b", "Gargoyles", 5_000L);
		b.setStartedAt(2_000L);
		LedgerRecord c = record("c", "Gargoyles", 5_000L);
		c.setStartedAt(2_000L);
		LedgerRecord noId = record(null, "Gargoyles", 5_000L);
		noId.setStartedAt(2_000L);
		List<LedgerRecord> records = Arrays.asList(a, null, c, noId, b);

		// later start first, then by id (null id sorts as empty)
		assertEquals(Arrays.asList(null, "b", "c", "a"), ids(LedgerHistory.sortNewestFirst(records)));
	}

	@Test
	public void sortDoesNotModifyInput()
	{
		List<LedgerRecord> records = Arrays.asList(record("old", "A", 1L), record("new", "A", 2L));
		LedgerHistory.sortNewestFirst(records);
		assertEquals(Arrays.asList("old", "new"), ids(records));
	}

	// ---- Filtering ----

	@Test
	public void filterOptionsAreAllThenDistinctMonstersAlphabetically()
	{
		List<LedgerRecord> records = Arrays.asList(
			record("1", "Kurask", 1L),
			record("2", "abyssal demons", 2L),
			record("3", "Kurask", 3L),
			record("4", "Black dragons", 4L),
			record("5", null, 5L));
		assertEquals(Arrays.asList("All", "abyssal demons", "Black dragons", "Kurask", "Unknown task"),
			LedgerHistory.filterOptions(records));
	}

	@Test
	public void filterOptionsForNoRecordsIsJustAll()
	{
		assertEquals(Collections.singletonList("All"), LedgerHistory.filterOptions(Collections.emptyList()));
	}

	@Test
	public void selectionIsKeptOnlyWhileTheMonsterRemains()
	{
		List<String> options = Arrays.asList("All", "Gargoyles", "Kurask");
		assertEquals("Kurask", LedgerHistory.keepSelection(options, "Kurask"));
		assertNull(LedgerHistory.keepSelection(options, "Hydras"));
		assertNull(LedgerHistory.keepSelection(options, null));
		assertNull(LedgerHistory.keepSelection(options, "All"));
		assertNull(LedgerHistory.keepSelection(Collections.singletonList("All"), "Kurask"));
	}

	@Test
	public void filterByMonsterKeepsOrder()
	{
		List<LedgerRecord> sorted = LedgerHistory.sortNewestFirst(Arrays.asList(
			record("g1", "Gargoyles", 1L),
			record("k1", "Kurask", 2L),
			record("g2", "Gargoyles", 3L)));
		assertEquals(Arrays.asList("g2", "g1"), ids(new HistoryFilter("Gargoyles", null, null).apply(sorted, SlayerSpots.NONE, NO_NAMES)));
		assertEquals(Collections.singletonList("k1"), ids(new HistoryFilter("Kurask", null, null).apply(sorted, SlayerSpots.NONE, NO_NAMES)));
		assertTrue(new HistoryFilter("Hydras", null, null).apply(sorted, SlayerSpots.NONE, NO_NAMES).isEmpty());
	}

	@Test
	public void nullFilterMeansAll()
	{
		List<LedgerRecord> sorted = LedgerHistory.sortNewestFirst(Arrays.asList(
			record("g1", "Gargoyles", 1L),
			record("k1", "Kurask", 2L)));
		assertEquals(Arrays.asList("k1", "g1"), ids(new HistoryFilter(null, null, null).apply(sorted, SlayerSpots.NONE, NO_NAMES)));
	}

	@Test
	public void unnamedRecordsMatchTheUnknownFilter()
	{
		List<LedgerRecord> records = Arrays.asList(record("x", null, 1L), record("y", "", 2L), record("z", "Kurask", 3L));
		assertEquals(Arrays.asList("x", "y"), ids(new HistoryFilter(LedgerHistory.UNKNOWN_MONSTER, null, null).apply(records, SlayerSpots.NONE, NO_NAMES)));
	}

	// ---- Formatting ----

	@Test
	public void killsShowOriginalAmountWhenKnown()
	{
		LedgerRecord r = record("1", "Kurask", 1L);
		r.setKills(42);
		r.setOriginalAmount(150);
		assertEquals("42 / 150", LedgerHistory.formatKills(r));
	}

	@Test
	public void killsAloneWhenOriginalAmountUnknown()
	{
		LedgerRecord r = record("1", "Kurask", 1L);
		r.setKills(42);
		r.setOriginalAmount(null);
		assertEquals("42", LedgerHistory.formatKills(r));
		r.setOriginalAmount(0);
		assertEquals("42", LedgerHistory.formatKills(r));
	}

	@Test
	public void durationIsHoursMinutesSeconds()
	{
		assertEquals("0:00:00", LedgerHistory.formatDuration(0));
		assertEquals("0:00:00", LedgerHistory.formatDuration(-5_000));
		assertEquals("0:00:59", LedgerHistory.formatDuration(59_999));
		assertEquals("0:01:05", LedgerHistory.formatDuration(65_000));
		assertEquals("1:02:03", LedgerHistory.formatDuration(3_723_000));
		assertEquals("27:00:00", LedgerHistory.formatDuration(27 * 3_600_000L));
	}

	@Test
	public void killTimeIsShortAndRoundedToWholeSeconds()
	{
		assertEquals("0s", LedgerHistory.formatKillTime(0));
		assertEquals("negative counts as none", "0s", LedgerHistory.formatKillTime(-5_000));
		assertEquals("0s", LedgerHistory.formatKillTime(499));
		assertEquals("1s", LedgerHistory.formatKillTime(500));
		assertEquals("18s", LedgerHistory.formatKillTime(17_600));
		assertEquals("rounds up to a whole minute", "1m 00s", LedgerHistory.formatKillTime(59_500));
		assertEquals("59s", LedgerHistory.formatKillTime(59_499));
		assertEquals("1m 05s", LedgerHistory.formatKillTime(65_000));
		assertEquals("4m 05s", LedgerHistory.formatKillTime(245_000));
		assertEquals("59m 59s", LedgerHistory.formatKillTime(3_599_499));
		assertEquals("1h 00m", LedgerHistory.formatKillTime(3_599_500));
		assertEquals("1h 02m", LedgerHistory.formatKillTime(3_720_000));
		assertEquals("minutes round half up", "1h 03m", LedgerHistory.formatKillTime(3_750_000));
		assertEquals("27h 00m", LedgerHistory.formatKillTime(27 * 3_600_000L));
		assertEquals("no overflow", "2562047788015h 13m", LedgerHistory.formatKillTime(Long.MAX_VALUE));
	}

	/** Only a partial task names its status: its tooltip says so; a completed one's says nothing. */
	@Test
	public void onlyAPartialTasksTooltipNamesItsStatus()
	{
		LedgerRecord r = record("1", "Kurask", 1L);
		r.setCompleted(true);
		assertFalse(savedTooltip(r), savedTooltip(r).contains(LedgerHistory.PARTIAL));
		assertFalse(savedTooltip(r), savedTooltip(r).contains("Completed"));
		r.setCompleted(false);
		r.setPartial(true);
		assertTrue(savedTooltip(r), savedTooltip(r).startsWith("<html>Kurask<br>" + LedgerHistory.PARTIAL_NOTE + "<br>"));
	}

	@Test
	public void endTimeUsesTheGivenZone()
	{
		LedgerRecord r = record("1", "Kurask", Instant.parse("2026-01-02T23:30:00Z").toEpochMilli());
		DateTimeFormatter pattern = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
		assertEquals("2026-01-02 23:30", LedgerHistory.formatEndedAt(r, pattern.withZone(ZoneId.of("UTC"))));
		assertEquals("2026-01-03 08:30", LedgerHistory.formatEndedAt(r, pattern.withZone(ZoneId.of("Asia/Tokyo"))));
	}

	@Test
	public void shortDateTimeIsTheLocalizedShortStyle()
	{
		ZoneId zone = ZoneId.of("Europe/London");
		LedgerRecord r = record("1", "Kurask", Instant.parse("2026-07-04T12:05:00Z").toEpochMilli());
		String expected = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.US).withZone(zone)
			.format(Instant.ofEpochMilli(r.getEndedAt()));
		assertEquals(expected, LedgerHistory.formatEndedAt(r, LedgerHistory.shortDateTime(zone).withLocale(Locale.US)));
		// 12:05 UTC is 13:05 BST
		assertTrue(expected, expected.contains("1:05"));
	}

	@Test
	public void missingEndTimeShowsDash()
	{
		assertEquals("-", LedgerHistory.formatEndedAt(record("1", "Kurask", 0L), DateTimeFormatter.ISO_INSTANT));
	}

	@Test
	public void deleteConfirmationText()
	{
		LedgerRecord r = record("1", "Abyssal demons", 1L);
		r.setKills(37);
		assertEquals("Delete this Abyssal demons task (37 kills)? This can't be undone.",
			LedgerHistory.deleteConfirmation(r));
	}

	// ---- Values ----

	private static LedgerRecord valued(Long lootGe, Long suppliesGe)
	{
		LedgerRecord r = record("v", "Gargoyles", 1_000L);
		r.setLootGeValue(lootGe);
		r.setLootHaValue(lootGe);
		r.setSuppliesGeValue(suppliesGe);
		r.setSuppliesHaValue(suppliesGe);
		return r;
	}

	/**
	 * {@code r}'s values at the prices saved with it, loot at GE: how the task-complete chat line values a record
	 * ({@link TaskCompleteMessage}); the formatting is the same as at today's prices.
	 */
	private static HistoryPricing.Values saved(LedgerRecord r)
	{
		return HistoryPricing.value(r, LootPriceMode.GE, null);
	}

	/** {@code r}'s Task history row tooltip at the prices saved with it, without a location line or an end time. */
	private static String savedTooltip(LedgerRecord r)
	{
		return tooltip(r, saved(r));
	}

	/** {@code r}'s Task history row tooltip at {@code v}, without a location line or an end time. */
	private static String tooltip(LedgerRecord r, HistoryPricing.Values v)
	{
		return LedgerHistory.historyRowTooltip(r, v, null, LedgerHistory.NO_VALUE);
	}

	/** The loot value as Task history shows it: the Loot received sub-header of a task's details. */
	private static String loot(HistoryPricing.Values v)
	{
		return LedgerFormat.amount(v.getLootValue(), v.isLootApproximate());
	}

	/** The supplies value as Task history shows it: the Supplies used sub-header of a task's details. */
	private static String supplies(HistoryPricing.Values v)
	{
		return LedgerFormat.amount(v.getSuppliesGe(), v.isSuppliesApproximate());
	}

	/** The profit as a Task history row shows it, signed. */
	private static String profit(HistoryPricing.Values v)
	{
		return LedgerFormat.signed(v.getProfit(), v.isProfitApproximate());
	}

	@Test
	public void compactGpFormatting()
	{
		assertEquals("0 gp", LedgerHistory.formatGp(0));
		assertEquals("9,450 gp", LedgerHistory.formatGp(9_450));
		assertEquals("350K gp", LedgerHistory.formatGp(350_000));
		assertEquals("1.23M gp", LedgerHistory.formatGp(1_234_567));
		assertEquals("-850K gp", LedgerHistory.formatGp(-850_000));
		assertEquals("2.14B gp", LedgerHistory.formatGp(2_147_483_647L));
		assertEquals("-", LedgerHistory.formatGp(null, false));
		assertEquals("-", LedgerHistory.formatGp(null, true));
		assertEquals("~1.23M gp", LedgerHistory.formatGp(1_234_567L, true));
		// No trailing zeros (DESIGN_PRINCIPLES 5.5), in every compact amount: gp, history amounts, XP rows, drops.
		assertEquals("1M gp", LedgerHistory.formatGp(1_000_000));
		assertEquals("58K", LedgerHistory.formatAmount(58_000L, false));
		assertEquals("~10K", LedgerHistory.formatAmount(10_000L, true));
		assertEquals("1,234,567 gp", LedgerHistory.formatGpExact(1_234_567));
	}

	@Test
	public void profitHandlesNullsAndSaturates()
	{
		assertNull(LedgerHistory.profit(null, null));
		assertNull("supplies without loot value", LedgerHistory.profit(null, 500L));
		assertEquals(Long.valueOf(1_000), LedgerHistory.profit(1_000L, null));
		assertEquals(Long.valueOf(-400), LedgerHistory.profit(100L, 500L));
		assertEquals(Long.valueOf(Long.MIN_VALUE), LedgerHistory.profit(-2L, Long.MAX_VALUE));
		assertEquals(Long.valueOf(Long.MAX_VALUE), LedgerHistory.profit(Long.MAX_VALUE, -1L));
	}

	/** The record's stored loot GE minus its stored supplies GE, or {@code null} when it was not valued. */
	private static Long storedProfitGe(LedgerRecord record)
	{
		return LedgerHistory.profit(LedgerHistory.lootGe(record), LedgerHistory.suppliesGe(record));
	}

	@Test
	public void recordSavedBeforeValuesShowsDashesNeverZero()
	{
		LedgerRecord old = record("old", "Kurask", 1_000L);
		assertFalse(LedgerHistory.isValued(old));
		assertNull(LedgerHistory.suppliesGe(old));
		assertNull(storedProfitGe(old));
		HistoryPricing.Values today = HistoryPricing.value(old, LootPriceMode.GE, Collections.emptyMap());
		assertEquals("-", loot(today));
		assertEquals("-", supplies(today));
		assertEquals("-", profit(today));
		assertFalse(tooltip(old, today).contains("0 gp"));
	}

	@Test
	public void lootValuedWithoutSuppliesMeansNoneUsed()
	{
		LedgerRecord r = valued(1_234_567L, null);
		assertEquals(Long.valueOf(0), LedgerHistory.suppliesGe(r));
		assertEquals(Long.valueOf(1_234_567), storedProfitGe(r));
		assertEquals("1.23M", loot(saved(r)));
		assertEquals("0", supplies(saved(r)));
		assertEquals("+1.23M", profit(saved(r)));
	}

	@Test
	public void negativeProfit()
	{
		LedgerRecord r = valued(50_000L, 400_000L);
		assertEquals(Long.valueOf(-350_000), storedProfitGe(r));
		assertEquals("-350K", profit(saved(r)));
		assertEquals("400K", supplies(saved(r)));
	}

	@Test
	public void unavailablePricesMarkTotalsApproximate()
	{
		LedgerRecord r = valued(10_000L, 2_000L);
		r.setSuppliesUnpricedItems(2);
		assertEquals("10K", loot(saved(r)));
		assertEquals("~2,000", supplies(saved(r)));
		assertEquals("~+8,000", profit(saved(r)));
		String tooltip = savedTooltip(r);
		assertTrue(tooltip, tooltip.contains("Some prices were unavailable (loot: 0, supplies: 2)"));
		assertTrue(tooltip, tooltip.contains("Loot: 10,000 gp GE, 10,000 gp high alch"));
		assertTrue(tooltip, tooltip.contains("Profit: 8,000 gp GE"));

		r.setSuppliesUnpricedItems(null);
		r.setLootUnpricedItems(1);
		assertEquals("~10K", loot(saved(r)));
		assertEquals("2,000", supplies(saved(r)));
		assertTrue(savedTooltip(r).contains("(loot: 1, supplies: 0), so totals may be too low"));

		// One item both looted and used as a supply is listed on each side, not added up to a total.
		r.setSuppliesUnpricedItems(1);
		assertTrue(savedTooltip(r).contains("(loot: 1, supplies: 1)"));
	}

	@Test
	public void rowTooltipSaysWhichPricesAndModeAreShown()
	{
		LedgerRecord r = valued(10_000L, 2_000L);
		r.setLoot(Collections.singletonMap(1, 2L));
		r.setUnitGePrices(Collections.singletonMap(1, 5_000L));
		r.setUnitHaPrices(Collections.singletonMap(1, 6_000L));
		r.setLootHaValue(12_000L);
		r.setSupplies(Collections.singletonMap(2, 1L));

		String saved = tooltip(r, HistoryPricing.value(r, LootPriceMode.GE, null));
		assertFalse("no saved-prices line", saved.contains("Prices:"));
		assertFalse(saved, saved.contains("Loot value ("));
		assertTrue(saved, saved.contains("Profit: 8,000 gp GE"));

		Map<Integer, Long> todayGe = new HashMap<>();
		todayGe.put(1, 7_000L);
		todayGe.put(2, 2_000L);
		HistoryPricing.Values today = HistoryPricing.value(r, LootPriceMode.HIGH_ALCH, todayGe);
		String current = tooltip(r, today);
		assertTrue(current, current.contains("<br>" + LedgerHistory.CURRENT_PRICES));
		assertTrue(current, current.contains("Loot: 14,000 gp GE, 12,000 gp high alch"));
		assertTrue(current, current.contains("Loot value (High alch): 12,000 gp"));
		assertTrue(current, current.contains("Profit: 10,000 gp (loot at high alch, supplies at GE)"));
		assertEquals("12K", loot(today));
		assertEquals("+10K", profit(today));

		// Saved without per-item prices: still valued from its items at today's prices.
		r.setUnitGePrices(null);
		r.setUnitHaPrices(null);
		HistoryPricing.Values revalued = HistoryPricing.value(r, LootPriceMode.GE, todayGe);
		String older = tooltip(r, revalued);
		assertTrue(older, older.contains("<br>" + LedgerHistory.CURRENT_PRICES));
		assertFalse(older, older.contains(LedgerHistory.SAVED_TOTALS));
		assertEquals("14K", loot(revalued));
		assertEquals("+12K", profit(revalued));

		// Saved without items: today's prices can't revalue it, so it keeps its saved totals.
		r.setLoot(null);
		r.setSupplies(null);
		HistoryPricing.Values kept = HistoryPricing.value(r, LootPriceMode.GE, todayGe);
		String old = tooltip(r, kept);
		assertTrue(old, old.contains("<br>" + LedgerHistory.SAVED_TOTALS));
		assertFalse(old, old.contains(LedgerHistory.CURRENT_PRICES));
		assertEquals("~10K", loot(kept));
		assertEquals("~+8,000", profit(kept));
	}

	@Test
	public void valueLabelsNameThePriceMode()
	{
		assertEquals("GE", LedgerHistory.priceLabel(LootPriceMode.GE));
		assertEquals("HA", LedgerHistory.priceLabel(LootPriceMode.HIGH_ALCH));
		assertEquals("null counts as GE", "GE", LedgerHistory.priceLabel(null));
		assertEquals("Profit (GE): ", LedgerHistory.profitLabel(LootPriceMode.GE));
		assertEquals("Profit (HA): ", LedgerHistory.profitLabel(LootPriceMode.HIGH_ALCH));

		// In High alch the loot is its high alch value and the supplies stay at GE; the ~ of an unpriced supply carries
		// to the profit, before its sign.
		LedgerRecord r = valued(10_000L, 2_000L);
		r.setLootHaValue(9_000L);
		r.setSuppliesUnpricedItems(1);
		HistoryPricing.Values ha = HistoryPricing.value(r, LootPriceMode.HIGH_ALCH, null);
		assertEquals("9,000", loot(ha));
		assertEquals("~2,000", supplies(ha));
		assertEquals("~+7,000", profit(ha));
	}

	// ---- Superiors and bank trips ----

	@Test
	public void countsShowDashWhenUnknownAndZeroWhenCounted()
	{
		assertEquals("-", LedgerHistory.formatCount(null));
		assertEquals("0", LedgerHistory.formatCount(0));
		assertEquals("Superiors: 3", LedgerHistory.formatSuperiors(3));
		assertEquals("Superiors: -", LedgerHistory.formatSuperiors(null));
		assertEquals("Bank trips: 0", LedgerHistory.formatBankTrips(0));
		assertEquals("Bank trips: -", LedgerHistory.formatBankTrips(null));
		assertEquals("Deaths: 1", LedgerHistory.formatDeaths(1));
		assertEquals("Deaths: -", LedgerHistory.formatDeaths(null));
	}

	@Test
	public void rowTooltipAddsTheCountsToTheValues()
	{
		LedgerRecord r = valued(10_000L, 2_000L);
		r.setSuperiorsSpawned(2);
		r.setBankTrips(0);
		r.setDeaths(1);
		String tooltip = savedTooltip(r);
		// The values right after the head (which ends with the XP), the counts last.
		assertTrue(tooltip, tooltip.contains("<br>XP: -<br>Loot: 10,000 gp GE"));
		assertTrue(tooltip, tooltip.endsWith("<br>Superiors: 2<br>Bank trips: 0<br>Deaths: 1</html>"));
		assertEquals(1, tooltip.split("<html>", -1).length - 1);

		LedgerRecord old = record("old", "Kurask", 1_000L);
		String oldTooltip = tooltip(old, HistoryPricing.value(old, LootPriceMode.GE, Collections.emptyMap()));
		assertTrue(oldTooltip, oldTooltip.endsWith("<br>XP: -<br>" + LedgerHistory.NOT_VALUED
			+ "<br>Superiors: -<br>Bank trips: -<br>Deaths: -</html>"));
	}

	/**
	 * A Task history row's tooltip in look A: first what the two-line row has no room for (the full
	 * name, the date and time, the exact active time, the kills, the XP), then the details.
	 */
	@Test
	public void historyRowTooltipStartsWithWhatTheRowHasNoRoomFor()
	{
		LedgerRecord r = valued(10_000L, 2_000L);
		r.setMonster("Thermonuclear <smoke> devil");
		r.setOriginalAmount(10);
		r.setActiveMillis(60 * 60_000L + 5_400L);
		r.setCompleted(true);
		r.setXpGained(Collections.singletonMap("SLAYER", 40_100L));
		String tooltip = LedgerHistory.historyRowTooltip(r, saved(r), "Smoke Dungeon", "10/28/26, 3:01 PM");
		// The row shows "1 h" (LedgerFormat.duration); the tooltip the exact time.
		assertEquals("<html>Thermonuclear &lt;smoke&gt; devil<br>10/28/26, 3:01 PM<br>Active: 1:00:05<br>Kills: 10 / 10"
			+ "<br>XP: 40.1K (40K/h)<br>Loot: 10,000 gp GE, 10,000 gp high alch<br>Supplies: 2,000 gp GE, 2,000 gp"
			+ " high alch<br>Profit: 8,000 gp GE<br>Location: Smoke Dungeon<br>Superiors: -<br>Bank trips: -"
			+ "<br>Deaths: -</html>", tooltip);
		assertEquals(1, tooltip.split("<html>", -1).length - 1);

		// A partial task says so; one saved before XP was tracked says "XP: -".
		LedgerRecord partial = record("p", "Kurask", 1_000L);
		String partialTip = LedgerHistory.historyRowTooltip(partial, saved(partial), null, "-");
		assertTrue(partialTip, partialTip.startsWith("<html>Kurask<br>" + LedgerHistory.PARTIAL_NOTE
			+ "<br>-<br>Active: 0:00:00<br>Kills: 10<br>XP: -<br>" + LedgerHistory.NOT_VALUED));
	}

	/** The values tooltip of a saved task's item lists' sub-headers: its row tooltip's first lines. */
	@Test
	public void theItemListsValuesTooltipIsTheRowsValues()
	{
		LedgerRecord r = valued(10_000L, 2_000L);
		assertEquals("<html>Loot: 10,000 gp GE, 10,000 gp high alch<br>Supplies: 2,000 gp GE, 2,000 gp high alch"
			+ "<br>Profit: 8,000 gp GE</html>", LedgerHistory.valuesTooltip(saved(r)));
		LedgerRecord old = record("old", "Kurask", 1_000L);
		assertEquals("<html>" + LedgerHistory.NOT_VALUED + "</html>", LedgerHistory.valuesTooltip(saved(old)));
	}

	@Test
	public void shortDateIsTheLocalizedShortDateWithoutTheTime()
	{
		ZoneId zone = ZoneId.of("Europe/London");
		LedgerRecord r = record("1", "Kurask", Instant.parse("2026-07-04T23:30:00Z").toEpochMilli());
		assertEquals("7/5/26, a day later in London",
			"7/5/26", LedgerHistory.formatEndedAt(r, LedgerHistory.shortDate(zone).withLocale(Locale.US)));
	}

	@Test
	public void rowTooltipShowsTheNoteEscaped()
	{
		LedgerRecord r = record("a", "Kurask", 1_000L);
		r.setNote("Barrage <b>& burst</b>");
		String tooltip = savedTooltip(r);
		assertTrue(tooltip, tooltip.endsWith("<br>XP: -<br>" + LedgerHistory.NOT_VALUED
			+ "<br>Superiors: -<br>Bank trips: -<br>Deaths: -<br>Note: Barrage &lt;b&gt;&amp; burst&lt;/b&gt;</html>"));
	}

	@Test
	public void editNotePromptNamesTheTaskAndLimit()
	{
		assertEquals("Note for this Kurask task (up to 60 characters; leave empty to remove it):",
			LedgerHistory.editNotePrompt(record("a", "Kurask", 1_000L)));
	}

	@Test
	public void timeLeftEarlyInATaskUsesTheHistoricalPace()
	{
		// 1 kill in 30 seconds: too little for the live pace, so 40 kills at the history's 45 seconds a kill.
		assertEquals("~30m", LedgerHistory.formatTimeLeft(30_000, 1, 40, 45_000L));
		assertTrue(LedgerHistory.isHistoricalTimeLeft(30_000, 1, 40, 45_000L));
		// 3 kills but under a minute: still the history.
		assertEquals("~30m", LedgerHistory.formatTimeLeft(59_999, 3, 40, 45_000L));
		// Saturates rather than overflowing, as TaskStatistics.estimate does.
		assertEquals(LedgerHistory.formatTimeLeftMinutes(Long.MAX_VALUE),
			LedgerHistory.formatTimeLeft(0, 0, Integer.MAX_VALUE, Long.MAX_VALUE));
	}

	@Test
	public void timeLeftMinutesRoundToTheNearestMinuteWithoutSeconds()
	{
		assertEquals("~<1m", LedgerHistory.formatTimeLeftMinutes(0));
		assertEquals("~<1m", LedgerHistory.formatTimeLeftMinutes(29_999));
		assertEquals("~<1m", LedgerHistory.formatTimeLeftMinutes(29_000));
		assertEquals("half a minute rounds up", "~1m", LedgerHistory.formatTimeLeftMinutes(30_000));
		assertEquals("~44m", LedgerHistory.formatTimeLeftMinutes(44 * 60_000L));
		assertEquals("~44m", LedgerHistory.formatTimeLeftMinutes(44 * 60_000L + 29_999));
		assertEquals("~45m", LedgerHistory.formatTimeLeftMinutes(44 * 60_000L + 30_000));
		assertEquals("~59m", LedgerHistory.formatTimeLeftMinutes(59 * 60_000L));
		assertEquals("59m 30s rounds up to the hour", "~1h 00m",
			LedgerHistory.formatTimeLeftMinutes(59 * 60_000L + 30_000));
		assertEquals("~1h 05m", LedgerHistory.formatTimeLeftMinutes(65 * 60_000L));
		assertEquals("~2h 00m", LedgerHistory.formatTimeLeftMinutes(120 * 60_000L));
		assertEquals("~3h 27m", LedgerHistory.formatTimeLeftMinutes((3 * 60 + 27) * 60_000L + 10_000));
		assertEquals("~27h 00m", LedgerHistory.formatTimeLeftMinutes(27 * 3_600_000L));
	}

	@Test
	public void timeLeftMinutesHandleTheSaturatedAndNegativeExtremes()
	{
		// The saturated historical estimate (historicalTimeLeftMillis): no overflow while rounding.
		assertEquals("~2562047788015h 13m", LedgerHistory.formatTimeLeftMinutes(Long.MAX_VALUE));
		assertEquals("~<1m", LedgerHistory.formatTimeLeftMinutes(-5_000));
		assertEquals("~<1m", LedgerHistory.formatTimeLeftMinutes(Long.MIN_VALUE));
	}

	@Test
	public void timeLeftEarlyInATaskWithoutHistoryIsEstimating()
	{
		assertEquals(LedgerHistory.TIME_LEFT_ESTIMATING, LedgerHistory.formatTimeLeft(30_000, 1, 40, null));
		assertFalse(LedgerHistory.isHistoricalTimeLeft(30_000, 1, 40, null));
	}

	@Test
	public void timeLeftUsesTheLivePaceAfterThreeKillsAndOneMinute()
	{
		// 3 kills in 1 minute: 20 seconds a kill, 40 left; the history's 45 seconds a kill is no longer used.
		assertEquals("~13m", LedgerHistory.formatTimeLeft(60_000, 3, 40, 45_000L));
		assertEquals("as without history", LedgerHistory.formatTimeLeft(60_000, 3, 40, null),
			LedgerHistory.formatTimeLeft(60_000, 3, 40, 45_000L));
		assertFalse(LedgerHistory.isHistoricalTimeLeft(60_000, 3, 40, 45_000L));
	}

	@Test
	public void timeLeftWithNoKillsLeftIsNoValueEvenWithHistory()
	{
		assertEquals(LedgerHistory.NO_VALUE, LedgerHistory.formatTimeLeft(30_000, 1, 0, 45_000L));
		assertEquals(LedgerHistory.NO_VALUE,
			LedgerHistory.formatTimeLeft(30_000, 1, SessionTracker.LiveTask.UNKNOWN_REMAINING, 45_000L));
		assertFalse(LedgerHistory.isHistoricalTimeLeft(30_000, 1, 0, 45_000L));
	}
}
