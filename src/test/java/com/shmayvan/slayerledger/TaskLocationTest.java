package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.TestSpots.CATACOMBS;
import static com.shmayvan.slayerledger.TestSpots.CATACOMBS_ID;
import static com.shmayvan.slayerledger.TestSpots.CORSAIR;
import static com.shmayvan.slayerledger.TestSpots.CORSAIR_ID;
import static com.shmayvan.slayerledger.TestSpots.HYDRA_LAIR_ID;
import static com.shmayvan.slayerledger.TestSpots.KARUULM_ID;
import static com.shmayvan.slayerledger.TestSpots.KARUULM_SHARED;
import static com.shmayvan.slayerledger.TestSpots.KRAKEN_COVE_ID;
import static com.shmayvan.slayerledger.TestSpots.KRAKEN_ROOM_ID;
import static com.shmayvan.slayerledger.TestSpots.KRAKEN_SHARED;
import static com.shmayvan.slayerledger.TestSpots.SMOKE;
import static com.shmayvan.slayerledger.TestSpots.SMOKE_ID;
import static com.shmayvan.slayerledger.TestSpots.TABLE;
import static com.shmayvan.slayerledger.TestSpots.TOWER;
import static com.shmayvan.slayerledger.TestSpots.TOWER_ID;
import static com.shmayvan.slayerledger.TestSpots.UNLISTED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Location names: validation, and which name the panel shows for a record, the live session and the
 * current task: the player's own name for the task, then their name for its region, then the spot table's name.
 */
public class TaskLocationTest
{
	private static final String UNLISTED_KEY = "region:" + UNLISTED;

	/** A record of {@code monster} saved with {@code spotId} in {@code region}. */
	private static LedgerRecord at(int region, String spotId, String monster)
	{
		LedgerRecord record = new LedgerRecord();
		record.setMonster(monster);
		record.setLocationRegion(region);
		record.setSpotId(spotId);
		return record;
	}

	@Test
	public void namesAreTrimmedCollapsedAndCut()
	{
		assertEquals("Smoke Dungeon", TaskLocation.normalizeName("  Smoke \t  Dungeon\n"));
		assertNull("empty", TaskLocation.normalizeName(""));
		assertNull("blank", TaskLocation.normalizeName(" \n\t "));
		assertNull(TaskLocation.normalizeName(null));
		String longName = "Catacombs of Kourend, the north-westernmost corner";
		String cut = TaskLocation.normalizeName(longName);
		assertEquals(TaskLocation.MAX_NAME_LENGTH, cut.length());
		assertEquals(longName.substring(0, TaskLocation.MAX_NAME_LENGTH).trim(), cut);
	}

	@Test
	public void historyShowsTheTasksOwnNameThenItsRegionsNameThenItsSpotsName()
	{
		Map<Integer, String> names = new HashMap<>();
		LedgerRecord record = new LedgerRecord();
		record.setMonster("Dust devils");
		assertEquals("no region at all", TaskLocation.UNKNOWN, TaskLocation.displayName(record, names::get, TABLE));

		record.setLocationRegion(SMOKE);
		assertEquals("a record saved before spots: its region's spot", "Smoke Dungeon",
			TaskLocation.displayName(record, names::get, TABLE));
		record.setSpotId(SMOKE_ID);
		assertEquals("Smoke Dungeon", TaskLocation.displayName(record, names::get, TABLE));
		names.put(SMOKE, " Smokey  ");
		assertEquals("the player's name for the region comes first", "Smokey",
			TaskLocation.displayName(record, names::get, TABLE));
		record.setLocation("West bank");
		assertEquals("the task's own name first of all", "West bank",
			TaskLocation.displayName(record, names::get, TABLE));
		assertEquals("the spot is kept", SMOKE_ID, TABLE.recordSpot(record));
	}

	/** A Task history row's second line: the table's short name, a player's name as given. */
	@Test
	public void theHistoryRowShowsTheSpotsShortNameAndThePlayersNamesAsGiven()
	{
		Map<Integer, String> names = new HashMap<>();
		LedgerRecord record = at(CATACOMBS, CATACOMBS_ID, "Dust devils");
		assertEquals("Catacombs of Kourend", TaskLocation.displayName(record, names::get, TABLE));
		assertEquals("Catacombs", TaskLocation.shortName(record, names::get, TABLE));
		assertEquals("Stronghold Cave",
			TaskLocation.shortName(at(TestSpots.STRONGHOLD, TestSpots.STRONGHOLD_ID, "Ankou"), names::get, TABLE));
		assertEquals("Region 12342", TaskLocation.shortName(at(UNLISTED, UNLISTED_KEY, "Cows"), names::get, TABLE));

		names.put(CATACOMBS, "North end");
		assertEquals("the player's name for the region", "North end",
			TaskLocation.shortName(record, names::get, TABLE));
		record.setLocation("Catacombs of Kourend, the far north end");
		assertEquals("the task's own name, cut short on the row only", "Catacombs of Kourend, the far north end",
			TaskLocation.shortName(record, names::get, TABLE));

		LedgerRecord konar = new LedgerRecord();
		konar.setMonster("Cave kraken");
		konar.setTaskLocation("Kraken Cove");
		assertEquals("an older Konar record's assigned area", "Kraken Cove",
			TaskLocation.shortName(konar, names::get, TABLE));
		assertNull("none known", TaskLocation.shortName(new LedgerRecord(), names::get, TABLE));
		assertNull("without the table only the player's names", TaskLocation.shortName(
			at(CATACOMBS, CATACOMBS_ID, "Dust devils"), region -> null, SlayerSpots.NONE));
	}

	@Test
	public void anUnlistedRegionIsNamedRegionNUntilThePlayerNamesIt()
	{
		Map<Integer, String> names = new HashMap<>();
		LedgerRecord record = new LedgerRecord();
		record.setLocationRegion(UNLISTED);
		record.setSpotId(UNLISTED_KEY);
		assertEquals("Region 12342", TaskLocation.displayName(record, names::get, TABLE));
		names.put(UNLISTED, "Edgeville");
		assertEquals("a 1.x name, or a rename since", "Edgeville", TaskLocation.displayName(record, names::get, TABLE));
	}

	@Test
	public void anOwnersNameForARegionStaysTheNameShownForItsSpot()
	{
		// A synthetic 1.x record in region 7820, which the owner named "Myth's Guild" and the table calls the Corsair
		// Cove Dungeon: the name stays, the spot is the table's.
		Map<Integer, String> names = new HashMap<>();
		names.put(CORSAIR, "Myth's Guild");
		LedgerRecord record = new LedgerRecord();
		record.setMonster("Blue dragons");
		record.setLocationRegion(CORSAIR);
		assertEquals("Myth's Guild", TaskLocation.displayName(record, names::get, TABLE));
		assertEquals(CORSAIR_ID, TABLE.recordSpot(record));
		record.setLocation("Myth's Guild");
		assertEquals("as 1.x saved it", "Myth's Guild", TaskLocation.displayName(record, names::get, TABLE));
		names.clear();
		record.setLocation(null);
		assertEquals("without it, the table's name", "Corsair Cove Dungeon",
			TaskLocation.displayName(record, names::get, TABLE));
	}

	@Test
	public void aRegionsNameNamesItsSpotOnlyWhenTheRegionHasOneSpot()
	{
		// 1.x names for two regions that two spots share, and for the owner's region 7820, which has one.
		Map<Integer, String> names = new HashMap<>();
		names.put(KRAKEN_SHARED, "Krakens");
		names.put(KARUULM_SHARED, "Karuulm");
		names.put(CORSAIR, "Myth's Guild");
		assertEquals("each spot of a shared region keeps the table's name", "Kraken Cove boss room",
			TaskLocation.displayName(at(KRAKEN_SHARED, KRAKEN_ROOM_ID, "Cave kraken"), names::get, TABLE));
		assertEquals("Kraken Cove",
			TaskLocation.displayName(at(KRAKEN_SHARED, KRAKEN_COVE_ID, "Waterfiends"), names::get, TABLE));
		assertEquals("Alchemical Hydra's lair",
			TaskLocation.displayName(at(KARUULM_SHARED, HYDRA_LAIR_ID, "Hydras"), names::get, TABLE));
		assertEquals("Karuulm Slayer Dungeon",
			TaskLocation.displayName(at(KARUULM_SHARED, KARUULM_ID, "Hydras"), names::get, TABLE));
		LedgerRecord oneX = at(KRAKEN_SHARED, null, "Waterfiends");
		assertEquals("a record saved before spots, whose region decides its spot", "Kraken Cove",
			TaskLocation.displayName(oneX, names::get, TABLE));
		assertEquals("the live task box too", "Kraken Cove boss room",
			TaskLocation.liveName(new TaskSpot(KRAKEN_ROOM_ID, KRAKEN_SHARED), names::get, TABLE));
		assertEquals("and where the player stands", new TaskLocation.Here(HYDRA_LAIR_ID, "Alchemical Hydra's lair"),
			TaskLocation.current(null, TaskSpot.UNKNOWN, KARUULM_SHARED, HYDRA_LAIR_ID, names::get, TABLE));

		assertEquals("a region with one spot shows the player's name", "Myth's Guild",
			TaskLocation.displayName(at(CORSAIR, CORSAIR_ID, "Blue dragons"), names::get, TABLE));
		assertEquals("an Unknown spot has no table name, so the region's", "Krakens",
			TaskLocation.liveName(new TaskSpot(null, KRAKEN_SHARED), names::get, TABLE));
		LedgerRecord own = at(KRAKEN_SHARED, KRAKEN_ROOM_ID, "Cave kraken");
		own.setLocation("Boss");
		assertEquals("a task's own name still comes first", "Boss", TaskLocation.displayName(own, names::get, TABLE));
	}

	@Test
	public void aStoredSpotTheTableDoesNotKnowIsNamedByItsRegion()
	{
		// An id a later table dropped or renamed.
		Map<Integer, String> names = new HashMap<>();
		LedgerRecord record = at(SMOKE, "smoke-dungeon-before", "Dust devils");
		assertEquals("Smoke Dungeon", TaskLocation.displayName(record, names::get, TABLE));
		record.setLocationRegion(null);
		assertEquals("no region either: Unknown", TaskLocation.UNKNOWN,
			TaskLocation.displayName(record, names::get, TABLE));
	}

	@Test
	public void anAssignedAreaNamesOnlyATaskWithoutASpot()
	{
		Map<Integer, String> names = new HashMap<>();
		LedgerRecord record = new LedgerRecord();
		record.setMonster("Cave kraken");
		record.setTaskLocation("Kraken Cove");
		assertEquals("an older Konar record without a region", "Kraken Cove",
			TaskLocation.displayName(record, names::get, TABLE));
		record.setLocationRegion(KRAKEN_SHARED);
		assertNull("a shared region the task can't decide", TABLE.recordSpot(record));
		assertEquals("Kraken Cove", TaskLocation.displayName(record, names::get, TABLE));
		record.setLocationRegion(SMOKE);
		assertEquals("a spot comes first", "Smoke Dungeon", TaskLocation.displayName(record, names::get, TABLE));
	}

	@Test
	public void withoutTheTableOnlyThePlayersNamesAreKnown()
	{
		Map<Integer, String> names = new HashMap<>();
		LedgerRecord record = new LedgerRecord();
		record.setLocationRegion(SMOKE);
		record.setSpotId(SMOKE_ID);
		assertNull(TaskLocation.recordLocation(record, names::get, SlayerSpots.NONE));
		assertNull(TaskLocation.recordLocation(record, names::get, null));
		names.put(SMOKE, "Smoke Dungeon");
		assertEquals("Smoke Dungeon", TaskLocation.recordLocation(record, names::get, SlayerSpots.NONE));
		record.setSpotId(UNLISTED_KEY);
		record.setLocationRegion(UNLISTED);
		assertEquals("an unlisted region's key still names it", "Region 12342",
			TaskLocation.recordLocation(record, names::get, SlayerSpots.NONE));
	}

	@Test
	public void theLiveNameIsTheSpotsNameAfterTheRegionsName()
	{
		Map<Integer, String> names = new HashMap<>();
		assertNull("no kills yet", TaskLocation.liveName(TaskSpot.UNKNOWN, names::get, TABLE));
		assertNull(TaskLocation.liveName(null, names::get, TABLE));
		TaskSpot spot = new TaskSpot(CATACOMBS_ID, CATACOMBS);
		assertEquals("Catacombs of Kourend", TaskLocation.liveName(spot, names::get, TABLE));
		names.put(CATACOMBS, "Cata");
		assertEquals("Cata", TaskLocation.liveName(spot, names::get, TABLE));
		assertEquals("an Unknown spot in a region the player named", "Kraken",
			TaskLocation.liveName(new TaskSpot(null, KRAKEN_SHARED), k -> k == KRAKEN_SHARED ? "Kraken" : null, TABLE));
		assertEquals("Location: Smoke Dungeon", TaskLocation.format("Smoke Dungeon"));
		assertEquals("Location: Unknown", TaskLocation.format(null));
	}

	@Test
	public void theCurrentLocationIsTheSessionsSpotThenTheAreaThenWhereThePlayerIs()
	{
		Map<Integer, String> names = new HashMap<>();
		TaskLocation.Here nowhere = TaskLocation.current(null, TaskSpot.UNKNOWN, LocationTally.NO_REGION, null,
			names::get, TABLE);
		assertNull(nowhere.getSpot());
		assertNull(nowhere.getName());
		assertFalse(nowhere.isKnown());
		assertEquals("where the player is", new TaskLocation.Here(TOWER_ID, "Slayer Tower"),
			TaskLocation.current(null, TaskSpot.UNKNOWN, TOWER, TOWER_ID, names::get, TABLE));
		assertEquals("the assigned area before where the player stands, with the spot of that name",
			new TaskLocation.Here(KRAKEN_COVE_ID, "Kraken Cove"),
			TaskLocation.current(" Kraken Cove ", TaskSpot.UNKNOWN, TOWER, TOWER_ID, names::get, TABLE));
		TaskLocation.Here noSuchSpot = TaskLocation.current("Somewhere else", TaskSpot.UNKNOWN, TOWER, TOWER_ID,
			names::get, TABLE);
		assertEquals("an area the table has no spot of", new TaskLocation.Here(null, "Somewhere else"), noSuchSpot);
		assertFalse("so no task matches it", noSuchSpot.isKnown());
		assertEquals("the session's spot first", new TaskLocation.Here(SMOKE_ID, "Smoke Dungeon"),
			TaskLocation.current("Kraken Cove", new TaskSpot(SMOKE_ID, SMOKE), TOWER, TOWER_ID, names::get, TABLE));
		assertEquals("a session whose spot is Unknown is passed over", new TaskLocation.Here(TOWER_ID, "Slayer Tower"),
			TaskLocation.current(null, new TaskSpot(null, KRAKEN_SHARED), TOWER, TOWER_ID, names::get, TABLE));
		names.put(TOWER, "Tower");
		assertEquals("the player's name for the region, the same spot", new TaskLocation.Here(TOWER_ID, "Tower"),
			TaskLocation.current(null, TaskSpot.UNKNOWN, TOWER, TOWER_ID, names::get, TABLE));
		assertEquals("an unlisted region where the player is", new TaskLocation.Here(UNLISTED_KEY, "Region 12342"),
			TaskLocation.current(null, TaskSpot.UNKNOWN, UNLISTED, UNLISTED_KEY, names::get, TABLE));
	}

	@Test
	public void aSpotIsFoundByItsFullOrShortNameWhenOnlyOneSpotHasIt()
	{
		assertEquals(KRAKEN_COVE_ID, TABLE.spotNamed("Kraken Cove"));
		assertEquals("case and spacing ignored", KRAKEN_COVE_ID, TABLE.spotNamed("  kraken   COVE "));
		assertEquals("the short name", CATACOMBS_ID, TABLE.spotNamed("Catacombs"));
		assertEquals(CATACOMBS_ID, TABLE.spotNamed("Catacombs of Kourend"));
		assertNull(TABLE.spotNamed("Nowhere"));
		assertNull(TABLE.spotNamed(null));
		assertNull(TABLE.spotNamed(" "));
		assertNull("no table", SlayerSpots.NONE.spotNamed("Kraken Cove"));
	}

	@Test
	public void locationsMatchIgnoringCaseAndSpacing()
	{
		assertEquals(TaskLocation.matchKey("Smoke Dungeon"), TaskLocation.matchKey("  smoke   DUNGEON "));
		assertNotEquals(TaskLocation.matchKey("Smoke Dungeon"), TaskLocation.matchKey("Smoke Devil Dungeon"));
		assertEquals("smoke dungeon", TaskLocation.matchKey(" Smoke\tDungeon "));
		// No name has no key, so it never matches a name or another no name.
		assertNull(TaskLocation.matchKey(null));
		assertNull(TaskLocation.matchKey(" "));
		assertNull(TaskLocation.matchKey(""));
	}

	@Test
	public void theDialogsSayWhatTheyChange()
	{
		LedgerRecord record = new LedgerRecord();
		record.setMonster("Dust devils");
		assertEquals("Location of this Dust devils task only (empty to remove its own name):",
			TaskLocation.editPrompt(record));
		assertEquals("Name of Region 12342, for every task there:", TaskLocation.renamePrompt(UNLISTED));
	}
}
