package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Supplies in the session tracker, snapshots and ledger records, including per-dose potion valuation.
 */
public class SuppliesSessionTest
{
	private static final int DEMON_ID = 42;
	private static final int DRAGON_ID = 43;
	private static final long ACCOUNT = 555L;
	private static final int[] COUNTERS = {100, 2, 0};
	private static final int SHARK = SupplyTrackerTest.SHARK;
	private static final int PRAYER_4 = SupplyTrackerTest.PRAYER_4;
	private static final int PRAYER_3 = SupplyTrackerTest.PRAYER_3;
	private static final int ARROW = SupplyTrackerTest.ADAMANT_ARROW;

	/** Shark 1,000 GE / 180 HA; prayer potion(4) 8,002 GE / 228 HA; arrows 10 GE / 48 HA. */
	private static final ItemValuation.PriceLookup PRICES = new ItemValuation.PriceLookup()
	{
		@Override
		public int canonicalize(int itemId)
		{
			return itemId;
		}

		@Override
		public long gePrice(int id)
		{
			return id == SHARK ? 1_000 : id == PRAYER_4 ? 8_002 : id == ARROW ? 10 : 0;
		}

		@Override
		public long haPrice(int id)
		{
			return id == SHARK ? 180 : id == PRAYER_4 ? 228 : id == ARROW ? 48 : 0;
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

	private static Map<Integer, Integer> doses(int id, int n)
	{
		Map<Integer, Integer> m = new TreeMap<>();
		m.put(id, n);
		return m;
	}

	private void startDemonTask()
	{
		tracker.observe("Abyssal demons", DEMON_ID, 50, 150, false);
		assertTrue(tracker.play(ACCOUNT));
	}

	// ---- Tracker ----

	@Test
	public void suppliesAreRecordedOnlyWhileRunning()
	{
		tracker.observe("Abyssal demons", DEMON_ID, 50, 150, false);
		assertFalse("idle", tracker.addSupplies(tally(SHARK, 1), null, null));
		assertTrue(tracker.play(ACCOUNT));
		assertTrue(tracker.addSupplies(tally(SHARK, 2, PRAYER_4, 3), null, doses(PRAYER_4, 4)));
		tracker.pause();
		assertFalse("paused", tracker.addSupplies(tally(SHARK, 5), null, null));
		tracker.play(ACCOUNT);
		tracker.autoPause();
		assertFalse("auto-paused", tracker.addSupplies(tally(SHARK, 5), null, null));

		assertEquals(2, tracker.getSupplies().get(SHARK));
		assertEquals(3, tracker.getSupplies().get(PRAYER_4));
		assertEquals(Integer.valueOf(4), tracker.getSupplyDoses().get(PRAYER_4));

		SessionTracker.Summary summary = tracker.stopAndReset();
		assertEquals(2, summary.getSupplies().get(SHARK));
		assertEquals(Integer.valueOf(4), summary.getSupplyDoses().get(PRAYER_4));
		assertFalse("stopped/idle", tracker.addSupplies(tally(SHARK, 1), null, null));
		assertTrue(tracker.getSupplies().isEmpty());
		assertTrue(tracker.getSupplyDoses().isEmpty());
	}

	@Test
	public void returnedAmmoReducesUsedButNeverBelowZero()
	{
		startDemonTask();
		tracker.addSupplies(tally(ARROW, 10, SHARK, 1), null, null);
		assertTrue(tracker.addSupplies(null, tally(ARROW, 4), null));
		assertEquals(6, tracker.getSupplies().get(ARROW));
		tracker.addSupplies(null, tally(ARROW, 50), null);
		assertEquals(0, tracker.getSupplies().get(ARROW));
		assertFalse("nothing left to offset", tracker.addSupplies(null, tally(ARROW, 1), null));
		assertEquals(1, tracker.getSupplies().get(SHARK));
		assertFalse(tracker.getSupplies().itemIds().contains(ARROW));
	}

	/**
	 * a corrected cannon pickup reports the same item as used and returned in one call (the guessed load moved
	 * to granite, and the granite that came back). The used amount must be merged before the returned one is taken
	 * off, or the returned granite would be clamped against nothing and the full load counted as fired.
	 */
	@Test
	public void usedIsMergedBeforeReturnedOfTheSameItemIsSubtracted()
	{
		startDemonTask();
		final int regular = SupplyTracker.CANNONBALL;
		final int granite = SupplyTracker.GRANITE_CANNONBALL;
		// The cannon's varp emptied a tick before the cannonballs came back, under the regular guess.
		assertTrue(tracker.addSupplies(tally(regular, 26), null, null));
		// The pickup shows granite: 30 granite loaded, 26 granite back, and the guessed regular load offset.
		assertTrue(tracker.addSupplies(tally(granite, 30), tally(regular, 30, granite, 26), null));
		assertEquals(4, tracker.getSupplies().get(granite));
		assertEquals(0, tracker.getSupplies().get(regular));
	}

	@Test
	public void heldCompletionGetsNoSuppliesButANewSessionDoes()
	{
		tracker.observe("Abyssal demons", DEMON_ID, 1, 150, false);
		assertTrue(tracker.play(ACCOUNT));
		tracker.addSupplies(tally(SHARK, 2), null, null);
		tracker.observe(null, SessionTracker.NO_CREATURE, 0, true);
		assertTrue(tracker.holdCompletion());
		assertFalse("held session is no longer running", tracker.addSupplies(tally(SHARK, 3), null, null));

		tracker.observe("Black dragons", DRAGON_ID, 20, 20, false);
		assertTrue(tracker.play(ACCOUNT));
		assertTrue(tracker.addSupplies(tally(SHARK, 4), null, null));
		SessionTracker.Summary held = tracker.takePendingCompletion();
		assertEquals(2, held.getSupplies().get(SHARK));
		assertEquals(4, tracker.getSupplies().get(SHARK));
	}

	@Test
	public void lateLootKeepsTheHeldSessionsSupplies()
	{
		tracker.observe("Abyssal demons", DEMON_ID, 1, 150, false);
		assertTrue(tracker.play(ACCOUNT));
		tracker.addSupplies(tally(PRAYER_4, 2), null, doses(PRAYER_4, 4));
		tracker.observe(null, SessionTracker.NO_CREATURE, 0, true);
		assertTrue(tracker.holdCompletion());
		tracker.addLoot(tally(526, 1));
		SessionTracker.Summary held = tracker.takePendingCompletion();
		assertEquals(1, held.getLoot().get(526));
		assertEquals(2, held.getSupplies().get(PRAYER_4));
		assertEquals(Integer.valueOf(4), held.getSupplyDoses().get(PRAYER_4));
	}

	@Test
	public void endToEndFromTickDiffsToValuedRecord()
	{
		SupplyTracker supplies = new SupplyTracker(SupplyTrackerTest.CLASSIFIER);
		startDemonTask();
		feed(supplies, SupplyTrackerTest.obs(SHARK, 10, PRAYER_4, 3, ARROW, 100));
		feed(supplies, SupplyTrackerTest.obs(SHARK, 8, PRAYER_4, 2, PRAYER_3, 1, ARROW, 90));
		feed(supplies, SupplyTrackerTest.obs(SHARK, 8, PRAYER_4, 2, PRAYER_3, 1, ARROW, 95));

		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), false);
		assertEquals(Long.valueOf(2), record.getSupplies().get(SHARK));
		assertEquals(Long.valueOf(1), record.getSupplies().get(PRAYER_4));
		assertEquals("10 fired, 5 picked up", Long.valueOf(5), record.getSupplies().get(ARROW));
		assertEquals(Collections.singletonMap(PRAYER_4, 4), record.getSupplyDoses());

		record.captureValues(PRICES);
		// 2 sharks + 1 dose of a 4-dose potion (8,002 / 4 = 2,000.5, rounded) + 5 arrows.
		assertEquals(Long.valueOf(2 * 1_000 + 2_001 + 5 * 10), record.getSuppliesGeValue());
		assertEquals(Long.valueOf(2 * 180 + 57 + 5 * 48), record.getSuppliesHaValue());
		assertEquals("unit prices stay per item", Long.valueOf(8_002), record.getUnitGePrices().get(PRAYER_4));
	}

	private void feed(SupplyTracker supplies, SupplyTracker.Observation o)
	{
		SupplyTracker.Usage usage = supplies.update(o, tracker.getState() == SessionTracker.State.RUNNING, false,
			Collections.emptySet());
		tracker.addSupplies(usage.getUsed(), usage.getReturned(), usage.getDoses());
	}

	// ---- Valuation ----

	@Test
	public void potionSuppliesAreValuedPerDose()
	{
		LedgerRecord record = new LedgerRecord();
		Map<Integer, Long> used = new TreeMap<>();
		used.put(PRAYER_4, 10L);
		used.put(SHARK, 3L);
		record.setSupplies(used);
		record.setSupplyDoses(doses(PRAYER_4, 4));
		record.captureValues(PRICES);
		// 10 doses = 2.5 potions: 8,002 * 10 / 4 = 20,005.
		assertEquals(Long.valueOf(20_005 + 3_000), record.getSuppliesGeValue());
		assertEquals(Long.valueOf(570 + 540), record.getSuppliesHaValue());
	}

	@Test
	public void doseTotalRoundsToTheNearestCoin()
	{
		Map<Integer, Long> prices = Collections.singletonMap(PRAYER_4, 1_001L);
		assertEquals("3,003 / 4 = 750.75", 751, ItemValuation.total(tally(PRAYER_4, 3), prices, doses(PRAYER_4, 4)));
		assertEquals("1,001 / 4 = 250.25", 250, ItemValuation.total(tally(PRAYER_4, 1), prices, doses(PRAYER_4, 4)));
		assertEquals("no dose entry: per item", 3_003, ItemValuation.total(tally(PRAYER_4, 3), prices, null));
	}

	@Test
	public void recordWithoutSuppliesHasNoSupplyValues()
	{
		startDemonTask();
		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), true);
		assertNull(record.getSupplies());
		assertNull(record.getSupplyDoses());
		record.captureValues(PRICES);
		assertNull(record.getSuppliesGeValue());
		assertNull(record.getSuppliesHaValue());
		String json = gson.toJson(record);
		assertFalse(json, json.contains("supplies") || json.contains("supplyDoses"));
	}

	@Test
	public void dosesAreKeptOnlyForRecordedSupplies()
	{
		startDemonTask();
		tracker.addSupplies(tally(PRAYER_4, 2, ARROW, 3), null, doses(PRAYER_4, 4));
		tracker.addSupplies(null, tally(PRAYER_4, 5), null);
		// Returns only ever come from ammo and cannonballs, but a fully offset id must not leave a stray dose entry.
		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), false);
		assertNull(record.getSupplyDoses());
		assertEquals(Long.valueOf(3), record.getSupplies().get(ARROW));
	}

	// ---- Snapshots and restore ----

	private SessionSnapshot snapshotWithSupplies()
	{
		startDemonTask();
		tracker.addSupplies(tally(SHARK, 4, PRAYER_4, 6), null, doses(PRAYER_4, 4));
		now += 30_000;
		tracker.observe("Abyssal demons", DEMON_ID, 46, 150, false);
		tracker.autoPause();
		return SessionSnapshot.fromSummary(tracker.activeSummary(), COUNTERS, now);
	}

	@Test
	public void snapshotWithSuppliesRoundTripsThroughJson()
	{
		SessionSnapshot s = snapshotWithSupplies();
		String json = gson.toJson(s);
		assertTrue(json, json.contains("\"supplies\":{\"385\":4,\"2434\":6}"));
		assertTrue(json, json.contains("\"supplyDoses\":{\"2434\":4}"));
		SessionSnapshot back = gson.fromJson(json, SessionSnapshot.class);
		assertEquals(s, back);
		assertEquals(SessionSnapshot.CURRENT_SCHEMA_VERSION, back.getSchemaVersion());
	}

	@Test
	public void fileRestoreKeepsSuppliesAndAddsToThem()
	{
		SessionSnapshot s = gson.fromJson(gson.toJson(snapshotWithSupplies()), SessionSnapshot.class);
		SessionTracker restored = new SessionTracker(() -> now);
		restored.observe("Abyssal demons", DEMON_ID, 46, 150, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, restored.restore(s, COUNTERS));
		assertEquals(4, restored.getSupplies().get(SHARK));
		assertFalse("restored session is paused", restored.addSupplies(tally(SHARK, 10), null, null));
		assertTrue(restored.play(ACCOUNT));
		restored.addSupplies(tally(SHARK, 1, PRAYER_4, 1), null, doses(PRAYER_4, 4));

		LedgerRecord record = LedgerRecord.fromSummary(restored.stopAndReset(), true);
		assertEquals(Long.valueOf(5), record.getSupplies().get(SHARK));
		assertEquals(Long.valueOf(7), record.getSupplies().get(PRAYER_4));
		assertEquals(Integer.valueOf(4), record.getSupplyDoses().get(PRAYER_4));
	}

	@Test
	public void mismatchedRestoreSavesSuppliesInPartialRecord()
	{
		SessionSnapshot s = gson.fromJson(gson.toJson(snapshotWithSupplies()), SessionSnapshot.class);
		SessionTracker later = new SessionTracker(() -> now);
		later.observe("Black dragons", DRAGON_ID, 20, 20, false);
		assertEquals(SessionTracker.RestoreResult.MISMATCH, later.restore(s, COUNTERS));
		assertTrue(later.getSupplies().isEmpty());

		LedgerRecord record = LedgerRecord.fromSummary(s.toSummary(), true);
		assertEquals(Long.valueOf(4), record.getSupplies().get(SHARK));
		assertEquals(Integer.valueOf(4), record.getSupplyDoses().get(PRAYER_4));
	}

	@Test
	public void restoreClearsSuppliesOfAnEarlierSession()
	{
		SessionSnapshot s = snapshotWithSupplies();
		SessionTracker other = new SessionTracker(() -> now);
		other.observe("Abyssal demons", DEMON_ID, 46, 150, false);
		other.play(ACCOUNT);
		other.addSupplies(tally(ARROW, 9), null, null);
		other.stopAndReset();
		assertEquals(SessionTracker.RestoreResult.RESTORED, other.restore(s, COUNTERS));
		assertEquals(0, other.getSupplies().get(ARROW));
		assertEquals(4, other.getSupplies().get(SHARK));
	}

	@Test
	public void version2SnapshotWithoutSuppliesRestores()
	{
		String v2 = "{\"schemaVersion\":2,\"sessionId\":\"s1\",\"accountHash\":555,\"monster\":\"Abyssal demons\","
			+ "\"creatureId\":42,\"initialAmount\":50,\"originalAmount\":150,\"kills\":4,\"activeMillis\":30000,"
			+ "\"startedAt\":1000,\"savedAt\":2000,\"remaining\":46,\"completionCounters\":[100,2,0],"
			+ "\"loot\":{\"526\":3}}";
		SessionSnapshot s = gson.fromJson(v2, SessionSnapshot.class);
		assertNull(s.getSupplies());
		assertNull(s.getSupplyDoses());
		assertTrue(s.toSummary().getSupplies().isEmpty());

		tracker.observe("Abyssal demons", DEMON_ID, 46, 150, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, tracker.restore(s, COUNTERS));
		assertTrue(tracker.getSupplies().isEmpty());
		assertEquals(3, tracker.getLoot().get(526));
	}

	// ---- Records on disk ----

	@Test
	public void olderRecordsWithoutSuppliesStillLoad() throws IOException
	{
		File dir = new File(temp.getRoot(), "slayer-ledger");
		assertTrue(dir.mkdirs());
		File file = new File(dir, ACCOUNT + ".json");
		String json = "{\"schemaVersion\":2,\"records\":["
			+ "{\"schemaVersion\":1,\"id\":\"v1\",\"monster\":\"Kurask\",\"initialAmount\":50,\"kills\":50,\"completed\":true},"
			+ "{\"schemaVersion\":2,\"id\":\"v2\",\"monster\":\"Kurask\",\"initialAmount\":50,\"kills\":50,\"completed\":true,"
			+ "\"loot\":{\"526\":10},\"unitGePrices\":{\"526\":100},\"unitHaPrices\":{\"526\":0},"
			+ "\"lootGeValue\":1000,\"lootHaValue\":0}]}";
		Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
		LedgerStore store = new LedgerStore(Filepath.Unchecked.getRooted(dir.toPath()), gson);

		startDemonTask();
		tracker.addSupplies(tally(PRAYER_4, 4), null, doses(PRAYER_4, 4));
		LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), false);
		record.captureValues(PRICES);
		store.append(ACCOUNT, record);

		List<LedgerRecord> loaded = store.load(ACCOUNT);
		assertEquals(3, loaded.size());
		for (int i = 0; i < 2; i++)
		{
			assertNull(loaded.get(i).getSupplies());
			assertNull(loaded.get(i).getSupplyDoses());
			assertNull(loaded.get(i).getSuppliesGeValue());
		}
		assertEquals(Long.valueOf(1_000), loaded.get(1).getLootGeValue());
		assertEquals(Long.valueOf(4), loaded.get(2).getSupplies().get(PRAYER_4));
		assertEquals(Integer.valueOf(4), loaded.get(2).getSupplyDoses().get(PRAYER_4));
		assertEquals(Long.valueOf(8_002), loaded.get(2).getSuppliesGeValue());
	}

	@Test
	public void activeFileWithSuppliesRoundTripsThroughStore() throws IOException
	{
		LedgerStore store = new LedgerStore(Filepath.Unchecked.getRooted(temp.getRoot().toPath().resolve("slayer-ledger")), gson);
		SessionSnapshot s = snapshotWithSupplies();
		store.writeActive(ACCOUNT, s);
		assertEquals(s, store.readActive(ACCOUNT));
	}

	// ---- ItemTally ----

	@Test
	public void tallySubtractStopsAtZero()
	{
		ItemTally t = tally(SHARK, 5);
		assertEquals(3, t.subtract(SHARK, 3));
		assertEquals(2, t.get(SHARK));
		assertEquals(2, t.subtract(SHARK, 10));
		assertTrue(t.isEmpty());
		assertEquals(0, t.subtract(SHARK, 1));
		assertEquals(0, t.subtract(ARROW, -1));
	}
}
