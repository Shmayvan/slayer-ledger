package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import org.junit.Test;

/**
 * The per-task statistics the live card's time left and the overlay's time left and supply runway read: the sample
 * count, the time per kill, the location and the supplies per kill (the 2.0 rebuild trimmed them to these).
 */
public class TaskStatisticsTest
{
	private static final int CREATURE = 42;
	private static final String MONSTER = "Abyssal demons";
	private static final long MINUTE = 60_000L;

	@Test
	public void averagesCompletedRecordsAndKeepsOutliers()
	{
		LedgerRecord usual = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		usual.setSupplies(Collections.singletonMap(100, 10L));
		usual.setSupplyDoses(Collections.singletonMap(100, 4));

		LedgerRecord outlier = record(CREATURE, MONSTER, 20, 60 * MINUTE);
		outlier.setSupplies(Collections.singletonMap(100, 60L));
		outlier.setSupplyDoses(Collections.singletonMap(100, 4));

		LedgerRecord partial = record(CREATURE, MONSTER, 100, 100 * MINUTE);
		partial.setPartial(true);
		LedgerRecord otherCreature = record(43, MONSTER, 100, 100 * MINUTE);
		TaskStatistics stats = stats(Arrays.asList(usual, outlier, partial, otherCreature));

		assertEquals(2, stats.getSampleCount());
		assertEquals(Long.valueOf(140_000), stats.getMillisPerKill());
		assertEquals("70 doses over 30 kills", 70d / 30d, stats.getSuppliesPerKill().get(100), 1e-9);
		assertNull("from every location", stats.getLocation());
	}

	@Test
	public void handlesLegacyMissingSuppliesWithoutTreatingThemAsZero()
	{
		LedgerRecord legacy = record(CREATURE, MONSTER, 5, 10 * MINUTE);
		legacy.setSupplies(Collections.singletonMap(100, 5L));
		legacy.setXpGained(null);

		TaskStatistics stats = stats(Collections.singletonList(legacy));
		assertEquals(1, stats.getSampleCount());
		assertEquals(Long.valueOf(120_000), stats.getMillisPerKill());
		assertEquals(Double.valueOf(1d), stats.getSuppliesPerKill().get(100));

		// Valued with no supplies: it used none, so it counts for the supplies per kill.
		LedgerRecord valuedWithoutSupplies = record(CREATURE, MONSTER, 5, 10 * MINUTE);
		valuedWithoutSupplies.setLootGeValue(500L);
		TaskStatistics mixed = stats(Arrays.asList(legacy, valuedWithoutSupplies));
		assertEquals(Double.valueOf(0.5), mixed.getSuppliesPerKill().get(100));
	}

	@Test
	public void supportsLegacyNameMatching()
	{
		LedgerRecord legacy = record(null, "  ABYSSAL DEMONS ", 2, MINUTE);
		TaskStatistics stats = stats(Collections.singletonList(legacy));

		assertEquals(1, stats.getSampleCount());
		assertEquals(Long.valueOf(30_000), stats.getMillisPerKill());
	}

	@Test
	public void excludesRecordsThatAreNeitherCompletedNorPartial()
	{
		LedgerRecord stopped = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		stopped.setCompleted(false);
		stopped.setPartial(false);
		LedgerRecord noKills = record(CREATURE, MONSTER, 0, 10 * MINUTE);

		TaskStatistics none = stats(Arrays.asList(stopped, noKills));
		assertEquals(0, none.getSampleCount());
		assertNull(none.getMillisPerKill());
		assertTrue(none.getSuppliesPerKill().isEmpty());
	}

	@Test
	public void aOneXTaskSavedWithoutSuppliesIsLeftOutOfTheSuppliesPerKill()
	{
		// A 2.0 task: 10 kills, 100K of loot, 20 manta rays.
		LedgerRecord tracked = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		tracked.setLootGeValue(100_000L);
		tracked.setLootHaValue(100_000L);
		tracked.setSuppliesGeValue(1_000L);
		SavedItems.withItems(tracked);
		// A 1.x task saved with no supplies: its supply counting may have stopped, so what it used is unknown.
		LedgerRecord oneX = record(CREATURE, MONSTER, 30, 30 * MINUTE);
		oneX.setLootGeValue(300_000L);
		oneX.setLootHaValue(300_000L);
		oneX.setSuppliesGeValue(0L);
		SavedItems.withItems(oneX);
		oneX.setSchemaVersion(2);

		TaskStatistics stats = stats(Arrays.asList(tracked, oneX));
		assertEquals("both count for the time", 2, stats.getSampleCount());
		assertEquals(Long.valueOf(MINUTE), stats.getMillisPerKill());
		// The 2.0 task's 20 manta rays, over its own 10 kills, not over the 40 of both.
		assertEquals(2d, stats.getSuppliesPerKill().get(SavedItems.SUPPLY), 1e-9);

		// A 1.x task migrated in memory reads schema version 3; its estimated cannon tag says it is one.
		oneX.setSchemaVersion(SetupMigration.MIGRATED_VERSION);
		oneX.setCannonEstimated(true);
		assertEquals(2d, stats(Arrays.asList(tracked, oneX)).getSuppliesPerKill().get(SavedItems.SUPPLY), 1e-9);
		// A task 2.0 saved without supplies used none.
		oneX.setCannonEstimated(null);
		assertEquals("20 over 40 kills", 0.5d, stats(Arrays.asList(tracked, oneX)).getSuppliesPerKill()
			.get(SavedItems.SUPPLY), 1e-9);
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
			cerberus, "Cerberus", TaskSelection.ANY, null, null);
		assertEquals(2, stats.getSampleCount());
		assertEquals(Long.valueOf(MINUTE), stats.getMillisPerKill());
	}

	@Test
	public void currentTaskWithoutACreatureKeyMatchesEveryRecordByName()
	{
		// Without the current task's key there is nothing to compare ids with, so every record (with or without an
		// id) is matched by its name.
		LedgerRecord withId = record(CREATURE, MONSTER, 4, 4 * MINUTE);
		LedgerRecord legacy = record(null, MONSTER, 6, 12 * MINUTE);
		LedgerRecord otherName = record(CREATURE, "Kraken", 10, 10 * MINUTE);

		TaskStatistics stats = TaskStatistics.forTask(Arrays.asList(withId, legacy, otherName),
			SessionTracker.NO_CREATURE, MONSTER, TaskSelection.ANY, null, null);
		assertEquals(2, stats.getSampleCount());
		assertEquals("16 minutes over 10 kills", Long.valueOf(96_000), stats.getMillisPerKill());
	}

	@Test
	public void sameLocationFirstUsesOnlyTheTasksAtTheSpotAndSaysWhere()
	{
		LedgerRecord smoke = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		smoke.setSpotId(TestSpots.SMOKE_ID);
		LedgerRecord catacombs = record(CREATURE, MONSTER, 10, 30 * MINUTE);
		catacombs.setSpotId(TestSpots.CATACOMBS_ID);
		// Its own name (Edit location) says Smoke Dungeon, but its spot is the Catacombs: tasks match by spot.
		catacombs.setLocation("Smoke Dungeon");
		LedgerRecord partialHere = record(CREATURE, MONSTER, 10, 100 * MINUTE);
		partialHere.setSpotId(TestSpots.SMOKE_ID);
		partialHere.setPartial(true);
		LedgerRecord otherMonsterHere = record(43, "Dust devils", 10, 100 * MINUTE);
		otherMonsterHere.setSpotId(TestSpots.SMOKE_ID);
		java.util.List<LedgerRecord> records = Arrays.asList(smoke, catacombs, partialHere, otherMonsterHere);

		TaskStatistics here = atSpot(records, smoke("Smoke Dungeon"));
		assertEquals("samples at the spot only", 1, here.getSampleCount());
		assertEquals(Long.valueOf(MINUTE), here.getMillisPerKill());
		assertEquals("the name the current location was given", "Smoke Dungeon", here.getLocation());
	}

	@Test
	public void withNoTaskAtTheLocationEveryLocationCounts()
	{
		LedgerRecord smoke = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		smoke.setSpotId(TestSpots.SMOKE_ID);
		LedgerRecord unknown = record(CREATURE, MONSTER, 10, 30 * MINUTE);
		java.util.List<LedgerRecord> records = Arrays.asList(smoke, unknown);

		TaskLocation.Here[] elsewhere = {new TaskLocation.Here(TestSpots.CATACOMBS_ID, "Catacombs of Kourend"), null,
			TaskLocation.Here.UNKNOWN, new TaskLocation.Here(null, "Smoke Dungeon"), smoke(null)};
		for (TaskLocation.Here location : elsewhere)
		{
			TaskStatistics all = atSpot(records, location);
			assertEquals(String.valueOf(location), 2, all.getSampleCount());
			assertEquals(String.valueOf(location), Long.valueOf(2 * MINUTE), all.getMillisPerKill());
			assertNull(String.valueOf(location), all.getLocation());
		}
		LedgerRecord partial = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		partial.setPartial(true);
		partial.setSpotId(TestSpots.CATACOMBS_ID);
		TaskStatistics onlyPartialThere = atSpot(Arrays.asList(smoke, unknown, partial),
			new TaskLocation.Here(TestSpots.CATACOMBS_ID, "Catacombs of Kourend"));
		assertNull("a partial task is no sample there", onlyPartialThere.getLocation());
		assertEquals(2, onlyPartialThere.getSampleCount());
	}

	@Test
	public void aRecordSavedWithOnlyARegionMatchesByItsRegionsSpotAndANameAloneNever()
	{
		// Saved before spots: its region decides its spot, with no name needed.
		LedgerRecord regionOnly = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		regionOnly.setLocationRegion(TestSpots.SMOKE);
		// A 1.x task named by hand, with no region: no spot, so it never matches a location.
		LedgerRecord nameOnly = record(CREATURE, MONSTER, 10, 50 * MINUTE);
		nameOnly.setLocation("Smoke Dungeon");
		LedgerRecord elsewhere = record(CREATURE, MONSTER, 10, 30 * MINUTE);
		elsewhere.setSpotId(TestSpots.CATACOMBS_ID);
		java.util.List<LedgerRecord> records = Arrays.asList(regionOnly, nameOnly, elsewhere);

		TaskStatistics here = atSpot(records, smoke("Smoke"));
		assertEquals("Smoke", here.getLocation());
		assertEquals(1, here.getSampleCount());
		assertEquals(Long.valueOf(MINUTE), here.getMillisPerKill());
	}

	@Test
	public void theSameLocationSamplesFeedTheSuppliesPerKill()
	{
		LedgerRecord smoke = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		smoke.setSpotId(TestSpots.SMOKE_ID);
		smoke.setSupplies(Collections.singletonMap(100, 10L));
		LedgerRecord catacombs = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		catacombs.setSpotId(TestSpots.CATACOMBS_ID);
		catacombs.setSupplies(Collections.singletonMap(100, 90L));

		TaskStatistics here = atSpot(Arrays.asList(smoke, catacombs), smoke("Smoke Dungeon"));
		assertEquals(Double.valueOf(1d), here.getSuppliesPerKill().get(100));
		assertEquals("every location", Double.valueOf(5d),
			atSpot(Arrays.asList(smoke, catacombs), null).getSuppliesPerKill().get(100));
	}

	@Test
	public void poolsTheSuppliesPerKillOverTheRecordsThatHaveThem()
	{
		LedgerRecord first = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		first.setSupplies(map(100, 12, 101, 30));
		first.setSupplyDoses(Collections.singletonMap(100, 4));
		LedgerRecord second = record(CREATURE, MONSTER, 20, 20 * MINUTE);
		second.setSupplies(map(100, 40, 101, 60));
		second.setSupplyDoses(Collections.singletonMap(100, 4));

		TaskStatistics stats = stats(Arrays.asList(first, second));
		// 52 doses and 90 items over 30 kills.
		assertEquals(52d / 30d, stats.getSuppliesPerKill().get(100), 1e-9);
		assertEquals(3d, stats.getSuppliesPerKill().get(101), 1e-9);
	}

	@Test
	public void aKnownNoUseCountsAsNoSuppliesAndAnUnknownOneStaysUnknown()
	{
		LedgerRecord used = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		used.setSupplies(map(100, 10));
		// A partial task is no sample; a task saved without supplies or values says nothing of them.
		LedgerRecord partial = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		partial.setPartial(true);
		partial.setSupplies(map(100, 1_000));
		LedgerRecord legacy = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		legacy.setSupplies(null);
		TaskStatistics unknown = stats(Arrays.asList(used, partial, legacy));
		assertEquals(2, unknown.getSampleCount());
		assertEquals("10 over the 10 kills that say what they used", Double.valueOf(1d),
			unknown.getSuppliesPerKill().get(100));

		// Valued with no supplies: it used none.
		LedgerRecord noSupplies = record(CREATURE, MONSTER, 10, 10 * MINUTE);
		noSupplies.setLootGeValue(0L);
		noSupplies.setSupplies(Collections.emptyMap());
		assertEquals("10 over 20 kills", Double.valueOf(0.5d),
			stats(Arrays.asList(used, legacy, noSupplies)).getSuppliesPerKill().get(100));
		assertTrue(stats(Collections.singletonList(noSupplies)).getSuppliesPerKill().isEmpty());
	}

	/** The Smoke Dungeon's spot as the current location, shown as {@code name}. */
	private static TaskLocation.Here smoke(String name)
	{
		return new TaskLocation.Here(TestSpots.SMOKE_ID, name);
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
				TaskSelection.ANY, null, null).getSampleCount());
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
				"IRON DRAGONS", TaskSelection.ANY, null, null).getSampleCount());
		}
		finally
		{
			Locale.setDefault(original);
		}
	}

	/** The task's statistics over every location. */
	private static TaskStatistics stats(java.util.List<LedgerRecord> records)
	{
		return TaskStatistics.forTask(records, CREATURE, MONSTER, TaskSelection.ANY, null, null);
	}

	/** The task's statistics at {@code here}'s spot first. */
	private static TaskStatistics atSpot(java.util.List<LedgerRecord> records, TaskLocation.Here here)
	{
		return TaskStatistics.forTask(records, CREATURE, MONSTER, TaskSelection.ANY, here, TestSpots.TABLE::recordSpot);
	}

	private static java.util.Map<Integer, Long> map(int... values)
	{
		java.util.Map<Integer, Long> result = new java.util.HashMap<>();
		for (int i = 0; i < values.length; i += 2)
		{
			result.put(values[i], (long) values[i + 1]);
		}
		return result;
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
