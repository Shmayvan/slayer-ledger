package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.PluginHarness.item;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.LoggerFactory;

/**
 * Supply counting must not stop for good during a long client run. On 2026-11-08 every task after a bank visit
 * was saved with no supplies at all while its session ran normally: the bank's interface group can stay loaded after
 * the bank closed ({@link PluginHarness#loadInterfaceWithoutOpening}), and the plugin took a loaded root widget for an
 * open interface, so it left every later tick out of the supplies diff. An excluded interface now counts as open only
 * while its root widget is loaded and shown. Counting also carries on after a bank trip, a logout and login, a world
 * hop and a lost connection in the same client run. {@link HarnessSupplyExclusionsTest} covers the exclusions
 * themselves.
 */
public class HarnessSupplyCountingStopTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final String[] EAT = {"Eat", null, null, null, "Drop"};
	private static final String[] DRINK = {"Drink", null, null, "Empty", "Drop"};
	private static final String[] NO_USE = {null, null, null, null, "Drop"};
	/** Prayer potion item ids by dose (index 1 to 4). */
	private static final int[] PRAYER = {-1, ItemID._1DOSEPRAYERRESTORE, ItemID._2DOSEPRAYERRESTORE,
		ItemID._3DOSEPRAYERRESTORE, ItemID._4DOSEPRAYERRESTORE};

	@Rule
	public final PluginHarness harness = new PluginHarness();

	private int sharks = 10;
	/** Prayer potions held, by dose (index 1 to 4). */
	private final int[] potions = {0, 0, 0, 0, 4};
	private int deathRunes;
	private int bloodRunes;
	private int waterRunes;

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.SHARK, "Shark", 800, 0, EAT);
		for (int dose = 1; dose <= 4; dose++)
		{
			harness.defineItem(PRAYER[dose], "Prayer potion(" + dose + ")", 2_000L * dose, 0, DRINK);
		}
		harness.defineItem(ItemID.DEATHRUNE, "Death rune", 200, 0, NO_USE);
		harness.defineItem(ItemID.BLOODRUNE, "Blood rune", 300, 0, NO_USE);
		harness.defineItem(ItemID.WATERRUNE, "Water rune", 5, 0, NO_USE);
		updateInventory();
		harness.startUp();
		harness.setTask(42, "Dust devils", 200, 200);
		harness.loginAndSettle(ACCOUNT);
	}

	/** Sets the inventory to the sharks, potions and runes held now. */
	private void updateInventory()
	{
		final List<Item> items = new ArrayList<>();
		addStack(items, ItemID.SHARK, sharks);
		for (int dose = 1; dose <= 4; dose++)
		{
			for (int i = 0; i < potions[dose]; i++)
			{
				items.add(item(PRAYER[dose], 1));
			}
		}
		addStack(items, ItemID.DEATHRUNE, deathRunes);
		addStack(items, ItemID.BLOODRUNE, bloodRunes);
		addStack(items, ItemID.WATERRUNE, waterRunes);
		harness.setInventory(items.toArray(new Item[0]));
	}

	private static void addStack(List<Item> items, int itemId, int quantity)
	{
		if (quantity > 0)
		{
			items.add(item(itemId, quantity));
		}
	}

	/** Eats one shark and drinks one dose (from the potion with the fewest doses), each on its own tick. */
	private void eatAndDrink()
	{
		sharks--;
		updateInventory();
		harness.tick();
		for (int dose = 1; dose <= 4; dose++)
		{
			if (potions[dose] > 0)
			{
				// The drunk dose leaves a potion with one dose fewer (a 1-dose potion leaves a vial, not tracked).
				potions[dose]--;
				if (dose > 1)
				{
					potions[dose - 1]++;
				}
				break;
			}
		}
		updateInventory();
		harness.tick();
	}

	/**
	 * A bank trip: the bank opens, sharks are deposited, then sharks, potions and runes are withdrawn, and the bank
	 * closes, after which nothing is counted for a couple of ticks. With {@code groupLeftLoaded}, the game then loads
	 * the bank's group again without opening it, as suspected on 2026-11-08.
	 */
	private void bankTrip(boolean groupLeftLoaded)
	{
		harness.openInterface(InterfaceID.BANKMAIN);
		harness.tick();
		sharks = 5;
		updateInventory();
		harness.tick();
		sharks = 20;
		potions[4] += 2;
		deathRunes = 1_000;
		bloodRunes = 500;
		waterRunes = 1_500;
		updateInventory();
		harness.tick();
		harness.closeInterface(InterfaceID.BANKMAIN);
		if (groupLeftLoaded)
		{
			harness.loadInterfaceWithoutOpening(InterfaceID.BANKMAIN);
		}
		harness.ticks(SupplyExclusions.INTERFACE_TICKS + 1);
	}

	/** Stops the session and returns the saved record's supplies. */
	private Map<Integer, Long> stopAndGetSupplies()
	{
		harness.endTask();
		final Map<Integer, Long> supplies = harness.onlyRecord(ACCOUNT).getSupplies();
		return supplies == null ? Collections.emptyMap() : supplies;
	}

	private static long used(Map<Integer, Long> supplies, int itemId)
	{
		return supplies.getOrDefault(itemId, 0L);
	}

	@Test
	public void theBanksGroupLeftLoadedAfterItClosedDoesNotStopCounting()
	{
		assertTrue(harness.startNow());
		eatAndDrink();
		bankTrip(true);
		for (int i = 0; i < 5; i++)
		{
			eatAndDrink();
		}

		final Map<Integer, Long> supplies = stopAndGetSupplies();
		assertEquals("sharks eaten before and after the bank trip", 6L, used(supplies, ItemID.SHARK));
		assertEquals("prayer potion doses", 6L, used(supplies, ItemID._4DOSEPRAYERRESTORE));
	}

	@Test
	public void noExcludedInterfaceThatIsOnlyLoadedStopsCounting()
	{
		for (int group : SupplyExclusions.excludedInterfaces())
		{
			harness.loadInterfaceWithoutOpening(group);
		}
		assertTrue(harness.startNow());
		harness.tick();
		eatAndDrink();
		eatAndDrink();

		final Map<Integer, Long> supplies = stopAndGetSupplies();
		assertEquals(2L, used(supplies, ItemID.SHARK));
		assertEquals(2L, used(supplies, ItemID._4DOSEPRAYERRESTORE));
	}

	@Test
	public void barrageRunesLooseInTheInventoryAreCountedAfterABankTrip()
	{
		assertTrue(harness.startNow());
		bankTrip(true);
		// Ten Ice Barrage casts: 4 death, 2 blood and 6 water runes each, from the inventory.
		for (int cast = 0; cast < 10; cast++)
		{
			deathRunes -= 4;
			bloodRunes -= 2;
			waterRunes -= 6;
			updateInventory();
			harness.tick();
		}

		final Map<Integer, Long> supplies = stopAndGetSupplies();
		assertEquals(40L, used(supplies, ItemID.DEATHRUNE));
		assertEquals(20L, used(supplies, ItemID.BLOODRUNE));
		assertEquals(60L, used(supplies, ItemID.WATERRUNE));
	}

	/** One Ice Barrage cast on its own tick: 4 death, 2 blood and 6 water runes from the inventory. */
	private void barrage()
	{
		deathRunes -= 4;
		bloodRunes -= 2;
		waterRunes -= 6;
		updateInventory();
		harness.tick();
	}

	@Test
	public void theOwnersBarrageTaskCountsRunesPotionsAndFoodAroundABankTrip()
	{
		// The owner's task of 2026-11-08, the fixed regression case: Ice Barrage with death, blood and water
		// runes loose in the inventory, potions and food used in the same task, and a bank trip after which the bank's
		// group stayed loaded.
		deathRunes = 400;
		bloodRunes = 200;
		waterRunes = 600;
		updateInventory();
		harness.tick();
		assertTrue(harness.startNow());
		for (int i = 0; i < 5; i++)
		{
			barrage();
			eatAndDrink();
		}
		bankTrip(true);
		for (int i = 0; i < 5; i++)
		{
			barrage();
			eatAndDrink();
		}

		final Map<Integer, Long> supplies = stopAndGetSupplies();
		assertEquals(40L, used(supplies, ItemID.DEATHRUNE));
		assertEquals(20L, used(supplies, ItemID.BLOODRUNE));
		assertEquals(60L, used(supplies, ItemID.WATERRUNE));
		assertEquals(10L, used(supplies, ItemID.SHARK));
		assertEquals("prayer potion doses", 10L, used(supplies, ItemID._4DOSEPRAYERRESTORE));
		assertEquals(5, supplies.size());
	}

	@Test
	public void potionsAndFoodKeepCountingAfterABankTripALogoutAHopAndALostConnection()
	{
		keepsCountingThroughALongClientRun(false);
	}

	@Test
	public void potionsAndFoodKeepCountingThroughALongClientRunWithTheBanksGroupLeftLoaded()
	{
		keepsCountingThroughALongClientRun(true);
	}

	@Test
	public void sharksDepositedInTheMiddleOfABankVisitAreNotCounted()
	{
		assertTrue(harness.startNow());
		eatAndDrink();
		harness.openInterface(InterfaceID.BANKMAIN);
		// Well past the ticks around opening: only the open check keeps this deposit out.
		harness.ticks(SupplyExclusions.INTERFACE_TICKS + 3);
		sharks -= 3;
		updateInventory();
		harness.tick();
		harness.closeInterface(InterfaceID.BANKMAIN);
		harness.ticks(SupplyExclusions.INTERFACE_TICKS + 1);
		eatAndDrink();

		final Map<Integer, Long> supplies = stopAndGetSupplies();
		assertEquals("only the two sharks eaten", 2L, used(supplies, ItemID.SHARK));
	}

	@Test
	public void aLongStretchOfUncountedTicksLeavesRateLimitedLogLines()
	{
		final Logger logger = (Logger) LoggerFactory.getLogger(SlayerLedgerPlugin.class);
		final Level previousLevel = logger.getLevel();
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		logger.setLevel(Level.DEBUG);
		try
		{
			final String line = "Supplies not counted for %d game ticks in a row while the session runs: %s";
			final String bankOpen = "excluded interface " + InterfaceID.BANKMAIN + " is open";
			final int first = SkippedSupplyTicks.FIRST_REPORT_TICKS;
			final int repeat = SkippedSupplyTicks.REPEAT_REPORT_TICKS;

			// No session running: nothing would be counted anyway, so nothing is logged.
			harness.openInterface(InterfaceID.BANKMAIN);
			harness.ticks(first + 1);
			assertEquals(Collections.emptyList(), supplyLines(appender));

			assertTrue(harness.startNow());
			harness.ticks(first - 1);
			assertEquals("a short bank visit leaves no line", Collections.emptyList(), supplyLines(appender));
			harness.tick();
			assertEquals(Collections.singletonList(String.format(line, first, bankOpen)), supplyLines(appender));
			assertEquals("the first line is debug", Level.DEBUG, supplyEvents(appender).get(0).getLevel());
			harness.ticks(repeat - 1);
			assertEquals("rate-limited", 1, supplyLines(appender).size());
			harness.tick();
			assertEquals(String.format(line, first + repeat, bankOpen), supplyLines(appender).get(1));
			assertEquals("a repeat reaches the default client log", Level.INFO,
				supplyEvents(appender).get(1).getLevel());

			// Closed, with its group left loaded: counting goes on, so no further line.
			harness.closeInterface(InterfaceID.BANKMAIN);
			harness.loadInterfaceWithoutOpening(InterfaceID.BANKMAIN);
			harness.ticks(repeat + first);
			assertEquals(2, supplyLines(appender).size());
		}
		finally
		{
			logger.detachAppender(appender);
			logger.setLevel(previousLevel);
		}
	}

	/** The plugin's "Supplies not counted" log lines so far. */
	private static List<String> supplyLines(ListAppender<ILoggingEvent> appender)
	{
		final List<String> lines = new ArrayList<>();
		for (ILoggingEvent event : supplyEvents(appender))
		{
			lines.add(event.getFormattedMessage());
		}
		return lines;
	}

	/** The plugin's "Supplies not counted" log events so far, at any level. */
	private static List<ILoggingEvent> supplyEvents(ListAppender<ILoggingEvent> appender)
	{
		final List<ILoggingEvent> events = new ArrayList<>();
		for (ILoggingEvent event : new ArrayList<>(appender.list))
		{
			if (event.getFormattedMessage().startsWith("Supplies not counted"))
			{
				events.add(event);
			}
		}
		return events;
	}

	/**
	 * One running session through a long client run: supplies used after a bank trip, after a logout and login
	 * (restarted), after a world hop (still running: a hop is not a stop) and after a lost connection (restarted) all
	 * count.
	 */
	private void keepsCountingThroughALongClientRun(boolean groupLeftLoaded)
	{
		assertTrue(harness.startNow());
		eatAndDrink();

		bankTrip(groupLeftLoaded);
		eatAndDrink();

		harness.logout();
		harness.loginAndSettle(ACCOUNT);
		assertEquals(SessionTracker.State.LOGGED_OUT, harness.sessionState());
		assertTrue("restarted after the login", harness.startNow());
		eatAndDrink();

		harness.hop();
		harness.ticks(PluginHarness.SETTLE_TICKS);
		assertEquals("a hop is not a stop", SessionTracker.State.RUNNING, harness.sessionState());
		eatAndDrink();

		harness.setGameState(GameState.CONNECTION_LOST);
		harness.setGameState(GameState.LOGGED_IN);
		harness.ticks(PluginHarness.SETTLE_TICKS);
		assertEquals("a lost connection stops it like a logout", SessionTracker.State.LOGGED_OUT,
			harness.sessionState());
		assertTrue("restarted after the reconnect", harness.startNow());
		eatAndDrink();

		final Map<Integer, Long> supplies = stopAndGetSupplies();
		assertEquals("one shark in each stretch", 5L, used(supplies, ItemID.SHARK));
		assertEquals("one dose in each stretch", 5L, used(supplies, ItemID._4DOSEPRAYERRESTORE));
	}
}
