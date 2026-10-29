package com.shmayvan.slayerledger;

import com.google.gson.Gson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Rule;
import org.junit.Test;

/**
 * The task box's advice row as wired in the plugin ({@link PluginHarness}): shown for the current task with
 * enough history, hidden otherwise, following the "Task advisor" settings live and never worked out on the refresh
 * timer. {@link TaskAdvisorTest} covers the rules.
 */
public class HarnessTaskAdvisorTest
{
	private static final long HOUR = 60 * 60_000L;
	/** The current task's creature key; the records' monsters are this and the next ones. */
	private static final int TASK = 42;
	/** Tasks per monster in {@link #history}: the fewest the advisor counts. */
	private static final int TASKS = 3;
	private static final Gson GSON = new Gson();

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Test
	public void shownAsTheThirdLineOnAnAssignmentWithEnoughHistory()
	{
		harness.setAssignment(SlayerMaster.KONAR, 0, null);
		harness.setVarbit(VarbitID.SLAYER_TASKS_COMPLETED, 47);
		start(monsters(6));

		assertEquals("Advice: Consider skip (n=3)", advice());
		String tooltip = tooltip();
		assertTrue(tooltip, tooltip.contains("Profit (GE)/h 10K: 1st lowest of your 6 monsters (bottom 25%)"));
		assertEquals(Arrays.asList("Abyssal demons · 10 left", "Konar · bonus in 3",
			"Advice: Consider skip (n=3)"), taskBoxRows());
	}

	@Test
	public void hiddenWithTooLittleHistoryOrNoTask()
	{
		start(monsters(5));
		assertNull("5 monsters", advice());

		// The lowest of 10 with 3 tasks: Consider block needs 5, so Consider skip.
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT, history(monsters(10))));
		assertEquals("Advice: Consider skip (n=3)", advice());
		assertTrue(tooltip(), tooltip().contains("(bottom 10%, block from 5 tasks)"));
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT, history(monsters(10), 5)));
		assertEquals("Advice: Consider block (n=5)", advice());
		assertTrue(tooltip(), tooltip().contains("From 5 completed tasks of this monster (n)"));
		// The main advice pools Catacombs variant and cannon tasks, which Trends shows apart.
		assertTrue(tooltip(), tooltip().contains(
			"as in the Trends list, Catacombs variant and cannon tasks pooled with their task."));

		harness.setRemaining(0);
		harness.tick();
		assertNull("no task", advice());
	}

	@Test
	public void theSettingTurnsItOffAndOnLive()
	{
		start(monsters(6));
		assertEquals("Advice: Consider skip (n=3)", advice());

		harness.setTaskAdvisor(false);
		assertNull(advice());
		harness.setTaskAdvisor(true);
		assertEquals("Advice: Consider skip (n=3)", advice());
	}

	@Test
	public void startsOffWhenTheSettingIsOff()
	{
		harness.config.taskAdvisor = false;
		start(monsters(6));
		assertNull(advice());
	}

	@Test
	public void changingTheGoalChangesTheAdvice()
	{
		List<LedgerRecord> records = monsters(6);
		// The task's monster has the lowest profit but the most Slayer XP and points.
		for (int i = 0; i < records.size(); i++)
		{
			records.get(i).setXpGained(Collections.singletonMap("SLAYER", (60 - i) * 1_000L));
			records.get(i).setPointsEarned(i == 0 ? 100 : 10 + i);
		}
		start(records);
		assertEquals("Advice: Consider skip (n=3)", advice());

		harness.setTaskAdvisorGoal(TaskAdvisorGoal.SLAYER_XP);
		assertDo();
		assertTrue(tooltip(), tooltip().contains("Slayer XP/h 60K: 1st highest of your 6 monsters"));

		harness.setTaskAdvisorGoal(TaskAdvisorGoal.POINTS);
		assertDo();
		assertTrue(tooltip(), tooltip().contains("Points/h 100: 1st highest"));

		harness.setTaskAdvisorGoal(TaskAdvisorGoal.PROFIT);
		assertEquals("Advice: Consider skip (n=3)", advice());
	}

	@Test
	public void followsTheLootPriceMode()
	{
		List<LedgerRecord> records = monsters(6);
		for (LedgerRecord record : records)
		{
			record.setLootHaValue(1_000L);
		}
		records.get(0).setLootHaValue(500_000L);
		start(records);
		assertEquals("Advice: Consider skip (n=3)", advice());

		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		assertDo();
		assertTrue(tooltip(), tooltip().contains("Profit (HA)/h 500K"));
	}

	@Test
	public void workedOutOnlyWhenSomethingItDependsOnChanges()
	{
		List<LedgerRecord> records = monsters(6);
		start(records);
		assertEquals("Advice: Consider skip (n=3)", advice());
		int builds = builds();

		// The refresh timer and game ticks work nothing out.
		for (int i = 0; i < 5; i++)
		{
			harness.onEdt(() -> harness.panel().refresh());
		}
		harness.ticks(5);
		assertEquals("Advice: Consider skip (n=3)", advice());
		assertEquals(builds, builds());

		// New records.
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT, history(records)));
		assertEquals(builds + 1, builds());

		// Another task.
		harness.setTask(TASK + 5, "Monster 47", 10, 10);
		harness.tick();
		assertDo();
		assertEquals(builds + 2, builds());
		harness.onEdt(() -> harness.panel().refresh());
		assertEquals(builds + 2, builds());

		// The goal.
		harness.setTaskAdvisorGoal(TaskAdvisorGoal.SLAYER_XP);
		assertNull("no XP recorded", advice());
		assertEquals(builds + 3, builds());
		harness.onEdt(() -> harness.panel().refresh());
		assertEquals("hidden for too little data, still not worked out again", builds + 3, builds());
	}

	@Test
	public void todaysPricesKeepTheRowWhileWaitingThenWorkItOutOnce()
	{
		harness.defineItem(ItemID.DRAGON_BONES, "Dragon bones", 2_000, 5_000);
		harness.defineItem(ItemID.RANARR_SEED, "Ranarr seed", 500_000, 20);
		List<LedgerRecord> records = monsters(6);
		for (int i = 0; i < records.size(); i++)
		{
			// Saved at the same values as today's prices.
			records.get(i).setLoot(Collections.singletonMap(ItemID.DRAGON_BONES, (i + 1) * 5L));
			records.get(i).setUnitGePrices(Collections.singletonMap(ItemID.DRAGON_BONES, 2_000L));
			records.get(i).setUnitHaPrices(Collections.singletonMap(ItemID.DRAGON_BONES, 5_000L));
		}
		start(records);
		harness.drain();
		assertEquals("Advice: Consider skip (n=3)", advice());
		int builds = builds();

		// The task's tasks are saved again with a ranarr seed, saved at 10K but 500K today, not priced yet.
		records.get(0).setLoot(Collections.singletonMap(ItemID.RANARR_SEED, 1L));
		records.get(0).setUnitGePrices(Collections.singletonMap(ItemID.RANARR_SEED, 10_000L));
		records.get(0).setUnitHaPrices(Collections.singletonMap(ItemID.RANARR_SEED, 20L));
		List<LedgerRecord> withSeed = history(records);
		harness.onEdt(() ->
		{
			SlayerLedgerPanel panel = harness.panel();
			PanelProbe ui = harness.ui();
			// The lookup is queued for the client thread, so it cannot answer inside this EDT task.
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, withSeed);
			panel.refresh();
			assertTrue(harness.historyModel().isWaitingForCurrentPrices());
			assertEquals("the shown row stays", "Advice: Consider skip (n=3)", ui.getTaskBox().getAdviceText());
			assertEquals(builds, harness.historyModel().getAdviceBuildCount());
		});

		harness.drain();
		harness.onEdt(() -> assertFalse(harness.historyModel().isWaitingForCurrentPrices()));
		assertDo();
		assertEquals("worked out once", builds + 1, builds());
		assertTrue(tooltip(), tooltip().contains("Profit (GE)/h 500K: 1st highest of your 6 monsters"));
	}

	@Test
	public void hiddenByTheHistoryMessageAndNeverShowsAnotherAccountsAdvice()
	{
		start(monsters(6));
		assertEquals("Advice: Consider skip (n=3)", advice());

		harness.onEdt(() -> harness.historyModel().clearRecords(PluginHarness.ACCOUNT,
			SlayerLedgerPanel.LOGGED_OUT_MESSAGE));
		assertNull("no records shown", advice());

		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT, history(monsters(6))));
		assertEquals("Advice: Consider skip (n=3)", advice());

		// Another account's records: its advice, where the task's monster is the best.
		List<LedgerRecord> other = monsters(6);
		other.get(0).setLootGeValue(900_000L);
		SavedItems.withItems(other.get(0));
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.OTHER_ACCOUNT, history(other)));
		assertDo();
		assertTrue(tooltip(), tooltip().contains("900K"));

		// Too little history on that account: none, never the first account's.
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.OTHER_ACCOUNT, history(monsters(5))));
		assertNull(advice());
	}

	@Test
	public void hiddenOnLogout()
	{
		start(monsters(6));
		assertEquals("Advice: Consider skip (n=3)", advice());

		harness.logout();
		harness.drain();
		assertNull(advice());
	}

	/**
	 * Starts the plugin on a 10-kill Abyssal demons task (creature key {@link #TASK}) and shows {@code records}, each
	 * as {@link #TASKS} tasks ({@link #history}).
	 */
	private void start(List<LedgerRecord> records)
	{
		harness.startUp();
		harness.setTask(TASK, "Abyssal demons", 10, 10);
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		List<LedgerRecord> history = history(records);
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT, history));
	}

	/** Each of {@code records} as {@link #TASKS} identical tasks, so every monster has enough for advice. */
	private static List<LedgerRecord> history(List<LedgerRecord> records)
	{
		return history(records, TASKS);
	}

	private static List<LedgerRecord> history(List<LedgerRecord> records, int tasks)
	{
		List<LedgerRecord> history = new ArrayList<>();
		for (LedgerRecord record : records)
		{
			for (int task = 0; task < tasks; task++)
			{
				history.add(GSON.fromJson(GSON.toJson(record), LedgerRecord.class));
			}
		}
		return history;
	}

	/**
	 * The advice row after a panel refresh, once the advice worked out off the EDT is back: the harness drains
	 * the executor and the EDT first.
	 */
	private String advice()
	{
		String[] text = new String[1];
		harness.onEdt(() -> harness.panel().refresh());
		harness.drain();
		harness.onEdt(() -> text[0] = harness.ui().getTaskBox().getAdviceText());
		return text[0];
	}

	/** Do adds no row to the task box: it is in the task line's tooltip, with its reason. */
	private void assertDo()
	{
		assertNull("no row for Do", advice());
		String tooltip = tooltip();
		assertTrue(tooltip, tooltip != null && tooltip.contains("<br>Advice: Do<br>"));
	}

	/** The advice row's tooltip, or for Do the task line's tooltip that has it. */
	private String tooltip()
	{
		String[] text = new String[1];
		harness.drain();
		harness.onEdt(() ->
		{
			PanelProbe.TaskBoxProbe box = harness.ui().getTaskBox();
			text[0] = box.getAdviceText() != null ? box.getAdviceTooltip() : box.getTaskTooltip();
		});
		return text[0];
	}

	private int builds()
	{
		int[] count = new int[1];
		harness.drain();
		harness.onEdt(() -> count[0] = harness.historyModel().getAdviceBuildCount());
		return count[0];
	}

	private List<String> taskBoxRows()
	{
		List<?>[] rows = new List<?>[1];
		harness.onEdt(() -> harness.panel().refresh());
		harness.drain();
		harness.onEdt(() -> rows[0] = harness.ui().getTaskBox().getRows());
		@SuppressWarnings("unchecked")
		List<String> result = (List<String>) rows[0];
		return result;
	}

	/**
	 * {@code count} monsters from {@link #TASK} up, one hour-long task each (repeated by {@link #history}); the
	 * {@code i}th makes {@code i * 10K}/h, in coins ({@link SavedItems}).
	 */
	private static List<LedgerRecord> monsters(int count)
	{
		List<LedgerRecord> records = new ArrayList<>();
		for (int i = 0; i < count; i++)
		{
			LedgerRecord record = new LedgerRecord();
			record.setCreatureId(TASK + i);
			record.setMonster("Monster " + (TASK + i));
			record.setKills(10);
			record.setInitialAmount(10);
			record.setOriginalAmount(10);
			record.setActiveMillis(HOUR);
			record.setCompleted(true);
			record.setLootGeValue((i + 1) * 10_000L);
			record.setSuppliesGeValue(0L);
			records.add(SavedItems.withItems(record));
		}
		return records;
	}
}
