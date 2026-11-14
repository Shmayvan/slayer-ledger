package com.shmayvan.slayerledger;

import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;

/**
 * The task-complete chat line ({@code DESIGN_PRINCIPLES.md} 5.2, 5.3), posted for a successfully saved completed task:
 * {@code Dust devils done: 201 kills in 1 h 1 min, +412K. Gz.} Facts first: the task as the history names it
 * ({@link LedgerHistory#monster}: its Catacombs variant or Slayer boss, {@code (cannon)} for a cannon task), its kills,
 * its active time ({@link LedgerFormat#duration}), its profit at the "Loot price" mode ({@link LedgerFormat#signed},
 * {@code ~} before the sign when approximate) and its superiors when any spawned, then {@code Gz.} unless a
 * fastest-task line follows it ({@link FastestTaskMessage}), so only one line carries personality. Plain text, apart
 * from {@link #chatMessage}. Pure (RuneLite's string-only {@link ChatMessageBuilder} only).
 */
final class TaskCompleteMessage
{
	/** The line's one word of personality, after the facts. */
	static final String GZ = "Gz.";

	private TaskCompleteMessage()
	{
	}

	/** {@link #format(LedgerRecord, LootPriceMode, HiddenLoot, boolean)} with nothing hidden and {@link #GZ}. */
	static String format(LedgerRecord record, LootPriceMode mode)
	{
		return format(record, mode, HiddenLoot.NONE, true);
	}

	/**
	 * The line for a completed record in the selected loot price mode, with the loot items {@code hidden} hides left
	 * out of the profit; {@code null} for a partial or missing record. A profit not recorded is left out, as is a
	 * superior count of 0 or not recorded: {@code Wyrms done: 172 kills in 1 h 40 min, ~+2.1M, 3 superiors. Gz.}
	 *
	 * @param gz whether to end with {@link #GZ}
	 */
	static String format(LedgerRecord record, LootPriceMode mode, HiddenLoot hidden, boolean gz)
	{
		if (record == null || !record.isCompleted())
		{
			return null;
		}

		final StringBuilder line = new StringBuilder(head(record)).append(' ')
			.append(LedgerFormat.kills(record.getKills())).append(record.getKills() == 1 ? " kill" : " kills")
			.append(" in ").append(LedgerFormat.duration(record.getActiveMillis()));

		// The record was just saved, so the prices saved with it are today's.
		final HistoryPricing.Values values = HistoryPricing.value(record, mode, null, hidden);
		final Long profit = values.getProfit();
		if (profit != null)
		{
			line.append(", ").append(LedgerFormat.signed(profit, values.isProfitApproximate()));
		}

		final Integer superiors = record.getSuperiorsSpawned();
		// Most tasks have none; "0 superiors" would only make the line longer.
		if (superiors != null && superiors > 0)
		{
			line.append(", ").append(LedgerFormat.kills(superiors)).append(superiors == 1 ? " superior" : " superiors");
		}
		line.append('.');
		if (gz)
		{
			line.append(' ').append(GZ);
		}
		return line.toString();
	}

	/** The line's head, the task as the history names it: {@code Dust devils done:}. */
	static String head(LedgerRecord record)
	{
		return LedgerHistory.monster(record) + " done:";
	}

	/**
	 * A line from {@link #format} as a RuneLite chat message ({@code DESIGN_PRINCIPLES.md} 4.5): its {@link #head} in
	 * the player's chat highlight colour and the rest in the normal one, as the assignment line
	 * ({@link TaskPlanText#chatMessage}). {@link ChatMessageBuilder#append(String)} escapes the plain text, so nothing
	 * is escaped before and no text goes through its unescaped {@code append(Color, String)}.
	 */
	static String chatMessage(LedgerRecord record, String line)
	{
		final String head = head(record);
		final ChatMessageBuilder message = new ChatMessageBuilder();
		if (line.startsWith(head))
		{
			message.append(ChatColorType.HIGHLIGHT).append(head).append(ChatColorType.NORMAL)
				.append(line.substring(head.length()));
		}
		else
		{
			message.append(ChatColorType.NORMAL).append(line);
		}
		return message.build();
	}
}
