package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import net.runelite.api.Skill;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * The style of one of the player's attacks ({@link AttackStyles}): the cast animations, and each weapon's style
 * from its equipment stats. The stats are the OSRS Wiki's Infobox Bonuses (stab, slash, crush, magic and ranged attack,
 * ranged strength), read on 2026-11-09, which RuneLite's item stats copy.
 */
public class AttackStylesTest
{
	private static SetupStyle weapon(int stab, int slash, int crush, int magic, int range, int rangedStrength)
	{
		return AttackStyles.weaponStyle(stab, slash, crush, magic, range, rangedStrength);
	}

	@Test
	public void theCastAnimationsAreTheGameValuesThePlanNames()
	{
		assertEquals(1979, AnimationID.ZAROS_VERTICAL_CASTING);
		assertEquals(10092, AnimationID.ZAROS_VERTICAL_CASTING_WALKMERGE);
		assertEquals(7753, AnimationID.ZAROS_VERTICAL_CASTING_PRIORITY);
		assertEquals(1978, AnimationID.ZAROS_CASTING);
		assertEquals(10091, AnimationID.ZAROS_CASTING_WALKMERGE);
	}

	@Test
	public void burstAndBarrageCastsAreBarrageWhateverIsWielded()
	{
		for (int animation : new int[]{1979, 10092, 7753})
		{
			assertEquals(SetupStyle.BARRAGE, AttackStyles.attackStyle(animation, SetupStyle.MAGIC));
			assertEquals("a staff with melee bonuses", SetupStyle.BARRAGE,
				AttackStyles.attackStyle(animation, SetupStyle.MELEE));
			assertEquals("the weapon's stats not loaded", SetupStyle.BARRAGE, AttackStyles.attackStyle(animation, null));
			assertTrue(AttackStyles.isSpell(animation));
		}
	}

	@Test
	public void rushBlitzAndOtherSpellsAreMagic()
	{
		// Rush and Blitz, standing and walking.
		for (int animation : new int[]{1978, 10091})
		{
			assertEquals(SetupStyle.MAGIC, AttackStyles.attackStyle(animation, SetupStyle.MELEE));
		}
		// A wave (also a trident's cast), a surge, Iban Blast, Magic Dart, a god spell and an Arceuus demonbane.
		for (int animation : new int[]{AnimationID.HUMAN_CASTWAVE_STAFF, AnimationID.HUMAN_CAST_SURGE,
			AnimationID.HUMAN_CAST_SURGE_WALKMERGE, AnimationID.HUMAN_CASTIBANBLAST, AnimationID.SLAYER_MAGICDART_CAST,
			AnimationID.HUMAN_CASTING, AnimationID.HUMAN_SPELLCAST_DEMONBANE})
		{
			assertEquals(SetupStyle.MAGIC, AttackStyles.attackStyle(animation, SetupStyle.MELEE));
		}
	}

	@Test
	public void anyOtherAnimationIsTheWeaponsStyle()
	{
		final int whipAttack = 1658;
		assertEquals(SetupStyle.MELEE, AttackStyles.attackStyle(whipAttack, SetupStyle.MELEE));
		assertEquals(SetupStyle.RANGED, AttackStyles.attackStyle(whipAttack, SetupStyle.RANGED));
		assertNull("no style without the weapon's", AttackStyles.attackStyle(whipAttack, null));
		assertFalse(AttackStyles.isSpell(whipAttack));
	}

	@Test
	public void noAnimationAndEatingAreNeverAttacks()
	{
		assertFalse(AttackStyles.mayBeAttack(-1));
		assertFalse(AttackStyles.mayBeAttack(AnimationID.HUMAN_EAT));
		assertTrue(AttackStyles.mayBeAttack(1658));
		assertTrue(AttackStyles.mayBeAttack(1979));
	}

	@Test
	public void meleeWeaponsAreMelee()
	{
		// stab, slash, crush, magic, range, ranged strength
		assertEquals("Abyssal whip", SetupStyle.MELEE, weapon(0, 82, 0, 0, 0, 0));
		assertEquals("Abyssal tentacle", SetupStyle.MELEE, weapon(0, 90, 0, 0, 0, 0));
		assertEquals("Scythe of vitur", SetupStyle.MELEE, weapon(70, 125, 30, -6, 0, 0));
		assertEquals("Ghrazi rapier", SetupStyle.MELEE, weapon(100, 55, 0, 0, 0, 0));
		assertEquals("Osmumten's fang", SetupStyle.MELEE, weapon(105, 75, 0, 0, 0, 0));
		assertEquals("Blade of saeldor", SetupStyle.MELEE, weapon(55, 100, 0, 0, 0, 0));
		assertEquals("Dragon hunter lance", SetupStyle.MELEE, weapon(85, 65, 65, 0, 0, 0));
		assertEquals("Abyssal bludgeon", SetupStyle.MELEE, weapon(0, 0, 102, 0, 0, 0));
		assertEquals("Armadyl godsword", SetupStyle.MELEE, weapon(0, 132, 80, 0, 0, 0));
		assertEquals("Dragon scimitar", SetupStyle.MELEE, weapon(8, 67, -2, 0, 0, 0));
		assertEquals("Arclight", SetupStyle.MELEE, weapon(10, 38, 0, 0, 0, 0));
		assertEquals("Emberlight", SetupStyle.MELEE, weapon(63, 70, 0, 0, 0, 0));
		assertEquals("Keris partisan", SetupStyle.MELEE, weapon(58, -2, 57, 2, 0, 0));
		assertEquals("Voidwaker", SetupStyle.MELEE, weapon(70, 80, -2, 5, 0, 0));
		assertEquals("Inquisitor's mace", SetupStyle.MELEE, weapon(52, -4, 102, 0, 0, 0));
		assertEquals("Soulreaper axe", SetupStyle.MELEE, weapon(28, 134, 66, 0, 0, 0));
		assertEquals("Noxious halberd", SetupStyle.MELEE, weapon(80, 132, 0, 0, 0, 0));
		assertEquals("Elder maul", SetupStyle.MELEE, weapon(0, 0, 135, 0, 0, 0));
		assertEquals("Dragon claws", SetupStyle.MELEE, weapon(41, 57, -4, 0, 0, 0));
		assertEquals("Dragon warhammer", SetupStyle.MELEE, weapon(-4, -4, 95, -4, 0, 0));
		assertEquals("Zamorakian hasta", SetupStyle.MELEE, weapon(85, 65, 65, 0, 0, 0));
		assertEquals("Dual macuahuitl", SetupStyle.MELEE, weapon(115, -4, 121, -4, 0, 0));
	}

	@Test
	public void staffsWithMeleeBonusesAreMeleeAndTheirCastsMagic()
	{
		assertEquals("Staff of the dead", SetupStyle.MELEE, weapon(55, 70, 0, 17, 0, 0));
		assertEquals("Toxic staff of the dead", SetupStyle.MELEE, weapon(55, 70, 0, 25, 0, 0));
		assertEquals("Staff of light", SetupStyle.MELEE, weapon(55, 70, 0, 17, 0, 0));
		assertEquals("Ahrim's staff", SetupStyle.MELEE, weapon(12, -1, 65, 15, 0, 0));
		assertEquals("Ancient staff", SetupStyle.MELEE, weapon(10, -1, 40, 15, 0, 0));
		assertEquals("Iban's staff", SetupStyle.MELEE, weapon(10, -1, 40, 10, 0, 0));
		assertEquals("Slayer's staff", SetupStyle.MELEE, weapon(7, -1, 25, 12, 0, 0));
		// The OSRS Wiki's bonuses, read on 2026-11-09: so its block, if it read as an attack, would be Melee.
		assertEquals("Ancient sceptre", SetupStyle.MELEE, weapon(20, -1, 50, 20, 0, 0));
		// Their autocast spells are told by the cast animation: a surge from Ahrim's staff, Iban Blast, Magic Dart.
		assertEquals(SetupStyle.MAGIC, AttackStyles.attackStyle(AnimationID.HUMAN_CAST_SURGE, SetupStyle.MELEE));
		assertEquals(SetupStyle.MAGIC, AttackStyles.attackStyle(AnimationID.HUMAN_CASTIBANBLAST, SetupStyle.MELEE));
		assertEquals(SetupStyle.MAGIC, AttackStyles.attackStyle(AnimationID.SLAYER_MAGICDART_CAST, SetupStyle.MELEE));
	}

	@Test
	public void rangedWeaponsAreRanged()
	{
		assertEquals("Toxic blowpipe (charged)", SetupStyle.RANGED, weapon(0, 0, 0, 0, 30, 20));
		assertEquals("Toxic blowpipe (empty)", SetupStyle.RANGED, weapon(0, 0, 0, 0, 30, 0));
		assertEquals("Twisted bow", SetupStyle.RANGED, weapon(0, 0, 0, 0, 70, 20));
		assertEquals("Bow of faerdhinen", SetupStyle.RANGED, weapon(0, 0, 0, 0, 128, 106));
		assertEquals("Magic shortbow (i)", SetupStyle.RANGED, weapon(0, 0, 0, 0, 75, 0));
		assertEquals("Crystal bow", SetupStyle.RANGED, weapon(0, 0, 0, 0, 100, 78));
		assertEquals("Dark bow", SetupStyle.RANGED, weapon(0, 0, 0, 0, 95, 0));
		assertEquals("Venator bow", SetupStyle.RANGED, weapon(0, 0, 0, 0, 90, 25));
		assertEquals("Webweaver bow", SetupStyle.RANGED, weapon(0, 0, 0, 0, 85, 65));
		assertEquals("Rune crossbow", SetupStyle.RANGED, weapon(0, 0, 0, 0, 90, 0));
		assertEquals("Armadyl crossbow", SetupStyle.RANGED, weapon(0, 0, 0, 0, 100, 0));
		assertEquals("Dragon hunter crossbow", SetupStyle.RANGED, weapon(0, 0, 0, 0, 95, 0));
		assertEquals("Zaryte crossbow", SetupStyle.RANGED, weapon(0, 0, 0, 0, 110, 0));
		assertEquals("Heavy ballista", SetupStyle.RANGED, weapon(0, 0, 0, 0, 125, 15));
		assertEquals("Dragon dart: no ranged attack bonus, only ranged strength", SetupStyle.RANGED,
			weapon(0, 0, 0, 0, 0, 35));
		assertEquals("Dragon knife", SetupStyle.RANGED, weapon(0, 0, 0, 0, 28, 30));
		assertEquals("Red chinchompa", SetupStyle.RANGED, weapon(0, 0, 0, 0, 70, 15));
		assertEquals("Black chinchompa", SetupStyle.RANGED, weapon(0, 0, 0, 0, 80, 30));
		assertEquals("Eclipse atlatl: ranged with melee strength", SetupStyle.RANGED, weapon(0, 0, 0, 0, 87, 0));
	}

	@Test
	public void tridentsPoweredStavesAndWandsAreMagic()
	{
		assertEquals("Trident of the seas", SetupStyle.MAGIC, weapon(0, 0, 0, 15, 0, 0));
		assertEquals("Trident of the swamp", SetupStyle.MAGIC, weapon(0, 0, 0, 25, 0, 0));
		assertEquals("Sanguinesti staff", SetupStyle.MAGIC, weapon(0, 0, 0, 25, -4, 0));
		assertEquals("Tumeken's shadow", SetupStyle.MAGIC, weapon(0, 0, 0, 35, 0, 0));
		assertEquals("Accursed sceptre", SetupStyle.MAGIC, weapon(0, 0, 0, 22, 0, 0));
		assertEquals("Thammaron's sceptre", SetupStyle.MAGIC, weapon(0, 0, 0, 15, 0, 0));
		assertEquals("Warped sceptre", SetupStyle.MAGIC, weapon(0, 0, 0, 12, 0, 0));
		assertEquals("Eye of ayak", SetupStyle.MAGIC, weapon(0, 0, 0, 30, 0, 0));
		assertEquals("Kodai wand", SetupStyle.MAGIC, weapon(0, 0, 0, 28, 0, 0));
		assertEquals("Nightmare staff", SetupStyle.MAGIC, weapon(0, 0, 0, 16, 0, 0));
		assertEquals("Master wand", SetupStyle.MAGIC, weapon(0, 0, 0, 20, 0, 0));
	}

	@Test
	public void burstBarrageAndChinchompasSplash()
	{
		final int blackChinchompa = 11959;
		assertEquals(blackChinchompa, ItemID.CHINCHOMPA_BLACK);
		assertEquals(10033, ItemID.CHINCHOMPA_CAPTURED);
		assertEquals(10034, ItemID.CHINCHOMPA_BIG_CAPTURED);
		final int throwAnimation = AnimationID.HUMAN_CHINCHOMPA_ATTACK;
		for (int animation : new int[]{1979, 10092, 7753})
		{
			assertTrue(AttackStyles.splashes(animation, -1));
		}
		assertTrue(AttackStyles.splashes(throwAnimation, blackChinchompa));
		assertTrue(AttackStyles.splashes(throwAnimation, ItemID.CHINCHOMPA_BIG_CAPTURED));
		assertTrue("a Barrage cast holding them", AttackStyles.splashes(1979, blackChinchompa));
		assertFalse("another spell cast holding them hits one NPC", AttackStyles.splashes(1978, blackChinchompa));
		assertFalse("Rush and Blitz", AttackStyles.splashes(1978, -1));
		assertFalse("a blowpipe", AttackStyles.splashes(AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK, ItemID.TOXIC_BLOWPIPE));
	}

	@Test
	public void rangedXpAloneConfirmsNoAttack()
	{
		// The cannon gains Ranged XP with no Hitpoints XP (OSRS Wiki, "Dwarf multicannon"); every attack of the player's
		// that hits gains Hitpoints XP, and a spell its base Magic XP even when it splashes.
		assertEquals(Arrays.asList(Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.MAGIC, Skill.HITPOINTS),
			Arrays.asList(AttackStyles.ATTACK_XP_SKILLS));
	}

	@Test
	public void aSalamanderReadsAsRanged()
	{
		// Black salamander: slash +59, ranged +69, magic 0; its three styles share one weapon (a known limit).
		assertEquals(SetupStyle.RANGED, weapon(0, 59, 0, 0, 69, 0));
	}
}
