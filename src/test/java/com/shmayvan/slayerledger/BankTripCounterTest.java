package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.InterfaceID;
import org.junit.Test;

/**
 * Which bank openings are new bank trips; {@link SessionTrackerCountersTest} covers the count on the session.
 */
public class BankTripCounterTest
{
	private static final String SESSION = "session-1";
	private static final int WINDOW = BankTripCounter.SAME_TRIP_TICKS;

	private final BankTripCounter counter = new BankTripCounter();

	@Test
	public void bankInterfacesAreTheBankDepositBoxAndGroupStorage()
	{
		assertTrue(BankTripCounter.isBankInterface(InterfaceID.BANKMAIN));
		assertTrue(BankTripCounter.isBankInterface(InterfaceID.BANK_DEPOSITBOX));
		assertTrue(BankTripCounter.isBankInterface(InterfaceID.SHARED_BANK));
		assertFalse(BankTripCounter.isBankInterface(InterfaceID.SEED_VAULT));
		assertFalse(BankTripCounter.isBankInterface(InterfaceID.GE_OFFERS));
		assertFalse(BankTripCounter.isBankInterface(149));
		assertEquals(3, BankTripCounter.bankInterfaces().length);
		// The same interfaces supply counting already leaves out.
		for (int group : BankTripCounter.bankInterfaces())
		{
			assertTrue(SupplyExclusions.isExcludedInterface(group));
		}
	}

	@Test
	public void windowIsThirtySeconds()
	{
		// 50 game ticks of 0.6 s.
		assertEquals(30_000, BankTripCounter.SAME_TRIP_TICKS * 600);
	}

	@Test
	public void firstOpeningWhileRunningIsATrip()
	{
		assertTrue(counter.bankOpened(100, true, SESSION));
	}

	@Test
	public void reopeningWithinTheWindowOfClosingIsTheSameTrip()
	{
		assertTrue(counter.bankOpened(100, true, SESSION));
		// A long bank visit: the window runs from closing, not opening.
		counter.bankClosed(400);
		assertFalse(counter.bankOpened(400 + WINDOW, true, SESSION));
		counter.bankClosed(410 + WINDOW);
		assertFalse("deposit box after the bank", counter.bankOpened(410 + 2 * WINDOW, true, SESSION));
	}

	@Test
	public void openingAfterTheWindowIsANewTrip()
	{
		assertTrue(counter.bankOpened(100, true, SESSION));
		counter.bankClosed(110);
		assertTrue(counter.bankOpened(110 + WINDOW + 1, true, SESSION));
		counter.bankClosed(200 + WINDOW);
		assertTrue(counter.bankOpened(201 + 2 * WINDOW, true, SESSION));
	}

	@Test
	public void openingWithoutCloseUsesTheOpeningTick()
	{
		assertTrue(counter.bankOpened(100, true, SESSION));
		assertFalse(counter.bankOpened(100 + WINDOW, true, SESSION));
		assertTrue(counter.bankOpened(101 + 2 * WINDOW, true, SESSION));
	}

	@Test
	public void bankingWhilePausedOrIdleIsNeverATrip()
	{
		assertFalse("paused", counter.bankOpened(100, false, SESSION));
		assertFalse("no session", counter.bankOpened(1_000, false, null));
		assertFalse("running without a session id", counter.bankOpened(2_000, true, null));
	}

	@Test
	public void reopeningRightAfterResumingIsTheSameTripAsThePausedVisit()
	{
		assertFalse(counter.bankOpened(100, false, SESSION));
		counter.bankClosed(105);
		assertFalse(counter.bankOpened(110, true, SESSION));
		counter.bankClosed(115);
		assertTrue(counter.bankOpened(116 + WINDOW, true, SESSION));
	}

	@Test
	public void aNewSessionsFirstOpeningAlwaysCounts()
	{
		assertFalse("idle", counter.bankOpened(100, false, null));
		counter.bankClosed(101);
		assertTrue(counter.bankOpened(102, true, SESSION));
		counter.bankClosed(103);
		assertTrue("the next session", counter.bankOpened(104, true, "session-2"));
	}

	@Test
	public void closingWithoutAnOpeningDoesNotStartAWindow()
	{
		counter.bankClosed(100);
		assertTrue(counter.bankOpened(101, true, SESSION));
	}

	@Test
	public void aTickCountThatWentBackIsANewTrip()
	{
		assertTrue(counter.bankOpened(5_000, true, SESSION));
		assertTrue(counter.bankOpened(10, true, SESSION));
	}

	@Test
	public void resetForgetsTheLastVisit()
	{
		assertTrue(counter.bankOpened(100, true, SESSION));
		counter.reset();
		assertTrue(counter.bankOpened(101, true, SESSION));
	}
}
