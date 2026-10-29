package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.ui.NavigationButton;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Plugin start and stop through the client's wiring ({@link PluginHarness}): registration with a real event bus, the
 * sidebar button, the completion grace ticker's registration, and recovery from a failed start.
 */
public class HarnessLifecycleTest
{
	private static final int TASK = 42;
	private static final String TASK_NAME = "Abyssal demons";

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Test
	public void startedPluginReceivesEventsFromTheEventBus()
	{
		harness.startUp();
		assertFalse("logged out", harness.canPlay());

		harness.setTask(TASK, TASK_NAME, 10, 10);
		harness.login(PluginHarness.ACCOUNT);
		harness.ticks(PluginHarness.SETTLE_TICKS - 1);
		assertFalse("task not read while the login settles", harness.canPlay());
		harness.tick();

		// Only true if onGameStateChanged and onGameTick both ran.
		assertTrue(harness.canPlay());
		assertEquals(TASK_NAME, harness.tracker().getCurrentMonster());
		assertEquals(10, harness.tracker().getCurrentRemaining());
	}

	@Test
	public void navigationButtonIsAddedOnStartAndRemovedOnStop()
	{
		harness.startUp();
		ArgumentCaptor<NavigationButton> added = ArgumentCaptor.forClass(NavigationButton.class);
		verify(harness.clientToolbar).addNavigation(added.capture());
		assertEquals("Slayer Ledger", added.getValue().getTooltip());
		assertNotNull(added.getValue().getPanel());
		verify(harness.clientToolbar, never()).removeNavigation(any());

		harness.shutDown();
		verify(harness.clientToolbar).removeNavigation(added.getValue());
	}

	@Test
	public void stoppedPluginIgnoresEvents()
	{
		harness.startUp();
		harness.setTask(TASK, TASK_NAME, 10, 10);
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		harness.shutDown();

		// Neither the plugin nor its grace ticker is left subscribed, so no handler of the plugin reads the client.
		final int callsWhenStopped = harness.pluginCallCount();
		assertTrue("the running plugin read the client, so the count works", callsWhenStopped > 0);
		harness.setRemaining(9);
		harness.ticks(3);
		harness.lootOnNextTick(526, 1);
		harness.tick();
		harness.postLoot(526, 1);
		harness.chat(ChatMessageType.GAMEMESSAGE, "You pick the man's pocket.");
		harness.clickMenu("Drop", 526);
		harness.openInterface(InterfaceID.BANKMAIN);
		harness.closeInterface(InterfaceID.BANKMAIN);
		harness.localPlayerDies();
		harness.hop();
		harness.logout();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		assertEquals("client calls by a stopped plugin", callsWhenStopped, harness.pluginCallCount());

		assertFalse(harness.canPlay());
		assertTrue(harness.records(PluginHarness.ACCOUNT).isEmpty());
		assertNull(harness.activeSession(PluginHarness.ACCOUNT));
	}

	/**
	 * startUp (EDT) only queues the reset of the client-thread state, and the handlers are registered right after it, so
	 * a client frame may post events before that block runs. Until it has, the handlers do nothing: no client reads, and
	 * nothing from the previous run (here a settled login, which would read the task at once).
	 */
	@Test
	public void handlersWaitForTheStartUpResetOfClientThreadState()
	{
		harness.startUp();
		harness.setTask(TASK, TASK_NAME, 10, 10);
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		harness.shutDown();

		harness.startUpWithoutDrain();
		final int callsBeforeReset = harness.pluginCallCount();
		harness.postWithoutDrain(new GameTick());
		final GameStateChanged loggedIn = new GameStateChanged();
		loggedIn.setGameState(GameState.LOGGED_IN);
		harness.postWithoutDrain(loggedIn);
		harness.postWithoutDrain(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", "You pick the man's pocket.",
			null, 0));
		assertEquals("no handler read the client before the reset", callsBeforeReset, harness.pluginCallCount());
		assertFalse("the task was not read with the previous run's state", harness.tracker().isTaskObserved());
		assertFalse(harness.canPlay());

		// The block reads the game state and account itself, so the skipped GameStateChanged loses nothing; the login
		// settles afresh before the task is read.
		harness.drain();
		harness.ticks(PluginHarness.SETTLE_TICKS - 1);
		assertFalse("task not read while the login settles", harness.tracker().isTaskObserved());
		harness.tick();
		assertTrue(harness.canPlay());
		assertEquals(10, harness.tracker().getCurrentRemaining());
	}

	@Test
	public void startingWhileLoggedInPicksUpTheAccountAndTask()
	{
		harness.setTask(TASK, TASK_NAME, 10, 10);
		harness.setAccountHash(PluginHarness.ACCOUNT);
		harness.setGameStateSilently(GameState.LOGGED_IN);

		harness.startUp();
		harness.ticks(PluginHarness.SETTLE_TICKS);

		assertTrue(harness.canPlay());
		assertTrue(harness.play());
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals(PluginHarness.ACCOUNT, harness.tracker().getSessionAccountHash());
	}

	@Test
	public void failedStartUpIsCleanedUpByShutDown()
	{
		doThrow(new IllegalStateException("toolbar unavailable")).when(harness.clientToolbar).addNavigation(any());
		try
		{
			harness.startUp();
			fail("startUp should have failed");
		}
		catch (IllegalStateException e)
		{
			assertEquals("toolbar unavailable", e.getMessage());
			assertEquals("shutDown after the failed startUp threw", 0, e.getSuppressed().length);
		}
		assertFalse(harness.isRunning());
		ArgumentCaptor<NavigationButton> button = ArgumentCaptor.forClass(NavigationButton.class);
		verify(harness.clientToolbar).addNavigation(button.capture());
		verify(harness.clientToolbar).removeNavigation(button.getValue());

		// Nothing is left subscribed: game events after the failed start reach nothing and throw nothing.
		harness.setTask(TASK, TASK_NAME, 10, 10);
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		assertFalse(harness.canPlay());
	}

	@Test
	public void pluginStartsNormallyAfterAFailedStart()
	{
		doThrow(new IllegalStateException("toolbar unavailable")).doNothing()
			.when(harness.clientToolbar).addNavigation(any());
		try
		{
			harness.startUp();
			fail("startUp should have failed");
		}
		catch (IllegalStateException expected)
		{
			// Retried below.
		}

		harness.startUp();
		harness.setTask(TASK, TASK_NAME, 3, 3);
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		assertTrue(harness.play());
		harness.completeTask();
		harness.tick();
		assertTrue(harness.tracker().hasPendingCompletion());
		harness.tick();
		assertTrue("the ticker counts each tick once", harness.tracker().hasPendingCompletion());
		harness.tick();
		assertTrue(harness.onlyRecord(PluginHarness.ACCOUNT).isCompleted());
	}

	@Test
	public void shutDownWithoutStartUpDoesNotThrow()
	{
		harness.onEdt(harness.plugin::shutDown);
		verify(harness.clientToolbar, never()).removeNavigation(any());
	}

	@Test
	public void restartDoesNotRegisterTheGraceTickerTwice()
	{
		harness.startUp();
		harness.shutDown();
		harness.startUp();
		verify(harness.clientToolbar, times(2)).addNavigation(any());

		harness.setTask(TASK, TASK_NAME, 3, 3);
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		assertTrue(harness.play());
		harness.setRemaining(1);
		harness.tick();
		harness.completeTask();
		harness.tick();

		// Held on the completion tick, then two more ticks. A second ticker registration would halve the window.
		assertTrue(harness.tracker().hasPendingCompletion());
		harness.tick();
		assertTrue(harness.tracker().hasPendingCompletion());
		assertTrue(harness.records(PluginHarness.ACCOUNT).isEmpty());
		harness.tick();
		assertFalse(harness.tracker().hasPendingCompletion());
		LedgerRecord record = harness.onlyRecord(PluginHarness.ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals(3, record.getKills());
	}

	@Test
	public void restartKeepsTheSameNavigationLifecycle()
	{
		harness.startUp();
		harness.shutDown();
		harness.startUp();
		harness.shutDown();
		ArgumentCaptor<NavigationButton> added = ArgumentCaptor.forClass(NavigationButton.class);
		verify(harness.clientToolbar, times(2)).addNavigation(added.capture());
		ArgumentCaptor<NavigationButton> removed = ArgumentCaptor.forClass(NavigationButton.class);
		verify(harness.clientToolbar, times(2)).removeNavigation(removed.capture());
		assertSame(added.getAllValues().get(0), removed.getAllValues().get(0));
		assertSame(added.getAllValues().get(1), removed.getAllValues().get(1));
	}
}
