package com.shmayvan.slayerledger;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Expected counts for the selected, bundled Slayer drop-table entries. An instance holds one validated snapshot
 * ({@link #load}); the comparison logic is pure (no RuneLite types).
 */
@Slf4j
final class LuckMeter
{
	/** The bundled snapshot, relative to this class. */
	static final String SNAPSHOT_RESOURCE = "slayer-drop-snapshot.json";
	/** The only snapshot schema version this build reads. */
	static final int SNAPSHOT_VERSION = 1;
	/** Suffix the plugin adds to a noted item's name. */
	private static final String NOTED_SUFFIX = " (noted)";

	private final Snapshot snapshot;

	private LuckMeter(Snapshot snapshot)
	{
		this.snapshot = snapshot;
	}

	/**
	 * Reads and validates the snapshot {@code resource} (relative to this class) with {@code gson}, the client's
	 * injected instance. Returns {@code null}, logged at debug level, when the resource is missing, unreadable,
	 * malformed or invalid, so a bad snapshot never breaks the panel.
	 */
	static LuckMeter load(Gson gson, String resource)
	{
		try (InputStream stream = LuckMeter.class.getResourceAsStream(resource))
		{
			if (stream == null)
			{
				log.debug("Slayer drop snapshot {} is missing", resource);
				return null;
			}
			return parse(gson, new InputStreamReader(stream, StandardCharsets.UTF_8));
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Unable to load the Slayer drop snapshot {}", resource, e);
			return null;
		}
	}

	/** Parses and validates a snapshot. Throws a {@link RuntimeException} when it is malformed or invalid. */
	static LuckMeter parse(Gson gson, Reader reader)
	{
		Snapshot snapshot = gson.fromJson(reader, Snapshot.class);
		validate(snapshot);
		return new LuckMeter(snapshot);
	}

	/**
	 * Whether a record is a sample: a completed, non-partial played task with kills whose loot was tracked. Records
	 * saved before loot tracking (schema 1) have neither {@code loot} nor values; a tracked task with no loot has no
	 * {@code loot} map (an empty tally is stored as {@code null}) but is valued.
	 */
	static boolean isSample(LedgerRecord record)
	{
		return record != null && record.isCompleted() && !record.isPartial() && record.getKills() > 0
			&& (record.getLoot() != null || LedgerHistory.isValued(record));
	}

	/** Supported task names (as in the snapshot) that have at least one sample, in snapshot order. */
	List<String> supportedTasks(Collection<LedgerRecord> records)
	{
		if (records == null || records.isEmpty())
		{
			return Collections.emptyList();
		}
		Set<String> recordedTasks = new HashSet<>();
		for (LedgerRecord record : records)
		{
			if (isSample(record))
			{
				recordedTasks.add(TaskStatistics.normalize(record.getMonster()));
			}
		}
		List<String> tasks = new ArrayList<>();
		for (Table table : snapshot.tables)
		{
			if (recordedTasks.contains(TaskStatistics.normalize(table.task)))
			{
				tasks.add(table.task);
			}
		}
		return Collections.unmodifiableList(tasks);
	}

	/**
	 * The samples for {@code task}, matched by the {@link TaskStatistics#normalize}d name (trimmed, lower case). The
	 * snapshot has no creature ids, so a record matches by name whether or not it has a {@code creatureId}.
	 */
	List<LedgerRecord> samples(Collection<LedgerRecord> records, String task)
	{
		Table table = table(task);
		if (table == null || records == null)
		{
			return Collections.emptyList();
		}
		String key = TaskStatistics.normalize(table.task);
		List<LedgerRecord> matching = new ArrayList<>();
		for (LedgerRecord record : records)
		{
			if (isSample(record) && key.equals(TaskStatistics.normalize(record.getMonster())))
			{
				matching.add(record);
			}
		}
		return matching;
	}

	/**
	 * Aggregates the samples for one task. Actual items are matched to snapshot names using the supplied item-name
	 * lookup (a noted name counts as its item); unknown names are not guessed.
	 */
	Summary summarize(Collection<LedgerRecord> records, String task, Map<Integer, String> itemNames)
	{
		Table table = table(task);
		List<LedgerRecord> samples = samples(records, task);
		if (table == null || samples.isEmpty())
		{
			return Summary.empty(table == null ? task : table.task);
		}

		Map<String, Long> actualByItem = new HashMap<>();
		long kills = 0;
		for (LedgerRecord record : samples)
		{
			kills = saturatedAdd(kills, record.getKills());
			Map<Integer, Long> loot = record.getLoot();
			if (loot == null || itemNames == null)
			{
				continue;
			}
			for (Map.Entry<Integer, Long> drop : loot.entrySet())
			{
				String key = itemKey(itemNames.get(drop.getKey()));
				Long quantity = drop.getValue();
				if (key == null || quantity == null || quantity <= 0)
				{
					continue;
				}
				actualByItem.merge(key, quantity, LuckMeter::saturatedAdd);
			}
		}

		List<DropEntry> drops = new ArrayList<>();
		for (Drop drop : table.drops)
		{
			long actual = actualByItem.getOrDefault(itemKey(drop.item), 0L);
			double expected = (double) kills * drop.numerator / drop.denominator;
			drops.add(new DropEntry(drop.item, drop.numerator, drop.denominator, actual, expected));
		}
		return new Summary(table.task, samples.size(), kills, drops);
	}

	/**
	 * The chance of at least {@code observed} drops when the count is Poisson with mean {@code expected}: 1 for
	 * {@code observed <= 0}. Summed in log space, so large means and counts neither overflow nor underflow early.
	 */
	static double chanceOfAtLeast(long observed, double expected)
	{
		if (observed <= 0)
		{
			return 1;
		}
		if (!(expected > 0) || Double.isInfinite(expected))
		{
			return Double.isInfinite(expected) ? 1 : 0;
		}
		double logMean = Math.log(expected);
		if (observed > expected)
		{
			// Upper tail directly: sum the terms from observed up until they no longer change the total.
			double logTerm = -expected + observed * logMean - logFactorial(observed);
			double sum = 0;
			for (long i = observed; ; i++)
			{
				double term = Math.exp(logTerm);
				sum += term;
				if (term <= sum * 1e-17 || i - observed > 1_000_000)
				{
					break;
				}
				logTerm += logMean - Math.log(i + 1);
			}
			return clamp(sum);
		}
		// 1 - P(X < observed): the lower tail has at most observed (<= expected) terms.
		double logTerm = -expected;
		double below = 0;
		for (long i = 0; i < observed; i++)
		{
			below += Math.exp(logTerm);
			logTerm += logMean - Math.log(i + 1);
		}
		return clamp(1 - below);
	}

	private static double logFactorial(long n)
	{
		double sum = 0;
		for (long i = 2; i <= n; i++)
		{
			sum += Math.log(i);
		}
		return sum;
	}

	private static double clamp(double value)
	{
		return Math.max(0, Math.min(1, value));
	}

	/** Snapshot metadata, for the attribution tooltip. */
	String getSnapshotDate()
	{
		return snapshot.snapshotDate;
	}

	String getSource()
	{
		return snapshot.source;
	}

	String getLicense()
	{
		return snapshot.license;
	}

	/**
	 * The item name as matched: normalized, with the plugin's " (noted)" suffix dropped. {@code null} for a blank
	 * name, which matches no drop.
	 */
	private static String itemKey(String name)
	{
		String key = TaskStatistics.normalize(name);
		return key == null ? null : key.endsWith(NOTED_SUFFIX) ? key.substring(0, key.length() - NOTED_SUFFIX.length()).trim() : key;
	}

	private Table table(String task)
	{
		String key = TaskStatistics.normalize(task);
		for (Table table : snapshot.tables)
		{
			if (TaskStatistics.normalize(table.task).equals(key))
			{
				return table;
			}
		}
		return null;
	}

	private static long saturatedAdd(long left, long right)
	{
		try
		{
			return Math.addExact(left, right);
		}
		catch (ArithmeticException e)
		{
			return right >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
		}
	}

	private static void validate(Snapshot snapshot)
	{
		if (snapshot == null || snapshot.version != SNAPSHOT_VERSION || snapshot.tables == null
			|| snapshot.tables.isEmpty() || isBlank(snapshot.snapshotDate) || isBlank(snapshot.license)
			|| isBlank(snapshot.licenseUrl) || isBlank(snapshot.source) || isBlank(snapshot.contributors)
			|| isBlank(snapshot.attribution) || isBlank(snapshot.scope))
		{
			throw new IllegalStateException("Slayer drop snapshot metadata is missing or unsupported.");
		}
		Set<String> tasks = new HashSet<>();
		for (Table table : snapshot.tables)
		{
			if (table == null || isBlank(table.task) || isBlank(table.article) || isBlank(table.page)
				|| table.revision <= 0 || table.drops == null || table.drops.isEmpty()
				|| !tasks.add(TaskStatistics.normalize(table.task)))
			{
				throw new IllegalStateException("Slayer drop snapshot has an invalid or duplicate task table.");
			}
			Set<String> items = new HashSet<>();
			for (Drop drop : table.drops)
			{
				if (drop == null || isBlank(drop.item) || drop.numerator <= 0 || drop.denominator <= 0
					|| drop.numerator > drop.denominator || !items.add(itemKey(drop.item)))
				{
					throw new IllegalStateException("Slayer drop snapshot has an invalid or duplicate drop.");
				}
			}
		}
	}

	private static boolean isBlank(String value)
	{
		return value == null || value.trim().isEmpty();
	}

	static final class Summary
	{
		private final String task;
		private final int taskCount;
		private final long kills;
		private final List<DropEntry> drops;

		private Summary(String task, int taskCount, long kills, List<DropEntry> drops)
		{
			this.task = task;
			this.taskCount = taskCount;
			this.kills = kills;
			this.drops = Collections.unmodifiableList(new ArrayList<>(drops));
		}

		private static Summary empty(String task)
		{
			return new Summary(task, 0, 0, Collections.emptyList());
		}

		String getTask()
		{
			return task;
		}

		/** Samples: completed tasks with loot data. */
		int getTaskCount()
		{
			return taskCount;
		}

		/** Kills of the samples only. */
		long getKills()
		{
			return kills;
		}

		List<DropEntry> getDrops()
		{
			return drops;
		}
	}

	static final class DropEntry
	{
		/** Below this expected count the ratio is hidden (owner decision). */
		static final double MIN_EXPECTED_FOR_RATIO = 1;

		private final String item;
		private final int numerator;
		private final int denominator;
		private final long actual;
		private final double expected;

		private DropEntry(String item, int numerator, int denominator, long actual, double expected)
		{
			this.item = item;
			this.numerator = numerator;
			this.denominator = denominator;
			this.actual = actual;
			this.expected = expected;
		}

		String getItem()
		{
			return item;
		}

		int getNumerator()
		{
			return numerator;
		}

		int getDenominator()
		{
			return denominator;
		}

		long getActual()
		{
			return actual;
		}

		double getExpected()
		{
			return expected;
		}

		/** Whether enough drops are expected to show the observed / expected ratio. */
		boolean hasRatio()
		{
			return expected >= MIN_EXPECTED_FOR_RATIO;
		}

		/** Observed / expected, or {@code NaN} when {@link #hasRatio()} is false. */
		double getRatio()
		{
			return hasRatio() ? actual / expected : Double.NaN;
		}

		/** The Poisson chance of at least the observed count, see {@link LuckMeter#chanceOfAtLeast}. */
		double getChanceOfAtLeastObserved()
		{
			return chanceOfAtLeast(actual, expected);
		}
	}

	private static final class Snapshot
	{
		private int version;
		private String snapshotDate;
		private String license;
		private String licenseUrl;
		private String source;
		private String contributors;
		private String attribution;
		private String scope;
		private List<Table> tables;
	}

	private static final class Table
	{
		private String task;
		private String article;
		private String page;
		private long revision;
		private List<Drop> drops;
	}

	private static final class Drop
	{
		private String item;
		private int numerator;
		private int denominator;
	}
}
