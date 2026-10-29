package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.PluginHarness.item;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.Collections;
import java.util.Map;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Supplies counting as wired in the plugin ({@link PluginHarness}): only while RUNNING, and not while a bank-like
 * interface is open (tracked through WidgetLoaded / WidgetClosed and the root widget), after a "Drop" click on the
 * item, or around the local player's death. {@link SupplyExclusionsTest}, {@link SupplyTrackerTest} and
 * {@link SuppliesSessionTest} cover the rules themselves.
 */
public class HarnessSupplyExclusionsTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.SHARK, "Shark", 800, 0, "Eat", null, null, null, "Drop");
		harness.defineItem(ItemID.LOBSTER, "Lobster", 150, 0, "Eat", null, null, null, "Drop");
		harness.setInventory(item(ItemID.SHARK, 10), item(ItemID.LOBSTER, 5));
		harness.startUp();
		harness.setTask(42, "Abyssal demons", 10, 10);
		harness.loginAndSettle(ACCOUNT);
	}

	private void inventory(int sharks, int lobsters)
	{
		harness.setInventory(item(ItemID.SHARK, sharks), item(ItemID.LOBSTER, lobsters));
	}

	/** Stops the session and returns the saved record's supplies. */
	private Map<Integer, Long> stopAndGetSupplies()
	{
		harness.stop();
		Map<Integer, Long> supplies = harness.onlyRecord(ACCOUNT).getSupplies();
		return supplies == null ? Collections.emptyMap() : supplies;
	}

	private static long used(Map<Integer, Long> supplies, int itemId)
	{
		return supplies.getOrDefault(itemId, 0L);
	}

	@Test
	public void foodEatenWhileRunningIsCountedAndPriced()
	{
		assertTrue(harness.play());
		inventory(8, 5);
		harness.tick();
		inventory(8, 4);
		harness.tick();

		harness.stop();
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(2L, used(record.getSupplies(), ItemID.SHARK));
		assertEquals(1L, used(record.getSupplies(), ItemID.LOBSTER));
		assertEquals(Long.valueOf(2 * 800 + 150), record.getSuppliesGeValue());
	}

	@Test
	public void suppliesCountOnlyWhileRunning()
	{
		inventory(9, 5);
		harness.tick();
		assertTrue(harness.play());
		inventory(8, 5);
		harness.tick();
		harness.pause();
		inventory(7, 5);
		harness.tick();
		// Eaten between the last tick and Play: Play takes a fresh baseline, so this is not counted either.
		inventory(6, 5);
		assertTrue(harness.play());
		inventory(5, 5);
		harness.tick();

		assertEquals(2L, used(stopAndGetSupplies(), ItemID.SHARK));
	}

	@Test
	public void nothingIsCountedWhileTheBankIsOpenOrJustAfter()
	{
		assertTrue(harness.play());
		harness.openInterface(InterfaceID.BANKMAIN);
		inventory(3, 0);
		harness.tick();
		inventory(20, 0);
		harness.tick();
		harness.closeInterface(InterfaceID.BANKMAIN);
		// Items the bank moved can still arrive for a couple of ticks after it closes.
		inventory(18, 0);
		harness.ticks(SupplyExclusions.INTERFACE_TICKS + 1);

		inventory(17, 0);
		harness.tick();
		assertEquals(1L, used(stopAndGetSupplies(), ItemID.SHARK));
	}

	@Test
	public void bankOpenedAndClosedBetweenTicksStillExcludesTheTick()
	{
		assertTrue(harness.play());
		harness.openInterface(InterfaceID.BANKMAIN);
		harness.closeInterface(InterfaceID.BANKMAIN);
		inventory(4, 5);
		harness.tick();

		Map<Integer, Long> supplies = stopAndGetSupplies();
		assertEquals(0L, used(supplies, ItemID.SHARK));
	}

	@Test
	public void otherInterfacesDoNotExclude()
	{
		assertTrue(harness.play());
		harness.openInterface(InterfaceID.INVENTORY);
		inventory(9, 5);
		harness.tick();
		assertEquals(1L, used(stopAndGetSupplies(), ItemID.SHARK));
	}

	@Test
	public void droppedItemIsNotASupplyButOthersOnTheSameTickAre()
	{
		assertTrue(harness.play());
		harness.clickMenu("Drop", ItemID.SHARK);
		inventory(9, 4);
		harness.tick();
		harness.ticks(SupplyExclusions.DROP_WINDOW_TICKS);
		// Outside the drop window, eating a shark counts again.
		inventory(8, 4);
		harness.tick();

		Map<Integer, Long> supplies = stopAndGetSupplies();
		assertEquals(1L, used(supplies, ItemID.SHARK));
		assertEquals(1L, used(supplies, ItemID.LOBSTER));
	}

	@Test
	public void otherMenuOptionsDoNotExclude()
	{
		assertTrue(harness.play());
		harness.clickMenu("Eat", ItemID.SHARK);
		inventory(9, 5);
		harness.tick();
		assertEquals(1L, used(stopAndGetSupplies(), ItemID.SHARK));
	}

	@Test
	public void nothingIsCountedAroundTheLocalPlayersDeath()
	{
		assertTrue(harness.play());
		harness.localPlayerDies();
		inventory(0, 0);
		harness.ticks(SupplyExclusions.DEATH_TICKS + 1);
		assertEquals(0L, used(stopAndGetSupplies(), ItemID.SHARK));
	}

	@Test
	public void suppliesCountAgainAfterTheDeathWindow()
	{
		assertTrue(harness.play());
		harness.localPlayerDies();
		inventory(0, 0);
		harness.ticks(SupplyExclusions.DEATH_TICKS + 1);
		inventory(5, 5);
		harness.tick();
		inventory(4, 5);
		harness.tick();
		assertEquals(1L, used(stopAndGetSupplies(), ItemID.SHARK));
	}

	@Test
	public void anotherActorsDeathDoesNotExclude()
	{
		assertTrue(harness.play());
		harness.otherActorDies();
		inventory(9, 5);
		harness.tick();
		assertEquals(1L, used(stopAndGetSupplies(), ItemID.SHARK));
	}
}
