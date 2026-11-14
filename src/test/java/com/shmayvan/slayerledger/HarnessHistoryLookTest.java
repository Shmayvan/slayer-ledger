package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.client.ui.PluginPanel;
import org.junit.Rule;
import org.junit.Test;

/**
 * The Task history in look A (DESIGN_PRINCIPLES.md 3.7 and 3.9) as wired in the plugin
 * ({@link PluginHarness}): two-line flat rows (name, duration, date, short location, setup tag icons, signed profit,
 * {@code Partial} only for a partial task), the tag icons' tooltips (served by the row, which keeps the mouse over
 * them), the hover background, the setup filters behind More filters, the header's filter count and Clear filters, both
 * empty states and the model's message without records, the sidebar's width and the refresh timer. {@link HarnessHistoryFiltersTest} covers Monster, Location and Master, {@link HarnessHistoryPagingTest} the
 * paging.
 */
public class HarnessHistoryLookTest
{
	@Rule
	public final PluginHarness harness = new PluginHarness();

	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int DURADEL = 5;
	private static final long END = 1_700_000_000_000L;
	private static final String CANNON = ItemID.TWPART3 + " 16x16: Cannon";

	/** A tag icon showing the game sprite {@code archive} in its 16 px box, with {@code tooltip} over it. */
	private static String sprite(int archive, String tooltip)
	{
		return "sprite " + archive + " 16x16: " + tooltip;
	}

	private <T> T onEdt(Supplier<T> read)
	{
		List<T> result = new ArrayList<>(1);
		harness.onEdt(() -> result.add(read.get()));
		return result.get(0);
	}

	private PanelProbe.HistoryListProbe history()
	{
		return harness.ui().getHistory();
	}

	private static LedgerRecord record(String id, String monster, Integer master, long endedAt, long activeMinutes)
	{
		LedgerRecord record = new LedgerRecord();
		record.setId(id);
		record.setMonster(monster);
		record.setCreatureId(42);
		record.setSlayerMaster(master);
		record.setKills(142);
		record.setInitialAmount(142);
		record.setOriginalAmount(142);
		record.setActiveMillis(activeMinutes * 60_000L);
		record.setEndedAt(endedAt);
		record.setStartedAt(endedAt - record.getActiveMillis());
		record.setCompleted(true);
		return record;
	}

	/**
	 * Three tasks, newest first: a completed Dust devils cannon task in the Catacombs with Barrage and Prayer gear, 3
	 * Ranarr seeds of loot and 10 sharks used (+141K); a partial Abyssal demons task in the Stronghold Slayer Cave saved
	 * before setup was recorded (an estimated Barrage), 10 sharks used and no loot (-9,000); and a Konar Kurasks task
	 * with no place and no values, detected melee set to Ranged by the player, with Strength gear.
	 */
	private static List<LedgerRecord> tasks()
	{
		LedgerRecord devils = record("devils", "Dust devils", DURADEL, END, 61);
		devils.setLocationRegion(TestSpots.CATACOMBS);
		devils.setSpotId(TestSpots.CATACOMBS_ID);
		devils.setLoot(Collections.singletonMap(ItemID.RANARR_SEED, 3L));
		devils.setSupplies(Collections.singletonMap(ItemID.SHARK, 10L));
		devils.setLootGeValue(1L);
		devils.setSuppliesGeValue(1L);
		devils.setXpGained(Collections.singletonMap("SLAYER", 12_000L));
		SetupTags.detected(true, SetupStyle.BARRAGE, GearFocus.PRAYER, SetupOverrides.NONE).copyTo(devils);

		LedgerRecord demons = record("demons", "Abyssal demons", DURADEL, END - 86_400_000L, 52);
		demons.setCompleted(false);
		demons.setPartial(true);
		demons.setLocationRegion(TestSpots.STRONGHOLD);
		demons.setSpotId(TestSpots.STRONGHOLD_ID);
		demons.setSupplies(Collections.singletonMap(ItemID.SHARK, 10L));
		demons.setLootGeValue(0L);
		demons.setSuppliesGeValue(1L);
		SetupTags.estimated(false, SetupStyle.BARRAGE).copyTo(demons);

		LedgerRecord kurasks = record("kurasks", "Kurasks", SlayerMaster.KONAR, END - 2 * 86_400_000L, 30);
		SetupTags.detected(false, SetupStyle.MELEE, GearFocus.STRENGTH, SetupOverrides.NONE.withStyle(SetupStyle.RANGED))
			.copyTo(kurasks);
		return Arrays.asList(devils, demons, kurasks);
	}

	private void show(List<LedgerRecord> records)
	{
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, records));
		harness.drain();
	}

	/** Logs in with the Ranarr seed and the shark priced, and shows {@link #tasks()}. */
	private List<LedgerRecord> showTasks()
	{
		harness.defineItem(ItemID.RANARR_SEED, "Ranarr seed", 50_000, 20);
		harness.defineItem(ItemID.SHARK, "Shark", 900, 10);
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		List<LedgerRecord> records = tasks();
		show(records);
		return records;
	}

	private static String date(LedgerRecord record)
	{
		return LedgerHistory.formatEndedAt(record, LedgerHistory.shortDate(ZoneId.systemDefault()));
	}

	@Test
	public void eachRowIsTwoLinesWithItsNameDateLocationDurationAndSignedProfitAndPartialOnlyWhenPartial()
	{
		List<LedgerRecord> records = showTasks();

		assertEquals(Arrays.asList(
			"Dust devils (cannon) | 1 h 1 min / " + date(records.get(0)) + " \u00b7 Catacombs | +141K",
			"Abyssal demons | 52 min / Partial | " + date(records.get(1)) + " \u00b7 Stronghold Cave | -9,000",
			"Kurasks | 30 min / " + date(records.get(2)) + " | -"), onEdt(() -> history().getRowTexts()));
		// The status is a word, and only for the exception: no "Completed" on every row.
		assertEquals(Arrays.asList("Dust devils (cannon)", "Abyssal demons | Partial", "Kurasks"),
			onEdt(() -> history().getTitleTexts()));
		// The sign carries the profit; the colour only adds to it.
		assertEquals(Arrays.asList(LedgerTheme.GOOD, LedgerTheme.ALERT, LedgerTheme.VALUE),
			onEdt(() -> history().getProfitColors()));

		// The tooltip has what the row has no room for: the full name, date and time, the exact active time (the row
		// shows whole minutes), kills and XP, then the details.
		List<String> tooltips = onEdt(() -> history().getTooltips());
		String ended = LedgerHistory.formatEndedAt(records.get(0), LedgerHistory.shortDateTime(ZoneId.systemDefault()));
		assertTrue(tooltips.get(0), tooltips.get(0).startsWith("<html>Dust devils (cannon)<br>" + ended
			+ "<br>Active: 1:01:00<br>Kills: 142 / 142<br>XP: 12K (11.8K/h)<br>Loot: 150,000 gp GE"));
		assertTrue(tooltips.get(0), tooltips.get(0).contains("<br>Location: Catacombs of Kourend<br>"));
		assertFalse(tooltips.get(0), tooltips.get(0).contains(LedgerHistory.PARTIAL_NOTE));
		assertTrue(tooltips.get(1), tooltips.get(1).startsWith("<html>Abyssal demons<br>"
			+ LedgerHistory.PARTIAL_NOTE + "<br>"));
		assertTrue(tooltips.get(2), tooltips.get(2).contains("<br>XP: -<br>" + LedgerHistory.NOT_VALUED));

		// The details open in place in look A: flat lists, each under a sub-header with its value on the right.
		harness.onEdt(() -> history().clickRow(0));
		harness.drain();
		assertEquals(Arrays.asList(SlayerLedgerPanel.ITEMS_AT_CURRENT_PRICES, "Supplies used | 9,000",
			"Shark | " + ItemID.SHARK + " x10", "Loot received | 150K", "Ranarr seed | " + ItemID.RANARR_SEED + " x3",
			"XP gained | 11.8K/h", "Slayer | 12K"), onEdt(() -> history().getDetailsText(0)));
		// Nested in the row, their sub-headers are quieter than the row's name, as Overall totals' are
		// (DESIGN_PRINCIPLES.md 3.8): Small in the value colour with no rule, the value on the right in Small in the
		// label colour. The live card's keep the Bold heading over a rule (HarnessLiveCardTest).
		assertEquals("Plain value", onEdt(() -> history().getNameLook(0)));
		for (String title : Arrays.asList("Supplies used", "Loot received", SkillXp.LIST_TITLE))
		{
			assertEquals(title, "Small value | Small label | no rule",
				onEdt(() -> history().getDetailsHeaderLook(0, title)));
		}
		// The edit actions stay on the right-click menu.
		assertEquals(Arrays.asList("Edit note", "Edit location", HistoryListSection.SETUP_MENU_ITEM, "Delete task"),
			onEdt(() -> history().getMenuItems(0)));
	}

	@Test
	public void eachEffectiveTagHasItsIconAndATooltipThatSaysEstimatedOrSetByYou()
	{
		showTasks();
		harness.onEdt(() -> history().layOut());

		// The overlay's game images, and the tooltips as Swing shows them with the mouse over each icon.
		assertEquals(Arrays.asList(
			Arrays.asList(CANNON, sprite(SpriteID.Magicon2.ICE_BARRAGE, "Barrage"),
				sprite(SpriteID.Staticons.PRAYER, "Prayer gear")),
			Collections.singletonList(sprite(SpriteID.Magicon2.ICE_BARRAGE, "Barrage (estimated)")),
			Arrays.asList(sprite(SpriteID.Staticons.RANGED, "Ranged (set by you)"),
				sprite(SpriteID.Staticons.STRENGTH, "Strength gear"))),
			onEdt(() -> history().getTagIcons()));
		// Off the icons, the row's own tooltip.
		String rowTooltip = onEdt(() -> history().getTooltips()).get(0);
		assertEquals(rowTooltip, onEdt(() -> history().getTooltipOver(0, "history.name")));
		assertEquals(rowTooltip, onEdt(() -> history().getTooltipOver(0, "history.profit")));

		// The row serves the icons' tooltips, so they don't take the mouse from it: a right-click on one opens the row's
		// edit menu and a click opens the task.
		for (int row = 0; row < 3; row++)
		{
			int index = row;
			assertTrue("row " + row, onEdt(() -> history().rowGetsTheMouseOverItsTags(index)));
		}
		assertEquals(Arrays.asList("Edit note", "Edit location", HistoryListSection.SETUP_MENU_ITEM, "Delete task"),
			onEdt(() -> history().getMenuItemsOverTag(0)));
		harness.onEdt(() -> history().clickRowTag(0));
		harness.drain();
		assertNotNull("a click on a tag opens the task", onEdt(() -> history().getDetailsText(0)));
	}

	/**
	 * A Barrage row shows Ice Barrage's spell icon, as the overlay and the live card's toggles do, not the Magic skill
	 * icon: the rows' tag images come through the sidebar's {@link SetupImages}, which asks for each once, also
	 * across rebuilds.
	 */
	@Test
	public void aBarrageRowShowsIceBarragesSpriteAndEachImageIsAskedForOnce()
	{
		showTasks();
		harness.onEdt(() -> history().layOut());
		List<List<String>> tags = onEdt(() -> history().getTagIcons());
		assertEquals(sprite(SpriteID.Magicon2.ICE_BARRAGE, "Barrage (estimated)"), tags.get(1).get(0));
		for (List<String> row : tags)
		{
			for (String tag : row)
			{
				assertFalse("no skill icon: " + tag, tag.startsWith("skill "));
			}
		}
		// Ice Barrage (on two rows), Prayer, Ranged and Strength: once each.
		assertEquals(4, harness.spriteImageRequests());

		// Rebuilt for another Loot price: the images are kept, never asked for again.
		int builds = onEdt(() -> history().getBuildCount());
		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		harness.drain();
		assertTrue(onEdt(() -> history().getBuildCount()) > builds);
		assertEquals(4, harness.spriteImageRequests());
		harness.onEdt(() -> history().layOut());
		assertEquals(tags, onEdt(() -> history().getTagIcons()));
	}

	@Test
	public void aRowIsHighlightedFromEnteringItUntilAnyExitAlsoOneInsideIt()
	{
		showTasks();
		harness.onEdt(() -> history().layOut());

		harness.onEdt(() -> history().mouseEnterRow(0));
		assertTrue(onEdt(() -> history().isRowHovered(0)));
		// Moving onto a tag icon is no exit: the row keeps the mouse there.
		assertTrue(onEdt(() -> history().rowGetsTheMouseOverItsTags(0)));
		assertTrue(onEdt(() -> history().isRowHovered(0)));

		// The right-click menu (or a dialog after it) takes the mouse while it is over the row: the exit is at a point
		// inside the row, and no highlight is left behind once the menu closes or the dialog is cancelled.
		harness.onEdt(() -> history().mouseExitRow(0, true));
		assertFalse("no highlight left behind", onEdt(() -> history().isRowHovered(0)));

		harness.onEdt(() -> history().mouseEnterRow(0));
		harness.onEdt(() -> history().mouseExitRow(0, false));
		assertFalse(onEdt(() -> history().isRowHovered(0)));
	}

	@Test
	public void moreFiltersOpensOpensItselfForASetupFilterAndTheSetupFiltersFilter()
	{
		showTasks();
		String[] setup = {"history.cannonFilter", "history.styleFilter", "history.gearFilter"};
		assertTrue("Task type always shows", onEdt(() -> history().isFilterShown("history.taskTypeFilter")));
		assertEquals(Arrays.asList("Any", "Regular", "Konar", "Wilderness", "Boss"),
			onEdt(() -> history().getSetupFilterOptions("history.taskTypeFilter")));
		assertEquals(Arrays.asList("Any", "Yes", "No"),
			onEdt(() -> history().getSetupFilterOptions("history.cannonFilter")));
		assertEquals(Arrays.asList("Any", "Melee", "Ranged", "Barrage", "Magic", "Mixed"),
			onEdt(() -> history().getSetupFilterOptions("history.styleFilter")));
		assertEquals(Arrays.asList("Any", "Prayer", "Strength", "Mixed"),
			onEdt(() -> history().getSetupFilterOptions("history.gearFilter")));
		for (String name : setup)
		{
			assertFalse(name, onEdt(() -> history().isFilterShown(name)));
		}
		assertEquals(HistoryListSection.MORE_FILTERS, onEdt(() -> history().getMoreFiltersText()));

		// It opens and closes.
		harness.onEdt(() -> history().clickMoreFilters());
		for (String name : setup)
		{
			assertTrue(name, onEdt(() -> history().isFilterShown(name)));
		}
		assertEquals(HistoryListSection.HIDE_MORE_FILTERS, onEdt(() -> history().getMoreFiltersText()));
		harness.onEdt(() -> history().clickMoreFilters());
		assertFalse(onEdt(() -> history().isFilterShown("history.styleFilter")));

		// A setup filter set while they are hidden opens them, and filters: an estimated Barrage is Barrage.
		harness.onEdt(() -> history().selectSetupFilter("history.styleFilter", "Barrage"));
		for (String name : setup)
		{
			assertTrue(name, onEdt(() -> history().isFilterShown(name)));
		}
		assertEquals(Arrays.asList("Dust devils (cannon)", "Abyssal demons | Partial"),
			onEdt(() -> history().getTitleTexts()));
		harness.onEdt(() -> history().selectSetupFilter("history.cannonFilter", "No"));
		assertEquals(Arrays.asList("Abyssal demons | Partial"), onEdt(() -> history().getTitleTexts()));
		harness.onEdt(() ->
		{
			history().selectSetupFilter("history.cannonFilter", "Any");
			history().selectSetupFilter("history.styleFilter", "Any");
			history().selectSetupFilter("history.gearFilter", "Strength");
		});
		assertEquals("the effective tags: Ranged set by the player", Arrays.asList("Kurasks"),
			onEdt(() -> history().getTitleTexts()));
		assertEquals("Any | Any | Any | Strength", onEdt(() -> history().getSetupFilterSelections()));

		// Hidden while one is set, the toggle says so; another account starts with every filter on Any.
		harness.onEdt(() -> history().clickMoreFilters());
		assertEquals(HistoryListSection.MORE_FILTERS + " (1 set)", onEdt(() -> history().getMoreFiltersText()));
		harness.onEdt(() -> history().clickMoreFilters());
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.OTHER_ACCOUNT, tasks()));
		harness.drain();
		assertEquals("Any | Any | Any | Any", onEdt(() -> history().getSetupFilterSelections()));
		assertEquals(3, (int) onEdt(() -> history().getRowCount()));
		assertEquals("remembered open while the section exists", HistoryListSection.HIDE_MORE_FILTERS,
			onEdt(() -> history().getMoreFiltersText()));

		// Task type always shows and filters too.
		harness.onEdt(() -> history().selectSetupFilter("history.taskTypeFilter", "Konar"));
		assertEquals(Arrays.asList("Kurasks"), onEdt(() -> history().getTitleTexts()));
	}

	@Test
	public void theHeaderCountsTheFiltersSetAndClearFiltersSetsThemAllBack()
	{
		showTasks();
		assertEquals(HistoryListSection.TITLE, onEdt(() -> history().getHeaderTitle()));
		assertFalse(onEdt(() -> history().isClearFiltersShown()));

		harness.onEdt(() -> history().selectFilter("Abyssal demons"));
		assertEquals("Task history \u00b7 1 filter", onEdt(() -> history().getHeaderTitle()));
		assertTrue(onEdt(() -> history().isClearFiltersShown()));
		harness.onEdt(() ->
		{
			history().selectSetupFilter("history.taskTypeFilter", "Regular");
			history().selectSetupFilter("history.styleFilter", "Barrage");
		});
		assertEquals("Task history \u00b7 3 filters", onEdt(() -> history().getHeaderTitle()));
		assertEquals(1, (int) onEdt(() -> history().getRowCount()));
		// At 225 px the count and both links fit: Clear filters sits beside More filters, not beside the title.
		harness.onEdt(() -> history().layOut());
		assertTrue(onEdt(() -> history().headerAndFilterLinksFit()));

		harness.onEdt(() -> history().clickClearFilters());
		assertEquals(HistoryListSection.TITLE, onEdt(() -> history().getHeaderTitle()));
		assertFalse(onEdt(() -> history().isClearFiltersShown()));
		assertEquals("All | All | All", onEdt(() -> history().getFilterSelections()));
		assertEquals("Any | Any | Any | Any", onEdt(() -> history().getSetupFilterSelections()));
		assertEquals(3, (int) onEdt(() -> history().getRowCount()));
		assertEquals("the setup filters stay as the player left them", HistoryListSection.HIDE_MORE_FILTERS,
			onEdt(() -> history().getMoreFiltersText()));
	}

	@Test
	public void bothEmptyStatesSaySoAndNoMatchesOffersClearFilters()
	{
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		assertEquals("<html>" + HistoryListSection.NO_HISTORY + "</html>", onEdt(() -> history().getEmptyText()));
		assertEquals("<html>No saved tasks yet. Finished tasks show up here.</html>",
			onEdt(() -> history().getEmptyText()));
		assertFalse(onEdt(() -> history().isEmptyClearShown()));

		show(tasks());
		assertNull(onEdt(() -> history().getEmptyText()));
		harness.onEdt(() ->
		{
			history().selectSetupFilter("history.taskTypeFilter", "Konar");
			history().selectSetupFilter("history.styleFilter", "Barrage");
		});
		assertEquals("<html>No tasks match these filters.</html>", onEdt(() -> history().getEmptyText()));
		assertTrue(onEdt(() -> history().isEmptyClearShown()));
		assertEquals("Task history \u00b7 2 filters", onEdt(() -> history().getHeaderTitle()));

		harness.onEdt(() -> history().clickEmptyClear());
		assertNull(onEdt(() -> history().getEmptyText()));
		assertEquals(3, (int) onEdt(() -> history().getRowCount()));
		assertEquals(HistoryListSection.TITLE, onEdt(() -> history().getHeaderTitle()));
	}

	@Test
	public void withoutRecordsTheListKeepsTheModelsMessageAndNoFilterStaysSet()
	{
		showTasks();
		harness.onEdt(() ->
		{
			history().selectFilter("Abyssal demons");
			history().selectSetupFilter("history.taskTypeFilter", "Regular");
			history().selectSetupFilter("history.styleFilter", "Barrage");
		});
		assertEquals("Task history · 3 filters", onEdt(() -> history().getHeaderTitle()));

		// The history can't be read: every filter goes back to All or Any, so the header counts none over the empty,
		// disabled list and offers no Clear filters.
		harness.onEdt(() -> harness.historyModel().clearRecords(ACCOUNT, SlayerLedgerPanel.LOAD_FAILED_MESSAGE));
		harness.drain();
		String failed = "<html>" + SlayerLedgerPanel.LOAD_FAILED_MESSAGE + "</html>";
		assertEquals(failed, onEdt(() -> history().getEmptyText()));
		assertEquals(HistoryListSection.TITLE, onEdt(() -> history().getHeaderTitle()));
		assertFalse(onEdt(() -> history().isClearFiltersShown()));
		assertEquals("All | All | All", onEdt(() -> history().getFilterSelections()));
		assertEquals("Any | Any | Any | Any", onEdt(() -> history().getSetupFilterSelections()));

		// A redraw (another Loot price) keeps the message: never "No saved tasks yet." for a history that failed to
		// load, is loading or is logged out.
		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		harness.drain();
		assertEquals(failed, onEdt(() -> history().getEmptyText()));
		harness.onEdt(() -> harness.historyModel().clearRecords(ACCOUNT, SlayerLedgerPanel.LOADING_MESSAGE));
		harness.drain();
		harness.setLootPriceMode(LootPriceMode.GE);
		harness.drain();
		assertEquals("<html>" + SlayerLedgerPanel.LOADING_MESSAGE + "</html>", onEdt(() -> history().getEmptyText()));

		// Loaded with no saved task: that sentence.
		show(Collections.emptyList());
		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		harness.drain();
		assertEquals("<html>" + HistoryListSection.NO_HISTORY + "</html>", onEdt(() -> history().getEmptyText()));
	}

	@Test
	public void aLongNameAndLocationAreCutShortAt225PxAndNothingOverlaps()
	{
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		LedgerRecord record = record("long", "Thermonuclear smoke devil", DURADEL, END, 600);
		record.setLocation("Catacombs of Kourend, the far north end");
		SetupTags.detected(true, SetupStyle.BARRAGE, GearFocus.PRAYER, SetupOverrides.NONE).copyTo(record);
		record.setLootGeValue(1_240_000L);
		record.setSuppliesGeValue(0L);
		show(Collections.singletonList(record));

		int[] widths = onEdt(() -> history().layOut());
		assertEquals(PluginPanel.PANEL_WIDTH, widths[0]);
		assertTrue("the list fits: " + Arrays.toString(widths), widths[1] <= PluginPanel.PANEL_WIDTH);
		assertTrue("the rows fit: " + Arrays.toString(widths), widths[2] <= widths[1]);
		assertTrue(onEdt(() -> history().getListPreferredWidth()) <= PluginPanel.PANEL_WIDTH);
		assertTrue("no part leaves its row or covers another", onEdt(() -> history().partsFitTheirRows()));
		String shown = onEdt(() -> history().getRowTextsShown()).get(0);
		String[] lines = shown.split(" / ");
		assertTrue(shown, lines[0].startsWith("Thermonuclear") && lines[0].contains(FitLabel.ELLIPSIS + " | 10 h"));
		// The tags and the profit keep their room: the location is cut short.
		assertTrue(shown, lines[1].startsWith(date(record) + " \u00b7 C")
			&& lines[1].endsWith(FitLabel.ELLIPSIS + " | ~+1.24M"));
		// The whole of both is in the tooltip.
		String tooltip = onEdt(() -> history().getTooltips()).get(0);
		assertTrue(tooltip, tooltip.startsWith("<html>Thermonuclear smoke devil (cannon)<br>"));
		assertTrue(tooltip, tooltip.contains("<br>Location: Catacombs of Kourend, the far north end<br>"));
		assertEquals(3, onEdt(() -> history().getTagIcons()).get(0).size());
	}

	/** The header's and the second line's words, without the plugin. */
	@Test
	public void theHeaderCountAndTheSecondLineRead()
	{
		assertEquals("Task history", HistoryListSection.headerText(0));
		assertEquals("Task history \u00b7 1 filter", HistoryListSection.headerText(1));
		assertEquals("Task history \u00b7 7 filters", HistoryListSection.headerText(7));
		assertEquals("10/28/26 \u00b7 Catacombs", HistoryListSection.secondLine("10/28/26", "Catacombs"));
		assertEquals("10/28/26", HistoryListSection.secondLine("10/28/26", null));
		assertEquals("no end time: the place alone", "Catacombs", HistoryListSection.secondLine("-", "Catacombs"));
		assertEquals("-", HistoryListSection.secondLine("-", null));
	}

	@Test
	public void theRefreshTimerRebuildsNothing()
	{
		showTasks();
		harness.onEdt(() ->
		{
			history().clickMoreFilters();
			history().selectSetupFilter("history.styleFilter", "Barrage");
			history().clickRow(0);
		});
		harness.drain();
		int builds = onEdt(() -> history().getBuildCount());
		int icons = harness.itemImageRequests();
		List<String> rows = onEdt(() -> history().getRowTexts());
		for (int i = 0; i < 5; i++)
		{
			harness.onEdt(() -> harness.panel().refresh());
		}
		harness.drain();
		assertEquals(builds, (int) onEdt(() -> history().getBuildCount()));
		assertEquals("no icon asked for again", icons, harness.itemImageRequests());
		assertEquals(rows, onEdt(() -> history().getRowTexts()));
		assertEquals("Task history \u00b7 1 filter", onEdt(() -> history().getHeaderTitle()));
		assertNotNull(onEdt(() -> history().getDetailsText(0)));
	}
}
