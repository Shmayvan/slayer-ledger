package com.shmayvan.slayerledger;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Values the live session's loot and supplies for the panel. Pure: prices come from a {@link ItemValuation.PriceLookup}
 * (the plugin's runs on the client thread). Not thread-safe; the plugin only calls it on the client thread.
 *
 * <p>Unit prices are cached for the session, so a recompute only looks up items not seen before (and retries items
 * whose GE price was unavailable, at most once every {@link #RETRY_TICKS} ticks). Totals use the
 * same rules as saved records ({@link ItemValuation}): potions per dose, coins at face value, unavailable GE prices
 * left out and counted. Loot is also valued in the chosen {@link LootPriceMode} ({@link LootPricing}), and its GE value
 * follows {@link LootPricing} too (an item with a GE price of 0 counts at its high-alchemy value); supplies are
 * always valued at GE prices as captured. Loot items Ground Items hides ({@link HiddenLoot}), except coins,
 * are left out of every loot value and unpriced count; the tally is not changed. The cache is dropped when the
 * session changes or {@link #reset()} is called.</p>
 *
 * <p>Not final only so plugin tests can stand in a valuation that fails.</p>
 */
class SessionValuation
{
	/** Ticks between retries of unavailable GE prices while the tallies do not change. */
	static final int RETRY_TICKS = 10;

	/** Loot and supplies values of a session at one moment. Immutable. */
	static final class Values
	{
		private final String sessionId;
		private final LootPriceMode lootPriceMode;
		private final long lootValue;
		private final int lootValueUnpriced;
		private final long lootGe;
		private final long lootHa;
		private final long suppliesGe;
		private final long suppliesHa;
		private final int lootUnpriced;
		private final int suppliesUnpriced;
		private final ItemDetails items;
		private final HiddenLoot hiddenLoot;

		/** Values with the loot valued at GE prices, without item details. */
		Values(String sessionId, long lootGe, long lootHa, long suppliesGe, long suppliesHa, int lootUnpriced,
			int suppliesUnpriced)
		{
			this(builder().sessionId(sessionId).lootValue(lootGe).lootValueUnpriced(lootUnpriced).lootGe(lootGe)
				.lootHa(lootHa).suppliesGe(suppliesGe).suppliesHa(suppliesHa).lootUnpriced(lootUnpriced)
				.suppliesUnpriced(suppliesUnpriced));
		}

		private Values(Builder b)
		{
			this.sessionId = b.sessionId;
			this.lootPriceMode = b.lootPriceMode == null ? LootPriceMode.GE : b.lootPriceMode;
			this.lootValue = b.lootValue;
			this.lootValueUnpriced = b.lootValueUnpriced;
			this.lootGe = b.lootGe;
			this.lootHa = b.lootHa;
			this.suppliesGe = b.suppliesGe;
			this.suppliesHa = b.suppliesHa;
			this.lootUnpriced = b.lootUnpriced;
			this.suppliesUnpriced = b.suppliesUnpriced;
			this.items = b.items == null ? ItemDetails.EMPTY : b.items;
			this.hiddenLoot = b.hiddenLoot == null ? HiddenLoot.NONE : b.hiddenLoot;
		}

		/** A builder starting from no values: GE mode, everything 0, no item details. */
		static Builder builder()
		{
			return new Builder();
		}

		/** Builds {@link Values}; a {@code null} price mode is GE and {@code null} items are none. */
		static final class Builder
		{
			private String sessionId;
			private LootPriceMode lootPriceMode = LootPriceMode.GE;
			private long lootValue;
			private int lootValueUnpriced;
			private long lootGe;
			private long lootHa;
			private long suppliesGe;
			private long suppliesHa;
			private int lootUnpriced;
			private int suppliesUnpriced;
			private ItemDetails items = ItemDetails.EMPTY;
			private HiddenLoot hiddenLoot = HiddenLoot.NONE;

			private Builder()
			{
			}

			Builder sessionId(String sessionId)
			{
				this.sessionId = sessionId;
				return this;
			}

			Builder lootPriceMode(LootPriceMode lootPriceMode)
			{
				this.lootPriceMode = lootPriceMode;
				return this;
			}

			Builder lootValue(long lootValue)
			{
				this.lootValue = lootValue;
				return this;
			}

			Builder lootValueUnpriced(int lootValueUnpriced)
			{
				this.lootValueUnpriced = lootValueUnpriced;
				return this;
			}

			Builder lootGe(long lootGe)
			{
				this.lootGe = lootGe;
				return this;
			}

			Builder lootHa(long lootHa)
			{
				this.lootHa = lootHa;
				return this;
			}

			Builder suppliesGe(long suppliesGe)
			{
				this.suppliesGe = suppliesGe;
				return this;
			}

			Builder suppliesHa(long suppliesHa)
			{
				this.suppliesHa = suppliesHa;
				return this;
			}

			Builder lootUnpriced(int lootUnpriced)
			{
				this.lootUnpriced = lootUnpriced;
				return this;
			}

			Builder suppliesUnpriced(int suppliesUnpriced)
			{
				this.suppliesUnpriced = suppliesUnpriced;
				return this;
			}

			Builder items(ItemDetails items)
			{
				this.items = items;
				return this;
			}

			Builder hiddenLoot(HiddenLoot hiddenLoot)
			{
				this.hiddenLoot = hiddenLoot;
				return this;
			}

			Values build()
			{
				return new Values(this);
			}
		}

		/** Id of the session these values belong to. */
		String getSessionId()
		{
			return sessionId;
		}

		/** The price mode {@link #getLootValue()} uses. */
		LootPriceMode getLootPriceMode()
		{
			return lootPriceMode;
		}

		/** Loot value in {@link #getLootPriceMode()}. */
		long getLootValue()
		{
			return lootValue;
		}

		/**
		 * Distinct loot items whose price in {@link #getLootPriceMode()} was unavailable, so {@link #getLootValue()} is
		 * approximate; see {@link LootPricing#countUnpriced}.
		 */
		int getLootValueUnpriced()
		{
			return lootValueUnpriced;
		}

		/** Loot value in {@link LootPriceMode#GE}: GE prices, with a GE price of 0 at high alch ({@link LootPricing}). */
		long getLootGe()
		{
			return lootGe;
		}

		long getLootHa()
		{
			return lootHa;
		}

		long getSuppliesGe()
		{
			return suppliesGe;
		}

		long getSuppliesHa()
		{
			return suppliesHa;
		}

		/** Distinct loot items whose GE price was unavailable (left out of {@link #getLootGe()}). */
		int getLootUnpriced()
		{
			return lootUnpriced;
		}

		/** Distinct supply items whose GE price was unavailable (left out of {@link #getSuppliesGe()}). */
		int getSuppliesUnpriced()
		{
			return suppliesUnpriced;
		}

		/**
		 * The session's loot and supplies item by item, with the unit prices these values use, for the panel's item
		 * lists.
		 */
		ItemDetails getItems()
		{
			return items;
		}

		/**
		 * The loot items left out of these values, which the "Loot received" grid leaves out too; never
		 * {@code null}. {@link #getItems()} still lists them.
		 */
		HiddenLoot getHiddenLoot()
		{
			return hiddenLoot;
		}

		/** Loot GE value minus supplies GE value. */
		long getProfitGe()
		{
			return LedgerHistory.profit(lootGe, suppliesGe);
		}

		/** Loot value in {@link #getLootPriceMode()} minus supplies GE value. */
		long getProfit()
		{
			return LedgerHistory.profit(lootValue, suppliesGe);
		}

		@Override
		public boolean equals(Object o)
		{
			if (this == o)
			{
				return true;
			}
			if (!(o instanceof Values))
			{
				return false;
			}
			Values v = (Values) o;
			return Objects.equals(sessionId, v.sessionId) && lootPriceMode == v.lootPriceMode
				&& lootValue == v.lootValue && lootValueUnpriced == v.lootValueUnpriced && lootGe == v.lootGe && lootHa == v.lootHa
				&& suppliesGe == v.suppliesGe && suppliesHa == v.suppliesHa
				&& lootUnpriced == v.lootUnpriced && suppliesUnpriced == v.suppliesUnpriced && items.equals(v.items)
				&& hiddenLoot.equals(v.hiddenLoot);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(sessionId, lootPriceMode, lootValue, lootValueUnpriced, lootGe, lootHa, suppliesGe,
				suppliesHa, lootUnpriced, suppliesUnpriced, items, hiddenLoot);
		}

		@Override
		public String toString()
		{
			return "Values{sessionId=" + sessionId + ", lootPriceMode=" + lootPriceMode + ", lootValue=" + lootValue
				+ ", lootValueUnpriced=" + lootValueUnpriced + ", lootGe=" + lootGe + ", lootHa=" + lootHa + ", suppliesGe=" + suppliesGe
				+ ", suppliesHa=" + suppliesHa + ", lootUnpriced=" + lootUnpriced
				+ ", suppliesUnpriced=" + suppliesUnpriced + "}";
		}
	}

	private String sessionId;
	private ItemValuation.UnitPrices prices = new ItemValuation.UnitPrices();
	private ItemTally lastLoot;
	private ItemTally lastSupplies;
	private Map<Integer, Integer> lastDoses;
	private LootPriceMode lastMode;
	private HiddenLoot lastHidden;
	private Values last;
	private int lastRetryTick;

	/**
	 * The session's values with the loot valued at GE prices; see
	 * {@link #update(String, ItemTally, ItemTally, Map, LootPriceMode, ItemValuation.PriceLookup, int)}.
	 */
	Values update(String sessionId, ItemTally loot, ItemTally supplies, Map<Integer, Integer> doses,
		ItemValuation.PriceLookup lookup, int tick)
	{
		return update(sessionId, loot, supplies, doses, LootPriceMode.GE, lookup, tick);
	}

	/**
	 * The session's values. Recomputed only when the session, its loot, its supplies, its doses or the price mode
	 * changed since the
	 * last call, or when some GE price was unavailable and {@link #RETRY_TICKS} ticks have passed since it was last
	 * tried; otherwise the
	 * previous values are returned without any lookup.
	 *
	 * @param sessionId id of the session; a different id drops the cached prices
	 * @param loot      the session's loot
	 * @param supplies  the session's supplies (potions in doses)
	 * @param doses     doses per item for the potion ids in {@code supplies}, or {@code null}
	 * @param mode      how the loot is valued ({@code null} means GE); supplies are always valued at GE prices
	 * @param lookup    price source, valid on the calling thread
	 * @param tick      current game tick, for the retry cadence
	 */
	Values update(String sessionId, ItemTally loot, ItemTally supplies, Map<Integer, Integer> doses,
		LootPriceMode mode, ItemValuation.PriceLookup lookup, int tick)
	{
		return update(sessionId, loot, supplies, doses, mode, HiddenLoot.NONE, lookup, tick);
	}

	/**
	 * The session's values, with the loot items {@code hidden} hides, except coins, left out of the loot values
	 *; see {@link #update(String, ItemTally, ItemTally, Map, LootPriceMode, ItemValuation.PriceLookup, int)}.
	 * Other hidden items also recompute the values.
	 *
	 * @param hidden loot items left out ({@code null} is none); they are still priced and listed in the item details
	 */
	Values update(String sessionId, ItemTally loot, ItemTally supplies, Map<Integer, Integer> doses,
		LootPriceMode mode, HiddenLoot hidden, ItemValuation.PriceLookup lookup, int tick)
	{
		final HiddenLoot hiddenLoot = hidden == null ? HiddenLoot.NONE : hidden;
		final LootPriceMode lootMode = mode == null ? LootPriceMode.GE : mode;
		if (!Objects.equals(sessionId, this.sessionId))
		{
			reset();
			this.sessionId = sessionId;
		}
		final ItemTally lootTally = loot == null ? new ItemTally() : loot;
		final ItemTally suppliesTally = supplies == null ? new ItemTally() : supplies;
		final Map<Integer, Integer> doseMap = doses == null ? new TreeMap<>() : doses;
		final boolean changed = last == null || !lootTally.equals(lastLoot) || !suppliesTally.equals(lastSupplies)
			|| !doseMap.equals(lastDoses) || lootMode != lastMode || !hiddenLoot.equals(lastHidden);
		// The tick count restarts after a login or hop; a retry is then due at once.
		final boolean retry = !prices.getGeMissing().isEmpty()
			&& (tick - lastRetryTick >= RETRY_TICKS || tick < lastRetryTick);
		if (!changed && !retry)
		{
			return last;
		}

		final boolean noneMissing = prices.getGeMissing().isEmpty();
		priceNew(lootTally, lookup);
		priceNew(suppliesTally, lookup);
		if (retry)
		{
			// Items whose GE price was unavailable are looked up again (new items were priced above); items no
			// longer in the tallies are forgotten, and priced afresh if they come back.
			for (int id : prices.getGeMissing().toArray(new Integer[0]))
			{
				if (lootTally.get(id) > 0 || suppliesTally.get(id) > 0)
				{
					ItemValuation.capturePrice(id, lookup, prices);
				}
				else
				{
					prices.getGeMissing().remove(id);
				}
			}
			lastRetryTick = tick;
		}
		else if (noneMissing && !prices.getGeMissing().isEmpty())
		{
			// The first unavailable price: retry it RETRY_TICKS from now, even while the tallies keep changing.
			lastRetryTick = tick;
		}

		lastLoot = new ItemTally(lootTally);
		lastSupplies = new ItemTally(suppliesTally);
		lastDoses = new TreeMap<>(doseMap);
		lastMode = lootMode;
		lastHidden = hiddenLoot;
		final ItemTally valued = hiddenLoot.valued(lootTally);
		last = Values.builder()
			.sessionId(sessionId)
			.lootPriceMode(lootMode)
			.lootValue(LootPricing.total(valued, lootMode, prices.getGe(), prices.getHa(), null))
			.lootValueUnpriced(LootPricing.countUnpriced(valued, lootMode, prices.getGe(), prices.getHa()))
			.lootGe(LootPricing.total(valued, LootPriceMode.GE, prices.getGe(), prices.getHa(), null))
			.lootHa(ItemValuation.total(valued, prices.getHa()))
			.suppliesGe(ItemValuation.total(suppliesTally, prices.getGe(), doseMap))
			.suppliesHa(ItemValuation.total(suppliesTally, prices.getHa(), doseMap))
			.lootUnpriced(ItemValuation.countUnpriced(valued, prices.getGe()))
			.suppliesUnpriced(ItemValuation.countUnpriced(suppliesTally, prices.getGe()))
			.items(ItemDetails.of(lootTally, suppliesTally, doseMap, prices.getGe(), prices.getHa()))
			.hiddenLoot(hiddenLoot)
			.build();
		return last;
	}

	/** Looks up items not priced (or tried) yet this session. */
	private void priceNew(ItemTally tally, ItemValuation.PriceLookup lookup)
	{
		for (int id : tally.itemIds())
		{
			if (!prices.getGe().containsKey(id) && !prices.getGeMissing().contains(id))
			{
				ItemValuation.capturePrice(id, lookup, prices);
			}
		}
	}

	/** Drops the cached prices and values (session ended or changed). */
	void reset()
	{
		sessionId = null;
		prices = new ItemValuation.UnitPrices();
		lastLoot = null;
		lastSupplies = null;
		lastDoses = null;
		lastMode = null;
		lastHidden = null;
		last = null;
		lastRetryTick = 0;
	}
}
