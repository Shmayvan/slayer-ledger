package com.shmayvan.slayerledger;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntFunction;

/**
 * Works out which loot item ids Ground Items' hidden list ({@link GroundItemsHidden}) hides, from the items' names
 *. Each id is matched once per list: the answer is kept until the list changes ({@link #setList}), so the
 * wildcard matching does not run per tick or per row. An id whose name is unknown is not hidden, and is matched again
 * once its name is known.
 *
 * <p>Not thread-safe: each owner uses its own, on one thread (the plugin's on the client thread, the history model's
 * on the EDT).</p>
 */
final class HiddenLootResolver
{
	private GroundItemsHidden list = GroundItemsHidden.NONE;
	/** Whether each id matched so far hides, for {@link #list}. */
	private final Map<Integer, Boolean> matched = new HashMap<>();

	/** The list matched against. */
	GroundItemsHidden list()
	{
		return list;
	}

	/** Matches against {@code newList} from now on ({@code null} is none); returns whether it changed. */
	boolean setList(GroundItemsHidden newList)
	{
		GroundItemsHidden l = newList == null ? GroundItemsHidden.NONE : newList;
		if (l.equals(list))
		{
			return false;
		}
		list = l;
		matched.clear();
		return true;
	}

	/**
	 * The hidden ones of {@code itemIds}.
	 *
	 * @param names an item's own name (a note's is the unnoted item's), or {@code null} while unknown; a lookup that
	 *              throws counts as unknown. Only called for ids not matched yet.
	 */
	HiddenLoot resolve(Collection<Integer> itemIds, IntFunction<String> names)
	{
		if (list.isEmpty() || itemIds == null)
		{
			return HiddenLoot.NONE;
		}
		Set<Integer> hidden = new TreeSet<>();
		for (Integer id : itemIds)
		{
			if (id == null)
			{
				continue;
			}
			Boolean hides = matched.get(id);
			if (hides == null)
			{
				String name;
				try
				{
					name = names.apply(id);
				}
				catch (RuntimeException e)
				{
					name = null;
				}
				if (name == null)
				{
					continue;
				}
				hides = list.hides(name);
				matched.put(id, hides);
			}
			if (hides)
			{
				hidden.add(id);
			}
		}
		return HiddenLoot.of(hidden);
	}
}
