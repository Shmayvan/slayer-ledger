package com.shmayvan.slayerledger;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * Pure valuation of a saved {@link LedgerRecord} for the task history, in a {@link LootPriceMode} and at today's GE
 * prices. No RuneLite dependencies; records are never changed.
 *
 * <p>Every saved task the sidebar shows is valued at today's prices (there is no "saved prices" view any more).
 * The prices saved with a task are still read in two places: its high-alchemy values (which never change), and a
 * {@code null} {@code currentGe}, which values a record at the prices saved with it. In the plugin only the
 * task-complete chat line does that, for the record it has just saved: those prices are today's.</p>
 *
 * <p>Rules:</p>
 * <ul>
 *     <li>A record saved before values were tracked ({@link LedgerHistory#isValued}) has no values, whatever the
 *     mode or prices.</li>
 *     <li><b>Today's prices.</b> The loot and supplies GE totals are recomputed from the record's items at
 *     {@code currentGe} (unit GE prices keyed by the id as tallied); items missing from it are unpriced (the total is
 *     marked {@code ~}), and loot items at 0 count at their high-alchemy value ({@link LootPricing}). High-alchemy
 *     values never change, so the record's stored high-alchemy unit values and totals are used. Supplies stay per
 *     dose or bite. This holds for every record with loot or supply items, also one saved without per-item prices
 *     (neither {@code unitGePrices} nor {@code unitHaPrices}): its GE values are recomputed the same way, and its
 *     high-alchemy values are its saved totals (it has no high-alchemy unit values), as {@link ItemDetails} shows
 *     its items. In High alch mode the loot value of a record with loot items but no {@code unitHaPrices} is that
 *     saved total marked {@code ~}, every loot item counting as unpriced, as its item details show them.</li>
 *     <li><b>Tasks saved without items</b> (no loot or supply items, only totals) can't be revalued: at today's
 *     prices they keep their saved totals ({@link Values#isSavedTotals()}), and a non-zero GE total is marked
 *     {@code ~} because it is not at today's prices. Their high-alchemy total is exact, as high-alchemy values never
 *     change. A record without items whose saved GE totals are 0 has nothing to revalue: it is valued at today's
 *     prices (all 0).</li>
 *     <li><b>Saved prices</b> ({@code currentGe} {@code null}). Supplies and high-alchemy totals are the record's
 *     stored totals. A record with unit prices and items has its loot GE value recomputed per item from its
 *     {@code unitGePrices} and {@code unitHaPrices} ({@link LootPricing}), so an item saved with a GE price of 0
 *     counts at its high-alchemy value; the stored {@code lootGeValue} is left as saved. Any other record uses its
 *     stored loot GE total.</li>
 *     <li>Supplies are always valued at GE prices. Profit is the loot value in the mode minus the supplies.</li>
 *     <li><b>Hidden loot</b>. Loot items Ground Items hides ({@link HiddenLoot}), except coins, are left out of
 *     every loot value and of the unpriced counts, as if they had not dropped; the record itself is unchanged.
 *     They can only be left out where loot is valued per item: a record saved without items keeps its stored
 *     totals, and one saved without high-alchemy unit values its stored high-alchemy total, hidden items
 *     included.</li>
 * </ul>
 */
final class HistoryPricing
{
	/** A record's values as shown in the history. Immutable; build one with {@link #builder()}. */
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	static final class Values
	{
		private final LootPriceMode mode;
		private final boolean currentPrices;
		private final boolean savedTotals;
		private final Long lootValue;
		private final Long lootGe;
		private final Long lootHa;
		private final Long suppliesGe;
		private final Long suppliesHa;
		private final int lootUnpriced;
		private final int lootGeUnpriced;
		private final int suppliesUnpriced;

		/** Whether the record has values at all (it was saved after values were tracked). */
		boolean isValued()
		{
			return lootValue != null;
		}

		LootPriceMode getMode()
		{
			return mode;
		}

		/**
		 * Whether the GE values use today's prices: always, but for a {@code null} {@code currentGe} and a record
		 * that can't be revalued ({@link #isSavedTotals()}).
		 */
		boolean isCurrentPrices()
		{
			return currentPrices;
		}

		/**
		 * Whether today's prices were asked for but the record kept no loot or supply items to value, only a non-zero
		 * saved GE total, so its saved totals are used instead; a non-zero one is marked approximate ({@code ~}).
		 */
		boolean isSavedTotals()
		{
			return savedTotals;
		}

		/** Loot value in {@link #getMode()}, or {@code null} when not valued. */
		Long getLootValue()
		{
			return lootValue;
		}

		/** Loot value in {@link LootPriceMode#GE} ({@link LootPricing}), or {@code null} when not valued. */
		Long getLootGe()
		{
			return lootGe;
		}

		Long getLootHa()
		{
			return lootHa;
		}

		/** Supplies GE value: 0 for a valued record without supplies, {@code null} when not valued. */
		Long getSuppliesGe()
		{
			return suppliesGe;
		}

		Long getSuppliesHa()
		{
			return suppliesHa;
		}

		/** Distinct loot items whose price in {@link #getMode()} was unavailable. */
		int getLootUnpriced()
		{
			return lootUnpriced;
		}

		/** Distinct loot items whose GE price was unavailable, so {@link #getLootGe()} is approximate. */
		int getLootGeUnpriced()
		{
			return lootGeUnpriced;
		}

		/** Distinct supply items whose GE price was unavailable. */
		int getSuppliesUnpriced()
		{
			return suppliesUnpriced;
		}

		/**
		 * Whether {@link #getLootValue()} is approximate (shown with {@code ~}): some item had no price, or it is a
		 * non-zero saved GE total ({@link #isSavedTotals()}; high-alchemy values never change).
		 */
		boolean isLootApproximate()
		{
			return lootUnpriced > 0 || (savedTotals && mode != LootPriceMode.HIGH_ALCH && isNonZero(lootValue));
		}

		/** Whether {@link #getSuppliesGe()} is approximate: some item had no price, or a non-zero saved total. */
		boolean isSuppliesApproximate()
		{
			return suppliesUnpriced > 0 || (savedTotals && isNonZero(suppliesGe));
		}

		private static boolean isNonZero(Long value)
		{
			return value != null && value != 0;
		}

		/** Loot value minus supplies, or {@code null} when not valued. */
		Long getProfit()
		{
			return LedgerHistory.profit(lootValue, suppliesGe);
		}

		/** Whether {@link #getProfit()} is approximate. */
		boolean isProfitApproximate()
		{
			return isLootApproximate() || isSuppliesApproximate();
		}
	}

	private HistoryPricing()
	{
	}

	/**
	 * {@code record}'s values in {@code mode}; see the class rules.
	 *
	 * @param currentGe today's GE unit prices keyed by the id as tallied (an item without an entry has no price), or
	 *                  {@code null} to use the prices saved with the record
	 */
	static Values value(LedgerRecord record, LootPriceMode mode, Map<Integer, Long> currentGe)
	{
		return value(record, mode, currentGe, HiddenLoot.NONE);
	}

	/**
	 * {@code record}'s values in {@code mode}, without the loot items {@code hidden} hides (coins always count); see
	 * the class rules.
	 *
	 * @param currentGe today's GE unit prices keyed by the id as tallied (an item without an entry has no price), or
	 *                  {@code null} to use the prices saved with the record
	 * @param hidden    loot items left out of the values; {@code null} is none
	 */
	static Values value(LedgerRecord record, LootPriceMode mode, Map<Integer, Long> currentGe, HiddenLoot hidden)
	{
		final LootPriceMode lootMode = mode == null ? LootPriceMode.GE : mode;
		if (!LedgerHistory.isValued(record))
		{
			return Values.builder().mode(lootMode).currentPrices(currentGe != null).build();
		}
		final ItemTally allLoot = ItemTally.fromMap(record.getLoot());
		final ItemTally loot = hidden == null ? allLoot : hidden.valued(allLoot);
		// Whether some loot is left out; only then are the saved totals and counts worked out again per item.
		final boolean hiding = loot != allLoot;
		final ItemTally supplies = record.getSupplies() == null ? null : ItemTally.fromMap(record.getSupplies());
		final Map<Integer, Long> ha = record.getUnitHaPrices();
		final boolean hasUnitPrices = record.getUnitGePrices() != null || ha != null;
		// Today's prices value the record's items; a record without any keeps its saved totals, when there are any.
		final boolean hasItems = !allLoot.isEmpty() || (supplies != null && !supplies.isEmpty());
		final boolean savedTotals = currentGe != null && !hasItems && hasSavedGeTotal(record);
		final boolean current = currentGe != null && !savedTotals;
		final long lootHa = hiding && ha != null ? ItemValuation.total(loot, ha)
			: record.getLootHaValue() != null ? record.getLootHaValue() : 0;
		final long suppliesHa = record.getSuppliesHaValue() != null ? record.getSuppliesHaValue() : 0;

		final long lootGe;
		final long suppliesGe;
		final int lootGeUnpriced;
		final int suppliesUnpriced;
		if (current)
		{
			lootGe = LootPricing.total(loot, LootPriceMode.GE, currentGe, ha, null);
			suppliesGe = ItemValuation.total(supplies, currentGe, record.getSupplyDoses());
			lootGeUnpriced = ItemValuation.countUnpriced(loot, currentGe);
			suppliesUnpriced = ItemValuation.countUnpriced(supplies, currentGe);
		}
		else
		{
			final Map<Integer, Long> ge = record.getUnitGePrices();
			final boolean perItem = hasUnitPrices && hasItems;
			lootGe = perItem ? LootPricing.total(loot, LootPriceMode.GE, ge, ha, null) : record.getLootGeValue();
			suppliesGe = LedgerHistory.suppliesGe(record);
			lootGeUnpriced = hiding && perItem ? ItemValuation.countUnpriced(loot, ge)
				: LedgerHistory.lootUnpriced(record);
			suppliesUnpriced = LedgerHistory.suppliesUnpriced(record);
		}

		long lootValue;
		int lootUnpriced;
		switch (lootMode)
		{
			case HIGH_ALCH:
				lootValue = lootHa;
				// Without high-alchemy unit values every loot item is unpriced, as its details show it, so the saved
				// high-alchemy total of a record with loot items is marked ~.
				lootUnpriced = ItemValuation.countUnpriced(loot, ha);
				break;
			default:
				lootValue = lootGe;
				lootUnpriced = lootGeUnpriced;
				break;
		}
		return Values.builder()
			.mode(lootMode)
			.currentPrices(current)
			.savedTotals(savedTotals)
			.lootValue(lootValue)
			.lootGe(lootGe)
			.lootHa(lootHa)
			.suppliesGe(suppliesGe)
			.suppliesHa(suppliesHa)
			.lootUnpriced(lootUnpriced)
			.lootGeUnpriced(lootGeUnpriced)
			.suppliesUnpriced(suppliesUnpriced)
			.build();
	}

	/** Whether {@code record} was saved with a non-zero loot or supplies GE total. */
	private static boolean hasSavedGeTotal(LedgerRecord record)
	{
		final Long lootGe = record.getLootGeValue();
		final Long suppliesGe = record.getSuppliesGeValue();
		return (lootGe != null && lootGe != 0) || (suppliesGe != null && suppliesGe != 0);
	}

	/** Every distinct item id in the loot and supplies of {@code records}, for one batched price lookup. */
	static Set<Integer> itemIds(Collection<LedgerRecord> records)
	{
		Set<Integer> ids = new TreeSet<>();
		for (LedgerRecord r : records)
		{
			if (r == null)
			{
				continue;
			}
			addKeys(ids, r.getLoot());
			addKeys(ids, r.getSupplies());
		}
		return ids;
	}

	private static void addKeys(Set<Integer> into, Map<Integer, Long> map)
	{
		if (map == null)
		{
			return;
		}
		for (Integer id : map.keySet())
		{
			if (id != null)
			{
				into.add(id);
			}
		}
	}
}
