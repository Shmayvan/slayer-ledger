package com.shmayvan.slayerledger;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * The header of the live task card (DESIGN_PRINCIPLES.md 3.4): the task's icon (36 by 32) beside its lines.
 * <ol>
 * <li>The task's name in Bold, in the heading colour: {@code Abyssal demons}; a task done on its Catacombs variant or
 * Slayer boss, once the session's kills say so, shows the variant instead: {@code Deviant spectres},
 * {@code Grotesque Guardians}; while the session's Cannon tag is Yes the name ends with {@code (cannon)};
 * boss kills the name doesn't show add {@code + boss}: {@code Cave kraken + boss}. Without a task:
 * {@code No Slayer task.}</li>
 * <li>(while known) the location, in Small: the active session's Slayer spot, else a Konar or Wilderness task's
 * assigned area;</li>
 * <li>(while any part is known) the master, the type when it isn't Regular or Konar (the master already says Konar),
 * the kills left while no session is active (the stats grid shows them while one is: before the first kill, after
 * End task) and the next bonus, in Small: {@code Konar · 201 left · bonus in 3}; its tooltip has each part,
 * the location too, in full.</li>
 * </ol>
 * The task advisor's skip / block badge is on the advisor card ({@link AdvisorCardSection}).
 * The lines are cut short with {@code …} when they don't fit the card ({@link FitLabel}); the optional ones are added
 * or removed only when they appear or go, and the icon is asked for only when the task changes. EDT only.
 */
final class TaskBoxView
{
	/** Between the parts of the master line. */
	static final String SEPARATOR = " · ";
	/** The name line without a task (DESIGN_PRINCIPLES.md 3.9). */
	static final String NO_TASK = "No Slayer task.";
	/** The name line before the game has told the plugin the task. */
	static final String WAITING_FOR_GAME = "Waiting for game data";
	/** The task icon's size: an item sprite. */
	static final int ICON_WIDTH = 36;
	static final int ICON_HEIGHT = 32;

	private final LedgerHistoryModel model;
	private final SessionTracker tracker;
	/** The plugin's current task streak, or {@code null} while unknown. */
	private final Supplier<Integer> taskStreak;
	/** Revalidates and repaints the panel after a change. */
	private final Runnable relayout;
	/** Draws the task's icon. */
	private final ItemIcons icons;

	/** The task's icon, 36 by 32; shown while the task has one. */
	private final JLabel iconLabel = new JLabel();
	/** "Abyssal demons", "No Slayer task." or "Waiting for game data". */
	private final FitLabel taskLabel = new FitLabel();
	/** "Catacombs of Kourend"; in {@link #lines} only while known. */
	private final FitLabel locationLabel = new FitLabel();
	/** "Konar · bonus in 3"; in {@link #lines}, after the location, only while some part is known. */
	private final FitLabel detailsLabel = new FitLabel();
	/** The header: the icon, then {@link #lines}. */
	private final JPanel box = new JPanel(new BorderLayout(6, 0));
	/** The name, then (while known) the location and the details. */
	private final JPanel lines = new JPanel();
	/** The details of the current task, as of the last {@link #refreshTask}: whether there is a task, its master etc. */
	private boolean hasTask;
	/** The kills left on the task. */
	private int remaining;
	/** Whether a session is active, so the stats grid shows the kills left and the master line doesn't. */
	private boolean sessionActive;
	private Integer master;
	private TaskType type;
	/** The task's assigned area (Konar, Wilderness), or {@code null}. */
	private String area;
	/** The active session's location name, as of the last {@link #refreshLocation}, or {@code null}. */
	private String liveLocation;
	/** The task whose icon is shown ({@code null}: none), so it is asked for only when the task changes. */
	private String iconTask;
	/** How many times the icon was asked for ({@link #getBuildCount}). */
	private int builds;

	TaskBoxView(LedgerHistoryModel model, SessionTracker tracker, Supplier<Integer> taskStreak, Runnable relayout,
		ItemIcons icons)
	{
		this.model = model;
		this.tracker = tracker;
		this.taskStreak = taskStreak;
		this.relayout = relayout;
		this.icons = icons;
		// Names for tests, which find the rows by them; no visible effect.
		box.setName("taskBox");
		iconLabel.setName("taskBox.icon");
		lines.setName("taskBox.lines");
		taskLabel.setName("taskBox.task");
		locationLabel.setName("taskBox.location");
		detailsLabel.setName("taskBox.details");

		box.setOpaque(false);
		box.setAlignmentX(Component.LEFT_ALIGNMENT);
		Dimension iconSize = new Dimension(ICON_WIDTH, ICON_HEIGHT);
		iconLabel.setPreferredSize(iconSize);
		iconLabel.setMinimumSize(iconSize);
		iconLabel.setMaximumSize(iconSize);
		iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
		iconLabel.setVerticalAlignment(SwingConstants.CENTER);
		iconLabel.setVisible(false);
		box.add(iconLabel, BorderLayout.WEST);

		lines.setOpaque(false);
		lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
		taskLabel.setFont(LedgerTheme.boldFont());
		taskLabel.setForeground(LedgerTheme.HEADING);
		locationLabel.setFont(LedgerTheme.smallFont());
		locationLabel.setForeground(LedgerTheme.LABEL);
		detailsLabel.setFont(LedgerTheme.smallFont());
		detailsLabel.setForeground(LedgerTheme.HINT);
		for (FitLabel label : new FitLabel[]{taskLabel, locationLabel, detailsLabel})
		{
			label.setAlignmentX(Component.LEFT_ALIGNMENT);
		}
		lines.add(taskLabel);
		// The lines are centred on the icon's height, and take the width beside it.
		JPanel centred = new JPanel(new GridBagLayout());
		centred.setOpaque(false);
		GridBagConstraints c = new GridBagConstraints();
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		centred.add(lines, c);
		box.add(centred, BorderLayout.CENTER);
	}

	/** The header, for the live task card to add first. */
	JPanel getComponent()
	{
		return box;
	}

	/** Shows the task's name, icon and tooltip, then who assigned it, its type and the next bonus. */
	void refreshTask(int remaining, String monster)
	{
		hasTask = remaining > 0;
		this.remaining = remaining;
		sessionActive = tracker.getState().isActive();
		String icon = null;
		if (!tracker.isTaskObserved())
		{
			taskLabel.setText(WAITING_FOR_GAME);
			taskLabel.setToolTipText(null);
		}
		else if (hasTask)
		{
			// The variant the active session's kills say is being done (its Catacombs variant, or Slayer
			// boss), and whether its Cannon tag is Yes: the name its task is saved under; then
			// whether it has boss kills its name doesn't show.
			TaskSelection selection = tracker.getCurrentTaskSelection();
			TaskVariants.Decision decision = tracker.getCurrentVariant();
			String variant = decision.getVariant();
			boolean cannon = Boolean.TRUE.equals(selection.getCannon());
			String boss = decision.isBossKills() && !decision.isVariantBoss()
				? TaskVariants.bossName(decision.getTask()) : null;
			taskLabel.setText(decision.displayName(monster, cannon));
			// A long monster name may not fit the card; the tooltip has it in full.
			taskLabel.setToolTipText("<html>Task: " + LedgerHistory.escapeHtml(monster)
				+ (variant == null ? "" : "<br>Doing: " + LedgerHistory.escapeHtml(variant) + " (from your kills)")
				+ (boss == null ? "" : "<br>Boss kills: " + LedgerHistory.escapeHtml(boss))
				+ (cannon ? "<br>Cannon: Yes, a cannon task" : "")
				+ "<br>Kills left: " + LedgerFormat.kills(remaining) + "</html>");
			// The assigned task's icon, also for a variant or cannon task, as the history rows show it.
			icon = tracker.getCurrentMonster();
		}
		else
		{
			taskLabel.setText(NO_TASK);
			taskLabel.setToolTipText(null);
		}
		refreshIcon(icon);
		TaskContext context = hasTask ? tracker.getCurrentContext() : TaskContext.NONE;
		master = context.getMaster();
		type = hasTask ? context.type(tracker.getCurrentCreatureId()) : null;
		area = type == null ? null : TaskLocation.normalizeName(context.getLocation());
		refreshDetails();
	}

	/** Shows {@code task}'s icon ({@link TaskIcons}), or none; asks for it only when the task changed. */
	private void refreshIcon(String task)
	{
		if (Objects.equals(task, iconTask))
		{
			return;
		}
		iconTask = task;
		Integer itemId = task == null ? null : TaskIcons.itemId(task);
		boolean show = itemId != null;
		iconLabel.setIcon(null);
		if (show)
		{
			builds++;
			icons.apply(iconLabel, itemId, 0);
		}
		if (iconLabel.isVisible() != show)
		{
			iconLabel.setVisible(show);
			relayout.run();
		}
	}

	/**
	 * Takes the active session's location while it is known: the name of the Slayer spot its kills so far decide
	 * ({@link TaskLocation#liveName}: the player's name for its region, asked of the plugin when not known yet,
	 * else the spot table's name). Until then the line shows a Konar or Wilderness task's assigned area.
	 */
	void refreshLocation(SessionTracker.State state)
	{
		String name = null;
		if (state.isActive())
		{
			TaskSpot spot = tracker.getTaskSpot();
			if (spot.getRegion() != null)
			{
				model.requestLocationNames(Collections.singleton(spot.getRegion()));
			}
			name = TaskLocation.liveName(spot, model::locationName, model.spots());
		}
		liveLocation = name;
		refreshDetails();
	}

	/**
	 * Shows the location line and the details line: the known parts of master, type (unless Regular or Konar), the
	 * kills left while no session is active and the next bonus, with each part and the location in full in the
	 * tooltip; each line is in the header only while it has something to say.
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
			if (!sessionActive)
			{
				// The task's size before the first kill and after End task; while a session is active the stats grid
				// shows it.
				parts.add(LedgerFormat.kills(remaining) + " left");
				tooltip.add("Kills left: " + LedgerFormat.kills(remaining));
			}
		}
		String location = liveLocation != null ? liveLocation : area;
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

		String tip = tooltip.isEmpty() ? null : "<html>" + String.join("<br>", tooltip) + "</html>";
		boolean changed = false;
		if (location != null)
		{
			// A player-typed location must not turn the line into HTML.
			locationLabel.setText(location.regionMatches(true, 0, "<html", 0, 5) ? " " + location : location);
			// A long location may not fit the card; the tooltip has every part in full.
			locationLabel.setToolTipText(tip);
		}
		changed |= showLine(locationLabel, location != null, 1);
		if (!parts.isEmpty())
		{
			detailsLabel.setText(String.join(SEPARATOR, parts));
			detailsLabel.setToolTipText(tip);
		}
		changed |= showLine(detailsLabel, !parts.isEmpty(), lineIndex(locationLabel) + 1);
		if (changed)
		{
			relayout.run();
		}
	}

	/** Where the line after {@code previous} goes: after it while it is shown, else after the name. */
	private int lineIndex(JLabel previous)
	{
		return previous.getParent() == lines ? lines.getComponentZOrder(previous) : 0;
	}

	/** Adds {@code line} at {@code index} or removes it, as {@code show} says; whether it changed. */
	private boolean showLine(JLabel line, boolean show, int index)
	{
		boolean shown = line.getParent() == lines;
		if (shown == show)
		{
			return false;
		}
		if (show)
		{
			lines.add(line, Math.min(index, lines.getComponentCount()));
		}
		else
		{
			lines.remove(line);
		}
		return true;
	}

	/**
	 * How many times the task icon was asked for. Performance seam: tests assert this doesn't grow on the refresh
	 * timer.
	 */
	int getBuildCount()
	{
		return builds;
	}
}
