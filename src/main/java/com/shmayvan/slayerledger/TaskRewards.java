package com.shmayvan.slayerledger;

/**
 * Works out what a task completion gave: the Slayer points earned (the SLAYER_POINTS varbit, 4068) and the streak the
 * completion raised. Read on the same settled ticks as {@link CompletionSignal}. Pure (no RuneLite types) and not
 * thread-safe; the plugin uses it on the client thread only.
 *
 * <p>Points earned are the change from the reading just before the completion tick to the latest reading. Points
 * spent earlier in the task don't matter, since the baseline is the last reading before the completion. The change
 * is followed after the completion tick ({@link #startCompletion}, {@link #earned}) while the completed session is
 * held for late loot, in case the points arrive a tick after the streak counter. It is {@code null}, never negative,
 * when it can't be trusted:</p>
 * <ul>
 *     <li>there is no reading before the completion: the first reading after {@link #reset()} (after login, a world
 *     hop or a lost connection) only sets the baseline, as it does for {@link CompletionSignal}, so a login resync is
 *     never counted;</li>
 *     <li>the points went down (spent on the completion tick or just after).</li>
 * </ul>
 */
final class TaskRewards
{
	/** Reading before the latest, or {@code null}. */
	private Integer previous;
	/** Latest reading, or {@code null} after {@link #reset()}. */
	private Integer latest;
	/** Session whose completion is being followed, or {@code null} when none is. */
	private String sessionId;
	/** Points before the followed completion, or {@code null} when unknown. */
	private Integer completionBaseline;
	/** Streak after the followed completion, or {@code null} when unknown. */
	private Integer completionStreak;

	/** Forgets every reading and stops following. */
	void reset()
	{
		previous = null;
		latest = null;
		stopFollowing();
	}

	/** Records this settled tick's SLAYER_POINTS reading. Call once per settled tick, before {@link #startCompletion}. */
	void update(int points)
	{
		previous = latest;
		latest = points;
	}

	/**
	 * Starts following a completion signalled on this tick.
	 *
	 * @param completedSessionId the completed session
	 * @param counters           this tick's completion counters (as passed to {@link CompletionSignal#update})
	 * @param incrementedCounter {@link CompletionSignal#incrementedCounter()} for this tick
	 */
	void startCompletion(String completedSessionId, int[] counters, int incrementedCounter)
	{
		sessionId = completedSessionId;
		completionBaseline = previous;
		completionStreak = streakAfter(counters, incrementedCounter);
	}

	/** Whether a completion is being followed. */
	boolean isFollowing()
	{
		return sessionId != null;
	}

	/** The session whose completion is being followed, or {@code null}. */
	String getSessionId()
	{
		return sessionId;
	}

	/** Points earned by the followed completion as of the latest reading, or {@code null}; see the class comment. */
	Integer earned()
	{
		return isFollowing() ? pointsEarned(completionBaseline, latest) : null;
	}

	/** Streak after the followed completion, or {@code null}. */
	Integer streak()
	{
		return isFollowing() ? completionStreak : null;
	}

	/** Stops following the completion (it has been saved, or can no longer be updated). */
	void stopFollowing()
	{
		sessionId = null;
		completionBaseline = null;
		completionStreak = null;
	}

	/** {@code after - before}, or {@code null} when either is unknown or the points went down. */
	static Integer pointsEarned(Integer before, Integer after)
	{
		if (before == null || after == null || after < before)
		{
			return null;
		}
		return after - before;
	}

	/**
	 * The streak a completion raised: the counter that went up, as it stands now, or {@code null} when it is not
	 * known which counter that was.
	 */
	static Integer streakAfter(int[] counters, int incrementedCounter)
	{
		if (counters == null || incrementedCounter < 0 || incrementedCounter >= counters.length)
		{
			return null;
		}
		return counters[incrementedCounter];
	}
}
