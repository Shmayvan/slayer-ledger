package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Which of the player's attacks each of their hitsplats is put down to ({@link PlayerAttacks}), with the real hit
 * timing on an NPC (OSRS Wiki, "Hit delay", one tick later than each weapon's own delay): a melee attack's hits on the
 * tick after it (a multi-hit special's last ones the tick after that), a ranged or magic attack's 2 to 6 ticks after
 * it; only an attack on that NPC, apart from a Burst's, Barrage's or chinchompa's splash on the tick its target was hit;
 * and none for a hitsplat with no attack on its NPC around it (a cannon's). Swaps respect the weapons' attack speeds.
 */
public class PlayerAttacksTest
{
	private static final long TARGET = DamagedNpcs.key(-1, 1);
	private static final long OTHER = DamagedNpcs.key(-1, 2);
	private static final long THIRD = DamagedNpcs.key(-1, 3);

	private final PlayerAttacks attacks = new PlayerAttacks();

	private void attack(int tick, SetupStyle style, long target)
	{
		attacks.add(tick, style, tick * 600L, target, style == SetupStyle.BARRAGE);
	}

	private SetupStyle styleFor(int hitTick, long npc)
	{
		final PlayerAttacks.Attack attack = attacks.forHit(hitTick, npc);
		return attack == null ? null : attack.getStyle();
	}

	@Test
	public void aMeleeHitLandsTheTickAfterItsAttack()
	{
		attack(100, SetupStyle.MELEE, TARGET);
		assertNull("not on the attack's own tick", styleFor(100, TARGET));
		assertEquals(SetupStyle.MELEE, styleFor(101, TARGET));
		assertEquals(60_000, attacks.forHit(101, TARGET).getAt());
		assertNull("never before the attack", styleFor(99, TARGET));
	}

	@Test
	public void theDragonClawsHitOnTheTwoTicksAfterTheSpecialAttack()
	{
		attack(100, SetupStyle.MELEE, TARGET);
		for (int hit = 0; hit < 2; hit++)
		{
			assertEquals("the first two hits", SetupStyle.MELEE, styleFor(101, TARGET));
			assertEquals("the last two hits", SetupStyle.MELEE, styleFor(102, TARGET));
		}
		assertNull("not three ticks later", styleFor(103, TARGET));
	}

	@Test
	public void theClawsAfterABlowpipeDartAreMelee()
	{
		// A dart on 98 (its hit on 100), then the claws as soon as the blowpipe allows, 2 ticks later.
		attack(98, SetupStyle.RANGED, TARGET);
		attack(100, SetupStyle.MELEE, TARGET);
		assertEquals("the dart's hit", SetupStyle.RANGED, styleFor(100, TARGET));
		assertEquals(SetupStyle.MELEE, styleFor(101, TARGET));
		assertEquals("the claws' last hits, not the dart", SetupStyle.MELEE, styleFor(102, TARGET));
	}

	@Test
	public void aRangedOrMagicHitLandsTwoToSixTicksAfterItsAttack()
	{
		attack(100, SetupStyle.BARRAGE, TARGET);
		assertNull("not on its own tick", styleFor(100, TARGET));
		assertNull("not the tick after", styleFor(101, TARGET));
		for (int tick = 100 + PlayerAttacks.RANGED_EARLIEST; tick <= 100 + PlayerAttacks.RANGED_LATEST; tick++)
		{
			assertEquals(SetupStyle.BARRAGE, styleFor(tick, TARGET));
		}
		assertNull(styleFor(101 + PlayerAttacks.RANGED_LATEST, TARGET));
		assertEquals(2, PlayerAttacks.RANGED_EARLIEST);
		assertEquals(6, PlayerAttacks.RANGED_LATEST);
	}

	@Test
	public void aDartsHitAfterASwapToAWhipIsStillRanged()
	{
		// A blowpipe dart on 100, a swap, then a whip as soon as the blowpipe allows: 2 ticks later.
		attack(100, SetupStyle.RANGED, TARGET);
		attack(102, SetupStyle.MELEE, TARGET);
		assertEquals("the dart's hit on 102", SetupStyle.RANGED, styleFor(102, TARGET));
		assertEquals("the whip's hit on 103", SetupStyle.MELEE, styleFor(103, TARGET));
	}

	@Test
	public void aWhipsHitAfterATridentsCastIsMelee()
	{
		// A trident's cast on 100, then a whip as soon as the trident allows: 4 ticks later.
		attack(100, SetupStyle.MAGIC, TARGET);
		attack(104, SetupStyle.MELEE, TARGET);
		assertEquals("the trident's hit", SetupStyle.MAGIC, styleFor(103, TARGET));
		assertEquals("the whip's hit on 105", SetupStyle.MELEE, styleFor(105, TARGET));
	}

	@Test
	public void theLatestRangedOrMagicAttackOnTheNpcWins()
	{
		attack(100, SetupStyle.MAGIC, TARGET);
		attack(104, SetupStyle.BARRAGE, TARGET);
		assertEquals(SetupStyle.MAGIC, styleFor(105, TARGET));
		assertEquals(SetupStyle.BARRAGE, styleFor(106, TARGET));
	}

	@Test
	public void onlyAnAttackOnTheNpcLandsItsHits()
	{
		attack(100, SetupStyle.RANGED, TARGET);
		attack(102, SetupStyle.MELEE, OTHER);
		assertNull("the cannon's hit on another NPC while blowpiping", styleFor(102, OTHER));
		assertNull("or on the tick of a melee hit on its target", styleFor(103, THIRD));
		assertEquals(SetupStyle.RANGED, styleFor(103, TARGET));
		assertEquals(SetupStyle.MELEE, styleFor(103, OTHER));
	}

	@Test
	public void aBarragesSplashCountsOnlyOnTheTickItsTargetWasHit()
	{
		attack(100, SetupStyle.BARRAGE, TARGET);
		// Its hits on all the NPCs it hits land together on 103: the target's first.
		assertEquals(SetupStyle.BARRAGE, styleFor(103, TARGET));
		assertNull("no attack on the other NPC", attacks.forHit(103, OTHER));
		assertEquals(SetupStyle.BARRAGE, attacks.forSplashHit(103).getStyle());
		assertNull("the cannon's hit on another NPC on another tick", attacks.forSplashHit(102));
		assertNull(attacks.forSplashHit(104));
	}

	@Test
	public void aChinchompaSplashesAndABlowpipeDoesNot()
	{
		// A chinchompa on 100, then a dart at another NPC when the chinchompa allows (3 ticks on rapid).
		attacks.add(100, SetupStyle.RANGED, 60_000, TARGET, true);
		attacks.add(103, SetupStyle.RANGED, 61_800, OTHER, false);
		assertEquals(SetupStyle.RANGED, styleFor(102, TARGET));
		assertEquals("its splash", 100, attacks.forSplashHit(102).getTick());
		assertEquals(SetupStyle.RANGED, styleFor(105, OTHER));
		assertNull("a dart hits its target alone", attacks.forSplashHit(105));
	}

	@Test
	public void aHitWithNoAttackOnItsNpcAroundItHasNone()
	{
		assertNull("a cannon's hit", attacks.forHit(100, TARGET));
		assertNull(attacks.forSplashHit(100));
		attack(100, SetupStyle.RANGED, TARGET);
		assertNull("long after", attacks.forHit(200, TARGET));
	}

	@Test
	public void attacksWithoutAStyleOrOutOfOrderAreIgnored()
	{
		attacks.add(100, null, 1_000, TARGET, false);
		assertNull(attacks.forHit(101, TARGET));
		attacks.add(100, SetupStyle.MELEE, 1_000, TARGET, false);
		final PlayerAttacks.Attack first = attacks.forHit(101, TARGET);
		attacks.add(100, SetupStyle.RANGED, 1_000, TARGET, false);
		attacks.add(99, SetupStyle.RANGED, 400, TARGET, false);
		assertSame("one attack per tick, in tick order", first, attacks.forHit(101, TARGET));
		attacks.clear();
		assertNull(attacks.forHit(101, TARGET));
	}

	@Test
	public void theXpReadingTellsWhetherItRoseSinceTheLastTick()
	{
		assertFalse("a first reading is a baseline", attacks.xpRose(1_000));
		assertFalse("no XP gained (a block)", attacks.xpRose(1_000));
		assertTrue(attacks.xpRose(1_004));
		assertFalse("not read", attacks.xpRose(PlayerAttacks.NO_XP));
		assertFalse("a baseline again", attacks.xpRose(1_010));
		assertTrue(attacks.xpRose(1_011));
		attacks.forgetXp();
		assertFalse(attacks.xpRose(1_020));
		attacks.clear();
		assertFalse(attacks.xpRose(1_030));
	}
}
