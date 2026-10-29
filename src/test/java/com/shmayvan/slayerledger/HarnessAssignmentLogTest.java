package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * The skip and block log as wired in the plugin ({@link PluginHarness}): assignments that end without a session are
 * saved beside the records with their outcome, never for a played task, never from a login resync or a hop, bounded,
 * deletable, shown only in the collapsed "Skipped / blocked" section and never counted as played tasks.
 * {@link AssignmentWatchTest}, {@link AssignmentOutcomeTest} and {@link AssignmentLogTest} cover the rules.
 */
public class HarnessAssignmentLogTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int DEMONS = 42;
	private static final int CAVE_HORRORS = 80;
	private static final int DURADEL = 5;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.startUp();
		harness.setTask(DEMONS, "Abyssal demons", 120, 120);
		harness.setAssignment(DURADEL, 0, null);
		harness.setSlayerPoints(500);
		harness.setVarbit(VarbitID.SLAYER_TASKS_COMPLETED, 10);
		harness.loginAndSettle(ACCOUNT);
	}

	/** Ticks out the decision window after an end. */
	private void settle()
	{
		harness.ticks(AssignmentWatch.END_WINDOW_TICKS + 1);
	}

	private void skip(int cost)
	{
		harness.setRemaining(0);
		harness.setSlayerPoints(500 - cost);
	}

	private AssignmentEntry onlyEntry()
	{
		final List<AssignmentEntry> entries = harness.assignments(ACCOUNT);
		assertEquals(entries.toString(), 1, entries.size());
		return entries.get(0);
	}

	private <T> T onEdt(java.util.function.Supplier<T> read)
	{
		final List<T> result = new ArrayList<>(1);
		harness.onEdt(() -> result.add(read.get()));
		return result.get(0);
	}

	private List<String> assignmentRows()
	{
		return onEdt(() -> harness.ui().getAssignments().getRows());
	}

	@Test
	public void aSkipIsSavedAndShownCollapsed()
	{
		skip(30);
		settle();
		AssignmentEntry e = onlyEntry();
		assertEquals(AssignmentOutcome.SKIPPED, e.getOutcome());
		assertEquals(Integer.valueOf(-30), e.getPointsChange());
		assertEquals("Abyssal demons", e.getMonster());
		assertEquals(Integer.valueOf(DEMONS), e.getCreatureId());
		assertEquals(Integer.valueOf(120), e.getOriginalAmount());
		assertEquals(Integer.valueOf(DURADEL), e.getSlayerMaster());
		assertTrue(e.getEndedAt() > 0);
		assertTrue("not a played task", harness.records(ACCOUNT).isEmpty());

		assertEquals("collapsed by default", "Skipped / blocked (1) | Show",
			onEdt(() -> harness.ui().getAssignments().getHeaderText()));
		assertFalse(onEdt(() -> harness.ui().getAssignments().isExpanded()));
		assertTrue(assignmentRows().isEmpty());

		harness.onEdt(() -> harness.ui().getAssignments().toggle());
		assertTrue(onEdt(() -> harness.ui().getAssignments().isExpanded()));
		List<String> rows = assignmentRows();
		assertEquals(1, rows.size());
		assertTrue(rows.get(0), rows.get(0).startsWith("Abyssal demons | Skipped \u00b7 "));
		assertTrue(rows.get(0), rows.get(0).contains("<br>Master: Duradel/Kuradal<br>Type: Regular<br>Assigned: 120"
			+ "<br>Points: -30</html>"));
		assertEquals("Saved tasks: 0", onEdt(() -> harness.ui().getSavedTasksText()));
		assertEquals(Arrays.asList(LedgerHistory.ALL), onEdt(() -> harness.ui().getHistory().getFilterOptions()));
		assertTrue(onEdt(() -> harness.ui().getHistory().getValuesText()).isEmpty());
	}

	@Test
	public void aBlockIsSaved()
	{
		skip(100);
		harness.setVarbit(VarbitID.SLAYER_BLOCKED_DURADEL_1, DEMONS);
		settle();
		assertEquals(AssignmentOutcome.BLOCKED, onlyEntry().getOutcome());
		assertEquals(Integer.valueOf(-100), onlyEntry().getPointsChange());
	}

	@Test
	public void aBlockSeenInAnOldBlockedVarpIsSaved()
	{
		skip(100);
		harness.setVarp(VarPlayerID.SLAYER_REWARDS_BLOCKED_2, DEMONS);
		settle();
		assertEquals(AssignmentOutcome.BLOCKED, onlyEntry().getOutcome());
	}

	@Test
	public void aTuraelResetIsSaved()
	{
		harness.setTask(6, "Cows", 20, 20);
		harness.setAssignment(1, 0, null);
		harness.setVarbit(VarbitID.SLAYER_TASKS_COMPLETED, 0);
		settle();
		AssignmentEntry e = onlyEntry();
		assertEquals(AssignmentOutcome.RESET, e.getOutcome());
		assertEquals("Abyssal demons", e.getMonster());
		assertEquals("the old task's master", Integer.valueOf(DURADEL), e.getSlayerMaster());
		assertEquals(Integer.valueOf(0), e.getPointsChange());
	}

	@Test
	public void aCompletionWithoutASessionIsSaved()
	{
		harness.completeTask(15);
		settle();
		AssignmentEntry e = onlyEntry();
		assertEquals(AssignmentOutcome.COMPLETED, e.getOutcome());
		assertEquals(Integer.valueOf(15), e.getPointsChange());
		assertTrue(harness.records(ACCOUNT).isEmpty());
	}

	@Test
	public void anUnexplainedEndIsSavedAsUnknownWhenTheNextTaskAppears()
	{
		harness.setRemaining(0);
		settle();
		harness.ticks(10);
		assertTrue("waits for the next task", harness.assignments(ACCOUNT).isEmpty());
		harness.setTask(CAVE_HORRORS, "Cave horrors", 150, 150);
		harness.tick();
		assertEquals(AssignmentOutcome.UNKNOWN, onlyEntry().getOutcome());
	}

	@Test
	public void aCompletedPlayedTaskGetsNoEntry()
	{
		assertTrue(harness.play());
		harness.setRemaining(119);
		harness.tick();
		harness.completeTask(15);
		settle();
		assertTrue(harness.onlyRecord(ACCOUNT).isCompleted());
		assertTrue(harness.assignments(ACCOUNT).isEmpty());
	}

	@Test
	public void aPartialPlayedTaskGetsNoEntryWhenSkippedLater()
	{
		assertTrue(harness.play());
		harness.setRemaining(100);
		harness.tick();
		harness.stop();
		assertTrue(harness.onlyRecord(ACCOUNT).isPartial());
		harness.ticks(5);
		skip(30);
		settle();
		assertTrue(harness.assignments(ACCOUNT).isEmpty());
	}

	@Test
	public void aRunningSessionsTaskSkippedGetsNoEntry()
	{
		assertTrue(harness.play());
		harness.tick();
		skip(30);
		settle();
		assertTrue(harness.assignments(ACCOUNT).isEmpty());
	}

	@Test
	public void aTaskChangedDuringASessionGetsNoEntry()
	{
		assertTrue(harness.play());
		harness.tick();
		harness.setTask(6, "Cows", 20, 20);
		harness.setVarbit(VarbitID.SLAYER_TASKS_COMPLETED, 0);
		settle();
		assertTrue("the session was saved as partial", harness.onlyRecord(ACCOUNT).isPartial());
		assertTrue(harness.assignments(ACCOUNT).isEmpty());
	}

	@Test
	public void aTaskPlayedInAnEarlierRunGetsNoEntry()
	{
		assertTrue(harness.play());
		harness.setRemaining(100);
		harness.tick();
		harness.stop();
		// The client is restarted: the plugin forgets what it watched.
		harness.shutDown();
		harness.startUp();
		harness.ticks(PluginHarness.SETTLE_TICKS);
		skip(30);
		settle();
		assertTrue(harness.assignments(ACCOUNT).isEmpty());
	}

	@Test
	public void anUnplayedTaskFirstSeenAtAStartIsStillSaved()
	{
		harness.shutDown();
		harness.startUp();
		harness.ticks(PluginHarness.SETTLE_TICKS);
		skip(30);
		settle();
		assertEquals(AssignmentOutcome.SKIPPED, onlyEntry().getOutcome());
	}

	@Test
	public void aLoginResyncMakesNoEntry()
	{
		harness.logout();
		// Skipped and reassigned on another client while logged out here.
		harness.setTask(CAVE_HORRORS, "Cave horrors", 150, 150);
		harness.setSlayerPoints(470);
		harness.loginAndSettle(ACCOUNT);
		settle();
		harness.ticks(10);
		assertTrue(harness.assignments(ACCOUNT).isEmpty());
	}

	@Test
	public void varpsSyncingAfterALoginMakeNoEntry()
	{
		harness.logout();
		harness.setRemaining(0);
		harness.setSlayerPoints(0);
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(3);
		harness.setRemaining(120);
		harness.setSlayerPoints(500);
		settle();
		harness.ticks(10);
		assertTrue(harness.assignments(ACCOUNT).isEmpty());
	}

	@Test
	public void aHopMakesNoEntry()
	{
		harness.hop();
		harness.setRemaining(0);
		harness.setSlayerPoints(0);
		harness.ticks(PluginHarness.SETTLE_TICKS + 2);
		harness.setRemaining(120);
		harness.setSlayerPoints(500);
		settle();
		harness.ticks(10);
		assertTrue(harness.assignments(ACCOUNT).isEmpty());
	}

	@Test
	public void aSkipJustBeforeALogoutIsDropped()
	{
		skip(30);
		harness.tick();
		harness.logout();
		harness.loginAndSettle(ACCOUNT);
		settle();
		harness.ticks(10);
		assertTrue("an undecided end is dropped, not guessed", harness.assignments(ACCOUNT).isEmpty());
	}

	@Test
	public void theNewestEntriesAreKept() throws IOException
	{
		harness.logout();
		final StringBuilder json = new StringBuilder("{\"schemaVersion\":2,\"records\":[],\"assignments\":[");
		for (int i = 0; i < AssignmentLog.MAX_ENTRIES; i++)
		{
			json.append(i == 0 ? "" : ",").append("{\"id\":\"old").append(i).append("\",\"monster\":\"Cows\","
				+ "\"endedAt\":").append(1_000 + i).append(",\"outcome\":\"SKIPPED\"}");
		}
		json.append("]}");
		writeLedger(json.toString());
		harness.loginAndSettle(ACCOUNT);
		assertEquals("Skipped / blocked (500) | Show", onEdt(() -> harness.ui().getAssignments().getHeaderText()));

		skip(30);
		settle();
		final List<AssignmentEntry> entries = harness.assignments(ACCOUNT);
		assertEquals(AssignmentLog.MAX_ENTRIES, entries.size());
		assertEquals("the oldest went", "old1", entries.get(0).getId());
		assertEquals("Abyssal demons", entries.get(entries.size() - 1).getMonster());
		assertEquals("Skipped / blocked (500) | Show", onEdt(() -> harness.ui().getAssignments().getHeaderText()));
	}

	@Test
	public void anEntryCanBeDeleted()
	{
		skip(30);
		settle();
		final String id = onlyEntry().getId();
		harness.deleteAssignment(ACCOUNT, id);
		assertTrue(harness.assignments(ACCOUNT).isEmpty());
		assertEquals("Skipped / blocked (0) | Show", onEdt(() -> harness.ui().getAssignments().getHeaderText()));
		harness.onEdt(() -> harness.ui().getAssignments().toggle());
		assertTrue(assignmentRows().isEmpty());
	}

	@Test
	public void deletingAnEntryKeepsTheRecords()
	{
		assertTrue(harness.play());
		harness.setRemaining(100);
		harness.tick();
		harness.stop();
		harness.setTask(CAVE_HORRORS, "Cave horrors", 150, 150);
		harness.ticks(2);
		skip(30);
		settle();
		harness.deleteAssignment(ACCOUNT, onlyEntry().getId());
		assertEquals(1, harness.records(ACCOUNT).size());
		assertEquals("Saved tasks: 1", onEdt(() -> harness.ui().getSavedTasksText()));
	}

	@Test
	public void anOldLedgerFileLoadsUnchanged() throws IOException
	{
		harness.logout();
		final String old = "{\"schemaVersion\":2,\"records\":[{\"id\":\"r1\",\"monster\":\"Kraken\",\"creatureId\":"
			+ DEMONS + ",\"initialAmount\":3,\"kills\":3,\"activeMillis\":60000,\"startedAt\":1000,\"endedAt\":61000,"
			+ "\"completed\":true,\"partial\":false}]}";
		writeLedger(old);
		harness.loginAndSettle(ACCOUNT);
		assertEquals("Saved tasks: 1", onEdt(() -> harness.ui().getSavedTasksText()));
		assertEquals("Skipped / blocked (0) | Show", onEdt(() -> harness.ui().getAssignments().getHeaderText()));
		assertEquals("loading never rewrites the file", old, readLedger());

		skip(30);
		settle();
		assertEquals(1, harness.records(ACCOUNT).size());
		assertEquals("Kraken", harness.onlyRecord(ACCOUNT).getMonster());
		assertEquals(1, harness.assignments(ACCOUNT).size());
	}

	@Test
	public void anotherAccountsTaskIsNotCarriedOver()
	{
		harness.logout();
		harness.setSlayerPoints(470);
		harness.setRemaining(0);
		harness.loginAndSettle(PluginHarness.OTHER_ACCOUNT);
		settle();
		harness.ticks(10);
		assertTrue(harness.assignments(ACCOUNT).isEmpty());
		assertTrue(harness.assignments(PluginHarness.OTHER_ACCOUNT).isEmpty());
	}

	private Path ledgerPath()
	{
		return harness.ledgerDirectory().toPath().resolve(ACCOUNT + ".json");
	}

	private void writeLedger(String json) throws IOException
	{
		Files.write(ledgerPath(), json.getBytes(StandardCharsets.UTF_8));
	}

	private String readLedger() throws IOException
	{
		return new String(Files.readAllBytes(ledgerPath()), StandardCharsets.UTF_8);
	}
}
