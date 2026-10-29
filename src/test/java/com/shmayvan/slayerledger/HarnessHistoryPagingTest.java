package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.runelite.api.gameval.ItemID;
import org.junit.Rule;
import org.junit.Test;

/**
 * The history list's paging and lazy row tooltips as wired in the plugin ({@link PluginHarness}): a long
 * history builds only {@link HistoryListSection#PAGE_SIZE} rows at a time, and a full redraw stays cheap on the EDT.
 */
public class HarnessHistoryPagingTest
{
	@Rule
	public final PluginHarness harness = new PluginHarness();

	private static final int RECORDS = 2_000;
	private static final String[] MONSTERS = {"Abyssal demons", "Dagannoth", "Kurasks", "Gargoyles", "Nechryael",
		"Black demons", "Hellhounds", "Bloodveld"};
	private static final int[] CREATURES = {42, 120, 82, 46, 52, 30, 31, 48};
	private static final long DAY = 24L * 60 * 60_000L;

	private <T> T onEdt(Supplier<T> read)
	{
		List<T> result = new ArrayList<>(1);
		harness.onEdt(() -> result.add(read.get()));
		return result.get(0);
	}

	/** {@link #RECORDS} varied tasks over the last few years, each with a few items, newest first by index. */
	private static List<LedgerRecord> syntheticRecords()
	{
		List<LedgerRecord> records = new ArrayList<>(RECORDS);
		long newest = 1_700_000_000_000L;
		for (int i = 0; i < RECORDS; i++)
		{
			int kind = i % MONSTERS.length;
			LedgerRecord record = new LedgerRecord();
			record.setId("r" + i);
			record.setMonster(MONSTERS[kind]);
			record.setCreatureId(CREATURES[kind]);
			int kills = 80 + i % 120;
			record.setInitialAmount(kills);
			record.setKills(kills);
			record.setActiveMillis((20 + i % 40) * 60_000L);
			record.setEndedAt(newest - i * DAY / 2);
			record.setStartedAt(record.getEndedAt() - record.getActiveMillis());
			boolean completed = i % 7 != 0;
			record.setCompleted(completed);
			record.setPartial(!completed);
			Map<Integer, Long> loot = new HashMap<>();
			loot.put(995, 10_000L + i);
			loot.put(1_000 + kind, 1L + i % 5);
			record.setLoot(loot);
			Map<Integer, Long> prices = new HashMap<>();
			prices.put(995, 1L);
			prices.put(1_000 + kind, 2_500L);
			record.setUnitGePrices(prices);
			record.setLootGeValue(10_000L + i + 2_500L * (1 + i % 5));
			record.setSuppliesGeValue(5_000L + i % 300);
			Map<String, Long> xp = new HashMap<>();
			xp.put("SLAYER", kills * 150L);
			record.setXpGained(xp);
			records.add(record);
		}
		return records;
	}

	@Test
	public void aLongHistoryShowsOnePageAtATimeAndAFullRedrawIsQuickOnTheEdt()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		List<LedgerRecord> records = syntheticRecords();
		harness.onEdt(() ->
		{
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, records);
			// Open every section that draws from the whole history, so the redraw below includes them.
			harness.ui().getTrends().toggle();
			harness.ui().getOverallTotals().toggle();
			harness.ui().getLuckMeter().toggle();
		});
		harness.drain();

		assertEquals(HistoryListSection.PAGE_SIZE, (int) onEdt(() -> harness.ui().getHistory().getRowCount()));
		assertEquals("Show 25 more", onEdt(() -> harness.ui().getHistory().getShowMoreText()));
		assertEquals("Showing 25 of 2000 tasks", onEdt(() -> harness.ui().getHistory().getShowMoreTooltip()));

		List<String> trendRows = onEdt(() -> harness.ui().getTrends().getRows());
		assertEquals(MONSTERS.length, trendRows.size());
		assertEquals(1, (int) onEdt(() -> harness.ui().getOverallTotals().getRows().size()));

		// A full redraw on the EDT, as a reload of the ledger after a save does: the history list, Trends, Overall
		// totals, the luck meter, the estimate and the advice. Measured on the EDT only, after a first warm-up redraw,
		// as the best of a few runs so a CI pause doesn't fail it.
		long best = Long.MAX_VALUE;
		for (int run = 0; run < 5; run++)
		{
			long[] nanos = new long[1];
			int ranked = onEdt(() -> harness.historyModel().getRankingBuildCount());
			harness.onEdt(() ->
			{
				long start = System.nanoTime();
				harness.historyModel().setRecords(PluginHarness.ACCOUNT, records);
				nanos[0] = System.nanoTime() - start;
			});
			// Trends and the totals were queued for the executor, not worked out on the EDT; the rows shown stay.
			assertTrue(harness.executor.pendingCount() > 0);
			assertEquals(ranked + 1, (int) onEdt(() -> harness.historyModel().getRankingBuildCount()));
			assertEquals(trendRows, onEdt(() -> harness.ui().getTrends().getRows()));
			harness.drain();
			assertEquals(trendRows, onEdt(() -> harness.ui().getTrends().getRows()));
			if (run > 0)
			{
				best = Math.min(best, nanos[0]);
			}
		}
		System.out.println("HarnessHistoryPagingTest: full redraw of 2,000 records on the EDT: " + best / 1_000 + " us");
		assertTrue("full redraw took " + best / 1_000_000 + " ms on the EDT", best < 100_000_000L);
		assertEquals(HistoryListSection.PAGE_SIZE, (int) onEdt(() -> harness.ui().getHistory().getRowCount()));
	}

	@Test
	public void showMoreAddsAPageAndAnotherMonsterGoesBackToTheFirst()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		List<LedgerRecord> records = syntheticRecords().subList(0, 60);
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT, records));
		harness.drain();

		harness.onEdt(() -> harness.ui().getHistory().clickShowMore());
		assertEquals(50, (int) onEdt(() -> harness.ui().getHistory().getRowCount()));
		assertEquals("Show 10 more", onEdt(() -> harness.ui().getHistory().getShowMoreText()));
		harness.onEdt(() -> harness.ui().getHistory().clickShowMore());
		assertEquals(60, (int) onEdt(() -> harness.ui().getHistory().getRowCount()));
		assertNull(onEdt(() -> harness.ui().getHistory().getShowMoreText()));

		// A reload of the same account's records (a save, an edit) keeps the rows opened up.
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT, records));
		harness.drain();
		assertEquals(60, (int) onEdt(() -> harness.ui().getHistory().getRowCount()));

		// Another monster starts from its first page; so does "All" again.
		harness.onEdt(() -> harness.ui().getHistory().selectFilter("Abyssal demons"));
		assertEquals(8, (int) onEdt(() -> harness.ui().getHistory().getRowCount()));
		assertNull(onEdt(() -> harness.ui().getHistory().getShowMoreText()));
		harness.onEdt(() -> harness.ui().getHistory().selectFilter(LedgerHistory.ALL));
		assertEquals(HistoryListSection.PAGE_SIZE, (int) onEdt(() -> harness.ui().getHistory().getRowCount()));

		// Another account's history starts from its first page.
		harness.onEdt(() -> harness.ui().getHistory().clickShowMore());
		assertEquals(50, (int) onEdt(() -> harness.ui().getHistory().getRowCount()));
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.OTHER_ACCOUNT, records));
		harness.drain();
		assertEquals(HistoryListSection.PAGE_SIZE, (int) onEdt(() -> harness.ui().getHistory().getRowCount()));
	}

	@Test
	public void rowTooltipsAreTheSameTextBuiltOnDemand()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		List<LedgerRecord> records = syntheticRecords().subList(0, 30);
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT, records));
		harness.drain();

		List<String> tooltips = onEdt(() -> harness.ui().getHistory().getTooltips());
		// Valued at today's prices.
		Map<Integer, Long> today = onEdt(() -> new HashMap<>(harness.historyModel().shownPrices()));
		assertEquals(HistoryListSection.PAGE_SIZE, tooltips.size());
		for (int i = 0; i < tooltips.size(); i++)
		{
			LedgerRecord record = records.get(i);
			assertEquals(LedgerHistory.rowTooltip(record, HistoryPricing.value(record, LootPriceMode.GE, today),
				TaskLocation.UNKNOWN), tooltips.get(i));
		}
		// Asked again, the same text.
		assertEquals(tooltips, onEdt(() -> harness.ui().getHistory().getTooltips()));
	}

	@Test
	public void showMoreIsIgnoredWhileTheRowsWaitForTodaysPrices()
	{
		harness.defineItem(ItemID.DRAGON_BONES, "Dragon bones", 2_000, 5_000);
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		List<LedgerRecord> records = syntheticRecords().subList(0, 60);
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT, records));
		harness.drain();
		assertEquals(HistoryListSection.PAGE_SIZE, (int) onEdt(() -> harness.ui().getHistory().getRowCount()));

		// A save with an item not priced yet: the rows wait for its price, and clicks meanwhile are ignored.
		LedgerRecord saved = syntheticRecords().get(0);
		saved.setId("saved");
		saved.setEndedAt(saved.getEndedAt() + DAY);
		saved.setLoot(Collections.singletonMap(ItemID.DRAGON_BONES, 10L));
		List<LedgerRecord> withSaved = new ArrayList<>(records);
		withSaved.add(0, saved);
		harness.onEdt(() ->
		{
			// The lookup is queued for the client thread, so it cannot answer inside this EDT task.
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, withSaved);
			assertTrue(harness.historyModel().isWaitingForCurrentPrices());
			for (int click = 0; click < 3; click++)
			{
				harness.ui().getHistory().clickShowMore();
			}
			assertEquals(HistoryListSection.PAGE_SIZE, harness.ui().getHistory().getRowCount());
		});
		harness.drain();
		assertFalse(onEdt(() -> harness.historyModel().isWaitingForCurrentPrices()));
		assertEquals("the clicks while waiting did not pile up", HistoryListSection.PAGE_SIZE,
			(int) onEdt(() -> harness.ui().getHistory().getRowCount()));

		// Once the prices are here, a click adds a page again.
		harness.onEdt(() -> harness.ui().getHistory().clickShowMore());
		assertEquals(50, (int) onEdt(() -> harness.ui().getHistory().getRowCount()));
	}

	@Test
	public void theFilterDroppingTheSelectedMonsterGoesBackToTheFirstPage()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		List<LedgerRecord> demons = renamed(syntheticRecords().subList(0, 60), "Abyssal demons");
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT, demons));
		harness.drain();
		harness.onEdt(() ->
		{
			harness.ui().getHistory().selectFilter("Abyssal demons");
			harness.ui().getHistory().clickShowMore();
		});
		assertEquals(50, (int) onEdt(() -> harness.ui().getHistory().getRowCount()));

		// The same account's records without that monster (all renamed): "All", from its first page.
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT,
			renamed(syntheticRecords().subList(0, 60), "Kurasks")));
		harness.drain();
		assertEquals(HistoryListSection.PAGE_SIZE, (int) onEdt(() -> harness.ui().getHistory().getRowCount()));
		assertEquals("Show 25 more", onEdt(() -> harness.ui().getHistory().getShowMoreText()));
	}

	/** {@code records}, each now a task of {@code monster}. */
	private static List<LedgerRecord> renamed(List<LedgerRecord> records, String monster)
	{
		for (LedgerRecord record : records)
		{
			record.setMonster(monster);
		}
		return records;
	}
}
