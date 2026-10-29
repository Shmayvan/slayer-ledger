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
import java.util.function.IntFunction;
import org.junit.Test;

/** The Task history's Location and Master filters, combined with the Monster filter. */
public class HistoryFilterTest
{
	private static final int SMOKE = 12_946;
	private static final int UNNAMED = 6_557;
	private static final Map<Integer, String> REGION_NAMES = Collections.singletonMap(SMOKE, "Smoke Dungeon");
	private static final IntFunction<String> NAMES = REGION_NAMES::get;
	private static final IntFunction<String> NO_NAMES = region -> null;

	private static LedgerRecord record(String id, String monster, String location, Integer master)
	{
		LedgerRecord record = new LedgerRecord();
		record.setId(id);
		record.setMonster(monster);
		record.setLocation(location);
		record.setSlayerMaster(master);
		return record;
	}

	private static List<String> ids(List<LedgerRecord> records)
	{
		List<String> ids = new ArrayList<>();
		for (LedgerRecord record : records)
		{
			ids.add(record.getId());
		}
		return ids;
	}

	/** Newest first, as the model holds them. */
	private static List<LedgerRecord> history()
	{
		LedgerRecord regionOnly = record("r", "Dust devils", null, 5);
		regionOnly.setLocationRegion(SMOKE);
		LedgerRecord unnamedRegion = record("u", "Dust devils", null, null);
		unnamedRegion.setLocationRegion(UNNAMED);
		LedgerRecord konar = record("k", "Hydras", null, SlayerMaster.KONAR);
		konar.setTaskLocation("Karuulm Slayer Dungeon");
		return Arrays.asList(
			record("a", "Abyssal demons", "Catacombs", 5),
			record("b", "Dust devils", "Smoke Dungeon", 6),
			regionOnly,
			unnamedRegion,
			konar,
			record("c", "Abyssal demons", "abyss", 99),
			record("d", "Abyssal demons", null, SlayerMaster.NONE));
	}

	@Test
	public void aRecordsLocationIsTheOneHistoryShowsOrNoLocation()
	{
		List<LedgerRecord> records = history();
		assertEquals("Catacombs", HistoryFilter.location(records.get(0), NAMES));
		assertEquals("its region's name", "Smoke Dungeon", HistoryFilter.location(records.get(2), NAMES));
		assertEquals("not named yet", HistoryFilter.NO_LOCATION, HistoryFilter.location(records.get(2), NO_NAMES));
		assertEquals(HistoryFilter.NO_LOCATION, HistoryFilter.location(records.get(3), NAMES));
		assertEquals("its assigned area", "Karuulm Slayer Dungeon", HistoryFilter.location(records.get(4), NAMES));
		assertEquals(HistoryFilter.NO_LOCATION, HistoryFilter.location(records.get(6), NAMES));
	}

	@Test
	public void aRecordsMasterIsItsNameOrUnknownMaster()
	{
		List<LedgerRecord> records = history();
		assertEquals("Duradel/Kuradal", HistoryFilter.master(records.get(0)));
		assertEquals("Konar", HistoryFilter.master(records.get(4)));
		assertEquals("no master saved", HistoryFilter.UNKNOWN_MASTER, HistoryFilter.master(records.get(3)));
		assertEquals("a value not in the table", HistoryFilter.UNKNOWN_MASTER, HistoryFilter.master(records.get(5)));
		assertEquals("no task", HistoryFilter.UNKNOWN_MASTER, HistoryFilter.master(records.get(6)));
	}

	@Test
	public void locationChoicesAreAllThenEachLocationAlphabeticallyThenNoLocation()
	{
		assertEquals(Arrays.asList(LedgerHistory.ALL, "abyss", "Catacombs", "Karuulm Slayer Dungeon", "Smoke Dungeon",
			HistoryFilter.NO_LOCATION), HistoryFilter.locationOptions(history(), NAMES));
	}

	@Test
	public void locationsThatAreTheSamePlaceAreOneChoiceAsTheNewestRecordSpellsIt()
	{
		List<LedgerRecord> records = Arrays.asList(record("new", "Kurask", "Fremennik  slayer dungeon", 5),
			record("old", "Kurask", "Fremennik Slayer Dungeon", 5));
		assertEquals(Arrays.asList(LedgerHistory.ALL, "Fremennik slayer dungeon"),
			HistoryFilter.locationOptions(records, NAMES));
		assertEquals(Arrays.asList("new", "old"),
			ids(new HistoryFilter(null, "Fremennik slayer dungeon", null).apply(records, NAMES)));
	}

	@Test
	public void masterChoicesAreAllThenEachMasterAlphabeticallyThenUnknownMaster()
	{
		assertEquals(Arrays.asList(LedgerHistory.ALL, "Duradel/Kuradal", "Konar", "Nieve/Steve",
			HistoryFilter.UNKNOWN_MASTER), HistoryFilter.masterOptions(history()));
	}

	@Test
	public void choicesLeaveOutSentinelsNobodyHasAndAreJustAllWithoutRecords()
	{
		List<LedgerRecord> records = Collections.singletonList(record("a", "Kurask", "Iorwerth Dungeon", 6));
		assertEquals(Arrays.asList(LedgerHistory.ALL, "Iorwerth Dungeon"), HistoryFilter.locationOptions(records, NAMES));
		assertEquals(Arrays.asList(LedgerHistory.ALL, "Nieve/Steve"), HistoryFilter.masterOptions(records));
		assertEquals(Collections.singletonList(LedgerHistory.ALL),
			HistoryFilter.locationOptions(Collections.emptyList(), NAMES));
		assertEquals(Collections.singletonList(LedgerHistory.ALL), HistoryFilter.masterOptions(Collections.emptyList()));
	}

	@Test
	public void eachFilterAloneKeepsTheRecordOrder()
	{
		List<LedgerRecord> records = history();
		assertEquals(Arrays.asList("a", "b", "r", "u", "k", "c", "d"), ids(new HistoryFilter(null, null, null).apply(records, NAMES)));
		assertEquals(Arrays.asList("a", "c", "d"),
			ids(new HistoryFilter("Abyssal demons", null, null).apply(records, NAMES)));
		assertEquals("its own name or its region's", Arrays.asList("b", "r"),
			ids(new HistoryFilter(null, "Smoke Dungeon", null).apply(records, NAMES)));
		assertEquals("ignoring case", Arrays.asList("b", "r"),
			ids(new HistoryFilter(null, "smoke dungeon", null).apply(records, NAMES)));
		assertEquals(Arrays.asList("u", "d"),
			ids(new HistoryFilter(null, HistoryFilter.NO_LOCATION, null).apply(records, NAMES)));
		assertEquals(Arrays.asList("a", "r"),
			ids(new HistoryFilter(null, null, "Duradel/Kuradal").apply(records, NAMES)));
		assertEquals(Arrays.asList("u", "c", "d"),
			ids(new HistoryFilter(null, null, HistoryFilter.UNKNOWN_MASTER).apply(records, NAMES)));
	}

	@Test
	public void filtersCombine()
	{
		List<LedgerRecord> records = history();
		assertEquals(Collections.singletonList("r"),
			ids(new HistoryFilter("Dust devils", "Smoke Dungeon", "Duradel/Kuradal").apply(records, NAMES)));
		assertEquals(Collections.singletonList("d"),
			ids(new HistoryFilter("Abyssal demons", HistoryFilter.NO_LOCATION, HistoryFilter.UNKNOWN_MASTER)
				.apply(records, NAMES)));
		assertEquals("each matches alone, not together", Collections.emptyList(),
			ids(new HistoryFilter("Hydras", "Catacombs", null).apply(records, NAMES)));
	}

	@Test
	public void aLocationSelectionIsKeptWhileTheSamePlaceIsAChoice()
	{
		List<String> options = Arrays.asList(LedgerHistory.ALL, "Catacombs", "Smoke Dungeon", HistoryFilter.NO_LOCATION);
		assertEquals("Smoke Dungeon", HistoryFilter.keepLocation(options, "Smoke Dungeon"));
		assertEquals("as it is spelled now", "Smoke Dungeon", HistoryFilter.keepLocation(options, "smoke  dungeon"));
		assertEquals(HistoryFilter.NO_LOCATION, HistoryFilter.keepLocation(options, HistoryFilter.NO_LOCATION));
		assertNull(HistoryFilter.keepLocation(options, "Abyss"));
		assertNull(HistoryFilter.keepLocation(options, null));
		assertNull(HistoryFilter.keepLocation(options, LedgerHistory.ALL));
		assertNull("no record without a location any more",
			HistoryFilter.keepLocation(Arrays.asList(LedgerHistory.ALL, "Catacombs"), HistoryFilter.NO_LOCATION));
	}

	@Test
	public void aPlaceNamedNoLocationIsAChoiceOfItsOwn()
	{
		List<LedgerRecord> records = Arrays.asList(record("named", "Kurask", "No location", 6),
			record("respelled", "Kurask", "no  LOCATION", 6), record("none", "Kurask", null, 6));
		List<String> options = HistoryFilter.locationOptions(records, NAMES);
		assertEquals(Arrays.asList(LedgerHistory.ALL, "No location", HistoryFilter.NO_LOCATION), options);
		assertEquals("both show the same", Arrays.asList(LedgerHistory.ALL, "No location", "No location"),
			Arrays.asList(HistoryFilter.label(options.get(0)), HistoryFilter.label(options.get(1)),
				HistoryFilter.label(options.get(2))));
		assertEquals(Arrays.asList("named", "respelled"),
			ids(new HistoryFilter(null, "No location", null).apply(records, NAMES)));
		assertEquals(Collections.singletonList("none"),
			ids(new HistoryFilter(null, HistoryFilter.NO_LOCATION, null).apply(records, NAMES)));
		assertEquals("No location", HistoryFilter.keepLocation(options, "no location"));
		assertEquals(HistoryFilter.NO_LOCATION, HistoryFilter.keepLocation(options, HistoryFilter.NO_LOCATION));
		assertFalse(HistoryFilter.sameLocationChoice("No location", HistoryFilter.NO_LOCATION));
	}

	@Test
	public void aLocationSpelledAnewIsTheSameChoice()
	{
		assertTrue(HistoryFilter.sameLocationChoice(null, null));
		assertTrue(HistoryFilter.sameLocationChoice("Smoke Dungeon", "smoke  dungeon"));
		assertTrue(HistoryFilter.sameLocationChoice(HistoryFilter.NO_LOCATION, HistoryFilter.NO_LOCATION));
		assertFalse(HistoryFilter.sameLocationChoice("Smoke Dungeon", null));
		assertFalse(HistoryFilter.sameLocationChoice(null, "Smoke Dungeon"));
		assertFalse(HistoryFilter.sameLocationChoice("Smoke Dungeon", "Catacombs"));
	}

	@Test
	public void aRegionNamedLaterMovesItsRecordsOutOfNoLocation()
	{
		Map<Integer, String> names = new HashMap<>();
		List<LedgerRecord> records = history();
		assertEquals(Arrays.asList("r", "u", "d"),
			ids(new HistoryFilter(null, HistoryFilter.NO_LOCATION, null).apply(records, names::get)));
		names.put(UNNAMED, "Catacombs");
		assertEquals(Arrays.asList("a", "u"), ids(new HistoryFilter(null, "Catacombs", null).apply(records, names::get)));
		assertEquals(Arrays.asList("r", "d"),
			ids(new HistoryFilter(null, HistoryFilter.NO_LOCATION, null).apply(records, names::get)));
	}
}
