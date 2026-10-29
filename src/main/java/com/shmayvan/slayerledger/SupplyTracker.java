package com.shmayvan.slayerledger;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.gameval.ItemID;

/**
 * Pure per-tick supplies diff. The plugin reads the player's item containers once per game tick into an
 * {@link Observation} (inventory, worn ammo, weapon, amulet, ring and gloves slots, Dizana's quiver, rune pouch, and the
 * cannon's loaded ammo); this class compares it with the previous tick and reports what was used. No RuneLite dependencies; item
 * facts come from a {@link Classifier} the plugin backs with {@code ItemComposition} on the client thread.
 *
 * <p>All tracked containers are summed into one virtual container before diffing, so moving items between them nets
 * to zero: filling or emptying the rune pouch, equipping ammo from the inventory, loading the quiver, and loading or
 * picking up the cannon. Only these categories are tracked; every other item is ignored:</p>
 * <ul>
 *     <li>{@link Category#FOOD}: items with an "Eat" or "Drink" inventory action that are not potions. Food eaten in
 *     parts (pies, pizzas and cakes, see {@link #MULTI_BITE_FOOD}) is tracked in bites per food, like potion doses:
 *     eating a whole pizza (2 to 1 bite, then 1 to 0) uses two bites, which are worth one pizza.</li>
 *     <li>{@link Category#POTION}: items named {@code Name(n)} with a "Drink" action. Tracked in doses per family
 *     ({@code Name}), so a drink (4 to 3) uses one dose and decanting, which keeps the family's total doses, nets to
 *     zero.</li>
 *     <li>{@link Category#RUNE}, {@link Category#AMMO} and {@link Category#CANNONBALL}, as decided by the plugin.</li>
 *     <li>{@link Category#SLAYER_CONSUMABLE}: bags of salt and ice coolers ({@link #SLAYER_CONSUMABLES}), one per item
 *     used.</li>
 *     <li>{@link Category#TELEPORT_TABLET}: items with a "Break" inventory action whose name contains "teleport" (for
 *     example "Varrock teleport", "Teleport to house" or "Salve graveyard teleport"), one per tablet broken.</li>
 *     <li>{@link Category#CHARGES}: teleport jewellery and the fungicide spray ({@link #CHARGED_ITEMS}), tracked in
 *     charges per item type like potion doses and recorded under the item's highest-charge variant. Using a charge (8
 *     to 7) uses one charge, and so does the last one, whether the item then crumbles (ring of dueling, games
 *     necklace) or becomes an uncharged variant, which is not a supply (amulet of glory, "Fungicide spray 0").
 *     Recharging (the Fountain of Heroes, refilling the spray) is an increase, so it is ignored. The refill item
 *     ("Fungicide") is not a supply, so a spray's cost is counted once, per charge sprayed.</li>
 * </ul>
 *
 * <p>A net decrease is a use, except for items dropped on this tick (see {@link #update}). A net increase is ignored
 * (it is loot or a withdrawal), except that an increase of ammo or cannonballs is reported as
 * {@link Usage#getReturned() returned}: picked-up ammo, or cannonballs back from the cannon, offset what was counted as
 * used before (the session never lets that go below zero).</p>
 *
 * <p>The cannon's loaded ammo is counted as the cannonball type that left the inventory when it was loaded. When
 * tracking starts with the cannon already loaded, the type is unknown and regular cannonballs are assumed until a load
 * or pickup shows it. If the cannon's pickup or unload (or a further load) shows the other type, the assumption is
 * corrected: its load moves to that type, so picking up a cannon that has not fired counts nothing, and cannonballs
 * fired since the last {@link #reset()} under the assumed type move to that type too. A pickup or unload shows
 * the type only when more than half of the cannon's last seen load comes back as it, so shots fired on the pickup tick
 * are allowed for, while fewer cannonballs looted as the cannon empties are not taken for its type.</p>
 *
 * <p>Not thread-safe; the plugin uses it on the client thread only.</p>
 */
final class SupplyTracker
{
	/** {@code net.runelite.api.gameval.ItemID.MCANNONBALL} (1.13.3), the default for the cannon's loaded ammo. */
	static final int CANNONBALL = 2;
	/** {@code net.runelite.api.gameval.ItemID.GRANITE_CANNONBALL} (1.13.3). */
	static final int GRANITE_CANNONBALL = 21728;

	/** Potion names end in the dose count, for example "Prayer potion(4)". */
	private static final Pattern DOSE_NAME = Pattern.compile("^(.+)\\((\\d)\\)$");

	/**
	 * Food eaten in parts, by the id of each stage: {full item id, bites left in this stage, bites in a full item}. The
	 * ids are the RuneLite 1.13.4 {@code net.runelite.api.gameval.ItemID} constants (unnoted; noted items are never
	 * supplies). What is left after the last bite of a pie (the pie dish) has no "Eat" action, so it is not a supply.
	 */
	static final Map<Integer, int[]> MULTI_BITE_FOOD = multiBiteFood();

	private static Map<Integer, int[]> multiBiteFood()
	{
		final Map<Integer, int[]> food = new HashMap<>();
		// Pizzas: full, then 1/2.
		addStages(food, ItemID.PLAIN_PIZZA, ItemID.HALF_PLAIN_PIZZA);
		addStages(food, ItemID.MEAT_PIZZA, ItemID.HALF_MEAT_PIZZA);
		addStages(food, ItemID.ANCHOVIE_PIZZA, ItemID.HALF_ANCHOVIE_PIZZA);
		addStages(food, ItemID.PINEAPPLE_PIZZA, ItemID.HALF_PINEAPPLE_PIZZA);
		// Pies: full, then half (then the pie dish, which is not food).
		addStages(food, ItemID.REDBERRY_PIE, ItemID.HALF_A_REDBERRY_PIE);
		addStages(food, ItemID.MEAT_PIE, ItemID.HALF_A_MEAT_PIE);
		addStages(food, ItemID.APPLE_PIE, ItemID.HALF_AN_APPLE_PIE);
		addStages(food, ItemID.GARDEN_PIE, ItemID.HALF_GARDEN_PIE);
		addStages(food, ItemID.FISH_PIE, ItemID.HALF_FISH_PIE);
		addStages(food, ItemID.BOTANICAL_PIE, ItemID.HALF_BOTANICAL_PIE);
		addStages(food, ItemID.MUSHROOM_PIE, ItemID.HALF_MUSHROOM_PIE);
		addStages(food, ItemID.ADMIRAL_PIE, ItemID.HALF_ADMIRAL_PIE);
		addStages(food, ItemID.DRAGONFRUIT_PIE, ItemID.HALF_DRAGONFRUIT_PIE);
		addStages(food, ItemID.WILD_PIE, ItemID.HALF_WILD_PIE);
		addStages(food, ItemID.SUMMER_PIE, ItemID.HALF_SUMMER_PIE);
		// Cakes: full, then 2/3, then a slice.
		addStages(food, ItemID.CAKE, ItemID.PARTIAL_CAKE, ItemID.CAKE_SLICE);
		addStages(food, ItemID.CHOCOLATE_CAKE, ItemID.PARTIAL_CHOCOLATE_CAKE, ItemID.CHOCOLATE_SLICE);
		return Collections.unmodifiableMap(food);
	}

	/**
	 * Items used per charge, by the id of each charged variant: {highest-charge item id, charges left, charges in the
	 * highest-charge item}. One family per item type; the trimmed glory and the imbued ring of wealth are families of
	 * their own. Uncharged variants ("Amulet of glory", "Skills necklace", "Fungicide spray 0" and so on) are not
	 * listed, so they are not supplies, and neither are items that never run out (amulet of eternal glory, slayer ring
	 * (eternal)). The ids are the RuneLite 1.13.4 {@code net.runelite.api.gameval.ItemID} constants, with the charges
	 * core {@code ItemWithCharge} gives them (its TELEPORT and FUNGICIDE_SPRAY types).
	 */
	static final Map<Integer, int[]> CHARGED_ITEMS = chargedItems();

	private static Map<Integer, int[]> chargedItems()
	{
		final Map<Integer, int[]> items = new HashMap<>();
		addStages(items, ItemID.RING_OF_DUELING_8, ItemID.RING_OF_DUELING_7, ItemID.RING_OF_DUELING_6,
			ItemID.RING_OF_DUELING_5, ItemID.RING_OF_DUELING_4, ItemID.RING_OF_DUELING_3, ItemID.RING_OF_DUELING_2,
			ItemID.RING_OF_DUELING_1);
		// Games necklace.
		addStages(items, ItemID.NECKLACE_OF_MINIGAMES_8, ItemID.NECKLACE_OF_MINIGAMES_7,
			ItemID.NECKLACE_OF_MINIGAMES_6, ItemID.NECKLACE_OF_MINIGAMES_5, ItemID.NECKLACE_OF_MINIGAMES_4,
			ItemID.NECKLACE_OF_MINIGAMES_3, ItemID.NECKLACE_OF_MINIGAMES_2, ItemID.NECKLACE_OF_MINIGAMES_1);
		addStages(items, ItemID.AMULET_OF_GLORY_6, ItemID.AMULET_OF_GLORY_5, ItemID.AMULET_OF_GLORY_4,
			ItemID.AMULET_OF_GLORY_3, ItemID.AMULET_OF_GLORY_2, ItemID.AMULET_OF_GLORY_1);
		// Amulet of glory (t).
		addStages(items, ItemID.TRAIL_AMULET_OF_GLORY_6, ItemID.TRAIL_AMULET_OF_GLORY_5,
			ItemID.TRAIL_AMULET_OF_GLORY_4, ItemID.TRAIL_AMULET_OF_GLORY_3, ItemID.TRAIL_AMULET_OF_GLORY_2,
			ItemID.TRAIL_AMULET_OF_GLORY_1);
		// Skills necklace.
		addStages(items, ItemID.JEWL_NECKLACE_OF_SKILLS_6, ItemID.JEWL_NECKLACE_OF_SKILLS_5,
			ItemID.JEWL_NECKLACE_OF_SKILLS_4, ItemID.JEWL_NECKLACE_OF_SKILLS_3, ItemID.JEWL_NECKLACE_OF_SKILLS_2,
			ItemID.JEWL_NECKLACE_OF_SKILLS_1);
		// Combat bracelet.
		addStages(items, ItemID.JEWL_BRACELET_OF_COMBAT_6, ItemID.JEWL_BRACELET_OF_COMBAT_5,
			ItemID.JEWL_BRACELET_OF_COMBAT_4, ItemID.JEWL_BRACELET_OF_COMBAT_3, ItemID.JEWL_BRACELET_OF_COMBAT_2,
			ItemID.JEWL_BRACELET_OF_COMBAT_1);
		addStages(items, ItemID.RING_OF_WEALTH_5, ItemID.RING_OF_WEALTH_4, ItemID.RING_OF_WEALTH_3,
			ItemID.RING_OF_WEALTH_2, ItemID.RING_OF_WEALTH_1);
		// Ring of wealth (i).
		addStages(items, ItemID.RING_OF_WEALTH_I5, ItemID.RING_OF_WEALTH_I4, ItemID.RING_OF_WEALTH_I3,
			ItemID.RING_OF_WEALTH_I2, ItemID.RING_OF_WEALTH_I1);
		addStages(items, ItemID.NECKLACE_OF_PASSAGE_5, ItemID.NECKLACE_OF_PASSAGE_4, ItemID.NECKLACE_OF_PASSAGE_3,
			ItemID.NECKLACE_OF_PASSAGE_2, ItemID.NECKLACE_OF_PASSAGE_1);
		// Digsite pendant.
		addStages(items, ItemID.NECKLACE_OF_DIGSITE_5, ItemID.NECKLACE_OF_DIGSITE_4, ItemID.NECKLACE_OF_DIGSITE_3,
			ItemID.NECKLACE_OF_DIGSITE_2, ItemID.NECKLACE_OF_DIGSITE_1);
		addStages(items, ItemID.BURNING_AMULET_5, ItemID.BURNING_AMULET_4, ItemID.BURNING_AMULET_3,
			ItemID.BURNING_AMULET_2, ItemID.BURNING_AMULET_1);
		addStages(items, ItemID.SLAYER_RING_8, ItemID.SLAYER_RING_7, ItemID.SLAYER_RING_6, ItemID.SLAYER_RING_5,
			ItemID.SLAYER_RING_4, ItemID.SLAYER_RING_3, ItemID.SLAYER_RING_2, ItemID.SLAYER_RING_1);
		addStages(items, ItemID.RING_OF_RETURNING_5, ItemID.RING_OF_RETURNING_4, ItemID.RING_OF_RETURNING_3,
			ItemID.RING_OF_RETURNING_2, ItemID.RING_OF_RETURNING_1);
		// Fungicide spray 10 down to 1 ("Fungicide spray 0" is empty).
		addStages(items, ItemID.SLAYER_SPRAY_PUMP_10, ItemID.SLAYER_SPRAY_PUMP_9, ItemID.SLAYER_SPRAY_PUMP_8,
			ItemID.SLAYER_SPRAY_PUMP_7, ItemID.SLAYER_SPRAY_PUMP_6, ItemID.SLAYER_SPRAY_PUMP_5,
			ItemID.SLAYER_SPRAY_PUMP_4, ItemID.SLAYER_SPRAY_PUMP_3, ItemID.SLAYER_SPRAY_PUMP_2,
			ItemID.SLAYER_SPRAY_PUMP_1);
		return Collections.unmodifiableMap(items);
	}

	/**
	 * Slayer items used up one per use: the bag of salt ({@code SLAYER_BAG_OF_SALT}) and the ice cooler
	 * ({@code SLAYER_ICY_WATER}), RuneLite 1.13.4 {@code net.runelite.api.gameval.ItemID} constants. Tools that are not
	 * used up, such as the rock hammer, are not supplies.
	 */
	static final Set<Integer> SLAYER_CONSUMABLES = Collections.unmodifiableSet(
		new HashSet<>(Arrays.asList(ItemID.SLAYER_BAG_OF_SALT, ItemID.SLAYER_ICY_WATER)));

	/** Adds an item's stages, from the full item down to the last bite (or charge). */
	private static void addStages(Map<Integer, int[]> food, int... stages)
	{
		for (int i = 0; i < stages.length; i++)
		{
			food.put(stages[i], new int[]{stages[0], stages.length - i, stages.length});
		}
	}

	/** How an item is tracked. */
	enum Category
	{
		FOOD,
		POTION,
		RUNE,
		AMMO,
		CANNONBALL,
		/** Bags of salt and ice coolers; see {@link #SLAYER_CONSUMABLES}. */
		SLAYER_CONSUMABLE,
		/** Teleport tablets: a "Break" action and "teleport" in the name. */
		TELEPORT_TABLET,
		/** Teleport jewellery and the fungicide spray, in charges; see {@link #CHARGED_ITEMS}. */
		CHARGES,
		/** Not a supply; ignored. */
		OTHER
	}

	/** What the tracker needs to know about one item id. */
	static final class ItemInfo
	{
		static final ItemInfo OTHER = new ItemInfo(Category.OTHER, null, 0);

		private final Category category;
		private final String family;
		private final int doses;
		/**
		 * Item a multi-bite food's bites (or a charged item's charges) are recorded under, or -1 (potions use their
		 * highest-dose variant seen).
		 */
		private final int fullItemId;
		/** Bites (or charges) in one {@link #fullItemId} item, or 0. */
		private final int dosesPerItem;

		ItemInfo(Category category, String family, int doses)
		{
			this(category, family, doses, -1, 0);
		}

		private ItemInfo(Category category, String family, int doses, int fullItemId, int dosesPerItem)
		{
			this.category = Objects.requireNonNull(category);
			this.family = family;
			this.doses = doses;
			this.fullItemId = fullItemId;
			this.dosesPerItem = dosesPerItem;
		}

		static ItemInfo of(Category category)
		{
			return new ItemInfo(category, null, 0);
		}

		static ItemInfo potion(String family, int doses)
		{
			return new ItemInfo(Category.POTION, family, doses);
		}

		/**
		 * One stage of a food eaten in parts: food tracked in bites, in one family per full item, and recorded under the
		 * full item with {@code bitesPerItem} bites per item.
		 */
		static ItemInfo multiBiteFood(int fullItemId, int bitesLeft, int bitesPerItem)
		{
			return new ItemInfo(Category.FOOD, "bites:" + fullItemId, bitesLeft, fullItemId, bitesPerItem);
		}

		/**
		 * One variant of a charged item: tracked in charges, in one family per item type, and recorded under the
		 * highest-charge item {@code fullItemId} with {@code chargesPerItem} charges per item.
		 */
		static ItemInfo charged(int fullItemId, int chargesLeft, int chargesPerItem)
		{
			return new ItemInfo(Category.CHARGES, "charges:" + fullItemId, chargesLeft, fullItemId, chargesPerItem);
		}

		Category getCategory()
		{
			return category;
		}

		/**
		 * Family tracked in doses (or bites), or {@code null}: a potion's name without the dose suffix, or one key per
		 * full item for food eaten in parts.
		 */
		String getFamily()
		{
			return family;
		}

		/** Doses in one potion item, or bites left in one stage of a multi-bite food; 0 otherwise. */
		int getDoses()
		{
			return doses;
		}

		/** Full item a multi-bite food's bites are recorded under, or -1. */
		int getFullItemId()
		{
			return fullItemId;
		}

		/** Bites in one {@link #getFullItemId() full item}, or 0. */
		int getDosesPerItem()
		{
			return dosesPerItem;
		}
	}

	/** Item facts source. Implementations may only be valid on a particular thread (the plugin's: the client thread). */
	interface Classifier
	{
		ItemInfo classify(int itemId);
	}

	/**
	 * Classifies an item from its definition. Rune, ammo and cannonball are decided by the caller (they need lookups
	 * this class cannot do) and take precedence. A teleport tablet has a "Break" action and "teleport" in its name; a
	 * potion is a {@code Name(n)} item with a "Drink" action; food is any other item with an "Eat" or "Drink" action.
	 * Noted items have no inventory actions, so they are never supplies.
	 *
	 * @param name             item name, or {@code null}
	 * @param inventoryActions inventory actions, entries may be {@code null}
	 */
	static ItemInfo classify(String name, String[] inventoryActions, boolean rune, boolean ammo, boolean cannonball)
	{
		return classify(-1, name, inventoryActions, rune, ammo, cannonball);
	}

	/**
	 * Like {@link #classify(String, String[], boolean, boolean, boolean)}, except that some items are classified by
	 * their id first (see {@link #classifiesById}): a stage of a food eaten in parts ({@link #MULTI_BITE_FOOD}) as
	 * {@link ItemInfo#multiBiteFood multi-bite food}, a charged item ({@link #CHARGED_ITEMS}) as
	 * {@link ItemInfo#charged charges}, and a {@link #SLAYER_CONSUMABLES Slayer consumable}.
	 */
	static ItemInfo classify(int itemId, String name, String[] inventoryActions, boolean rune, boolean ammo,
		boolean cannonball)
	{
		final int[] stage = MULTI_BITE_FOOD.get(itemId);
		if (stage != null)
		{
			return ItemInfo.multiBiteFood(stage[0], stage[1], stage[2]);
		}
		final int[] charges = CHARGED_ITEMS.get(itemId);
		if (charges != null)
		{
			return ItemInfo.charged(charges[0], charges[1], charges[2]);
		}
		if (SLAYER_CONSUMABLES.contains(itemId))
		{
			return ItemInfo.of(Category.SLAYER_CONSUMABLE);
		}
		if (cannonball)
		{
			return ItemInfo.of(Category.CANNONBALL);
		}
		if (rune)
		{
			return ItemInfo.of(Category.RUNE);
		}
		if (ammo)
		{
			return ItemInfo.of(Category.AMMO);
		}
		if (hasAction(inventoryActions, "Break") && name != null && name.toLowerCase(Locale.ROOT).contains("teleport"))
		{
			return ItemInfo.of(Category.TELEPORT_TABLET);
		}
		final boolean eat = hasAction(inventoryActions, "Eat");
		final boolean drink = hasAction(inventoryActions, "Drink");
		if (drink && name != null)
		{
			final Matcher m = DOSE_NAME.matcher(name.trim());
			if (m.matches())
			{
				final int doses = Integer.parseInt(m.group(2));
				if (doses > 0)
				{
					return ItemInfo.potion(m.group(1).trim(), doses);
				}
			}
		}
		return eat || drink ? ItemInfo.of(Category.FOOD) : ItemInfo.OTHER;
	}

	/**
	 * Whether {@link #classify(int, String, String[], boolean, boolean, boolean)} decides this id from its tables alone
	 * (multi-bite food, charged items and Slayer consumables), so the caller needs no other lookups for it.
	 */
	static boolean classifiesById(int itemId)
	{
		return MULTI_BITE_FOOD.containsKey(itemId) || CHARGED_ITEMS.containsKey(itemId)
			|| SLAYER_CONSUMABLES.contains(itemId);
	}

	private static boolean hasAction(String[] actions, String action)
	{
		if (actions == null)
		{
			return false;
		}
		for (String a : actions)
		{
			if (a != null && a.equalsIgnoreCase(action))
			{
				return true;
			}
		}
		return false;
	}

	/** One tick's view of the tracked containers. Quantities of the same id are summed. */
	static final class Observation
	{
		private final Map<Integer, Long> items = new TreeMap<>();
		private int cannonBalls;

		/** Adds an item stack. Negative ids (empty slots) and non-positive quantities are ignored. */
		Observation add(int itemId, long quantity)
		{
			if (itemId >= 0 && quantity > 0)
			{
				items.merge(itemId, quantity, Long::sum);
			}
			return this;
		}

		/** Cannonballs loaded in the player's cannon (the ROCKTHROWER varp); 0 when none or no cannon. */
		Observation setCannonBalls(int cannonBalls)
		{
			this.cannonBalls = Math.max(0, cannonBalls);
			return this;
		}

		Map<Integer, Long> getItems()
		{
			return Collections.unmodifiableMap(items);
		}

		int getCannonBalls()
		{
			return cannonBalls;
		}
	}

	/** Supplies used on one tick. */
	static final class Usage
	{
		static final Usage NONE = new Usage(new ItemTally(), new ItemTally(), Collections.emptyMap());

		private final ItemTally used;
		private final ItemTally returned;
		private final Map<Integer, Integer> doses;

		Usage(ItemTally used, ItemTally returned, Map<Integer, Integer> doses)
		{
			this.used = used;
			this.returned = returned;
			this.doses = doses;
		}

		/**
		 * Items used, by item id. Potions are in doses, keyed by the highest-dose variant seen of their family,
		 * multi-bite food is in bites, keyed by the full item, and charged items are in charges, keyed by the
		 * highest-charge item; see {@link #getDoses()}.
		 */
		ItemTally getUsed()
		{
			return new ItemTally(used);
		}

		/** Ammo and cannonballs that came back (picked up or unloaded), by item id. */
		ItemTally getReturned()
		{
			return new ItemTally(returned);
		}

		/** Doses (bites, charges) per item for every potion, multi-bite food and charged item id in {@link #getUsed()}. */
		Map<Integer, Integer> getDoses()
		{
			return new TreeMap<>(doses);
		}

		boolean isEmpty()
		{
			return used.isEmpty() && returned.isEmpty();
		}
	}

	/** Identifies one tracked quantity: an item id, or a potion or multi-bite food family (in doses or bites). */
	private static final class Key
	{
		private final int itemId;
		private final String family;

		private Key(int itemId, String family)
		{
			this.itemId = itemId;
			this.family = family;
		}

		@Override
		public boolean equals(Object o)
		{
			if (!(o instanceof Key))
			{
				return false;
			}
			Key k = (Key) o;
			return family != null ? family.equals(k.family) : k.family == null && itemId == k.itemId;
		}

		@Override
		public int hashCode()
		{
			return family != null ? family.hashCode() : Integer.hashCode(itemId);
		}
	}

	private final Classifier classifier;
	/** Classifications by item id; item definitions do not change while the client runs. */
	private final Map<Integer, ItemInfo> infoCache = new HashMap<>();
	/** Category of every key seen. */
	private final Map<Key, Category> keyCategories = new HashMap<>();
	/**
	 * Item each family's use is recorded under, with its doses per item: {doses, item id}. For a potion, the
	 * highest-dose variant seen; for multi-bite food and charged items, the full (highest-charge) item.
	 */
	private final Map<String, int[]> potionVariants = new HashMap<>();

	/** Tracked quantities at the previous update, or {@code null} when a fresh baseline is needed. */
	private Map<Key, Long> baseline;
	private int baselineCannonBalls;
	/** The cannon's ammo at the update before the baseline's, so a pickup split over two ticks can resolve a guess. */
	private int priorCannonBalls;
	/** Cannonball type the cannon's loaded ammo is attributed to; inferred from what the player loads or picks up. */
	private int cannonBallId = CANNONBALL;
	/**
	 * Whether {@link #cannonBallId} comes from a load or pickup this tracker saw. Until then it is only a guess (regular
	 * cannonballs), for example when tracking starts with a cannon already loaded.
	 */
	private boolean cannonBallIdKnown;
	/** Cannonballs counted as used under the guessed type since the last reset; moved to the real type once it shows. */
	private long guessedFired;

	SupplyTracker(Classifier classifier)
	{
		this.classifier = Objects.requireNonNull(classifier);
	}

	/** Forgets the baseline; the next {@link #update} takes a fresh one and counts nothing. */
	void reset()
	{
		baseline = null;
		baselineCannonBalls = 0;
		priorCannonBalls = 0;
		// A correction after a reset could move cannonballs into a session that never counted them.
		guessedFired = 0;
	}

	/** Whether a baseline is held. */
	boolean hasBaseline()
	{
		return baseline != null;
	}

	/** Item id the cannon's loaded ammo is currently attributed to. */
	int getCannonBallId()
	{
		return cannonBallId;
	}

	/**
	 * Compares this tick's containers with the previous tick's and returns what was used. The observation always
	 * becomes the new baseline. Nothing is counted when there was no baseline (first tick, or after {@link #reset()}),
	 * when {@code counting} is false (the session is not running), or when {@code excluded} is true (a bank, shop,
	 * trade or similar interface was open, or the player died).
	 *
	 * @param dropped item ids the player chose "Drop" on recently; decreases of these (and, for a potion, of its whole
	 *                family) are not counted this tick
	 */
	Usage update(Observation observation, boolean counting, boolean excluded, Set<Integer> dropped)
	{
		return update(observation, counting, excluded, dropped, Collections.emptySet());
	}

	/**
	 * Like {@link #update(Observation, boolean, boolean, Set)}, and changes of the {@code ignored} item ids count
	 * neither way this tick: no decrease is used and no increase is returned. Used for the materials of a blowpipe or
	 * trident being loaded, charged or emptied ({@link ChargeExclusions}), which leave or enter the inventory without
	 * being used up.
	 */
	Usage update(Observation observation, boolean counting, boolean excluded, Set<Integer> dropped,
		Set<Integer> ignored)
	{
		final Map<Key, Long> after = new HashMap<>();
		for (Map.Entry<Integer, Long> e : observation.getItems().entrySet())
		{
			final int id = e.getKey();
			final ItemInfo info = info(id);
			if (info.getCategory() == Category.OTHER)
			{
				continue;
			}
			final Key key = key(id, info);
			long quantity = e.getValue();
			if (info.getFamily() != null)
			{
				quantity = saturatedMultiply(quantity, info.getDoses());
			}
			after.merge(key, quantity, SupplyTracker::saturatedAdd);
		}

		final int cannon = observation.getCannonBalls();
		final int previousCannon = baselineCannonBalls;
		// Cannonballs counted under a wrong guess of the loaded type, moved to the type that showed.
		int guessedId = -1;
		long correction = 0;
		if (baseline != null)
		{
			// The cannon's previous load is the type that showed only when this load adds to it or this is its pickup.
			boolean sameLoad = baselineCannonBalls > 0;
			int shown = -1;
			if (cannon > baselineCannonBalls)
			{
				// Loading: attribute the cannon's ammo to the cannonball type that left the inventory.
				shown = largestInventoryChange(after, false);
			}
			else if (!cannonBallIdKnown && cannon == 0 && (baselineCannonBalls > 0 || priorCannonBalls > 0))
			{
				// Picked up or unloaded with the type a guess (on this tick, or the varp a tick before the
				// inventory): the type that came back is the one the cannon held.
				final int returnedId = largestInventoryChange(after, true);
				// Only when more than half of the cannon's last load came back: the cannon may fire between the
				// last load seen and the pickup, so fewer than it held can come back, but a few cannonballs looted
				// on the tick the cannon emptied by firing must not pass for its type.
				final int lastLoad = baselineCannonBalls > 0 ? baselineCannonBalls : priorCannonBalls;
				if (returnedId >= 0 && inventoryChange(after, new Key(returnedId, null)) * 2 > lastLoad)
				{
					shown = returnedId;
					sameLoad = true;
				}
			}
			if (shown >= 0)
			{
				if (!cannonBallIdKnown && shown != cannonBallId && sameLoad)
				{
					// The guess was wrong: the baseline's load, and what it fired, were the shown type all along.
					moveCannonLoad(shown);
					guessedId = cannonBallId;
					correction = guessedFired;
				}
				cannonBallId = shown;
				cannonBallIdKnown = true;
				guessedFired = 0;
			}
			else if (!cannonBallIdKnown && cannon == 0 && baselineCannonBalls == 0 && priorCannonBalls == 0)
			{
				// Empty for three updates without its type showing: what it fired stays under the guess.
				guessedFired = 0;
			}
		}
		if (cannon > 0)
		{
			final Key cannonKey = new Key(cannonBallId, null);
			keyCategories.put(cannonKey, Category.CANNONBALL);
			after.merge(cannonKey, (long) cannon, SupplyTracker::saturatedAdd);
		}

		final Map<Key, Long> before = baseline;
		baseline = after;
		priorCannonBalls = previousCannon;
		baselineCannonBalls = cannon;
		if (before == null || !counting || excluded)
		{
			return Usage.NONE;
		}

		final Set<Key> droppedKeys = new HashSet<>();
		if (dropped != null)
		{
			for (Integer id : dropped)
			{
				if (id != null && id >= 0)
				{
					droppedKeys.add(key(id, info(id)));
				}
			}
		}
		final Set<Key> ignoredKeys = new HashSet<>();
		if (ignored != null)
		{
			for (Integer id : ignored)
			{
				if (id != null && id >= 0)
				{
					ignoredKeys.add(key(id, info(id)));
				}
			}
		}

		final ItemTally used = new ItemTally();
		final ItemTally returned = new ItemTally();
		final Map<Integer, Integer> doses = new TreeMap<>();
		final Set<Key> keys = new HashSet<>(before.keySet());
		keys.addAll(after.keySet());
		keys.removeAll(ignoredKeys);
		for (Key key : keys)
		{
			final long delta = after.getOrDefault(key, 0L) - before.getOrDefault(key, 0L);
			final Category category = keyCategories.getOrDefault(key, Category.OTHER);
			if (delta < 0)
			{
				if (droppedKeys.contains(key))
				{
					continue;
				}
				if (key.family != null)
				{
					final int[] variant = potionVariants.get(key.family);
					used.add(variant[1], -delta);
					doses.put(variant[1], variant[0]);
				}
				else
				{
					used.add(key.itemId, -delta);
					if (!cannonBallIdKnown && key.itemId == cannonBallId)
					{
						// Fired from a cannon whose type is a guess: corrected if its pickup shows another type.
						guessedFired = saturatedAdd(guessedFired, Math.min(-delta, Math.max(0, previousCannon - cannon)));
					}
				}
			}
			else if (delta > 0 && (category == Category.AMMO || category == Category.CANNONBALL))
			{
				returned.add(key.itemId, delta);
			}
		}
		if (correction > 0)
		{
			used.add(cannonBallId, correction);
			returned.add(guessedId, correction);
		}
		return used.isEmpty() && returned.isEmpty() ? Usage.NONE : new Usage(used, returned, doses);
	}

	/**
	 * Classifies the observation's items and notes each potion family's highest-dose variant seen, as {@link #update}
	 * does for every item it sees, without touching the baseline or counting anything. Lets the plugin keep noting the
	 * potions the player holds while no diff runs (no session running), so a potion's use is recorded under the same
	 * item as when every tick was diffed.
	 *
	 * @return {@code false} when an item's data was not loaded yet, so it should be noted again later
	 */
	boolean noteVariants(Observation observation)
	{
		boolean complete = true;
		for (Integer id : observation.getItems().keySet())
		{
			final ItemInfo info = info(id);
			if (info.getCategory() == Category.OTHER)
			{
				// Not cached: the classifier had no answer yet (see info), and asks again next time.
				complete &= infoCache.containsKey(id);
				continue;
			}
			key(id, info);
		}
		return complete;
	}

	/**
	 * The cannonball type whose amount outside the cannon changed most since the baseline, or -1 when none did: the
	 * largest decrease (what was loaded) or, with {@code increase}, the largest increase (what came back from a pickup
	 * or unload).
	 */
	private int largestInventoryChange(Map<Key, Long> after, boolean increase)
	{
		long largest = 0;
		int itemId = -1;
		// Only a key in the baseline can decrease, and only one in after can increase.
		for (Key key : (increase ? after : baseline).keySet())
		{
			if (key.family != null || keyCategories.get(key) != Category.CANNONBALL)
			{
				continue;
			}
			final long change = inventoryChange(after, key);
			final long size = increase ? change : -change;
			if (size > largest)
			{
				largest = size;
				itemId = key.itemId;
			}
		}
		return itemId;
	}

	/**
	 * How much the amount of {@code key} outside the cannon changed since the baseline: positive when it went up. The
	 * baseline's cannon load, held under the current {@link #cannonBallId}, is left out.
	 */
	private long inventoryChange(Map<Key, Long> after, Key key)
	{
		long beforeInventory = baseline.getOrDefault(key, 0L);
		if (key.itemId == cannonBallId)
		{
			// The baseline includes the cannon's previous load under the current type.
			beforeInventory -= baselineCannonBalls;
		}
		return after.getOrDefault(key, 0L) - beforeInventory;
	}

	/** Moves the baseline's cannon load from the guessed {@link #cannonBallId} to {@code itemId}. */
	private void moveCannonLoad(int itemId)
	{
		if (baselineCannonBalls <= 0)
		{
			return;
		}
		baseline.merge(new Key(cannonBallId, null), (long) -baselineCannonBalls, Long::sum);
		baseline.merge(new Key(itemId, null), (long) baselineCannonBalls, Long::sum);
	}

	private ItemInfo info(int itemId)
	{
		ItemInfo info = infoCache.get(itemId);
		if (info == null)
		{
			try
			{
				info = classifier.classify(itemId);
			}
			catch (RuntimeException e)
			{
				info = null;
			}
			if (info == null)
			{
				// Item data not available yet (or the lookup failed): treat as untracked now, classify again later.
				return ItemInfo.OTHER;
			}
			if ((info.getCategory() == Category.POTION || info.getFamily() != null)
				&& (info.getFamily() == null || info.getDoses() <= 0))
			{
				info = ItemInfo.OTHER;
			}
			infoCache.put(itemId, info);
		}
		return info;
	}

	private Key key(int itemId, ItemInfo info)
	{
		final Key key;
		if (info.getFamily() != null)
		{
			key = new Key(-1, info.getFamily());
			if (info.getFullItemId() >= 0)
			{
				potionVariants.putIfAbsent(info.getFamily(), new int[]{info.getDosesPerItem(), info.getFullItemId()});
			}
			else
			{
				final int[] best = potionVariants.get(info.getFamily());
				if (best == null || info.getDoses() > best[0])
				{
					potionVariants.put(info.getFamily(), new int[]{info.getDoses(), itemId});
				}
			}
		}
		else
		{
			key = new Key(itemId, null);
		}
		keyCategories.putIfAbsent(key, info.getCategory());
		return key;
	}

	private static long saturatedAdd(long a, long b)
	{
		long sum = a + b;
		return sum < 0 ? Long.MAX_VALUE : sum;
	}

	private static long saturatedMultiply(long a, long b)
	{
		try
		{
			return Math.multiplyExact(a, b);
		}
		catch (ArithmeticException e)
		{
			return Long.MAX_VALUE;
		}
	}
}
