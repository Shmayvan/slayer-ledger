package com.shmayvan.slayerledger;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import lombok.*;
import net.runelite.client.ui.*;

/**
 * The advisor card of {@link SlayerLedgerPanel} (V2 plan 6 and 8, DESIGN_PRINCIPLES.md 3.5): the second
 * raised card, under the live section, shown while a task is assigned and the account's history is loaded. It shows
 * the model's plan to show ({@link LedgerHistoryModel#plan}) and works nothing out itself. Top to bottom:
 * <ol>
 * <li>the title, what the plan is based on ({@code Based on 6 Dust devils tasks}), on at most two lines;</li>
 * <li>the note, on parchment: a Konar task's note when its area has no task yet; the recommendation, the fastest
 * setup (name, on at most two lines, and sample size, {@code based on 1 task} for a thin recommendation; its time for
 * the kills left, and a Bring grid of at most 8 items with {@code +N more}, whose tooltip lists the rest); the All
 * setups line.</li>
 * </ol>
 * Without history the card has one line ({@link TaskPlanText#noHistoryLine}). Bundle C (owner decision
 * 2026-11-16) keeps the card as it is through the task: it no longer collapses after the first kill. Every word comes
 * from {@link TaskPlanText} and every number from {@link LedgerFormat}.
 *
 * <p>It is rebuilt only when the plan shown changes. The panel's refresh ({@link #refresh}, also run by its timer)
 * only reads the tracker for the task and never asks the model for the plan; the model's changes
 * ({@link #modelChanged}) read the plan. While the plan for the current inputs is worked out
 * ({@link LedgerHistoryModel#plan} is {@code null}, as after a kill) the card keeps the plan it shows, as long as the
 * plan is the current assignment's ({@link TaskPlan#isFor}: creature, name, task type and assigned area). A reading
 * of no task only hides the card: the count can read 0 for a tick or two
 * ({@link SessionTracker#MAX_TRANSIENT_ZERO_OBSERVATIONS}), and when the same assignment is back no model change says
 * so, since its plan is the same. Nothing in it asks for width: long names are {@link FitLabel}s, and the title,
 * option names and sentences are broken into lines at the card's fixed width. EDT only.</p>
 */
final class AdvisorCardSection
{
	/** Space between the card's parts. */
	private static final int GAP = 6;
	/** Space inside the note's 1 px edge: the card's 199 px less 2 x 6 leaves 187, room for a 184 px grid row. */
	private static final int NOTE_PADDING = 5;
	/**
	 * The width a sentence on the card wraps at: the sidebar's fixed content width less the card's frame, 199 px
	 * (DESIGN_PRINCIPLES.md 3.1).
	 */
	private static final int CARD_WRAP_WIDTH = PluginPanel.PANEL_WIDTH - 2 * PluginPanel.BORDER_OFFSET
		- 2 * LedgerTheme.CARD_PADDING;
	/** The width a sentence on the note wraps at: the card's less the note's edge and padding, 187 px. */
	private static final int NOTE_WRAP_WIDTH = CARD_WRAP_WIDTH - 2 * (1 + NOTE_PADDING);
	/**
	 * The lines the title and an option's name may take; past them the last is cut short with {@code …}, the whole
	 * text in the tooltip.
	 */
	private static final int MAX_NAME_LINES = 2;
	/** The Bring grids' title. */
	static final String BRING_TITLE = "Bring";

	/**
	 * One of the note's Bring grids and the items it shows, with its {@code +N more} label and the items that lists,
	 * so new item names only rename them.
	 */
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	private static final class BringGrid
	{
		private final ItemGridView grid;
		private final List<TaskPlan.BringItem> items;
		/** {@code +N more}, or {@code null} when the grid shows every item. */
		private final JLabel more;
		private final List<TaskPlan.BringItem> rest;
	}

	private final LedgerHistoryModel model;
	private final SessionTracker tracker;
	/** Revalidates and repaints the panel after a change. */
	private final Runnable relayout;
	/** Draws the Bring grids' icons. */
	private final ItemIcons icons;

	/** The card's row of the panel, with the gap above it: hidden as one, so a hidden card leaves no gap. */
	private final JPanel block = new JPanel(new BorderLayout());
	/** The raised card: the title and the note. */
	private final JPanel card = new JPanel();
	/** {@code Based on 6 Dust devils tasks}, one label a line. */
	private final JPanel title = column("advisor.title");
	/** The note's Bring grids as last built. */
	private final List<BringGrid> bringGrids = new ArrayList<>();

	/**
	 * The plan in the card, or {@code null} for none; kept, hidden, while no task is read, for the same assignment
	 * coming back.
	 */
	private TaskPlan shownPlan;
	/** The spot and option names shown, so a region's new name rebuilds the card only when it changes one. */
	private List<String> shownNames = Collections.emptyList();
	/** How many times the card's content was built ({@link #getBuildCount}). */
	private int builds;

	/**
	 * @param icons draws the Bring grids' item icons
	 */
	AdvisorCardSection(LedgerHistoryModel model, SessionTracker tracker, Runnable relayout, ItemIcons icons)
	{
		this.model = model;
		this.tracker = tracker;
		this.relayout = relayout;
		this.icons = icons;
		// Names for tests, which find the parts by them; no visible effect.
		block.setName("advisor");
		card.setName("advisorCard");
	}

	/** Adds the card's row to {@code content}, hidden until there is a plan to show. */
	void addTo(JPanel content)
	{
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		LedgerTheme.styleCard(card);
		block.setOpaque(false);
		block.add(card, BorderLayout.CENTER);
		block.setVisible(false);
		// Often hidden: it carries its own gap, so it leaves none then.
		SlayerLedgerPanel.addHideableRow(content, block);
	}

	/**
	 * The panel's refresh (its timer, a panel action, new live values) and the model's
	 * {@link LedgerHistoryModel.Change#LIVE} (the live inputs changed, so a task read with the panel hidden is seen):
	 * hides the card without a task, and shows it again with the plan it kept when the same assignment is back. Reads
	 * nothing of the model but whether its history is loaded, and rebuilds only when the plan it keeps is not the
	 * current assignment's.
	 */
	void refresh()
	{
		update(false);
	}

	/** A change of the history model: reads the plan to show again, and rebuilds if it changed. */
	void modelChanged()
	{
		update(true);
	}

	/**
	 * Item names arrived: renames the Bring grids' items (each grid redraws only if a name changed) and the items their
	 * {@code +N more} lists.
	 */
	void itemNamesChanged()
	{
		for (BringGrid bring : bringGrids)
		{
			bring.grid.setRows(bringRows(bring.items));
			if (bring.more != null)
			{
				bring.more.setToolTipText(bringMoreTooltip(bring.rest));
			}
		}
	}

	/** Region names arrived or were forgotten: rebuilds the card if a spot it names is called something else now. */
	void locationNamesChanged()
	{
		if (shownPlan != null && !names(shownPlan).equals(shownNames))
		{
			rebuild(shownPlan);
		}
	}

	/**
	 * Shows what the card should show now. With {@code readModel} the plan to show is read from the model (on its
	 * changes); without, only the tracker is (on the panel's refresh), and the plan shown is kept while it is the
	 * current assignment's.
	 */
	private void update(boolean readModel)
	{
		if (!model.isLoaded())
		{
			// Logged out, loading or another account's history: nothing of the last assignment is kept.
			show(null);
			return;
		}
		if (!tracker.isTaskObserved() || tracker.getCurrentRemaining() <= 0)
		{
			// No task, or the count reading 0 for a tick or two: only hidden. The plan stays for the same assignment,
			// whose return no model change announces (its plan is the same).
			setShown(false);
			return;
		}
		int creatureId = tracker.getCurrentCreatureId();
		String monster = tracker.getCurrentMonster();
		TaskContext context = tracker.getCurrentContext();
		TaskPlan plan = shownPlan;
		if (readModel)
		{
			// Null while the plan for the current inputs is worked out (a kill): the plan shown stays until it is back.
			TaskPlan current = model.plan();
			plan = current != null ? current : plan;
		}
		if (plan != null && !plan.isFor(creatureId, monster, TaskType.of(context.getMaster(), creatureId),
			context.getLocation()))
		{
			// Another assignment's plan (another creature, task type or Konar area): hidden until this one's is ready.
			plan = null;
		}
		show(plan);
	}

	/** Rebuilds the card if the plan it should show changed, and shows it while it has a plan. */
	private void show(TaskPlan plan)
	{
		if (plan != shownPlan)
		{
			rebuild(plan);
		}
		setShown(plan != null);
	}

	/** Shows or hides the card's row, with the gap above it. */
	private void setShown(boolean shown)
	{
		if (block.isVisible() != shown)
		{
			block.setVisible(shown);
			relayout.run();
		}
	}

	/** Builds the card for {@code plan}, or empties it for none. */
	private void rebuild(TaskPlan plan)
	{
		shownPlan = plan;
		card.removeAll();
		bringGrids.clear();
		shownNames = Collections.emptyList();
		if (plan != null)
		{
			builds++;
			requestRegionNames(plan);
			shownNames = names(plan);
			build(plan);
		}
		card.revalidate();
		card.repaint();
		relayout.run();
	}

	/** The card's content: with history the title and the note, else the one line. */
	private void build(TaskPlan plan)
	{
		String task = taskName(plan);
		if (!plan.hasHistory())
		{
			card.add(wrapped("advisor.noHistory", TaskPlanText.noHistoryLine(task, plan), LedgerTheme.LABEL,
				CARD_WRAP_WIDTH));
			return;
		}
		String text = TaskPlanText.cardTitle(task, plan.getAllSetups().getTasks());
		title.removeAll();
		FontMetrics bold = title.getFontMetrics(LedgerTheme.boldFont());
		for (String line : TaskPlanText.wrap(text, CARD_WRAP_WIDTH, CARD_WRAP_WIDTH, MAX_NAME_LINES, bold::stringWidth))
		{
			title.add(fitLine("advisor.title.line", line, LedgerTheme.boldFont(), LedgerTheme.HEADING));
		}
		title.setToolTipText(html(Collections.singletonList(text)));
		card.add(title);
		card.add(gapped(buildNote(plan, task), GAP));
	}

	/** The parchment note: the Konar note, the recommendation and All setups. */
	private JPanel buildNote(TaskPlan plan, String task)
	{
		JPanel note = column("advisor.note");
		note.setOpaque(true);
		note.setBackground(LedgerTheme.PARCHMENT);
		note.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LedgerTheme.PARCHMENT_EDGE, 1),
			BorderFactory.createEmptyBorder(NOTE_PADDING, NOTE_PADDING, NOTE_PADDING, NOTE_PADDING)));
		boolean perKill = !plan.hasKillsLeft();
		if (plan.isAreaFallback())
		{
			addPart(note, wrapped("advisor.areaNote", TaskPlanText.areaFallback(TaskPlanText.shortSpotName(
				plan.getAreaSpot(), model.spots(), regionNames())), LedgerTheme.MUTED_INK, NOTE_WRAP_WIDTH), 0);
		}
		TaskPlan.Option best = plan.getRecommendation();
		if (best == null)
		{
			JLabel unknown = smallLabel(TaskPlanText.SETUP_UNKNOWN, LedgerTheme.MUTED_INK);
			unknown.setName("advisor.setupUnknown");
			addPart(note, unknown, GAP);
		}
		else
		{
			addPart(note, optionBlock("advisor.recommendation", best, plan.isRecommendationThin(), task, perKill), GAP);
		}
		TaskPlan.Summary all = plan.getAllSetups();
		addPart(note, line("advisor.allSetups", TaskPlanText.allSetups(all), TaskPlanText.time(all.getTime(), perKill),
			LedgerTheme.INK, LedgerTheme.INK), GAP);
		return note;
	}

	/**
	 * The recommendation's block: its name and sample size ({@code based on 1 task} when {@code thin}), its time for
	 * the kills left, then with the kills left known what to bring.
	 */
	private JPanel optionBlock(String name, TaskPlan.Option option, boolean thin, String task, boolean perKill)
	{
		JPanel block = column(name);
		String optionName = TaskPlanText.optionName(option, task, model.spots(), regionNames());
		String tasks = TaskPlanText.optionTasks(option, thin);
		JPanel head = optionHead(name, optionName, tasks);
		head.setToolTipText(html(Arrays.asList(optionName, tasks)));
		addPart(block, head, 0);

		JLabel time = LedgerTheme.label(TaskPlanText.time(option.getTime(), perKill), LedgerTheme.boldFont(),
			LedgerTheme.INK);
		time.setName(name + ".time");
		addPart(block, time, 1);
		if (!perKill)
		{
			ItemGridView grid = new ItemGridView(BRING_TITLE, false, LedgerTheme.PARCHMENT, icons);
			grid.setName(name + ".bring");
			grid.useParchment(this::bringTooltip);
			grid.setAlignmentX(Component.LEFT_ALIGNMENT);
			grid.setRows(bringRows(option.getBring()));
			addPart(block, grid, 3);
			JLabel more = null;
			if (option.getBringMore() > 0)
			{
				// The grid shows the first items only: the rest are listed in the label's tooltip.
				more = smallLabel(TaskPlanText.bringMore(option.getBringMore()), LedgerTheme.MUTED_INK);
				more.setName(name + ".bringMore");
				more.setToolTipText(bringMoreTooltip(option.getBringRest()));
				addPart(block, more, 2);
			}
			bringGrids.add(new BringGrid(grid, option.getBring(), more, option.getBringRest()));
			Set<Integer> ids = new HashSet<>();
			for (TaskPlan.BringItem item : option.getBring())
			{
				ids.add(item.getItemId());
			}
			for (TaskPlan.BringItem item : option.getBringRest())
			{
				ids.add(item.getItemId());
			}
			if (!ids.isEmpty())
			{
				model.requestItemNames(ids);
			}
		}
		return block;
	}

	/**
	 * An option's name in Bold on at most two lines, the first beside its sample size {@code tasks} on the right (the
	 * room it leaves), the second the note's full width: {@code Cannon, melee, Smoke  3 tasks} / {@code Dungeon}.
	 * Past two lines the second is cut short with {@code …}. Named {@code name.head}; each name line
	 * {@code name.name}, the sample size {@code name.tasks}.
	 */
	private JPanel optionHead(String name, String optionName, String tasks)
	{
		JPanel head = column(name + ".head");
		JLabel tasksLabel = smallLabel(tasks, LedgerTheme.MUTED_INK);
		tasksLabel.setName(name + ".tasks");
		FontMetrics bold = head.getFontMetrics(LedgerTheme.boldFont());
		int firstWidth = NOTE_WRAP_WIDTH - GAP - tasksLabel.getPreferredSize().width;
		List<String> lines = TaskPlanText.wrap(optionName, firstWidth, NOTE_WRAP_WIDTH, MAX_NAME_LINES,
			bold::stringWidth);
		JPanel first = new JPanel(new BorderLayout(GAP, 0));
		first.setOpaque(false);
		first.setAlignmentX(Component.LEFT_ALIGNMENT);
		first.add(fitLine(name + ".name", lines.isEmpty() ? "" : lines.get(0), LedgerTheme.boldFont(),
			LedgerTheme.INK), BorderLayout.CENTER);
		first.add(tasksLabel, BorderLayout.EAST);
		head.add(first);
		for (String line : lines.subList(Math.min(1, lines.size()), lines.size()))
		{
			head.add(fitLine(name + ".name", line, LedgerTheme.boldFont(), LedgerTheme.INK));
		}
		return head;
	}

	/** One line of a wrapped text, named {@code name}: a {@link FitLabel}, cut short if it ever has less room. */
	private static FitLabel fitLine(String name, String text, Font font, Color color)
	{
		FitLabel label = new FitLabel(text);
		label.setName(name);
		label.setFont(font);
		label.setForeground(color);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	/** The Bring grid's rows: each item in whole items, at its GE value, named as far as names are known. */
	private List<ItemDetails.Row> bringRows(List<TaskPlan.BringItem> items)
	{
		List<ItemDetails.Row> rows = new ArrayList<>();
		for (TaskPlan.BringItem item : items)
		{
			rows.add(ItemDetails.Row.builder()
				.itemId(item.getItemId())
				.name(ItemDetails.displayName(item.getItemId(), model::itemName, false))
				.quantity(item.getQuantity())
				.unit(ItemDetails.Unit.ITEMS)
				.value(item.getValue())
				.priceName("GE")
				.build());
		}
		return rows;
	}

	/** A Bring icon's tooltip: its full name, the amount and its GE value ({@link TaskPlanText#bringTooltip}). */
	private String bringTooltip(ItemDetails.Row row)
	{
		return html(TaskPlanText.bringTooltip(row.getName(), row.getQuantity(), row.getValue()));
	}

	/**
	 * The {@code +N more} label's tooltip: each of {@code rest}, the items past the grid's, on its own line, worded as
	 * the icons' tooltips ({@link TaskPlanText#bringMoreLine}) and named as the grid names its items.
	 */
	private String bringMoreTooltip(List<TaskPlan.BringItem> rest)
	{
		List<String> lines = new ArrayList<>();
		for (ItemDetails.Row row : bringRows(rest))
		{
			lines.add(TaskPlanText.bringMoreLine(row.getName(), row.getQuantity(), row.getValue()));
		}
		return html(lines);
	}

	/** Asks for the player's names of the unlisted regions the plan's spots are in, so they are named once known. */
	private void requestRegionNames(TaskPlan plan)
	{
		Set<Integer> regions = new HashSet<>();
		for (String spot : spotKeys(plan))
		{
			Integer region = SlayerSpots.keyRegion(spot);
			if (region != null)
			{
				regions.add(region);
			}
		}
		if (!regions.isEmpty())
		{
			model.requestLocationNames(regions);
		}
	}

	/** The names of the plan's spots as shown now, which a region's name may change. */
	private List<String> names(TaskPlan plan)
	{
		List<String> names = new ArrayList<>();
		for (String spot : spotKeys(plan))
		{
			names.add(TaskPlanText.shortSpotName(spot, model.spots(), regionNames()));
		}
		return names;
	}

	/** The spots the card names: the assigned area's and the recommendation's. */
	private static List<String> spotKeys(TaskPlan plan)
	{
		List<String> spots = new ArrayList<>();
		if (plan.getAreaSpot() != null)
		{
			spots.add(plan.getAreaSpot());
		}
		if (plan.getRecommendation() != null)
		{
			spots.add(plan.getRecommendation().getSpot());
		}
		return spots;
	}

	/**
	 * The plan's task name as the sidebar shows it: from the plan, not the tracker, so a rebuild while no task is read
	 * (the card hidden, its plan kept) still names the task.
	 */
	private static String taskName(TaskPlan plan)
	{
		return LedgerRecord.displayName(plan.getTask(), plan.getCreatureId());
	}

	/** The player's names for map regions ({@link LedgerHistoryModel#locationName}). */
	private IntFunction<String> regionNames()
	{
		return model::locationName;
	}

	/** A vertical box named {@code name}, transparent, left-aligned in its parent. */
	private static JPanel column(String name)
	{
		JPanel column = new JPanel();
		column.setName(name);
		column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
		column.setOpaque(false);
		column.setAlignmentX(Component.LEFT_ALIGNMENT);
		return column;
	}

	/**
	 * A line named {@code name}: {@code left} cut short with {@code …} when it doesn't fit ({@link FitLabel}),
	 * {@code right} right-aligned; both in Small.
	 */
	private static JPanel line(String name, String left, String right, Color leftColor, Color rightColor)
	{
		JPanel line = new JPanel(new BorderLayout(GAP, 0));
		line.setName(name);
		line.setOpaque(false);
		line.setAlignmentX(Component.LEFT_ALIGNMENT);
		FitLabel leftLabel = new FitLabel(left);
		leftLabel.setFont(LedgerTheme.smallFont());
		leftLabel.setForeground(leftColor);
		line.add(leftLabel, BorderLayout.CENTER);
		line.add(smallLabel(right, rightColor), BorderLayout.EAST);
		return line;
	}

	private static JLabel smallLabel(String text, Color color)
	{
		JLabel label = LedgerTheme.label(text, LedgerTheme.smallFont(), color);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	/**
	 * A sentence in Small broken into lines that fit {@code width} px ({@link TaskPlanText#wrap}, measured in the
	 * font), one {@link FitLabel} each, named {@code name}: the no-history line and the Konar note. The sidebar's width
	 * is fixed, so the lines are worked out once; each line still asks for no width and is cut short if it ever had
	 * less room.
	 */
	private static JPanel wrapped(String name, String text, Color color, int width)
	{
		JPanel lines = column(name);
		FontMetrics metrics = lines.getFontMetrics(LedgerTheme.smallFont());
		for (String line : TaskPlanText.wrap(text, width, metrics::stringWidth))
		{
			FitLabel label = new FitLabel(line);
			label.setFont(LedgerTheme.smallFont());
			label.setForeground(color);
			label.setAlignmentX(Component.LEFT_ALIGNMENT);
			lines.add(label);
		}
		return lines;
	}

	/** Adds {@code part} to {@code column} with {@code gap} px above it, unless it is the first. */
	private static void addPart(JPanel column, JComponent part, int gap)
	{
		if (column.getComponentCount() > 0 && gap > 0)
		{
			column.add(Box.createVerticalStrut(gap));
		}
		part.setAlignmentX(Component.LEFT_ALIGNMENT);
		column.add(part);
	}

	/** {@code part} in a transparent holder with {@code gap} px of space above it, left-aligned. */
	private static JComponent gapped(JComponent part, int gap)
	{
		JPanel holder = new JPanel(new BorderLayout());
		holder.setOpaque(false);
		holder.setAlignmentX(Component.LEFT_ALIGNMENT);
		holder.setBorder(BorderFactory.createEmptyBorder(gap, 0, 0, 0));
		holder.add(part, BorderLayout.CENTER);
		return holder;
	}


	/** Plain-text lines as an HTML tooltip, each escaped. */
	private static String html(List<String> lines)
	{
		List<String> escaped = new ArrayList<>();
		for (String line : lines)
		{
			escaped.add(LedgerHistory.escapeHtml(line));
		}
		return "<html>" + String.join("<br>", escaped) + "</html>";
	}

	/**
	 * How many times the card's content was built. Performance seam: tests assert this doesn't grow on the refresh
	 * timer.
	 */
	int getBuildCount()
	{
		return builds;
	}
}
