package com.shmayvan.slayerledger;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * The shown history's derived views (split from {@link LedgerHistoryModel}): the per-task statistics, the task
 * advisor and its settings, the Trends list and chart, Overall totals, the luck meter, the "Skipped / blocked" rows and
 * What to bring. Each is worked out on demand and cached until what it comes from changes: the records
 * ({@link #setHistory}'s version), how they are valued ({@link HistoryValuation#valuationVersion}), item names where
 * they are shown ({@link NameBook#itemNamesVersion}) and the view's own inputs (a metric, a grouping, a task). Asking
 * again with nothing changed returns the same object, so the panel's refresh timer never works them out again. The
 * statistics are kept until the model drops them ({@link #clearTaskStatistics}).
 *
 * <p>Off the EDT. The views that scan every record for the sidebar, the task advice, the Trends list and chart
 * and Overall totals, are worked out on a background executor (the plugin's), once per inputs, from an immutable
 * snapshot taken on the EDT: the records list (unmodifiable and replaced, never changed; the records in it are not
 * changed once loaded), for Overall totals' By master the "Skipped / blocked" entries list (the same), the creature
 * and goal for the advice, the "Loot price" mode and a copy of the prices shown
 * ({@link Valued}). Asked for while it is being worked out, such a view returns {@code null} (the advice: the same
 * task's previous advice, else {@code null}), and the panel keeps what it shows. The result goes back to the EDT, where
 * it is kept only if it is still the latest one asked for: a result for older inputs (an older records or valuation
 * version, another metric) is dropped. A kept result tells {@link #setOnReady}'s listener, which has the panel redraw.
 * The background work touches no Swing and no mutable state; everything it reads is captured before it is queued and
 * everything it returns is immutable, published to the EDT through the EDT executor's queue.</p>
 *
 * <p>Kept on the EDT, worked out when asked: the per-task statistics ({@link #taskStatistics}), because the chat
 * preview and the overlay's time per kill decide synchronously from them and ask for region names while building them;
 * the luck meter, which asks for item names and depends on which are still pending; the "Skipped / blocked" rows and
 * What to bring, which are small. Names are asked for on the EDT only.</p>
 *
 * <p>Threading: EDT only, like the model that owns it (except the background work described above). The results are
 * unmodifiable.</p>
 */
final class LedgerViews
{
	private final SessionTracker tracker;
	private final HistoryValuation valuation;
	private final NameBook names;
	/** Works out the background views; the plugin's executor (tests: a queue or the calling thread). */
	private final Executor background;
	/** Runs a background view's result on the EDT ({@code SwingUtilities::invokeLater}; tests: the calling thread). */
	private final Executor edt;
	/** Told on the EDT when a background view's result is kept; none until set. */
	private Runnable onReady = () -> { };
	/** Set by {@link #close}: nothing more is worked out or kept. */
	private boolean closed;

	// The history the views come from, as the model last set it (setHistory).
	/** All records, newest first; unmodifiable, replaced and never changed. */
	private List<LedgerRecord> records = Collections.emptyList();
	/** "Skipped / blocked" entries, newest first; unmodifiable. */
	private List<AssignmentEntry> assignments = Collections.emptyList();
	/** The model's version of {@link #records} and {@link #assignments}. */
	private long version;
	/** The account the records are of; another one drops the kept advice (see {@link #advice}). */
	private long account = SessionTracker.NO_ACCOUNT;
	/** How the records are valued, captured for the background views at {@link Valued#valuationVersion}. */
	private Valued valued;

	/** Reads the bundled luck-meter snapshot (once, on first use); none until set. */
	private Supplier<LuckMeter> luckMeterSource = () -> null;
	/** The bundled luck-meter snapshot, read once; {@code null} when it could not be read. */
	private LuckMeter luckMeter;
	private boolean luckMeterLoaded;

	/** The "Task advisor" setting. */
	private boolean adviceEnabled = true;
	/** The "Task advisor goal" setting. */
	private TaskRanking.Metric adviceMetric = TaskRanking.Metric.PROFIT;

	/** Per-task aggregates, dropped when the history, its pricing or a region name changes. */
	private final Map<String, TaskStatistics> taskStatisticsCache = new HashMap<>();
	/** {@link #effectiveSelection} per task and live selection; dropped with the statistics or new history. */
	private final Map<String, TaskSelection> effectiveSelectionCache = new HashMap<>();
	private final Background<TaskAdvisor.Outcome> advice = new Background<>();
	private final Background<List<OverallTotals.Entry>> overallTotals = new Background<>();
	private final Background<List<TaskRanking.Entry>> ranking = new Background<>();
	private final Background<List<TaskChart.Monster>> chartMonsters = new Background<>();
	private final Background<List<TaskChart.Point>> chartPoints = new Background<>();
	private final Cached<List<String>> luckTasks = new Cached<>();
	private final Cached<LuckView> luck = new Cached<>();
	private final Cached<List<AssignmentRow>> assignmentRows = new Cached<>();
	private final Cached<WhatToBring> whatToBring = new Cached<>();

	/**
	 * @param valuation  how the records are valued; only read
	 * @param names      region and item names; missing ones are asked for
	 * @param background works out the advice, Trends and Overall totals off the EDT
	 * @param edt        runs their results on the EDT
	 */
	LedgerViews(SessionTracker tracker, HistoryValuation valuation, NameBook names, Executor background, Executor edt)
	{
		this.tracker = tracker;
		this.valuation = valuation;
		this.names = names;
		this.background = background;
		this.edt = edt;
	}

	/**
	 * Sets the history the views come from (the model's account, records and entries, newest first, unmodifiable, and
	 * its version); the views are worked out again when next asked for. Another account drops the kept advice and any
	 * advice still being worked out, so the previous account's advice is never shown for the same task while the new
	 * account's is worked out.
	 */
	void setHistory(long account, long version, List<LedgerRecord> records, List<AssignmentEntry> assignments)
	{
		if (account != this.account)
		{
			this.account = account;
			advice.reset();
		}
		this.version = version;
		this.records = records;
		this.assignments = assignments;
		effectiveSelectionCache.clear();
	}

	/** Sets what is told, on the EDT, when a view worked out in the background is ready to be shown. */
	void setOnReady(Runnable listener)
	{
		onReady = listener == null ? () -> { } : listener;
	}

	/** The plugin stopped: nothing more is worked out, and results still on their way are dropped. */
	void close()
	{
		closed = true;
	}

	/** How the records are valued now, as an immutable copy made once per valuation version. */
	private Valued valued()
	{
		long current = valuation.valuationVersion();
		if (valued == null || valued.valuationVersion != current)
		{
			valued = new Valued(current, valuation.lootPriceMode(),
				Collections.unmodifiableMap(new HashMap<>(valuation.shownPrices())), valuation.hiddenLoot());
		}
		return valued;
	}

	/** Sets what reads the bundled luck-meter snapshot; it is read once, the first time the luck meter is shown. */
	void setLuckMeterSource(Supplier<LuckMeter> source)
	{
		luckMeterSource = source == null ? () -> null : source;
	}

	// ---- Statistics ----

	/** Drops the cached statistics, after the history, its pricing or a region name changed. */
	void clearTaskStatistics()
	{
		taskStatisticsCache.clear();
		effectiveSelectionCache.clear();
	}

	/** {@link #taskStatisticsKey(int, String, TaskSelection, String)} pooling all records ({@link TaskSelection#ANY}). */
	String taskStatisticsKey(int creatureId, String monster, String location)
	{
		return taskStatisticsKey(creatureId, monster, TaskSelection.ANY, location);
	}

	/**
	 * Key of {@link #taskStatistics}: creature key, name as {@link TaskStatistics#normalize} matches it, the selection
	 * the statistics use (version and cannon use, {@link #effectiveSelection}, {@link TaskSelection#key}),
	 * mode, the valuation version ({@link HistoryValuation#valuationVersion}, which also changes with Ground Items'
	 * hidden items) and the location ({@link TaskLocation#normalizeName}d), so a change of location, version or
	 * cannon use, a first cannon task in the history, or other prices or hidden items, gives other statistics.
	 */
	String taskStatisticsKey(int creatureId, String monster, TaskSelection selection, String location)
	{
		String name = TaskStatistics.normalize(monster);
		String here = TaskLocation.normalizeName(location);
		return creatureId + "\u0000" + (name == null ? "" : name) + "\u0000"
			+ effectiveSelection(creatureId, monster, selection).key() + "\u0000" + valuation.lootPriceMode() + "\u0000"
			+ valuation.valuationVersion() + "\u0000" + (here == null ? "" : here);
	}

	/**
	 * The selection a task's statistics use for the live {@code selection} ({@link TaskStatistics#effectiveSelection}): cannon tasks only when the history has a completed cannon task of the task's selected version, else tasks
	 * with and without a cannon pooled. Cached with the statistics until {@link #clearTaskStatistics} or new history.
	 */
	TaskSelection effectiveSelection(int creatureId, String monster, TaskSelection selection)
	{
		TaskSelection requested = selectionOrAny(selection);
		if (!Boolean.TRUE.equals(requested.getCannon()))
		{
			return requested;
		}
		String name = TaskStatistics.normalize(monster);
		String key = creatureId + "\u0000" + (name == null ? "" : name) + "\u0000" + requested.key();
		TaskSelection effective = effectiveSelectionCache.get(key);
		if (effective == null)
		{
			effective = TaskStatistics.effectiveSelection(records, creatureId, monster, requested);
			effectiveSelectionCache.put(key, effective);
		}
		return effective;
	}

	/** {@link #taskStatistics(int, String, TaskSelection, String)} pooling all records ({@link TaskSelection#ANY}). */
	TaskStatistics taskStatistics(int creatureId, String monster, String location)
	{
		return taskStatistics(creatureId, monster, TaskSelection.ANY, location);
	}

	/**
	 * A task's statistics, of the records {@code selection} selects (version and cannon use; cannon tasks
	 * only just when the history has some, {@link #effectiveSelection}): from its history at {@code location} when it
	 * has some there, else from all of it (see {@link TaskStatistics#getLocation}). Cached until
	 * {@link #clearTaskStatistics}.
	 */
	TaskStatistics taskStatistics(int creatureId, String monster, TaskSelection selection, String location)
	{
		String key = taskStatisticsKey(creatureId, monster, selection, location);
		TaskStatistics stats = taskStatisticsCache.get(key);
		if (stats == null)
		{
			if (location != null)
			{
				requestSampleLocationNames(creatureId, monster);
			}
			stats = TaskStatistics.forTask(records, creatureId, monster,
				effectiveSelection(creatureId, monster, selection), location,
				record -> TaskLocation.recordLocation(record, names::locationName), valuation.lootPriceMode(),
				valuation.shownPrices(), valuation.hiddenLoot());
			taskStatisticsCache.put(key, stats);
		}
		return stats;
	}

	private static TaskSelection selectionOrAny(TaskSelection selection)
	{
		return selection == null ? TaskSelection.ANY : selection;
	}

	/**
	 * Asks the plugin for the region names of the task's samples that have only a region, so their location is known
	 * to the same-location estimates.
	 */
	private void requestSampleLocationNames(int creatureId, String monster)
	{
		Set<Integer> regions = new HashSet<>();
		for (LedgerRecord record : records)
		{
			if (record.getLocationRegion() != null && TaskLocation.normalizeName(record.getLocation()) == null
				&& TaskLocation.normalizeName(record.getTaskLocation()) == null
				&& TaskStatistics.isSample(record, creatureId, monster))
			{
				regions.add(record.getLocationRegion());
			}
		}
		names.requestLocationNames(regions);
	}

	// ---- Task advisor ----

	/** Applies the "Task advisor" and "Task advisor goal" settings; {@code null} is the profit goal. */
	void setTaskAdvisor(boolean enabled, TaskRanking.Metric metric)
	{
		adviceEnabled = enabled;
		adviceMetric = metric == null ? TaskRanking.Metric.PROFIT : metric;
	}

	/** Whether the task advisor has a row to show: it is on and the observed task has kills left. */
	boolean isAdviceShown()
	{
		return adviceEnabled && tracker.isTaskObserved() && tracker.getCurrentRemaining() > 0;
	}

	/**
	 * The task advisor's result for the current task ({@link TaskAdvisor}), or {@code null} without one or when
	 * {@link #isAdviceShown} is false. Worked out again, in the background, only when the task, the records, how they
	 * are valued or the goal changed; meanwhile the same account's previous advice for the same task, goal and "Loot
	 * price" mode is returned, or {@code null}.
	 */
	TaskAdvisor.Result advice()
	{
		TaskAdvisor.Outcome outcome = adviceOutcome();
		return outcome == null ? null : outcome.getAdvice();
	}

	/**
	 * Which version of the current task, standard or Catacombs variant, is better on the goal
	 * ({@link TaskAdvisor#adviseVariant}), or {@code null}. Worked out with {@link #advice}, by the same rules.
	 */
	TaskAdvisor.VersionAdvice variantAdvice()
	{
		TaskAdvisor.Outcome outcome = adviceOutcome();
		return outcome == null ? null : outcome.getVariant();
	}

	/**
	 * Whether the current task is better with a cannon or without one on the goal ({@link TaskAdvisor#adviseCannon}),
	 * or {@code null}. Worked out with {@link #advice}, by the same rules.
	 */
	TaskAdvisor.VersionAdvice cannonAdvice()
	{
		TaskAdvisor.Outcome outcome = adviceOutcome();
		return outcome == null ? null : outcome.getCannon();
	}

	/** {@link #advice}, {@link #variantAdvice} and {@link #cannonAdvice}, worked out together. */
	private TaskAdvisor.Outcome adviceOutcome()
	{
		if (!isAdviceShown())
		{
			return null;
		}
		int creatureId = tracker.getCurrentCreatureId();
		String monster = tracker.getCurrentMonster();
		Valued now = valued();
		List<LedgerRecord> from = records;
		TaskRanking.Metric metric = adviceMetric;
		List<Object> key = Arrays.asList(version, now.valuationVersion, creatureId, TaskStatistics.normalize(monster),
			metric, now.mode);
		if (advice.request(key, () -> TaskAdvisor.adviseAll(from, creatureId, monster, metric, now.mode, now.prices,
			now.hidden)))
		{
			return advice.value;
		}
		// Worked out in the background: the same task's advice for the same goal and mode stays until then (the kept
		// advice is always this account's: setHistory drops it on another account).
		List<Object> shown = advice.key;
		return shown != null && shown.subList(2, 6).equals(key.subList(2, 6)) ? advice.value : null;
	}

	/**
	 * How many times the advice was worked out (asked of the background).
	 * Performance seam: tests assert this doesn't grow on the refresh timer.
	 */
	int getAdviceBuildCount()
	{
		return advice.builds;
	}

	// ---- Trends and totals ----

	/**
	 * The completed-task totals in {@code grouping} ({@link OverallTotals}), once per records, entries and pricing,
	 * worked out in the background: {@code null} until it is ready. By master also counts the "Skipped / blocked"
	 * entries as assignments: the unmodifiable entries list is captured with the records (both are replaced,
	 * never changed, and {@link #version} covers both), and its entries are not changed once loaded.
	 */
	List<OverallTotals.Entry> overallTotals(OverallTotals.Grouping grouping)
	{
		ZoneId zone = ZoneId.systemDefault();
		Valued now = valued();
		List<LedgerRecord> from = records;
		List<AssignmentEntry> entries = assignments;
		return overallTotals.get(Arrays.asList(version, now.valuationVersion, grouping, zone),
			() -> Collections.unmodifiableList(OverallTotals.summarize(from, entries, grouping, now.mode, now.prices,
				now.hidden, zone)));
	}

	/**
	 * How many times the overall totals were worked out.
	 * Performance seam: tests assert this doesn't grow on the refresh timer.
	 */
	int getOverallTotalsBuildCount()
	{
		return overallTotals.builds;
	}

	/**
	 * The Trends list for {@code metric} ({@link TaskRanking}), worked out once per records and pricing, in the
	 * background: {@code null} until it is ready.
	 */
	List<TaskRanking.Entry> ranking(TaskRanking.Metric metric)
	{
		Valued now = valued();
		List<LedgerRecord> from = records;
		return ranking.get(Arrays.asList(version, now.valuationVersion, metric),
			() -> Collections.unmodifiableList(TaskRanking.rank(from, metric, now.mode, now.prices, now.hidden)));
	}

	/**
	 * How many times the Trends list was worked out.
	 * Performance seam: tests assert this doesn't grow on the refresh timer.
	 */
	int getRankingBuildCount()
	{
		return ranking.builds;
	}

	/**
	 * The Trends chart's Monster choices for {@code metric} ({@link TaskChart#monsters}), in the background:
	 * {@code null} until they are ready.
	 */
	List<TaskChart.Monster> chartMonsters(TaskRanking.Metric metric)
	{
		Valued now = valued();
		List<LedgerRecord> from = records;
		return chartMonsters.get(Arrays.asList(version, now.valuationVersion, metric),
			() -> Collections.unmodifiableList(TaskChart.monsters(from, metric, now.mode, now.prices, now.hidden)));
	}

	/**
	 * The Trends chart's points for {@code monster} and {@code metric} ({@link TaskChart#points}), in the background:
	 * {@code null} until they are ready.
	 */
	List<TaskChart.Point> chartPoints(TaskChart.Monster monster, TaskRanking.Metric metric)
	{
		Valued now = valued();
		List<LedgerRecord> from = records;
		return chartPoints.get(Arrays.asList(version, now.valuationVersion, monster, metric),
			() -> Collections.unmodifiableList(TaskChart.points(from, monster, metric, now.mode, now.prices,
				now.hidden)));
	}

	// ---- Luck meter ----

	/** The bundled luck-meter snapshot, read once; {@code null} when it could not be read. */
	private LuckMeter luckMeter()
	{
		if (!luckMeterLoaded)
		{
			luckMeterLoaded = true;
			luckMeter = luckMeterSource.get();
		}
		return luckMeter;
	}

	/**
	 * The luck meter's task choices ({@link LuckMeter#supportedTasks}), or {@code null} when its data could not be
	 * read.
	 */
	List<String> luckTasks()
	{
		LuckMeter meter = luckMeter();
		if (meter == null)
		{
			return null;
		}
		return luckTasks.get(Collections.singletonList(version),
			() -> Collections.unmodifiableList(meter.supportedTasks(records)));
	}

	/**
	 * The luck meter for {@code task} (none: {@code null}): its samples' loot item names are asked for, and the drops
	 * are compared once they are here. Worked out again only when the records, the task or item names change.
	 */
	LuckView luck(String task)
	{
		LuckMeter meter = luckMeter();
		if (meter == null)
		{
			return LuckView.UNAVAILABLE;
		}
		return luck.get(Arrays.asList(version, task, names.itemNamesVersion()), () -> luckView(meter, task));
	}

	private LuckView luckView(LuckMeter meter, String task)
	{
		List<LedgerRecord> samples = task == null ? Collections.emptyList() : meter.samples(records, task);
		Set<Integer> lootIds = new HashSet<>();
		for (LedgerRecord record : samples)
		{
			if (record.getLoot() != null)
			{
				for (Integer id : record.getLoot().keySet())
				{
					if (id != null)
					{
						lootIds.add(id);
					}
				}
			}
		}
		names.requestItemNames(lootIds);
		boolean namesPending = false;
		boolean namesFailed = false;
		for (Integer id : lootIds)
		{
			namesPending |= names.isItemNamePending(id);
			namesFailed |= names.isItemNameFailed(id);
		}
		if (samples.isEmpty())
		{
			return new LuckView(LuckView.State.NO_SAMPLES, null, false);
		}
		if (namesPending)
		{
			return new LuckView(LuckView.State.NAMES_LOADING, null, false);
		}
		return new LuckView(LuckView.State.SUMMARY, meter.summarize(records, task, names.itemNames()), namesFailed);
	}

	/**
	 * How many times the luck meter was worked out.
	 * Performance seam: tests assert this doesn't grow on the refresh timer.
	 */
	int getLuckBuildCount()
	{
		return luck.builds;
	}

	// ---- Skipped / blocked ----

	/** The "Skipped / blocked" rows, newest first, worked out once per change of the entries. */
	List<AssignmentRow> assignmentRows()
	{
		ZoneId zone = ZoneId.systemDefault();
		return assignmentRows.get(Arrays.asList(version, zone), () ->
		{
			DateTimeFormatter date = AssignmentLog.shortDate(zone);
			DateTimeFormatter dateTime = LedgerHistory.shortDateTime(zone);
			List<AssignmentRow> rows = new ArrayList<>();
			for (AssignmentEntry entry : assignments)
			{
				rows.add(new AssignmentRow(entry, AssignmentLog.monster(entry), AssignmentLog.status(entry, date),
					AssignmentLog.tooltip(entry, dateTime)));
			}
			return Collections.unmodifiableList(rows);
		});
	}

	/**
	 * How many times the "Skipped / blocked" rows were worked out.
	 * Performance seam: tests assert this doesn't grow on the refresh timer.
	 */
	int getAssignmentRowsBuildCount()
	{
		return assignmentRows.builds;
	}

	// ---- What to bring ----

	/**
	 * What to bring for the {@code remaining} kills of the task {@code stats} are for ({@link SupplyRecommendation}),
	 * with its supplies named and sorted; the supplies' names are asked for. Worked out again only when the
	 * statistics, the kills left or item names change.
	 */
	WhatToBring whatToBring(TaskStatistics stats, int remaining)
	{
		return whatToBring.get(Arrays.asList(stats, remaining, names.itemNamesVersion()), () ->
		{
			SupplyRecommendation.Estimate estimate = SupplyRecommendation.forTask(stats, remaining);
			return estimate == null ? null : new WhatToBring(estimate, bringRows(estimate, stats));
		});
	}

	/**
	 * How many times What to bring was worked out.
	 * Performance seam: tests assert this doesn't grow on the refresh timer.
	 */
	int getWhatToBringBuildCount()
	{
		return whatToBring.builds;
	}

	/** The supplies of {@code estimate}, named and in {@link BringRow#ORDER}. */
	private List<BringRow> bringRows(SupplyRecommendation.Estimate estimate, TaskStatistics stats)
	{
		List<BringRow> rows = new ArrayList<>();
		for (Map.Entry<Integer, Long> item : estimate.getSupplies().entrySet())
		{
			int itemId = item.getKey();
			Integer dosesPerItem = stats.getSupplyDoses().get(itemId);
			ItemDetails.Unit unit = dosesPerItem == null ? ItemDetails.Unit.ITEMS : ItemDetails.doseUnit(itemId);
			String name = ItemDetails.displayName(itemId, names::itemName, dosesPerItem != null);
			Long price = stats.getSupplyUnitGePrices().get(itemId);
			Long value = null;
			if (price != null)
			{
				ItemTally one = new ItemTally();
				one.add(itemId, item.getValue());
				value = ItemValuation.total(one, Collections.singletonMap(itemId, price),
					dosesPerItem == null ? null : Collections.singletonMap(itemId, dosesPerItem));
			}
			rows.add(new BringRow(itemId, name, item.getValue(), unit, value));
		}
		rows.sort(BringRow.ORDER);
		return Collections.unmodifiableList(rows);
	}

	/** How the records are valued, as one immutable snapshot for the background views. */
	private static final class Valued
	{
		private final long valuationVersion;
		private final LootPriceMode mode;
		/** An unmodifiable copy of today's prices the history is valued at; never {@code null}. */
		private final Map<Integer, Long> prices;
		/** Loot items left out of the loot value; immutable. */
		private final HiddenLoot hidden;

		private Valued(long valuationVersion, LootPriceMode mode, Map<Integer, Long> prices, HiddenLoot hidden)
		{
			this.valuationVersion = valuationVersion;
			this.mode = mode;
			this.prices = prices;
			this.hidden = hidden;
		}
	}

	/**
	 * One derived view worked out in the background: its latest kept result and what it was worked out from, and the
	 * inputs being worked out now. Its fields are read and written on the EDT only; the background runs only the
	 * {@code compute} supplier, which reads nothing but what it captured.
	 */
	private final class Background<T>
	{
		/** What {@link #value} was worked out from; {@code null} before the first result. */
		private List<Object> key;
		private T value;
		/** The inputs being worked out now, or {@code null}; only a result for these is kept. */
		private List<Object> pendingKey;
		/** How many times it was asked of the background. */
		private int builds;
		/** True while {@link #request} queues the work, so a result delivered at once (tests) tells no one. */
		private boolean requesting;

		/** The result for {@code newKey}, or {@code null} while it is worked out in the background. */
		private T get(List<Object> newKey, Supplier<T> compute)
		{
			return request(newKey, compute) ? value : null;
		}

		/**
		 * Whether {@link #value} is the result for {@code newKey}; if not, has it worked out in the background (once
		 * per inputs: asked again while it is, nothing more is queued).
		 */
		private boolean request(List<Object> newKey, Supplier<T> compute)
		{
			if (newKey.equals(key))
			{
				// Back to the kept inputs: a result still on its way for others asked for since is dropped.
				pendingKey = null;
				return true;
			}
			if (closed || newKey.equals(pendingKey))
			{
				return false;
			}
			pendingKey = newKey;
			builds++;
			requesting = true;
			try
			{
				background.execute(() ->
				{
					// Background thread: only the captured snapshot is read. Should it throw, the executor reports it and
					// the view keeps what it shows until its inputs change.
					T result = compute.get();
					edt.execute(() -> deliver(newKey, result));
				});
			}
			catch (RejectedExecutionException e)
			{
				// The client is shutting down its executor: nothing is worked out any more.
				pendingKey = null;
			}
			finally
			{
				requesting = false;
			}
			return newKey.equals(key);
		}

		/** Drops the kept result and the inputs being worked out, so a result still on its way is dropped. */
		private void reset()
		{
			key = null;
			value = null;
			pendingKey = null;
		}

		/** EDT. Keeps a result only if it is for the latest inputs asked for; an older one is dropped. */
		private void deliver(List<Object> resultKey, T result)
		{
			if (closed || !resultKey.equals(pendingKey))
			{
				return;
			}
			pendingKey = null;
			key = resultKey;
			value = result;
			if (!requesting)
			{
				onReady.run();
			}
		}
	}

	/** One derived view's latest result and what it was worked out from. */
	private static final class Cached<T>
	{
		private List<Object> key;
		private T value;
		/** How many times it was worked out. */
		private int builds;

		private T get(List<Object> newKey, Supplier<T> compute)
		{
			if (!newKey.equals(key))
			{
				builds++;
				value = compute.get();
				key = newKey;
			}
			return value;
		}
	}
}
