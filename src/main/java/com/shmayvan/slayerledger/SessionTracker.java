package com.shmayvan.slayerledger;

import java.util.*;
import java.util.function.*;
import lombok.*;

/**
 * Tracks a single Slayer task session. This class has no RuneLite dependencies so it can be unit tested with a
 * controllable clock. All public methods are synchronized because the plugin calls it from both the client thread
 * (task observations, kills, Away, logouts, account changes) and the Swing event dispatch thread (Pause / Resume /
 * End task / Track again).
 *
 * <p><b>Automatic lifecycle</b> (V2 plan 4.1). No session exists ({@code IDLE}) until a task kill: a drop of the
 * Slayer count ({@link #observe}) or a bracelet of slaughter keeping it ({@link #braceletKill}). The kill starts the
 * session. A session's time is the sum of its segments: a segment opens at a (re)start and closes at a stop, and time
 * outside segments is never task time. A task kill while the session is not {@code RUNNING} ({@code AWAY},
 * {@code PAUSED} or {@code LOGGED_OUT}) opens a segment at the kill's resume point: the earliest engagement start of
 * the NPCs paired with it ({@link KillPairing}), or the kill itself, never earlier than the assignment, the previous
 * session's end or the close of the previous segment. {@link #resume} (manual, from {@code PAUSED}) opens one at once.
 * The stops are {@link #pause}, {@link #loggedOut} (logout or connection loss; a hop is not a stop), Away
 * ({@link #checkAway}), {@link #endTask} and the end of the task (completion or a new task).</p>
 *
 * <p>States: {@code IDLE -> RUNNING} (a task kill, or {@link #startNow} in tests), {@code RUNNING -> AWAY | PAUSED |
 * LOGGED_OUT}, {@code AWAY | PAUSED | LOGGED_OUT -> RUNNING} (a task kill; {@code PAUSED} also by {@link #resume}),
 * {@code AWAY | PAUSED -> LOGGED_OUT}, any of them {@code -> STOPPED}, and {@link #restore} takes {@code IDLE} to
 * {@code LOGGED_OUT}. Invalid transitions are no-ops and return {@code false}. The plugin ends sessions through
 * {@link #stopAndReset} and {@link #endTask}, so it never leaves the tracker {@code STOPPED}; see
 * {@code docs/ARCHITECTURE.md}. {@link #reset()} returns a stopped tracker to {@code IDLE} so a new session can begin.
 * What the player is shown is {@link #getStatus()}.</p>
 *
 * <p><b>Ended.</b> {@link #endTask} stops the session and marks its assignment Ended: no kill on it starts a session
 * until {@link #trackAgain}, and nothing from before End task counts after it. A new assignment is tracked
 * normally.</p>
 *
 * <p>A running session that completes can be held back from saving for {@link #COMPLETION_LOOT_GRACE_TICKS} game
 * ticks (see {@link #holdCompletion()}) so the final kill's loot, which may arrive after the task count update, is
 * still recorded. The held session is already stopped (its kills and active time are final) and the tracker is back to
 * {@code IDLE}; it is outside the state machine, so it is never reported as active. When its final kill was Araxxor's,
 * whose loot comes only when the player harvests its corpse, a harvest after the save is added to its saved record
 * ({@link #takeHarvestRecord}).</p>
 *
 * <p><b>Supplies, XP and loot</b> (V2 plan 4.1). Inside a segment they count at once. Outside one, while a task is
 * assigned and not Ended ({@link #isCollecting()}), they are held per arrival time in a buffer of at most 60 minutes
 * ({@link TimedUsage}). Every (re)start counts the buffered XP and loot from the segment start (for a kill restart, the
 * backdated resume point, and the kill's own tick) and the buffered supplies from {@link #WARM_UP_MILLIS} before it,
 * no earlier than the resume point's clamps; the rest of the buffer is dropped once the restart's pairing is final
 * ({@link #pairKill}). When Away starts, the supplies, XP and loot since the last activity are taken back into the
 * buffer, apart from loot of a known task NPC (a paired task kill's) arriving within {@link #TASK_NPC_LOOT_MILLIS} of
 * the last task kill ({@link #ARAXXOR_LOOT_MILLIS} for Araxxor's), which always counts, even when it arrives after the
 * session stopped. The counters (superiors, bank trips, deaths) still count only while {@code RUNNING}; Away takes the
 * bank trips since the last activity back out, but not the deaths.</p>
 *
 * <p><b>Jad and Zuk tasks.</b> Inside the Fight Caves or the Inferno with that task assigned is Running
 * ({@link #setInTaskCave}): entering opens a segment, leaving closes it, and Away never starts inside.</p>
 */
public class SessionTracker
{
	public enum State
	{
		/** No session: waiting for the first kill of an assignment, Ended, or no task. */
		IDLE,
		/** A segment is open: time, supplies, XP and loot count. */
		RUNNING,
		/**
		 * No task kill or hit on a known task NPC for {@code awayMinutes}, or the player left the Fight Caves or the
		 * Inferno on that task; the next task kill (or entering again) restarts.
		 */
		AWAY,
		/** Paused by the player; Resume or the next task kill restarts. */
		PAUSED,
		/** Logged out (or the connection was lost), or restored after a client restart; the next task kill restarts. */
		LOGGED_OUT,
		/** Only seen inside {@link #stopAndReset}. */
		STOPPED;

		/** Whether a session exists and has not ended: running or waiting to restart. */
		public boolean isActive()
		{
			return this == RUNNING || this == AWAY || this == PAUSED || this == LOGGED_OUT;
		}

		/** Whether a session exists but its clock is stopped (Away, Paused or Logged out). */
		public boolean isClockStopped()
		{
			return this == AWAY || this == PAUSED || this == LOGGED_OUT;
		}
	}

	/**
	 * What the overlay and panel show about tracking ({@link #getStatus()}): the session's state, or without a session
	 * whether the assignment waits for its first kill or was Ended. Each has a label and, for the overlay's state line,
	 * a hint (empty when there is nothing to add).
	 */
	@Getter
	@RequiredArgsConstructor
	public enum Status
	{
		NO_TASK("No task", ""),
		WAITING_FOR_FIRST_KILL("Waiting for first kill", ""),
		RUNNING("Running", ""),
		AWAY("Away", "Back on your next kill"),
		/** Paused by the player: Resume in the sidebar (or the next task kill) restarts it. */
		PAUSED("Paused", "Resume in sidebar"),
		/** Logged out, before the next login has read the task. */
		LOGGED_OUT("Logged out", ""),
		/** Logged out earlier, or restored, and logged in again: the next task kill restarts. */
		WAITING_FOR_NEXT_KILL("Waiting for next kill", ""),
		/** After End task: the sidebar's Track again tracks the assignment again. */
		ENDED("Ended", "See sidebar");

		/** The status as shown, for example "Away". */
		private final String label;
		/**
		 * What happens next or what to do, for the overlay's state line (DESIGN_PRINCIPLES.md 2.1), for example "Back on
		 * your next kill" or "Resume in sidebar"; may be empty. On a Jad or Zuk task the overlay words Away and the
		 * waiting states for the caves instead ({@link OverlayRows#stateRow}).
		 */
		private final String hint;

		/** Whether time is not running while a task is assigned, so the overlay shows a state line. */
		public boolean hasStateLine()
		{
			return this != NO_TASK && this != RUNNING;
		}
	}

	/**
	 * Outcome of {@link #restore} and {@link #restoreEnded}.
	 */
	public enum RestoreResult
	{
		/** The snapshot was restored (a session as LOGGED_OUT, or the assignment's Ended flag). */
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

	/** Default of the "Away after" setting, in minutes (V2 plan 4.1). */
	public static final int DEFAULT_AWAY_MINUTES = 10;
	/** Smallest "Away after" setting, in minutes. */
	public static final int MIN_AWAY_MINUTES = 1;
	/** Largest "Away after" setting, in minutes. */
	public static final int MAX_AWAY_MINUTES = 60;
	/** A time that is not known (no clamp, no kill yet). */
	static final long NO_TIME = Long.MIN_VALUE;
	/**
	 * The supplies warm-up (V2 plan 4.1): a (re)start also counts the supplies buffered this long before the segment
	 * start (a pre-pot, a teleport to the task, potions drunk at the bank), no earlier than the resume point's clamps.
	 */
	static final long WARM_UP_MILLIS = 3 * 60_000L;
	/**
	 * How long after the session's last task kill a known task NPC's loot still counts as that kill's: it is never
	 * taken back by Away, and counts when it arrives while the session is stopped (a Pause or logout just after the
	 * kill). 10 game ticks: a kill's loot arrives within a tick or two of the count's drop. Later loot of the same names
	 * (a Konar or Wilderness task monster killed outside the assigned area, say) is like any other loot.
	 */
	static final long TASK_NPC_LOOT_MILLIS = 10 * 600L;
	/**
	 * {@link #TASK_NPC_LOOT_MILLIS} for Araxxor's loot: 5 minutes. Its drop is rolled only when the player
	 * chooses to Harvest its corpse, which can be well after the kill (OSRS Wiki, "Araxxor": "Choosing the Harvest
	 * option rolls the drop table"; RuneLite 1.13.4's boss timer notes that harvestable Araxxor is not marked dead).
	 */
	static final long ARAXXOR_LOOT_MILLIS = 5 * 60_000L;
	/** Araxxor's name, as {@link TaskStatistics#normalize} keys it: its loot gets {@link #ARAXXOR_LOOT_MILLIS}. */
	private static final String ARAXXOR = TaskStatistics.normalize("Araxxor");
	/** Most bank trips kept as since the last activity ({@link #bankTripsSinceActivity}); plenty. */
	private static final int MAX_COUNTERS_SINCE_ACTIVITY = 256;

	/** The time source, in clock millis. */
	private final LongSupplier clock;

	private State state = State.IDLE;
	/** How long without activity a running session goes Away ({@link #setAwayMinutes}). */
	private long awayMillis = DEFAULT_AWAY_MINUTES * 60_000L;
	/** The logged-in account ({@link #setAccount}), or {@link #NO_ACCOUNT}; a kill starts a session only for one. */
	private long account = NO_ACCOUNT;

	// The current assignment, kept across logins (unlike the observed task) so a login onto the same assignment keeps
	// its clamp and its Ended flag. Only an observation with a task changes it; see noteAssignment.
	private boolean hasAssignment;
	private long assignmentAccount = NO_ACCOUNT;
	private String assignmentMonster;
	private int assignmentCreatureId = NO_CREATURE;
	private int assignmentOriginalAmount = UNKNOWN_AMOUNT;
	/** The lowest count read on the assignment; a higher one is a new assignment. */
	private int assignmentRemaining;
	/** When the current assignment was first seen (a clamp of every resume point), or {@link #NO_TIME}. */
	private long assignmentAt = NO_TIME;
	/** Whether the current assignment was Ended ({@link #endTask}) and not tracked again. */
	private boolean ended;
	/**
	 * When the last session ended, or End task was pressed with no session ({@link #endTask}): a clamp of every resume
	 * point, or {@link #NO_TIME}. Outlives sessions.
	 */
	private long previousSessionEnd = NO_TIME;
	/** Clock time of the latest kill a session counted or (re)started on ({@link #getLastKillAt()}); outlives sessions. */
	private long lastKillAt = NO_TIME;
	/** A drop to zero seen while no session ran, waiting for its completion to start one; or {@code null}. */
	private PendingFirstKill pendingFirstKill;
	/** Readings with a zero count since {@link #pendingFirstKill}. */
	private int pendingFirstKillZeros;
	/** Most known task NPC names a session keeps ({@link #taskNpcNames}). */
	static final int MAX_TASK_NPCS = 32;

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
	/**
	 * Whether that drop restarts a stopped session once the completion confirms it, from {@link #pendingFinalPairing}
	 * at {@link #pendingFinalAt}: a drop to zero without a completion may be a cancelled task, so it restarts nothing yet.
	 */
	private boolean pendingFinalRestart;
	private KillPairing pendingFinalPairing = KillPairing.NONE;
	private long pendingFinalAt = NO_TIME;
	private boolean awaitingCompletion;
	// Remaining count just before the drop to zero, and how many zero observations have been seen since.
	private int preZeroBaseline;
	private int zeroObservations;
	private boolean completed;
	/**
	 * NPC loot counted: received while RUNNING, a known task NPC's while stopped, or buffered and counted at a
	 * (re)start.
	 */
	private final ItemTally loot = new ItemTally();
	/** Supplies used while RUNNING, and buffered ones a (re)start counted; potions in doses (see {@link #supplyDoses}). */
	private final ItemTally supplies = new ItemTally();
	/** Doses per item for potion ids in {@link #supplies}. */
	private final Map<Integer, Integer> supplyDoses = new TreeMap<>();
	/** Superior spawns, bank trips and deaths counted while RUNNING; never {@code null} and never unknown here. */
	private SessionCounters counters = SessionCounters.ZERO;
	/** Notable drops of the loot counted, and pets, in the order they arrived; bounded by {@link NotableDrops#add}. */
	private List<NotableDrop> notableDrops = new ArrayList<>();
	/** Boss kill counts while RUNNING, in the order first seen; bounded by {@link BossKillCounts#add}. */
	private List<BossKillCount> bossKillCounts = new ArrayList<>();
	/**
	 * Counted kills per place (map region and Slayer spot), the place killed at most recently last (see
	 * {@link LocationTally}); counted like {@link #kills}. They decide the task's spot ({@link #getTaskSpot}).
	 */
	private List<LocationKills> locationKills = new ArrayList<>();
	/**
	 * Deaths of NPCs the player damaged while RUNNING (and, added once it runs, those paired with the kill that
	 * (re)started it), per NPC name in the order first seen ({@link NpcKillTally}); they decide the task's variant
	 * (its Catacombs variant or Slayer boss) and boss flag ({@link TaskVariants}).
	 */
	private List<NpcKills> npcKills = new ArrayList<>();
	/** Where the player was when {@link #pendingFinalKills} dropped, so they are tallied there when confirmed. */
	private KillPlace pendingFinalPlace = KillPlace.NONE;
	/**
	 * Where the player was at the latest {@link #observe}, or {@link KillPlace#NONE} when unknown or the task was
	 * cleared. Not session data: the panel uses it for the current task's location.
	 */
	private KillPlace currentPlace = KillPlace.NONE;
	/**
	 * The bundled Slayer spot table, which decides the spot of kills tallied by region alone and breaks ties
	 * between spots ({@link LocationTally#decide}); {@link SlayerSpots#NONE} until {@link #setSpots}.
	 */
	private SlayerSpots spots = SlayerSpots.NONE;
	/** The player's note on the session ({@link TaskNote#normalize normalized}), or {@code null}. */
	private String note;
	/** The player's setup overrides on the session ({@link #setSetupOverrides}); never {@code null}. */
	private SetupOverrides setupOverrides = SetupOverrides.NONE;
	/**
	 * The session's setup kill contexts ({@link SetupKills}); never {@code null}. Kept across a restore; one is counted
	 * per task kill ({@link #addKillContext}).
	 */
	private SetupKills setupKills = SetupKills.NONE;
	/**
	 * Task kills the session counted before it recorded kill contexts: every kill of a session restored from a 1.x
	 * active-session file, which recorded none ({@link SessionSnapshot#killsWithoutContexts}). They are left out of the
	 * cannon share ({@link #contextKills}), so a cannon task a 1.x version started stays one after its next kill. 0 for a
	 * 2.0 session; kept across a restore.
	 */
	private int killsBeforeContexts;
	/**
	 * XP gained per skill while RUNNING, and buffered XP a (re)start counted ({@link #observeXp}), keyed as
	 * {@link SkillXp} keys them.
	 */
	private final Map<String, Long> xpGained = new TreeMap<>();
	/**
	 * The latest XP reading of every skill ({@link #observeXp}), or {@code null} after {@link #resetXpBaseline()} or an
	 * unsynced reading. Kept whatever the state, so it is always the baseline for the next reading: XP gained outside a
	 * segment is buffered ({@link #buffer}) while a task is assigned and not Ended, and otherwise (no task, Ended, a
	 * held completion's grace window) is never counted.
	 */
	private Map<String, Integer> xpReading;
	/**
	 * The session task's assignment, and once the task is completed the points earned and streak (see
	 * {@link #setCompletionRewards}), or {@link TaskContext#NONE}.
	 */
	private TaskContext sessionContext = TaskContext.NONE;

	/** Time of the closed segments. */
	private long accumulatedMillis;
	/** Start of the open segment (while RUNNING). */
	private long segmentStart;
	/** Close of the session's previous segment (a clamp of the next resume point), or {@link #NO_TIME}. */
	private long lastSegmentClose = NO_TIME;
	/** The open segment's latest task kill or hit on a known task NPC; Away counts from it (or the segment start). */
	private long lastActivity = NO_TIME;
	/** Clock time of the kill that opened the current segment, or {@link #NO_TIME}; {@link #pairKill} may backdate it. */
	private long restartKillAt = NO_TIME;
	/** The clamp the current segment's resume point was held to; a later backdating keeps to it too. */
	private long restartFloor = NO_TIME;
	/** The state {@link #stop()} stopped the session from, for its snapshot ({@link SegmentInfo#getState()}). */
	private State stoppedFrom = State.IDLE;
	/** The end of the session before this one when this one started (a clamp, persisted), or {@link #NO_TIME}. */
	private long priorSessionEnd = NO_TIME;
	/** Normalized names of the NPCs paired with the session's kills: its known task NPCs (V2 plan 4.1). */
	private final Set<String> taskNpcNames = new LinkedHashSet<>();
	/**
	 * Supplies, XP and loot outside segments while a task is assigned and not Ended ({@link #isBuffering()}), and what
	 * Away took back; a (re)start counts part of it ({@link #countBuffered}). Not session data: it belongs to the
	 * assignment, so a session's start can count it, and it is forgotten at a logout or account change
	 * ({@link #clearCurrentTask}) and at End task ({@link #endTask}). The resume point's clamps keep anything from before
	 * them out.
	 */
	private final TimedUsage buffer = new TimedUsage();
	/**
	 * The open segment's supplies, XP and loot since its last activity, as they were added to the totals: what Away
	 * takes back ({@link #takeBackSince}). Pets and loot of known task NPCs are never in it.
	 */
	private final TimedUsage sinceActivity = new TimedUsage();
	/**
	 * Clock times of the bank trips counted in the open segment since its last activity, oldest first: Away takes them
	 * back like the supplies (project-manager decision: they belong to whatever the player went off to
	 * do). Unlike supplies they are dropped, never buffered: a bank trip counts only while the clock runs. Deaths are
	 * never taken back (project-manager decision): a death usually lands a few ticks after the last
	 * hit, and a long recovery after it shouldn't erase a real task death.
	 */
	private final ArrayDeque<Long> bankTripsSinceActivity = new ArrayDeque<>();
	/** Whether the player is inside the Fight Caves or the Inferno on that task ({@link #setInTaskCave}). */
	private boolean inTaskCave;
	private long startedAt;
	private long endedAt;

	/** Completed session held back from saving for late loot, or {@code null}. Independent of {@link #state}. */
	private Summary pendingCompletion;
	/** Game ticks left before {@link #pendingCompletion} is released by {@link #tickCompletion()}. */
	private int pendingCompletionTicks;
	/**
	 * When the held completion's last segment was opened by its final kill ({@link #restartKillAt}), that kill's time,
	 * the segment's start and its clamp, so {@link #pairKill} can still backdate it; else {@link #NO_TIME}.
	 */
	private long pendingRestartKillAt = NO_TIME;
	private long pendingSegmentStart;
	private long pendingRestartFloor = NO_TIME;
	/** The held completion's final kill ({@link #lastKillAt} when it was held), for {@link #addHeldNpcKill}. */
	private long pendingKillAt = NO_TIME;
	/** Whether the latest task kill ({@link #lastKillAt}) was paired with Araxxor's death. */
	private boolean lastKillAraxxor;
	/**
	 * Whether the held completion's final kill was paired with Araxxor's death, on its tick or the tick after
	 * ({@link #pairKill}): its record then waits for the harvest ({@link #takeHarvestRecord}).
	 */
	private boolean pendingKillAraxxor;
	/**
	 * Clock time of the latest Araxxor loot ({@link #addLoot(ItemTally, List, String)}), whatever its items (none kept
	 * after the bonecrusher and Ignored loot, too) and wherever it went, or {@link #NO_TIME}; outlives sessions. Loot at
	 * or after a held completion's final kill was that kill's harvest, so no harvest window opens for it.
	 */
	private long lastAraxxorLootAt = NO_TIME;
	/**
	 * The Araxxor harvest window: the id of the record of a task whose final kill was Araxxor, from when its held
	 * completion was released for saving ({@link #tickCompletion}) until its harvest ({@link #takeHarvestRecord}), or
	 * {@code null}. Araxxor drops its loot only when the player harvests its corpse, which can come after the save.
	 */
	private String harvestRecordId;
	/** The account of {@link #harvestRecordId}'s task. */
	private long harvestAccount = NO_ACCOUNT;
	/** The final kill of {@link #harvestRecordId}'s task; the window lasts {@link #ARAXXOR_LOOT_MILLIS} from it. */
	private long harvestKillAt = NO_TIME;
	/**
	 * Creature keys of tasks a session took (adopted or restored) since the last {@link #takeSessionTaskKeys()}, so a
	 * session started and ended between two calls is still reported.
	 */
	private final Set<Integer> takenTaskKeys = new HashSet<>();

	public SessionTracker(LongSupplier clock)
	{
		this.clock = Objects.requireNonNull(clock);
	}

	/** The tracker's clock now (clock millis); the plugin stamps the player's hits and NPC deaths with it. */
	public synchronized long now()
	{
		return clock.getAsLong();
	}

	/**
	 * The "Away after" setting: a running session with no task kill and no hit on a known task NPC for this many
	 * minutes goes Away ({@link #checkAway}). Clamped to {@link #MIN_AWAY_MINUTES} to {@link #MAX_AWAY_MINUTES}.
	 */
	public synchronized void setAwayMinutes(int minutes)
	{
		awayMillis = Math.max(MIN_AWAY_MINUTES, Math.min(MAX_AWAY_MINUTES, minutes)) * 60_000L;
	}

	/** The Away limit in millis; also the longest gap within one engagement ({@link DamagedNpcs}). */
	public synchronized long getAwayMillis()
	{
		return awayMillis;
	}

	/**
	 * The logged-in account, or {@link #NO_ACCOUNT}. A kill starts a session only for a known account, and the
	 * assignment it belongs to is remembered per account.
	 */
	public synchronized void setAccount(long accountHash)
	{
		account = accountHash;
	}

	/**
	 * Test seam: starts a session at once for the given account, as the old Play did: from {@code IDLE} it adopts the
	 * observed task (or the first one observed later) and opens a segment now; from {@code AWAY}, {@code PAUSED} or
	 * {@code LOGGED_OUT} it opens one now, as a kill restart with nothing paired would. Lets tests whose subject is not
	 * how a session starts begin one without a kill. Like Play, it counts nothing from before it: the buffer outside
	 * segments is dropped, with no warm-up. Refused until the task has been observed for the current login, for an
	 * Ended assignment, and for another account's session.
	 */
	synchronized boolean startNow(long accountHash)
	{
		if (accountHash == NO_ACCOUNT || !taskObserved)
		{
			return false;
		}
		final long now = clock.getAsLong();
		if (state == State.IDLE)
		{
			if (ended)
			{
				return false;
			}
			sessionId = UUID.randomUUID().toString();
			sessionAccountHash = accountHash;
			priorSessionEnd = previousSessionEnd;
			startedAt = now;
			openSegmentNow(now);
			buffer.clear();
			if (currentRemaining > 0)
			{
				adoptCurrentTask();
			}
			return true;
		}
		if (state.isClockStopped() && accountHash == sessionAccountHash)
		{
			openSegmentNow(now);
			buffer.clear();
			return true;
		}
		return false;
	}

	/** Whether {@link #resume} would succeed for the given account: the session is Paused and the account's. */
	public synchronized boolean canResume(long accountHash)
	{
		return state == State.PAUSED && accountHash != NO_ACCOUNT && accountHash == sessionAccountHash && taskObserved;
	}

	/**
	 * Resume (the player's, while Paused): opens a segment at once, a restart, so the supplies buffered in the warm-up
	 * before it count ({@link #restartNow}).
	 */
	public synchronized boolean resume(long accountHash)
	{
		if (!canResume(accountHash))
		{
			return false;
		}
		restartNow(clock.getAsLong());
		return true;
	}

	/**
	 * Pauses a running session: its segment closes now.
	 */
	public synchronized boolean pause()
	{
		if (state != State.RUNNING)
		{
			return false;
		}
		closeSegment(clock.getAsLong());
		state = State.PAUSED;
		return true;
	}

	/**
	 * A logout or lost connection stops the session: a running segment closes now, and the next task kill after a
	 * login restarts it (a login is not a restart). An Away or Paused session becomes Logged out too.
	 *
	 * @return whether the state changed
	 */
	public synchronized boolean loggedOut()
	{
		if (!state.isActive() || state == State.LOGGED_OUT)
		{
			return false;
		}
		if (state == State.RUNNING)
		{
			closeSegment(clock.getAsLong());
		}
		state = State.LOGGED_OUT;
		return true;
	}

	/**
	 * Away (V2 plan 4.1): once {@code awayMinutes} have passed since the running session's last activity (its latest
	 * task kill or own hit on a known task NPC, or the segment's start), the segment closes at that last activity, so
	 * the clock steps back once, and the session waits for the next task kill. The supplies, XP and loot added since
	 * the last activity are taken back out of the totals into the buffer ({@link #takeBackSince}), and so are the bank
	 * trips since then, which are dropped; the last kill's own XP (on its tick), loot of its known task NPCs
	 * ({@link #TASK_NPC_LOOT_MILLIS}), deaths and superiors stay. Never inside the Fight Caves or the Inferno on that task
	 * ({@link #setInTaskCave}). Called every settled tick.
	 *
	 * @return whether the session went Away
	 */
	public synchronized boolean checkAway()
	{
		if (state != State.RUNNING || inTaskCave)
		{
			return false;
		}
		final long last = Math.max(segmentStart, lastActivity);
		if (clock.getAsLong() - last < awayMillis)
		{
			return false;
		}
		takeBackSince(last);
		takeBackBankTripsSince(last);
		closeSegment(last);
		state = State.AWAY;
		return true;
	}

	/**
	 * Jad and Zuk tasks (V2 plan 4.1): whether the player is inside the Fight Caves or the Inferno with that task
	 * assigned ({@link TaskCaves#isInside}), read every settled tick. Inside is Running:
	 * <ul>
	 * <li>entering, or being inside with a session waiting to restart (after a login inside, or a client restart's
	 * restore), opens a segment at once: a restart ({@link #restartNow}, with the warm-up), or the start of a session on
	 * an assignment waiting for its first kill. A session the player paused inside stays Paused until they Resume or
	 * enter again;</li>
	 * <li>leaving, also after a death, closes the segment now and keeps what came before; the session waits Away for
	 * the next entry (or task kill). A logout inside closes it as any logout does ({@link #loggedOut});</li>
	 * <li>Away never starts inside ({@link #checkAway}).</li>
	 * </ul>
	 *
	 * @param inside       whether the player is inside the assigned task's cave now
	 * @param startAllowed whether a new session may start now (not while a restore check is pending, as for a kill)
	 * @return whether a segment opened or closed
	 */
	public synchronized boolean setInTaskCave(boolean inside, boolean startAllowed)
	{
		final boolean entered = inside && !inTaskCave;
		final boolean left = !inside && inTaskCave;
		inTaskCave = inside;
		final long now = clock.getAsLong();
		if (left)
		{
			if (state != State.RUNNING)
			{
				return false;
			}
			closeSegment(now);
			state = State.AWAY;
			return true;
		}
		if (!inside || state == State.RUNNING || (state == State.PAUSED && !entered))
		{
			return false;
		}
		if (state.isClockStopped())
		{
			if (!isSessionOnCurrentTask())
			{
				return false;
			}
			restartNow(now);
			return true;
		}
		if (state != State.IDLE || !taskObserved || currentRemaining <= 0 || account == NO_ACCOUNT || ended
			|| !startAllowed)
		{
			return false;
		}
		clearSession();
		sessionId = UUID.randomUUID().toString();
		sessionAccountHash = account;
		priorSessionEnd = previousSessionEnd;
		adoptCurrentTask();
		restartNow(now);
		startedAt = segmentStart;
		return true;
	}

	/**
	 * The player hit an NPC named {@code name}: activity for Away when it is one of the session's known task NPCs (the
	 * names paired with its kills) and the session is running.
	 *
	 * @return whether it counted as activity
	 */
	public synchronized boolean taskNpcHit(String name)
	{
		final String key = TaskStatistics.normalize(name);
		if (state != State.RUNNING || key == null || !taskNpcNames.contains(key))
		{
			return false;
		}
		lastActivity = Math.max(lastActivity, clock.getAsLong());
		return true;
	}

	/**
	 * A bracelet of slaughter's activation: a task kill that left the count unchanged (V2 plan 4.1). It is activity:
	 * it resets the Away clock of a running session, restarts a stopped one at its resume point, and starts a session
	 * on an assignment waiting for its first kill. It counts no kill: the kills follow the task count.
	 *
	 * @param pairing the NPCs that died around the activation
	 * @return whether it counted (for a session, or started one)
	 */
	public synchronized boolean braceletKill(KillPairing pairing)
	{
		final KillPairing kill = pairing == null ? KillPairing.NONE : pairing;
		final long now = clock.getAsLong();
		if (state == State.RUNNING)
		{
			noteKill(now, kill);
			return true;
		}
		if (state.isClockStopped() && isSessionOnCurrentTask())
		{
			openSegmentAtKill(kill, now);
			noteKill(now, kill);
			return true;
		}
		if (state == State.IDLE && taskObserved && currentRemaining > 0 && canStart(kill))
		{
			startSession(currentMonster, currentCreatureId, currentOriginalAmount, currentContext, currentRemaining,
				kill, now);
			return true;
		}
		return false;
	}

	/**
	 * The NPCs that died around a task kill, once all of them are known (deaths on the tick after the kill included):
	 * their names become known task NPCs, and when that kill (re)started the session, its segment is backdated to their
	 * earliest engagement start if that is earlier, still clamped as the restart was, and the buffered XP and loot from
	 * the new start and supplies from the warm-up before it are counted too. A completed session held for late loot is
	 * backdated the same way when its final kill restarted it. This is the restart's final pairing, so the rest of the
	 * buffer is dropped (V2 plan 4.1). A held completion's final kill paired with Araxxor's death waits for its harvest
	 * once it is saved ({@link #takeHarvestRecord}).
	 *
	 * @param killAt  the kill's clock time ({@link #getLastKillAt()} just after it)
	 * @param pairing the NPCs that died from 2 ticks before the kill to 1 tick after
	 */
	public synchronized void pairKill(long killAt, KillPairing pairing)
	{
		if (pairing == null || killAt == NO_TIME)
		{
			return;
		}
		if (state.isActive() && lastKillAt == killAt)
		{
			learnTaskNpcs(pairing);
		}
		if (pendingCompletion != null && pendingKillAt == killAt && pairsAraxxor(pairing))
		{
			pendingKillAraxxor = true;
		}
		if (state == State.RUNNING && restartKillAt == killAt)
		{
			final long backdated = pairing.hasStart()
				? Math.max(restartFloor, Math.min(segmentStart, pairing.getEarliestStart())) : segmentStart;
			if (backdated < segmentStart)
			{
				if (startedAt == segmentStart)
				{
					startedAt = backdated;
				}
				segmentStart = backdated;
				countBuffered(restartFloor, backdated);
			}
			buffer.clear();
		}
		else if (pendingCompletion != null && pendingRestartKillAt == killAt)
		{
			final long backdated = pairing.hasStart() ? Math.max(pendingRestartFloor,
				Math.min(pendingSegmentStart, pairing.getEarliestStart())) : pendingSegmentStart;
			if (backdated < pendingSegmentStart)
			{
				pendingCompletion = pendingCompletion.backdated(pendingSegmentStart - backdated, pendingSegmentStart)
					.withCounted(buffer.take(warmUpFrom(pendingRestartFloor, backdated), backdated));
				pendingSegmentStart = backdated;
			}
			buffer.clear();
		}
	}

	/** Clock time of the latest task kill a session counted or (re)started on, or {@link #NO_TIME}. */
	public synchronized long getLastKillAt()
	{
		return lastKillAt;
	}

	/**
	 * Whether End task would do something: a session exists, or the observed assignment waits for its first kill.
	 */
	public synchronized boolean canEndTask()
	{
		return state.isActive() || (state == State.IDLE && !ended && taskObserved && currentRemaining > 0);
	}

	/**
	 * End task (V2 plan 4.1): stops the session, if any, and marks its assignment Ended, so no kill on it starts a
	 * session again until {@link #trackAgain}. Without a session, an assignment waiting for its first kill is marked
	 * Ended. Decided in one call, so a kill can't restart the session in between. Either way nothing from before it
	 * counts after Track again: the buffer outside segments is dropped, and End task is the next resume point's clamp
	 * ({@link #previousSessionEnd}), as a session's end is.
	 *
	 * @return the stopped session's summary (to save through Keep / Discard), or {@code null} when none was active
	 */
	public synchronized Summary endTask()
	{
		if (!canEndTask())
		{
			return null;
		}
		final long now = clock.getAsLong();
		if (!hasAssignment && hasSessionTask)
		{
			// A session started without a task observed for this login (a test seam's): its own task is the assignment.
			setAssignment(sessionAccountHash, sessionMonster, sessionCreatureId, originalAmount, baseline, now);
		}
		ended = true;
		buffer.clear();
		if (!state.isActive())
		{
			// No session to stop (whose end would be the clamp): the wait for the first kill ends here.
			previousSessionEnd = now;
			return null;
		}
		return stopAndReset();
	}

	/** Track again: an Ended assignment waits for its first kill again. */
	public synchronized boolean trackAgain()
	{
		if (!ended)
		{
			return false;
		}
		ended = false;
		return true;
	}

	/**
	 * The account of the Ended assignment, whose marker ({@link #endedMarker}) a logout or shutdown may have persisted,
	 * or {@link #NO_ACCOUNT} when the assignment isn't Ended.
	 */
	public synchronized long getEndedAccount()
	{
		return ended && hasAssignment ? assignmentAccount : NO_ACCOUNT;
	}

	/**
	 * What the overlay and panel show: the session's state, or without one whether the observed assignment waits for its
	 * first kill or was Ended. A Logged out session reads {@link Status#WAITING_FOR_NEXT_KILL} once the task has been
	 * read after the next login.
	 */
	public synchronized Status getStatus()
	{
		switch (state)
		{
			case RUNNING:
				return Status.RUNNING;
			case AWAY:
				return Status.AWAY;
			case PAUSED:
				return Status.PAUSED;
			case LOGGED_OUT:
				return taskObserved ? Status.WAITING_FOR_NEXT_KILL : Status.LOGGED_OUT;
			default:
				break;
		}
		if (ended && (!taskObserved || currentRemaining > 0))
		{
			return Status.ENDED;
		}
		return taskObserved && currentRemaining > 0 ? Status.WAITING_FOR_FIRST_KILL : Status.NO_TASK;
	}

	/**
	 * The Ended assignment as a marker snapshot ({@link SessionSnapshot#isEndedMarker()}) for the active-session file,
	 * so a client restart keeps it Ended; {@code null} when the assignment isn't Ended.
	 */
	public synchronized SessionSnapshot endedMarker(int[] completionCounters, long savedAt)
	{
		if (!ended || !hasAssignment || assignmentAccount == NO_ACCOUNT)
		{
			return null;
		}
		final SessionSnapshot marker = new SessionSnapshot();
		marker.setSessionId(SessionSnapshot.ENDED_ID_PREFIX + UUID.randomUUID());
		marker.setAccountHash(assignmentAccount);
		marker.setMonster(assignmentMonster);
		marker.setCreatureId(assignmentCreatureId);
		marker.setOriginalAmount(assignmentOriginalAmount == UNKNOWN_AMOUNT ? null : assignmentOriginalAmount);
		marker.setInitialAmount(assignmentRemaining);
		marker.setRemaining(assignmentRemaining);
		marker.setCompletionCounters(completionCounters == null ? null
			: Arrays.copyOf(completionCounters, completionCounters.length));
		marker.setStartedAt(savedAt);
		marker.setSavedAt(savedAt);
		marker.setTrackingState(SessionSnapshot.ENDED_STATE);
		marker.setAssignedAt(assignmentAt == NO_TIME ? null : assignmentAt);
		marker.setPreviousSessionEnd(previousSessionEnd == NO_TIME ? null : previousSessionEnd);
		return marker;
	}

	/**
	 * Restores an Ended assignment from its marker ({@link #endedMarker}) when it matches the observed task, as
	 * {@link #restore} checks a session. Nothing else is restored.
	 */
	public synchronized RestoreResult restoreEnded(SessionSnapshot marker, int[] currentCompletionCounters)
	{
		if (state != State.IDLE || !taskObserved)
		{
			return RestoreResult.REFUSED;
		}
		if (!matchesCurrentTask(marker, currentCompletionCounters))
		{
			return RestoreResult.MISMATCH;
		}
		ended = true;
		if (marker.getAssignedAt() != null)
		{
			assignmentAt = marker.getAssignedAt();
		}
		if (marker.getPreviousSessionEnd() != null)
		{
			previousSessionEnd = Math.max(previousSessionEnd, marker.getPreviousSessionEnd());
		}
		return RestoreResult.RESTORED;
	}

	/**
	 * Forgets the latest observed task (for example on logout or account change) so stale data is not shown or
	 * adopted, and the next {@link #observe} only sets the baselines (it is never a kill). The buffer outside segments
	 * goes with it, as the player's hits do, so nothing from before a logout or from another account is counted, and so
	 * does whether the player was inside a task's cave. The active session, if any, and the current assignment (with
	 * its Ended flag) are unaffected.
	 */
	public synchronized void clearCurrentTask()
	{
		buffer.clear();
		inTaskCave = false;
		taskObserved = false;
		currentMonster = null;
		currentCreatureId = NO_CREATURE;
		currentRemaining = 0;
		currentOriginalAmount = UNKNOWN_AMOUNT;
		currentContext = TaskContext.NONE;
		currentPlace = KillPlace.NONE;
	}

	/**
	 * Stops an active session (its running segment closes now). Use {@link #summary()} to read the result, then
	 * {@link #reset()}. Its end is the next session's clamp ({@link #previousSessionEnd}).
	 */
	public synchronized boolean stop()
	{
		if (!state.isActive())
		{
			return false;
		}
		long now = clock.getAsLong();
		stoppedFrom = state;
		if (state == State.RUNNING)
		{
			closeSegment(now);
		}
		endedAt = now;
		previousSessionEnd = now;
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
	 * @return the stopped session's summary, or {@code null} if no session was active
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
		if (!state.isActive() || sessionAccountHash == accountHash)
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
		if (!state.isActive() || logoutSnapshot == null
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
	 * Restores a persisted session as LOGGED_OUT (V2 plan 4.1: as after a logout, so the next task kill restarts it) if
	 * it matches the latest observed task. Kills, active time and the clamps are kept; the kill baseline is the current
	 * remaining count, so kills made while untracked are not counted. A 1.x file (no segment fields) closes its last
	 * segment when it was saved, and its kills, which recorded no kill context, are left out of the cannon share
	 * ({@link #killsBeforeContexts}).
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
		state = State.LOGGED_OUT;
		restoredPending = true;
		lastSegmentClose = snapshot.getSegmentClosedAt() != null ? snapshot.getSegmentClosedAt() : snapshot.getSavedAt();
		if (snapshot.getAssignedAt() != null)
		{
			assignmentAt = snapshot.getAssignedAt();
		}
		if (snapshot.getPreviousSessionEnd() != null)
		{
			previousSessionEnd = Math.max(previousSessionEnd, snapshot.getPreviousSessionEnd());
		}
		priorSessionEnd = previousSessionEnd;
		if (snapshot.getTaskNpcs() != null)
		{
			for (String name : snapshot.getTaskNpcs())
			{
				learnTaskNpc(name);
			}
		}
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
		// Likewise for the kills per place; a snapshot without them tallies from the restore. A tally kept before spots
		// has regions only: their spots are decided from the regions.
		locationKills = LocationTally.sanitize(snapshot.getLocationKills());
		// Likewise for the kills per NPC name; a snapshot without them tallies from the restore.
		npcKills = NpcKillTally.sanitize(snapshot.getNpcKills());
		note = TaskNote.normalize(snapshot.getNote());
		// A snapshot from before setup tags has no overrides and no kill contexts.
		setupOverrides = SetupOverrides.from(snapshot);
		setupKills = SetupKills.from(snapshot);
		// A 1.x file's kills recorded no context: they are left out of the cannon share.
		killsBeforeContexts = Math.min(kills, snapshot.killsWithoutContexts());
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
	 * completed session is already held. A final kill restarts a stopped session before it completes it, so a completion
	 * is normally RUNNING; one that isn't (it completed without a kill being seen) is saved immediately as before.</p>
	 *
	 * @return whether the session is now held; if {@code false} the caller should end and save it as usual
	 */
	public synchronized boolean holdCompletion()
	{
		if (state != State.RUNNING || !completed || pendingCompletion != null)
		{
			return false;
		}
		// The final kill may have opened the last segment; its pairing may still backdate it (pairKill).
		final long restartAt = restartKillAt;
		final long floor = restartFloor;
		final long start = segmentStart;
		pendingCompletion = stopAndReset();
		pendingCompletionTicks = COMPLETION_LOOT_GRACE_TICKS;
		pendingRestartKillAt = restartAt;
		pendingRestartFloor = floor;
		pendingSegmentStart = start;
		pendingKillAt = lastKillAt;
		pendingKillAraxxor = lastKillAraxxor;
		return true;
	}

	/**
	 * Counts down the held completed session by one game tick. Call once per game tick. When the grace window ends and
	 * the final kill was paired with Araxxor's death, and no Araxxor loot came since that kill (before the completion
	 * was held or while it was, and whatever its items: {@link #lastAraxxorLootAt}), Araxxor's harvest window opens for
	 * the session's record ({@link #takeHarvestRecord}): Araxxor drops its loot only when the player harvests its
	 * corpse.
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
		if (pendingCompletionTicks > 0)
		{
			return null;
		}
		final long killAt = pendingKillAt;
		final boolean harvested = lastAraxxorLootAt != NO_TIME && lastAraxxorLootAt >= killAt;
		final boolean awaitsHarvest = pendingKillAraxxor && !harvested && pendingCompletion.isCompleted()
			&& pendingCompletion.getSessionId() != null;
		final Summary summary = releasePendingCompletion();
		if (awaitsHarvest)
		{
			harvestRecordId = summary.getSessionId();
			harvestAccount = summary.getAccountHash();
			harvestKillAt = killAt;
		}
		return summary;
	}

	/**
	 * Releases the held completed session at once (for example on logout, hop, account change, End task or shutdown).
	 * Each held session is returned exactly once, by this method or {@link #tickCompletion()}. Every one of those paths
	 * also ends Araxxor's harvest window, released early or not: a logout, a lost connection or a hop leaves the
	 * corpse behind, and the others end the task's tracking.
	 *
	 * @return the held session's summary (including loot received so far), or {@code null} if none is held
	 */
	public synchronized Summary takePendingCompletion()
	{
		endHarvestWindow();
		return releasePendingCompletion();
	}

	/** Hands out the held completed session, if any, and forgets it. */
	private Summary releasePendingCompletion()
	{
		final Summary summary = pendingCompletion;
		pendingCompletion = null;
		pendingCompletionTicks = 0;
		pendingRestartKillAt = NO_TIME;
		pendingRestartFloor = NO_TIME;
		pendingKillAt = NO_TIME;
		pendingKillAraxxor = false;
		return summary;
	}

	/**
	 * Araxxor's harvest after its task was saved: whether loot of {@code npcName} arriving now for
	 * {@code accountHash} is the harvest of the Araxxor kill that completed the task saved last, and if so the id of that
	 * task's record, which the loot is added to instead of {@link #addLoot(ItemTally, List, String)}. That is Araxxor's
	 * loot within {@link #ARAXXOR_LOOT_MILLIS} of that final kill, for the same account, with no task kill since. Any
	 * Araxxor loot ends the window, so a harvest is added once; so does every early release of a held completion
	 * ({@link #takePendingCompletion}: a logout, hop or account change).
	 *
	 * @param accountHash the logged-in account
	 * @param npcName     the name of the NPC the loot came from, or {@code null} when not known
	 * @return the record's id, or {@code null} when the loot is not that harvest (it is then loot like any other)
	 */
	public synchronized String takeHarvestRecord(long accountHash, String npcName)
	{
		if (harvestRecordId == null || !isAraxxor(npcName))
		{
			return null;
		}
		final String id = harvestRecordId;
		final long since = clock.getAsLong() - harvestKillAt;
		final boolean harvest = accountHash != NO_ACCOUNT && accountHash == harvestAccount
			&& lastKillAt == harvestKillAt && pendingCompletion == null && since >= 0 && since <= ARAXXOR_LOOT_MILLIS;
		endHarvestWindow();
		return harvest ? id : null;
	}

	/** Ends Araxxor's harvest window, if one is open. */
	private void endHarvestWindow()
	{
		harvestRecordId = null;
		harvestAccount = NO_ACCOUNT;
		harvestKillAt = NO_TIME;
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
	 * ended without a session. Decided in one call, so a Resume click can't fall between two reads.
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
	 * Adds NPC loot to the session ({@link #addLoot(ItemTally, List, String)} with no NPC name).
	 *
	 * @return whether the loot was added to a session
	 */
	public synchronized boolean addLoot(ItemTally items)
	{
		return addLoot(items, null, null);
	}

	/**
	 * Adds NPC loot and its notable stacks ({@link #addLoot(ItemTally, List, String)} with no NPC name).
	 *
	 * @return whether the loot was added to a session
	 */
	public synchronized boolean addLoot(ItemTally items, List<NotableDrop> notableDrops)
	{
		return addLoot(items, notableDrops, null);
	}

	/**
	 * Adds NPC loot and its notable stacks (see {@link NotableDrops}); both go to the same place, or neither is kept:
	 * <ul>
	 * <li>while a completed session is held for late loot ({@link #holdCompletion()}), to that session, even if a new
	 * session has been started meanwhile;</li>
	 * <li>while RUNNING, to the session. Unless it is a paired task kill's loot (below), it is also noted as use since the
	 * last activity, which Away takes back;</li>
	 * <li>a paired task kill's loot while the session is Away, Paused or Logged out: to the session, because loot from a
	 * paired task kill always counts, even when it arrives after the session stopped (V2 plan 4.1). That is loot of one
	 * of the session's known task NPCs arriving within {@link #TASK_NPC_LOOT_MILLIS} ({@link #ARAXXOR_LOOT_MILLIS} for
	 * Araxxor) after its last task kill;
	 * later loot of those names is like any other;</li>
	 * <li>otherwise, while a task is assigned and not Ended, to the buffer outside segments, which a (re)start counts
	 * from the segment start;</li>
	 * <li>otherwise nowhere.</li>
	 * </ul>
	 *
	 * <p>Loot is deliberately not filtered by the task monster: any NPC loot received while the session runs counts,
	 * because drop sources do not map reliably to Slayer task categories (superiors, variants, bosses and wildcards
	 * share or rename NPCs) and the session runs only between task kills, Pause and Away.</p>
	 *
	 * <p>Araxxor's loot is noted first, even with no items (all crushed or ignored): after the final kill it is that
	 * kill's harvest, so no harvest window opens for the task's record ({@link #lastAraxxorLootAt},
	 * {@link #takeHarvestRecord}).</p>
	 *
	 * @param items        the loot, as kept; may be empty
	 * @param notableDrops the loot's notable stacks, or {@code null} when none
	 * @param npcName      the name of the NPC it came from, or {@code null} when not known
	 * @return whether the loot was added to a session (not when only buffered)
	 */
	public synchronized boolean addLoot(ItemTally items, List<NotableDrop> notableDrops, String npcName)
	{
		if (isAraxxor(npcName))
		{
			lastAraxxorLootAt = clock.getAsLong();
		}
		if (items == null || items.isEmpty())
		{
			return false;
		}
		if (pendingCompletion != null)
		{
			pendingCompletion = pendingCompletion.withAddedLoot(items, notableDrops);
			return true;
		}
		final boolean taskNpcLoot = isTaskKillLoot(npcName);
		if (state == State.RUNNING || (state.isActive() && taskNpcLoot))
		{
			loot.merge(items);
			this.notableDrops = NotableDrops.add(this.notableDrops, notableDrops);
			if (state == State.RUNNING && !taskNpcLoot)
			{
				noteSinceActivity().addLoot(clock.getAsLong(), items, notableDrops);
			}
			return true;
		}
		if (isBuffering())
		{
			buffer.addLoot(clock.getAsLong(), items, notableDrops);
		}
		return false;
	}

	/**
	 * Whether loot may be kept now ({@link #addLoot(ItemTally, List, String)}): a session is active, a completed one is
	 * held for late loot, or a task is assigned and not Ended (buffered). Lets the caller skip pricing loot that would
	 * not be kept.
	 */
	public synchronized boolean isRecordingLoot()
	{
		return pendingCompletion != null || state.isActive() || isBuffering();
	}

	/**
	 * Adds a pet drop ({@link PetMessage}) to the session's notable drops: to a completed session held for late loot,
	 * since the pet may come from the final kill; while RUNNING, to the session, and it is never taken back by Away;
	 * otherwise, while a task is assigned and not Ended, to the buffer, as loot.
	 *
	 * @return whether it was added to a session (not when only buffered)
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
		if (state == State.RUNNING)
		{
			notableDrops = NotableDrops.add(notableDrops, added);
			return true;
		}
		if (isBuffering())
		{
			buffer.addLoot(clock.getAsLong(), null, added);
		}
		return false;
	}

	/** Whether {@code npcName} is one of the session's known task NPCs (paired with its kills). */
	private boolean isKnownTaskNpc(String npcName)
	{
		final String key = TaskStatistics.normalize(npcName);
		return key != null && taskNpcNames.contains(key);
	}

	/**
	 * Whether loot from {@code npcName} arriving now is a paired task kill's: one of the session's known task NPCs,
	 * within {@link #TASK_NPC_LOOT_MILLIS} after the session's last task kill, or {@link #ARAXXOR_LOOT_MILLIS} for
	 * Araxxor, whose loot comes when the player harvests its corpse.
	 */
	private boolean isTaskKillLoot(String npcName)
	{
		if (lastKillAt == NO_TIME || !isKnownTaskNpc(npcName))
		{
			return false;
		}
		final long since = clock.getAsLong() - lastKillAt;
		final long window = isAraxxor(npcName) ? ARAXXOR_LOOT_MILLIS : TASK_NPC_LOOT_MILLIS;
		return since >= 0 && since <= window;
	}

	/** Whether {@code npcName} is Araxxor's, as {@link TaskStatistics#normalize} keys names. */
	private static boolean isAraxxor(String npcName)
	{
		return ARAXXOR.equals(TaskStatistics.normalize(npcName));
	}

	/** Whether Araxxor's death is among the NPCs paired with a task kill. */
	private static boolean pairsAraxxor(KillPairing pairing)
	{
		if (pairing != null)
		{
			for (String name : pairing.getNames())
			{
				if (isAraxxor(name))
				{
					return true;
				}
			}
		}
		return false;
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
	 * Adds supplies used: while RUNNING to the session (and noted as use since the last activity, which Away takes
	 * back); otherwise, while a task is assigned and not Ended, to the buffer outside segments, which a (re)start counts
	 * from {@link #WARM_UP_MILLIS} before the segment start. Unlike loot, supplies are never added to a completed session
	 * held for late loot (see {@link #holdCompletion()}): it stopped running when the task completed.
	 *
	 * @param used     items used; potions in doses
	 * @param returned ammo or cannonballs that came back (picked up, or unloaded from the cannon); each reduces that
	 *                 item's used quantity, never below zero
	 * @param doses    doses per item for the potion ids in {@code used}, or {@code null}
	 * @return whether the session changed (not when only buffered)
	 */
	public synchronized boolean addSupplies(ItemTally used, ItemTally returned, Map<Integer, Integer> doses)
	{
		final boolean hasUsed = used != null && !used.isEmpty();
		if (!hasUsed && (returned == null || returned.isEmpty()))
		{
			return false;
		}
		if (state != State.RUNNING)
		{
			if (isBuffering())
			{
				buffer.addSupplies(clock.getAsLong(), used, returned, doses);
			}
			return false;
		}
		boolean changed = false;
		if (hasUsed)
		{
			supplies.merge(used);
			putDoses(supplyDoses, doses);
			changed = true;
		}
		// What a return actually took off, so taking it back (Away) puts exactly that back.
		final ItemTally takenOff = new ItemTally();
		if (returned != null)
		{
			for (int id : returned.itemIds())
			{
				takenOff.add(id, supplies.subtract(id, returned.get(id)));
			}
		}
		changed |= !takenOff.isEmpty();
		noteSinceActivity().addSupplies(clock.getAsLong(), hasUsed ? used : null, takenOff, doses);
		return changed;
	}

	/**
	 * Counts a superior Slayer monster spawn, only while the session is RUNNING. Unlike loot, a spawn is never added to
	 * a completed session held for late loot (see {@link #holdCompletion()}): it stopped running when the task
	 * completed, and a superior that appears after that belongs to no task. One that appears just before the kill that
	 * (re)starts the session is held by the plugin and added once it runs.
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
	 * are trips is decided by {@link BankTripCounter}. One after the last activity is taken back if the session goes
	 * Away ({@link #checkAway}).
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
		noteSinceActivity(bankTripsSinceActivity);
		return true;
	}

	/**
	 * Counts a death of the local player, only while the session is RUNNING (never for a held completed session, like
	 * {@link #addSuperiorSpawn()}). Deaths are only counted; what a death costs is not tracked. Never taken back by Away
	 * ({@link #bankTripsSinceActivity}), like superiors.
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
	 * the session is RUNNING (never for a held completed session, like {@link #addDeath()}). The deaths paired with the
	 * kill that (re)starts the session die before it does, so the plugin adds them once it runs. The kill count itself
	 * still comes from the task count; this tally only decides the task's variant and boss flag.
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

	/**
	 * Tallies the death of an NPC the player damaged into the completed session held for late loot, when the death was
	 * paired with that session's final kill at {@code killAt}: the session stopped on its completion tick, so a
	 * paired death on the tick after was refused by {@link #addNpcKill}. Matters for a short task's variant or boss.
	 *
	 * @param killAt the final kill's clock time ({@link #getLastKillAt()} just after it)
	 * @param name   the name to tally, or {@code null} for none
	 * @return whether it was tallied
	 */
	public synchronized boolean addHeldNpcKill(long killAt, String name)
	{
		if (pendingCompletion == null || killAt == NO_TIME || killAt != pendingKillAt
			|| TaskStatistics.normalize(name) == null)
		{
			return false;
		}
		pendingCompletion = pendingCompletion.withNpcKill(name);
		return true;
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
	 * <li>cannon tasks only while the session's Cannon tag is Yes ({@link #getSetupTags}, the player's
	 * override, else at least half of its kills cannon kills, or with no kill context its fired cannonballs), as its
	 * saved record will be classified ({@link LedgerRecord#cannonTask}); else tasks with and without a cannon pooled.
	 * It never narrows to tasks without a cannon (owner decision): a cannon can still be set up later in the task.</li>
	 * </ul>
	 */
	public synchronized TaskSelection getCurrentTaskSelection()
	{
		if (!isSessionOnCurrentTask())
		{
			return TaskSelection.ANY;
		}
		return CatacombsVariants.selection(sessionMonster, npcKills, kills)
			.withCannon(getSetupTags().isCannon() ? Boolean.TRUE : null);
	}

	/**
	 * The variant and boss flag the active session's kills give its task ({@link TaskVariants#decide} of its kills per
	 * NPC name and its task kills, the numbers the saved record's are decided from), while the session is on the
	 * observed task (as {@link #getCurrentTaskSelection}); else {@link TaskVariants.Decision#NONE}. For display only: the
	 * statistics follow {@link #getCurrentTaskSelection}.
	 */
	public synchronized TaskVariants.Decision getCurrentVariant()
	{
		if (!isSessionOnCurrentTask())
		{
			return TaskVariants.Decision.NONE;
		}
		return TaskVariants.decide(sessionMonster, sessionCreatureId, npcKills, kills);
	}

	private boolean isSessionOnCurrentTask()
	{
		return state.isActive() && hasSessionTask && taskObserved
			&& currentCreatureId == sessionCreatureId
			&& (currentMonster == null || sessionMonster == null || currentMonster.equals(sessionMonster));
	}

	/**
	 * Records this tick's XP reading of every skill and adds the XP gained since the previous reading: while the session
	 * is RUNNING to it (and noted as use since the last activity, which Away takes back); otherwise, while a task is
	 * assigned and not Ended, to the buffer outside segments, which a (re)start counts from the segment start (so the
	 * fight that restarts the session, and the restart kill's own XP, count). Decided in one synchronized call, so a
	 * Pause or Resume click can't fall between the reading and the decision. The previous reading is the baseline
	 * whatever the state. Nothing is added when:
	 * <ul>
	 *     <li>the session is not RUNNING and no task is assigned, or it is Ended, or a completion is held for late loot
	 *     (it stopped on its completion tick, so XP after that tick never reaches it);</li>
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
	 * @return whether XP was added to the session (not when only buffered)
	 */
	public synchronized boolean observeXp(Map<String, Integer> reading)
	{
		final Map<String, Integer> previous = xpReading;
		xpReading = reading == null ? null : new TreeMap<>(reading);
		final boolean running = state == State.RUNNING;
		if (reading == null || previous == null || (!running && !isBuffering()))
		{
			return false;
		}
		final Map<String, Long> gains = SkillXp.gains(previous, reading);
		if (gains == null || gains.isEmpty())
		{
			return false;
		}
		if (!running)
		{
			buffer.addXp(clock.getAsLong(), gains);
			return false;
		}
		SkillXp.add(xpGained, gains);
		noteSinceActivity().addXp(clock.getAsLong(), gains);
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
	 * Sets (or, with an empty or {@code null} note, clears) the note of the given session: the active
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
		if (state.isActive() && sessionId.equals(this.sessionId))
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
	 * Sets the Slayer points earned and the streak of a completed session: the active session once
	 * {@link #observe} reported it {@link ObserveResult#COMPLETED}, or the completed session held for late loot (see
	 * {@link #holdCompletion()}), so the points can still be corrected while it is held. Recorded whether the session
	 * was running or stopped when the task completed: they belong to the task, not to the time tracked.
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
		if (state.isActive() && completed && sessionId.equals(this.sessionId))
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

	/**
	 * Sets the player's setup overrides (V2 plan 4.3) of the given session, replacing the earlier ones (each
	 * {@code null} in {@code overrides} clears that tag's override): the active session, or a completed session still
	 * held for late loot (see {@link #holdCompletion()}), so they reach the saved record like a note. They are saved
	 * with the session's snapshot and record.
	 *
	 * @param sessionId the session they were set for; another session's are never changed
	 * @param overrides the overrides, or {@code null} for none
	 * @return whether a session took them; {@code false} when it is no longer active or held
	 */
	public synchronized boolean setSetupOverrides(String sessionId, SetupOverrides overrides)
	{
		if (sessionId == null)
		{
			return false;
		}
		final SetupOverrides value = overrides == null ? SetupOverrides.NONE : overrides;
		if (state.isActive() && sessionId.equals(this.sessionId))
		{
			setupOverrides = value;
			return true;
		}
		if (pendingCompletion != null && sessionId.equals(pendingCompletion.getSessionId()))
		{
			pendingCompletion = pendingCompletion.withSetupOverrides(value);
			return true;
		}
		return false;
	}

	/** The setup overrides of the started (running, paused or just stopped) session; never {@code null}. */
	public synchronized SetupOverrides getSetupOverrides()
	{
		return setupOverrides;
	}

	/** The setup kill contexts of the started (running, paused or just stopped) session; never {@code null}. */
	public synchronized SetupKills getSetupKills()
	{
		return setupKills;
	}

	/**
	 * The setup tags of the started (running, paused or just stopped) session: detected from its kill contexts and the
	 * kills that may have one ({@link SetupTags#forSession}, {@link #contextKills}) with the player's overrides, as its
	 * record will have them.
	 */
	public synchronized SetupTags getSetupTags()
	{
		return SetupTags.forSession(supplies, setupKills, getContextKills(), setupOverrides);
	}

	/**
	 * The started (running, paused or just stopped) session's task kills that may have a kill context
	 * ({@link #contextKills}): what its Cannon tag shares its cannon kills over, as {@link #getSetupTags} does, and so
	 * the live card's Cannon tooltip.
	 */
	public synchronized int getContextKills()
	{
		return contextKills(kills, killsBeforeContexts);
	}

	/**
	 * A session's task kills that may have a kill context, the cannon share's denominator: its kills less those it
	 * counted before contexts were recorded (a session restored from a 1.x file). For a 2.0 session, all its kills.
	 */
	static int contextKills(int kills, int killsBeforeContexts)
	{
		return Math.max(0, kills - Math.max(0, killsBeforeContexts));
	}

	/**
	 * Counts one task kill's setup context (V2 plan 4.3) for the session whose latest task kill was at
	 * {@code killAt}: the active session (running or stopped since), or the completed session held for late loot when
	 * that kill was its final one. The plugin calls this once per kill counted, on the settled tick after the kill, when
	 * the NPC deaths paired with it are all known. Nothing is counted for another session's kill.
	 *
	 * @param killAt      the kill's clock time ({@link #getLastKillAt()} just after it)
	 * @param cannon      whether it was a cannon kill
	 * @param style       its style, or {@code null} for none (a cannon-only kill)
	 * @param gear        its gear focus, or {@code null} when not read
	 * @param prayerBonus the worn prayer bonus at the kill, or {@code null} when not read
	 * @return whether a session counted it
	 */
	public synchronized boolean addKillContext(long killAt, boolean cannon, SetupStyle style, GearFocus gear,
		Integer prayerBonus)
	{
		if (killAt == NO_TIME)
		{
			return false;
		}
		if (pendingCompletion != null && killAt == pendingKillAt)
		{
			pendingCompletion = pendingCompletion.withSetupKills(
				pendingCompletion.getSetupKills().withKill(cannon, style, gear, prayerBonus));
			return true;
		}
		if (!state.isActive() || killAt != lastKillAt)
		{
			return false;
		}
		setupKills = setupKills.withKill(cannon, style, gear, prayerBonus);
		return true;
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
	 * The active (running, or stopped and waiting to restart) session, or {@code null}. Does not change state.
	 */
	public synchronized Summary activeSummary()
	{
		return state.isActive() ? summary() : null;
	}

	/**
	 * A small snapshot of the active session's task for the overlay, read in one call so its parts can't tear, or
	 * {@code null} when no session is active or it has no task yet. A completed session held for late loot is not
	 * active. The kills left are the latest observed count only while the task has been observed for this login and the
	 * observed task is the session's; otherwise (after logout until the next reading, or while a cleared task waits for
	 * its completion message) they are {@link LiveTask#UNKNOWN_REMAINING}. Cheap: copies no tallies.
	 */
	public synchronized LiveTask liveTask()
	{
		if (!state.isActive() || !hasSessionTask)
		{
			return null;
		}
		boolean matches = taskObserved && currentRemaining > 0 && currentCreatureId == sessionCreatureId
			&& (currentMonster == null || sessionMonster == null || currentMonster.equals(sessionMonster));
		return new LiveTask(getStatus(), sessionId, sessionMonster, sessionCreatureId, kills, getActiveMillis(),
			matches ? currentRemaining : LiveTask.UNKNOWN_REMAINING);
	}

	/**
	 * Whether the active session was restored from disk and has not restarted yet.
	 */
	public synchronized boolean isRestoredPending()
	{
		return restoredPending && state == State.LOGGED_OUT;
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
		return observe(monster, creatureId, remaining, originalAmount, assignment, KillPlace.NONE,
			completionSignaled, KillPairing.NONE);
	}

	/**
	 * Records the latest task state read from the game, with the map region the local player is in. Kills counted by
	 * this observation are tallied in {@code region} ({@link LocationTally}), in the same step, so they follow exactly
	 * the rules of the kills themselves (RUNNING only; a final drop to zero is tallied where it dropped once the
	 * completion confirms it). Their spot is decided from the region alone ({@link KillPlace#ofRegion}); the plugin
	 * passes its tile's spot too ({@link #observe(String, int, int, int, TaskContext, KillPlace, boolean,
	 * KillPairing)}). See {@link #observe(String, int, int, int, TaskContext, boolean)} for the rest.
	 *
	 * @param region map region id of the local player's tile ({@code WorldPoint#getRegionID}, instances as their
	 *               template), or {@link LocationTally#NO_REGION} when unknown (the kills still count, untallied)
	 */
	public synchronized ObserveResult observe(String monster, int creatureId, int remaining, int originalAmount,
		TaskContext assignment, int region, boolean completionSignaled)
	{
		return observe(monster, creatureId, remaining, originalAmount, assignment, KillPlace.ofRegion(region),
			completionSignaled, KillPairing.NONE);
	}

	/**
	 * {@link #observe(String, int, int, int, TaskContext, KillPlace, boolean, KillPairing)} at a place known only by
	 * its region ({@link KillPlace#ofRegion}).
	 */
	public synchronized ObserveResult observe(String monster, int creatureId, int remaining, int originalAmount,
		TaskContext assignment, int region, boolean completionSignaled, KillPairing pairing)
	{
		return observe(monster, creatureId, remaining, originalAmount, assignment, KillPlace.ofRegion(region),
			completionSignaled, pairing);
	}

	/**
	 * Records the latest task state read from the game, and a task kill if the count dropped (V2 plan 4.1). See
	 * {@link #observe(String, int, int, int, TaskContext, int, boolean)} for the task and the region; kills counted are
	 * tallied at {@code place}, the player's tile's region and Slayer spot ({@link KillPlace#at}).
	 *
	 * <ul>
	 * <li>A drop on the same assignment while no session exists starts one, for a known account
	 * ({@link #setAccount}), unless the assignment is Ended or {@code pairing} blocks a start; while the session is
	 * Away, Paused or Logged out it restarts it. Either way the segment opens at the kill's resume point
	 * ({@link #openSegmentAtKill}), then the kills are counted.</li>
	 * <li>A drop to zero is a kill only once the completion confirms it (a cancelled task drops to zero too); then the
	 * restart comes first, so the backdated last fight counts, then the completion.</li>
	 * <li>The first reading after a login, a plugin start or an account change ({@link #clearCurrentTask}) only sets
	 * the baselines: a count that changed meanwhile was not seen to change.</li>
	 * </ul>
	 *
	 * @param pairing the NPCs the player damaged that died around this reading ({@link DamagedNpcs#pairing}), or
	 *                {@link KillPairing#NONE}
	 */
	public synchronized ObserveResult observe(String monster, int creatureId, int remaining, int originalAmount,
		TaskContext assignment, KillPlace place, boolean completionSignaled, KillPairing pairing)
	{
		final KillPairing kill = pairing == null ? KillPairing.NONE : pairing;
		final KillPlace here = place == null || !place.isKnown() ? KillPlace.NONE : place;
		final long now = clock.getAsLong();
		remaining = Math.max(0, remaining);
		final boolean resync = !taskObserved;
		final PendingFirstKill previous = new PendingFirstKill(here, kill, now);
		taskObserved = true;
		currentPlace = here;
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
		noteAssignment(now);

		if (state == State.IDLE)
		{
			return observeWithoutSession(previous, resync, completionSignaled);
		}
		if (!state.isActive())
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
					clearPendingFinal();
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
				if (pendingFinalRestart && state != State.RUNNING)
				{
					// The final kill restarts the session first, so its backdated fight counts, then completes it.
					openSegmentAtKill(pendingFinalPairing, pendingFinalAt);
				}
				if (pendingFinalKills > 0)
				{
					noteKill(pendingFinalAt, pendingFinalPairing);
				}
				countKills(pendingFinalKills, pendingFinalPlace);
				clearPendingFinal();
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
			final boolean finalKill = !resync && drop > 0;
			if (completionSignaled)
			{
				if (finalKill)
				{
					if (state != State.RUNNING)
					{
						// Restart first, so the backdated last fight counts, then complete.
						openSegmentAtKill(kill, now);
					}
					noteKill(now, kill);
					countKills(drop, here);
				}
				completed = true;
				return ObserveResult.COMPLETED;
			}
			// Not a kill yet: a cancelled task drops to zero too. The completion confirms it (see above).
			pendingFinalKills = finalKill ? drop : 0;
			pendingFinalRestart = finalKill;
			pendingFinalPairing = kill;
			pendingFinalAt = now;
			pendingFinalPlace = here;
			awaitingCompletion = true;
			preZeroBaseline = drop;
			zeroObservations = 1;
			return ObserveResult.TASK_CLEARED;
		}

		if (remaining < baseline && !resync)
		{
			if (state != State.RUNNING)
			{
				openSegmentAtKill(kill, now);
			}
			noteKill(now, kill);
			countKills(baseline - remaining, here);
		}
		// Increases, and the first reading after a login, only move the baseline.
		baseline = remaining;
		updateSessionTaskDetails();
		return ObserveResult.NONE;
	}

	/**
	 * {@link #observe} while no session exists: a drop of the count on the same assignment is its first kill and starts
	 * a session (V2 plan 4.1), unless the reading is a resync, no account is known, the assignment is Ended or the
	 * pairing blocks a start. A drop to zero starts one only with its completion (then it completes at once).
	 */
	private ObserveResult observeWithoutSession(PendingFirstKill previous, boolean resync, boolean completionSignaled)
	{
		if (pendingFirstKill != null)
		{
			final PendingFirstKill first = pendingFirstKill;
			if (resync || currentRemaining > 0 || ++pendingFirstKillZeros > MAX_TRANSIENT_ZERO_OBSERVATIONS + 1)
			{
				// The count came back (a transient zero), or the wait ran out: no kill.
				pendingFirstKill = null;
			}
			else if (completionSignaled)
			{
				pendingFirstKill = null;
				startSession(first.monster, first.creatureId, first.originalAmount, first.context, first.remaining,
					first.pairing, first.at);
				countKills(first.remaining, first.place);
				baseline = 0;
				completed = true;
				return ObserveResult.COMPLETED;
			}
			if (currentRemaining <= 0)
			{
				return ObserveResult.NONE;
			}
		}
		if (resync || previous.remaining <= 0 || !canStart(previous.pairing))
		{
			return ObserveResult.NONE;
		}
		if (currentRemaining > 0)
		{
			final boolean sameAssignment = previous.creatureId == currentCreatureId
				&& (previous.monster == null || currentMonster == null || previous.monster.equals(currentMonster))
				&& (previous.originalAmount == UNKNOWN_AMOUNT || currentOriginalAmount == UNKNOWN_AMOUNT
				|| previous.originalAmount == currentOriginalAmount);
			if (!sameAssignment || currentRemaining >= previous.remaining)
			{
				return ObserveResult.NONE;
			}
			startSession(currentMonster, currentCreatureId, currentOriginalAmount, currentContext, previous.remaining,
				previous.pairing, previous.at);
			countKills(previous.remaining - currentRemaining, previous.place);
			baseline = currentRemaining;
			return ObserveResult.NONE;
		}
		if (completionSignaled)
		{
			startSession(previous.monster, previous.creatureId, previous.originalAmount, previous.context,
				previous.remaining, previous.pairing, previous.at);
			countKills(previous.remaining, previous.place);
			baseline = 0;
			completed = true;
			return ObserveResult.COMPLETED;
		}
		// Not a kill yet: a cancelled task drops to zero too. Its completion, if it comes, starts the session.
		pendingFirstKill = previous;
		pendingFirstKillZeros = 1;
		return ObserveResult.NONE;
	}

	/** Whether a kill may start a new session now: a known account, an assignment not Ended, and no start blocked. */
	private boolean canStart(KillPairing pairing)
	{
		return account != NO_ACCOUNT && !ended && pairing.isStartAllowed();
	}

	/**
	 * Starts a session on its first kill at {@code killAt}: it takes the given task with {@code countBefore} as its
	 * initial count and baseline, and opens its first segment at the kill's resume point ({@link #openSegmentAtKill}).
	 * The caller counts the kills.
	 */
	private void startSession(String monster, int creatureId, int original, TaskContext context, int countBefore,
		KillPairing pairing, long killAt)
	{
		clearSession();
		sessionId = UUID.randomUUID().toString();
		sessionAccountHash = account;
		priorSessionEnd = previousSessionEnd;
		hasSessionTask = true;
		sessionMonster = monster;
		sessionCreatureId = creatureId;
		takenTaskKeys.add(creatureId);
		initialAmount = countBefore;
		baseline = countBefore;
		originalAmount = original;
		sessionContext = context == null ? TaskContext.NONE : context;
		openSegmentAtKill(pairing, killAt);
		startedAt = segmentStart;
		noteKill(killAt, pairing);
	}

	/**
	 * Opens a segment for a task kill at {@code killAt} (V2 plan 4.1): at its resume point, the earliest engagement start
	 * of the NPCs paired with it, or the kill itself when none is known (a cannon-only kill), never later than the kill
	 * and clamped to no earlier than the assignment, the previous session's end and the close of the previous segment.
	 * The clamps win: a final kill confirmed by its completion after a Pause opens at the Pause, never before it, so no
	 * time is counted twice. {@link #pairKill} may still backdate it once the next tick's deaths are known.
	 *
	 * <p>The buffer outside segments is counted from it ({@link #countBuffered}): XP and loot from the segment start, or
	 * from the start of the kill's tick when that is earlier (the plugin reads that tick's XP just before it sees the
	 * kill, {@link KillPairing#getTickStart()}), and supplies from {@link #WARM_UP_MILLIS} before that, never before the
	 * clamps. What is older stays in the buffer until {@link #pairKill} has the final pairing.</p>
	 */
	private void openSegmentAtKill(KillPairing pairing, long killAt)
	{
		final long floor = resumeFloor();
		final long resume = pairing != null && pairing.hasStart() ? pairing.getEarliestStart() : killAt;
		segmentStart = Math.max(floor, Math.min(killAt, resume));
		state = State.RUNNING;
		restoredPending = false;
		restartKillAt = killAt;
		restartFloor = floor;
		lastActivity = killAt;
		final long killTick = pairing == null ? killAt : Math.min(killAt, pairing.getTickStart());
		countBuffered(floor, Math.min(segmentStart, killTick));
	}

	/**
	 * The clamps of a resume point (V2 plan 4.1): no earlier than the assignment, the previous session's end and the
	 * close of the previous segment; {@link #NO_TIME} when none is known.
	 */
	private long resumeFloor()
	{
		return Math.max(assignmentAt, Math.max(previousSessionEnd, lastSegmentClose));
	}

	/** Opens a segment now, with nothing to backdate (Resume, a cave entry, and the {@link #startNow} test seam). */
	private void openSegmentNow(long now)
	{
		segmentStart = now;
		state = State.RUNNING;
		restoredPending = false;
		restartKillAt = NO_TIME;
		restartFloor = NO_TIME;
		lastActivity = now;
	}

	/**
	 * A restart at once (Resume, entering the Fight Caves or the Inferno): opens a segment now, counts the buffered
	 * XP and loot from now and supplies from the warm-up before it ({@link #countBuffered}), and drops the rest of the
	 * buffer, since nothing can backdate it.
	 */
	private void restartNow(long now)
	{
		final long floor = resumeFloor();
		openSegmentNow(now);
		countBuffered(floor, now);
		buffer.clear();
	}

	/**
	 * Counts what the buffer outside segments holds for a segment counting from {@code countFrom} (V2 plan 4.1): its
	 * XP and loot from {@code countFrom}, and its supplies from {@link #WARM_UP_MILLIS} before it, neither before
	 * {@code floor} (the resume point's clamps). They are counted in the order they arrived and leave the buffer; what
	 * lies before {@code floor} is dropped, as it can never count.
	 */
	private void countBuffered(long floor, long countFrom)
	{
		buffer.dropBefore(floor);
		for (TimedUsage.Entry entry : buffer.take(warmUpFrom(floor, countFrom), Math.max(floor, countFrom)))
		{
			count(entry);
		}
	}

	/** Where the supplies warm-up of a segment counting from {@code countFrom} starts: never before {@code floor}. */
	private static long warmUpFrom(long floor, long countFrom)
	{
		final long warmUp = countFrom < NO_TIME + WARM_UP_MILLIS ? NO_TIME : countFrom - WARM_UP_MILLIS;
		return Math.max(floor, warmUp);
	}

	/** Adds one buffered entry to the session's totals, as if it had arrived while running. */
	private void count(TimedUsage.Entry entry)
	{
		if (entry.getUsed() != null)
		{
			supplies.merge(entry.getUsed());
			putDoses(supplyDoses, entry.getDoses());
		}
		if (entry.getReturned() != null)
		{
			for (int id : entry.getReturned().itemIds())
			{
				supplies.subtract(id, entry.getReturned().get(id));
			}
		}
		SkillXp.add(xpGained, entry.getXp());
		if (entry.getLoot() != null)
		{
			loot.merge(entry.getLoot());
		}
		notableDrops = NotableDrops.add(notableDrops, entry.getDrops());
	}

	/**
	 * The open segment's use since its last activity ({@link #sinceActivity}), with what came at or before the last
	 * activity dropped first: only what came after it can be taken back.
	 */
	private TimedUsage noteSinceActivity()
	{
		sinceActivity.dropUpTo(Math.max(segmentStart, lastActivity));
		return sinceActivity;
	}

	/**
	 * Away (V2 plan 4.1): takes the supplies, XP and loot added after {@code last} (the last activity) back out of the
	 * totals, newest first, so returned ammo goes back exactly as it was taken off, and moves them to the buffer, where
	 * a later (re)start may count them again. Pets and known task NPCs' loot were never noted, so they stay.
	 */
	private void takeBackSince(long last)
	{
		final List<TimedUsage.Entry> taken = sinceActivity.takeAfter(last);
		if (taken.isEmpty())
		{
			return;
		}
		for (TimedUsage.Entry entry : taken)
		{
			if (entry.getReturned() != null)
			{
				supplies.merge(entry.getReturned());
			}
			if (entry.getUsed() != null)
			{
				for (int id : entry.getUsed().itemIds())
				{
					supplies.subtract(id, entry.getUsed().get(id));
				}
			}
			SkillXp.subtract(xpGained, entry.getXp());
			if (entry.getLoot() != null)
			{
				for (int id : entry.getLoot().itemIds())
				{
					loot.subtract(id, entry.getLoot().get(id));
				}
			}
			if (entry.getDrops() != null)
			{
				final List<NotableDrop> kept = new ArrayList<>(notableDrops);
				for (NotableDrop drop : entry.getDrops())
				{
					kept.remove(drop);
				}
				notableDrops = kept;
			}
		}
		// A potion, food or charge no longer used keeps no dose entry.
		supplyDoses.keySet().removeIf(id -> supplies.get(id) == 0);
		for (int i = taken.size() - 1; i >= 0; i--)
		{
			buffer.add(taken.get(i));
		}
	}

	/**
	 * Notes a bank trip counted now in {@code since} ({@link #bankTripsSinceActivity}), dropping the ones at or before
	 * the last activity first: only what came after it can be taken back.
	 */
	private void noteSinceActivity(ArrayDeque<Long> since)
	{
		final long last = Math.max(segmentStart, lastActivity);
		while (!since.isEmpty() && (since.peekFirst() <= last || since.size() >= MAX_COUNTERS_SINCE_ACTIVITY))
		{
			since.removeFirst();
		}
		since.addLast(clock.getAsLong());
	}

	/**
	 * Away: takes the bank trips counted after {@code last} (the last activity) back out of the counters. They
	 * are dropped, not buffered: they count only while the clock runs.
	 */
	private void takeBackBankTripsSince(long last)
	{
		final int trips = countAfter(bankTripsSinceActivity, last);
		if (trips > 0)
		{
			counters = counters.toBuilder().bankTrips(Math.max(0, counters.getBankTrips() - trips)).build();
		}
	}

	private static int countAfter(ArrayDeque<Long> times, long after)
	{
		int n = 0;
		for (long at : times)
		{
			if (at > after)
			{
				n++;
			}
		}
		return n;
	}

	/**
	 * Whether supplies, XP and loot outside a segment go to the buffer now: no segment is open, and a task is
	 * assigned (observed this login, with kills left) and not Ended.
	 */
	private boolean isBuffering()
	{
		return state != State.RUNNING && taskObserved && currentRemaining > 0 && !ended;
	}

	/**
	 * Whether supplies, XP and loot are collected now (V2 plan 4.1): counted while a segment is open, or buffered while
	 * a task is assigned and not Ended. Nothing is collected while no task is assigned: the plugin runs its
	 * supplies diff only while this holds.
	 */
	public synchronized boolean isCollecting()
	{
		return state == State.RUNNING || isBuffering();
	}

	/**
	 * Test seam: how many entries the buffer outside segments holds (each one clock time's supplies, XP and loot), to
	 * check that it is bounded and dropped.
	 */
	synchronized int bufferSize()
	{
		return buffer.size();
	}

	/**
	 * Closes the open segment at {@code at}: its time is kept, and {@code at} is the next resume point's clamp. Its use
	 * since the last activity can no longer be taken back.
	 */
	private void closeSegment(long at)
	{
		accumulatedMillis += Math.max(0, at - segmentStart);
		lastSegmentClose = Math.max(segmentStart, at);
		restartKillAt = NO_TIME;
		sinceActivity.clear();
		bankTripsSinceActivity.clear();
	}

	/**
	 * A task kill at {@code at}: activity for Away, and the paired NPCs' names become known task NPCs. Whether Araxxor
	 * died with it is kept for a completion it may cause ({@link #holdCompletion}).
	 */
	private void noteKill(long at, KillPairing pairing)
	{
		lastKillAt = at;
		lastKillAraxxor = pairsAraxxor(pairing);
		lastActivity = Math.max(lastActivity, at);
		learnTaskNpcs(pairing);
	}

	private void learnTaskNpcs(KillPairing pairing)
	{
		if (pairing != null)
		{
			for (String name : pairing.getNames())
			{
				learnTaskNpc(name);
			}
		}
	}

	/** Adds a known task NPC, normalized; bounded, since only a handful of names are ever a task's. */
	private void learnTaskNpc(String name)
	{
		final String key = TaskStatistics.normalize(name);
		if (key != null && taskNpcNames.size() < MAX_TASK_NPCS)
		{
			taskNpcNames.add(key);
		}
	}

	/**
	 * Notes the observed task as the current assignment (only a reading with a task does). The same assignment is the
	 * same account, creature key, name and assigned size (unknown parts match anything) with a count that has not gone
	 * up; anything else is a new assignment: it is first seen now (the clamp {@link #assignmentAt}) and is not Ended.
	 */
	private void noteAssignment(long now)
	{
		if (currentRemaining <= 0)
		{
			return;
		}
		final boolean same = hasAssignment && account == assignmentAccount && currentCreatureId == assignmentCreatureId
			&& (currentMonster == null || assignmentMonster == null || currentMonster.equals(assignmentMonster))
			&& (currentOriginalAmount == UNKNOWN_AMOUNT || assignmentOriginalAmount == UNKNOWN_AMOUNT
			|| currentOriginalAmount == assignmentOriginalAmount)
			&& currentRemaining <= assignmentRemaining;
		if (same)
		{
			if (assignmentMonster == null)
			{
				assignmentMonster = currentMonster;
			}
			if (assignmentOriginalAmount == UNKNOWN_AMOUNT)
			{
				assignmentOriginalAmount = currentOriginalAmount;
			}
			assignmentRemaining = currentRemaining;
			return;
		}
		setAssignment(account, currentMonster, currentCreatureId, currentOriginalAmount, currentRemaining, now);
	}

	private void setAssignment(long accountHash, String monster, int creatureId, int original, int remaining, long at)
	{
		hasAssignment = true;
		assignmentAccount = accountHash;
		assignmentMonster = monster;
		assignmentCreatureId = creatureId;
		assignmentOriginalAmount = original;
		assignmentRemaining = remaining;
		assignmentAt = at;
		ended = false;
	}

	private void clearPendingFinal()
	{
		pendingFinalKills = 0;
		pendingFinalPlace = KillPlace.NONE;
		pendingFinalRestart = false;
		pendingFinalPairing = KillPairing.NONE;
		pendingFinalAt = NO_TIME;
	}

	/** Counts {@code n} kills for the session and tallies them at {@code place} ({@link LocationTally#add}). */
	private void countKills(int n, KillPlace place)
	{
		if (n <= 0)
		{
			return;
		}
		kills += n;
		locationKills = LocationTally.add(locationKills, place, n);
	}

	/**
	 * Sets the bundled Slayer spot table, which decides the spot of kills tallied by region alone and breaks
	 * ties between spots; {@code null} is {@link SlayerSpots#NONE}. The plugin sets it once, when it starts.
	 */
	public synchronized void setSpots(SlayerSpots spots)
	{
		this.spots = spots == null ? SlayerSpots.NONE : spots;
	}

	/** The spot table set by {@link #setSpots}; never {@code null}. */
	public synchronized SlayerSpots getSpots()
	{
		return spots;
	}

	/** A copy of the session's kills per place so far, the place killed at most recently last. */
	public synchronized List<LocationKills> getLocationKills()
	{
		return new ArrayList<>(locationKills);
	}

	/**
	 * The started session's spot and region from its kills so far ({@link LocationTally#decide}), or
	 * {@link TaskSpot#UNKNOWN} with no kill tallied.
	 */
	public synchronized TaskSpot getTaskSpot()
	{
		return LocationTally.decide(locationKills, spots, sessionMonster);
	}

	/**
	 * The map region the player was in at the latest observation ({@link LocationTally#NO_REGION} when unknown, or
	 * after {@link #clearCurrentTask}).
	 */
	public synchronized int getCurrentRegion()
	{
		return currentPlace.getRegion();
	}

	/**
	 * The spot of the place the player was in at the latest observation: its tile's spot, else its region's for the
	 * current task ({@link SlayerSpots#spotForRegion}); {@code null} when unknown or after {@link #clearCurrentTask}.
	 */
	public synchronized String getCurrentSpot()
	{
		if (!currentPlace.isKnown())
		{
			return null;
		}
		return currentPlace.getSpot() != null ? currentPlace.getSpot()
			: spots.spotForRegion(currentPlace.getRegion(), currentMonster);
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

	/**
	 * The current task's assigned size as last read from the game, or {@link #UNKNOWN_AMOUNT} when not known (the live
	 * task card's bar).
	 */
	public synchronized int getCurrentOriginalAmount()
	{
		return currentOriginalAmount;
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
			return accumulatedMillis + Math.max(0, clock.getAsLong() - segmentStart);
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
			.taskSpot(LocationTally.decide(locationKills, spots, sessionMonster))
			.npcKills(npcKills)
			.setupKills(setupKills)
			.killsBeforeContexts(killsBeforeContexts)
			.setupOverrides(setupOverrides)
			// An open segment has no close yet: a snapshot of it closes it when it is saved.
			.segments(new SegmentInfo(state == State.STOPPED ? stoppedFrom : state,
				state == State.RUNNING ? NO_TIME : lastSegmentClose, assignmentAt, priorSessionEnd,
				new ArrayList<>(taskNpcNames)))
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
		clearPendingFinal();
		pendingFirstKill = null;
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
		setupOverrides = SetupOverrides.NONE;
		setupKills = SetupKills.NONE;
		killsBeforeContexts = 0;
		xpGained.clear();
		sessionContext = TaskContext.NONE;
		accumulatedMillis = 0;
		segmentStart = 0;
		lastSegmentClose = NO_TIME;
		lastActivity = NO_TIME;
		restartKillAt = NO_TIME;
		restartFloor = NO_TIME;
		stoppedFrom = State.IDLE;
		priorSessionEnd = NO_TIME;
		taskNpcNames.clear();
		sinceActivity.clear();
		bankTripsSinceActivity.clear();
		startedAt = 0;
		endedAt = 0;
	}

	/**
	 * The task reading before an observation (the count before a possible first kill, with the task it was on), kept
	 * as {@link #pendingFirstKill} when that kill dropped the count to zero and waits for its completion. Created
	 * before the observation replaces the tracker's current task, which it copies.
	 */
	@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
	private final class PendingFirstKill
	{
		final String monster = currentMonster;
		final int creatureId = currentCreatureId;
		final int originalAmount = currentOriginalAmount;
		final TaskContext context = currentContext;
		/** The count before the kill. */
		final int remaining = currentRemaining;
		/** Where the player was on the kill's tick. */
		final KillPlace place;
		final KillPairing pairing;
		/** The kill's clock time. */
		final long at;
	}

	/**
	 * Immutable snapshot of a session. Build one with {@link #builder()}, or change a copy with {@link #toBuilder()};
	 * the builder starts from an empty session (no id, {@link #NO_ACCOUNT}, {@link #NO_CREATURE},
	 * {@link #UNKNOWN_AMOUNT}, everything else 0 or empty, counters at 0 and XP gained known and empty).
	 */
	@Getter
	public static final class Summary
	{
		/** Session id (also used as the ledger record id), or {@code null} if no session was started. */
		private final String sessionId;
		/** Account that started the session, or {@link #NO_ACCOUNT}. */
		private final long accountHash;
		private final String monster;
		private final int creatureId;
		@Getter(AccessLevel.NONE)
		private final boolean hasTask;
		private final int initialAmount;
		/** Assigned task size, or {@link #UNKNOWN_AMOUNT}. */
		private final int originalAmount;
		private final int kills;
		private final long activeMillis;
		private final long startedAt;
		private final long endedAt;
		private final boolean completed;
		/** Remaining task count to resume from (the count before a pending drop to zero, if any). */
		private final int resumeRemaining;
		/** The session's loot, supplies, notable drops and boss kill counts (never {@code null}). */
		private final SessionLoot sessionLoot;
		/** The session's superior spawns, bank trips and deaths (never {@code null}; each {@code null} when unknown). */
		private final SessionCounters counters;
		/** The player's note, or {@code null}. */
		private final String note;
		/** The task's assignment and, for a completed task, its points earned and streak (never {@code null}). */
		private final TaskContext context;
		/**
		 * XP gained per skill while running ({@link SkillXp}; unmodifiable, possibly empty), or {@code null} when unknown
		 * (a snapshot from before XP was tracked).
		 */
		private final Map<String, Long> xpGained;
		/** The session's counted kills per place, the most recent last (never {@code null}; unmodifiable). */
		private final List<LocationKills> locationKills;
		/**
		 * The task's spot and the region saved with it ({@link LocationTally#decide}); {@link TaskSpot#UNKNOWN} when no
		 * kill was tallied. Never {@code null}.
		 */
		private final TaskSpot taskSpot;
		/** The session's kills per NPC name, in the order first seen (never {@code null}; unmodifiable). */
		private final List<NpcKills> npcKills;
		/** The session's setup kill contexts (never {@code null}). */
		private final SetupKills setupKills;
		/**
		 * Task kills counted before the session recorded kill contexts: those of a session restored from a 1.x
		 * active-session file; 0 for a 2.0 session.
		 */
		private final int killsBeforeContexts;
		/** The player's setup overrides (never {@code null}). */
		private final SetupOverrides setupOverrides;
		/** The segment clamps, the state the session was in and its known task NPCs (never {@code null}). */
		private final SegmentInfo segments;

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
			// Without a decision from the tracker or SessionSnapshot.toSummary(SlayerSpots) (a summary a test builds),
			// decided without the spot table.
			this.taskSpot = b.taskSpot != null ? b.taskSpot
				: LocationTally.decide(this.locationKills, SlayerSpots.NONE, b.monster);
			this.npcKills = Collections.unmodifiableList(NpcKillTally.sanitize(b.npcKills));
			this.setupKills = b.setupKills == null ? SetupKills.NONE : b.setupKills;
			this.killsBeforeContexts = Math.max(0, Math.min(b.kills, b.killsBeforeContexts));
			this.setupOverrides = b.setupOverrides == null ? SetupOverrides.NONE : b.setupOverrides;
			this.segments = b.segments == null ? SegmentInfo.NONE : b.segments;
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
				.taskSpot(taskSpot)
				.npcKills(npcKills)
				.setupKills(setupKills)
				.killsBeforeContexts(killsBeforeContexts)
				.setupOverrides(setupOverrides)
				.segments(segments);
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
			private TaskSpot taskSpot;
			private List<NpcKills> npcKills;
			private SetupKills setupKills = SetupKills.NONE;
			private int killsBeforeContexts;
			private SetupOverrides setupOverrides = SetupOverrides.NONE;
			private SegmentInfo segments = SegmentInfo.NONE;

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

			/** Counted kills per place, the most recent last ({@link LocationTally}), or {@code null}. */
			Builder locationKills(List<LocationKills> locationKills)
			{
				this.locationKills = locationKills;
				return this;
			}

			/**
			 * The task's spot and region as the tracker (or {@link SessionSnapshot#toSummary(SlayerSpots)}) decided them
			 * with its spot table ({@link LocationTally#decide}); {@code null} decides them from {@link #locationKills}
			 * without the table.
			 */
			Builder taskSpot(TaskSpot taskSpot)
			{
				this.taskSpot = taskSpot;
				return this;
			}

			/** Kills per NPC name in the order first seen ({@link NpcKillTally}), or {@code null}. */
			Builder npcKills(List<NpcKills> npcKills)
			{
				this.npcKills = npcKills;
				return this;
			}

			/** The setup kill contexts ({@link SetupKills}); {@code null} is {@link SetupKills#NONE}. */
			Builder setupKills(SetupKills setupKills)
			{
				this.setupKills = setupKills;
				return this;
			}

			/**
			 * Task kills counted before kill contexts were recorded, left out of the cannon share; kept between 0
			 * and the kills.
			 */
			Builder killsBeforeContexts(int killsBeforeContexts)
			{
				this.killsBeforeContexts = killsBeforeContexts;
				return this;
			}

			/** The player's setup overrides; {@code null} is {@link SetupOverrides#NONE}. */
			Builder setupOverrides(SetupOverrides setupOverrides)
			{
				this.setupOverrides = setupOverrides;
				return this;
			}

			/** The segment clamps, state and known task NPCs; {@code null} is {@link SegmentInfo#NONE}. */
			Builder segments(SegmentInfo segments)
			{
				this.segments = segments;
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

		/** A copy of this summary with the given setup overrides. */
		Summary withSetupOverrides(SetupOverrides newOverrides)
		{
			return toBuilder().setupOverrides(newOverrides).build();
		}

		/** A copy of this summary with the given setup kill contexts. */
		Summary withSetupKills(SetupKills newKills)
		{
			return toBuilder().setupKills(newKills).build();
		}

		/** A copy of this summary with the given task context. */
		Summary withContext(TaskContext newContext)
		{
			return toBuilder().context(newContext).build();
		}

		/** A copy of this summary with one more kill of {@code name} in its kills per NPC name ({@link NpcKillTally}). */
		Summary withNpcKill(String name)
		{
			return toBuilder().npcKills(NpcKillTally.add(npcKills, name)).build();
		}

		/**
		 * A copy of this summary with buffered supplies, XP and loot added ({@link #pairKill} backdating a held
		 * completion), in the order given, as {@link SessionTracker} counts them.
		 */
		Summary withCounted(List<TimedUsage.Entry> entries)
		{
			if (entries == null || entries.isEmpty())
			{
				return this;
			}
			final ItemTally addedLoot = new ItemTally(sessionLoot.getLoot());
			final ItemTally addedSupplies = new ItemTally(sessionLoot.getSupplies());
			final Map<Integer, Integer> doses = new TreeMap<>(sessionLoot.getSupplyDoses());
			List<NotableDrop> drops = sessionLoot.getNotableDrops();
			final Map<String, Long> xp = new TreeMap<>();
			SkillXp.add(xp, xpGained);
			for (TimedUsage.Entry entry : entries)
			{
				if (entry.getUsed() != null)
				{
					addedSupplies.merge(entry.getUsed());
					putDoses(doses, entry.getDoses());
				}
				if (entry.getReturned() != null)
				{
					for (int id : entry.getReturned().itemIds())
					{
						addedSupplies.subtract(id, entry.getReturned().get(id));
					}
				}
				SkillXp.add(xp, entry.getXp());
				if (entry.getLoot() != null)
				{
					addedLoot.merge(entry.getLoot());
				}
				drops = NotableDrops.add(drops, entry.getDrops());
			}
			return toBuilder()
				.sessionLoot(sessionLoot.toBuilder().loot(addedLoot).supplies(addedSupplies).supplyDoses(doses)
					.notableDrops(drops).build())
				.xpGained(xp)
				.build();
		}

		/**
		 * A copy of this summary whose last segment, which started at {@code segmentStart}, starts {@code millis}
		 * earlier ({@link #pairKill}): the active time grows by {@code millis}, and the session's start moves with it when
		 * that segment was its first.
		 */
		Summary backdated(long millis, long segmentStart)
		{
			return toBuilder()
				.activeMillis(activeMillis + millis)
				.startedAt(startedAt == segmentStart ? startedAt - millis : startedAt)
				.build();
		}

		public boolean hasTask()
		{
			return hasTask;
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
		 * The session's setup tags, detected from its kill contexts and the kills that may have one (or, with no kill
		 * context, its supplies; {@link SetupTags#forSession}) with the player's overrides: what its record stores.
		 */
		public SetupTags getSetupTags()
		{
			return SetupTags.forSession(sessionLoot.getSupplies(), setupKills, contextKills(kills, killsBeforeContexts),
				setupOverrides);
		}
	}

	/** Immutable snapshot of the active session's task progress ({@link #liveTask()}). */
	@Getter
	@AllArgsConstructor(access = AccessLevel.PACKAGE)
	public static final class LiveTask
	{
		/** {@link #getRemaining()} when the kills left on the session's task are not known right now. */
		public static final int UNKNOWN_REMAINING = -1;

		private final Status status;
		private final String sessionId;
		/** The session's task name, or {@code null} when unknown. */
		private final String monster;
		private final int creatureId;
		private final int kills;
		private final long activeMillis;
		/** Kills left on the session's task, or {@link #UNKNOWN_REMAINING}. */
		private final int remaining;
	}
}
