package com.shmayvan.slayerledger;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Cannon tasks: detection from the supplies used ({@link CannonTasks}), in the session and the saved record
 * (also records saved before supplies were tracked), the grouping name with and without a Catacombs variant, the
 * per-task statistics' selection, the monster ranking split and pooled, and the task advisor's badge pooling cannon
 * tasks. {@link HarnessCannonTaskTest} covers the wiring.
 */
public class CannonTaskGroupingTest
{
	private static final long ACCOUNT = 555L;
	private static final int DUST_DEVILS = 49;
	private static final String DEVILS = "Dust devils";
	private static final int SPECTRES = 7;
	private static final String SPECTRE_TASK = "Aberrant spectres";
	private static final String DEVIANT = "Deviant spectres";
	private static final long HOUR = 60 * 60_000L;
	private static final long MINUTE = 60_000L;

	private long now = 1_000_000L;

	// ---- Detection ----

	@Test
	public void anyFiredCannonballRegularOrGraniteMakesACannonTask()
	{
		assertTrue(CannonTasks.fired(supplies(SupplyTracker.CANNONBALL, 1L)));
		assertTrue(CannonTasks.fired(supplies(SupplyTracker.GRANITE_CANNONBALL, 30L)));
		assertFalse("other supplies only", CannonTasks.fired(supplies(385, 12L)));
		assertFalse("none", CannonTasks.fired(Collections.<Integer, Long>emptyMap()));
		assertFalse("not tracked", CannonTasks.fired((Map<Integer, Long>) null));
		assertFalse("a zero left by a hand-edited file", CannonTasks.fired(supplies(SupplyTracker.CANNONBALL, 0L)));

		ItemTally live = new ItemTally();
		assertFalse(CannonTasks.fired(live));
		live.add(SupplyTracker.GRANITE_CANNONBALL, 2);
		assertTrue(CannonTasks.fired(live));
		assertFalse(CannonTasks.fired((ItemTally) null));
	}

	@Test
	public void recordsAreClassifiedFromTheirSuppliesAndOldOnesHaveNoCannon()
	{
		Gson gson = new Gson();
		LedgerRecord old = gson.fromJson("{\"schemaVersion\":2,\"id\":\"a\",\"monster\":\"Dust devils\","
			+ "\"creatureId\":49,\"kills\":10,\"activeMillis\":60000,\"completed\":true}", LedgerRecord.class);
		assertNull(old.getSupplies());
		assertFalse("saved before supplies were tracked", old.cannonTask());
		assertEquals(DEVILS, old.groupName());

		LedgerRecord withSupplies = gson.fromJson("{\"schemaVersion\":2,\"id\":\"b\",\"monster\":\"Dust devils\","
			+ "\"creatureId\":49,\"kills\":10,\"activeMillis\":60000,\"completed\":true,"
			+ "\"supplies\":{\"2\":120,\"385\":3}}", LedgerRecord.class);
		assertTrue(withSupplies.cannonTask());
		assertEquals("Dust devils (cannon)", withSupplies.groupName());
		// Nothing is stored for it.
		assertFalse(gson.toJson(withSupplies).contains("cannon"));
	}

	@Test
	public void theSessionNarrowsToCannonTasksOnceACannonballIsFired()
	{
		SessionTracker tracker = playing(DEVILS, DUST_DEVILS);
		assertSame("pooled before any cannonball", TaskSelection.ANY, tracker.getCurrentTaskSelection());

		// Loaded, then picked up again: they came back, so nothing was fired.
		assertTrue(tracker.addSupplies(tally(SupplyTracker.CANNONBALL, 30), null, null));
		assertTrue(tracker.addSupplies(null, tally(SupplyTracker.CANNONBALL, 30), null));
		assertSame(TaskSelection.ANY, tracker.getCurrentTaskSelection());

		assertTrue(tracker.addSupplies(tally(SupplyTracker.CANNONBALL, 2), null, null));
		assertEquals(TaskSelection.ANY.withCannon(true), tracker.getCurrentTaskSelection());
		assertEquals("Dust devils (cannon)", tracker.getCurrentTaskSelection().displayName(DEVILS));

		// Still a cannon task once paused; the saved record is named the same way.
		tracker.pause();
		assertEquals(TaskSelection.ANY.withCannon(true), tracker.getCurrentTaskSelection());
		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), true);
		assertTrue(record.cannonTask());
		assertEquals("Dust devils (cannon)", record.groupName());
		assertSame("no session any more", TaskSelection.ANY, tracker.getCurrentTaskSelection());
	}

	@Test
	public void cannonAndCatacombsVariantCombine()
	{
		SessionTracker tracker = playing(SPECTRE_TASK, SPECTRES);
		assertTrue(tracker.addSupplies(tally(SupplyTracker.GRANITE_CANNONBALL, 5), null, null));
		assertEquals("no task kills yet: any version, cannon only", TaskSelection.ANY.withCannon(true),
			tracker.getCurrentTaskSelection());
		assertEquals("Aberrant spectres (cannon)", tracker.getCurrentTaskSelection().displayName(SPECTRE_TASK));

		assertTrue(tracker.addNpcKill("Deviant spectre"));
		now += MINUTE;
		tracker.observe(SPECTRE_TASK, SPECTRES, 49, 50, false);
		TaskSelection selection = tracker.getCurrentTaskSelection();
		assertEquals(TaskSelection.of(DEVIANT).withCannon(true), selection);
		assertEquals("Deviant spectres (cannon)", selection.displayName(SPECTRE_TASK));
		assertEquals(DEVIANT, tracker.getCurrentTaskSelection().getVariant());

		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), true);
		assertEquals(DEVIANT, record.getVariant());
		assertEquals("Deviant spectres (cannon)", record.groupName());
		assertEquals(DEVIANT, record.versionName());
	}

	// ---- Grouping ----

	@Test
	public void historyFilterTitlesAndTheChatLineUseTheCannonName()
	{
		LedgerRecord plain = record(DUST_DEVILS, DEVILS, null, false, 10, HOUR);
		LedgerRecord cannon = record(DUST_DEVILS, DEVILS, null, true, 10, HOUR);
		LedgerRecord both = record(SPECTRES, SPECTRE_TASK, DEVIANT, true, 10, HOUR);
		List<LedgerRecord> records = Arrays.asList(plain, cannon, both);

		assertEquals(Arrays.asList(LedgerHistory.ALL, DEVIANT + " (cannon)", DEVILS, DEVILS + " (cannon)"),
			LedgerHistory.filterOptions(records));
		assertEquals(Collections.singletonList(cannon), new HistoryFilter(DEVILS + " (cannon)", null, null).apply(records, SlayerSpots.NONE, r -> null));
		assertEquals(Collections.singletonList(plain), new HistoryFilter(DEVILS, null, null).apply(records, SlayerSpots.NONE, r -> null));
		assertEquals(DEVILS + " (cannon)", LedgerHistory.monster(cannon));

		String chat = TaskCompleteMessage.format(cannon, LootPriceMode.GE);
		assertTrue(chat, chat.startsWith("Dust devils (cannon) done: 10 kills in "));
		assertTrue(TaskCompleteMessage.format(plain, LootPriceMode.GE)
			.startsWith("Dust devils done: 10 kills in "));
	}

	@Test
	public void statisticsCountTheSelectedCannonUse()
	{
		List<LedgerRecord> records = Arrays.asList(
			record(DUST_DEVILS, DEVILS, null, false, 10, 10 * MINUTE),
			record(DUST_DEVILS, DEVILS, null, false, 10, 10 * MINUTE),
			record(DUST_DEVILS, DEVILS, null, true, 10, 5 * MINUTE));
		TaskStatistics pooled = devilStats(records, TaskSelection.ANY);
		assertEquals(3, pooled.getSampleCount());
		TaskStatistics cannon = devilStats(records, TaskSelection.ANY.withCannon(true));
		assertEquals(1, cannon.getSampleCount());
		assertEquals(Long.valueOf(30_000L), cannon.getMillisPerKill());
		assertEquals(2, devilStats(records, TaskSelection.ANY.withCannon(false)).getSampleCount());
		assertEquals("no selection pools them", 3, devilStats(records, null).getSampleCount());
	}

	@Test
	public void statisticsSelectVariantAndCannonTogether()
	{
		List<LedgerRecord> records = Arrays.asList(
			record(SPECTRES, SPECTRE_TASK, null, false, 10, HOUR),
			record(SPECTRES, SPECTRE_TASK, null, true, 10, HOUR),
			record(SPECTRES, SPECTRE_TASK, DEVIANT, false, 10, HOUR),
			record(SPECTRES, SPECTRE_TASK, DEVIANT, true, 10, HOUR),
			record(SPECTRES, SPECTRE_TASK, DEVIANT, true, 10, HOUR));
		assertEquals(5, spectreStats(records, TaskSelection.ANY).getSampleCount());
		assertEquals(3, spectreStats(records, TaskSelection.ANY.withCannon(true)).getSampleCount());
		assertEquals(3, spectreStats(records, TaskSelection.of(DEVIANT)).getSampleCount());
		assertEquals(2, spectreStats(records, TaskSelection.of(DEVIANT).withCannon(true)).getSampleCount());
		assertEquals(1, spectreStats(records, TaskSelection.STANDARD.withCannon(true)).getSampleCount());
		assertEquals(1, spectreStats(records, TaskSelection.STANDARD.withCannon(false)).getSampleCount());
	}

	@Test
	public void aSelectionKeepsItsVariantOnlyForThatVariantsTask()
	{
		// The overlay reads the task and the selection in separate calls; a selection from another session's task
		// shows the task without that variant, keeping its cannon limit.
		TaskSelection deviant = TaskSelection.of(DEVIANT).withCannon(true);
		assertSame(deviant, deviant.forTask(SPECTRE_TASK));
		assertEquals(TaskSelection.ANY.withCannon(true), deviant.forTask(DEVILS));
		assertEquals(TaskSelection.ANY, TaskSelection.of("Mutated bloodveld").forTask(SPECTRE_TASK));
		assertEquals(TaskSelection.ANY, TaskSelection.of(DEVIANT).forTask(null));
		assertEquals(DEVILS + CannonTasks.SUFFIX, deviant.forTask(DEVILS).displayName(DEVILS));

		// Without a variant there is nothing to guard.
		assertSame(TaskSelection.STANDARD, TaskSelection.STANDARD.forTask(DEVILS));
		assertSame(TaskSelection.ANY, TaskSelection.ANY.forTask(SPECTRE_TASK));
		TaskSelection cannon = TaskSelection.ANY.withCannon(true);
		assertSame(cannon, cannon.forTask(null));
	}

	@Test
	public void selectionsCompareByValueAndKeyTheCannon()
	{
		assertSame(TaskSelection.ANY, TaskSelection.ANY.withCannon(null));
		assertSame(TaskSelection.ANY, TaskSelection.ANY.withCannon(true).withCannon(null));
		assertSame(TaskSelection.STANDARD, TaskSelection.STANDARD.withCannon(false).withCannon(null));
		assertNotEquals(TaskSelection.ANY, TaskSelection.ANY.withCannon(true));
		assertNotEquals(TaskSelection.ANY.withCannon(true), TaskSelection.ANY.withCannon(false));
		assertNotEquals(TaskSelection.ANY.withCannon(true).key(), TaskSelection.STANDARD.withCannon(true).key());
		assertEquals(TaskSelection.of("Deviant spectres").withCannon(true),
			TaskSelection.of("deviant SPECTRES").withCannon(true));
		assertEquals(TaskSelection.ANY.withCannon(true), TaskSelection.of(DEVIANT).withCannon(true).withAnyVariant());
		assertTrue(TaskSelection.ANY.withCannon(true).isAnyVariant());
		assertNull(TaskSelection.ANY.withCannon(true).getVariant());

		assertEquals(DEVILS, TaskSelection.ANY.displayName(DEVILS));
		assertEquals("without a cannon limit nothing is added", DEVILS,
			TaskSelection.ANY.withCannon(false).displayName(DEVILS));
		assertEquals(DEVIANT, TaskSelection.of(DEVIANT).displayName(SPECTRE_TASK));
	}

	@Test
	public void theAdvisorRankingPoolsCannonTasks()
	{
		List<LedgerRecord> records = new ArrayList<>();
		records.add(profit(DUST_DEVILS, DEVILS, null, false, 100_000L));
		records.add(profit(DUST_DEVILS, DEVILS, null, true, 300_000L));
		records.add(profit(DUST_DEVILS, DEVILS, null, true, 300_000L));
		records.add(profit(SPECTRES, SPECTRE_TASK, DEVIANT, true, 200_000L));

		List<TaskRanking.Entry> pooled = TaskRanking.rank(records, TaskRanking.Metric.PROFIT, LootPriceMode.GE, null,
			HiddenLoot.NONE);
		assertEquals(Arrays.asList(DEVILS, SPECTRE_TASK), names(pooled));
		assertEquals(3, pooled.get(0).getSampleCount());
	}

	// ---- Task advisor ----

	@Test
	public void theMainAdvicePoolsCannonTasks()
	{
		// Six monsters of 3 tasks each; the dust devils' tasks are split 2 + 1 by cannon use.
		List<LedgerRecord> records = new ArrayList<>();
		for (int creature = 1; creature <= 5; creature++)
		{
			for (int task = 0; task < TaskAdvisor.MIN_TASKS; task++)
			{
				records.add(profit(100 + creature, "Monster " + creature, null, false, (creature + 1) * 100_000L));
			}
		}
		records.add(profit(DUST_DEVILS, DEVILS, null, false, 50_000L));
		records.add(profit(DUST_DEVILS, DEVILS, null, false, 50_000L));
		records.add(profit(DUST_DEVILS, DEVILS, null, true, 50_000L));
		TaskAdvisor.Result advice = TaskAdvisor.advise(records, DUST_DEVILS, DEVILS, TaskRanking.Metric.PROFIT,
			LootPriceMode.GE, null, HiddenLoot.NONE);
		assertNotNull("pooled, the dust devils have 3 tasks and qualify", advice);
		assertEquals(3, advice.getTasks());
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, advice.getAdvice());
	}

	// ---- Per-task statistics and overlay text ----

	@Test
	public void afterACannonballTheStatisticsUseCannonTasksOnlyWhenThereAreSome()
	{
		TaskSelection cannon = TaskSelection.ANY.withCannon(true);
		List<LedgerRecord> plain = Arrays.asList(record(DUST_DEVILS, DEVILS, null, false, 10, HOUR),
			record(DUST_DEVILS, DEVILS, null, false, 20, HOUR));
		assertSame("no cannon tasks: pooled, as before the cannonball", TaskSelection.ANY,
			TaskStatistics.effectiveSelection(plain, DUST_DEVILS, DEVILS, cannon));
		assertEquals(2, devilStats(plain, TaskStatistics.effectiveSelection(plain, DUST_DEVILS, DEVILS, cannon))
			.getSampleCount());

		List<LedgerRecord> mixed = new ArrayList<>(plain);
		mixed.add(record(DUST_DEVILS, DEVILS, null, true, 10, HOUR));
		assertEquals("one cannon task: cannon tasks only", cannon,
			TaskStatistics.effectiveSelection(mixed, DUST_DEVILS, DEVILS, cannon));
		assertEquals(1, devilStats(mixed, TaskStatistics.effectiveSelection(mixed, DUST_DEVILS, DEVILS, cannon))
			.getSampleCount());

		// Another task's cannon tasks, or an unfinished one, don't count.
		List<LedgerRecord> others = new ArrayList<>(plain);
		others.add(record(SPECTRES, SPECTRE_TASK, null, true, 10, HOUR));
		LedgerRecord partial = record(DUST_DEVILS, DEVILS, null, true, 10, HOUR);
		partial.setCompleted(false);
		others.add(partial);
		assertSame(TaskSelection.ANY, TaskStatistics.effectiveSelection(others, DUST_DEVILS, DEVILS, cannon));

		// Without a cannonball the selection stays, whatever the history.
		assertSame(TaskSelection.ANY, TaskStatistics.effectiveSelection(mixed, DUST_DEVILS, DEVILS, TaskSelection.ANY));
		assertSame(TaskSelection.ANY, TaskStatistics.effectiveSelection(mixed, DUST_DEVILS, DEVILS, null));

		// The version stays: a Deviant spectres cannon task needs a Deviant spectres one in the history.
		TaskSelection deviantCannon = TaskSelection.of(DEVIANT).withCannon(true);
		List<LedgerRecord> spectres = Arrays.asList(record(SPECTRES, SPECTRE_TASK, null, true, 10, HOUR),
			record(SPECTRES, SPECTRE_TASK, DEVIANT, false, 10, HOUR));
		assertEquals(TaskSelection.of(DEVIANT),
			TaskStatistics.effectiveSelection(spectres, SPECTRES, SPECTRE_TASK, deviantCannon));
		assertEquals(TaskSelection.ANY.withCannon(true), TaskStatistics.effectiveSelection(spectres, SPECTRES,
			SPECTRE_TASK, TaskSelection.ANY.withCannon(true)));
	}

	@Test
	public void theOverlayNamesTheTaskAsTheTaskBoxDoes()
	{
		SessionTracker.LiveTask task = new SessionTracker.LiveTask(SessionTracker.Status.RUNNING, "session", DEVILS,
			DUST_DEVILS, 3, 60_000L, 20);
		assertEquals(DEVILS, new OverlayRows.Data(task, null, null, null).getTaskName());
		assertEquals(DEVILS, OverlayRows.taskName(DEVILS, DUST_DEVILS, TaskSelection.ANY, null));
		assertEquals(DEVILS + " (cannon)",
			OverlayRows.taskName(DEVILS, DUST_DEVILS, TaskSelection.ANY.withCannon(true), null));
		assertEquals(DEVIANT + " (cannon)", OverlayRows.taskName(SPECTRE_TASK, SPECTRES,
			TaskSelection.of(DEVIANT).withCannon(true), null));
	}

	// ---- Helpers ----

	/** A running task of 50 just started, without task kills. */
	private SessionTracker playing(String monster, int creatureId)
	{
		SessionTracker tracker = new SessionTracker(() -> now);
		tracker.observe(monster, creatureId, 50, 50, false);
		assertTrue(tracker.startNow(ACCOUNT));
		return tracker;
	}

	private static ItemTally tally(int id, long quantity)
	{
		ItemTally tally = new ItemTally();
		tally.add(id, quantity);
		return tally;
	}

	private static Map<Integer, Long> supplies(int id, long quantity)
	{
		Map<Integer, Long> supplies = new HashMap<>();
		supplies.put(id, quantity);
		return supplies;
	}

	private static List<String> names(List<TaskRanking.Entry> ranking)
	{
		List<String> names = new ArrayList<>();
		for (TaskRanking.Entry entry : ranking)
		{
			names.add(entry.getMonster());
		}
		return names;
	}

	private static TaskStatistics devilStats(List<LedgerRecord> records, TaskSelection selection)
	{
		return TaskStatistics.forTask(records, DUST_DEVILS, DEVILS, selection, null, null);
	}

	private static TaskStatistics spectreStats(List<LedgerRecord> records, TaskSelection selection)
	{
		return TaskStatistics.forTask(records, SPECTRES, SPECTRE_TASK, selection, null, null);
	}

	/** An hour-long completed task making {@code profit} (loot at GE, supplies valued at 0). */
	private static LedgerRecord profit(int creatureId, String monster, String variant, boolean cannon, long profit)
	{
		LedgerRecord record = record(creatureId, monster, variant, cannon, 10, HOUR);
		record.setLootGeValue(profit);
		record.setSuppliesGeValue(0L);
		return record;
	}

	/** A completed task; a cannon task fired 50 cannonballs. */
	private static LedgerRecord record(int creatureId, String monster, String variant, boolean cannon, int kills,
		long activeMillis)
	{
		LedgerRecord record = new LedgerRecord();
		record.setId(java.util.UUID.randomUUID().toString());
		record.setCreatureId(creatureId);
		record.setMonster(monster);
		record.setVariant(variant);
		record.setSupplies(cannon ? supplies(SupplyTracker.CANNONBALL, 50L) : null);
		record.setKills(kills);
		record.setInitialAmount(kills);
		record.setOriginalAmount(kills);
		record.setActiveMillis(activeMillis);
		record.setEndedAt(500_000L);
		record.setCompleted(true);
		return record;
	}
}
