package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.Rule;
import org.junit.Test;

/**
 * The "Panel sections" settings as wired in the plugin ({@link PluginHarness}): a hidden section takes no space,
 * builds nothing and asks for no item names; turned back on, it is rebuilt from the current data at once. Also the
 * sidebar's sections and their order since the advisor card replaced the History estimate and What to bring.
 */
public class HarnessPanelSectionsTest
{
	@Rule
	public final PluginHarness harness = new PluginHarness();

	private <T> T onEdt(Supplier<T> read)
	{
		List<T> result = new ArrayList<>(1);
		harness.onEdt(() -> result.add(read.get()));
		return result.get(0);
	}

	private boolean shown(SlayerLedgerPanel.OptionalSection section)
	{
		return onEdt(() -> harness.ui().isSectionShown(section));
	}

	private int panelHeight()
	{
		return onEdt(() -> harness.panel().getPreferredSize().height);
	}

	@Test
	public void everySectionIsShownByDefaultAndAHiddenOneLeavesNoGap()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		Map<SlayerLedgerPanel.OptionalSection, String> keys = new HashMap<>();
		keys.put(SlayerLedgerPanel.OptionalSection.OVERALL_TOTALS, SlayerLedgerConfig.SHOW_OVERALL_TOTALS);
		keys.put(SlayerLedgerPanel.OptionalSection.SKIPPED_BLOCKED, SlayerLedgerConfig.SHOW_SKIPPED_BLOCKED);
		for (SlayerLedgerPanel.OptionalSection section : SlayerLedgerPanel.OptionalSection.values())
		{
			assertTrue(section.name(), shown(section));
			int before = panelHeight();
			int header = onEdt(() -> harness.ui().getSectionHeaderHeight(section));
			harness.setPanelSection(keys.get(section), false);
			assertFalse(section.name(), shown(section));
			// A collapsed section is its header with a 6 px gap above it and above its hidden body. Hiding it takes
			// all of it away.
			assertEquals(section.name(), before - (header + 12), panelHeight());
			harness.setPanelSection(keys.get(section), true);
			assertTrue(section.name(), shown(section));
			assertEquals(section.name(), before, panelHeight());
		}
	}

	@Test
	public void hiddenTotalsAndSkippedBlockedBuildNothingAndAreRebuiltWhenShown()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		LedgerRecord demons = completed("Abyssal demons", 42, 100, 10 * 60_000L);
		demons.setLootGeValue(100_000L);
		demons.setSuppliesGeValue(10_000L);
		AssignmentEntry skip = new AssignmentEntry();
		skip.setId("a1");
		skip.setMonster("Kurasks");
		skip.setEndedAt(1_000L);
		skip.setOutcome(AssignmentOutcome.SKIPPED);
		harness.onEdt(() ->
		{
			PanelProbe ui = harness.ui();
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, Collections.singletonList(demons));
			harness.historyModel().setAssignments(PluginHarness.ACCOUNT, Collections.singletonList(skip));
			ui.getOverallTotals().toggle();
			ui.getAssignments().toggle();
		});
		// The totals are worked out off the EDT.
		harness.drain();
		List<String> totalsRows = onEdt(() -> harness.ui().getOverallTotals().getRows());
		List<String> assignmentRows = onEdt(() -> harness.ui().getAssignments().getRows());
		assertEquals(1, totalsRows.size());
		assertEquals(1, assignmentRows.size());

		Function<PanelProbe, int[]> builds = ui -> new int[]{ui.getOverallTotals().getBuildCount(),
			ui.getAssignments().getBuildCount()};
		harness.setPanelSection(SlayerLedgerConfig.SHOW_OVERALL_TOTALS, false);
		harness.setPanelSection(SlayerLedgerConfig.SHOW_SKIPPED_BLOCKED, false);
		int[] hidden = onEdt(() -> builds.apply(harness.ui()));
		assertTrue(onEdt(() -> harness.ui().getOverallTotals().getRows()).isEmpty());
		assertTrue(onEdt(() -> harness.ui().getAssignments().getRows()).isEmpty());

		// New records and assignments, the Loot price mode, today's prices and the refresh timer build nothing while
		// hidden.
		LedgerRecord dagannoth = completed("Dagannoth", 120, 150, 20 * 60_000L);
		dagannoth.setLootGeValue(50_000L);
		dagannoth.setSuppliesGeValue(5_000L);
		AssignmentEntry block = new AssignmentEntry();
		block.setId("a2");
		block.setMonster("Gargoyles");
		block.setEndedAt(2_000L);
		block.setOutcome(AssignmentOutcome.BLOCKED);
		harness.onEdt(() ->
		{
			SlayerLedgerPanel panel = harness.panel();
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, Arrays.asList(dagannoth, demons));
			harness.historyModel().setAssignments(PluginHarness.ACCOUNT, Arrays.asList(block, skip));
			panel.refresh();
		});
		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		harness.setLootPriceMode(LootPriceMode.GE);
		harness.onEdt(() -> harness.historyModel().addCurrentPrices(Collections.singleton(ItemValuation.COINS),
			Collections.singletonMap(ItemValuation.COINS, 1L)));
		harness.drain();
		assertTrue(Arrays.equals(hidden, onEdt(() -> builds.apply(harness.ui()))));
		assertTrue(onEdt(() -> harness.ui().getOverallTotals().getRows()).isEmpty());
		assertTrue(onEdt(() -> harness.ui().getAssignments().getRows()).isEmpty());

		// Shown again: open as before and rebuilt once, at once, from the current data.
		harness.setPanelSection(SlayerLedgerConfig.SHOW_OVERALL_TOTALS, true);
		harness.setPanelSection(SlayerLedgerConfig.SHOW_SKIPPED_BLOCKED, true);
		int[] reshown = onEdt(() -> builds.apply(harness.ui()));
		for (int i = 0; i < hidden.length; i++)
		{
			assertEquals("section " + i, hidden[i] + 1, reshown[i]);
		}
		// The rows come from the data set while hidden, not from before the hide.
		assertFalse(totalsRows.equals(onEdt(() -> harness.ui().getOverallTotals().getRows())));
		List<String> reshownAssignments = onEdt(() -> harness.ui().getAssignments().getRows());
		assertEquals(2, reshownAssignments.size());
		assertTrue(reshownAssignments.containsAll(assignmentRows));
		assertTrue(onEdt(() -> harness.ui().getOverallTotals().isExpanded()));
		assertTrue(onEdt(() -> harness.ui().getAssignments().isExpanded()));
	}

	@Test
	public void theAdvisorCardReplacedTheHistoryEstimateAndWhatToBring()
	{
		// A task with history, so the card shows: the 1.x History estimate and What to bring would have too.
		harness.startUp();
		harness.setTask(42, "Abyssal demons", 10, 10);
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		LedgerRecord demons = completed("Abyssal demons", 42, 10, 10 * 60_000L);
		demons.setLootGeValue(100_000L);
		demons.setSuppliesGeValue(0L);
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT,
			Collections.singletonList(demons)));
		harness.drain();
		harness.onEdt(() -> harness.panel().refresh());
		assertTrue(onEdt(() -> harness.ui().getAdvisor().isShown()));

		// Their parts and the What to bring block are gone, and only two sections have a "Panel sections" setting.
		for (String part : new String[]{"estimate", "estimate.title", "bringBlock", "bring", "bring.header"})
		{
			assertFalse(part, onEdt(() -> harness.ui().isInPanel(part)));
		}
		assertEquals(Arrays.asList(SlayerLedgerPanel.OptionalSection.OVERALL_TOTALS,
			SlayerLedgerPanel.OptionalSection.SKIPPED_BLOCKED),
			Arrays.asList(SlayerLedgerPanel.OptionalSection.values()));

		// The sidebar's order (V2_PLAN.md 8): the status strip, the live task, the advisor card, History (the saved
		// count and the task history), Overall totals, Skipped / blocked.
		List<String> order = onEdt(() -> harness.ui().getContentOrder());
		List<String> sections = Arrays.asList("session", "liveCard", "advisor", "history.header", "totalsBlock",
			"assignmentsBlock");
		int previous = -1;
		for (String section : sections)
		{
			int index = order.indexOf(section);
			assertTrue(section + " in " + order, index > previous);
			previous = index;
		}
		// Nothing else between the advisor card and History but the saved-task count.
		assertEquals(order.toString(), order.indexOf("advisor") + 1, order.indexOf("history.header"));
	}

	private static LedgerRecord completed(String monster, int creatureId, int kills, long activeMillis)
	{
		LedgerRecord record = new LedgerRecord();
		record.setId(monster + kills);
		record.setCreatureId(creatureId);
		record.setMonster(monster);
		record.setKills(kills);
		record.setActiveMillis(activeMillis);
		record.setEndedAt(1_700_000_000_000L);
		record.setCompleted(true);
		record.setPartial(false);
		return record;
	}
}
