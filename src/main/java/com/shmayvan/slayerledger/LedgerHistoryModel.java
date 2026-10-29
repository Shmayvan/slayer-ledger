package com.shmayvan.slayerledger;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The shown account's saved history and everything worked out from it: its records and "Skipped /
 * blocked" entries, the new-task chat preview and the overlay's historical time per kill. Since the off-EDT views the
 * rest is kept by three collaborators it owns: how loot is valued ({@link HistoryValuation}: the "Loot price" mode,
 * today's GE prices and the wait for them), the player's region names and item names ({@link NameBook}) and the sidebar's
 * derived views ({@link LedgerViews}: the per-task statistics, the task advisor, Trends, Overall totals, the luck
 * meter, the "Skipped / blocked" rows and What to bring). The model is still the one entry point: callers read and
 * write through it, and it decides what each change means and tells its listener ({@link Change}). The plugin creates
 * and owns it and writes to it; the panel observes it ({@link #setListener}) and only renders the immutable results it
 * returns.
 *
 * <p>Threading: EDT only. Every method, including the getters, must be called on the Swing event dispatch thread, as
 * the panel's were; the exceptions are the static {@link #liveInputs} and the instance method
 * {@link #isWaitingForCurrentPrices}, which reads one volatile field (in {@link HistoryValuation}). Nothing here is
 * synchronized. The advice, Trends and Overall totals are worked out on a background executor from an immutable
 * snapshot and handed back on the EDT ({@link LedgerViews}): they return {@code null} until ready, then the
 * listener is told {@link Change#VIEWS}.
 * The tracker it reads is synchronized, so the EDT may read it. The plugin's callbacks it calls
 * ({@code requestLocationNames}, {@code requestCurrentPrices} and {@code requestItemNames}, which the plugin's
 * {@link ClientLookups} serves, {@code postAssignmentPreview} and {@code publishHistoricalPace}) are the plugin's EDT
 * entry points: they queue client-thread work or write one volatile field. The panel's listener
 * ({@link #setListener}) is told of every change, on the EDT and after the change is complete, and redraws what it
 * shows. The lists and views it returns are unmodifiable and replaced, never changed, when what they come from
 * changes.</p>
 *
 * <p>Derived views are worked out on demand and cached until what they come from changes: the records
 * ({@link #version}), how they are valued ({@link #valuationVersion}), item names where they are shown, and the view's
 * own inputs (a metric, a grouping, a task). Asking again with nothing changed returns the same object, so the panel's
 * refresh timer never works them out again. The per-task statistics stay on the EDT: the chat preview and the
 * overlay's time per kill decide from them at once.</p>
 *
 * <p>No Swing or AWT: whether a chat line is posted, what the overlay is told and when a wait for today's prices runs
 * out are decided here, not by a refresh timer. The plugin re-checks them ({@link #refreshLive}) whenever the live task
 * or session changes ({@link #liveInputs}), and ends an expired wait for today's prices ({@link
 * #endExpiredCurrentPricesWait}) on the settled ticks while one runs; the panel's own refresh also ends an expired wait
 * and re-checks the preview. The preview is also re-checked whenever what it waits for arrives ({@link #setRecords},
 * {@link #addLocationNames}, {@link #endCurrentPricesWait}).</p>
 */
final class LedgerHistoryModel
{
	/** See {@link HistoryValuation#CURRENT_PRICES_WAIT_MILLIS}. */
	static final long CURRENT_PRICES_WAIT_MILLIS = HistoryValuation.CURRENT_PRICES_WAIT_MILLIS;

	private final SessionTracker tracker;
	private final Consumer<String> previewPoster;
	private final Consumer<OverlayRows.HistoricalPace> pacePublisher;
	/** How the history is valued. */
	private final HistoryValuation valuation;
	/** Region and item names. */
	private final NameBook names;
	/** The cached derived views. */
	private final LedgerViews views;
	/**
	 * Which of the records' loot ids Ground Items' hidden list hides, from the item names; the result is kept
	 * by {@link #valuation}.
	 */
	private final HiddenLootResolver hiddenLoot = new HiddenLootResolver();
	/**
	 * Whether a loot item's name that {@link #updateHiddenLoot} asked for has not arrived yet while the list hides
	 * something, so the hidden loot may still change; the chat preview waits for it.
	 */
	private boolean hiddenLootNamesPending;
	/** Told of every change (EDT); the panel's, none until set. */
	private Consumer<Change> listener = change -> { };
	/** Set by {@link #shutDown}: nothing is asked for, posted or published any more. */
	private boolean closed;

	// History
	/** Account whose records are held, or {@link SessionTracker#NO_ACCOUNT} when none are. */
	private long account = SessionTracker.NO_ACCOUNT;
	/** All records of {@link #account}, newest first; unmodifiable. */
	private List<LedgerRecord> records = Collections.emptyList();
	/** Whether {@link #records} are {@link #account}'s loaded records, not a message's empty list. */
	private boolean loaded;
	/** "Skipped / blocked" entries of {@link #account}, newest first; unmodifiable. */
	private List<AssignmentEntry> assignments = Collections.emptyList();
	/** Why the history is empty ({@link #clearRecords}), or {@code null} while records are held. */
	private String historyMessage;
	/** Bumped whenever {@link #records}, {@link #assignments} or {@link #account} change. */
	private long version;

	// Live
	/**
	 * Whether the latest {@link #currentLocation} may change once a region name asked of the plugin arrives.
	 */
	private boolean currentLocationPending;
	/** A new assignment whose chat preview waits for what it needs ({@link #checkAssignmentPreview}), or null. */
	private AssignmentPreview pendingPreview;

	/** What changed, as told to the listener ({@link #setListener}). */
	enum Change
	{
		/** New records; the views valued at today's prices are redrawn now. */
		RECORDS,
		/** New records, but the views valued at today's prices keep what they show until the prices arrive. */
		RECORDS_WAITING_FOR_PRICES,
		/** The records were dropped, with a message to show instead ({@link #historyMessage}). */
		HISTORY_CLEARED,
		/** New "Skipped / blocked" entries. */
		ASSIGNMENTS,
		/** Another "Loot price" mode. */
		LOOT_PRICE_MODE,
		/**
		 * Other loot items are hidden: Ground Items' hidden list or the setting changed, or names arrived that
		 * match it. Every view valued from the loot is redrawn.
		 */
		HIDDEN_LOOT,
		/** Today's prices arrived while nothing waited for them: the views valued at them are redrawn. */
		CURRENT_PRICES,
		/** A wait for today's prices ended: every view valued at them is redrawn. */
		PRICED_VIEWS,
		/** Region names arrived and one changed: records' and the current task's locations may differ. */
		LOCATION_NAMES,
		/** Region names arrived and none changed. */
		LOCATION_NAMES_UNCHANGED,
		/** Every region name was forgotten (another RuneScape profile). */
		LOCATION_NAMES_CLEARED,
		/** Item names (or the nature rune's price) arrived. */
		ITEM_NAMES,
		/** The task advisor settings changed. */
		TASK_ADVISOR,
		/**
		 * A view worked out in the background (the advice, Trends or Overall totals) is ready: what shows it is redrawn.
		 */
		VIEWS
	}

	/**
	 * @param locationNameRequests asks the plugin for the names of regions (EDT)
	 * @param currentPriceRequests asks the plugin for today's GE prices of item ids (EDT)
	 * @param itemNameRequests     asks the plugin for the names of item ids, and the nature rune's price (EDT)
	 * @param previewPoster        posts a new assignment's chat preview line (EDT)
	 * @param pacePublisher        publishes the overlay's historical time per kill, or {@code null} to clear it (EDT)
	 * @param viewExecutor         works out the advice, Trends and Overall totals off the EDT (the plugin's executor)
	 * @param edt                  runs their results on the EDT ({@code SwingUtilities::invokeLater})
	 */
	LedgerHistoryModel(SessionTracker tracker, LootPriceMode lootPriceMode,
		Consumer<Set<Integer>> locationNameRequests, Consumer<Set<Integer>> currentPriceRequests,
		Consumer<Set<Integer>> itemNameRequests, Consumer<String> previewPoster,
		Consumer<OverlayRows.HistoricalPace> pacePublisher, Executor viewExecutor, Executor edt)
	{
		this.tracker = tracker;
		this.previewPoster = previewPoster;
		this.pacePublisher = pacePublisher;
		valuation = new HistoryValuation(lootPriceMode, currentPriceRequests);
		names = new NameBook(locationNameRequests, itemNameRequests);
		// The views get the records from setHistory, not from the model.
		views = new LedgerViews(tracker, valuation, names, viewExecutor, edt);
		views.setOnReady(() -> fire(Change.VIEWS));
	}

	/**
	 * Sets what is told of every change (the panel, which redraws); EDT. It is called on the EDT, after the change is
	 * complete, and may read the model.
	 */
	void setListener(Consumer<Change> listener)
	{
		this.listener = listener == null ? change -> { } : listener;
	}

	/** Sets what reads the bundled luck-meter snapshot; it is read once, the first time the luck meter is shown. */
	void setLuckMeterSource(Supplier<LuckMeter> source)
	{
		views.setLuckMeterSource(source);
	}

	private void fire(Change change)
	{
		listener.accept(change);
	}

	/** The plugin stopped: the waiting preview is dropped and nothing is asked for, posted or published again. */
	void shutDown()
	{
		closed = true;
		names.close();
		views.close();
		pendingPreview = null;
	}

	// ---- History ----

	/** Account whose history is held, or {@link SessionTracker#NO_ACCOUNT} when none is. */
	long account()
	{
		return account;
	}

	/** The account's records, newest first; unmodifiable, and empty while none are loaded. */
	List<LedgerRecord> records()
	{
		return records;
	}

	/** Whether {@link #records} are the account's loaded records (not the empty list of a message). */
	boolean isLoaded()
	{
		return loaded;
	}

	/** The account's "Skipped / blocked" entries, newest first; unmodifiable. */
	List<AssignmentEntry> assignments()
	{
		return assignments;
	}

	/** Bumped on every change of the account, its records or its entries; never goes back. */
	long version()
	{
		return version;
	}

	/**
	 * Holds an account's saved records (in any order; copied) and drops the statistics. Asks for today's prices of the
	 * items not priced yet; returns whether the views valued at today's prices should be redrawn now ({@code false}
	 * while they keep what they show until the prices arrive, which is never longer than
	 * {@link #CURRENT_PRICES_WAIT_MILLIS}; another account's records and an emptied history are always redrawn at
	 * once, and again when the prices arrive). The plugin clears the history before it loads another account's
	 * records ({@link #clearRecords}), so a first load waits too. The caller must have checked that {@code account} is
	 * still the account whose history should be shown. Tells the listener {@link Change#RECORDS} or
	 * {@link Change#RECORDS_WAITING_FOR_PRICES}.
	 */
	boolean setRecords(long account, List<LedgerRecord> records)
	{
		final boolean sameAccount = account == this.account;
		this.account = account;
		this.records = Collections.unmodifiableList(LedgerHistory.sortNewestFirst(records));
		loaded = true;
		historyMessage = null;
		version++;
		updateHiddenLoot();
		views.setHistory(this.account, version, this.records, assignments);
		clearTaskStatistics();
		requestCurrentPrices();
		final boolean redraw = !sameAccount || this.records.isEmpty() || !waitForCurrentPrices();
		if (redraw)
		{
			valuation.stopWaiting();
		}
		refreshHistoricalPace();
		// A preview waiting for the records is posted now, not on a panel redraw.
		checkAssignmentPreview();
		fire(redraw ? Change.RECORDS : Change.RECORDS_WAITING_FOR_PRICES);
		return redraw;
	}

	/**
	 * Drops the records and entries (logged out, loading, or a load failure), keeping {@code account} as the one shown;
	 * nothing waits for today's prices any more. {@code message} says why ({@link #historyMessage}). Tells the listener
	 * {@link Change#HISTORY_CLEARED}.
	 */
	void clearRecords(long account, String message)
	{
		valuation.stopWaiting();
		this.account = account;
		records = Collections.emptyList();
		loaded = false;
		assignments = Collections.emptyList();
		historyMessage = message;
		version++;
		updateHiddenLoot();
		views.setHistory(this.account, version, records, assignments);
		clearTaskStatistics();
		refreshHistoricalPace();
		fire(Change.HISTORY_CLEARED);
	}

	/** Why the history is empty (the message of the last {@link #clearRecords}), or {@code null}. */
	String historyMessage()
	{
		return historyMessage;
	}

	/**
	 * Holds an account's "Skipped / blocked" entries (in any order; copied). Ignored, returning {@code false}, unless
	 * {@code account}'s history is the one held; otherwise tells the listener {@link Change#ASSIGNMENTS}.
	 */
	boolean setAssignments(long account, List<AssignmentEntry> entries)
	{
		if (account != this.account)
		{
			return false;
		}
		assignments = Collections.unmodifiableList(AssignmentLog.sortNewestFirst(entries));
		version++;
		views.setHistory(this.account, version, records, assignments);
		fire(Change.ASSIGNMENTS);
		return true;
	}

	// ---- Valuation (kept by HistoryValuation) ----

	LootPriceMode lootPriceMode()
	{
		return valuation.lootPriceMode();
	}

	/**
	 * Sets the "Loot price" mode ({@code null} is GE); returns whether it changed, which drops the statistics and tells
	 * the listener {@link Change#LOOT_PRICE_MODE}.
	 */
	boolean setLootPriceMode(LootPriceMode mode)
	{
		if (!valuation.setLootPriceMode(mode))
		{
			return false;
		}
		clearTaskStatistics();
		fire(Change.LOOT_PRICE_MODE);
		return true;
	}

	/** See {@link HistoryValuation#shownPrices}: today's GE prices the history is valued at; never {@code null}. */
	Map<Integer, Long> shownPrices()
	{
		return valuation.shownPrices();
	}

	/**
	 * Asks the plugin (once, in one batch) for today's GE price of every item in the records that has none yet and is
	 * not already asked for.
	 */
	void requestCurrentPrices()
	{
		if (!closed)
		{
			valuation.requestCurrentPrices(records);
		}
	}

	/**
	 * Adds today's GE prices the plugin looked up again after they were unavailable ({@code prices}, for those of
	 * {@code requested} that it found; {@code requested} was not pending); see
	 * {@link #addCurrentPrices(Set, Map, Set)}.
	 */
	void addCurrentPrices(Set<Integer> requested, Map<Integer, Long> prices)
	{
		addCurrentPrices(requested, prices, Collections.emptySet());
	}

	/**
	 * Adds today's GE prices the plugin looked up ({@code prices}, for those of {@code requested} that it found). The
	 * plugin also sends the prices it found when it looked up again the ones that were unavailable ({@code requested}
	 * is then not pending). While the views wait for prices ({@link #waitForCurrentPrices}), the wait ends once the
	 * last batch asked for has arrived and none of {@code retrying} is still being looked up again (for example while
	 * RuneLite's price list loads at client start), or once it runs out ({@link #endExpiredCurrentPricesWait});
	 * otherwise, when some price is new or changed, the statistics are dropped and the listener is told
	 * {@link Change#CURRENT_PRICES}. An answer that adds no new price redraws nothing.
	 *
	 * @param retrying ids of {@code requested} that came back without a price and that the plugin looks up again
	 *                 ({@link PriceRetry}); may be empty
	 */
	void addCurrentPrices(Set<Integer> requested, Map<Integer, Long> prices, Set<Integer> retrying)
	{
		final boolean changed = valuation.addCurrentPrices(requested, prices, retrying);
		if (valuation.isWaitingForCurrentPrices())
		{
			if (valuation.isWaitOver())
			{
				endCurrentPricesWait();
			}
			return;
		}
		if (!changed)
		{
			return;
		}
		clearTaskStatistics();
		fire(Change.CURRENT_PRICES);
	}

	/** Whether some prices asked for have not arrived yet. */
	boolean isCurrentPricesPending()
	{
		return valuation.isCurrentPricesPending();
	}

	/** See {@link HistoryValuation#waitForCurrentPrices}. */
	boolean waitForCurrentPrices()
	{
		return valuation.waitForCurrentPrices();
	}

	/**
	 * Whether the views valued at today's prices keep what they show until the prices asked for arrive. Any
	 * thread (one volatile read): the plugin checks it on the client thread to ask for
	 * {@link #endExpiredCurrentPricesWait} on the EDT.
	 */
	boolean isWaitingForCurrentPrices()
	{
		return valuation.isWaitingForCurrentPrices();
	}

	/** See {@link HistoryValuation#currentPricesWaitExpired}; the caller ends the wait ({@link #endCurrentPricesWait}). */
	boolean currentPricesWaitExpired()
	{
		return valuation.currentPricesWaitExpired();
	}

	/** Test seam: lets a wait for today's prices run out at once, as if no answer came in time. */
	void expireCurrentPricesWait()
	{
		valuation.expireCurrentPricesWait();
	}

	/**
	 * Ends any wait for today's prices and drops the statistics, checks the waiting chat preview again and tells the
	 * listener {@link Change#PRICED_VIEWS}, so every view valued at them is redrawn.
	 */
	void endCurrentPricesWait()
	{
		valuation.stopWaiting();
		clearTaskStatistics();
		checkAssignmentPreview();
		fire(Change.PRICED_VIEWS);
	}

	/**
	 * Ends a wait for today's prices that has lasted {@link #CURRENT_PRICES_WAIT_MILLIS} (an answer never came;
	 * {@link #currentPricesWaitExpired}): the waiting chat preview is checked again ({@link #endCurrentPricesWait}) and
	 * the panel redraws its views valued at today's prices with the prices there are ({@link Change#PRICED_VIEWS}).
	 * Returns whether it did; does nothing while no wait has run out, so it may be called as often as wanted.
	 */
	boolean endExpiredCurrentPricesWait()
	{
		if (closed || !currentPricesWaitExpired())
		{
			return false;
		}
		endCurrentPricesWait();
		return true;
	}

	/** Bumped whenever how the history is valued changes: the mode, today's prices or the hidden loot. */
	long valuationVersion()
	{
		return valuation.valuationVersion();
	}

	// ---- Hidden loot ----

	/**
	 * Hides what {@code list} hides from now on (Ground Items' hidden list, or {@link GroundItemsHidden#NONE} while
	 * the setting or Ground Items is off; {@code null} is none). When that changes which loot is hidden, the statistics
	 * are dropped and the listener is told {@link Change#HIDDEN_LOOT}. Item names not known yet are asked for; the
	 * items are matched again when they arrive ({@link #addItemNames}).
	 */
	void setGroundItemsHidden(GroundItemsHidden list)
	{
		if (hiddenLoot.setList(list) && updateHiddenLoot())
		{
			hiddenLootChanged();
		}
	}

	/** The records' loot items left out of the loot value and the "Loot received" grids; never {@code null}. */
	HiddenLoot hiddenLoot()
	{
		return valuation.hiddenLoot();
	}

	/**
	 * Works out which of the records' loot ids are hidden, from the names known, and asks for the missing names while
	 * something can be hidden. Returns whether that changed (which bumps the valuation version); the caller tells the
	 * listener.
	 */
	private boolean updateHiddenLoot()
	{
		Set<Integer> ids = new HashSet<>();
		if (!hiddenLoot.list().isEmpty())
		{
			for (LedgerRecord record : records)
			{
				if (record.getLoot() != null)
				{
					ids.addAll(record.getLoot().keySet());
				}
			}
			ids.remove(null);
			names.requestItemNames(ids);
		}
		hiddenLootNamesPending = false;
		for (Integer id : ids)
		{
			if (names.isItemNamePending(id))
			{
				hiddenLootNamesPending = true;
				break;
			}
		}
		return valuation.setHiddenLoot(hiddenLoot.resolve(ids, names::itemBaseName));
	}

	/** Other loot is hidden: drops the statistics, re-checks the waiting chat preview and tells the listener. */
	private void hiddenLootChanged()
	{
		clearTaskStatistics();
		checkAssignmentPreview();
		fire(Change.HIDDEN_LOOT);
	}

	// ---- Region names (kept by NameBook) ----

	/** The player's name for {@code region}, or {@code null} when it has none or it is not known yet. */
	String locationName(Integer region)
	{
		return names.locationName(region);
	}

	/** Asks the plugin (once, in one batch) for the names of {@code regions} not known, asked for or missing yet. */
	void requestLocationNames(Set<Integer> regions)
	{
		names.requestLocationNames(regions);
	}

	/**
	 * Adds region names from the plugin ({@code names}, for the regions in {@code requested} that have one; also sent
	 * when the player names one). Requested regions without a name are not asked for again. Returns whether a name
	 * changed: the statistics are then dropped (a record's or the current task's location may have changed) and the
	 * overlay's time per kill is worked out again.
	 */
	boolean addLocationNames(Set<Integer> requested, Map<Integer, String> names)
	{
		boolean changed = this.names.addLocationNames(requested, names);
		if (changed)
		{
			clearTaskStatistics();
		}
		refreshHistoricalPace();
		// A preview waiting for a location name is posted now, not on a panel redraw.
		checkAssignmentPreview();
		fire(changed ? Change.LOCATION_NAMES : Change.LOCATION_NAMES_UNCHANGED);
		return changed;
	}

	/**
	 * Forgets every region name (another RuneScape profile is active): they are asked for again as needed. Tells the
	 * listener {@link Change#LOCATION_NAMES_CLEARED}.
	 */
	void clearLocationNames()
	{
		names.clearLocationNames();
		clearTaskStatistics();
		refreshHistoricalPace();
		fire(Change.LOCATION_NAMES_CLEARED);
	}

	/** Whether some region names asked for have not arrived yet. */
	boolean isLocationNamePending()
	{
		return names.isLocationNamePending();
	}

	/**
	 * The current task's location for the same-location estimates ({@link TaskLocation#currentName}): its assigned
	 * area, else the name of the running or paused session's region with the most kills, else the name of the region
	 * the player is in. Names not known yet are asked of the plugin (once each); {@code null} while unknown.
	 */
	String currentLocation()
	{
		SessionTracker.State state = tracker.getState();
		boolean active = state == SessionTracker.State.RUNNING || state == SessionTracker.State.PAUSED;
		String area = active ? tracker.getSessionTaskLocation() : null;
		if (area == null)
		{
			area = tracker.getCurrentContext().getLocation();
		}
		Integer sessionRegion = active ? tracker.getDominantRegion() : null;
		int currentRegion = tracker.getCurrentRegion();
		currentLocationPending = false;
		if (TaskLocation.normalizeName(area) == null)
		{
			// Where the player stands matters only while the session's own region has no name.
			Set<Integer> regions = new HashSet<>();
			if (sessionRegion != null)
			{
				regions.add(sessionRegion);
			}
			if (sessionRegion == null || !names.hasLocationName(sessionRegion))
			{
				regions.add(currentRegion);
			}
			names.requestLocationNames(regions);
			for (Integer region : regions)
			{
				currentLocationPending |= names.isLocationNamePending(region);
			}
		}
		return TaskLocation.currentName(area, sessionRegion, currentRegion, names::locationName);
	}

	/**
	 * Whether the latest {@link #currentLocation} may change once a region name asked of the plugin arrives
	 * ({@link #addLocationNames}).
	 */
	boolean isCurrentLocationPending()
	{
		return currentLocationPending;
	}

	// ---- Item names (kept by NameBook) ----

	/** The name of item {@code id} (as tallied), or {@code null} when it is not known (yet). */
	String itemName(Integer id)
	{
		return names.itemName(id);
	}

	/** The nature rune's GE price for the alch hint, or {@code null} while unknown. */
	Long natureRunePrice()
	{
		return names.natureRunePrice();
	}

	/** Whether the name of {@code id} was asked for and has not arrived yet. */
	boolean isItemNamePending(Integer id)
	{
		return names.isItemNamePending(id);
	}

	/** Whether the plugin could not name {@code id}. */
	boolean isItemNameFailed(Integer id)
	{
		return names.isItemNameFailed(id);
	}

	/** See {@link NameBook#requestItemNames}; the answer comes through {@link #addItemNames}. */
	void requestItemNames(Set<Integer> ids)
	{
		names.requestItemNames(ids);
	}

	/**
	 * Adds item names looked up by the plugin ({@code names}, for the ids in {@code requested} that have one) and the
	 * nature rune's GE price ({@code null} when unavailable); ids without a name are not asked for again. Tells the
	 * listener {@link Change#ITEM_NAMES}.
	 */
	void addItemNames(Set<Integer> requested, Map<Integer, String> names, Long natureRune)
	{
		this.names.addItemNames(requested, names, natureRune);
		fire(Change.ITEM_NAMES);
		// A name that arrived may be one Ground Items hides.
		if (!hiddenLoot.list().isEmpty() && updateHiddenLoot())
		{
			hiddenLootChanged();
		}
		// A preview waiting for those names is posted now that the hidden loot is worked out.
		checkAssignmentPreview();
	}

	// ---- Live: the chat preview and the overlay's time per kill ----

	/**
	 * What {@link #refreshLive} depends on that the tracker holds: the session's state and id, the task and its kills
	 * left, which version of it the session's kills say is done and whether it fired a cannonball
	 * ({@link SessionTracker#getCurrentTaskSelection}), and every input of {@link #currentLocation}. Any
	 * thread (the tracker is synchronized); the plugin reads it on the client thread each settled tick and asks for
	 * {@link #refreshLive} on the EDT only when it changed.
	 */
	static List<Object> liveInputs(SessionTracker tracker)
	{
		SessionTracker.State state = tracker.getState();
		return Arrays.asList(state, tracker.getSessionId(), tracker.isTaskObserved(), tracker.getCurrentCreatureId(),
			tracker.getCurrentMonster(), tracker.getCurrentRemaining(), tracker.getSessionTaskLocation(),
			tracker.getCurrentContext().getLocation(), tracker.getDominantRegion(), tracker.getCurrentRegion(),
			tracker.getCurrentTaskSelection());
	}

	/**
	 * Ends a wait for today's prices that ran out ({@link #endExpiredCurrentPricesWait}), re-checks the waiting chat
	 * preview and publishes the overlay's time per kill again.
	 */
	void refreshLive()
	{
		endExpiredCurrentPricesWait();
		checkAssignmentPreview();
		refreshHistoricalPace();
	}

	/**
	 * The plugin saw a new assignment of {@code account}: its chat preview is posted once the history and what
	 * the estimate needs are here ({@link #checkAssignmentPreview}), at most once. A later one replaces it.
	 */
	void requestAssignmentPreview(long account, int creatureId, String monster)
	{
		if (closed)
		{
			return;
		}
		pendingPreview = new AssignmentPreview(account, creatureId, monster);
		checkAssignmentPreview();
	}

	/** Whether an assignment preview waits to be posted. */
	boolean isAssignmentPreviewPending()
	{
		return pendingPreview != null;
	}

	/**
	 * Posts the waiting assignment preview through the plugin, built like the History estimate for the kills left
	 * (the same statistics, location first), or drops it. It waits while the account's records are loading, today's
	 * prices are on their way, a location name is, or a loot item name that decides the hidden loot is; it is
	 * dropped once that task is no longer the current one or another account's history is held. Without completed
	 * history nothing is posted.
	 */
	void checkAssignmentPreview()
	{
		AssignmentPreview preview = pendingPreview;
		if (preview == null || closed)
		{
			return;
		}
		int remaining = tracker.getCurrentRemaining();
		if (preview.account != account || !tracker.isTaskObserved() || remaining <= 0
			|| tracker.getCurrentCreatureId() != preview.creatureId
			|| !Objects.equals(TaskStatistics.normalize(tracker.getCurrentMonster()),
			TaskStatistics.normalize(preview.monster)))
		{
			pendingPreview = null;
			return;
		}
		if (!loaded || valuation.isWaitingForCurrentPrices() || hiddenLootNamesPending)
		{
			// addItemNames re-checks once the names that decide the hidden loot are here.
			return;
		}
		String location = currentLocation();
		TaskStatistics stats = taskStatistics(preview.creatureId, preview.monster, location);
		if (currentLocationPending || names.isLocationNamePending())
		{
			// addLocationNames drops the statistics; the next check builds them again.
			return;
		}
		pendingPreview = null;
		String line = AssignmentPreviewMessage.format(LedgerRecord.displayName(preview.monster, preview.creatureId),
			remaining, stats, valuation.lootPriceMode());
		if (line != null)
		{
			previewPoster.accept(line);
		}
	}

	/**
	 * The historical time per kill the sidebar's "Time left" uses for the running or paused session's task (at its
	 * location first, and of the version and cannon use its session shows), or {@code null} with no such
	 * session or no history for its task.
	 */
	OverlayRows.HistoricalPace historicalPace()
	{
		SessionTracker.State state = tracker.getState();
		if (state != SessionTracker.State.RUNNING && state != SessionTracker.State.PAUSED)
		{
			return null;
		}
		TaskStatistics stats = taskStatistics(tracker.getCurrentCreatureId(), tracker.getCurrentMonster(),
			tracker.getCurrentTaskSelection(), currentLocation());
		Long perKill = stats.getSampleCount() == 0 ? null : stats.getMillisPerKill();
		String sessionId = tracker.getSessionId();
		return perKill == null || sessionId == null ? null : new OverlayRows.HistoricalPace(sessionId, perKill);
	}

	/**
	 * Publishes {@link #historicalPace} to the plugin for the overlay: run when the records, a region name, the
	 * task, its location or the session change.
	 */
	void refreshHistoricalPace()
	{
		if (closed)
		{
			return;
		}
		pacePublisher.accept(historicalPace());
	}

	// ---- Derived views (kept by LedgerViews) ----

	/** Drops the cached statistics, after the history, its pricing or a region name changed. */
	private void clearTaskStatistics()
	{
		views.clearTaskStatistics();
	}

	/** See {@link LedgerViews#taskStatisticsKey}. */
	String taskStatisticsKey(int creatureId, String monster, String location)
	{
		return views.taskStatisticsKey(creatureId, monster, location);
	}

	/** See {@link LedgerViews#taskStatisticsKey(int, String, TaskSelection, String)}. */
	String taskStatisticsKey(int creatureId, String monster, TaskSelection selection, String location)
	{
		return views.taskStatisticsKey(creatureId, monster, selection, location);
	}

	/** See {@link LedgerViews#taskStatistics}. */
	TaskStatistics taskStatistics(int creatureId, String monster, String location)
	{
		return views.taskStatistics(creatureId, monster, location);
	}

	/** See {@link LedgerViews#taskStatistics(int, String, TaskSelection, String)}. */
	TaskStatistics taskStatistics(int creatureId, String monster, TaskSelection selection, String location)
	{
		return views.taskStatistics(creatureId, monster, selection, location);
	}

	/** See {@link LedgerViews#effectiveSelection}. */
	TaskSelection effectiveSelection(int creatureId, String monster, TaskSelection selection)
	{
		return views.effectiveSelection(creatureId, monster, selection);
	}

	/**
	 * Applies the "Task advisor" and "Task advisor goal" settings and tells the listener
	 * {@link Change#TASK_ADVISOR}, so the advice is worked out again at once, or hidden.
	 */
	void setTaskAdvisor(boolean enabled, TaskRanking.Metric metric)
	{
		views.setTaskAdvisor(enabled, metric);
		fire(Change.TASK_ADVISOR);
	}

	/** See {@link LedgerViews#isAdviceShown}. */
	boolean isAdviceShown()
	{
		return views.isAdviceShown();
	}

	/** See {@link LedgerViews#advice}. */
	TaskAdvisor.Result advice()
	{
		return views.advice();
	}

	/** See {@link LedgerViews#variantAdvice}. */
	TaskAdvisor.VersionAdvice variantAdvice()
	{
		return views.variantAdvice();
	}

	/** See {@link LedgerViews#cannonAdvice}. */
	TaskAdvisor.VersionAdvice cannonAdvice()
	{
		return views.cannonAdvice();
	}

	/** See {@link LedgerViews#overallTotals}. */
	List<OverallTotals.Entry> overallTotals(OverallTotals.Grouping grouping)
	{
		return views.overallTotals(grouping);
	}

	/** See {@link LedgerViews#ranking}. */
	List<TaskRanking.Entry> ranking(TaskRanking.Metric metric)
	{
		return views.ranking(metric);
	}

	/** See {@link LedgerViews#chartMonsters}. */
	List<TaskChart.Monster> chartMonsters(TaskRanking.Metric metric)
	{
		return views.chartMonsters(metric);
	}

	/** See {@link LedgerViews#chartPoints}. */
	List<TaskChart.Point> chartPoints(TaskChart.Monster monster, TaskRanking.Metric metric)
	{
		return views.chartPoints(monster, metric);
	}

	/** See {@link LedgerViews#luckTasks}. */
	List<String> luckTasks()
	{
		return views.luckTasks();
	}

	/** See {@link LedgerViews#luck}. */
	LuckView luck(String task)
	{
		return views.luck(task);
	}

	/** See {@link LedgerViews#assignmentRows}. */
	List<AssignmentRow> assignmentRows()
	{
		return views.assignmentRows();
	}

	/** See {@link LedgerViews#whatToBring}. */
	WhatToBring whatToBring(TaskStatistics stats, int remaining)
	{
		return views.whatToBring(stats, remaining);
	}

	/**
	 * How many times the advice was worked out ({@link LedgerViews#getAdviceBuildCount}).
	 * Performance seam: tests assert this doesn't grow on the refresh timer.
	 */
	int getAdviceBuildCount()
	{
		return views.getAdviceBuildCount();
	}

	/**
	 * How many times the overall totals were worked out ({@link LedgerViews#getOverallTotalsBuildCount}).
	 * Performance seam: tests assert this doesn't grow on the refresh timer.
	 */
	int getOverallTotalsBuildCount()
	{
		return views.getOverallTotalsBuildCount();
	}

	/**
	 * How many times the Trends list was worked out ({@link LedgerViews#getRankingBuildCount}).
	 * Performance seam: tests assert this doesn't grow on the refresh timer.
	 */
	int getRankingBuildCount()
	{
		return views.getRankingBuildCount();
	}

	/**
	 * How many times the luck meter was worked out ({@link LedgerViews#getLuckBuildCount}).
	 * Performance seam: tests assert this doesn't grow on the refresh timer.
	 */
	int getLuckBuildCount()
	{
		return views.getLuckBuildCount();
	}

	/**
	 * How many times the "Skipped / blocked" rows were worked out ({@link LedgerViews#getAssignmentRowsBuildCount}).
	 * Performance seam: tests assert this doesn't grow on the refresh timer.
	 */
	int getAssignmentRowsBuildCount()
	{
		return views.getAssignmentRowsBuildCount();
	}

	/**
	 * How many times What to bring was worked out ({@link LedgerViews#getWhatToBringBuildCount}).
	 * Performance seam: tests assert this doesn't grow on the refresh timer.
	 */
	int getWhatToBringBuildCount()
	{
		return views.getWhatToBringBuildCount();
	}

	/** A new assignment waiting for its chat preview. */
	private static final class AssignmentPreview
	{
		private final long account;
		private final int creatureId;
		private final String monster;

		private AssignmentPreview(long account, int creatureId, String monster)
		{
			this.account = account;
			this.creatureId = creatureId;
			this.monster = monster;
		}
	}
}
