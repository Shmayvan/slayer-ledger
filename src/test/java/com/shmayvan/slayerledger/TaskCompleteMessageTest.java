package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class TaskCompleteMessageTest
{
	@Test
	public void formatsSummaryUsingSelectedLootPriceMode()
	{
		LedgerRecord record = record(true);
		record.setMonster("Wyrms");
		record.setKills(172);
		record.setActiveMillis(6_012_000);
		record.setLootGeValue(2_500_000L);
		record.setLootHaValue(1_500_000L);
		record.setSuppliesGeValue(400_000L);
		record.setSuperiorsSpawned(3);

		assertEquals("Slayer task complete: 172 Wyrms · 1:40:12 · +2.1M · 3 superiors",
			TaskCompleteMessage.format(record, LootPriceMode.GE));
		assertEquals("Slayer task complete: 172 Wyrms · 1:40:12 · +1.1M · 3 superiors",
			TaskCompleteMessage.format(record, LootPriceMode.HIGH_ALCH));
	}

	@Test
	public void omitsUnavailableProfitAndSuperiorCount()
	{
		LedgerRecord record = record(true);
		record.setMonster("Wyrms");
		record.setKills(172);
		record.setActiveMillis(6_012_000);

		assertEquals("Slayer task complete: 172 Wyrms · 1:40:12", TaskCompleteMessage.format(record, LootPriceMode.GE));
	}

	@Test
	public void omitsZeroSuperiorsAndShowsApproximateProfit()
	{
		LedgerRecord record = record(true);
		record.setMonster("Wyrms");
		record.setKills(172);
		record.setActiveMillis(6_012_000);
		record.setLootGeValue(100L);
		record.setSuppliesGeValue(0L);
		record.setLootUnpricedItems(1);
		record.setSuperiorsSpawned(0);

		assertEquals("Slayer task complete: 172 Wyrms · 1:40:12 · ~+100",
			TaskCompleteMessage.format(record, LootPriceMode.GE));

		record.setSuperiorsSpawned(1);
		assertEquals("Slayer task complete: 172 Wyrms · 1:40:12 · ~+100 · 1 superior",
			TaskCompleteMessage.format(record, LootPriceMode.GE));
	}

	@Test
	public void putsTheSignAfterTheApproximateMarkForLosses()
	{
		LedgerRecord record = record(true);
		record.setMonster("Wyrms");
		record.setKills(172);
		record.setActiveMillis(6_012_000);
		record.setLootGeValue(100L);
		record.setSuppliesGeValue(600L);
		record.setLootUnpricedItems(1);

		assertEquals("Slayer task complete: 172 Wyrms · 1:40:12 · ~-500",
			TaskCompleteMessage.format(record, LootPriceMode.GE));
	}

	@Test
	public void doesNotFormatPartialRecords()
	{
		assertNull(TaskCompleteMessage.format(record(false), LootPriceMode.GE));
	}

	private static LedgerRecord record(boolean completed)
	{
		LedgerRecord record = new LedgerRecord();
		record.setCompleted(completed);
		return record;
	}
}
