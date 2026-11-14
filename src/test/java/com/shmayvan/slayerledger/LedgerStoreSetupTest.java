package com.shmayvan.slayerledger;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.client.util.Filepath;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The 2.0 record fields in {@link LedgerStore}: they round-trip through save and load, the player's setup
 * overrides on saved records ({@link LedgerStore#updateSetup}) win over the detected tags and survive a reload, and the
 * schema bump keeps the policy (a newer file is refused, unknown members survive a migration and every rewrite).
 */
public class LedgerStoreSetupTest
{
	private static final long ACCOUNT = 123456789L;

	/** A 2.0 record with every field this issue adds set, as {@link LedgerStore} writes it (after the 1.x fields). */
	private static final String FULL_RECORD_JSON = "{\"schemaVersion\":3,\"id\":\"r1\",\"monster\":\"Dust devils\","
		+ "\"initialAmount\":10,\"kills\":10,\"activeMillis\":600000,\"startedAt\":1000,\"endedAt\":601000,"
		+ "\"completed\":true,\"partial\":false,\"superiorsSpawned\":0,\"bankTrips\":0,\"deaths\":0,\"xpGained\":{},"
		+ "\"locationRegion\":6556,\"spotId\":\"catacombs\",\"bossKills\":false,"
		+ "\"npcKills\":[{\"name\":\"Dust devil\",\"kills\":10},{\"name\":\"Ankou\",\"kills\":1}],"
		+ "\"cannonKills\":2,\"styleKills\":{\"BARRAGE\":2,\"MAGIC\":1},\"gearKills\":{\"MIXED\":1,\"PRAYER\":2},"
		+ "\"prayerBonusMin\":14,\"prayerBonusMax\":31,\"detectedCannon\":false,\"detectedStyle\":\"BARRAGE\","
		+ "\"detectedGear\":\"PRAYER\",\"gearOverride\":\"STRENGTH\"}";

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final Gson gson = new Gson();
	private File dir;
	private LedgerStore store;

	@Before
	public void setUp()
	{
		dir = new File(temp.getRoot(), "slayer-ledger");
		store = newStore();
	}

	private LedgerStore newStore()
	{
		return new LedgerStore(Filepath.Unchecked.getRooted(dir.toPath()), gson);
	}

	private File ledgerFile()
	{
		return new File(dir, ACCOUNT + ".json");
	}

	private String ledgerText() throws IOException
	{
		return new String(Files.readAllBytes(ledgerFile().toPath()), StandardCharsets.UTF_8);
	}

	private void writeLedger(String json) throws IOException
	{
		assertTrue(dir.isDirectory() || dir.mkdirs());
		Files.write(ledgerFile().toPath(), json.getBytes(StandardCharsets.UTF_8));
	}

	/** A 2.0 record of a finished session with kills by NPC, kill contexts, detected tags and an override. */
	private static LedgerRecord fullRecord()
	{
		final SetupKills kills = SetupKills.NONE
			.withKill(true, SetupStyle.BARRAGE, GearFocus.PRAYER, 14)
			.withKill(true, SetupStyle.BARRAGE, GearFocus.PRAYER, 31)
			.withKill(false, SetupStyle.MAGIC, GearFocus.MIXED, 20);
		final SessionTracker.Summary summary = SessionTracker.Summary.builder()
			.sessionId("r1")
			.accountHash(ACCOUNT)
			.monster("Dust devils")
			.hasTask(true)
			.initialAmount(10)
			.kills(10)
			.activeMillis(600_000)
			.startedAt(1_000)
			.endedAt(601_000)
			.completed(true)
			.npcKills(Arrays.asList(new NpcKills("Dust devil", 10), new NpcKills("Ankou", 1)))
			.setupKills(kills)
			.setupOverrides(SetupOverrides.of(null, null, GearFocus.STRENGTH))
			.build();
		return LedgerRecord.fromSummary(summary, false);
	}

	@Test
	public void everyNewFieldRoundTripsThroughSaveAndLoad() throws IOException
	{
		final LedgerRecord record = fullRecord();
		assertEquals(LedgerRecord.CURRENT_SCHEMA_VERSION, record.getSchemaVersion());
		assertEquals(Arrays.asList(new NpcKills("Dust devil", 10), new NpcKills("Ankou", 1)), record.getNpcKills());
		assertEquals(Integer.valueOf(2), record.getCannonKills());
		assertEquals(Integer.valueOf(14), record.getPrayerBonusMin());
		assertEquals(Integer.valueOf(31), record.getPrayerBonusMax());
		assertEquals("2 cannon kills of 10 is no cannon task", Boolean.FALSE, record.getDetectedCannon());
		assertEquals("2 of 3 styled kills", SetupStyle.BARRAGE, record.getDetectedStyle());
		assertEquals("the focus of most kills", GearFocus.PRAYER, record.getDetectedGear());
		assertEquals(GearFocus.STRENGTH, record.getGearOverride());
		assertNull(record.getCannonEstimated());

		store.append(ACCOUNT, record);
		final LedgerRecord loaded = newStore().load(ACCOUNT).get(0);
		assertEquals(record, loaded);

		// Every field, as written and read back (the spot as phase 6 will fill it).
		record.setSpotId("catacombs");
		record.setLocationRegion(6556);
		store.write(ACCOUNT, Collections.singletonList(record));
		assertEquals("{\"schemaVersion\":3,\"records\":[" + FULL_RECORD_JSON + "]}", ledgerText());
		assertEquals(record, newStore().load(ACCOUNT).get(0));
	}

	@Test
	public void overridesOnASavedRecordWinAndSurviveAReloadAndClearing() throws IOException
	{
		store.append(ACCOUNT, fullRecord());
		final SetupOverrides mine = SetupOverrides.of(true, SetupStyle.MELEE, null);
		assertEquals(1, store.updateSetup(ACCOUNT, "r1", mine).size());

		LedgerRecord loaded = newStore().load(ACCOUNT).get(0);
		SetupTags tags = SetupTags.of(loaded);
		assertEquals(mine, tags.getOverrides());
		assertTrue("the override wins", tags.isCannon());
		assertEquals(SetupStyle.MELEE, tags.getStyle());
		assertEquals("the gear override was cleared", GearFocus.PRAYER, tags.getGear());
		assertFalse("the detected values are kept", tags.isDetectedCannon());
		assertEquals(SetupStyle.BARRAGE, tags.getDetectedStyle());
		assertEquals("only the overrides changed", fullRecord().getNpcKills(), loaded.getNpcKills());
		assertEquals(Integer.valueOf(2), loaded.getCannonKills());

		final String before = ledgerText();
		store.updateSetup(ACCOUNT, "r1", mine);
		assertEquals("the same overrides don't rewrite the file", before, ledgerText());
		assertEquals("a missing record changes nothing", 1, store.updateSetup(ACCOUNT, "nope", SetupOverrides.NONE)
			.size());
		assertEquals(before, ledgerText());

		store.updateSetup(ACCOUNT, "r1", null);
		loaded = newStore().load(ACCOUNT).get(0);
		tags = SetupTags.of(loaded);
		assertEquals(SetupOverrides.NONE, tags.getOverrides());
		assertFalse("back to the detected cannon", tags.isCannon());
		assertFalse(ledgerText().contains("Override"));
	}

	@Test
	public void anOverrideOfAMigratedTagIsNoLongerAnEstimate() throws IOException
	{
		writeLedger("{\"schemaVersion\":2,\"records\":[{\"schemaVersion\":2,\"id\":\"old\",\"monster\":\"Dust devils\","
			+ "\"kills\":100,\"xpGained\":{\"MAGIC\":60000}}]}");
		assertTrue(SetupTags.of(store.load(ACCOUNT).get(0)).isStyleEstimated());

		store.updateSetup(ACCOUNT, "old", SetupOverrides.NONE.withStyle(SetupStyle.BARRAGE));
		final SetupTags tags = SetupTags.of(newStore().load(ACCOUNT).get(0));
		assertEquals(SetupStyle.BARRAGE, tags.getStyle());
		assertFalse("the player confirmed it", tags.isStyleEstimated());
		assertTrue("the cannon is still an estimate", tags.isCannonEstimated());
		assertTrue("the detected style still is one", tags.isDetectedStyleEstimated());
	}

	@Test
	public void aNewerFileIsRefusedForASetupEditAndLeftAlone() throws IOException
	{
		final String newer = "{\"schemaVersion\":" + (LedgerStore.FILE_SCHEMA_VERSION + 1) + ",\"records\":[{\"id\":"
			+ "\"future\",\"monster\":\"Hydras\",\"kills\":9,\"detectedStyle\":\"CHINCHOMPA\"}]}";
		writeLedger(newer);
		try
		{
			store.updateSetup(ACCOUNT, "future", SetupOverrides.of(true, null, null));
			fail("expected UnsupportedSchemaException");
		}
		catch (LedgerStore.UnsupportedSchemaException expected)
		{
			// expected
		}
		try
		{
			store.append(ACCOUNT, fullRecord());
			fail("expected UnsupportedSchemaException");
		}
		catch (LedgerStore.UnsupportedSchemaException expected)
		{
			// expected
		}
		assertEquals(newer, ledgerText());
		// Still read on a best-effort basis: a style this build doesn't know reads as Unknown.
		final LedgerRecord future = store.load(ACCOUNT).get(0);
		assertEquals("Hydras", future.getMonster());
		assertEquals(SetupStyle.UNKNOWN, SetupTags.of(future).getStyle());
	}

	@Test
	public void unknownMembersSurviveTheMigrationAndEveryRewrite() throws IOException
	{
		writeLedger("{\"schemaVersion\":2,\"records\":[{\"schemaVersion\":2,\"id\":\"old\",\"monster\":\"Kurask\","
			+ "\"kills\":50,\"xpGained\":{\"ATTACK\":9000},\"futureRecordField\":[1,2]}],\"futureTopField\":true}");
		final JsonElement recordField = gson.fromJson("[1,2]", JsonElement.class);

		store.updateSetup(ACCOUNT, "old", SetupOverrides.NONE.withGear(GearFocus.PRAYER));
		JsonObject file = gson.fromJson(ledgerText(), JsonObject.class);
		JsonObject old = file.getAsJsonArray("records").get(0).getAsJsonObject();
		assertEquals(LedgerStore.FILE_SCHEMA_VERSION, file.get("schemaVersion").getAsInt());
		assertEquals(3, old.get("schemaVersion").getAsInt());
		assertEquals("MELEE", old.get("detectedStyle").getAsString());
		assertEquals("PRAYER", old.get("gearOverride").getAsString());
		assertEquals(recordField, old.get("futureRecordField"));
		assertTrue(file.get("futureTopField").getAsBoolean());

		// A later build's unknown style value is kept as it was, beside a known override.
		final String withFutureStyle = ledgerText().replace("\"detectedStyle\":\"MELEE\"",
			"\"detectedStyle\":\"CHINCHOMPA\"");
		writeLedger(withFutureStyle);
		assertEquals("unknown style values read as Unknown", SetupStyle.UNKNOWN,
			SetupTags.of(store.load(ACCOUNT).get(0)).getStyle());
		store.updateNote(ACCOUNT, "old", "Leaf-bladed");
		old = gson.fromJson(ledgerText(), JsonObject.class).getAsJsonArray("records").get(0).getAsJsonObject();
		assertEquals("CHINCHOMPA", old.get("detectedStyle").getAsString());
		assertEquals(recordField, old.get("futureRecordField"));
		assertEquals("Leaf-bladed", old.get("note").getAsString());
	}

	@Test
	public void keptSideFilesAreWrittenAtTheNewVersion() throws IOException
	{
		assertTrue(store.writeUnsaved(ACCOUNT, fullRecord()));
		final File kept = new File(dir, ACCOUNT + ".unsaved-r1.json");
		final JsonObject json = gson.fromJson(new String(Files.readAllBytes(kept.toPath()), StandardCharsets.UTF_8),
			JsonObject.class);
		assertEquals(LedgerStore.FILE_SCHEMA_VERSION, json.get("schemaVersion").getAsInt());
		final List<LedgerRecord> saved = store.appendUnsaved(ACCOUNT);
		assertEquals(Collections.singletonList(fullRecord()), saved);
	}
}
