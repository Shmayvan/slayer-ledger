package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.ui.overlay.Overlay;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * The "not tracking" nudge: a task kill while idle or paused shows an overlay row (idle only) and posts one chat
 * line per assignment. {@link TrackingNudgeTest} covers the rules, {@link OverlayRowsTest} the row.
 */
public class HarnessTrackingNudgeTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final String IDLE_LINE =
		"Kill not counted: Slayer Ledger isn't tracking. Press Play in the sidebar.";
	private static final String PAUSED_LINE =
		"Kill not counted: Slayer Ledger is paused. Press Resume in the sidebar.";
	/** The line after a hop paused a running session. */
	private static final String AUTO_PAUSE_LINE = "Slayer Ledger paused (world hop). Press Resume to keep tracking.";

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.startUp();
		harness.setTask(42, "Abyssal demons", 12, 12);
		harness.loginAndSettle(ACCOUNT);
	}

	/** The nudge lines queued so far (other chat lines, such as a new-task preview, are left out). */
	private List<QueuedMessage> nudgeLines()
	{
		ArgumentCaptor<QueuedMessage> messages = ArgumentCaptor.forClass(QueuedMessage.class);
		verify(harness.chatMessageManager, atLeast(0)).queue(messages.capture());
		List<QueuedMessage> lines = new ArrayList<>();
		for (QueuedMessage message : messages.getAllValues())
		{
			String text = message.getRuneLiteFormattedMessage();
			if (IDLE_LINE.equals(text) || PAUSED_LINE.equals(text))
			{
				lines.add(message);
			}
		}
		return lines;
	}

	/** Every chat line queued so far, in order. */
	private List<String> allLines()
	{
		ArgumentCaptor<QueuedMessage> messages = ArgumentCaptor.forClass(QueuedMessage.class);
		verify(harness.chatMessageManager, atLeast(0)).queue(messages.capture());
		List<String> lines = new ArrayList<>();
		for (QueuedMessage message : messages.getAllValues())
		{
			lines.add(message.getRuneLiteFormattedMessage());
		}
		return lines;
	}

	/** The overlay's rows now, with every row setting on. */
	private List<OverlayRows.Row> rows()
	{
		return OverlayRows.rows(harness.overlayData(), true, true, true, true);
	}

	private boolean showsNotTracking()
	{
		for (OverlayRows.Row row : rows())
		{
			if (OverlayRows.NOT_TRACKING_LABEL.equals(row.getLabel()))
			{
				assertTrue(row.isWarning());
				return true;
			}
		}
		return false;
	}

	private int warningRows()
	{
		int n = 0;
		for (OverlayRows.Row row : rows())
		{
			n += row.isWarning() ? 1 : 0;
		}
		return n;
	}

	private void kill(int remaining)
	{
		harness.setRemaining(remaining);
		harness.tick();
	}

	@Test
	public void idleKillShowsTheRowAndOneChatLine()
	{
		assertFalse("nothing before a kill", showsNotTracking());
		assertNull("the overlay is hidden while idle without a nudge", harness.overlayData());

		kill(11);
		assertTrue(showsNotTracking());
		assertEquals(1, rows().size());
		List<QueuedMessage> lines = nudgeLines();
		assertEquals(1, lines.size());
		assertEquals(IDLE_LINE, lines.get(0).getRuneLiteFormattedMessage());
		assertEquals(ChatMessageType.CONSOLE, lines.get(0).getType());
		assertEquals("Slayer Ledger", lines.get(0).getName());

		kill(10);
		kill(9);
		harness.ticks(3);
		assertEquals("one line per task, not per kill", 1, nudgeLines().size());
		assertTrue(showsNotTracking());
		assertEquals("only informs", SessionTracker.State.IDLE, harness.sessionState());
	}

	@Test
	public void playClearsTheRow()
	{
		kill(11);
		assertTrue(showsNotTracking());

		assertTrue(harness.play());
		assertFalse("gone as soon as Play is pressed", showsNotTracking());
		kill(10);
		assertFalse(showsNotTracking());
		assertEquals(1, nudgeLines().size());

		// Stopped again: another untracked kill shows the row again, still without a second line.
		harness.stop();
		assertFalse(showsNotTracking());
		kill(9);
		assertTrue(showsNotTracking());
		assertEquals(1, nudgeLines().size());
	}

	@Test
	public void pausedKillPostsOnePausedLineAndNoSecondWarningRow()
	{
		assertTrue(harness.play());
		kill(11);
		harness.pause();

		kill(10);
		List<QueuedMessage> lines = nudgeLines();
		assertEquals(1, lines.size());
		assertEquals(PAUSED_LINE, lines.get(0).getRuneLiteFormattedMessage());
		assertEquals("only the Paused row warns", 1, warningRows());
		assertEquals(OverlayRows.PAUSED_LABEL, rows().get(0).getLabel());
		assertFalse(showsNotTracking());

		kill(9);
		assertEquals(1, nudgeLines().size());
		assertEquals("resume stays manual", SessionTracker.State.PAUSED, harness.sessionState());
	}

	@Test
	public void runningSessionNeverNudges()
	{
		assertTrue(harness.play());
		kill(11);
		kill(10);
		assertFalse(showsNotTracking());
		assertEquals(0, nudgeLines().size());
	}

	@Test
	public void completingKillNeverNudges()
	{
		kill(1);
		assertEquals(1, nudgeLines().size());
		harness.setTask(43, "Black demons", 1, 30);
		harness.tick();
		assertFalse("a new assignment clears the row", showsNotTracking());

		// The final kill of the new task, while idle: the completion, not a nudge.
		harness.completeTask();
		harness.ticks(3);
		assertEquals(1, nudgeLines().size());
		assertFalse(showsNotTracking());
	}

	@Test
	public void completingKillOfARunningSessionNeverNudges()
	{
		assertTrue(harness.play());
		kill(1);
		harness.completeTask();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS + 2);
		assertEquals(SessionTracker.State.IDLE, harness.sessionState());
		assertEquals(0, nudgeLines().size());
		assertFalse(showsNotTracking());
	}

	@Test
	public void newAssignmentCanNudgeAgain()
	{
		kill(11);
		assertEquals(1, nudgeLines().size());

		harness.completeTask();
		harness.ticks(3);
		harness.setTask(43, "Black demons", 30, 30);
		harness.tick();
		assertEquals("a new assignment is not a kill", 1, nudgeLines().size());
		assertFalse(showsNotTracking());

		kill(29);
		assertEquals(2, nudgeLines().size());
		assertTrue(showsNotTracking());
	}

	@Test
	public void sameAssignmentIsNotNudgedAgainAfterAHopOrRelogin()
	{
		kill(11);
		assertEquals(1, nudgeLines().size());

		harness.hop();
		harness.ticks(PluginHarness.SETTLE_TICKS);
		kill(10);
		assertEquals(1, nudgeLines().size());
		assertTrue(showsNotTracking());

		harness.logout();
		assertNull("hidden at the login screen", harness.overlayData());
		harness.loginAndSettle(ACCOUNT);
		assertFalse(showsNotTracking());
		kill(9);
		assertEquals(1, nudgeLines().size());
		assertTrue("the row comes back on the next untracked kill", showsNotTracking());
	}

	@Test
	public void hopAutoPauseThenAKillPostsTheAutoPauseLineAndOnePausedLine()
	{
		assertTrue(harness.play());
		harness.hop();
		harness.ticks(PluginHarness.SETTLE_TICKS);
		assertEquals(SessionTracker.State.PAUSED, harness.sessionState());
		assertEquals(Collections.singletonList(AUTO_PAUSE_LINE), allLines());

		kill(11);
		kill(10);
		harness.ticks(3);
		assertEquals(Arrays.asList(AUTO_PAUSE_LINE, PAUSED_LINE), allLines());
		assertEquals("only the Paused row warns", 1, warningRows());
		assertEquals("resume stays manual", SessionTracker.State.PAUSED, harness.sessionState());
	}

	@Test
	public void aKillOnTheFirstSettledTickAfterReloginWithARestoreCheckPendingPostsNothing()
	{
		harness.logout();
		harness.login(ACCOUNT);
		harness.ticks(PluginHarness.SETTLE_TICKS - 1);
		// The first settled tick: the restore check for this account starts on it, and the count went down since login.
		harness.setRemaining(11);
		harness.tick();
		assertEquals(Collections.emptyList(), allLines());
		assertFalse(showsNotTracking());
		assertEquals(SessionTracker.State.IDLE, harness.sessionState());

		// The check is done (no session to restore): the next kill nudges.
		kill(10);
		assertEquals(Collections.singletonList(IDLE_LINE), allLines());
		assertTrue(showsNotTracking());
	}

	@Test
	public void aPluginRestartHidesTheRowButKeepsTheAssignment()
	{
		kill(11);
		assertTrue(showsNotTracking());

		harness.shutDown();
		harness.startUpWithoutDrain();
		assertNull("no row before the start's client-thread block has run", harness.overlayData());
		harness.drain();
		assertNull("no row from before the restart", harness.overlayData());
		harness.ticks(PluginHarness.SETTLE_TICKS + 2);
		assertNull(harness.overlayData());

		kill(10);
		assertTrue("the next untracked kill shows it again", showsNotTracking());
		assertEquals("the assignment was already nudged", 1, nudgeLines().size());
	}

	@Test
	public void overlaySettingOffHidesTheRowButNotTheLine()
	{
		harness.config.showTaskOverlay = false;
		kill(11);
		assertEquals(1, nudgeLines().size());
		assertNull("the overlay draws nothing", render());
		harness.config.showTaskOverlay = true;
		assertNotNull(render());
	}

	private java.awt.Dimension render()
	{
		ArgumentCaptor<Overlay> overlay = ArgumentCaptor.forClass(Overlay.class);
		verify(harness.overlayManager, times(1)).add(overlay.capture());
		BufferedImage image = new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			return overlay.getValue().render(graphics);
		}
		finally
		{
			graphics.dispose();
		}
	}
}
