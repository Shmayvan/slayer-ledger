package com.shmayvan.slayerledger;

import com.google.gson.*;
import java.util.*;

/**
 * The icon of each Slayer task: the item whose sprite RuneLite's core Slayer plugin shows for the task in its
 * infobox (mostly the Slayer-guide pictures, {@code ItemID.SLAYERGUIDE_*}; boss tasks show their pet). Overall totals
 * draws it before a monster's name ({@link OverallTotalsSection}).
 *
 * <p>The table is the bundled {@code task-icons.json} ({@link BundledData}), a copy of the core plugin's
 * {@code net.runelite.client.plugins.slayer.Task} enum (task name and {@code itemSpriteId}) in RuneLite 1.13.4, BSD
 * 2-Clause (the notice is in the file and in {@code LICENSE}). That enum and its {@code getTask} are package-private,
 * so a Plugin Hub plugin can't call them without reflection, which the hub forbids; {@code TaskIconsTest} checks the
 * copy against the enum (by reflection, test code only), so a RuneLite update that changes it fails the build.</p>
 *
 * <p>Names are matched by {@link TaskStatistics#normalize}. Pass the task's assigned name
 * ({@link LedgerRecord#getMonster}): a Catacombs variant or cannon group's name is no task name and has no icon here.
 * Pure (the ids are gameval {@code ItemID} values, plain ints).</p>
 */
final class TaskIcons
{
	/** Item id of each task's icon, by normalized task name. */
	private static final Map<String, Integer> ICONS = BundledData.load("task-icons.json", TaskIcons::read,
		Collections.emptyMap());

	private TaskIcons()
	{
	}

	/**
	 * The item id whose sprite is {@code taskName}'s icon, or {@code null} for a name that is no known task (blank,
	 * {@code null}, a Catacombs variant or cannon group name, or a task newer than the table).
	 */
	static Integer itemId(String taskName)
	{
		String key = TaskStatistics.normalize(taskName);
		return key == null ? null : ICONS.get(key);
	}

	private static Map<String, Integer> read(JsonObject root)
	{
		final Map<String, Integer> map = new HashMap<>();
		for (JsonElement row : root.getAsJsonArray("tasks"))
		{
			final JsonObject task = row.getAsJsonObject();
			map.put(TaskStatistics.normalize(task.get("task").getAsString()), task.get("id").getAsInt());
		}
		return Collections.unmodifiableMap(map);
	}
}
