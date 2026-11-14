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
 * advisor and its settings, the advisor's plan, Overall totals and the "Skipped / blocked" rows.
 * Each is worked out on demand and cached until what it comes from changes: the records ({@link #setHistory}'s
 * version), how they are valued ({@link HistoryValuation#valuationVersion}), item names where they are shown
 * ({@link NameBook#itemNamesVersion}) and the view's own inputs (a metric, a grouping, a task). Asking again with
 * nothing changed returns the same object, so the panel's refresh timer never works them out again. The statistics are
 * kept until the model drops them ({@link #clearTaskStatistics}).
 *
 * <p>Off the EDT. The views that scan every record, the task advice, the advisor's plan and Overall
 * totals, are worked out on a background executor (the plugin's), once per inputs, from an immutable snapshot taken on
 * the EDT: the records list (unmodifiable and replaced, never changed; the records in it are not changed once loaded),
 * for Overall totals' By master the "Skipped / blocked" entries list (the same), the creature and goal for the advice,
 * the task, its kills left, the metric and the potion families for the plan, the "Loot price" mode and a copy of the
 * prices shown ({@link Valued}). Asked for while it is being worked out, such a view returns {@code null} (the advice:
 * the same task's previous advice, else {@code null}; the plan shown: the same task's previous plan, else
 * {@code null}), and the panel keeps what it shows. The result goes back to the EDT, where it is kept only if it is
 * still the latest one asked for: a result for older inputs (an older records or valuation version, another grouping,
 * goal or metric, other kills left) is dropped. A kept result tells {@link #setOnReady}'s listener, which has the panel
 * redraw. The background work touches no Swing and no mutable state; everything it reads is captured before it is
 * queued and everything it returns is immutable, published to the EDT through the EDT executor's queue.</p>
 *
 * <p>Kept on the EDT, worked out when asked: the per-task statistics ({@link #taskStatistics}), because the live card's
 * time left and the overlay's time per kill decide synchronously from them and ask for region names while building
 * them; the "Skipped / blocked" rows, which are small. Names are asked for on the EDT only.</p>
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
	/**
	 * Bumped only when {@link #records} change (a new list: they are replaced, never changed), not with the entries,
	 * so a "Skipped / blocked" entry doesn't work out the plan or its potion families again.
	 */
	private long recordsVersion;
	/** The account the records are of; another one drops the kept advice (see {@link #advice}). */
	private long account = SessionTracker.NO_ACCOUNT;
	/** How the records are valued, captured for the background views at {@link Valued#valuationVersion}. */
	private Valued valued;

	/** The "Task advisor" setting: whether the skip / block badge is shown. */
	private boolean adviceEnabled = true;
	/**
	 * The "Advisor metric" setting: what the plan picks by, and through {@link TaskAdvisor#badgeMetric} the rate
	 * the badge ranks by.
	 */
	private AdvisorMetric advisorMetric = AdvisorMetric.DEFAULT;

	/** Per-task aggregates, dropped when the history, the spot table or a region name changes. */
	private final Map<String, TaskStatistics> taskStatisticsCache = new HashMap<>();
	/** How many times a task's statistics were worked out ({@link #getTaskStatisticsBuildCount}). */
	private int taskStatisticsBuilds;
	/** {@link #effectiveSelection} per task and live selection; dropped with the statistics or new history. */
	private final Map<String, TaskSelection> effectiveSelectionCache = new HashMap<>();
	private final Background<TaskAdvisor.Result> advice = new Background<>();
	private final Background<TaskPlan> plan = new Background<>();
	/** The records' potion families for the plan ({@link TaskPlanner#potionFamilies}), per records and item names. */
	private final Cached<PotionFamilies> potionFamilies = new Cached<>();
	private final Background<List<OverallTotals.Entry>> overallTotals = new Background<>();
	private final Cached<List<AssignmentRow>> assignmentRows = new Cached<>();

	/**
	 * @param valuation  how the records are valued; only read
	 * @param names      region and item names; missing ones are asked for
	 * @param background works out the advice and Overall totals off the EDT
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
	 * its version); the views are worked out again when next asked for. Another account drops the kept advice and plan
	 * and any still being worked out, so the previous account's are never shown for the same task while the new
	 * account's are worked out. A new records list bumps {@link #recordsVersion}; the same list (new entries only)
	 * doesn't.
	 */
	void setHistory(long account, long version, List<LedgerRecord> records, List<AssignmentEntry> assignments)
	{
		if (account != this.account)
		{
			this.account = account;
			advice.reset();
			plan.reset();
		}
		this.version = version;
		if (records != this.records)
		{
			recordsVersion++;
		}
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

	// ---- Statistics ----

	/** Drops the cached statistics, after the history, the spot table or a region name changed. */
	void clearTaskStatistics()
	{
		taskStatisticsCache.clear();
		effectiveSelectionCache.clear();
	}

	/**
	 * Key of {@link #taskStatistics}: creature key, name as {@link TaskStatistics#normalize} matches it, the selection
	 * the statistics use (version and cannon use, {@link #effectiveSelection}, {@link TaskSelection#key})
	 * and the location (its spot key and the name shown), so a change of location or its name, version or cannon
	 * use, or a first cannon task in the history, gives other statistics. Nothing in the statistics is valued, so
	 * the "Loot price" mode, today's prices and the hidden loot are not part of it.
	 */
	private String taskStatisticsKey(int creatureId, String monster, TaskSelection selection,
		TaskLocation.Here location)
	{
		String name = TaskStatistics.normalize(monster);
		TaskLocation.Here here = location == null ? TaskLocation.Here.UNKNOWN : location;
		return creatureId + "\u0000" + (name == null ? "" : name) + "\u0000"
			+ effectiveSelection(creatureId, monster, selection).key() + "\u0000"
			+ (here.getSpot() == null ? "" : here.getSpot()) + "\u0000" + (here.getName() == null ? "" : here.getName());
	}

	/**
	 * The selection a task's statistics use for the live {@code selection} ({@link TaskStatistics#effectiveSelection}):
	 * cannon tasks only when the history has a completed cannon task of the task's selected version, else tasks
	 * with and without a cannon pooled. Cached with the statistics until {@link #clearTaskStatistics} or new history.
	 */
	private TaskSelection effectiveSelection(int creatureId, String monster, TaskSelection selection)
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

	/**
	 * A task's statistics, of the records {@code selection} selects (version and cannon use; cannon tasks
	 * only just when the history has some, {@link #effectiveSelection}): from its history at {@code location}'s spot
	 * when it has some there (a record's spot is {@link SlayerSpots#recordSpot}), else from all of it (see
	 * {@link TaskStatistics#getLocation}). Cached until {@link #clearTaskStatistics}.
	 */
	TaskStatistics taskStatistics(int creatureId, String monster, TaskSelection selection,
		TaskLocation.Here location)
	{
		String key = taskStatisticsKey(creatureId, monster, selection, location);
		TaskStatistics stats = taskStatisticsCache.get(key);
		if (stats == null)
		{
			SlayerSpots spots = names.spots();
			stats = TaskStatistics.forTask(records, creatureId, monster,
				effectiveSelection(creatureId, monster, selection), location, spots::recordSpot);
			taskStatisticsCache.put(key, stats);
			taskStatisticsBuilds++;
		}
		return stats;
	}

	/**
	 * How many times a task's statistics were worked out. Performance seam: tests assert this doesn't grow on the
	 * refresh timer, or when only how the history is valued changes.
	 */
	int getTaskStatisticsBuildCount()
	{
		return taskStatisticsBuilds;
	}

	private static TaskSelection selectionOrAny(TaskSelection selection)
	{
		return selection == null ? TaskSelection.ANY : selection;
	}

	// ---- Task advisor ----

	/**
	 * Applies the "Task advisor" and "Advisor metric" settings; {@code null} is
	 * {@link AdvisorMetric#DEFAULT}. The badge ranks by {@link TaskAdvisor#badgeMetric} of the metric.
	 */
	void setTaskAdvisor(boolean enabled, AdvisorMetric metric)
	{
		adviceEnabled = enabled;
		advisorMetric = AdvisorMetric.orDefault(metric);
	}

	/** The "Advisor metric" setting; never {@code null}. */
	AdvisorMetric advisorMetric()
	{
		return advisorMetric;
	}

	/** Whether the task advisor has a row to show: it is on and the observed task has kills left. */
	boolean isAdviceShown()
	{
		return adviceEnabled && tracker.isTaskObserved() && tracker.getCurrentRemaining() > 0;
	}

	/**
	 * The task advisor's result for the current task ({@link TaskAdvisor}), or {@code null} without one or when
	 * {@link #isAdviceShown} is false. It ranks by the rate the "Advisor metric" picks
	 * ({@link TaskAdvisor#badgeMetric}, the goal). Worked out again, in the background, only when the task, the
	 * records, how they are valued or the goal changed; meanwhile the same account's previous advice for the same
	 * task, goal and "Loot price" mode is returned, or {@code null}.
	 */
	TaskAdvisor.Result advice()
	{
		if (!isAdviceShown())
		{
			return null;
		}
		int creatureId = tracker.getCurrentCreatureId();
		String monster = tracker.getCurrentMonster();
		Valued now = valued();
		List<LedgerRecord> from = records;
		// Profit per hour and Cheapest both rank by profit: the same goal, so the same advice.
		TaskRanking.Metric metric = TaskAdvisor.badgeMetric(advisorMetric);
		List<Object> key = Arrays.asList(version, now.valuationVersion, creatureId, TaskStatistics.normalize(monster),
			metric, now.mode);
		if (advice.request(key, () -> TaskAdvisor.advise(from, creatureId, monster, metric, now.mode, now.prices,
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

	// ---- Advisor plan ----

	/**
	 * The advisor's plan to show for the current task: the plan for the current inputs ({@link #exactPlan}) or, while
	 * that one is worked out or the views wait for today's prices, the plan last kept for the same task (creature
	 * key, name, type and assigned area), "Advisor metric" and "Loot price" mode, so a kill doesn't blank it; only its
	 * kills left, records, prices, potion families or spot table may be older. {@code null} without a task (none
	 * observed, or no kills left) and while no such plan was kept. The kept plan is always this account's
	 * ({@link #setHistory} drops it on another account). Asking for it asks for the exact plan, as {@link #exactPlan}
	 * does.
	 */
	TaskPlan plan()
	{
		return plan(false);
	}

	/**
	 * The advisor's plan for exactly the current inputs ({@link TaskPlanner}), worked out in the background from the
	 * immutable snapshot: the task's creature key, name, type ({@link TaskType#of} its master) and assigned area
	 * (Konar's), its kills left, the records ({@link #recordsVersion}: not the "Skipped / blocked" entries), the
	 * "Advisor metric", how the records are valued ({@link Valued}: the "Loot price" mode, a copy of today's prices,
	 * the hidden loot), the spot table and the potion families ({@link #potionFamilies}). {@code null} while there is
	 * no task (none observed, or no kills left), while the views wait for today's prices (worked out when the wait
	 * ends) and while the plan for the current inputs is worked out: only the plan of the inputs last asked for is kept
	 * (the stale-result rule), and when it is ready {@link #setOnReady}'s listener is told. Asked for again with the
	 * same inputs, nothing is worked out. The model asks for it whenever an input changed; the chat line uses this one,
	 * so it never names older kills left.
	 */
	TaskPlan exactPlan()
	{
		return plan(true);
	}

	/** {@link #exactPlan}, or with {@code exact} false {@link #plan}'s kept plan while there is none. */
	private TaskPlan plan(boolean exact)
	{
		if (!tracker.isTaskObserved() || tracker.getCurrentRemaining() <= 0)
		{
			return null;
		}
		int creatureId = tracker.getCurrentCreatureId();
		String monster = tracker.getCurrentMonster();
		TaskContext context = tracker.getCurrentContext();
		TaskType type = TaskType.of(context.getMaster(), creatureId);
		String area = context.getLocation();
		AdvisorMetric metric = advisorMetric;
		// The task and how it is judged come first in the key: a kept plan is shown only for the same.
		List<Object> task = Arrays.asList(creatureId, monster, type, area, metric, valuation.lootPriceMode());
		if (!valuation.isWaitingForCurrentPrices())
		{
			int killsLeft = tracker.getCurrentRemaining();
			Valued now = valued();
			Map<Integer, String> families = potionFamilies().families;
			SlayerSpots spots = names.spots();
			List<LedgerRecord> from = records;
			List<Object> key = new ArrayList<>(task);
			key.addAll(Arrays.asList(recordsVersion, now.valuationVersion, killsLeft, families, spots));
			if (plan.request(key, () -> TaskPlanner.plan(TaskPlanner.Request.builder()
				.creatureId(creatureId)
				.task(monster)
				.taskType(type)
				.area(area)
				.killsLeft(killsLeft)
				.records(from)
				.metric(metric)
				.mode(now.mode)
				.prices(now.prices)
				.hidden(now.hidden)
				.spots(spots)
				.potionFamilies(families)
				.build())))
			{
				return plan.value;
			}
		}
		if (exact)
		{
			return null;
		}
		List<Object> shown = plan.key;
		return shown != null && shown.subList(0, task.size()).equals(task) ? plan.value : null;
	}

	/**
	 * Whether the name of a potion the task's records used (its creature's: {@link TaskStatistics#isSample}) is still
	 * on its way, so {@link #exactPlan}'s potion families (and with them what to bring) may still change. The chat line
	 * waits for it; another creature's potions don't hold it.
	 */
	boolean isPotionFamilyPending(int creatureId, String monster)
	{
		Set<Integer> pending = potionFamilies().pending;
		if (pending.isEmpty())
		{
			return false;
		}
		for (LedgerRecord record : records)
		{
			if (record.getSupplyDoses() == null || !TaskStatistics.isSample(record, creatureId, monster))
			{
				continue;
			}
			for (Integer id : record.getSupplyDoses().keySet())
			{
				if (pending.contains(id))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The records' potion families ({@link TaskPlanner#potionFamilies}) from the item names known, worked out again
	 * when the records change or names arrive; the names of the potions (the ids the records count in doses) are
	 * asked for. The map is unmodifiable and equal to the last one when no family changed, so the plan's inputs only
	 * change with a family.
	 */
	private PotionFamilies potionFamilies()
	{
		return potionFamilies.get(Arrays.asList(recordsVersion, names.itemNamesVersion()), () ->
		{
			Set<Integer> potions = new HashSet<>();
			for (LedgerRecord record : records)
			{
				if (record.getSupplyDoses() == null)
				{
					continue;
				}
				for (Integer id : record.getSupplyDoses().keySet())
				{
					if (id != null && ItemDetails.doseUnit(id) == ItemDetails.Unit.DOSES)
					{
						potions.add(id);
					}
				}
			}
			Set<Integer> pending = new HashSet<>();
			if (!potions.isEmpty())
			{
				names.requestItemNames(potions);
				for (Integer id : potions)
				{
					if (names.isItemNamePending(id))
					{
						pending.add(id);
					}
				}
			}
			return new PotionFamilies(Collections.unmodifiableMap(TaskPlanner.potionFamilies(records,
				names::itemName)), Collections.unmodifiableSet(pending));
		});
	}

	/**
	 * How many times the plan was worked out (asked of the background).
	 * Performance seam: tests assert this doesn't grow on the refresh timer.
	 */
	int getPlanBuildCount()
	{
		return plan.builds;
	}

	// ---- Totals ----

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

	// ---- Skipped / blocked ----

	/** The "Skipped / blocked" rows, newest first, worked out once per change of the entries. */
	List<AssignmentRow> assignmentRows()
	{
		ZoneId zone = ZoneId.systemDefault();
		return assignmentRows.get(Arrays.asList(version, zone), () ->
		{
			DateTimeFormatter dateTime = LedgerHistory.shortDateTime(zone);
			List<AssignmentRow> rows = new ArrayList<>();
			for (AssignmentEntry entry : assignments)
			{
				rows.add(new AssignmentRow(entry, AssignmentLog.monster(entry),
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

	/** The records' potion families for the plan, and the potions whose names are still on their way. Immutable. */
	private static final class PotionFamilies
	{
		/** Potion id to family ({@link TaskPlanner#potionFamilies}); unmodifiable. */
		private final Map<Integer, String> families;
		/** The potion ids whose names were asked for and have not arrived; unmodifiable. */
		private final Set<Integer> pending;

		private PotionFamilies(Map<Integer, String> families, Set<Integer> pending)
		{
			this.families = families;
			this.pending = pending;
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
