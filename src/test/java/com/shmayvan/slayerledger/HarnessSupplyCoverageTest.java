package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.PluginHarness.item;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Collections;
import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Supply coverage audit: each consumable family a Slayer task uses is counted through the plugin's wiring
 * ({@link PluginHarness}), so a regression in any family fails the build. This class covers the families no other
 * harness test does; the rest are proven elsewhere:
 * <ul>
 *     <li>runes loose in the inventory (an Ice Barrage task), potions by dose and food:
 *     {@link HarnessSupplyCountingStopTest}; decanting nets zero: {@link SupplyTrackerTest}; food eaten in bites:
 *     {@link MultiBiteFoodTest};</li>
 *     <li>blowpipe darts and scales, trident and powered staff charges, and loading or charging them:
 *     {@link HarnessChargedWeaponsTest};</li>
 *     <li>regular and granite cannonballs: {@link HarnessCannonTaskTest};</li>
 *     <li>bags of salt, teleport tablets and teleport jewellery: {@link HarnessChargedSuppliesTest}; ice coolers and the
 *     fungicide spray: {@link SupplyChargesTest}.</li>
 * </ul>
 */
public class HarnessSupplyCoverageTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final String[] NO_USE = {null, null, null, null, "Drop"};
	private static final String[] DRINK = {"Drink", null, null, "Empty", "Drop"};
	private static final String[] WEAR = {null, "Wear", null, null, "Drop"};
	private static final String[] TELEPORT = {"Teleport", null, null, null, "Drop"};
	/** Rune types of the rune pouch enum ({@link PluginHarness#setRunePouchRunes}), from type 1. */
	private static final int[] POUCH_RUNES = {ItemID.DEATHRUNE, ItemID.BLOODRUNE, ItemID.WATERRUNE, ItemID.COSMICRUNE};
	private static final int[] POUCH_TYPES = {VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2,
		VarbitID.RUNE_POUCH_TYPE_3, VarbitID.RUNE_POUCH_TYPE_4};
	private static final int[] POUCH_QUANTITIES = {VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2,
		VarbitID.RUNE_POUCH_QUANTITY_3, VarbitID.RUNE_POUCH_QUANTITY_4};

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.DEATHRUNE, "Death rune", 200, 0, NO_USE);
		harness.defineItem(ItemID.BLOODRUNE, "Blood rune", 300, 0, NO_USE);
		harness.defineItem(ItemID.WATERRUNE, "Water rune", 5, 0, NO_USE);
		harness.defineItem(ItemID.COSMICRUNE, "Cosmic rune", 100, 0, NO_USE);
		harness.defineItem(ItemID.FIRERUNE, "Fire rune", 5, 0, NO_USE);
		harness.setRunePouchRunes(POUCH_RUNES);
		harness.defineAmmo(ItemID.RUNE_ARROW, "Rune arrow", 100);
		harness.defineAmmo(ItemID.ADAMANT_ARROW, "Adamant arrow", 40);
		harness.defineStackableWeapon(ItemID.RUNE_KNIFE, "Rune knife", 150);
		harness.defineStackableWeapon(ItemID.RUNE_DART, "Rune dart", 120);
		harness.defineItem(ItemID.TELEPORTSCROLL_ZULANDRA, "Zul-andra teleport", 1_000, 0, TELEPORT);
		harness.defineItem(ItemID.TELEPORTSCROLL_CERBERUS, "Key master teleport", 2_000, 0, TELEPORT);
		harness.defineItem(ItemID.BRACELET_OF_SLAUGHTER, "Bracelet of slaughter", 3_000, 0, WEAR);
		harness.defineItem(ItemID.EXPEDITIOUS_BRACELET, "Expeditious bracelet", 6_000, 0, WEAR);
		harness.setInventory();
		harness.startUp();
		harness.setTask(42, "Abyssal demons", 100, 100);
		harness.loginAndSettle(ACCOUNT);
	}

	/** Stops the session and returns the saved record's supplies. */
	private Map<Integer, Long> stopAndGetSupplies()
	{
		harness.endTask();
		final Map<Integer, Long> supplies = harness.onlyRecord(ACCOUNT).getSupplies();
		return supplies == null ? Collections.emptyMap() : supplies;
	}

	private static long used(Map<Integer, Long> supplies, int itemId)
	{
		return supplies.getOrDefault(itemId, 0L);
	}

	/** Puts {@code quantities} in rune pouch slots 1 to 4, holding the {@link #POUCH_RUNES} types in that order. */
	private void runePouch(int... quantities)
	{
		for (int slot = 0; slot < POUCH_TYPES.length; slot++)
		{
			harness.setVarbit(POUCH_TYPES[slot], slot + 1);
			harness.setVarbit(POUCH_QUANTITIES[slot], quantities[slot]);
		}
	}

	@Test
	public void runesFromTheInventoryAndEveryDivineRunePouchSlotAreCounted()
	{
		runePouch(1_000, 500, 1_500, 100);
		harness.setInventory(item(ItemID.DIVINE_RUNE_POUCH, 1), item(ItemID.FIRERUNE, 500),
			item(ItemID.DEATHRUNE, 100));
		harness.tick();
		assertTrue(harness.startNow());

		// Filling the pouch with the inventory's death runes is not a use.
		runePouch(1_100, 500, 1_500, 100);
		harness.setInventory(item(ItemID.DIVINE_RUNE_POUCH, 1), item(ItemID.FIRERUNE, 500));
		harness.tick();
		// One cast from each of the four slots, and fire runes loose in the inventory.
		runePouch(1_096, 498, 1_494, 99);
		harness.setInventory(item(ItemID.DIVINE_RUNE_POUCH, 1), item(ItemID.FIRERUNE, 490));
		harness.tick();

		final Map<Integer, Long> supplies = stopAndGetSupplies();
		assertEquals(4L, used(supplies, ItemID.DEATHRUNE));
		assertEquals(2L, used(supplies, ItemID.BLOODRUNE));
		assertEquals(6L, used(supplies, ItemID.WATERRUNE));
		assertEquals(1L, used(supplies, ItemID.COSMICRUNE));
		assertEquals(10L, used(supplies, ItemID.FIRERUNE));
		assertEquals(5, supplies.size());
	}

	@Test
	public void runesCastForAThrallFromTheInventoryAndPouchAreCounted()
	{
		// Resurrect Greater Ghost (OSRS Wiki): 10 fire, 5 blood and 1 cosmic rune, with the Book of the Dead carried.
		runePouch(0, 200, 0, 50);
		harness.setInventory(item(ItemID.BH_RUNE_POUCH, 1), item(ItemID.FIRERUNE, 300),
			item(ItemID.BOOK_OF_THE_DEAD, 1));
		harness.tick();
		assertTrue(harness.startNow());
		for (int cast = 1; cast <= 3; cast++)
		{
			runePouch(0, 200 - 5 * cast, 0, 50 - cast);
			harness.setInventory(item(ItemID.BH_RUNE_POUCH, 1), item(ItemID.FIRERUNE, 300 - 10 * cast),
				item(ItemID.BOOK_OF_THE_DEAD, 1));
			harness.tick();
		}

		final Map<Integer, Long> supplies = stopAndGetSupplies();
		assertEquals(30L, used(supplies, ItemID.FIRERUNE));
		assertEquals(15L, used(supplies, ItemID.BLOODRUNE));
		assertEquals(3L, used(supplies, ItemID.COSMICRUNE));
		assertEquals("the book is not used up", 3, supplies.size());
	}

	@Test
	public void arrowsFromTheAmmoSlotAndQuiverCountAndPickedUpOnesOffsetThem()
	{
		harness.setWorn(EquipmentInventorySlot.AMMO, ItemID.RUNE_ARROW, 500);
		harness.setVarp(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO, ItemID.ADAMANT_ARROW);
		harness.setVarp(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT, 200);
		harness.tick();
		assertTrue(harness.startNow());

		// Fired from the ammo slot, then from the quiver's second ammo.
		harness.setWorn(EquipmentInventorySlot.AMMO, ItemID.RUNE_ARROW, 495);
		harness.tick();
		harness.setVarp(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT, 197);
		harness.tick();
		// Two rune arrows picked up from the floor.
		harness.setInventory(item(ItemID.RUNE_ARROW, 2));
		harness.tick();
		// Equipping them into the ammo slot moves them between tracked containers.
		harness.setInventory();
		harness.setWorn(EquipmentInventorySlot.AMMO, ItemID.RUNE_ARROW, 497);
		harness.tick();

		final Map<Integer, Long> supplies = stopAndGetSupplies();
		assertEquals("5 fired, 2 picked up", 3L, used(supplies, ItemID.RUNE_ARROW));
		assertEquals(3L, used(supplies, ItemID.ADAMANT_ARROW));
		assertEquals(2, supplies.size());
	}

	@Test
	public void thrownKnivesAndDartsFromTheWeaponSlotCountAndPickedUpOnesOffsetThem()
	{
		harness.setWorn(EquipmentInventorySlot.WEAPON, ItemID.RUNE_KNIFE, 100);
		harness.setInventory(item(ItemID.RUNE_DART, 50));
		harness.tick();
		assertTrue(harness.startNow());

		harness.setWorn(EquipmentInventorySlot.WEAPON, ItemID.RUNE_KNIFE, 96);
		harness.tick();
		harness.setInventory(item(ItemID.RUNE_DART, 50), item(ItemID.RUNE_KNIFE, 2));
		harness.tick();
		// Swapping to the darts: both stacks move between the inventory and the weapon slot.
		harness.setWorn(EquipmentInventorySlot.WEAPON, ItemID.RUNE_DART, 50);
		harness.setInventory(item(ItemID.RUNE_KNIFE, 98));
		harness.tick();
		harness.setWorn(EquipmentInventorySlot.WEAPON, ItemID.RUNE_DART, 47);
		harness.tick();

		final Map<Integer, Long> supplies = stopAndGetSupplies();
		assertEquals("4 thrown, 2 picked up", 2L, used(supplies, ItemID.RUNE_KNIFE));
		assertEquals(3L, used(supplies, ItemID.RUNE_DART));
		assertEquals(2, supplies.size());
	}

	@Test
	public void teleportScrollsCountOnePerScrollAndStoringThemInTheBookIsNotAUse()
	{
		harness.setInventory(item(ItemID.TELEPORTSCROLL_ZULANDRA, 10), item(ItemID.TELEPORTSCROLL_CERBERUS, 5),
			item(ItemID.BOOKOFSCROLLS_CHARGED, 1));
		harness.tick();
		assertTrue(harness.startNow());

		harness.setInventory(item(ItemID.TELEPORTSCROLL_ZULANDRA, 9), item(ItemID.TELEPORTSCROLL_CERBERUS, 5),
			item(ItemID.BOOKOFSCROLLS_CHARGED, 1));
		harness.tick();
		// The Key master teleports are stored in the master scroll book.
		harness.clickUseOn(ItemID.TELEPORTSCROLL_CERBERUS, ItemID.BOOKOFSCROLLS_CHARGED);
		harness.setInventory(item(ItemID.TELEPORTSCROLL_ZULANDRA, 9), item(ItemID.BOOKOFSCROLLS_CHARGED, 1));
		harness.tick();
		// Well after the store: the last Zul-andra scroll read is counted as usual.
		harness.ticks(SupplyExclusions.DROP_WINDOW_TICKS + 1);
		harness.setInventory(item(ItemID.TELEPORTSCROLL_ZULANDRA, 8), item(ItemID.BOOKOFSCROLLS_CHARGED, 1));
		harness.tick();

		harness.endTask();
		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(2L, used(record.getSupplies(), ItemID.TELEPORTSCROLL_ZULANDRA));
		assertEquals(0L, used(record.getSupplies(), ItemID.TELEPORTSCROLL_CERBERUS));
		assertEquals(1, record.getSupplies().size());
		assertEquals(Long.valueOf(2 * 1_000), record.getSuppliesGeValue());
	}

	@Test
	public void slayerBraceletChargesCountFromTheirMessages()
	{
		harness.setWorn(EquipmentInventorySlot.GLOVES, ItemID.BRACELET_OF_SLAUGHTER, 1);
		harness.tick();
		final String slaughter = "Your bracelet of slaughter prevents your slayer count from decreasing. It has %d "
			+ "charges left.";
		harness.chat(ChatMessageType.GAMEMESSAGE, String.format(slaughter, 29));
		assertTrue(harness.startNow());
		harness.chat(ChatMessageType.GAMEMESSAGE, String.format(slaughter, 28));
		harness.tick();
		harness.chat(ChatMessageType.GAMEMESSAGE, String.format(slaughter, 27));
		// Checking it uses nothing.
		harness.chat(ChatMessageType.GAMEMESSAGE, "Your bracelet of slaughter has 27 charges left.");
		harness.tick();
		// An expeditious bracelet's last charge: it crumbles and leaves the gloves slot, which is not counted again.
		harness.setWorn(EquipmentInventorySlot.GLOVES, ItemID.EXPEDITIOUS_BRACELET, 1);
		harness.tick();
		harness.chat(ChatMessageType.GAMEMESSAGE,
			"Your expeditious bracelet helps you progress your slayer task faster. It then crumbles to dust.");
		harness.setWorn(EquipmentInventorySlot.GLOVES, -1, 0);
		harness.tick();
		harness.pause();
		// A slaughter activation is a task kill: it restarts the paused session, and its charge, used just
		// before, counts in the restart's supplies warm-up (V2 plan 4.1).
		harness.chat(ChatMessageType.GAMEMESSAGE, String.format(slaughter, 26));
		harness.tick();
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());

		harness.endTask();
		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		final Map<Integer, Long> supplies = record.getSupplies();
		assertEquals("28, 27 and 26; not 29, before the test seam's start", 3L,
			used(supplies, ItemID.BRACELET_OF_SLAUGHTER));
		assertEquals(1L, used(supplies, ItemID.EXPEDITIOUS_BRACELET));
		assertEquals(2, supplies.size());
		assertEquals(Integer.valueOf(30), record.getSupplyDoses().get(ItemID.BRACELET_OF_SLAUGHTER));
		assertEquals(Integer.valueOf(30), record.getSupplyDoses().get(ItemID.EXPEDITIOUS_BRACELET));
		// 3 * 3,000 / 30 + 6,000 / 30.
		assertEquals(Long.valueOf(300 + 200), record.getSuppliesGeValue());
	}

	@Test
	public void aMembersPotionSeenOnAFreeWorldIsCountedInDosesAfterAHopToAMembersWorld()
	{
		// on a free world the game names members items "... (Members)". If that definition were classified and
		// cached, a prayer potion would count as food for the rest of the client run.
		final int[] prayer = {-1, ItemID._1DOSEPRAYERRESTORE, ItemID._2DOSEPRAYERRESTORE, ItemID._3DOSEPRAYERRESTORE,
			ItemID._4DOSEPRAYERRESTORE};
		for (int dose = 1; dose <= 4; dose++)
		{
			harness.defineItem(prayer[dose], "Prayer potion(" + dose + ")" + SupplyReader.MEMBERS_SUFFIX, 2_000L * dose,
				0, DRINK);
		}
		harness.setInventory(item(ItemID._4DOSEPRAYERRESTORE, 1), item(ItemID._4DOSEPRAYERRESTORE, 1));
		harness.ticks(PluginHarness.SETTLE_TICKS + SlayerLedgerPlugin.INVENTORY_NOTE_TRIES);

		// A hop to a members world: the same items now show their real definitions.
		for (int dose = 1; dose <= 4; dose++)
		{
			harness.defineItem(prayer[dose], "Prayer potion(" + dose + ")", 2_000L * dose, 0, DRINK);
		}
		harness.hop();
		harness.ticks(PluginHarness.SETTLE_TICKS);
		assertTrue(harness.startNow());
		harness.setInventory(item(ItemID._4DOSEPRAYERRESTORE, 1), item(ItemID._3DOSEPRAYERRESTORE, 1));
		harness.tick();
		harness.setInventory(item(ItemID._4DOSEPRAYERRESTORE, 1), item(ItemID._2DOSEPRAYERRESTORE, 1));
		harness.tick();

		harness.endTask();
		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals("two doses, under the 4-dose potion", Collections.singletonMap(ItemID._4DOSEPRAYERRESTORE, 2L),
			record.getSupplies());
		assertEquals(Integer.valueOf(4), record.getSupplyDoses().get(ItemID._4DOSEPRAYERRESTORE));
		assertNull(record.getSupplyDoses().get(ItemID._3DOSEPRAYERRESTORE));
	}
}
