package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Collections;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * Which ticks and dropped (or stored) items the plugin leaves out of supply counting.
 */
public class SupplyExclusionsTest
{
	private static final int SHARK = 385;
	private static final int ARROW = 890;
	private static final int RUNE_POUCH = 12791;
	/** Not an excluded interface. */
	private static final int OTHER_INTERFACE = 149;

	private final SupplyExclusions exclusions = new SupplyExclusions();

	@Test
	public void nothingIsExcludedByDefault()
	{
		exclusions.beginTick(0, false);
		assertFalse(exclusions.isTickExcluded(0));
		assertFalse(exclusions.isTickExcluded(1_000));
		assertTrue(exclusions.droppedItems().isEmpty());
	}

	@Test
	public void excludedInterfacesAreRecognised()
	{
		for (int group : SupplyExclusions.excludedInterfaces())
		{
			assertTrue(SupplyExclusions.isExcludedInterface(group));
		}
		assertTrue(SupplyExclusions.isExcludedInterface(InterfaceID.BANKMAIN));
		assertTrue(SupplyExclusions.isExcludedInterface(InterfaceID.DEATH_COFFER));
		assertFalse(SupplyExclusions.isExcludedInterface(OTHER_INTERFACE));
		assertEquals(11, SupplyExclusions.excludedInterfaces().length);
	}

	@Test
	public void openInterfaceExcludesItsTickAndTwoMore()
	{
		exclusions.beginTick(100, true);
		assertTrue("+0", exclusions.isTickExcluded(100));
		exclusions.beginTick(101, false);
		assertTrue("+1", exclusions.isTickExcluded(101));
		exclusions.beginTick(102, false);
		assertTrue("+2", exclusions.isTickExcluded(102));
		exclusions.beginTick(103, false);
		assertFalse("+3", exclusions.isTickExcluded(103));
	}

	@Test
	public void interfaceStillOpenKeepsExtendingTheWindow()
	{
		for (int tick = 100; tick <= 110; tick++)
		{
			exclusions.beginTick(tick, true);
			assertTrue(exclusions.isTickExcluded(tick));
		}
		exclusions.beginTick(112, false);
		assertTrue("2 ticks after it was last open", exclusions.isTickExcluded(112));
		exclusions.beginTick(113, false);
		assertFalse(exclusions.isTickExcluded(113));
	}

	@Test
	public void widgetLoadedAndClosedMarkTheTick()
	{
		// Opened and closed between two ticks: never seen open by beginTick.
		exclusions.interfaceChanged(InterfaceID.BANKMAIN, 200);
		exclusions.beginTick(200, false);
		assertTrue(exclusions.isTickExcluded(200));
		assertTrue(exclusions.isTickExcluded(202));
		assertFalse(exclusions.isTickExcluded(203));

		exclusions.interfaceChanged(InterfaceID.SHOPMAIN, 300);
		assertTrue(exclusions.isTickExcluded(302));
		assertFalse(exclusions.isTickExcluded(303));
	}

	@Test
	public void otherInterfacesDoNotExclude()
	{
		exclusions.interfaceChanged(OTHER_INTERFACE, 200);
		assertFalse(exclusions.isTickExcluded(200));
	}

	@Test
	public void dropWindowCoversTwoTicksThenIsPruned()
	{
		exclusions.dropClicked(SHARK, false, 50);
		exclusions.beginTick(50, false);
		assertEquals("+0", Collections.singleton(SHARK), exclusions.droppedItems());
		assertFalse("a drop alone does not exclude the tick", exclusions.isTickExcluded(50));
		exclusions.beginTick(52, false);
		assertEquals("+2", Collections.singleton(SHARK), exclusions.droppedItems());
		exclusions.beginTick(53, false);
		assertTrue("+3: pruned", exclusions.droppedItems().isEmpty());
	}

	@Test
	public void dropsArePrunedPerItem()
	{
		exclusions.dropClicked(SHARK, false, 50);
		exclusions.dropClicked(ARROW, false, 52);
		exclusions.beginTick(53, false);
		assertEquals(Collections.singleton(ARROW), exclusions.droppedItems());
		// A repeated drop moves the item's window forward.
		exclusions.dropClicked(ARROW, false, 54);
		exclusions.beginTick(56, false);
		assertEquals(Collections.singleton(ARROW), exclusions.droppedItems());
		exclusions.beginTick(57, false);
		assertTrue(exclusions.droppedItems().isEmpty());
	}

	@Test
	public void dropFromALaterTickIsPruned()
	{
		// The tick count went backwards (should it ever restart; RuneLite 1.13.4's keeps counting).
		exclusions.dropClicked(SHARK, false, 500);
		exclusions.beginTick(10, false);
		assertTrue(exclusions.droppedItems().isEmpty());
	}

	@Test
	public void droppedItemsIsACopy()
	{
		exclusions.dropClicked(SHARK, false, 50);
		exclusions.beginTick(50, false);
		exclusions.droppedItems().clear();
		assertEquals(Collections.singleton(SHARK), exclusions.droppedItems());
	}

	@Test
	public void aScrollStoredInTheMasterScrollBookIsLeftOutLikeADrop()
	{
		// The plugin passes the target item twice (entry and widget) and the selected item.
		exclusions.useClicked(new int[]{ItemID.BOOKOFSCROLLS_EMPTY, ItemID.BOOKOFSCROLLS_EMPTY,
			ItemID.TELEPORTSCROLL_ZULANDRA}, 80);
		exclusions.beginTick(80, false);
		assertEquals(Collections.singleton(ItemID.TELEPORTSCROLL_ZULANDRA), exclusions.droppedItems());
		assertFalse("the tick itself still counts", exclusions.isTickExcluded(80));
		exclusions.beginTick(83, false);
		assertTrue("+3: pruned", exclusions.droppedItems().isEmpty());

		// The book used on a scroll, a filled book.
		exclusions.useClicked(new int[]{ItemID.TELEPORTSCROLL_CERBERUS, ItemID.TELEPORTSCROLL_CERBERUS,
			ItemID.BOOKOFSCROLLS_CHARGED}, 90);
		exclusions.beginTick(90, false);
		assertEquals(Collections.singleton(ItemID.TELEPORTSCROLL_CERBERUS), exclusions.droppedItems());
	}

	@Test
	public void otherItemOnItemUsesLeaveNothingOut()
	{
		exclusions.useClicked(new int[]{SHARK, SHARK, ItemID.BOOKOFSCROLLS_CHARGED}, 80);
		exclusions.useClicked(new int[]{ItemID.TELEPORTSCROLL_ZULANDRA, ItemID.TELEPORTSCROLL_ZULANDRA, SHARK}, 80);
		exclusions.useClicked(new int[]{ItemID.POH_TABLET_VARROCKTELEPORT, -1, ItemID.BOOKOFSCROLLS_CHARGED}, 80);
		exclusions.useClicked(null, 80);
		exclusions.beginTick(80, false);
		assertTrue(exclusions.droppedItems().isEmpty());
	}

	@Test
	public void runePouchDropExcludesTheWholeTick()
	{
		exclusions.dropClicked(RUNE_POUCH, true, 70);
		exclusions.beginTick(70, false);
		assertTrue(exclusions.isTickExcluded(70));
		assertTrue(exclusions.droppedItems().contains(RUNE_POUCH));
		assertTrue("+2", exclusions.isTickExcluded(72));
		assertFalse("+3", exclusions.isTickExcluded(73));
	}

	@Test
	public void deathExcludesTenTicks()
	{
		exclusions.playerDied(1_000);
		assertTrue("+0", exclusions.isTickExcluded(1_000));
		assertTrue("+3", exclusions.isTickExcluded(1_003));
		assertTrue("+10", exclusions.isTickExcluded(1_010));
		assertFalse("+11", exclusions.isTickExcluded(1_011));
	}

	@Test
	public void resetForgetsEverything()
	{
		exclusions.beginTick(10, true);
		exclusions.dropClicked(SHARK, false, 10);
		exclusions.playerDied(10);
		exclusions.reset();
		assertFalse(exclusions.isTickExcluded(10));
		assertTrue(exclusions.droppedItems().isEmpty());
		// After a reset the tick count may restart at a low value; nothing is excluded.
		assertFalse(exclusions.isTickExcluded(0));
	}

	@Test
	public void anInterfaceWindowFromALaterTickExcludesNothing()
	{
		// a window recorded before the tick count restarted must not cover every tick until it catches up.
		exclusions.interfaceChanged(InterfaceID.BANKMAIN, 5_000);
		exclusions.beginTick(10, false);
		assertFalse(exclusions.isTickExcluded(10));
		assertFalse(exclusions.isTickExcluded(4_999));
		assertTrue("the window itself still applies", exclusions.isTickExcluded(5_000));
		assertNull(exclusions.exclusionReason(10));
	}

	@Test
	public void aRunePouchDropOrDeathFromALaterTickExcludesNothing()
	{
		exclusions.dropClicked(RUNE_POUCH, true, 5_000);
		exclusions.playerDied(6_000);
		exclusions.beginTick(10, false);
		assertFalse(exclusions.isTickExcluded(10));
		assertFalse(exclusions.isTickExcluded(5_999));
		assertTrue(exclusions.isTickExcluded(6_005));
	}

	@Test
	public void windowsFarApartDoNotOverflow()
	{
		exclusions.playerDied(Integer.MIN_VALUE / 2 + 1);
		exclusions.interfaceChanged(InterfaceID.BANKMAIN, Integer.MIN_VALUE / 2 + 1);
		assertFalse(exclusions.isTickExcluded(Integer.MAX_VALUE));
	}

	@Test
	public void theReasonSaysWhyATickIsExcluded()
	{
		exclusions.beginTick(100, false);
		assertNull("not excluded", exclusions.exclusionReason(100));

		exclusions.beginTick(101, true);
		assertEquals("an excluded interface was open, opened or closed (or a rune pouch dropped) on tick 101",
			exclusions.exclusionReason(102));

		exclusions.playerDied(200);
		assertEquals("the local player died on tick 200", exclusions.exclusionReason(205));
		assertNull(exclusions.exclusionReason(211));
	}
}
