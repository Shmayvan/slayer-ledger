package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import org.junit.Test;

public class TaskStatisticsTest
{
	private static final int CREATURE = 42;
	private static final String MONSTER = "Abyssal demons";
	private static final long MINUTE = 60_000L;

	@Test
	public void averagesCompletedRecordsAndKeepsOutliers()
	{
		LedgerRecord usual = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		usual.setLoot(Collections.singletonMap(SavedItems.LOOT, 100_000L));
		usual.setLootGeValue(100_000L);
		usual.setLootHaValue(200_000L);
		usual.setSuppliesGeValue(10_000L);
		usual.setSupplies(Collections.singletonMap(100, 10L));
		usual.setSupplyDoses(Collections.singletonMap(100, 4));
		usual.setXpGained(Collections.singletonMap("SLAYER", 10_000L));

		LedgerRecord outlier = record(CREATURE, MONSTER, 20, 60 * MINUTE);
		outlier.setLoot(Collections.singletonMap(SavedItems.LOOT, 400_000L));
		outlier.setLootGeValue(400_000L);
		outlier.setLootHaValue(800_000L);
		outlier.setSuppliesGeValue(60_000L);
		outlier.setSupplies(Collections.singletonMap(100, 60L));
		outlier.setSupplyDoses(Collections.singletonMap(100, 4));
		outlier.setXpGained(Collections.singletonMap("SLAYER", 30_000L));

		LedgerRecord partial = record(CREATURE, MONSTER, 100, 100 * MINUTE);
		partial.setPartial(true);
		LedgerRecord otherCreature = record(43, MONSTER, 100, 100 * MINUTE);
		// A 4-dose item at 4,000 gp today: 1,000 gp a dose.
		java.util.Map<Integer, Long> today = SavedItems.todaysPrices();
		today.put(100, 4_000L);
		TaskStatistics stats = TaskStatistics.forTask(Arrays.asList(usual, outlier, partial, otherCreature), CREATURE,
			MONSTER, LootPriceMode.GE, today);

		assertEquals(2, stats.getSampleCount());
		assertEquals(35 * MINUTE, stats.getAverageTaskDurationMillis());
		assertEquals(Long.valueOf(140_000), stats.getMillisPerKill());
		assertEquals(Long.valueOf(16_667), stats.getLootPerKill());
		assertEquals("70 doses at 1,000 gp over 30 kills", Long.valueOf(2_333), stats.getSuppliesValuePerKill());
		assertFalse(stats.isProfitApproximate());
		assertEquals(70d / 30d, stats.getSuppliesPerKill().get(100), 1e-9);
		assertEquals(4, (int) stats.getSupplyDoses().get(100));
		assertEquals(40_000d / 30d, stats.getXpPerKill().get("SLAYER"), 1e-9);
		assertEquals(Long.valueOf(34_286), stats.getXpPerHour());
	}

	@Test
	public void includesOneSampleAndReturnsNoEstimateForNoSamples()
	{
		assertEquals(0, stats(Collections.emptyList()).getSampleCount());
		assertNull(stats(Collections.emptyList()).estimate(10));

		LedgerRecord one = record(CREATURE, MONSTER, 2, 2 * MINUTE);
		one.setLootGeValue(1_000L);
		one.setSuppliesGeValue(100L);
		TaskStatistics stats = stats(Collections.singletonList(one));
		TaskStatistics.Estimate estimate = stats.estimate(5);

		assertEquals(1, estimate.getSampleCount());
		assertEquals(Long.valueOf(5 * MINUTE), estimate.getActiveMillis());
		assertEquals(Long.valueOf(2_500), estimate.getLootValue());
		assertEquals(Long.valueOf(250), estimate.getSuppliesValue());
	}

	@Test
	public void handlesLegacyMissingValuesAndXpWithoutTreatingThemAsZero()
	{
		LedgerRecord legacy = record(CREATURE, MONSTER, 5, 10 * MINUTE);
		legacy.setSupplies(Collections.singletonMap(100, 5L));
		legacy.setXpGained(null);

		TaskStatistics stats = stats(Collections.singletonList(legacy));
		assertEquals(1, stats.getSampleCount());
		assertEquals(Long.valueOf(120_000), stats.getMillisPerKill());
		assertNull(stats.getLootPerKill());
		assertNull(stats.getSuppliesValuePerKill());
		assertEquals(Double.valueOf(1d), stats.getSuppliesPerKill().get(100));
		assertTrue(stats.getXpPerKill().isEmpty());
		assertNull(stats.getXpPerHour());

		LedgerRecord valuedWithoutSupplies = record(CREATURE, MONSTER, 5, 10 * MINUTE);
		valuedWithoutSupplies.setLootGeValue(500L);
		TaskStatistics mixed = stats(Arrays.asList(legacy, valuedWithoutSupplies));
		assertEquals(Long.valueOf(100), mixed.getLootPerKill());
		assertEquals(Long.valueOf(0), mixed.getSuppliesValuePerKill());
		assertEquals(Double.valueOf(0.5), mixed.getSuppliesPerKill().get(100));
	}

	@Test
	public void supportsLegacyNameMatchingAndSelectedLootPriceMode()
	{
		LedgerRecord legacy = record(null, "  ABYSSAL DEMONS ", 2, MINUTE);
		legacy.setLootGeValue(100L);
		legacy.setLootHaValue(400L);
		TaskStatistics stats = TaskStatistics.forTask(Collections.singletonList(legacy), CREATURE, MONSTER,
			LootPriceMode.HIGH_ALCH, Collections.emptyMap());

		assertEquals(1, stats.getSampleCount());
		assertEquals(Long.valueOf(200), stats.getLootPerKill());
	}

	@Test
	public void excludesRecordsThatAreNeitherCompletedNorPartial()
	{
		LedgerRecord stopped = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		stopped.setCompleted(false);
		stopped.setPartial(false);
		LedgerRecord noKills = record(CREATURE, MONSTER, 0, 10 * MINUTE);

		assertEquals(0, stats(Arrays.asList(stopped, noKills)).getSampleCount());
		assertNull(stats(Arrays.asList(stopped, noKills)).estimate(10));
	}

	@Test
	public void todaysPricesChangeTheGpAverages()
	{
		LedgerRecord record = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		record.setLoot(Collections.singletonMap(500, 10L));
		record.setUnitGePrices(Collections.singletonMap(500, 1_000L));
		record.setLootGeValue(10_000L);
		record.setLootHaValue(5_000L);
		record.setSupplies(Collections.singletonMap(600, 20L));
		record.setSuppliesGeValue(2_000L);
		java.util.List<LedgerRecord> records = Collections.singletonList(record);

		java.util.Map<Integer, Long> then = new java.util.HashMap<>();
		then.put(500, 1_000L);
		then.put(600, 100L);
		TaskStatistics saved = TaskStatistics.forTask(records, CREATURE, MONSTER, LootPriceMode.GE, then);
		assertEquals(Long.valueOf(1_000), saved.getLootPerKill());
		assertEquals(Long.valueOf(200), saved.getSuppliesValuePerKill());

		java.util.Map<Integer, Long> today = new java.util.HashMap<>();
		today.put(500, 3_000L);
		today.put(600, 50L);
		TaskStatistics current = TaskStatistics.forTask(records, CREATURE, MONSTER, LootPriceMode.GE, today);
		assertEquals(Long.valueOf(3_000), current.getLootPerKill());
		assertEquals(Long.valueOf(100), current.getSuppliesValuePerKill());
	}

	@Test
	public void profitPerKillIsLootPerKillMinusSuppliesPerKillOverTheValuedKills()
	{
		LedgerRecord first = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		first.setLootGeValue(100_000L);
		first.setLootHaValue(150_000L);
		first.setSuppliesGeValue(10_000L);
		LedgerRecord second = record(CREATURE, MONSTER, 30, 20 * MINUTE);
		second.setLootGeValue(300_000L);
		second.setLootHaValue(450_000L);
		second.setSuppliesGeValue(50_000L);
		// Not valued: left out of the GP averages, not counted as 0.
		LedgerRecord legacy = record(CREATURE, MONSTER, 60, 60 * MINUTE);
		java.util.List<LedgerRecord> records = Arrays.asList(SavedItems.withItems(first),
			SavedItems.withItems(second), legacy);

		TaskStatistics ge = TaskStatistics.forTask(records, CREATURE, MONSTER, LootPriceMode.GE,
			SavedItems.todaysPrices());
		assertEquals(Long.valueOf(10_000), ge.getLootPerKill());
		assertEquals(Long.valueOf(1_500), ge.getSuppliesValuePerKill());
		assertEquals(Long.valueOf(8_500), ge.getProfitPerKill());
		assertFalse(ge.isProfitApproximate());

		// Loot at the Loot price mode, supplies at GE.
		TaskStatistics ha = TaskStatistics.forTask(records, CREATURE, MONSTER, LootPriceMode.HIGH_ALCH,
			SavedItems.todaysPrices());
		assertEquals(Long.valueOf(13_500), ha.getProfitPerKill());
	}

	@Test
	public void profitPerKillAddsUpWithTheRoundedPartsAndCanBeALoss()
	{
		LedgerRecord record = record(CREATURE, MONSTER, 3, 10 * MINUTE);
		record.setLootGeValue(1_000L);
		record.setSuppliesGeValue(2_000L);

		TaskStatistics stats = stats(Collections.singletonList(record));
		// 333.3 and 666.7 round to 333 and 667: the profit line matches the two lines above it.
		assertEquals(Long.valueOf(333), stats.getLootPerKill());
		assertEquals(Long.valueOf(667), stats.getSuppliesValuePerKill());
		assertEquals(Long.valueOf(-334), stats.getProfitPerKill());
	}

	@Test
	public void profitPerKillIsApproximateWhenEitherPartIsAndUnknownWithoutValuedTasks()
	{
		// Item 7 has no price today.
		LedgerRecord lootUnpriced = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		lootUnpriced.setLootGeValue(1_000L);
		lootUnpriced.setSuppliesGeValue(0L);
		SavedItems.withItems(lootUnpriced);
		java.util.Map<Integer, Long> loot7 = new java.util.HashMap<>(lootUnpriced.getLoot());
		loot7.put(7, 1L);
		lootUnpriced.setLoot(loot7);
		TaskStatistics loot = stats(Collections.singletonList(lootUnpriced));
		assertTrue(loot.isLootApproximate());
		assertFalse(loot.isSuppliesApproximate());
		assertTrue(loot.isProfitApproximate());

		LedgerRecord suppliesUnpriced = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		suppliesUnpriced.setLootGeValue(1_000L);
		suppliesUnpriced.setSuppliesGeValue(0L);
		SavedItems.withItems(suppliesUnpriced);
		suppliesUnpriced.setSupplies(Collections.singletonMap(7, 1L));
		TaskStatistics supplies = stats(Collections.singletonList(suppliesUnpriced));
		assertFalse(supplies.isLootApproximate());
		assertTrue(supplies.isSuppliesApproximate());
		assertTrue(supplies.isProfitApproximate());

		LedgerRecord legacy = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		TaskStatistics unvalued = stats(Collections.singletonList(legacy));
		assertEquals(1, unvalued.getSampleCount());
		assertNull(unvalued.getLootPerKill());
		assertNull(unvalued.getProfitPerKill());
		assertNull(stats(Collections.emptyList()).getProfitPerKill());
	}

	@Test
	public void untradeableLootAtGeZeroCountsAtHighAlch()
	{
		LedgerRecord record = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		record.setLoot(Collections.singletonMap(500, 10L));
		record.setUnitGePrices(Collections.singletonMap(500, 0L));
		record.setUnitHaPrices(Collections.singletonMap(500, 300L));
		// Stored at the captured GE price, 0; HistoryPricing recomputes it per item.
		record.setLootGeValue(0L);
		record.setLootHaValue(3_000L);
		record.setSuppliesGeValue(0L);

		// Still untradeable today.
		TaskStatistics stats = TaskStatistics.forTask(Collections.singletonList(record), CREATURE, MONSTER,
			LootPriceMode.GE, Collections.singletonMap(500, 0L));
		assertEquals(Long.valueOf(300), stats.getLootPerKill());
		assertFalse(stats.isLootApproximate());
	}

	@Test
	public void bossKeysMatchByIdAndLegacyNamesOnlyWithoutAnId()
	{
		int cerberus = SessionTracker.creatureKey(SessionTracker.BOSS_TASK_TARGET, 3);
		int otherBoss = SessionTracker.creatureKey(SessionTracker.BOSS_TASK_TARGET, 4);
		LedgerRecord same = record(cerberus, "Cerberus", 5, 5 * MINUTE);
		// A record with an id is matched by id only, even when its name is the same.
		LedgerRecord otherKeySameName = record(otherBoss, "Cerberus", 7, 7 * MINUTE);
		LedgerRecord legacy = record(null, "cerberus", 2, 2 * MINUTE);
		LedgerRecord legacyOther = record(null, "Kraken", 9, 9 * MINUTE);

		TaskStatistics stats = TaskStatistics.forTask(Arrays.asList(same, otherKeySameName, legacy, legacyOther),
			cerberus, "Cerberus", LootPriceMode.GE, Collections.emptyMap());
		assertEquals(2, stats.getSampleCount());
		assertEquals(Long.valueOf(MINUTE), stats.getMillisPerKill());
	}

	@Test
	public void currentTaskWithoutACreatureKeyMatchesEveryRecordByName()
	{
		// Without the current task's key there is nothing to compare ids with, so every record (with or without an
		// id) is matched by its name.
		LedgerRecord withId = record(CREATURE, MONSTER, 4, 4 * MINUTE);
		LedgerRecord legacy = record(null, MONSTER, 6, 6 * MINUTE);
		LedgerRecord otherName = record(CREATURE, "Kraken", 10, 10 * MINUTE);

		TaskStatistics stats = TaskStatistics.forTask(Arrays.asList(withId, legacy, otherName),
			SessionTracker.NO_CREATURE, MONSTER, LootPriceMode.GE, Collections.emptyMap());
		assertEquals(2, stats.getSampleCount());
		assertEquals(5 * MINUTE, stats.getAverageTaskDurationMillis());
	}

	@Test
	public void sameLocationFirstUsesOnlyTheTasksThereAndSaysWhere()
	{
		LedgerRecord smoke = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		smoke.setLocation("Smoke Dungeon");
		LedgerRecord catacombs = record(CREATURE, MONSTER, 10, 30 * MINUTE);
		catacombs.setLocation("Catacombs of Kourend");
		LedgerRecord partialHere = record(CREATURE, MONSTER, 10, 100 * MINUTE);
		partialHere.setLocation("Smoke Dungeon");
		partialHere.setPartial(true);
		LedgerRecord otherMonsterHere = record(43, "Dust devils", 10, 100 * MINUTE);
		otherMonsterHere.setLocation("Smoke Dungeon");
		java.util.List<LedgerRecord> records = Arrays.asList(smoke, catacombs, partialHere, otherMonsterHere);

		TaskStatistics here = TaskStatistics.forTask(records, CREATURE, MONSTER, "  smoke   DUNGEON ",
			record -> TaskLocation.recordLocation(record, region -> null), LootPriceMode.GE, Collections.emptyMap());
		assertEquals("case and spacing ignored; samples only", 1, here.getSampleCount());
		assertEquals(Long.valueOf(MINUTE), here.getMillisPerKill());
		assertEquals("as the current location was given, normalised", "smoke DUNGEON", here.getLocation());
	}

	@Test
	public void withNoTaskAtTheLocationEveryLocationCounts()
	{
		LedgerRecord smoke = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		smoke.setLocation("Smoke Dungeon");
		LedgerRecord unknown = record(CREATURE, MONSTER, 10, 30 * MINUTE);
		java.util.List<LedgerRecord> records = Arrays.asList(smoke, unknown);

		for (String location : new String[]{"Catacombs of Kourend", null, "  "})
		{
			TaskStatistics all = TaskStatistics.forTask(records, CREATURE, MONSTER, location,
				record -> TaskLocation.recordLocation(record, region -> null), LootPriceMode.GE,
				Collections.emptyMap());
			assertEquals(location, 2, all.getSampleCount());
			assertEquals(location, Long.valueOf(2 * MINUTE), all.getMillisPerKill());
			assertNull(location, all.getLocation());
		}
		LedgerRecord partial = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		partial.setPartial(true);
		partial.setLocation("Catacombs of Kourend");
		TaskStatistics onlyPartialThere = TaskStatistics.forTask(Arrays.asList(smoke, unknown, partial), CREATURE,
			MONSTER, "Catacombs of Kourend", record -> TaskLocation.recordLocation(record, region -> null),
			LootPriceMode.GE, Collections.emptyMap());
		assertNull("a partial task is no sample there", onlyPartialThere.getLocation());
		assertEquals(2, onlyPartialThere.getSampleCount());
	}

	@Test
	public void aRecordSavedBeforeItsRegionWasNamedMatchesOnceItIs()
	{
		LedgerRecord unnamed = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		unnamed.setLocationRegion(12_946);
		LedgerRecord elsewhere = record(CREATURE, MONSTER, 10, 30 * MINUTE);
		elsewhere.setLocation("Catacombs of Kourend");
		java.util.List<LedgerRecord> records = Arrays.asList(unnamed, elsewhere);
		java.util.Map<Integer, String> names = new java.util.HashMap<>();

		TaskStatistics before = TaskStatistics.forTask(records, CREATURE, MONSTER, "Smoke Dungeon",
			record -> TaskLocation.recordLocation(record, names::get), LootPriceMode.GE, Collections.emptyMap());
		assertNull("not named yet: all history", before.getLocation());
		assertEquals(2, before.getSampleCount());

		names.put(12_946, "Smoke Dungeon");
		TaskStatistics after = TaskStatistics.forTask(records, CREATURE, MONSTER, "Smoke Dungeon",
			record -> TaskLocation.recordLocation(record, names::get), LootPriceMode.GE, Collections.emptyMap());
		assertEquals("Smoke Dungeon", after.getLocation());
		assertEquals(1, after.getSampleCount());
		assertEquals(Long.valueOf(MINUTE), after.getMillisPerKill());
	}

	@Test
	public void theSameLocationSamplesFeedWhatToBring()
	{
		LedgerRecord smoke = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		smoke.setLocation("Smoke Dungeon");
		smoke.setSupplies(Collections.singletonMap(100, 10L));
		smoke.setBankTrips(0);
		LedgerRecord catacombs = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		catacombs.setLocation("Catacombs of Kourend");
		catacombs.setSupplies(Collections.singletonMap(100, 90L));
		catacombs.setBankTrips(4);

		TaskStatistics here = TaskStatistics.forTask(Arrays.asList(smoke, catacombs), CREATURE, MONSTER,
			"Smoke Dungeon", record -> TaskLocation.recordLocation(record, region -> null), LootPriceMode.GE,
			Collections.emptyMap());
		SupplyRecommendation.Estimate bring = SupplyRecommendation.forTask(here, 10);
		assertEquals(Long.valueOf(10), bring.getSupplies().get(100));
		assertEquals(Long.valueOf(0), bring.getBankTrips());
		assertEquals(1, bring.getSupplySampleCount());
	}

	@Test
	public void normalizeIsTheOneNameKeyAndBlankIsNull()
	{
		assertEquals("abyssal demons", TaskStatistics.normalize("  Abyssal Demons "));
		assertNull(TaskStatistics.normalize(null));
		assertNull(TaskStatistics.normalize(""));
		assertNull(TaskStatistics.normalize("   "));
	}

	@Test
	public void blankNamesMatchNothing()
	{
		LedgerRecord blank = record(null, "  ", 10, 10 * MINUTE);
		LedgerRecord unnamed = record(null, null, 10, 10 * MINUTE);
		for (String monster : new String[]{"", " ", null})
		{
			assertEquals(0, TaskStatistics.forTask(Arrays.asList(blank, unnamed), SessionTracker.NO_CREATURE, monster,
				LootPriceMode.GE, Collections.emptyMap()).getSampleCount());
		}
	}

	@Test
	public void namesNormalizeTheSameUnderATurkishDefaultLocale()
	{
		Locale original = Locale.getDefault();
		try
		{
			// In Turkish, "I".toLowerCase() is a dotless i and "i".toUpperCase() a dotted capital I.
			Locale.setDefault(new Locale("tr", "TR"));
			assertEquals("iron dragons", TaskStatistics.normalize("IRON DRAGONS"));
			assertEquals("Hitpoints", SkillXp.skillName("HITPOINTS"));
			assertEquals("Mining", SkillXp.skillName("mINING"));
			LedgerRecord iron = record(null, "Iron dragons", 10, 10 * MINUTE);
			assertEquals(1, TaskStatistics.forTask(Collections.singletonList(iron), SessionTracker.NO_CREATURE,
				"IRON DRAGONS", LootPriceMode.GE, Collections.emptyMap()).getSampleCount());
		}
		finally
		{
			Locale.setDefault(original);
		}
	}

	private static TaskStatistics stats(Iterable<LedgerRecord> records)
	{
		java.util.List<LedgerRecord> list = new java.util.ArrayList<>();
		for (LedgerRecord record : records)
		{
			list.add(record);
		}
		return TaskStatistics.forTask(list, CREATURE, MONSTER, LootPriceMode.GE, SavedItems.todaysPrices());
	}

	private static LedgerRecord record(Integer creatureId, String monster, int kills, long activeMillis)
	{
		LedgerRecord record = new LedgerRecord();
		record.setCreatureId(creatureId);
		record.setMonster(monster);
		record.setKills(kills);
		record.setActiveMillis(activeMillis);
		record.setCompleted(true);
		record.setPartial(false);
		return record;
	}
}
