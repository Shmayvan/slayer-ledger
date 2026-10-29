package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** {@link SessionCounters}, {@link SessionLoot} and the {@link SessionTracker.Summary} builder. */
public class SessionValueObjectsTest
{
	@Test
	public void emptySummaryBuilderIsANewSession()
	{
		SessionTracker.Summary s = SessionTracker.Summary.builder().build();
		assertNull(s.getSessionId());
		assertEquals(SessionTracker.NO_ACCOUNT, s.getAccountHash());
		assertEquals(SessionTracker.NO_CREATURE, s.getCreatureId());
		assertEquals(SessionTracker.UNKNOWN_AMOUNT, s.getOriginalAmount());
		assertSame(TaskContext.NONE, s.getContext());
		assertEquals(Integer.valueOf(0), s.getSuperiorsSpawned());
		assertEquals(Integer.valueOf(0), s.getBankTrips());
		assertEquals(Integer.valueOf(0), s.getDeaths());
		assertTrue(s.getLoot().isEmpty());
		assertTrue(s.getNotableDrops().isEmpty());
		assertEquals(Collections.emptyMap(), s.getXpGained());
		assertTrue(s.getLocationKills().isEmpty());
	}

	@Test
	public void nullsInTheSummaryBuilderAreEmptyOrUnknown()
	{
		SessionTracker.Summary s = SessionTracker.Summary.builder()
			.sessionLoot(null)
			.counters(null)
			.context(null)
			.xpGained(null)
			.locationKills(null)
			.build();
		assertSame(SessionLoot.EMPTY, s.getSessionLoot());
		assertSame(SessionCounters.ZERO, s.getCounters());
		assertSame(TaskContext.NONE, s.getContext());
		assertNull("unknown XP stays unknown", s.getXpGained());
		assertTrue(s.getLocationKills().isEmpty());
	}

	@Test
	public void copiesChangeOnlyWhatTheyNameAndKeepTheRest()
	{
		ItemTally loot = new ItemTally();
		loot.add(592, 3);
		SessionTracker.Summary s = SessionTracker.Summary.builder()
			.sessionId("s1")
			.kills(4)
			.counters(SessionCounters.builder().deaths(2).build())
			.sessionLoot(SessionLoot.builder().loot(loot).build())
			.note("Barrage")
			.build();

		SessionTracker.Summary noted = s.withNote("Cannon").withBossKillCount("Abyssal Sire", 10);
		assertEquals("Cannon", noted.getNote());
		assertEquals(1, noted.getBossKillCounts().size());
		assertEquals("s1", noted.getSessionId());
		assertEquals(4, noted.getKills());
		assertEquals(Integer.valueOf(2), noted.getDeaths());
		assertEquals(3, noted.getLoot().get(592));
		assertEquals("Barrage", s.getNote());

		ItemTally extra = new ItemTally();
		extra.add(592, 2);
		assertEquals(5, s.withAddedLoot(extra, null).getLoot().get(592));
		assertEquals(3, s.getLoot().get(592));
	}

	@Test
	public void sessionLootCopiesWhatItIsGiven()
	{
		ItemTally loot = new ItemTally();
		loot.add(592, 3);
		Map<Integer, Integer> doses = new HashMap<>();
		doses.put(2434, 4);
		doses.put(385, 0);
		List<BossKillCount> counts = new ArrayList<>(BossKillCounts.add(null, "Abyssal Sire", 10));
		SessionLoot built = SessionLoot.builder().loot(loot).supplyDoses(doses).bossKillCounts(counts).build();

		loot.add(592, 10);
		doses.put(2434, 3);
		counts.clear();
		assertEquals(3, built.getLoot().get(592));
		assertEquals(Collections.singletonMap(2434, 4), built.getSupplyDoses());
		assertEquals(1, built.getBossKillCounts().size());
		built.getLoot().add(592, 1);
		assertEquals("getters hand out copies", 3, built.getLoot().get(592));
	}

	@Test
	public void restoredCountersCountUnknownAndNegativeAsZero()
	{
		SessionCounters stored = SessionCounters.builder().superiorsSpawned(null).bankTrips(-3).deaths(2).build();
		SessionCounters restored = stored.withUnknownAsZero();
		assertEquals(Integer.valueOf(0), restored.getSuperiorsSpawned());
		assertEquals(Integer.valueOf(0), restored.getBankTrips());
		assertEquals(Integer.valueOf(2), restored.getDeaths());
		assertNull("the stored counters are unchanged", stored.getSuperiorsSpawned());
	}

	@Test
	public void countersAreCopiedToBothPersistedShapesAndBack()
	{
		SessionCounters counters = SessionCounters.builder().superiorsSpawned(1).bankTrips(null).deaths(3).build();
		SessionSnapshot snapshot = new SessionSnapshot();
		counters.copyTo(snapshot);
		LedgerRecord record = new LedgerRecord();
		counters.copyTo(record);

		assertEquals(counters, SessionCounters.from(snapshot));
		assertEquals(Integer.valueOf(1), record.getSuperiorsSpawned());
		assertNull(record.getBankTrips());
		assertEquals(Integer.valueOf(3), record.getDeaths());
	}
}
