package com.shmayvan.slayerledger;

import java.awt.Color;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
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
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The "Task history" section of {@link SlayerLedgerPanel}: the Monster, Location and Master filters, the error line
 * and one row per saved task, valued at today's prices, with its item details below it while open and a
 * right-click menu to edit or delete it. The rows are rebuilt from the history model's records only when they, their
 * pricing or the names they show change; while views wait for today's prices the rows shown stay. EDT only.
 *
 * <p>Filters ({@link HistoryFilter}): they combine, and each lists only the values in the account's records. Their
 * choices are rebuilt when the records change and when region names arrive or are forgotten (a record's location may
 * be its region's name), never on the refresh timer; a selected value that is gone goes back to "All".</p>
 *
 * <p>Paging: at most {@link #PAGE_SIZE} rows are built at first, with a "Show N more" button below them that
 * adds the next {@link #PAGE_SIZE} (ignored while the rows wait for today's prices). Choosing another value in a
 * filter (or a filter dropping the selected one) and another account's history go back to the first page; reloads of
 * the same account's records (a save, an edit, a delete, new prices or names) keep the rows the player has opened up.
 * A row's tooltip is built the first time it is asked for, not with the row.</p>
 *
 * <p>Each row's monster name shows its task's icon ({@link TaskIcons}, from the record's assigned name, so a
 * Catacombs variant or cannon task shows its task's), or an empty slot of the same size; asked for only as rows are
 * built, never on the refresh timer.</p>
 */
final class HistoryListSection
{
	/** Changes one value of a saved record: {@code (account, record id, new value)}. */
	@FunctionalInterface
	interface RecordEdit
	{
		void apply(long account, String recordId, String value);
	}

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
	/** Revalidates and repaints the panel after a change. */
	private final Runnable relayout;
	/** Parent of the dialogs. */
	private final Component dialogParent;
	/** Draws the item icons and the skill icons of a row's details. */
	private final SidebarIcons icons;

	private final FilterChoice monsterFilter = new FilterChoice("history.filter", "Show only tasks for this monster",
		Objects::equals);
	private final FilterChoice locationFilter = new FilterChoice("history.locationFilter",
		"Show only tasks at this location", HistoryFilter::sameLocationChoice);
	private final FilterChoice masterFilter = new FilterChoice("history.masterFilter",
		"Show only tasks from this Slayer master", Objects::equals);
	private final JPanel list = new JPanel();
	private final JLabel emptyLabel = new JLabel();
	private final JLabel errorLabel = new JLabel();
	/** Account whose history the section last showed, so another account's selection and open rows are forgotten. */
	private long shownAccount = SessionTracker.NO_ACCOUNT;
	/** Keys ({@link #recordKey}) of the history rows whose item details are open. */
	private final Set<String> expandedRecords = new HashSet<>();
	/** XP lists of open history rows showing all their skills ("+N more" clicked), by record key and list title. */
	private final Set<String> listsShowingAll = new HashSet<>();
	/** Shown instead of the rows when the filters together match none of the saved tasks. */
	static final String NO_MATCHES = "No saved tasks match these filters.";
	/** Rows shown at first, and added by each click on {@link #showMoreButton}. */
	static final int PAGE_SIZE = 25;
	/** Below the shown rows while more of the filtered records are left: adds {@link #PAGE_SIZE} rows. */
	private final JButton showMoreButton = new JButton();
	/** How many of the filtered records get a row: a multiple of {@link #PAGE_SIZE}. */
	private int shownLimit = PAGE_SIZE;
	/** How many times the history rows were built. */
	private int builds;

	HistoryListSection(LedgerHistoryModel model, BiConsumer<Long, String> deleteRecord, RecordEdit editNote,
		RecordEdit editLocation, Component dialogParent, Runnable relayout, SidebarIcons icons)
	{
		this.model = model;
		this.deleteRecord = deleteRecord;
		this.editNote = editNote;
		this.editLocation = editLocation;
		this.dialogParent = dialogParent;
		this.relayout = relayout;
		this.icons = icons;
	}

	/** Adds the title, the filter, the hints, the error line and the (empty) list to {@code content}. */
	void addTo(JPanel content)
	{
		JLabel title = new JLabel("Task history");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		SlayerLedgerPanel.addRow(content, title);

		// One labelled row per filter, the labels in one column.
		JPanel filters = new JPanel(new GridBagLayout());
		filters.setName("history.filters");
		filters.setBackground(ColorScheme.DARK_GRAY_COLOR);
		monsterFilter.addTo(filters, "Monster", 0);
		locationFilter.addTo(filters, "Location", 1);
		masterFilter.addTo(filters, "Master", 2);
		SlayerLedgerPanel.addRow(content, filters);

		JLabel itemsHint = new JLabel("Click a task to list its supplies and loot.");
		itemsHint.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		itemsHint.setFont(FontManager.getRunescapeSmallFont());
		SlayerLedgerPanel.addRow(content, itemsHint);
		// HTML so the hint wraps in the narrow sidebar.
		JLabel hint = new JLabel("<html>Right-click a task to edit its note or location, or delete it.</html>");
		hint.setName("history.editHint");
		hint.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(hint);

		errorLabel.setName("history.error");
		errorLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		errorLabel.setFont(FontManager.getRunescapeSmallFont());
		errorLabel.setVisible(false);
		SlayerLedgerPanel.addRow(content, errorLabel);

		list.setName("history.list");
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		emptyLabel.setName("history.empty");
		emptyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		emptyLabel.setFont(FontManager.getRunescapeSmallFont());
		emptyLabel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		emptyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		showMoreButton.setName("history.showMore");
		showMoreButton.setFocusable(false);
		showMoreButton.setAlignmentX(Component.LEFT_ALIGNMENT);
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

	/** Forgets the selection and open rows of another account's history. */
	void forgetOtherAccount()
	{
		if (model.account() != shownAccount)
		{
			shownAccount = model.account();
			monsterFilter.selection = null;
			locationFilter.selection = null;
			masterFilter.selection = null;
			shownLimit = PAGE_SIZE;
			expandedRecords.clear();
			listsShowingAll.clear();
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
	 * value remains. Region names not known yet are asked for; their records' locations follow once they arrive
	 * ({@link #locationNamesChanged}).
	 */
	void rebuildFilter()
	{
		List<LedgerRecord> records = model.records();
		model.requestLocationNames(unnamedRegions(records));
		boolean changed = monsterFilter.setOptions(LedgerHistory.filterOptions(records), LedgerHistory::keepSelection);
		changed |= locationFilter.setOptions(HistoryFilter.locationOptions(records, model::locationName),
			HistoryFilter::keepLocation);
		changed |= masterFilter.setOptions(HistoryFilter.masterOptions(records), LedgerHistory::keepSelection);
		if (changed)
		{
			// A selected value is gone: "All", from its first page.
			shownLimit = PAGE_SIZE;
		}
		monsterFilter.box.setEnabled(!records.isEmpty());
		locationFilter.box.setEnabled(!records.isEmpty());
		masterFilter.box.setEnabled(!records.isEmpty());
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
		return new HistoryFilter(monsterFilter.selection, locationFilter.selection, masterFilter.selection);
	}

	/**
	 * Rebuilds the first {@link #shownLimit} rows of the filtered records and the "Show N more" button below them, or
	 * shows "No saved tasks yet." (or {@link #NO_MATCHES} when the filters together match no task); while prices are
	 * awaited the rows stay. Region names are asked for every record, as the Location filter needs them.
	 */
	void rebuild()
	{
		if (model.records().isEmpty())
		{
			showEmpty("No saved tasks yet.");
			return;
		}
		if (model.isWaitingForCurrentPrices())
		{
			// The rows shown stay until the prices asked for arrive; the model then has them rebuilt (PRICED_VIEWS).
			return;
		}
		builds++;
		clearRows();
		DateTimeFormatter formatter = LedgerHistory.shortDateTime(ZoneId.systemDefault());
		Map<Integer, Long> prices = model.shownPrices();
		Set<Integer> names = new HashSet<>();
		List<LedgerRecord> filtered = selectedFilter().apply(model.records(), model::locationName);
		model.requestLocationNames(unnamedRegions(model.records()));
		if (filtered.isEmpty())
		{
			showEmpty(NO_MATCHES);
			return;
		}
		int shown = Math.min(shownLimit, filtered.size());
		for (LedgerRecord record : filtered.subList(0, shown))
		{
			if (list.getComponentCount() > 0)
			{
				list.add(Box.createVerticalStrut(4));
			}
			JPanel row = row(model.account(), record,
				HistoryPricing.value(record, model.lootPriceMode(), prices, model.hiddenLoot()), formatter);
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			list.add(row);
			String key = recordKey(record);
			if (expandedRecords.contains(key))
			{
				JPanel details = details(record, prices, names);
				details.setAlignmentX(Component.LEFT_ALIGNMENT);
				list.add(details);
			}
		}
		int left = filtered.size() - shown;
		if (left > 0)
		{
			showMoreButton.setText("Show " + Math.min(PAGE_SIZE, left) + " more");
			showMoreButton.setToolTipText("Showing " + shown + " of " + filtered.size() + " tasks");
			list.add(Box.createVerticalStrut(4));
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

	/** Clears the rows and shows {@code message} instead. */
	void showEmpty(String message)
	{
		clearRows();
		emptyLabel.setText("<html>" + LedgerHistory.escapeHtml(message) + "</html>");
		list.add(emptyLabel);
		list.revalidate();
		list.repaint();
	}

	private void clearRows()
	{
		list.removeAll();
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
	 * The item details below an open history row: the task's supplies and loot as item icons, at the prices its row
	 * shows (today's GE prices with the saved high-alchemy values, as {@link HistoryPricing} values the totals), or
	 * {@link ItemDetails#NO_ITEM_DETAILS} for a task saved before items were tracked. Ids whose names are needed are
	 * added to {@code names}.
	 */
	private JPanel details(LedgerRecord record, Map<Integer, Long> prices, Set<Integer> names)
	{
		JPanel details = new JPanel();
		details.setName("history.details");
		details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
		details.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		details.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, ColorScheme.DARK_GRAY_COLOR),
			BorderFactory.createEmptyBorder(4, 8, 6, 8)));
		ItemDetails items = ItemDetails.of(record);
		if (items == null)
		{
			details.add(detailsLabel(ItemDetails.NO_ITEM_DETAILS, ColorScheme.LIGHT_GRAY_COLOR));
			addXp(details, record);
			return details;
		}
		items = items.withGePrices(prices);
		details.add(detailsLabel(SlayerLedgerPanel.ITEMS_AT_CURRENT_PRICES, ColorScheme.MEDIUM_GRAY_COLOR));
		ItemGridView supplies = new ItemGridView("Supplies used", false, ColorScheme.DARKER_GRAY_COLOR, icons.getItems());
		supplies.setRows(items.supplyRows(model::itemName));
		ItemGridView loot = new ItemGridView("Loot received", false, ColorScheme.DARKER_GRAY_COLOR, icons.getItems());
		// Without the items Ground Items hides, as the loot value leaves them out.
		loot.setRows(items.withNatureRunePrice(model.natureRunePrice()).lootRows(model.lootPriceMode(),
			model::itemName), model.hiddenLoot());
		addDetailsList(details, supplies);
		addDetailsList(details, loot);
		names.addAll(items.itemIds());
		addXp(details, record);
		return details;
	}

	/**
	 * Adds a saved task's XP, skill by skill with XP per hour of its active time, below its items; nothing for a task
	 * with no XP (or saved before XP was tracked; its row says "XP: -").
	 */
	private void addXp(JPanel details, LedgerRecord record)
	{
		List<SkillXp.Row> rows = SkillXp.rows(record.getXpGained(), record.getActiveMillis());
		if (rows.isEmpty())
		{
			return;
		}
		ItemListView xp = new ItemListView(SkillXp.LIST_TITLE, false, ColorScheme.DARKER_GRAY_COLOR, SkillXp.LIST_NOUN,
			icons.getSkills());
		xp.setRows(rows);
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
		itemList.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		details.add(itemList);
	}

	private static JLabel detailsLabel(String text, Color color)
	{
		JLabel label = SlayerLedgerPanel.historyLabel(text, color);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private JPanel row(long account, LedgerRecord record, HistoryPricing.Values values, DateTimeFormatter formatter)
	{
		// Exact GE and high-alchemy values, which prices are shown, any that were unavailable, the location and the
		// counts, built when first asked for. The location is in the tooltip only: no row line has room for it
		// in the sidebar.
		JPanel row = new LazyTooltipPanel(new GridLayout(5, 1, 0, 2),
			() -> LedgerHistory.rowTooltip(record, values, recordLocation(record)));
		row.setName("history.row");
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

		JLabel name = SlayerLedgerPanel.historyLabel(LedgerHistory.monster(record), Color.WHITE);
		// A name for tests, which read the icon from it; no visible effect.
		name.setName("history.name");
		SmallItemIcon.applyTask(icons.getItems(), name, record.getMonster());
		row.add(SlayerLedgerPanel.historyLine(name,
			SlayerLedgerPanel.historyLabel(LedgerHistory.status(record),
				record.isCompleted() ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.BRAND_ORANGE)));
		row.add(SlayerLedgerPanel.historyLine(
			SlayerLedgerPanel.historyLabel(LedgerHistory.formatEndedAt(record, formatter), ColorScheme.LIGHT_GRAY_COLOR),
			SlayerLedgerPanel.historyLabel(LedgerHistory.formatDuration(record.getActiveMillis()),
				ColorScheme.LIGHT_GRAY_COLOR)));
		row.add(SlayerLedgerPanel.historyLine(
			SlayerLedgerPanel.historyLabel("Kills: " + LedgerHistory.formatKills(record), ColorScheme.LIGHT_GRAY_COLOR),
			SlayerLedgerPanel.historyLabel(LedgerHistory.formatProfit(values),
				SlayerLedgerPanel.profitColor(values.getProfit()))));
		row.add(SlayerLedgerPanel.historyLine(
			SlayerLedgerPanel.historyLabel(LedgerHistory.formatLoot(values), ColorScheme.LIGHT_GRAY_COLOR),
			SlayerLedgerPanel.historyLabel(LedgerHistory.formatSupplies(values), ColorScheme.LIGHT_GRAY_COLOR)));
		// "XP: -" for a task saved before XP was tracked.
		row.add(SlayerLedgerPanel.historyLine(
			SlayerLedgerPanel.historyLabel(LedgerHistory.formatXp(record), ColorScheme.LIGHT_GRAY_COLOR),
			SlayerLedgerPanel.historyLabel(LedgerHistory.formatXpRate(record), ColorScheme.LIGHT_GRAY_COLOR)));

		// Right-click menu, as in core LootTrackerBox.
		JPopupMenu menu = new JPopupMenu();
		menu.setBorder(new EmptyBorder(5, 5, 5, 5));
		JMenuItem editNote = new JMenuItem("Edit note");
		// LedgerStore.updateNote matches by id, like delete.
		editNote.setEnabled(record.getId() != null);
		editNote.addActionListener(e -> promptEditNote(account, record));
		menu.add(editNote);
		JMenuItem editLocation = new JMenuItem("Edit location");
		// LedgerStore.updateLocation matches by id, like the note.
		editLocation.setEnabled(record.getId() != null);
		editLocation.addActionListener(e -> promptEditLocation(account, record));
		menu.add(editLocation);
		JMenuItem delete = new JMenuItem("Delete task");
		// LedgerStore.delete matches by id; a record without one cannot be targeted safely.
		delete.setEnabled(record.getId() != null);
		delete.addActionListener(e -> confirmDelete(account, record));
		menu.add(delete);
		row.setComponentPopupMenu(menu);

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				// A left click opens or closes the task's items (once per double click); a right click opens the menu.
				if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1)
				{
					toggleDetails(record);
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});
		return row;
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
	 * Asks for a saved record's location name, starting from the one shown; Cancel or closing the dialog changes
	 * nothing, an empty name removes the record's own name (it then shows its region's name, if any, or "Unknown").
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
		if (Objects.equals(location, record.getLocation()))
		{
			return;
		}
		clearError();
		editLocation.apply(account, record.getId(), location);
	}

	/** A saved record's location as the history shows it ({@link TaskLocation#displayName}). */
	private String recordLocation(LedgerRecord record)
	{
		return TaskLocation.displayName(record, model::locationName);
	}

	/**
	 * One filter's labelled drop-down and its selection. Sized from a short prototype, so a long name never
	 * widens the sidebar: it is cut short in the closed box, whose tooltip names the selected value in full, while the
	 * open list widens to fit it ({@link ComboPopupWidener}). Choices show as {@link HistoryFilter#label} names them.
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
		/**
		 * Whether two selections ({@code null} for "All") pick the same records, so a choice spelled anew keeps the
		 * page.
		 */
		private final BiPredicate<String, String> sameChoice;
		/** True while the box's model is being rebuilt, so its change events are ignored. */
		private boolean updating;

		@SuppressWarnings("unchecked")
		private FilterChoice(String name, String description, BiPredicate<String, String> sameChoice)
		{
			this.description = description;
			this.sameChoice = sameChoice;
			box.setName(name);
			box.setFocusable(false);
			// The look and feel's renderer, given each choice's label ("No location" is not a real name).
			ListCellRenderer<Object> renderer = (ListCellRenderer<Object>) box.getRenderer();
			box.setRenderer((JList<? extends String> list, String value, int index, boolean selected, boolean focus) ->
				renderer.getListCellRendererComponent(list, HistoryFilter.label(value), index, selected, focus));
			// Sized from a short name, not the longest choice, so a long one never widens the sidebar.
			box.setPrototypeDisplayValue("Abyssal demons");
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
				rebuild();
			});
		}

		/** Adds the label and the box as row {@code row} of {@code filters} (a {@link GridBagLayout}). */
		private void addTo(JPanel filters, String label, int row)
		{
			JLabel text = new JLabel(label);
			text.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			text.setFont(FontManager.getRunescapeSmallFont());
			int top = row == 0 ? 0 : 4;
			GridBagConstraints c = new GridBagConstraints();
			c.gridx = 0;
			c.gridy = row;
			c.anchor = GridBagConstraints.WEST;
			c.insets = new Insets(top, 0, 0, 6);
			filters.add(text, c);
			c.gridx = 1;
			c.weightx = 1;
			c.fill = GridBagConstraints.HORIZONTAL;
			c.insets = new Insets(top, 0, 0, 0);
			filters.add(box, c);
		}

		/**
		 * Shows {@code newOptions}, keeping the selection as {@code keep} decides ({@code (options, selection)} to the
		 * value kept, {@code null} for "All"). The box's model is replaced only when the choices changed, so an open
		 * list is not disturbed.
		 *
		 * @return whether the selection was dropped or now picks other records (not when only its spelling changed)
		 */
		private boolean setOptions(List<String> newOptions, BiFunction<List<String>, String, String> keep)
		{
			String kept = keep.apply(newOptions, selection);
			boolean changed = !sameChoice.test(kept, selection);
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
			updateTooltip();
			return changed;
		}

		/** The description, and the selected value in full (the box may cut it short). */
		private void updateTooltip()
		{
			box.setToolTipText(selection == null ? description
				: "<html>" + LedgerHistory.escapeHtml(description) + ":<br>"
				+ LedgerHistory.escapeHtml(HistoryFilter.label(selection)) + "</html>");
		}
	}

	/**
	 * A history row whose tooltip is built the first time Swing (or a test) asks for it, then kept: the rows are rebuilt
	 * whenever what a tooltip shows changes (the records, their pricing, region names).
	 */
	private static final class LazyTooltipPanel extends JPanel
	{
		private final transient Supplier<String> tooltip;
		private String text;

		private LazyTooltipPanel(GridLayout layout, Supplier<String> tooltip)
		{
			super(layout);
			this.tooltip = tooltip;
			// What setToolTipText does, without building the text.
			ToolTipManager.sharedInstance().registerComponent(this);
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

		@Override
		public String getToolTipText(MouseEvent event)
		{
			return getToolTipText();
		}
	}

	/** How many times the rows were built. Performance seam: tests assert this doesn't grow on the refresh timer. */
	int getBuildCount()
	{
		return builds;
	}
}
