package com.shmayvan.slayerledger;

import java.util.regex.*;
import net.runelite.client.util.*;

/**
 * Plain text of a game chat message, for the matchers that compare it. Pure (RuneLite's static {@link Text} only).
 *
 * <p>Game messages come with two kinds of markup. {@link Text#removeTags} strips the {@code <col=ff0000>} style tags,
 * but not Jagex's own {@code @name@} colour markup: in November 2026 the game was seen sending highlighted messages
 * as, for example, {@code @mes_hl_red@A superior foe has appeared...</col>}. This strips both.</p>
 */
final class GameMessageText
{
	/**
	 * Jagex colour markup such as {@code @mes_hl_red@} or {@code @red@}: a lower-case letter, then lower-case letters,
	 * digits or underscores, between two {@code @}.
	 */
	private static final Pattern AT_MARKUP = Pattern.compile("@[a-z][a-z0-9_]*@");

	private GameMessageText()
	{
	}

	/**
	 * The message without tags or {@code @name@} markup, trimmed. {@code null} stays {@code null}.
	 */
	static String plain(String message)
	{
		if (message == null)
		{
			return null;
		}
		return AT_MARKUP.matcher(Text.removeTags(message)).replaceAll("").trim();
	}
}
