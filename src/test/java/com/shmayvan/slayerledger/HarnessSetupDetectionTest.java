package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.ui.PluginPanel;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Setup detection through the plugin's events (V2 plan 4.3): each task kill's context (cannon, style, gear focus)
 * from the cannon's shots, the player's attacks and their hitsplats, and the worn equipment, and the task's tags from
 * them, live and on the saved record; and the live tags shown and overridden in the sidebar. The weapons' and armour's
 * stats are the OSRS Wiki's. Nothing here waits for the panel's refresh timer: the panel is refreshed by hand, as the
 * timer would.
 *
 * <p>Timing is the game's (OSRS Wiki, "Hit delay"): an attack starts on the GameTick of tick A, with its XP on that
 * tick; a melee hit lands on A + 1 (the dragon claws' last hits on A + 2), a ranged or magic hit from A + 2, the
 * cannon's on its shot's tick with Ranged XP and no Hitpoints XP. The next attack waits out the weapon's attack speed
 * (a blowpipe 2 ticks, a whip, trident or claws 4, a Barrage 5).</p>
 */
public class HarnessSetupDetectionTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int TASK = 49;
	private static final int DUST_DEVIL = 423;
	private static final long TICK = 600L;

	// Equipment, by made-up ids (no charged weapon or supply of the plugin's).
	private static final int WHIP = 40_001;
	private static final int BLOWPIPE = 40_002;
	private static final int MAGIC_SHORTBOW = 40_003;
	private static final int KODAI_WAND = 40_004;
	private static final int STAFF_OF_THE_DEAD = 40_005;
	private static final int TRIDENT = 40_006;
	private static final int DRAGON_CLAWS = 40_007;
	private static final int ANCIENT_SCEPTRE = 40_008;
	/** Body armour with the prayer bonus in its name's place: {@code BODY_PRAYER + n} has +n. */
	private static final int BODY_PRAYER = 41_000;

	// Attack animations (any but a spell's or eating; the weapon decides).
	private static final int WHIP_ATTACK = AnimationID.SLAYER_ABYSSAL_WHIP_ATTACK;
	private static final int BLOWPIPE_ATTACK = AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK;
	private static final int BOW_ATTACK = AnimationID.HUMAN_BOW;
	private static final int STAFF_BASH = AnimationID.HUMAN_AXE_CHOP;
	private static final int TRIDENT_CAST = AnimationID.HUMAN_CASTWAVE_STAFF;
	private static final int CLAWS_SPEC = AnimationID.HUMAN_DRAGON_CLAWS_SPEC;
	private static final int BARRAGE = AnimationID.ZAROS_VERTICAL_CASTING;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	private final AtomicLong now = new AtomicLong(1_000_000_000L);
	private int nextIndex = 1;
	private int cannonballs;

	private static ItemEquipmentStats weapon(int stab, int slash, int crush, int magic, int range, int rangedStrength)
	{
		return ItemEquipmentStats.builder().slot(EquipmentInventorySlot.WEAPON.getSlotIdx()).astab(stab).aslash(slash)
			.acrush(crush).amagic(magic).arange(range).rstr(rangedStrength).build();
	}

	@Before
	public void setUp()
	{
		harness.setSkillXp(Skill.HITPOINTS, 1_000_000);
		harness.defineEquipment(WHIP, "Abyssal whip", weapon(0, 82, 0, 0, 0, 0));
		harness.defineEquipment(BLOWPIPE, "Toxic blowpipe", weapon(0, 0, 0, 0, 30, 20));
		harness.defineEquipment(MAGIC_SHORTBOW, "Magic shortbow (i)", weapon(0, 0, 0, 0, 75, 0));
		harness.defineEquipment(KODAI_WAND, "Kodai wand", weapon(0, 0, 0, 28, 0, 0));
		harness.defineEquipment(STAFF_OF_THE_DEAD, "Staff of the dead", weapon(55, 70, 0, 17, 0, 0));
		harness.defineEquipment(TRIDENT, "Trident of the swamp", weapon(0, 0, 0, 25, 0, 0));
		harness.defineEquipment(DRAGON_CLAWS, "Dragon claws", weapon(41, 57, -4, 0, 0, 0));
		harness.defineEquipment(ANCIENT_SCEPTRE, "Ancient sceptre", weapon(20, -1, 50, 20, 0, 0));
		harness.startUp();
		harness.setTrackerClock(now::get);
		harness.setInventory();
		harness.setTask(TASK, "Dust devils", 50, 50);
		harness.loginAndSettle(ACCOUNT);
		tick();
	}

	private <T> T onEdt(Supplier<T> read)
	{
		final AtomicReference<T> result = new AtomicReference<>();
		harness.onEdt(() -> result.set(read.get()));
		return result.get();
	}

	private void tick()
	{
		now.addAndGet(TICK);
		harness.tick();
	}

	private void ticks(int n)
	{
		for (int i = 0; i < n; i++)
		{
			tick();
		}
	}

	private NPC devil()
	{
		return harness.npc(nextIndex++, DUST_DEVIL, "Dust devil");
	}

	private void wield(int weaponId)
	{
		harness.setWorn(EquipmentInventorySlot.WEAPON, weaponId, 1);
	}

	/** Wears body armour with {@code prayer} prayer bonus (the only worn item with a prayer bonus). */
	private void wearPrayer(int prayer)
	{
		final int id = BODY_PRAYER + prayer;
		harness.defineEquipment(id, "Body +" + prayer, ItemEquipmentStats.builder()
			.slot(EquipmentInventorySlot.BODY.getSlotIdx()).prayer(prayer).build());
		harness.setWorn(EquipmentInventorySlot.BODY, id, 1);
	}

	/**
	 * The player's attack on {@code target} starts on the next tick: its animation's first frame, with the Hitpoints XP
	 * its damage gains on that tick, then mid-way.
	 */
	private void attack(int animation, NPC target)
	{
		harness.setInteracting(target);
		harness.setAnimation(animation, 0);
		harness.gainXp(Skill.HITPOINTS, 4);
		tick();
		harness.setAnimation(animation, 1);
	}

	/** A melee attack on {@code target} on the next tick, A, its hit landing on A + 1. */
	private void meleeAttack(int animation, NPC target)
	{
		attack(animation, target);
		harness.hitsplat(target, true);
		tick();
	}

	/** A ranged or magic attack on {@code target} on the next tick, its hits on {@code hit} landing two ticks later. */
	private void rangedAttack(int animation, NPC target, NPC... hit)
	{
		attack(animation, target);
		tick();
		for (NPC npc : hit)
		{
			harness.hitsplat(npc, true);
		}
		tick();
	}

	/**
	 * {@code npcs} die and the task count drops by as many on the next tick; the tick after, their kills' contexts are
	 * counted.
	 */
	private void kills(NPC... npcs)
	{
		for (NPC npc : npcs)
		{
			harness.npcDies(npc);
		}
		harness.setRemaining(harness.tracker().getCurrentRemaining() - npcs.length);
		tick();
		tick();
	}

	/** Places the cannon, loaded; the next tick reads it. */
	private void placeCannon()
	{
		cannonballs = 30;
		harness.setVarp(VarPlayerID.DROPCANNON, CannonFire.PLACED);
		harness.setVarp(VarPlayerID.ROCKTHROWER, cannonballs);
		tick();
	}

	/**
	 * The cannon fires at {@code npc} on the next tick: one cannonball fewer, its Ranged XP (no Hitpoints XP), and its
	 * hitsplat, the player's own, on that tick.
	 */
	private void cannonShot(NPC npc)
	{
		harness.setVarp(VarPlayerID.ROCKTHROWER, --cannonballs);
		harness.gainXp(Skill.RANGED, 10);
		harness.hitsplat(npc, true);
		tick();
	}

	private SetupKills contexts()
	{
		return harness.tracker().getSetupKills();
	}

	private SetupTags tags()
	{
		return harness.tracker().getSetupTags();
	}

	// ---- Cannon ----

	@Test
	public void aTaskWithMostKillsByCannonIsACannonTask()
	{
		wield(WHIP);
		placeCannon();
		for (int i = 0; i < 3; i++)
		{
			final NPC devil = devil();
			cannonShot(devil);
			kills(devil);
		}
		// The cannon stops firing; then two kills in melee, more than 10 ticks after its last shot.
		ticks(CannonFire.FIRE_WINDOW_TICKS);
		for (int i = 0; i < 2; i++)
		{
			final NPC devil = devil();
			meleeAttack(WHIP_ATTACK, devil);
			kills(devil);
		}

		assertEquals(5, harness.tracker().getKills());
		assertEquals(Integer.valueOf(3), contexts().getCannonKills());
		assertEquals("the cannon's kills have no style", Collections.singletonMap("MELEE", 2),
			contexts().getStyleKills());
		assertTrue(tags().isCannon());
		assertEquals(SetupStyle.MELEE, tags().getStyle());

		harness.endTask();
		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(Boolean.TRUE, record.getDetectedCannon());
		assertEquals(SetupStyle.MELEE, record.getDetectedStyle());
		assertEquals(Integer.valueOf(3), record.getCannonKills());
	}

	@Test
	public void aBriefCannonSetupIsNoCannonTask()
	{
		wield(WHIP);
		placeCannon();
		final NPC first = devil();
		cannonShot(first);
		kills(first);
		ticks(CannonFire.FIRE_WINDOW_TICKS);
		for (int i = 0; i < 4; i++)
		{
			final NPC devil = devil();
			meleeAttack(WHIP_ATTACK, devil);
			kills(devil);
		}

		assertEquals(Integer.valueOf(1), contexts().getCannonKills());
		assertFalse("1 kill of 5 by cannon", tags().isCannon());
		harness.endTask();
		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(Boolean.FALSE, record.getDetectedCannon());
		assertFalse("grouping follows the Cannon tag, not the cannonballs fired", record.cannonTask());
		assertFalse(record.groupName().endsWith(CannonTasks.SUFFIX));
	}

	@Test
	public void cannonHitsNeverCountAsRangedAndACannonOnlyTaskHasNoStyle()
	{
		// A ranged weapon worn, but the cannon does the killing.
		wield(BLOWPIPE);
		placeCannon();
		for (int i = 0; i < 3; i++)
		{
			// Loading the cannon: an animation, but at no NPC, so no attack; its hits land in the next ticks.
			harness.setInteracting(null);
			harness.setAnimation(AnimationID.HUMAN_PICKUPFLOOR, 0);
			tick();
			harness.setAnimation(AnimationID.HUMAN_PICKUPFLOOR, 1);
			final NPC devil = devil();
			cannonShot(devil);
			cannonShot(devil);
			kills(devil);
		}

		assertEquals(Integer.valueOf(3), contexts().getCannonKills());
		assertTrue("no styled kill", contexts().getStyleKills().isEmpty());
		assertTrue(tags().isCannon());
		assertEquals(SetupStyle.UNKNOWN, tags().getStyle());
		harness.endTask();
		assertEquals(SetupStyle.UNKNOWN, harness.onlyRecord(ACCOUNT).getDetectedStyle());
	}

	// ---- Style ----

	@Test
	public void aBarrageTaskCountsItsSplashOnOtherNpcs()
	{
		wield(KODAI_WAND);
		final NPC target = devil();
		final NPC second = devil();
		final NPC third = devil();
		rangedAttack(AnimationID.ZAROS_VERTICAL_CASTING, target, target, second, third);
		kills(target, second, third);
		assertEquals("the target and its splash", Collections.singletonMap("BARRAGE", 3), contexts().getStyleKills());

		// Cast while walking, and the priority variant.
		final NPC fourth = devil();
		final NPC fifth = devil();
		rangedAttack(AnimationID.ZAROS_VERTICAL_CASTING_WALKMERGE, fourth, fourth, fifth);
		kills(fourth, fifth);
		assertEquals("a walking barrage", Collections.singletonMap("BARRAGE", 5), contexts().getStyleKills());
		final NPC sixth = devil();
		rangedAttack(AnimationID.ZAROS_VERTICAL_CASTING_PRIORITY, sixth, sixth);
		kills(sixth);

		assertEquals(Collections.singletonMap("BARRAGE", 6), contexts().getStyleKills());
		assertEquals(SetupStyle.BARRAGE, tags().getStyle());
	}

	@Test
	public void rushAndBlitzAreMagic()
	{
		// A staff with melee bonuses: its casts are Magic by their animation, its bash Melee.
		wield(STAFF_OF_THE_DEAD);
		final NPC first = devil();
		rangedAttack(AnimationID.ZAROS_CASTING, first, first);
		kills(first);
		final NPC second = devil();
		rangedAttack(AnimationID.ZAROS_CASTING_WALKMERGE, second, second);
		kills(second);
		final NPC third = devil();
		meleeAttack(STAFF_BASH, third);
		kills(third);

		assertEquals(2, contexts().styleKills(SetupStyle.MAGIC));
		assertEquals(1, contexts().styleKills(SetupStyle.MELEE));
		assertEquals(0, contexts().styleKills(SetupStyle.BARRAGE));
		assertEquals(SetupStyle.MAGIC, tags().getStyle());
	}

	@Test
	public void aMeleeTaskWithAWeaponSwapMidFight()
	{
		// A dart on A, a switch to the whip before it lands, then the whip as soon as the blowpipe allows (A + 2), and
		// again 4 ticks later: 1 dart and 2 whip hits, Melee.
		wield(BLOWPIPE);
		final NPC first = devil();
		attack(BLOWPIPE_ATTACK, first);
		wield(WHIP);
		tick();
		harness.hitsplat(first, true);
		meleeAttack(WHIP_ATTACK, first);
		ticks(2);
		meleeAttack(WHIP_ATTACK, first);
		kills(first);
		assertEquals("2 whip hits and 1 dart: Melee", Collections.singletonMap("MELEE", 1), contexts().getStyleKills());

		// A dart at one NPC, a switch to the whip, then a whip attack at another before the dart lands: the dart's hit is
		// Ranged, a Ranged kill, though the latest attack was in melee.
		wield(BLOWPIPE);
		final NPC second = devil();
		final NPC third = devil();
		attack(BLOWPIPE_ATTACK, second);
		wield(WHIP);
		tick();
		harness.hitsplat(second, true);
		meleeAttack(WHIP_ATTACK, third);
		kills(second);
		assertEquals(1, contexts().styleKills(SetupStyle.RANGED));

		ticks(1);
		meleeAttack(WHIP_ATTACK, third);
		kills(third);

		assertEquals(2, contexts().styleKills(SetupStyle.MELEE));
		assertEquals(SetupStyle.MELEE, tags().getStyle());
		harness.endTask();
		assertEquals(SetupStyle.MELEE, harness.onlyRecord(ACCOUNT).getDetectedStyle());
	}

	// ---- Hit timing ----

	@Test
	public void aDartsHitOnTheTickOfAWhipAttackAfterASwapIsRanged()
	{
		// A blowpipe dart on A, a swap, then a whip attack on the same NPC on A + 2, as the dart lands and kills it.
		wield(BLOWPIPE);
		final NPC devil = devil();
		attack(BLOWPIPE_ATTACK, devil);
		wield(WHIP);
		tick();
		harness.hitsplat(devil, true);
		harness.npcDies(devil);
		harness.setRemaining(harness.tracker().getCurrentRemaining() - 1);
		attack(WHIP_ATTACK, devil);
		tick();

		assertEquals("the dart's hit, not the whip's", Collections.singletonMap("RANGED", 1),
			contexts().getStyleKills());
	}

	@Test
	public void aWhipsHitAfterATridentsCastIsMelee()
	{
		// A trident's cast on A lands on A + 2; a whip on A + 4, when the trident allows, lands on A + 5; another whip
		// 4 ticks later kills it: 1 Magic and 2 Melee hits.
		wield(TRIDENT);
		final NPC devil = devil();
		attack(TRIDENT_CAST, devil);
		tick();
		harness.hitsplat(devil, true);
		tick();
		wield(WHIP);
		tick();
		meleeAttack(WHIP_ATTACK, devil);
		ticks(2);
		meleeAttack(WHIP_ATTACK, devil);
		kills(devil);

		assertEquals(Collections.singletonMap("MELEE", 1), contexts().getStyleKills());
	}

	@Test
	public void theDragonClawsHitsOnTheTwoTicksAfterTheirSpecialAttackAreMelee()
	{
		// A dart on A - 2 lands on A; the claws' special attack on A, when the blowpipe allows, hits twice on A + 1 and
		// twice on A + 2, and kills.
		wield(BLOWPIPE);
		final NPC devil = devil();
		attack(BLOWPIPE_ATTACK, devil);
		tick();
		harness.hitsplat(devil, true);
		wield(DRAGON_CLAWS);
		attack(CLAWS_SPEC, devil);
		harness.hitsplat(devil, true);
		harness.hitsplat(devil, true);
		tick();
		harness.hitsplat(devil, true);
		harness.hitsplat(devil, true);
		kills(devil);

		assertEquals("1 dart and 4 claw hits", Collections.singletonMap("MELEE", 1), contexts().getStyleKills());
	}

	// ---- Cannon hits beside the player's attacks ----

	@Test
	public void theCannonsHitsOnOtherNpcsWhileBlowpipingHaveNoStyle()
	{
		wield(BLOWPIPE);
		placeCannon();
		final NPC target = devil();
		final NPC other = devil();
		attack(BLOWPIPE_ATTACK, target);
		cannonShot(other);
		// The dart lands as the next one starts; the cannon keeps firing at the other NPC within the darts' windows.
		harness.hitsplat(target, true);
		attack(BLOWPIPE_ATTACK, target);
		cannonShot(other);
		harness.hitsplat(target, true);
		cannonShot(other);
		kills(target, other);

		assertEquals("the target's kill alone", Collections.singletonMap("RANGED", 1), contexts().getStyleKills());
		assertEquals(Integer.valueOf(2), contexts().getCannonKills());
	}

	@Test
	public void theCannonsHitsOnOtherNpcsWhileBarragingHaveNoStyleButTheSplashDoes()
	{
		wield(KODAI_WAND);
		placeCannon();
		final NPC target = devil();
		final NPC splashed = devil();
		final NPC other = devil();
		attack(BARRAGE, target);
		cannonShot(other);
		// The barrage lands on its target and the NPC beside it; the cannon hits another NPC on the next ticks, within
		// the barrage's window but not with its hits.
		harness.hitsplat(target, true);
		harness.hitsplat(splashed, true);
		tick();
		cannonShot(other);
		cannonShot(other);
		kills(target, splashed, other);

		assertEquals("the target and its splash", Collections.singletonMap("BARRAGE", 2), contexts().getStyleKills());
		assertEquals(Integer.valueOf(3), contexts().getCannonKills());
	}

	@Test
	public void theCannonsHitsOnOtherNpcsWhileMeleeingHaveNoStyle()
	{
		wield(WHIP);
		placeCannon();
		final NPC target = devil();
		final NPC other = devil();
		attack(WHIP_ATTACK, target);
		// The cannon hits another NPC on the tick of the whip's hit, and on the next.
		harness.hitsplat(target, true);
		cannonShot(other);
		cannonShot(other);
		kills(target, other);

		assertEquals(Collections.singletonMap("MELEE", 1), contexts().getStyleKills());
	}

	@Test
	public void aDecayedCannonEmptyingIsNoShot()
	{
		wield(WHIP);
		placeCannon();
		// The cannon decays: core CannonPlugin then takes it as inactive, and its cannonballs read 0.
		harness.setVarbit(VarbitID.MCANNON_DECAYED, 1);
		harness.setVarp(VarPlayerID.ROCKTHROWER, 0);
		tick();
		final NPC devil = devil();
		meleeAttack(WHIP_ATTACK, devil);
		kills(devil);

		assertEquals(Integer.valueOf(0), contexts().getCannonKills());
	}

	// ---- Blocks ----

	@Test
	public void aBlockBetweenACastAndItsHitsDoesNotTakeThem()
	{
		// The Ancient sceptre's stats read Melee: a block taken for an attack would make the barrage's hits Melee. A
		// monster hits the player the tick after the cast, while the cannon fires (Ranged XP, no Hitpoints XP).
		wield(ANCIENT_SCEPTRE);
		placeCannon();
		final NPC target = devil();
		final NPC splashed = devil();
		final NPC other = devil();
		attack(BARRAGE, target);
		harness.setAnimation(AnimationID.HUMAN_STAFF_BLOCK, 0);
		cannonShot(other);
		harness.setAnimation(AnimationID.HUMAN_STAFF_BLOCK, 1);
		tick();
		harness.hitsplat(target, true);
		harness.hitsplat(splashed, true);
		kills(target, splashed);
		assertEquals(Collections.singletonMap("BARRAGE", 2), contexts().getStyleKills());

		// The kodai wand's stats read Magic: a block taken for an attack would make them Magic.
		ticks(CannonFire.FIRE_WINDOW_TICKS);
		wield(KODAI_WAND);
		final NPC second = devil();
		attack(BARRAGE, second);
		harness.setAnimation(AnimationID.HUMAN_STAFF_BLOCK, 0);
		tick();
		harness.setAnimation(AnimationID.HUMAN_STAFF_BLOCK, 1);
		tick();
		harness.hitsplat(second, true);
		kills(second);
		assertEquals(Collections.singletonMap("BARRAGE", 3), contexts().getStyleKills());
	}

	// ---- The final kill ----

	@Test
	public void theFinalKillsHitsOnTheTickAfterTheCountDropStillGiveItsStyle()
	{
		harness.setTask(TASK, "Dust devils", 2, 2);
		tick();
		wield(BLOWPIPE);
		final NPC first = devil();
		rangedAttack(BLOWPIPE_ATTACK, first, first);
		kills(first);
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());

		// A dart at the last one; the count drops to 0 and the task completes the tick before its hit and death show.
		final NPC last = devil();
		attack(BLOWPIPE_ATTACK, last);
		harness.completeTask();
		tick();
		harness.hitsplat(last, true);
		harness.npcDies(last);
		ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS + 1);

		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals("both kills Ranged", 2, SetupKills.from(record).styleKills(SetupStyle.RANGED));
	}

	@Test
	public void aRangedTaskWithABowAndABlowpipe()
	{
		wield(MAGIC_SHORTBOW);
		final NPC first = devil();
		rangedAttack(BOW_ATTACK, first, first);
		kills(first);
		wield(BLOWPIPE);
		for (int i = 0; i < 2; i++)
		{
			final NPC devil = devil();
			rangedAttack(BLOWPIPE_ATTACK, devil, devil);
			kills(devil);
		}

		assertEquals(Collections.singletonMap("RANGED", 3), contexts().getStyleKills());
		assertEquals(SetupStyle.RANGED, tags().getStyle());
	}

	@Test
	public void aTaskMixed45To40To15IsMixed()
	{
		wield(WHIP);
		for (int i = 0; i < 9; i++)
		{
			final NPC devil = devil();
			meleeAttack(WHIP_ATTACK, devil);
			kills(devil);
		}
		wield(MAGIC_SHORTBOW);
		for (int i = 0; i < 8; i++)
		{
			final NPC devil = devil();
			rangedAttack(BOW_ATTACK, devil, devil);
			kills(devil);
		}
		wield(KODAI_WAND);
		final NPC target = devil();
		final NPC second = devil();
		final NPC third = devil();
		rangedAttack(AnimationID.ZAROS_VERTICAL_CASTING, target, target, second, third);
		kills(target, second, third);

		assertEquals(9, contexts().styleKills(SetupStyle.MELEE));
		assertEquals(8, contexts().styleKills(SetupStyle.RANGED));
		assertEquals(3, contexts().styleKills(SetupStyle.BARRAGE));
		assertEquals("no style reaches half", SetupStyle.MIXED, tags().getStyle());
	}

	// ---- Gear focus ----

	@Test
	public void eachKillsGearFocusComesFromTheWornPrayerBonusAtItsCutOffs()
	{
		wield(WHIP);
		// A worn item without stats adds nothing.
		harness.setWorn(EquipmentInventorySlot.RING, 49_999, 1);
		for (int prayer : new int[]{SetupDetection.PRAYER_FOCUS_MIN, SetupDetection.PRAYER_FOCUS_MIN - 1,
			SetupDetection.STRENGTH_FOCUS_MAX + 1, SetupDetection.STRENGTH_FOCUS_MAX})
		{
			wearPrayer(prayer);
			final NPC devil = devil();
			meleeAttack(WHIP_ATTACK, devil);
			kills(devil);
		}

		assertEquals(1, contexts().gearKills(GearFocus.PRAYER));
		assertEquals("+24 and +15", 2, contexts().gearKills(GearFocus.MIXED));
		assertEquals(1, contexts().gearKills(GearFocus.STRENGTH));
		assertEquals(Integer.valueOf(14), contexts().getPrayerBonusMin());
		assertEquals(Integer.valueOf(25), contexts().getPrayerBonusMax());
		assertEquals("the focus of most kills", GearFocus.MIXED, tags().getGear());
	}

	@Test
	public void aTieBetweenGearFocusesIsMixed()
	{
		wield(WHIP);
		for (int prayer : new int[]{37, 37, 7, 7})
		{
			wearPrayer(prayer);
			final NPC devil = devil();
			meleeAttack(WHIP_ATTACK, devil);
			kills(devil);
		}
		assertEquals(GearFocus.MIXED, tags().getGear());

		final NPC fifth = devil();
		meleeAttack(WHIP_ATTACK, fifth);
		kills(fifth);
		assertEquals(GearFocus.STRENGTH, tags().getGear());
		harness.endTask();
		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(GearFocus.STRENGTH, record.getDetectedGear());
		assertEquals(Integer.valueOf(7), record.getPrayerBonusMin());
		assertEquals(Integer.valueOf(37), record.getPrayerBonusMax());
	}

	// ---- Live display and overrides ----

	/** Refreshes the panel, lets the sprites it asked for arrive, then reads it. */
	private <T> T refreshed(java.util.function.Function<PanelProbe, T> read)
	{
		harness.onEdt(() -> harness.panel().refresh());
		harness.drain();
		return onEdt(() -> read.apply(harness.ui()));
	}

	/** The toggle's text as {@link PanelProbe#getLiveSetupToggle} reads a sprite: {@code Style | sprite 1 18x16 | v}. */
	private static String sprite(String name, SetupTagIcons.Icon icon, String value)
	{
		return name + " | sprite " + icon.getId() + " 18x16 | " + value;
	}

	@Test
	public void theLiveTagsAreSpriteTogglesSetFromTheirMenusAndReachTheRecord()
	{
		wield(WHIP);
		wearPrayer(7);
		assertFalse("hidden without a session", refreshed(PanelProbe::isLiveSetupShown));
		final NPC first = devil();
		meleeAttack(WHIP_ATTACK, first);
		kills(first);

		// Each toggle shows its effective value's sprite; no cannon has no sprite, so its word.
		assertTrue(refreshed(PanelProbe::isLiveSetupShown));
		assertEquals(Arrays.asList("Cannon | - 18x16 | No", sprite("Style", SetupTagIcons.Icon.MELEE, "Melee"),
			sprite("Gear", SetupTagIcons.Icon.STRENGTH, "Strength")), refreshed(ui -> Arrays.asList(
			ui.getLiveSetupToggle("cannon"), ui.getLiveSetupToggle("style"), ui.getLiveSetupToggle("gear"))));
		assertFalse("nothing set yet", refreshed(ui -> ui.isLiveSetupMarked("style")));
		final String styleTip = refreshed(ui -> ui.getLiveSetupTooltip("style"));
		assertEquals("<html>Style: Melee<br>Detected<br>Kills by style: Melee 1<br>" + SetupToggles.HINT + "</html>",
			styleTip);

		// A click opens the menu: the detected value first, the current choice selected.
		assertTrue("filled when it opens", onEdt(() -> harness.ui().getLiveSetupMenu("style")).isEmpty());
		assertEquals(Arrays.asList("* Detected: Melee", "Melee", "Ranged", "Barrage", "Magic", "Mixed"),
			onEdt(() -> harness.ui().clickLiveSetup("style")));
		// The keyboard opens it too: Tab reaches the toggle, Space presses it.
		assertTrue(onEdt(() -> harness.ui().isLiveSetupFocusable("gear")));
		assertEquals(Arrays.asList("* Detected: Strength", "Prayer", "Strength", "Mixed"),
			onEdt(() -> harness.ui().pressSpaceOnLiveSetup("gear")));

		harness.onEdt(() ->
		{
			harness.ui().chooseLiveSetup("style", "Barrage");
			harness.ui().chooseLiveSetup("cannon", "Yes");
		});
		harness.drain();
		assertEquals(SetupOverrides.of(true, SetupStyle.BARRAGE, null), harness.tracker().getSetupOverrides());
		// Shown at once, with the corner mark, and the tooltip says so too.
		assertEquals(Arrays.asList(SetupTagIcons.Icon.CANNON.getId() + " 18x16", "sprite "
			+ SetupTagIcons.Icon.BARRAGE.getId() + " 18x16"), onEdt(() -> Arrays.asList(
			harness.ui().getLiveSetupToggle("cannon").split(" \\| ")[1],
			harness.ui().getLiveSetupToggle("style").split(" \\| ")[1])));
		assertEquals(Arrays.asList(true, true, false), onEdt(() -> Arrays.asList(harness.ui().isLiveSetupMarked(
			"cannon"), harness.ui().isLiveSetupMarked("style"), harness.ui().isLiveSetupMarked("gear"))));
		assertEquals(sprite("Style", SetupTagIcons.Icon.BARRAGE, "Barrage, set by you"),
			refreshed(ui -> ui.getLiveSetupToggle("style")));
		final List<String> tooltips = refreshed(ui -> Arrays.asList(ui.getLiveSetupTooltip("cannon"),
			ui.getLiveSetupTooltip("style"), ui.getLiveSetupTooltip("gear")));
		assertTrue(tooltips.get(0), tooltips.get(0).startsWith(
			"<html>Cannon: Yes<br>Set by you; detected No<br>Cannon kills: 0 of 1<br>"));
		assertTrue(tooltips.get(1), tooltips.get(1).startsWith(
			"<html>Style: Barrage<br>Set by you; detected Melee<br>Kills by style: Melee 1<br>"));
		assertTrue(tooltips.get(2), tooltips.get(2).startsWith(
			"<html>Gear: Strength<br>Detected<br>Kills by gear: Strength 1<br>Prayer bonus: +7<br>"));
		assertEquals(Arrays.asList("Detected: No", "* Yes", "No"), onEdt(() -> harness.ui().clickLiveSetup("cannon")));

		// "Detected" clears the override: back to the detected cannon, unmarked.
		harness.onEdt(() -> harness.ui().chooseLiveSetup("cannon", "Detected: No"));
		harness.drain();
		assertEquals(SetupOverrides.of(null, SetupStyle.BARRAGE, null), harness.tracker().getSetupOverrides());
		assertEquals("Cannon | - 18x16 | No", refreshed(ui -> ui.getLiveSetupToggle("cannon")));
		assertFalse(onEdt(() -> harness.ui().isLiveSetupMarked("cannon")));
		assertTrue(refreshed(ui -> ui.isLiveSetupMarked("style")));

		harness.endTask();
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(SetupStyle.MELEE, record.getDetectedStyle());
		assertEquals(SetupOverrides.of(null, SetupStyle.BARRAGE, null), SetupOverrides.from(record));

		// A pick just after the session was saved, before the sidebar caught up, goes to its record.
		harness.onEdt(() -> harness.ui().chooseLiveSetup("gear", "Prayer"));
		harness.drain();
		record = harness.onlyRecord(ACCOUNT);
		assertEquals(SetupOverrides.of(null, SetupStyle.BARRAGE, GearFocus.PRAYER), SetupOverrides.from(record));
		assertFalse("hidden once the session ended", onEdt(() ->
		{
			harness.panel().refresh();
			return harness.ui().isLiveSetupShown();
		}));
	}

	@Test
	public void theLiveSetupTogglesFitTheCardAt225PxAndAreRedrawnOnlyWhenTheTagsChange()
	{
		wield(WHIP);
		final NPC first = devil();
		meleeAttack(WHIP_ATTACK, first);
		kills(first);

		final int[] builds = new int[3];
		harness.onEdt(() -> harness.panel().refresh());
		harness.drain();
		final int meleeSprites = harness.spriteImageRequests();
		harness.onEdt(() ->
		{
			builds[0] = harness.ui().getLiveSetupBuildCount();
			// The refresh timer, with the tags unchanged.
			harness.panel().refresh();
			harness.panel().refresh();
			builds[1] = harness.ui().getLiveSetupBuildCount();
		});
		harness.drain();
		assertEquals(builds[0], builds[1]);

		// At the sidebar's 225 px the card has 199 px inside: the three toggles fit in one row at their own widths.
		final int width = onEdt(() -> harness.ui().getLiveSetupPreferredWidth());
		assertTrue("wants " + width, width <= 199);
		final List<Integer> layout = onEdt(() ->
		{
			harness.ui().layOutLiveCard();
			return harness.ui().getLiveSetupLayout();
		});
		assertEquals("the row is the card's inside", Integer.valueOf(199), layout.get(0));
		assertEquals("the first toggle at the card's left edge", Integer.valueOf(0), layout.get(1));
		for (int toggle = 0; toggle < 3; toggle++)
		{
			final int x = layout.get(1 + 3 * toggle);
			final int shown = layout.get(2 + 3 * toggle);
			assertEquals("not squeezed: " + layout, layout.get(3 + 3 * toggle), Integer.valueOf(shown));
			assertTrue("inside the row: " + layout, x >= 0 && x + shown <= layout.get(0));
		}
		final int widest = onEdt(() -> harness.ui().getLiveSectionPreferredWidth());
		assertTrue("no sideways scroll: the live section wants " + widest,
			widest <= PluginPanel.PANEL_WIDTH - 2 * PluginPanel.BORDER_OFFSET);

		// Two ranged kills: the detected style changes, so the toggles are redrawn, once.
		wield(MAGIC_SHORTBOW);
		for (int i = 0; i < 2; i++)
		{
			final NPC devil = devil();
			rangedAttack(BOW_ATTACK, devil, devil);
			kills(devil);
		}
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			harness.panel().refresh();
			builds[2] = harness.ui().getLiveSetupBuildCount();
		});
		assertEquals(builds[1] + 1, builds[2]);
		assertEquals(sprite("Style", SetupTagIcons.Icon.RANGED, "Ranged"),
			refreshed(ui -> ui.getLiveSetupToggle("style")));
		final int rangedSprites = harness.spriteImageRequests();
		assertEquals("Ranged's sprite is asked for once (Strength gear's was already there)", meleeSprites + 1,
			rangedSprites);

		// Two more melee kills (3 of 5): Melee again. Its sprite was kept, so nothing is asked for.
		wield(WHIP);
		for (int i = 0; i < 2; i++)
		{
			final NPC devil = devil();
			meleeAttack(WHIP_ATTACK, devil);
			kills(devil);
		}
		assertEquals(sprite("Style", SetupTagIcons.Icon.MELEE, "Melee"),
			refreshed(ui -> ui.getLiveSetupToggle("style")));
		assertEquals("each sprite is asked for once", rangedSprites, harness.spriteImageRequests());
	}

	@Test
	public void anOverrideSetJustAsTheTaskCompletesReachesItsRecord()
	{
		// A new assignment of 2.
		harness.setTask(TASK, "Dust devils", 2, 2);
		tick();
		wield(WHIP);
		final NPC first = devil();
		meleeAttack(WHIP_ATTACK, first);
		kills(first);
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		harness.onEdt(() -> harness.panel().refresh());

		final NPC last = devil();
		meleeAttack(WHIP_ATTACK, last);
		harness.npcDies(last);
		harness.completeTask();
		tick();
		// Held for late loot; the sidebar has not caught up, so its toggles still belong to that session.
		harness.onEdt(() -> harness.ui().chooseLiveSetup("style", "Ranged"));
		harness.drain();
		ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS + 1);

		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals(SetupStyle.RANGED, record.getStyleOverride());
		assertEquals("the final kill's context reached it too", 2, SetupKills.from(record).styleKills(SetupStyle.MELEE));
		assertEquals(SetupStyle.MELEE, record.getDetectedStyle());
	}
}
