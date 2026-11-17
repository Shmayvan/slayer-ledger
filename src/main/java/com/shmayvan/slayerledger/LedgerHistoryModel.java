package com.shmayvan.slayerledger;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import lombok.*;

/**
 * The shown account's saved history and everything worked out from it: its records and "Skipped /
 * blocked" entries and the overlay's historical time per kill. Now the rest is kept by three
 * collaborators it owns: how loot is valued ({@link HistoryValuation}: the "Loot price" mode, today's GE prices and the
 * wait for them), the player's region names and item names ({@link NameBook}) and the derived views ({@link LedgerViews}:
 * the per-task statistics, the advisor's plan, Overall totals and the "Skipped / blocked" rows). The model is still the
 * one entry point: callers read and write through it, and it decides what each change means and tells its listener
 * ({@link Change}). The plugin creates and owns it and writes to it; the panel observes it
 * ({@link #setListener}) and only renders the immutable results it returns.
 *
 * <p>Threading: EDT only. Every method, including the getters, must be called on the Swing event dispatch thread, as
 * the panel's were; the exceptions are the static {@link #liveInputs} and the instance method
 * {@link #isWaitingForCurrentPrices}, which reads one volatile field (in {@link HistoryValuation}). Nothing here is
 * synchronized. The advisor's plan and Overall totals are worked out on a background executor from an immutable
 * snapshot and handed back on the EDT ({@link LedgerViews}): they return {@code null} until ready (the plan
 * shown: the same task's previous one, if any), then the listener is told {@link Change#VIEWS}.
 * The tracker it reads is synchronized, so the EDT may read it. The plugin's callbacks it calls
 * ({@code requestLocationNames}, {@code requestCurrentPrices} and {@code requestItemNames}, which the plugin's
 * {@link ClientLookups} serves, and {@code publishHistoricalPace}) are the plugin's EDT entry points: they queue
 * client-thread work or write one volatile field. The panel's listener ({@link #setListener}) is told of every
 * change, on the EDT and after the change is complete, and redraws what it shows. The lists and views it returns are
 * unmodifiable and replaced, never changed, when what they come from changes.</p>
 *
 * <p>Derived views are worked out on demand and cached until what they come from changes: the records
 * ({@link #version}), how they are valued ({@link #valuationVersion}), item names where they are shown, and the view's
 * own inputs (a grouping, a task). Asking again with nothing changed returns the same object, so the panel's
 * refresh timer never works them out again. The per-task statistics stay on the EDT: the overlay's time per kill
 * decides from them at once.</p>
 *
 * <p>No Swing or AWT: what the overlay is told, when the advisor's plan is worked out and when a wait for today's
 * prices runs out are decided here, not by a refresh timer. The plugin re-checks them ({@link #refreshLive}) whenever
 * the live task or session changes ({@link #liveInputs}), and ends an expired wait for today's prices
 * ({@link #endExpiredCurrentPricesWait}) on the settled ticks while one runs; the panel's own refresh also ends an
 * expired wait. The plan is asked for whenever one of its inputs arrives ({@link #setRecords}, today's prices, the
 * "Loot price" mode, the hidden loot, {@link #addItemNames}).</p>
 */
final class LedgerHistoryModel
{
	/** See {@link HistoryValuation#CURRENT_PRICES_WAIT_MILLIS}. */
	static final long CURRENT_PRICES_WAIT_MILLIS = HistoryValuation.CURRENT_PRICES_WAIT_MILLIS;

	private final SessionTracker tracker;
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
	@Getter
	private boolean loaded;
	/** "Skipped / blocked" entries of {@link #account}, newest first; unmodifiable. */
	private List<AssignmentEntry> assignments = Collections.emptyList();
	/** Why the history is empty ({@link #clearRecords}), or {@code null} while records are held. */
	private String historyMessage;
	/** Bumped whenever {@link #records}, {@link #assignments} or {@link #account} change. */
	private long version;

	// Live
	/**
	 * The live inputs ({@link #liveInputs}) as {@link #refreshLive} last read them, or {@code null} before its first
	 * run: the listener is told {@link Change#LIVE} only when they differ.
	 */
	private List<Object> lastLiveInputs;

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
		/**
		 * A view worked out in the background (the advisor's plan or Overall totals) is ready: what shows it is
		 * redrawn.
		 */
		VIEWS,
		/**
		 * The live inputs ({@link #liveInputs}: the task, its kills left, the session, ...) changed, as
		 * {@link #refreshLive} saw: what reads only the tracker for them (the advisor card's task and first kill) is
		 * checked again, with the panel hidden too. Told once per change, never for a check that finds nothing new.
		 */
		LIVE
	}

	/**
	 * @param locationNameRequests asks the plugin for the names of regions (EDT)
	 * @param currentPriceRequests asks the plugin for today's GE prices of item ids (EDT)
	 * @param itemNameRequests     asks the plugin for the names of item ids, and the nature rune's price (EDT)
	 * @param pacePublisher        publishes the overlay's historical time per kill, or {@code null} to clear it (EDT)
	 * @param viewExecutor         works out the plan and Overall totals off the EDT (the plugin's executor)
	 * @param edt                  runs their results on the EDT ({@code SwingUtilities::invokeLater})
	 */
	LedgerHistoryModel(SessionTracker tracker, LootPriceMode lootPriceMode,
		Consumer<Set<Integer>> locationNameRequests, Consumer<Set<Integer>> currentPriceRequests,
		Consumer<Set<Integer>> itemNameRequests, Consumer<OverlayRows.HistoricalPace> pacePublisher,
		Executor viewExecutor, Executor edt)
	{
		this.tracker = tracker;
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

	private void fire(Change change)
	{
		listener.accept(change);
	}

	/** The plugin stopped: nothing is asked for or published again. */
	void shutDown()
	{
		closed = true;
		names.close();
		views.close();
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
		refreshAdvisor();
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
	 * The record-fast task chat line for {@code record}, a completed task just saved ({@link FastestTaskMessage}), as a
	 * RuneLite chat message, or {@code null} when it is no record. Worked out from the records held, one pass over
	 * them, and only while they are {@code account}'s loaded records ({@code record} itself is left out, so it doesn't
	 * matter whether they hold it yet); otherwise {@code null}.
	 */
	String fastestTaskLine(long account, LedgerRecord record)
	{
		if (closed || !loaded || account != this.account)
		{
			return null;
		}
		return FastestTaskMessage.chatMessage(FastestTaskMessage.find(record, records));
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
	 * Sets the "Loot price" mode ({@code null} is GE); returns whether it changed, which asks for the plan again and
	 * tells the listener {@link Change#LOOT_PRICE_MODE}. The per-task statistics value nothing, so they are kept.
	 */
	boolean setLootPriceMode(LootPriceMode mode)
	{
		if (!valuation.setLootPriceMode(mode))
		{
			return false;
		}
		refreshAdvisor();
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
	 * otherwise, when some price is new or changed, the plan is asked for again and the listener is told
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
		refreshAdvisor();
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
	 * Ends any wait for today's prices, asks for the advisor's plan at the prices there are and tells the listener
	 * {@link Change#PRICED_VIEWS}, so every view valued at them is redrawn.
	 */
	void endCurrentPricesWait()
	{
		valuation.stopWaiting();
		refreshAdvisor();
		fire(Change.PRICED_VIEWS);
	}

	/**
	 * Ends a wait for today's prices that has lasted {@link #CURRENT_PRICES_WAIT_MILLIS} (an answer never came;
	 * {@link #currentPricesWaitExpired}): the plan is asked for again ({@link #endCurrentPricesWait}) and the panel
	 * redraws its views valued at today's prices with the prices there are ({@link Change#PRICED_VIEWS}).
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
	 * the setting or Ground Items is off; {@code null} is none). When that changes which loot is hidden, the plan is
	 * asked for again and the listener is told {@link Change#HIDDEN_LOOT}. Item names not known yet are asked for; the
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
		return valuation.setHiddenLoot(hiddenLoot.resolve(ids, names::itemBaseName));
	}

	/**
	 * Other loot is hidden: asks for the plan again and tells the listener (the per-task statistics value nothing, so
	 * they are kept).
	 */
	private void hiddenLootChanged()
	{
		refreshAdvisor();
		fire(Change.HIDDEN_LOOT);
	}

	// ---- Region names (kept by NameBook) ----

	/** The player's name for {@code region}, or {@code null} when it has none or it is not known yet. */
	String locationName(Integer region)
	{
		return names.locationName(region);
	}

	/**
	 * Sets the bundled Slayer spot table, which names the records' and the current task's locations;
	 * {@code null} is {@link SlayerSpots#NONE}. The plugin sets it once, when it starts.
	 */
	void setSpots(SlayerSpots spots)
	{
		names.setSpots(spots);
		clearTaskStatistics();
	}

	/** The bundled Slayer spot table; never {@code null}. */
	SlayerSpots spots()
	{
		return names.spots();
	}

	/**
	 * A saved record's location name as the history shows it ({@link TaskLocation#recordLocation}), or {@code null}
	 * when none is known.
	 */
	String recordLocation(LedgerRecord record)
	{
		return names.recordLocation(record);
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

	/**
	 * The current task's location for the same-location statistics ({@link TaskLocation#current}): the active
	 * session's spot, else its assigned area, else the spot the player is in, with the name the live card's time left
	 * shows. Region names not known yet are asked of the plugin (once each), for the name;
	 * {@link TaskLocation.Here#UNKNOWN} while unknown.
	 */
	TaskLocation.Here currentLocation()
	{
		SessionTracker.State state = tracker.getState();
		boolean active = state.isActive();
		String area = active ? tracker.getSessionTaskLocation() : null;
		if (area == null)
		{
			area = tracker.getCurrentContext().getLocation();
		}
		TaskSpot sessionSpot = active ? tracker.getTaskSpot() : TaskSpot.UNKNOWN;
		int currentRegion = tracker.getCurrentRegion();
		String currentSpot = tracker.getCurrentSpot();
		// A region's name comes before its spot's; where the player stands matters only while the session has no spot
		// and the task no assigned area (as TaskLocation.current decides).
		Set<Integer> regions = new HashSet<>();
		boolean sessionHasSpot = sessionSpot.getSpotId() != null;
		if (sessionHasSpot && sessionSpot.getRegion() != null)
		{
			regions.add(sessionSpot.getRegion());
		}
		if (!sessionHasSpot && TaskLocation.normalizeName(area) == null)
		{
			regions.add(currentRegion);
		}
		names.requestLocationNames(regions);
		return TaskLocation.current(area, sessionSpot, currentRegion, currentSpot, names::locationName,
			names.spots());
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
		// A potion's name may join it to its family in the plan.
		refreshAdvisor();
	}

	// ---- Live: the advisor's plan and the overlay's time per kill ----

	/**
	 * What {@link #refreshLive} depends on that the tracker holds: the session's state and id, the task, its master
	 * (the plan's task type) and its kills left, which version of it the session's kills say is done and whether its
	 * Cannon tag is Yes ({@link SessionTracker#getCurrentTaskSelection}), and every input of
	 * {@link #currentLocation}, the assigned area among them. Any thread (the tracker is synchronized); the plugin
	 * reads it on the client thread each settled tick and asks for {@link #refreshLive} on the EDT only when it
	 * changed, so a new kills left asks for a new plan at most once a tick.
	 */
	static List<Object> liveInputs(SessionTracker tracker)
	{
		SessionTracker.State state = tracker.getState();
		return Arrays.asList(state, tracker.getSessionId(), tracker.isTaskObserved(), tracker.getCurrentCreatureId(),
			tracker.getCurrentMonster(), tracker.getCurrentRemaining(), tracker.getCurrentContext().getMaster(),
			tracker.getSessionTaskLocation(), tracker.getCurrentContext().getLocation(), tracker.getTaskSpot(),
			tracker.getCurrentRegion(), tracker.getCurrentSpot(), tracker.getCurrentTaskSelection());
	}

	/**
	 * Ends a wait for today's prices that ran out ({@link #endExpiredCurrentPricesWait}), asks for the advisor's plan
	 * of the live task and publishes the overlay's time per kill again. When the live inputs differ from what the last
	 * call read, the listener is then told {@link Change#LIVE}, so what reads only the tracker (the advisor card's
	 * task) needn't wait for a panel refresh.
	 */
	void refreshLive()
	{
		List<Object> inputs = liveInputs(tracker);
		endExpiredCurrentPricesWait();
		refreshAdvisor();
		refreshHistoricalPace();
		if (!closed && !inputs.equals(lastLiveInputs))
		{
			lastLiveInputs = inputs;
			fire(Change.LIVE);
		}
	}

	/**
	 * Asks for the advisor's plan of the current inputs ({@link #plan}: worked out in the background, once per inputs).
	 * Called whenever one of the plan's inputs may have changed, so it doesn't wait for the panel.
	 */
	private void refreshAdvisor()
	{
		plan();
	}

	/**
	 * The advisor's plan to show for the current task ({@link LedgerViews#plan}): the plan for the current inputs
	 * or, while it is worked out in the background or the views wait for today's prices, the last plan for the
	 * same task, so a kill doesn't blank it. {@code null} while there is no task, the account's records are not loaded,
	 * or no such plan is kept. When the plan for the current inputs is ready the listener is told {@link Change#VIEWS}.
	 * Asking again with nothing changed works nothing out.
	 */
	TaskPlan plan()
	{
		return closed || !loaded ? null : views.plan();
	}

	/**
	 * How many times the plan was worked out ({@link LedgerViews#getPlanBuildCount}).
	 * Performance seam: tests assert this doesn't grow on the refresh timer.
	 */
	int getPlanBuildCount()
	{
		return views.getPlanBuildCount();
	}


	/**
	 * The historical time per kill the sidebar's "Time left" uses for the active session's task (at its
	 * location first, and of the version and cannon use its session shows), with the same tasks' supplies per
	 * kill for the overlay's supply runway, or {@code null} with no such session or no history for its task.
	 */
	OverlayRows.HistoricalPace historicalPace()
	{
		SessionTracker.State state = tracker.getState();
		if (!state.isActive())
		{
			return null;
		}
		TaskStatistics stats = taskStatistics(tracker.getCurrentCreatureId(), tracker.getCurrentMonster(),
			tracker.getCurrentTaskSelection(), currentLocation());
		Long perKill = stats.getSampleCount() == 0 ? null : stats.getMillisPerKill();
		String sessionId = tracker.getSessionId();
		// With the supplies per kill of the same tasks, for the overlay's supply runway.
		return perKill == null || sessionId == null ? null
			: new OverlayRows.HistoricalPace(sessionId, perKill, stats.getSuppliesPerKill());
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

	/** Drops the cached statistics, after the history, the spot table or a region name changed. */
	private void clearTaskStatistics()
	{
		views.clearTaskStatistics();
	}

	/** See {@link LedgerViews#taskStatistics(int, String, TaskSelection, TaskLocation.Here)}. */
	TaskStatistics taskStatistics(int creatureId, String monster, TaskSelection selection, TaskLocation.Here location)
	{
		return views.taskStatistics(creatureId, monster, selection, location);
	}

	/**
	 * How many times a task's statistics were worked out ({@link LedgerViews#getTaskStatisticsBuildCount}).
	 * Performance seam: tests assert this doesn't grow on the refresh timer, or when only the valuation changes.
	 */
	int getTaskStatisticsBuildCount()
	{
		return views.getTaskStatisticsBuildCount();
	}

	/** See {@link LedgerViews#overallTotals}. */
	List<OverallTotals.Entry> overallTotals(OverallTotals.Grouping grouping)
	{
		return views.overallTotals(grouping);
	}

	/** See {@link LedgerViews#assignmentRows}. */
	List<AssignmentRow> assignmentRows()
	{
		return views.assignmentRows();
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
	 * How many times the "Skipped / blocked" rows were worked out ({@link LedgerViews#getAssignmentRowsBuildCount}).
	 * Performance seam: tests assert this doesn't grow on the refresh timer.
	 */
	int getAssignmentRowsBuildCount()
	{
		return views.getAssignmentRowsBuildCount();
	}
}
