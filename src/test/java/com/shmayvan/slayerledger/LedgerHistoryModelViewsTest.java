package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import org.junit.Before;
import org.junit.Test;

/**
 * The derived views {@link LedgerHistoryModel} works out for the panel: each is worked out once and cached
 * until what it comes from changes (the records, how they are valued, item names or its own inputs), and the listener
 * is told what changed. The panel's harness tests cover how they are shown.
 */
public class LedgerHistoryModelViewsTest
{
	private static final long ACCOUNT = 1L;
	private static final int DEMONS = 42;
	private static final int WHIP = 4151;
	private static final int SHARK = 385;
	private static final long MINUTE = 60_000L;

	private final List<Set<Integer>> priceRequests = new ArrayList<>();
	private final List<Set<Integer>> nameRequests = new ArrayList<>();
	private final List<LedgerHistoryModel.Change> changes = new ArrayList<>();
	/** Background views queued here while {@link #holdBackground}; otherwise worked out at once. */
	private final Deque<Runnable> backgroundTasks = new ArrayDeque<>();
	private boolean holdBackground;
	private final Executor background = task ->
	{
		if (holdBackground)
		{
			backgroundTasks.add(task);
		}
		else
		{
			task.run();
		}
	};
	private SessionTracker tracker;
	private LedgerHistoryModel model;

	@Before
	public void setUp()
	{
		tracker = new SessionTracker(() -> 1_000_000L);
		model = new LedgerHistoryModel(tracker, LootPriceMode.GE, regions ->
		{
		}, priceRequests::add, nameRequests::add, line ->
		{
		}, pace ->
		{
		}, background, Runnable::run);
		model.setListener(changes::add);
	}

	/** A completed Abyssal demons task: 10 kills in 10 minutes, a whip dropped and 5 sharks eaten. */
	private static LedgerRecord demons(String id, long endedAt)
	{
		LedgerRecord record = new LedgerRecord();
		record.setId(id);
		record.setCreatureId(DEMONS);
		record.setMonster("Abyssal demons");
		record.setKills(10);
		record.setActiveMillis(10 * MINUTE);
		record.setCompleted(true);
		record.setLootGeValue(100_000L);
		record.setLootHaValue(200_000L);
		record.setSuppliesGeValue(10_000L);
		record.setLoot(Collections.singletonMap(WHIP, 1L));
		record.setSupplies(Collections.singletonMap(SHARK, 5L));
		record.setBankTrips(1);
		record.setEndedAt(endedAt);
		return record;
	}

	private static AssignmentEntry skipped(String id, long endedAt)
	{
		AssignmentEntry entry = new AssignmentEntry();
		entry.setId(id);
		entry.setMonster("Abyssal demons");
		entry.setEndedAt(endedAt);
		entry.setOutcome(AssignmentOutcome.SKIPPED);
		return entry;
	}

	@Test
	public void theListenerIsToldWhatChanged()
	{
		model.setRecords(ACCOUNT, Collections.singletonList(demons("a", 1)));
		model.setAssignments(ACCOUNT, Collections.singletonList(skipped("s", 2)));
		model.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		model.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		model.addItemNames(Collections.singleton(WHIP), Collections.singletonMap(WHIP, "Abyssal whip"), null);
		model.setTaskAdvisor(true, TaskRanking.Metric.XP);
		model.clearRecords(ACCOUNT, "Loading");
		assertEquals(Arrays.asList(LedgerHistoryModel.Change.RECORDS, LedgerHistoryModel.Change.ASSIGNMENTS,
			LedgerHistoryModel.Change.LOOT_PRICE_MODE, LedgerHistoryModel.Change.ITEM_NAMES,
			LedgerHistoryModel.Change.TASK_ADVISOR, LedgerHistoryModel.Change.HISTORY_CLEARED), changes);
		assertEquals("Loading", model.historyMessage());

		changes.clear();
		model.setAssignments(2L, Collections.singletonList(skipped("t", 3)));
		assertTrue("another account's entries are ignored", changes.isEmpty());
	}

	@Test
	public void todaysPricesRedrawTheirViews()
	{
		LedgerRecord record = demons("a", 1);
		// Another account's records are drawn at once; their prices are asked for all the same.
		model.setRecords(ACCOUNT, Collections.singletonList(record));
		assertEquals(Collections.singletonList(LedgerHistoryModel.Change.RECORDS), changes);
		assertEquals(Collections.singletonList(new TreeSet<>(Arrays.asList(SHARK, WHIP))), priceRequests);
		changes.clear();

		// Nothing waits for them: today's prices redraw their views at once.
		model.addCurrentPrices(priceRequests.get(0), Collections.singletonMap(SHARK, 1_000L));
		assertEquals(Collections.singletonList(LedgerHistoryModel.Change.CURRENT_PRICES), changes);
		model.requestCurrentPrices();
		assertEquals("the whip's price was unavailable, so it is asked for again", Arrays.asList(
			new TreeSet<>(Arrays.asList(SHARK, WHIP)), Collections.singleton(WHIP)), priceRequests);

		// Waiting for the prices asked for: the views are redrawn once, when the last batch arrives.
		changes.clear();
		LedgerRecord other = demons("b", 2);
		other.setLoot(Collections.singletonMap(995, 1L));
		model.setRecords(ACCOUNT, Arrays.asList(record, other));
		assertEquals(Collections.singletonList(LedgerHistoryModel.Change.RECORDS_WAITING_FOR_PRICES), changes);
		model.addCurrentPrices(priceRequests.get(1), Collections.emptyMap());
		assertEquals("still waiting for the coins", Collections.singletonList(
			LedgerHistoryModel.Change.RECORDS_WAITING_FOR_PRICES), changes);
		model.addCurrentPrices(priceRequests.get(2), Collections.singletonMap(995, 1L));
		assertEquals(Arrays.asList(LedgerHistoryModel.Change.RECORDS_WAITING_FOR_PRICES,
			LedgerHistoryModel.Change.PRICED_VIEWS), changes);
	}

	@Test
	public void overallTotalsAreWorkedOutOncePerRecordsAndPricing()
	{
		model.setRecords(ACCOUNT, Collections.singletonList(demons("a", 1)));
		List<OverallTotals.Entry> totals = model.overallTotals(OverallTotals.Grouping.ALL);
		assertEquals(1, totals.size());
		assertEquals(1, model.getOverallTotalsBuildCount());
		assertSame("nothing changed", totals, model.overallTotals(OverallTotals.Grouping.ALL));
		assertEquals(1, model.getOverallTotalsBuildCount());

		model.overallTotals(OverallTotals.Grouping.MASTER);
		assertEquals("another grouping", 2, model.getOverallTotalsBuildCount());

		model.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		List<OverallTotals.Entry> alched = model.overallTotals(OverallTotals.Grouping.ALL);
		assertNotSame(totals, alched);
		assertEquals("the mode changed", 3, model.getOverallTotalsBuildCount());

		// The whip's price is unavailable.
		model.addCurrentPrices(priceRequests.get(0), Collections.singletonMap(SHARK, 1_000L));
		model.overallTotals(OverallTotals.Grouping.ALL);
		assertEquals("today's prices arrived", 4, model.getOverallTotalsBuildCount());

		model.setRecords(ACCOUNT, Arrays.asList(demons("a", 1), demons("b", 2)));
		assertEquals("the whip's price is asked for again", Collections.singleton(WHIP),
			priceRequests.get(priceRequests.size() - 1));
		model.addCurrentPrices(priceRequests.get(priceRequests.size() - 1), Collections.emptyMap());
		assertEquals(2, model.overallTotals(OverallTotals.Grouping.ALL).get(0).getTaskCount());
		assertEquals("the records changed", 5, model.getOverallTotalsBuildCount());
	}

	@Test
	public void theTrendsListAndChartAreWorkedOutOncePerRecordsAndPricing()
	{
		model.setRecords(ACCOUNT, Collections.singletonList(demons("a", 1)));
		List<TaskRanking.Entry> ranking = model.ranking(TaskRanking.Metric.PROFIT);
		assertEquals(1, ranking.size());
		assertSame(ranking, model.ranking(TaskRanking.Metric.PROFIT));
		assertEquals(1, model.getRankingBuildCount());
		model.ranking(TaskRanking.Metric.XP);
		assertEquals("another metric", 2, model.getRankingBuildCount());

		model.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		assertNotSame(ranking, model.ranking(TaskRanking.Metric.PROFIT));
		assertEquals(3, model.getRankingBuildCount());

		List<TaskChart.Monster> monsters = model.chartMonsters(TaskRanking.Metric.PROFIT);
		assertSame(monsters, model.chartMonsters(TaskRanking.Metric.PROFIT));
		List<TaskChart.Point> points = model.chartPoints(monsters.get(0), TaskRanking.Metric.PROFIT);
		assertEquals(1, points.size());
		assertSame(points, model.chartPoints(monsters.get(0), TaskRanking.Metric.PROFIT));
		model.setRecords(ACCOUNT, Arrays.asList(demons("a", 1), demons("b", 2)));
		assertEquals(2, model.chartPoints(monsters.get(0), TaskRanking.Metric.PROFIT).size());
	}

	/** Runs the queued background views, oldest first; their results reach the model at once (the test is the EDT). */
	private void runBackground()
	{
		while (!backgroundTasks.isEmpty())
		{
			backgroundTasks.poll().run();
		}
	}

	@Test
	public void backgroundViewsAreNullUntilReadyThenTellTheListenerOnce()
	{
		holdBackground = true;
		model.setRecords(ACCOUNT, Collections.singletonList(demons("a", 1)));
		changes.clear();
		assertNull("being worked out", model.ranking(TaskRanking.Metric.PROFIT));
		assertNull("asked again: still being worked out", model.ranking(TaskRanking.Metric.PROFIT));
		assertEquals("queued once", 1, backgroundTasks.size());
		assertEquals(1, model.getRankingBuildCount());
		assertTrue(changes.isEmpty());

		runBackground();
		assertEquals(Collections.singletonList(LedgerHistoryModel.Change.VIEWS), changes);
		List<TaskRanking.Entry> ranking = model.ranking(TaskRanking.Metric.PROFIT);
		assertEquals(1, ranking.size());
		assertSame(ranking, model.ranking(TaskRanking.Metric.PROFIT));
		assertEquals(1, model.getRankingBuildCount());
	}

	@Test
	public void aResultForOlderRecordsOrPricingIsDropped()
	{
		holdBackground = true;
		model.setRecords(ACCOUNT, Collections.singletonList(demons("a", 1)));
		assertNull(model.overallTotals(OverallTotals.Grouping.ALL));
		// New records arrive while the first totals are still being worked out.
		model.setRecords(ACCOUNT, Arrays.asList(demons("a", 1), demons("b", 2)));
		assertNull(model.overallTotals(OverallTotals.Grouping.ALL));
		assertEquals(2, backgroundTasks.size());
		changes.clear();

		// The older result comes back first and is dropped; the newer one is kept.
		backgroundTasks.poll().run();
		assertTrue("the older result tells no one", changes.isEmpty());
		assertNull("and is not shown", model.overallTotals(OverallTotals.Grouping.ALL));
		backgroundTasks.poll().run();
		assertEquals(Collections.singletonList(LedgerHistoryModel.Change.VIEWS), changes);
		assertEquals(2, model.overallTotals(OverallTotals.Grouping.ALL).get(0).getTaskCount());
		assertEquals(2, model.getOverallTotalsBuildCount());

		// Another mode while the totals are worked out: the result for the previous mode is dropped too.
		assertNull(model.ranking(TaskRanking.Metric.PROFIT));
		model.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		assertNull(model.ranking(TaskRanking.Metric.PROFIT));
		changes.clear();
		backgroundTasks.poll().run();
		assertTrue(changes.isEmpty());
		backgroundTasks.poll().run();
		assertEquals(Collections.singletonList(LedgerHistoryModel.Change.VIEWS), changes);
		assertNotNull(model.ranking(TaskRanking.Metric.PROFIT));
	}

	/**
	 * Enough completed tasks for the advisor to rank ({@link TaskAdvisor#MIN_MONSTERS} monsters with
	 * {@link TaskAdvisor#MIN_TASKS} tasks each), Abyssal demons among them, plus {@code extra} more demons tasks.
	 */
	private static List<LedgerRecord> board(int extra)
	{
		List<LedgerRecord> records = new ArrayList<>();
		for (int monster = 0; monster < TaskAdvisor.MIN_MONSTERS; monster++)
		{
			for (int task = 0; task < TaskAdvisor.MIN_TASKS + (monster == 0 ? extra : 0); task++)
			{
				LedgerRecord record = demons("m" + monster + "t" + task, monster * 100L + task);
				if (monster > 0)
				{
					record.setCreatureId(DEMONS + monster);
					record.setMonster("Monster " + monster);
					record.setLootGeValue(50_000L * monster);
				}
				records.add(record);
			}
		}
		return records;
	}

	@Test
	public void theSameTasksAdviceStaysWhileItIsWorkedOutAgainAndNothingIsKeptAfterShutDown()
	{
		model.setRecords(ACCOUNT, board(0));
		tracker.observe("Abyssal demons", DEMONS, 20, false);
		TaskAdvisor.Result advice = model.advice();
		assertNotNull(advice);

		holdBackground = true;
		model.setRecords(ACCOUNT, board(1));
		assertSame("the same task's advice stays until the new one is ready", advice, model.advice());
		runBackground();
		TaskAdvisor.Result again = model.advice();
		assertNotNull(again);
		assertNotSame(advice, again);

		// Another goal: the previous goal's advice is not shown meanwhile.
		model.setTaskAdvisor(true, TaskRanking.Metric.XP);
		assertNull(model.advice());
		runBackground();

		// After shutDown, results still on their way are dropped and nothing new is queued.
		model.setRecords(ACCOUNT, board(2));
		assertNull(model.ranking(TaskRanking.Metric.PROFIT));
		model.shutDown();
		changes.clear();
		runBackground();
		assertTrue(changes.isEmpty());
		assertNull(model.ranking(TaskRanking.Metric.PROFIT));
		assertTrue(backgroundTasks.isEmpty());
	}

	@Test
	public void anotherAccountOrModeNeverShowsThePreviousAdviceWhileItIsWorkedOut()
	{
		model.setRecords(ACCOUNT, board(0));
		tracker.observe("Abyssal demons", DEMONS, 20, false);
		TaskAdvisor.Result first = model.advice();
		assertNotNull(first);

		// Another "Loot price" mode: the GE advice is not shown while the HA one is worked out.
		holdBackground = true;
		model.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		assertNull(model.advice());
		runBackground();
		assertNotNull(model.advice());
		model.setLootPriceMode(LootPriceMode.GE);
		model.advice();
		runBackground();
		assertNotNull(model.advice());

		// Another account with the same current task: the first account's advice is never shown, while its history
		// loads or after, before the new advice is ready; and a result still on its way for the first is dropped.
		model.setRecords(ACCOUNT, board(1));
		assertNotNull("the same account's advice stays", model.advice());
		model.clearRecords(ACCOUNT + 1, "Loading...");
		assertNull(model.advice());
		model.setRecords(ACCOUNT + 1, board(2));
		assertNull(model.advice());
		runBackground();
		TaskAdvisor.Result other = model.advice();
		assertNotNull(other);
		assertNotSame(first, other);
	}

	@Test
	public void aResultForInputsLeftBeforeItArrivesIsDropped()
	{
		model.setRecords(ACCOUNT, Collections.singletonList(demons("a", 1)));
		List<TaskRanking.Entry> profit = model.ranking(TaskRanking.Metric.PROFIT);
		assertNotNull(profit);
		holdBackground = true;
		assertNull("XP is queued", model.ranking(TaskRanking.Metric.XP));
		assertSame("back to Profit: kept", profit, model.ranking(TaskRanking.Metric.PROFIT));
		int builds = model.getRankingBuildCount();
		changes.clear();

		// The XP result arrives after the player went back to Profit: dropped, and nothing is redrawn.
		runBackground();
		assertTrue(changes.isEmpty());
		assertSame(profit, model.ranking(TaskRanking.Metric.PROFIT));
		assertEquals(builds, model.getRankingBuildCount());
		assertTrue(backgroundTasks.isEmpty());
	}

	@Test
	public void theAdviceIsWorkedOutOncePerTaskRecordsPricingAndGoal()
	{
		model.setRecords(ACCOUNT, Collections.singletonList(demons("a", 1)));
		assertNull("no task", model.advice());
		assertEquals(0, model.getAdviceBuildCount());

		tracker.observe("Abyssal demons", DEMONS, 20, false);
		model.advice();
		model.advice();
		assertEquals(1, model.getAdviceBuildCount());

		model.setTaskAdvisor(true, TaskRanking.Metric.XP);
		model.advice();
		assertEquals("another goal", 2, model.getAdviceBuildCount());
		model.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		model.advice();
		assertEquals("the mode changed", 3, model.getAdviceBuildCount());
		model.setRecords(ACCOUNT, Arrays.asList(demons("a", 1), demons("b", 2)));
		model.advice();
		assertEquals("the records changed", 4, model.getAdviceBuildCount());

		model.setTaskAdvisor(false, TaskRanking.Metric.XP);
		assertNull("off", model.advice());
		assertEquals(4, model.getAdviceBuildCount());
	}

	@Test
	public void theLuckMeterWaitsForItemNamesAndIsWorkedOutOncePerChange()
	{
		assertNull("no data without a source", model.luckTasks());
		assertEquals(LuckView.State.UNAVAILABLE, model.luck("Abyssal demons").getState());

		LedgerHistoryModel withMeter = new LedgerHistoryModel(tracker, LootPriceMode.GE, regions ->
		{
		}, priceRequests::add, nameRequests::add, line ->
		{
		}, pace ->
		{
		}, background, Runnable::run);
		withMeter.setLuckMeterSource(() -> LuckMeter.load(new Gson(), LuckMeter.SNAPSHOT_RESOURCE));
		withMeter.setRecords(ACCOUNT, Collections.singletonList(demons("a", 1)));
		assertEquals(Collections.singletonList("Abyssal demons"), withMeter.luckTasks());
		assertSame(withMeter.luckTasks(), withMeter.luckTasks());

		LuckView loading = withMeter.luck("Abyssal demons");
		assertEquals(LuckView.State.NAMES_LOADING, loading.getState());
		assertEquals(Collections.singleton(WHIP), nameRequests.get(nameRequests.size() - 1));
		assertSame("nothing arrived", loading, withMeter.luck("Abyssal demons"));
		assertEquals(1, withMeter.getLuckBuildCount());

		withMeter.addItemNames(Collections.singleton(WHIP), Collections.singletonMap(WHIP, "Abyssal whip"), null);
		LuckView shown = withMeter.luck("Abyssal demons");
		assertEquals(LuckView.State.SUMMARY, shown.getState());
		assertEquals(1, shown.getSummary().getTaskCount());
		assertSame(shown, withMeter.luck("Abyssal demons"));
		assertEquals(2, withMeter.getLuckBuildCount());

		withMeter.setRecords(ACCOUNT, Collections.emptyList());
		assertEquals(LuckView.State.NO_SAMPLES, withMeter.luck("Abyssal demons").getState());
		assertEquals("the records changed", 3, withMeter.getLuckBuildCount());
	}

	@Test
	public void theSkippedBlockedRowsAreWorkedOutOncePerChangeOfTheEntries()
	{
		model.setRecords(ACCOUNT, Collections.emptyList());
		model.setAssignments(ACCOUNT, Arrays.asList(skipped("s", 1), skipped("t", 2)));
		List<AssignmentRow> rows = model.assignmentRows();
		assertEquals(2, rows.size());
		assertEquals("newest first", "t", rows.get(0).getEntry().getId());
		assertEquals("Abyssal demons", rows.get(0).getMonster());
		assertNotNull(rows.get(0).getStatus());
		assertNotNull(rows.get(0).getTooltip());
		assertSame(rows, model.assignmentRows());
		assertEquals(1, model.getAssignmentRowsBuildCount());

		model.setAssignments(ACCOUNT, Collections.singletonList(skipped("s", 1)));
		assertEquals(1, model.assignmentRows().size());
		assertEquals(2, model.getAssignmentRowsBuildCount());
	}

	@Test
	public void whatToBringIsWorkedOutOncePerStatisticsKillsLeftAndItemNames()
	{
		model.setRecords(ACCOUNT, Collections.singletonList(demons("a", 1)));
		TaskStatistics stats = model.taskStatistics(DEMONS, "Abyssal demons", null);
		WhatToBring bring = model.whatToBring(stats, 20);
		assertNotNull(bring);
		assertEquals("Item " + SHARK, bring.getRows().get(0).getName());
		assertEquals("x10", bring.getRows().get(0).quantityText());
		assertSame(bring, model.whatToBring(stats, 20));
		assertEquals(1, model.getWhatToBringBuildCount());

		model.whatToBring(stats, 10);
		assertEquals("other kills left", 2, model.getWhatToBringBuildCount());

		model.addItemNames(Collections.singleton(SHARK), Collections.singletonMap(SHARK, "Shark"), null);
		assertEquals("Shark", model.whatToBring(stats, 10).getRows().get(0).getName());
		assertEquals("named", 3, model.getWhatToBringBuildCount());

		model.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		TaskStatistics restated = model.taskStatistics(DEMONS, "Abyssal demons", null);
		assertNotSame("the statistics were dropped", stats, restated);
		model.whatToBring(restated, 10);
		assertEquals(4, model.getWhatToBringBuildCount());
	}

	@Test
	public void itemNamesAreAskedForOnceAndTheNatureRunePriceWithTheFirstBatch()
	{
		model.requestItemNames(Collections.emptySet());
		assertEquals("the nature rune price is asked for", 1, nameRequests.size());
		model.requestItemNames(Collections.emptySet());
		assertEquals(1, nameRequests.size());

		model.requestItemNames(Collections.singleton(WHIP));
		model.requestItemNames(Collections.singleton(WHIP));
		assertEquals(2, nameRequests.size());
		assertTrue(model.isItemNamePending(WHIP));

		model.addItemNames(Collections.singleton(WHIP), Collections.emptyMap(), 150L);
		assertTrue(model.isItemNameFailed(WHIP));
		assertEquals(Long.valueOf(150L), model.natureRunePrice());
		model.requestItemNames(Collections.singleton(WHIP));
		assertEquals("a failed name is not asked for again", 2, nameRequests.size());
	}
}
