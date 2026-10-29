package com.shmayvan.slayerledger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * A task whose save fails: the panel says so, the record is kept in a side file
 * ({@code <hash>.unsaved-<id>.json}), and it is appended exactly once by the next successful save or ledger load,
 * which also clears the message.
 */
public class HarnessSaveFailedTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int TASK = 42;
	private static final String TASK_NAME = "Abyssal demons";
	private static final String NEWER_LEDGER = "{\"schemaVersion\":99,\"records\":[{\"id\":\"future\","
		+ "\"monster\":\"Hydras\",\"kills\":9,\"completed\":true}]}";
	private static final String SAME_LEDGER_AT_THIS_VERSION = "{\"schemaVersion\":2,\"records\":[{\"id\":\"future\","
		+ "\"monster\":\"Hydras\",\"kills\":9,\"completed\":true}]}";

	@Rule
	public final PluginHarness harness = new PluginHarness();

	private final boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.BONES, "Bones", 100, 1);
	}

	private <T> T onEdt(Supplier<T> read)
	{
		final List<T> result = new ArrayList<>(1);
		harness.onEdt(() -> result.add(read.get()));
		return result.get(0);
	}

	private String historyError()
	{
		return onEdt(() -> harness.ui().getHistory().getErrorText());
	}

	private String savedTasks()
	{
		return onEdt(() -> harness.ui().getSavedTasksText());
	}

	private File ledgerFile()
	{
		return new File(harness.ledgerDirectory(), ACCOUNT + ".json");
	}

	private String[] keptFiles()
	{
		final String[] names = harness.ledgerDirectory().list();
		return names == null ? new String[0]
			: Arrays.stream(names).filter(n -> n.startsWith(ACCOUNT + ".unsaved-")).toArray(String[]::new);
	}

	private static String expectedError()
	{
		return "<html>" + SlayerLedgerPanel.SAVE_FAILED_MESSAGE + "</html>";
	}

	/**
	 * Makes the ledger file unreadable, so any save fails without it being a parse error (which would move it aside):
	 * write-only where permissions are POSIX, a directory in its place on Windows (where a directory cannot be opened
	 * as a reader), as in {@code LedgerStoreTest.nonParseIoFailureDoesNotRenameFile}.
	 */
	private void blockLedger() throws IOException
	{
		final File file = ledgerFile();
		if (posix)
		{
			Files.write(file.toPath(), "ledger".getBytes(StandardCharsets.UTF_8));
			Files.setPosixFilePermissions(file.toPath(), Collections.singleton(PosixFilePermission.OWNER_WRITE));
		}
		else
		{
			assertTrue(file.mkdirs());
		}
	}

	/** Undoes {@link #blockLedger}: the ledger is missing again, so the next write creates it. */
	private void unblockLedger() throws IOException
	{
		final File file = ledgerFile();
		if (posix)
		{
			Files.setPosixFilePermissions(file.toPath(),
				EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		}
		Files.delete(file.toPath());
	}

	private void writeLedger(String json) throws IOException
	{
		assertTrue(harness.ledgerDirectory().isDirectory() || harness.ledgerDirectory().mkdirs());
		Files.write(ledgerFile().toPath(), json.getBytes(StandardCharsets.UTF_8));
	}

	private String readLedger() throws IOException
	{
		return new String(Files.readAllBytes(ledgerFile().toPath()), StandardCharsets.UTF_8);
	}

	/** Plays the task from ten left, kills them all and lets the completion save after its grace ticks. */
	private void playAndComplete()
	{
		harness.setTask(TASK, TASK_NAME, 10, 10);
		harness.ticks(PluginHarness.SETTLE_TICKS);
		assertTrue(harness.play());
		harness.setRemaining(8);
		harness.lootOnNextTick(ItemID.BONES, 1);
		harness.tick();
		harness.completeTask();
		harness.tick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
		assertEquals(SessionTracker.State.IDLE, harness.sessionState());
	}

	private void relogin()
	{
		harness.logout();
		harness.loginAndSettle(ACCOUNT);
	}

	@Test
	public void unwritableLedgerShowsTheMessageAndTheNextLoadSavesTheKeptTaskOnce() throws IOException
	{
		harness.startUp();
		harness.setTask(TASK, TASK_NAME, 10, 10);
		harness.loginAndSettle(ACCOUNT);
		assertNull(historyError());
		blockLedger();

		playAndComplete();
		assertEquals(expectedError(), historyError());
		assertEquals("the task is kept beside the ledger", 1, keptFiles().length);
		assertNull("no active session file holds it", harness.activeSession(ACCOUNT));

		unblockLedger();
		relogin();
		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals(10, record.getKills());
		assertEquals(Long.valueOf(1), record.getLoot().get(ItemID.BONES));
		assertEquals(0, keptFiles().length);
		assertNull("cleared once the task is saved", historyError());
		assertEquals("Saved tasks: 1", savedTasks());

		relogin();
		assertEquals("saved exactly once", 1, harness.records(ACCOUNT).size());
	}

	@Test
	public void nextSaveAlsoSavesTheKeptTaskOnce() throws IOException
	{
		harness.startUp();
		harness.setTask(TASK, TASK_NAME, 10, 10);
		harness.loginAndSettle(ACCOUNT);
		blockLedger();
		playAndComplete();
		assertEquals(expectedError(), historyError());
		final String[] kept = keptFiles();
		assertEquals(1, kept.length);
		final String keptName = kept[0];
		unblockLedger();

		// The next task's Stop saves it as partial, and the kept task with it.
		harness.setTask(TASK, TASK_NAME, 20, 20);
		harness.ticks(PluginHarness.SETTLE_TICKS);
		assertTrue(harness.play());
		harness.setRemaining(19);
		harness.tick();
		harness.stop();

		final List<LedgerRecord> records = harness.records(ACCOUNT);
		assertEquals(2, records.size());
		assertEquals(1, records.stream().filter(LedgerRecord::isCompleted).count());
		assertEquals(1, records.stream().filter(LedgerRecord::isPartial).count());
		assertEquals(0, keptFiles().length);
		assertNull(historyError());
		assertEquals("Saved tasks: 2", savedTasks());
		final LedgerRecord completed = records.stream().filter(LedgerRecord::isCompleted).findFirst().get();
		assertEquals("the kept task is the completed one", ACCOUNT + ".unsaved-" + completed.getId() + ".json",
			keptName);
	}

	@Test
	public void newerSchemaLedgerKeepsTheTaskWithoutRetryingForeverAndNeverChangesTheFile() throws IOException
	{
		writeLedger(NEWER_LEDGER);
		harness.startUp();
		harness.setTask(TASK, TASK_NAME, 10, 10);
		harness.loginAndSettle(ACCOUNT);
		assertEquals("read on a best-effort basis", "Saved tasks: 1", savedTasks());

		playAndComplete();
		assertEquals(expectedError(), historyError());
		assertEquals(1, keptFiles().length);
		final String[] kept = keptFiles();

		// Every load tries once more and fails the same way; drain() would fail if it kept retrying.
		relogin();
		assertEquals(expectedError(), historyError());
		assertArrayEquals(kept, keptFiles());
		assertEquals(NEWER_LEDGER, readLedger());
		harness.shutDown();
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		assertEquals(expectedError(), historyError());
		assertArrayEquals(kept, keptFiles());
		assertEquals(NEWER_LEDGER, readLedger());

		// A build that can write it again (here: the file back at this version) saves the task once.
		writeLedger(SAME_LEDGER_AT_THIS_VERSION);
		relogin();
		assertEquals(2, harness.records(ACCOUNT).size());
		assertEquals(0, keptFiles().length);
		assertNull(historyError());
		assertEquals("Saved tasks: 2", savedTasks());
	}

	@Test
	public void messageIsShownEvenWhenTheTaskCannotBeKept()
	{
		// No ledger directory at all: neither the ledger nor the side file can be written.
		harness.plugin.setLedgerDirectorySource(() ->
		{
			throw new IOException("plugin directory unavailable");
		});
		harness.startUp();
		harness.setTask(TASK, TASK_NAME, 10, 10);
		harness.loginAndSettle(ACCOUNT);
		assertNull("a failed load alone is not a failed save", historyError());

		playAndComplete();
		assertEquals(expectedError(), historyError());
		final String[] files = harness.ledgerDirectory().list();
		assertEquals("nothing written", 0, files == null ? 0 : files.length);
		assertFalse(Arrays.asList(files == null ? new String[0] : files).contains(ACCOUNT + ".json"));
	}

	@Test
	public void keptFileUsesTheStoresLayout() throws IOException
	{
		harness.startUp();
		harness.setTask(TASK, TASK_NAME, 10, 10);
		harness.loginAndSettle(ACCOUNT);
		blockLedger();
		playAndComplete();

		final String[] kept = keptFiles();
		assertEquals(1, kept.length);
		// Read back with a fresh store over the same directory, as after a client restart.
		unblockLedger();
		final LedgerStore store = new LedgerStore(Filepath.Unchecked.getRooted(harness.ledgerDirectory().toPath()),
			new com.google.gson.Gson());
		final List<LedgerRecord> saved = store.appendUnsaved(ACCOUNT);
		assertEquals(1, saved.size());
		assertEquals(TASK_NAME, saved.get(0).getMonster());
		assertEquals(ACCOUNT + ".unsaved-" + saved.get(0).getId() + ".json", kept[0]);
	}

	@Test
	public void staleActiveFileOfTheKeptTaskNeverReplacesIt() throws IOException
	{
		harness.startUp();
		harness.setTask(TASK, TASK_NAME, 10, 10);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		harness.setRemaining(8);
		harness.tick();
		// Logging out mid-task writes the active file; it stays after the next login.
		relogin();
		assertEquals(SessionTracker.State.PAUSED, harness.sessionState());
		assertNotNull("the logout's active file is still there", harness.activeSession(ACCOUNT));

		blockLedger();
		assertTrue(harness.play());
		harness.setRemaining(5);
		harness.tick();
		harness.completeTask();
		harness.tick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
		assertEquals(SessionTracker.State.IDLE, harness.sessionState());
		assertEquals(expectedError(), historyError());
		final String[] kept = keptFiles();
		assertEquals(1, kept.length);
		assertNull("the stale active file goes with the kept task", harness.activeSession(ACCOUNT));

		// Still blocked: nothing turns the old snapshot into a partial copy that replaces the completed task.
		relogin();
		assertArrayEquals(kept, keptFiles());
		assertEquals(expectedError(), historyError());

		unblockLedger();
		relogin();
		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals(10, record.getKills());
		assertEquals(0, keptFiles().length);
		assertNull(historyError());
		assertEquals("Saved tasks: 1", savedTasks());
	}

	@Test
	public void keptTasksSavedBeforeALaterOneFailsAreShown() throws IOException
	{
		harness.startUp();
		harness.setTask(TASK, TASK_NAME, 10, 10);
		harness.loginAndSettle(ACCOUNT);
		// Two kept tasks: one that saves, then one the ledger can never take (a lone surrogate the encoder refuses).
		final LedgerRecord good = new LedgerRecord();
		good.setId("a");
		good.setMonster("Gargoyles");
		good.setKills(5);
		good.setCompleted(true);
		final LedgerStore store = new LedgerStore(Filepath.Unchecked.getRooted(harness.ledgerDirectory().toPath()),
			new com.google.gson.Gson());
		store.writeUnsaved(ACCOUNT, good);
		Files.write(new File(harness.ledgerDirectory(), ACCOUNT + ".unsaved-b.json").toPath(),
			("{\"schemaVersion\":2,\"record\":{\"id\":\"b\",\"monster\":\"Bad \\ud800\",\"kills\":1,"
				+ "\"completed\":true}}").getBytes(StandardCharsets.UTF_8));

		playAndComplete();
		assertEquals(2, harness.records(ACCOUNT).size());
		assertEquals("the panel shows both saved tasks", "Saved tasks: 2", savedTasks());
		assertEquals("the bad one is still waiting", expectedError(), historyError());
		assertEquals(Collections.singletonList(ACCOUNT + ".unsaved-b.json"), Arrays.asList(keptFiles()));
	}
}
