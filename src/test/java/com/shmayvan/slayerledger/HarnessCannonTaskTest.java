package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * Cannon tasks as wired in the plugin ({@link PluginHarness}): once the running session's Cannon tag is Yes
 * (at least half of its kills made while the cannon fired, or before its first kill's context any cannonball
 * fired), the live task card and the overlay's Task row add "(cannon)", the task's statistics and with them the
 * historical time left (sidebar and overlay alike) narrow from every task to cannon tasks when the history has some,
 * and the saved task is shown as its own monster in the History list and Overall totals By monster. Picking the cannon
 * up returns the cannonballs not fired, also when tracking started with the cannon already loaded with granite
 * cannonballs. {@link CannonTaskGroupingTest} covers the rules.
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

	/** The tooltip of the live card's Cannon toggle, as the player sees it after a refresh. */
	private String liveCannonTooltip()
	{
		AtomicReference<String> tooltip = new AtomicReference<>();
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			tooltip.set(harness.ui().getLiveSetupTooltip("cannon"));
		});
		return tooltip.get();
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

	/**
	 * The overlay's task name ({@code "Task"}), kills left ({@code "Left"}: {@code 12}, or {@code ?} while unknown) or
	 * time left ({@code "Time left"}), or {@code null} when it shows none.
	 */
	private String overlayValue(String label)
	{
		for (OverlayRows.Row row : OverlayRows.rows(harness.overlayData(), OverlayRows.Groups.ALL))
		{
			if ("Task".equals(label) && row.getKind() == OverlayRows.Kind.IDENTITY)
			{
				return row.getLeft();
			}
			if ("Time left".equals(label) && row.getKind() == OverlayRows.Kind.PROGRESS)
			{
				return row.getRight();
			}
			if ("Left".equals(label) && row.getKind() == OverlayRows.Kind.PROGRESS)
			{
				return row.getLeft().endsWith(" left") ? row.getLeft().substring(0, row.getLeft().length() - 5)
					: OverlayRows.UNKNOWN_REMAINING;
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

	/**
	 * Asserts the sidebar and the overlay show {@code expected} ({@code ~18m}) as the time left: the same estimate, in
	 * the overlay's form ({@code ~18 min}, DESIGN_PRINCIPLES.md 5.5).
	 */
	private void assertTimeLeft(String message, String expected)
	{
		assertEquals(message, "Time left: " + expected, sidebarTimeLeft());
		assertEquals(message, expected.replace("m", " min"), overlayValue("Time left"));
	}

	/** How many tasks the current task's statistics count for the session's cannon use, as the time left reads them. */
	private int statisticsTasks()
	{
		AtomicReference<Integer> tasks = new AtomicReference<>();
		harness.drain();
		harness.onEdt(() -> tasks.set(harness.historyModel().taskStatistics(TASK, DEVILS,
			harness.tracker().getCurrentTaskSelection(), harness.historyModel().currentLocation()).getSampleCount()));
		return tasks.get();
	}

	@Test
	public void halfTheKillsByCannonShowInTheTaskBoxAndOverlayAndNameTheSavedTask()
	{
		assertTrue(harness.startNow());
		setUpCannon();
		kill();
		harness.tick();
		assertEquals("loading the cannon fires nothing", DEVILS, taskBoxRows().get(0));
		assertOverlayTask(DEVILS, "4");

		fire(2);
		assertEquals(Long.valueOf(2), harness.tracker().getSupplies().toMap().get(SupplyTracker.CANNONBALL));
		assertEquals("a cannonball fired alone is no cannon task", DEVILS, taskBoxRows().get(0));
		// A kill while the cannon fires: 1 of 2 kills, half, so the Cannon tag is Yes once its context is counted.
		kill();
		harness.tick();
		assertEquals(CANNON, taskBoxRows().get(0));
		assertOverlayTask(CANNON, "3");
		AtomicReference<String> tooltip = new AtomicReference<>();
		harness.onEdt(() -> tooltip.set(harness.ui().getTaskBox().getTaskTooltip()));
		assertTrue(tooltip.get(), tooltip.get().contains("Task: Dust devils<br>Cannon: Yes, a cannon task"));

		// Paused, it stays a cannon task.
		harness.pause();
		assertOverlayTask(CANNON, "3");
		assertTrue(harness.startNow());
		for (int i = 0; i < 2; i++)
		{
			fire(1);
			kill();
		}
		complete();

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals(DEVILS, record.getMonster());
		assertEquals(Boolean.TRUE, record.getDetectedCannon());
		assertTrue(record.cannonTask());
		assertEquals(CANNON, record.groupName());
	}

	/**
	 * a cannon task a 1.x version started, restored from its active-session file after the update, stays a cannon
	 * task after its next cannon kill on the live card, the overlay and the saved task. Its 10 earlier kills recorded no
	 * context, so only the kills since the restore count for its Cannon tag and its Cannon toggle's tooltip.
	 */
	@Test
	public void aCannonTaskRestoredFromA1xActiveFileStaysOneAfterItsNextKill() throws IOException
	{
		harness.logout();
		harness.shutDown();
		final long saved = now.get() - 5 * 60_000L;
		final String json = "{\"schemaVersion\":2,\"sessionId\":\"from-1x\",\"accountHash\":" + ACCOUNT
			+ ",\"monster\":\"" + DEVILS + "\",\"creatureId\":" + TASK + ",\"initialAmount\":15,\"originalAmount\":15,"
			+ "\"kills\":10,\"activeMillis\":600000,\"startedAt\":" + (saved - 20 * 60_000L) + ",\"savedAt\":" + saved
			+ ",\"remaining\":5,\"supplies\":{\"" + SupplyTracker.CANNONBALL + "\":120}}";
		Files.write(new File(harness.ledgerDirectory(), ACCOUNT + ".active.json").toPath(),
			json.getBytes(StandardCharsets.UTF_8));
		harness.setTask(TASK, DEVILS, 5, 15);
		harness.startUp();
		harness.setTrackerClock(now::get);
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(2);
		assertEquals(SessionTracker.State.LOGGED_OUT, harness.sessionState());
		assertEquals(10, harness.tracker().getKills());
		assertEquals("no context yet: the cannonballs 1.x saw fired", CANNON, taskBoxRows().get(0));
		String tooltip = liveCannonTooltip();
		assertFalse("no cannon kill counted yet: " + tooltip, tooltip.contains("Cannon kills"));

		// The next kill, with the cannon firing; its context is counted on the tick after.
		setUpCannon();
		fire(2);
		kill();
		harness.tick();
		assertEquals(11, harness.tracker().getKills());
		assertEquals("1 cannon kill of the 1 since the restore, not of 11", CANNON, taskBoxRows().get(0));
		assertOverlayTask(CANNON, "4");
		tooltip = liveCannonTooltip();
		assertTrue("the Cannon toggle counts the same kills: " + tooltip,
			tooltip.contains("<br>Cannon kills: 1 of 1<br>"));

		for (int i = 0; i < 3; i++)
		{
			fire(1);
			kill();
		}
		fire(1);
		complete();
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals("from-1x", record.getId());
		assertTrue(record.isCompleted());
		assertEquals(15, record.getKills());
		assertEquals(Integer.valueOf(5), record.getCannonKills());
		assertEquals(Boolean.TRUE, record.getDetectedCannon());
		assertEquals(CANNON, record.groupName());
		harness.drain();
		AtomicReference<List<String>> titles = new AtomicReference<>();
		harness.onEdt(() -> titles.set(harness.ui().getHistory().getTitleTexts()));
		assertEquals("the saved task's row is named a cannon task", Collections.singletonList(CANNON), titles.get());
	}

	@Test
	public void historyAndTotalsShowCannonTasksAsTheirOwnMonster()
	{
		// A task without a cannon, then one with.
		assertTrue(harness.startNow());
		for (int i = 0; i < 5; i++)
		{
			kill();
		}
		complete();

		harness.setTask(TASK, DEVILS, 5, 5);
		harness.tick();
		assertTrue(harness.startNow());
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
		assertEquals("newest first", Arrays.asList(CANNON, DEVILS), titles.get());

		harness.onEdt(() ->
		{
			harness.ui().getOverallTotals().clickTitle();
			harness.ui().getOverallTotals().setGrouping(OverallTotals.Grouping.MONSTER);
		});
		harness.drain();
		AtomicReference<List<String>> rows = new AtomicReference<>();
		harness.onEdt(() -> rows.set(harness.ui().getOverallTotals().getRows()));
		assertEquals(rows.get().toString(), 2, rows.get().size());
		assertTrue(rows.get().toString(), rows.get().stream().anyMatch(row -> row.startsWith(CANNON + " | 1 task\n")));
		assertTrue(rows.get().toString(), rows.get().stream().anyMatch(row -> row.startsWith(DEVILS + " | 1 task\n")));
	}

	@Test
	public void theStatisticsPoolUntilHalfTheKillsAreByCannonThenCountCannonTasksOnly()
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

		assertEquals(6, statisticsTasks());

		assertTrue(harness.startNow());
		setUpCannon();
		kill();
		assertEquals("still pooled without a cannonball fired", 6, statisticsTasks());
		assertTimeLeft("4 kills at 4.5 minutes", "~18m");

		fire(1);
		harness.tick();
		assertEquals("a cannonball alone, its first kill none of the cannon's: still pooled", TaskSelection.ANY,
			harness.tracker().getCurrentTaskSelection());
		// A kill while the cannon fires: 1 of 2 kills.
		kill();
		harness.tick();
		assertEquals(TaskSelection.ANY.withCannon(true), harness.tracker().getCurrentTaskSelection());
		assertEquals("cannon tasks only", 3, statisticsTasks());
		assertTimeLeft("3 kills at 3 minutes", "~9m");
	}

	@Test
	public void withoutCannonTasksInTheHistoryTheStatisticsKeepPoolingAfterACannonball()
	{
		List<LedgerRecord> history = new ArrayList<>();
		for (int i = 0; i < 3; i++)
		{
			history.add(saved(false));
		}
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, history));
		harness.drain();

		assertTrue(harness.startNow());
		setUpCannon();
		fire(1);
		kill();
		assertEquals(TaskSelection.ANY.withCannon(true), harness.tracker().getCurrentTaskSelection());
		assertEquals("the task box still shows the cannon", CANNON, taskBoxRows().get(0));
		assertEquals("no cannon tasks yet, so all of them", 3, statisticsTasks());
		assertTimeLeft("4 kills at 6 minutes", "~24m");

		// A first cannon task in the history: from then on only it counts.
		List<LedgerRecord> withCannon = new ArrayList<>(history);
		withCannon.add(saved(true));
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, withCannon));
		harness.drain();
		assertEquals("the cannon task only", 1, statisticsTasks());
		assertTimeLeft("4 kills at 3 minutes", "~12m");
	}

	@Test
	public void aCannonLoadedAndPickedUpWithoutFiringIsNoCannonTask()
	{
		assertTrue(harness.startNow());
		setUpCannon();
		kill();
		pickUpCannon();
		assertEquals(0L, harness.tracker().getSupplies().get(SupplyTracker.CANNONBALL));
		assertEquals(TaskSelection.ANY, harness.tracker().getCurrentTaskSelection());
		assertEquals(DEVILS, taskBoxRows().get(0));
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
		assertTrue(harness.startNow());
		setUpCannon();
		fire(4);
		kill();
		pickUpCannon();
		assertEquals(4L, harness.tracker().getSupplies().get(SupplyTracker.CANNONBALL));
		assertEquals(TaskSelection.ANY.withCannon(true), harness.tracker().getCurrentTaskSelection());
		assertEquals(CANNON, taskBoxRows().get(0));
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
		assertTrue(harness.startNow());
		kill();
		pickUpGraniteCannon();
		assertEquals(0L, harness.tracker().getSupplies().get(SupplyTracker.CANNONBALL));
		assertEquals(0L, harness.tracker().getSupplies().get(SupplyTracker.GRANITE_CANNONBALL));
		assertEquals(TaskSelection.ANY, harness.tracker().getCurrentTaskSelection());
		assertEquals(DEVILS, taskBoxRows().get(0));
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
		assertTrue(harness.startNow());
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
		assertTrue(harness.startNow());
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
		assertTrue(harness.startNow());
		fire(3);
		// The loaded type is not known yet, but the count is: before any kill's context the task box shows the cannon
		// at once, from the cannonballs fired.
		assertEquals(CANNON, taskBoxRows().get(0));
		// The shot just before the kill makes it a cannon kill (the first reading after the start is the cannon's
		// baseline, not a shot).
		fire(1);
		kill();
		pickUpGraniteCannon();
		assertEquals("moved to the type the pickup returned", 0L,
			harness.tracker().getSupplies().get(SupplyTracker.CANNONBALL));
		assertEquals(4L, harness.tracker().getSupplies().get(SupplyTracker.GRANITE_CANNONBALL));
		assertEquals(TaskSelection.ANY.withCannon(true), harness.tracker().getCurrentTaskSelection());
		assertEquals(CANNON, taskBoxRows().get(0));
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
