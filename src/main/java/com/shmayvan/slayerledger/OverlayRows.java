package com.shmayvan.slayerledger;

import java.awt.*;
import java.util.*;
import java.util.List;
import lombok.*;

/**
 * The task overlay's rows (DESIGN_PRINCIPLES.md section 2, V2 plan 7), in their fixed order:
 * <ol>
 * <li>the state line, whenever time isn't running (no setting turns it off): {@code Away} / {@code Back on your next
 * kill}, {@code Paused} / {@code Resume in sidebar}, {@code Ended} / {@code See sidebar}; on a Jad or Zuk task
 * ({@link #stateRow}) {@code Away} / {@code Back when you go in} after leaving the Fight Caves or the Inferno, and
 * {@code Waiting} / {@code Go in to start} or {@code Go in to resume};</li>
 * <li>the identity: the task's icon beside its name and a line with the spot's short name and the setup tag sprites
 * (Progress and identity);</li>
 * <li>the bar, labelled {@code 73 / 201} (Progress and identity; only with the task's size known);</li>
 * <li>{@code 128 left} / {@code ~38 min}, or with the kills left unknown {@code 73 killed} / {@code ? left} (Progress
 * and identity);</li>
 * <li>{@code 0:18/kill} / {@code 200/h} (Pace; once the pace is known);</li>
 * <li>{@code 21:54 elapsed} (Pace) / {@code 1 superior} (Haul);</li>
 * <li>{@code +212K} / {@code 58.6K XP} (Haul; once the live values are loaded);</li>
 * <li>the supply runway, {@code Prayer: ~40 kills} or critical {@code Prayer: 6 left} (Supply runway), or else the
 * superior moment line for about 10 s (Haul): at most one of the two, the runway first.</li>
 * </ol>
 * A line that pairs two groups shows only the enabled one's value, in its place. Rows 4 to 8 need a session; without
 * one an assigned task shows only its state, identity and bar.
 *
 * <p>Pure: no client, so every row is unit-tested. The plugin builds a {@link Snapshot} once per tick (the identity, the
 * superiors, XP, the runway and the moment) and the overlay joins it with the live task every frame ({@link Data}); the
 * rows are text, worked out with {@link LedgerFormat}'s number rules, the same as the sidebar's. {@link #fitted} cuts
 * the text to the overlay's width with the live {@link FontMetrics}, so nothing wraps.</p>
 */
final class OverlayRows
{
	/** Shown instead of the kills left while they are not known (see {@link SessionTracker#liveTask()}). */
	static final String UNKNOWN_REMAINING = LedgerFormat.UNKNOWN;
	/** The state line's hint on a Jad or Zuk task while Away, until the player goes in again. */
	static final String CAVE_HINT = "Back when you go in";
	/**
	 * The state line's label on a Jad or Zuk task while it waits for its first or next kill: short, so that the hint
	 * beside it fits 152 px (DESIGN_PRINCIPLES.md 2.2).
	 */
	static final String CAVE_WAITING = "Waiting";
	/** The hint beside {@link #CAVE_WAITING} while a Jad or Zuk task waits for its first kill: going in starts it. */
	static final String CAVE_START_HINT = "Go in to start";
	/** The hint beside {@link #CAVE_WAITING} while a Jad or Zuk task waits for its next kill: going in restarts it. */
	static final String CAVE_RESUME_HINT = "Go in to resume";
	/** Marks text cut short to fit: names, the spot, the state hint, the runway and the moment, never a value. */
	static final String ELLIPSIS = "…";
	/** The words the runway drops first when its line doesn't fit. */
	static final String KILLS_WORD = " kills";
	/** The unit word the elapsed value drops when its line doesn't fit ({@link #unitWord}). */
	static final String ELAPSED_WORD = " elapsed";
	/** The unit word the kills left drop when their line doesn't fit. */
	static final String LEFT_WORD = " left";
	/** The unit word the kills done (with the kills left unknown) drop when their line doesn't fit. */
	static final String KILLED_WORD = " killed";
	/** The unit word the time left drops when its line doesn't fit: {@code ~5 h 12}. */
	static final String MIN_WORD = " min";
	/** The unit word the pace drops when its line doesn't fit: {@code 0:18}. */
	static final String PER_KILL_WORD = "/kill";
	/** A kills count, value or size not known. */
	static final int UNKNOWN = -1;

	/** The task icon: an item sprite, 36 by 32. */
	static final int ICON_WIDTH = 36;
	static final int ICON_HEIGHT = 32;
	/** Between the task icon and its two lines. */
	static final int ICON_GAP = 4;
	/** The runway's supply icon: the item sprite at half size. */
	static final int SMALL_ICON_WIDTH = 18;
	static final int SMALL_ICON_HEIGHT = 16;
	/** A setup tag sprite's box on the identity's second line. */
	static final int TAG_SIZE = 16;
	/** Between two tag sprites. */
	static final int TAG_GAP = 2;
	/** Between text and the icons beside it. */
	static final int TEXT_ICON_GAP = 3;

	private OverlayRows()
	{
	}

	/** Which row a {@link Row} is, top to bottom. */
	enum Kind
	{
		STATE,
		IDENTITY,
		BAR,
		PROGRESS,
		PACE,
		ELAPSED,
		HAUL,
		RUNWAY,
		MOMENT
	}

	/** What colour a text is drawn in: the overlay maps each to a {@link LedgerTheme} token. */
	enum Tone
	{
		/** White ({@link LedgerTheme#VALUE}). */
		VALUE,
		/** The game's yellow ({@link LedgerTheme#ALERT}): states, the runway and a loss. */
		ALERT,
		/** The game's orange ({@link LedgerTheme#HEADING}): the task's name. */
		NAME,
		/** The light green ({@link LedgerTheme#GOOD}): a profit. */
		GOOD
	}

	/** The overlay's four row groups (V2 plan 2.1, 7), each with its own setting. Immutable. */
	@Value
	static class Groups
	{
		/** Every group on: the default. */
		static final Groups ALL = new Groups(true, true, true, true);
		/** Every group off: only the state line is left. */
		static final Groups NONE = new Groups(false, false, false, false);

		/** The identity, the bar, and the kills left and time left. */
		boolean progress;
		/** The pace and kills per hour, and the elapsed time. */
		boolean pace;
		/** The superiors, profit, XP and the superior moment line. */
		boolean haul;
		/** The supply runway. */
		boolean runway;
	}

	/**
	 * What the plugin works out for the overlay once per settled tick ({@link OverlayFeed}), so that drawing a frame
	 * computes nothing: the identity, the session's superiors and XP, the supply runway and the superior moment. For an
	 * assignment with no session ({@link #getSessionId} {@code null}) only the identity, its size and kills left.
	 * Immutable.
	 */
	@Value
	@Builder
	static class Snapshot
	{
		/** The session it belongs to, or {@code null} for the assignment without a session. */
		String sessionId;
		/** The task's name as the sidebar shows it ({@link #taskName}), or {@code null} while unknown. */
		String taskName;
		/** The task icon's item ({@link TaskIcons}), or {@code null} for none. */
		Integer iconItemId;
		/** The spot's short name ({@link SlayerSpots#shortName}), or {@code null} while unknown. */
		String spotName;
		/** The setup tag sprites ({@link SetupTagIcons}). */
		@Builder.Default
		List<SetupTagIcons.Icon> tags = Collections.emptyList();
		/** The task's assigned size, or {@link #UNKNOWN}. */
		@Builder.Default
		int assigned = UNKNOWN;
		/** The kills left, for the assignment without a session (a session's are read live), or {@link #UNKNOWN}. */
		@Builder.Default
		int remaining = UNKNOWN;
		/** The session's superiors so far. */
		int superiors;
		/** The session's XP so far, all skills. */
		long xp;
		/** The supply runway to show, or {@code null}. */
		SupplyRunway.Warning runway;
		/** The superior moment line while it shows, or {@code null}. */
		String moment;
		/** Whether the task is a Jad or Zuk task ({@link TaskCaves#isCaveTask}). */
		boolean caveTask;
	}

	/**
	 * What one frame shows: the live task (status, kills, active time and kills left, read every frame so the clock and
	 * a Pause are current), with the tick's {@link Snapshot}, the live values and the historical time per kill, each
	 * only when it belongs to the same session (the plugin checks); or, with no session, the state of an assignment
	 * waiting for its first kill or Ended ({@link #stateOnly}).
	 */
	@Getter
	static final class Data
	{
		/** What tracking is doing ({@link SessionTracker#getStatus()}); never {@code null}. */
		private final SessionTracker.Status status;
		/** The active session's task, or {@code null} for {@link #stateOnly}. */
		private final SessionTracker.LiveTask task;
		/** The tick's snapshot, or {@code null}. */
		private final Snapshot snapshot;
		/** The session's live values, or {@code null}. */
		private final SessionValuation.Values values;
		/** The historical time per kill for the time left, or {@code null} when there is none. */
		private final Long historicalMillisPerKill;

		/**
		 * @param task                    the active session's task (not {@code null})
		 * @param snapshot                the same session's tick snapshot, or {@code null} before its first
		 * @param values                  the same session's live values, or {@code null} when none are known yet
		 * @param historicalMillisPerKill the same session's task's historical time per kill, as the sidebar uses it
		 *                                ({@link HistoricalPace}), or {@code null} when it has no history
		 */
		Data(SessionTracker.LiveTask task, Snapshot snapshot, SessionValuation.Values values,
			Long historicalMillisPerKill)
		{
			this.status = task.getStatus();
			this.task = task;
			this.snapshot = snapshot;
			this.values = values;
			this.historicalMillisPerKill = historicalMillisPerKill;
		}

		private Data(SessionTracker.Status status, Snapshot snapshot)
		{
			this.status = status;
			this.task = null;
			this.snapshot = snapshot;
			this.values = null;
			this.historicalMillisPerKill = null;
		}

		/**
		 * The state line, with the assignment's identity and bar from {@code snapshot} (or {@code null} before the first
		 * tick's), for an assignment with no session: waiting for its first kill, or Ended
		 * ({@link SessionTracker#getStatus()}).
		 */
		static Data stateOnly(SessionTracker.Status status, Snapshot snapshot)
		{
			return new Data(status, snapshot);
		}

		/**
		 * The task's name: the snapshot's (with its variant and cannon, {@link #taskName}), else the session's task
		 * name alone; {@code null} when neither is known.
		 */
		String getTaskName()
		{
			if (snapshot != null && snapshot.getTaskName() != null)
			{
				return snapshot.getTaskName();
			}
			return task == null || task.getMonster() == null ? null
				: LedgerRecord.displayName(task.getMonster(), task.getCreatureId());
		}

		/** The task icon's item: the snapshot's, else the session task's ({@link TaskIcons}); {@code null} for none. */
		Integer getIconItemId()
		{
			if (snapshot != null)
			{
				return snapshot.getIconItemId();
			}
			return task == null ? null : TaskIcons.itemId(task.getMonster());
		}

		/** The kills left: the live task's, else the assignment's from the snapshot; {@link #UNKNOWN} when not known. */
		int getRemaining()
		{
			if (task != null)
			{
				return task.getRemaining() > 0 ? task.getRemaining() : UNKNOWN;
			}
			return snapshot == null || snapshot.getRemaining() <= 0 ? UNKNOWN : snapshot.getRemaining();
		}
	}

	/**
	 * The history's figures the overlay uses for a session's task (and location) until the session's own take over: the
	 * time per kill ({@link LedgerFormat#timeLeft}) and the supplies per kill ({@link SupplyRunway}).
	 * {@link LedgerHistoryModel} works them out from the history on the EDT and publishes them; the plugin reads them on
	 * the client thread and uses them only for the same session. Immutable.
	 */
	@Getter
	@EqualsAndHashCode
	static final class HistoricalPace
	{
		private final String sessionId;
		private final long millisPerKill;
		/** Each supply's use per kill in the history, by item id; empty for none. */
		private final Map<Integer, Double> suppliesPerKill;

		/**
		 * @param suppliesPerKill each supply's use per kill (item id to doses, bites or items:
		 *                        {@link TaskStatistics#getSuppliesPerKill}), or {@code null} for none
		 */
		HistoricalPace(String sessionId, long millisPerKill, Map<Integer, Double> suppliesPerKill)
		{
			this.sessionId = sessionId;
			this.millisPerKill = millisPerKill;
			this.suppliesPerKill = suppliesPerKill == null || suppliesPerKill.isEmpty() ? Collections.emptyMap()
				: Collections.unmodifiableMap(new TreeMap<>(suppliesPerKill));
		}

		/** Whether this belongs to {@code task}'s session. */
		boolean belongsTo(SessionTracker.LiveTask task)
		{
			return task != null && sessionId != null && sessionId.equals(task.getSessionId());
		}

		/** The time per kill when this pace belongs to {@code task}'s session, else {@code null}. */
		Long millisPerKillFor(SessionTracker.LiveTask task)
		{
			return belongsTo(task) ? millisPerKill : null;
		}
	}

	/**
	 * One overlay row: text on the left and right, their tones, and per kind the task icon, the identity's second line
	 * and tags, the bar's numbers or the runway. Immutable.
	 */
	static final class Row
	{
		@Getter
		private final Kind kind;
		/** The left text: the state, the task name, the bar label, a value or the runway's and moment's line. */
		@Getter
		private final String left;
		/** The right text (empty for none): the state's hint or a value. */
		@Getter
		private final String right;
		@Getter
		private final Tone leftTone;
		@Getter
		private final Tone rightTone;
		/**
		 * Whether the left text is the key number ({@code 128 left}), Bold while the overlay font is the default
		 * Plain.
		 */
		@Getter
		private final boolean key;
		/** The identity's task icon or the runway's supply icon, or {@code null}. */
		@Getter
		private final Integer iconItemId;
		private final String second;
		/** The identity's setup tag sprites. */
		@Getter
		private final List<SetupTagIcons.Icon> tags;
		/** The bar's kills done. */
		@Getter
		private final int done;
		/** The bar's task size. */
		@Getter
		private final int total;
		/** The runway this row shows, or {@code null}. */
		@Getter
		private final SupplyRunway.Warning runway;

		private Row(Kind kind, String left, String right, Tone leftTone, Tone rightTone, boolean key,
			Details details)
		{
			this.kind = kind;
			this.left = left == null ? "" : left;
			this.right = right == null ? "" : right;
			this.leftTone = leftTone;
			this.rightTone = rightTone;
			this.key = key;
			this.iconItemId = details.iconItemId;
			this.second = details.second;
			this.tags = details.tags;
			this.done = details.done;
			this.total = details.total;
			this.runway = details.runway;
		}

		/** A row's kind-specific parts, so the constructor stays short. */
		private static final class Details
		{
			static final Details NONE = new Details(null, null, Collections.emptyList(), 0, 0, null);

			final Integer iconItemId;
			final String second;
			final List<SetupTagIcons.Icon> tags;
			final int done;
			final int total;
			final SupplyRunway.Warning runway;

			Details(Integer iconItemId, String second, List<SetupTagIcons.Icon> tags, int done, int total,
				SupplyRunway.Warning runway)
			{
				this.iconItemId = iconItemId;
				this.second = second;
				this.tags = tags == null ? Collections.emptyList() : tags;
				this.done = done;
				this.total = total;
				this.runway = runway;
			}
		}

		static Row line(Kind kind, String left, String right)
		{
			return new Row(kind, left, right, Tone.VALUE, Tone.VALUE, false, Details.NONE);
		}

		/** The text, cut to fit by {@link #fitted}. */
		private Row withText(String newLeft, String newRight, String newSecond)
		{
			return new Row(kind, newLeft, newRight, leftTone, rightTone, key,
				new Details(iconItemId, newSecond, tags, done, total, runway));
		}

		private Row withTones(Tone newLeft, Tone newRight)
		{
			return new Row(kind, left, right, newLeft, newRight, key,
				new Details(iconItemId, second, tags, done, total, runway));
		}

		/** Whether the row is drawn in Bold while the overlay font is Plain: the key number, a critical runway. */
		boolean isBold()
		{
			return key || (runway != null && runway.isCritical());
		}

		/** The identity's second line: the spot's short name (may be empty). */
		String getSecond()
		{
			return second == null ? "" : second;
		}

		@Override
		public String toString()
		{
			return kind + ": " + left + (right.isEmpty() ? "" : " / " + right)
				+ (getSecond().isEmpty() ? "" : " | " + getSecond());
		}
	}

	/**
	 * The state line for {@code status}: its label and hint, in the alert colour; {@code null} while time is running (or
	 * there is no task). On a Jad or Zuk task ({@code caveTask}) the session starts or restarts when the player goes in
	 * (V2 plan 4.1), so Away says {@link #CAVE_HINT}, and the waiting states read {@link #CAVE_WAITING} with
	 * {@link #CAVE_START_HINT} (the first kill) or {@link #CAVE_RESUME_HINT} (the next).
	 */
	static Row stateRow(SessionTracker.Status status, boolean caveTask)
	{
		if (status == null || !status.hasStateLine())
		{
			return null;
		}
		String label = status.getLabel();
		String hint = status.getHint();
		if (caveTask)
		{
			switch (status)
			{
				case AWAY:
					hint = CAVE_HINT;
					break;
				case WAITING_FOR_FIRST_KILL:
					label = CAVE_WAITING;
					hint = CAVE_START_HINT;
					break;
				case WAITING_FOR_NEXT_KILL:
					label = CAVE_WAITING;
					hint = CAVE_RESUME_HINT;
					break;
				default:
					break;
			}
		}
		return Row.line(Kind.STATE, label, hint).withTones(Tone.ALERT, Tone.ALERT);
	}

	/** The identity: the task icon (or none) beside the name, then the spot's short name and the setup tag sprites. */
	static Row identityRow(String name, Integer iconItemId, String spotName, List<SetupTagIcons.Icon> tags)
	{
		return new Row(Kind.IDENTITY, name, null, Tone.NAME, Tone.VALUE, false,
			new Row.Details(iconItemId, spotName, tags, 0, 0, null));
	}

	/** The bar: {@code done} of {@code total} kills, labelled {@code 73 / 201}. */
	static Row barRow(int done, int total)
	{
		return new Row(Kind.BAR, LedgerFormat.progress(done, total), null, Tone.VALUE, Tone.VALUE, false,
			new Row.Details(null, null, null, done, total, null));
	}

	/**
	 * The key line: {@code 128 left} (the key number) / the time left ({@link LedgerFormat#timeLeft}); with the kills
	 * left unknown, the session's kills and {@code ? left}.
	 */
	static Row progressRow(SessionTracker.LiveTask task, Long historicalMillisPerKill)
	{
		final int remaining = task.getRemaining();
		if (remaining <= 0)
		{
			return Row.line(Kind.PROGRESS, LedgerFormat.kills(task.getKills()) + " killed", UNKNOWN_REMAINING + " left");
		}
		return new Row(Kind.PROGRESS, LedgerFormat.kills(remaining) + " left",
			LedgerFormat.timeLeft(task.getActiveMillis(), task.getKills(), remaining, historicalMillisPerKill),
			Tone.VALUE, Tone.VALUE, true, Row.Details.NONE);
	}

	/** The pace line, {@code 0:18/kill} / {@code 200/h}, or {@code null} until the pace is known. */
	static Row paceRow(SessionTracker.LiveTask task)
	{
		final OptionalLong perKill = TaskTimeEstimate.averageKillMillis(task.getActiveMillis(), task.getKills());
		final OptionalDouble perHour = TaskTimeEstimate.killsPerHour(task.getActiveMillis(), task.getKills());
		if (!perKill.isPresent() || !perHour.isPresent())
		{
			return null;
		}
		return Row.line(Kind.PACE, LedgerFormat.pace(perKill.getAsLong()),
			LedgerFormat.perHour(perHour.getAsDouble()) + "/h");
	}

	/** {@code 1 superior}, {@code 0 superiors}, {@code 12 superiors}. */
	static String superiorsText(int superiors)
	{
		return superiors == 1 ? "1 superior" : LedgerFormat.kills(Math.max(0, superiors)) + " superiors";
	}

	/**
	 * The live profit, as the live card shows it: loot in its price mode minus supplies, always signed ({@code +212K},
	 * {@code -38K}), with {@code ~} before the sign when a price is missing.
	 */
	static String profitText(SessionValuation.Values values)
	{
		final int unpriced = values.getLootValueUnpriced() + values.getSuppliesUnpriced();
		return LedgerFormat.signed(values.getProfit(), unpriced > 0);
	}

	/** {@code 58.6K XP}. */
	static String xpText(long xp)
	{
		return LedgerFormat.amount(Math.max(0, xp)) + " XP";
	}

	/**
	 * The runway's text: {@code Prayer: ~40 kills}, or without {@code withKillsWord} {@code Prayer: ~40}; critical
	 * {@code Prayer: 6 left}.
	 */
	static String runwayText(SupplyRunway.Warning runway, boolean withKillsWord)
	{
		final String name = runway.getKind().getShortName() + ": ";
		if (runway.isCritical())
		{
			return name + LedgerFormat.kills(runway.getKills()) + " left";
		}
		return name + LedgerFormat.APPROXIMATE + LedgerFormat.kills(runway.getKills())
			+ (withKillsWord ? KILLS_WORD : "");
	}

	/** The runway row: the supply's icon and {@link #runwayText}, in the alert colour; Bold when critical. */
	static Row runwayRow(SupplyRunway.Warning runway)
	{
		return new Row(Kind.RUNWAY, runwayText(runway, true), null, Tone.ALERT, Tone.ALERT, false,
			new Row.Details(runway.getItemId(), null, null, 0, 0, runway));
	}

	/**
	 * The superior moment line (DESIGN_PRINCIPLES.md 5.2, 5.3): {@code Superior spawned.} for the session's first, then
	 * by turns {@code Another superior (2nd).} and {@code Superior spawned (3rd).}; three variants at most.
	 *
	 * @param number which of the session's superiors it is, from 1
	 */
	static String momentText(int number)
	{
		if (number <= 1)
		{
			return "Superior spawned.";
		}
		final String nth = ordinal(number);
		return number % 2 == 0 ? "Another superior (" + nth + ")." : "Superior spawned (" + nth + ").";
	}

	/** {@code 1st}, {@code 2nd}, {@code 3rd}, {@code 4th}, {@code 11th}, {@code 12th}, {@code 21st}, {@code 112th}. */
	static String ordinal(int n)
	{
		final int lastTwo = Math.abs(n) % 100;
		final String suffix;
		if (lastTwo >= 11 && lastTwo <= 13)
		{
			suffix = "th";
		}
		else
		{
			switch (Math.abs(n) % 10)
			{
				case 1:
					suffix = "st";
					break;
				case 2:
					suffix = "nd";
					break;
				case 3:
					suffix = "rd";
					break;
				default:
					suffix = "th";
					break;
			}
		}
		return LedgerFormat.kills(n) + suffix;
	}

	/**
	 * The task's name as the sidebar's task box shows it: its display name ({@link LedgerRecord#displayName}), or its
	 * variant's (its Catacombs variant or Slayer boss) once the session's kills say so, with {@code (cannon)}
	 * while its Cannon tag is Yes and {@code + boss} for boss kills its name doesn't show
	 * ({@link TaskVariants.Decision#displayName}; without a variant, {@link TaskSelection#displayName}).
	 *
	 * @param selection the session's version and cannon use ({@link SessionTracker#getCurrentTaskSelection}), or
	 *                  {@code null} for the task itself
	 * @param variant   the session's variant and boss flag ({@link SessionTracker#getCurrentVariant}), or {@code null}
	 *                  for the selection's version alone
	 */
	static String taskName(String monster, int creatureId, TaskSelection selection, TaskVariants.Decision variant)
	{
		final String name = LedgerRecord.displayName(monster, creatureId);
		if (variant == null)
		{
			return selection == null ? name : selection.displayName(name);
		}
		return variant.displayName(name, selection != null && Boolean.TRUE.equals(selection.getCannon()));
	}

	/**
	 * The rows to show, in order, for the enabled groups; empty when {@code data} is {@code null}. The state line comes
	 * first whatever the groups, so a stopped clock is never silent. See the class comment for the rows.
	 */
	static List<Row> rows(Data data, Groups groups)
	{
		if (data == null)
		{
			return Collections.emptyList();
		}
		final Groups shown = groups == null ? Groups.ALL : groups;
		final Snapshot snapshot = data.getSnapshot();
		final SessionTracker.LiveTask task = data.getTask();
		final List<Row> rows = new ArrayList<>(10);
		final Row state = stateRow(data.getStatus(), snapshot != null && snapshot.isCaveTask());
		if (state != null)
		{
			rows.add(state);
		}
		if (shown.isProgress())
		{
			final String name = data.getTaskName();
			if (name != null)
			{
				rows.add(identityRow(name, data.getIconItemId(), snapshot == null ? null : snapshot.getSpotName(),
					snapshot == null ? null : snapshot.getTags()));
			}
			final int remaining = data.getRemaining();
			final int total = snapshot == null ? UNKNOWN : snapshot.getAssigned();
			if (total > 0 && remaining > 0 && remaining <= total)
			{
				rows.add(barRow(total - remaining, total));
			}
			if (task != null)
			{
				rows.add(progressRow(task, data.getHistoricalMillisPerKill()));
			}
		}
		if (task == null)
		{
			return rows;
		}
		if (shown.isPace())
		{
			final Row pace = paceRow(task);
			if (pace != null)
			{
				rows.add(pace);
			}
		}
		final String elapsed = shown.isPace() ? LedgerFormat.clock(task.getActiveMillis()) + ELAPSED_WORD : null;
		final String superiors = shown.isHaul() && snapshot != null ? superiorsText(snapshot.getSuperiors()) : null;
		if (elapsed != null || superiors != null)
		{
			rows.add(Row.line(Kind.ELAPSED, elapsed, superiors));
		}
		final SessionValuation.Values values = data.getValues();
		if (shown.isHaul() && values != null)
		{
			final long profit = values.getProfit();
			rows.add(Row.line(Kind.HAUL, profitText(values), snapshot == null ? null : xpText(snapshot.getXp()))
				.withTones(profit > 0 ? Tone.GOOD : profit < 0 ? Tone.ALERT : Tone.VALUE, Tone.VALUE));
		}
		// At most one of the two; a moment never hides a warning (DESIGN_PRINCIPLES.md 2.1).
		if (shown.isRunway() && snapshot != null && snapshot.getRunway() != null)
		{
			rows.add(runwayRow(snapshot.getRunway()));
		}
		else if (shown.isHaul() && snapshot != null && snapshot.getMoment() != null)
		{
			rows.add(Row.line(Kind.MOMENT, snapshot.getMoment(), null));
		}
		return rows;
	}

	// ---- Fitting to the width ----

	/**
	 * {@code row} with its text cut to fit {@code width} pixels of content (the overlay's width less its borders) in the
	 * live fonts, so that nothing wraps (DESIGN_PRINCIPLES.md 2.2):
	 * <ul>
	 * <li>the value lines (progress, pace, elapsed, haul) never cut a value ({@link #fitValues}): they drop unit words,
	 * then whole values ({@code 1,128 left} / {@code ~5 h 12}, {@code 1:03:31} / {@code 2 superiors});</li>
	 * <li>the state line keeps its label whole and cuts the hint, and cuts the label only when it doesn't fit alone
	 * (dropping the hint); the moment line is cut;</li>
	 * <li>the identity's name and spot fit beside the task icon, the spot also beside its tag sprites;</li>
	 * <li>the runway fits beside its icon, dropping {@link #KILLS_WORD} first;</li>
	 * <li>the bar's label is left as it is.</li>
	 * </ul>
	 * Text is cut with {@link #ELLIPSIS}.
	 *
	 * @param plain the metrics of the overlay's font
	 * @param bold  the metrics of the Bold font for {@link Row#isBold} text, or {@code null} when it is drawn in
	 *              {@code plain} (a non-default overlay font)
	 */
	static Row fitted(Row row, int width, FontMetrics plain, FontMetrics bold)
	{
		final FontMetrics leftMetrics = row.isBold() && bold != null ? bold : plain;
		switch (row.getKind())
		{
			case BAR:
				return row;
			case IDENTITY:
			{
				final int textWidth = width - (row.getIconItemId() != null ? ICON_WIDTH + ICON_GAP : 0);
				final int tagsWidth = tagsWidth(row.getTags().size());
				final String name = truncate(row.getLeft(), textWidth, plain);
				final String spot = truncate(row.getSecond(),
					textWidth - (tagsWidth > 0 ? tagsWidth + TEXT_ICON_GAP : 0), plain);
				return row.withText(name, "", spot);
			}
			case RUNWAY:
			{
				final int textWidth = width - SMALL_ICON_WIDTH - TEXT_ICON_GAP;
				String text = row.getLeft();
				if (leftMetrics.stringWidth(text) > textWidth && row.getRunway() != null)
				{
					text = runwayText(row.getRunway(), false);
				}
				return row.withText(truncate(text, textWidth, leftMetrics), "", null);
			}
			case PROGRESS:
			case PACE:
			case ELAPSED:
			case HAUL:
			{
				final String[] fit = fitValues(row.getKind(), row.getLeft(), row.getRight(), width, leftMetrics, plain);
				return row.withText(fit[0], fit[1], row.getSecond());
			}
			default:
			{
				final String[] fit = fit(row.getLeft(), row.getRight(), width, leftMetrics, plain);
				return row.withText(fit[0], fit[1], row.getSecond());
			}
		}
	}

	/**
	 * A value line's two values fitted to {@code width} pixels without cutting one, so no number loses a digit: the first
	 * of these that fits, with a space between:
	 * <ol>
	 * <li>both whole;</li>
	 * <li>the right value without its unit word ({@link #unitWord}: {@code 1,128 left} / {@code ~5 h 12});</li>
	 * <li>both without their unit words ({@code 1:03:31} / {@code 2 superiors});</li>
	 * <li>the left value alone, whole, then without its unit word;</li>
	 * <li>nothing.</li>
	 * </ol>
	 */
	static String[] fitValues(Kind kind, String left, String right, int width, FontMetrics leftMetrics,
		FontMetrics rightMetrics)
	{
		final String l = left == null ? "" : left;
		final String r = right == null ? "" : right;
		final String shortLeft = l.substring(0, l.length() - unitWord(kind, true, l).length());
		final String shortRight = r.substring(0, r.length() - unitWord(kind, false, r).length());
		final String[][] candidates = {{l, r}, {l, shortRight}, {shortLeft, shortRight}, {l, ""}, {shortLeft, ""}};
		for (String[] candidate : candidates)
		{
			if (fits(candidate[0], candidate[1], width, leftMetrics, rightMetrics))
			{
				return candidate;
			}
		}
		return new String[]{"", ""};
	}

	/**
	 * The unit word a value of a {@code kind} line ends with, which {@link #fitValues} drops before the whole value, or
	 * {@code ""} for none: on the left {@link #LEFT_WORD} or {@link #KILLED_WORD} (progress), {@link #PER_KILL_WORD}
	 * (pace) and {@link #ELAPSED_WORD} (elapsed); on the right {@link #MIN_WORD} (the time left). The other values (the
	 * kills per hour, superiors, profit and XP) have none: without it a bare number would say nothing.
	 */
	static String unitWord(Kind kind, boolean leftSide, String value)
	{
		final List<String> words;
		switch (kind)
		{
			case PROGRESS:
				words = leftSide ? Arrays.asList(LEFT_WORD, KILLED_WORD) : Collections.singletonList(MIN_WORD);
				break;
			case PACE:
				words = leftSide ? Collections.singletonList(PER_KILL_WORD) : Collections.emptyList();
				break;
			case ELAPSED:
				words = leftSide ? Collections.singletonList(ELAPSED_WORD) : Collections.emptyList();
				break;
			default:
				words = Collections.emptyList();
				break;
		}
		for (String word : words)
		{
			if (value != null && value.length() > word.length() && value.endsWith(word))
			{
				return word;
			}
		}
		return "";
	}

	/** Whether {@code left} and {@code right} fit {@code width} pixels whole, with a space between. */
	private static boolean fits(String left, String right, int width, FontMetrics leftMetrics,
		FontMetrics rightMetrics)
	{
		final int gap = left.isEmpty() || right.isEmpty() ? 0 : rightMetrics.stringWidth(" ");
		return leftMetrics.stringWidth(left) + gap + rightMetrics.stringWidth(right) <= width;
	}

	/** The width the identity's tag sprites take: {@link #TAG_SIZE} each, {@link #TAG_GAP} between. */
	static int tagsWidth(int tags)
	{
		return tags <= 0 ? 0 : tags * TAG_SIZE + (tags - 1) * TAG_GAP;
	}

	/**
	 * {@code left} and {@code right} cut to share {@code width} pixels with a space between: the left whole and the
	 * right cut to what is left, or, when the left doesn't fit alone, the left cut and no right.
	 */
	static String[] fit(String left, String right, int width, FontMetrics leftMetrics, FontMetrics rightMetrics)
	{
		final String l = left == null ? "" : left;
		final String r = right == null ? "" : right;
		final int leftWidth = leftMetrics.stringWidth(l);
		if (r.isEmpty())
		{
			return new String[]{truncate(l, width, leftMetrics), ""};
		}
		final int gap = l.isEmpty() ? 0 : rightMetrics.stringWidth(" ");
		if (leftWidth + gap + rightMetrics.stringWidth(r) <= width)
		{
			return new String[]{l, r};
		}
		final int room = width - leftWidth - gap;
		final String cut = room > 0 ? truncate(r, room, rightMetrics) : "";
		if (cut.isEmpty() || cut.equals(ELLIPSIS))
		{
			// No useful room for the right side: the left alone, cut if it must be.
			return new String[]{truncate(l, width, leftMetrics), ""};
		}
		return new String[]{l, cut};
	}

	/**
	 * {@code text} as it is when it fits {@code maxWidth} pixels, else its longest start that fits with {@link #ELLIPSIS}
	 * after it (trailing spaces dropped); just the ellipsis, or nothing, when even that is too wide.
	 */
	static String truncate(String text, int maxWidth, FontMetrics metrics)
	{
		if (text == null || text.isEmpty())
		{
			return "";
		}
		if (metrics.stringWidth(text) <= maxWidth)
		{
			return text;
		}
		final int ellipsis = metrics.stringWidth(ELLIPSIS);
		if (ellipsis > maxWidth)
		{
			return "";
		}
		// The longest start that fits with the ellipsis (widths grow with length, so a binary search).
		int low = 0;
		int high = text.length() - 1;
		while (low < high)
		{
			final int mid = (low + high + 1) / 2;
			if (metrics.stringWidth(text.substring(0, mid)) + ellipsis <= maxWidth)
			{
				low = mid;
			}
			else
			{
				high = mid - 1;
			}
		}
		final String start = text.substring(0, low).replaceAll("\\s+$", "");
		return start + ELLIPSIS;
	}
}
