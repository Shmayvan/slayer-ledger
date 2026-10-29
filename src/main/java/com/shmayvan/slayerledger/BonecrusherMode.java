package com.shmayvan.slayerledger;

/**
 * How kill loot that a bonecrusher or ash sanctifier would destroy is handled; see {@link CrushedLootFilter}.
 */
public enum BonecrusherMode
{
	/**
	 * Bones are left out of kill loot while a bonecrusher or bonecrusher necklace is in the inventory or worn, and
	 * demonic ashes while an ash sanctifier is, checked at each loot drop. Charges are not checked.
	 */
	AUTO("Auto"),
	/** Bones and demonic ashes are always left out of kill loot. */
	ALWAYS_EXCLUDE("Always exclude"),
	/** Bones and demonic ashes are always counted as loot. */
	NEVER_EXCLUDE("Never exclude");

	private final String displayName;

	BonecrusherMode(String displayName)
	{
		this.displayName = displayName;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
