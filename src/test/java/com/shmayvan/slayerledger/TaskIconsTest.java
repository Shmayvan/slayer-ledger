package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.lang.reflect.Method;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/** The task icons of Overall totals: {@link TaskIcons} and the task a By monster group shows the icon of. */
public class TaskIconsTest
{
	private static final ZoneId UTC = ZoneId.of("UTC");

	@Test
	public void aKnownTaskHasItsSlayerGuideIcon()
	{
		assertEquals(Integer.valueOf(ItemID.SLAYERGUIDE_DUSTDEVIL), TaskIcons.itemId("Dust devils"));
		// Matched as TaskStatistics.normalize matches names: case and outer spaces don't matter.
		assertEquals(Integer.valueOf(ItemID.SLAYERGUIDE_DUSTDEVIL), TaskIcons.itemId("  dust DEVILS "));
		assertEquals(Integer.valueOf(ItemID.SLAYERGUIDE_ABERRANTSPECTER), TaskIcons.itemId("Aberrant spectres"));
		// A task without a Slayer-guide picture has the core plugin's other item, a boss task its pet.
		assertEquals(Integer.valueOf(ItemID.DRAGONMASK_BLACK), TaskIcons.itemId("Black dragons"));
		assertEquals(Integer.valueOf(ItemID.SNAKEPET), TaskIcons.itemId("Zulrah"));
	}

	@Test
	public void anUnknownTaskHasNone()
	{
		assertNull(TaskIcons.itemId("Lava strykewyrms"));
		assertNull(TaskIcons.itemId(LedgerHistory.UNKNOWN_MONSTER));
		assertNull(TaskIcons.itemId("Unknown task (12)"));
		assertNull(TaskIcons.itemId(""));
		assertNull(TaskIcons.itemId("   "));
		assertNull(TaskIcons.itemId(null));
		// Group names are no task names: the group's task (OverallTotals.Entry#getTask) is looked up instead.
		assertNull(TaskIcons.itemId("Deviant spectres"));
		assertNull(TaskIcons.itemId("Dust devils" + CannonTasks.SUFFIX));
	}

	@Test
	public void aCatacombsVariantGroupShowsItsTasksIcon()
	{
		LedgerRecord newest = record("Aberrant spectres", 1);
		newest.setVariant("Deviant spectres");
		LedgerRecord older = record("Aberrant spectres", 2);
		older.setVariant("Deviant spectres");

		OverallTotals.Entry group = byMonster(Arrays.asList(newest, older)).get(0);

		assertEquals("Deviant spectres", group.getLabel());
		assertEquals("Aberrant spectres", group.getTask());
		assertEquals(Integer.valueOf(ItemID.SLAYERGUIDE_ABERRANTSPECTER), TaskIcons.itemId(group.getTask()));
	}

	@Test
	public void aCannonGroupShowsItsTasksIcon()
	{
		LedgerRecord cannon = record("Dust devils", 1);
		cannon.setSupplies(Collections.singletonMap(SupplyTracker.CANNONBALL, 200L));
		LedgerRecord granite = record("Fire giants", 2);
		granite.setSupplies(Collections.singletonMap(SupplyTracker.GRANITE_CANNONBALL, 50L));

		List<OverallTotals.Entry> groups = byMonster(Arrays.asList(cannon, granite));

		Map<String, String> tasks = new HashMap<>();
		for (OverallTotals.Entry group : groups)
		{
			tasks.put(group.getLabel(), group.getTask());
		}
		assertEquals("Dust devils", tasks.get("Dust devils (cannon)"));
		assertEquals("Fire giants", tasks.get("Fire giants (cannon)"));
		assertEquals(Integer.valueOf(ItemID.SLAYERGUIDE_DUSTDEVIL), TaskIcons.itemId(tasks.get("Dust devils (cannon)")));
	}

	@Test
	public void aGroupWithoutATaskNameHasNoTaskAndOtherGroupingsHaveNone()
	{
		LedgerRecord unnamed = record(null, 1);
		OverallTotals.Entry group = byMonster(Collections.singletonList(unnamed)).get(0);
		assertNull(group.getTask());
		assertNull(TaskIcons.itemId(group.getTask()));

		LedgerRecord named = record("Dust devils", 1);
		for (OverallTotals.Grouping grouping : Arrays.asList(OverallTotals.Grouping.ALL,
			OverallTotals.Grouping.MASTER, OverallTotals.Grouping.MONTH))
		{
			List<OverallTotals.Entry> entries = OverallTotals.summarize(Collections.singletonList(named), grouping,
				LootPriceMode.GE, Collections.emptyMap(), UTC);
			assertNull(grouping.toString(), entries.get(0).getTask());
		}
	}

	@Test
	public void everyBundledTaskHasAnIcon()
	{
		TaskLocations locations = TaskLocations.load(new Gson(), TaskLocations.SNAPSHOT_RESOURCE);
		assertNotNull(locations);
		for (String task : locations.tasks())
		{
			assertNotNull("No icon: " + task, TaskIcons.itemId(task));
		}
	}

	/**
	 * The table is RuneLite's core Slayer {@code Task} enum: every task with the same item, and no other. The enum is
	 * package-private, so it is read by reflection (test code only; the plugin can't).
	 */
	@Test
	public void theTableMatchesRuneLitesSlayerTasks() throws Exception
	{
		Class<?> taskClass = Class.forName("net.runelite.client.plugins.slayer.Task");
		Method getName = taskClass.getDeclaredMethod("getName");
		Method getItemSpriteId = taskClass.getDeclaredMethod("getItemSpriteId");
		getName.setAccessible(true);
		getItemSpriteId.setAccessible(true);
		Object[] tasks = taskClass.getEnumConstants();
		assertTrue(tasks.length > 100);
		for (Object task : tasks)
		{
			String name = (String) getName.invoke(task);
			assertEquals(name, getItemSpriteId.invoke(task), TaskIcons.itemId(name));
		}
		assertEquals(tasks.length, TaskIcons.size());
	}

	private static List<OverallTotals.Entry> byMonster(List<LedgerRecord> records)
	{
		return OverallTotals.summarize(records, OverallTotals.Grouping.MONSTER, LootPriceMode.GE,
			Collections.emptyMap(), UTC);
	}

	/** A completed Konar task of {@code monster}, newest first by {@code age}. */
	private static LedgerRecord record(String monster, long age)
	{
		LedgerRecord record = new LedgerRecord();
		record.setMonster(monster);
		record.setSlayerMaster(SlayerMaster.KONAR);
		record.setEndedAt(1_000_000L - age);
		record.setKills(10);
		record.setActiveMillis(60_000L);
		record.setCompleted(true);
		record.setPartial(false);
		return record;
	}
}
