package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure: the supplies or the loot of several saved tasks added up item by item, for the item grids of Overall totals
 *. Each task's rows come from {@link ItemDetails} exactly as its history details show them (at the prices it is
 * shown at), so a summed row is valued as the group's profit line counts it. No RuneLite dependencies.
 *
 * <p>Rules:</p>
 * <ul>
 *     <li>One row per item id and {@link ItemDetails.Unit}, as tallied: a noted item stays its own row, and the doses,
 *     bites or charges of an item counted that way add up as doses, bites or charges.</li>
 *     <li>Quantities and values are the sums of the tasks' rows. A task whose item had no price adds its quantity but
 *     no value: the row's value is the sum of the priced tasks' values, marked approximate
 *     ({@link ItemDetails.Row#isApproximate()}); with no priced task it is unavailable ({@code null}).</li>
 *     <li>The unit prices and doses per item shown in the tooltip are those of the first task added that has them;
 *     Overall totals adds tasks newest first. At today's prices every task has the same GE price.</li>
 *     <li>The rows are not named and have no alch hint: names and the nature rune price are known on the EDT only, so
 *     the panel adds them with {@link ItemDetails#named}.</li>
 * </ul>
 *
 * <p>Not thread-safe: one is filled and read by one thread (the background thread working out the totals). The rows it
 * returns are immutable.</p>
 */
final class ItemSums
{
	/** The sums by item id and unit, in the order first added. */
	private final Map<List<Object>, Sum> sums = new LinkedHashMap<>();

	/** Adds one task's supply or loot rows ({@link ItemDetails#supplyRows} or {@link ItemDetails#lootRows}). */
	void add(List<ItemDetails.Row> rows)
	{
		for (ItemDetails.Row row : rows)
		{
			sums.computeIfAbsent(Arrays.asList(row.getItemId(), row.getUnit()), key -> new Sum(row)).add(row);
		}
	}

	/** The summed rows, unnamed ("Item &lt;id&gt;") and without the alch hint, unmodifiable. */
	List<ItemDetails.Row> rows()
	{
		List<ItemDetails.Row> rows = new ArrayList<>(sums.size());
		for (Sum sum : sums.values())
		{
			rows.add(sum.toRow());
		}
		return Collections.unmodifiableList(rows);
	}

	/** One item's running sum. */
	private static final class Sum
	{
		private final ItemDetails.Row first;
		private long quantity;
		private long value;
		private int priced;
		private int unpriced;
		private Long unitGe;
		private Long unitHa;
		private Integer dosesPerItem;

		private Sum(ItemDetails.Row first)
		{
			this.first = first;
		}

		private void add(ItemDetails.Row row)
		{
			quantity = saturatedAdd(quantity, row.getQuantity());
			if (row.getValue() == null)
			{
				unpriced++;
			}
			else
			{
				value = saturatedAdd(value, row.getValue());
				priced++;
			}
			unitGe = unitGe != null ? unitGe : row.getUnitGe();
			unitHa = unitHa != null ? unitHa : row.getUnitHa();
			dosesPerItem = dosesPerItem != null ? dosesPerItem : row.getDosesPerItem();
		}

		private ItemDetails.Row toRow()
		{
			return first.toBuilder()
				.quantity(quantity)
				.value(priced == 0 ? null : value)
				.unitGe(unitGe)
				.unitHa(unitHa)
				.dosesPerItem(dosesPerItem)
				.alchHint(false)
				.approximate(priced > 0 && unpriced > 0)
				.build();
		}
	}

	private static long saturatedAdd(long left, long right)
	{
		try
		{
			return Math.addExact(left, right);
		}
		catch (ArithmeticException e)
		{
			return right >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
		}
	}
}
