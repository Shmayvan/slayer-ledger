package com.shmayvan.slayerledger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * Parsing and matching of the "Ignored loot" setting: ids, case-insensitive names, {@code *} wildcards and noted items.
 */
public class IgnoredLootTest
{
	@Test
	public void emptySettingsIgnoreNothing()
	{
		assertSame(IgnoredLoot.NONE, IgnoredLoot.parse(null));
		assertSame(IgnoredLoot.NONE, IgnoredLoot.parse(""));
		assertSame(IgnoredLoot.NONE, IgnoredLoot.parse("  "));
		assertSame(IgnoredLoot.NONE, IgnoredLoot.parse(" , ,,"));
		assertTrue(IgnoredLoot.NONE.isEmpty());
		assertFalse(IgnoredLoot.NONE.ignores(ItemID.BONES, ItemID.BONES, "Bones"));
	}

	@Test
	public void namesMatchWholeNamesCaseInsensitively()
	{
		IgnoredLoot ignored = IgnoredLoot.parse(" bones ,Coins");
		assertFalse(ignored.isEmpty());
		assertTrue(ignored.hasNames());
		assertTrue(ignored.ignores(ItemID.BONES, ItemID.BONES, "Bones"));
		assertTrue(ignored.ignores(ItemID.COINS, ItemID.COINS, "COINS"));
		assertFalse("not a substring match", ignored.ignores(ItemID.BIG_BONES, ItemID.BIG_BONES, "Big bones"));
		assertFalse("an unknown name matches no name entry", ignored.ignores(ItemID.BONES, ItemID.BONES, null));
	}

	@Test
	public void wildcardsMatchAnyRunOfCharacters()
	{
		IgnoredLoot ignored = IgnoredLoot.parse("*seed, Rune *, *bones*");
		assertTrue(ignored.ignores(5295, 5295, "Ranarr seed"));
		assertTrue(ignored.ignores(1, 1, "Rune full helm"));
		assertTrue(ignored.ignores(ItemID.BIG_BONES, ItemID.BIG_BONES, "Big bones"));
		assertTrue(ignored.ignores(ItemID.BONES, ItemID.BONES, "Bones"));
		assertFalse(ignored.ignores(2, 2, "Seed box"));
		assertFalse(ignored.ignores(3, 3, "Adamant full helm"));
	}

	@Test
	public void regexCharactersInNamesAreLiteral()
	{
		IgnoredLoot ignored = IgnoredLoot.parse("Prayer potion(4), Amulet of glory(?)");
		assertTrue(ignored.ignores(2434, 2434, "Prayer potion(4)"));
		assertFalse(ignored.ignores(139, 139, "Prayer potion(3)"));
		assertFalse(ignored.ignores(1712, 1712, "Amulet of glory(4)"));
	}

	@Test
	public void numbersAreItemIds()
	{
		IgnoredLoot ignored = IgnoredLoot.parse("526");
		assertFalse("ids need no name lookup", ignored.hasNames());
		assertTrue(ignored.ignores(ItemID.BONES, ItemID.BONES, null));
		assertFalse(ignored.ignores(ItemID.BIG_BONES, ItemID.BIG_BONES, "526"));

		IgnoredLoot tooLarge = IgnoredLoot.parse("99999999999");
		assertTrue("too large for an id: only a name could match", tooLarge.hasNames());
		assertFalse(tooLarge.ignores(ItemID.BONES, ItemID.BONES, "Bones"));
	}

	@Test
	public void notedDropsMatchTheirUnnotedItem()
	{
		IgnoredLoot byId = IgnoredLoot.parse(Integer.toString(ItemID.DRAGON_BONES));
		assertTrue(byId.ignores(ItemID.Cert.DRAGON_BONES, ItemID.DRAGON_BONES, "Dragon bones"));
		IgnoredLoot byNotedId = IgnoredLoot.parse(Integer.toString(ItemID.Cert.DRAGON_BONES));
		assertTrue("the noted id itself also matches",
			byNotedId.ignores(ItemID.Cert.DRAGON_BONES, ItemID.DRAGON_BONES, "Dragon bones"));
		assertFalse("but not the unnoted drop", byNotedId.ignores(ItemID.DRAGON_BONES, ItemID.DRAGON_BONES, "Dragon bones"));
		IgnoredLoot byName = IgnoredLoot.parse("dragon bones");
		assertTrue(byName.ignores(ItemID.Cert.DRAGON_BONES, ItemID.DRAGON_BONES, "Dragon bones"));
	}
}
