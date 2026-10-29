package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Rules for a task's boss kill counts ({@link BossKillCount}), from the game's kill count messages
 * ({@link KillCountMessage}). Pure (no RuneLite types).
 *
 * <ul>
 *     <li><b>Which messages count.</b> Only boss kill messages ({@link KillCountMessage.Result#isBossKill()}), and only
 *     while loot is being recorded (the session is running, or a completed task waits for its last loot; enforced by
 *     {@link SessionTracker#addBossKillCount}). On a boss task (SLAYER_TARGET
 *     {@value SessionTracker#BOSS_TASK_TARGET}) every boss's message counts, since one task can give counts under
 *     several names (Dagannoth Kings give Rex, Prime and Supreme; Barrows gives "Barrows chest"). On any other task a
 *     message counts only when its boss name {@linkplain #sameName matches} the task's name, for example a TzTok-Jad
 *     or TzKal-Zuk task.</li>
 *     <li><b>Name matching.</b> Colour tags are removed, runs of spaces (including non-breaking spaces) become one
 *     space, and the ends are trimmed; the two names must then be equal ignoring case. Nothing fuzzier: no plurals,
 *     prefixes or aliases.</li>
 *     <li><b>What is kept.</b> One entry per boss name (same matching), in the order first seen: the name as the
 *     first message gave it, the first and latest count, and the kills counted. A message counts a kill when its count
 *     is higher than the entry's latest (so a repeated message counts once); the first message of a boss counts one.
 *     Kills while paused have no message counted, so they are not in the kills, but the next count includes them.
 *     The count before the task is not stored: it can't be told reliably (kills before Play, or while paused).</li>
 *     <li>A task keeps at most {@link #MAX_PER_TASK} bosses; a further boss's messages are not counted.</li>
 * </ul>
 */
final class BossKillCounts
{
	/** Most bosses kept per task. */
	static final int MAX_PER_TASK = 8;

	private BossKillCounts()
	{
	}

	/** A name without colour tags, with runs of spaces made one and the ends trimmed ("" for {@code null}). */
	static String cleanName(String name)
	{
		if (name == null)
		{
			return "";
		}
		return name.replaceAll("<[^>]*>", "").replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
	}

	/** Whether two names are the same boss or task: equal after {@link #cleanName}, ignoring case. */
	static boolean sameName(String a, String b)
	{
		final String left = key(a);
		return !left.isEmpty() && left.equals(key(b));
	}

	private static String key(String name)
	{
		return cleanName(name).toLowerCase(Locale.ROOT);
	}

	/**
	 * Whether a boss's kill count message belongs to a task.
	 *
	 * @param taskName   the task's name, or {@code null} when unknown
	 * @param creatureId the task's creature key ({@link SessionTracker#creatureKey}); boss tasks are below
	 *                   {@link SessionTracker#NO_CREATURE}
	 * @param boss       the boss name the message gives
	 */
	static boolean appliesTo(String taskName, int creatureId, String boss)
	{
		if (cleanName(boss).isEmpty())
		{
			return false;
		}
		return creatureId < SessionTracker.NO_CREATURE || sameName(taskName, boss);
	}

	/**
	 * The task's kill counts with one more message counted (see the class rules). Returns a new list;
	 * {@code current} is not changed.
	 *
	 * @param current the task's kill counts so far, or {@code null}
	 * @param boss    the boss name the message gives
	 * @param kc      the count the message gives; below 1 is ignored
	 */
	static List<BossKillCount> add(List<BossKillCount> current, String boss, int kc)
	{
		final List<BossKillCount> result = sanitize(current);
		final String name = cleanName(boss);
		if (name.isEmpty() || kc < 1)
		{
			return result;
		}
		for (int i = 0; i < result.size(); i++)
		{
			final BossKillCount entry = result.get(i);
			if (sameName(entry.getBoss(), name))
			{
				if (kc > entry.getLastKc())
				{
					result.set(i, new BossKillCount(entry.getBoss(), entry.getFirstKc(), kc, entry.getKills() + 1));
				}
				return result;
			}
		}
		if (result.size() < MAX_PER_TASK)
		{
			result.add(new BossKillCount(name, kc, kc, 1));
		}
		return result;
	}

	/**
	 * The usable entries of a stored list (never {@code null}; a new list): an entry without a name, with a count
	 * below 1, a first count above the latest, or no kills is left out, and so is a later entry for a boss already
	 * listed and anything beyond {@link #MAX_PER_TASK}.
	 */
	static List<BossKillCount> sanitize(List<BossKillCount> counts)
	{
		final List<BossKillCount> result = new ArrayList<>();
		if (counts == null)
		{
			return result;
		}
		final Set<String> seen = new HashSet<>();
		for (BossKillCount entry : counts)
		{
			if (result.size() >= MAX_PER_TASK)
			{
				break;
			}
			if (entry == null || cleanName(entry.getBoss()).isEmpty() || entry.getFirstKc() < 1
				|| entry.getLastKc() < entry.getFirstKc() || entry.getKills() < 1 || !seen.add(key(entry.getBoss())))
			{
				continue;
			}
			result.add(entry);
		}
		return result;
	}

	/**
	 * One line per boss, for the panel and the history tooltip: {@code Boss KC: 152 (+12)} for the boss the task is
	 * named after, {@code Dagannoth Rex KC: 52 (+4)} for any other. The number is the latest count and {@code +n} the
	 * kills counted on the task. Empty when there are none.
	 *
	 * @param taskName the task's name, or {@code null}
	 */
	static List<String> lines(List<BossKillCount> counts, String taskName)
	{
		final List<BossKillCount> usable = sanitize(counts);
		if (usable.isEmpty())
		{
			return Collections.emptyList();
		}
		final List<String> lines = new ArrayList<>();
		for (BossKillCount entry : usable)
		{
			final String label = sameName(entry.getBoss(), taskName) ? "Boss" : cleanName(entry.getBoss());
			lines.add(label + " KC: " + String.format(Locale.ROOT, "%,d", entry.getLastKc())
				+ " (+" + String.format(Locale.ROOT, "%,d", entry.getKills()) + ")");
		}
		return lines;
	}
}
