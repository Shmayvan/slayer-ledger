package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.TestSpots.CATACOMBS_ID;
import static com.shmayvan.slayerledger.TestSpots.SMOKE;
import static com.shmayvan.slayerledger.TestSpots.SMOKE_ID;
import static com.shmayvan.slayerledger.TestSpots.STRONGHOLD_ID;
import static com.shmayvan.slayerledger.TestSpots.TABLE;
import static com.shmayvan.slayerledger.TestSpots.TOWER_ID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * The advisor's planner (V2 plan 5 and 6) on synthetic records: which tasks count, the options and their
 * grouping (estimated values, Unknown spot or style, the boss variant), the n rules of the recommendation and its
 * counterpart, the time percentiles, what to bring and the three metrics with their ties.
 */
public class TaskPlannerTest
{
	private static final int CREATURE = 42;
	private static final String TASK = "Dust devils";
	private static final long MINUTE = 60_000L;
	private static final int COINS = ItemValuation.COINS;
	private static final int SUPPLY = 9_000;
	private static final int CANNONBALL = 2;
	private static final int DEATH_RUNE = 560;
	private static final int BLOOD_RUNE = 565;
	/** Prayer potion(4): counted in doses, 4 to a potion. */
	private static final int PRAYER_POTION = 2_434;
	/** Ring of dueling(8): counted in charges, 8 to a ring. */
	private static final int DUELING_RING = 2_552;
	private static final Map<Integer, Long> PRICES = prices();

	private static Map<Integer, Long> prices()
	{
		final Map<Integer, Long> prices = new HashMap<>();
		prices.put(COINS, 1L);
		prices.put(SUPPLY, 100L);
		prices.put(CANNONBALL, 200L);
		prices.put(DEATH_RUNE, 300L);
		prices.put(BLOOD_RUNE, 400L);
		prices.put(PRAYER_POTION, 8_000L);
		return Collections.unmodifiableMap(prices);
	}

	private static int ids;

	/**
	 * A completed Dust devils task of {@code kills} kills in {@code minutes} minutes at the spot {@code spot}
	 * ({@code null}: none), with the detected cannon and style and an Unknown gear focus.
	 */
	private static LedgerRecord task(String spot, boolean cannon, SetupStyle style, int kills, double minutes)
	{
		final LedgerRecord record = new LedgerRecord();
		record.setId("task-" + ids++);
		record.setCreatureId(CREATURE);
		record.setMonster(TASK);
		record.setKills(kills);
		record.setActiveMillis(Math.round(minutes * MINUTE));
		record.setCompleted(true);
		record.setSpotId(spot);
		SetupTags.detected(cannon, style, GearFocus.UNKNOWN, SetupOverrides.NONE).copyTo(record);
		return record;
	}

	/** {@code record}, valued: {@code loot} coins and {@code supplies} of {@link #SUPPLY}. */
	private static LedgerRecord valued(LedgerRecord record, long loot, long supplies)
	{
		record.setLoot(Collections.singletonMap(COINS, loot));
		record.setLootGeValue(loot);
		record.setLootHaValue(loot);
		if (supplies > 0)
		{
			record.setSupplies(Collections.singletonMap(SUPPLY, supplies));
		}
		record.setSuppliesGeValue(supplies * PRICES.get(SUPPLY));
		return record;
	}

	/** {@code record} with {@code supplies} (item id, amount pairs) used. */
	private static LedgerRecord using(LedgerRecord record, long... supplies)
	{
		final Map<Integer, Long> used = new HashMap<>();
		for (int i = 0; i < supplies.length; i += 2)
		{
			used.put((int) supplies[i], supplies[i + 1]);
		}
		record.setSupplies(used);
		return record;
	}

	private static List<LedgerRecord> times(int n, LedgerRecord... templates)
	{
		final List<LedgerRecord> records = new ArrayList<>();
		for (int i = 0; i < n; i++)
		{
			for (LedgerRecord template : templates)
			{
				records.add(copy(template));
			}
		}
		return records;
	}

	private static LedgerRecord copy(LedgerRecord template)
	{
		final LedgerRecord record = task(template.getSpotId(), template.getDetectedCannon(),
			template.getDetectedStyle(), template.getKills(), template.getActiveMillis() / (double) MINUTE);
		record.setSupplies(template.getSupplies());
		record.setSupplyDoses(template.getSupplyDoses());
		record.setLoot(template.getLoot());
		record.setLootGeValue(template.getLootGeValue());
		record.setLootHaValue(template.getLootHaValue());
		record.setSuppliesGeValue(template.getSuppliesGeValue());
		record.setXpGained(template.getXpGained());
		return record;
	}

	private static TaskPlan plan(int kills, AdvisorMetric metric, List<LedgerRecord> records)
	{
		return TaskPlanner.plan(TaskPlanner.Request.builder()
			.creatureId(CREATURE)
			.task(TASK)
			.taskType(TaskType.REGULAR)
			.killsLeft(kills)
			.records(records)
			.metric(metric)
			.mode(LootPriceMode.GE)
			.prices(PRICES)
			.hidden(HiddenLoot.NONE)
			.spots(TABLE)
			.build());
	}

	private static TaskPlan plan(int kills, AdvisorMetric metric, LedgerRecord... records)
	{
		return plan(kills, metric, Arrays.asList(records));
	}

	@SafeVarargs
	private static List<LedgerRecord> all(List<LedgerRecord>... groups)
	{
		final List<LedgerRecord> records = new ArrayList<>();
		for (List<LedgerRecord> group : groups)
		{
			records.addAll(group);
		}
		return records;
	}

	private static long minutes(long millis)
	{
		return Math.round(millis / (double) MINUTE);
	}

	// ---- No history ----

	@Test
	public void withNoCompletedTaskThePlanIsEmptyAndSaysSo()
	{
		final TaskPlan none = plan(142, null);
		assertFalse(none.hasHistory());
		assertNull(none.getRecommendation());
		assertNull(none.getCounterpart());
		assertTrue(none.getOptions().isEmpty());
		assertTrue(none.getMoreSetups().isEmpty());
		assertEquals(0, none.getAllSetups().getTasks());
		assertNull(none.getAllSetups().getTime());
		assertEquals("the default metric", AdvisorMetric.FASTEST_FINISH, none.getMetric());
		assertEquals("No completed Dust devils tasks yet, so no estimate.", TaskPlanText.noHistory(TASK));

		// None of these is a sample: partial, unfinished, without kills or time, or of another creature.
		final LedgerRecord partial = task(SMOKE_ID, false, SetupStyle.MELEE, 10, 10);
		partial.setPartial(true);
		final LedgerRecord unfinished = task(SMOKE_ID, false, SetupStyle.MELEE, 10, 10);
		unfinished.setCompleted(false);
		final LedgerRecord noKills = task(SMOKE_ID, false, SetupStyle.MELEE, 0, 10);
		final LedgerRecord noTime = task(SMOKE_ID, false, SetupStyle.MELEE, 10, 0);
		final LedgerRecord other = task(SMOKE_ID, false, SetupStyle.MELEE, 10, 10);
		other.setCreatureId(CREATURE + 1);
		assertFalse(plan(142, null, partial, unfinished, noKills, noTime, other).hasHistory());
	}

	@Test
	public void withTheKillsLeftUnknownThePlanIsPerKill()
	{
		// 10 kills in 20 minutes, 10 of the supply (100 gp each): 2 minutes and 100 gp a kill.
		final List<LedgerRecord> records = times(3,
			valued(task(SMOKE_ID, false, SetupStyle.MELEE, 10, 20), 5_000, 10));
		records.add(task(CATACOMBS_ID, false, SetupStyle.UNKNOWN, 10, 20));
		for (int kills : new int[]{0, SessionTracker.UNKNOWN_AMOUNT})
		{
			final TaskPlan plan = plan(kills, null, records);
			assertTrue(kills + ": history, so a plan", plan.hasHistory());
			assertFalse(plan.hasKillsLeft());
			assertEquals(4, plan.getAllSetups().getTasks());
			assertEquals(1, plan.getExcludedTasks());
			assertEquals(2 * MINUTE, plan.getAllSetups().getTime().getMillis());
			final TaskPlan.Option best = plan.getRecommendation();
			assertEquals(3, best.getTasks());
			assertEquals("for one kill, not scaled", 2 * MINUTE, best.getTime().getMillis());
			assertEquals(2 * MINUTE, best.getTime().getPerKillMillis());
			assertEquals(2 * MINUTE, best.getTime().getLowMillis());
			assertEquals(2 * MINUTE, best.getTime().getHighMillis());
			assertEquals("per kill", Long.valueOf(100), best.getSupplyCost());
			assertTrue("nothing to bring for unknown kills", best.getBring().isEmpty());
			assertEquals(0, best.getBringMore());
		}
		assertTrue(plan(10, null, records).hasKillsLeft());
	}

	// ---- The n rules ----

	@Test
	public void threeSlowerTasksBeatTwoFasterOnes()
	{
		final TaskPlan plan = plan(100, AdvisorMetric.FASTEST_FINISH, all(
			times(3, task(CATACOMBS_ID, false, SetupStyle.BARRAGE, 100, 30)),
			times(2, task(SMOKE_ID, false, SetupStyle.BARRAGE, 100, 10))));

		final TaskPlan.Option best = plan.getRecommendation();
		assertEquals(CATACOMBS_ID, best.getSpot());
		assertEquals(3, best.getTasks());
		assertFalse(plan.isRecommendationThin());
		assertEquals(30, minutes(best.getTime().getMillis()));
		assertNull("no option with a cannon", plan.getCounterpart());
		assertEquals(1, plan.getMoreSetups().size());
		assertEquals(SMOKE_ID, plan.getMoreSetups().get(0).getSpot());
		assertEquals("all options, in the metric's order", SMOKE_ID, plan.getOptions().get(0).getSpot());
	}

	@Test
	public void withNoOptionOfThreeTheMostTasksWinTiesByTheMetricAndItIsThin()
	{
		final TaskPlan plan = plan(100, AdvisorMetric.FASTEST_FINISH, all(
			times(2, task(CATACOMBS_ID, false, SetupStyle.BARRAGE, 100, 30)),
			times(1, task(SMOKE_ID, false, SetupStyle.BARRAGE, 100, 5)),
			times(2, task(TOWER_ID, false, SetupStyle.BARRAGE, 100, 20))));

		final TaskPlan.Option best = plan.getRecommendation();
		assertEquals("2 tasks each: the faster", TOWER_ID, best.getSpot());
		assertTrue(plan.isRecommendationThin());
		assertEquals("based on 2 tasks", TaskPlanText.basedOn(best.getTasks()));
		assertEquals("based on 1 task", TaskPlanText.basedOn(1));
	}

	// ---- Grouping ----

	@Test
	public void estimatedValuesGroupUnderTheirOwnValueWithTheMark()
	{
		final LedgerRecord estimated = task(CATACOMBS_ID, false, SetupStyle.BARRAGE, 100, 30);
		estimated.setStyleEstimated(true);
		final LedgerRecord detected = task(CATACOMBS_ID, false, SetupStyle.BARRAGE, 100, 30);
		// Estimated Magic, set to Barrage by the player: Barrage, not estimated.
		final LedgerRecord overridden = task(CATACOMBS_ID, false, SetupStyle.MAGIC, 100, 30);
		overridden.setStyleEstimated(true);
		overridden.setStyleOverride(SetupStyle.BARRAGE);
		final LedgerRecord estimatedCannon = task(CATACOMBS_ID, true, SetupStyle.BARRAGE, 100, 30);
		estimatedCannon.setCannonEstimated(true);

		final TaskPlan plan = plan(100, null, estimated, detected, overridden, estimatedCannon);
		assertEquals(2, plan.getOptions().size());
		final TaskPlan.Option barrage = plan.getRecommendation();
		assertFalse(barrage.isCannon());
		assertEquals(SetupStyle.BARRAGE, barrage.getStyle());
		assertEquals("~Barrage with Barrage", 3, barrage.getTasks());
		assertTrue(barrage.isStyleEstimated());
		assertFalse(barrage.isCannonEstimated());
		assertTrue(barrage.isEstimated());
		final TaskPlan.Option cannon = plan.getCounterpart();
		assertTrue("~Yes with Yes", cannon.isCannon());
		assertTrue(cannon.isCannonEstimated());
		assertTrue(cannon.isEstimated());

		assertFalse("no estimate, no mark", plan(100, null, detected, overridden).getRecommendation().isEstimated());
	}

	@Test
	public void anUnknownSpotOrStyleIsLeftOutButCountsInAllSetups()
	{
		final LedgerRecord here = task(SMOKE_ID, false, SetupStyle.MELEE, 10, 10);
		// Saved before spots: its region gives it the Smoke Dungeon.
		final LedgerRecord regionOnly = task(null, false, SetupStyle.MELEE, 10, 10);
		regionOnly.setLocationRegion(SMOKE);
		final LedgerRecord noSpot = task(null, false, SetupStyle.MELEE, 10, 30);
		final LedgerRecord noStyle = task(SMOKE_ID, false, SetupStyle.UNKNOWN, 10, 50);
		// A cannon task with only cannon kills: style Unknown, whatever its cannon.
		final LedgerRecord cannonOnly = task(SMOKE_ID, true, SetupStyle.UNKNOWN, 10, 70);

		final TaskPlan plan = plan(10, null, here, regionOnly, noSpot, noStyle, cannonOnly);
		assertEquals(1, plan.getOptions().size());
		assertEquals(2, plan.getRecommendation().getTasks());
		assertNull("the Unknown cannon task is in no option", plan.getCounterpart());
		assertEquals(5, plan.getAllSetups().getTasks());
		assertEquals(3, plan.getExcludedTasks());
		// Times per kill 1, 1, 3, 5 and 7 minutes: the median is 3.
		assertEquals(30, minutes(plan.getAllSetups().getTime().getMillis()));

		final TaskPlan unknownOnly = plan(10, null, noSpot, noStyle);
		assertTrue(unknownOnly.hasHistory());
		assertNull(unknownOnly.getRecommendation());
		assertTrue(unknownOnly.getOptions().isEmpty());
		assertEquals(2, unknownOnly.getExcludedTasks());
	}

	@Test
	public void aGrotesqueGuardiansTaskIsItsOwnOptionAndBossKillsAloneDontSplit()
	{
		final int gargoyles = 46;
		final List<LedgerRecord> records = new ArrayList<>();
		for (String variant : new String[]{null, null, "Grotesque Guardians"})
		{
			final LedgerRecord record = task(TOWER_ID, false, SetupStyle.MELEE, 50, 25);
			record.setCreatureId(gargoyles);
			record.setMonster("Gargoyles");
			record.setVariant(variant);
			records.add(record);
		}
		// A few kills of the boss: the flag, but still a gargoyle task.
		records.get(1).setBossKills(true);
		records.get(2).setBossKills(true);

		final TaskPlan plan = TaskPlanner.plan(TaskPlanner.Request.builder().creatureId(gargoyles).task("Gargoyles")
			.taskType(TaskType.REGULAR).killsLeft(50).records(records).spots(TABLE).build());
		assertEquals(2, plan.getOptions().size());
		final TaskPlan.Option standard = plan.getRecommendation();
		assertFalse(standard.isBoss());
		assertEquals(2, standard.getTasks());
		assertEquals("shown, not split", 1, standard.getBossKillTasks());
		final TaskPlan.Option boss = plan.getMoreSetups().get(0);
		assertTrue(boss.isBoss());
		assertEquals(1, boss.getTasks());
		assertEquals(1, boss.getBossKillTasks());

		assertTrue(TaskPlanner.isBossVariant(records.get(2)));
		assertFalse(TaskPlanner.isBossVariant(records.get(1)));
		final LedgerRecord deviant = task(CATACOMBS_ID, false, SetupStyle.MELEE, 10, 10);
		deviant.setMonster("Aberrant spectres");
		deviant.setVariant("Deviant spectres");
		assertFalse("a Catacombs variant is no boss: its spot keeps it apart", TaskPlanner.isBossVariant(deviant));
	}

	@Test
	public void bossTasksAreTheirOwnPool()
	{
		final int cerberus = -2 - 7;
		final LedgerRecord bossTask = task(SMOKE_ID, false, SetupStyle.MELEE, 1, 5);
		bossTask.setCreatureId(cerberus);
		bossTask.setMonster("Cerberus");
		// A task of the same name without a creature key is no boss task.
		final LedgerRecord nameOnly = task(SMOKE_ID, false, SetupStyle.MELEE, 10, 10);
		nameOnly.setCreatureId(null);
		nameOnly.setMonster("Cerberus");
		final List<LedgerRecord> records = Arrays.asList(bossTask, nameOnly);

		final TaskPlan boss = TaskPlanner.plan(TaskPlanner.Request.builder().creatureId(SessionTracker.NO_CREATURE)
			.task("Cerberus").taskType(TaskType.BOSS).killsLeft(1).records(records).spots(TABLE).build());
		assertEquals(1, boss.getAllSetups().getTasks());
		assertEquals(5, minutes(boss.getRecommendation().getTime().getMillis()));
		final TaskPlan regular = TaskPlanner.plan(TaskPlanner.Request.builder().creatureId(SessionTracker.NO_CREATURE)
			.task("Cerberus").taskType(TaskType.REGULAR).killsLeft(10).records(records).spots(TABLE).build());
		assertEquals(1, regular.getAllSetups().getTasks());
		assertEquals(10, minutes(regular.getRecommendation().getTime().getMillis()));
	}

	// ---- Counterpart ----

	@Test
	public void theCounterpartIsTheBestWithTheOtherCannonChoiceByTheSameNRule()
	{
		final List<LedgerRecord> records = all(
			times(3, task(CATACOMBS_ID, false, SetupStyle.BARRAGE, 142, 24)),
			times(2, task(SMOKE_ID, true, SetupStyle.MELEE, 142, 41)),
			times(1, task(SMOKE_ID, true, SetupStyle.RANGED, 142, 20)),
			times(1, task(TOWER_ID, false, SetupStyle.MELEE, 142, 30)));
		final TaskPlan plan = plan(142, AdvisorMetric.FASTEST_FINISH, records);
		assertEquals(CATACOMBS_ID, plan.getRecommendation().getSpot());
		final TaskPlan.Option counterpart = plan.getCounterpart();
		assertTrue(counterpart.isCannon());
		assertEquals("none has 3: the most tasks, not the fastest", SetupStyle.MELEE, counterpart.getStyle());
		assertEquals(2, counterpart.getTasks());
		assertEquals(SMOKE_ID, counterpart.getSpot());
		assertEquals("the rest by the metric", Arrays.asList(SetupStyle.RANGED, SetupStyle.MELEE),
			Arrays.asList(plan.getMoreSetups().get(0).getStyle(), plan.getMoreSetups().get(1).getStyle()));
		assertEquals(TOWER_ID, plan.getMoreSetups().get(1).getSpot());

		// A slower cannon option with 3 tasks is chosen on the metric over those with fewer.
		records.addAll(times(3, task(STRONGHOLD_ID, true, SetupStyle.MELEE, 142, 60)));
		final TaskPlan withThree = plan(142, AdvisorMetric.FASTEST_FINISH, records);
		assertEquals(STRONGHOLD_ID, withThree.getCounterpart().getSpot());
		assertEquals(3, withThree.getMoreSetups().size());

		// A cannon recommendation's counterpart is without one.
		final TaskPlan cannonFirst = plan(142, AdvisorMetric.FASTEST_FINISH, all(
			times(3, task(SMOKE_ID, true, SetupStyle.MELEE, 142, 20)),
			times(1, task(CATACOMBS_ID, false, SetupStyle.BARRAGE, 142, 10))));
		assertTrue(cannonFirst.getRecommendation().isCannon());
		assertFalse(cannonFirst.getCounterpart().isCannon());
		assertTrue(cannonFirst.getMoreSetups().isEmpty());
	}

	// ---- Time ----

	@Test
	public void percentilesInterpolateBetweenTheClosestRanks()
	{
		assertEquals(2.0, percentile(20, 2), 1e-12);
		assertEquals(2.0, percentile(80, 2), 1e-12);
		assertEquals(2.0, percentile(50, 1, 3), 1e-12);
		assertEquals(1.4, percentile(20, 1, 3), 1e-12);
		assertEquals(2.6, percentile(80, 1, 3), 1e-12);
		assertEquals(1.8, percentile(20, 1, 2, 3, 4, 5), 1e-12);
		assertEquals(3.0, percentile(50, 1, 2, 3, 4, 5), 1e-12);
		assertEquals(4.2, percentile(80, 1, 2, 3, 4, 5), 1e-12);
		final double[] twenty = new double[20];
		for (int i = 0; i < twenty.length; i++)
		{
			twenty[i] = i + 1;
		}
		assertEquals(4.8, percentile(20, twenty), 1e-12);
		assertEquals(10.5, percentile(50, twenty), 1e-12);
		assertEquals(16.2, percentile(80, twenty), 1e-12);
		assertNull(TaskPlanner.percentile(Collections.emptyList(), 50));
	}

	private static double percentile(int percent, double... values)
	{
		final List<BigDecimal> sorted = new ArrayList<>();
		for (double value : values)
		{
			sorted.add(BigDecimal.valueOf(value));
		}
		return TaskPlanner.percentile(sorted, percent).doubleValue();
	}

	@Test
	public void theTimeIsTheMedianPerKillTimesTheKillsLeftWithItsRange()
	{
		// Tasks of 10 kills taking 10, 20, ... minutes; 10 kills left, so the times are those minutes again.
		assertTime(1, 20, 20, 20);
		assertTime(2, 15, 12, 18);
		assertTime(5, 30, 18, 42);
		assertTime(20, 105, 48, 162);

		final TaskPlan one = plan(30, null, task(SMOKE_ID, false, SetupStyle.MELEE, 10, 20));
		assertEquals("2 minutes a kill", 2 * MINUTE, one.getRecommendation().getTime().getPerKillMillis());
		assertEquals("times the kills left", 60 * MINUTE, one.getRecommendation().getTime().getMillis());
	}

	/** {@code n} tasks of 10 kills taking 10, 20, ... minutes, or 20 minutes for one. */
	private static void assertTime(int n, long median, long low, long high)
	{
		final List<LedgerRecord> records = new ArrayList<>();
		for (int i = 1; i <= n; i++)
		{
			records.add(task(SMOKE_ID, false, SetupStyle.MELEE, 10, n == 1 ? 20 : 10 * i));
		}
		final TaskPlan.Time time = plan(10, null, records).getRecommendation().getTime();
		assertEquals(n + " tasks: median", median, minutes(time.getMillis()));
		assertEquals(n + " tasks: 20th percentile", low, minutes(time.getLowMillis()));
		assertEquals(n + " tasks: 80th percentile", high, minutes(time.getHighMillis()));
	}

	// ---- What to bring ----

	@Test
	public void eachAmountIsThe80thPercentileOfTheUsePerKillTimesTheKillsLeft()
	{
		// Cannonballs per kill 0, 1, 2, 3 and 4: the 80th percentile is 3.2 a kill.
		final List<LedgerRecord> tasks = new ArrayList<>();
		for (int i = 0; i < 5; i++)
		{
			tasks.add(using(task(SMOKE_ID, true, SetupStyle.MELEE, 10, 10), CANNONBALL, 10L * i, DEATH_RUNE,
				i == 4 ? 50 : 0));
		}
		// A sixth task that used no death runes: 5 of 6 tasks used none, so the 80th percentile is none.
		tasks.add(using(task(SMOKE_ID, true, SetupStyle.MELEE, 10, 10), CANNONBALL, 20));
		final List<TaskPlan.BringItem> bring = TaskPlanner.bring(tasks, 10, PRICES);
		assertEquals(1, bring.size());
		assertEquals(CANNONBALL, bring.get(0).getItemId());
		// Per kill 0, 1, 2, 2, 3, 4: rank 4 of 0 to 5 is 3.
		assertEquals(30, bring.get(0).getQuantity());
		assertEquals(Long.valueOf(30 * 200), bring.get(0).getValue());

		tasks.remove(5);
		assertEquals(32, TaskPlanner.bring(tasks, 10, PRICES).get(0).getQuantity());
	}

	@Test
	public void amountsRoundUpToWholeItemsButAWholeAmountStaysWhole()
	{
		assertEquals("a quarter a kill for 10 kills: 2.5", 3, single(BLOOD_RUNE, 1, 4, 10));
		assertEquals("a half a kill for 142 kills: exactly 71", 71, single(BLOOD_RUNE, 1, 2, 142));
		assertEquals("a third a kill for 3 kills: exactly 1", 1, single(BLOOD_RUNE, 1, 3, 3));
		assertEquals("a third a kill for 4 kills", 2, single(BLOOD_RUNE, 1, 3, 4));
		assertEquals(440, single(DEATH_RUNE, 440, 142, 142));
	}

	/** What to bring of {@code id} for {@code kills} kills after one task of {@code taskKills} that used {@code used}. */
	private static long single(int id, long used, int taskKills, int kills)
	{
		final LedgerRecord record = using(task(SMOKE_ID, false, SetupStyle.MELEE, taskKills, 10), id, used);
		return TaskPlanner.bring(Collections.singletonList(record), kills, PRICES).get(0).getQuantity();
	}

	@Test
	public void dosesBecomePotionsAndChargesWholeItems()
	{
		// 20 doses over 10 kills: half a potion a kill, 4.5 potions for 9 kills.
		final LedgerRecord potions = using(task(SMOKE_ID, false, SetupStyle.MELEE, 10, 10), PRAYER_POTION, 20,
			DUELING_RING, 4, SlayerBracelets.SLAUGHTER_BRACELET, 3);
		final Map<Integer, Integer> doses = new HashMap<>();
		doses.put(PRAYER_POTION, 4);
		doses.put(DUELING_RING, 8);
		doses.put(SlayerBracelets.SLAUGHTER_BRACELET, SlayerBracelets.CHARGES_PER_BRACELET);
		potions.setSupplyDoses(doses);

		final Map<Integer, Long> bring = quantities(TaskPlanner.bring(Collections.singletonList(potions), 9, PRICES));
		assertEquals(Long.valueOf(5), bring.get(PRAYER_POTION));
		// 4 charges over 10 kills, 8 charges a ring: 0.45 rings for 9 kills.
		assertEquals(Long.valueOf(1), bring.get(DUELING_RING));
		assertEquals(Long.valueOf(1), bring.get(SlayerBracelets.SLAUGHTER_BRACELET));
		final Map<Integer, Long> more = quantities(TaskPlanner.bring(Collections.singletonList(potions), 30, PRICES));
		assertEquals("1.5 rings", Long.valueOf(2), more.get(DUELING_RING));
		assertEquals("valued per potion", Long.valueOf(15 * 8_000L),
			TaskPlanner.bring(Collections.singletonList(potions), 30, PRICES).get(0).getValue());
	}

	private static Map<Integer, Long> quantities(List<TaskPlan.BringItem> items)
	{
		final Map<Integer, Long> quantities = new HashMap<>();
		for (TaskPlan.BringItem item : items)
		{
			quantities.put(item.getItemId(), item.getQuantity());
		}
		return quantities;
	}

	/** {@code record} as a 1.x task migrated by 2.0: estimated tags ({@link SetupMigration}), schema version 3. */
	private static LedgerRecord migrated(LedgerRecord record)
	{
		SetupTags.estimated(record.getDetectedCannon(), record.getDetectedStyle()).copyTo(record);
		return record;
	}

	@Test
	public void a1xTaskSavedWithoutSuppliesHasNoSupplyDataButA2Point0OneUsedNone()
	{
		final LedgerRecord used = using(task(SMOKE_ID, false, SetupStyle.MELEE, 10, 10), SUPPLY, 10);
		// Saved by 1.x without supplies, valued or not: its supplies may not have been counted.
		final LedgerRecord oldValued = migrated(valued(task(SMOKE_ID, false, SetupStyle.MELEE, 10, 10), 1_000, 0));
		final LedgerRecord oldUnvalued = migrated(task(SMOKE_ID, false, SetupStyle.MELEE, 10, 10));
		final LedgerRecord notMigrated = task(SMOKE_ID, false, SetupStyle.MELEE, 10, 10);
		notMigrated.setSchemaVersion(2);
		assertFalse(TaskPlanner.hasSupplyData(oldValued));
		assertFalse(TaskPlanner.hasSupplyData(oldUnvalued));
		assertFalse(TaskPlanner.hasSupplyData(notMigrated));
		assertTrue("supplies recorded", TaskPlanner.hasSupplyData(migrated(using(task(SMOKE_ID, false,
			SetupStyle.MELEE, 10, 10), SUPPLY, 1))));
		assertEquals("only the task that recorded supplies", 10,
			TaskPlanner.bring(Arrays.asList(used, oldValued, oldUnvalued, notMigrated), 10, PRICES).get(0)
				.getQuantity());

		// Saved by 2.0 without supplies: it used none, valued or not.
		final LedgerRecord none = task(SMOKE_ID, false, SetupStyle.MELEE, 10, 10);
		assertTrue(TaskPlanner.hasSupplyData(none));
		// Per kill 0 and 1: the 80th percentile is 0.8.
		assertEquals(8, TaskPlanner.bring(Arrays.asList(used, none), 10, PRICES).get(0).getQuantity());
		assertEquals(8, TaskPlanner.bring(Arrays.asList(used, valued(task(SMOKE_ID, false, SetupStyle.MELEE, 10, 10),
			1_000, 0)), 10, PRICES).get(0).getQuantity());
	}

	@Test
	public void aTaskWithoutSupplyDataIsLeftOutOfTheSupplyCostAndProfitAndCheapestDoesntPickIt()
	{
		// Barrage tasks saved by 1.x with no supplies recorded (a 1.x supply bug), the case migration calls Barrage: 10
		// minutes, 100K loot.
		final List<LedgerRecord> barrage = times(3, valued(task(CATACOMBS_ID, false, SetupStyle.BARRAGE, 100, 10),
			100_000, 0));
		for (LedgerRecord record : barrage)
		{
			migrated(record);
		}
		// Cannon tasks with their supplies: 20 minutes, 100K loot, 500 of the supply (50K).
		final List<LedgerRecord> cannon = times(3, valued(task(SMOKE_ID, true, SetupStyle.MELEE, 100, 20), 100_000,
			500));

		final TaskPlan cheapest = plan(100, AdvisorMetric.CHEAPEST, all(barrage, cannon));
		assertEquals("not the tasks whose supplies are unknown", SMOKE_ID, cheapest.getRecommendation().getSpot());
		assertEquals(Long.valueOf(50_000), cheapest.getRecommendation().getSupplyCost());
		final TaskPlan.Option unknown = cheapest.getCounterpart();
		assertEquals(CATACOMBS_ID, unknown.getSpot());
		assertEquals("they still count for n", 3, unknown.getTasks());
		assertEquals("and for time", 10, minutes(unknown.getTime().getMillis()));
		assertNull("no supply cost", unknown.getSupplyCost());
		assertNull("no profit: its supplies are unknown", unknown.getProfitPerHour());
		assertTrue("nothing to bring", unknown.getBring().isEmpty());

		// A 2.0 record without supplies used none: 0 gp, and it is the cheapest.
		final TaskPlan withNone = plan(100, AdvisorMetric.CHEAPEST, all(times(3, valued(task(CATACOMBS_ID, false,
			SetupStyle.BARRAGE, 100, 10), 100_000, 0)), cannon));
		assertEquals(CATACOMBS_ID, withNone.getRecommendation().getSpot());
		assertEquals(Long.valueOf(0), withNone.getRecommendation().getSupplyCost());
		assertEquals("100K in 10 minutes", Long.valueOf(600_000), withNone.getRecommendation().getProfitPerHour());
	}

	// ---- Potion families ----

	/** Prayer potion(3): 3 doses. */
	private static final int PRAYER_POTION_3 = 139;

	@Test
	public void aPotionKeptUnderTwoIdsIsOneBringItem()
	{
		// The key moved from the 3-dose to the 4-dose potion mid-task: 6 doses under one, 10 under the other.
		final LedgerRecord split = using(task(SMOKE_ID, false, SetupStyle.MELEE, 10, 10), PRAYER_POTION_3, 6,
			PRAYER_POTION, 10);
		final Map<Integer, Integer> splitDoses = new HashMap<>();
		splitDoses.put(PRAYER_POTION_3, 3);
		splitDoses.put(PRAYER_POTION, 4);
		split.setSupplyDoses(splitDoses);
		final LedgerRecord whole = using(task(SMOKE_ID, false, SetupStyle.MELEE, 10, 10), PRAYER_POTION, 16);
		whole.setSupplyDoses(Collections.singletonMap(PRAYER_POTION, 4));
		final List<LedgerRecord> tasks = Arrays.asList(split, whole);

		final Map<Integer, String> itemNames = new HashMap<>();
		itemNames.put(PRAYER_POTION_3, "Prayer potion(3)");
		itemNames.put(PRAYER_POTION, "Prayer potion(4)");
		final Map<Integer, String> families = TaskPlanner.potionFamilies(tasks, itemNames::get);
		assertEquals("prayer potion", families.get(PRAYER_POTION_3));
		assertEquals("prayer potion", families.get(PRAYER_POTION));

		// 16 doses in each task: 4 potions of 4 doses per 10 kills.
		final List<TaskPlan.BringItem> merged = TaskPlanner.bring(tasks, 10, PRICES, families);
		assertEquals(1, merged.size());
		assertEquals("keyed by the potion with the most doses", PRAYER_POTION, merged.get(0).getItemId());
		assertEquals(4, merged.get(0).getQuantity());
		assertEquals(Long.valueOf(4 * 8_000L), merged.get(0).getValue());
		assertEquals("without the families, two lines", 2, TaskPlanner.bring(tasks, 10, PRICES).size());

		final TaskPlan plan = TaskPlanner.plan(TaskPlanner.Request.builder().creatureId(CREATURE).task(TASK)
			.killsLeft(10).records(tasks).prices(PRICES).spots(TABLE).potionFamilies(families).build());
		assertEquals(merged, plan.getRecommendation().getBring());
	}

	@Test
	public void potionFamiliesAreNamedLikeTheTrackersAndLeaveOtherItemsAlone()
	{
		final int pizza = SupplyTracker.MULTI_BITE_FOOD.keySet().iterator().next();
		final int unnamed = 12_345;
		final LedgerRecord record = using(task(SMOKE_ID, false, SetupStyle.MELEE, 10, 10), PRAYER_POTION, 4,
			DUELING_RING, 2, pizza, 2, unnamed, 3, DEATH_RUNE, 100);
		final Map<Integer, Integer> doses = new HashMap<>();
		doses.put(PRAYER_POTION, 4);
		doses.put(DUELING_RING, 8);
		doses.put(pizza, 2);
		doses.put(unnamed, 4);
		record.setSupplyDoses(doses);
		final Map<Integer, String> names = new HashMap<>();
		names.put(PRAYER_POTION, "Prayer potion(4)");
		names.put(DUELING_RING, "Ring of dueling(8)");
		names.put(pizza, "Some pizza");
		names.put(DEATH_RUNE, "Death rune");

		assertEquals(Collections.singletonMap(PRAYER_POTION, "prayer potion"),
			TaskPlanner.potionFamilies(Arrays.asList(record, null), names::get));
		assertTrue(TaskPlanner.potionFamilies(null, names::get).isEmpty());
		assertTrue(TaskPlanner.potionFamilies(Collections.singletonList(record), null).isEmpty());
	}

	// ---- Wilderness and Konar ----

	@Test
	public void aWildernessTaskUsesOnlyWildernessTasks()
	{
		final List<LedgerRecord> wilderness = times(2, task(TOWER_ID, false, SetupStyle.MELEE, 10, 30));
		for (LedgerRecord record : wilderness)
		{
			record.setSlayerMaster(SlayerMaster.KRYSTILIA);
		}
		final List<LedgerRecord> elsewhere = times(3, task(SMOKE_ID, false, SetupStyle.MELEE, 10, 10));
		elsewhere.get(0).setSlayerMaster(SlayerMaster.KONAR);
		final List<LedgerRecord> records = all(wilderness, elsewhere);

		final TaskPlan plan = TaskPlanner.plan(TaskPlanner.Request.builder().creatureId(CREATURE).task(TASK)
			.taskType(TaskType.WILDERNESS).killsLeft(10).records(records).spots(TABLE).build());
		assertEquals("kills elsewhere don't count for a Wilderness task", 2, plan.getAllSetups().getTasks());
		assertEquals(TOWER_ID, plan.getRecommendation().getSpot());
		assertEquals(1, plan.getOptions().size());
		assertFalse(plan.isWildernessHistoryMissing());

		assertEquals("any other task uses them all", 5, plan(10, null, records).getAllSetups().getTasks());

		// Only tasks elsewhere: no history for a Wilderness task, noted, since the creature has tasks.
		final TaskPlan none = TaskPlanner.plan(TaskPlanner.Request.builder().creatureId(CREATURE).task(TASK)
			.taskType(TaskType.WILDERNESS).killsLeft(10).records(elsewhere).spots(TABLE).build());
		assertFalse(none.hasHistory());
		assertTrue(none.isWildernessHistoryMissing());
		assertFalse("no task of the creature at all", TaskPlanner.plan(TaskPlanner.Request.builder()
			.creatureId(CREATURE).task(TASK).taskType(TaskType.WILDERNESS).killsLeft(10)
			.records(Collections.emptyList()).spots(TABLE).build()).isWildernessHistoryMissing());
		assertFalse("not a Wilderness task", plan(10, null, Collections.emptyList()).isWildernessHistoryMissing());
	}

	@Test
	public void aKonarTaskKeepsTheOptionsAtItsAreasSpot()
	{
		final List<LedgerRecord> records = all(
			times(3, task(SMOKE_ID, false, SetupStyle.MELEE, 10, 10)),
			times(3, task(SMOKE_ID, true, SetupStyle.MELEE, 10, 5)),
			times(1, task(CATACOMBS_ID, false, SetupStyle.BARRAGE, 10, 30)));

		final TaskPlan there = konar("Catacombs of Kourend", records);
		assertEquals(CATACOMBS_ID, there.getAreaSpot());
		assertFalse(there.isAreaFallback());
		assertEquals("only the options there", 1, there.getOptions().size());
		assertEquals(CATACOMBS_ID, there.getRecommendation().getSpot());
		assertTrue(there.isRecommendationThin());
		assertNull("no cannon option there", there.getCounterpart());
		assertTrue(there.getMoreSetups().isEmpty());
		assertEquals("All setups keeps every task", 7, there.getAllSetups().getTasks());
		assertEquals(0, there.getExcludedTasks());
		assertEquals("the short name too", CATACOMBS_ID, konar("catacombs", records).getAreaSpot());

		final TaskPlan none = konar("Slayer Tower", records);
		assertEquals(TOWER_ID, none.getAreaSpot());
		assertTrue("none there: every option, noted", none.isAreaFallback());
		assertEquals(3, none.getOptions().size());
		assertEquals(SMOKE_ID, none.getRecommendation().getSpot());

		final TaskPlan unnamed = konar("Somewhere the table doesn't list", records);
		assertNull(unnamed.getAreaSpot());
		assertFalse(unnamed.isAreaFallback());
		assertEquals(3, unnamed.getOptions().size());
		assertNull(konar(null, records).getAreaSpot());
	}

	@Test
	public void aPlanSaysWhichAssignmentItIsFor()
	{
		final List<LedgerRecord> records = times(3, task(SMOKE_ID, false, SetupStyle.MELEE, 10, 10));
		final TaskPlan plan = konar("Catacombs of Kourend", records);
		assertEquals(CREATURE, plan.getCreatureId());
		assertEquals(TaskType.KONAR, plan.getTaskType());
		assertEquals("Catacombs of Kourend", plan.getArea());
		assertTrue(plan.isFor(CREATURE, TASK, TaskType.KONAR, "Catacombs of Kourend"));
		// The card keeps a plan only for its own assignment: another area, type, creature or name is another's.
		assertFalse("another Konar area", plan.isFor(CREATURE, TASK, TaskType.KONAR, "Slayer Tower"));
		assertFalse(plan.isFor(CREATURE, TASK, TaskType.REGULAR, "Catacombs of Kourend"));
		assertFalse(plan.isFor(CREATURE + 1, TASK, TaskType.KONAR, "Catacombs of Kourend"));
		assertFalse(plan.isFor(CREATURE, "Greater demons", TaskType.KONAR, "Catacombs of Kourend"));

		// The plan without history too.
		final TaskPlan none = konar(null, Collections.emptyList());
		assertFalse(none.hasHistory());
		assertTrue(none.isFor(CREATURE, TASK, TaskType.KONAR, null));
		assertFalse(none.isFor(CREATURE, TASK, TaskType.KONAR, "Slayer Tower"));
	}

	private static TaskPlan konar(String area, List<LedgerRecord> records)
	{
		return TaskPlanner.plan(TaskPlanner.Request.builder().creatureId(CREATURE).task(TASK).taskType(TaskType.KONAR)
			.area(area).killsLeft(10).records(records).spots(TABLE).build());
	}

	@Test
	public void theMostValuableEightShowAndTheRestAreCounted()
	{
		final Map<Integer, Long> prices = new HashMap<>();
		final long[] used = new long[22];
		for (int i = 0; i < 11; i++)
		{
			used[2 * i] = 1_000 + i;
			used[2 * i + 1] = 1;
			if (i < 10)
			{
				prices.put(1_000 + i, 10L * (i + 1));
			}
		}
		final LedgerRecord record = using(task(SMOKE_ID, false, SetupStyle.MELEE, 1, 1), used);
		final TaskPlan plan = TaskPlanner.plan(TaskPlanner.Request.builder().creatureId(CREATURE).task(TASK)
			.killsLeft(1).records(Collections.singletonList(record)).prices(prices).spots(TABLE).build());
		final TaskPlan.Option option = plan.getRecommendation();
		assertEquals(TaskPlanner.MAX_BRING_ITEMS, option.getBring().size());
		assertEquals(3, option.getBringMore());
		assertEquals("most valuable first", 1_009, option.getBring().get(0).getItemId());
		assertEquals(1_002, option.getBring().get(7).getItemId());
		// The rest, for "+3 more"'s tooltip: in the same order, the unpriced one last.
		final List<Integer> rest = new ArrayList<>();
		for (TaskPlan.BringItem item : option.getBringRest())
		{
			rest.add(item.getItemId());
		}
		assertEquals(Arrays.asList(1_001, 1_000, 1_010), rest);

		// Unpriced items last, then the larger amount, then the lower id.
		final LedgerRecord unpriced = using(task(SMOKE_ID, false, SetupStyle.MELEE, 1, 1), 7, 3, 8, 5, 6, 3,
			CANNONBALL, 1);
		final List<TaskPlan.BringItem> order = TaskPlanner.bring(Collections.singletonList(unpriced), 1, PRICES);
		assertEquals(Arrays.asList(CANNONBALL, 8, 6, 7), Arrays.asList(order.get(0).getItemId(),
			order.get(1).getItemId(), order.get(2).getItemId(), order.get(3).getItemId()));
		assertNull(order.get(1).getValue());
	}

	// ---- Rates and gear ----

	@Test
	public void ratesArePooledAndTheSupplyCostIsForTheKillsLeft()
	{
		final LedgerRecord first = valued(task(SMOKE_ID, false, SetupStyle.MELEE, 100, 10), 50_000, 100);
		first.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 10_000L));
		final LedgerRecord second = valued(task(SMOKE_ID, false, SetupStyle.MELEE, 50, 20), 100_000, 200);
		second.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 20_000L));
		// Saved before values and XP were tracked: counts for the time only.
		final LedgerRecord old = task(SMOKE_ID, false, SetupStyle.MELEE, 100, 30);

		final TaskPlan.Option option = plan(100, null, first, second, old).getRecommendation();
		assertEquals(3, option.getTasks());
		assertEquals("30K XP in 30 minutes", Long.valueOf(60_000), option.getSlayerXpPerHour());
		// Loot 150K minus supplies 300 x 100 gp, in 30 minutes.
		assertEquals(Long.valueOf(240_000), option.getProfitPerHour());
		assertFalse(option.isProfitApproximate());
		// 30K of supplies over 150 kills: 200 gp a kill, for 100 kills.
		assertEquals(Long.valueOf(20_000), option.getSupplyCost());
		assertFalse(option.isSupplyCostApproximate());

		final TaskPlan.Option unvalued = plan(100, null, old).getRecommendation();
		assertNull(unvalued.getSlayerXpPerHour());
		assertNull(unvalued.getProfitPerHour());
		assertNull(unvalued.getSupplyCost());

		// A supply without a price today: the cost is marked approximate.
		final LedgerRecord unpriced = valued(task(SMOKE_ID, false, SetupStyle.MELEE, 100, 10), 50_000, 0);
		unpriced.setSupplies(Collections.singletonMap(7, 5L));
		assertTrue(plan(100, null, unpriced).getRecommendation().isSupplyCostApproximate());
	}

	@Test
	public void theGearNoteIsTheFocusOfMoreThanHalfTheTasks()
	{
		final LedgerRecord prayer1 = gear(GearFocus.PRAYER);
		final LedgerRecord prayer2 = gear(GearFocus.PRAYER);
		final LedgerRecord strength = gear(GearFocus.STRENGTH);
		final TaskPlan.Option mostly = plan(10, null, prayer1, prayer2, strength).getRecommendation();
		assertEquals(GearFocus.PRAYER, mostly.getGear());
		assertEquals("mostly Prayer gear", mostly.gearNote());

		assertNull("a tie", plan(10, null, prayer1, strength).getRecommendation().gearNote());
		assertNull("Unknown is no note", plan(10, null, prayer1, gear(GearFocus.UNKNOWN), gear(GearFocus.UNKNOWN))
			.getRecommendation().gearNote());
		final LedgerRecord overridden = gear(GearFocus.UNKNOWN);
		overridden.setGearOverride(GearFocus.STRENGTH);
		assertEquals("the effective focus", GearFocus.STRENGTH,
			plan(10, null, overridden, strength, prayer1).getRecommendation().getGear());
	}

	private static LedgerRecord gear(GearFocus focus)
	{
		final LedgerRecord record = task(SMOKE_ID, false, SetupStyle.MELEE, 10, 10);
		record.setDetectedGear(focus);
		return record;
	}

	// ---- Metrics ----

	@Test
	public void eachMetricPicksItsBest()
	{
		final List<LedgerRecord> records = all(
			// 20 minutes; 100K profit (300K/h); 1,000 supplies (100K) per 100 kills.
			times(3, valued(task(CATACOMBS_ID, false, SetupStyle.BARRAGE, 100, 20), 200_000, 1_000)),
			// 30 minutes; 550K profit (1.1M/h); 50K of supplies.
			times(3, valued(task(SMOKE_ID, true, SetupStyle.MELEE, 100, 30), 600_000, 500)),
			// 40 minutes; 90K profit (135K/h); 10K of supplies.
			times(3, valued(task(TOWER_ID, false, SetupStyle.RANGED, 100, 40), 100_000, 100)));

		final TaskPlan fastest = plan(100, AdvisorMetric.FASTEST_FINISH, records);
		assertEquals(CATACOMBS_ID, fastest.getRecommendation().getSpot());
		assertEquals(SMOKE_ID, fastest.getCounterpart().getSpot());
		assertEquals(TOWER_ID, fastest.getMoreSetups().get(0).getSpot());

		final TaskPlan profit = plan(100, AdvisorMetric.PROFIT_PER_HOUR, records);
		assertEquals(SMOKE_ID, profit.getRecommendation().getSpot());
		assertEquals(Long.valueOf(1_100_000), profit.getRecommendation().getProfitPerHour());
		assertEquals("the best without a cannon", CATACOMBS_ID, profit.getCounterpart().getSpot());

		final TaskPlan cheapest = plan(100, AdvisorMetric.CHEAPEST, records);
		assertEquals(TOWER_ID, cheapest.getRecommendation().getSpot());
		assertEquals(Long.valueOf(10_000), cheapest.getRecommendation().getSupplyCost());
		assertEquals(Arrays.asList(TOWER_ID, SMOKE_ID, CATACOMBS_ID), spots(cheapest.getOptions()));
	}

	@Test
	public void tiesGoToMoreTasksThenLessTimeAndAMissingValueIsLast()
	{
		final TaskPlan.Option fewer = option(SMOKE_ID, 3, 20, 100L, 1_000L);
		final TaskPlan.Option more = option(TOWER_ID, 4, 20, 100L, 1_000L);
		final TaskPlan.Option slower = option(CATACOMBS_ID, 4, 30, 100L, 1_000L);
		final TaskPlan.Option none = option(STRONGHOLD_ID, 9, 10, null, null);
		for (AdvisorMetric metric : AdvisorMetric.values())
		{
			final List<TaskPlan.Option> options = new ArrayList<>(Arrays.asList(none, slower, fewer, more));
			options.sort(TaskPlanner.order(metric));
			if (metric == AdvisorMetric.FASTEST_FINISH)
			{
				assertEquals(metric.name(), Arrays.asList(STRONGHOLD_ID, TOWER_ID, SMOKE_ID, CATACOMBS_ID),
					spots(options));
			}
			else
			{
				assertEquals(metric.name(), Arrays.asList(TOWER_ID, CATACOMBS_ID, SMOKE_ID, STRONGHOLD_ID),
					spots(options));
			}
		}
		assertSame(AdvisorMetric.FASTEST_FINISH, AdvisorMetric.orDefault(null));
	}

	private static TaskPlan.Option option(String spot, int tasks, long minutes, Long profitPerHour, Long cost)
	{
		return TaskPlan.Option.builder()
			.spot(spot)
			.style(SetupStyle.MELEE)
			.tasks(tasks)
			.time(new TaskPlan.Time(minutes * MINUTE / 10, minutes * MINUTE, minutes * MINUTE, minutes * MINUTE))
			.profitPerHour(profitPerHour)
			.supplyCost(cost)
			.bring(Collections.emptyList())
			.build();
	}

	private static List<String> spots(List<TaskPlan.Option> options)
	{
		final List<String> spots = new ArrayList<>();
		for (TaskPlan.Option option : options)
		{
			spots.add(option.getSpot());
		}
		return spots;
	}
}
