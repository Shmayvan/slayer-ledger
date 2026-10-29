package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.gameval.ItemID;
import org.junit.Rule;
import org.junit.Test;

/**
 * The current task's historical estimate as wired in the panel ({@link PluginHarness}): the History lines for
 * the kills left, the cache cleared by the "Loot price" mode and today's prices, and the time-left fallback until
 * the live pace is reliable. {@link TaskStatisticsTest} covers the averages.
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

	private String estimateText()
	{
		String[] text = new String[1];
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			text[0] = harness.ui().getHistoryEstimate().getText();
		});
		return text[0];
	}

	/** The History estimate's tooltip after a refresh. */
	private String estimateTooltip()
	{
		String[] tooltip = new String[1];
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			tooltip[0] = harness.ui().getHistoryEstimate().getTooltip();
		});
		return tooltip[0];
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
	public void showsIdleAssignmentEstimateAndUsesHistoryForLiveTimeFallback()
	{
		start(10, 10, prior());

		String estimate = estimateText();
		assertNotNull(estimate);
		assertEquals("History (1 task) | 10 kills ~0:10:00 | Loot (GE): 100K | Supplies: 10K", estimate);
		String[] tooltip = new String[1];
		harness.onEdt(() -> tooltip[0] = harness.ui().getHistoryEstimate().getTooltip());
		assertTrue(tooltip[0], tooltip[0].contains("Average task duration"));

		assertTrue(harness.play());
		String[] time = timeLeft();
		assertEquals("Time left: ~10m", time[0]);
		assertTrue(time[1], time[1].contains("Historical estimate"));
		assertTrue(time[1], time[1].contains("after 3 kills and 1 minute"));
		assertEquals("still shown while running (owner decision)", "History (1 task) | 10 kills ~0:10:00 | "
			+ "Loot (GE): 100K | Supplies: 10K", estimateText());
	}

	@Test
	public void idleEstimateUsesTheKillsLeftNotTheAssignedSize()
	{
		// For example after a partial save at 3 of 10, or kills made before Play.
		start(7, 10, prior());

		assertEquals("History (1 task) | 7 kills ~0:07:00 | Loot (GE): 70K | Supplies: 7,000", estimateText());
	}

	@Test
	public void theEstimateIsAtTodaysPricesInTheLootPriceMode()
	{
		// Saved at 2,000 gp a bone; 3,000 gp today.
		harness.defineItem(ItemID.DRAGON_BONES, "Dragon bones", 3_000, 5_000);
		LedgerRecord prior = prior();
		prior.setLoot(Collections.singletonMap(ItemID.DRAGON_BONES, 10L));
		prior.setUnitGePrices(Collections.singletonMap(ItemID.DRAGON_BONES, 2_000L));
		prior.setUnitHaPrices(Collections.singletonMap(ItemID.DRAGON_BONES, 5_000L));
		prior.setLootGeValue(20_000L);
		prior.setLootHaValue(50_000L);
		// No supplies used: supplies are revalued from the (empty) tally, as in the history.
		prior.setSupplies(null);
		prior.setSuppliesGeValue(0L);
		start(10, 10, prior);
		// The lookup is queued for the client thread, and its answer for the EDT.
		harness.drain();
		assertEquals("History (1 task) | 10 kills ~0:10:00 | Loot (GE): 30K | Supplies: 0", estimateText());
		String[] tooltip = new String[1];
		harness.onEdt(() -> tooltip[0] = harness.ui().getHistoryEstimate().getTooltip());
		assertTrue(tooltip[0], tooltip[0].contains("GP averages use today's prices in GE mode"));
		assertFalse(tooltip[0], tooltip[0].contains("saved prices"));

		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		assertEquals("History (1 task) | 10 kills ~0:10:00 | Loot (HA): 50K | Supplies: 0", estimateText());
	}

	@Test
	public void theTooltipShowsProfitPerKillUnderLootAndSupplies()
	{
		start(10, 10, prior());
		assertTrue(estimateTooltip(), estimateTooltip().contains("<br>Loot per kill: 10K gp"
			+ "<br>Supplies per kill: 1,000 gp<br>Profit per kill: 9,000 gp<br>"));

		// Loot at the Loot price setting, supplies at GE.
		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		assertTrue(estimateTooltip(), estimateTooltip().contains("<br>Profit per kill: 19K gp<br>"));
	}

	@Test
	public void profitPerKillIsApproximateALossOrUnknownLikeItsParts()
	{
		// Saved with its totals only, with a supply price unavailable: no loot, supplies marked ~.
		LedgerRecord prior = prior();
		prior.setLoot(null);
		prior.setSupplies(null);
		prior.setUnitGePrices(null);
		prior.setUnitHaPrices(null);
		prior.setLootGeValue(0L);
		prior.setSuppliesUnpricedItems(1);
		start(10, 10, prior);
		assertTrue(estimateTooltip(), estimateTooltip().contains("<br>Loot per kill: 0 gp"
			+ "<br>Supplies per kill: ~1,000 gp<br>Profit per kill: ~-1,000 gp<br>"));

		// A task saved before values were tracked: no GP averages at all.
		LedgerRecord legacy = prior();
		legacy.setLootGeValue(null);
		legacy.setLootHaValue(null);
		legacy.setSuppliesGeValue(null);
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT,
			Collections.singletonList(legacy)));
		harness.drain();
		assertTrue(estimateTooltip(), estimateTooltip().contains("<br>Loot per kill: -<br>Supplies per kill: -"
			+ "<br>Profit per kill: -<br>"));
	}

	@Test
	public void livePaceTakesOverAfterThreeKillsAndOneMinute()
	{
		start(10, 10, prior());
		assertTrue(harness.play());
		assertEquals("Time left: ~10m", timeLeft()[0]);

		// 3 kills in 30 seconds: not yet a minute of active time, so still the history (1 minute a kill, 7 left).
		now.addAndGet(MINUTE / 2);
		harness.setRemaining(7);
		harness.tick();
		String[] time = timeLeft();
		assertEquals("Time left: ~7m", time[0]);
		assertTrue(time[1], time[1].contains("Historical estimate"));
		assertEquals("History (1 task) | 7 kills ~0:07:00 | Loot (GE): 70K | Supplies: 7,000", estimateText());

		// 3 kills in 90 seconds: 30 seconds a kill, 7 left.
		now.addAndGet(MINUTE);
		harness.tick();
		time = timeLeft();
		assertEquals("3m 30s rounds half up", "Time left: ~4m", time[0]);
		assertTrue(time[1], time[1].contains("Kills per hour"));
	}
}
