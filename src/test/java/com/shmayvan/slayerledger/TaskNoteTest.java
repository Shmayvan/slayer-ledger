package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Note normalization and the recent-notes list. */
public class TaskNoteTest
{
	@Test
	public void trimsAndCollapsesWhitespace()
	{
		assertEquals("Prayer gear", TaskNote.normalize("  Prayer   gear  "));
		assertEquals("Prayer gear", TaskNote.normalize("Prayer\t gear"));
		assertEquals("Barrage", TaskNote.normalize("Barrage"));
	}

	@Test
	public void emptyIsNull()
	{
		assertNull(TaskNote.normalize(null));
		assertNull(TaskNote.normalize(""));
		assertNull(TaskNote.normalize("   \t\n "));
		assertNull(TaskNote.normalize("\u0000\u0007"));
	}

	@Test
	public void controlCharactersAreStripped()
	{
		assertEquals("Range pray", TaskNote.normalize("Range\npray"));
		assertEquals("Range pray", TaskNote.normalize("Range\r\n\u0000pray\u001b"));
		assertEquals("Cannon", TaskNote.normalize("Can​non"));
	}

	@Test
	public void longNotesAreCut()
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 10; i++)
		{
			sb.append("abcdefghi ");
		}
		String note = TaskNote.normalize(sb.toString());
		assertEquals(TaskNote.MAX_LENGTH, 60);
		// Six 9-letter words and five spaces make 59; a seventh word does not fit, and no trailing space is kept.
		assertEquals("abcdefghi abcdefghi abcdefghi abcdefghi abcdefghi abcdefghi", note);

		String exact = repeat('x', 60);
		assertEquals(exact, TaskNote.normalize(exact));
		assertEquals(exact, TaskNote.normalize(exact + "yz"));
		assertEquals(exact, TaskNote.normalize("   " + exact + "   "));
	}

	@Test
	public void neverSplitsASurrogatePair()
	{
		String emoji = "💀";
		String note = TaskNote.normalize(repeat('x', 59) + emoji);
		assertEquals(repeat('x', 59), note);
		assertEquals(repeat('x', 58) + emoji, TaskNote.normalize(repeat('x', 58) + emoji));
	}

	@Test
	public void recentNotesStartWithTheDefaults()
	{
		assertEquals(TaskNote.DEFAULTS, TaskNote.recentNotes(Collections.<String>emptyList()));
		assertEquals(Arrays.asList("Prayer gear", "Strength gear", "Barrage", "Range pray", "Cannon"),
			TaskNote.DEFAULTS);
	}

	@Test
	public void recentNotesAreNewestFirstDistinctAndKeepTheLatestCasing()
	{
		List<String> notes = Arrays.asList("Burst  ", null, "", "burst", "BARRAGE", "Bursting cannon", "Burst");
		assertEquals(Arrays.asList("Burst", "BARRAGE", "Bursting cannon", "Prayer gear", "Strength gear", "Range pray",
			"Cannon"), TaskNote.recentNotes(notes));
	}

	@Test
	public void recentNotesAreCapped()
	{
		List<String> notes = new ArrayList<>();
		for (int i = 0; i < 12; i++)
		{
			notes.add("Note " + i);
		}
		List<String> recent = TaskNote.recentNotes(notes);
		assertEquals(TaskNote.MAX_RECENT, recent.size());
		assertEquals("Note 0", recent.get(0));
		assertEquals("Note 7", recent.get(7));
	}

	@Test
	public void recentNotesFromRecordsUseTheEndTime()
	{
		LedgerRecord older = record("a", 1_000, "Cannon");
		LedgerRecord newer = record("b", 2_000, "Barrage");
		LedgerRecord none = record("c", 3_000, null);
		LedgerRecord oldest = record("d", 500, "barrage");
		List<String> recent = TaskNote.recentNotes(Arrays.asList(older, none, oldest, newer));
		assertEquals(Arrays.asList("Barrage", "Cannon", "Prayer gear", "Strength gear", "Range pray"), recent);
	}

	private static LedgerRecord record(String id, long endedAt, String note)
	{
		LedgerRecord r = new LedgerRecord();
		r.setId(id);
		r.setEndedAt(endedAt);
		r.setNote(note);
		return r;
	}

	private static String repeat(char c, int n)
	{
		char[] chars = new char[n];
		Arrays.fill(chars, c);
		return new String(chars);
	}
}
