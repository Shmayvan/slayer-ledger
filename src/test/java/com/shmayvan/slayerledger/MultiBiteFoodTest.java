package com.shmayvan.slayerledger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Test;

/**
 * Food eaten in parts (pizzas, pies and cakes) is tracked in bites, like potion doses, so eating a whole one costs
 * exactly one item.
 */
public class MultiBiteFoodTest
{
	private static final String[] EAT = {"Eat", null, null, null, "Drop"};
	private static final String[] NONE = {null, null, null, null, "Drop"};

	/** Classifies with the production rules; the pie dish and everything unknown have no "Eat" action. */
	private static final SupplyTracker.Classifier CLASSIFIER = id ->
	{
		if (id == ItemID.PIEDISH)
		{
			return SupplyTracker.classify(id, "Pie dish", NONE, false, false, false);
		}
		if (id == ItemID.SHARK || SupplyTracker.MULTI_BITE_FOOD.containsKey(id))
		{
			return SupplyTracker.classify(id, "Food " + id, EAT, false, false, false);
		}
		return SupplyTracker.classify(id, "Other", NONE, false, false, false);
	};

	private SupplyTracker supplies;

	@Before
	public void setUp()
	{
		supplies = new SupplyTracker(CLASSIFIER);
	}

	private SupplyTracker.Usage tick(int... idQty)
	{
		return supplies.update(SupplyTrackerTest.obs(idQty), true, false, Collections.emptySet());
	}

	@Test
	public void itemIdsMatchTheGameValues()
	{
		// Checked against net.runelite.api.gameval.ItemID in RuneLite 1.13.4.
		assertEquals(2289, ItemID.PLAIN_PIZZA);
		assertEquals(2291, ItemID.HALF_PLAIN_PIZZA);
		assertEquals(2293, ItemID.MEAT_PIZZA);
		assertEquals(2295, ItemID.HALF_MEAT_PIZZA);
		assertEquals(2297, ItemID.ANCHOVIE_PIZZA);
		assertEquals(2299, ItemID.HALF_ANCHOVIE_PIZZA);
		assertEquals(2301, ItemID.PINEAPPLE_PIZZA);
		assertEquals(2303, ItemID.HALF_PINEAPPLE_PIZZA);
		assertEquals(2323, ItemID.APPLE_PIE);
		assertEquals(2325, ItemID.REDBERRY_PIE);
		assertEquals(2327, ItemID.MEAT_PIE);
		assertEquals(2331, ItemID.HALF_A_MEAT_PIE);
		assertEquals(2333, ItemID.HALF_A_REDBERRY_PIE);
		assertEquals(2335, ItemID.HALF_AN_APPLE_PIE);
		assertEquals(7178, ItemID.GARDEN_PIE);
		assertEquals(7180, ItemID.HALF_GARDEN_PIE);
		assertEquals(7188, ItemID.FISH_PIE);
		assertEquals(7190, ItemID.HALF_FISH_PIE);
		assertEquals(7198, ItemID.ADMIRAL_PIE);
		assertEquals(7200, ItemID.HALF_ADMIRAL_PIE);
		assertEquals(7208, ItemID.WILD_PIE);
		assertEquals(7210, ItemID.HALF_WILD_PIE);
		assertEquals(7218, ItemID.SUMMER_PIE);
		assertEquals(7220, ItemID.HALF_SUMMER_PIE);
		assertEquals(19662, ItemID.BOTANICAL_PIE);
		assertEquals(19659, ItemID.HALF_BOTANICAL_PIE);
		assertEquals(21690, ItemID.MUSHROOM_PIE);
		assertEquals(21687, ItemID.HALF_MUSHROOM_PIE);
		assertEquals(22795, ItemID.DRAGONFRUIT_PIE);
		assertEquals(22792, ItemID.HALF_DRAGONFRUIT_PIE);
		assertEquals(1891, ItemID.CAKE);
		assertEquals(1893, ItemID.PARTIAL_CAKE);
		assertEquals(1895, ItemID.CAKE_SLICE);
		assertEquals(1897, ItemID.CHOCOLATE_CAKE);
		assertEquals(1899, ItemID.PARTIAL_CHOCOLATE_CAKE);
		assertEquals(1901, ItemID.CHOCOLATE_SLICE);
		assertEquals(2313, ItemID.PIEDISH);
	}

	@Test
	public void tableHoldsEveryStageWithItsBites()
	{
		assertEquals(4 * 2 + 11 * 2 + 2 * 3, SupplyTracker.MULTI_BITE_FOOD.size());
		assertArrayEquals(new int[]{ItemID.PLAIN_PIZZA, 2, 2}, SupplyTracker.MULTI_BITE_FOOD.get(ItemID.PLAIN_PIZZA));
		assertArrayEquals(new int[]{ItemID.PLAIN_PIZZA, 1, 2}, SupplyTracker.MULTI_BITE_FOOD.get(ItemID.HALF_PLAIN_PIZZA));
		assertArrayEquals(new int[]{ItemID.MEAT_PIE, 1, 2}, SupplyTracker.MULTI_BITE_FOOD.get(ItemID.HALF_A_MEAT_PIE));
		assertArrayEquals(new int[]{ItemID.CAKE, 3, 3}, SupplyTracker.MULTI_BITE_FOOD.get(ItemID.CAKE));
		assertArrayEquals(new int[]{ItemID.CAKE, 2, 3}, SupplyTracker.MULTI_BITE_FOOD.get(ItemID.PARTIAL_CAKE));
		assertArrayEquals(new int[]{ItemID.CAKE, 1, 3}, SupplyTracker.MULTI_BITE_FOOD.get(ItemID.CAKE_SLICE));
		assertArrayEquals(new int[]{ItemID.CHOCOLATE_CAKE, 1, 3},
			SupplyTracker.MULTI_BITE_FOOD.get(ItemID.CHOCOLATE_SLICE));
		assertFalse("the pie dish is not food", SupplyTracker.MULTI_BITE_FOOD.containsKey(ItemID.PIEDISH));
		assertFalse("noted food is never a supply", SupplyTracker.MULTI_BITE_FOOD.containsKey(ItemID.Cert.PLAIN_PIZZA));
	}

	@Test
	public void classifiedByIdAsFoodInBites()
	{
		SupplyTracker.ItemInfo half = CLASSIFIER.classify(ItemID.HALF_MEAT_PIZZA);
		assertEquals(SupplyTracker.Category.FOOD, half.getCategory());
		assertEquals(1, half.getDoses());
		assertEquals(ItemID.MEAT_PIZZA, half.getFullItemId());
		assertEquals(2, half.getDosesPerItem());
		assertEquals(CLASSIFIER.classify(ItemID.MEAT_PIZZA).getFamily(), half.getFamily());
		SupplyTracker.ItemInfo shark = CLASSIFIER.classify(ItemID.SHARK);
		assertNull("ordinary food is counted per item", shark.getFamily());
		assertEquals(SupplyTracker.Category.OTHER, CLASSIFIER.classify(ItemID.PIEDISH).getCategory());
	}

	@Test
	public void eatingAWholePizzaCostsExactlyOnePizza()
	{
		tick(ItemID.PLAIN_PIZZA, 1);
		SupplyTracker.Usage first = tick(ItemID.HALF_PLAIN_PIZZA, 1);
		SupplyTracker.Usage second = tick();

		ItemTally used = new ItemTally();
		Map<Integer, Integer> doses = new HashMap<>();
		for (SupplyTracker.Usage usage : new SupplyTracker.Usage[]{first, second})
		{
			used.merge(usage.getUsed());
			doses.putAll(usage.getDoses());
		}
		assertEquals("two bites, recorded under the full pizza", 2, used.get(ItemID.PLAIN_PIZZA));
		assertEquals("the half pizza is not an item of its own", 0, used.get(ItemID.HALF_PLAIN_PIZZA));
		assertEquals(1, used.size());
		assertEquals(Integer.valueOf(2), doses.get(ItemID.PLAIN_PIZZA));

		// Valued at exactly one pizza (not a pizza plus a half pizza, as before).
		Map<Integer, Long> prices = new HashMap<>();
		prices.put(ItemID.PLAIN_PIZZA, 300L);
		prices.put(ItemID.HALF_PLAIN_PIZZA, 150L);
		assertEquals(300L, ItemValuation.total(used, prices, doses));
	}

	@Test
	public void aHalfPizzaHeldAtSessionStartCostsHalf()
	{
		tick(ItemID.HALF_ANCHOVIE_PIZZA, 1, ItemID.SHARK, 2);
		SupplyTracker.Usage usage = tick(ItemID.SHARK, 2);
		assertEquals(1, usage.getUsed().get(ItemID.ANCHOVIE_PIZZA));
		assertEquals(Integer.valueOf(2), usage.getDoses().get(ItemID.ANCHOVIE_PIZZA));
		Map<Integer, Long> prices = Collections.singletonMap(ItemID.ANCHOVIE_PIZZA, 401L);
		assertEquals("half of 401, rounded", 201L, ItemValuation.total(usage.getUsed(), prices, usage.getDoses()));
	}

	@Test
	public void eatingAPieLeavesTheDishUncounted()
	{
		tick(ItemID.MEAT_PIE, 2);
		assertEquals(1, tick(ItemID.MEAT_PIE, 1, ItemID.HALF_A_MEAT_PIE, 1).getUsed().get(ItemID.MEAT_PIE));
		SupplyTracker.Usage last = tick(ItemID.MEAT_PIE, 1, ItemID.PIEDISH, 1);
		assertEquals(1, last.getUsed().get(ItemID.MEAT_PIE));
		assertEquals(1, last.getUsed().size());
		assertTrue("the dish is no return", last.getReturned().isEmpty());
	}

	@Test
	public void aCakeIsThreeBites()
	{
		tick(ItemID.CHOCOLATE_CAKE, 1);
		ItemTally used = new ItemTally();
		Map<Integer, Integer> doses = new HashMap<>();
		for (int[] after : new int[][]{{ItemID.PARTIAL_CHOCOLATE_CAKE, 1}, {ItemID.CHOCOLATE_SLICE, 1}, {}})
		{
			SupplyTracker.Usage usage = tick(after);
			used.merge(usage.getUsed());
			doses.putAll(usage.getDoses());
		}
		assertEquals(3, used.get(ItemID.CHOCOLATE_CAKE));
		assertEquals(Integer.valueOf(3), doses.get(ItemID.CHOCOLATE_CAKE));
		assertEquals(900L, ItemValuation.total(used, Collections.singletonMap(ItemID.CHOCOLATE_CAKE, 900L), doses));
	}

	@Test
	public void differentFoodsAreSeparateAndSlicesAreNotCake()
	{
		tick(ItemID.PLAIN_PIZZA, 1, ItemID.MEAT_PIZZA, 1, ItemID.CAKE_SLICE, 1);
		SupplyTracker.Usage usage = tick(ItemID.HALF_PLAIN_PIZZA, 1, ItemID.MEAT_PIZZA, 1);
		assertEquals(1, usage.getUsed().get(ItemID.PLAIN_PIZZA));
		assertEquals(0, usage.getUsed().get(ItemID.MEAT_PIZZA));
		assertEquals("a slice is one bite of a cake", 1, usage.getUsed().get(ItemID.CAKE));
	}

	@Test
	public void droppingAPartNetsNothing()
	{
		tick(ItemID.PLAIN_PIZZA, 1, ItemID.HALF_PLAIN_PIZZA, 1);
		SupplyTracker.Usage usage = supplies.update(SupplyTrackerTest.obs(ItemID.PLAIN_PIZZA, 1), true, false,
			Collections.singleton(ItemID.HALF_PLAIN_PIZZA));
		assertTrue(usage.isEmpty());
	}
}
