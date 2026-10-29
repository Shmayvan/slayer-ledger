package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.Test;

/** {@link AssignmentLog}: the bound, the check against saved records, the order and the row text. */
public class AssignmentLogTest
{
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M/d/yy", Locale.US)
		.withZone(ZoneOffset.UTC);
	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("M/d/yy, h:mm a", Locale.US)
		.withZone(ZoneOffset.UTC);
	/** 2026-10-26 12:00 UTC. */
	private static final long OCT_26 = 1_793_016_000_000L;

	static AssignmentEntry entry(String id, long endedAt)
	{
		AssignmentEntry e = new AssignmentEntry();
		e.setId(id);
		e.setMonster("Abyssal demons");
		e.setCreatureId(42);
		e.setOriginalAmount(120);
		e.setEndedAt(endedAt);
		e.setOutcome(AssignmentOutcome.SKIPPED);
		e.setPointsChange(-30);
		return e;
	}

	private static LedgerRecord record(int creatureId, Integer amount, boolean completed, long endedAt)
	{
		LedgerRecord r = new LedgerRecord();
		r.setId("r" + endedAt);
		r.setCreatureId(creatureId);
		r.setOriginalAmount(amount);
		r.setCompleted(completed);
		r.setPartial(!completed);
		r.setEndedAt(endedAt);
		return r;
	}

	@Test
	public void addKeepsTheNewestEntriesOldestFirst()
	{
		List<AssignmentEntry> entries = new ArrayList<>();
		for (int i = 0; i < AssignmentLog.MAX_ENTRIES; i++)
		{
			entries = AssignmentLog.add(entries, entry("e" + i, 1_000L + i));
		}
		assertEquals(AssignmentLog.MAX_ENTRIES, entries.size());
		entries = AssignmentLog.add(entries, entry("newest", 5_000L));
		assertEquals(AssignmentLog.MAX_ENTRIES, entries.size());
		assertEquals("the oldest went", "e1", entries.get(0).getId());
		assertEquals("newest", entries.get(entries.size() - 1).getId());
	}

	@Test
	public void anOlderEntryBeyondTheBoundIsNotKept()
	{
		List<AssignmentEntry> entries = new ArrayList<>();
		for (int i = 0; i < AssignmentLog.MAX_ENTRIES; i++)
		{
			entries.add(entry("e" + i, 1_000L + i));
		}
		entries = AssignmentLog.add(entries, entry("ancient", 1L));
		assertEquals(AssignmentLog.MAX_ENTRIES, entries.size());
		assertTrue(entries.stream().noneMatch(e -> "ancient".equals(e.getId())));
	}

	@Test
	public void addIgnoresAnIdAlreadyThereAndNulls()
	{
		List<AssignmentEntry> entries = AssignmentLog.add(Arrays.asList(entry("a", 1L), null), entry("a", 2L));
		assertEquals(1, entries.size());
		assertEquals(1L, entries.get(0).getEndedAt());
		assertEquals(1, AssignmentLog.add(entries, null).size());
	}

	@Test
	public void sortNewestFirst()
	{
		List<AssignmentEntry> sorted = AssignmentLog.sortNewestFirst(Arrays.asList(entry("a", 1L), entry("b", 3L),
			null, entry("c", 2L)));
		assertEquals(Arrays.asList("b", "c", "a"), Arrays.asList(sorted.get(0).getId(), sorted.get(1).getId(),
			sorted.get(2).getId()));
		assertTrue(AssignmentLog.sortNewestFirst(null).isEmpty());
	}

	@Test
	public void playedBeforeWhenTheNewestRecordIsAPartialOfTheSameTask()
	{
		AssignmentEntry e = entry("a", 9_000L);
		assertTrue(AssignmentLog.playedBefore(Arrays.asList(record(46, 150, true, 1_000L), record(42, 120, false, 2_000L)),
			e));
		assertTrue("size unknown in the record", AssignmentLog.playedBefore(
			Collections.singletonList(record(42, null, false, 2_000L)), e));
	}

	@Test
	public void notPlayedBeforeOtherwise()
	{
		AssignmentEntry e = entry("a", 9_000L);
		assertFalse("no records", AssignmentLog.playedBefore(Collections.emptyList(), e));
		assertFalse("completed: that assignment ended", AssignmentLog.playedBefore(
			Collections.singletonList(record(42, 120, true, 2_000L)), e));
		assertFalse("another task", AssignmentLog.playedBefore(
			Collections.singletonList(record(46, 120, false, 2_000L)), e));
		assertFalse("another size", AssignmentLog.playedBefore(
			Collections.singletonList(record(42, 200, false, 2_000L)), e));
		assertFalse("only the newest counts", AssignmentLog.playedBefore(
			Arrays.asList(record(42, 120, false, 1_000L), record(46, 150, true, 2_000L)), e));
		e.setCreatureId(null);
		assertFalse("unknown task", AssignmentLog.playedBefore(
			Collections.singletonList(record(42, 120, false, 2_000L)), e));
	}

	@Test
	public void rowText()
	{
		assertEquals("Abyssal demons \u00b7 Skipped \u00b7 10/26/26", AssignmentLog.rowText(entry("a", OCT_26), DATE));
		AssignmentEntry e = entry("a", OCT_26);
		e.setOutcome(null);
		e.setMonster(null);
		assertEquals("Unknown task \u00b7 Ended \u00b7 10/26/26", AssignmentLog.rowText(e, DATE));
		e.setEndedAt(0);
		assertEquals("Ended", AssignmentLog.status(e, DATE));
	}

	@Test
	public void tooltipShowsKnownPartsOnly()
	{
		AssignmentEntry e = entry("a", OCT_26);
		e.setSlayerMaster(5);
		assertEquals("<html>Abyssal demons<br>Skipped (cancelled for points)<br>Ended: 10/26/26, 12:00 PM"
			+ "<br>Master: Duradel/Kuradal<br>Type: Regular<br>Assigned: 120<br>Points: -30</html>",
			AssignmentLog.tooltip(e, DATE_TIME));

		AssignmentEntry bare = new AssignmentEntry();
		bare.setOutcome(AssignmentOutcome.COMPLETED);
		bare.setMonster("<b>x</b>");
		assertEquals("<html>&lt;b&gt;x&lt;/b&gt;<br>Completed without a tracked session</html>",
			AssignmentLog.tooltip(bare, DATE_TIME));

		AssignmentEntry konar = entry("k", OCT_26);
		konar.setSlayerMaster(SlayerMaster.KONAR);
		konar.setTaskLocation("Kraken Cove");
		konar.setSlayerArea(12);
		konar.setPointsChange(18);
		konar.setOutcome(AssignmentOutcome.COMPLETED);
		String tip = AssignmentLog.tooltip(konar, DATE_TIME);
		assertTrue(tip, tip.contains("<br>Type: Konar (Kraken Cove)<br>"));
		assertTrue(tip, tip.endsWith("<br>Points: +18</html>"));
	}

	@Test
	public void deleteConfirmation()
	{
		assertEquals("Delete this Abyssal demons entry (skipped)? This can't be undone.",
			AssignmentLog.deleteConfirmation(entry("a", 1L)));
	}
}
