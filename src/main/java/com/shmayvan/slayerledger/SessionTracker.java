package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Tracks a single Slayer task session. This class has no RuneLite dependencies so it can be unit tested with a
 * controllable clock. All public methods are synchronized because the plugin calls it from both the client thread
 * (task observations, auto-pause, account changes) and the Swing event dispatch thread (Play / Pause / Stop).
 *
 * <p>States: {@code IDLE -> RUNNING <-> PAUSED}, {@code RUNNING|PAUSED -> STOPPED}, and {@link #restore} takes
 * {@code IDLE} to {@code PAUSED}. Invalid transitions are no-ops and return {@code false}. The plugin ends sessions
 * through {@link #stopAndReset}, so it never leaves the tracker {@code STOPPED}.
 * {@link #reset()} returns a stopped tracker to {@code IDLE} so a new session can begin.</p>
 *
 * <p>A running session that completes can be held back from saving for {@link #COMPLETION_LOOT_GRACE_TICKS} game
 * ticks (see {@link #holdCompletion()}) so the final kill's loot, which may arrive after the task count update, is
 * still recorded. The held session is already stopped (its kills and active time are final) and the tracker is back to
 * {@code IDLE}; it is outside the state machine, so it is never reported as active.</p>
 */
public class SessionTracker
{
	public enum State
	{
		IDLE,
		RUNNING,
		PAUSED,
		STOPPED
	}

	/**
	 * Outcome of {@link #restore}.
	 */
	public enum RestoreResult
	{
		/** The snapshot was restored as a PAUSED session. */
		RESTORED,
		/** The current task does not match the snapshot; it should be saved as partial instead. */
		MISMATCH,
		/** Restore is not possible right now (a session is active or no task has been observed yet). */
		REFUSED
	}

	/**
	 * Outcome of {@link #observe}, telling the caller whether the session needs attention.
	 */
	public enum ObserveResult
	{
		/** Nothing the caller needs to act on. */
		NONE,
		/** The session's task was completed (the session should be stopped and saved). */
		COMPLETED,
		/** The remaining count dropped to zero without a completion signal (for example, a cancelled task). */
		TASK_CLEARED,
		/** A different task was observed while a session was active (the session should be stopped). */
		TASK_CHANGED
	}

	/** Value used for "no creature id known". */
	public static final int NO_CREATURE = -1;
	/** Value used for "no amount known". */
	public static final int UNKNOWN_AMOUNT = -1;
	/**
	 * A drop to zero followed by the same task within this many zero observations (ticks) is treated as a transient
	 * glitch and the session resumes. Longer gaps are treated as a cancelled-and-reassigned task, unless the assigned
	 * size is unchanged and the remaining count did not go up.
	 */
	public static final int MAX_TRANSIENT_ZERO_OBSERVATIONS = 3;

	/** Maximum ticks a post-login restore check waits for a non-zero task count before running anyway. */
	public static final int RESTORE_SYNC_MAX_TICKS = 10;

	/** SLAYER_TARGET value for boss tasks, from [proc,helper_slayer_current_assignment] (as used by core SlayerPlugin). */
	public static final int BOSS_TASK_TARGET = 98;

	/**
	 * Creature key identifying a task: the SLAYER_TARGET value, or for boss tasks {@code -2 - bossId} so that each
	 * boss is a distinct task even when name lookups fail. Boss keys are always negative and never equal
	 * {@link #NO_CREATURE}.
	 */
	public static int creatureKey(int slayerTarget, int bossId)
	{
		return slayerTarget == BOSS_TASK_TARGET ? -2 - Math.max(0, bossId) : slayerTarget;
	}

	/** Value used for "no account". Matches {@code Client#getAccountHash()} when logged out. */
	public static final long NO_ACCOUNT = -1L;

	/**
	 * Game ticks a completed running session waits for late loot before it is saved (see {@link #holdCompletion()}).
	 * The final kill's loot can arrive on the completion tick after the task count update, or a tick or two later; the
	 * plugin counts the window from the tick after completion, so loot through completion tick + 2 is kept.
	 */
	public static final int COMPLETION_LOOT_GRACE_TICKS = 2;

	/** Read under the tracker's lock; only {@link #setClock} replaces it. */
	private LongSupplier clock;

	private State state = State.IDLE;

	// Latest observed task, kept regardless of session state.
	private boolean taskObserved;
	private String currentMonster;
	private int currentCreatureId = NO_CREATURE;
	private int currentRemaining;
	private int currentOriginalAmount = UNKNOWN_AMOUNT;
	/** Who assigned the latest observed task (master and area), or {@link TaskContext#NONE}. */
	private TaskContext currentContext = TaskContext.NONE;

	// Session task.
	private String sessionId;
	private boolean restoredPending;
	private long sessionAccountHash = NO_ACCOUNT;
	private String sessionMonster;
	private int sessionCreatureId = NO_CREATURE;
	private boolean hasSessionTask;
	private int initialAmount;
	private int originalAmount = UNKNOWN_AMOUNT;
	private int baseline;
	private int kills;
	// Kills from a final drop to zero that is awaiting a completion signal.
	private int pendingFinalKills;
	private boolean awaitingCompletion;
	// Remaining count just before the drop to zero, and how many zero observations have been seen since.
	private int preZeroBaseline;
	private int zeroObservations;
	private boolean completed;
	/** NPC loot received while RUNNING. */
	private final ItemTally loot = new ItemTally();
	/** Supplies used while RUNNING; potions in doses (see {@link #supplyDoses}). */
	private final ItemTally supplies = new ItemTally();
	/** Doses per item for potion ids in {@link #supplies}. */
	private final Map<Integer, Integer> supplyDoses = new TreeMap<>();
	/** Superior spawns, bank trips and deaths counted while RUNNING; never {@code null} and never unknown here. */
	private SessionCounters counters = SessionCounters.ZERO;
	/** Notable drops while RUNNING, in the order they arrived; bounded by {@link NotableDrops#add}. */
	private List<NotableDrop> notableDrops = new ArrayList<>();
	/** Boss kill counts while RUNNING, in the order first seen; bounded by {@link BossKillCounts#add}. */
	private List<BossKillCount> bossKillCounts = new ArrayList<>();
	/** Counted kills per map region, in the order first seen (see {@link LocationTally}); counted like {@link #kills}. */
	private List<LocationKills> locationKills = new ArrayList<>();
	/**
	 * Deaths of NPCs the player damaged while RUNNING, per NPC name in the order first seen ({@link NpcKillTally}); they decide whether the task was done on its Catacombs variant ({@link CatacombsVariants}).
	 */
	private List<NpcKills> npcKills = new ArrayList<>();
	/** Region the player was in when {@link #pendingFinalKills} dropped, so they are tallied there when confirmed. */
	private int pendingFinalRegion = LocationTally.NO_REGION;
	/**
	 * Region of the latest counted kill whose task has no assigned area name, until {@link #takeRegionToName()} takes
	 * it; {@link LocationTally#NO_REGION} when none. Not session data: it outlives the session that counted the kill.
	 */
	private int regionToName = LocationTally.NO_REGION;
	/**
	 * Region the player was in at the latest {@link #observe}, or {@link LocationTally#NO_REGION} when unknown or the
	 * task was cleared. Not session data: the panel uses it for the current task's location (Part B).
	 */
	private int currentRegion = LocationTally.NO_REGION;
	/** The player's note on the session ({@link TaskNote#normalize normalized}), or {@code null}. */
	private String note;
	/** XP gained per skill while RUNNING (see {@link #observeXp}), keyed as {@link SkillXp} keys them. */
	private final Map<String, Long> xpGained = new TreeMap<>();
	/**
	 * The latest XP reading of every skill ({@link #observeXp}), or {@code null} after {@link #resetXpBaseline()} or an
	 * unsynced reading. Kept whatever the state, so it is always the baseline for the next reading: XP gained while
	 * idle, paused, before Play or in a held completion's grace window moves it and is never counted later.
	 */
	private Map<String, Integer> xpReading;
	/**
	 * The session task's assignment, and once the task is completed the points earned and streak (see
	 * {@link #setCompletionRewards}), or {@link TaskContext#NONE}.
	 */
	private TaskContext sessionContext = TaskContext.NONE;

	private long accumulatedMillis;
	private long runningSince;
	private long startedAt;
	private long endedAt;

	/** Completed session held back from saving for late loot, or {@code null}. Independent of {@link #state}. */
	private Summary pendingCompletion;
	/** Game ticks left before {@link #pendingCompletion} is released by {@link #tickCompletion()}. */
	private int pendingCompletionTicks;
	/**
	 * Creature keys of tasks a session took (adopted or restored) since the last {@link #takeSessionTaskKeys()}, so a
	 * session started and ended between two calls is still reported.
	 */
	private final Set<Integer> takenTaskKeys = new HashSet<>();

	public SessionTracker(LongSupplier clock)
	{
		this.clock = Objects.requireNonNull(clock);
	}

	/** Test seam: reads the time from {@code clock} from now on (a harness-run plugin's tracker reads the system clock). */
	synchronized void setClock(LongSupplier clock)
	{
		this.clock = Objects.requireNonNull(clock);
	}

	/**
	 * Whether {@link #play(long)} would succeed for the given account.
	 */
	public synchronized boolean canPlay(long accountHash)
	{
		if (accountHash == NO_ACCOUNT || !taskObserved)
		{
			return false;
		}
		return state == State.IDLE || (state == State.PAUSED && accountHash == sessionAccountHash);
	}

	/**
	 * Starts a session (from IDLE) or resumes it (from PAUSED) for the given account. Refused until the task has
	 * been observed for the current login (see {@link #clearCurrentTask()}), and resuming requires the same account
	 * that started the session.
	 */
	public synchronized boolean play(long accountHash)
	{
		if (!canPlay(accountHash))
		{
			return false;
		}
		long now = clock.getAsLong();
		restoredPending = false;
		if (state == State.IDLE)
		{
			sessionId = UUID.randomUUID().toString();
			sessionAccountHash = accountHash;
			startedAt = now;
			runningSince = now;
			state = State.RUNNING;
			if (currentRemaining > 0)
			{
				adoptCurrentTask();
			}
			return true;
		}
		runningSince = now;
		state = State.RUNNING;
		return true;
	}

	/**
	 * Pauses a running session.
	 */
	public synchronized boolean pause()
	{
		if (state != State.RUNNING)
		{
			return false;
		}
		accumulate(clock.getAsLong());
		state = State.PAUSED;
		return true;
	}

	/**
	 * Pauses because of logout or world hop. Resuming is always manual.
	 */
	public synchronized boolean autoPause()
	{
		return pause();
	}

	/**
	 * Forgets the latest observed task (for example on logout or account change) so stale data is not shown or
	 * adopted. Play is refused until the next {@link #observe}. The active session, if any, is unaffected.
	 */
	public synchronized void clearCurrentTask()
	{
		taskObserved = false;
		currentMonster = null;
		currentCreatureId = NO_CREATURE;
		currentRemaining = 0;
		currentOriginalAmount = UNKNOWN_AMOUNT;
		currentContext = TaskContext.NONE;
		currentRegion = LocationTally.NO_REGION;
	}

	/**
	 * Stops a running or paused session. Use {@link #summary()} to read the result, then {@link #reset()}.
	 */
	public synchronized boolean stop()
	{
		if (state != State.RUNNING && state != State.PAUSED)
		{
			return false;
		}
		long now = clock.getAsLong();
		if (state == State.RUNNING)
		{
			accumulate(now);
		}
		endedAt = now;
		state = State.STOPPED;
		return true;
	}

	/**
	 * Returns a stopped tracker to IDLE. The latest observed task is kept so it can be shown and adopted by the
	 * next session.
	 */
	public synchronized boolean reset()
	{
		if (state != State.STOPPED)
		{
			return false;
		}
		state = State.IDLE;
		clearSession();
		return true;
	}

	/**
	 * Atomically stops an active session, captures its summary and returns to IDLE.
	 *
	 * @return the stopped session's summary, or {@code null} if no session was running or paused
	 */
	public synchronized Summary stopAndReset()
	{
		if (!stop())
		{
			return null;
		}
		Summary summary = summary();
		reset();
		return summary;
	}

	/**
	 * Atomically stops and resets the active session only if it belongs to an account other than the given one.
	 *
	 * @return the stopped session's summary, or {@code null} if nothing was stopped
	 */
	public synchronized Summary stopAndResetIfOtherAccount(long accountHash)
	{
		if ((state != State.RUNNING && state != State.PAUSED) || sessionAccountHash == accountHash)
		{
			return null;
		}
		return stopAndReset();
	}

	/**
	 * Whether a snapshot matches the latest observed task closely enough to be restored: same creature key, a task
	 * is active, the remaining count has not gone up, the assigned size has not changed (when both are known), and
	 * no task-completion counter has changed since the snapshot (when both are known). See
	 * {@link #counterChanged(int, int)}.
	 */
	public synchronized boolean matchesCurrentTask(SessionSnapshot snapshot, int[] currentCompletionCounters)
	{
		if (!taskObserved || currentRemaining <= 0 || currentCreatureId != snapshot.getCreatureId()
			|| currentRemaining > snapshot.getRemaining())
		{
			return false;
		}
		Integer savedOriginal = snapshot.getOriginalAmount();
		if (savedOriginal != null && currentOriginalAmount != UNKNOWN_AMOUNT && savedOriginal != currentOriginalAmount)
		{
			return false;
		}
		int[] saved = snapshot.getCompletionCounters();
		if (saved != null && currentCompletionCounters != null && saved.length == currentCompletionCounters.length)
		{
			for (int i = 0; i < saved.length; i++)
			{
				if (counterChanged(saved[i], currentCompletionCounters[i]))
				{
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * For a session kept in memory across a logout: whether it still matches the task observed after the next login,
	 * using the same checks as {@link #restore}. Returns {@code true} (nothing to do) if no session is active or the
	 * active session is not the one in the snapshot.
	 */
	public synchronized boolean activeSessionStillMatches(SessionSnapshot logoutSnapshot, int[] currentCompletionCounters)
	{
		if ((state != State.RUNNING && state != State.PAUSED) || logoutSnapshot == null
			|| !Objects.equals(sessionId, logoutSnapshot.getSessionId()))
		{
			return true;
		}
		return matchesCurrentTask(logoutSnapshot, currentCompletionCounters);
	}

	/**
	 * Whether one task-completion counter shows a change since a snapshot. An increase means a task was completed;
	 * a decrease to a non-zero value means the streak changed (for example a Turael reset or cancellation). A counter
	 * that reads zero now but was non-zero in the snapshot is treated as not yet synced (unknown), not a decrease.
	 */
	static boolean counterChanged(int saved, int current)
	{
		if (current == 0 && saved != 0)
		{
			return false;
		}
		return current != saved;
	}

	/**
	 * Whether a post-login restore / re-login check should wait another tick: the task count may not be synced yet
	 * while it reads zero, so the check is deferred until it is positive, for at most
	 * {@link #RESTORE_SYNC_MAX_TICKS} ticks (after which it runs anyway and a zero count is a mismatch).
	 *
	 * @param remaining    the task count read this tick
	 * @param ticksWaited  how many ticks the check has already been deferred
	 */
	public static boolean shouldDeferRestoreCheck(int remaining, int ticksWaited)
	{
		return remaining <= 0 && ticksWaited < RESTORE_SYNC_MAX_TICKS;
	}

	/**
	 * Restores a persisted session as PAUSED if it matches the latest observed task. Kills and active time are kept;
	 * the kill baseline is the current remaining count, so kills made while untracked are not counted.
	 */
	public synchronized RestoreResult restore(SessionSnapshot snapshot, int[] currentCompletionCounters)
	{
		if (state != State.IDLE || !taskObserved)
		{
			return RestoreResult.REFUSED;
		}
		if (snapshot.getAccountHash() == NO_ACCOUNT || !matchesCurrentTask(snapshot, currentCompletionCounters))
		{
			return RestoreResult.MISMATCH;
		}
		clearSession();
		state = State.PAUSED;
		restoredPending = true;
		sessionId = snapshot.getSessionId() != null ? snapshot.getSessionId() : UUID.randomUUID().toString();
		sessionAccountHash = snapshot.getAccountHash();
		sessionMonster = snapshot.getMonster() != null ? snapshot.getMonster() : currentMonster;
		sessionCreatureId = snapshot.getCreatureId();
		hasSessionTask = true;
		takenTaskKeys.add(sessionCreatureId);
		initialAmount = snapshot.getInitialAmount();
		originalAmount = snapshot.getOriginalAmount() != null ? snapshot.getOriginalAmount() : currentOriginalAmount;
		baseline = currentRemaining;
		kills = Math.max(0, snapshot.getKills());
		loot.merge(ItemTally.fromMap(snapshot.getLoot()));
		supplies.merge(ItemTally.fromMap(snapshot.getSupplies()));
		putDoses(supplyDoses, snapshot.getSupplyDoses());
		// A snapshot from before these were counted has none; counting starts from the restore.
		counters = SessionCounters.from(snapshot).withUnknownAsZero();
		// A snapshot from before notable drops were kept has none.
		notableDrops = NotableDrops.sanitize(snapshot.getNotableDrops());
		// Likewise for boss kill counts.
		bossKillCounts = BossKillCounts.sanitize(snapshot.getBossKillCounts());
		// Likewise for the kills per region; a snapshot without them tallies from the restore.
		locationKills = LocationTally.sanitize(snapshot.getLocationKills());
		// Likewise for the kills per NPC name; a snapshot without them tallies from the restore.
		npcKills = NpcKillTally.sanitize(snapshot.getNpcKills());
		note = TaskNote.normalize(snapshot.getNote());
		// A snapshot from before XP was tracked has none; counting starts from the restore.
		SkillXp.add(xpGained, snapshot.getXpGained());
		// The snapshot matched the current task, so the current reading fills what an older snapshot lacks.
		sessionContext = TaskContext.of(snapshot.getSlayerMaster(), snapshot.getSlayerArea(),
			snapshot.getTaskLocation(), null, null).fillAssignment(currentContext);
		accumulatedMillis = Math.max(0, snapshot.getActiveMillis());
		startedAt = snapshot.getStartedAt();
		return RestoreResult.RESTORED;
	}

	/**
	 * Stops a RUNNING session whose task was just completed and holds it back from saving for
	 * {@link #COMPLETION_LOOT_GRACE_TICKS} game ticks, so late loot from the final kill is still recorded. The kills,
	 * active time and end time are frozen now and the tracker returns to {@code IDLE} at once.
	 *
	 * <p>Refused (returns {@code false}, nothing changes) unless the session is RUNNING and completed, or if another
	 * completed session is already held. A completion while PAUSED is not held: no loot is counted while paused, so the
	 * caller saves it immediately as before.</p>
	 *
	 * @return whether the session is now held; if {@code false} the caller should end and save it as usual
	 */
	public synchronized boolean holdCompletion()
	{
		if (state != State.RUNNING || !completed || pendingCompletion != null)
		{
			return false;
		}
		pendingCompletion = stopAndReset();
		pendingCompletionTicks = COMPLETION_LOOT_GRACE_TICKS;
		return true;
	}

	/**
	 * Counts down the held completed session by one game tick. Call once per game tick.
	 *
	 * @return the held session's final summary once its grace window has ended (it is no longer held), otherwise
	 *         {@code null}
	 */
	public synchronized Summary tickCompletion()
	{
		if (pendingCompletion == null)
		{
			return null;
		}
		pendingCompletionTicks--;
		return pendingCompletionTicks > 0 ? null : takePendingCompletion();
	}

	/**
	 * Releases the held completed session at once (for example on logout, account change, Stop or shutdown). Each
	 * held session is returned exactly once, by this method or {@link #tickCompletion()}.
	 *
	 * @return the held session's summary (including loot received so far), or {@code null} if none is held
	 */
	public synchronized Summary takePendingCompletion()
	{
		final Summary summary = pendingCompletion;
		pendingCompletion = null;
		pendingCompletionTicks = 0;
		return summary;
	}

	/** Whether a completed session is being held for late loot. */
	public synchronized boolean hasPendingCompletion()
	{
		return pendingCompletion != null;
	}

	/**
	 * Creature keys of the tasks a session held since the previous call: the active (or just stopped) session's task,
	 * a completed session held for late loot, and any task a session took since then, even if that session has ended
	 * already. {@link AssignmentWatch} uses them, once per settled tick, to tell played assignments from ones that
	 * ended without a session. Decided in one call, so a Play click can't fall between two reads.
	 */
	public synchronized Set<Integer> takeSessionTaskKeys()
	{
		final Set<Integer> keys = new HashSet<>(takenTaskKeys);
		takenTaskKeys.clear();
		if (state != State.IDLE && hasSessionTask)
		{
			keys.add(sessionCreatureId);
		}
		if (pendingCompletion != null && pendingCompletion.hasTask())
		{
			keys.add(pendingCompletion.getCreatureId());
		}
		return keys;
	}

	/**
	 * Adds NPC loot to the session, only while it is RUNNING. Loot received while IDLE, PAUSED or STOPPED is not
	 * attributed to any session. While a completed session is held for late loot (see {@link #holdCompletion()}), all
	 * loot goes to that session instead, even if a new session has been started meanwhile.
	 *
	 * <p>Loot is deliberately not filtered by the task monster: any NPC loot received while the session runs counts,
	 * because drop sources do not map reliably to Slayer task categories (superiors, variants, bosses and wildcards
	 * share or rename NPCs) and the player controls the session with Play / Pause.</p>
	 *
	 * @return whether the loot was recorded
	 */
	public synchronized boolean addLoot(ItemTally items)
	{
		return addLoot(items, null);
	}

	/**
	 * Adds NPC loot and its notable stacks (see {@link NotableDrops}) to the session, by the rules of
	 * {@link #addLoot(ItemTally)}: both go to the same session, or neither is recorded.
	 *
	 * @param items        the loot
	 * @param notableDrops the loot's notable stacks, or {@code null} when none
	 * @return whether the loot was recorded
	 */
	public synchronized boolean addLoot(ItemTally items, List<NotableDrop> notableDrops)
	{
		if (items == null || items.isEmpty())
		{
			return false;
		}
		if (pendingCompletion != null)
		{
			pendingCompletion = pendingCompletion.withAddedLoot(items, notableDrops);
			return true;
		}
		if (state != State.RUNNING)
		{
			return false;
		}
		loot.merge(items);
		this.notableDrops = NotableDrops.add(this.notableDrops, notableDrops);
		return true;
	}

	/**
	 * Whether loot would be recorded now ({@link #addLoot(ItemTally)}): the session is RUNNING, or a completed session
	 * is held for late loot. Lets the caller skip pricing loot that would not be recorded.
	 */
	public synchronized boolean isRecordingLoot()
	{
		return pendingCompletion != null || state == State.RUNNING;
	}

	/**
	 * Adds a pet drop ({@link PetMessage}) to the session's notable drops, by the same rules as loot
	 * ({@link #addLoot(ItemTally)}): while RUNNING, or to a completed session held for late loot, since the pet may
	 * come from the final kill.
	 *
	 * @return whether it was recorded
	 */
	public synchronized boolean addPet(NotableDrop pet)
	{
		if (pet == null)
		{
			return false;
		}
		final List<NotableDrop> added = Collections.singletonList(pet);
		if (pendingCompletion != null)
		{
			pendingCompletion = pendingCompletion.withAddedLoot(null, added);
			return true;
		}
		if (state != State.RUNNING)
		{
			return false;
		}
		notableDrops = NotableDrops.add(notableDrops, added);
		return true;
	}

	/**
	 * Counts a boss kill count message ({@link KillCountMessage}) for the session, by the same rules as loot
	 * ({@link #addLoot(ItemTally)}): while RUNNING, or for a completed session held for late loot, since the message
	 * may come with the final kill. Whether the boss belongs to the task, and the counts, follow
	 * {@link BossKillCounts}; all of it is decided here in one step.
	 *
	 * @param boss the boss name the message gives
	 * @param kc   the count the message gives
	 * @return whether it was counted for a session (its count may still have been a repeat)
	 */
	public synchronized boolean addBossKillCount(String boss, int kc)
	{
		if (pendingCompletion != null)
		{
			if (!BossKillCounts.appliesTo(pendingCompletion.getMonster(), pendingCompletion.getCreatureId(), boss))
			{
				return false;
			}
			pendingCompletion = pendingCompletion.withBossKillCount(boss, kc);
			return true;
		}
		if (state != State.RUNNING || !BossKillCounts.appliesTo(sessionMonster, sessionCreatureId, boss))
		{
			return false;
		}
		bossKillCounts = BossKillCounts.add(bossKillCounts, boss, kc);
		return true;
	}

	/**
	 * Adds supplies used to the session, only while it is RUNNING. Unlike loot, supplies are never added to a completed
	 * session held for late loot (see {@link #holdCompletion()}): it stopped running when the task completed.
	 *
	 * @param used     items used; potions in doses
	 * @param returned ammo or cannonballs that came back (picked up, or unloaded from the cannon); each reduces that
	 *                 item's used quantity, never below zero
	 * @param doses    doses per item for the potion ids in {@code used}, or {@code null}
	 * @return whether the session changed
	 */
	public synchronized boolean addSupplies(ItemTally used, ItemTally returned, Map<Integer, Integer> doses)
	{
		if (state != State.RUNNING)
		{
			return false;
		}
		boolean changed = false;
		if (used != null && !used.isEmpty())
		{
			supplies.merge(used);
			putDoses(supplyDoses, doses);
			changed = true;
		}
		if (returned != null)
		{
			for (int id : returned.itemIds())
			{
				changed |= supplies.subtract(id, returned.get(id)) > 0;
			}
		}
		return changed;
	}

	/**
	 * Counts a superior Slayer monster spawn, only while the session is RUNNING. Unlike loot, a spawn is never added to
	 * a completed session held for late loot (see {@link #holdCompletion()}): it stopped running when the task
	 * completed, and a superior that appears after that belongs to no task.
	 *
	 * @return whether it was counted
	 */
	public synchronized boolean addSuperiorSpawn()
	{
		if (state != State.RUNNING)
		{
			return false;
		}
		counters = counters.toBuilder().superiorsSpawned(saturatedIncrement(counters.getSuperiorsSpawned())).build();
		return true;
	}

	/**
	 * Counts a bank trip, only while the session is RUNNING (never for a held completed session). Which bank openings
	 * are trips is decided by {@link BankTripCounter}.
	 *
	 * @return whether it was counted
	 */
	public synchronized boolean addBankTrip()
	{
		if (state != State.RUNNING)
		{
			return false;
		}
		counters = counters.toBuilder().bankTrips(saturatedIncrement(counters.getBankTrips())).build();
		return true;
	}

	/**
	 * Counts a death of the local player, only while the session is RUNNING (never for a held completed session, like
	 * {@link #addSuperiorSpawn()}). Deaths are only counted; what a death costs is not tracked.
	 *
	 * @return whether it was counted
	 */
	public synchronized boolean addDeath()
	{
		if (state != State.RUNNING)
		{
			return false;
		}
		counters = counters.toBuilder().deaths(saturatedIncrement(counters.getDeaths())).build();
		return true;
	}

	/**
	 * Counts the death of an NPC the player damaged under its name ({@link CatacombsVariants#tallyName}), only while
	 * the session is RUNNING (never for a held completed session, like {@link #addDeath()}). The kill count itself
	 * still comes from the task count; this tally only tells a Catacombs variant apart.
	 *
	 * @param name the name to tally, or {@code null} for none
	 * @return whether it was tallied
	 */
	public synchronized boolean addNpcKill(String name)
	{
		if (state != State.RUNNING || TaskStatistics.normalize(name) == null)
		{
			return false;
		}
		final List<NpcKills> updated = NpcKillTally.add(npcKills, name);
		final boolean tallied = NpcKillTally.total(updated) != NpcKillTally.total(npcKills);
		npcKills = updated;
		return tallied;
	}

	/** A copy of the session's kills per NPC name so far, in the order first seen. */
	public synchronized List<NpcKills> getNpcKills()
	{
		return new ArrayList<>(npcKills);
	}

	/**
	 * Which records the observed task's statistics use, while the active session is on the observed task (else
	 * {@link TaskSelection#ANY}):
	 * <ul>
	 * <li>the version the session's kills decide ({@link CatacombsVariants#selection} of its kills per NPC name
	 * and its task kills, the same numbers the saved record's variant is decided from);</li>
	 * <li>cannon tasks only once the session has fired a cannonball ({@link CannonTasks#fired} of its supplies,
	 * as the saved record is classified), else tasks with and without a cannon pooled. It never narrows to tasks
	 * without a cannon (owner decision): a cannon can still be set up later in the task.</li>
	 * </ul>
	 */
	public synchronized TaskSelection getCurrentTaskSelection()
	{
		if (!isSessionOnCurrentTask())
		{
			return TaskSelection.ANY;
		}
		return CatacombsVariants.selection(sessionMonster, npcKills, kills)
			.withCannon(CannonTasks.fired(supplies) ? Boolean.TRUE : null);
	}

	private boolean isSessionOnCurrentTask()
	{
		return (state == State.RUNNING || state == State.PAUSED) && hasSessionTask && taskObserved
			&& currentCreatureId == sessionCreatureId
			&& (currentMonster == null || sessionMonster == null || currentMonster.equals(sessionMonster));
	}

	/**
	 * Records this tick's XP reading of every skill and, only while the session is RUNNING, adds the XP gained since
	 * the previous reading to it. Decided in one synchronized call, so a Play or Pause click can't fall between the
	 * reading and the decision. The previous reading is the baseline whatever the state, so after Play or Resume the
	 * first counted XP is what arrived after the click (the same way kills count from the task count read before
	 * Play). Nothing is counted when:
	 * <ul>
	 *     <li>the session is not RUNNING: idle, paused, stopped, or a completion held for late loot (it stopped on its
	 *     completion tick, so XP after that tick never reaches it);</li>
	 *     <li>there is no previous reading: the first reading after {@link #resetXpBaseline()} (login, world hop, lost
	 *     connection, account switch) or after an unsynced one only sets the baseline, so the stats a login sends are
	 *     never counted;</li>
	 *     <li>{@code reading} is {@code null} (the caller's sign that the stats are not synced); the baseline is
	 *     dropped;</li>
	 *     <li>any skill went down since the previous reading ({@link SkillXp#gains}): the readings can't be compared,
	 *     and this one becomes the baseline.</li>
	 * </ul>
	 *
	 * @param reading every skill's XP, keyed as {@link SkillXp} keys them, or {@code null} when not synced
	 * @return whether XP was added to the session
	 */
	public synchronized boolean observeXp(Map<String, Integer> reading)
	{
		final Map<String, Integer> previous = xpReading;
		xpReading = reading == null ? null : new TreeMap<>(reading);
		if (reading == null || previous == null || state != State.RUNNING)
		{
			return false;
		}
		final Map<String, Long> gains = SkillXp.gains(previous, reading);
		if (gains == null || gains.isEmpty())
		{
			return false;
		}
		SkillXp.add(xpGained, gains);
		return true;
	}

	/**
	 * Forgets the XP baseline (on login, world hop, lost connection or account switch), so the next reading of
	 * {@link #observeXp} only sets it. The session's XP so far is kept.
	 */
	public synchronized void resetXpBaseline()
	{
		xpReading = null;
	}

	/** A copy of the XP gained per skill in the started (running, paused or just stopped) session. */
	public synchronized Map<String, Long> getXpGained()
	{
		return new TreeMap<>(xpGained);
	}

	/**
	 * Sets (or, with an empty or {@code null} note, clears) the note of the given session: the running or paused
	 * session, or a completed session still held for late loot (see {@link #holdCompletion()}), so a note committed
	 * just as the task completes still reaches the saved record. The note is {@link TaskNote#normalize normalized}.
	 *
	 * @param sessionId the session the note was typed for; another session's note is never changed
	 * @return whether a session took the note; {@code false} when it is no longer active or held
	 */
	public synchronized boolean setNote(String sessionId, String note)
	{
		if (sessionId == null)
		{
			return false;
		}
		final String normalized = TaskNote.normalize(note);
		if ((state == State.RUNNING || state == State.PAUSED) && sessionId.equals(this.sessionId))
		{
			this.note = normalized;
			return true;
		}
		if (pendingCompletion != null && sessionId.equals(pendingCompletion.getSessionId()))
		{
			pendingCompletion = pendingCompletion.withNote(normalized);
			return true;
		}
		return false;
	}

	/**
	 * Sets the Slayer points earned and the streak of a completed session: the running or paused session once
	 * {@link #observe} reported it {@link ObserveResult#COMPLETED}, or the completed session held for late loot (see
	 * {@link #holdCompletion()}), so the points can still be corrected while it is held. Recorded whether the session
	 * was running or paused when the task completed: they belong to the task, not to the time tracked.
	 *
	 * @param sessionId    the completed session; another session is never changed
	 * @param pointsEarned points the completion gave, or {@code null} when not trusted
	 * @param taskStreak   streak after the completion, or {@code null} when unknown
	 * @return whether a completed session took them; {@code false} when it is no longer active or held
	 */
	public synchronized boolean setCompletionRewards(String sessionId, Integer pointsEarned, Integer taskStreak)
	{
		if (sessionId == null)
		{
			return false;
		}
		if ((state == State.RUNNING || state == State.PAUSED) && completed && sessionId.equals(this.sessionId))
		{
			sessionContext = sessionContext.withRewards(pointsEarned, taskStreak);
			return true;
		}
		if (pendingCompletion != null && sessionId.equals(pendingCompletion.getSessionId()))
		{
			pendingCompletion = pendingCompletion.withContext(
				pendingCompletion.getContext().withRewards(pointsEarned, taskStreak));
			return true;
		}
		return false;
	}

	/** Assignment of the latest observed task (master and area), or {@link TaskContext#NONE}. */
	public synchronized TaskContext getCurrentContext()
	{
		return currentContext;
	}

	/** Note of the started (running, paused or just stopped) session, or {@code null}. */
	public synchronized String getNote()
	{
		return note;
	}

	/** Superior spawns counted in the started (running, paused or just stopped) session. */
	public synchronized int getSuperiorsSpawned()
	{
		return counters.getSuperiorsSpawned();
	}

	/** Bank trips counted in the started (running, paused or just stopped) session. */
	public synchronized int getBankTrips()
	{
		return counters.getBankTrips();
	}

	/** Deaths counted in the started (running, paused or just stopped) session. */
	public synchronized int getDeaths()
	{
		return counters.getDeaths();
	}

	private static int saturatedIncrement(int n)
	{
		return n == Integer.MAX_VALUE ? n : n + 1;
	}

	/** A copy of the session's supplies used so far (potions in doses). */
	public synchronized ItemTally getSupplies()
	{
		return new ItemTally(supplies);
	}

	/** A copy of the doses-per-item map for potion supplies. */
	public synchronized Map<Integer, Integer> getSupplyDoses()
	{
		return new TreeMap<>(supplyDoses);
	}

	/** Copies valid (positive) dose entries from {@code from} into {@code into}. */
	static void putDoses(Map<Integer, Integer> into, Map<Integer, Integer> from)
	{
		if (from == null)
		{
			return;
		}
		for (Map.Entry<Integer, Integer> e : from.entrySet())
		{
			if (e.getKey() != null && e.getValue() != null && e.getValue() > 0)
			{
				into.put(e.getKey(), e.getValue());
			}
		}
	}

	/** A copy of the session's loot so far. */
	public synchronized ItemTally getLoot()
	{
		return new ItemTally(loot);
	}

	/** A copy of the session's notable drops so far, in the order they arrived. */
	public synchronized List<NotableDrop> getNotableDrops()
	{
		return new ArrayList<>(notableDrops);
	}

	/** A copy of the session's boss kill counts so far, in the order first seen. */
	public synchronized List<BossKillCount> getBossKillCounts()
	{
		return new ArrayList<>(bossKillCounts);
	}

	/** The active session's task name, or {@code null} when unknown or no session is active. */
	public synchronized String getSessionMonster()
	{
		return sessionMonster;
	}

	/**
	 * The active (running or paused) session, or {@code null}. Does not change state.
	 */
	public synchronized Summary activeSummary()
	{
		return state == State.RUNNING || state == State.PAUSED ? summary() : null;
	}

	/**
	 * A small snapshot of the active (running or paused) session's task for the overlay, read in one call so its parts
	 * can't tear, or {@code null} when no session is active or it has no task yet. A completed session held for late
	 * loot is not active. The kills left are the latest observed count only while the task has been observed for this
	 * login and the observed task is the session's; otherwise (after logout until the next reading, or while a cleared
	 * task waits for its completion message) they are {@link LiveTask#UNKNOWN_REMAINING}. Cheap: copies no tallies.
	 */
	public synchronized LiveTask liveTask()
	{
		if ((state != State.RUNNING && state != State.PAUSED) || !hasSessionTask)
		{
			return null;
		}
		boolean matches = taskObserved && currentRemaining > 0 && currentCreatureId == sessionCreatureId
			&& (currentMonster == null || sessionMonster == null || currentMonster.equals(sessionMonster));
		return new LiveTask(state, sessionId, sessionMonster, sessionCreatureId, kills, getActiveMillis(),
			matches ? currentRemaining : LiveTask.UNKNOWN_REMAINING);
	}

	/**
	 * Whether the active session was restored from disk and has not been resumed yet.
	 */
	public synchronized boolean isRestoredPending()
	{
		return restoredPending && state == State.PAUSED;
	}

	/**
	 * Records the latest task state read from the game, without an original (assigned) amount.
	 */
	public synchronized ObserveResult observe(String monster, int creatureId, int remaining, boolean completionSignaled)
	{
		return observe(monster, creatureId, remaining, UNKNOWN_AMOUNT, completionSignaled);
	}

	/**
	 * Records the latest task state read from the game, without its assignment (master and area).
	 */
	public synchronized ObserveResult observe(String monster, int creatureId, int remaining, int originalAmount,
		boolean completionSignaled)
	{
		return observe(monster, creatureId, remaining, originalAmount, TaskContext.NONE, completionSignaled);
	}

	/**
	 * Records the latest task state read from the game.
	 *
	 * @param monster            task monster name, or {@code null} when there is no task or the name is unknown
	 * @param creatureId         task creature identifier, or {@link #NO_CREATURE}
	 * @param remaining          remaining kills on the task (0 when there is no task)
	 * @param originalAmount     the task's assigned size, or {@link #UNKNOWN_AMOUNT}
	 * @param assignment         who assigned the task ({@link TaskContext#assignment}), or {@link TaskContext#NONE};
	 *                           a session takes it with the task and fills in parts that were unknown then from later
	 *                           readings of the same task
	 * @param completionSignaled whether the game signalled a task completion since the previous observation
	 */
	public synchronized ObserveResult observe(String monster, int creatureId, int remaining, int originalAmount,
		TaskContext assignment, boolean completionSignaled)
	{
		return observe(monster, creatureId, remaining, originalAmount, assignment, LocationTally.NO_REGION,
			completionSignaled);
	}

	/**
	 * Records the latest task state read from the game, with the map region the local player is in. Kills counted by
	 * this observation are tallied in {@code region} ({@link LocationTally}), in the same step, so they follow exactly
	 * the rules of the kills themselves (RUNNING only; a final drop to zero is tallied where it dropped once the
	 * completion confirms it). See {@link #observe(String, int, int, int, TaskContext, boolean)} for the rest.
	 *
	 * @param region map region id of the local player's tile ({@code WorldPoint#getRegionID}, instances as their
	 *               template), or {@link LocationTally#NO_REGION} when unknown (the kills still count, untallied)
	 */
	public synchronized ObserveResult observe(String monster, int creatureId, int remaining, int originalAmount,
		TaskContext assignment, int region, boolean completionSignaled)
	{
		remaining = Math.max(0, remaining);
		taskObserved = true;
		currentRegion = LocationTally.isRegion(region) ? region : LocationTally.NO_REGION;
		if (remaining > 0)
		{
			currentMonster = monster;
			currentCreatureId = creatureId;
			currentOriginalAmount = originalAmount > 0 ? originalAmount : UNKNOWN_AMOUNT;
			currentContext = assignment == null ? TaskContext.NONE
				: TaskContext.of(assignment.getMaster(), assignment.getArea(), assignment.getLocation(), null, null);
		}
		else
		{
			currentMonster = null;
			currentCreatureId = NO_CREATURE;
			currentOriginalAmount = UNKNOWN_AMOUNT;
			currentContext = TaskContext.NONE;
		}
		currentRemaining = remaining;

		if (state != State.RUNNING && state != State.PAUSED)
		{
			return ObserveResult.NONE;
		}

		if (!hasSessionTask)
		{
			if (remaining > 0)
			{
				// Session started without a task; the first observed task becomes the baseline.
				adoptCurrentTask();
			}
			return ObserveResult.NONE;
		}

		if (completed)
		{
			return remaining > 0 && !sameTask(monster, creatureId) ? ObserveResult.TASK_CHANGED : ObserveResult.NONE;
		}

		if (awaitingCompletion)
		{
			if (remaining > 0)
			{
				if (sameTask(monster, creatureId) && isTransientZero(remaining))
				{
					// The same task came back (a transient zero): the drop was not real.
					awaitingCompletion = false;
					pendingFinalKills = 0;
					pendingFinalRegion = LocationTally.NO_REGION;
					zeroObservations = 0;
					baseline = remaining;
					updateSessionTaskDetails();
					return ObserveResult.NONE;
				}
				// A new (or cancelled and reassigned) task appeared after the old one was cleared without completing.
				return ObserveResult.TASK_CHANGED;
			}
			zeroObservations++;
			if (completionSignaled)
			{
				countKills(pendingFinalKills, pendingFinalRegion);
				pendingFinalKills = 0;
				pendingFinalRegion = LocationTally.NO_REGION;
				awaitingCompletion = false;
				completed = true;
				return ObserveResult.COMPLETED;
			}
			return ObserveResult.NONE;
		}

		if (remaining > 0 && !sameTask(monster, creatureId))
		{
			return ObserveResult.TASK_CHANGED;
		}

		if (remaining == 0)
		{
			int drop = baseline;
			baseline = 0;
			if (completionSignaled)
			{
				if (state == State.RUNNING)
				{
					countKills(drop, region);
				}
				completed = true;
				return ObserveResult.COMPLETED;
			}
			pendingFinalKills = state == State.RUNNING ? drop : 0;
			pendingFinalRegion = region;
			awaitingCompletion = true;
			preZeroBaseline = drop;
			zeroObservations = 1;
			return ObserveResult.TASK_CLEARED;
		}

		if (remaining < baseline && state == State.RUNNING)
		{
			countKills(baseline - remaining, region);
		}
		// Increases (or decreases while paused) only move the baseline.
		baseline = remaining;
		updateSessionTaskDetails();
		return ObserveResult.NONE;
	}

	/**
	 * Counts {@code n} kills for the session and tallies them in {@code region}. When the session's task has no assigned
	 * area name (Konar, Wilderness) and the region is in the tally (not a new one past
	 * {@link LocationTally#MAX_REGIONS}), the region is kept for {@link #takeRegionToName()}.
	 */
	private void countKills(int n, int region)
	{
		if (n <= 0)
		{
			return;
		}
		kills += n;
		locationKills = LocationTally.add(locationKills, region, n);
		if (sessionContext.getLocation() == null && LocationTally.contains(locationKills, region))
		{
			regionToName = region;
		}
	}

	/**
	 * Takes the region of the latest kill counted since the previous call, if its task has no assigned area name, so the
	 * caller can ask the player to name it; {@link LocationTally#NO_REGION} when there is none. Taken once.
	 */
	public synchronized int takeRegionToName()
	{
		final int region = regionToName;
		regionToName = LocationTally.NO_REGION;
		return region;
	}

	/** A copy of the session's kills per region so far, in the order first seen. */
	public synchronized List<LocationKills> getLocationKills()
	{
		return new ArrayList<>(locationKills);
	}

	/** The started session's region with the most kills ({@link LocationTally#dominantRegion}), or {@code null}. */
	public synchronized Integer getDominantRegion()
	{
		return LocationTally.dominantRegion(locationKills);
	}

	/**
	 * The map region the player was in at the latest observation ({@link LocationTally#NO_REGION} when unknown, or
	 * after {@link #clearCurrentTask}).
	 */
	public synchronized int getCurrentRegion()
	{
		return currentRegion;
	}

	/** The started session's assigned area name (Konar, Wilderness), or {@code null}. */
	public synchronized String getSessionTaskLocation()
	{
		return sessionContext.getLocation();
	}

	public synchronized State getState()
	{
		return state;
	}

	public synchronized boolean isTaskObserved()
	{
		return taskObserved;
	}

	public synchronized String getCurrentMonster()
	{
		return currentMonster;
	}

	public synchronized int getCurrentCreatureId()
	{
		return currentCreatureId;
	}

	public synchronized int getCurrentRemaining()
	{
		return currentRemaining;
	}

	/** Id of the started (running, paused or just stopped) session, or {@code null} when there is none. */
	public synchronized String getSessionId()
	{
		return sessionId;
	}

	public synchronized int getKills()
	{
		return kills;
	}

	public synchronized boolean isCompleted()
	{
		return completed;
	}

	public synchronized boolean hasSessionTask()
	{
		return hasSessionTask;
	}

	public synchronized long getSessionAccountHash()
	{
		return sessionAccountHash;
	}

	/**
	 * Active (running) time so far, including the current running stretch.
	 */
	public synchronized long getActiveMillis()
	{
		if (state == State.RUNNING)
		{
			return accumulatedMillis + Math.max(0, clock.getAsLong() - runningSince);
		}
		return accumulatedMillis;
	}

	/**
	 * Snapshot of the session. Meaningful once a session has been started; typically read after {@link #stop()}.
	 */
	public synchronized Summary summary()
	{
		return Summary.builder()
			.sessionId(sessionId)
			.accountHash(sessionAccountHash)
			.monster(sessionMonster)
			.creatureId(sessionCreatureId)
			.hasTask(hasSessionTask)
			.initialAmount(initialAmount)
			.originalAmount(originalAmount)
			.kills(kills)
			.activeMillis(getActiveMillis())
			.startedAt(startedAt)
			.endedAt(state == State.STOPPED ? endedAt : clock.getAsLong())
			.completed(completed)
			.resumeRemaining(awaitingCompletion ? preZeroBaseline : baseline)
			.sessionLoot(SessionLoot.builder()
				.loot(loot)
				.supplies(supplies)
				.supplyDoses(supplyDoses)
				.notableDrops(notableDrops)
				.bossKillCounts(bossKillCounts)
				.build())
			.counters(counters)
			.note(note)
			.context(sessionContext)
			.xpGained(xpGained)
			.locationKills(locationKills)
			.npcKills(npcKills)
			.build();
	}

	private void adoptCurrentTask()
	{
		hasSessionTask = true;
		sessionMonster = currentMonster;
		sessionCreatureId = currentCreatureId;
		takenTaskKeys.add(sessionCreatureId);
		initialAmount = currentRemaining;
		baseline = currentRemaining;
		originalAmount = currentOriginalAmount;
		sessionContext = currentContext;
	}

	/** Takes what the session didn't know yet from the same task's latest reading: its size and its assignment. */
	private void updateSessionTaskDetails()
	{
		if (currentOriginalAmount != UNKNOWN_AMOUNT)
		{
			originalAmount = currentOriginalAmount;
		}
		sessionContext = sessionContext.fillAssignment(currentContext);
	}

	private boolean isTransientZero(int remaining)
	{
		if (zeroObservations <= MAX_TRANSIENT_ZERO_OBSERVATIONS)
		{
			return true;
		}
		return originalAmount != UNKNOWN_AMOUNT && currentOriginalAmount == originalAmount
			&& remaining <= preZeroBaseline;
	}

	private boolean sameTask(String monster, int creatureId)
	{
		if (creatureId != sessionCreatureId)
		{
			return false;
		}
		if (monster == null || sessionMonster == null)
		{
			// Name lookup failed on one side; the creature id is the best evidence available.
			if (sessionMonster == null)
			{
				sessionMonster = monster;
			}
			return true;
		}
		return monster.equals(sessionMonster);
	}

	private void accumulate(long now)
	{
		accumulatedMillis += Math.max(0, now - runningSince);
	}

	private void clearSession()
	{
		sessionId = null;
		restoredPending = false;
		sessionAccountHash = NO_ACCOUNT;
		sessionMonster = null;
		sessionCreatureId = NO_CREATURE;
		hasSessionTask = false;
		initialAmount = 0;
		originalAmount = UNKNOWN_AMOUNT;
		baseline = 0;
		kills = 0;
		pendingFinalKills = 0;
		pendingFinalRegion = LocationTally.NO_REGION;
		locationKills = new ArrayList<>();
		npcKills = new ArrayList<>();
		preZeroBaseline = 0;
		zeroObservations = 0;
		awaitingCompletion = false;
		completed = false;
		loot.clear();
		supplies.clear();
		supplyDoses.clear();
		counters = SessionCounters.ZERO;
		notableDrops = new ArrayList<>();
		bossKillCounts = new ArrayList<>();
		note = null;
		xpGained.clear();
		sessionContext = TaskContext.NONE;
		accumulatedMillis = 0;
		runningSince = 0;
		startedAt = 0;
		endedAt = 0;
	}

	/**
	 * Immutable snapshot of a session. Build one with {@link #builder()}, or change a copy with {@link #toBuilder()};
	 * the builder starts from an empty session (no id, {@link #NO_ACCOUNT}, {@link #NO_CREATURE},
	 * {@link #UNKNOWN_AMOUNT}, everything else 0 or empty, counters at 0 and XP gained known and empty).
	 */
	public static final class Summary
	{
		private final String sessionId;
		private final long accountHash;
		private final String monster;
		private final int creatureId;
		private final boolean hasTask;
		private final int initialAmount;
		private final int originalAmount;
		private final int kills;
		private final long activeMillis;
		private final long startedAt;
		private final long endedAt;
		private final boolean completed;
		private final int resumeRemaining;
		private final SessionLoot sessionLoot;
		private final SessionCounters counters;
		private final String note;
		private final TaskContext context;
		private final Map<String, Long> xpGained;
		private final List<LocationKills> locationKills;
		private final List<NpcKills> npcKills;

		private Summary(Builder b)
		{
			this.sessionId = b.sessionId;
			this.accountHash = b.accountHash;
			this.monster = b.monster;
			this.creatureId = b.creatureId;
			this.hasTask = b.hasTask;
			this.initialAmount = b.initialAmount;
			this.originalAmount = b.originalAmount;
			this.kills = b.kills;
			this.activeMillis = b.activeMillis;
			this.startedAt = b.startedAt;
			this.endedAt = b.endedAt;
			this.completed = b.completed;
			this.resumeRemaining = b.resumeRemaining;
			this.sessionLoot = b.sessionLoot == null ? SessionLoot.EMPTY : b.sessionLoot;
			this.counters = b.counters == null ? SessionCounters.ZERO : b.counters;
			this.note = b.note;
			this.context = b.context == null ? TaskContext.NONE : b.context;
			this.xpGained = b.xpGained == null ? null : Collections.unmodifiableMap(SkillXp.sanitize(b.xpGained));
			this.locationKills = Collections.unmodifiableList(LocationTally.sanitize(b.locationKills));
			this.npcKills = Collections.unmodifiableList(NpcKillTally.sanitize(b.npcKills));
		}

		/** A builder for an empty session; see the class comment. */
		static Builder builder()
		{
			return new Builder();
		}

		/** A builder holding this summary's values. */
		Builder toBuilder()
		{
			return new Builder()
				.sessionId(sessionId)
				.accountHash(accountHash)
				.monster(monster)
				.creatureId(creatureId)
				.hasTask(hasTask)
				.initialAmount(initialAmount)
				.originalAmount(originalAmount)
				.kills(kills)
				.activeMillis(activeMillis)
				.startedAt(startedAt)
				.endedAt(endedAt)
				.completed(completed)
				.resumeRemaining(resumeRemaining)
				.sessionLoot(sessionLoot)
				.counters(counters)
				.note(note)
				.context(context)
				.xpGained(xpGained)
				.locationKills(locationKills)
				.npcKills(npcKills);
		}

		/** Builds a {@link Summary}; {@link #build()} copies what it is given, so the builder can be reused. */
		static final class Builder
		{
			private String sessionId;
			private long accountHash = NO_ACCOUNT;
			private String monster;
			private int creatureId = NO_CREATURE;
			private boolean hasTask;
			private int initialAmount;
			private int originalAmount = UNKNOWN_AMOUNT;
			private int kills;
			private long activeMillis;
			private long startedAt;
			private long endedAt;
			private boolean completed;
			private int resumeRemaining;
			private SessionLoot sessionLoot = SessionLoot.EMPTY;
			private SessionCounters counters = SessionCounters.ZERO;
			private String note;
			private TaskContext context = TaskContext.NONE;
			private Map<String, Long> xpGained = Collections.emptyMap();
			private List<LocationKills> locationKills;
			private List<NpcKills> npcKills;

			private Builder()
			{
			}

			/** Session id (also used as the ledger record id), or {@code null}. */
			Builder sessionId(String sessionId)
			{
				this.sessionId = sessionId;
				return this;
			}

			Builder accountHash(long accountHash)
			{
				this.accountHash = accountHash;
				return this;
			}

			Builder monster(String monster)
			{
				this.monster = monster;
				return this;
			}

			Builder creatureId(int creatureId)
			{
				this.creatureId = creatureId;
				return this;
			}

			Builder hasTask(boolean hasTask)
			{
				this.hasTask = hasTask;
				return this;
			}

			Builder initialAmount(int initialAmount)
			{
				this.initialAmount = initialAmount;
				return this;
			}

			/** Assigned task size, or {@link #UNKNOWN_AMOUNT}. */
			Builder originalAmount(int originalAmount)
			{
				this.originalAmount = originalAmount;
				return this;
			}

			Builder kills(int kills)
			{
				this.kills = kills;
				return this;
			}

			Builder activeMillis(long activeMillis)
			{
				this.activeMillis = activeMillis;
				return this;
			}

			Builder startedAt(long startedAt)
			{
				this.startedAt = startedAt;
				return this;
			}

			Builder endedAt(long endedAt)
			{
				this.endedAt = endedAt;
				return this;
			}

			Builder completed(boolean completed)
			{
				this.completed = completed;
				return this;
			}

			/** Remaining task count to resume from (the count before a pending drop to zero, if any). */
			Builder resumeRemaining(int resumeRemaining)
			{
				this.resumeRemaining = resumeRemaining;
				return this;
			}

			/** Loot, supplies, notable drops and boss kill counts; {@code null} is {@link SessionLoot#EMPTY}. */
			Builder sessionLoot(SessionLoot sessionLoot)
			{
				this.sessionLoot = sessionLoot;
				return this;
			}

			/** Superior spawns, bank trips and deaths; {@code null} is {@link SessionCounters#ZERO}. */
			Builder counters(SessionCounters counters)
			{
				this.counters = counters;
				return this;
			}

			/** The player's note, or {@code null}. */
			Builder note(String note)
			{
				this.note = note;
				return this;
			}

			/** The task's assignment and completion rewards; {@code null} is {@link TaskContext#NONE}. */
			Builder context(TaskContext context)
			{
				this.context = context;
				return this;
			}

			/**
			 * XP gained per skill ({@link SkillXp}), or {@code null} when unknown (a snapshot from before XP was
			 * tracked).
			 */
			Builder xpGained(Map<String, Long> xpGained)
			{
				this.xpGained = xpGained;
				return this;
			}

			/** Counted kills per map region in the order first seen ({@link LocationTally}), or {@code null}. */
			Builder locationKills(List<LocationKills> locationKills)
			{
				this.locationKills = locationKills;
				return this;
			}

			/** Kills per NPC name in the order first seen ({@link NpcKillTally}), or {@code null}. */
			Builder npcKills(List<NpcKills> npcKills)
			{
				this.npcKills = npcKills;
				return this;
			}

			Summary build()
			{
				return new Summary(this);
			}
		}

		/** A copy of this summary with extra loot (may be {@code null}) and notable drops (likewise) added. */
		Summary withAddedLoot(ItemTally extra, List<NotableDrop> extraNotableDrops)
		{
			return toBuilder().sessionLoot(sessionLoot.withAddedLoot(extra, extraNotableDrops)).build();
		}

		/** A copy of this summary with one more boss kill count message counted ({@link BossKillCounts#add}). */
		Summary withBossKillCount(String boss, int kc)
		{
			return toBuilder().sessionLoot(sessionLoot.withBossKillCount(boss, kc)).build();
		}

		/** A copy of this summary with the given note. */
		Summary withNote(String newNote)
		{
			return toBuilder().note(newNote).build();
		}

		/** A copy of this summary with the given task context. */
		Summary withContext(TaskContext newContext)
		{
			return toBuilder().context(newContext).build();
		}

		/** The task's assignment and, for a completed task, its points earned and streak (never {@code null}). */
		public TaskContext getContext()
		{
			return context;
		}

		/** Session id (also used as the ledger record id), or {@code null} if no session was started. */
		public String getSessionId()
		{
			return sessionId;
		}

		/** Remaining task count to resume from (the count before a pending drop to zero, if any). */
		public int getResumeRemaining()
		{
			return resumeRemaining;
		}

		/** Account that started the session, or {@link #NO_ACCOUNT}. */
		public long getAccountHash()
		{
			return accountHash;
		}

		public String getMonster()
		{
			return monster;
		}

		public int getCreatureId()
		{
			return creatureId;
		}

		public boolean hasTask()
		{
			return hasTask;
		}

		public int getInitialAmount()
		{
			return initialAmount;
		}

		/** Assigned task size, or {@link #UNKNOWN_AMOUNT}. */
		public int getOriginalAmount()
		{
			return originalAmount;
		}

		public int getKills()
		{
			return kills;
		}

		public long getActiveMillis()
		{
			return activeMillis;
		}

		public long getStartedAt()
		{
			return startedAt;
		}

		public long getEndedAt()
		{
			return endedAt;
		}

		public boolean isCompleted()
		{
			return completed;
		}

		/** The session's loot, supplies, notable drops and boss kill counts (never {@code null}). */
		public SessionLoot getSessionLoot()
		{
			return sessionLoot;
		}

		/** The session's superior spawns, bank trips and deaths (never {@code null}; each {@code null} when unknown). */
		public SessionCounters getCounters()
		{
			return counters;
		}

		/** A copy of the session's loot (never {@code null}). */
		public ItemTally getLoot()
		{
			return sessionLoot.getLoot();
		}

		/** A copy of the session's supplies used, potions in doses (never {@code null}). */
		public ItemTally getSupplies()
		{
			return sessionLoot.getSupplies();
		}

		/** A copy of the doses per item for potion ids in {@link #getSupplies()} (never {@code null}). */
		public Map<Integer, Integer> getSupplyDoses()
		{
			return sessionLoot.getSupplyDoses();
		}

		/** Superior spawns counted while running, or {@code null} when unknown. */
		public Integer getSuperiorsSpawned()
		{
			return counters.getSuperiorsSpawned();
		}

		/** Bank trips counted while running, or {@code null} when unknown. */
		public Integer getBankTrips()
		{
			return counters.getBankTrips();
		}

		/** Deaths counted while running, or {@code null} when unknown. */
		public Integer getDeaths()
		{
			return counters.getDeaths();
		}

		/** The player's note, or {@code null}. */
		public String getNote()
		{
			return note;
		}

		/** The session's notable drops in the order they arrived (never {@code null}; unmodifiable). */
		public List<NotableDrop> getNotableDrops()
		{
			return sessionLoot.getNotableDrops();
		}

		/** The session's boss kill counts in the order first seen (never {@code null}; unmodifiable). */
		public List<BossKillCount> getBossKillCounts()
		{
			return sessionLoot.getBossKillCounts();
		}

		/**
		 * XP gained per skill while running ({@link SkillXp}; unmodifiable, possibly empty), or {@code null} when unknown
		 * (a snapshot from before XP was tracked).
		 */
		public Map<String, Long> getXpGained()
		{
			return xpGained;
		}

		/** The session's counted kills per map region, in the order first seen (never {@code null}; unmodifiable). */
		public List<LocationKills> getLocationKills()
		{
			return locationKills;
		}

		/** The region with the most counted kills ({@link LocationTally#dominantRegion}), or {@code null} when none. */
		public Integer getDominantRegion()
		{
			return LocationTally.dominantRegion(locationKills);
		}

		/** The session's kills per NPC name, in the order first seen (never {@code null}; unmodifiable). */
		public List<NpcKills> getNpcKills()
		{
			return npcKills;
		}
	}

	/** Immutable snapshot of the active session's task progress ({@link #liveTask()}). */
	public static final class LiveTask
	{
		/** {@link #getRemaining()} when the kills left on the session's task are not known right now. */
		public static final int UNKNOWN_REMAINING = -1;

		private final State state;
		private final String sessionId;
		private final String monster;
		private final int creatureId;
		private final int kills;
		private final long activeMillis;
		private final int remaining;

		LiveTask(State state, String sessionId, String monster, int creatureId, int kills, long activeMillis,
			int remaining)
		{
			this.state = state;
			this.sessionId = sessionId;
			this.monster = monster;
			this.creatureId = creatureId;
			this.kills = kills;
			this.activeMillis = activeMillis;
			this.remaining = remaining;
		}

		public State getState()
		{
			return state;
		}

		public String getSessionId()
		{
			return sessionId;
		}

		/** The session's task name, or {@code null} when unknown. */
		public String getMonster()
		{
			return monster;
		}

		public int getCreatureId()
		{
			return creatureId;
		}

		public int getKills()
		{
			return kills;
		}

		public long getActiveMillis()
		{
			return activeMillis;
		}

		/** Kills left on the session's task, or {@link #UNKNOWN_REMAINING}. */
		public int getRemaining()
		{
			return remaining;
		}
	}
}
