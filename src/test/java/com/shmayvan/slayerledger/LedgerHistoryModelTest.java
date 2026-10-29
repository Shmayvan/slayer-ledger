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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link LedgerHistoryModel} without the panel or the plugin: the history it holds, its version, and the
 * decisions it makes on its own (the new-task chat preview and the overlay's historical time per kill).
 * {@link HarnessHistoryModelTest} covers the wiring.
 */
public class LedgerHistoryModelTest
{
	private static final long ACCOUNT = 1L;
	private static final long OTHER_ACCOUNT = 2L;
	private static final int DEMONS = 42;
	private static final long MINUTE = 60_000L;
	private static final int SHARK = 385;

	private final List<String> posted = new ArrayList<>();
	private final List<OverlayRows.HistoricalPace> published = new ArrayList<>();
	private final List<Set<Integer>> priceRequests = new ArrayList<>();
	private long now = 1_000_000L;
	private SessionTracker tracker;
	private LedgerHistoryModel model;

	@Before
	public void setUp()
	{
		tracker = new SessionTracker(() -> now);
		model = new LedgerHistoryModel(tracker, LootPriceMode.GE, regions ->
		{
		}, priceRequests::add, ids ->
		{
		}, posted::add, published::add,
			Runnable::run, Runnable::run);
	}

	/**
	 * A completed Abyssal demons task ended at {@code endedAt}: 10 kills in 10 minutes, loot 100K GE, 1 bank trip. It
	 * has totals but no per-item prices, so it keeps them, marked {@code ~}.
	 */
	private static LedgerRecord task(String id, long endedAt)
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
		record.setBankTrips(1);
		record.setEndedAt(endedAt);
		return record;
	}

	private static AssignmentEntry entry(String id, long endedAt)
	{
		AssignmentEntry entry = new AssignmentEntry();
		entry.setId(id);
		entry.setMonster("Abyssal demons");
		entry.setEndedAt(endedAt);
		entry.setOutcome(AssignmentOutcome.SKIPPED);
		return entry;
	}

	/** Today's GE prices of the bones (5,000 gp) and coins (1 gp) of the hidden-loot test. */
	private static Map<Integer, Long> prices(int bones, int coins)
	{
		Map<Integer, Long> prices = new HashMap<>();
		prices.put(bones, 5_000L);
		prices.put(coins, 1L);
		return prices;
	}

	/** The tracker sees an Abyssal demons task with {@code remaining} kills left. */
	private void demons(int remaining)
	{
		tracker.observe("Abyssal demons", DEMONS, remaining, false);
	}

	@Test
	public void theVersionChangesWithEveryHistoryChange()
	{
		long version = model.version();
		assertTrue(model.setRecords(ACCOUNT, Collections.singletonList(task("a", 1))));
		assertTrue(model.version() > version);

		version = model.version();
		assertTrue(model.setAssignments(ACCOUNT, Collections.singletonList(entry("s", 1))));
		assertTrue(model.version() > version);

		version = model.version();
		assertFalse("another account's entries are ignored",
			model.setAssignments(OTHER_ACCOUNT, Collections.singletonList(entry("t", 2))));
		assertEquals(version, model.version());
		assertEquals(1, model.assignments().size());

		model.clearRecords(ACCOUNT, null);
		assertTrue(model.version() > version);
		assertTrue(model.records().isEmpty());
		assertTrue("a message drops the entries too", model.assignments().isEmpty());
		assertFalse(model.isLoaded());
	}

	@Test
	public void theViewsAreUnmodifiableSortedCopies()
	{
		List<LedgerRecord> records = new ArrayList<>(Arrays.asList(task("old", 1), task("new", 2)));
		List<AssignmentEntry> entries = new ArrayList<>(Arrays.asList(entry("old", 1), entry("new", 2)));
		model.setRecords(ACCOUNT, records);
		model.setAssignments(ACCOUNT, entries);
		records.clear();
		entries.clear();

		assertEquals("newest first, and not the caller's list", Arrays.asList("new", "old"),
			Arrays.asList(model.records().get(0).getId(), model.records().get(1).getId()));
		assertEquals(Arrays.asList("new", "old"),
			Arrays.asList(model.assignments().get(0).getId(), model.assignments().get(1).getId()));
		assertTrue(model.isLoaded());
		assertEquals(ACCOUNT, model.account());
		assertUnmodifiable(model.records(), task("x", 3));
		assertUnmodifiable(model.assignments(), entry("x", 3));
	}

	private static <T> void assertUnmodifiable(List<T> list, T element)
	{
		try
		{
			list.add(element);
		}
		catch (UnsupportedOperationException expected)
		{
			return;
		}
		throw new AssertionError("the list can be changed");
	}

	@Test
	public void thePreviewWaitsForTheRecordsThenPostsOnce()
	{
		demons(20);
		model.requestAssignmentPreview(ACCOUNT, DEMONS, "Abyssal demons");
		assertTrue("another account's (none) history is held", posted.isEmpty());
		assertFalse("dropped: the history held is not the account's", model.isAssignmentPreviewPending());

		model.clearRecords(ACCOUNT, null);
		model.requestAssignmentPreview(ACCOUNT, DEMONS, "Abyssal demons");
		model.checkAssignmentPreview();
		assertTrue("waits while the records load", model.isAssignmentPreviewPending());
		assertTrue(posted.isEmpty());

		model.setRecords(ACCOUNT, Arrays.asList(SavedItems.withItems(task("a", 1)), SavedItems.withItems(task("b", 2)),
			SavedItems.withItems(task("c", 3))));
		model.checkAssignmentPreview();
		assertTrue("waits for today's prices", posted.isEmpty());
		model.addCurrentPrices(priceRequests.get(priceRequests.size() - 1), SavedItems.todaysPrices());
		model.checkAssignmentPreview();
		model.refreshLive();
		model.checkAssignmentPreview();
		assertEquals(Collections.singletonList(
			"Abyssal demons (20): ~0:20:00 · +180K (GE) · ~2 bank trips (from 3 tasks)"), posted);
		assertFalse(model.isAssignmentPreviewPending());
	}

	@Test
	public void thePreviewWaitsForTheNamesThatDecideTheHiddenLoot()
	{
		final int bones = 526;
		final int coins = ItemValuation.COINS;
		// Ground Items' built-in list hides both; only the bones leave the value (coins always count).
		model.setGroundItemsHidden(GroundItemsHidden.parse("Coins, Bones", ""));
		List<LedgerRecord> records = new ArrayList<>();
		for (String id : Arrays.asList("a", "b", "c"))
		{
			// 50K of bones and 50K of coins: the 100K GE of task().
			LedgerRecord record = task(id, 1);
			Map<Integer, Long> loot = new HashMap<>();
			loot.put(bones, 10L);
			loot.put(coins, 50_000L);
			Map<Integer, Long> prices = new HashMap<>();
			prices.put(bones, 5_000L);
			prices.put(coins, 1L);
			record.setLoot(loot);
			record.setUnitGePrices(prices);
			record.setUnitHaPrices(prices);
			// Supplies are valued per item at today's prices too: 10K of supplies, as task() saved.
			record.setSupplies(Collections.singletonMap(SHARK, 10L));
			records.add(record);
		}
		model.clearRecords(ACCOUNT, null);
		demons(20);
		model.requestAssignmentPreview(ACCOUNT, DEMONS, "Abyssal demons");

		model.setRecords(ACCOUNT, records);
		model.refreshLive();
		assertTrue("waits for today's prices", posted.isEmpty());
		Map<Integer, Long> today = new HashMap<>(prices(bones, coins));
		today.put(SHARK, 1_000L);
		model.addCurrentPrices(priceRequests.get(priceRequests.size() - 1), today);
		model.refreshLive();
		assertTrue("waits for the loot items' names", posted.isEmpty());
		assertTrue(model.isAssignmentPreviewPending());

		Map<Integer, String> names = new HashMap<>();
		names.put(bones, "Bones");
		names.put(coins, "Coins");
		model.addItemNames(new HashSet<>(Arrays.asList(bones, coins)), names, null);
		assertEquals("posted with the bones left out and the coins counted", Collections.singletonList(
			"Abyssal demons (20): ~0:20:00 · +80K (GE) · ~2 bank trips (from 3 tasks)"), posted);
		assertFalse(model.isAssignmentPreviewPending());
	}

	@Test
	public void thePreviewIsDroppedWhenTheTaskChanges()
	{
		model.setRecords(ACCOUNT, Collections.singletonList(task("a", 1)));
		model.clearRecords(ACCOUNT, null);
		demons(20);
		model.requestAssignmentPreview(ACCOUNT, DEMONS, "Abyssal demons");
		tracker.observe("Gargoyles", 46, 15, false);
		model.checkAssignmentPreview();

		assertFalse(model.isAssignmentPreviewPending());
		model.setRecords(ACCOUNT, Collections.singletonList(task("a", 1)));
		model.checkAssignmentPreview();
		assertTrue(posted.isEmpty());
	}

	@Test
	public void thePreviewWaitsForTodaysPrices()
	{
		LedgerRecord record = task("a", 1);
		record.setLoot(Collections.singletonMap(995, 1L));
		model.setRecords(ACCOUNT, Collections.singletonList(record));
		model.setRecords(ACCOUNT, Collections.singletonList(record));
		assertEquals("asked once", 1, priceRequests.size());
		assertTrue(model.isWaitingForCurrentPrices());

		demons(20);
		model.requestAssignmentPreview(ACCOUNT, DEMONS, "Abyssal demons");
		assertTrue(model.isAssignmentPreviewPending());

		model.addCurrentPrices(priceRequests.get(0), Collections.singletonMap(995, 1L));
		model.endCurrentPricesWait();
		model.checkAssignmentPreview();
		assertEquals(1, posted.size());
	}

	@Test
	public void anExpiredWaitForTodaysPricesEndsInTheModelAndPostsThePreview()
	{
		final int[] redraws = new int[1];
		model.setListener(change ->
		{
			if (change == LedgerHistoryModel.Change.PRICED_VIEWS)
			{
				redraws[0]++;
			}
		});
		LedgerRecord record = task("a", 1);
		record.setLoot(Collections.singletonMap(995, 1L));
		// Another account's records never wait; the same account's again do.
		model.setRecords(ACCOUNT, Collections.singletonList(record));
		model.setRecords(ACCOUNT, Collections.singletonList(record));
		assertTrue(model.isWaitingForCurrentPrices());
		demons(20);
		model.requestAssignmentPreview(ACCOUNT, DEMONS, "Abyssal demons");

		assertFalse("not run out yet", model.endExpiredCurrentPricesWait());
		model.refreshLive();
		assertTrue(posted.isEmpty());
		assertEquals(0, redraws[0]);

		// The answer never comes.
		model.expireCurrentPricesWait();
		model.refreshLive();
		assertFalse(model.isWaitingForCurrentPrices());
		assertEquals(1, posted.size());
		assertEquals("the panel is told to redraw once", 1, redraws[0]);
		assertFalse("nothing more to end", model.endExpiredCurrentPricesWait());
		model.refreshLive();
		assertEquals("posted once", 1, posted.size());
		assertEquals(1, redraws[0]);
	}

	@Test
	public void theOverlayPaceFollowsTheRecordsAndTheSession()
	{
		demons(12);
		assertTrue(tracker.play(ACCOUNT));
		model.setRecords(ACCOUNT, Collections.singletonList(task("a", 1)));
		OverlayRows.HistoricalPace pace = published.get(published.size() - 1);
		assertNotNull("published when the records arrive", pace);
		assertEquals(new OverlayRows.HistoricalPace(tracker.getSessionId(), MINUTE), pace);
		assertEquals(pace, model.historicalPace());

		model.clearRecords(ACCOUNT, null);
		assertNull("no history, no pace", published.get(published.size() - 1));

		model.setRecords(ACCOUNT, Collections.singletonList(task("a", 1)));
		assertNotNull(published.get(published.size() - 1));
		assertTrue(tracker.stop());
		model.refreshLive();
		assertNull("no session, no pace", published.get(published.size() - 1));

		int count = published.size();
		model.shutDown();
		model.refreshLive();
		model.setRecords(ACCOUNT, Collections.emptyList());
		assertEquals("nothing is published after shutDown", count, published.size());
	}

	@Test
	public void todaysPricesArrivingRevalueTheCachedViewsOnce()
	{
		LedgerRecord record = task("a", 1);
		record.setLoot(Collections.singletonMap(995, 1L));
		record.setUnitGePrices(Collections.singletonMap(995, 1L));
		model.setRecords(ACCOUNT, Collections.singletonList(record));
		assertEquals("asked once, without a box to tick", 1, priceRequests.size());
		demons(20);

		model.advice();
		model.overallTotals(OverallTotals.Grouping.ALL);
		model.ranking(TaskRanking.Metric.PROFIT);
		long valuation = model.valuationVersion();
		int adviceBuilds = model.getAdviceBuildCount();
		int totalsBuilds = model.getOverallTotalsBuildCount();
		int rankingBuilds = model.getRankingBuildCount();
		model.advice();
		assertEquals("cached until the prices arrive", adviceBuilds, model.getAdviceBuildCount());

		model.addCurrentPrices(priceRequests.get(0), Collections.singletonMap(995, 1L));
		assertTrue("today's prices revalue the history", model.valuationVersion() > valuation);
		model.advice();
		model.overallTotals(OverallTotals.Grouping.ALL);
		model.ranking(TaskRanking.Metric.PROFIT);
		assertEquals(adviceBuilds + 1, model.getAdviceBuildCount());
		assertEquals(totalsBuilds + 1, model.getOverallTotalsBuildCount());
		assertEquals(rankingBuilds + 1, model.getRankingBuildCount());
		model.advice();
		model.overallTotals(OverallTotals.Grouping.ALL);
		model.ranking(TaskRanking.Metric.PROFIT);
		assertEquals("then cached again", adviceBuilds + 1, model.getAdviceBuildCount());
		assertEquals(totalsBuilds + 1, model.getOverallTotalsBuildCount());
		assertEquals(rankingBuilds + 1, model.getRankingBuildCount());
	}

	/** A record of {@link #task} with one of each of {@code items} in its loot. */
	private static LedgerRecord withLoot(String id, long endedAt, int... items)
	{
		LedgerRecord record = task(id, endedAt);
		Map<Integer, Long> loot = new HashMap<>();
		for (int item : items)
		{
			loot.put(item, 1L);
		}
		record.setLoot(loot);
		return record;
	}

	@Test
	public void anAnswerWithoutANewPriceRedrawsNothingButStillEndsTheWait()
	{
		final int[] changes = new int[2];
		model.setListener(change ->
		{
			if (change == LedgerHistoryModel.Change.CURRENT_PRICES)
			{
				changes[0]++;
			}
			else if (change == LedgerHistoryModel.Change.PRICED_VIEWS)
			{
				changes[1]++;
			}
		});
		LedgerRecord coins = withLoot("a", 1, 995);
		// Another account's records never wait.
		model.setRecords(ACCOUNT, Collections.singletonList(coins));
		model.addCurrentPrices(priceRequests.get(0), Collections.singletonMap(995, 1L));
		assertEquals("a new price redraws", 1, changes[0]);
		long version = model.valuationVersion();

		// The same price again, and an answer that found nothing: nothing new, nothing redrawn.
		model.addCurrentPrices(Collections.singleton(995), Collections.singletonMap(995, 1L));
		model.addCurrentPrices(Collections.singleton(7), Collections.emptyMap());
		assertEquals(version, model.valuationVersion());
		assertEquals(1, changes[0]);

		// A changed price is new.
		model.addCurrentPrices(Collections.singleton(995), Collections.singletonMap(995, 2L));
		assertTrue(model.valuationVersion() > version);
		assertEquals(2, changes[0]);
		version = model.valuationVersion();

		// The last answer a wait asked for ends it even when it adds nothing.
		model.setRecords(ACCOUNT, Arrays.asList(coins, withLoot("b", 2, 7)));
		assertTrue(model.isWaitingForCurrentPrices());
		model.addCurrentPrices(priceRequests.get(priceRequests.size() - 1), Collections.emptyMap(),
			Collections.emptySet());
		assertFalse(model.isWaitingForCurrentPrices());
		assertFalse(model.isCurrentPricesPending());
		assertEquals("the views are redrawn once", 1, changes[1]);
		assertEquals(version, model.valuationVersion());
		assertEquals(2, changes[0]);
	}

	@Test
	public void aWaitHoldsForPricesLookedUpAgainUntilTheyArriveRunOutOrTheHistoryIsCleared()
	{
		final int[] redraws = new int[1];
		model.setListener(change ->
		{
			if (change == LedgerHistoryModel.Change.PRICED_VIEWS)
			{
				redraws[0]++;
			}
		});
		// As at client start, before RuneLite's price list has loaded: the answer has no price for item 7.
		LedgerRecord first = withLoot("a", 1, 995, 7);
		model.setRecords(ACCOUNT, Collections.singletonList(first));
		model.setRecords(ACCOUNT, Collections.singletonList(first));
		assertTrue(model.isWaitingForCurrentPrices());
		model.addCurrentPrices(priceRequests.get(0), Collections.singletonMap(995, 1L), Collections.singleton(7));
		assertFalse("answered", model.isCurrentPricesPending());
		assertTrue("but item 7 is looked up again", model.isWaitingForCurrentPrices());
		assertEquals(0, redraws[0]);

		// The retry finds it: the wait ends with one redraw.
		model.addCurrentPrices(Collections.singleton(7), Collections.singletonMap(7, 100L));
		assertFalse(model.isWaitingForCurrentPrices());
		assertEquals(1, redraws[0]);

		// A price that never turns up: the wait still runs out.
		LedgerRecord second = withLoot("b", 2, 8);
		model.setRecords(ACCOUNT, Arrays.asList(first, second));
		model.addCurrentPrices(priceRequests.get(priceRequests.size() - 1), Collections.emptyMap(),
			Collections.singleton(8));
		assertTrue(model.isWaitingForCurrentPrices());
		assertFalse("not run out yet", model.endExpiredCurrentPricesWait());
		model.expireCurrentPricesWait();
		assertTrue(model.endExpiredCurrentPricesWait());
		assertFalse(model.isWaitingForCurrentPrices());
		assertEquals(2, redraws[0]);

		// And clearing the history ends it too.
		model.setRecords(ACCOUNT, Arrays.asList(first, second, withLoot("c", 3, 9)));
		model.addCurrentPrices(priceRequests.get(priceRequests.size() - 1), Collections.emptyMap(),
			Collections.singleton(9));
		assertTrue(model.isWaitingForCurrentPrices());
		model.clearRecords(ACCOUNT, null);
		assertFalse(model.isWaitingForCurrentPrices());

		// Prices looked up again while nothing waits hold nothing back later.
		model.setRecords(OTHER_ACCOUNT, Collections.singletonList(withLoot("d", 4, 10)));
		assertFalse(model.isWaitingForCurrentPrices());
		model.addCurrentPrices(priceRequests.get(priceRequests.size() - 1), Collections.emptyMap(),
			Collections.singleton(10));
		model.setRecords(OTHER_ACCOUNT, Arrays.asList(withLoot("d", 4, 10), withLoot("e", 5, 11)));
		assertTrue(model.isWaitingForCurrentPrices());
		model.addCurrentPrices(priceRequests.get(priceRequests.size() - 1), Collections.singletonMap(11, 5L),
			Collections.emptySet());
		assertFalse("only the ids of this wait hold it", model.isWaitingForCurrentPrices());
	}
}
