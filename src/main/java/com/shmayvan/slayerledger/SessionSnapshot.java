package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * A persisted in-progress session (the account's "active session" file). Written on logout, client exit, plugin
 * shutdown and account switch, and restored as PAUSED on the next login if the task still matches. Serialized with
 * Gson; no RuneLite dependencies.
 */
@Data
public class SessionSnapshot
{
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
	 */
	public static final int CURRENT_SCHEMA_VERSION = 2;

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
	 * Counted kills per map region so far, in the order first seen ({@link LocationTally}), or {@code null} when none
	 * (and in older files).
	 */
	private List<LocationKills> locationKills;
	/**
	 * Kills per NPC name so far, in the order first seen ({@link NpcKillTally}), or {@code null} when none (and in
	 * older files).
	 */
	private List<NpcKills> npcKills;

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
		return s;
	}

	/**
	 * The snapshot as an ended, incomplete session summary (used when it cannot be restored).
	 */
	SessionTracker.Summary toSummary()
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
			.npcKills(npcKills)
			.build();
	}
}
