package com.shmayvan.slayerledger;

import java.util.*;
import net.runelite.client.chat.*;
import net.runelite.client.util.*;

/**
 * The notable drop chat line ({@code DESIGN_PRINCIPLES.md} 5.2, 5.3): {@code Dragon chainbody (148K) from a dust
 * devil.} or {@code Abyssal whip (1.4M) at kill 140 of 201.} Pure (RuneLite's static {@link Text} and string-only
 * {@link ChatMessageBuilder} only).
 *
 * <p>Rules (decided by the project manager under the owner's standing OK, 2026-11-09):</p>
 * <ul>
 *     <li>A notable drop is a loot item stack from one kill worth at least the "Notable drop value" setting at the
 *     "Loot price" setting ({@link NotableDrops}). A kill with several gets one line, for the most valuable
 *     ({@link #pick}); a pet gets none (the game announces it).</li>
 *     <li>The line is the item, {@code x25} when the stack is more than one, and its value ({@link LedgerFormat#amount}),
 *     then one of two variants, chosen by the task's kill number so the choice is fixed: on an even kill number,
 *     {@code at kill 140 of 201} (the task's kills done of its assigned size, as the overlay's bar counts them); on an
 *     odd one, or with the kill number unknown, {@code from a dust devil} (the NPC that dropped it, lower case with
 *     {@code a} or {@code an}; a Slayer boss by its own name, {@code from Cerberus}). Without the NPC's name the kill
 *     variant is used when the kill number is known, else the item and value alone.</li>
 *     <li>It ends with {@link #PAID_FOR} only when the drop's value covers the session's supply cost so far
 *     ({@link #paysForTask}): a known, exact cost above 0. The plugin adds it once per task (session), on the first
 *     line it is true for.</li>
 *     <li>An item Ground Items' hidden list hides from the loot value (all but coins) gets no line; the plugin leaves it
 *     out before {@link #pick}, so the kill's next most valuable notable drop, if any, gets the line instead.</li>
 * </ul>
 */
final class NotableDropMessage
{
	/** The one flavour clause, used only when it is true ({@link #paysForTask}). */
	static final String PAID_FOR = "That's the task paid for.";

	private NotableDropMessage()
	{
	}

	/**
	 * The drop to announce from one kill's notable drops: the most valuable item at {@code mode} with a known name and
	 * value (the first of equals); {@code null} when there is none (no drops, or only pets).
	 */
	static NotableDrop pick(List<NotableDrop> drops, LootPriceMode mode)
	{
		NotableDrop best = null;
		if (drops == null)
		{
			return null;
		}
		for (NotableDrop drop : drops)
		{
			if (drop == null || drop.isPet() || drop.getName() == null || drop.getName().trim().isEmpty()
				|| drop.value(mode) == null)
			{
				continue;
			}
			if (best == null || drop.value(mode) > best.value(mode))
			{
				best = drop;
			}
		}
		return best;
	}

	/**
	 * Whether a drop worth {@code dropValue} pays for the task so far: the session's supply cost
	 * ({@code suppliesCost}, GE) is known exactly, above 0 and no more than the drop's value.
	 *
	 * @param suppliesCost the supply cost so far, or {@code null} when unknown or approximate (a price missing)
	 */
	static boolean paysForTask(long dropValue, Long suppliesCost)
	{
		return suppliesCost != null && suppliesCost > 0 && dropValue >= suppliesCost;
	}

	/**
	 * The line for {@code drop} (see the class rules), or {@code null} when it has no name or no value at
	 * {@code mode}.
	 *
	 * @param npcName    the name of the NPC it came from, or {@code null} when unknown
	 * @param killNumber the task's kills done when it dropped, or 0 when unknown
	 * @param taskSize   the task's assigned size, or 0 when unknown
	 * @param paidFor    whether to end with {@link #PAID_FOR} ({@link #paysForTask})
	 */
	static String line(NotableDrop drop, LootPriceMode mode, String npcName, int killNumber, int taskSize,
		boolean paidFor)
	{
		final String item = item(drop, mode);
		if (item == null)
		{
			return null;
		}
		final String source = source(npcName);
		final boolean killKnown = killNumber > 0 && taskSize >= killNumber;
		final String where;
		if (killKnown && (killNumber % 2 == 0 || source == null))
		{
			where = " at kill " + LedgerFormat.kills(killNumber) + " of " + LedgerFormat.kills(taskSize);
		}
		else
		{
			where = source == null ? "" : " from " + source;
		}
		return item + where + "." + (paidFor ? " " + PAID_FOR : "");
	}

	/**
	 * The line ({@link #line}) as a RuneLite chat message ({@code DESIGN_PRINCIPLES.md} 4.5): the item and its value in
	 * the player's chat highlight colour and the rest in the normal one, built with the escaping
	 * {@link ChatMessageBuilder#append(String)}, so a name can't add tags; {@code null} without a line.
	 */
	static String chatMessage(NotableDrop drop, LootPriceMode mode, String npcName, int killNumber, int taskSize,
		boolean paidFor)
	{
		final String line = line(drop, mode, npcName, killNumber, taskSize, paidFor);
		if (line == null)
		{
			return null;
		}
		final String item = item(drop, mode);
		return new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append(item)
			.append(ChatColorType.NORMAL)
			.append(line.substring(item.length()))
			.build();
	}

	/** {@code Dragon chainbody (148K)} or {@code Rune bar x25 (125K)}; {@code null} without a name or value. */
	static String item(NotableDrop drop, LootPriceMode mode)
	{
		final Long value = drop == null ? null : drop.value(mode);
		final String name = drop == null || drop.getName() == null ? null : drop.getName().trim();
		if (value == null || name == null || name.isEmpty())
		{
			return null;
		}
		return name + (drop.getQuantity() > 1 ? " x" + LedgerFormat.kills(drop.getQuantity()) : "") + " ("
			+ LedgerFormat.amount(value) + ")";
	}

	/**
	 * Who dropped it, for {@code from ...}: a Slayer boss by its own name ({@code Cerberus}), any other NPC in lower
	 * case with its article ({@code a dust devil}, {@code an abyssal demon}); {@code null} without a name.
	 */
	static String source(String npcName)
	{
		final String name = npcName == null ? null : Text.removeTags(npcName).trim();
		if (name == null || name.isEmpty())
		{
			return null;
		}
		if (SlayerBosses.isBossNpcName(name))
		{
			return name;
		}
		final String lower = name.toLowerCase(Locale.ROOT);
		return ("aeiou".indexOf(lower.charAt(0)) >= 0 ? "an " : "a ") + lower;
	}
}
