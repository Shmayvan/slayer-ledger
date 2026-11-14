package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.TestSpots.CATACOMBS;
import static com.shmayvan.slayerledger.TestSpots.CATACOMBS_ID;
import static com.shmayvan.slayerledger.TestSpots.SMOKE;
import static com.shmayvan.slayerledger.TestSpots.SMOKE_ID;
import static com.shmayvan.slayerledger.TestSpots.STRONGHOLD;
import static com.shmayvan.slayerledger.TestSpots.STRONGHOLD_ID;
import static com.shmayvan.slayerledger.TestSpots.TABLE;
import static com.shmayvan.slayerledger.TestSpots.UNLISTED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import org.junit.Test;

/**
 * The Task history's Location and Master filters, combined with the Monster filter: the Location filter groups
 * by spot and shows each spot by the location name of its newest task without a name of its own.
 */
public class HistoryFilterTest
{
	private static final String UNLISTED_KEY = "region:" + UNLISTED;
	private static final Map<Integer, String> REGION_NAMES = Collections.singletonMap(UNLISTED, "Edgeville");
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

	private static LedgerRecord at(String id, String monster, int region, String spotId, Integer master)
	{
		LedgerRecord record = record(id, monster, null, master);
		record.setLocationRegion(region);
		record.setSpotId(spotId);
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

	private static List<String> filter(String monster, String location, String master, List<LedgerRecord> records)
	{
		return ids(new HistoryFilter(monster, location, master).apply(records, TABLE, NAMES));
	}

	/** Newest first, as the model holds them. */
	private static List<LedgerRecord> history()
	{
		LedgerRecord konar = record("k", "Hydras", null, SlayerMaster.KONAR);
		konar.setTaskLocation("Karuulm Slayer Dungeon");
		// A 1.x record in the Smoke Dungeon, saved with the name its region had then: no spot stored.
		LedgerRecord oneX = record("x", "Dust devils", "Smokey", 5);
		oneX.setLocationRegion(SMOKE);
		return Arrays.asList(
			at("a", "Abyssal demons", CATACOMBS, CATACOMBS_ID, 5),
			at("b", "Dust devils", SMOKE, SMOKE_ID, 6),
			oneX,
			at("e", "Dust devils", UNLISTED, UNLISTED_KEY, null),
			konar,
			record("c", "Abyssal demons", "abyss", 99),
			record("d", "Abyssal demons", null, SlayerMaster.NONE));
	}

	@Test
	public void aRecordsLocationKeyIsItsSpotElseItsOwnNameElseNoLocation()
	{
		List<LedgerRecord> records = history();
		assertEquals(CATACOMBS_ID, HistoryFilter.locationKey(records.get(0), TABLE, NAMES));
		assertEquals("a record saved before spots: its region's", SMOKE_ID,
			HistoryFilter.locationKey(records.get(2), TABLE, NAMES));
		assertEquals(UNLISTED_KEY, HistoryFilter.locationKey(records.get(3), TABLE, NO_NAMES));
		assertEquals("no spot: its assigned area", HistoryFilter.NAME_PREFIX + "karuulm slayer dungeon",
			HistoryFilter.locationKey(records.get(4), TABLE, NAMES));
		assertEquals("no spot: its own name", HistoryFilter.NAME_PREFIX + "abyss",
			HistoryFilter.locationKey(records.get(5), TABLE, NAMES));
		assertEquals(HistoryFilter.NO_LOCATION, HistoryFilter.locationKey(records.get(6), TABLE, NAMES));
		assertEquals("without the table, a stored spot still groups", SMOKE_ID,
			HistoryFilter.locationKey(records.get(1), SlayerSpots.NONE, NAMES));
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
	public void locationChoicesAreAllThenEachSpotByItsNewestNameAlphabeticallyThenNoLocation()
	{
		HistoryFilter.LocationChoices choices = HistoryFilter.locationChoices(history(), TABLE, NAMES);
		assertEquals(Arrays.asList(LedgerHistory.ALL, HistoryFilter.NAME_PREFIX + "abyss", CATACOMBS_ID, UNLISTED_KEY,
			HistoryFilter.NAME_PREFIX + "karuulm slayer dungeon", SMOKE_ID, HistoryFilter.NO_LOCATION),
			choices.getOptions());
		List<String> labels = new ArrayList<>();
		for (String option : choices.getOptions())
		{
			labels.add(choices.label(option));
		}
		assertEquals("the Smoke Dungeon shows its newest task's name", Arrays.asList(LedgerHistory.ALL, "abyss",
			"Catacombs of Kourend", "Edgeville", "Karuulm Slayer Dungeon", "Smoke Dungeon", "No location"), labels);
	}

	@Test
	public void aTaskRenamedOnItsOwnStaysInItsSpotsChoice()
	{
		// The newest Smoke Dungeon task was renamed on its own: the spot is one choice, and that task's name names only
		// that task, so the choice keeps the spot's name.
		LedgerRecord renamed = at("new", "Dust devils", SMOKE, SMOKE_ID, 5);
		renamed.setLocation("West side");
		List<LedgerRecord> records = Arrays.asList(renamed, at("old", "Dust devils", SMOKE, SMOKE_ID, 5),
			at("cata", "Dust devils", CATACOMBS, CATACOMBS_ID, 5));
		HistoryFilter.LocationChoices choices = HistoryFilter.locationChoices(records, TABLE, NAMES);
		assertEquals(Arrays.asList(LedgerHistory.ALL, CATACOMBS_ID, SMOKE_ID), choices.getOptions());
		assertEquals("Smoke Dungeon", choices.label(SMOKE_ID));
		assertEquals(Arrays.asList("new", "old"), filter(null, SMOKE_ID, null, records));
	}

	@Test
	public void aSpotsChoiceIsNamedByItsNewestTaskWithoutANameOfItsOwn()
	{
		Map<Integer, String> names = new HashMap<>();
		names.put(CATACOMBS, "Cata");
		LedgerRecord newest = at("new", "Dust devils", CATACOMBS, CATACOMBS_ID, 5);
		newest.setLocation("North end");
		LedgerRecord oldest = at("old", "Dust devils", CATACOMBS, CATACOMBS_ID, 5);
		oldest.setLocation("South end");
		List<LedgerRecord> records = Arrays.asList(newest, at("mid", "Dust devils", CATACOMBS, CATACOMBS_ID, 5), oldest);
		assertEquals("the player's name for the region, not a task's own", "Cata",
			HistoryFilter.locationChoices(records, TABLE, names::get).label(CATACOMBS_ID));
		assertEquals("or the table's", "Catacombs of Kourend",
			HistoryFilter.locationChoices(records, TABLE, NO_NAMES).label(CATACOMBS_ID));

		// 1.x tasks there all named by hand: the newest one's name, so the place is found by a name the player gave.
		LedgerRecord smokey = record("smokey", "Dust devils", "Smokey", 5);
		smokey.setLocationRegion(SMOKE);
		LedgerRecord west = record("west", "Dust devils", "Smoke west", 5);
		west.setLocationRegion(SMOKE);
		HistoryFilter.LocationChoices oneX = HistoryFilter.locationChoices(Arrays.asList(smokey, west), TABLE, NAMES);
		assertEquals(Arrays.asList(LedgerHistory.ALL, SMOKE_ID), oneX.getOptions());
		assertEquals("Smokey", oneX.label(SMOKE_ID));
	}

	@Test
	public void aStoredSpotTheTableNoLongerHasGroupsByItsRegion()
	{
		// An id a later table dropped or renamed: the record takes its region's spot, and groups and is named with it.
		List<LedgerRecord> records = Arrays.asList(at("stale", "Dust devils", SMOKE, "smoke-dungeon-before", 5),
			at("current", "Dust devils", SMOKE, SMOKE_ID, 5));
		assertEquals(SMOKE_ID, HistoryFilter.locationKey(records.get(0), TABLE, NAMES));
		HistoryFilter.LocationChoices choices = HistoryFilter.locationChoices(records, TABLE, NAMES);
		assertEquals(Arrays.asList(LedgerHistory.ALL, SMOKE_ID), choices.getOptions());
		assertEquals("Smoke Dungeon", choices.label(SMOKE_ID));
		assertEquals(Arrays.asList("stale", "current"), filter(null, SMOKE_ID, null, records));
	}

	@Test
	public void theSameNameInTwoSpotsIsTwoChoices()
	{
		// Aberrant spectres in the Catacombs and in the Stronghold Slayer Cave, both shown as "Spectres".
		Map<Integer, String> names = new HashMap<>();
		names.put(CATACOMBS, "Spectres");
		names.put(STRONGHOLD, "Spectres");
		List<LedgerRecord> records = Arrays.asList(at("cata", "Aberrant spectres", CATACOMBS, CATACOMBS_ID, 5),
			at("stronghold", "Aberrant spectres", STRONGHOLD, STRONGHOLD_ID, 5));
		HistoryFilter.LocationChoices choices = HistoryFilter.locationChoices(records, TABLE, names::get);
		assertEquals(Arrays.asList(LedgerHistory.ALL, CATACOMBS_ID, STRONGHOLD_ID), choices.getOptions());
		assertEquals(Collections.singletonList("stronghold"),
			ids(new HistoryFilter(null, STRONGHOLD_ID, null).apply(records, TABLE, names::get)));
	}

	@Test
	public void namesWithoutASpotThatAreTheSamePlaceAreOneChoiceAsTheNewestRecordSpellsIt()
	{
		List<LedgerRecord> records = Arrays.asList(record("new", "Kurask", "Fremennik  slayer dungeon", 5),
			record("old", "Kurask", "Fremennik Slayer Dungeon", 5));
		HistoryFilter.LocationChoices choices = HistoryFilter.locationChoices(records, TABLE, NAMES);
		String key = HistoryFilter.NAME_PREFIX + "fremennik slayer dungeon";
		assertEquals(Arrays.asList(LedgerHistory.ALL, key), choices.getOptions());
		assertEquals("Fremennik slayer dungeon", choices.label(key));
		assertEquals(Arrays.asList("new", "old"), filter(null, key, null, records));
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
		List<LedgerRecord> records = Collections.singletonList(at("a", "Kurask", SMOKE, SMOKE_ID, 6));
		assertEquals(Arrays.asList(LedgerHistory.ALL, SMOKE_ID),
			HistoryFilter.locationChoices(records, TABLE, NAMES).getOptions());
		assertEquals(Arrays.asList(LedgerHistory.ALL, "Nieve/Steve"), HistoryFilter.masterOptions(records));
		assertEquals(Collections.singletonList(LedgerHistory.ALL),
			HistoryFilter.locationChoices(Collections.emptyList(), TABLE, NAMES).getOptions());
		assertEquals(Collections.singletonList(LedgerHistory.ALL), HistoryFilter.masterOptions(Collections.emptyList()));
	}

	@Test
	public void eachFilterAloneKeepsTheRecordOrder()
	{
		List<LedgerRecord> records = history();
		assertEquals(Arrays.asList("a", "b", "x", "e", "k", "c", "d"), filter(null, null, null, records));
		assertEquals(Arrays.asList("a", "c", "d"), filter("Abyssal demons", null, null, records));
		assertEquals("a 1.x record with its own name groups by its region's spot", Arrays.asList("b", "x"),
			filter(null, SMOKE_ID, null, records));
		assertEquals(Collections.singletonList("e"), filter(null, UNLISTED_KEY, null, records));
		assertEquals(Collections.singletonList("d"), filter(null, HistoryFilter.NO_LOCATION, null, records));
		assertEquals(Arrays.asList("a", "x"), filter(null, null, "Duradel/Kuradal", records));
		assertEquals(Arrays.asList("e", "c", "d"), filter(null, null, HistoryFilter.UNKNOWN_MASTER, records));
	}

	@Test
	public void filtersCombine()
	{
		List<LedgerRecord> records = history();
		assertEquals(Collections.singletonList("x"), filter("Dust devils", SMOKE_ID, "Duradel/Kuradal", records));
		assertEquals(Collections.singletonList("d"),
			filter("Abyssal demons", HistoryFilter.NO_LOCATION, HistoryFilter.UNKNOWN_MASTER, records));
		assertEquals("each matches alone, not together", Collections.emptyList(),
			filter("Hydras", CATACOMBS_ID, null, records));
	}

	@Test
	public void aLocationSelectionIsKeptWhileItIsAChoice()
	{
		List<String> options = Arrays.asList(LedgerHistory.ALL, CATACOMBS_ID, SMOKE_ID, HistoryFilter.NO_LOCATION);
		assertEquals(SMOKE_ID, HistoryFilter.keepLocation(options, SMOKE_ID));
		assertEquals(HistoryFilter.NO_LOCATION, HistoryFilter.keepLocation(options, HistoryFilter.NO_LOCATION));
		assertNull(HistoryFilter.keepLocation(options, STRONGHOLD_ID));
		assertNull(HistoryFilter.keepLocation(options, null));
		assertNull(HistoryFilter.keepLocation(options, LedgerHistory.ALL));
		assertNull("no record without a location any more",
			HistoryFilter.keepLocation(Arrays.asList(LedgerHistory.ALL, CATACOMBS_ID), HistoryFilter.NO_LOCATION));
	}

	@Test
	public void aPlaceNamedNoLocationIsAChoiceOfItsOwn()
	{
		List<LedgerRecord> records = Arrays.asList(record("named", "Kurask", "No location", 6),
			record("respelled", "Kurask", "no  LOCATION", 6), record("none", "Kurask", null, 6));
		HistoryFilter.LocationChoices choices = HistoryFilter.locationChoices(records, TABLE, NAMES);
		String named = HistoryFilter.NAME_PREFIX + "no location";
		assertEquals(Arrays.asList(LedgerHistory.ALL, named, HistoryFilter.NO_LOCATION), choices.getOptions());
		assertEquals("both show the same", Arrays.asList(LedgerHistory.ALL, "No location", "No location"),
			Arrays.asList(choices.label(LedgerHistory.ALL), choices.label(named),
				choices.label(HistoryFilter.NO_LOCATION)));
		assertEquals(Arrays.asList("named", "respelled"), filter(null, named, null, records));
		assertEquals(Collections.singletonList("none"), filter(null, HistoryFilter.NO_LOCATION, null, records));
	}

	@Test
	public void aRegionNamedLaterRenamesItsChoiceButKeepsIt()
	{
		Map<Integer, String> names = new HashMap<>();
		List<LedgerRecord> records = history();
		HistoryFilter.LocationChoices before = HistoryFilter.locationChoices(records, TABLE, names::get);
		assertEquals("Region 12342", before.label(UNLISTED_KEY));
		names.put(UNLISTED, "Edgeville");
		HistoryFilter.LocationChoices after = HistoryFilter.locationChoices(records, TABLE, names::get);
		assertEquals("Edgeville", after.label(UNLISTED_KEY));
		assertEquals(UNLISTED_KEY, HistoryFilter.keepLocation(after.getOptions(), UNLISTED_KEY));
		assertEquals(Collections.singletonList("e"),
			ids(new HistoryFilter(null, UNLISTED_KEY, null).apply(records, TABLE, names::get)));
	}
}
