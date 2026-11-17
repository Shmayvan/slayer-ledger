package com.shmayvan.slayerledger;

import lombok.*;

/**
 * Where the local player stood when the task count was read (V2 plan 4.5): the map region and the Slayer spot of
 * the player's tile ({@link SlayerSpots#spotAt}), instances as their template. Kills counted on that reading are
 * tallied there ({@link LocationTally}). Immutable and pure (no RuneLite types).
 */
@Getter
@EqualsAndHashCode
@ToString
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class KillPlace
{
	/** No place known: no player or scene, or the player is on a Sailing boat. */
	static final KillPlace NONE = new KillPlace(LocationTally.NO_REGION, null);

	/** Map region id, or {@link LocationTally#NO_REGION}. */
	private final int region;
	/**
	 * The spot key of the tile ({@link SlayerSpots}: a table id or {@code region:N}), or {@code null} when it is
	 * decided from the region alone ({@link SlayerSpots#spotForRegion}), as for a tile in a shared region that no spot
	 * of it matches.
	 */
	private final String spot;

	/** A place known only by its region, whose spot is decided from the region ({@link SlayerSpots#spotForRegion}). */
	static KillPlace ofRegion(int region)
	{
		return LocationTally.isRegion(region) ? new KillPlace(region, null) : NONE;
	}

	/** The place of tile {@code (x, y)} on {@code plane}: its region and its spot in {@code spots}. */
	static KillPlace at(SlayerSpots spots, int x, int y, int plane)
	{
		final int region = SlayerSpots.regionOf(x, y);
		if (!LocationTally.isRegion(region))
		{
			return NONE;
		}
		return new KillPlace(region, spots == null ? null : spots.spotAt(x, y, plane));
	}

	/** Whether a region is known. */
	boolean isKnown()
	{
		return LocationTally.isRegion(region);
	}
}
