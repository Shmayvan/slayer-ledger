package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.ui.overlay.Overlay;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * The task overlay as wired in the plugin ({@link PluginHarness}): registration, what it shows as the session
 * changes, live values matched by session, the settings, the completion grace window and a re-login. {@link
 * OverlayRowsTest} covers the row text.
 */
public class HarnessOverlayTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final long MINUTE = 60_000;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	/** The tracker's clock, so active time (and the time-left estimate) is under the test's control. */
	private final AtomicLong now = new AtomicLong(1_000_000_000L);

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.BONES, "Bones", 100, 1);
		harness.startUp();
		useTestClock();
		harness.setTask(42, "Abyssal demons", 12, 12);
		harness.loginAndSettle(ACCOUNT);
	}

	/** Points the plugin's session tracker at {@link #now} (it reads the system clock otherwise). */
	private void useTestClock()
	{
		harness.setTrackerClock(now::get);
	}

	/** The overlay the plugin registered. */
	private SlayerLedgerOverlay overlay()
	{
		ArgumentCaptor<Overlay> overlay = ArgumentCaptor.forClass(Overlay.class);
		verify(harness.overlayManager, times(1)).add(overlay.capture());
		return (SlayerLedgerOverlay) overlay.getValue();
	}

	/** Renders the overlay as the client does (on the client thread); {@code null} when it draws nothing. */
	private java.awt.Dimension render()
	{
		BufferedImage image = new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			return overlay().render(graphics);
		}
		finally
		{
			graphics.dispose();
		}
	}

	/** The rows the overlay shows now, with every row on. */
	private List<OverlayRows.Row> rows()
	{
		return OverlayRows.rows(harness.overlayData(), true, true, true, true);
	}

	private String value(String label)
	{
		for (OverlayRows.Row row : rows())
		{
			if (row.getLabel().equals(label))
			{
				return row.getValue();
			}
		}
		return null;
	}

	@Test
	public void registersOverlayAndShowsOnlyRunningOrPausedTaskSessions()
	{
		assertNull("idle sessions have no task overlay", harness.overlayData());
		assertNull(render());

		assertTrue(harness.play());
		OverlayRows.Data running = harness.overlayData();
		assertNotNull(running);
		assertEquals("Abyssal demons", running.getTaskName());
		assertEquals(12, running.getTask().getRemaining());
		assertNotNull("drawn while running", render());

		harness.pause();
		OverlayRows.Data paused = harness.overlayData();
		assertNotNull(paused);
		assertEquals(SessionTracker.State.PAUSED, paused.getTask().getState());
		assertEquals(12, paused.getTask().getRemaining());
		assertNotNull("drawn while paused", render());

		harness.stop();
		assertNull("stopped sessions have no task overlay", harness.overlayData());
		assertNull(render());

		harness.shutDown();
		verify(harness.overlayManager, times(1)).remove(any(Overlay.class));
	}

	@Test
	public void killsAndTimeLeftUpdateAfterAKill()
	{
		assertTrue(harness.play());
		assertEquals("Abyssal demons", value("Task"));
		assertEquals("12", value("Left"));
		assertEquals("0", value("Kills"));
		assertEquals(LedgerHistory.TIME_LEFT_ESTIMATING, value("Time left"));

		now.addAndGet(4 * MINUTE);
		harness.setRemaining(8);
		harness.tick();

		assertEquals("Abyssal demons", value("Task"));
		assertEquals("8", value("Left"));
		assertEquals("4", value("Kills"));
		// One kill a minute so far, 8 left.
		assertEquals("~8m", value("Time left"));
	}

	@Test
	public void profitComesFromThisSessionsPublishedValuesOnly()
	{
		assertTrue(harness.play());
		assertNull("not valued yet", value("Profit (GE)"));
		harness.postLoot(ItemID.BONES, 1);
		harness.tick();
		assertEquals("100 gp", value("Profit (GE)"));

		// A new session between ticks: the published values still belong to the old one.
		harness.stop();
		assertTrue(harness.play());
		assertNull("the previous session's values are ignored", value("Profit (GE)"));
		assertNull(harness.overlayData().getValues());

		// Valued once the 2-tick throttle from the old session's valuation has passed.
		harness.ticks(2);
		assertEquals("the new session's own values", "0 gp", value("Profit (GE)"));
	}

	@Test
	public void settingsHideTheOverlay()
	{
		assertTrue(harness.play());
		assertNotNull(render());

		harness.config.showTaskOverlay = false;
		assertNull("the master toggle hides it", render());

		harness.config.showTaskOverlay = true;
		harness.config.overlayTask = false;
		harness.config.overlayKills = false;
		harness.config.overlayTimeLeft = false;
		harness.config.overlayProfit = false;
		assertNull("nothing to draw with every row off", render());

		harness.config.overlayKills = true;
		assertNotNull(render());
	}

	@Test
	public void hiddenDuringTheCompletionGraceWindowAndAfterTheSave()
	{
		assertTrue(harness.play());
		harness.setRemaining(1);
		harness.tick();
		assertEquals("Abyssal demons", value("Task"));
		assertEquals("1", value("Left"));

		harness.completeTask();
		harness.tick();
		assertTrue("held for late loot", harness.tracker().hasPendingCompletion());
		assertNull("hidden once the final kill completes the task", harness.overlayData());
		assertNull(render());

		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
		assertTrue(harness.onlyRecord(ACCOUNT).isCompleted());
		assertNull("hidden after the save", harness.overlayData());
		assertNull(render());
	}

	@Test
	public void aPausedSessionAfterReLoginIsNeverShownAsComplete()
	{
		assertTrue(harness.play());
		harness.setRemaining(10);
		harness.tick();
		harness.pause();
		harness.logout();

		harness.login(ACCOUNT);
		for (int i = 0; i < PluginHarness.SETTLE_TICKS; i++)
		{
			// Until the task is read again the kills left are unknown, never 0.
			assertEquals("tick " + i, SessionTracker.LiveTask.UNKNOWN_REMAINING,
				harness.overlayData().getTask().getRemaining());
			assertEquals("tick " + i, "Abyssal demons", value("Task"));
			assertEquals("tick " + i, "?", value("Left"));
			assertEquals("tick " + i, LedgerHistory.NO_VALUE, value("Time left"));
			assertEquals("tick " + i, "2", value("Kills"));
			harness.tick();
		}
		assertEquals("Abyssal demons", value("Task"));
		assertEquals("10", value("Left"));
	}

	@Test
	public void aClearedTaskWaitingForItsCompletionShowsUnknownKillsLeft()
	{
		assertTrue(harness.play());
		// The count reads 0 without a completion: possibly transient, so the session waits.
		harness.setRemaining(0);
		harness.tick();
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());

		assertEquals(SessionTracker.LiveTask.UNKNOWN_REMAINING, harness.overlayData().getTask().getRemaining());
		assertEquals("Abyssal demons", value("Task"));
		assertEquals("?", value("Left"));
		assertEquals(LedgerHistory.NO_VALUE, value("Time left"));
	}

	/** A completed Abyssal demons task: 10 kills in 10 minutes, so one minute a kill. */
	private static LedgerRecord priorTask()
	{
		LedgerRecord prior = new LedgerRecord();
		prior.setCreatureId(42);
		prior.setMonster("Abyssal demons");
		prior.setKills(10);
		prior.setActiveMillis(10 * MINUTE);
		prior.setCompleted(true);
		return prior;
	}

	/** The sidebar's time-left line after a refresh (on the EDT, as the panel's timer runs it). */
	private String sidebarTimeLeft()
	{
		String[] text = new String[1];
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			text[0] = harness.ui().getTimeLeftText();
		});
		return text[0];
	}

	@Test
	public void earlyInATaskTheOverlayShowsTheSidebarsHistoricalEstimate()
	{
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, Collections.singletonList(priorTask())));
		assertTrue(harness.play());

		String sidebar = sidebarTimeLeft();
		assertEquals("12 kills at the history's minute a kill", "Time left: ~12m", sidebar);
		assertEquals(sidebar, "Time left: " + value("Time left"));

		// 1 kill in 30 seconds: too little for the live pace, so still the history, for the 11 left.
		now.addAndGet(MINUTE / 2);
		harness.setRemaining(11);
		harness.tick();
		sidebar = sidebarTimeLeft();
		assertEquals("Time left: ~11m", sidebar);
		assertEquals(sidebar, "Time left: " + value("Time left"));

		// 3 kills in 90 seconds: the live pace (30 seconds a kill, 9 left) takes over in both.
		now.addAndGet(MINUTE);
		harness.setRemaining(9);
		harness.tick();
		sidebar = sidebarTimeLeft();
		assertEquals("4m 30s rounds half up", "Time left: ~5m", sidebar);
		assertEquals(sidebar, "Time left: " + value("Time left"));
	}

	@Test
	public void theHistoricalEstimateIsClearedWithItsSession()
	{
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, Collections.singletonList(priorTask())));
		assertTrue(harness.play());
		assertEquals("Time left: ~12m", sidebarTimeLeft());
		assertEquals("~12m", value("Time left"));

		// A new session before the panel's next refresh: the old session's estimate is still the published one.
		harness.stop();
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, Collections.emptyList()));
		assertTrue(harness.play());
		assertEquals("the previous session's estimate is not reused before the panel refreshes",
			LedgerHistory.TIME_LEFT_ESTIMATING, value("Time left"));
		assertEquals(LiveSessionSection.TIME_LEFT_ESTIMATING, sidebarTimeLeft());
		assertEquals(LedgerHistory.TIME_LEFT_ESTIMATING, value("Time left"));
	}
}
