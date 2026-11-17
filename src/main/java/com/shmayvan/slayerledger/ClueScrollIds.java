package com.shmayvan.slayerledger;

import com.google.gson.*;
import java.util.*;

/**
 * Every item id of a clue scroll, by tier, as NPC loot reports it. Each clue step is its own item, so one tier has
 * many ids (core LootTrackerPlugin maps them back by name for the same reason, see its LootTrackerMapping).
 *
 * <p>The ids are the bundled {@code clue-scroll-ids.json} ({@link BundledData}), generated from
 * {@code net.runelite.api.gameval.ItemID} in RuneLite API 1.13.4: every top-level id whose item name is exactly
 * "Clue scroll (beginner)", "Clue scroll (easy)" and so on. Caskets, keys, challenge scrolls, puzzle boxes, torn master
 * clue parts and the Leagues-only scroll boxes are not clue scroll drops, so they are not included.
 * {@code KeysAndCluesTest} checks every id against the gameval constant names. Each tier is empty when the file can't
 * be read. Pure (no RuneLite types).</p>
 */
final class ClueScrollIds
{
	/** Each tier's ids, by the file's tier name. */
	private static final Map<String, int[]> TIERS = BundledData.load("clue-scroll-ids.json", ClueScrollIds::read,
		Collections.emptyMap());

	/** "Clue scroll (beginner)": 1 id. */
	static final int[] BEGINNER = tier("beginner");
	/** "Clue scroll (easy)": 166 ids. */
	static final int[] EASY = tier("easy");
	/** "Clue scroll (medium)": 167 ids. */
	static final int[] MEDIUM = tier("medium");
	/** "Clue scroll (hard)": 187 ids. */
	static final int[] HARD = tier("hard");
	/** "Clue scroll (elite)": 138 ids. */
	static final int[] ELITE = tier("elite");
	/** "Clue scroll (master)": 1 id. */
	static final int[] MASTER = tier("master");

	private ClueScrollIds()
	{
	}

	private static Map<String, int[]> read(JsonObject root)
	{
		final Map<String, int[]> tiers = new HashMap<>();
		for (Map.Entry<String, JsonElement> tier : root.getAsJsonObject("tiers").entrySet())
		{
			tiers.put(tier.getKey(), BundledData.ids(tier.getValue().getAsJsonArray()));
		}
		return tiers;
	}

	private static int[] tier(String name)
	{
		return TIERS.getOrDefault(name, new int[0]);
	}
}
