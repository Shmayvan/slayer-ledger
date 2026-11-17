package com.shmayvan.slayerledger;

import java.util.*;
import lombok.*;

/**
 * A persisted in-progress session (the account's "active session" file). Written on logout, client exit, plugin
 * shutdown and account switch, and restored as Logged out on the next login if the task still matches (the next task
 * kill restarts it). The same file may instead hold an Ended assignment's marker ({@link #isEndedMarker()}): no
 * session, only the assignment, so it stays Ended after a client restart. Serialized with Gson; no RuneLite
 * dependencies.
 */
@Data
public class SessionSnapshot
{
	/** {@link #trackingState} of an Ended assignment's marker. */
	static final String ENDED_STATE = "ENDED";
	/** Start of a marker's {@link #sessionId}: never a session's or a record's id. */
	static final String ENDED_ID_PREFIX = "ended-";

	/**
	 * Version 2 adds {@link #loot}. Raised from 1 so an older build, which would drop the loot when it rewrites the
	 * file, refuses to overwrite or restore it instead. {@link #supplies} and {@link #supplyDoses} were added to
	 * version 2 later as optional fields, before any build wrote supplies. {@link #superiorsSpawned} and
	 * {@link #bankTrips} were added the same way; a file without them restores with both counts at 0. {@link #note} was
	 * added the same way; a file without it has no note. {@link #deaths} was added the same way; a file without it
	 * restores with 0 deaths. {@link #slayerMaster}, {@link #slayerArea} and {@link #taskLocation} were added the same
	 * way; a file without them restores with the assignment read from the game, since the task must match. Points and
	 * streak are not kept: a snapshot is never of a completed task. {@link #notableDrops} was added the same way; a file
	 * without it restores with none. {@link #bossKillCounts} was added the same way; a file without it restores with
	 * none. {@link #xpGained} was added the same way; a file without it restores with no XP, and XP counts from the
	 * restore. {@link #locationKills} was added the same way; a file without it restores with none, and kills are
	 * tallied per region from the restore. {@link #npcKills} was added the same way; a file without it restores
	 * with none, and kills are tallied per NPC name from the restore.
	 *
	 * <p>Version 3 (2.0) adds the Ended assignment's marker ({@link #isEndedMarker()}), a file that holds no
	 * session. An older build would restore it as an empty session, so it may not read version 3 files; it leaves them
	 * untouched. Version 3 also adds the segment fields {@link #trackingState}, {@link #segmentClosedAt},
	 * {@link #assignedAt}, {@link #previousSessionEnd} and {@link #taskNpcs}, all optional: a version 2 (1.x) file has
	 * none and restores with its last segment closed when it was saved, and its known task NPCs learned again from the
	 * next kill.</p>
	 *
	 * <p>The setup fields (2.0) were added to version 3 later, as optional fields, before any released build
	 * wrote version 3: the player's overrides ({@link #cannonOverride}, {@link #styleOverride}, {@link #gearOverride}) and the
	 * kill-context counts ({@link #cannonKills}, {@link #styleKills}, {@link #gearKills}, {@link #prayerBonusMin},
	 * {@link #prayerBonusMax}; see {@link SetupKills}). A file without them restores with no overrides and no counts.
	 * The detected tags are not kept: they are worked out from the session again ({@link SetupTags#forSession}), and
	 * the spot is decided when the record is saved. Nor are the variant and the boss flag: they are worked out
	 * from {@link #npcKills} ({@link TaskVariants}).</p>
	 *
	 * <p>{@link #killsBeforeContexts} was added to version 3 the same way: a file without it counts none, unless it
	 * is a version 2 (1.x) file, whose kills all recorded no context ({@link #killsWithoutContexts()}).</p>
	 */
	public static final int CURRENT_SCHEMA_VERSION = 3;
	/**
	 * The first version a 2.0 build wrote, and the first whose sessions record kill contexts: an older
	 * (1.x) file's kills recorded none.
	 */
	static final int FIRST_2_0_SCHEMA_VERSION = 3;

	private int schemaVersion = CURRENT_SCHEMA_VERSION;
	/** Session id; becomes the ledger record id so a session can never become two records. */
	private String sessionId;
	private long accountHash;
	private String monster;
	/** Creature key, see {@link SessionTracker#creatureKey(int, int)}. */
	private int creatureId = SessionTracker.NO_CREATURE;
	private int initialAmount;
	/** Assigned task size, or {@code null} when unknown. */
	private Integer originalAmount;
	private int kills;
	private long activeMillis;
	private long startedAt;
	/** When the snapshot was taken; used as the end time if it later becomes a partial record. */
	private long savedAt;
	/** Remaining task count when the snapshot was taken. */
	private int remaining;
	/** Task-completion counters when the snapshot was taken, or {@code null} if unknown. */
	private int[] completionCounters;
	/** Loot tally so far (item id to quantity), or {@code null} when none (and in version 1 files). */
	private Map<Integer, Long> loot;
	/** Supplies used so far (item id to quantity, potions in doses), or {@code null} when none. */
	private Map<Integer, Long> supplies;
	/** Doses per item for potion ids in {@link #supplies}, or {@code null} when none. */
	private Map<Integer, Integer> supplyDoses;
	/** Superior spawns counted so far, or {@code null} in files from before they were counted. */
	private Integer superiorsSpawned;
	/** Bank trips counted so far, or {@code null} in files from before they were counted. */
	private Integer bankTrips;
	/** Deaths of the local player counted so far, or {@code null} in files from before they were counted. */
	private Integer deaths;
	/** The player's note on the session, or {@code null} when none (and in files from before notes existed). */
	private String note;
	/** SLAYER_MASTER value of the session task, or {@code null} when unknown (and in older files). */
	private Integer slayerMaster;
	/** SLAYER_AREA value of the session task, or {@code null} when none or unknown. */
	private Integer slayerArea;
	/** Name of {@link #slayerArea}, or {@code null}. */
	private String taskLocation;
	/** Notable drops so far, in the order they arrived, or {@code null} when none (and in older files). */
	private List<NotableDrop> notableDrops;
	/** Boss kill counts so far, in the order first seen, or {@code null} when none (and in older files). */
	private List<BossKillCount> bossKillCounts;
	/**
	 * XP gained so far per skill (keyed as {@link SkillXp} keys them, e.g. {@code "SLAYER"}), or {@code null} in files
	 * from before XP was tracked. Empty when the session has gained none yet.
	 */
	private Map<String, Long> xpGained;
	/**
	 * Counted kills per place so far ({@link LocationTally}), or {@code null} when none (and in older files). Each entry
	 * keeps its Slayer spot ({@link LocationKills#getSpot}, an optional member); a 1.x file's entries have
	 * regions only, and their spots are decided from the regions. The order is the order the tally keeps (1.x: first
	 * seen; since the place killed at most recently last).
	 */
	private List<LocationKills> locationKills;
	/**
	 * Kills per NPC name so far, in the order first seen ({@link NpcKillTally}), or {@code null} when none (and in
	 * older files).
	 */
	private List<NpcKills> npcKills;
	/**
	 * The session's state when it was persisted ({@link SessionTracker.State} name: RUNNING, AWAY, PAUSED or
	 * LOGGED_OUT), or {@link #ENDED_STATE} for an Ended assignment's marker; {@code null} in older files. A session is
	 * always restored as Logged out, whatever it was.
	 */
	private String trackingState;
	/**
	 * When the session's last segment closed (a logout, a pause, Away at the last activity), or {@code null} in older
	 * files, which restore with {@link #savedAt} instead. The next resume point is never earlier.
	 */
	private Long segmentClosedAt;
	/** When the session's assignment was first seen (a clamp of every resume point), or {@code null} when unknown. */
	private Long assignedAt;
	/**
	 * The end of the session before this one (a clamp of every resume point), or {@code null} when unknown. In a
	 * marker, the Ended session's end, so a kill after Track again is never backdated before it.
	 */
	private Long previousSessionEnd;
	/** The session's known task NPCs (normalized names paired with its kills), or {@code null} when none. */
	private List<String> taskNpcs;
	/** Task kills made while the cannon was firing ({@link SetupKills}), or {@code null} when none were counted. */
	private Integer cannonKills;
	/** Task kills per style ({@link SetupStyle} name), or {@code null} when none were counted. */
	private Map<String, Integer> styleKills;
	/** Task kills per gear focus ({@link GearFocus} name), or {@code null} when none were counted. */
	private Map<String, Integer> gearKills;
	/** The lowest worn prayer bonus seen at a task kill, or {@code null}. */
	private Integer prayerBonusMin;
	/** The highest worn prayer bonus seen at a task kill, or {@code null}. */
	private Integer prayerBonusMax;
	/** The player's cannon override on the live task, or {@code null} for none ({@link SetupOverrides}). */
	private Boolean cannonOverride;
	/** The player's style override on the live task, or {@code null} for none. */
	private SetupStyle styleOverride;
	/** The player's gear focus override on the live task, or {@code null} for none. */
	private GearFocus gearOverride;
	/**
	 * Task kills the session counted before it recorded kill contexts: those of a session a 1.x version started,
	 * which a 2.0 build restored and persisted again. Left out of the cannon share
	 * ({@link SessionTracker#contextKills}). {@code null} for none; read through {@link #killsWithoutContexts()}.
	 */
	private Integer killsBeforeContexts;

	/** Whether this is an Ended assignment's marker (no session; see the class comment). */
	boolean isEndedMarker()
	{
		return ENDED_STATE.equals(trackingState);
	}

	/**
	 * The session's task kills that recorded no kill context, between 0 and its kills: the stored
	 * {@link #killsBeforeContexts}, or every kill of a 1.x file (below {@link #FIRST_2_0_SCHEMA_VERSION}), which recorded
	 * none. 0 for a 2.0 session that has none stored, whatever its contexts.
	 */
	int killsWithoutContexts()
	{
		final int before = killsBeforeContexts != null ? killsBeforeContexts
			: schemaVersion < FIRST_2_0_SCHEMA_VERSION ? kills : 0;
		return Math.max(0, Math.min(kills, before));
	}

	static SessionSnapshot fromSummary(SessionTracker.Summary summary, int[] completionCounters, long savedAt)
	{
		SessionSnapshot s = new SessionSnapshot();
		s.setSessionId(summary.getSessionId());
		s.setAccountHash(summary.getAccountHash());
		s.setMonster(summary.getMonster());
		s.setCreatureId(summary.getCreatureId());
		s.setInitialAmount(summary.getInitialAmount());
		s.setOriginalAmount(summary.getOriginalAmount() == SessionTracker.UNKNOWN_AMOUNT ? null : summary.getOriginalAmount());
		s.setKills(summary.getKills());
		s.setActiveMillis(summary.getActiveMillis());
		s.setStartedAt(summary.getStartedAt());
		s.setSavedAt(savedAt);
		s.setRemaining(summary.getResumeRemaining());
		s.setCompletionCounters(completionCounters == null ? null : Arrays.copyOf(completionCounters, completionCounters.length));
		summary.getSessionLoot().copyTo(s);
		summary.getCounters().copyTo(s);
		s.setNote(summary.getNote());
		final TaskContext context = summary.getContext();
		s.setSlayerMaster(context.getMaster());
		s.setSlayerArea(context.getArea());
		s.setTaskLocation(context.getLocation());
		s.setXpGained(LedgerRecord.xpGainedFor(summary));
		s.setLocationKills(summary.getLocationKills().isEmpty() ? null : new ArrayList<>(summary.getLocationKills()));
		s.setNpcKills(summary.getNpcKills().isEmpty() ? null : new ArrayList<>(summary.getNpcKills()));
		final SegmentInfo segments = summary.getSegments();
		s.setTrackingState(segments.getState() == null ? null : segments.getState().name());
		// A segment still open (a snapshot of a running session) closes when it is saved.
		s.setSegmentClosedAt(segments.getSegmentClosedAt() != null ? segments.getSegmentClosedAt() : savedAt);
		s.setAssignedAt(segments.getAssignedAt());
		s.setPreviousSessionEnd(segments.getPreviousSessionEnd());
		s.setTaskNpcs(segments.getTaskNpcs().isEmpty() ? null : new ArrayList<>(segments.getTaskNpcs()));
		summary.getSetupKills().copyTo(s);
		s.setKillsBeforeContexts(summary.getKillsBeforeContexts() > 0 ? summary.getKillsBeforeContexts() : null);
		summary.getSetupOverrides().copyTo(s);
		return s;
	}

	/**
	 * The snapshot as an ended, incomplete session summary (used when it cannot be restored). Its spot and the region
	 * saved with it are decided from {@link #locationKills} with {@code spots} ({@link LocationTally#decide}), exactly as
	 * for a live session: kills tallied by region alone (a 1.x file) take their region's spot, and a tie goes to the spot
	 * that lists the task.
	 *
	 * @param spots the plugin's spot table ({@link SlayerSpots#NONE} or {@code null} when unavailable)
	 */
	SessionTracker.Summary toSummary(SlayerSpots spots)
	{
		return SessionTracker.Summary.builder()
			.sessionId(sessionId)
			.accountHash(accountHash)
			.monster(monster)
			.creatureId(creatureId)
			.hasTask(true)
			.initialAmount(initialAmount)
			.originalAmount(originalAmount == null ? SessionTracker.UNKNOWN_AMOUNT : originalAmount)
			.kills(kills)
			.activeMillis(activeMillis)
			.startedAt(startedAt)
			.endedAt(savedAt)
			.completed(false)
			.resumeRemaining(remaining)
			.sessionLoot(SessionLoot.from(this))
			.counters(SessionCounters.from(this))
			.note(TaskNote.normalize(note))
			.context(TaskContext.of(slayerMaster, slayerArea, taskLocation, null, null))
			.xpGained(xpGained)
			.locationKills(locationKills)
			.taskSpot(LocationTally.decide(locationKills, spots, monster))
			.npcKills(npcKills)
			.setupKills(SetupKills.from(this))
			.killsBeforeContexts(killsWithoutContexts())
			.setupOverrides(SetupOverrides.from(this))
			.build();
	}
}
