package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.SessionTracker.ObserveResult;
import static com.shmayvan.slayerledger.SessionTracker.State;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Before;
import org.junit.Test;

/**
 * Supplies, XP and loot outside segments on the tracker alone (V2 plan 4.1): what is collected and where, the
 * buffer counted at a (re)start and dropped after it, Away's take-back, a held completion backdated with the buffer,
 * and the Jad and Zuk caves. {@code HarnessUsageBufferTest} and {@code HarnessTaskCaveTest} cover the same rules through
 * the plugin's events.
 */
public class SessionBufferTest
{
	private static final int DEMON_ID = 42;
	private static final long ACCOUNT = 555L;
	private static final long MINUTE = 60_000L;
	private static final int SHARK = 385;
	private static final int DART = 811;
	private static final int WHIP = 4151;
	private static final int BONES = 526;

	private long now;
	private SessionTracker tracker;
	/** The last XP reading passed, so each gain is a new reading. */
	private int attackXp = 1_000_000;

	@Before
	public void setUp()
	{
		now = 100 * MINUTE;
		tracker = new SessionTracker(() -> now);
		tracker.setAccount(ACCOUNT);
		tracker.observeXp(reading());
	}

	private Map<String, Integer> reading()
	{
		final Map<String, Integer> reading = new TreeMap<>();
		reading.put("ATTACK", attackXp);
		return reading;
	}

	private void gainXp(int amount)
	{
		attackXp += amount;
		tracker.observeXp(reading());
	}

	private long xp()
	{
		return tracker.getXpGained().getOrDefault("ATTACK", 0L);
	}

	private static ItemTally tally(int id, long quantity)
	{
		final ItemTally tally = new ItemTally();
		tally.add(id, quantity);
		return tally;
	}

	private void use(int id, long quantity)
	{
		tracker.addSupplies(tally(id, quantity), null, null);
	}

	private ObserveResult demons(int remaining)
	{
		return demons(remaining, KillPairing.NONE);
	}

	private ObserveResult demons(int remaining, KillPairing pairing)
	{
		return tracker.observe("Abyssal demons", DEMON_ID, remaining, 50, TaskContext.NONE, LocationTally.NO_REGION,
			false, pairing);
	}

	/** NPCs whose earliest engagement started at {@code start}, named Abyssal demon. */
	private static KillPairing fightFrom(long start)
	{
		return KillPairing.of(start, Collections.singletonList("Abyssal demon"));
	}

	// ---- What is collected ----

	@Test
	public void suppliesXpAndLootAreCollectedOnlyWhileATaskIsAssignedAndNotEnded()
	{
		tracker.observe(null, SessionTracker.NO_CREATURE, 0, false);
		assertFalse("no task", tracker.isCollecting());
		use(SHARK, 1);
		gainXp(10);
		tracker.addLoot(tally(BONES, 1));
		assertEquals(0, tracker.bufferSize());

		demons(50);
		assertTrue("a task waiting for its first kill", tracker.isCollecting());
		use(SHARK, 1);
		assertEquals(1, tracker.bufferSize());

		tracker.endTask();
		assertFalse("Ended", tracker.isCollecting());
		tracker.trackAgain();
		assertTrue(tracker.isCollecting());

		tracker.clearCurrentTask();
		assertFalse("a logout forgets the task", tracker.isCollecting());
		assertEquals("and the buffer", 0, tracker.bufferSize());
	}

	@Test
	public void endTaskWithNoSessionDropsTheBufferAndClampsTheNextResumePoint()
	{
		demons(50);
		final long firstHit = now;
		now += 30_000L;
		// Eaten while waiting for the first kill, then End task at the same clock time: not before the clamp, so only
		// dropping the buffer keeps it out.
		use(SHARK, 1);
		gainXp(10);
		final long ended = now;
		assertNull("no session to save", tracker.endTask());
		assertEquals("what was held back is dropped", 0, tracker.bufferSize());
		assertEquals("the clamp outlives a client restart", Long.valueOf(ended),
			tracker.endedMarker(null, now).getPreviousSessionEnd());

		now += 30_000L;
		assertTrue(tracker.trackAgain());
		now += 30_000L;
		// A kill a minute after the shark, whose fight began before End task.
		demons(49, fightFrom(firstHit));
		assertEquals(State.RUNNING, tracker.getState());
		assertTrue("the shark eaten before End task", tracker.getSupplies().isEmpty());
		assertEquals("nor the XP", 0, xp());
		assertEquals("timed from End task, not the first hit", now - ended, tracker.getActiveMillis());
	}

	@Test
	public void aRestartCountsTheBufferAndItsFinalPairingDropsTheRest()
	{
		demons(50);
		// Before any warm-up below.
		use(SHARK, 1);
		now += 4 * MINUTE;
		use(SHARK, 1);
		gainXp(10);
		now += 3 * MINUTE;
		gainXp(20);
		use(SHARK, 1);
		now += MINUTE;
		// A cannon kill: XP from the kill, supplies from 3 minutes before it.
		demons(49);
		assertEquals(State.RUNNING, tracker.getState());
		assertEquals(1, tracker.getSupplies().get(SHARK));
		assertEquals(0, xp());
		assertEquals("the older part waits for the final pairing", 3, tracker.bufferSize());

		// The next tick's pairing backdates it 4 minutes: the XP from then and the shark 3 minutes before then count.
		tracker.pairKill(tracker.getLastKillAt(), fightFrom(now - 4 * MINUTE));
		assertEquals(4 * MINUTE, tracker.getActiveMillis());
		assertEquals(2, tracker.getSupplies().get(SHARK));
		assertEquals(30, xp());
		assertEquals("the rest, the first shark, is dropped", 0, tracker.bufferSize());
	}

	@Test
	public void aHeldCompletionBackdatedByItsFinalPairingTakesTheBufferedFight()
	{
		demons(2);
		demons(1);
		tracker.pause();
		now += 10 * MINUTE;
		final long fightStart = now;
		use(SHARK, 1);
		gainXp(50);
		now += MINUTE;
		// The final kill restarts and completes; nothing paired yet, so it opens at the kill.
		assertEquals(ObserveResult.COMPLETED, tracker.observe(null, SessionTracker.NO_CREATURE, 0,
			SessionTracker.UNKNOWN_AMOUNT, TaskContext.NONE, LocationTally.NO_REGION, true, KillPairing.NONE));
		assertTrue(tracker.holdCompletion());
		assertEquals(0, xp());

		// The NPC died on the tick after: the held completion is backdated to its fight, with the fight's use.
		tracker.pairKill(tracker.getLastKillAt(), fightFrom(fightStart));
		final SessionTracker.Summary held = tracker.takePendingCompletion();
		assertNotNull(held);
		assertEquals(MINUTE, held.getActiveMillis());
		assertEquals(1, held.getSupplies().get(SHARK));
		assertEquals(Long.valueOf(50), held.getXpGained().get("ATTACK"));
		assertEquals(0, tracker.bufferSize());
	}

	@Test
	public void theTestSeamCountsNothingFromBeforeIt()
	{
		demons(50);
		use(SHARK, 1);
		gainXp(10);
		assertTrue(tracker.startNow(ACCOUNT));
		assertTrue(tracker.getSupplies().isEmpty());
		assertEquals(0, xp());
		assertEquals(0, tracker.bufferSize());
	}

	// ---- Away ----

	@Test
	public void awayTakesBackWhatCameAfterTheLastActivityExactly()
	{
		demons(50);
		demons(49);
		// Before the last activity: 10 darts used.
		use(DART, 10);
		now += MINUTE;
		demons(48);
		// After it: 3 used and 15 picked up, XP and an NPC's loot. The return can take off only the 13 used (never
		// below zero), so the take-back puts those 13 back, not the 15 picked up (which would leave 12).
		now += MINUTE;
		tracker.addSupplies(tally(DART, 3), tally(DART, 15), null);
		assertEquals(0, tracker.getSupplies().get(DART));
		gainXp(40);
		tracker.addLoot(tally(BONES, 2), null, "Goblin");
		now += 10 * MINUTE;
		assertTrue(tracker.checkAway());

		assertEquals("as before the last activity", 10, tracker.getSupplies().get(DART));
		assertEquals(0, xp());
		assertEquals(0, tracker.getLoot().get(BONES));
		assertEquals("moved to the buffer", 1, tracker.bufferSize());
	}

	@Test
	public void aTaskKillsLootIsNeverTakenBackAndCountsWhileStoppedOnlyJustAfterTheKill()
	{
		demons(50);
		demons(49, fightFrom(now));
		now += SessionTracker.TASK_NPC_LOOT_MILLIS;
		final NotableDrop whip = NotableDrop.pet(NotableDrop.Pet.RECEIVED, now);
		tracker.addLoot(tally(WHIP, 1), Collections.singletonList(whip), "Abyssal demon");
		now += 10 * MINUTE;
		assertTrue(tracker.checkAway());
		assertEquals("the kill's loot stays", 1, tracker.getLoot().get(WHIP));
		assertEquals(Collections.singletonList(whip), tracker.getNotableDrops());

		// long after the last task kill, the same names' loot is like any other.
		assertFalse("buffered", tracker.addLoot(tally(WHIP, 1), null, "abyssal DEMON"));
		assertEquals(1, tracker.getLoot().get(WHIP));
		assertFalse("other loot is buffered", tracker.addLoot(tally(BONES, 1), null, "Goblin"));
		assertEquals(0, tracker.getLoot().get(BONES));

		// Paused just after a kill: its loot still counts.
		now += MINUTE;
		demons(48, fightFrom(now));
		assertTrue(tracker.pause());
		now += SessionTracker.TASK_NPC_LOOT_MILLIS;
		assertTrue("a paired task kill's late loot", tracker.addLoot(tally(WHIP, 1), null, "Abyssal demon"));
		assertEquals(2, tracker.getLoot().get(WHIP));
		now += 1;
		assertFalse("too late", tracker.addLoot(tally(WHIP, 1), null, "Abyssal demon"));
		assertEquals(2, tracker.getLoot().get(WHIP));
	}

	@Test
	public void awayTakesBackTheBankTripsSinceTheLastActivityButKeepsDeathsAndSuperiors()
	{
		demons(50);
		demons(49, fightFrom(now));
		// On the kill's own tick: not after the last activity.
		assertTrue(tracker.addBankTrip());
		assertTrue(tracker.addDeath());
		now += MINUTE;
		// After it: the player went off to do something else, or died a few ticks after their last hit.
		assertTrue(tracker.addBankTrip());
		assertTrue(tracker.addDeath());
		assertTrue(tracker.addSuperiorSpawn());
		assertEquals(2, tracker.getBankTrips());
		assertEquals(2, tracker.getDeaths());

		now += 10 * MINUTE;
		assertTrue(tracker.checkAway());
		assertEquals(1, tracker.getBankTrips());
		assertEquals("a death is never taken back", 2, tracker.getDeaths());
		assertEquals("only task kills spawn superiors", 1, tracker.getSuperiorsSpawned());

		// A restart's warm-up never counts them again.
		now += MINUTE;
		demons(48, fightFrom(now - 30_000));
		assertEquals(1, tracker.getBankTrips());
		assertEquals(2, tracker.getDeaths());
	}

	@Test
	public void araxxorsLootCountsForFiveMinutesAfterTheKill()
	{
		// Araxxor drops its loot only when the player harvests its corpse, which can be minutes after the kill.
		araxytes(50);
		araxytes(49, KillPairing.of(now, Collections.singletonList("Araxxor")));
		// Harvested 3 minutes after the kill, nothing since: never taken back by Away.
		now += 3 * MINUTE;
		assertTrue(tracker.addLoot(tally(WHIP, 1), null, "Araxxor"));
		now += 10 * MINUTE;
		assertTrue(tracker.checkAway());
		assertEquals("the harvest stays", 1, tracker.getLoot().get(WHIP));

		// The next kill, then a pause: a harvest 4 minutes later still counts, one after 5 minutes doesn't.
		araxytes(48, KillPairing.of(now, Arrays.asList("Araxxor", "Araxyte")));
		assertTrue(tracker.pause());
		now += 4 * MINUTE;
		assertTrue("harvested while paused: still the kill's loot", tracker.addLoot(tally(WHIP, 1), null, "Araxxor"));
		assertFalse("another task NPC's window is still 10 ticks", tracker.addLoot(tally(BONES, 1), null, "Araxyte"));
		assertEquals(2, tracker.getLoot().get(WHIP));
		now = now - 4 * MINUTE + SessionTracker.ARAXXOR_LOOT_MILLIS + 1;
		assertFalse("too late", tracker.addLoot(tally(WHIP, 1), null, "Araxxor"));
		assertEquals(2, tracker.getLoot().get(WHIP));
	}

	private ObserveResult araxytes(int remaining)
	{
		return araxytes(remaining, KillPairing.NONE);
	}

	private ObserveResult araxytes(int remaining, KillPairing pairing)
	{
		return tracker.observe("Araxytes", 105, remaining, 50, TaskContext.NONE, LocationTally.NO_REGION, false,
			pairing);
	}

	@Test
	public void aKnownTaskNpcsLootLongAfterTheLastKillIsTakenBackByAway()
	{
		demons(50);
		demons(49, fightFrom(now));
		// A Konar task monster killed outside the assigned area, say: no task kill, so no activity.
		now += MINUTE;
		tracker.addLoot(tally(WHIP, 1), null, "Abyssal demon");
		assertEquals(1, tracker.getLoot().get(WHIP));
		now += 10 * MINUTE;
		assertTrue(tracker.checkAway());
		assertEquals("taken back like any loot after the last activity", 0, tracker.getLoot().get(WHIP));
	}

	@Test
	public void aTakenBackNotableDropAndPotionLeaveNoTrace()
	{
		demons(50);
		demons(49);
		now += MINUTE;
		final NotableDrop drop = NotableDrop.pet(NotableDrop.Pet.DUPLICATE, now);
		tracker.addLoot(tally(WHIP, 1), Collections.singletonList(drop), null);
		tracker.addSupplies(tally(2434, 1), null, Collections.singletonMap(2434, 4));
		now += 10 * MINUTE;
		assertTrue(tracker.checkAway());
		assertTrue(tracker.getNotableDrops().isEmpty());
		assertTrue(tracker.getSupplies().isEmpty());
		assertTrue("no dose entry for a potion no longer used", tracker.getSupplyDoses().isEmpty());
	}

	// ---- Jad and Zuk ----

	@Test
	public void insideTheTasksCaveIsRunningAndNeverAway()
	{
		tracker.observe("TzTok-Jad", 93, 1, 1, TaskContext.NONE, false);
		use(SHARK, 1);
		now += MINUTE;
		assertTrue(tracker.setInTaskCave(true, true));
		assertEquals(State.RUNNING, tracker.getState());
		assertEquals("the warm-up before entering", 1, tracker.getSupplies().get(SHARK));
		assertFalse("already running", tracker.setInTaskCave(true, true));

		now += 30 * MINUTE;
		assertFalse("never Away inside", tracker.checkAway());
		assertTrue(tracker.setInTaskCave(false, true));
		assertEquals(State.AWAY, tracker.getState());
		assertEquals("leaving keeps what came before", 30 * MINUTE, tracker.getActiveMillis());

		assertTrue(tracker.setInTaskCave(true, true));
		assertEquals(State.RUNNING, tracker.getState());
	}

	@Test
	public void aNewSessionInsideWaitsWhileAStartIsBlocked()
	{
		tracker.observe("TzKal-Zuk", 94, 1, 1, TaskContext.NONE, false);
		assertFalse(tracker.setInTaskCave(true, false));
		assertEquals(State.IDLE, tracker.getState());
		assertTrue("still inside once the block lifts", tracker.setInTaskCave(true, true));
		assertEquals(State.RUNNING, tracker.getState());
	}
}
