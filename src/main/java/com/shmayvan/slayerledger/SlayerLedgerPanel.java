package com.shmayvan.slayerledger;

import java.awt.*;
import java.awt.event.*;
import java.util.List;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.*;
import net.runelite.client.ui.*;

/**
 * Sidebar panel. All methods must be called on the Swing event dispatch thread.
 *
 * <p>The panel lays out the sidebar in the order of DESIGN_PRINCIPLES.md 3.2: the status strip (the task's
 * state, Pause or Resume, and End task or Track again), the live task ({@link LiveSessionSection}, with the card's
 * header {@link TaskBoxView}), the advisor card ({@link AdvisorCardSection}, phase 9b, which replaced the 1.x History
 * estimate and What to bring), the task history ({@link HistoryListSection}), {@link OverallTotalsSection} and
 * {@link AssignmentsSection}. It runs the status strip's buttons and the notices and decides which sections redraw on
 * which change of the history model; the sections are classes of their own. The page is
 * {@link LedgerTheme#PAGE}, direction A's stone.</p>
 */
class SlayerLedgerPanel extends PluginPanel
{
	/**
	 * The sidebar sections the "Panel sections" settings can hide. A hidden section takes no space and builds
	 * nothing; shown again, it is rebuilt from the current data.
	 */
	enum OptionalSection
	{
		OVERALL_TOTALS,
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
	static final String EDIT_SETUP_FAILED_MESSAGE = "Couldn't save this setup. See the client log.";
	/** A completed or partial task could not be saved; the record is kept and saved on the next try. */
	static final String SAVE_FAILED_MESSAGE = "Couldn't save the last task. See the client log.";
	/** Araxxor's harvest could not be added to the task its kill completed, which was already saved. */
	static final String HARVEST_FAILED_MESSAGE = "Couldn't add Araxxor's loot to the last task. See the client log.";
	/** First line of a saved task's item details: they are valued at today's GE prices. */
	static final String ITEMS_AT_CURRENT_PRICES = "Today's GE prices (high alch unchanged)";
	// The status strip's buttons' texts; a click acts on the text its button shows.
	static final String PAUSE = "Pause";
	static final String RESUME = "Resume";
	static final String END_TASK = "End task";
	static final String TRACK_AGAIN = "Track again";

	private final SlayerLedgerPlugin plugin;
	private final SessionTracker tracker;
	/**
	 * The shown account's history, its valuation, names, statistics and derived views; owned and written by
	 * the plugin. The panel observes it ({@link #modelChanged}) and its sections render what it works out; they ask it
	 * for names, on the EDT.
	 */
	private final LedgerHistoryModel historyModel;

	/** The live task card's header: the task's icon and name, its location, master and next bonus. */
	private final TaskBoxView taskBox;
	/** The advisor card: the plan for the task. */
	private final AdvisorCardSection advisor;
	/** Task history: the filter and the saved tasks. */
	private final HistoryListSection history;
	/** Completed-task totals. */
	private final OverallTotalsSection overallTotals;
	/** The totals header and body with the gaps above them, hidden as one by the "Overall totals" setting. */
	private final JPanel totalsBlock = new JPanel();
	/** Skipped / blocked: assignments that ended without a saved session; never played tasks. */
	private final AssignmentsSection assignments;
	/** The header and body with the gaps above them, hidden as one by the "Skipped / blocked" setting. */
	private final JPanel assignmentsBlock = new JPanel();

	/** The live task: the card, the grids, the XP, the task loot, the dart hint and the note. */
	private final LiveSessionSection live;
	private final JLabel savedLabel = new JLabel();
	/** The status strip (DESIGN_PRINCIPLES.md 3.4a): the state word and clock on the left, the buttons on the right. */
	private final JPanel statusStrip = new JPanel(new BorderLayout(6, 0));
	/** What tracking is doing ({@link SessionTracker#getStatus()}), in words: "Running", "Waiting for first kill". */
	private final JLabel stateLabel = new JLabel();
	/** The elapsed clock beside "Running", in the hint colour; empty otherwise. */
	private final JLabel clockLabel = new JLabel();
	/** Pause while running, Resume while Paused; hidden otherwise. A click acts on the text shown. */
	private final JButton pauseButton = new JButton(PAUSE);
	/** End task while there is something to end; Track again once the assignment is Ended; hidden otherwise. */
	private final JButton endButton = new JButton(END_TASK);
	/** The gap between the two buttons, shown only while both are. */
	private final Component buttonGap = Box.createHorizontalStrut(4);
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

	SlayerLedgerPanel(SlayerLedgerPlugin plugin, SessionTracker tracker, LedgerHistoryModel historyModel)
	{
		this.plugin = plugin;
		this.tracker = tracker;
		this.historyModel = historyModel;
		// The sidebar's item and skill icons come from RuneLite's ItemManager and SkillIconManager, through the
		// plugin; the setup tags' images from ItemManager and SpriteManager, each asked for once for the live
		// card's toggles and the Task history rows.
		SidebarIcons icons = new SidebarIcons(plugin::applyItemIcon, plugin::applySkillIcon,
			new SetupImages(plugin::loadSetupIcon));
		taskBox = new TaskBoxView(historyModel, tracker, plugin::currentTaskStreak, this::relayout, icons.getItems());
		advisor = new AdvisorCardSection(historyModel, tracker, this::relayout, icons.getItems());
		history = new HistoryListSection(historyModel, (account, recordId) ->
		{
			if (account == noticeAccount && recordId.equals(noticeRecordId))
			{
				// The notice's Discard would otherwise target a record that no longer exists.
				clearNotice();
			}
			plugin.deleteRecord(account, recordId);
		}, plugin::editRecordNote, plugin::editRecordLocation, plugin::editRecordSetup, this, this::relayout, icons);
		// Renaming a region the spot table doesn't list names it for every task there.
		history.setRenamePlace(plugin::saveLocationName);
		// The masters' faces are bundled images, read when first shown.
		overallTotals = new OverallTotalsSection(historyModel, this::relayout, icons.getItems(), new MasterIcons());
		assignments = new AssignmentsSection(historyModel, this::relayout, this, (account, id) ->
		{
			history.clearError();
			plugin.deleteAssignment(account, id);
		}, icons.getItems());
		live = new LiveSessionSection(tracker, historyModel, taskBox, plugin::setSessionNote, plugin::setSessionSetup,
			this::relayout, icons);

		setBorder(new EmptyBorder(6, 6, 6, 6));
		setBackground(LedgerTheme.PAGE);
		setLayout(new BorderLayout());
		// The page under the panel's own content, down to the bottom of the sidebar: the scroll pane's view and
		// viewport, which this panel's PluginPanel constructor made.
		Container north = getParent();
		if (north != null)
		{
			north.setBackground(LedgerTheme.PAGE);
		}
		if (getScrollPane() != null)
		{
			getScrollPane().getViewport().setBackground(LedgerTheme.PAGE);
		}

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(LedgerTheme.PAGE);

		buildStatusStrip();
		addRow(content, statusStrip);

		noticePanel.setOpaque(false);
		noticeLabel.setForeground(LedgerTheme.LABEL);
		noticeLabel.setFont(LedgerTheme.smallFont());
		noticePanel.add(noticeLabel, BorderLayout.CENTER);
		JPanel discardRow = new JPanel(new BorderLayout());
		discardRow.setOpaque(false);
		styleTextButton(noticeDiscardButton);
		discardRow.add(noticeDiscardButton, BorderLayout.WEST);
		noticePanel.add(discardRow, BorderLayout.SOUTH);
		noticePanel.setVisible(false);
		addHideableRow(content, noticePanel);

		restoredLabel.setForeground(LedgerTheme.LABEL);
		restoredLabel.setFont(LedgerTheme.smallFont());
		restoredLabel.setVisible(false);
		addHideableRow(content, restoredLabel);

		live.addTo(content);
		advisor.addTo(content);
		addRow(content, section(savedLabel));
		history.addTo(content);
		addOptionalBlock(content, totalsBlock);
		overallTotals.addTo(totalsBlock);
		addOptionalBlock(content, assignmentsBlock);
		assignments.addTo(assignmentsBlock);

		nameComponents();
		add(content, BorderLayout.NORTH);

		// Each button acts on what it showed, not on the state at the click: a kill may have restarted the session (or
		// a new assignment cleared Ended) since the last refresh, and then the click does nothing. Each action checks
		// its own state again (resume only while Paused, pause only while Running, Track again only while Ended, End
		// task only while there is something to end).
		pauseButton.addActionListener(e ->
		{
			if (RESUME.equals(pauseButton.getText()))
			{
				plugin.resume();
			}
			else
			{
				plugin.pause();
			}
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
		endButton.addActionListener(e ->
		{
			if (TRACK_AGAIN.equals(endButton.getText()))
			{
				plugin.trackAgain();
				refresh();
				return;
			}
			// Send a typed note first: End task may not take focus from the note box, and a Keep/Discard prompt would
			// otherwise save the task without it.
			live.commitNote();
			plugin.endTask();
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

	/**
	 * Builds the status strip (DESIGN_PRINCIPLES.md 3.4a): one flat line, the state word (Plain) and, while running,
	 * the elapsed clock (Small, hint colour) on the left; the valid buttons as small text buttons on the right.
	 */
	private void buildStatusStrip()
	{
		statusStrip.setOpaque(false);
		stateLabel.setFont(LedgerTheme.plainFont());
		stateLabel.setForeground(LedgerTheme.LABEL);
		clockLabel.setFont(LedgerTheme.smallFont());
		clockLabel.setForeground(LedgerTheme.HINT);
		JPanel left = new JPanel(new BorderLayout(4, 0));
		left.setOpaque(false);
		left.add(stateLabel, BorderLayout.WEST);
		left.add(clockLabel, BorderLayout.CENTER);
		statusStrip.add(left, BorderLayout.CENTER);
		// A box layout gives a hidden button no room, so only the valid ones take space.
		JPanel buttons = new JPanel();
		buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
		buttons.setOpaque(false);
		styleTextButton(pauseButton);
		styleTextButton(endButton);
		buttons.add(pauseButton);
		buttons.add(buttonGap);
		buttons.add(endButton);
		statusStrip.add(buttons, BorderLayout.EAST);
	}

	/**
	 * A small text button of the page (DESIGN_PRINCIPLES.md 3.4a): Small text in the value colour on the card's stone,
	 * framed by a 1 px rule. Flat: no focus ring, square corners.
	 */
	static void styleTextButton(JButton button)
	{
		button.setFont(LedgerTheme.smallFont());
		button.setForeground(LedgerTheme.VALUE);
		button.setBackground(LedgerTheme.CARD);
		button.setFocusPainted(false);
		button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LedgerTheme.BEVEL_INNER, 1),
			BorderFactory.createEmptyBorder(3, 6, 2, 6)));
	}

	/** Names the panel's own components (no visible effect), so tests can find them; the sections name theirs. */
	private void nameComponents()
	{
		totalsBlock.setName("totalsBlock");
		assignmentsBlock.setName("assignmentsBlock");
		savedLabel.setName("savedTasks");
		statusStrip.setName("session");
		stateLabel.setName("session.state");
		clockLabel.setName("session.clock");
		pauseButton.setName("session.pauseResume");
		endButton.setName("session.endTask");
		noticePanel.setName("partialNotice");
		noticeLabel.setName("partialNotice.text");
		noticeDiscardButton.setName("partialNotice.discard");
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
	 * builds nothing and asks for no item names of its own; shown again, it is rebuilt from the current data at once,
	 * open or closed as it was.
	 */
	void setSectionShown(OptionalSection section, boolean shown)
	{
		switch (section)
		{
			case OVERALL_TOTALS:
				totalsBlock.setVisible(shown);
				overallTotals.setShown(shown);
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
	 * {@code values} is {@code null}. They are only shown while a session is active, and only while their
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
				if (!historyModel.isWaitingForCurrentPrices())
				{
					// Otherwise redrawn once the prices asked for arrive.
					redrawPricedViews();
				}
				break;
			case CURRENT_PRICES:
				advisor.modelChanged();
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
				// A record's or the current task's location may have changed, so may the history's Location filter and
				// the advisor's names of the player's regions.
				history.locationNamesChanged();
				advisor.locationNamesChanged();
				refresh();
				break;
			case LOCATION_NAMES_UNCHANGED:
				refresh();
				break;
			case LOCATION_NAMES_CLEARED:
				history.locationNamesChanged();
				advisor.locationNamesChanged();
				refresh();
				break;
			case ITEM_NAMES:
				itemNamesChanged();
				break;
			case VIEWS:
				// A view worked out off the EDT is ready; each redraws only if its own result is new.
				advisor.modelChanged();
				overallTotals.viewsReady();
				break;
			case LIVE:
				// The task, its kills left or the session changed: only the advisor card reads them outside the
				// panel's refresh, and it reads only the tracker.
				advisor.refresh();
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
		savedLabel.setText("Saved tasks: " + records().size());
		history.clearError();
		history.rebuildFilter();
		if (redraw)
		{
			history.rebuild();
			overallTotals.rebuild();
		}
		live.setRecentNotes(TaskNote.recentNotes(records()));
		advisor.modelChanged();
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
		live.lootPriceModeChanged();
		if (historyModel.isWaitingForCurrentPrices())
		{
			// Redrawn in the new mode once the prices asked for arrive.
			return;
		}
		advisor.modelChanged();
		overallTotals.rebuild();
		history.rebuild();
	}

	/** Redraws every view valued at today's prices, after a wait for them ended. */
	private void redrawPricedViews()
	{
		advisor.modelChanged();
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
		// No records, so this hides the advisor card.
		advisor.modelChanged();
		savedLabel.setText("Saved tasks: -");
		history.clearError();
		history.rebuildFilter();
		// No records: the list shows the model's message, as on any later redraw.
		history.rebuild();
		assignments.rebuild();
		overallTotals.rebuild();
		// Never offer another account's notes.
		live.setRecentNotes(TaskNote.recentNotes(records()));
	}

	/**
	 * Redraws the item lists, the advisor card's grids, open history rows and the Overall totals item grids once item
	 * names arrive.
	 */
	private void itemNamesChanged()
	{
		live.itemNamesChanged();
		advisor.itemNamesChanged();
		history.itemNamesChanged();
		overallTotals.itemNamesChanged();
	}

	/**
	 * Shows a non-modal notice that a session ended without the player pressing End task and was saved as partial,
	 * with a Discard button that deletes that record. It stays while the next session runs, until Discard, the
	 * record's delete, or the save of a later session ({@link #sessionSaved}).
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

	/**
	 * EDT. The session {@code recordId} was saved: a partial notice for an earlier session goes, since a later one is
	 * now saved. The notice for this very record (posted with its save) stays.
	 */
	void sessionSaved(String recordId)
	{
		if (noticeRecordId != null && !noticeRecordId.equals(recordId))
		{
			clearNotice();
		}
	}

	/**
	 * EDT. Another account is logged in: a partial notice for a session of an earlier account goes, since its Discard
	 * would act on a history this account doesn't show.
	 */
	void accountChanged(long account)
	{
		if (noticeRecordId != null && noticeAccount != account)
		{
			clearNotice();
		}
	}

	void clearNotice()
	{
		noticeAccount = SessionTracker.NO_ACCOUNT;
		noticeRecordId = null;
		noticePanel.setVisible(false);
		revalidate();
		repaint();
	}

	/** "1 task" or "N tasks". */
	static String sampleTasks(int count)
	{
		return count + (count == 1 ? " task" : " tasks");
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
		taskBox.refreshTask(remaining, monster);
		advisor.refresh();

		SessionTracker.State state = tracker.getState();
		taskBox.refreshLocation(state);
		live.refresh(state, remaining);
		refreshStatusStrip(state);
		// The partial notice stays while the next session runs: it goes with Discard, or when a later session is saved
		// (sessionSaved).

		// Shown until the restored session restarts (or ends).
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
		live.refreshValuesAndLoot(state);
	}

	/**
	 * Shows the state in words (V2 plan 4.1; DESIGN_PRINCIPLES.md 3.4a): "Running" with the elapsed clock, else the
	 * status in the label colour; and only the valid buttons: Pause while running, Resume while Paused (pressable once
	 * a restore check is over), End task while there is something to end, Track again once the assignment is Ended.
	 */
	private void refreshStatusStrip(SessionTracker.State state)
	{
		SessionTracker.Status status = tracker.getStatus();
		boolean running = status == SessionTracker.Status.RUNNING;
		stateLabel.setText(status.getLabel());
		// The word carries the state; the colour only adds to it.
		stateLabel.setForeground(running ? LedgerTheme.GOOD : LedgerTheme.LABEL);
		clockLabel.setText(running ? LedgerFormat.clock(tracker.getActiveMillis()) : "");
		boolean paused = state == SessionTracker.State.PAUSED;
		pauseButton.setText(paused ? RESUME : PAUSE);
		pauseButton.setEnabled(!paused || plugin.canResume());
		boolean ended = status == SessionTracker.Status.ENDED;
		endButton.setText(ended ? TRACK_AGAIN : END_TASK);
		boolean changed = setShown(pauseButton, paused || state == SessionTracker.State.RUNNING);
		changed |= setShown(endButton, ended || tracker.canEndTask());
		buttonGap.setVisible(pauseButton.isVisible() && endButton.isVisible());
		if (changed)
		{
			relayout();
		}
	}

	/** Shows or hides {@code component}; whether that changed anything. */
	private static boolean setShown(JComponent component, boolean shown)
	{
		if (component.isVisible() == shown)
		{
			return false;
		}
		component.setVisible(shown);
		return true;
	}

	/** The shown account's records, newest first ({@link LedgerHistoryModel#records}). */
	private List<LedgerRecord> records()
	{
		return historyModel.records();
	}

	// Seams for tests. The panel is read through its components' names (setName); these are what no component shows.

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
	 * How many times Overall totals' rows were built. Performance seam: tests assert this doesn't grow on the refresh
	 * timer.
	 */
	int getOverallTotalsBuildCount()
	{
		return overallTotals.getBuildCount();
	}

	/**
	 * How many times the live setup toggles were redrawn. Performance seam: tests assert this doesn't grow on the
	 * refresh timer.
	 */
	int getLiveSetupBuildCount()
	{
		return live.getSetupBuildCount();
	}

	/**
	 * How many times the live task card's icon was asked for. Performance seam: tests assert this doesn't grow on the
	 * refresh timer.
	 */
	int getLiveCardBuildCount()
	{
		return taskBox.getBuildCount();
	}

	/**
	 * How many times the live grids and XP list were drawn. Performance seam: tests assert this doesn't grow on the
	 * refresh timer.
	 */
	int getLiveListsBuildCount()
	{
		return live.getListsBuildCount();
	}

	/**
	 * How many times the advisor card's content was built. Performance seam: tests assert this doesn't grow on the
	 * refresh timer.
	 */
	int getAdvisorCardBuildCount()
	{
		return advisor.getBuildCount();
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
	 * Adds {@code row}, which is often hidden, to {@code container} with the gap {@link #addRow} would put above it as
	 * its own top border, so a hidden row leaves no gap.
	 */
	static void addHideableRow(JPanel container, JComponent row)
	{
		row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0), row.getBorder()));
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

	/** A box of Small lines on RuneLite's grey (the saved-task count, until the history's redesign, phase 9c). */
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
