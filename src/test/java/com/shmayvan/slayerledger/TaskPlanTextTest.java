package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.TestSpots.CATACOMBS_ID;
import static com.shmayvan.slayerledger.TestSpots.SMOKE_ID;
import static com.shmayvan.slayerledger.TestSpots.TABLE;
import static com.shmayvan.slayerledger.TestSpots.UNLISTED;
import static org.junit.Assert.assertEquals;
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
 * The advisor's words: estimated times and paces and how spots are named ({@code DESIGN_PRINCIPLES.md}
 * section 5); and the advisor card's text: its title, option names, sample sizes and times, the
 * Konar, Wilderness and setup-unknown notes and the Bring tooltips.
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

	static
	{
		PRICES.put(DEATH_RUNE, 300L);
		PRICES.put(BLOOD_RUNE, 400L);
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

	@Test
	public void anOptionsTimeIsForTheKillsLeftOrAPaceWithThemUnknown()
	{
		final List<LedgerRecord> records = new ArrayList<>();
		for (int i = 0; i < 3; i++)
		{
			// 28.4 minutes for 142 kills: 12 seconds a kill.
			records.add(task(CATACOMBS_ID, false, SetupStyle.BARRAGE, 28.4));
		}
		assertEquals("~28 min", TaskPlanText.time(plan(142, records).getRecommendation().getTime(), false));
		assertEquals("~0:12/kill", TaskPlanText.time(plan(-1, records).getRecommendation().getTime(), true));
		assertEquals("~1 h", TaskPlanText.time(new TaskPlan.Time(12_000, 60 * MINUTE), false));
		assertEquals("~0:12/kill", TaskPlanText.time(new TaskPlan.Time(12_000, 60 * MINUTE), true));
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

	/** {@code records}' plan as a Wilderness task's (Krystilia's) with {@code kills} left. */
	private static TaskPlan wildernessPlan(int kills, List<LedgerRecord> records)
	{
		return TaskPlanner.plan(TaskPlanner.Request.builder().creatureId(CREATURE).task(TASK)
			.taskType(TaskType.WILDERNESS).killsLeft(kills).records(records).prices(PRICES).spots(TABLE).build());
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

	// ---- The advisor card ----

	/** A plan's option built directly: {@code tasks} tasks at {@code spot} taking {@code minutes} each. */
	private static TaskPlan.Option option(String spot, boolean cannon, SetupStyle style, int tasks, long minutes)
	{
		return TaskPlan.Option.builder().spot(spot).cannon(cannon).style(style).tasks(tasks)
			.time(new TaskPlan.Time(minutes * MINUTE / 142, minutes * MINUTE))
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
		final TaskPlan.Option other = plan.getOptions().get(1);
		assertEquals("Barrage, Catacombs", TaskPlanText.optionName(best, TASK, TABLE, NO_REGION_NAMES));
		assertEquals("3 tasks", TaskPlanText.optionTasks(best, false));
		assertEquals("Cannon, melee, Smoke Dungeon", TaskPlanText.optionName(other, TASK, TABLE, NO_REGION_NAMES));
		assertEquals("2 tasks", TaskPlanText.optionTasks(other, false));

		// Thin data: one task. An estimated cannon or style (a migrated task) says so beside the sample size, and the
		// name has no "~".
		final LedgerRecord one = task(SMOKE_ID, true, SetupStyle.RANGED, 30);
		one.setStyleEstimated(true);
		final TaskPlan thinPlan = plan(142, Collections.singletonList(one));
		final TaskPlan.Option thin = thinPlan.getRecommendation();
		assertEquals("1 task, estimated", TaskPlanText.optionTasks(thin, false));
		// As a thin recommendation (V2 plan 6): what it is based on.
		assertTrue(thinPlan.isRecommendationThin());
		assertEquals("based on 1 task, estimated", TaskPlanText.optionTasks(thin, true));
		assertEquals("based on 3 tasks", TaskPlanText.optionTasks(best, true));
		assertEquals("Cannon, ranged, Smoke Dungeon", TaskPlanText.optionName(thin, TASK, TABLE, NO_REGION_NAMES));

		// Done on the task's Slayer boss: its name first.
		final TaskPlan.Option boss = TaskPlan.Option.builder().spot(TestSpots.TOWER_ID).boss(true).cannon(false)
			.style(SetupStyle.MELEE).tasks(2).time(new TaskPlan.Time(1, 1)).bring(Collections.emptyList())
			.build();
		assertEquals("Grotesque Guardians, melee, Slayer Tower",
			TaskPlanText.optionName(boss, "Gargoyles", TABLE, NO_REGION_NAMES));
		assertEquals("Boss, melee, Slayer Tower", TaskPlanText.optionName(boss, null, TABLE, NO_REGION_NAMES));
		// An unlisted region the player named.
		assertEquals("Melee, Edgeville", TaskPlanText.optionName(option(SlayerSpots.regionKey(UNLISTED), false,
			SetupStyle.MELEE, 1, 20), TASK, TABLE, region -> "Edgeville"));
	}

	@Test
	public void theCardsRowsAndLines()
	{
		final TaskPlan plan = plan(142, example());
		assertEquals("+2 more", TaskPlanText.bringMore(2));
		assertEquals(Arrays.asList("Prayer potion(4)", "Bring: 1,206", "GE value: 10,854,000 gp"),
			TaskPlanText.bringTooltip("Prayer potion(4)", 1_206, 10_854_000L));
		assertEquals(Arrays.asList("Death rune", "Bring: 440"), TaskPlanText.bringTooltip("Death rune", 440, null));
		// "+N more"'s tooltip: an item a line, worded as its icon's tooltip.
		assertEquals("Soul rune, Bring: 20, GE value: 3,000 gp", TaskPlanText.bringMoreLine("Soul rune", 20, 3_000L));
		assertEquals("Death rune, Bring: 440", TaskPlanText.bringMoreLine("Death rune", 440, null));
		assertEquals("All setups (5 tasks)", TaskPlanText.allSetups(plan.getAllSetups()));
		assertEquals("All setups (1 task)", TaskPlanText.allSetups(new TaskPlan.Summary(1, null)));
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
