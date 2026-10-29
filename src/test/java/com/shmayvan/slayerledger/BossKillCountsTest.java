package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** {@link BossKillCounts}: which messages belong to a task, name matching, the per-task counts and their lines. */
public class BossKillCountsTest
{
	private static final int BOSS_TASK = SessionTracker.creatureKey(SessionTracker.BOSS_TASK_TARGET, 20);
	private static final int REGULAR_TASK = 42;

	@Test
	public void nameMatchingIgnoresCaseTagsAndSpacing()
	{
		assertTrue(BossKillCounts.sameName("TzTok-Jad", "tztok-jad"));
		assertTrue(BossKillCounts.sameName("<col=ff0000>TzTok-Jad</col>", " TzTok-Jad "));
		assertTrue("double and non-breaking spaces",
			BossKillCounts.sameName("Kalphite  Queen", "Kalphite\u00A0queen"));
		assertFalse("no fuzzy matching", BossKillCounts.sameName("Dagannoth Kings", "Dagannoth Rex"));
		assertFalse(BossKillCounts.sameName("Black demons", "Black demon"));
		assertFalse(BossKillCounts.sameName(null, null));
		assertFalse(BossKillCounts.sameName("", ""));
		assertEquals("Vorkath", BossKillCounts.cleanName("<col=ff0000>Vorkath</col>"));
	}

	@Test
	public void bossTasksTakeEveryBossRegularTasksOnlyTheirOwn()
	{
		assertTrue(BossKillCounts.appliesTo("Dagannoth Kings", BOSS_TASK, "Dagannoth Rex"));
		assertTrue("the name lookup failed", BossKillCounts.appliesTo(null, BOSS_TASK, "Vorkath"));
		assertTrue(BossKillCounts.appliesTo("TzTok-Jad", REGULAR_TASK, "TzTok-Jad"));
		assertTrue(BossKillCounts.appliesTo("Tztok-jad", REGULAR_TASK, "<col=ff0000>TzTok-Jad</col>"));
		assertFalse(BossKillCounts.appliesTo("Abyssal demons", REGULAR_TASK, "Vorkath"));
		assertFalse(BossKillCounts.appliesTo(null, REGULAR_TASK, "Vorkath"));
		assertFalse(BossKillCounts.appliesTo("TzTok-Jad", SessionTracker.NO_CREATURE, "Vorkath"));
		assertFalse(BossKillCounts.appliesTo("Vorkath", BOSS_TASK, " "));
	}

	@Test
	public void countsKillsFromTheFirstMessage()
	{
		List<BossKillCount> counts = BossKillCounts.add(null, "Vorkath", 141);
		assertEquals(Collections.singletonList(new BossKillCount("Vorkath", 141, 141, 1)), counts);
		counts = BossKillCounts.add(counts, "vorkath", 142);
		counts = BossKillCounts.add(counts, "Vorkath", 152);
		assertEquals("one kill per message; the first spelling stays",
			Collections.singletonList(new BossKillCount("Vorkath", 141, 152, 3)), counts);
	}

	@Test
	public void repeatedOrLowerCountsAndBadCountsAreIgnored()
	{
		List<BossKillCount> counts = BossKillCounts.add(null, "Vorkath", 10);
		counts = BossKillCounts.add(counts, "Vorkath", 10);
		counts = BossKillCounts.add(counts, "Vorkath", 9);
		counts = BossKillCounts.add(counts, "Vorkath", 0);
		counts = BossKillCounts.add(counts, "", 11);
		assertEquals(Collections.singletonList(new BossKillCount("Vorkath", 10, 10, 1)), counts);
	}

	@Test
	public void addDoesNotChangeTheGivenList()
	{
		List<BossKillCount> before = BossKillCounts.add(null, "Vorkath", 10);
		List<BossKillCount> copy = new ArrayList<>(before);
		BossKillCounts.add(before, "Vorkath", 11);
		BossKillCounts.add(before, "Zulrah", 1);
		assertEquals(copy, before);
	}

	@Test
	public void severalBossesInOrderFirstSeenAndBounded()
	{
		List<BossKillCount> counts = BossKillCounts.add(null, "Dagannoth Rex", 50);
		counts = BossKillCounts.add(counts, "Dagannoth Prime", 20);
		counts = BossKillCounts.add(counts, "Dagannoth Rex", 51);
		assertEquals(Arrays.asList(new BossKillCount("Dagannoth Rex", 50, 51, 2),
			new BossKillCount("Dagannoth Prime", 20, 20, 1)), counts);

		for (int i = 0; i < BossKillCounts.MAX_PER_TASK + 2; i++)
		{
			counts = BossKillCounts.add(counts, "Boss " + i, 1);
		}
		assertEquals(BossKillCounts.MAX_PER_TASK, counts.size());
		counts = BossKillCounts.add(counts, "Dagannoth Rex", 52);
		assertEquals("a listed boss still counts once full", 52, counts.get(0).getLastKc());
	}

	@Test
	public void sanitizeDropsBrokenEntries()
	{
		List<BossKillCount> stored = Arrays.asList(null, new BossKillCount(null, 1, 1, 1),
			new BossKillCount("Vorkath", 0, 5, 1), new BossKillCount("Vorkath", 6, 5, 1),
			new BossKillCount("Vorkath", 5, 6, 0), new BossKillCount("Vorkath", 5, 6, 2),
			new BossKillCount("VORKATH", 1, 9, 9));
		assertEquals(Collections.singletonList(new BossKillCount("Vorkath", 5, 6, 2)),
			BossKillCounts.sanitize(stored));
		assertTrue(BossKillCounts.sanitize(null).isEmpty());
	}

	@Test
	public void linesNameOnlyTheOtherBosses()
	{
		List<BossKillCount> counts = Arrays.asList(new BossKillCount("Vorkath", 141, 1152, 12));
		assertEquals(Collections.singletonList("Boss KC: 1,152 (+12)"), BossKillCounts.lines(counts, "vorkath"));
		assertEquals(Collections.singletonList("Vorkath KC: 1,152 (+12)"), BossKillCounts.lines(counts, null));
		assertEquals(Arrays.asList("Dagannoth Rex KC: 51 (+2)", "Dagannoth Prime KC: 20 (+1)"),
			BossKillCounts.lines(Arrays.asList(new BossKillCount("Dagannoth Rex", 50, 51, 2),
				new BossKillCount("Dagannoth Prime", 20, 20, 1)), "Dagannoth Kings"));
		assertTrue(BossKillCounts.lines(null, "Vorkath").isEmpty());
	}

	@Test
	public void tooltipIsEmptyWithoutCounts()
	{
		assertEquals("", LedgerHistory.bossKillCountTooltip(null, "Vorkath"));
	}

	@Test
	public void tooltipListsTheCountsEscaped()
	{
		assertEquals("<br>Kree'arra KC: 4 (+1)", LedgerHistory.bossKillCountTooltip(
			Collections.singletonList(new BossKillCount("Kree'arra", 4, 4, 1)), "General Graardor"));
		assertEquals("<br>A &amp; B KC: 1 (+1)", LedgerHistory.bossKillCountTooltip(
			Collections.singletonList(new BossKillCount("A & B", 1, 1, 1)), null));
	}
}
