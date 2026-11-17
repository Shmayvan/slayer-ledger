package com.shmayvan.slayerledger;

import java.math.*;
import java.util.*;
import lombok.*;
import net.runelite.client.chat.*;

/**
 * The record-fast task chat line ({@code DESIGN_PRINCIPLES.md} 5.2, 5.3): {@code Fastest Dust devils task yet: 52 min,
 * 4 min under your old best.} Pure (RuneLite's string-only {@link ChatMessageBuilder} only).
 *
 * <p>The record (decided by the project manager under the owner's standing OK, 2026-11-09):</p>
 * <ul>
 *     <li>A completed task is a record when its time per kill (active time over kills) is below the best of the
 *     account's other completed tasks of the same name as the history groups them ({@link LedgerRecord#groupName}:
 *     the same monster, Catacombs variant or Slayer boss, and cannon use; matched by
 *     {@link TaskStatistics#normalize}).</li>
 *     <li>There must be at least {@link #MIN_EARLIER_TASKS} of those other tasks to beat. A partial task, a task without
 *     kills or active time, and one without a name never count, on either side.</li>
 *     <li>A tie is no record: the time per kill must be strictly lower (compared exactly, without rounding).</li>
 *     <li>Task sizes differ, so the line compares at this task's kills: its own active time against the old best's
 *     time per kill times this task's kills. The margin is that difference, in the 5.5 completed-duration form
 *     ({@link LedgerFormat#duration}: {@code 4 min}, {@code <1 min}, {@code 1 h 2 min}). Comparing per kill is the
 *     fairest measure, since a 200-kill task always takes longer than a 120-kill one; scaling the old best to this
 *     task's kills keeps the margin in the minutes this task took.</li>
 * </ul>
 */
final class FastestTaskMessage
{
	/** The fewest other completed tasks of the same name a record must beat. */
	static final int MIN_EARLIER_TASKS = 3;
	private static final String PREFIX = "Fastest ";

	private FastestTaskMessage()
	{
	}

	/** A record found by {@link #find}: this task's active time and how far under the old best it came. Immutable. */
	@Getter
	@AllArgsConstructor(access = AccessLevel.PACKAGE)
	static final class Record
	{
		/** The task's name as the history shows it ({@link LedgerHistory#monster}). */
		private final String task;
		private final long activeMillis;
		/** The old best's time per kill times this task's kills, less this task's active time; more than 0. */
		private final long marginMillis;
	}

	/**
	 * Whether {@code record} is a record against {@code history} (see the class rules), and by how much; {@code null}
	 * when it isn't. {@code record} itself (by identity or id) is left out of {@code history}, so the history may
	 * already hold it.
	 */
	static Record find(LedgerRecord record, Collection<LedgerRecord> history)
	{
		if (!counts(record) || history == null)
		{
			return null;
		}
		final String key = TaskStatistics.normalize(record.groupName());
		int earlier = 0;
		LedgerRecord best = null;
		for (LedgerRecord other : history)
		{
			if (other == record || !counts(other) || record.getId() != null && record.getId().equals(other.getId())
				|| !key.equals(TaskStatistics.normalize(other.groupName())))
			{
				continue;
			}
			earlier++;
			if (best == null || faster(other, best))
			{
				best = other;
			}
		}
		if (earlier < MIN_EARLIER_TASKS || !faster(record, best))
		{
			return null;
		}
		final double oldTime = (double) best.getActiveMillis() * record.getKills() / best.getKills();
		// At least a millisecond: a strictly faster pace that rounds to nothing is still under the old best.
		final long margin = Math.max(1, Math.round(oldTime) - record.getActiveMillis());
		return new Record(LedgerHistory.monster(record), record.getActiveMillis(), margin);
	}

	/** {@code Fastest Dust devils task yet: 52 min, 4 min under your old best.}, or {@code null} without a record. */
	static String line(Record record)
	{
		if (record == null)
		{
			return null;
		}
		return head(record) + " " + LedgerFormat.duration(record.getActiveMillis()) + ", "
			+ LedgerFormat.duration(record.getMarginMillis()) + " under your old best.";
	}

	/** The line's head: {@code Fastest Dust devils task yet:}. */
	static String head(Record record)
	{
		return PREFIX + record.getTask() + " task yet:";
	}

	/**
	 * The line ({@link #line}) as a RuneLite chat message ({@code DESIGN_PRINCIPLES.md} 4.5): its head in the player's
	 * chat highlight colour and the rest in the normal one, built with the escaping
	 * {@link ChatMessageBuilder#append(String)}; {@code null} without a record.
	 */
	static String chatMessage(Record record)
	{
		final String line = line(record);
		if (line == null)
		{
			return null;
		}
		final String head = head(record);
		return new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append(head)
			.append(ChatColorType.NORMAL)
			.append(line.substring(head.length()))
			.build();
	}

	/** Whether a record can take part: completed, not partial, named, with kills and active time. */
	private static boolean counts(LedgerRecord record)
	{
		return record != null && record.isCompleted() && !record.isPartial() && record.getKills() > 0
			&& record.getActiveMillis() > 0 && TaskStatistics.normalize(record.groupName()) != null;
	}

	/** Whether {@code a}'s time per kill is strictly below {@code b}'s, compared exactly. */
	private static boolean faster(LedgerRecord a, LedgerRecord b)
	{
		final BigInteger left = BigInteger.valueOf(a.getActiveMillis()).multiply(BigInteger.valueOf(b.getKills()));
		final BigInteger right = BigInteger.valueOf(b.getActiveMillis()).multiply(BigInteger.valueOf(a.getKills()));
		return left.compareTo(right) < 0;
	}
}
