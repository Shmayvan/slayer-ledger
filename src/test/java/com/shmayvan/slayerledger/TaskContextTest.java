package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link TaskContext} (master, area, points earned and streak) on its own and as the session carries it: taken with the
 * task, filled from later readings of the same task, rewards set on completion only, and through snapshots, restores,
 * records, JSON and the history tooltip.
 */
public class TaskContextTest
{
	private static final int TASK = 42;
	private static final long ACCOUNT = 555L;
	private static final int[] COUNTERS = {100, 2, 0};
	private static final TaskContext KONAR_AT_COVE = TaskContext.assignment(SlayerMaster.KONAR, 12, "Kraken Cove");

	private final Gson gson = new Gson();
	private long now;
	private SessionTracker tracker;

	@Before
	public void setUp()
	{
		now = 1_000_000L;
		tracker = new SessionTracker(() -> now);
	}

	private SessionTracker.ObserveResult observe(int remaining, TaskContext assignment, boolean completion)
	{
		return tracker.observe("Kraken", TASK, remaining, 100, assignment, completion);
	}

	// ---- TaskContext ----

	@Test
	public void assignmentKeepsOnlyRealValues()
	{
		assertSame(TaskContext.NONE, TaskContext.assignment(SlayerMaster.NONE, 0, "Ignored"));
		TaskContext noArea = TaskContext.assignment(6, 0, null);
		assertEquals(Integer.valueOf(6), noArea.getMaster());
		assertNull(noArea.getArea());
		assertNull(noArea.getLocation());
		TaskContext unnamed = TaskContext.assignment(SlayerMaster.KONAR, 12, "");
		assertEquals(Integer.valueOf(12), unnamed.getArea());
		assertNull(unnamed.getLocation());
		assertEquals(TaskType.KONAR, KONAR_AT_COVE.type(TASK));
	}

	@Test
	public void fillAssignmentOnlyAddsWhatIsMissing()
	{
		TaskContext unsynced = TaskContext.assignment(SlayerMaster.NONE, 12, null);
		TaskContext filled = unsynced.fillAssignment(KONAR_AT_COVE);
		assertEquals(KONAR_AT_COVE, filled);
		TaskContext other = TaskContext.assignment(6, 30, "Elsewhere");
		assertEquals("known parts are kept", KONAR_AT_COVE, KONAR_AT_COVE.fillAssignment(other));
		assertEquals("a name for another area is not taken", TaskContext.assignment(SlayerMaster.KONAR, 12, null),
			TaskContext.assignment(SlayerMaster.KONAR, 12, null).fillAssignment(other));
		assertEquals(KONAR_AT_COVE, KONAR_AT_COVE.fillAssignment(null));
	}

	// ---- Session ----

	@Test
	public void sessionTakesTheAssignmentWithTheTask()
	{
		observe(50, KONAR_AT_COVE, false);
		assertEquals(KONAR_AT_COVE, tracker.getCurrentContext());
		assertTrue(tracker.play(ACCOUNT));
		assertEquals(KONAR_AT_COVE, tracker.summary().getContext());
	}

	@Test
	public void laterReadingsOfTheSameTaskFillTheGaps()
	{
		observe(50, TaskContext.assignment(SlayerMaster.NONE, 0, null), false);
		assertTrue(tracker.play(ACCOUNT));
		assertNull(tracker.summary().getContext().getMaster());
		observe(49, KONAR_AT_COVE, false);
		assertEquals(KONAR_AT_COVE, tracker.summary().getContext());
	}

	@Test
	public void noTaskClearsTheCurrentAssignment()
	{
		observe(50, KONAR_AT_COVE, false);
		tracker.observe(null, SessionTracker.NO_CREATURE, 0, false);
		assertSame(TaskContext.NONE, tracker.getCurrentContext());
		observe(50, KONAR_AT_COVE, false);
		tracker.clearCurrentTask();
		assertSame(TaskContext.NONE, tracker.getCurrentContext());
	}

	@Test
	public void rewardsAreSetOnlyOnACompletedSession()
	{
		observe(2, KONAR_AT_COVE, false);
		assertTrue(tracker.play(ACCOUNT));
		String id = tracker.getSessionId();
		assertFalse("not completed yet", tracker.setCompletionRewards(id, 18, 43));
		assertEquals(SessionTracker.ObserveResult.COMPLETED, observe(0, TaskContext.NONE, true));
		assertFalse("another session", tracker.setCompletionRewards("other", 18, 43));
		assertTrue(tracker.setCompletionRewards(id, 18, 43));
		assertEquals(Integer.valueOf(18), tracker.summary().getContext().getPointsEarned());
		assertEquals(Integer.valueOf(43), tracker.summary().getContext().getTaskStreak());
		assertEquals("the assignment stays", Integer.valueOf(SlayerMaster.KONAR), tracker.summary().getContext().getMaster());
	}

	@Test
	public void heldCompletionTakesCorrectedPoints()
	{
		observe(2, KONAR_AT_COVE, false);
		assertTrue(tracker.play(ACCOUNT));
		String id = tracker.getSessionId();
		observe(0, TaskContext.NONE, true);
		assertTrue(tracker.setCompletionRewards(id, 0, 43));
		assertTrue(tracker.holdCompletion());
		assertTrue(tracker.setCompletionRewards(id, 18, 43));
		SessionTracker.Summary held = tracker.takePendingCompletion();
		assertEquals(Integer.valueOf(18), held.getContext().getPointsEarned());
		assertFalse("saved: nothing left to update", tracker.setCompletionRewards(id, 20, 43));
	}

	@Test
	public void recordHasTheContextAndRewardsOnlyWhenCompleted()
	{
		observe(2, KONAR_AT_COVE, false);
		assertTrue(tracker.play(ACCOUNT));
		String id = tracker.getSessionId();
		observe(0, TaskContext.NONE, true);
		tracker.setCompletionRewards(id, 18, 43);
		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), false);
		assertEquals(Integer.valueOf(SlayerMaster.KONAR), record.getSlayerMaster());
		assertEquals(Integer.valueOf(12), record.getSlayerArea());
		assertEquals("Kraken Cove", record.getTaskLocation());
		assertEquals(Integer.valueOf(18), record.getPointsEarned());
		assertEquals(Integer.valueOf(43), record.getTaskStreak());

		LedgerRecord copy = gson.fromJson(gson.toJson(record), LedgerRecord.class);
		assertEquals(record, copy);
	}

	@Test
	public void partialRecordHasNoRewards()
	{
		observe(50, KONAR_AT_COVE, false);
		assertTrue(tracker.play(ACCOUNT));
		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), true);
		assertEquals(Integer.valueOf(SlayerMaster.KONAR), record.getSlayerMaster());
		assertNull(record.getPointsEarned());
		assertNull(record.getTaskStreak());
	}

	@Test
	public void snapshotRestoresTheAssignment()
	{
		observe(50, KONAR_AT_COVE, false);
		assertTrue(tracker.play(ACCOUNT));
		SessionSnapshot snapshot = SessionSnapshot.fromSummary(tracker.stopAndReset(), COUNTERS, now);
		SessionSnapshot copy = gson.fromJson(gson.toJson(snapshot), SessionSnapshot.class);
		assertEquals(Integer.valueOf(SlayerMaster.KONAR), copy.getSlayerMaster());

		SessionTracker restored = new SessionTracker(() -> now);
		// The game's reading lacks the area name this time; the snapshot's is kept.
		restored.observe("Kraken", TASK, 50, 100, TaskContext.assignment(SlayerMaster.KONAR, 12, null), false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, restored.restore(copy, COUNTERS));
		assertEquals(KONAR_AT_COVE, restored.summary().getContext());
		assertEquals(KONAR_AT_COVE, copy.toSummary().getContext());
	}

	@Test
	public void olderSnapshotTakesTheAssignmentFromTheMatchingTask()
	{
		String json = "{\"schemaVersion\":2,\"sessionId\":\"s1\",\"accountHash\":555,\"monster\":\"Kraken\","
			+ "\"creatureId\":42,\"initialAmount\":60,\"originalAmount\":100,\"kills\":10,\"remaining\":50}";
		SessionSnapshot old = gson.fromJson(json, SessionSnapshot.class);
		assertNull(old.getSlayerMaster());
		assertSame(TaskContext.NONE, old.toSummary().getContext());
		observe(50, KONAR_AT_COVE, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, tracker.restore(old, null));
		assertEquals(KONAR_AT_COVE, tracker.summary().getContext());
	}

	@Test
	public void olderRecordLoadsWithEverythingUnknown()
	{
		String json = "{\"schemaVersion\":2,\"id\":\"x\",\"monster\":\"Kraken\",\"creatureId\":42,\"kills\":4,"
			+ "\"completed\":true}";
		LedgerRecord old = gson.fromJson(json, LedgerRecord.class);
		assertNull(old.getSlayerMaster());
		assertNull(old.getSlayerArea());
		assertNull(old.getTaskLocation());
		assertNull(old.getPointsEarned());
		assertNull(old.getTaskStreak());
		assertEquals("no task context lines", "", LedgerHistory.taskContextTooltip(old));
	}

	// ---- Display ----

	@Test
	public void formatsTheContext()
	{
		assertEquals("Master: Konar", LedgerHistory.formatMaster(SlayerMaster.KONAR));
		assertEquals("Unknown master (14)", LedgerHistory.formatMaster(14));
		assertEquals("Master: -", LedgerHistory.formatMaster(null));
		assertEquals("Master: -", LedgerHistory.formatMaster(SlayerMaster.NONE));
		assertEquals("Type: Konar (Kraken Cove)", LedgerHistory.formatTaskType(TaskType.KONAR, "Kraken Cove"));
		assertEquals("Type: Boss", LedgerHistory.formatTaskType(TaskType.BOSS, null));
		assertEquals("Type: -", LedgerHistory.formatTaskType(null, null));
		assertEquals("Points earned: 18", LedgerHistory.formatPointsEarned(18));
		assertEquals("Points earned: -", LedgerHistory.formatPointsEarned(null));
		assertEquals("Task streak: 43", LedgerHistory.formatTaskStreak(43));
	}

	@Test
	public void historyTooltipListsTheKnownParts()
	{
		LedgerRecord r = new LedgerRecord();
		r.setCreatureId(TASK);
		r.setSlayerMaster(SlayerMaster.KONAR);
		r.setTaskLocation("Kraken <Cove>");
		r.setPointsEarned(18);
		r.setTaskStreak(43);
		assertEquals("<br>Master: Konar<br>Type: Konar (Kraken &lt;Cove&gt;)<br>Points earned: 18<br>Task streak: 43",
			LedgerHistory.taskContextTooltip(r));
		String tooltip = LedgerHistory.rowTooltip(r, HistoryPricing.value(r, LootPriceMode.GE, Collections.emptyMap()),
			null);
		assertTrue(tooltip, tooltip.endsWith("<br>Deaths: -<br>Master: Konar<br>Type: Konar (Kraken &lt;Cove&gt;)"
			+ "<br>Points earned: 18<br>Task streak: 43</html>"));

		LedgerRecord boss = new LedgerRecord();
		boss.setCreatureId(SessionTracker.creatureKey(SessionTracker.BOSS_TASK_TARGET, 3));
		assertEquals("a boss task without a known master", "<br>Type: Boss", LedgerHistory.taskContextTooltip(boss));
	}
}
