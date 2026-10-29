package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** {@link AssignmentPreviewMessage}: the new-assignment chat line from the History estimate's statistics. */
public class AssignmentPreviewMessageTest
{
	private static final long MINUTE = 60_000L;
	private static final int DEMONS = 42;

	/**
	 * A completed Abyssal demons task: 10 kills in 10 minutes, loot 100K GE / 200K HA, supplies 10K, 1 bank trip; with
	 * its items ({@link SavedItems}).
	 */
	private static LedgerRecord task(String location)
	{
		LedgerRecord record = new LedgerRecord();
		record.setCreatureId(DEMONS);
		record.setMonster("Abyssal demons");
		record.setKills(10);
		record.setActiveMillis(10 * MINUTE);
		record.setCompleted(true);
		record.setLootGeValue(100_000L);
		record.setLootHaValue(200_000L);
		record.setSuppliesGeValue(10_000L);
		record.setBankTrips(1);
		record.setLocation(location);
		return SavedItems.withItems(record);
	}

	private static TaskStatistics stats(List<LedgerRecord> records, LootPriceMode mode)
	{
		return TaskStatistics.forTask(records, DEMONS, "Abyssal demons", mode, SavedItems.todaysPrices());
	}

	private static String format(int kills, List<LedgerRecord> records, LootPriceMode mode)
	{
		return AssignmentPreviewMessage.format("Abyssal demons", kills, stats(records, mode), mode);
	}

	@Test
	public void theFullLineHasTimeProfitAndBankTripsForTheKillsLeft()
	{
		List<LedgerRecord> records = Arrays.asList(task(null), task(null), task(null));
		assertEquals("Abyssal demons (172): ~2:52:00 · +1.54M (GE) · ~18 bank trips (from 3 tasks)",
			format(172, records, LootPriceMode.GE));
	}

	@Test
	public void theProfitIsInTheLootPriceModeAndSaysWhich()
	{
		List<LedgerRecord> records = Collections.singletonList(task(null));
		assertEquals("Abyssal demons (20): ~0:20:00 · +180K (GE) · ~2 bank trips (from 1 task)",
			format(20, records, LootPriceMode.GE));
		assertEquals("Abyssal demons (20): ~0:20:00 · +380K (HA) · ~2 bank trips (from 1 task)",
			format(20, records, LootPriceMode.HIGH_ALCH));
	}

	@Test
	public void unknownPartsAreLeftOut()
	{
		// Saved before values and bank trips were tracked: only the time is known.
		LedgerRecord old = task(null);
		old.setLootGeValue(null);
		old.setLootHaValue(null);
		old.setSuppliesGeValue(null);
		old.setBankTrips(null);
		assertEquals("Abyssal demons (20): ~0:20:00 (from 1 task)",
			format(20, Collections.singletonList(old), LootPriceMode.GE));

		// No bank trips on any of them: "No bank trips", as What to bring.
		LedgerRecord noTrips = task(null);
		noTrips.setBankTrips(0);
		assertEquals("Abyssal demons (20): ~0:20:00 · +180K (GE) · No bank trips (from 1 task)",
			format(20, Collections.singletonList(noTrips), LootPriceMode.GE));
	}

	@Test
	public void anApproximateOrLosingProfitPutsTheSignAfterTheMark()
	{
		LedgerRecord loss = task(null);
		loss.setLootGeValue(1_000L);
		loss.setSuppliesGeValue(6_000L);
		SavedItems.withItems(loss);
		// Item 7 has no price today.
		Map<Integer, Long> loot = new HashMap<>(loss.getLoot());
		loot.put(7, 1L);
		loss.setLoot(loot);
		loss.setBankTrips(null);
		assertEquals("Abyssal demons (10): ~0:10:00 · ~-5,000 (GE) (from 1 task)",
			format(10, Collections.singletonList(loss), LootPriceMode.GE));
	}

	@Test
	public void historyLimitedToALocationSaysWhere()
	{
		List<LedgerRecord> records = Arrays.asList(task("Catacombs of Kourend"), task("Abyss"));
		TaskStatistics stats = TaskStatistics.forTask(records, DEMONS, "Abyssal demons", "Catacombs of Kourend",
			LedgerRecord::getLocation, LootPriceMode.GE, SavedItems.todaysPrices());
		assertEquals("Abyssal demons (10): ~0:10:00 · +90K (GE) · ~1 bank trip (from 1 task at Catacombs of Kourend)",
			AssignmentPreviewMessage.format("Abyssal demons", 10, stats, LootPriceMode.GE));
	}

	@Test
	public void nothingWithoutCompletedHistory()
	{
		LedgerRecord partial = task(null);
		partial.setPartial(true);
		assertNull(format(20, Collections.singletonList(partial), LootPriceMode.GE));
		assertNull(format(20, Collections.emptyList(), LootPriceMode.GE));
		assertNull("no kills left", format(0, Collections.singletonList(task(null)), LootPriceMode.GE));
		assertNull(AssignmentPreviewMessage.format("Abyssal demons", 20, null, LootPriceMode.GE));
	}
}
