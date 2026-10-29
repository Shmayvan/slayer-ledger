package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.SaveDecision.Action;
import static com.shmayvan.slayerledger.SaveDecision.EndReason;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class SaveDecisionTest
{
	private static final long ACCOUNT = 42L;

	private static Action decide(boolean hasTask, long account, boolean completed, EndReason reason, boolean confirm)
	{
		return SaveDecision.decide(hasTask, account, completed, reason, confirm);
	}

	@Test
	public void completedIsSavedForEveryReason()
	{
		for (EndReason reason : EndReason.values())
		{
			assertEquals(Action.SAVE_COMPLETED, decide(true, ACCOUNT, true, reason, true));
			assertEquals(Action.SAVE_COMPLETED, decide(true, ACCOUNT, true, reason, false));
		}
	}

	@Test
	public void playerStopIncomplete()
	{
		assertEquals(Action.PROMPT, decide(true, ACCOUNT, false, EndReason.PLAYER_STOP, true));
		assertEquals(Action.SAVE_PARTIAL, decide(true, ACCOUNT, false, EndReason.PLAYER_STOP, false));
	}

	@Test
	public void systemEndedIncompleteIsPartialWithNotice()
	{
		assertEquals(Action.SAVE_PARTIAL_WITH_NOTICE, decide(true, ACCOUNT, false, EndReason.SYSTEM, true));
		assertEquals(Action.SAVE_PARTIAL_WITH_NOTICE, decide(true, ACCOUNT, false, EndReason.SYSTEM, false));
	}

	@Test
	public void restoreMismatchIsPartialWithNotice()
	{
		assertEquals(Action.SAVE_PARTIAL_WITH_NOTICE, decide(true, ACCOUNT, false, EndReason.RESTORE_MISMATCH, true));
		assertEquals(Action.SAVE_PARTIAL_WITH_NOTICE, decide(true, ACCOUNT, false, EndReason.RESTORE_MISMATCH, false));
	}

	@Test
	public void shutdownLogoutAndAccountSwitchPersistInsteadOfSaving()
	{
		for (EndReason reason : new EndReason[]{EndReason.SHUTDOWN, EndReason.LOGOUT, EndReason.ACCOUNT_SWITCH})
		{
			assertEquals(Action.PERSIST_ACTIVE, decide(true, ACCOUNT, false, reason, true));
			assertEquals(Action.PERSIST_ACTIVE, decide(true, ACCOUNT, false, reason, false));
		}
	}

	@Test
	public void unresolvedPromptIsPartialWithoutPrompt()
	{
		assertEquals(Action.SAVE_PARTIAL, decide(true, ACCOUNT, false, EndReason.PROMPT_UNRESOLVED, true));
		assertEquals(Action.SAVE_PARTIAL, decide(true, ACCOUNT, false, EndReason.PROMPT_UNRESOLVED, false));
	}

	@Test
	public void noTaskOrNoAccountIsDiscarded()
	{
		for (EndReason reason : EndReason.values())
		{
			assertEquals(Action.DISCARD, decide(false, ACCOUNT, false, reason, true));
			assertEquals(Action.DISCARD, decide(false, ACCOUNT, true, reason, false));
			assertEquals(Action.DISCARD, decide(true, SessionTracker.NO_ACCOUNT, true, reason, true));
			assertEquals(Action.DISCARD, decide(true, SessionTracker.NO_ACCOUNT, false, reason, false));
		}
	}

	@Test
	public void summaryOverloadUsesSessionAccount()
	{
		SessionTracker tracker = new SessionTracker(() -> 0L);
		tracker.observe("Abyssal demons", 1, 10, false);
		tracker.play(ACCOUNT);
		SessionTracker.Summary summary = tracker.stopAndReset();
		assertEquals(Action.PROMPT, SaveDecision.decide(summary, EndReason.PLAYER_STOP, true));
		assertEquals(Action.SAVE_PARTIAL_WITH_NOTICE, SaveDecision.decide(summary, EndReason.SYSTEM, true));
		assertEquals(Action.PERSIST_ACTIVE, SaveDecision.decide(summary, EndReason.SHUTDOWN, true));
	}
}
