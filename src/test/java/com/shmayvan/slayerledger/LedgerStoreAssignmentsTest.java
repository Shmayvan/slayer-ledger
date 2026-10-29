package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The ledger file's optional {@code assignments} list ({@link AssignmentEntry}): old files load unchanged, every
 * rewrite keeps the entries, entries never come back as records, and the bound and the record check apply.
 */
public class LedgerStoreAssignmentsTest
{
	private static final long ACCOUNT = 123456789L;

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private File dir;
	private LedgerStore store;

	@Before
	public void setUp()
	{
		dir = new File(temp.getRoot(), "slayer-ledger");
		store = new LedgerStore(Filepath.Unchecked.getRooted(dir.toPath()), new Gson());
	}

	private static LedgerRecord record(String id, int creatureId, boolean completed, long endedAt)
	{
		LedgerRecord r = new LedgerRecord();
		r.setId(id);
		r.setMonster("Task " + creatureId);
		r.setCreatureId(creatureId);
		r.setOriginalAmount(120);
		r.setCompleted(completed);
		r.setPartial(!completed);
		r.setEndedAt(endedAt);
		return r;
	}

	private String json() throws IOException
	{
		return new String(Files.readAllBytes(new File(dir, ACCOUNT + ".json").toPath()), StandardCharsets.UTF_8);
	}

	private void writeJson(String json) throws IOException
	{
		dir.mkdirs();
		Files.write(new File(dir, ACCOUNT + ".json").toPath(), json.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void anOldFileLoadsWithNoEntriesAndIsRewrittenWithoutTheField() throws IOException
	{
		writeJson("{\"schemaVersion\":2,\"records\":[{\"id\":\"r1\",\"monster\":\"Kraken\",\"creatureId\":42,"
			+ "\"kills\":3,\"endedAt\":1000,\"completed\":true}]}");
		LedgerStore.Contents contents = store.loadContents(ACCOUNT);
		assertEquals(1, contents.getRecords().size());
		assertTrue(contents.getAssignments().isEmpty());

		store.append(ACCOUNT, record("r2", 46, false, 2_000L));
		assertFalse(json(), json().contains("assignments"));
		assertEquals(2, store.load(ACCOUNT).size());
	}

	@Test
	public void entriesAreStoredBesideTheRecordsAndNeverLoadAsRecords() throws IOException
	{
		store.append(ACCOUNT, record("r1", 42, true, 1_000L));
		List<AssignmentEntry> entries = store.appendAssignment(ACCOUNT, AssignmentLogTest.entry("e1", 2_000L), false);
		assertEquals(1, entries.size());
		assertEquals(1, store.load(ACCOUNT).size());
		LedgerStore.Contents contents = store.loadContents(ACCOUNT);
		assertEquals(1, contents.getRecords().size());
		assertEquals(entries, contents.getAssignments());
		assertEquals(AssignmentOutcome.SKIPPED, contents.getAssignments().get(0).getOutcome());
		assertTrue(json(), json().contains("\"outcome\":\"SKIPPED\""));
	}

	@Test
	public void appendingAnEntryWithoutAFileCreatesIt() throws IOException
	{
		store.appendAssignment(ACCOUNT, AssignmentLogTest.entry("e1", 2_000L), false);
		LedgerStore.Contents contents = store.loadContents(ACCOUNT);
		assertTrue(contents.getRecords().isEmpty());
		assertEquals(1, contents.getAssignments().size());
	}

	@Test
	public void everyRecordRewriteKeepsTheEntries() throws IOException
	{
		store.append(ACCOUNT, record("r1", 42, true, 1_000L));
		store.appendAssignment(ACCOUNT, AssignmentLogTest.entry("e1", 2_000L), false);

		store.append(ACCOUNT, record("r2", 46, true, 3_000L));
		assertEquals(1, store.loadContents(ACCOUNT).getAssignments().size());
		store.updateNote(ACCOUNT, "r2", "note");
		assertEquals(1, store.loadContents(ACCOUNT).getAssignments().size());
		store.delete(ACCOUNT, "r1");
		assertEquals(1, store.loadContents(ACCOUNT).getAssignments().size());
		assertEquals(1, store.load(ACCOUNT).size());
	}

	@Test
	public void deleteAssignmentRemovesOnlyThatEntry() throws IOException
	{
		store.append(ACCOUNT, record("r1", 42, true, 1_000L));
		store.appendAssignment(ACCOUNT, AssignmentLogTest.entry("e1", 2_000L), false);
		store.appendAssignment(ACCOUNT, AssignmentLogTest.entry("e2", 3_000L), false);

		List<AssignmentEntry> left = store.deleteAssignment(ACCOUNT, "e1");
		assertEquals(1, left.size());
		assertEquals("e2", left.get(0).getId());
		assertEquals(1, store.load(ACCOUNT).size());
		assertEquals("unknown id: nothing changes", 1, store.deleteAssignment(ACCOUNT, "nope").size());

		store.deleteAssignment(ACCOUNT, "e2");
		assertFalse("the last one gone: no empty list written", json().contains("assignments"));
	}

	@Test
	public void theNewestEntriesAreKept() throws IOException
	{
		for (int i = 0; i <= AssignmentLog.MAX_ENTRIES; i++)
		{
			store.appendAssignment(ACCOUNT, AssignmentLogTest.entry("e" + i, 1_000L + i), false);
		}
		List<AssignmentEntry> entries = store.loadContents(ACCOUNT).getAssignments();
		assertEquals(AssignmentLog.MAX_ENTRIES, entries.size());
		assertEquals("e1", entries.get(0).getId());
	}

	@Test
	public void anEntryLookingPlayedInAnEarlierRunIsSkippedWhenChecked() throws IOException
	{
		// The newest record is a partial session of the same task.
		store.append(ACCOUNT, record("r1", 42, false, 1_000L));
		assertTrue(store.appendAssignment(ACCOUNT, AssignmentLogTest.entry("e1", 2_000L), true).isEmpty());
		assertEquals("unchecked: stored", 1,
			store.appendAssignment(ACCOUNT, AssignmentLogTest.entry("e2", 2_000L), false).size());
	}

	@Test
	public void aNewerSchemaFileIsNeverOverwritten() throws IOException
	{
		writeJson("{\"schemaVersion\":99,\"records\":[]}");
		try
		{
			store.appendAssignment(ACCOUNT, AssignmentLogTest.entry("e1", 2_000L), false);
			fail();
		}
		catch (LedgerStore.UnsupportedSchemaException expected)
		{
			// The file is left alone.
		}
		try
		{
			store.deleteAssignment(ACCOUNT, "e1");
			fail();
		}
		catch (LedgerStore.UnsupportedSchemaException expected)
		{
			// Likewise.
		}
		assertEquals("{\"schemaVersion\":99,\"records\":[]}", json());
	}

	@Test
	public void anUnknownOutcomeFromANewerBuildReadsAsNull() throws IOException
	{
		writeJson("{\"schemaVersion\":2,\"records\":[],\"assignments\":[{\"id\":\"e1\",\"outcome\":\"SWAPPED\","
			+ "\"endedAt\":5}]}");
		AssignmentEntry e = store.loadContents(ACCOUNT).getAssignments().get(0);
		assertEquals(null, e.getOutcome());
		assertEquals(AssignmentOutcome.UNKNOWN, AssignmentOutcome.orUnknown(e.getOutcome()));
	}
}
