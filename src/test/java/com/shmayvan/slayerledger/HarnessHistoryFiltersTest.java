package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import net.runelite.client.ui.PluginPanel;
import org.junit.Rule;
import org.junit.Test;

/**
 * The Task history's Monster, Location and Master filters as wired in the plugin ({@link PluginHarness}): their
 * choices, how they combine, paging and the empty text on the filtered list, values that disappear, another account,
 * region names that arrive later and an edited location. {@link HistoryFilterTest} covers the rules.
 */
public class HarnessHistoryFiltersTest
{
	@Rule
	public final PluginHarness harness = new PluginHarness();

	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int SMOKE = 12_946;
	private static final int DURADEL = 5;
	private static final int NIEVE = 6;
	private static final String DURADEL_NAME = "Duradel/Kuradal";
	private static final String NIEVE_NAME = "Nieve/Steve";
	private static final String LONG_LOCATION = "Catacombs of Kourend, the far north end";

	private <T> T onEdt(Supplier<T> read)
	{
		List<T> result = new ArrayList<>(1);
		harness.onEdt(() -> result.add(read.get()));
		return result.get(0);
	}

	private PanelProbe.HistoryListProbe history()
	{
		return harness.ui().getHistory();
	}

	private static LedgerRecord record(int i, String monster, String location, Integer master)
	{
		LedgerRecord record = new LedgerRecord();
		record.setId("r" + i);
		record.setMonster(monster);
		record.setLocation(location);
		record.setSlayerMaster(master);
		record.setKills(10);
		record.setInitialAmount(10);
		record.setActiveMillis(60_000L);
		record.setEndedAt(1_700_000_000_000L - i * 60_000L);
		record.setStartedAt(record.getEndedAt() - 60_000L);
		record.setCompleted(true);
		return record;
	}

	/**
	 * 60 tasks, newest first: Abyssal demons in the Catacombs from Duradel (every 3rd, from 0), Dust devils in the Smoke
	 * Dungeon from Nieve (every 3rd, from 1), and Abyssal demons with no location and no master (every 3rd, from 2).
	 */
	private static List<LedgerRecord> tasks()
	{
		List<LedgerRecord> records = new ArrayList<>();
		for (int i = 0; i < 60; i++)
		{
			switch (i % 3)
			{
				case 0:
					records.add(record(i, "Abyssal demons", "Catacombs", DURADEL));
					break;
				case 1:
					records.add(record(i, "Dust devils", "Smoke Dungeon", NIEVE));
					break;
				default:
					records.add(record(i, "Abyssal demons", null, null));
					break;
			}
		}
		return records;
	}

	private void show(long account, List<LedgerRecord> records)
	{
		harness.onEdt(() -> harness.historyModel().setRecords(account, records));
		harness.drain();
	}

	private List<String> titles()
	{
		return onEdt(() -> history().getTitleTexts());
	}

	@Test
	public void eachFilterListsOnlyTheValuesInTheHistoryAlphabeticallyWithTheUnknownLast()
	{
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		assertEquals("no history yet", Arrays.asList(LedgerHistory.ALL),
			onEdt(() -> history().getLocationFilterOptions()));
		assertEquals(Arrays.asList(LedgerHistory.ALL), onEdt(() -> history().getMasterFilterOptions()));

		show(ACCOUNT, tasks());
		assertEquals(Arrays.asList(LedgerHistory.ALL, "Abyssal demons", "Dust devils"),
			onEdt(() -> history().getFilterOptions()));
		assertEquals(Arrays.asList(LedgerHistory.ALL, "Catacombs", "Smoke Dungeon", HistoryFilter.NO_LOCATION_LABEL),
			onEdt(() -> history().getLocationFilterOptions()));
		assertEquals(Arrays.asList(LedgerHistory.ALL, DURADEL_NAME, NIEVE_NAME, HistoryFilter.UNKNOWN_MASTER),
			onEdt(() -> history().getMasterFilterOptions()));
		assertEquals("All | All | All", onEdt(() -> history().getFilterSelections()));
	}

	@Test
	public void eachFilterAloneAndTogetherPagesTheFilteredList()
	{
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		show(ACCOUNT, tasks());
		assertEquals("Showing 25 of 60 tasks", onEdt(() -> history().getShowMoreTooltip()));

		// Location alone: 20 Smoke Dungeon tasks, one page.
		harness.onEdt(() -> history().selectLocationFilter("Smoke Dungeon"));
		assertEquals(20, (int) onEdt(() -> history().getRowCount()));
		assertNull(onEdt(() -> history().getShowMoreText()));
		assertTrue(titles().stream().allMatch(t -> t.startsWith("Dust devils")));
		harness.onEdt(() -> history().selectLocationFilter(LedgerHistory.ALL));

		// Master alone: 20 tasks without a known master.
		harness.onEdt(() -> history().selectMasterFilter(HistoryFilter.UNKNOWN_MASTER));
		assertEquals(20, (int) onEdt(() -> history().getRowCount()));

		// Monster alone: 40 Abyssal demons tasks, paged by 25.
		harness.onEdt(() ->
		{
			history().selectMasterFilter(LedgerHistory.ALL);
			history().selectFilter("Abyssal demons");
		});
		assertEquals(25, (int) onEdt(() -> history().getRowCount()));
		assertEquals("Show 15 more", onEdt(() -> history().getShowMoreText()));
		assertEquals("Showing 25 of 40 tasks", onEdt(() -> history().getShowMoreTooltip()));
		harness.onEdt(() -> history().clickShowMore());
		assertEquals(40, (int) onEdt(() -> history().getRowCount()));
		assertNull(onEdt(() -> history().getShowMoreText()));

		// Another value in a second filter: back to the first page of what both match.
		harness.onEdt(() -> history().selectLocationFilter(HistoryFilter.NO_LOCATION));
		assertEquals(20, (int) onEdt(() -> history().getRowCount()));
		assertEquals("Abyssal demons | No location | All", onEdt(() -> history().getFilterSelections()));
		assertNull(onEdt(() -> history().getEmptyText()));

		// Together they can match nothing: the list says so, and the filters stay to change.
		harness.onEdt(() -> history().selectMasterFilter(DURADEL_NAME));
		assertEquals(0, (int) onEdt(() -> history().getRowCount()));
		assertEquals("<html>" + HistoryListSection.NO_MATCHES + "</html>", onEdt(() -> history().getEmptyText()));
		assertNull(onEdt(() -> history().getShowMoreText()));
		harness.onEdt(() -> history().selectLocationFilter("Catacombs"));
		assertEquals(20, (int) onEdt(() -> history().getRowCount()));
		assertNull(onEdt(() -> history().getEmptyText()));
	}

	@Test
	public void aValueThatDisappearsGoesBackToAllFromTheFirstPage()
	{
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		List<LedgerRecord> records = tasks();
		show(ACCOUNT, records);
		harness.onEdt(() ->
		{
			history().selectFilter("Abyssal demons");
			history().selectMasterFilter(HistoryFilter.UNKNOWN_MASTER);
		});
		assertEquals(20, (int) onEdt(() -> history().getRowCount()));

		// The tasks without a master are gone (deleted): Master goes back to "All", Monster stays.
		List<LedgerRecord> withMasters = new ArrayList<>();
		for (LedgerRecord record : records)
		{
			if (record.getSlayerMaster() != null)
			{
				withMasters.add(record);
			}
		}
		show(ACCOUNT, withMasters);
		assertEquals("Abyssal demons | All | All", onEdt(() -> history().getFilterSelections()));
		assertEquals(Arrays.asList(LedgerHistory.ALL, DURADEL_NAME, NIEVE_NAME),
			onEdt(() -> history().getMasterFilterOptions()));
		assertEquals(Arrays.asList(LedgerHistory.ALL, "Catacombs", "Smoke Dungeon"),
			onEdt(() -> history().getLocationFilterOptions()));
		assertEquals(20, (int) onEdt(() -> history().getRowCount()));
		assertNull(onEdt(() -> history().getShowMoreText()));
	}

	@Test
	public void anotherAccountStartsWithEveryFilterOnAll()
	{
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		show(ACCOUNT, tasks());
		harness.onEdt(() ->
		{
			history().selectFilter("Dust devils");
			history().selectLocationFilter("Smoke Dungeon");
			history().selectMasterFilter(NIEVE_NAME);
		});
		assertEquals(20, (int) onEdt(() -> history().getRowCount()));

		// The other account has the same values, but its history starts unfiltered.
		show(PluginHarness.OTHER_ACCOUNT, tasks());
		assertEquals("All | All | All", onEdt(() -> history().getFilterSelections()));
		assertEquals(25, (int) onEdt(() -> history().getRowCount()));
		assertEquals("Showing 25 of 60 tasks", onEdt(() -> history().getShowMoreTooltip()));
	}

	@Test
	public void aRegionsNameArrivingMovesItsTasksOutOfNoLocation()
	{
		harness.setRsProfileConfig(SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX + SMOKE,
			"Smoke Dungeon");
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		List<LedgerRecord> records = new ArrayList<>();
		LedgerRecord regionOnly = record(0, "Dust devils", null, NIEVE);
		regionOnly.setLocationRegion(SMOKE);
		records.add(regionOnly);
		records.add(record(1, "Kurasks", null, NIEVE));
		harness.onEdt(() ->
		{
			harness.historyModel().setRecords(ACCOUNT, records);
			// The region's name is asked of the client thread, so it is not known yet.
			assertEquals(Arrays.asList(LedgerHistory.ALL, HistoryFilter.NO_LOCATION_LABEL),
				history().getLocationFilterOptions());
		});
		harness.drain();
		assertEquals(Arrays.asList(LedgerHistory.ALL, "Smoke Dungeon", HistoryFilter.NO_LOCATION_LABEL),
			onEdt(() -> history().getLocationFilterOptions()));
		harness.onEdt(() -> history().selectLocationFilter("Smoke Dungeon"));
		assertEquals(Arrays.asList("Dust devils | Completed"), titles());
	}

	@Test
	public void anEditedLocationUpdatesTheLocationFilter() throws IOException
	{
		assertTrue(harness.ledgerDirectory().isDirectory() || harness.ledgerDirectory().mkdirs());
		String json = "{\"schemaVersion\":2,\"records\":["
			+ "{\"schemaVersion\":2,\"id\":\"a\",\"monster\":\"Kurasks\",\"creatureId\":82,\"initialAmount\":3,"
			+ "\"kills\":3,\"activeMillis\":60000,\"startedAt\":1000,\"endedAt\":61000,\"completed\":true,"
			+ "\"partial\":false,\"location\":\"Iorwerth Dungeon\",\"slayerMaster\":6},"
			+ "{\"schemaVersion\":2,\"id\":\"b\",\"monster\":\"Kurasks\",\"creatureId\":82,\"initialAmount\":3,"
			+ "\"kills\":3,\"activeMillis\":60000,\"startedAt\":2000,\"endedAt\":62000,\"completed\":true,"
			+ "\"partial\":false,\"location\":\"Fremennik Slayer Dungeon\",\"slayerMaster\":6}]}";
		Files.write(harness.ledgerDirectory().toPath().resolve(ACCOUNT + ".json"), json.getBytes(StandardCharsets.UTF_8));
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		assertEquals(Arrays.asList(LedgerHistory.ALL, "Fremennik Slayer Dungeon", "Iorwerth Dungeon"),
			onEdt(() -> history().getLocationFilterOptions()));
		harness.onEdt(() -> history().selectLocationFilter("Iorwerth Dungeon"));
		assertEquals(1, (int) onEdt(() -> history().getRowCount()));

		// The filtered task's location is renamed: its old name is gone, so the filter goes back to "All".
		harness.onEdt(() -> harness.plugin.editRecordLocation(ACCOUNT, "a", "Lighthouse"));
		harness.drain();
		assertEquals(Arrays.asList(LedgerHistory.ALL, "Fremennik Slayer Dungeon", "Lighthouse"),
			onEdt(() -> history().getLocationFilterOptions()));
		assertEquals("All | All | All", onEdt(() -> history().getFilterSelections()));
		assertEquals(2, (int) onEdt(() -> history().getRowCount()));

		// Its own name removed (no region either): it is listed under "No location".
		harness.onEdt(() -> harness.plugin.editRecordLocation(ACCOUNT, "a", null));
		harness.drain();
		assertEquals(Arrays.asList(LedgerHistory.ALL, "Fremennik Slayer Dungeon", HistoryFilter.NO_LOCATION_LABEL),
			onEdt(() -> history().getLocationFilterOptions()));
	}

	@Test
	public void aLongNameNeverWidensTheFiltersAndTheTooltipNamesItInFull()
	{
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		show(ACCOUNT, tasks());
		int width = onEdt(() -> history().getFilterPreferredWidth("history.locationFilter"));
		assertEquals("Show only tasks at this location", onEdt(() -> history().getFilterTooltip(
			"history.locationFilter")));

		List<LedgerRecord> records = tasks();
		records.add(0, record(99, "Abyssal demons", LONG_LOCATION, DURADEL));
		show(ACCOUNT, records);
		assertEquals(width, (int) onEdt(() -> history().getFilterPreferredWidth("history.locationFilter")));
		assertEquals("the boxes are the same width", width,
			(int) onEdt(() -> history().getFilterPreferredWidth("history.filter")));
		assertEquals(width, (int) onEdt(() -> history().getFilterPreferredWidth("history.masterFilter")));
		int rows = onEdt(() -> history().getFiltersPreferredWidth());
		assertTrue("the filters fit the sidebar: " + rows, rows < PluginPanel.PANEL_WIDTH);
		harness.onEdt(() -> history().selectLocationFilter(LONG_LOCATION));
		assertEquals("<html>Show only tasks at this location:<br>" + LONG_LOCATION + "</html>",
			onEdt(() -> history().getFilterTooltip("history.locationFilter")));
	}

	@Test
	public void theOpenListsWidenToFitLongNamesWhileTheBoxesKeepTheirWidth()
	{
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		List<LedgerRecord> records = tasks();
		records.add(0, record(99, "Abyssal demons", LONG_LOCATION, DURADEL));
		show(ACCOUNT, records);
		int box = onEdt(() -> history().getFilterPreferredWidth("history.locationFilter"));
		for (String name : Arrays.asList("history.filter", "history.locationFilter", "history.masterFilter"))
		{
			assertTrue(name, onEdt(() -> history().widenFilterList(name)));
		}
		int list = onEdt(() -> history().getFilterListPreferredWidth("history.locationFilter"));
		assertTrue("the open list fits the long name: " + list + " > " + box, list > box);
		assertTrue("at most the maximum: " + list, list <= ComboPopupWidener.MAX_WIDTH);
		assertEquals("the closed box keeps its width", box,
			(int) onEdt(() -> history().getFilterPreferredWidth("history.locationFilter")));
		int rows = onEdt(() -> history().getFiltersPreferredWidth());
		assertTrue("the filters still fit the sidebar: " + rows, rows < PluginPanel.PANEL_WIDTH);
	}

	@Test
	public void aPlaceNamedNoLocationIsItsOwnChoiceBesideTheTasksWithout()
	{
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		List<LedgerRecord> records = new ArrayList<>();
		records.add(record(0, "Dust devils", "No location", NIEVE));
		records.add(record(1, "Kurasks", null, NIEVE));
		show(ACCOUNT, records);
		assertEquals("two entries, alike to the eye", Arrays.asList(LedgerHistory.ALL, "No location",
			HistoryFilter.NO_LOCATION_LABEL), onEdt(() -> history().getLocationFilterOptions()));

		harness.onEdt(() -> history().selectLocationFilter("No location"));
		assertEquals(Arrays.asList("Dust devils | Completed"), titles());
		harness.onEdt(() -> history().selectLocationFilter(HistoryFilter.NO_LOCATION));
		assertEquals(Arrays.asList("Kurasks | Completed"), titles());
		assertEquals("All | No location | All", onEdt(() -> history().getFilterSelections()));
		assertEquals("<html>Show only tasks at this location:<br>No location</html>",
			onEdt(() -> history().getFilterTooltip("history.locationFilter")));

		// The named place's tasks are gone: the tasks without a location stay selected.
		show(ACCOUNT, records.subList(1, 2));
		assertEquals(Arrays.asList("Kurasks | Completed"), titles());
		assertEquals(Arrays.asList(LedgerHistory.ALL, HistoryFilter.NO_LOCATION_LABEL),
			onEdt(() -> history().getLocationFilterOptions()));
	}

	@Test
	public void theSamePlaceSpelledAnewKeepsTheRowsShown()
	{
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		List<LedgerRecord> records = new ArrayList<>();
		for (int i = 0; i < 40; i++)
		{
			records.add(record(i, "Dust devils", "Smoke Dungeon", NIEVE));
		}
		show(ACCOUNT, records);
		harness.onEdt(() ->
		{
			history().selectLocationFilter("Smoke Dungeon");
			history().clickShowMore();
		});
		assertEquals(40, (int) onEdt(() -> history().getRowCount()));

		// The newest task's location is spelled anew: still the same place, so the rows opened up stay.
		List<LedgerRecord> respelled = new ArrayList<>(records);
		respelled.set(0, record(0, "Dust devils", "smoke  dungeon", NIEVE));
		show(ACCOUNT, respelled);
		assertEquals(Arrays.asList(LedgerHistory.ALL, "smoke dungeon"),
			onEdt(() -> history().getLocationFilterOptions()));
		assertEquals("All | smoke dungeon | All", onEdt(() -> history().getFilterSelections()));
		assertEquals(40, (int) onEdt(() -> history().getRowCount()));
	}

	@Test
	public void theRefreshTimerNeverRebuildsTheFilteredRows()
	{
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		show(ACCOUNT, tasks());
		harness.onEdt(() -> history().selectLocationFilter("Catacombs"));
		int builds = onEdt(() -> history().getBuildCount());
		for (int i = 0; i < 5; i++)
		{
			harness.onEdt(() -> harness.panel().refresh());
		}
		harness.drain();
		assertEquals(builds, (int) onEdt(() -> history().getBuildCount()));
		assertEquals("All | Catacombs | All",
			onEdt(() -> history().getFilterSelections()));
	}
}
