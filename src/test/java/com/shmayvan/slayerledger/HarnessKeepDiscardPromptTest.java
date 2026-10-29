package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.List;
import java.util.concurrent.Future;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * The Keep/Discard dialog that Stop shows for an unfinished task while "Confirm before saving incomplete tasks" is on
 * (the default), through the plugin's wiring ({@link PluginHarness}, with the dialog's answer scripted): Keep saves one
 * partial record, Discard saves none, and a dialog still open when the plugin is turned off or the client exits is
 * saved as partial exactly once, with the late answer saving nothing more (checked by the work it queues, as the ledger
 * also skips a record id it already holds). Each test also checks the account's
 * active-session file, which the logout before Stop has written. {@link SaveDecisionTest} covers the decision itself.
 */
public class HarnessKeepDiscardPromptTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int TASK = 42;
	private static final String TASK_NAME = "Abyssal demons";

	@Rule
	public final PluginHarness harness = new PluginHarness();

	/** The id of the session Stop ends; its active-session file was written at the logout. */
	private String sessionId;

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.BONES, "Bones", 100, 1);
		harness.startUp();
		harness.setTask(TASK, TASK_NAME, 10, 10);
		harness.loginAndSettle(ACCOUNT);

		// Two kills, then a logout and login, so the session has an active-session file when Stop is pressed.
		assertTrue(harness.play());
		harness.setRemaining(8);
		harness.lootOnNextTick(ItemID.BONES, 1);
		harness.tick();
		harness.logout();
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		harness.tick();

		final SessionSnapshot active = harness.activeSession(ACCOUNT);
		assertNotNull(active);
		sessionId = active.getSessionId();
		assertTrue(harness.records(ACCOUNT).isEmpty());
	}

	/** The account's one record: the partial session Stop ended. */
	private void assertOnePartialRecord()
	{
		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isPartial());
		assertFalse(record.isCompleted());
		assertEquals(sessionId, record.getId());
		assertEquals(TASK_NAME, record.getMonster());
		assertEquals(2, record.getKills());
		assertEquals(Long.valueOf(1), record.getLoot().get(ItemID.BONES));
	}

	@Test
	public void keepSavesOnePartialRecord()
	{
		harness.scriptKeepDiscardPrompt(PluginHarness.PromptAnswer.KEEP);
		harness.stop();

		assertEquals(1, harness.promptsShown());
		assertOnePartialRecord();
		assertNull("the active file goes once the record is saved", harness.activeSession(ACCOUNT));
		assertEquals(SessionTracker.State.IDLE, harness.sessionState());

		harness.clientShutdown();
		harness.shutDown();
		assertOnePartialRecord();
		assertNull(harness.activeSession(ACCOUNT));
	}

	@Test
	public void discardSavesNothingAndRemovesTheActiveFile()
	{
		harness.scriptKeepDiscardPrompt(PluginHarness.PromptAnswer.DISCARD);
		harness.stop();

		assertEquals(1, harness.promptsShown());
		assertTrue(harness.records(ACCOUNT).isEmpty());
		assertNull("a discarded session is not restored later", harness.activeSession(ACCOUNT));
		assertEquals(SessionTracker.State.IDLE, harness.sessionState());

		harness.clientShutdown();
		harness.shutDown();
		harness.startUp();
		harness.ticks(PluginHarness.SETTLE_TICKS);
		assertEquals("nothing restored", SessionTracker.State.IDLE, harness.sessionState());
		assertTrue(harness.records(ACCOUNT).isEmpty());
		assertNull(harness.activeSession(ACCOUNT));
	}

	@Test
	public void keepAnsweredAfterTheDialogWasOpenSavesOnce()
	{
		harness.scriptKeepDiscardPrompt(PluginHarness.PromptAnswer.LEAVE_OPEN);
		harness.stop();
		assertTrue(harness.isPromptOpen());
		assertTrue("nothing saved while the dialog is open", harness.records(ACCOUNT).isEmpty());
		assertNotNull(harness.activeSession(ACCOUNT));

		assertTrue("Keep queues the save", harness.answerOpenPrompt(true) > 0);
		assertFalse(harness.isPromptOpen());
		assertOnePartialRecord();
		assertNull(harness.activeSession(ACCOUNT));

		harness.clientShutdown();
		harness.shutDown();
		assertOnePartialRecord();
		assertEquals(1, harness.promptsShown());
	}

	@Test
	public void pluginShutDownWhileTheDialogIsOpenSavesThePartialOnce()
	{
		harness.scriptKeepDiscardPrompt(PluginHarness.PromptAnswer.LEAVE_OPEN);
		harness.stop();
		assertTrue(harness.isPromptOpen());

		harness.shutDown();
		assertOnePartialRecord();
		assertNull(harness.activeSession(ACCOUNT));

		// The dialog outlives the plugin; its late answer saves nothing more.
		assertTrue(harness.isPromptOpen());
		assertEquals("the late Keep queues no second save", 0, harness.answerOpenPrompt(true));
		assertOnePartialRecord();
		assertNull(harness.activeSession(ACCOUNT));
		assertEquals(1, harness.promptsShown());
	}

	@Test
	public void pluginShutDownWhileTheDialogIsOpenKeepsThePartialWhenDiscardIsAnsweredLate()
	{
		harness.scriptKeepDiscardPrompt(PluginHarness.PromptAnswer.LEAVE_OPEN);
		harness.stop();

		harness.shutDown();
		assertEquals("the late Discard queues no delete", 0, harness.answerOpenPrompt(false));
		assertOnePartialRecord();
		assertNull(harness.activeSession(ACCOUNT));
	}

	@Test
	public void clientExitWhileTheDialogIsOpenSavesThePartialOnceAndWaitsForIt()
	{
		harness.scriptKeepDiscardPrompt(PluginHarness.PromptAnswer.LEAVE_OPEN);
		harness.stop();
		assertTrue(harness.isPromptOpen());

		final List<Future<?>> waitedFor = harness.clientShutdown();
		assertEquals("the client waits for the partial's save", 1, waitedFor.size());
		assertTrue(waitedFor.get(0).isDone());
		assertOnePartialRecord();
		assertNull(harness.activeSession(ACCOUNT));

		// The plugin is stopped as the client closes, then the dialog's late answer comes; neither saves again.
		harness.shutDown();
		assertEquals("the late Keep queues no second save", 0, harness.answerOpenPrompt(true));
		assertOnePartialRecord();
		assertNull(harness.activeSession(ACCOUNT));
		assertEquals(1, harness.promptsShown());
	}
}
