package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.ChatMessageType;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemEquipmentStats;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * The follow-ups of the automatic-tracking review, through the plugin's events: a hit's activity and engagement
 * are timed with its tick's supplies, XP and attack (not before them); a known task NPC's loot counts while the
 * session is stopped only just after a task kill; and Away takes back the bank trips since the last activity, but not
 * the deaths (project-manager decision) or the superiors.
 */
public class HarnessTrackingFollowUpsTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int TASK = 42;
	private static final int DEMON = 415;
	private static final long TICK = 600L;
	private static final long MINUTE = 60_000L;
	private static final int BOW = 40_003;
	private static final String SUPERIOR = "<col=ef1020>A superior foe has appeared...</col>";

	@Rule
	public final PluginHarness harness = new PluginHarness();

	private final AtomicLong now = new AtomicLong(1_000_000_000L);
	private int nextIndex = 1;
	private int sharks = 20;

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.SHARK, "Shark", 1_000, 300, "Eat");
		harness.defineItem(ItemID.ABYSSAL_WHIP, "Abyssal whip", 1_500_000, 72_000);
		harness.defineEquipment(BOW, "Magic shortbow (i)", ItemEquipmentStats.builder()
			.slot(EquipmentInventorySlot.WEAPON.getSlotIdx()).arange(75).build());
		harness.setSkillXp(Skill.HITPOINTS, 1_000_000);
		harness.setSkillXp(Skill.ATTACK, 2_000_000);
		harness.setSkillXp(Skill.RANGED, 3_000_000);
		harness.startUp();
		harness.setTrackerClock(now::get);
		harness.setInventory(inventory());
		harness.setTask(TASK, "Abyssal demons", 50, 50);
		harness.loginAndSettle(ACCOUNT);
		tick();
	}

	private Item[] inventory()
	{
		final List<Item> items = new ArrayList<>();
		for (int i = 0; i < sharks; i++)
		{
			items.add(PluginHarness.item(ItemID.SHARK, 1));
		}
		return items.toArray(new Item[0]);
	}

	private void tick()
	{
		now.addAndGet(TICK);
		harness.tick();
	}

	/** An Abyssal demon, the name {@link PluginHarness#lootOnNextTick} reports loot for. */
	private NPC demon()
	{
		return harness.npc(nextIndex++, DEMON, PluginHarness.LOOT_NPC_NAME);
	}

	/** A one-hit task kill: the hit and death now, the count's drop on the next tick, and one more tick. */
	private void kill()
	{
		final NPC npc = demon();
		harness.hitsplat(npc, true);
		harness.npcDies(npc);
		harness.setRemaining(harness.tracker().getCurrentRemaining() - 1);
		tick();
		tick();
	}

	private long xp(Skill skill)
	{
		return harness.tracker().getXpGained().getOrDefault(skill.name(), 0L);
	}

	// ---- Hit timing within a tick ----

	@Test
	public void aMeleeHitsOwnTickXpAndSuppliesAreNotTakenBackByAway()
	{
		kill();
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		// A hit on a task monster arrives early in its tick; the tick's XP and the shark eaten are read on its GameTick,
		// a moment later.
		final NPC demon = demon();
		harness.hitsplat(demon, true);
		now.addAndGet(300);
		harness.gainXp(Skill.ATTACK, 40);
		sharks--;
		harness.setInventory(inventory());
		harness.tick();
		assertEquals(40, xp(Skill.ATTACK));
		assertEquals(1, harness.tracker().getSupplies().get(ItemID.SHARK));

		// Nothing more: Away, closing at that hit.
		now.addAndGet(10 * MINUTE);
		harness.tick();
		assertEquals(SessionTracker.State.AWAY, harness.sessionState());
		assertEquals("the hit's own tick's XP stays", 40, xp(Skill.ATTACK));
		assertEquals("and so does its shark", 1, harness.tracker().getSupplies().get(ItemID.SHARK));
	}

	@Test
	public void aRangedAttacksOwnTickXpCountsWhenItsKillStartsTheSession()
	{
		harness.setWorn(EquipmentInventorySlot.WEAPON, BOW, 1);
		final NPC demon = demon();
		// The attack starts, with its Ranged and Hitpoints XP on the same tick; no session yet, so it is held back.
		harness.setInteracting(demon);
		harness.setAnimation(AnimationID.HUMAN_BOW, 0);
		harness.gainXp(Skill.RANGED, 80);
		harness.gainXp(Skill.HITPOINTS, 26);
		tick();
		final long attackTick = now.get();
		harness.setAnimation(AnimationID.HUMAN_BOW, 1);
		tick();
		// The arrow lands two ticks later and kills it: the first task kill.
		now.addAndGet(TICK);
		harness.hitsplat(demon, true);
		harness.npcDies(demon);
		harness.setRemaining(49);
		harness.tick();

		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals("backdated to the attack's tick", now.get() - attackTick, harness.tracker().getActiveMillis());
		assertEquals("its XP counts", 80, xp(Skill.RANGED));
	}

	// ---- Loot of a task kill ----

	@Test
	public void aTaskKillsLootCountsWhileStoppedOnlyJustAfterTheKill()
	{
		kill();
		harness.pause();
		// The kill's loot arrives a tick later.
		harness.lootOnNextTick(ItemID.ABYSSAL_WHIP, 1);
		tick();
		assertEquals(1, harness.tracker().getLoot().get(ItemID.ABYSSAL_WHIP));

		// The same monster's loot a minute later, still paused (killed outside a Konar area, say): held back.
		now.addAndGet(MINUTE);
		harness.lootOnNextTick(ItemID.ABYSSAL_WHIP, 1);
		tick();
		assertEquals(1, harness.tracker().getLoot().get(ItemID.ABYSSAL_WHIP));

		harness.endTask();
		assertEquals(Long.valueOf(1), harness.onlyRecord(ACCOUNT).getLoot().get(ItemID.ABYSSAL_WHIP));
	}

	// ---- Counters on Away ----

	@Test
	public void bankTripsAfterTheLastActivityAreTakenBackOnAwayButDeathsAndSuperiorsStay()
	{
		kill();
		// A death before the next kill: the player was still on the task.
		now.addAndGet(MINUTE);
		harness.localPlayerDies();
		now.addAndGet(MINUTE);
		kill();

		// After the last kill: a bank trip, a death and a superior.
		now.addAndGet(MINUTE);
		harness.openInterface(InterfaceID.BANKMAIN);
		harness.closeInterface(InterfaceID.BANKMAIN);
		harness.localPlayerDies();
		harness.chat(ChatMessageType.GAMEMESSAGE, SUPERIOR);
		assertEquals(1, harness.tracker().getBankTrips());
		assertEquals(2, harness.tracker().getDeaths());
		assertEquals(1, harness.tracker().getSuperiorsSpawned());

		now.addAndGet(10 * MINUTE);
		tick();
		assertEquals(SessionTracker.State.AWAY, harness.sessionState());
		assertEquals("the bank trip after the last kill is taken back", 0, harness.tracker().getBankTrips());
		assertEquals("a death a while after the last hit is still a task death", 2, harness.tracker().getDeaths());
		assertEquals("only task kills spawn superiors", 1, harness.tracker().getSuperiorsSpawned());

		harness.endTask();
		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(Integer.valueOf(0), record.getBankTrips());
		assertEquals(Integer.valueOf(2), record.getDeaths());
		assertEquals(Integer.valueOf(1), record.getSuperiorsSpawned());
	}
}
