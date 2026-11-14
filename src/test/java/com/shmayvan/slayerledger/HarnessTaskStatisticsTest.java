package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.gameval.ItemID;
import org.junit.Rule;
import org.junit.Test;

/**
 * The current task's per-task statistics as wired in the plugin ({@link PluginHarness}): the live card's
 * time-left fallback until the live pace is reliable, and, since they value nothing, kept when today's prices
 * or the "Loot price" mode change. The History estimate that showed their values went with the advisor card. {@link TaskStatisticsTest} covers the averages.
 */
public class HarnessTaskStatisticsTest
{
	private static final long MINUTE = 60_000L;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	/** The tracker's clock, so active time (and the live time-left estimate) is under the test's control. */
	private final AtomicLong now = new AtomicLong(1_000_000_000L);

	/**
	 * A completed task: 10 kills in 10 minutes, loot 100K GE / 200K high alch, supplies 10K; with its items
	 * ({@link SavedItems}).
	 */
	private static LedgerRecord prior()
	{
		LedgerRecord prior = new LedgerRecord();
		prior.setCreatureId(42);
		prior.setMonster("Abyssal demons");
		prior.setKills(10);
		prior.setActiveMillis(10 * MINUTE);
		prior.setCompleted(true);
		prior.setLootGeValue(100_000L);
		prior.setLootHaValue(200_000L);
		prior.setSuppliesGeValue(10_000L);
		return SavedItems.withItems(prior);
	}

	/** Starts the plugin on a task with {@code remaining} of {@code assigned} kills left and shows {@code prior}. */
	private void start(int remaining, int assigned, LedgerRecord prior)
	{
		SavedItems.define(harness);
		harness.startUp();
		useTestClock();
		harness.setTask(42, "Abyssal demons", remaining, assigned);
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT, Collections.singletonList(prior)));
		// Today's prices of its items are looked up on the client thread and answered on the EDT.
		harness.drain();
	}

	/** Points the plugin's session tracker at {@link #now} (it reads the system clock otherwise). */
	private void useTestClock()
	{
		harness.setTrackerClock(now::get);
	}

	/**
	 * The current task's statistics as the live card's time left and the overlay's pace read them: of the session's
	 * version and cannon use, at its location ({@link LedgerHistoryModel#currentLocation}).
	 */
	private TaskStatistics stats()
	{
		TaskStatistics[] stats = new TaskStatistics[1];
		harness.onEdt(() -> stats[0] = harness.historyModel().taskStatistics(42, "Abyssal demons",
			harness.tracker().getCurrentTaskSelection(), harness.historyModel().currentLocation()));
		return stats[0];
	}

	private String[] timeLeft()
	{
		String[] time = new String[2];
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			time[0] = harness.ui().getTimeLeftText();
			time[1] = harness.ui().getTimeLeftTooltip();
		});
		return time;
	}

	@Test
	public void theStatisticsFeedTheLiveTimeLeftUntilTheLivePaceIsReliable()
	{
		start(10, 10, prior());

		TaskStatistics stats = stats();
		assertEquals(1, stats.getSampleCount());
		assertEquals(Long.valueOf(MINUTE), stats.getMillisPerKill());
		assertEquals("10K of manta rays (200) over 10 kills", 20d, stats.getSuppliesPerKill().get(SavedItems.SUPPLY),
			1e-9);

		assertTrue(harness.startNow());
		String[] time = timeLeft();
		assertEquals("Time left: ~10m", time[0]);
		assertTrue(time[1], time[1].contains("Historical estimate"));
		assertTrue(time[1], time[1].contains("after 3 kills and 1 minute"));
	}

	@Test
	public void aPriceOrLootPriceModeChangeKeepsTheStatistics()
	{
		harness.defineItem(ItemID.DRAGON_BONES, "Dragon bones", 3_000, 5_000);
		start(10, 10, prior());
		assertTrue(harness.startNow());
		timeLeft();
		TaskStatistics stats = stats();
		long[] before = new long[2];
		harness.onEdt(() ->
		{
			before[0] = harness.historyModel().getTaskStatisticsBuildCount();
			before[1] = harness.historyModel().valuationVersion();
		});

		// Another Loot price mode, then a price arriving: the history is valued again, the statistics not.
		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		harness.drain();
		harness.onEdt(() -> harness.historyModel().addCurrentPrices(Collections.singleton(ItemID.DRAGON_BONES),
			Collections.singletonMap(ItemID.DRAGON_BONES, 3_500L)));
		harness.drain();
		assertEquals("Time left: ~10m", timeLeft()[0]);
		assertSame(stats, stats());
		long[] after = new long[2];
		harness.onEdt(() ->
		{
			after[0] = harness.historyModel().getTaskStatisticsBuildCount();
			after[1] = harness.historyModel().valuationVersion();
		});
		assertTrue("the valuation changed", after[1] > before[1]);
		assertEquals("not worked out again", before[0], after[0]);
	}

	@Test
	public void livePaceTakesOverAfterThreeKillsAndOneMinute()
	{
		start(10, 10, prior());
		assertTrue(harness.startNow());
		assertEquals("Time left: ~10m", timeLeft()[0]);

		// 3 kills in 30 seconds: not yet a minute of active time, so still the history (1 minute a kill, 7 left).
		now.addAndGet(MINUTE / 2);
		harness.setRemaining(7);
		harness.tick();
		String[] time = timeLeft();
		assertEquals("Time left: ~7m", time[0]);
		assertTrue(time[1], time[1].contains("Historical estimate"));

		// 3 kills in 90 seconds: 30 seconds a kill, 7 left.
		now.addAndGet(MINUTE);
		harness.tick();
		time = timeLeft();
		assertEquals("3m 30s rounds half up", "Time left: ~4m", time[0]);
		assertTrue(time[1], time[1].contains("Kills per hour"));
	}
}
