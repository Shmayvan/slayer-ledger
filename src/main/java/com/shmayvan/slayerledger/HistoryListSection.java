package com.shmayvan.slayerledger;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.border.EmptyBorder;

/**
 * The "Task history" section of {@link SlayerLedgerPanel}, in look A (DESIGN_PRINCIPLES.md 3.7): a flat
 * section header, the filters, the hints, the error line and one flat row per saved task, valued at today's prices
 *, with its item details below it while open and a right-click menu to edit or delete it. The rows are rebuilt
 * from the history model's records only when they, their pricing, the filters or the names they show change; while
 * views wait for today's prices the rows shown stay. EDT only.
 *
 * <p>Rows: two lines on the page, separated by a 1 px rule, with the hover background ({@link LedgerTheme#HOVER})
 * under the mouse. Left, the task's icon ({@link TaskIcons}, from the record's assigned name, so a Catacombs
 * variant or cannon task shows its task's; an empty slot of the same size otherwise) and its name
 * ({@link LedgerHistory#monster}: the variant, {@code (cannon)}), then in Small {@link LedgerHistory#PARTIAL} for a
 * partial task (the only status a row names), the date, the location's short form ({@link TaskLocation#shortName}) and
 * one game image per effective setup tag ({@link SetupTagIcons#tags}, as the overlay and the live card's toggles show
 * it, from {@link SetupImages}; the row shows its tooltip over it). Right, the active
 * time ({@link LedgerFormat#duration}) and the profit, signed and coloured ({@link LedgerFormat#signed}, {@code ~} when
 * approximate). Nothing asks for width: the name and the second line are {@link FitLabel}s cut short with {@code \u2026},
 * and the tooltip ({@link LedgerHistory#historyRowTooltip}) starts with the full name, the date and time, the exact
 * active time, the kills and the XP. Only the row listens to the mouse, so its click, hover and menu work over every
 * part of it. The icons are asked for only as rows are built, never on the refresh timer.</p>
 *
 * <p>Filters (V2 plan 5, {@link HistoryFilter}): Monster, Location, Master and Task type always show; Cannon,
 * Style and Gear sit behind a {@code More filters} toggle, which opens by itself when one of them is set and stays as
 * the player left it while the section exists. They combine. Monster, Location and Master list only the values in the
 * account's records; their choices are rebuilt when the records change and when region names arrive or are forgotten
 * (a record's location may be its region's name), never on the refresh timer, and a selected value that is gone goes
 * back to "All". The Location filter's choices are spots, each shown by its newest task's location name. The
 * setup filters have fixed choices, "Any" first. While any filter is set the header counts them ({@code Task history \u00b7
 * 2 filters}), and {@code Clear filters} shows beside the {@code More filters} toggle.</p>
 *
 * <p>Paging: at most {@link #PAGE_SIZE} rows are built at first, with a "Show N more" button below them that
 * adds the next {@link #PAGE_SIZE} (ignored while the rows wait for today's prices). Choosing another value in a
 * filter (or a filter dropping the selected one), clearing the filters and another account's history go back to the
 * first page; reloads of the same account's records (a save, an edit, a delete, new prices or names) keep the rows the
 * player has opened up. A row's tooltip is built the first time it is asked for, not with the row.</p>
 */
final class HistoryListSection
{
	/** Changes one value of a saved record: {@code (account, record id, new value)}. */
	@FunctionalInterface
	interface RecordEdit
	{
		void apply(long account, String recordId, String value);
	}

	/** Sets a saved record's setup overrides: {@code (account, record id, overrides)}. */
	@FunctionalInterface
	interface SetupEdit
	{
		void apply(long account, String recordId, SetupOverrides overrides);
	}

	/** The section header's title. */
	static final String TITLE = "Task history";
	/** Shown instead of the rows while the account has no saved task (DESIGN_PRINCIPLES.md 3.9). */
	static final String NO_HISTORY = "No saved tasks yet. Finished tasks show up here.";
	/** Shown instead of the rows when the filters together match none of the saved tasks, with {@link #CLEAR_FILTERS}. */
	static final String NO_MATCHES = "No tasks match these filters.";
	/** The link that sets every filter back to All or Any. */
	static final String CLEAR_FILTERS = "Clear filters";
	/** The toggle that shows the setup filters (Cannon, Style, Gear). */
	static final String MORE_FILTERS = "More filters";
	/** The same toggle while they show. */
	static final String HIDE_MORE_FILTERS = "Hide more filters";
	/** The hint under the filters, naming every right-click menu item (HTML, so it wraps in the narrow sidebar). */
	static final String EDIT_HINT = "<html>Right-click a task to edit its note, location or setup, or delete it.</html>";
	/** The right-click menu item that opens a saved task's setup dialog. */
	static final String SETUP_MENU_ITEM = "Setup\u2026";
	/**
	 * The right-click menu item that renames a region the spot table doesn't list, for every task there; only
	 * on such a task.
	 */
	static final String RENAME_PLACE_MENU_ITEM = "Rename place (all its tasks)";
	/** Rows shown at first, and added by each click on {@link #showMoreButton}. */
	static final int PAGE_SIZE = 25;
	/** Between the date and the location on a row's second line. */
	static final String SEPARATOR = " \u00b7 ";

	/** Space between a row's left and right parts, and around the hover background. */
	private static final int GAP = 6;
	/** Space between the parts of a row's second line, and between its tag icons. */
	private static final int SMALL_GAP = 2;
	/** A row's padding inside its hover background. */
	private static final int ROW_PADDING = 4;
	/** Sizes every filter box alike, from a short name, so a long name never widens the sidebar. */
	private static final String FILTER_PROTOTYPE = "Abyssal demons";

	private final LedgerHistoryModel model;
	/**
	 * Deletes a saved record, after the player confirmed it: {@code (account, record id)}. The panel also drops a
	 * notice about that record first.
	 */
	private final BiConsumer<Long, String> deleteRecord;
	/** Saves a record's new note ({@code null} removes it). */
	private final RecordEdit editNote;
	/** Saves a record's new location name ({@code null} removes it). */
	private final RecordEdit editLocation;
	/** Saves a record's setup overrides (each {@code null} clears one). */
	private final SetupEdit editSetup;
	/**
	 * Renames a region the spot table doesn't list for every task there ({@code (region, name)}); {@code true}
	 * when the name was taken. None until {@link #setRenamePlace}.
	 */
	private BiPredicate<Integer, String> renamePlace = (region, name) -> false;
	/** Revalidates and repaints the panel after a change. */
	private final Runnable relayout;
	/** Parent of the dialogs. */
	private final Component dialogParent;
	/** Draws the task and tag icons of the rows and the item and skill icons of a row's details. */
	private final SidebarIcons icons;
	/** The shown rows' tag icons whose image has not arrived yet, by image; forgotten when the rows are cleared. */
	private final Map<SetupTagIcons.Icon, List<JLabel>> waitingTags = new EnumMap<>(SetupTagIcons.Icon.class);

	/** The section header's title, with the number of filters set ({@link #headerText}). */
	private final JLabel titleLabel = new JLabel(TITLE);
	/** In the header while a filter is set: sets every filter back to All or Any. */
	private final JButton clearButton = new JButton(CLEAR_FILTERS);

	private final FilterChoice monsterFilter = new FilterChoice("history.filter", "Show only tasks for this monster",
		HistoryFilter::label);
	/** The Location filter's choices are spot keys ({@link HistoryFilter#locationKey}), shown by their labels. */
	private HistoryFilter.LocationChoices locationChoices;
	private final FilterChoice locationFilter = new FilterChoice("history.locationFilter",
		"Show only tasks at this location", key -> locationChoices == null ? HistoryFilter.label(key)
		: locationChoices.label(key));
	private final FilterChoice masterFilter = new FilterChoice("history.masterFilter",
		"Show only tasks from this Slayer master", HistoryFilter::label);
	private final SetupFilter<TaskType> taskTypeFilter = new SetupFilter<>("history.taskTypeFilter",
		"Show only tasks of this type", HistoryFilter.TASK_TYPE_CHOICES);
	private final SetupFilter<Boolean> cannonFilter = new SetupFilter<>("history.cannonFilter",
		"Show only tasks with or without a cannon", HistoryFilter.CANNON_CHOICES);
	private final SetupFilter<SetupStyle> styleFilter = new SetupFilter<>("history.styleFilter",
		"Show only tasks of this attack style", HistoryFilter.STYLE_CHOICES);
	private final SetupFilter<GearFocus> gearFilter = new SetupFilter<>("history.gearFilter",
		"Show only tasks with this gear focus", HistoryFilter.GEAR_CHOICES);
	/** Shows or hides the setup filters; see {@link #moreOpen}. */
	private final JButton moreToggle = new JButton(MORE_FILTERS);
	/**
	 * Whether the setup filters (Cannon, Style, Gear) show: opened and closed by {@link #moreToggle}, and opened when one
	 * of them is set. Kept while the section exists, also across accounts.
	 */
	private boolean moreOpen;

	private final JPanel list = new JPanel();
	/** Shown instead of the rows: {@link #emptyLabel}, with {@link #emptyClearButton} when no task matches. */
	private final JPanel emptyBlock = new JPanel();
	private final JLabel emptyLabel = new JLabel();
	/** Under {@link #NO_MATCHES}: sets every filter back to All or Any. */
	private final JButton emptyClearButton = new JButton(CLEAR_FILTERS);
	private final JLabel errorLabel = new JLabel();
	/** Account whose history the section last showed, so another account's selection and open rows are forgotten. */
	private long shownAccount = SessionTracker.NO_ACCOUNT;
	/** Keys ({@link #recordKey}) of the history rows whose item details are open. */
	private final Set<String> expandedRecords = new HashSet<>();
	/** XP lists of open history rows showing all their skills ("+N more" clicked), by record key and list title. */
	private final Set<String> listsShowingAll = new HashSet<>();
	/** Below the shown rows while more of the filtered records are left: adds {@link #PAGE_SIZE} rows. */
	private final JButton showMoreButton = new JButton();
	/** How many of the filtered records get a row: a multiple of {@link #PAGE_SIZE}. */
	private int shownLimit = PAGE_SIZE;
	/** How many times the history rows were built. */
	private int builds;

	HistoryListSection(LedgerHistoryModel model, BiConsumer<Long, String> deleteRecord, RecordEdit editNote,
		RecordEdit editLocation, SetupEdit editSetup, Component dialogParent, Runnable relayout, SidebarIcons icons)
	{
		this.model = model;
		this.deleteRecord = deleteRecord;
		this.editNote = editNote;
		this.editLocation = editLocation;
		this.editSetup = editSetup;
		this.dialogParent = dialogParent;
		this.relayout = relayout;
		this.icons = icons;
		icons.getSetup().addListener(this::tagImageArrived, list::repaint);
	}

	/**
	 * Sets what renames a region the spot table doesn't list, for every task there ({@code (region, name)};
	 * {@code true} when the name was taken).
	 */
	void setRenamePlace(BiPredicate<Integer, String> renamePlace)
	{
		this.renamePlace = renamePlace;
	}

	/** Adds the header, the filters, the hints, the error line and the (empty) list to {@code content}. */
	void addTo(JPanel content)
	{
		// A flat section header (DESIGN_PRINCIPLES.md 3.3), counting the filters set.
		JPanel header = new JPanel(new BorderLayout(GAP, 0));
		header.setName("history.header");
		titleLabel.setName("history.title");
		LedgerTheme.styleSectionHeader(header, titleLabel, null);
		header.add(titleLabel, BorderLayout.WEST);
		SlayerLedgerPanel.addRow(content, header);

		// One labelled row per filter, the labels in one column; the setup filters behind the toggle.
		JPanel filters = new JPanel(new GridBagLayout());
		filters.setName("history.filters");
		filters.setBackground(LedgerTheme.PAGE);
		int row = 0;
		monsterFilter.addTo(filters, "Monster", row++);
		locationFilter.addTo(filters, "Location", row++);
		masterFilter.addTo(filters, "Master", row++);
		taskTypeFilter.addTo(filters, "Task type", row++);
		moreToggle.setName("history.moreFilters");
		styleLink(moreToggle, LedgerTheme.LABEL);
		Font underlined = LedgerTheme.smallFont().deriveFont(Collections.singletonMap(TextAttribute.UNDERLINE,
			TextAttribute.UNDERLINE_ON));
		moreToggle.setFont(underlined);
		moreToggle.addActionListener(e -> setMoreOpen(!moreOpen));
		// Clear filters beside the toggle, under the filters it clears, while a filter is set: beside the header's
		// "Task history \u00b7 2 filters" (Bold, 148 px) it would not fit the sidebar's 213 px.
		clearButton.setName("history.clearFilters");
		styleLink(clearButton, LedgerTheme.LABEL);
		clearButton.setFont(underlined);
		clearButton.setToolTipText("Show every task: set each filter back to All or Any");
		clearButton.addActionListener(e -> clearFilters());
		clearButton.setVisible(false);
		JPanel links = new JPanel(new BorderLayout(GAP, 0));
		links.setName("history.filterLinks");
		links.setOpaque(false);
		links.add(moreToggle, BorderLayout.WEST);
		links.add(clearButton, BorderLayout.EAST);
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = row++;
		c.gridwidth = 2;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(2, 0, 0, 0);
		filters.add(links, c);
		cannonFilter.addTo(filters, "Cannon", row++);
		styleFilter.addTo(filters, "Style", row++);
		gearFilter.addTo(filters, "Gear", row);
		showMoreFilters();
		SlayerLedgerPanel.addRow(content, filters);

		JLabel itemsHint = new JLabel("Click a task to list its supplies and loot.");
		itemsHint.setForeground(LedgerTheme.HINT);
		itemsHint.setFont(LedgerTheme.smallFont());
		SlayerLedgerPanel.addRow(content, itemsHint);
		// HTML so the hint wraps in the narrow sidebar.
		JLabel hint = new JLabel(EDIT_HINT);
		hint.setName("history.editHint");
		hint.setForeground(LedgerTheme.HINT);
		hint.setFont(LedgerTheme.smallFont());
		hint.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(hint);

		errorLabel.setName("history.error");
		// A file error: the alert colour, with words that say what failed (DESIGN_PRINCIPLES.md 3.9).
		errorLabel.setForeground(LedgerTheme.ALERT);
		errorLabel.setFont(LedgerTheme.smallFont());
		errorLabel.setVisible(false);
		SlayerLedgerPanel.addRow(content, errorLabel);

		list.setName("history.list");
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(LedgerTheme.PAGE);
		emptyBlock.setName("history.emptyBlock");
		emptyBlock.setLayout(new BoxLayout(emptyBlock, BoxLayout.Y_AXIS));
		emptyBlock.setOpaque(false);
		emptyBlock.setBorder(BorderFactory.createEmptyBorder(6, ROW_PADDING, 6, ROW_PADDING));
		emptyBlock.setAlignmentX(Component.LEFT_ALIGNMENT);
		emptyLabel.setName("history.empty");
		emptyLabel.setForeground(LedgerTheme.LABEL);
		emptyLabel.setFont(LedgerTheme.smallFont());
		emptyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		emptyBlock.add(emptyLabel);
		emptyClearButton.setName("history.empty.clear");
		styleLink(emptyClearButton, LedgerTheme.LABEL);
		emptyClearButton.setFont(moreToggle.getFont());
		emptyClearButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		emptyClearButton.addActionListener(e -> clearFilters());
		emptyBlock.add(emptyClearButton);
		showMoreButton.setName("history.showMore");
		showMoreButton.setFocusable(false);
		showMoreButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		SlayerLedgerPanel.styleTextButton(showMoreButton);
		showMoreButton.addActionListener(e ->
		{
			if (model.isWaitingForCurrentPrices())
			{
				// The rows stay until today's prices arrive; a click now would not show, so clicks don't pile up.
				return;
			}
			shownLimit += PAGE_SIZE;
			rebuild();
		});
		SlayerLedgerPanel.addRow(content, list);
	}

	/** Forgets the selection and open rows of another account's history; the setup filters' toggle stays. */
	void forgetOtherAccount()
	{
		if (model.account() != shownAccount)
		{
			shownAccount = model.account();
			monsterFilter.selection = null;
			locationFilter.selection = null;
			masterFilter.selection = null;
			resetFixedFilters();
			shownLimit = PAGE_SIZE;
			expandedRecords.clear();
			listsShowingAll.clear();
			updateHeader();
		}
	}

	/**
	 * Shows a non-modal error above the history list (for example a failed delete) until the next successful refresh
	 * or the next delete. Ignored if {@code account}'s history is no longer shown.
	 */
	void showError(long account, String message)
	{
		if (account != model.account())
		{
			return;
		}
		errorLabel.setText("<html>" + LedgerHistory.escapeHtml(message) + "</html>");
		errorLabel.setVisible(true);
		relayout.run();
	}

	void clearError()
	{
		if (errorLabel.isVisible())
		{
			errorLabel.setVisible(false);
			relayout.run();
		}
	}

	/**
	 * Rebuilds the filters' choices from the records (and the region names known), keeping each selection while its
	 * value remains. Without records (logged out, loading, a load failure, none saved) every filter goes back to All or
	 * Any, so the header never counts filters over an empty list. Region names not known yet are asked for; their
	 * records' locations follow once they arrive ({@link #locationNamesChanged}).
	 */
	void rebuildFilter()
	{
		List<LedgerRecord> records = model.records();
		model.requestLocationNames(unnamedRegions(records));
		boolean changed = monsterFilter.setOptions(LedgerHistory.filterOptions(records), LedgerHistory::keepSelection);
		// The labels first: the box shows the choices by them.
		locationChoices = HistoryFilter.locationChoices(records, model.spots(), model::locationName);
		changed |= locationFilter.setOptions(locationChoices.getOptions(), HistoryFilter::keepLocation);
		changed |= masterFilter.setOptions(HistoryFilter.masterOptions(records), LedgerHistory::keepSelection);
		boolean any = !records.isEmpty();
		if (!any)
		{
			// The fixed choices stay, but there is nothing for them to filter.
			changed |= resetFixedFilters();
		}
		if (changed)
		{
			// A selected value is gone: "All" or "Any", from its first page.
			shownLimit = PAGE_SIZE;
		}
		for (JComboBox<?> box : Arrays.asList(monsterFilter.box, locationFilter.box, masterFilter.box,
			taskTypeFilter.box, cannonFilter.box, styleFilter.box, gearFilter.box))
		{
			box.setEnabled(any);
		}
		updateHeader();
	}

	/**
	 * Region names arrived (one changed) or were forgotten: records' locations may differ, so the Location filter's
	 * choices are rebuilt, then the rows.
	 */
	void locationNamesChanged()
	{
		rebuildFilter();
		if (!model.records().isEmpty())
		{
			rebuild();
		}
	}

	/** The regions of the records without a location name of their own: they show their region's name, if any. */
	private static Set<Integer> unnamedRegions(List<LedgerRecord> records)
	{
		Set<Integer> regions = new HashSet<>();
		for (LedgerRecord record : records)
		{
			if (record.getLocation() == null && record.getLocationRegion() != null)
			{
				regions.add(record.getLocationRegion());
			}
		}
		return regions;
	}

	/** The filters as selected now. */
	private HistoryFilter selectedFilter()
	{
		return new HistoryFilter(monsterFilter.selection, locationFilter.selection, masterFilter.selection)
			.withTaskType(taskTypeFilter.selection).withCannon(cannonFilter.selection)
			.withStyle(styleFilter.selection).withGear(gearFilter.selection);
	}

	/** The setup filters behind {@link #moreToggle}: Cannon, Style and Gear. */
	private List<SetupFilter<?>> setupFilters()
	{
		return Arrays.asList(cannonFilter, styleFilter, gearFilter);
	}

	/**
	 * Sets the filters with fixed choices (Task type and the setup filters) back to Any, without their change events;
	 * returns whether any was set.
	 */
	private boolean resetFixedFilters()
	{
		boolean wasSet = false;
		for (SetupFilter<?> filter : Arrays.asList(taskTypeFilter, cannonFilter, styleFilter, gearFilter))
		{
			wasSet |= filter.selection != null;
			filter.reset();
		}
		return wasSet;
	}

	/** How many filters are set (not All or Any). */
	private int filtersSet()
	{
		int set = 0;
		for (Object selection : Arrays.asList(monsterFilter.selection, locationFilter.selection,
			masterFilter.selection, taskTypeFilter.selection, cannonFilter.selection, styleFilter.selection,
			gearFilter.selection))
		{
			if (selection != null)
			{
				set++;
			}
		}
		return set;
	}

	/** {@link #TITLE}, with {@code \u00b7 2 filters} while filters are set (DESIGN_PRINCIPLES.md 3.7). */
	static String headerText(int filtersSet)
	{
		return filtersSet <= 0 ? TITLE
			: TITLE + SEPARATOR + filtersSet + (filtersSet == 1 ? " filter" : " filters");
	}

	/** Shows how many filters are set in the header, with {@link #CLEAR_FILTERS} while any is. */
	private void updateHeader()
	{
		int set = filtersSet();
		titleLabel.setText(headerText(set));
		if (clearButton.isVisible() != set > 0)
		{
			clearButton.setVisible(set > 0);
			relayout.run();
		}
		showMoreFilters();
	}

	/** A filter's choice changed: the header, then the rows. */
	private void filtersChanged()
	{
		updateHeader();
		rebuild();
	}

	/** Sets every filter back to All or Any, from the first page, as {@link #CLEAR_FILTERS} does. */
	void clearFilters()
	{
		if (filtersSet() == 0)
		{
			return;
		}
		monsterFilter.select(null);
		locationFilter.select(null);
		masterFilter.select(null);
		resetFixedFilters();
		shownLimit = PAGE_SIZE;
		filtersChanged();
	}

	/** Opens or closes the setup filters, as {@link #moreToggle} does. */
	private void setMoreOpen(boolean open)
	{
		if (moreOpen != open)
		{
			moreOpen = open;
			showMoreFilters();
			relayout.run();
		}
	}

	/** Shows the setup filters while {@link #moreOpen}, and the toggle's text: how many are set while hidden. */
	private void showMoreFilters()
	{
		int hiddenSet = 0;
		for (SetupFilter<?> filter : setupFilters())
		{
			filter.setShown(moreOpen);
			if (filter.selection != null)
			{
				hiddenSet++;
			}
		}
		moreToggle.setText(moreOpen ? HIDE_MORE_FILTERS
			: hiddenSet > 0 ? MORE_FILTERS + " (" + hiddenSet + " set)" : MORE_FILTERS);
	}

	/**
	 * Rebuilds the first {@link #shownLimit} rows of the filtered records and the "Show N more" button below them, or
	 * shows why there are none ({@link #emptyMessage}, or {@link #NO_MATCHES} when the filters together match no task);
	 * while prices are awaited the rows stay. Region names are asked for every record, as the Location filter needs
	 * them.
	 */
	void rebuild()
	{
		if (model.records().isEmpty())
		{
			showEmpty(emptyMessage(), false);
			return;
		}
		if (model.isWaitingForCurrentPrices())
		{
			// The rows shown stay until the prices asked for arrive; the model then has them rebuilt (PRICED_VIEWS).
			return;
		}
		builds++;
		clearRows();
		ZoneId zone = ZoneId.systemDefault();
		RowFormat format = new RowFormat(LedgerHistory.shortDate(zone), LedgerHistory.shortDateTime(zone));
		Map<Integer, Long> prices = model.shownPrices();
		Set<Integer> names = new HashSet<>();
		List<LedgerRecord> filtered = selectedFilter().apply(model.records(), model.spots(), model::locationName);
		model.requestLocationNames(unnamedRegions(model.records()));
		if (filtered.isEmpty())
		{
			showEmpty(NO_MATCHES, true);
			return;
		}
		int shown = Math.min(shownLimit, filtered.size());
		for (LedgerRecord record : filtered.subList(0, shown))
		{
			HistoryPricing.Values values = HistoryPricing.value(record, model.lootPriceMode(), prices,
				model.hiddenLoot());
			JPanel row = row(model.account(), record, values, format, list.getComponentCount() == 0);
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			list.add(row);
			String key = recordKey(record);
			if (expandedRecords.contains(key))
			{
				JPanel details = details(record, values, prices, names);
				details.setAlignmentX(Component.LEFT_ALIGNMENT);
				list.add(details);
			}
		}
		int left = filtered.size() - shown;
		if (left > 0)
		{
			showMoreButton.setText("Show " + Math.min(PAGE_SIZE, left) + " more");
			showMoreButton.setToolTipText("Showing " + shown + " of " + filtered.size() + " tasks");
			list.add(Box.createVerticalStrut(GAP));
			list.add(showMoreButton);
		}
		model.requestItemNames(names);
		list.revalidate();
		list.repaint();
	}

	/** Rebuilds the rows once item names arrive, while any row's item details are open. */
	void itemNamesChanged()
	{
		if (!expandedRecords.isEmpty() && !model.records().isEmpty())
		{
			rebuild();
		}
	}

	/**
	 * What the list shows without records: the model's message when it has one (logged out, loading, a load failure),
	 * so a redraw (another Loot price, for example) never replaces it; {@link SlayerLedgerPanel#LOGGED_OUT_MESSAGE}
	 * before any history was shown; {@link #NO_HISTORY} for an account with no saved task.
	 */
	private String emptyMessage()
	{
		String message = model.historyMessage();
		if (message != null)
		{
			return message;
		}
		return model.isLoaded() ? NO_HISTORY : SlayerLedgerPanel.LOGGED_OUT_MESSAGE;
	}

	/** Clears the rows and shows {@code message} instead, with {@link #CLEAR_FILTERS} under it when {@code clear}. */
	private void showEmpty(String message, boolean clear)
	{
		clearRows();
		// HTML, so the sentence wraps in the narrow sidebar.
		emptyLabel.setText("<html>" + LedgerHistory.escapeHtml(message) + "</html>");
		emptyClearButton.setVisible(clear);
		list.add(emptyBlock);
		list.revalidate();
		list.repaint();
	}

	private void clearRows()
	{
		list.removeAll();
		waitingTags.clear();
	}

	/** A setup tag's image arrived: drawn on the shown rows' icons that wait for it. */
	private void tagImageArrived(SetupTagIcons.Icon image)
	{
		List<JLabel> waiting = waitingTags.remove(image);
		if (waiting != null)
		{
			for (JLabel label : waiting)
			{
				SmallItemIcon.applyTag(icons.getSetup(), label, image);
			}
		}
	}

	/** Identifies a record across reloads: its id, or its times for a record without one. */
	static String recordKey(LedgerRecord record)
	{
		return record.getId() != null ? "id:" + record.getId()
			: "t:" + record.getStartedAt() + ":" + record.getEndedAt();
	}

	/** Opens or closes the item details below a saved task, as a click on its row does. */
	private void toggleDetails(LedgerRecord record)
	{
		String key = recordKey(record);
		if (!expandedRecords.remove(key))
		{
			expandedRecords.add(key);
		}
		else
		{
			listsShowingAll.removeIf(k -> k.startsWith(key + "|"));
		}
		rebuild();
	}

	/**
	 * The item details below an open history row, in look A: the task's supplies and loot as item icons under
	 * nested sub-headers, quieter than the row's name above them as Overall totals' are ({@link
	 * TitledListView#useNestedSubHeader}; DESIGN_PRINCIPLES.md 3.8), with their values on the right in Small in
	 * {@link LedgerTheme#LABEL}, at the prices its row shows (today's GE prices with the saved high-alchemy values, as
	 * {@link HistoryPricing} values the totals), then its XP; or {@link ItemDetails#NO_ITEM_DETAILS} for a task saved
	 * before items were tracked. Ids whose names are needed are added to {@code names}.
	 */
	private JPanel details(LedgerRecord record, HistoryPricing.Values values, Map<Integer, Long> prices,
		Set<Integer> names)
	{
		JPanel details = new JPanel();
		details.setName("history.details");
		details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
		details.setBackground(LedgerTheme.PAGE);
		details.setBorder(BorderFactory.createEmptyBorder(0, ROW_PADDING, 8, ROW_PADDING));
		ItemDetails items = ItemDetails.of(record);
		if (items == null)
		{
			details.add(detailsLabel(ItemDetails.NO_ITEM_DETAILS));
			addXp(details, record);
			return details;
		}
		items = items.withGePrices(prices);
		details.add(detailsLabel(SlayerLedgerPanel.ITEMS_AT_CURRENT_PRICES));
		String tooltip = LedgerHistory.valuesTooltip(values);
		ItemGridView supplies = new ItemGridView("Supplies used", false, LedgerTheme.PAGE, icons.getItems());
		supplies.useNestedSubHeader();
		// One cell per potion, though a record may hold one under two dose variants.
		supplies.setRows(items.supplyGridRows(model::itemName));
		supplies.setHeaderValue(LedgerFormat.amount(values.getSuppliesGe(), values.isSuppliesApproximate()),
			LedgerTheme.LABEL, tooltip);
		ItemGridView loot = new ItemGridView("Loot received", false, LedgerTheme.PAGE, icons.getItems());
		loot.useNestedSubHeader();
		// Without the items Ground Items hides, as the loot value leaves them out.
		loot.setRows(items.withNatureRunePrice(model.natureRunePrice()).lootRows(model.lootPriceMode(),
			model::itemName), model.hiddenLoot());
		loot.setHeaderValue(LedgerFormat.amount(values.getLootValue(), values.isLootApproximate()), LedgerTheme.LABEL,
			tooltip);
		addDetailsList(details, supplies);
		addDetailsList(details, loot);
		names.addAll(items.itemIds());
		addXp(details, record);
		return details;
	}

	/**
	 * Adds a saved task's XP, skill by skill with XP per hour of its active time, below its items, under a nested
	 * sub-header like the item grids' with the task's XP per hour on the right; nothing for a task with no XP (or saved
	 * before XP was tracked).
	 */
	private void addXp(JPanel details, LedgerRecord record)
	{
		List<SkillXp.Row> rows = SkillXp.rows(record.getXpGained(), record.getActiveMillis());
		if (rows.isEmpty())
		{
			return;
		}
		ItemListView xp = new ItemListView(SkillXp.LIST_TITLE, false, LedgerTheme.PAGE, SkillXp.LIST_NOUN,
			icons.getSkills());
		xp.useNestedSubHeader();
		xp.setRows(rows);
		Long rate = SkillXp.perHour(SkillXp.total(record.getXpGained()), record.getActiveMillis());
		xp.setHeaderValue(rate == null ? "" : LedgerFormat.amount(rate) + "/h", LedgerTheme.LABEL,
			rate == null ? null : "XP per hour of active time");
		// Keeps whether the list shows all its skills ("+N more") across rebuilds.
		String listKey = recordKey(record) + "|" + SkillXp.LIST_TITLE;
		xp.setShowAll(listsShowingAll.contains(listKey), shown ->
		{
			if (shown)
			{
				listsShowingAll.add(listKey);
			}
			else
			{
				listsShowingAll.remove(listKey);
			}
		});
		addDetailsList(details, xp);
	}

	/** Adds one list (item icons or XP) to a row's details. */
	private static void addDetailsList(JPanel details, TitledListView<?> itemList)
	{
		itemList.setOpaque(false);
		itemList.setAlignmentX(Component.LEFT_ALIGNMENT);
		itemList.setBorder(BorderFactory.createEmptyBorder(GAP, 0, 0, 0));
		details.add(itemList);
	}

	private static JLabel detailsLabel(String text)
	{
		JLabel label = LedgerTheme.label(text, LedgerTheme.smallFont(), LedgerTheme.HINT);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	/** The two date formats of a rebuild: the rows' dates and the tooltips' dates with times. */
	private static final class RowFormat
	{
		private final DateTimeFormatter date;
		private final DateTimeFormatter dateTime;

		private RowFormat(DateTimeFormatter date, DateTimeFormatter dateTime)
		{
			this.date = date;
			this.dateTime = dateTime;
		}
	}

	/**
	 * A saved task's row: two lines on the page ({@link RowPanel}), its tooltip built when first asked for, a left
	 * click opening its details and a right click its edit menu.
	 */
	private JPanel row(long account, LedgerRecord record, HistoryPricing.Values values, RowFormat format,
		boolean first)
	{
		// Exact values, which prices are shown, the full name, date and time, kills, XP, location and counts: built
		// when first asked for.
		RowPanel row = new RowPanel(() -> LedgerHistory.historyRowTooltip(record, values, recordLocation(record),
			LedgerHistory.formatEndedAt(record, format.dateTime)));
		row.setName("history.row");
		row.setBackground(LedgerTheme.PAGE);
		// A 1 px rule between rows (DESIGN_PRINCIPLES.md 3.3), drawn over the hover background too.
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(first ? 0 : 1, 0, 0, 0, LedgerTheme.BEVEL_INNER),
			BorderFactory.createEmptyBorder(ROW_PADDING, ROW_PADDING, ROW_PADDING, ROW_PADDING)));

		row.name.setText(LedgerHistory.monster(record));
		SmallItemIcon.applyTask(icons.getItems(), row.name, record.getMonster());
		row.duration.setText(LedgerFormat.duration(record.getActiveMillis()));
		if (!record.isCompleted())
		{
			row.addStatus(LedgerHistory.PARTIAL);
		}
		row.when.setText(secondLine(LedgerHistory.formatEndedAt(record, format.date),
			TaskLocation.shortName(record, model::locationName, model.spots())));
		Long profit = values.getProfit();
		row.profit.setText(LedgerFormat.signed(profit, values.isProfitApproximate()));
		row.profit.setForeground(profit == null || profit == 0 ? LedgerTheme.VALUE
			: profit > 0 ? LedgerTheme.GOOD : LedgerTheme.ALERT);

		row.addMouseListener(rowMouse(row, record));
		for (SetupTagIcons.Tag tag : SetupTagIcons.tags(SetupTags.of(record)))
		{
			JLabel icon = row.addTag(tag);
			// The game image, as the overlay and the live card's toggles show it; a sprite arrives later.
			if (!SmallItemIcon.applyTag(icons.getSetup(), icon, tag.getIcon()))
			{
				waitingTags.computeIfAbsent(tag.getIcon(), image -> new ArrayList<>()).add(icon);
			}
		}
		row.setComponentPopupMenu(menu(account, record));
		return row;
	}

	/**
	 * The second line's text: the date, then the location after {@link #SEPARATOR} when one is known; the location
	 * alone without a date.
	 */
	static String secondLine(String date, String location)
	{
		boolean dated = date != null && !LedgerHistory.NO_VALUE.equals(date);
		if (location == null)
		{
			return dated ? date : LedgerHistory.NO_VALUE;
		}
		return dated ? date + SEPARATOR + location : location;
	}

	/**
	 * A row's mouse: a single left click opens or closes the task's items (once per double click; a right click opens
	 * the menu), and the hover background shows from when the mouse enters the row until it leaves it. No part of the
	 * row listens to the mouse, its tag icons included ({@link RowPanel#getToolTipText(MouseEvent)} serves their
	 * tooltips), so moving over them is no exit; any exit clears the hover, also one at a point still inside the row,
	 * as when the right-click menu or a dialog takes the mouse.
	 */
	private MouseAdapter rowMouse(JPanel row, LedgerRecord record)
	{
		return new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1)
				{
					toggleDetails(record);
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(LedgerTheme.HOVER);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(LedgerTheme.PAGE);
			}
		};
	}

	/** A row's right-click menu, as in core LootTrackerBox: the note, the location, the setup and delete. */
	private JPopupMenu menu(long account, LedgerRecord record)
	{
		JPopupMenu menu = new JPopupMenu();
		menu.setBorder(new EmptyBorder(5, 5, 5, 5));
		JMenuItem editNoteItem = new JMenuItem("Edit note");
		// LedgerStore.updateNote matches by id, like delete.
		editNoteItem.setEnabled(record.getId() != null);
		editNoteItem.addActionListener(e -> promptEditNote(account, record));
		menu.add(editNoteItem);
		JMenuItem editLocationItem = new JMenuItem("Edit location");
		// LedgerStore.updateLocation matches by id, like the note.
		editLocationItem.setEnabled(record.getId() != null);
		editLocationItem.addActionListener(e -> promptEditLocation(account, record));
		menu.add(editLocationItem);
		Integer unlisted = SlayerSpots.keyRegion(model.spots().recordSpot(record));
		if (unlisted != null)
		{
			// A region the spot table doesn't list is a place of its own, which the player names.
			JMenuItem renamePlaceItem = new JMenuItem(RENAME_PLACE_MENU_ITEM);
			renamePlaceItem.addActionListener(e -> promptRenamePlace(unlisted));
			menu.add(renamePlaceItem);
		}
		JMenuItem editSetupItem = new JMenuItem(SETUP_MENU_ITEM);
		// LedgerStore.updateSetup matches by id, like the note.
		editSetupItem.setEnabled(record.getId() != null);
		editSetupItem.addActionListener(e -> promptEditSetup(account, record));
		menu.add(editSetupItem);
		JMenuItem delete = new JMenuItem("Delete task");
		// LedgerStore.delete matches by id; a record without one cannot be targeted safely.
		delete.setEnabled(record.getId() != null);
		delete.addActionListener(e -> confirmDelete(account, record));
		menu.add(delete);
		return menu;
	}

	/** Asks before deleting a saved record; Cancel or closing the dialog does nothing. */
	private void confirmDelete(long account, LedgerRecord record)
	{
		String[] options = {"Delete", "Cancel"};
		int choice = JOptionPane.showOptionDialog(dialogParent, LedgerHistory.deleteConfirmation(record),
			"Slayer Ledger", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
		if (choice != 0 || record.getId() == null || account == SessionTracker.NO_ACCOUNT)
		{
			return;
		}
		clearError();
		deleteRecord.accept(account, record.getId());
	}

	/** Asks for a saved record's new note; Cancel or closing the dialog changes nothing, an empty note removes it. */
	private void promptEditNote(long account, LedgerRecord record)
	{
		Object input = JOptionPane.showInputDialog(dialogParent, LedgerHistory.editNotePrompt(record), "Slayer Ledger",
			JOptionPane.PLAIN_MESSAGE, null, null, record.getNote() == null ? "" : record.getNote());
		if (input == null || record.getId() == null || account == SessionTracker.NO_ACCOUNT)
		{
			return;
		}
		String note = TaskNote.normalize(input.toString());
		if (Objects.equals(note, record.getNote()))
		{
			return;
		}
		clearError();
		editNote.apply(account, record.getId(), note);
	}

	/**
	 * Asks for a saved record's own location name, starting from the one shown; Cancel or closing the dialog changes
	 * nothing, an empty name removes the record's own name (it then shows its spot's name, or "Unknown"), and the name
	 * already shown is not saved as its own. The record keeps its spot.
	 */
	private void promptEditLocation(long account, LedgerRecord record)
	{
		String shown = recordLocation(record);
		Object input = JOptionPane.showInputDialog(dialogParent, TaskLocation.editPrompt(record), "Slayer Ledger",
			JOptionPane.PLAIN_MESSAGE, null, null, TaskLocation.UNKNOWN.equals(shown) ? "" : shown);
		if (input == null || record.getId() == null || account == SessionTracker.NO_ACCOUNT)
		{
			return;
		}
		String location = TaskLocation.normalizeName(input.toString());
		if (Objects.equals(location, record.getLocation())
			|| (record.getLocation() == null && Objects.equals(location, shown)))
		{
			// Unchanged; a name it shows from its spot stays its spot's, so it follows a later rename.
			return;
		}
		clearError();
		editLocation.apply(account, record.getId(), location);
	}

	/**
	 * Asks for the name of {@code region}, a region the spot table doesn't list, for every task there, starting
	 * from its name now; Cancel, closing the dialog, an empty name or the same name change nothing.
	 */
	private void promptRenamePlace(int region)
	{
		String current = model.locationName(region);
		Object input = JOptionPane.showInputDialog(dialogParent, TaskLocation.renamePrompt(region), "Slayer Ledger",
			JOptionPane.PLAIN_MESSAGE, null, null, current == null ? "" : current);
		if (input == null)
		{
			return;
		}
		String name = TaskLocation.normalizeName(input.toString());
		if (name == null || name.equals(current))
		{
			return;
		}
		clearError();
		renamePlace.test(region, name);
	}

	/**
	 * Asks for a saved record's setup overrides: one choice per tag, cannon, style and gear, whose first choice
	 * clears the override and shows the detected value. Cancel or closing the dialog changes nothing; OK saves the
	 * choices when they differ from the record's overrides.
	 */
	private void promptEditSetup(long account, LedgerRecord record)
	{
		final SetupTags tags = SetupTags.of(record);
		final SetupOverrides current = tags.getOverrides();
		final JComboBox<SetupChoices.Choice<Boolean>> cannon = setupBox("history.setup.cannon",
			SetupChoices.cannon(tags), current.getCannon());
		final JComboBox<SetupChoices.Choice<SetupStyle>> style = setupBox("history.setup.style",
			SetupChoices.style(tags), current.getStyle());
		final JComboBox<SetupChoices.Choice<GearFocus>> gear = setupBox("history.setup.gear",
			SetupChoices.gear(tags), current.getGear());

		JPanel form = new JPanel(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = 0;
		c.gridwidth = 2;
		c.anchor = GridBagConstraints.WEST;
		c.insets = new Insets(0, 0, 6, 0);
		form.add(new JLabel(SetupChoices.prompt(record)), c);
		c.gridwidth = 1;
		c.insets = new Insets(2, 0, 2, 6);
		addSetupRow(form, c, "Cannon", cannon);
		addSetupRow(form, c, "Style", style);
		addSetupRow(form, c, "Gear", gear);

		int answer = JOptionPane.showConfirmDialog(dialogParent, form, "Slayer Ledger", JOptionPane.OK_CANCEL_OPTION,
			JOptionPane.PLAIN_MESSAGE);
		if (answer != JOptionPane.OK_OPTION || record.getId() == null || account == SessionTracker.NO_ACCOUNT)
		{
			return;
		}
		final SetupOverrides chosen = SetupOverrides.of(chosen(cannon), chosen(style), chosen(gear));
		if (chosen.equals(current))
		{
			return;
		}
		clearError();
		editSetup.apply(account, record.getId(), chosen);
	}

	private static <T> JComboBox<SetupChoices.Choice<T>> setupBox(String name, List<SetupChoices.Choice<T>> choices,
		T override)
	{
		final DefaultComboBoxModel<SetupChoices.Choice<T>> boxModel = new DefaultComboBoxModel<>();
		for (SetupChoices.Choice<T> choice : choices)
		{
			boxModel.addElement(choice);
		}
		final JComboBox<SetupChoices.Choice<T>> box = new JComboBox<>(boxModel);
		box.setName(name);
		box.setSelectedIndex(SetupChoices.indexOf(choices, override));
		return box;
	}

	private static void addSetupRow(JPanel form, GridBagConstraints c, String label, JComboBox<?> box)
	{
		c.gridy++;
		c.gridx = 0;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		form.add(new JLabel(label), c);
		c.gridx = 1;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		form.add(box, c);
	}

	/** The override a setup box's selected choice sets, or {@code null} to clear it. */
	private static <T> T chosen(JComboBox<SetupChoices.Choice<T>> box)
	{
		final int index = box.getSelectedIndex();
		return index < 0 ? null : box.getItemAt(index).getValue();
	}

	/** A saved record's location as the history shows it ({@link TaskLocation#displayName}). */
	private String recordLocation(LedgerRecord record)
	{
		return TaskLocation.displayName(record, model::locationName, model.spots());
	}

	/**
	 * Styles {@code button} as a text link of the page: Small text in {@code color}, no frame or fill, a hand cursor,
	 * and still focusable, so the keyboard reaches it (Space presses it).
	 */
	private static void styleLink(JButton button, Color color)
	{
		button.setFont(LedgerTheme.smallFont());
		button.setForeground(color);
		button.setContentAreaFilled(false);
		button.setOpaque(false);
		button.setFocusPainted(false);
		button.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
	}

	/** Adds a filter's label and box as row {@code row} of {@code filters} (a {@link GridBagLayout}). */
	private static JLabel addFilterRow(JPanel filters, String label, JComboBox<String> box, int row)
	{
		JLabel text = LedgerTheme.label(label, LedgerTheme.smallFont(), LedgerTheme.LABEL);
		int top = row == 0 ? 0 : 4;
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = row;
		c.anchor = GridBagConstraints.WEST;
		c.insets = new Insets(top, 0, 0, GAP);
		filters.add(text, c);
		c.gridx = 1;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(top, 0, 0, 0);
		filters.add(box, c);
		return text;
	}

	/** A filter box in look A: the stone of a card behind RuneLite's own drawing, sized from a short name. */
	private static void styleFilterBox(JComboBox<String> box, String name)
	{
		box.setName(name);
		box.setFocusable(false);
		box.setBackground(LedgerTheme.CARD);
		box.setForeground(LedgerTheme.VALUE);
		// Sized from a short name, not the longest choice, so a long one never widens the sidebar.
		box.setPrototypeDisplayValue(FILTER_PROTOTYPE);
	}

	/**
	 * One filter's labelled drop-down and its selection: Monster, Location or Master, whose choices are the
	 * values in the records. Sized from a short prototype, so a long name never widens the sidebar: it is cut short in
	 * the closed box, whose tooltip names the selected value in full, while the open list widens to fit it
	 * ({@link ComboPopupWidener}). Choices show as its labeler names them.
	 */
	private final class FilterChoice
	{
		private final JComboBox<String> box = new JComboBox<>();
		/** What the filter does: the start of the box's tooltip. */
		private final String description;
		/** The choices shown, {@link LedgerHistory#ALL} first. */
		private List<String> options = Collections.singletonList(LedgerHistory.ALL);
		/** Selected value, or {@code null} for "All". */
		private String selection;
		/** How a choice is shown ("No location" is not a real name; a Location choice is a spot key). */
		private final Function<String, String> labeler;
		/** True while the box's model is being rebuilt, so its change events are ignored. */
		private boolean updating;

		@SuppressWarnings("unchecked")
		private FilterChoice(String name, String description, Function<String, String> labeler)
		{
			this.description = description;
			this.labeler = labeler;
			styleFilterBox(box, name);
			// The look and feel's renderer, given each choice's label ("No location" is not a real name; a
			// Location choice is a spot key).
			ListCellRenderer<Object> renderer = (ListCellRenderer<Object>) box.getRenderer();
			box.setRenderer((JList<? extends String> list, String value, int index, boolean selected, boolean focus) ->
				renderer.getListCellRendererComponent(list, value == null ? null : labeler.apply(value), index,
					selected, focus));
			ComboPopupWidener.install(box, ComboPopupWidener.MAX_WIDTH);
			box.setModel(new DefaultComboBoxModel<>(options.toArray(new String[0])));
			updateTooltip();
			box.addActionListener(e ->
			{
				if (updating)
				{
					return;
				}
				int index = box.getSelectedIndex();
				String chosen = index <= 0 ? null : box.getItemAt(index);
				if (!Objects.equals(chosen, selection))
				{
					// Another value: back to the first page.
					shownLimit = PAGE_SIZE;
				}
				selection = chosen;
				updateTooltip();
				filtersChanged();
			});
		}

		/** Adds the label and the box as row {@code row} of {@code filters} (a {@link GridBagLayout}). */
		private void addTo(JPanel filters, String label, int row)
		{
			addFilterRow(filters, label, box, row);
		}

		/** Selects {@code value} ({@code null}: "All") without its change event, as Clear filters does. */
		private void select(String value)
		{
			selection = value;
			updating = true;
			try
			{
				box.setSelectedItem(value == null ? LedgerHistory.ALL : value);
			}
			finally
			{
				updating = false;
			}
			updateTooltip();
		}

		/**
		 * Shows {@code newOptions}, keeping the selection as {@code keep} decides ({@code (options, selection)} to the
		 * value kept, {@code null} for "All"). The box's model is replaced only when the choices changed, so an open
		 * list is not disturbed.
		 *
		 * @return whether the selection was dropped (not when only its label changed)
		 */
		private boolean setOptions(List<String> newOptions, BiFunction<List<String>, String, String> keep)
		{
			String kept = keep.apply(newOptions, selection);
			boolean changed = !Objects.equals(kept, selection);
			selection = kept;
			updating = true;
			try
			{
				if (!newOptions.equals(options))
				{
					options = newOptions;
					box.setModel(new DefaultComboBoxModel<>(newOptions.toArray(new String[0])));
				}
				box.setSelectedItem(selection == null ? LedgerHistory.ALL : selection);
			}
			finally
			{
				updating = false;
			}
			// A label may have changed with the same choices.
			box.repaint();
			updateTooltip();
			return changed;
		}

		/** The description, and the selected value in full (the box may cut it short). */
		private void updateTooltip()
		{
			box.setToolTipText(selection == null ? description
				: "<html>" + LedgerHistory.escapeHtml(description) + ":<br>"
				+ LedgerHistory.escapeHtml(labeler.apply(selection)) + "</html>");
		}
	}

	/**
	 * A setup filter's labelled drop-down (V2 plan 5): Task type, Cannon, Style or Gear, with fixed choices
	 * ({@link HistoryFilter#TASK_TYPE_CHOICES} and so on, {@code null} first for "Any"), shown as
	 * {@link HistoryFilter#setupLabel} names them. Setting Cannon, Style or Gear opens the setup filters
	 * ({@link #moreOpen}).
	 *
	 * @param <T> what a choice is
	 */
	private final class SetupFilter<T>
	{
		private final JComboBox<String> box = new JComboBox<>();
		private final List<T> choices;
		/** What the filter does: the start of the box's tooltip. */
		private final String description;
		/** The label beside the box, hidden with it. */
		private JLabel label;
		/** Selected value, or {@code null} for "Any". */
		private T selection;
		/** True while the box is set programmatically, so its change events are ignored. */
		private boolean updating;

		private SetupFilter(String name, String description, List<T> choices)
		{
			this.choices = choices;
			this.description = description;
			styleFilterBox(box, name);
			List<String> labels = new ArrayList<>(choices.size());
			for (T choice : choices)
			{
				labels.add(HistoryFilter.setupLabel(choice));
			}
			box.setModel(new DefaultComboBoxModel<>(labels.toArray(new String[0])));
			updateTooltip();
			box.addActionListener(e ->
			{
				if (updating)
				{
					return;
				}
				int index = box.getSelectedIndex();
				T chosen = index <= 0 ? null : choices.get(index);
				if (Objects.equals(chosen, selection))
				{
					return;
				}
				// Another value: back to the first page.
				shownLimit = PAGE_SIZE;
				selection = chosen;
				updateTooltip();
				if (chosen != null && setupFilters().contains(this))
				{
					setMoreOpen(true);
				}
				filtersChanged();
			});
		}

		/** Adds the label and the box as row {@code row} of {@code filters} (a {@link GridBagLayout}). */
		private void addTo(JPanel filters, String text, int row)
		{
			label = addFilterRow(filters, text, box, row);
		}

		/** Shows or hides the label and the box. */
		private void setShown(boolean shown)
		{
			box.setVisible(shown);
			if (label != null)
			{
				label.setVisible(shown);
			}
		}

		/** Back to "Any", without the change event. */
		private void reset()
		{
			selection = null;
			updating = true;
			try
			{
				box.setSelectedIndex(0);
			}
			finally
			{
				updating = false;
			}
			updateTooltip();
		}

		/** The description, and the selected value. */
		private void updateTooltip()
		{
			box.setToolTipText(selection == null ? description
				: "<html>" + LedgerHistory.escapeHtml(description) + ":<br>"
				+ LedgerHistory.escapeHtml(HistoryFilter.setupLabel(selection)) + "</html>");
		}
	}

	/**
	 * A history row: two lines (DESIGN_PRINCIPLES.md 3.7). The first has the task's icon and name, cut short to leave
	 * room for the active time on the right; the second the status word (partial tasks only), the date and location,
	 * cut short to leave room for the tag icons right after it and the profit on the right. It asks for no width of its
	 * own, so it never widens the sidebar, and builds its tooltip the first time Swing (or a test) asks for it, then
	 * keeps it: the rows are rebuilt whenever what a tooltip shows changes (the records, their pricing, region names).
	 * Over a tag icon its tooltip is the tag's instead ({@link #getToolTipText(MouseEvent)}).
	 */
	private static final class RowPanel extends JPanel
	{
		private final transient Supplier<String> tooltip;
		private String text;
		/** Each tag icon's tooltip ({@link SetupTagIcons.Tag#getTooltip}), by icon. */
		private final Map<Component, String> tagTooltips = new HashMap<>();
		/** The task's icon and name: Plain, value colour. */
		private final FitLabel name = new FitLabel();
		/** The active time: Plain, value colour. */
		private final JLabel duration = new JLabel();
		/** {@link LedgerHistory#PARTIAL} for a partial task, else {@code null}. */
		private JLabel status;
		/** The date and location: Small, label colour. */
		private final FitLabel when = new FitLabel();
		/** The setup tags' icons ({@link SetupTagIcons}), after {@link #when}. */
		private final JPanel tags = new JPanel();
		/** The profit, signed: Plain, coloured by its sign. */
		private final JLabel profit = new JLabel();

		private RowPanel(Supplier<String> tooltip)
		{
			this.tooltip = tooltip;
			setLayout(new RowLayout());
			// What setToolTipText does, without building the text.
			ToolTipManager.sharedInstance().registerComponent(this);
			// Names for tests, which read the parts by them; no visible effect.
			name.setName("history.name");
			duration.setName("history.duration");
			when.setName("history.when");
			tags.setName("history.tags");
			profit.setName("history.profit");
			name.setFont(LedgerTheme.plainFont());
			name.setForeground(LedgerTheme.VALUE);
			duration.setFont(LedgerTheme.plainFont());
			duration.setForeground(LedgerTheme.VALUE);
			when.setFont(LedgerTheme.smallFont());
			when.setForeground(LedgerTheme.LABEL);
			profit.setFont(LedgerTheme.plainFont());
			tags.setOpaque(false);
			tags.setLayout(new BoxLayout(tags, BoxLayout.X_AXIS));
			tags.setInheritsPopupMenu(true);
			for (JComponent part : new JComponent[]{name, duration, when, tags, profit})
			{
				part.setInheritsPopupMenu(true);
				add(part);
			}
		}

		/** Adds the status word before the date: the only status a row names, so it is never colour alone. */
		private void addStatus(String word)
		{
			status = LedgerTheme.label(word, LedgerTheme.smallFont(), LedgerTheme.VALUE);
			status.setName("history.status");
			status.setInheritsPopupMenu(true);
			add(status);
		}

		/**
		 * Adds one setup tag's icon slot and returns it, for its image ({@link SmallItemIcon#applyTag}). The icon has no
		 * tooltip or listener of its own, which would take the mouse from the row (its click, hover and menu): the row
		 * shows the tag's tooltip while the mouse is over it.
		 */
		private JLabel addTag(SetupTagIcons.Tag tag)
		{
			JLabel icon = new JLabel();
			icon.setName("history.tag");
			Dimension size = new Dimension(SmallItemIcon.TAG_SIZE, SmallItemIcon.TAG_SIZE);
			icon.setPreferredSize(size);
			icon.setMinimumSize(size);
			icon.setMaximumSize(size);
			icon.setInheritsPopupMenu(true);
			tagTooltips.put(icon, tag.getTooltip());
			if (tags.getComponentCount() > 0)
			{
				tags.add(Box.createHorizontalStrut(SMALL_GAP));
			}
			tags.add(icon);
			return icon;
		}

		@Override
		public String getToolTipText()
		{
			if (text == null)
			{
				text = tooltip.get();
			}
			return text;
		}

		/** Over a tag icon, the tag's tooltip ({@code Barrage (estimated)}); anywhere else, the row's. */
		@Override
		public String getToolTipText(MouseEvent event)
		{
			if (event != null)
			{
				// The deepest part shown at the point: a tag icon cut off by the line's end is not there.
				String tag = tagTooltips.get(SwingUtilities.getDeepestComponentAt(this, event.getX(), event.getY()));
				if (tag != null)
				{
					return tag;
				}
			}
			return getToolTipText();
		}

		/** Any width the list gives it, its own height. */
		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}

		/** Lays out the two lines; see {@link RowPanel}. */
		private final class RowLayout implements LayoutManager
		{
			@Override
			public void addLayoutComponent(String key, Component component)
			{
			}

			@Override
			public void removeLayoutComponent(Component component)
			{
			}

			/** No width of its own; the two lines' height. */
			@Override
			public Dimension preferredLayoutSize(Container parent)
			{
				Insets insets = parent.getInsets();
				return new Dimension(insets.left + insets.right,
					insets.top + firstHeight() + SMALL_GAP + secondHeight() + insets.bottom);
			}

			@Override
			public Dimension minimumLayoutSize(Container parent)
			{
				return preferredLayoutSize(parent);
			}

			@Override
			public void layoutContainer(Container parent)
			{
				Insets insets = parent.getInsets();
				int left = insets.left;
				int right = parent.getWidth() - insets.right;
				int y = insets.top;

				int first = firstHeight();
				int durationX = place(duration, right - duration.getPreferredSize().width, y, first, Integer.MAX_VALUE);
				place(name, left, y, first, Math.max(0, durationX - GAP - left));

				y += first + SMALL_GAP;
				int second = secondHeight();
				int profitX = place(profit, right - profit.getPreferredSize().width, y, second, Integer.MAX_VALUE);
				int end = profitX - GAP;
				int x = left;
				if (status != null)
				{
					int statusWidth = Math.min(status.getPreferredSize().width, Math.max(0, end - x));
					place(status, x, y, second, statusWidth);
					x += statusWidth + SMALL_GAP;
				}
				int tagsWidth = tags.getComponentCount() == 0 ? 0 : tags.getPreferredSize().width;
				int room = end - x - (tagsWidth > 0 ? tagsWidth + SMALL_GAP : 0);
				int textWidth = Math.max(0, Math.min(naturalWidth(when), room));
				place(when, x, y, second, textWidth);
				x += textWidth + (textWidth > 0 ? SMALL_GAP : 0);
				place(tags, x, y, second, Math.max(0, Math.min(tagsWidth, end - x)));
			}

			/**
			 * Puts {@code part} at {@code x} on the line at {@code y} of {@code height}, centred, at most {@code width}
			 * wide (its preferred width otherwise); returns {@code x}.
			 */
			private int place(Component part, int x, int y, int height, int width)
			{
				Dimension size = part.getPreferredSize();
				int partHeight = Math.min(height, size.height);
				int partWidth = width == Integer.MAX_VALUE ? size.width : width;
				part.setBounds(x, y + (height - partHeight) / 2, partWidth, partHeight);
				return x;
			}

			private int firstHeight()
			{
				return Math.max(name.getPreferredSize().height, duration.getPreferredSize().height);
			}

			private int secondHeight()
			{
				int height = Math.max(when.getPreferredSize().height, profit.getPreferredSize().height);
				height = Math.max(height, tags.getComponentCount() == 0 ? 0 : SmallItemIcon.TAG_SIZE);
				return status == null ? height : Math.max(height, status.getPreferredSize().height);
			}
		}

		/** The width {@code label}'s whole text takes in its font: what a {@link FitLabel} needs not to cut it. */
		private static int naturalWidth(FitLabel label)
		{
			FontMetrics metrics = label.getFontMetrics(label.getFont());
			Insets insets = label.getInsets();
			return metrics.stringWidth(label.getFullText()) + insets.left + insets.right;
		}
	}

	/** How many times the rows were built. Performance seam: tests assert this doesn't grow on the refresh timer. */
	int getBuildCount()
	{
		return builds;
	}
}
