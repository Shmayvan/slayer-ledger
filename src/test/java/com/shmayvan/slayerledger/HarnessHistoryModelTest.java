package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * The plugin's {@link LedgerHistoryModel} as wired ({@link PluginHarness}): the advisor's plan and the
 * overlay's historical time per kill never need the panel's refresh timer. {@link #setUp} stops that timer and
 * no test calls the panel's {@code refresh()}, so each result below comes from the one trigger the test names:
 * <ul>
 * <li>a new assignment's plan: the settled tick whose live inputs changed ({@code refreshLive}), then the plan worked
 * out off the EDT;</li>
 * <li>a plan waiting for today's prices whose answer was lost: a later settled tick ending the expired wait
 * ({@code endExpiredCurrentPricesWait});</li>
 * <li>a plan waiting for the records: the model's own {@code setRecords} (called directly, not through the panel, and
 * with no tick before the check);</li>
 * <li>the overlay's pace at Play, Stop and Play: the plugin's {@code refreshLive} after the panel action; after a
 * kill: the settled tick whose live inputs changed; when the history empties: the model's {@code setRecords};</li>
 * <li>records arriving during a session: the model's {@code setRecords} (written by the plugin);</li>
 * <li>the player moving to a region with other history: the settled tick whose live inputs changed
 * ({@code refreshLive});</li>
 * <li>a region name arriving or being forgotten: the model's {@code addLocationNames} and
 * {@code clearLocationNames} (written by the plugin), which leave the pace as it is: tasks match by
 * spot, not by name.</li>
 * </ul>
 */
public class HarnessHistoryModelTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final long MINUTE = 60_000L;
	private static final int DEMONS = 42;
	/** A region the spot table doesn't list, so it has no name until the player gives it one. */
	private static final int UNNAMED = TestSpots.UNLISTED;
	private static final int SMOKE = 12_946;
	private static final int CATACOMBS = 6_557;


	@Rule
	public final PluginHarness harness = new PluginHarness();

	/** The tracker's clock, so active time (and the time-left estimate) is under the test's control. */
	private final AtomicLong now = new AtomicLong(1_000_000_000L);

	@Before
	public void setUp() throws IOException
	{
		save(task(), task(), task());
		SavedItems.define(harness);
		harness.startUp();
		// As with the sidebar closed for good: the panel's one-second refresh never runs.
		harness.onEdt(() -> harness.panel().shutDown());
		useTestClock();
		harness.setInventory();
		harness.setPlayerRegion(UNNAMED);
	}

	/** Points the plugin's session tracker at {@link #now} (it reads the system clock otherwise). */
	private void useTestClock()
	{
		harness.setTrackerClock(now::get);
	}

	/**
	 * A completed Abyssal demons task: 10 kills in 10 minutes, loot 100K GE, supplies 10K, 1 bank trip; with its items
	 * ({@link SavedItems}).
	 */
	private static LedgerRecord task()
	{
		return task(10, null);
	}

	/** As {@link #task()}, but taking {@code minutes} minutes, at the spot {@code spotId} ({@code null}: Unknown). */
	private static LedgerRecord task(int minutes, String spotId)
	{
		LedgerRecord record = new LedgerRecord();
		record.setId("task-" + System.nanoTime());
		record.setCreatureId(DEMONS);
		record.setMonster("Abyssal demons");
		record.setKills(10);
		record.setActiveMillis(minutes * MINUTE);
		record.setCompleted(true);
		record.setLootGeValue(100_000L);
		record.setLootHaValue(200_000L);
		record.setSuppliesGeValue(10_000L);
		record.setBankTrips(1);
		record.setSpotId(spotId);
		return SavedItems.withItems(record);
	}

	/** An unfinished task with one Ranarr seed, so the history asks the plugin for its price today. */
	private static LedgerRecord ranarrRecord()
	{
		LedgerRecord record = new LedgerRecord();
		record.setId("ranarr");
		record.setCreatureId(DEMONS);
		record.setMonster("Abyssal demons");
		record.setKills(1);
		record.setActiveMillis(MINUTE);
		record.setLoot(Collections.singletonMap(ItemID.RANARR_SEED, 1L));
		record.setUnitGePrices(Collections.singletonMap(ItemID.RANARR_SEED, 40_000L));
		record.setUnitHaPrices(Collections.singletonMap(ItemID.RANARR_SEED, 20L));
		record.setLootGeValue(40_000L);
		record.setLootHaValue(20L);
		record.setSuppliesGeValue(0L);
		record.setSuppliesHaValue(0L);
		return record;
	}

	/**
	 * Runs the queued client-thread work with the plugin's history model unset, so the answers it would send the model
	 * (today's prices, region names) are lost, as if they never came.
	 */
	private void loseClientThreadAnswers()
	{
		final LedgerHistoryModel model = harness.historyModel();
		setPluginHistoryModel(null);
		try
		{
			harness.runClientThread();
		}
		finally
		{
			setPluginHistoryModel(model);
		}
		harness.flushEdt();
	}

	private void setPluginHistoryModel(LedgerHistoryModel model)
	{
		harness.plugin.setHistoryModel(model);
	}

	/** Saves the records to the account's ledger file, read when the account logs in. */
	private void save(LedgerRecord... records) throws IOException
	{
		LedgerStore store = new LedgerStore(Filepath.Unchecked.getRooted(harness.ledgerDirectory().toPath()),
			harness.gson);
		for (LedgerRecord record : records)
		{
			store.append(ACCOUNT, record);
		}
	}

	/** The advisor's plan the model shows now, after the queued work has run; {@code null} for none. */
	private TaskPlan plan()
	{
		harness.drain();
		AtomicReference<TaskPlan> plan = new AtomicReference<>();
		harness.onEdt(() -> plan.set(harness.historyModel().plan()));
		return plan.get();
	}

	/** Asserts the plan is for 20 Abyssal demons from the three saved tasks. */
	private void assertPlanForTwentyDemons()
	{
		TaskPlan plan = plan();
		assertNotNull("a plan", plan);
		assertEquals(20, plan.getKillsLeft());
		assertEquals(3, plan.getAllSetups().getTasks());
	}

	/** The overlay's time left now ({@code ~12 min}), with every group on; {@code null} without a session. */
	private String overlayTimeLeft()
	{
		for (OverlayRows.Row row : OverlayRows.rows(harness.overlayData(), OverlayRows.Groups.ALL))
		{
			if (row.getKind() == OverlayRows.Kind.PROGRESS)
			{
				return row.getRight();
			}
		}
		return null;
	}

	@Test
	public void aNewAssignmentsPlanIsWorkedOutWithoutThePanelTimer()
	{
		harness.setRemaining(0);
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(AssignmentWatch.SYNC_MAX_TICKS);
		assertNull("no task, no plan", plan());
		harness.setTask(DEMONS, "Abyssal demons", 20, 20);
		harness.tick();

		assertPlanForTwentyDemons();
		final AtomicReference<Integer> builds = new AtomicReference<>();
		harness.onEdt(() -> builds.set(harness.historyModel().getPlanBuildCount()));
		harness.ticks(5);
		final AtomicReference<Integer> after = new AtomicReference<>();
		harness.onEdt(() -> after.set(harness.historyModel().getPlanBuildCount()));
		assertEquals("worked out once", builds.get(), after.get());
	}

	@Test
	public void aPlanWaitingForTodaysPricesIsWorkedOutWhenTheWaitRunsOutWithoutThePanelTimer()
	{
		harness.setRemaining(0);
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(AssignmentWatch.SYNC_MAX_TICKS);
		// The saved tasks' prices arrived after logging in, so nothing waits.

		// A record with an item not priced yet: the history waits for its price, whose answer is lost.
		final List<LedgerRecord> records = new ArrayList<>(harness.records(ACCOUNT));
		records.add(ranarrRecord());
		final AtomicBoolean waiting = new AtomicBoolean();
		harness.onEdt(() ->
		{
			harness.historyModel().setRecords(ACCOUNT, records);
			waiting.set(harness.historyModel().isWaitingForCurrentPrices());
		});
		assertTrue("waits for the price", waiting.get());
		loseClientThreadAnswers();

		harness.setTask(DEMONS, "Abyssal demons", 20, 20);
		harness.tick();
		assertNull("waits for today's prices", plan());

		// The 3 seconds pass with nothing else changing; the next settled tick ends the wait.
		harness.onEdt(() -> harness.historyModel().expireCurrentPricesWait());
		harness.tick();
		harness.onEdt(() -> waiting.set(harness.historyModel().isWaitingForCurrentPrices()));
		assertFalse(waiting.get());
		TaskPlan plan = plan();
		assertNotNull("worked out at the prices there are", plan);
		assertEquals(20, plan.getKillsLeft());
		assertEquals("the unfinished task is no sample", 3, plan.getAllSetups().getTasks());
	}

	@Test
	public void aPlanWaitingForTheRecordsIsWorkedOutWhenTheyArriveWithoutThePanelTimer()
	{
		harness.setRemaining(0);
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(AssignmentWatch.SYNC_MAX_TICKS);
		// As while the ledger is still being read.
		harness.onEdt(() -> harness.historyModel().clearRecords(ACCOUNT, SlayerLedgerPanel.LOADING_MESSAGE));
		harness.setTask(DEMONS, "Abyssal demons", 20, 20);
		harness.ticks(3);
		assertNull("waits for the records", plan());

		// Straight to the model, not through the panel: the model asks for the plan on its own. No tick runs before
		// the check.
		final List<LedgerRecord> records = harness.records(ACCOUNT);
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, records));
		assertPlanForTwentyDemons();
	}

	@Test
	public void theOverlayGetsTheHistoricalPaceWithoutThePanelTimer()
	{
		harness.setTask(DEMONS, "Abyssal demons", 12, 12);
		harness.loginAndSettle(ACCOUNT);
		assertEquals("no session yet", null, overlayTimeLeft());

		assertTrue(harness.startNow());
		assertEquals("12 kills at the history's minute a kill", "~12 min", overlayTimeLeft());

		// 1 kill in 30 seconds: too little for the live pace, so still the history, for the 11 left.
		now.addAndGet(MINUTE / 2);
		harness.setRemaining(11);
		harness.tick();
		assertEquals("~11 min", overlayTimeLeft());

		// A new session gets the pace for its own id at once.
		harness.endTask();
		harness.trackAgain();
		assertTrue(harness.startNow());
		assertEquals("~11 min", overlayTimeLeft());

		// The history emptied: cleared at once, not on the next refresh.
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, Collections.emptyList()));
		assertEquals(LedgerFormat.UNKNOWN, overlayTimeLeft());
	}

	@Test
	public void recordsArrivingDuringASessionFeedTheOverlayWithoutThePanelTimer()
	{
		harness.setTask(DEMONS, "Abyssal demons", 12, 12);
		harness.loginAndSettle(ACCOUNT);
		harness.onEdt(() -> harness.historyModel().clearRecords(ACCOUNT, SlayerLedgerPanel.LOADING_MESSAGE));
		assertTrue(harness.startNow());
		assertEquals(LedgerFormat.UNKNOWN, overlayTimeLeft());

		final List<LedgerRecord> records = harness.records(ACCOUNT);
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, records));
		assertEquals("~12 min", overlayTimeLeft());
	}

	@Test
	public void aTickMovingToARegionWithOtherHistoryChangesTheOverlay()
	{
		harness.setPlayerRegion(SMOKE);
		harness.setTask(DEMONS, "Abyssal demons", 12, 12);
		harness.loginAndSettle(ACCOUNT);
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT,
			Arrays.asList(task(10, TestSpots.SMOKE_ID), task(30, TestSpots.CATACOMBS_ID))));
		harness.drain();
		assertTrue(harness.startNow());
		assertEquals("a minute a kill at Smoke Dungeon", "~12 min", overlayTimeLeft());

		// No kill yet, so the session's location is where the player stands.
		harness.setPlayerRegion(CATACOMBS);
		harness.tick();
		assertEquals("three minutes a kill in the Catacombs", "~36 min", overlayTimeLeft());
	}

	@Test
	public void aRegionNameArrivingOrForgottenLeavesTheOverlayAtTheSpot()
	{
		harness.setTask(DEMONS, "Abyssal demons", 12, 12);
		harness.loginAndSettle(ACCOUNT);
		final List<LedgerRecord> records = new ArrayList<>(harness.records(ACCOUNT));
		records.add(task(30, SlayerSpots.regionKey(UNNAMED)));
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, records));
		harness.drain();
		assertTrue(harness.startNow());
		assertEquals("the region has no name, but its spot matches: that task only", "~36 min", overlayTimeLeft());

		harness.onEdt(() -> harness.historyModel().addLocationNames(Collections.singleton(UNNAMED),
			Collections.singletonMap(UNNAMED, "Edgeville")));
		assertEquals("named: still that task", "~36 min", overlayTimeLeft());

		// Another RuneScape profile: the name is forgotten. The names asked for again are lost, so only the model's
		// clearLocationNames can have told the overlay.
		harness.onEdt(() -> harness.historyModel().clearLocationNames());
		loseClientThreadAnswers();
		assertEquals("~36 min", overlayTimeLeft());
	}
}
