package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Rules for the free-text note on a task (for example "Barrage" or "Prayer gear"): how a typed note is cleaned up and
 * which notes the panel offers for reuse. Pure; no RuneLite, Swing or file access.
 */
final class TaskNote
{
	/** Longest note kept, in characters; longer notes are cut. */
	static final int MAX_LENGTH = 60;
	/** Most notes offered in the recent-notes list. */
	static final int MAX_RECENT = 8;
	/** Notes offered before (and after) the player's own, so common labels are spelled the same way every time. */
	static final List<String> DEFAULTS = Collections.unmodifiableList(Arrays.asList(
		"Prayer gear", "Strength gear", "Barrage", "Range pray", "Cannon"));

	private TaskNote()
	{
	}

	/**
	 * The note as stored: control characters become spaces, runs of whitespace become one space, the ends are trimmed
	 * and it is cut to {@link #MAX_LENGTH} characters (never in the middle of a surrogate pair).
	 *
	 * @return the note, or {@code null} when nothing is left (or {@code raw} is {@code null})
	 */
	static String normalize(String raw)
	{
		return normalize(raw, MAX_LENGTH);
	}

	/**
	 * {@link #normalize(String)} with another length limit, for other short player-typed labels (for example
	 * {@link TaskLocation#normalizeName location names}).
	 *
	 * @param maxLength longest result kept, in characters
	 */
	static String normalize(String raw, int maxLength)
	{
		if (raw == null)
		{
			return null;
		}
		StringBuilder sb = new StringBuilder(Math.min(raw.length(), maxLength + 1));
		boolean pendingSpace = false;
		for (int i = 0; i < raw.length(); )
		{
			int cp = raw.codePointAt(i);
			i += Character.charCount(cp);
			if (Character.isISOControl(cp) || Character.isWhitespace(cp) || Character.isSpaceChar(cp)
				|| Character.getType(cp) == Character.FORMAT)
			{
				// Separators become one space between words; format characters (e.g. zero-width) are dropped too.
				pendingSpace |= Character.getType(cp) != Character.FORMAT;
				continue;
			}
			int extra = (pendingSpace && sb.length() > 0 ? 1 : 0) + Character.charCount(cp);
			if (sb.length() + extra > maxLength)
			{
				break;
			}
			if (pendingSpace && sb.length() > 0)
			{
				sb.append(' ');
			}
			pendingSpace = false;
			sb.appendCodePoint(cp);
		}
		return sb.length() == 0 ? null : sb.toString();
	}

	/**
	 * Notes to offer for reuse: the distinct notes of the given records, newest first, then the {@link #DEFAULTS}, at
	 * most {@link #MAX_RECENT}. Notes differing only in case count once, with the newest record's spelling.
	 */
	static List<String> recentNotes(Collection<LedgerRecord> records)
	{
		List<String> notes = new ArrayList<>();
		for (LedgerRecord r : LedgerHistory.sortNewestFirst(records))
		{
			notes.add(r.getNote());
		}
		return recentNotes(notes);
	}

	/**
	 * Like {@link #recentNotes(Collection)}, from notes already ordered newest first. Each is normalized; empty and
	 * {@code null} ones are skipped.
	 */
	static List<String> recentNotes(List<String> newestFirst)
	{
		List<String> result = new ArrayList<>(MAX_RECENT);
		Set<String> seen = new HashSet<>();
		addDistinct(newestFirst, result, seen);
		addDistinct(DEFAULTS, result, seen);
		return result;
	}

	private static void addDistinct(List<String> notes, List<String> result, Set<String> seen)
	{
		for (String raw : notes)
		{
			if (result.size() >= MAX_RECENT)
			{
				return;
			}
			String note = normalize(raw);
			if (note != null && seen.add(note.toLowerCase(Locale.ROOT)))
			{
				result.add(note);
			}
		}
	}
}
