package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** The By monster and By master figures of {@link OverallTotals}. */
public class OverallTotalsGroupingsTest
{
	private static final ZoneId UTC = ZoneId.of("UTC");
	private static final long MINUTE = 60_000L;
	private static final int COINS = ItemValuation.COINS;
	private static final int BONES = 526;
	/** SLAYER_MASTER values ({@link SlayerMaster}). */
	private static final int DURADEL = 5;
	private static final int NIEVE = 6;
	/** Today's GE prices of the {@link SavedItems} fixture items. */
	private static final Map<Integer, Long> TODAY = SavedItems.todaysPrices();

	// ---- By monster ----

	@Test
	public void byMonsterPoolsItsRatesAsTrendsDoes()
	{
		LedgerRecord first = record("Dust devils", SlayerMaster.KONAR, 100, 30 * MINUTE);
		valued(first, 100_000L, 20_000L);
		Map<String, Long> xp = new HashMap<>();
		xp.put(SkillXp.SLAYER, 10_000L);
		xp.put("HITPOINTS", 3_000L);
		first.setXpGained(xp);
		LedgerRecord old = record("Dust devils", SlayerMaster.KONAR, 50, 15 * MINUTE);
		valued(old, 40_000L, 10_000L);
		// Saved before XP was tracked: left out of the XP lines, not counted as 0.
		old.setXpGained(null);
		// No active time: not a Trends sample, so out of the rates, but in the totals and averages.
		LedgerRecord untimed = record("Dust devils", SlayerMaster.KONAR, 20, 0);
		valued(untimed, 10_000L, 0L);
		untimed.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 500L));

		OverallTotals.Entry group = single(OverallTotals.summarize(Arrays.asList(first, old, untimed),
			OverallTotals.Grouping.MONSTER, LootPriceMode.GE, TODAY, UTC));

		assertEquals("Dust devils", group.getLabel());
		assertEquals(3, group.getTaskCount());
		assertEquals(170, group.getKills());
		assertEquals(15 * MINUTE, group.getAverageActiveMillis());
		assertEquals(Long.valueOf(40_000L), group.getAverageProfit());
		assertEquals(3, group.getProfitSampleCount());
		// Pooled over the timed tasks: 110,000 gp over 150 kills and 45 minutes.
		assertEquals(Long.valueOf(733L), group.getProfitPerKill());
		assertEquals(Long.valueOf(18_000L), group.getMillisPerKill());
		assertEquals(Long.valueOf(146_667L), group.getProfitPerHour());
		assertFalse(group.isProfitRateApproximate());
		// Slayer XP only (not Hitpoints), of the 2 tasks that recorded XP; per hour from the timed one.
		assertEquals(Long.valueOf(10_500L), group.getSlayerXp());
		assertEquals(2, group.getSlayerXpSampleCount());
		assertEquals(Long.valueOf(20_000L), group.getSlayerXpPerHour());
		assertTrue("By monster counts no assignments", group.getAssignments().isEmpty());

		// The same figures as the Trends list.
		assertEquals(TaskRanking.rank(Arrays.asList(first, old, untimed), TaskRanking.Metric.PROFIT,
			LootPriceMode.GE, TODAY).get(0).getRatePerHour(), group.getProfitPerHour());
		assertEquals(TaskRanking.rank(Arrays.asList(first, old, untimed), TaskRanking.Metric.PROFIT_PER_KILL,
			LootPriceMode.GE, TODAY).get(0).getRatePerHour(), group.getProfitPerKill());
		assertEquals(TaskRanking.rank(Arrays.asList(first, old, untimed), TaskRanking.Metric.XP,
			LootPriceMode.GE, TODAY).get(0).getRatePerHour(), group.getSlayerXpPerHour());
	}

	@Test
	public void hourlyRatesNeedAMinuteOfActiveTime()
	{
		LedgerRecord quick = record("Kalphite", SlayerMaster.KONAR, 10, 59_000L);
		valued(quick, 5_000L, 0L);
		quick.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 100L));

		OverallTotals.Entry group = single(OverallTotals.summarize(Collections.singletonList(quick),
			OverallTotals.Grouping.MONSTER, LootPriceMode.GE, TODAY, UTC));

		assertNull(group.getProfitPerHour());
		assertNull(group.getSlayerXpPerHour());
		// Per kill needs no minimum time.
		assertEquals(Long.valueOf(500L), group.getProfitPerKill());
		assertEquals(Long.valueOf(5_900L), group.getMillisPerKill());
	}

	@Test
	public void missingDataLeavesTheLinesUnknown()
	{
		// Saved before values, XP and points were tracked.
		LedgerRecord legacy = record("Nechryael", null, 10, 10 * MINUTE);

		OverallTotals.Entry group = single(OverallTotals.summarize(Collections.singletonList(legacy),
			OverallTotals.Grouping.MONSTER, LootPriceMode.GE, TODAY, UTC));

		assertEquals(10, group.getKills());
		assertEquals(Long.valueOf(MINUTE), group.getMillisPerKill());
		assertNull(group.getAverageProfit());
		assertEquals(0, group.getProfitSampleCount());
		assertNull(group.getProfitPerKill());
		assertNull(group.getProfitPerHour());
		assertNull(group.getSlayerXp());
		assertNull(group.getAverageSlayerXp());
		assertNull(group.getSlayerXpPerHour());
		assertNull(group.getPoints());
		assertNull(group.getAveragePoints());
	}

	@Test
	public void variantsAndCannonTasksAreTheirOwnMonstersMostTasksFirst()
	{
		List<LedgerRecord> records = new ArrayList<>();
		for (int i = 0; i < 3; i++)
		{
			LedgerRecord cannon = record("Dust devils", SlayerMaster.KONAR, 10, MINUTE);
			cannon.setSupplies(Collections.singletonMap(SupplyTracker.CANNONBALL, 100L));
			records.add(cannon);
		}
		records.add(record("Dust devils", SlayerMaster.KONAR, 10, MINUTE));
		// The same monster written another way groups with it.
		records.add(record(" dust devils ", SlayerMaster.KONAR, 10, MINUTE));
		for (int i = 0; i < 2; i++)
		{
			LedgerRecord deviant = record("Aberrant spectres", SlayerMaster.KONAR, 10, MINUTE);
			deviant.setVariant("Deviant spectres");
			records.add(deviant);
			records.add(record("Abyssal demons", SlayerMaster.KONAR, 10, MINUTE));
		}
		records.add(record("Aberrant spectres", SlayerMaster.KONAR, 10, MINUTE));
		LedgerRecord deviantCannon = record("Aberrant spectres", SlayerMaster.KONAR, 10, MINUTE);
		deviantCannon.setVariant("Deviant spectres");
		deviantCannon.setSupplies(Collections.singletonMap(SupplyTracker.GRANITE_CANNONBALL, 5L));
		records.add(deviantCannon);

		List<OverallTotals.Entry> monsters = OverallTotals.summarize(records, OverallTotals.Grouping.MONSTER,
			LootPriceMode.GE, TODAY, UTC);

		assertEquals(Arrays.asList("Dust devils (cannon)", "Abyssal demons", "Deviant spectres", "Dust devils",
			"Aberrant spectres", "Deviant spectres (cannon)"), labels(monsters));
		assertEquals(Arrays.asList(3, 2, 2, 2, 1, 1), taskCounts(monsters));
	}

	@Test
	public void byMonsterValuesTodaysPricesWithoutHiddenLootButWithCoins()
	{
		LedgerRecord record = record("Abyssal demons", SlayerMaster.KONAR, 10, 60 * MINUTE);
		Map<Integer, Long> loot = new HashMap<>();
		loot.put(COINS, 1_000L);
		loot.put(BONES, 10L);
		record.setLoot(loot);
		record.setUnitGePrices(map(COINS, 1, BONES, 50));
		record.setUnitHaPrices(map(COINS, 1, BONES, 1));
		record.setLootGeValue(1_500L);
		record.setLootHaValue(1_010L);
		record.setSuppliesGeValue(0L);
		// Today's prices price coins at face value, as the plugin's lookup does.
		Map<Integer, Long> today = map(BONES, 100, COINS, 1);

		OverallTotals.Entry all = single(OverallTotals.summarize(Collections.singletonList(record),
			Collections.emptyList(), OverallTotals.Grouping.MONSTER, LootPriceMode.GE, today, HiddenLoot.NONE, UTC));
		assertEquals(Long.valueOf(2_000L), all.getAverageProfit());
		assertEquals(Long.valueOf(200L), all.getProfitPerKill());
		assertEquals(Long.valueOf(2_000L), all.getProfitPerHour());

		// Ground Items hides coins and bones: the bones leave the value, the coins still count.
		OverallTotals.Entry hidden = single(OverallTotals.summarize(Collections.singletonList(record),
			Collections.emptyList(), OverallTotals.Grouping.MONSTER, LootPriceMode.GE, today,
			HiddenLoot.of(Arrays.asList(COINS, BONES)), UTC));
		assertEquals(Long.valueOf(1_000L), hidden.getAverageProfit());
		assertEquals(Long.valueOf(100L), hidden.getProfitPerKill());
		assertEquals(Long.valueOf(1_000L), hidden.getProfitPerHour());
	}

	@Test
	public void highAlchValuesTheLootAtItsAlchValue()
	{
		LedgerRecord record = record("Gargoyles", SlayerMaster.KONAR, 10, 60 * MINUTE);
		record.setLoot(Collections.singletonMap(BONES, 10L));
		record.setSupplies(Collections.singletonMap(385, 2L));
		record.setUnitGePrices(map(BONES, 50, 385, 100));
		record.setUnitHaPrices(map(BONES, 300, 385, 1));
		record.setLootGeValue(500L);
		record.setLootHaValue(3_000L);
		record.setSuppliesGeValue(200L);
		record.setSuppliesHaValue(2L);
		Map<Integer, Long> today = map(BONES, 50, 385, 100);

		for (OverallTotals.Grouping grouping : Arrays.asList(OverallTotals.Grouping.MONSTER,
			OverallTotals.Grouping.MASTER))
		{
			OverallTotals.Entry entry = single(OverallTotals.summarize(Collections.singletonList(record),
				grouping, LootPriceMode.HIGH_ALCH, today, UTC));
			// Loot at high alch, supplies at GE.
			assertEquals(grouping.toString(), Long.valueOf(2_800L), entry.getAverageProfit());
			assertEquals(grouping.toString(), Long.valueOf(2_800L), entry.getProfitPerHour());
			assertEquals(grouping.toString(), Long.valueOf(2_800L), entry.getProfit());
		}
		OverallTotals.Entry monster = single(OverallTotals.summarize(Collections.singletonList(record),
			OverallTotals.Grouping.MONSTER, LootPriceMode.HIGH_ALCH, today, UTC));
		assertEquals(Long.valueOf(280L), monster.getProfitPerKill());
	}

	@Test
	public void anUnavailablePriceMarksTheRatesApproximate()
	{
		LedgerRecord record = record("Kurask", SlayerMaster.KONAR, 10, 60 * MINUTE);
		valued(record, 1_000L, 0L);
		// Bones have no price today.
		Map<Integer, Long> loot = new HashMap<>(record.getLoot());
		loot.put(BONES, 5L);
		record.setLoot(loot);

		OverallTotals.Entry group = single(OverallTotals.summarize(Collections.singletonList(record),
			OverallTotals.Grouping.MONSTER, LootPriceMode.GE, TODAY, UTC));

		assertTrue(group.isProfitApproximate());
		assertTrue(group.isProfitRateApproximate());
	}

	@Test
	public void onlyCompletedTasksCount()
	{
		LedgerRecord done = record("Kurask", SlayerMaster.KONAR, 10, MINUTE);
		LedgerRecord partial = record("Kurask", SlayerMaster.KONAR, 10, MINUTE);
		partial.setPartial(true);
		LedgerRecord incomplete = record("Kurask", SlayerMaster.KONAR, 10, MINUTE);
		incomplete.setCompleted(false);

		OverallTotals.Entry group = single(OverallTotals.summarize(Arrays.asList(done, partial, incomplete),
			OverallTotals.Grouping.MONSTER, LootPriceMode.GE, TODAY, UTC));
		assertEquals(1, group.getTaskCount());
		assertEquals(10, group.getKills());
	}

	// ---- By master ----

	@Test
	public void byMasterTotalsAveragesAndRates()
	{
		LedgerRecord first = record("Dust devils", SlayerMaster.KONAR, 100, 40 * MINUTE);
		valued(first, 300_000L, 50_000L);
		first.setPointsEarned(18);
		first.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 12_000L));
		LedgerRecord milestone = record("Abyssal demons", SlayerMaster.KONAR, 120, 20 * MINUTE);
		valued(milestone, 100_000L, 10_000L);
		// A bonus task: its points count in the total, but not the average (as Points/h in Trends).
		milestone.setPointsEarned(270);
		milestone.setTaskStreak(10);
		milestone.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 15_000L));
		// Saved before points and XP were tracked.
		LedgerRecord older = record("Abyssal demons", SlayerMaster.KONAR, 80, 30 * MINUTE);
		valued(older, 50_000L, 20_000L);

		OverallTotals.Entry konar = single(OverallTotals.summarize(Arrays.asList(first, milestone, older),
			OverallTotals.Grouping.MASTER, LootPriceMode.GE, TODAY, UTC));

		assertEquals("Konar", konar.getLabel());
		assertEquals(3, konar.getTaskCount());
		assertEquals(30 * MINUTE, konar.getAverageActiveMillis());
		assertEquals(Long.valueOf(370_000L), konar.getProfit());
		assertEquals(3, konar.getProfitSampleCount());
		assertEquals(Long.valueOf(123_333L), konar.getAverageProfit());
		// 370,000 gp over 90 minutes.
		assertEquals(Long.valueOf(246_667L), konar.getProfitPerHour());
		assertEquals(Long.valueOf(288L), konar.getPoints());
		assertEquals(2, konar.getPointsSampleCount());
		assertEquals(Long.valueOf(18L), konar.getAveragePoints());
		assertEquals(1, konar.getAveragePointsSampleCount());
		assertEquals(Long.valueOf(27_000L), konar.getSlayerXp());
		assertEquals(2, konar.getSlayerXpSampleCount());
		assertEquals(Long.valueOf(13_500L), konar.getAverageSlayerXp());
	}

	@Test
	public void monstersAssignedCountCompletedTasksAndEveryLogEntry()
	{
		List<LedgerRecord> records = new ArrayList<>();
		for (int i = 0; i < 3; i++)
		{
			records.add(record("Dust devils", SlayerMaster.KONAR, 10, MINUTE));
		}
		// A cannon task and a Catacombs variant count as their task.
		LedgerRecord cannon = record("Dust devils", SlayerMaster.KONAR, 10, MINUTE);
		cannon.setSupplies(Collections.singletonMap(SupplyTracker.CANNONBALL, 50L));
		records.add(cannon);
		LedgerRecord deviant = record("Aberrant spectres", SlayerMaster.KONAR, 10, MINUTE);
		deviant.setVariant("Deviant spectres");
		records.add(deviant);
		// A partial session is not an assignment of its own; another master's tasks are not Konar's.
		LedgerRecord partial = record("Abyssal demons", SlayerMaster.KONAR, 10, MINUTE);
		partial.setPartial(true);
		partial.setCompleted(false);
		records.add(partial);
		records.add(record("Gargoyles", DURADEL, 10, MINUTE));

		List<AssignmentEntry> entries = Arrays.asList(
			entry("Abyssal demons", SlayerMaster.KONAR, AssignmentOutcome.SKIPPED),
			entry("Abyssal demons", SlayerMaster.KONAR, AssignmentOutcome.BLOCKED),
			entry("Dust devils", SlayerMaster.KONAR, AssignmentOutcome.COMPLETED),
			entry("Aberrant spectres", SlayerMaster.KONAR, null),
			entry("Gargoyles", DURADEL, AssignmentOutcome.SKIPPED),
			// A master without a completed task has no group.
			entry("Gargoyles", NIEVE, AssignmentOutcome.SKIPPED));

		List<OverallTotals.Entry> masters = OverallTotals.summarize(records, entries, OverallTotals.Grouping.MASTER,
			LootPriceMode.GE, TODAY, HiddenLoot.NONE, UTC);
		assertEquals(Arrays.asList("Duradel/Kuradal", "Konar"), labels(masters));

		OverallTotals.Entry konar = masters.get(1);
		assertEquals(9, konar.getAssignmentTotal());
		List<OverallTotals.Assignment> assigned = konar.getAssignments();
		assertEquals(3, assigned.size());
		assertEquals(new OverallTotals.Assignment("Dust devils", 5, 9, 4,
			Collections.singletonMap(AssignmentOutcome.COMPLETED, 1)), assigned.get(0));
		assertEquals("5 / 9 (56%)", assigned.get(0).text());
		assertEquals("Aberrant spectres", assigned.get(1).getName());
		assertEquals("2 / 9 (22%)", assigned.get(1).text());
		assertEquals("Abyssal demons", assigned.get(2).getName());
		assertEquals("2 / 9 (22%)", assigned.get(2).text());

		String tooltip = assigned.get(2).tooltip("Konar");
		assertTrue(tooltip, tooltip.contains("Abyssal demons<br>2 of 9 assignments from Konar (22.2%)"));
		assertTrue(tooltip, tooltip.contains("<br>Skipped: 1<br>Blocked: 1"));
		assertFalse(tooltip, tooltip.contains("Completed: "));
		// An entry whose outcome is unknown counts as "Ended".
		assertTrue(assigned.get(1).tooltip("Konar"), assigned.get(1).tooltip("Konar")
			.contains("<br>Completed: 1<br>Ended: 1"));

		OverallTotals.Entry duradel = masters.get(0);
		assertEquals(2, duradel.getAssignmentTotal());
		assertEquals("2 / 2 (100%)", duradel.getAssignments().get(0).text());
	}

	@Test
	public void averagePointsLeaveOutOnlyBonusTasks()
	{
		LedgerRecord noStreak = record("Dust devils", SlayerMaster.KONAR, 10, MINUTE);
		noStreak.setPointsEarned(15);
		LedgerRecord ninth = record("Dust devils", SlayerMaster.KONAR, 10, MINUTE);
		ninth.setPointsEarned(18);
		ninth.setTaskStreak(9);
		LedgerRecord fiftieth = record("Dust devils", SlayerMaster.KONAR, 10, MINUTE);
		fiftieth.setPointsEarned(900);
		fiftieth.setTaskStreak(50);

		OverallTotals.Entry konar = single(OverallTotals.summarize(Arrays.asList(noStreak, ninth, fiftieth),
			OverallTotals.Grouping.MASTER, LootPriceMode.GE, TODAY, UTC));
		assertEquals(Long.valueOf(933L), konar.getPoints());
		assertEquals(3, konar.getPointsSampleCount());
		// (15 + 18) / 2, rounded.
		assertEquals(Long.valueOf(17L), konar.getAveragePoints());
		assertEquals(2, konar.getAveragePointsSampleCount());

		// Only bonus tasks: a total, but no average.
		OverallTotals.Entry onlyBonus = single(OverallTotals.summarize(Collections.singletonList(fiftieth),
			OverallTotals.Grouping.MASTER, LootPriceMode.GE, TODAY, UTC));
		assertEquals(Long.valueOf(900L), onlyBonus.getPoints());
		assertNull(onlyBonus.getAveragePoints());
	}

	@Test
	public void aCompletedTaskWithoutKillsIsNoAssignment()
	{
		LedgerRecord played = record("Dust devils", SlayerMaster.KONAR, 10, MINUTE);
		// Not a task the groups count, so not an assignment either: the two counts agree.
		LedgerRecord noKills = record("Kurask", SlayerMaster.KONAR, 0, MINUTE);

		OverallTotals.Entry konar = single(OverallTotals.summarize(Arrays.asList(played, noKills),
			Collections.emptyList(), OverallTotals.Grouping.MASTER, LootPriceMode.GE, TODAY, HiddenLoot.NONE, UTC));
		assertEquals(1, konar.getTaskCount());
		assertEquals(1, konar.getAssignmentTotal());
		assertEquals("Dust devils", konar.getAssignments().get(0).getName());
	}

	@Test
	public void sharesRoundToWholePercents()
	{
		assertEquals("12 / 80 (15%)", OverallTotals.shareText(12, 80));
		// Half up.
		assertEquals("1 / 8 (13%)", OverallTotals.shareText(1, 8));
		assertEquals("1 / 3 (33%)", OverallTotals.shareText(1, 3));
		assertEquals("2 / 3 (67%)", OverallTotals.shareText(2, 3));
		assertEquals("1 / 250 (<1%)", OverallTotals.shareText(1, 250));
		assertEquals("1 / 200 (1%)", OverallTotals.shareText(1, 200));
		assertEquals("0 / 0 (-)", OverallTotals.shareText(0, 0));
	}

	@Test
	public void assignmentNamesMatchIgnoringCaseAndUnknownNamesGroupTogether()
	{
		LedgerRecord record = record("Dust devils", SlayerMaster.KONAR, 10, MINUTE);
		List<AssignmentEntry> entries = Arrays.asList(
			entry("dust devils ", SlayerMaster.KONAR, AssignmentOutcome.SKIPPED),
			entry(null, SlayerMaster.KONAR, AssignmentOutcome.SKIPPED),
			entry(" ", SlayerMaster.KONAR, AssignmentOutcome.RESET));

		List<OverallTotals.Assignment> assigned = single(OverallTotals.summarize(Collections.singletonList(record),
			entries, OverallTotals.Grouping.MASTER, LootPriceMode.GE, TODAY, HiddenLoot.NONE, UTC)).getAssignments();

		assertEquals(2, assigned.size());
		// Ties by name: "Dust devils" (the record's name, seen first) before "Unknown task".
		assertEquals("Dust devils", assigned.get(0).getName());
		assertEquals("2 / 4 (50%)", assigned.get(0).text());
		assertEquals(LedgerHistory.UNKNOWN_MONSTER, assigned.get(1).getName());
		assertEquals("2 / 4 (50%)", assigned.get(1).text());
	}

	@Test
	public void entriesWithoutAMasterCountUnderUnknownMaster()
	{
		LedgerRecord record = record("Dust devils", null, 10, MINUTE);
		List<AssignmentEntry> entries = Collections.singletonList(entry("Kurask", null, AssignmentOutcome.SKIPPED));

		OverallTotals.Entry unknown = single(OverallTotals.summarize(Collections.singletonList(record), entries,
			OverallTotals.Grouping.MASTER, LootPriceMode.GE, TODAY, HiddenLoot.NONE, UTC));

		assertEquals(OverallTotals.UNKNOWN_MASTER, unknown.getLabel());
		assertEquals(2, unknown.getAssignmentTotal());
	}

	@Test
	public void otherGroupingsCountNoAssignments()
	{
		LedgerRecord record = record("Dust devils", SlayerMaster.KONAR, 10, MINUTE);
		List<AssignmentEntry> entries = Collections.singletonList(
			entry("Kurask", SlayerMaster.KONAR, AssignmentOutcome.SKIPPED));
		for (OverallTotals.Grouping grouping : Arrays.asList(OverallTotals.Grouping.ALL,
			OverallTotals.Grouping.MONTH, OverallTotals.Grouping.MONSTER))
		{
			OverallTotals.Entry entry = single(OverallTotals.summarize(Collections.singletonList(record), entries,
				grouping, LootPriceMode.GE, TODAY, HiddenLoot.NONE, UTC));
			assertTrue(grouping.toString(), entry.getAssignments().isEmpty());
			assertEquals(grouping.toString(), 0, entry.getAssignmentTotal());
		}
	}

	@Test
	public void groupingChoicesAreInTheirOrder()
	{
		assertEquals("[Overall, By master, By month, By monster]",
			Arrays.toString(OverallTotals.Grouping.values()));
	}

	private static OverallTotals.Entry single(List<OverallTotals.Entry> entries)
	{
		assertEquals(entries.toString(), 1, entries.size());
		return entries.get(0);
	}

	private static List<String> labels(List<OverallTotals.Entry> entries)
	{
		List<String> labels = new ArrayList<>();
		for (OverallTotals.Entry entry : entries)
		{
			labels.add(entry.getLabel());
		}
		return labels;
	}

	private static List<Integer> taskCounts(List<OverallTotals.Entry> entries)
	{
		List<Integer> counts = new ArrayList<>();
		for (OverallTotals.Entry entry : entries)
		{
			counts.add(entry.getTaskCount());
		}
		return counts;
	}

	/** Saved loot and supply totals, with the {@link SavedItems} items they stand for. */
	private static void valued(LedgerRecord record, long loot, long supplies)
	{
		record.setLootGeValue(loot);
		record.setLootHaValue(loot);
		record.setSuppliesGeValue(supplies);
		SavedItems.withItems(record);
	}

	private static Map<Integer, Long> map(long... idValue)
	{
		Map<Integer, Long> m = new HashMap<>();
		for (int i = 0; i < idValue.length; i += 2)
		{
			m.put((int) idValue[i], idValue[i + 1]);
		}
		return m;
	}

	private static AssignmentEntry entry(String monster, Integer master, AssignmentOutcome outcome)
	{
		AssignmentEntry entry = new AssignmentEntry();
		entry.setMonster(monster);
		entry.setSlayerMaster(master);
		entry.setOutcome(outcome);
		entry.setEndedAt(1);
		return entry;
	}

	private static LedgerRecord record(String monster, Integer master, int kills, long activeMillis)
	{
		LedgerRecord record = new LedgerRecord();
		record.setMonster(monster);
		record.setSlayerMaster(master);
		record.setEndedAt(1);
		record.setKills(kills);
		record.setActiveMillis(activeMillis);
		record.setCompleted(true);
		record.setPartial(false);
		return record;
	}
}
