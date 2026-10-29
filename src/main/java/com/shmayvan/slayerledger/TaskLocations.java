package com.shmayvan.slayerledger;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * The bundled list of each Slayer task's locations, from the Old School RuneScape Wiki. An instance holds one
 * validated snapshot ({@link #load}); the lookup is pure (no RuneLite types).
 */
@Slf4j
final class TaskLocations
{
	/** The bundled snapshot, relative to this class. */
	static final String SNAPSHOT_RESOURCE = "slayer-task-locations.json";
	/** The only snapshot schema version this build reads. */
	static final int SNAPSHOT_VERSION = 1;

	private final Snapshot snapshot;
	/** Unmodifiable location lists by {@link TaskStatistics#normalize}d task name. */
	private final Map<String, List<String>> locationsByTask;

	private TaskLocations(Snapshot snapshot)
	{
		this.snapshot = snapshot;
		Map<String, List<String>> byTask = new HashMap<>();
		for (Entry entry : snapshot.tasks)
		{
			byTask.put(TaskStatistics.normalize(entry.task),
				Collections.unmodifiableList(new ArrayList<>(entry.locations)));
		}
		this.locationsByTask = byTask;
	}

	/**
	 * Reads and validates the snapshot {@code resource} (relative to this class) with {@code gson}, the client's
	 * injected instance. Returns {@code null}, logged at debug level, when the resource is missing, unreadable,
	 * malformed or invalid, so bad data never breaks the plugin.
	 */
	static TaskLocations load(Gson gson, String resource)
	{
		try (InputStream stream = TaskLocations.class.getResourceAsStream(resource))
		{
			if (stream == null)
			{
				log.debug("Slayer task locations {} are missing", resource);
				return null;
			}
			return parse(gson, new InputStreamReader(stream, StandardCharsets.UTF_8));
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Unable to load the Slayer task locations {}", resource, e);
			return null;
		}
	}

	/** Parses and validates a snapshot. Throws a {@link RuntimeException} when it is malformed or invalid. */
	static TaskLocations parse(Gson gson, Reader reader)
	{
		Snapshot snapshot = gson.fromJson(reader, Snapshot.class);
		validate(snapshot);
		return new TaskLocations(snapshot);
	}

	/**
	 * The Wiki's locations for {@code taskName}, matched by the {@link TaskStatistics#normalize}d name (trimmed, lower
	 * case), in the Wiki's order. Unmodifiable; empty when the task is unknown or the name is {@code null}.
	 */
	List<String> locationsFor(String taskName)
	{
		List<String> locations = locationsByTask.get(TaskStatistics.normalize(taskName));
		return locations == null ? Collections.emptyList() : locations;
	}

	/** The task names in the snapshot, in snapshot order. Unmodifiable. */
	List<String> tasks()
	{
		List<String> tasks = new ArrayList<>(snapshot.tasks.size());
		for (Entry entry : snapshot.tasks)
		{
			tasks.add(entry.task);
		}
		return Collections.unmodifiableList(tasks);
	}

	/** Snapshot metadata, for attribution. */
	String getRetrieved()
	{
		return snapshot.retrieved;
	}

	String getSource()
	{
		return snapshot.source;
	}

	String getLicense()
	{
		return snapshot.license;
	}

	String getAttribution()
	{
		return snapshot.attribution;
	}

	private static void validate(Snapshot snapshot)
	{
		if (snapshot == null || snapshot.version != SNAPSHOT_VERSION || snapshot.tasks == null
			|| snapshot.tasks.isEmpty() || isBlank(snapshot.source) || isBlank(snapshot.license)
			|| isBlank(snapshot.licenseUrl) || isBlank(snapshot.contributors) || isBlank(snapshot.retrieved)
			|| isBlank(snapshot.attribution))
		{
			throw new IllegalStateException("Slayer task locations metadata is missing or unsupported.");
		}
		Set<String> tasks = new HashSet<>();
		for (Entry entry : snapshot.tasks)
		{
			if (entry == null || isBlank(entry.task) || isBlank(entry.article) || isBlank(entry.page)
				|| entry.revision <= 0 || entry.locations == null || entry.locations.isEmpty()
				|| !tasks.add(TaskStatistics.normalize(entry.task)))
			{
				throw new IllegalStateException("Slayer task locations have an invalid or duplicate task.");
			}
			Set<String> locations = new HashSet<>();
			for (String location : entry.locations)
			{
				if (isBlank(location) || !locations.add(TaskStatistics.normalize(location)))
				{
					throw new IllegalStateException("Slayer task locations have a blank or duplicate location.");
				}
			}
		}
	}

	private static boolean isBlank(String value)
	{
		return value == null || value.trim().isEmpty();
	}

	private static final class Snapshot
	{
		private int version;
		private String source;
		private String license;
		private String licenseUrl;
		private String contributors;
		private String retrieved;
		private String attribution;
		private List<Entry> tasks;
	}

	private static final class Entry
	{
		private String task;
		private String article;
		private String page;
		private long revision;
		private List<String> locations;
	}
}
