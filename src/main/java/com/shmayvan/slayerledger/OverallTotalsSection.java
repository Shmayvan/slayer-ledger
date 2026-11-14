package com.shmayvan.slayerledger;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
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
import javax.swing.SwingConstants;

/**
 * The collapsed "Overall totals" section of {@link SlayerLedgerPanel}: completed-task totals, overall or grouped
 * by Slayer master, month or monster ({@link OverallTotals}, worked out by the history model). The rows are built only
 * while the section is open and its "Overall totals" setting shows it, and never by the panel's refresh timer.
 * Overall and By month show the active time, profit and XP of all skills; By monster and By master show
 * averages and rates instead, and By master its monsters assigned ({@link OverallTotals.Assignment}) in a text list
 * with "+N more" ({@link ItemListView}).
 *
 * <p>Look A (DESIGN_PRINCIPLES.md 3.3, 3.8): flat on the page under its section header, no cards. Each
 * group is a block, and a grouping's blocks are small multiples: the same rows in the same order and units, so the eye
 * compares values, not layouts. A block's title is the group's name in Bold in {@link LedgerTheme#HEADING} with its
 * sample size on the right ({@code 3 tasks}). Each row is a label in Small in {@link LedgerTheme#LABEL}, with the unit
 * in it ({@code Profit (GE)/h}), and its value right-aligned in Plain, every amount through {@link LedgerFormat} (one
 * rule per row, so a row has the same precision in every block), profit signed and coloured. A total that only some
 * of the group's tasks recorded says how many beside its label, in {@link LedgerTheme#HINT} ({@code 2 tasks}).</p>
 *
 * <p>By monster's group names and By master's monsters assigned show the task's icon before the name
 * ({@link TaskIcons}), the item sprite at the XP lists' small icon size ({@link SmallItemIcon}), asked for on the EDT
 * only when the rows are built (open and shown, never by the refresh timer); a task without a known icon keeps an
 * empty slot of the same size.</p>
 *
 * <p>By master's group names show the master's face ({@link MasterIcons}), the bundled chathead image drawn in
 * the same slot, read the first time it is shown; a missing image or an unknown master keeps the empty slot.</p>
 *
 * <p>Each group has two item grids, "Supplies used" and "Loot received", under nested sub-headers, quieter
 * than the block's title ({@link TitledListView#useNestedSubHeader}), closed until clicked. Its items are summed off
 * the EDT with the totals ({@link OverallTotals.Entry#getSupplies()}); here they are only named
 * ({@link ItemDetails#named}, with the names the model's {@link NameBook} has, asked for here) and shown. A grid stays
 * open or closed while the section rebuilds the same group in the same grouping; closing the section or changing the
 * grouping closes them again. EDT only.</p>
 */
final class OverallTotalsSection
{
	/** Shown instead of the blocks with no completed task (DESIGN_PRINCIPLES.md 3.9: plain, one line). */
	static final String EMPTY_TEXT = "No completed tasks yet.";
	/** XP is every skill's (owner decision), unlike the Slayer XP/h of {@link TaskRanking}'s rates. */
	private static final String XP_LABEL = "XP (all skills)";
	private static final String SUPPLIES_TITLE = "Supplies used";
	private static final String LOOT_TITLE = "Loot received";
	private static final String ASSIGNMENTS_TITLE = "Monsters assigned";
	/** Space between two groups' blocks (DESIGN_PRINCIPLES.md 3.3: flat rows apart by 4 to 8 px). */
	private static final int BLOCK_GAP = 8;
	/** Space above each list under a block's rows: the monsters assigned and the two grids. */
	private static final int LIST_GAP = 4;
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
		// A section header (DESIGN_PRINCIPLES.md 3.3).
		LedgerTheme.styleSectionHeader(header, title, toggle);
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
		body.setBackground(LedgerTheme.PAGE);
		JPanel groupingRow = new JPanel(new BorderLayout(6, 0));
		groupingRow.setBackground(LedgerTheme.PAGE);
		JLabel groupingLabel = LedgerTheme.label("Group", LedgerTheme.smallFont(), LedgerTheme.LABEL);
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
		list.setBackground(LedgerTheme.PAGE);
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
			JLabel empty = LedgerTheme.label(EMPTY_TEXT, LedgerTheme.smallFont(), LedgerTheme.LABEL);
			// A name for tests; no visible effect.
			empty.setName("totals.empty");
			empty.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
			empty.setAlignmentX(Component.LEFT_ALIGNMENT);
			list.add(empty);
		}
		else
		{
			for (int i = 0; i < entries.size(); i++)
			{
				OverallTotals.Entry entry = entries.get(i);
				String key = selected + "|" + entry.getLabel();
				JPanel block = block(entry, selected);
				JPanel items = items(entry, key, kept, ids);
				if (selected == OverallTotals.Grouping.MASTER)
				{
					// The monsters assigned come first, above the item grids.
					items.add(assignmentList(entry, key, keptLists), 0);
				}
				block.add(items);
				list.add(block);
				if (i + 1 < entries.size())
				{
					list.add(Box.createVerticalStrut(BLOCK_GAP));
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
	 * A group's two item grids, below its rows: the grids already shown for {@code key} if any, so an open one stays
	 * open, else new closed ones. They go into {@code kept}, and their item ids into {@code ids}.
	 */
	private JPanel items(OverallTotals.Entry entry, String key, Map<String, Grid> kept, Set<Integer> ids)
	{
		JPanel items = new JPanel();
		// A name for tests, which read the rows above it apart from it; no visible effect.
		items.setName("totals.items");
		items.setLayout(new BoxLayout(items, BoxLayout.Y_AXIS));
		items.setOpaque(false);
		items.setAlignmentX(Component.LEFT_ALIGNMENT);
		items.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
		items.add(grid(key + "|supplies", "totals.supplies", SUPPLIES_TITLE, entry.getSupplies(), false, kept));
		items.add(grid(key + "|loot", "totals.loot", LOOT_TITLE, entry.getLoot(), true, kept));
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
	 * {@link ItemDetails#MAX_ROWS} until "+N more" is clicked, under a nested sub-header like the grids'. The list
	 * shown for {@code key} is kept, so its "+N more" choice stays while the same group rebuilds; it goes into
	 * {@code kept}.
	 */
	private ItemListView assignmentList(OverallTotals.Entry entry, String key, Map<String, ItemListView> kept)
	{
		ItemListView view = assignmentLists.get(key);
		if (view == null)
		{
			view = new ItemListView(ASSIGNMENTS_TITLE, false, LedgerTheme.PAGE, "monsters", NO_ICONS, icons);
			// A name for tests, which find the list by it; no visible effect.
			view.setName("totals.assignments");
			// Its lines count assignments, so the title counts nothing (only hidden rows, which it never has); quieter
			// than the block's title above it.
			view.useNestedSubHeader();
			view.setBorder(BorderFactory.createEmptyBorder(LIST_GAP, 0, 0, 0));
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

	/**
	 * The grid for {@code key}, kept from the last build or new and closed, showing {@code rows}: a nested sub-header
	 * with Show / Hide, and the live grids' stone cells on the page.
	 */
	private ItemGridView grid(String key, String name, String title, List<ItemDetails.Row> rows, boolean loot,
		Map<String, Grid> kept)
	{
		Grid grid = grids.get(key);
		if (grid == null)
		{
			ItemGridView view = new ItemGridView(title, true, LedgerTheme.PAGE, icons);
			// A name for tests, which find the grids by it; no visible effect.
			view.setName(name);
			view.useNestedSubHeader();
			view.setBorder(BorderFactory.createEmptyBorder(LIST_GAP, 0, 0, 0));
			view.setAlignmentX(Component.LEFT_ALIGNMENT);
			grid = new Grid(view, loot);
		}
		grid.rows = rows;
		grid.show();
		kept.put(key, grid);
		return grid.view;
	}

	/**
	 * One group's block: its title line (the name and the sample size), then the grouping's rows
	 * ({@link #addTotalLines}, {@link #addMonsterLines}, {@link #addMasterLines}), every block of a grouping with the
	 * same rows; the item lists are added below them. Its tooltip has the exact figures.
	 */
	private JPanel block(OverallTotals.Entry entry, OverallTotals.Grouping grouping)
	{
		JPanel block = new JPanel();
		block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
		block.setOpaque(false);
		block.setAlignmentX(Component.LEFT_ALIGNMENT);
		block.add(titleLine(entry, grouping));
		if (grouping == OverallTotals.Grouping.MONSTER)
		{
			addMonsterLines(block, entry);
			block.setToolTipText(monsterTooltip(entry));
		}
		else if (grouping == OverallTotals.Grouping.MASTER)
		{
			addMasterLines(block, entry);
			block.setToolTipText(masterTooltip(entry));
		}
		else
		{
			addTotalLines(block, entry);
			block.setToolTipText(tooltip(entry));
		}
		for (Component line : block.getComponents())
		{
			((JComponent) line).setAlignmentX(Component.LEFT_ALIGNMENT);
		}
		return block;
	}

	/**
	 * A block's title line: the group's name in Bold in the heading colour, cut short with {@code …} when it doesn't fit
	 * ({@link FitLabel}; the block's tooltip has it whole), with By monster's task icon or By master's face before it;
	 * on the right the sample size, {@code 3 tasks}, whose tooltip says they are completed tasks.
	 */
	private JPanel titleLine(OverallTotals.Entry entry, OverallTotals.Grouping grouping)
	{
		FitLabel name = new FitLabel(entry.getLabel());
		// A name for tests, which read the icon and the text as shown from it; no visible effect.
		name.setName("totals.name");
		name.setFont(LedgerTheme.boldFont());
		name.setForeground(LedgerTheme.HEADING);
		if (grouping == OverallTotals.Grouping.MONSTER)
		{
			// The icon of the group's task (OverallTotals.Entry#getTask), so a Catacombs variant or cannon group
			// shows its task's, or an empty slot of the same size.
			SmallItemIcon.applyTask(icons, name, entry.getTask());
		}
		else if (grouping == OverallTotals.Grouping.MASTER)
		{
			// The master's face, or an empty slot of the same size for an unknown master or a missing image.
			name.setIconTextGap(4);
			name.setIcon(masterIcons.icon(entry.getLabel()));
		}
		JLabel count = LedgerTheme.label(SlayerLedgerPanel.sampleTasks(entry.getTaskCount()), LedgerTheme.smallFont(),
			LedgerTheme.LABEL);
		count.setVerticalAlignment(SwingConstants.BOTTOM);
		// Completed tasks, not By master's assignments.
		count.setToolTipText(completedTasks(entry.getTaskCount()));
		JPanel line = new JPanel(new BorderLayout(6, 0));
		line.setOpaque(false);
		line.add(name, BorderLayout.CENTER);
		line.add(count, BorderLayout.EAST);
		return line;
	}

	/** Overall and By month: active time, profit and XP of all skills. */
	private void addTotalLines(JPanel block, OverallTotals.Entry entry)
	{
		int tasks = entry.getTaskCount();
		addLine(block, "Active time", LedgerHistory.formatDuration(entry.getActiveMillis()), LedgerTheme.VALUE, null);
		// "Profit (GE)" / "Profit (HA)" with the Loot price mode.
		addProfitLine(block, LedgerHistory.profitName(model.lootPriceMode()), entry.getProfit(),
			entry.isProfitApproximate(), sample(entry.getProfitSampleCount(), tasks));
		addLine(block, XP_LABEL, LedgerFormat.amount(entry.getXp(), false), LedgerTheme.VALUE,
			sample(entry.getXpSampleCount(), tasks));
	}

	/**
	 * By monster: kills, average task length, average task profit, profit per kill, pace, profit per hour, then
	 * Slayer XP and Slayer XP per hour. The tasks done are the title's sample size. The averages use the tasks that
	 * recorded the figure and the rates those of them with active time, so only the totals say how many.
	 */
	private void addMonsterLines(JPanel block, OverallTotals.Entry entry)
	{
		int tasks = entry.getTaskCount();
		String profit = LedgerHistory.profitName(model.lootPriceMode());
		addLine(block, "Kills", LedgerFormat.kills(entry.getKills()), LedgerTheme.VALUE, null);
		addLine(block, "Avg task", LedgerHistory.formatDuration(entry.getAverageActiveMillis()), LedgerTheme.VALUE,
			null);
		addProfitLine(block, "Avg " + lowerFirst(profit), entry.getAverageProfit(), entry.isProfitApproximate(),
			sample(entry.getProfitSampleCount(), tasks));
		addProfitLine(block, profit + "/kill", entry.getProfitPerKill(), entry.isProfitRateApproximate(), null);
		addLine(block, "Pace", pace(entry.getMillisPerKill()), LedgerTheme.VALUE, null);
		addProfitLine(block, profit + "/h", entry.getProfitPerHour(), entry.isProfitRateApproximate(), null);
		addLine(block, "Slayer XP", LedgerFormat.amount(entry.getSlayerXp(), false), LedgerTheme.VALUE,
			sample(entry.getSlayerXpSampleCount(), tasks));
		addLine(block, "Slayer XP/h", LedgerFormat.amount(entry.getSlayerXpPerHour(), false), LedgerTheme.VALUE, null);
	}

	/**
	 * By master: average task length, average task profit, total profit, profit per hour, Slayer points (total
	 * and per task, the average without bonus tasks) and Slayer XP (total and per task). The tasks are the title's
	 * sample size; the monsters assigned are a list below the rows ({@link #assignmentList}).
	 */
	private void addMasterLines(JPanel block, OverallTotals.Entry entry)
	{
		int tasks = entry.getTaskCount();
		String profit = LedgerHistory.profitName(model.lootPriceMode());
		addLine(block, "Avg task", LedgerHistory.formatDuration(entry.getAverageActiveMillis()), LedgerTheme.VALUE,
			null);
		addProfitLine(block, "Avg " + lowerFirst(profit), entry.getAverageProfit(), entry.isProfitApproximate(), null);
		addProfitLine(block, profit, entry.getProfit(), entry.isProfitApproximate(),
			sample(entry.getProfitSampleCount(), tasks));
		addProfitLine(block, profit + "/h", entry.getProfitPerHour(), entry.isProfitRateApproximate(), null);
		addLine(block, "Points", LedgerFormat.amount(entry.getPoints(), false), LedgerTheme.VALUE,
			sample(entry.getPointsSampleCount(), tasks));
		addLine(block, "Avg points", LedgerFormat.amount(entry.getAveragePoints(), false), LedgerTheme.VALUE, null);
		addLine(block, "Slayer XP", LedgerFormat.amount(entry.getSlayerXp(), false), LedgerTheme.VALUE,
			sample(entry.getSlayerXpSampleCount(), tasks));
		addLine(block, "Avg Slayer XP", LedgerFormat.amount(entry.getAverageSlayerXp(), false), LedgerTheme.VALUE,
			null);
	}

	/** "Profit (GE)" as "profit (GE)", after "Avg". */
	private static String lowerFirst(String text)
	{
		return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
	}

	/** The time per kill as the live card's pace, {@code 0:18/kill}, or {@link LedgerFormat#NONE} without one. */
	private static String pace(Long millisPerKill)
	{
		return millisPerKill == null ? LedgerFormat.NONE : LedgerFormat.pace(millisPerKill);
	}

	/**
	 * The sample size of a total that only some of the group's {@code tasks} recorded, {@code 2 tasks}; {@code null}
	 * when all did, since the block's title says how many those are.
	 */
	private static String sample(int samples, int tasks)
	{
		return samples < tasks ? SlayerLedgerPanel.sampleTasks(samples) : null;
	}

	/**
	 * Adds one row to {@code block}: {@code label} in Small in the label colour on the left, then {@code sample} (if
	 * any) in the hint colour, cut short when the row has no room for it, and {@code value} right-aligned in Plain in
	 * {@code color}. Labels and values are short, so a row never widens the sidebar.
	 */
	private static void addLine(JPanel block, String label, String value, Color color, String sample)
	{
		JPanel line = new JPanel(new BorderLayout(6, 0));
		line.setOpaque(false);
		line.setBorder(BorderFactory.createEmptyBorder(1, 0, 0, 0));
		JLabel left = LedgerTheme.label(label, LedgerTheme.smallFont(), LedgerTheme.LABEL);
		left.setVerticalAlignment(SwingConstants.BOTTOM);
		line.add(left, BorderLayout.WEST);
		if (sample != null)
		{
			FitLabel samples = new FitLabel(sample);
			samples.setFont(LedgerTheme.smallFont());
			samples.setForeground(LedgerTheme.HINT);
			samples.setVerticalAlignment(SwingConstants.BOTTOM);
			line.add(samples, BorderLayout.CENTER);
		}
		JLabel right = LedgerTheme.label(value, LedgerTheme.plainFont(), color);
		right.setVerticalAlignment(SwingConstants.BOTTOM);
		line.add(right, BorderLayout.EAST);
		block.add(line);
	}

	/**
	 * A profit row: signed ({@link LedgerFormat#signed}, {@code ~} first when approximate), in the good colour for a
	 * gain and the alert colour for a loss; the sign says it too (DESIGN_PRINCIPLES.md 4.3).
	 */
	private static void addProfitLine(JPanel block, String label, Long value, boolean approximate, String sample)
	{
		addLine(block, label, LedgerFormat.signed(value, approximate), profitColor(value), sample);
	}

	/** {@link LedgerTheme#GOOD} for a gain, {@link LedgerTheme#ALERT} for a loss, the value colour otherwise. */
	private static Color profitColor(Long profit)
	{
		if (profit == null || profit == 0)
		{
			return LedgerTheme.VALUE;
		}
		return profit > 0 ? LedgerTheme.GOOD : LedgerTheme.ALERT;
	}

	/** Exact totals, the price source and how many tasks each total comes from. */
	private String tooltip(OverallTotals.Entry entry)
	{
		LootPriceMode mode = model.lootPriceMode();
		StringBuilder sb = new StringBuilder("<html>").append(LedgerHistory.escapeHtml(entry.getLabel()));
		sb.append("<br>").append(completedTasks(entry.getTaskCount())).append(", active ")
			.append(LedgerHistory.formatDuration(entry.getActiveMillis()));

		Long profit = entry.getProfit();
		sb.append("<br>").append(LedgerHistory.escapeHtml(LedgerHistory.profitLabel(mode)))
			.append(profit == null ? "unknown" : (entry.isProfitApproximate() ? LedgerHistory.APPROXIMATE : "")
				+ LedgerHistory.escapeHtml(LedgerHistory.formatGpExact(profit)));
		sb.append("<br>").append(LedgerHistory.escapeHtml(priceSource(mode)));
		sb.append("<br>Profit from ").append(SlayerLedgerPanel.sampleTasks(entry.getProfitSampleCount()))
			.append(" with loot and supply values");

		Long xp = entry.getXp();
		sb.append("<br>").append(XP_LABEL).append(": ")
			.append(xp == null ? "unknown" : String.format("%,d", xp));
		sb.append("<br>XP from ").append(SlayerLedgerPanel.sampleTasks(entry.getXpSampleCount()))
			.append(" with recorded XP");
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
		sb.append("<br>Kills: ").append(String.format("%,d", entry.getKills()));
		sb.append("<br>Average task: ").append(LedgerHistory.formatDuration(entry.getAverageActiveMillis()));
		sb.append("<br>Average ").append(lowerFirst(profit)).append(": ")
			.append(exactGp(entry.getAverageProfit(), entry.isProfitApproximate()));
		sb.append("<br>").append(profit).append(" per kill: ")
			.append(exactGp(entry.getProfitPerKill(), entry.isProfitRateApproximate()));
		sb.append("<br>Pace: ").append(pace(entry.getMillisPerKill()));
		sb.append("<br>").append(profit).append(" per hour: ")
			.append(exactGp(entry.getProfitPerHour(), entry.isProfitRateApproximate()));
		sb.append("<br>Slayer XP: ").append(exact(entry.getSlayerXp()))
			.append(", per hour: ").append(exact(entry.getSlayerXpPerHour()));
		appendNotes(sb, entry, mode);
		return sb.append("</html>").toString();
	}

	/** By master's tooltip: the exact figures, the price source and how many tasks each total comes from. */
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
		sb.append("<br>Points from ").append(SlayerLedgerPanel.sampleTasks(entry.getPointsSampleCount()))
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

	/** Where a profit's prices come from, in the Loot price {@code mode}. */
	private static String priceSource(LootPriceMode mode)
	{
		return "Loot at " + mode + " prices minus GE-valued supplies" + (mode == LootPriceMode.HIGH_ALCH
			? " (today's available GE prices for GE-priced items)"
			: " (today's available GE prices)");
	}

	/** The price source, how many tasks the totals come from, the rate rules and the {@code ~} note. */
	private static void appendNotes(StringBuilder sb, OverallTotals.Entry entry, LootPriceMode mode)
	{
		sb.append("<br>").append(LedgerHistory.escapeHtml(priceSource(mode)));
		sb.append("<br>Profit from ").append(SlayerLedgerPanel.sampleTasks(entry.getProfitSampleCount()))
			.append(" with loot and supply values, Slayer XP from ")
			.append(SlayerLedgerPanel.sampleTasks(entry.getSlayerXpSampleCount())).append(" with recorded XP");
		sb.append("<br>Averages use those tasks; rates pool those of them with active time,")
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
