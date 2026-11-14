package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Task locations as wired in the plugin ({@link PluginHarness}): the current task's
 * statistics, which the live card's time left and the overlay's pace use, come only from the history at the task's
 * spot when there is some (the session's spot, else the assigned area's, else the spot where the player is), matched
 * by spot id, and name the location; otherwise from all of it. The History estimate that showed them went with the
 * advisor card. {@link TaskStatisticsTest} and {@link TaskLocationTest} cover the rules.
 */
public class HarnessTaskLocationEstimateTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final long MINUTE = 60_000L;
	private static final int TASK = 42;
	private static final int SMOKE = 12_946;
	private static final int CATACOMBS = 6_557;
	/** A region the spot table doesn't list, with no history: named "Region 12342" until the player renames it. */
	private static final int UNLISTED = TestSpots.UNLISTED;
	private static final int KONAR_AREA = 7;
	private static final String SMOKE_ID = TestSpots.SMOKE_ID;
	private static final String CATACOMBS_ID = TestSpots.CATACOMBS_ID;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.startUp();
		harness.setInventory();
		harness.setTask(TASK, "Dust devils", 20, 20);
		harness.setPlayerRegion(SMOKE);
	}

	/**
	 * A completed Dust devils task of 10 kills in {@code minutes} minutes, 1,000 gp loot a minute (in coins), at the
	 * spot {@code spotId} ({@code null}: Unknown).
	 */
	private static LedgerRecord task(int minutes, String spotId)
	{
		LedgerRecord record = new LedgerRecord();
		record.setId("task-" + minutes);
		record.setCreatureId(TASK);
		record.setMonster("Dust devils");
		record.setKills(10);
		record.setActiveMillis(minutes * MINUTE);
		record.setCompleted(true);
		record.setLootGeValue(minutes * 1_000L);
		record.setLootHaValue(minutes * 1_000L);
		record.setSuppliesGeValue(0L);
		record.setSupplies(Collections.singletonMap(100, (long) minutes));
		record.setBankTrips(0);
		record.setSpotId(spotId);
		return SavedItems.withItems(record);
	}

	private void name(int region, String name)
	{
		harness.setRsProfileConfig(SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX + region, name);
	}

	private void showRecords(LedgerRecord... records)
	{
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, Arrays.asList(records)));
		harness.drain();
	}

	/**
	 * The current task's statistics as the live card's time left reads them, after a refresh, once the location names
	 * asked of the plugin have arrived.
	 */
	private TaskStatistics stats()
	{
		harness.onEdt(() -> harness.panel().refresh());
		harness.drain();
		final AtomicReference<TaskStatistics> stats = new AtomicReference<>();
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			stats.set(harness.historyModel().taskStatistics(TASK, "Dust devils",
				harness.tracker().getCurrentTaskSelection(), harness.historyModel().currentLocation()));
		});
		return stats.get();
	}

	/** {@link #stats} in short: "Smoke Dungeon (1 task) | 60s a kill", or "All locations (...)" for all of them. */
	private String statistics()
	{
		TaskStatistics stats = stats();
		return (stats.getLocation() == null ? "All locations" : stats.getLocation()) + " ("
			+ SlayerLedgerPanel.sampleTasks(stats.getSampleCount()) + ") | " + stats.getMillisPerKill() / 1_000
			+ "s a kill";
	}

	/** Kills {@code n} more of the task in {@code region} (the count drops on the next tick). */
	private void kill(int n, int region)
	{
		harness.setPlayerRegion(region);
		harness.setRemaining(harness.tracker().getCurrentRemaining() - n);
		harness.tick();
	}

	@Test
	public void theStatisticsUseOnlyTheHistoryWhereThePlayerIs()
	{
		name(SMOKE, "Smoke Dungeon");
		name(CATACOMBS, "Catacombs of Kourend");
		harness.loginAndSettle(ACCOUNT);
		showRecords(task(10, SMOKE_ID), task(30, CATACOMBS_ID), task(50, CATACOMBS_ID));

		assertEquals("Smoke Dungeon (1 task) | 60s a kill", statistics());

		// Walking to the Catacombs while the task is idle: its two tasks there.
		harness.setPlayerRegion(CATACOMBS);
		harness.tick();
		assertEquals("Catacombs of Kourend (2 tasks) | 240s a kill", statistics());
	}

	@Test
	public void aRunningSessionUsesItsRegionWithTheMostKillsAndTheTimeLeftFallbackToo()
	{
		name(SMOKE, "Smoke Dungeon");
		name(CATACOMBS, "Catacombs of Kourend");
		harness.loginAndSettle(ACCOUNT);
		showRecords(task(10, SMOKE_ID), task(30, CATACOMBS_ID));
		assertTrue(harness.startNow());
		kill(1, CATACOMBS);
		kill(1, CATACOMBS);
		// Back in the Smoke Dungeon, but the session's kills are in the Catacombs.
		harness.setPlayerRegion(SMOKE);
		harness.tick();

		assertEquals("Catacombs of Kourend (1 task) | 180s a kill", statistics());
		final String[] time = new String[2];
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			time[0] = harness.ui().getTimeLeftText();
			time[1] = harness.ui().getTimeLeftTooltip();
		});
		assertEquals("Time left: ~54m", time[0]);
		assertTrue(time[1], time[1].contains("from 1 completed task at Catacombs of Kourend;"));
	}

	@Test
	public void withNoHistoryAtTheLocationItUsesAllOfIt()
	{
		name(SMOKE, "Smoke Dungeon");
		harness.loginAndSettle(ACCOUNT);
		showRecords(task(10, CATACOMBS_ID), task(30, null));

		assertEquals("All locations (2 tasks) | 120s a kill", statistics());

		// A region the table doesn't list and nobody named: no history there, so all of it too.
		harness.setPlayerRegion(UNLISTED);
		harness.tick();
		assertEquals("All locations (2 tasks) | 120s a kill", statistics());
	}

	@Test
	public void aKonarAreaMatchesTheTasksAtTheSpotOfThatName()
	{
		name(SMOKE, "Smoke Dungeon");
		harness.setAssignment(SlayerMaster.KONAR, KONAR_AREA, "Kraken Cove");
		LedgerRecord olderKonar = task(10, TestSpots.KRAKEN_COVE_ID);
		olderKonar.setTaskLocation("Kraken Cove");
		// Its own name is the area's, but its spot is unknown: names never match.
		LedgerRecord namedOnly = task(70, null);
		namedOnly.setLocation("Kraken Cove");
		harness.loginAndSettle(ACCOUNT);
		showRecords(olderKonar, task(30, SMOKE_ID), task(50, TestSpots.KRAKEN_COVE_ID), namedOnly);

		// The assigned area wins over the region the player is in.
		assertEquals("Kraken Cove (2 tasks) | 180s a kill", statistics());
	}

	@Test
	public void aRecordWithOnlyARegionCountsByItsSpotsName()
	{
		harness.loginAndSettle(ACCOUNT);
		// Saved without a name: the table names its region's spot.
		LedgerRecord unnamed = task(10, null);
		unnamed.setLocationRegion(SMOKE);
		showRecords(unnamed, task(30, CATACOMBS_ID));
		assertEquals("Smoke Dungeon (1 task) | 60s a kill", statistics());
	}

	@Test
	public void anUnlistedRegionRenamedForAllItsTasksStillMatches()
	{
		harness.setPlayerRegion(UNLISTED);
		harness.loginAndSettle(ACCOUNT);
		LedgerRecord there = task(10, null);
		there.setLocationRegion(UNLISTED);
		there.setSpotId(SlayerSpots.regionKey(UNLISTED));
		showRecords(there, task(30, CATACOMBS_ID));
		assertEquals("Region 12342 (1 task) | 60s a kill", statistics());

		// The history's "Rename place": the record and where the player stands both take the name.
		harness.onEdt(() -> harness.plugin.saveLocationName(UNLISTED, "Edgeville"));
		harness.drain();
		assertEquals("Edgeville (1 task) | 60s a kill", statistics());
	}

	@Test
	public void theStatisticsAreKeptOnTheRefreshTimerWhileASessionRuns()
	{
		name(SMOKE, "Smoke Dungeon");
		harness.loginAndSettle(ACCOUNT);
		showRecords(task(10, SMOKE_ID), task(30, CATACOMBS_ID));
		// The live card reads the statistics on each refresh only while a session is active: with no kill yet, its
		// time left is the history's.
		assertTrue(harness.startNow());
		// The first read asks for the name of the region the player is in (the session has no spot yet); once it is
		// in, nothing changes.
		final TaskStatistics before = stats();
		assertEquals("Smoke Dungeon", before.getLocation());
		final String[] tooltip = new String[1];
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			tooltip[0] = harness.ui().getTimeLeftTooltip();
		});
		assertTrue("the refresh read them: " + tooltip[0],
			tooltip[0].contains("from 1 completed task at Smoke Dungeon;"));

		// The refresh timer's reads and the session's ticks keep them.
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			harness.panel().refresh();
		});
		harness.ticks(3);
		assertSame(before, stats());
	}

	@Test
	public void theStatisticsAreWorkedOutAgainForAnotherLocation()
	{
		name(SMOKE, "Smoke Dungeon");
		name(CATACOMBS, "Catacombs of Kourend");
		harness.loginAndSettle(ACCOUNT);
		showRecords(task(10, SMOKE_ID), task(30, CATACOMBS_ID));
		final TaskStatistics before = stats();
		assertEquals("Smoke Dungeon", before.getLocation());

		// Walking to the Catacombs while the task is idle: another location, so other statistics.
		harness.setPlayerRegion(CATACOMBS);
		harness.tick();
		TaskStatistics there = stats();
		assertNotSame(before, there);
		assertEquals("Catacombs of Kourend", there.getLocation());
	}
}
