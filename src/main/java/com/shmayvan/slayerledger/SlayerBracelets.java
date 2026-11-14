package com.shmayvan.slayerledger;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import net.runelite.api.gameval.ItemID;

/**
 * Charges used by the bracelet of slaughter and the expeditious bracelet. Each time a worn bracelet's effect
 * activates, the game uses one of its {@value #CHARGES_PER_BRACELET} charges (OSRS Wiki, "Bracelet of slaughter" and
 * "Expeditious bracelet") and prints a game message saying so, which core ItemChargePlugin also counts them by
 * (RuneLite 1.13.4). The bracelet's item id doesn't change with its charges and the charges aren't in a container, so
 * the supplies diff can't see them; one message is one charge, recorded under the bracelet with
 * {@value #CHARGES_PER_BRACELET} charges per item and valued like a teleport jewellery charge. The last charge counts
 * too, whether the bracelet then crumbles or regenerates (a Combat Achievements reward); breaking a bracelet early is
 * not counted. Pure; tags and markup are stripped by {@link GameMessageText}.
 */
final class SlayerBracelets
{
	/** No bracelet charge used. */
	static final int NONE = -1;
	/**
	 * The bracelet of slaughter: its activation means a task kill left the Slayer count unchanged, which the session
	 * still counts as activity (V2 plan 4.1, {@link SessionTracker#braceletKill}).
	 */
	static final int SLAUGHTER_BRACELET = ItemID.BRACELET_OF_SLAUGHTER;
	/** Charges in one bracelet of either kind (core ItemChargePlugin's MAX_SLAYER_BRACELET_CHARGES). */
	static final int CHARGES_PER_BRACELET = 30;
	/** The two bracelets, as recorded in the supplies: counted (and shown) in charges. */
	static final Set<Integer> BRACELETS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		ItemID.BRACELET_OF_SLAUGHTER, ItemID.EXPEDITIOUS_BRACELET)));

	/** A bracelet of slaughter activating, as core ItemChargePlugin matches it (1.13.4). */
	private static final Pattern SLAUGHTER = Pattern.compile("Your bracelet of slaughter prevents your slayer count "
		+ "from decreasing\\. (?:It has \\d{1,2} charges? left\\.|It then crumbles to dust\\.|It then regenerates itself "
		+ "to full charge!)");
	/** An expeditious bracelet activating, as core ItemChargePlugin matches it (1.13.4). */
	private static final Pattern EXPEDITIOUS = Pattern.compile("Your expeditious bracelet helps you progress your "
		+ "slayer (?:task )?faster\\. (?:It has \\d{1,2} charges? left\\.|It then crumbles to dust\\.|It then "
		+ "regenerates itself to full charge!)");

	private SlayerBracelets()
	{
	}

	/**
	 * The bracelet a game message says used a charge ({@code ItemID.BRACELET_OF_SLAUGHTER} or
	 * {@code ItemID.EXPEDITIOUS_BRACELET}), or {@link #NONE}. The message may carry colour tags. A check message ("Your
	 * bracelet of slaughter has 12 charges left.") is not a use.
	 */
	static int chargeUsed(String message)
	{
		if (message == null)
		{
			return NONE;
		}
		final String plain = GameMessageText.plain(message);
		if (SLAUGHTER.matcher(plain).find())
		{
			return ItemID.BRACELET_OF_SLAUGHTER;
		}
		if (EXPEDITIOUS.matcher(plain).find())
		{
			return ItemID.EXPEDITIOUS_BRACELET;
		}
		return NONE;
	}
}
