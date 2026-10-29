package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * Blowpipe darts and scales and trident casts counted from attack animations.
 */
public class ChargedWeaponUsageTest
{
	private static final int BLOWPIPE = ItemID.TOXIC_BLOWPIPE_LOADED;
	private static final int SHOT = AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK;
	private static final int CAST = AnimationID.HUMAN_CASTWAVE_STAFF;
	private static final int DART = ItemID.ADAMANT_DART;
	private static final int SCALE = ItemID.SNAKEBOSS_SCALE;

	private final ChargedWeaponUsage usage = new ChargedWeaponUsage();
	private int tick;

	private static ChargedWeaponUsage.Tick.TickBuilder blowpipeShot()
	{
		return ChargedWeaponUsage.Tick.builder().weaponId(BLOWPIPE).animationId(SHOT).dartId(DART);
	}

	private static ChargedWeaponUsage.Tick.TickBuilder tridentCast(int weaponId)
	{
		return ChargedWeaponUsage.Tick.builder().weaponId(weaponId).animationId(CAST).tridentGraphic(true);
	}

	/** Feeds {@code shots} ticks built by {@code tick} and sums what was used. */
	private Map<Integer, Integer> run(ChargedWeaponUsage.Tick.TickBuilder builder, int shots)
	{
		final Map<Integer, Integer> total = new HashMap<>();
		for (int i = 0; i < shots; i++)
		{
			usage.update(builder.tick(++tick).build()).forEach((id, qty) -> total.merge(id, qty, Integer::sum));
		}
		return total;
	}

	private static int qty(Map<Integer, Integer> used, int itemId)
	{
		return used.getOrDefault(itemId, 0);
	}

	@Test
	public void noDeviceUsesOneDartPerShot()
	{
		final Map<Integer, Integer> used = run(blowpipeShot(), 3);
		assertEquals(3, qty(used, DART));
		assertEquals(2, qty(used, SCALE));
	}

	@Test
	public void scalesCarryOverInThirds()
	{
		assertEquals(0, qty(run(blowpipeShot(), 1), SCALE));
		assertEquals(1, qty(run(blowpipeShot(), 1), SCALE));
		assertEquals(1, qty(run(blowpipeShot(), 1), SCALE));
		assertEquals(0, qty(run(blowpipeShot(), 1), SCALE));
	}

	@Test
	public void hundredShotsWithEachDevice()
	{
		assertDarts(ItemID.ANMA_30_REWARD, false, 40);
		assertDarts(ItemID.ANMA_50_REWARD, false, 28);
		assertDarts(ItemID.SKILLCAPE_MAX_ANMA, false, 28);
		assertDarts(ItemID.AVAS_ASSEMBLER, false, 20);
		assertDarts(ItemID.AVAS_ASSEMBLER_MASORI, false, 20);
		assertDarts(ItemID.SKILLCAPE_MAX_ASSEMBLER_MASORI_TROUVER, false, 20);
		assertDarts(ItemID.SKILLCAPE_RANGING, false, 28);
		assertDarts(ItemID.SKILLCAPE_RANGING, true, 20);
		assertDarts(ItemID.SKILLCAPE_RANGING_TRIMMED, true, 20);
		assertDarts(ItemID.SKILLCAPE_MAX, false, 28);
		assertDarts(ItemID.SKILLCAPE_MAX, true, 20);
		assertDarts(ItemID.DIZANAS_QUIVER_CHARGED, false, 20);
		assertDarts(ItemID.SKILLCAPE_MAX_DIZANAS, false, 20);
		// The upgrade setting only affects ranging and max capes.
		assertDarts(ItemID.ANMA_50_REWARD, true, 28);
		assertDarts(ChargedWeaponUsage.NO_ITEM, true, 100);
		// A broken assembler saves nothing.
		assertDarts(ItemID.AVAS_ASSEMBLER_BROKEN, false, 100);
	}

	private void assertDarts(int capeId, boolean upgrade, int expectedDarts)
	{
		usage.reset();
		final Map<Integer, Integer> used = run(blowpipeShot().capeId(capeId).vorkathsHeadUpgrade(upgrade), 100);
		assertEquals("darts with " + capeId, expectedDarts, qty(used, DART));
		assertEquals("scales with " + capeId, 66, qty(used, SCALE));
	}

	@Test
	public void assemblerCarriesDartsOverInHundredths()
	{
		final ChargedWeaponUsage.Tick.TickBuilder shot = blowpipeShot().capeId(ItemID.AVAS_ASSEMBLER);
		assertEquals(0, qty(run(shot, 4), DART));
		assertEquals(1, qty(run(shot, 1), DART));
		assertEquals(0, qty(run(shot, 4), DART));
		assertEquals(1, qty(run(shot, 1), DART));
	}

	@Test
	public void quiverSettingWhenPassed()
	{
		assertEquals(20, ChargedWeaponUsage.quiverLossHundredths(null));
		assertEquals(20, ChargedWeaponUsage.quiverLossHundredths(0));
		assertEquals(28, ChargedWeaponUsage.quiverLossHundredths(1));
		assertEquals(40, ChargedWeaponUsage.quiverLossHundredths(2));
		assertEquals(20, ChargedWeaponUsage.quiverLossHundredths(7));
		assertEquals(40, ChargedWeaponUsage.dartLossHundredths(ItemID.DIZANAS_QUIVER_INFINITE, 2, false));
		// The quiver setting doesn't apply to other capes.
		assertEquals(20, ChargedWeaponUsage.dartLossHundredths(ItemID.AVAS_ASSEMBLER, 2, false));
	}

	@Test
	public void unknownDartTypeCountsScalesOnlyAndAsksForACheck()
	{
		assertFalse(usage.isDartCheckNeeded());
		final Map<Integer, Integer> used = run(blowpipeShot().dartId(ChargedWeaponUsage.NO_ITEM), 3);
		assertEquals(1, used.size());
		assertEquals(2, qty(used, SCALE));
		assertTrue(usage.isDartCheckNeeded());

		// No dart fraction was carried while unknown: the first known shot uses one dart from scratch.
		final Map<Integer, Integer> known = run(blowpipeShot().capeId(ItemID.AVAS_ASSEMBLER), 4);
		assertFalse(usage.isDartCheckNeeded());
		assertEquals(0, qty(known, DART));
	}

	@Test
	public void knownDartTypeOnAnyTickClearsTheHint()
	{
		run(blowpipeShot().dartId(ChargedWeaponUsage.NO_ITEM), 1);
		assertTrue(usage.isDartCheckNeeded());
		run(ChargedWeaponUsage.Tick.builder().dartId(DART), 1);
		assertFalse(usage.isDartCheckNeeded());
	}

	@Test
	public void changingDartTypeDropsTheCarryOver()
	{
		final ChargedWeaponUsage.Tick.TickBuilder shot = blowpipeShot().capeId(ItemID.AVAS_ASSEMBLER);
		run(shot, 4);
		final Map<Integer, Integer> used = run(shot.dartId(ItemID.RUNE_DART), 1);
		assertEquals(0, qty(used, ItemID.RUNE_DART));
		assertEquals(0, qty(used, DART));
		assertEquals(1, qty(run(shot, 4), ItemID.RUNE_DART));
	}

	@Test
	public void ornamentBlowpipeAndAnimationCount()
	{
		final Map<Integer, Integer> used = run(blowpipeShot().weaponId(ItemID.TOXIC_BLOWPIPE_LOADED_ORNAMENT)
			.animationId(AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK_ORNAMENT), 1);
		assertEquals(1, qty(used, DART));
	}

	@Test
	public void nothingWithoutTheBlowpipeWorn()
	{
		assertTrue(run(blowpipeShot().weaponId(ChargedWeaponUsage.NO_ITEM), 10).isEmpty());
		assertTrue(run(blowpipeShot().weaponId(ItemID.TOXIC_BLOWPIPE), 10).isEmpty());
	}

	@Test
	public void nothingForOtherAnimations()
	{
		assertTrue(run(blowpipeShot().animationId(-1), 10).isEmpty());
		assertTrue(run(blowpipeShot().animationId(CAST), 10).isEmpty());
	}

	@Test
	public void nothingAfterFrameZero()
	{
		assertTrue(run(blowpipeShot().animationFrame(1), 10).isEmpty());
		assertTrue(run(tridentCast(ItemID.TOTS_CHARGED).animationFrame(3), 10).isEmpty());
	}

	@Test
	public void atMostOneAttackPerTick()
	{
		final ChargedWeaponUsage.Tick shot = blowpipeShot().tick(5).build();
		assertEquals(1, qty(usage.update(shot), DART));
		assertTrue(usage.update(shot).isEmpty());
		assertTrue(usage.update(blowpipeShot().tick(4).build()).isEmpty());
		assertEquals(1, qty(usage.update(blowpipeShot().tick(6).build()), DART));
	}

	@Test
	public void tridentOfTheSeasCast()
	{
		for (int trident : new int[]{ItemID.TOTS, ItemID.TOTS_CHARGED, ItemID.TOTS_I_CHARGED, ItemID.TOTS_ORN,
			ItemID.TOTS_CHARGED_ORN, ItemID.TOTS_I_CHARGED_ORN})
		{
			final Map<Integer, Integer> used = run(tridentCast(trident), 1);
			assertEquals("" + trident, 4, used.size());
			assertEquals(1, qty(used, ItemID.DEATHRUNE));
			assertEquals(1, qty(used, ItemID.CHAOSRUNE));
			assertEquals(5, qty(used, ItemID.FIRERUNE));
			assertEquals(10, qty(used, ItemID.COINS));
		}
	}

	@Test
	public void tridentOfTheSwampCast()
	{
		for (int trident : new int[]{ItemID.TOXIC_TOTS_CHARGED, ItemID.TOXIC_TOTS_I_CHARGED,
			ItemID.TOXIC_TOTS_CHARGED_ORN, ItemID.TOXIC_TOTS_I_CHARGED_ORN})
		{
			final Map<Integer, Integer> used = run(tridentCast(trident)
				.animationId(AnimationID.HUMAN_CASTWAVE_STAFF_WALKMERGE), 1);
			assertEquals("" + trident, 4, used.size());
			assertEquals(1, qty(used, ItemID.DEATHRUNE));
			assertEquals(1, qty(used, ItemID.CHAOSRUNE));
			assertEquals(5, qty(used, ItemID.FIRERUNE));
			assertEquals(1, qty(used, SCALE));
		}
	}

	@Test
	public void tridentCastsAddUp()
	{
		final Map<Integer, Integer> used = run(tridentCast(ItemID.TOXIC_TOTS_CHARGED), 10);
		assertEquals(10, qty(used, ItemID.DEATHRUNE));
		assertEquals(50, qty(used, ItemID.FIRERUNE));
		assertEquals(10, qty(used, SCALE));
	}

	@Test
	public void unchargedTridentsCastNothing()
	{
		for (int trident : new int[]{ItemID.TOTS_UNCHARGED, ItemID.TOTS_I_UNCHARGED, ItemID.TOTS_UNCHARGED_ORN,
			ItemID.TOTS_I_UNCHARGED_ORN, ItemID.TOXIC_TOTS_UNCHARGED, ItemID.TOXIC_TOTS_I_UNCHARGED,
			ItemID.TOXIC_TOTS_UNCHARGED_ORN, ItemID.TOXIC_TOTS_I_UNCHARGED_ORN})
		{
			assertTrue("" + trident, run(tridentCast(trident), 1).isEmpty());
		}
	}

	@Test
	public void tridentNeedsTheCastGraphic()
	{
		assertTrue(run(tridentCast(ItemID.TOTS_CHARGED).tridentGraphic(false), 5).isEmpty());
		// A wave spell from another staff shows no trident graphic, and the graphic alone isn't a cast.
		assertTrue(run(tridentCast(ItemID.TOTS_CHARGED).animationId(-1), 5).isEmpty());
	}

	@Test
	public void resetForgetsCarryOversAndTheLastTick()
	{
		run(blowpipeShot().capeId(ItemID.AVAS_ASSEMBLER), 4);
		run(blowpipeShot().dartId(ChargedWeaponUsage.NO_ITEM), 2);
		assertTrue(usage.isDartCheckNeeded());
		usage.reset();
		assertFalse(usage.isDartCheckNeeded());
		tick = 0;
		final Map<Integer, Integer> used = run(blowpipeShot().capeId(ItemID.AVAS_ASSEMBLER), 1);
		assertEquals(0, qty(used, DART));
		assertEquals(0, qty(used, SCALE));
	}

	@Test
	public void trackedIdSetsDontOverlap()
	{
		for (int id : ChargedWeaponUsage.BLOWPIPES)
		{
			assertFalse(ChargedWeaponUsage.SEAS_TRIDENTS.contains(id) || ChargedWeaponUsage.SWAMP_TRIDENTS.contains(id));
		}
		for (int id : ChargedWeaponUsage.SEAS_TRIDENTS)
		{
			assertFalse(ChargedWeaponUsage.SWAMP_TRIDENTS.contains(id));
		}
		for (int id : ChargedWeaponUsage.ASSEMBLERS)
		{
			assertFalse(ChargedWeaponUsage.ACCUMULATORS.contains(id) || ChargedWeaponUsage.RANGING_CAPES.contains(id)
				|| ChargedWeaponUsage.QUIVERS.contains(id) || ChargedWeaponUsage.ATTRACTORS.contains(id));
		}
	}
}
