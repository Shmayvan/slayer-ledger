package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
 * builds nothing and asks for no item names; turned back on, it is rebuilt from the current data at once.
 */
public class HarnessPanelSectionsTest
{
	@Rule
	public final PluginHarness harness = new PluginHarness();

	private static final int WHIP = 1001;

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
		keys.put(SlayerLedgerPanel.OptionalSection.WHAT_TO_BRING, SlayerLedgerConfig.SHOW_WHAT_TO_BRING);
		keys.put(SlayerLedgerPanel.OptionalSection.TRENDS, SlayerLedgerConfig.SHOW_TRENDS);
		keys.put(SlayerLedgerPanel.OptionalSection.OVERALL_TOTALS, SlayerLedgerConfig.SHOW_OVERALL_TOTALS);
		keys.put(SlayerLedgerPanel.OptionalSection.LUCK_METER, SlayerLedgerConfig.SHOW_LUCK_METER);
		keys.put(SlayerLedgerPanel.OptionalSection.SKIPPED_BLOCKED, SlayerLedgerConfig.SHOW_SKIPPED_BLOCKED);
		for (SlayerLedgerPanel.OptionalSection section : SlayerLedgerPanel.OptionalSection.values())
		{
			assertTrue(section.name(), shown(section));
			int before = panelHeight();
			int header = onEdt(() -> harness.ui().getSectionHeaderHeight(section));
			harness.setPanelSection(keys.get(section), false);
			assertFalse(section.name(), shown(section));
			// A collapsed section is its header with a 6 px gap above it and above its hidden body; What to bring is
			// hidden here (no task), so only its gap is left. Hiding either takes all of it away.
			int expected = section == SlayerLedgerPanel.OptionalSection.WHAT_TO_BRING ? 6 : header + 12;
			assertEquals(section.name(), before - expected, panelHeight());
			harness.setPanelSection(keys.get(section), true);
			assertTrue(section.name(), shown(section));
			assertEquals(section.name(), before, panelHeight());
		}
	}

	@Test
	public void hiddenTrendsTotalsAndSkippedBlockedBuildNothingAndAreRebuiltWhenShown()
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
			ui.getTrends().toggle();
			ui.getOverallTotals().toggle();
			ui.getAssignments().toggle();
		});
		// Trends and the totals are worked out off the EDT.
		harness.drain();
		List<String> trendRows = onEdt(() -> harness.ui().getTrends().getRows());
		List<String> totalsRows = onEdt(() -> harness.ui().getOverallTotals().getRows());
		List<String> assignmentRows = onEdt(() -> harness.ui().getAssignments().getRows());
		assertEquals(1, trendRows.size());
		assertEquals(1, totalsRows.size());
		assertEquals(1, assignmentRows.size());

		Function<PanelProbe, int[]> builds = ui -> new int[]{ui.getTrends().getBuildCount(),
			ui.getOverallTotals().getBuildCount(), ui.getAssignments().getBuildCount()};
		harness.setPanelSection(SlayerLedgerConfig.SHOW_TRENDS, false);
		harness.setPanelSection(SlayerLedgerConfig.SHOW_OVERALL_TOTALS, false);
		harness.setPanelSection(SlayerLedgerConfig.SHOW_SKIPPED_BLOCKED, false);
		int[] hidden = onEdt(() -> builds.apply(harness.ui()));
		assertTrue(onEdt(() -> harness.ui().getTrends().getRows()).isEmpty());
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
		assertTrue(onEdt(() -> harness.ui().getTrends().getRows()).isEmpty());
		assertTrue(onEdt(() -> harness.ui().getOverallTotals().getRows()).isEmpty());
		assertTrue(onEdt(() -> harness.ui().getAssignments().getRows()).isEmpty());

		// Shown again: open as before and rebuilt once, at once, from the current data.
		harness.setPanelSection(SlayerLedgerConfig.SHOW_TRENDS, true);
		harness.setPanelSection(SlayerLedgerConfig.SHOW_OVERALL_TOTALS, true);
		harness.setPanelSection(SlayerLedgerConfig.SHOW_SKIPPED_BLOCKED, true);
		int[] reshown = onEdt(() -> builds.apply(harness.ui()));
		for (int i = 0; i < hidden.length; i++)
		{
			assertEquals("section " + i, hidden[i] + 1, reshown[i]);
		}
		// The rows come from the data set while hidden, not from before the hide.
		assertEquals(2, onEdt(() -> harness.ui().getTrends().getRows()).size());
		assertFalse(totalsRows.equals(onEdt(() -> harness.ui().getOverallTotals().getRows())));
		List<String> reshownAssignments = onEdt(() -> harness.ui().getAssignments().getRows());
		assertEquals(2, reshownAssignments.size());
		assertTrue(reshownAssignments.containsAll(assignmentRows));
		assertTrue(onEdt(() -> harness.ui().getTrends().isExpanded()));
		assertTrue(onEdt(() -> harness.ui().getOverallTotals().isExpanded()));
		assertTrue(onEdt(() -> harness.ui().getAssignments().isExpanded()));
	}

	@Test
	public void aLuckMeterHiddenAtStartUpAsksForNoItemNamesUntilShown()
	{
		harness.defineItem(WHIP, "Abyssal whip", 1_500_000, 0);
		harness.config.showLuckMeter = false;
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		assertFalse("hidden from the start", shown(SlayerLedgerPanel.OptionalSection.LUCK_METER));
		// Opened earlier (the header itself is hidden): still nothing is compared or named.
		harness.onEdt(() -> harness.ui().getLuckMeter().toggle());
		LedgerRecord demons = completed("Abyssal demons", 42, 512, 60 * 60_000L);
		demons.setLoot(Collections.singletonMap(WHIP, 2L));
		int[] builds = new int[1];
		harness.onEdt(() ->
		{
			SlayerLedgerPanel panel = harness.panel();
			PanelProbe ui = harness.ui();
			builds[0] = ui.getLuckMeter().getBuildCount();
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, Collections.singletonList(demons));
			panel.refresh();
		});
		harness.drain();
		assertEquals(builds[0], (int) onEdt(() -> harness.ui().getLuckMeter().getBuildCount()));
		assertTrue(onEdt(() -> harness.ui().getLuckMeter().getRows()).isEmpty());
		// The whip's composition is read for its price (the history is valued at today's prices), but its name
		// is not asked for.
		assertNull("no item name asked for", onEdt(() -> harness.historyModel().itemName(WHIP)));

		harness.setPanelSection(SlayerLedgerConfig.SHOW_LUCK_METER, true);
		assertTrue(shown(SlayerLedgerPanel.OptionalSection.LUCK_METER));
		assertTrue(onEdt(() -> harness.ui().getLuckMeter().isExpanded()));
		assertEquals("Abyssal whip", onEdt(() -> harness.historyModel().itemName(WHIP)));
		assertEquals("Abyssal whip | 2.0x\nObserved / expected: | 2 / 1.00",
			onEdt(() -> harness.ui().getLuckMeter().getRows()).get(0));

		// Hidden again: the rows go, and later records and item names build nothing.
		harness.setPanelSection(SlayerLedgerConfig.SHOW_LUCK_METER, false);
		assertTrue(onEdt(() -> harness.ui().getLuckMeter().getRows()).isEmpty());
		harness.onEdt(() ->
		{
			PanelProbe ui = harness.ui();
			builds[0] = ui.getLuckMeter().getBuildCount();
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, Collections.singletonList(demons));
			harness.historyModel().addItemNames(Collections.singleton(WHIP),
				Collections.singletonMap(WHIP, "Abyssal whip"), null);
		});
		assertEquals(builds[0], (int) onEdt(() -> harness.ui().getLuckMeter().getBuildCount()));
		assertTrue(onEdt(() -> harness.ui().getLuckMeter().getRows()).isEmpty());
	}

	@Test
	public void hiddenWhatToBringIsNotWorkedOutAndComesBackWithTheEstimate()
	{
		harness.defineItem(101, "Cannonballs", 200, 0);
		harness.startUp();
		harness.setTask(42, "Abyssal demons", 10, 10);
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		LedgerRecord demons = completed("Abyssal demons", 42, 10, 10_000L);
		demons.setSupplies(Collections.singletonMap(101, 30L));
		demons.setBankTrips(1);
		harness.onEdt(() ->
		{
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, Collections.singletonList(demons));
			harness.ui().getHistoryEstimate().toggleBring();
		});
		harness.drain();
		String header = onEdt(() ->
		{
			harness.panel().refresh();
			return harness.ui().getHistoryEstimate().getBringText();
		});
		List<String> lines = onEdt(() -> harness.ui().getHistoryEstimate().getBringLines());
		assertNotNull(header);
		assertEquals(Collections.singletonList("Cannonballs | x30"), lines);

		harness.setPanelSection(SlayerLedgerConfig.SHOW_WHAT_TO_BRING, false);
		assertFalse(shown(SlayerLedgerPanel.OptionalSection.WHAT_TO_BRING));
		assertNull(onEdt(() -> harness.ui().getHistoryEstimate().getBringText()));
		assertNotNull("the History estimate stays", onEdt(() -> harness.ui().getHistoryEstimate().getText()));
		int builds = onEdt(() -> harness.ui().getHistoryEstimate().getBringBuildCount());
		// Records, the Loot price mode and the refresh timer work nothing of it out while hidden.
		harness.onEdt(() ->
		{
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, Collections.singletonList(demons));
			harness.panel().refresh();
		});
		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		harness.drain();
		assertEquals(builds, (int) onEdt(() -> harness.ui().getHistoryEstimate().getBringBuildCount()));
		assertNull(onEdt(() -> harness.ui().getHistoryEstimate().getBringText()));
		assertTrue(onEdt(() -> harness.ui().getHistoryEstimate().getBringLines()).isEmpty());

		harness.setPanelSection(SlayerLedgerConfig.SHOW_WHAT_TO_BRING, true);
		assertTrue(shown(SlayerLedgerPanel.OptionalSection.WHAT_TO_BRING));
		assertEquals(builds + 1, (int) onEdt(() -> harness.ui().getHistoryEstimate().getBringBuildCount()));
		assertEquals(header, onEdt(() -> harness.ui().getHistoryEstimate().getBringText()));
		assertEquals("still open", lines, onEdt(() -> harness.ui().getHistoryEstimate().getBringLines()));
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
