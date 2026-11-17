package com.shmayvan.slayerledger;

import java.time.ZoneId;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@link OverallTotals#summarize} for tests whose subject isn't the assignment log or the hidden loot: no assignment
 * log entries, and (unless given) no loot hidden.
 */
final class TestTotals
{
	private TestTotals()
	{
	}

	static List<OverallTotals.Entry> summarize(Collection<LedgerRecord> records, OverallTotals.Grouping grouping,
		LootPriceMode mode, Map<Integer, Long> currentGe, ZoneId zone)
	{
		return summarize(records, grouping, mode, currentGe, HiddenLoot.NONE, zone);
	}

	static List<OverallTotals.Entry> summarize(Collection<LedgerRecord> records, OverallTotals.Grouping grouping,
		LootPriceMode mode, Map<Integer, Long> currentGe, HiddenLoot hidden, ZoneId zone)
	{
		return OverallTotals.summarize(records, Collections.emptyList(), grouping, mode, currentGe, hidden, zone);
	}
}
