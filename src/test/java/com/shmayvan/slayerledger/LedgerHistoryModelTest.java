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
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link LedgerHistoryModel} without the panel or the plugin: the history it holds, its version, and the
 * decisions it makes on its own (when the advisor's plan is worked out, and the overlay's historical time per kill).
 * {@link HarnessHistoryModelTest} covers the wiring.
 */
public class LedgerHistoryModelTest
{
	private static final long ACCOUNT = 1L;
	private static final long OTHER_ACCOUNT = 2L;
	private static final int DEMONS = 42;
	private static final long MINUTE = 60_000L;
	private static final int SHARK = 385;

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
		}, published::add, Runnable::run, Runnable::run);
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
	public void thePlanWaitsForTodaysPrices()
	{
		LedgerRecord record = task("a", 1);
		record.setLoot(Collections.singletonMap(995, 1L));
		model.setRecords(ACCOUNT, Collections.singletonList(record));
		model.setRecords(ACCOUNT, Collections.singletonList(record));
		assertEquals("asked once", 1, priceRequests.size());
		assertTrue(model.isWaitingForCurrentPrices());

		demons(20);
		model.refreshLive();
		assertNull("no plan while the views wait for today's prices", model.plan());
		assertEquals(0, model.getPlanBuildCount());

		model.addCurrentPrices(priceRequests.get(0), Collections.singletonMap(995, 1L));
		assertEquals("the last price asked for ends the wait, and the plan is worked out at once", 1,
			model.getPlanBuildCount());
		assertNotNull(model.plan());
		model.endCurrentPricesWait();
		assertEquals("the same inputs: nothing worked out again", 1, model.getPlanBuildCount());
	}

	@Test
	public void anExpiredWaitForTodaysPricesEndsInTheModel()
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

		assertFalse("not run out yet", model.endExpiredCurrentPricesWait());
		model.refreshLive();
		assertNull("no plan yet", model.plan());
		assertEquals(0, redraws[0]);

		// The answer never comes.
		model.expireCurrentPricesWait();
		model.refreshLive();
		assertFalse(model.isWaitingForCurrentPrices());
		assertNotNull("the plan at the prices there are", model.plan());
		assertEquals("the panel is told to redraw once", 1, redraws[0]);
		assertFalse("nothing more to end", model.endExpiredCurrentPricesWait());
		model.refreshLive();
		assertEquals(1, redraws[0]);
	}

	@Test
	public void theListenerIsToldOfTheLiveInputsOnlyWhenTheyChange()
	{
		final List<LedgerHistoryModel.Change> changes = new ArrayList<>();
		model.setListener(changes::add);
		demons(20);
		model.refreshLive();
		assertEquals("the first check sees new inputs", Collections.singletonList(LedgerHistoryModel.Change.LIVE),
			changes);

		// Checks with nothing new, as a panel action's would be: nobody is told.
		changes.clear();
		model.refreshLive();
		model.refreshLive();
		assertTrue(changes.toString(), changes.isEmpty());

		// A session of the task starts with no kill (a restored one is the same to the inputs): no plan input changed,
		// so only this tells the advisor card.
		assertTrue(tracker.startNow(ACCOUNT));
		model.refreshLive();
		assertEquals(Collections.singletonList(LedgerHistoryModel.Change.LIVE), changes);
		changes.clear();
		model.refreshLive();
		assertTrue(changes.toString(), changes.isEmpty());

		// A kill.
		demons(19);
		model.refreshLive();
		assertEquals(Collections.singletonList(LedgerHistoryModel.Change.LIVE), changes);

		// Stopped: nothing is told any more.
		changes.clear();
		model.shutDown();
		demons(18);
		model.refreshLive();
		assertTrue(changes.toString(), changes.isEmpty());
	}


	@Test
	public void theOverlayPaceFollowsTheRecordsAndTheSession()
	{
		demons(12);
		assertTrue(tracker.startNow(ACCOUNT));
		model.setRecords(ACCOUNT, Collections.singletonList(task("a", 1)));
		OverlayRows.HistoricalPace pace = published.get(published.size() - 1);
		assertNotNull("published when the records arrive", pace);
		assertEquals(new OverlayRows.HistoricalPace(tracker.getSessionId(), MINUTE, null), pace);
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

		model.plan();
		model.overallTotals(OverallTotals.Grouping.ALL);
		long valuation = model.valuationVersion();
		int planBuilds = model.getPlanBuildCount();
		int totalsBuilds = model.getOverallTotalsBuildCount();
		model.plan();
		assertEquals("cached until the prices arrive", planBuilds, model.getPlanBuildCount());

		model.addCurrentPrices(priceRequests.get(0), Collections.singletonMap(995, 1L));
		assertTrue("today's prices revalue the history", model.valuationVersion() > valuation);
		model.plan();
		model.overallTotals(OverallTotals.Grouping.ALL);
		assertEquals(planBuilds + 1, model.getPlanBuildCount());
		assertEquals(totalsBuilds + 1, model.getOverallTotalsBuildCount());
		model.plan();
		model.overallTotals(OverallTotals.Grouping.ALL);
		assertEquals("then cached again", planBuilds + 1, model.getPlanBuildCount());
		assertEquals(totalsBuilds + 1, model.getOverallTotalsBuildCount());
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
