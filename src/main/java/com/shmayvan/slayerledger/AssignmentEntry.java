package com.shmayvan.slayerledger;

import lombok.Data;

/**
 * A Slayer assignment that ended without a saved session: skipped, blocked, reset, completed while no session was
 * tracked, or ended for a reason the plugin couldn't tell ({@link AssignmentOutcome}). Stored in the ledger file's
 * {@code assignments} list ({@link LedgerStore}), next to the records but never among them, and serialized with Gson
 * like {@link LedgerRecord}: {@code null} fields are left out when writing and missing fields read as {@code null}.
 *
 * <p><b>An entry is not a played task.</b> It has no kills, time, loot or supplies, and it never counts anywhere
 * played tasks do: the history list and its totals, the Monster filter, the saved-task count, the time-left estimate
 * ({@link TaskTimeEstimate}) and today's prices only ever see {@link LedgerRecord}s. Phase 3 (per-monster averages
 * and new-task estimates) must also use the records only and ignore these entries (owner decision).</p>
 *
 * <p>Added to schema 2 as an optional list (no schema bump), following the precedent of the optional
 * {@link LedgerRecord} fields: a file without it loads with no entries, and is written back without the field until
 * an entry is added. An older build would read such a file and drop the list on its next write.</p>
 */
@Data
public class AssignmentEntry
{
	/** Random id, so the entry can be deleted from the history. */
	private String id;
	/** Task name, or {@code null} when the lookup failed. */
	private String monster;
	/** Slayer task creature key ({@link SessionTracker#creatureKey}), or {@code null} when unknown. */
	private Integer creatureId;
	/** Assigned task size, or {@code null} when unknown. */
	private Integer originalAmount;
	/** SLAYER_MASTER value, as in {@link LedgerRecord#getSlayerMaster()}; {@code null} when unknown. */
	private Integer slayerMaster;
	/** SLAYER_AREA value (Konar), or {@code null}. */
	private Integer slayerArea;
	/** The area's name, or {@code null}. */
	private String taskLocation;
	/** When the assignment was first seen to have ended (epoch ms). */
	private long endedAt;
	/** How it ended; {@code null} reads as {@link AssignmentOutcome#UNKNOWN} (for example an outcome from a newer build, which this build also writes back as {@code null}, so a downgrade loses it). */
	private AssignmentOutcome outcome;
	/**
	 * Slayer points after the end minus points before it (negative when points were spent), or {@code null} when not
	 * observed (no reading before the end).
	 */
	private Integer pointsChange;

	/** The assignment's master and area as a {@link TaskContext} (no rewards). */
	TaskContext context()
	{
		return TaskContext.of(slayerMaster, slayerArea, taskLocation, null, null);
	}
}
