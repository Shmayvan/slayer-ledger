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
 * Ground Items' hidden list as wired in the plugin: the items it hides are left out of every "Loot received"
 * grid (live, a saved task's details, Overall totals) and out of the loot value and profit everywhere (live values,
 * history rows, Overall totals, Trends, the task-complete line), read only from Ground Items' config. A change of the
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

	private static String lootLine(long value)
	{
		return LedgerHistory.lootLabel(LootPriceMode.GE) + LedgerHistory.formatGp(value);
	}

	/** Starts a session with a platebody, 3 bones and 2 noted bones looted, and opens the live loot grid. */
	private void startLooting()
	{
		harness.startUp();
		harness.setTask(TASK, "Abyssal demons", 10, 10);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		harness.postLoot(ItemID.RUNE_PLATEBODY, 1);
		harness.postLoot(ItemID.BONES, 3);
		harness.postLoot(ItemID.Cert.BONES, 2);
		harness.ticks(2);
		harness.onEdt(() -> harness.ui().getLiveLoot().toggle());
	}

	@Test
	public void liveLootLeavesHiddenItemsOutOfTheGridAndTheValue()
	{
		harness.setGroundItemsHidden("bones");
		startLooting();

		assertEquals("noted bones match as bones", lootLine(38_000), liveLootLine());
		assertEquals("Loot received (1, 2 hidden) | Hide", onEdt(() -> harness.ui().getLiveLoot().getHeaderText()));
		assertEquals(Collections.singletonList("Rune platebody | " + ItemID.RUNE_PLATEBODY + " | (alch)"),
			onEdt(() -> harness.ui().getLiveLoot().getCellTexts()));
		String tip = onEdt(() -> harness.ui().getLiveLoot().getHeaderTooltip());
		assertTrue(tip, tip.contains("2 items are hidden by your Ground Items<br>hidden list"));
		assertTrue(tip, tip.contains("hidden items<br>are left out of loot value, except coins."));

		// The setting turned off: everything counts again, at once (on the next tick).
		harness.setHideGroundItemsHidden(false);
		harness.tick();
		assertEquals(lootLine(38_500), liveLootLine());
		assertEquals("Loot received (3) | Hide", onEdt(() -> harness.ui().getLiveLoot().getHeaderText()));

		// On again, and the list edited in Ground Items: applied without a restart.
		harness.setHideGroundItemsHidden(true);
		harness.setGroundItemsHidden("Rune*");
		harness.tick();
		assertEquals(lootLine(500), liveLootLine());
		assertEquals("Loot received (2, 1 hidden) | Hide", onEdt(() -> harness.ui().getLiveLoot().getHeaderText()));

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
		assertTrue(harness.play());
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
		assertTrue(line, line.endsWith(" · +" + LedgerHistory.formatAmount(38_000L, false)));
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals("the saved loot keeps the bones", Long.valueOf(10), record.getLoot().get(ItemID.BONES));
		assertEquals("and its saved value", Long.valueOf(39_000), record.getLootGeValue());
	}

	private static String profitLine(long profit)
	{
		return "Profit (GE): | " + LedgerHistory.formatAmount(profit, false) + " (n=1)";
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

	/** The history row's loot line, the details' loot grid, the totals' profit line and loot grid, the Trends rate. */
	private List<String> shown()
	{
		harness.drain();
		harness.onEdt(() ->
		{
			harness.historyModel().ranking(TaskRanking.Metric.PROFIT);
			harness.ui().getOverallTotals().getRows();
		});
		harness.drain();
		return onEdt(() ->
		{
			PanelProbe ui = harness.ui();
			String row = ui.getHistory().getValuesText().get(0).split(" \\| ")[0];
			PanelProbe.ItemGridProbe details = ui.getHistory().getDetailsGrid(0, "Loot received");
			String profit = ui.getOverallTotals().getRows().get(0).split("\n")[2];
			List<TaskRanking.Entry> ranking = harness.historyModel().ranking(TaskRanking.Metric.PROFIT);
			return Arrays.asList(row, details.getHeaderText() + " " + details.getCellTexts(), profit,
				ui.getOverallTotals().getLoot(0).getHeaderText(), String.valueOf(ranking.get(0).getRatePerHour()));
		});
	}

	@Test
	public void historyTotalsAndTrendsLeaveHiddenLootOutAndFollowTheList()
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
		String hiddenLoot = LedgerHistory.formatLoot(HistoryPricing.value(record, LootPriceMode.GE, today,
			HiddenLoot.of(Collections.singleton(ItemID.BONES))));
		String allLoot = LedgerHistory.formatLoot(HistoryPricing.value(record, LootPriceMode.GE, today));
		assertEquals("Loot (GE): 38K", hiddenLoot);
		assertEquals("Loot (GE): 39K", allLoot);
		// Names arrive from the client thread; the bones are then matched and left out everywhere.
		assertEquals(Arrays.asList(hiddenLoot,
			"Loot received (1, 1 hidden) [Rune platebody | " + ItemID.RUNE_PLATEBODY + " | (alch)]",
			profitLine(38_000), "Loot received (1, 1 hidden) | Show", "38000"), shown());
		String tip = onEdt(() -> harness.ui().getHistory().getDetailsGrid(0, "Loot received").getHeaderTooltip());
		assertTrue(tip, tip.startsWith("<html>1 item is hidden by your Ground Items"));

		int[] builds = new int[2];
		harness.onEdt(() -> builds[0] = harness.ui().getOverallTotals().getBuildCount());
		long valuation = onEdt(() -> harness.historyModel().valuationVersion());

		// The list edited in Ground Items: every view is valued again, nothing cached is served.
		harness.setGroundItemsHidden("");
		assertEquals(Arrays.asList(allLoot,
			"Loot received (2) [Rune platebody | " + ItemID.RUNE_PLATEBODY + " | (alch), Bones | " + ItemID.BONES + " x10]",
			profitLine(39_000), "Loot received (2) | Show", "39000"), shown());
		harness.onEdt(() -> builds[1] = harness.ui().getOverallTotals().getBuildCount());
		assertTrue("the totals were worked out again", builds[1] > builds[0]);
		assertTrue(onEdt(() -> harness.historyModel().valuationVersion()) > valuation);

		// Hidden again, then the setting turned off: the values come back, the record unchanged.
		harness.setGroundItemsHidden("*bones*");
		assertEquals(hiddenLoot, shown().get(0));
		harness.setHideGroundItemsHidden(false);
		assertEquals(allLoot, shown().get(0));
		assertEquals("39000", shown().get(4));
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

	private static final int SPECTRES = 7;
	private static final String SPECTRE_TASK = "Aberrant spectres";
	private static final String DEVIANT_CANNON = "Deviant spectres (cannon)";

	/**
	 * A completed hour-long Deviant spectres task with a cannon: 10 kills, a platebody (30,000 saved) and
	 * 10 bones (50 saved), and 50 cannonballs (12 saved, 600 gp of supplies).
	 */
	private static LedgerRecord deviantCannonRecord()
	{
		LedgerRecord r = record();
		r.setId("deviant-cannon");
		r.setMonster(SPECTRE_TASK);
		r.setCreatureId(SPECTRES);
		r.setVariant("Deviant spectres");
		r.setOriginalAmount(10);
		r.setSupplies(new HashMap<>(Collections.singletonMap(SupplyTracker.CANNONBALL, 50L)));
		Map<Integer, Long> ge = new HashMap<>(r.getUnitGePrices());
		ge.put(SupplyTracker.CANNONBALL, 12L);
		r.setUnitGePrices(ge);
		return r;
	}

	/** {@code metric}'s Trends rate of the group named {@code name}, after the background work; {@code null} if none. */
	private Long trendsRate(TaskRanking.Metric metric, String name)
	{
		harness.onEdt(() -> harness.historyModel().ranking(metric));
		harness.drain();
		return onEdt(() ->
		{
			for (TaskRanking.Entry entry : harness.historyModel().ranking(metric))
			{
				if (name.equals(entry.getMonster()))
				{
					return entry.getRatePerHour();
				}
			}
			return null;
		});
	}

	/** {@code metric}'s Trends chart point of the group labelled {@code name}, after the background work. */
	private Long chartRate(TaskRanking.Metric metric, String name)
	{
		harness.onEdt(() -> harness.historyModel().chartMonsters(metric));
		harness.drain();
		TaskChart.Monster monster = onEdt(() ->
		{
			for (TaskChart.Monster m : harness.historyModel().chartMonsters(metric))
			{
				if (name.equals(m.getLabel()))
				{
					return m;
				}
			}
			return null;
		});
		assertNotNull(name, monster);
		harness.onEdt(() -> harness.historyModel().chartPoints(monster, metric));
		harness.drain();
		return onEdt(() -> harness.historyModel().chartPoints(monster, metric).get(0).getRatePerHour());
	}

	/** The History estimate's profit per kill for the live Deviant spectres cannon task's records. */
	private Long estimateProfitPerKill()
	{
		TaskSelection selection = TaskSelection.of("Deviant spectres").withCannon(true);
		return onEdt(() ->
		{
			assertEquals("the history has a cannon task, so the estimate counts cannon tasks only", selection,
				harness.historyModel().effectiveSelection(SPECTRES, SPECTRE_TASK, selection));
			return harness.historyModel().taskStatistics(SPECTRES, SPECTRE_TASK, selection, null).getProfitPerKill();
		});
	}

	@Test
	public void aSplitTrendsRowAndProfitPerKillLeaveHiddenLootOut()
	{
		harness.defineItem(SupplyTracker.CANNONBALL, "Cannonball", 12, 5);
		harness.setGroundItemsHidden("Bones");
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		LedgerRecord record = deviantCannonRecord();
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, Collections.singletonList(record)));
		harness.drain();

		// The variant's cannon row at today's prices: 38,000 loot without the bones, minus 600 of
		// cannonballs.
		assertEquals(Long.valueOf(37_400), trendsRate(TaskRanking.Metric.PROFIT, DEVIANT_CANNON));
		assertEquals(Long.valueOf(3_740), trendsRate(TaskRanking.Metric.PROFIT_PER_KILL, DEVIANT_CANNON));
		assertEquals(Long.valueOf(3_740), chartRate(TaskRanking.Metric.PROFIT_PER_KILL, DEVIANT_CANNON));
		assertEquals(Long.valueOf(3_740), estimateProfitPerKill());

		// The setting turned off: the bones (10 at 100 gp today) count again in every split view.
		harness.setHideGroundItemsHidden(false);
		assertEquals(Long.valueOf(38_400), trendsRate(TaskRanking.Metric.PROFIT, DEVIANT_CANNON));
		assertEquals(Long.valueOf(3_840), trendsRate(TaskRanking.Metric.PROFIT_PER_KILL, DEVIANT_CANNON));
		assertEquals(Long.valueOf(3_840), chartRate(TaskRanking.Metric.PROFIT_PER_KILL, DEVIANT_CANNON));
		assertEquals(Long.valueOf(3_840), estimateProfitPerKill());
		assertEquals(Long.valueOf(10), record.getLoot().get(ItemID.BONES));
	}
}
