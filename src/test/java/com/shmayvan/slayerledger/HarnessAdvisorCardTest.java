package com.shmayvan.slayerledger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.util.Filepath;
import net.runelite.client.ui.PluginPanel;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * The advisor card as wired in the plugin ({@link PluginHarness}), read through {@link PanelProbe}:
 * after an assignment with history its title, metric tabs, the recommendation and counterpart blocks with their times
 * and bring items, All setups and More setups; no history, thin data (labelled {@code based on 1 task}) and a Konar
 * area with no task; a tab click saving the "Advisor metric" and the plan following, the old tab kept until it does;
 * collapsed after the first kill (a drop of the count, a task already started at login, an active or restored
 * session, a Konar task), opened and closed by clicks, open again for a new assignment (with or without a no-task
 * gap); a count reading 0 for a tick only hiding it; another Konar area's plan never shown; the skip / block badge on
 * the card and no longer in the task box; nothing rebuilt by the refresh or idle ticks; and the title and option names
 * on two lines without widening the sidebar. Every refresh here is called by the test; nothing waits for the panel's
 * refresh timer, and the first-kill rules are read without one ({@link #card}), as with the panel hidden.
 */
public class HarnessAdvisorCardTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final long MINUTE = 60_000L;
	private static final int DEMONS = 42;
	private static final String TASK = "Abyssal demons";
	/** A SLAYER_AREA value for Konar's assigned area, which the harness names. */
	private static final int KONAR_AREA = 7;
	/** Numbers the records' ids: the store skips a duplicate id, so they must never repeat. */
	private static final AtomicInteger IDS = new AtomicInteger();

	/** A barrage task's supplies for its 10 kills, most valuable at today's prices first (see {@link #setUp}). */
	private static final Map<Integer, Long> BARRAGE_SUPPLIES = new HashMap<>();
	/** A cannon task's supplies for its 10 kills. */
	private static final Map<Integer, Long> CANNON_SUPPLIES = new HashMap<>();

	static
	{
		BARRAGE_SUPPLIES.put(ItemID.BLOODRUNE, 220L);
		BARRAGE_SUPPLIES.put(ItemID.DEATHRUNE, 440L);
		// 12 doses: 3 potions.
		BARRAGE_SUPPLIES.put(ItemID._4DOSEPRAYERRESTORE, 12L);
		BARRAGE_SUPPLIES.put(ItemID.DRAGON_BONES, 2L);
		BARRAGE_SUPPLIES.put(ItemID.MANTARAY, 3L);
		BARRAGE_SUPPLIES.put(ItemID.SHARK, 5L);
		BARRAGE_SUPPLIES.put(ItemID.CHAOSRUNE, 50L);
		BARRAGE_SUPPLIES.put(ItemID.WATERRUNE, 660L);
		BARRAGE_SUPPLIES.put(ItemID.SOULRUNE, 10L);
		BARRAGE_SUPPLIES.put(ItemID.FIRERUNE, 100L);
		CANNON_SUPPLIES.put(SupplyTracker.CANNONBALL, 100L);
		CANNON_SUPPLIES.put(ItemID.SHARK, 5L);
	}

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.BLOODRUNE, "Blood rune", 450, 0);
		harness.defineItem(ItemID.DEATHRUNE, "Death rune", 200, 0);
		harness.defineItem(ItemID._4DOSEPRAYERRESTORE, "Prayer potion(4)", 9_000, 0);
		harness.defineItem(ItemID.DRAGON_BONES, "Dragon bones", 2_500, 0);
		harness.defineItem(ItemID.MANTARAY, "Manta ray", 1_600, 0);
		harness.defineItem(ItemID.SHARK, "Shark", 900, 0);
		harness.defineItem(ItemID.CHAOSRUNE, "Chaos rune", 80, 0);
		harness.defineItem(ItemID.WATERRUNE, "Water rune", 5, 0);
		harness.defineItem(ItemID.SOULRUNE, "Soul rune", 150, 0);
		harness.defineItem(ItemID.FIRERUNE, "Fire rune", 5, 0);
		harness.defineItem(SupplyTracker.CANNONBALL, "Cannonball", 200, 0);
	}

	/** A completed 10-kill task of {@code monster} with {@code minutes} of active time. */
	private static LedgerRecord task(String monster, String spot, boolean cannon, SetupStyle style, GearFocus gear,
		double minutes, long coins, long slayerXp, Map<Integer, Long> supplies)
	{
		LedgerRecord record = new LedgerRecord();
		record.setId("card-" + IDS.incrementAndGet());
		record.setCreatureId(DEMONS);
		record.setMonster(monster);
		record.setKills(10);
		record.setInitialAmount(10);
		record.setOriginalAmount(10);
		record.setActiveMillis(Math.round(minutes * MINUTE));
		record.setCompleted(true);
		record.setSpotId(spot);
		// Valued when saved (its loot's GE total); the history values its items at today's prices.
		record.setLootGeValue(coins);
		record.setLootHaValue(0L);
		record.setSuppliesGeValue(0L);
		record.setLoot(Collections.singletonMap(ItemID.COINS, coins));
		record.setSupplies(new HashMap<>(supplies));
		if (supplies.containsKey(ItemID._4DOSEPRAYERRESTORE))
		{
			record.setSupplyDoses(Collections.singletonMap(ItemID._4DOSEPRAYERRESTORE, 4));
		}
		record.setXpGained(Collections.singletonMap(SkillXp.SLAYER, slayerXp));
		SetupTags.detected(cannon, style, gear, SetupOverrides.NONE).copyTo(record);
		return record;
	}

	/** A barrage task in the Catacombs, in prayer gear: 300K of coins and 30K Slayer XP. */
	private static LedgerRecord barrage(double minutes)
	{
		return task(TASK, TestSpots.CATACOMBS_ID, false, SetupStyle.BARRAGE, GearFocus.PRAYER, minutes, 300_000L,
			30_000L, BARRAGE_SUPPLIES);
	}

	/** A 20-minute cannon task in the Smoke Dungeon: 1K of coins for 24.5K of supplies, a loss; 10K Slayer XP. */
	private static LedgerRecord cannon()
	{
		return task(TASK, TestSpots.SMOKE_ID, true, SetupStyle.MELEE, GearFocus.UNKNOWN, 20, 1_000L, 10_000L,
			CANNON_SUPPLIES);
	}

	/**
	 * Three barrage tasks (27, 30 and 33 minutes: 3 minutes a kill, 2.82 to 3.18 at the 20th and 80th percentiles),
	 * three cannon tasks (2 minutes a kill) and one 50-minute melee task in the Stronghold Slayer Cave.
	 */
	private static List<LedgerRecord> history()
	{
		List<LedgerRecord> records = new ArrayList<>();
		for (double minutes : new double[]{27, 30, 33})
		{
			records.add(barrage(minutes));
			records.add(cannon());
		}
		records.add(task(TASK, TestSpots.STRONGHOLD_ID, false, SetupStyle.MELEE, GearFocus.UNKNOWN, 50, 50_000L,
			10_000L, CANNON_SUPPLIES));
		return records;
	}

	/**
	 * Saves {@code records} to the account's ledger file and starts the plugin on a 20-kill {@code task} (creature key
	 * {@link #DEMONS}); the login loads them, as a task saved later is loaded with them.
	 */
	private void start(String task, List<LedgerRecord> records)
	{
		start(task, records, 20, 20);
	}

	/** {@link #start(String, List)} on a task with {@code remaining} of {@code assigned} kills left at the login. */
	private void start(String task, List<LedgerRecord> records, int remaining, int assigned)
	{
		LedgerStore store = new LedgerStore(Filepath.Unchecked.getRooted(harness.ledgerDirectory().toPath()),
			harness.gson);
		try
		{
			for (LedgerRecord record : records)
			{
				store.append(ACCOUNT, record);
			}
		}
		catch (IOException e)
		{
			throw new AssertionError("unable to save the records", e);
		}
		harness.startUp();
		harness.setInventory();
		harness.setTask(DEMONS, task, remaining, assigned);
		harness.loginAndSettle(ACCOUNT);
	}

	/** {@link #start(String, List)} on an Abyssal demons task. */
	private void start(List<LedgerRecord> records)
	{
		start(TASK, records);
	}

	/** Reads the card on the EDT, without refreshing the panel. */
	private <T> T card(Function<PanelProbe.AdvisorCardProbe, T> read)
	{
		List<T> result = new ArrayList<>(1);
		harness.onEdt(() -> result.add(read.apply(harness.ui().getAdvisor())));
		return result.get(0);
	}

	/** Refreshes the panel, as its timer would, then reads the card. */
	private <T> T refreshed(Function<PanelProbe.AdvisorCardProbe, T> read)
	{
		List<T> result = new ArrayList<>(1);
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			result.add(read.apply(harness.ui().getAdvisor()));
		});
		return result.get(0);
	}

	/** Clicks on the card, then lets what the click queued run. */
	private void click(java.util.function.Consumer<PanelProbe.AdvisorCardProbe> action)
	{
		harness.onEdt(() -> action.accept(harness.ui().getAdvisor()));
		harness.drain();
	}

	@Test
	public void afterAnAssignmentTheCardShowsThePlan()
	{
		start(history());

		assertTrue(card(PanelProbe.AdvisorCardProbe::isShown));
		assertTrue("open until the first kill", card(PanelProbe.AdvisorCardProbe::isOpen));
		assertEquals("Based on 7 Abyssal demons tasks", card(PanelProbe.AdvisorCardProbe::getTitle));
		assertEquals("[Fastest] | Profit/h | Cheapest", card(PanelProbe.AdvisorCardProbe::getTabs));
		assertTrue(card(PanelProbe.AdvisorCardProbe::areTabsFocusableLinks));

		// Fastest finish: the cannon tasks, at 2 minutes a kill; the most valuable things to bring first.
		assertEquals(Arrays.asList(
			"Cannon, melee, Smoke Dungeon | 3 tasks",
			"~40 min",
			"Slayer XP/h | 30K",
			"Profit/h | -70.5K",
			"Supplies | 49K",
			"Gear | -",
			"Bring",
			"Cannonball | " + SupplyTracker.CANNONBALL + " x200",
			"Shark | " + ItemID.SHARK + " x10"), card(probe -> probe.getBlock("recommendation")));
		// The sign says it's a loss; red ink adds to it.
		assertEquals(LedgerTheme.RED_INK, card(probe -> probe.getRowValueColor("recommendation", "profit")));

		// The counterpart, without a cannon, in the same block after a rule: 8 items, then the rest counted.
		assertTrue(card(PanelProbe.AdvisorCardProbe::hasRule));
		assertEquals("Without cannon", card(PanelProbe.AdvisorCardProbe::getCounterpartLabel));
		assertEquals(Arrays.asList(
			"Barrage, Catacombs | 3 tasks",
			"~1 h | 56 min to 1 h 4 min",
			"Slayer XP/h | 60K",
			"Profit/h | +124K",
			"Supplies | 475K",
			"Gear | mostly Prayer",
			"Bring",
			"Blood rune | " + ItemID.BLOODRUNE + " x440",
			"Death rune | " + ItemID.DEATHRUNE + " x880",
			"Prayer potion(4) | " + ItemID._4DOSEPRAYERRESTORE + " x6",
			"Dragon bones | " + ItemID.DRAGON_BONES + " x4",
			"Manta ray | " + ItemID.MANTARAY + " x6",
			"Shark | " + ItemID.SHARK + " x10",
			"Chaos rune | " + ItemID.CHAOSRUNE + " x100",
			"Water rune | " + ItemID.WATERRUNE + " x1320",
			"+2 more"), card(probe -> probe.getBlock("counterpart")));
		assertEquals(LedgerTheme.INK, card(probe -> probe.getRowValueColor("counterpart", "profit")));
		assertEquals("<html>Prayer potion(4)<br>Bring: 6<br>GE value: 54,000 gp</html>",
			card(probe -> probe.getBring("counterpart").getCellTooltips().get(2)));
		// The two the grid has no room for, listed in "+2 more"'s tooltip, a line each, as the icons word them.
		assertEquals("<html>Soul rune, Bring: 20, GE value: 3,000 gp<br>Fire rune, Bring: 200, GE value: 1,000 gp"
			+ "</html>", card(probe -> probe.getBringMoreTooltip("counterpart")));
		assertNull("all in the grid", card(probe -> probe.getBringMoreTooltip("recommendation")));
		assertEquals("five slots to a row: 8 items fill two", 10,
			(int) card(probe -> probe.getBring("counterpart").getSlotCount()));

		assertEquals("All setups (7 tasks) | ~54 min", card(PanelProbe.AdvisorCardProbe::getAllSetups));
		assertEquals("More setups (1)", card(PanelProbe.AdvisorCardProbe::getMoreToggle));
		assertTrue(card(PanelProbe.AdvisorCardProbe::getMoreRows).isEmpty());
		click(PanelProbe.AdvisorCardProbe::clickMoreToggle);
		assertEquals(Collections.singletonList("Melee, Stronghold Cave | ~1 h 40 min (1 task)"),
			card(PanelProbe.AdvisorCardProbe::getMoreRows));
		assertEquals("Hide more setups", card(PanelProbe.AdvisorCardProbe::getMoreToggle));

		// Its place: after the live section, before the task history.
		List<String> order = new ArrayList<>();
		harness.onEdt(() -> order.addAll(harness.ui().getContentOrder()));
		assertTrue(order.toString(), order.indexOf("liveXp") < order.indexOf("advisor"));
		assertTrue(order.toString(), order.indexOf("advisor") < order.indexOf("history.header"));
	}

	@Test
	public void theMoreBringItemsTooltipIsEscaped()
	{
		harness.defineItem(ItemID.FIRERUNE, "Fire <b>rune</b> & co", 5, 0);
		start(history());

		assertEquals("<html>Soul rune, Bring: 20, GE value: 3,000 gp<br>Fire &lt;b&gt;rune&lt;/b&gt; &amp; co,"
			+ " Bring: 200, GE value: 1,000 gp</html>", card(probe -> probe.getBringMoreTooltip("counterpart")));
	}

	@Test
	public void withNoHistoryTheCardSaysSoInOneLine()
	{
		// Another monster's tasks only.
		LedgerRecord devils = task("Dust devils", TestSpots.SMOKE_ID, false, SetupStyle.MELEE, GearFocus.UNKNOWN, 30,
			1_000L, 1_000L, CANNON_SUPPLIES);
		devils.setCreatureId(DEMONS + 1);
		start(Collections.singletonList(devils));

		assertTrue(card(PanelProbe.AdvisorCardProbe::isShown));
		assertNull("no title", card(PanelProbe.AdvisorCardProbe::getTitle));
		assertNull("no tabs", card(PanelProbe.AdvisorCardProbe::getTabs));
		assertNull(card(probe -> probe.getBlock("recommendation")));
		assertEquals("No completed Abyssal demons tasks yet, so no estimate.",
			card(PanelProbe.AdvisorCardProbe::getNoHistoryLine));
		// Broken into lines that fit the card, so it neither widens the sidebar nor is cut short.
		assertTrue(card(PanelProbe.AdvisorCardProbe::getNoHistoryLines).size() > 1);
		int contentWidth = PluginPanel.PANEL_WIDTH - 2 * PluginPanel.BORDER_OFFSET;
		int preferred = card(PanelProbe.AdvisorCardProbe::getPreferredWidth);
		assertTrue("the card asks for no more than the content's 213 px: " + preferred, preferred <= contentWidth);
		int[] laidOut = card(PanelProbe.AdvisorCardProbe::layOut);
		assertTrue(Arrays.toString(laidOut), laidOut[2] <= contentWidth);
		for (String line : card(PanelProbe.AdvisorCardProbe::getNoHistoryLinesShown))
		{
			assertFalse(line, line.endsWith(FitLabel.ELLIPSIS));
		}
	}

	@Test
	public void thinDataShowsTheOneTask()
	{
		start(Collections.singletonList(barrage(30)));

		assertEquals("Based on 1 Abyssal demons task", card(PanelProbe.AdvisorCardProbe::getTitle));
		List<String> block = card(probe -> probe.getBlock("recommendation"));
		assertEquals("a thin recommendation says what it is based on (V2 plan 6)", "Barrage, Catacombs | based on 1 task",
			block.get(0));
		List<String> name = card(probe -> probe.getNameLines("recommendation"));
		assertEquals("the longer sample size leaves less room beside the name, which wraps",
			Arrays.asList("Barrage,", "Catacombs"), name);
		assertEquals("one task: no range", "~1 h", block.get(1));
		assertNull("no counterpart", card(probe -> probe.getBlock("counterpart")));
		assertFalse(card(PanelProbe.AdvisorCardProbe::hasRule));
		assertNull(card(PanelProbe.AdvisorCardProbe::getMoreToggle));
	}

	@Test
	public void aKonarAreaWithNoTaskThereUsesEveryArea()
	{
		harness.setAssignment(SlayerMaster.KONAR, KONAR_AREA, "Slayer Tower");
		start(history());

		assertEquals("No completed tasks in " + TaskPlanText.shortSpotName(TestSpots.TOWER_ID, TestSpots.TABLE, null)
			+ " yet, so all areas are used.", card(PanelProbe.AdvisorCardProbe::getAreaNote));
		assertTrue("lines that fit the note", card(PanelProbe.AdvisorCardProbe::getAreaNoteLines).size() > 1);
		assertEquals("every option", "Cannon, melee, Smoke Dungeon | 3 tasks",
			card(probe -> probe.getBlock("recommendation")).get(0));

		// A Konar area with tasks there: no note.
		harness.setAssignment(SlayerMaster.KONAR, KONAR_AREA + 1, "Catacombs of Kourend");
		harness.tick();
		assertNull(card(PanelProbe.AdvisorCardProbe::getAreaNote));
		assertEquals("Barrage, Catacombs | 3 tasks", card(probe -> probe.getBlock("recommendation")).get(0));
	}

	@Test
	public void aTabClickSavesTheMetricAndThePlanFollows()
	{
		start(history());
		assertEquals("Cannon, melee, Smoke Dungeon | 3 tasks", card(probe -> probe.getBlock("recommendation")).get(0));
		int planBuilds = planBuilds();

		click(probe -> probe.clickTab(AdvisorMetric.PROFIT_PER_HOUR));
		assertEquals("the setting is saved", AdvisorMetric.PROFIT_PER_HOUR, harness.config.advisorMetric);
		assertEquals("Fastest | [Profit/h] | Cheapest", card(PanelProbe.AdvisorCardProbe::getTabs));
		assertEquals("worked out again on the config-change path, once", planBuilds + 1, planBuilds());
		// The most profit per hour: the barrage tasks, and the counterpart is the cannon's.
		assertEquals("Barrage, Catacombs | 3 tasks", card(probe -> probe.getBlock("recommendation")).get(0));
		assertEquals("With cannon", card(PanelProbe.AdvisorCardProbe::getCounterpartLabel));
		assertEquals("Cannon, melee, Smoke Dungeon | 3 tasks", card(probe -> probe.getBlock("counterpart")).get(0));

		// The tab already chosen saves nothing.
		click(probe -> probe.clickTab(AdvisorMetric.PROFIT_PER_HOUR));
		assertEquals(planBuilds + 1, planBuilds());

		// The settings panel's change shows on the tabs too.
		harness.setAdvisorMetric(AdvisorMetric.CHEAPEST);
		assertEquals("Fastest | Profit/h | [Cheapest]", card(PanelProbe.AdvisorCardProbe::getTabs));
	}

	@Test
	public void afterATabClickTheOldTabStaysUntilTheNewPlanIsDelivered()
	{
		start(history());

		// The click is saved and reaches the model (the EDT runs), but the plan is still being worked out (the
		// executor hasn't run): the card keeps the plan it shows, and the tab is that plan's, not the setting's.
		harness.onEdt(() -> harness.ui().getAdvisor().clickTab(AdvisorMetric.PROFIT_PER_HOUR));
		harness.flushEdt();
		harness.onEdt(() -> assertEquals(AdvisorMetric.PROFIT_PER_HOUR, harness.historyModel().advisorMetric()));
		harness.onEdt(() -> assertNull("still worked out", harness.historyModel().plan()));
		assertTrue(card(PanelProbe.AdvisorCardProbe::isShown));
		assertEquals("the tab and the blocks agree", "[Fastest] | Profit/h | Cheapest",
			card(PanelProbe.AdvisorCardProbe::getTabs));
		assertEquals("Cannon, melee, Smoke Dungeon | 3 tasks", card(probe -> probe.getBlock("recommendation")).get(0));
		assertEquals("[Fastest] | Profit/h | Cheapest", refreshed(PanelProbe.AdvisorCardProbe::getTabs));

		// Delivered: the new plan and its tab together.
		harness.drain();
		assertEquals("Fastest | [Profit/h] | Cheapest", card(PanelProbe.AdvisorCardProbe::getTabs));
		assertEquals("Barrage, Catacombs | 3 tasks", card(probe -> probe.getBlock("recommendation")).get(0));
	}

	@Test
	public void collapsedAfterTheFirstKillOpenedByAClickAndOpenAgainForANewTask()
	{
		start(history());
		assertTrue(card(PanelProbe.AdvisorCardProbe::isOpen));

		// The first kill: one line, the plan for the 19 kills left.
		harness.setRemaining(19);
		harness.tick();
		assertFalse(refreshed(PanelProbe.AdvisorCardProbe::isOpen));
		assertEquals("Plan: cannon, melee, ~38 min (3 tasks)", card(PanelProbe.AdvisorCardProbe::getCollapsedLine));
		String tooltip = card(PanelProbe.AdvisorCardProbe::getCollapsedTooltip);
		assertTrue(tooltip, tooltip.contains("Cannon, melee, Smoke Dungeon (3 tasks)"));
		assertTrue(tooltip, tooltip.contains("Without cannon: Barrage, Catacombs, ~57 min (3 tasks)"));
		assertTrue(tooltip, tooltip.endsWith(AdvisorCardSection.OPEN_HINT + "</html>"));

		// A click opens it; the next kill keeps the player's choice.
		click(PanelProbe.AdvisorCardProbe::clickCollapsed);
		assertTrue(card(PanelProbe.AdvisorCardProbe::isOpen));
		harness.setRemaining(18);
		harness.tick();
		assertTrue(refreshed(PanelProbe.AdvisorCardProbe::isOpen));
		assertEquals("~36 min", card(probe -> probe.getBlock("recommendation")).get(1));

		// A click on the title closes it again.
		click(PanelProbe.AdvisorCardProbe::clickTitle);
		assertFalse(refreshed(PanelProbe.AdvisorCardProbe::isOpen));

		// The task done and a new one assigned: open again.
		harness.completeTask();
		harness.tick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS + 1);
		assertFalse("no task: no card", refreshed(PanelProbe.AdvisorCardProbe::isShown));
		harness.setTask(DEMONS, TASK, 15, 15);
		harness.tick();
		assertTrue(refreshed(PanelProbe.AdvisorCardProbe::isShown));
		assertTrue("a new assignment opens it", card(PanelProbe.AdvisorCardProbe::isOpen));
		assertEquals("~30 min", card(probe -> probe.getBlock("recommendation")).get(1));
	}

	@Test
	public void theBadgeIsOnTheCardAndNoLongerInTheTaskBox()
	{
		List<LedgerRecord> records = history();
		// Five other monsters at 50K Slayer XP and 20K profit an hour: Abyssal demons, at 39K Slayer XP and 42.7K
		// profit an hour, is the lowest of 6 by Slayer XP and the highest by profit.
		for (int monster = 1; monster <= 5; monster++)
		{
			for (int i = 0; i < 3; i++)
			{
				LedgerRecord other = task("Monster " + monster, TestSpots.SMOKE_ID, false, SetupStyle.MELEE,
					GearFocus.UNKNOWN, 60, 20_000L, 50_000L, Collections.emptyMap());
				other.setCreatureId(DEMONS + monster);
				records.add(other);
			}
		}
		start(records);

		assertEquals("Consider skip (n=7)", card(PanelProbe.AdvisorCardProbe::getBadge));
		assertEquals(LedgerTheme.ALERT, card(PanelProbe.AdvisorCardProbe::getBadgeColor));
		String tooltip = card(PanelProbe.AdvisorCardProbe::getBadgeTooltip);
		assertTrue(tooltip, tooltip.contains("1st lowest of your 6 monsters (bottom 25%)"));
		assertTrue(tooltip, tooltip.contains("From 7 completed tasks of this monster (n)"));
		List<String> rows = new ArrayList<>();
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			rows.addAll(harness.ui().getTaskBox().getRows());
		});
		assertEquals("the task box has only the task's lines", Arrays.asList(TASK, "20 left"), rows);
		harness.onEdt(() -> assertFalse(harness.ui().isInPanel("taskBox.advice")));

		// The "Task advisor" setting turns the badge off; the card stays.
		harness.setTaskAdvisor(false);
		assertNull(card(PanelProbe.AdvisorCardProbe::getBadge));
		assertTrue(card(PanelProbe.AdvisorCardProbe::isShown));
		harness.setTaskAdvisor(true);
		assertEquals("Consider skip (n=7)", card(PanelProbe.AdvisorCardProbe::getBadge));

		// Profit per hour: Abyssal demons are not the lowest by profit, so Do, which adds no line.
		harness.setAdvisorMetric(AdvisorMetric.PROFIT_PER_HOUR);
		assertNull(card(PanelProbe.AdvisorCardProbe::getBadge));
		harness.onEdt(() -> assertEquals(TaskAdvisor.Advice.DO, harness.historyModel().advice().getAdvice()));
	}

	@Test
	public void nothingIsRebuiltByTheRefreshOrIdleTicks()
	{
		start(history());
		int builds = card(PanelProbe.AdvisorCardProbe::getBuildCount);

		for (int i = 0; i < 5; i++)
		{
			refreshed(PanelProbe.AdvisorCardProbe::isShown);
		}
		harness.ticks(5);
		refreshed(PanelProbe.AdvisorCardProbe::isShown);
		assertEquals(builds, (int) card(PanelProbe.AdvisorCardProbe::getBuildCount));

		// A kill is a change: collapsed, and the plan for the kills left; then idle again.
		harness.setRemaining(19);
		harness.tick();
		refreshed(PanelProbe.AdvisorCardProbe::isShown);
		int afterKill = card(PanelProbe.AdvisorCardProbe::getBuildCount);
		assertTrue(afterKill > builds);
		for (int i = 0; i < 5; i++)
		{
			refreshed(PanelProbe.AdvisorCardProbe::isShown);
		}
		harness.ticks(5);
		assertEquals(afterKill, (int) card(PanelProbe.AdvisorCardProbe::getBuildCount));
	}

	@Test
	public void theTitleAndOptionNamesWrapOntoTwoLinesAndAreShownWhole()
	{
		start(history());
		int[] laidOut = card(PanelProbe.AdvisorCardProbe::layOut);
		int contentWidth = PluginPanel.PANEL_WIDTH - 2 * PluginPanel.BORDER_OFFSET;
		assertTrue(Arrays.toString(laidOut), laidOut[2] <= contentWidth);

		// At 225 px neither fits one line in Bold: each takes two, which hold the whole text.
		List<String> title = card(PanelProbe.AdvisorCardProbe::getTitleLinesShown);
		assertEquals(title.toString(), 2, title.size());
		assertEquals("Based on 7 Abyssal demons tasks", String.join(" ", title));
		List<String> best = card(probe -> probe.getNameLinesShown("recommendation"));
		assertEquals(best.toString(), 2, best.size());
		assertEquals("the sample size stays on the first line's right, the rest goes under it",
			"Cannon, melee, Smoke Dungeon", String.join(" ", best));
		assertEquals(Collections.singletonList("Barrage, Catacombs"),
			card(probe -> probe.getNameLinesShown("counterpart")));
		List<String> all = new ArrayList<>(title);
		all.addAll(best);
		for (String line : all)
		{
			assertFalse(line, line.contains(FitLabel.ELLIPSIS));
		}
		assertEquals("Cannon, melee, Smoke Dungeon | 3 tasks", card(probe -> probe.getBlock("recommendation")).get(0));
	}

	@Test
	public void pastTwoLinesTheTitleIsCutShortAndNothingWidensTheSidebar()
	{
		String task = "Abyssal demons with a name made long for the width check";
		List<LedgerRecord> records = new ArrayList<>();
		for (int i = 0; i < 3; i++)
		{
			records.add(task(task, TestSpots.STRONGHOLD_ID, true, SetupStyle.BARRAGE, GearFocus.STRENGTH, 30, 300_000L,
				30_000L, BARRAGE_SUPPLIES));
		}
		start(task, records);

		assertEquals("Based on 3 " + task + " tasks", card(PanelProbe.AdvisorCardProbe::getTitle));
		int contentWidth = PluginPanel.PANEL_WIDTH - 2 * PluginPanel.BORDER_OFFSET;
		int preferred = card(PanelProbe.AdvisorCardProbe::getPreferredWidth);
		assertTrue("the card asks for no more than the content's 213 px: " + preferred, preferred <= contentWidth);
		int[] laidOut = card(PanelProbe.AdvisorCardProbe::layOut);
		assertEquals(PluginPanel.PANEL_WIDTH, laidOut[0]);
		assertTrue(Arrays.toString(laidOut), laidOut[1] <= contentWidth);
		assertTrue(Arrays.toString(laidOut), laidOut[2] <= contentWidth);
		// Two lines, the second holding the rest of the title and cut short; the tooltip has it all.
		List<String> shown = card(PanelProbe.AdvisorCardProbe::getTitleLinesShown);
		assertEquals(shown.toString(), 2, shown.size());
		assertTrue(shown.toString(), shown.get(0).startsWith("Based on 3 Abyssal demons"));
		assertTrue("the rest is cut short: " + shown, shown.get(1).endsWith(FitLabel.ELLIPSIS));
		List<String> lines = card(PanelProbe.AdvisorCardProbe::getTitleLines);
		assertEquals("Based on 3 " + task + " tasks", String.join(" ", lines));
		String title = card(PanelProbe.AdvisorCardProbe::getTitleTooltip);
		assertTrue(title, title.startsWith("<html>Based on 3 " + task + " tasks<br>"));
		// The option's name fits two lines: whole.
		List<String> name = card(probe -> probe.getNameLinesShown("recommendation"));
		assertEquals("Cannon, barrage, Stronghold Cave", String.join(" ", name));
	}

	@Test
	public void aTaskAlreadyStartedAtTheLoginIsCollapsed()
	{
		start(TASK, history(), 12, 20);

		// Read as the model's changes left it: no panel refresh.
		assertTrue(card(PanelProbe.AdvisorCardProbe::isShown));
		assertFalse("fewer left than assigned: its first kill is done", card(PanelProbe.AdvisorCardProbe::isOpen));
		assertEquals("Plan: cannon, melee, ~24 min (3 tasks)", card(PanelProbe.AdvisorCardProbe::getCollapsedLine));
		assertRefreshBuildsNothing();
	}

	@Test
	public void aRestoredSessionIsCollapsed()
	{
		start(history());
		// A session of the task with no kill yet, kept over a restart: restored as Logged out, the count unchanged.
		assertTrue(harness.startNow());
		harness.logout();
		harness.shutDown();
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		assertEquals(SessionTracker.State.LOGGED_OUT, harness.sessionState());
		assertEquals(20, harness.tracker().getCurrentRemaining());

		// The restore is applied after the last tick's live check, and no tick follows: the restore runs the check
		// itself, so the model is told now, whatever the EDT ran before it (the overlay's pace shows it).
		assertNotNull("the restored session's pace", harness.overlayData().getHistoricalMillisPerKill());
		assertTrue(card(PanelProbe.AdvisorCardProbe::isShown));
		assertFalse("an active session of the task", card(PanelProbe.AdvisorCardProbe::isOpen));
		assertRefreshBuildsNothing();
	}

	@Test
	public void aSessionOfTheTaskCollapsesTheCardWithoutARefreshAndRebuildsNothingElse()
	{
		start(history());
		assertTrue(card(PanelProbe.AdvisorCardProbe::isOpen));
		int cardBuilds = card(PanelProbe.AdvisorCardProbe::getBuildCount);
		int[] others = otherSectionBuilds();

		// A session of the task starts with no kill (as a restored one is): no input of the plan changed, so no view
		// is worked out and nothing refreshes the panel. The model's live change alone tells the card.
		assertTrue(harness.startNow());
		assertFalse("a session of the task", card(PanelProbe.AdvisorCardProbe::isOpen));
		assertEquals("collapsed once", cardBuilds + 1, (int) card(PanelProbe.AdvisorCardProbe::getBuildCount));
		assertArrayEquals("no other section is rebuilt for it", others, otherSectionBuilds());

		// Ticks with nothing new build nothing.
		harness.ticks(3);
		assertEquals(cardBuilds + 1, (int) card(PanelProbe.AdvisorCardProbe::getBuildCount));
		assertRefreshBuildsNothing();
	}

	/**
	 * The build counts of the sections other than the card: the history, Overall totals, Skipped / blocked, the live
	 * card's header and its setup toggles.
	 */
	private int[] otherSectionBuilds()
	{
		int[] builds = new int[5];
		harness.onEdt(() ->
		{
			SlayerLedgerPanel panel = harness.panel();
			builds[0] = panel.getHistoryListBuildCount();
			builds[1] = panel.getOverallTotalsBuildCount();
			builds[2] = panel.getAssignmentsBuildCount();
			builds[3] = panel.getLiveCardBuildCount();
			builds[4] = panel.getLiveSetupBuildCount();
		});
		return builds;
	}

	@Test
	public void theSameCreatureReassignedWithoutAGapOpensAgain()
	{
		start(history());
		harness.setRemaining(19);
		harness.tick();
		assertFalse(card(PanelProbe.AdvisorCardProbe::isOpen));

		// A new assignment of the same creature, read with no tick of no task between.
		harness.setTask(DEMONS, TASK, 30, 30);
		harness.tick();
		assertTrue(card(PanelProbe.AdvisorCardProbe::isShown));
		assertTrue("a new assignment opens it", card(PanelProbe.AdvisorCardProbe::isOpen));
		assertEquals("~1 h", card(probe -> probe.getBlock("recommendation")).get(1));
		assertRefreshBuildsNothing();
	}

	@Test
	public void aKonarTaskCollapsesAfterItsFirstKillAndOpensForANewArea()
	{
		harness.setAssignment(SlayerMaster.KONAR, KONAR_AREA + 1, "Catacombs of Kourend");
		start(history());
		assertTrue(card(PanelProbe.AdvisorCardProbe::isOpen));
		assertEquals("Barrage, Catacombs | 3 tasks", card(probe -> probe.getBlock("recommendation")).get(0));

		harness.setRemaining(19);
		harness.tick();
		assertFalse(card(PanelProbe.AdvisorCardProbe::isOpen));
		assertEquals("Plan: barrage, ~57 min (3 tasks)", card(PanelProbe.AdvisorCardProbe::getCollapsedLine));
		assertRefreshBuildsNothing();

		// Konar gives the same creature again, elsewhere: a new assignment, open, with that area's plan.
		harness.setAssignment(SlayerMaster.KONAR, KONAR_AREA, "Slayer Tower");
		harness.setTask(DEMONS, TASK, 25, 25);
		harness.tick();
		assertTrue(card(PanelProbe.AdvisorCardProbe::isOpen));
		assertEquals("No completed tasks in " + TaskPlanText.shortSpotName(TestSpots.TOWER_ID, TestSpots.TABLE, null)
			+ " yet, so all areas are used.", card(PanelProbe.AdvisorCardProbe::getAreaNote));
		assertRefreshBuildsNothing();
	}

	@Test
	public void aCountReadingZeroForATickOnlyHidesTheCard()
	{
		start(history());
		harness.setRemaining(19);
		harness.tick();
		// The player opens it after the first kill: their choice.
		click(PanelProbe.AdvisorCardProbe::clickCollapsed);
		assertTrue(refreshed(PanelProbe.AdvisorCardProbe::isOpen));
		int builds = card(PanelProbe.AdvisorCardProbe::getBuildCount);

		// The count reads 0 for one tick (SessionTracker.MAX_TRANSIENT_ZERO_OBSERVATIONS), then the same task is back
		// with the same kills left, so its plan is the same and no model change says so.
		harness.setRemaining(0);
		harness.tick();
		assertFalse("no task: hidden", refreshed(PanelProbe.AdvisorCardProbe::isShown));
		harness.setRemaining(19);
		harness.tick();
		assertTrue("shown again by the refresh", refreshed(PanelProbe.AdvisorCardProbe::isShown));
		assertTrue("the player's choice holds", card(PanelProbe.AdvisorCardProbe::isOpen));
		assertEquals("~38 min", card(probe -> probe.getBlock("recommendation")).get(1));
		assertEquals("nothing rebuilt", builds, (int) card(PanelProbe.AdvisorCardProbe::getBuildCount));
	}

	@Test
	public void aRebuildWhileNoTaskIsReadStillNamesTheTask()
	{
		// The cannon tasks were done in a region the spot table doesn't list, so the card names it "Region N" until the
		// player names it.
		final int unlisted = 1_234;
		List<LedgerRecord> records = new ArrayList<>();
		for (int i = 0; i < 3; i++)
		{
			records.add(task(TASK, "region:" + unlisted, true, SetupStyle.MELEE, GearFocus.UNKNOWN, 20, 1_000L, 10_000L,
				CANNON_SUPPLIES));
		}
		start(records);
		String title = card(PanelProbe.AdvisorCardProbe::getTitle);
		assertTrue(title, title.contains("Abyssal demons"));
		int builds = card(PanelProbe.AdvisorCardProbe::getBuildCount);

		// The count reads 0 for a tick, and meanwhile that region gets its name: the card is rebuilt from its kept plan,
		// with the plan's task name, not the tracker's "no task".
		harness.setRemaining(0);
		harness.tick();
		assertFalse(refreshed(PanelProbe.AdvisorCardProbe::isShown));
		harness.onEdt(() -> harness.historyModel().addLocationNames(Collections.singleton(unlisted),
			Collections.singletonMap(unlisted, "My spot")));
		assertTrue("rebuilt for the new spot name", card(PanelProbe.AdvisorCardProbe::getBuildCount) > builds);

		// The task is read again: the card the gap built shows, naming the task and the region's new name.
		harness.setRemaining(20);
		harness.tick();
		assertTrue(refreshed(PanelProbe.AdvisorCardProbe::isShown));
		assertEquals("still the task's name", title, card(PanelProbe.AdvisorCardProbe::getTitle));
		assertTrue(card(probe -> probe.getBlock("recommendation")).get(0).contains("My spot"));
	}

	@Test
	public void aReloginToTheSameTaskShowsTheCardAgain()
	{
		start(history());
		assertTrue(card(PanelProbe.AdvisorCardProbe::isShown));
		assertTrue(card(PanelProbe.AdvisorCardProbe::isOpen));
		List<String> recommendation = card(probe -> probe.getBlock("recommendation"));

		// Logging out clears the history; logging back in to the same account, task and place (no kill in between)
		// loads it again, and the first settled tick tells the model the task, which asks for the plan.
		harness.logout();
		harness.loginAndSettle(ACCOUNT);
		assertTrue("shown again after the relog", card(PanelProbe.AdvisorCardProbe::isShown));
		assertTrue("open: no kill yet", card(PanelProbe.AdvisorCardProbe::isOpen));
		assertEquals("the same plan", recommendation, card(probe -> probe.getBlock("recommendation")));
	}

	@Test
	public void anotherKonarAreasPlanIsNeverShown()
	{
		harness.setAssignment(SlayerMaster.KONAR, KONAR_AREA + 1, "Catacombs of Kourend");
		start(history());
		assertEquals("Barrage, Catacombs | 3 tasks", card(probe -> probe.getBlock("recommendation")).get(0));

		// The same creature in another area, same kills left: the tick is read and the new plan asked for, but not
		// worked out yet (the executor hasn't run). The Catacombs plan is not this assignment's.
		harness.setAssignment(SlayerMaster.KONAR, KONAR_AREA, "Slayer Tower");
		harness.tickWithoutDrain();
		harness.flushEdt();
		assertFalse("hidden until this area's plan is ready", refreshed(PanelProbe.AdvisorCardProbe::isShown));

		harness.drain();
		assertTrue(card(PanelProbe.AdvisorCardProbe::isShown));
		assertEquals("No completed tasks in " + TaskPlanText.shortSpotName(TestSpots.TOWER_ID, TestSpots.TABLE, null)
			+ " yet, so all areas are used.", card(PanelProbe.AdvisorCardProbe::getAreaNote));
		assertEquals("Cannon, melee, Smoke Dungeon | 3 tasks", card(probe -> probe.getBlock("recommendation")).get(0));
	}

	/** A panel refresh after the model's changes built the card finds nothing to change: nothing is rebuilt. */
	private void assertRefreshBuildsNothing()
	{
		int builds = card(PanelProbe.AdvisorCardProbe::getBuildCount);
		boolean open = card(PanelProbe.AdvisorCardProbe::isOpen);
		assertEquals(open, refreshed(PanelProbe.AdvisorCardProbe::isOpen));
		assertEquals(builds, (int) card(PanelProbe.AdvisorCardProbe::getBuildCount));
	}

	private int planBuilds()
	{
		AtomicInteger builds = new AtomicInteger();
		harness.onEdt(() -> builds.set(harness.historyModel().getPlanBuildCount()));
		return builds.get();
	}
}
