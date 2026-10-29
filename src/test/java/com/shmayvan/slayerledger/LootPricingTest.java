package com.shmayvan.slayerledger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Loot valued in a price mode: GE, high alch, a missing GE price, a GE price of 0 (valued at high alch in every
 * mode), and doses.
 */
public class LootPricingTest
{
	private static final int RUNE = 1;
	private static final int UNTRADEABLE = 2;
	private static final int NO_GE = 3;
	private static final int POTION = 4;

	private static Map<Integer, Long> prices(long... idPrice)
	{
		Map<Integer, Long> map = new HashMap<>();
		for (int i = 0; i < idPrice.length; i += 2)
		{
			map.put((int) idPrice[i], idPrice[i + 1]);
		}
		return map;
	}

	private static ItemTally tally(int... idQty)
	{
		ItemTally t = new ItemTally();
		for (int i = 0; i < idQty.length; i += 2)
		{
			t.add(idQty[i], idQty[i + 1]);
		}
		return t;
	}

	/** GE: 100 each, untradeable 0 (known), no GE price for {@link #NO_GE}. */
	private final Map<Integer, Long> ge = prices(RUNE, 100, UNTRADEABLE, 0);
	private final Map<Integer, Long> ha = prices(RUNE, 60, UNTRADEABLE, 50, NO_GE, 300);
	private final ItemTally loot = tally(RUNE, 2, UNTRADEABLE, 1, NO_GE, 1);

	@Test
	public void geValuesAtGeAndLeavesOutAMissingPrice()
	{
		// Runes at GE, the untradeable item (GE 0) at its high alch value, the item without a GE price left out.
		assertEquals(200 + 50, LootPricing.total(loot, LootPriceMode.GE, ge, ha, null));
		assertEquals(1, LootPricing.countUnpriced(loot, LootPriceMode.GE, ge, ha));
		assertEquals(200, LootPricing.total(loot, LootPriceMode.GE, ge, null, null));
	}

	@Test
	public void aZeroGePriceCountsAtHighAlchInEveryMode()
	{
		// 7 Brimstone keys (untradeable: GE 0, high alch 48,000) and an item at GE 100 / high alch 60.
		int key = 23083;
		ItemTally keys = tally(key, 7, RUNE, 1);
		Map<Integer, Long> keyGe = prices(key, 0, RUNE, 100);
		Map<Integer, Long> keyHa = prices(key, 48_000, RUNE, 60);
		assertEquals(336_100, LootPricing.total(keys, LootPriceMode.GE, keyGe, keyHa, null));
		assertEquals(336_060, LootPricing.total(keys, LootPriceMode.HIGH_ALCH, keyGe, keyHa, null));
		for (LootPriceMode mode : LootPriceMode.values())
		{
			assertEquals("a GE price of 0 is known, not unpriced (" + mode + ")", 0,
				LootPricing.countUnpriced(keys, mode, keyGe, keyHa));
		}
		assertEquals(Long.valueOf(48_000), LootPricing.geUnitPrices(keyGe, keyHa).get(key));
		assertEquals("the input prices are not changed", Long.valueOf(0), keyGe.get(key));
	}

	@Test
	public void aMissingGePriceIsStillUnpricedInGe()
	{
		ItemTally t = tally(NO_GE, 2, RUNE, 1);
		assertEquals(100, LootPricing.total(t, LootPriceMode.GE, ge, ha, null));
		assertEquals(1, LootPricing.countUnpriced(t, LootPriceMode.GE, ge, ha));
		assertEquals(null, LootPricing.geUnitPrices(ge, ha).get(NO_GE));
	}

	@Test
	public void coinsStayAtFaceValue()
	{
		// Coins are captured at 1 gp for both prices (ItemValuation), so no mode changes them.
		ItemTally coins = tally(ItemValuation.COINS, 5_000);
		Map<Integer, Long> one = prices(ItemValuation.COINS, 1);
		for (LootPriceMode mode : LootPriceMode.values())
		{
			assertEquals(5_000, LootPricing.total(coins, mode, one, one, null));
		}
	}

	@Test
	public void aZeroGePriceWithoutHighAlchStaysZero()
	{
		ItemTally t = tally(UNTRADEABLE, 3);
		assertEquals(0, LootPricing.total(t, LootPriceMode.GE, ge, prices(), null));
		assertEquals(0, LootPricing.countUnpriced(t, LootPriceMode.GE, ge, prices()));
	}

	@Test
	public void highAlchValuesAtHighAlch()
	{
		assertEquals(120 + 50 + 300, LootPricing.total(loot, LootPriceMode.HIGH_ALCH, ge, ha, null));
		assertEquals(0, LootPricing.countUnpriced(loot, LootPriceMode.HIGH_ALCH, ge, ha));
		assertEquals("no high alch values at all", 3,
			LootPricing.countUnpriced(loot, LootPriceMode.HIGH_ALCH, ge, null));
	}

	@Test
	public void anItemWithNeitherPriceIsLeftOut()
	{
		ItemTally t = tally(RUNE, 1, 99, 5);
		assertEquals(100, LootPricing.total(t, LootPriceMode.GE, ge, ha, null));
		assertEquals(1, LootPricing.countUnpriced(t, LootPriceMode.GE, ge, ha));
		assertEquals(60, LootPricing.total(t, LootPriceMode.HIGH_ALCH, ge, ha, null));
		assertEquals(1, LootPricing.countUnpriced(t, LootPriceMode.HIGH_ALCH, ge, ha));
		for (LootPriceMode mode : LootPriceMode.values())
		{
			assertEquals(mode.name(), 0, LootPricing.total(t, mode, null, null, null));
		}
	}

	@Test
	public void dosesAreValuedPerDoseInEveryMode()
	{
		// 3 doses of a 4-dose potion: GE 800, high alch 100 per potion.
		ItemTally doses = tally(POTION, 3);
		Map<Integer, Long> potionGe = prices(POTION, 800);
		Map<Integer, Long> potionHa = prices(POTION, 100);
		Map<Integer, Integer> perItem = Collections.singletonMap(POTION, 4);
		assertEquals(600, LootPricing.total(doses, LootPriceMode.GE, potionGe, potionHa, perItem));
		assertEquals(75, LootPricing.total(doses, LootPriceMode.HIGH_ALCH, potionGe, potionHa, perItem));
		assertEquals(2_400, LootPricing.total(doses, LootPriceMode.GE, potionGe, potionHa, null));
	}

	@Test
	public void nullModeIsGe()
	{
		assertEquals(250, LootPricing.total(loot, null, ge, ha, null));
		assertEquals(1, LootPricing.countUnpriced(loot, null, ge, ha));
	}

	@Test
	public void displayNames()
	{
		assertEquals("GE", LootPriceMode.GE.toString());
		assertEquals("High alch", LootPriceMode.HIGH_ALCH.toString());
	}

	@Test
	public void onlyGeAndHighAlchRemain()
	{
		// "Higher of the two" was removed (owner decision, 2026-10-25).
		assertArrayEquals(new LootPriceMode[]{LootPriceMode.GE, LootPriceMode.HIGH_ALCH}, LootPriceMode.values());
	}
}
