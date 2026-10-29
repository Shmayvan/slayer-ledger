package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * The task box's next bonus, short on its second line and in full in that line's tooltip, as wired in the
 * plugin ({@link PluginHarness}): the streak of the current task's master, read on settled ticks, the new streak right
 * after a completion, and hidden while it is unknown.
 * {@link StreakMilestoneTest} covers the rules.
 */
public class HarnessStreakHintTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.startUp();
		harness.setTask(42, "Kraken", 3, 3);
		harness.setAssignment(SlayerMaster.KONAR, 0, null);
		harness.setVarbit(VarbitID.SLAYER_TASKS_COMPLETED, 47);
		harness.setVarbit(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED, 9);
		harness.setVarp(VarPlayerID.SLAYER_MORTIMER_TASKS_COMPLETED, 120);
	}

	private String hint()
	{
		final String[] text = new String[1];
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			text[0] = harness.ui().getTaskBox().getStreakHintText();
		});
		return text[0];
	}

	private List<String> taskBoxRows()
	{
		final List<?>[] rows = new List<?>[1];
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			rows[0] = harness.ui().getTaskBox().getRows();
		});
		@SuppressWarnings("unchecked")
		final List<String> result = (List<String>) rows[0];
		return result;
	}

	@Test
	public void hiddenUntilLoggedInAndSettled()
	{
		assertNull("not logged in", hint());
		harness.login(ACCOUNT);
		assertNull("no settled reading yet", hint());
		harness.ticks(PluginHarness.SETTLE_TICKS);
		assertEquals("Next bonus: task 50 (in 3)", hint());
	}

	@Test
	public void shownShortOnTheSecondLineAndInFullInItsTooltip()
	{
		harness.loginAndSettle(ACCOUNT);
		assertEquals(Arrays.asList("Kraken · 3 left", "Konar · bonus in 3"), taskBoxRows());
		assertEquals("Next bonus: task 50 (in 3)", hint());
	}

	@Test
	public void hiddenWhileTheMasterIsUnknown()
	{
		harness.setAssignment(SlayerMaster.NONE, 0, null);
		harness.loginAndSettle(ACCOUNT);
		assertNull(hint());
		// The master's varbit syncs later.
		harness.setAssignment(6, 0, null);
		harness.tick();
		assertEquals("Next bonus: task 50 (in 3)", hint());
	}

	@Test
	public void hiddenForAMasterNotInTheTable()
	{
		harness.setAssignment(14, 0, null);
		harness.loginAndSettle(ACCOUNT);
		assertNull(hint());
	}

	@Test
	public void hiddenWithNoTaskAndNoCompletionSeen()
	{
		harness.setRemaining(0);
		harness.loginAndSettle(ACCOUNT);
		assertNull(hint());
	}

	@Test
	public void justCompletedTaskShowsTheNewStreak()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		harness.completeTask(18);
		harness.tick();
		assertEquals("streak 48, no task yet", "Next bonus: task 50 (in 2)", hint());
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
		assertEquals("kept until the next task", "Next bonus: task 50 (in 2)", hint());

		// The next task, from the same master type.
		harness.setTask(43, "Hydras", 100, 100);
		harness.tick();
		assertEquals("Next bonus: task 50 (in 2)", hint());
	}

	@Test
	public void completingTheTenthTaskMovesToTheNext()
	{
		harness.setVarbit(VarbitID.SLAYER_TASKS_COMPLETED, 49);
		harness.loginAndSettle(ACCOUNT);
		assertEquals("Next bonus: task 50 (in 1)", hint());
		// Completed without a session: the hint still follows the counter.
		harness.completeTask();
		harness.tick();
		assertEquals("Next bonus: task 60 (in 10)", hint());
	}

	@Test
	public void wildernessAndMortimerUseTheirOwnStreaks()
	{
		harness.setAssignment(SlayerMaster.KRYSTILIA, 0, null);
		harness.loginAndSettle(ACCOUNT);
		assertEquals("Wilderness streak 9", "Next bonus: task 10 (in 1)", hint());
		harness.setRemaining(0);
		harness.setVarbit(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED, 10);
		harness.tick();
		assertEquals("the Wilderness streak went up", "Next bonus: task 20 (in 10)", hint());

		harness.setTask(43, "Hydras", 100, 100);
		harness.setAssignment(SlayerMaster.MORTIMER, 0, null);
		harness.tick();
		assertEquals("Mortimer streak 120", "Next bonus: task 130 (in 10)", hint());

		harness.setAssignment(SlayerMaster.KONAR, 0, null);
		harness.tick();
		assertEquals("regular streak 47", "Next bonus: task 50 (in 3)", hint());
	}

	@Test
	public void aStreakRisingATickAfterTheTaskEndsKeepsItsMaster()
	{
		harness.setAssignment(SlayerMaster.KRYSTILIA, 0, null);
		harness.loginAndSettle(ACCOUNT);
		harness.setRemaining(0);
		harness.tick();
		assertEquals("still the Wilderness streak", "Next bonus: task 10 (in 1)", hint());
		harness.setVarbit(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED, 10);
		harness.tick();
		assertEquals("the Wilderness streak went up", "Next bonus: task 20 (in 10)", hint());
	}

	@Test
	public void logoutHidesItAndAHopKeepsIt()
	{
		harness.loginAndSettle(ACCOUNT);
		harness.completeTask();
		harness.tick();
		assertEquals("Next bonus: task 50 (in 2)", hint());

		harness.hop();
		assertNull("no settled reading after the hop yet", hint());
		harness.ticks(PluginHarness.SETTLE_TICKS);
		assertEquals("the completed task's streak is kept across a hop", "Next bonus: task 50 (in 2)", hint());

		harness.logout();
		assertNull(hint());
		harness.loginAndSettle(ACCOUNT);
		assertNull("no task and no completion seen since the login screen", hint());
	}
}
