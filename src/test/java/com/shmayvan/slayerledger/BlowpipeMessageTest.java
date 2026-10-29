package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * The blowpipe's dart type read from its check and out-of-darts messages.
 */
public class BlowpipeMessageTest
{
	@Test
	public void checkMessageWithDarts()
	{
		final BlowpipeMessage.Darts darts = BlowpipeMessage.parse(
			"Darts: <col=007f00>Adamant dart x 16,383</col>. Scales: <col=007f00>16,383 (100.0%)</col>.");
		assertTrue(darts.isLoaded());
		assertEquals(ItemID.ADAMANT_DART, darts.getDartId());
		assertEquals(16_383, darts.getDartCount());
		assertEquals(16_383, darts.getScales());
	}

	@Test
	public void checkMessageWithoutTags()
	{
		final BlowpipeMessage.Darts darts = BlowpipeMessage.parse("Darts: Dragon dart x 5. Scales: 0 (0.0%).");
		assertEquals(ItemID.DRAGON_DART, darts.getDartId());
		assertEquals(5, darts.getDartCount());
		assertEquals(0, darts.getScales());
	}

	@Test
	public void checkMessageWithNoDarts()
	{
		final BlowpipeMessage.Darts darts = BlowpipeMessage.parse(
			"Darts: <col=007f00>None</col>. Scales: <col=007f00>99 (0.6%)</col>.");
		assertFalse(darts.isLoaded());
		assertEquals(ChargedWeaponUsage.NO_ITEM, darts.getDartId());
		assertEquals(0, darts.getDartCount());
		assertEquals(99, darts.getScales());
	}

	@Test
	public void everyDartName()
	{
		assertDart("Bronze dart", ItemID.BRONZE_DART);
		assertDart("Iron dart", ItemID.IRON_DART);
		assertDart("Steel dart", ItemID.STEEL_DART);
		assertDart("Black dart", ItemID.BLACK_DART);
		assertDart("Mithril dart", ItemID.MITHRIL_DART);
		assertDart("Adamant dart", ItemID.ADAMANT_DART);
		assertDart("Rune dart", ItemID.RUNE_DART);
		assertDart("Amethyst dart", ItemID.AMETHYST_DART);
		assertDart("Dragon dart", ItemID.DRAGON_DART);
	}

	private static void assertDart(String name, int itemId)
	{
		assertEquals(name, itemId, BlowpipeMessage.parse("Darts: " + name + " x 1,000. Scales: 2 (0.0%).").getDartId());
		assertEquals(name, itemId, BlowpipeMessage.dartId(name.toUpperCase()));
	}

	@Test
	public void unknownDartNameIsLoadedButUnknown()
	{
		final BlowpipeMessage.Darts darts = BlowpipeMessage.parse("Darts: Atlatl dart x 12. Scales: 3 (0.1%).");
		assertTrue(darts.isLoaded());
		assertEquals(ChargedWeaponUsage.NO_ITEM, darts.getDartId());
		assertEquals(12, darts.getDartCount());
	}

	@Test
	public void outOfDartsMessages()
	{
		for (String message : new String[]{
			"Your blowpipe has run out of darts.",
			"Your blowpipe has run out of scales and darts.",
			"Your blowpipe contains no darts.",
			"Your blowpipe needs to be charged with Zulrah's scales and loaded with darts.",
			"<col=ef1020>The blowpipe has no darts in it.</col>"})
		{
			final BlowpipeMessage.Darts darts = BlowpipeMessage.parse(message);
			assertFalse(message, darts.isLoaded());
			assertEquals(ChargedWeaponUsage.NO_ITEM, darts.getDartId());
			assertEquals(-1, darts.getScales());
		}
	}

	@Test
	public void otherMessagesAreIgnored()
	{
		assertNull(BlowpipeMessage.parse(null));
		assertNull(BlowpipeMessage.parse(""));
		assertNull(BlowpipeMessage.parse("Your blowpipe needs to be charged with Zulrah's scales."));
		assertNull(BlowpipeMessage.parse("The blowpipe can't hold any more darts."));
		assertNull(BlowpipeMessage.parse("Scales: 11,000 (100.0%)"));
		assertNull(BlowpipeMessage.parse("Player: Darts: Rune dart x 5. Scales: 3 (0.1%). lol"));
		assertNull(BlowpipeMessage.parse("Darts: Rune dart x 5. Scales: 3."));
	}
}
