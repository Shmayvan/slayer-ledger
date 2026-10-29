package com.shmayvan.slayerledger;

/**
 * The chat line posted after a logout or world hop paused a running session. Resuming stays manual (owner
 * decision), so the line says how to keep tracking.
 */
final class AutoPauseMessage
{
	/** What paused the session. */
	enum Reason
	{
		WORLD_HOP("world hop"),
		LOGOUT("logged out");

		private final String text;

		Reason(String text)
		{
			this.text = text;
		}
	}

	private AutoPauseMessage()
	{
	}

	/** The chat line for {@code reason}, for example {@code Slayer Ledger paused (world hop). Press Resume ...}. */
	static String format(Reason reason)
	{
		return "Slayer Ledger paused (" + reason.text + "). Press Resume to keep tracking.";
	}
}
