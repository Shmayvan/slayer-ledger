package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

/**
 * The task-complete chat line ({@code DESIGN_PRINCIPLES.md} 5.3): facts first, then {@code Gz.}; the singular and
 * plural forms, an approximate or missing profit, a long name, and the chat message's colours.
 */
public class TaskCompleteMessageTest
{
	@Test
	public void theDesignShape()
	{
		LedgerRecord record = record(true);
		record.setMonster("Dust devils");
		record.setKills(201);
		record.setActiveMillis(61 * 60_000L);
		record.setLootGeValue(500_000L);
		record.setSuppliesGeValue(88_000L);

		assertEquals("Dust devils done: 201 kills in 1 h 1 min, +412K. Gz.",
			TaskCompleteMessage.format(record, LootPriceMode.GE));
		assertEquals("without the Gz when a record line follows", "Dust devils done: 201 kills in 1 h 1 min, +412K.",
			TaskCompleteMessage.format(record, LootPriceMode.GE, HiddenLoot.NONE, false));
	}

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

		assertEquals("Wyrms done: 172 kills in 1 h 40 min, +2.1M, 3 superiors. Gz.",
			TaskCompleteMessage.format(record, LootPriceMode.GE));
		assertEquals("Wyrms done: 172 kills in 1 h 40 min, +1.1M, 3 superiors. Gz.",
			TaskCompleteMessage.format(record, LootPriceMode.HIGH_ALCH));
	}

	@Test
	public void omitsUnavailableProfitAndSuperiorCount()
	{
		LedgerRecord record = record(true);
		record.setMonster("Wyrms");
		record.setKills(172);
		record.setActiveMillis(6_012_000);

		assertEquals("Wyrms done: 172 kills in 1 h 40 min. Gz.", TaskCompleteMessage.format(record, LootPriceMode.GE));
	}

	@Test
	public void singularForms()
	{
		LedgerRecord record = record(true);
		record.setMonster("Cave kraken");
		record.setKills(1);
		record.setActiveMillis(52 * 60_000L);
		record.setLootGeValue(0L);
		record.setSuppliesGeValue(0L);
		record.setSuperiorsSpawned(1);

		assertEquals("Cave kraken done: 1 kill in 52 min, 0, 1 superior. Gz.",
			TaskCompleteMessage.format(record, LootPriceMode.GE));
	}

	@Test
	public void omitsZeroSuperiorsAndShowsApproximateProfit()
	{
		LedgerRecord record = record(true);
		record.setMonster("Wyrms");
		record.setKills(1_412);
		record.setActiveMillis(6_012_000);
		record.setLootGeValue(100L);
		record.setSuppliesGeValue(0L);
		record.setLootUnpricedItems(1);
		record.setSuperiorsSpawned(0);

		assertEquals("Wyrms done: 1,412 kills in 1 h 40 min, ~+100. Gz.",
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

		assertEquals("Wyrms done: 172 kills in 1 h 40 min, ~-500. Gz.",
			TaskCompleteMessage.format(record, LootPriceMode.GE));
	}

	@Test
	public void aLongNameStaysWholeOnOneLine()
	{
		LedgerRecord record = record(true);
		record.setMonster("Smoke devils");
		record.setVariant("Thermonuclear smoke devil");
		record.setKills(40);
		record.setActiveMillis(30_000);
		record.setLootGeValue(1_000_000L);
		record.setSuppliesGeValue(0L);

		String line = TaskCompleteMessage.format(record, LootPriceMode.GE);
		assertEquals("Thermonuclear smoke devil done: 40 kills in 1 min, +1M. Gz.", line);
		assertEquals(-1, line.indexOf('\n'));
	}

	@Test
	public void doesNotFormatPartialRecords()
	{
		assertNull(TaskCompleteMessage.format(record(false), LootPriceMode.GE));
		assertNull(TaskCompleteMessage.format(null, LootPriceMode.GE));
	}

	@Test
	public void theChatMessageHighlightsTheHeadAndEscapesTheName()
	{
		LedgerRecord record = record(true);
		record.setMonster("Dust <col=ff0000>devils");
		record.setKills(2);
		record.setActiveMillis(120_000);
		String line = TaskCompleteMessage.format(record, LootPriceMode.GE);

		assertEquals("<colHIGHLIGHT>Dust <lt>col=ff0000<gt>devils done:<colNORMAL> 2 kills in 2 min. Gz.",
			TaskCompleteMessage.chatMessage(record, line));
	}

	private static LedgerRecord record(boolean completed)
	{
		LedgerRecord record = new LedgerRecord();
		record.setCompleted(completed);
		return record;
	}
}
