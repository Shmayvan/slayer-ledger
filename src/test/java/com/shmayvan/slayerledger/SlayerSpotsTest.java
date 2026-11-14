package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.TestSpots.TABLE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.google.gson.Gson;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * The bundled Slayer spot table: its metadata and credits, the integrity of every spot, the matching rule in
 * every shared region of the research notes, the lookups by region (records saved before spots) and loading bad data.
 */
public class SlayerSpotsTest
{
	private static final Gson GSON = new Gson();
	private static final Pattern ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
	private static final Pattern WIKI_SOURCE = Pattern.compile(
		"https://oldschool\\.runescape\\.wiki/w/[^?\\s]+\\?oldid=[0-9]+");
	/** The NPC dump the coordinates were verified with, cited where it supplied region ids. */
	private static final Pattern DUMP_SOURCE = Pattern.compile(
		"https://github\\.com/mejrs/data_osrs/blob/[0-9a-f]{40}/[A-Za-z_]+\\.json");

	/** One bounds box of a shared region, as the research notes' "Shared regions" table gives it. */
	private static final class Box
	{
		private final int region;
		private final String spot;
		private final int x1;
		private final int y1;
		private final int x2;
		private final int y2;

		private Box(int region, String spot, int x1, int y1, int x2, int y2)
		{
			this.region = region;
			this.spot = spot;
			this.x1 = x1;
			this.y1 = y1;
			this.x2 = x2;
			this.y2 = y2;
		}
	}

	private static Box box(int region, String spot, int x1, int y1, int x2, int y2)
	{
		return new Box(region, spot, x1, y1, x2, y2);
	}

	/**
	 * Every bounds-decided shared region of the research notes ("Shared regions"; region 5536 is decided by plane and
	 * checked apart), with the spot each box belongs to.
	 */
	private static final List<Box> README_BOXES = Arrays.asList(
		box(4918, "kebos-lowlands", 1216, 3456, 1279, 3484),
		box(4918, "mount-quidamortem", 1216, 3485, 1279, 3519),
		box(5175, "lizardman-settlement", 1280, 3520, 1343, 3572),
		box(5175, "xerics-shrine", 1280, 3573, 1343, 3583),
		box(5176, "kebos-swamp", 1280, 3610, 1343, 3647),
		box(5176, "xerics-shrine", 1280, 3584, 1343, 3609),
		box(5177, "battlefront", 1280, 3690, 1343, 3702),
		box(5177, "great-kourend", 1280, 3703, 1343, 3711),
		box(5177, "molch", 1280, 3648, 1343, 3689),
		box(5682, "mons-gratia", 1408, 3236, 1471, 3263),
		box(5682, "ralos-rise", 1408, 3200, 1471, 3235),
		box(5690, "lizardman-canyon", 1408, 3712, 1471, 3731),
		box(5690, "lovakengj", 1408, 3732, 1471, 3775),
		box(5943, "graveyard-of-heroes", 1472, 3546, 1535, 3583),
		box(5943, "shayzien", 1472, 3520, 1535, 3545),
		box(5946, "lizardman-canyon", 1472, 3712, 1535, 3737),
		box(5946, "lovakengj", 1472, 3738, 1535, 3775),
		box(6203, "arceuus", 1561, 3776, 1599, 3839),
		box(6203, "lovakengj", 1536, 3776, 1560, 3839),
		box(6701, "colossal-wyrm-remains", 1664, 2880, 1727, 2918),
		box(6701, "stonecutter-outpost", 1664, 2919, 1727, 2943),
		box(6972, "dark-altar", 1728, 3840, 1762, 3903),
		box(6972, "dense-essence-mine", 1763, 3840, 1791, 3903),
		box(8008, "mourner-headquarters", 2036, 4608, 2047, 4671),
		box(8008, "mourner-tunnels", 1984, 4608, 2035, 4671),
		box(8754, "iorwerth-camp", 2176, 3232, 2239, 3263),
		box(8754, "isafdar", 2176, 3200, 2239, 3231),
		box(9116, "kraken-cove", 2240, 9984, 2303, 10019),
		box(9116, "kraken-cove", 2240, 10020, 2267, 10047),
		box(9116, "kraken-boss-room", 2268, 10020, 2303, 10047),
		box(9778, "grave-of-scorpius", 2432, 3220, 2495, 3263),
		box(9778, "observatory", 2432, 3200, 2495, 3219),
		box(10287, "gutanoth", 2560, 3008, 2623, 3051),
		box(10287, "yanille", 2560, 3052, 2623, 3071),
		box(10294, "coal-trucks", 2568, 3456, 2623, 3519),
		box(10294, "fishing-guild", 2560, 3456, 2567, 3519),
		box(10547, "ardougne-zoo", 2624, 3264, 2687, 3297),
		box(10547, "handelmort-mansion", 2624, 3298, 2687, 3327),
		box(10904, "legends-guild", 2720, 9728, 2751, 9791),
		box(10904, "legends-guild-dungeon", 2688, 9728, 2719, 9791),
		box(11051, "marim", 2752, 2752, 2792, 2815),
		box(11051, "temple-of-marimbo", 2793, 2752, 2815, 2815),
		box(11416, "entrana-dungeon", 2816, 9728, 2879, 9757),
		box(11416, "taverley-dungeon", 2816, 9758, 2879, 9791),
		box(11418, "ice-queens-lair", 2816, 9891, 2879, 9919),
		box(11418, "white-wolf-mountain", 2816, 9856, 2879, 9890),
		box(11675, "ice-queens-lair", 2880, 9920, 2898, 9983),
		box(11675, "warriors-guild", 2899, 9920, 2943, 9983),
		box(11826, "port-sarim", 2978, 3200, 3007, 3263),
		box(11826, "rimmington", 2944, 3200, 2977, 3263),
		box(11830, "ice-mountain", 2988, 3456, 3007, 3519),
		box(11830, "mind-altar", 2944, 3456, 2987, 3519),
		box(11929, "dwarven-mine", 2961, 9792, 3007, 9855),
		box(11929, "taverley-dungeon", 2944, 9792, 2960, 9855),
		box(12086, "black-knights-fortress", 3008, 3484, 3071, 3519),
		box(12086, "dwarven-mine", 3008, 3456, 3071, 3457),
		box(12086, "ice-mountain", 3008, 3458, 3071, 3483),
		box(12092, "king-black-dragon-lair", 3008, 3840, 3020, 3903),
		box(12092, "lava-maze", 3021, 3840, 3071, 3903),
		box(12345, "ferox-enclave", 3072, 3684, 3135, 3711),
		box(12345, "graveyard-of-shadows", 3072, 3648, 3135, 3683),
		box(12349, "mage-arena", 3072, 3904, 3135, 3932),
		box(12349, "wilderness", 3072, 3933, 3135, 3967),
		box(12594, "lumbridge", 3136, 3226, 3199, 3263),
		box(12594, "lumbridge-swamp", 3136, 3200, 3199, 3225),
		box(12595, "freds-farm", 3193, 3264, 3199, 3327),
		box(12595, "lumbridge", 3136, 3264, 3192, 3327),
		box(12603, "lava-dragon-isle", 3168, 3776, 3199, 3839),
		box(12603, "wilderness", 3136, 3776, 3167, 3839),
		box(12605, "magic-axe-hut", 3136, 3958, 3199, 3967),
		box(12605, "wilderness-resource-area", 3136, 3904, 3199, 3957),
		box(12698, "edgeville-dungeon", 3136, 9856, 3153, 9919),
		box(12698, "varrock-sewers", 3154, 9856, 3199, 9919),
		box(13357, "nardah", 3371, 2880, 3391, 2943),
		box(13357, "river-elid", 3328, 2880, 3370, 2943),
		box(13615, "kharidian-desert", 3392, 3016, 3455, 3071),
		box(13615, "nardah", 3392, 3008, 3455, 3015),
		box(14387, "darkmeyer", 3584, 3324, 3647, 3327),
		box(14387, "meiyerditch", 3584, 3264, 3647, 3323),
		box(14389, "darkmeyer", 3584, 3392, 3647, 3406),
		box(14389, "haunted-woods", 3584, 3407, 3647, 3455));

	@Test
	public void theBundledTableLoadsWithItsMetadataAndCredits()
	{
		assertTrue(TABLE.isAvailable());
		assertEquals(261, TABLE.spots().size());
		Set<Integer> regions = new HashSet<>();
		for (SlayerSpots.Spot spot : TABLE.spots())
		{
			regions.addAll(spot.getRegions());
		}
		assertEquals(471, regions.size());
		assertEquals("2026-11-09", TABLE.getRetrieved());
		assertEquals("Old School RuneScape Wiki", TABLE.getSource());
		assertEquals("CC BY-NC-SA 3.0", TABLE.getLicense());
		assertTrue(TABLE.getAttribution().contains("Old School RuneScape Wiki contributors"));
		assertTrue(TABLE.getAttribution().contains("CC BY-NC-SA 3.0"));
		assertTrue("the verification data is named", TABLE.getVerifiedWith().contains("mejrs/data_osrs"));
	}

	@Test
	public void everySpotHasAnIdNameShortNameRegionsTasksAndAWikiSource()
	{
		Set<String> ids = new HashSet<>();
		for (SlayerSpots.Spot spot : TABLE.spots())
		{
			String id = spot.getId();
			assertTrue("id: " + id, ID.matcher(id).matches());
			assertTrue("unique: " + id, ids.add(id));
			assertFalse(id, spot.getName().trim().isEmpty());
			assertEquals(id, spot.getName().trim(), spot.getName());
			assertFalse(id, spot.getShortName().trim().isEmpty());
			assertTrue(id + ": " + spot.getShortName(),
				spot.getShortName().length() <= SlayerSpots.MAX_SHORT_NAME_LENGTH);
			assertFalse(id, spot.getRegions().isEmpty());
			assertFalse(id, spot.getTasks().isEmpty());
			assertTrue(id, Arrays.asList("high", "medium", "low").contains(spot.getConfidence()));
			boolean wiki = false;
			for (String source : spot.getSources())
			{
				assertTrue(id + ": " + source, WIKI_SOURCE.matcher(source).matches()
					|| DUMP_SOURCE.matcher(source).matches());
				wiki |= WIKI_SOURCE.matcher(source).matches();
			}
			assertTrue("an OSRS Wiki revision: " + id, wiki);
			assertSame(spot, TABLE.spot(id));
			assertEquals(spot.getName(), TABLE.name(id));
			assertEquals(spot.getShortName(), TABLE.shortName(id));
		}
	}

	@Test
	public void everyTaskIsACoreSlayerTaskName() throws Exception
	{
		Set<String> coreNames = coreTaskNames();
		for (SlayerSpots.Spot spot : TABLE.spots())
		{
			for (String task : spot.getTasks())
			{
				assertTrue("Not a core Task name: " + task + " (" + spot.getId() + ")", coreNames.contains(task));
			}
		}
	}

	/**
	 * In every region listed by several spots, no tile on any plane matches two of them, every tile on plane 0
	 * matches one, and every bounds box lies inside one of its spot's shared regions.
	 */
	@Test
	public void noRegionAndPlaneIsClaimedTwiceWithoutBoundsThatSeparateThem()
	{
		Map<Integer, List<SlayerSpots.Spot>> byRegion = new HashMap<>();
		for (SlayerSpots.Spot spot : TABLE.spots())
		{
			for (int region : spot.getRegions())
			{
				byRegion.computeIfAbsent(region, r -> new ArrayList<>()).add(spot);
			}
		}
		int shared = 0;
		for (Map.Entry<Integer, List<SlayerSpots.Spot>> entry : byRegion.entrySet())
		{
			List<SlayerSpots.Spot> spots = entry.getValue();
			if (spots.size() < 2)
			{
				continue;
			}
			shared++;
			int region = entry.getKey();
			int baseX = (region >> 8) << 6;
			int baseY = (region & 0xFF) << 6;
			for (int plane = 0; plane < 4; plane++)
			{
				for (int x = baseX; x < baseX + 64; x++)
				{
					for (int y = baseY; y < baseY + 64; y++)
					{
						int matches = 0;
						for (SlayerSpots.Spot spot : spots)
						{
							matches += spot.matches(x, y, plane) ? 1 : 0;
						}
						assertTrue("claimed twice: region " + region + " (" + x + ", " + y + ", " + plane + ")",
							matches <= 1);
						assertTrue("unclaimed: region " + region + " (" + x + ", " + y + ")", plane > 0 || matches == 1);
					}
				}
			}
		}
		assertEquals("the research notes' 40 shared regions", 40, shared);
		for (SlayerSpots.Spot spot : TABLE.spots())
		{
			for (int[] box : spot.getBounds())
			{
				int region = SlayerSpots.regionOf(box[0], box[1]);
				assertEquals(spot.getId(), region, SlayerSpots.regionOf(box[2], box[3]));
				assertTrue(spot.getId(), spot.getRegions().contains(region));
				assertTrue(spot.getId() + " " + region, byRegion.get(region).size() > 1);
			}
		}
	}

	@Test
	public void theMatchingRulePicksTheResearchNotesSpotInEveryBoundedSharedRegion()
	{
		Set<Integer> regions = new TreeSet<>();
		for (Box b : README_BOXES)
		{
			regions.add(b.region);
			assertEquals(b.region, SlayerSpots.regionOf(b.x1, b.y1));
			int midX = (b.x1 + b.x2) / 2;
			int midY = (b.y1 + b.y2) / 2;
			for (int[] point : new int[][]{{b.x1, b.y1}, {b.x2, b.y2}, {b.x1, b.y2}, {b.x2, b.y1}, {midX, midY}})
			{
				for (int plane = 0; plane < 4; plane++)
				{
					assertEquals(b.region + " (" + point[0] + ", " + point[1] + ", " + plane + ")", b.spot,
						TABLE.spotAt(point[0], point[1], plane));
				}
			}
		}
		assertEquals(39, regions.size());
	}

	@Test
	public void theHydraLairAndKaruulmShareRegion5536ByPlane()
	{
		// The Alchemical Hydra's spawn is on plane 0; the drakes above it, on plane 1, are Karuulm's.
		assertEquals(TestSpots.KARUULM_SHARED, SlayerSpots.regionOf(1364, 10265));
		assertEquals(TestSpots.HYDRA_LAIR_ID, TABLE.spotAt(1364, 10265, 0));
		assertEquals(TestSpots.KARUULM_ID, TABLE.spotAt(1364, 10265, 1));
		assertNull("no spot of 5536 is on plane 2", TABLE.spotAt(1364, 10265, 2));
		// Karuulm's plane counts only in the shared region: its other regions are its on every plane.
		assertEquals(5_022, SlayerSpots.regionOf(1250, 10150));
		assertEquals(TestSpots.KARUULM_ID, TABLE.spotAt(1250, 10150, 0));
	}

	@Test
	public void theKrakensBoxIsItsBossRoom()
	{
		// The Kraken, its tentacles and where the crevice leads are in the boss room; the cove is south and west.
		assertEquals(TestSpots.KRAKEN_SHARED, SlayerSpots.regionOf(2278, 10034));
		assertEquals(TestSpots.KRAKEN_ROOM_ID, TABLE.spotAt(2278, 10034, 0));
		assertEquals(TestSpots.KRAKEN_ROOM_ID, TABLE.spotAt(2275, 10038, 0));
		assertEquals(TestSpots.KRAKEN_ROOM_ID, TABLE.spotAt(2280, 10022, 0));
		assertEquals(TestSpots.KRAKEN_COVE_ID, TABLE.spotAt(2280, 10016, 0));
		assertEquals(TestSpots.KRAKEN_COVE_ID, TABLE.spotAt(2267, 10034, 0));
		assertEquals(TestSpots.KRAKEN_ROOM_ID, TABLE.spotAt(2268, 10020, 0));
		assertEquals(TestSpots.KRAKEN_COVE_ID, TABLE.spotAt(2268, 10019, 0));
	}

	@Test
	public void aRegionListedByOneSpotIsItsOnEveryPlaneAndTheSanityAnchorHolds()
	{
		// The research notes' sanity anchor: a dust devil at (1708, 10014) is in the Catacombs.
		assertEquals(TestSpots.CATACOMBS_ID, TABLE.spotAt(1708, 10014, 0));
		assertEquals(TestSpots.CATACOMBS_ID, TABLE.spotAt(1708, 10014, 3));
		assertEquals(TestSpots.STRONGHOLD, SlayerSpots.regionOf(2420, 9790));
		assertEquals(TestSpots.STRONGHOLD_ID, TABLE.spotAt(2420, 9790, 0));
		assertEquals(TestSpots.CORSAIR, SlayerSpots.regionOf(1950, 8990));
		assertEquals(TestSpots.CORSAIR_ID, TABLE.spotAt(1950, 8990, 0));
	}

	@Test
	public void anUnlistedRegionIsASpotOfItsOwn()
	{
		int x = 3093;
		int y = 3491;
		assertEquals(TestSpots.UNLISTED, SlayerSpots.regionOf(x, y));
		assertTrue(TABLE.spotsInRegion(TestSpots.UNLISTED).isEmpty());
		assertEquals("region:12342", TABLE.spotAt(x, y, 0));
		assertEquals("region:12342", SlayerSpots.regionKey(TestSpots.UNLISTED));
		assertEquals(Integer.valueOf(TestSpots.UNLISTED), SlayerSpots.keyRegion("region:12342"));
		assertNull(SlayerSpots.keyRegion("region:x"));
		assertNull(SlayerSpots.keyRegion("region:-3"));
		assertNull(SlayerSpots.keyRegion(TestSpots.SMOKE_ID));
		assertNull(SlayerSpots.keyRegion(null));
		assertEquals("Region 12342", TABLE.name("region:12342"));
		assertEquals("Region 12342", TABLE.shortName("region:12342"));
		assertNull(TABLE.name(null));
		assertNull(TABLE.name("no-such-spot"));
		assertNull(TABLE.shortName("no-such-spot"));
		assertFalse("an unlisted region lists no task", TABLE.listsTask("region:12342", "Dust devils"));
	}

	@Test
	public void aRegionAloneGivesItsSpotOrTheOneSharedSpotListingTheTask()
	{
		assertEquals(TestSpots.SMOKE_ID, TABLE.spotForRegion(TestSpots.SMOKE, "Bloodveld"));
		assertEquals(TestSpots.CATACOMBS_ID, TABLE.spotForRegion(TestSpots.CATACOMBS, null));
		assertEquals("region:12342", TABLE.spotForRegion(TestSpots.UNLISTED, "Dust devils"));
		// Shared: Kraken Cove lists waterfiends, the boss room doesn't; both list cave kraken.
		assertEquals(TestSpots.KRAKEN_COVE_ID, TABLE.spotForRegion(TestSpots.KRAKEN_SHARED, " waterfiends "));
		assertNull(TABLE.spotForRegion(TestSpots.KRAKEN_SHARED, "Cave kraken"));
		assertNull("none lists it", TABLE.spotForRegion(TestSpots.KRAKEN_SHARED, "Dust devils"));
		assertNull(TABLE.spotForRegion(TestSpots.KRAKEN_SHARED, null));
		assertEquals("only Karuulm lists drakes", TestSpots.KARUULM_ID,
			TABLE.spotForRegion(TestSpots.KARUULM_SHARED, "Drakes"));
		assertNull("both list hydras", TABLE.spotForRegion(TestSpots.KARUULM_SHARED, "Hydras"));
		assertNull(TABLE.spotForRegion(null, "Dust devils"));
		assertNull(TABLE.spotForRegion(LocationTally.NO_REGION, "Dust devils"));
	}

	@Test
	public void aRecordsSpotIsItsOwnElseItsRegionsWorkedOutWhenRead()
	{
		LedgerRecord record = new LedgerRecord();
		record.setMonster("Aberrant spectres");
		assertNull("no region at all: Unknown", TABLE.recordSpot(record));
		record.setLocationRegion(TestSpots.STRONGHOLD);
		assertEquals(TestSpots.STRONGHOLD_ID, TABLE.recordSpot(record));
		assertNull("the record is not changed", record.getSpotId());
		record.setSpotId(TestSpots.CATACOMBS_ID);
		assertEquals("a stored spot wins", TestSpots.CATACOMBS_ID, TABLE.recordSpot(record));
		record.setSpotId(" ");
		assertEquals(TestSpots.STRONGHOLD_ID, TABLE.recordSpot(record));
		record.setSpotId(null);
		record.setMonster("Waterfiends");
		record.setLocationRegion(TestSpots.KRAKEN_SHARED);
		assertEquals(TestSpots.KRAKEN_COVE_ID, TABLE.recordSpot(record));
		record.setMonster("Cave kraken");
		assertNull("a shared region two of whose spots list the task: Unknown", TABLE.recordSpot(record));
		assertNull(TABLE.recordSpot(null));
	}

	@Test
	public void aStoredSpotTheTableDoesNotKnowTakesItsRegionsSpot()
	{
		// An id a later table dropped or renamed, or a key that isn't one: treated as no id.
		LedgerRecord record = new LedgerRecord();
		record.setMonster("Dust devils");
		record.setLocationRegion(TestSpots.SMOKE);
		record.setSpotId("smoke-dungeon-before");
		assertFalse(TABLE.knows("smoke-dungeon-before"));
		assertEquals(TestSpots.SMOKE_ID, TABLE.recordSpot(record));
		record.setSpotId("region:abc");
		assertEquals(TestSpots.SMOKE_ID, TABLE.recordSpot(record));
		assertEquals("the record is not changed", "region:abc", record.getSpotId());
		record.setSpotId(" region:" + TestSpots.UNLISTED + " ");
		assertEquals("an unlisted region's key is known", "region:" + TestSpots.UNLISTED, TABLE.recordSpot(record));
		record.setSpotId("region:" + TestSpots.SMOKE);
		assertFalse("a region key of a region the table now lists", TABLE.knows("region:" + TestSpots.SMOKE));
		assertEquals("moves to that region's spot", TestSpots.SMOKE_ID, TABLE.recordSpot(record));
		record.setSpotId(TestSpots.CATACOMBS_ID);
		assertEquals("a known id wins over the region", TestSpots.CATACOMBS_ID, TABLE.recordSpot(record));
		record.setSpotId("gone");
		record.setLocationRegion(null);
		assertNull("no region either: Unknown", TABLE.recordSpot(record));
		assertEquals("without the table, a stored id is kept", "gone", SlayerSpots.NONE.recordSpot(record));
	}

	@Test
	public void withoutTheTableNoSpotIsGiven()
	{
		assertFalse(SlayerSpots.NONE.isAvailable());
		assertTrue(SlayerSpots.NONE.spots().isEmpty());
		assertNull(SlayerSpots.NONE.spotAt(3093, 3491, 0));
		assertNull("listed and unlisted can't be told apart", SlayerSpots.NONE.spotForRegion(TestSpots.UNLISTED, null));
		LedgerRecord record = new LedgerRecord();
		record.setLocationRegion(TestSpots.SMOKE);
		assertNull(SlayerSpots.NONE.recordSpot(record));
		record.setSpotId(TestSpots.SMOKE_ID);
		assertEquals("a stored spot is still the record's", TestSpots.SMOKE_ID, SlayerSpots.NONE.recordSpot(record));
		assertNull(SlayerSpots.NONE.name(TestSpots.SMOKE_ID));
		assertNull(SlayerSpots.NONE.getRetrieved());
	}

	@Test
	public void missingMalformedOrInvalidResourcesLoadAsNone()
	{
		assertSame(SlayerSpots.NONE, SlayerSpots.load(GSON, "no-such-slayer-spots.json"));
		assertSame(SlayerSpots.NONE, SlayerSpots.load(GSON, "slayer-spots-malformed.json"));
		// Valid JSON, but the same id twice.
		assertSame(SlayerSpots.NONE, SlayerSpots.load(GSON, "slayer-spots-invalid.json"));
		assertSame(SlayerSpots.NONE, SlayerSpots.load(null, SlayerSpots.SNAPSHOT_RESOURCE));
	}

	@Test
	public void validationRejectsBadTables()
	{
		assertNotNull(SlayerSpots.parse(GSON, new StringReader(table(1, SPOT))));
		assertRejected("null");
		assertRejected(table(2, SPOT));
		assertRejected(table(1, ""));
		assertRejected(table(1, "null"));
		assertRejected(table(1, SPOT + ", " + SPOT));
		assertRejected(table(1, SPOT.replace("\"id\": \"smoke-dungeon\"", "\"id\": \" \"")));
		assertRejected(table(1, SPOT.replace("\"id\": \"smoke-dungeon\"", "\"id\": \"region:5\"")));
		assertRejected(table(1, SPOT.replace("\"name\": \"Smoke Dungeon\"", "\"name\": \"\"")));
		assertRejected(table(1, SPOT.replace("\"short\": \"Smoke Dungeon\"", "\"short\": \"Smoke Dungeon west\"")));
		assertRejected(table(1, SPOT.replace("[12946]", "[]")));
		assertRejected(table(1, SPOT.replace("[12946]", "[-1]")));
		assertRejected(table(1, SPOT.replace("[\"Dust devils\"]", "[\" \"]")));
		assertRejected(table(1, SPOT.replace("\"sources\": [\"s\"]", "\"sources\": []")));
		assertRejected(table(1, SPOT.replace("\"plane\": null", "\"plane\": 4")));
		assertRejected(table(1, SPOT.replace("\"bounds\": null", "\"bounds\": [[1, 2, 3]]")));
		assertRejected(table(1, SPOT.replace("\"bounds\": null", "\"bounds\": [[5, 2, 3, 4]]")));
		assertRejected(table(1, SPOT).replace("\"matching\": \"m\", ", ""));
	}

	private static final String SPOT = "{ \"id\": \"smoke-dungeon\", \"name\": \"Smoke Dungeon\", "
		+ "\"short\": \"Smoke Dungeon\", \"regions\": [12946], \"plane\": null, \"bounds\": null, "
		+ "\"tasks\": [\"Dust devils\"], \"sources\": [\"s\"], \"confidence\": \"high\" }";

	private static String table(int version, String spots)
	{
		return "{ \"version\": " + version + ", \"source\": \"Old School RuneScape Wiki\", "
			+ "\"license\": \"CC BY-NC-SA 3.0\", \"licenseUrl\": \"https://creativecommons.org/licenses/by-nc-sa/3.0/\", "
			+ "\"contributors\": \"Old School RuneScape Wiki contributors\", \"retrieved\": \"2026-11-09\", "
			+ "\"attribution\": \"Test\", \"matching\": \"m\", \"spots\": [" + spots + "] }";
	}

	private static void assertRejected(String json)
	{
		try
		{
			SlayerSpots.parse(GSON, new StringReader(json));
			fail("Expected the table to be rejected: " + json);
		}
		catch (RuntimeException expected)
		{
			// Rejected, as load() would turn into NONE.
		}
	}

	/** The names in RuneLite's core Slayer {@code Task} enum (package-private, so read by reflection). */
	static Set<String> coreTaskNames() throws Exception
	{
		Class<?> taskClass = Class.forName("net.runelite.client.plugins.slayer.Task");
		Method getName = taskClass.getDeclaredMethod("getName");
		getName.setAccessible(true);
		Set<String> names = new HashSet<>();
		for (Object task : taskClass.getEnumConstants())
		{
			names.add((String) getName.invoke(task));
		}
		assertTrue(names.size() > 100);
		return names;
	}
}
