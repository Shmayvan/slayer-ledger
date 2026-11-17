package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import org.junit.Before;
import org.junit.Test;

/**
 * The derived views {@link LedgerHistoryModel} works out for the panel: each is worked out once and cached
 * until what it comes from changes (the records, how they are valued, item names or its own inputs), and the listener
 * is told what changed; the background ones, the advisor's plan among them, keep only the result of the
 * latest inputs. The panel's harness tests cover how they are shown.
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
		}, priceRequests::add, nameRequests::add, pace ->
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
		model.clearRecords(ACCOUNT, "Loading");
		assertEquals(Arrays.asList(LedgerHistoryModel.Change.RECORDS, LedgerHistoryModel.Change.ASSIGNMENTS,
			LedgerHistoryModel.Change.LOOT_PRICE_MODE, LedgerHistoryModel.Change.ITEM_NAMES,
			LedgerHistoryModel.Change.HISTORY_CLEARED), changes);
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
		assertNull("being worked out", model.overallTotals(OverallTotals.Grouping.ALL));
		assertNull("asked again: still being worked out", model.overallTotals(OverallTotals.Grouping.ALL));
		assertEquals("queued once", 1, backgroundTasks.size());
		assertEquals(1, model.getOverallTotalsBuildCount());
		assertTrue(changes.isEmpty());

		runBackground();
		assertEquals(Collections.singletonList(LedgerHistoryModel.Change.VIEWS), changes);
		List<OverallTotals.Entry> totals = model.overallTotals(OverallTotals.Grouping.ALL);
		assertEquals(1, totals.size());
		assertSame(totals, model.overallTotals(OverallTotals.Grouping.ALL));
		assertEquals(1, model.getOverallTotalsBuildCount());
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
		assertNull(model.overallTotals(OverallTotals.Grouping.MASTER));
		model.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		assertNull(model.overallTotals(OverallTotals.Grouping.MASTER));
		changes.clear();
		backgroundTasks.poll().run();
		assertTrue(changes.isEmpty());
		backgroundTasks.poll().run();
		assertEquals(Collections.singletonList(LedgerHistoryModel.Change.VIEWS), changes);
		assertNotNull(model.overallTotals(OverallTotals.Grouping.MASTER));
	}

	@Test
	public void theSameTasksPlanStaysWhileItIsWorkedOutAgainAndNothingIsKeptAfterShutDown()
	{
		tracker.observe("Abyssal demons", DEMONS, 20, false);
		model.setRecords(ACCOUNT, setups());
		model.addCurrentPrices(priceRequests.get(priceRequests.size() - 1), SavedItems.todaysPrices());
		TaskPlan plan = model.plan();
		assertNotNull(plan);

		holdBackground = true;
		model.setRecords(ACCOUNT, setups());
		assertSame("the same task's plan stays until the new one is ready", plan, model.plan());
		runBackground();
		TaskPlan again = model.plan();
		assertNotNull(again);
		assertNotSame(plan, again);

		// After shutDown, results still on their way are dropped and nothing new is queued.
		model.setRecords(ACCOUNT, setups());
		assertNull(model.overallTotals(OverallTotals.Grouping.ALL));
		model.shutDown();
		changes.clear();
		runBackground();
		assertTrue(changes.isEmpty());
		assertNull(model.overallTotals(OverallTotals.Grouping.ALL));
		assertTrue(backgroundTasks.isEmpty());
	}

	@Test
	public void anotherAccountNeverShowsThePreviousPlanWhileItIsWorkedOut()
	{
		tracker.observe("Abyssal demons", DEMONS, 20, false);
		model.setRecords(ACCOUNT, setups());
		model.addCurrentPrices(priceRequests.get(priceRequests.size() - 1), SavedItems.todaysPrices());
		TaskPlan first = model.plan();
		assertNotNull(first);

		// Another account with the same current task: the first account's plan is never shown, while its history
		// loads or after, before the new plan is ready; and a result still on its way for the first is dropped.
		holdBackground = true;
		model.setRecords(ACCOUNT, setups());
		assertSame("the same account's plan stays", first, model.plan());
		model.clearRecords(ACCOUNT + 1, "Loading...");
		assertNull(model.plan());
		model.setRecords(ACCOUNT + 1, setups().subList(0, 2));
		assertNull(model.plan());
		runBackground();
		TaskPlan other = model.plan();
		assertNotNull(other);
		assertEquals(2, other.getAllSetups().getTasks());
	}

	@Test
	public void aResultForInputsLeftBeforeItArrivesIsDropped()
	{
		model.setRecords(ACCOUNT, Collections.singletonList(demons("a", 1)));
		List<OverallTotals.Entry> overall = model.overallTotals(OverallTotals.Grouping.ALL);
		assertNotNull(overall);
		holdBackground = true;
		assertNull("By master is queued", model.overallTotals(OverallTotals.Grouping.MASTER));
		assertSame("back to Overall: kept", overall, model.overallTotals(OverallTotals.Grouping.ALL));
		int builds = model.getOverallTotalsBuildCount();
		changes.clear();

		// The By master result arrives after the player went back to Overall: dropped, and nothing is redrawn.
		runBackground();
		assertTrue(changes.isEmpty());
		assertSame(overall, model.overallTotals(OverallTotals.Grouping.ALL));
		assertEquals(builds, model.getOverallTotalsBuildCount());
		assertTrue(backgroundTasks.isEmpty());
	}


	/**
	 * A completed task of 10 Abyssal demons at the Smoke Dungeon with melee: with a cannon in 5 minutes for 50K of
	 * coins, else in 10 minutes for 200K.
	 */
	private static LedgerRecord setup(String id, boolean cannon)
	{
		LedgerRecord record = new LedgerRecord();
		record.setId(id);
		record.setCreatureId(DEMONS);
		record.setMonster("Abyssal demons");
		record.setKills(10);
		record.setActiveMillis((cannon ? 5 : 10) * MINUTE);
		record.setCompleted(true);
		long loot = cannon ? 50_000L : 200_000L;
		record.setLootGeValue(loot);
		record.setLootHaValue(loot);
		record.setSuppliesGeValue(0L);
		record.setSpotId(TestSpots.SMOKE_ID);
		SetupTags.detected(cannon, SetupStyle.MELEE, GearFocus.UNKNOWN, SetupOverrides.NONE).copyTo(record);
		return SavedItems.withItems(record);
	}

	/** Three tasks of each {@link #setup}. */
	private static List<LedgerRecord> setups()
	{
		List<LedgerRecord> records = new ArrayList<>();
		for (int i = 0; i < 3; i++)
		{
			records.add(setup("plain" + i, false));
			records.add(setup("cannon" + i, true));
		}
		return records;
	}

	@Test
	public void thePlanIsWorkedOutInTheBackgroundOncePerInputsThenTellsTheListener()
	{
		model.setSpots(TestSpots.TABLE);
		tracker.observe("Abyssal demons", DEMONS, 20, false);
		assertNull("no records loaded", model.plan());
		assertEquals(0, model.getPlanBuildCount());

		holdBackground = true;
		model.setRecords(ACCOUNT, setups());
		assertNull("being worked out", model.plan());
		assertNull(model.plan());
		assertEquals("queued once, by the records", 1, model.getPlanBuildCount());
		// Today's prices arrive (the loot's coins): the plan at them is asked for, and the one on its way is stale.
		model.addCurrentPrices(priceRequests.get(priceRequests.size() - 1), SavedItems.todaysPrices());
		assertNull(model.plan());
		assertEquals(2, model.getPlanBuildCount());
		assertEquals(2, backgroundTasks.size());
		changes.clear();

		runBackground();
		assertEquals("told once, for the plan kept", Collections.singletonList(LedgerHistoryModel.Change.VIEWS),
			changes);
		TaskPlan plan = model.plan();
		assertEquals(20, plan.getKillsLeft());
		assertTrue("the faster setup", plan.getRecommendation().isCannon());
		assertEquals(TestSpots.SMOKE_ID, plan.getRecommendation().getSpot());
		assertSame("nothing changed", plan, model.plan());
		model.refreshLive();
		assertSame(plan, model.plan());
		assertEquals(2, model.getPlanBuildCount());

		// A kill: the plan for the kills left, asked for by the live check.
		tracker.observe("Abyssal demons", DEMONS, 19, false);
		model.refreshLive();
		runBackground();
		assertEquals(19, model.plan().getKillsLeft());
		assertEquals(3, model.getPlanBuildCount());

		// No task: no plan, and nothing is worked out.
		tracker.observe(null, SessionTracker.NO_CREATURE, 0, false);
		model.refreshLive();
		assertNull(model.plan());
		assertEquals(3, model.getPlanBuildCount());
	}

	@Test
	public void aPlanForOlderInputsIsDropped()
	{
		holdBackground = true;
		tracker.observe("Abyssal demons", DEMONS, 20, false);
		model.setRecords(ACCOUNT, setups());
		// A kill before the first plan is back: the plan for 19 kills left is asked for too.
		tracker.observe("Abyssal demons", DEMONS, 19, false);
		model.refreshLive();
		assertEquals(2, backgroundTasks.size());
		changes.clear();

		backgroundTasks.poll().run();
		assertTrue("the plan for 20 kills left tells no one", changes.isEmpty());
		assertNull("and is not shown", model.plan());
		backgroundTasks.poll().run();
		assertEquals(Collections.singletonList(LedgerHistoryModel.Change.VIEWS), changes);
		assertEquals(19, model.plan().getKillsLeft());
		assertEquals(2, model.getPlanBuildCount());

		// Another account's records: the first account's plan is never shown while the new one is worked out.
		model.setRecords(ACCOUNT + 1, setups().subList(0, 2));
		assertNull(model.plan());
		runBackground();
		assertEquals(2, model.plan().getAllSetups().getTasks());
	}

	@Test
	public void thePlanShownStaysThroughAKillAndAnotherLootPriceMode()
	{
		model.setSpots(TestSpots.TABLE);
		tracker.observe("Abyssal demons", DEMONS, 20, false);
		model.setRecords(ACCOUNT, setups());
		model.addCurrentPrices(priceRequests.get(priceRequests.size() - 1), SavedItems.todaysPrices());
		TaskPlan twenty = model.plan();
		assertEquals(20, twenty.getKillsLeft());

		// A kill: the plan for 19 kills left is worked out in the background, and the one for 20 is still shown.
		holdBackground = true;
		tracker.observe("Abyssal demons", DEMONS, 19, false);
		model.refreshLive();
		assertSame("kept while the new one is worked out", twenty, model.plan());
		runBackground();
		TaskPlan nineteen = model.plan();
		assertEquals(19, nineteen.getKillsLeft());

		// Another "Loot price" mode: the plan values nothing in it, so the one shown stays until it is worked out
		// again. Another task keeps nothing: no plan is shown until its own is back.
		model.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		assertSame("another mode", nineteen, model.plan());
		runBackground();
		assertNotNull(model.plan());
		tracker.observe("Gargoyles", 46, 20, false);
		model.refreshLive();
		assertNull("another task", model.plan());
	}

	@Test
	public void aSkippedOrBlockedEntryWorksOutNoNewPlan()
	{
		model.setSpots(TestSpots.TABLE);
		tracker.observe("Abyssal demons", DEMONS, 20, false);
		model.setRecords(ACCOUNT, setups());
		model.addCurrentPrices(priceRequests.get(priceRequests.size() - 1), SavedItems.todaysPrices());
		TaskPlan plan = model.plan();
		assertNotNull(plan);
		int builds = model.getPlanBuildCount();

		// The entries are no input of the plan: the same plan, and nothing worked out.
		assertTrue(model.setAssignments(ACCOUNT, Collections.singletonList(skipped("s", 2))));
		assertSame(plan, model.plan());
		model.refreshLive();
		assertEquals(builds, model.getPlanBuildCount());

		// New records are.
		model.setRecords(ACCOUNT, setups());
		assertEquals(builds + 1, model.getPlanBuildCount());
		assertNotSame(plan, model.plan());
	}

	@Test
	public void potionNamesJoinAPotionsDoseVariantsInThePlan()
	{
		final int prayer4 = 2_434;
		final int prayer3 = 139;
		List<LedgerRecord> records = new ArrayList<>();
		for (int i = 0; i < 3; i++)
		{
			// 8 doses of the 4-dose potion and 3 of the 3-dose one, as a task whose potion's key moved.
			LedgerRecord record = setup("p" + i, false);
			Map<Integer, Long> supplies = new HashMap<>();
			supplies.put(prayer4, 8L);
			supplies.put(prayer3, 3L);
			record.setSupplies(supplies);
			Map<Integer, Integer> doses = new HashMap<>();
			doses.put(prayer4, 4);
			doses.put(prayer3, 3);
			record.setSupplyDoses(doses);
			records.add(record);
		}
		tracker.observe("Abyssal demons", DEMONS, 20, false);
		model.setRecords(ACCOUNT, records);
		assertTrue("the potions' names are asked for", nameRequests.get(nameRequests.size() - 1)
			.containsAll(Arrays.asList(prayer4, prayer3)));
		List<TaskPlan.BringItem> apart = model.plan().getRecommendation().getBring();
		assertEquals("each on its own: 4 and 2 potions", 2, apart.size());

		Map<Integer, String> names = new HashMap<>();
		names.put(prayer4, "Prayer potion(4)");
		names.put(prayer3, "Prayer potion(3)");
		model.addItemNames(new HashSet<>(names.keySet()), names, null);
		List<TaskPlan.BringItem> joined = model.plan().getRecommendation().getBring();
		assertEquals(1, joined.size());
		assertEquals(prayer4, joined.get(0).getItemId());
		assertEquals("11 doses a task: 5.5 potions for 20 kills, rounded up", 6, joined.get(0).getQuantity());
		assertEquals(2, model.getPlanBuildCount());

		// Other names don't change the families: the plan isn't worked out again.
		model.addItemNames(Collections.singleton(WHIP), Collections.singletonMap(WHIP, "Abyssal whip"), null);
		model.plan();
		assertEquals(2, model.getPlanBuildCount());
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
		// The row carries its entry, the monster and the tooltip; the panel reads the outcome, date and master from the
		// entry.
		assertTrue(rows.get(0).getTooltip(), rows.get(0).getTooltip().startsWith("<html>Abyssal demons<br>Skipped"));
		assertSame(rows, model.assignmentRows());
		assertEquals(1, model.getAssignmentRowsBuildCount());

		model.setAssignments(ACCOUNT, Collections.singletonList(skipped("s", 1)));
		assertEquals(1, model.assignmentRows().size());
		assertEquals(2, model.getAssignmentRowsBuildCount());
	}

	@Test
	public void theTaskStatisticsAreKeptUntilTheirRecordsChangeNotWhenOnlyTheValuationDoes()
	{
		model.setRecords(ACCOUNT, Collections.singletonList(demons("a", 1)));
		int builds = model.getTaskStatisticsBuildCount();
		TaskStatistics stats = demonStats();
		assertEquals(1, stats.getSampleCount());
		assertSame(stats, demonStats());
		assertEquals(builds + 1, model.getTaskStatisticsBuildCount());

		// They value nothing: another Loot price mode, today's prices or hidden loot keep them.
		long valuation = model.valuationVersion();
		model.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		model.addCurrentPrices(Collections.singleton(WHIP), Collections.singletonMap(WHIP, 2_000_000L));
		model.setGroundItemsHidden(GroundItemsHidden.parse("Abyssal whip", ""));
		model.addItemNames(Collections.singleton(WHIP), Collections.singletonMap(WHIP, "Abyssal whip"), null);
		assertTrue("the whip is hidden", model.hiddenLoot().leavesOutAny(Collections.singleton(WHIP)));
		model.endCurrentPricesWait();
		assertTrue("the valuation changed", model.valuationVersion() > valuation);
		assertSame("the statistics were kept", stats, demonStats());
		assertEquals(builds + 1, model.getTaskStatisticsBuildCount());

		model.setRecords(ACCOUNT, Arrays.asList(demons("b", 2), demons("a", 1)));
		assertEquals("new records", 2, demonStats().getSampleCount());
		assertEquals(builds + 2, model.getTaskStatisticsBuildCount());
	}

	/** The Abyssal demons task's statistics from every location, as the live card asks for them. */
	private TaskStatistics demonStats()
	{
		return model.taskStatistics(DEMONS, "Abyssal demons", TaskSelection.ANY, null);
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
		assertEquals("a pending name is not asked for again", 2, nameRequests.size());
		assertNull("pending: not named yet", model.itemName(WHIP));
		assertFalse("pending: not failed yet", model.isItemNameFailed(WHIP));

		model.addItemNames(Collections.singleton(WHIP), Collections.emptyMap(), 150L);
		assertTrue(model.isItemNameFailed(WHIP));
		assertEquals(Long.valueOf(150L), model.natureRunePrice());
		model.requestItemNames(Collections.singleton(WHIP));
		assertEquals("a failed name is not asked for again", 2, nameRequests.size());
	}
}
