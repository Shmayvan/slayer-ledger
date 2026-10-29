package com.shmayvan.slayerledger;

import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The task box at the top of {@link SlayerLedgerPanel}, at most three lines:
 * <ol>
 * <li>the task and the kills left: {@code Abyssal demons · 172 left}; a task done on its Catacombs variant, once the
 * session's kills say so, shows the variant instead: {@code Deviant spectres · 120 left}; once the session has
 * fired a cannonball the name ends with {@code (cannon)}: {@code Dust devils (cannon) · 80 left};</li>
 * <li>(while any part is known) the master, the type when it isn't Regular or Konar (the master already says
 * Konar), the location (the running or paused session's, else the task's assigned area) and the next bonus
 *: {@code Konar · Kraken Cove · bonus in 3}; its tooltip has each of them in full;</li>
 * <li>(while the task advisor has advice other than Do) the advice, with its reason as the tooltip. Do adds
 * nothing to the box; its reason is in the first line's tooltip. When the advisor can say which version of the task
 * is better, standard or Catacombs variant ({@link TaskAdvisor#adviseVariant}), or with or without a cannon
 * ({@link TaskAdvisor#adviseCannon}): with advice other than Do it is in the advice's tooltip, otherwise this
 * line shows it, both in one line when there are both ({@link TaskAdvisor#betterText}):
 * {@code Better: Deviant spectres, without cannon}.</li>
 * </ol>
 * The optional lines are added or removed only when they appear or go. EDT only.
 */
final class TaskBoxView
{
	/** Between the parts of the first and second lines. */
	static final String SEPARATOR = " · ";

	private final LedgerHistoryModel model;
	private final SessionTracker tracker;
	/** The plugin's current task streak, or {@code null} while unknown. */
	private final Supplier<Integer> taskStreak;
	/** Revalidates and repaints the panel after a change. */
	private final Runnable relayout;

	/** "Abyssal demons · 172 left", "Task: None" or "Task: waiting for game data". */
	private final JLabel taskLabel = new JLabel();
	/** "Konar · Kraken Cove · bonus in 3"; in {@link #box}, after the task, only while some part is known. */
	private final JLabel detailsLabel = new JLabel();
	/**
	 * "Advice: Consider skip (n=3)" ({@link TaskAdvisor}); last in {@link #box}, only while there is a task, the
	 * "Task advisor" setting is on, there is enough history and the advice isn't Do.
	 */
	private final JLabel adviceLabel = new JLabel();
	/** The task box: the task, then (while known) the details and the advice. */
	private final JPanel box;
	/** The advice shown (the model's result), so the row is redrawn only when the model works out a new one. */
	private TaskAdvisor.Result shownAdvice;
	/** The variant advice shown, likewise. */
	private TaskAdvisor.VersionAdvice shownVariant;
	/** The cannon advice shown, likewise. */
	private TaskAdvisor.VersionAdvice shownCannon;

	/** The first line's tooltip without the advice: the task and the kills left in full, or {@code null}. */
	private String taskTooltip;
	/** The details of the current task, as of the last {@link #refreshTask}: whether there is a task, its master etc. */
	private boolean hasTask;
	private Integer master;
	private TaskType type;
	/** The task's assigned area (Konar, Wilderness), or {@code null}. */
	private String area;
	/** The running or paused session's location name, as of the last {@link #refreshLocation}, or {@code null}. */
	private String liveLocation;

	TaskBoxView(LedgerHistoryModel model, SessionTracker tracker, Supplier<Integer> taskStreak, Runnable relayout)
	{
		this.model = model;
		this.tracker = tracker;
		this.taskStreak = taskStreak;
		this.relayout = relayout;
		box = SlayerLedgerPanel.section(taskLabel);
		// Names for tests, which find the rows by them; no visible effect.
		box.setName("taskBox");
		taskLabel.setName("taskBox.task");
		detailsLabel.setName("taskBox.details");
		adviceLabel.setName("taskBox.advice");
	}

	/** Adds the task box to {@code content}. */
	void addTo(JPanel content)
	{
		detailsLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		detailsLabel.setFont(FontManager.getRunescapeSmallFont());
		adviceLabel.setFont(FontManager.getRunescapeSmallFont());
		SlayerLedgerPanel.addRow(content, box);
	}

	/** Shows the task and the kills left, then who assigned it, its type, the next bonus and the advice. */
	void refreshTask(int remaining, String monster)
	{
		hasTask = remaining > 0;
		if (!tracker.isTaskObserved())
		{
			taskLabel.setText("Task: waiting for game data");
			taskTooltip = null;
		}
		else if (hasTask)
		{
			// The Catacombs variant the running or paused session's kills say is being done, and whether it
			// fired a cannonball: the name its task is saved under.
			TaskSelection selection = tracker.getCurrentTaskSelection();
			String variant = selection.getVariant();
			boolean cannon = Boolean.TRUE.equals(selection.getCannon());
			taskLabel.setText(selection.displayName(monster) + SEPARATOR + remaining + " left");
			// A long monster name may not fit the sidebar; the tooltip has it in full.
			taskTooltip = "Task: " + LedgerHistory.escapeHtml(monster)
				+ (variant == null ? "" : "<br>Doing: " + LedgerHistory.escapeHtml(variant) + " (from your kills)")
				+ (cannon ? "<br>Cannon: cannonballs fired this session" : "")
				+ "<br>Remaining: " + remaining;
		}
		else
		{
			taskLabel.setText("Task: None");
			taskTooltip = null;
		}
		TaskContext context = hasTask ? tracker.getCurrentContext() : TaskContext.NONE;
		master = context.getMaster();
		type = hasTask ? context.type(tracker.getCurrentCreatureId()) : null;
		area = type == null ? null : TaskLocation.normalizeName(context.getLocation());
		refreshDetails();
		refreshAdvice();
	}

	/**
	 * Takes the running or paused session's location while it is known: its assigned area, else the player's name for
	 * its region with the most kills (asked of the plugin when not known yet).
	 */
	void refreshLocation(SessionTracker.State state)
	{
		String name = null;
		if (state == SessionTracker.State.RUNNING || state == SessionTracker.State.PAUSED)
		{
			Integer region = tracker.getDominantRegion();
			if (region != null)
			{
				model.requestLocationNames(Collections.singleton(region));
			}
			name = TaskLocation.liveName(tracker.getSessionTaskLocation(), region, model::locationName);
		}
		liveLocation = name;
		refreshDetails();
	}

	/**
	 * Shows the second line: the known parts of master, type (unless Regular or Konar), location and next bonus, with
	 * each in full in the tooltip; the line is in the box only while some part is known.
	 */
	private void refreshDetails()
	{
		List<String> parts = new ArrayList<>();
		List<String> tooltip = new ArrayList<>();
		if (hasTask)
		{
			String masterName = SlayerMaster.displayName(master);
			if (masterName != null)
			{
				parts.add(masterName);
			}
			if (type != null && type != TaskType.REGULAR && type != TaskType.KONAR)
			{
				parts.add(type.toString());
			}
			tooltip.add("Master: " + LedgerHistory.escapeHtml(masterName == null ? LedgerHistory.NO_VALUE : masterName));
			tooltip.add(LedgerHistory.escapeHtml(LedgerHistory.formatTaskType(type, area)));
		}
		String location = liveLocation != null ? liveLocation : area;
		if (location != null)
		{
			parts.add(location);
		}
		if (liveLocation != null)
		{
			tooltip.add(LedgerHistory.escapeHtml(TaskLocation.format(liveLocation)));
		}
		Integer streak = taskStreak.get();
		String bonus = StreakMilestone.formatShort(streak);
		if (bonus != null)
		{
			parts.add(bonus);
			tooltip.add(StreakMilestone.format(streak));
		}

		boolean show = !parts.isEmpty();
		if (show)
		{
			String text = String.join(SEPARATOR, parts);
			// A player-typed location first in the line must not turn it into HTML.
			detailsLabel.setText(text.regionMatches(true, 0, "<html", 0, 5) ? " " + text : text);
			// A long location may not fit the sidebar; the tooltip has every part in full.
			detailsLabel.setToolTipText("<html>" + String.join("<br>", tooltip) + "</html>");
		}
		boolean shown = detailsLabel.getParent() == box;
		if (shown != show)
		{
			if (show)
			{
				box.add(detailsLabel, 1);
			}
			else
			{
				box.remove(detailsLabel);
			}
			relayoutBox();
		}
	}

	/**
	 * Shows the task advisor's row for the current task, last in the box. The model works it out
	 * ({@link LedgerHistoryModel#advice}) only when the task, the records, the Loot price mode, today's prices or the
	 * settings change, and the row is redrawn only then; while views wait for today's prices the shown row stays
	 * until they arrive.
	 */
	void refreshAdvice()
	{
		if (!model.isAdviceShown())
		{
			shownAdvice = null;
			shownVariant = null;
			shownCannon = null;
			showAdvice(null, null, null);
			return;
		}
		if (model.isWaitingForCurrentPrices())
		{
			// The first line's tooltip may have changed (a new task); the advice in it stays until the prices arrive.
			showTaskTooltip(shownAdvice, shownVariant, shownCannon);
			return;
		}
		TaskAdvisor.Result result = model.advice();
		TaskAdvisor.VersionAdvice variant = model.variantAdvice();
		TaskAdvisor.VersionAdvice cannon = model.cannonAdvice();
		boolean rowShown = adviceLabel.getParent() == box;
		if (result != shownAdvice || variant != shownVariant || cannon != shownCannon
			|| hasRow(result, variant, cannon) != rowShown)
		{
			shownAdvice = result;
			shownVariant = variant;
			shownCannon = cannon;
			showAdvice(result, variant, cannon);
		}
		else
		{
			showTaskTooltip(result, variant, cannon);
		}
	}

	/** Whether the advice row is in the box: advice other than Do, or variant or cannon advice. */
	private static boolean hasRow(TaskAdvisor.Result result, TaskAdvisor.VersionAdvice variant,
		TaskAdvisor.VersionAdvice cannon)
	{
		return isWarning(result) || variant != null || cannon != null;
	}

	/** Whether {@code result} is advice other than Do (shown in orange). */
	private static boolean isWarning(TaskAdvisor.Result result)
	{
		return result != null && result.getAdvice() != TaskAdvisor.Advice.DO;
	}

	/**
	 * Shows {@code result}: as the last row of the task box when it isn't Do, in the first line's tooltip when it is,
	 * or nowhere for {@code null}. The variant and cannon advice are in that row's tooltip, or the row
	 * itself, in one line, when there is no other advice to show there: the box keeps to three lines.
	 */
	private void showAdvice(TaskAdvisor.Result result, TaskAdvisor.VersionAdvice variant,
		TaskAdvisor.VersionAdvice cannon)
	{
		boolean row = hasRow(result, variant, cannon);
		String versions = versionReasons(variant, cannon);
		if (isWarning(result))
		{
			adviceLabel.setText(result.text());
			// The panel's hint colour (as the blowpipe hint) for skip and block.
			adviceLabel.setForeground(ColorScheme.BRAND_ORANGE);
			adviceLabel.setToolTipText("<html>" + adviceReason(result)
				+ (versions == null ? "" : "<br>" + versions) + "</html>");
		}
		else if (versions != null)
		{
			String better = TaskAdvisor.betterText(variant, cannon);
			adviceLabel.setText(better);
			adviceLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			// The line in full first, as the sidebar may cut it off.
			adviceLabel.setToolTipText("<html>" + LedgerHistory.escapeHtml(better) + "<br>" + versions + "</html>");
		}
		showTaskTooltip(result, variant, cannon);
		boolean shown = adviceLabel.getParent() == box;
		if (shown != row)
		{
			if (row)
			{
				box.add(adviceLabel);
			}
			else
			{
				box.remove(adviceLabel);
			}
			relayoutBox();
		}
	}

	/**
	 * Sets the first line's tooltip: the task in full and, when {@code advice} is Do, that advice and its reason, and
	 * then the variant and cannon advice, if any.
	 */
	private void showTaskTooltip(TaskAdvisor.Result advice, TaskAdvisor.VersionAdvice variant,
		TaskAdvisor.VersionAdvice cannon)
	{
		String tooltip = taskTooltip;
		if (tooltip != null && advice != null && advice.getAdvice() == TaskAdvisor.Advice.DO)
		{
			tooltip += "<br>" + advice.text() + "<br>" + adviceReason(advice);
		}
		String versions = versionReasons(variant, cannon);
		if (tooltip != null && versions != null)
		{
			tooltip += "<br>" + LedgerHistory.escapeHtml(TaskAdvisor.betterText(variant, cannon)) + "<br>" + versions;
		}
		taskLabel.setToolTipText(tooltip == null ? null : "<html>" + tooltip + "</html>");
	}

	/**
	 * The variant and cannon advice's reasons and what they count, as HTML without the {@code <html>}
	 * tags, or {@code null} when there is neither.
	 */
	private String versionReasons(TaskAdvisor.VersionAdvice variant, TaskAdvisor.VersionAdvice cannon)
	{
		if (variant == null && cannon == null)
		{
			return null;
		}
		StringBuilder reasons = new StringBuilder();
		if (variant != null)
		{
			reasons.append(LedgerHistory.escapeHtml(variant.reason(model.lootPriceMode())))
				.append("<br>Standard and Catacombs variant tasks compared, with and without a cannon.");
		}
		if (cannon != null)
		{
			reasons.append(reasons.length() == 0 ? "" : "<br>")
				.append(LedgerHistory.escapeHtml(cannon.reason(model.lootPriceMode())))
				.append("<br>Tasks with a cannon (cannonballs fired) and without one compared, both versions.");
		}
		return reasons.append("<br>Each with ").append(TaskAdvisor.MIN_TASKS).append("+ tasks and ")
			.append(TaskAdvisor.MIN_ACTIVE_MILLIS / 60_000L).append("+ min, rates as in the Trends list. Advice only.")
			.toString();
	}

	/**
	 * The advice's reason and what it counts, as HTML without the {@code <html>} tags. The main advice pools each
	 * task's Catacombs variant and cannon tasks with its others, while the Trends list splits them, so it
	 * says so.
	 */
	private String adviceReason(TaskAdvisor.Result result)
	{
		return LedgerHistory.escapeHtml(result.reason(model.lootPriceMode()))
			+ "<br>From " + result.getTasks() + " completed tasks of this monster (n), with rates as in the Trends"
			+ " list, Catacombs variant and cannon tasks pooled with their task. Advice only."
			+ "<br>Counts monsters with " + TaskAdvisor.MIN_TASKS + "+ tasks and "
			+ TaskAdvisor.MIN_ACTIVE_MILLIS / 60_000L + "+ min; Consider block needs n of "
			+ TaskAdvisor.MIN_BLOCK_TASKS + "+. " + (result.isBoss() ? "Bosses rank only against bosses."
			: "Boss tasks are ranked separately.");
	}

	private void relayoutBox()
	{
		((GridLayout) box.getLayout()).setRows(box.getComponentCount());
		relayout.run();
	}
}
