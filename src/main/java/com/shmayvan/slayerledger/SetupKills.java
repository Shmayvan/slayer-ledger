package com.shmayvan.slayerledger;

import java.util.*;
import lombok.*;

/**
 * A session's setup kill contexts, as counts (V2 plan 4.3): how many task kills were cannon kills, how many were
 * made with each style and in each gear focus, and the range of the prayer bonus seen at those kills. The plugin
 * counts them per task kill ({@link SessionTracker#addKillContext}); a session starts with none ({@link #NONE}).
 * Shared by {@link SessionTracker.Summary},
 * {@link SessionSnapshot} and {@link LedgerRecord}, which keep them as flat fields of their own ({@code cannonKills},
 * {@code styleKills}, {@code gearKills}, {@code prayerBonusMin}, {@code prayerBonusMax}). Immutable. No RuneLite
 * dependencies.
 *
 * <p>The style and gear counts are keyed by the {@link SetupStyle} and {@link GearFocus} names, as strings, so a key a
 * later build adds survives a restore and a save by this one. Only positive counts are kept. Everything is
 * {@code null} (unknown) until a first kill context is counted: a migrated record, or one saved before setup detection,
 * has no counts, which is not the same as counts of 0.</p>
 */
@Value
final class SetupKills
{
	/** No kill context counted. */
	static final SetupKills NONE = new SetupKills(null, null, null, null, null);

	/** Cannon kills, or {@code null} when no kill context was counted. */
	private final Integer cannonKills;
	/** Kills per style name ({@link SetupStyle#name()}); never {@code null}, unmodifiable. */
	private final Map<String, Integer> styleKills;
	/** Kills per gear focus name ({@link GearFocus#name()}); never {@code null}, unmodifiable. */
	private final Map<String, Integer> gearKills;
	/** The lowest prayer bonus seen at a kill, or {@code null}. */
	private final Integer prayerBonusMin;
	/** The highest prayer bonus seen at a kill, or {@code null}. */
	private final Integer prayerBonusMax;

	private SetupKills(Integer cannonKills, Map<String, Integer> styleKills, Map<String, Integer> gearKills,
		Integer prayerBonusMin, Integer prayerBonusMax)
	{
		this.cannonKills = cannonKills == null ? null : Math.max(0, cannonKills);
		this.styleKills = Collections.unmodifiableMap(positive(styleKills));
		this.gearKills = Collections.unmodifiableMap(positive(gearKills));
		this.prayerBonusMin = prayerBonusMin;
		this.prayerBonusMax = prayerBonusMax;
	}

	/**
	 * The given counts, cleaned: negative cannon kills become 0, entries without a key or with a count below 1 are left
	 * out, and a prayer bonus range is kept only when both ends are known and in order.
	 */
	static SetupKills of(Integer cannonKills, Map<String, Integer> styleKills, Map<String, Integer> gearKills,
		Integer prayerBonusMin, Integer prayerBonusMax)
	{
		final boolean range = prayerBonusMin != null && prayerBonusMax != null && prayerBonusMin <= prayerBonusMax;
		final SetupKills kills = new SetupKills(cannonKills, styleKills, gearKills, range ? prayerBonusMin : null,
			range ? prayerBonusMax : null);
		return kills.isEmpty() ? NONE : kills;
	}

	/**
	 * A copy with one more task kill's context counted (the tracker calls this per kill, {@link
	 * SessionTracker#addKillContext}).
	 *
	 * @param cannon      whether it was a cannon kill
	 * @param style       its style, or {@code null} for none (a cannon-only kill)
	 * @param gear        its gear focus, or {@code null} for none
	 * @param prayerBonus the worn prayer bonus at the kill, or {@code null} when not read
	 */
	SetupKills withKill(boolean cannon, SetupStyle style, GearFocus gear, Integer prayerBonus)
	{
		final int cannonCount = saturatedAdd(cannonKills == null ? 0 : cannonKills, cannon ? 1 : 0);
		final Map<String, Integer> styles = new TreeMap<>(styleKills);
		if (style != null)
		{
			styles.merge(style.name(), 1, SetupKills::saturatedAdd);
		}
		final Map<String, Integer> gears = new TreeMap<>(gearKills);
		if (gear != null)
		{
			gears.merge(gear.name(), 1, SetupKills::saturatedAdd);
		}
		Integer min = prayerBonusMin;
		Integer max = prayerBonusMax;
		if (prayerBonus != null)
		{
			min = min == null ? prayerBonus : Math.min(min, prayerBonus);
			max = max == null ? prayerBonus : Math.max(max, prayerBonus);
		}
		return new SetupKills(cannonCount, styles, gears, min, max);
	}

	/** Whether no kill context was counted. */
	boolean isEmpty()
	{
		return cannonKills == null && styleKills.isEmpty() && gearKills.isEmpty() && prayerBonusMin == null;
	}

	/** Kills made with {@code style}; 0 for none. */
	int styleKills(SetupStyle style)
	{
		return style == null ? 0 : styleKills.getOrDefault(style.name(), 0);
	}

	/** Kills made in {@code gear}; 0 for none. */
	int gearKills(GearFocus gear)
	{
		return gear == null ? 0 : gearKills.getOrDefault(gear.name(), 0);
	}

	/** The record's counts as stored. */
	static SetupKills from(LedgerRecord record)
	{
		return of(record.getCannonKills(), record.getStyleKills(), record.getGearKills(), record.getPrayerBonusMin(),
			record.getPrayerBonusMax());
	}

	/** The snapshot's counts as stored. */
	static SetupKills from(SessionSnapshot snapshot)
	{
		return of(snapshot.getCannonKills(), snapshot.getStyleKills(), snapshot.getGearKills(),
			snapshot.getPrayerBonusMin(), snapshot.getPrayerBonusMax());
	}

	/** Sets the record's count fields ({@code null} for what was not counted, and for empty maps). */
	void copyTo(LedgerRecord record)
	{
		record.setCannonKills(cannonKills);
		record.setStyleKills(mapOrNull(styleKills));
		record.setGearKills(mapOrNull(gearKills));
		record.setPrayerBonusMin(prayerBonusMin);
		record.setPrayerBonusMax(prayerBonusMax);
	}

	/** Sets the snapshot's count fields, as {@link #copyTo(LedgerRecord)}. */
	void copyTo(SessionSnapshot snapshot)
	{
		snapshot.setCannonKills(cannonKills);
		snapshot.setStyleKills(mapOrNull(styleKills));
		snapshot.setGearKills(mapOrNull(gearKills));
		snapshot.setPrayerBonusMin(prayerBonusMin);
		snapshot.setPrayerBonusMax(prayerBonusMax);
	}

	private static Map<String, Integer> mapOrNull(Map<String, Integer> counts)
	{
		return counts.isEmpty() ? null : new TreeMap<>(counts);
	}

	private static Map<String, Integer> positive(Map<String, Integer> counts)
	{
		final Map<String, Integer> result = new TreeMap<>();
		if (counts != null)
		{
			for (Map.Entry<String, Integer> e : counts.entrySet())
			{
				if (e.getKey() != null && !e.getKey().trim().isEmpty() && e.getValue() != null && e.getValue() > 0)
				{
					result.put(e.getKey(), e.getValue());
				}
			}
		}
		return result;
	}

	private static int saturatedAdd(int a, int b)
	{
		final long sum = (long) a + b;
		return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
	}
}
