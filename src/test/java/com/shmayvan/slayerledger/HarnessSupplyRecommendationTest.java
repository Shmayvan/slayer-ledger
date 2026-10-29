package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Rule;
import org.junit.Test;

/**
 * What to bring as wired in the panel ({@link PluginHarness}): the collapsible section below History, its rows
 * and their order, and its rebuilds with the estimate cache. {@link SupplyRecommendationTest} covers the amounts.
 */
public class HarnessSupplyRecommendationTest
{
	@Rule
	public final PluginHarness harness = new PluginHarness();

	/** Two completed tasks, 30 kills: 52 prayer doses (12,000 gp a 4-dose potion), 90 cannonballs (200 gp), 3 trips. */
	private static List<LedgerRecord> twoTasks()
	{
		LedgerRecord first = record(10, map(100, 12, 101, 30), 1);
		first.setSupplyDoses(Collections.singletonMap(100, 4));
		first.setUnitGePrices(map(100, 12_000, 101, 200));
		LedgerRecord second = record(20, map(100, 40, 101, 60), 2);
		second.setSupplyDoses(Collections.singletonMap(100, 4));
		second.setUnitGePrices(map(100, 12_000, 101, 200));
		return Arrays.asList(first, second);
	}

	private void start(int remaining, List<LedgerRecord> records)
	{
		start(remaining, records, 12_000, 200);
	}

	/** Starts with today's GE prices of the potion and the cannonballs (0: unavailable). */
	private void start(int remaining, List<LedgerRecord> records, long potionGe, long cannonballGe)
	{
		harness.defineItem(100, "Prayer potion(4)", potionGe, 200);
		harness.defineItem(101, "Cannonballs", cannonballGe, 0);
		harness.startUp();
		harness.setTask(42, "Abyssal demons", remaining, 10);
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		setRecords(records);
	}

	private void setRecords(List<LedgerRecord> records)
	{
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT, records));
		// The item names are looked up on the client thread and answered on the EDT.
		harness.drain();
	}

	private String header()
	{
		String[] text = new String[1];
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			text[0] = harness.ui().getHistoryEstimate().getBringText();
		});
		return text[0];
	}

	private List<String> lines()
	{
		@SuppressWarnings("unchecked")
		List<String>[] lines = new List[1];
		harness.onEdt(() -> lines[0] = harness.ui().getHistoryEstimate().getBringLines());
		return lines[0];
	}

	private String tooltip()
	{
		String[] text = new String[1];
		harness.onEdt(() -> text[0] = harness.ui().getHistoryEstimate().getBringTooltip());
		return text[0];
	}

	private String rowTooltip(int index)
	{
		String[] text = new String[1];
		harness.onEdt(() -> text[0] = harness.ui().getHistoryEstimate().getBringRowTooltip(index));
		return text[0];
	}

	private int builds()
	{
		int[] builds = new int[1];
		harness.onEdt(() -> builds[0] = harness.ui().getHistoryEstimate().getBringBuildCount());
		return builds[0];
	}

	private void open()
	{
		harness.onEdt(() -> harness.ui().getHistoryEstimate().clickBringTitle());
	}

	@Test
	public void collapsedByDefaultAndOpensToRowsSortedByValue()
	{
		start(7, twoTasks());

		// 3 trips over 30 kills, times 7 kills left: 0.7, rounded up.
		assertEquals("What to bring (2 tasks) | Show | ~1 bank trip | 2 supplies", header());
		assertTrue(lines().isEmpty());
		String tooltip = tooltip();
		assertTrue(tooltip, tooltip.contains("Suggested for the 7 kills left."));
		assertTrue(tooltip, tooltip.contains("over 2 completed tasks (30 kills) with supplies or values"));
		assertTrue(tooltip, tooltip.contains("rounded up to whole tracked units"));
		assertTrue(tooltip, tooltip.contains("pooled trips per kill over 2 completed tasks (30 kills)"));

		open();
		assertEquals("What to bring (2 tasks) | Hide | ~1 bank trip | 2 supplies", header());
		// 52 doses / 30 * 7 = 12.1 -> 13 (39,000 gp); 90 / 30 * 7 = 21 (4,200 gp): the potion first despite fewer.
		assertEquals(Arrays.asList("Prayer potion | 13 doses", "Cannonballs | x21"), lines());
		assertEquals("<html>13 doses of Prayer potion for the 7 kills left</html>", rowTooltip(0));
		assertEquals("<html>21 x Cannonballs for the 7 kills left</html>", rowTooltip(1));

		open();
		assertEquals("What to bring (2 tasks) | Show | ~1 bank trip | 2 supplies", header());
		assertTrue(lines().isEmpty());
	}

	@Test
	public void unpricedRowsSortByAmountThenName()
	{
		LedgerRecord record = record(10, map(100, 4, 101, 10, 102, 10), 0);
		// No GE price today for any of them: the views wait for them until the wait runs out.
		start(10, Collections.singletonList(record), 0, 0);
		harness.onEdt(() ->
		{
			harness.historyModel().expireCurrentPricesWait();
			harness.historyModel().endExpiredCurrentPricesWait();
		});
		// The item names are looked up on the client thread and answered on the EDT.
		harness.drain();
		harness.onEdt(() -> harness.historyModel().addItemNames(Collections.singleton(102),
			Collections.singletonMap(102, "Arrows"), null));
		header();
		open();

		assertEquals(Arrays.asList("Arrows | x10", "Cannonballs | x10", "Prayer potion(4) | x4"), lines());
		assertEquals("What to bring (1 task) | Hide | No bank trips | 3 supplies", header());
	}

	@Test
	public void rebuildsOnlyWhenTheKillsLeftChangeNotOnTheTimer()
	{
		start(7, twoTasks());
		open();
		header();
		int builds = builds();
		header();
		header();
		assertEquals("the refresh timer rebuilds nothing", builds, builds());

		harness.setRemaining(1);
		harness.tick();
		assertEquals("What to bring (2 tasks) | Hide | ~1 bank trip | 2 supplies", header());
		assertEquals(builds + 1, builds());
		// 52 / 30 = 1.7 -> 2 doses; 90 / 30 = 3.
		assertEquals(Arrays.asList("Prayer potion | 2 doses", "Cannonballs | x3"), lines());
		assertTrue(tooltip(), tooltip().contains("Suggested for the 1 kill left."));
		assertEquals("<html>3 x Cannonballs for the 1 kill left</html>", rowTooltip(1));
	}

	@Test
	public void hiddenWithoutATaskAndBackWhenItReturnsStillOpen()
	{
		start(7, twoTasks());
		open();
		assertEquals("What to bring (2 tasks) | Hide | ~1 bank trip | 2 supplies", header());

		harness.setRemaining(0);
		harness.tick();
		assertNull(header());

		harness.setRemaining(7);
		harness.tick();
		assertEquals("open state kept for the session", "What to bring (2 tasks) | Hide | ~1 bank trip | 2 supplies",
			header());
		assertEquals(Arrays.asList("Prayer potion | 13 doses", "Cannonballs | x21"), lines());
	}

	@Test
	public void hiddenWithoutHistoryAndUpdatedWhenRecordsChange()
	{
		start(7, Collections.emptyList());
		assertNull(header());

		setRecords(twoTasks());
		assertEquals("What to bring (2 tasks) | Show | ~1 bank trip | 2 supplies", header());

		setRecords(Collections.singletonList(record(10, map(101, 20), 5)));
		open();
		// 5 trips over 10 kills, times 7: 3.5 -> 4; 20 / 10 * 7 = 14.
		assertEquals("What to bring (1 task) | Hide | ~4 bank trips | 1 supply", header());
		assertEquals(Collections.singletonList("Cannonballs | x14"), lines());
	}

	@Test
	public void itemNamesArrivingLaterReplaceItemIds()
	{
		start(10, Collections.singletonList(record(10, map(300, 5), 0)));
		open();
		assertEquals(Collections.singletonList("Item 300 | x5"), lines());

		harness.onEdt(() -> harness.historyModel().addItemNames(Collections.singleton(300),
			Collections.singletonMap(300, "<b>Bolts</b>"), null));
		header();
		assertEquals("the name is shown as text", Collections.singletonList("<b>Bolts</b> | x5"), lines());
		assertEquals("<html>5 x &lt;b&gt;Bolts&lt;/b&gt; for the 10 kills left</html>", rowTooltip(0));
	}

	@Test
	public void singularAndDoseUnits()
	{
		harness.defineItem(ItemID.ANCHOVIE_PIZZA, "Anchovy pizza", 100, 0);
		harness.defineItem(ItemID.AMULET_OF_GLORY_6, "Amulet of glory(6)", 10_000, 0);
		LedgerRecord record = record(10, map(100, 1, ItemID.ANCHOVIE_PIZZA, 20, ItemID.AMULET_OF_GLORY_6, 1), 1);
		Map<Integer, Integer> doses = new java.util.HashMap<>();
		doses.put(100, 4);
		doses.put(ItemID.ANCHOVIE_PIZZA, 2);
		doses.put(ItemID.AMULET_OF_GLORY_6, 6);
		record.setSupplyDoses(doses);
		start(1, Collections.singletonList(record));
		open();

		assertEquals("What to bring (1 task) | Hide | ~1 bank trip | 3 supplies", header());
		// At today's prices: a dose 3,000 gp, a charge 1,667 gp, 2 bites 100 gp.
		assertEquals(Arrays.asList("Prayer potion | 1 dose", "Amulet of glory | 1 charge", "Anchovy pizza | 2 bites"),
			lines());
		assertEquals("<html>1 dose of Prayer potion for the 1 kill left</html>", rowTooltip(0));
	}

	@Test
	public void bankOnlyAndNoTrackedSuppliesMessages()
	{
		LedgerRecord bankOnly = record(10, null, 2);
		start(10, Collections.singletonList(bankOnly));
		open();
		assertEquals("What to bring (1 task) | Hide | ~2 bank trips | ", header());
		assertEquals(Collections.singletonList("No supply data in history"), lines());
		assertTrue(tooltip(), tooltip().contains("Supplies: no completed task recorded supplies or values."));

		LedgerRecord none = record(10, Collections.emptyMap(), 0);
		none.setLootGeValue(0L);
		setRecords(Collections.singletonList(none));
		assertEquals("What to bring (1 task) | Hide | No bank trips | ", header());
		assertEquals(Collections.singletonList("No tracked supplies in history"), lines());

		LedgerRecord unknownTrips = record(10, Collections.emptyMap(), 0);
		unknownTrips.setBankTrips(null);
		setRecords(Collections.singletonList(unknownTrips));
		assertEquals("What to bring (1 task) | Hide |  | ", header());
		assertTrue(tooltip(), tooltip().contains("Bank trips: no completed task recorded them."));
	}

	private static LedgerRecord record(int kills, Map<Integer, Long> supplies, int bankTrips)
	{
		LedgerRecord record = new LedgerRecord();
		record.setCreatureId(42);
		record.setMonster("Abyssal demons");
		record.setKills(kills);
		record.setActiveMillis(kills * 1_000L);
		record.setCompleted(true);
		record.setSupplies(supplies);
		record.setBankTrips(bankTrips);
		return record;
	}

	private static Map<Integer, Long> map(int... values)
	{
		Map<Integer, Long> result = new java.util.HashMap<>();
		for (int i = 0; i < values.length; i += 2)
		{
			result.put(values[i], (long) values[i + 1]);
		}
		return result;
	}
}
