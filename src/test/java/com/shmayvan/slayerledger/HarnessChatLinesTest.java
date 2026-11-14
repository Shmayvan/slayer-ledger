package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.PluginHarness.item;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.game.ItemStack;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * The personality pass's chat lines as wired in the plugin ({@link PluginHarness}, 2.0 phase 11): the task-complete
 * line and the record-fast task line post once per task, the record line only with 3 earlier completed tasks to beat
 * and then the task-complete line without its {@code Gz.}; the notable drop line for a session's notable stack, with
 * the kill number on even kills, never for an item Ground Items' hidden list hides (coins aside), and
 * {@code That's the task paid for.} only when the drop covers the supplies so far, once per task; and each line's
 * setting stops it. {@link TaskCompleteMessageTest}, {@link FastestTaskMessageTest} and
 * {@link NotableDropMessageTest} cover the words.
 */
public class HarnessChatLinesTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final long MINUTE = 60_000L;
	private static final int DEMONS = 42;
	private static final String[] EAT = {"Eat", null, null, null, "Drop"};
	private static final String COMPLETE = "<colHIGHLIGHT>Abyssal demons done:<colNORMAL> 3 kills in 15 min, +100.";
	private static final String RECORD = "<colHIGHLIGHT>Fastest Abyssal demons task yet:<colNORMAL> 15 min, 15 min"
		+ " under your old best.";

	@Rule
	public final PluginHarness harness = new PluginHarness();

	/** The tracker's clock, so a task's active time is under the test's control. */
	private final AtomicLong now = new AtomicLong(1_000_000_000L);
	private int ids;

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.BONES, "Bones", 100, 1);
		harness.defineItem(ItemID.ABYSSAL_WHIP, "Abyssal whip", 1_500_000, 72_000);
		harness.defineItem(ItemID.SHARK, "Shark", 800, 50, EAT);
		harness.defineItem(ItemID.RUNE_PLATEBODY, "Rune platebody", 200_000, 39_000);
		harness.defineItem(ItemID.COINS, "Coins", 1, 1);
		harness.startUp();
		harness.setTrackerClock(now::get);
	}

	// ---- Task complete and record ----

	@Test
	public void theTaskCompleteAndRecordLinesPostOncePerTask() throws IOException
	{
		saveSlowTasks(3);

		playTask();
		harness.ticks(5);
		harness.logout();

		assertEquals(15 * MINUTE, played().getActiveMillis());
		// The record line follows, so the task-complete line has no Gz.
		assertEquals(Arrays.asList(COMPLETE, RECORD), lines());
	}

	@Test
	public void withoutThreeEarlierTasksThereIsNoRecord() throws IOException
	{
		saveSlowTasks(2);

		playTask();

		assertEquals(Collections.singletonList(COMPLETE.replace("+100.", "+100. Gz.")), lines());
	}

	@Test
	public void theRecordTaskLineSettingStopsItsLine() throws IOException
	{
		saveSlowTasks(3);
		harness.config.recordTaskLine = false;

		playTask();

		assertEquals("the Gz. is back", Collections.singletonList(COMPLETE.replace("+100.", "+100. Gz.")), lines());
	}

	@Test
	public void theTaskCompleteLineSettingStopsItsLineButNotTheNotification() throws IOException
	{
		saveSlowTasks(3);
		harness.config.taskCompleteLine = false;
		harness.config.taskCompleteNotification = true;

		playTask();

		assertEquals(Collections.singletonList(RECORD), lines());
		ArgumentCaptor<String> notification = ArgumentCaptor.forClass(String.class);
		verify(harness.notifier, times(1)).notify(notification.capture());
		assertEquals("Abyssal demons done: 3 kills in 15 min, +100.", notification.getValue());
	}

	@Test
	public void aPartialTaskHasNeitherLine() throws IOException
	{
		saveSlowTasks(3);
		harness.setTask(DEMONS, "Abyssal demons", 3, 3);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		now.addAndGet(MINUTE);
		harness.setRemaining(2);
		harness.tick();
		harness.endTask();

		assertTrue(played().isPartial());
		assertEquals(Collections.emptyList(), lines());
	}

	@Test
	public void aLogoutBeforeTheRecordCheckLeavesTheGzAndNoRecordLine() throws IOException
	{
		saveSlowTasks(3);

		playTaskThenLeaveBeforeTheRecordCheck(this::logoutWithoutDrain);

		assertEquals(15 * MINUTE, played().getActiveMillis());
		assertEquals(Collections.singletonList(COMPLETE.replace("+100.", "+100. Gz.")), lines());
	}

	@Test
	public void anAccountSwitchBeforeTheRecordCheckLeavesTheGzAndNoRecordLine() throws IOException
	{
		saveSlowTasks(3);

		playTaskThenLeaveBeforeTheRecordCheck(() ->
		{
			logoutWithoutDrain();
			harness.setAccountHash(PluginHarness.OTHER_ACCOUNT);
			gameStateWithoutDrain(GameState.LOGGING_IN);
			gameStateWithoutDrain(GameState.LOADING);
			gameStateWithoutDrain(GameState.LOGGED_IN);
		});

		assertEquals(15 * MINUTE, played().getActiveMillis());
		assertEquals(Collections.singletonList(COMPLETE.replace("+100.", "+100. Gz.")), lines());
	}

	// ---- Notable drops ----

	@Test
	public void theNotableDropLineNamesTheMonsterOrTheKill()
	{
		startTask(10);
		kill(9);
		harness.postLoot("Abyssal demon", ItemID.ABYSSAL_WHIP, 1);
		kill(8);
		harness.postLoot("Abyssal demon", ItemID.ABYSSAL_WHIP, 1);
		// Below the Notable drop value: no line.
		harness.postLoot("Abyssal demon", ItemID.BONES, 1);

		assertEquals(Arrays.asList("<colHIGHLIGHT>Abyssal whip (1.5M)<colNORMAL> from an abyssal demon.",
			"<colHIGHLIGHT>Abyssal whip (1.5M)<colNORMAL> at kill 2 of 10."), lines());
	}

	@Test
	public void theTaskIsPaidForOnlyWhenTheDropCoversTheSuppliesSoFar()
	{
		harness.setInventory(item(ItemID.SHARK, 10));
		startTask(10);
		// Two sharks eaten: 1,600 gp of supplies, valued within two ticks.
		harness.setInventory(item(ItemID.SHARK, 8));
		kill(9);
		harness.ticks(2);
		harness.postLoot("Abyssal demon", ItemID.ABYSSAL_WHIP, 1);
		// Bones worth 100 gp each, notable at a 50 gp threshold, don't cover 1,600 gp.
		harness.setNotableDropThreshold(50);
		harness.postLoot("Abyssal demon", ItemID.BONES, 1);

		assertEquals(Arrays.asList(
			"<colHIGHLIGHT>Abyssal whip (1.5M)<colNORMAL> from an abyssal demon. That's the task paid for.",
			"<colHIGHLIGHT>Bones (100)<colNORMAL> from an abyssal demon."), lines());
	}

	@Test
	public void theTaskIsPaidForOnlyOncePerTask()
	{
		harness.setInventory(item(ItemID.SHARK, 10));
		startTask(10);
		harness.setInventory(item(ItemID.SHARK, 8));
		kill(9);
		harness.ticks(2);
		harness.postLoot("Abyssal demon", ItemID.ABYSSAL_WHIP, 1);
		kill(7);
		harness.ticks(2);
		// It covers the supplies again, but the task was paid for already.
		harness.postLoot("Abyssal demon", ItemID.ABYSSAL_WHIP, 1);

		assertEquals(Arrays.asList(
			"<colHIGHLIGHT>Abyssal whip (1.5M)<colNORMAL> from an abyssal demon. That's the task paid for.",
			"<colHIGHLIGHT>Abyssal whip (1.5M)<colNORMAL> from an abyssal demon."), lines());
	}

	@Test
	public void theNextTaskCanBePaidForAgain()
	{
		harness.setInventory(item(ItemID.SHARK, 10));
		startTask(10);
		harness.setInventory(item(ItemID.SHARK, 8));
		kill(9);
		harness.ticks(2);
		harness.postLoot("Abyssal demon", ItemID.ABYSSAL_WHIP, 1);
		harness.completeTask();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS + 1);

		// A new task, a new session: its first drop that covers its supplies says it again.
		harness.setTask(DEMONS, "Abyssal demons", 10, 10);
		harness.tick();
		assertTrue(harness.startNow());
		harness.setInventory(item(ItemID.SHARK, 6));
		kill(9);
		harness.ticks(2);
		harness.postLoot("Abyssal demon", ItemID.ABYSSAL_WHIP, 1);

		String paid = "<colHIGHLIGHT>Abyssal whip (1.5M)<colNORMAL> from an abyssal demon. That's the task paid for.";
		assertEquals(Arrays.asList(paid, paid), lines().stream().filter(l -> l.contains("Abyssal whip"))
			.collect(Collectors.toList()));
	}

	@Test
	public void aHiddenItemGetsNoLineNorPaysForTheTask()
	{
		harness.setGroundItemsHidden("Abyssal whip");
		harness.setInventory(item(ItemID.SHARK, 10));
		startTask(10);
		harness.setInventory(item(ItemID.SHARK, 8));
		kill(9);
		harness.ticks(2);
		harness.postLoot("Abyssal demon", ItemID.ABYSSAL_WHIP, 1);

		assertEquals(Collections.emptyList(), lines());
		assertEquals("still a notable drop of the task", 1, harness.tracker().getNotableDrops().size());
	}

	@Test
	public void aHiddenItemGivesWayToTheKillsNextNotableDrop()
	{
		harness.setGroundItemsHidden("Abyssal whip");
		startTask(10);
		kill(9);
		harness.postLoot("Abyssal demon", new ItemStack(ItemID.ABYSSAL_WHIP, 1),
			new ItemStack(ItemID.RUNE_PLATEBODY, 1));

		assertEquals(Collections.singletonList("<colHIGHLIGHT>Rune platebody (200K)<colNORMAL> from an abyssal demon."),
			lines());
	}

	@Test
	public void hiddenCoinsStillGetTheirLine()
	{
		harness.setGroundItemsHidden("Coins");
		startTask(10);
		kill(9);
		harness.postLoot("Abyssal demon", ItemID.COINS, 200_000);

		assertEquals(Collections.singletonList("<colHIGHLIGHT>Coins x200,000 (200K)<colNORMAL> from an abyssal demon."),
			lines());
	}

	@Test
	public void withNoSuppliesUsedTheTaskIsNotPaidFor()
	{
		startTask(10);
		kill(9);
		harness.ticks(2);
		harness.postLoot("Abyssal demon", ItemID.ABYSSAL_WHIP, 1);

		assertEquals(Collections.singletonList("<colHIGHLIGHT>Abyssal whip (1.5M)<colNORMAL> from an abyssal demon."),
			lines());
	}

	@Test
	public void theNotableDropLineSettingStopsItsLine()
	{
		harness.config.notableDropLine = false;
		startTask(10);
		kill(9);
		harness.postLoot("Abyssal demon", ItemID.ABYSSAL_WHIP, 1);

		assertEquals(Collections.emptyList(), lines());
		assertEquals("still a notable drop of the task", 1, harness.tracker().getNotableDrops().size());
	}

	@Test
	public void lootTheSessionDoesNotTakeHasNoLine()
	{
		harness.setTask(DEMONS, "Abyssal demons", 10, 10);
		harness.loginAndSettle(ACCOUNT);
		// A task, but no session yet: the loot is only buffered.
		harness.postLoot("Abyssal demon", ItemID.ABYSSAL_WHIP, 1);

		verify(harness.chatMessageManager, never()).queue(any(QueuedMessage.class));
	}

	// ---- Helpers ----

	/** A 3-kill Abyssal demons task done in 15 minutes, its last kill's loot one bone, then its grace window. */
	private void playTask()
	{
		harness.setTask(DEMONS, "Abyssal demons", 3, 3);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		now.addAndGet(5 * MINUTE);
		harness.setRemaining(2);
		harness.tick();
		now.addAndGet(5 * MINUTE);
		harness.setRemaining(1);
		harness.tick();
		now.addAndGet(5 * MINUTE);
		harness.completeTask();
		harness.lootOnNextTick(ItemID.BONES, 1);
		harness.tick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
	}

	/**
	 * As {@link #playTask}, but the EDT is held while the grace window ends and the task is saved, so the record check
	 * waits there; {@code leave} then runs (without draining) before the EDT is let go and everything drains.
	 */
	private void playTaskThenLeaveBeforeTheRecordCheck(Runnable leave)
	{
		harness.setTask(DEMONS, "Abyssal demons", 3, 3);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		now.addAndGet(5 * MINUTE);
		harness.setRemaining(2);
		harness.tick();
		now.addAndGet(5 * MINUTE);
		harness.setRemaining(1);
		harness.tick();
		now.addAndGet(5 * MINUTE);
		harness.completeTask();
		harness.lootOnNextTick(ItemID.BONES, 1);
		harness.tick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS - 1);
		assertEquals("not saved yet", Collections.emptyList(), lines());

		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		SwingUtilities.invokeLater(() ->
		{
			held.countDown();
			try
			{
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
		});
		try
		{
			assertTrue(held.await(10, TimeUnit.SECONDS));
			// The grace window ends: the save is queued, then runs, and the record check waits for the EDT.
			harness.tickWithoutDrain();
			harness.runClientThread();
			assertTrue("the save ran", harness.runExecutor() > 0);
			leave.run();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
		finally
		{
			release.countDown();
		}
		harness.drain();
	}

	/** The client goes to the login screen without draining: the account hash reads -1 there. */
	private void logoutWithoutDrain()
	{
		harness.setAccountHash(SessionTracker.NO_ACCOUNT);
		gameStateWithoutDrain(GameState.LOGIN_SCREEN);
	}

	/** Sets the game state and posts {@link GameStateChanged} without draining any queued work. */
	private void gameStateWithoutDrain(GameState state)
	{
		harness.setGameStateSilently(state);
		GameStateChanged event = new GameStateChanged();
		event.setGameState(state);
		harness.postWithoutDrain(event);
	}

	/** Logs in on an Abyssal demons task of {@code size} and starts its session. */
	private void startTask(int size)
	{
		harness.setTask(DEMONS, "Abyssal demons", size, size);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
	}

	/** A task kill: the kills left drop to {@code remaining}, seen on the next tick. */
	private void kill(int remaining)
	{
		harness.setRemaining(remaining);
		harness.tick();
	}

	/** Saves {@code count} completed 3-kill Abyssal demons tasks of 30 minutes each, read when the account logs in. */
	private void saveSlowTasks(int count) throws IOException
	{
		LedgerStore store = new LedgerStore(Filepath.Unchecked.getRooted(harness.ledgerDirectory().toPath()),
			harness.gson);
		for (int i = 0; i < count; i++)
		{
			LedgerRecord record = new LedgerRecord();
			record.setId("slow-" + ids++);
			record.setCreatureId(DEMONS);
			record.setMonster("Abyssal demons");
			record.setKills(3);
			record.setActiveMillis(30 * MINUTE);
			record.setCompleted(true);
			record.setLootGeValue(0L);
			record.setLootHaValue(0L);
			record.setSuppliesGeValue(0L);
			store.append(ACCOUNT, record);
		}
	}

	/** The one saved task that isn't one of {@link #saveSlowTasks}. */
	private LedgerRecord played()
	{
		List<LedgerRecord> played = harness.records(ACCOUNT).stream().filter(r -> !r.getId().startsWith("slow-"))
			.collect(Collectors.toList());
		assertEquals(played.toString(), 1, played.size());
		return played.get(0);
	}

	/** The chat lines posted so far, each a "Slayer Ledger" console message. */
	private List<String> lines()
	{
		ArgumentCaptor<QueuedMessage> messages = ArgumentCaptor.forClass(QueuedMessage.class);
		verify(harness.chatMessageManager, atLeast(0)).queue(messages.capture());
		for (QueuedMessage message : messages.getAllValues())
		{
			assertEquals(ChatMessageType.CONSOLE, message.getType());
			assertEquals("Slayer Ledger", message.getName());
		}
		return messages.getAllValues().stream().map(QueuedMessage::getRuneLiteFormattedMessage)
			.collect(Collectors.toList());
	}
}
