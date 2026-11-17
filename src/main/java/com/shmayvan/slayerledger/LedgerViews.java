package com.shmayvan.slayerledger;

import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import lombok.*;

/**
 * The shown history's derived views (split from {@link LedgerHistoryModel}): the per-task statistics, the
 * advisor's plan, Overall totals and the "Skipped / blocked" rows.
 * Each is worked out on demand and cached until what it comes from changes: the records ({@link #setHistory}'s
 * version), how they are valued ({@link HistoryValuation#valuationVersion}), item names where they are shown
 * ({@link NameBook#itemNamesVersion}) and the view's own inputs (a grouping, a task). Asking again with
 * nothing changed returns the same object, so the panel's refresh timer never works them out again. The statistics are
 * kept until the model drops them ({@link #clearTaskStatistics}).
 *
 * <p>Off the EDT. The views that scan every record, the advisor's plan and Overall totals, are worked
 * out on a background executor (the plugin's), once per inputs, from an immutable snapshot taken on the EDT: the
 * records list (unmodifiable and replaced, never changed; the records in it are not changed once loaded), for Overall
 * totals' By master the "Skipped / blocked" entries list (the same), the task, its kills left and the potion families
 * for the plan, the "Loot price" mode and a copy of the prices shown ({@link Valued}). Asked for while it is being
 * worked out, such a view returns {@code null} (the plan shown: the same task's previous plan, else {@code null}), and
 * the panel keeps what it shows. The result goes back to the EDT, where it is kept only if it is still the latest one
 * asked for: a result for older inputs (an older records or valuation version, another grouping, other kills left) is
 * dropped. A kept result tells {@link #setOnReady}'s listener, which has the panel redraw. The background work
 * touches no Swing and no mutable state; everything it reads is captured before it is queued and everything it
 * returns is immutable, published to the EDT through the EDT executor's queue.</p>
 *
 * <p>Kept on the EDT, worked out when asked: the per-task statistics ({@link #taskStatistics}), because the live card's
 * time left and the overlay's time per kill decide synchronously from them and ask for region names while building
 * them; the "Skipped / blocked" rows, which are small. Names are asked for on the EDT only.</p>
 *
 * <p>Threading: EDT only, like the model that owns it (except the background work described above). The results are
 * unmodifiable.</p>
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class LedgerViews
{
	private final SessionTracker tracker;
	/** How the records are valued; only read. */
	private final HistoryValuation valuation;
	/** Region and item names; missing ones are asked for. */
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
	/** The account the records are of; another one drops the kept plan (see {@link #plan}). */
	private long account = SessionTracker.NO_ACCOUNT;
	/** How the records are valued, captured for the background views at {@link Valued#valuationVersion}. */
	private Valued valued;

	/** Per-task aggregates, dropped when the history, the spot table or a region name changes. */
	private final Map<String, TaskStatistics> taskStatisticsCache = new HashMap<>();
	/** How many times a task's statistics were worked out ({@link #getTaskStatisticsBuildCount}). */
	private int taskStatisticsBuilds;
	/** {@link #effectiveSelection} per task and live selection; dropped with the statistics or new history. */
	private final Map<String, TaskSelection> effectiveSelectionCache = new HashMap<>();
	private final Background<TaskPlan> plan = new Background<>();
	/** The records' potion families for the plan ({@link TaskPlanner#potionFamilies}), per records and item names. */
	private final Cached<Map<Integer, String>> potionFamilies = new Cached<>();
	private final Background<List<OverallTotals.Entry>> overallTotals = new Background<>();
	private final Cached<List<AssignmentRow>> assignmentRows = new Cached<>();

	/**
	 * Sets the history the views come from (the model's account, records and entries, newest first, unmodifiable, and
	 * its version); the views are worked out again when next asked for. Another account drops the kept plan and any
	 * still being worked out, so the previous account's is never shown for the same task while the new account's is
	 * worked out. A new records list bumps {@link #recordsVersion}; the same list (new entries only)
	 * doesn't.
	 */
	void setHistory(long account, long version, List<LedgerRecord> records, List<AssignmentEntry> assignments)
	{
		if (account != this.account)
		{
			this.account = account;
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

	// ---- Advisor plan ----

	/**
	 * The advisor's plan to show for the current task ({@link TaskPlanner}): the plan for the current inputs, worked
	 * out in the background from the immutable snapshot: the task's creature key, name, type ({@link TaskType#of} its
	 * master) and assigned area (Konar's), its kills left, the records ({@link #recordsVersion}: not the "Skipped /
	 * blocked" entries), today's prices ({@link Valued}, at its valuation version), the spot table and the potion
	 * families ({@link #potionFamilies}). While that plan is worked out or the views wait for today's prices (it is
	 * worked out when the wait ends), the plan last kept for the same task (creature key, name, type and assigned
	 * area), so a kill doesn't blank it; only its kills left, records, prices, potion families or spot table may be
	 * older. {@code null} without a task (none observed, or no kills left) and while no such plan was kept. Only the
	 * plan of the inputs last asked for is kept (the stale-result rule), and when it is ready {@link #setOnReady}'s
	 * listener is told. Asked for again with the same inputs, nothing is worked out. The kept plan is always this
	 * account's ({@link #setHistory} drops it on another account).
	 */
	TaskPlan plan()
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
		// The task comes first in the key: a kept plan is shown only for the same.
		List<Object> task = Arrays.asList(creatureId, monster, type, area);
		if (!valuation.isWaitingForCurrentPrices())
		{
			int killsLeft = tracker.getCurrentRemaining();
			Valued now = valued();
			Map<Integer, String> families = potionFamilies();
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
				.prices(now.prices)
				.spots(spots)
				.potionFamilies(families)
				.build())))
			{
				return plan.value;
			}
		}
		List<Object> shown = plan.key;
		return shown != null && shown.subList(0, task.size()).equals(task) ? plan.value : null;
	}

	/**
	 * The records' potion families ({@link TaskPlanner#potionFamilies}) from the item names known, worked out again
	 * when the records change or names arrive; the names of the potions (the ids the records count in doses) are
	 * asked for. The map is unmodifiable and equal to the last one when no family changed, so the plan's inputs only
	 * change with a family.
	 */
	private Map<Integer, String> potionFamilies()
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
			if (!potions.isEmpty())
			{
				names.requestItemNames(potions);
			}
			return Collections.unmodifiableMap(TaskPlanner.potionFamilies(records, names::itemName));
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
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	private static final class Valued
	{
		private final long valuationVersion;
		private final LootPriceMode mode;
		/** An unmodifiable copy of today's prices the history is valued at; never {@code null}. */
		private final Map<Integer, Long> prices;
		/** Loot items left out of the loot value; immutable. */
		private final HiddenLoot hidden;
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
