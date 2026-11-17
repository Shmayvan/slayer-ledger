package com.shmayvan.slayerledger;

import lombok.*;

/**
 * Kills a session counted at one place: a map region and the Slayer spot of the tiles they were counted on.
 * Serialized with Gson in {@link SessionSnapshot}; effectively immutable (no setters). Pure (no RuneLite types). The
 * rules are in {@link LocationTally}.
 */
@Getter
@EqualsAndHashCode
@ToString
@AllArgsConstructor(access = AccessLevel.PACKAGE)
final class LocationKills
{
	/** Map region id of the local player's tile when the kills were counted (instances as their template region). */
	private int region;
	/**
	 * The Slayer spot key of those tiles ({@link KillPlace#getSpot}), or {@code null} when it is decided from
	 * {@link #region} alone: a tile no spot of a shared region matched, or a tally kept before spots.
	 */
	private String spot;
	/** Kills counted at this place; at least 1. */
	private int kills;

	/** For Gson. */
	private LocationKills()
	{
	}

	/** Kills in {@code region}, whose spot is decided from the region alone. */
	LocationKills(int region, int kills)
	{
		this(region, null, kills);
	}
}
