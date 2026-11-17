package com.shmayvan.slayerledger;

import java.util.*;

/**
 * The rules that turn what was seen at a task's kills into its detected setup tags (V2 plan 4.3): each kill's
 * gear focus from the worn prayer bonus, each killed NPC's style from its hitsplats' attacks, and the task's cannon,
 * style and gear focus from its kill contexts ({@link SetupKills}). {@link SetupTags#forSession} uses them for the live
 * session and the record it is saved as. Pure (no RuneLite types).
 *
 * <p><b>Gear focus cut-offs.</b> The total prayer bonus of the worn items: {@value #PRAYER_FOCUS_MIN} or more is
 * Prayer, {@value #STRENGTH_FOCUS_MAX} or less is Strength, anything between is Mixed. Checked against the OSRS Wiki's
 * bonuses of real setups on 2026-11-09 ({@code docs/COUNTING_RULES.md}, "Setup tags"): damage-focused sets total +4 to
 * +14 (Torva or Bandos with a torture and infernal cape +7 to +9, with Rada's blessing 4 up to +11; Masori or Armadyl
 * +6; crystal armour with a bow of faerdhinen +10; Ancestral with an Elidinis' ward +8; Virtus with a ward and Rada's
 * blessing +14), and prayer sets +29 to +37 (Justiciar or Proselyte with holy wraps, a holy book, a ring of the gods,
 * holy sandals and a blessing). The plan's first guess, Strength at +10 or less, would have called the common Bandos
 * set with Rada's blessing 4 Mixed, so the Strength cut-off was raised to +14.</p>
 */
final class SetupDetection
{
	/** The lowest worn prayer bonus that is Prayer gear. */
	static final int PRAYER_FOCUS_MIN = 25;
	/** The highest worn prayer bonus that is Strength (damage) gear. */
	static final int STRENGTH_FOCUS_MAX = 14;

	private SetupDetection()
	{
	}

	/** The gear focus of a worn prayer bonus (see the class comment). */
	static GearFocus gearFocus(int prayerBonus)
	{
		if (prayerBonus >= PRAYER_FOCUS_MIN)
		{
			return GearFocus.PRAYER;
		}
		return prayerBonus <= STRENGTH_FOCUS_MAX ? GearFocus.STRENGTH : GearFocus.MIXED;
	}

	/**
	 * A killed NPC's style: the style most of the player's hitsplats on it were put down to ({@link PlayerAttacks}), Mixed
	 * when two styles tie for most, or {@code null} when none was (only cannon hits, say).
	 *
	 * @param hitsPerStyle hitsplats per style, indexed by {@link SetupStyle#ordinal()}
	 */
	static SetupStyle npcStyle(int[] hitsPerStyle)
	{
		SetupStyle best = null;
		int bestHits = 0;
		boolean tie = false;
		for (SetupStyle style : SetupStyle.values())
		{
			final int hits = style.ordinal() < hitsPerStyle.length ? hitsPerStyle[style.ordinal()] : 0;
			if (hits > bestHits)
			{
				best = style;
				bestHits = hits;
				tie = false;
			}
			else if (hits > 0 && hits == bestHits)
			{
				tie = true;
			}
		}
		return tie ? SetupStyle.MIXED : best;
	}

	/**
	 * Whether a task was done with a cannon: at least half of its {@code kills} were cannon kills. A task with no kills,
	 * or none counted as cannon kills, was not.
	 */
	static boolean cannon(Integer cannonKills, int kills)
	{
		return cannonKills != null && cannonKills > 0 && kills > 0 && 2L * cannonKills >= kills;
	}

	/**
	 * A task's style from its kills per style ({@link SetupKills#getStyleKills()}): the style covering at least half of
	 * the kills that have a style. Mixed when two styles tie for that, when no style reaches half, or when the covering
	 * style is itself Mixed (kills whose NPC was hit equally in two styles); Unknown with no styled kill at all (a
	 * cannon-only task). Keys this build doesn't know (a later build's styles) are left out.
	 */
	static SetupStyle taskStyle(Map<String, Integer> styleKills)
	{
		long total = 0;
		SetupStyle best = null;
		int bestKills = 0;
		boolean tie = false;
		for (SetupStyle style : SetupStyle.values())
		{
			if (style == SetupStyle.UNKNOWN)
			{
				continue;
			}
			final int kills = styleKills == null ? 0 : styleKills.getOrDefault(style.name(), 0);
			if (kills <= 0)
			{
				continue;
			}
			total += kills;
			if (kills > bestKills)
			{
				best = style;
				bestKills = kills;
				tie = false;
			}
			else if (kills == bestKills)
			{
				tie = true;
			}
		}
		if (best == null)
		{
			return SetupStyle.UNKNOWN;
		}
		return !tie && 2L * bestKills >= total ? best : SetupStyle.MIXED;
	}

	/**
	 * A task's gear focus from its kills per focus ({@link SetupKills#getGearKills()}): the focus of the most kills,
	 * Mixed when two tie for most, Unknown with none. Keys this build doesn't know are left out.
	 */
	static GearFocus taskGear(Map<String, Integer> gearKills)
	{
		GearFocus best = null;
		int bestKills = 0;
		boolean tie = false;
		for (GearFocus gear : GearFocus.values())
		{
			if (gear == GearFocus.UNKNOWN)
			{
				continue;
			}
			final int kills = gearKills == null ? 0 : gearKills.getOrDefault(gear.name(), 0);
			if (kills > bestKills)
			{
				best = gear;
				bestKills = kills;
				tie = false;
			}
			else if (kills > 0 && kills == bestKills)
			{
				tie = true;
			}
		}
		if (best == null)
		{
			return GearFocus.UNKNOWN;
		}
		return tie ? GearFocus.MIXED : best;
	}

	/*
	 * What each tag was detected from, for the live card's setup toggles' tooltips (SetupToggles; plain text, no
	 * player text). Each is empty before a kill's context is counted.
	 */

	/**
	 * What the cannon tag was detected from: the cannon kills of the task's kills that may have a kill context
	 * ({@code taskKills}, {@link SessionTracker#contextKills}), {@code Cannon kills: 2 of 3}.
	 */
	static List<String> cannonDetails(SetupKills kills, int taskKills)
	{
		final List<String> lines = new ArrayList<>();
		if (kills.getCannonKills() != null)
		{
			lines.add("Cannon kills: " + kills.getCannonKills() + " of " + taskKills);
		}
		return lines;
	}

	/** What the style tag was detected from: the kills per style, {@code Kills by style: Melee 2, Barrage 1}. */
	static List<String> styleDetails(SetupKills kills)
	{
		final List<String> lines = new ArrayList<>();
		addCounts(lines, "Kills by style: ", kills.getStyleKills(), styleLabels());
		return lines;
	}

	/**
	 * What the gear focus tag was detected from: the kills per gear focus and the worn prayer bonus seen at the kills,
	 * {@code Kills by gear: Prayer 2, Strength 1} and {@code Prayer bonus: -2 to +33}.
	 */
	static List<String> gearDetails(SetupKills kills)
	{
		final List<String> lines = new ArrayList<>();
		addCounts(lines, "Kills by gear: ", kills.getGearKills(), gearLabels());
		final Integer min = kills.getPrayerBonusMin();
		final Integer max = kills.getPrayerBonusMax();
		if (min != null && max != null)
		{
			lines.add("Prayer bonus: " + signed(min) + (min.equals(max) ? "" : " to " + signed(max)));
		}
		return lines;
	}

	private static void addCounts(List<String> lines, String title, Map<String, Integer> counts,
		Map<String, String> labels)
	{
		final StringBuilder line = new StringBuilder();
		for (Map.Entry<String, String> label : labels.entrySet())
		{
			final int n = counts.getOrDefault(label.getKey(), 0);
			if (n > 0)
			{
				line.append(line.length() == 0 ? "" : ", ").append(label.getValue()).append(' ').append(n);
			}
		}
		if (line.length() > 0)
		{
			lines.add(title + line);
		}
	}

	private static Map<String, String> styleLabels()
	{
		final Map<String, String> labels = new LinkedHashMap<>();
		for (SetupStyle style : SetupStyle.values())
		{
			labels.put(style.name(), style.getLabel());
		}
		return labels;
	}

	private static Map<String, String> gearLabels()
	{
		final Map<String, String> labels = new LinkedHashMap<>();
		for (GearFocus gear : GearFocus.values())
		{
			labels.put(gear.name(), gear.getLabel());
		}
		return labels;
	}

	/** {@code +6}, {@code 0} or {@code -2}. */
	static String signed(int n)
	{
		return n > 0 ? "+" + n : Integer.toString(n);
	}
}
