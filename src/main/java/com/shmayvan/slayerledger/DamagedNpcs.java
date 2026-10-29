package com.shmayvan.slayerledger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The NPCs the player has damaged (a hitsplat of theirs, {@code Hitsplat.isMine()}) and that haven't died or despawned
 * yet, by world view id and NPC index. A death of one of them is the player's kill for {@link NpcKillTally}.
 * Bounded: at most {@link #MAX_NPCS} are kept, the one damaged longest ago is dropped first. Pure (no RuneLite types);
 * client thread only, not synchronized.
 *
 * <p>An NPC index is unique only among the NPCs of one world view (the top-level one, or a boat's), so an NPC is kept
 * by both. A despawned NPC's index can be reused, so the plugin forgets an NPC when it despawns ({@link #forget}) and
 * every NPC on every login, hop and plugin start ({@link #clear}).</p>
 */
final class DamagedNpcs
{
	/** Most NPCs kept; plenty for barraging a dense multi-combat spot. */
	static final int MAX_NPCS = 64;

	/** NPC key ({@link #key}) to {@code true}, in the order last damaged (access order), so the eldest is the oldest. */
	private final Map<Long, Boolean> damaged = new LinkedHashMap<Long, Boolean>(16, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest)
		{
			return size() > MAX_NPCS;
		}
	};

	/** The player damaged the NPC at {@code index} in world view {@code worldViewId}. */
	void damaged(int worldViewId, int index)
	{
		damaged.put(key(worldViewId, index), Boolean.TRUE);
	}

	/**
	 * Whether the NPC at {@code index} in world view {@code worldViewId} was damaged by the player; it is forgotten
	 * either way (it died).
	 */
	boolean takeDied(int worldViewId, int index)
	{
		return damaged.remove(key(worldViewId, index)) != null;
	}

	/** Forgets the NPC at {@code index} in world view {@code worldViewId} (it despawned). */
	void forget(int worldViewId, int index)
	{
		damaged.remove(key(worldViewId, index));
	}

	/** Forgets every NPC. */
	void clear()
	{
		damaged.clear();
	}

	/** How many NPCs are kept. */
	int size()
	{
		return damaged.size();
	}

	/** One key per world view id and NPC index: the id in the high 32 bits, the index in the low 32. */
	private static long key(int worldViewId, int index)
	{
		return ((long) worldViewId << 32) | (index & 0xFFFFFFFFL);
	}
}
