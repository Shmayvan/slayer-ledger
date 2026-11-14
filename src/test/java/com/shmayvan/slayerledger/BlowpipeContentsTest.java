package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/** What the blowpipe holds for the supply runway ({@link BlowpipeContents}). */
public class BlowpipeContentsTest
{
	private static Map<Integer, Integer> used(int darts, int scales)
	{
		Map<Integer, Integer> used = new HashMap<>();
		if (darts > 0)
		{
			used.put(ItemID.AMETHYST_DART, darts);
		}
		if (scales > 0)
		{
			used.put(ItemID.SNAKEBOSS_SCALE, scales);
		}
		return used;
	}

	@Test
	public void unknownUntilACheckMessage()
	{
		BlowpipeContents contents = new BlowpipeContents();
		assertFalse(contents.isKnown());
		contents.used(used(1, 2));
		assertFalse("shots don't make it known", contents.isKnown());

		contents.messageSeen(BlowpipeMessage.parse("Darts: Amethyst dart x 1,200. Scales: 8,000 (48.5%)."));
		assertTrue(contents.isKnown());
		assertEquals(ItemID.AMETHYST_DART, contents.getDartId());
		assertEquals(1_200, contents.getDarts());
		assertEquals(8_000, contents.getScales());
	}

	@Test
	public void shotsAreTakenOut()
	{
		BlowpipeContents contents = new BlowpipeContents();
		contents.messageSeen(new BlowpipeMessage.Darts(true, ItemID.AMETHYST_DART, 10, 5));

		contents.used(used(1, 2));
		assertEquals(9, contents.getDarts());
		assertEquals(3, contents.getScales());
		contents.used(used(20, 20));
		assertEquals("never below none", 0, contents.getDarts());
		assertEquals(0, contents.getScales());

		// Another dart type's use isn't these darts'.
		contents.messageSeen(new BlowpipeMessage.Darts(true, ItemID.AMETHYST_DART, 10, 5));
		Map<Integer, Integer> other = new HashMap<>();
		other.put(ItemID.RUNE_DART, 3);
		contents.used(other);
		assertEquals(10, contents.getDarts());
	}

	@Test
	public void emptyUnknownTypeAndForget()
	{
		BlowpipeContents contents = new BlowpipeContents();
		contents.messageSeen(new BlowpipeMessage.Darts(true, ItemID.AMETHYST_DART, 10, 5));

		// "Your blowpipe has run out of darts.": no darts, the scales as they were.
		contents.messageSeen(BlowpipeMessage.parse("Your blowpipe has run out of darts."));
		assertTrue(contents.isKnown());
		assertEquals(0, contents.getDarts());
		assertEquals(5, contents.getScales());

		// Out of scales: none left, the darts as they were.
		contents.messageSeen(new BlowpipeMessage.Darts(true, ItemID.AMETHYST_DART, 10, 5));
		contents.outOfScales();
		assertTrue(contents.isKnown());
		assertEquals(10, contents.getDarts());
		assertEquals(0, contents.getScales());
		BlowpipeContents unchecked = new BlowpipeContents();
		unchecked.outOfScales();
		assertFalse("the darts still unknown", unchecked.isKnown());
		assertEquals(0, unchecked.getScales());

		// Out of both: none of either.
		contents.messageSeen(new BlowpipeMessage.Darts(true, ItemID.AMETHYST_DART, 10, 5));
		contents.messageSeen(BlowpipeMessage.parse("Your blowpipe has run out of scales and darts."));
		assertTrue(contents.isKnown());
		assertEquals(0, contents.getDarts());
		assertEquals(0, contents.getScales());
		contents.messageSeen(new BlowpipeMessage.Darts(true, ItemID.AMETHYST_DART, 10, 5));
		contents.messageSeen(BlowpipeMessage.parse(
			"Your blowpipe needs to be charged with Zulrah's scales and loaded with darts."));
		assertEquals(0, contents.getDarts());
		assertEquals(0, contents.getScales());

		// A dart type this plugin doesn't know: the darts are unknown.
		contents.messageSeen(new BlowpipeMessage.Darts(true, ChargedWeaponUsage.NO_ITEM, 10, 5));
		assertFalse(contents.isKnown());

		contents.messageSeen(new BlowpipeMessage.Darts(true, ItemID.AMETHYST_DART, 10, 5));
		contents.forget();
		assertFalse(contents.isKnown());
		assertEquals(BlowpipeContents.UNKNOWN, contents.getDarts());
		assertEquals(BlowpipeContents.UNKNOWN, contents.getScales());
		assertEquals(ChargedWeaponUsage.NO_ITEM, contents.getDartId());
	}

	@Test
	public void theBlowpipesDartsAreKnown()
	{
		assertTrue(BlowpipeMessage.isDart(ItemID.AMETHYST_DART));
		assertTrue(BlowpipeMessage.isDart(ItemID.DRAGON_DART));
		assertFalse(BlowpipeMessage.isDart(ItemID.SNAKEBOSS_SCALE));
	}
}
