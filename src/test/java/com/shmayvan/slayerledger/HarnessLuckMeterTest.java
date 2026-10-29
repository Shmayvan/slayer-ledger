package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;

public class HarnessLuckMeterTest
{
	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Test
	public void meterStartsCollapsedAndRefreshesAfterItemNamesAreLookedUp()
	{
		harness.defineItem(1001, "Abyssal whip", 1_500_000, 0);
		harness.defineItem(1002, "Abyssal dagger", 20_000_000, 0);
		harness.defineItem(1003, "Granite maul", 200_000, 0);
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);

		Map<Integer, Long> loot = new HashMap<>();
		loot.put(1001, 2L);
		loot.put(1002, 1L);
		LedgerRecord demons = record("Abyssal demons", 512, loot);
		LedgerRecord gargoyles = record("Gargoyles", 256, Collections.singletonMap(1003, 1L));
		// Saved before loot tracking: its kills must not count.
		LedgerRecord old = record("Abyssal demons", 5_000, null);
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT,
			Arrays.asList(demons, gargoyles, old)));

		boolean[] expanded = new boolean[1];
		harness.onEdt(() -> expanded[0] = harness.ui().getLuckMeter().isExpanded());
		assertFalse(expanded[0]);

		harness.onEdt(() -> harness.ui().getLuckMeter().clickTitle());
		harness.drain();
		List<String> rows = rows();
		assertEquals(Arrays.asList(
			"Abyssal whip | 2.0x\nObserved / expected: | 2 / 1.00",
			"Abyssal dagger | too few kills\nObserved / expected: | 1 / 0.02"), rows);
		assertEquals("1 task / 512 kills with loot data", summary());
		List<String> tooltips = tooltips();
		assertTrue(tooltips.get(1), tooltips.get(1).contains("Chance of at least 1: 1.6% (Poisson model)"));
		assertTrue(tooltips.get(1), tooltips.get(1).contains("Too few kills for a ratio"));
		assertTrue(tooltips.get(0), tooltips.get(0).contains("Observed / expected: 2.00x"));

		harness.onEdt(() -> harness.ui().getLuckMeter().setTask("Gargoyles"));
		harness.drain();
		rows = rows();
		assertEquals(2, rows.size());
		assertEquals("Granite maul | 1.0x\nObserved / expected: | 1 / 1.00", rows.get(0));
	}

	@Test
	public void namesAreNotEscapedAndFailedNamesAreNoted()
	{
		harness.defineItem(1001, "Abyssal whip", 1_500_000, 0);
		// A "null" name is a failed lookup, as in the game.
		harness.defineItem(1009, "null", 0, 0);
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		Map<Integer, Long> loot = new HashMap<>();
		loot.put(1001, 1L);
		loot.put(1009, 1L);
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT,
			Collections.singletonList(record("Abyssal demons", 1_024, loot))));
		harness.onEdt(() -> harness.ui().getLuckMeter().clickTitle());
		harness.drain();
		List<String> rows = rows();
		assertEquals(3, rows.size());
		assertEquals("Abyssal whip | 0.5x\nObserved / expected: | 1 / 2.00", rows.get(0));
		assertEquals("Some item names unavailable.", rows.get(2));
		assertTrue(tooltips().get(0).contains("observed counts may be incomplete"));
	}

	@Test
	public void nothingIsBuiltWhileCollapsedOrByTheRefreshTimerOrPrices()
	{
		harness.defineItem(1001, "Abyssal whip", 1_500_000, 0);
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		LedgerRecord record = record("Abyssal demons", 512, Collections.singletonMap(1001, 1L));
		int[] builds = new int[2];
		harness.onEdt(() ->
		{
			SlayerLedgerPanel panel = harness.panel();
			PanelProbe ui = harness.ui();
			builds[0] = ui.getLuckMeter().getBuildCount();
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, Collections.singletonList(record));
			panel.refresh();
			builds[1] = ui.getLuckMeter().getBuildCount();
		});
		assertEquals("collapsed: nothing built", builds[0], builds[1]);
		assertTrue(rows().isEmpty());

		harness.onEdt(() -> harness.ui().getLuckMeter().clickTitle());
		harness.drain();
		assertEquals(2, rows().size());
		harness.onEdt(() ->
		{
			SlayerLedgerPanel panel = harness.panel();
			PanelProbe ui = harness.ui();
			builds[0] = ui.getLuckMeter().getBuildCount();
			panel.refresh();
			panel.refresh();
			builds[1] = ui.getLuckMeter().getBuildCount();
		});
		assertEquals("the refresh timer never rebuilds", builds[0], builds[1]);

		// The meter uses no prices: the Loot price mode and today's prices arriving don't rebuild it.
		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		harness.onEdt(() -> harness.historyModel().addCurrentPrices(Collections.singleton(ItemValuation.COINS),
			Collections.singletonMap(ItemValuation.COINS, 1L)));
		harness.drain();
		harness.onEdt(() -> builds[1] = harness.ui().getLuckMeter().getBuildCount());
		assertEquals("prices never rebuild", builds[0], builds[1]);

		// Collapsing drops the rows; record changes while collapsed build nothing.
		harness.onEdt(() ->
		{
			PanelProbe ui = harness.ui();
			ui.getLuckMeter().clickTitle();
			builds[0] = ui.getLuckMeter().getBuildCount();
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, Collections.singletonList(record));
			builds[1] = ui.getLuckMeter().getBuildCount();
		});
		assertEquals(builds[0], builds[1]);
		assertTrue(rows().isEmpty());
	}

	private List<String> rows()
	{
		Object[] rows = new Object[1];
		harness.onEdt(() -> rows[0] = harness.ui().getLuckMeter().getRows());
		@SuppressWarnings("unchecked")
		List<String> result = (List<String>) rows[0];
		return result;
	}

	private List<String> tooltips()
	{
		Object[] tooltips = new Object[1];
		harness.onEdt(() -> tooltips[0] = harness.ui().getLuckMeter().getTooltips());
		@SuppressWarnings("unchecked")
		List<String> result = (List<String>) tooltips[0];
		return result;
	}

	private String summary()
	{
		String[] summary = new String[1];
		harness.onEdt(() -> summary[0] = harness.ui().getLuckMeter().getSummary());
		return summary[0];
	}

	private static LedgerRecord record(String monster, int kills, Map<Integer, Long> loot)
	{
		LedgerRecord record = new LedgerRecord();
		record.setMonster(monster);
		record.setKills(kills);
		record.setCompleted(true);
		record.setPartial(false);
		record.setLoot(loot);
		return record;
	}
}
