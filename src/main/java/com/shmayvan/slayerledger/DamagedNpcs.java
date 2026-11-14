package com.shmayvan.slayerledger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The NPCs the player has damaged (a hitsplat of theirs, {@code Hitsplat.isMine()}) and that haven't died or despawned
 * yet, by world view id and NPC index, with when the player's current run of hits on each began, and the deaths
 * of such NPCs in the last few ticks. A death of one of them is the player's kill for {@link NpcKillTally}, and the
 * deaths around a task kill are paired with it ({@link #pairing}, V2 plan 4.1); each death remembers whether its kill
 * was tallied, so the kill that (re)starts a session tallies the deaths before it once ({@link #tallyUntallied}).
 * Bounded: at most {@link #MAX_NPCS} damaged NPCs are kept, the one damaged longest ago is dropped first, and at most
 * {@link #MAX_DEATHS} deaths, each for {@link #DEATH_KEEP_TICKS} ticks. Pure (no RuneLite types); client thread only,
 * not synchronized.
 *
 * <p><b>Engagement.</b> An NPC's engagement is its run of the player's own hits with no gap longer than the Away limit
 * ({@code awayMillis}); its first hit is the engagement start. Each NPC keeps the start and the latest hit of its
 * current run, which is all the engagement start needs: a hit more than the limit after the previous one starts a new
 * run. A death more than the limit after the latest hit is a run of its own, so an NPC hit long ago that dies now
 * (another player's kill, a cannon) never stretches a restart back to that old hit.</p>
 *
 * <p><b>Styles.</b> Each hit is noted as it arrives ({@link #damaged(int, int, long, long, int)}) and put down to
 * one of the player's attacks on that tick's GameTick, once the tick's attack is known ({@link #applyHits}, by
 * {@link PlayerAttacks}: an attack on that NPC, or a Burst's, Barrage's or chinchompa's splash): the NPC counts its hits
 * per style, and its run starts no later than the attack's tick (an attack lands a tick or more after it starts, and
 * after its own XP), so a restart backdated to it counts that XP. A death keeps its NPC's counts, also those of hits
 * put down after it died, and its style is the majority of them ({@link SetupDetection#npcStyle}). Each death gives its
 * style to one task kill at most ({@link #takeStyles}).</p>
 *
 * <p>An NPC index is unique only among the NPCs of one world view (the top-level one, or a boat's), so an NPC is kept
 * by both. A despawned NPC's index can be reused, so the plugin forgets an NPC when it despawns ({@link #forget}) and
 * every NPC and death on every login, hop and plugin start ({@link #clear}).</p>
 */
final class DamagedNpcs
{
	/** Most damaged NPCs kept; plenty for barraging a dense multi-combat spot. */
	static final int MAX_NPCS = 64;
	/** Most recent deaths kept. */
	static final int MAX_DEATHS = 64;
	/**
	 * Ticks a death is kept after the tick it happened on. A task kill on tick T is paired with deaths on ticks T - 2 to
	 * T + 1, decided again on tick T + 1, so a death on tick D is needed until tick D + 3. A drop to zero on tick T is a
	 * kill only once its completion confirms it, which may come up to
	 * {@link SessionTracker#MAX_TRANSIENT_ZERO_OBSERVATIONS} + 1 ticks later; its deaths are still paired from T - 2 then
	 *, so they are kept that much longer.
	 */
	static final int DEATH_KEEP_TICKS = 3 + SessionTracker.MAX_TRANSIENT_ZERO_OBSERVATIONS + 1;

	/** Most hits waiting for {@link #applyHits}; one tick's hits, even a barrage's on a dense spot, are far fewer. */
	static final int MAX_PENDING_HITS = 256;

	/** One damaged NPC's current run of the player's hits, and its hits per style. */
	private static final class Run
	{
		long start;
		long lastHit;
		/** Hits put down to an attack, per style ({@link SetupStyle#ordinal()}). */
		final int[] styleHits = new int[SetupStyle.values().length];
	}

	/** A hit noted as it arrived, put down to an attack on its tick's GameTick ({@link #applyHits}). */
	private static final class PendingHit
	{
		final Run run;
		/** Its NPC ({@link #key}). */
		final long npc;
		final int tick;

		PendingHit(Run run, long npc, int tick)
		{
			this.run = run;
			this.npc = npc;
			this.tick = tick;
		}
	}

	/** One death of a damaged NPC. */
	private static final class Death
	{
		final String name;
		/** The name its kill is tallied under ({@code CatacombsVariants.tallyName}), or {@code null} for none. */
		final String tallyName;
		/** The NPC's last run of hits: its start may still move back, and its style counts grow ({@link #applyHits}). */
		final Run run;
		final long diedAt;
		/** Whether it died more than the Away limit after its latest hit: a run of its own, starting at the death. */
		final boolean ownRun;
		final int tick;
		/** Whether its kill is in the session's kills per NPC name; never tallied twice. */
		boolean tallied;
		/** Whether its style went to a task kill's context ({@link #takeStyles}); never twice. */
		boolean styleTaken;

		Death(String name, String tallyName, Run run, long diedAt, boolean ownRun, int tick, boolean tallied)
		{
			this.name = name;
			this.tallyName = tallyName;
			this.run = run;
			this.diedAt = diedAt;
			this.ownRun = ownRun;
			this.tick = tick;
			this.tallied = tallied;
		}

		/** Its engagement start: its run's start, or the death itself for a run of its own; never after the death. */
		long engagementStart()
		{
			return ownRun ? diedAt : Math.min(run.start, diedAt);
		}
	}

	/** NPC key ({@link #key}) to its run, in the order last damaged (access order), so the eldest is the oldest. */
	private final Map<Long, Run> damaged = new LinkedHashMap<Long, Run>(16, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Run> eldest)
		{
			return size() > MAX_NPCS;
		}
	};
	/** Recent deaths of damaged NPCs, oldest first. */
	private final Deque<Death> deaths = new ArrayDeque<>();
	/** Hits since the last {@link #applyHits}, oldest first. */
	private final Deque<PendingHit> pendingHits = new ArrayDeque<>();

	/**
	 * The player hit the NPC at {@code index} in world view {@code worldViewId} at {@code now} (clock millis). A hit more
	 * than {@code awayMillis} after the NPC's previous one starts a new engagement. The hit is never put down to an
	 * attack (for tests of the engagement alone; the plugin uses {@link #damaged(int, int, long, long, int)}).
	 */
	void damaged(int worldViewId, int index, long now, long awayMillis)
	{
		run(worldViewId, index, now, awayMillis);
	}

	/**
	 * The player's hit on the NPC at {@code index} in world view {@code worldViewId} arrived at {@code now} (clock
	 * millis) on game tick {@code tick}, as {@link #damaged(int, int, long, long)}; the next {@link #applyHits} puts it
	 * down to an attack.
	 */
	void damaged(int worldViewId, int index, long now, long awayMillis, int tick)
	{
		final Run run = run(worldViewId, index, now, awayMillis);
		if (pendingHits.size() >= MAX_PENDING_HITS)
		{
			pendingHits.removeFirst();
		}
		pendingHits.addLast(new PendingHit(run, key(worldViewId, index), tick));
	}

	private Run run(int worldViewId, int index, long now, long awayMillis)
	{
		final long key = key(worldViewId, index);
		Run run = damaged.get(key);
		if (run == null)
		{
			run = new Run();
			run.start = now;
			damaged.put(key, run);
		}
		else if (now - run.lastHit > awayMillis)
		{
			run.start = now;
		}
		run.lastHit = Math.max(run.lastHit, now);
		return run;
	}

	/**
	 * Puts the hits noted since the last call down to the player's attacks: each counts for its attack's style on its
	 * NPC, alive or dead, and the NPC's run starts no later than the attack's tick. First every hit is matched with an
	 * attack on its own NPC ({@link PlayerAttacks#forHit}), which tells on which ticks each Burst's, Barrage's or
	 * chinchompa's hits on its target landed; then the hits left over with such a splash on their tick
	 * ({@link PlayerAttacks#forSplashHit}). A hit with neither (a cannon's) counts for no style and moves nothing. Called
	 * once per game tick, once that tick's attack is known.
	 */
	void applyHits(PlayerAttacks attacks)
	{
		final List<PendingHit> unmatched = new ArrayList<>();
		for (PendingHit hit : pendingHits)
		{
			if (!count(hit, attacks.forHit(hit.tick, hit.npc)))
			{
				unmatched.add(hit);
			}
		}
		for (PendingHit hit : unmatched)
		{
			count(hit, attacks.forSplashHit(hit.tick));
		}
		pendingHits.clear();
	}

	/** Counts {@code hit} for {@code attack}'s style, if there is one; returns whether there was. */
	private static boolean count(PendingHit hit, PlayerAttacks.Attack attack)
	{
		if (attack == null)
		{
			return false;
		}
		hit.run.styleHits[attack.getStyle().ordinal()]++;
		hit.run.start = Math.min(hit.run.start, attack.getAt());
		return true;
	}

	/** Whether the player damaged the NPC at {@code index} in world view {@code worldViewId} (and it is still kept). */
	boolean isDamaged(int worldViewId, int index)
	{
		return damaged.containsKey(key(worldViewId, index));
	}

	/**
	 * The NPC at {@code index} in world view {@code worldViewId} died on game tick {@code tick} at {@code now}. When
	 * the player had damaged it, its death is kept for {@link #pairing} under {@code name}, with its engagement start
	 * (the death itself when its latest hit was more than {@code awayMillis} before), and for {@link #tallyUntallied}
	 * under {@code tallyName}, with whether its kill was {@code tallied} already. It is forgotten as a damaged NPC
	 * either way.
	 *
	 * @param tallyName the name its kill is tallied under ({@code CatacombsVariants.tallyName}), or {@code null}
	 * @param tallied   whether its kill is already in the session's kills per NPC name
	 * @return whether the player had damaged it
	 */
	boolean died(int worldViewId, int index, String name, String tallyName, boolean tallied, int tick, long now,
		long awayMillis)
	{
		final Run run = damaged.remove(key(worldViewId, index));
		if (run == null)
		{
			return false;
		}
		deaths.addLast(new Death(name, tallyName, run, now, now - run.lastHit > awayMillis, tick, tallied));
		while (deaths.size() > MAX_DEATHS)
		{
			deaths.removeFirst();
		}
		prune(tick);
		return true;
	}

	/**
	 * The deaths kept from game ticks {@code fromTick} to {@code toTick} (both included): their earliest engagement
	 * start and their names. Deaths older than {@link #DEATH_KEEP_TICKS} before {@code toTick} are dropped first.
	 */
	KillPairing pairing(int fromTick, int toTick)
	{
		prune(toTick);
		long earliest = KillPairing.NO_START;
		final List<String> names = new ArrayList<>();
		for (Death death : deaths)
		{
			if (death.tick >= fromTick && death.tick <= toTick)
			{
				earliest = Math.min(earliest, death.engagementStart());
				names.add(death.name);
			}
		}
		return KillPairing.of(earliest, names);
	}

	/**
	 * The styles of up to {@code kills} deaths kept from game ticks {@code fromTick} to {@code toTick} (both included)
	 * whose style no task kill has taken yet, oldest first, each marked taken: the deaths paired with a count drop
	 * of {@code kills} give those kills their styles. A death with no styled hit (a cannon's kill) gives {@code null}.
	 * Fewer than {@code kills} when fewer such deaths are kept; the other kills have no style.
	 */
	List<SetupStyle> takeStyles(int fromTick, int toTick, int kills)
	{
		prune(toTick);
		final List<SetupStyle> styles = new ArrayList<>();
		for (Death death : deaths)
		{
			if (styles.size() >= kills)
			{
				break;
			}
			if (!death.styleTaken && death.tick >= fromTick && death.tick <= toTick)
			{
				death.styleTaken = true;
				styles.add(SetupDetection.npcStyle(death.run.styleHits));
			}
		}
		return styles;
	}

	/**
	 * Tallies the kills of the deaths kept from game ticks {@code fromTick} to {@code toTick} (both included) that are
	 * not tallied yet: the deaths paired with a kill that (re)started the session died before it ran, so their kills
	 * were refused then. Each death {@code tally} accepts (returns {@code true}) is marked, so no death is ever tallied
	 * twice, whichever kills it is paired with.
	 *
	 * @param tally tallies a kill under its tally name ({@link SessionTracker#addNpcKill}); returns whether it did
	 * @return how many kills were tallied
	 */
	int tallyUntallied(int fromTick, int toTick, Predicate<String> tally)
	{
		prune(toTick);
		int count = 0;
		for (Death death : deaths)
		{
			if (!death.tallied && death.tick >= fromTick && death.tick <= toTick && tally.test(death.tallyName))
			{
				death.tallied = true;
				count++;
			}
		}
		return count;
	}

	/**
	 * Drops the deaths from before {@code tick - DEATH_KEEP_TICKS}. The client's tick count only goes up within a run
	 * (also across logins and hops), and {@link #clear} drops everything on those anyway.
	 */
	private void prune(int tick)
	{
		final Iterator<Death> it = deaths.iterator();
		while (it.hasNext())
		{
			if (it.next().tick < tick - DEATH_KEEP_TICKS)
			{
				it.remove();
			}
		}
	}

	/** Forgets the NPC at {@code index} in world view {@code worldViewId} (it despawned). */
	void forget(int worldViewId, int index)
	{
		damaged.remove(key(worldViewId, index));
	}

	/** Forgets every NPC, death and hit waiting for {@link #applyHits}. */
	void clear()
	{
		damaged.clear();
		deaths.clear();
		pendingHits.clear();
	}

	/** How many damaged NPCs are kept. */
	int size()
	{
		return damaged.size();
	}

	/**
	 * One key per world view id and NPC index: the id in the high 32 bits, the index in the low 32. Also the target key
	 * of {@link PlayerAttacks}.
	 */
	static long key(int worldViewId, int index)
	{
		return ((long) worldViewId << 32) | (index & 0xFFFFFFFFL);
	}
}
