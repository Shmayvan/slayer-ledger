package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Skill;
import org.junit.Test;

public class TaskRankingTest
{
	private static final long MINUTE = 60_000L;

	@Test
	public void ranksPooledRatesWithMetricSpecificSampleCounts()
	{
		LedgerRecord first = record(42, "Abyssal demons", 10 * MINUTE);
		first.setLootGeValue(100_000L);
		first.setSuppliesGeValue(10_000L);
		first.setXpGained(Collections.singletonMap("SLAYER", 10_000L));
		first.setPointsEarned(20);

		LedgerRecord second = record(42, "Abyssal demons", 20 * MINUTE);
		second.setLootGeValue(500_000L);
		second.setSuppliesGeValue(100_000L);

		LedgerRecord other = record(43, "Kurask", 30 * MINUTE);
		other.setLootGeValue(0L);
		other.setSuppliesGeValue(0L);
		other.setXpGained(Collections.emptyMap());
		other.setPointsEarned(0);

		LedgerRecord partial = record(42, "Abyssal demons", MINUTE);
		partial.setPartial(true);
		partial.setLootGeValue(100_000_000L);
		LedgerRecord untracked = record(44, "Gargoyles", MINUTE);
		untracked.setCompleted(false);
		untracked.setLootGeValue(100_000_000L);

		List<LedgerRecord> records = Arrays.asList(first, second, other, partial, untracked);
		List<TaskRanking.Entry> gp = rank(records, TaskRanking.Metric.PROFIT, LootPriceMode.GE);
		assertEquals(2, gp.size());
		assertEquals("Abyssal demons", gp.get(0).getMonster());
		assertEquals(Long.valueOf(980_000L), gp.get(0).getRatePerHour());
		assertEquals(2, gp.get(0).getSampleCount());
		assertEquals("Kurask", gp.get(1).getMonster());
		assertEquals(Long.valueOf(0L), gp.get(1).getRatePerHour());

		List<TaskRanking.Entry> xp = rank(records, TaskRanking.Metric.XP, LootPriceMode.GE);
		assertEquals(2, xp.size());
		assertEquals("Abyssal demons", xp.get(0).getMonster());
		assertEquals(Long.valueOf(60_000L), xp.get(0).getRatePerHour());
		assertEquals(1, xp.get(0).getSampleCount());
		assertEquals(Long.valueOf(0L), xp.get(1).getRatePerHour());
		assertEquals(1, xp.get(1).getSampleCount());

		List<TaskRanking.Entry> points = rank(records, TaskRanking.Metric.POINTS, LootPriceMode.GE);
		assertEquals(2, points.size());
		assertEquals("Abyssal demons", points.get(0).getMonster());
		assertEquals(Long.valueOf(120L), points.get(0).getRatePerHour());
		assertEquals(1, points.get(0).getSampleCount());
	}

	@Test
	public void includesUntrimmedOutliersAndMatchesLegacyNamesToCreatureKeys()
	{
		LedgerRecord ordinary = record(42, "Abyssal demons", 60 * MINUTE);
		ordinary.setLootGeValue(0L);
		ordinary.setSuppliesGeValue(0L);
		LedgerRecord outlier = record(null, " ABYSSAL DEMONS ", MINUTE);
		outlier.setLootGeValue(100_000L);
		outlier.setSuppliesGeValue(0L);

		TaskRanking.Entry entry = rank(Arrays.asList(ordinary, outlier), TaskRanking.Metric.PROFIT,
			LootPriceMode.GE).get(0);
		assertEquals(2, entry.getSampleCount());
		assertEquals(Long.valueOf(Math.round(100_000 * 3_600_000d / (61 * MINUTE))), entry.getRatePerHour());
		assertEquals("Abyssal demons", entry.getMonster());
	}

	@Test
	public void selectedLootModeSetsProfitAndUnavailableDataIsNotInvented()
	{
		LedgerRecord valued = record(42, "Abyssal demons", MINUTE);
		valued.setLootGeValue(200L);
		valued.setLootHaValue(400L);
		valued.setSuppliesGeValue(100L);
		assertEquals(Long.valueOf(18_000L), rank(Collections.singletonList(valued), TaskRanking.Metric.PROFIT,
			LootPriceMode.HIGH_ALCH).get(0).getRatePerHour());

		LedgerRecord legacy = record(42, "Abyssal demons", MINUTE);
		assertTrue(rank(Collections.singletonList(legacy), TaskRanking.Metric.PROFIT, LootPriceMode.GE).isEmpty());
		assertTrue(rank(Collections.singletonList(legacy), TaskRanking.Metric.XP, LootPriceMode.GE).isEmpty());
		assertTrue(rank(Collections.singletonList(legacy), TaskRanking.Metric.POINTS, LootPriceMode.GE).isEmpty());
	}

	@Test
	public void valuedRecordWithoutStoredSuppliesCountsThemAsZeroAsTaskStatisticsDoes()
	{
		LedgerRecord valued = record(42, "Abyssal demons", 60 * MINUTE);
		valued.setLootGeValue(5_000L);

		List<TaskRanking.Entry> gp = rank(Collections.singletonList(valued), TaskRanking.Metric.PROFIT,
			LootPriceMode.GE);
		assertEquals(1, gp.size());
		assertEquals(Long.valueOf(5_000L), gp.get(0).getRatePerHour());
	}

	@Test
	public void marksRatesApproximateWhenPricesAreMissingAndUsesCurrentGePrices()
	{
		LedgerRecord valued = record(42, "Abyssal demons", 60 * MINUTE);
		valued.setLoot(Collections.singletonMap(5, 2L));
		valued.setSupplies(Collections.singletonMap(6, 1L));
		valued.setUnitGePrices(Collections.singletonMap(5, 10L));
		valued.setUnitHaPrices(Collections.singletonMap(5, 20L));
		valued.setLootGeValue(20L);
		valued.setLootHaValue(40L);
		valued.setSuppliesGeValue(0L);
		valued.setLootUnpricedItems(1);
		Map<Integer, Long> current = new HashMap<>();
		current.put(5, 100L);
		current.put(6, 5L);

		TaskRanking.Entry saved = rank(Collections.singletonList(valued), TaskRanking.Metric.PROFIT,
			LootPriceMode.GE).get(0);
		assertTrue(saved.isApproximate());
		TaskRanking.Entry today = TaskRanking.rank(Collections.singletonList(valued), TaskRanking.Metric.PROFIT,
			LootPriceMode.GE, current).get(0);
		assertEquals(Long.valueOf(195L), today.getRatePerHour());
		assertFalse(today.isApproximate());
	}

	@Test
	public void pointsCountOnlyTasksTrackedFromTheFullAssignment()
	{
		// Owner decision: a task started part-way through has points for time the ledger never saw.
		LedgerRecord full = record(42, "Abyssal demons", 30 * MINUTE);
		full.setPointsEarned(15);
		full.setLootGeValue(30_000L);
		full.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 3_000L));
		LedgerRecord midway = record(42, "Abyssal demons", 30 * MINUTE);
		midway.setInitialAmount(5);
		midway.setOriginalAmount(10);
		midway.setPointsEarned(15);
		midway.setLootGeValue(30_000L);
		midway.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 3_000L));
		LedgerRecord unknownSize = record(42, "Abyssal demons", 30 * MINUTE);
		unknownSize.setOriginalAmount(null);
		unknownSize.setPointsEarned(15);
		LedgerRecord extended = record(43, "Kurask", 60 * MINUTE);
		extended.setInitialAmount(12);
		extended.setOriginalAmount(10);
		extended.setPointsEarned(10);
		List<LedgerRecord> records = Arrays.asList(full, midway, unknownSize, extended);

		List<TaskRanking.Entry> points = rank(records, TaskRanking.Metric.POINTS, LootPriceMode.GE);
		assertEquals(2, points.size());
		assertEquals("Abyssal demons", points.get(0).getMonster());
		assertEquals(1, points.get(0).getSampleCount());
		assertEquals(Long.valueOf(30L), points.get(0).getRatePerHour());
		assertEquals("Kurask", points.get(1).getMonster());
		assertEquals(Long.valueOf(10L), points.get(1).getRatePerHour());

		// The part-way task still counts for the other metrics.
		TaskRanking.Entry gp = rank(records, TaskRanking.Metric.PROFIT, LootPriceMode.GE).get(0);
		assertEquals(2, gp.getSampleCount());
		assertEquals(Long.valueOf(60_000L), gp.getRatePerHour());
		TaskRanking.Entry xp = rank(records, TaskRanking.Metric.XP, LootPriceMode.GE).get(0);
		assertEquals(2, xp.getSampleCount());
		assertEquals(Long.valueOf(6_000L), xp.getRatePerHour());

		assertTrue(TaskRanking.coversWholeTask(full));
		assertFalse(TaskRanking.coversWholeTask(midway));
		assertFalse(TaskRanking.coversWholeTask(unknownSize));
		assertTrue(TaskRanking.coversWholeTask(extended));
	}

	@Test
	public void pointsLeaveOutBonusTasks()
	{
		// Owner decision: which monster lands on a bonus task (every 10th in a streak) is streak luck.
		LedgerRecord hundredth = record(42, "Abyssal demons", 30 * MINUTE);
		hundredth.setPointsEarned(375);
		hundredth.setTaskStreak(100);
		hundredth.setLootGeValue(30_000L);
		hundredth.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 3_000L));

		LedgerRecord regular = record(43, "Kurask", 30 * MINUTE);
		regular.setPointsEarned(15);
		regular.setTaskStreak(101);
		LedgerRecord tenth = record(43, "Kurask", 30 * MINUTE);
		tenth.setPointsEarned(75);
		tenth.setTaskStreak(110);

		LedgerRecord noStreak = record(44, "Gargoyles", 60 * MINUTE);
		noStreak.setPointsEarned(15);
		LedgerRecord zeroStreak = record(44, "Gargoyles", 60 * MINUTE);
		zeroStreak.setPointsEarned(15);
		zeroStreak.setTaskStreak(0);

		List<LedgerRecord> records = Arrays.asList(hundredth, regular, tenth, noStreak, zeroStreak);
		List<TaskRanking.Entry> points = rank(records, TaskRanking.Metric.POINTS, LootPriceMode.GE);
		assertEquals(Arrays.asList("Kurask", "Gargoyles"), names(points));
		// Only the non-bonus Kurask task counts: 15 points in 30 minutes.
		assertEquals(1, points.get(0).getSampleCount());
		assertEquals(Long.valueOf(30L), points.get(0).getRatePerHour());
		// Without a stored streak (or a streak of 0) a task still counts, as before.
		assertEquals(2, points.get(1).getSampleCount());
		assertEquals(Long.valueOf(15L), points.get(1).getRatePerHour());

		// The bonus task still counts for the other metrics.
		TaskRanking.Entry gp = rank(records, TaskRanking.Metric.PROFIT, LootPriceMode.GE).get(0);
		assertEquals("Abyssal demons", gp.getMonster());
		assertEquals(Long.valueOf(60_000L), gp.getRatePerHour());
		TaskRanking.Entry xp = rank(records, TaskRanking.Metric.XP, LootPriceMode.GE).get(0);
		assertEquals("Abyssal demons", xp.getMonster());
		assertEquals(1, xp.getSampleCount());

		assertFalse(TaskRanking.countsForPointsRate(hundredth));
		assertTrue(TaskRanking.countsForPointsRate(regular));
		assertTrue(TaskRanking.countsForPointsRate(noStreak));
	}

	@Test
	public void noRateUntilTheMetricHasAMinuteOfActiveTimeAndThoseSortLast()
	{
		LedgerRecord shortTask = record(42, "Abyssal demons", 30_000L);
		shortTask.setLootGeValue(1_000_000L);
		shortTask.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 1_000L));
		LedgerRecord shortValued = record(42, "Abyssal demons", 30_000L);
		shortValued.setLootGeValue(0L);
		LedgerRecord loss = record(43, "Kurask", 60 * MINUTE);
		loss.setLootGeValue(0L);
		loss.setSuppliesGeValue(10_000L);
		loss.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 1_000L));
		List<LedgerRecord> records = Arrays.asList(shortTask, shortValued, loss);

		// Profit pools both 30-second tasks: one minute, so a rate.
		List<TaskRanking.Entry> gp = rank(records, TaskRanking.Metric.PROFIT, LootPriceMode.GE);
		assertEquals("Abyssal demons", gp.get(0).getMonster());
		assertEquals(Long.valueOf(1_000_000L * 60), gp.get(0).getRatePerHour());
		assertEquals(2, gp.get(0).getSampleCount());

		// XP has only the one 30-second task: listed with its sample, no rate, below a lower rate.
		List<TaskRanking.Entry> xp = rank(records, TaskRanking.Metric.XP, LootPriceMode.GE);
		assertEquals(2, xp.size());
		assertEquals("Kurask", xp.get(0).getMonster());
		assertEquals(Long.valueOf(1_000L), xp.get(0).getRatePerHour());
		assertEquals("Abyssal demons", xp.get(1).getMonster());
		assertNull(xp.get(1).getRatePerHour());
		assertEquals(1, xp.get(1).getSampleCount());
	}

	@Test
	public void xpIsSlayerXpOnly()
	{
		assertEquals(Skill.SLAYER.name(), SkillXp.SLAYER);
		Map<String, Long> mixed = new HashMap<>();
		mixed.put("ATTACK", 50_000L);
		mixed.put("HITPOINTS", 20_000L);
		mixed.put(SkillXp.SLAYER, 5_000L);
		LedgerRecord slayer = record(42, "Abyssal demons", 60 * MINUTE);
		slayer.setXpGained(mixed);
		LedgerRecord noSlayer = record(42, "Abyssal demons", 60 * MINUTE);
		noSlayer.setXpGained(Collections.singletonMap("ATTACK", 90_000L));
		LedgerRecord notRecorded = record(42, "Abyssal demons", 60 * MINUTE);

		TaskRanking.Entry xp = rank(Arrays.asList(slayer, noSlayer, notRecorded), TaskRanking.Metric.XP,
			LootPriceMode.GE).get(0);
		// No Slayer key is 0 Slayer XP (2 samples); null XP was not recorded (left out).
		assertEquals(2, xp.getSampleCount());
		assertEquals(Long.valueOf(2_500L), xp.getRatePerHour());
	}

	@Test
	public void metricLabelsFollowTheLootPriceMode()
	{
		assertEquals("Profit (GE)/h", TaskRanking.Metric.PROFIT.label(LootPriceMode.GE));
		assertEquals("Profit (HA)/h", TaskRanking.Metric.PROFIT.label(LootPriceMode.HIGH_ALCH));
		assertEquals("Profit (GE)/h", TaskRanking.Metric.PROFIT.label(null));
		assertEquals("Slayer XP/h", TaskRanking.Metric.XP.label(LootPriceMode.HIGH_ALCH));
		assertEquals("Points/h", TaskRanking.Metric.POINTS.label(LootPriceMode.GE));
		assertEquals("Profit (GE)/kill", TaskRanking.Metric.PROFIT_PER_KILL.label(LootPriceMode.GE));
		assertEquals("Profit (HA)/kill", TaskRanking.Metric.PROFIT_PER_KILL.label(LootPriceMode.HIGH_ALCH));
		assertEquals("Profit (GE)/kill", TaskRanking.Metric.PROFIT_PER_KILL.label(null));
	}

	@Test
	public void profitPerKillComesRightAfterProfitPerHourAndIsTheOnlyPerKillMetric()
	{
		assertEquals(Arrays.asList(TaskRanking.Metric.PROFIT, TaskRanking.Metric.PROFIT_PER_KILL,
			TaskRanking.Metric.XP, TaskRanking.Metric.POINTS), Arrays.asList(TaskRanking.Metric.values()));
		for (TaskRanking.Metric metric : TaskRanking.Metric.values())
		{
			assertEquals(metric.name(), metric == TaskRanking.Metric.PROFIT_PER_KILL, metric.isPerKill());
		}
	}

	@Test
	public void profitPerKillPoolsTheProfitOfTheTasksWithOneOverTheirKills()
	{
		LedgerRecord first = record(42, "Abyssal demons", 60 * MINUTE);
		first.setLootGeValue(100_000L);
		first.setLootHaValue(200_000L);
		first.setSuppliesGeValue(10_000L);
		LedgerRecord second = record(42, "Abyssal demons", 30 * MINUTE);
		second.setKills(30);
		second.setLootGeValue(200_000L);
		second.setLootHaValue(300_000L);
		second.setSuppliesGeValue(0L);
		// Not valued: its kills are left out, not counted as zero profit.
		LedgerRecord legacy = record(42, "Abyssal demons", 60 * MINUTE);
		legacy.setKills(1_000);
		// No kills: not a task sample at all.
		LedgerRecord noKills = record(42, "Abyssal demons", 60 * MINUTE);
		noKills.setKills(0);
		noKills.setLootGeValue(1_000_000L);
		noKills.setSuppliesGeValue(0L);
		List<LedgerRecord> records = Arrays.asList(first, second, legacy, noKills);

		TaskRanking.Entry ge = rank(records, TaskRanking.Metric.PROFIT_PER_KILL, LootPriceMode.GE).get(0);
		// (90K + 200K) / (10 + 30) kills; not the mean of the two tasks' 9K and 6,667.
		assertEquals(Long.valueOf(7_250L), ge.getRatePerHour());
		assertEquals(2, ge.getSampleCount());
		assertFalse(ge.isApproximate());

		TaskRanking.Entry ha = rank(records, TaskRanking.Metric.PROFIT_PER_KILL, LootPriceMode.HIGH_ALCH).get(0);
		assertEquals(Long.valueOf(12_250L), ha.getRatePerHour());
	}

	@Test
	public void profitPerKillNeedsNoMinimumTimeAndIsApproximateWithMissingPrices()
	{
		LedgerRecord brief = record(42, "Abyssal demons", 1_000L);
		brief.setLootGeValue(5_000L);
		brief.setSuppliesGeValue(0L);
		brief.setSuppliesUnpricedItems(1);

		TaskRanking.Entry entry = rank(Collections.singletonList(brief), TaskRanking.Metric.PROFIT_PER_KILL,
			LootPriceMode.GE).get(0);
		assertEquals(Long.valueOf(500L), entry.getRatePerHour());
		assertTrue(entry.isApproximate());
		assertNull("the hourly rate still needs a minute", rank(Collections.singletonList(brief),
			TaskRanking.Metric.PROFIT, LootPriceMode.GE).get(0).getRatePerHour());
	}

	@Test
	public void profitPerKillUsesTodaysPrices()
	{
		LedgerRecord valued = record(42, "Abyssal demons", 60 * MINUTE);
		valued.setLoot(Collections.singletonMap(5, 2L));
		valued.setSupplies(Collections.singletonMap(6, 1L));
		valued.setUnitGePrices(Collections.singletonMap(5, 10L));
		valued.setLootGeValue(20L);
		valued.setLootHaValue(40L);
		valued.setSuppliesGeValue(0L);
		valued.setLootUnpricedItems(1);
		Map<Integer, Long> current = new HashMap<>();
		current.put(5, 1_000L);
		current.put(6, 500L);

		TaskRanking.Entry today = TaskRanking.rank(Collections.singletonList(valued),
			TaskRanking.Metric.PROFIT_PER_KILL, LootPriceMode.GE, current).get(0);
		// (2 x 1,000 - 500) / 10 kills.
		assertEquals(Long.valueOf(150L), today.getRatePerHour());
		assertFalse(today.isApproximate());
	}

	@Test
	public void bossKeysStayApartAndAUniqueLegacyNameJoinsItsBoss()
	{
		// Boss task keys are -2 - bossId.
		LedgerRecord kraken = record(-3, "Kraken", 60 * MINUTE);
		kraken.setLootGeValue(100_000L);
		LedgerRecord cerberus = record(-4, "Cerberus", 60 * MINUTE);
		cerberus.setLootGeValue(150_000L);
		LedgerRecord legacyKraken = record(null, "kraken", 60 * MINUTE);
		legacyKraken.setLootGeValue(300_000L);

		List<TaskRanking.Entry> gp = rank(Arrays.asList(kraken, cerberus, legacyKraken), TaskRanking.Metric.PROFIT,
			LootPriceMode.GE);
		assertEquals(2, gp.size());
		assertEquals("Kraken", gp.get(0).getMonster());
		assertEquals(2, gp.get(0).getSampleCount());
		assertEquals(Long.valueOf(200_000L), gp.get(0).getRatePerHour());
		assertEquals("Cerberus", gp.get(1).getMonster());
		assertEquals(1, gp.get(1).getSampleCount());
	}

	@Test
	public void aLegacyNameThatMapsToTwoIdsKeepsItsOwnNameKey()
	{
		LedgerRecord first = record(10, "Dragons", 60 * MINUTE);
		first.setLootGeValue(100_000L);
		LedgerRecord second = record(11, "Dragons", 60 * MINUTE);
		second.setLootGeValue(200_000L);
		LedgerRecord legacy = record(null, " dragons ", 60 * MINUTE);
		legacy.setLootGeValue(300_000L);

		List<TaskRanking.Entry> gp = rank(Arrays.asList(first, second, legacy), TaskRanking.Metric.PROFIT,
			LootPriceMode.GE);
		assertEquals(3, gp.size());
		assertEquals("dragons", gp.get(0).getMonster());
		assertEquals(1, gp.get(0).getSampleCount());
		assertEquals(Long.valueOf(300_000L), gp.get(0).getRatePerHour());
		assertEquals(Long.valueOf(200_000L), gp.get(1).getRatePerHour());
		assertEquals(Long.valueOf(100_000L), gp.get(2).getRatePerHour());
	}

	@Test
	public void equalRatesSortByNameIgnoringCaseThenByName()
	{
		List<LedgerRecord> records = new ArrayList<>();
		int id = 1;
		for (String name : Arrays.asList("beta", "alpha", "Alpha"))
		{
			LedgerRecord record = record(id++, name, 60 * MINUTE);
			record.setLootGeValue(1_000L);
			records.add(record);
		}

		List<TaskRanking.Entry> gp = rank(records, TaskRanking.Metric.PROFIT, LootPriceMode.GE);
		assertEquals(Arrays.asList("Alpha", "alpha", "beta"), names(gp));
	}

	@Test
	public void lossesSortBelowGainsAndMonstersWithoutARateSortLast()
	{
		LedgerRecord gain = record(1, "Gain", 60 * MINUTE);
		gain.setLootGeValue(10_000L);
		LedgerRecord even = record(2, "Even", 60 * MINUTE);
		even.setLootGeValue(0L);
		LedgerRecord loss = record(3, "Loss", 60 * MINUTE);
		loss.setLootGeValue(0L);
		loss.setSuppliesGeValue(5_000L);
		LedgerRecord brief = record(4, "Brief", 1_000L);
		brief.setLootGeValue(1_000_000L);

		List<TaskRanking.Entry> gp = rank(Arrays.asList(brief, loss, even, gain), TaskRanking.Metric.PROFIT,
			LootPriceMode.GE);
		assertEquals(Arrays.asList("Gain", "Even", "Loss", "Brief"), names(gp));
		assertEquals(Long.valueOf(-5_000L), gp.get(2).getRatePerHour());
		assertNull(gp.get(3).getRatePerHour());
	}

	@Test
	public void profitPerKillLossesSortBelowZeroAndTiesByName()
	{
		LedgerRecord gain = record(1, "Gain", 60 * MINUTE);
		gain.setLootGeValue(10_000L);
		LedgerRecord even = record(2, "even", 60 * MINUTE);
		even.setLootGeValue(0L);
		LedgerRecord alsoEven = record(3, "Even", 60 * MINUTE);
		alsoEven.setLootGeValue(0L);
		LedgerRecord loss = record(4, "Loss", 60 * MINUTE);
		loss.setLootGeValue(0L);
		loss.setSuppliesGeValue(5_000L);

		List<TaskRanking.Entry> gp = rank(Arrays.asList(loss, even, gain, alsoEven),
			TaskRanking.Metric.PROFIT_PER_KILL, LootPriceMode.GE);
		assertEquals(Arrays.asList("Gain", "Even", "even", "Loss"), names(gp));
		assertEquals(Long.valueOf(1_000L), gp.get(0).getRatePerHour());
		assertEquals(Long.valueOf(-500L), gp.get(3).getRatePerHour());
	}

	@Test
	public void prefersTheTrimmedNameOfARecordWithACreatureId()
	{
		LedgerRecord legacy = record(null, " abyssal DEMONS ", 60 * MINUTE);
		legacy.setLootGeValue(0L);
		LedgerRecord keyed = record(42, "  Abyssal demons ", 60 * MINUTE);
		keyed.setLootGeValue(0L);
		LedgerRecord nameOnly = record(null, " Kurask ", 60 * MINUTE);
		nameOnly.setLootGeValue(0L);

		List<TaskRanking.Entry> gp = rank(Arrays.asList(legacy, keyed, nameOnly), TaskRanking.Metric.PROFIT,
			LootPriceMode.GE);
		assertEquals(Arrays.asList("Abyssal demons", "Kurask"), names(gp));
		assertEquals(2, gp.get(0).getSampleCount());
	}

	private static List<String> names(List<TaskRanking.Entry> entries)
	{
		List<String> names = new ArrayList<>();
		for (TaskRanking.Entry entry : entries)
		{
			names.add(entry.getMonster());
		}
		return names;
	}

	private static List<TaskRanking.Entry> rank(List<LedgerRecord> records, TaskRanking.Metric metric,
		LootPriceMode mode)
	{
		return TaskRanking.rank(records, metric, mode, null);
	}

	/** A completed record that tracked the whole assignment of 10. */
	private static LedgerRecord record(Integer creatureId, String monster, long activeMillis)
	{
		LedgerRecord record = new LedgerRecord();
		record.setCreatureId(creatureId);
		record.setMonster(monster);
		record.setKills(10);
		record.setInitialAmount(10);
		record.setOriginalAmount(10);
		record.setActiveMillis(activeMillis);
		record.setCompleted(true);
		return record;
	}
}
