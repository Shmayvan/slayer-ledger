package com.shmayvan.slayerledger;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Per-item quantities (item id to quantity), used for loot and supplies. Pure: no RuneLite dependencies.
 *
 * <p>Ids are stored exactly as received (a noted drop keeps its noted id); pricing resolves the unnoted id, see
 * {@link ItemValuation}. Quantities are {@code long} so large coin stacks over a long task cannot overflow. Only
 * positive quantities are kept. Not thread-safe; {@link SessionTracker} guards its instance with its own lock.</p>
 *
 * <p>The JSON form is a plain {@code Map<Integer, Long>} (an object such as {@code {"995": 1200}}), see
 * {@link #toMap()} and {@link #fromMap(Map)}.</p>
 */
public final class ItemTally
{
	private final TreeMap<Integer, Long> quantities = new TreeMap<>();

	public ItemTally()
	{
	}

	/** A copy of another tally. */
	public ItemTally(ItemTally other)
	{
		if (other != null)
		{
			quantities.putAll(other.quantities);
		}
	}

	/**
	 * Builds a tally from its JSON form. {@code null} gives an empty tally; null keys and non-positive quantities
	 * are skipped.
	 */
	public static ItemTally fromMap(Map<Integer, Long> map)
	{
		ItemTally tally = new ItemTally();
		if (map != null)
		{
			for (Map.Entry<Integer, Long> e : map.entrySet())
			{
				if (e.getKey() != null && e.getValue() != null)
				{
					tally.add(e.getKey(), e.getValue());
				}
			}
		}
		return tally;
	}

	/**
	 * Adds a quantity of an item. Non-positive quantities are ignored. Saturates at {@link Long#MAX_VALUE}.
	 */
	public void add(int itemId, long quantity)
	{
		if (quantity <= 0)
		{
			return;
		}
		quantities.merge(itemId, quantity, ItemTally::saturatedAdd);
	}

	/**
	 * Removes up to a quantity of an item, never going below zero (an item reaching zero is removed). Non-positive
	 * quantities are ignored.
	 *
	 * @return the quantity actually removed
	 */
	public long subtract(int itemId, long quantity)
	{
		if (quantity <= 0)
		{
			return 0;
		}
		final long current = get(itemId);
		final long removed = Math.min(current, quantity);
		if (removed == current)
		{
			quantities.remove(itemId);
		}
		else
		{
			quantities.put(itemId, current - removed);
		}
		return removed;
	}

	/** Adds every item of another tally to this one. */
	public void merge(ItemTally other)
	{
		if (other == null)
		{
			return;
		}
		// Merging a tally into itself iterates over a copy.
		Map<Integer, Long> source = other == this ? new TreeMap<>(quantities) : other.quantities;
		for (Map.Entry<Integer, Long> e : source.entrySet())
		{
			add(e.getKey(), e.getValue());
		}
	}

	/** Quantity of an item, or 0. */
	public long get(int itemId)
	{
		Long q = quantities.get(itemId);
		return q == null ? 0 : q;
	}

	public boolean isEmpty()
	{
		return quantities.isEmpty();
	}

	/** Number of distinct items. */
	public int size()
	{
		return quantities.size();
	}

	/** Item ids in ascending order (read-only view). */
	public Set<Integer> itemIds()
	{
		return Collections.unmodifiableSet(quantities.keySet());
	}

	public void clear()
	{
		quantities.clear();
	}

	/**
	 * The JSON form: a new, sorted, mutable map, or {@code null} when the tally is empty (so records and snapshots
	 * without items omit the field).
	 */
	public Map<Integer, Long> toMap()
	{
		return quantities.isEmpty() ? null : new TreeMap<>(quantities);
	}

	private static long saturatedAdd(long a, long b)
	{
		long sum = a + b;
		return sum < 0 ? Long.MAX_VALUE : sum;
	}

	@Override
	public boolean equals(Object o)
	{
		return o instanceof ItemTally && quantities.equals(((ItemTally) o).quantities);
	}

	@Override
	public int hashCode()
	{
		return quantities.hashCode();
	}

	@Override
	public String toString()
	{
		return "ItemTally" + quantities;
	}
}
