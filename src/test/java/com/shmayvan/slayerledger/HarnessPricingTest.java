package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * The "Loot price" setting and the history at today's prices as wired in the plugin
 * ({@link PluginHarness}): a setting change reaches the live values and the history without a restart, and saved tasks
 * are valued at today's prices, looked up on the client thread, once per item, and shown on the EDT.
 * {@link LootPricingTest}, {@link HistoryPricingTest} and {@link SessionValuationTest} cover the calculations.
 */
public class HarnessPricingTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.BONES, "Bones", 100, 1);
		// High alch above GE, so the modes differ.
		harness.defineItem(ItemID.DRAGON_BONES, "Dragon bones", 2_000, 5_000);
		harness.startUp();
		harness.setInventory();
		harness.setTask(42, "Abyssal demons", 10, 10);
		harness.loginAndSettle(ACCOUNT);
	}

	/** Plays a session that loots bones and dragon bones (GE 2,100, high alch 5,001). */
	private void loot()
	{
		assertTrue(harness.play());
		harness.postLoot(ItemID.BONES, 1);
		harness.postLoot(ItemID.DRAGON_BONES, 1);
		harness.ticks(2);
	}

	private String liveValues()
	{
		AtomicReference<String> text = new AtomicReference<>();
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			text.set(harness.ui().getValuesText());
		});
		return text.get();
	}

	/** The only history row: "Loot | Supplies | Profit | tooltip". */
	private String historyRow()
	{
		AtomicReference<List<String>> rows = new AtomicReference<>();
		harness.onEdt(() -> rows.set(harness.ui().getHistory().getValuesText()));
		assertEquals(1, rows.get().size());
		return rows.get().get(0);
	}

	@Test
	public void lootPriceSettingChangesLiveValuesAndHistoryWithoutRestart()
	{
		loot();
		assertEquals("Loot (GE): 2,100 gp | Supplies: 0 gp | Profit (GE): 2,100 gp", liveValues());

		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		harness.tick();
		assertEquals("Loot (HA): 5,001 gp | Supplies: 0 gp | Profit (HA): 5,001 gp", liveValues());

		harness.stop();
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals("the record keeps both GE and high alch", Long.valueOf(2_100), record.getLootGeValue());
		assertEquals(Long.valueOf(5_001), record.getLootHaValue());
		assertTrue(historyRow(), historyRow().startsWith("Loot (HA): 5,001 | Supplies: 0 | Profit (HA): 5,001 | "));
		assertTrue(historyRow(), historyRow().contains("Loot value (High alch): 5,001 gp"));

		harness.setLootPriceMode(LootPriceMode.GE);
		assertTrue(historyRow(), historyRow().startsWith("Loot (GE): 2,100 | Supplies: 0 | Profit (GE): 2,100 | "));
		assertFalse(historyRow(), historyRow().contains("Loot value ("));
	}

	@Test
	public void aSavedTaskIsRevaluedAtTodaysPricesInTheHistoryTrendsAndTotals()
	{
		// Saved at 40,000 gp for the seed and 500 gp a shark; 50,000 gp and 900 gp today.
		harness.defineItem(ItemID.RANARR_SEED, "Ranarr seed", 50_000, 20);
		harness.defineItem(ItemID.SHARK, "Shark", 900, 10);
		LedgerRecord record = ranarrRecord();
		record.setCompleted(true);
		record.setActiveMillis(60 * 60_000L);
		record.setSupplies(Collections.singletonMap(ItemID.SHARK, 10L));
		Map<Integer, Long> ge = new HashMap<>(record.getUnitGePrices());
		ge.put(ItemID.SHARK, 500L);
		record.setUnitGePrices(ge);
		record.setSuppliesGeValue(5_000L);
		int calls = harness.pluginCallCount();
		harness.onEdt(() ->
		{
			harness.historyModel().setRecords(ACCOUNT, Collections.singletonList(record));
			harness.ui().getOverallTotals().clickTitle();
			harness.ui().getTrends().clickTitle();
		});
		// The lookup is queued for the client thread, and its answer for the EDT.
		harness.drain();
		assertTrue("prices are looked up through ItemManager", harness.pluginCallCount() > calls);

		String row = historyRow();
		assertTrue(row, row.startsWith("Loot (GE): 50K | Supplies: 9,000 | Profit (GE): 41K | "));
		assertTrue(row, row.contains(LedgerHistory.CURRENT_PRICES));
		assertTrue("high alch unchanged", row.contains("Loot: 50,000 gp GE, 20 gp high alch"));
		assertFalse("no saved prices are shown", row.contains("40,000") || row.contains("35,000"));
		assertEquals(Collections.singletonList("1. Abyssal demons | 41K gp/h (n=1)"), trendsRows());
		assertTrue(totalsRows().toString(), totalsRows().get(0).contains("Profit (GE): | 41K (n=1)"));

		// High alch: the loot at its saved high alch value, the supplies still at today's GE price.
		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		assertTrue(historyRow(), historyRow().startsWith("Loot (HA): 20 | Supplies: 9,000 | Profit (HA): -8,980 | "));
		assertEquals(Collections.singletonList("1. Abyssal demons | -8,980 gp/h (n=1)"), trendsRows());
		assertTrue(totalsRows().toString(), totalsRows().get(0).contains("Profit (HA): | -8,980 (n=1)"));

		// Looked up once: the same tasks again ask for nothing, even after today's price changes.
		harness.setGePrice(ItemID.RANARR_SEED, 60_000);
		calls = harness.pluginCallCount();
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, Collections.singletonList(record)));
		harness.drain();
		assertEquals(calls, harness.pluginCallCount());
		assertTrue(historyRow(), historyRow().contains("Loot: 50,000 gp GE"));
	}

	private List<String> trendsRows()
	{
		AtomicReference<List<String>> rows = new AtomicReference<>();
		// Worked out off the EDT.
		harness.drain();
		harness.onEdt(() -> rows.set(harness.ui().getTrends().getRows()));
		return rows.get();
	}

	private List<String> totalsRows()
	{
		AtomicReference<List<String>> rows = new AtomicReference<>();
		harness.drain();
		harness.onEdt(() -> rows.set(harness.ui().getOverallTotals().getRows()));
		return rows.get();
	}

	@Test
	public void todaysPricesCoverNewlySavedTasks()
	{
		loot();
		harness.stop();

		harness.defineItem(ItemID.RANARR_SEED, "Ranarr seed", 40_000, 20);
		assertTrue(harness.play());
		harness.postLoot(ItemID.RANARR_SEED, 1);
		harness.tick();
		int builds = historyListBuilds();
		harness.stop();
		// The save reloads the records; the rows wait for the seed's price and are drawn once.
		assertEquals(builds + 1, historyListBuilds());
		AtomicReference<List<String>> rows = new AtomicReference<>();
		harness.onEdt(() -> rows.set(harness.ui().getHistory().getValuesText()));
		assertEquals(2, rows.get().size());
		for (String row : rows.get())
		{
			assertTrue(row, row.contains(LedgerHistory.CURRENT_PRICES));
			assertFalse("every item was priced", row.contains("~"));
		}
		assertTrue(rows.get().toString(), rows.get().stream().anyMatch(r -> r.startsWith("Loot (GE): 40K | ")));
	}

	private int historyListBuilds()
	{
		AtomicReference<Integer> builds = new AtomicReference<>();
		harness.onEdt(() -> builds.set(harness.ui().getHistory().getBuildCount()));
		return builds.get();
	}

	private List<String> historyValues()
	{
		AtomicReference<List<String>> rows = new AtomicReference<>();
		harness.onEdt(() -> rows.set(harness.ui().getHistory().getValuesText()));
		return rows.get();
	}

	/** A record that looted one Ranarr seed (saved at GE 40,000), newer than the played session. */
	private static LedgerRecord ranarrRecord()
	{
		LedgerRecord record = new LedgerRecord();
		record.setId("ranarr");
		record.setMonster("Abyssal demons");
		record.setCreatureId(42);
		record.setStartedAt(System.currentTimeMillis() + 60_000);
		record.setEndedAt(System.currentTimeMillis() + 120_000);
		record.setKills(1);
		record.setActiveMillis(60_000);
		record.setLoot(Collections.singletonMap(ItemID.RANARR_SEED, 1L));
		record.setUnitGePrices(Collections.singletonMap(ItemID.RANARR_SEED, 40_000L));
		record.setUnitHaPrices(Collections.singletonMap(ItemID.RANARR_SEED, 20L));
		record.setLootGeValue(40_000L);
		record.setLootHaValue(20L);
		record.setSuppliesGeValue(0L);
		record.setSuppliesHaValue(0L);
		return record;
	}

	/** Whether the history waits for today's prices. */
	private boolean waiting()
	{
		AtomicReference<Boolean> waiting = new AtomicReference<>();
		harness.onEdt(() -> waiting.set(harness.historyModel().isWaitingForCurrentPrices()));
		return waiting.get();
	}

	/** Lets a wait for today's prices run out, as after {@link HistoryValuation#CURRENT_PRICES_WAIT_MILLIS}. */
	private void runOutTheWait()
	{
		harness.onEdt(() ->
		{
			harness.historyModel().expireCurrentPricesWait();
			assertTrue(harness.historyModel().endExpiredCurrentPricesWait());
		});
	}

	@Test
	public void atClientStartTheHistoryWaitsForPricesLookedUpAgainAndIsDrawnOnce()
	{
		// Played, saved and shown before RuneLite has loaded its price list: every tradeable item reads 0.
		harness.setGePrice(ItemID.BONES, 0);
		harness.setGePrice(ItemID.DRAGON_BONES, 0);
		loot();
		int builds = historyListBuilds();
		harness.stop();
		assertTrue("the answer came without the price: wait for it to be looked up again", waiting());
		assertEquals("nothing drawn unpriced", builds, historyListBuilds());

		// The price list loads; the next try finds the prices and the wait ends with one redraw.
		harness.setGePrice(ItemID.BONES, 100);
		harness.setGePrice(ItemID.DRAGON_BONES, 3_000);
		harness.ticks(PriceRetry.INTERVAL_TICKS);
		assertFalse(waiting());
		String row = historyRow();
		assertTrue(row, row.startsWith("Loot (GE): 3,100 | Supplies: 0 | Profit (GE): 3,100 | "));
		assertEquals("drawn once, priced", builds + 1, historyListBuilds());
	}

	@Test
	public void aWaitForAPriceLookedUpAgainStillRunsOutAndEndsWhenTheHistoryIsCleared()
	{
		// RuneLite's price list never loads in this run.
		harness.setGePrice(ItemID.BONES, 0);
		harness.setGePrice(ItemID.DRAGON_BONES, 0);
		loot();
		harness.stop();
		assertTrue(waiting());
		runOutTheWait();
		assertFalse(waiting());
		assertTrue(historyRow(), historyRow().startsWith("Loot (GE): ~0 | "));

		// Waiting again for the same price, until the history is cleared (logging out).
		List<LedgerRecord> records = harness.records(ACCOUNT);
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, records));
		harness.drain();
		assertTrue(waiting());
		harness.onEdt(() -> harness.historyModel().clearRecords(ACCOUNT, SlayerLedgerPanel.LOADING_MESSAGE));
		assertFalse(waiting());
	}

	@Test
	public void onceAPriceIsSeenAnItemWithoutAPriceHoldsNothingBack()
	{
		// RuneLite's price list is loaded (the bones are priced) but has no price for the dragon bones.
		loot();
		harness.setGePrice(ItemID.DRAGON_BONES, 0);
		int builds = historyListBuilds();
		harness.stop();
		assertFalse("a missing price is not waited for", waiting());
		assertTrue(historyRow(), historyRow().startsWith("Loot (GE): ~100 | Supplies: 0 | Profit (GE): ~100 | "));
		assertEquals("drawn at once", builds + 1, historyListBuilds());

		// Loaded again, the history still doesn't wait for it.
		List<LedgerRecord> records = harness.records(ACCOUNT);
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, records));
		harness.drain();
		assertFalse(waiting());
		assertTrue(historyRow(), historyRow().startsWith("Loot (GE): ~100 | "));
	}

	@Test
	public void unavailablePricesAreMarkedAndLookedUpAgain()
	{
		loot();
		// No price for the dragon bones yet; the bones are priced, so the history is drawn without waiting.
		harness.setGePrice(ItemID.DRAGON_BONES, 0);
		harness.stop();
		String unpriced = historyRow();
		assertTrue(unpriced, unpriced.startsWith("Loot (GE): ~100 | Supplies: 0 | Profit (GE): ~100 | "));
		assertTrue(unpriced, unpriced.contains("Some prices were unavailable (loot: 1, supplies: 0)"));

		harness.setGePrice(ItemID.DRAGON_BONES, 3_000);
		int builds = historyListBuilds();
		harness.ticks(PriceRetry.INTERVAL_TICKS - 1);
		assertTrue("not due yet", historyRow().startsWith("Loot (GE): ~100 | "));
		assertEquals(builds, historyListBuilds());
		harness.tick();
		String row = historyRow();
		assertTrue(row, row.startsWith("Loot (GE): 3,100 | Supplies: 0 | Profit (GE): 3,100 | "));
		assertFalse(row, row.contains("Some prices were unavailable"));
		assertTrue(row, row.contains(LedgerHistory.CURRENT_PRICES));
		assertEquals("redrawn once", builds + 1, historyListBuilds());

		// Found: not looked up or redrawn again.
		harness.setGePrice(ItemID.DRAGON_BONES, 4_000);
		harness.ticks(PriceRetry.INTERVAL_TICKS * 3);
		assertTrue(historyRow(), historyRow().startsWith("Loot (GE): 3,100 | "));
		assertEquals(builds + 1, historyListBuilds());
	}

	@Test
	public void unavailablePricesAreRetriedAtMostMaxAttemptsTimes()
	{
		loot();
		harness.setGePrice(ItemID.DRAGON_BONES, 0);
		harness.stop();
		int builds = historyListBuilds();
		harness.ticks(PriceRetry.INTERVAL_TICKS * PriceRetry.MAX_ATTEMPTS);
		assertEquals("a retry that finds nothing redraws nothing", builds, historyListBuilds());

		// The cap is reached: a price that turns up later is not looked up by the retry.
		harness.setGePrice(ItemID.DRAGON_BONES, 3_000);
		harness.ticks(PriceRetry.INTERVAL_TICKS * 3);
		assertTrue(historyRow(), historyRow().startsWith("Loot (GE): ~100 | "));

		// A change of the saved tasks (here, the same ones loaded again) still asks for it.
		List<LedgerRecord> records = harness.records(ACCOUNT);
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, records));
		harness.drain();
		assertTrue(historyRow(), historyRow().startsWith("Loot (GE): 3,100 | "));
	}

	@Test
	public void newRecordsKeepThePreviousRowsUntilTheirPricesArriveThenRedrawOnce()
	{
		harness.defineItem(ItemID.RANARR_SEED, "Ranarr seed", 50_000, 20);
		loot();
		harness.stop();
		LedgerRecord saved = harness.onlyRecord(ACCOUNT);
		harness.onEdt(() -> harness.ui().getOverallTotals().clickTitle());
		harness.onEdt(() -> harness.ui().getTrends().clickTitle());
		int[] before = new int[3];
		harness.onEdt(() ->
		{
			SlayerLedgerPanel panel = harness.panel();
			PanelProbe ui = harness.ui();
			before[0] = ui.getHistory().getBuildCount();
			before[1] = ui.getOverallTotals().getBuildCount();
			before[2] = ui.getTrends().getBuildCount();
			List<String> rows = ui.getHistory().getValuesText();
			// The lookup is queued for the client thread, so it cannot answer inside this EDT task.
			harness.historyModel().setRecords(ACCOUNT, Arrays.asList(saved, ranarrRecord()));
			panel.refresh();
			assertTrue(harness.historyModel().isWaitingForCurrentPrices());
			assertEquals("the previous rows stay", rows, ui.getHistory().getValuesText());
			assertEquals(before[0], ui.getHistory().getBuildCount());
			assertEquals(before[1], ui.getOverallTotals().getBuildCount());
			assertEquals(before[2], ui.getTrends().getBuildCount());
			assertEquals("the count does not wait", "Saved tasks: 2", ui.getSavedTasksText());
		});

		harness.drain();
		harness.onEdt(() ->
		{
			PanelProbe ui = harness.ui();
			assertFalse(harness.historyModel().isWaitingForCurrentPrices());
			assertEquals("redrawn once", before[0] + 1, ui.getHistory().getBuildCount());
			assertEquals(before[1] + 1, ui.getOverallTotals().getBuildCount());
			assertEquals(before[2] + 1, ui.getTrends().getBuildCount());
			List<String> rows = ui.getHistory().getValuesText();
			assertEquals(2, rows.size());
			assertTrue(rows.toString(), rows.get(0).startsWith("Loot (GE): 50K | "));
			for (String row : rows)
			{
				assertFalse(row, row.contains("~"));
			}
		});
	}

	@Test
	public void aWaitForPricesThatNeverArriveEnds()
	{
		harness.defineItem(ItemID.RANARR_SEED, "Ranarr seed", 50_000, 20);
		loot();
		harness.stop();
		LedgerRecord saved = harness.onlyRecord(ACCOUNT);
		harness.onEdt(() ->
		{
			SlayerLedgerPanel panel = harness.panel();
			PanelProbe ui = harness.ui();
			harness.historyModel().setRecords(ACCOUNT, Arrays.asList(saved, ranarrRecord()));
			assertTrue(harness.historyModel().isWaitingForCurrentPrices());
			harness.historyModel().expireCurrentPricesWait();
			panel.refresh();
			assertFalse(harness.historyModel().isWaitingForCurrentPrices());
			List<String> rows = ui.getHistory().getValuesText();
			assertEquals("shown without the missing price", 2, rows.size());
			assertTrue(rows.toString(), rows.get(0).startsWith("Loot (GE): ~0 | "));
		});

		// A late answer is still shown.
		harness.drain();
		assertTrue(historyValues().get(0), historyValues().get(0).startsWith("Loot (GE): 50K | "));
	}

	@Test
	public void deletingTheLastTaskWhileWaitingClearsTheHistory()
	{
		harness.defineItem(ItemID.RANARR_SEED, "Ranarr seed", 50_000, 20);
		loot();
		harness.stop();
		LedgerRecord saved = harness.onlyRecord(ACCOUNT);
		harness.onEdt(() ->
		{
			PanelProbe ui = harness.ui();
			harness.historyModel().setRecords(ACCOUNT, Arrays.asList(saved, ranarrRecord()));
			assertTrue(harness.historyModel().isWaitingForCurrentPrices());
			// The account's last tasks are deleted while their prices are still being looked up.
			harness.historyModel().setRecords(ACCOUNT, Collections.emptyList());
			assertTrue("the deleted rows are gone at once", ui.getHistory().getValuesText().isEmpty());
		});
		harness.drain();
		assertTrue(historyValues().toString(), historyValues().isEmpty());
	}

	@Test
	public void highAlchTooltipsFlagMissingGePrices()
	{
		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		harness.setGePrice(ItemID.DRAGON_BONES, 0);
		loot();
		assertEquals("high alch values are all known", "Loot (HA): 5,001 gp | Supplies: 0 gp | Profit (HA): 5,001 gp",
			liveValues());
		AtomicReference<String> tooltip = new AtomicReference<>();
		harness.onEdt(() -> tooltip.set(harness.ui().getValuesTooltip()));
		assertTrue(tooltip.get(), tooltip.get().contains("Loot: ~100 gp GE, 5,001 gp high alch"));
		assertTrue(tooltip.get(), tooltip.get().contains("(loot at high alch: 0, loot at GE: 1, supplies: 0)"));

		// The history at today's prices says the same when today's GE price is missing.
		harness.stop();
		String row = historyRow();
		assertTrue(row, row.startsWith("Loot (HA): 5,001 | Supplies: 0 | Profit (HA): 5,001 | "));
		assertTrue(row, row.contains("Loot: ~100 gp GE, 5,001 gp high alch"));
		assertTrue(row, row.contains("(loot at high alch: 0, loot at GE: 1, supplies: 0)"));
	}
}
