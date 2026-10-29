package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.PluginHarness.item;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Collections;
import java.util.Map;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Teleport jewellery, teleport tablets and Slayer consumables as wired in the plugin ({@link PluginHarness}): the worn
 * amulet, ring and gloves slots are read with the inventory, charged items are classified by id even though their item
 * stats are not loaded (the harness never loads them), and charges are saved and valued per charge.
 * {@link SupplyChargesTest} covers the rules themselves.
 */
public class HarnessChargedSuppliesTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final String[] WEAR = {null, "Wear", "Rub", null, "Drop"};

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.AMULET_OF_GLORY_6, "Amulet of glory(6)", 12_000, 0, WEAR);
		harness.defineItem(ItemID.AMULET_OF_GLORY_4, "Amulet of glory(4)", 11_000, 0, WEAR);
		harness.defineItem(ItemID.AMULET_OF_GLORY_3, "Amulet of glory(3)", 10_000, 0, WEAR);
		harness.defineItem(ItemID.RING_OF_DUELING_8, "Ring of dueling(8)", 800, 0, WEAR);
		harness.defineItem(ItemID.RING_OF_DUELING_1, "Ring of dueling(1)", 100, 0, WEAR);
		harness.defineItem(ItemID.JEWL_BRACELET_OF_COMBAT_6, "Combat bracelet(6)", 6_000, 0, WEAR);
		harness.defineItem(ItemID.JEWL_BRACELET_OF_COMBAT_5, "Combat bracelet(5)", 5_000, 0, WEAR);
		harness.defineItem(ItemID.POH_TABLET_VARROCKTELEPORT, "Varrock teleport", 500, 0,
			"Break", null, null, null, "Drop");
		harness.defineItem(ItemID.SLAYER_BAG_OF_SALT, "Bag of salt", 10, 0, null, null, null, null, "Drop");
		harness.setInventory(item(ItemID.POH_TABLET_VARROCKTELEPORT, 5), item(ItemID.SLAYER_BAG_OF_SALT, 50));
		harness.setWorn(EquipmentInventorySlot.AMULET, ItemID.AMULET_OF_GLORY_4, 1);
		harness.setWorn(EquipmentInventorySlot.RING, ItemID.RING_OF_DUELING_1, 1);
		harness.setWorn(EquipmentInventorySlot.GLOVES, ItemID.JEWL_BRACELET_OF_COMBAT_6, 1);
		harness.startUp();
		harness.setTask(42, "Rockslugs", 10, 10);
		harness.loginAndSettle(ACCOUNT);
	}

	private static long used(Map<Integer, Long> supplies, int itemId)
	{
		return supplies == null ? 0 : supplies.getOrDefault(itemId, 0L);
	}

	@Test
	public void wornJewelleryTabletsAndSaltAreCountedAndValued()
	{
		assertTrue(harness.play());
		// Teleports from the equipment tab: a glory charge, the dueling ring's last charge (it crumbles) and a combat
		// bracelet charge.
		harness.setWorn(EquipmentInventorySlot.AMULET, ItemID.AMULET_OF_GLORY_3, 1);
		harness.setWorn(EquipmentInventorySlot.RING, -1, 0);
		harness.setWorn(EquipmentInventorySlot.GLOVES, ItemID.JEWL_BRACELET_OF_COMBAT_5, 1);
		// A tablet broken and two bags of salt used.
		harness.setInventory(item(ItemID.POH_TABLET_VARROCKTELEPORT, 4), item(ItemID.SLAYER_BAG_OF_SALT, 48));
		harness.tick();

		harness.stop();
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		Map<Integer, Long> supplies = record.getSupplies();
		assertEquals(1L, used(supplies, ItemID.AMULET_OF_GLORY_6));
		assertEquals(1L, used(supplies, ItemID.RING_OF_DUELING_8));
		assertEquals(1L, used(supplies, ItemID.JEWL_BRACELET_OF_COMBAT_6));
		assertEquals(1L, used(supplies, ItemID.POH_TABLET_VARROCKTELEPORT));
		assertEquals(2L, used(supplies, ItemID.SLAYER_BAG_OF_SALT));
		assertEquals(5, supplies.size());
		Map<Integer, Integer> doses = record.getSupplyDoses();
		assertEquals(Integer.valueOf(6), doses.get(ItemID.AMULET_OF_GLORY_6));
		assertEquals(Integer.valueOf(8), doses.get(ItemID.RING_OF_DUELING_8));
		assertEquals(Integer.valueOf(6), doses.get(ItemID.JEWL_BRACELET_OF_COMBAT_6));
		assertNull(doses.get(ItemID.POH_TABLET_VARROCKTELEPORT));
		// 12,000 / 6 + 800 / 8 + 6,000 / 6 + 500 + 2 * 10.
		assertEquals(Long.valueOf(2_000 + 100 + 1_000 + 500 + 20), record.getSuppliesGeValue());
	}

	@Test
	public void unequippingAndPausingAreNotCounted()
	{
		assertTrue(harness.play());
		// Taking the amulet off into the inventory moves it between tracked containers.
		harness.setWorn(EquipmentInventorySlot.AMULET, -1, 0);
		harness.setInventory(item(ItemID.POH_TABLET_VARROCKTELEPORT, 5), item(ItemID.SLAYER_BAG_OF_SALT, 50),
			item(ItemID.AMULET_OF_GLORY_4, 1));
		harness.tick();
		harness.pause();
		harness.setInventory(item(ItemID.POH_TABLET_VARROCKTELEPORT, 5), item(ItemID.SLAYER_BAG_OF_SALT, 50),
			item(ItemID.AMULET_OF_GLORY_3, 1));
		harness.tick();

		harness.stop();
		Map<Integer, Long> supplies = harness.onlyRecord(ACCOUNT).getSupplies();
		assertEquals(Collections.emptyMap(), supplies == null ? Collections.emptyMap() : supplies);
	}

	@Test
	public void jewelleryBankedFromTheEquipmentIsNotCounted()
	{
		assertTrue(harness.play());
		harness.openInterface(InterfaceID.BANKMAIN);
		harness.setWorn(EquipmentInventorySlot.AMULET, -1, 0);
		harness.setWorn(EquipmentInventorySlot.GLOVES, -1, 0);
		harness.tick();
		harness.closeInterface(InterfaceID.BANKMAIN);
		harness.ticks(SupplyExclusions.INTERFACE_TICKS + 1);

		harness.stop();
		Map<Integer, Long> supplies = harness.onlyRecord(ACCOUNT).getSupplies();
		assertEquals(0L, used(supplies, ItemID.AMULET_OF_GLORY_6));
		assertEquals(0L, used(supplies, ItemID.JEWL_BRACELET_OF_COMBAT_6));
	}
}
