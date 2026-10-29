package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/**
 * The pure per-tick supplies diff: categories, potion doses, container moves netting to zero, and exclusions.
 */
public class SupplyTrackerTest
{
	static final int SHARK = 385;
	static final int JUG_OF_WINE = 1993;
	static final int PRAYER_4 = 2434;
	static final int PRAYER_3 = 139;
	static final int PRAYER_2 = 141;
	static final int PRAYER_1 = 143;
	static final int SUPER_COMBAT_3 = 12697;
	static final int SUPER_COMBAT_2 = 12699;
	static final int VIAL = 229;
	static final int NATURE_RUNE = 561;
	static final int DEATH_RUNE = 560;
	static final int RUNE_POUCH = 12791;
	static final int ADAMANT_ARROW = 890;
	static final int BONES = 526;
	static final int GLORY_4 = 1712;

	private static final String[] EAT = {"Eat", null, null, null, "Drop"};
	private static final String[] DRINK = {"Drink", null, null, "Empty", "Drop"};
	private static final String[] NONE = {null, null, null, null, "Drop"};

	/** Test item definitions, classified with the production rules. */
	static final SupplyTracker.Classifier CLASSIFIER = id ->
	{
		switch (id)
		{
			case SHARK:
				return SupplyTracker.classify("Shark", EAT, false, false, false);
			case JUG_OF_WINE:
				return SupplyTracker.classify("Jug of wine", DRINK, false, false, false);
			case PRAYER_4:
				return SupplyTracker.classify("Prayer potion(4)", DRINK, false, false, false);
			case PRAYER_3:
				return SupplyTracker.classify("Prayer potion(3)", DRINK, false, false, false);
			case PRAYER_2:
				return SupplyTracker.classify("Prayer potion(2)", DRINK, false, false, false);
			case PRAYER_1:
				return SupplyTracker.classify("Prayer potion(1)", DRINK, false, false, false);
			case SUPER_COMBAT_3:
				return SupplyTracker.classify("Super combat potion(3)", DRINK, false, false, false);
			case SUPER_COMBAT_2:
				return SupplyTracker.classify("Super combat potion(2)", DRINK, false, false, false);
			case NATURE_RUNE:
				return SupplyTracker.classify("Nature rune", NONE, true, false, false);
			case DEATH_RUNE:
				return SupplyTracker.classify("Death rune", NONE, true, false, false);
			case ADAMANT_ARROW:
				return SupplyTracker.classify("Adamant arrow", NONE, false, true, false);
			case SupplyTracker.CANNONBALL:
				return SupplyTracker.classify("Cannonball", NONE, false, false, true);
			case SupplyTracker.GRANITE_CANNONBALL:
				return SupplyTracker.classify("Granite cannonball", NONE, false, false, true);
			case GLORY_4:
				return SupplyTracker.classify("Amulet of glory(4)", new String[]{null, null, "Rub", null, "Drop"}, false, false, false);
			default:
				// Vials, bones, the rune pouch and anything else: not a supply.
				return SupplyTracker.classify("Other", NONE, false, false, false);
		}
	};

	private SupplyTracker supplies;

	@Before
	public void setUp()
	{
		supplies = new SupplyTracker(CLASSIFIER);
	}

	static SupplyTracker.Observation obs(int... idQty)
	{
		SupplyTracker.Observation o = new SupplyTracker.Observation();
		for (int i = 0; i < idQty.length; i += 2)
		{
			o.add(idQty[i], idQty[i + 1]);
		}
		return o;
	}

	private SupplyTracker.Usage running(SupplyTracker.Observation o)
	{
		return supplies.update(o, true, false, Collections.emptySet());
	}

	private static Set<Integer> ids(int... ids)
	{
		Set<Integer> set = new HashSet<>();
		for (int id : ids)
		{
			set.add(id);
		}
		return set;
	}

	// ---- Classification ----

	@Test
	public void classifiesByNameActionsAndFlags()
	{
		SupplyTracker.ItemInfo prayer = CLASSIFIER.classify(PRAYER_3);
		assertEquals(SupplyTracker.Category.POTION, prayer.getCategory());
		assertEquals("Prayer potion", prayer.getFamily());
		assertEquals(3, prayer.getDoses());
		assertEquals(SupplyTracker.Category.FOOD, CLASSIFIER.classify(SHARK).getCategory());
		assertEquals("a drink without doses is food", SupplyTracker.Category.FOOD, CLASSIFIER.classify(JUG_OF_WINE).getCategory());
		assertEquals("charged jewellery is not a potion", SupplyTracker.Category.OTHER, CLASSIFIER.classify(GLORY_4).getCategory());
		assertEquals(SupplyTracker.Category.OTHER, SupplyTracker.classify("Prayer potion(4)", null, false, false, false).getCategory());
		assertEquals(SupplyTracker.Category.RUNE, CLASSIFIER.classify(NATURE_RUNE).getCategory());
		assertEquals(SupplyTracker.Category.AMMO, CLASSIFIER.classify(ADAMANT_ARROW).getCategory());
		assertEquals(SupplyTracker.Category.CANNONBALL, CLASSIFIER.classify(SupplyTracker.CANNONBALL).getCategory());
		assertEquals(SupplyTracker.Category.OTHER, CLASSIFIER.classify(BONES).getCategory());
		assertEquals("flags take precedence", SupplyTracker.Category.CANNONBALL,
			SupplyTracker.classify("Odd(4)", DRINK, true, true, true).getCategory());
	}

	// ---- Baseline ----

	@Test
	public void firstUpdateOnlyTakesABaseline()
	{
		assertFalse(supplies.hasBaseline());
		assertTrue(running(obs(SHARK, 10)).isEmpty());
		assertTrue(supplies.hasBaseline());
		assertEquals(1, running(obs(SHARK, 9)).getUsed().get(SHARK));
	}

	@Test
	public void resetForgetsTheBaseline()
	{
		running(obs(SHARK, 10));
		supplies.reset();
		assertTrue("fresh baseline after reset", running(obs(SHARK, 2)).isEmpty());
		assertEquals(1, running(obs(SHARK, 1)).getUsed().get(SHARK));
	}

	@Test
	public void nothingCountsWhileNotRunningButTheBaselineMoves()
	{
		running(obs(SHARK, 10));
		assertTrue(supplies.update(obs(SHARK, 5), false, false, Collections.emptySet()).isEmpty());
		// Resuming: only what changes from the paused tick on counts.
		assertEquals(1, running(obs(SHARK, 4)).getUsed().get(SHARK));
	}

	// ---- Food ----

	@Test
	public void foodEatenIsCountedAndIncreasesAreIgnored()
	{
		running(obs(SHARK, 10, JUG_OF_WINE, 2, BONES, 5));
		SupplyTracker.Usage usage = running(obs(SHARK, 9, JUG_OF_WINE, 1, BONES, 2));
		assertEquals(1, usage.getUsed().get(SHARK));
		assertEquals(1, usage.getUsed().get(JUG_OF_WINE));
		assertEquals("buried bones are not a supply", 0, usage.getUsed().get(BONES));
		assertTrue("no doses for food", usage.getDoses().isEmpty());

		assertTrue("loot pickup", running(obs(SHARK, 12, JUG_OF_WINE, 1)).isEmpty());
		assertEquals(3, running(obs(SHARK, 9, JUG_OF_WINE, 1)).getUsed().get(SHARK));
	}

	// ---- Potions ----

	@Test
	public void potionDrinksCountDosesUnderTheHighestDoseVariant()
	{
		running(obs(PRAYER_4, 2));
		SupplyTracker.Usage usage = running(obs(PRAYER_4, 1, PRAYER_3, 1));
		assertEquals("one dose", 1, usage.getUsed().get(PRAYER_4));
		assertEquals(Integer.valueOf(4), usage.getDoses().get(PRAYER_4));
		assertEquals(1, usage.getUsed().size());

		running(obs(PRAYER_4, 1, PRAYER_2, 1));
		running(obs(PRAYER_4, 1, PRAYER_1, 1));
		usage = running(obs(PRAYER_4, 1, VIAL, 1));
		assertEquals("the last dose leaves a vial", 1, usage.getUsed().get(PRAYER_4));
		assertEquals(0, usage.getUsed().get(VIAL));
	}

	@Test
	public void decantingAndCombiningNetToZero()
	{
		running(obs(PRAYER_3, 1, PRAYER_1, 1));
		assertTrue("3 + 1 into a 4 and a vial", running(obs(PRAYER_4, 1, VIAL, 1)).isEmpty());
		assertTrue("4 into 2 + 2", running(obs(PRAYER_2, 2, VIAL, 0)).isEmpty());
	}

	@Test
	public void familyIsKeyedByTheHighestDoseSeenSoFar()
	{
		running(obs(SUPER_COMBAT_3, 1));
		SupplyTracker.Usage usage = running(obs(SUPER_COMBAT_2, 1));
		assertEquals(1, usage.getUsed().get(SUPER_COMBAT_3));
		assertEquals(Integer.valueOf(3), usage.getDoses().get(SUPER_COMBAT_3));
	}

	@Test
	public void differentPotionFamiliesAreSeparate()
	{
		running(obs(PRAYER_4, 1, SUPER_COMBAT_3, 1));
		SupplyTracker.Usage usage = running(obs(PRAYER_3, 1, SUPER_COMBAT_2, 1));
		assertEquals(1, usage.getUsed().get(PRAYER_4));
		assertEquals(1, usage.getUsed().get(SUPER_COMBAT_3));
	}

	// ---- Runes ----

	@Test
	public void runesCastFromInventoryAndPouchAreCounted()
	{
		// Inventory and pouch together: 100 nature runes and 50 death runes in total.
		running(obs(RUNE_POUCH, 1, NATURE_RUNE, 60, NATURE_RUNE, 40, DEATH_RUNE, 50));
		SupplyTracker.Usage usage = running(obs(RUNE_POUCH, 1, NATURE_RUNE, 60, NATURE_RUNE, 39, DEATH_RUNE, 48));
		assertEquals(1, usage.getUsed().get(NATURE_RUNE));
		assertEquals(2, usage.getUsed().get(DEATH_RUNE));
	}

	@Test
	public void movingRunesIntoOrOutOfThePouchNetsToZero()
	{
		// Inventory 100 nature runes, pouch empty.
		running(obs(RUNE_POUCH, 1, NATURE_RUNE, 100));
		// Fill: inventory 0, pouch 100.
		assertTrue(running(obs(RUNE_POUCH, 1).add(NATURE_RUNE, 100)).isEmpty());
		// Partly empty: inventory 30, pouch 70.
		assertTrue(running(obs(RUNE_POUCH, 1, NATURE_RUNE, 30, NATURE_RUNE, 70)).isEmpty());
	}

	// ---- Ammo ----

	@Test
	public void equippingAmmoNetsToZeroAndFiringCounts()
	{
		running(obs(ADAMANT_ARROW, 500));
		assertTrue("equipped from the inventory", running(obs().add(ADAMANT_ARROW, 500)).isEmpty());
		assertEquals(3, running(obs(ADAMANT_ARROW, 497)).getUsed().get(ADAMANT_ARROW));
		assertTrue("unequipped", running(obs(ADAMANT_ARROW, 497)).isEmpty());
	}

	@Test
	public void pickedUpAmmoIsReportedAsReturned()
	{
		running(obs(ADAMANT_ARROW, 500));
		running(obs(ADAMANT_ARROW, 490));
		SupplyTracker.Usage usage = running(obs(ADAMANT_ARROW, 496));
		assertTrue(usage.getUsed().isEmpty());
		assertEquals(6, usage.getReturned().get(ADAMANT_ARROW));
	}

	@Test
	public void foodPickupIsNotReturned()
	{
		running(obs(SHARK, 5));
		assertTrue(running(obs(SHARK, 6)).isEmpty());
	}

	// ---- Cannon ----

	@Test
	public void cannonLoadFireAndPickUp()
	{
		running(obs(SupplyTracker.CANNONBALL, 100));
		// Load 30 into the cannon.
		assertTrue("loading", running(obs(SupplyTracker.CANNONBALL, 70).setCannonBalls(30)).isEmpty());
		// Fire 5.
		SupplyTracker.Usage fired = running(obs(SupplyTracker.CANNONBALL, 70).setCannonBalls(25));
		assertEquals(5, fired.getUsed().get(SupplyTracker.CANNONBALL));
		// Pick the cannon up: its 25 go back to the inventory.
		assertTrue("pickup", running(obs(SupplyTracker.CANNONBALL, 95).setCannonBalls(0)).isEmpty());
	}

	@Test
	public void cannonLoadSplitOverTwoTicksIsOffsetByReturn()
	{
		running(obs(SupplyTracker.CANNONBALL, 100));
		SupplyTracker.Usage first = running(obs(SupplyTracker.CANNONBALL, 70));
		assertEquals(30, first.getUsed().get(SupplyTracker.CANNONBALL));
		SupplyTracker.Usage second = running(obs(SupplyTracker.CANNONBALL, 70).setCannonBalls(30));
		assertEquals("the session subtracts this back", 30, second.getReturned().get(SupplyTracker.CANNONBALL));
	}

	@Test
	public void graniteCannonballsAreAttributedFromWhatWasLoaded()
	{
		running(obs(SupplyTracker.GRANITE_CANNONBALL, 50, SupplyTracker.CANNONBALL, 10));
		assertTrue(running(obs(SupplyTracker.GRANITE_CANNONBALL, 20, SupplyTracker.CANNONBALL, 10).setCannonBalls(30)).isEmpty());
		assertEquals(SupplyTracker.GRANITE_CANNONBALL, supplies.getCannonBallId());
		SupplyTracker.Usage fired = running(obs(SupplyTracker.GRANITE_CANNONBALL, 20, SupplyTracker.CANNONBALL, 10).setCannonBalls(28));
		assertEquals(2, fired.getUsed().get(SupplyTracker.GRANITE_CANNONBALL));
		assertEquals(0, fired.getUsed().get(SupplyTracker.CANNONBALL));
	}

	private static final int G = SupplyTracker.GRANITE_CANNONBALL;
	private static final int R = SupplyTracker.CANNONBALL;

	/** Tracking starts with 30 granite cannonballs already loaded: the type is a guess (regular). */
	private void startWithLoadedGraniteCannon()
	{
		running(obs(G, 70).setCannonBalls(30));
		assertEquals("guessed", R, supplies.getCannonBallId());
	}

	@Test
	public void aGraniteCannonLoadedBeforeTrackingIsPickedUpWithoutCountingAnything()
	{
		startWithLoadedGraniteCannon();
		// the pickup returns granite, so the cannon held granite; nothing was used.
		assertTrue("pickup", running(obs(G, 100)).isEmpty());
		assertEquals(G, supplies.getCannonBallId());
	}

	@Test
	public void cannonballsFiredFromAGuessedTypeMoveToTheTypePickedUp()
	{
		startWithLoadedGraniteCannon();
		SupplyTracker.Usage fired = running(obs(G, 70).setCannonBalls(26));
		assertEquals("counted under the guess until the type shows", 4, fired.getUsed().get(R));

		SupplyTracker.Usage pickup = running(obs(G, 96));
		assertEquals(4, pickup.getUsed().get(G));
		assertEquals("offsets the guess", 4, pickup.getReturned().get(R));
		assertEquals(0, pickup.getReturned().get(G));
		assertEquals(0, pickup.getUsed().get(R));
	}

	@Test
	public void aGuessedPickupSplitOverTwoTicksIsCorrectedOnTheSecond()
	{
		startWithLoadedGraniteCannon();
		running(obs(G, 70).setCannonBalls(26));
		// The varp goes to 0 a tick before the cannonballs reach the inventory.
		SupplyTracker.Usage emptied = running(obs(G, 70));
		assertEquals(26, emptied.getUsed().get(R));
		SupplyTracker.Usage arrived = running(obs(G, 96));
		// 4 fired and 26 back: the session nets granite to 4 and regular to 0.
		assertEquals(30, arrived.getUsed().get(G));
		assertEquals(30, arrived.getReturned().get(R));
		assertEquals(26, arrived.getReturned().get(G));
		assertEquals(G, supplies.getCannonBallId());
	}

	@Test
	public void aGuessedTypeIsCorrectedByAFurtherLoadOfTheOtherType()
	{
		startWithLoadedGraniteCannon();
		SupplyTracker.Usage fired = running(obs(G, 70).setCannonBalls(28));
		assertEquals(2, fired.getUsed().get(R));
		SupplyTracker.Usage loaded = running(obs(G, 50).setCannonBalls(48));
		assertEquals(2, loaded.getUsed().get(G));
		assertEquals(2, loaded.getReturned().get(R));
		assertEquals(0, loaded.getUsed().get(R));
		assertTrue("pickup", running(obs(G, 98)).isEmpty());
	}

	@Test
	public void aRightGuessIsKeptAndCountsAsBefore()
	{
		running(obs(R, 70).setCannonBalls(30));
		assertEquals(4, running(obs(R, 70).setCannonBalls(26)).getUsed().get(R));
		assertTrue("pickup", running(obs(R, 96)).isEmpty());
		assertEquals(R, supplies.getCannonBallId());
	}

	@Test
	public void aGuessedCannonEmptiedByFiringKeepsItsCountWhenAnotherTypeIsLoadedLater()
	{
		startWithLoadedGraniteCannon();
		assertEquals(30, running(obs(G, 70)).getUsed().get(R));
		running(obs(G, 70));
		running(obs(G, 70));
		// A new load shows granite, but says nothing about what the emptied cannon held.
		SupplyTracker.Usage loaded = running(obs(G, 40).setCannonBalls(30));
		assertTrue(loaded.isEmpty());
		assertEquals(G, supplies.getCannonBallId());
	}

	@Test
	public void graniteLootedAsARegularGuessedCannonEmptiesDoesNotChangeItsType()
	{
		// a regular load, tracked as a guess, empties without its cannonballs coming back (fired or decayed),
		// and 3 granite cannonballs are looted on that tick: fewer than the load, so they are not its type.
		running(obs(R, 70).setCannonBalls(10));
		SupplyTracker.Usage emptied = running(obs(R, 70, G, 3));
		assertEquals(10, emptied.getUsed().get(R));
		assertEquals("loot is no granite cost", 0, emptied.getUsed().get(G));
		assertEquals(0, emptied.getReturned().get(R));
		assertEquals(R, supplies.getCannonBallId());
		// Nor on the next tick, where the pickup split over two ticks would show.
		assertTrue(running(obs(R, 70, G, 3)).isEmpty());
		assertEquals(R, supplies.getCannonBallId());
		// A later granite load shows the new type, without moving the regular cannonballs already fired.
		assertTrue(running(obs(R, 70, G, 3)).isEmpty());
		assertTrue(running(obs(R, 70).setCannonBalls(3)).isEmpty());
		assertEquals(G, supplies.getCannonBallId());
	}

	@Test
	public void aGraniteLoadLargerThanTheLootStillResolvesOnPickup()
	{
		startWithLoadedGraniteCannon();
		running(obs(G, 70).setCannonBalls(26));
		// Picked up with 3 regular cannonballs looted on the same tick: the 26 granite back win.
		SupplyTracker.Usage pickup = running(obs(G, 96, R, 3));
		assertEquals(G, supplies.getCannonBallId());
		assertEquals(4, pickup.getUsed().get(G));
		// The 4 guessed regular cannonballs offset, plus the 3 looted ones, which return like any cannonball pickup.
		assertEquals(7, pickup.getReturned().get(R));
		assertEquals(0, pickup.getReturned().get(G));
	}

	/** What the {@code usages} net to for {@code itemId}: used minus returned, as a session adds them up. */
	private static long net(int itemId, SupplyTracker.Usage... usages)
	{
		long net = 0;
		for (SupplyTracker.Usage usage : usages)
		{
			net += usage.getUsed().get(itemId) - usage.getReturned().get(itemId);
		}
		return net;
	}

	@Test
	public void aGraniteCannonFiringOnItsPickupTickStillResolves()
	{
		startWithLoadedGraniteCannon();
		SupplyTracker.Usage fired = running(obs(G, 70).setCannonBalls(26));
		// The cannon fires once more on the pickup tick, so only 25 of the 26 it held come back: more than half.
		SupplyTracker.Usage pickup = running(obs(G, 95));
		assertEquals(G, supplies.getCannonBallId());
		assertEquals(5, net(G, fired, pickup));
		assertEquals(0, net(R, fired, pickup));
	}

	@Test
	public void aGraniteCannonFiringBeforeAPickupSplitOverTwoTicksStillResolves()
	{
		startWithLoadedGraniteCannon();
		SupplyTracker.Usage fired = running(obs(G, 70).setCannonBalls(26));
		// The varp goes to 0 after one more shot, a tick before the 25 left reach the inventory.
		SupplyTracker.Usage emptied = running(obs(G, 70));
		SupplyTracker.Usage arrived = running(obs(G, 95));
		assertEquals(G, supplies.getCannonBallId());
		assertEquals(5, net(G, fired, emptied, arrived));
		assertEquals(0, net(R, fired, emptied, arrived));
	}

	@Test
	public void aRightGuessCountsTheShotOnItsPickupTick()
	{
		running(obs(R, 70).setCannonBalls(30));
		SupplyTracker.Usage fired = running(obs(R, 70).setCannonBalls(26));
		SupplyTracker.Usage pickup = running(obs(R, 95));
		assertEquals(R, supplies.getCannonBallId());
		assertEquals(5, net(R, fired, pickup));
		assertEquals(0, net(G, fired, pickup));

		// The same, with the pickup split over two ticks.
		setUp();
		running(obs(R, 70).setCannonBalls(30));
		fired = running(obs(R, 70).setCannonBalls(26));
		SupplyTracker.Usage emptied = running(obs(R, 70));
		SupplyTracker.Usage arrived = running(obs(R, 95));
		assertEquals(R, supplies.getCannonBallId());
		assertEquals(5, net(R, fired, emptied, arrived));
		assertEquals(0, net(G, fired, emptied, arrived));
	}

	@Test
	public void aResetDropsTheGuessedCountSoAnotherSessionIsNotCorrected()
	{
		startWithLoadedGraniteCannon();
		running(obs(G, 70).setCannonBalls(26));
		supplies.reset();
		running(obs(G, 70).setCannonBalls(26));
		SupplyTracker.Usage pickup = running(obs(G, 96));
		assertTrue(pickup.isEmpty());
		assertEquals(G, supplies.getCannonBallId());
	}

	// ---- Exclusions ----

	@Test
	public void droppedItemsAreNotCounted()
	{
		running(obs(SHARK, 10, NATURE_RUNE, 100));
		SupplyTracker.Usage usage = supplies.update(obs(SHARK, 8, NATURE_RUNE, 99), true, false, ids(SHARK));
		assertEquals(0, usage.getUsed().get(SHARK));
		assertEquals("other items still count", 1, usage.getUsed().get(NATURE_RUNE));
		// The baseline moved past the drop.
		assertTrue(running(obs(SHARK, 8, NATURE_RUNE, 99)).isEmpty());
	}

	@Test
	public void ignoredItemsCountNeitherWay()
	{
		running(obs(ADAMANT_ARROW, 500, NATURE_RUNE, 100));
		// Loaded into a weapon: not used.
		SupplyTracker.Usage usage = supplies.update(obs(ADAMANT_ARROW, 400, NATURE_RUNE, 99), true, false,
			Collections.emptySet(), ids(ADAMANT_ARROW));
		assertEquals(0, usage.getUsed().get(ADAMANT_ARROW));
		assertEquals("other items still count", 1, usage.getUsed().get(NATURE_RUNE));
		// Unloaded again: not returned.
		usage = supplies.update(obs(ADAMANT_ARROW, 500, NATURE_RUNE, 99), true, false, Collections.emptySet(),
			ids(ADAMANT_ARROW));
		assertTrue(usage.isEmpty());
		// The baseline moved past both.
		assertTrue(running(obs(ADAMANT_ARROW, 500, NATURE_RUNE, 99)).isEmpty());
	}

	@Test
	public void droppingAPotionExcludesItsFamily()
	{
		running(obs(PRAYER_4, 1, PRAYER_3, 1));
		assertTrue(supplies.update(obs(PRAYER_4, 1), true, false, ids(PRAYER_3)).isEmpty());
	}

	@Test
	public void excludedTicksCountNothingAndMoveTheBaseline()
	{
		running(obs(SHARK, 10, PRAYER_4, 4));
		// Bank open: deposit everything, withdraw fresh supplies.
		assertTrue(supplies.update(obs(SHARK, 2), true, true, Collections.emptySet()).isEmpty());
		assertTrue(supplies.update(obs(SHARK, 20, PRAYER_4, 8), true, true, Collections.emptySet()).isEmpty());
		// After the bank closed only real use counts.
		SupplyTracker.Usage usage = running(obs(SHARK, 19, PRAYER_4, 8));
		assertEquals(1, usage.getUsed().get(SHARK));
		assertEquals(0, usage.getUsed().get(PRAYER_4));
	}

	@Test
	public void usageIsACopy()
	{
		running(obs(SHARK, 10));
		SupplyTracker.Usage usage = running(obs(SHARK, 9));
		usage.getUsed().add(SHARK, 100);
		usage.getDoses().put(SHARK, 4);
		assertEquals(1, usage.getUsed().get(SHARK));
		assertNull(usage.getDoses().get(SHARK));
	}

	@Test
	public void classifierFailureMeansNotASupply()
	{
		Map<Integer, Integer> calls = new HashMap<>();
		SupplyTracker failing = new SupplyTracker(id ->
		{
			calls.merge(id, 1, Integer::sum);
			throw new IllegalStateException("not loaded");
		});
		failing.update(obs(SHARK, 10), true, false, Collections.emptySet());
		assertTrue(failing.update(obs(SHARK, 9), true, false, Collections.emptySet()).isEmpty());
		assertEquals("a failed classification is not cached", Integer.valueOf(2), calls.get(SHARK));
	}

	@Test
	public void unavailableClassificationIsRetriedLater()
	{
		// Item stats load asynchronously: ammo classified before they arrive must be counted once they have.
		final boolean[] loaded = {false};
		SupplyTracker tracker = new SupplyTracker(id -> loaded[0]
			? SupplyTracker.classify("Rune arrow", new String[]{null, "Wield", null, null, "Drop"}, false, true, false)
			: null);
		final int runeArrow = 892;
		tracker.update(obs(runeArrow, 100), true, false, Collections.emptySet());
		assertTrue(tracker.update(obs(runeArrow, 99), true, false, Collections.emptySet()).isEmpty());
		loaded[0] = true;
		// The first tick after the data arrives becomes the item's baseline; use is counted from then on.
		tracker.update(obs(runeArrow, 98), true, false, Collections.emptySet());
		assertEquals(1, tracker.update(obs(runeArrow, 97), true, false, Collections.emptySet())
			.getUsed().get(runeArrow));
	}
}
