package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The loot item ids Ground Items' hidden list hides, as worked out from their names by
 * {@link HiddenLootResolver}. Every one is left out of the "Loot received" grids ({@link #visibleRows}); every one
 * but coins is also left out of the loot value (and so profit) wherever loot is valued ({@link #valued}). Coins always
 * count in the value (owner decision, 2026-10-28): only {@link ItemValuation#COINS} drops as loot, the other coin-like
 * ids in the item list (fake, Mage Training Arena) never do. Ids are as tallied, so a noted id is listed on its own.
 * Stored loot is never changed: this is applied when loot is valued or shown.
 *
 * <p>Immutable, so it can be handed to the views worked out off the EDT; equal sets are {@link #equals equal}.</p>
 */
final class HiddenLoot
{
	/** Hides nothing. */
	static final HiddenLoot NONE = new HiddenLoot(Collections.emptySet());

	/** Hidden from view. */
	private final Set<Integer> ids;
	/** Left out of the value: {@link #ids} without coins. */
	private final Set<Integer> valueIds;

	private HiddenLoot(Set<Integer> ids)
	{
		this.ids = ids;
		if (ids.contains(ItemValuation.COINS))
		{
			Set<Integer> withoutCoins = new TreeSet<>(ids);
			withoutCoins.remove(ItemValuation.COINS);
			valueIds = Collections.unmodifiableSet(withoutCoins);
		}
		else
		{
			valueIds = ids;
		}
	}

	/** The ids in {@code ids} ({@code null} ones skipped); {@link #NONE} when there are none. */
	static HiddenLoot of(Collection<Integer> ids)
	{
		Set<Integer> set = new TreeSet<>();
		if (ids != null)
		{
			for (Integer id : ids)
			{
				if (id != null)
				{
					set.add(id);
				}
			}
		}
		return set.isEmpty() ? NONE : new HiddenLoot(Collections.unmodifiableSet(set));
	}

	/** Whether nothing is hidden from view. */
	boolean isEmpty()
	{
		return ids.isEmpty();
	}

	/**
	 * {@code loot} as valued: without the hidden items except coins. A new tally, or {@code loot} itself when nothing
	 * in it is left out of the value. Never changes it.
	 */
	ItemTally valued(ItemTally loot)
	{
		if (loot == null || !leavesOutAny(loot.itemIds()))
		{
			return loot;
		}
		ItemTally kept = new ItemTally();
		for (int id : loot.itemIds())
		{
			if (!valueIds.contains(id))
			{
				kept.add(id, loot.get(id));
			}
		}
		return kept;
	}

	/** Whether any of {@code itemIds} is left out of the value (a hidden item other than coins). */
	boolean leavesOutAny(Collection<Integer> itemIds)
	{
		if (valueIds.isEmpty() || itemIds == null)
		{
			return false;
		}
		for (Integer id : itemIds)
		{
			if (id != null && valueIds.contains(id))
			{
				return true;
			}
		}
		return false;
	}

	/** The rows of items not hidden from view, in order; coins are hidden like any other item. */
	List<ItemDetails.Row> visibleRows(List<ItemDetails.Row> rows)
	{
		if (ids.isEmpty())
		{
			return rows;
		}
		List<ItemDetails.Row> kept = new ArrayList<>(rows.size());
		for (ItemDetails.Row row : rows)
		{
			if (!ids.contains(row.getItemId()))
			{
				kept.add(row);
			}
		}
		return kept;
	}

	@Override
	public boolean equals(Object o)
	{
		return this == o || o instanceof HiddenLoot && ids.equals(((HiddenLoot) o).ids);
	}

	@Override
	public int hashCode()
	{
		return ids.hashCode();
	}

	@Override
	public String toString()
	{
		return "HiddenLoot" + ids;
	}
}
