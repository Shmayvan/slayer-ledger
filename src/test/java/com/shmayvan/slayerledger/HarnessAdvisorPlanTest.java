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
 * when the task, its kills left or the records change, kept only for the latest inputs (the plan shown stays through a
 * kill until the new one is back), never worked out on the panel's refresh timer, and for Konar's and Krystilia's
 * tasks from the history where their kills count. {@link TaskPlannerTest} covers the plan itself.
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


	private int planBuilds()
	{
		AtomicInteger builds = new AtomicInteger();
		harness.onEdt(() -> builds.set(harness.historyModel().getPlanBuildCount()));
		return builds.get();
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
		// plan for 20 is still shown, so the card doesn't blank once a kill.
		harness.setRemaining(19);
		harness.tickWithoutDrain();
		harness.runClientThread();
		harness.flushEdt();
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
		harness.drain();
		assertEquals("nothing more worked out", builds + 2, planBuilds());
		assertEquals(18, plan().getKillsLeft());
	}

	@Test
	public void theFastestSetupIsRecommended()
	{
		start(smokeDungeon());

		// The cannon tasks, at 3 minutes a kill against 6: always the least time for the kills left.
		TaskPlan plan = plan();
		assertTrue(plan.getRecommendation().isCannon());
		assertEquals("both setups are options", 2, plan.getOptions().size());
		assertFalse(plan.getOptions().get(1).isCannon());
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
