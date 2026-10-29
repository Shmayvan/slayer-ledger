package com.shmayvan.slayerledger;

import com.google.gson.Gson;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.HierarchyEvent;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar panel. All methods must be called on the Swing event dispatch thread.
 *
 * <p>The panel lays out the sidebar, runs the session buttons and notices and decides which sections redraw on which
 * change of the history model; the sections themselves are classes of their own: {@link TaskBoxView},
 * {@link LocationPromptView}, {@link HistoryEstimateSection} (with What to bring), {@link LiveSessionSection},
 * {@link HistoryListSection}, {@link TrendsSection}, {@link OverallTotalsSection}, {@link LuckMeterSection} and
 * {@link AssignmentsSection}.</p>
 */
class SlayerLedgerPanel extends PluginPanel
{
	/**
	 * The sidebar sections the "Panel sections" settings can hide. A hidden section takes no space and builds
	 * nothing; shown again, it is rebuilt from the current data.
	 */
	enum OptionalSection
	{
		WHAT_TO_BRING,
		TRENDS,
		OVERALL_TOTALS,
		LUCK_METER,
		SKIPPED_BLOCKED
	}

	private static final int REFRESH_MILLIS = 1000;
	static final String LOGGED_OUT_MESSAGE = "Log in to see your saved tasks.";
	static final String LOADING_MESSAGE = "Loading saved tasks...";
	static final String LOAD_FAILED_MESSAGE = "Unable to read saved tasks. See the client log for details.";
	static final String DELETE_FAILED_MESSAGE = "Couldn't delete this task. See the client log.";
	static final String DELETE_ENTRY_FAILED_MESSAGE = "Couldn't delete this entry. See the client log.";
	static final String EDIT_NOTE_FAILED_MESSAGE = "Couldn't save this note. See the client log.";
	static final String EDIT_LOCATION_FAILED_MESSAGE = "Couldn't save this location. See the client log.";
	/** A completed or partial task could not be saved; the record is kept and saved on the next try. */
	static final String SAVE_FAILED_MESSAGE = "Couldn't save the last task. See the client log.";
	/** Title of the inline prompt that asks the player to name the region of a kill. */
	static final String LOCATION_PROMPT_TITLE = "Name this location";
	/** First line of a saved task's item details: they are valued at today's GE prices. */
	static final String ITEMS_AT_CURRENT_PRICES = "Today's GE prices (high alch unchanged)";

	private final SlayerLedgerPlugin plugin;
	private final SessionTracker tracker;
	/**
	 * The shown account's history, its valuation, names, statistics and derived views; owned and written by
	 * the plugin. The panel observes it ({@link #modelChanged}) and its sections render what it works out; they ask it
	 * for names, on the EDT.
	 */
	private final LedgerHistoryModel historyModel;

	/** Task, remaining, master, type, next bonus, advice and the session's location. */
	private final TaskBoxView taskBox;
	/** Inline, non-modal "Name this location" prompt below the task box. */
	private final LocationPromptView locationPrompt;
	/** The History estimate for the observed task and What to bring below it. */
	private final HistoryEstimateSection historyEstimate;
	/** {@link #historyEstimate}'s What to bring with the gap above it, hidden as one by its setting. */
	private final JPanel bringBlock = new JPanel();
	/** Task history: the filter and the saved tasks. */
	private final HistoryListSection history;
	/** Task rankings and charts. */
	private final TrendsSection trends;
	/** The Trends section with the gaps above its parts, hidden as one by the "Trends" setting. */
	private final JPanel trendsBlock = new JPanel();
	/** Completed-task totals. */
	private final OverallTotalsSection overallTotals;
	/** The totals header and body with the gaps above them, hidden as one by the "Overall totals" setting. */
	private final JPanel totalsBlock = new JPanel();
	/** Bundled rare-drop comparisons. */
	private final LuckMeterSection luckMeter;
	/** The luck header and body with the gaps above them, hidden as one by the "Luck meter" setting. */
	private final JPanel luckBlock = new JPanel();
	/** Skipped / blocked: assignments that ended without a saved session; never played tasks. */
	private final AssignmentsSection assignments;
	/** The header and body with the gaps above them, hidden as one by the "Skipped / blocked" setting. */
	private final JPanel assignmentsBlock = new JPanel();

	/** The live session: progress, note, values, item lists, task loot and the dart hint. */
	private final LiveSessionSection live;
	private final JLabel savedLabel = new JLabel();
	private final JButton playButton = new JButton("Play");
	private final JButton pauseButton = new JButton("Pause");
	private final JButton stopButton = new JButton("Stop");
	private final JLabel restoredLabel = new JLabel();
	private final JPanel noticePanel = new JPanel(new BorderLayout(0, 4));
	private final JLabel noticeLabel = new JLabel();
	private final JButton noticeDiscardButton = new JButton("Discard");
	private long noticeAccount = SessionTracker.NO_ACCOUNT;
	private String noticeRecordId;
	private final Timer refreshTimer;
	/** Whether the panel is on screen, as {@link #showingChanged} last heard; EDT only. */
	private boolean showing;
	/** Set by {@link #shutDown()}: the refresh timer never starts again; EDT only. */
	private boolean stopped;

	SlayerLedgerPanel(SlayerLedgerPlugin plugin, SessionTracker tracker, LedgerHistoryModel historyModel, Gson gson)
	{
		this.plugin = plugin;
		this.tracker = tracker;
		this.historyModel = historyModel;
		taskBox = new TaskBoxView(historyModel, tracker, plugin::currentTaskStreak, this::relayout);
		locationPrompt = new LocationPromptView(gson, this::relayout, plugin::saveLocationName,
			plugin::skipLocationPrompt);
		historyEstimate = new HistoryEstimateSection(historyModel, tracker, this::relayout);
		// The sidebar's item and skill icons come from RuneLite's ItemManager and SkillIconManager, through the
		// plugin.
		SidebarIcons icons = new SidebarIcons(plugin::applyItemIcon, plugin::applySkillIcon);
		history = new HistoryListSection(historyModel, (account, recordId) ->
		{
			if (account == noticeAccount && recordId.equals(noticeRecordId))
			{
				// The notice's Discard would otherwise target a record that no longer exists.
				clearNotice();
			}
			plugin.deleteRecord(account, recordId);
		}, plugin::editRecordNote, plugin::editRecordLocation, this, this::relayout, icons);
		trends = new TrendsSection(historyModel, this::relayout, icons.getItems());
		// The masters' faces are bundled images, read when first shown.
		overallTotals = new OverallTotalsSection(historyModel, this::relayout, icons.getItems(), new MasterIcons());
		luckMeter = new LuckMeterSection(historyModel, this::relayout, icons.getItems());
		assignments = new AssignmentsSection(historyModel, this::relayout, this, (account, id) ->
		{
			history.clearError();
			plugin.deleteAssignment(account, id);
		}, icons.getItems());
		live = new LiveSessionSection(tracker, historyModel, plugin::setSessionNote, this::relayout, icons);

		setBorder(new EmptyBorder(6, 6, 6, 6));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new BorderLayout());

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("Slayer Ledger");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		addRow(content, title);
		taskBox.addTo(content);
		locationPrompt.addTo(content);
		historyEstimate.addTo(content);
		addOptionalBlock(content, bringBlock);
		historyEstimate.addBringTo(bringBlock);
		live.addTo(content);

		JPanel buttons = new JPanel(new GridLayout(1, 3, 4, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		for (JButton button : new JButton[]{playButton, pauseButton, stopButton})
		{
			// Narrow side margins so "Resume" fits a third of the panel even when its scroll bar is showing.
			button.setMargin(new Insets(2, 4, 2, 4));
			buttons.add(button);
		}
		addRow(content, buttons);

		noticePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		noticePanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 2, 0, 0, ColorScheme.BRAND_ORANGE),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)));
		noticeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		noticeLabel.setFont(FontManager.getRunescapeSmallFont());
		noticePanel.add(noticeLabel, BorderLayout.CENTER);
		noticePanel.add(noticeDiscardButton, BorderLayout.SOUTH);
		noticePanel.setVisible(false);
		addRow(content, noticePanel);

		restoredLabel.setForeground(ColorScheme.BRAND_ORANGE);
		restoredLabel.setFont(FontManager.getRunescapeSmallFont());
		restoredLabel.setVisible(false);
		addRow(content, restoredLabel);
		addRow(content, section(savedLabel));
		history.addTo(content);
		addOptionalBlock(content, trendsBlock);
		trends.addTo(trendsBlock);
		addOptionalBlock(content, totalsBlock);
		overallTotals.addTo(totalsBlock);
		addOptionalBlock(content, luckBlock);
		luckMeter.addTo(luckBlock);
		addOptionalBlock(content, assignmentsBlock);
		assignments.addTo(assignmentsBlock);

		nameComponents();
		add(content, BorderLayout.NORTH);

		playButton.addActionListener(e ->
		{
			if (plugin.play())
			{
				clearNotice();
			}
			refresh();
		});
		pauseButton.addActionListener(e ->
		{
			plugin.pause();
			refresh();
		});
		noticeDiscardButton.addActionListener(e ->
		{
			if (noticeRecordId != null)
			{
				history.clearError();
				plugin.deleteRecord(noticeAccount, noticeRecordId);
			}
			clearNotice();
		});
		stopButton.addActionListener(e ->
		{
			// Send a typed note first: Stop may not take focus from the note box, and a Keep/Discard prompt would
			// otherwise save the task without it.
			live.commitNote();
			plugin.stop();
			live.setSessionValues(null);
			refresh();
		});

		historyCleared();
		// The plugin writes the model; the panel redraws what changed. The model also ends a wait for today's prices
		// that ran out on its own (ticks, not this panel's timer).
		historyModel.setListener(this::modelChanged);
		refresh();

		// Runs only while the panel is on screen. Nothing the plugin posts or publishes depends on it.
		refreshTimer = new Timer(REFRESH_MILLIS, e -> refresh());
		// Tied to whether the panel is actually showing, not only to onActivate / onDeactivate: in RuneLite 1.13.4,
		// ClientUI.removeNavigation removes a tab before updating its sidebar entries, so the tab pane's index shift can
		// make the client (de)activate the wrong panel. Swing posts SHOWING_CHANGED on the EDT whenever the panel, or an
		// ancestor such as the sidebar tab's wrapper, is shown or hidden.
		addHierarchyListener(e ->
		{
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0)
			{
				showingChanged(isShowing());
			}
		});
	}

	/**
	 * EDT. The panel came on screen or went off it: refreshes at once and starts the one-second refresh timer, or stops
	 * it. Does nothing after {@link #shutDown()}. Called by the panel's hierarchy listener; test seam: headless tests
	 * call it directly, since no component is ever showing there.
	 */
	void showingChanged(boolean nowShowing)
	{
		showing = nowShowing;
		if (nowShowing)
		{
			startRefreshing();
		}
		else
		{
			refreshTimer.stop();
		}
	}

	/** Shows the current state at once and refreshes once a second from now, unless already running or shut down. */
	private void startRefreshing()
	{
		if (stopped || refreshTimer.isRunning())
		{
			return;
		}
		refresh();
		refreshTimer.start();
	}

	/**
	 * The client selected the panel's tab (EDT). A hint only: the timer follows whether the panel is showing
	 * ({@link #showingChanged}), so an activation while the panel is off screen (or after shutdown) starts nothing.
	 */
	@Override
	public void onActivate()
	{
		if (showing)
		{
			startRefreshing();
		}
	}

	/**
	 * The client deselected the panel's tab (EDT). A hint only: stops the timer unless the panel is still showing, so a
	 * deactivation meant for another panel can't freeze this one while it is on screen.
	 */
	@Override
	public void onDeactivate()
	{
		if (!showing)
		{
			refreshTimer.stop();
		}
	}

	/** Names the panel's own components (no visible effect), so tests can find them; the sections name theirs. */
	private void nameComponents()
	{
		bringBlock.setName("bringBlock");
		trendsBlock.setName("trendsBlock");
		totalsBlock.setName("totalsBlock");
		luckBlock.setName("luckBlock");
		assignmentsBlock.setName("assignmentsBlock");
		savedLabel.setName("savedTasks");
	}

	/** EDT. The plugin stopped: stops the refresh timer for good (a late activation or showing change starts nothing). */
	void shutDown()
	{
		stopped = true;
		refreshTimer.stop();
	}

	/** Revalidates and repaints the panel after a section changed. */
	private void relayout()
	{
		revalidate();
		repaint();
	}

	/**
	 * Shows or hides an optional section, as its "Panel sections" setting says. A hidden section takes no space,
	 * builds nothing and asks for no item names of its own (What to bring's supplies are still named for the History
	 * estimate's tooltip); shown again, it is rebuilt from the current data at once, open or closed as it was.
	 */
	void setSectionShown(OptionalSection section, boolean shown)
	{
		switch (section)
		{
			case WHAT_TO_BRING:
				bringBlock.setVisible(shown);
				historyEstimate.setBringShown(shown);
				break;
			case TRENDS:
				trendsBlock.setVisible(shown);
				trends.setShown(shown);
				break;
			case OVERALL_TOTALS:
				totalsBlock.setVisible(shown);
				overallTotals.setShown(shown);
				break;
			case LUCK_METER:
				luckBlock.setVisible(shown);
				luckMeter.setShown(shown);
				break;
			case SKIPPED_BLOCKED:
				assignmentsBlock.setVisible(shown);
				assignments.setShown(shown);
				break;
			default:
				return;
		}
		relayout();
	}

	/**
	 * Shows the live session's loot, supplies and profit values (computed on the client thread), or hides them when
	 * {@code values} is {@code null}. They are only shown while a session is running or paused, and only while their
	 * session id is the tracker's current session (values that arrive late for an ended session are ignored).
	 */
	void setSessionValues(SessionValuation.Values values)
	{
		live.setSessionValues(values);
		refresh();
	}

	/**
	 * Asks for (or withdraws) the "Check your blowpipe" hint: a blowpipe shot was counted without a known dart type
	 * (see {@link ChargedWeaponUsage#isDartCheckNeeded()}). It is shown only while a session is running.
	 */
	void setDartHint(boolean requested)
	{
		live.setDartHint(requested);
		refresh();
	}

	/** Redraws what a change of the model affects ({@link LedgerHistoryModel#setListener}); EDT. */
	private void modelChanged(LedgerHistoryModel.Change change)
	{
		switch (change)
		{
			case RECORDS:
				recordsChanged(true);
				break;
			case RECORDS_WAITING_FOR_PRICES:
				recordsChanged(false);
				break;
			case HISTORY_CLEARED:
				historyCleared();
				break;
			case ASSIGNMENTS:
				assignments.rebuild();
				// Overall totals' By master counts the entries as assignments.
				overallTotals.rebuild();
				break;
			case LOOT_PRICE_MODE:
				lootPriceModeChanged();
				break;
			case HIDDEN_LOOT:
				historyEstimate.invalidate();
				if (!historyModel.isWaitingForCurrentPrices())
				{
					// Otherwise redrawn once the prices asked for arrive.
					redrawPricedViews();
				}
				break;
			case CURRENT_PRICES:
				historyEstimate.invalidate();
				historyEstimate.refresh();
				taskBox.refreshAdvice();
				trends.rebuild();
				overallTotals.rebuild();
				if (!records().isEmpty())
				{
					history.rebuild();
				}
				break;
			case PRICED_VIEWS:
				redrawPricedViews();
				break;
			case LOCATION_NAMES:
				// A record's or the current task's location may have changed, so may the same-location estimate and
				// the history's Location filter.
				historyEstimate.invalidate();
				history.locationNamesChanged();
				refresh();
				break;
			case LOCATION_NAMES_UNCHANGED:
				refresh();
				break;
			case LOCATION_NAMES_CLEARED:
				locationPrompt.hide();
				historyEstimate.invalidate();
				history.locationNamesChanged();
				refresh();
				break;
			case ITEM_NAMES:
				itemNamesChanged();
				break;
			case TASK_ADVISOR:
				taskBox.refreshAdvice();
				break;
			case VIEWS:
				// A view worked out off the EDT is ready; each redraws only if its own result is new.
				taskBox.refreshAdvice();
				trends.viewsReady();
				overallTotals.viewsReady();
				break;
			default:
				break;
		}
	}

	/**
	 * Shows the account's new records in the history list and updates the saved count. While new items are not priced
	 * yet: the views valued at them keep showing the previous records until the prices arrive
	 * ({@code redraw} is false; they are redrawn once on {@link LedgerHistoryModel.Change#PRICED_VIEWS}).
	 */
	private void recordsChanged(boolean redraw)
	{
		history.forgetOtherAccount();
		historyEstimate.invalidate();
		savedLabel.setText("Saved tasks: " + records().size());
		history.clearError();
		history.rebuildFilter();
		if (redraw)
		{
			history.rebuild();
			trends.rebuild();
			overallTotals.rebuild();
		}
		luckMeter.rebuild();
		live.setRecentNotes(TaskNote.recentNotes(records()));
		historyEstimate.refresh();
		taskBox.refreshAdvice();
	}

	/**
	 * Shows a non-modal error above the history list (for example a failed delete) until the next successful refresh
	 * or the next delete. Ignored if {@code account}'s history is no longer shown.
	 */
	void showHistoryError(long account, String message)
	{
		history.showError(account, message);
	}

	/** Redraws the history in the new "Loot price" mode; the live values follow it through the plugin's values. */
	private void lootPriceModeChanged()
	{
		historyEstimate.invalidate();
		live.lootPriceModeChanged();
		if (historyModel.isWaitingForCurrentPrices())
		{
			// Redrawn in the new mode once the prices asked for arrive.
			return;
		}
		historyEstimate.refresh();
		taskBox.refreshAdvice();
		trends.rebuild();
		overallTotals.rebuild();
		history.rebuild();
	}

	/** Redraws every view valued at today's prices, after a wait for them ended. */
	private void redrawPricedViews()
	{
		historyEstimate.invalidate();
		historyEstimate.refresh();
		taskBox.refreshAdvice();
		trends.rebuild();
		overallTotals.rebuild();
		if (!records().isEmpty())
		{
			history.rebuild();
		}
	}

	/**
	 * Clears the history and shows the model's message instead (logged out, loading, or a load failure); before any,
	 * the logged-out message.
	 */
	private void historyCleared()
	{
		history.forgetOtherAccount();
		String message = historyModel.historyMessage();
		historyEstimate.invalidate();
		// No records, so this hides the estimate and the advice.
		historyEstimate.refresh();
		taskBox.refreshAdvice();
		savedLabel.setText("Saved tasks: -");
		history.clearError();
		history.rebuildFilter();
		history.showEmpty(message == null ? LOGGED_OUT_MESSAGE : message);
		assignments.rebuild();
		trends.rebuild();
		overallTotals.rebuild();
		luckMeter.rebuild();
		// Never offer another account's notes.
		live.setRecentNotes(TaskNote.recentNotes(records()));
	}

	/**
	 * Redraws the item lists, the estimate's tooltip, open history rows, the Overall totals item grids and the luck
	 * meter once item names arrive.
	 */
	private void itemNamesChanged()
	{
		live.itemNamesChanged();
		// The estimate's tooltip names the supplies.
		historyEstimate.invalidate();
		historyEstimate.refresh();
		history.itemNamesChanged();
		overallTotals.itemNamesChanged();
		luckMeter.itemNamesChanged();
	}

	/**
	 * Shows the inline "Name this location" prompt for map region {@code region} ({@link LocationPromptView#show}).
	 * Nothing changes when it is already shown for that region.
	 */
	void showLocationPrompt(int region, String monster, List<String> ownNames)
	{
		locationPrompt.show(region, monster, ownNames);
	}

	/** Hides the "Name this location" prompt. */
	void hideLocationPrompt()
	{
		locationPrompt.hide();
	}

	/**
	 * Shows a non-modal notice that a session ended without the player pressing Stop and was saved as partial,
	 * with a Discard button that deletes that record.
	 */
	void showPartialNotice(long account, LedgerRecord record)
	{
		noticeAccount = account;
		noticeRecordId = record.getId();
		noticeLabel.setText(String.format("<html>Previous session saved as partial (%s, %d kills).</html>",
			LedgerHistory.escapeHtml(record.getMonster()), record.getKills()));
		noticePanel.setVisible(true);
		revalidate();
		repaint();
	}

	void clearNotice()
	{
		noticeAccount = SessionTracker.NO_ACCOUNT;
		noticeRecordId = null;
		noticePanel.setVisible(false);
		revalidate();
		repaint();
	}

	/** A selector row: a small label left, {@code selector} filling the rest. */
	static JPanel selectorRow(String label, JComponent selector)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel rowLabel = new JLabel(label);
		rowLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		rowLabel.setFont(FontManager.getRunescapeSmallFont());
		row.add(rowLabel, BorderLayout.WEST);
		row.add(selector, BorderLayout.CENTER);
		return row;
	}

	/** "1 task" or "N tasks". */
	static String sampleTasks(int count)
	{
		return count + (count == 1 ? " task" : " tasks");
	}

	/** One line of a history row: {@code left} fills the line (truncated if too long), {@code right} is right-aligned. */
	static JPanel historyLine(JLabel left, JLabel right)
	{
		JPanel line = new JPanel(new BorderLayout(6, 0));
		line.setOpaque(false);
		line.setInheritsPopupMenu(true);
		left.setInheritsPopupMenu(true);
		line.add(left, BorderLayout.CENTER);
		if (right != null)
		{
			right.setInheritsPopupMenu(true);
			line.add(right, BorderLayout.EAST);
		}
		return line;
	}

	/** Green for a profit, red for a loss, grey when unknown. */
	static Color profitColor(Long profit)
	{
		if (profit == null)
		{
			return ColorScheme.LIGHT_GRAY_COLOR;
		}
		return profit < 0 ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.PROGRESS_COMPLETE_COLOR;
	}

	static JLabel historyLabel(String text, Color color)
	{
		JLabel label = new JLabel(text);
		label.setForeground(color);
		label.setFont(FontManager.getRunescapeSmallFont());
		return label;
	}

	/**
	 * Asks whether an incomplete session should be kept (saved as partial). Closing the dialog keeps the session,
	 * so the player never loses data by accident.
	 */
	boolean confirmKeepIncomplete(SessionTracker.Summary summary)
	{
		String[] options = {"Keep", "Discard"};
		String message = String.format("The %s task is not complete (%d kills, %s).%n"
				+ "Keep this session as a partial record, or discard it?",
			LedgerRecord.displayName(summary.getMonster(), summary.getCreatureId()), summary.getKills(),
			LedgerHistory.formatDuration(summary.getActiveMillis()));
		int choice = JOptionPane.showOptionDialog(this, message, "Slayer Ledger",
			JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
		return choice != 1;
	}


	/** Updates every label from the tracker; also run once a second by the refresh timer while the panel is shown. */
	void refresh()
	{
		// Ends a wait for today's prices that has lasted LedgerHistoryModel#CURRENT_PRICES_WAIT_MILLIS (an answer never
		// came): the views are redrawn with the prices there are (PRICED_VIEWS, called back by the model). The model
		// also ends it on the plugin's ticks, so nothing depends on the refresh timer.
		historyModel.endExpiredCurrentPricesWait();
		int remaining = tracker.getCurrentRemaining();
		String monster = LedgerRecord.displayName(tracker.getCurrentMonster(), tracker.getCurrentCreatureId());
		historyEstimate.refresh();
		taskBox.refreshTask(remaining, monster);

		SessionTracker.State state = tracker.getState();
		taskBox.refreshLocation(state);
		live.refresh(state, remaining);

		playButton.setEnabled(plugin.canPlay());
		playButton.setText(state == SessionTracker.State.PAUSED ? "Resume" : "Play");

		// Shown until the restored session is resumed (or ended).
		boolean restored = tracker.isRestoredPending();
		if (restored)
		{
			SessionTracker.Summary active = tracker.summary();
			restoredLabel.setText(String.format("<html>Restored previous session (%s, %d kills).</html>",
				LedgerHistory.escapeHtml(LedgerRecord.displayName(active.getMonster(), active.getCreatureId())), active.getKills()));
		}
		if (restoredLabel.isVisible() != restored)
		{
			restoredLabel.setVisible(restored);
			revalidate();
			repaint();
		}
		pauseButton.setEnabled(state == SessionTracker.State.RUNNING);
		stopButton.setEnabled(state == SessionTracker.State.RUNNING || state == SessionTracker.State.PAUSED);
		live.refreshValuesAndLoot(state);
	}

	/** The shown account's records, newest first ({@link LedgerHistoryModel#records}). */
	private List<LedgerRecord> records()
	{
		return historyModel.records();
	}

	// Seams for tests. The panel is read through its components' names (setName); these are what no component shows.

	/** The region the "Name this location" prompt asks about, or {@link LocationTally#NO_REGION} while it is hidden. */
	int getLocationPromptRegion()
	{
		// Test seam: the region is not shown in the prompt.
		return locationPrompt.getRegion();
	}

	/** Reads the bundled task location list from {@code resource} instead, before the prompt is first shown. */
	void setTaskLocationsResource(String resource)
	{
		// Test seam: lets a test point the prompt at a missing or broken list.
		locationPrompt.setTaskLocationsResource(resource);
	}

	/**
	 * Whether the one-second refresh timer runs. Performance seam: tests assert it runs only while the panel is on
	 * screen.
	 */
	boolean isRefreshTimerRunning()
	{
		return refreshTimer.isRunning();
	}

	/**
	 * How many times the history rows were built. Performance seam: tests assert this doesn't grow on the refresh
	 * timer.
	 */
	int getHistoryListBuildCount()
	{
		return history.getBuildCount();
	}

	/**
	 * How many times the History estimate's text and tooltip were built. Performance seam: tests assert this doesn't grow
	 * on the refresh timer.
	 */
	int getHistoryEstimateBuildCount()
	{
		return historyEstimate.getBuildCount();
	}

	/** How many times What to bring was built. Performance seam: tests assert this doesn't grow on the refresh timer. */
	int getBringBuildCount()
	{
		return historyEstimate.getBringBuildCount();
	}

	/**
	 * How many times Trends' shown view was built. Performance seam: tests assert this doesn't grow on the refresh
	 * timer.
	 */
	int getTrendsBuildCount()
	{
		return trends.getBuildCount();
	}

	/**
	 * How many times Overall totals' rows were built. Performance seam: tests assert this doesn't grow on the refresh
	 * timer.
	 */
	int getOverallTotalsBuildCount()
	{
		return overallTotals.getBuildCount();
	}

	/**
	 * How many times the Luck meter's rows were built. Performance seam: tests assert this doesn't grow on the refresh
	 * timer.
	 */
	int getLuckMeterBuildCount()
	{
		return luckMeter.getBuildCount();
	}

	/**
	 * How many times the Skipped / blocked rows were built. Performance seam: tests assert this doesn't grow on the
	 * refresh timer.
	 */
	int getAssignmentsBuildCount()
	{
		return assignments.getBuildCount();
	}

	static void addRow(JPanel container, JComponent row)
	{
		if (container.getComponentCount() > 0)
		{
			container.add(Box.createVerticalStrut(6));
		}
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		container.add(row);
	}

	/**
	 * Adds {@code block}, empty, as a row of {@code content} that an optional section is then built into. The gap
	 * {@link #addRow} would put above it is the block's top border instead, so hiding the block leaves no gap; the
	 * section's own rows keep their gaps inside it, so a shown section looks as before.
	 */
	static void addOptionalBlock(JPanel content, JPanel block)
	{
		block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
		block.setOpaque(false);
		block.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		block.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(block);
	}

	static JPanel section(JLabel... labels)
	{
		JPanel panel = new JPanel(new GridLayout(labels.length, 1, 0, 2));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		for (JLabel label : labels)
		{
			label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			label.setFont(FontManager.getRunescapeSmallFont());
			panel.add(label);
		}
		return panel;
	}
}
