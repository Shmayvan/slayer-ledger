package com.shmayvan.slayerledger;

import java.util.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.gameval.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.*;

/**
 * Reads what the player carries for supply counting: the {@link SupplyTracker.Observation} of the tracked
 * containers, and each item's supplies category ({@link #classifyItem}) from its definition. The diff and the counting
 * rules stay in {@link SupplyTracker}; this class only reads the client. Client thread only; the rune pouch's rune ids
 * are cached until {@link #reset()}, which the plugin calls in its startUp client-thread block.
 *
 * <p>Injected by Guice into the plugin, as RuneLite plugins inject their overlays and helpers.
 */
class SupplyReader
{
	/** Rune pouch slots as read by core RunepouchOverlay (1.13.3); the divine rune pouch uses the same varbits. */
	private static final int[] RUNE_POUCH_TYPE_VARBITS = {
		VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
		VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6
	};
	private static final int[] RUNE_POUCH_QUANTITY_VARBITS = {
		VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2, VarbitID.RUNE_POUCH_QUANTITY_3,
		VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5, VarbitID.RUNE_POUCH_QUANTITY_6
	};
	/** Worn slots read for supplies: ammo and thrown weapons, and teleport jewellery used from the equipment tab. */
	private static final EquipmentInventorySlot[] SUPPLY_WORN_SLOTS = {
		EquipmentInventorySlot.AMMO, EquipmentInventorySlot.WEAPON, EquipmentInventorySlot.AMULET,
		EquipmentInventorySlot.RING, EquipmentInventorySlot.GLOVES
	};
	/** What the game appends to a members item's name on a free world (see {@link #classifyItem}). */
	static final String MEMBERS_SUFFIX = " (Members)";

	private final Client client;
	private final ItemManager itemManager;
	/** Item ids the rune pouch can hold (EnumID.RUNEPOUCH_RUNE values), loaded on first use. */
	private Set<Integer> runePouchRuneIds;

	@Inject
	SupplyReader(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
	}

	/** Client thread, in the plugin's startUp block: the rune pouch's rune ids are loaded afresh. */
	void reset()
	{
		runePouchRuneIds = null;
	}

	/**
	 * Client thread. The tracked containers: the inventory; the worn ammo and weapon slots (classification keeps only
	 * ammo and stackable thrown weapons) and amulet, ring and gloves slots (classification keeps only charged teleport
	 * jewellery, see {@link SupplyTracker#CHARGED_ITEMS}); Dizana's quiver (the DIZANAS_QUIVER_TEMP_AMMO / _AMOUNT varps,
	 * as core AmmoPlugin reads them); the rune pouch while one is in the inventory (varbits RUNE_POUCH_TYPE_1-6 and
	 * RUNE_POUCH_QUANTITY_1-6 mapped through EnumID.RUNEPOUCH_RUNE, as core RunepouchOverlay does); and the cannon's
	 * loaded ammo (the ROCKTHROWER varp core CannonPlugin reads).
	 *
	 * @return the observation, or {@code null} while the inventory is not loaded
	 */
	SupplyTracker.Observation readSupplyObservation()
	{
		final ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory == null)
		{
			return null;
		}
		final SupplyTracker.Observation observation = new SupplyTracker.Observation();
		boolean hasRunePouch = false;
		for (Item item : inventory.getItems())
		{
			observation.add(item.getId(), item.getQuantity());
			hasRunePouch |= isRunePouch(item.getId());
		}

		final ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (worn != null)
		{
			for (EquipmentInventorySlot slot : SUPPLY_WORN_SLOTS)
			{
				final Item item = worn.getItem(slot.getSlotIdx());
				if (item != null)
				{
					observation.add(item.getId(), item.getQuantity());
				}
			}
		}

		final int quiverAmmo = client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO);
		final int quiverAmount = client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT);
		if (quiverAmmo >= 0 && quiverAmount > 0)
		{
			observation.add(quiverAmmo, quiverAmount);
		}

		if (hasRunePouch)
		{
			final EnumComposition runeEnum = client.getEnum(EnumID.RUNEPOUCH_RUNE);
			for (int i = 0; runeEnum != null && i < RUNE_POUCH_TYPE_VARBITS.length; i++)
			{
				final int type = client.getVarbitValue(RUNE_POUCH_TYPE_VARBITS[i]);
				final int amount = client.getVarbitValue(RUNE_POUCH_QUANTITY_VARBITS[i]);
				if (type != 0 && amount > 0)
				{
					observation.add(runeEnum.getIntValue(type), amount);
				}
			}
		}

		observation.setCannonBalls(client.getVarpValue(VarPlayerID.ROCKTHROWER));
		return observation;
	}

	/**
	 * Client thread. The inventory's items alone (the only place potions are kept), for
	 * {@link SupplyTracker#noteVariants} while no session is running; {@code null} while the inventory is not loaded.
	 */
	SupplyTracker.Observation readInventoryObservation()
	{
		final ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory == null)
		{
			return null;
		}
		final SupplyTracker.Observation observation = new SupplyTracker.Observation();
		for (Item item : inventory.getItems())
		{
			observation.add(item.getId(), item.getQuantity());
		}
		return observation;
	}

	/**
	 * Client thread. Supplies category from the item definition: cannonballs by id, runes by the rune pouch enum (or a
	 * name ending in " rune"), ammo by equipment slot (ammo, or a stackable weapon such as darts, knives or
	 * chinchompas), multi-bite food (pies, pizzas and cakes), charged teleport jewellery, the fungicide spray, Slayer
	 * consumables and teleport scrolls by id, and teleport tablets, potions and food by name and inventory actions (see
	 * {@link SupplyTracker#classify}). Noted items and placeholders are never supplies.
	 *
	 * <p>A members item seen on a free world is not classified: there the game appends {@value #MEMBERS_SUFFIX}
	 * to its name ({@code ItemComposition.getName}; the client's {@code getMembersName} removes exactly that suffix,
	 * 1.13.4), so "Prayer potion(4) (Members)" would not read as a potion. {@link SupplyTracker} caches what this
	 * returns for the plugin's life, so it would stay wrong after a hop to a members world; {@code null} makes it ask
	 * again once the item's real definition shows. The item can't be used on a free world anyway.</p>
	 *
	 * @return the category, or {@code null} while a wieldable item's stats are not loaded yet or a members item is seen
	 * on a free world (retried later)
	 */
	SupplyTracker.ItemInfo classifyItem(int itemId)
	{
		final ItemComposition comp = itemManager.getItemComposition(itemId);
		if (comp.getNote() != -1 || comp.getPlaceholderTemplateId() != -1)
		{
			return SupplyTracker.ItemInfo.OTHER;
		}
		final String name = comp.getName();
		if (SupplyTracker.classifiesById(itemId))
		{
			// Decided by id alone: no need to wait for the item stats of wearable jewellery, and the free-world name of a
			// members item doesn't matter.
			return SupplyTracker.classify(itemId, name, comp.getInventoryActions(), false, false, false);
		}
		if (name != null && name.endsWith(MEMBERS_SUFFIX))
		{
			return null;
		}
		final boolean cannonball = itemId == SupplyTracker.CANNONBALL || itemId == SupplyTracker.GRANITE_CANNONBALL;
		final boolean rune = runePouchRuneIds().contains(itemId) || (name != null && name.endsWith(" rune"));
		final ItemStats stats = itemManager.getItemStats(itemId);
		if (stats == null && !cannonball && !rune && isEquipable(comp.getInventoryActions()))
		{
			// Item stats are loaded asynchronously and may not be available yet; don't let an unloaded wieldable item
			// (possibly ammo) be cached as untracked. SupplyTracker retries a null classification later.
			return null;
		}
		final int slot = stats != null && stats.getEquipment() != null ? stats.getEquipment().getSlot() : -1;
		final boolean ammo = slot == EquipmentInventorySlot.AMMO.getSlotIdx()
			|| (slot == EquipmentInventorySlot.WEAPON.getSlotIdx() && comp.isStackable());
		return SupplyTracker.classify(itemId, name, comp.getInventoryActions(), rune, ammo, cannonball);
	}

	/** Whether inventory actions {@code actions} include "Wield" or "Wear". Pure. */
	static boolean isEquipable(String[] actions)
	{
		if (actions != null)
		{
			for (String action : actions)
			{
				if ("Wield".equals(action) || "Wear".equals(action))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Client thread. Item ids the rune pouch can hold; empty (and retried later) while the enum is unavailable. */
	private Set<Integer> runePouchRuneIds()
	{
		if (runePouchRuneIds != null)
		{
			return runePouchRuneIds;
		}
		final Set<Integer> ids = new HashSet<>();
		final EnumComposition runeEnum = client.getEnum(EnumID.RUNEPOUCH_RUNE);
		if (runeEnum != null)
		{
			for (int id : runeEnum.getIntVals())
			{
				if (id > 0)
				{
					ids.add(id);
				}
			}
		}
		if (!ids.isEmpty())
		{
			runePouchRuneIds = ids;
		}
		return ids;
	}

	/** Whether {@code itemId} is a rune pouch (regular or divine, either version) whose runes are read. Pure. */
	static boolean isRunePouch(int itemId)
	{
		return itemId == ItemID.BH_RUNE_POUCH || itemId == ItemID.BH_RUNE_POUCH_TROUVER
			|| itemId == ItemID.DIVINE_RUNE_POUCH || itemId == ItemID.DIVINE_RUNE_POUCH_TROUVER;
	}

	/** The item worn in {@code slot} of {@code worn}, or {@link ChargedWeaponUsage#NO_ITEM}. Pure. */
	static int wornItemId(ItemContainer worn, EquipmentInventorySlot slot)
	{
		final Item item = worn != null ? worn.getItem(slot.getSlotIdx()) : null;
		return item != null ? item.getId() : ChargedWeaponUsage.NO_ITEM;
	}
}
