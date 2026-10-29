package com.shmayvan.slayerledger;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Reads and writes per-account ledger files ({@code <dir>/<accountHash>.json}) and active (in-progress) session
 * files ({@code <dir>/<accountHash>.active.json}). This class performs blocking file
 * IO and must not be called on the client thread or the Swing event dispatch thread.
 *
 * <p>A record that could not be appended can be kept in a side file ({@code <dir>/<accountHash>.unsaved-<id>.json},
 * {@link #writeUnsaved}) and appended on a later try ({@link #appendUnsaved}), which deletes the side file once the
 * record is in the ledger.</p>
 *
 * <p>The ledger file also holds the assignments that ended without a saved session ({@link AssignmentEntry}), in an
 * optional {@code assignments} list beside the records. Every rewrite keeps that list as read, and a file without it
 * stays without it until an entry is added. Loading the records never returns entries: they are not played tasks.</p>
 *
 * <p>The directory comes from a {@link DirectorySource} that is asked on the first file operation (so on the same
 * thread as all other IO) and again on later operations until it succeeds; once resolved it is kept. While it cannot
 * be resolved, every operation fails with an {@link IOException}.</p>
 *
 * <p><b>Schema policy</b> (stated here only; other docs refer to it). {@link #FILE_SCHEMA_VERSION} and
 * {@link SessionSnapshot#CURRENT_SCHEMA_VERSION} are raised only for a change an older build cannot read safely: a
 * field whose type or meaning changes, or a field that is removed. A new field is added without a raise and must be
 * nullable and additive (a file without it reads as {@code null}). A build that does not know a field still keeps it
 * in the ledger file: every rewrite (append, delete, note and location edits, assignment changes) starts from the
 * file's JSON and writes back the members this build does not bind, both top-level members of the file and members of
 * each record and assignment entry that stays in the file. A member counts as unknown when it is missing after the
 * freshly bound object is serialized again, so it never replaces a known value or an edit. Members nested deeper
 * (inside a loot map, a notable drop and so on) are not kept, and the active session file is replaced as a whole by
 * each new snapshot. Do not add {@code @SerializedName} alternates to these classes without handling them here: a
 * member spelled with an alternate name would be kept as unknown and written back beside the bound one.</p>
 *
 * <p>Every read takes {@code schemaVersion} from the raw JSON before binding it. A ledger or active session file with
 * a higher version than this build supports is never written, moved aside or deleted: the write paths and
 * {@link #readActive} fail with {@link UnsupportedSchemaException}, and {@link #deleteActive} leaves it alone.
 * {@link #load} still reads a newer ledger file on a best-effort basis, and fails with
 * {@link UnsupportedSchemaException} if it does not bind. Only a file that is not valid JSON for this layout at a
 * supported version is moved aside (renamed with a {@code .corrupt-<time>} suffix) when it would be written.</p>
 */
@Slf4j
public class LedgerStore
{
	/**
	 * Version of the ledger files this build writes; see the schema policy in the class comment. Version 2 (records
	 * with loot, supplies and values) replaced 1 because builds before it rewrote the file without the fields they did
	 * not know; those builds refuse to write a version 2 file. Fields added since are additive and kept by every
	 * rewrite, so they did not raise it. Version 1 files are read and upgraded on the next write.
	 */
	static final int FILE_SCHEMA_VERSION = 2;

	/** The member holding a file's schema version, read before binding. */
	private static final String SCHEMA_VERSION = "schemaVersion";

	/** The ledger file is not valid JSON for the expected layout. */
	static class LedgerParseException extends IOException
	{
		LedgerParseException(String message, Throwable cause)
		{
			super(message, cause);
		}
	}

	/** The ledger file was written by a newer plugin version; it is never overwritten by this version. */
	static class UnsupportedSchemaException extends IOException
	{
		UnsupportedSchemaException(String message)
		{
			super(message);
		}
	}

	/**
	 * Kept records ({@link #writeUnsaved}) could not be appended by {@link #appendUnsaved}; their side files are kept
	 * for the next try. The cause is the append's failure.
	 */
	static class UnsavedRecordsException extends IOException
	{
		/** The updated record list after the kept records appended before the failure, or {@code null} if none was. */
		private final transient List<LedgerRecord> records;

		UnsavedRecordsException(String message, Exception cause, List<LedgerRecord> records)
		{
			super(message, cause);
			this.records = records;
		}

		/** The updated record list after the kept records appended before the failure, or {@code null} if none was. */
		List<LedgerRecord> getRecords()
		{
			return records;
		}
	}

	/** Suffix of the kept-record side files. */
	private static final String UNSAVED_SUFFIX = ".json";

	/** A record id usable as is in a side file name. */
	private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

	/**
	 * Supplies the ledger directory. It may do blocking IO (the plugin's source is {@code Plugin#getPluginDirectory},
	 * which can move a legacy directory), so it is only called from the store's own operations.
	 */
	@FunctionalInterface
	interface DirectorySource
	{
		Filepath get() throws IOException;
	}

	private final DirectorySource directorySource;
	private final Gson gson;
	/** The resolved directory, or {@code null} until {@link #directory()} first succeeds. Guarded by {@code this}. */
	private Filepath directory;

	public LedgerStore(Filepath directory, Gson gson)
	{
		this(() -> directory, gson);
	}

	LedgerStore(DirectorySource directorySource, Gson gson)
	{
		this.directorySource = Objects.requireNonNull(directorySource);
		this.gson = gson;
	}

	/**
	 * The ledger directory, resolved from the {@link DirectorySource} on the first call that succeeds. A failure is
	 * not remembered: the next call asks again.
	 *
	 * @throws IOException if the directory cannot be resolved (a runtime failure of the source is wrapped)
	 */
	synchronized Filepath directory() throws IOException
	{
		if (directory == null)
		{
			final Filepath resolved;
			try
			{
				resolved = directorySource.get();
			}
			catch (RuntimeException e)
			{
				throw new IOException("Unable to resolve the ledger directory", e);
			}
			if (resolved == null)
			{
				throw new IOException("No ledger directory");
			}
			directory = resolved;
		}
		return directory;
	}

	/**
	 * Loads all records for an account. A missing file yields an empty list. Files written by a newer schema are
	 * read on a best-effort basis (unknown fields are ignored); one that does not bind fails with
	 * {@link UnsupportedSchemaException}.
	 *
	 * @throws IOException if the file exists but cannot be read or parsed
	 */
	public synchronized List<LedgerRecord> load(long accountHash) throws IOException
	{
		return records(read(fileFor(accountHash), false));
	}

	/**
	 * Loads an account's records and its assignment entries with one read, like {@link #load}. A file without
	 * entries yields an empty entry list.
	 *
	 * @throws IOException if the file exists but cannot be read or parsed
	 */
	public synchronized Contents loadContents(long accountHash) throws IOException
	{
		final StoredFile file = read(fileFor(accountHash), false);
		return new Contents(records(file), assignments(file));
	}

	/**
	 * Appends a record to the account's file and returns the updated record list.
	 *
	 * <p>If the existing file is not valid JSON for this layout it is moved aside (renamed with a
	 * {@code .corrupt-<time>} suffix) rather than silently overwritten. Any other read failure, or a file with a newer
	 * schema version, fails the append without touching the file. A record whose id is already present is not
	 * appended again.</p>
	 */
	public synchronized List<LedgerRecord> append(long accountHash, LedgerRecord record) throws IOException
	{
		final StoredFile file = readForWrite(fileFor(accountHash));
		List<LedgerRecord> records = records(file);
		if (record.getId() != null && records.stream().anyMatch(r -> record.getId().equals(r.getId())))
		{
			// Already saved (e.g. a crash between appending and deleting the active-session file).
			log.debug("Ledger already contains record {}; not appending again", record.getId());
			return records;
		}
		records.add(record);
		write(accountHash, records, file);
		return records;
	}

	/**
	 * Deletes the record with the given id, if present, and returns the updated record list. Fails without
	 * changing anything if the file cannot be read or has a newer schema version.
	 *
	 * <p>When a record was removed and the account's active session file holds the same session id (left over by a
	 * crash between appending the record and deleting the active file), that file is deleted too, so the deleted
	 * task cannot come back as a restored session. An unreadable or newer-schema active file is never deleted. A kept
	 * side file for the same record ({@link #writeUnsaved}, left over when deleting it failed after its append) is
	 * deleted as well, so a later {@link #appendUnsaved} cannot bring the deleted task back.</p>
	 */
	public synchronized List<LedgerRecord> delete(long accountHash, String id) throws IOException
	{
		final StoredFile file = read(fileFor(accountHash), true);
		List<LedgerRecord> records = records(file);
		if (records.removeIf(r -> Objects.equals(r.getId(), id)))
		{
			write(accountHash, records, file);
			// Same lock (synchronized is reentrant); deleteActive leaves unreadable and newer-schema files alone.
			try
			{
				if (deleteActive(accountHash, id))
				{
					log.debug("Deleted leftover active session file for deleted record {}", id);
				}
			}
			catch (IOException e)
			{
				// The record is deleted; only a leftover active file (if any) may remain and be restored later.
				log.warn("Unable to check the active session file after deleting record {}", id, e);
			}
			if (id != null)
			{
				try
				{
					unsavedFileFor(accountHash, id).deleteIfExists();
				}
				catch (IOException e)
				{
					log.warn("Unable to delete the kept record file of deleted record {}", id, e);
				}
			}
		}
		return records;
	}

	/**
	 * Sets (or, with {@code null}, clears) the note of the record with the given id, if present, and returns the
	 * updated record list. The note is stored as given; callers normalize it ({@link TaskNote#normalize}). The file is
	 * only rewritten when the note changes. Fails without changing anything if the file cannot be read or has a newer
	 * schema version.
	 */
	public synchronized List<LedgerRecord> updateNote(long accountHash, String id, String note) throws IOException
	{
		return updateRecord(accountHash, id, r ->
		{
			if (Objects.equals(r.getNote(), note))
			{
				return false;
			}
			r.setNote(note);
			return true;
		});
	}

	/**
	 * Sets (or, with {@code null}, clears) the location name ({@link LedgerRecord#getLocation()}) of the record with the
	 * given id, if present, and returns the updated record list; its region and everything else are kept. The name is
	 * stored as given; callers normalize it ({@link TaskLocation#normalizeName}). Rewritten and failing like
	 * {@link #updateNote}.
	 */
	public synchronized List<LedgerRecord> updateLocation(long accountHash, String id, String location)
		throws IOException
	{
		return updateRecord(accountHash, id, r ->
		{
			if (Objects.equals(r.getLocation(), location))
			{
				return false;
			}
			r.setLocation(location);
			return true;
		});
	}

	/**
	 * Applies {@code change} to the record with the given id, if present (it returns whether it changed the record),
	 * and rewrites the file only when it did. Fails without changing anything if the file cannot be read or has a newer
	 * schema version.
	 */
	private List<LedgerRecord> updateRecord(long accountHash, String id, Predicate<LedgerRecord> change)
		throws IOException
	{
		final StoredFile file = read(fileFor(accountHash), true);
		List<LedgerRecord> records = records(file);
		boolean changed = false;
		for (LedgerRecord r : records)
		{
			if (id != null && id.equals(r.getId()) && change.test(r))
			{
				changed = true;
			}
		}
		if (changed)
		{
			write(accountHash, records, file);
		}
		return records;
	}

	/**
	 * Adds an assignment entry to the account's file, keeping the newest {@link AssignmentLog#MAX_ENTRIES}, and returns
	 * the updated entries. With {@code checkRecords} the entry is left out when the saved records suggest its
	 * assignment was played in an earlier run ({@link AssignmentLog#playedBefore}). Fails like {@link #append} (an
	 * unparseable file is moved aside; a newer schema is never overwritten).
	 */
	public synchronized List<AssignmentEntry> appendAssignment(long accountHash, AssignmentEntry entry,
		boolean checkRecords) throws IOException
	{
		final StoredFile file = readForWrite(fileFor(accountHash));
		final List<LedgerRecord> records = records(file);
		final List<AssignmentEntry> assignments = assignments(file);
		if (checkRecords && AssignmentLog.playedBefore(records, entry))
		{
			log.debug("Assignment {} looks played in an earlier run; not adding it", entry.getMonster());
			return assignments;
		}
		final List<AssignmentEntry> updated = AssignmentLog.add(assignments, entry);
		writeFile(accountHash, records, updated, file);
		return updated;
	}

	/**
	 * Deletes the assignment entry with the given id, if present, and returns the updated entries. Fails without
	 * changing anything if the file cannot be read or has a newer schema version. Records are never touched.
	 */
	public synchronized List<AssignmentEntry> deleteAssignment(long accountHash, String id) throws IOException
	{
		final StoredFile file = read(fileFor(accountHash), true);
		final List<AssignmentEntry> assignments = assignments(file);
		if (id != null && assignments.removeIf(e -> id.equals(e.getId())))
		{
			writeFile(accountHash, records(file), assignments, file);
		}
		return assignments;
	}

	/**
	 * Atomically replaces the account's records with the given ones, keeping the file's assignment entries (with their
	 * unknown members) and its top-level members this build does not know. Unknown record members are kept only for
	 * record objects bound by the store's own read for that rewrite; the records given here never are (this call reads
	 * the file itself), so they are written with their known fields only. Fails like {@link #delete} if the file cannot
	 * be read or has a newer schema version.
	 */
	synchronized void write(long accountHash, List<LedgerRecord> records) throws IOException
	{
		write(accountHash, records, read(fileFor(accountHash), true));
	}

	/**
	 * Atomically replaces the account's file with the given records, keeping the assignment entries and unknown
	 * members of {@code read} (the file as read for writing before the change, or {@code null}).
	 */
	private void write(long accountHash, List<LedgerRecord> records, StoredFile read) throws IOException
	{
		writeFile(accountHash, records, assignments(read), read);
	}

	/**
	 * Atomically replaces the account's file (write to a temp file, then move). An empty entry list is not written, so
	 * a file without entries keeps the layout older builds wrote. The members of {@code read} (the file as read for
	 * writing, or {@code null}) that this build does not bind are written back after the known ones: top-level members
	 * of the file, and members of each record and entry that is still in the file.
	 */
	private void writeFile(long accountHash, List<LedgerRecord> records, List<AssignmentEntry> assignments,
		StoredFile read) throws IOException
	{
		LedgerFile file = new LedgerFile();
		file.records = new ArrayList<>(records);
		file.assignments = assignments == null || assignments.isEmpty() ? null : new ArrayList<>(assignments);
		final JsonObject tree = gson.toJsonTree(file).getAsJsonObject();
		if (read != null)
		{
			addUnknownItemMembers(tree.get("records"), file.records, read.unknownItemMembers);
			addUnknownItemMembers(tree.get("assignments"), file.assignments, read.unknownItemMembers);
			addMissingMembers(tree, read.unknownMembers);
		}
		writeAtomically(fileFor(accountHash), tree);
	}

	/** Adds each item's unknown members (if any) to its object in {@code tree}, the serialized {@code items}. */
	private static void addUnknownItemMembers(JsonElement tree, List<?> items, Map<Object, JsonObject> unknown)
	{
		if (unknown.isEmpty() || items == null || tree == null || !tree.isJsonArray())
		{
			return;
		}
		final JsonArray array = tree.getAsJsonArray();
		for (int i = 0; i < Math.min(array.size(), items.size()); i++)
		{
			final JsonObject members = unknown.get(items.get(i));
			if (members != null && array.get(i).isJsonObject())
			{
				addMissingMembers(array.get(i).getAsJsonObject(), members);
			}
		}
	}

	/** Adds the members of {@code members} that {@code target} does not have, after its own. */
	private static void addMissingMembers(JsonObject target, JsonObject members)
	{
		for (Map.Entry<String, JsonElement> member : members.entrySet())
		{
			if (!target.has(member.getKey()))
			{
				target.add(member.getKey(), member.getValue());
			}
		}
	}

	private void writeAtomically(Filepath target, Object content) throws IOException
	{
		final Filepath dir = directory();
		dir.createDirectories();
		final Filepath temp = dir.createTempFile(target.getFileName(), ".tmp");
		try
		{
			// A strict encoder, as Files.newBufferedWriter uses: unencodable text fails the write instead of
			// being replaced.
			try (Writer writer = new BufferedWriter(
				new OutputStreamWriter(temp.openOutputStream(), StandardCharsets.UTF_8.newEncoder())))
			{
				gson.toJson(content, writer);
			}
			try
			{
				temp.moveTo(target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException e)
			{
				temp.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		finally
		{
			temp.deleteIfExists();
		}
	}

	/**
	 * Atomically writes the account's active (in-progress) session file. An unparseable existing file is moved
	 * aside; a file with a newer schema version is never overwritten ({@link UnsupportedSchemaException}).
	 */
	public synchronized void writeActive(long accountHash, SessionSnapshot snapshot) throws IOException
	{
		Filepath path = activeFileFor(accountHash);
		try
		{
			readActiveFile(path);
		}
		catch (LedgerParseException e)
		{
			moveAside(path, e);
		}
		writeAtomically(path, snapshot);
	}

	/**
	 * Reads the account's active session file.
	 *
	 * <ul>
	 *     <li>Missing file: {@code null}.</li>
	 *     <li>Unparseable file: moved aside, {@code null}.</li>
	 *     <li>Newer schema version: {@link UnsupportedSchemaException}; the file is left untouched.</li>
	 *     <li>A session whose id is already in the ledger (crash between append and delete), or kept in a side file
	 *     by a failed save ({@link #writeUnsaved}): the file is deleted and {@code null} is returned, so a session can
	 *     never become two records and a stale snapshot never replaces the kept record.</li>
	 * </ul>
	 */
	public synchronized SessionSnapshot readActive(long accountHash) throws IOException
	{
		Filepath path = activeFileFor(accountHash);
		SessionSnapshot snapshot;
		try
		{
			snapshot = readActiveFile(path);
		}
		catch (LedgerParseException e)
		{
			moveAside(path, e);
			return null;
		}
		if (snapshot == null)
		{
			return null;
		}
		final String sessionId = snapshot.getSessionId();
		if (sessionId != null && isKept(accountHash, sessionId))
		{
			// Checked before the ledger, which may be the very file that cannot be read.
			log.debug("Active session {} is kept as an unsaved record; removing the active file", sessionId);
			path.deleteIfExists();
			return null;
		}
		if (sessionId != null)
		{
			List<LedgerRecord> records;
			try
			{
				records = load(accountHash);
			}
			catch (IOException e)
			{
				// Cannot check for duplicates; append's id guard still prevents a second record.
				log.warn("Unable to read ledger {} while checking active session", fileFor(accountHash), e);
				return snapshot;
			}
			if (records.stream().anyMatch(r -> sessionId.equals(r.getId())))
			{
				log.debug("Active session {} is already in the ledger; removing the active file", sessionId);
				path.deleteIfExists();
				return null;
			}
		}
		return snapshot;
	}

	/**
	 * Deletes the account's active session file if it holds the given session. A file for a different session, an
	 * unparseable file, or a newer-schema file is left untouched.
	 *
	 * @return whether the file was deleted
	 */
	public synchronized boolean deleteActive(long accountHash, String sessionId) throws IOException
	{
		Filepath path = activeFileFor(accountHash);
		SessionSnapshot snapshot;
		try
		{
			snapshot = readActiveFile(path);
		}
		catch (LedgerParseException | UnsupportedSchemaException e)
		{
			return false;
		}
		if (snapshot == null || sessionId == null || !sessionId.equals(snapshot.getSessionId()))
		{
			return false;
		}
		path.deleteIfExists();
		return true;
	}

	/** The account's active session file, {@code <dir>/<accountHash>.active.json}. */
	Filepath activeFileFor(long accountHash) throws IOException
	{
		return directory().joinSegment(Long.toString(accountHash) + ".active.json");
	}

	/**
	 * Reads the active session file, or {@code null} if it does not exist.
	 *
	 * @throws UnsupportedSchemaException if its schema version is newer than supported (checked before binding)
	 * @throws LedgerParseException if it is not valid JSON for the snapshot layout
	 */
	private SessionSnapshot readActiveFile(Filepath path) throws IOException
	{
		final JsonObject raw = readJsonObject(path, "active session file");
		if (raw == null)
		{
			return null;
		}
		checkSupported(raw, SessionSnapshot.CURRENT_SCHEMA_VERSION, "Active session file " + path);
		try
		{
			return gson.fromJson(raw, SessionSnapshot.class);
		}
		catch (JsonParseException e)
		{
			throw new LedgerParseException("Unable to parse active session file " + path, e);
		}
	}

	private static void moveAside(Filepath path, Exception cause) throws IOException
	{
		Filepath backup = path.getParent().joinSegment(path.getFileName() + ".corrupt-" + System.currentTimeMillis());
		log.warn("File {} is not valid JSON for this layout; moving it to {}", path, backup, cause);
		path.moveTo(backup, StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * Keeps a record whose {@link #append} failed in its own side file ({@link #unsavedFileFor}), written atomically
	 * like the ledger, so {@link #appendUnsaved} can save it later. Writing it again for the same record id replaces
	 * the earlier copy, except that a kept completed record is never replaced by a partial one (the partial would be a
	 * stale copy of the same session). A kept file that is not valid JSON for its layout is moved aside first.
	 *
	 * @return whether the record was written ({@code false} when a kept completed record was kept instead)
	 * @throws UnsupportedSchemaException if a kept file for the same id has a newer schema version; it is left alone
	 * @throws IOException if the file cannot be written (for example the directory is unavailable or the record holds
	 * text that cannot be encoded)
	 */
	public synchronized boolean writeUnsaved(long accountHash, LedgerRecord record) throws IOException
	{
		Objects.requireNonNull(record);
		final Filepath path = unsavedFileFor(accountHash, record.getId());
		if (record.getId() != null)
		{
			try
			{
				final LedgerRecord existing = readUnsavedFile(path);
				if (existing != null && existing.isCompleted() && !record.isCompleted()
					&& record.getId().equals(existing.getId()))
				{
					log.debug("Keeping the completed record {} instead of a partial copy", record.getId());
					return false;
				}
			}
			catch (LedgerParseException e)
			{
				moveAside(path, e);
			}
		}
		final UnsavedFile file = new UnsavedFile();
		file.record = record;
		writeAtomically(path, file);
		return true;
	}

	/**
	 * Whether a record with the given id is kept in a side file ({@link #writeUnsaved}). A side file written by a newer
	 * schema counts as kept; one that is not valid JSON for its layout does not.
	 */
	private boolean isKept(long accountHash, String id) throws IOException
	{
		final Filepath path = unsavedFileFor(accountHash, id);
		try
		{
			final LedgerRecord kept = readUnsavedFile(path);
			return kept != null && id.equals(kept.getId());
		}
		catch (UnsupportedSchemaException e)
		{
			return true;
		}
		catch (LedgerParseException e)
		{
			return false;
		}
	}

	/**
	 * Appends the account's kept records ({@link #writeUnsaved}) to its ledger, oldest file name first, and deletes
	 * each side file after its record is in the ledger. A record already in the ledger is not appended again
	 * ({@link #append}'s id guard), so a retry after a failed delete saves it only once; the account's active session
	 * file for the same session is removed as {@link #append}'s callers do. A side file that is not valid JSON for its
	 * layout is moved aside; one written by a newer schema is left alone and skipped.
	 *
	 * @return the updated record list, or {@code null} if no kept record was appended
	 * @throws UnsavedRecordsException if a kept record could not be appended; it and the ones after it stay kept, and
	 * the exception carries the updated list after the ones appended before it
	 * @throws IOException if the side files cannot be listed
	 */
	public synchronized List<LedgerRecord> appendUnsaved(long accountHash) throws IOException
	{
		List<LedgerRecord> updated = null;
		for (Filepath path : unsavedFiles(accountHash))
		{
			final LedgerRecord record;
			try
			{
				record = readUnsavedFile(path);
			}
			catch (UnsupportedSchemaException e)
			{
				log.warn("Skipping kept Slayer Ledger record {} written by a newer version", path, e);
				continue;
			}
			catch (LedgerParseException e)
			{
				moveAside(path, e);
				continue;
			}
			if (record == null)
			{
				continue;
			}
			try
			{
				updated = append(accountHash, record);
			}
			catch (IOException | RuntimeException e)
			{
				throw new UnsavedRecordsException("Unable to save kept record " + path, e, updated);
			}
			log.debug("Saved kept Slayer Ledger record {}", record.getId());
			try
			{
				path.deleteIfExists();
			}
			catch (IOException e)
			{
				// The record is saved; the next retry finds it in the ledger and only deletes the file.
				log.warn("Unable to delete kept record file {} after saving it", path, e);
			}
			try
			{
				deleteActive(accountHash, record.getId());
			}
			catch (IOException e)
			{
				// readActive removes an active file whose session is already in the ledger.
				log.warn("Unable to check the active session file after saving kept record {}", record.getId(), e);
			}
		}
		return updated;
	}

	/**
	 * The account's kept-record side files ({@code <accountHash>.unsaved-<key>.json}), sorted by name; empty if there
	 * are none or the directory does not exist yet.
	 */
	List<Filepath> unsavedFiles(long accountHash) throws IOException
	{
		final Filepath dir = directory();
		if (!dir.isDirectory())
		{
			return Collections.emptyList();
		}
		final String prefix = unsavedPrefix(accountHash);
		final List<Filepath> files = new ArrayList<>();
		try (Stream<Filepath> entries = dir.walk(1))
		{
			entries.filter(p -> !p.equals(dir))
				.filter(p ->
				{
					final String name = p.getFileName();
					// Temp files (".tmp") and corrupt backups (".corrupt-<time>") end differently.
					return name.startsWith(prefix) && name.endsWith(UNSAVED_SUFFIX) && p.isFile();
				})
				.forEach(files::add);
		}
		files.sort(Comparator.comparing(Filepath::getFileName));
		return files;
	}

	/**
	 * The side file keeping a record of the account that could not be saved:
	 * {@code <dir>/<accountHash>.unsaved-<key>.json}. The key is the record id when it is a plain id (letters, digits,
	 * {@code -} and {@code _}, as the plugin's UUIDs are), otherwise a UUID derived from it (random for a record
	 * without an id), so every name passes {@code Filepath}'s checks and one record id always maps to one file.
	 */
	Filepath unsavedFileFor(long accountHash, String id) throws IOException
	{
		final String key;
		if (id != null && SAFE_ID.matcher(id).matches())
		{
			key = id;
		}
		else if (id != null)
		{
			key = UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString();
		}
		else
		{
			key = UUID.randomUUID().toString();
		}
		return directory().joinSegment(unsavedPrefix(accountHash) + key + UNSAVED_SUFFIX);
	}

	private static String unsavedPrefix(long accountHash)
	{
		return Long.toString(accountHash) + ".unsaved-";
	}

	/**
	 * Reads a kept-record side file, or {@code null} if it no longer exists.
	 *
	 * @throws UnsupportedSchemaException if its schema version is newer than supported (checked before binding)
	 * @throws LedgerParseException if it is not valid JSON for its layout or holds no record
	 */
	private LedgerRecord readUnsavedFile(Filepath path) throws IOException
	{
		final JsonObject raw = readJsonObject(path, "kept record file");
		if (raw == null)
		{
			if (path.exists())
			{
				throw new LedgerParseException("The kept record file " + path + " is empty", null);
			}
			return null;
		}
		checkSupported(raw, FILE_SCHEMA_VERSION, "Kept record file " + path);
		final UnsavedFile file;
		try
		{
			file = gson.fromJson(raw, UnsavedFile.class);
		}
		catch (JsonParseException e)
		{
			throw new LedgerParseException("Unable to parse kept record file " + path, e);
		}
		if (file == null || file.record == null)
		{
			throw new LedgerParseException("The kept record file " + path + " holds no record", null);
		}
		return file.record;
	}

	/** The account's ledger file, {@code <dir>/<accountHash>.json}. */
	Filepath fileFor(long accountHash) throws IOException
	{
		return directory().joinSegment(Long.toString(accountHash) + ".json");
	}

	/**
	 * Opens a file for reading as UTF-8 with a strict decoder, as {@code Files.newBufferedReader} does, so malformed
	 * input fails the read (and is reported as a parse error) instead of being replaced and later written back.
	 */
	private static Reader openReader(Filepath path) throws IOException
	{
		return new BufferedReader(new InputStreamReader(path.openInputStream(), StandardCharsets.UTF_8.newDecoder()));
	}

	/**
	 * Reads a file as a JSON object, without binding it.
	 *
	 * @return the object, or {@code null} if the file does not exist or holds no JSON value (or {@code null})
	 * @throws LedgerParseException if it is not valid UTF-8 JSON or not a JSON object
	 */
	private JsonObject readJsonObject(Filepath path, String what) throws IOException
	{
		if (!path.exists())
		{
			return null;
		}
		final JsonElement tree;
		try (Reader reader = openReader(path))
		{
			tree = gson.fromJson(reader, JsonElement.class);
		}
		catch (JsonParseException e)
		{
			// JsonSyntaxException is a subclass of JsonParseException; malformed UTF-8 arrives wrapped in one.
			throw new LedgerParseException("Unable to parse " + what + " " + path, e);
		}
		if (tree == null || tree.isJsonNull())
		{
			return null;
		}
		if (!tree.isJsonObject())
		{
			throw new LedgerParseException("The " + what + " " + path + " is not a JSON object", null);
		}
		return tree.getAsJsonObject();
	}

	/**
	 * Reads the ledger file for a write ({@link #read} with {@code forWrite}), moving it aside if it is not valid JSON
	 * for this layout; {@code null} is then returned, as for a missing file. A newer schema version fails with
	 * {@link UnsupportedSchemaException} and is never moved aside.
	 */
	private StoredFile readForWrite(Filepath path) throws IOException
	{
		try
		{
			return read(path, true);
		}
		catch (LedgerParseException e)
		{
			moveAside(path, e);
			return null;
		}
	}

	/**
	 * Reads and binds the ledger file. Its schema version is read before binding: for a write ({@code forWrite}) a
	 * newer version fails with {@link UnsupportedSchemaException}; otherwise a newer file is bound on a best-effort
	 * basis, and fails with {@link UnsupportedSchemaException} if it does not bind. For a write, the members this build
	 * does not bind are kept for {@link #writeFile}.
	 *
	 * @return the file, or {@code null} if it does not exist
	 * @throws LedgerParseException if a file at a supported version is not valid JSON for this layout
	 */
	private StoredFile read(Filepath path, boolean forWrite) throws IOException
	{
		final JsonObject raw = readJsonObject(path, "ledger file");
		if (raw == null)
		{
			return null;
		}
		final String description = "Ledger file " + path;
		if (forWrite)
		{
			checkSupported(raw, FILE_SCHEMA_VERSION, description);
		}
		final LedgerFile file;
		try
		{
			file = gson.fromJson(raw, LedgerFile.class);
		}
		catch (JsonParseException e)
		{
			if (isNewer(raw, FILE_SCHEMA_VERSION))
			{
				throw new UnsupportedSchemaException(description + " has schema version "
					+ raw.get(SCHEMA_VERSION).getAsString() + ", newer than supported version " + FILE_SCHEMA_VERSION
					+ ", and this version cannot read it");
			}
			throw new LedgerParseException("Unable to parse ledger file " + path, e);
		}
		if (file == null)
		{
			return null;
		}
		if (!forWrite)
		{
			return new StoredFile(file, new JsonObject());
		}
		// Computed now, before any caller edits the bound objects: a member is unknown when serializing the freshly
		// bound object does not produce it.
		final StoredFile stored = new StoredFile(file, unknownMembers(raw, file));
		collectUnknownItemMembers(raw.get("records"), file.records, stored.unknownItemMembers);
		collectUnknownItemMembers(raw.get("assignments"), file.assignments, stored.unknownItemMembers);
		return stored;
	}

	/** Whether the object's {@code schemaVersion} is a number above {@code supported}. */
	private static boolean isNewer(JsonObject raw, int supported)
	{
		final JsonElement version = raw.get(SCHEMA_VERSION);
		if (version == null || !version.isJsonPrimitive())
		{
			return false;
		}
		try
		{
			return version.getAsDouble() > supported;
		}
		catch (NumberFormatException e)
		{
			// Not a number: binding decides whether the file is usable.
			return false;
		}
	}

	/** Fails with {@link UnsupportedSchemaException} if the object's schema version is above {@code supported}. */
	private static void checkSupported(JsonObject raw, int supported, String description)
		throws UnsupportedSchemaException
	{
		if (isNewer(raw, supported))
		{
			throw new UnsupportedSchemaException(description + " has schema version "
				+ raw.get(SCHEMA_VERSION).getAsString() + ", newer than supported version " + supported
				+ "; refusing to change it");
		}
	}

	/**
	 * Keeps, for each item of {@code bound} (bound from the array {@code raw}, in the same order), the members of its
	 * JSON object that this build does not bind ({@link #unknownMembers}).
	 */
	private void collectUnknownItemMembers(JsonElement raw, List<?> bound, Map<Object, JsonObject> out)
	{
		if (raw == null || !raw.isJsonArray() || bound == null || raw.getAsJsonArray().size() != bound.size())
		{
			return;
		}
		final JsonArray array = raw.getAsJsonArray();
		for (int i = 0; i < array.size(); i++)
		{
			final Object item = bound.get(i);
			if (item != null && array.get(i).isJsonObject())
			{
				final JsonObject unknown = unknownMembers(array.get(i).getAsJsonObject(), item);
				if (unknown.size() > 0)
				{
					out.put(item, unknown);
				}
			}
		}
	}

	/**
	 * The members of {@code raw} that this build does not bind, in file order: those missing from {@code bound} (the
	 * object bound from {@code raw}, before any change) when it is serialized again. Members whose value is
	 * {@code null} are left out, so a known field written as an explicit {@code null} is not mistaken for an unknown
	 * one (Gson leaves {@code null} fields out); an unknown {@code null} reads the same as a missing member.
	 */
	private JsonObject unknownMembers(JsonObject raw, Object bound)
	{
		final JsonElement tree = gson.toJsonTree(bound);
		final Set<String> known = tree.isJsonObject() ? tree.getAsJsonObject().keySet() : Collections.emptySet();
		final JsonObject unknown = new JsonObject();
		for (Map.Entry<String, JsonElement> member : raw.entrySet())
		{
			if (!member.getValue().isJsonNull() && !known.contains(member.getKey()))
			{
				unknown.add(member.getKey(), member.getValue());
			}
		}
		return unknown;
	}

	private static List<LedgerRecord> records(StoredFile stored)
	{
		List<LedgerRecord> records = new ArrayList<>();
		final LedgerFile file = stored == null ? null : stored.file;
		if (file != null && file.records != null)
		{
			for (LedgerRecord r : file.records)
			{
				if (r != null)
				{
					records.add(r);
				}
			}
		}
		return records;
	}

	/** The file's assignment entries without nulls, in file order; empty when it has none. */
	private static List<AssignmentEntry> assignments(StoredFile stored)
	{
		List<AssignmentEntry> assignments = new ArrayList<>();
		final LedgerFile file = stored == null ? null : stored.file;
		if (file != null && file.assignments != null)
		{
			for (AssignmentEntry e : file.assignments)
			{
				if (e != null)
				{
					assignments.add(e);
				}
			}
		}
		return assignments;
	}

	/** An account's records and assignment entries, read together. */
	static final class Contents
	{
		private final List<LedgerRecord> records;
		private final List<AssignmentEntry> assignments;

		Contents(List<LedgerRecord> records, List<AssignmentEntry> assignments)
		{
			this.records = records;
			this.assignments = assignments;
		}

		/** The played tasks. */
		List<LedgerRecord> getRecords()
		{
			return records;
		}

		/** The assignments that ended without a saved session; never played tasks (see {@link AssignmentEntry}). */
		List<AssignmentEntry> getAssignments()
		{
			return assignments;
		}
	}

	/**
	 * A ledger file as read: the bound layout and, when read for a write, the JSON members this build does not bind,
	 * to be written back (see the schema policy in the class comment).
	 */
	private static final class StoredFile
	{
		final LedgerFile file;
		/** The file's top-level members this build does not bind. */
		final JsonObject unknownMembers;
		/** The members each bound record and entry has that this build does not bind, for those that have any. */
		final Map<Object, JsonObject> unknownItemMembers = new IdentityHashMap<>();

		StoredFile(LedgerFile file, JsonObject unknownMembers)
		{
			this.file = file;
			this.unknownMembers = unknownMembers;
		}
	}

	/**
	 * On-disk file layout. {@code assignments} was added to schema 2 as an optional list (see {@link AssignmentEntry}
	 * and the schema policy in the class comment); it is {@code null} (left out of the file) while there are none.
	 */
	static class LedgerFile
	{
		int schemaVersion = FILE_SCHEMA_VERSION;
		List<LedgerRecord> records = new ArrayList<>();
		List<AssignmentEntry> assignments;
	}

	/** On-disk layout of a kept-record side file ({@link #writeUnsaved}): one record, at the ledger's schema. */
	static class UnsavedFile
	{
		int schemaVersion = FILE_SCHEMA_VERSION;
		LedgerRecord record;
	}
}
