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
import net.runelite.client.config.Range;
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
		return config(manager);
	}

	/** The config as {@link ConfigManager#getConfig(Class)} makes it, reading what {@code manager} stores. */
	private static SlayerLedgerConfig config(ConfigManager manager) throws Exception
	{
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
		// The chat lines of the personality pass (2.0 phase 11) are on by default.
		assertTrue(defaults.taskCompleteLine());
		assertTrue(defaults.recordTaskLine());
		assertTrue(defaults.notableDropLine());
		// The advisor chat line (which replaced the old preview) is on by default.
		assertTrue(defaults.advisorChatLine());
		assertTrue(defaults.showTaskOverlay());
		// Every overlay group is on (V2 plan 2.1).
		assertTrue(defaults.overlayProgress());
		assertTrue(defaults.overlayPace());
		assertTrue(defaults.overlayHaul());
		assertTrue(defaults.overlayRunway());
		// Every panel section is shown by default.
		assertTrue(defaults.showOverallTotals());
		assertTrue(defaults.showSkippedBlocked());
		// The task advisor is on, and the advisor's metric is Fastest finish (V2 plan 6).
		assertTrue(defaults.taskAdvisor());
		assertEquals(AdvisorMetric.FASTEST_FINISH, defaults.advisorMetric());
		// Away after 10 minutes without a task kill or a hit on a task NPC (V2 plan 4.1).
		assertEquals(10, defaults.awayMinutes());
	}

	@Test
	public void awayMinutesIsLimitedToOneToSixtyMinutes() throws Exception
	{
		Range range = SlayerLedgerConfig.class.getMethod("awayMinutes").getAnnotation(Range.class);
		assertEquals(1, range.min());
		assertEquals(60, range.max());

		SessionTracker tracker = new SessionTracker(() -> 0L);
		tracker.setAwayMinutes(0);
		assertEquals(60_000L, tracker.getAwayMillis());
		tracker.setAwayMinutes(500);
		assertEquals(60 * 60_000L, tracker.getAwayMillis());
		tracker.setAwayMinutes(10);
		assertEquals(10 * 60_000L, tracker.getAwayMillis());
	}

	@Test
	public void advisorMetricsHaveTheirLabelAndBadgeRate()
	{
		assertEquals("Fastest finish", AdvisorMetric.FASTEST_FINISH.toString());
		assertEquals("Profit per hour", AdvisorMetric.PROFIT_PER_HOUR.toString());
		assertEquals("Cheapest", AdvisorMetric.CHEAPEST.toString());
		// The badge ranks by Slayer XP/h under Fastest finish, by profit/h otherwise (V2 plan 6).
		assertEquals(TaskRanking.Metric.XP, TaskAdvisor.badgeMetric(AdvisorMetric.FASTEST_FINISH));
		assertEquals(TaskRanking.Metric.PROFIT, TaskAdvisor.badgeMetric(AdvisorMetric.PROFIT_PER_HOUR));
		assertEquals(TaskRanking.Metric.PROFIT, TaskAdvisor.badgeMetric(AdvisorMetric.CHEAPEST));
		assertEquals(3, AdvisorMetric.values().length);
	}

	@Test
	public void theAdvisorChatLineKeepsThePreviewsKeySoTheChoiceCarriesOver() throws Exception
	{
		// As 1.x saved "New-task chat preview" turned off.
		ConfigManager manager = mock(ConfigManager.class, CALLS_REAL_METHODS);
		doReturn("false").when(manager).getConfiguration(SlayerLedgerConfig.GROUP, "assignmentPreview");
		doReturn("PROFIT_PER_HOUR").when(manager).getConfiguration(SlayerLedgerConfig.GROUP,
			SlayerLedgerConfig.ADVISOR_METRIC);
		SlayerLedgerConfig config = config(manager);

		assertFalse("still off", config.advisorChatLine());
		assertEquals(AdvisorMetric.PROFIT_PER_HOUR, config.advisorMetric());
	}

	@Test
	public void theOverlayGroupsKeepTheRowSettingsKeysThatMapToThem() throws Exception
	{
		// 1.x's "Show task row" (the name and kills left) is Progress and identity, and its "Show profit row" Haul.
		assertEquals("overlayTask", SlayerLedgerConfig.OVERLAY_PROGRESS);
		assertEquals("overlayProfit", SlayerLedgerConfig.OVERLAY_HAUL);
		assertEquals("overlayPace", SlayerLedgerConfig.OVERLAY_PACE);
		assertEquals("overlayRunway", SlayerLedgerConfig.OVERLAY_RUNWAY);

		// As 1.x saved both rows turned off: still off.
		ConfigManager manager = mock(ConfigManager.class, CALLS_REAL_METHODS);
		doReturn("false").when(manager).getConfiguration(SlayerLedgerConfig.GROUP, "overlayTask");
		doReturn("false").when(manager).getConfiguration(SlayerLedgerConfig.GROUP, "overlayProfit");
		// 1.x's kills and time-left rows have no group of their own: their keys are no longer read.
		doReturn("false").when(manager).getConfiguration(SlayerLedgerConfig.GROUP, "overlayKills");
		doReturn("false").when(manager).getConfiguration(SlayerLedgerConfig.GROUP, "overlayTimeLeft");
		// Never saved: the defaults.
		doReturn(null).when(manager).getConfiguration(SlayerLedgerConfig.GROUP, "overlayPace");
		doReturn(null).when(manager).getConfiguration(SlayerLedgerConfig.GROUP, "overlayRunway");
		doReturn(null).when(manager).getConfiguration(SlayerLedgerConfig.GROUP, "showTaskOverlay");
		SlayerLedgerConfig config = config(manager);

		assertFalse(config.overlayProgress());
		assertFalse(config.overlayHaul());
		assertTrue("new: on", config.overlayPace());
		assertTrue("new: on", config.overlayRunway());
		assertTrue(config.showTaskOverlay());

		List<String> keys = new ArrayList<>();
		for (ConfigItemDescriptor item : mock(ConfigManager.class, CALLS_REAL_METHODS)
			.getConfigDescriptor(configWithLootPrice(null)).getItems())
		{
			keys.add(item.getItem().keyName());
		}
		assertFalse(keys.contains("overlayKills"));
		assertFalse(keys.contains("overlayTimeLeft"));
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
		// "Ask again about skipped places" went with the naming prompt.
		assertEquals(Arrays.asList("confirmIncomplete", "bonecrusherHandling", SlayerLedgerConfig.IGNORED_LOOT,
			SlayerLedgerConfig.AWAY_MINUTES),
			bySection.get(SlayerLedgerConfig.trackingSection));
		assertEquals(Arrays.asList(SlayerLedgerConfig.LOOT_PRICE_MODE, SlayerLedgerConfig.NOTABLE_DROP_THRESHOLD,
			SlayerLedgerConfig.HIDE_GROUND_ITEMS_HIDDEN), bySection.get(SlayerLedgerConfig.valuesSection));
		assertEquals(Arrays.asList(SlayerLedgerConfig.TASK_COMPLETE_LINE, "taskCompleteNotification",
			SlayerLedgerConfig.RECORD_TASK_LINE, SlayerLedgerConfig.NOTABLE_DROP_LINE, SlayerLedgerConfig.ADVISOR_CHAT_LINE),
			bySection.get(SlayerLedgerConfig.chatSection));
		// The master toggle first, then the four groups in the overlay's order.
		assertEquals(Arrays.asList("showTaskOverlay", SlayerLedgerConfig.OVERLAY_PROGRESS,
			SlayerLedgerConfig.OVERLAY_PACE, SlayerLedgerConfig.OVERLAY_HAUL, SlayerLedgerConfig.OVERLAY_RUNWAY),
			bySection.get(SlayerLedgerConfig.taskOverlaySection));
		// The badge's on/off toggle first, then the advisor's metric ("Task advisor goal" was removed).
		assertEquals(Arrays.asList(SlayerLedgerConfig.TASK_ADVISOR, SlayerLedgerConfig.ADVISOR_METRIC),
			bySection.get(SlayerLedgerConfig.taskAdvisorSection));
		// In sidebar order.
		assertEquals(Arrays.asList(SlayerLedgerConfig.SHOW_OVERALL_TOTALS, SlayerLedgerConfig.SHOW_SKIPPED_BLOCKED),
			bySection.get(SlayerLedgerConfig.panelSectionsSection));
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
			// Trends and the Luck meter were removed in 2.0, with their settings, and What to bring went into
			// the advisor card, with its setting. A stored value of a removed key is left orphaned,
			// which is harmless: nothing reads it.
			assertFalse(item.getItem().keyName(), Arrays.asList("showTrends", "showLuckMeter", "showWhatToBring")
				.contains(item.getItem().keyName()));
		}
		assertEquals(Arrays.asList("Overall totals", "Skipped / blocked"), names);
	}

	@Test
	public void theRemovedHigherOptionReadsAsGe() throws Exception
	{
		// Saved by clients from before "Higher of the two" was removed (owner decision, 2026-10-25).
		assertEquals(LootPriceMode.GE, configWithLootPrice("HIGHER").lootPriceMode());
	}
}
