package com.shmayvan.slayerledger;

import com.google.gson.*;
import java.util.*;
import lombok.*;

/**
 * The bundled boss table (V2 plan 4.4): each Slayer task that has a Slayer boss, the boss, and the NPC names the
 * game gives the boss when it dies, so a task's kills per NPC name ({@link NpcKillTally}) can tell a task done on its
 * boss from one done on the task's own monsters ({@link TaskVariants}). Also the superior Slayer monsters of the tasks
 * with a boss or a Catacombs variant ({@link CatacombsVariants}), which the variant decision leaves out. Pure (no
 * RuneLite types).
 *
 * <p><b>Data and licence.</b> The rows are the bundled {@code slayer-bosses.json} ({@link BundledData}; no rows when it
 * can't be read), adapted from the Old School RuneScape Wiki by Old School RuneScape Wiki contributors, under CC
 * BY-NC-SA 3.0 (the file's {@code attribution}; the README credits it as it credits the other Wiki snapshots). The
 * list of Slayer bosses is the "Slayer bosses" section of the Wiki's Boss article (the file's {@code listSource}): the
 * bosses that each "requires a task for either the boss itself or the equivalent monster", that is the Slayer bosses
 * fought on a regular task. The "Bosses" section of the Slayer article and its main article, Boss Slayer (the boss task
 * list), were checked against it (the file's {@code checkedAgainst}). Each row cites the boss's own article, which
 * names the task (Araxxor's names two: araxytes and spiders). The superiors are from the file's
 * {@code superiorsSource}. Bosses that are only assigned as boss tasks (Vorkath, Zulrah, the Maggot King and the rest
 * of Boss Slayer's list) and bosses that count for a task without needing one (for example the King Black Dragon for
 * black dragons) are not on that list, so they are not rows.</p>
 *
 * <p><b>NPC names.</b> Each boss lists the name of every NPC that dies in one of its kills, as the game's NPC
 * definitions give it (the gameval {@code NpcID} javadoc names; the file keeps one id and constant per name to show
 * where it came from, and {@code TaskVariantsTest} checks them). The name is the same in every form: the Abyssal Sire's
 * phases, the Alchemical Hydra's forms and Cerberus' are all named after the boss. The Grotesque Guardians are two
 * NPCs, Dusk and Dawn, killed together. The Kraken lies as a "Whirlpool" until it is disturbed and dies as the
 * "Kraken"; the whirlpool's name is left out, since cave kraken lie as whirlpools too.</p>
 */
final class SlayerBosses
{
	/** One Slayer task's Slayer boss. Immutable. */
	@Getter
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	static final class Boss
	{
		/** The Slayer task's name, as the game's task table gives it (for example "Gargoyles"). */
		private final String task;
		/**
		 * The boss's name, as its kill count message gives it (for example "Grotesque Guardians"): the name a task done
		 * on it is shown and grouped under ({@link LedgerRecord#getVariant}).
		 */
		private final String name;
		/** The name of every NPC that dies in one of its kills ("Dusk", "Dawn"), in the file's order. Unmodifiable. */
		private final List<String> npcNames;
	}

	private static final List<Boss> BOSSES = BundledData.load("slayer-bosses.json",
		root -> BundledData.rows(root.getAsJsonArray("bosses"), row -> new Boss(row.get("task").getAsString(),
			row.get("name").getAsString(), names(row.getAsJsonArray("npcs")))), Collections.emptyList());

	/**
	 * The names of the superior Slayer monsters of the tasks with a Slayer boss or a Catacombs variant; hellhounds, cave
	 * kraken, spiders and the dragons have none. On any other task the variant is always the standard monster, so its
	 * superiors need no row. A superior only appears on its own task.
	 */
	private static final List<String> SUPERIORS = BundledData.load("slayer-bosses.json",
		root -> names(root.getAsJsonArray("superiors")), Collections.emptyList());

	/** The bosses' NPC names, {@link TaskStatistics#normalize}d (the tally asks for every NPC that dies). */
	private static final Set<String> BOSS_NPC_KEYS = bossNpcKeys();
	/** The superiors' names, {@link TaskStatistics#normalize}d (the overlay's variant asks every frame). */
	private static final Set<String> SUPERIOR_KEYS = keys(SUPERIORS);

	private SlayerBosses()
	{
	}

	/** Every row, in a fixed order. */
	static List<Boss> all()
	{
		return BOSSES;
	}

	/** The task's Slayer boss ({@code taskName} matched by {@link TaskStatistics#normalize}), or {@code null}. */
	static Boss forTask(String taskName)
	{
		final String key = TaskStatistics.normalize(taskName);
		if (key == null)
		{
			return null;
		}
		for (Boss boss : BOSSES)
		{
			if (key.equals(TaskStatistics.normalize(boss.task)))
			{
				return boss;
			}
		}
		return null;
	}

	/** Whether {@code npcName} (matched by {@link TaskStatistics#normalize}) is one of a listed boss's NPCs. */
	static boolean isBossNpcName(String npcName)
	{
		final String key = TaskStatistics.normalize(npcName);
		return key != null && BOSS_NPC_KEYS.contains(key);
	}

	/** Whether {@code npcName} (matched by {@link TaskStatistics#normalize}) is a listed superior's name. */
	static boolean isSuperiorName(String npcName)
	{
		final String key = TaskStatistics.normalize(npcName);
		return key != null && SUPERIOR_KEYS.contains(key);
	}

	private static Set<String> bossNpcKeys()
	{
		final Set<String> keys = new HashSet<>();
		for (Boss boss : BOSSES)
		{
			keys.addAll(keys(boss.npcNames));
		}
		return Collections.unmodifiableSet(keys);
	}

	private static Set<String> keys(List<String> names)
	{
		final Set<String> keys = new HashSet<>();
		for (String name : names)
		{
			keys.add(TaskStatistics.normalize(name));
		}
		return Collections.unmodifiableSet(keys);
	}

	/** The {@code name}s of the NPC objects of {@code npcs}, in order. Unmodifiable. */
	private static List<String> names(JsonArray npcs)
	{
		return BundledData.rows(npcs, npc -> npc.get("name").getAsString());
	}
}
