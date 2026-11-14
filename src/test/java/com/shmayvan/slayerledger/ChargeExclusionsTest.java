package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * Charging, loading, unloading and uncharging the blowpipe, a trident or a powered staff is left out of the supplies
 * diff.
 */
public class ChargeExclusionsTest
{
	private static final int SHARK = 385;
	private static final int BLOWPIPE = ItemID.TOXIC_BLOWPIPE_LOADED;
	private static final int TRIDENT = ItemID.TOXIC_TOTS_CHARGED;

	private final ChargeExclusions exclusions = new ChargeExclusions();

	@Test
	public void nothingIsExcludedByDefault()
	{
		assertFalse(exclusions.isExcluded(ItemID.RUNE_DART, 0));
		assertTrue(exclusions.excludedItems(0).isEmpty());
	}

	@Test
	public void usingDartsOnTheBlowpipeExcludesMaterialsOnly()
	{
		exclusions.menuOptionClicked("Use", new int[]{ItemID.RUNE_DART, BLOWPIPE}, 100);
		assertTrue(exclusions.isExcluded(ItemID.RUNE_DART, 100));
		assertTrue(exclusions.isExcluded(ItemID.SNAKEBOSS_SCALE, 100));
		assertTrue(exclusions.isExcluded(ItemID.DEATHRUNE, 101));
		assertTrue(exclusions.isExcluded(ItemID.CHAOSRUNE, 101));
		assertTrue(exclusions.isExcluded(ItemID.FIRERUNE, 101));
		assertTrue(exclusions.isExcluded(ItemID.COINS, 101));
		assertFalse(exclusions.isExcluded(SHARK, 100));
		assertFalse(exclusions.isExcluded(BLOWPIPE, 100));
		assertFalse(exclusions.isExcluded(ItemID.NATURERUNE, 100));
		assertEquals(ChargeExclusions.MATERIALS, exclusions.excludedItems(100));
	}

	@Test
	public void eitherOrderOfUseCounts()
	{
		exclusions.menuOptionClicked("Use", new int[]{TRIDENT, ItemID.FIRERUNE}, 10);
		assertTrue(exclusions.isExcluded(ItemID.FIRERUNE, 10));
	}

	@Test
	public void unloadAndUnchargeOpenTheWindow()
	{
		exclusions.menuOptionClicked("Unload", new int[]{BLOWPIPE}, 10);
		assertTrue(exclusions.isExcluded(ItemID.RUNE_DART, 11));
		exclusions.reset();
		exclusions.menuOptionClicked("<col=ff9040>Uncharge</col>", new int[]{ItemID.TOTS_CHARGED_ORN}, 20);
		assertTrue(exclusions.isExcluded(ItemID.COINS, 21));
		exclusions.reset();
		exclusions.menuOptionClicked("UNCHARGE", new int[]{ItemID.TOXIC_BLOWPIPE_ORNAMENT}, 30);
		assertTrue(exclusions.isExcluded(ItemID.SNAKEBOSS_SCALE, 30));
	}

	@Test
	public void otherOptionsAndItemsDontOpenIt()
	{
		exclusions.menuOptionClicked("Check", new int[]{BLOWPIPE}, 10);
		exclusions.menuOptionClicked("Wield", new int[]{TRIDENT}, 10);
		exclusions.menuOptionClicked("Drop", new int[]{BLOWPIPE}, 10);
		exclusions.menuOptionClicked("Use", new int[]{ItemID.RUNE_DART, SHARK}, 10);
		exclusions.menuOptionClicked("Use", new int[]{ItemID.RUNE_DART}, 10);
		exclusions.menuOptionClicked(null, new int[]{BLOWPIPE}, 10);
		exclusions.menuOptionClicked("Use", null, 10);
		assertFalse(exclusions.isExcluded(ItemID.RUNE_DART, 10));
		assertFalse(exclusions.isExcluded(ItemID.RUNE_DART, 11));
	}

	@Test
	public void timesOut()
	{
		exclusions.menuOptionClicked("Use", new int[]{ItemID.CHAOSRUNE, TRIDENT}, 100);
		assertFalse("before", exclusions.isExcluded(ItemID.CHAOSRUNE, 99));
		assertTrue(exclusions.isExcluded(ItemID.CHAOSRUNE, 100 + ChargeExclusions.TIMEOUT_TICKS));
		assertFalse(exclusions.isExcluded(ItemID.CHAOSRUNE, 101 + ChargeExclusions.TIMEOUT_TICKS));
	}

	@Test
	public void confirmationClosesAfterTheGrace()
	{
		exclusions.menuOptionClicked("Use", new int[]{ItemID.DEATHRUNE, TRIDENT}, 100);
		exclusions.messageSeen("You add 123 charges to the weapon.<br>New total: 246", 110);
		assertTrue(exclusions.isExcluded(ItemID.DEATHRUNE, 110));
		assertTrue(exclusions.isExcluded(ItemID.DEATHRUNE, 112));
		assertFalse(exclusions.isExcluded(ItemID.DEATHRUNE, 113));
		assertTrue(exclusions.excludedItems(113).isEmpty());
	}

	@Test
	public void otherMessagesDontClose()
	{
		exclusions.menuOptionClicked("Use", new int[]{ItemID.RUNE_DART, BLOWPIPE}, 100);
		exclusions.messageSeen("You eat the shark.", 101);
		exclusions.messageSeen(null, 101);
		assertTrue(exclusions.isExcluded(ItemID.RUNE_DART, 120));
	}

	@Test
	public void confirmationWithoutAWindowDoesNothing()
	{
		exclusions.messageSeen("You uncharge your weapon.", 5);
		assertFalse(exclusions.isExcluded(ItemID.COINS, 5));
		exclusions.menuOptionClicked("Uncharge", new int[]{TRIDENT}, 10);
		// The earlier confirmation didn't shorten the new window.
		assertTrue(exclusions.isExcluded(ItemID.COINS, 20));
	}

	@Test
	public void aNewClickReopensTheWindow()
	{
		exclusions.menuOptionClicked("Use", new int[]{ItemID.RUNE_DART, BLOWPIPE}, 100);
		exclusions.messageSeen("Darts: Rune dart x 50. Scales: 0 (0.0%).", 100);
		exclusions.menuOptionClicked("Use", new int[]{ItemID.SNAKEBOSS_SCALE, BLOWPIPE}, 140);
		assertTrue(exclusions.isExcluded(ItemID.SNAKEBOSS_SCALE, 140 + ChargeExclusions.TIMEOUT_TICKS));
	}

	@Test
	public void confirmations()
	{
		assertTrue(ChargeExclusions.isConfirmation(
			"Darts: <col=007f00>Rune dart x 1,000</col>. Scales: <col=007f00>16,383 (100.0%)</col>."));
		assertTrue(ChargeExclusions.isConfirmation("Darts: None. Scales: 99 (0.6%)."));
		assertTrue(ChargeExclusions.isConfirmation("The blowpipe can't hold any more darts."));
		assertTrue(ChargeExclusions.isConfirmation("The blowpipe can't hold any more scales."));
		assertTrue(ChargeExclusions.isConfirmation("The blowpipe has no darts in it."));
		assertTrue(ChargeExclusions.isConfirmation("@mes_hl_red@The blowpipe has no darts in it.</col>"));
		assertTrue(ChargeExclusions.isConfirmation("You add a charge to the weapon.<br>New total: 2016"));
		assertTrue(ChargeExclusions.isConfirmation("You add 1,200 charges to the weapon.<br>New total: 2,500"));
		assertTrue(ChargeExclusions.isConfirmation("Your weapon is already fully charged."));
		assertTrue(ChargeExclusions.isConfirmation("Your Sanguinesti staff is already fully charged."));
		assertTrue(ChargeExclusions.isConfirmation("Your Holy sanguinesti staff is already fully charged."));
		assertTrue(ChargeExclusions.isConfirmation("You uncharge your weapon."));
		// The powered staves' dialogs, as geheur/weapon-charges recorded them.
		assertTrue(ChargeExclusions.isConfirmation("You apply 201 charges to your Tumeken's shadow."));
		assertTrue(ChargeExclusions.isConfirmation(
			"You apply an additional 301 charges to your<br>Tumeken's shadow. It now has 498 charges in total."));
		assertTrue(ChargeExclusions.isConfirmation("You apply 1 charges to your Holy sanguinesti staff."));
		assertTrue(ChargeExclusions.isConfirmation(
			"You uncharge your Sanguinesti staff, regaining 3 blood<br>runes in the process."));
		assertTrue(ChargeExclusions.isConfirmation(
			"You uncharge your Tumeken's shadow, regaining 198<br>soul runes and 495 chaos runes in the process."));
		assertFalse(ChargeExclusions.isConfirmation("Your blowpipe has run out of darts."));
		assertFalse(ChargeExclusions.isConfirmation("Tumeken's shadow has 200 charges remaining."));
		assertFalse(ChargeExclusions.isConfirmation(null));
	}

	@Test
	public void chargingAndUnchargingAPoweredStaffExcludesItsRunes()
	{
		exclusions.menuOptionClicked("Use", new int[]{ItemID.BLOODRUNE, ItemID.SANGUINESTI_STAFF_UNCHARGED}, 100);
		assertTrue(exclusions.isExcluded(ItemID.BLOODRUNE, 100));
		assertFalse(exclusions.isExcluded(SHARK, 100));
		exclusions.messageSeen("You apply 500 charges to your Sanguinesti staff.", 103);
		assertTrue(exclusions.isExcluded(ItemID.BLOODRUNE, 105));
		assertFalse(exclusions.isExcluded(ItemID.BLOODRUNE, 106));

		// The staves' own "Charge" option opens the charge prompt.
		exclusions.menuOptionClicked("Charge", new int[]{ItemID.TUMEKENS_SHADOW}, 200);
		assertTrue(exclusions.isExcluded(ItemID.SOULRUNE, 200));
		assertTrue(exclusions.isExcluded(ItemID.AETHERRUNE, 201));
		assertTrue(exclusions.isExcluded(ItemID.CHAOSRUNE, 201));
		exclusions.reset();
		exclusions.menuOptionClicked("Uncharge", new int[]{ItemID.SANGUINESTI_STAFF_OR}, 300);
		assertTrue(exclusions.isExcluded(ItemID.BLOODRUNE, 301));
		exclusions.reset();
		// "Charge" on anything else opens nothing.
		exclusions.menuOptionClicked("Charge", new int[]{ItemID.BLOODRUNE}, 400);
		assertFalse(exclusions.isExcluded(ItemID.BLOODRUNE, 400));
	}

	@Test
	public void resetForgetsTheWindow()
	{
		exclusions.menuOptionClicked("Use", new int[]{ItemID.RUNE_DART, BLOWPIPE}, 100);
		exclusions.reset();
		assertFalse(exclusions.isExcluded(ItemID.RUNE_DART, 100));
	}

	@Test
	public void aWindowFromALaterTickExcludesNothing()
	{
		// a window opened before the tick count restarted must not cover the ticks after the restart.
		exclusions.menuOptionClicked("Use", new int[]{ItemID.RUNE_DART, BLOWPIPE}, 5_000);
		assertFalse(exclusions.isExcluded(ItemID.RUNE_DART, 10));
		assertTrue(exclusions.excludedItems(10).isEmpty());
		exclusions.messageSeen("The blowpipe has no darts in it.", 10);
		assertTrue("the window itself is unchanged", exclusions.isExcluded(ItemID.RUNE_DART, 5_000));
	}

	@Test
	public void everyWeaponVariantIsAWeapon()
	{
		for (int id : ChargedWeaponUsage.BLOWPIPES)
		{
			assertTrue(ChargeExclusions.WEAPONS.contains(id));
		}
		for (int id : ChargedWeaponUsage.SEAS_TRIDENTS)
		{
			assertTrue(ChargeExclusions.WEAPONS.contains(id));
		}
		for (int id : ChargedWeaponUsage.SWAMP_TRIDENTS)
		{
			assertTrue(ChargeExclusions.WEAPONS.contains(id));
		}
		for (int id : ChargedWeaponUsage.SANGUINESTI_STAVES)
		{
			assertTrue(ChargeExclusions.WEAPONS.contains(id));
		}
		for (int id : ChargedWeaponUsage.TUMEKENS_SHADOWS)
		{
			assertTrue(ChargeExclusions.WEAPONS.contains(id));
		}
		assertEquals(28, ChargeExclusions.WEAPONS.size());
		assertEquals(17, ChargeExclusions.MATERIALS.size());
	}
}
