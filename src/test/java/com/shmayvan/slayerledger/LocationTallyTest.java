package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** The kills per region of a session: adding, the dominant region and its ties, and cleaning a stored tally. */
public class LocationTallyTest
{
	private static final int SMOKE = 12_946;
	private static final int CATACOMBS = 6_557;
	private static final int TOWER = 13_623;

	@Test
	public void addsKillsPerRegionInTheOrderFirstSeen()
	{
		List<LocationKills> tally = LocationTally.add(null, SMOKE, 3);
		tally = LocationTally.add(tally, CATACOMBS, 1);
		List<LocationKills> before = tally;
		tally = LocationTally.add(tally, SMOKE, 2);
		assertEquals(Arrays.asList(new LocationKills(SMOKE, 5), new LocationKills(CATACOMBS, 1)), tally);
		assertEquals("the list passed in is not changed",
			Arrays.asList(new LocationKills(SMOKE, 3), new LocationKills(CATACOMBS, 1)), before);
	}

	@Test
	public void containsOnlyTalliedRegions()
	{
		List<LocationKills> tally = LocationTally.add(null, SMOKE, 2);
		assertTrue(LocationTally.contains(tally, SMOKE));
		assertFalse(LocationTally.contains(tally, CATACOMBS));
		assertFalse(LocationTally.contains(tally, LocationTally.NO_REGION));
		assertFalse(LocationTally.contains(null, SMOKE));
		assertFalse("no kills", LocationTally.contains(Collections.singletonList(new LocationKills(SMOKE, 0)), SMOKE));
	}

	@Test
	public void unknownRegionsAndNoKillsAreNotTallied()
	{
		List<LocationKills> tally = LocationTally.add(null, LocationTally.NO_REGION, 4);
		assertTrue(tally.isEmpty());
		tally = LocationTally.add(tally, SMOKE, 0);
		tally = LocationTally.add(tally, SMOKE, -2);
		assertTrue(tally.isEmpty());
		assertNull(LocationTally.dominantRegion(tally));
		assertNull(LocationTally.dominantRegion(null));
	}

	@Test
	public void theDominantRegionHasTheMostKillsAndTiesGoToTheEarliestSeen()
	{
		List<LocationKills> tally = LocationTally.add(null, SMOKE, 2);
		tally = LocationTally.add(tally, CATACOMBS, 3);
		assertEquals(Integer.valueOf(CATACOMBS), LocationTally.dominantRegion(tally));
		tally = LocationTally.add(tally, SMOKE, 1);
		assertEquals("a tie goes to the region seen first", Integer.valueOf(SMOKE), LocationTally.dominantRegion(tally));
		tally = LocationTally.add(tally, TOWER, 3);
		assertEquals("a later region needs more kills, not as many", Integer.valueOf(SMOKE),
			LocationTally.dominantRegion(tally));
		tally = LocationTally.add(tally, TOWER, 1);
		assertEquals(Integer.valueOf(TOWER), LocationTally.dominantRegion(tally));
	}

	@Test
	public void killsSaturate()
	{
		List<LocationKills> tally = LocationTally.add(null, SMOKE, Integer.MAX_VALUE - 1);
		tally = LocationTally.add(tally, SMOKE, 5);
		assertEquals(Collections.singletonList(new LocationKills(SMOKE, Integer.MAX_VALUE)), tally);
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
		assertEquals("a tallied region still counts", 2, tally.get(0).getKills());
	}

	@Test
	public void sanitizeDropsBadEntriesAndMergesRepeats()
	{
		List<LocationKills> stored = new ArrayList<>(Arrays.asList(new LocationKills(SMOKE, 2), null,
			new LocationKills(-5, 3), new LocationKills(CATACOMBS, 0), new LocationKills(CATACOMBS, 4),
			new LocationKills(SMOKE, 3)));
		assertEquals(Arrays.asList(new LocationKills(SMOKE, 5), new LocationKills(CATACOMBS, 4)),
			LocationTally.sanitize(stored));
		assertTrue(LocationTally.sanitize(null).isEmpty());
		assertEquals("repeats count together once cleaned", Integer.valueOf(SMOKE),
			LocationTally.dominantRegion(LocationTally.sanitize(stored)));
		assertEquals("a bad entry never wins", Integer.valueOf(SMOKE),
			LocationTally.dominantRegion(Arrays.asList(new LocationKills(-5, 9), new LocationKills(CATACOMBS, 0),
				new LocationKills(SMOKE, 1))));
	}
}
