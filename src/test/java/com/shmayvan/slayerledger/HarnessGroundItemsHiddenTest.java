package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.chat.QueuedMessage;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Ground Items' hidden list as wired in the plugin: the items it hides are left out of every loot grid (live,
 * a saved task's details, Overall totals) and out of the loot value and profit everywhere (live values, history rows,
 * Overall totals, the task-complete line), read only from Ground Items' config. A change of the
 * list, of Ground Items' on/off state or of the "Hide Ground Items' hidden items" setting applies at once, and the
 * records are never changed, so the values come back. {@link GroundItemsHiddenTest} covers the matching and
 * {@link HiddenLootValuationTest} the values.
 */
public class HarnessGroundItemsHiddenTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int TASK = 42;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.RUNE_PLATEBODY, "Rune platebody", 38_000, 39_000);
		harness.defineItem(ItemID.BONES, "Bones", 100, 1);
		harness.defineNotedItem(ItemID.Cert.BONES, ItemID.BONES);
		harness.defineItem(ItemID.NATURERUNE, "Nature rune", 90, 108);
	}

	private <T> T onEdt(Supplier<T> read)
	{
		AtomicReference<T> value = new AtomicReference<>();
		harness.onEdt(() -> value.set(read.get()));
		return value.get();
	}

	private String liveLootLine()
	{
		return onEdt(() ->
		{
			harness.panel().refresh();
			return harness.ui().getValuesText();
		}).split(" \\| ")[0];
	}

	/** The live loot's value as {@link PanelProbe#getValuesText} reads it from the loot grid's sub-header. */
	private static String lootLine(long value)
	{
		return "Loot: " + LedgerFormat.amount(value);
	}

	/** Starts a session with a platebody, 3 bones and 2 noted bones looted; the live loot grid is always open. */
	private void startLooting()
	{
		harness.startUp();
		harness.setTask(TASK, "Abyssal demons", 10, 10);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		harness.postLoot(ItemID.RUNE_PLATEBODY, 1);
		harness.postLoot(ItemID.BONES, 3);
		harness.postLoot(ItemID.Cert.BONES, 2);
		harness.ticks(2);
	}

	@Test
	public void liveLootLeavesHiddenItemsOutOfTheGridAndTheValue()
	{
		harness.setGroundItemsHidden("bones");
		startLooting();

		assertEquals("noted bones match as bones", lootLine(38_000), liveLootLine());
		assertEquals("Loot (2 hidden) | 38K", onEdt(() -> harness.ui().getLiveLoot().getHeaderText()));
		assertEquals(Collections.singletonList("Rune platebody | " + ItemID.RUNE_PLATEBODY + " | (alch)"),
			onEdt(() -> harness.ui().getLiveLoot().getCellTexts()));
		String tip = onEdt(() -> harness.ui().getLiveLoot().getHeaderTooltip());
		assertTrue(tip, tip.contains("2 items are hidden by your Ground Items<br>hidden list"));
		assertTrue(tip, tip.contains("hidden items<br>are left out of loot value, except coins."));

		// The setting turned off: everything counts again, at once (on the next tick).
		harness.setHideGroundItemsHidden(false);
		harness.tick();
		assertEquals(lootLine(38_500), liveLootLine());
		assertEquals("Loot | 38.5K", onEdt(() -> harness.ui().getLiveLoot().getHeaderText()));

		// On again, and the list edited in Ground Items: applied without a restart.
		harness.setHideGroundItemsHidden(true);
		harness.setGroundItemsHidden("Rune*");
		harness.tick();
		assertEquals(lootLine(500), liveLootLine());
		assertEquals("Loot (1 hidden) | 500", onEdt(() -> harness.ui().getLiveLoot().getHeaderText()));

		// Ground Items turned off: its list no longer applies.
		harness.setGroundItemsConfig(GroundItemsHidden.PLUGIN_ENABLED_GROUP, GroundItemsHidden.PLUGIN_ENABLED_KEY,
			"false");
		harness.tick();
		assertEquals(lootLine(38_500), liveLootLine());
		harness.setGroundItemsConfig(GroundItemsHidden.PLUGIN_ENABLED_GROUP, GroundItemsHidden.PLUGIN_ENABLED_KEY,
			"true");
		harness.tick();
		assertEquals(lootLine(500), liveLootLine());

		// Never edited: Ground Items' built-in list, which hides Bones.
		harness.setGroundItemsHidden(null);
		harness.tick();
		assertEquals(lootLine(38_000), liveLootLine());
	}

	@Test
	public void onlyGroundItemsOwnSettingsAreRead()
	{
		startLooting();
		// Another Ground Items setting, or another plugin's, changes nothing.
		harness.setGroundItemsConfig(GroundItemsHidden.GROUP, "showHighlightedOnly", "true");
		harness.setGroundItemsConfig("lootTracker", GroundItemsHidden.HIDDEN_ITEMS_KEY, "Bones");
		harness.tick();
		assertEquals(lootLine(38_500), liveLootLine());
	}

	@Test
	public void theTaskCompleteLineLeavesHiddenLootOut()
	{
		harness.setGroundItemsHidden("Bones");
		harness.startUp();
		harness.setTask(TASK, "Abyssal demons", 3, 3);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		harness.setRemaining(1);
		harness.lootOnNextTick(ItemID.RUNE_PLATEBODY, 1);
		harness.tick();
		harness.lootOnNextTick(ItemID.BONES, 10);
		harness.tick();
		harness.completeTask();
		harness.tick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);

		ArgumentCaptor<QueuedMessage> message = ArgumentCaptor.forClass(QueuedMessage.class);
		verify(harness.chatMessageManager, times(1)).queue(message.capture());
		String line = message.getValue().getRuneLiteFormattedMessage();
		assertTrue(line, line.endsWith(", " + LedgerFormat.signed(38_000L, false) + ". Gz."));
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals("the saved loot keeps the bones", Long.valueOf(10), record.getLoot().get(ItemID.BONES));
		assertEquals("and its saved value", Long.valueOf(39_000), record.getLootGeValue());
	}

	private static String profitLine(long profit)
	{
		return "Profit (GE) | " + LedgerFormat.signed(profit, false);
	}

	/** A completed hour-long task: a platebody (30,000 saved) and 10 bones (50 saved), 600 gp of supplies. */
	private static LedgerRecord record()
	{
		LedgerRecord r = new LedgerRecord();
		r.setId("a");
		r.setMonster("Abyssal demons");
		r.setCreatureId(TASK);
		r.setInitialAmount(10);
		r.setKills(10);
		r.setActiveMillis(3_600_000L);
		r.setStartedAt(1_000L);
		r.setEndedAt(3_601_000L);
		r.setCompleted(true);
		r.setPartial(false);
		Map<Integer, Long> loot = new HashMap<>();
		loot.put(ItemID.RUNE_PLATEBODY, 1L);
		loot.put(ItemID.BONES, 10L);
		r.setLoot(loot);
		Map<Integer, Long> ge = new HashMap<>();
		ge.put(ItemID.RUNE_PLATEBODY, 30_000L);
		ge.put(ItemID.BONES, 50L);
		r.setUnitGePrices(ge);
		Map<Integer, Long> ha = new HashMap<>();
		ha.put(ItemID.RUNE_PLATEBODY, 39_000L);
		ha.put(ItemID.BONES, 1L);
		r.setUnitHaPrices(ha);
		r.setLootGeValue(30_500L);
		r.setLootHaValue(39_010L);
		r.setSuppliesGeValue(600L);
		r.setSuppliesHaValue(0L);
		return r;
	}

	/**
	 * The history row's profit, the details' loot grid (its sub-header with the loot value, and its icons), the totals'
	 * profit line and loot grid.
	 */
	private List<String> shown()
	{
		harness.drain();
		harness.onEdt(() -> harness.ui().getOverallTotals().getRows());
		harness.drain();
		return onEdt(() ->
		{
			PanelProbe ui = harness.ui();
			String row = ui.getHistory().getValuesText().get(0).split(" \\| ")[0];
			PanelProbe.ItemGridProbe details = ui.getHistory().getDetailsGrid(0, "Loot received");
			String profit = ui.getOverallTotals().getRows().get(0).split("\n")[2];
			return Arrays.asList(row, details.getHeaderText() + " " + details.getCellTexts(), profit,
				ui.getOverallTotals().getLoot(0).getHeaderText());
		});
	}

	@Test
	public void historyAndTotalsLeaveHiddenLootOutAndFollowTheList()
	{
		harness.setGroundItemsHidden("Bones");
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		LedgerRecord record = record();
		harness.onEdt(() ->
		{
			harness.historyModel().setRecords(ACCOUNT, Collections.singletonList(record));
			harness.ui().getOverallTotals().clickTitle();
		});
		harness.drain();
		harness.onEdt(() -> harness.ui().getHistory().clickRow(0));

		// Valued at today's prices: the platebody at 38,000 gp, the bones at 100 gp.
		Map<Integer, Long> today = new HashMap<>();
		today.put(ItemID.RUNE_PLATEBODY, 38_000L);
		today.put(ItemID.BONES, 100L);
		HistoryPricing.Values hiddenValues = HistoryPricing.value(record, LootPriceMode.GE, today,
			HiddenLoot.of(Collections.singleton(ItemID.BONES)));
		HistoryPricing.Values allValues = HistoryPricing.value(record, LootPriceMode.GE, today);
		// The loot value, as the details' Loot received sub-header shows it.
		assertEquals("38K", LedgerFormat.amount(hiddenValues.getLootValue(), hiddenValues.isLootApproximate()));
		assertEquals("39K", LedgerFormat.amount(allValues.getLootValue(), allValues.isLootApproximate()));
		// The row shows the profit: here the loot, as no supply item was kept to value today.
		String hiddenLoot = LedgerFormat.signed(hiddenValues.getProfit(), false);
		String allLoot = LedgerFormat.signed(allValues.getProfit(), false);
		assertEquals("+38K", hiddenLoot);
		assertEquals("+39K", allLoot);
		// Names arrive from the client thread; the bones are then matched and left out everywhere.
		assertEquals(Arrays.asList(hiddenLoot,
			"Loot received (1 hidden) | 38K [Rune platebody | " + ItemID.RUNE_PLATEBODY + " | (alch)]",
			profitLine(38_000), "Loot received (1 hidden) | Show"), shown());
		String tip = onEdt(() -> harness.ui().getHistory().getDetailsGrid(0, "Loot received").getHeaderTooltip());
		assertTrue(tip, tip.startsWith("<html>1 item is hidden by your Ground Items"));

		int[] builds = new int[2];
		harness.onEdt(() -> builds[0] = harness.ui().getOverallTotals().getBuildCount());
		long valuation = onEdt(() -> harness.historyModel().valuationVersion());

		// The list edited in Ground Items: every view is valued again, nothing cached is served.
		harness.setGroundItemsHidden("");
		assertEquals(Arrays.asList(allLoot,
			"Loot received | 39K [Rune platebody | " + ItemID.RUNE_PLATEBODY + " | (alch), Bones | " + ItemID.BONES + " x10]",
			profitLine(39_000), "Loot received | Show"), shown());
		harness.onEdt(() -> builds[1] = harness.ui().getOverallTotals().getBuildCount());
		assertTrue("the totals were worked out again", builds[1] > builds[0]);
		assertTrue(onEdt(() -> harness.historyModel().valuationVersion()) > valuation);

		// Hidden again, then the setting turned off: the values come back, the record unchanged.
		harness.setGroundItemsHidden("*bones*");
		assertEquals(hiddenLoot, shown().get(0));
		harness.setHideGroundItemsHidden(false);
		assertEquals(allLoot, shown().get(0));
		assertEquals(profitLine(39_000), shown().get(2));
		assertEquals(Long.valueOf(10), record.getLoot().get(ItemID.BONES));

		// The refresh timer works nothing out again.
		harness.onEdt(() ->
		{
			builds[0] = harness.ui().getOverallTotals().getBuildCount();
			harness.panel().refresh();
			harness.panel().refresh();
		});
		harness.drain();
		harness.onEdt(() -> builds[1] = harness.ui().getOverallTotals().getBuildCount());
		assertEquals(builds[0], builds[1]);
		assertNotNull(record.getLoot());
	}
}
