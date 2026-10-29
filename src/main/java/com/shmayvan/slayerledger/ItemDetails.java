package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

/**
 * Pure: a task's supplies and loot item by item, for the panel's item grids. No RuneLite dependencies; item
 * names come from an injected {@link ItemNames} (the plugin resolves them through {@code ItemManager} on the client
 * thread, and the panel passes what it has received).
 *
 * <p>Rules:</p>
 * <ul>
 *     <li>One row per item id as tallied (a noted drop is its own row). Quantities in doses, bites or charges (ids in
 *     the supply doses, see {@link LedgerRecord#getSupplyDoses()}) show as "N doses", "N bites" or "N charges"; the
 *     word comes from {@link SupplyTracker#MULTI_BITE_FOOD} and {@link SupplyTracker#CHARGED_ITEMS}, any other is a
 *     potion. Their names drop the "(4)" dose or charge suffix of the item they are keyed by.</li>
 *     <li>Supplies are valued at GE prices, per dose, bite or charge ({@link ItemValuation#total(ItemTally, Map, Map)});
 *     loot in the {@link LootPriceMode}, with a GE price of 0 at high alch ({@link LootPricing}). The values are the
 *     same per-item amounts the totals add up. An item without the price shows as unavailable in its tooltip.</li>
 *     <li>Rows are sorted by value, highest first; unpriced rows come last; ties by name (ignoring case), then id.</li>
 *     <li>A loot row gets the alch hint ({@link #ALCH_MARKER}) when its high alch unit value, less the nature rune
 *     the cast uses, is above its GE unit price ({@link #alchBeatsGe}). Untradeables (GE 0), items without either
 *     price, and every item while the nature rune price is unknown never get it.</li>
 *     <li>The panel shows every row as an item icon ({@link ItemGridView}). A text list (the XP lists) shows at most
 *     {@link #MAX_ROWS} rows until the player asks for all ({@link #visible}).</li>
 * </ul>
 *
 * <p>Immutable.</p>
 */
final class ItemDetails
{
	/** Rows a text list ({@link ItemListView}, the XP lists) shows before "+N more". */
	static final int MAX_ROWS = 10;
	/** Value of an item whose price is unavailable. */
	static final String UNPRICED = "?";
	/** Shown for a saved task that has no item data (saved before loot and supplies were tracked). */
	static final String NO_ITEM_DETAILS = "No item details";
	/**
	 * Names the alch hint in the tooltip of a loot item whose high alch value beats its GE price (its icon has an
	 * orange border). Not "(HA)", which the panel uses for the "Loot price" setting.
	 */
	static final String ALCH_MARKER = "(alch)";
	/** Details without any items. */
	static final ItemDetails EMPTY = new ItemDetails(null, null, null, null, null, null);

	/** Item names, as far as they are known. */
	interface ItemNames
	{
		/** The item's name, or {@code null} while it is unknown. */
		String name(int itemId);
	}

	/** How a row's quantity is counted. */
	enum Unit
	{
		ITEMS(null, null),
		DOSES("dose", "doses"),
		BITES("bite", "bites"),
		CHARGES("charge", "charges");

		private final String one;
		private final String many;

		Unit(String one, String many)
		{
			this.one = one;
			this.many = many;
		}

		/** The word for one ("dose"), or {@code null} for {@link #ITEMS}. */
		String getOne()
		{
			return one;
		}

		/** The word for several ("doses"), or {@code null} for {@link #ITEMS}. */
		String getMany()
		{
			return many;
		}
	}

	/** One line of an item list. */
	@Value
	@Builder(toBuilder = true)
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	static class Row
	{
		int itemId;
		/** Name to show (never {@code null}). */
		String name;
		long quantity;
		Unit unit;
		/** Value of the whole quantity, or {@code null} when the price is unavailable. */
		Long value;
		/** What {@link #value} is: "GE" or "high alch". */
		String priceName;
		/** GE unit price (per item, not per dose) as saved or looked up, or {@code null} when unavailable. */
		Long unitGe;
		/** High alch unit value, or {@code null} when unavailable. */
		Long unitHa;
		/** Doses (bites, charges) in one item for {@link #unitGe}, or {@code null}. */
		Integer dosesPerItem;
		/**
		 * Whether to show the alch hint (the icon's orange border, and {@link #ALCH_MARKER} in the tooltip): loot whose
		 * high alch value beats its GE price.
		 */
		boolean alchHint;
		/**
		 * Whether {@link #value} leaves out part of the quantity, whose price was unavailable: only in rows summed over
		 * several tasks ({@link ItemSums}), when some of them had the price and others not.
		 */
		boolean approximate;

		/** "x14", or "22 doses" for an item counted in doses, bites or charges. */
		String quantityText()
		{
			if (unit == Unit.ITEMS)
			{
				return "x" + LedgerHistory.formatAmount(quantity, false);
			}
			return LedgerHistory.formatAmount(quantity, false) + " " + (quantity == 1 ? unit.one : unit.many);
		}

		/** The value without " gp" (as in history rows), or {@link #UNPRICED}. */
		String valueText()
		{
			return value == null ? UNPRICED : LedgerHistory.formatAmount(value, approximate);
		}

		/**
		 * HTML tooltip of the item's icon: the full name, the exact quantity and value, the unit prices and, if shown,
		 * the alch hint.
		 */
		String tooltip()
		{
			StringBuilder sb = new StringBuilder("<html>").append(LedgerHistory.escapeHtml(name));
			sb.append("<br>Quantity: ").append(String.format("%,d", quantity));
			if (unit != Unit.ITEMS)
			{
				sb.append(' ').append(quantity == 1 ? unit.one : unit.many);
			}
			sb.append("<br>Value (").append(priceName).append("): ")
				.append(value == null ? "unavailable"
					: (approximate ? LedgerHistory.APPROXIMATE : "") + LedgerHistory.formatGpExact(value));
			sb.append("<br>GE: ").append(geText());
			sb.append("<br>High alch: ").append(unitHa == null ? "unavailable" : LedgerHistory.formatGpExact(unitHa)
				+ " each");
			if (alchHint)
			{
				sb.append("<br>").append(ALCH_MARKER).append(" High alch, less a nature rune, is worth more than")
					.append("<br>the GE price. GE tax not included.");
			}
			return sb.append("</html>").toString();
		}

		private String geText()
		{
			if (unitGe == null)
			{
				return "unavailable";
			}
			if (unitGe == 0)
			{
				return "untradeable";
			}
			String each = LedgerHistory.formatGpExact(unitGe) + " each";
			if (dosesPerItem != null && unit != Unit.ITEMS)
			{
				return each + " (" + dosesPerItem + " " + (dosesPerItem == 1 ? unit.one : unit.many) + ")";
			}
			return each;
		}
	}

	/** Highest value first; unpriced last; then by name ignoring case, then id. */
	static final Comparator<Row> BY_VALUE = Comparator
		.comparing((Row r) -> r.getValue() == null)
		.thenComparing(r -> r.getValue() == null ? 0L : r.getValue(), Comparator.reverseOrder())
		.thenComparing(Row::getName, String.CASE_INSENSITIVE_ORDER)
		.thenComparingInt(Row::getItemId);

	private final Map<Integer, Long> loot;
	private final Map<Integer, Long> supplies;
	private final Map<Integer, Integer> doses;
	private final Map<Integer, Long> ge;
	private final Map<Integer, Long> ha;
	/** The GE price of the nature rune a high alch uses, or {@code null} while unknown (then no alch hint). */
	private final Long natureRunePrice;

	private ItemDetails(Map<Integer, Long> loot, Map<Integer, Long> supplies, Map<Integer, Integer> doses,
		Map<Integer, Long> ge, Map<Integer, Long> ha, Long natureRunePrice)
	{
		this.loot = copy(loot);
		this.supplies = copy(supplies);
		this.doses = copy(doses);
		this.ge = copy(ge);
		this.ha = copy(ha);
		this.natureRunePrice = natureRunePrice;
	}

	/**
	 * A live session's items: its tallies and the unit prices it is valued at (keyed by the id as tallied, as in
	 * {@link ItemValuation.UnitPrices}). Everything is copied.
	 */
	static ItemDetails of(ItemTally loot, ItemTally supplies, Map<Integer, Integer> doses, Map<Integer, Long> ge,
		Map<Integer, Long> ha)
	{
		return new ItemDetails(loot == null ? null : loot.toMap(), supplies == null ? null : supplies.toMap(), doses,
			ge, ha, null);
	}

	/**
	 * A saved task's items at the prices saved with it, or {@code null} when it has no item data: saved before loot
	 * and supplies were tracked (not valued, and neither loot nor supplies stored).
	 */
	static ItemDetails of(LedgerRecord record)
	{
		if (record == null || !LedgerHistory.isValued(record) && record.getLoot() == null
			&& record.getSupplies() == null)
		{
			return null;
		}
		return new ItemDetails(record.getLoot(), record.getSupplies(), record.getSupplyDoses(),
			record.getUnitGePrices(), record.getUnitHaPrices(), null);
	}

	/**
	 * These items at other GE unit prices ({@code currentGe}, keyed by the id as tallied; an id without an entry has no
	 * price), as the history values them at today's prices ({@link HistoryPricing}); the high alch values stay.
	 */
	ItemDetails withGePrices(Map<Integer, Long> currentGe)
	{
		return new ItemDetails(loot, supplies, doses, currentGe, ha, natureRunePrice);
	}

	/** These items with the nature rune's GE price for the alch hint ({@code null} or not positive: unknown). */
	ItemDetails withNatureRunePrice(Long price)
	{
		return new ItemDetails(loot, supplies, doses, ge, ha, price != null && price > 0 ? price : null);
	}

	/** Every item id in the loot and supplies, for one name lookup. */
	Set<Integer> itemIds()
	{
		Set<Integer> ids = new TreeSet<>(loot.keySet());
		ids.addAll(supplies.keySet());
		return ids;
	}

	boolean hasLoot()
	{
		return !loot.isEmpty();
	}

	boolean hasSupplies()
	{
		return !supplies.isEmpty();
	}

	/** The supplies, valued at GE prices per dose, bite or charge, sorted by {@link #BY_VALUE}. */
	List<Row> supplyRows(ItemNames names)
	{
		List<Row> rows = new ArrayList<>();
		for (Map.Entry<Integer, Long> e : supplies.entrySet())
		{
			int id = e.getKey();
			Integer perItem = doses.get(id);
			Unit unit = perItem == null ? Unit.ITEMS : doseUnit(id);
			Long unitGe = ge.get(id);
			rows.add(Row.builder()
				.itemId(id)
				.name(displayName(id, names, unit != Unit.ITEMS))
				.quantity(e.getValue())
				.unit(unit)
				.value(value(id, e.getValue(), unitGe, doses))
				.priceName("GE")
				.unitGe(unitGe)
				.unitHa(ha.get(id))
				.dosesPerItem(perItem)
				.alchHint(false)
				.build());
		}
		rows.sort(BY_VALUE);
		return rows;
	}

	/** The loot, valued in {@code mode} ({@link LootPricing}), sorted by {@link #BY_VALUE}, with the alch hint. */
	List<Row> lootRows(LootPriceMode mode, ItemNames names)
	{
		LootPriceMode lootMode = mode == null ? LootPriceMode.GE : mode;
		Map<Integer, Long> prices = LootPricing.unitPrices(lootMode, ge, ha);
		String priceName = lootMode == LootPriceMode.HIGH_ALCH ? "high alch" : "GE";
		List<Row> rows = new ArrayList<>();
		for (Map.Entry<Integer, Long> e : loot.entrySet())
		{
			int id = e.getKey();
			Long unitGe = ge.get(id);
			Long unitHa = ha.get(id);
			rows.add(Row.builder()
				.itemId(id)
				.name(displayName(id, names, false))
				.quantity(e.getValue())
				.unit(Unit.ITEMS)
				.value(value(id, e.getValue(), prices.get(id), null))
				.priceName(priceName)
				.unitGe(unitGe)
				.unitHa(unitHa)
				.dosesPerItem(null)
				.alchHint(alchBeatsGe(unitGe, unitHa, natureRunePrice))
				.build());
		}
		rows.sort(BY_VALUE);
		return rows;
	}

	/**
	 * Whether alching beats selling: the high alch unit value, less the nature rune the cast uses, is above a known,
	 * non-zero GE unit price. An untradeable item (GE 0, already valued at high alch), an item missing either price,
	 * and any item while the nature rune price is unknown never qualify. The GE tax is not counted.
	 */
	static boolean alchBeatsGe(Long unitGe, Long unitHa, Long natureRunePrice)
	{
		return unitGe != null && unitHa != null && natureRunePrice != null && unitGe > 0
			&& unitHa - natureRunePrice > unitGe;
	}

	/**
	 * Rows worked out without names ({@link ItemSums#rows()}, Overall totals), as {@link #supplyRows} or
	 * {@link #lootRows} would show them: named through {@code names} and, for {@code loot}, given the alch hint at
	 * {@code natureRunePrice} ({@code null} or not positive: unknown, no hint). Sorted by {@link #BY_VALUE}.
	 */
	static List<Row> named(List<Row> rows, ItemNames names, Long natureRunePrice, boolean loot)
	{
		Long nature = natureRunePrice != null && natureRunePrice > 0 ? natureRunePrice : null;
		List<Row> result = new ArrayList<>(rows.size());
		for (Row row : rows)
		{
			result.add(row.toBuilder()
				.name(displayName(row.getItemId(), names, row.getUnit() != Unit.ITEMS))
				.alchHint(loot && alchBeatsGe(row.getUnitGe(), row.getUnitHa(), nature))
				.build());
		}
		result.sort(BY_VALUE);
		return result;
	}

	/** The rows to show: all of them when {@code showAll}, otherwise the first {@link #MAX_ROWS}. */
	static <T> List<T> visible(List<T> rows, boolean showAll)
	{
		if (showAll || rows.size() <= MAX_ROWS)
		{
			return rows;
		}
		return rows.subList(0, MAX_ROWS);
	}

	/** Rows left out by {@link #visible} when not showing all (0 when they all fit). */
	static int hiddenCount(int rows)
	{
		return Math.max(0, rows - MAX_ROWS);
	}

	/** The quantity word of a dose-counted supply id: bites, charges, or doses (potions). */
	static Unit doseUnit(int itemId)
	{
		if (SupplyTracker.MULTI_BITE_FOOD.containsKey(itemId))
		{
			return Unit.BITES;
		}
		if (SupplyTracker.CHARGED_ITEMS.containsKey(itemId))
		{
			return Unit.CHARGES;
		}
		return Unit.DOSES;
	}

	/**
	 * The name to show: the looked-up name, without a trailing "(4)" for an item counted in doses, bites or charges
	 * (its row counts doses, not that item); "Item &lt;id&gt;" while the name is unknown.
	 */
	static String displayName(int itemId, ItemNames names, boolean perDose)
	{
		String name = names == null ? null : names.name(itemId);
		if (name == null || name.trim().isEmpty())
		{
			return "Item " + itemId;
		}
		if (perDose)
		{
			String stripped = name.replaceFirst("(\\s*\\([a-z]?\\d+\\)|\\s+\\d+)$", "");
			return stripped.isEmpty() ? name : stripped;
		}
		return name;
	}

	/** One item's value as the totals count it, or {@code null} without a unit price. */
	private static Long value(int id, long quantity, Long unitPrice, Map<Integer, Integer> doses)
	{
		if (unitPrice == null)
		{
			return null;
		}
		ItemTally one = new ItemTally();
		one.add(id, quantity);
		return ItemValuation.total(one, Collections.singletonMap(id, unitPrice), doses);
	}

	private static <V> Map<Integer, V> copy(Map<Integer, V> map)
	{
		Map<Integer, V> result = new TreeMap<>();
		if (map != null)
		{
			for (Map.Entry<Integer, V> e : map.entrySet())
			{
				if (e.getKey() != null && e.getValue() != null)
				{
					result.put(e.getKey(), e.getValue());
				}
			}
		}
		return Collections.unmodifiableMap(result);
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof ItemDetails))
		{
			return false;
		}
		ItemDetails d = (ItemDetails) o;
		return loot.equals(d.loot) && supplies.equals(d.supplies) && doses.equals(d.doses) && ge.equals(d.ge)
			&& ha.equals(d.ha);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(loot, supplies, doses, ge, ha);
	}

	@Override
	public String toString()
	{
		return "ItemDetails{loot=" + loot + ", supplies=" + supplies + ", doses=" + doses + "}";
	}
}
