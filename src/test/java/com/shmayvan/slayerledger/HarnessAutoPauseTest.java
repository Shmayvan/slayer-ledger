package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import java.util.List;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.client.chat.QueuedMessage;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * A logout or world hop pauses the running session: the overlay shows the paused indicator, and one chat line
 * on the next login says why. Resuming stays manual. {@link OverlayRowsTest} covers the indicator row itself.
 */
public class HarnessAutoPauseTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.startUp();
		harness.setTask(42, "Abyssal demons", 12, 12);
		harness.loginAndSettle(ACCOUNT);
	}

	/** The overlay's rows now, with every row setting on. */
	private List<OverlayRows.Row> rows()
	{
		return OverlayRows.rows(harness.overlayData(), true, true, true, true);
	}

	private boolean showsPaused()
	{
		List<OverlayRows.Row> rows = rows();
		return !rows.isEmpty() && OverlayRows.PAUSED_LABEL.equals(rows.get(0).getLabel()) && rows.get(0).isWarning();
	}

	/** The one chat line queued so far (fails unless exactly one was). */
	private QueuedMessage onlyChatLine()
	{
		ArgumentCaptor<QueuedMessage> message = ArgumentCaptor.forClass(QueuedMessage.class);
		verify(harness.chatMessageManager, times(1)).queue(message.capture());
		return message.getValue();
	}

	@Test
	public void hopShowsPausedAndPostsOneChatLineAfterLogin()
	{
		assertTrue(harness.play());
		assertFalse("running shows no indicator", showsPaused());

		harness.setGameState(GameState.HOPPING);
		assertEquals(SessionTracker.State.PAUSED, harness.tracker().getState());
		assertTrue("indicator shown as soon as the hop pauses", showsPaused());
		verify(harness.chatMessageManager, never()).queue(any(QueuedMessage.class));

		harness.setGameState(GameState.LOADING);
		verify(harness.chatMessageManager, never()).queue(any(QueuedMessage.class));
		harness.setGameState(GameState.LOGGED_IN);
		harness.ticks(PluginHarness.SETTLE_TICKS);

		assertTrue(showsPaused());
		QueuedMessage message = onlyChatLine();
		assertEquals(ChatMessageType.CONSOLE, message.getType());
		assertEquals("Slayer Ledger", message.getName());
		assertEquals("Slayer Ledger paused (world hop). Press Resume to keep tracking.",
			message.getRuneLiteFormattedMessage());
		assertEquals("resume stays manual", SessionTracker.State.PAUSED, harness.tracker().getState());

		// Another hop or login without a new pause (the session is already paused) posts nothing more.
		harness.hop();
		harness.ticks(PluginHarness.SETTLE_TICKS);
		onlyChatLine();
		assertTrue(showsPaused());

		assertTrue("Resume still works", harness.play());
		assertFalse("indicator gone once resumed", showsPaused());
		onlyChatLine();
	}

	@Test
	public void logoutPostsOneChatLineOnTheNextLogin()
	{
		assertTrue(harness.play());
		harness.logout();
		assertEquals(SessionTracker.State.PAUSED, harness.tracker().getState());
		verify(harness.chatMessageManager, never()).queue(any(QueuedMessage.class));

		harness.loginAndSettle(ACCOUNT);
		assertEquals("Slayer Ledger paused (logged out). Press Resume to keep tracking.",
			onlyChatLine().getRuneLiteFormattedMessage());
		assertTrue(showsPaused());

		clearInvocations(harness.chatMessageManager);
		harness.logout();
		harness.loginAndSettle(ACCOUNT);
		verify(harness.chatMessageManager, never()).queue(any(QueuedMessage.class));
	}

	@Test
	public void manualPauseShowsTheIndicatorButPostsNoChatLine()
	{
		assertTrue(harness.play());
		harness.pause();
		assertTrue(showsPaused());

		harness.hop();
		harness.ticks(PluginHarness.SETTLE_TICKS);
		assertTrue(showsPaused());
		verify(harness.chatMessageManager, never()).queue(any(QueuedMessage.class));
	}

	@Test
	public void idleSessionPostsNothingOnAHop()
	{
		harness.hop();
		harness.ticks(PluginHarness.SETTLE_TICKS);
		assertEquals(SessionTracker.State.IDLE, harness.tracker().getState());
		verify(harness.chatMessageManager, never()).queue(any(QueuedMessage.class));
	}

	@Test
	public void taskChangedWhileLoggedOutPostsNoPausedLine()
	{
		assertTrue(harness.play());
		harness.logout();
		// The task changed elsewhere (another client) before this login: the restore check ends the session.
		harness.setTask(43, "Black demons", 30, 30);
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(PluginHarness.SETTLE_TICKS);

		assertFalse("the restore check ended the session", harness.tracker().getState() == SessionTracker.State.PAUSED);
		ArgumentCaptor<QueuedMessage> messages = ArgumentCaptor.forClass(QueuedMessage.class);
		verify(harness.chatMessageManager, atLeast(0)).queue(messages.capture());
		for (QueuedMessage message : messages.getAllValues())
		{
			assertFalse(message.getRuneLiteFormattedMessage(),
				String.valueOf(message.getRuneLiteFormattedMessage()).contains("paused"));
		}
	}

	@Test
	public void stoppedBeforeTheLoginPostsNothing()
	{
		assertTrue(harness.play());
		harness.setGameState(GameState.HOPPING);
		// Stopped from the sidebar before the next login: nothing is left to tell.
		harness.stop();
		harness.setGameState(GameState.LOADING);
		harness.setGameState(GameState.LOGGED_IN);
		harness.ticks(PluginHarness.SETTLE_TICKS);
		verify(harness.chatMessageManager, never()).queue(any(QueuedMessage.class));
	}
}
