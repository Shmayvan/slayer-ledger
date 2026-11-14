package com.shmayvan.slayerledger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * When the player's cannon fired ({@link CannonFire}): a drop of its loaded cannonballs while it is placed and not
 * decayed, and a task kill within {@link CannonFire#FIRE_WINDOW_TICKS} ticks of the last one is a cannon kill.
 */
public class CannonFireTest
{
	private final CannonFire cannon = new CannonFire();

	@Test
	public void aDropWhilePlacedIsAShotThatCountsForTenTicks()
	{
		cannon.update(100, true, false, 30);
		assertFalse("loaded, not fired", cannon.firedWithin(100));
		cannon.update(101, true, false, 29);
		assertTrue(cannon.firedWithin(101));
		assertTrue(cannon.firedWithin(101 + CannonFire.FIRE_WINDOW_TICKS));
		assertFalse(cannon.firedWithin(102 + CannonFire.FIRE_WINDOW_TICKS));
		assertFalse("not before the shot", cannon.firedWithin(100));
	}

	@Test
	public void loadingAndPickingUpAreNoShots()
	{
		cannon.update(100, true, false, 0);
		cannon.update(101, true, false, 30);
		assertFalse("loaded", cannon.firedWithin(101));
		cannon.update(102, false, false, 0);
		assertFalse("picked up: no longer placed", cannon.firedWithin(102));
		cannon.update(103, true, false, 25);
		assertFalse("placed again: the first reading is a baseline", cannon.firedWithin(103));
		cannon.update(104, true, false, 24);
		assertTrue(cannon.firedWithin(104));
	}

	@Test
	public void decayEmptyingTheCannonIsNoShot()
	{
		cannon.update(100, true, false, 30);
		// Decayed: core CannonPlugin takes it as no longer active; its cannonballs read 0.
		cannon.update(101, true, true, 0);
		assertFalse(cannon.firedWithin(101));
		cannon.update(102, true, true, 0);
		assertFalse(cannon.firedWithin(102));
		// Repaired and loaded: the first reading is a baseline, then shots count again.
		cannon.update(103, true, false, 30);
		assertFalse(cannon.firedWithin(103));
		cannon.update(104, true, false, 29);
		assertTrue(cannon.firedWithin(104));
	}

	@Test
	public void resetForgetsTheShots()
	{
		cannon.update(100, true, false, 30);
		cannon.update(101, true, false, 29);
		cannon.reset();
		assertFalse(cannon.firedWithin(101));
		cannon.update(102, true, false, 28);
		assertFalse("the first reading after a reset is a baseline", cannon.firedWithin(102));
	}
}
