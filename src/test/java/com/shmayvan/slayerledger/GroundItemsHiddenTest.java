package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Ground Items' hidden list as Slayer Ledger reads it: {@link GroundItemsHidden} matches names as
 * {@code GroundItemsPlugin} (RuneLite 1.13.4) does, and {@link HiddenLootResolver} turns names into hidden item ids
 * once per list.
 */
public class GroundItemsHiddenTest
{
	private static GroundItemsHidden hidden(String list)
	{
		return GroundItemsHidden.parse(list, "");
	}

	@Test
	public void exactNamesIgnoreCase()
	{
		GroundItemsHidden list = hidden("Bones, Rune platebody");
		assertTrue(list.hides("Bones"));
		assertTrue(list.hides("bones"));
		assertTrue(list.hides("RUNE PLATEBODY"));
		assertFalse("exact means the whole name", list.hides("Big bones"));
		assertFalse(list.hides("Bone"));
		assertFalse("an unknown name is never hidden", list.hides(null));
	}

	@Test
	public void wildcardsUseRuneLitesMatcher()
	{
		GroundItemsHidden list = hidden("*seed, Clue scroll*, *rune*");
		assertTrue(list.hides("Ranarr seed"));
		assertTrue(list.hides("RANARR SEED"));
		assertTrue(list.hides("Clue scroll (hard)"));
		assertTrue(list.hides("Nature rune"));
		assertTrue(list.hides("Rune platebody"));
		assertFalse(list.hides("Seed pod"));
		assertFalse(list.hides("Bones"));
		// Other regex characters are taken literally.
		assertTrue(hidden("Clue scroll (*)").hides("Clue scroll (elite)"));
		assertFalse(hidden("Coins.*").hides("Coinsxx"));
		assertTrue(hidden("Coins.*").hides("Coins.xx"));
	}

	@Test
	public void entriesAreSplitOnCommasAndTrimmed()
	{
		GroundItemsHidden list = hidden("  Vial ,Ashes,, ,  Jug  ,");
		assertTrue(list.hides("Vial"));
		assertTrue(list.hides("Ashes"));
		assertTrue(list.hides("Jug"));
		assertFalse("blank entries match nothing", list.hides(""));
		assertFalse(list.hides(" "));
		assertEquals("the same list however it is spaced", hidden("Vial, Ashes, Jug"), list);
	}

	@Test
	public void anEmptyListHidesNothing()
	{
		assertSame(GroundItemsHidden.NONE, hidden(""));
		assertSame(GroundItemsHidden.NONE, hidden(" , ,"));
		assertTrue(hidden("").isEmpty());
		assertFalse(hidden("").hides("Bones"));
	}

	@Test
	public void aListNeverEditedIsGroundItemsDefault()
	{
		GroundItemsHidden list = GroundItemsHidden.parse(null, null);
		assertEquals(hidden(GroundItemsHidden.DEFAULT_HIDDEN_ITEMS), list);
		for (String name : Arrays.asList("Vial", "Ashes", "Coins", "Bones", "Bucket", "Jug", "Seaweed"))
		{
			assertTrue(name, list.hides(name));
		}
		assertFalse(list.hides("Big bones"));
	}

	@Test
	public void entriesWithAQuantityConditionAreIgnored()
	{
		GroundItemsHidden list = hidden("Coins < 1000, Bones > 5, Ashes > 0, Vial > 1 2");
		assertFalse("only a stack on the ground can be checked against it", list.hides("Coins"));
		assertFalse(list.hides("Bones"));
		assertTrue("> 0 holds for every stack", list.hides("Ashes"));
		// Ground Items reads an unparsable quantity as "> 0".
		assertTrue(list.hides("Vial"));
		// Text after the < or > that is not a number: no condition, it is all name.
		assertTrue(hidden("Vial>abc").hides("vial>ABC"));
		// A trailing number without < or > is part of the name.
		assertTrue(hidden("Rune 2h sword").hides("Rune 2h sword"));
		assertTrue(hidden("Coins <").hides("Coins <"));
		assertSame(GroundItemsHidden.NONE, hidden("Coins < 1000"));
	}

	@Test
	public void theHighlightedListWinsAsInGroundItems()
	{
		// An exact highlight beats any hide.
		assertFalse(GroundItemsHidden.parse("*", "Dragon bones").hides("Dragon bones"));
		assertTrue(GroundItemsHidden.parse("*", "Dragon bones").hides("Bones"));
		assertFalse(GroundItemsHidden.parse("Bones", "bones").hides("Bones"));
		// A wildcard highlight beats a wildcard hide, but not an exact one.
		assertFalse(GroundItemsHidden.parse("*bones", "Dragon*").hides("Dragon bones"));
		assertTrue(GroundItemsHidden.parse("Dragon bones", "Dragon*").hides("Dragon bones"));
		// A highlight with a quantity condition is ignored too.
		assertTrue(GroundItemsHidden.parse("Coins", "Coins > 10000").hides("Coins"));
	}

	@Test
	public void theSettingAndGroundItemsMustBothBeOn()
	{
		assertSame("setting off", GroundItemsHidden.NONE, GroundItemsHidden.effective(false, null, "Bones", ""));
		assertSame("Ground Items off", GroundItemsHidden.NONE,
			GroundItemsHidden.effective(true, "false", "Bones", ""));
		assertTrue("Ground Items on", GroundItemsHidden.effective(true, "true", "Bones", "").hides("Bones"));
		assertTrue("never turned off: on by default", GroundItemsHidden.effective(true, null, "Bones", "")
			.hides("Bones"));
		assertTrue("never edited: the default list", GroundItemsHidden.effective(true, null, null, null)
			.hides("Coins"));
	}

	@Test
	public void equalListsAreEqual()
	{
		assertEquals(hidden("Bones, *seed"), GroundItemsHidden.parse("Bones,*seed", null));
		assertEquals(hidden("Bones, *seed").hashCode(), hidden("Bones,*seed").hashCode());
		assertNotEquals(hidden("Bones"), hidden("Bones, Ashes"));
		assertNotEquals(GroundItemsHidden.parse("Bones", "Ashes"), hidden("Bones"));
	}

	// ---- HiddenLootResolver and HiddenLoot ----

	@Test
	public void theResolverMatchesEachIdOncePerList()
	{
		Map<Integer, String> names = new HashMap<>();
		names.put(526, "Bones");
		names.put(1127, "Rune platebody");
		// A noted item's own name is its unnoted item's.
		names.put(527, "Bones");
		List<Integer> asked = new ArrayList<>();
		HiddenLootResolver resolver = new HiddenLootResolver();
		assertSame("nothing to hide: no names asked", HiddenLoot.NONE,
			resolver.resolve(Arrays.asList(526, 1127), id ->
			{
				asked.add(id);
				return names.get(id);
			}));
		assertTrue(asked.isEmpty());

		assertTrue(resolver.setList(hidden("Bones")));
		assertFalse("the same list again", resolver.setList(hidden("Bones")));
		HiddenLoot result = resolver.resolve(Arrays.asList(526, 527, 1127), id ->
		{
			asked.add(id);
			return names.get(id);
		});
		assertEquals("the note too, not the platebody", HiddenLoot.of(Arrays.asList(526, 527)), result);
		assertEquals(Arrays.asList(526, 527, 1127), asked);

		// Matched once: asked again, no name is looked up.
		resolver.resolve(Arrays.asList(526, 527, 1127), id ->
		{
			asked.add(id);
			return names.get(id);
		});
		assertEquals(3, asked.size());

		// Another list matches afresh.
		assertTrue(resolver.setList(hidden("Rune*")));
		assertEquals(HiddenLoot.of(Collections.singleton(1127)), resolver.resolve(Arrays.asList(526, 1127),
			names::get));
	}

	@Test
	public void anUnknownNameIsNotHiddenUntilItIsKnown()
	{
		Map<Integer, String> names = new HashMap<>();
		HiddenLootResolver resolver = new HiddenLootResolver();
		resolver.setList(hidden("Bones"));
		assertSame(HiddenLoot.NONE, resolver.resolve(Collections.singleton(526), names::get));
		assertSame("a failed lookup is unknown", HiddenLoot.NONE, resolver.resolve(Collections.singleton(526), id ->
		{
			throw new IllegalStateException("no item data");
		}));
		names.put(526, "Bones");
		assertEquals(HiddenLoot.of(Collections.singleton(526)), resolver.resolve(Collections.singleton(526),
			names::get));
	}

	@Test
	public void hiddenLootLeavesItemsOutWithoutChangingTheTally()
	{
		ItemTally loot = new ItemTally();
		loot.add(526, 3);
		loot.add(1127, 1);
		HiddenLoot hidden = HiddenLoot.of(Arrays.asList(526, null));
		ItemTally valued = hidden.valued(loot);
		assertEquals(0, valued.get(526));
		assertEquals(1, valued.get(1127));
		assertEquals("the tally itself is unchanged", 3, loot.get(526));
		assertSame("nothing hidden: the same tally", loot, HiddenLoot.of(Collections.singleton(4151)).valued(loot));
		assertSame(HiddenLoot.NONE, HiddenLoot.of(Collections.emptyList()));
		assertTrue(hidden.leavesOutAny(Arrays.asList(1, 526)));
		assertFalse(hidden.leavesOutAny(Collections.singleton(1)));
	}

	@Test
	public void hiddenCoinsLeaveTheGridButKeepTheirValue()
	{
		ItemTally loot = new ItemTally();
		loot.add(ItemValuation.COINS, 5_000);
		loot.add(526, 3);
		loot.add(1127, 1);
		HiddenLoot coinsAndBones = HiddenLoot.of(Arrays.asList(ItemValuation.COINS, 526));

		ItemTally valued = coinsAndBones.valued(loot);
		assertEquals("coins always count in the value", 5_000, valued.get(ItemValuation.COINS));
		assertEquals("bones are left out", 0, valued.get(526));
		assertEquals(1, valued.get(1127));
		assertFalse(coinsAndBones.isEmpty());

		HiddenLoot coinsOnly = HiddenLoot.of(Collections.singleton(ItemValuation.COINS));
		assertFalse(coinsOnly.isEmpty());
		assertFalse(coinsOnly.leavesOutAny(loot.itemIds()));
		assertSame("hidden coins alone leave nothing out of the value", loot, coinsOnly.valued(loot));

		List<ItemDetails.Row> rows = Arrays.asList(
			ItemDetails.Row.builder().itemId(1127).name("Rune platebody").quantity(1).build(),
			ItemDetails.Row.builder().itemId(526).name("Bones").quantity(3).build(),
			ItemDetails.Row.builder().itemId(ItemValuation.COINS).name("Coins").quantity(5_000).build());
		List<ItemDetails.Row> shown = coinsAndBones.visibleRows(rows);
		assertEquals("coins are hidden from the grid like any item", 1, shown.size());
		assertEquals(1127, shown.get(0).getItemId());
		assertEquals(2, coinsOnly.visibleRows(rows).size());
	}
}
