package com.shmayvan.slayerledger;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Catacombs variant tasks in the session, the saved record and every per-monster grouping: the History estimate
 * statistics, the Trends list and chart, the history list and its Monster filter, the task advisor (pooled) and its
 * variant advice. Records saved before variants were tracked have no variant and stay standard.
 */
public class CatacombsVariantGroupingTest
{
	private static final long ACCOUNT = 555L;
	private static final int SPECTRES = 7;
	private static final String TASK = "Aberrant spectres";
	private static final String DEVIANT = "Deviant spectres";
	private static final long HOUR = 60 * 60_000L;
	private static final long MINUTE = 60_000L;

	private long now = 1_000_000L;

	// ---- Session and saved record ----

	@Test
	public void killsWhileRunningDecideTheSavedRecordsVariant()
	{
		// 10 task kills: 6 Deviant spectres, 4 Aberrant spectres, and Ankous on the way that don't count.
		SessionTracker tracker = runningSpectreTask();
		for (int i = 0; i < 6; i++)
		{
			assertTrue(tracker.addNpcKill("Deviant spectre"));
		}
		for (int i = 0; i < 4; i++)
		{
			assertTrue(tracker.addNpcKill("Aberrant spectre"));
		}
		for (int i = 0; i < 8; i++)
		{
			assertTrue(tracker.addNpcKill("Ankou"));
		}
		assertFalse(tracker.addNpcKill(null));
		assertEquals(DEVIANT, tracker.getCurrentTaskSelection().getVariant());
		assertEquals(TaskSelection.of(DEVIANT), tracker.getCurrentTaskSelection());

		// Paused: not tallied, but the decision stands.
		assertTrue(tracker.pause());
		assertFalse(tracker.addNpcKill("Aberrant spectre"));
		assertFalse(tracker.addNpcKill("Aberrant spectre"));
		assertEquals(DEVIANT, tracker.getCurrentTaskSelection().getVariant());

		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), true);
		assertEquals(DEVIANT, record.getVariant());
		assertEquals(TASK, record.getMonster());
		assertEquals(DEVIANT, record.groupName());
		assertEquals(DEVIANT, LedgerHistory.monster(record));
		assertNull("no session any more", tracker.getCurrentTaskSelection().getVariant());
		assertSame(TaskSelection.ANY, tracker.getCurrentTaskSelection());
	}

	@Test
	public void theLiveSelectionWaitsForTheFirstTaskKillAndATieIsStandard()
	{
		SessionTracker tracker = playingSpectreTask();
		assertNull(tracker.getCurrentTaskSelection().getVariant());
		assertSame("no task kills yet: both versions pooled", TaskSelection.ANY, tracker.getCurrentTaskSelection());
		assertNull(LedgerRecord.fromSummary(tracker.activeSummary(), true).getVariant());

		// An unrelated kill first (for example an Ankou on the way) changes nothing.
		tracker.addNpcKill("Ankou");
		assertSame(TaskSelection.ANY, tracker.getCurrentTaskSelection());
		// A variant kill counts once the task counter has counted it.
		tracker.addNpcKill("Deviant spectre");
		assertSame(TaskSelection.ANY, tracker.getCurrentTaskSelection());
		killTask(tracker);
		assertEquals(DEVIANT, tracker.getCurrentTaskSelection().getVariant());
		assertEquals(TaskSelection.of(DEVIANT), tracker.getCurrentTaskSelection());
		assertEquals(DEVIANT, LedgerRecord.fromSummary(tracker.activeSummary(), true).getVariant());

		tracker.addNpcKill("Aberrant spectre");
		killTask(tracker);
		assertNull("a tie is standard", tracker.getCurrentTaskSelection().getVariant());
		assertSame(TaskSelection.STANDARD, tracker.getCurrentTaskSelection());
		assertNull(LedgerRecord.fromSummary(tracker.stopAndReset(), true).getVariant());
	}

	@Test
	public void deathSpawnsAndOtherBarragedNpcsDontMakeItStandard()
	{
		// A Greater nechryael task: each Greater Nechryael kill also kills its death spawns, which count for nothing.
		SessionTracker tracker = new SessionTracker(() -> now);
		tracker.observe("Nechryael", 52, 20, 20, false);
		assertTrue(tracker.play(ACCOUNT));
		for (int i = 1; i <= 5; i++)
		{
			tracker.addNpcKill("Greater Nechryael");
			tracker.addNpcKill("Death spawn");
			tracker.addNpcKill("Death spawn");
			tracker.addNpcKill("Death spawn");
			now += MINUTE;
			tracker.observe("Nechryael", 52, 20 - i, 20, false);
			assertEquals("Greater nechryael", tracker.getCurrentTaskSelection().getVariant());
		}
		assertEquals("Greater nechryael", LedgerRecord.fromSummary(tracker.stopAndReset(), true).getVariant());
	}

	@Test
	public void theTallySurvivesTheActiveSessionFile()
	{
		Gson gson = new Gson();
		SessionTracker tracker = runningSpectreTask();
		for (int i = 0; i < 6; i++)
		{
			tracker.addNpcKill("Deviant spectre");
		}
		tracker.autoPause();
		SessionSnapshot snapshot = SessionSnapshot.fromSummary(tracker.activeSummary(), null, now);
		String json = gson.toJson(snapshot);
		String tally = ",\"npcKills\":[{\"name\":\"Deviant spectre\",\"kills\":6}]";
		assertTrue(json, json.contains(tally));

		// Restored, the same 6 variant kills of 10 task kills decide it, live and on save.
		SessionTracker restored = new SessionTracker(() -> now);
		restored.observe(TASK, SPECTRES, 40, 50, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED,
			restored.restore(gson.fromJson(json, SessionSnapshot.class), null));
		assertEquals(10, restored.getKills());
		assertEquals(DEVIANT, restored.getCurrentTaskSelection().getVariant());
		assertEquals(TaskSelection.of(DEVIANT), restored.getCurrentTaskSelection());
		assertEquals(DEVIANT, LedgerRecord.fromSummary(restored.activeSummary(), true).getVariant());
		// A snapshot that can't be restored becomes a partial record with its variant.
		assertEquals(DEVIANT, LedgerRecord.fromSummary(gson.fromJson(json, SessionSnapshot.class).toSummary(), true)
			.getVariant());

		// Fewer variant kills than half of the restored task kills: standard, live and on save.
		SessionTracker half = new SessionTracker(() -> now);
		half.observe(TASK, SPECTRES, 40, 50, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED,
			half.restore(gson.fromJson(json.replace("\"kills\":6}", "\"kills\":5}"), SessionSnapshot.class), null));
		assertNull(half.getCurrentTaskSelection().getVariant());
		assertSame(TaskSelection.STANDARD, half.getCurrentTaskSelection());
		assertNull(LedgerRecord.fromSummary(half.activeSummary(), true).getVariant());

		// An older file has no tally and restores without one.
		SessionSnapshot old = gson.fromJson(json.replace(tally, ""), SessionSnapshot.class);
		assertNull(old.getNpcKills());
		SessionTracker fromOld = new SessionTracker(() -> now);
		fromOld.observe(TASK, SPECTRES, 40, 50, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, fromOld.restore(old, null));
		assertTrue(fromOld.getNpcKills().isEmpty());
		assertNull(fromOld.getCurrentTaskSelection().getVariant());
	}

	@Test
	public void recordsWithoutTheFieldLoadAsStandard()
	{
		Gson gson = new Gson();
		LedgerRecord old = gson.fromJson("{\"schemaVersion\":2,\"id\":\"a\",\"monster\":\"Aberrant spectres\","
			+ "\"creatureId\":7,\"kills\":10,\"activeMillis\":60000,\"completed\":true}", LedgerRecord.class);
		assertNull(old.getVariant());
		assertEquals(TASK, old.groupName());
		assertTrue(TaskSelection.STANDARD.matches(old));
		// Written back without the field, and a variant record with it.
		assertFalse(gson.toJson(old).contains("variant"));
		old.setVariant(DEVIANT);
		assertTrue(gson.toJson(old).contains("\"variant\":\"Deviant spectres\""));
	}

	// ---- Grouping ----

	@Test
	public void statisticsCountTheSelectedVersion()
	{
		List<LedgerRecord> records = Arrays.asList(record(null, 10, 10 * MINUTE), record(null, 10, 10 * MINUTE),
			record(DEVIANT, 10, 20 * MINUTE));
		assertEquals(3, stats(records, TaskSelection.ANY).getSampleCount());
		assertEquals(2, stats(records, TaskSelection.STANDARD).getSampleCount());
		TaskStatistics deviant = stats(records, TaskSelection.of(DEVIANT));
		assertEquals(1, deviant.getSampleCount());
		assertEquals(Long.valueOf(2 * MINUTE), deviant.getMillisPerKill());
		// The pooled overloads are unchanged.
		assertEquals(3, TaskStatistics.forTask(records, SPECTRES, TASK, LootPriceMode.GE, Collections.emptyMap())
			.getSampleCount());
	}

	@Test
	public void trendsListAndChartShowAVariantAsItsOwnMonster()
	{
		List<LedgerRecord> records = new ArrayList<>();
		records.add(profit(null, 100_000L));
		records.add(profit(DEVIANT, 400_000L));
		records.add(profit(DEVIANT, 400_000L));

		List<TaskRanking.Entry> ranking = TaskRanking.rank(records, TaskRanking.Metric.PROFIT, LootPriceMode.GE, null);
		assertEquals(2, ranking.size());
		assertEquals(DEVIANT, ranking.get(0).getMonster());
		assertEquals(2, ranking.get(0).getSampleCount());
		assertEquals(Long.valueOf(400_000L), ranking.get(0).getRatePerHour());
		assertEquals(TASK, ranking.get(1).getMonster());
		assertEquals(1, ranking.get(1).getSampleCount());

		// The advisor's ranking pools the two, as before.
		List<TaskRanking.Entry> pooled = TaskRanking.rank(records, TaskRanking.Metric.PROFIT, LootPriceMode.GE, null,
			HiddenLoot.NONE, TaskRanking.Split.NONE);
		assertEquals(1, pooled.size());
		assertEquals(TASK, pooled.get(0).getMonster());
		assertEquals(3, pooled.get(0).getSampleCount());

		List<TaskChart.Monster> monsters = TaskChart.monsters(records, TaskRanking.Metric.PROFIT, LootPriceMode.GE,
			null);
		assertEquals(Arrays.asList(TASK, DEVIANT), Arrays.asList(monsters.get(0).getLabel(),
			monsters.get(1).getLabel()));
		assertEquals(2, TaskChart.points(records, monsters.get(1), TaskRanking.Metric.PROFIT, LootPriceMode.GE, null)
			.size());
	}

	@Test
	public void theHistoryFilterListsTheVariantSeparately()
	{
		List<LedgerRecord> records = Arrays.asList(record(null, 10, HOUR), record(DEVIANT, 10, HOUR));
		assertEquals(Arrays.asList(LedgerHistory.ALL, TASK, DEVIANT), LedgerHistory.filterOptions(records));
		assertEquals(1, new HistoryFilter(DEVIANT, null, null).apply(records, r -> null).size());
		assertEquals(1, new HistoryFilter(TASK, null, null).apply(records, r -> null).size());
		assertTrue(LedgerHistory.rowTooltip(records.get(1), HistoryPricing.value(records.get(1), LootPriceMode.GE,
			Collections.emptyMap()), null).contains("Catacombs variant of Aberrant spectres"));
		assertFalse(LedgerHistory.rowTooltip(records.get(0), HistoryPricing.value(records.get(0), LootPriceMode.GE,
			Collections.emptyMap()), null).contains("Catacombs variant"));
	}

	// ---- Task advisor ----

	@Test
	public void theAdvisorSaysWhichVersionIsBetterWithEnoughHistoryOfBoth()
	{
		List<LedgerRecord> records = new ArrayList<>();
		for (int i = 0; i < TaskAdvisor.MIN_TASKS; i++)
		{
			records.add(profit(null, 300_000L));
			records.add(profit(DEVIANT, 450_000L));
		}
		TaskAdvisor.VersionAdvice advice = adviseVariant(records);
		assertNotNull(advice);
		assertEquals(DEVIANT, advice.getLabel());
		assertEquals("Better: Deviant spectres", TaskAdvisor.betterText(advice, null));
		assertEquals("Profit (GE)/h: Deviant spectres 450K (n=3), Aberrant spectres 300K (n=3)",
			advice.reason(LootPriceMode.GE));

		// The other way round.
		for (LedgerRecord record : records)
		{
			record.setLootGeValue(record.getVariant() == null ? 500_000L : 200_000L);
		}
		assertEquals(TASK, adviseVariant(records).getLabel());

		// Equal rates: no variant advice.
		for (LedgerRecord record : records)
		{
			record.setLootGeValue(300_000L);
		}
		assertNull(adviseVariant(records));
	}

	@Test
	public void theAdvisorGivesNoVariantAdviceWithoutEnoughHistoryOfEither()
	{
		List<LedgerRecord> records = new ArrayList<>();
		for (int i = 0; i < TaskAdvisor.MIN_TASKS; i++)
		{
			records.add(profit(null, 300_000L));
		}
		records.add(profit(DEVIANT, 450_000L));
		records.add(profit(DEVIANT, 450_000L));
		assertNull("2 variant tasks", adviseVariant(records));

		// Three variant tasks, but under 30 minutes of them.
		List<LedgerRecord> short3 = new ArrayList<>(records.subList(0, TaskAdvisor.MIN_TASKS));
		for (int i = 0; i < TaskAdvisor.MIN_TASKS; i++)
		{
			LedgerRecord quick = profit(DEVIANT, 450_000L);
			quick.setActiveMillis(5 * MINUTE);
			short3.add(quick);
		}
		assertNull("15 minutes of variant tasks", adviseVariant(short3));

		// No variant for the task, or no variant history at all.
		assertNull(adviseVariant(records.subList(0, TaskAdvisor.MIN_TASKS)));
		assertNull(TaskAdvisor.adviseVariant(records, 42, "Abyssal demons", TaskRanking.Metric.PROFIT,
			LootPriceMode.GE, null, HiddenLoot.NONE));
	}

	@Test
	public void theMainAdvicePoolsBothVersions()
	{
		// Six monsters of 3 tasks each; the spectres' tasks are split 2 + 1 between the versions.
		List<LedgerRecord> records = new ArrayList<>();
		for (int creature = 1; creature <= 5; creature++)
		{
			for (int task = 0; task < TaskAdvisor.MIN_TASKS; task++)
			{
				LedgerRecord other = profit(null, (creature + 1) * 100_000L);
				other.setCreatureId(100 + creature);
				other.setMonster("Monster " + creature);
				records.add(other);
			}
		}
		records.add(profit(null, 50_000L));
		records.add(profit(null, 50_000L));
		records.add(profit(DEVIANT, 50_000L));
		TaskAdvisor.Outcome outcome = TaskAdvisor.adviseAll(records, SPECTRES, TASK, TaskRanking.Metric.PROFIT,
			LootPriceMode.GE, null, HiddenLoot.NONE);
		assertNotNull("pooled, the spectres have 3 tasks and qualify", outcome.getAdvice());
		assertEquals(3, outcome.getAdvice().getTasks());
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, outcome.getAdvice().getAdvice());
		assertNull("too few of each version", outcome.getVariant());
	}

	// ---- Helpers ----

	/** A running spectres task of 50 with 10 task kills so far. */
	private SessionTracker runningSpectreTask()
	{
		SessionTracker tracker = playingSpectreTask();
		now += 60_000;
		tracker.observe(TASK, SPECTRES, 40, 50, false);
		return tracker;
	}

	/** A spectres task of 50 just started, without task kills. */
	private SessionTracker playingSpectreTask()
	{
		SessionTracker tracker = new SessionTracker(() -> now);
		tracker.observe(TASK, SPECTRES, 50, 50, false);
		assertTrue(tracker.play(ACCOUNT));
		return tracker;
	}

	/** The task counter counts one more kill of the running spectres task. */
	private void killTask(SessionTracker tracker)
	{
		now += MINUTE;
		tracker.observe(TASK, SPECTRES, tracker.getCurrentRemaining() - 1, 50, false);
	}

	private static TaskAdvisor.VersionAdvice adviseVariant(List<LedgerRecord> records)
	{
		return TaskAdvisor.adviseVariant(records, SPECTRES, TASK, TaskRanking.Metric.PROFIT, LootPriceMode.GE, null,
			HiddenLoot.NONE);
	}

	private static TaskStatistics stats(List<LedgerRecord> records, TaskSelection variant)
	{
		return TaskStatistics.forTask(records, SPECTRES, TASK, variant, null, null, LootPriceMode.GE,
			Collections.emptyMap(), HiddenLoot.NONE);
	}

	/** An hour-long completed spectres task making {@code profit} (loot at GE, no supplies). */
	private static LedgerRecord profit(String variant, long profit)
	{
		LedgerRecord record = record(variant, 10, HOUR);
		record.setLootGeValue(profit);
		record.setSuppliesGeValue(0L);
		return record;
	}

	private static LedgerRecord record(String variant, int kills, long activeMillis)
	{
		LedgerRecord record = new LedgerRecord();
		record.setId(java.util.UUID.randomUUID().toString());
		record.setCreatureId(SPECTRES);
		record.setMonster(TASK);
		record.setVariant(variant);
		record.setKills(kills);
		record.setInitialAmount(kills);
		record.setOriginalAmount(kills);
		record.setActiveMillis(activeMillis);
		record.setEndedAt(1_700_000_000_000L + activeMillis);
		record.setCompleted(true);
		return record;
	}
}
