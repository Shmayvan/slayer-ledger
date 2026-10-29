package com.shmayvan.slayerledger;

/** The luck meter for one task: what to show, and the comparison once it can be shown. */
final class LuckView
{
	enum State
	{
		/** The bundled data could not be read. */
		UNAVAILABLE,
		/** No completed task of it has loot data. */
		NO_SAMPLES,
		/** The loot's item names are on their way. */
		NAMES_LOADING,
		/** The comparison ({@link #getSummary}). */
		SUMMARY
	}

	static final LuckView UNAVAILABLE = new LuckView(State.UNAVAILABLE, null, false);

	private final State state;
	private final LuckMeter.Summary summary;
	private final boolean namesFailed;

	LuckView(State state, LuckMeter.Summary summary, boolean namesFailed)
	{
		this.state = state;
		this.summary = summary;
		this.namesFailed = namesFailed;
	}

	State getState()
	{
		return state;
	}

	/** The comparison while {@link State#SUMMARY}, else {@code null}. */
	LuckMeter.Summary getSummary()
	{
		return summary;
	}

	/** Whether some of the loot's item names could not be looked up. */
	boolean isNamesFailed()
	{
		return namesFailed;
	}
}
