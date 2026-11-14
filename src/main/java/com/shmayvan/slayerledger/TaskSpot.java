package com.shmayvan.slayerledger;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * A task's location as its kills decide it (V2 plan 4.5, {@link LocationTally#decide}): its Slayer spot and the
 * map region it is saved with. Immutable and pure (no RuneLite types).
 */
@Getter
@EqualsAndHashCode
@ToString
final class TaskSpot
{
	/** No kill was tallied: no spot and no region. */
	static final TaskSpot UNKNOWN = new TaskSpot(null, null);

	/**
	 * The spot key ({@link SlayerSpots}: a table id or {@code region:N}), or {@code null} for the Unknown spot (no kill
	 * tallied, or none at a place the table decides).
	 */
	private final String spotId;
	/**
	 * The region saved with it ({@link LedgerRecord#getLocationRegion}): the spot's region with the most kills, or with
	 * an Unknown spot the region with the most kills of all; {@code null} when no kill was tallied.
	 */
	private final Integer region;

	TaskSpot(String spotId, Integer region)
	{
		this.spotId = spotId;
		this.region = region;
	}
}
