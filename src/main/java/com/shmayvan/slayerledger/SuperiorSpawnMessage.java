package com.shmayvan.slayerledger;

import net.runelite.client.util.Text;

/**
 * Recognises the game message sent when a superior Slayer monster spawns. Pure (RuneLite's static {@link Text} only).
 *
 * <p>The text is the one core SlayerPlugin (RuneLite 1.13.4, {@code CHAT_SUPERIOR_MESSAGE}) notifies on, received as a
 * game or spam message. The game colours it, so colour and other tags are stripped before an exact comparison; core
 * uses {@code contains}, which this deliberately tightens so that, for example, a player's own message quoting the
 * text in another chat type or with extra words is never counted.</p>
 */
final class SuperiorSpawnMessage
{
	static final String TEXT = "A superior foe has appeared...";

	private SuperiorSpawnMessage()
	{
	}

	/**
	 * Whether a chat message announces a superior spawn.
	 *
	 * @param gameOrSpamMessage whether the message type is GAMEMESSAGE or SPAM (as core SlayerPlugin checks)
	 * @param message           the message text, possibly with tags
	 */
	static boolean matches(boolean gameOrSpamMessage, String message)
	{
		return gameOrSpamMessage && message != null && TEXT.equals(Text.removeTags(message).trim());
	}
}
