package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.gameval.VarPlayerID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Cannon tasks as wired in the plugin ({@link PluginHarness}): once the running session fires a cannonball (the
 * cannon's loaded ammo goes down, as the supplies diff counts it), the task box and the overlay's Task row add
 * "(cannon)", the History estimate and the historical time left (sidebar and overlay alike) narrow from every task
 * to cannon tasks when the history has some, and the saved task is shown as its own monster in the History list and
 * Trends. Picking the cannon up returns the cannonballs not fired, also when tracking started with the cannon already
 * loaded with granite cannonballs. {@link CannonTaskGroupingTest} covers the rules.
 */
public class HarnessCannonTaskTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int TASK = 49;
	private static final String DEVILS = "Dust devils";
	private static final String CANNON = DEVILS + " (cannon)";

	@Rule
	public final PluginHarness harness = new PluginHarness();

	private final AtomicLong now = new AtomicLong(1_000_000L);
	/** Cannonballs loaded in the cannon. */
	private int loaded;

	@Before
	public void setUp()
	{
		harness.defineItem(SupplyTracker.CANNONBALL, "Cannonball", 200, 1);
		harness.defineItem(SupplyTracker.GRANITE_CANNONBALL, "Granite cannonball", 400, 1);
		harness.startUp();
		harness.setInventory(PluginHarness.item(SupplyTracker.CANNONBALL, 100));
		harness.setTrackerClock(now::get);
		harness.setTask(TASK, DEVILS, 5, 5);
		harness.loginAndSettle(ACCOUNT);
	}

	/** Sets up the cannon and loads 30 cannonballs from the inventory: moved, not used. */
	private void setUpCannon()
	{
		harness.setVarp(VarPlayerID.DROPCANNON, 4);
		harness.tick();
		harness.setInventory(PluginHarness.item(SupplyTracker.CANNONBALL, 70));
		loaded = 30;
		harness.setVarp(VarPlayerID.ROCKTHROWER, loaded);
		harness.tick();
	}

	/** Picks the cannon up: its loaded cannonballs go back to the inventory on the same tick. */
	private void pickUpCannon()
	{
		harness.setInventory(PluginHarness.item(SupplyTracker.CANNONBALL, 70 + loaded));
		loaded = 0;
		harness.setVarp(VarPlayerID.ROCKTHROWER, 0);
		harness.setVarp(VarPlayerID.DROPCANNON, 0);
		harness.tick();
	}

	/**
	 * sets up the cannon and loads 30 granite cannonballs, then restarts the plugin, so tracking starts with the
	 * cannon already loaded and the new supplies tracker never saw which type went in.
	 */
	private void restartWithLoadedGraniteCannon()
	{
		harness.setInventory(PluginHarness.item(SupplyTracker.GRANITE_CANNONBALL, 100));
		harness.tick();
		harness.setVarp(VarPlayerID.DROPCANNON, 4);
		harness.tick();
		harness.setInventory(PluginHarness.item(SupplyTracker.GRANITE_CANNONBALL, 70));
		loaded = 30;
		harness.setVarp(VarPlayerID.ROCKTHROWER, loaded);
		harness.tick();

		harness.shutDown();
		harness.startUp();
		harness.ticks(PluginHarness.SETTLE_TICKS);
	}

	/** Picks up the granite-loaded cannon: its loaded cannonballs go back to the inventory on the same tick. */
	private void pickUpGraniteCannon()
	{
		harness.setInventory(PluginHarness.item(SupplyTracker.GRANITE_CANNONBALL, 70 + loaded));
		loaded = 0;
		harness.setVarp(VarPlayerID.ROCKTHROWER, 0);
		harness.setVarp(VarPlayerID.DROPCANNON, 0);
		harness.tick();
	}

	/** The cannon fires {@code count} of its loaded cannonballs. */
	private void fire(int count)
	{
		loaded -= count;
		harness.setVarp(VarPlayerID.ROCKTHROWER, loaded);
		harness.tick();
	}

	/** The task count goes down on the next tick (2 minutes later). */
	private void kill()
	{
		now.addAndGet(2 * 60_000L);
		harness.setRemaining(harness.tracker().getCurrentRemaining() - 1);
		harness.tick();
	}

	/** Completes the task and lets its grace window pass, so it is saved. */
	private void complete()
	{
		now.addAndGet(60_000L);
		harness.completeTask();
		harness.tick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS + 1);
	}

	private List<String> taskBoxRows()
	{
		AtomicReference<List<String>> rows = new AtomicReference<>();
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			rows.set(harness.ui().getTaskBox().getRows());
		});
		return rows.get();
	}

	/** The overlay's Task row value, or {@code null} when it has none. */
	private String overlayTask()
	{
		return overlayValue("Task");
	}

	/** Asserts the overlay's Task row shows {@code task} and its Left row {@code left}. */
	private void assertOverlayTask(String task, String left)
	{
		assertOverlayTask(null, task, left);
	}

	/** As {@link #assertOverlayTask(String, String)}, with {@code message} on failure. */
	private void assertOverlayTask(String message, String task, String left)
	{
		assertEquals(message, task, overlayTask());
		assertEquals(message, left, overlayValue("Left"));
	}

	/** The overlay's {@code label} row value, or {@code null} when it has none. */
	private String overlayValue(String label)
	{
		for (OverlayRows.Row row : OverlayRows.rows(harness.overlayData(), true, true, true, true))
		{
			if (label.equals(row.getLabel()))
			{
				return row.getValue();
			}
		}
		return null;
	}

	/** The sidebar's time-left line, after a refresh. */
	private String sidebarTimeLeft()
	{
		AtomicReference<String> text = new AtomicReference<>();
		harness.drain();
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			text.set(harness.ui().getTimeLeftText());
		});
		return text.get();
	}

	/** Asserts the sidebar and the overlay show {@code expected} as the time left. */
	private void assertTimeLeft(String message, String expected)
	{
		assertEquals(message, "Time left: " + expected, sidebarTimeLeft());
		assertEquals(message, expected, overlayValue("Time left"));
	}

	/** The History estimate's text and tooltip, after a refresh. */
	private String[] estimate()
	{
		String[] result = new String[2];
		harness.drain();
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			result[0] = harness.ui().getHistoryEstimate().getText();
			result[1] = harness.ui().getHistoryEstimate().getTooltip();
		});
		return result;
	}

	@Test
	public void aFiredCannonballShowsInTheTaskBoxAndOverlayAndNamesTheSavedTask()
	{
		assertTrue(harness.play());
		setUpCannon();
		kill();
		assertEquals("loading the cannon fires nothing", DEVILS + " · 4 left", taskBoxRows().get(0));
		assertOverlayTask(DEVILS, "4");

		fire(2);
		assertEquals(Long.valueOf(2), harness.tracker().getSupplies().toMap().get(SupplyTracker.CANNONBALL));
		assertEquals(CANNON + " · 4 left", taskBoxRows().get(0));
		assertOverlayTask(CANNON, "4");
		AtomicReference<String> tooltip = new AtomicReference<>();
		harness.onEdt(() -> tooltip.set(harness.ui().getTaskBox().getTaskTooltip()));
		assertTrue(tooltip.get(), tooltip.get().contains("Task: Dust devils<br>Cannon: cannonballs fired"));

		// Paused, it stays a cannon task.
		harness.pause();
		assertOverlayTask(CANNON, "4");
		assertTrue(harness.play());
		for (int i = 0; i < 3; i++)
		{
			kill();
		}
		complete();

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals(DEVILS, record.getMonster());
		assertTrue(record.cannonTask());
		assertEquals(CANNON, record.groupName());
	}

	@Test
	public void historyAndTrendsShowCannonTasksAsTheirOwnMonster()
	{
		// A task without a cannon, then one with.
		assertTrue(harness.play());
		for (int i = 0; i < 5; i++)
		{
			kill();
		}
		complete();

		harness.setTask(TASK, DEVILS, 5, 5);
		harness.tick();
		assertTrue(harness.play());
		setUpCannon();
		fire(3);
		for (int i = 0; i < 5; i++)
		{
			kill();
		}
		complete();

		assertEquals(2, harness.records(ACCOUNT).size());
		harness.drain();
		AtomicReference<List<String>> filter = new AtomicReference<>();
		AtomicReference<List<String>> titles = new AtomicReference<>();
		harness.onEdt(() ->
		{
			filter.set(harness.ui().getHistory().getFilterOptions());
			titles.set(harness.ui().getHistory().getTitleTexts());
		});
		assertEquals(Arrays.asList(LedgerHistory.ALL, DEVILS, CANNON), filter.get());
		assertEquals("newest first", Arrays.asList(CANNON + " | Completed", DEVILS + " | Completed"), titles.get());

		harness.onEdt(() -> harness.ui().getTrends().clickTitle());
		harness.drain();
		AtomicReference<List<String>> rows = new AtomicReference<>();
		harness.onEdt(() -> rows.set(harness.ui().getTrends().getRows()));
		assertEquals(rows.get().toString(), 2, rows.get().size());
		assertTrue(rows.get().toString(), rows.get().stream().anyMatch(row -> row.contains(". " + CANNON + " |")));
		assertTrue(rows.get().toString(), rows.get().stream().anyMatch(row -> row.contains(". " + DEVILS + " |")));
	}

	@Test
	public void theEstimatePoolsUntilACannonballIsFiredThenCountsCannonTasksOnly()
	{
		// 6 minutes a kill without a cannon, 3 with: 4.5 pooled.
		List<LedgerRecord> history = new ArrayList<>();
		for (int i = 0; i < 3; i++)
		{
			history.add(saved(false));
			history.add(saved(true));
		}
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, history));
		harness.drain();

		assertTrue(estimate()[0], estimate()[0].startsWith("History (6 tasks)"));
		assertTrue(estimate()[1], estimate()[1].contains("Counts cannon tasks too, until you fire a cannonball."));

		assertTrue(harness.play());
		setUpCannon();
		kill();
		assertTrue("still pooled without a cannonball fired", estimate()[0].startsWith("History (6 tasks)"));
		assertTimeLeft("4 kills at 4.5 minutes", "~18m");

		fire(1);
		assertEquals(TaskSelection.ANY.withCannon(true), harness.tracker().getCurrentTaskSelection());
		assertTrue(estimate()[0], estimate()[0].startsWith("History (3 tasks)"));
		assertTrue(estimate()[1], estimate()[1].contains("Only your cannon tasks count."));
		assertTimeLeft("4 kills at 3 minutes", "~12m");
	}

	@Test
	public void withoutCannonTasksInTheHistoryTheEstimateKeepsPoolingAfterACannonball()
	{
		List<LedgerRecord> history = new ArrayList<>();
		for (int i = 0; i < 3; i++)
		{
			history.add(saved(false));
		}
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, history));
		harness.drain();

		assertTrue(harness.play());
		setUpCannon();
		fire(1);
		kill();
		assertEquals(TaskSelection.ANY.withCannon(true), harness.tracker().getCurrentTaskSelection());
		assertEquals("the task box still shows the cannon", CANNON + " · 4 left", taskBoxRows().get(0));
		String[] estimate = estimate();
		assertTrue(estimate[0], estimate[0].startsWith("History (3 tasks)"));
		assertTrue(estimate[1], estimate[1].contains(HistoryEstimateSection.NO_CANNON_TASKS_NOTE));
		assertFalse(estimate[1], estimate[1].contains("Only your cannon tasks count."));
		assertTimeLeft("4 kills at 6 minutes", "~24m");

		// A first cannon task in the history: from then on only it counts.
		List<LedgerRecord> withCannon = new ArrayList<>(history);
		withCannon.add(saved(true));
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, withCannon));
		harness.drain();
		estimate = estimate();
		assertTrue(estimate[0], estimate[0].startsWith("History (1 task)"));
		assertTrue(estimate[1], estimate[1].contains("Only your cannon tasks count."));
		assertTimeLeft("4 kills at 3 minutes", "~12m");
	}

	@Test
	public void aCannonLoadedAndPickedUpWithoutFiringIsNoCannonTask()
	{
		assertTrue(harness.play());
		setUpCannon();
		kill();
		pickUpCannon();
		assertEquals(0L, harness.tracker().getSupplies().get(SupplyTracker.CANNONBALL));
		assertEquals(TaskSelection.ANY, harness.tracker().getCurrentTaskSelection());
		assertEquals(DEVILS + " · 4 left", taskBoxRows().get(0));
		for (int i = 0; i < 4; i++)
		{
			kill();
		}
		complete();

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertFalse(record.cannonTask());
		assertFalse(String.valueOf(record.getSupplies()), CannonTasks.fired(record.getSupplies()));
		assertEquals(DEVILS, record.groupName());
	}

	@Test
	public void aCannonPickedUpAfterFiringCountsOnlyTheFiredCannonballs()
	{
		assertTrue(harness.play());
		setUpCannon();
		fire(4);
		kill();
		pickUpCannon();
		assertEquals(4L, harness.tracker().getSupplies().get(SupplyTracker.CANNONBALL));
		assertEquals(TaskSelection.ANY.withCannon(true), harness.tracker().getCurrentTaskSelection());
		assertEquals(CANNON + " · 4 left", taskBoxRows().get(0));
		for (int i = 0; i < 4; i++)
		{
			kill();
		}
		complete();

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.cannonTask());
		assertEquals(Long.valueOf(4), record.getSupplies().get(SupplyTracker.CANNONBALL));
		assertEquals(CANNON, record.groupName());
	}

	@Test
	public void aGraniteCannonLoadedBeforeTrackingStartsAndPickedUpWithoutFiringIsNoCannonTask()
	{
		restartWithLoadedGraniteCannon();
		assertTrue(harness.play());
		kill();
		pickUpGraniteCannon();
		assertEquals(0L, harness.tracker().getSupplies().get(SupplyTracker.CANNONBALL));
		assertEquals(0L, harness.tracker().getSupplies().get(SupplyTracker.GRANITE_CANNONBALL));
		assertEquals(TaskSelection.ANY, harness.tracker().getCurrentTaskSelection());
		assertEquals(DEVILS + " · 4 left", taskBoxRows().get(0));
		for (int i = 0; i < 4; i++)
		{
			kill();
		}
		complete();

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertFalse(record.cannonTask());
		assertFalse(String.valueOf(record.getSupplies()), CannonTasks.fired(record.getSupplies()));
		assertEquals(DEVILS, record.groupName());
	}

	@Test
	public void aGraniteCannonPickupSplitOverTwoTicksNetsTheFiredGraniteCannonballs()
	{
		restartWithLoadedGraniteCannon();
		assertTrue(harness.play());
		fire(4);
		// The varps go to 0 a tick before the cannonballs reach the inventory: counted under the guess for now.
		final int returning = loaded;
		loaded = 0;
		harness.setVarp(VarPlayerID.ROCKTHROWER, 0);
		harness.setVarp(VarPlayerID.DROPCANNON, 0);
		harness.tick();
		assertEquals(30L, harness.tracker().getSupplies().get(SupplyTracker.CANNONBALL));
		harness.setInventory(PluginHarness.item(SupplyTracker.GRANITE_CANNONBALL, 70 + returning));
		harness.tick();

		assertEquals(0L, harness.tracker().getSupplies().get(SupplyTracker.CANNONBALL));
		assertEquals(4L, harness.tracker().getSupplies().get(SupplyTracker.GRANITE_CANNONBALL));
	}

	@Test
	public void aGraniteCannonFiringOnItsPickupTickNetsTheFiredGraniteCannonballs()
	{
		restartWithLoadedGraniteCannon();
		assertTrue(harness.play());
		fire(4);
		// One more shot on the pickup tick: 25 of the 26 the cannon held come back.
		loaded -= 1;
		pickUpGraniteCannon();

		assertEquals(0L, harness.tracker().getSupplies().get(SupplyTracker.CANNONBALL));
		assertEquals(5L, harness.tracker().getSupplies().get(SupplyTracker.GRANITE_CANNONBALL));
	}

	@Test
	public void aGraniteCannonLoadedBeforeTrackingStartsCountsOnlyTheFiredGraniteCannonballs()
	{
		restartWithLoadedGraniteCannon();
		assertTrue(harness.play());
		fire(4);
		// The loaded type is not known yet, but the count is: the task box shows the cannon at once.
		assertEquals(CANNON + " · 5 left", taskBoxRows().get(0));
		kill();
		pickUpGraniteCannon();
		assertEquals("moved to the type the pickup returned", 0L,
			harness.tracker().getSupplies().get(SupplyTracker.CANNONBALL));
		assertEquals(4L, harness.tracker().getSupplies().get(SupplyTracker.GRANITE_CANNONBALL));
		assertEquals(TaskSelection.ANY.withCannon(true), harness.tracker().getCurrentTaskSelection());
		assertEquals(CANNON + " · 4 left", taskBoxRows().get(0));
		for (int i = 0; i < 4; i++)
		{
			kill();
		}
		complete();

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.cannonTask());
		assertEquals(Long.valueOf(4), record.getSupplies().get(SupplyTracker.GRANITE_CANNONBALL));
		assertEquals(0L, record.getSupplies().getOrDefault(SupplyTracker.CANNONBALL, 0L).longValue());
		assertEquals(CANNON, record.groupName());
	}

	/**
	 * A completed dust devils task of 10 kills, saved earlier: an hour long, or half an hour as a cannon task, which
	 * fired 50 cannonballs.
	 */
	private static LedgerRecord saved(boolean cannon)
	{
		LedgerRecord record = new LedgerRecord();
		record.setId(java.util.UUID.randomUUID().toString());
		record.setCreatureId(TASK);
		record.setMonster(DEVILS);
		record.setKills(10);
		record.setInitialAmount(10);
		record.setOriginalAmount(10);
		record.setActiveMillis((cannon ? 30 : 60) * 60_000L);
		record.setEndedAt(500_000L);
		record.setCompleted(true);
		record.setSupplies(cannon ? Collections.singletonMap(SupplyTracker.CANNONBALL, 50L) : null);
		record.setLootGeValue(cannon ? 400_000L : 100_000L);
		record.setSuppliesGeValue(0L);
		return record;
	}
}
