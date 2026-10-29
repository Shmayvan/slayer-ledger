package com.shmayvan.slayerledger;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The collapsed "Overall totals" section of {@link SlayerLedgerPanel}: completed-task totals, overall or grouped
 * by Slayer master, month or monster ({@link OverallTotals}, worked out by the history model). The rows are built only
 * while the section is open and its "Overall totals" setting shows it, and never by the panel's refresh timer.
 * Overall and By month show the task count, active time, profit and XP of all skills; By monster and By master
 * show averages and rates instead, and By master its monsters assigned ({@link OverallTotals.Assignment}) in a
 * text list with "+N more" ({@link ItemListView}).
 *
 * <p>By monster's group names and By master's monsters assigned show the task's icon before the name (* {@link TaskIcons}), the item sprite at the XP lists' small icon size ({@link SmallItemIcon}), asked for on the EDT
 * only when the rows are built (open and shown, never by the refresh timer); a task without a known icon keeps an
 * empty slot of the same size.</p>
 *
 * <p>By master's group names show the master's face ({@link MasterIcons}), the bundled chathead image drawn in
 * the same slot, read the first time it is shown; a missing image or an unknown master keeps the empty slot.</p>
 *
 * <p>Each group has two item grids, "Supplies used" and "Loot received", closed until clicked. Its items are
 * summed off the EDT with the totals ({@link OverallTotals.Entry#getSupplies()}); here they are only named
 * ({@link ItemDetails#named}, with the names the model's {@link NameBook} has, asked for here) and shown. A grid stays
 * open or closed while the section rebuilds the same group in the same grouping; closing the section or changing the
 * grouping closes them again. EDT only.</p>
 */
final class OverallTotalsSection
{
	/** XP is every skill's (owner decision), unlike Trends' Slayer XP/h. */
	private static final String XP_LABEL = "XP (all skills)";
	private static final String SUPPLIES_TITLE = "Supplies used";
	private static final String LOOT_TITLE = "Loot received";
	private static final String ASSIGNMENTS_TITLE = "Monsters assigned";
	/** The assignment lines have no skill icons; their task icons are item sprites ({@link #icons}). */
	private static final SkillIcons NO_ICONS = (label, skill) ->
	{
	};

	private final LedgerHistoryModel model;
	/** Revalidates and repaints the panel after a change. */
	private final Runnable relayout;
	/** Draws the item grids' icons and the task icons. */
	private final ItemIcons icons;
	/** The masters' faces of By master. */
	private final MasterIcons masterIcons;

	private final JPanel header = new JPanel(new BorderLayout(6, 0));
	private final JLabel title = new JLabel("Overall totals");
	private final JLabel toggle = new JLabel();
	private final JPanel body = new JPanel();
	private final JComboBox<OverallTotals.Grouping> grouping = new JComboBox<>(OverallTotals.Grouping.values());
	private final JPanel list = new JPanel();
	private boolean expanded;
	/** The "Overall totals" setting; while off, nothing is summed, open or not. The panel hides the components. */
	private boolean shown = true;
	/** How many times the rows were built. */
	private int builds;
	/** The model's totals the rows shown were built from; {@code null} when none are. */
	private List<OverallTotals.Entry> shownEntries;
	/**
	 * The item grids shown, by grouping, group and list, with the unnamed rows they show; kept while the same groups
	 * are rebuilt, so an open grid stays open.
	 */
	private Map<String, Grid> grids = new HashMap<>();
	/** The By master assignment lists shown, by grouping and group; kept like {@link #grids}, so "+N more" stays. */
	private Map<String, ItemListView> assignmentLists = new HashMap<>();

	/** @param masterIcons the masters' faces before By master's group names */
	OverallTotalsSection(LedgerHistoryModel model, Runnable relayout, ItemIcons icons, MasterIcons masterIcons)
	{
		this.model = model;
		this.relayout = relayout;
		this.icons = icons;
		this.masterIcons = masterIcons;
	}

	/** Adds the header and the body to {@code content}, collapsed. */
	void addTo(JPanel content)
	{
		// Names for tests, which find the parts by them; no visible effect.
		header.setName("totals.header");
		title.setName("totals.title");
		toggle.setName("totals.toggle");
		body.setName("totals.body");
		grouping.setName("totals.grouping");
		list.setName("totals.list");
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		toggle.setFont(FontManager.getRunescapeSmallFont());
		toggle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		header.add(title, BorderLayout.WEST);
		header.add(toggle, BorderLayout.EAST);
		header.setToolTipText("Completed-task totals, overall or grouped by Slayer master, month or monster.");
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
		JPanel groupingRow = new JPanel(new BorderLayout(6, 0));
		groupingRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel groupingLabel = new JLabel("Group");
		groupingLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		groupingLabel.setFont(FontManager.getRunescapeSmallFont());
		grouping.setFocusable(false);
		grouping.addActionListener(e ->
		{
			if (expanded && shown)
			{
				buildRows();
			}
		});
		groupingRow.add(groupingLabel, BorderLayout.WEST);
		groupingRow.add(grouping, BorderLayout.CENTER);
		SlayerLedgerPanel.addRow(body, groupingRow);

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		list.setAlignmentX(Component.LEFT_ALIGNMENT);
		SlayerLedgerPanel.addRow(body, list);
		body.setVisible(false);
		SlayerLedgerPanel.addRow(content, body);
		rebuild();
	}

	/** Opens or closes the section. */
	void toggle()
	{
		expanded = !expanded;
		rebuild();
	}

	/**
	 * Applies the "Overall totals" setting: hidden, the section drops its rows and sums nothing; shown again, it
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

	/** Shows the section as it now is: when open and shown, sums the current records; otherwise drops the rows. */
	void rebuild()
	{
		toggle.setText(expanded ? "Hide" : "Show");
		body.setVisible(expanded);
		if (expanded && shown)
		{
			buildRows();
		}
		else
		{
			// Nothing is summed while collapsed or hidden; opening or showing the section sums the current records.
			list.removeAll();
			shownEntries = null;
			grids = new HashMap<>();
			assignmentLists = new HashMap<>();
		}
		relayout.run();
	}

	/**
	 * Item names or the nature rune price (the alch hint) arrived: while open and shown, the grids are named again.
	 * Only grids whose rows changed are redrawn, and the totals are not rebuilt.
	 */
	void itemNamesChanged()
	{
		if (expanded && shown && shownEntries != null)
		{
			for (Grid grid : grids.values())
			{
				grid.show();
			}
			relayout.run();
		}
	}

	/**
	 * The totals worked out off the EDT are ready (the model's {@link LedgerHistoryModel.Change#VIEWS}): while open and
	 * shown, builds the rows again if the model now has totals it does not show.
	 */
	void viewsReady()
	{
		// While views wait for today's prices what is shown stays; the end of the wait rebuilds it.
		if (expanded && shown && !model.isWaitingForCurrentPrices())
		{
			List<OverallTotals.Entry> entries = model.overallTotals(
				(OverallTotals.Grouping) grouping.getSelectedItem());
			if (entries != null && entries != shownEntries)
			{
				buildRows();
			}
		}
	}

	private void buildRows()
	{
		OverallTotals.Grouping selected = (OverallTotals.Grouping) grouping.getSelectedItem();
		List<OverallTotals.Entry> entries = model.overallTotals(selected);
		if (entries == null)
		{
			// Worked out off the EDT: the rows shown stay until it is ready (viewsReady).
			return;
		}
		builds++;
		shownEntries = entries;
		list.removeAll();
		Map<String, Grid> kept = new HashMap<>();
		Map<String, ItemListView> keptLists = new HashMap<>();
		Set<Integer> ids = new TreeSet<>();
		if (entries.isEmpty())
		{
			JLabel empty = new JLabel("No completed tasks yet.");
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
				OverallTotals.Entry entry = entries.get(i);
				String key = selected + "|" + entry.getLabel();
				JPanel row = row(entry, selected);
				JPanel items = items(entry, key, kept, ids);
				if (selected == OverallTotals.Grouping.MASTER)
				{
					// The monsters assigned come first, above the item grids.
					items.add(assignmentList(entry, key, keptLists), 0);
					JComponent gap = (JComponent) Box.createVerticalStrut(2);
					gap.setAlignmentX(Component.LEFT_ALIGNMENT);
					items.add(gap, 1);
				}
				row.add(items);
				list.add(row);
				if (i + 1 < entries.size())
				{
					list.add(Box.createVerticalStrut(2));
				}
			}
		}
		grids = kept;
		assignmentLists = keptLists;
		if (!ids.isEmpty())
		{
			model.requestItemNames(ids);
		}
		relayout.run();
	}

	/**
	 * A group's two item grids, below its lines: the grids already shown for {@code key} if any, so an open one stays
	 * open, else new closed ones. They go into {@code kept}, and their item ids into {@code ids}.
	 */
	private JPanel items(OverallTotals.Entry entry, String key, Map<String, Grid> kept, Set<Integer> ids)
	{
		JPanel items = new JPanel();
		// A name for tests, which read the lines above it apart from it; no visible effect.
		items.setName("totals.items");
		items.setLayout(new BoxLayout(items, BoxLayout.Y_AXIS));
		items.setOpaque(false);
		items.setAlignmentX(Component.LEFT_ALIGNMENT);
		items.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
		items.add(grid(key + "|supplies", "totals.supplies", SUPPLIES_TITLE, entry.getSupplies(), false, kept));
		items.add(Box.createVerticalStrut(2));
		items.add(grid(key + "|loot", "totals.loot", LOOT_TITLE, entry.getLoot(), true, kept));
		for (Component c : items.getComponents())
		{
			((JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
		}
		for (ItemDetails.Row row : entry.getSupplies())
		{
			ids.add(row.getItemId());
		}
		for (ItemDetails.Row row : entry.getLoot())
		{
			ids.add(row.getItemId());
		}
		return items;
	}

	/**
	 * A By master group's monsters assigned: {@code Dust devils | 12 / 80 (15%)}, most first, the top
	 * {@link ItemDetails#MAX_ROWS} until "+N more" is clicked. The list shown for {@code key} is kept, so its "+N more"
	 * choice stays while the same group rebuilds; it goes into {@code kept}.
	 */
	private ItemListView assignmentList(OverallTotals.Entry entry, String key, Map<String, ItemListView> kept)
	{
		ItemListView view = assignmentLists.get(key);
		if (view == null)
		{
			view = new ItemListView(ASSIGNMENTS_TITLE, false, ColorScheme.DARKER_GRAY_COLOR, "monsters", NO_ICONS,
				icons);
			// A name for tests, which find the list by it; no visible effect.
			view.setName("totals.assignments");
			view.hideCount();
			view.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
			view.setAlignmentX(Component.LEFT_ALIGNMENT);
		}
		List<AssignmentLine> lines = new ArrayList<>();
		for (OverallTotals.Assignment assignment : entry.getAssignments())
		{
			lines.add(new AssignmentLine(assignment, entry.getLabel()));
		}
		view.setRows(lines);
		kept.put(key, view);
		return view;
	}

	/** The grid for {@code key}, kept from the last build or new and closed, showing {@code rows}. */
	private ItemGridView grid(String key, String name, String title, List<ItemDetails.Row> rows, boolean loot,
		Map<String, Grid> kept)
	{
		Grid grid = grids.get(key);
		if (grid == null)
		{
			ItemGridView view = new ItemGridView(title, true, ColorScheme.DARKER_GRAY_COLOR, icons);
			// A name for tests, which find the grids by it; no visible effect.
			view.setName(name);
			view.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
			grid = new Grid(view, loot);
		}
		grid.rows = rows;
		grid.show();
		kept.put(key, grid);
		return grid.view;
	}

	/**
	 * One totals entry in short two-column lines that fit the sidebar, like a history row: the bold group name and
	 * task count, then the grouping's lines ({@link #addTotalLines}, {@link #addMonsterLines},
	 * {@link #addMasterLines}).
	 */
	private JPanel row(OverallTotals.Entry entry, OverallTotals.Grouping grouping)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 4));

		JLabel name = SlayerLedgerPanel.historyLabel(entry.getLabel(), Color.WHITE);
		name.setFont(FontManager.getRunescapeBoldFont());
		if (grouping == OverallTotals.Grouping.MONSTER)
		{
			taskIcon(name, entry.getTask());
		}
		else if (grouping == OverallTotals.Grouping.MASTER)
		{
			masterIcon(name, entry.getLabel());
		}
		JLabel count = SlayerLedgerPanel.historyLabel(SlayerLedgerPanel.sampleTasks(entry.getTaskCount()),
			ColorScheme.LIGHT_GRAY_COLOR);
		// Completed tasks, not By master's assignments.
		count.setToolTipText(completedTasks(entry.getTaskCount()));
		row.add(SlayerLedgerPanel.historyLine(name, count));
		if (grouping == OverallTotals.Grouping.MONSTER)
		{
			addMonsterLines(row, entry);
			row.setToolTipText(monsterTooltip(entry));
		}
		else if (grouping == OverallTotals.Grouping.MASTER)
		{
			addMasterLines(row, entry);
			row.setToolTipText(masterTooltip(entry));
		}
		else
		{
			addTotalLines(row, entry);
			row.setToolTipText(tooltip(entry));
		}
		for (Component line : row.getComponents())
		{
			((JComponent) line).setAlignmentX(Component.LEFT_ALIGNMENT);
		}
		return row;
	}

	/**
	 * By monster's group name: the icon of the group's task ({@link OverallTotals.Entry#getTask}, so a Catacombs
	 * variant or cannon group shows its task's), or an empty slot of the same size, before the name. The name asks for
	 * no width of its own, so the icon can't widen the sidebar: a long name is cut short (the row's tooltip has it).
	 */
	private void taskIcon(JLabel name, String task)
	{
		// A name for tests, which read the icon from it; no visible effect.
		name.setName("totals.name");
		SmallItemIcon.applyTask(icons, name, task);
	}

	/**
	 * By master's group name: the master's face ({@link MasterIcons}), or an empty slot of the same size for an
	 * unknown master or a missing image, before the name, which asks for no width of its own, as {@link #taskIcon}.
	 */
	private void masterIcon(JLabel name, String master)
	{
		// A name for tests, which read the icon from it; no visible effect.
		name.setName("totals.name");
		name.setIconTextGap(4);
		name.setIcon(masterIcons.icon(master));
		name.setPreferredSize(new Dimension(0, name.getPreferredSize().height));
	}

	/** Overall and By month: active time, profit and XP of all skills, each value with its own sample count. */
	private void addTotalLines(JPanel row, OverallTotals.Entry entry)
	{
		row.add(SlayerLedgerPanel.historyLine(SlayerLedgerPanel.historyLabel("Active:", ColorScheme.LIGHT_GRAY_COLOR),
			SlayerLedgerPanel.historyLabel(LedgerHistory.formatDuration(entry.getActiveMillis()),
				ColorScheme.LIGHT_GRAY_COLOR)));
		// "Profit (GE):" / "Profit (HA):" with the Loot price mode; amounts without " gp", as history rows.
		String profit = LedgerHistory.formatAmount(entry.getProfit(), entry.isProfitApproximate())
			+ " (n=" + entry.getProfitSampleCount() + ")";
		row.add(SlayerLedgerPanel.historyLine(SlayerLedgerPanel.historyLabel(
			LedgerHistory.profitLabel(model.lootPriceMode()).trim(), ColorScheme.LIGHT_GRAY_COLOR),
			SlayerLedgerPanel.historyLabel(profit, SlayerLedgerPanel.profitColor(entry.getProfit()))));
		row.add(SlayerLedgerPanel.historyLine(SlayerLedgerPanel.historyLabel(XP_LABEL + ":",
			ColorScheme.LIGHT_GRAY_COLOR),
			SlayerLedgerPanel.historyLabel(LedgerHistory.formatAmount(entry.getXp(), false) + " (n="
				+ entry.getXpSampleCount() + ")", ColorScheme.LIGHT_GRAY_COLOR)));
	}

	/**
	 * By monster: tasks done, kills, average task length, average task profit, profit per kill, time per kill,
	 * profit per hour, then Slayer XP and Slayer XP per hour. The totals show their sample count as {@code (n=...)};
	 * the averages use those tasks and the rates those of them with active time, so they don't repeat it.
	 */
	private void addMonsterLines(JPanel row, OverallTotals.Entry entry)
	{
		String profit = LedgerHistory.profitName(model.lootPriceMode());
		addLine(row, "Tasks done:", LedgerHistory.formatAmount((long) entry.getTaskCount(), false),
			ColorScheme.LIGHT_GRAY_COLOR);
		addLine(row, "Killed:", LedgerHistory.formatAmount(entry.getKills(), false), ColorScheme.LIGHT_GRAY_COLOR);
		addLine(row, "Avg task:", LedgerHistory.formatDuration(entry.getAverageActiveMillis()),
			ColorScheme.LIGHT_GRAY_COLOR);
		addProfitLine(row, "Avg " + lowerFirst(profit) + ":", entry.getAverageProfit(), entry.isProfitApproximate(),
			entry.getProfitSampleCount());
		addProfitLine(row, profit + "/kill:", entry.getProfitPerKill(), entry.isProfitRateApproximate(), null);
		addLine(row, "Time/kill:", entry.getMillisPerKill() == null ? LedgerHistory.NO_VALUE
			: LedgerHistory.formatKillTime(entry.getMillisPerKill()), ColorScheme.LIGHT_GRAY_COLOR);
		addProfitLine(row, profit + "/h:", entry.getProfitPerHour(), entry.isProfitRateApproximate(), null);
		addCountLine(row, "Slayer XP:", entry.getSlayerXp(), entry.getSlayerXpSampleCount());
		addLine(row, "Slayer XP/h:", LedgerHistory.formatAmount(entry.getSlayerXpPerHour(), false),
			ColorScheme.LIGHT_GRAY_COLOR);
	}

	/**
	 * By master: total tasks, average task length, average task profit, total profit, profit per hour, Slayer
	 * points (total and per task, the average without bonus tasks) and Slayer XP (total and per task). The monsters
	 * assigned are a list below them ({@link #assignmentList}).
	 */
	private void addMasterLines(JPanel row, OverallTotals.Entry entry)
	{
		String profit = LedgerHistory.profitName(model.lootPriceMode());
		addLine(row, "Total tasks:", LedgerHistory.formatAmount((long) entry.getTaskCount(), false),
			ColorScheme.LIGHT_GRAY_COLOR);
		addLine(row, "Avg task:", LedgerHistory.formatDuration(entry.getAverageActiveMillis()),
			ColorScheme.LIGHT_GRAY_COLOR);
		addProfitLine(row, "Avg " + lowerFirst(profit) + ":", entry.getAverageProfit(), entry.isProfitApproximate(),
			null);
		addProfitLine(row, profit + ":", entry.getProfit(), entry.isProfitApproximate(), entry.getProfitSampleCount());
		addProfitLine(row, profit + "/h:", entry.getProfitPerHour(), entry.isProfitRateApproximate(), null);
		addCountLine(row, "Points:", entry.getPoints(), entry.getPointsSampleCount());
		addLine(row, "Avg points:", LedgerHistory.formatAmount(entry.getAveragePoints(), false),
			ColorScheme.LIGHT_GRAY_COLOR);
		addCountLine(row, "Slayer XP:", entry.getSlayerXp(), entry.getSlayerXpSampleCount());
		addLine(row, "Avg Slayer XP:", LedgerHistory.formatAmount(entry.getAverageSlayerXp(), false),
			ColorScheme.LIGHT_GRAY_COLOR);
	}

	/** "Profit (GE)" as "profit (GE)", after "Avg". */
	private static String lowerFirst(String text)
	{
		return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
	}

	/** A two-column line: {@code left} in grey, {@code right} in {@code color}. */
	private static void addLine(JPanel row, String left, String right, Color color)
	{
		row.add(SlayerLedgerPanel.historyLine(SlayerLedgerPanel.historyLabel(left, ColorScheme.LIGHT_GRAY_COLOR),
			SlayerLedgerPanel.historyLabel(right, color)));
	}

	/**
	 * A profit amount without " gp", with {@code ~} when approximate, coloured as a profit, and {@code (n=...)} when
	 * {@code samples} is given.
	 */
	private static void addProfitLine(JPanel row, String left, Long value, boolean approximate, Integer samples)
	{
		addLine(row, left, LedgerHistory.formatAmount(value, approximate)
			+ (samples == null ? "" : " (n=" + samples + ")"), SlayerLedgerPanel.profitColor(value));
	}

	/** A total with its sample count: {@code 12.3K (n=12)}, or {@code - (n=0)}. */
	private static void addCountLine(JPanel row, String left, Long value, int samples)
	{
		addLine(row, left, LedgerHistory.formatAmount(value, false) + " (n=" + samples + ")",
			ColorScheme.LIGHT_GRAY_COLOR);
	}

	/** Exact totals, the price source and what each sample count means. */
	private String tooltip(OverallTotals.Entry entry)
	{
		LootPriceMode mode = model.lootPriceMode();
		StringBuilder sb = new StringBuilder("<html>").append(LedgerHistory.escapeHtml(entry.getLabel()));
		int tasks = entry.getTaskCount();
		sb.append("<br>").append(tasks).append(tasks == 1 ? " completed task" : " completed tasks")
			.append(", active ").append(LedgerHistory.formatDuration(entry.getActiveMillis()));

		Long profit = entry.getProfit();
		sb.append("<br>").append(LedgerHistory.escapeHtml(LedgerHistory.profitLabel(mode)))
			.append(profit == null ? "unknown" : (entry.isProfitApproximate() ? LedgerHistory.APPROXIMATE : "")
				+ LedgerHistory.escapeHtml(LedgerHistory.formatGpExact(profit)));
		String source = "Loot at " + mode + " prices minus GE-valued supplies"
			+ (mode == LootPriceMode.HIGH_ALCH
			? " (today's available GE prices for GE-priced items)"
			: " (today's available GE prices)");
		sb.append("<br>").append(LedgerHistory.escapeHtml(source));
		sb.append("<br>n = ").append(SlayerLedgerPanel.sampleTasks(entry.getProfitSampleCount()))
			.append(" with loot and supply values");

		Long xp = entry.getXp();
		sb.append("<br>").append(XP_LABEL).append(": ")
			.append(xp == null ? "unknown" : String.format("%,d", xp));
		sb.append("<br>n = ").append(SlayerLedgerPanel.sampleTasks(entry.getXpSampleCount())).append(" with recorded XP");
		if (entry.isProfitApproximate())
		{
			sb.append("<br>~ means some item prices were unavailable, or an older task kept its saved totals.");
		}
		return sb.append("</html>").toString();
	}

	/** By monster's tooltip: the exact figures, the price source and how the rates are worked out. */
	private String monsterTooltip(OverallTotals.Entry entry)
	{
		LootPriceMode mode = model.lootPriceMode();
		String profit = LedgerHistory.profitName(mode);
		StringBuilder sb = groupTooltipStart(entry);
		sb.append("<br>Killed: ").append(String.format("%,d", entry.getKills()));
		sb.append("<br>Average task: ").append(LedgerHistory.formatDuration(entry.getAverageActiveMillis()));
		sb.append("<br>Average ").append(lowerFirst(profit)).append(": ")
			.append(exactGp(entry.getAverageProfit(), entry.isProfitApproximate()));
		sb.append("<br>").append(profit).append(" per kill: ")
			.append(exactGp(entry.getProfitPerKill(), entry.isProfitRateApproximate()));
		sb.append("<br>Time per kill: ").append(entry.getMillisPerKill() == null ? LedgerHistory.NO_VALUE
			: LedgerHistory.formatKillTime(entry.getMillisPerKill()));
		sb.append("<br>").append(profit).append(" per hour: ")
			.append(exactGp(entry.getProfitPerHour(), entry.isProfitRateApproximate()));
		sb.append("<br>Slayer XP: ").append(exact(entry.getSlayerXp()))
			.append(", per hour: ").append(exact(entry.getSlayerXpPerHour()));
		appendNotes(sb, entry, mode);
		return sb.append("</html>").toString();
	}

	/** By master's tooltip: the exact figures, the price source and what the sample counts mean. */
	private String masterTooltip(OverallTotals.Entry entry)
	{
		LootPriceMode mode = model.lootPriceMode();
		String profit = LedgerHistory.profitName(mode);
		StringBuilder sb = groupTooltipStart(entry);
		sb.append("<br>Average task: ").append(LedgerHistory.formatDuration(entry.getAverageActiveMillis()));
		sb.append("<br>Average ").append(lowerFirst(profit)).append(": ")
			.append(exactGp(entry.getAverageProfit(), entry.isProfitApproximate()));
		sb.append("<br>").append(profit).append(": ").append(exactGp(entry.getProfit(), entry.isProfitApproximate()));
		sb.append("<br>").append(profit).append(" per hour: ")
			.append(exactGp(entry.getProfitPerHour(), entry.isProfitRateApproximate()));
		sb.append("<br>Slayer points: ").append(exact(entry.getPoints())).append(", per task: ")
			.append(exact(entry.getAveragePoints()));
		sb.append("<br>n = ").append(SlayerLedgerPanel.sampleTasks(entry.getPointsSampleCount()))
			.append(" with recorded points (bonus tasks included)");
		sb.append("<br>Per task leaves out bonus tasks (every 10th in a streak): ")
			.append(SlayerLedgerPanel.sampleTasks(entry.getAveragePointsSampleCount()));
		sb.append("<br>Slayer XP: ").append(exact(entry.getSlayerXp())).append(", per task: ")
			.append(exact(entry.getAverageSlayerXp()));
		appendNotes(sb, entry, mode);
		int assigned = entry.getAssignmentTotal();
		sb.append("<br>Monsters assigned: ").append(assigned).append(assigned == 1 ? " assignment" : " assignments")
			.append(" (completed tasks and Skipped / blocked entries)");
		return sb.append("</html>").toString();
	}

	/** The group's name and task count, with the active time. */
	private static StringBuilder groupTooltipStart(OverallTotals.Entry entry)
	{
		StringBuilder sb = new StringBuilder("<html>").append(LedgerHistory.escapeHtml(entry.getLabel()));
		return sb.append("<br>").append(completedTasks(entry.getTaskCount())).append(", active ")
			.append(LedgerHistory.formatDuration(entry.getActiveMillis()));
	}

	/** {@code 12 completed tasks}. */
	private static String completedTasks(int tasks)
	{
		return tasks + (tasks == 1 ? " completed task" : " completed tasks");
	}

	/** The price source, the sample counts, the rate rules and the {@code ~} note. */
	private static void appendNotes(StringBuilder sb, OverallTotals.Entry entry, LootPriceMode mode)
	{
		sb.append("<br>").append(LedgerHistory.escapeHtml("Loot at " + mode + " prices minus GE-valued supplies"
			+ (mode == LootPriceMode.HIGH_ALCH
			? " (today's available GE prices for GE-priced items)"
			: " (today's available GE prices)")));
		sb.append("<br>n = ").append(SlayerLedgerPanel.sampleTasks(entry.getProfitSampleCount()))
			.append(" with loot and supply values, ").append(entry.getSlayerXpSampleCount())
			.append(" with recorded XP");
		sb.append("<br>Averages use those tasks; rates pool those of them with active time, as Trends does,")
			.append(" and hourly rates need 1 minute of it.");
		if (entry.isProfitApproximate())
		{
			sb.append("<br>~ means some item prices were unavailable, or an older task kept its saved totals.");
		}
	}

	/** An exact gp amount for a tooltip, {@code ~} when approximate, or "-". */
	private static String exactGp(Long value, boolean approximate)
	{
		return value == null ? LedgerHistory.NO_VALUE : (approximate ? LedgerHistory.APPROXIMATE : "")
			+ LedgerHistory.escapeHtml(LedgerHistory.formatGpExact(value));
	}

	/** An exact count for a tooltip, or "-". */
	private static String exact(Long value)
	{
		return value == null ? LedgerHistory.NO_VALUE : String.format("%,d", value);
	}

	/** How many times the rows were built. Performance seam: tests assert this doesn't grow on the refresh timer. */
	int getBuildCount()
	{
		return builds;
	}

	/**
	 * One line of a By master group's monsters assigned ({@link OverallTotals.Assignment}); equal when its assignment
	 * and master are, so an unchanged list is not redrawn.
	 */
	private static final class AssignmentLine implements ItemListView.Line
	{
		private final OverallTotals.Assignment assignment;
		private final String master;

		private AssignmentLine(OverallTotals.Assignment assignment, String master)
		{
			this.assignment = assignment;
			this.master = master;
		}

		@Override
		public String getName()
		{
			return assignment.getName();
		}

		@Override
		public String getSkill()
		{
			return null;
		}

		/** The task's icon, or {@code null} for a name that is no known task (an empty slot). */
		@Override
		public Integer getIconItemId()
		{
			return TaskIcons.itemId(assignment.getName());
		}

		@Override
		public String amountText()
		{
			return assignment.text();
		}

		@Override
		public String tooltip()
		{
			return assignment.tooltip(master);
		}

		@Override
		public boolean equals(Object other)
		{
			if (!(other instanceof AssignmentLine))
			{
				return false;
			}
			AssignmentLine that = (AssignmentLine) other;
			return assignment.equals(that.assignment) && master.equals(that.master);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(assignment, master);
		}
	}

	/** One item grid and the unnamed rows it shows. */
	private final class Grid
	{
		private final ItemGridView view;
		/** Whether the rows are loot, which can have the alch hint. */
		private final boolean loot;
		/** Unnamed rows ({@link OverallTotals.Entry#getSupplies()} or {@link OverallTotals.Entry#getLoot()}). */
		private List<ItemDetails.Row> rows;

		private Grid(ItemGridView view, boolean loot)
		{
			this.view = view;
			this.loot = loot;
		}

		/**
		 * Names the rows with what the model knows and shows them, loot without the items Ground Items hides;
		 * nothing is redrawn when they are unchanged.
		 */
		private void show()
		{
			view.setRows(ItemDetails.named(rows, model::itemName, model.natureRunePrice(), loot),
				loot ? model.hiddenLoot() : HiddenLoot.NONE);
		}
	}
}
