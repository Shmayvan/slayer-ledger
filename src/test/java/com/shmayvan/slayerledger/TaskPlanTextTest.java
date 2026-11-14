package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.TestSpots.CATACOMBS_ID;
import static com.shmayvan.slayerledger.TestSpots.SMOKE_ID;
import static com.shmayvan.slayerledger.TestSpots.STRONGHOLD_ID;
import static com.shmayvan.slayerledger.TestSpots.TABLE;
import static com.shmayvan.slayerledger.TestSpots.UNLISTED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;
import org.junit.Test;

/**
 * The advisor's words: the assignment chat line (its length, what it keeps longest, the counterpart's spot, the
 * no-history and unknown-kills wording, and as a chat message its colours and escaping), estimated times and paces,
 * and how items and spots are named in it ({@code DESIGN_PRINCIPLES.md} section 5); and the advisor card's text
 *: its title, option names, sample sizes and ranges, the Konar, Wilderness and setup-unknown notes,
 * the collapsed line and the skip / block badge.
 */
public class TaskPlanTextTest
{
	private static final int CREATURE = 42;
	private static final String TASK = "Dust devils";
	private static final long MINUTE = 60_000L;
	private static final int DEATH_RUNE = 560;
	private static final int BLOOD_RUNE = 565;
	private static final IntFunction<String> NO_REGION_NAMES = region -> null;
	private static final Map<Integer, Long> PRICES = new HashMap<>();
	private static final Map<Integer, String> NAMES = new HashMap<>();

	static
	{
		PRICES.put(DEATH_RUNE, 300L);
		PRICES.put(BLOOD_RUNE, 400L);
		NAMES.put(DEATH_RUNE, "Death rune");
		NAMES.put(BLOOD_RUNE, "Blood rune");
	}

	private static LedgerRecord task(String spot, boolean cannon, SetupStyle style, double minutes)
	{
		final LedgerRecord record = new LedgerRecord();
		record.setCreatureId(CREATURE);
		record.setMonster(TASK);
		record.setKills(142);
		record.setActiveMillis(Math.round(minutes * MINUTE));
		record.setCompleted(true);
		record.setSpotId(spot);
		SetupTags.detected(cannon, style, GearFocus.UNKNOWN, SetupOverrides.NONE).copyTo(record);
		return record;
	}

	/** Three barrage tasks in the Catacombs (24 minutes, 440 death and 220 blood runes) and two cannon tasks. */
	private static List<LedgerRecord> example()
	{
		final List<LedgerRecord> records = new ArrayList<>();
		for (int i = 0; i < 3; i++)
		{
			final LedgerRecord barrage = task(CATACOMBS_ID, false, SetupStyle.BARRAGE, 24);
			final Map<Integer, Long> runes = new HashMap<>();
			runes.put(DEATH_RUNE, 440L);
			runes.put(BLOOD_RUNE, 220L);
			barrage.setSupplies(runes);
			records.add(barrage);
		}
		records.add(task(SMOKE_ID, true, SetupStyle.MELEE, 41));
		records.add(task(SMOKE_ID, true, SetupStyle.MELEE, 41));
		return records;
	}

	private static TaskPlan plan(int kills, List<LedgerRecord> records)
	{
		return TaskPlanner.plan(TaskPlanner.Request.builder().creatureId(CREATURE).task(TASK)
			.taskType(TaskType.REGULAR).killsLeft(kills).records(records).prices(PRICES).spots(TABLE).build());
	}

	private static String line(TaskPlan plan, Map<Integer, String> names)
	{
		return TaskPlanText.chatLine(TASK, plan, TABLE, NO_REGION_NAMES, names::get);
	}

	@Test
	public void theLineNamesTheRecommendationItsBringItemsAndTheCounterpart()
	{
		final String line = line(plan(142, example()), NAMES);
		assertEquals("Dust devils x142: barrage, Catacombs, ~24 min; ~440 death, ~220 blood (3 tasks). "
			+ "Cannon, Smoke Dungeon: ~41 min (2).", line);
		assertTrue(line.length() <= TaskPlanText.MAX_CHAT_LENGTH);
	}

	@Test
	public void whenItDoesntFitTheCounterpartIsKeptLongest()
	{
		final TaskPlan plan = plan(142, example());
		// "Dust devils x142: barrage, Catacombs, ~24 min (3 tasks)." is 56 characters, the counterpart 36, each item
		// 7 and its name. Names ending in "s" stay as they are.
		final String twenty = "Aaaaaaaaaaaaaaaaaaas";
		final String thirty = "Bbbbbbbbbbbbbbbbbbbbbbbbbbbbbs";

		// 1. Everything (the spec's example, above). 2. The first item and the counterpart.
		final String firstAndCounterpart = line(plan, names("Death rune", twenty));
		assertEquals("Dust devils x142: barrage, Catacombs, ~24 min; ~440 death (3 tasks). "
			+ "Cannon, Smoke Dungeon: ~41 min (2).", firstAndCounterpart);

		// 3. The counterpart alone, before an item alone.
		assertEquals("Dust devils x142: barrage, Catacombs, ~24 min (3 tasks). Cannon, Smoke Dungeon: ~41 min (2).",
			line(plan, names(thirty, "Blood rune")));

		// 4. With no room for the counterpart, the first item alone; 5. else neither. 45 characters of task name.
		final String longTask = "A task name made long to test the chat budget";
		final String firstOnly = TaskPlanText.chatLine(longTask, plan, TABLE, NO_REGION_NAMES,
			names("Death rune", "Blood rune")::get);
		assertEquals(longTask + " x142: barrage, Catacombs, ~24 min; ~440 death (3 tasks).", firstOnly);
		assertEquals(longTask + " x142: barrage, Catacombs, ~24 min (3 tasks).",
			TaskPlanText.chatLine(longTask, plan, TABLE, NO_REGION_NAMES, names(thirty, "Blood rune")::get));
		for (String line : new String[]{firstAndCounterpart, firstOnly})
		{
			assertTrue(line, line.length() <= TaskPlanText.MAX_CHAT_LENGTH);
		}
	}

	@Test
	public void aCounterpartAtTheSameSpotLeavesTheSpotOut()
	{
		// The owner's example: a migrated cannon task and a task without a cannon, both in the Stronghold Slayer Cave.
		final LedgerRecord cannon = task(STRONGHOLD_ID, true, SetupStyle.MELEE, 32);
		SetupTags.estimated(true, SetupStyle.MELEE).copyTo(cannon);
		cannon.setSupplies(Collections.singletonMap(SupplyTracker.CANNONBALL, 433L));
		final LedgerRecord without = task(STRONGHOLD_ID, false, SetupStyle.MELEE, 46);
		for (LedgerRecord record : new LedgerRecord[]{cannon, without})
		{
			record.setMonster("Aberrant spectres");
			record.setKills(150);
		}
		final TaskPlan plan = plan(150, Arrays.asList(cannon, without));
		final String line = TaskPlanText.chatLine("Aberrant spectres", plan, TABLE, NO_REGION_NAMES,
			Collections.singletonMap(SupplyTracker.CANNONBALL, "Cannonball")::get);
		assertEquals("Aberrant spectres x150: ~cannon, ~melee, Stronghold Cave, ~32 min; ~433 cannonballs (1 task). "
			+ "No cannon: ~46 min (1).", line);
		assertTrue(line.length() <= TaskPlanText.MAX_CHAT_LENGTH);
	}

	@Test
	public void withTheKillsLeftUnknownTheLineGivesThePace()
	{
		final List<LedgerRecord> records = new ArrayList<>();
		for (int i = 0; i < 3; i++)
		{
			// 28.4 minutes for 142 kills: 12 seconds a kill.
			records.add(task(CATACOMBS_ID, false, SetupStyle.BARRAGE, 28.4));
		}
		assertEquals("Dust devils: barrage, Catacombs, ~0:12/kill (3 tasks).", line(plan(-1, records), NAMES));
		assertEquals("no kills left: the same", "Dust devils: barrage, Catacombs, ~0:12/kill (3 tasks).",
			line(plan(0, records), NAMES));

		records.add(task(SMOKE_ID, true, SetupStyle.MELEE, 41));
		assertEquals("Dust devils: barrage, Catacombs, ~0:12/kill (3 tasks). Cannon, Smoke Dungeon: ~0:17/kill (1).",
			line(plan(SessionTracker.UNKNOWN_AMOUNT, records), NAMES));
		assertEquals("Dust devils: no completed tasks yet.", line(plan(-1, Collections.emptyList()), NAMES));
		final List<LedgerRecord> unknown = Collections.singletonList(task(SMOKE_ID, true, SetupStyle.UNKNOWN, 28.4));
		assertEquals("Dust devils: ~0:12/kill (1 task, setup unknown).", line(plan(-1, unknown), NAMES));
	}

	@Test
	public void paceIsInMinutesAndSecondsAKill()
	{
		assertEquals("~0:12/kill", TaskPlanText.estimatedPace(12_000));
		assertEquals("~0:01/kill", TaskPlanText.estimatedPace(0));
		assertEquals("~0:01/kill", TaskPlanText.estimatedPace(499));
		assertEquals("~1:00/kill", TaskPlanText.estimatedPace(59_500));
		assertEquals("~12:34/kill", TaskPlanText.estimatedPace((12 * 60 + 34) * 1_000L));
		assertEquals("~1:02:05/kill", TaskPlanText.estimatedPace(3_725_000));
	}

	private static Map<Integer, String> names(String death, String blood)
	{
		final Map<Integer, String> names = new HashMap<>();
		names.put(DEATH_RUNE, death);
		names.put(BLOOD_RUNE, blood);
		return names;
	}

	@Test
	public void theChatMessageHighlightsTheHeadAndEscapesThePlainText()
	{
		final TaskPlan plan = plan(142, example());
		assertEquals("Dust devils x142:", TaskPlanText.chatHead(TASK, plan));
		assertEquals("Dust devils:", TaskPlanText.chatHead(TASK, plan(-1, example())));
		assertEquals("<colHIGHLIGHT>Dust devils x142:<colNORMAL> barrage, Catacombs, ~24 min; ~440 death, ~220 blood"
			+ " (3 tasks). Cannon, Smoke Dungeon: ~41 min (2).",
			TaskPlanText.chatMessage(TASK, plan, TABLE, NO_REGION_NAMES, NAMES::get));

		// Text is escaped once, by ChatMessageBuilder: a name with a tag shows as typed.
		final Map<Integer, String> tagged = names("<b>Death rune", "Blood rune");
		assertEquals("<colHIGHLIGHT>Dust devils x142:<colNORMAL> barrage, Catacombs, ~24 min; ~440 <lt>b<gt>death,"
			+ " ~220 blood (3 tasks). Cannon, Smoke Dungeon: ~41 min (2).",
			TaskPlanText.chatMessage(TASK, plan, TABLE, NO_REGION_NAMES, tagged::get));
		assertEquals("<colHIGHLIGHT><lt>col=ff0000<gt>Dust devils x142:<colNORMAL> no completed tasks yet.",
			TaskPlanText.chatMessage("<col=ff0000>Dust devils", plan(142, Collections.emptyList()), TABLE,
				NO_REGION_NAMES, NAMES::get));
	}

	@Test
	public void withNoHistoryItSaysSo()
	{
		assertEquals("Dust devils x142: no completed tasks yet.",
			line(plan(142, Collections.emptyList()), NAMES));
		assertEquals("No completed Dust devils tasks yet, so no estimate.", TaskPlanText.noHistory(TASK));
	}

	/** {@code records}' plan as a Wilderness task's (Krystilia's) with {@code kills} left. */
	private static TaskPlan wildernessPlan(int kills, List<LedgerRecord> records)
	{
		return TaskPlanner.plan(TaskPlanner.Request.builder().creatureId(CREATURE).task(TASK)
			.taskType(TaskType.WILDERNESS).killsLeft(kills).records(records).prices(PRICES).spots(TABLE).build());
	}

	@Test
	public void aWildernessTaskWithOnlyOtherTasksSaysNoneWereInTheWilderness()
	{
		// Tasks from other masters only: a Wilderness task uses none of them.
		final String line = line(wildernessPlan(1_412, example()), NAMES);
		assertEquals("Dust devils x1,412: no completed Wilderness tasks yet.", line);
		assertTrue(line.length() <= TaskPlanText.MAX_CHAT_LENGTH);
		assertEquals("Dust devils: no completed Wilderness tasks yet.", line(wildernessPlan(-1, example()), NAMES));

		// No task of the creature at all: no completed tasks, whatever the master.
		assertEquals("Dust devils x142: no completed tasks yet.",
			line(wildernessPlan(142, Collections.emptyList()), NAMES));

		// With a Wilderness task, its plan as usual.
		final LedgerRecord krystilia = task(SMOKE_ID, false, SetupStyle.MELEE, 30);
		krystilia.setSlayerMaster(SlayerMaster.KRYSTILIA);
		final List<LedgerRecord> records = new ArrayList<>(example());
		records.add(krystilia);
		assertEquals("Dust devils x142: melee, Smoke Dungeon, ~30 min (1 task).",
			line(wildernessPlan(142, records), NAMES));
	}

	@Test
	public void aThinOrEstimatedOrCannonRecommendationAndUnknownSetups()
	{
		final LedgerRecord one = task(SMOKE_ID, true, SetupStyle.RANGED, 30);
		one.setStyleEstimated(true);
		assertEquals("Dust devils x1,412: cannon, ~ranged, Smoke Dungeon, ~4 h 58 min (1 task).",
			line(plan(1_412, Collections.singletonList(one)), NAMES));

		final List<LedgerRecord> unknown = new ArrayList<>();
		for (double minutes : new double[]{30, 40, 50})
		{
			unknown.add(task(SMOKE_ID, true, SetupStyle.UNKNOWN, minutes));
		}
		assertEquals("Dust devils x142: ~40 min (3 tasks, setup unknown).", line(plan(142, unknown), NAMES));

		// An unlisted region the player named: its name, cut to a short name's length.
		final LedgerRecord there = task(SlayerSpots.regionKey(UNLISTED), false, SetupStyle.MELEE, 20);
		final String named = TaskPlanText.chatLine(TASK, plan(142, Collections.singletonList(there)), TABLE,
			region -> region == UNLISTED ? "Edgeville dungeon, west side" : null, NAMES::get);
		assertEquals("Dust devils x142: melee, Edgeville dunge, ~20 min (1 task).", named);
		assertFalse(named.contains("~ "));
	}

	@Test
	public void estimatedTimesAreInMinutesThenHoursAndMinutes()
	{
		assertEquals("~1 min", TaskPlanText.estimatedTime(0));
		assertEquals("~1 min", TaskPlanText.estimatedTime(29_000));
		assertEquals("~24 min", TaskPlanText.estimatedTime(24 * MINUTE));
		assertEquals("~24 min", TaskPlanText.estimatedTime(24 * MINUTE + 29_999));
		assertEquals("~25 min", TaskPlanText.estimatedTime(24 * MINUTE + 30_000));
		assertEquals("~59 min", TaskPlanText.estimatedTime(59 * MINUTE));
		assertEquals("~1 h", TaskPlanText.estimatedTime(60 * MINUTE));
		assertEquals("~1 h 5 min", TaskPlanText.estimatedTime(65 * MINUTE));
		assertEquals("~2 h", TaskPlanText.estimatedTime(120 * MINUTE));
	}

	@Test
	public void itemsAreNamedForTheLine()
	{
		final Map<Integer, String> names = new HashMap<>();
		names.put(1, "Death rune");
		names.put(2, "Prayer potion(4)");
		names.put(3, "Ring of dueling(8)");
		names.put(4, "Teleport to house");
		names.put(5, "Anglerfish");
		names.put(6, "Zulrah's scales");
		names.put(7, "Cannonball");
		names.put(8, "Ruby dragon bolts (e)");
		names.put(9, "Tuna potato");
		names.put(10, "Super restore(4)");
		names.put(12, "Curry");
		names.put(13, "Potato with cheese");
		names.put(14, "Cooked turkey");
		names.put(15, "Jug of wine");
		final ItemDetails.ItemNames lookup = names::get;
		assertEquals("death", TaskPlanText.chatItemName(1, 440, lookup));
		assertEquals("prayer potions", TaskPlanText.chatItemName(2, 6, lookup));
		assertEquals("prayer potion", TaskPlanText.chatItemName(2, 1, lookup));
		assertEquals("rings of dueling", TaskPlanText.chatItemName(3, 2, lookup));
		assertEquals("teleports to house", TaskPlanText.chatItemName(4, 3, lookup));
		assertEquals("anglerfish", TaskPlanText.chatItemName(5, 20, lookup));
		assertEquals("zulrah's scales", TaskPlanText.chatItemName(6, 900, lookup));
		assertEquals("cannonballs", TaskPlanText.chatItemName(7, 1_050, lookup));
		assertEquals("ruby dragon bolts (e)", TaskPlanText.chatItemName(8, 50, lookup));
		assertEquals("tuna potatoes", TaskPlanText.chatItemName(9, 2, lookup));
		assertEquals("super restores", TaskPlanText.chatItemName(10, 4, lookup));
		assertEquals("a name not known yet", "item 11", TaskPlanText.chatItemName(11, 4, lookup));
		assertEquals("a consonant and y", "curries", TaskPlanText.chatItemName(12, 3, lookup));
		assertEquals("curry", TaskPlanText.chatItemName(12, 1, lookup));
		assertEquals("X with Y", "potatoes with cheese", TaskPlanText.chatItemName(13, 5, lookup));
		assertEquals("a vowel and y", "cooked turkeys", TaskPlanText.chatItemName(14, 2, lookup));
		assertEquals("jugs of wine", TaskPlanText.chatItemName(15, 2, lookup));
	}

	// ---- The advisor card ----

	/** A plan's option built directly: {@code tasks} tasks at {@code spot} taking {@code minutes} each. */
	private static TaskPlan.Option option(String spot, boolean cannon, SetupStyle style, int tasks, long minutes)
	{
		return TaskPlan.Option.builder().spot(spot).cannon(cannon).style(style).tasks(tasks)
			.time(new TaskPlan.Time(minutes * MINUTE / 142, minutes * MINUTE, minutes * MINUTE, minutes * MINUTE))
			.bring(Collections.emptyList()).build();
	}

	@Test
	public void theCardsTitleSaysWhatItIsBasedOn()
	{
		assertEquals("Based on 6 Dust devils tasks", TaskPlanText.cardTitle(TASK, 6));
		assertEquals("Based on 1 Dust devils task", TaskPlanText.cardTitle(TASK, 1));
		assertEquals("Based on 3 tasks", TaskPlanText.cardTitle(null, 3));
	}

	@Test
	public void theCardsNoHistoryKonarAndSetupUnknownLines()
	{
		assertEquals("No completed Dust devils tasks yet, so no estimate.",
			TaskPlanText.noHistoryLine(TASK, plan(142, Collections.emptyList())));
		assertEquals("No completed Wilderness Dust devils tasks yet, so no estimate.",
			TaskPlanText.noHistoryLine(TASK, wildernessPlan(142, example())));
		assertEquals("No completed Wilderness tasks yet, so no estimate.",
			TaskPlanText.noHistoryLine(null, wildernessPlan(142, example())));
		assertEquals("No completed tasks in Catacombs yet, so all areas are used.", TaskPlanText.areaFallback(
			TaskPlanText.shortSpotName(CATACOMBS_ID, TABLE, NO_REGION_NAMES)));
		assertEquals("No completed tasks in the assigned area yet, so all areas are used.",
			TaskPlanText.areaFallback(null));
		assertEquals("Setup unknown for these tasks.", TaskPlanText.SETUP_UNKNOWN);
	}

	@Test
	public void anOptionIsNamedBySetupAndSpotWithItsSampleSize()
	{
		final TaskPlan plan = plan(142, example());
		final TaskPlan.Option best = plan.getRecommendation();
		final TaskPlan.Option other = plan.getCounterpart();
		assertEquals("Barrage, Catacombs", TaskPlanText.optionName(best, TASK, TABLE, NO_REGION_NAMES));
		assertEquals("3 tasks", TaskPlanText.optionTasks(best));
		assertEquals("Cannon, melee, Smoke Dungeon", TaskPlanText.optionName(other, TASK, TABLE, NO_REGION_NAMES));
		assertEquals("2 tasks", TaskPlanText.optionTasks(other));
		assertEquals("Without cannon", TaskPlanText.counterpartLabel(best));
		assertEquals("With cannon", TaskPlanText.counterpartLabel(other));

		// Thin data: one task. An estimated cannon or style (a migrated task) says so beside the sample size, and the
		// name has no "~".
		final LedgerRecord one = task(SMOKE_ID, true, SetupStyle.RANGED, 30);
		one.setStyleEstimated(true);
		final TaskPlan thinPlan = plan(142, Collections.singletonList(one));
		final TaskPlan.Option thin = thinPlan.getRecommendation();
		assertEquals("1 task, estimated", TaskPlanText.optionTasks(thin));
		// As a thin recommendation (V2 plan 6): what it is based on.
		assertTrue(thinPlan.isRecommendationThin());
		assertEquals("based on 1 task, estimated", TaskPlanText.optionTasks(thin, true));
		assertEquals("based on 3 tasks", TaskPlanText.optionTasks(best, true));
		assertEquals("Cannon, ranged, Smoke Dungeon (based on 1 task, estimated)",
			TaskPlanText.collapsedTooltip(thinPlan, TASK, TABLE, NO_REGION_NAMES).get(0));
		assertEquals("Cannon, ranged, Smoke Dungeon", TaskPlanText.optionName(thin, TASK, TABLE, NO_REGION_NAMES));

		// Done on the task's Slayer boss: its name first.
		final TaskPlan.Option boss = TaskPlan.Option.builder().spot(TestSpots.TOWER_ID).boss(true).cannon(false)
			.style(SetupStyle.MELEE).tasks(2).time(new TaskPlan.Time(1, 1, 1, 1)).bring(Collections.emptyList())
			.build();
		assertEquals("Grotesque Guardians, melee, Slayer Tower",
			TaskPlanText.optionName(boss, "Gargoyles", TABLE, NO_REGION_NAMES));
		assertEquals("Boss, melee, Slayer Tower", TaskPlanText.optionName(boss, null, TABLE, NO_REGION_NAMES));
		// An unlisted region the player named.
		assertEquals("Melee, Edgeville", TaskPlanText.optionName(option(SlayerSpots.regionKey(UNLISTED), false,
			SetupStyle.MELEE, 1, 20), TASK, TABLE, region -> "Edgeville"));
	}

	@Test
	public void theRangeIsInWholeMinutesOrPacesAndLeftOutWhenItIsOneValue()
	{
		assertEquals("54 to 58 min", TaskPlanText.timeRange(new TaskPlan.Time(0, 0, 54 * MINUTE, 58 * MINUTE),
			false));
		assertEquals("from an hour, hours and minutes (DESIGN_PRINCIPLES.md 5.5)", "54 min to 1 h 6 min",
			TaskPlanText.timeRange(new TaskPlan.Time(0, 0, 54 * MINUTE, 66 * MINUTE), false));
		assertEquals("1 h to 2 h", TaskPlanText.timeRange(new TaskPlan.Time(0, 0, 60 * MINUTE, 120 * MINUTE), false));
		assertEquals("one task", null, TaskPlanText.timeRange(new TaskPlan.Time(0, 60 * MINUTE, 60 * MINUTE,
			60 * MINUTE), false));
		assertEquals("the same whole minute", null, TaskPlanText.timeRange(new TaskPlan.Time(0, 0, 60 * MINUTE,
			60 * MINUTE + 20_000), false));
		assertEquals("0:10 to 0:14/kill", TaskPlanText.timeRange(new TaskPlan.Time(12_000, 0, 10_000, 14_000), true));
		assertEquals(null, TaskPlanText.timeRange(new TaskPlan.Time(12_000, 0, 12_000, 12_000), true));
		assertEquals("~1 h", TaskPlanText.time(new TaskPlan.Time(12_000, 60 * MINUTE, 0, 0), false));
		assertEquals("~0:12/kill", TaskPlanText.time(new TaskPlan.Time(12_000, 60 * MINUTE, 0, 0), true));
	}

	@Test
	public void theCardsRowsAndLines()
	{
		final TaskPlan plan = plan(142, example());
		assertEquals("Supplies", TaskPlanText.suppliesLabel(false));
		assertEquals("Supplies/kill", TaskPlanText.suppliesLabel(true));
		assertEquals("-", TaskPlanText.gear(plan.getRecommendation()));
		assertEquals("mostly Prayer", TaskPlanText.gear(TaskPlan.Option.builder().spot(SMOKE_ID)
			.style(SetupStyle.MELEE).gear(GearFocus.PRAYER).tasks(1).time(new TaskPlan.Time(1, 1, 1, 1))
			.bring(Collections.emptyList()).build()));
		assertEquals("+2 more", TaskPlanText.bringMore(2));
		assertEquals(Arrays.asList("Prayer potion(4)", "Bring: 1,206", "GE value: 10,854,000 gp"),
			TaskPlanText.bringTooltip("Prayer potion(4)", 1_206, 10_854_000L));
		assertEquals(Arrays.asList("Death rune", "Bring: 440"), TaskPlanText.bringTooltip("Death rune", 440, null));
		// "+N more"'s tooltip: an item a line, worded as its icon's tooltip.
		assertEquals("Soul rune, Bring: 20, GE value: 3,000 gp", TaskPlanText.bringMoreLine("Soul rune", 20, 3_000L));
		assertEquals("Death rune, Bring: 440", TaskPlanText.bringMoreLine("Death rune", 440, null));
		assertEquals("All setups (5 tasks)", TaskPlanText.allSetups(plan.getAllSetups()));
		assertEquals("All setups (1 task)", TaskPlanText.allSetups(new TaskPlan.Summary(1, null)));
		assertEquals("More setups (2)", TaskPlanText.moreSetups(2, false));
		assertEquals("Hide more setups", TaskPlanText.moreSetups(2, true));
		final TaskPlan.Option slow = option(STRONGHOLD_ID, false, SetupStyle.MELEE, 2, 82);
		assertEquals("~1 h 22 min (2 tasks)", TaskPlanText.moreSetupValue(slow, false));
		assertEquals("~0:35/kill (2 tasks)", TaskPlanText.moreSetupValue(slow, true));
	}

	@Test
	public void theCollapsedLineNamesTheSetupTimeAndSampleSize()
	{
		final TaskPlan plan = plan(142, example());
		assertEquals("Plan: barrage, ~24 min (3 tasks)", TaskPlanText.collapsedLine(plan));
		assertEquals(Arrays.asList("Barrage, Catacombs (3 tasks)", "~24 min",
			"With cannon: Cannon, melee, Smoke Dungeon, ~41 min (2 tasks)"),
			TaskPlanText.collapsedTooltip(plan, TASK, TABLE, NO_REGION_NAMES));
		assertEquals("Plan: barrage, ~0:10/kill (3 tasks)", TaskPlanText.collapsedLine(plan(-1, example())));
		assertEquals("Plan: no completed tasks yet", TaskPlanText.collapsedLine(plan(142, Collections.emptyList())));
		assertEquals("Plan: no completed Wilderness tasks yet",
			TaskPlanText.collapsedLine(wildernessPlan(142, example())));
		final List<LedgerRecord> unknown = new ArrayList<>();
		for (double minutes : new double[]{30, 40, 50})
		{
			unknown.add(task(SMOKE_ID, true, SetupStyle.UNKNOWN, minutes));
		}
		final TaskPlan noOption = plan(142, unknown);
		assertEquals("Plan: ~40 min (3 tasks, setup unknown)", TaskPlanText.collapsedLine(noOption));
		assertTrue(TaskPlanText.collapsedTooltip(noOption, TASK, TABLE, NO_REGION_NAMES).isEmpty());
	}

	@Test
	public void theCardsSentencesAreBrokenIntoLinesThatFit()
	{
		// One pixel a character, for the test.
		final ToIntFunction<String> measure = String::length;
		assertEquals(Arrays.asList("No completed Dust", "devils tasks yet, so", "no estimate."),
			TaskPlanText.wrap("No completed Dust devils tasks yet, so no estimate.", 20, measure));
		assertEquals("a word too long has a line of its own", Arrays.asList("a", "Hellhounds-and-more", "b"),
			TaskPlanText.wrap("a Hellhounds-and-more b", 5, measure));
		assertEquals(Collections.singletonList("a b"), TaskPlanText.wrap("  a   b ", 10, measure));
	}

	@Test
	public void theTitleAndOptionNamesTakeAtMostTwoLinesTheFirstBesideTheSampleSize()
	{
		final ToIntFunction<String> measure = String::length;
		// The first line has the room the sample size leaves (12), the second the full width (20).
		assertEquals(Arrays.asList("Cannon,", "melee, Smoke Dungeon"),
			TaskPlanText.wrap("Cannon, melee, Smoke Dungeon", 12, 20, 2, measure));
		assertEquals(Arrays.asList("Cannon, melee,", "Smoke Dungeon"),
			TaskPlanText.wrap("Cannon, melee, Smoke Dungeon", 14, 20, 2, measure));
		assertEquals("fits one line", Collections.singletonList("Barrage, Catacombs"),
			TaskPlanText.wrap("Barrage, Catacombs", 18, 20, 2, measure));
		// Past two lines the second holds the rest, which the card cuts short (FitLabel), the whole in the tooltip.
		assertEquals(Arrays.asList("Based on 3 Abyssal", "demons with a name made long tasks"),
			TaskPlanText.wrap("Based on 3 Abyssal demons with a name made long tasks", 20, 20, 2, measure));
		assertEquals("a first word too long keeps its line", Arrays.asList("Hellhounds-and-more", "b c"),
			TaskPlanText.wrap("Hellhounds-and-more b c", 5, 5, 2, measure));
		assertEquals(Collections.singletonList("a b c"), TaskPlanText.wrap("a b c", 1, 1, 1, measure));
	}

	@Test
	public void theBadgeIsSkipOrBlockWithItsSampleSizeAndDoAddsNothing()
	{
		final TaskAdvisor.Result skip = TaskAdvisor.Result.builder().advice(TaskAdvisor.Advice.CONSIDER_SKIP)
			.metric(TaskRanking.Metric.XP).ratePerHour(12_000).position(1).monsters(6).tasks(6).build();
		assertEquals("Consider skip (n=6)", TaskPlanText.badge(skip));
		final TaskAdvisor.Result block = TaskAdvisor.Result.builder().advice(TaskAdvisor.Advice.CONSIDER_BLOCK)
			.metric(TaskRanking.Metric.XP).ratePerHour(12_000).position(1).monsters(24).tasks(6).boss(true).build();
		assertEquals("Consider block (n=6)", TaskPlanText.badge(block));
		assertEquals(null, TaskPlanText.badge(TaskAdvisor.Result.builder().advice(TaskAdvisor.Advice.DO)
			.metric(TaskRanking.Metric.XP).tasks(6).build()));
		assertEquals(null, TaskPlanText.badge(null));

		final List<String> reason = TaskPlanText.badgeReason(skip, LootPriceMode.GE);
		assertEquals(3, reason.size());
		assertEquals(skip.reason(LootPriceMode.GE), reason.get(0));
		assertEquals("From 6 completed tasks of this monster (n), Catacombs variant and cannon tasks pooled with their"
			+ " task. Advice only.", reason.get(1));
		assertEquals("Counts monsters with 3+ tasks and 30+ min; Consider block needs n of 5+. Boss tasks are ranked"
			+ " separately.", reason.get(2));
		assertTrue(TaskPlanText.badgeReason(block, LootPriceMode.GE).get(2)
			.endsWith("Bosses rank only against bosses."));
	}

	@Test
	public void spotsGoByTheirShortNames()
	{
		assertEquals("Catacombs", TaskPlanText.shortSpotName(CATACOMBS_ID, TABLE, NO_REGION_NAMES));
		assertEquals("Region 12342", TaskPlanText.shortSpotName(SlayerSpots.regionKey(UNLISTED), TABLE,
			NO_REGION_NAMES));
		assertEquals("Edgeville", TaskPlanText.shortSpotName(SlayerSpots.regionKey(UNLISTED), TABLE,
			region -> "Edgeville"));
		assertEquals("a listed spot keeps the table's", "Catacombs",
			TaskPlanText.shortSpotName(CATACOMBS_ID, TABLE, region -> "Mine"));
		assertEquals("an id the table doesn't know", "old-spot",
			TaskPlanText.shortSpotName("old-spot", TABLE, NO_REGION_NAMES));
	}
}
