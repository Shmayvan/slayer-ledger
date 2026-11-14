package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.TestSpots.CATACOMBS;
import static com.shmayvan.slayerledger.TestSpots.CATACOMBS_ID;
import static com.shmayvan.slayerledger.TestSpots.CATACOMBS_WEST;
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
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * The kills per place of a session: adding, cleaning a stored tally, and the task's spot from it with its
 * ties ({@link LocationTally#decide}).
 */
public class LocationTallyTest
{
	private static final String DEVILS = "Dust devils";

	private static List<LocationKills> tally(Object... placeKills)
	{
		List<LocationKills> tally = null;
		for (int i = 0; i < placeKills.length; i += 2)
		{
			tally = LocationTally.add(tally, (KillPlace) placeKills[i], (Integer) placeKills[i + 1]);
		}
		return tally;
	}

	private static KillPlace centre(int region)
	{
		return TestSpots.centre(region);
	}

	@Test
	public void addsKillsPerPlaceTheMostRecentLast()
	{
		List<LocationKills> tally = LocationTally.add(null, centre(SMOKE), 3);
		tally = LocationTally.add(tally, centre(CATACOMBS), 1);
		List<LocationKills> before = tally;
		tally = LocationTally.add(tally, centre(SMOKE), 2);
		assertEquals(Arrays.asList(new LocationKills(CATACOMBS, CATACOMBS_ID, 1), new LocationKills(SMOKE, SMOKE_ID, 5)),
			tally);
		assertEquals("the list passed in is not changed",
			Arrays.asList(new LocationKills(SMOKE, SMOKE_ID, 3), new LocationKills(CATACOMBS, CATACOMBS_ID, 1)), before);
	}

	@Test
	public void aRegionSharedByTwoSpotsIsTwoPlaces()
	{
		List<LocationKills> tally = tally(TestSpots.place(2250, 10000, 0), 2, TestSpots.place(2278, 10034, 0), 3,
			KillPlace.ofRegion(KRAKEN_SHARED), 1);
		assertEquals(Arrays.asList(new LocationKills(KRAKEN_SHARED, KRAKEN_COVE_ID, 2),
			new LocationKills(KRAKEN_SHARED, KRAKEN_ROOM_ID, 3), new LocationKills(KRAKEN_SHARED, 1)), tally);
	}

	@Test
	public void unknownPlacesAndNoKillsAreNotTallied()
	{
		List<LocationKills> tally = LocationTally.add(null, KillPlace.NONE, 4);
		tally = LocationTally.add(tally, LocationTally.NO_REGION, 4);
		assertTrue(tally.isEmpty());
		tally = LocationTally.add(tally, centre(SMOKE), 0);
		tally = LocationTally.add(tally, centre(SMOKE), -2);
		assertTrue(tally.isEmpty());
		assertEquals(TaskSpot.UNKNOWN, LocationTally.decide(tally, TABLE, DEVILS));
		assertEquals(TaskSpot.UNKNOWN, LocationTally.decide(null, TABLE, DEVILS));
	}

	@Test
	public void theSpotWithTheMostKillsWinsAcrossItsRegions()
	{
		// 2 + 2 in the Catacombs' two regions beat 3 in the Smoke Dungeon's one.
		List<LocationKills> tally = tally(centre(CATACOMBS_WEST), 2, centre(SMOKE), 3, centre(CATACOMBS), 2);
		assertEquals("its region is the one with the most kills there, the most recent on a tie",
			new TaskSpot(CATACOMBS_ID, CATACOMBS), LocationTally.decide(tally, TABLE, DEVILS));
		tally = LocationTally.add(tally, centre(CATACOMBS_WEST), 1);
		assertEquals(new TaskSpot(CATACOMBS_ID, CATACOMBS_WEST), LocationTally.decide(tally, TABLE, DEVILS));
	}

	@Test
	public void aTieGoesToTheSpotListingTheTaskThenToTheMostRecentKill()
	{
		// The Slayer Tower lists no dust devils, so the Smoke Dungeon wins the tie although the tower is more recent.
		List<LocationKills> tally = tally(centre(SMOKE), 2, centre(TOWER), 2);
		assertEquals(new TaskSpot(SMOKE_ID, SMOKE), LocationTally.decide(tally, TABLE, DEVILS));
		assertEquals("without the task's name, the most recent", new TaskSpot(TOWER_ID, TOWER),
			LocationTally.decide(tally, TABLE, null));
		// Both list dust devils: the most recent kill decides.
		tally = tally(centre(SMOKE), 2, centre(CATACOMBS), 2);
		assertEquals(new TaskSpot(CATACOMBS_ID, CATACOMBS), LocationTally.decide(tally, TABLE, DEVILS));
		tally = tally(centre(CATACOMBS), 2, centre(SMOKE), 2);
		assertEquals(new TaskSpot(SMOKE_ID, SMOKE), LocationTally.decide(tally, TABLE, DEVILS));
	}

	@Test
	public void killsTalliedByRegionAloneTakeTheirRegionsSpot()
	{
		List<LocationKills> tally = Collections.singletonList(new LocationKills(SMOKE, 3));
		assertEquals(new TaskSpot(SMOKE_ID, SMOKE), LocationTally.decide(tally, TABLE, DEVILS));
		assertEquals("no table: no spot, the region kept", new TaskSpot(null, SMOKE),
			LocationTally.decide(tally, SlayerSpots.NONE, DEVILS));
		assertEquals("an unlisted region is a spot of its own", new TaskSpot("region:" + UNLISTED, UNLISTED),
			LocationTally.decide(Collections.singletonList(new LocationKills(UNLISTED, 1)), TABLE, DEVILS));
	}

	@Test
	public void aSharedRegionAloneIsDecidedByTheTaskOrNotAtAll()
	{
		List<LocationKills> kraken = Collections.singletonList(new LocationKills(KRAKEN_SHARED, 5));
		assertEquals("only Kraken Cove lists waterfiends", new TaskSpot(KRAKEN_COVE_ID, KRAKEN_SHARED),
			LocationTally.decide(kraken, TABLE, "Waterfiends"));
		assertEquals("both list cave kraken: Unknown, the region kept", new TaskSpot(null, KRAKEN_SHARED),
			LocationTally.decide(kraken, TABLE, "Cave kraken"));
		// Kills with no spot count for none: one kill in the Catacombs is the task's spot.
		List<LocationKills> mixed = new ArrayList<>(kraken);
		mixed.add(new LocationKills(CATACOMBS, CATACOMBS_ID, 1));
		assertEquals(new TaskSpot(CATACOMBS_ID, CATACOMBS), LocationTally.decide(mixed, TABLE, "Cave kraken"));
	}

	@Test
	public void killsSaturate()
	{
		List<LocationKills> tally = LocationTally.add(null, centre(SMOKE), Integer.MAX_VALUE - 1);
		tally = LocationTally.add(tally, centre(SMOKE), 5);
		assertEquals(Collections.singletonList(new LocationKills(SMOKE, SMOKE_ID, Integer.MAX_VALUE)), tally);
		tally = LocationTally.add(tally, centre(CATACOMBS), Integer.MAX_VALUE);
		tally = LocationTally.add(tally, centre(CATACOMBS_WEST), 5);
		assertEquals("a spot's sum saturates too", CATACOMBS_ID, LocationTally.decide(tally, TABLE, DEVILS).getSpotId());
	}

	@Test
	public void atMostMaxRegionsAreTallied()
	{
		List<LocationKills> tally = null;
		for (int region = 0; region < LocationTally.MAX_REGIONS + 3; region++)
		{
			tally = LocationTally.add(tally, region, 1);
		}
		assertEquals(LocationTally.MAX_REGIONS, tally.size());
		tally = LocationTally.add(tally, 0, 1);
		assertEquals("a tallied place still counts, and is now the most recent", new LocationKills(0, 2),
			tally.get(LocationTally.MAX_REGIONS - 1));
	}

	@Test
	public void sanitizeDropsBadEntriesAndMergesRepeats()
	{
		List<LocationKills> stored = new ArrayList<>(Arrays.asList(new LocationKills(SMOKE, 2), null,
			new LocationKills(-5, 3), new LocationKills(CATACOMBS, 0), new LocationKills(CATACOMBS, CATACOMBS_ID, 4),
			new LocationKills(SMOKE, " ", 3), new LocationKills(SMOKE, SMOKE_ID, 1)));
		assertEquals("a blank spot reads as none", Arrays.asList(new LocationKills(SMOKE, 5),
			new LocationKills(CATACOMBS, CATACOMBS_ID, 4), new LocationKills(SMOKE, SMOKE_ID, 1)),
			LocationTally.sanitize(stored));
		assertTrue(LocationTally.sanitize(null).isEmpty());
		assertEquals("repeats count together once cleaned", new TaskSpot(SMOKE_ID, SMOKE),
			LocationTally.decide(stored, TABLE, DEVILS));
		assertEquals("a bad entry never wins", new TaskSpot(SMOKE_ID, SMOKE),
			LocationTally.decide(Arrays.asList(new LocationKills(-5, 9), new LocationKills(CATACOMBS, 0),
				new LocationKills(SMOKE, 1)), TABLE, DEVILS));
	}
}
