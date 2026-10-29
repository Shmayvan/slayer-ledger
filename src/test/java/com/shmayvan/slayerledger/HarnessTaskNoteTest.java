package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Task notes as wired in the plugin ({@link PluginHarness}): the panel's note box sets the live session's note on
 * the EDT, the note is saved on the record, kept across logout and a restart, and a saved record's note is rewritten
 * on the executor. {@link TaskNoteTest} and {@link SessionTrackerNoteTest} cover the rules.
 */
public class HarnessTaskNoteTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int TASK = 42;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.startUp();
		harness.setInventory();
		harness.setTask(TASK, "Wyrms", 10, 10);
		harness.loginAndSettle(ACCOUNT);
	}

	/** Types a note in the panel's note box and commits it (Enter), on the EDT. */
	private void typeNote(String text)
	{
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			harness.ui().typeNote(text);
		});
		harness.drain();
	}

	/** The note box's text after a refresh, or {@code null} while it is hidden. */
	private String noteBox()
	{
		final String[] text = new String[1];
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			text[0] = harness.ui().isNoteShown() ? harness.ui().getNoteText() : null;
		});
		return text[0];
	}

	/** The panel's list of recent notes. */
	private List<String> recentNotes()
	{
		final AtomicReference<List<String>> recent = new AtomicReference<>();
		harness.onEdt(() -> recent.set(harness.ui().getRecentNotes()));
		return recent.get();
	}

	@Test
	public void noteSetWhileRunningIsSavedOnTheRecord()
	{
		assertNull("hidden without a session", noteBox());
		assertTrue(harness.play());
		assertEquals("", noteBox());
		typeNote("  Barrage   and  burst ");
		assertEquals("Barrage and burst", harness.tracker().getNote());
		assertEquals("shown as saved", "Barrage and burst", noteBox());

		harness.pause();
		typeNote("Prayer gear");
		assertEquals("Prayer gear", noteBox());
		harness.stop();
		assertNull("hidden once the session ended", noteBox());

		assertEquals("Prayer gear", harness.onlyRecord(ACCOUNT).getNote());
		assertEquals("offered first next time", "Prayer gear", recentNotes().get(0));

		assertTrue(harness.play());
		assertEquals("a new session starts without a note", "", noteBox());
	}

	@Test
	public void clearingTheNoteSavesNone()
	{
		assertTrue(harness.play());
		typeNote("Cannon");
		typeNote("   ");
		assertNull(harness.tracker().getNote());
		harness.stop();
		assertNull(harness.onlyRecord(ACCOUNT).getNote());
	}

	@Test
	public void noteSurvivesLogoutAndARestart()
	{
		assertTrue(harness.play());
		typeNote("Range pray");
		harness.logout();
		SessionSnapshot snapshot = harness.activeSession(ACCOUNT);
		assertNotNull(snapshot);
		assertEquals("Range pray", snapshot.getNote());
		harness.shutDown();

		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		assertEquals("restored as paused", SessionTracker.State.PAUSED, harness.sessionState());
		assertEquals("Range pray", harness.tracker().getNote());
		assertEquals("Range pray", noteBox());
		assertTrue(harness.play());
		harness.stop();
		assertEquals("Range pray", harness.onlyRecord(ACCOUNT).getNote());
	}

	@Test
	public void noteKeptInMemoryAcrossARelogin()
	{
		assertTrue(harness.play());
		typeNote("Strength gear");
		harness.logout();
		harness.loginAndSettle(ACCOUNT);
		assertEquals(SessionTracker.State.PAUSED, harness.sessionState());
		assertEquals("Strength gear", noteBox());
	}

	@Test
	public void noteSetDuringTheCompletionGraceWindowIsSaved()
	{
		assertTrue(harness.play());
		final String sessionId = harness.tracker().getSessionId();
		harness.completeTask();
		harness.tick();
		assertTrue("held for late loot", harness.tracker().hasPendingCompletion());
		harness.onEdt(() -> harness.plugin.setSessionNote(ACCOUNT, sessionId, "Barrage"));
		harness.drain();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
		assertFalse(harness.tracker().hasPendingCompletion());

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals("Barrage", record.getNote());
	}

	@Test
	public void noteSentAfterTheSessionWasSavedGoesToItsRecord()
	{
		assertTrue(harness.play());
		final String sessionId = harness.tracker().getSessionId();
		harness.completeTask();
		harness.ticks(1 + SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
		assertEquals(sessionId, harness.onlyRecord(ACCOUNT).getId());

		harness.onEdt(() -> harness.plugin.setSessionNote(ACCOUNT, sessionId, " Cannon "));
		harness.drain();
		assertEquals("Cannon", harness.onlyRecord(ACCOUNT).getNote());
	}

	@Test
	public void noteSentJustAsStopIsPressedReachesTheRecord()
	{
		assertTrue(harness.play());
		final String sessionId = harness.tracker().getSessionId();
		// Stop queues the record's save on the client thread; a note sent before that runs must still land on it.
		harness.onEdt(() ->
		{
			harness.plugin.stop();
			harness.plugin.setSessionNote(ACCOUNT, sessionId, "Barrage");
		});
		harness.drain();
		assertEquals("Barrage", harness.onlyRecord(ACCOUNT).getNote());
	}

	@Test
	public void editingASavedRecordsNoteRewritesItAndClearingRemovesIt()
	{
		assertTrue(harness.play());
		typeNote("Cannon");
		harness.stop();
		final LedgerRecord saved = harness.onlyRecord(ACCOUNT);
		assertEquals("Cannon", saved.getNote());

		harness.onEdt(() -> harness.plugin.editRecordNote(ACCOUNT, saved.getId(), "  Burst\n"));
		harness.drain();
		LedgerRecord edited = harness.onlyRecord(ACCOUNT);
		assertEquals("Burst", edited.getNote());
		saved.setNote("Burst");
		assertEquals("only the note changes", saved, edited);
		assertEquals("the panel was refreshed", "Burst", recentNotes().get(0));

		harness.onEdt(() -> harness.plugin.editRecordNote(ACCOUNT, saved.getId(), ""));
		harness.drain();
		assertNull(harness.onlyRecord(ACCOUNT).getNote());
		assertEquals(TaskNote.DEFAULTS, recentNotes());
	}

	@Test
	public void editingANoteNeverTouchesTheActiveSession()
	{
		assertTrue(harness.play());
		typeNote("Cannon");
		harness.stop();
		final String savedId = harness.onlyRecord(ACCOUNT).getId();
		assertTrue(harness.play());
		typeNote("Barrage");

		harness.onEdt(() -> harness.plugin.editRecordNote(ACCOUNT, savedId, "Burst"));
		harness.drain();
		assertEquals("Burst", harness.onlyRecord(ACCOUNT).getNote());
		assertEquals("Barrage", harness.tracker().getNote());
		assertEquals("Barrage", noteBox());
	}
}
