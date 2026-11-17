package com.shmayvan.slayerledger;

import java.util.*;
import java.util.function.*;
import net.runelite.client.util.*;

/**
 * The advisor plan's words (V2 plan 6, {@code DESIGN_PRINCIPLES.md} section 5): the estimated times and paces,
 * the thin-data and no-history phrases, and the advisor card's text (its title, option names and rows,
 * and the Konar and Wilderness notes), so {@link AdvisorCardSection} has no wording of its own. Plain text; the card
 * escapes what it puts in HTML. Straight quotes, no em dashes, {@code ~} before every estimate. Pure (RuneLite's
 * static {@link QuantityFormatter} only).
 */
final class TaskPlanText
{
	/** The card's note when the plan has tasks but no option: every task's spot or style is Unknown. */
	static final String SETUP_UNKNOWN = "Setup unknown for these tasks.";

	private TaskPlanText()
	{
	}

	/**
	 * An estimated time, rounded to the minute (at least 1): {@code ~24 min}, from 60 minutes {@code ~1 h 5 min}, and
	 * {@code ~2 h} on the hour.
	 */
	static String estimatedTime(long millis)
	{
		final long minutes = Math.max(1, (Math.max(0, millis) + 30_000) / 60_000);
		if (minutes < 60)
		{
			return LedgerHistory.APPROXIMATE + minutes + " min";
		}
		final long rest = minutes % 60;
		return LedgerHistory.APPROXIMATE + minutes / 60 + " h" + (rest == 0 ? "" : " " + rest + " min");
	}

	/**
	 * An estimated pace ({@code DESIGN_PRINCIPLES.md} 5.5), rounded to the second (at least 1): {@code ~0:12/kill},
	 * from an hour {@code ~1:02:05/kill}.
	 */
	static String estimatedPace(long millis)
	{
		final long seconds = Math.max(1, (Math.max(0, millis) + 500) / 1_000);
		final long hours = seconds / 3_600;
		final long minutes = seconds / 60 % 60;
		final String clock = hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds % 60)
			: String.format(Locale.ROOT, "%d:%02d", minutes, seconds % 60);
		return LedgerHistory.APPROXIMATE + clock + "/kill";
	}

	/** {@code based on 1 task} or {@code based on 2 tasks}: what a thin recommendation is labelled with. */
	static String basedOn(int tasks)
	{
		return "based on " + tasks(tasks);
	}

	/** {@code No completed Dust devils tasks yet, so no estimate.}: the advisor with no history. */
	static String noHistory(String task)
	{
		return "No completed " + (task == null ? "" : task + " ") + "tasks yet, so no estimate.";
	}

	/**
	 * An option's time ({@code ~60 min}): for the kills left ({@link #estimatedTime}), or its pace ({@code ~0:12/kill},
	 * {@link #estimatedPace}) with them unknown ({@code perKill}).
	 */
	static String time(TaskPlan.Time time, boolean perKill)
	{
		return perKill ? estimatedPace(time.getPerKillMillis()) : estimatedTime(time.getMillis());
	}

	/** {@code 1 task} or {@code 3 tasks}. */
	static String tasks(int tasks)
	{
		return tasks + (tasks == 1 ? " task" : " tasks");
	}

	// ---- The advisor card (DESIGN_PRINCIPLES.md 3.5) ----

	/**
	 * The card's title, what the plan is based on: {@code Based on 6 Dust devils tasks}, {@code Based on 1 Dust devils
	 * task}, or without a name {@code Based on 6 tasks}.
	 *
	 * @param task  the task's name as shown, or {@code null}
	 * @param tasks the plan's sample size (its All setups tasks)
	 */
	static String cardTitle(String task, int tasks)
	{
		return "Based on " + tasks + (task == null ? "" : " " + task) + (tasks == 1 ? " task" : " tasks");
	}

	/**
	 * The card's one line without history ({@link TaskPlan#hasHistory} false): {@link #noHistory}, or for a Wilderness
	 * task whose creature has completed tasks, none of them in the Wilderness
	 * ({@link TaskPlan#isWildernessHistoryMissing}), {@code No completed Wilderness Abyssal demons tasks yet, so no
	 * estimate.}
	 */
	static String noHistoryLine(String task, TaskPlan plan)
	{
		return plan.isWildernessHistoryMissing() ? noHistory(task == null ? "Wilderness" : "Wilderness " + task)
			: noHistory(task);
	}

	/**
	 * A Konar task's note when no option is at its assigned area's spot, so every option is shown
	 * ({@link TaskPlan#isAreaFallback}): {@code No completed tasks in Catacombs yet, so all areas are used.}
	 *
	 * @param area the area's spot as shown ({@link #shortSpotName}), or {@code null}
	 */
	static String areaFallback(String area)
	{
		return "No completed tasks in " + (area == null ? "the assigned area" : area) + " yet, so all areas are used.";
	}

	/**
	 * An option's name on the card, its setup and spot: {@code Barrage, Catacombs}, {@code Cannon, melee, Smoke
	 * Dungeon}, and for an option done on the task's Slayer boss its name first, {@code Grotesque Guardians, melee,
	 * Slayer Tower}. No {@code ~}: the card says {@code estimated} beside the sample size ({@link #optionTasks}).
	 *
	 * @param task        the task's name, for its Slayer boss ({@link TaskVariants#bossName})
	 * @param regionNames the player's names for map regions, {@code null} when unknown
	 */
	static String optionName(TaskPlan.Option option, String task, SlayerSpots spots, IntFunction<String> regionNames)
	{
		final String style = option.getStyle().getLabel();
		String setup = option.isCannon() ? "Cannon, " + style.toLowerCase(Locale.ROOT) : style;
		if (option.isBoss())
		{
			final String boss = task == null ? null : TaskVariants.bossName(task);
			setup = (boss == null ? "Boss" : boss) + ", " + setup.substring(0, 1).toLowerCase(Locale.ROOT)
				+ setup.substring(1);
		}
		return setup + ", " + shortSpotName(option.getSpot(), spots, regionNames);
	}

	/**
	 * An option's sample size on the card: {@code 3 tasks}, {@code 1 task}, and {@code 3 tasks, estimated} when some
	 * task's cannon or style is an estimate ({@link TaskPlan.Option#isEstimated}, a migrated 1.x task); for a thin
	 * recommendation ({@link TaskPlan#isRecommendationThin}: {@code thin}) {@code based on 1 task} ({@link #basedOn}),
	 * {@code based on 1 task, estimated} (V2 plan 6, DESIGN_PRINCIPLES.md 3.5).
	 */
	static String optionTasks(TaskPlan.Option option, boolean thin)
	{
		return (thin ? basedOn(option.getTasks()) : tasks(option.getTasks()))
			+ (option.isEstimated() ? ", estimated" : "");
	}

	/** The bring items past the grid's 8: {@code +3 more}. */
	static String bringMore(int more)
	{
		return "+" + more + " more";
	}

	/**
	 * A bring item's tooltip lines, plain text: its full name, the amount for the kills left and, when priced, its
	 * value at today's GE price: {@code Prayer potion(4)}, {@code Bring: 6}, {@code GE value: 54,000 gp}.
	 */
	static List<String> bringTooltip(String name, long quantity, Long value)
	{
		final List<String> lines = new ArrayList<>();
		lines.add(name);
		lines.add("Bring: " + QuantityFormatter.formatNumber(quantity));
		if (value != null)
		{
			lines.add("GE value: " + LedgerHistory.formatGpExact(value));
		}
		return lines;
	}

	/**
	 * One line of the {@code +N more} label's tooltip, plain text: a bring item past the grid's 8, its icon tooltip's
	 * lines ({@link #bringTooltip}) on one line: {@code Soul rune, Bring: 20, GE value: 3,000 gp}.
	 */
	static String bringMoreLine(String name, long quantity, Long value)
	{
		return String.join(", ", bringTooltip(name, quantity, value));
	}

	/** The All setups line's label: {@code All setups (6 tasks)}. */
	static String allSetups(TaskPlan.Summary summary)
	{
		return "All setups (" + tasks(summary.getTasks()) + ")";
	}

	/**
	 * {@code text} broken into lines at spaces, each as many words as fit {@code width} as {@code measure} measures
	 * them (the card's sentences, in its font): {@code No completed Abyssal demons tasks yet,} / {@code so no
	 * estimate.} A word wider than {@code width} has a line of its own, which the card cuts short.
	 */
	static List<String> wrap(String text, int width, ToIntFunction<String> measure)
	{
		return wrap(text, width, width, Integer.MAX_VALUE, measure);
	}

	/**
	 * {@code text} broken into at most {@code maxLines} lines at spaces: the first as many words as fit
	 * {@code firstWidth}, each later one as many as fit {@code width}, as {@code measure} measures them (the card's
	 * title and option names, which have two lines: an option's name wraps beside its sample size first,
	 * {@code Cannon, melee, Smoke} / {@code Dungeon}). The last line allowed holds the rest of the text, which the card
	 * cuts short with {@code …} when it doesn't fit, its tooltip holding the whole. A word wider than its line has a
	 * line of its own.
	 */
	static List<String> wrap(String text, int firstWidth, int width, int maxLines, ToIntFunction<String> measure)
	{
		final List<String> lines = new ArrayList<>();
		String line = "";
		for (String word : text.trim().split(" +"))
		{
			final String longer = line.isEmpty() ? word : line + " " + word;
			final boolean last = lines.size() >= maxLines - 1;
			if (line.isEmpty() || last || measure.applyAsInt(longer) <= (lines.isEmpty() ? firstWidth : width))
			{
				line = longer;
			}
			else
			{
				lines.add(line);
				line = word;
			}
		}
		if (!line.isEmpty())
		{
			lines.add(line);
		}
		return lines;
	}

	/**
	 * A spot's short name for narrow places: the table's short name ({@link SlayerSpots#shortName}), or for an unlisted
	 * region's spot the player's name for the region (cut to {@link SlayerSpots#MAX_SHORT_NAME_LENGTH} characters),
	 * else {@code Region N}. A key the table doesn't know is shown as it is.
	 */
	static String shortSpotName(String spotKey, SlayerSpots spots, IntFunction<String> regionNames)
	{
		final Integer region = SlayerSpots.keyRegion(spotKey);
		if (region != null && regionNames != null)
		{
			final String named = TaskLocation.normalizeName(regionNames.apply(region));
			if (named != null)
			{
				return named.length() > SlayerSpots.MAX_SHORT_NAME_LENGTH
					? named.substring(0, SlayerSpots.MAX_SHORT_NAME_LENGTH).trim() : named;
			}
		}
		final String name = (spots == null ? SlayerSpots.NONE : spots).shortName(spotKey);
		if (name != null)
		{
			return name;
		}
		return region != null ? SlayerSpots.regionName(region) : spotKey;
	}
}
