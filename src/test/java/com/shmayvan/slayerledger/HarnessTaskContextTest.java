package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Master, task type, Slayer points earned and task streak as wired in the plugin ({@link PluginHarness}): read on the
 * client thread with the task, saved with the record, shown in the live panel and the history, and never taken from a
 * login resync or a spend. {@link TaskContextTest}, {@link TaskRewardsTest} and {@link SlayerMasterTest} cover the
 * rules.
 */
public class HarnessTaskContextTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int TASK = 42;
	private static final int AREA = 12;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.startUp();
		harness.setTask(TASK, "Kraken", 3, 3);
		harness.setAssignment(SlayerMaster.KONAR, AREA, "Kraken Cove");
		harness.setSlayerPoints(500);
		harness.setVarbit(VarbitID.SLAYER_TASKS_COMPLETED, 42);
	}

	/** Completes the task with {@code points} and waits out the late-loot grace window. */
	private LedgerRecord completeAndSave(int points)
	{
		harness.completeTask(points);
		harness.tick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
		return harness.onlyRecord(ACCOUNT);
	}

	private String assignmentText()
	{
		final String[] text = new String[1];
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			text[0] = harness.ui().getTaskBox().getAssignmentText();
		});
		return text[0];
	}

	/** The task box's rows as shown. */
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

	private List<String> historyText()
	{
		final List<?>[] rows = new List<?>[1];
		harness.onEdt(() -> rows[0] = harness.ui().getHistory().getValuesText());
		@SuppressWarnings("unchecked")
		final List<String> result = (List<String>) rows[0];
		return result;
	}

	@Test
	public void completedTaskRecordsMasterTypePointsAndStreak()
	{
		harness.loginAndSettle(ACCOUNT);
		assertEquals("Master: Konar | Type: Konar (Kraken Cove)", assignmentText());
		// Konar's type is her name, so the second line shows it once; the assigned area is the location.
		assertEquals(Arrays.asList("Kraken · 3 left", "Konar · Kraken Cove · bonus in 8"), taskBoxRows());
		assertTrue(harness.play());
		harness.setRemaining(1);
		harness.tick();

		LedgerRecord record = completeAndSave(18);
		assertTrue(record.isCompleted());
		assertEquals(Integer.valueOf(SlayerMaster.KONAR), record.getSlayerMaster());
		assertEquals(Integer.valueOf(AREA), record.getSlayerArea());
		assertEquals("Kraken Cove", record.getTaskLocation());
		assertEquals(Integer.valueOf(18), record.getPointsEarned());
		assertEquals(Integer.valueOf(43), record.getTaskStreak());
		assertNull("no task now", assignmentText());
		assertEquals("the next bonus stays", Arrays.asList("Task: None", "bonus in 7"), taskBoxRows());

		String row = historyText().get(0);
		assertTrue(row, row.contains("<br>Master: Konar<br>Type: Konar (Kraken Cove)<br>Points earned: 18"
			+ "<br>Task streak: 43"));
	}

	@Test
	public void pointsArrivingDuringTheGraceWindowCount()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		harness.completeTask();
		harness.tick();
		assertTrue(harness.tracker().hasPendingCompletion());
		// The points varbit lands a tick after the streak counter.
		harness.setSlayerPoints(518);
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(Integer.valueOf(18), record.getPointsEarned());
		assertEquals(Integer.valueOf(43), record.getTaskStreak());
	}

	@Test
	public void streakArrivingAfterTheLastKillStillRecordsTheRewards()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		// SLAYER_COUNT reaches 0 a tick before the streak counter and points go up.
		harness.setRemaining(0);
		harness.tick();
		harness.setVarbit(VarbitID.SLAYER_TASKS_COMPLETED, 43);
		harness.setSlayerPoints(518);
		harness.tick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals(Integer.valueOf(18), record.getPointsEarned());
		assertEquals(Integer.valueOf(43), record.getTaskStreak());
	}

	@Test
	public void pointsSpentMidTaskDontChangeTheTaskPoints()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		harness.setRemaining(2);
		harness.tick();
		// Spent 400 points at a master during the task.
		harness.setSlayerPoints(100);
		harness.tick();
		LedgerRecord record = completeAndSave(18);
		assertEquals(Integer.valueOf(18), record.getPointsEarned());
	}

	@Test
	public void pointsThatWentDownAtTheCompletionAreNotRecorded()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		LedgerRecord record = completeAndSave(-50);
		assertNull("never negative", record.getPointsEarned());
		assertEquals(Integer.valueOf(43), record.getTaskStreak());
	}

	@Test
	public void loginResyncIsNotAReward()
	{
		// The game reads 0 points and no streak until the server resyncs them after login.
		harness.setSlayerPoints(0);
		harness.setVarbit(VarbitID.SLAYER_TASKS_COMPLETED, 0);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		harness.setSlayerPoints(500);
		harness.setVarbit(VarbitID.SLAYER_TASKS_COMPLETED, 42);
		harness.tick();
		assertTrue("a resync is no completion", harness.records(ACCOUNT).isEmpty());

		// Logging out and back in resets the baselines; the first reading after it never counts.
		harness.logout();
		harness.login(ACCOUNT);
		harness.setSlayerPoints(900);
		harness.ticks(PluginHarness.SETTLE_TICKS);
		assertTrue(harness.play());
		LedgerRecord record = completeAndSave(18);
		assertEquals("from the reading after login, not the one before logout", Integer.valueOf(18),
			record.getPointsEarned());
	}

	@Test
	public void wildernessCompletionRecordsTheWildernessStreak()
	{
		harness.setAssignment(SlayerMaster.KRYSTILIA, 0, null);
		harness.setVarbit(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED, 9);
		harness.loginAndSettle(ACCOUNT);
		assertEquals("Master: Krystilia | Type: Wilderness", assignmentText());
		assertEquals(Arrays.asList("Kraken · 3 left", "Krystilia · Wilderness · bonus in 1"), taskBoxRows());
		assertTrue(harness.play());
		harness.setRemaining(0);
		harness.setVarbit(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED, 10);
		harness.setSlayerPoints(525);
		harness.tick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(Integer.valueOf(10), record.getTaskStreak());
		assertEquals(Integer.valueOf(25), record.getPointsEarned());
		assertEquals(Integer.valueOf(SlayerMaster.KRYSTILIA), record.getSlayerMaster());
		assertNull(record.getSlayerArea());
	}

	@Test
	public void mortimerCompletionRecordsMortimersStreak()
	{
		harness.setAssignment(SlayerMaster.MORTIMER, 0, null);
		harness.setVarp(VarPlayerID.SLAYER_MORTIMER_TASKS_COMPLETED, 4);
		harness.loginAndSettle(ACCOUNT);
		assertEquals("Master: Mortimer | Type: Regular", assignmentText());
		assertEquals("Regular is not shown", Arrays.asList("Kraken · 3 left", "Mortimer · bonus in 6"),
			taskBoxRows());
		assertTrue(harness.play());
		harness.setRemaining(0);
		harness.setVarp(VarPlayerID.SLAYER_MORTIMER_TASKS_COMPLETED, 5);
		harness.tick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
		assertEquals(Integer.valueOf(5), harness.onlyRecord(ACCOUNT).getTaskStreak());
	}

	@Test
	public void bossTaskAndUnmappedMaster()
	{
		harness.setTask(SessionTracker.BOSS_TASK_TARGET, "Boss", 3, 3);
		harness.setAssignment(14, 0, null);
		harness.loginAndSettle(ACCOUNT);
		assertEquals("Master: Unknown master (14) | Type: Boss", assignmentText());
		// The boss is not known yet (no boss id), as the task line shows it.
		assertEquals(Arrays.asList("Unknown boss task (0) · 3 left", "Unknown master (14) · Boss"),
			taskBoxRows());
	}

	@Test
	public void masterSyncedAfterTheStartIsFilledIn()
	{
		harness.setAssignment(SlayerMaster.NONE, 0, null);
		harness.loginAndSettle(ACCOUNT);
		assertNull("nothing known: no second line", assignmentText());
		assertEquals(Arrays.asList("Kraken · 3 left"), taskBoxRows());
		assertTrue(harness.play());
		harness.setAssignment(6, 0, null);
		harness.tick();
		harness.stop();
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isPartial());
		assertEquals(Integer.valueOf(6), record.getSlayerMaster());
		assertNull("not completed: no points", record.getPointsEarned());
		assertNull(record.getTaskStreak());
	}

	@Test
	public void pausedCompletionStillRecordsItsRewards()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		harness.pause();
		harness.completeTask(18);
		harness.tick();
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals(Integer.valueOf(18), record.getPointsEarned());
		assertEquals(Integer.valueOf(43), record.getTaskStreak());
	}

	@Test
	public void restoredSessionKeepsItsAssignment()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		harness.logout();
		harness.shutDown();
		assertEquals(Integer.valueOf(SlayerMaster.KONAR), harness.activeSession(ACCOUNT).getSlayerMaster());

		// After the restart the game hasn't sent the master and area yet; the snapshot's are kept.
		harness.setAssignment(SlayerMaster.NONE, 0, null);
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		assertEquals(SessionTracker.State.PAUSED, harness.sessionState());
		assertTrue(harness.play());
		LedgerRecord record = completeAndSave(18);
		assertEquals(Integer.valueOf(SlayerMaster.KONAR), record.getSlayerMaster());
		assertEquals(Integer.valueOf(AREA), record.getSlayerArea());
		assertEquals("Kraken Cove", record.getTaskLocation());
		assertEquals(Integer.valueOf(18), record.getPointsEarned());
	}

	@Test
	public void olderRecordWithoutTheFieldsStillLoads() throws IOException
	{
		assertTrue(harness.ledgerDirectory().isDirectory() || harness.ledgerDirectory().mkdirs());
		String json = "{\"schemaVersion\":2,\"records\":[{\"schemaVersion\":2,\"id\":\"old\",\"monster\":\"Kraken\","
			+ "\"creatureId\":42,\"initialAmount\":3,\"kills\":3,\"activeMillis\":60000,\"startedAt\":1000,"
			+ "\"endedAt\":61000,\"completed\":true,\"partial\":false}]}";
		Files.write(harness.ledgerDirectory().toPath().resolve(ACCOUNT + ".json"), json.getBytes(StandardCharsets.UTF_8));

		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		harness.completeTask(18);
		harness.tick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);

		List<LedgerRecord> records = harness.records(ACCOUNT);
		assertEquals(2, records.size());
		LedgerRecord old = records.stream().filter(r -> "old".equals(r.getId())).findFirst().orElseThrow(AssertionError::new);
		assertNull(old.getSlayerMaster());
		assertNull(old.getPointsEarned());
		assertNull(old.getTaskStreak());
		List<String> rows = historyText();
		assertEquals(2, rows.size());
		String oldRow = rows.get(1);
		assertTrue(oldRow, oldRow.endsWith("<br>Deaths: -</html>"));
	}
}
