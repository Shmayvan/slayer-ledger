package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import org.junit.Test;

/**
 * The task advisor's rules: positions from the bottom, the 10% and 25% thresholds, the sample-size rules
 * (3 tasks and 30 minutes per monster, 5 tasks for block), the separate boss pool and the minimum data.
 */
public class TaskAdvisorTest
{
	private static final long HOUR = 60 * 60_000L;
	private static final long MINUTE = 60_000L;
	/** Tasks per monster in {@link #profitMonsters(int)}: the fewest that qualify. */
	private static final int TASKS = 3;

	@Test
	public void bottomTenPercentBlocksAndBottomTwentyFivePercentSkips()
	{
		// 10 monsters: the lowest is the bottom 10%, the 2nd the bottom 25%.
		assertEquals(TaskAdvisor.Advice.CONSIDER_BLOCK, TaskAdvisor.classify(1, 10));
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, TaskAdvisor.classify(2, 10));
		assertEquals(TaskAdvisor.Advice.DO, TaskAdvisor.classify(3, 10));
		// Exactly on a threshold is inside it.
		assertEquals(TaskAdvisor.Advice.CONSIDER_BLOCK, TaskAdvisor.classify(10, 100));
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, TaskAdvisor.classify(11, 100));
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, TaskAdvisor.classify(25, 100));
		assertEquals(TaskAdvisor.Advice.DO, TaskAdvisor.classify(26, 100));
		assertEquals(TaskAdvisor.Advice.CONSIDER_BLOCK, TaskAdvisor.classify(2, 20));
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, TaskAdvisor.classify(5, 20));
		assertEquals(TaskAdvisor.Advice.DO, TaskAdvisor.classify(6, 20));
		// Under 10 monsters none is in the bottom 10%; with 6 or 7 only the lowest is in the bottom 25%, from 8 two.
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, TaskAdvisor.classify(1, 6));
		assertEquals(TaskAdvisor.Advice.DO, TaskAdvisor.classify(2, 6));
		assertEquals(TaskAdvisor.Advice.DO, TaskAdvisor.classify(2, 7));
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, TaskAdvisor.classify(2, 8));
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, TaskAdvisor.classify(1, 9));
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, TaskAdvisor.classify(2, 9));
		assertEquals(TaskAdvisor.Advice.DO, TaskAdvisor.classify(3, 9));
	}

	@Test
	public void theLowestOfSixMonstersIsConsiderSkipWithTheSampleSize()
	{
		List<LedgerRecord> records = profitMonsters(6);
		TaskAdvisor.Result lowest = advise(records, 1, TaskRanking.Metric.PROFIT);
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, lowest.getAdvice());
		assertEquals(1, lowest.getPosition());
		assertEquals(6, lowest.getMonsters());
		assertEquals(3, lowest.getTasks());
		assertFalse(lowest.isBoss());
		assertEquals("Advice: Consider skip (n=3)", lowest.text());
		assertEquals("Profit (GE)/h 10K: 1st lowest of your 6 monsters (bottom 25%)",
			lowest.reason(LootPriceMode.GE));

		// Do keeps the label short: no sample size.
		TaskAdvisor.Result second = advise(records, 2, TaskRanking.Metric.PROFIT);
		assertEquals(TaskAdvisor.Advice.DO, second.getAdvice());
		assertEquals("Advice: Do", second.text());
		assertEquals("Profit (GE)/h 20K: 2nd lowest of your 6 monsters", second.reason(LootPriceMode.GE));

		// n is the monster's own task count.
		records.add(profitRecord(1, 10_000L));
		records.add(profitRecord(1, 10_000L));
		records.add(profitRecord(1, 10_000L));
		assertEquals("Advice: Consider skip (n=6)", advise(records, 1, TaskRanking.Metric.PROFIT).text());
	}

	@Test
	public void theLowestOfTenMonstersIsConsiderBlockWithFiveTasks()
	{
		List<LedgerRecord> records = profitMonsters(10, 5);
		TaskAdvisor.Result lowest = advise(records, 1, TaskRanking.Metric.PROFIT);
		assertEquals(TaskAdvisor.Advice.CONSIDER_BLOCK, lowest.getAdvice());
		assertEquals("Advice: Consider block (n=5)", lowest.text());
		assertEquals("Profit (GE)/h 10K: 1st lowest of your 10 monsters (bottom 10%)",
			lowest.reason(LootPriceMode.GE));
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, advise(records, 2, TaskRanking.Metric.PROFIT).getAdvice());
		assertEquals(TaskAdvisor.Advice.DO, advise(records, 3, TaskRanking.Metric.PROFIT).getAdvice());
		assertEquals(10, advise(records, 10, TaskRanking.Metric.PROFIT).getPosition());
	}

	@Test
	public void blockNeedsFiveTasksOtherwiseItIsSkip()
	{
		// The lowest of 10 monsters with 3, then 4 tasks: Consider skip, saying why it isn't block.
		List<LedgerRecord> records = profitMonsters(10);
		TaskAdvisor.Result result = advise(records, 1, TaskRanking.Metric.PROFIT);
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, result.getAdvice());
		assertEquals("Advice: Consider skip (n=3)", result.text());
		assertEquals("Profit (GE)/h 10K: 1st lowest of your 10 monsters (bottom 10%, block from 5 tasks)",
			result.reason(LootPriceMode.GE));

		records.add(profitRecord(1, 10_000L));
		assertEquals("Advice: Consider skip (n=4)", advise(records, 1, TaskRanking.Metric.PROFIT).text());

		records.add(profitRecord(1, 10_000L));
		result = advise(records, 1, TaskRanking.Metric.PROFIT);
		assertEquals(TaskAdvisor.Advice.CONSIDER_BLOCK, result.getAdvice());
		assertEquals("Advice: Consider block (n=5)", result.text());
	}

	@Test
	public void tiedMonstersShareTheHighestPositionOfTheirTie()
	{
		// 10 monsters, the two lowest tied: both are 2nd lowest, so the bottom 25% but not the bottom 10%.
		List<LedgerRecord> records = profitMonsters(10, 5);
		setLoot(records, 1, 20_000L);
		TaskAdvisor.Result first = advise(records, 1, TaskRanking.Metric.PROFIT);
		TaskAdvisor.Result second = advise(records, 2, TaskRanking.Metric.PROFIT);
		assertEquals(2, first.getPosition());
		assertEquals(2, second.getPosition());
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, first.getAdvice());
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, second.getAdvice());

		// All equal: every monster is the highest, so Do.
		List<LedgerRecord> equal = profitMonsters(12);
		for (LedgerRecord record : equal)
		{
			record.setLootGeValue(50_000L);
		}
		TaskAdvisor.Result result = advise(equal, 1, TaskRanking.Metric.PROFIT);
		assertEquals(12, result.getPosition());
		assertEquals(TaskAdvisor.Advice.DO, result.getAdvice());
	}

	@Test
	public void aMonsterWithOneTaskGetsNoAdviceAndDoesNotCount()
	{
		List<LedgerRecord> records = profitMonsters(6);
		// Monster 7 has a single long task: well over 30 minutes, but under 3 tasks.
		LedgerRecord single = record(7, "Monster 7", 5 * HOUR);
		single.setLootGeValue(5_000L);
		single.setSuppliesGeValue(0L);
		records.add(single);
		assertNull(advise(records, 7, TaskRanking.Metric.PROFIT));
		// It is not in the ranking either: Monster 1 is still the lowest of 6, not 2nd of 7.
		TaskAdvisor.Result lowest = advise(records, 1, TaskRanking.Metric.PROFIT);
		assertEquals(1, lowest.getPosition());
		assertEquals(6, lowest.getMonsters());

		// Two tasks are still too few.
		records.add(profitRecord(7, 5_000L));
		assertNull(advise(records, 7, TaskRanking.Metric.PROFIT));
		assertEquals(6, advise(records, 1, TaskRanking.Metric.PROFIT).getMonsters());
	}

	@Test
	public void threeTasksUnderThirtyMinutesGetNoAdviceAndDoNotCount()
	{
		List<LedgerRecord> records = profitMonsters(6);
		// Monster 7: 3 tasks of 9 minutes, 27 minutes in all.
		for (int i = 0; i < 3; i++)
		{
			LedgerRecord quick = record(7, "Monster 7", 9 * MINUTE);
			quick.setLootGeValue(100L);
			quick.setSuppliesGeValue(0L);
			records.add(quick);
		}
		assertNull(advise(records, 7, TaskRanking.Metric.PROFIT));
		assertEquals(1, advise(records, 1, TaskRanking.Metric.PROFIT).getPosition());
		assertEquals(6, advise(records, 1, TaskRanking.Metric.PROFIT).getMonsters());

		// A 4th task of 3 minutes reaches 30 minutes: it qualifies, as the lowest of 7.
		LedgerRecord fourth = record(7, "Monster 7", 3 * MINUTE);
		fourth.setLootGeValue(10L);
		fourth.setSuppliesGeValue(0L);
		records.add(fourth);
		TaskAdvisor.Result result = advise(records, 7, TaskRanking.Metric.PROFIT);
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, result.getAdvice());
		assertEquals("Advice: Consider skip (n=4)", result.text());
		assertEquals(7, result.getMonsters());
		assertEquals(2, advise(records, 1, TaskRanking.Metric.PROFIT).getPosition());
	}

	@Test
	public void bossTasksAreRankedApartFromRegularTasks()
	{
		// 6 regular monsters at 10K to 60K/h and 6 bosses at 1M to 6M/h.
		List<LedgerRecord> records = profitMonsters(6);
		for (int i = 1; i <= 6; i++)
		{
			for (int task = 0; task < TASKS; task++)
			{
				records.add(bossRecord(i, i * 1_000_000L));
			}
		}
		// The lowest boss is far above every regular monster, yet the lowest of its own pool.
		TaskAdvisor.Result boss = advise(records, bossKey(1), "Boss 1");
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, boss.getAdvice());
		assertTrue(boss.isBoss());
		assertEquals(1, boss.getPosition());
		assertEquals(6, boss.getMonsters());
		assertEquals("Profit (GE)/h 1M: 1st lowest of your 6 bosses (bottom 25%)", boss.reason(LootPriceMode.GE));
		// The best regular monster is below every boss, yet the best of its own pool.
		TaskAdvisor.Result best = advise(records, 6, TaskRanking.Metric.PROFIT);
		assertEquals(TaskAdvisor.Advice.DO, best.getAdvice());
		assertFalse(best.isBoss());
		assertEquals(6, best.getPosition());
		assertEquals(6, best.getMonsters());
		// The regular lowest is unchanged by the bosses.
		TaskAdvisor.Result lowest = advise(records, 1, TaskRanking.Metric.PROFIT);
		assertEquals(1, lowest.getPosition());
		assertEquals(6, lowest.getMonsters());

		// The minimum of 6 is per pool: with 5 bosses the bosses get no advice, the regular monsters still do.
		records.removeIf(record -> record.getCreatureId() != null && record.getCreatureId() == bossKey(6));
		assertNull(advise(records, bossKey(1), "Boss 1"));
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, advise(records, 1, TaskRanking.Metric.PROFIT).getAdvice());
		// And 5 regular monsters with 6 bosses: none for the regular monsters, though 11 monsters qualify in all.
		for (int task = 0; task < TASKS; task++)
		{
			records.add(bossRecord(6, 6_000_000L));
		}
		records.removeIf(record -> record.getCreatureId() != null && record.getCreatureId() == 6);
		assertNull(advise(records, 1, TaskRanking.Metric.PROFIT));
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, advise(records, bossKey(1), "Boss 1").getAdvice());
	}

	@Test
	public void noAdviceWithFewerThanSixQualifyingMonsters()
	{
		List<LedgerRecord> records = profitMonsters(5);
		assertNull(advise(records, 1, TaskRanking.Metric.PROFIT));

		// A sixth monster under a minute of active time has no rate, so it doesn't count.
		for (int i = 0; i < TASKS; i++)
		{
			LedgerRecord brief = record(6, "Monster 6", 10_000L);
			brief.setLootGeValue(1_000L);
			brief.setSuppliesGeValue(0L);
			records.add(brief);
		}
		assertNull(advise(records, 1, TaskRanking.Metric.PROFIT));
		// Nor is that monster advised on.
		records.addAll(profitMonster(7, 70_000L, TASKS));
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, advise(records, 1, TaskRanking.Metric.PROFIT).getAdvice());
		assertNull(advise(records, 6, TaskRanking.Metric.PROFIT));
	}

	@Test
	public void noAdviceWithoutQualifyingTasksOfTheCurrentMonster()
	{
		List<LedgerRecord> records = profitMonsters(6);
		assertNull("never done", advise(records, 99, TaskRanking.Metric.PROFIT));

		// Partial and uncompleted tasks are not samples, as in the rankings.
		for (int i = 0; i < TASKS; i++)
		{
			LedgerRecord partial = profitRecord(99, 1_000L);
			partial.setPartial(true);
			LedgerRecord unfinished = profitRecord(99, 1_000L);
			unfinished.setCompleted(false);
			records.add(partial);
			records.add(unfinished);
		}
		assertNull(advise(records, 99, TaskRanking.Metric.PROFIT));

		assertNull(TaskAdvisor.advise(Collections.emptyList(), 1, "Monster 1", TaskRanking.Metric.PROFIT,
			LootPriceMode.GE, null));
		assertNull(TaskAdvisor.advise(records, 1, "Monster 1", null, LootPriceMode.GE, null));
	}

	@Test
	public void slayerXpCountsOnlySlayerXp()
	{
		List<LedgerRecord> records = new ArrayList<>();
		for (int i = 1; i <= 6; i++)
		{
			for (int task = 0; task < TASKS; task++)
			{
				LedgerRecord record = record(i, "Monster " + i, HOUR);
				Long slayer = i * 1_000L;
				record.setXpGained(new HashMap<>(Collections.singletonMap("SLAYER", slayer)));
				records.add(record);
			}
		}
		// Plenty of other XP doesn't lift the lowest.
		for (LedgerRecord record : monsterRecords(records, 1))
		{
			record.getXpGained().put("ATTACK", 1_000_000L);
		}
		TaskAdvisor.Result result = advise(records, 1, TaskRanking.Metric.XP);
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, result.getAdvice());
		assertEquals("Slayer XP/h 1,000: 1st lowest of your 6 monsters (bottom 25%)", result.reason(LootPriceMode.GE));
		// No profit was recorded, so there is no profit advice.
		assertNull(advise(records, 1, TaskRanking.Metric.PROFIT));
	}

	@Test
	public void pointsCountOnlyTasksTrackedFromTheStart()
	{
		List<LedgerRecord> records = new ArrayList<>();
		for (int i = 1; i <= 6; i++)
		{
			for (int task = 0; task < TASKS; task++)
			{
				LedgerRecord record = record(i, "Monster " + i, HOUR);
				record.setPointsEarned(i * 10);
				records.add(record);
			}
		}
		TaskAdvisor.Result lowest = advise(records, 1, TaskRanking.Metric.POINTS);
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, lowest.getAdvice());
		assertEquals("Points/h 10: 1st lowest of your 6 monsters (bottom 25%)", lowest.reason(LootPriceMode.GE));

		// A task started part-way through is left out of Points/h, so Monster 6 has 2 points tasks and doesn't count.
		monsterRecords(records, 6).get(0).setInitialAmount(5);
		assertNull(advise(records, 1, TaskRanking.Metric.POINTS));
	}

	@Test
	public void profitFollowsTheLootPriceMode()
	{
		List<LedgerRecord> records = profitMonsters(6);
		// At high alch Monster 1 is the best.
		for (LedgerRecord record : records)
		{
			record.setLootHaValue(1_000L);
		}
		for (LedgerRecord record : monsterRecords(records, 1))
		{
			record.setLootHaValue(500_000L);
		}
		TaskAdvisor.Result result = TaskAdvisor.advise(records, 1, "Monster 1", TaskRanking.Metric.PROFIT,
			LootPriceMode.HIGH_ALCH, null);
		assertEquals(TaskAdvisor.Advice.DO, result.getAdvice());
		assertEquals("Profit (HA)/h 500K: 1st highest of your 6 monsters", result.reason(LootPriceMode.HIGH_ALCH));
	}

	@Test
	public void aTaskWithoutACreatureKeyMatchesByName()
	{
		List<LedgerRecord> records = profitMonsters(6);
		TaskAdvisor.Result result = TaskAdvisor.advise(records, SessionTracker.NO_CREATURE, " monster 1 ",
			TaskRanking.Metric.PROFIT, LootPriceMode.GE, null);
		assertEquals(1, result.getPosition());

		// Older records without an id are grouped by name.
		List<LedgerRecord> named = profitMonsters(6);
		for (LedgerRecord record : named)
		{
			record.setCreatureId(null);
		}
		assertEquals(1, TaskAdvisor.advise(named, SessionTracker.NO_CREATURE, "Monster 1", TaskRanking.Metric.PROFIT,
			LootPriceMode.GE, null).getPosition());
		assertNull(TaskAdvisor.advise(named, SessionTracker.NO_CREATURE, " ", TaskRanking.Metric.PROFIT,
			LootPriceMode.GE, null));
	}

	@Test
	public void theUpperHalfCountsFromTheTop()
	{
		List<LedgerRecord> records = profitMonsters(24);
		assertEquals("Profit (GE)/h 200K: 5th highest of your 24 monsters",
			advise(records, 20, TaskRanking.Metric.PROFIT).reason(LootPriceMode.GE));
		assertEquals("Profit (GE)/h 130K: 12th highest of your 24 monsters",
			advise(records, 13, TaskRanking.Metric.PROFIT).reason(LootPriceMode.GE));
		// Exactly half way is still the lower half.
		assertEquals("Profit (GE)/h 120K: 12th lowest of your 24 monsters",
			advise(records, 12, TaskRanking.Metric.PROFIT).reason(LootPriceMode.GE));
		assertEquals("Profit (GE)/h 240K: 1st highest of your 24 monsters",
			advise(records, 24, TaskRanking.Metric.PROFIT).reason(LootPriceMode.GE));
	}

	@Test
	public void aCreatureKeyWithOnlyOlderRecordsWithoutAnIdMatchesByName()
	{
		List<LedgerRecord> records = profitMonsters(5);
		for (int i = 0; i < TASKS; i++)
		{
			LedgerRecord demons = profitRecord(42, 5_000L);
			demons.setCreatureId(null);
			demons.setMonster("Abyssal demons");
			records.add(demons);
		}
		TaskAdvisor.Result result = TaskAdvisor.advise(records, 42, "Abyssal demons", TaskRanking.Metric.PROFIT,
			LootPriceMode.GE, null);
		assertEquals(TaskAdvisor.Advice.CONSIDER_SKIP, result.getAdvice());
		assertEquals(1, result.getPosition());
		assertEquals(6, result.getMonsters());

		// With a record of its own id, the creature key is used as before (the older records then join it).
		records.add(profitRecord(42, 5_000L));
		records.get(records.size() - 1).setMonster("Abyssal demons");
		result = TaskAdvisor.advise(records, 42, "Abyssal demons", TaskRanking.Metric.PROFIT, LootPriceMode.GE, null);
		assertEquals(1, result.getPosition());
		assertEquals(6, result.getMonsters());
		assertEquals(4, result.getTasks());
	}

	@Test
	public void ordinals()
	{
		assertEquals("1st", TaskAdvisor.ordinal(1));
		assertEquals("2nd", TaskAdvisor.ordinal(2));
		assertEquals("3rd", TaskAdvisor.ordinal(3));
		assertEquals("4th", TaskAdvisor.ordinal(4));
		assertEquals("11th", TaskAdvisor.ordinal(11));
		assertEquals("12th", TaskAdvisor.ordinal(12));
		assertEquals("13th", TaskAdvisor.ordinal(13));
		assertEquals("21st", TaskAdvisor.ordinal(21));
		assertEquals("102nd", TaskAdvisor.ordinal(102));
		assertEquals("111th", TaskAdvisor.ordinal(111));
	}

	private static TaskAdvisor.Result advise(List<LedgerRecord> records, int creatureId, TaskRanking.Metric metric)
	{
		return advise(records, creatureId, "Monster " + creatureId, metric);
	}

	private static TaskAdvisor.Result advise(List<LedgerRecord> records, int creatureId, String monster)
	{
		return advise(records, creatureId, monster, TaskRanking.Metric.PROFIT);
	}

	private static TaskAdvisor.Result advise(List<LedgerRecord> records, int creatureId, String monster,
		TaskRanking.Metric metric)
	{
		return TaskAdvisor.advise(records, creatureId, monster, metric, LootPriceMode.GE, null);
	}

	/** Monsters 1 to {@code count}, {@link #TASKS} hour-long tasks each, monster {@code i} making {@code i * 10K}/h. */
	private static List<LedgerRecord> profitMonsters(int count)
	{
		return profitMonsters(count, TASKS);
	}

	private static List<LedgerRecord> profitMonsters(int count, int tasks)
	{
		List<LedgerRecord> records = new ArrayList<>();
		for (int i = 1; i <= count; i++)
		{
			records.addAll(profitMonster(i, i * 10_000L, tasks));
		}
		return records;
	}

	private static List<LedgerRecord> profitMonster(int creatureId, long profit, int tasks)
	{
		List<LedgerRecord> records = new ArrayList<>();
		for (int task = 0; task < tasks; task++)
		{
			records.add(profitRecord(creatureId, profit));
		}
		return records;
	}

	/** The records of the monster with {@code creatureId}. */
	private static List<LedgerRecord> monsterRecords(List<LedgerRecord> records, int creatureId)
	{
		List<LedgerRecord> own = new ArrayList<>();
		for (LedgerRecord record : records)
		{
			if (record.getCreatureId() != null && record.getCreatureId() == creatureId)
			{
				own.add(record);
			}
		}
		return own;
	}

	private static void setLoot(List<LedgerRecord> records, int creatureId, long lootGe)
	{
		for (LedgerRecord record : monsterRecords(records, creatureId))
		{
			record.setLootGeValue(lootGe);
		}
	}

	/** Boss {@code bossId}'s creature key, as {@link SessionTracker#creatureKey} makes it. */
	private static int bossKey(int bossId)
	{
		return -2 - bossId;
	}

	private static LedgerRecord bossRecord(int bossId, long profit)
	{
		LedgerRecord record = record(bossKey(bossId), "Boss " + bossId, HOUR);
		record.setLootGeValue(profit);
		record.setSuppliesGeValue(0L);
		return record;
	}

	private static LedgerRecord profitRecord(int creatureId, long profit)
	{
		LedgerRecord record = record(creatureId, "Monster " + creatureId, HOUR);
		record.setLootGeValue(profit);
		record.setSuppliesGeValue(0L);
		return record;
	}

	private static LedgerRecord record(int creatureId, String monster, long activeMillis)
	{
		LedgerRecord record = new LedgerRecord();
		record.setCreatureId(creatureId);
		record.setMonster(monster);
		record.setKills(10);
		record.setInitialAmount(10);
		record.setOriginalAmount(10);
		record.setActiveMillis(activeMillis);
		record.setCompleted(true);
		return record;
	}
}
