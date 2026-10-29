package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import lombok.Value;

/**
 * XP gained per skill during a task. Skills are keyed by the name of RuneLite's {@code Skill} constant (for
 * example {@code "SLAYER"}), so this class has no RuneLite types and the saved files stay readable. Pure and
 * stateless; {@link SessionTracker} keeps the readings and the totals.
 *
 * <p>XP is counted as the difference between two readings of every skill's experience. A reading in which any skill
 * went <em>down</em> is never counted ({@link #gains} returns {@code null}): XP never decreases in the game, so the
 * two readings are of different accounts or one of them was not synced yet.</p>
 *
 * <p>Readings iterate {@code Skill.values()}. There is no overall skill to read: in the RuneLite API (checked against
 * 1.13.4) {@code Skill.OVERALL} is a deprecated constant that is {@code null} and not one of the values, so an "all
 * skills" figure is the sum of the per-skill gains.</p>
 */
final class SkillXp
{
	/** Title of the per-skill XP lists (the live one and a saved task's). */
	static final String LIST_TITLE = "XP gained";
	/** Noun of the per-skill XP lists' "+N more" tooltip. */
	static final String LIST_NOUN = "skills";
	/** Key of the Slayer skill: the name of RuneLite's {@code Skill.SLAYER} constant. */
	static final String SLAYER = "SLAYER";

	/** Highest XP first, then by skill name. */
	private static final Comparator<Row> ORDER = Comparator.comparingLong(Row::getXp).reversed()
		.thenComparing(Row::getName);

	private SkillXp()
	{
	}

	/**
	 * XP gained per skill from {@code before} to {@code after}: each skill whose XP went up, by how much. A skill
	 * missing from either reading is left out.
	 *
	 * @return the gains (empty when nothing went up), or {@code null} when either reading is missing or any skill went
	 *         down, so the readings can't be compared
	 */
	static Map<String, Long> gains(Map<String, Integer> before, Map<String, Integer> after)
	{
		if (before == null || after == null)
		{
			return null;
		}
		Map<String, Long> gains = new TreeMap<>();
		for (Map.Entry<String, Integer> e : after.entrySet())
		{
			Integer was = before.get(e.getKey());
			Integer now = e.getValue();
			if (e.getKey() == null || was == null || now == null)
			{
				continue;
			}
			if (now < was)
			{
				return null;
			}
			if (now > was)
			{
				gains.put(e.getKey(), (long) now - was);
			}
		}
		return gains;
	}

	/** Adds {@code gains} (may be {@code null}) into {@code into}, saturating at {@link Long#MAX_VALUE}. */
	static void add(Map<String, Long> into, Map<String, Long> gains)
	{
		if (gains == null)
		{
			return;
		}
		for (Map.Entry<String, Long> e : gains.entrySet())
		{
			if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0)
			{
				continue;
			}
			into.merge(e.getKey(), e.getValue(), SkillXp::saturatedAdd);
		}
	}

	/** A sorted copy of {@code xp} with only positive entries; empty for {@code null}. */
	static Map<String, Long> sanitize(Map<String, Long> xp)
	{
		Map<String, Long> result = new TreeMap<>();
		add(result, xp);
		return result;
	}

	/** Total XP of every skill; 0 for {@code null}. */
	static long total(Map<String, Long> xp)
	{
		long total = 0;
		if (xp != null)
		{
			for (Long value : xp.values())
			{
				if (value != null && value > 0)
				{
					total = saturatedAdd(total, value);
				}
			}
		}
		return total;
	}

	/**
	 * XP per hour of active time, or {@code null} until there is at least {@link TaskTimeEstimate#MIN_ACTIVE_MILLIS}
	 * of it (a rate from a few seconds means little).
	 */
	static Long perHour(long xp, long activeMillis)
	{
		if (activeMillis < TaskTimeEstimate.MIN_ACTIVE_MILLIS || xp < 0)
		{
			return null;
		}
		return Math.round(xp * 3_600_000d / activeMillis);
	}

	/** Display name of a skill key: {@code "HITPOINTS"} is "Hitpoints". */
	static String skillName(String key)
	{
		if (key == null || key.isEmpty())
		{
			return "Unknown skill";
		}
		return key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
	}

	/**
	 * The total line: {@code XP: 12,345 (40,100/h)}, without the rate until {@link #perHour} gives one, or
	 * {@code XP: -} when the XP was not recorded ({@code null}, a task saved before XP was tracked).
	 */
	static String totalText(Map<String, Long> xp, long activeMillis)
	{
		if (xp == null)
		{
			return "XP: " + LedgerHistory.NO_VALUE;
		}
		long total = total(xp);
		String rate = rateText(total, activeMillis);
		return "XP: " + LedgerHistory.formatAmount(total, false) + (rate == null ? "" : " (" + rate + ")");
	}

	/** {@code 40,100/h}, or {@code null} until {@link #perHour} gives a rate. */
	static String rateText(long xp, long activeMillis)
	{
		Long rate = perHour(xp, activeMillis);
		return rate == null ? null : LedgerHistory.formatAmount(rate, false) + "/h";
	}

	/**
	 * Tooltip of a total line: the exact total and rate, then every skill; a plain note when the XP was not recorded.
	 */
	static String tooltip(Map<String, Long> xp, long activeMillis)
	{
		if (xp == null)
		{
			return "Not recorded: this task was saved before XP was tracked.";
		}
		long total = total(xp);
		StringBuilder sb = new StringBuilder("<html>XP gained: ").append(String.format("%,d", total));
		Long rate = perHour(total, activeMillis);
		if (rate != null)
		{
			sb.append("<br>XP per hour: ").append(String.format("%,d", rate));
		}
		for (Row row : rows(xp, activeMillis))
		{
			sb.append("<br>").append(row.getName()).append(": ").append(String.format("%,d", row.getXp()));
		}
		return sb.append("<br>Counted only while the session runs.</html>").toString();
	}

	/** One line per skill with XP, highest first; empty for {@code null}. */
	static List<Row> rows(Map<String, Long> xp, long activeMillis)
	{
		if (xp == null)
		{
			return Collections.emptyList();
		}
		List<Row> rows = new ArrayList<>();
		for (Map.Entry<String, Long> e : sanitize(xp).entrySet())
		{
			rows.add(new Row(e.getKey(), skillName(e.getKey()), e.getValue(), perHour(e.getValue(), activeMillis)));
		}
		rows.sort(ORDER);
		return rows;
	}

	private static long saturatedAdd(long a, long b)
	{
		long sum = a + b;
		return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
	}

	/** One skill's line of a per-skill XP list, with the skill's small icon before its name. */
	@Value
	static class Row implements ItemListView.Line
	{
		/** Skill key ({@code "SLAYER"}), for the icon. */
		String skill;
		/** Skill name to show. */
		String name;
		long xp;
		/** XP per hour of active time, or {@code null} while too little time has passed. */
		Long perHour;

		@Override
		public String amountText()
		{
			return LedgerHistory.formatAmount(xp, false);
		}

		@Override
		public String tooltip()
		{
			return "<html>" + name + ": " + String.format("%,d", xp) + " XP"
				+ (perHour == null ? "" : "<br>" + String.format("%,d", perHour) + " XP per hour") + "</html>";
		}
	}
}
