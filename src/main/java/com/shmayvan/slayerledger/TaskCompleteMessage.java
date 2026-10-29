package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.List;

/** Formats the chat summary for a successfully saved completed task. */
final class TaskCompleteMessage
{
	private static final String PREFIX = "Slayer task complete: ";

	private TaskCompleteMessage()
	{
	}

	/**
	 * Formats a completed record in the selected loot price mode. Values not present in the record are omitted, as is a
	 * superior count of 0, and an approximate profit puts the sign after the {@code ~} ({@code ~+100}, {@code ~-500}).
	 */
	static String format(LedgerRecord record, LootPriceMode mode)
	{
		return format(record, mode, HiddenLoot.NONE);
	}

	/**
	 * As {@link #format(LedgerRecord, LootPriceMode)}, with the loot items {@code hidden} hides left out of the profit
	 *.
	 */
	static String format(LedgerRecord record, LootPriceMode mode, HiddenLoot hidden)
	{
		if (record == null || !record.isCompleted())
		{
			return null;
		}

		List<String> parts = new ArrayList<>();
		parts.add(record.getKills() + " " + LedgerHistory.monster(record));
		parts.add(LedgerHistory.formatDuration(record.getActiveMillis()));

		// The record was just saved, so the prices saved with it are today's.
		HistoryPricing.Values values = HistoryPricing.value(record, mode, null, hidden);
		Long profit = values.getProfit();
		if (profit != null)
		{
			String amount = LedgerHistory.formatAmount(profit, false);
			parts.add((values.isProfitApproximate() ? LedgerHistory.APPROXIMATE : "") + (profit >= 0 ? "+" : "") + amount);
		}

		Integer superiors = record.getSuperiorsSpawned();
		// Most tasks have none; "0 superiors" would only make the line longer.
		if (superiors != null && superiors > 0)
		{
			parts.add(superiors + (superiors == 1 ? " superior" : " superiors"));
		}

		return PREFIX + String.join(" · ", parts);
	}
}
