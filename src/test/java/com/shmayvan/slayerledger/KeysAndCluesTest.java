package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * {@link KeysAndClues} and {@link ClueScrollIds}: counting keys and clue scrolls in a loot tally, their panel text, and
 * every id checked against the gameval {@link ItemID} constants of the RuneLite API the build resolves.
 */
public class KeysAndCluesTest
{
	/** Constant names of the top-level gameval ItemID ints, by value. */
	private static Map<Integer, String> itemIdNames() throws IllegalAccessException
	{
		Map<Integer, String> names = new HashMap<>();
		for (Field f : ItemID.class.getDeclaredFields())
		{
			int m = f.getModifiers();
			if (Modifier.isStatic(m) && Modifier.isFinal(m) && f.getType() == int.class)
			{
				names.put(f.getInt(null), f.getName());
			}
		}
		return names;
	}

	private static Map<Integer, Long> loot(long... idQuantityPairs)
	{
		Map<Integer, Long> loot = new TreeMap<>();
		for (int i = 0; i < idQuantityPairs.length; i += 2)
		{
			loot.put((int) idQuantityPairs[i], idQuantityPairs[i + 1]);
		}
		return loot;
	}

	@Test
	public void keyIdsAreTheGamevalKeys()
	{
		assertEquals(ItemID.KONAR_KEY, KeysAndClues.BRIMSTONE_KEY_IDS[0]);
		assertEquals(new HashSet<>(java.util.Arrays.asList(ItemID.KONAR_KEY, ItemID.KONAR_KEY_COUNTVIEW1,
			ItemID.KONAR_KEY_COUNTVIEW2, ItemID.KONAR_KEY_COUNTVIEW3, ItemID.KONAR_KEY_COUNTVIEW4,
			ItemID.KONAR_KEY_COUNTVIEW5, ItemID.KONAR_KEY_COUNTVIEW6)), toSet(KeysAndClues.BRIMSTONE_KEY_IDS));
		assertEquals(ItemID.SLAYER_WILDERNESS_KEY, KeysAndClues.LARRANS_KEY_IDS[0]);
		assertEquals(new HashSet<>(java.util.Arrays.asList(ItemID.SLAYER_WILDERNESS_KEY,
			ItemID.SLAYER_WILDERNESS_KEY_2, ItemID.SLAYER_WILDERNESS_KEY_4, ItemID.SLAYER_WILDERNESS_KEY_5)),
			toSet(KeysAndClues.LARRANS_KEY_IDS));
	}

	/**
	 * Every clue id is a gameval clue scroll constant of its tier ({@code TRAIL_..._<TIER>_...}), not a casket, key,
	 * challenge scroll, puzzle box or torn master clue part, and no id is in two tiers. The lists were generated from
	 * the item names in the API source; the constant names are checked here because names aren't in the class file.
	 */
	@Test
	public void clueIdsAreGamevalClueScrollsOfTheirTier() throws IllegalAccessException
	{
		Map<Integer, String> names = itemIdNames();
		Pattern notAClue = Pattern.compile("_(CASKET|KEY\\d*|CHALLENGE|PUZZLEBOX|PART\\d)$");
		Set<Integer> seen = new HashSet<>();
		int total = 0;
		for (KeysAndClues.ClueTier tier : KeysAndClues.ClueTier.values())
		{
			String upper = tier.name();
			Pattern ofTier = Pattern.compile("^TRAIL_(CLUE_)?" + upper + "(_|$)");
			for (int id : idsOf(tier))
			{
				String name = names.get(id);
				assertNotNull(tier + " id " + id + " is not a gameval ItemID", name);
				assertTrue(tier + " id " + id + " is " + name, ofTier.matcher(name).find());
				assertFalse(tier + " id " + id + " is " + name, notAClue.matcher(name).find());
				assertTrue("in one tier only: " + id, seen.add(id));
				total++;
			}
		}
		assertEquals("ids in API 1.13.4", 1 + 166 + 167 + 187 + 138 + 1, total);
		assertEquals(ItemID.TRAIL_CLUE_BEGINNER, ClueScrollIds.BEGINNER[0]);
		assertEquals(ItemID.TRAIL_CLUE_MASTER, ClueScrollIds.MASTER[0]);
		// The base ids core LootTrackerMapping uses are included.
		assertTrue(toSet(ClueScrollIds.EASY).contains(ItemID.TRAIL_CLUE_EASY_SIMPLE001));
		assertTrue(toSet(ClueScrollIds.MEDIUM).contains(ItemID.TRAIL_CLUE_MEDIUM_SEXTANT001));
		assertTrue(toSet(ClueScrollIds.HARD).contains(ItemID.TRAIL_CLUE_HARD_MAP001));
		assertTrue(toSet(ClueScrollIds.ELITE).contains(ItemID.TRAIL_ELITE_EMOTE_EXP1));
	}

	/**
	 * The reverse check: every gameval clue scroll constant of a tier is in that tier's list, so a RuneLite update that
	 * adds clue steps fails here instead of leaving them uncounted. Regenerate {@link ClueScrollIds} when it does.
	 */
	@Test
	public void everyGamevalClueScrollIsListed() throws IllegalAccessException
	{
		Map<Integer, String> names = itemIdNames();
		Pattern notAClue = Pattern.compile("_(CASKET|KEY\\d*|CHALLENGE|PUZZLEBOX|PART\\d)$");
		for (KeysAndClues.ClueTier tier : KeysAndClues.ClueTier.values())
		{
			Pattern ofTier = Pattern.compile("^TRAIL_(CLUE_)?" + tier.name() + "(_|$)");
			Set<Integer> listed = toSet(idsOf(tier));
			for (Map.Entry<Integer, String> e : new TreeMap<>(names).entrySet())
			{
				String name = e.getValue();
				if (ofTier.matcher(name).find() && !notAClue.matcher(name).find())
				{
					assertTrue(tier + " clue " + name + " (" + e.getKey() + ") is not listed", listed.contains(e.getKey()));
				}
			}
		}
	}

	private static int[] idsOf(KeysAndClues.ClueTier tier)
	{
		switch (tier)
		{
			case BEGINNER:
				return ClueScrollIds.BEGINNER;
			case EASY:
				return ClueScrollIds.EASY;
			case MEDIUM:
				return ClueScrollIds.MEDIUM;
			case HARD:
				return ClueScrollIds.HARD;
			case ELITE:
				return ClueScrollIds.ELITE;
			default:
				return ClueScrollIds.MASTER;
		}
	}

	private static Set<Integer> toSet(int[] ids)
	{
		Set<Integer> set = new HashSet<>();
		for (int id : ids)
		{
			set.add(id);
		}
		return set;
	}

	@Test
	public void countsKeysAndCluesByTier()
	{
		KeysAndClues k = KeysAndClues.of(loot(ItemID.KONAR_KEY, 2, ItemID.SLAYER_WILDERNESS_KEY, 1,
			ItemID.TRAIL_CLUE_HARD_MAP001, 1, ClueScrollIds.HARD[5], 1, ItemID.TRAIL_ELITE_EMOTE_EXP1, 2,
			ItemID.TRAIL_CLUE_BEGINNER, 1, ItemID.COINS, 5000, ItemID.TRAIL_CLUE_HARD_SEXTANT001_CASKET, 1));
		assertEquals(2, k.getBrimstoneKeys());
		assertEquals(1, k.getLarransKeys());
		assertEquals(1, k.getClues(KeysAndClues.ClueTier.BEGINNER));
		assertEquals("a casket is not a clue scroll", 2, k.getClues(KeysAndClues.ClueTier.HARD));
		assertEquals(2, k.getClues(KeysAndClues.ClueTier.ELITE));
		assertEquals(0, k.getClues(KeysAndClues.ClueTier.MASTER));
		assertEquals("Keys: 2 brimstone, 1 Larran's", k.keysText());
		assertEquals("Clues: 1 beginner, 2 hard, 2 elite", k.cluesText());
	}

	@Test
	public void stackSizeVariantsCountAsTheKey()
	{
		KeysAndClues k = KeysAndClues.of(loot(ItemID.KONAR_KEY_COUNTVIEW3, 1, ItemID.SLAYER_WILDERNESS_KEY_5, 1));
		assertEquals(1, k.getBrimstoneKeys());
		assertEquals(1, k.getLarransKeys());
	}

	@Test
	public void nothingToShow()
	{
		assertSame(KeysAndClues.NONE, KeysAndClues.of((Map<Integer, Long>) null));
		assertSame(KeysAndClues.NONE, KeysAndClues.of((ItemTally) null));
		KeysAndClues k = KeysAndClues.of(loot(ItemID.COINS, 100));
		assertFalse(k.hasKeys());
		assertFalse(k.hasClues());
		assertNull(k.keysText());
		assertNull(k.cluesText());
		assertEquals("Keys: 1 Larran's", KeysAndClues.of(loot(ItemID.SLAYER_WILDERNESS_KEY, 1)).keysText());
		assertNull(KeysAndClues.of(loot(ItemID.SLAYER_WILDERNESS_KEY, 1)).cluesText());
	}

	@Test
	public void ignoresBadEntriesAndSaturates()
	{
		Map<Integer, Long> loot = new HashMap<>();
		loot.put(ItemID.KONAR_KEY, Long.MAX_VALUE);
		loot.put(ItemID.KONAR_KEY_COUNTVIEW1, 5L);
		loot.put(ItemID.SLAYER_WILDERNESS_KEY, -3L);
		loot.put(null, 4L);
		loot.put(ItemID.TRAIL_CLUE_MASTER, null);
		KeysAndClues k = KeysAndClues.of(loot);
		assertEquals(Long.MAX_VALUE, k.getBrimstoneKeys());
		assertEquals(0, k.getLarransKeys());
		assertFalse(k.hasClues());
	}

	@Test
	public void fromATally()
	{
		ItemTally tally = new ItemTally();
		tally.add(ItemID.TRAIL_CLUE_MASTER, 1);
		assertEquals("Clues: 1 master", KeysAndClues.of(tally).cluesText());
	}
}
