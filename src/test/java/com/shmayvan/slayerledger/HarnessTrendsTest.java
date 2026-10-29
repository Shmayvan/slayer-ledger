package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * The collapsed "Trends" section as wired in the plugin ({@link PluginHarness}): its List view ranks monsters
 * as the old Task rankings did, its Chart view plots tasks as the old Task charts did, and only the shown
 * view is built, only while open. {@link TaskRankingTest} and {@link TaskChartTest} cover the calculations.
 */
public class HarnessTrendsTest
{
	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		SavedItems.define(harness);
	}

	@Test
	public void collapsedInTheListViewAndTheListSwitchesBetweenRecordedMetrics()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		LedgerRecord demons = rankingRecord(42, "Abyssal demons", 10 * 60_000L);
		demons.setLootGeValue(100_000L);
		demons.setSuppliesGeValue(10_000L);
		demons.setXpGained(Collections.singletonMap("SLAYER", 10_000L));
		demons.setPointsEarned(20);
		LedgerRecord kurasks = rankingRecord(43, "Kurasks", 60 * 60_000L);
		kurasks.setLootGeValue(200_000L);
		kurasks.setSuppliesGeValue(0L);
		kurasks.setXpGained(Collections.emptyMap());
		kurasks.setPointsEarned(0);
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT,
			Arrays.asList(SavedItems.withItems(demons), SavedItems.withItems(kurasks))));

		boolean[] state = new boolean[2];
		TrendsSection.View[] view = new TrendsSection.View[1];
		harness.onEdt(() ->
		{
			state[0] = trends().isExpanded();
			state[1] = trends().isMonsterChoiceShown();
			view[0] = trends().getView();
		});
		assertFalse(state[0]);
		assertFalse("the Monster choice is the chart's", state[1]);
		assertEquals(TrendsSection.View.LIST, view[0]);

		String[][] rows = new String[3][];
		String[] labels = new String[3];
		// The list is worked out off the EDT: each read waits for it (rows() drains).
		harness.onEdt(() -> trends().clickTitle());
		rows[0] = rows().toArray(new String[0]);
		labels[0] = metricText();
		harness.onEdt(() -> trends().setMetric(TaskRanking.Metric.XP));
		rows[1] = rows().toArray(new String[0]);
		labels[1] = metricText();
		harness.onEdt(() -> trends().setMetric(TaskRanking.Metric.POINTS));
		rows[2] = rows().toArray(new String[0]);
		labels[2] = metricText();

		// Two columns: the name (truncated when narrow) and the rate with its sample count.
		assertEquals("1. Abyssal demons | 540K gp/h (n=1)", rows[0][0]);
		assertEquals("Profit (GE)/h", labels[0]);
		assertEquals("1. Abyssal demons | 60K XP/h (n=1)", rows[1][0]);
		assertEquals("2. Kurasks | 0 XP/h (n=1)", rows[1][1]);
		assertEquals("Slayer XP/h", labels[1]);
		assertEquals("1. Abyssal demons | 120 points/h (n=1)", rows[2][0]);
		assertEquals("Points/h", labels[2]);
	}

	@Test
	public void listRowsFollowTheLootPriceModeAtTodaysPrices()
	{
		// Saved at 2,000 gp a bone; 3,000 gp today.
		harness.defineItem(ItemID.DRAGON_BONES, "Dragon bones", 3_000, 5_000);
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		LedgerRecord record = rankingRecord(42, "Abyssal demons", 60 * 60_000L);
		record.setLoot(Collections.singletonMap(ItemID.DRAGON_BONES, 10L));
		record.setUnitGePrices(Collections.singletonMap(ItemID.DRAGON_BONES, 2_000L));
		record.setUnitHaPrices(Collections.singletonMap(ItemID.DRAGON_BONES, 5_000L));
		record.setLootGeValue(20_000L);
		record.setLootHaValue(50_000L);
		record.setSuppliesGeValue(0L);
		harness.onEdt(() ->
		{
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, Collections.singletonList(record));
			trends().clickTitle();
		});
		// The lookup is queued for the client thread, and its answer for the EDT: the rows wait for the prices.
		assertEquals(Collections.singletonList("1. Abyssal demons | 30K gp/h (n=1)"), rows());
		assertEquals("Profit (GE)/h", metricText());
		assertTrue(tooltips().get(0), tooltips().get(0).contains("(today's available GE prices)"));

		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		assertEquals(Collections.singletonList("1. Abyssal demons | 50K gp/h (n=1)"), rows());
		assertEquals("Profit (HA)/h", metricText());
		assertTrue(tooltips().get(0), tooltips().get(0).contains("today's available GE prices for GE-priced items"));
		assertTrue(tooltips().get(0), tooltips().get(0).contains("Profit (HA)/h: 50,000"));
	}

	@Test
	public void aMonsterUnderAMinuteIsListedLastWithoutARate()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		LedgerRecord brief = rankingRecord(42, "Abyssal demons", 30_000L);
		brief.setLootGeValue(1_000_000L);
		LedgerRecord loss = rankingRecord(43, "Kurasks", 60 * 60_000L);
		loss.setLootGeValue(0L);
		loss.setSuppliesGeValue(5_000L);
		harness.onEdt(() ->
		{
			harness.historyModel().setRecords(PluginHarness.ACCOUNT,
				Arrays.asList(SavedItems.withItems(brief), SavedItems.withItems(loss)));
			trends().clickTitle();
		});

		assertEquals(Arrays.asList("1. Kurasks | -5,000 gp/h (n=1)", "2. Abyssal demons | - (n=1)"), rows());
		assertTrue(tooltips().get(1), tooltips().get(1).contains("no rate until 1 minute of active time"));
	}

	@Test
	public void profitPerKillIsListedAfterProfitPerHourAndPoolsKillsWithoutAMinimumTime()
	{
		// Not on the GE today: its price is unavailable.
		harness.defineItem(ItemID.RANARR_SEED, "Ranarr seed", 0, 0);
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		// Under a minute: no hourly rate, but a per-kill one.
		LedgerRecord brief = rankingRecord(42, "Abyssal demons", 30_000L);
		brief.setLootGeValue(100_000L);
		brief.setLootHaValue(150_000L);
		brief.setSuppliesGeValue(0L);
		// With a ranarr seed whose GE price is unavailable (high alch 0): its loot is marked ~.
		LedgerRecord unpriced = rankingRecord(42, "Abyssal demons", 60 * 60_000L);
		unpriced.setLootGeValue(30_000L);
		unpriced.setLootHaValue(30_000L);
		unpriced.setSuppliesGeValue(7_000L);
		unpriced.setLootUnpricedItems(1);
		SavedItems.withItems(unpriced);
		Map<Integer, Long> loot = new HashMap<>(unpriced.getLoot());
		loot.put(ItemID.RANARR_SEED, 1L);
		unpriced.setLoot(loot);
		Map<Integer, Long> ha = new HashMap<>(unpriced.getUnitHaPrices());
		ha.put(ItemID.RANARR_SEED, 0L);
		unpriced.setUnitHaPrices(ha);
		// Not valued: left out rather than counted as zero.
		LedgerRecord legacy = rankingRecord(42, "Abyssal demons", 60 * 60_000L);
		LedgerRecord loss = rankingRecord(43, "Kurasks", 60 * 60_000L);
		loss.setLootGeValue(0L);
		loss.setLootHaValue(0L);
		loss.setSuppliesGeValue(5_000L);
		harness.onEdt(() ->
		{
			harness.historyModel().setRecords(PluginHarness.ACCOUNT,
				Arrays.asList(SavedItems.withItems(brief), unpriced, legacy, SavedItems.withItems(loss)));
			trends().clickTitle();
		});
		// The seed's price is looked up again; it never comes, so the wait runs out.
		harness.drain();
		harness.onEdt(() ->
		{
			harness.historyModel().expireCurrentPricesWait();
			harness.historyModel().endExpiredCurrentPricesWait();
		});

		@SuppressWarnings("unchecked")
		List<String>[] choices = new List[1];
		harness.onEdt(() -> choices[0] = trends().getMetricChoices());
		assertEquals(Arrays.asList("Profit (GE)/h", "Profit (GE)/kill", "Slayer XP/h", "Points/h"), choices[0]);

		harness.onEdt(() -> trends().setMetric(TaskRanking.Metric.PROFIT_PER_KILL));
		assertEquals("Profit (GE)/kill", metricText());
		// (100K + 23K) / 20 kills; a loss ranks below 0.
		assertEquals(Arrays.asList("1. Abyssal demons | ~6,150 gp/kill (n=2)", "2. Kurasks | -500 gp/kill (n=1)"),
			rows());
		String tooltip = tooltips().get(0);
		assertTrue(tooltip, tooltip.contains("Profit (GE)/kill: ~6,150"));
		assertTrue(tooltip, tooltip.contains("Loot at GE prices minus GE-valued supplies (today's available GE prices),"
			+ " divided by recorded kills"));
		assertTrue(tooltip, tooltip.contains("2 completed tasks included"));
		assertTrue(tooltip,
			tooltip.contains("Some item prices were unavailable, or an older task kept its saved totals."));

		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		assertEquals("Profit (HA)/kill", metricText());
		// (150K + 23K) / 20 kills; the missing GE price doesn't touch high alch values.
		assertEquals("1. Abyssal demons | 8,650 gp/kill (n=2)", rows().get(0));
	}

	@Test
	public void profitPerKillChartsOnePointPerTask()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		LedgerRecord record = chartRecord(42, "Abyssal demons", Instant.parse("2025-01-15T12:00:00Z").toEpochMilli());
		record.setLootGeValue(2_000L);
		record.setLootHaValue(5_000L);
		record.setSuppliesGeValue(500L);
		SavedItems.withItems(record);
		harness.onEdt(() ->
		{
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, Collections.singletonList(record));
			trends().clickTitle();
			trends().setMetric(TaskRanking.Metric.PROFIT_PER_KILL);
			trends().clickView(TrendsSection.View.CHART);
		});

		assertEquals(1, pointCount());
		assertTrue(tooltip(), tooltip().contains("Profit (GE)/kill: 150"));

		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		assertTrue(tooltip(), tooltip().contains("Profit (HA)/kill: 450"));
	}

	@Test
	public void theChartViewShowsTheMonsterChoiceAndRefreshesItsTaskSamples()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		LedgerRecord demons = chartRecord(42, "Abyssal demons", Instant.parse("2025-01-15T12:00:00Z").toEpochMilli());
		demons.setLootGeValue(200L);
		demons.setSuppliesGeValue(50L);
		demons.setXpGained(Collections.singletonMap("SLAYER", 100L));
		LedgerRecord kurasks = chartRecord(43, "Kurasks", Instant.parse("2025-01-16T12:00:00Z").toEpochMilli());
		kurasks.setLootGeValue(100L);
		kurasks.setSuppliesGeValue(0L);
		LedgerRecord partial = chartRecord(42, "Abyssal demons", Instant.parse("2025-01-17T12:00:00Z").toEpochMilli());
		partial.setPartial(true);
		partial.setLootGeValue(10_000L);
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT,
			Arrays.asList(demons, kurasks, partial)));

		String[][] monsters = new String[2][];
		int[] points = new int[3];
		boolean[] monsterShown = new boolean[2];
		// The chart is worked out off the EDT: drained before each read.
		harness.onEdt(() ->
		{
			trends().clickTitle();
			trends().clickView(TrendsSection.View.CHART);
		});
		harness.drain();
		harness.onEdt(() ->
		{
			monsterShown[0] = trends().isMonsterChoiceShown();
			monsters[0] = trends().getMonsters().toArray(new String[0]);
			points[0] = trends().getPointCount();
			trends().setMetric(TaskRanking.Metric.XP);
		});
		harness.drain();
		harness.onEdt(() ->
		{
			monsters[1] = trends().getMonsters().toArray(new String[0]);
			points[1] = trends().getPointCount();
			trends().setMetric(TaskRanking.Metric.POINTS);
		});
		harness.drain();
		harness.onEdt(() ->
		{
			points[2] = trends().getPointCount();
			trends().clickView(TrendsSection.View.LIST);
			monsterShown[1] = trends().isMonsterChoiceShown();
		});

		assertTrue(monsterShown[0]);
		assertEquals(Arrays.asList("Abyssal demons", "Kurasks"), Arrays.asList(monsters[0]));
		assertEquals(1, points[0]);
		assertEquals(Collections.singletonList("Abyssal demons"), Arrays.asList(monsters[1]));
		assertEquals(1, points[1]);
		assertEquals(0, points[2]);
		assertFalse("the Monster choice shows only in Chart view", monsterShown[1]);
		assertEquals("switching to the list drops the points", 0, pointCount());
	}

	@Test
	public void chartMetricAndTooltipNameTheProfitWithTheLootPriceMode()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		LedgerRecord record = chartRecord(42, "Abyssal demons", Instant.parse("2025-01-15T12:00:00Z").toEpochMilli());
		record.setLootGeValue(200L);
		record.setLootHaValue(500L);
		record.setSuppliesGeValue(50L);
		SavedItems.withItems(record);
		harness.onEdt(() ->
		{
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, Collections.singletonList(record));
			trends().clickTitle();
			trends().clickView(TrendsSection.View.CHART);
		});

		assertEquals("Profit (GE)/h", metricText());
		assertTrue(tooltip(), tooltip().contains("Profit (GE)/h: 150"));

		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		assertEquals("Profit (HA)/h", metricText());
		assertTrue(tooltip(), tooltip().contains("Profit (HA)/h: 450"));

		record.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 1_000L));
		harness.onEdt(() ->
		{
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, Collections.singletonList(record));
			trends().setMetric(TaskRanking.Metric.XP);
		});
		assertEquals("Slayer XP/h", metricText());
		assertTrue(tooltip(), tooltip().contains("Slayer XP/h: 1,000"));
	}

	@Test
	public void theViewMetricAndMonsterChoicesAreKeptWhileClosed()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		LedgerRecord demons = chartRecord(42, "Abyssal demons", Instant.parse("2025-01-15T12:00:00Z").toEpochMilli());
		demons.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 100L));
		LedgerRecord kurasks = chartRecord(43, "Kurasks", Instant.parse("2025-01-16T12:00:00Z").toEpochMilli());
		kurasks.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 200L));
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT, Arrays.asList(demons, kurasks)));

		TrendsSection.View[] view = new TrendsSection.View[1];
		String[] metric = new String[1];
		boolean[] open = new boolean[1];
		int[] points = new int[1];
		harness.onEdt(() ->
		{
			trends().clickTitle();
			trends().clickView(TrendsSection.View.CHART);
			trends().setMetric(TaskRanking.Metric.XP);
		});
		// The Monster choices are worked out off the EDT.
		harness.drain();
		harness.onEdt(() ->
		{
			PanelProbe.TrendsProbe trends = trends();
			trends.setMonster("Kurasks");
			trends.clickTitle();
			trends.clickTitle();
		});
		harness.drain();
		harness.onEdt(() ->
		{
			PanelProbe.TrendsProbe trends = trends();
			open[0] = trends.isExpanded();
			view[0] = trends.getView();
			metric[0] = trends.getMetricText();
			points[0] = trends.getPointCount();
		});
		assertTrue(open[0]);
		assertEquals(TrendsSection.View.CHART, view[0]);
		assertEquals("Slayer XP/h", metric[0]);
		assertEquals(1, points[0]);
		assertTrue(tooltip(), tooltip().contains("Slayer XP/h: 200"));
	}

	@Test
	public void onlyTheShownViewIsBuiltOnlyWhileOpenAndNeverByTheRefreshTimer()
	{
		harness.defineItem(ItemID.DRAGON_BONES, "Dragon bones", 2_000, 5_000);
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		LedgerRecord record = chartRecord(42, "Abyssal demons", Instant.parse("2025-01-15T12:00:00Z").toEpochMilli());
		record.setLoot(Collections.singletonMap(ItemID.DRAGON_BONES, 10L));
		record.setUnitGePrices(Collections.singletonMap(ItemID.DRAGON_BONES, 2_000L));
		record.setUnitHaPrices(Collections.singletonMap(ItemID.DRAGON_BONES, 5_000L));
		record.setLootGeValue(20_000L);
		record.setLootHaValue(50_000L);
		record.setSuppliesGeValue(0L);
		int[] builds = new int[2];
		harness.onEdt(() ->
		{
			PanelProbe.TrendsProbe trends = trends();
			builds[0] = trends.getBuildCount();
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, Collections.singletonList(record));
			trends.setMetric(TaskRanking.Metric.XP);
			trends.setMetric(TaskRanking.Metric.PROFIT);
			trends.clickView(TrendsSection.View.CHART);
			trends.clickView(TrendsSection.View.LIST);
		});
		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		harness.onEdt(() -> harness.historyModel().addCurrentPrices(Collections.singleton(ItemID.DRAGON_BONES),
			Collections.singletonMap(ItemID.DRAGON_BONES, 2_000L)));
		harness.drain();
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			builds[1] = trends().getBuildCount();
		});
		assertEquals("collapsed: nothing built", builds[0], builds[1]);
		assertTrue(rows().isEmpty());
		assertEquals(0, pointCount());

		// Opening builds the list once, when it is worked out (off the EDT); the 1-second refresh never rebuilds.
		harness.onEdt(() ->
		{
			builds[0] = trends().getBuildCount();
			trends().clickTitle();
		});
		harness.drain();
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			harness.panel().refresh();
		});
		harness.drain();
		harness.onEdt(() -> builds[1] = trends().getBuildCount());
		assertEquals(builds[0] + 1, builds[1]);
		assertEquals(Collections.singletonList("1. Abyssal demons | 50K gp/h (n=1)"), rows());
		assertEquals("the chart is not built in List view", 0, pointCount());

		// Switching to the chart builds it once and drops the list; picking the shown view again builds nothing.
		harness.onEdt(() ->
		{
			builds[0] = trends().getBuildCount();
			trends().clickView(TrendsSection.View.CHART);
			trends().clickView(TrendsSection.View.CHART);
		});
		harness.drain();
		harness.onEdt(() -> harness.panel().refresh());
		harness.drain();
		harness.onEdt(() -> builds[1] = trends().getBuildCount());
		assertEquals(builds[0] + 1, builds[1]);
		assertTrue(rows().isEmpty());
		assertEquals(1, pointCount());
		assertTrue(tooltip(), tooltip().contains("Profit (HA)/h: 50K"));

		// A metric or Loot price mode change while open rebuilds once.
		harness.onEdt(() ->
		{
			builds[0] = trends().getBuildCount();
			trends().setMetric(TaskRanking.Metric.XP);
		});
		harness.drain();
		harness.onEdt(() ->
		{
			builds[1] = trends().getBuildCount();
			trends().setMetric(TaskRanking.Metric.PROFIT);
		});
		harness.drain();
		assertEquals(builds[0] + 1, builds[1]);
		harness.onEdt(() -> builds[0] = trends().getBuildCount());
		harness.setLootPriceMode(LootPriceMode.GE);
		harness.onEdt(() -> builds[1] = trends().getBuildCount());
		assertEquals(builds[0] + 1, builds[1]);
		assertTrue(tooltip(), tooltip().contains("Profit (GE)/h: 20K"));

		// Back to the list: built once; the hidden Monster choice builds nothing.
		harness.onEdt(() ->
		{
			builds[0] = trends().getBuildCount();
			trends().clickView(TrendsSection.View.LIST);
		});
		harness.drain();
		harness.onEdt(() ->
		{
			trends().setMonster("Abyssal demons");
			builds[1] = trends().getBuildCount();
		});
		assertEquals(builds[0] + 1, builds[1]);
		assertEquals(0, pointCount());
		assertEquals(Collections.singletonList("1. Abyssal demons | 20K gp/h (n=1)"), rows());

		// Closing it drops the rows; record, metric and view changes while closed build nothing.
		harness.onEdt(() ->
		{
			PanelProbe.TrendsProbe trends = trends();
			trends.clickTitle();
			builds[0] = trends.getBuildCount();
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, Collections.singletonList(record));
			trends.setMetric(TaskRanking.Metric.POINTS);
			trends.clickView(TrendsSection.View.CHART);
			trends.clickView(TrendsSection.View.LIST);
			builds[1] = trends.getBuildCount();
		});
		assertEquals(builds[0], builds[1]);
		assertTrue(rows().isEmpty());
		assertEquals(0, pointCount());
	}

	@Test
	public void theChartWaitsForTodaysPricesThenRedrawsOnce()
	{
		harness.defineItem(ItemID.DRAGON_BONES, "Dragon bones", 2_000, 5_000);
		harness.defineItem(ItemID.RANARR_SEED, "Ranarr seed", 50_000, 20);
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		LedgerRecord first = chartRecord(42, "Abyssal demons", Instant.parse("2025-01-15T12:00:00Z").toEpochMilli());
		first.setId("first");
		first.setLoot(Collections.singletonMap(ItemID.DRAGON_BONES, 10L));
		first.setLootGeValue(20_000L);
		first.setLootHaValue(50_000L);
		first.setSuppliesGeValue(0L);
		LedgerRecord second = chartRecord(42, "Abyssal demons", Instant.parse("2025-01-16T12:00:00Z").toEpochMilli());
		second.setId("second");
		second.setLoot(Collections.singletonMap(ItemID.RANARR_SEED, 1L));
		second.setLootGeValue(40_000L);
		second.setLootHaValue(20L);
		second.setSuppliesGeValue(0L);
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT, Collections.singletonList(first)));
		harness.drain();
		harness.onEdt(() ->
		{
			trends().clickTitle();
			trends().clickView(TrendsSection.View.CHART);
		});
		assertEquals(1, pointCount());

		int[] before = new int[1];
		harness.onEdt(() ->
		{
			SlayerLedgerPanel panel = harness.panel();
			before[0] = trends().getBuildCount();
			// The lookup is queued for the client thread, so it cannot answer inside this EDT task.
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, Arrays.asList(first, second));
			panel.refresh();
			assertTrue(harness.historyModel().isWaitingForCurrentPrices());
			assertEquals("the previous chart stays", before[0], trends().getBuildCount());
			assertEquals(1, trends().getPointCount());
		});

		harness.drain();
		harness.onEdt(() ->
		{
			assertFalse(harness.historyModel().isWaitingForCurrentPrices());
			assertEquals("redrawn once", before[0] + 1, trends().getBuildCount());
			assertEquals(2, trends().getPointCount());
		});
	}

	private PanelProbe.TrendsProbe trends()
	{
		return harness.ui().getTrends();
	}

	// The reads below drain first: the list and the chart are worked out off the EDT.

	private List<String> rows()
	{
		@SuppressWarnings("unchecked")
		List<String>[] rows = new List[1];
		harness.drain();
		harness.onEdt(() -> rows[0] = trends().getRows());
		return rows[0];
	}

	private List<String> tooltips()
	{
		@SuppressWarnings("unchecked")
		List<String>[] tooltips = new List[1];
		harness.drain();
		harness.onEdt(() -> tooltips[0] = trends().getTooltips());
		return tooltips[0];
	}

	private String metricText()
	{
		String[] text = new String[1];
		harness.drain();
		harness.onEdt(() -> text[0] = trends().getMetricText());
		return text[0];
	}

	private int pointCount()
	{
		int[] count = new int[1];
		harness.drain();
		harness.onEdt(() -> count[0] = trends().getPointCount());
		return count[0];
	}

	/** Paints the chart at a fixed size and returns the tooltip of its single point, in the middle. */
	private String tooltip()
	{
		String[] tooltip = new String[1];
		harness.drain();
		harness.onEdt(() ->
		{
			TaskTrendChartView chart = trends().getChart();
			chart.setSize(240, 150);
			BufferedImage image = new BufferedImage(240, 150, BufferedImage.TYPE_INT_ARGB);
			Graphics2D graphics = image.createGraphics();
			chart.paint(graphics);
			graphics.dispose();
			tooltip[0] = chart.getToolTipText(new MouseEvent(chart, MouseEvent.MOUSE_MOVED,
				System.currentTimeMillis(), 0, 143, 67, 0, false));
		});
		return tooltip[0];
	}

	/** A completed task that tracked the whole assignment, without a completion date. */
	private static LedgerRecord rankingRecord(int creatureId, String monster, long activeMillis)
	{
		LedgerRecord record = new LedgerRecord();
		record.setCreatureId(creatureId);
		record.setMonster(monster);
		record.setKills(10);
		record.setInitialAmount(10);
		record.setOriginalAmount(10);
		record.setActiveMillis(activeMillis);
		record.setCompleted(true);
		return record;
	}

	/** A completed hour-long task that ended at {@code endedAt}. */
	private static LedgerRecord chartRecord(int creatureId, String monster, long endedAt)
	{
		LedgerRecord record = new LedgerRecord();
		record.setCreatureId(creatureId);
		record.setMonster(monster);
		record.setEndedAt(endedAt);
		record.setKills(10);
		record.setActiveMillis(60 * 60 * 1000L);
		record.setCompleted(true);
		record.setPartial(false);
		return record;
	}
}
