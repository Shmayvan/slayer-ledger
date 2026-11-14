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
 * decisions it makes on its own (the assignment chat line, and the overlay's historical time per kill).
 * {@link HarnessHistoryModelTest} covers the wiring.
 */
public class LedgerHistoryModelTest
{
	private static final long ACCOUNT = 1L;
	private static final long OTHER_ACCOUNT = 2L;
	private static final int DEMONS = 42;
	private static final long MINUTE = 60_000L;
	private static final int SHARK = 385;
	private static final int DEATH_RUNE = 560;

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
	public void theChatLineWaitsForTheRecordsThenPostsOnce()
	{
		demons(20);
		model.requestAssignmentLine(ACCOUNT, DEMONS, "Abyssal demons");
		assertTrue("another account's (none) history is held", posted.isEmpty());
		assertFalse("dropped: the history held is not the account's", model.isAssignmentLinePending());

		model.clearRecords(ACCOUNT, null);
		model.requestAssignmentLine(ACCOUNT, DEMONS, "Abyssal demons");
		model.checkAssignmentLine();
		assertTrue("waits while the records load", model.isAssignmentLinePending());
		assertTrue(posted.isEmpty());

		model.setRecords(ACCOUNT, Arrays.asList(SavedItems.withItems(task("a", 1)), SavedItems.withItems(task("b", 2)),
			SavedItems.withItems(task("c", 3))));
		model.checkAssignmentLine();
		assertTrue("waits for today's prices", posted.isEmpty());
		model.addCurrentPrices(priceRequests.get(priceRequests.size() - 1), SavedItems.todaysPrices());
		model.checkAssignmentLine();
		model.refreshLive();
		model.checkAssignmentLine();
		// The tasks have no spot or style, so they are in no setup: their time for the 20 kills left.
		assertEquals(Collections.singletonList(UNKNOWN_SETUP_LINE), posted);
		assertFalse(model.isAssignmentLinePending());
	}

	/** The chat line for 20 Abyssal demons from three {@link #task}s, which have no spot or style. */
	private static final String UNKNOWN_SETUP_LINE =
		"<colHIGHLIGHT>Abyssal demons x20:<colNORMAL> ~20 min (3 tasks, setup unknown).";

	/**
	 * A {@link #task} at the Smoke Dungeon done with barrage, using 400 death runes; {@code cannon} for a cannon task
	 * that took twice as long.
	 */
	private static LedgerRecord barrage(String id, boolean cannon)
	{
		LedgerRecord record = task(id, 1);
		record.setActiveMillis((cannon ? 20 : 10) * MINUTE);
		record.setSpotId(TestSpots.SMOKE_ID);
		record.setSupplies(Collections.singletonMap(DEATH_RUNE, 400L));
		SetupTags.detected(cannon, SetupStyle.BARRAGE, GearFocus.UNKNOWN, SetupOverrides.NONE).copyTo(record);
		return record;
	}

	@Test
	public void theChatLineNamesTheRecommendationItsItemsAndTheCounterpartOnceTheNamesArrive()
	{
		final List<Set<Integer>> nameRequests = new ArrayList<>();
		model = new LedgerHistoryModel(tracker, LootPriceMode.GE, regions ->
		{
		}, priceRequests::add, nameRequests::add, posted::add, published::add, Runnable::run, Runnable::run);
		model.setSpots(TestSpots.TABLE);
		model.setRecords(ACCOUNT, Arrays.asList(barrage("a", false), barrage("b", false), barrage("c", false),
			barrage("d", true)));
		demons(20);
		model.requestAssignmentLine(ACCOUNT, DEMONS, "Abyssal demons");
		assertTrue("waits for the death rune's name", posted.isEmpty());
		assertTrue(nameRequests.get(nameRequests.size() - 1).contains(DEATH_RUNE));

		model.addItemNames(Collections.singleton(DEATH_RUNE), Collections.singletonMap(DEATH_RUNE, "Death rune"), null);
		assertEquals(Collections.singletonList("<colHIGHLIGHT>Abyssal demons x20:<colNORMAL> barrage, Smoke Dungeon,"
			+ " ~20 min; ~800 death (3 tasks). Cannon: ~40 min (1)."), posted);
		assertFalse(model.isAssignmentLinePending());
		model.refreshLive();
		assertEquals("once", 1, posted.size());
	}

	@Test
	public void withNoCompletedTaskTheChatLineSaysSo()
	{
		model.setRecords(ACCOUNT, Collections.emptyList());
		tracker.observe("Gargoyles", 46, 15, false);
		model.requestAssignmentLine(ACCOUNT, 46, "Gargoyles");
		assertEquals(Collections.singletonList("<colHIGHLIGHT>Gargoyles x15:<colNORMAL> no completed tasks yet."),
			posted);
	}

	@Test
	public void theChatLineWaitsForTheNamesThatDecideTheHiddenLoot()
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
		model.requestAssignmentLine(ACCOUNT, DEMONS, "Abyssal demons");

		model.setRecords(ACCOUNT, records);
		model.refreshLive();
		assertTrue("waits for today's prices", posted.isEmpty());
		Map<Integer, Long> today = new HashMap<>(prices(bones, coins));
		today.put(SHARK, 1_000L);
		model.addCurrentPrices(priceRequests.get(priceRequests.size() - 1), today);
		model.refreshLive();
		assertTrue("waits for the loot items' names", posted.isEmpty());
		assertTrue(model.isAssignmentLinePending());

		Map<Integer, String> names = new HashMap<>();
		names.put(bones, "Bones");
		names.put(coins, "Coins");
		model.addItemNames(new HashSet<>(Arrays.asList(bones, coins)), names, null);
		assertEquals("posted once the hidden loot is known", Collections.singletonList(UNKNOWN_SETUP_LINE), posted);
		assertFalse(model.isAssignmentLinePending());
	}

	@Test
	public void theChatLineIsDroppedWhenTheTaskChanges()
	{
		model.setRecords(ACCOUNT, Collections.singletonList(task("a", 1)));
		model.clearRecords(ACCOUNT, null);
		demons(20);
		model.requestAssignmentLine(ACCOUNT, DEMONS, "Abyssal demons");
		tracker.observe("Gargoyles", 46, 15, false);
		model.checkAssignmentLine();

		assertFalse(model.isAssignmentLinePending());
		model.setRecords(ACCOUNT, Collections.singletonList(task("a", 1)));
		model.checkAssignmentLine();
		assertTrue(posted.isEmpty());
	}

	@Test
	public void theChatLineWaitsForTodaysPrices()
	{
		LedgerRecord record = task("a", 1);
		record.setLoot(Collections.singletonMap(995, 1L));
		model.setRecords(ACCOUNT, Collections.singletonList(record));
		model.setRecords(ACCOUNT, Collections.singletonList(record));
		assertEquals("asked once", 1, priceRequests.size());
		assertTrue(model.isWaitingForCurrentPrices());

		demons(20);
		model.requestAssignmentLine(ACCOUNT, DEMONS, "Abyssal demons");
		assertTrue(model.isAssignmentLinePending());
		assertNull("no plan while the views wait for today's prices", model.plan());
		assertEquals(0, model.getPlanBuildCount());

		model.addCurrentPrices(priceRequests.get(0), Collections.singletonMap(995, 1L));
		assertEquals("the last price asked for ends the wait, and the plan is worked out at once", 1,
			model.getPlanBuildCount());
		assertEquals(1, posted.size());
		model.endCurrentPricesWait();
		model.checkAssignmentLine();
		assertEquals(1, posted.size());
	}

	@Test
	public void anExpiredWaitForTodaysPricesEndsInTheModelAndPostsTheChatLine()
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
		model.requestAssignmentLine(ACCOUNT, DEMONS, "Abyssal demons");

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

	/** Region names asked for by the model built with {@link #modelAskingForNames}. */
	private final List<Set<Integer>> regionRequests = new ArrayList<>();
	/** Item names asked for by the model built with {@link #modelAskingForNames}. */
	private final List<Set<Integer>> nameRequests = new ArrayList<>();

	/** A model that records the region and item names it asks for (never answered), with the spot table set. */
	private void modelAskingForNames()
	{
		model = new LedgerHistoryModel(tracker, LootPriceMode.GE, regionRequests::add, priceRequests::add,
			nameRequests::add, posted::add, published::add, Runnable::run, Runnable::run);
		model.setSpots(TestSpots.TABLE);
	}

	/** Three {@link #barrage} tasks (no cannon) at {@code spot}. */
	private static List<LedgerRecord> barrageAt(String spot)
	{
		List<LedgerRecord> records = new ArrayList<>();
		for (String id : Arrays.asList("a", "b", "c"))
		{
			LedgerRecord record = barrage(id, false);
			record.setSpotId(spot);
			records.add(record);
		}
		return records;
	}

	@Test
	public void theChatLineWaitsForARegionsNameThatArrivesAfterThePlan()
	{
		modelAskingForNames();
		model.setRecords(ACCOUNT, barrageAt(SlayerSpots.regionKey(TestSpots.UNLISTED)));
		demons(20);
		model.requestAssignmentLine(ACCOUNT, DEMONS, "Abyssal demons");
		assertNotNull("the plan is here", model.exactPlan());
		assertTrue("the line asked for the region's name", regionRequests.stream()
			.anyMatch(regions -> regions.contains(TestSpots.UNLISTED)));

		model.addItemNames(Collections.singleton(DEATH_RUNE), Collections.singletonMap(DEATH_RUNE, "Death rune"), null);
		assertTrue("waits for the region's name", posted.isEmpty());
		assertTrue(model.isAssignmentLinePending());

		model.addLocationNames(Collections.singleton(TestSpots.UNLISTED),
			Collections.singletonMap(TestSpots.UNLISTED, "Cave"));
		assertEquals(Collections.singletonList("<colHIGHLIGHT>Abyssal demons x20:<colNORMAL> barrage, Cave, ~20 min;"
			+ " ~800 death (3 tasks)."), posted);
		assertFalse(model.isAssignmentLinePending());
	}

	@Test
	public void theChatLineWaitsForItsCreaturesPotionNamesThatArriveAfterThePlan()
	{
		final int bloodRune = 565;
		final int prayer4 = 2_434;
		final int prayer3 = 139;
		final int superRestore4 = 3_024;
		modelAskingForNames();
		List<LedgerRecord> records = new ArrayList<>();
		for (LedgerRecord record : barrageAt(TestSpots.SMOKE_ID))
		{
			// 400 death and 200 blood runes, and prayer potions under two ids: worth less than the runes.
			Map<Integer, Long> supplies = new HashMap<>();
			supplies.put(DEATH_RUNE, 400L);
			supplies.put(bloodRune, 200L);
			supplies.put(prayer4, 8L);
			supplies.put(prayer3, 3L);
			record.setSupplies(supplies);
			Map<Integer, Integer> doses = new HashMap<>();
			doses.put(prayer4, 4);
			doses.put(prayer3, 3);
			record.setSupplyDoses(doses);
			records.add(record);
		}
		// Another creature's task with a potion whose name never comes: it doesn't hold this task's line.
		LedgerRecord gargoyles = task("g", 2);
		gargoyles.setCreatureId(46);
		gargoyles.setMonster("Gargoyles");
		gargoyles.setSupplies(Collections.singletonMap(superRestore4, 4L));
		gargoyles.setSupplyDoses(Collections.singletonMap(superRestore4, 4));
		records.add(gargoyles);
		model.setRecords(ACCOUNT, records);
		Map<Integer, Long> today = new HashMap<>();
		today.put(DEATH_RUNE, 200L);
		today.put(bloodRune, 300L);
		model.addCurrentPrices(priceRequests.get(priceRequests.size() - 1), today);
		demons(20);
		model.requestAssignmentLine(ACCOUNT, DEMONS, "Abyssal demons");
		assertNotNull("the plan is here", model.exactPlan());
		assertTrue("the potions' names are asked for", nameRequests.stream()
			.anyMatch(ids -> ids.containsAll(Arrays.asList(prayer4, prayer3, superRestore4))));

		// The two items the line shows are named, but a prayer potion's name may still join its doses.
		Map<Integer, String> runes = new HashMap<>();
		runes.put(DEATH_RUNE, "Death rune");
		runes.put(bloodRune, "Blood rune");
		model.addItemNames(new HashSet<>(runes.keySet()), runes, null);
		assertTrue("waits for the potions' names", posted.isEmpty());
		assertTrue(model.isAssignmentLinePending());

		Map<Integer, String> potions = new HashMap<>();
		potions.put(prayer4, "Prayer potion(4)");
		potions.put(prayer3, "Prayer potion(3)");
		model.addItemNames(new HashSet<>(potions.keySet()), potions, null);
		assertEquals(Collections.singletonList("<colHIGHLIGHT>Abyssal demons x20:<colNORMAL> barrage, Smoke Dungeon,"
			+ " ~20 min; ~800 death, ~400 blood (3 tasks)."), posted);
		assertTrue("the Gargoyles' potion is still on its way", model.isItemNamePending(superRestore4));
		assertEquals("joined in the plan the line came from", 1, model.exactPlan().getRecommendation().getBring()
			.stream().filter(item -> item.getItemId() == prayer4 || item.getItemId() == prayer3).count());
	}

	@Test
	public void anAccountSwitchWhileTheChatLineWaitsDropsIt()
	{
		model.setSpots(TestSpots.TABLE);
		List<LedgerRecord> records = barrageAt(TestSpots.SMOKE_ID);
		model.setRecords(ACCOUNT, records);
		demons(20);
		model.requestAssignmentLine(ACCOUNT, DEMONS, "Abyssal demons");
		assertTrue("waits for the death rune's name", model.isAssignmentLinePending());

		// Another account logs in before it arrives, and the first one again before the other's history has loaded.
		model.clearRecords(OTHER_ACCOUNT, null);
		assertFalse("dropped at once", model.isAssignmentLinePending());
		model.clearRecords(ACCOUNT, null);
		model.setRecords(ACCOUNT, records);
		answerPrices();
		model.addItemNames(Collections.singleton(DEATH_RUNE), Collections.singletonMap(DEATH_RUNE, "Death rune"), null);
		model.refreshLive();
		assertNotNull("everything a line needs is here", model.exactPlan());
		assertTrue(posted.isEmpty());
	}

	/** Answers every price asked for so far, with none found, so no wait for today's prices holds anything. */
	private void answerPrices()
	{
		for (Set<Integer> requested : new ArrayList<>(priceRequests))
		{
			model.addCurrentPrices(requested, Collections.emptyMap());
		}
		assertFalse(model.isWaitingForCurrentPrices());
	}

	@Test
	public void aLogoutWhileTheChatLineWaitsDropsIt()
	{
		model.setSpots(TestSpots.TABLE);
		List<LedgerRecord> records = barrageAt(TestSpots.SMOKE_ID);
		// The history still loading at the assignment.
		model.clearRecords(ACCOUNT, null);
		demons(20);
		model.requestAssignmentLine(ACCOUNT, DEMONS, "Abyssal demons");
		assertTrue("waits for the records", model.isAssignmentLinePending());

		// A logout, and a login to the same account with the same task: a task there at a login gets no line.
		model.clearRecords(SessionTracker.NO_ACCOUNT, null);
		assertFalse("dropped at once", model.isAssignmentLinePending());
		assertFalse(model.isAssignmentLineOverdue());
		model.clearRecords(ACCOUNT, null);
		model.setRecords(ACCOUNT, records);
		answerPrices();
		model.addItemNames(Collections.singleton(DEATH_RUNE), Collections.singletonMap(DEATH_RUNE, "Death rune"), null);
		model.refreshLive();
		assertNotNull("everything a line needs is here", model.exactPlan());
		assertTrue(posted.isEmpty());
	}

	@Test
	public void afterFiveSecondsTheChatLineIsPostedWithTheNamesThereAre()
	{
		modelAskingForNames();
		model.setRecords(ACCOUNT, barrageAt(SlayerSpots.regionKey(TestSpots.UNLISTED)));
		demons(20);
		model.requestAssignmentLine(ACCOUNT, DEMONS, "Abyssal demons");
		assertTrue("waits for the item's and the region's names", model.isAssignmentLinePending());
		assertFalse(model.isAssignmentLineOverdue());

		now += LedgerHistoryModel.ASSIGNMENT_LINE_NAMES_WAIT_MILLIS - 1;
		assertFalse(model.isAssignmentLineOverdue());
		model.checkAssignmentLine();
		assertTrue("still waits", posted.isEmpty());

		// The names never come: the settled tick asks for a check once the 5 seconds are over.
		now += 1;
		assertTrue(model.isAssignmentLineOverdue());
		model.checkAssignmentLine();
		assertEquals(Collections.singletonList("<colHIGHLIGHT>Abyssal demons x20:<colNORMAL> barrage, Region "
			+ TestSpots.UNLISTED + ", ~20 min; ~800 item " + DEATH_RUNE + " (3 tasks)."), posted);
		assertFalse(model.isAssignmentLinePending());
		assertFalse("nothing more for the ticks to check", model.isAssignmentLineOverdue());

		// Names arriving later post nothing more.
		model.addItemNames(Collections.singleton(DEATH_RUNE), Collections.singletonMap(DEATH_RUNE, "Death rune"), null);
		model.addLocationNames(Collections.singleton(TestSpots.UNLISTED),
			Collections.singletonMap(TestSpots.UNLISTED, "Cave"));
		assertEquals(1, posted.size());
	}

	@Test
	public void aLineStillWaitingForItsRecordsAfterTheNamesWaitIsLeftToThem()
	{
		model.setSpots(TestSpots.TABLE);
		model.clearRecords(ACCOUNT, null);
		demons(20);
		model.requestAssignmentLine(ACCOUNT, DEMONS, "Abyssal demons");

		now += LedgerHistoryModel.ASSIGNMENT_LINE_NAMES_WAIT_MILLIS;
		assertTrue(model.isAssignmentLineOverdue());
		model.checkAssignmentLine();
		assertTrue("the records are still loading", model.isAssignmentLinePending());
		assertFalse("the ticks stop asking: setRecords checks it", model.isAssignmentLineOverdue());

		// The records load, then today's prices; the names wait is over, so the death rune is named by its id.
		model.setRecords(ACCOUNT, barrageAt(TestSpots.SMOKE_ID));
		assertTrue("waits for today's prices", posted.isEmpty());
		answerPrices();
		assertEquals(Collections.singletonList("<colHIGHLIGHT>Abyssal demons x20:<colNORMAL> barrage, Smoke Dungeon,"
			+ " ~20 min; ~800 item " + DEATH_RUNE + " (3 tasks)."), posted);
	}

	@Test
	public void theOverlayPaceFollowsTheRecordsAndTheSession()
	{
		demons(12);
		assertTrue(tracker.startNow(ACCOUNT));
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
		long valuation = model.valuationVersion();
		int adviceBuilds = model.getAdviceBuildCount();
		int totalsBuilds = model.getOverallTotalsBuildCount();
		model.advice();
		assertEquals("cached until the prices arrive", adviceBuilds, model.getAdviceBuildCount());

		model.addCurrentPrices(priceRequests.get(0), Collections.singletonMap(995, 1L));
		assertTrue("today's prices revalue the history", model.valuationVersion() > valuation);
		model.advice();
		model.overallTotals(OverallTotals.Grouping.ALL);
		assertEquals(adviceBuilds + 1, model.getAdviceBuildCount());
		assertEquals(totalsBuilds + 1, model.getOverallTotalsBuildCount());
		model.advice();
		model.overallTotals(OverallTotals.Grouping.ALL);
		assertEquals("then cached again", adviceBuilds + 1, model.getAdviceBuildCount());
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
