package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * Which kill loot a bonecrusher or ash sanctifier leaves out, for each "Bonecrusher handling" mode.
 */
public class CrushedLootFilterTest
{
	private static final List<Integer> NOTHING = Collections.emptyList();
	private static final List<Integer> CRUSHER = Arrays.asList(ItemID.SHARK, ItemID.BONECRUSHER);
	private static final List<Integer> NECKLACE = Collections.singletonList(ItemID.BONECRUSHER_NECKLACE);
	private static final List<Integer> SANCTIFIER = Collections.singletonList(ItemID.ASH_SANCTIFIER);
	private static final List<Integer> BOTH = Arrays.asList(ItemID.BONECRUSHER_NECKLACE, ItemID.ASH_SANCTIFIER);

	/** Wyrm loot as in the owner's task: wyrm bones, demonic ashes, plain ashes, noted bones, coins. */
	private static ItemTally wyrmLoot()
	{
		ItemTally loot = new ItemTally();
		loot.add(ItemID.WYRM_BONES, 1);
		loot.add(ItemID.ABYSSAL_ASHES, 1);
		loot.add(ItemID.ASHES, 1);
		loot.add(ItemID.Cert.DRAGON_BONES, 5);
		loot.add(ItemID.COINS, 300);
		return loot;
	}

	@Test
	public void itemIdsMatchTheGameValues()
	{
		// Checked against net.runelite.api.gameval.ItemID in RuneLite 1.13.4, and the owner's wyrm data (22780).
		assertEquals(13116, ItemID.BONECRUSHER);
		assertEquals(22986, ItemID.BONECRUSHER_NECKLACE);
		assertEquals(25781, ItemID.ASH_SANCTIFIER);
		assertEquals(22780, ItemID.WYRM_BONES);
		assertTrue(CrushedLootFilter.BONES.contains(22780));
		assertTrue(CrushedLootFilter.DEMONIC_ASHES.containsAll(Arrays.asList(25766, 25769, 25772, 25775, 25778)));
		assertEquals(5, CrushedLootFilter.DEMONIC_ASHES.size());
	}

	@Test
	public void boneAndAshSetsDoNotOverlapAndHoldNoNotedOrPlainItems()
	{
		for (int id : CrushedLootFilter.BONES)
		{
			assertFalse(CrushedLootFilter.DEMONIC_ASHES.contains(id));
		}
		assertFalse(CrushedLootFilter.BONES.contains(ItemID.Cert.BONES));
		assertFalse(CrushedLootFilter.BONES.contains(ItemID.Cert.WYRM_BONES));
		assertFalse(CrushedLootFilter.DEMONIC_ASHES.contains(ItemID.ASHES));
		assertFalse(CrushedLootFilter.DEMONIC_ASHES.contains(ItemID.Cert.ABYSSAL_ASHES));
		assertTrue(CrushedLootFilter.BONES.containsAll(Arrays.asList(ItemID.BONES, ItemID.BIG_BONES,
			ItemID.DRAGON_BONES, ItemID.DAGANNOTH_KING_BONES, ItemID.DRAKE_BONES, ItemID.HYDRA_BONES)));
	}

	@Test
	public void autoWithoutACrusherKeepsEverything()
	{
		CrushedLootFilter filter = CrushedLootFilter.forDrop(BonecrusherMode.AUTO, NOTHING);
		assertSame(CrushedLootFilter.KEEP_ALL, filter);
		assertEquals(wyrmLoot(), filter.apply(wyrmLoot()));
		assertSame(CrushedLootFilter.KEEP_ALL, CrushedLootFilter.forDrop(BonecrusherMode.AUTO, null));
		assertSame(CrushedLootFilter.KEEP_ALL, CrushedLootFilter.forDrop(BonecrusherMode.AUTO,
			Arrays.asList(null, ItemID.SHARK)));
	}

	@Test
	public void autoWithABonecrusherDropsBonesOnly()
	{
		for (List<Integer> carried : Arrays.asList(CRUSHER, NECKLACE))
		{
			CrushedLootFilter filter = CrushedLootFilter.forDrop(BonecrusherMode.AUTO, carried);
			assertTrue(filter.excludesBones());
			assertFalse(filter.excludesAshes());
			ItemTally kept = filter.apply(wyrmLoot());
			assertEquals(0, kept.get(ItemID.WYRM_BONES));
			assertEquals(1, kept.get(ItemID.ABYSSAL_ASHES));
			assertEquals(1, kept.get(ItemID.ASHES));
			assertEquals("noted bones are not crushed", 5, kept.get(ItemID.Cert.DRAGON_BONES));
			assertEquals(300, kept.get(ItemID.COINS));
			assertEquals(4, kept.size());
		}
	}

	@Test
	public void autoWithAnAshSanctifierDropsDemonicAshesOnly()
	{
		CrushedLootFilter filter = CrushedLootFilter.forDrop(BonecrusherMode.AUTO, SANCTIFIER);
		assertFalse(filter.excludesBones());
		assertTrue(filter.excludesAshes());
		ItemTally kept = filter.apply(wyrmLoot());
		assertEquals(1, kept.get(ItemID.WYRM_BONES));
		assertEquals(0, kept.get(ItemID.ABYSSAL_ASHES));
		assertEquals("plain ashes are not demonic", 1, kept.get(ItemID.ASHES));
	}

	@Test
	public void autoWithBothDropsBonesAndAshes()
	{
		ItemTally kept = CrushedLootFilter.forDrop(BonecrusherMode.AUTO, BOTH).apply(wyrmLoot());
		assertEquals(0, kept.get(ItemID.WYRM_BONES));
		assertEquals(0, kept.get(ItemID.ABYSSAL_ASHES));
		assertEquals(3, kept.size());
	}

	@Test
	public void alwaysExcludeIgnoresWhatIsCarried()
	{
		for (List<Integer> carried : Arrays.asList(NOTHING, CRUSHER, null))
		{
			CrushedLootFilter filter = CrushedLootFilter.forDrop(BonecrusherMode.ALWAYS_EXCLUDE, carried);
			assertTrue(filter.excludesBones());
			assertTrue(filter.excludesAshes());
			assertFalse(filter.keeps(ItemID.WYRM_BONES));
			assertFalse(filter.keeps(ItemID.INFERNAL_ASHES));
			assertTrue(filter.keeps(ItemID.COINS));
		}
		assertFalse(CrushedLootFilter.needsCarriedItems(BonecrusherMode.ALWAYS_EXCLUDE));
	}

	@Test
	public void neverExcludeKeepsEverythingEvenWithACrusher()
	{
		CrushedLootFilter filter = CrushedLootFilter.forDrop(BonecrusherMode.NEVER_EXCLUDE, BOTH);
		assertSame(CrushedLootFilter.KEEP_ALL, filter);
		assertEquals(wyrmLoot(), filter.apply(wyrmLoot()));
		assertFalse(CrushedLootFilter.needsCarriedItems(BonecrusherMode.NEVER_EXCLUDE));
	}

	@Test
	public void nullModeIsAuto()
	{
		assertTrue(CrushedLootFilter.needsCarriedItems(BonecrusherMode.AUTO));
		assertTrue(CrushedLootFilter.needsCarriedItems(null));
		assertTrue(CrushedLootFilter.forDrop(null, CRUSHER).excludesBones());
	}

	@Test
	public void applyLeavesTheInputAloneAndHandlesNull()
	{
		ItemTally loot = wyrmLoot();
		CrushedLootFilter.forDrop(BonecrusherMode.ALWAYS_EXCLUDE, null).apply(loot);
		assertEquals(wyrmLoot(), loot);
		assertTrue(CrushedLootFilter.KEEP_ALL.apply(null).isEmpty());
	}

	@Test
	public void modesShowReadableNames()
	{
		assertEquals("Auto", BonecrusherMode.AUTO.toString());
		assertEquals("Always exclude", BonecrusherMode.ALWAYS_EXCLUDE.toString());
		assertEquals("Never exclude", BonecrusherMode.NEVER_EXCLUDE.toString());
	}
}
