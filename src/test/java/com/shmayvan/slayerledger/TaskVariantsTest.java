package com.shmayvan.slayerledger;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.util.Filepath;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The boss table ({@link SlayerBosses}) and the variant and boss flag rule ({@link TaskVariants}, V2 plan 4.4):
 * the variant is the kind with the most task kills in the tally, leaving out superiors; kills of the task's Slayer boss
 * set the boss flag; Catacombs variants keep their 1.x names; migrated records get the flag from their kills by NPC or
 * else their boss kill counts. {@link HarnessTaskVariantsTest} covers the wiring.
 */
public class TaskVariantsTest
{
	private static final int GARGOYLES = 46;
	/** A boss task's creature key: {@code -2 - bossId}. */
	private static final int BOSS_TASK = -2 - 7;
	private static final String GG = "Grotesque Guardians";

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private static List<NpcKills> tally(Object... nameAndKills)
	{
		return CatacombsVariantsTest.tally(nameAndKills);
	}

	private static TaskVariants.Decision decide(String task, List<NpcKills> tally, int taskKills)
	{
		return TaskVariants.decide(task, GARGOYLES, tally, taskKills);
	}

	// ---- The table ----

	@Test
	public void everyBossRowResolvesItsNpcNamesAndHasASource()
	{
		// The ids and names of the RuneLite 1.13.4 gameval NpcID constants (their javadoc gives the NPC's name).
		assertBoss("Gargoyles", GG, "Grotesque_Guardians", 15387555L,
			NpcID.GARGBOSS_DUSK_DEATH, 7889, "Dusk", NpcID.GARGBOSS_DAWN_DEATH, 7885, "Dawn");
		assertBoss("Cave kraken", "Kraken", "Kraken", 15383981L, NpcID.SLAYER_KRAKEN_BOSS, 494, "Kraken");
		assertBoss("Hellhounds", "Cerberus", "Cerberus", 15397004L, NpcID.CERBERUS_ATTACKING, 5862, "Cerberus");
		assertBoss("Smoke devils", "Thermonuclear smoke devil", "Thermonuclear_smoke_devil", 15369525L,
			NpcID.SMOKE_DEVIL_BOSS, 499, "Thermonuclear smoke devil");
		assertBoss("Abyssal demons", "Abyssal Sire", "Abyssal_Sire", 15392068L,
			NpcID.ABYSSALSIRE_SIRE_APOCALYPSE, 5908, "Abyssal Sire");
		assertBoss("Hydras", "Alchemical Hydra", "Alchemical_Hydra", 15375803L,
			NpcID.HYDRABOSS_FINALDEATH, 8622, "Alchemical Hydra");
		assertBoss("Araxytes", "Araxxor", "Araxxor", 15396994L, NpcID.ARAXXOR, 13668, "Araxxor");
		assertBoss("Spiders", "Araxxor", "Araxxor", 15396994L, NpcID.ARAXXOR, 13668, "Araxxor");
		assertBoss("Gryphons", "Shellbane gryphon", "Shellbane_gryphon", 15387213L,
			NpcID.GRYPHON_BOSS, 14860, "Shellbane gryphon");
		assertEquals(9, SlayerBosses.all().size());

		for (SlayerBosses.Boss boss : SlayerBosses.all())
		{
			// The task is one of the game's Slayer tasks, as RuneLite's Slayer plugin names them.
			assertNotNull(boss.getTask(), TaskIcons.itemId(boss.getTask()));
			assertSource(boss.getSource());
			assertFalse(boss.getNpcs().isEmpty());
			for (SlayerBosses.Npc npc : boss.getNpcs())
			{
				// Each NPC name resolves to the boss: one kill of it on the task is the boss variant and sets the flag.
				assertTrue(npc.getName(), SlayerBosses.isBossNpcName(npc.getName()));
				assertTrue(TaskVariants.isDecidingNpcName(npc.getName()));
				TaskVariants.Decision decision = decide(boss.getTask(), tally(npc.getName(), 1), 1);
				assertEquals(npc.getName(), boss.getName(), decision.getVariant());
				assertTrue(decision.isVariantBoss());
				assertTrue(decision.isBossKills());
			}
			assertEquals(boss.getName(), TaskVariants.bossName(boss.getTask()));
		}

		// The data's attribution, shaped as slayer-spots.json's.
		assertEquals("Old School RuneScape Wiki", SlayerBosses.SOURCE);
		assertEquals("CC BY-NC-SA 3.0", SlayerBosses.LICENSE);
		assertEquals("https://creativecommons.org/licenses/by-nc-sa/3.0/", SlayerBosses.LICENSE_URL);
		assertEquals("Old School RuneScape Wiki contributors", SlayerBosses.CONTRIBUTORS);
		assertEquals("2026-11-09", SlayerBosses.RETRIEVED);
		assertTrue(SlayerBosses.ATTRIBUTION.contains("CC BY-NC-SA 3.0"));
		assertSource(SlayerBosses.LIST_SOURCE);
		assertSource(SlayerBosses.SUPERIORS_SOURCE);
	}

	@Test
	public void theSuperiorsAreTheGameNamesOfTheTasksWithAKind()
	{
		Map<Integer, String> expected = new HashMap<>();
		expected.put(NpcID.SUPERIOR_GARGOYLE, "Marble gargoyle");
		expected.put(NpcID.SUPERIOR_SMOKE_DEVIL, "Nuclear smoke devil");
		expected.put(NpcID.SUPERIOR_ABYSSAL_DEMON, "Greater abyssal demon");
		expected.put(NpcID.SUPERIOR_HYDRA, "Colossal Hydra");
		expected.put(NpcID.SUPERIOR_ARAXYTE, "Dreadborn Araxyte");
		expected.put(NpcID.SUPERIOR_GRYPHON, "Dire gryphon");
		expected.put(NpcID.SUPERIOR_ABBERANT_SPECTRE, "Abhorrent spectre");
		expected.put(NpcID.SUPERIOR_KOUREND_SPECTRE, "Repugnant spectre");
		expected.put(NpcID.SUPERIOR_NECHRYAEL, "Nechryarch");
		expected.put(NpcID.SUPERIOR_BLOODVELD, "Insatiable Bloodveld");
		expected.put(NpcID.SUPERIOR_KOUREND_BLOODVELD, "Insatiable mutated Bloodveld");
		expected.put(NpcID.SUPERIOR_JELLY, "Vitreous Jelly");
		expected.put(NpcID.SUPERIOR_KOUREND_JELLY, "Vitreous Warped Jelly");
		expected.put(NpcID.SUPERIOR_CHILLED_JELLY, "Vitreous Chilled Jelly");
		expected.put(NpcID.SUPERIOR_BANSHEE, "Screaming banshee");
		expected.put(NpcID.SUPERIOR_KOUREND_BANSHEE, "Screaming twisted banshee");
		Map<Integer, String> actual = new HashMap<>();
		for (SlayerBosses.Npc npc : SlayerBosses.superiors())
		{
			actual.put(npc.getId(), npc.getName());
			assertTrue(TaskVariants.isDecidingNpcName(npc.getName()));
		}
		assertEquals(expected, actual);
		assertEquals(16, SlayerBosses.superiors().size());
		assertEquals(7407, NpcID.SUPERIOR_GARGOYLE);
		assertEquals(13680, NpcID.SUPERIOR_ARAXYTE);
		assertTrue(SlayerBosses.isSuperiorName(" MARBLE gargoyle "));
		assertFalse(SlayerBosses.isSuperiorName("Gargoyle"));
		assertFalse(SlayerBosses.isSuperiorName(null));
		assertFalse(TaskVariants.isDecidingNpcName("Gargoyle"));
	}

	// ---- The variant ----

	@Test
	public void grotesqueGuardiansKillsOnAGargoyleTaskSetTheVariantAndTheBossFlag()
	{
		// Each kill kills Dusk and Dawn and takes one off the task.
		TaskVariants.Decision decision = decide("Gargoyles", tally("Dusk", 5, "Dawn", 5), 5);
		assertEquals(GG, decision.getVariant());
		assertTrue(decision.isVariantBoss());
		assertTrue(decision.isBossKills());
		assertEquals(GG, decision.displayName("Gargoyles", false));

		// With Double Trouble each kill takes two off the task: still the Guardians, not a tie with gargoyles.
		assertEquals(GG, decide("Gargoyles", tally("Dusk", 5, "Dawn", 5), 10).getVariant());
		// Only Dusk tallied (Dawn's death not seen): the same kills.
		assertEquals(GG, decide("Gargoyles", tally("Dusk", 5), 5).getVariant());
		// A Guardians kill is one kill, not Dusk's plus Dawn's: 3 of them lose to 4 gargoyles.
		assertNull(decide("Gargoyles", tally("Gargoyle", 4, "Dusk", 3, "Dawn", 3), 7).getVariant());
		// Mostly gargoyles: standard, with the boss flag.
		TaskVariants.Decision mostlyGargoyles = decide("Gargoyles", tally("Gargoyle", 30, "Dusk", 3, "Dawn", 3), 33);
		assertNull(mostlyGargoyles.getVariant());
		assertTrue(mostlyGargoyles.isBossKills());
		assertEquals("Gargoyles + boss", mostlyGargoyles.displayName("Gargoyles", false));
	}

	@Test
	public void aCaveKrakenTaskWithAFewKrakenKillsIsTheDominantKind()
	{
		TaskVariants.Decision fewKraken = decide("Cave kraken", tally("Cave kraken", 100, "Kraken", 3), 103);
		assertNull("the cave kraken did most of the task", fewKraken.getVariant());
		assertFalse(fewKraken.isVariantBoss());
		assertTrue("any boss kill sets the flag", fewKraken.isBossKills());
		assertEquals("Cave kraken + boss", fewKraken.displayName("Cave kraken", false));

		TaskVariants.Decision mostlyKraken = decide("Cave kraken", tally("Kraken", 60, "Cave kraken", 40), 100);
		assertEquals("Kraken", mostlyKraken.getVariant());
		assertEquals("Kraken", mostlyKraken.displayName("Cave kraken", false));

		// A tie is the standard monster; no boss kill, no flag.
		assertNull(decide("Cave kraken", tally("Kraken", 20, "Cave kraken", 20), 40).getVariant());
		TaskVariants.Decision noBoss = decide("Cave kraken", tally("Cave kraken", 40), 40);
		assertFalse(noBoss.isBossKills());
		assertEquals("Cave kraken", noBoss.displayName("Cave kraken", false));
		// The Kraken's tentacles and other monsters that die around it are no task kills: they can't outweigh it.
		assertEquals("Kraken", decide("Cave kraken", tally("Kraken", 10, "Enormous Tentacle", 40), 10).getVariant());
	}

	@Test
	public void superiorsAreLeftOutOfTheVariant()
	{
		// 7 task kills: 3 Guardians, 2 Marble gargoyles (superiors), 2 gargoyles. Counted as gargoyles, the 2
		// superiors would make the standard monster 4 kills to the Guardians' 3.
		assertEquals(GG, decide("Gargoyles", tally("Dusk", 3, "Dawn", 3, "Marble gargoyle", 2, "Gargoyle", 2), 7)
			.getVariant());
		// The same for a Catacombs variant: a Repugnant spectre is neither a Deviant nor an Aberrant spectre.
		assertEquals("Deviant spectres", decide("Aberrant spectres",
			tally("Deviant spectre", 3, "Repugnant spectre", 2, "Aberrant spectre", 2), 7).getVariant());
		// And a superior is never the variant, however many.
		assertNull(decide("Abyssal demons", tally("Greater abyssal demon", 5, "Abyssal demon", 1), 6).getVariant());
	}

	@Test
	public void killsTheTallyMissedCountForNeither()
	{
		// 10 task kills, 5 tallied as the variant (the others' NPCs were never seen dying, or were killed before the
		// tally existed): the tally decides. In older versions the 5 missing kills counted as the standard monster.
		assertEquals("Deviant spectres", decide("Aberrant spectres", tally("Deviant spectre", 5), 10).getVariant());
		assertEquals("Deviant spectres", CatacombsVariants.decide("Aberrant spectres", tally("Deviant spectre", 5), 10));
		// No task kills yet: the standard monster, but a boss kill already sets the flag.
		TaskVariants.Decision early = decide("Gargoyles", tally("Dusk", 1, "Dawn", 1), 0);
		assertNull(early.getVariant());
		assertTrue(early.isBossKills());
	}

	@Test
	public void aFilesTallyIsReadAsItsCleanCopyWouldBe()
	{
		// A repeated name adds up (5 Guardians kills beat 4 gargoyles); an entry without a name or kills counts for
		// nothing, as NpcKillTally.sanitize would clean it.
		List<NpcKills> raw = new ArrayList<>(tally("Dusk", 2, "Gargoyle", 4, " dusk ", 3, "Dusk", 0));
		raw.add(null);
		raw.add(new NpcKills(null, 7));
		raw.add(new NpcKills("Kraken", -3));
		assertEquals(GG, decide("Gargoyles", raw, 9).getVariant());
		assertEquals(decide("Gargoyles", NpcKillTally.sanitize(raw), 9), decide("Gargoyles", raw, 9));
		assertFalse(decide("Cave kraken", raw, 9).isBossKills());
	}

	@Test
	public void bossTasksAndTasksWithoutAKindHaveNeither()
	{
		// Boss tasks are their own pool: no variant and no flag, even on a regular task's name.
		assertSame(TaskVariants.Decision.NONE, TaskVariants.decide(GG, BOSS_TASK, tally("Dusk", 5, "Dawn", 5), 5));
		assertSame(TaskVariants.Decision.NONE,
			TaskVariants.decide("Gargoyles", BOSS_TASK, tally("Dusk", 5, "Dawn", 5), 5));
		assertSame(TaskVariants.Decision.NONE, decide("Dust devils", tally("Dust devil", 5), 5));
		assertSame(TaskVariants.Decision.NONE, decide(null, tally("Dusk", 5), 5));
		assertSame(TaskVariants.Decision.NONE, decide(" ", tally("Dusk", 5), 5));
		// A boss of another task is no kind of this one.
		assertFalse(decide("Hellhounds", tally("Dusk", 5, "Dawn", 5), 5).isBossKills());
		// Names match by the one key.
		assertEquals(GG, decide("  GARGOYLES ", tally(" dusk", 2), 2).getVariant());
	}

	@Test
	public void theLiveNameAndItsGuard()
	{
		TaskVariants.Decision deviant = decide("Aberrant spectres", tally("Deviant spectre", 2), 2);
		assertEquals("Deviant spectres (cannon)", deviant.displayName("Aberrant spectres", true));
		TaskVariants.Decision hellhounds = decide("Hellhounds", tally("Hellhound", 20, "Cerberus", 1), 21);
		assertEquals("Hellhounds (cannon) + boss", hellhounds.displayName("Hellhounds", true));
		assertEquals("Hellhounds", TaskVariants.Decision.NONE.displayName("Hellhounds", false));
		assertNull(TaskVariants.Decision.NONE.displayName(null, false));

		// Shown only with its own task: a separate call may have seen another session.
		assertSame(hellhounds, hellhounds.forTask(" hellhounds"));
		assertSame(TaskVariants.Decision.NONE, hellhounds.forTask("Gargoyles"));
		assertSame(TaskVariants.Decision.NONE, hellhounds.forTask(null));
		assertEquals(hellhounds, decide("Hellhounds", tally("Hellhound", 20, "Cerberus", 1), 21));
		assertEquals("Hellhounds", hellhounds.getTask());
	}

	@Test
	public void theTallyAlwaysTakesTheNamesTheDecisionReads()
	{
		List<NpcKills> full = new ArrayList<>();
		for (int i = 0; i < NpcKillTally.MAX_NAMES; i++)
		{
			full = NpcKillTally.add(full, "Npc " + i);
		}
		full = NpcKillTally.add(full, "Dusk");
		full = NpcKillTally.add(full, "Marble gargoyle");
		full = NpcKillTally.add(full, "Gargoyle");
		assertEquals(1, NpcKillTally.killsOf(full, "Dusk"));
		assertEquals(1, NpcKillTally.killsOf(full, "Marble gargoyle"));
		assertEquals("other new names wait", 0, NpcKillTally.killsOf(full, "Gargoyle"));
		assertEquals(NpcKillTally.MAX_NAMES + 2, NpcKillTally.sanitize(full).size());
	}

	// ---- Saved records ----

	@Test
	public void aCatacombsVariantReadsTheSameForAMigratedRecordAndANewOne() throws IOException
	{
		// A 1.x record, as a 1.x build saved a Deviant spectres task: its variant and nothing else.
		File dir = temp.newFolder("slayer-ledger");
		Files.write(new File(dir, "5.json").toPath(), ("{\"schemaVersion\":2,\"records\":[{\"schemaVersion\":2,"
			+ "\"id\":\"old\",\"monster\":\"Aberrant spectres\",\"creatureId\":7,\"kills\":10,\"activeMillis\":600000,"
			+ "\"completed\":true,\"partial\":false,\"variant\":\"Deviant spectres\"}]}")
			.getBytes(StandardCharsets.UTF_8));
		LedgerRecord migrated = new LedgerStore(Filepath.Unchecked.getRooted(dir.toPath()), new Gson()).load(5L)
			.get(0);

		// A task this version saves, done on Deviant spectres.
		SessionTracker.Summary summary = SessionTracker.Summary.builder()
			.sessionId("new")
			.monster("Aberrant spectres")
			.creatureId(7)
			.hasTask(true)
			.initialAmount(10)
			.kills(10)
			.completed(true)
			.npcKills(tally("Deviant spectre", 9, "Repugnant spectre", 1, "Ankou", 3))
			.build();
		LedgerRecord saved = LedgerRecord.fromSummary(summary, false);

		for (LedgerRecord record : Arrays.asList(migrated, saved))
		{
			assertEquals("Deviant spectres", record.getVariant());
			assertEquals("Deviant spectres", record.groupName());
			assertEquals("Deviant spectres", LedgerHistory.monster(record));
			assertEquals(Boolean.FALSE, record.getBossKills());
			assertEquals("<br>Catacombs variant of Aberrant spectres", LedgerHistory.variantTooltip(record));
		}
	}

	@Test
	public void aNewRecordKeepsTheVariantAndTheBossFlag()
	{
		LedgerRecord gg = LedgerRecord.fromSummary(summary("Gargoyles", 6, tally("Dusk", 6, "Dawn", 6), null), false);
		assertEquals(GG, gg.getVariant());
		assertEquals(Boolean.TRUE, gg.getBossKills());
		assertEquals(GG, gg.groupName());
		assertEquals("<br>Slayer boss of Gargoyles", LedgerHistory.variantTooltip(gg));

		LedgerRecord kraken = LedgerRecord.fromSummary(
			summary("Cave kraken", 103, tally("Cave kraken", 100, "Kraken", 3), null), false);
		assertNull(kraken.getVariant());
		assertEquals(Boolean.TRUE, kraken.getBossKills());
		assertEquals("Cave kraken", kraken.groupName());
		assertEquals("<br>Boss kills: Kraken", LedgerHistory.variantTooltip(kraken));

		LedgerRecord plain = LedgerRecord.fromSummary(summary("Gargoyles", 5, tally("Gargoyle", 5), null), false);
		assertNull(plain.getVariant());
		assertEquals(Boolean.FALSE, plain.getBossKills());
		assertEquals("", LedgerHistory.variantTooltip(plain));

		LedgerRecord bossTask = LedgerRecord.fromSummary(
			summary(GG, 6, tally("Dusk", 6, "Dawn", 6), null, BOSS_TASK), false);
		assertNull("boss tasks are their own pool", bossTask.getVariant());
		assertEquals(Boolean.FALSE, bossTask.getBossKills());
		assertEquals(GG, bossTask.groupName());
	}

	@Test
	public void aMigratedRecordGetsTheBossFlagFromItsKillsByNpcElseItsBossKillCounts()
	{
		List<BossKillCount> ggCount = Collections.singletonList(new BossKillCount(GG, 120, 123, 3));

		// With kills by NPC, they decide: a Guardians kill, or none (whatever the kill counts say).
		assertMigrated(true, oneX("Gargoyles", GARGOYLES, tally("Gargoyle", 50, "Dusk", 2, "Dawn", 2), null));
		assertMigrated(false, oneX("Gargoyles", GARGOYLES, tally("Gargoyle", 50), ggCount));
		// Without them, the boss kill counts do.
		assertMigrated(true, oneX("Gargoyles", GARGOYLES, null, ggCount));
		assertMigrated(true, oneX("Gargoyles", null, Collections.emptyList(),
			Collections.singletonList(new BossKillCount(" grotesque GUARDIANS", 5, 5, 1))));
		assertMigrated(false, oneX("Gargoyles", GARGOYLES, null, null));
		assertMigrated(false, oneX("Gargoyles", GARGOYLES, null,
			Collections.singletonList(new BossKillCount("Kraken", 5, 5, 1))));
		// A boss task is its own pool, and a task without a Slayer boss has none.
		assertMigrated(false, oneX(GG, BOSS_TASK, null, ggCount));
		assertMigrated(false, oneX("Dust devils", 49, tally("Dust devil", 50), null));

		// A record that has the flag keeps it, and a 2.0 record is not migrated.
		LedgerRecord kept = oneX("Gargoyles", GARGOYLES, null, ggCount);
		kept.setBossKills(false);
		assertTrue(SetupMigration.migrate(kept));
		assertEquals(Boolean.FALSE, kept.getBossKills());
		assertFalse(TaskVariants.migrate(kept));
		LedgerRecord current = oneX("Gargoyles", GARGOYLES, null, ggCount);
		current.setSchemaVersion(LedgerRecord.CURRENT_SCHEMA_VERSION);
		assertFalse(SetupMigration.migrate(current));
		assertNull(current.getBossKills());
		// Its variant is kept as saved.
		LedgerRecord variant = oneX("Gargoyles", GARGOYLES, tally("Dusk", 9, "Dawn", 9), null);
		assertTrue(SetupMigration.migrate(variant));
		assertNull("a 1.x record's variant is never decided again", variant.getVariant());
	}

	private static void assertMigrated(boolean expected, LedgerRecord record)
	{
		assertNull(record.getBossKills());
		assertTrue(SetupMigration.migrate(record));
		assertEquals(Boolean.valueOf(expected), record.getBossKills());
	}

	/** A 1.x-shaped record (schema version 2) of {@code monster} with the given kills by NPC and boss kill counts. */
	private static LedgerRecord oneX(String monster, Integer creatureId, List<NpcKills> npcKills,
		List<BossKillCount> bossKillCounts)
	{
		LedgerRecord record = new LedgerRecord();
		record.setSchemaVersion(2);
		record.setId("r");
		record.setMonster(monster);
		record.setCreatureId(creatureId);
		record.setKills(50);
		record.setCompleted(true);
		record.setNpcKills(npcKills);
		record.setBossKillCounts(bossKillCounts);
		return record;
	}

	private static SessionTracker.Summary summary(String monster, int kills, List<NpcKills> npcKills,
		List<BossKillCount> bossKillCounts)
	{
		return summary(monster, kills, npcKills, bossKillCounts, GARGOYLES);
	}

	private static SessionTracker.Summary summary(String monster, int kills, List<NpcKills> npcKills,
		List<BossKillCount> bossKillCounts, int creatureId)
	{
		return SessionTracker.Summary.builder()
			.sessionId(monster + kills)
			.monster(monster)
			.creatureId(creatureId)
			.hasTask(true)
			.initialAmount(kills)
			.kills(kills)
			.completed(true)
			.npcKills(npcKills)
			.sessionLoot(SessionLoot.builder().bossKillCounts(bossKillCounts).build())
			.build();
	}

	private static void assertBoss(String task, String name, String path, long revision, Object... npcs)
	{
		SlayerBosses.Boss boss = SlayerBosses.forTask(task);
		assertNotNull(task, boss);
		assertEquals(task, boss.getTask());
		assertEquals(name, boss.getName());
		assertEquals("https://oldschool.runescape.wiki/w/" + path, boss.getSource().getPage());
		assertEquals(revision, boss.getSource().getRevision());
		assertEquals(npcs.length / 3, boss.getNpcs().size());
		for (int i = 0; i < npcs.length; i += 3)
		{
			assertEquals("the constant's id", npcs[i + 1], npcs[i]);
			SlayerBosses.Npc npc = boss.getNpcs().get(i / 3);
			assertEquals(npcs[i + 1], npc.getId());
			assertEquals(npcs[i + 2], npc.getName());
		}
	}

	private static void assertSource(SlayerBosses.Source source)
	{
		assertNotNull(source);
		assertFalse(source.getArticle().trim().isEmpty());
		assertTrue(source.getPage(), source.getPage().startsWith("https://oldschool.runescape.wiki/w/"));
		assertTrue(source.getRevision() > 0);
	}

	@Test
	public void aRecordSavedWithoutTheFlagReadsItFromItsKillsWithoutChangingIt()
	{
		final LedgerRecord record = new LedgerRecord();
		record.setMonster("Gargoyles");
		record.setKills(10);
		record.setNpcKills(java.util.Arrays.asList(new NpcKills("Dusk", 3), new NpcKills("Gargoyle", 7)));
		record.setSchemaVersion(LedgerRecord.CURRENT_SCHEMA_VERSION);
		assertTrue(TaskVariants.hasBossKills(record));
		assertNull("the record itself is left as saved", record.getBossKills());
		record.setBossKills(false);
		assertFalse("a saved flag wins", TaskVariants.hasBossKills(record));
		assertFalse(TaskVariants.hasBossKills(null));
	}

}
