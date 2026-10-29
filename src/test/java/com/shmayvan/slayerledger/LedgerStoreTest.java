package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LedgerStoreTest
{
	private static final long ACCOUNT_A = 123456789L;
	private static final long ACCOUNT_B = -987654321L;

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private File dir;
	private LedgerStore store;

	@Before
	public void setUp()
	{
		dir = new File(temp.getRoot(), "slayer-ledger");
		store = newStore();
	}

	/** A store over {@code dir}, as after a client restart. */
	private LedgerStore newStore()
	{
		return new LedgerStore(Filepath.Unchecked.getRooted(dir.toPath()), new Gson());
	}

	private static LedgerRecord record(String monster, int kills, boolean completed)
	{
		LedgerRecord r = new LedgerRecord();
		r.setId(monster + "-" + kills);
		r.setMonster(monster);
		r.setCreatureId(7);
		r.setInitialAmount(100);
		r.setKills(kills);
		r.setActiveMillis(60_000L);
		r.setStartedAt(1_000L);
		r.setEndedAt(61_000L);
		r.setCompleted(completed);
		r.setPartial(!completed);
		return r;
	}

	@Test
	public void missingFileLoadsEmpty() throws IOException
	{
		assertTrue(store.load(ACCOUNT_A).isEmpty());
	}

	@Test
	public void roundTrip() throws IOException
	{
		LedgerRecord a = record("Abyssal demons", 100, true);
		LedgerRecord b = record("Black dragons", 12, false);
		b.setCreatureId(null);
		store.write(ACCOUNT_A, Arrays.asList(a, b));

		List<LedgerRecord> loaded = newStore().load(ACCOUNT_A);
		assertEquals(Arrays.asList(a, b), loaded);
		assertNull(loaded.get(1).getCreatureId());
		assertTrue(loaded.get(1).isPartial());
	}

	@Test
	public void appendAddsToExisting() throws IOException
	{
		store.append(ACCOUNT_A, record("Abyssal demons", 100, true));
		List<LedgerRecord> after = store.append(ACCOUNT_A, record("Gargoyles", 5, false));
		assertEquals(2, after.size());
		assertEquals(after, store.load(ACCOUNT_A));
	}

	@Test
	public void accountsAreSeparated() throws IOException
	{
		store.append(ACCOUNT_A, record("Abyssal demons", 100, true));
		store.append(ACCOUNT_B, record("Hellhounds", 40, true));
		store.append(ACCOUNT_B, record("Kurask", 3, false));

		assertEquals(1, store.load(ACCOUNT_A).size());
		assertEquals("Abyssal demons", store.load(ACCOUNT_A).get(0).getMonster());
		assertEquals(2, store.load(ACCOUNT_B).size());
		assertTrue(new File(dir, ACCOUNT_A + ".json").isFile());
		assertTrue(new File(dir, ACCOUNT_B + ".json").isFile());
	}

	@Test
	public void atomicWriteLeavesNoTempFilesAndReplacesContent() throws IOException
	{
		store.write(ACCOUNT_A, Collections.singletonList(record("Abyssal demons", 100, true)));
		store.write(ACCOUNT_A, Collections.singletonList(record("Gargoyles", 5, false)));

		String[] names = dir.list();
		assertEquals(Collections.singletonList(ACCOUNT_A + ".json"), Arrays.asList(names));
		List<LedgerRecord> loaded = store.load(ACCOUNT_A);
		assertEquals(1, loaded.size());
		assertEquals("Gargoyles", loaded.get(0).getMonster());

		String json = new String(Files.readAllBytes(new File(dir, ACCOUNT_A + ".json").toPath()), StandardCharsets.UTF_8);
		assertTrue(json.contains("\"schemaVersion\":" + LedgerStore.FILE_SCHEMA_VERSION));
	}

	@Test
	public void corruptFileIsPreservedOnAppend() throws IOException
	{
		assertTrue(dir.mkdirs());
		File file = new File(dir, ACCOUNT_A + ".json");
		Files.write(file.toPath(), "{not json".getBytes(StandardCharsets.UTF_8));

		try
		{
			store.load(ACCOUNT_A);
			throw new AssertionError("expected IOException");
		}
		catch (IOException expected)
		{
			// expected
		}

		List<LedgerRecord> records = store.append(ACCOUNT_A, record("Abyssal demons", 1, false));
		assertEquals(1, records.size());
		String[] names = dir.list();
		assertEquals(2, names.length);
		assertTrue(Arrays.stream(names).anyMatch(n -> n.startsWith(ACCOUNT_A + ".json.corrupt-")));
	}

	@Test
	public void unknownFieldsAreIgnored() throws IOException
	{
		assertTrue(dir.mkdirs());
		String json = "{\"schemaVersion\":3,\"records\":[{\"schemaVersion\":3,\"id\":\"x\",\"monster\":\"Aberrant spectres\","
			+ "\"kills\":4,\"futureField\":[{\"itemId\":1}]}]}";
		Files.write(new File(dir, ACCOUNT_A + ".json").toPath(), json.getBytes(StandardCharsets.UTF_8));
		List<LedgerRecord> loaded = store.load(ACCOUNT_A);
		assertEquals(1, loaded.size());
		assertEquals("Aberrant spectres", loaded.get(0).getMonster());
		assertEquals(4, loaded.get(0).getKills());
	}

	@Test
	public void newerSchemaIsNeverOverwritten() throws IOException
	{
		assertTrue(dir.mkdirs());
		File file = new File(dir, ACCOUNT_A + ".json");
		String json = "{\"schemaVersion\":99,\"records\":[{\"id\":\"future\",\"monster\":\"Hydras\",\"kills\":9}]}";
		Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));

		try
		{
			store.append(ACCOUNT_A, record("Abyssal demons", 1, true));
			fail("expected UnsupportedSchemaException");
		}
		catch (LedgerStore.UnsupportedSchemaException expected)
		{
			// expected
		}
		try
		{
			store.delete(ACCOUNT_A, "future");
			fail("expected UnsupportedSchemaException");
		}
		catch (LedgerStore.UnsupportedSchemaException expected)
		{
			// expected
		}

		assertEquals(json, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
		assertEquals(Collections.singletonList(ACCOUNT_A + ".json"), Arrays.asList(dir.list()));
	}

	@Test
	public void nonParseIoFailureDoesNotRenameFile() throws IOException
	{
		boolean supportsPosix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
		File blocker = new File(dir, ACCOUNT_A + ".json");
		if (supportsPosix)
		{
			assertTrue(dir.mkdirs());
			Files.write(blocker.toPath(), "ledger".getBytes(StandardCharsets.UTF_8));
			Files.setPosixFilePermissions(blocker.toPath(), Collections.singleton(PosixFilePermission.OWNER_WRITE));
		}
		else
		{
			// On Windows, a directory cannot be opened as a reader.
			assertTrue(blocker.mkdirs());
		}

		try
		{
			store.append(ACCOUNT_A, record("Abyssal demons", 1, true));
			fail("expected IOException");
		}
		catch (LedgerStore.LedgerParseException e)
		{
			fail("not a parse error");
		}
		catch (IOException expected)
		{
			// expected
		}
		finally
		{
			if (supportsPosix)
			{
				Files.setPosixFilePermissions(blocker.toPath(),
					Collections.singleton(PosixFilePermission.OWNER_READ));
			}
		}

		if (supportsPosix)
		{
			assertTrue(blocker.isFile());
			assertEquals("ledger", new String(Files.readAllBytes(blocker.toPath()), StandardCharsets.UTF_8));
		}
		else
		{
			assertTrue(blocker.isDirectory());
		}
		assertEquals(Collections.singletonList(ACCOUNT_A + ".json"), Arrays.asList(dir.list()));
	}

	@Test
	public void deleteRemovesRecordById() throws IOException
	{
		LedgerRecord a = record("Abyssal demons", 100, true);
		LedgerRecord b = record("Gargoyles", 5, false);
		store.append(ACCOUNT_A, a);
		store.append(ACCOUNT_A, b);
		store.append(ACCOUNT_B, record("Gargoyles", 5, false));

		List<LedgerRecord> after = store.delete(ACCOUNT_A, b.getId());
		assertEquals(Collections.singletonList(a), after);
		assertEquals(Collections.singletonList(a), store.load(ACCOUNT_A));
		// other accounts are untouched, unknown ids are a no-op
		assertEquals(1, store.load(ACCOUNT_B).size());
		assertEquals(1, store.delete(ACCOUNT_A, "missing").size());
	}

	@Test
	public void deleteThenReloadFromFreshStore() throws IOException
	{
		LedgerRecord a = record("Abyssal demons", 100, true);
		LedgerRecord b = record("Gargoyles", 5, false);
		LedgerRecord c = record("Kurask", 7, false);
		store.append(ACCOUNT_A, a);
		store.append(ACCOUNT_A, b);
		store.append(ACCOUNT_A, c);

		store.delete(ACCOUNT_A, b.getId());
		// A new store (as after a client restart) reads the deletion from disk; order of the rest is kept.
		assertEquals(Arrays.asList(a, c), newStore().load(ACCOUNT_A));
		store.delete(ACCOUNT_A, a.getId());
		store.delete(ACCOUNT_A, c.getId());
		assertTrue(newStore().load(ACCOUNT_A).isEmpty());
		assertTrue(new File(dir, ACCOUNT_A + ".json").isFile());
	}

	@Test
	public void deleteLeavesActiveSessionFileUntouched() throws IOException
	{
		LedgerRecord a = record("Abyssal demons", 3, false);
		store.append(ACCOUNT_A, a);
		store.writeActive(ACCOUNT_A, snapshot("s1", 3));
		byte[] before = Files.readAllBytes(activeFile(ACCOUNT_A).toPath());

		store.delete(ACCOUNT_A, a.getId());
		// Even an id equal to the active session id only targets ledger records.
		store.delete(ACCOUNT_A, "s1");

		assertTrue(store.load(ACCOUNT_A).isEmpty());
		assertTrue(Arrays.equals(before, Files.readAllBytes(activeFile(ACCOUNT_A).toPath())));
		assertEquals("s1", store.readActive(ACCOUNT_A).getSessionId());
	}

	@Test
	public void deleteAlsoRemovesLeftoverActiveFileForSameSession() throws IOException
	{
		// Crash between appending the record and deleting the active file.
		LedgerRecord record = record("Abyssal demons", 3, false);
		record.setId("s1");
		store.append(ACCOUNT_A, record);
		store.writeActive(ACCOUNT_A, snapshot("s1", 3));
		store.writeActive(ACCOUNT_B, snapshot("s1", 3));

		assertTrue(store.delete(ACCOUNT_A, "s1").isEmpty());
		assertFalse(activeFile(ACCOUNT_A).exists());
		// the deleted task does not come back as a restored session
		assertNull(store.readActive(ACCOUNT_A));
		assertTrue(store.load(ACCOUNT_A).isEmpty());
		// other accounts' active files are untouched
		assertTrue(activeFile(ACCOUNT_B).exists());
	}

	@Test
	public void deleteKeepsActiveFileForOtherSession() throws IOException
	{
		LedgerRecord record = record("Abyssal demons", 3, false);
		record.setId("r1");
		store.append(ACCOUNT_A, record);
		store.writeActive(ACCOUNT_A, snapshot("s1", 3));

		store.delete(ACCOUNT_A, "r1");
		assertEquals("s1", store.readActive(ACCOUNT_A).getSessionId());
	}

	@Test
	public void deleteNeverRemovesUnreadableOrNewerActiveFile() throws IOException
	{
		assertTrue(dir.mkdirs());
		LedgerRecord record = record("Abyssal demons", 3, false);
		record.setId("s1");

		Files.write(activeFile(ACCOUNT_A).toPath(), "{oops".getBytes(StandardCharsets.UTF_8));
		store.append(ACCOUNT_A, record);
		assertTrue(store.delete(ACCOUNT_A, "s1").isEmpty());
		assertEquals("{oops", new String(Files.readAllBytes(activeFile(ACCOUNT_A).toPath()), StandardCharsets.UTF_8));

		String newer = "{\"schemaVersion\":99,\"sessionId\":\"s1\",\"kills\":4}";
		Files.write(activeFile(ACCOUNT_A).toPath(), newer.getBytes(StandardCharsets.UTF_8));
		store.append(ACCOUNT_A, record);
		assertTrue(store.delete(ACCOUNT_A, "s1").isEmpty());
		assertEquals(newer, new String(Files.readAllBytes(activeFile(ACCOUNT_A).toPath()), StandardCharsets.UTF_8));
	}

	@Test
	public void deleteOnMissingFileIsEmpty() throws IOException
	{
		assertTrue(store.delete(ACCOUNT_A, "x").isEmpty());
		assertFalse(new File(dir, ACCOUNT_A + ".json").exists());
	}

	@Test
	public void recordsWithoutOriginalAmountStillLoad() throws IOException
	{
		assertTrue(dir.mkdirs());
		String json = "{\"schemaVersion\":1,\"records\":[{\"schemaVersion\":1,\"id\":\"old\",\"monster\":\"Kurask\","
			+ "\"initialAmount\":50,\"kills\":50,\"completed\":true}]}";
		Files.write(new File(dir, ACCOUNT_A + ".json").toPath(), json.getBytes(StandardCharsets.UTF_8));
		LedgerRecord loaded = store.load(ACCOUNT_A).get(0);
		assertNull(loaded.getOriginalAmount());
		assertEquals(50, loaded.getInitialAmount());

		LedgerRecord withOriginal = record("Kurask", 10, true);
		withOriginal.setOriginalAmount(120);
		List<LedgerRecord> all = store.append(ACCOUNT_A, withOriginal);
		assertEquals(2, all.size());
		assertEquals(Integer.valueOf(120), store.load(ACCOUNT_A).get(1).getOriginalAmount());
	}

	// ---- Duplicate guard ----

	@Test
	public void appendSkipsRecordWithExistingId() throws IOException
	{
		LedgerRecord first = record("Abyssal demons", 10, false);
		store.append(ACCOUNT_A, first);
		LedgerRecord again = record("Abyssal demons", 10, false);
		again.setKills(99);
		List<LedgerRecord> after = store.append(ACCOUNT_A, again);
		assertEquals(1, after.size());
		assertEquals(10, store.load(ACCOUNT_A).get(0).getKills());
	}

	// ---- Active session files ----

	private static SessionSnapshot snapshot(String sessionId, int kills)
	{
		SessionSnapshot s = new SessionSnapshot();
		s.setSessionId(sessionId);
		s.setAccountHash(ACCOUNT_A);
		s.setMonster("Abyssal demons");
		s.setCreatureId(42);
		s.setInitialAmount(50);
		s.setOriginalAmount(150);
		s.setKills(kills);
		s.setActiveMillis(60_000L);
		s.setStartedAt(1_000L);
		s.setSavedAt(61_000L);
		s.setRemaining(50 - kills);
		s.setCompletionCounters(new int[]{10, 0, 0});
		return s;
	}

	private File activeFile(long account)
	{
		return new File(dir, account + ".active.json");
	}

	@Test
	public void activeMissingIsNull() throws IOException
	{
		assertNull(store.readActive(ACCOUNT_A));
		assertFalse(store.deleteActive(ACCOUNT_A, "x"));
	}

	@Test
	public void activeRoundTripAndOverwrite() throws IOException
	{
		store.writeActive(ACCOUNT_A, snapshot("s1", 3));
		assertEquals(snapshot("s1", 3), store.readActive(ACCOUNT_A));
		store.writeActive(ACCOUNT_A, snapshot("s1", 5));
		assertEquals(5, store.readActive(ACCOUNT_A).getKills());
		// atomic write leaves only the target file
		assertEquals(Collections.singletonList(ACCOUNT_A + ".active.json"), Arrays.asList(dir.list()));
		// per account
		assertNull(store.readActive(ACCOUNT_B));
		// active files do not appear as ledger records
		assertTrue(store.load(ACCOUNT_A).isEmpty());
	}

	@Test
	public void deleteActiveOnlyForMatchingSession() throws IOException
	{
		store.writeActive(ACCOUNT_A, snapshot("s1", 3));
		assertFalse(store.deleteActive(ACCOUNT_A, "other"));
		assertFalse(store.deleteActive(ACCOUNT_A, null));
		assertTrue(activeFile(ACCOUNT_A).exists());
		assertTrue(store.deleteActive(ACCOUNT_A, "s1"));
		assertFalse(activeFile(ACCOUNT_A).exists());
	}

	@Test
	public void corruptActiveFileIsMovedAsideAndIgnored() throws IOException
	{
		assertTrue(dir.mkdirs());
		Files.write(activeFile(ACCOUNT_A).toPath(), "{oops".getBytes(StandardCharsets.UTF_8));
		assertNull(store.readActive(ACCOUNT_A));
		assertFalse(activeFile(ACCOUNT_A).exists());
		assertTrue(Arrays.stream(dir.list()).anyMatch(n -> n.startsWith(ACCOUNT_A + ".active.json.corrupt-")));

		// and writing continues normally afterwards
		store.writeActive(ACCOUNT_A, snapshot("s2", 1));
		assertEquals("s2", store.readActive(ACCOUNT_A).getSessionId());
	}

	@Test
	public void corruptActiveFileIsMovedAsideOnWrite() throws IOException
	{
		assertTrue(dir.mkdirs());
		Files.write(activeFile(ACCOUNT_A).toPath(), "{oops".getBytes(StandardCharsets.UTF_8));
		assertFalse(store.deleteActive(ACCOUNT_A, "s1")); // never deletes a file it cannot read
		assertTrue(activeFile(ACCOUNT_A).exists());
		store.writeActive(ACCOUNT_A, snapshot("s1", 1));
		assertEquals("s1", store.readActive(ACCOUNT_A).getSessionId());
		assertEquals(2, dir.list().length);
	}

	@Test
	public void newerSchemaActiveFileIsNeverTouched() throws IOException
	{
		assertTrue(dir.mkdirs());
		String json = "{\"schemaVersion\":99,\"sessionId\":\"future\",\"kills\":4}";
		Files.write(activeFile(ACCOUNT_A).toPath(), json.getBytes(StandardCharsets.UTF_8));

		try
		{
			store.readActive(ACCOUNT_A);
			fail("expected UnsupportedSchemaException");
		}
		catch (LedgerStore.UnsupportedSchemaException expected)
		{
			// expected
		}
		try
		{
			store.writeActive(ACCOUNT_A, snapshot("s1", 1));
			fail("expected UnsupportedSchemaException");
		}
		catch (LedgerStore.UnsupportedSchemaException expected)
		{
			// expected
		}
		assertFalse(store.deleteActive(ACCOUNT_A, "future"));
		assertEquals(json, new String(Files.readAllBytes(activeFile(ACCOUNT_A).toPath()), StandardCharsets.UTF_8));
	}

	@Test
	public void activeSessionAlreadyInLedgerIsRemovedNotRestored() throws IOException
	{
		// Simulates a crash after the record append but before the active file was deleted.
		store.writeActive(ACCOUNT_A, snapshot("s1", 3));
		LedgerRecord record = record("Abyssal demons", 3, false);
		record.setId("s1");
		store.append(ACCOUNT_A, record);

		assertNull(store.readActive(ACCOUNT_A));
		assertFalse(activeFile(ACCOUNT_A).exists());
		// and appending the same session again does not duplicate it
		store.append(ACCOUNT_A, record);
		assertEquals(1, store.load(ACCOUNT_A).size());
	}

	@Test
	public void activeSessionNotInLedgerIsKept() throws IOException
	{
		LedgerRecord other = record("Gargoyles", 5, true);
		other.setId("other");
		store.append(ACCOUNT_A, other);
		store.writeActive(ACCOUNT_A, snapshot("s1", 3));
		assertEquals("s1", store.readActive(ACCOUNT_A).getSessionId());
		assertTrue(activeFile(ACCOUNT_A).exists());
	}

	// ---- Directory and file names ----

	@Test
	public void directoryIsResolvedOnFirstUseAndKept() throws IOException
	{
		final AtomicInteger calls = new AtomicInteger();
		final Filepath root = Filepath.Unchecked.getRooted(dir.toPath());
		LedgerStore lazy = new LedgerStore(() ->
		{
			calls.incrementAndGet();
			return root;
		}, new Gson());
		assertEquals("not resolved by the constructor", 0, calls.get());

		lazy.append(ACCOUNT_A, record("Abyssal demons", 1, true));
		lazy.writeActive(ACCOUNT_A, snapshot("s1", 1));
		assertEquals(1, lazy.load(ACCOUNT_A).size());
		assertSame(root, lazy.directory());
		assertEquals(1, calls.get());
		assertTrue(new File(dir, ACCOUNT_A + ".json").isFile());
	}

	@Test
	public void unresolvedDirectoryFailsEveryOperationAndIsRetried() throws IOException
	{
		final AtomicInteger calls = new AtomicInteger();
		final Filepath root = Filepath.Unchecked.getRooted(dir.toPath());
		LedgerStore failing = new LedgerStore(() ->
		{
			if (calls.incrementAndGet() <= 6)
			{
				throw new IOException("plugin directory unavailable");
			}
			return root;
		}, new Gson());

		assertFailsWithIo(() -> failing.load(ACCOUNT_A));
		assertFailsWithIo(() -> failing.append(ACCOUNT_A, record("Abyssal demons", 1, true)));
		assertFailsWithIo(() -> failing.delete(ACCOUNT_A, "x"));
		assertFailsWithIo(() -> failing.writeActive(ACCOUNT_A, snapshot("s1", 1)));
		assertFailsWithIo(() -> failing.readActive(ACCOUNT_A));
		assertFailsWithIo(() -> failing.deleteActive(ACCOUNT_A, "s1"));
		assertFalse("nothing was written", dir.exists());

		// The next operation asks again and works once the directory is available.
		failing.append(ACCOUNT_A, record("Abyssal demons", 1, true));
		assertEquals(1, failing.load(ACCOUNT_A).size());
		assertEquals(7, calls.get());
	}

	@Test
	public void runtimeFailureOfDirectorySourceIsAnIoException()
	{
		LedgerStore failing = new LedgerStore(() ->
		{
			throw new IllegalArgumentException("internalName must be set");
		}, new Gson());
		assertFailsWithIo(() -> failing.load(ACCOUNT_A));
		LedgerStore missing = new LedgerStore(() -> null, new Gson());
		assertFailsWithIo(() -> missing.readActive(ACCOUNT_A));
	}

	/**
	 * Filepath rejects some names (reserved characters, Windows device names, a trailing dot or space). Every name
	 * the store creates passes: ledger, active, temp and corrupt-backup files, for negative account hashes too.
	 */
	@Test
	public void fileNamesPassFilepathValidationForAnyAccountHash() throws IOException
	{
		for (long account : new long[]{Long.MIN_VALUE, -1L, 0L, ACCOUNT_B, Long.MAX_VALUE})
		{
			LedgerRecord record = record("Abyssal demons", 1, true);
			store.append(account, record);
			SessionSnapshot snapshot = snapshot("s" + account, 1);
			store.writeActive(account, snapshot);
			assertEquals(Collections.singletonList(record), store.load(account));
			assertEquals(snapshot, store.readActive(account));
			assertEquals(account + ".json", store.fileFor(account).getFileName());
			assertEquals(account + ".active.json", store.activeFileFor(account).getFileName());

			Files.write(activeFile(account).toPath(), "{oops".getBytes(StandardCharsets.UTF_8));
			assertNull(store.readActive(account));
			final String backupPrefix = account + ".active.json.corrupt-";
			assertTrue(Arrays.stream(dir.list()).anyMatch(n -> n.startsWith(backupPrefix)));
		}
		// Only the target files and the backups remain; no temp files.
		assertTrue(Arrays.stream(dir.list()).noneMatch(n -> n.endsWith(".tmp")));
	}

	@Test
	public void malformedUtf8IsTreatedAsUnparseable() throws IOException
	{
		assertTrue(dir.mkdirs());
		File file = new File(dir, ACCOUNT_A + ".json");
		byte[] prefix = "{\"schemaVersion\":2,\"records\":[{\"id\":\"x\",\"monster\":\"".getBytes(StandardCharsets.UTF_8);
		byte[] suffix = "\"}]}".getBytes(StandardCharsets.UTF_8);
		byte[] bytes = new byte[prefix.length + 1 + suffix.length];
		System.arraycopy(prefix, 0, bytes, 0, prefix.length);
		bytes[prefix.length] = (byte) 0xFF;
		System.arraycopy(suffix, 0, bytes, prefix.length + 1, suffix.length);
		Files.write(file.toPath(), bytes);

		try
		{
			store.load(ACCOUNT_A);
			fail("expected LedgerParseException");
		}
		catch (LedgerStore.LedgerParseException expected)
		{
			// expected: not decoded with replacement characters
		}
		// As for any unparseable file, an append keeps the original bytes in a backup instead of rewriting them.
		store.append(ACCOUNT_A, record("Abyssal demons", 1, true));
		File[] backups = dir.listFiles((d, n) -> n.startsWith(ACCOUNT_A + ".json.corrupt-"));
		assertEquals(1, backups.length);
		assertTrue(Arrays.equals(bytes, Files.readAllBytes(backups[0].toPath())));
	}

	@Test
	public void unencodableTextFailsTheWriteAndKeepsTheFile() throws IOException
	{
		store.append(ACCOUNT_A, record("Abyssal demons", 1, true));
		File file = new File(dir, ACCOUNT_A + ".json");
		byte[] before = Files.readAllBytes(file.toPath());

		try
		{
			// A lone surrogate cannot be encoded as UTF-8; a lenient writer would save it as '?'.
			store.append(ACCOUNT_A, record("Bad \uD800 name", 2, true));
			fail("expected an IOException from the strict encoder");
		}
		catch (IOException expected)
		{
			// expected
		}
		assertTrue(Arrays.equals(before, Files.readAllBytes(file.toPath())));
		assertEquals(1, store.load(ACCOUNT_A).size());
	}

	private interface IoAction
	{
		void run() throws IOException;
	}

	private static void assertFailsWithIo(IoAction action)
	{
		try
		{
			action.run();
			fail("expected IOException");
		}
		catch (IOException expected)
		{
			// expected
		}
	}

	// ---- Notes ----

	@Test
	public void updateNoteRewritesOnlyThatRecord() throws IOException
	{
		LedgerRecord a = record("Abyssal demons", 100, true);
		LedgerRecord b = record("Gargoyles", 5, false);
		store.append(ACCOUNT_A, a);
		store.append(ACCOUNT_A, b);

		List<LedgerRecord> after = store.updateNote(ACCOUNT_A, b.getId(), "Barrage");
		assertEquals("Barrage", after.get(1).getNote());
		List<LedgerRecord> loaded = newStore().load(ACCOUNT_A);
		assertNull(loaded.get(0).getNote());
		assertEquals("Barrage", loaded.get(1).getNote());
		b.setNote("Barrage");
		assertEquals("nothing else changes, order kept", Arrays.asList(a, b), loaded);

		store.updateNote(ACCOUNT_A, b.getId(), null);
		assertNull("cleared", newStore().load(ACCOUNT_A).get(1).getNote());
		String json = new String(Files.readAllBytes(new File(dir, ACCOUNT_A + ".json").toPath()), StandardCharsets.UTF_8);
		assertFalse(json, json.contains("note"));
	}

	@Test
	public void updateNoteOfAMissingRecordOrFileChangesNothing() throws IOException
	{
		assertTrue(store.updateNote(ACCOUNT_A, "missing", "Cannon").isEmpty());
		assertFalse("no file is created", new File(dir, ACCOUNT_A + ".json").exists());

		store.append(ACCOUNT_A, record("Abyssal demons", 100, true));
		File file = new File(dir, ACCOUNT_A + ".json");
		byte[] before = Files.readAllBytes(file.toPath());
		assertEquals(1, store.updateNote(ACCOUNT_A, "missing", "Cannon").size());
		assertTrue(Arrays.equals(before, Files.readAllBytes(file.toPath())));
	}

	@Test
	public void updateNoteNeverOverwritesANewerSchema() throws IOException
	{
		assertTrue(dir.mkdirs());
		File file = new File(dir, ACCOUNT_A + ".json");
		String json = "{\"schemaVersion\":99,\"records\":[{\"id\":\"future\",\"monster\":\"Hydras\",\"kills\":9}]}";
		Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
		try
		{
			store.updateNote(ACCOUNT_A, "future", "Cannon");
			fail("expected UnsupportedSchemaException");
		}
		catch (LedgerStore.UnsupportedSchemaException expected)
		{
			// expected
		}
		assertEquals(json, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
	}

	// ---- Locations ----

	@Test
	public void updateLocationRewritesOnlyThatRecordsName() throws IOException
	{
		LedgerRecord a = record("Abyssal demons", 100, true);
		LedgerRecord b = record("Dust devils", 5, true);
		b.setLocationRegion(13_878);
		b.setLocation("Smoke Dungeon");
		store.append(ACCOUNT_A, a);
		store.append(ACCOUNT_A, b);

		store.updateLocation(ACCOUNT_A, b.getId(), "Catacombs");
		List<LedgerRecord> loaded = newStore().load(ACCOUNT_A);
		assertNull(loaded.get(0).getLocation());
		b.setLocation("Catacombs");
		assertEquals("only the name changes; the region is kept", Arrays.asList(a, b), loaded);

		store.updateLocation(ACCOUNT_A, b.getId(), null);
		LedgerRecord cleared = newStore().load(ACCOUNT_A).get(1);
		assertNull(cleared.getLocation());
		assertEquals(Integer.valueOf(13_878), cleared.getLocationRegion());
	}

	@Test
	public void updateLocationOfAMissingRecordChangesNothingAndNeverOverwritesANewerSchema() throws IOException
	{
		store.append(ACCOUNT_A, record("Abyssal demons", 100, true));
		File file = new File(dir, ACCOUNT_A + ".json");
		byte[] before = Files.readAllBytes(file.toPath());
		assertEquals(1, store.updateLocation(ACCOUNT_A, "missing", "Catacombs").size());
		assertTrue(Arrays.equals(before, Files.readAllBytes(file.toPath())));

		String json = "{\"schemaVersion\":99,\"records\":[{\"id\":\"future\",\"monster\":\"Hydras\",\"kills\":9}]}";
		Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
		try
		{
			store.updateLocation(ACCOUNT_A, "future", "Catacombs");
			fail("expected UnsupportedSchemaException");
		}
		catch (LedgerStore.UnsupportedSchemaException expected)
		{
			// expected
		}
		assertEquals(json, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
	}

	// ---- Unknown fields and newer schemas ----

	private JsonObject ledgerJson() throws IOException
	{
		String json = new String(Files.readAllBytes(new File(dir, ACCOUNT_A + ".json").toPath()), StandardCharsets.UTF_8);
		return new Gson().fromJson(json, JsonObject.class);
	}

	private static JsonObject find(JsonObject file, String list, String id)
	{
		for (JsonElement e : file.getAsJsonArray(list))
		{
			if (id.equals(e.getAsJsonObject().get("id").getAsString()))
			{
				return e.getAsJsonObject();
			}
		}
		throw new AssertionError("no " + id + " in " + list);
	}

	@Test
	public void unknownFieldsSurviveEveryRewrite() throws IOException
	{
		assertTrue(dir.mkdirs());
		// As a newer build at the same schema version might write it: unknown members at every level we keep.
		String json = "{\"schemaVersion\":2,\"records\":[{\"schemaVersion\":2,\"id\":\"old\",\"monster\":\"Kurask\","
			+ "\"kills\":50,\"note\":\"Leaf-bladed\",\"futureRecordField\":[{\"itemId\":1,\"qty\":2}]}],"
			+ "\"assignments\":[{\"id\":\"e1\",\"monster\":\"Hellhounds\",\"endedAt\":5,\"futureEntryField\":7},"
			// Older than e1 but after it, so adding an entry re-sorts the list and moves e1 to a new index.
			+ "{\"id\":\"e0\",\"monster\":\"Kalphites\",\"endedAt\":1}],"
			+ "\"futureTopField\":{\"nested\":true}}";
		Files.write(new File(dir, ACCOUNT_A + ".json").toPath(), json.getBytes(StandardCharsets.UTF_8));
		final JsonElement recordField = new Gson().fromJson("[{\"itemId\":1,\"qty\":2}]", JsonElement.class);
		final JsonElement topField = new Gson().fromJson("{\"nested\":true}", JsonElement.class);

		// Append
		LedgerRecord added = record("Abyssal demons", 100, true);
		assertEquals(2, store.append(ACCOUNT_A, added).size());
		JsonObject file = ledgerJson();
		assertEquals(topField, file.get("futureTopField"));
		assertEquals(recordField, find(file, "records", "old").get("futureRecordField"));
		assertEquals(new JsonPrimitive(7), find(file, "assignments", "e1").get("futureEntryField"));
		assertFalse("a new record gets no unknown members", find(file, "records", added.getId()).has("futureRecordField"));

		// Note and location edits; clearing a known field never brings back its old value.
		store.updateNote(ACCOUNT_A, "old", null);
		store.updateLocation(ACCOUNT_A, "old", "Fremennik Slayer Dungeon");
		file = ledgerJson();
		JsonObject old = find(file, "records", "old");
		assertFalse(old.has("note"));
		assertEquals("Fremennik Slayer Dungeon", old.get("location").getAsString());
		assertEquals(recordField, old.get("futureRecordField"));
		assertEquals(topField, file.get("futureTopField"));

		// Deleting another record
		assertEquals(1, store.delete(ACCOUNT_A, added.getId()).size());
		file = ledgerJson();
		assertEquals(recordField, find(file, "records", "old").get("futureRecordField"));
		assertEquals(new JsonPrimitive(7), find(file, "assignments", "e1").get("futureEntryField"));
		assertEquals(topField, file.get("futureTopField"));

		// Assignment changes
		AssignmentEntry entry = new AssignmentEntry();
		entry.setId("e2");
		entry.setMonster("Dagannoth");
		entry.setEndedAt(10);
		List<AssignmentEntry> entries = store.appendAssignment(ACCOUNT_A, entry, false);
		assertEquals(3, entries.size());
		assertEquals("re-sorted oldest first: e1 moved", "e1", entries.get(1).getId());
		file = ledgerJson();
		assertEquals("e1", file.getAsJsonArray("assignments").get(1).getAsJsonObject().get("id").getAsString());
		assertEquals(new JsonPrimitive(7), find(file, "assignments", "e1").get("futureEntryField"));
		assertFalse("the unknown member stays on e1", find(file, "assignments", "e0").has("futureEntryField"));
		assertFalse(find(file, "assignments", "e2").has("futureEntryField"));
		assertEquals(recordField, find(file, "records", "old").get("futureRecordField"));
		assertEquals(topField, file.get("futureTopField"));
		assertEquals(2, store.deleteAssignment(ACCOUNT_A, "e2").size());
		file = ledgerJson();
		assertEquals(new JsonPrimitive(7), find(file, "assignments", "e1").get("futureEntryField"));
		assertFalse(find(file, "assignments", "e0").has("futureEntryField"));
		assertEquals(topField, file.get("futureTopField"));

		// The known fields still load as before.
		LedgerRecord loaded = newStore().load(ACCOUNT_A).get(0);
		assertEquals("Kurask", loaded.getMonster());
		assertEquals(50, loaded.getKills());
		assertEquals(LedgerStore.FILE_SCHEMA_VERSION, file.get("schemaVersion").getAsInt());
	}

	@Test
	public void explicitNullKnownFieldsStayAbsentAfterARewrite() throws IOException
	{
		assertTrue(dir.mkdirs());
		String json = "{\"schemaVersion\":2,\"records\":[{\"schemaVersion\":2,\"id\":\"old\",\"monster\":\"Kurask\","
			+ "\"kills\":50,\"note\":null,\"location\":null,\"creatureId\":null}],"
			+ "\"assignments\":[{\"id\":\"e1\",\"monster\":null,\"endedAt\":5}]}";
		Files.write(new File(dir, ACCOUNT_A + ".json").toPath(), json.getBytes(StandardCharsets.UTF_8));

		store.append(ACCOUNT_A, record("Abyssal demons", 100, true));
		JsonObject file = ledgerJson();
		JsonObject old = find(file, "records", "old");
		assertFalse(old.toString(), old.has("note"));
		assertFalse(old.has("location"));
		assertFalse(old.has("creatureId"));
		assertFalse(find(file, "assignments", "e1").has("monster"));

		// A later edit writes the known value once.
		store.updateNote(ACCOUNT_A, "old", "Leaf-bladed");
		assertEquals("Leaf-bladed", find(ledgerJson(), "records", "old").get("note").getAsString());

		// A file with an explicit null list keeps leaving it out.
		Files.write(new File(dir, ACCOUNT_A + ".json").toPath(),
			"{\"schemaVersion\":2,\"records\":[],\"assignments\":null}".getBytes(StandardCharsets.UTF_8));
		store.append(ACCOUNT_A, record("Gargoyles", 5, false));
		assertFalse(ledgerJson().has("assignments"));
	}

	@Test
	public void knownFieldsAreWrittenAsBefore() throws IOException
	{
		LedgerRecord a = record("Abyssal demons", 100, true);
		a.setLoot(Collections.singletonMap(592, 3L));
		a.setNote("Barrage");
		a.setXpGained(Collections.singletonMap("SLAYER", 15_000L));
		LedgerRecord b = record("Gargoyles", 5, false);
		b.setCreatureId(null);
		store.append(ACCOUNT_A, a);
		store.append(ACCOUNT_A, b);

		LedgerStore.LedgerFile expected = new LedgerStore.LedgerFile();
		expected.records = Arrays.asList(a, b);
		String json = new String(Files.readAllBytes(new File(dir, ACCOUNT_A + ".json").toPath()), StandardCharsets.UTF_8);
		assertEquals(new Gson().toJson(expected), json);
	}

	@Test
	public void newerSchemaWithChangedTypesIsRefusedAndNeverMovedAside() throws IOException
	{
		assertTrue(dir.mkdirs());
		File file = new File(dir, ACCOUNT_A + ".json");
		// A future layout this version cannot bind: records became an object.
		String json = "{\"schemaVersion\":3,\"records\":{\"future\":{\"monster\":\"Hydras\",\"kills\":9}}}";
		Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
		AssignmentEntry entry = new AssignmentEntry();
		entry.setId("e1");
		entry.setEndedAt(1);

		List<IoAction> actions = Arrays.asList(
			() -> store.append(ACCOUNT_A, record("Abyssal demons", 1, true)),
			() -> store.appendAssignment(ACCOUNT_A, entry, true),
			() -> store.delete(ACCOUNT_A, "future"),
			() -> store.updateNote(ACCOUNT_A, "future", "Cannon"),
			() -> store.updateLocation(ACCOUNT_A, "future", "Catacombs"),
			() -> store.deleteAssignment(ACCOUNT_A, "e1"),
			() -> store.write(ACCOUNT_A, Collections.emptyList()),
			() -> store.load(ACCOUNT_A),
			() -> store.loadContents(ACCOUNT_A));
		for (IoAction action : actions)
		{
			try
			{
				action.run();
				fail("expected UnsupportedSchemaException");
			}
			catch (LedgerStore.UnsupportedSchemaException expected)
			{
				// expected
			}
		}
		assertEquals(json, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
		assertEquals(Collections.singletonList(ACCOUNT_A + ".json"), Arrays.asList(dir.list()));
	}

	@Test
	public void newerActiveFileWithChangedTypesIsRefusedAndNeverMovedAside() throws IOException
	{
		assertTrue(dir.mkdirs());
		// A future snapshot this version cannot bind: kills became an object.
		String json = "{\"schemaVersion\":3,\"sessionId\":\"future\",\"kills\":{\"total\":4}}";
		Files.write(activeFile(ACCOUNT_A).toPath(), json.getBytes(StandardCharsets.UTF_8));

		for (IoAction action : Arrays.<IoAction>asList(
			() -> store.readActive(ACCOUNT_A),
			() -> store.writeActive(ACCOUNT_A, snapshot("s1", 1))))
		{
			try
			{
				action.run();
				fail("expected UnsupportedSchemaException");
			}
			catch (LedgerStore.UnsupportedSchemaException expected)
			{
				// expected
			}
		}
		assertFalse(store.deleteActive(ACCOUNT_A, "future"));
		assertEquals(json, new String(Files.readAllBytes(activeFile(ACCOUNT_A).toPath()), StandardCharsets.UTF_8));
		assertEquals(Collections.singletonList(ACCOUNT_A + ".active.json"), Arrays.asList(dir.list()));
	}

	@Test
	public void supportedFileWithWrongTypesIsStillMovedAsideOnAppend() throws IOException
	{
		assertTrue(dir.mkdirs());
		String json = "{\"schemaVersion\":2,\"records\":{\"x\":1}}";
		Files.write(new File(dir, ACCOUNT_A + ".json").toPath(), json.getBytes(StandardCharsets.UTF_8));
		try
		{
			store.load(ACCOUNT_A);
			fail("expected LedgerParseException");
		}
		catch (LedgerStore.LedgerParseException expected)
		{
			// expected
		}
		assertEquals(1, store.append(ACCOUNT_A, record("Abyssal demons", 1, true)).size());
		assertTrue(Arrays.stream(dir.list()).anyMatch(n -> n.startsWith(ACCOUNT_A + ".json.corrupt-")));
	}

	// ---- Kept (unsaved) records ----

	private String[] unsavedNames(long account)
	{
		final String prefix = account + ".unsaved-";
		return dir.exists() ? Arrays.stream(dir.list()).filter(n -> n.startsWith(prefix)).sorted().toArray(String[]::new)
			: new String[0];
	}

	@Test
	public void keptRecordIsAppendedOnceAndItsFileDeleted() throws IOException
	{
		LedgerRecord saved = record("Abyssal demons", 100, true);
		store.append(ACCOUNT_A, saved);
		LedgerRecord kept = record("Gargoyles", 5, true);
		kept.setId("0b6f3c1e-5d0c-4f4e-9a59-2f7d5c0d1a11");
		store.writeUnsaved(ACCOUNT_A, kept);
		assertEquals(Collections.singletonList(ACCOUNT_A + ".unsaved-0b6f3c1e-5d0c-4f4e-9a59-2f7d5c0d1a11.json"),
			Arrays.asList(unsavedNames(ACCOUNT_A)));
		assertEquals("a kept record is not in the ledger", 1, store.load(ACCOUNT_A).size());

		List<LedgerRecord> updated = newStore().appendUnsaved(ACCOUNT_A);
		assertEquals(Arrays.asList(saved, kept), updated);
		assertEquals(updated, store.load(ACCOUNT_A));
		assertEquals(0, unsavedNames(ACCOUNT_A).length);

		assertNull("nothing left to save", store.appendUnsaved(ACCOUNT_A));
		assertEquals(2, store.load(ACCOUNT_A).size());
		assertTrue(Arrays.stream(dir.list()).noneMatch(n -> n.endsWith(".tmp")));
	}

	@Test
	public void keptRecordAlreadyInTheLedgerIsNotAppendedAgain() throws IOException
	{
		LedgerRecord record = record("Abyssal demons", 100, true);
		store.writeUnsaved(ACCOUNT_A, record);
		// Saved meanwhile, as when deleting the side file failed after an earlier retry.
		store.append(ACCOUNT_A, record);

		assertEquals(Collections.singletonList(record), store.appendUnsaved(ACCOUNT_A));
		assertEquals(Collections.singletonList(record), store.load(ACCOUNT_A));
		assertEquals(0, unsavedNames(ACCOUNT_A).length);
	}

	@Test
	public void keepingTheSameRecordTwiceReplacesTheFile() throws IOException
	{
		LedgerRecord record = record("Abyssal demons", 100, true);
		store.writeUnsaved(ACCOUNT_A, record);
		record.setKills(101);
		store.writeUnsaved(ACCOUNT_A, record);
		assertEquals(1, unsavedNames(ACCOUNT_A).length);

		store.appendUnsaved(ACCOUNT_A);
		assertEquals(101, store.load(ACCOUNT_A).get(0).getKills());
		assertEquals(1, store.load(ACCOUNT_A).size());
	}

	@Test
	public void nothingKeptOrNoDirectoryYetSavesNothing() throws IOException
	{
		assertNull(store.appendUnsaved(ACCOUNT_A));
		assertFalse("never creates the directory", dir.exists());
		store.append(ACCOUNT_A, record("Abyssal demons", 1, true));
		assertNull(store.appendUnsaved(ACCOUNT_A));
	}

	@Test
	public void keptRecordsStayWhileTheLedgerHasANewerSchema() throws IOException
	{
		assertTrue(dir.mkdirs());
		File file = new File(dir, ACCOUNT_A + ".json");
		String json = "{\"schemaVersion\":99,\"records\":[{\"id\":\"future\",\"monster\":\"Hydras\",\"kills\":9}]}";
		Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
		store.writeUnsaved(ACCOUNT_A, record("Abyssal demons", 1, true));
		store.writeUnsaved(ACCOUNT_A, record("Gargoyles", 2, true));

		for (int attempt = 0; attempt < 2; attempt++)
		{
			try
			{
				store.appendUnsaved(ACCOUNT_A);
				fail("expected UnsavedRecordsException");
			}
			catch (LedgerStore.UnsavedRecordsException e)
			{
				assertTrue(e.getCause() instanceof LedgerStore.UnsupportedSchemaException);
			}
		}
		assertEquals(json, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
		assertEquals(2, unsavedNames(ACCOUNT_A).length);

		// Once the ledger can be written again (here: the newer file is gone), both are saved, once each.
		Files.delete(file.toPath());
		assertEquals(2, store.appendUnsaved(ACCOUNT_A).size());
		assertEquals(2, store.load(ACCOUNT_A).size());
		assertEquals(0, unsavedNames(ACCOUNT_A).length);
	}

	@Test
	public void corruptKeptFileIsMovedAsideAndNewerOneIsLeftAlone() throws IOException
	{
		assertTrue(dir.mkdirs());
		File corrupt = new File(dir, ACCOUNT_A + ".unsaved-a.json");
		Files.write(corrupt.toPath(), "{oops".getBytes(StandardCharsets.UTF_8));
		File empty = new File(dir, ACCOUNT_A + ".unsaved-b.json");
		Files.write(empty.toPath(), "{\"schemaVersion\":2}".getBytes(StandardCharsets.UTF_8));
		File newer = new File(dir, ACCOUNT_A + ".unsaved-c.json");
		String newerJson = "{\"schemaVersion\":99,\"record\":{\"id\":\"c\",\"kills\":\"many\"}}";
		Files.write(newer.toPath(), newerJson.getBytes(StandardCharsets.UTF_8));
		store.writeUnsaved(ACCOUNT_A, record("Abyssal demons", 1, true));

		assertEquals(1, store.appendUnsaved(ACCOUNT_A).size());
		assertFalse(corrupt.exists());
		assertFalse(empty.exists());
		assertTrue(Arrays.stream(dir.list()).anyMatch(n -> n.startsWith(ACCOUNT_A + ".unsaved-a.json.corrupt-")));
		assertTrue(Arrays.stream(dir.list()).anyMatch(n -> n.startsWith(ACCOUNT_A + ".unsaved-b.json.corrupt-")));
		assertEquals(newerJson, new String(Files.readAllBytes(newer.toPath()), StandardCharsets.UTF_8));
		assertEquals(1, store.load(ACCOUNT_A).size());

		assertNull("backups and the newer file are not retried", store.appendUnsaved(ACCOUNT_A));
		assertEquals(1, store.load(ACCOUNT_A).size());
	}

	@Test
	public void keptRecordsBelongToTheirAccount() throws IOException
	{
		store.writeUnsaved(ACCOUNT_A, record("Abyssal demons", 1, true));
		store.writeUnsaved(ACCOUNT_B, record("Hellhounds", 2, true));
		store.writeUnsaved(-ACCOUNT_A, record("Kurask", 3, true));

		assertEquals(1, store.appendUnsaved(ACCOUNT_A).size());
		assertEquals("Abyssal demons", store.load(ACCOUNT_A).get(0).getMonster());
		assertEquals(1, unsavedNames(ACCOUNT_B).length);
		assertEquals(1, unsavedNames(-ACCOUNT_A).length);
		assertTrue(store.load(ACCOUNT_B).isEmpty());
	}

	@Test
	public void savingAKeptRecordRemovesItsActiveSessionFile() throws IOException
	{
		LedgerRecord record = record("Abyssal demons", 1, true);
		store.writeActive(ACCOUNT_A, snapshot(record.getId(), 1));
		store.writeUnsaved(ACCOUNT_A, record);

		store.appendUnsaved(ACCOUNT_A);
		assertFalse(activeFile(ACCOUNT_A).exists());
		assertEquals(1, store.load(ACCOUNT_A).size());
	}

	@Test
	public void keptFileNamesPassFilepathValidationForAnyId() throws IOException
	{
		final String uuid = "0b6f3c1e-5d0c-4f4e-9a59-2f7d5c0d1a11";
		assertEquals(ACCOUNT_A + ".unsaved-" + uuid + ".json", store.unsavedFileFor(ACCOUNT_A, uuid).getFileName());
		assertEquals("the same id maps to the same file", store.unsavedFileFor(ACCOUNT_A, "a b").getFileName(),
			store.unsavedFileFor(ACCOUNT_A, "a b").getFileName());
		for (String id : new String[]{"CON", "a/b", "a:b", "x.", " ", "é", "", null})
		{
			LedgerRecord record = record("Abyssal demons", 1, true);
			record.setId(id);
			store.writeUnsaved(Long.MIN_VALUE, record);
		}
		assertEquals(8, unsavedNames(Long.MIN_VALUE).length);
		assertEquals(8, store.appendUnsaved(Long.MIN_VALUE).size());
		assertEquals(0, unsavedNames(Long.MIN_VALUE).length);
	}

	@Test
	public void unencodableKeptRecordFailsAndLeavesNoFile() throws IOException
	{
		try
		{
			store.writeUnsaved(ACCOUNT_A, record("Bad \uD800 name", 2, true));
			fail("expected an IOException from the strict encoder");
		}
		catch (IOException expected)
		{
			// expected
		}
		assertEquals(0, unsavedNames(ACCOUNT_A).length);
		assertTrue(Arrays.stream(dir.list()).noneMatch(n -> n.endsWith(".tmp")));
	}

	@Test
	public void unresolvedDirectoryFailsKeepingAndRetrying()
	{
		LedgerStore broken = new LedgerStore(() ->
		{
			throw new IOException("plugin directory unavailable");
		}, new Gson());
		assertFailsWithIo(() -> broken.writeUnsaved(ACCOUNT_A, record("Abyssal demons", 1, true)));
		try
		{
			broken.appendUnsaved(ACCOUNT_A);
			fail("expected IOException");
		}
		catch (LedgerStore.UnsavedRecordsException e)
		{
			fail("nothing is known to be kept");
		}
		catch (IOException expected)
		{
			// expected
		}
	}

	@Test
	public void activeSessionThatIsKeptIsRemovedEvenWhenTheLedgerCannotBeRead() throws IOException
	{
		LedgerRecord record = record("Abyssal demons", 10, true);
		store.writeActive(ACCOUNT_A, snapshot(record.getId(), 4));
		store.writeUnsaved(ACCOUNT_A, record);
		// A newer-schema ledger makes the ledger check fail; the kept file is checked first.
		File file = new File(dir, ACCOUNT_A + ".json");
		Files.write(file.toPath(), "{\"schemaVersion\":99,\"records\":[]}".getBytes(StandardCharsets.UTF_8));

		assertNull(store.readActive(ACCOUNT_A));
		assertFalse(activeFile(ACCOUNT_A).exists());
		assertEquals(1, unsavedNames(ACCOUNT_A).length);
	}

	@Test
	public void activeSessionOfAnotherKeptRecordIsStillRead() throws IOException
	{
		store.writeActive(ACCOUNT_A, snapshot("running", 4));
		store.writeUnsaved(ACCOUNT_A, record("Abyssal demons", 10, true));

		assertEquals("running", store.readActive(ACCOUNT_A).getSessionId());
		assertTrue(activeFile(ACCOUNT_A).exists());
	}

	@Test
	public void keptCompletedRecordIsNeverReplacedByAPartialOne() throws IOException
	{
		LedgerRecord completed = record("Abyssal demons", 10, true);
		assertTrue(store.writeUnsaved(ACCOUNT_A, completed));
		LedgerRecord partial = record("Abyssal demons", 10, false);
		partial.setKills(4);
		assertEquals(completed.getId(), partial.getId());
		assertFalse(store.writeUnsaved(ACCOUNT_A, partial));

		store.appendUnsaved(ACCOUNT_A);
		LedgerRecord saved = store.load(ACCOUNT_A).get(0);
		assertTrue(saved.isCompleted());
		assertEquals(10, saved.getKills());
		assertEquals(1, store.load(ACCOUNT_A).size());
	}

	@Test
	public void keptPartialRecordIsReplacedByTheCompletedOne() throws IOException
	{
		LedgerRecord partial = record("Abyssal demons", 10, false);
		partial.setKills(4);
		assertTrue(store.writeUnsaved(ACCOUNT_A, partial));
		assertTrue(store.writeUnsaved(ACCOUNT_A, record("Abyssal demons", 10, true)));

		store.appendUnsaved(ACCOUNT_A);
		assertTrue(store.load(ACCOUNT_A).get(0).isCompleted());
		assertEquals(10, store.load(ACCOUNT_A).get(0).getKills());
	}

	@Test
	public void deletingARecordAlsoDeletesItsLeftoverKeptFile() throws IOException
	{
		LedgerRecord record = record("Abyssal demons", 10, true);
		LedgerRecord other = record("Gargoyles", 5, true);
		store.append(ACCOUNT_A, record);
		// Left over when deleting it failed after its append.
		store.writeUnsaved(ACCOUNT_A, record);
		store.writeUnsaved(ACCOUNT_A, other);

		assertTrue(store.delete(ACCOUNT_A, record.getId()).isEmpty());
		assertEquals("only the deleted record's file goes", 1, unsavedNames(ACCOUNT_A).length);
		assertEquals(Collections.singletonList(other), store.appendUnsaved(ACCOUNT_A));
		assertEquals("the deleted task does not come back", Collections.singletonList(other),
			store.load(ACCOUNT_A));
	}

	@Test
	public void failurePartWayCarriesTheRecordsSavedBeforeIt() throws IOException
	{
		LedgerRecord saved = record("Abyssal demons", 100, true);
		store.append(ACCOUNT_A, saved);
		LedgerRecord first = record("Gargoyles", 5, true);
		first.setId("a");
		store.writeUnsaved(ACCOUNT_A, first);
		// A lone surrogate, as an escape: it reads back but the strict encoder cannot write it to the ledger.
		File bad = new File(dir, ACCOUNT_A + ".unsaved-b.json");
		Files.write(bad.toPath(), ("{\"schemaVersion\":2,\"record\":{\"id\":\"b\",\"monster\":\"Bad \\ud800\","
			+ "\"kills\":1,\"completed\":true}}").getBytes(StandardCharsets.UTF_8));

		try
		{
			store.appendUnsaved(ACCOUNT_A);
			fail("expected UnsavedRecordsException");
		}
		catch (LedgerStore.UnsavedRecordsException e)
		{
			assertEquals(Arrays.asList(saved, first), e.getRecords());
		}
		assertEquals(Arrays.asList(saved, first), store.load(ACCOUNT_A));
		assertEquals(Collections.singletonList(ACCOUNT_A + ".unsaved-b.json"), Arrays.asList(unsavedNames(ACCOUNT_A)));

		try
		{
			store.appendUnsaved(ACCOUNT_A);
			fail("expected UnsavedRecordsException");
		}
		catch (LedgerStore.UnsavedRecordsException e)
		{
			assertNull("nothing saved before the failure this time", e.getRecords());
		}
	}

	@Test
	public void newerSchemaKeptFileIsNotReplaced() throws IOException
	{
		assertTrue(dir.mkdirs());
		LedgerRecord record = record("Abyssal demons", 10, true);
		File newer = new File(dir, store.unsavedFileFor(ACCOUNT_A, record.getId()).getFileName());
		String json = "{\"schemaVersion\":99,\"record\":{\"id\":\"x\"}}";
		Files.write(newer.toPath(), json.getBytes(StandardCharsets.UTF_8));

		try
		{
			store.writeUnsaved(ACCOUNT_A, record);
			fail("expected UnsupportedSchemaException");
		}
		catch (LedgerStore.UnsupportedSchemaException expected)
		{
			// expected
		}
		assertEquals(json, new String(Files.readAllBytes(newer.toPath()), StandardCharsets.UTF_8));
	}
}
