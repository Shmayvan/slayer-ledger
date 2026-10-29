package com.shmayvan.slayerledger;

import net.runelite.client.util.Text;

/**
 * Recognises the game messages sent when a pet drops. Pure (RuneLite's static {@link Text} only).
 *
 * <p>The texts are the three core ScreenshotPlugin (RuneLite 1.13.4, {@code PET_MESSAGES}) takes a pet screenshot on,
 * for the same chat types (game, spam, trade and friends chat notification messages). Core uses {@code contains};
 * this strips the colour tags and checks the start of the message instead, so a message only quoting the text is not
 * counted. Each text starts with "You", so another player's pet broadcast never matches.</p>
 */
final class PetMessage
{
	/** The pet follows you. */
	static final String FOLLOWED = "You have a funny feeling like you're being followed";
	/** The pet went to your inventory, because another follower was out. */
	static final String BACKPACK = "You feel something weird sneaking into your backpack";
	/** You already have this pet, so it was not given to you. */
	static final String DUPLICATE = "You have a funny feeling like you would have been followed";

	private PetMessage()
	{
	}

	/**
	 * What a chat message says about a pet drop, or {@code null} when it is not a pet message.
	 *
	 * @param petMessageType whether the message type is one core checks: GAMEMESSAGE, SPAM, TRADE or
	 *                       FRIENDSCHATNOTIFICATION
	 * @param message        the message text, possibly with tags
	 */
	static NotableDrop.Pet parse(boolean petMessageType, String message)
	{
		if (!petMessageType || message == null)
		{
			return null;
		}
		final String text = Text.removeTags(message).trim();
		if (text.startsWith(FOLLOWED) || text.startsWith(BACKPACK))
		{
			return NotableDrop.Pet.RECEIVED;
		}
		if (text.startsWith(DUPLICATE))
		{
			return NotableDrop.Pet.DUPLICATE;
		}
		return null;
	}
}
