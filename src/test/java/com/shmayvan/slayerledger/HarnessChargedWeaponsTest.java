package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.PluginHarness.item;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Collections;
import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.SpotanimID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Blowpipe, trident and powered staff (Sanguinesti staff, Tumeken's shadow) use as wired in the plugin
 * ({@link PluginHarness}): shots and casts read from the local player's animation, frame and spotanims with the worn
 * weapon and cape on GameTick and counted only while RUNNING; the dart type learned from the check message and kept in
 * the RSProfile config; loading, charging and unloading kept out of the supplies diff; and the panel's "Check your
 * blowpipe" hint. {@link ChargedWeaponUsageTest},
 * {@link BlowpipeMessageTest} and {@link ChargeExclusionsTest} cover the rules themselves.
 */
public class HarnessChargedWeaponsTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final String RUNE_DART_CHECK =
		"Darts: <col=007f00>Rune dart x 1,000</col>. Scales: <col=007f00>5,000 (33.3%)</col>.";

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.defineStackableWeapon(ItemID.RUNE_DART, "Rune dart", 100);
		harness.defineItem(ItemID.SNAKEBOSS_SCALE, "Zulrah's scales", 150, 0, null, null, null, null, "Drop");
		harness.defineItem(ItemID.DEATHRUNE, "Death rune", 200, 0, null, null, null, null, "Drop");
		harness.defineItem(ItemID.CHAOSRUNE, "Chaos rune", 80, 0, null, null, null, null, "Drop");
		harness.defineItem(ItemID.FIRERUNE, "Fire rune", 5, 0, null, null, null, null, "Drop");
		harness.defineItem(ItemID.BLOODRUNE, "Blood rune", 300, 0, null, null, null, null, "Drop");
		harness.defineItem(ItemID.SOULRUNE, "Soul rune", 250, 0, null, null, null, null, "Drop");
		harness.setInventory();
		harness.startUp();
		harness.setTask(42, "Abyssal demons", 10, 10);
		harness.loginAndSettle(ACCOUNT);
	}

	/** Stops the session and returns the saved record's supplies. */
	private Map<Integer, Long> stopAndGetSupplies()
	{
		harness.endTask();
		Map<Integer, Long> supplies = harness.onlyRecord(ACCOUNT).getSupplies();
		return supplies == null ? Collections.emptyMap() : supplies;
	}

	private static long used(Map<Integer, Long> supplies, int itemId)
	{
		return supplies.getOrDefault(itemId, 0L);
	}

	/** One tick on which a blowpipe shot starts (attack animation at frame 0), then the animation ends. */
	private void blowpipeShot()
	{
		harness.setAnimation(AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK, 0);
		harness.tick();
		harness.setAnimation(-1, 0);
	}

	private void blowpipeShots(int n)
	{
		for (int i = 0; i < n; i++)
		{
			blowpipeShot();
		}
	}

	private boolean dartHintShown()
	{
		final boolean[] shown = new boolean[1];
		harness.onEdt(() -> shown[0] = harness.ui().isDartHintShown());
		return shown[0];
	}

	@Test
	public void blowpipeShotsCountDartsAndScalesOnlyWhileRunning()
	{
		harness.setWorn(EquipmentInventorySlot.WEAPON, ItemID.TOXIC_BLOWPIPE_LOADED, 1);
		harness.chat(ChatMessageType.GAMEMESSAGE, RUNE_DART_CHECK);
		// Before Play: not counted.
		blowpipeShots(3);

		assertTrue(harness.startNow());
		// No cape-slot device: a dart per shot; 2/3 of a scale per shot.
		blowpipeShots(3);
		// A frame other than 0 is the same shot still playing.
		harness.setAnimation(AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK, 5);
		harness.tick();
		harness.setAnimation(-1, 0);
		harness.pause();
		blowpipeShots(3);

		Map<Integer, Long> supplies = stopAndGetSupplies();
		assertEquals(3L, used(supplies, ItemID.RUNE_DART));
		assertEquals(2L, used(supplies, ItemID.SNAKEBOSS_SCALE));
		assertEquals(2, supplies.size());
	}

	@Test
	public void vorkathsHeadSettingAppliesToTheMaxCape()
	{
		harness.config.vorkathsHeadUpgrade = true;
		harness.setWorn(EquipmentInventorySlot.WEAPON, ItemID.TOXIC_BLOWPIPE_LOADED, 1);
		harness.setWorn(EquipmentInventorySlot.CAPE, ItemID.SKILLCAPE_MAX, 1);
		harness.chat(ChatMessageType.GAMEMESSAGE, RUNE_DART_CHECK);
		assertTrue(harness.startNow());
		// 80% saved: 0.2 darts per shot.
		blowpipeShots(10);

		Map<Integer, Long> supplies = stopAndGetSupplies();
		assertEquals(2L, used(supplies, ItemID.RUNE_DART));
		// 10 shots: 20 thirds, 6 whole scales.
		assertEquals(6L, used(supplies, ItemID.SNAKEBOSS_SCALE));
	}

	@Test
	public void tridentCastsCountTheirChargeMaterials()
	{
		harness.setWorn(EquipmentInventorySlot.WEAPON, ItemID.TOTS_CHARGED, 1);
		assertTrue(harness.startNow());
		harness.setAnimation(AnimationID.HUMAN_CASTWAVE_STAFF, 0);
		harness.setSpotAnims(SpotanimID.SLAYER_TOTS_CASTING);
		harness.tick();
		// The wave-staff animation without the trident's graphic is another spell.
		harness.setSpotAnims();
		harness.tick();
		harness.setAnimation(-1, 0);
		harness.tick();

		Map<Integer, Long> supplies = stopAndGetSupplies();
		assertEquals(1L, used(supplies, ItemID.DEATHRUNE));
		assertEquals(1L, used(supplies, ItemID.CHAOSRUNE));
		assertEquals(5L, used(supplies, ItemID.FIRERUNE));
		assertEquals(10L, used(supplies, ItemID.COINS));
		assertEquals(4, supplies.size());
	}

	/** One tick on which a cast starts ({@code animationId} at frame 0 with the cast graphic {@code graphic}). */
	private void cast(int animationId, int graphic)
	{
		harness.setAnimation(animationId, 0);
		harness.setSpotAnims(graphic);
		harness.tick();
		harness.setAnimation(-1, 0);
		harness.setSpotAnims();
	}

	@Test
	public void poweredStaffCastsCountTheirChargeRunes()
	{
		// Blood runes in the inventory are not what the staff uses: they must not change, or be counted twice.
		harness.setInventory(item(ItemID.BLOODRUNE, 100));
		harness.setWorn(EquipmentInventorySlot.WEAPON, ItemID.SANGUINESTI_STAFF, 1);
		harness.tick();
		assertTrue(harness.startNow());
		cast(AnimationID.HUMAN_CASTWAVE_STAFF, SpotanimID.SANGUINESTI_STAFF_CASTING);
		cast(AnimationID.HUMAN_CASTWAVE_STAFF_WALKMERGE, SpotanimID.SANGUINESTI_STAFF_CASTING);
		// Another weapon's graphic is not a Sanguinesti cast.
		cast(AnimationID.HUMAN_CASTWAVE_STAFF, SpotanimID.SLAYER_TOTS_CASTING);

		harness.setWorn(EquipmentInventorySlot.WEAPON, ItemID.TUMEKENS_SHADOW, 1);
		cast(AnimationID.TOA_SOT_CAST_B, SpotanimID.TUMEKENS_SHADOW_CASTING);
		// The wave-staff animation is not a shadow cast.
		cast(AnimationID.HUMAN_CASTWAVE_STAFF, SpotanimID.TUMEKENS_SHADOW_CASTING);
		harness.pause();
		cast(AnimationID.TOA_SOT_CAST_B, SpotanimID.TUMEKENS_SHADOW_CASTING);

		harness.endTask();
		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		final Map<Integer, Long> supplies = record.getSupplies();
		assertEquals("2 per Sanguinesti cast", 4L, used(supplies, ItemID.BLOODRUNE));
		assertEquals("2 per shadow cast", 2L, used(supplies, ItemID.SOULRUNE));
		assertEquals("5 per shadow cast", 5L, used(supplies, ItemID.CHAOSRUNE));
		assertEquals(3, supplies.size());
		// 4 * 300 + 2 * 250 + 5 * 80.
		assertEquals(Long.valueOf(1_200 + 500 + 400), record.getSuppliesGeValue());
	}

	@Test
	public void chargingAPoweredStaffIsNotCountedTwice()
	{
		harness.setInventory(item(ItemID.SANGUINESTI_STAFF_UNCHARGED, 1), item(ItemID.BLOODRUNE, 1_000));
		harness.tick();
		assertTrue(harness.startNow());
		// 500 charges take 1,000 blood runes.
		harness.clickUseOn(ItemID.BLOODRUNE, ItemID.SANGUINESTI_STAFF_UNCHARGED);
		harness.tick();
		harness.setInventory(item(ItemID.SANGUINESTI_STAFF, 1));
		harness.tick();
		harness.showObjectbox("You apply 500 charges to your Sanguinesti staff.");
		harness.ticks(ChargeExclusions.CONFIRM_GRACE_TICKS + 1);
		harness.showObjectbox(null);

		// The shadow's "Charge" option takes its soul and chaos runes.
		harness.setInventory(item(ItemID.SANGUINESTI_STAFF, 1), item(ItemID.TUMEKENS_SHADOW_UNCHARGED, 1),
			item(ItemID.SOULRUNE, 200), item(ItemID.CHAOSRUNE, 500));
		harness.tick();
		harness.clickMenu("Charge", ItemID.TUMEKENS_SHADOW_UNCHARGED);
		harness.setInventory(item(ItemID.SANGUINESTI_STAFF, 1), item(ItemID.TUMEKENS_SHADOW, 1));
		harness.tick();
		harness.showObjectbox("You apply 100 charges to your Tumeken's shadow.");
		harness.ticks(ChargeExclusions.CONFIRM_GRACE_TICKS + 1);
		harness.showObjectbox(null);

		// After the window, a cast counts its charge, and blood runes used from the inventory count again.
		harness.setWorn(EquipmentInventorySlot.WEAPON, ItemID.SANGUINESTI_STAFF, 1);
		harness.setInventory(item(ItemID.TUMEKENS_SHADOW, 1), item(ItemID.BLOODRUNE, 10));
		harness.tick();
		cast(AnimationID.HUMAN_CASTWAVE_STAFF, SpotanimID.SANGUINESTI_STAFF_CASTING);
		harness.setInventory(item(ItemID.TUMEKENS_SHADOW, 1), item(ItemID.BLOODRUNE, 7));
		harness.tick();

		final Map<Integer, Long> supplies = stopAndGetSupplies();
		assertEquals("one cast and 3 from the inventory", 5L, used(supplies, ItemID.BLOODRUNE));
		assertEquals(0L, used(supplies, ItemID.SOULRUNE));
		assertEquals(0L, used(supplies, ItemID.CHAOSRUNE));
		assertEquals(1, supplies.size());
	}

	@Test
	public void loadingAndUnloadingDartsIsNotCountedTwice()
	{
		harness.setInventory(item(ItemID.RUNE_DART, 100), item(ItemID.TOXIC_BLOWPIPE_LOADED, 1));
		harness.tick();
		assertTrue(harness.startNow());
		// Loading the inventory's darts into the blowpipe.
		harness.clickUseOn(ItemID.RUNE_DART, ItemID.TOXIC_BLOWPIPE_LOADED);
		harness.setInventory(item(ItemID.TOXIC_BLOWPIPE_LOADED, 1));
		harness.tick();
		harness.chat(ChatMessageType.GAMEMESSAGE, RUNE_DART_CHECK);
		harness.ticks(ChargeExclusions.CONFIRM_GRACE_TICKS + 1);
		assertEquals(String.valueOf(ItemID.RUNE_DART),
			harness.rsProfileConfig(SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.BLOWPIPE_DART_KEY));

		// Two shots with the blowpipe wielded.
		harness.setInventory();
		harness.setWorn(EquipmentInventorySlot.WEAPON, ItemID.TOXIC_BLOWPIPE_LOADED, 1);
		harness.tick();
		blowpipeShots(2);

		// Unloading the darts back into the inventory is not a return of ammo used.
		harness.clickMenu("Unload", ItemID.TOXIC_BLOWPIPE_LOADED);
		harness.setInventory(item(ItemID.RUNE_DART, 98));
		harness.tick();
		assertNull("unloading forgets the dart type",
			harness.rsProfileConfig(SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.BLOWPIPE_DART_KEY));

		Map<Integer, Long> supplies = stopAndGetSupplies();
		assertEquals(2L, used(supplies, ItemID.RUNE_DART));
		assertEquals(1L, used(supplies, ItemID.SNAKEBOSS_SCALE));
	}

	@Test
	public void chargingATridentIsNotCountedAndTheDialogEndsTheWindow()
	{
		harness.setInventory(item(ItemID.TOTS_UNCHARGED, 1), item(ItemID.DEATHRUNE, 100),
			item(ItemID.CHAOSRUNE, 100), item(ItemID.FIRERUNE, 500));
		harness.tick();
		assertTrue(harness.startNow());
		harness.clickUseOn(ItemID.FIRERUNE, ItemID.TOTS_UNCHARGED);
		// The number input is answered; 100 charges use all the runes.
		harness.tick();
		harness.setInventory(item(ItemID.TOTS_CHARGED, 1));
		harness.tick();
		harness.showObjectbox("You add 100 charges to the weapon.<br>New total: 100");
		harness.ticks(ChargeExclusions.CONFIRM_GRACE_TICKS + 1);
		harness.showObjectbox(null);

		// After the window, runes used from the inventory count again.
		harness.setInventory(item(ItemID.TOTS_CHARGED, 1), item(ItemID.FIRERUNE, 10));
		harness.tick();
		harness.setInventory(item(ItemID.TOTS_CHARGED, 1), item(ItemID.FIRERUNE, 4));
		harness.tick();

		Map<Integer, Long> supplies = stopAndGetSupplies();
		assertEquals(0L, used(supplies, ItemID.DEATHRUNE));
		assertEquals(0L, used(supplies, ItemID.CHAOSRUNE));
		assertEquals(6L, used(supplies, ItemID.FIRERUNE));
	}

	@Test
	public void theDartTypeIsKeptForTheNextRunAndForgottenWhenEmpty()
	{
		// Saved in an earlier run of the plugin, for this RuneScape profile.
		harness.shutDown();
		harness.setRsProfileConfig(SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.BLOWPIPE_DART_KEY,
			String.valueOf(ItemID.RUNE_DART));
		harness.startUp();
		harness.ticks(PluginHarness.SETTLE_TICKS);
		harness.setWorn(EquipmentInventorySlot.WEAPON, ItemID.TOXIC_BLOWPIPE_LOADED, 1);
		assertTrue(harness.startNow());
		blowpipeShots(1);

		// Out of darts: shots count scales only until the type is known again.
		harness.chat(ChatMessageType.GAMEMESSAGE, "Your blowpipe has run out of darts.");
		assertNull(harness.rsProfileConfig(SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.BLOWPIPE_DART_KEY));
		blowpipeShots(2);

		Map<Integer, Long> supplies = stopAndGetSupplies();
		assertEquals(1L, used(supplies, ItemID.RUNE_DART));
		assertEquals(2L, used(supplies, ItemID.SNAKEBOSS_SCALE));
	}

	@Test
	public void theCheckHintSaysWhatToDo()
	{
		assertEquals("Check your blowpipe to count darts.", LiveSessionSection.DART_HINT_TEXT);
	}

	@Test
	public void theCheckHintShowsWhileDartsCannotBeCounted()
	{
		harness.setWorn(EquipmentInventorySlot.WEAPON, ItemID.TOXIC_BLOWPIPE_LOADED, 1);
		assertTrue(harness.startNow());
		harness.tick();
		assertFalse("no shot yet", dartHintShown());
		blowpipeShot();
		assertTrue(dartHintShown());

		harness.pause();
		harness.tick();
		assertFalse("hidden while paused", dartHintShown());
		assertTrue(harness.startNow());
		blowpipeShot();
		assertTrue(dartHintShown());

		// Checking the blowpipe names the darts: the hint goes on the next tick.
		harness.chat(ChatMessageType.GAMEMESSAGE, RUNE_DART_CHECK);
		harness.tick();
		assertFalse(dartHintShown());

		// Unknown again, but the blowpipe is no longer wielded.
		harness.chat(ChatMessageType.GAMEMESSAGE, "Your blowpipe contains no darts.");
		blowpipeShot();
		assertTrue(dartHintShown());
		harness.setWorn(EquipmentInventorySlot.WEAPON, -1, 0);
		harness.tick();
		assertFalse(dartHintShown());

		harness.setWorn(EquipmentInventorySlot.WEAPON, ItemID.TOXIC_BLOWPIPE_LOADED, 1);
		blowpipeShot();
		assertTrue(dartHintShown());
		harness.endTask();
		harness.tick();
		assertFalse("hidden once stopped", dartHintShown());
	}
}
