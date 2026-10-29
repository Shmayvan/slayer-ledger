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
import java.util.Objects;

/**
 * Pure rules and formatting for the assignment entries ({@link AssignmentEntry}): the bound on how many are kept, the
 * check against saved records, the order and the "Skipped / blocked" section's text. No Swing, client or file access.
 *
 * <p>Entries are never mixed with {@link LedgerRecord}s: nothing here, or anywhere else, counts them as played tasks
 * (see {@link AssignmentEntry}).</p>
 */
final class AssignmentLog
{
	/** At most this many entries are kept; adding one more drops the oldest (by end time). */
	static final int MAX_ENTRIES = 500;
	/** Separator between the parts of a row, as in {@code Abyssal demons \u00b7 Skipped \u00b7 10/26/26}. */
	static final String SEPARATOR = " \u00b7 ";

	/** Newest first by end time; ties broken by id, for a stable order. */
	static final Comparator<AssignmentEntry> NEWEST_FIRST = Comparator
		.comparingLong(AssignmentEntry::getEndedAt).reversed()
		.thenComparing(e -> e.getId() == null ? "" : e.getId());

	private AssignmentLog()
	{
	}

	/**
	 * {@code entries} with {@code entry} added (unless one with the same id is already there), keeping only the newest
	 * {@link #MAX_ENTRIES}; the order on disk is oldest first.
	 */
	static List<AssignmentEntry> add(Collection<AssignmentEntry> entries, AssignmentEntry entry)
	{
		final List<AssignmentEntry> result = sortNewestFirst(entries);
		if (entry != null && (entry.getId() == null
			|| result.stream().noneMatch(e -> entry.getId().equals(e.getId()))))
		{
			result.add(entry);
			result.sort(NEWEST_FIRST);
		}
		while (result.size() > MAX_ENTRIES)
		{
			result.remove(result.size() - 1);
		}
		final List<AssignmentEntry> oldestFirst = new ArrayList<>(result.size());
		for (int i = result.size() - 1; i >= 0; i--)
		{
			oldestFirst.add(result.get(i));
		}
		return oldestFirst;
	}

	/** Copy without nulls, newest first. */
	static List<AssignmentEntry> sortNewestFirst(Collection<AssignmentEntry> entries)
	{
		final List<AssignmentEntry> sorted = new ArrayList<>();
		if (entries != null)
		{
			for (AssignmentEntry e : entries)
			{
				if (e != null)
				{
					sorted.add(e);
				}
			}
		}
		sorted.sort(NEWEST_FIRST);
		return sorted;
	}

	/**
	 * Whether the saved records suggest that an assignment the plugin didn't watch from its start
	 * ({@link AssignmentWatch.Ended#isCheckRecords()}) was played in an earlier run: the newest record is a partial
	 * (not completed) session of the same creature key with the same assigned size (when both are known). A partial
	 * session doesn't end its assignment, so the assignment it belonged to can still be the one that just ended. Errs
	 * towards "played": a missing entry is better than one for a task that was played.
	 */
	static boolean playedBefore(Collection<LedgerRecord> records, AssignmentEntry entry)
	{
		if (records == null || entry == null || entry.getCreatureId() == null)
		{
			return false;
		}
		final List<LedgerRecord> sorted = LedgerHistory.sortNewestFirst(records);
		if (sorted.isEmpty())
		{
			return false;
		}
		final LedgerRecord newest = sorted.get(0);
		return !newest.isCompleted() && Objects.equals(newest.getCreatureId(), entry.getCreatureId())
			&& (newest.getOriginalAmount() == null || entry.getOriginalAmount() == null
			|| newest.getOriginalAmount().equals(entry.getOriginalAmount()));
	}

	// ---- Display ----

	/** Date of a row, in the locale's short style. */
	static DateTimeFormatter shortDate(ZoneId zone)
	{
		return DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withZone(zone);
	}

	/** Task name, or {@link LedgerHistory#UNKNOWN_MONSTER}. */
	static String monster(AssignmentEntry entry)
	{
		final String monster = entry.getMonster();
		return monster == null || monster.isEmpty() ? LedgerHistory.UNKNOWN_MONSTER : monster;
	}

	/** {@code Skipped \u00b7 10/26/26}: the outcome and the end date (without a date when there is none). */
	static String status(AssignmentEntry entry, DateTimeFormatter date)
	{
		final String label = AssignmentOutcome.orUnknown(entry.getOutcome()).getLabel();
		return entry.getEndedAt() > 0 ? label + SEPARATOR + date.format(Instant.ofEpochMilli(entry.getEndedAt())) : label;
	}

	/** {@code Abyssal demons \u00b7 Skipped \u00b7 10/26/26}. */
	static String rowText(AssignmentEntry entry, DateTimeFormatter date)
	{
		return monster(entry) + SEPARATOR + status(entry, date);
	}

	/**
	 * The row's HTML tooltip: the task, the outcome, the end time, then the master, type, assigned amount and point
	 * change, each only when known.
	 */
	static String tooltip(AssignmentEntry entry, DateTimeFormatter dateTime)
	{
		final StringBuilder sb = new StringBuilder("<html>");
		sb.append(LedgerHistory.escapeHtml(monster(entry)));
		sb.append("<br>").append(AssignmentOutcome.orUnknown(entry.getOutcome()).getDescription());
		if (entry.getEndedAt() > 0)
		{
			sb.append("<br>Ended: ").append(dateTime.format(Instant.ofEpochMilli(entry.getEndedAt())));
		}
		if (SlayerMaster.isKnown(entry.getSlayerMaster()))
		{
			sb.append("<br>").append(LedgerHistory.escapeHtml(LedgerHistory.formatMaster(entry.getSlayerMaster())));
		}
		final TaskType type = TaskType.of(entry.getSlayerMaster(), entry.getCreatureId());
		if (type != null)
		{
			sb.append("<br>").append(LedgerHistory.escapeHtml(LedgerHistory.formatTaskType(type,
				entry.getTaskLocation())));
		}
		if (entry.getOriginalAmount() != null && entry.getOriginalAmount() > 0)
		{
			sb.append("<br>Assigned: ").append(entry.getOriginalAmount());
		}
		if (entry.getPointsChange() != null)
		{
			sb.append("<br>Points: ").append(formatPointsChange(entry.getPointsChange()));
		}
		return sb.append("</html>").toString();
	}

	/** A signed point change: {@code -30}, {@code +18}, {@code 0}. */
	static String formatPointsChange(int change)
	{
		return change > 0 ? "+" + change : Integer.toString(change);
	}

	/** Text of the delete confirmation dialog. */
	static String deleteConfirmation(AssignmentEntry entry)
	{
		return String.format("Delete this %s entry (%s)? This can't be undone.", monster(entry),
			AssignmentOutcome.orUnknown(entry.getOutcome()).getLabel().toLowerCase(Locale.ROOT));
	}
}
