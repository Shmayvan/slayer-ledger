package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class TaskChartTest
{
	private static final long HOUR = 60 * 60 * 1000L;

	@Test
	public void plotsEachCompletedTaskRateChronologicallyAndKeepsNotesAndApproximation()
	{
		LedgerRecord later = record(42, "Abyssal demons", "later", 3 * HOUR, 2 * HOUR);
		later.setLootGeValue(300L);
		later.setSuppliesGeValue(0L);
		LedgerRecord earlier = record(42, "Abyssal demons", "earlier", HOUR, HOUR);
		earlier.setLootGeValue(100L);
		earlier.setSuppliesGeValue(0L);
		earlier.setLootUnpricedItems(1);

		List<TaskChart.Monster> monsters = TaskChart.monsters(Arrays.asList(later, earlier),
			TaskRanking.Metric.PROFIT, LootPriceMode.GE, null);
		assertEquals(1, monsters.size());
		List<TaskChart.Point> points = TaskChart.points(Arrays.asList(later, earlier), monsters.get(0),
			TaskRanking.Metric.PROFIT, LootPriceMode.GE, null);

		assertEquals(2, points.size());
		assertEquals(HOUR, points.get(0).getEndedAt());
		assertEquals(100L, points.get(0).getRatePerHour());
		assertTrue(points.get(0).isApproximate());
		assertEquals("earlier", points.get(0).getNote());
		assertEquals(3 * HOUR, points.get(1).getEndedAt());
		assertEquals(150L, points.get(1).getRatePerHour());
		assertFalse(points.get(1).isApproximate());
	}

	@Test
	public void excludesPartialIncompleteUndatedUntimedAndUnrecordedMetricSamples()
	{
		LedgerRecord valid = record(42, "Abyssal demons", null, HOUR, HOUR);
		valid.setXpGained(Collections.singletonMap("SLAYER", 50_000L));
		LedgerRecord partial = record(42, "Abyssal demons", null, 2 * HOUR, HOUR);
		partial.setPartial(true);
		partial.setXpGained(Collections.singletonMap("SLAYER", 500_000L));
		LedgerRecord incomplete = record(42, "Abyssal demons", null, 3 * HOUR, HOUR);
		incomplete.setCompleted(false);
		incomplete.setXpGained(Collections.singletonMap("SLAYER", 500_000L));
		LedgerRecord undated = record(42, "Abyssal demons", null, 0, HOUR);
		undated.setXpGained(Collections.singletonMap("SLAYER", 500_000L));
		LedgerRecord untimed = record(42, "Abyssal demons", null, 4 * HOUR, 0);
		untimed.setXpGained(Collections.singletonMap("SLAYER", 500_000L));
		LedgerRecord missingXp = record(42, "Abyssal demons", null, 5 * HOUR, HOUR);

		List<LedgerRecord> records = Arrays.asList(valid, partial, incomplete, undated, untimed, missingXp);
		List<TaskChart.Monster> monsters = TaskChart.monsters(records, TaskRanking.Metric.XP, LootPriceMode.GE, null);
		assertEquals(1, monsters.size());
		List<TaskChart.Point> points = TaskChart.points(records, monsters.get(0), TaskRanking.Metric.XP,
			LootPriceMode.GE, null);
		assertEquals(1, points.size());
		assertEquals(50_000L, points.get(0).getRatePerHour());

		assertTrue(TaskChart.monsters(records, TaskRanking.Metric.PROFIT, LootPriceMode.GE, null).isEmpty());
	}

	@Test
	public void legacyNamesMatchOnlyWhenTheCreatureIdIsUnambiguous()
	{
		LedgerRecord keyed = record(42, "Abyssal demons", null, HOUR, HOUR);
		keyed.setXpGained(Collections.singletonMap("SLAYER", 10L));
		LedgerRecord legacy = record(null, " ABYSSAL DEMONS ", null, 2 * HOUR, HOUR);
		legacy.setXpGained(Collections.singletonMap("SLAYER", 20L));
		List<LedgerRecord> unique = Arrays.asList(keyed, legacy);
		assertEquals(1, TaskChart.monsters(unique, TaskRanking.Metric.XP, LootPriceMode.GE, null).size());
		TaskChart.Monster monster = TaskChart.monsters(unique, TaskRanking.Metric.XP, LootPriceMode.GE, null).get(0);
		assertEquals(2, TaskChart.points(unique, monster, TaskRanking.Metric.XP, LootPriceMode.GE, null).size());

		LedgerRecord otherKey = record(43, "Abyssal demons", null, 3 * HOUR, HOUR);
		otherKey.setXpGained(Collections.singletonMap("SLAYER", 30L));
		assertEquals(3, TaskChart.monsters(Arrays.asList(keyed, legacy, otherKey), TaskRanking.Metric.XP,
			LootPriceMode.GE, null).size());
	}

	@Test
	public void usesSelectedLootModeAndCurrentPricesForProfitRates()
	{
		LedgerRecord record = record(42, "Abyssal demons", null, HOUR, HOUR);
		record.setLoot(Collections.singletonMap(5, 2L));
		record.setSupplies(Collections.singletonMap(6, 1L));
		record.setUnitGePrices(Collections.singletonMap(5, 10L));
		record.setUnitHaPrices(Collections.singletonMap(5, 20L));
		record.setLootGeValue(20L);
		record.setLootHaValue(40L);
		record.setSuppliesGeValue(0L);
		TaskChart.Monster monster = TaskChart.monsters(Collections.singletonList(record),
			TaskRanking.Metric.PROFIT, LootPriceMode.GE, null).get(0);

		assertEquals(20L, TaskChart.points(Collections.singletonList(record), monster, TaskRanking.Metric.PROFIT,
			LootPriceMode.GE, null).get(0).getRatePerHour());
		assertEquals(40L, TaskChart.points(Collections.singletonList(record), monster, TaskRanking.Metric.PROFIT,
			LootPriceMode.HIGH_ALCH, null).get(0).getRatePerHour());
		assertEquals(195L, TaskChart.points(Collections.singletonList(record), monster, TaskRanking.Metric.PROFIT,
			LootPriceMode.GE, Map.of(5, 100L, 6, 5L)).get(0).getRatePerHour());
	}

	@Test
	public void chartRendersAndPointTooltipIncludesDateRateAndEscapedTaskNote()
	{
		LedgerRecord record = record(42, "Abyssal demons", "Barrage & burst", HOUR, HOUR);
		record.setLootGeValue(100L);
		record.setSuppliesGeValue(0L);
		TaskChart.Monster monster = TaskChart.monsters(Collections.singletonList(record),
			TaskRanking.Metric.PROFIT, LootPriceMode.GE, null).get(0);
		List<TaskChart.Point> points = TaskChart.points(Collections.singletonList(record), monster,
			TaskRanking.Metric.PROFIT, LootPriceMode.GE, null);
		TaskTrendChartView chart = new TaskTrendChartView();
		chart.setData(points, "Profit (GE)/h", ZoneId.of("UTC"));
		chart.setSize(240, 150);
		BufferedImage image = new BufferedImage(240, 150, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		chart.paint(graphics);
		graphics.dispose();

		String tooltip = chart.getToolTipText(new MouseEvent(chart, MouseEvent.MOUSE_MOVED,
			System.currentTimeMillis(), 0, 143, 67, 0, false));
		assertTrue(tooltip, tooltip.contains("Profit (GE)/h: 100"));
		assertFalse(tooltip, tooltip.contains("~ means"));
		assertTrue(tooltip, tooltip.contains("Barrage &amp; burst"));
		String date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.of("UTC"))
			.format(Instant.ofEpochMilli(HOUR));
		assertTrue(tooltip, tooltip.contains(date));
	}

	@Test
	public void aTaskNeedsAMinuteOfActiveTime()
	{
		LedgerRecord short59 = record(42, "Abyssal demons", null, HOUR, 59_000L);
		short59.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 59L));
		LedgerRecord minute = record(42, "Abyssal demons", null, 2 * HOUR, 60_000L);
		minute.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 60L));

		assertTrue(TaskChart.monsters(Collections.singletonList(short59), TaskRanking.Metric.XP, LootPriceMode.GE,
			null).isEmpty());
		List<LedgerRecord> records = Arrays.asList(short59, minute);
		TaskChart.Monster monster = TaskChart.monsters(records, TaskRanking.Metric.XP, LootPriceMode.GE, null)
			.get(0);
		List<TaskChart.Point> points = TaskChart.points(records, monster, TaskRanking.Metric.XP, LootPriceMode.GE,
			null);
		assertEquals(1, points.size());
		assertEquals(2 * HOUR, points.get(0).getEndedAt());
		assertEquals(3_600L, points.get(0).getRatePerHour());
	}

	@Test
	public void profitPerKillPlotsEachTaskOverItsOwnKillsWithNoMinimumTime()
	{
		LedgerRecord brief = record(42, "Abyssal demons", "brief", HOUR, 1_000L);
		brief.setLootGeValue(5_000L);
		brief.setLootHaValue(8_000L);
		brief.setSuppliesGeValue(1_000L);
		LedgerRecord loss = record(42, "Abyssal demons", null, 2 * HOUR, HOUR);
		loss.setKills(4);
		loss.setLootGeValue(0L);
		loss.setLootHaValue(0L);
		loss.setSuppliesGeValue(2_000L);
		loss.setSuppliesUnpricedItems(1);
		// Not valued, and undated: no point.
		LedgerRecord legacy = record(42, "Abyssal demons", null, 3 * HOUR, HOUR);
		LedgerRecord undated = record(42, "Abyssal demons", null, 0L, HOUR);
		undated.setLootGeValue(1_000_000L);
		undated.setSuppliesGeValue(0L);
		List<LedgerRecord> records = Arrays.asList(legacy, loss, brief, undated);

		TaskChart.Monster monster = TaskChart.monsters(records, TaskRanking.Metric.PROFIT_PER_KILL,
			LootPriceMode.GE, null).get(0);
		List<TaskChart.Point> points = TaskChart.points(records, monster, TaskRanking.Metric.PROFIT_PER_KILL,
			LootPriceMode.GE, null);
		assertEquals(2, points.size());
		assertEquals(HOUR, points.get(0).getEndedAt());
		assertEquals(400L, points.get(0).getRatePerHour());
		assertFalse(points.get(0).isApproximate());
		assertEquals(-500L, points.get(1).getRatePerHour());
		assertTrue(points.get(1).isApproximate());

		List<TaskChart.Point> ha = TaskChart.points(records, monster, TaskRanking.Metric.PROFIT_PER_KILL,
			LootPriceMode.HIGH_ALCH, null);
		assertEquals(700L, ha.get(0).getRatePerHour());
		// The hourly profit still leaves the brief task out.
		assertEquals(1, TaskChart.points(records, monster, TaskRanking.Metric.PROFIT, LootPriceMode.GE, null).size());
	}

	@Test
	public void xpIsSlayerXpOnlyAndMissingSlayerXpIsZero()
	{
		LedgerRecord both = record(42, "Abyssal demons", null, HOUR, HOUR);
		Map<String, Long> xp = new HashMap<>();
		xp.put(SkillXp.SLAYER, 50L);
		xp.put("ATTACK", 1_000L);
		both.setXpGained(xp);
		LedgerRecord noSlayer = record(42, "Abyssal demons", null, 2 * HOUR, HOUR);
		noSlayer.setXpGained(Collections.singletonMap("ATTACK", 1_000L));
		LedgerRecord noXp = record(42, "Abyssal demons", null, 3 * HOUR, HOUR);

		List<LedgerRecord> records = Arrays.asList(both, noSlayer, noXp);
		TaskChart.Monster monster = TaskChart.monsters(records, TaskRanking.Metric.XP, LootPriceMode.GE, null)
			.get(0);
		List<TaskChart.Point> points = TaskChart.points(records, monster, TaskRanking.Metric.XP, LootPriceMode.GE,
			null);
		assertEquals(2, points.size());
		assertEquals(50L, points.get(0).getRatePerHour());
		assertEquals(0L, points.get(1).getRatePerHour());
	}

	@Test
	public void pointsNeedTheWholeAssignment()
	{
		LedgerRecord whole = record(42, "Abyssal demons", null, HOUR, HOUR);
		whole.setPointsEarned(15);
		whole.setOriginalAmount(100);
		whole.setInitialAmount(100);
		LedgerRecord partWay = record(42, "Abyssal demons", null, 2 * HOUR, HOUR);
		partWay.setPointsEarned(15);
		partWay.setOriginalAmount(100);
		partWay.setInitialAmount(60);
		LedgerRecord unknownSize = record(42, "Abyssal demons", null, 3 * HOUR, HOUR);
		unknownSize.setPointsEarned(15);
		unknownSize.setInitialAmount(100);

		List<LedgerRecord> records = Arrays.asList(whole, partWay, unknownSize);
		TaskChart.Monster monster = TaskChart.monsters(records, TaskRanking.Metric.POINTS, LootPriceMode.GE, null)
			.get(0);
		List<TaskChart.Point> points = TaskChart.points(records, monster, TaskRanking.Metric.POINTS,
			LootPriceMode.GE, null);
		assertEquals(1, points.size());
		assertEquals(HOUR, points.get(0).getEndedAt());
		assertEquals(15L, points.get(0).getRatePerHour());
	}

	@Test
	public void pointsLeaveOutBonusTasks()
	{
		LedgerRecord regular = record(42, "Abyssal demons", null, HOUR, HOUR);
		regular.setPointsEarned(15);
		regular.setOriginalAmount(100);
		regular.setInitialAmount(100);
		regular.setTaskStreak(49);
		LedgerRecord fiftieth = record(42, "Abyssal demons", null, 2 * HOUR, HOUR);
		fiftieth.setPointsEarned(225);
		fiftieth.setOriginalAmount(100);
		fiftieth.setInitialAmount(100);
		fiftieth.setTaskStreak(50);
		LedgerRecord noStreak = record(42, "Abyssal demons", null, 3 * HOUR, HOUR);
		noStreak.setPointsEarned(12);
		noStreak.setOriginalAmount(100);
		noStreak.setInitialAmount(100);
		LedgerRecord onlyBonus = record(43, "Kurask", null, 4 * HOUR, HOUR);
		onlyBonus.setPointsEarned(375);
		onlyBonus.setOriginalAmount(100);
		onlyBonus.setInitialAmount(100);
		onlyBonus.setTaskStreak(100);

		List<LedgerRecord> records = Arrays.asList(regular, fiftieth, noStreak, onlyBonus);
		List<TaskChart.Monster> monsters = TaskChart.monsters(records, TaskRanking.Metric.POINTS, LootPriceMode.GE,
			null);
		assertEquals("a monster whose only task was a bonus task has no point", 1, monsters.size());
		assertEquals("Abyssal demons", monsters.get(0).getLabel());
		List<TaskChart.Point> points = TaskChart.points(records, monsters.get(0), TaskRanking.Metric.POINTS,
			LootPriceMode.GE, null);
		assertEquals(2, points.size());
		assertEquals(HOUR, points.get(0).getEndedAt());
		assertEquals(15L, points.get(0).getRatePerHour());
		assertEquals(3 * HOUR, points.get(1).getEndedAt());
		assertEquals(12L, points.get(1).getRatePerHour());
	}

	@Test
	public void monsterLabelsUseTheCreatureRecordNameAndTellSameNamedGroupsApart()
	{
		LedgerRecord legacy = record(null, " abyssal DEMONS ", null, HOUR, HOUR);
		legacy.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 1L));
		LedgerRecord keyed = record(42, "  Abyssal demons ", null, 2 * HOUR, HOUR);
		keyed.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 1L));
		assertEquals(Collections.singletonList("Abyssal demons"),
			labels(Arrays.asList(legacy, keyed), TaskRanking.Metric.XP));

		// A task and a boss task of the same name, and a record without a creature key whose name has both.
		LedgerRecord task = record(7, "Kraken", null, HOUR, HOUR);
		task.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 1L));
		LedgerRecord boss = record(-2 - 3, "Kraken", null, 2 * HOUR, HOUR);
		boss.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 1L));
		LedgerRecord older = record(null, "Kraken", null, 3 * HOUR, HOUR);
		older.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 1L));
		assertEquals(Arrays.asList("Kraken (boss)", "Kraken (older records)", "Kraken (task)"),
			labels(Arrays.asList(task, boss, older), TaskRanking.Metric.XP));

		// Two tasks of the same name fall back to their ids.
		LedgerRecord other = record(8, "Kraken", null, 4 * HOUR, HOUR);
		other.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 1L));
		assertEquals(Arrays.asList("Kraken (task 7)", "Kraken (task 8)"),
			labels(Arrays.asList(task, other), TaskRanking.Metric.XP));

		// A blank name without a creature key is not grouped.
		LedgerRecord blank = record(null, "  ", null, HOUR, HOUR);
		blank.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 1L));
		assertTrue(labels(Collections.singletonList(blank), TaskRanking.Metric.XP).isEmpty());
	}

	@Test
	public void yAxisTicksAreNiceStepsCoveringTheRange()
	{
		assertEquals(Arrays.asList(1_229_500L, 1_230_000L, 1_230_500L, 1_231_000L),
			ticks(1_229_900L, 1_231_000L));
		assertEquals(Arrays.asList(90L, 95L, 100L, 105L, 110L), ticks(100L, 100L));
		assertEquals(Arrays.asList(-1L, 0L, 1L), ticks(0L, 0L));
		assertEquals(Arrays.asList(-500L, 0L, 500L, 1_000L), ticks(-300L, 1_000L));
	}

	@Test
	public void approximateTooltipExplainsTheTilde()
	{
		LedgerRecord record = record(42, "Abyssal demons", null, HOUR, HOUR);
		record.setLootGeValue(100L);
		record.setSuppliesGeValue(0L);
		record.setLootUnpricedItems(1);
		TaskChart.Monster monster = TaskChart.monsters(Collections.singletonList(record),
			TaskRanking.Metric.PROFIT, LootPriceMode.GE, null).get(0);
		TaskTrendChartView chart = new TaskTrendChartView();
		chart.setData(TaskChart.points(Collections.singletonList(record), monster, TaskRanking.Metric.PROFIT,
			LootPriceMode.GE, null), "Profit (GE)/h", ZoneId.of("UTC"));
		chart.setSize(240, 150);
		BufferedImage image = new BufferedImage(240, 150, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		chart.paint(graphics);
		graphics.dispose();

		String tooltip = chart.getToolTipText(new MouseEvent(chart, MouseEvent.MOUSE_MOVED,
			System.currentTimeMillis(), 0, 143, 67, 0, false));
		assertTrue(tooltip, tooltip.contains("Profit (GE)/h: ~100"));
		assertTrue(tooltip,
			tooltip.contains("~ means some item prices were unavailable, or an older task kept its saved totals."));
	}

	private static List<Long> ticks(long minimum, long maximum)
	{
		List<Long> ticks = new ArrayList<>();
		for (long tick : TaskTrendChartView.ticks(minimum, maximum))
		{
			ticks.add(tick);
		}
		return ticks;
	}

	private static List<String> labels(List<LedgerRecord> records, TaskRanking.Metric metric)
	{
		List<String> labels = new ArrayList<>();
		for (TaskChart.Monster monster : TaskChart.monsters(records, metric, LootPriceMode.GE, null))
		{
			labels.add(monster.getLabel());
		}
		return labels;
	}

	private static LedgerRecord record(Integer creatureId, String monster, String note, long endedAt,
		long activeMillis)
	{
		LedgerRecord record = new LedgerRecord();
		record.setCreatureId(creatureId);
		record.setMonster(monster);
		record.setNote(note);
		record.setEndedAt(endedAt);
		record.setKills(10);
		record.setActiveMillis(activeMillis);
		record.setCompleted(true);
		record.setPartial(false);
		return record;
	}
}
