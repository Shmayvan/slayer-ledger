package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * The combined capture-time loot filter: crushed bones and ashes ({@link CrushedLootFilter}) and the player's ignored
 * items ({@link IgnoredLoot}).
 */
public class LootFilterTest
{
	private static final Map<Integer, String> NAMES = new HashMap<>();

	static
	{
		NAMES.put(ItemID.WYRM_BONES, "Wyrm bones");
		NAMES.put(ItemID.ABYSSAL_ASHES, "Abyssal ashes");
		NAMES.put(ItemID.COINS, "Coins");
		NAMES.put(ItemID.DRAGON_BONES, "Dragon bones");
		NAMES.put(ItemID.RANARR_SEED, "Ranarr seed");
	}

	/** Item facts as the plugin reads them; records every lookup. */
	private static final class FakeNames implements LootFilter.ItemNames
	{
		final List<String> calls = new ArrayList<>();

		@Override
		public int unnoted(int itemId)
		{
			calls.add("unnoted " + itemId);
			return itemId == ItemID.Cert.DRAGON_BONES ? ItemID.DRAGON_BONES : itemId;
		}

		@Override
		public String name(int itemId)
		{
			calls.add("name " + itemId);
			return NAMES.get(itemId);
		}
	}

	private static ItemTally drop()
	{
		ItemTally loot = new ItemTally();
		loot.add(ItemID.WYRM_BONES, 1);
		loot.add(ItemID.ABYSSAL_ASHES, 1);
		loot.add(ItemID.COINS, 300);
		loot.add(ItemID.Cert.DRAGON_BONES, 5);
		loot.add(ItemID.RANARR_SEED, 2);
		return loot;
	}

	private static CrushedLootFilter crusher()
	{
		return CrushedLootFilter.forDrop(BonecrusherMode.AUTO, Collections.singletonList(ItemID.BONECRUSHER));
	}

	@Test
	public void keepsEverythingWithoutCrusherOrIgnoredItemsAndLooksNothingUp()
	{
		FakeNames names = new FakeNames();
		ItemTally kept = new LootFilter(CrushedLootFilter.KEEP_ALL, IgnoredLoot.NONE).apply(drop(), names);
		assertEquals(drop(), kept);
		assertTrue("no item lookups when nothing is ignored", names.calls.isEmpty());
	}

	@Test
	public void combinesTheCrusherAndTheIgnoredItems()
	{
		ItemTally kept = new LootFilter(crusher(), IgnoredLoot.parse("coins, *seed")).apply(drop(), new FakeNames());
		assertEquals(0, kept.get(ItemID.WYRM_BONES));
		assertEquals(0, kept.get(ItemID.COINS));
		assertEquals(0, kept.get(ItemID.RANARR_SEED));
		assertEquals(1, kept.get(ItemID.ABYSSAL_ASHES));
		assertEquals("noted bones are not crushed", 5, kept.get(ItemID.Cert.DRAGON_BONES));
		assertEquals(2, kept.size());
	}

	@Test
	public void notedDropsAreMatchedByTheUnnotedName()
	{
		ItemTally kept = new LootFilter(CrushedLootFilter.KEEP_ALL, IgnoredLoot.parse("Dragon bones"))
			.apply(drop(), new FakeNames());
		assertEquals(0, kept.get(ItemID.Cert.DRAGON_BONES));
		assertEquals(1, kept.get(ItemID.WYRM_BONES));
	}

	@Test
	public void idOnlyListsLookUpNoNames()
	{
		FakeNames names = new FakeNames();
		ItemTally kept = new LootFilter(CrushedLootFilter.KEEP_ALL, IgnoredLoot.parse(Integer.toString(ItemID.DRAGON_BONES)))
			.apply(drop(), names);
		assertEquals(0, kept.get(ItemID.Cert.DRAGON_BONES));
		assertEquals(4, kept.size());
		for (String call : names.calls)
		{
			assertTrue(call, call.startsWith("unnoted "));
		}
	}

	@Test
	public void eachItemIsLookedUpOnce()
	{
		FakeNames names = new FakeNames();
		new LootFilter(crusher(), IgnoredLoot.parse("coins")).apply(drop(), names);
		// The crushed wyrm bones are never looked up; the other four ids once each (unnoted id, then name).
		assertEquals(8, names.calls.size());
	}

	@Test
	public void failedLookupsLeaveOnlyIdMatches()
	{
		LootFilter.ItemNames failing = new LootFilter.ItemNames()
		{
			@Override
			public int unnoted(int itemId)
			{
				throw new IllegalStateException("not loaded");
			}

			@Override
			public String name(int itemId)
			{
				throw new IllegalStateException("not loaded");
			}
		};
		ItemTally kept = new LootFilter(CrushedLootFilter.KEEP_ALL, IgnoredLoot.parse("Coins, " + ItemID.RANARR_SEED))
			.apply(drop(), failing);
		assertEquals("the name could not be read", 300, kept.get(ItemID.COINS));
		assertEquals(0, kept.get(ItemID.RANARR_SEED));
	}

	@Test
	public void nullLootIsEmpty()
	{
		assertTrue(new LootFilter(crusher(), IgnoredLoot.parse("x")).apply(null, new FakeNames()).isEmpty());
	}
}
