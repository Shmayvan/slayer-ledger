package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.ui.PluginPanel;
import org.junit.Rule;
import org.junit.Test;

public class HarnessOverallTotalsTest
{
	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Test
	public void totalsAreCollapsedAndCanBeGroupedByMasterOrMonth()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		LedgerRecord konar = record(SlayerMaster.KONAR, Instant.parse("2025-01-15T12:00:00Z").toEpochMilli(),
			60_000L);
		konar.setLootGeValue(200L);
		konar.setSuppliesGeValue(50L);
		konar.setXpGained(Collections.singletonMap("SLAYER", 100L));
		LedgerRecord unknown = record(null, 0, 120_000L);
		LedgerRecord partial = record(SlayerMaster.KONAR, 0, 30_000L);
		partial.setPartial(true);
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT,
			Arrays.asList(konar, unknown, partial)));

		boolean[] expanded = new boolean[1];
		harness.onEdt(() -> expanded[0] = harness.ui().getOverallTotals().isExpanded());
		assertFalse(expanded[0]);

		String[][] rows = new String[3][];
		// The totals are worked out off the EDT: each read waits for them (rows() drains).
		harness.onEdt(() -> harness.ui().getOverallTotals().clickTitle());
		rows[0] = rows().toArray(new String[0]);
		harness.onEdt(() -> harness.ui().getOverallTotals().setGrouping(OverallTotals.Grouping.MASTER));
		rows[1] = rows().toArray(new String[0]);
		harness.onEdt(() -> harness.ui().getOverallTotals().setGrouping(OverallTotals.Grouping.MONTH));
		rows[2] = rows().toArray(new String[0]);

		// Short two-column lines that fit the sidebar; amounts without " gp", as history rows.
		assertEquals(1, rows[0].length);
		assertEquals("Overall | 2 tasks\n"
			+ "Active: | 0:03:00\n"
			+ "Profit (GE): | ~150 (n=1)\n"
			+ "XP (all skills): | 100 (n=1)", rows[0][0]);
		assertEquals(2, rows[1].length);
		assertTrue(rows[1][0], rows[1][0].startsWith("Konar | 1 task\n"));
		// By master's own lines; a task without values, points or XP leaves them unknown.
		assertEquals("Unknown master | 1 task\n"
			+ "Total tasks: | 1\n"
			+ "Avg task: | 0:02:00\n"
			+ "Avg profit (GE): | -\n"
			+ "Profit (GE): | - (n=0)\n"
			+ "Profit (GE)/h: | -\n"
			+ "Points: | - (n=0)\n"
			+ "Avg points: | -\n"
			+ "Slayer XP: | - (n=0)\n"
			+ "Avg Slayer XP: | -", rows[1][1]);
		assertEquals(2, rows[2].length);
		assertTrue(rows[2][0], rows[2][0].startsWith("2025-01 | 1 task\n"));
		assertTrue(rows[2][1], rows[2][1].startsWith("Unknown date | 1 task\n"));

		String tooltip = tooltips().get(1);
		assertTrue(tooltip, tooltip.contains("Unknown date"));
		assertTrue(tooltip, tooltip.contains("n = 0 tasks with loot and supply values"));
	}

	@Test
	public void profitFollowsTheLootPriceModeAtTodaysPrices()
	{
		// Today's GE price of the bones (3,000) is not the one saved with the task (2,000).
		harness.defineItem(ItemID.DRAGON_BONES, "Dragon bones", 3_000, 5_000);
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		LedgerRecord record = record(SlayerMaster.KONAR, 1, 60 * 60_000L);
		record.setLoot(Collections.singletonMap(ItemID.DRAGON_BONES, 10L));
		record.setUnitGePrices(Collections.singletonMap(ItemID.DRAGON_BONES, 2_000L));
		record.setUnitHaPrices(Collections.singletonMap(ItemID.DRAGON_BONES, 5_000L));
		record.setLootGeValue(20_000L);
		record.setLootHaValue(50_000L);
		record.setSuppliesGeValue(0L);
		record.setXpGained(Collections.singletonMap("SLAYER", 1_000L));
		harness.onEdt(() ->
		{
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, Collections.singletonList(record));
			harness.ui().getOverallTotals().clickTitle();
		});
		// The lookup is queued for the client thread, and its answer for the EDT.
		harness.drain();
		assertEquals("Profit (GE): | 30K (n=1)", profitLine());
		String tooltip = tooltips().get(0);
		assertTrue(tooltip, tooltip.contains("Profit (GE): 30,000 gp"));
		assertTrue(tooltip, tooltip.contains("Loot at GE prices minus GE-valued supplies (today's available GE prices)"));
		assertTrue(tooltip, tooltip.contains("n = 1 task with loot and supply values"));
		assertTrue(tooltip, tooltip.contains("XP (all skills): 1,000"));
		assertTrue(tooltip, tooltip.contains("n = 1 task with recorded XP"));

		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		assertEquals("high alch values don't change", "Profit (HA): | 50K (n=1)", profitLine());
		assertTrue(tooltips().get(0), tooltips().get(0).contains("Profit (HA): 50,000 gp"));
		assertTrue(tooltips().get(0), tooltips().get(0).contains("today's available GE prices for GE-priced items"));
	}

	@Test
	public void anUnavailablePriceIsMarkedApproximate()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		LedgerRecord record = record(SlayerMaster.KONAR, 1, 60_000L);
		record.setLootGeValue(1_500L);
		record.setSuppliesGeValue(0L);
		record.setLootUnpricedItems(1);
		harness.onEdt(() ->
		{
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, Collections.singletonList(record));
			harness.ui().getOverallTotals().clickTitle();
		});

		assertEquals("Profit (GE): | ~1,500 (n=1)", profitLine());
		String tooltip = tooltips().get(0);
		assertTrue(tooltip, tooltip.contains("Profit (GE): ~1,500 gp"));
		assertTrue(tooltip,
			tooltip.contains("~ means some item prices were unavailable, or an older task kept its saved totals."));
	}

	@Test
	public void nothingIsSummedWhileCollapsedOrByTheRefreshTimer()
	{
		harness.defineItem(ItemID.DRAGON_BONES, "Dragon bones", 2_000, 5_000);
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		LedgerRecord record = record(SlayerMaster.KONAR, 1, 60 * 60_000L);
		record.setLoot(Collections.singletonMap(ItemID.DRAGON_BONES, 10L));
		record.setLootGeValue(20_000L);
		record.setLootHaValue(50_000L);
		// With its high alch unit value, so the High alch total is exact.
		record.setUnitHaPrices(Collections.singletonMap(ItemID.DRAGON_BONES, 5_000L));
		record.setSuppliesGeValue(0L);
		int[] builds = new int[2];
		harness.onEdt(() ->
		{
			PanelProbe ui = harness.ui();
			builds[0] = ui.getOverallTotals().getBuildCount();
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, Collections.singletonList(record));
			ui.getOverallTotals().setGrouping(OverallTotals.Grouping.MASTER);
			ui.getOverallTotals().setGrouping(OverallTotals.Grouping.ALL);
		});
		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		harness.onEdt(() -> harness.historyModel().addCurrentPrices(Collections.singleton(ItemID.DRAGON_BONES),
			Collections.singletonMap(ItemID.DRAGON_BONES, 3_000L)));
		harness.drain();
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			builds[1] = harness.ui().getOverallTotals().getBuildCount();
		});
		assertEquals("collapsed: no totals built", builds[0], builds[1]);
		assertTrue(rows().isEmpty());

		// Opening builds once, when the totals are worked out (off the EDT); the 1-second refresh never rebuilds.
		harness.onEdt(() ->
		{
			builds[0] = harness.ui().getOverallTotals().getBuildCount();
			harness.ui().getOverallTotals().clickTitle();
		});
		harness.drain();
		harness.onEdt(() ->
		{
			assertEquals(builds[0] + 1, harness.ui().getOverallTotals().getBuildCount());
			builds[0] = harness.ui().getOverallTotals().getBuildCount();
			harness.panel().refresh();
			harness.panel().refresh();
		});
		harness.drain();
		harness.onEdt(() -> builds[1] = harness.ui().getOverallTotals().getBuildCount());
		assertEquals(builds[0], builds[1]);
		assertEquals("Profit (HA): | 50K (n=1)", profitLine());

		// Closing it drops the rows; record and grouping changes while closed sum nothing.
		harness.onEdt(() ->
		{
			PanelProbe ui = harness.ui();
			ui.getOverallTotals().clickTitle();
			builds[0] = ui.getOverallTotals().getBuildCount();
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, Collections.singletonList(record));
			ui.getOverallTotals().setGrouping(OverallTotals.Grouping.MONTH);
			builds[1] = ui.getOverallTotals().getBuildCount();
		});
		assertEquals(builds[0], builds[1]);
		assertTrue(rows().isEmpty());
	}

	@Test
	public void eachGroupShowsItsSuppliesAndLootAsClosedIconGrids()
	{
		harness.defineItem(ItemID.SHARK, "Shark", 1_000, 50);
		harness.defineItem(ItemID._4DOSEPRAYERRESTORE, "Prayer potion(4)", 8_000, 100);
		harness.defineItem(ItemID.RUNE_PLATEBODY, "Rune platebody", 38_000, 39_000);
		// The alch hint subtracts the nature rune: 39,000 - 90 still beats 38,000.
		harness.defineItem(ItemID.NATURERUNE, "Nature rune", 90, 108);
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		LedgerRecord newer = itemized(map(ItemID.SHARK, 3, ItemID._4DOSEPRAYERRESTORE, 6), 1);
		newer.setSupplyDoses(Collections.singletonMap(ItemID._4DOSEPRAYERRESTORE, 4));
		LedgerRecord older = itemized(map(ItemID.SHARK, 2), 1);
		// Saved before loot and supplies were tracked: no item data.
		LedgerRecord old = record(SlayerMaster.KRYSTILIA, 1, 60_000L);
		old.setLootGeValue(5_000L);
		harness.onEdt(() ->
		{
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, Arrays.asList(newer, older, old));
			harness.ui().getOverallTotals().clickTitle();
			harness.ui().getOverallTotals().setGrouping(OverallTotals.Grouping.MASTER);
		});
		// The totals are worked out off the EDT, then the item names on the client thread.
		harness.drain();

		assertEquals(Arrays.asList("Supplies used (2) | Show", "Loot received (1) | Show",
			"Supplies used (0) | Show", "Loot received (0) | Show"), onEdt(() -> Arrays.asList(
			harness.ui().getOverallTotals().getSupplies(0).getHeaderText(),
			harness.ui().getOverallTotals().getLoot(0).getHeaderText(),
			harness.ui().getOverallTotals().getSupplies(1).getHeaderText(),
			harness.ui().getOverallTotals().getLoot(1).getHeaderText())));
		assertTrue("closed: no icons", onEdt(() -> harness.ui().getOverallTotals().getSupplies(0).getCellTexts())
			.isEmpty());
		// The grids sit below the group's lines, which read as before.
		assertTrue(rows().get(0), rows().get(0).startsWith("Konar | 2 tasks\n"));

		int[] builds = new int[2];
		harness.onEdt(() ->
		{
			builds[0] = harness.ui().getOverallTotals().getBuildCount();
			harness.ui().getOverallTotals().getSupplies(0).toggle();
			harness.ui().getOverallTotals().getLoot(0).toggle();
			harness.ui().getOverallTotals().getSupplies(1).toggle();
			harness.ui().getOverallTotals().getLoot(1).toggle();
		});
		harness.drain();
		// Summed over both Konar tasks: 6 doses, 3 + 2 sharks, 1 + 1 platebodies, as history details show them.
		assertEquals(Arrays.asList("Prayer potion | " + ItemID._4DOSEPRAYERRESTORE + " x6",
			"Shark | " + ItemID.SHARK + " x5"), onEdt(() -> harness.ui().getOverallTotals().getSupplies(0)
			.getCellTexts()));
		assertEquals(Collections.singletonList("Rune platebody | " + ItemID.RUNE_PLATEBODY + " x2 | (alch)"),
			onEdt(() -> harness.ui().getOverallTotals().getLoot(0).getCellTexts()));
		assertTooltips(onEdt(() -> harness.ui().getOverallTotals().getSupplies(0).getCellTooltips()),
			"Quantity: 6 doses<br>Value (GE): 12,000 gp", "Quantity: 5<br>Value (GE): 5,000 gp");
		assertTooltips(onEdt(() -> harness.ui().getOverallTotals().getLoot(0).getCellTooltips()),
			"Quantity: 2<br>Value (GE): 76,000 gp");
		assertTrue(onEdt(() -> harness.ui().getOverallTotals().getLoot(0).getCellTooltips()).get(0)
			.contains(ItemDetails.ALCH_MARKER + " High alch"));
		// Krystilia's only task has no item data.
		assertEquals(Collections.singletonList("None"),
			onEdt(() -> harness.ui().getOverallTotals().getSupplies(1).getCellTexts()));
		assertEquals(Collections.singletonList("None"),
			onEdt(() -> harness.ui().getOverallTotals().getLoot(1).getCellTexts()));

		// Opening grids and the refresh timer rebuild no totals.
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			harness.panel().refresh();
		});
		harness.drain();
		harness.onEdt(() -> builds[1] = harness.ui().getOverallTotals().getBuildCount());
		assertEquals(builds[0], builds[1]);

		// The Loot price setting revalues the loot as the profit line; an open grid stays open.
		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		harness.drain();
		assertTrue(rows().get(0), rows().get(0).contains("\nProfit (HA): | 61K (n=2)\n"));
		assertEquals("Loot received (1) | Hide", onEdt(() -> harness.ui().getOverallTotals().getLoot(0)
			.getHeaderText()));
		assertTooltips(onEdt(() -> harness.ui().getOverallTotals().getLoot(0).getCellTooltips()),
			"Value (high alch): 78,000 gp");
	}

	@Test
	public void namesArriveFromTheClientThreadWithoutRebuildingTheTotals()
	{
		harness.defineItem(ItemID.RUNE_PLATEBODY, "Rune platebody", 38_000, 39_000);
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		LedgerRecord record = itemized(Collections.emptyMap(), 1);
		int[] builds = new int[1];
		int calls = harness.pluginCallCount();
		harness.onEdt(() ->
		{
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, Collections.singletonList(record));
			builds[0] = harness.ui().getOverallTotals().getBuildCount();
			harness.ui().getOverallTotals().clickTitle();
		});
		// The totals are built once they are worked out; the names asked for then arrive from the client thread and
		// only rename the grids.
		harness.drain();
		assertEquals(builds[0] + 1, (int) onEdt(() -> harness.ui().getOverallTotals().getBuildCount()));
		assertTrue("names were looked up through ItemManager", harness.pluginCallCount() > calls);
		assertEquals(Collections.singletonList("Rune platebody | " + ItemID.RUNE_PLATEBODY), onEdt(() ->
		{
			harness.ui().getOverallTotals().getLoot(0).toggle();
			return harness.ui().getOverallTotals().getLoot(0).getCellTexts();
		}));

		// Opening and closing a grid reads nothing from the client and builds no totals.
		int before = harness.pluginCallCount();
		harness.onEdt(() ->
		{
			harness.ui().getOverallTotals().getLoot(0).toggle();
			harness.ui().getOverallTotals().getLoot(0).toggle();
		});
		harness.drain();
		assertEquals(before, harness.pluginCallCount());
		assertEquals(builds[0] + 1, (int) onEdt(() -> harness.ui().getOverallTotals().getBuildCount()));
	}

	@Test
	public void byMonsterHasItsOwnLinesAndClosedGrids()
	{
		SavedItems.define(harness);
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		LedgerRecord dustDevils = monster("Dust devils", 100, 30 * 60_000L);
		dustDevils.setLootGeValue(100_000L);
		dustDevils.setSuppliesGeValue(10_000L);
		SavedItems.withItems(dustDevils);
		dustDevils.setXpGained(Collections.singletonMap("SLAYER", 9_000L));
		// A cannon task saved without values.
		LedgerRecord cannon = monster("Dust devils", 50, 10 * 60_000L);
		cannon.setSupplies(Collections.singletonMap(SupplyTracker.CANNONBALL, 200L));
		LedgerRecord deviant = monster("Aberrant spectres", 40, 20 * 60_000L);
		deviant.setVariant("Deviant spectres");
		LedgerRecord deviant2 = monster("Aberrant spectres", 40, 20 * 60_000L);
		deviant2.setVariant("Deviant spectres");
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT,
			Arrays.asList(dustDevils, cannon, deviant, deviant2)));

		assertEquals(Arrays.asList("Overall", "By master", "By month", "By monster"),
			onEdt(() -> harness.ui().getOverallTotals().getGroupings()));
		harness.onEdt(() ->
		{
			harness.ui().getOverallTotals().clickTitle();
			harness.ui().getOverallTotals().setGrouping(OverallTotals.Grouping.MONSTER);
		});
		List<String> rows = rows();

		// Most tasks first; a Catacombs variant and a cannon task are monsters of their own.
		assertEquals(3, rows.size());
		assertTrue(rows.get(0), rows.get(0).startsWith("Deviant spectres | 2 tasks\n"));
		assertTrue(rows.get(2), rows.get(2).startsWith("Dust devils (cannon) | 1 task\n"));
		assertEquals("Dust devils | 1 task\n"
			+ "Tasks done: | 1\n"
			+ "Killed: | 100\n"
			+ "Avg task: | 0:30:00\n"
			+ "Avg profit (GE): | 90K (n=1)\n"
			+ "Profit (GE)/kill: | 900\n"
			+ "Time/kill: | 18s\n"
			+ "Profit (GE)/h: | 180K\n"
			+ "Slayer XP: | 9,000 (n=1)\n"
			+ "Slayer XP/h: | 18K", rows.get(1));
		// Without values or XP, the lines say so.
		assertTrue(rows.get(2), rows.get(2).contains("\nAvg profit (GE): | - (n=0)\nProfit (GE)/kill: | -\n"));
		assertTrue(rows.get(2), rows.get(2).endsWith("\nSlayer XP: | - (n=0)\nSlayer XP/h: | -"));
		String tooltip = tooltips().get(1);
		assertTrue(tooltip, tooltip.contains("Profit (GE) per hour: 180,000 gp"));
		assertTrue(tooltip, tooltip.contains("Averages use those tasks; rates pool those of them with active time"));
		assertTrue(tooltip, tooltip.contains("hourly rates need 1 minute of it"));
		assertEquals("1 completed task", onEdt(() -> harness.ui().getOverallTotals().getTaskCountTooltip(1)));

		// Short lines: the sidebar doesn't widen.
		int width = onEdt(() -> harness.ui().getOverallTotals().getListPreferredWidth());
		assertTrue("wants " + width, width <= PluginPanel.PANEL_WIDTH);

		// The item grids start closed, and By monster has no monsters assigned.
		assertEquals(Arrays.asList("Supplies used (1) | Show", "Loot received (1) | Show"), onEdt(() -> Arrays.asList(
			harness.ui().getOverallTotals().getSupplies(1).getHeaderText(),
			harness.ui().getOverallTotals().getLoot(1).getHeaderText())));
		assertFalse(onEdt(() -> harness.ui().getOverallTotals().hasAssignments(1)));

		// The refresh timer never rebuilds it.
		int[] builds = new int[2];
		harness.onEdt(() ->
		{
			builds[0] = harness.ui().getOverallTotals().getBuildCount();
			harness.panel().refresh();
			harness.panel().refresh();
		});
		harness.drain();
		harness.onEdt(() -> builds[1] = harness.ui().getOverallTotals().getBuildCount());
		assertEquals(builds[0], builds[1]);
	}

	@Test
	public void byMasterListsTheMonstersAssignedWithSkippedAndBlockedOnes()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		List<LedgerRecord> records = new ArrayList<>();
		for (int i = 0; i < 3; i++)
		{
			LedgerRecord task = monster("Dust devils", 100, 30 * 60_000L);
			task.setPointsEarned(18);
			records.add(task);
		}
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT, records));
		// 11 other monsters skipped once each, and Dust devils blocked once: 15 assignments.
		List<AssignmentEntry> entries = new ArrayList<>();
		String[] skipped = {"Abyssal demons", "Basilisks", "Black demons", "Bloodvelds", "Cave kraken",
			"Dark beasts", "Fire giants", "Gargoyles", "Hellhounds", "Kalphite", "Kurask"};
		for (String name : skipped)
		{
			entries.add(entry(name, AssignmentOutcome.SKIPPED));
		}
		entries.add(entry("Dust devils", AssignmentOutcome.BLOCKED));
		harness.onEdt(() ->
		{
			harness.historyModel().setAssignments(PluginHarness.ACCOUNT, entries);
			harness.ui().getOverallTotals().clickTitle();
			harness.ui().getOverallTotals().setGrouping(OverallTotals.Grouping.MASTER);
		});

		assertEquals("Konar | 3 tasks\n"
			+ "Total tasks: | 3\n"
			+ "Avg task: | 0:30:00\n"
			+ "Avg profit (GE): | -\n"
			+ "Profit (GE): | - (n=0)\n"
			+ "Profit (GE)/h: | -\n"
			+ "Points: | 54 (n=3)\n"
			+ "Avg points: | 18\n"
			+ "Slayer XP: | - (n=0)\n"
			+ "Avg Slayer XP: | -", rows().get(0));
		// The title counts completed tasks, not the 15 assignments below.
		assertEquals("3 completed tasks", onEdt(() -> harness.ui().getOverallTotals().getTaskCountTooltip(0)));
		String masterTooltip = tooltips().get(0);
		assertTrue(masterTooltip, masterTooltip.contains("Slayer points: 54, per task: 18"));
		assertTrue(masterTooltip,
			masterTooltip.contains("Per task leaves out bonus tasks (every 10th in a streak): 3 tasks"));
		assertEquals("Monsters assigned", onEdt(() -> harness.ui().getOverallTotals().getAssignments(0)
			.getHeaderText()));
		List<String> lines = onEdt(() -> harness.ui().getOverallTotals().getAssignments(0).getLineTexts());
		assertEquals(ItemDetails.MAX_ROWS + 1, lines.size());
		assertEquals("Dust devils | 4 / 15 (27%)", lines.get(0));
		assertEquals("Abyssal demons | 1 / 15 (7%)", lines.get(1));
		assertEquals("+2 more", lines.get(ItemDetails.MAX_ROWS));
		String tooltip = onEdt(() -> harness.ui().getOverallTotals().getAssignments(0).getLineTooltips()).get(0);
		assertTrue(tooltip, tooltip.contains("4 of 15 assignments from Konar (26.7%)<br>Completed: 3<br>Blocked: 1"));
		// The item grids stay closed below it.
		assertEquals("Supplies used (0) | Show", onEdt(() -> harness.ui().getOverallTotals().getSupplies(0)
			.getHeaderText()));

		int width = onEdt(() -> harness.ui().getOverallTotals().getListPreferredWidth());
		assertTrue("wants " + width, width <= PluginPanel.PANEL_WIDTH);

		// "+2 more" shows them all, and stays shown while the same group rebuilds.
		harness.onEdt(() -> harness.ui().getOverallTotals().getAssignments(0).clickShowAll());
		assertEquals(13, (int) onEdt(() -> harness.ui().getOverallTotals().getAssignments(0).getLineTexts().size()));
		int[] builds = new int[2];
		harness.onEdt(() ->
		{
			builds[0] = harness.ui().getOverallTotals().getBuildCount();
			harness.panel().refresh();
			harness.panel().refresh();
		});
		harness.drain();
		harness.onEdt(() -> builds[1] = harness.ui().getOverallTotals().getBuildCount());
		assertEquals("the refresh timer rebuilds nothing", builds[0], builds[1]);

		// A new Skipped / blocked entry is counted at once.
		List<AssignmentEntry> more = new ArrayList<>(entries);
		more.add(entry("Kurask", AssignmentOutcome.SKIPPED));
		harness.onEdt(() -> harness.historyModel().setAssignments(PluginHarness.ACCOUNT, more));
		harness.drain();
		lines = onEdt(() -> harness.ui().getOverallTotals().getAssignments(0).getLineTexts());
		assertEquals("Dust devils | 4 / 16 (25%)", lines.get(0));
		assertEquals("Kurask | 2 / 16 (13%)", lines.get(1));
		assertEquals("Show less", lines.get(lines.size() - 1));
	}

	@Test
	public void longAssignmentNamesAndAmountsDontWidenTheSidebar()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		String longName = "Lava strykewyrms in the Wilderness Slayer Cave";
		String bossTask = "Unknown boss task (12)";
		LedgerRecord boss = monster(bossTask, 1, 30 * 60_000L);
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT, Collections.singletonList(boss)));
		// 500 assignments, 100 of each monster: the completed task and 499 entries.
		List<AssignmentEntry> entries = new ArrayList<>();
		for (String name : Arrays.asList(longName, bossTask, "Abyssal demons", "Dust devils", "Gargoyles"))
		{
			for (int i = name.equals(bossTask) ? 1 : 0; i < 100; i++)
			{
				entries.add(entry(name, AssignmentOutcome.SKIPPED));
			}
		}
		harness.onEdt(() ->
		{
			harness.historyModel().setAssignments(PluginHarness.ACCOUNT, entries);
			harness.ui().getOverallTotals().clickTitle();
			harness.ui().getOverallTotals().setGrouping(OverallTotals.Grouping.MASTER);
		});

		assertTrue(rows().get(0), rows().get(0).startsWith("Konar | 1 task\n"));
		List<String> lines = onEdt(() -> harness.ui().getOverallTotals().getAssignments(0).getLineTexts());
		assertEquals(5, lines.size());
		assertTrue(lines.toString(), lines.contains(longName + " | 100 / 500 (20%)"));
		assertTrue(lines.toString(), lines.contains(bossTask + " | 100 / 500 (20%)"));
		int width = onEdt(() -> harness.ui().getOverallTotals().getListPreferredWidth());
		assertTrue("wants " + width, width <= PluginPanel.PANEL_WIDTH);
	}

	/** A completed Konar task of {@code name}, without values. */
	private static LedgerRecord monster(String name, int kills, long activeMillis)
	{
		LedgerRecord record = record(SlayerMaster.KONAR, 1, activeMillis);
		record.setMonster(name);
		record.setKills(kills);
		return record;
	}

	/** A Konar "Skipped / blocked" entry. */
	private static AssignmentEntry entry(String name, AssignmentOutcome outcome)
	{
		AssignmentEntry entry = new AssignmentEntry();
		entry.setId(UUID.randomUUID().toString());
		entry.setMonster(name);
		entry.setSlayerMaster(SlayerMaster.KONAR);
		entry.setOutcome(outcome);
		entry.setEndedAt(1);
		return entry;
	}

	/** Reads {@code read} on the EDT. */
	private <T> T onEdt(Supplier<T> read)
	{
		AtomicReference<T> value = new AtomicReference<>();
		harness.onEdt(() -> value.set(read.get()));
		return value.get();
	}

	/** Asserts that each tooltip contains its expected text, in order. */
	private static void assertTooltips(List<String> tooltips, String... expected)
	{
		assertEquals(tooltips.toString(), expected.length, tooltips.size());
		for (int i = 0; i < expected.length; i++)
		{
			assertTrue(tooltips.get(i), tooltips.get(i).contains(expected[i]));
		}
	}

	/**
	 * A Konar task with {@code supplies} and one rune platebody of loot per {@code platebodies}, at saved prices (shark
	 * 1,000, prayer potion 8,000, platebody GE 38,000 and high alch 39,000), valued as the plugin saves it.
	 */
	private static LedgerRecord itemized(Map<Integer, Long> supplies, long platebodies)
	{
		LedgerRecord record = record(SlayerMaster.KONAR, 1, 60_000L);
		record.setSupplies(supplies);
		record.setLoot(Collections.singletonMap(ItemID.RUNE_PLATEBODY, platebodies));
		record.setUnitGePrices(map(ItemID.SHARK, 1_000, ItemID._4DOSEPRAYERRESTORE, 8_000, ItemID.RUNE_PLATEBODY,
			38_000));
		record.setUnitHaPrices(map(ItemID.SHARK, 50, ItemID._4DOSEPRAYERRESTORE, 100, ItemID.RUNE_PLATEBODY, 39_000));
		record.setLootGeValue(38_000L * platebodies);
		record.setLootHaValue(39_000L * platebodies);
		long supplyValue = 0;
		for (Map.Entry<Integer, Long> e : supplies.entrySet())
		{
			supplyValue += e.getKey() == ItemID.SHARK ? 1_000 * e.getValue() : 2_000 * e.getValue();
		}
		record.setSuppliesGeValue(supplyValue);
		return record;
	}

	private static Map<Integer, Long> map(long... idValue)
	{
		Map<Integer, Long> m = new HashMap<>();
		for (int i = 0; i < idValue.length; i += 2)
		{
			m.put((int) idValue[i], idValue[i + 1]);
		}
		return m;
	}

	/** The profit line of the first entry. */
	private String profitLine()
	{
		return rows().get(0).split("\n")[2];
	}

	private List<String> rows()
	{
		@SuppressWarnings("unchecked")
		List<String>[] rows = new List[1];
		// The totals are worked out off the EDT.
		harness.drain();
		harness.onEdt(() -> rows[0] = harness.ui().getOverallTotals().getRows());
		return rows[0];
	}

	private List<String> tooltips()
	{
		@SuppressWarnings("unchecked")
		List<String>[] tooltips = new List[1];
		harness.drain();
		harness.onEdt(() -> tooltips[0] = harness.ui().getOverallTotals().getTooltips());
		return tooltips[0];
	}

	private static LedgerRecord record(Integer master, long endedAt, long activeMillis)
	{
		LedgerRecord record = new LedgerRecord();
		record.setSlayerMaster(master);
		record.setEndedAt(endedAt);
		record.setKills(10);
		record.setActiveMillis(activeMillis);
		record.setCompleted(true);
		record.setPartial(false);
		return record;
	}
}
