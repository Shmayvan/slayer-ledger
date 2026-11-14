package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.Test;

/**
 * Engagements and kill pairing (V2 plan 4.1): each damaged NPC's run of the player's hits, the deaths paired with a
 * task kill, and tallying the deaths before a restart once. {@link CatacombsVariantsTest} covers keeping, forgetting
 * and bounding the NPCs themselves.
 */
public class DamagedNpcsTest
{
	private static final long MINUTE = 60_000L;
	private static final long AWAY = 10 * MINUTE;

	@Test
	public void engagementStartsAtTheFirstHitOfTheCurrentRun()
	{
		DamagedNpcs npcs = new DamagedNpcs();
		npcs.damaged(0, 1, 1_000, AWAY);
		npcs.damaged(0, 1, 5 * MINUTE, AWAY);
		npcs.damaged(0, 1, 14 * MINUTE, AWAY);
		assertTrue(npcs.died(0, 1, "Abyssal demon", "Abyssal demon", false, 100, 15 * MINUTE, AWAY));

		KillPairing pairing = npcs.pairing(98, 100);
		assertEquals("no gap over the limit: one engagement", 1_000, pairing.getEarliestStart());
		assertEquals(Collections.singleton("Abyssal demon"), pairing.getNames());
	}

	@Test
	public void aGapOverTheLimitStartsANewEngagement()
	{
		DamagedNpcs npcs = new DamagedNpcs();
		npcs.damaged(0, 1, 0, AWAY);
		// Walked off for half an hour, then came back for it.
		npcs.damaged(0, 1, 30 * MINUTE, AWAY);
		npcs.damaged(0, 1, 31 * MINUTE, AWAY);
		npcs.died(0, 1, "Abyssal demon", "Abyssal demon", false, 10, 31 * MINUTE, AWAY);

		assertEquals(30 * MINUTE, npcs.pairing(8, 10).getEarliestStart());
	}

	@Test
	public void anNpcHitLongBeforeItDiesDoesNotStretchTheStart()
	{
		DamagedNpcs npcs = new DamagedNpcs();
		npcs.damaged(0, 1, 0, AWAY);
		// Killed 20 minutes later by someone else, or a cannon: its own run is the death itself.
		npcs.died(0, 1, "Abyssal demon", "Abyssal demon", false, 10, 20 * MINUTE, AWAY);

		KillPairing pairing = npcs.pairing(8, 10);
		assertEquals(20 * MINUTE, pairing.getEarliestStart());
		assertEquals("still a task NPC", Collections.singleton("Abyssal demon"), pairing.getNames());
	}

	@Test
	public void aBarrageMultiKillPairsEveryDeathInTheWindow()
	{
		DamagedNpcs npcs = new DamagedNpcs();
		npcs.damaged(0, 1, 2_000, AWAY);
		npcs.damaged(0, 2, 1_000, AWAY);
		npcs.damaged(0, 3, 3_000, AWAY);
		npcs.damaged(0, 4, 500, AWAY);
		npcs.died(0, 1, "Dust devil", "Dust devil", false, 99, 60_000, AWAY);
		npcs.died(0, 2, "Dust devil", "Dust devil", false, 100, 60_000, AWAY);
		npcs.died(0, 3, "Choke devil", "Choke devil", false, 101, 60_000, AWAY);
		// Outside T - 2 to T + 1 for a kill on tick 100.
		npcs.died(0, 4, "Abyssal demon", "Abyssal demon", false, 97, 60_000, AWAY);

		KillPairing atKill = npcs.pairing(98, 100);
		assertEquals(1_000, atKill.getEarliestStart());
		assertEquals(Collections.singleton("Dust devil"), atKill.getNames());
		KillPairing nextTick = npcs.pairing(98, 101);
		assertEquals(new LinkedHashSet<>(Arrays.asList("Dust devil", "Choke devil")), nextTick.getNames());
		assertEquals(1_000, nextTick.getEarliestStart());
	}

	@Test
	public void nothingPairedIsNoStart()
	{
		DamagedNpcs npcs = new DamagedNpcs();
		KillPairing pairing = npcs.pairing(98, 100);
		assertFalse(pairing.hasStart());
		assertEquals(KillPairing.NO_START, pairing.getEarliestStart());
		assertTrue(pairing.getNames().isEmpty());
		assertTrue(pairing.isStartAllowed());
		assertFalse(pairing.withStartBlocked().isStartAllowed());

		// An NPC the player never hit is no kill of theirs.
		assertFalse(npcs.died(0, 9, "Abyssal demon", "Abyssal demon", false, 100, 1_000, AWAY));
		assertFalse(npcs.pairing(98, 100).hasStart());
	}

	@Test
	public void theDeathsBeforeARestartAreTalliedOnceByTheKillThatRestartsIt()
	{
		DamagedNpcs npcs = new DamagedNpcs();
		for (int index = 1; index <= 5; index++)
		{
			npcs.damaged(0, index, 1_000, AWAY);
		}
		// Died while the session was stopped, so refused then; a kill on tick 100 restarts it.
		npcs.died(0, 1, "Deviant spectre", "Deviant spectre", false, 98, 2_000, AWAY);
		npcs.died(0, 2, "Deviant spectre", "Deviant spectre", false, 100, 2_000, AWAY);
		// Tallied as it died (the session ran then).
		npcs.died(0, 3, "Deviant spectre", "Deviant spectre", true, 100, 2_000, AWAY);
		// Before the kill's window (T - 2 to T).
		npcs.died(0, 4, "Abyssal demon", "Abyssal demon", false, 97, 2_000, AWAY);

		List<String> tallied = new ArrayList<>();
		assertEquals(2, npcs.tallyUntallied(98, 100, tallied::add));
		assertEquals(Arrays.asList("Deviant spectre", "Deviant spectre"), tallied);
		assertEquals("a restart on the next tick: its window overlaps, nothing is tallied twice", 0,
			npcs.tallyUntallied(99, 101, tallied::add));

		// One the tally refuses stays untallied, for a later kill to try again.
		npcs.died(0, 5, "Deviant spectre", "Deviant spectre", false, 101, 2_000, AWAY);
		assertEquals("refused", 0, npcs.tallyUntallied(99, 101, name -> false));
		assertEquals(1, npcs.tallyUntallied(99, 101, tallied::add));
		assertEquals(3, tallied.size());
	}

	@Test
	public void deathsAreKeptOnlyAFewTicks()
	{
		DamagedNpcs npcs = new DamagedNpcs();
		npcs.damaged(0, 1, 1_000, AWAY);
		npcs.died(0, 1, "Abyssal demon", "Abyssal demon", false, 100, 2_000, AWAY);

		assertTrue("paired again on the tick after a kill on tick 102",
			npcs.pairing(100, 103).hasStart());
		assertFalse("dropped after DEATH_KEEP_TICKS", npcs.pairing(90, 100 + DamagedNpcs.DEATH_KEEP_TICKS + 1).hasStart());

		npcs.damaged(0, 2, 1_000, AWAY);
		npcs.died(0, 2, "Abyssal demon", "Abyssal demon", false, 200, 2_000, AWAY);
		npcs.clear();
		assertFalse("a login or hop forgets the deaths", npcs.pairing(198, 200).hasStart());
	}

	// ---- Styles ----

	/** The key of the NPC at {@code index} in world view 0, as the plugin keys an attack's target. */
	private static long npc(int index)
	{
		return DamagedNpcs.key(0, index);
	}

	@Test
	public void eachHitCountsForItsAttacksStyleAndADeathGivesItsMajority()
	{
		final DamagedNpcs npcs = new DamagedNpcs();
		final PlayerAttacks attacks = new PlayerAttacks();
		attacks.add(100, SetupStyle.MELEE, 60_000, npc(1), false);
		npcs.applyHits(attacks);
		// A melee hit lands the tick after its attack.
		npcs.damaged(0, 1, 60_600, AWAY, 101);
		npcs.applyHits(attacks);
		attacks.add(104, SetupStyle.MELEE, 62_400, npc(1), false);
		npcs.applyHits(attacks);
		npcs.damaged(0, 1, 63_000, AWAY, 105);
		// A cannon's hit: no attack of the player's around it.
		npcs.damaged(0, 1, 63_600, AWAY, 110);
		npcs.applyHits(attacks);
		npcs.died(0, 1, "Abyssal demon", "Abyssal demon", false, 110, 63_600, AWAY);

		assertEquals(Collections.singletonList(SetupStyle.MELEE), npcs.takeStyles(108, 111, 1));
		assertTrue("each death's style goes to one kill", npcs.takeStyles(108, 111, 1).isEmpty());
	}

	@Test
	public void theKillingHitPutDownAfterTheDeathStillCounts()
	{
		final DamagedNpcs npcs = new DamagedNpcs();
		final PlayerAttacks attacks = new PlayerAttacks();
		attacks.add(100, SetupStyle.MELEE, 60_000, npc(1), false);
		// The hit and the death arrive before the next tick's GameTick, which puts the hit down to the attack.
		npcs.damaged(0, 1, 60_600, AWAY, 101);
		npcs.died(0, 1, "Abyssal demon", "Abyssal demon", false, 101, 60_600, AWAY);
		npcs.applyHits(attacks);
		assertEquals(Collections.singletonList(SetupStyle.MELEE), npcs.takeStyles(99, 102, 1));
	}

	@Test
	public void onlyHitsOnTheAttacksTargetOrItsSplashCount()
	{
		final DamagedNpcs npcs = new DamagedNpcs();
		final PlayerAttacks attacks = new PlayerAttacks();
		// A dart at NPC 1; the cannon hits NPC 2 as the dart lands.
		attacks.add(100, SetupStyle.RANGED, 60_000, npc(1), false);
		npcs.damaged(0, 2, 61_200, AWAY, 102);
		npcs.damaged(0, 1, 61_200, AWAY, 102);
		npcs.applyHits(attacks);
		// A barrage at NPC 1 when the blowpipe allows; the cannon hits NPC 3 on the next tick, then the barrage lands on
		// NPC 3 (its splash, arriving before the hit on its target) and NPC 1 together.
		attacks.add(102, SetupStyle.BARRAGE, 61_200, npc(1), true);
		npcs.damaged(0, 3, 61_800, AWAY, 103);
		npcs.applyHits(attacks);
		npcs.damaged(0, 3, 62_400, AWAY, 104);
		npcs.damaged(0, 1, 62_400, AWAY, 104);
		npcs.applyHits(attacks);
		for (int index = 1; index <= 3; index++)
		{
			npcs.died(0, index, "Dust devil", "Dust devil", false, 104, 62_400, AWAY);
		}

		final List<SetupStyle> styles = npcs.takeStyles(102, 105, 3);
		assertEquals("NPC 1: the dart and the barrage (a tie); NPC 2: only the cannon's hit; NPC 3: the splash",
			Arrays.asList(SetupStyle.MIXED, null, SetupStyle.BARRAGE), styles);
	}

	@Test
	public void aRangedHitStartsItsRunAtTheAttacksTick()
	{
		final DamagedNpcs npcs = new DamagedNpcs();
		final PlayerAttacks attacks = new PlayerAttacks();
		attacks.add(100, SetupStyle.RANGED, 60_000, npc(1), false);
		npcs.applyHits(attacks);
		// The arrow lands two ticks later.
		npcs.damaged(0, 1, 61_100, AWAY, 102);
		npcs.applyHits(attacks);
		npcs.died(0, 1, "Abyssal demon", "Abyssal demon", false, 102, 61_100, AWAY);
		assertEquals("backdated to the attack's tick, before its XP", 60_000, npcs.pairing(100, 102).getEarliestStart());

		// A cannon's hit moves nothing.
		npcs.damaged(0, 2, 70_000, AWAY, 120);
		npcs.applyHits(attacks);
		npcs.died(0, 2, "Abyssal demon", "Abyssal demon", false, 120, 70_000, AWAY);
		assertEquals(70_000, npcs.pairing(118, 120).getEarliestStart());
		assertEquals(Collections.singletonList(null), npcs.takeStyles(118, 120, 1));
	}

	@Test
	public void aCountDropTakesTheStylesOfItsDeathsOldestFirstUpToItsKills()
	{
		final DamagedNpcs npcs = new DamagedNpcs();
		final PlayerAttacks attacks = new PlayerAttacks();
		attacks.add(100, SetupStyle.BARRAGE, 60_000, npc(1), true);
		for (int index = 1; index <= 3; index++)
		{
			// The barrage on its target, NPC 1, and its splash on two more.
			npcs.damaged(0, index, 61_000, AWAY, 102);
		}
		npcs.applyHits(attacks);
		for (int index = 1; index <= 3; index++)
		{
			npcs.died(0, index, "Dust devil", "Dust devil", false, 102, 61_000, AWAY);
		}
		assertEquals(Arrays.asList(SetupStyle.BARRAGE, SetupStyle.BARRAGE), npcs.takeStyles(100, 103, 2));
		assertEquals("the third death is left for the next kill", Collections.singletonList(SetupStyle.BARRAGE),
			npcs.takeStyles(100, 104, 5));
		npcs.damaged(0, 9, 61_000, AWAY, 102);
		npcs.clear();
		npcs.applyHits(attacks);
		assertTrue("a login or hop forgets the hits waiting too", npcs.takeStyles(100, 104, 5).isEmpty());
	}
}
