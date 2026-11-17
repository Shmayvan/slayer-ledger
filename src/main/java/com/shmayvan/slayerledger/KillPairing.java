package com.shmayvan.slayerledger;

import java.util.*;
import lombok.*;

/**
 * What the plugin knows about a task kill (a drop of the Slayer count, or a bracelet of slaughter's activation) from
 * the NPCs the player damaged that died around it ({@link DamagedNpcs#pairing}, V2 plan 4.1): the earliest engagement
 * start among them, which is where a (re)start of the session is backdated to, and their names, which become the
 * session's known task NPCs. Also whether a kill may start a new session now, and when the kill's game tick began
 * ({@link #getTickStart()}): the plugin reads that tick's XP just before it sees the kill, so a (re)start counts it.
 * Pure and immutable.
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
final class KillPairing
{
	/** {@link #getEarliestStart()} when no damaged NPC died around the kill (for example a cannon-only kill). */
	static final long NO_START = Long.MAX_VALUE;

	/** Nothing paired; a kill may start a session; its tick's start unknown. */
	static final KillPairing NONE = new KillPairing(NO_START, Collections.emptySet(), true, NO_START);

	/** The earliest engagement start of the paired NPCs (clock millis), or {@link #NO_START}. */
	private final long earliestStart;
	/** The paired NPCs' names as the game gives them (unmodifiable, possibly empty). */
	private final Set<String> names;
	/** Whether a kill may start a new session now (see {@link #withStartBlocked()}). */
	private final boolean startAllowed;
	/** When the kill's game tick began (see {@link #withTickStart}), or {@link #NO_START} when not known. */
	private final long tickStart;

	/**
	 * A pairing of NPCs whose earliest engagement started at {@code earliestStart} ({@link #NO_START} for none), with
	 * the given NPC names ({@code null} entries are left out).
	 */
	static KillPairing of(long earliestStart, Collection<String> names)
	{
		final Set<String> kept = new LinkedHashSet<>();
		if (names != null)
		{
			for (String name : names)
			{
				if (name != null)
				{
					kept.add(name);
				}
			}
		}
		return new KillPairing(earliestStart, Collections.unmodifiableSet(kept), true, NO_START);
	}

	/**
	 * This pairing, except that a kill may not start a new session now: the plugin is still checking for a session to
	 * restore, which a new session would block. A session that exists still restarts.
	 */
	KillPairing withStartBlocked()
	{
		return startAllowed ? new KillPairing(earliestStart, names, false, tickStart) : this;
	}

	/**
	 * This pairing, for a kill seen on a game tick that began at {@code tickStart} (clock millis, read before that
	 * tick's supplies and XP): a (re)start at the kill counts what the buffer holds from that tick on, the kill's own XP
	 * included, even when nothing is paired (V2 plan 4.1).
	 */
	KillPairing withTickStart(long tickStart)
	{
		return new KillPairing(earliestStart, names, startAllowed, tickStart);
	}

	/** Whether any paired NPC's engagement start is known. */
	boolean hasStart()
	{
		return earliestStart != NO_START;
	}
}
