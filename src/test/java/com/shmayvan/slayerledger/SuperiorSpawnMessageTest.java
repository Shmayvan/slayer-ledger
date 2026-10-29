package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Recognising the superior spawn game message.
 */
public class SuperiorSpawnMessageTest
{
	@Test
	public void textMatchesCoreSlayerPlugin()
	{
		// net.runelite.client.plugins.slayer.SlayerPlugin.CHAT_SUPERIOR_MESSAGE in RuneLite 1.13.4.
		assertEquals("A superior foe has appeared...", SuperiorSpawnMessage.TEXT);
	}

	@Test
	public void matchesPlainAndColouredText()
	{
		assertTrue(SuperiorSpawnMessage.matches(true, "A superior foe has appeared..."));
		assertTrue(SuperiorSpawnMessage.matches(true, "<col=ef1020>A superior foe has appeared...</col>"));
		assertTrue(SuperiorSpawnMessage.matches(true, " <col=ff0000>A superior foe has appeared...</col> "));
	}

	@Test
	public void otherTextDoesNotMatch()
	{
		assertFalse(SuperiorSpawnMessage.matches(true, "A superior foe has appeared"));
		assertFalse(SuperiorSpawnMessage.matches(true, "a superior foe has appeared..."));
		assertFalse(SuperiorSpawnMessage.matches(true, "Someone said: A superior foe has appeared..."));
		assertFalse(SuperiorSpawnMessage.matches(true, "You have completed your task!"));
		assertFalse(SuperiorSpawnMessage.matches(true, ""));
		assertFalse(SuperiorSpawnMessage.matches(true, null));
	}

	@Test
	public void onlyGameOrSpamMessagesMatch()
	{
		assertFalse(SuperiorSpawnMessage.matches(false, "A superior foe has appeared..."));
	}
}
