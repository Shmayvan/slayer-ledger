package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Watches Slayer assignments on the plugin's settled ticks and reports each one that ended without a session as an
 * {@link AssignmentEntry} (skipped, blocked, reset, completed while untracked, or ended for an unknown reason; see
 * {@link AssignmentOutcome}). Pure (no RuneLite types) and not thread-safe; the plugin uses it on the client thread
 * only, every settled tick, whether or not a session is running. It only reads; it never acts.
 *
 * <p><b>Played assignments.</b> An assignment that any session held (the creature keys from
 * {@link SessionTracker#takeSessionTaskKeys()}: started, adopted, restored, paused, or held for late loot) is played,
 * and never reported, whether that session was saved as completed, as partial, or discarded. Played-ness is only
 * known for what this plugin run saw: an assignment first seen at a login or plugin start ("not witnessed from its
 * start") may have had a session in an earlier run, so its entry is marked {@link Ended#isCheckRecords()} and the
 * plugin skips it when the newest saved record looks like the same assignment ({@link AssignmentLog#playedBefore}).</p>
 *
 * <p><b>Ending.</b> The assignment ends on the first settled tick it is no longer current: the task count reads 0, or
 * another task (another creature key, name or assigned size) is current. The outcome is decided
 * {@link #END_WINDOW_TICKS} ticks later, comparing the readings on the last tick it was active with the readings then,
 * so points or a streak that arrive a tick late still count. If the count reads 0 and the same task comes back first
 * (a transient zero, as {@link SessionTracker} also allows), nothing ended. An end by a 0 count whose outcome is
 * {@link AssignmentOutcome#UNKNOWN} waits until another task appears (and is dropped if the same task comes back with
 * no more kills left than before), as the tracker treats a long zero gap.</p>
 *
 * <p><b>Logins, hops and lost connections</b> ({@link #reset()}): any undecided end is dropped, and the first reading
 * afterwards only re-reads the baseline, as for {@link CompletionSignal} and {@link TaskRewards}, so a login resync
 * never produces an entry. The current assignment is remembered across it (an assignment still active at logout has
 * not ended): if the task after the login is the same one, it keeps its played flag; otherwise the old one ended while
 * the plugin wasn't watching and is forgotten without an entry. While the count still reads 0 after a login, the
 * baseline waits up to {@link #SYNC_MAX_TICKS} ticks for the task, like the restore check.</p>
 *
 * <p><b>New assignments</b> ({@link #takeStarted()}, for the new-task chat preview): an assignment counts as new only
 * when it is first seen outside a baseline, i.e. watched from its start. One read by a baseline (plugin start, login,
 * hop, account change) or that comes back after a transient or long zero is never new.</p>
 */
final class AssignmentWatch
{
	/** Ticks after the end at which the outcome is decided. One more than the tracker's transient-zero allowance. */
	static final int END_WINDOW_TICKS = SessionTracker.MAX_TRANSIENT_ZERO_OBSERVATIONS + 1;
	/** Ticks a post-login baseline waits for a non-zero task count before deciding there is no task. */
	static final int SYNC_MAX_TICKS = SessionTracker.RESTORE_SYNC_MAX_TICKS;

	/** One settled tick's reading of the task and the values the outcome is decided from; see {@link #builder()}. */
	static final class Reading
	{
		private final String monster;
		private final int creatureId;
		private final int remaining;
		private final int originalAmount;
		private final TaskContext context;
		private final Values values;
		private final boolean completionSignaled;

		private Reading(Builder b)
		{
			this.monster = b.monster;
			this.creatureId = b.creatureId;
			this.remaining = Math.max(0, b.remaining);
			this.originalAmount = b.originalAmount > 0 ? b.originalAmount : SessionTracker.UNKNOWN_AMOUNT;
			this.context = b.context == null ? TaskContext.NONE : b.context;
			this.values = new Values(b.points, b.counters, b.blockedValues);
			this.completionSignaled = b.completionSignaled;
		}

		/** A builder starting from no task: no creature, unknown size, no counters or blocked values. */
		static Builder builder()
		{
			return new Builder();
		}

		/** Builds a {@link Reading}. */
		static final class Builder
		{
			private String monster;
			private int creatureId = SessionTracker.NO_CREATURE;
			private int remaining;
			private int originalAmount = SessionTracker.UNKNOWN_AMOUNT;
			private TaskContext context;
			private int points;
			private int[] counters;
			private int[] blockedValues;
			private boolean completionSignaled;

			private Builder()
			{
			}

			/** Task name, or {@code null} when there is no task or the lookup failed. */
			Builder monster(String monster)
			{
				this.monster = monster;
				return this;
			}

			/** Creature key, or {@link SessionTracker#NO_CREATURE}. */
			Builder creatureId(int creatureId)
			{
				this.creatureId = creatureId;
				return this;
			}

			/** SLAYER_COUNT (0 when there is no task). */
			Builder remaining(int remaining)
			{
				this.remaining = remaining;
				return this;
			}

			/** Assigned size, or {@link SessionTracker#UNKNOWN_AMOUNT}. */
			Builder originalAmount(int originalAmount)
			{
				this.originalAmount = originalAmount;
				return this;
			}

			/** Master and area ({@link TaskContext#assignment}), or {@code null}. */
			Builder context(TaskContext context)
			{
				this.context = context;
				return this;
			}

			/** SLAYER_POINTS. */
			Builder points(int points)
			{
				this.points = points;
				return this;
			}

			/** The completion counters, as passed to {@link CompletionSignal#update}. */
			Builder counters(int[] counters)
			{
				this.counters = counters;
				return this;
			}

			/** Every blocked-task varp and varbit, in the same order every tick. */
			Builder blockedValues(int[] blockedValues)
			{
				this.blockedValues = blockedValues;
				return this;
			}

			/** {@link CompletionSignal#update}'s result for this tick. */
			Builder completionSignaled(boolean completionSignaled)
			{
				this.completionSignaled = completionSignaled;
				return this;
			}

			Reading build()
			{
				return new Reading(this);
			}
		}

		private boolean hasTask()
		{
			return remaining > 0;
		}
	}

	/** An ended assignment to store. */
	static final class Ended
	{
		private final AssignmentEntry entry;
		private final boolean checkRecords;

		private Ended(AssignmentEntry entry, boolean checkRecords)
		{
			this.entry = entry;
			this.checkRecords = checkRecords;
		}

		AssignmentEntry getEntry()
		{
			return entry;
		}

		/**
		 * Whether the assignment wasn't watched from its start, so a session in an earlier plugin run can't be ruled
		 * out: store it only if {@link AssignmentLog#playedBefore} says the saved records don't show one.
		 */
		boolean isCheckRecords()
		{
			return checkRecords;
		}
	}

	/** Points, completion counters and blocked-task values at one reading. */
	private static final class Values
	{
		private final int points;
		private final int[] counters;
		private final int[] blocked;

		private Values(int points, int[] counters, int[] blocked)
		{
			this.points = points;
			this.counters = counters == null ? new int[0] : counters.clone();
			this.blocked = blocked == null ? new int[0] : blocked.clone();
		}
	}

	/** An assignment as watched. */
	private static final class Assignment
	{
		private String monster;
		private final int creatureId;
		private int originalAmount;
		private TaskContext context;
		private int remaining;
		private final boolean witnessedStart;
		private boolean played;
		/** Readings on the last tick the assignment was current. */
		private Values lastActive;
		/** Whether a completion was signalled on that tick. */
		private boolean completionOnLastActive;

		private Assignment(Reading r, boolean witnessedStart)
		{
			this.monster = r.monster;
			this.creatureId = r.creatureId;
			this.originalAmount = r.originalAmount;
			this.context = r.context;
			this.remaining = r.remaining;
			this.witnessedStart = witnessedStart;
			this.lastActive = r.values;
			this.completionOnLastActive = r.completionSignaled;
		}

		/** Same task: creature key, name (when both known) and assigned size (when both known). */
		private boolean sameTask(Reading r)
		{
			return r.hasTask() && r.creatureId == creatureId
				&& (r.monster == null || monster == null || r.monster.equals(monster))
				&& (r.originalAmount == SessionTracker.UNKNOWN_AMOUNT || originalAmount == SessionTracker.UNKNOWN_AMOUNT
				|| r.originalAmount == originalAmount);
		}

		/** The same task after a gap (a login, or a long zero): also no more kills left than before. */
		private boolean sameTaskAfterGap(Reading r)
		{
			return sameTask(r) && r.remaining <= remaining;
		}

		/** Takes this tick's reading of the same task. */
		private void refresh(Reading r)
		{
			if (monster == null)
			{
				monster = r.monster;
			}
			if (originalAmount == SessionTracker.UNKNOWN_AMOUNT)
			{
				originalAmount = r.originalAmount;
			}
			context = context.fillAssignment(r.context);
			remaining = r.remaining;
			lastActive = r.values;
			completionOnLastActive = r.completionSignaled;
		}
	}

	/** An assignment that has ended, waiting for its outcome. */
	private static final class Pending
	{
		private final Assignment assignment;
		private final long endedAt;
		private final boolean endedByZero;
		private boolean completion;
		private int ticks;
		/** Readings at the end of the window, once an unknown outcome waits for the next task; else {@code null}. */
		private Values frozen;

		private Pending(Assignment assignment, long endedAt, boolean endedByZero, boolean completion)
		{
			this.assignment = assignment;
			this.endedAt = endedAt;
			this.endedByZero = endedByZero;
			this.completion = completion;
		}
	}

	/** The current assignment, or {@code null}; kept across {@link #reset()}. */
	private Assignment current;
	/** The ended assignment waiting for its outcome, or {@code null}. */
	private Pending pending;
	/** Whether the next readings only set the baseline (after construction, {@link #reset()} or {@link #forget()}). */
	private boolean syncing = true;
	/** Zero-count readings while syncing. */
	private int syncTicks;
	/** The current assignment watched from its start, until {@link #takeStarted()} takes it; else {@code null}. */
	private Assignment started;

	/** After a login, hop or lost connection: drops an undecided end, keeps the current assignment; see the class. */
	void reset()
	{
		pending = null;
		syncing = true;
		syncTicks = 0;
		started = null;
	}

	/** After an account change: forgets everything, including the current assignment. */
	void forget()
	{
		reset();
		current = null;
	}

	/**
	 * Takes one settled tick's reading.
	 *
	 * @param r               this tick's reading
	 * @param sessionTaskKeys {@link SessionTracker#takeSessionTaskKeys()} for this tick: creature keys of tasks a
	 *                        session held since the last tick
	 * @param now             the time (epoch ms), used as the end time of an assignment that ends on this tick
	 * @return the assignments to store (usually none)
	 */
	List<Ended> update(Reading r, Set<Integer> sessionTaskKeys, long now)
	{
		markPlayed(sessionTaskKeys);
		if (syncing)
		{
			sync(r);
			markPlayed(sessionTaskKeys);
			return Collections.emptyList();
		}

		final List<Ended> ended = new ArrayList<>(1);
		if (pending != null && current == null && pending.endedByZero && !pending.completion
			&& (pending.frozen == null ? pending.assignment.sameTask(r) : pending.assignment.sameTaskAfterGap(r)))
		{
			// A transient zero: the same task is back, so it never ended.
			current = pending.assignment;
			current.refresh(r);
			pending = null;
		}
		else if (current != null && current.sameTask(r))
		{
			current.refresh(r);
		}
		else if (current != null)
		{
			if (pending != null)
			{
				// Another end before the last one was decided: decide that one now, by the readings just before this
				// second change, so the second assignment's points or streak don't count for the first.
				add(ended, pending, current.lastActive);
			}
			pending = new Pending(current, now, !r.hasTask(), current.completionOnLastActive || r.completionSignaled);
			current = r.hasTask() ? new Assignment(r, true) : null;
			started = current;
			markPlayed(sessionTaskKeys);
			return ended;
		}
		else if (r.hasTask())
		{
			current = new Assignment(r, true);
			started = current;
			if (pending != null && pending.frozen != null)
			{
				// Another task after a long zero: the old one ended, reason unknown.
				add(ended, pending, pending.frozen);
				pending = null;
			}
		}

		if (pending != null && pending.frozen == null)
		{
			pending.ticks++;
			pending.completion |= r.completionSignaled;
			if (pending.ticks >= END_WINDOW_TICKS)
			{
				if (pending.endedByZero && current == null && outcome(pending, r.values) == AssignmentOutcome.UNKNOWN)
				{
					pending.frozen = r.values;
				}
				else
				{
					add(ended, pending, r.values);
					pending = null;
				}
			}
		}
		markPlayed(sessionTaskKeys);
		return ended;
	}

	private void sync(Reading r)
	{
		if (r.hasTask())
		{
			if (current != null && current.sameTaskAfterGap(r))
			{
				current.refresh(r);
			}
			else
			{
				// A new assignment, or the old one ended while the plugin wasn't watching: no entry either way.
				current = new Assignment(r, false);
			}
			syncing = false;
		}
		else if (++syncTicks >= SYNC_MAX_TICKS)
		{
			current = null;
			syncing = false;
		}
	}

	private void markPlayed(Set<Integer> keys)
	{
		if (keys == null || keys.isEmpty())
		{
			return;
		}
		if (current != null && keys.contains(current.creatureId))
		{
			current.played = true;
		}
		if (pending != null && keys.contains(pending.assignment.creatureId))
		{
			pending.assignment.played = true;
		}
	}

	private static AssignmentOutcome outcome(Pending p, Values now)
	{
		final Values before = p.assignment.lastActive;
		boolean streakDropped = false;
		final int n = Math.min(before.counters.length, now.counters.length);
		for (int i = 0; i < n; i++)
		{
			if (now.counters[i] < before.counters[i])
			{
				streakDropped = true;
				break;
			}
		}
		return AssignmentOutcome.classify(p.completion, now.points - before.points,
			!Arrays.equals(before.blocked, now.blocked), streakDropped);
	}

	/** Adds the pending assignment's entry, unless a session held it. */
	private static void add(List<Ended> ended, Pending p, Values now)
	{
		final Assignment a = p.assignment;
		if (a.played)
		{
			return;
		}
		final AssignmentEntry entry = new AssignmentEntry();
		entry.setId(UUID.randomUUID().toString());
		entry.setMonster(a.monster);
		entry.setCreatureId(a.creatureId == SessionTracker.NO_CREATURE ? null : a.creatureId);
		entry.setOriginalAmount(a.originalAmount == SessionTracker.UNKNOWN_AMOUNT ? null : a.originalAmount);
		entry.setSlayerMaster(a.context.getMaster());
		entry.setSlayerArea(a.context.getArea());
		entry.setTaskLocation(a.context.getLocation());
		entry.setEndedAt(p.endedAt);
		entry.setOutcome(outcome(p, now));
		entry.setPointsChange(now.points - a.lastActive.points);
		ended.add(new Ended(entry, !a.witnessedStart));
	}

	/**
	 * Whether the last {@link #update} saw a new assignment (one watched from its start; see the class), once: the
	 * flag is cleared by this call, by a later assignment replacing it, and by {@link #reset()}.
	 *
	 * @return {@code true} at most once per new assignment
	 */
	boolean takeStarted()
	{
		final boolean isNew = started != null && started == current;
		started = null;
		return isNew;
	}

	/** Test seam: whether an assignment is being watched now. */
	boolean hasCurrent()
	{
		return current != null;
	}

	/** Test seam: whether an ended assignment waits for its outcome. */
	boolean hasPending()
	{
		return pending != null;
	}

	@Override
	public String toString()
	{
		return "AssignmentWatch(current=" + (current == null ? null : current.creatureId) + ", pending="
			+ (pending == null ? null : pending.assignment.creatureId) + ", syncing=" + syncing + ")";
	}
}
