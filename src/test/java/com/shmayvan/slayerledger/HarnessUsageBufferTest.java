package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.Item;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Supplies, XP and loot outside segments, through the plugin's events (V2 plan 4.1): the buffer while a task is
 * assigned and no segment is open; the 3-minute supplies warm-up and XP and loot from the segment start at every
 * (re)start, with the resume point's clamps (End task among them), kept across a hop; what Away takes back, and how it
 * counts at the next restart; the restart kill's own XP; and the final kill's NPC tallies. {@link SessionBufferTest}
 * covers the same rules on the tracker alone, {@link HarnessTaskCaveTest} the Jad and Zuk tasks.
 */
public class HarnessUsageBufferTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int TASK = 42;
	private static final String TASK_NAME = "Abyssal demons";
	private static final int DEMON = 415;
	private static final int SPECTRES = 7;
	private static final long MINUTE = 60_000L;
	private static final long SECOND = 1_000L;
	private static final String[] DRINK = {"Drink", null, null, "Empty", "Drop"};

	@Rule
	public final PluginHarness harness = new PluginHarness();

	/** The tracker's clock: supplies, XP, loot, hits, deaths and segments are timed by it. */
	private final AtomicLong now = new AtomicLong(1_000_000_000L);
	private int nextIndex = 1;
	/** Sharks in the inventory. */
	private int sharks = 30;
	/** Potions in the inventory besides the sharks, one item id each (none unless a test adds them). */
	private final List<Integer> potions = new ArrayList<>();

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.SHARK, "Shark", 1_000, 300, "Eat");
		harness.defineItem(ItemID.ABYSSAL_WHIP, "Abyssal whip", 1_500_000, 72_000);
		harness.defineItem(ItemID.BONES, "Bones", 100, 1);
		harness.setSkillXp(Skill.HITPOINTS, 1_000_000);
		harness.setSkillXp(Skill.SLAYER, 2_000_000);
		harness.setSkillXp(Skill.ATTACK, 3_000_000);
		harness.startUp();
		harness.setTrackerClock(now::get);
		harness.setInventory(inventory());
		harness.setTask(TASK, TASK_NAME, 50, 50);
		harness.loginAndSettle(ACCOUNT);
		// The task is read; the supplies diff takes its first baseline.
		harness.tick();
	}

	private Item[] inventory()
	{
		final List<Item> items = new ArrayList<>();
		for (int i = 0; i < sharks; i++)
		{
			items.add(PluginHarness.item(ItemID.SHARK, 1));
		}
		for (int id : potions)
		{
			items.add(PluginHarness.item(id, 1));
		}
		return items.toArray(new Item[0]);
	}

	/** Eats a shark now: the supplies diff sees it on the next tick. */
	private void eat()
	{
		sharks--;
		harness.setInventory(inventory());
		harness.tick();
	}

	/**
	 * Defines a potion family's 4- and 3-dose items, as the supplies diff reads potions, and puts {@code count} 4-dose
	 * potions in the inventory; the next tick sees them (a gain, never a use).
	 */
	private void holdPotions(String name, int fourDose, int threeDose, int count)
	{
		harness.defineItem(fourDose, name + "(4)", 10_000, 0, DRINK);
		harness.defineItem(threeDose, name + "(3)", 7_500, 0, DRINK);
		for (int i = 0; i < count; i++)
		{
			potions.add(fourDose);
		}
		harness.setInventory(inventory());
		harness.tick();
	}

	/** Drinks one dose of a 4-dose potion now, leaving a 3-dose one: the supplies diff sees it on the next tick. */
	private void drink(int fourDose, int threeDose)
	{
		potions.set(potions.indexOf(fourDose), threeDose);
		harness.setInventory(inventory());
		harness.tick();
	}

	/** Doses used of the potion family whose 4-dose item is {@code fourDose} (supplies key a family by it). */
	private long dosesUsed(int fourDose)
	{
		return harness.tracker().getSupplies().get(fourDose);
	}

	private long sharksUsed()
	{
		return harness.tracker().getSupplies().get(ItemID.SHARK);
	}

	private long xp(Skill skill)
	{
		return harness.tracker().getXpGained().getOrDefault(skill.name(), 0L);
	}

	private long loot(int itemId)
	{
		return harness.tracker().getLoot().get(itemId);
	}

	private NPC demon()
	{
		return harness.npc(nextIndex++, DEMON, "Abyssal demon");
	}

	/** The count drops by {@code n} on the next tick. */
	private void countDrops(int n)
	{
		harness.setRemaining(harness.tracker().getCurrentRemaining() - n);
		harness.tick();
	}

	/** A kill with no NPC of the player's dying (a cannon-only kill): the count drops on the next tick. */
	private void cannonKill()
	{
		countDrops(1);
	}

	/** A fight: the player's first hit now, more hits for {@code millis}, then the NPC dies and the count drops. */
	private void fight(long millis)
	{
		final NPC npc = demon();
		harness.hitsplat(npc, true);
		now.addAndGet(millis);
		harness.hitsplat(npc, true);
		harness.npcDies(npc);
		countDrops(1);
	}

	private long active()
	{
		return harness.tracker().getActiveMillis();
	}

	// ---- The first kill: warm-up, and a long fight counting in full ----

	@Test
	public void aBossFightLongerThanTheWarmUpCountsInFullFromTheFirstHit()
	{
		// Five minutes before the first hit: before the warm-up.
		eat();
		// Two and a half minutes before it: a pre-pot, in the warm-up.
		now.addAndGet(2 * MINUTE + 30 * SECOND);
		eat();
		// XP before the first hit (another fight) is not the task's.
		now.addAndGet(30 * SECOND);
		harness.gainXp(Skill.ATTACK, 50);
		harness.tick();
		assertEquals("buffered, not counted yet", SessionTracker.State.IDLE, harness.sessionState());

		now.addAndGet(2 * MINUTE);
		final NPC boss = demon();
		harness.hitsplat(boss, true);
		for (int minute = 1; minute <= 6; minute++)
		{
			now.addAndGet(MINUTE);
			harness.hitsplat(boss, true);
			harness.gainXp(Skill.ATTACK, 100);
			eat();
		}
		now.addAndGet(30 * SECOND);
		harness.npcDies(boss);
		harness.gainXp(Skill.SLAYER, 500);
		countDrops(1);
		harness.lootOnNextTick(ItemID.ABYSSAL_WHIP, 1);
		harness.tick();

		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals("from the first hit", 6 * MINUTE + 30 * SECOND, active());
		assertEquals("the pre-pot and every shark of the fight, not the one five minutes before", 7, sharksUsed());
		assertEquals("the whole fight's XP, not what came before the first hit", 600, xp(Skill.ATTACK));
		assertEquals("the kill's own XP", 500, xp(Skill.SLAYER));
		assertEquals(1, loot(ItemID.ABYSSAL_WHIP));

		harness.endTask();
		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(Long.valueOf(7), record.getSupplies().get(ItemID.SHARK));
		assertEquals(Long.valueOf(600), record.getXpGained().get(Skill.ATTACK.name()));
	}

	// ---- The warm-up's clamps ----

	@Test
	public void aTuraelSkipKeepsThePreviousTasksSuppliesOutOfTheNewOnesWarmUp()
	{
		// Used while the first task waits for its first kill.
		eat();
		now.addAndGet(30 * SECOND);
		// Turael skips it for another task.
		harness.setTask(43, "Black demons", 30, 30);
		harness.tick();
		now.addAndGet(30 * SECOND);
		eat();
		// The new task's first kill, a minute and a half after the first shark: both lie in its 3 minutes.
		now.addAndGet(30 * SECOND);
		cannonKill();

		assertEquals("Black demons", harness.tracker().getSessionMonster());
		assertEquals("only the shark eaten on the new task", 1, sharksUsed());
	}

	@Test
	public void theWarmUpNeverReachesBackBeforeThePreviousSessionsEnd()
	{
		fight(10 * SECOND);
		harness.tick();
		harness.pause();
		now.addAndGet(10 * SECOND);
		// Used while paused, then the session is ended: neither counts it.
		eat();
		now.addAndGet(10 * SECOND);
		harness.endTask();
		assertNull("no supplies", harness.onlyRecord(ACCOUNT).getSupplies());

		now.addAndGet(10 * SECOND);
		harness.trackAgain();
		// The diff runs again from the next tick.
		harness.tick();
		now.addAndGet(10 * SECOND);
		eat();
		now.addAndGet(10 * SECOND);
		cannonKill();

		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals("only the shark eaten after the previous session ended", 1, sharksUsed());
	}

	@Test
	public void endTaskWhileWaitingForTheFirstKillDropsWhatWasHeldBack()
	{
		eat();
		now.addAndGet(10 * SECOND);
		harness.endTask();
		assertEquals(SessionTracker.Status.ENDED, harness.status());

		now.addAndGet(10 * SECOND);
		harness.trackAgain();
		// The diff runs again from the next tick.
		harness.tick();
		now.addAndGet(10 * SECOND);
		// Half a minute after the shark: well within the warm-up, were it not for End task.
		cannonKill();

		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals("the shark eaten before End task", 0, sharksUsed());
	}

	@Test
	public void aHopKeepsWhatWasHeldBack()
	{
		// A pre-pot while waiting for the first kill, then a hop.
		eat();
		now.addAndGet(30 * SECOND);
		harness.hop();
		harness.ticks(PluginHarness.SETTLE_TICKS);
		assertEquals("a hop is not a stop", SessionTracker.Status.WAITING_FOR_FIRST_KILL, harness.status());
		assertEquals("the shark is still held back", 1, harness.tracker().bufferSize());

		now.addAndGet(30 * SECOND);
		cannonKill();
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals("the shark eaten before the hop is in the warm-up", 1, sharksUsed());
	}

	@Test
	public void theWarmUpAfterAPauseCountsWhatWasUsedSinceThePauseOnce()
	{
		fight(10 * SECOND);
		now.addAndGet(10 * SECOND);
		eat();
		assertEquals("counted while running", 1, sharksUsed());
		now.addAndGet(MINUTE);
		harness.pause();
		now.addAndGet(30 * SECOND);
		eat();
		assertEquals("buffered while paused", 1, sharksUsed());

		// A kill restart a minute after the pause: its warm-up starts at the pause (the close of the previous segment),
		// so the shark eaten before the pause, already counted, is not counted again.
		now.addAndGet(30 * SECOND);
		fight(10 * SECOND);
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals(2, sharksUsed());
	}

	@Test
	public void resumeCountsTheWarmUpBeforeIt()
	{
		cannonKill();
		harness.pause();
		now.addAndGet(5 * MINUTE);
		eat();
		now.addAndGet(MINUTE);
		harness.gainXp(Skill.ATTACK, 70);
		eat();
		now.addAndGet(MINUTE);
		assertTrue(harness.resume());
		assertEquals("the sharks of the last 3 minutes", 2, sharksUsed());
		assertEquals("XP counts from the restart only", 0, xp(Skill.ATTACK));
	}

	// ---- Pause and Away ----

	@Test
	public void aPauseHoldingAFortyMinuteRaidCountsOnlyItsLastThreeMinutesOfSupplies()
	{
		fight(10 * SECOND);
		harness.tick();
		harness.pause();
		final long paused = now.get();
		for (int minute = 5; minute <= 35; minute += 5)
		{
			now.set(paused + minute * MINUTE);
			harness.gainXp(Skill.ATTACK, 1_000);
			harness.postLoot("Great Olm", ItemID.BONES, 10);
			eat();
		}
		now.set(paused + 38 * MINUTE + 30 * SECOND);
		eat();

		// The kill that restarts the session, with its own Slayer XP.
		now.set(paused + 40 * MINUTE);
		harness.gainXp(Skill.SLAYER, 100);
		cannonKill();

		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals("only the shark of the last 3 minutes before the restart", 1, sharksUsed());
		assertEquals("the raid's XP never counts", 0, xp(Skill.ATTACK));
		assertEquals("nor its loot", 0, loot(ItemID.BONES));
		assertEquals("the restart kill's XP does", 100, xp(Skill.SLAYER));
	}

	@Test
	public void aKillThenAwayKeepsItsXpAndLateLootAndTakesBackLaterUse()
	{
		fight(10 * SECOND);
		now.addAndGet(30 * SECOND);
		// The second kill, with its Slayer XP on its tick.
		final NPC npc = demon();
		harness.hitsplat(npc, true);
		harness.npcDies(npc);
		harness.gainXp(Skill.SLAYER, 300);
		countDrops(1);
		// Its loot arrives two ticks and a second later.
		now.addAndGet(SECOND);
		harness.tick();
		harness.lootOnNextTick(ItemID.ABYSSAL_WHIP, 1);
		harness.tick();

		// Then the player stops doing the task: a shark, other XP, other loot.
		now.addAndGet(MINUTE);
		eat();
		now.addAndGet(MINUTE);
		harness.gainXp(Skill.ATTACK, 200);
		harness.tick();
		now.addAndGet(MINUTE);
		harness.postLoot("Goblin", ItemID.BONES, 5);
		assertEquals(1, sharksUsed());
		assertEquals(200, xp(Skill.ATTACK));
		assertEquals(5, loot(ItemID.BONES));

		now.addAndGet(7 * MINUTE - SECOND);
		harness.tick();
		assertEquals(SessionTracker.State.AWAY, harness.sessionState());
		assertEquals("the clock steps back to the last kill", 40 * SECOND, active());
		assertEquals("the kill's own XP stays", 300, xp(Skill.SLAYER));
		assertEquals("its loot stays", 1, loot(ItemID.ABYSSAL_WHIP));
		assertEquals("the later shark is taken back", 0, sharksUsed());
		assertEquals("so is the later XP", 0, xp(Skill.ATTACK));
		assertEquals("and the other NPC's loot", 0, loot(ItemID.BONES));

		// The task monster's loot this long after the last task kill is no paired kill's: it doesn't count.
		harness.lootOnNextTick(ItemID.ABYSSAL_WHIP, 1);
		harness.tick();
		assertEquals(1, loot(ItemID.ABYSSAL_WHIP));
		// Nor does other loot.
		harness.postLoot("Goblin", ItemID.BONES, 5);
		assertEquals(0, loot(ItemID.BONES));

		harness.endTask();
		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertNull("no supplies saved", record.getSupplies());
		assertEquals(Long.valueOf(1), record.getLoot().get(ItemID.ABYSSAL_WHIP));
	}

	@Test
	public void whatAwayTookBackCountsInTheNextWarmUpAtItsOwnTimeOnce()
	{
		holdPotions("Prayer potion", ItemID._4DOSEPRAYERRESTORE, ItemID._3DOSEPRAYERRESTORE, 2);
		holdPotions("Super restore", ItemID._4DOSE2RESTORE, ItemID._3DOSE2RESTORE, 1);
		holdPotions("Super combat potion", ItemID._4DOSE2COMBAT, ItemID._3DOSE2COMBAT, 1);
		fight(10 * SECOND);
		harness.tick();
		final long lastKill = now.get();

		// Sips after the last kill, counted while the clock runs.
		now.set(lastKill + MINUTE);
		drink(ItemID._4DOSEPRAYERRESTORE, ItemID._3DOSEPRAYERRESTORE);
		now.set(lastKill + 8 * MINUTE + 30 * SECOND);
		drink(ItemID._4DOSE2RESTORE, ItemID._3DOSE2RESTORE);
		assertEquals(1, dosesUsed(ItemID._4DOSEPRAYERRESTORE));
		assertEquals(1, dosesUsed(ItemID._4DOSE2RESTORE));

		// Away, 10 minutes after the last kill, takes both back into the buffer.
		now.set(lastKill + 10 * MINUTE);
		harness.tick();
		assertEquals(SessionTracker.State.AWAY, harness.sessionState());
		assertEquals(0, dosesUsed(ItemID._4DOSEPRAYERRESTORE));
		assertEquals(0, dosesUsed(ItemID._4DOSE2RESTORE));

		// More sips at the bank.
		now.set(lastKill + 10 * MINUTE + 30 * SECOND);
		drink(ItemID._4DOSE2COMBAT, ItemID._3DOSE2COMBAT);
		now.set(lastKill + 10 * MINUTE + 45 * SECOND);
		drink(ItemID._4DOSEPRAYERRESTORE, ItemID._3DOSEPRAYERRESTORE);

		// The restart, 11 minutes after the last kill: its warm-up starts 8 minutes after it, after the first prayer sip
		// and before the super restore (both taken back at their own times, not when Away started).
		now.set(lastKill + 11 * MINUTE);
		cannonKill();
		harness.tick();
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals("the bank sip, not the one taken back from before the warm-up", 1,
			dosesUsed(ItemID._4DOSEPRAYERRESTORE));
		assertEquals("taken back, then counted again in the warm-up: once", 1, dosesUsed(ItemID._4DOSE2RESTORE));
		assertEquals(1, dosesUsed(ItemID._4DOSE2COMBAT));
		assertEquals("the rest is dropped", 0, harness.tracker().bufferSize());
	}

	@Test
	public void fightingOtherNpcsPastTheAwayLimitAddsNoSuppliesXpOrLoot()
	{
		fight(10 * SECOND);
		harness.tick();
		final NPC boss = harness.npc(nextIndex++, 7540, "Tekton");
		for (int minute = 1; minute <= 15; minute++)
		{
			now.addAndGet(MINUTE);
			harness.hitsplat(boss, true);
			harness.gainXp(Skill.ATTACK, 100);
			eat();
			harness.postLoot("Tekton", ItemID.BONES, 1);
		}
		assertEquals(SessionTracker.State.AWAY, harness.sessionState());
		assertEquals(10 * SECOND, active());
		assertEquals(0, sharksUsed());
		assertEquals(0, xp(Skill.ATTACK));
		assertEquals(0, loot(ItemID.BONES));

		// A task kill five quiet minutes later: none of it is in its warm-up.
		now.addAndGet(5 * MINUTE);
		cannonKill();
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals(0, sharksUsed());
		assertEquals(0, xp(Skill.ATTACK));
		assertEquals(0, loot(ItemID.BONES));
	}

	// ---- The restart kill and the final kill ----

	@Test
	public void theXpOfTheKillThatStartsOrRestartsTheSessionCounts()
	{
		// Every read moves the clock on a millisecond, as real time moves between the XP read and the kill.
		harness.setTrackerClock(now::getAndIncrement);
		harness.gainXp(Skill.SLAYER, 100);
		cannonKill();
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals("the first kill's own XP", 100, xp(Skill.SLAYER));

		harness.pause();
		now.addAndGet(MINUTE);
		harness.gainXp(Skill.SLAYER, 50);
		cannonKill();
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals("the restart kill's own XP", 150, xp(Skill.SLAYER));
	}

	@Test
	public void aDeathPairedWithTheFinalKillIsTalliedDuringTheGraceWindow()
	{
		harness.setTask(SPECTRES, "Aberrant spectres", 2, 2);
		harness.tick();
		final NPC first = harness.npc(nextIndex++, NpcID.KOUREND_SPECTRE, "Deviant spectre");
		harness.hitsplat(first, true);
		harness.npcDies(first);
		now.addAndGet(10 * SECOND);
		countDrops(1);
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());

		// The final kill completes the task; its NPC dies on the tick after.
		final NPC last = harness.npc(nextIndex++, NpcID.KOUREND_SPECTRE, "Deviant spectre");
		harness.hitsplat(last, true);
		now.addAndGet(10 * SECOND);
		harness.completeTask();
		harness.tick();
		assertTrue(harness.tracker().hasPendingCompletion());
		harness.npcDies(last);
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);

		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals("both kills were the variant's", "Deviant spectres", record.getVariant());
	}

	@Test
	public void aCompletionTicksAfterTheDropToZeroTalliesTheFinalKillFromTheDropTick()
	{
		harness.setTask(SPECTRES, "Aberrant spectres", 2, 2);
		harness.tick();
		final NPC first = harness.npc(nextIndex++, NpcID.KOUREND_SPECTRE, "Deviant spectre");
		harness.hitsplat(first, true);
		harness.npcDies(first);
		now.addAndGet(10 * SECOND);
		countDrops(1);
		harness.logout();
		now.addAndGet(30 * MINUTE);
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(2);
		assertEquals(SessionTracker.Status.WAITING_FOR_NEXT_KILL, harness.status());

		// The final kill: its NPC dies, the count drops to zero on the next tick (D), and the completion comes three
		// ticks after that (C). Its death, on D - 1, is paired from D - 2, not C - 2, and is still kept on C.
		final NPC last = harness.npc(nextIndex++, NpcID.KOUREND_SPECTRE, "Deviant spectre");
		harness.hitsplat(last, true);
		now.addAndGet(20 * SECOND);
		harness.npcDies(last);
		harness.tick();
		countDrops(1);
		harness.ticks(2);
		harness.completeTask();
		harness.tick();
		assertTrue("restarted first, so held for its loot", harness.tracker().hasPendingCompletion());
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);

		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals(2, record.getKills());
		assertEquals("the final kill's death is tallied", "Deviant spectres", record.getVariant());
		assertEquals("its fight counts", 10 * SECOND + 20 * SECOND, record.getActiveMillis());
	}
}
