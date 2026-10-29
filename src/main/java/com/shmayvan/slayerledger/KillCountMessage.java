package com.shmayvan.slayerledger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * Recognises the game's kill count chat messages, such as {@code Your Vorkath kill count is: <col=ff0000>152</col>.}
 * Pure (no RuneLite types).
 *
 * <p>The pattern is core ChatCommandsPlugin's {@code KILLCOUNT_PATTERN} (RuneLite 1.13.4), matched the same way (on the
 * message with its colour tags, anywhere in it) for the same chat types (game, spam, trade and friends chat
 * notification messages). As in core, the count must be coloured ({@code <col=...>} or {@code @...@}, closed by
 * {@code </col>}), and a message with neither a prefix nor a count word ({@code Your X is: ...}) is not a kill count.
 * The boss name may be coloured too; its tags are removed.</p>
 *
 * <p>Core's pattern also matches counts that are not boss kills: raids and other completions ({@code completed},
 * {@code completion count for}, {@code completion count}), {@code subdued} (Wintertodt), {@code harvest} (Herbiboar),
 * {@code lap} (agility courses), {@code success} and {@code Total Ticket} counts. Those parse with
 * {@link Result#isBossKill()} {@code false}. Only {@code <boss> kill count is:} and the bare {@code <boss> count is:}
 * (for example {@code Your Barrows chest count is:}) are boss kills.</p>
 */
final class KillCountMessage
{
	/** Core ChatCommandsPlugin's KILLCOUNT_PATTERN (RuneLite 1.13.4), unchanged. */
	static final Pattern KILLCOUNT_PATTERN = Pattern.compile("Your (?<pre>completion count for |subdued |completed )?"
		+ "(?:<col=[0-9a-f]{6}>)?(?<boss>.+?)(?:</col>)? (?<post>(?:(?:kill|harvest|lap|completion|success|Total Ticket) )?"
		+ "(?:count )?)is: ?(?:<col=[0-9a-f]{6}>|@.+?@)(?<kc>[0-9,]+)</col>");

	private static final String KILL_COUNT = "kill count ";
	private static final String COUNT = "count ";

	private KillCountMessage()
	{
	}

	/** A recognised kill count message. */
	@Getter
	@EqualsAndHashCode
	@ToString
	static final class Result
	{
		/** The name the message gives, without colour tags, for example {@code Vorkath} or {@code Barrows chest}. */
		private final String boss;
		/** The count the message gives (at least 0). */
		private final int kc;
		/** Whether it counts a boss kill ({@code kill count} or bare {@code count}), rather than a lap, raid and so on. */
		private final boolean bossKill;

		Result(String boss, int kc, boolean bossKill)
		{
			this.boss = boss;
			this.kc = kc;
			this.bossKill = bossKill;
		}
	}

	/**
	 * What a chat message says about a kill count, or {@code null} when it is not a kill count message.
	 *
	 * @param kcMessageType whether the message type is one core checks: GAMEMESSAGE, SPAM, TRADE or
	 *                      FRIENDSCHATNOTIFICATION
	 * @param message       the message text, with its tags
	 */
	static Result parse(boolean kcMessageType, String message)
	{
		if (!kcMessageType || message == null)
		{
			return null;
		}
		final Matcher matcher = KILLCOUNT_PATTERN.matcher(message);
		if (!matcher.find())
		{
			return null;
		}
		final String pre = matcher.group("pre");
		final String post = matcher.group("post");
		final boolean hasPre = pre != null && !pre.isEmpty();
		final boolean hasPost = post != null && !post.isEmpty();
		if (!hasPre && !hasPost)
		{
			// Core ignores these too ("Your X is: n").
			return null;
		}
		final String boss = BossKillCounts.cleanName(matcher.group("boss"));
		final Integer kc = parseCount(matcher.group("kc"));
		if (boss.isEmpty() || kc == null)
		{
			return null;
		}
		final boolean bossKill = !hasPre && (KILL_COUNT.equals(post) || COUNT.equals(post));
		return new Result(boss, kc, bossKill);
	}

	/** A count such as {@code 1,130}, or {@code null} when it has no digits or is too large. */
	private static Integer parseCount(String text)
	{
		final String digits = text.replace(",", "");
		if (digits.isEmpty() || digits.length() > 10)
		{
			return null;
		}
		final long value = Long.parseLong(digits);
		return value > Integer.MAX_VALUE ? null : (int) value;
	}
}
