package com.shmayvan.slayerledger;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.client.util.Filepath;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The ledger and active-session JSON, pinned byte for byte. The expected strings below were produced by the
 * build before the session value objects (SessionLoot, SessionCounters, the Summary builder) were introduced, from the
 * same scenario, so a refactor of how a session is modelled can't change what is written to disk. Every field of
 * {@link LedgerRecord} and {@link SessionSnapshot} is set at least once.
 */
public class SessionJsonFormatTest
{
	private static final long ACCOUNT = 123456789L;
	private static final long START = 1_700_000_000_000L;
	private static final int BOSS_TASK = SessionTracker.creatureKey(SessionTracker.BOSS_TASK_TARGET, 5);
	private static final int REGION = 12850;
	private static final int WHIP = 4151;
	private static final int ASHES = 592;
	private static final int COINS = 995;
	/** Tradeable without a GE price, so the record counts it as unpriced. */
	private static final int UNPRICED_LOOT = 11286;
	private static final int PRAYER_POTION = 2434;
	private static final int SHARK = 385;
	private static final int UNPRICED_SUPPLY = 12625;
	/** Task-completion counters when the snapshot was taken. */
	private static final int[] COUNTERS = {3, 0, 1};

	/** A completed record from the scenario, values captured, as {@link LedgerStore} writes it inside the file. */
	private static final String RECORD_JSON =
		"{\"schemaVersion\":2,\"id\":\"session-1\",\"monster\":\"Abyssal Sire\",\"creatureId\":-7,"
		+ "\"initialAmount\":6,\"originalAmount\":6,\"kills\":6,\"activeMillis\":91000,"
		+ "\"startedAt\":1700000000000,\"endedAt\":1700000091000,\"completed\":true,\"partial\":false,"
		+ "\"loot\":{\"592\":3,\"995\":2500,\"4151\":1,\"11286\":1},\"supplies\":{\"385\":5,\"2434\":7,"
		+ "\"12625\":1},\"supplyDoses\":{\"2434\":4},\"unitGePrices\":{\"385\":800,\"592\":120,\"995\":1,"
		+ "\"2434\":9000,\"4151\":1500000},\"unitHaPrices\":{\"385\":150,\"592\":0,\"995\":1,\"2434\":0,"
		+ "\"4151\":72000,\"11286\":0,\"12625\":0},\"lootGeValue\":1502860,\"lootHaValue\":74500,"
		+ "\"suppliesGeValue\":19750,\"suppliesHaValue\":750,\"lootUnpricedItems\":1,"
		+ "\"suppliesUnpricedItems\":1,\"superiorsSpawned\":1,\"bankTrips\":2,\"deaths\":1,"
		+ "\"note\":\"Barrage\",\"slayerMaster\":8,\"slayerArea\":3,\"taskLocation\":\"Catacombs\","
		+ "\"pointsEarned\":15,\"taskStreak\":42,\"notableDrops\":[{\"itemId\":4151,"
		+ "\"name\":\"Abyssal whip\",\"quantity\":1,\"geValue\":1500000,\"haValue\":72000,"
		+ "\"time\":1700000060000},{\"quantity\":1,\"time\":1700000060001,\"pet\":\"RECEIVED\"}],"
		+ "\"bossKillCounts\":[{\"boss\":\"Abyssal Sire\",\"firstKc\":10,\"lastKc\":11,\"kills\":2}],"
		+ "\"xpGained\":{\"HITPOINTS\":700,\"SLAYER\":2500},\"locationRegion\":12850,"
		+ "\"location\":\"Catacombs of Kourend\"}";
	/** The scenario's snapshot taken mid-session, as the active-session file. */
	private static final String SNAPSHOT_JSON =
		"{\"schemaVersion\":2,\"sessionId\":\"session-1\",\"accountHash\":123456789,"
		+ "\"monster\":\"Abyssal Sire\",\"creatureId\":-7,\"initialAmount\":6,\"originalAmount\":6,"
		+ "\"kills\":4,\"activeMillis\":61000,\"startedAt\":1700000000000,\"savedAt\":1700000061005,"
		+ "\"remaining\":2,\"completionCounters\":[3,0,1],\"loot\":{\"592\":3,\"995\":2500,\"4151\":1,"
		+ "\"11286\":1},\"supplies\":{\"385\":5,\"2434\":7,\"12625\":1},\"supplyDoses\":{\"2434\":4},"
		+ "\"superiorsSpawned\":1,\"bankTrips\":2,\"deaths\":1,\"note\":\"Barrage\",\"slayerMaster\":8,"
		+ "\"slayerArea\":3,\"taskLocation\":\"Catacombs\",\"notableDrops\":[{\"itemId\":4151,"
		+ "\"name\":\"Abyssal whip\",\"quantity\":1,\"geValue\":1500000,\"haValue\":72000,"
		+ "\"time\":1700000060000},{\"quantity\":1,\"time\":1700000060001,\"pet\":\"RECEIVED\"}],"
		+ "\"bossKillCounts\":[{\"boss\":\"Abyssal Sire\",\"firstKc\":10,\"lastKc\":11,\"kills\":2}],"
		+ "\"xpGained\":{\"HITPOINTS\":700,\"SLAYER\":2500},\"locationKills\":[{\"region\":12850,"
		+ "\"kills\":2},{\"region\":12851,\"kills\":2}]}";
	/** The snapshot above saved as a partial record (it could not be restored). */
	private static final String PARTIAL_JSON =
		"{\"schemaVersion\":2,\"id\":\"session-1\",\"monster\":\"Abyssal Sire\",\"creatureId\":-7,"
		+ "\"initialAmount\":6,\"originalAmount\":6,\"kills\":4,\"activeMillis\":61000,"
		+ "\"startedAt\":1700000000000,\"endedAt\":1700000061005,\"completed\":false,\"partial\":true,"
		+ "\"loot\":{\"592\":3,\"995\":2500,\"4151\":1,\"11286\":1},\"supplies\":{\"385\":5,\"2434\":7,"
		+ "\"12625\":1},\"supplyDoses\":{\"2434\":4},\"superiorsSpawned\":1,\"bankTrips\":2,\"deaths\":1,"
		+ "\"note\":\"Barrage\",\"slayerMaster\":8,\"slayerArea\":3,\"taskLocation\":\"Catacombs\","
		+ "\"notableDrops\":[{\"itemId\":4151,\"name\":\"Abyssal whip\",\"quantity\":1,\"geValue\":1500000,"
		+ "\"haValue\":72000,\"time\":1700000060000},{\"quantity\":1,\"time\":1700000060001,"
		+ "\"pet\":\"RECEIVED\"}],\"bossKillCounts\":[{\"boss\":\"Abyssal Sire\",\"firstKc\":10,"
		+ "\"lastKc\":11,\"kills\":2}],\"xpGained\":{\"HITPOINTS\":700,\"SLAYER\":2500},"
		+ "\"locationRegion\":12850,\"location\":\"Catacombs\"}";

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final Gson gson = new Gson();
	private final AtomicLong now = new AtomicLong(START);
	private File dir;
	private LedgerStore store;

	@Before
	public void setUp()
	{
		dir = new File(temp.getRoot(), "slayer-ledger");
		store = new LedgerStore(Filepath.Unchecked.getRooted(dir.toPath()), gson);
	}

	@Test
	public void completedRecordIsWrittenAsBefore()
	{
		assertEquals(RECORD_JSON, gson.toJson(completedRecord()));
	}

	@Test
	public void snapshotIsWrittenAsBefore()
	{
		assertEquals(SNAPSHOT_JSON, gson.toJson(snapshot()));
	}

	@Test
	public void snapshotSavedAsPartialIsWrittenAsBefore()
	{
		LedgerRecord partial = LedgerRecord.fromSummary(snapshot().toSummary(), true);
		assertEquals(PARTIAL_JSON, gson.toJson(partial));
	}

	@Test
	public void restoredSnapshotIsWrittenBackUnchanged()
	{
		SessionSnapshot saved = gson.fromJson(SNAPSHOT_JSON, SessionSnapshot.class);
		SessionTracker tracker = new SessionTracker(now::get);
		tracker.observe("Abyssal Sire", BOSS_TASK, 2, 6, TaskContext.assignment(8, 3, "Catacombs"), REGION, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, tracker.restore(saved, COUNTERS.clone()));

		SessionSnapshot again = SessionSnapshot.fromSummary(tracker.summary(), COUNTERS, saved.getSavedAt());
		// Restoring starts a new active stretch; nothing else of the session changes.
		assertEquals(SNAPSHOT_JSON, gson.toJson(again));
	}

	@Test
	public void ledgerFileIsRewrittenByteForByte() throws IOException
	{
		String fixture = "{\"schemaVersion\":2,\"records\":[" + RECORD_JSON + "," + PARTIAL_JSON + "]}";
		assertEquals(true, dir.mkdirs());
		File file = new File(dir, ACCOUNT + ".json");
		Files.write(file.toPath(), fixture.getBytes(StandardCharsets.UTF_8));

		List<LedgerRecord> records = store.load(ACCOUNT);
		assertEquals(2, records.size());
		store.write(ACCOUNT, records);

		assertEquals(fixture, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
	}

	@Test
	public void activeFileIsRewrittenByteForByte() throws IOException
	{
		assertEquals(true, dir.mkdirs());
		File file = new File(dir, ACCOUNT + ".active.json");
		Files.write(file.toPath(), SNAPSHOT_JSON.getBytes(StandardCharsets.UTF_8));

		SessionSnapshot read = store.readActive(ACCOUNT);
		assertNotNull(read);
		store.writeActive(ACCOUNT, read);

		assertEquals(SNAPSHOT_JSON, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
	}

	/** The scenario's snapshot, taken while the session is running, with a fixed id. */
	private SessionSnapshot snapshot()
	{
		SessionTracker tracker = runningSession();
		SessionSnapshot s = SessionSnapshot.fromSummary(tracker.summary(), COUNTERS, now.get() + 5);
		s.setSessionId("session-1");
		return s;
	}

	/** The scenario's session completed, with completion rewards and captured values, with a fixed id. */
	private LedgerRecord completedRecord()
	{
		SessionTracker tracker = runningSession();
		now.addAndGet(30_000);
		tracker.observe("Abyssal Sire", BOSS_TASK, 0, 6, TaskContext.assignment(8, 3, "Catacombs"), REGION, true);
		assertEquals(true, tracker.isCompleted());
		tracker.setCompletionRewards(tracker.getSessionId(), 15, 42);
		tracker.stop();
		LedgerRecord record = LedgerRecord.fromSummary(tracker.summary(), false);
		record.setId("session-1");
		record.captureValues(new ItemValuation.PriceLookup()
		{
			@Override
			public int canonicalize(int itemId)
			{
				return itemId;
			}

			@Override
			public long gePrice(int canonicalId)
			{
				switch (canonicalId)
				{
					case WHIP:
						return 1_500_000;
					case ASHES:
						return 120;
					case COINS:
						return 1;
					case PRAYER_POTION:
						return 9_000;
					case SHARK:
						return 800;
					default:
						return 0;
				}
			}

			@Override
			public boolean isTradeable(int canonicalId)
			{
				return true;
			}

			@Override
			public long haPrice(int canonicalId)
			{
				return canonicalId == WHIP ? 72_000 : canonicalId == SHARK ? 150 : 0;
			}
		});
		record.setLocation("Catacombs of Kourend");
		return record;
	}

	/** A running boss task session with every kind of session data: loot, supplies, counters, XP, region, note. */
	private SessionTracker runningSession()
	{
		now.set(START);
		SessionTracker tracker = new SessionTracker(now::get);
		final TaskContext assignment = TaskContext.assignment(8, 3, "Catacombs");
		tracker.observe("Abyssal Sire", BOSS_TASK, 6, 6, assignment, REGION, false);
		tracker.observeXp(xp(1_000, 500));
		assertEquals(true, tracker.play(ACCOUNT));
		now.addAndGet(60_000);
		tracker.observe("Abyssal Sire", BOSS_TASK, 4, 6, assignment, REGION, false);
		tracker.observe("Abyssal Sire", BOSS_TASK, 2, 6, assignment, REGION + 1, false);
		tracker.observeXp(xp(3_500, 1_200));

		ItemTally loot = new ItemTally();
		loot.add(WHIP, 1);
		loot.add(ASHES, 3);
		loot.add(COINS, 2_500);
		loot.add(UNPRICED_LOOT, 1);
		tracker.addLoot(loot, Collections.singletonList(
			NotableDrop.item(WHIP, "Abyssal whip", 1, 1_500_000L, 72_000L, now.get())));
		tracker.addPet(NotableDrop.pet(null, now.get() + 1));
		tracker.addBossKillCount("Abyssal Sire", 10);
		tracker.addBossKillCount("Abyssal Sire", 11);

		ItemTally used = new ItemTally();
		used.add(PRAYER_POTION, 7);
		used.add(SHARK, 5);
		used.add(UNPRICED_SUPPLY, 1);
		tracker.addSupplies(used, null, Collections.singletonMap(PRAYER_POTION, 4));

		tracker.addSuperiorSpawn();
		tracker.addBankTrip();
		tracker.addBankTrip();
		tracker.addDeath();
		tracker.setNote(tracker.getSessionId(), "Barrage");
		now.addAndGet(1_000);
		return tracker;
	}

	private static Map<String, Integer> xp(int slayer, int hitpoints)
	{
		Map<String, Integer> reading = new TreeMap<>();
		reading.put("SLAYER", slayer);
		reading.put("HITPOINTS", hitpoints);
		return reading;
	}
}
