package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a session's restore needs besides its tallies (V2 plan 4.1), carried by {@link SessionTracker.Summary} into
 * {@link SessionSnapshot}: the state it was in, the clamps of its next resume point (the close of its last segment,
 * when its assignment was first seen, and the end of the session before it) and its known task NPCs. Times are clock
 * millis, {@code null} when unknown. Pure and immutable.
 */
final class SegmentInfo
{
	/** Nothing known (a summary built from a 1.x snapshot, or in tests). */
	static final SegmentInfo NONE = new SegmentInfo(null, SessionTracker.NO_TIME, SessionTracker.NO_TIME,
		SessionTracker.NO_TIME, null);

	private final SessionTracker.State state;
	private final Long segmentClosedAt;
	private final Long assignedAt;
	private final Long previousSessionEnd;
	private final List<String> taskNpcs;

	/**
	 * @param state              the state the session was in, or {@code null} when unknown
	 * @param segmentClosedAt    the close of its last segment, or {@link SessionTracker#NO_TIME}
	 * @param assignedAt         when its assignment was first seen, or {@link SessionTracker#NO_TIME}
	 * @param previousSessionEnd the end of the session before it, or {@link SessionTracker#NO_TIME}
	 * @param taskNpcs           its known task NPCs (normalized names), or {@code null} for none
	 */
	SegmentInfo(SessionTracker.State state, long segmentClosedAt, long assignedAt, long previousSessionEnd,
		List<String> taskNpcs)
	{
		this.state = state;
		this.segmentClosedAt = time(segmentClosedAt);
		this.assignedAt = time(assignedAt);
		this.previousSessionEnd = time(previousSessionEnd);
		this.taskNpcs = taskNpcs == null || taskNpcs.isEmpty() ? Collections.emptyList()
			: Collections.unmodifiableList(new ArrayList<>(taskNpcs));
	}

	private static Long time(long millis)
	{
		return millis == SessionTracker.NO_TIME ? null : millis;
	}

	/** The state the session was in when this was taken, or {@code null} when unknown. */
	SessionTracker.State getState()
	{
		return state;
	}

	/** The close of the session's last segment, or {@code null} while unknown (or a segment is open). */
	Long getSegmentClosedAt()
	{
		return segmentClosedAt;
	}

	/** When the session's assignment was first seen, or {@code null}. */
	Long getAssignedAt()
	{
		return assignedAt;
	}

	/** The end of the session before this one, or {@code null}. */
	Long getPreviousSessionEnd()
	{
		return previousSessionEnd;
	}

	/** The session's known task NPCs, normalized (unmodifiable, possibly empty). */
	List<String> getTaskNpcs()
	{
		return taskNpcs;
	}
}
