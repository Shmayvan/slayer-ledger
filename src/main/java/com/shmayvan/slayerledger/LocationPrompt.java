package com.shmayvan.slayerledger;

import java.util.HashSet;
import java.util.Set;

/**
 * Which map region the panel's "Name this location" prompt asks about. A counted kill in a region without a
 * name offers it; one region is asked about at a time, and the prompt stays until the player saves a name or skips
 * it. A skipped region is not asked about again: the plugin keeps the skip in the RuneScape profile's config and
 * says so in {@link #offer}; this class also remembers it until {@link #forgetSkips} or {@link #reset}, so a skip that
 * could not be kept (no profile yet) still holds for the account. Pure (no RuneLite types); client thread only.
 */
final class LocationPrompt
{
	private final Set<Integer> skipped = new HashSet<>();
	private int shown = LocationTally.NO_REGION;

	/**
	 * Offers the region of a counted kill.
	 *
	 * @param named        whether the region already has a name
	 * @param skippedSaved whether the active profile's config has the region as skipped
	 * @return whether the prompt should now be shown for {@code region}
	 */
	boolean offer(int region, boolean named, boolean skippedSaved)
	{
		if (!LocationTally.isRegion(region) || named || skippedSaved || LocationTally.isRegion(shown)
			|| skipped.contains(region))
		{
			return false;
		}
		shown = region;
		return true;
	}

	/** The player skipped naming {@code region}: it is not asked about again until {@link #forgetSkips}. */
	void skip(int region)
	{
		skipped.add(region);
		if (shown == region)
		{
			shown = LocationTally.NO_REGION;
		}
	}

	/** {@code region} got a name; its prompt (if shown) is done. */
	void named(int region)
	{
		if (shown == region)
		{
			shown = LocationTally.NO_REGION;
		}
	}

	/** Hides the prompt (logout, another profile) without skipping its region: a later kill there asks again. */
	void hide()
	{
		shown = LocationTally.NO_REGION;
	}

	/**
	 * Forgets the skipped regions (another profile is active, or the player asked about them again), without hiding a
	 * shown prompt.
	 */
	void forgetSkips()
	{
		skipped.clear();
	}

	/** Forgets everything, for a new plugin run. */
	void reset()
	{
		skipped.clear();
		shown = LocationTally.NO_REGION;
	}

	/** The region the prompt is shown for, or {@link LocationTally#NO_REGION}. */
	int getShown()
	{
		return shown;
	}
}
