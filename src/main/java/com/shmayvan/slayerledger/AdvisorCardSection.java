package com.shmayvan.slayerledger;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.PluginPanel;

/**
 * The advisor card of {@link SlayerLedgerPanel} (V2 plan 6 and 8, DESIGN_PRINCIPLES.md 3.5): the second
 * raised card, under the live section, shown while a task is assigned and the account's history is loaded. It shows
 * the model's plan to show ({@link LedgerHistoryModel#plan}) and works nothing out itself. Top to bottom:
 * <ol>
 * <li>the title, what the plan is based on ({@code Based on 6 Dust devils tasks}), on at most two lines, then the skip
 * / block badge ({@code Consider skip (n=6)}) in the alert colour while the "Task advisor" setting is on
 * and the advice isn't Do (Do adds no line);</li>
 * <li>the plan's metric as three text tabs, Fastest, Profit/h and Cheapest, the plan's in the value colour over a 2 px
 * accent line: a click saves the "Advisor metric" setting through the plugin
 * ({@link SlayerLedgerPlugin#setAdvisorMetric}), and the plan follows on the config-change path, its tab with it;</li>
 * <li>the note, on parchment: a Konar task's note when its area has no task yet; the recommendation, then after a
 * rule and {@code With cannon} or {@code Without cannon} the counterpart in an identical block (name, on at most two
 * lines, and sample size, {@code based on 1 task} for a thin recommendation; time and range, Slayer XP/h, Profit/h,
 * Supplies, gear, and a Bring grid of at most 8 items with {@code +N more}, whose tooltip lists the rest); the All
 * setups line; the More setups toggle and its list.</li>
 * </ol>
 * Without history the card has one line ({@link TaskPlanText#noHistoryLine}). The card is open from the assignment to
 * its first task kill, then collapsed to one line ({@link TaskPlanText#collapsedLine}); a click on the collapsed line
 * opens it, a click on the title closes it, and that choice holds until the assignment changes. Every word comes from
 * {@link TaskPlanText} and every number from {@link LedgerFormat}.
 *
 * <p>It is rebuilt only when the plan shown, the badge or whether it is open changes. The panel's refresh
 * ({@link #refresh}, also run by its timer) only reads the tracker for the task and its first kill and never asks the
 * model for the plan; the model's changes ({@link #modelChanged}) read the plan and the badge. While the plan for the
 * current inputs is worked out ({@link LedgerHistoryModel#plan} is {@code null}, as after a tab click) the card keeps
 * the plan it shows, with that plan's tab, as long as the plan is the current assignment's ({@link TaskPlan#isFor}:
 * creature, name, task type and assigned area). A reading of no task only hides the card: the count can read 0 for a
 * tick or two ({@link SessionTracker#MAX_TRANSIENT_ZERO_OBSERVATIONS}), and when the same assignment is back no model
 * change says so, since its plan is the same. Nothing in it asks for width: long names are {@link FitLabel}s, and the
 * title, option names and sentences are broken into lines at the card's fixed width. EDT only.</p>
 */
final class AdvisorCardSection
{
	/** Space between the card's parts. */
	private static final int GAP = 6;
	/** Space inside the note's 1 px edge: the card's 199 px less 2 x 6 leaves 187, room for a 184 px grid row. */
	private static final int NOTE_PADDING = 5;
	/** Space between the metric tabs. */
	private static final int TAB_GAP = 10;
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
	/** The title's tooltip's last line. */
	static final String CLOSE_HINT = "Click to close the plan.";
	/** The collapsed line's tooltip's last line. */
	static final String OPEN_HINT = "Click to open the plan.";

	/**
	 * One of the note's Bring grids and the items it shows, with its {@code +N more} label and the items that lists,
	 * so new item names only rename them.
	 */
	private static final class BringGrid
	{
		private final ItemGridView grid;
		private final List<TaskPlan.BringItem> items;
		/** {@code +N more}, or {@code null} when the grid shows every item. */
		private final JLabel more;
		private final List<TaskPlan.BringItem> rest;

		private BringGrid(ItemGridView grid, List<TaskPlan.BringItem> items, JLabel more,
			List<TaskPlan.BringItem> rest)
		{
			this.grid = grid;
			this.items = items;
			this.more = more;
			this.rest = rest;
		}
	}

	private final LedgerHistoryModel model;
	private final SessionTracker tracker;
	/** Saves the "Advisor metric" setting (the plugin's config manager), as a tab click chooses it. */
	private final Consumer<AdvisorMetric> saveMetric;
	/** Revalidates and repaints the panel after a change. */
	private final Runnable relayout;
	/** Draws the Bring grids' icons. */
	private final ItemIcons icons;

	/** The card's row of the panel, with the gap above it: hidden as one, so a hidden card leaves no gap. */
	private final JPanel block = new JPanel(new BorderLayout());
	/** The raised card: open, the title, badge, tabs and note; collapsed, one line. */
	private final JPanel card = new JPanel();
	/** {@code Based on 6 Dust devils tasks}, one label a line; a click closes the card. */
	private final JPanel title = column("advisor.title");
	/** {@code Consider skip (n=6)}: in the card only while there is such advice. */
	private final FitLabel badgeLabel = new FitLabel();
	/** The metric tabs, with a 1 px rule under them. */
	private final JPanel tabs = new JPanel();
	private final Map<AdvisorMetric, JButton> tabButtons = new EnumMap<>(AdvisorMetric.class);
	/** The card collapsed: {@code Plan: barrage, ~60 min (3 tasks)}; a click opens it. */
	private final FitLabel collapsedLabel = new FitLabel();
	/** {@code More setups (2)} or {@code Hide more setups}: opens and closes {@link #moreList}. */
	private final JButton moreToggle = new JButton();
	/** The other setups, one line each; shown while {@link #moreOpen}. */
	private final JPanel moreList = new JPanel();
	/** The note's Bring grids as last built. */
	private final List<BringGrid> bringGrids = new ArrayList<>();

	/**
	 * The plan in the card, or {@code null} for none; kept, hidden, while no task is read, for the same assignment
	 * coming back.
	 */
	private TaskPlan shownPlan;
	/** The badge shown (the model's advice other than Do), or {@code null}. */
	private TaskAdvisor.Result shownBadge;
	/** Whether the card is shown open. */
	private boolean shownOpen;
	/** The spot and option names shown, so a region's new name rebuilds the card only when it changes one. */
	private List<String> shownNames = Collections.emptyList();
	/** Whether More setups is open; closed for each new assignment. */
	private boolean moreOpen;

	/**
	 * The assignment the card is for (creature key, name, task type and assigned area), or {@code null} before the
	 * first task read with the history loaded. A reading of no task keeps it.
	 */
	private List<Object> assignment;
	/** Its assigned size, or {@link SessionTracker#UNKNOWN_AMOUNT}. */
	private int assignedOriginal = SessionTracker.UNKNOWN_AMOUNT;
	/** The kills left when the card first saw the assignment, and at its last look. */
	private int firstRemaining;
	private int lastRemaining;
	/**
	 * The session that was active when the card moved to this assignment from another, or {@code null}: the previous
	 * assignment's, which the tracker keeps when the same creature is reassigned with no reading of 0 between (a count
	 * going up only moves its baseline), so it is no sign of this assignment's first kill.
	 */
	private String previousSession;
	/** Whether a task kill of the assignment was seen, so it is collapsed unless the player opened it. */
	private boolean killed;
	/** The player's choice, open or closed, for this assignment; {@code null} until a click. */
	private Boolean chosenOpen;
	/** How many times the card's content was built ({@link #getBuildCount}). */
	private int builds;

	/**
	 * @param saveMetric saves the "Advisor metric" setting a tab click chooses
	 * @param icons      draws the Bring grids' item icons
	 */
	AdvisorCardSection(LedgerHistoryModel model, SessionTracker tracker, Consumer<AdvisorMetric> saveMetric,
		Runnable relayout, ItemIcons icons)
	{
		this.model = model;
		this.tracker = tracker;
		this.saveMetric = saveMetric;
		this.relayout = relayout;
		this.icons = icons;
		// Names for tests, which find the parts by them; no visible effect.
		block.setName("advisor");
		card.setName("advisorCard");
		badgeLabel.setName("advisor.badge");
		tabs.setName("advisor.tabs");
		collapsedLabel.setName("advisor.collapsed");
		moreToggle.setName("advisor.moreToggle");
		moreList.setName("advisor.more");

		badgeLabel.setFont(LedgerTheme.smallFont());
		// The words say skip or block; the alert colour draws the eye to them.
		badgeLabel.setForeground(LedgerTheme.ALERT);
		collapsedLabel.setFont(LedgerTheme.smallFont());
		collapsedLabel.setForeground(LedgerTheme.LABEL);
		for (JComponent part : new JComponent[]{badgeLabel, collapsedLabel})
		{
			part.setAlignmentX(Component.LEFT_ALIGNMENT);
		}
		// Its lines have no listener of their own, so a click or hover on any of them reaches it.
		title.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		title.addMouseListener(clicked(() -> choose(false)));
		collapsedLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		collapsedLabel.addMouseListener(clicked(() -> choose(true)));

		tabs.setLayout(new BoxLayout(tabs, BoxLayout.X_AXIS));
		tabs.setOpaque(false);
		tabs.setAlignmentX(Component.LEFT_ALIGNMENT);
		tabs.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LedgerTheme.BEVEL_INNER));
		for (AdvisorMetric metric : AdvisorMetric.values())
		{
			JButton tab = new JButton(metric.getShortLabel());
			tab.setName("advisor.tab." + metric.name());
			styleLink(tab, LedgerTheme.HINT);
			tab.setToolTipText(metric.toString());
			tab.addActionListener(e -> chooseMetric(metric));
			if (!tabButtons.isEmpty())
			{
				tabs.add(Box.createHorizontalStrut(TAB_GAP));
			}
			tabButtons.put(metric, tab);
			tabs.add(tab);
		}
		tabs.add(Box.createHorizontalGlue());

		styleLink(moreToggle, LedgerTheme.MUTED_INK);
		Font small = LedgerTheme.smallFont();
		// The same font, underlined: the toggle reads as a link (the mockup's), at the font's own size.
		moreToggle.setFont(small.deriveFont(Collections.singletonMap(TextAttribute.UNDERLINE,
			TextAttribute.UNDERLINE_ON)));
		moreToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
		moreToggle.addActionListener(e -> toggleMore());
		moreList.setLayout(new BoxLayout(moreList, BoxLayout.Y_AXIS));
		moreList.setOpaque(false);
		moreList.setAlignmentX(Component.LEFT_ALIGNMENT);
		moreList.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
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
	 * {@link LedgerHistoryModel.Change#LIVE} (the live inputs changed, so a restored session or a kill is seen with the
	 * panel hidden): notes the task and its first kill, hides the card without a task, and shows it again with the plan
	 * it kept when the same assignment is back. Reads nothing of the model but whether its history is loaded, and
	 * rebuilds only when whether the card is open changed or the plan it keeps is not the current assignment's.
	 */
	void refresh()
	{
		update(false);
	}

	/** A change of the history model: reads the plan to show and the badge again, and rebuilds if either changed. */
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
			rebuild(shownPlan, shownBadge, shownOpen);
		}
	}

	/**
	 * Shows what the card should show now. With {@code readModel} the plan to show and the badge are read from the model
	 * (on its changes); without, only the tracker is (on the panel's refresh), and the plan shown is kept while it is
	 * the current assignment's.
	 */
	private void update(boolean readModel)
	{
		if (!model.isLoaded())
		{
			// Logged out, loading or another account's history: nothing of the last assignment is kept.
			assignment = null;
			show(null, null, false);
			return;
		}
		if (!tracker.isTaskObserved() || tracker.getCurrentRemaining() <= 0)
		{
			// No task, or the count reading 0 for a tick or two: only hidden. The plan, the badge and the player's
			// choice stay for the same assignment, whose return no model change announces (its plan is the same).
			setShown(false);
			return;
		}
		int creatureId = tracker.getCurrentCreatureId();
		String monster = tracker.getCurrentMonster();
		TaskContext context = tracker.getCurrentContext();
		TaskType type = TaskType.of(context.getMaster(), creatureId);
		String area = context.getLocation();
		noteAssignment(Arrays.asList(creatureId, monster, type, area), monster);
		TaskPlan plan = shownPlan;
		TaskAdvisor.Result badge = shownBadge;
		if (readModel)
		{
			// Null while the plan for the current inputs is worked out (a tab click, a kill): the plan shown stays,
			// with its own tab, until it is back.
			TaskPlan current = model.plan();
			plan = current != null ? current : plan;
			badge = readBadge();
		}
		if (plan != null && !plan.isFor(creatureId, monster, type, area))
		{
			// Another assignment's plan (another creature, task type or Konar area): hidden until this one's is ready.
			plan = null;
		}
		show(plan, badge, chosenOpen != null ? chosenOpen : !killed);
	}

	/** Rebuilds the card if what it should show changed, and shows it while it has a plan. */
	private void show(TaskPlan plan, TaskAdvisor.Result badge, boolean open)
	{
		if (plan != shownPlan || badge != shownBadge || open != shownOpen)
		{
			rebuild(plan, badge, open);
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

	/**
	 * The badge to show: the model's advice when the "Task advisor" setting is on and it isn't Do, else {@code null};
	 * while views wait for today's prices the badge shown stays until they arrive.
	 */
	private TaskAdvisor.Result readBadge()
	{
		if (!model.isAdviceShown())
		{
			return null;
		}
		if (model.isWaitingForCurrentPrices())
		{
			return shownBadge;
		}
		TaskAdvisor.Result result = model.advice();
		return TaskPlanText.badge(result) == null ? null : result;
	}

	/**
	 * Notes the observed task, {@code key} (its creature key, name {@code monster}, task type and assigned area), as
	 * the card's assignment: a new one (another key, another assigned size, or more kills left than before) opens the
	 * card again and forgets the player's choice. Its first task kill is seen as a drop of the kills left, a session of
	 * this task (not one the previous assignment left running, {@link #previousSession}), or kills left below the
	 * assigned size (a task already started before a login or restart).
	 */
	private void noteAssignment(List<Object> key, String monster)
	{
		int remaining = tracker.getCurrentRemaining();
		int original = tracker.getCurrentOriginalAmount();
		boolean same = key.equals(assignment) && remaining <= lastRemaining
			&& (original == SessionTracker.UNKNOWN_AMOUNT || assignedOriginal == SessionTracker.UNKNOWN_AMOUNT
			|| original == assignedOriginal);
		boolean sessionActive = tracker.getState().isActive();
		String session = sessionActive ? tracker.getSessionId() : null;
		if (!same)
		{
			// The first assignment seen since the history loaded has no previous one: a session running then is its own
			// (restored at a login, or started before a restart).
			previousSession = assignment == null ? null : session;
			assignment = key;
			assignedOriginal = original;
			firstRemaining = remaining;
			killed = false;
			chosenOpen = null;
			moreOpen = false;
		}
		else if (assignedOriginal == SessionTracker.UNKNOWN_AMOUNT)
		{
			assignedOriginal = original;
		}
		lastRemaining = remaining;
		killed |= remaining < firstRemaining
			|| (original != SessionTracker.UNKNOWN_AMOUNT && remaining < original)
			|| (sessionActive && Objects.equals(tracker.getSessionMonster(), monster)
			&& !Objects.equals(session, previousSession));
	}

	/** The player opened ({@code true}) or closed the card: held until the assignment changes. */
	private void choose(boolean open)
	{
		chosenOpen = open;
		update(false);
	}

	/** A tab click: saves the metric unless it is the one set; the plan follows through the model ({@link #update}). */
	private void chooseMetric(AdvisorMetric metric)
	{
		if (metric != model.advisorMetric())
		{
			saveMetric.accept(metric);
		}
	}

	/** Opens or closes the other setups' list; nothing is rebuilt. */
	private void toggleMore()
	{
		moreOpen = !moreOpen;
		moreToggle.setText(TaskPlanText.moreSetups(shownPlan == null ? 0 : shownPlan.getMoreSetups().size(), moreOpen));
		moreList.setVisible(moreOpen);
		relayout.run();
	}

	/** Builds the card for {@code plan} (or empties it for none), open or collapsed. */
	private void rebuild(TaskPlan plan, TaskAdvisor.Result badge, boolean open)
	{
		shownPlan = plan;
		shownBadge = badge;
		shownOpen = open;
		card.removeAll();
		bringGrids.clear();
		shownNames = Collections.emptyList();
		if (plan != null)
		{
			builds++;
			requestRegionNames(plan);
			shownNames = names(plan);
			if (open)
			{
				buildOpen(plan, badge);
			}
			else
			{
				buildCollapsed(plan, badge);
			}
		}
		card.revalidate();
		card.repaint();
		relayout.run();
	}

	/** The card collapsed: one line in Small, with the spot, the counterpart and the badge in its tooltip. */
	private void buildCollapsed(TaskPlan plan, TaskAdvisor.Result badge)
	{
		collapsedLabel.setText(TaskPlanText.collapsedLine(plan));
		List<String> tip = new ArrayList<>();
		tip.add(collapsedLabel.getFullText());
		tip.addAll(TaskPlanText.collapsedTooltip(plan, taskName(plan), model.spots(), regionNames()));
		if (badge != null)
		{
			tip.add(TaskPlanText.badge(badge));
		}
		tip.add(OPEN_HINT);
		collapsedLabel.setToolTipText(html(tip));
		card.add(collapsedLabel);
	}

	/**
	 * The card open: the title and badge, then with history the tabs (the plan's metric chosen, so the tab and the
	 * blocks always agree) and the note, else the one line.
	 */
	private void buildOpen(TaskPlan plan, TaskAdvisor.Result badge)
	{
		String task = taskName(plan);
		if (plan.hasHistory())
		{
			String text = TaskPlanText.cardTitle(task, plan.getAllSetups().getTasks());
			title.removeAll();
			FontMetrics bold = title.getFontMetrics(LedgerTheme.boldFont());
			for (String line : TaskPlanText.wrap(text, CARD_WRAP_WIDTH, CARD_WRAP_WIDTH, MAX_NAME_LINES,
				bold::stringWidth))
			{
				title.add(fitLine("advisor.title.line", line, LedgerTheme.boldFont(), LedgerTheme.HEADING));
			}
			title.setToolTipText(html(Arrays.asList(text, CLOSE_HINT)));
			card.add(title);
		}
		if (badge != null)
		{
			badgeLabel.setText(TaskPlanText.badge(badge));
			badgeLabel.setToolTipText(html(TaskPlanText.badgeReason(badge, model.lootPriceMode())));
			card.add(badgeLabel);
		}
		if (!plan.hasHistory())
		{
			JPanel none = wrapped("advisor.noHistory", TaskPlanText.noHistoryLine(task, plan), LedgerTheme.LABEL,
				CARD_WRAP_WIDTH);
			// No title to click: the line closes the card instead.
			none.setToolTipText(CLOSE_HINT);
			none.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			none.addMouseListener(clicked(() -> choose(false)));
			card.add(card.getComponentCount() > 0 ? gapped(none, 2) : none);
			return;
		}
		for (Map.Entry<AdvisorMetric, JButton> tab : tabButtons.entrySet())
		{
			boolean selected = tab.getKey() == plan.getMetric();
			JButton button = tab.getValue();
			button.setForeground(selected ? LedgerTheme.VALUE : LedgerTheme.HINT);
			// The chosen tab: the accent line under it; the others keep its room, so nothing moves.
			button.setBorder(selected
				? BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 2, 0, LedgerTheme.HEADING),
				BorderFactory.createEmptyBorder(1, 0, 1, 0))
				: BorderFactory.createEmptyBorder(1, 0, 3, 0));
		}
		card.add(gapped(tabs, 4));
		card.add(gapped(buildNote(plan, task), GAP));
	}

	/** The parchment note: the Konar note, the recommendation and counterpart, All setups and More setups. */
	private JPanel buildNote(TaskPlan plan, String task)
	{
		JPanel note = column("advisor.note");
		note.setOpaque(true);
		note.setBackground(LedgerTheme.PARCHMENT);
		note.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LedgerTheme.PARCHMENT_EDGE, 1),
			BorderFactory.createEmptyBorder(NOTE_PADDING, NOTE_PADDING, NOTE_PADDING, NOTE_PADDING)));
		boolean perKill = !plan.hasKillsLeft();
		SlayerSpots spots = model.spots();
		IntFunction<String> regions = regionNames();
		if (plan.isAreaFallback())
		{
			addPart(note, wrapped("advisor.areaNote", TaskPlanText.areaFallback(TaskPlanText.shortSpotName(
				plan.getAreaSpot(), spots, regions)), LedgerTheme.MUTED_INK, NOTE_WRAP_WIDTH), 0);
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
			TaskPlan.Option other = plan.getCounterpart();
			if (other != null)
			{
				// Small multiples: the counterpart in the same block, under a rule and its cannon choice.
				JPanel rule = new JPanel();
				rule.setName("advisor.rule");
				rule.setBackground(LedgerTheme.PARCHMENT_EDGE);
				rule.setPreferredSize(new Dimension(0, 1));
				rule.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
				addPart(note, rule, GAP);
				JLabel label = smallLabel(TaskPlanText.counterpartLabel(other), LedgerTheme.MUTED_INK);
				label.setName("advisor.counterpartLabel");
				addPart(note, label, 4);
				addPart(note, optionBlock("advisor.counterpart", other, false, task, perKill), 2);
			}
		}
		TaskPlan.Summary all = plan.getAllSetups();
		addPart(note, line("advisor.allSetups", TaskPlanText.allSetups(all), TaskPlanText.time(all.getTime(), perKill),
			LedgerTheme.INK, LedgerTheme.INK), GAP);
		List<TaskPlan.Option> more = plan.getMoreSetups();
		if (!more.isEmpty())
		{
			moreToggle.setText(TaskPlanText.moreSetups(more.size(), moreOpen));
			addPart(note, moreToggle, 4);
			moreList.removeAll();
			for (TaskPlan.Option option : more)
			{
				JPanel row = line("advisor.more.row", TaskPlanText.optionName(option, task, spots, regions),
					TaskPlanText.moreSetupValue(option, perKill), LedgerTheme.INK, LedgerTheme.MUTED_INK);
				row.setToolTipText(html(Arrays.asList(TaskPlanText.optionName(option, task, spots, regions),
					TaskPlanText.optionTasks(option))));
				addPart(moreList, row, 2);
			}
			moreList.setVisible(moreOpen);
			addPart(note, moreList, 0);
		}
		return note;
	}

	/**
	 * One option's block, the same for the recommendation and the counterpart (small multiples): its name and sample
	 * size ({@code based on 1 task} when {@code thin}), its time and range, Slayer XP/h, Profit/h (signed; a loss in
	 * red ink), Supplies and gear, then with the kills left known what to bring.
	 */
	private JPanel optionBlock(String name, TaskPlan.Option option, boolean thin, String task, boolean perKill)
	{
		JPanel block = column(name);
		String optionName = TaskPlanText.optionName(option, task, model.spots(), regionNames());
		String tasks = TaskPlanText.optionTasks(option, thin);
		JPanel head = optionHead(name, optionName, tasks);
		head.setToolTipText(html(Arrays.asList(optionName, tasks)));
		addPart(block, head, 0);

		JPanel time = new JPanel(new BorderLayout(GAP, 0));
		time.setName(name + ".time");
		time.setOpaque(false);
		time.setAlignmentX(Component.LEFT_ALIGNMENT);
		time.add(LedgerTheme.label(TaskPlanText.time(option.getTime(), perKill), LedgerTheme.boldFont(),
			LedgerTheme.INK), BorderLayout.WEST);
		String range = TaskPlanText.timeRange(option.getTime(), perKill);
		FitLabel rangeLabel = new FitLabel(range == null ? "" : range);
		rangeLabel.setFont(LedgerTheme.smallFont());
		rangeLabel.setForeground(LedgerTheme.MUTED_INK);
		time.add(rangeLabel, BorderLayout.CENTER);
		addPart(block, time, 1);

		Long profit = option.getProfitPerHour();
		addPart(block, line(name + ".xp", "Slayer XP/h", LedgerFormat.amount(option.getSlayerXpPerHour(), false),
			LedgerTheme.INK, LedgerTheme.INK), 2);
		// The sign says gain or loss; red ink only adds to it.
		addPart(block, line(name + ".profit", "Profit/h", LedgerFormat.signed(profit, option.isProfitApproximate()),
			LedgerTheme.INK, profit != null && profit < 0 ? LedgerTheme.RED_INK : LedgerTheme.INK), 1);
		addPart(block, line(name + ".supplies", TaskPlanText.suppliesLabel(perKill),
			LedgerFormat.amount(option.getSupplyCost(), option.isSupplyCostApproximate()), LedgerTheme.INK,
			LedgerTheme.INK), 1);
		addPart(block, line(name + ".gear", "Gear", TaskPlanText.gear(option), LedgerTheme.INK, LedgerTheme.INK), 1);
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

	/** The spots the card names: the assigned area's, the recommendation's, the counterpart's and the others'. */
	private static List<String> spotKeys(TaskPlan plan)
	{
		List<String> spots = new ArrayList<>();
		if (plan.getAreaSpot() != null)
		{
			spots.add(plan.getAreaSpot());
		}
		for (TaskPlan.Option option : plan.getOptions())
		{
			spots.add(option.getSpot());
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

	/**
	 * Styles {@code button} as a text link of the card: Small text in {@code color}, no frame or fill, a hand cursor,
	 * and still focusable, so the keyboard reaches it (Space presses it).
	 */
	private static void styleLink(JButton button, Color color)
	{
		button.setFont(LedgerTheme.smallFont());
		button.setForeground(color);
		button.setContentAreaFilled(false);
		button.setOpaque(false);
		button.setFocusPainted(false);
		button.setBorder(BorderFactory.createEmptyBorder(1, 0, 3, 0));
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
	}

	/** A left click's listener. */
	private static MouseAdapter clicked(Runnable action)
	{
		return new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1)
				{
					action.run();
				}
			}
		};
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
