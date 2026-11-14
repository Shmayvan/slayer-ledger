package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * The setup detection rules ({@link SetupDetection}): the gear focus cut-offs, checked against real setups'
 * prayer bonuses from the OSRS Wiki (2026-11-09), each killed NPC's style, and the task's cannon, style and gear focus
 * from its kill contexts.
 */
public class SetupDetectionTest
{
	private static int sum(int... prayer)
	{
		int total = 0;
		for (int p : prayer)
		{
			total += p;
		}
		return total;
	}

	private static Map<String, Integer> counts(Object... keysAndCounts)
	{
		final Map<String, Integer> map = new HashMap<>();
		for (int i = 0; i < keysAndCounts.length; i += 2)
		{
			map.put(keysAndCounts[i].toString(), (Integer) keysAndCounts[i + 1]);
		}
		return map;
	}

	private static int[] hits(int melee, int ranged, int barrage, int magic)
	{
		final int[] hits = new int[SetupStyle.values().length];
		hits[SetupStyle.MELEE.ordinal()] = melee;
		hits[SetupStyle.RANGED.ordinal()] = ranged;
		hits[SetupStyle.BARRAGE.ordinal()] = barrage;
		hits[SetupStyle.MAGIC.ordinal()] = magic;
		return hits;
	}

	// ---- Gear focus ----

	@Test
	public void theCutOffs()
	{
		assertEquals(GearFocus.PRAYER, SetupDetection.gearFocus(SetupDetection.PRAYER_FOCUS_MIN));
		assertEquals(GearFocus.MIXED, SetupDetection.gearFocus(SetupDetection.PRAYER_FOCUS_MIN - 1));
		assertEquals(GearFocus.MIXED, SetupDetection.gearFocus(SetupDetection.STRENGTH_FOCUS_MAX + 1));
		assertEquals(GearFocus.STRENGTH, SetupDetection.gearFocus(SetupDetection.STRENGTH_FOCUS_MAX));
		assertEquals(GearFocus.STRENGTH, SetupDetection.gearFocus(0));
		assertEquals("a negative total", GearFocus.STRENGTH, SetupDetection.gearFocus(-3));
		assertEquals(25, SetupDetection.PRAYER_FOCUS_MIN);
		assertEquals(14, SetupDetection.STRENGTH_FOCUS_MAX);
	}

	/** Damage-focused sets: each item's prayer bonus in slot order, from the OSRS Wiki. */
	@Test
	public void realDamageSetsAreStrength()
	{
		// Torva helm, body, legs; torture; infernal cape; primordial boots; ferocious gloves; ultor ring; avernic
		// defender; Osmumten's fang.
		final int torva = sum(1, 1, 1, 2, 2, 0, 0, 0, 0, 0);
		assertEquals(7, torva);
		assertEquals("with Rada's blessing 4", 9, torva + 2);
		// Neitiznot faceguard, Bandos chestplate and tassets; torture; infernal cape; primordial; ferocious; ultor;
		// avernic; whip.
		final int bandos = sum(3, 1, 1, 2, 2, 0, 0, 0, 0, 0);
		assertEquals(9, bandos);
		// Helm of neitiznot, fighter torso, obsidian platelegs, fury, fire cape, dragon boots, barrows gloves, berserker
		// ring (i), dragon defender, whip.
		final int midLevel = sum(3, 0, 0, 5, 2, 0, 0, 0, 0, 0);
		assertEquals(10, midLevel);
		// Masori (f) mask, body, chaps; anguish; Ava's assembler; zaryte vambraces; pegasian; venator ring; blowpipe.
		final int masori = sum(1, 1, 1, 2, 0, 1, 0, 0, 0);
		assertEquals(6, masori);
		// Armadyl helmet, chestplate, chainskirt; anguish; assembler; zaryte; pegasian; venator; Armadyl crossbow.
		final int armadyl = sum(1, 1, 1, 2, 0, 1, 0, 0, 1);
		assertEquals(7, armadyl);
		// Crystal helm, body, legs; anguish; assembler; zaryte; pegasian; venator; bow of faerdhinen.
		final int crystal = sum(2, 3, 2, 2, 0, 1, 0, 0, 0);
		assertEquals(10, crystal);
		// Ancestral hat, top, bottom; occult; imbued god cape; tormented bracelet; eternal boots; magus ring; kodai
		// wand; Elidinis' ward (f).
		final int ancestral = sum(0, 0, 0, 2, 0, 2, 0, 0, 0, 4);
		assertEquals(8, ancestral);
		// Ahrim's hood, robetop, robeskirt; occult; imbued god cape; tormented; eternal; seers ring (i); Ahrim's staff;
		// arcane spirit shield.
		final int ahrims = sum(0, 0, 0, 2, 0, 2, 0, 0, 0, 3);
		assertEquals(7, ahrims);
		// Virtus mask, top, bottom; occult; imbued god cape; tormented; eternal; magus; kodai; Elidinis' ward (f); Rada's
		// blessing 4: the highest damage set found.
		final int virtus = sum(1, 2, 1, 2, 0, 2, 0, 0, 0, 4, 2);
		assertEquals(14, virtus);

		for (int total : new int[]{torva, torva + 2, bandos, bandos + 2, midLevel, midLevel + 2, masori, armadyl,
			crystal, crystal + 2, ancestral, ahrims, virtus})
		{
			assertEquals("+" + total, GearFocus.STRENGTH, SetupDetection.gearFocus(total));
		}
	}

	@Test
	public void realPrayerSetsArePrayer()
	{
		// Proselyte sallet, hauberk, cuisse; holy wraps; Saradomin cape; holy book; holy blessing; ring of the gods;
		// holy sandals; salve amulet (ei); whip.
		final int proselyte = sum(4, 8, 6, 3, 0, 5, 1, 4, 3, 3, 0);
		assertEquals(37, proselyte);
		assertEquals("a defender in place of the book", 32, proselyte - 5);
		// Justiciar faceguard, chestguard, legguards and the same.
		final int justiciar = sum(2, 4, 4, 3, 0, 5, 1, 4, 3, 3, 0);
		assertEquals(29, justiciar);
		for (int total : new int[]{proselyte, proselyte - 5, justiciar})
		{
			assertEquals("+" + total, GearFocus.PRAYER, SetupDetection.gearFocus(total));
		}
	}

	@Test
	public void aPartlyPrayerSetIsMixed()
	{
		// Bandos with a Proselyte hauberk and cuisse in place of the chestplate and tassets.
		assertEquals(GearFocus.MIXED, SetupDetection.gearFocus(sum(3, 8, 6, 2, 2, 0, 0, 0, 0, 0)));
	}

	@Test
	public void theTasksGearIsTheFocusOfMostKillsAndATieIsMixed()
	{
		assertEquals(GearFocus.PRAYER, SetupDetection.taskGear(counts("PRAYER", 10, "STRENGTH", 3, "MIXED", 2)));
		assertEquals(GearFocus.MIXED, SetupDetection.taskGear(counts("PRAYER", 5, "STRENGTH", 5)));
		assertEquals("a tie for most, not with the rest", GearFocus.MIXED,
			SetupDetection.taskGear(counts("PRAYER", 5, "STRENGTH", 5, "MIXED", 1)));
		assertEquals(GearFocus.STRENGTH, SetupDetection.taskGear(counts("STRENGTH", 1)));
		assertEquals(GearFocus.UNKNOWN, SetupDetection.taskGear(counts()));
		assertEquals(GearFocus.UNKNOWN, SetupDetection.taskGear(null));
		assertEquals("a later build's key is left out", GearFocus.PRAYER,
			SetupDetection.taskGear(counts("PRAYER", 2, "FUTURE", 9)));
	}

	// ---- Styles ----

	@Test
	public void anNpcsStyleIsItsMajorityAndATieIsMixed()
	{
		assertEquals(SetupStyle.MELEE, SetupDetection.npcStyle(hits(3, 1, 0, 0)));
		assertEquals(SetupStyle.MIXED, SetupDetection.npcStyle(hits(2, 2, 0, 0)));
		assertEquals(SetupStyle.BARRAGE, SetupDetection.npcStyle(hits(0, 0, 1, 0)));
		assertNull("only a cannon's hits", SetupDetection.npcStyle(hits(0, 0, 0, 0)));
	}

	@Test
	public void theTasksStyleCoversAtLeastHalfOfItsStyledKills()
	{
		assertEquals(SetupStyle.BARRAGE, SetupDetection.taskStyle(counts("BARRAGE", 50, "MAGIC", 30, "MELEE", 20)));
		assertEquals("45/40/15", SetupStyle.MIXED,
			SetupDetection.taskStyle(counts("MELEE", 45, "RANGED", 40, "BARRAGE", 15)));
		assertEquals("a tie at half", SetupStyle.MIXED, SetupDetection.taskStyle(counts("MELEE", 5, "RANGED", 5)));
		assertEquals(SetupStyle.MELEE, SetupDetection.taskStyle(counts("MELEE", 1)));
		assertEquals("kills that were already mixed", SetupStyle.MIXED,
			SetupDetection.taskStyle(counts("MIXED", 6, "MELEE", 4)));
		assertEquals("no styled kill (a cannon-only task)", SetupStyle.UNKNOWN, SetupDetection.taskStyle(counts()));
		assertEquals(SetupStyle.UNKNOWN, SetupDetection.taskStyle(null));
		assertEquals("a later build's key is left out", SetupStyle.RANGED,
			SetupDetection.taskStyle(counts("RANGED", 2, "MELEE", 1, "THRALL", 9)));
	}

	// ---- Cannon ----

	@Test
	public void aCannonTaskHadAtLeastHalfItsKillsByCannon()
	{
		assertTrue(SetupDetection.cannon(5, 10));
		assertTrue(SetupDetection.cannon(10, 10));
		assertFalse("a brief cannon setup", SetupDetection.cannon(4, 10));
		assertFalse(SetupDetection.cannon(0, 10));
		assertFalse("no kill context", SetupDetection.cannon(null, 10));
		assertFalse("no kills", SetupDetection.cannon(3, 0));
	}

	@Test
	public void eachTagSaysWhatItWasDetectedFrom()
	{
		final SetupKills kills = SetupKills.NONE.withKill(true, SetupStyle.BARRAGE, GearFocus.PRAYER, 31)
			.withKill(false, SetupStyle.BARRAGE, GearFocus.PRAYER, 33)
			.withKill(true, null, GearFocus.STRENGTH, -2);
		assertEquals(Collections.singletonList("Cannon kills: 2 of 3"), SetupDetection.cannonDetails(kills, 3));
		assertEquals(Collections.singletonList("Kills by style: Barrage 2"), SetupDetection.styleDetails(kills));
		assertEquals(Arrays.asList("Kills by gear: Prayer 2, Strength 1", "Prayer bonus: -2 to +33"),
			SetupDetection.gearDetails(kills));
		assertEquals("one prayer bonus seen", Collections.singletonList("Prayer bonus: +31"),
			SetupDetection.gearDetails(SetupKills.NONE.withKill(false, null, null, 31)));
		// Before a kill's context is counted there is nothing to say.
		assertTrue(SetupDetection.cannonDetails(SetupKills.NONE, 0).isEmpty());
		assertTrue(SetupDetection.styleDetails(SetupKills.NONE).isEmpty());
		assertTrue(SetupDetection.gearDetails(SetupKills.NONE).isEmpty());
	}
}
