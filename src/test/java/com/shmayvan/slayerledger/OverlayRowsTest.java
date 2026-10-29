package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.List;
import org.junit.Test;

public class OverlayRowsTest
{
	private static SessionTracker.LiveTask task(int kills, long activeMillis, int remaining)
	{
		return new SessionTracker.LiveTask(SessionTracker.State.RUNNING, "session", "Wyrms", 42, kills, activeMillis,
			remaining);
	}

	private static SessionValuation.Values values(LootPriceMode mode, long loot, int lootUnpriced, long supplies)
	{
		return SessionValuation.Values.builder().sessionId("session").lootPriceMode(mode).lootValue(loot)
			.lootValueUnpriced(lootUnpriced).lootGe(loot).lootHa(loot).suppliesGe(supplies).suppliesHa(supplies)
			.lootUnpriced(lootUnpriced).build();
	}

	@Test
	public void formatsAllRowsLikeTheSidebar()
	{
		SessionValuation.Values values = values(LootPriceMode.GE, 2_500_000, 0, 400_000);
		OverlayRows.Data data = new OverlayRows.Data(task(172, 6_012_000, 23), values);

		List<OverlayRows.Row> rows = OverlayRows.rows(data, true, true, true, true);

		assertEquals(5, rows.size());
		assertEquals("Task", rows.get(0).getLabel());
		assertEquals("Wyrms", rows.get(0).getValue());
		assertEquals("Left", rows.get(1).getLabel());
		assertEquals("23", rows.get(1).getValue());
		assertEquals("Kills", rows.get(2).getLabel());
		assertEquals("172", rows.get(2).getValue());
		assertEquals("Time left", rows.get(3).getLabel());
		assertEquals("13m 23.9s rounds down", "~13m", rows.get(3).getValue());
		assertEquals("Profit (GE)", rows.get(4).getLabel());
		assertEquals("no added sign (owner decision)", "2.1M gp", rows.get(4).getValue());
		// The same text as the sidebar's lines, without their labels.
		assertEquals(LiveSessionSection.timeLeftText(6_012_000, 172, 23), "Time left: " + rows.get(3).getValue());
		assertEquals(LiveSessionSection.liveProfitText(values), "Profit (GE): " + rows.get(4).getValue());
	}

	@Test
	public void approximateLossAtHighAlchMatchesTheSidebar()
	{
		SessionValuation.Values values = values(LootPriceMode.HIGH_ALCH, 100, 1, 200);

		List<OverlayRows.Row> rows = OverlayRows.rows(new OverlayRows.Data(task(2, 45_000, 5), values),
			false, false, false, true);

		assertEquals(1, rows.size());
		assertEquals("Profit (HA)", rows.get(0).getLabel());
		assertEquals("~-100 gp", rows.get(0).getValue());
		assertEquals(LiveSessionSection.liveProfitText(values), "Profit (HA): " + rows.get(0).getValue());
	}

	@Test
	public void unknownKillsLeftShowAQuestionMarkAndNoTimeLeft()
	{
		OverlayRows.Data data = new OverlayRows.Data(task(30, 600_000, SessionTracker.LiveTask.UNKNOWN_REMAINING),
			null);

		List<OverlayRows.Row> rows = OverlayRows.rows(data, true, false, true, true);

		assertEquals("no live values yet: no profit row", 3, rows.size());
		assertEquals("Task", rows.get(0).getLabel());
		assertEquals("Wyrms", rows.get(0).getValue());
		assertEquals("Left", rows.get(1).getLabel());
		assertEquals(OverlayRows.UNKNOWN_REMAINING, rows.get(1).getValue());
		assertEquals("?", rows.get(1).getValue());
		assertEquals("Time left", rows.get(2).getLabel());
		assertEquals(LedgerHistory.NO_VALUE, rows.get(2).getValue());
	}

	@Test
	public void taskNameAndKillsLeftAreSeparateRowsSoTheOverlayDoesNotWrap()
	{
		OverlayRows.Data data = new OverlayRows.Data(task(4, 120_000, 8), null);

		List<OverlayRows.Row> rows = OverlayRows.rows(data, true, false, false, false);

		assertEquals(2, rows.size());
		assertEquals("Task", rows.get(0).getLabel());
		assertEquals("only the name", "Wyrms", rows.get(0).getValue());
		assertEquals("directly under the task", "Left", rows.get(1).getLabel());
		assertEquals("8", rows.get(1).getValue());
		assertFalse(rows.get(0).isWarning());
		assertFalse(rows.get(1).isWarning());
	}

	@Test
	public void taskSettingOffHidesBothTheTaskAndLeftRows()
	{
		OverlayRows.Data data = new OverlayRows.Data(task(4, 120_000, 8), values(LootPriceMode.GE, 1, 0, 0));

		List<OverlayRows.Row> rows = OverlayRows.rows(data, false, true, true, true);

		assertEquals(3, rows.size());
		assertEquals("Kills", rows.get(0).getLabel());
		assertEquals("Time left", rows.get(1).getLabel());
		assertEquals("Profit (GE)", rows.get(2).getLabel());
		for (OverlayRows.Row row : rows)
		{
			assertFalse("Task".equals(row.getLabel()));
			assertFalse("Left".equals(row.getLabel()));
		}
	}

	@Test
	public void timeLeftIsEstimatingUntilThereAreEnoughKills()
	{
		List<OverlayRows.Row> rows = OverlayRows.rows(new OverlayRows.Data(task(1, 30_000, 40), null),
			false, false, true, false);

		assertEquals(1, rows.size());
		assertEquals(LedgerHistory.TIME_LEFT_ESTIMATING, rows.get(0).getValue());
	}

	@Test
	public void noRowsWithoutDataOrWithEveryRowOff()
	{
		assertTrue(OverlayRows.rows(null, true, true, true, true).isEmpty());
		OverlayRows.Data data = new OverlayRows.Data(task(1, 30_000, 40), values(LootPriceMode.GE, 1, 0, 0));
		assertTrue(OverlayRows.rows(data, false, false, false, false).isEmpty());
	}

	@Test
	public void pausedSessionShowsAWarningRowFirst()
	{
		SessionTracker.LiveTask paused = new SessionTracker.LiveTask(SessionTracker.State.PAUSED, "session", "Wyrms", 42,
			172, 6_012_000, 23);

		List<OverlayRows.Row> rows = OverlayRows.rows(new OverlayRows.Data(paused, null), true, true, true, true);

		assertEquals(5, rows.size());
		assertEquals(OverlayRows.PAUSED_LABEL, rows.get(0).getLabel());
		assertEquals("Paused", rows.get(0).getLabel());
		assertEquals("Resume in sidebar", rows.get(0).getValue());
		assertTrue(rows.get(0).isWarning());
		assertEquals("Task", rows.get(1).getLabel());
		assertFalse(rows.get(1).isWarning());
		assertEquals("Left", rows.get(2).getLabel());
		assertEquals("Kills", rows.get(3).getLabel());
		assertEquals("Time left", rows.get(4).getLabel());
	}

	@Test
	public void pausedRowIsShownEvenWithEveryRowSettingOff()
	{
		SessionTracker.LiveTask paused = new SessionTracker.LiveTask(SessionTracker.State.PAUSED, "session", "Wyrms", 42,
			0, 0, 23);

		List<OverlayRows.Row> rows = OverlayRows.rows(new OverlayRows.Data(paused, null), false, false, false, false);

		assertEquals(1, rows.size());
		assertEquals(OverlayRows.PAUSED_LABEL, rows.get(0).getLabel());
	}

	@Test
	public void runningSessionHasNoPausedRow()
	{
		List<OverlayRows.Row> rows = OverlayRows.rows(new OverlayRows.Data(task(1, 1_000, 5), null), true, true, true,
			true);

		for (OverlayRows.Row row : rows)
		{
			assertFalse(row.isWarning());
			assertFalse(OverlayRows.PAUSED_LABEL.equals(row.getLabel()));
		}
		assertTrue(OverlayRows.rows(new OverlayRows.Data(task(1, 1_000, 5), null), false, false, false, false).isEmpty());
	}

	@Test
	public void notTrackingIsOneWarningRowWhateverTheSettings()
	{
		OverlayRows.Data data = OverlayRows.Data.notTracking();

		for (boolean on : new boolean[]{true, false})
		{
			List<OverlayRows.Row> rows = OverlayRows.rows(data, on, on, on, on);
			assertEquals(1, rows.size());
			assertEquals(OverlayRows.NOT_TRACKING_LABEL, rows.get(0).getLabel());
			assertEquals("Not tracking", rows.get(0).getLabel());
			assertEquals("Play in sidebar", rows.get(0).getValue());
			assertTrue(rows.get(0).isWarning());
		}
	}

	@Test
	public void sessionRowsNeverIncludeNotTracking()
	{
		SessionTracker.LiveTask paused = new SessionTracker.LiveTask(SessionTracker.State.PAUSED, "session", "Wyrms", 42,
			1, 1_000, 5);
		for (OverlayRows.Data data : new OverlayRows.Data[]{
			new OverlayRows.Data(task(1, 1_000, 5), null), new OverlayRows.Data(paused, null)})
		{
			for (OverlayRows.Row row : OverlayRows.rows(data, true, true, true, true))
			{
				assertFalse(OverlayRows.NOT_TRACKING_LABEL.equals(row.getLabel()));
			}
		}
	}

	@Test
	public void timeLeftUsesTheHistoricalPaceLikeTheSidebar()
	{
		OverlayRows.Data data = new OverlayRows.Data(task(1, 30_000, 40), null, 45_000L);

		List<OverlayRows.Row> rows = OverlayRows.rows(data, false, false, true, false);

		assertEquals(1, rows.size());
		assertEquals("~30m", rows.get(0).getValue());
		assertEquals(LiveSessionSection.timeLeftText(30_000, 1, 40, 45_000L), "Time left: " + rows.get(0).getValue());
	}

	@Test
	public void historicalPaceBelongsToItsSessionOnly()
	{
		OverlayRows.HistoricalPace pace = new OverlayRows.HistoricalPace("session", 45_000L);

		assertEquals(Long.valueOf(45_000L), pace.millisPerKillFor(task(1, 30_000, 40)));
		assertEquals(null, pace.millisPerKillFor(new SessionTracker.LiveTask(SessionTracker.State.RUNNING, "other",
			"Wyrms", 42, 1, 30_000, 40)));
		assertEquals(null, pace.millisPerKillFor(null));
	}
}
