package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Per-task historical averages, calculated from saved completed records only: what the live task card's time left and
 * the overlay's time left and supply runway read (the 2.0 rebuild trimmed it to them). That is the sample count, the time per
 * kill, the location the samples were limited to and the supplies per kill. Nothing here is valued, so neither today's
 * prices, the "Loot price" mode nor Ground Items' hidden items change it. Immutable; pure (no RuneLite types).
 */
final class TaskStatistics
{
	private final int sampleCount;
	private final long totalKills;
	private final long activeMillis;
	/** Kills of the samples that count for the supplies per kill ({@link TaskPlanner#hasSupplyData}). */
	private final long supplyKills;
	/** Supplies used by those samples, by item id (doses, bites or charges for the items saved so). */
	private final Map<Integer, Long> supplies;
	/** The name of the location the samples were limited to, or {@code null} for every location. */
	private final String location;

	private TaskStatistics(Totals t)
	{
		this.location = t.location;
		this.sampleCount = t.sampleCount;
		this.totalKills = t.totalKills;
		this.activeMillis = t.activeMillis;
		this.supplyKills = t.supplyKills;
		this.supplies = Collections.unmodifiableMap(new TreeMap<>(t.supplies));
	}

	/** The running totals {@link #aggregate} adds the samples into; a new one is the empty statistics. */
	private static final class Totals
	{
		private int sampleCount;
		private long totalKills;
		private long activeMillis;
		private long supplyKills;
		private final Map<Integer, Long> supplies = new TreeMap<>();
		private String location;
	}

	/**
	 * The statistics of the records of the task {@code selection} selects: its version and whether a cannon was
	 * used; {@link TaskSelection#ANY} (or {@code null}) pools the standard monster and its Catacombs variant,
	 * with and without a cannon. Per-kill values are pooled across their kills. Outliers are not removed.
	 *
	 * <p>Same location first (by spot): when {@code here} is
	 * {@link TaskLocation.Here#isKnown known} and at least one sample ({@link #isSample}) is at its spot
	 * ({@code recordSpot}'s spot key for the record equals {@code here}'s), only those samples count and
	 * {@link #getLocation} is {@code here}'s name; otherwise every sample counts. A record's own location name (Edit
	 * location) never moves it: tasks match by spot, as they group.</p>
	 *
	 * <p>Supplies count only from samples that say what they used, by the advisor's rule
	 * ({@link TaskPlanner#hasSupplyData}): a 1.x record saved without supplies may have had its supply counting
	 * stop, so it is left out of the supplies per kill, not counted as using nothing. It still counts for the
	 * kills and the time.</p>
	 *
	 * @param here       the current task's location ({@link TaskLocation#current}), or {@code null} while unknown
	 * @param recordSpot a saved record's spot key ({@link SlayerSpots#recordSpot}), {@code null} when Unknown; or
	 *                   {@code null} to pool every location
	 */
	static TaskStatistics forTask(Collection<LedgerRecord> records, int creatureId, String monster,
		TaskSelection selection, TaskLocation.Here here, Function<LedgerRecord, String> recordSpot)
	{
		if (here != null && here.isKnown() && records != null && recordSpot != null)
		{
			List<LedgerRecord> atLocation = new ArrayList<>();
			for (LedgerRecord record : records)
			{
				if (isSample(record, creatureId, monster, selection) && here.getSpot().equals(recordSpot.apply(record)))
				{
					atLocation.add(record);
				}
			}
			if (!atLocation.isEmpty())
			{
				return aggregate(atLocation, creatureId, monster, selection, here.getName());
			}
		}
		return aggregate(records, creatureId, monster, selection, null);
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
		TaskSelection selection, String location)
	{
		final Totals t = new Totals();
		t.location = location;
		if (records == null)
		{
			return new TaskStatistics(t);
		}

		for (LedgerRecord record : records)
		{
			if (!isSample(record, creatureId, monster, selection))
			{
				continue;
			}

			long recordKills = record.getKills();
			t.sampleCount++;
			t.totalKills = saturatedAdd(t.totalKills, recordKills);
			t.activeMillis = saturatedAdd(t.activeMillis, Math.max(0, record.getActiveMillis()));

			// A 1.x task saved without supplies: what it used is unknown, not nothing. A valued task saved
			// without supplies used none.
			Map<Integer, Long> recordSupplies = record.getSupplies();
			if (TaskPlanner.hasSupplyData(record) && (recordSupplies != null || LedgerHistory.isValued(record)))
			{
				t.supplyKills = saturatedAdd(t.supplyKills, recordKills);
				if (recordSupplies != null)
				{
					for (Map.Entry<Integer, Long> item : recordSupplies.entrySet())
					{
						if (item.getKey() != null && item.getValue() != null && item.getValue() > 0)
						{
							t.supplies.merge(item.getKey(), item.getValue(), TaskStatistics::saturatedAdd);
						}
					}
				}
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
	 * The one monster-name key every feature groups and matches by (task statistics, the advisor's ranking, Overall
	 * totals, the Slayer spot table's task lists): trimmed and lower case in {@link Locale#ROOT}, so the key never
	 * depends on the client's default locale. {@code null} for a {@code null} or blank name, which matches nothing.
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

	/**
	 * The name of the location the samples are limited to (the current task's {@link TaskLocation.Here#getName}), or
	 * {@code null} when they are from every location.
	 */
	String getLocation()
	{
		return location;
	}

	/** The samples' active time over their kills, or {@code null} without a sample. */
	Long getMillisPerKill()
	{
		return totalKills == 0 ? null : Math.round(activeMillis / (double) totalKills);
	}

	/**
	 * Each supply's use per kill, by item id, over the kills of the samples that say what they used (doses, bites or
	 * charges for the items saved so); empty without one. The overlay's supply runway reads it.
	 */
	Map<Integer, Double> getSuppliesPerKill()
	{
		Map<Integer, Double> result = new TreeMap<>();
		if (supplyKills > 0)
		{
			supplies.forEach((id, quantity) -> result.put(id, quantity / (double) supplyKills));
		}
		return Collections.unmodifiableMap(result);
	}
}
