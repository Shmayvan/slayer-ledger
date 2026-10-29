package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/** Per-task historical averages, calculated from saved completed records only. */
final class TaskStatistics
{
	private final int sampleCount;
	private final long totalKills;
	private final long activeMillis;
	private final long valuedKills;
	private final long lootValue;
	private final long suppliesValue;
	private final boolean lootApproximate;
	private final boolean suppliesApproximate;
	private final long supplyKills;
	private final int supplySampleCount;
	private final Map<Integer, Long> supplies;
	private final Map<Integer, Integer> supplyDoses;
	private final Map<Integer, Long> supplyUnitGe;
	private final long bankTrips;
	private final long bankTripKills;
	private final int bankTripSampleCount;
	private final long xpKills;
	private final long xpActiveMillis;
	private final Map<String, Long> xp;
	/** The location the samples were limited to (Part B), or {@code null} when every location counts. */
	private final String location;
	/** How many of the samples are cannon tasks ({@link LedgerRecord#cannonTask}). */
	private final int cannonSampleCount;

	private TaskStatistics(Totals t)
	{
		this.location = t.location;
		this.sampleCount = t.sampleCount;
		this.cannonSampleCount = t.cannonSampleCount;
		this.totalKills = t.totalKills;
		this.activeMillis = t.activeMillis;
		this.valuedKills = t.valuedKills;
		this.lootValue = t.lootValue;
		this.suppliesValue = t.suppliesValue;
		this.lootApproximate = t.lootApproximate;
		this.suppliesApproximate = t.suppliesApproximate;
		this.supplyKills = t.supplyKills;
		this.supplySampleCount = t.supplySampleCount;
		this.supplies = Collections.unmodifiableMap(new TreeMap<>(t.supplies));
		this.supplyDoses = Collections.unmodifiableMap(new TreeMap<>(t.supplyDoses));
		this.supplyUnitGe = Collections.unmodifiableMap(new TreeMap<>(t.supplyUnitGe));
		this.bankTrips = t.bankTrips;
		this.bankTripKills = t.bankTripKills;
		this.bankTripSampleCount = t.bankTripSampleCount;
		this.xpKills = t.xpKills;
		this.xpActiveMillis = t.xpActiveMillis;
		this.xp = Collections.unmodifiableMap(new TreeMap<>(t.xp));
	}

	/** The running totals {@link #aggregate} adds the samples into; a new one is the empty statistics. */
	private static final class Totals
	{
		private int sampleCount;
		private int cannonSampleCount;
		private long totalKills;
		private long activeMillis;
		private long valuedKills;
		private long lootValue;
		private long suppliesValue;
		private boolean lootApproximate;
		private boolean suppliesApproximate;
		private long supplyKills;
		private int supplySampleCount;
		private final Map<Integer, Long> supplies = new TreeMap<>();
		private final Map<Integer, Integer> supplyDoses = new TreeMap<>();
		private final Map<Integer, Long> supplyUnitGe = new TreeMap<>();
		private long bankTrips;
		private long bankTripKills;
		private int bankTripSampleCount;
		private long xpKills;
		private long xpActiveMillis;
		private final Map<String, Long> xp = new TreeMap<>();
		private String location;
	}

	/**
	 * Aggregates records matching the current task. Per-kill values are pooled across their available kills; task
	 * duration is the arithmetic mean of task samples. Outliers are not removed.
	 *
	 * @param currentGe today's GE unit prices the records are valued at ({@link HistoryPricing}); never {@code null}
	 */
	static TaskStatistics forTask(Collection<LedgerRecord> records, int creatureId, String monster,
		LootPriceMode mode, Map<Integer, Long> currentGe)
	{
		return aggregate(records, creatureId, monster, TaskSelection.ANY, mode, currentGe, HiddenLoot.NONE, null);
	}

	/**
	 * Same location first (Part B): when {@code location} is known and at least one sample ({@link #isSample}) is
	 * at that location ({@link TaskLocation#sameLocation} with {@code recordLocation}'s name for it), only those samples
	 * count and {@link #getLocation} is the location; otherwise every sample counts, as
	 * {@link #forTask(Collection, int, String, LootPriceMode, Map)}.
	 *
	 * @param location       the current task's location, or {@code null} while unknown
	 * @param recordLocation a saved record's location ({@link TaskLocation#recordLocation}), {@code null} when unknown
	 */
	static TaskStatistics forTask(Collection<LedgerRecord> records, int creatureId, String monster, String location,
		Function<LedgerRecord, String> recordLocation, LootPriceMode mode, Map<Integer, Long> currentGe)
	{
		return forTask(records, creatureId, monster, TaskSelection.ANY, location, recordLocation, mode, currentGe,
			HiddenLoot.NONE);
	}

	/**
	 * As {@link #forTask(Collection, int, String, String, Function, LootPriceMode, Map)}, with the loot items
	 * {@code hidden} hides left out of the loot values ({@link HistoryPricing}).
	 */
	static TaskStatistics forTask(Collection<LedgerRecord> records, int creatureId, String monster, String location,
		Function<LedgerRecord, String> recordLocation, LootPriceMode mode, Map<Integer, Long> currentGe,
		HiddenLoot hidden)
	{
		return forTask(records, creatureId, monster, TaskSelection.ANY, location, recordLocation, mode, currentGe,
			hidden);
	}

	/**
	 * As {@link #forTask(Collection, int, String, String, Function, LootPriceMode, Map, HiddenLoot)}, counting only the
	 * records of the task {@code selection} selects: its version and whether a cannon was used.
	 * {@link TaskSelection#ANY} pools the standard monster and its Catacombs variant, with and without a cannon, as
	 * before they were told apart.
	 */
	static TaskStatistics forTask(Collection<LedgerRecord> records, int creatureId, String monster,
		TaskSelection selection, String location, Function<LedgerRecord, String> recordLocation, LootPriceMode mode,
		Map<Integer, Long> currentGe, HiddenLoot hidden)
	{
		final String here = TaskLocation.normalizeName(location);
		if (here != null && records != null && recordLocation != null)
		{
			List<LedgerRecord> atLocation = new ArrayList<>();
			for (LedgerRecord record : records)
			{
				if (isSample(record, creatureId, monster, selection)
					&& TaskLocation.sameLocation(here, recordLocation.apply(record)))
				{
					atLocation.add(record);
				}
			}
			if (!atLocation.isEmpty())
			{
				return aggregate(atLocation, creatureId, monster, selection, mode, currentGe, hidden, here);
			}
		}
		return aggregate(records, creatureId, monster, selection, mode, currentGe, hidden, null);
	}

	/**
	 * Whether {@code record} is a sample of the task: completed, not partial, with kills, and the same creature (either
	 * version, standard or Catacombs variant, with or without a cannon).
	 */
	static boolean isSample(LedgerRecord record, int creatureId, String monster)
	{
		return isSample(record, creatureId, monster, TaskSelection.ANY);
	}

	/**
	 * As {@link #isSample(LedgerRecord, int, String)}, and of the version and cannon use {@code selection} selects
	 *.
	 */
	static boolean isSample(LedgerRecord record, int creatureId, String monster, TaskSelection selection)
	{
		return record != null && record.isCompleted() && !record.isPartial() && record.getKills() > 0
			&& matches(record, creatureId, monster) && (selection == null || selection.matches(record));
	}

	/**
	 * The selection the live task's statistics use for {@code selection} (owner decision 2026-10-28): when it
	 * asks for cannon tasks only but {@code records} hold no {@link #isSample sample} of the task that is a cannon task
	 * of its version (at any location), tasks with and without a cannon pooled ({@code selection} without its cannon
	 * limit), as before the cannonball; otherwise {@code selection} itself. A {@code null} selection is
	 * {@link TaskSelection#ANY}.
	 */
	static TaskSelection effectiveSelection(Collection<LedgerRecord> records, int creatureId, String monster,
		TaskSelection selection)
	{
		TaskSelection requested = selection == null ? TaskSelection.ANY : selection;
		if (!Boolean.TRUE.equals(requested.getCannon()))
		{
			return requested;
		}
		if (records != null)
		{
			for (LedgerRecord record : records)
			{
				if (isSample(record, creatureId, monster, requested))
				{
					return requested;
				}
			}
		}
		return requested.withCannon(null);
	}

	private static TaskStatistics aggregate(Collection<LedgerRecord> records, int creatureId, String monster,
		TaskSelection selection, LootPriceMode mode, Map<Integer, Long> currentGe, HiddenLoot hidden, String location)
	{
		Objects.requireNonNull(currentGe, "currentGe");
		if (records == null)
		{
			return empty();
		}

		final Totals t = new Totals();
		t.location = location;

		for (LedgerRecord record : records)
		{
			if (!isSample(record, creatureId, monster, selection))
			{
				continue;
			}

			long recordKills = record.getKills();
			t.sampleCount++;
			if (record.cannonTask())
			{
				t.cannonSampleCount++;
			}
			t.totalKills = saturatedAdd(t.totalKills, recordKills);
			t.activeMillis = saturatedAdd(t.activeMillis, Math.max(0, record.getActiveMillis()));

			if (LedgerHistory.isValued(record))
			{
				HistoryPricing.Values values = HistoryPricing.value(record, mode, currentGe, hidden);
				if (values.getLootValue() != null && values.getSuppliesGe() != null)
				{
					t.valuedKills = saturatedAdd(t.valuedKills, recordKills);
					t.lootValue = saturatedAdd(t.lootValue, values.getLootValue());
					t.suppliesValue = saturatedAdd(t.suppliesValue, values.getSuppliesGe());
					t.lootApproximate |= values.isLootApproximate();
					t.suppliesApproximate |= values.isSuppliesApproximate();
				}
			}

			Map<Integer, Long> recordSupplies = record.getSupplies();
			if (recordSupplies != null || LedgerHistory.isValued(record))
			{
				t.supplyKills = saturatedAdd(t.supplyKills, recordKills);
				t.supplySampleCount++;
				if (recordSupplies != null)
				{
					for (Map.Entry<Integer, Long> item : recordSupplies.entrySet())
					{
						if (item.getKey() != null && item.getValue() != null && item.getValue() > 0)
						{
							t.supplies.merge(item.getKey(), item.getValue(), TaskStatistics::saturatedAdd);
							Integer doses = record.getSupplyDoses() == null
								? null : record.getSupplyDoses().get(item.getKey());
							if (doses != null && doses > 0)
							{
								t.supplyDoses.put(item.getKey(), doses);
							}
						}
					}
				}
			}

			if (record.getBankTrips() != null && record.getBankTrips() >= 0)
			{
				t.bankTrips = saturatedAdd(t.bankTrips, record.getBankTrips());
				t.bankTripKills = saturatedAdd(t.bankTripKills, recordKills);
				t.bankTripSampleCount++;
			}

			Map<String, Long> recordXp = record.getXpGained();
			if (recordXp != null)
			{
				t.xpKills = saturatedAdd(t.xpKills, recordKills);
				t.xpActiveMillis = saturatedAdd(t.xpActiveMillis, Math.max(0, record.getActiveMillis()));
				for (Map.Entry<String, Long> skill : recordXp.entrySet())
				{
					if (skill.getKey() != null && skill.getValue() != null && skill.getValue() > 0)
					{
						t.xp.merge(skill.getKey(), skill.getValue(), TaskStatistics::saturatedAdd);
					}
				}
			}
		}

		// Today's prices; an item without one has no price, as in the history.
		for (Integer id : t.supplies.keySet())
		{
			Long price = currentGe.get(id);
			if (price != null && price > 0)
			{
				t.supplyUnitGe.put(id, price);
			}
		}

		return new TaskStatistics(t);
	}

	private static boolean matches(LedgerRecord record, int creatureId, String monster)
	{
		Integer recordCreatureId = record.getCreatureId();
		if (creatureId != SessionTracker.NO_CREATURE && recordCreatureId != null)
		{
			return recordCreatureId == creatureId;
		}
		String recordMonster = normalize(record.getMonster());
		return recordMonster != null && recordMonster.equals(normalize(monster));
	}

	/**
	 * The one monster-name key every feature groups and matches by (task statistics, Trends ranking and chart, luck
	 * meter, Wiki task locations): trimmed and lower case in {@link Locale#ROOT}, so the key never depends on the
	 * client's default locale. {@code null} for a {@code null} or blank name, which matches nothing.
	 */
	static String normalize(String value)
	{
		if (value == null)
		{
			return null;
		}
		String name = value.trim();
		return name.isEmpty() ? null : name.toLowerCase(Locale.ROOT);
	}

	private static TaskStatistics empty()
	{
		return new TaskStatistics(new Totals());
	}

	private static long saturatedAdd(long left, long right)
	{
		try
		{
			return Math.addExact(left, right);
		}
		catch (ArithmeticException e)
		{
			return right >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
		}
	}

	int getSampleCount()
	{
		return sampleCount;
	}

	/** How many of the {@link #getSampleCount samples} are cannon tasks. */
	int getCannonSampleCount()
	{
		return cannonSampleCount;
	}

	/**
	 * The location the samples are limited to ({@link TaskLocation#normalizeName}d, as the current task's location was
	 * given), or {@code null} when they are from every location.
	 */
	String getLocation()
	{
		return location;
	}

	long getAverageTaskDurationMillis()
	{
		return sampleCount == 0 ? 0 : Math.round(activeMillis / (double) sampleCount);
	}

	Long getMillisPerKill()
	{
		return totalKills == 0 ? null : Math.round(activeMillis / (double) totalKills);
	}

	Long getLootPerKill()
	{
		return valuedKills == 0 ? null : Math.round(lootValue / (double) valuedKills);
	}

	Long getSuppliesValuePerKill()
	{
		return valuedKills == 0 ? null : Math.round(suppliesValue / (double) valuedKills);
	}

	/**
	 * Profit per kill: {@link #getLootPerKill} minus {@link #getSuppliesValuePerKill}, so the three tooltip
	 * lines add up; loot in the Loot price mode, supplies at GE. {@code null} when either part is unknown.
	 */
	Long getProfitPerKill()
	{
		Long loot = getLootPerKill();
		Long supplies = getSuppliesValuePerKill();
		return loot == null || supplies == null ? null : LedgerHistory.profit(loot, supplies);
	}

	boolean isLootApproximate()
	{
		return lootApproximate;
	}

	boolean isSuppliesApproximate()
	{
		return suppliesApproximate;
	}

	/** Whether {@link #getProfitPerKill} is approximate: either part left out an item without a price. */
	boolean isProfitApproximate()
	{
		return lootApproximate || suppliesApproximate;
	}

	Map<Integer, Double> getSuppliesPerKill()
	{
		Map<Integer, Double> result = new TreeMap<>();
		if (supplyKills > 0)
		{
			supplies.forEach((id, quantity) -> result.put(id, quantity / (double) supplyKills));
		}
		return Collections.unmodifiableMap(result);
	}

	Map<Integer, Integer> getSupplyDoses()
	{
		return supplyDoses;
	}

	/**
	 * Pooled supply totals by item id (doses, bites or charges for the {@link #getSupplyDoses} ids), over the
	 * {@link #getSupplyKills} of the records with supplies or values; the exact sums behind {@link #getSuppliesPerKill}.
	 */
	Map<Integer, Long> getSupplyTotals()
	{
		return supplies;
	}

	/** Kills of the records that count for the supply averages (with supplies or values). */
	long getSupplyKills()
	{
		return supplyKills;
	}

	/** How many records count for the supply averages (with supplies or values). */
	int getSupplySampleCount()
	{
		return supplySampleCount;
	}

	/**
	 * Today's GE unit price (per item, not per dose) of each pooled supply id that has one. For sorting only.
	 */
	Map<Integer, Long> getSupplyUnitGePrices()
	{
		return supplyUnitGe;
	}

	/** Bank trips summed over the records with a known (non-negative) count. */
	long getBankTripTotal()
	{
		return bankTrips;
	}

	/** Kills of the records with a known bank-trip count, the denominator of trips per kill. */
	long getBankTripKills()
	{
		return bankTripKills;
	}

	/** How many records have a known bank-trip count. */
	int getBankTripSampleCount()
	{
		return bankTripSampleCount;
	}

	Map<String, Double> getXpPerKill()
	{
		Map<String, Double> result = new TreeMap<>();
		if (xpKills > 0)
		{
			xp.forEach((skill, value) -> result.put(skill, value / (double) xpKills));
		}
		return Collections.unmodifiableMap(result);
	}

	Long getXpPerHour()
	{
		long totalXp = SkillXp.total(xp);
		return SkillXp.perHour(totalXp, xpActiveMillis);
	}

	/**
	 * Estimated session values for {@code taskSize}, with unavailable historical GP data kept unknown.
	 */
	Estimate estimate(int taskSize)
	{
		if (taskSize <= 0 || sampleCount == 0)
		{
			return null;
		}
		Long msPerKill = getMillisPerKill();
		Long lootPerKill = getLootPerKill();
		Long suppliesPerKill = getSuppliesValuePerKill();
		Long estimatedMillis = msPerKill == null ? null : multiply(msPerKill, taskSize);
		Long estimatedLoot = lootPerKill == null ? null : multiply(lootPerKill, taskSize);
		Long estimatedSupplies = suppliesPerKill == null ? null : multiply(suppliesPerKill, taskSize);
		return new Estimate(sampleCount, estimatedMillis, estimatedLoot, estimatedSupplies,
			lootApproximate, suppliesApproximate);
	}

	private static long multiply(long value, int multiplier)
	{
		try
		{
			return Math.multiplyExact(value, (long) multiplier);
		}
		catch (ArithmeticException e)
		{
			return value >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
		}
	}

	static final class Estimate
	{
		private final int sampleCount;
		private final Long activeMillis;
		private final Long lootValue;
		private final Long suppliesValue;
		private final boolean lootApproximate;
		private final boolean suppliesApproximate;

		Estimate(int sampleCount, Long activeMillis, Long lootValue, Long suppliesValue, boolean lootApproximate,
			boolean suppliesApproximate)
		{
			this.sampleCount = sampleCount;
			this.activeMillis = activeMillis;
			this.lootValue = lootValue;
			this.suppliesValue = suppliesValue;
			this.lootApproximate = lootApproximate;
			this.suppliesApproximate = suppliesApproximate;
		}

		int getSampleCount()
		{
			return sampleCount;
		}

		Long getActiveMillis()
		{
			return activeMillis;
		}

		Long getLootValue()
		{
			return lootValue;
		}

		Long getSuppliesValue()
		{
			return suppliesValue;
		}

		boolean isLootApproximate()
		{
			return lootApproximate;
		}

		boolean isSuppliesApproximate()
		{
			return suppliesApproximate;
		}
	}
}
