package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Slayer keys and clue scrolls among a task's loot: brimstone keys (Konar), Larran's keys (Krystilia's Wilderness
 * tasks) and clue scrolls by tier. Derived from the loot tally ({@link LedgerRecord#getLoot()}, or the session's
 * loot), so it follows the loot's rules (only loot received while the session was running, after the bonecrusher and
 * "Ignored loot" filters) and older records with loot get counts too. Nothing extra is stored. Immutable; pure (no
 * RuneLite types).
 *
 * <p>Ids are those of {@code net.runelite.api.gameval.ItemID} in RuneLite API 1.13.4 ({@code KeysAndCluesTest} checks
 * them against it). Besides each key's own id, the ids the game lists under the same name (its stack-size display
 * variants) are counted too, in case loot ever reports one. Clue scroll ids are in {@link ClueScrollIds}.</p>
 */
final class KeysAndClues
{
	/** Clue scroll tiers, in display order. */
	enum ClueTier
	{
		BEGINNER("beginner", ClueScrollIds.BEGINNER),
		EASY("easy", ClueScrollIds.EASY),
		MEDIUM("medium", ClueScrollIds.MEDIUM),
		HARD("hard", ClueScrollIds.HARD),
		ELITE("elite", ClueScrollIds.ELITE),
		MASTER("master", ClueScrollIds.MASTER);

		private final String displayName;
		private final int[] itemIds;

		ClueTier(String displayName, int[] itemIds)
		{
			this.displayName = displayName;
			this.itemIds = itemIds;
		}

		@Override
		public String toString()
		{
			return displayName;
		}
	}

	/**
	 * Brimstone key: {@code ItemID.KONAR_KEY} (23083) and its {@code KONAR_KEY_COUNTVIEW1}..{@code 6} variants
	 * (23085-23090).
	 */
	static final int[] BRIMSTONE_KEY_IDS = {23083, 23085, 23086, 23087, 23088, 23089, 23090};
	/**
	 * Larran's key: {@code ItemID.SLAYER_WILDERNESS_KEY} (23490) and its {@code SLAYER_WILDERNESS_KEY_2}, {@code _4}
	 * and {@code _5} variants (23492-23494).
	 */
	static final int[] LARRANS_KEY_IDS = {23490, 23492, 23493, 23494};

	static final KeysAndClues NONE = new KeysAndClues(0, 0, new long[ClueTier.values().length]);

	/** What each counted id is: a {@link ClueTier}, or {@link #BRIMSTONE} / {@link #LARRAN} for keys. */
	private static final Map<Integer, Object> KINDS = new HashMap<>();
	private static final Object BRIMSTONE = new Object();
	private static final Object LARRAN = new Object();

	static
	{
		for (int id : BRIMSTONE_KEY_IDS)
		{
			KINDS.put(id, BRIMSTONE);
		}
		for (int id : LARRANS_KEY_IDS)
		{
			KINDS.put(id, LARRAN);
		}
		for (ClueTier tier : ClueTier.values())
		{
			for (int id : tier.itemIds)
			{
				KINDS.put(id, tier);
			}
		}
	}

	private final long brimstoneKeys;
	private final long larransKeys;
	/** Clue scrolls by {@link ClueTier#ordinal()}. */
	private final long[] clues;

	private KeysAndClues(long brimstoneKeys, long larransKeys, long[] clues)
	{
		this.brimstoneKeys = brimstoneKeys;
		this.larransKeys = larransKeys;
		this.clues = clues;
	}

	/** The keys and clue scrolls in {@code loot} (item id to quantity); {@link #NONE} for {@code null}. */
	static KeysAndClues of(Map<Integer, Long> loot)
	{
		if (loot == null || loot.isEmpty())
		{
			return NONE;
		}
		long brimstone = 0;
		long larran = 0;
		long[] clues = new long[ClueTier.values().length];
		for (Map.Entry<Integer, Long> e : loot.entrySet())
		{
			if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0)
			{
				continue;
			}
			Object kind = KINDS.get(e.getKey());
			if (kind == BRIMSTONE)
			{
				brimstone = saturatedAdd(brimstone, e.getValue());
			}
			else if (kind == LARRAN)
			{
				larran = saturatedAdd(larran, e.getValue());
			}
			else if (kind instanceof ClueTier)
			{
				int i = ((ClueTier) kind).ordinal();
				clues[i] = saturatedAdd(clues[i], e.getValue());
			}
		}
		return new KeysAndClues(brimstone, larran, clues);
	}

	/** The keys and clue scrolls in {@code loot}; {@link #NONE} for {@code null}. */
	static KeysAndClues of(ItemTally loot)
	{
		return loot == null ? NONE : of(loot.toMap());
	}

	long getBrimstoneKeys()
	{
		return brimstoneKeys;
	}

	long getLarransKeys()
	{
		return larransKeys;
	}

	long getClues(ClueTier tier)
	{
		return clues[tier.ordinal()];
	}

	boolean hasKeys()
	{
		return brimstoneKeys > 0 || larransKeys > 0;
	}

	boolean hasClues()
	{
		return Arrays.stream(clues).anyMatch(n -> n > 0);
	}

	/** {@code Keys: 2 brimstone, 1 Larran's}, leaving out a kind with none; {@code null} when there are no keys. */
	String keysText()
	{
		List<String> parts = new ArrayList<>();
		if (brimstoneKeys > 0)
		{
			parts.add(brimstoneKeys + " brimstone");
		}
		if (larransKeys > 0)
		{
			parts.add(larransKeys + " Larran's");
		}
		return parts.isEmpty() ? null : "Keys: " + String.join(", ", parts);
	}

	/** {@code Clues: 1 hard, 2 elite}, lowest tier first, leaving out tiers with none; {@code null} when none. */
	String cluesText()
	{
		List<String> parts = new ArrayList<>();
		for (ClueTier tier : ClueTier.values())
		{
			long n = clues[tier.ordinal()];
			if (n > 0)
			{
				parts.add(n + " " + tier);
			}
		}
		return parts.isEmpty() ? null : "Clues: " + String.join(", ", parts);
	}

	private static long saturatedAdd(long a, long b)
	{
		long r = a + b;
		return ((a ^ r) & (b ^ r)) < 0 ? Long.MAX_VALUE : r;
	}
}
