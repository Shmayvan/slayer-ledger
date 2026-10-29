package com.shmayvan.slayerledger;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.TreeSet;
import net.runelite.client.util.QuantityFormatter;

/**
 * Pure sorting, filtering and formatting for the task-history view and the live session values. No Swing, client
 * or file access (only RuneLite's static {@link QuantityFormatter}), so it can be unit-tested and called from any
 * thread.
 */
final class LedgerHistory
{
	/** Label of the filter entry that shows every monster. */
	static final String ALL = "All";
	static final String UNKNOWN_MONSTER = "Unknown task";
	/** Shown instead of a value that was never recorded (tasks saved before values were tracked). */
	static final String NO_VALUE = "-";
	/** Prefix of a total that leaves out items whose GE price was unavailable. */
	static final String APPROXIMATE = "~";
	/** Time left until a session has enough kills and active time for an estimate ({@link #formatTimeLeft}). */
	static final String TIME_LEFT_ESTIMATING = "estimating...";
	/** Tooltip text for a task saved before loot and supply values were tracked. */
	static final String NOT_VALUED = "Not valued: this task was saved before loot and supply values were tracked.";
	/** Tooltip line of a history row valued at today's GE prices. */
	static final String CURRENT_PRICES = "Prices: today's GE prices (high alch values don't change)";
	/** Tooltip line of a history row saved without per-item prices, which keeps its saved totals. */
	static final String SAVED_TOTALS = "Prices: saved totals (no items kept), so GE values are marked ~";

	/** Newest first by end time; ties broken by start time (newest first), then id, for a stable order. */
	static final Comparator<LedgerRecord> NEWEST_FIRST = Comparator
		.comparingLong(LedgerRecord::getEndedAt).reversed()
		.thenComparing(Comparator.comparingLong(LedgerRecord::getStartedAt).reversed())
		.thenComparing(r -> r.getId() == null ? "" : r.getId());

	private LedgerHistory()
	{
	}

	/**
	 * Name shown for a record, and what the Monster filter matches: its {@link LedgerRecord#groupName} (the Catacombs
	 * variant for a task done on one); records without a name (very old or hand-edited files) show a fallback.
	 */
	static String monster(LedgerRecord record)
	{
		String monster = record.groupName();
		return monster == null || monster.isEmpty() ? UNKNOWN_MONSTER : monster;
	}

	/** Copy of {@code records} without nulls, newest first. */
	static List<LedgerRecord> sortNewestFirst(Collection<LedgerRecord> records)
	{
		List<LedgerRecord> sorted = new ArrayList<>(records.size());
		for (LedgerRecord r : records)
		{
			if (r != null)
			{
				sorted.add(r);
			}
		}
		sorted.sort(NEWEST_FIRST);
		return sorted;
	}

	/** Filter choices: {@link #ALL} followed by each distinct monster in the records, alphabetically (ignoring case). */
	static List<String> filterOptions(Collection<LedgerRecord> records)
	{
		TreeSet<String> names = new TreeSet<>(Comparator.comparing((String s) -> s.toLowerCase(Locale.ROOT)).thenComparing(s -> s));
		for (LedgerRecord r : records)
		{
			if (r != null)
			{
				names.add(monster(r));
			}
		}
		List<String> options = new ArrayList<>(names.size() + 1);
		options.add(ALL);
		options.addAll(names);
		return options;
	}

	/**
	 * The filter selection to keep after the options change: {@code selection} while it is still one of the monster
	 * options, otherwise {@code null} ("All").
	 */
	static String keepSelection(List<String> options, String selection)
	{
		return selection != null && !ALL.equals(selection) && options.contains(selection) ? selection : null;
	}

	/** "kills / originalAmount" when the assigned amount is known, otherwise just the kills. */
	static String formatKills(LedgerRecord record)
	{
		Integer original = record.getOriginalAmount();
		if (original != null && original > 0)
		{
			return record.getKills() + " / " + original;
		}
		return Integer.toString(record.getKills());
	}

	/** Active time as H:MM:SS. */
	static String formatDuration(long millis)
	{
		long totalSeconds = Math.max(0, millis) / 1000;
		return String.format("%d:%02d:%02d", totalSeconds / 3600, (totalSeconds / 60) % 60, totalSeconds % 60);
	}

	/**
	 * A time per kill in short form, rounded to whole seconds (half up): {@code 18s} under a minute,
	 * {@code 4m 05s} under an hour and {@code 1h 02m} from an hour on (minutes rounded half up). A negative time counts
	 * as none ({@code 0s}). Task lengths and elapsed time keep {@link #formatDuration}.
	 */
	static String formatKillTime(long millis)
	{
		long clamped = Math.max(0, millis);
		long seconds = clamped / 1000 + (clamped % 1000 >= 500 ? 1 : 0);
		if (seconds < 60)
		{
			return seconds + "s";
		}
		if (seconds < 3600)
		{
			return String.format("%dm %02ds", seconds / 60, seconds % 60);
		}
		// Minutes from the exact time, not the rounded seconds, so a time is rounded only once.
		long minutes = clamped / 60_000 + (clamped % 60_000 >= 30_000 ? 1 : 0);
		return String.format("%dh %02dm", minutes / 60, minutes % 60);
	}

	/**
	 * Estimated time left, rounded to the nearest whole minute (half up), without seconds: {@code ~44m} under an hour,
	 * {@code ~1h 05m} from an hour on, and {@code ~<1m} when it rounds to no minutes. A negative time counts as none;
	 * the saturated {@link Long#MAX_VALUE} of {@link #historicalTimeLeftMillis} formats without overflowing.
	 */
	static String formatTimeLeftMinutes(long millis)
	{
		long clamped = Math.max(0, millis);
		long minutes = clamped / 60_000 + (clamped % 60_000 >= 30_000 ? 1 : 0);
		if (minutes == 0)
		{
			return "~<1m";
		}
		if (minutes < 60)
		{
			return "~" + minutes + "m";
		}
		return String.format("~%dh %02dm", minutes / 60, minutes % 60);
	}

	/**
	 * Estimated time left on the task from the session's pace alone: {@link #formatTimeLeft(long, int, int, Long)}
	 * with no history.
	 */
	static String formatTimeLeft(long activeMillis, int kills, int remaining)
	{
		return formatTimeLeft(activeMillis, kills, remaining, null);
	}

	/**
	 * Estimated time left on the task, in whole minutes ({@link #formatTimeLeftMinutes}): at the session's pace so far
	 * (see {@link TaskTimeEstimate}); until there are enough kills and active time for that, at the historical time per
	 * kill when there is one ({@link #historicalTimeLeftMillis}), else {@link #TIME_LEFT_ESTIMATING}; or
	 * {@link #NO_VALUE} when no kills are left on the task (no task, the task is done, or the kills left are unknown).
	 * The sidebar and the overlay both show it, so they can't disagree.
	 *
	 * @param historicalMillisPerKill the task's average active time per kill in its completed history
	 *                                ({@link TaskStatistics#getMillisPerKill}), or {@code null} when it has none
	 */
	static String formatTimeLeft(long activeMillis, int kills, int remaining, Long historicalMillisPerKill)
	{
		if (remaining <= 0)
		{
			return NO_VALUE;
		}
		OptionalLong estimate = TaskTimeEstimate.remainingMillis(activeMillis, kills, remaining);
		if (estimate.isPresent())
		{
			return formatTimeLeftMinutes(estimate.getAsLong());
		}
		return historicalMillisPerKill == null ? TIME_LEFT_ESTIMATING
			: formatTimeLeftMinutes(historicalTimeLeftMillis(historicalMillisPerKill, remaining));
	}

	/**
	 * Whether {@link #formatTimeLeft(long, int, int, Long)} shows the historical estimate for these values (rather than
	 * the live pace, {@link #TIME_LEFT_ESTIMATING} or {@link #NO_VALUE}).
	 */
	static boolean isHistoricalTimeLeft(long activeMillis, int kills, int remaining, Long historicalMillisPerKill)
	{
		return remaining > 0 && historicalMillisPerKill != null
			&& !TaskTimeEstimate.remainingMillis(activeMillis, kills, remaining).isPresent();
	}

	/**
	 * The historical time left: {@code millisPerKill * remaining}, saturated at {@link Long#MAX_VALUE} as
	 * {@link TaskStatistics#estimate} does.
	 */
	static long historicalTimeLeftMillis(long millisPerKill, int remaining)
	{
		try
		{
			return Math.multiplyExact(millisPerKill, (long) remaining);
		}
		catch (ArithmeticException e)
		{
			return millisPerKill >= 0 == remaining >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
		}
	}

	static String status(LedgerRecord record)
	{
		return record.isCompleted() ? "Completed" : "Partial";
	}

	/** Short, locale-style date/time formatter in the given zone (the system zone in the panel). */
	static DateTimeFormatter shortDateTime(ZoneId zone)
	{
		return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(zone);
	}

	/** End time formatted with {@code formatter}, or "-" when the record has no end time. */
	static String formatEndedAt(LedgerRecord record, DateTimeFormatter formatter)
	{
		long endedAt = record.getEndedAt();
		if (endedAt <= 0)
		{
			return "-";
		}
		return formatter.format(Instant.ofEpochMilli(endedAt));
	}

	/** Text of the delete confirmation dialog. */
	static String deleteConfirmation(LedgerRecord record)
	{
		return String.format("Delete this %s task (%d kills)? This can't be undone.", monster(record), record.getKills());
	}

	// ---- Values ----

	/** Compact gp amount as in core Loot Tracker ({@code QuantityFormatter}, e.g. "9,450 gp", "350K gp", "1.23M gp"). */
	static String formatGp(long value)
	{
		return QuantityFormatter.quantityToStackSize(value) + " gp";
	}

	/**
	 * Compact gp amount, {@link #NO_VALUE} when {@code value} is {@code null} (never "0"), prefixed with
	 * {@link #APPROXIMATE} when {@code approximate}.
	 */
	static String formatGp(Long value, boolean approximate)
	{
		if (value == null)
		{
			return NO_VALUE;
		}
		return (approximate ? APPROXIMATE : "") + formatGp(value.longValue());
	}

	/**
	 * Like {@link #formatGp(Long, boolean)} without the " gp" suffix, for history rows: with the price label the loot
	 * and supplies don't fit one sidebar line otherwise.
	 */
	static String formatAmount(Long value, boolean approximate)
	{
		if (value == null)
		{
			return NO_VALUE;
		}
		return (approximate ? APPROXIMATE : "") + QuantityFormatter.quantityToStackSize(value);
	}

	/** Full gp amount with thousands separators, for tooltips. */
	static String formatGpExact(long value)
	{
		return QuantityFormatter.formatNumber(value) + " gp";
	}

	/**
	 * Loot value minus supplies value, saturating at the {@code long} range; {@code null} when the loot was not valued.
	 * A {@code null} supplies value counts as 0 (no supplies recorded).
	 */
	static Long profit(Long loot, Long supplies)
	{
		if (loot == null)
		{
			return null;
		}
		if (supplies == null)
		{
			return loot;
		}
		try
		{
			return Math.subtractExact(loot, supplies);
		}
		catch (ArithmeticException e)
		{
			return supplies < 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
		}
	}

	/** Whether a record was valued when saved; records saved before values were tracked were not. */
	static boolean isValued(LedgerRecord record)
	{
		return record.getLootGeValue() != null;
	}

	/**
	 * The record's stored loot GE value, or {@code null} when it was not valued. This is the raw saved total (an
	 * untradeable item at 0); what the history shows comes from {@link HistoryPricing}, which values a GE price of 0
	 * at high alch.
	 */
	static Long lootGe(LedgerRecord record)
	{
		return record.getLootGeValue();
	}

	/**
	 * The record's supplies GE value: the saved total, 0 for a valued record without supplies (none were used), or
	 * {@code null} when the record was not valued.
	 */
	static Long suppliesGe(LedgerRecord record)
	{
		if (record.getSuppliesGeValue() != null)
		{
			return record.getSuppliesGeValue();
		}
		return isValued(record) ? 0L : null;
	}

	/**
	 * Stored loot GE minus supplies GE, or {@code null} when the record was not valued; see {@link #lootGe} (use
	 * {@link HistoryPricing} for the shown profit).
	 */
	static Long profitGe(LedgerRecord record)
	{
		return profit(lootGe(record), suppliesGe(record));
	}

	/** Distinct loot items whose GE price was unavailable when the record was saved. */
	static int lootUnpriced(LedgerRecord record)
	{
		Integer n = record.getLootUnpricedItems();
		return n == null ? 0 : Math.max(0, n);
	}

	/** Distinct supply items whose GE price was unavailable when the record was saved. */
	static int suppliesUnpriced(LedgerRecord record)
	{
		Integer n = record.getSuppliesUnpricedItems();
		return n == null ? 0 : Math.max(0, n);
	}

	/** Short name of a loot price mode for the panel's value labels: "GE", or "HA" for high alchemy. */
	static String priceLabel(LootPriceMode mode)
	{
		return mode == LootPriceMode.HIGH_ALCH ? "HA" : "GE";
	}

	/** "Loot (GE): " or "Loot (HA): ", the label of a loot value in {@code mode} ({@code null} counts as GE). */
	static String lootLabel(LootPriceMode mode)
	{
		return "Loot (" + priceLabel(mode) + "): ";
	}

	/** "Profit (GE): " or "Profit (HA): ", the label of a profit with the loot in {@code mode}. */
	static String profitLabel(LootPriceMode mode)
	{
		return profitName(mode) + ": ";
	}

	/** "Profit (GE)" or "Profit (HA)", the name of a profit with the loot in {@code mode} (the overlay's row label). */
	static String profitName(LootPriceMode mode)
	{
		return "Profit (" + priceLabel(mode) + ")";
	}

	/**
	 * The live session's profit amount ({@code 1.2M gp}): loot in its price mode minus supplies, approximate when
	 * either has an unavailable price. The sidebar's live profit line and the overlay both show it.
	 */
	static String formatLiveProfit(SessionValuation.Values values)
	{
		int unpriced = values.getLootValueUnpriced() + values.getSuppliesUnpriced();
		return formatGp(values.getProfit(), unpriced > 0);
	}

	/** "Loot (GE): ..." or "Loot (HA): ..." text of a history row: the loot value in the values' price mode. */
	static String formatLoot(HistoryPricing.Values values)
	{
		return lootLabel(values.getMode()) + formatAmount(values.getLootValue(), values.isLootApproximate());
	}

	/** "Supplies: ..." text of a history row (always at GE prices). */
	static String formatSupplies(HistoryPricing.Values values)
	{
		return "Supplies: " + formatAmount(values.getSuppliesGe(), values.isSuppliesApproximate());
	}

	/**
	 * "Profit (GE): ..." or "Profit (HA): ..." text of a history row: loot in the values' price mode minus supplies;
	 * approximate when either is.
	 */
	static String formatProfit(HistoryPricing.Values values)
	{
		return profitLabel(values.getMode()) + formatAmount(values.getProfit(), values.isProfitApproximate());
	}

	/**
	 * HTML tooltip of a history row: the exact values ({@code values}, which also say which prices and price mode are
	 * shown), followed by the superior spawns, bank trips and deaths ({@link #NO_VALUE} when the task was saved before
	 * they were counted), the task context, the boss kill counts, keys, clue scrolls and notable drops (when there are
	 * any) and the note, if any.
	 */
	static String rowTooltip(LedgerRecord record, HistoryPricing.Values values)
	{
		return rowTooltip(record, values, null);
	}

	/**
	 * HTML tooltip of a history row ({@link #rowTooltip(LedgerRecord, HistoryPricing.Values)}) with its location line
	 * ({@link TaskLocation#format}) after the values.
	 *
	 * @param location the location to show ({@link TaskLocation#displayName}), or {@code null} for no location line
	 */
	static String rowTooltip(LedgerRecord record, HistoryPricing.Values values, String location)
	{
		String valuesText = values.isValued() ? valuesTooltipBody(values) : NOT_VALUED;
		String note = record.getNote() == null ? "" : "<br>" + formatNote(record.getNote());
		String locationText = location == null ? "" : "<br>" + escapeHtml(TaskLocation.format(location));
		String variantText = variantTooltip(record);
		return "<html>" + valuesText + variantText + locationText + "<br>"
			+ formatSuperiors(record.getSuperiorsSpawned()) + "<br>"
			+ formatBankTrips(record.getBankTrips()) + "<br>" + formatDeaths(record.getDeaths())
			+ taskContextTooltip(record) + bossKillCountTooltip(record.getBossKillCounts(), record.getMonster())
			+ taskLootTooltip(record, values.getMode()) + note + "</html>";
	}

	/**
	 * The tooltip line of a task done on its Catacombs variant, {@code <br>Catacombs variant of Aberrant
	 * spectres}, or "" for the standard monster.
	 */
	static String variantTooltip(LedgerRecord record)
	{
		String variant = record.getVariant();
		if (variant == null || variant.trim().isEmpty() || record.getMonster() == null)
		{
			return "";
		}
		return "<br>" + escapeHtml("Catacombs variant of " + record.getMonster());
	}

	/**
	 * The boss kill count lines ({@link BossKillCounts#lines}) of a tooltip, each starting with {@code <br>}; "" when
	 * there are none (for example a record saved before they were recorded).
	 *
	 * @param taskName the task's name, or {@code null}
	 */
	static String bossKillCountTooltip(List<BossKillCount> counts, String taskName)
	{
		StringBuilder sb = new StringBuilder();
		for (String line : BossKillCounts.lines(counts, taskName))
		{
			sb.append("<br>").append(escapeHtml(line));
		}
		return sb.toString();
	}

	/**
	 * The keys, clue scrolls and notable drops lines of a history row's tooltip, each starting with {@code <br>}. Keys
	 * and clues come from the record's loot ({@link KeysAndClues}); a kind with none, and a record without loot or
	 * notable drops (for example one saved before they were recorded), has no line at all.
	 *
	 * @param mode the price mode the notable drops' values are shown in ({@code null} is GE)
	 */
	static String taskLootTooltip(LedgerRecord record, LootPriceMode mode)
	{
		StringBuilder sb = new StringBuilder();
		KeysAndClues keysAndClues = KeysAndClues.of(record.getLoot());
		if (keysAndClues.hasKeys())
		{
			sb.append("<br>").append(escapeHtml(keysAndClues.keysText()));
		}
		if (keysAndClues.hasClues())
		{
			sb.append("<br>").append(escapeHtml(keysAndClues.cluesText()));
		}
		sb.append(notableDropsTooltip(record.getNotableDrops(), mode));
		return sb.toString();
	}

	/** Most notable drops listed in the live panel; the rest are in its tooltip. */
	static final int LIVE_NOTABLE_DROP_LINES = 3;

	/**
	 * The live panel's keys, clue scrolls and notable drops lines: {@code Keys: 2 brimstone} and
	 * {@code Clues: 1 hard, 2 elite} when there are any, then {@code Notable drops:} with the newest
	 * {@link #LIVE_NOTABLE_DROP_LINES} drops (short values in {@code mode}) and {@code +N more} for the rest. Empty when
	 * there is nothing to show.
	 */
	static List<String> liveTaskLootLines(KeysAndClues keysAndClues, List<NotableDrop> drops, LootPriceMode mode)
	{
		List<String> lines = new ArrayList<>();
		if (keysAndClues != null && keysAndClues.hasKeys())
		{
			lines.add(keysAndClues.keysText());
		}
		if (keysAndClues != null && keysAndClues.hasClues())
		{
			lines.add(keysAndClues.cluesText());
		}
		List<NotableDrop> sorted = NotableDrops.newestFirst(drops);
		if (!sorted.isEmpty())
		{
			lines.add("Notable drops:");
			for (int i = 0; i < Math.min(LIVE_NOTABLE_DROP_LINES, sorted.size()); i++)
			{
				lines.add("  " + formatNotableDrop(sorted.get(i), mode, false));
			}
			if (sorted.size() > LIVE_NOTABLE_DROP_LINES)
			{
				lines.add("  +" + (sorted.size() - LIVE_NOTABLE_DROP_LINES) + " more");
			}
		}
		return lines;
	}

	/**
	 * {@code <br>Notable drops:} followed by one indented line per drop, newest first, with exact values in
	 * {@code mode}; "" when there are none.
	 */
	static String notableDropsTooltip(List<NotableDrop> drops, LootPriceMode mode)
	{
		List<NotableDrop> sorted = NotableDrops.newestFirst(drops);
		if (sorted.isEmpty())
		{
			return "";
		}
		StringBuilder sb = new StringBuilder("<br>Notable drops:");
		for (NotableDrop drop : sorted)
		{
			sb.append("<br>&nbsp;&nbsp;").append(escapeHtml(formatNotableDrop(drop, mode, true)));
		}
		return sb.toString();
	}

	/**
	 * One notable drop: {@code Abyssal whip: 1.5M}, {@code Rune bar x 25: 300K}, {@code Pet} or
	 * {@code Pet (already owned)}. The value is in {@code mode} ({@code null} is GE) as it was when the drop arrived,
	 * {@link #NO_VALUE} when unknown; {@code exact} gives the full amount in gp, for tooltips.
	 */
	static String formatNotableDrop(NotableDrop drop, LootPriceMode mode, boolean exact)
	{
		if (drop.isPet())
		{
			return drop.getPet() == NotableDrop.Pet.DUPLICATE ? "Pet (already owned)" : "Pet";
		}
		String name = drop.getName() != null ? drop.getName()
			: drop.getItemId() != null ? "Item " + drop.getItemId() : "Unknown drop";
		String quantity = drop.getQuantity() > 1 ? " x " + QuantityFormatter.formatNumber(drop.getQuantity()) : "";
		Long value = drop.value(mode);
		String valueText = value == null ? NO_VALUE
			: exact ? formatGpExact(value) : QuantityFormatter.quantityToStackSize(value);
		return name + quantity + ": " + valueText;
	}

	/**
	 * The task context lines of a history row's tooltip, each starting with {@code <br>}: the master, the type, the
	 * points earned and the streak. A part that is unknown (for example in a record saved before it was recorded) has
	 * no line at all.
	 */
	static String taskContextTooltip(LedgerRecord record)
	{
		StringBuilder sb = new StringBuilder();
		if (SlayerMaster.isKnown(record.getSlayerMaster()))
		{
			sb.append("<br>").append(escapeHtml(formatMaster(record.getSlayerMaster())));
		}
		TaskType type = TaskType.of(record.getSlayerMaster(), record.getCreatureId());
		if (type != null)
		{
			sb.append("<br>").append(escapeHtml(formatTaskType(type, record.getTaskLocation())));
		}
		if (record.getPointsEarned() != null)
		{
			sb.append("<br>").append(formatPointsEarned(record.getPointsEarned()));
		}
		if (record.getTaskStreak() != null)
		{
			sb.append("<br>").append(formatTaskStreak(record.getTaskStreak()));
		}
		return sb.toString();
	}

	/**
	 * {@code Master: Konar}; {@code Unknown master (n)} for a value not in {@link SlayerMaster}'s table; or
	 * {@code Master: -} when no master is known.
	 */
	static String formatMaster(Integer master)
	{
		if (!SlayerMaster.isKnown(master))
		{
			return "Master: " + NO_VALUE;
		}
		String name = SlayerMaster.displayName(master);
		return SlayerMaster.hasName(master) ? "Master: " + name : name;
	}

	/**
	 * {@code Type: Konar (Kraken Cove)}, {@code Type: Boss} and so on, or {@code Type: -} when the type is unknown.
	 *
	 * @param location the task's assigned area name (Konar's tasks), or {@code null}
	 */
	static String formatTaskType(TaskType type, String location)
	{
		if (type == null)
		{
			return "Type: " + NO_VALUE;
		}
		return "Type: " + type + (location == null ? "" : " (" + location + ")");
	}

	/** {@code Points earned: N}, or {@code Points earned: -} when unknown. */
	static String formatPointsEarned(Integer pointsEarned)
	{
		return "Points earned: " + formatCount(pointsEarned);
	}

	/** {@code Task streak: N}, or {@code Task streak: -} when unknown. */
	static String formatTaskStreak(Integer taskStreak)
	{
		return "Task streak: " + formatCount(taskStreak);
	}

	/** {@code Note: ...} for an HTML tooltip, with the note's HTML special characters escaped. */
	static String formatNote(String note)
	{
		return "Note: " + escapeHtml(note);
	}

	/** Escapes {@code &}, {@code <} and {@code >} for Swing HTML text; {@code null} becomes "". */
	static String escapeHtml(String s)
	{
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** Text of the history "Edit note" dialog. */
	static String editNotePrompt(LedgerRecord record)
	{
		return String.format("Note for this %s task (up to %d characters; leave empty to remove it):", monster(record),
			TaskNote.MAX_LENGTH);
	}

	/** {@code Superiors: N}, or {@code Superiors: -} when not counted. */
	static String formatSuperiors(Integer superiorsSpawned)
	{
		return "Superiors: " + formatCount(superiorsSpawned);
	}

	/** {@code Bank trips: N}, or {@code Bank trips: -} when not counted. */
	static String formatBankTrips(Integer bankTrips)
	{
		return "Bank trips: " + formatCount(bankTrips);
	}

	/** {@code Deaths: N}, or {@code Deaths: -} when not counted. */
	static String formatDeaths(Integer deaths)
	{
		return "Deaths: " + formatCount(deaths);
	}

	/**
	 * A history row's XP: {@code XP: 12,345}, or {@code XP: -} for a task saved before XP was tracked (see
	 * {@link SkillXp#totalText}).
	 */
	static String formatXp(LedgerRecord record)
	{
		return SkillXp.totalText(record.getXpGained(), 0);
	}

	/**
	 * A history row's XP per hour of active time, labelled so it doesn't read as gp: {@code XP/h: 40.1K}; ""
	 * when the XP was not recorded or the task had too little active time for a rate.
	 */
	static String formatXpRate(LedgerRecord record)
	{
		if (record.getXpGained() == null)
		{
			return "";
		}
		Long rate = SkillXp.perHour(SkillXp.total(record.getXpGained()), record.getActiveMillis());
		return rate == null ? "" : "XP/h: " + formatAmount(rate, false);
	}

	/** A count, or {@link #NO_VALUE} when it is unknown ({@code null}). Never shows an unknown count as 0. */
	static String formatCount(Integer count)
	{
		return count == null ? NO_VALUE : Integer.toString(count);
	}

	/** Tooltip body of a valued history row: the values, the prices used and any approximations. */
	private static String valuesTooltipBody(HistoryPricing.Values v)
	{
		long lootGe = v.getLootGe() != null ? v.getLootGe() : 0;
		long lootHa = v.getLootHa() != null ? v.getLootHa() : 0;
		long suppliesGe = v.getSuppliesGe() != null ? v.getSuppliesGe() : 0;
		long suppliesHa = v.getSuppliesHa() != null ? v.getSuppliesHa() : 0;
		return valuesTooltipBody(v.getMode(), v.getLootValue(), lootGe, lootHa, suppliesGe, suppliesHa,
			v.getLootUnpriced(), v.getLootGeUnpriced(), v.getSuppliesUnpriced())
			+ (v.isCurrentPrices() ? "<br>" + CURRENT_PRICES : v.isSavedTotals() ? "<br>" + SAVED_TOTALS : "");
	}

	/** HTML tooltip of the live session values. */
	static String valuesTooltip(SessionValuation.Values v)
	{
		return "<html>" + valuesTooltipBody(v.getLootPriceMode(), v.getLootValue(), v.getLootGe(), v.getLootHa(),
			v.getSuppliesGe(), v.getSuppliesHa(), v.getLootValueUnpriced(), v.getLootUnpriced(), v.getSuppliesUnpriced())
			+ "</html>";
	}

	/**
	 * HTML tooltip for GE and high-alchemy loot and supply values with the loot at GE prices, noting the loot and
	 * supply items whose GE price was unavailable.
	 */
	static String valuesTooltip(long lootGe, long lootHa, long suppliesGe, long suppliesHa, int lootUnpriced,
		int suppliesUnpriced)
	{
		return "<html>" + valuesTooltipBody(LootPriceMode.GE, lootGe, lootGe, lootHa, suppliesGe, suppliesHa,
			lootUnpriced, lootUnpriced, suppliesUnpriced) + "</html>";
	}

	/**
	 * Exact GE and high-alchemy loot and supply values, a GE figure marked {@link #APPROXIMATE} when some of its GE
	 * prices were unavailable; the loot value in {@code mode} when that is not GE; the profit (loot in {@code mode}
	 * minus supplies at GE); and a note when some loot (priced in {@code mode}, and at GE when that is not the mode) or
	 * supply items had no price. So in High alch mode a missing GE price is still flagged.
	 *
	 * @param lootUnpriced   distinct loot items without a price in {@code mode}
	 * @param lootGeUnpriced distinct loot items without a GE price
	 */
	private static String valuesTooltipBody(LootPriceMode mode, long lootValue, long lootGe, long lootHa,
		long suppliesGe, long suppliesHa, int lootUnpriced, int lootGeUnpriced, int suppliesUnpriced)
	{
		boolean geMode = mode == null || mode == LootPriceMode.GE;
		StringBuilder sb = new StringBuilder();
		sb.append("Loot: ").append(formatGpExact(lootGe, lootGeUnpriced > 0)).append(" GE, ")
			.append(formatGpExact(lootHa)).append(" high alch");
		if (!geMode)
		{
			sb.append("<br>Loot value (").append(mode).append("): ").append(formatGpExact(lootValue));
		}
		sb.append("<br>Supplies: ").append(formatGpExact(suppliesGe, suppliesUnpriced > 0)).append(" GE, ")
			.append(formatGpExact(suppliesHa)).append(" high alch");
		sb.append("<br>Profit: ").append(formatGpExact(profit(lootValue, suppliesGe)));
		if (mode == null || mode == LootPriceMode.GE)
		{
			sb.append(" GE");
		}
		else
		{
			sb.append(" (loot at ").append(mode.toString().toLowerCase(Locale.ROOT)).append(", supplies at GE)");
		}
		if (!geMode && lootGeUnpriced > 0)
		{
			sb.append("<br>").append(unpricedNote(mode, lootUnpriced, lootGeUnpriced, suppliesUnpriced));
		}
		else if (lootUnpriced > 0 || suppliesUnpriced > 0)
		{
			sb.append("<br>").append(unpricedNote(lootUnpriced, suppliesUnpriced));
		}
		return sb.toString();
	}

	/** {@link #formatGpExact(long)}, prefixed with {@link #APPROXIMATE} when {@code approximate}. */
	private static String formatGpExact(long value, boolean approximate)
	{
		return (approximate ? APPROXIMATE : "") + formatGpExact(value);
	}

	/**
	 * Explains a {@link #APPROXIMATE} total. Loot and supplies are counted separately (not added up), because one item
	 * can be both loot and a supply and would otherwise be counted twice.
	 */
	static String unpricedNote(int lootUnpriced, int suppliesUnpriced)
	{
		return String.format("~ Some prices were unavailable (loot: %d, supplies: %d), so totals may be too low.",
			lootUnpriced, suppliesUnpriced);
	}

	/**
	 * {@link #unpricedNote(int, int)} in a loot price mode other than GE when some loot GE prices were unavailable:
	 * the loot is counted both in {@code mode} and at GE, for example
	 * {@code (loot at high alch: 0, loot at GE: 1, supplies: 0)}.
	 */
	static String unpricedNote(LootPriceMode mode, int lootUnpriced, int lootGeUnpriced, int suppliesUnpriced)
	{
		return String.format("~ Some prices were unavailable (loot at %s: %d, loot at GE: %d, supplies: %d), so the"
			+ " figures marked ~ may be too low.", mode.toString().toLowerCase(Locale.ROOT), lootUnpriced, lootGeUnpriced,
			suppliesUnpriced);
	}
}
