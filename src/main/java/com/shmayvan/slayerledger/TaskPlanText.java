package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.util.QuantityFormatter;

/**
 * The advisor plan's words (V2 plan 6, {@code DESIGN_PRINCIPLES.md} section 5): the assignment chat line, the
 * estimated times and paces, the thin-data and no-history phrases, and the advisor card's text (its
 * title, option names and rows, the Konar and Wilderness notes, the collapsed line and the skip / block badge), so
 * {@link AdvisorCardSection} has no wording of its own. Plain text, apart from the chat line as a chat message
 * ({@link #chatMessage}), which {@link ChatMessageBuilder} escapes and colours; the card escapes what it puts in HTML.
 * Straight quotes, no em dashes, {@code ~} before every estimate. Pure (RuneLite's static {@link QuantityFormatter}
 * and its string-only {@link ChatMessageBuilder} only).
 */
final class TaskPlanText
{
	/** The chat line's budget, in characters. */
	static final int MAX_CHAT_LENGTH = 120;
	/** The card's note when the plan has tasks but no option: every task's spot or style is Unknown. */
	static final String SETUP_UNKNOWN = "Setup unknown for these tasks.";
	private static final String RUNE_SUFFIX = " rune";

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
	 * The assignment chat line, plain text and at most {@link #MAX_CHAT_LENGTH} characters, for example
	 * {@code Dust devils x142: barrage, Catacombs, ~24 min; ~440 death, ~220 blood (3 tasks). Cannon, Smoke Dungeon:
	 * ~41 min (2).}: the task and its kills left; the recommendation's setup (cannon when it uses one, then its style),
	 * its spot's short name and time; its two most valuable bring items; its sample size; then the counterpart's cannon
	 * choice, its spot when it isn't the recommendation's, and its time with its sample size
	 * ({@code No cannon: ~46 min (1).}). A {@code ~} marks an estimated tag. The counterpart matters more than the
	 * items, so the line is the first of these that fits: everything; the first item and the counterpart; the
	 * counterpart alone; the first item alone; neither.
	 *
	 * <p>With no completed task: {@code Dust devils x142: no completed tasks yet.}, and on a Wilderness task whose
	 * creature has completed tasks but none in the Wilderness ({@link TaskPlan#isWildernessHistoryMissing}):
	 * {@code Abyssal demons x40: no completed Wilderness tasks yet.} With tasks but no option (every task
	 * has an Unknown spot or style): {@code Dust devils x142: ~40 min (3 tasks, setup unknown).} With the kills left
	 * unknown ({@link TaskPlan#hasKillsLeft}) the task has no {@code x142} and each time is a pace:
	 * {@code Dust devils: barrage, Catacombs, ~0:12/kill (3 tasks).}</p>
	 *
	 * @param task        the task's name as shown ({@link LedgerRecord#displayName}); {@code null} is the plan's
	 * @param regionNames the player's names for map regions (an unlisted region's spot), {@code null} when unknown
	 * @param itemNames   item names, as far as they are known
	 */
	static String chatLine(String task, TaskPlan plan, SlayerSpots spots, IntFunction<String> regionNames,
		ItemDetails.ItemNames itemNames)
	{
		final boolean perKill = !plan.hasKillsLeft();
		final String head = chatHead(task, plan) + " ";
		if (!plan.hasHistory())
		{
			return head + (plan.isWildernessHistoryMissing() ? "no completed Wilderness tasks yet."
				: "no completed tasks yet.");
		}
		final TaskPlan.Option best = plan.getRecommendation();
		if (best == null)
		{
			final TaskPlan.Summary all = plan.getAllSetups();
			return head + time(all.getTime(), perKill) + " (" + tasks(all.getTasks()) + ", setup unknown).";
		}
		final List<String> bring = new ArrayList<>();
		for (TaskPlan.BringItem item : best.getBring())
		{
			if (bring.size() == 2)
			{
				break;
			}
			bring.add(LedgerHistory.APPROXIMATE + QuantityFormatter.formatNumber(item.getQuantity()) + " "
				+ chatItemName(item.getItemId(), item.getQuantity(), itemNames));
		}
		final List<String> first = bring.subList(0, Math.min(1, bring.size()));
		final String spot = shortSpotName(best.getSpot(), spots, regionNames);
		final String setup = head + setup(best) + ", " + spot + ", " + time(best.getTime(), perKill);
		final String n = " (" + tasks(best.getTasks()) + ").";
		final TaskPlan.Option other = plan.getCounterpart();
		String counterpart = "";
		if (other != null)
		{
			final String otherSpot = shortSpotName(other.getSpot(), spots, regionNames);
			counterpart = " " + (other.isCannonEstimated() ? LedgerHistory.APPROXIMATE : "")
				+ (other.isCannon() ? "Cannon" : "No cannon") + (otherSpot.equals(spot) ? "" : ", " + otherSpot) + ": "
				+ time(other.getTime(), perKill) + " (" + other.getTasks() + ").";
		}

		final String[] lines = {
			setup + items(bring) + n + counterpart,
			setup + items(first) + n + counterpart,
			setup + n + counterpart,
			setup + items(first) + n,
			setup + n,
		};
		for (String line : lines)
		{
			if (line.length() <= MAX_CHAT_LENGTH)
			{
				return line;
			}
		}
		return lines[lines.length - 1];
	}

	/**
	 * The start of the chat line ({@link #chatLine}): the task and its kills left, {@code Dust devils x142:}, or with
	 * the kills left unknown {@code Dust devils:}.
	 *
	 * @param task the task's name as shown; {@code null} is the plan's
	 */
	static String chatHead(String task, TaskPlan plan)
	{
		final String name = task != null ? task : plan.getTask();
		return (name == null ? "Unknown task" : name)
			+ (plan.hasKillsLeft() ? " x" + QuantityFormatter.formatNumber(plan.getKillsLeft()) : "") + ":";
	}

	/**
	 * The chat line ({@link #chatLine}) as a RuneLite chat message ({@code DESIGN_PRINCIPLES.md} 4.5): its head
	 * ({@link #chatHead}) in the player's chat highlight colour and the rest in the normal one
	 * ({@link ChatColorType#HIGHLIGHT}, {@link ChatColorType#NORMAL}), so it follows the player's chat colours.
	 * {@link ChatMessageBuilder#append(String)} escapes the plain text (a name the player gave a region can't add
	 * tags), so nothing is escaped before; no text goes through its unescaped {@code append(Color, String)}.
	 */
	static String chatMessage(String task, TaskPlan plan, SlayerSpots spots, IntFunction<String> regionNames,
		ItemDetails.ItemNames itemNames)
	{
		final String line = chatLine(task, plan, spots, regionNames, itemNames);
		final String head = chatHead(task, plan);
		return new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append(head)
			.append(ChatColorType.NORMAL)
			// The line starts with its head (chatLine builds it from chatHead).
			.append(line.substring(head.length()))
			.build();
	}

	/**
	 * An option's time ({@code ~60 min}): for the kills left ({@link #estimatedTime}), or its pace ({@code ~0:12/kill},
	 * {@link #estimatedPace}) with them unknown ({@code perKill}).
	 */
	static String time(TaskPlan.Time time, boolean perKill)
	{
		return perKill ? estimatedPace(time.getPerKillMillis()) : estimatedTime(time.getMillis());
	}

	/** {@code ; ~440 death, ~220 blood}, or nothing without items. */
	private static String items(List<String> bring)
	{
		return bring.isEmpty() ? "" : "; " + String.join(", ", bring);
	}

	/** {@code barrage}, {@code cannon, ranged} or {@code ~barrage}: the option's cannon when it uses one, its style. */
	private static String setup(TaskPlan.Option option)
	{
		final String style = SetupTags.shown(option.getStyle().getLabel().toLowerCase(Locale.ROOT),
			option.isStyleEstimated());
		return option.isCannon() ? SetupTags.shown("cannon", option.isCannonEstimated()) + ", " + style : style;
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
	 * task's cannon or style is an estimate ({@link TaskPlan.Option#isEstimated}, a migrated 1.x task).
	 */
	static String optionTasks(TaskPlan.Option option)
	{
		return optionTasks(option, false);
	}

	/**
	 * {@link #optionTasks(TaskPlan.Option)}, or for a thin recommendation ({@link TaskPlan#isRecommendationThin}:
	 * {@code thin}) {@code based on 1 task} ({@link #basedOn}), {@code based on 1 task, estimated} (V2 plan 6,
	 * DESIGN_PRINCIPLES.md 3.5).
	 */
	static String optionTasks(TaskPlan.Option option, boolean thin)
	{
		return (thin ? basedOn(option.getTasks()) : tasks(option.getTasks()))
			+ (option.isEstimated() ? ", estimated" : "");
	}

	/**
	 * An option's range, the 20th to the 80th percentile ({@link TaskPlan.Time}) in whole minutes: {@code 54 to 58 min},
	 * from an hour {@code 54 min to 1 h 6 min}; with the kills left unknown ({@code perKill}) its paces,
	 * {@code 0:10 to 0:14/kill}. {@code null} when both ends read the same, as with one task.
	 */
	static String timeRange(TaskPlan.Time time, boolean perKill)
	{
		if (perKill)
		{
			final String low = paceClock(time.getLowMillis());
			final String high = paceClock(time.getHighMillis());
			return low.equals(high) ? null : low + " to " + high + "/kill";
		}
		final long low = minutes(time.getLowMillis());
		final long high = minutes(time.getHighMillis());
		if (low == high)
		{
			return null;
		}
		return high < 60 ? low + " to " + high + " min" : duration(low) + " to " + duration(high);
	}

	/** The supplies row's label: {@code Supplies}, or {@code Supplies/kill} with the kills left unknown. */
	static String suppliesLabel(boolean perKill)
	{
		return perKill ? "Supplies/kill" : "Supplies";
	}

	/**
	 * An option's gear on the card: {@code mostly Prayer}, or {@link LedgerFormat#NONE} without a majority
	 * ({@link TaskPlan.Option#getGear}).
	 */
	static String gear(TaskPlan.Option option)
	{
		return option.getGear() == null ? LedgerFormat.NONE : "mostly " + option.getGear().getLabel();
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

	/** The label over the counterpart: {@code With cannon} or {@code Without cannon}, its cannon choice. */
	static String counterpartLabel(TaskPlan.Option counterpart)
	{
		return counterpart.isCannon() ? "With cannon" : "Without cannon";
	}

	/** The All setups line's label: {@code All setups (6 tasks)}. */
	static String allSetups(TaskPlan.Summary summary)
	{
		return "All setups (" + tasks(summary.getTasks()) + ")";
	}

	/** The toggle of the other setups: {@code More setups (2)}, or once open {@code Hide more setups}. */
	static String moreSetups(int count, boolean open)
	{
		return open ? "Hide more setups" : "More setups (" + count + ")";
	}

	/** One of the other setups' value: its time and sample size, {@code ~82 min (2 tasks)}. */
	static String moreSetupValue(TaskPlan.Option option, boolean perKill)
	{
		return time(option.getTime(), perKill) + " (" + tasks(option.getTasks()) + ")";
	}

	/**
	 * The card collapsed to one line after the first kill: {@code Plan: barrage, ~60 min (3 tasks)} (the
	 * recommendation's setup as in the chat line, its time and sample size); with tasks but no option
	 * {@code Plan: ~40 min (3 tasks, setup unknown)}; without history {@code Plan: no completed tasks yet} (or
	 * {@code no completed Wilderness tasks yet}).
	 */
	static String collapsedLine(TaskPlan plan)
	{
		final boolean perKill = !plan.hasKillsLeft();
		if (!plan.hasHistory())
		{
			return "Plan: " + (plan.isWildernessHistoryMissing() ? "no completed Wilderness tasks yet"
				: "no completed tasks yet");
		}
		final TaskPlan.Option best = plan.getRecommendation();
		if (best == null)
		{
			final TaskPlan.Summary all = plan.getAllSetups();
			return "Plan: " + time(all.getTime(), perKill) + " (" + tasks(all.getTasks()) + ", setup unknown)";
		}
		return "Plan: " + setup(best) + ", " + time(best.getTime(), perKill) + " (" + tasks(best.getTasks()) + ")";
	}

	/**
	 * The collapsed line's tooltip lines, plain text: the recommendation's name, time and range and sample size
	 * ({@code based on 1 task} when thin), then the counterpart's label, time and sample size; nothing to add without
	 * a recommendation.
	 */
	static List<String> collapsedTooltip(TaskPlan plan, String task, SlayerSpots spots, IntFunction<String> regionNames)
	{
		final List<String> lines = new ArrayList<>();
		final TaskPlan.Option best = plan.getRecommendation();
		if (best == null)
		{
			return lines;
		}
		final boolean perKill = !plan.hasKillsLeft();
		lines.add(optionName(best, task, spots, regionNames) + " (" + optionTasks(best, plan.isRecommendationThin())
			+ ")");
		final String range = timeRange(best.getTime(), perKill);
		lines.add(time(best.getTime(), perKill) + (range == null ? "" : ", " + range));
		final TaskPlan.Option other = plan.getCounterpart();
		if (other != null)
		{
			lines.add(counterpartLabel(other) + ": " + optionName(other, task, spots, regionNames) + ", "
				+ time(other.getTime(), perKill) + " (" + tasks(other.getTasks()) + ")");
		}
		return lines;
	}

	/**
	 * The skip / block badge on the card: {@code Consider skip (n=6)} or {@code Consider block (n=6)};
	 * {@code null} for Do, which adds no line, or no advice.
	 */
	static String badge(TaskAdvisor.Result result)
	{
		if (result == null || result.getAdvice() == TaskAdvisor.Advice.DO)
		{
			return null;
		}
		return result.getAdvice() + " (n=" + result.getTasks() + ")";
	}

	/**
	 * The badge's tooltip lines, plain text: why ({@link TaskAdvisor.Result#reason}), what it counts (each task's
	 * Catacombs variant and cannon tasks pool with it) and the rules it needs.
	 */
	static List<String> badgeReason(TaskAdvisor.Result result, LootPriceMode mode)
	{
		final List<String> lines = new ArrayList<>();
		lines.add(result.reason(mode));
		lines.add("From " + result.getTasks() + " completed tasks of this monster (n), Catacombs variant and cannon"
			+ " tasks pooled with their task. Advice only.");
		lines.add("Counts monsters with " + TaskAdvisor.MIN_TASKS + "+ tasks and "
			+ TaskAdvisor.MIN_ACTIVE_MILLIS / 60_000L + "+ min; Consider block needs n of "
			+ TaskAdvisor.MIN_BLOCK_TASKS + "+. " + (result.isBoss() ? "Bosses rank only against bosses."
			: "Boss tasks are ranked separately."));
		return lines;
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

	/** Whole minutes of {@code millis}, rounded, at least 1 (as {@link #estimatedTime}). */
	private static long minutes(long millis)
	{
		return Math.max(1, (Math.max(0, millis) + 30_000) / 60_000);
	}

	/** {@code 54 min}, from an hour {@code 1 h 6 min}, on the hour {@code 2 h} (DESIGN_PRINCIPLES.md 5.5). */
	private static String duration(long minutes)
	{
		return LedgerFormat.minutes(minutes);
	}

	/** {@link #estimatedPace}'s clock without its {@code ~} and {@code /kill}: {@code 0:12}. */
	private static String paceClock(long millis)
	{
		final String pace = estimatedPace(millis);
		return pace.substring(LedgerHistory.APPROXIMATE.length(), pace.length() - "/kill".length());
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

	/**
	 * An item as the chat line names an amount of it: the game's name without its dose or charge suffix
	 * ({@link ItemDetails#displayName}), starting lower case. A rune drops "rune" (the brevity exception of
	 * {@code DESIGN_PRINCIPLES.md} 5.4: {@code death}). Anything else is plural for an amount other than 1: the word
	 * before " of ", " to " or " with " ({@code rings of dueling}, {@code potatoes with cheese}), else the last word;
	 * a word ending in "s", "fish" or no letter stays as it is, one ending in "sh", "ch", "x" or "o" takes "es", one
	 * ending in a consonant and "y" takes "ies" for the "y" ({@code curries}), any other "s".
	 */
	static String chatItemName(int itemId, long quantity, ItemDetails.ItemNames names)
	{
		final String name = ItemDetails.displayName(itemId, names, true);
		final String lower = name.toLowerCase(Locale.ROOT);
		if (lower.endsWith(RUNE_SUFFIX) && lower.length() > RUNE_SUFFIX.length())
		{
			return lower.substring(0, lower.length() - RUNE_SUFFIX.length());
		}
		final String shown = name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1);
		return quantity == 1 ? shown : plural(shown);
	}

	private static String plural(String name)
	{
		int end = name.length();
		for (String joint : new String[]{" of ", " to ", " with "})
		{
			final int at = name.indexOf(joint);
			if (at > 0 && at < end)
			{
				end = at;
			}
		}
		final String word = name.substring(name.lastIndexOf(' ', end - 1) + 1, end).toLowerCase(Locale.ROOT);
		int stem = end;
		final String suffix;
		if (word.isEmpty() || !Character.isLetter(word.charAt(word.length() - 1)) || word.endsWith("s")
			|| word.endsWith("fish"))
		{
			suffix = "";
		}
		else if (word.endsWith("sh") || word.endsWith("ch") || word.endsWith("x") || word.endsWith("o"))
		{
			suffix = "es";
		}
		else if (word.length() > 1 && word.endsWith("y") && isConsonant(word.charAt(word.length() - 2)))
		{
			stem = end - 1;
			suffix = "ies";
		}
		else
		{
			suffix = "s";
		}
		return name.substring(0, stem) + suffix + name.substring(end);
	}

	/** Whether {@code c} is a letter other than a, e, i, o or u. */
	private static boolean isConsonant(char c)
	{
		return Character.isLetter(c) && "aeiou".indexOf(Character.toLowerCase(c)) < 0;
	}
}
