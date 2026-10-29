package com.shmayvan.slayerledger;

import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.DBTableID;

/**
 * The plugin's Slayer DB-table lookups: the task name and Konar's area name, read the way core SlayerPlugin
 * reads them. Client thread only. The last successful lookup of each is cached for the plugin instance's lifetime
 * (never cleared, as before the move); failed lookups are not cached, so they are retried on the next tick.
 *
 * <p>Injected by Guice into the plugin, as RuneLite plugins inject their overlays and helpers.
 */
@Slf4j
class SlayerDbLookups
{
	private final Client client;
	private int cachedTaskKey = Integer.MIN_VALUE;
	private String cachedTaskName;
	private int cachedAreaId = Integer.MIN_VALUE;
	private String cachedAreaName;

	@Inject
	SlayerDbLookups(Client client)
	{
		this.client = client;
	}

	/**
	 * Client thread. Name of a Slayer area from the SlayerArea DB table (COL_AREA_NAME_IN_HELPER, as core
	 * SlayerPlugin's task location), or {@code null} when the lookup fails; failures are retried on the next tick.
	 */
	String lookupAreaName(int areaId)
	{
		if (areaId == cachedAreaId && cachedAreaName != null)
		{
			return cachedAreaName;
		}
		String name = null;
		try
		{
			final List<Integer> rows = client.getDBRowsByValue(DBTableID.SlayerArea.ID,
				DBTableID.SlayerArea.COL_AREA_ID, 0, areaId);
			if (!rows.isEmpty())
			{
				name = (String) client.getDBTableField(rows.get(0), DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER, 0)[0];
			}
		}
		catch (RuntimeException e)
		{
			log.debug("Unable to look up slayer area {}", areaId, e);
		}
		if (name == null || name.isEmpty())
		{
			return null;
		}
		cachedAreaId = areaId;
		cachedAreaName = name;
		return name;
	}

	/**
	 * Client thread. Resolves the task name the same way core SlayerPlugin does (1.13.3): the SlayerTask DB table
	 * keyed by the SLAYER_TARGET varp, or the SlayerTaskSublist table for boss tasks. Successful lookups are cached;
	 * failed lookups return {@code null} and are retried on the next tick.
	 */
	String lookupTaskName(int taskId, int bossId, int key)
	{
		if (key == cachedTaskKey && cachedTaskName != null)
		{
			return cachedTaskName;
		}

		String name = null;
		try
		{
			Integer taskRow = null;
			if (taskId == SessionTracker.BOSS_TASK_TARGET)
			{
				List<Integer> bossRows = client.getDBRowsByValue(DBTableID.SlayerTaskSublist.ID,
					DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, 0, bossId);
				if (!bossRows.isEmpty())
				{
					taskRow = (Integer) client.getDBTableField(bossRows.get(0), DBTableID.SlayerTaskSublist.COL_TASK, 0)[0];
				}
			}
			else
			{
				List<Integer> taskRows = client.getDBRowsByValue(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, taskId);
				if (!taskRows.isEmpty())
				{
					taskRow = taskRows.get(0);
				}
			}
			if (taskRow != null)
			{
				name = (String) client.getDBTableField(taskRow, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)[0];
			}
		}
		catch (RuntimeException e)
		{
			log.debug("Unable to look up slayer task {}", taskId, e);
		}

		if (name == null || name.isEmpty())
		{
			// Not cached, so the lookup is retried next tick. The panel and record show a fallback name.
			return null;
		}
		name = name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
		cachedTaskKey = key;
		cachedTaskName = name;
		return name;
	}
}
