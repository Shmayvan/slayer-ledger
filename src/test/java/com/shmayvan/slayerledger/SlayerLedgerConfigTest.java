package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.config.ConfigDescriptor;
import net.runelite.client.config.ConfigItemDescriptor;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ConfigSectionDescriptor;
import org.junit.Test;

/**
 * The "Loot price" setting read through RuneLite's own config proxy handler (the one
 * {@link ConfigManager#getConfig(Class)} uses), so a value saved for the removed "Higher of the two" option is checked
 * against the client's real parsing rather than a stub.
 */
public class SlayerLedgerConfigTest
{
	/** A config proxy whose stored "Loot price" value is {@code saved} ({@code null} for unset). */
	private static SlayerLedgerConfig configWithLootPrice(String saved) throws Exception
	{
		// Only the stored string is stubbed; stringToObject (the enum parsing) is the client's own.
		ConfigManager manager = mock(ConfigManager.class, CALLS_REAL_METHODS);
		doReturn(saved).when(manager).getConfiguration(SlayerLedgerConfig.GROUP, SlayerLedgerConfig.LOOT_PRICE_MODE);

		// The handler class is package-private in the client.
		Constructor<?> ctor = Class.forName("net.runelite.client.config.ConfigInvocationHandler")
			.getDeclaredConstructor(ConfigManager.class);
		ctor.setAccessible(true);
		InvocationHandler handler = (InvocationHandler) ctor.newInstance(manager);
		return (SlayerLedgerConfig) Proxy.newProxyInstance(SlayerLedgerConfig.class.getClassLoader(),
			new Class<?>[]{SlayerLedgerConfig.class}, handler);
	}

	@Test
	public void savedModesAreRead() throws Exception
	{
		assertEquals(LootPriceMode.GE, configWithLootPrice("GE").lootPriceMode());
		assertEquals(LootPriceMode.HIGH_ALCH, configWithLootPrice("HIGH_ALCH").lootPriceMode());
		assertEquals("unset", LootPriceMode.GE, configWithLootPrice(null).lootPriceMode());
	}

	@Test
	public void defaults()
	{
		SlayerLedgerConfig defaults = new SlayerLedgerConfig()
		{
		};
		assertFalse(defaults.taskCompleteNotification());
		// The new-task chat preview is on by default.
		assertTrue(defaults.assignmentPreview());
		assertTrue(defaults.showTaskOverlay());
		assertTrue(defaults.overlayTask());
		assertTrue(defaults.overlayKills());
		assertTrue(defaults.overlayTimeLeft());
		assertTrue(defaults.overlayProfit());
		// Every panel section is shown by default.
		assertTrue(defaults.showWhatToBring());
		assertTrue(defaults.showTrends());
		assertTrue(defaults.showOverallTotals());
		assertTrue(defaults.showLuckMeter());
		assertTrue(defaults.showSkippedBlocked());
		// The task advisor is on, with Profit/h as its goal.
		assertTrue(defaults.taskAdvisor());
		assertEquals(TaskAdvisorGoal.PROFIT, defaults.taskAdvisorGoal());
		// Nothing is waiting to forget the skipped places.
		assertFalse(defaults.resetLocationSkips());
	}

	@Test
	public void taskAdvisorGoalsHaveTheirLabelAndMetric()
	{
		assertEquals("Profit/h", TaskAdvisorGoal.PROFIT.toString());
		assertEquals("Slayer XP/h", TaskAdvisorGoal.SLAYER_XP.toString());
		assertEquals("Points/h", TaskAdvisorGoal.POINTS.toString());
		assertEquals(TaskRanking.Metric.PROFIT, TaskAdvisorGoal.PROFIT.metric());
		assertEquals(TaskRanking.Metric.XP, TaskAdvisorGoal.SLAYER_XP.metric());
		assertEquals(TaskRanking.Metric.POINTS, TaskAdvisorGoal.POINTS.metric());
		// Profit per kill is a Trends metric only: the advisor keeps its three hourly goals.
		assertEquals(3, TaskAdvisorGoal.values().length);
		for (TaskAdvisorGoal goal : TaskAdvisorGoal.values())
		{
			assertFalse(goal.name(), goal.metric().isPerKill());
		}
	}

	@Test
	public void everySettingIsPlacedInASectionInADeliberateOrder() throws Exception
	{
		// The client's own descriptor (sorted by position, then name), as the config panel lays it out.
		ConfigDescriptor descriptor = mock(ConfigManager.class, CALLS_REAL_METHODS)
			.getConfigDescriptor(configWithLootPrice(null));

		// Core settings first, the niche charged-weapon setting last.
		List<String> sections = new ArrayList<>();
		List<String> sectionNames = new ArrayList<>();
		for (ConfigSectionDescriptor section : descriptor.getSections())
		{
			sections.add(section.getKey());
			sectionNames.add(section.getSection().name());
		}
		assertEquals(Arrays.asList(SlayerLedgerConfig.trackingSection, SlayerLedgerConfig.valuesSection,
			SlayerLedgerConfig.chatSection, SlayerLedgerConfig.taskOverlaySection, SlayerLedgerConfig.taskAdvisorSection,
			SlayerLedgerConfig.panelSectionsSection, SlayerLedgerConfig.chargedWeaponsSection), sections);
		assertEquals(Arrays.asList("Tracking", "Values", "Chat", "Task overlay", "Task advisor", "Panel sections",
			"Charged weapons"), sectionNames);

		// Every item has a position (no accidental by-name order) and a section; keys in config panel order.
		Map<String, List<String>> bySection = new LinkedHashMap<>();
		for (ConfigItemDescriptor item : descriptor.getItems())
		{
			String key = item.getItem().keyName();
			// -1 is ConfigItem's default: no position given.
			assertNotEquals("no position: " + key, -1, item.getItem().position());
			assertNotEquals("no section: " + key, "", item.getItem().section());
			bySection.computeIfAbsent(item.getItem().section(), k -> new ArrayList<>()).add(key);
		}
		assertEquals(Arrays.asList("confirmIncomplete", "bonecrusherHandling", SlayerLedgerConfig.IGNORED_LOOT,
			SlayerLedgerConfig.RESET_LOCATION_SKIPS), bySection.get(SlayerLedgerConfig.trackingSection));
		assertEquals(Arrays.asList(SlayerLedgerConfig.LOOT_PRICE_MODE, SlayerLedgerConfig.NOTABLE_DROP_THRESHOLD,
			SlayerLedgerConfig.HIDE_GROUND_ITEMS_HIDDEN), bySection.get(SlayerLedgerConfig.valuesSection));
		assertEquals(Arrays.asList("taskCompleteNotification", "assignmentPreview"),
			bySection.get(SlayerLedgerConfig.chatSection));
		// The master toggle first, then the rows.
		assertEquals(Arrays.asList("showTaskOverlay", "overlayTask", "overlayKills", "overlayTimeLeft", "overlayProfit"),
			bySection.get(SlayerLedgerConfig.taskOverlaySection));
		// The on/off toggle first, then the goal.
		assertEquals(Arrays.asList(SlayerLedgerConfig.TASK_ADVISOR, SlayerLedgerConfig.TASK_ADVISOR_GOAL),
			bySection.get(SlayerLedgerConfig.taskAdvisorSection));
		// In sidebar order.
		assertEquals(Arrays.asList(SlayerLedgerConfig.SHOW_WHAT_TO_BRING, SlayerLedgerConfig.SHOW_TRENDS,
			SlayerLedgerConfig.SHOW_OVERALL_TOTALS, SlayerLedgerConfig.SHOW_LUCK_METER,
			SlayerLedgerConfig.SHOW_SKIPPED_BLOCKED), bySection.get(SlayerLedgerConfig.panelSectionsSection));
		assertEquals(Collections.singletonList(SlayerLedgerConfig.VORKATHS_HEAD_UPGRADE),
			bySection.get(SlayerLedgerConfig.chargedWeaponsSection));
		assertEquals("no other section", 7, bySection.size());
	}

	@Test
	public void panelSectionSettingsAreNamedAsTheSidebarHeadings() throws Exception
	{
		ConfigDescriptor descriptor = mock(ConfigManager.class, CALLS_REAL_METHODS)
			.getConfigDescriptor(configWithLootPrice(null));
		List<String> names = new ArrayList<>();
		for (ConfigItemDescriptor item : descriptor.getItems())
		{
			if (SlayerLedgerConfig.panelSectionsSection.equals(item.getItem().section()))
			{
				names.add(item.getItem().name());
			}
		}
		assertEquals(Arrays.asList("What to bring", "Trends", "Overall totals", "Luck meter", "Skipped / blocked"),
			names);
	}

	@Test
	public void theRemovedHigherOptionReadsAsGe() throws Exception
	{
		// Saved by clients from before "Higher of the two" was removed (owner decision, 2026-10-25).
		assertEquals(LootPriceMode.GE, configWithLootPrice("HIGHER").lootPriceMode());
	}
}
