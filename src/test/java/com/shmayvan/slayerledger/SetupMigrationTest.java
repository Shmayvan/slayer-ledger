package com.shmayvan.slayerledger;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.util.Filepath;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The migration of 1.x records (V2 plan 4.3, {@link SetupMigration}). The ledger below is synthetic: made up for
 * this test in the shape a 1.x build writes (schema 2 file, records at version 2 or 1), never a player's real file.
 * Each record is one documented case, read through {@link LedgerStore} as the plugin reads it.
 */
public class SetupMigrationTest
{
	private static final long ACCOUNT = 987654321L;
	private static final int CANNONBALL = SupplyTracker.CANNONBALL;
	private static final int PRAYER_POTION = 2434;
	private static final int SHARK = 385;
	private static final int ZULRAH_SCALES = 12934;
	private static final int ADAMANT_DART = 810;

	/** One 1.x-shaped record: the members every 1.x record has, then {@code rest} (supplies, XP, ...). */
	private static String record(int version, String id, String monster, int kills, String rest)
	{
		return "{\"schemaVersion\":" + version + ",\"id\":\"" + id + "\",\"monster\":\"" + monster + "\","
			+ "\"creatureId\":42,\"initialAmount\":" + kills + ",\"originalAmount\":" + kills + ",\"kills\":" + kills
			+ ",\"activeMillis\":1800000,\"startedAt\":1762500000000,\"endedAt\":1762501800000,\"completed\":true,"
			+ "\"partial\":false" + rest + "}";
	}

	/** The synthetic 1.x ledger: one record per documented case. */
	private static final String LEDGER_1X = "{\"schemaVersion\":2,\"records\":["
		// A cannon task fought in melee: the cannon's Ranged XP is left out, so the melee XP decides.
		+ record(2, "cannon", "Kalphite", 150, ",\"supplies\":{\"" + CANNONBALL + "\":420,\"" + PRAYER_POTION
			+ "\":8},\"supplyDoses\":{\"" + PRAYER_POTION + "\":4},\"xpGained\":{\"ATTACK\":4000,\"HITPOINTS\":9000,"
			+ "\"RANGED\":21000,\"SLAYER\":12000,\"STRENGTH\":11000}") + ","
		// A melee task with no cannon.
		+ record(2, "melee", "Abyssal demons", 120, ",\"supplies\":{\"" + PRAYER_POTION + "\":20,\"" + SHARK
			+ "\":5},\"supplyDoses\":{\"" + PRAYER_POTION + "\":4},\"xpGained\":{\"ATTACK\":12000,\"DEFENCE\":3000,"
			+ "\"HITPOINTS\":15000,\"SLAYER\":18000,\"STRENGTH\":30000}") + ","
		// A barrage task: death and blood runes, almost all Magic XP.
		+ record(2, "barrage", "Dust devils", 200, ",\"supplies\":{\"" + ItemID.DEATHRUNE + "\":480,\""
			+ ItemID.BLOODRUNE + "\":240},\"xpGained\":{\"HITPOINTS\":22000,\"MAGIC\":95000,\"SLAYER\":26000}") + ","
		// A barrage task whose supplies were not counted (shaped like the 2026-11-08 Dust devils record): no supplies
		// at all and a Magic XP share of about 60%.
		+ record(2, "barrage-no-supplies", "Dust devils", 180, ",\"xpGained\":{\"ATTACK\":12000,\"HITPOINTS\":30000,"
			+ "\"MAGIC\":54000,\"SLAYER\":24000,\"STRENGTH\":24000}") + ","
		// A ranged task on longrange: its Defence XP alone can't be told from melee, so it is left out.
		+ record(2, "ranged", "Kurask", 130, ",\"supplies\":{\"" + ZULRAH_SCALES + "\":260,\"" + ADAMANT_DART
			+ "\":90},\"xpGained\":{\"DEFENCE\":18000,\"HITPOINTS\":12000,\"RANGED\":18000,\"SLAYER\":17000}") + ","
		// A cannon task done with ranged: the Ranged XP is left out, so nothing is left to estimate from.
		+ record(2, "cannon-ranged", "Black demons", 140, ",\"supplies\":{\"" + CANNONBALL + "\":510},"
			+ "\"xpGained\":{\"HITPOINTS\":14000,\"RANGED\":46000,\"SLAYER\":22000}") + ","
		// A version 1 record: no supplies and no XP kept.
		+ record(1, "no-xp", "Kurask", 50, "")
		+ "]}";

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final Gson gson = new Gson();
	private File dir;
	private File file;

	@Before
	public void setUp() throws IOException
	{
		dir = new File(temp.getRoot(), "slayer-ledger");
		assertTrue(dir.mkdirs());
		file = new File(dir, ACCOUNT + ".json");
		Files.write(file.toPath(), LEDGER_1X.getBytes(StandardCharsets.UTF_8));
	}

	private LedgerStore store()
	{
		return new LedgerStore(Filepath.Unchecked.getRooted(dir.toPath()), gson);
	}

	private Map<String, LedgerRecord> loaded() throws IOException
	{
		final Map<String, LedgerRecord> byId = new HashMap<>();
		for (LedgerRecord r : store().load(ACCOUNT))
		{
			byId.put(r.getId(), r);
		}
		assertEquals(7, byId.size());
		return byId;
	}

	/** The record's effective tags as {@code cannon/style/gear}, an estimate marked {@code ~}. */
	private static String tags(LedgerRecord r)
	{
		final SetupTags tags = SetupTags.of(r);
		return SetupTags.shown(SetupTags.cannonLabel(tags.isCannon()), tags.isCannonEstimated()) + "/"
			+ SetupTags.shown(tags.getStyle().getLabel(), tags.isStyleEstimated()) + "/"
			+ SetupTags.shown(tags.getGear().getLabel(), tags.isGearEstimated());
	}

	@Test
	public void everyDocumentedCaseGetsItsEstimatedTags() throws IOException
	{
		final Map<String, LedgerRecord> records = loaded();
		assertEquals("~Yes/~Melee/Unknown", tags(records.get("cannon")));
		assertEquals("~No/~Melee/Unknown", tags(records.get("melee")));
		assertEquals("~No/~Barrage/Unknown", tags(records.get("barrage")));
		assertEquals("~No/~Barrage/Unknown", tags(records.get("barrage-no-supplies")));
		assertEquals("~No/~Ranged/Unknown", tags(records.get("ranged")));
		assertEquals("Unknown is no estimate", "~Yes/Unknown/Unknown", tags(records.get("cannon-ranged")));
		assertEquals("~No/Unknown/Unknown", tags(records.get("no-xp")));
	}

	@Test
	public void migratedRecordsStoreTheirTagsAtTheCurrentVersionWithNothingElseChanged() throws IOException
	{
		final LedgerRecord barrage = loaded().get("barrage");
		assertEquals(LedgerRecord.CURRENT_SCHEMA_VERSION, barrage.getSchemaVersion());
		assertEquals(Boolean.FALSE, barrage.getDetectedCannon());
		assertEquals(Boolean.TRUE, barrage.getCannonEstimated());
		assertEquals(SetupStyle.BARRAGE, barrage.getDetectedStyle());
		assertEquals(Boolean.TRUE, barrage.getStyleEstimated());
		assertEquals(GearFocus.UNKNOWN, barrage.getDetectedGear());
		assertNull("Unknown is not an estimate", barrage.getGearEstimated());
		assertNull(barrage.getCannonOverride());
		assertNull(barrage.getStyleOverride());
		assertNull(barrage.getGearOverride());
		assertNull("1.x kept no kills by NPC", barrage.getNpcKills());
		assertNull("nor kill contexts", barrage.getCannonKills());
		assertNull(barrage.getStyleKills());
		assertNull("the spot is worked out later (phase 6)", barrage.getSpotId());
		assertEquals("a Dust devils task has no Slayer boss", Boolean.FALSE, barrage.getBossKills());

		final LedgerRecord oneX = gson.fromJson(gson.toJson(barrage), LedgerRecord.class);
		oneX.setSchemaVersion(2);
		oneX.setDetectedCannon(null);
		oneX.setCannonEstimated(null);
		oneX.setDetectedStyle(null);
		oneX.setStyleEstimated(null);
		oneX.setDetectedGear(null);
		oneX.setBossKills(null);
		final LedgerRecord asRead = gson.fromJson(LEDGER_1X, LedgerStore.LedgerFile.class).records.get(2);
		assertEquals("only the version, the tags and the boss flag were added", asRead, oneX);
	}

	@Test
	public void readingChangesNothingOnDiskAndTheNextWriteKeepsTheTags() throws IOException
	{
		loaded();
		assertEquals(LEDGER_1X, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));

		store().updateNote(ACCOUNT, "melee", "Whip");
		final String written = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
		assertTrue(written, written.startsWith("{\"schemaVersion\":" + LedgerStore.FILE_SCHEMA_VERSION + ","));
		assertTrue(written, written.contains("\"detectedStyle\":\"BARRAGE\",\"styleEstimated\":true"));
		assertEquals("~No/~Barrage/Unknown", tags(loaded().get("barrage")));
		assertEquals("Whip", loaded().get("melee").getNote());
	}

	@Test
	public void aMigratedRecordIsNeverMigratedAgain() throws IOException
	{
		store().updateNote(ACCOUNT, "cannon", "Cannon");
		// A later edit of the written record: the stored tags are what counts, not its supplies or XP any more.
		final String written = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
		final String edited = written.replace("\"detectedStyle\":\"MELEE\"", "\"detectedStyle\":\"MIXED\"");
		assertFalse(written.equals(edited));
		Files.write(file.toPath(), edited.getBytes(StandardCharsets.UTF_8));
		assertEquals("~Yes/~Mixed/Unknown", tags(loaded().get("cannon")));

		final LedgerRecord again = loaded().get("cannon");
		assertFalse(SetupMigration.migrate(again));
		assertEquals(SetupStyle.MIXED, again.getDetectedStyle());
	}

	@Test
	public void aKeptRecordFromA1xBuildIsMigratedWhenItIsSaved() throws IOException
	{
		final String kept = "{\"schemaVersion\":2,\"record\":" + record(2, "kept", "Dust devils", 100,
			",\"supplies\":{\"" + ItemID.DEATHRUNE + "\":200,\"" + ItemID.BLOODRUNE + "\":100},"
				+ "\"xpGained\":{\"MAGIC\":50000}") + "}";
		Files.write(new File(dir, ACCOUNT + ".unsaved-kept.json").toPath(), kept.getBytes(StandardCharsets.UTF_8));
		final List<LedgerRecord> records = store().appendUnsaved(ACCOUNT);
		assertEquals(8, records.size());
		assertEquals("~No/~Barrage/Unknown", tags(records.get(7)));
		assertEquals("~No/~Barrage/Unknown", tags(store().load(ACCOUNT).get(7)));
	}

	// ---- The rules ----

	private static Map<String, Long> xp(Object... skillAndXp)
	{
		final Map<String, Long> xp = new TreeMap<>();
		for (int i = 0; i < skillAndXp.length; i += 2)
		{
			xp.put((String) skillAndXp[i], ((Number) skillAndXp[i + 1]).longValue());
		}
		return xp;
	}

	private static Map<Integer, Long> supplies(int... idAndQuantity)
	{
		final Map<Integer, Long> supplies = new TreeMap<>();
		for (int i = 0; i < idAndQuantity.length; i += 2)
		{
			supplies.put(idAndQuantity[i], (long) idAndQuantity[i + 1]);
		}
		return supplies;
	}

	@Test
	public void theStyleCoveringAtLeastHalfWinsAndATieOrNoneIsMixed()
	{
		final Map<Integer, Long> food = supplies(SHARK, 3);
		assertEquals(SetupStyle.MELEE, SetupMigration.estimateStyle(xp("STRENGTH", 50, "RANGED", 25, "MAGIC", 25),
			food, false));
		assertEquals("a tie", SetupStyle.MIXED, SetupMigration.estimateStyle(xp("STRENGTH", 50, "RANGED", 50), food,
			false));
		assertEquals("none at half", SetupStyle.MIXED,
			SetupMigration.estimateStyle(xp("ATTACK", 40, "RANGED", 35, "MAGIC", 25), food, false));
		assertEquals("Hitpoints, Slayer and Prayer don't count", SetupStyle.UNKNOWN,
			SetupMigration.estimateStyle(xp("HITPOINTS", 900, "SLAYER", 500, "PRAYER", 100), food, false));
		assertEquals("no XP kept", SetupStyle.UNKNOWN, SetupMigration.estimateStyle(null, food, false));
		assertEquals(SetupStyle.UNKNOWN, SetupMigration.estimateStyle(Collections.emptyMap(), food, false));
	}

	@Test
	public void defenceCountsAsMeleeBesideAttackOrStrengthOrAsADefenceStance()
	{
		final Map<Integer, Long> food = supplies(SHARK, 3);
		assertEquals("controlled melee", SetupStyle.MELEE,
			SetupMigration.estimateStyle(xp("ATTACK", 30, "STRENGTH", 30, "DEFENCE", 30, "RANGED", 80), food, false));
		assertEquals("longrange", SetupStyle.RANGED,
			SetupMigration.estimateStyle(xp("DEFENCE", 100, "RANGED", 100), food, false));
		assertEquals("Defence alone is a melee Defence stance", SetupStyle.MELEE,
			SetupMigration.estimateStyle(xp("DEFENCE", 100), food, false));
		assertEquals("a cannon task on longrange", SetupStyle.UNKNOWN,
			SetupMigration.estimateStyle(xp("DEFENCE", 100, "RANGED", 300), supplies(CANNONBALL, 50), true));
	}

	@Test
	public void magicIsBarrageOnlyWithAncientRunesOrNoSupplies()
	{
		final Map<String, Long> magic = xp("MAGIC", 1000, "HITPOINTS", 300);
		assertEquals("barrage, blitz", SetupStyle.BARRAGE, SetupMigration.estimateStyle(magic,
			supplies(ItemID.DEATHRUNE, 40, ItemID.BLOODRUNE, 20, ItemID.WATERRUNE, 60), false));
		assertEquals("ice burst with a water staff", SetupStyle.BARRAGE, SetupMigration.estimateStyle(magic,
			supplies(ItemID.DEATHRUNE, 40, ItemID.CHAOSRUNE, 80), false));
		assertEquals("a trident: one chaos rune per death rune", SetupStyle.MAGIC, SetupMigration.estimateStyle(magic,
			supplies(ItemID.DEATHRUNE, 40, ItemID.CHAOSRUNE, 40, ItemID.FIRERUNE, 200, ItemID.COINS, 400), false));
		assertEquals("a Sanguinesti staff: blood runes alone", SetupStyle.MAGIC,
			SetupMigration.estimateStyle(magic, supplies(ItemID.BLOODRUNE, 80), false));
		assertEquals("Tumeken's shadow", SetupStyle.MAGIC,
			SetupMigration.estimateStyle(magic, supplies(ItemID.SOULRUNE, 40, ItemID.CHAOSRUNE, 100), false));
		assertEquals("wave spells", SetupStyle.MAGIC,
			SetupMigration.estimateStyle(magic, supplies(ItemID.BLOODRUNE, 40, ItemID.FIRERUNE, 200), false));
		assertEquals("no supplies kept", SetupStyle.BARRAGE, SetupMigration.estimateStyle(magic, null, false));
		assertEquals("supplies counted, none used", SetupStyle.BARRAGE,
			SetupMigration.estimateStyle(magic, supplies(), false));
		assertEquals("cannonballs and no runes", SetupStyle.MAGIC,
			SetupMigration.estimateStyle(magic, supplies(CANNONBALL, 100), true));
	}

	@Test
	public void theCannonComesFromTheCannonballsFired()
	{
		final LedgerRecord granite = gson.fromJson(record(2, "g", "Kalphite", 10, ",\"supplies\":{\""
			+ SupplyTracker.GRANITE_CANNONBALL + "\":30}"), LedgerRecord.class);
		assertTrue(SetupMigration.migrate(granite));
		assertEquals("~Yes/Unknown/Unknown", tags(granite));

		final LedgerRecord none = gson.fromJson(record(2, "n", "Kalphite", 10, ",\"supplies\":{\"" + SHARK + "\":3}"),
			LedgerRecord.class);
		assertTrue(SetupMigration.migrate(none));
		assertEquals("~No/Unknown/Unknown", tags(none));
	}

	@Test
	public void aCurrentRecordIsLeftAlone()
	{
		final LedgerRecord current = new LedgerRecord();
		current.setXpGained(xp("MAGIC", 1000));
		assertFalse(SetupMigration.migrate(current));
		assertNull(current.getDetectedStyle());
		assertFalse(SetupMigration.migrate(null));
	}

	@Test
	public void aMeleeDefenceStanceCountsAsMeleeButLongrangeAndDefensiveCastingDont()
	{
		final Map<Integer, Long> food = supplies(ItemID.SHARK, 5);
		// Defence stance: 4 Defence XP per damage, a few Magic XP from an alch or a thrall.
		assertEquals(SetupStyle.MELEE, SetupMigration.estimateStyle(xp("DEFENCE", 40000, "MAGIC", 600), null, false));
		assertEquals(SetupStyle.MELEE, SetupMigration.estimateStyle(xp("DEFENCE", 40000), food, false));
		// Longrange: as much Ranged XP as Defence XP.
		assertEquals(SetupStyle.RANGED, SetupMigration.estimateStyle(xp("DEFENCE", 100, "RANGED", 100), food, false));
		// Defensive casting: less Defence XP than Magic XP.
		assertEquals(SetupStyle.BARRAGE, SetupMigration.estimateStyle(xp("DEFENCE", 30000, "MAGIC", 70000),
			supplies(ItemID.DEATHRUNE, 400, ItemID.BLOODRUNE, 200), false));
	}

	@Test
	public void aPoweredStaffWithThrallsIsNotBarrage()
	{
		final Map<String, Long> magic = xp("MAGIC", 90000, "HITPOINTS", 20000);
		// Sanguinesti staff (2 blood runes a cast) with a few death runes from Superior thralls.
		assertEquals(SetupStyle.MAGIC, SetupMigration.estimateStyle(magic,
			supplies(ItemID.BLOODRUNE, 1000, ItemID.DEATHRUNE, 40), false));
		// Tumeken's shadow (5 chaos runes a cast) with a few death runes.
		assertEquals(SetupStyle.MAGIC, SetupMigration.estimateStyle(magic,
			supplies(ItemID.CHAOSRUNE, 2500, ItemID.SOULRUNE, 1000, ItemID.DEATHRUNE, 40), false));
		// Ice Barrage (4 death, 2 blood) and Blood Blitz (2 death, 4 blood) are still Barrage; so is Ice Burst.
		assertEquals(SetupStyle.BARRAGE, SetupMigration.estimateStyle(magic,
			supplies(ItemID.DEATHRUNE, 400, ItemID.BLOODRUNE, 200), false));
		assertEquals(SetupStyle.BARRAGE, SetupMigration.estimateStyle(magic,
			supplies(ItemID.DEATHRUNE, 200, ItemID.BLOODRUNE, 400), false));
		assertEquals(SetupStyle.BARRAGE, SetupMigration.estimateStyle(magic,
			supplies(ItemID.DEATHRUNE, 200, ItemID.CHAOSRUNE, 400), false));
	}

	@Test
	public void aMigratedRecordGetsThisStepsOwnVersion()
	{
		final LedgerRecord record = new LedgerRecord();
		record.setSchemaVersion(2);
		assertTrue(SetupMigration.migrate(record));
		assertEquals(SetupMigration.MIGRATED_VERSION, record.getSchemaVersion());
	}

}
