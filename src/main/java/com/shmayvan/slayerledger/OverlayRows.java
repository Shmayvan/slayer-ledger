package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The task overlay's rows ({@link SlayerLedgerOverlay}): a paused or not-tracking indicator, the task name, its kills
 * left (a row of its own, so the narrow overlay doesn't wrap), session kills, time left and profit.
 * Pure: the overlay passes the row settings in. Time left and profit use the sidebar's own formatting
 * ({@link LedgerHistory#formatTimeLeft(long, int, int, Long)}, {@link LedgerHistory#formatLiveProfit}), so both show
 * the same text; for time left that includes the historical estimate the history model publishes
 * ({@link HistoricalPace}).
 */
final class OverlayRows
{
	/** Shown instead of the kills left while they are not known (see {@link SessionTracker#liveTask()}). */
	static final String UNKNOWN_REMAINING = "?";
	/** Label of the indicator row shown first while the session is paused (manually or by a logout or world hop). */
	static final String PAUSED_LABEL = "Paused";
	/** Value of the paused indicator row: resuming is manual, from the sidebar. */
	static final String PAUSED_VALUE = "Resume in sidebar";
	/** Label of the warning row shown after a task kill while no session is running ({@link TrackingNudge}). */
	static final String NOT_TRACKING_LABEL = "Not tracking";
	/** Value of the not-tracking row: starting a session is manual, from the sidebar. */
	static final String NOT_TRACKING_VALUE = "Play in sidebar";

	private OverlayRows()
	{
	}

	/**
	 * What the overlay shows: the active session's task and its live values, if any are known yet; or, with no active
	 * session, only the not-tracking nudge ({@link #notTracking()}).
	 */
	static final class Data
	{
		private static final Data NOT_TRACKING = new Data(null, null);

		private final SessionTracker.LiveTask task;
		private final SessionValuation.Values values;
		private final Long historicalMillisPerKill;
		private final TaskSelection selection;

		/**
		 * @param task   the active session's task (not {@code null})
		 * @param values the same session's live values, or {@code null} when none are known yet
		 */
		Data(SessionTracker.LiveTask task, SessionValuation.Values values)
		{
			this(task, values, null);
		}

		/**
		 * @param task                    the active session's task (not {@code null})
		 * @param values                  the same session's live values, or {@code null} when none are known yet
		 * @param historicalMillisPerKill the same session's task's historical time per kill, as the sidebar uses it
		 *                                ({@link HistoricalPace}), or {@code null} when it has no history
		 */
		Data(SessionTracker.LiveTask task, SessionValuation.Values values, Long historicalMillisPerKill)
		{
			this(task, values, historicalMillisPerKill, null);
		}

		/**
		 * @param task                    the active session's task (not {@code null})
		 * @param values                  the same session's live values, or {@code null} when none are known yet
		 * @param historicalMillisPerKill the same session's task's historical time per kill, as the sidebar uses it
		 *                                ({@link HistoricalPace}), or {@code null} when it has no history
		 * @param selection               the same session's task's version and cannon use, as the sidebar's task box
		 *                                shows them ({@link SessionTracker#getCurrentTaskSelection}), or
		 *                                {@code null} for the task itself
		 */
		Data(SessionTracker.LiveTask task, SessionValuation.Values values, Long historicalMillisPerKill,
			TaskSelection selection)
		{
			this.task = task;
			this.values = values;
			this.historicalMillisPerKill = historicalMillisPerKill;
			this.selection = selection;
		}

		/** The not-tracking nudge alone: a task kill was seen while no session is running ({@link TrackingNudge}). */
		static Data notTracking()
		{
			return NOT_TRACKING;
		}

		/** The active session's task, or {@code null} for {@link #notTracking()}. */
		SessionTracker.LiveTask getTask()
		{
			return task;
		}

		/** The session's live values, or {@code null}. */
		SessionValuation.Values getValues()
		{
			return values;
		}

		/** The historical time per kill for the time-left row, or {@code null} when there is none. */
		Long getHistoricalMillisPerKill()
		{
			return historicalMillisPerKill;
		}

		/**
		 * The task's display name ({@link LedgerRecord#displayName}), or its Catacombs variant's name once the session's
		 * kills say so, with {@code (cannon)} once it fired a cannonball, as the sidebar's task box shows
		 * it ({@link TaskSelection#displayName}).
		 */
		String getTaskName()
		{
			String name = LedgerRecord.displayName(task.getMonster(), task.getCreatureId());
			return selection == null ? name : selection.displayName(name);
		}
	}

	/**
	 * The historical time per kill the sidebar uses for a session's task (and location) until the live pace takes over
	 * ({@link LedgerHistory#formatTimeLeft(long, int, int, Long)}). {@link LedgerHistoryModel} computes it from the
	 * history on the EDT and publishes it; the overlay reads it on the client thread and uses it only for the same
	 * session. Immutable.
	 */
	static final class HistoricalPace
	{
		private final String sessionId;
		private final long millisPerKill;

		HistoricalPace(String sessionId, long millisPerKill)
		{
			this.sessionId = sessionId;
			this.millisPerKill = millisPerKill;
		}

		/** The time per kill when this pace belongs to {@code task}'s session, else {@code null}. */
		Long millisPerKillFor(SessionTracker.LiveTask task)
		{
			return task != null && sessionId != null && sessionId.equals(task.getSessionId()) ? millisPerKill : null;
		}

		String getSessionId()
		{
			return sessionId;
		}

		long getMillisPerKill()
		{
			return millisPerKill;
		}

		@Override
		public boolean equals(Object other)
		{
			if (!(other instanceof HistoricalPace))
			{
				return false;
			}
			HistoricalPace that = (HistoricalPace) other;
			return millisPerKill == that.millisPerKill && Objects.equals(sessionId, that.sessionId);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(sessionId, millisPerKill);
		}
	}

	/** One overlay line: a label on the left, its value on the right, optionally drawn as a warning. */
	static final class Row
	{
		private final String label;
		private final String value;
		private final boolean warning;

		Row(String label, String value)
		{
			this(label, value, false);
		}

		Row(String label, String value, boolean warning)
		{
			this.label = label;
			this.value = value;
			this.warning = warning;
		}

		/** Whether the overlay draws this row in its warning colour. */
		boolean isWarning()
		{
			return warning;
		}

		String getLabel()
		{
			return label;
		}

		String getValue()
		{
			return value;
		}
	}

	/**
	 * The rows to show, in order, for the enabled settings; empty when {@code data} is {@code null}. While the session
	 * is paused, a warning row ({@link #PAUSED_LABEL}) comes first whatever the settings, so a paused session never
	 * looks like it is still tracking. With no active session, {@link Data#notTracking()} gives the one warning row
	 * {@link #NOT_TRACKING_LABEL}, also whatever the settings. {@code showTask} controls both the task name row and the
	 * kills-left row under it. The profit row is left out until the session has live values.
	 */
	static List<Row> rows(Data data, boolean showTask, boolean showKills, boolean showTimeLeft, boolean showProfit)
	{
		if (data == null)
		{
			return Collections.emptyList();
		}

		SessionTracker.LiveTask task = data.getTask();
		if (task == null)
		{
			return Collections.singletonList(new Row(NOT_TRACKING_LABEL, NOT_TRACKING_VALUE, true));
		}
		int remaining = task.getRemaining();
		List<Row> rows = new ArrayList<>(6);
		if (task.getState() == SessionTracker.State.PAUSED)
		{
			rows.add(new Row(PAUSED_LABEL, PAUSED_VALUE, true));
		}
		if (showTask)
		{
			rows.add(new Row("Task", data.getTaskName()));
			rows.add(new Row("Left", remaining > 0 ? Integer.toString(remaining) : UNKNOWN_REMAINING));
		}
		if (showKills)
		{
			rows.add(new Row("Kills", Integer.toString(task.getKills())));
		}
		if (showTimeLeft)
		{
			rows.add(new Row("Time left",
				LedgerHistory.formatTimeLeft(task.getActiveMillis(), task.getKills(), remaining,
					data.getHistoricalMillisPerKill())));
		}
		SessionValuation.Values values = data.getValues();
		if (showProfit && values != null)
		{
			rows.add(new Row(LedgerHistory.profitName(values.getLootPriceMode()), LedgerHistory.formatLiveProfit(values)));
		}
		return rows;
	}
}
