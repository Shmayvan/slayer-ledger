package com.shmayvan.slayerledger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * The player's attacks of the last few game ticks, each with its style ({@link AttackStyles}), its target NPC and the
 * clock time its tick began, so each of the player's own hitsplats can be put down to the attack that landed it (V2
 * plan 4.3). Pure; client thread only, not synchronized. Bounded to the attacks of the last
 * {@link #RANGED_LATEST} + 1 ticks.
 *
 * <p><b>When an attack's hits land.</b> An attack is noted on the GameTick it starts on, tick A. Every hit on an NPC
 * lands one tick later than the attack's own hit delay, because the game processes NPCs before players (OSRS Wiki,
 * "Hit delay": processing order delay; RuneLite 1.13.4's {@code SpecialWeapon.getHitDelay}: "one server cycle of
 * additional delay"). Melee has no hit delay of its own, so:</p>
 * <ul>
 * <li>melee lands on A + 1 ({@link #MELEE}); a multi-hit special attack's last hits (the dragon claws') on A + 2
 * ({@link #MELEE_LATE});</li>
 * <li>bows, crossbows, thrown weapons, chinchompas and ballistae land on A + 2 to A + 4, spells and powered staves on
 * A + 2 to A + 5, and Tumeken's shadow at range and a Barrage on a large NPC up to A + 6: ranged and magic attacks land
 * on A + {@link #RANGED_EARLIEST} to A + {@link #RANGED_LATEST}.</li>
 * </ul>
 *
 * <p><b>Which attack a hitsplat on an NPC is</b> ({@link #forHit}). Only an attack on that NPC (its target) can be it.
 * For a hitsplat on tick H, in this order:</p>
 * <ol>
 * <li>a melee attack on it on H - 1;</li>
 * <li>else a melee attack on it on H - 2 (a multi-hit special's last hits);</li>
 * <li>else the latest ranged or magic attack on it from H - 6 to H - 2;</li>
 * <li>else none: a hitsplat of the player's with no attack of theirs on that NPC around it, such as the cannon's.</li>
 * </ol>
 * <p>Melee is tried first. By then a ranged or magic attack's hits have landed: the player's next attack waits out the
 * weapon's attack speed (2 ticks for a blowpipe, 3 for a shortbow, 4 for a trident, 5 for a Barrage), and only a hit
 * from its longest range lands as late as one tick after that, the tick a melee attack's hit would; from that far away
 * the player can't reach melee range in time. So a dart's hit on A + 2, after a swap to a whip that attacks on A + 2,
 * is the dart's, and a whip's hit on A + 5 after a trident's cast on A is the whip's.</p>
 *
 * <p><b>Splash</b> ({@link #forSplashHit}). A Burst or Barrage, and a chinchompa, also hits the NPCs around its target.
 * A hitsplat that no attack on its own NPC landed is such a splash when, on its tick, the same attack's hit on its
 * target landed too (all of one cast's hits land together; a chinchompa's other targets are hit only when its target is,
 * OSRS Wiki, "Black chinchompa"). So the cannon's hits on other NPCs while the player barrages give no style, apart
 * from one that lands on the very tick of a Barrage's hits.</p>
 *
 * <p>The style is the attack's, decided when it started, so a ranged attack's hitsplat that lands after the player
 * switched to a melee weapon is still Ranged.</p>
 */
final class PlayerAttacks
{
	/** Ticks after a melee attack its hits land. */
	static final int MELEE = 1;
	/** Ticks after a multi-hit melee special attack its last hits land. */
	static final int MELEE_LATE = 2;
	/** The fewest ticks after a ranged or magic attack its hits land. */
	static final int RANGED_EARLIEST = 2;
	/** The most ticks after a ranged or magic attack its hits land. */
	static final int RANGED_LATEST = 6;

	/** No XP reading ({@link #xpRose}). */
	static final long NO_XP = -1;

	/** One attack: the tick it started on, its style and target, and when that tick began (clock millis). */
	static final class Attack
	{
		private final int tick;
		private final SetupStyle style;
		private final long at;
		private final long target;
		private final boolean splash;
		/** For a splash attack: bit {@code d} is set once its hit on its target landed {@code d} ticks after it. */
		private int landed;

		Attack(int tick, SetupStyle style, long at, long target, boolean splash)
		{
			this.tick = tick;
			this.style = style;
			this.at = at;
			this.target = target;
			this.splash = splash;
		}

		int getTick()
		{
			return tick;
		}

		SetupStyle getStyle()
		{
			return style;
		}

		/** When the attack's tick began (clock millis): before that tick's XP is read. */
		long getAt()
		{
			return at;
		}

		private boolean isMelee()
		{
			return style == SetupStyle.MELEE;
		}
	}

	/** Recent attacks, oldest first; at most one per tick. */
	private final Deque<Attack> attacks = new ArrayDeque<>();
	/** The XP reading of the previous tick ({@link #xpRose}), or {@link #NO_XP}. */
	private long lastXp = NO_XP;

	/**
	 * The player's attack on the NPC {@code target} ({@link DamagedNpcs#key}) started on {@code tick}, whose start is
	 * {@code at} (clock millis), with {@code style}; {@code splash} when it also hits the NPCs around its target (a Burst
	 * or Barrage, a chinchompa). Ignored without a style, or for a tick not after the latest attack's.
	 */
	void add(int tick, SetupStyle style, long at, long target, boolean splash)
	{
		if (style == null || (!attacks.isEmpty() && tick <= attacks.peekLast().tick))
		{
			return;
		}
		attacks.addLast(new Attack(tick, style, at, target, splash));
		while (attacks.peekFirst().tick < tick - RANGED_LATEST - 1)
		{
			attacks.removeFirst();
		}
	}

	/**
	 * The attack on the NPC {@code npc} that a hitsplat of the player's on it on {@code hitTick} is put down to (see the
	 * class comment), or {@code null}. A splash attack it finds notes that its hits land on {@code hitTick}, for
	 * {@link #forSplashHit}: so call this for all of a tick's hitsplats first.
	 */
	Attack forHit(int hitTick, long npc)
	{
		Attack melee = null;
		Attack meleeLate = null;
		Attack ranged = null;
		final Iterator<Attack> it = attacks.descendingIterator();
		while (it.hasNext())
		{
			final Attack attack = it.next();
			final int delay = hitTick - attack.tick;
			if (delay > RANGED_LATEST)
			{
				break;
			}
			if (attack.target != npc || delay < MELEE)
			{
				continue;
			}
			if (attack.isMelee())
			{
				if (delay == MELEE)
				{
					melee = attack;
				}
				else if (delay == MELEE_LATE)
				{
					meleeLate = attack;
				}
			}
			else if (ranged == null && delay >= RANGED_EARLIEST)
			{
				ranged = attack;
			}
		}
		final Attack found = melee != null ? melee : meleeLate != null ? meleeLate : ranged;
		if (found != null && found.splash)
		{
			found.landed |= 1 << (hitTick - found.tick);
		}
		return found;
	}

	/**
	 * The splash attack (a Burst or Barrage, a chinchompa) that a hitsplat of the player's on {@code hitTick} is put
	 * down to when no attack on its own NPC is ({@link #forHit}): the latest one from {@link #RANGED_EARLIEST} to
	 * {@link #RANGED_LATEST} ticks before whose hit on its target landed on {@code hitTick} too, or {@code null}.
	 */
	Attack forSplashHit(int hitTick)
	{
		final Iterator<Attack> it = attacks.descendingIterator();
		while (it.hasNext())
		{
			final Attack attack = it.next();
			final int delay = hitTick - attack.tick;
			if (delay > RANGED_LATEST)
			{
				break;
			}
			if (attack.splash && delay >= RANGED_EARLIEST && (attack.landed & (1 << delay)) != 0)
			{
				return attack;
			}
		}
		return null;
	}

	/**
	 * This tick's total XP in the skills that confirm an attack ({@link AttackStyles#ATTACK_XP_SKILLS}), or
	 * {@link #NO_XP} when it can't be read: whether it rose since the previous tick's reading. An animation is an
	 * attack only if it did: every attack that hits gains XP on its own tick, before its hitsplat, and a block
	 * gains none. Called once a tick, so the next tick compares with this reading.
	 */
	boolean xpRose(long xp)
	{
		final boolean rose = lastXp != NO_XP && xp != NO_XP && xp > lastXp;
		lastXp = xp;
		return rose;
	}

	/** Forgets the XP reading, keeping the attacks (no attack can start while the XP isn't read). */
	void forgetXp()
	{
		lastXp = NO_XP;
	}

	/** Forgets every attack and the XP reading (a login, hop or plugin start, or while no task is assigned). */
	void clear()
	{
		attacks.clear();
		lastXp = NO_XP;
	}
}
