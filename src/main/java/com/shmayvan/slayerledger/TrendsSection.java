package com.shmayvan.slayerledger;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The collapsed "Trends" section of {@link SlayerLedgerPanel}: one Metric choice and a List / Chart toggle over
 * the completed-task records. List ranks monsters ({@link TaskRanking}); Chart plots one point per task for a chosen
 * monster ({@link TaskChart}). Only the visible view is built, only while the section is open, and never by the
 * panel's refresh timer. The view, metric and monster choices last for the panel's life; they are not saved.
 * The list and the chart are worked out off the EDT: until the model has them, what is shown stays, and the
 * panel calls {@link #viewsReady} when they are ready. EDT only.
 *
 * <p>Task icons ({@link TaskIcons}): each List row shows its task's icon before its rank and name, and each
 * Monster choice before its name, through a wrapper around the look and feel's renderer ({@link TaskIconCache}). A
 * Catacombs variant or cannon group shows its task's icon ({@link TaskRanking.Entry#getTask},
 * {@link TaskChart.Monster#getTask}); a name that is no known task an empty slot of the same size. They are asked for
 * only when the shown view is built. The Monster box is sized from a short name, its open list widened to fit
 * ({@link ComboPopupWidener}), so a long name never widens the sidebar.</p>
 */
final class TrendsSection
{
	enum View
	{
		LIST,
		CHART
	}

	/** Works the list and the chart out, once per change of the records or their pricing. */
	private final LedgerHistoryModel model;
	/** Revalidates and repaints the panel after a change. */
	private final Runnable relayout;
	/** Draws the List rows' task icons. */
	private final ItemIcons icons;

	private final JPanel header = new JPanel(new BorderLayout(6, 0));
	private final JLabel title = new JLabel("Trends");
	private final JLabel toggle = new JLabel();
	private final JPanel body = new JPanel();
	private final JToggleButton listButton = new JToggleButton("List");
	private final JToggleButton chartButton = new JToggleButton("Chart");
	private final JComboBox<TaskRanking.Metric> metric = new JComboBox<>(TaskRanking.Metric.values());
	private final JComboBox<TaskChart.Monster> monster = new JComboBox<>();
	/** The Monster choices' icons. */
	private final TaskIconCache monsterIcons;
	private JPanel monsterRow;
	/** The Monster row with the gap below it, hidden as one in List view so no empty gap is left. */
	private final JPanel monsterBlock = new JPanel(new BorderLayout());
	private final JPanel list = new JPanel();
	private final TaskTrendChartView chart = new TaskTrendChartView();
	private boolean expanded;
	/** The "Trends" setting; while off, nothing is built, open or not. The panel hides the components. */
	private boolean shown = true;
	private View view = View.LIST;
	private boolean updatingChoices;
	/** How many times the shown view (the list rows or the chart points) was built. */
	private int builds;
	/** The model's results the shown list, Monster choices and chart were built from; {@code null} when none. */
	private List<TaskRanking.Entry> shownRanking;
	private List<TaskChart.Monster> shownMonsters;
	private List<TaskChart.Point> shownPoints;

	/** @param icons draws the task icons of the List rows and the Monster choices */
	TrendsSection(LedgerHistoryModel model, Runnable relayout, ItemIcons icons)
	{
		this.model = model;
		this.relayout = relayout;
		this.icons = icons;
		this.monsterIcons = new TaskIconCache(icons, monster);
	}

	/** Adds the header and the body to {@code content}, collapsed. */
	void addTo(JPanel content)
	{
		// Names for tests, which find the parts by them; no visible effect.
		header.setName("trends.header");
		title.setName("trends.title");
		toggle.setName("trends.toggle");
		body.setName("trends.body");
		listButton.setName("trends.listButton");
		chartButton.setName("trends.chartButton");
		metric.setName("trends.metric");
		monster.setName("trends.monster");
		list.setName("trends.list");
		chart.setName("trends.chart");
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		toggle.setFont(FontManager.getRunescapeSmallFont());
		toggle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		header.add(title, BorderLayout.WEST);
		header.add(toggle, BorderLayout.EAST);
		header.setToolTipText("<html>Compare completed tasks by profit, Slayer XP or Slayer points per active hour,"
			+ " or by profit per kill."
			+ "<br>List ranks monsters; Chart plots one point per task over time (hover a point for its task note)."
			+ "<br>An hourly rate needs " + TaskTimeEstimate.MIN_ACTIVE_MILLIS / 60_000 + " minute of active time."
			+ "<br>Points/h counts only tasks tracked from the full assigned amount.</html>");
		MouseAdapter toggleListener = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggle();
			}
		};
		header.addMouseListener(toggleListener);
		title.addMouseListener(toggleListener);
		toggle.addMouseListener(toggleListener);
		SlayerLedgerPanel.addRow(content, header);

		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel viewButtons = new JPanel(new GridLayout(1, 2, 4, 0));
		viewButtons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		ButtonGroup viewGroup = new ButtonGroup();
		for (JToggleButton button : new JToggleButton[]{listButton, chartButton})
		{
			button.setFont(FontManager.getRunescapeSmallFont());
			button.setFocusable(false);
			button.setMargin(new Insets(1, 4, 1, 4));
			viewGroup.add(button);
			viewButtons.add(button);
		}
		listButton.setToolTipText("Rank monsters by the metric");
		chartButton.setToolTipText("Chart one monster's tasks over time");
		listButton.setSelected(true);
		listButton.addActionListener(e -> setView(View.LIST));
		chartButton.addActionListener(e -> setView(View.CHART));
		SlayerLedgerPanel.addRow(body, SlayerLedgerPanel.selectorRow("View", viewButtons));

		metric.setFocusable(false);
		metric.setRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
				boolean focused)
			{
				String text = metricLabel((TaskRanking.Metric) value);
				return super.getListCellRendererComponent(list, text, index, selected, focused);
			}
		});
		metric.addActionListener(e ->
		{
			if (isOpen() && !updatingChoices)
			{
				buildView();
			}
		});
		SlayerLedgerPanel.addRow(body, SlayerLedgerPanel.selectorRow("Metric", metric));

		monster.setFocusable(false);
		monsterIcons.install(monster, TaskChart.Monster::getTask);
		// Sized from a short name, not the longest choice, so neither a long name nor the icon widens the sidebar.
		monster.setPrototypeDisplayValue(TaskChart.Monster.prototype("Abyssal demons"));
		ComboPopupWidener.install(monster, ComboPopupWidener.MAX_WIDTH);
		monster.addActionListener(e ->
		{
			if (isOpen() && view == View.CHART && !updatingChoices)
			{
				buildChartPoints();
			}
		});
		monsterRow = SlayerLedgerPanel.selectorRow("Monster", monster);
		monsterRow.setName("trends.monsterRow");
		monsterBlock.setOpaque(false);
		monsterBlock.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		monsterBlock.add(monsterRow, BorderLayout.CENTER);

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		// One row for the views: only the shown one takes space, so hidden ones leave no gaps.
		JPanel views = new JPanel();
		views.setLayout(new BoxLayout(views, BoxLayout.Y_AXIS));
		views.setOpaque(false);
		for (JPanel part : new JPanel[]{monsterBlock, list})
		{
			part.setAlignmentX(Component.LEFT_ALIGNMENT);
			views.add(part);
		}
		chart.setAlignmentX(Component.LEFT_ALIGNMENT);
		views.add(chart);
		SlayerLedgerPanel.addRow(body, views);
		body.setVisible(false);
		SlayerLedgerPanel.addRow(content, body);
		rebuild();
	}

	/**
	 * Shows the section as it now is: when open, builds the shown view from the current records, Loot price mode and
	 * prices; when collapsed, drops what was built. The panel calls this on record changes, a Loot price mode change
	 * and today's prices; never on its refresh timer.
	 */
	void rebuild()
	{
		toggle.setText(expanded ? "Hide" : "Show");
		body.setVisible(expanded);
		// The profit choice is labelled with the Loot price mode.
		metric.repaint();
		showViewComponents();
		if (isOpen())
		{
			buildView();
		}
		else
		{
			// Nothing is built while collapsed or hidden; opening or showing the section builds the shown view.
			clearList();
			clearChart();
		}
		relayout.run();
	}

	/**
	 * Applies the "Trends" setting: hidden, the section drops what was built and builds nothing; shown again, it
	 * is rebuilt from the current records at once if it was open.
	 */
	void setShown(boolean value)
	{
		if (shown != value)
		{
			shown = value;
			rebuild();
		}
	}

	/**
	 * A view worked out off the EDT is ready (the model's {@link LedgerHistoryModel.Change#VIEWS}): while open, builds
	 * the shown view again if the model now has a result it does not show.
	 */
	void viewsReady()
	{
		// While views wait for today's prices what is shown stays; the end of the wait rebuilds it.
		if (!isOpen() || model.isWaitingForCurrentPrices())
		{
			return;
		}
		TaskRanking.Metric selected = (TaskRanking.Metric) metric.getSelectedItem();
		if (view == View.LIST)
		{
			List<TaskRanking.Entry> entries = model.ranking(selected);
			if (entries != null && entries != shownRanking)
			{
				buildList();
			}
			return;
		}
		List<TaskChart.Monster> monsters = model.chartMonsters(selected);
		if (monsters == null)
		{
			return;
		}
		if (monsters != shownMonsters)
		{
			buildChart();
			return;
		}
		List<TaskChart.Point> points = model.chartPoints((TaskChart.Monster) monster.getSelectedItem(), selected);
		if (points != null && points != shownPoints)
		{
			buildChartPoints();
		}
	}

	/** Whether the section is open and its setting shows it, so the shown view is built. */
	private boolean isOpen()
	{
		return expanded && shown;
	}

	/** Opens or closes the section. */
	void toggle()
	{
		expanded = !expanded;
		rebuild();
	}

	/** Shows the list or the chart; when open, builds the newly shown one once and drops the other. */
	void setView(View newView)
	{
		if (newView == null || newView == view)
		{
			// Keep the buttons in step (a click on the selected button changes nothing).
			(view == View.LIST ? listButton : chartButton).setSelected(true);
			return;
		}
		view = newView;
		(view == View.LIST ? listButton : chartButton).setSelected(true);
		showViewComponents();
		if (view == View.LIST)
		{
			clearChart();
		}
		else
		{
			clearList();
		}
		if (isOpen())
		{
			buildView();
		}
		relayout.run();
	}

	private void showViewComponents()
	{
		boolean chartShown = view == View.CHART;
		monsterRow.setVisible(chartShown);
		monsterBlock.setVisible(chartShown);
		chart.setVisible(chartShown);
		list.setVisible(!chartShown);
	}

	private void buildView()
	{
		if (view == View.LIST)
		{
			buildList();
		}
		else
		{
			buildChart();
		}
	}

	/** A metric's name in the Metric choice; the profit one follows the Loot price mode. */
	private String metricLabel(TaskRanking.Metric value)
	{
		return value == null ? "" : value.label(model.lootPriceMode());
	}

	private void clearList()
	{
		list.removeAll();
		shownRanking = null;
	}

	private void clearChart()
	{
		chart.setData(Collections.emptyList(), null, null);
		shownMonsters = null;
		shownPoints = null;
	}

	private void buildList()
	{
		TaskRanking.Metric selected = (TaskRanking.Metric) metric.getSelectedItem();
		List<TaskRanking.Entry> entries = model.ranking(selected);
		if (entries == null)
		{
			// Worked out off the EDT: the rows shown stay until it is ready (viewsReady).
			return;
		}
		builds++;
		shownRanking = entries;
		list.removeAll();
		LootPriceMode mode = model.lootPriceMode();
		if (entries.isEmpty())
		{
			JLabel empty = new JLabel("No completed records with this data.");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setFont(FontManager.getRunescapeSmallFont());
			empty.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
			empty.setAlignmentX(Component.LEFT_ALIGNMENT);
			list.add(empty);
		}
		else
		{
			for (int i = 0; i < entries.size(); i++)
			{
				TaskRanking.Entry entry = entries.get(i);
				// Name left (truncates in a narrow sidebar), rate and count right-aligned, like a history line.
				JLabel name = SlayerLedgerPanel.historyLabel((i + 1) + ". " + entry.getMonster(),
					ColorScheme.LIGHT_GRAY_COLOR);
				// A name for tests, which read the icon from it; no visible effect.
				name.setName("trends.name");
				// The task's icon: a variant or cannon group shows its task's.
				SmallItemIcon.applyTask(icons, name, entry.getTask());
				JPanel row = SlayerLedgerPanel.historyLine(name,
					SlayerLedgerPanel.historyLabel(rateText(entry, selected), ColorScheme.LIGHT_GRAY_COLOR));
				row.setAlignmentX(Component.LEFT_ALIGNMENT);
				row.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 4));
				row.setToolTipText(rankingTooltip(entry, selected, mode));
				list.add(row);
				if (i + 1 < entries.size())
				{
					list.add(Box.createVerticalStrut(2));
				}
			}
		}
		relayout.run();
	}

	/** Refreshes the Monster choices for the metric (keeping the selection while it remains), then the points. */
	private void buildChart()
	{
		TaskRanking.Metric selected = (TaskRanking.Metric) metric.getSelectedItem();
		List<TaskChart.Monster> monsters = model.chartMonsters(selected);
		if (monsters == null)
		{
			// Worked out off the EDT: the chart shown stays until it is ready (viewsReady).
			return;
		}
		shownMonsters = monsters;
		TaskChart.Monster previous = (TaskChart.Monster) monster.getSelectedItem();
		updatingChoices = true;
		try
		{
			DefaultComboBoxModel<TaskChart.Monster> model = new DefaultComboBoxModel<>();
			for (TaskChart.Monster choice : monsters)
			{
				model.addElement(choice);
				// Each task's icon once, as the chart is built.
				monsterIcons.request(choice.getTask());
			}
			monster.setModel(model);
			int kept = previous == null ? -1 : monsters.indexOf(previous);
			if (kept >= 0)
			{
				// The new choice, so a changed label shows.
				monster.setSelectedIndex(kept);
			}
			monster.setEnabled(!monsters.isEmpty());
		}
		finally
		{
			updatingChoices = false;
		}
		buildChartPoints();
	}

	private void buildChartPoints()
	{
		TaskChart.Monster selectedMonster = (TaskChart.Monster) monster.getSelectedItem();
		TaskRanking.Metric selected = (TaskRanking.Metric) metric.getSelectedItem();
		List<TaskChart.Point> points = model.chartPoints(selectedMonster, selected);
		if (points == null)
		{
			// Worked out off the EDT: the points shown stay until they are ready (viewsReady).
			return;
		}
		builds++;
		shownPoints = points;
		chart.setData(points, metricLabel(selected), ZoneId.systemDefault());
		relayout.run();
	}

	/**
	 * The rate and sample count; {@code - (n=N)} while an hourly metric has under a minute of active time. Profit per
	 * kill shows as {@code 12.3K gp/kill (n=N)}.
	 */
	private static String rateText(TaskRanking.Entry entry, TaskRanking.Metric metric)
	{
		String sampleCount = " (n=" + entry.getSampleCount() + ")";
		if (entry.getRatePerHour() == null)
		{
			return LedgerHistory.NO_VALUE + sampleCount;
		}
		switch (metric)
		{
			case XP:
				return LedgerHistory.formatAmount(entry.getRatePerHour(), false) + " XP/h" + sampleCount;
			case POINTS:
				return LedgerHistory.formatAmount(entry.getRatePerHour(), false) + " points/h" + sampleCount;
			case PROFIT_PER_KILL:
				return LedgerHistory.formatGp(entry.getRatePerHour(), entry.isApproximate()) + "/kill" + sampleCount;
			default:
				return LedgerHistory.formatGp(entry.getRatePerHour(), entry.isApproximate()) + "/h" + sampleCount;
		}
	}

	private String rankingTooltip(TaskRanking.Entry entry, TaskRanking.Metric metric, LootPriceMode mode)
	{
		String source;
		switch (metric)
		{
			case XP:
				source = "Recorded Slayer XP divided by recorded active time";
				break;
			case POINTS:
				source = "Recorded Slayer points divided by recorded active time.<br>Only tasks tracked from the full"
					+ " assigned amount count; a task started part-way through is left out.<br>Bonus tasks (every"
					+ " 10th in a streak) are left out too";
				break;
			default:
				source = "Loot at " + mode + " prices minus GE-valued supplies"
					+ (mode == LootPriceMode.HIGH_ALCH
					? " (today's available GE prices for GE-priced items)"
					: " (today's available GE prices)");
				source += metric.isPerKill() ? ", divided by recorded kills" : ", divided by recorded active time";
				break;
		}
		Long rate = entry.getRatePerHour();
		String rateLine = metric.label(mode) + ": " + (rate == null
			? "no rate until " + TaskTimeEstimate.MIN_ACTIVE_MILLIS / 60_000 + " minute of active time"
			: (entry.isApproximate() ? LedgerHistory.APPROXIMATE : "") + String.format("%,d", rate));
		return "<html>" + LedgerHistory.escapeHtml(entry.getMonster()) + "<br>" + LedgerHistory.escapeHtml(rateLine)
			+ "<br>" + source + "<br>" + entry.getSampleCount() + (entry.getSampleCount() == 1
				? " completed task included" : " completed tasks included")
			+ (entry.isApproximate()
			? "<br>Some item prices were unavailable, or an older task kept its saved totals." : "") + "</html>";
	}

	/** How many times the shown view was built. Performance seam: tests assert this doesn't grow on the refresh timer. */
	int getBuildCount()
	{
		return builds;
	}
}
