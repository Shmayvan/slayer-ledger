package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;

public class SupplyRecommendationTest
{
	@Test
	public void scalesPooledSupplyUseAndBankTripsPerKill()
	{
		LedgerRecord first = record(10);
		first.setSupplies(map(100, 12, 101, 30));
		first.setSupplyDoses(Collections.singletonMap(100, 4));
		first.setBankTrips(1);
		LedgerRecord second = record(20);
		second.setSupplies(map(100, 40, 101, 60));
		second.setSupplyDoses(Collections.singletonMap(100, 4));
		second.setBankTrips(2);

		TaskStatistics stats = statistics(Arrays.asList(first, second));
		SupplyRecommendation.Estimate estimate = SupplyRecommendation.forTask(stats, 10);

		// 52 doses over 30 kills, times 10: 17.3, rounded up.
		assertEquals(Long.valueOf(18), estimate.getSupplies().get(100));
		assertEquals(Long.valueOf(30), estimate.getSupplies().get(101));
		assertEquals(2, estimate.getSupplySampleCount());
		assertEquals(30, estimate.getSupplyKills());
		// 3 trips over 30 kills, times 10.
		assertEquals(Long.valueOf(1), estimate.getBankTrips());
		assertEquals(2, estimate.getBankTripSampleCount());
		assertEquals(30, estimate.getBankTripKills());
		assertEquals(10, estimate.getKills());
	}

	@Test
	public void wholeNumberResultsAreNotRoundedUpByFloatingPointError()
	{
		// 5 / 147 * 147 and 3 / 187 * 187 come out just above a whole number in doubles.
		assertEquals(Long.valueOf(5), single(5, 147, 147));
		assertEquals(Long.valueOf(3), single(3, 187, 187));
		assertEquals(Long.valueOf(15), single(3, 17, 85));
		// 10 over 3 kills, times 2: 6.67, rounded up.
		assertEquals(Long.valueOf(7), single(10, 3, 2));
		assertEquals(Long.valueOf(1), single(1, 1_000, 1));
	}

	@Test
	public void scaleUpIsExactAndSaturates()
	{
		assertEquals(5, SupplyRecommendation.scaleUp(5, 147, 147));
		assertEquals(1, SupplyRecommendation.scaleUp(1, 1, 3));
		assertEquals(0, SupplyRecommendation.scaleUp(0, 10, 3));
		assertEquals(Long.MAX_VALUE, SupplyRecommendation.scaleUp(Long.MAX_VALUE, 2, 1));
		assertEquals(Long.MAX_VALUE / 2 + 1, SupplyRecommendation.scaleUp(Long.MAX_VALUE, 1, 2));
	}

	@Test
	public void bankTripsUseOnlyTheKillsOfRecordsWithACount()
	{
		LedgerRecord counted = record(10);
		counted.setBankTrips(1);
		LedgerRecord unknown = record(30);
		unknown.setBankTrips(null);
		LedgerRecord negative = record(30);
		negative.setBankTrips(-1);
		SupplyRecommendation.Estimate estimate = SupplyRecommendation.forTask(
			statistics(Arrays.asList(counted, unknown, negative)), 25);

		// 1 trip over 10 kills, times 25: 2.5, rounded up.
		assertEquals(Long.valueOf(3), estimate.getBankTrips());
		assertEquals(1, estimate.getBankTripSampleCount());
		assertEquals(10, estimate.getBankTripKills());
	}

	@Test
	public void excludesPartialRecordsAndKeepsUnknownMetricsUnknown()
	{
		LedgerRecord partial = record(10);
		partial.setPartial(true);
		partial.setSupplies(map(100, 1_000));
		partial.setBankTrips(100);

		LedgerRecord legacy = record(10);
		legacy.setSupplies(null);
		legacy.setBankTrips(null);
		TaskStatistics stats = statistics(Arrays.asList(partial, legacy));

		assertEquals(1, stats.getSampleCount());
		assertEquals(0, stats.getSupplySampleCount());
		assertNull(SupplyRecommendation.forTask(stats, 10));
		assertNull(SupplyRecommendation.forTask(statistics(Collections.emptyList()), 10));
	}

	@Test
	public void countsKnownNoUseAsNoSuppliesAndAllowsBankOnlyEstimates()
	{
		LedgerRecord noSupplies = record(10);
		noSupplies.setLootGeValue(0L);
		noSupplies.setSupplies(Collections.emptyMap());
		noSupplies.setBankTrips(0);
		SupplyRecommendation.Estimate none = SupplyRecommendation.forTask(statistics(Collections.singletonList(noSupplies)),
			10);
		assertEquals(1, none.getSupplySampleCount());
		assertTrue(none.getSupplies().isEmpty());
		assertEquals(Long.valueOf(0), none.getBankTrips());

		LedgerRecord bankOnly = record(10);
		bankOnly.setBankTrips(2);
		SupplyRecommendation.Estimate banks = SupplyRecommendation.forTask(statistics(Collections.singletonList(bankOnly)),
			10);
		assertEquals(0, banks.getSupplySampleCount());
		assertTrue(banks.getSupplies().isEmpty());
		assertEquals(Long.valueOf(2), banks.getBankTrips());
	}

	@Test
	public void supplyPricesAreTodaysPrices()
	{
		LedgerRecord older = record(10);
		older.setEndedAt(1_000L);
		older.setSupplies(map(100, 5, 101, 5));
		older.setUnitGePrices(map(100, 10, 101, 20));
		LedgerRecord newer = record(10);
		newer.setEndedAt(2_000L);
		newer.setSupplies(map(100, 5));
		newer.setUnitGePrices(map(100, 30));

		// The prices saved with the tasks are not used.
		TaskStatistics none = statistics(Arrays.asList(newer, older));
		assertTrue(none.getSupplyUnitGePrices().isEmpty());

		TaskStatistics today = TaskStatistics.forTask(Arrays.asList(newer, older), 42, "Abyssal demons",
			LootPriceMode.GE, map(100, 99));
		assertEquals(Long.valueOf(99), today.getSupplyUnitGePrices().get(100));
		assertNull("no price today means none", today.getSupplyUnitGePrices().get(101));
	}

	/** The suggestion for {@code kills} after one task of {@code sampleKills} kills that used {@code used} of an item. */
	private static Long single(int used, int sampleKills, int kills)
	{
		LedgerRecord record = record(sampleKills);
		record.setSupplies(map(100, used));
		return SupplyRecommendation.forTask(statistics(Collections.singletonList(record)), kills).getSupplies().get(100);
	}

	private static TaskStatistics statistics(Iterable<LedgerRecord> records)
	{
		java.util.List<LedgerRecord> list = new java.util.ArrayList<>();
		for (LedgerRecord record : records)
		{
			list.add(record);
		}
		return TaskStatistics.forTask(list, 42, "Abyssal demons", LootPriceMode.GE, Collections.emptyMap());
	}

	private static LedgerRecord record(int kills)
	{
		LedgerRecord record = new LedgerRecord();
		record.setCreatureId(42);
		record.setMonster("Abyssal demons");
		record.setKills(kills);
		record.setActiveMillis(kills * 1_000L);
		record.setCompleted(true);
		return record;
	}

	private static Map<Integer, Long> map(int... values)
	{
		Map<Integer, Long> result = new java.util.HashMap<>();
		for (int i = 0; i < values.length; i += 2)
		{
			result.put(values[i], (long) values[i + 1]);
		}
		return result;
	}
}
