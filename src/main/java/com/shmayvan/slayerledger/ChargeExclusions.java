package com.shmayvan.slayerledger;

import java.util.*;
import java.util.regex.*;
import net.runelite.client.util.*;

/**
 * Keeps charging and emptying the blowpipe, a trident or a powered staff (Sanguinesti staff, Tumeken's shadow) out of
 * the supplies diff, so materials aren't counted twice ({@link ChargedWeaponUsage} already counts them per attack).
 * Pure (RuneLite's static {@link Text} only): the plugin passes in menu clicks and messages with the tick it saw them
 * on, and asks before each supplies diff. Not thread-safe.
 *
 * <p>Without it, the inventory diff would count darts loaded into the blowpipe and runes, coins or scales used to
 * charge a trident or staff as used, and darts or runes coming back out (unloading, uncharging, swapping the dart type)
 * would reduce what was counted.</p>
 *
 * <p>Rules:</p>
 * <ul>
 *     <li>A window opens on the tick the player clicks "Use" with one of the {@link #WEAPONS} involved (a material
 *     used on a weapon, or a weapon on a material), or "Charge", "Unload" or "Uncharge" on a weapon.</li>
 *     <li>Only the {@link #MATERIALS} (darts, Zulrah's scales, death, chaos, fire, blood, soul and aether runes, coins)
 *     are excluded while it is open; other items count as usual.</li>
 *     <li>It closes {@link #CONFIRM_GRACE_TICKS} ticks after a confirming message or dialog text
 *     ({@link #isConfirmation}), so items that only move on the next tick are still excluded, or
 *     {@link #TIMEOUT_TICKS} ticks after it opened, whichever is first. The timeout is long because charging a trident
 *     opens a number input that can stay open while the player decides, and some actions (unloading darts,
 *     uncharging the blowpipe) print no message. A new click re-opens the window from its tick.</li>
 * </ul>
 */
final class ChargeExclusions
{
	/** Ticks (30 seconds) a window stays open without a confirming message. */
	static final int TIMEOUT_TICKS = 50;
	/** Ticks a window stays open after the confirming message. */
	static final int CONFIRM_GRACE_TICKS = 2;
	/** Far enough in the past that no window includes it, without overflowing tick arithmetic. */
	static final int NEVER_TICK = Integer.MIN_VALUE / 2;

	/**
	 * Blowpipes (empty and loaded) and tridents (charged and uncharged), with their ornament and (e) versions; the
	 * Sanguinesti staff and holy Sanguinesti staff and Tumeken's shadow, charged and uncharged. Read from the bundled
	 * {@code charge-exclusions.json} ({@link BundledData}), as is {@link #MATERIALS}; empty when it can't be read.
	 */
	static final Set<Integer> WEAPONS = BundledData.idSet("charge-exclusions.json", "weapons");

	/** What the weapons are charged or loaded with (aether runes can charge Tumeken's shadow in place of soul runes). */
	static final Set<Integer> MATERIALS = BundledData.idSet("charge-exclusions.json", "materials");

	/**
	 * Messages and dialog texts (tags removed) that end a charge, load, unload or uncharge, as recorded by
	 * geheur/weapon-charges: the blowpipe check message (shown after adding darts or scales), a full or empty
	 * blowpipe, the trident's charge dialogs, and the powered staves' "You apply (an additional) N charges to your ..."
	 * and "You uncharge your ..., regaining ..." dialogs. Removing the tags also removes a {@code <br>}, joining the
	 * words either side of it, so the staves' patterns stop before the staff's name.
	 */
	private static final Pattern[] CONFIRMATIONS = {
		Pattern.compile("^Darts: .*\\. Scales: .*\\.$"),
		Pattern.compile("^The blowpipe can't hold any more (?:darts|scales)\\.$"),
		Pattern.compile("^The blowpipe has no darts in it\\.$"),
		Pattern.compile("^You add (?:a charge|[\\d,]+ charges) to the weapon\\..*$"),
		Pattern.compile("^Your weapon is already fully charged\\.$"),
		// The Sanguinesti staff (regular or holy) refusing more runes, as geheur/weapon-charges records it.
		Pattern.compile("^Your (?:[Hh]oly s|S)anguinesti staff is already fully charged\\.$"),
		Pattern.compile("^You uncharge your weapon\\.$"),
		Pattern.compile("^You apply (?:an additional )?[\\d,]+ charges? to your.*$"),
		Pattern.compile("^You uncharge your .+, regaining .*$"),
	};

	/** Tick the window opened. */
	private int openTick = NEVER_TICK;
	/** Last tick the window covers. */
	private int endTick = NEVER_TICK;

	/**
	 * A menu option was clicked on {@code tick}.
	 *
	 * @param option  the option text, possibly with tags ("Use", "Charge", "Unload", "Uncharge")
	 * @param itemIds the items involved: the clicked item and, for "Use", the item used on it
	 */
	void menuOptionClicked(String option, int[] itemIds, int tick)
	{
		if (option == null || itemIds == null || !involvesWeapon(itemIds))
		{
			return;
		}
		switch (Text.removeTags(option).trim().toLowerCase(Locale.ROOT))
		{
			case "use":
			case "charge":
			case "unload":
			case "uncharge":
				openTick = tick;
				endTick = tick + TIMEOUT_TICKS;
				break;
			default:
				break;
		}
	}

	private static boolean involvesWeapon(int[] itemIds)
	{
		for (int itemId : itemIds)
		{
			if (WEAPONS.contains(itemId))
			{
				return true;
			}
		}
		return false;
	}

	/** Whether a message or dialog text (possibly with tags) confirms a charge, load, unload or uncharge. */
	static boolean isConfirmation(String text)
	{
		if (text == null)
		{
			return false;
		}
		final String plain = GameMessageText.plain(text);
		for (Pattern confirmation : CONFIRMATIONS)
		{
			if (confirmation.matcher(plain).matches())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * A game message or dialog text was seen on {@code tick}; a confirmation closes an open window after
	 * {@link #CONFIRM_GRACE_TICKS} more ticks.
	 */
	void messageSeen(String text, int tick)
	{
		if (isOpen(tick) && isConfirmation(text))
		{
			endTick = Math.min(endTick, tick + CONFIRM_GRACE_TICKS);
		}
	}

	private boolean isOpen(int tick)
	{
		return tick >= openTick && tick <= endTick;
	}

	/** The item ids left out of the supplies diff on {@code tick} (empty if no window is open). */
	Set<Integer> excludedItems(int tick)
	{
		return isOpen(tick) ? MATERIALS : Collections.emptySet();
	}

	/**
	 * Forgets the window (login, hop, lost connection, plugin start). A tick before the window opened (the tick count
	 * restarted) is outside it anyway ({@link #isOpen}), so a window never outlasts {@link #TIMEOUT_TICKS}.
	 */
	void reset()
	{
		openTick = NEVER_TICK;
		endTick = NEVER_TICK;
	}
}
