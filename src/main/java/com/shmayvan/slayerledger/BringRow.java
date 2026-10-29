package com.shmayvan.slayerledger;

import java.util.Comparator;

/** One What to bring row. */
final class BringRow
{
	private final int itemId;
	private final String name;
	private final long quantity;
	private final ItemDetails.Unit unit;
	/** Value at a GE unit price, or {@code null} without one; for sorting only. */
	private final Long value;

	BringRow(int itemId, String name, long quantity, ItemDetails.Unit unit, Long value)
	{
		this.itemId = itemId;
		this.name = name;
		this.quantity = quantity;
		this.unit = unit;
		this.value = value;
	}

	/**
	 * What to bring's order: priced items first, by value (amount times a GE unit price, per dose for dose-counted ids),
	 * then unpriced items; ties by amount, then by name and id.
	 */
	static final Comparator<BringRow> ORDER = (a, b) ->
	{
		if ((a.value == null) != (b.value == null))
		{
			return a.value == null ? 1 : -1;
		}
		int order = a.value != null ? Long.compare(b.value, a.value) : 0;
		if (order == 0)
		{
			order = Long.compare(b.quantity, a.quantity);
		}
		if (order == 0)
		{
			order = String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name);
		}
		return order != 0 ? order : Integer.compare(a.itemId, b.itemId);
	};

	String getName()
	{
		return name;
	}

	/** "x30", or "13 doses" / "1 dose" for a dose-counted id, as the item lists write it. */
	String quantityText()
	{
		String amount = LedgerHistory.formatAmount(quantity, false);
		return unit == ItemDetails.Unit.ITEMS ? "x" + amount : amount + " " + unitWord();
	}

	/** "30 x Cannonballs", or "13 doses of Prayer potion"; plain text, escaped by the caller. */
	String tooltipText()
	{
		String amount = LedgerHistory.formatAmount(quantity, false);
		return unit == ItemDetails.Unit.ITEMS ? amount + " x " + name : amount + " " + unitWord() + " of " + name;
	}

	private String unitWord()
	{
		return quantity == 1 ? unit.getOne() : unit.getMany();
	}
}
