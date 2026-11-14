package com.shmayvan.slayerledger;

import java.util.Locale;
import java.util.OptionalLong;
import net.runelite.client.util.QuantityFormatter;

/**
 * The number rules of docs/DESIGN_PRINCIPLES.md 5.5, for the redesigned sidebar and overlay: gp and XP,
 * signed profit, kills, the running clock, pace, rates and the time left. Pure. The live task card, the advisor card,
 * Overall totals and the overlay use them. The live card's time left stays {@link LedgerHistory#formatTimeLeft(long, int, int, Long)} ({@code ~38m},
 * {@code ~9h 59m}), which fits the card's columns; the overlay's is {@link #timeLeft} ({@code ~38 min}), the same
 * estimate in the 5.5 form.
 */
final class LedgerFormat
{
	/** Shown for a value that does not apply, such as the time left with no kills left. */
	static final String NONE = LedgerHistory.NO_VALUE;
	/** Shown for a value not known yet, such as the pace before enough kills: {@code ?}. */
	static final String UNKNOWN = "?";
	/** Marks an approximate value: some prices were not loaded. Comes before any sign. */
	static final String APPROXIMATE = LedgerHistory.APPROXIMATE;

	private LedgerFormat()
	{
	}

	/**
	 * gp or XP: in full with commas under 10,000, then at most 3 significant figures with K, M or B and no trailing
	 * zeros ({@code 950}, {@code 1,235}, {@code 12.4K}, {@code 477K}, {@code 1.24M}, {@code 2.1B}; {@code 10K} for
	 * 10,050, {@code 1M} for 1,005,000, {@code 1.5M} for 1,500,000), as RuneLite's
	 * {@link QuantityFormatter#quantityToStackSize} shows stacks but without the zeros it leaves after the point. A
	 * negative amount has a hyphen.
	 */
	static String amount(long value)
	{
		String text = QuantityFormatter.quantityToStackSize(value);
		int suffix = text.length() - 1;
		if (Character.isDigit(text.charAt(suffix)) || text.indexOf('.') < 0)
		{
			return text;
		}
		int end = suffix;
		while (text.charAt(end - 1) == '0')
		{
			end--;
		}
		if (text.charAt(end - 1) == '.')
		{
			end--;
		}
		return text.substring(0, end) + text.charAt(suffix);
	}

	/** {@link #amount}, with {@link #APPROXIMATE} first when {@code approximate}; {@link #NONE} for {@code null}. */
	static String amount(Long value, boolean approximate)
	{
		return value == null ? NONE : (approximate ? APPROXIMATE : "") + amount(value.longValue());
	}

	/**
	 * A profit or other change: always signed except zero ({@code +212K}, {@code -38K}, {@code 0}), with
	 * {@link #APPROXIMATE} before the sign when {@code approximate} ({@code ~+212K}); {@link #NONE} for {@code null}.
	 */
	static String signed(Long value, boolean approximate)
	{
		if (value == null)
		{
			return NONE;
		}
		long v = value;
		return (approximate ? APPROXIMATE : "") + (v > 0 ? "+" : "") + amount(v);
	}

	/** Kills: a whole number with commas ({@code 1,412}). */
	static String kills(long kills)
	{
		return QuantityFormatter.formatNumber(kills);
	}

	/** Kills done of the task's total, with spaces: {@code 73 / 201}. */
	static String progress(long done, long total)
	{
		return kills(done) + " / " + kills(total);
	}

	/** A running clock: m:ss under an hour, then h:mm:ss ({@code 21:54}, {@code 1:03:31}). A negative time is 0. */
	static String clock(long millis)
	{
		long seconds = Math.max(0, millis) / 1000;
		long hours = seconds / 3600;
		if (hours > 0)
		{
			return String.format(Locale.ROOT, "%d:%02d:%02d", hours, (seconds / 60) % 60, seconds % 60);
		}
		return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
	}

	/**
	 * A completed duration in text (5.5), such as a saved task's active time on its Task history row: whole minutes,
	 * rounded half up, {@code 52 min}, from an hour {@code 1 h 1 min}, on the hour {@code 2 h}; {@code <1 min} for less
	 * than half a minute and {@code 0 min} for none.
	 */
	static String duration(long millis)
	{
		if (millis <= 0)
		{
			return minutes(0);
		}
		long minutes = millis / 60_000 + (millis % 60_000 >= 30_000 ? 1 : 0);
		return minutes == 0 ? "<" + minutes(1) : minutes(minutes);
	}

	/** Whole minutes in text (5.5): {@code 54 min}, from an hour {@code 1 h 6 min}, on the hour {@code 2 h}. */
	static String minutes(long minutes)
	{
		if (minutes < 60)
		{
			return minutes + " min";
		}
		final long rest = minutes % 60;
		return minutes / 60 + " h" + (rest == 0 ? "" : " " + rest + " min");
	}

	/** Pace: the time per kill as m:ss per kill, rounded to whole seconds (half up): {@code 0:18/kill}. */
	static String pace(long millisPerKill)
	{
		long clamped = Math.max(0, millisPerKill);
		long seconds = clamped / 1000 + (clamped % 1000 >= 500 ? 1 : 0);
		return String.format(Locale.ROOT, "%d:%02d/kill", seconds / 60, seconds % 60);
	}

	/**
	 * The time left on a task (5.5's estimated time, {@link TaskPlanText#estimatedTime}): {@code ~38 min}, from an hour
	 * {@code ~1 h 5 min}. The estimate is {@link LedgerHistory#formatTimeLeft(long, int, int, Long)}'s: the session's
	 * pace once it has enough kills and time ({@link TaskTimeEstimate}), until then the history's time per kill when
	 * there is one. {@link #UNKNOWN} while neither gives one; {@link #NONE} with no kills left.
	 *
	 * @param historicalMillisPerKill the task's time per kill in its completed history, or {@code null} without one
	 */
	static String timeLeft(long activeMillis, int kills, int remaining, Long historicalMillisPerKill)
	{
		if (remaining <= 0)
		{
			return NONE;
		}
		final OptionalLong estimate = TaskTimeEstimate.remainingMillis(activeMillis, kills, remaining);
		if (estimate.isPresent())
		{
			return TaskPlanText.estimatedTime(estimate.getAsLong());
		}
		return historicalMillisPerKill == null ? UNKNOWN
			: TaskPlanText.estimatedTime(LedgerHistory.historicalTimeLeftMillis(historicalMillisPerKill, remaining));
	}

	/** A rate per hour without its unit, for a value under a {@code .../h} label: {@link #amount} of it, rounded. */
	static String perHour(double perHour)
	{
		return amount(Math.round(Math.max(0, perHour)));
	}
}
