package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import net.runelite.api.ChatMessageType;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Bonecrusher handling, superior spawns and bank trips as wired in the plugin ({@link PluginHarness}): the crusher is
 * read from the inventory and worn items at each loot drop on the client thread, the spawn message and bank interfaces
 * count only while RUNNING, and the counts survive logout and a restart. {@link CrushedLootFilterTest},
 * {@link SuperiorSpawnMessageTest}, {@link BankTripCounterTest} and {@link SessionTrackerCountersTest} cover the rules.
 */
public class HarnessTaskDetailsTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int TASK = 42;
	private static final String SUPERIOR = "<col=ef1020>A superior foe has appeared...</col>";

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.WYRM_BONES, "Wyrm bones", 600, 1, "Bury");
		harness.defineItem(ItemID.ABYSSAL_ASHES, "Abyssal ashes", 300, 1, "Scatter");
		harness.defineItem(ItemID.COINS, "Coins", 1, 1);
		harness.defineItem(ItemID.BONECRUSHER, "Bonecrusher", 0, 1);
		harness.defineItem(ItemID.BONECRUSHER_NECKLACE, "Bonecrusher necklace", 0, 1, "Wear");
		harness.defineItem(ItemID.ASH_SANCTIFIER, "Ash sanctifier", 0, 1);
		harness.startUp();
		harness.setInventory();
		harness.setTask(TASK, "Wyrms", 10, 10);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
	}

	/** Loot from one kill, arriving through LootManager on the next tick. */
	private void killDrops()
	{
		harness.lootOnNextTick(ItemID.WYRM_BONES, 1);
		harness.lootOnNextTick(ItemID.ABYSSAL_ASHES, 1);
		harness.lootOnNextTick(ItemID.COINS, 100);
		harness.tick();
	}

	private ItemTally loot()
	{
		return harness.tracker().getLoot();
	}

	// ---- Bonecrusher ----

	@Test
	public void lootWithoutACrusherKeepsBonesAndAshes()
	{
		killDrops();
		assertEquals(1, loot().get(ItemID.WYRM_BONES));
		assertEquals(1, loot().get(ItemID.ABYSSAL_ASHES));
		assertEquals(100, loot().get(ItemID.COINS));
	}

	@Test
	public void crusherIsCheckedAtEachLootDrop()
	{
		harness.setInventory(PluginHarness.item(ItemID.BONECRUSHER, 1));
		killDrops();
		assertEquals("crushed", 0, loot().get(ItemID.WYRM_BONES));
		assertEquals(1, loot().get(ItemID.ABYSSAL_ASHES));
		assertEquals(100, loot().get(ItemID.COINS));

		// Banked mid-task: the next kill's bones count again.
		harness.setInventory();
		killDrops();
		assertEquals(1, loot().get(ItemID.WYRM_BONES));

		// A worn bonecrusher necklace and an ash sanctifier.
		harness.setWorn(EquipmentInventorySlot.AMULET, ItemID.BONECRUSHER_NECKLACE, 1);
		harness.setInventory(PluginHarness.item(ItemID.ASH_SANCTIFIER, 1));
		killDrops();
		assertEquals(1, loot().get(ItemID.WYRM_BONES));
		assertEquals(2, loot().get(ItemID.ABYSSAL_ASHES));
		assertEquals(300, loot().get(ItemID.COINS));

		harness.stop();
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(Long.valueOf(1), record.getLoot().get(ItemID.WYRM_BONES));
		assertEquals(Long.valueOf(2), record.getLoot().get(ItemID.ABYSSAL_ASHES));
	}

	@Test
	public void lootPostedBetweenTicksIsFilteredToo()
	{
		harness.setInventory(PluginHarness.item(ItemID.BONECRUSHER, 1));
		harness.postLoot(ItemID.WYRM_BONES, 1);
		assertEquals(0, loot().get(ItemID.WYRM_BONES));
	}

	@Test
	public void overridesIgnoreWhatIsCarried()
	{
		harness.config.bonecrusherHandling = BonecrusherMode.ALWAYS_EXCLUDE;
		killDrops();
		assertEquals(0, loot().get(ItemID.WYRM_BONES));
		assertEquals(0, loot().get(ItemID.ABYSSAL_ASHES));
		assertEquals(100, loot().get(ItemID.COINS));

		harness.config.bonecrusherHandling = BonecrusherMode.NEVER_EXCLUDE;
		harness.setInventory(PluginHarness.item(ItemID.BONECRUSHER, 1), PluginHarness.item(ItemID.ASH_SANCTIFIER, 1));
		killDrops();
		assertEquals(1, loot().get(ItemID.WYRM_BONES));
		assertEquals(1, loot().get(ItemID.ABYSSAL_ASHES));
	}

	@Test
	public void lootOfOnlyCrushedBonesAddsNothing()
	{
		harness.setInventory(PluginHarness.item(ItemID.BONECRUSHER, 1));
		harness.lootOnNextTick(ItemID.WYRM_BONES, 1);
		harness.tick();
		assertTrue(loot().isEmpty());
	}

	// ---- Superior spawns ----

	@Test
	public void superiorSpawnCountsOnlyWhileRunning()
	{
		harness.chat(ChatMessageType.GAMEMESSAGE, SUPERIOR);
		harness.chat(ChatMessageType.SPAM, SUPERIOR);
		harness.chat(ChatMessageType.PUBLICCHAT, SUPERIOR);
		harness.chat(ChatMessageType.GAMEMESSAGE, "A superior foe has appeared");
		assertEquals(2, harness.tracker().getSuperiorsSpawned());

		harness.pause();
		harness.chat(ChatMessageType.GAMEMESSAGE, SUPERIOR);
		assertEquals("not while paused", 2, harness.tracker().getSuperiorsSpawned());

		assertTrue(harness.play());
		harness.chat(ChatMessageType.GAMEMESSAGE, SUPERIOR);
		harness.stop();
		assertEquals(Integer.valueOf(3), harness.onlyRecord(ACCOUNT).getSuperiorsSpawned());
	}

	@Test
	public void superiorSpawnDuringTheCompletionGraceWindowIsNotAdded()
	{
		harness.chat(ChatMessageType.GAMEMESSAGE, SUPERIOR);
		harness.completeTask();
		harness.tick();
		assertTrue("held for late loot", harness.tracker().hasPendingCompletion());
		harness.chat(ChatMessageType.GAMEMESSAGE, SUPERIOR);
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
		assertFalse(harness.tracker().hasPendingCompletion());

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals(Integer.valueOf(1), record.getSuperiorsSpawned());
	}

	// ---- Bank trips ----

	@Test
	public void bankOpensAndReopensCountAsTrips()
	{
		harness.openInterface(InterfaceID.BANKMAIN);
		harness.ticks(5);
		harness.closeInterface(InterfaceID.BANKMAIN);
		harness.ticks(10);
		// A forgotten item: the same trip.
		harness.openInterface(InterfaceID.BANKMAIN);
		harness.closeInterface(InterfaceID.BANKMAIN);
		assertEquals(1, harness.tracker().getBankTrips());

		harness.ticks(BankTripCounter.SAME_TRIP_TICKS + 1);
		harness.openInterface(InterfaceID.BANK_DEPOSITBOX);
		harness.closeInterface(InterfaceID.BANK_DEPOSITBOX);
		assertEquals(2, harness.tracker().getBankTrips());

		// Other interfaces are not banks.
		harness.ticks(BankTripCounter.SAME_TRIP_TICKS + 1);
		harness.openInterface(InterfaceID.SEED_VAULT);
		harness.closeInterface(InterfaceID.SEED_VAULT);
		assertEquals(2, harness.tracker().getBankTrips());

		harness.openInterface(InterfaceID.SHARED_BANK);
		assertEquals(3, harness.tracker().getBankTrips());
		harness.closeInterface(InterfaceID.SHARED_BANK);

		harness.stop();
		assertEquals(Integer.valueOf(3), harness.onlyRecord(ACCOUNT).getBankTrips());
	}

	@Test
	public void bankingWhilePausedIsNotATripAndANewSessionStartsAtZero()
	{
		harness.pause();
		harness.openInterface(InterfaceID.BANKMAIN);
		harness.closeInterface(InterfaceID.BANKMAIN);
		assertEquals(0, harness.tracker().getBankTrips());
		harness.ticks(BankTripCounter.SAME_TRIP_TICKS + 1);
		assertTrue(harness.play());
		harness.openInterface(InterfaceID.BANKMAIN);
		harness.closeInterface(InterfaceID.BANKMAIN);
		assertEquals(1, harness.tracker().getBankTrips());
		harness.stop();
		assertEquals(Integer.valueOf(1), harness.onlyRecord(ACCOUNT).getBankTrips());

		// The next session's first bank visit counts even within the window, and its count starts at 0.
		assertTrue(harness.play());
		assertEquals(0, harness.tracker().getBankTrips());
		harness.openInterface(InterfaceID.BANKMAIN);
		assertEquals(1, harness.tracker().getBankTrips());
	}

	// ---- Persistence and panel ----

	@Test
	public void countsSurviveLogoutAndARestart()
	{
		harness.chat(ChatMessageType.GAMEMESSAGE, SUPERIOR);
		harness.openInterface(InterfaceID.BANKMAIN);
		harness.closeInterface(InterfaceID.BANKMAIN);
		harness.logout();
		SessionSnapshot snapshot = harness.activeSession(ACCOUNT);
		assertNotNull(snapshot);
		assertEquals(Integer.valueOf(1), snapshot.getSuperiorsSpawned());
		assertEquals(Integer.valueOf(1), snapshot.getBankTrips());
		harness.shutDown();

		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		assertEquals("restored as paused", SessionTracker.State.PAUSED, harness.sessionState());
		assertEquals(1, harness.tracker().getSuperiorsSpawned());
		assertEquals(1, harness.tracker().getBankTrips());
		assertTrue(harness.play());
		harness.chat(ChatMessageType.GAMEMESSAGE, SUPERIOR);
		harness.openInterface(InterfaceID.BANKMAIN);
		harness.stop();

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(Integer.valueOf(2), record.getSuperiorsSpawned());
		assertEquals(Integer.valueOf(2), record.getBankTrips());
		assertNull(harness.activeSession(ACCOUNT));
	}

	@Test
	public void panelShowsTheCountsOnlyWhileASessionIsActive()
	{
		harness.chat(ChatMessageType.GAMEMESSAGE, SUPERIOR);
		harness.openInterface(InterfaceID.BANKMAIN);
		assertEquals("Superiors: 1 | Deaths: 0 | Bank trips: 1", countsText());
		harness.pause();
		assertEquals("Superiors: 1 | Deaths: 0 | Bank trips: 1", countsText());
		harness.stop();
		assertNull("hidden once the session ended", countsText());
	}

	private String countsText()
	{
		final String[] text = new String[1];
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			text[0] = harness.ui().getCountsText();
		});
		return text[0];
	}
}
