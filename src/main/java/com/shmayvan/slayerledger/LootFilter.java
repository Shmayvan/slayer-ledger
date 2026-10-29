package com.shmayvan.slayerledger;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Decides which items of one kill-loot drop are counted, when the loot is captured: the items a bonecrusher or ash
 * sanctifier destroyed ({@link CrushedLootFilter}) and the player's ignored items ({@link IgnoredLoot}) are left out.
 * Saved records and loot already counted are never changed. Pure: item facts come from an {@link ItemNames} the plugin
 * backs with item compositions on the client thread.
 */
final class LootFilter
{
	/** Item facts for the ignored-items check. Implementations may only be valid on the client thread. */
	interface ItemNames
	{
		/** The unnoted item's id for a noted item, otherwise {@code itemId}. */
		int unnoted(int itemId);

		/** The item's name, or {@code null} when unknown. */
		String name(int itemId);
	}

	private final CrushedLootFilter crushed;
	private final IgnoredLoot ignored;

	LootFilter(CrushedLootFilter crushed, IgnoredLoot ignored)
	{
		this.crushed = Objects.requireNonNull(crushed);
		this.ignored = Objects.requireNonNull(ignored);
	}

	/**
	 * The loot that counts. Item facts are only looked up when items are ignored, at most once per distinct id; a
	 * failed lookup leaves that item to be matched by its id alone.
	 *
	 * @param loot  the drop, by item id as received
	 * @param names item facts; not used when nothing is ignored
	 */
	ItemTally apply(ItemTally loot, ItemNames names)
	{
		final ItemTally kept = new ItemTally();
		if (loot == null)
		{
			return kept;
		}
		final Map<Integer, Boolean> ignoredIds = new HashMap<>();
		for (int id : loot.itemIds())
		{
			if (crushed.keeps(id) && !ignoredIds.computeIfAbsent(id, k -> isIgnored(k, names)))
			{
				kept.add(id, loot.get(id));
			}
		}
		return kept;
	}

	private boolean isIgnored(int itemId, ItemNames names)
	{
		if (ignored.isEmpty())
		{
			return false;
		}
		int unnoted = itemId;
		String name = null;
		try
		{
			unnoted = names.unnoted(itemId);
			if (ignored.hasNames())
			{
				name = names.name(unnoted);
			}
		}
		catch (RuntimeException e)
		{
			// Item data unavailable: only an id entry can match.
		}
		return ignored.ignores(itemId, unnoted, name);
	}
}
