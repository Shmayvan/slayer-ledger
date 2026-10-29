package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link SupplyReader}: how an item definition is classified for supply counting, and which containers are read.
 * The client and item manager are plain Mockito mocks; the counting rules themselves are {@link SupplyTrackerTest}'s.
 */
public class SupplyReaderTest
{
	private static final String[] WIELD = {null, "Wield", null, null, "Drop"};
	private static final String[] NONE = {null, null, null, null, "Drop"};
	private static final int FIRE_RUNE = 554;
	private static final int ODD_POUCH_RUNE = 30000;
	private static final int BOLTS = 9144;
	private static final int DARTS = 811;
	private static final int SWORD = 1277;

	private Client client;
	private ItemManager itemManager;
	private SupplyReader reader;

	@Before
	public void setUp()
	{
		client = mock(Client.class);
		itemManager = mock(ItemManager.class);
		reader = new SupplyReader(client, itemManager);
	}

	private ItemComposition item(int id, String name, String[] actions, boolean stackable)
	{
		final ItemComposition comp = mock(ItemComposition.class);
		when(comp.getName()).thenReturn(name);
		when(comp.getNote()).thenReturn(-1);
		when(comp.getPlaceholderTemplateId()).thenReturn(-1);
		when(comp.getInventoryActions()).thenReturn(actions);
		when(comp.isStackable()).thenReturn(stackable);
		when(itemManager.getItemComposition(id)).thenReturn(comp);
		return comp;
	}

	private void stats(int id, EquipmentInventorySlot slot)
	{
		when(itemManager.getItemStats(id)).thenReturn(new ItemStats(true, 0, 0,
			ItemEquipmentStats.builder().slot(slot.getSlotIdx()).build()));
	}

	private EnumComposition runeEnum(int... runeIds)
	{
		final EnumComposition runes = mock(EnumComposition.class);
		when(runes.getIntVals()).thenReturn(runeIds);
		when(client.getEnum(EnumID.RUNEPOUCH_RUNE)).thenReturn(runes);
		return runes;
	}

	private static SupplyTracker.Category category(SupplyTracker.ItemInfo info)
	{
		return info.getCategory();
	}

	@Test
	public void notedItemsAndPlaceholdersAreNeverSupplies()
	{
		when(item(386, "Shark", NONE, false).getNote()).thenReturn(799);
		when(item(14401, "Shark", NONE, false).getPlaceholderTemplateId()).thenReturn(385);

		assertSame(SupplyTracker.ItemInfo.OTHER, reader.classifyItem(386));
		assertSame(SupplyTracker.ItemInfo.OTHER, reader.classifyItem(14401));
	}

	@Test
	public void runesComeFromThePouchEnumOrTheName()
	{
		runeEnum(0, ODD_POUCH_RUNE);
		item(ODD_POUCH_RUNE, "Odd thing", NONE, true);
		item(FIRE_RUNE, "Fire rune", NONE, true);
		item(SWORD, "Bronze sword", NONE, false);

		assertEquals(SupplyTracker.Category.RUNE, category(reader.classifyItem(ODD_POUCH_RUNE)));
		assertEquals(SupplyTracker.Category.RUNE, category(reader.classifyItem(FIRE_RUNE)));
		assertEquals(SupplyTracker.Category.OTHER, category(reader.classifyItem(SWORD)));
	}

	@Test
	public void runePouchIdsAreCachedOnceLoadedAndReloadedAfterReset()
	{
		runeEnum(FIRE_RUNE);
		item(FIRE_RUNE, "Fire rune", NONE, true);

		reader.classifyItem(FIRE_RUNE);
		reader.classifyItem(FIRE_RUNE);
		verify(client, times(1)).getEnum(EnumID.RUNEPOUCH_RUNE);

		reader.reset();
		reader.classifyItem(FIRE_RUNE);
		verify(client, times(2)).getEnum(EnumID.RUNEPOUCH_RUNE);
	}

	@Test
	public void anUnavailablePouchEnumIsRetried()
	{
		item(FIRE_RUNE, "Fire rune", NONE, true);

		reader.classifyItem(FIRE_RUNE);
		reader.classifyItem(FIRE_RUNE);
		verify(client, times(2)).getEnum(EnumID.RUNEPOUCH_RUNE);
	}

	@Test
	public void ammoIsTheAmmoSlotOrAStackableWeapon()
	{
		item(BOLTS, "Steel bolts", WIELD, true);
		stats(BOLTS, EquipmentInventorySlot.AMMO);
		item(DARTS, "Rune dart", WIELD, true);
		stats(DARTS, EquipmentInventorySlot.WEAPON);
		item(SWORD, "Bronze sword", WIELD, false);
		stats(SWORD, EquipmentInventorySlot.WEAPON);

		assertEquals(SupplyTracker.Category.AMMO, category(reader.classifyItem(BOLTS)));
		assertEquals(SupplyTracker.Category.AMMO, category(reader.classifyItem(DARTS)));
		assertEquals(SupplyTracker.Category.OTHER, category(reader.classifyItem(SWORD)));
	}

	@Test
	public void aWieldableItemWithoutStatsYetIsLeftForLater()
	{
		item(DARTS, "Rune dart", WIELD, true);

		assertNull(reader.classifyItem(DARTS));
	}

	@Test
	public void cannonballsAndIdTableItemsNeedNoItemStats()
	{
		item(SupplyTracker.CANNONBALL, "Cannonball", NONE, true);
		item(ItemID.PLAIN_PIZZA, "Plain pizza", new String[]{"Eat", null, null, null, "Drop"}, false);

		assertEquals(SupplyTracker.Category.CANNONBALL, category(reader.classifyItem(SupplyTracker.CANNONBALL)));
		assertEquals(SupplyTracker.Category.FOOD, category(reader.classifyItem(ItemID.PLAIN_PIZZA)));
		verify(itemManager, never()).getItemStats(ItemID.PLAIN_PIZZA);
	}

	@Test
	public void isEquipableLooksForWieldOrWear()
	{
		assertTrue(SupplyReader.isEquipable(WIELD));
		assertTrue(SupplyReader.isEquipable(new String[]{null, "Wear"}));
		assertFalse(SupplyReader.isEquipable(NONE));
		assertFalse(SupplyReader.isEquipable(null));
	}

	@Test
	public void runePouchesAreTheRegularAndDivineOnes()
	{
		assertTrue(SupplyReader.isRunePouch(ItemID.BH_RUNE_POUCH));
		assertTrue(SupplyReader.isRunePouch(ItemID.BH_RUNE_POUCH_TROUVER));
		assertTrue(SupplyReader.isRunePouch(ItemID.DIVINE_RUNE_POUCH));
		assertTrue(SupplyReader.isRunePouch(ItemID.DIVINE_RUNE_POUCH_TROUVER));
		assertFalse(SupplyReader.isRunePouch(FIRE_RUNE));
	}

	@Test
	public void noObservationWhileTheInventoryIsNotLoaded()
	{
		assertNull(reader.readSupplyObservation());
	}

	@Test
	public void theObservationReadsInventoryWornSlotsQuiverRunePouchAndCannon()
	{
		final ItemContainer inventory = mock(ItemContainer.class);
		when(inventory.getItems()).thenReturn(new Item[]{new Item(ItemID.BH_RUNE_POUCH, 1), new Item(SWORD, 1)});
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
		final ItemContainer worn = mock(ItemContainer.class);
		when(worn.getItem(EquipmentInventorySlot.AMMO.getSlotIdx())).thenReturn(new Item(BOLTS, 100));
		// Not a supply slot: never read.
		when(worn.getItem(EquipmentInventorySlot.HEAD.getSlotIdx())).thenReturn(new Item(1155, 1));
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(worn);
		when(client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO)).thenReturn(DARTS);
		when(client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT)).thenReturn(30);
		when(client.getVarpValue(VarPlayerID.ROCKTHROWER)).thenReturn(25);
		final EnumComposition runes = runeEnum(FIRE_RUNE);
		when(runes.getIntValue(3)).thenReturn(FIRE_RUNE);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_TYPE_2)).thenReturn(3);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_2)).thenReturn(500);

		final SupplyTracker.Observation observation = reader.readSupplyObservation();

		final Map<Integer, Long> expected = new HashMap<>();
		expected.put(ItemID.BH_RUNE_POUCH, 1L);
		expected.put(SWORD, 1L);
		expected.put(BOLTS, 100L);
		expected.put(DARTS, 30L);
		expected.put(FIRE_RUNE, 500L);
		assertEquals(expected, observation.getItems());
		assertEquals(25, observation.getCannonBalls());
	}

	@Test
	public void thePouchIsOnlyReadWhileOneIsCarried()
	{
		final ItemContainer inventory = mock(ItemContainer.class);
		when(inventory.getItems()).thenReturn(new Item[]{new Item(SWORD, 1)});
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
		when(client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO)).thenReturn(-1);
		runeEnum(FIRE_RUNE);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_TYPE_1)).thenReturn(3);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_1)).thenReturn(500);

		final SupplyTracker.Observation observation = reader.readSupplyObservation();

		assertEquals(1, observation.getItems().size());
		verify(client, never()).getEnum(anyInt());
	}

	@Test
	public void wornItemIdIsNoItemForAnEmptySlotOrNoContainer()
	{
		final ItemContainer worn = mock(ItemContainer.class);
		when(worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx())).thenReturn(new Item(DARTS, 10));

		assertEquals(DARTS, SupplyReader.wornItemId(worn, EquipmentInventorySlot.WEAPON));
		assertEquals(ChargedWeaponUsage.NO_ITEM, SupplyReader.wornItemId(worn, EquipmentInventorySlot.CAPE));
		assertEquals(ChargedWeaponUsage.NO_ITEM, SupplyReader.wornItemId(null, EquipmentInventorySlot.CAPE));
	}
}
