package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Rules for a task's notable drops ({@link NotableDrop}). Pure (no RuneLite types).
 *
 * <ul>
 *     <li>A loot item stack is notable when its value, in the "Loot price" setting's mode, is at least the "Notable
 *     drop value" setting ({@link #DEFAULT_THRESHOLD} by default). The value is the unit price times the quantity,
 *     with the same unit prices as the live loot value ({@link LootPricing}): in GE mode an untradeable item counts at
 *     its high-alchemy value. An item whose price in that mode is unavailable is never notable. One stack is one item
 *     id in one kill's loot (stacks of the same id in one kill are added up), after the bonecrusher and "Ignored loot"
 *     filters.</li>
 *     <li>A pet message ({@link PetMessage}) is always notable.</li>
 *     <li>A task keeps at most {@link #MAX_PER_TASK} notable drops, so the ledger file can't grow without limit. When
 *     another arrives, the lowest-ranked one is left out: items rank by value (the higher of the GE and high-alchemy
 *     values; unknown counts as 0), pets above every item, and among equals the oldest goes first.</li>
 * </ul>
 */
final class NotableDrops
{
	/** Default "Notable drop value" setting, in gp (owner decision, 2026-10-26). */
	static final int DEFAULT_THRESHOLD = 100_000;
	/** Most notable drops kept per task. */
	static final int MAX_PER_TASK = 20;

	/** Newest first by time; equal times keep the later-added one first when the list is reversed beforehand. */
	private static final Comparator<NotableDrop> NEWEST_FIRST =
		Comparator.comparingLong(NotableDrop::getTime).reversed();

	private NotableDrops()
	{
	}

	/** The threshold to use for a setting value: at least 1 gp. */
	static long threshold(long setting)
	{
		return Math.max(1, setting);
	}

	/**
	 * The notable stacks of one kill's loot.
	 *
	 * @param drop      the kill's loot (after filtering), by item id as received
	 * @param prices    unit prices of the loot's items ({@link ItemValuation#capturePrices})
	 * @param mode      the "Loot price" setting ({@code null} is GE)
	 * @param threshold the "Notable drop value" setting, in gp (see {@link #threshold(long)})
	 * @param names     the item name for an id, or {@code null}; only asked for notable items
	 * @param time      when the loot arrived (epoch milliseconds)
	 * @return the notable stacks, in item id order (empty when none)
	 */
	static List<NotableDrop> find(ItemTally drop, ItemValuation.UnitPrices prices, LootPriceMode mode,
		long threshold, IntFunction<String> names, long time)
	{
		if (drop == null || drop.isEmpty() || prices == null)
		{
			return Collections.emptyList();
		}
		final long minimum = threshold(threshold);
		final Map<Integer, Long> ge = LootPricing.geUnitPrices(prices.getGe(), prices.getHa());
		final Map<Integer, Long> ha = prices.getHa();
		List<NotableDrop> result = new ArrayList<>();
		for (int id : drop.itemIds())
		{
			final long quantity = drop.get(id);
			final Long geValue = stackValue(ge.get(id), quantity);
			final Long haValue = stackValue(ha.get(id), quantity);
			final Long value = mode == LootPriceMode.HIGH_ALCH ? haValue : geValue;
			if (value != null && value >= minimum)
			{
				result.add(NotableDrop.item(id, names == null ? null : names.apply(id), quantity, geValue, haValue,
					time));
			}
		}
		return result;
	}

	/** {@code unit * quantity}, saturating; {@code null} when the unit price is unknown or the quantity isn't positive. */
	static Long stackValue(Long unit, long quantity)
	{
		if (unit == null || quantity <= 0)
		{
			return null;
		}
		if (unit <= 0)
		{
			return 0L;
		}
		return unit > Long.MAX_VALUE / quantity ? Long.MAX_VALUE : unit * quantity;
	}

	/**
	 * {@code current} with {@code added} appended, keeping at most {@link #MAX_PER_TASK} (see the class rules).
	 * {@code null} entries are dropped. Neither list is changed; the result is in the order the drops were added.
	 */
	static List<NotableDrop> add(Collection<NotableDrop> current, Collection<NotableDrop> added)
	{
		List<NotableDrop> result = new ArrayList<>();
		addNonNull(result, current);
		addNonNull(result, added);
		while (result.size() > MAX_PER_TASK)
		{
			int lowest = 0;
			for (int i = 1; i < result.size(); i++)
			{
				// Strictly lower, so among equals the oldest (first) is removed.
				if (rank(result.get(i)) < rank(result.get(lowest)))
				{
					lowest = i;
				}
			}
			result.remove(lowest);
		}
		return result;
	}

	/** A stored list cleaned up for use: no {@code null} entries, at most {@link #MAX_PER_TASK}. */
	static List<NotableDrop> sanitize(Collection<NotableDrop> stored)
	{
		return add(stored, null);
	}

	/** {@code drops} newest first (by time; among equal times the later-added first). */
	static List<NotableDrop> newestFirst(Collection<NotableDrop> drops)
	{
		List<NotableDrop> result = sanitize(drops);
		Collections.reverse(result);
		result.sort(NEWEST_FIRST);
		return result;
	}

	/** Rank for {@link #add}: pets above every item, items by their higher known value. */
	static long rank(NotableDrop drop)
	{
		if (drop.isPet())
		{
			return Long.MAX_VALUE;
		}
		long ge = drop.getGeValue() == null ? 0 : drop.getGeValue();
		long ha = drop.getHaValue() == null ? 0 : drop.getHaValue();
		// Below every pet, even for an item at Long.MAX_VALUE.
		return Math.min(Long.MAX_VALUE - 1, Math.max(ge, ha));
	}

	private static void addNonNull(List<NotableDrop> into, Collection<NotableDrop> from)
	{
		if (from == null)
		{
			return;
		}
		for (NotableDrop d : from)
		{
			if (d != null)
			{
				into.add(d);
			}
		}
	}
}
