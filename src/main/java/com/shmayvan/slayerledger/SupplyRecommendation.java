package com.shmayvan.slayerledger;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Suggested supplies and bank trips for the kills left on a task, from the completed-task history of
 * {@link TaskStatistics}. Each amount is a pooled total per kill scaled to the kills and rounded up to a whole tracked
 * unit (item, dose, bite or charge), in whole numbers so no floating-point error adds a unit.
 */
final class SupplyRecommendation
{
	private SupplyRecommendation()
	{
	}

	/**
	 * Scales the pooled supply totals to {@code kills}: {@code ceil(total * kills / supplyKills)} per item, over the
	 * records with supplies or values. Bank trips are pooled the same way, over the kills of the records with a known
	 * count. Returns {@code null} when neither has a sample; a metric without samples stays unknown.
	 */
	static Estimate forTask(TaskStatistics stats, int kills)
	{
		if (stats == null || kills <= 0)
		{
			return null;
		}

		Map<Integer, Long> supplies = new TreeMap<>();
		long supplyKills = stats.getSupplyKills();
		boolean suppliesKnown = stats.getSupplySampleCount() > 0 && supplyKills > 0;
		if (suppliesKnown)
		{
			for (Map.Entry<Integer, Long> item : stats.getSupplyTotals().entrySet())
			{
				supplies.put(item.getKey(), scaleUp(item.getValue(), kills, supplyKills));
			}
		}

		long bankTripKills = stats.getBankTripKills();
		Long bankTrips = stats.getBankTripSampleCount() > 0 && bankTripKills > 0
			? scaleUp(stats.getBankTripTotal(), kills, bankTripKills) : null;
		if (!suppliesKnown && bankTrips == null)
		{
			return null;
		}
		return new Estimate(kills, supplies, suppliesKnown ? stats.getSupplySampleCount() : 0, supplyKills,
			bankTrips, stats.getBankTripSampleCount(), bankTripKills);
	}

	/**
	 * {@code ceil(total * kills / sampleKills)} in exact integer arithmetic, saturating at {@link Long#MAX_VALUE}
	 * when the product overflows; 0 for a non-positive total.
	 */
	static long scaleUp(long total, long kills, long sampleKills)
	{
		if (total <= 0 || kills <= 0 || sampleKills <= 0)
		{
			return 0;
		}
		long product;
		try
		{
			product = Math.multiplyExact(total, kills);
		}
		catch (ArithmeticException e)
		{
			return Long.MAX_VALUE;
		}
		long quotient = product / sampleKills;
		return product % sampleKills == 0 ? quotient : quotient + 1;
	}

	static final class Estimate
	{
		private final int kills;
		private final Map<Integer, Long> supplies;
		private final int supplySampleCount;
		private final long supplyKills;
		private final Long bankTrips;
		private final int bankTripSampleCount;
		private final long bankTripKills;

		private Estimate(int kills, Map<Integer, Long> supplies, int supplySampleCount, long supplyKills,
			Long bankTrips, int bankTripSampleCount, long bankTripKills)
		{
			this.kills = kills;
			this.supplies = Collections.unmodifiableMap(new TreeMap<>(supplies));
			this.supplySampleCount = supplySampleCount;
			this.supplyKills = supplyKills;
			this.bankTrips = bankTrips;
			this.bankTripSampleCount = bankTripSampleCount;
			this.bankTripKills = bankTripKills;
		}

		/** The kills the amounts are for (the kills left). */
		int getKills()
		{
			return kills;
		}

		/** Suggested amount by item id, in the id's tracked unit; empty when the history used no tracked supplies. */
		Map<Integer, Long> getSupplies()
		{
			return supplies;
		}

		/** Records behind the supply amounts; 0 when none has supplies or values (the amounts are unknown). */
		int getSupplySampleCount()
		{
			return supplySampleCount;
		}

		/** Kills of the records behind the supply amounts. */
		long getSupplyKills()
		{
			return supplyKills;
		}

		/** Suggested bank trips, or {@code null} when no record has a known count. */
		Long getBankTrips()
		{
			return bankTrips;
		}

		int getBankTripSampleCount()
		{
			return bankTripSampleCount;
		}

		/** Kills of the records behind the bank-trip suggestion. */
		long getBankTripKills()
		{
			return bankTripKills;
		}
	}
}
