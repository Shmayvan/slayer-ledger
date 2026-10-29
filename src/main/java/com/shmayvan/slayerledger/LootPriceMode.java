package com.shmayvan.slayerledger;

/**
 * Which price values kill loot; see {@link LootPricing}. Supplies are always valued at GE prices, because they are
 * bought.
 *
 * <p>A "Higher of the two" option was removed (owner decision, 2026-10-25). A config value saved for it no longer
 * matches a constant; RuneLite's config proxy then returns the item's default, GE.</p>
 */
public enum LootPriceMode
{
	/** The Grand Exchange price. */
	GE("GE"),
	/** The high-alchemy value. */
	HIGH_ALCH("High alch");

	private final String displayName;

	LootPriceMode(String displayName)
	{
		this.displayName = displayName;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
