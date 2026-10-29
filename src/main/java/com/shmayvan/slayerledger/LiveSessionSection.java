package com.shmayvan.slayerledger;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The live session rows of {@link SlayerLedgerPanel}: state, kills and elapsed time, then (while a session is running
 * or paused) time left, XP, superiors, deaths and bank trips; the session's note; its loot, supplies and profit values
 * with the item icon grids and the XP list; its boss kill counts, keys, clue scrolls and notable drops; and the "Check
 * your blowpipe" hint. Rows are added, shown or rebuilt only when they change. EDT only.
 */
final class LiveSessionSection
{
	static final String TIME_LEFT_ESTIMATING = "Time left: " + LedgerHistory.TIME_LEFT_ESTIMATING;
	static final String TIME_LEFT_NONE = "Time left: " + LedgerHistory.NO_VALUE;
	static final String DART_HINT_TEXT = "Check your blowpipe to count darts.";

	/** Saves the running or paused session's note: {@code (account, session id, note)}; {@code null} removes it. */
	@FunctionalInterface
	interface NoteSink
	{
		void save(long account, String sessionId, String note);
	}

	private final SessionTracker tracker;
	private final LedgerHistoryModel model;
	private final NoteSink saveNote;
	/** Revalidates and repaints the panel after a change. */
	private final Runnable relayout;

	private final JLabel stateLabel = new JLabel();
	private final JLabel killsLabel = new JLabel();
	private final JLabel elapsedLabel = new JLabel();
	private final JLabel timeLeftLabel = new JLabel();
	/** Total XP gained and XP per hour while a session is running or paused; the tooltip has every skill. */
	private final JLabel xpLabel = new JLabel();
	private final JLabel superiorsLabel = new JLabel();
	private final JLabel bankTripsLabel = new JLabel();
	private final JLabel deathsLabel = new JLabel();
	/** Superiors (left), deaths (centre) and bank trips (right), on one line. */
	private final JPanel countsLine = new JPanel(new BorderLayout(6, 0));
	/**
	 * State, kills, elapsed and (while a session is running or paused) time left, XP, superiors, deaths and bank trips.
	 */
	private final JPanel progressSection;
	private final JLabel lootValueLabel = new JLabel();
	private final JLabel suppliesValueLabel = new JLabel();
	private final JLabel profitValueLabel = new JLabel();
	private final JPanel valuesSection;
	/** The live session's supplies as item icons; shown with {@link #valuesSection}, closed at first. */
	private final ItemGridView liveSupplies;
	/** The live session's loot as item icons, in the "Loot price" setting, with the alch hint. */
	private final ItemGridView liveLoot;
	/**
	 * The live session's XP, skill by skill with each skill's icon; shown while a session is running or
	 * paused, closed at first.
	 */
	private final ItemListView liveXp;
	/**
	 * Boss kill counts ({@link BossKillCounts#lines}), keys, clue scrolls and notable drops
	 * ({@link LedgerHistory#liveTaskLootLines}) of the running or paused session; shown only when there are any.
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
	/** Row holding {@link #noteBox}; shown while a session is running or paused. */
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
	 * Live values last received, or {@code null} when none are known yet. Only shown while they belong to the
	 * tracker's current session.
	 */
	private SessionValuation.Values sessionValues;

	/** @param icons draws the item grids' icons and the XP list's skill icons */
	LiveSessionSection(SessionTracker tracker, LedgerHistoryModel model, NoteSink saveNote, Runnable relayout,
		SidebarIcons icons)
	{
		this.tracker = tracker;
		this.model = model;
		this.saveNote = saveNote;
		this.relayout = relayout;
		liveSupplies = new ItemGridView("Supplies used", true, ColorScheme.DARKER_GRAY_COLOR, icons.getItems());
		liveLoot = new ItemGridView("Loot received", true, ColorScheme.DARKER_GRAY_COLOR, icons.getItems());
		liveXp = new ItemListView(SkillXp.LIST_TITLE, true, ColorScheme.DARKER_GRAY_COLOR, SkillXp.LIST_NOUN,
			icons.getSkills());
		progressSection = SlayerLedgerPanel.section(stateLabel, killsLabel, elapsedLabel, timeLeftLabel);
		noteSection = buildNoteSection();
		valuesSection = SlayerLedgerPanel.section(lootValueLabel, suppliesValueLabel, profitValueLabel);
		// Names for tests, which find the parts by them; no visible effect.
		progressSection.setName("progress");
		timeLeftLabel.setName("progress.timeLeft");
		xpLabel.setName("progress.xp");
		countsLine.setName("progress.counts");
		superiorsLabel.setName("progress.superiors");
		deathsLabel.setName("progress.deaths");
		bankTripsLabel.setName("progress.bankTrips");
		noteSection.setName("note");
		noteBox.setName("note.box");
		valuesSection.setName("values");
		lootValueLabel.setName("values.loot");
		suppliesValueLabel.setName("values.supplies");
		profitValueLabel.setName("values.profit");
		liveSupplies.setName("liveSupplies");
		liveLoot.setName("liveLoot");
		liveXp.setName("liveXp");
		taskLootSection.setName("taskLoot");
		dartHintLabel.setName("dartHint");
	}

	/** Adds the progress, note, values, item lists, task loot and dart hint rows to {@code content}. */
	void addTo(JPanel content)
	{
		countsLine.setOpaque(false);
		for (JLabel label : new JLabel[]{superiorsLabel, deathsLabel, bankTripsLabel})
		{
			label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			label.setFont(FontManager.getRunescapeSmallFont());
		}
		deathsLabel.setHorizontalAlignment(JLabel.CENTER);
		countsLine.add(superiorsLabel, BorderLayout.WEST);
		countsLine.add(deathsLabel, BorderLayout.CENTER);
		countsLine.add(bankTripsLabel, BorderLayout.EAST);
		// Added back while a session is running or paused; a hidden label would still take a GridLayout row.
		progressSection.remove(timeLeftLabel);
		xpLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		xpLabel.setFont(FontManager.getRunescapeSmallFont());
		((GridLayout) progressSection.getLayout()).setRows(progressSection.getComponentCount());
		SlayerLedgerPanel.addRow(content, progressSection);
		SlayerLedgerPanel.addRow(content, noteSection);
		valuesSection.setVisible(false);
		SlayerLedgerPanel.addRow(content, valuesSection);
		for (TitledListView<?> list : new TitledListView<?>[]{liveSupplies, liveLoot, liveXp})
		{
			list.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
			list.setVisible(false);
			SlayerLedgerPanel.addRow(content, list);
		}
		taskLootSection.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		taskLootSection.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		taskLootSection.setVisible(false);
		SlayerLedgerPanel.addRow(content, taskLootSection);
		dartHintLabel.setForeground(ColorScheme.BRAND_ORANGE);
		dartHintLabel.setFont(FontManager.getRunescapeSmallFont());
		dartHintLabel.setToolTipText("<html>Blowpipe darts are counted once the plugin knows the dart type.<br>"
			+ "Use Check on the blowpipe; until then only Zulrah's scales are counted.</html>");
		dartHintLabel.setVisible(false);
		SlayerLedgerPanel.addRow(content, dartHintLabel);
	}

	/** Keeps the live values to show ({@link SlayerLedgerPanel#setSessionValues}); the next refresh shows them. */
	void setSessionValues(SessionValuation.Values values)
	{
		sessionValues = values;
	}

	/** Asks for (or withdraws) the "Check your blowpipe" hint; the next refresh shows or hides it. */
	void setDartHint(boolean requested)
	{
		dartHintRequested = requested;
	}

	/** Updates every row from the tracker for {@code state}, with {@code remaining} kills left on the task. */
	void refresh(SessionTracker.State state, int remaining)
	{
		stateLabel.setText("State: " + stateText(state));
		final long activeMillis = tracker.getActiveMillis();
		final int kills = tracker.getKills();
		killsLabel.setText("Kills: " + kills);
		elapsedLabel.setText("Elapsed: " + LedgerHistory.formatDuration(activeMillis));
		refreshActiveRows(state, activeMillis, kills, remaining);
		refreshNote(state);
	}

	/** Updates the values, item lists, task loot and dart hint for {@code state}. */
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
		section.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		section.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		JLabel label = new JLabel("Note");
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		section.add(label, BorderLayout.WEST);

		noteBox.setEditable(true);
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
	 * Stop), and shows it as it was saved (trimmed and shortened).
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
	 * Shows the note box while a session is running or paused, filled with that session's note. While the player is
	 * typing (the box has focus) or picking (its list is open) the text is left alone; when the session ends, text
	 * still being typed is sent first, to the session it was typed for.
	 */
	private void refreshNote(SessionTracker.State state)
	{
		boolean active = state == SessionTracker.State.RUNNING || state == SessionTracker.State.PAUSED;
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

	// ---- Progress ----

	/**
	 * Shows the estimated time left (it stands still while paused, as active time does), the session's XP (the total
	 * line and the per-skill list) and its superior spawns, deaths and bank trips while a session is running or
	 * paused; hides them otherwise.
	 */
	private void refreshActiveRows(SessionTracker.State state, long activeMillis, int kills, int remaining)
	{
		boolean active = state == SessionTracker.State.RUNNING || state == SessionTracker.State.PAUSED;
		if (active)
		{
			// The same statistics as the overlay's time per kill (LedgerHistoryModel#historicalPace), which the model
			// publishes itself when they or the session change.
			TaskStatistics stats = model.taskStatistics(tracker.getCurrentCreatureId(), tracker.getCurrentMonster(),
				tracker.getCurrentTaskSelection(), model.currentLocation());
			Long historicalPerKill = stats.getSampleCount() == 0 ? null : stats.getMillisPerKill();
			timeLeftLabel.setText(timeLeftText(activeMillis, kills, remaining, historicalPerKill));
			if (!LedgerHistory.isHistoricalTimeLeft(activeMillis, kills, remaining, historicalPerKill))
			{
				timeLeftLabel.setToolTipText(timeLeftTooltip(activeMillis, kills));
			}
			else
			{
				timeLeftLabel.setToolTipText(String.format("<html>Historical estimate from %d completed %s%s; assumes "
					+ "the same average time per kill.<br>The live pace takes over after %d kills and %d minute of "
					+ "active time.</html>", stats.getSampleCount(), stats.getSampleCount() == 1 ? "task" : "tasks",
					stats.getLocation() == null ? "" : " at " + LedgerHistory.escapeHtml(stats.getLocation()),
					TaskTimeEstimate.MIN_KILLS, TaskTimeEstimate.MIN_ACTIVE_MILLIS / 60_000));
			}
			superiorsLabel.setText(LedgerHistory.formatSuperiors(tracker.getSuperiorsSpawned()));
			bankTripsLabel.setText(LedgerHistory.formatBankTrips(tracker.getBankTrips()));
			deathsLabel.setText(LedgerHistory.formatDeaths(tracker.getDeaths()));
			Map<String, Long> xp = tracker.getXpGained();
			xpLabel.setText(SkillXp.totalText(xp, activeMillis));
			xpLabel.setToolTipText(SkillXp.tooltip(xp, activeMillis));
			// Without rates, so the rows (and an open tooltip) don't change every second; the total line has the rate.
			liveXp.setRows(SkillXp.rows(xp, 0));
		}
		if (liveXp.isVisible() != active)
		{
			liveXp.setVisible(active);
			relayout.run();
		}
		boolean shown = timeLeftLabel.getParent() == progressSection;
		if (shown != active)
		{
			if (active)
			{
				progressSection.add(timeLeftLabel);
				progressSection.add(xpLabel);
				progressSection.add(countsLine);
			}
			else
			{
				progressSection.remove(timeLeftLabel);
				progressSection.remove(xpLabel);
				progressSection.remove(countsLine);
			}
			((GridLayout) progressSection.getLayout()).setRows(progressSection.getComponentCount());
			relayout.run();
		}
	}

	/**
	 * Text of the time-left line: whole minutes ({@link LedgerHistory#formatTimeLeftMinutes}) at the session's pace
	 * so far (see {@link TaskTimeEstimate}), "estimating" until there are enough kills and active time, or {@code -}
	 * when no kills are left on the task (no task, or the task is done).
	 */
	static String timeLeftText(long activeMillis, int kills, int remaining)
	{
		return timeLeftText(activeMillis, kills, remaining, null);
	}

	/**
	 * Text of the time-left line with the task's historical time per kill (or {@code null}): the historical estimate
	 * until the live pace takes over ({@link LedgerHistory#formatTimeLeft(long, int, int, Long)}, as the overlay).
	 */
	static String timeLeftText(long activeMillis, int kills, int remaining, Long historicalMillisPerKill)
	{
		return "Time left: " + LedgerHistory.formatTimeLeft(activeMillis, kills, remaining, historicalMillisPerKill);
	}

	/** Tooltip of the time-left line: the pace behind the estimate, or when an estimate will appear. */
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

	private static String stateText(SessionTracker.State state)
	{
		switch (state)
		{
			case RUNNING:
				return "Running";
			case PAUSED:
				return "Paused";
			case STOPPED:
				return "Stopped";
			default:
				return "Idle";
		}
	}

	// ---- Values, item lists and task loot ----

	/** Live session values to show: those of the tracker's current session while it is running or paused. */
	static SessionValuation.Values shownValues(SessionValuation.Values values, SessionTracker.State state,
		String currentSessionId)
	{
		boolean active = state == SessionTracker.State.RUNNING || state == SessionTracker.State.PAUSED;
		if (!active || values == null || !Objects.equals(values.getSessionId(), currentSessionId))
		{
			return null;
		}
		return values;
	}

	/** Shows the live session values while a session is running or paused; clears them otherwise. */
	private void refreshValues(SessionTracker.State state)
	{
		boolean active = state == SessionTracker.State.RUNNING || state == SessionTracker.State.PAUSED;
		if (!active)
		{
			sessionValues = null;
		}
		SessionValuation.Values values = shownValues(sessionValues, state, tracker.getSessionId());
		if (values != null)
		{
			long profit = values.getProfit();
			lootValueLabel.setText(liveLootText(values));
			suppliesValueLabel.setText("Supplies: "
				+ LedgerHistory.formatGp(values.getSuppliesGe(), values.getSuppliesUnpriced() > 0));
			profitValueLabel.setText(liveProfitText(values));
			profitValueLabel.setForeground(SlayerLedgerPanel.profitColor(profit));
			valuesSection.setToolTipText(LedgerHistory.valuesTooltip(values));
		}
		boolean show = values != null;
		if (valuesSection.isVisible() != show)
		{
			valuesSection.setVisible(show);
			relayout.run();
		}
		refreshItemLists(values);
	}

	/**
	 * Shows the live session's supplies and loot as item icons, with {@link #valuesSection} (from the same values, so
	 * at the same prices and "Loot price" setting), and asks for the names not known yet. The icons are only rebuilt
	 * when the rows change.
	 */
	private void refreshItemLists(SessionValuation.Values values)
	{
		boolean show = values != null;
		if (show)
		{
			ItemDetails items = values.getItems();
			liveSupplies.setRows(items.supplyRows(model::itemName));
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
	 * Shows the session's boss kill counts, keys, clue scrolls and notable drops while a session is running or paused
	 * and there are any; hides them otherwise. The notable drops' values follow the "Loot price" setting; the tooltip
	 * lists the boss kill counts and every drop.
	 */
	private void refreshTaskLoot(SessionTracker.State state)
	{
		boolean active = state == SessionTracker.State.RUNNING || state == SessionTracker.State.PAUSED;
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
				JLabel label = new JLabel(line);
				label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				label.setFont(FontManager.getRunescapeSmallFont());
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

	/**
	 * The live loot line: the loot in its price mode, labelled with the mode ("Loot (GE): ..." or "Loot (HA): ...").
	 * The other figure is in the tooltip.
	 */
	static String liveLootText(SessionValuation.Values values)
	{
		return LedgerHistory.lootLabel(values.getLootPriceMode())
			+ LedgerHistory.formatGp(values.getLootValue(), values.getLootValueUnpriced() > 0);
	}

	/**
	 * The live profit line: loot in its price mode minus supplies, labelled with the mode; approximate when either has
	 * an unavailable price.
	 */
	static String liveProfitText(SessionValuation.Values values)
	{
		return LedgerHistory.profitLabel(values.getLootPriceMode()) + LedgerHistory.formatLiveProfit(values);
	}
}
