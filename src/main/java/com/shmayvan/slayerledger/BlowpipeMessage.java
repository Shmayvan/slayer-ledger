package com.shmayvan.slayerledger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Value;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.util.Text;

/**
 * Reads the toxic blowpipe's dart type from game messages. Pure (RuneLite's static {@link Text} only).
 *
 * <p>Checking the blowpipe (and, reportedly, adding darts or scales) sends
 * {@code Darts: <name> x <n>. Scales: <n> (<pct>%).} or {@code Darts: None. Scales: ...}, with the values coloured.
 * Tags are stripped before matching; numbers may contain commas. The messages below, from geheur/weapon-charges'
 * recorded game messages, mean the blowpipe has no darts:</p>
 * <ul>
 *     <li>{@code Your blowpipe has run out of darts.}</li>
 *     <li>{@code Your blowpipe has run out of scales and darts.}</li>
 *     <li>{@code Your blowpipe contains no darts.}</li>
 *     <li>{@code Your blowpipe needs to be charged with Zulrah's scales and loaded with darts.}</li>
 *     <li>{@code The blowpipe has no darts in it.} (unloading an empty blowpipe)</li>
 * </ul>
 */
final class BlowpipeMessage
{
	private static final Pattern CHECK = Pattern.compile(
		"^Darts: (?:None|(.+?) x ([\\d,]+))\\. Scales: ([\\d,]+) \\(([\\d.]+)%\\)\\.$");

	private static final String[] NO_DARTS = {
		"Your blowpipe has run out of darts.",
		"Your blowpipe has run out of scales and darts.",
		"Your blowpipe contains no darts.",
		"Your blowpipe needs to be charged with Zulrah's scales and loaded with darts.",
		"The blowpipe has no darts in it.",
	};

	/** Dart names as the check message shows them (lower case), to item ids. */
	private static final Map<String, Integer> DARTS = darts();

	private static Map<String, Integer> darts()
	{
		final Map<String, Integer> darts = new HashMap<>();
		darts.put("bronze dart", ItemID.BRONZE_DART);
		darts.put("iron dart", ItemID.IRON_DART);
		darts.put("steel dart", ItemID.STEEL_DART);
		darts.put("black dart", ItemID.BLACK_DART);
		darts.put("mithril dart", ItemID.MITHRIL_DART);
		darts.put("adamant dart", ItemID.ADAMANT_DART);
		darts.put("rune dart", ItemID.RUNE_DART);
		darts.put("amethyst dart", ItemID.AMETHYST_DART);
		darts.put("dragon dart", ItemID.DRAGON_DART);
		return Collections.unmodifiableMap(darts);
	}

	/** What a blowpipe message says about the darts inside. */
	@Value
	static class Darts
	{
		/** Whether the blowpipe holds darts. */
		boolean loaded;
		/**
		 * The dart item id, or {@link ChargedWeaponUsage#NO_ITEM} if the blowpipe holds no darts or holds a dart type
		 * this class doesn't know.
		 */
		int dartId;
		/** Darts inside, if the message says (a check message), else -1. */
		int dartCount;
		/** Zulrah's scales inside, if the message says (a check message), else -1. */
		int scales;
	}

	private BlowpipeMessage()
	{
	}

	/** The dart item id for a dart name as the check message shows it ("Rune dart"), or {@code NO_ITEM}. */
	static int dartId(String name)
	{
		return DARTS.getOrDefault(name.trim().toLowerCase(Locale.ROOT), ChargedWeaponUsage.NO_ITEM);
	}

	/**
	 * Parses a game message, possibly with tags.
	 *
	 * @return what it says about the blowpipe's darts, or {@code null} if it isn't a blowpipe dart message
	 */
	static Darts parse(String message)
	{
		if (message == null)
		{
			return null;
		}
		final String text = Text.removeTags(message).trim();
		final Matcher matcher = CHECK.matcher(text);
		if (matcher.matches())
		{
			final int scales = number(matcher.group(3));
			if (matcher.group(1) == null)
			{
				return new Darts(false, ChargedWeaponUsage.NO_ITEM, 0, scales);
			}
			return new Darts(true, dartId(matcher.group(1)), number(matcher.group(2)), scales);
		}
		for (String noDarts : NO_DARTS)
		{
			if (noDarts.equals(text))
			{
				return new Darts(false, ChargedWeaponUsage.NO_ITEM, 0, -1);
			}
		}
		return null;
	}

	private static int number(String digits)
	{
		try
		{
			return Integer.parseInt(digits.replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}
}
