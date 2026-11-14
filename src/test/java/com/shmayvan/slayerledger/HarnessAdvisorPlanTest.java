package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;

/**
 * The advisor's plan as wired in the plugin ({@link PluginHarness}): worked out off the EDT for the current task
 * when the task, its kills left, the records or the "Advisor metric" change, kept only for the latest inputs (the plan
 * shown stays through a kill until the new one is back), never worked out on the panel's refresh timer, and for
 * Konar's and Krystilia's tasks from the history where their kills count. {@link TaskPlannerTest} covers the plan
 * itself, {@link HarnessAssignmentLineTest} the chat line.
 */
public class HarnessAdvisorPlanTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final long MINUTE = 60_000L;
	private static final int DEMONS = 42;
	/** Nieve's SLAYER_MASTER value: a regular master. */
	private static final int NIEVE = 6;
	/** A SLAYER_AREA value for Konar's assigned area, which the harness names. */
	private static final int KONAR_AREA = 7;
	/** Numbers the records' ids: the store skips a duplicate id, so they must never repeat. */
	private static final AtomicInteger IDS = new AtomicInteger();

	@Rule
	public final PluginHarness harness = new PluginHarness();

	/**
	 * A completed Abyssal demons task of 10 kills at {@code spotId} with melee: with a cannon, 30 minutes for 30K of
	 * coins and 30K Slayer XP; without, 60 minutes for 300K and 1K Slayer XP.
	 */
	private static LedgerRecord demons(String spotId, boolean cannon)
	{
		LedgerRecord record = new LedgerRecord();
		record.setId("demons-" + IDS.incrementAndGet());
		record.setCreatureId(DEMONS);
		record.setMonster("Abyssal demons");
		record.setKills(10);
		record.setInitialAmount(10);
		record.setOriginalAmount(10);
		record.setActiveMillis((cannon ? 30 : 60) * MINUTE);
		record.setCompleted(true);
		record.setLootGeValue(cannon ? 30_000L : 300_000L);
		record.setLootHaValue(0L);
		record.setSuppliesGeValue(0L);
		record.setXpGained(Collections.singletonMap(SkillXp.SLAYER, cannon ? 30_000L : 1_000L));
		record.setSpotId(spotId);
		SetupTags.detected(cannon, SetupStyle.MELEE, GearFocus.UNKNOWN, SetupOverrides.NONE).copyTo(record);
		return SavedItems.withItems(record);
	}

	/** Three tasks of each {@link #demons} setup at the Smoke Dungeon. */
	private static List<LedgerRecord> smokeDungeon()
	{
		List<LedgerRecord> records = new ArrayList<>();
		for (int i = 0; i < 3; i++)
		{
			records.add(demons(TestSpots.SMOKE_ID, false));
			records.add(demons(TestSpots.SMOKE_ID, true));
		}
		return records;
	}

	/**
	 * {@link #smokeDungeon} and three hour-long tasks of each of five other monsters, at 100K profit and 50K Slayer XP
	 * an hour: Abyssal demons make the most profit per hour and the least Slayer XP, so the badge says Do by
	 * profit and Consider skip by Slayer XP.
	 */
	private static List<LedgerRecord> withOtherMonsters()
	{
		List<LedgerRecord> records = smokeDungeon();
		for (int monster = 1; monster <= 5; monster++)
		{
			for (int i = 0; i < 3; i++)
			{
				LedgerRecord record = new LedgerRecord();
				record.setId("other-" + IDS.incrementAndGet());
				record.setCreatureId(DEMONS + monster);
				record.setMonster("Monster " + monster);
				record.setKills(10);
				record.setActiveMillis(60 * MINUTE);
				record.setCompleted(true);
				record.setLootGeValue(100_000L);
				record.setLootHaValue(0L);
				record.setSuppliesGeValue(0L);
				record.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 50_000L));
				records.add(SavedItems.withItems(record));
			}
		}
		return records;
	}

	/** Starts the plugin on a 20-kill Abyssal demons task and shows {@code records}. */
	private void start(List<LedgerRecord> records)
	{
		harness.startUp();
		harness.setInventory();
		harness.setTask(DEMONS, "Abyssal demons", 20, 20);
		harness.loginAndSettle(ACCOUNT);
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, records));
		harness.drain();
	}

	/** The model's plan to show now, without draining anything first. */
	private TaskPlan plan()
	{
		AtomicReference<TaskPlan> plan = new AtomicReference<>();
		harness.onEdt(() -> plan.set(harness.historyModel().plan()));
		return plan.get();
	}

	/** The model's plan for exactly the current inputs now, without draining anything first. */
	private TaskPlan exactPlan()
	{
		AtomicReference<TaskPlan> plan = new AtomicReference<>();
		harness.onEdt(() -> plan.set(harness.historyModel().exactPlan()));
		return plan.get();
	}

	private int planBuilds()
	{
		AtomicInteger builds = new AtomicInteger();
		harness.onEdt(() -> builds.set(harness.historyModel().getPlanBuildCount()));
		return builds.get();
	}

	/**
	 * The advisor card's skip / block badge after a panel refresh, once the advice worked out off the
	 * EDT is back; {@code null} for Do, which adds no line.
	 */
	private String advice()
	{
		AtomicReference<String> text = new AtomicReference<>();
		harness.onEdt(() -> harness.panel().refresh());
		harness.drain();
		harness.onEdt(() -> text.set(harness.ui().getAdvisor().getBadge()));
		return text.get();
	}

	@Test
	public void thePlanShownStaysThroughAKillAndAnOlderOneIsDropped()
	{
		start(smokeDungeon());
		TaskPlan first = plan();
		assertNotNull(first);
		assertEquals(20, first.getKillsLeft());
		int builds = planBuilds();

		// A kill: the settled tick asks for the plan for 19 kills left, which waits for the executor. Meanwhile the
		// plan for 20 is still shown, so the card doesn't blank once a kill; there is no exact plan.
		harness.setRemaining(19);
		harness.tickWithoutDrain();
		harness.runClientThread();
		harness.flushEdt();
		assertNull("being worked out", exactPlan());
		assertSame("the kept plan is shown", first, plan());
		assertEquals(builds + 1, planBuilds());

		// Another kill before it is back: its plan is dropped when it arrives, and the plan for 18 is kept.
		harness.setRemaining(18);
		harness.tickWithoutDrain();
		harness.runClientThread();
		harness.flushEdt();
		assertEquals(builds + 2, planBuilds());
		assertSame(first, plan());
		harness.runExecutor();
		harness.flushEdt();
		TaskPlan latest = plan();
		assertEquals(18, latest.getKillsLeft());
		assertSame(latest, exactPlan());
		harness.drain();
		assertEquals("nothing more worked out", builds + 2, planBuilds());
		assertEquals(18, plan().getKillsLeft());
	}

	@Test
	public void theMetricChangesThePlansRecommendationAndTheBadge()
	{
		start(withOtherMonsters());

		// Fastest finish (the default): the cannon tasks, at 3 minutes a kill; the badge by Slayer XP/h.
		TaskPlan fastest = plan();
		assertEquals(AdvisorMetric.FASTEST_FINISH, fastest.getMetric());
		assertTrue(fastest.getRecommendation().isCannon());
		assertFalse(fastest.getCounterpart().isCannon());
		assertEquals("Consider skip (n=6)", advice());

		harness.setAdvisorMetric(AdvisorMetric.PROFIT_PER_HOUR);
		TaskPlan profit = plan();
		assertEquals(AdvisorMetric.PROFIT_PER_HOUR, profit.getMetric());
		assertFalse("300K an hour without the cannon", profit.getRecommendation().isCannon());
		assertTrue(profit.getCounterpart().isCannon());
		// The most profit per hour: Do, which adds no line. The 1.x "Better: without cannon" line is gone: the card's
		// counterpart says it.
		assertNull(advice());

		harness.setAdvisorMetric(AdvisorMetric.FASTEST_FINISH);
		assertTrue(plan().getRecommendation().isCannon());
		assertEquals("Consider skip (n=6)", advice());
	}

	@Test
	public void nothingIsWorkedOutOnThePanelRefreshTimer()
	{
		start(smokeDungeon());
		assertNotNull(plan());
		int builds = planBuilds();

		// The refresh timer and ticks with nothing changed work nothing out.
		for (int i = 0; i < 5; i++)
		{
			harness.onEdt(() -> harness.panel().refresh());
		}
		harness.ticks(5);
		assertEquals(builds, planBuilds());
		assertNotNull(plan());

		// With the panel shut (as with the sidebar closed for good), a kill still brings the new plan.
		harness.onEdt(() -> harness.panel().shutDown());
		harness.setRemaining(19);
		harness.tick();
		assertEquals(19, plan().getKillsLeft());
		assertEquals(builds + 1, planBuilds());
	}

	@Test
	public void aKonarTaskUsesTheOptionsAtItsAssignedArea()
	{
		List<LedgerRecord> records = smokeDungeon();
		// One slower barrage task in the Catacombs, where Konar sends the player.
		LedgerRecord catacombs = demons(TestSpots.CATACOMBS_ID, false);
		catacombs.setActiveMillis(90 * MINUTE);
		catacombs.setDetectedStyle(SetupStyle.BARRAGE);
		records.add(catacombs);
		harness.setAssignment(SlayerMaster.KONAR, KONAR_AREA, "Catacombs of Kourend");
		start(records);

		TaskPlan plan = plan();
		assertEquals(TestSpots.CATACOMBS_ID, plan.getAreaSpot());
		assertFalse(plan.isAreaFallback());
		assertEquals("only the option there", 1, plan.getOptions().size());
		assertEquals(TestSpots.CATACOMBS_ID, plan.getRecommendation().getSpot());
		assertEquals(SetupStyle.BARRAGE, plan.getRecommendation().getStyle());
		assertTrue("one task", plan.isRecommendationThin());
		assertEquals("All setups keeps every task", 7, plan.getAllSetups().getTasks());

		// Another master's task of the same creature: every option.
		harness.setAssignment(NIEVE, 0, null);
		harness.tick();
		assertNull(plan().getAreaSpot());
		assertEquals(TestSpots.SMOKE_ID, plan().getRecommendation().getSpot());
	}

	@Test
	public void aWildernessTaskUsesOnlyWildernessHistory()
	{
		List<LedgerRecord> records = smokeDungeon();
		for (LedgerRecord record : records)
		{
			record.setSlayerMaster(NIEVE);
		}
		// Two of Krystilia's tasks, in the Slayer Tower for the test's sake.
		for (int i = 0; i < 2; i++)
		{
			LedgerRecord wilderness = demons(TestSpots.TOWER_ID, false);
			wilderness.setSlayerMaster(SlayerMaster.KRYSTILIA);
			records.add(wilderness);
		}
		harness.setAssignment(SlayerMaster.KRYSTILIA, 0, null);
		start(records);

		TaskPlan plan = plan();
		assertEquals("only the Wilderness tasks", 2, plan.getAllSetups().getTasks());
		assertEquals(TestSpots.TOWER_ID, plan.getRecommendation().getSpot());

		// The master's varbit says Nieve now: every task of the creature.
		harness.setAssignment(NIEVE, 0, null);
		harness.tick();
		assertEquals(8, plan().getAllSetups().getTasks());
	}
}
