package com.shmayvan.slayerledger;

/**
 * Pure decision of what to do with a session when it ends or must leave memory.
 */
final class SaveDecision
{
	/** Why the session is ending (or being taken out of memory). */
	enum EndReason
	{
		/** The player pressed Stop. */
		PLAYER_STOP,
		/** The plugin ended it while logged in (task completed, changed, or cleared). */
		SYSTEM,
		/** The player logged out. The in-memory session is kept; a copy is persisted. */
		LOGOUT,
		/** The plugin was turned off or the client is closing. */
		SHUTDOWN,
		/** Another account logged in while this account's session was active. */
		ACCOUNT_SWITCH,
		/** A persisted active session could not be restored because the task no longer matches. */
		RESTORE_MISMATCH,
		/** A Keep/Discard dialog was still open when the client or plugin shut down. */
		PROMPT_UNRESOLVED
	}

	enum Action
	{
		/** Save as a completed record. */
		SAVE_COMPLETED,
		/** Ask the player whether to keep (as partial) or discard. */
		PROMPT,
		/** Save as partial silently. */
		SAVE_PARTIAL,
		/** Save as partial and show a non-modal notice offering Discard. */
		SAVE_PARTIAL_WITH_NOTICE,
		/** Do not create a record; persist the session as the account's active session to restore later. */
		PERSIST_ACTIVE,
		/** Do not save. */
		DISCARD
	}

	private SaveDecision()
	{
	}

	static Action decide(SessionTracker.Summary summary, EndReason reason, boolean confirmIncomplete)
	{
		return decide(summary.hasTask(), summary.getAccountHash(), summary.isCompleted(), reason, confirmIncomplete);
	}

	static Action decide(boolean hasTask, long accountHash, boolean completed, EndReason reason,
		boolean confirmIncomplete)
	{
		if (!hasTask || accountHash == SessionTracker.NO_ACCOUNT)
		{
			return Action.DISCARD;
		}
		if (completed)
		{
			return Action.SAVE_COMPLETED;
		}
		switch (reason)
		{
			case LOGOUT:
			case SHUTDOWN:
			case ACCOUNT_SWITCH:
				return Action.PERSIST_ACTIVE;
			case SYSTEM:
			case RESTORE_MISMATCH:
				return Action.SAVE_PARTIAL_WITH_NOTICE;
			case PROMPT_UNRESOLVED:
				return Action.SAVE_PARTIAL;
			case PLAYER_STOP:
			default:
				return confirmIncomplete ? Action.PROMPT : Action.SAVE_PARTIAL;
		}
	}
}
