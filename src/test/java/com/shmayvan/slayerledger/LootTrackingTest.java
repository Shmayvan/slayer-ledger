package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Loot tally in the session tracker, snapshots and ledger records.
 */
public class LootTrackingTest
{
	private static final int DEMON_ID = 42;
	private static final int DRAGON_ID = 43;
	private static final long ACCOUNT = 555L;
	private static final int[] COUNTERS = {100, 2, 0};
	private static final int BONES = 526;
	private static final int RUNE_SCIMITAR = 1333;

	/** Fixed prices: bones 100 GE / 0 HA, scimitar 15,000 GE / 15,360 HA, coins at face value via the helper. */
	private static final ItemValuation.PriceLookup PRICES = new ItemValuation.PriceLookup()
	{
		@Override
		public int canonicalize(int itemId)
		{
			return itemId;
		}

		@Override
		public long gePrice(int canonicalId)
		{
			return canonicalId == BONES ? 100 : canonicalId == RUNE_SCIMITAR ? 15_000 : 0;
		}

		@Override
		public long haPrice(int canonicalId)
		{
			return canonicalId == RUNE_SCIMITAR ? 15_360 : 0;
		}

		@Override
		public boolean isTradeable(int canonicalId)
		{
			return true;
		}
	};

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private long now;
	private SessionTracker tracker;
	private final Gson gson = new Gson();

	@Before
	public void setUp()
	{
		now = 1_000_000L;
		tracker = new SessionTracker(() -> now);
	}

	private static ItemTally tally(int... idQty)
	{
		ItemTally t = new ItemTally();
		for (int i = 0; i < idQty.length; i += 2)
		{
			t.add(idQty[i], idQty[i + 1]);
		}
		return t;
	}

	private void startDemonTask()
	{
		tracker.observe("Abyssal demons", DEMON_ID, 50, 150, false);
		assertTrue(tracker.play(ACCOUNT));
	}

	// ---- Tracker ----

	@Test
	public void lootIsRecordedOnlyWhileRunning()
	{
		tracker.observe("Abyssal demons", DEMON_ID, 50, 150, false);
		assertFalse("idle", tracker.addLoot(tally(BONES, 1)));
		assertTrue(tracker.play(ACCOUNT));
		assertTrue(tracker.addLoot(tally(BONES, 1, ItemValuation.COINS, 300)));
		tracker.pause();
		assertFalse("paused", tracker.addLoot(tally(BONES, 5)));
		tracker.play(ACCOUNT);
		assertTrue(tracker.addLoot(tally(BONES, 1)));
		tracker.autoPause();
		assertFalse("auto-paused", tracker.addLoot(tally(BONES, 5)));

		ItemTally loot = tracker.getLoot();
		assertEquals(2, loot.get(BONES));
		assertEquals(300, loot.get(ItemValuation.COINS));

		SessionTracker.Summary summary = tracker.stopAndReset();
		assertEquals(loot, summary.getLoot());
		assertFalse("stopped/idle", tracker.addLoot(tally(BONES, 1)));
		assertTrue(tracker.getLoot().isEmpty());
	}

	@Test
	public void lootFromAnyNpcCountsAndEmptyLootIsIgnored()
	{
		startDemonTask();
		// Not filtered by task monster: the tracker has no NPC information at all.
		assertTrue(tracker.addLoot(tally(RUNE_SCIMITAR, 1)));
		assertFalse(tracker.addLoot(new ItemTally()));
		assertFalse(tracker.addLoot(null));
		assertEquals(1, tracker.getLoot().size());
	}

	@Test
	public void newSessionStartsWithEmptyLoot()
	{
		startDemonTask();
		tracker.addLoot(tally(BONES, 3));
		tracker.stopAndReset();
		assertTrue(tracker.play(ACCOUNT));
		assertTrue(tracker.getLoot().isEmpty());
		assertTrue(tracker.activeSummary().getLoot().isEmpty());
	}

	@Test
	public void summaryLootIsACopy()
	{
		startDemonTask();
		tracker.addLoot(tally(BONES, 3));
		SessionTracker.Summary summary = tracker.activeSummary();
		tracker.addLoot(tally(BONES, 3));
		summary.getLoot().add(BONES, 100);
		assertEquals(3, summary.getLoot().get(BONES));
		assertEquals(6, tracker.getLoot().get(BONES));
	}

	// ---- Snapshot and restore ----

	private SessionSnapshot snapshotWithLoot()
	{
		startDemonTask();
		tracker.addLoot(tally(BONES, 4, ItemValuation.COINS, 1_000));
		now += 30_000;
		tracker.observe("Abyssal demons", DEMON_ID, 46, 150, false);
		tracker.autoPause();
		return SessionSnapshot.fromSummary(tracker.activeSummary(), COUNTERS, now);
	}

	@Test
	public void snapshotWithLootRoundTripsThroughJson()
	{
		SessionSnapshot s = snapshotWithLoot();
		assertEquals(Long.valueOf(4), s.getLoot().get(BONES));
		String json = gson.toJson(s);
		assertTrue(json, json.contains("\"loot\":{\"526\":4,\"995\":1000}"));
		SessionSnapshot back = gson.fromJson(json, SessionSnapshot.class);
		assertEquals(s, back);
		assertEquals(s.getLoot(), back.getLoot());
	}

	@Test
	public void snapshotWithoutLootOmitsField()
	{
		startDemonTask();
		SessionSnapshot s = SessionSnapshot.fromSummary(tracker.activeSummary(), COUNTERS, now);
		assertNull(s.getLoot());
		assertFalse(gson.toJson(s).contains("loot"));
	}

	@Test
	public void oldSnapshotWithoutLootRestores()
	{
		String v1 = "{\"schemaVersion\":1,\"sessionId\":\"s1\",\"accountHash\":555,\"monster\":\"Abyssal demons\","
			+ "\"creatureId\":42,\"initialAmount\":50,\"originalAmount\":150,\"kills\":4,\"activeMillis\":30000,"
			+ "\"startedAt\":1000,\"savedAt\":2000,\"remaining\":46,\"completionCounters\":[100,2,0]}";
		SessionSnapshot s = gson.fromJson(v1, SessionSnapshot.class);
		assertNull(s.getLoot());
		assertTrue(s.toSummary().getLoot().isEmpty());

		tracker.observe("Abyssal demons", DEMON_ID, 46, 150, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, tracker.restore(s, COUNTERS));
		assertTrue(tracker.getLoot().isEmpty());
		assertTrue(tracker.play(ACCOUNT));
		tracker.addLoot(tally(BONES, 1));
		assertEquals(1, tracker.getLoot().get(BONES));
	}

	@Test
	public void fileRestoreKeepsLootAndAddsToIt()
	{
		SessionSnapshot s = gson.fromJson(gson.toJson(snapshotWithLoot()), SessionSnapshot.class);
		SessionTracker restored = new SessionTracker(() -> now);
		restored.observe("Abyssal demons", DEMON_ID, 46, 150, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, restored.restore(s, COUNTERS));
		assertEquals(4, restored.getLoot().get(BONES));
		assertFalse("restored session is paused", restored.addLoot(tally(BONES, 10)));
		assertTrue(restored.play(ACCOUNT));
		restored.addLoot(tally(BONES, 1, RUNE_SCIMITAR, 1));

		LedgerRecord record = LedgerRecord.fromSummary(restored.stopAndReset(), true);
		assertEquals(Long.valueOf(5), record.getLoot().get(BONES));
		assertEquals(Long.valueOf(1_000), record.getLoot().get(ItemValuation.COINS));
		assertEquals(Long.valueOf(1), record.getLoot().get(RUNE_SCIMITAR));
	}

	@Test
	public void restoreClearsLootOfAnyEarlierSession()
	{
		SessionSnapshot s = snapshotWithLoot();
		SessionTracker other = new SessionTracker(() -> now);
		other.observe("Abyssal demons", DEMON_ID, 46, 150, false);
		other.play(ACCOUNT);
		other.addLoot(tally(RUNE_SCIMITAR, 9));
		other.stopAndReset();
		assertEquals(SessionTracker.RestoreResult.RESTORED, other.restore(s, COUNTERS));
		assertEquals(0, other.getLoot().get(RUNE_SCIMITAR));
		assertEquals(4, other.getLoot().get(BONES));
	}

	@Test
	public void mismatchedFileRestoreSavesLootInPartialRecord()
	{
		SessionSnapshot s = gson.fromJson(gson.toJson(snapshotWithLoot()), SessionSnapshot.class);
		SessionTracker later = new SessionTracker(() -> now);
		later.observe("Black dragons", DRAGON_ID, 20, 20, false);
		assertEquals(SessionTracker.RestoreResult.MISMATCH, later.restore(s, COUNTERS));
		assertTrue(later.getLoot().isEmpty());

		LedgerRecord record = LedgerRecord.fromSummary(s.toSummary(), true);
		assertTrue(record.isPartial());
		assertEquals(Long.valueOf(4), record.getLoot().get(BONES));
		assertEquals(Long.valueOf(1_000), record.getLoot().get(ItemValuation.COINS));
	}

	@Test
	public void inMemoryReloginKeepsLoot()
	{
		SessionSnapshot logout = snapshotWithLoot();
		// Logout: the session stays in memory, auto-paused; the current task is forgotten.
		tracker.clearCurrentTask();
		tracker.observe("Abyssal demons", DEMON_ID, 46, 150, false);
		assertTrue(tracker.activeSessionStillMatches(logout, COUNTERS));
		assertEquals(4, tracker.getLoot().get(BONES));
		assertTrue(tracker.play(ACCOUNT));
		tracker.addLoot(tally(BONES, 2));
		assertEquals(6, tracker.getLoot().get(BONES));
	}

	@Test
	public void inMemoryReloginMismatchSavesLootInPartialRecord()
	{
		SessionSnapshot logout = snapshotWithLoot();
		tracker.clearCurrentTask();
		tracker.observe("Black dragons", DRAGON_ID, 20, 20, false);
		assertFalse(tracker.activeSessionStillMatches(logout, COUNTERS));
		// The plugin then ends the session as RESTORE_MISMATCH.
		SessionTracker.Summary summary = tracker.stopAndReset();
		assertNotNull(summary);
		LedgerRecord record = LedgerRecord.fromSummary(summary, true);
		assertEquals(Long.valueOf(4), record.getLoot().get(BONES));
	}

	// ---- Records ----

	@Test
	public void recordCarriesLootAndCapturesValues()
	{
		startDemonTask();
		tracker.addLoot(tally(BONES, 10, RUNE_SCIMITAR, 2, ItemValuation.COINS, 2_500));
		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), true);
		assertEquals(LedgerRecord.CURRENT_SCHEMA_VERSION, record.getSchemaVersion());
		assertEquals(3, record.getLoot().size());
		assertNull("not valued until saved", record.getLootGeValue());

		record.captureValues(PRICES);
		assertEquals(Long.valueOf(10 * 100 + 2 * 15_000 + 2_500), record.getLootGeValue());
		assertEquals(Long.valueOf(2 * 15_360 + 2_500), record.getLootHaValue());
		assertEquals(Long.valueOf(15_000), record.getUnitGePrices().get(RUNE_SCIMITAR));
		assertEquals(Long.valueOf(1), record.getUnitHaPrices().get(ItemValuation.COINS));
		assertEquals(3, record.getUnitGePrices().size());
		assertNull(record.getSupplies());
		assertNull(record.getSuppliesGeValue());
		assertNull(record.getSuppliesHaValue());
	}

	@Test
	public void recordWithoutLootIsValuedAtZero()
	{
		startDemonTask();
		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), true);
		assertNull(record.getLoot());
		record.captureValues(PRICES);
		assertEquals(Long.valueOf(0), record.getLootGeValue());
		assertEquals(Long.valueOf(0), record.getLootHaValue());
		assertNull(record.getUnitGePrices());
		assertNull(record.getUnitHaPrices());
	}

	@Test
	public void suppliesAreValuedWhenPresent()
	{
		LedgerRecord record = new LedgerRecord();
		Map<Integer, Long> supplies = new TreeMap<>();
		supplies.put(BONES, 3L);
		record.setSupplies(supplies);
		record.captureValues(PRICES);
		assertEquals(Long.valueOf(300), record.getSuppliesGeValue());
		assertEquals(Long.valueOf(0), record.getSuppliesHaValue());
		assertEquals(Long.valueOf(100), record.getUnitGePrices().get(BONES));
		assertEquals(Long.valueOf(0), record.getLootGeValue());
	}

	@Test
	public void valuedRecordRoundTripsThroughJson()
	{
		startDemonTask();
		tracker.addLoot(tally(BONES, 10, RUNE_SCIMITAR, 2));
		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), false);
		record.captureValues(PRICES);
		String json = gson.toJson(record);
		assertFalse("null supplies are omitted", json.contains("supplies"));
		LedgerRecord back = gson.fromJson(json, LedgerRecord.class);
		assertEquals(record, back);
		assertEquals(Long.valueOf(31_000), back.getLootGeValue());
	}

	@Test
	public void oldRecordJsonLoadsWithNullItemFields()
	{
		String v1 = "{\"schemaVersion\":1,\"id\":\"old\",\"monster\":\"Kurask\",\"initialAmount\":50,\"kills\":50,"
			+ "\"completed\":true}";
		LedgerRecord r = gson.fromJson(v1, LedgerRecord.class);
		assertEquals(1, r.getSchemaVersion());
		assertEquals(50, r.getKills());
		assertNull(r.getLoot());
		assertNull(r.getSupplies());
		assertNull(r.getUnitGePrices());
		assertNull(r.getUnitHaPrices());
		assertNull(r.getLootGeValue());
		assertNull(r.getLootHaValue());
		assertNull(r.getSuppliesGeValue());
		assertNull(r.getSuppliesHaValue());
	}

	@Test
	public void version1LedgerFileLoadsAndIsUpgradedOnAppend() throws IOException
	{
		File dir = new File(temp.getRoot(), "slayer-ledger");
		assertTrue(dir.mkdirs());
		File file = new File(dir, ACCOUNT + ".json");
		String v1 = "{\"schemaVersion\":1,\"records\":[{\"schemaVersion\":1,\"id\":\"old\",\"monster\":\"Kurask\","
			+ "\"initialAmount\":50,\"kills\":50,\"completed\":true}]}";
		Files.write(file.toPath(), v1.getBytes(StandardCharsets.UTF_8));
		LedgerStore store = new LedgerStore(Filepath.Unchecked.getRooted(dir.toPath()), gson);

		startDemonTask();
		tracker.addLoot(tally(BONES, 10));
		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), true);
		record.captureValues(PRICES);
		store.append(ACCOUNT, record);

		String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
		assertTrue(json, json.startsWith("{\"schemaVersion\":2,"));
		List<LedgerRecord> loaded = store.load(ACCOUNT);
		assertEquals(2, loaded.size());
		assertNull(loaded.get(0).getLoot());
		assertEquals(1, loaded.get(0).getSchemaVersion());
		assertEquals(Long.valueOf(10), loaded.get(1).getLoot().get(BONES));
		assertEquals(Long.valueOf(1_000), loaded.get(1).getLootGeValue());
	}

	@Test
	public void activeFileWithLootRoundTripsThroughStore() throws IOException
	{
		LedgerStore store = new LedgerStore(Filepath.Unchecked.getRooted(temp.getRoot().toPath().resolve("slayer-ledger")), gson);
		SessionSnapshot s = snapshotWithLoot();
		store.writeActive(ACCOUNT, s);
		SessionSnapshot back = store.readActive(ACCOUNT);
		assertEquals(s, back);
		assertEquals(SessionSnapshot.CURRENT_SCHEMA_VERSION, back.getSchemaVersion());
	}

	/** Prices not loaded: every tradeable item reads 0 GE. */
	private static final ItemValuation.PriceLookup UNLOADED = new ItemValuation.PriceLookup()
	{
		@Override
		public int canonicalize(int itemId)
		{
			return itemId;
		}

		@Override
		public long gePrice(int canonicalId)
		{
			return 0;
		}

		@Override
		public long haPrice(int canonicalId)
		{
			return PRICES.haPrice(canonicalId);
		}

		@Override
		public boolean isTradeable(int canonicalId)
		{
			return true;
		}
	};

	@Test
	public void unavailablePricesAreMissingAndCounted()
	{
		LedgerRecord record = new LedgerRecord();
		Map<Integer, Long> loot = new TreeMap<>();
		loot.put(BONES, 10L);
		loot.put(RUNE_SCIMITAR, 1L);
		loot.put(ItemValuation.COINS, 300L);
		record.setLoot(loot);
		Map<Integer, Long> supplies = new TreeMap<>();
		supplies.put(BONES, 2L);
		record.setSupplies(supplies);
		record.captureValues(UNLOADED);

		assertEquals(LedgerRecord.CURRENT_SCHEMA_VERSION, record.getSchemaVersion());
		assertFalse("omitted, not stored as 0", record.getUnitGePrices().containsKey(BONES));
		assertFalse(record.getUnitGePrices().containsKey(RUNE_SCIMITAR));
		assertEquals(Long.valueOf(1), record.getUnitGePrices().get(ItemValuation.COINS));
		assertEquals("known prices still sum", Long.valueOf(300), record.getLootGeValue());
		assertEquals(Long.valueOf(15_360 + 300), record.getLootHaValue());
		assertEquals(Long.valueOf(0), record.getSuppliesGeValue());
		assertEquals(Integer.valueOf(2), record.getLootUnpricedItems());
		assertEquals(Integer.valueOf(1), record.getSuppliesUnpricedItems());

		LedgerRecord back = gson.fromJson(gson.toJson(record), LedgerRecord.class);
		assertEquals(record, back);
	}

	@Test
	public void fullyPricedRecordHasNoUnpricedMarkers()
	{
		LedgerRecord record = new LedgerRecord();
		Map<Integer, Long> loot = new TreeMap<>();
		loot.put(BONES, 10L);
		record.setLoot(loot);
		record.captureValues(PRICES);
		assertNull(record.getLootUnpricedItems());
		assertNull(record.getSuppliesUnpricedItems());
		assertFalse(gson.toJson(record).contains("Unpriced"));
	}
}
