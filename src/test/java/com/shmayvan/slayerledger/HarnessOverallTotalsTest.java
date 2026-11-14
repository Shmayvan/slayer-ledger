package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.awt.Color;
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

		// Short lines that fit the sidebar: the label, then a total's tasks when only some recorded it, then the value
		// (signed for a profit, without " gp"); the group's tasks on its title line (9d).
		assertEquals(1, rows[0].length);
		assertEquals("Overall | 2 tasks\n"
			+ "Active time | 0:03:00\n"
			+ "Profit (GE) | 1 task | ~+150\n"
			+ "XP (all skills) | 1 task | 100", rows[0][0]);
		assertEquals(2, rows[1].length);
		assertTrue(rows[1][0], rows[1][0].startsWith("Konar | 1 task\n"));
		// By master's own lines; a task without values, points or XP leaves them unknown.
		assertEquals("Unknown master | 1 task\n"
			+ "Avg task | 0:02:00\n"
			+ "Avg profit (GE) | -\n"
			+ "Profit (GE) | 0 tasks | -\n"
			+ "Profit (GE)/h | -\n"
			+ "Points | 0 tasks | -\n"
			+ "Avg points | -\n"
			+ "Slayer XP | 0 tasks | -\n"
			+ "Avg Slayer XP | -", rows[1][1]);
		assertEquals(2, rows[2].length);
		assertTrue(rows[2][0], rows[2][0].startsWith("2025-01 | 1 task\n"));
		assertTrue(rows[2][1], rows[2][1].startsWith("Unknown date | 1 task\n"));

		String tooltip = tooltips().get(1);
		assertTrue(tooltip, tooltip.contains("Unknown date"));
		assertTrue(tooltip, tooltip.contains("Profit from 0 tasks with loot and supply values"));
	}

	@Test
	public void profitFollowsTheLootPriceModeAtTodaysPrices()
	{
		// Today's GE price of the bones (3,000) is not the one saved with the task (2, 000).
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
		assertEquals("Profit (GE) | +30K", profitLine());
		String tooltip = tooltips().get(0);
		assertTrue(tooltip, tooltip.contains("Profit (GE): 30,000 gp"));
		assertTrue(tooltip, tooltip.contains("Loot at GE prices minus GE-valued supplies (today's available GE prices)"));
		assertTrue(tooltip, tooltip.contains("Profit from 1 task with loot and supply values"));
		assertTrue(tooltip, tooltip.contains("XP (all skills): 1,000"));
		assertTrue(tooltip, tooltip.contains("XP from 1 task with recorded XP"));

		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		assertEquals("high alch values don't change", "Profit (HA) | +50K", profitLine());
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

		assertEquals("Profit (GE) | ~+1,500", profitLine());
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
		assertEquals("Profit (HA) | +50K", profitLine());

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

		// Flat sub-headers like the live grids' (9d): only hidden items are counted in the title.
		assertEquals(Arrays.asList("Supplies used | Show", "Loot received | Show",
			"Supplies used | Show", "Loot received | Show"), onEdt(() -> Arrays.asList(
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
		assertTrue(rows().get(0), rows().get(0).contains("\nProfit (HA) | +61K\n"));
		assertEquals("Loot received | Hide", onEdt(() -> harness.ui().getOverallTotals().getLoot(0)
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
		// The tasks done are the title's sample size; the pace is the live card's (9d).
		assertEquals("Dust devils | 1 task\n"
			+ "Kills | 100\n"
			+ "Avg task | 0:30:00\n"
			+ "Avg profit (GE) | +90K\n"
			+ "Profit (GE)/kill | +900\n"
			+ "Pace | 0:18/kill\n"
			+ "Profit (GE)/h | +180K\n"
			+ "Slayer XP | 9,000\n"
			+ "Slayer XP/h | 18K", rows.get(1));
		// Without values or XP, the lines say so.
		assertTrue(rows.get(2), rows.get(2).contains("\nAvg profit (GE) | 0 tasks | -\nProfit (GE)/kill | -\n"));
		assertTrue(rows.get(2), rows.get(2).endsWith("\nSlayer XP | 0 tasks | -\nSlayer XP/h | -"));
		String tooltip = tooltips().get(1);
		assertTrue(tooltip, tooltip.contains("Profit (GE) per hour: 180,000 gp"));
		assertTrue(tooltip, tooltip.contains("Averages use those tasks; rates pool those of them with active time"));
		assertTrue(tooltip, tooltip.contains("hourly rates need 1 minute of it"));
		assertEquals("1 completed task", onEdt(() -> harness.ui().getOverallTotals().getTaskCountTooltip(1)));

		// Short lines: the sidebar doesn't widen.
		int width = onEdt(() -> harness.ui().getOverallTotals().getListPreferredWidth());
		assertTrue("wants " + width, width <= PluginPanel.PANEL_WIDTH);

		// The item grids start closed, and By monster has no monsters assigned.
		assertEquals(Arrays.asList("Supplies used | Show", "Loot received | Show"), onEdt(() -> Arrays.asList(
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
			+ "Avg task | 0:30:00\n"
			+ "Avg profit (GE) | -\n"
			+ "Profit (GE) | 0 tasks | -\n"
			+ "Profit (GE)/h | -\n"
			+ "Points | 54\n"
			+ "Avg points | 18\n"
			+ "Slayer XP | 0 tasks | -\n"
			+ "Avg Slayer XP | -", rows().get(0));
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
		assertEquals("Supplies used | Show", onEdt(() -> harness.ui().getOverallTotals().getSupplies(0)
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
	public void nestedSubHeadersAreQuieterThanTheBlockTitles()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		LedgerRecord duradel = monster("Gargoyles", 150, 40 * 60_000L);
		duradel.setSlayerMaster(5);
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT,
			Arrays.asList(monster("Dust devils", 200, 50 * 60_000L), duradel)));
		harness.onEdt(() ->
		{
			harness.ui().getOverallTotals().clickTitle();
			harness.ui().getOverallTotals().setGrouping(OverallTotals.Grouping.MASTER);
		});
		harness.drain();

		// DESIGN_PRINCIPLES.md 3.8: a block's title is Bold in the heading colour; the lists inside the block have
		// quieter sub-headers, Small with no rule, so it is clear where one block ends and the next begins. A grid's
		// Show / Hide stays on the right in Small in the label colour.
		for (int i = 0; i < 2; i++)
		{
			int index = i;
			assertEquals("Bold heading", onEdt(() -> harness.ui().getOverallTotals().getNameLook(index)));
			assertEquals("Small value | no rule",
				onEdt(() -> harness.ui().getOverallTotals().getAssignments(index).getHeaderLook()));
			assertEquals("Small value | Small label | no rule",
				onEdt(() -> harness.ui().getOverallTotals().getSupplies(index).getHeaderLook()));
			assertEquals("Small value | Small label | no rule",
				onEdt(() -> harness.ui().getOverallTotals().getLoot(index).getHeaderLook()));
		}
		// An open grid's header looks the same.
		harness.onEdt(() -> harness.ui().getOverallTotals().getLoot(0).toggle());
		assertEquals("Loot received | Hide", onEdt(() -> harness.ui().getOverallTotals().getLoot(0).getHeaderText()));
		assertEquals("Small value | Small label | no rule",
			onEdt(() -> harness.ui().getOverallTotals().getLoot(0).getHeaderLook()));

		// By monster's grids too.
		harness.onEdt(() -> harness.ui().getOverallTotals().setGrouping(OverallTotals.Grouping.MONSTER));
		harness.drain();
		assertEquals("Bold heading", onEdt(() -> harness.ui().getOverallTotals().getNameLook(0)));
		assertEquals("Small value | Small label | no rule",
			onEdt(() -> harness.ui().getOverallTotals().getSupplies(0).getHeaderLook()));
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

	@Test
	public void sampleSizesReadAsTasksAndUnitsSitInTheLabels()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		// Konar: two tasks with values, XP and points, and one saved without any. Duradel: one with all of them.
		List<LedgerRecord> records = new ArrayList<>();
		for (int i = 0; i < 2; i++)
		{
			records.add(valued(monster("Dust devils", 200, 50 * 60_000L), 412_000L, 30_000L, 24_000L, 15));
		}
		records.add(monster("Dust devils", 150, 40 * 60_000L));
		LedgerRecord duradel = valued(monster("Abyssal demons", 140, 60 * 60_000L), 905_000L, 120_000L, 21_000L, 15);
		duradel.setSlayerMaster(5);
		records.add(duradel);
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT, records));
		harness.onEdt(() -> harness.ui().getOverallTotals().clickTitle());

		for (OverallTotals.Grouping grouping : OverallTotals.Grouping.values())
		{
			harness.onEdt(() -> harness.ui().getOverallTotals().setGrouping(grouping));
			List<String> rows = rows();
			for (String text : concat(rows, tooltips()))
			{
				// DESIGN_PRINCIPLES.md 3.8: "3 tasks", never "n=3".
				assertFalse(grouping + ": " + text, text.contains("n=") || text.contains("n = "));
			}
			for (String row : rows)
			{
				assertTrue(grouping + ": " + row, row.split("\n")[0].matches(".+ \\| \\d+ tasks?"));
				for (String line : row.substring(row.indexOf('\n') + 1).split("\n"))
				{
					String[] parts = line.split(" \\| ");
					String label = parts[0];
					String value = parts[parts.length - 1];
					// The unit is in the label (Profit (GE)/h); a value is a bare amount: no " gp", no "/h".
					assertFalse(line, value.contains("gp") || value.contains("/h") || label.endsWith(":"));
					if (label.equals("Pace"))
					{
						// DESIGN_PRINCIPLES.md 5.5's pace, as the live card shows it.
						assertTrue(line, value.matches("\\d+:\\d\\d/kill"));
					}
					else
					{
						assertTrue(line, value.matches("-|~?[+-]?[\\d,.]+[KMB]?|\\d+:\\d\\d:\\d\\d"));
					}
				}
			}
		}

		// By master (masters by name): the title says the group's tasks; a total that only some recorded says how many.
		// The saved tasks keep only their totals, so their GE profit is marked ~.
		harness.onEdt(() -> harness.ui().getOverallTotals().setGrouping(OverallTotals.Grouping.MASTER));
		List<String> duradelRows = Arrays.asList(rows().get(0).split("\n"));
		assertEquals("Duradel/Kuradal | 1 task", duradelRows.get(0));
		assertTrue(duradelRows.toString(), duradelRows.contains("Profit (GE) | ~+785K"));
		assertEquals("1 completed task", onEdt(() -> harness.ui().getOverallTotals().getTaskCountTooltip(0)));
		List<String> konar = Arrays.asList(rows().get(1).split("\n"));
		assertEquals("Konar | 3 tasks", konar.get(0));
		assertTrue(konar.toString(), konar.contains("Profit (GE) | 2 tasks | ~+764K"));
		assertTrue(konar.toString(), konar.contains("Points | 2 tasks | 30"));
		assertTrue(konar.toString(), konar.contains("Slayer XP | 2 tasks | 48K"));
		String tooltip = tooltips().get(1);
		assertTrue(tooltip, tooltip.contains("Points from 2 tasks with recorded points"));
		assertTrue(tooltip, tooltip.contains("Profit from 2 tasks with loot and supply values, Slayer XP from 2 tasks"));

		// Labels in the label colour, values in the value colour, a profit signed and in the good colour (a loss in
		// the alert colour: everyBlockOfAGroupingHasTheSameRows); the title in the heading colour.
		List<List<Color>> colors = onEdt(() -> harness.ui().getOverallTotals().getLineColors(0));
		assertEquals(Arrays.asList(LedgerTheme.HEADING, LedgerTheme.LABEL), colors.get(0));
		assertEquals(Arrays.asList(LedgerTheme.LABEL, LedgerTheme.VALUE), colors.get(1));
		assertEquals(Arrays.asList(LedgerTheme.LABEL, LedgerTheme.GOOD), colors.get(3));
		assertEquals("a total from some tasks: its tasks in the hint colour",
			Arrays.asList(LedgerTheme.LABEL, LedgerTheme.HINT, LedgerTheme.GOOD),
			onEdt(() -> harness.ui().getOverallTotals().getLineColors(1)).get(3));
	}

	@Test
	public void everyBlockOfAGroupingHasTheSameRows()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		// Groups with every figure, with none and with a loss, so no row comes or goes with the data.
		LedgerRecord full = valued(monster("Dust devils", 200, 50 * 60_000L), 412_000L, 30_000L, 24_000L, 15);
		LedgerRecord bare = monster("Gargoyles", 150, 40 * 60_000L);
		bare.setSlayerMaster(5);
		LedgerRecord loss = valued(monster("Lava strykewyrms", 90, 38 * 60_000L), 40_000L, 180_000L, 13_500L, 12);
		loss.setSlayerMaster(null);
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT,
			Arrays.asList(full, bare, loss)));
		harness.onEdt(() -> harness.ui().getOverallTotals().clickTitle());

		Map<OverallTotals.Grouping, List<String>> expected = new HashMap<>();
		expected.put(OverallTotals.Grouping.ALL, Arrays.asList("Active time", "Profit (GE)", "XP (all skills)"));
		expected.put(OverallTotals.Grouping.MONTH, expected.get(OverallTotals.Grouping.ALL));
		expected.put(OverallTotals.Grouping.MASTER, Arrays.asList("Avg task", "Avg profit (GE)", "Profit (GE)",
			"Profit (GE)/h", "Points", "Avg points", "Slayer XP", "Avg Slayer XP"));
		expected.put(OverallTotals.Grouping.MONSTER, Arrays.asList("Kills", "Avg task", "Avg profit (GE)",
			"Profit (GE)/kill", "Pace", "Profit (GE)/h", "Slayer XP", "Slayer XP/h"));
		for (OverallTotals.Grouping grouping : OverallTotals.Grouping.values())
		{
			harness.onEdt(() -> harness.ui().getOverallTotals().setGrouping(grouping));
			harness.drain();
			List<List<String>> labels = onEdt(() -> harness.ui().getOverallTotals().getRowLabels());
			// One month: the tasks share an end date.
			boolean oneGroup = grouping == OverallTotals.Grouping.ALL || grouping == OverallTotals.Grouping.MONTH;
			assertEquals(grouping.toString(), oneGroup ? 1 : 3, labels.size());
			for (List<String> block : labels)
			{
				assertEquals(grouping + ": " + labels, expected.get(grouping), block);
			}

			// The refresh timer rebuilds no grouping's blocks.
			int builds = onEdt(() -> harness.ui().getOverallTotals().getBuildCount());
			harness.onEdt(() ->
			{
				harness.panel().refresh();
				harness.panel().refresh();
			});
			harness.drain();
			assertEquals(grouping.toString(), builds,
				(int) onEdt(() -> harness.ui().getOverallTotals().getBuildCount()));
		}

		// The loss is signed and in the alert colour (DESIGN_PRINCIPLES.md 4.3); Unknown master is listed last.
		harness.onEdt(() -> harness.ui().getOverallTotals().setGrouping(OverallTotals.Grouping.MASTER));
		List<String> unknown = Arrays.asList(rows().get(2).split("\n"));
		assertEquals("Unknown master | 1 task", unknown.get(0));
		assertTrue(unknown.toString(), unknown.contains("Profit (GE) | ~-140K"));
		assertEquals(Arrays.asList(LedgerTheme.LABEL, LedgerTheme.ALERT),
			onEdt(() -> harness.ui().getOverallTotals().getLineColors(2)).get(3));

		// The Loot price setting names the profit rows in every block alike.
		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		harness.drain();
		for (List<String> block : onEdt(() -> harness.ui().getOverallTotals().getRowLabels()))
		{
			assertEquals(Arrays.asList("Avg task", "Avg profit (HA)", "Profit (HA)", "Profit (HA)/h", "Points",
				"Avg points", "Slayer XP", "Avg Slayer XP"), block);
		}
	}

	@Test
	public void noCompletedTaskIsOnePlainLine()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		harness.onEdt(() -> harness.ui().getOverallTotals().clickTitle());
		for (OverallTotals.Grouping grouping : OverallTotals.Grouping.values())
		{
			harness.onEdt(() -> harness.ui().getOverallTotals().setGrouping(grouping));
			assertEquals(grouping.toString(), Collections.singletonList(OverallTotalsSection.EMPTY_TEXT), rows());
		}
		assertEquals("No completed tasks yet.", OverallTotalsSection.EMPTY_TEXT);

		// A partial task is no completed task.
		LedgerRecord partial = monster("Dust devils", 50, 10 * 60_000L);
		partial.setCompleted(false);
		partial.setPartial(true);
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT,
			Collections.singletonList(partial)));
		assertEquals(Collections.singletonList(OverallTotalsSection.EMPTY_TEXT), rows());
	}

	@Test
	public void longNamesAndAmountsFitTheSidebarAt225Px()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		String longName = "Lava strykewyrms in the Wilderness Slayer Cave";
		// A large approximate loss and long times, the widest values the rows show.
		LedgerRecord wide = valued(monster(longName, 1, 99 * 3_600_000L), 10_000L, 1_250_000L, 1_234_567L, 1_234);
		wide.setLootUnpricedItems(1);
		wide.setSlayerMaster(12);
		LedgerRecord dustDevils = valued(monster("Dust devils", 200, 50 * 60_000L), 412_000L, 30_000L, 24_000L, 15);
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT, Arrays.asList(wide, dustDevils)));
		harness.onEdt(() -> harness.ui().getOverallTotals().clickTitle());

		for (OverallTotals.Grouping grouping : OverallTotals.Grouping.values())
		{
			harness.onEdt(() -> harness.ui().getOverallTotals().setGrouping(grouping));
			harness.drain();
			int width = onEdt(() -> harness.ui().getOverallTotals().layOut());
			assertTrue(grouping + ": list " + width, width > 0 && width <= PluginPanel.PANEL_WIDTH);
			assertTrue(grouping.toString(),
				onEdt(() -> harness.ui().getOverallTotals().getListPreferredWidth()) <= PluginPanel.PANEL_WIDTH);
			assertEquals(grouping.toString(), Collections.emptyList(),
				onEdt(() -> harness.ui().getOverallTotals().getLinesThatDontFit()));
		}

		// The long monster name is cut short with "…"; its block's tooltip has it whole.
		harness.onEdt(() -> harness.ui().getOverallTotals().setGrouping(OverallTotals.Grouping.MONSTER));
		harness.drain();
		harness.onEdt(() -> harness.ui().getOverallTotals().layOut());
		List<String> names = onEdt(() -> harness.ui().getOverallTotals().getNamesShown());
		assertTrue(names.toString(), names.contains("Dust devils"));
		String cut = names.get(names.indexOf("Dust devils") == 0 ? 1 : 0);
		assertTrue(cut, cut.endsWith(FitLabel.ELLIPSIS) && longName.startsWith(cut.substring(0, cut.length() - 1)));
		assertTrue(tooltips().toString(), tooltips().stream().anyMatch(t -> t.startsWith("<html>" + longName + "<br>")));
		List<String> wideRows = Arrays.asList(rows().get(names.indexOf(cut)).split("\n"));
		assertTrue(wideRows.toString(), wideRows.contains("Avg profit (GE) | ~-1.24M"));
		assertTrue(wideRows.toString(), wideRows.contains("Avg task | 99:00:00"));
	}

	/** {@code record} with loot and supply values, Slayer XP and points. */
	private static LedgerRecord valued(LedgerRecord record, long loot, long supplies, long slayerXp, int points)
	{
		record.setLootGeValue(loot);
		record.setLootHaValue(loot);
		record.setSuppliesGeValue(supplies);
		record.setXpGained(Collections.singletonMap("SLAYER", slayerXp));
		record.setPointsEarned(points);
		return record;
	}

	private static List<String> concat(List<String> a, List<String> b)
	{
		List<String> all = new ArrayList<>(a);
		all.addAll(b);
		return all;
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
