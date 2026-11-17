package com.shmayvan.slayerledger;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import lombok.*;

/**
 * The live task section of {@link SlayerLedgerPanel} (DESIGN_PRINCIPLES.md 3.4, V2 plan 8). First the live task
 * card, the one raised card of the live section: its header ({@link TaskBoxView}), then while a session is active the
 * setup tags as sprite toggles the player can set ({@link SetupToggles}), the progress bar
 * ({@code 73 / 201}, while the task's size is known, {@link LedgerProgressBar}) and the two-column stats grid, in the
 * overlay's order: Kills left and Time left, Pace and Kills/h, Elapsed and Superiors, Profit and XP (and Deaths and
 * Bank trips once either happened). Then, flat on the page: the
 * "Check your blowpipe" hint, the supplies and loot grids, each under a sub-header with its value, the XP per skill,
 * the session's boss kill counts, keys, clue scrolls and notable drops, and the session's note. Rows are added, shown
 * or rebuilt only when they change; a refresh with nothing new only sets the same texts again. EDT only.
 */
final class LiveSessionSection
{
	static final String DART_HINT_TEXT = "Check your blowpipe to count darts.";
	/** The supplies grid's sub-header. */
	static final String SUPPLIES_TITLE = "Supplies used";
	/** The loot grid's sub-header. */
	static final String LOOT_TITLE = "Loot";
	/** The time left before there are enough kills for an estimate and no history to fall back on: unknown. */
	static final String TIME_LEFT_UNKNOWN = LedgerFormat.UNKNOWN;
	/** Space between the parts of the card, and between the stats grid's two columns. */
	private static final int GAP = 6;
	/** Space between a stat's label and its value. */
	private static final int LABEL_GAP = 3;
	/**
	 * The widest forms of the clock and the time left under 10 hours (0 is the fonts' widest digit): their columns
	 * keep at least this width, so the columns don't shift as the values change ({@link StatsLayout}).
	 */
	private static final String CLOCK_PROTOTYPE = "0:00:00";
	private static final String TIME_LEFT_PROTOTYPE = "~0h 00m";

	/** Saves the active session's note: {@code (account, session id, note)}; {@code null} removes it. */
	@FunctionalInterface
	interface NoteSink
	{
		void save(long account, String sessionId, String note);
	}

	/** Saves the active session's setup overrides: {@code (account, session id, overrides)}. */
	@FunctionalInterface
	interface SetupSink
	{
		void save(long account, String sessionId, SetupOverrides overrides);
	}

	/**
	 * One cell of the stats grid: a label on the left, its value right-aligned, cut short with {@code …} when its
	 * column has no room for it ({@link FitLabel}); the tooltip always starts with the label and the whole value.
	 */
	private static final class Stat extends JPanel
	{
		/** The label's text, for the tooltip. */
		private final String title;
		private final JLabel label;
		private final FitLabel value = new FitLabel();
		/** The widest value the cell usually shows, which its column keeps room for ({@link StatsLayout}); or none. */
		private final String prototype;

		private Stat(String key, String text)
		{
			this(key, text, null);
		}

		private Stat(String key, String text, String prototype)
		{
			super(new BorderLayout(LABEL_GAP, 0));
			this.title = text;
			this.prototype = prototype;
			// Names for tests, which find the cells by them; no visible effect.
			setName("stats." + key);
			value.setName("stats." + key + ".value");
			setOpaque(false);
			label = LedgerTheme.label(text, LedgerTheme.smallFont(), LedgerTheme.LABEL);
			label.setVerticalAlignment(SwingConstants.BOTTOM);
			value.setFont(LedgerTheme.plainFont());
			value.setForeground(LedgerTheme.VALUE);
			value.setHorizontalAlignment(SwingConstants.RIGHT);
			value.setVerticalAlignment(SwingConstants.BOTTOM);
			add(label, BorderLayout.WEST);
			add(value, BorderLayout.CENTER);
		}

		/**
		 * Shows {@code text} in {@code color}, with the label and {@code text} in the whole cell's tooltip, then
		 * {@code more} (plain text or HTML), if any.
		 */
		private void show(String text, Color color, String more)
		{
			value.setText(text);
			value.setForeground(color);
			String tooltip = statTooltip(title, text, more);
			if (!tooltip.equals(getToolTipText()))
			{
				setToolTipText(tooltip);
				label.setToolTipText(tooltip);
				value.setToolTipText(tooltip);
			}
		}

		/**
		 * The width the cell needs for its label and its whole value, in their fonts; with {@code steady}, at least
		 * the width {@link #prototype} needs.
		 */
		private int need(boolean steady)
		{
			FontMetrics metrics = value.getFontMetrics(value.getFont());
			int valueWidth = metrics.stringWidth(value.getFullText());
			if (steady && prototype != null)
			{
				valueWidth = Math.max(valueWidth, metrics.stringWidth(prototype));
			}
			Insets insets = getInsets();
			return insets.left + label.getPreferredSize().width + LABEL_GAP + valueWidth + insets.right;
		}
	}

	/**
	 * The stats grid's layout: rows of two columns of unequal width, each as wide as its widest cell needs
	 * ({@link Stat#need}), so a long value such as {@code ~10h 05m} isn't cut short in one column while the other has
	 * room to spare (a grid of equal columns cut most times left of an hour or more); {@link #leftColumnWidth} splits
	 * the room. It asks for no width of its own, so it never widens the sidebar (DESIGN_PRINCIPLES.md 3.1). EDT only.
	 */
	@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
	private static final class StatsLayout implements LayoutManager
	{
		private final int hgap;
		private final int vgap;

		@Override
		public void addLayoutComponent(String name, Component component)
		{
		}

		@Override
		public void removeLayoutComponent(Component component)
		{
		}

		@Override
		public Dimension preferredLayoutSize(Container parent)
		{
			Insets insets = parent.getInsets();
			int rows = (parent.getComponentCount() + 1) / 2;
			return new Dimension(insets.left + insets.right,
				insets.top + insets.bottom + rows * rowHeight(parent) + Math.max(0, rows - 1) * vgap);
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
			int available = parent.getWidth() - insets.left - insets.right - hgap;
			int[] steady = new int[2];
			int[] need = new int[2];
			for (int i = 0; i < parent.getComponentCount(); i++)
			{
				Stat stat = (Stat) parent.getComponent(i);
				steady[i % 2] = Math.max(steady[i % 2], stat.need(true));
				need[i % 2] = Math.max(need[i % 2], stat.need(false));
			}
			int left = leftColumnWidth(available, steady[0], steady[1], need[0], need[1]);
			int right = Math.max(0, available - left);
			int height = rowHeight(parent);
			for (int i = 0; i < parent.getComponentCount(); i++)
			{
				int y = insets.top + (i / 2) * (height + vgap);
				if (i % 2 == 0)
				{
					parent.getComponent(i).setBounds(insets.left, y, left, height);
				}
				else
				{
					parent.getComponent(i).setBounds(insets.left + left + hgap, y, right, height);
				}
			}
		}

		/** The tallest cell's height: every row has it. */
		private static int rowHeight(Container parent)
		{
			int height = 0;
			for (Component cell : parent.getComponents())
			{
				height = Math.max(height, cell.getPreferredSize().height);
			}
			return height;
		}
	}

	/**
	 * The stats grid's left column width, out of {@code available} for both columns. While the columns' steady needs
	 * (their values, and at least their prototypes) fit side by side, each column has its own and the room left over
	 * is shared, so the columns stay put as the values change. Else, while the values themselves fit, the left column
	 * keeps as much of its steady need as the right one leaves it. Only when they don't fit is each column cut to its
	 * share of the room; a value cut short has its whole text in the tooltip.
	 */
	static int leftColumnWidth(int available, int leftSteady, int rightSteady, int leftNeed, int rightNeed)
	{
		if (available <= 0)
		{
			return 0;
		}
		if (leftSteady + rightSteady <= available)
		{
			return leftSteady + (available - leftSteady - rightSteady) / 2;
		}
		if (leftNeed + rightNeed <= available)
		{
			return Math.max(leftNeed, Math.min(leftSteady, available - rightNeed));
		}
		return (int) ((long) available * leftNeed / Math.max(1, leftNeed + rightNeed));
	}

	/**
	 * A stat's tooltip: its label and whole value first ({@code Time left: ~1h 05m}), since the card may cut the value
	 * short (DESIGN_PRINCIPLES.md 3.1), then {@code more}, plain text or HTML, if any.
	 */
	static String statTooltip(String label, String value, String more)
	{
		String first = label + ": " + value;
		if (more == null)
		{
			return first;
		}
		String rest = more;
		if (rest.startsWith("<html>"))
		{
			rest = rest.substring("<html>".length());
			rest = rest.endsWith("</html>") ? rest.substring(0, rest.length() - "</html>".length()) : rest;
		}
		else
		{
			rest = LedgerHistory.escapeHtml(rest);
		}
		return "<html>" + LedgerHistory.escapeHtml(first) + "<br>" + rest + "</html>";
	}

	private final SessionTracker tracker;
	private final LedgerHistoryModel model;
	private final NoteSink saveNote;
	private final SetupSink saveSetup;
	/** Revalidates and repaints the panel after a change. */
	private final Runnable relayout;
	/** The card's header: the task's icon, name, location, master and advice. */
	private final TaskBoxView header;

	/** The live task card: the header, then while a session is active the setup, the bar and the stats. */
	private final JPanel card = new JPanel();
	/**
	 * The task's progress, {@code 73 / 201}, its label as wide as the bar; shown while a session is active and the
	 * task's size is known.
	 */
	private final LedgerProgressBar bar = new LedgerProgressBar();
	/** {@link #bar} with the gap above it, shown and hidden as one. */
	private final JPanel barRow = new JPanel(new BorderLayout());
	/** The bar's label as last set, so it is set only when it changes. */
	private String barText;
	/** The stats grid: two columns, in the overlay's order. */
	private final JPanel stats = new JPanel(new StatsLayout(GAP, 2));
	private final Stat killsLeft = new Stat("killsLeft", "Kills left");
	private final Stat timeLeft = new Stat("timeLeft", "Time left", TIME_LEFT_PROTOTYPE);
	private final Stat pace = new Stat("pace", "Pace");
	private final Stat killsPerHour = new Stat("killsPerHour", "Kills/h");
	private final Stat elapsed = new Stat("elapsed", "Elapsed", CLOCK_PROTOTYPE);
	private final Stat superiors = new Stat("superiors", "Superiors");
	private final Stat profit = new Stat("profit", "Profit");
	private final Stat xp = new Stat("xp", "XP");
	/** Deaths and bank trips: a fifth row, in the grid only once either happened. */
	private final Stat deaths = new Stat("deaths", "Deaths");
	private final Stat bankTrips = new Stat("bankTrips", "Bank trips");
	/** The live session's supplies as item icons, under their sub-header; shown with the live values. */
	private final ItemGridView liveSupplies;
	/** The live session's loot as item icons, in the "Loot price" setting, with the alch hint. */
	private final ItemGridView liveLoot;
	/** The live session's XP, skill by skill with each skill's icon; shown while a session is active. */
	private final ItemListView liveXp;
	/**
	 * Boss kill counts ({@link BossKillCounts#lines}), keys, clue scrolls and notable drops
	 * ({@link LedgerHistory#liveTaskLootLines}) of the active session; shown only when there are any.
	 */
	private final JPanel taskLootSection = new JPanel();
	/** Lines {@link #taskLootSection} shows, so it is only rebuilt when they change. */
	private List<String> taskLootLines = Collections.emptyList();
	/** "Check your blowpipe" hint, shown while the dart type is unknown (see {@link #setDartHint}). */
	private final JLabel dartHintLabel = new JLabel(DART_HINT_TEXT);
	/** Whether the plugin asks for the dart hint; it is only shown while a session is running. */
	private boolean dartHintRequested;
	/** The session's note: an editable combo box whose list offers recent notes. */
	private final JComboBox<String> noteBox = new JComboBox<>();
	/** Row holding {@link #noteBox}; shown while a session is active. */
	private final JPanel noteSection;
	/** Recent notes in {@link #noteBox}'s list. */
	private List<String> recentNotes = Collections.emptyList();
	/** Session (and its account) the note box is editing, or {@code null} while no session is active. */
	private String noteSessionId;
	private long noteAccount = SessionTracker.NO_ACCOUNT;
	/** The note last sent to (or read from) the tracker for {@link #noteSessionId}. */
	private String committedNote;
	/** True while the note box is changed programmatically, so its events are not taken as the player's edits. */
	private boolean updatingNote;
	/**
	 * The live task's setup tags: Cannon, Style and Gear as sprite toggles, each opening a menu whose first
	 * choice shows the detected value and clears the player's override ({@link SetupChoices}), as the history's
	 * Setup... dialog; in the card, shown while a session is active.
	 */
	private final SetupToggles setup;
	/** Session (and its account) the setup toggles belong to, or {@code null} while no session is active. */
	private String setupSessionId;
	private long setupAccount = SessionTracker.NO_ACCOUNT;
	/**
	 * Live values last received, or {@code null} when none are known yet. Only shown while they belong to the
	 * tracker's current session. Set through {@link SlayerLedgerPanel#setSessionValues}; the next refresh shows them.
	 */
	@Setter
	private SessionValuation.Values sessionValues;

	/**
	 * @param header    the card's header, which the panel refreshes
	 * @param saveSetup saves the live task's setup overrides
	 * @param icons     draws the item grids' icons, the XP list's skill icons and the setup tags' images
	 */
	LiveSessionSection(SessionTracker tracker, LedgerHistoryModel model, TaskBoxView header, NoteSink saveNote,
		SetupSink saveSetup, Runnable relayout, SidebarIcons icons)
	{
		this.tracker = tracker;
		this.model = model;
		this.header = header;
		this.saveNote = saveNote;
		this.saveSetup = saveSetup;
		this.relayout = relayout;
		liveSupplies = new ItemGridView(SUPPLIES_TITLE, false, LedgerTheme.PAGE, icons.getItems());
		liveLoot = new ItemGridView(LOOT_TITLE, false, LedgerTheme.PAGE, icons.getItems());
		liveXp = new ItemListView(SkillXp.LIST_TITLE, false, LedgerTheme.PAGE, SkillXp.LIST_NOUN, icons.getSkills());
		for (TitledListView<?> list : new TitledListView<?>[]{liveSupplies, liveLoot, liveXp})
		{
			list.useSubHeader();
		}
		noteSection = buildNoteSection();
		// The toggles name their own parts (setup, setup.cannon, ...).
		setup = new SetupToggles(icons.getSetup());
		setup.setOnChoose(this::savePickedSetup);
		// Names for tests, which find the parts by them; no visible effect.
		card.setName("liveCard");
		barRow.setName("progress");
		bar.setName("progress.bar");
		stats.setName("stats");
		noteSection.setName("note");
		noteBox.setName("note.box");
		liveSupplies.setName("liveSupplies");
		liveLoot.setName("liveLoot");
		liveXp.setName("liveXp");
		taskLootSection.setName("taskLoot");
		dartHintLabel.setName("dartHint");
	}

	/** Adds the live task card, then the hint, the grids, the XP, the task loot and the note to {@code content}. */
	void addTo(JPanel content)
	{
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		LedgerTheme.styleCard(card);
		card.add(header.getComponent());

		// Each optional part carries the gap above it, so a hidden part leaves none.
		JComponent setupRow = setup.getComponent();
		setupRow.setBorder(BorderFactory.createEmptyBorder(GAP, 0, 0, 0));
		setupRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		setupRow.setVisible(false);
		card.add(setupRow);

		barRow.setOpaque(false);
		barRow.setBorder(BorderFactory.createEmptyBorder(GAP, 0, 0, 0));
		barRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		barRow.add(bar, BorderLayout.CENTER);
		barRow.setVisible(false);
		card.add(barRow);

		stats.setOpaque(false);
		stats.setBorder(BorderFactory.createEmptyBorder(GAP, 0, 0, 0));
		stats.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (Stat stat : new Stat[]{killsLeft, timeLeft, pace, killsPerHour, elapsed, superiors, profit, xp})
		{
			stats.add(stat);
		}
		killsLeft.value.setFont(LedgerTheme.boldFont());
		killsLeft.value.setForeground(LedgerTheme.KEY);
		stats.setVisible(false);
		card.add(stats);
		SlayerLedgerPanel.addRow(content, card);

		dartHintLabel.setForeground(LedgerTheme.ALERT);
		dartHintLabel.setFont(LedgerTheme.smallFont());
		dartHintLabel.setToolTipText("<html>Blowpipe darts are counted once the plugin knows the dart type.<br>"
			+ "Use Check on the blowpipe; until then only Zulrah's scales are counted.</html>");
		dartHintLabel.setVisible(false);
		// Each of these is hidden at times; each carries its own gap, so a hidden one leaves none.
		SlayerLedgerPanel.addHideableRow(content, dartHintLabel);
		for (TitledListView<?> list : new TitledListView<?>[]{liveSupplies, liveLoot, liveXp})
		{
			list.setVisible(false);
			SlayerLedgerPanel.addHideableRow(content, list);
		}
		taskLootSection.setOpaque(false);
		taskLootSection.setVisible(false);
		SlayerLedgerPanel.addHideableRow(content, taskLootSection);
		SlayerLedgerPanel.addHideableRow(content, noteSection);
	}

	/** Asks for (or withdraws) the "Check your blowpipe" hint; the next refresh shows or hides it. */
	void setDartHint(boolean requested)
	{
		dartHintRequested = requested;
	}

	/** Updates the card from the tracker for {@code state}, with {@code remaining} kills left on the task. */
	void refresh(SessionTracker.State state, int remaining)
	{
		final long activeMillis = tracker.getActiveMillis();
		final int kills = tracker.getKills();
		refreshBar(state, remaining);
		refreshStats(state, activeMillis, kills, remaining);
		refreshNote(state);
		refreshSetup(state);
	}

	/** Updates the values, the grids, the task loot and the dart hint for {@code state}. */
	void refreshValuesAndLoot(SessionTracker.State state)
	{
		refreshValues(state);
		refreshTaskLoot(state);
		boolean hint = dartHintRequested && state == SessionTracker.State.RUNNING;
		if (dartHintLabel.isVisible() != hint)
		{
			dartHintLabel.setVisible(hint);
			relayout.run();
		}
	}

	/** Redraws the task loot in the new "Loot price" mode; the values follow it through the plugin's values. */
	void lootPriceModeChanged()
	{
		refreshTaskLoot(tracker.getState());
	}

	/** Redraws the item lists once item names arrive. */
	void itemNamesChanged()
	{
		refreshItemLists(shownValues(sessionValues, tracker.getState(), tracker.getSessionId()));
	}

	// ---- Session note ----

	private JPanel buildNoteSection()
	{
		JPanel section = new JPanel(new BorderLayout(6, 0));
		section.setOpaque(false);
		section.add(LedgerTheme.label("Note", LedgerTheme.smallFont(), LedgerTheme.LABEL), BorderLayout.WEST);

		noteBox.setEditable(true);
		noteBox.setFont(LedgerTheme.smallFont());
		// Sized from a short label, not the longest note, so a long note never widens the sidebar.
		noteBox.setPrototypeDisplayValue("Strength gear");
		noteBox.setToolTipText("<html>How you're doing this task, e.g. Barrage. Saved with the task.<br>"
			+ "Type a note and press Enter, or pick a recent one.</html>");
		noteBox.addActionListener(e ->
		{
			Object picked = noteBox.getSelectedItem();
			if (!updatingNote && "comboBoxChanged".equals(e.getActionCommand()) && picked != null)
			{
				// A pick from the list: make sure the editor shows it before it is read.
				setNoteEditorText(picked.toString());
			}
			commitNote();
		});
		noteBox.getEditor().getEditorComponent().addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent e)
			{
				commitNote();
			}
		});
		setRecentNotes(TaskNote.DEFAULTS);
		section.add(noteBox, BorderLayout.CENTER);
		section.setVisible(false);
		return section;
	}

	/** Text in the note box's editor (what the player has typed so far). */
	private String noteEditorText()
	{
		Object item = noteBox.getEditor().getItem();
		return item == null ? "" : item.toString();
	}

	/** Puts {@code text} in the note box without it counting as the player's edit. */
	private void setNoteEditorText(String text)
	{
		String shown = text == null ? "" : text;
		updatingNote = true;
		try
		{
			noteBox.setSelectedItem(shown);
			noteBox.getEditor().setItem(shown);
		}
		finally
		{
			updatingNote = false;
		}
	}

	/**
	 * Sends the typed note to the session the note box belongs to (Enter, a pick from the list, leaving the box, or
	 * End task), and shows it as it was saved (trimmed and shortened).
	 */
	void commitNote()
	{
		if (updatingNote || noteSessionId == null)
		{
			return;
		}
		String note = TaskNote.normalize(noteEditorText());
		if (!Objects.equals(note, committedNote))
		{
			committedNote = note;
			saveNote.save(noteAccount, noteSessionId, note);
		}
		if (!noteEditorText().equals(note == null ? "" : note))
		{
			setNoteEditorText(note);
		}
	}

	/** Replaces the note box's list, keeping what is in its editor. */
	void setRecentNotes(List<String> notes)
	{
		if (notes.equals(recentNotes))
		{
			return;
		}
		recentNotes = notes;
		String text = noteEditorText();
		updatingNote = true;
		try
		{
			noteBox.setModel(new DefaultComboBoxModel<>(notes.toArray(new String[0])));
		}
		finally
		{
			updatingNote = false;
		}
		setNoteEditorText(text);
	}

	/**
	 * Shows the note box while a session is active, filled with that session's note. While the player is
	 * typing (the box has focus) or picking (its list is open) the text is left alone; when the session ends, text
	 * still being typed is sent first, to the session it was typed for.
	 */
	private void refreshNote(SessionTracker.State state)
	{
		boolean active = state.isActive();
		String sessionId = active ? tracker.getSessionId() : null;
		if (noteSessionId != null && !noteSessionId.equals(sessionId))
		{
			commitNote();
			noteSessionId = null;
			noteAccount = SessionTracker.NO_ACCOUNT;
			committedNote = null;
			setNoteEditorText(null);
		}
		if (sessionId != null)
		{
			String note = tracker.getNote();
			if (noteSessionId == null)
			{
				noteSessionId = sessionId;
				noteAccount = tracker.getSessionAccountHash();
				committedNote = note;
				setNoteEditorText(note);
			}
			else if (!isNoteBeingEdited() && !Objects.equals(note, committedNote))
			{
				committedNote = note;
				setNoteEditorText(note);
			}
		}
		if (noteSection.isVisible() != active)
		{
			noteSection.setVisible(active);
			relayout.run();
		}
	}

	/** Whether the player is typing in, or picking from, the note box. */
	private boolean isNoteBeingEdited()
	{
		return noteBox.getEditor().getEditorComponent().isFocusOwner() || noteBox.isPopupVisible();
	}

	// ---- Setup tags ----

	/**
	 * Sends the overrides the player picked on a toggle to the session the toggles belong to: the active one, or one
	 * that ended since the last refresh (the plugin then writes them to the completion held for late loot, or to the
	 * saved record, {@link SlayerLedgerPlugin#setSessionSetup}).
	 */
	private void savePickedSetup(SetupOverrides overrides)
	{
		if (setupSessionId != null)
		{
			saveSetup.save(setupAccount, setupSessionId, overrides);
		}
	}

	/**
	 * Shows the setup toggles while a session is active, with its detected tags and the player's overrides; redrawn
	 * only when they change, and not while the player has a toggle's menu open.
	 */
	private void refreshSetup(SessionTracker.State state)
	{
		boolean active = state.isActive();
		String sessionId = active ? tracker.getSessionId() : null;
		if (!Objects.equals(sessionId, setupSessionId))
		{
			setupSessionId = sessionId;
			setupAccount = active ? tracker.getSessionAccountHash() : SessionTracker.NO_ACCOUNT;
			setup.clear();
		}
		if (sessionId != null)
		{
			// Over the kills that may have a context, as the tags are (not a restored 1.x task's earlier kills).
			setup.show(tracker.getSetupTags(), tracker.getSetupKills(), tracker.getContextKills());
		}
		JComponent row = setup.getComponent();
		if (row.isVisible() != active)
		{
			row.setVisible(active);
			relayout.run();
		}
	}

	/**
	 * How many times the setup toggles were redrawn. Performance seam: tests assert this doesn't grow on the refresh
	 * timer.
	 */
	int getSetupBuildCount()
	{
		return setup.getBuildCount();
	}

	/**
	 * How many times the live grids and XP list were drawn. Performance seam: tests assert this doesn't grow on the
	 * refresh timer.
	 */
	int getListsBuildCount()
	{
		return liveSupplies.getBuildCount() + liveLoot.getBuildCount() + liveXp.getBuildCount();
	}

	// ---- Bar and stats ----

	/**
	 * Shows the task's progress while a session is active and the task's size is known: the kills done of the
	 * assignment, {@code 73 / 201} (DESIGN_PRINCIPLES.md 2.4: no bar for an unknown size). It keeps its fill while
	 * paused; the status strip says so.
	 */
	private void refreshBar(SessionTracker.State state, int remaining)
	{
		int total = tracker.getCurrentOriginalAmount();
		boolean show = state.isActive() && remaining > 0 && total > 0 && remaining <= total;
		if (show)
		{
			int done = total - remaining;
			String text = LedgerFormat.progress(done, total);
			if (!text.equals(barText))
			{
				barText = text;
				bar.setMaximumValue(total);
				bar.setValue(done);
				bar.setCenterLabel(text);
				bar.setToolTipText(LedgerFormat.kills(done) + " of " + LedgerFormat.kills(total) + " kills done");
			}
		}
		if (barRow.isVisible() != show)
		{
			barRow.setVisible(show);
			relayout.run();
		}
	}

	/**
	 * Shows the stats grid while a session is active (running, away, paused or logged out); hides it otherwise. The
	 * time left stands still while paused, as active time does.
	 */
	private void refreshStats(SessionTracker.State state, long activeMillis, int kills, int remaining)
	{
		boolean active = state.isActive();
		if (active)
		{
			killsLeft.show(remaining > 0 ? LedgerFormat.kills(remaining) : LedgerFormat.UNKNOWN, LedgerTheme.KEY, null);
			refreshTimeLeft(activeMillis, kills, remaining);
			OptionalLong perKill = TaskTimeEstimate.averageKillMillis(activeMillis, kills);
			OptionalDouble perHour = TaskTimeEstimate.killsPerHour(activeMillis, kills);
			String paceTip = String.format("Shown after %d kills and %d minute of active time.",
				TaskTimeEstimate.MIN_KILLS, TaskTimeEstimate.MIN_ACTIVE_MILLIS / 60_000);
			// Not known yet: ? (DESIGN_PRINCIPLES.md 5.5), as the time left.
			pace.show(perKill.isPresent() ? LedgerFormat.pace(perKill.getAsLong()) : LedgerFormat.UNKNOWN,
				LedgerTheme.VALUE, perKill.isPresent() ? "Average active time per kill" : paceTip);
			killsPerHour.show(perHour.isPresent() ? LedgerFormat.perHour(perHour.getAsDouble()) : LedgerFormat.UNKNOWN,
				LedgerTheme.VALUE, perHour.isPresent() ? "Kills per hour of active time" : paceTip);
			elapsed.show(LedgerFormat.clock(activeMillis), LedgerTheme.VALUE,
				"Active time: time not Away, Paused or logged out");
			superiors.show(LedgerFormat.kills(tracker.getSuperiorsSpawned()), LedgerTheme.VALUE, null);
			Map<String, Long> gained = tracker.getXpGained();
			xp.show(LedgerFormat.amount(SkillXp.total(gained)), LedgerTheme.VALUE,
				SkillXp.tooltip(gained, activeMillis));
			int deathCount = tracker.getDeaths();
			int tripCount = tracker.getBankTrips();
			deaths.show(LedgerFormat.kills(deathCount), LedgerTheme.VALUE, null);
			bankTrips.show(LedgerFormat.kills(tripCount), LedgerTheme.VALUE, null);
			showCountsRow(deathCount > 0 || tripCount > 0);
			// Without rates, so the rows (and an open tooltip) don't change every second; the header has the rate.
			liveXp.setRows(SkillXp.rows(gained, 0));
			Long xpRate = SkillXp.perHour(SkillXp.total(gained), activeMillis);
			liveXp.setHeaderValue(xpRate == null ? "" : LedgerFormat.amount(xpRate) + "/h", LedgerTheme.VALUE,
				xpRate == null ? null : "XP per hour of active time");
		}
		if (stats.isVisible() != active)
		{
			stats.setVisible(active);
			relayout.run();
		}
		if (liveXp.isVisible() != active)
		{
			liveXp.setVisible(active);
			relayout.run();
		}
	}

	/** Adds Deaths and Bank trips as the grid's fifth row, or takes them out. */
	private void showCountsRow(boolean show)
	{
		boolean shown = deaths.getParent() == stats;
		if (shown == show)
		{
			return;
		}
		if (show)
		{
			stats.add(deaths);
			stats.add(bankTrips);
		}
		else
		{
			stats.remove(deaths);
			stats.remove(bankTrips);
		}
		relayout.run();
	}

	/**
	 * The time left (the overlay's formatter, so the two never disagree): at the session's pace, else the history's
	 * time per kill, else {@link #TIME_LEFT_UNKNOWN}; the tooltip says which.
	 */
	private void refreshTimeLeft(long activeMillis, int kills, int remaining)
	{
		// The same statistics as the overlay's time per kill (LedgerHistoryModel#historicalPace), which the model
		// publishes itself when they or the session change.
		TaskStatistics taskStats = model.taskStatistics(tracker.getCurrentCreatureId(), tracker.getCurrentMonster(),
			tracker.getCurrentTaskSelection(), model.currentLocation());
		Long historicalPerKill = taskStats.getSampleCount() == 0 ? null : taskStats.getMillisPerKill();
		String tooltip;
		if (!LedgerHistory.isHistoricalTimeLeft(activeMillis, kills, remaining, historicalPerKill))
		{
			tooltip = timeLeftTooltip(activeMillis, kills);
		}
		else
		{
			tooltip = String.format("<html>Historical estimate from %d completed %s%s; assumes "
				+ "the same average time per kill.<br>The live pace takes over after %d kills and %d minute of "
				+ "active time.</html>", taskStats.getSampleCount(), taskStats.getSampleCount() == 1 ? "task" : "tasks",
				taskStats.getLocation() == null ? "" : " at " + LedgerHistory.escapeHtml(taskStats.getLocation()),
				TaskTimeEstimate.MIN_KILLS, TaskTimeEstimate.MIN_ACTIVE_MILLIS / 60_000);
		}
		timeLeft.show(timeLeftText(activeMillis, kills, remaining, historicalPerKill), LedgerTheme.VALUE, tooltip);
	}

	/**
	 * The time-left value: whole minutes ({@link LedgerHistory#formatTimeLeft(long, int, int, Long)}: {@code ~9h 59m},
	 * short for the card's columns; the overlay writes the same estimate {@code ~9 h 59 min},
	 * {@link LedgerFormat#timeLeft}) at the session's pace, or the history's until the live pace takes over;
	 * {@link #TIME_LEFT_UNKNOWN} until either gives an estimate, which the card has no room to spell out (its tooltip
	 * does); {@code -} when no kills are left.
	 */
	static String timeLeftText(long activeMillis, int kills, int remaining, Long historicalMillisPerKill)
	{
		String text = LedgerHistory.formatTimeLeft(activeMillis, kills, remaining, historicalMillisPerKill);
		return LedgerHistory.TIME_LEFT_ESTIMATING.equals(text) ? TIME_LEFT_UNKNOWN : text;
	}

	/** Tooltip of the time left: the pace behind the estimate, or when an estimate will appear. */
	static String timeLeftTooltip(long activeMillis, int kills)
	{
		OptionalDouble perHour = TaskTimeEstimate.killsPerHour(activeMillis, kills);
		OptionalLong perKill = TaskTimeEstimate.averageKillMillis(activeMillis, kills);
		if (!perHour.isPresent() || !perKill.isPresent())
		{
			return String.format("Estimated after %d kills and %d minute of active time.", TaskTimeEstimate.MIN_KILLS,
				TaskTimeEstimate.MIN_ACTIVE_MILLIS / 60_000);
		}
		return String.format("<html>Kills per hour: %d<br>Average kill: %s<br>Assumes the same pace as so far.</html>",
			Math.round(perHour.getAsDouble()), LedgerHistory.formatKillTime(perKill.getAsLong()));
	}

	// ---- Values, item lists and task loot ----

	/** Live session values to show: those of the tracker's current session while it is active. */
	static SessionValuation.Values shownValues(SessionValuation.Values values, SessionTracker.State state,
		String currentSessionId)
	{
		boolean active = state.isActive();
		if (!active || values == null || !Objects.equals(values.getSessionId(), currentSessionId))
		{
			return null;
		}
		return values;
	}

	/**
	 * Shows the live session's profit in the stats and the supplies' and loot's values over their grids while a session
	 * is active and its values are known; clears them otherwise.
	 */
	private void refreshValues(SessionTracker.State state)
	{
		boolean active = state.isActive();
		if (!active)
		{
			sessionValues = null;
		}
		SessionValuation.Values values = shownValues(sessionValues, state, tracker.getSessionId());
		if (values != null)
		{
			String tooltip = LedgerHistory.valuesTooltip(values);
			long amount = values.getProfit();
			profit.show(liveProfitText(values), amount > 0 ? LedgerTheme.GOOD : amount < 0 ? LedgerTheme.ALERT
				: LedgerTheme.VALUE, tooltip);
			liveSupplies.setHeaderValue(LedgerFormat.amount(values.getSuppliesGe(), values.getSuppliesUnpriced() > 0),
				LedgerTheme.VALUE, tooltip);
			liveLoot.setHeaderValue(liveLootText(values), LedgerTheme.VALUE, tooltip);
		}
		else if (active)
		{
			profit.show(LedgerFormat.UNKNOWN, LedgerTheme.VALUE, "Shown once the session's items are valued.");
		}
		refreshItemLists(values);
	}

	/**
	 * Shows the live session's supplies and loot as item icons, with their values (from the same values, so at the same
	 * prices and "Loot price" setting), and asks for the names not known yet. The icons are only rebuilt when the rows
	 * change.
	 */
	private void refreshItemLists(SessionValuation.Values values)
	{
		boolean show = values != null;
		if (show)
		{
			ItemDetails items = values.getItems();
			// One cell per potion, though the session may count one under two dose variants.
			liveSupplies.setRows(items.supplyGridRows(model::itemName));
			// Without the items Ground Items hides, as the loot value leaves them out.
			liveLoot.setRows(items.withNatureRunePrice(model.natureRunePrice())
				.lootRows(values.getLootPriceMode(), model::itemName), values.getHiddenLoot());
			// Asks the plugin, through the model, for the names not known, asked for or failed yet.
			model.requestItemNames(items.itemIds());
		}
		if (liveSupplies.isVisible() != show)
		{
			liveSupplies.setVisible(show);
			liveLoot.setVisible(show);
			relayout.run();
		}
	}

	/**
	 * Shows the session's boss kill counts, keys, clue scrolls and notable drops while a session is active
	 * and there are any; hides them otherwise. The notable drops' values follow the "Loot price" setting; the tooltip
	 * lists the boss kill counts and every drop.
	 */
	private void refreshTaskLoot(SessionTracker.State state)
	{
		boolean active = state.isActive();
		List<NotableDrop> drops = active ? tracker.getNotableDrops() : Collections.emptyList();
		List<BossKillCount> killCounts = active ? tracker.getBossKillCounts() : Collections.emptyList();
		String taskName = active ? tracker.getSessionMonster() : null;
		LootPriceMode mode = model.lootPriceMode();
		List<String> lines = new ArrayList<>(BossKillCounts.lines(killCounts, taskName));
		if (active)
		{
			lines.addAll(LedgerHistory.liveTaskLootLines(KeysAndClues.of(tracker.getLoot()), drops, mode));
		}
		if (!lines.equals(taskLootLines))
		{
			taskLootLines = lines;
			taskLootSection.removeAll();
			taskLootSection.setLayout(new GridLayout(Math.max(1, lines.size()), 1, 0, 2));
			for (String line : lines)
			{
				FitLabel label = new FitLabel(line);
				label.setForeground(LedgerTheme.LABEL);
				label.setFont(LedgerTheme.smallFont());
				taskLootSection.add(label);
			}
			relayout.run();
		}
		String tooltip = LedgerHistory.bossKillCountTooltip(killCounts, taskName)
			+ LedgerHistory.notableDropsTooltip(drops, mode);
		// The tooltip lists the kill counts and every notable drop with its exact value; the sidebar may cut a long
		// name short.
		taskLootSection.setToolTipText(tooltip.isEmpty() ? null : "<html>" + tooltip.substring("<br>".length())
			+ "</html>");
		boolean show = !lines.isEmpty();
		if (taskLootSection.isVisible() != show)
		{
			taskLootSection.setVisible(show);
			relayout.run();
		}
	}

	/** The loot's value over its grid: in its price mode ({@code 344K}), approximate when a price is missing. */
	static String liveLootText(SessionValuation.Values values)
	{
		return LedgerFormat.amount(values.getLootValue(), values.getLootValueUnpriced() > 0);
	}

	/**
	 * The live profit: loot in its price mode minus supplies, always signed ({@code +212K}, {@code -38K}), approximate
	 * when either has an unavailable price.
	 */
	static String liveProfitText(SessionValuation.Values values)
	{
		int unpriced = values.getLootValueUnpriced() + values.getSuppliesUnpriced();
		return LedgerFormat.signed(values.getProfit(), unpriced > 0);
	}
}
