package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.events.ConfigChanged;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Ignored loot, multi-bite food and deaths as wired in the plugin ({@link PluginHarness}): the ignored-items setting is
 * read at startUp and on ConfigChanged and applied when loot arrives, with item names read on the client thread;
 * pizzas are counted in bites from the inventory each tick; and local-player deaths count while RUNNING and survive
 * logout and a restart. {@link IgnoredLootTest}, {@link LootFilterTest}, {@link MultiBiteFoodTest} and
 * {@link SessionTrackerCountersTest} cover the rules.
 */
public class HarnessAccuracyTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int TASK = 42;
	private static final String EAT = "Eat";

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.BONES, "Bones", 100, 1, "Bury");
		harness.defineItem(ItemID.COINS, "Coins", 1, 1);
		harness.defineItem(ItemID.RANARR_SEED, "Ranarr seed", 40_000, 20);
		harness.defineItem(ItemID.DRAGON_BONES, "Dragon bones", 2_000, 1, "Bury");
		harness.defineNotedItem(ItemID.Cert.DRAGON_BONES, ItemID.DRAGON_BONES);
		harness.defineItem(ItemID.PLAIN_PIZZA, "Plain pizza", 300, 10, EAT);
		harness.defineItem(ItemID.HALF_PLAIN_PIZZA, "1/2 plain pizza", 200, 5, EAT);
		harness.defineItem(ItemID.MEAT_PIE, "Meat pie", 100, 6, EAT);
		harness.defineItem(ItemID.HALF_A_MEAT_PIE, "Half a meat pie", 60, 3, EAT);
		harness.defineItem(ItemID.PIEDISH, "Pie dish", 10, 1);
	}

	private void startRunning(String ignoredLoot)
	{
		harness.config.ignoredLoot = ignoredLoot;
		harness.startUp();
		harness.setInventory();
		harness.setTask(TASK, "Abyssal demons", 10, 10);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
	}

	private void killDrops()
	{
		harness.lootOnNextTick(ItemID.BONES, 1);
		harness.lootOnNextTick(ItemID.COINS, 100);
		harness.lootOnNextTick(ItemID.RANARR_SEED, 1);
		harness.lootOnNextTick(ItemID.Cert.DRAGON_BONES, 3);
		harness.tick();
	}

	private ItemTally loot()
	{
		return harness.tracker().getLoot();
	}

	// ---- Ignored loot ----

	@Test
	public void theSettingAtStartUpIsApplied()
	{
		startRunning("bones, *SEED");
		killDrops();
		assertEquals(0, loot().get(ItemID.BONES));
		assertEquals(0, loot().get(ItemID.RANARR_SEED));
		assertEquals(100, loot().get(ItemID.COINS));
		assertEquals("Dragon bones is not matched by 'bones'", 3, loot().get(ItemID.Cert.DRAGON_BONES));
	}

	@Test
	public void aChangedSettingAppliesToNewLootOnly()
	{
		startRunning("");
		killDrops();
		assertEquals(1, loot().get(ItemID.BONES));

		harness.setIgnoredLoot("Bones, Dragon bones, " + ItemID.COINS);
		assertEquals("loot already counted is kept", 1, loot().get(ItemID.BONES));
		killDrops();
		assertEquals(1, loot().get(ItemID.BONES));
		assertEquals(100, loot().get(ItemID.COINS));
		assertEquals("a noted drop matches its unnoted name", 3, loot().get(ItemID.Cert.DRAGON_BONES));
		assertEquals(2, loot().get(ItemID.RANARR_SEED));

		harness.setIgnoredLoot("");
		killDrops();
		assertEquals(2, loot().get(ItemID.BONES));

		harness.stop();
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(Long.valueOf(2), record.getLoot().get(ItemID.BONES));
		assertEquals(Long.valueOf(6), record.getLoot().get(ItemID.Cert.DRAGON_BONES));
	}

	@Test
	public void otherSettingsChangesDoNotReparse()
	{
		startRunning("coins");
		// The value changes without a ConfigChanged for its key: the cached list is still used.
		harness.config.ignoredLoot = "";
		harness.post(configChanged(SlayerLedgerConfig.GROUP, "bonecrusherHandling"));
		harness.post(configChanged("lootTracker", SlayerLedgerConfig.IGNORED_LOOT));
		killDrops();
		assertEquals(0, loot().get(ItemID.COINS));
	}

	@Test
	public void ignoredLootByIdMatchesTheNotedDrop()
	{
		startRunning(Integer.toString(ItemID.DRAGON_BONES));
		killDrops();
		assertEquals(0, loot().get(ItemID.Cert.DRAGON_BONES));
		assertEquals(1, loot().get(ItemID.BONES));
	}

	private static ConfigChanged configChanged(String group, String key)
	{
		ConfigChanged event = new ConfigChanged();
		event.setGroup(group);
		event.setKey(key);
		return event;
	}

	// ---- Multi-bite food ----

	@Test
	public void eatingAWholePizzaCostsOnePizza()
	{
		startRunning("");
		harness.setInventory(PluginHarness.item(ItemID.PLAIN_PIZZA, 1));
		harness.tick();
		harness.setInventory(PluginHarness.item(ItemID.HALF_PLAIN_PIZZA, 1));
		harness.tick();
		harness.setInventory();
		harness.tick();

		ItemTally used = harness.tracker().getSupplies();
		assertEquals(2, used.get(ItemID.PLAIN_PIZZA));
		assertEquals(0, used.get(ItemID.HALF_PLAIN_PIZZA));
		harness.stop();
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(Long.valueOf(2), record.getSupplies().get(ItemID.PLAIN_PIZZA));
		assertEquals(Integer.valueOf(2), record.getSupplyDoses().get(ItemID.PLAIN_PIZZA));
		assertEquals("exactly one pizza", Long.valueOf(300), record.getSuppliesGeValue());
	}

	@Test
	public void aHalfPieInTheInventoryAtPlayCostsHalf()
	{
		harness.config.ignoredLoot = "";
		harness.startUp();
		harness.setInventory(PluginHarness.item(ItemID.HALF_A_MEAT_PIE, 1));
		harness.setTask(TASK, "Abyssal demons", 10, 10);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		harness.setInventory(PluginHarness.item(ItemID.PIEDISH, 1));
		harness.tick();

		harness.stop();
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(Long.valueOf(1), record.getSupplies().get(ItemID.MEAT_PIE));
		assertNull("the dish is not a supply", record.getSupplies().get(ItemID.PIEDISH));
		assertEquals("half of the 100 gp pie", Long.valueOf(50), record.getSuppliesGeValue());
	}

	// ---- Deaths ----

	@Test
	public void localPlayerDeathsCountOnlyWhileRunning()
	{
		startRunning("");
		harness.localPlayerDies();
		harness.otherActorDies();
		assertEquals(1, harness.tracker().getDeaths());
		harness.pause();
		harness.localPlayerDies();
		assertEquals("not while paused", 1, harness.tracker().getDeaths());
		assertTrue(harness.play());
		harness.localPlayerDies();
		assertEquals("Superiors: 0 | Deaths: 2 | Bank trips: 0", countsText());
		harness.stop();
		assertEquals(Integer.valueOf(2), harness.onlyRecord(ACCOUNT).getDeaths());
	}

	@Test
	public void deathsSurviveLogoutAndARestart()
	{
		startRunning("");
		harness.localPlayerDies();
		harness.logout();
		SessionSnapshot snapshot = harness.activeSession(ACCOUNT);
		assertNotNull(snapshot);
		assertEquals(Integer.valueOf(1), snapshot.getDeaths());
		harness.shutDown();

		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		assertEquals(SessionTracker.State.PAUSED, harness.sessionState());
		assertEquals(1, harness.tracker().getDeaths());
		assertTrue(harness.play());
		harness.localPlayerDies();
		harness.stop();
		assertEquals(Integer.valueOf(2), harness.onlyRecord(ACCOUNT).getDeaths());
	}

	@Test
	public void deathDuringTheCompletionGraceWindowIsNotAdded()
	{
		startRunning("");
		harness.completeTask();
		harness.tick();
		assertTrue(harness.tracker().hasPendingCompletion());
		harness.localPlayerDies();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
		assertEquals(Integer.valueOf(0), harness.onlyRecord(ACCOUNT).getDeaths());
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
