package com.shmayvan.slayerledger;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * Kills a session counted in one map region. Serialized with Gson in {@link SessionSnapshot}; effectively
 * immutable (no setters). Pure (no RuneLite types). The rules are in {@link LocationTally}.
 */
@Getter
@EqualsAndHashCode
@ToString
final class LocationKills
{
	/** Map region id of the local player's tile when the kills were counted (instances as their template region). */
	private int region;
	/** Kills counted in {@link #region}; at least 1. */
	private int kills;

	/** For Gson. */
	private LocationKills()
	{
	}

	LocationKills(int region, int kills)
	{
		this.region = region;
		this.kills = kills;
	}
}
