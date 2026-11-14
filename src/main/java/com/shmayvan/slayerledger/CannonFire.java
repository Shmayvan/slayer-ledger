package com.shmayvan.slayerledger;

/**
 * When the player's cannon last fired (V2 plan 4.3): a task kill is a cannon kill when the cannon was placed and
 * fired within the last {@link #FIRE_WINDOW_TICKS} ticks. The plugin reads, once per game tick, whether the cannon is
 * placed ({@code VarPlayerID.DROPCANNON} is 4, as core CannonPlugin reads it, 1.13.4), whether it has decayed
 * ({@code VarbitID.MCANNON_DECAYED} is 1, which core CannonPlugin takes as no longer active) and its loaded
 * cannonballs ({@code VarPlayerID.ROCKTHROWER}, as the supplies diff reads them). A drop of the loaded cannonballs
 * while it is placed and not decayed, from one reading to the next, is a shot. Picking the cannon up empties it, but
 * it is no longer placed then, so that is no shot; nor is its decay emptying it; loading it raises the count. Pure;
 * client thread only, not synchronized.
 */
final class CannonFire
{
	/** Ticks after its last shot during which a task kill is a cannon kill. */
	static final int FIRE_WINDOW_TICKS = 10;
	/** The {@code DROPCANNON} value of a placed cannon. */
	static final int PLACED = 4;

	private static final int NO_TICK = Integer.MIN_VALUE;
	private static final int NO_READING = -1;

	/** Cannonballs loaded at the previous reading while placed and not decayed, or {@link #NO_READING}. */
	private int lastAmmo = NO_READING;
	/** The tick of the latest shot seen, or {@link #NO_TICK}. */
	private int lastShotTick = NO_TICK;

	/**
	 * One tick's reading. A decayed cannon is read as not placed: its readings are no shots, and the first reading once
	 * it is repaired is a new baseline.
	 *
	 * @param placed  whether the cannon is placed now
	 * @param decayed whether it has decayed
	 * @param ammo    its loaded cannonballs now
	 */
	void update(int tick, boolean placed, boolean decayed, int ammo)
	{
		final boolean active = placed && !decayed;
		if (active && lastAmmo != NO_READING && ammo < lastAmmo)
		{
			lastShotTick = tick;
		}
		lastAmmo = active ? Math.max(0, ammo) : NO_READING;
	}

	/** Whether the cannon fired from {@link #FIRE_WINDOW_TICKS} ticks before {@code tick} to {@code tick}. */
	boolean firedWithin(int tick)
	{
		return lastShotTick != NO_TICK && lastShotTick <= tick && tick - lastShotTick <= FIRE_WINDOW_TICKS;
	}

	/** Forgets the readings and shots (a login, hop or plugin start, or while no task is assigned). */
	void reset()
	{
		lastAmmo = NO_READING;
		lastShotTick = NO_TICK;
	}
}
