package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class LuckMeterTest
{
	private static final LuckMeter METER = LuckMeter.load(new Gson(), LuckMeter.SNAPSHOT_RESOURCE);

	@Test
	public void bundledSnapshotLoadsWithTheCacheTaskNames()
	{
		assertNotNull(METER);
		List<LedgerRecord> records = Arrays.asList(record("Abyssal demons", 10), record("Dust devils", 10),
			record("Gargoyles", 10), record("Kurask", 10), record("Nechryael", 10));
		// The cache task names (core Task enum: "Kurask", not "Kurasks").
		assertEquals(Arrays.asList("Abyssal demons", "Dust devils", "Gargoyles", "Kurask", "Nechryael"),
			METER.supportedTasks(records));
		assertEquals("2026-10-26", METER.getSnapshotDate());
		assertEquals("CC BY-NC-SA 3.0", METER.getLicense());
	}

	@Test
	public void kuraskRecordsMatchTheirTable()
	{
		LedgerRecord kurask = record("Kurask", 384);
		kurask.setLoot(map(200, 1));
		LuckMeter.Summary summary = METER.summarize(Collections.singletonList(kurask), " kurask ",
			Collections.singletonMap(200, "Leaf-bladed sword"));
		assertEquals("Kurask", summary.getTask());
		assertEquals(1, summary.getTaskCount());
		assertEquals(384L, summary.getKills());
		assertEquals("Leaf-bladed sword", summary.getDrops().get(0).getItem());
		assertEquals(1L, summary.getDrops().get(0).getActual());
		assertEquals(1d, summary.getDrops().get(0).getExpected(), 1e-9);
	}

	@Test
	public void comparesObservedWithExpectedCountsOverSamplesOnly()
	{
		assertTrue(METER.supportedTasks(Collections.emptyList()).isEmpty());

		LedgerRecord completed = record("Abyssal demons", 512);
		completed.setLoot(map(100, 2, 101, 1, 102, 3));
		LedgerRecord partial = record("Abyssal demons", 50_000);
		partial.setPartial(true);
		LedgerRecord incomplete = record("Abyssal demons", 50_000);
		incomplete.setCompleted(false);

		Map<Integer, String> itemNames = new HashMap<>();
		itemNames.put(100, "Abyssal whip");
		itemNames.put(101, "ABYSSAL DAGGER");
		itemNames.put(102, "Untracked item");
		List<LedgerRecord> records = Arrays.asList(completed, partial, incomplete);

		LuckMeter.Summary summary = METER.summarize(records, "abyssal DEMONS", itemNames);
		assertEquals(1, summary.getTaskCount());
		assertEquals(512L, summary.getKills());
		assertEquals(2, summary.getDrops().size());

		LuckMeter.DropEntry whip = summary.getDrops().get(0);
		assertEquals("Abyssal whip", whip.getItem());
		assertEquals(2L, whip.getActual());
		assertEquals(1d, whip.getExpected(), 1e-9);
		assertTrue(whip.hasRatio());
		assertEquals(2d, whip.getRatio(), 1e-9);

		LuckMeter.DropEntry dagger = summary.getDrops().get(1);
		assertEquals("Abyssal dagger", dagger.getItem());
		assertEquals(1L, dagger.getActual());
		assertEquals(512d / 32000d, dagger.getExpected(), 1e-9);
	}

	@Test
	public void recordsWithoutLootDataAreNotSamples()
	{
		// Saved before loot tracking: no loot and no values. Its kills must not inflate expected.
		LedgerRecord old = record("Nechryael", 10_000);
		old.setLootGeValue(null);
		assertNull(old.getLoot());
		// Tracked with no loot at all: the empty tally is stored as null, but the record was valued.
		LedgerRecord tracked = record("Nechryael", 116);
		tracked.setLootGeValue(0L);
		LedgerRecord withLoot = record("Nechryael", 116);
		withLoot.setLoot(map(300, 1));
		assertFalse(LuckMeter.isSample(old));
		assertTrue(LuckMeter.isSample(tracked));
		assertTrue(LuckMeter.isSample(withLoot));

		LuckMeter.Summary summary = METER.summarize(Arrays.asList(old, tracked, withLoot), "Nechryael",
			Collections.singletonMap(300, "Rune boots"));
		assertEquals(2, summary.getTaskCount());
		assertEquals(232L, summary.getKills());
		assertEquals(2d, summary.getDrops().get(0).getExpected(), 1e-9);
		assertEquals(1L, summary.getDrops().get(0).getActual());

		assertTrue(METER.supportedTasks(Collections.singletonList(old)).isEmpty());
	}

	@Test
	public void notedNamesAndQuantitiesCount()
	{
		LedgerRecord record = record("Gargoyles", 512);
		record.setLoot(map(400, 2, 401, 1));
		Map<Integer, String> names = new HashMap<>();
		names.put(400, "Granite maul");
		names.put(401, "Granite maul (noted)");
		LuckMeter.Summary summary = METER.summarize(Collections.singletonList(record), "Gargoyles", names);
		assertEquals(3L, summary.getDrops().get(0).getActual());
		assertEquals(2d, summary.getDrops().get(0).getExpected(), 1e-9);
	}

	@Test
	public void unknownItemNamesAndUnsupportedTasksAreNotGuessed()
	{
		LedgerRecord record = record("Nechryael", 116);
		record.setLoot(map(100, 2));
		LuckMeter.Summary summary = METER.summarize(Collections.singletonList(record), "Nechryael",
			Collections.emptyMap());
		assertEquals(1, summary.getDrops().size());
		assertEquals(0L, summary.getDrops().get(0).getActual());
		assertEquals(1d, summary.getDrops().get(0).getExpected(), 1e-9);

		assertTrue(METER.summarize(Collections.singletonList(record), "Abyssal Sire",
			Collections.emptyMap()).getDrops().isEmpty());
		assertEquals(Collections.singletonList("Nechryael"), METER.supportedTasks(Collections.singletonList(record)));
	}

	@Test
	public void ratioIsHiddenBelowOneExpectedDrop()
	{
		LedgerRecord record = record("Abyssal demons", 256);
		record.setLoot(map(100, 1));
		LuckMeter.DropEntry whip = METER.summarize(Collections.singletonList(record), "Abyssal demons",
			Collections.singletonMap(100, "Abyssal whip")).getDrops().get(0);
		assertEquals(0.5, whip.getExpected(), 1e-9);
		assertFalse(whip.hasRatio());
		assertTrue(Double.isNaN(whip.getRatio()));
		// P(X >= 1) for Poisson(0.5) = 1 - e^-0.5.
		assertEquals(1 - Math.exp(-0.5), whip.getChanceOfAtLeastObserved(), 1e-12);
	}

	@Test
	public void poissonChanceOfAtLeastIsNumericallySafe()
	{
		assertEquals(1d, LuckMeter.chanceOfAtLeast(0, 0.01), 0);
		assertEquals(1d, LuckMeter.chanceOfAtLeast(0, 0), 0);
		assertEquals(0d, LuckMeter.chanceOfAtLeast(1, 0), 0);
		assertEquals(1 - Math.exp(-2) * (1 + 2), LuckMeter.chanceOfAtLeast(2, 2), 1e-12);
		// Upper tail: P(X >= 3) for Poisson(0.5).
		double lower = Math.exp(-0.5) * (1 + 0.5 + 0.125);
		assertEquals(1 - lower, LuckMeter.chanceOfAtLeast(3, 0.5), 1e-12);
		// Large means and counts neither overflow nor underflow to a wrong answer.
		assertEquals(0.5, LuckMeter.chanceOfAtLeast(10_000, 10_000), 0.01);
		double tiny = LuckMeter.chanceOfAtLeast(50, 0.01);
		assertTrue(tiny >= 0 && tiny < 1e-100);
		assertTrue(LuckMeter.chanceOfAtLeast(1, 5_000) > 0.999_999);
	}

	@Test
	public void blankNamesMatchNoTaskOrDrop()
	{
		LedgerRecord blank = record("  ", 10);
		LedgerRecord unnamed = record(null, 10);
		assertTrue(METER.supportedTasks(Arrays.asList(blank, unnamed)).isEmpty());
		assertEquals(0, METER.summarize(Arrays.asList(blank, unnamed), " ", Collections.emptyMap()).getTaskCount());

		LedgerRecord demons = record("Abyssal demons", 512);
		demons.setLoot(map(100, 2, 101, 1));
		Map<Integer, String> itemNames = new HashMap<>();
		itemNames.put(100, "Abyssal whip");
		itemNames.put(101, " ");
		LuckMeter.Summary summary = METER.summarize(Collections.singletonList(demons), "Abyssal demons", itemNames);
		assertEquals(2L, summary.getDrops().get(0).getActual());
		assertEquals(0L, summary.getDrops().get(1).getActual());
	}

	@Test
	public void missingOrBadSnapshotsLoadAsNull()
	{
		Gson gson = new Gson();
		assertNull(LuckMeter.load(gson, "no-such-snapshot.json"));
		assertNull(LuckMeter.load(gson, "luck-snapshot-malformed.json"));
		assertNull(LuckMeter.load(gson, "luck-snapshot-invalid.json"));
	}

	private static LedgerRecord record(String monster, int kills)
	{
		LedgerRecord record = new LedgerRecord();
		record.setMonster(monster);
		record.setKills(kills);
		record.setCompleted(true);
		record.setPartial(false);
		// Tracked loot (valued), unless a test clears it.
		record.setLootGeValue(0L);
		return record;
	}

	private static Map<Integer, Long> map(int... values)
	{
		Map<Integer, Long> result = new HashMap<>();
		for (int i = 0; i < values.length; i += 2)
		{
			result.put(values[i], (long) values[i + 1]);
		}
		return result;
	}
}
