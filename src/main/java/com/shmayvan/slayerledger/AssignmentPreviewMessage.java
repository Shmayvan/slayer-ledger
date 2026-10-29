package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.List;

/**
 * Formats the chat preview of a newly assigned task from its completed-task history: the History estimate
 * ({@link TaskStatistics#estimate}) and What to bring's bank trips ({@link SupplyRecommendation#forTask}) for the kills
 * left, from the same statistics the panel shows. Pure; no RuneLite types.
 */
final class AssignmentPreviewMessage
{
	private AssignmentPreviewMessage()
	{
	}

	/**
	 * {@code Abyssal demons (172): ~1:40:00 · +1.2M (GE) · ~2 bank trips (from 3 tasks)}: the task, the kills left, then
	 * the estimated active time, profit (loot in {@code mode}, labelled with it, minus supplies; an approximate one as
	 * {@code ~+100} / {@code ~-500}, as the task-complete summary) and bank trips. A part the history doesn't have is
	 * left out. The sample count says where they are from when the statistics are limited to one location
	 * ({@code from 1 task at Smoke Dungeon}).
	 *
	 * @param task  the task's display name ({@link LedgerRecord#displayName})
	 * @param kills the kills left
	 * @param stats the task's statistics, as the panel's History estimate uses them
	 * @return the line, or {@code null} when there is no completed history (or no task, or no kills left)
	 */
	static String format(String task, int kills, TaskStatistics stats, LootPriceMode mode)
	{
		if (task == null || stats == null || kills <= 0)
		{
			return null;
		}
		final TaskStatistics.Estimate estimate = stats.estimate(kills);
		if (estimate == null)
		{
			return null;
		}

		final List<String> parts = new ArrayList<>();
		if (estimate.getActiveMillis() != null)
		{
			parts.add(LedgerHistory.APPROXIMATE + LedgerHistory.formatDuration(estimate.getActiveMillis()));
		}
		final Long loot = estimate.getLootValue();
		final Long supplies = estimate.getSuppliesValue();
		if (loot != null && supplies != null)
		{
			final long profit = saturatedSubtract(loot, supplies);
			final boolean approximate = estimate.isLootApproximate() || estimate.isSuppliesApproximate();
			parts.add((approximate ? LedgerHistory.APPROXIMATE : "") + (profit >= 0 ? "+" : "")
				+ LedgerHistory.formatAmount(profit, false) + " (" + LedgerHistory.priceLabel(mode) + ")");
		}
		final SupplyRecommendation.Estimate bring = SupplyRecommendation.forTask(stats, kills);
		final Long trips = bring == null ? null : bring.getBankTrips();
		if (trips != null)
		{
			// As What to bring's header.
			parts.add(trips == 0 ? "No bank trips" : LedgerHistory.APPROXIMATE + LedgerHistory.formatAmount(trips, false)
				+ (trips == 1 ? " bank trip" : " bank trips"));
		}

		final int samples = estimate.getSampleCount();
		final String from = "(from " + samples + (samples == 1 ? " task" : " tasks")
			+ (stats.getLocation() == null ? "" : " at " + stats.getLocation()) + ")";
		final String head = task + " (" + kills + "):";
		return parts.isEmpty() ? head + " " + from : head + " " + String.join(" · ", parts) + " " + from;
	}

	private static long saturatedSubtract(long left, long right)
	{
		try
		{
			return Math.subtractExact(left, right);
		}
		catch (ArithmeticException e)
		{
			return right < 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
		}
	}
}
