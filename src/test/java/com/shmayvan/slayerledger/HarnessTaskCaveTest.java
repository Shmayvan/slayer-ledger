package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.Item;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Jad and Zuk tasks through the plugin's events (V2 plan 4.1): inside the Fight Caves or the Inferno with that
 * task assigned is Running. Entering opens a segment, leaving (also after a death) closes it and keeps what came
 * before, a logout inside closes it and a login inside opens one, and Away never starts inside. Supplies count inside
 * (with the warm-up before an entry), not outside.
 */
public class HarnessTaskCaveTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int JAD_TASK = 93;
	private static final int ZUK_TASK = 94;
	/** The TzHaar city, outside the Fight Caves. */
	private static final int TZHAAR_CITY = 9808;
	private static final long MINUTE = 60_000L;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	private final AtomicLong now = new AtomicLong(1_000_000_000L);
	private int sharks = 20;

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.SHARK, "Shark", 1_000, 300, "Eat");
		harness.startUp();
		harness.setTrackerClock(now::get);
		harness.setInventory(sharkStacks());
		harness.setPlayerRegion(TZHAAR_CITY);
		harness.setTask(JAD_TASK, TaskCaves.JAD_TASK, 1, 1);
		harness.loginAndSettle(ACCOUNT);
		// The task is read; the supplies diff takes its first baseline.
		harness.tick();
	}

	private Item[] sharkStacks()
	{
		final Item[] items = new Item[sharks];
		for (int i = 0; i < sharks; i++)
		{
			items[i] = PluginHarness.item(ItemID.SHARK, 1);
		}
		return items;
	}

	/** Eats a shark now: the supplies diff sees it on the next tick. */
	private void eat()
	{
		sharks--;
		harness.setInventory(sharkStacks());
		harness.tick();
	}

	private long sharksUsed()
	{
		return harness.tracker().getSupplies().get(ItemID.SHARK);
	}

	/** The player moves to {@code region}; the plugin sees it on the next tick. */
	private void moveTo(int region)
	{
		harness.setPlayerRegion(region);
		harness.tick();
	}

	private long active()
	{
		return harness.tracker().getActiveMillis();
	}

	@Test
	public void insideTheFightCavesOnAJadTaskIsRunning()
	{
		assertEquals(SessionTracker.Status.WAITING_FOR_FIRST_KILL, harness.status());
		// Ten minutes before entering: not counted. Two minutes before: a pre-pot, in the warm-up.
		eat();
		now.addAndGet(8 * MINUTE);
		eat();
		now.addAndGet(2 * MINUTE);

		moveTo(TaskCaves.FIGHT_CAVES_REGION);
		assertEquals("entering starts the session", SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals(1, sharksUsed());
		now.addAndGet(MINUTE);
		eat();
		assertEquals(2, sharksUsed());

		// A long wave with no task kill: Away never starts inside.
		now.addAndGet(15 * MINUTE);
		harness.tick();
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals(16 * MINUTE, active());

		// Dying and leaving closes the segment and keeps what came before.
		harness.localPlayerDies();
		moveTo(TZHAAR_CITY);
		assertEquals(SessionTracker.State.AWAY, harness.sessionState());
		assertEquals(16 * MINUTE, active());
		assertEquals("the death inside counts", 1, harness.tracker().getDeaths());
		// Past the ticks after a death, when the items moving to the gravestone are not supplies.
		harness.ticks(SupplyExclusions.DEATH_TICKS);
		// Outside, more than 3 minutes before entering again: not counted.
		now.addAndGet(MINUTE);
		eat();
		now.addAndGet(9 * MINUTE);
		harness.tick();
		assertEquals(16 * MINUTE, active());

		// Entering again restarts it.
		moveTo(TaskCaves.FIGHT_CAVES_REGION);
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals(2, sharksUsed());
		now.addAndGet(2 * MINUTE);
		eat();
		assertEquals(3, sharksUsed());

		// Logging out inside closes it; logging in inside opens one at once.
		harness.logout();
		assertEquals(SessionTracker.State.LOGGED_OUT, harness.sessionState());
		assertEquals(18 * MINUTE, active());
		now.addAndGet(30 * MINUTE);
		harness.loginAndSettle(ACCOUNT);
		assertEquals("a login inside is a restart", SessionTracker.State.RUNNING, harness.sessionState());
		assertFalse("so there is no waiting line", harness.status() == SessionTracker.Status.WAITING_FOR_NEXT_KILL);
		harness.tick();
		now.addAndGet(MINUTE);
		eat();
		assertEquals(19 * MINUTE, active());
		assertEquals(4, sharksUsed());

		// Leaving closes it again; outside, nothing counts.
		moveTo(TZHAAR_CITY);
		assertEquals(SessionTracker.State.AWAY, harness.sessionState());
		now.addAndGet(10 * MINUTE);
		eat();
		assertEquals(19 * MINUTE, active());
		assertEquals(4, sharksUsed());

		// Jad, killed inside, completes the task.
		now.addAndGet(10 * MINUTE);
		moveTo(TaskCaves.FIGHT_CAVES_REGION);
		now.addAndGet(5 * MINUTE);
		harness.completeTask();
		harness.tick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals(1, record.getKills());
		assertEquals(24 * MINUTE, record.getActiveMillis());
		assertEquals(Long.valueOf(4), record.getSupplies().get(ItemID.SHARK));
		assertEquals(Integer.valueOf(1), record.getDeaths());
	}

	@Test
	public void aSessionPausedInsideStaysPausedUntilResumedOrEnteredAgain()
	{
		moveTo(TaskCaves.FIGHT_CAVES_REGION);
		now.addAndGet(MINUTE);
		harness.pause();
		now.addAndGet(5 * MINUTE);
		harness.ticks(3);
		assertEquals(SessionTracker.State.PAUSED, harness.sessionState());
		assertTrue(harness.resume());
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());

		harness.pause();
		moveTo(TZHAAR_CITY);
		assertEquals(SessionTracker.State.PAUSED, harness.sessionState());
		moveTo(TaskCaves.FIGHT_CAVES_REGION);
		assertEquals("entering again restarts it", SessionTracker.State.RUNNING, harness.sessionState());
	}

	@Test
	public void anEndedJadTaskIsNotTrackedInside()
	{
		harness.endTask();
		moveTo(TaskCaves.FIGHT_CAVES_REGION);
		assertEquals(SessionTracker.Status.ENDED, harness.status());
		harness.trackAgain();
		harness.tick();
		assertEquals("Track again inside starts it", SessionTracker.State.RUNNING, harness.sessionState());
	}

	@Test
	public void onlyTheTasksOwnCaveIsRunning()
	{
		// A Jad task in the Inferno: not its cave.
		moveTo(TaskCaves.INFERNO_REGION);
		assertEquals(SessionTracker.Status.WAITING_FOR_FIRST_KILL, harness.status());

		// A Zuk task in the Inferno.
		harness.setTask(ZUK_TASK, TaskCaves.ZUK_TASK, 1, 1);
		harness.tick();
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals(TaskCaves.ZUK_TASK, harness.tracker().getSessionMonster());
		moveTo(TaskCaves.FIGHT_CAVES_REGION);
		assertEquals(SessionTracker.State.AWAY, harness.sessionState());

		// Another task in the Fight Caves: a new assignment ends the Zuk session, and the cave means nothing to it.
		harness.setTask(42, "Abyssal demons", 50, 50);
		harness.tick();
		assertEquals(SessionTracker.Status.WAITING_FOR_FIRST_KILL, harness.status());
	}
}
