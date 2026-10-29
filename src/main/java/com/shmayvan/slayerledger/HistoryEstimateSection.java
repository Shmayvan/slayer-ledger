package com.shmayvan.slayerledger;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The History estimate of {@link SlayerLedgerPanel} with What to bring below it: historical estimates for
 * the kills left on the observed task, and the supplies and bank trips they suggest. The text and tooltips are rebuilt
 * only when the task's statistics, the kills left or the item names change, never every second by the panel's refresh
 * timer; while views wait for today's prices what is shown stays. Nothing of What to bring is worked out while
 * its "What to bring" setting hides it. EDT only.
 */
final class HistoryEstimateSection
{
	/**
	 * The estimate's note when the session has fired a cannonball but the history has no cannon tasks of the task, so
	 * tasks with and without a cannon still count.
	 */
	static final String NO_CANNON_TASKS_NOTE = "No cannon tasks yet, so tasks without a cannon count too.";

	private final LedgerHistoryModel model;
	private final SessionTracker tracker;
	/** Revalidates and repaints the panel after a change. */
	private final Runnable relayout;

	/**
	 * Historical estimate for the observed task, two short lines like a history row: "History (N tasks)" and
	 * the kills and time, then the loot in the "Loot price" mode and the supplies. The tooltip has the averages. From
	 * the history at the current location (Part B), the title "History at <location> (N tasks)" has a line of its
	 * own ({@link #timeLine}).
	 */
	private final JPanel box = new JPanel(new GridLayout(0, 1, 0, 2));
	/** The estimate's first line: its title, then (unless it is on {@link #timeLine}) the time. */
	private final JPanel titleLine = new JPanel(new BorderLayout(6, 0));
	/**
	 * The kills and time on a line of their own, below a "History at <location>" title (Part B), which then has the
	 * whole width: beside the time, too little of a location name would show.
	 */
	private final JPanel timeLine = new JPanel(new BorderLayout(6, 0));
	private final JLabel titleLabel = new JLabel();
	/** " (N tasks)" right after {@link #titleLabel}, never cut: a long "History at <location>" truncates before it. */
	private final JLabel countLabel = new JLabel();
	private final JLabel timeLabel = new JLabel();
	private final JLabel lootLabel = new JLabel();
	private final JLabel suppliesLabel = new JLabel();
	/** How many times the estimate's text and tooltip were built. */
	private int builds;
	/**
	 * Cache key ({@link LedgerHistoryModel#taskStatisticsKey} and the kills left) of the shown estimate's text and
	 * tooltip, so the refresh timer doesn't rebuild them every second; {@code null} when they must be rebuilt.
	 */
	private String key;
	/** {@link #key} without the location, so the estimate can stay while only the location is pending. */
	private String anyLocationKey;

	/**
	 * What to bring for the kills left, styled like the History box below which it sits: a header with
	 * "What to bring (N tasks)", Show/Hide, the bank trips and the number of supplies, then (while open) one row per
	 * supply, name left and amount right. Rebuilt only with the estimate text ({@link #key}).
	 */
	private final JPanel bringSection = new JPanel();
	private final JPanel bringHeader = new JPanel(new GridLayout(2, 1, 0, 2));
	private final JLabel bringTitleLabel = new JLabel();
	private final JLabel bringToggleLabel = new JLabel();
	private final JLabel bringTripsLabel = new JLabel();
	private final JLabel bringCountLabel = new JLabel();
	private final JPanel bringRows = new JPanel();
	/** Whether What to bring is open; closed whenever the panel is created and kept for the session (owner). */
	private boolean bringExpanded;
	/** How many times What to bring was built. */
	private int bringBuilds;
	/** Whether the cached estimate ({@link #key}) has a What to bring section to show. */
	private boolean bringSectionBuilt;
	/** The "What to bring" setting; while off, nothing of the section is built. The panel hides its block. */
	private boolean bringShown = true;

	HistoryEstimateSection(LedgerHistoryModel model, SessionTracker tracker, Runnable relayout)
	{
		this.model = model;
		this.tracker = tracker;
		this.relayout = relayout;
	}

	/** Adds the History estimate box to {@code content}, hidden until there is an estimate. */
	void addTo(JPanel content)
	{
		// Names for tests, which find the parts by them; no visible effect.
		box.setName("estimate");
		titleLabel.setName("estimate.title");
		countLabel.setName("estimate.count");
		timeLabel.setName("estimate.time");
		timeLine.setName("estimate.timeLine");
		lootLabel.setName("estimate.loot");
		suppliesLabel.setName("estimate.supplies");
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		for (JLabel label : new JLabel[]{titleLabel, countLabel, timeLabel, lootLabel, suppliesLabel})
		{
			label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			label.setFont(FontManager.getRunescapeSmallFont());
		}
		JPanel title = new JPanel(new PrefixFitLayout());
		title.setOpaque(false);
		title.add(titleLabel);
		title.add(countLabel);
		titleLine.setOpaque(false);
		titleLine.add(title, BorderLayout.CENTER);
		titleLine.add(timeLabel, BorderLayout.EAST);
		timeLine.setOpaque(false);
		box.add(titleLine);
		box.add(SlayerLedgerPanel.historyLine(lootLabel, suppliesLabel));
		box.setVisible(false);
		SlayerLedgerPanel.addRow(content, box);
	}

	/** Adds What to bring to {@code block} (its optional block), closed and hidden until an estimate is shown. */
	void addBringTo(JPanel block)
	{
		// Names for tests, which find the parts by them; no visible effect.
		bringSection.setName("bring");
		bringHeader.setName("bring.header");
		bringTitleLabel.setName("bring.title");
		bringToggleLabel.setName("bring.toggle");
		bringTripsLabel.setName("bring.trips");
		bringCountLabel.setName("bring.count");
		bringRows.setName("bring.rows");
		bringSection.setLayout(new BoxLayout(bringSection, BoxLayout.Y_AXIS));
		bringSection.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		bringSection.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		bringHeader.setOpaque(false);
		bringHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
		bringHeader.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		for (JLabel label : new JLabel[]{bringTitleLabel, bringToggleLabel, bringTripsLabel, bringCountLabel})
		{
			label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			label.setFont(FontManager.getRunescapeSmallFont());
		}
		bringToggleLabel.setText("Show");
		bringHeader.add(SlayerLedgerPanel.historyLine(bringTitleLabel, bringToggleLabel));
		bringHeader.add(SlayerLedgerPanel.historyLine(bringTripsLabel, bringCountLabel));
		MouseAdapter toggle = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1)
				{
					toggleBring();
				}
			}
		};
		// Only the header listens: its labels have no listener or tooltip, so clicks and the tooltip reach it.
		bringHeader.addMouseListener(toggle);
		bringSection.add(bringHeader);

		bringRows.setLayout(new BoxLayout(bringRows, BoxLayout.Y_AXIS));
		bringRows.setOpaque(false);
		bringRows.setAlignmentX(Component.LEFT_ALIGNMENT);
		bringRows.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		bringRows.setVisible(false);
		bringSection.add(bringRows);
		bringSection.setVisible(false);
		SlayerLedgerPanel.addRow(block, bringSection);
	}

	/** Drops the cached estimate text, so the next {@link #refresh} rebuilds it. */
	void invalidate()
	{
		key = null;
	}

	/**
	 * Applies the "What to bring" setting: hidden, its rows are dropped and nothing of it is worked out; shown
	 * again, it is rebuilt with the estimate at once. The panel shows or hides the section's block.
	 */
	void setBringShown(boolean shown)
	{
		if (bringShown == shown)
		{
			return;
		}
		bringShown = shown;
		if (!shown)
		{
			bringRows.removeAll();
			bringSectionBuilt = false;
			setBringVisible(false);
		}
		// Built with the estimate text, so that is rebuilt too.
		key = null;
		refresh();
	}

	/**
	 * Shows historical estimates for the kills left on the observed task (the assigned size while it is new), also
	 * while it isn't being timed. The text and tooltip are rebuilt only when the statistics, the kills left or the
	 * item names change, not on every refresh.
	 */
	void refresh()
	{
		// The model decides whether the preview is posted; this is one of the points it is checked again at.
		model.checkAssignmentPreview();
		int remaining = tracker.getCurrentRemaining();
		if (!tracker.isTaskObserved() || remaining <= 0)
		{
			// Rebuilt when shown again, so the supply recommendation comes back with it.
			key = null;
			setVisible(false);
			setBringVisible(false);
			return;
		}
		if (model.isWaitingForCurrentPrices())
		{
			// Valued at today's prices: what is shown stays until the prices asked for arrive (PRICED_VIEWS).
			return;
		}

		int creatureId = tracker.getCurrentCreatureId();
		String monster = tracker.getCurrentMonster();
		String location = model.currentLocation();
		// Until the session's kills say which version of the task is done, both are pooled; until it fires a
		// cannonball, tasks with and without a cannon are, and after it too while there are no cannon tasks.
		TaskSelection selection = tracker.getCurrentTaskSelection();
		String newKey = model.taskStatisticsKey(creatureId, monster, selection, location) + "\u0000" + remaining;
		String newAnyLocationKey = model.taskStatisticsKey(creatureId, monster, selection, null) + "\u0000"
			+ remaining;
		if (newKey.equals(key) || (model.isCurrentLocationPending() && key != null
			&& newAnyLocationKey.equals(anyLocationKey)))
		{
			// While the name of the location is on its way, the shown estimate stays; it is rebuilt when it arrives.
			setVisible(true);
			setBringVisible(bringSectionBuilt);
			return;
		}
		TaskStatistics stats = model.taskStatistics(creatureId, monster, selection, location);
		TaskStatistics.Estimate estimate = stats.estimate(remaining);
		if (estimate == null)
		{
			key = null;
			setVisible(false);
			setBringVisible(false);
			return;
		}

		builds++;
		model.requestItemNames(stats.getSupplyTotals().keySet());
		// Nothing of What to bring is worked out while its setting hides it.
		WhatToBring bring = bringShown ? model.whatToBring(stats, remaining) : null;
		bringSectionBuilt = bring != null;
		refreshBring(bring, stats);
		// "History at Smoke Dungeon (3 tasks)": a long location truncates, the count stays (PrefixFitLayout).
		titleLabel.setText(stats.getLocation() == null ? "History" : "History at " + stats.getLocation());
		setTimeOnOwnLine(stats.getLocation() != null);
		countLabel.setText(" (" + estimate.getSampleCount() + " "
			+ (estimate.getSampleCount() == 1 ? "task" : "tasks") + ")");
		timeLabel.setText(remaining + (remaining == 1 ? " kill" : " kills")
			+ (estimate.getActiveMillis() == null ? "" : " ~" + LedgerHistory.formatDuration(estimate.getActiveMillis())));
		// Like a history row: the loot labelled with its price mode, no " gp", so both fit one sidebar line.
		lootLabel.setText(LedgerHistory.lootLabel(model.lootPriceMode())
			+ LedgerHistory.formatAmount(estimate.getLootValue(), estimate.isLootApproximate()));
		suppliesLabel.setText("Supplies: "
			+ LedgerHistory.formatAmount(estimate.getSuppliesValue(), estimate.isSuppliesApproximate()));
		box.setToolTipText(statisticsTooltip(stats, selectionNote(monster, selection,
			model.effectiveSelection(creatureId, monster, selection), stats)));
		key = newKey;
		anyLocationKey = newAnyLocationKey;
		setVisible(true);
	}

	/** Opens or closes What to bring, as a click on its header does. Nothing is rebuilt. */
	void toggleBring()
	{
		bringExpanded = !bringExpanded;
		bringToggleLabel.setText(bringExpanded ? "Hide" : "Show");
		bringRows.setVisible(bringExpanded);
		relayout.run();
	}

	/**
	 * Rebuilds What to bring for {@code bring}, or hides it for {@code null}. Called only when the estimate text is
	 * rebuilt (the statistics, the kills left or the item names changed), never by the refresh timer.
	 */
	private void refreshBring(WhatToBring bring, TaskStatistics stats)
	{
		if (bring == null)
		{
			setBringVisible(false);
			return;
		}
		SupplyRecommendation.Estimate estimate = bring.getEstimate();
		bringBuilds++;

		boolean suppliesKnown = estimate.getSupplySampleCount() > 0;
		int tasks = suppliesKnown ? estimate.getSupplySampleCount() : estimate.getBankTripSampleCount();
		bringTitleLabel.setText("What to bring (" + tasks + (tasks == 1 ? " task)" : " tasks)"));
		Long trips = estimate.getBankTrips();
		bringTripsLabel.setText(trips == null ? "" : trips == 0 ? "No bank trips"
			: "~" + LedgerHistory.formatAmount(trips, false) + (trips == 1 ? " bank trip" : " bank trips"));
		int count = estimate.getSupplies().size();
		bringCountLabel.setText(count == 0 ? "" : count + (count == 1 ? " supply" : " supplies"));
		bringHeader.setToolTipText(bringTooltip(estimate, stats.getLocation()));

		bringRows.removeAll();
		if (!suppliesKnown)
		{
			bringRows.add(bringMessage("No supply data in history"));
		}
		else if (estimate.getSupplies().isEmpty())
		{
			bringRows.add(bringMessage("No tracked supplies in history"));
		}
		else
		{
			for (BringRow row : bring.getRows())
			{
				JPanel line = SlayerLedgerPanel.historyLine(
					SlayerLedgerPanel.historyLabel(row.getName(), ColorScheme.LIGHT_GRAY_COLOR),
					SlayerLedgerPanel.historyLabel(row.quantityText(), ColorScheme.LIGHT_GRAY_COLOR));
				line.setAlignmentX(Component.LEFT_ALIGNMENT);
				line.setToolTipText("<html>" + LedgerHistory.escapeHtml(row.tooltipText()) + " for the "
					+ killsText(estimate.getKills()) + " left</html>");
				bringRows.add(line);
			}
		}
		bringRows.setVisible(bringExpanded);
		bringSection.setVisible(true);
		relayout.run();
	}

	private static JPanel bringMessage(String message)
	{
		JPanel line = SlayerLedgerPanel.historyLine(SlayerLedgerPanel.historyLabel(message,
			ColorScheme.MEDIUM_GRAY_COLOR), null);
		line.setAlignmentX(Component.LEFT_ALIGNMENT);
		return line;
	}

	/**
	 * Lays out two components left to right with no gap, each at most its preferred width: the second ({@code " (3
	 * tasks)"}) always gets its full width while it fits, and the first (a JLabel, {@code "History at Smoke Dungeon"})
	 * gets what is left, so a long first text truncates with "..." and the second stays right after it.
	 */
	private static final class PrefixFitLayout implements LayoutManager
	{
		@Override
		public void addLayoutComponent(String name, Component comp)
		{
		}

		@Override
		public void removeLayoutComponent(Component comp)
		{
		}

		@Override
		public Dimension preferredLayoutSize(Container parent)
		{
			int width = 0;
			int height = 0;
			for (Component component : parent.getComponents())
			{
				Dimension size = component.getPreferredSize();
				width += size.width;
				height = Math.max(height, size.height);
			}
			Insets insets = parent.getInsets();
			return new Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom);
		}

		@Override
		public Dimension minimumLayoutSize(Container parent)
		{
			Dimension preferred = preferredLayoutSize(parent);
			Insets insets = parent.getInsets();
			// May shrink to nothing: the first component truncates, the line never widens the sidebar.
			return new Dimension(insets.left + insets.right, preferred.height);
		}

		@Override
		public void layoutContainer(Container parent)
		{
			Insets insets = parent.getInsets();
			int available = Math.max(0, parent.getWidth() - insets.left - insets.right);
			int height = Math.max(0, parent.getHeight() - insets.top - insets.bottom);
			Component[] components = parent.getComponents();
			if (components.length == 0)
			{
				return;
			}
			int secondWidth = components.length > 1
				? Math.min(components[1].getPreferredSize().width, available) : 0;
			int firstWidth = Math.max(0, Math.min(components[0].getPreferredSize().width, available - secondWidth));
			components[0].setBounds(insets.left, insets.top, firstWidth, height);
			for (int i = 1; i < components.length; i++)
			{
				components[i].setBounds(insets.left + firstWidth, insets.top, i == 1 ? secondWidth : 0, height);
			}
		}
	}

	/**
	 * The header tooltip: what the amounts are for and what they are based on (only the tasks at {@code location} when
	 * it is not {@code null}).
	 */
	private static String bringTooltip(SupplyRecommendation.Estimate estimate, String location)
	{
		StringBuilder tooltip = new StringBuilder("<html>Suggested for the ")
			.append(killsText(estimate.getKills())).append(" left.");
		if (location != null)
		{
			tooltip.append("<br>From your tasks at ").append(LedgerHistory.escapeHtml(location)).append(" only.");
		}
		tooltip.append("<br>Supplies: ");
		if (estimate.getSupplySampleCount() > 0)
		{
			tooltip.append("pooled use per kill over ").append(tasksText(estimate.getSupplySampleCount()))
				.append(" (").append(killsText(estimate.getSupplyKills())).append(") with supplies or values,")
				.append(" times the kills left, rounded up to whole tracked units.");
		}
		else
		{
			tooltip.append("no completed task recorded supplies or values.");
		}
		tooltip.append("<br>Bank trips: ");
		if (estimate.getBankTrips() != null)
		{
			tooltip.append("pooled trips per kill over ").append(tasksText(estimate.getBankTripSampleCount()))
				.append(" (").append(killsText(estimate.getBankTripKills())).append(") with a bank-trip count,")
				.append(" times the kills left, rounded up.");
		}
		else
		{
			tooltip.append("no completed task recorded them.");
		}
		return tooltip.append("<br>Click to show or hide the supplies.</html>").toString();
	}

	private static String killsText(long kills)
	{
		return LedgerHistory.formatAmount(kills, false) + (kills == 1 ? " kill" : " kills");
	}

	private static String tasksText(int tasks)
	{
		return tasks + (tasks == 1 ? " completed task" : " completed tasks");
	}

	/**
	 * Puts the estimate's kills and time on a line of their own below the title ({@code true}, for "History at
	 * <location>"), or beside the title as a two-line box ({@code false}).
	 */
	private void setTimeOnOwnLine(boolean ownLine)
	{
		if ((timeLabel.getParent() == timeLine) == ownLine)
		{
			return;
		}
		if (ownLine)
		{
			titleLine.remove(timeLabel);
			timeLine.add(timeLabel, BorderLayout.CENTER);
			box.add(timeLine, 1);
		}
		else
		{
			box.remove(timeLine);
			timeLine.remove(timeLabel);
			titleLine.add(timeLabel, BorderLayout.EAST);
		}
		relayout.run();
	}

	private void setVisible(boolean visible)
	{
		if (box.isVisible() != visible)
		{
			box.setVisible(visible);
			relayout.run();
		}
	}

	private void setBringVisible(boolean visible)
	{
		if (bringSection.isVisible() != visible)
		{
			bringSection.setVisible(visible);
			relayout.run();
		}
	}

	/**
	 * Which of the task's tasks the estimate counts, as plain text, or {@code null} when there is nothing to say: the
	 * {@link #variantNote} and the {@link #cannonNote}, in one line.
	 */
	static String selectionNote(String monster, TaskSelection selection, TaskSelection effective, TaskStatistics stats)
	{
		String variant = variantNote(monster, selection);
		String cannon = cannonNote(selection, effective, stats);
		if (variant == null || cannon == null)
		{
			return variant != null ? variant : cannon;
		}
		return variant + " " + cannon;
	}

	/**
	 * Which version of a task with a Catacombs variant the estimate counts, as plain text, or {@code null} for a
	 * task without one: both until the session's kills say which, then only that one.
	 */
	static String variantNote(String monster, TaskSelection selection)
	{
		CatacombsVariants.Variant catacombs = CatacombsVariants.forTask(monster);
		if (catacombs == null || selection == null)
		{
			return null;
		}
		if (selection.isAnyVariant())
		{
			return "Counts " + catacombs.getName() + " tasks too, until your kills show which you are doing.";
		}
		return selection.getVariant() != null
			? "Only your " + selection.getVariant() + " tasks count."
			: "Your " + catacombs.getName() + " tasks are left out.";
	}

	/**
	 * Whether the estimate counts the task's cannon tasks, as plain text, from the live {@code selection} and
	 * the one the statistics use ({@code effective}, {@link LedgerHistoryModel#effectiveSelection}): once the session
	 * has fired a cannonball, only them, or, with none in the history, all tasks; before that both, said only when
	 * {@code stats} have some; else {@code null}.
	 */
	static String cannonNote(TaskSelection selection, TaskSelection effective, TaskStatistics stats)
	{
		if (selection != null && Boolean.TRUE.equals(selection.getCannon()))
		{
			return effective != null && Boolean.TRUE.equals(effective.getCannon())
				? "Only your cannon tasks count."
				: NO_CANNON_TASKS_NOTE;
		}
		if (stats != null && stats.getCannonSampleCount() > 0)
		{
			return "Counts cannon tasks too, until you fire a cannonball.";
		}
		return null;
	}

	private String statisticsTooltip(TaskStatistics stats, String selectionNote)
	{
		StringBuilder tooltip = new StringBuilder("<html>Completed-task history");
		if (stats.getLocation() != null)
		{
			tooltip.append(" at ").append(LedgerHistory.escapeHtml(stats.getLocation()));
		}
		tooltip.append(" (")
			.append(stats.getSampleCount()).append(stats.getSampleCount() == 1 ? " sample" : " samples")
			.append("); outliers are retained.");
		if (stats.getLocation() != null)
		{
			tooltip.append("<br>Only tasks at this location count; without any there, all of them do.");
		}
		if (selectionNote != null)
		{
			tooltip.append("<br>").append(LedgerHistory.escapeHtml(selectionNote));
		}
		tooltip.append("<br>Average task duration: ")
			.append(LedgerHistory.formatDuration(stats.getAverageTaskDurationMillis()));
		Long msPerKill = stats.getMillisPerKill();
		tooltip.append("<br>Average time per kill: ")
			.append(msPerKill == null ? LedgerHistory.NO_VALUE : LedgerHistory.formatKillTime(msPerKill));
		tooltip.append("<br>Loot per kill: ")
			.append(formatHistoricalGp(stats.getLootPerKill(), stats.isLootApproximate()))
			.append("<br>Supplies per kill: ")
			.append(formatHistoricalGp(stats.getSuppliesValuePerKill(), stats.isSuppliesApproximate()))
			.append("<br>Profit per kill: ")
			.append(formatHistoricalGp(stats.getProfitPerKill(), stats.isProfitApproximate()));

		Map<Integer, Double> supplies = stats.getSuppliesPerKill();
		if (!supplies.isEmpty())
		{
			tooltip.append("<br>Supplies used per kill:");
			for (Map.Entry<Integer, Double> item : supplies.entrySet())
			{
				String known = model.itemName(item.getKey());
				String name = known == null ? "Item " + item.getKey() : known;
				// Dose-counted ids (potions, food eaten in bites, charged jewellery) in their own unit, as the item lists.
				Integer doses = stats.getSupplyDoses().get(item.getKey());
				String unit = doses == null ? "items"
					: ItemDetails.doseUnit(item.getKey()).name().toLowerCase(Locale.ROOT);
				tooltip.append("<br>").append(LedgerHistory.escapeHtml(name)).append(": ")
					.append(formatAverage(item.getValue())).append(' ').append(unit).append("/kill");
			}
		}

		Map<String, Double> xpPerKill = stats.getXpPerKill();
		if (!xpPerKill.isEmpty())
		{
			tooltip.append("<br>XP per kill:");
			for (Map.Entry<String, Double> skill : xpPerKill.entrySet())
			{
				tooltip.append("<br>").append(LedgerHistory.escapeHtml(SkillXp.skillName(skill.getKey())))
					.append(": ").append(formatAverage(skill.getValue()));
			}
		}
		Long xpPerHour = stats.getXpPerHour();
		if (xpPerHour != null)
		{
			tooltip.append("<br>XP per hour: ").append(LedgerHistory.formatAmount(xpPerHour, false));
		}
		tooltip.append("<br>GP averages use today's prices in ")
			.append(LedgerHistory.escapeHtml(model.lootPriceMode().toString()))
			.append(" mode; older unvalued tasks are excluded.")
			.append("<br>XP and supply-item averages use only records that captured those data.</html>");
		return tooltip.toString();
	}

	private static String formatHistoricalGp(Long value, boolean approximate)
	{
		return value == null ? LedgerHistory.NO_VALUE : LedgerHistory.formatAmount(value, approximate) + " gp";
	}

	private static String formatAverage(double value)
	{
		String formatted = String.format(Locale.ROOT, "%.2f", value);
		while (formatted.endsWith("0"))
		{
			formatted = formatted.substring(0, formatted.length() - 1);
		}
		return formatted.endsWith(".") ? formatted.substring(0, formatted.length() - 1) : formatted;
	}

	/**
	 * How many times the estimate's text and tooltip were built. Performance seam: tests assert this doesn't grow on the
	 * refresh timer.
	 */
	int getBuildCount()
	{
		return builds;
	}

	/** How many times What to bring was built. Performance seam: tests assert this doesn't grow on the refresh timer. */
	int getBringBuildCount()
	{
		return bringBuilds;
	}
}
