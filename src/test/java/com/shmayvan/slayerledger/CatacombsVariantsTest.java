package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.NpcID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The Catacombs variant table, the kills per NPC name and the majority rule: {@link CatacombsVariants},
 * {@link NpcKillTally}, {@link DamagedNpcs} and {@link TaskSelection}.
 */
public class CatacombsVariantsTest
{
	@Test
	public void theTableHasTheCatacombsNpcIdsAndNames()
	{
		// The ids and names of the RuneLite 1.13.4 gameval NpcID constants (their javadoc gives the NPC's name).
		assertVariant("Aberrant spectres", NpcID.KOUREND_SPECTRE, 7279, "Deviant spectre", "Deviant spectres");
		assertVariant("Nechryael", NpcID.KOUREND_NECHRYAEL, 7278, "Greater Nechryael", "Greater nechryael");
		assertVariant("Bloodveld", NpcID.KOUREND_BLOODVELD, 7276, "Mutated Bloodveld", "Mutated bloodveld");
		assertVariant("Jellies", NpcID.KOUREND_JELLY, 7277, "Warped Jelly", "Warped jellies");
		assertVariant("Banshees", NpcID.KOUREND_BANSHEE, 7272, "Twisted Banshee", "Twisted banshees");
		assertVariant("Black dragons", NpcID.KOUREND_BLACK_DRAGON, 7275, "Brutal black dragon", "Brutal black dragons");
		assertVariant("Blue dragons", NpcID.KOUREND_BLUE_DRAGON, 7273, "Brutal blue dragon", "Brutal blue dragons");
		assertVariant("Red dragons", NpcID.KOUREND_RED_DRAGON, 7274, "Brutal red dragon", "Brutal red dragons");
		assertEquals(8, CatacombsVariants.all().size());
	}

	@Test
	public void tasksMatchByTheOneNameKeyAndOthersHaveNoVariant()
	{
		assertEquals("Deviant spectres", CatacombsVariants.forTask("  ABERRANT spectres ").getName());
		assertNull(CatacombsVariants.forTask("Abyssal demons"));
		assertNull(CatacombsVariants.forTask("Green dragons"));
		assertNull(CatacombsVariants.forTask(null));
		assertNull(CatacombsVariants.forTask(" "));
	}

	@Test
	public void onlyTheCatacombsNpcCountsAsTheVariant()
	{
		assertEquals("Deviant spectre", CatacombsVariants.tallyName(NpcID.KOUREND_SPECTRE, " Deviant spectre "));
		assertEquals("Aberrant spectre", CatacombsVariants.tallyName(NpcID.SLAYER_ABBERANT_SPECTRE_1, "Aberrant spectre"));
		// The same names outside the Catacombs are not the variant, so they are not tallied.
		assertNull(CatacombsVariants.tallyName(NpcID.WILD_CAVE_NECHRYAEL, "Greater Nechryael"));
		assertNull(CatacombsVariants.tallyName(NpcID.MYQ5_BLOODVELD, "Mutated Bloodveld"));
		assertNull(CatacombsVariants.tallyName(NpcID.DS2_BRUT_BLACK_DRAGON, "Brutal black dragon"));
		assertNull(CatacombsVariants.tallyName(NpcID.BRUTAL_BLUE_DRAGON_TAPOYAUIK, "Brutal blue dragon"));
		assertNull(CatacombsVariants.tallyName(1, null));
		assertNull(CatacombsVariants.tallyName(1, "  "));
	}

	@Test
	public void theVariantMustBeMoreThanHalfOfTheTaskKills()
	{
		// All standard.
		assertNull(CatacombsVariants.decide("Aberrant spectres", tally("Aberrant spectre", 50), 50));
		// All variant.
		assertEquals("Deviant spectres", CatacombsVariants.decide("Aberrant spectres", tally("Deviant spectre", 50),
			50));
		// Mixed: more than half of the task kills variant, or not.
		assertEquals("Deviant spectres", CatacombsVariants.decide("Aberrant spectres",
			tally("Deviant spectre", 31, "Aberrant spectre", 30), 61));
		assertNull(CatacombsVariants.decide("Aberrant spectres", tally("Deviant spectre", 30, "Aberrant spectre", 31),
			61));
		// A tie is the standard monster.
		assertNull(CatacombsVariants.decide("Aberrant spectres", tally("Deviant spectre", 20, "Aberrant spectre", 20),
			40));
		// No tallied kills, or no task kills.
		assertNull(CatacombsVariants.decide("Aberrant spectres", Collections.emptyList(), 10));
		assertNull(CatacombsVariants.decide("Aberrant spectres", null, 10));
		assertNull(CatacombsVariants.decide("Aberrant spectres", tally("Deviant spectre", 5), 0));
		// Names match ignoring case and spaces; a task without a variant never has one.
		assertEquals("Greater nechryael", CatacombsVariants.decide("nechryael", tally(" greater NECHRYAEL", 3), 3));
		assertNull(CatacombsVariants.decide("Abyssal demons", tally("Deviant spectre", 50), 50));
		assertNull(CatacombsVariants.decide(null, tally("Deviant spectre", 50), 50));
	}

	@Test
	public void unrelatedKillsDontCount()
	{
		// An Ankou on the way and other barraged NPCs are left out: 10 of 14 task kills were the variant.
		assertEquals("Deviant spectres", CatacombsVariants.decide("Aberrant spectres", tally("Deviant spectre", 10,
			"Ankou", 6, "Aberrant spectre", 4, "Ghost", 12), 14));
		// Greater Nechryael death spawns (and other names) don't count against the variant.
		assertEquals("Greater nechryael", CatacombsVariants.decide("Nechryael", tally("Greater Nechryael", 20,
			"Death spawn", 45), 20));
		// Nor for it: the variant against the task kills only.
		assertNull(CatacombsVariants.decide("Aberrant spectres", tally("Deviant spectre", 3, "Ankou", 40), 6));
		assertSame(TaskSelection.STANDARD,
			CatacombsVariants.selection("Aberrant spectres", tally("Deviant spectre", 3, "Ankou", 40), 6));
	}

	@Test
	public void theLiveSelectionPoolsUntilTheFirstTaskKill()
	{
		assertSame(TaskSelection.ANY, CatacombsVariants.selection("Aberrant spectres", Collections.emptyList(), 0));
		assertSame(TaskSelection.ANY, CatacombsVariants.selection("Abyssal demons", tally("Abyssal demon", 5), 5));
		// An unrelated kill before the first task kill changes nothing.
		assertSame(TaskSelection.ANY, CatacombsVariants.selection("Aberrant spectres", tally("Ankou", 1), 0));
		// A variant kill tallied before the task counter moved: still pooled.
		assertSame(TaskSelection.ANY, CatacombsVariants.selection("Aberrant spectres", tally("Deviant spectre", 1), 0));
		assertSame(TaskSelection.STANDARD, CatacombsVariants.selection("Aberrant spectres", tally("Aberrant spectre", 1),
			1));
		// One unrelated kill doesn't make it standard.
		assertEquals(TaskSelection.of("Deviant spectres"),
			CatacombsVariants.selection("Aberrant spectres", tally("Ankou", 1, "Deviant spectre", 1), 1));
		// A tie is standard.
		assertSame(TaskSelection.STANDARD,
			CatacombsVariants.selection("Aberrant spectres", tally("Deviant spectre", 1, "Aberrant spectre", 1), 2));
	}

	@Test
	public void taskVariantsSelectRecordsByTheirVersion()
	{
		LedgerRecord standard = new LedgerRecord();
		standard.setMonster("Aberrant spectres");
		LedgerRecord deviant = new LedgerRecord();
		deviant.setMonster("Aberrant spectres");
		deviant.setVariant("Deviant spectres");

		assertTrue(TaskSelection.ANY.matches(standard));
		assertTrue(TaskSelection.ANY.matches(deviant));
		assertTrue(TaskSelection.STANDARD.matches(standard));
		assertFalse(TaskSelection.STANDARD.matches(deviant));
		assertFalse(TaskSelection.of("deviant SPECTRES ").matches(standard));
		assertTrue(TaskSelection.of("deviant SPECTRES ").matches(deviant));
		assertSame(TaskSelection.STANDARD, TaskSelection.of(" "));
		assertNotEquals(TaskSelection.ANY, TaskSelection.STANDARD);
		assertEquals(TaskSelection.of("Deviant spectres").hashCode(), TaskSelection.of("deviant spectres").hashCode());

		// The group name is the variant's for a variant record, else the monster.
		assertEquals("Aberrant spectres", standard.groupName());
		assertEquals("Deviant spectres", deviant.groupName());
		deviant.setVariant(" ");
		assertEquals("Aberrant spectres", deviant.groupName());
	}

	@Test
	public void theTallyMergesNamesAndIsBounded()
	{
		List<NpcKills> tally = NpcKillTally.add(null, "Deviant spectre");
		tally = NpcKillTally.add(tally, " deviant SPECTRE ");
		tally = NpcKillTally.add(tally, "Ankou");
		tally = NpcKillTally.add(tally, null);
		tally = NpcKillTally.add(tally, " ");
		assertEquals(Arrays.asList(new NpcKills("Deviant spectre", 2), new NpcKills("Ankou", 1)), tally);
		assertEquals(2, NpcKillTally.killsOf(tally, "DEVIANT spectre"));
		assertEquals(0, NpcKillTally.killsOf(tally, "Aberrant spectre"));
		assertEquals(3, NpcKillTally.total(tally));

		List<NpcKills> many = new ArrayList<>();
		for (int i = 0; i < NpcKillTally.MAX_NAMES + 5; i++)
		{
			many = NpcKillTally.add(many, "Npc " + i);
		}
		assertEquals(NpcKillTally.MAX_NAMES, many.size());
		// A name already tallied still counts once the tally is full.
		many = NpcKillTally.add(many, "Npc 0");
		assertEquals(2, NpcKillTally.killsOf(many, "Npc 0"));
		// A Catacombs variant's name is always tallied, so the full tally can still decide the variant.
		many = NpcKillTally.add(many, "Deviant spectre");
		many = NpcKillTally.add(many, "deviant spectre");
		assertEquals(NpcKillTally.MAX_NAMES + 1, many.size());
		assertEquals(2, NpcKillTally.killsOf(many, "Deviant spectre"));
		assertEquals("Deviant spectres", CatacombsVariants.decide("Aberrant spectres", many, 3));
		// Other new names still aren't, and a file's full tally keeps the variant's name.
		many = NpcKillTally.add(many, "Npc 99");
		assertEquals(0, NpcKillTally.killsOf(many, "Npc 99"));
		assertEquals(2, NpcKillTally.killsOf(NpcKillTally.sanitize(many), "Deviant spectre"));
		assertTrue(CatacombsVariants.isVariantNpcName(" GREATER nechryael"));
		assertFalse(CatacombsVariants.isVariantNpcName("Aberrant spectre"));
		assertFalse(CatacombsVariants.isVariantNpcName(null));

		// A file's tally is cleaned: no names, no kills and repeats.
		List<NpcKills> read = Arrays.asList(null, new NpcKills(null, 3), new NpcKills("Ankou", 0),
			new NpcKills("Ankou", 2), new NpcKills("ankou", 1));
		assertEquals(Collections.singletonList(new NpcKills("Ankou", 3)), NpcKillTally.sanitize(read));
		assertTrue(NpcKillTally.sanitize(null).isEmpty());
	}

	@Test
	public void damagedNpcsAreTakenOnceAndBounded()
	{
		DamagedNpcs npcs = new DamagedNpcs();
		npcs.damaged(0, 5);
		assertTrue(npcs.takeDied(0, 5));
		assertFalse("taken once", npcs.takeDied(0, 5));
		assertFalse("never damaged", npcs.takeDied(0, 6));

		npcs.damaged(0, 7);
		npcs.forget(0, 7);
		assertFalse("despawned", npcs.takeDied(0, 7));

		for (int i = 0; i < DamagedNpcs.MAX_NPCS + 10; i++)
		{
			npcs.damaged(0, i);
		}
		assertEquals(DamagedNpcs.MAX_NPCS, npcs.size());
		// The ones damaged longest ago went first.
		assertFalse(npcs.takeDied(0, 0));
		assertTrue(npcs.takeDied(0, DamagedNpcs.MAX_NPCS + 9));
		npcs.clear();
		assertEquals(0, npcs.size());
	}

	@Test
	public void damagedNpcsAreKeptPerWorldView()
	{
		// The same index in another world view (a boat's) is another NPC.
		DamagedNpcs npcs = new DamagedNpcs();
		npcs.damaged(0, 5);
		assertFalse(npcs.takeDied(3, 5));
		npcs.forget(3, 5);
		assertTrue(npcs.takeDied(0, 5));

		npcs.damaged(-1, 5);
		npcs.damaged(3, 5);
		assertEquals(2, npcs.size());
		assertTrue(npcs.takeDied(3, 5));
		assertFalse(npcs.takeDied(0, 5));
		assertTrue(npcs.takeDied(-1, 5));
		// Indexes and ids with the high bit set don't collide.
		npcs.damaged(0, -1);
		assertFalse(npcs.takeDied(-1, -1));
		assertTrue(npcs.takeDied(0, -1));
	}

	private static void assertVariant(String task, int constant, int id, String npcName, String name)
	{
		assertEquals(id, constant);
		CatacombsVariants.Variant variant = CatacombsVariants.forTask(task);
		assertEquals(task, variant.getTask());
		assertEquals(id, variant.getNpcId());
		assertEquals(npcName, variant.getNpcName());
		assertEquals(name, variant.getName());
	}

	/** A tally of name and kills pairs, in order. */
	static List<NpcKills> tally(Object... nameAndKills)
	{
		List<NpcKills> tally = new ArrayList<>();
		for (int i = 0; i < nameAndKills.length; i += 2)
		{
			tally.add(new NpcKills((String) nameAndKills[i], (Integer) nameAndKills[i + 1]));
		}
		return tally;
	}
}
