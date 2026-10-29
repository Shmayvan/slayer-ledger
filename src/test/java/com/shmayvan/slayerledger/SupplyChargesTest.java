package com.shmayvan.slayerledger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Test;

/**
 * Slayer consumables (bag of salt, ice cooler), teleport tablets, and items used per charge (teleport jewellery and the
 * fungicide spray), through the same per-tick diff, exclusions and valuation as the other supplies.
 */
public class SupplyChargesTest
{
	private static final String[] NONE = {null, null, null, null, "Drop"};
	private static final String[] WEAR = {null, "Wear", "Rub", null, "Drop"};
	private static final String[] BREAK = {"Break", null, null, null, "Drop"};
	private static final String[] SPRAY = {null, "Check", null, null, "Drop"};
	private static final String[] EAT = {"Eat", null, null, null, "Drop"};

	/** Fragments have a "Break" action but are not teleports. */
	private static final int UNIDENTIFIED_FRAGMENT = 1_000_001;

	/** Classifies with the production rules (charged items and Slayer consumables are decided by id). */
	private static final SupplyTracker.Classifier CLASSIFIER = id ->
	{
		switch (id)
		{
			case ItemID.SHARK:
				return SupplyTracker.classify(id, "Shark", EAT, false, false, false);
			case ItemID.POH_TABLET_VARROCKTELEPORT:
				return SupplyTracker.classify(id, "Varrock teleport", BREAK, false, false, false);
			case ItemID.POH_TABLET_TELEPORTTOHOUSE:
				return SupplyTracker.classify(id, "Teleport to house", BREAK, false, false, false);
			case UNIDENTIFIED_FRAGMENT:
				return SupplyTracker.classify(id, "Unidentified fragment (misc)", BREAK, false, false, false);
			case ItemID.SLAYER_SPRAY_PUMP_0:
			case ItemID.SLAYER_FUNGICIDE:
				return SupplyTracker.classify(id, "Fungicide", SPRAY, false, false, false);
			case ItemID.AMULET_OF_GLORY:
			case ItemID.AMULET_OF_GLORY_INF:
			case ItemID.SLAYER_RING_ETERNAL:
				return SupplyTracker.classify(id, "Uncharged or eternal", WEAR, false, false, false);
			default:
				if (SupplyTracker.classifiesById(id))
				{
					return SupplyTracker.classify(id, "Charged " + id, WEAR, false, false, false);
				}
				return SupplyTracker.classify(id, "Other", NONE, false, false, false);
		}
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

	/** Runs the ticks after a baseline of {@code start} and returns what they used in total, with the doses map. */
	private SupplyTracker.Usage total(int[] start, int[]... ticks)
	{
		tick(start);
		ItemTally used = new ItemTally();
		ItemTally returned = new ItemTally();
		Map<Integer, Integer> doses = new HashMap<>();
		for (int[] t : ticks)
		{
			SupplyTracker.Usage usage = tick(t);
			used.merge(usage.getUsed());
			returned.merge(usage.getReturned());
			doses.putAll(usage.getDoses());
		}
		return new SupplyTracker.Usage(used, returned, doses);
	}

	// ---- Item tables ----

	@Test
	public void itemIdsMatchTheGameValues()
	{
		// Checked against net.runelite.api.gameval.ItemID in RuneLite 1.13.4.
		assertEquals(4161, ItemID.SLAYER_BAG_OF_SALT);
		assertEquals(6696, ItemID.SLAYER_ICY_WATER);
		assertEquals(7421, ItemID.SLAYER_SPRAY_PUMP_10);
		assertEquals(7430, ItemID.SLAYER_SPRAY_PUMP_1);
		assertEquals(7431, ItemID.SLAYER_SPRAY_PUMP_0);
		assertEquals(7432, ItemID.SLAYER_FUNGICIDE);
		assertEquals(2552, ItemID.RING_OF_DUELING_8);
		assertEquals(2566, ItemID.RING_OF_DUELING_1);
		assertEquals(3853, ItemID.NECKLACE_OF_MINIGAMES_8);
		assertEquals(3867, ItemID.NECKLACE_OF_MINIGAMES_1);
		assertEquals(11978, ItemID.AMULET_OF_GLORY_6);
		assertEquals(1712, ItemID.AMULET_OF_GLORY_4);
		assertEquals(1706, ItemID.AMULET_OF_GLORY_1);
		assertEquals(1704, ItemID.AMULET_OF_GLORY);
		assertEquals(19707, ItemID.AMULET_OF_GLORY_INF);
		assertEquals(11964, ItemID.TRAIL_AMULET_OF_GLORY_6);
		assertEquals(11968, ItemID.JEWL_NECKLACE_OF_SKILLS_6);
		assertEquals(11972, ItemID.JEWL_BRACELET_OF_COMBAT_6);
		assertEquals(11980, ItemID.RING_OF_WEALTH_5);
		assertEquals(20786, ItemID.RING_OF_WEALTH_I5);
		assertEquals(21146, ItemID.NECKLACE_OF_PASSAGE_5);
		assertEquals(11194, ItemID.NECKLACE_OF_DIGSITE_5);
		assertEquals(11190, ItemID.NECKLACE_OF_DIGSITE_1);
		assertEquals(21166, ItemID.BURNING_AMULET_5);
		assertEquals(11866, ItemID.SLAYER_RING_8);
		assertEquals(11873, ItemID.SLAYER_RING_1);
		assertEquals(21268, ItemID.SLAYER_RING_ETERNAL);
		assertEquals(21129, ItemID.RING_OF_RETURNING_5);
		assertEquals(8007, ItemID.POH_TABLET_VARROCKTELEPORT);
	}

	@Test
	public void chargedItemsAreKeyedByTheirHighestChargeVariant()
	{
		// Dueling, games, slayer ring: 8 each; glory, glory (t), skills, combat: 6 each; wealth, wealth (i), passage,
		// digsite, burning, returning: 5 each; the fungicide spray: 10.
		assertEquals(3 * 8 + 4 * 6 + 6 * 5 + 10, SupplyTracker.CHARGED_ITEMS.size());
		assertArrayEquals(new int[]{ItemID.RING_OF_DUELING_8, 8, 8},
			SupplyTracker.CHARGED_ITEMS.get(ItemID.RING_OF_DUELING_8));
		assertArrayEquals(new int[]{ItemID.RING_OF_DUELING_8, 1, 8},
			SupplyTracker.CHARGED_ITEMS.get(ItemID.RING_OF_DUELING_1));
		assertArrayEquals(new int[]{ItemID.AMULET_OF_GLORY_6, 4, 6},
			SupplyTracker.CHARGED_ITEMS.get(ItemID.AMULET_OF_GLORY_4));
		assertArrayEquals(new int[]{ItemID.TRAIL_AMULET_OF_GLORY_6, 3, 6},
			SupplyTracker.CHARGED_ITEMS.get(ItemID.TRAIL_AMULET_OF_GLORY_3));
		assertArrayEquals(new int[]{ItemID.NECKLACE_OF_DIGSITE_5, 1, 5},
			SupplyTracker.CHARGED_ITEMS.get(ItemID.NECKLACE_OF_DIGSITE_1));
		assertArrayEquals(new int[]{ItemID.RING_OF_WEALTH_I5, 2, 5},
			SupplyTracker.CHARGED_ITEMS.get(ItemID.RING_OF_WEALTH_I2));
		assertArrayEquals(new int[]{ItemID.SLAYER_RING_8, 3, 8},
			SupplyTracker.CHARGED_ITEMS.get(ItemID.SLAYER_RING_3));
		assertArrayEquals(new int[]{ItemID.SLAYER_SPRAY_PUMP_10, 9, 10},
			SupplyTracker.CHARGED_ITEMS.get(ItemID.SLAYER_SPRAY_PUMP_9));
		for (int notCharged : new int[]{ItemID.AMULET_OF_GLORY, ItemID.AMULET_OF_GLORY_INF, ItemID.SLAYER_RING_ETERNAL,
			ItemID.JEWL_NECKLACE_OF_SKILLS, ItemID.JEWL_BRACELET_OF_COMBAT, ItemID.RING_OF_WEALTH,
			ItemID.RING_OF_WEALTH_I, ItemID.TRAIL_AMULET_OF_GLORY, ItemID.SLAYER_SPRAY_PUMP_0, ItemID.SLAYER_FUNGICIDE,
			ItemID.Cert.RING_OF_DUELING_8})
		{
			assertFalse(notCharged + " is not a charged supply", SupplyTracker.CHARGED_ITEMS.containsKey(notCharged));
			assertFalse(SupplyTracker.classifiesById(notCharged));
		}
		assertTrue(SupplyTracker.SLAYER_CONSUMABLES.contains(ItemID.SLAYER_BAG_OF_SALT));
		assertTrue(SupplyTracker.SLAYER_CONSUMABLES.contains(ItemID.SLAYER_ICY_WATER));
		assertEquals(2, SupplyTracker.SLAYER_CONSUMABLES.size());
	}

	@Test
	public void classifiesTheNewCategories()
	{
		SupplyTracker.ItemInfo glory = CLASSIFIER.classify(ItemID.AMULET_OF_GLORY_4);
		assertEquals(SupplyTracker.Category.CHARGES, glory.getCategory());
		assertEquals(4, glory.getDoses());
		assertEquals(ItemID.AMULET_OF_GLORY_6, glory.getFullItemId());
		assertEquals(6, glory.getDosesPerItem());
		assertEquals(SupplyTracker.Category.SLAYER_CONSUMABLE, CLASSIFIER.classify(ItemID.SLAYER_BAG_OF_SALT).getCategory());
		assertNull(CLASSIFIER.classify(ItemID.SLAYER_ICY_WATER).getFamily());
		assertEquals(SupplyTracker.Category.TELEPORT_TABLET,
			CLASSIFIER.classify(ItemID.POH_TABLET_VARROCKTELEPORT).getCategory());
		assertEquals(SupplyTracker.Category.TELEPORT_TABLET,
			CLASSIFIER.classify(ItemID.POH_TABLET_TELEPORTTOHOUSE).getCategory());
		assertEquals("a Break action alone is not a teleport", SupplyTracker.Category.OTHER,
			CLASSIFIER.classify(UNIDENTIFIED_FRAGMENT).getCategory());
		assertEquals("a teleport name alone is not a tablet", SupplyTracker.Category.OTHER,
			SupplyTracker.classify("Teleport crystal", NONE, false, false, false).getCategory());
		assertEquals("tablets need no id", SupplyTracker.Category.TELEPORT_TABLET,
			SupplyTracker.classify("Salve graveyard teleport", BREAK, false, false, false).getCategory());
		for (int id : new int[]{ItemID.AMULET_OF_GLORY, ItemID.AMULET_OF_GLORY_INF, ItemID.SLAYER_RING_ETERNAL,
			ItemID.SLAYER_SPRAY_PUMP_0, ItemID.SLAYER_FUNGICIDE})
		{
			assertEquals(id + " is not a supply", SupplyTracker.Category.OTHER, CLASSIFIER.classify(id).getCategory());
		}
	}

	// ---- Slayer consumables ----

	@Test
	public void bagsOfSaltAndIceCoolersCountOnePerUse()
	{
		SupplyTracker.Usage usage = total(new int[]{ItemID.SLAYER_BAG_OF_SALT, 50, ItemID.SLAYER_ICY_WATER, 30},
			new int[]{ItemID.SLAYER_BAG_OF_SALT, 49, ItemID.SLAYER_ICY_WATER, 30},
			new int[]{ItemID.SLAYER_BAG_OF_SALT, 47, ItemID.SLAYER_ICY_WATER, 29});
		assertEquals(3, usage.getUsed().get(ItemID.SLAYER_BAG_OF_SALT));
		assertEquals(1, usage.getUsed().get(ItemID.SLAYER_ICY_WATER));
		assertTrue("counted per item, not in doses", usage.getDoses().isEmpty());
		assertTrue(usage.getReturned().isEmpty());

		// Buying more is an increase, never a use or a return.
		SupplyTracker.Usage bought = tick(ItemID.SLAYER_BAG_OF_SALT, 100, ItemID.SLAYER_ICY_WATER, 29);
		assertTrue(bought.isEmpty());
	}

	// ---- Fungicide spray ----

	@Test
	public void fungicideSprayCountsPerChargeIncludingTheLast()
	{
		SupplyTracker.Usage usage = total(new int[]{ItemID.SLAYER_SPRAY_PUMP_10, 1},
			new int[]{ItemID.SLAYER_SPRAY_PUMP_9, 1},
			new int[]{ItemID.SLAYER_SPRAY_PUMP_8, 1},
			new int[]{ItemID.SLAYER_SPRAY_PUMP_8, 1},
			new int[]{ItemID.SLAYER_SPRAY_PUMP_7, 1});
		assertEquals("three charges, recorded under the full spray", 3, usage.getUsed().get(ItemID.SLAYER_SPRAY_PUMP_10));
		assertEquals(1, usage.getUsed().size());
		assertEquals(Integer.valueOf(10), usage.getDoses().get(ItemID.SLAYER_SPRAY_PUMP_10));
		assertEquals("3/10 of the spray's price", 30L, ItemValuation.total(usage.getUsed(),
			Collections.singletonMap(ItemID.SLAYER_SPRAY_PUMP_10, 100L), usage.getDoses()));

		supplies.reset();
		SupplyTracker.Usage last = total(new int[]{ItemID.SLAYER_SPRAY_PUMP_1, 1},
			new int[]{ItemID.SLAYER_SPRAY_PUMP_0, 1});
		assertEquals("the last charge leaves the empty spray", 1, last.getUsed().get(ItemID.SLAYER_SPRAY_PUMP_10));
		assertEquals(1, last.getUsed().size());
	}

	@Test
	public void refillingTheSprayIsNotAUseAndTheRefillIsNotASupply()
	{
		SupplyTracker.Usage usage = total(new int[]{ItemID.SLAYER_SPRAY_PUMP_0, 1, ItemID.SLAYER_FUNGICIDE, 2},
			// Fungicide used on the empty spray: the refill item is used up and the spray is full again.
			new int[]{ItemID.SLAYER_SPRAY_PUMP_10, 1, ItemID.SLAYER_FUNGICIDE, 1},
			new int[]{ItemID.SLAYER_SPRAY_PUMP_9, 1, ItemID.SLAYER_FUNGICIDE, 1},
			// Topping up a partly used spray.
			new int[]{ItemID.SLAYER_SPRAY_PUMP_10, 1});
		assertEquals("only the charge sprayed", 1, usage.getUsed().get(ItemID.SLAYER_SPRAY_PUMP_10));
		assertEquals(0, usage.getUsed().get(ItemID.SLAYER_FUNGICIDE));
		assertEquals(1, usage.getUsed().size());
		assertTrue(usage.getReturned().isEmpty());
	}

	// ---- Teleport tablets ----

	@Test
	public void eachTabletBrokenCountsAsOne()
	{
		SupplyTracker.Usage usage = total(
			new int[]{ItemID.POH_TABLET_VARROCKTELEPORT, 5, ItemID.POH_TABLET_TELEPORTTOHOUSE, 10, UNIDENTIFIED_FRAGMENT, 3},
			new int[]{ItemID.POH_TABLET_VARROCKTELEPORT, 4, ItemID.POH_TABLET_TELEPORTTOHOUSE, 10, UNIDENTIFIED_FRAGMENT, 3},
			new int[]{ItemID.POH_TABLET_VARROCKTELEPORT, 4, ItemID.POH_TABLET_TELEPORTTOHOUSE, 9, UNIDENTIFIED_FRAGMENT, 2},
			// The last tablet of a stack.
			new int[]{ItemID.POH_TABLET_TELEPORTTOHOUSE, 9, UNIDENTIFIED_FRAGMENT, 2});
		assertEquals(5, usage.getUsed().get(ItemID.POH_TABLET_VARROCKTELEPORT));
		assertEquals(1, usage.getUsed().get(ItemID.POH_TABLET_TELEPORTTOHOUSE));
		assertEquals("not a teleport tablet", 0, usage.getUsed().get(UNIDENTIFIED_FRAGMENT));
		assertTrue(usage.getDoses().isEmpty());
	}

	// ---- Jewellery charges ----

	@Test
	public void jewelleryChargesAreCountedInTheInventory()
	{
		SupplyTracker.Usage usage = total(new int[]{ItemID.RING_OF_DUELING_8, 1, ItemID.NECKLACE_OF_MINIGAMES_5, 2},
			new int[]{ItemID.RING_OF_DUELING_7, 1, ItemID.NECKLACE_OF_MINIGAMES_5, 2},
			new int[]{ItemID.RING_OF_DUELING_7, 1, ItemID.NECKLACE_OF_MINIGAMES_5, 1, ItemID.NECKLACE_OF_MINIGAMES_4, 1});
		assertEquals(1, usage.getUsed().get(ItemID.RING_OF_DUELING_8));
		assertEquals(1, usage.getUsed().get(ItemID.NECKLACE_OF_MINIGAMES_8));
		assertEquals(Integer.valueOf(8), usage.getDoses().get(ItemID.RING_OF_DUELING_8));
		assertEquals(Integer.valueOf(8), usage.getDoses().get(ItemID.NECKLACE_OF_MINIGAMES_8));
		assertEquals(2, usage.getUsed().size());

		Map<Integer, Long> prices = new HashMap<>();
		prices.put(ItemID.RING_OF_DUELING_8, 800L);
		prices.put(ItemID.NECKLACE_OF_MINIGAMES_8, 1600L);
		assertEquals("one eighth of each", 100L + 200L, ItemValuation.total(usage.getUsed(), prices, usage.getDoses()));
	}

	@Test
	public void wornJewelleryIsCountedAndEquippingItNetsToZero()
	{
		// The plugin adds the worn amulet, ring and gloves slots to the same observation as the inventory.
		tick(ItemID.AMULET_OF_GLORY_4, 1, ItemID.SLAYER_RING_8, 1);
		assertTrue("equipping the ring from the inventory", tick(ItemID.AMULET_OF_GLORY_4, 1, ItemID.SLAYER_RING_8, 1)
			.isEmpty());
		SupplyTracker.Usage glory = tick(ItemID.AMULET_OF_GLORY_3, 1, ItemID.SLAYER_RING_8, 1);
		assertEquals("teleporting from the equipment tab", 1, glory.getUsed().get(ItemID.AMULET_OF_GLORY_6));
		assertEquals(Integer.valueOf(6), glory.getDoses().get(ItemID.AMULET_OF_GLORY_6));
		SupplyTracker.Usage ring = tick(ItemID.AMULET_OF_GLORY_3, 1, ItemID.SLAYER_RING_7, 1);
		assertEquals(1, ring.getUsed().get(ItemID.SLAYER_RING_8));
		assertEquals(Integer.valueOf(8), ring.getDoses().get(ItemID.SLAYER_RING_8));
	}

	@Test
	public void theLastChargeCountsOnceWhenTheItemCrumbles()
	{
		SupplyTracker.Usage usage = total(new int[]{ItemID.RING_OF_DUELING_1, 1, ItemID.NECKLACE_OF_PASSAGE_1, 1},
			new int[]{ItemID.NECKLACE_OF_PASSAGE_1, 1},
			new int[]{});
		assertEquals(1, usage.getUsed().get(ItemID.RING_OF_DUELING_8));
		assertEquals(1, usage.getUsed().get(ItemID.NECKLACE_OF_PASSAGE_5));
		assertEquals(2, usage.getUsed().size());
	}

	@Test
	public void theLastChargeCountsOnceWhenTheItemBecomesUncharged()
	{
		SupplyTracker.Usage usage = total(new int[]{ItemID.AMULET_OF_GLORY_1, 1, ItemID.JEWL_BRACELET_OF_COMBAT_1, 1},
			new int[]{ItemID.AMULET_OF_GLORY, 1, ItemID.JEWL_BRACELET_OF_COMBAT_1, 1},
			new int[]{ItemID.AMULET_OF_GLORY, 1, ItemID.JEWL_BRACELET_OF_COMBAT, 1});
		assertEquals(1, usage.getUsed().get(ItemID.AMULET_OF_GLORY_6));
		assertEquals(1, usage.getUsed().get(ItemID.JEWL_BRACELET_OF_COMBAT_6));
		assertEquals("the uncharged amulet is not used", 0, usage.getUsed().get(ItemID.AMULET_OF_GLORY));
		assertEquals(2, usage.getUsed().size());
	}

	@Test
	public void rechargingIsNotAUseAndLaterChargesCountAgain()
	{
		SupplyTracker.Usage usage = total(new int[]{ItemID.AMULET_OF_GLORY, 3},
			// Fountain of Heroes: every uncharged amulet becomes a glory(4).
			new int[]{ItemID.AMULET_OF_GLORY_4, 3},
			new int[]{ItemID.AMULET_OF_GLORY_4, 2, ItemID.AMULET_OF_GLORY_3, 1});
		assertEquals("only the charge used after recharging", 1, usage.getUsed().get(ItemID.AMULET_OF_GLORY_6));
		assertEquals(1, usage.getUsed().size());
		assertTrue("a recharge is not a return", usage.getReturned().isEmpty());
	}

	@Test
	public void eternalJewelleryIsNeverCounted()
	{
		tick(ItemID.AMULET_OF_GLORY_INF, 1, ItemID.SLAYER_RING_ETERNAL, 1);
		assertTrue(tick().isEmpty());
	}

	@Test
	public void trimmedAndImbuedVariantsAreFamiliesOfTheirOwn()
	{
		SupplyTracker.Usage usage = total(
			new int[]{ItemID.AMULET_OF_GLORY_4, 1, ItemID.TRAIL_AMULET_OF_GLORY_4, 1, ItemID.RING_OF_WEALTH_I5, 1},
			new int[]{ItemID.AMULET_OF_GLORY_4, 1, ItemID.TRAIL_AMULET_OF_GLORY_3, 1, ItemID.RING_OF_WEALTH_I4, 1});
		assertEquals(0, usage.getUsed().get(ItemID.AMULET_OF_GLORY_6));
		assertEquals(1, usage.getUsed().get(ItemID.TRAIL_AMULET_OF_GLORY_6));
		assertEquals(1, usage.getUsed().get(ItemID.RING_OF_WEALTH_I5));
		assertEquals(Integer.valueOf(5), usage.getDoses().get(ItemID.RING_OF_WEALTH_I5));
	}

	// ---- Exclusions ----

	@Test
	public void nothingIsCountedWhilePausedOrExcluded()
	{
		tick(ItemID.RING_OF_DUELING_8, 1, ItemID.SLAYER_BAG_OF_SALT, 10, ItemID.POH_TABLET_VARROCKTELEPORT, 5,
			ItemID.SLAYER_SPRAY_PUMP_10, 1);
		assertTrue("paused", supplies.update(SupplyTrackerTest.obs(ItemID.RING_OF_DUELING_7, 1,
			ItemID.SLAYER_BAG_OF_SALT, 9, ItemID.POH_TABLET_VARROCKTELEPORT, 4, ItemID.SLAYER_SPRAY_PUMP_9, 1),
			false, false, Collections.emptySet()).isEmpty());
		assertTrue("bank open: deposited", supplies.update(SupplyTrackerTest.obs(ItemID.SLAYER_SPRAY_PUMP_9, 1),
			true, true, Collections.emptySet()).isEmpty());
		// Nothing from the paused or banked ticks is counted later either.
		assertTrue(tick(ItemID.SLAYER_SPRAY_PUMP_9, 1).isEmpty());
	}

	@Test
	public void droppingIsNotAUse()
	{
		tick(ItemID.RING_OF_DUELING_3, 1, ItemID.SLAYER_ICY_WATER, 5, ItemID.POH_TABLET_VARROCKTELEPORT, 2);
		Set<Integer> dropped = new HashSet<>(Arrays.asList(ItemID.RING_OF_DUELING_3,
			ItemID.SLAYER_ICY_WATER, ItemID.POH_TABLET_VARROCKTELEPORT));
		assertTrue(supplies.update(SupplyTrackerTest.obs(), true, false, dropped).isEmpty());
	}

	@Test
	public void existingCategoriesAreUnchanged()
	{
		SupplyTracker.Usage usage = total(new int[]{ItemID.SHARK, 5, ItemID.RING_OF_DUELING_8, 1},
			new int[]{ItemID.SHARK, 4, ItemID.RING_OF_DUELING_8, 1});
		assertEquals(1, usage.getUsed().get(ItemID.SHARK));
		assertEquals(1, usage.getUsed().size());
		assertNull(usage.getDoses().get(ItemID.SHARK));
	}
}
