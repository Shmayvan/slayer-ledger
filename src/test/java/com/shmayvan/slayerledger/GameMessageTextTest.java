package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

/**
 * Stripping tags and Jagex's {@code @name@} colour markup from game messages.
 */
public class GameMessageTextTest
{
	@Test
	public void stripsTagsAndAtMarkup()
	{
		assertEquals("A superior foe has appeared...",
			GameMessageText.plain("@mes_hl_red@A superior foe has appeared...</col>"));
		assertEquals("Your divine magic potion is about to expire.",
			GameMessageText.plain("@mes_hl_red@Your divine magic potion is about to expire.</col>"));
		assertEquals("Darts: Adamant dart x 1,234.", GameMessageText.plain("Darts: <col=007f00>Adamant dart x 1,234</col>."));
		assertEquals("red then blue", GameMessageText.plain(" @red@red then @blu@blue "));
	}

	@Test
	public void leavesOtherAtSignsAlone()
	{
		assertEquals("Mail me @ home", GameMessageText.plain("Mail me @ home"));
		assertEquals("@ABC@ stays", GameMessageText.plain("@ABC@ stays"));
		assertEquals("a@b", GameMessageText.plain("a@b"));
	}

	@Test
	public void plainTextAndNull()
	{
		assertEquals("You have completed your task!", GameMessageText.plain("You have completed your task!"));
		assertEquals("", GameMessageText.plain(""));
		assertNull(GameMessageText.plain(null));
	}
}
