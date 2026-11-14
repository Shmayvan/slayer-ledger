package com.shmayvan.slayerledger;

import java.util.Map;

/**
 * Cannon tasks (owner decision): a task counts as a <b>cannon task</b> when its effective Cannon tag is Yes
 * ({@link SetupTags}, the player's override, else at least half of its kills made while the cannon fired; for a
 * migrated 1.x record, estimated from its fired cannonballs). See {@link LedgerRecord#cannonTask} and, for the live
 * session, {@link SessionTracker#getCurrentTaskSelection}. {@link #fired} is the 1.x rule, still the fallback where no
 * tag or kill context is known: any cannonballs, regular or granite ({@link SupplyTracker#CANNONBALL},
 * {@link SupplyTracker#GRANITE_CANNONBALL}), fired, that is in the supplies used. Cannonballs that come back (picked up
 * or unloaded) are taken off the supplies ({@link SessionTracker#addSupplies}), so loading a cannon and picking it up
 * again without firing fires none. Pure (no RuneLite types).
 *
 * <p>A cannon task is its own group beside the task's other tasks, named with {@link #SUFFIX}, for example
 * "Dust devils (cannon)" or, done on a Catacombs variant, "Deviant spectres (cannon)" ({@link #name}).</p>
 */
final class CannonTasks
{
	/** What a cannon task's name ends with: {@code " (cannon)"}. */
	static final String SUFFIX = " (cannon)";

	private CannonTasks()
	{
	}

	/**
	 * Whether {@code supplies} (item id to quantity used, as a record stores them) hold any fired cannonballs, regular
	 * or granite; {@code false} for {@code null} (a record saved before supplies were tracked).
	 */
	static boolean fired(Map<Integer, Long> supplies)
	{
		if (supplies == null)
		{
			return false;
		}
		return positive(supplies.get(SupplyTracker.CANNONBALL))
			|| positive(supplies.get(SupplyTracker.GRANITE_CANNONBALL));
	}

	/** As {@link #fired(Map)}, for the live session's supplies used. */
	static boolean fired(ItemTally supplies)
	{
		return supplies != null && (supplies.get(SupplyTracker.CANNONBALL) > 0
			|| supplies.get(SupplyTracker.GRANITE_CANNONBALL) > 0);
	}

	/**
	 * The name a task is shown and grouped under: {@code name} with {@link #SUFFIX} for a cannon task, else
	 * {@code name} itself; {@code null} stays {@code null}.
	 */
	static String name(String name, boolean cannon)
	{
		return name == null || !cannon ? name : name + SUFFIX;
	}

	private static boolean positive(Long quantity)
	{
		return quantity != null && quantity > 0;
	}
}
