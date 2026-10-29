package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Task locations (Part B) as wired in the panel ({@link PluginHarness}): the History estimate and What to bring
 * use only the history at the current task's location when there is some (the assigned area, else the session's
 * region with the most kills, else where the player is), and say so; otherwise all of it. The "Name this location"
 * prompt suggests the task's bundled Wiki locations and the player's other names. {@link TaskStatisticsTest} and
 * {@link TaskLocationTest} cover the rules.
 */
public class HarnessTaskLocationEstimateTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final long MINUTE = 60_000L;
	private static final int TASK = 42;
	private static final int SMOKE = 12_946;
	private static final int CATACOMBS = 6_557;
	private static final int UNNAMED = 13_623;
	private static final int KONAR_AREA = 7;

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
	 * A completed Dust devils task of 10 kills in {@code minutes} minutes, 1,000 gp loot a minute (in coins), at
	 * {@code location}.
	 */
	private static LedgerRecord task(int minutes, String location)
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
		record.setLocation(location);
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

	/** The History estimate after a refresh, once the location names it asked the plugin for have arrived. */
	private String estimate()
	{
		harness.onEdt(() -> harness.panel().refresh());
		harness.drain();
		final AtomicReference<String> text = new AtomicReference<>();
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			text.set(harness.ui().getHistoryEstimate().getText());
		});
		return text.get();
	}

	private String estimateTooltip()
	{
		final AtomicReference<String> text = new AtomicReference<>();
		harness.onEdt(() -> text.set(harness.ui().getHistoryEstimate().getTooltip()));
		return text.get();
	}

	private String bringTooltip()
	{
		final AtomicReference<String> text = new AtomicReference<>();
		harness.onEdt(() -> text.set(harness.ui().getHistoryEstimate().getBringTooltip()));
		return text.get();
	}

	private int[] builds()
	{
		final int[] builds = new int[2];
		harness.onEdt(() ->
		{
			builds[0] = harness.ui().getHistoryEstimate().getBuildCount();
			builds[1] = harness.ui().getHistoryEstimate().getBringBuildCount();
		});
		return builds;
	}

	/** Kills {@code n} more of the task in {@code region} (the count drops on the next tick). */
	private void kill(int n, int region)
	{
		harness.setPlayerRegion(region);
		harness.setRemaining(harness.tracker().getCurrentRemaining() - n);
		harness.tick();
	}

	private int promptRegion()
	{
		final int[] region = new int[1];
		harness.onEdt(() -> region[0] = harness.ui().getLocationPrompt().getRegion());
		return region[0];
	}

	private List<String> suggestions()
	{
		final AtomicReference<List<String>> suggestions = new AtomicReference<>();
		harness.onEdt(() -> suggestions.set(harness.ui().getLocationPrompt().getSuggestions()));
		return suggestions.get();
	}

	private String nameKey(int region)
	{
		return harness.rsProfileConfig(SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX + region);
	}

	@Test
	public void theEstimateUsesOnlyTheHistoryWhereThePlayerIs()
	{
		name(SMOKE, "Smoke Dungeon");
		name(CATACOMBS, "Catacombs of Kourend");
		harness.loginAndSettle(ACCOUNT);
		showRecords(task(10, "smoke  dungeon"), task(30, "Catacombs of Kourend"), task(50, "Catacombs of Kourend"));

		assertEquals("History at Smoke Dungeon (1 task) | 20 kills ~0:20:00 | Loot (GE): 20K | Supplies: 0",
			estimate());
		assertTrue(estimateTooltip(), estimateTooltip().startsWith("<html>Completed-task history at Smoke Dungeon (1 "
			+ "sample)"));
		assertTrue(bringTooltip(), bringTooltip().contains("From your tasks at Smoke Dungeon only."));
		assertTrue(bringTooltip(), bringTooltip().contains("over 1 completed task (10 kills)"));

		// Walking to the Catacombs while the task is idle: its two tasks there.
		harness.setPlayerRegion(CATACOMBS);
		harness.tick();
		assertEquals("History at Catacombs of Kourend (2 tasks) | 20 kills ~1:20:00 | Loot (GE): 80K | Supplies: 0",
			estimate());
	}

	@Test
	public void aRunningSessionUsesItsRegionWithTheMostKillsAndTheTimeLeftFallbackToo()
	{
		name(SMOKE, "Smoke Dungeon");
		name(CATACOMBS, "Catacombs of Kourend");
		harness.loginAndSettle(ACCOUNT);
		showRecords(task(10, "Smoke Dungeon"), task(30, "Catacombs of Kourend"));
		assertTrue(harness.play());
		kill(1, CATACOMBS);
		kill(1, CATACOMBS);
		// Back in the Smoke Dungeon, but the session's kills are in the Catacombs.
		harness.setPlayerRegion(SMOKE);
		harness.tick();

		assertEquals("History at Catacombs of Kourend (1 task) | 18 kills ~0:54:00 | Loot (GE): 54K | Supplies: 0",
			estimate());
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
	public void theLocationTitleHasItsOwnLineAndALongOneTruncatesBeforeTheCount()
	{
		name(SMOKE, "Smoke Dungeon");
		name(CATACOMBS, "Catacombs of Kourend, north-west corner");
		harness.loginAndSettle(ACCOUNT);
		showRecords(task(10, "Smoke Dungeon"), task(10, "Catacombs of Kourend, north-west corner"));

		// "History at Smoke Dungeon (1 task)" fits the sidebar whole on its own line, the count right after it.
		assertTrue(estimate(), estimate().startsWith("History at Smoke Dungeon (1 task) | 20 kills"));
		int[] widths = layOut();
		assertEquals("time on its own line: " + Arrays.toString(widths), 1, widths[5]);
		assertEquals("the title is whole", widths[1], widths[0]);
		assertEquals("the count is whole", widths[3], widths[2]);
		assertEquals("right after the title", widths[0], widths[4]);

		harness.setPlayerRegion(CATACOMBS);
		harness.tick();
		assertTrue(estimate(), estimate().startsWith("History at Catacombs of Kourend, north-west corner (1 task)"));
		widths = layOut();
		assertTrue("the title is cut: " + Arrays.toString(widths), widths[0] > 0 && widths[0] < widths[1]);
		assertEquals("the count is whole", widths[3], widths[2]);
		assertEquals(widths[0], widths[4]);

		// No history where the player is: the two-line box again.
		harness.setPlayerRegion(UNNAMED);
		harness.tick();
		assertTrue(estimate(), estimate().startsWith("History (2 tasks) | 20 kills"));
		widths = layOut();
		assertEquals("time beside the title", 0, widths[5]);
		assertEquals(widths[1], widths[0]);
		assertEquals(widths[0], widths[4]);
	}

	/** {@link PanelProbe#layOutHistoryEstimateTitle} on the EDT. */
	private int[] layOut()
	{
		final AtomicReference<int[]> widths = new AtomicReference<>();
		harness.onEdt(() -> widths.set(harness.ui().layOutHistoryEstimateTitle()));
		return widths.get();
	}

	@Test
	public void withNoHistoryAtTheLocationItUsesAllOfIt()
	{
		name(SMOKE, "Smoke Dungeon");
		harness.loginAndSettle(ACCOUNT);
		showRecords(task(10, "Catacombs of Kourend"), task(30, null));

		assertEquals("History (2 tasks) | 20 kills ~0:40:00 | Loot (GE): 40K | Supplies: 0", estimate());
		assertFalse(estimateTooltip(), estimateTooltip().contains(" at "));
		assertFalse(bringTooltip(), bringTooltip().contains("From your tasks at"));

		// An unnamed region: the location is unknown, so all of it too.
		harness.setPlayerRegion(UNNAMED);
		harness.tick();
		assertEquals("History (2 tasks) | 20 kills ~0:40:00 | Loot (GE): 40K | Supplies: 0", estimate());
	}

	@Test
	public void aKonarAreaMatchesItsTasks()
	{
		name(SMOKE, "Smoke Dungeon");
		harness.setAssignment(SlayerMaster.KONAR, KONAR_AREA, "Kraken Cove");
		LedgerRecord olderKonar = task(10, null);
		olderKonar.setTaskLocation("Kraken Cove");
		harness.loginAndSettle(ACCOUNT);
		showRecords(olderKonar, task(30, "Smoke Dungeon"), task(50, "kraken cove"));

		// The assigned area wins over the region the player is in.
		assertEquals("History at Kraken Cove (2 tasks) | 20 kills ~1:00:00 | Loot (GE): 60K | Supplies: 0",
			estimate());
	}

	@Test
	public void aRecordSavedBeforeItsRegionWasNamedCountsOnceItIs()
	{
		harness.loginAndSettle(ACCOUNT);
		LedgerRecord unnamed = task(10, null);
		unnamed.setLocationRegion(SMOKE);
		showRecords(unnamed, task(30, "Catacombs of Kourend"));
		assertEquals("History (2 tasks) | 20 kills ~0:40:00 | Loot (GE): 40K | Supplies: 0", estimate());

		// Named now, in the prompt of a kill there.
		assertTrue(harness.play());
		kill(1, SMOKE);
		assertEquals(SMOKE, promptRegion());
		harness.onEdt(() -> harness.ui().getLocationPrompt().save("Smoke Dungeon"));
		harness.drain();
		assertEquals("History at Smoke Dungeon (1 task) | 19 kills ~0:19:00 | Loot (GE): 19K | Supplies: 0",
			estimate());
	}

	@Test
	public void nothingRebuildsOnTheRefreshTimerButAChangeOfLocationDoes()
	{
		name(SMOKE, "Smoke Dungeon");
		name(CATACOMBS, "Catacombs of Kourend");
		harness.loginAndSettle(ACCOUNT);
		showRecords(task(10, "Smoke Dungeon"), task(30, "Catacombs of Kourend"));
		estimate();
		final int[] before = builds();
		estimate();
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			harness.panel().refresh();
		});
		harness.tick();
		estimate();
		assertEquals("estimate", before[0], builds()[0]);
		assertEquals("what to bring", before[1], builds()[1]);

		harness.setPlayerRegion(CATACOMBS);
		harness.tick();
		assertTrue(estimate(), estimate().startsWith("History at Catacombs of Kourend (1 task)"));
		assertEquals(before[0] + 1, builds()[0]);
		assertEquals(before[1] + 1, builds()[1]);
	}

	@Test
	public void thePromptSuggestsTheTasksWikiLocationsAndThePlayersOwnNames()
	{
		name(CATACOMBS, "My catacombs spot");
		name(UNNAMED, "smoke dungeon");
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		kill(1, SMOKE);
		assertEquals(SMOKE, promptRegion());

		// Dust devils on the Wiki, then the player's other name; their "smoke dungeon" is the Wiki's.
		assertEquals(Arrays.asList("Catacombs of Kourend", "Wilderness Slayer Cave", "Smoke Dungeon",
			"My catacombs spot"), suggestions());
		harness.onEdt(() -> harness.ui().getLocationPrompt().pickSuggestion(2));
		harness.drain();
		assertEquals("a pick only fills the field", SMOKE, promptRegion());
		final AtomicReference<String> field = new AtomicReference<>();
		harness.onEdt(() -> field.set(harness.ui().getLocationPrompt().getText()));
		assertEquals("Smoke Dungeon", field.get());
		harness.onEdt(() -> harness.ui().getLocationPrompt().enter(field.get()));
		harness.drain();
		assertEquals("Enter saves", "Smoke Dungeon", nameKey(SMOKE));
		assertEquals(LocationTally.NO_REGION, promptRegion());
	}

	@Test
	public void losingFocusOrSkippingDoesNotSaveATypedName()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		kill(1, SMOKE);
		assertEquals(SMOKE, promptRegion());

		// Swing fires the combo box's edit event when the field loses focus, e.g. on clicking Skip or the game.
		harness.onEdt(() -> harness.ui().getLocationPrompt().loseFocus("Smoke"));
		harness.drain();
		assertNull("focus loss doesn't save", nameKey(SMOKE));
		assertEquals("the prompt stays", SMOKE, promptRegion());
	}

	@Test
	public void aMissingLocationListStillLetsThePromptWork()
	{
		promptWorksWithout("missing-task-locations.json");
	}

	@Test
	public void aBrokenLocationListStillLetsThePromptWork()
	{
		// Valid JSON, but not a task location list: rejected as a whole.
		promptWorksWithout(LuckMeter.SNAPSHOT_RESOURCE);
	}

	/** With the bundled list read from {@code resource}, which fails, the prompt suggests only the player's names. */
	private void promptWorksWithout(String resource)
	{
		harness.onEdt(() -> harness.ui().getLocationPrompt().setTaskLocationsResource(resource));
		name(CATACOMBS, "My catacombs spot");
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		kill(1, SMOKE);
		assertEquals(SMOKE, promptRegion());
		assertEquals("only the player's names", Collections.singletonList("My catacombs spot"), suggestions());
		harness.onEdt(() -> harness.ui().getLocationPrompt().save("Smoke Dungeon"));
		harness.drain();
		assertEquals("Smoke Dungeon", nameKey(SMOKE));
		assertEquals(LocationTally.NO_REGION, promptRegion());
	}
}
