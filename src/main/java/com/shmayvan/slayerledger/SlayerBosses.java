package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.gameval.NpcID;

/**
 * The bundled boss table (V2 plan 4.4): each Slayer task that has a Slayer boss, the boss, and the NPC names the
 * game gives the boss when it dies, so a task's kills per NPC name ({@link NpcKillTally}) can tell a task done on its
 * boss from one done on the task's own monsters ({@link TaskVariants}). Also the superior Slayer monsters of the tasks
 * with a boss or a Catacombs variant ({@link CatacombsVariants}), which the variant decision leaves out. Pure (no
 * RuneLite types; the ids are the RuneLite 1.13.4 gameval {@code NpcID} constants, plain ints).
 *
 * <p><b>Data and licence.</b> The rows are adapted from the Old School RuneScape Wiki by Old School RuneScape Wiki
 * contributors, under CC BY-NC-SA 3.0 ({@link #ATTRIBUTION}; the README credits it as it credits the other Wiki
 * snapshots). The list of Slayer bosses is the "Slayer bosses" section of the Wiki's {@link #LIST_SOURCE Boss} article:
 * the bosses that each "requires a task for either the boss itself or the equivalent monster", that is the Slayer bosses
 * fought on a regular task. The "Bosses" section of the Slayer article (revision 15396824) and its main article, Boss
 * Slayer (the boss task list, revision 15390553), were checked against it. Each row cites the boss's own article, which
 * names the task (Araxxor's names two: araxytes and spiders). The superiors are from {@link #SUPERIORS_SOURCE}. Bosses
 * that are only assigned as boss tasks (Vorkath, Zulrah, the Maggot King and the rest of Boss Slayer's list) and bosses
 * that count for a task without needing one (for example the King Black Dragon for black dragons) are not on that list,
 * so they are not rows.</p>
 *
 * <p><b>NPC names.</b> Each boss lists the name of every NPC that dies in one of its kills, as the game's NPC
 * definitions give it (the gameval {@code NpcID} javadoc names, checked against RuneLite 1.13.4; one id is kept per
 * name to show where it came from, and {@code TaskVariantsTest} checks the constants again). The name is the same in
 * every form: the Abyssal Sire's phases, the Alchemical Hydra's forms and Cerberus' are all named after the boss. The
 * Grotesque Guardians are two NPCs, Dusk and Dawn, killed together. The Kraken lies as a "Whirlpool" until it is
 * disturbed and dies as the "Kraken"; the whirlpool's name is left out, since cave kraken lie as whirlpools too.</p>
 */
final class SlayerBosses
{
	/** Where the data comes from, as the bundled Wiki data ({@code slayer-spots.json}) names it. */
	static final String SOURCE = "Old School RuneScape Wiki";
	static final String LICENSE = "CC BY-NC-SA 3.0";
	static final String LICENSE_URL = "https://creativecommons.org/licenses/by-nc-sa/3.0/";
	static final String CONTRIBUTORS = "Old School RuneScape Wiki contributors";
	/** When the cited revisions were read. */
	static final String RETRIEVED = "2026-11-09";
	static final String ATTRIBUTION = "Slayer bosses, the tasks they are fought on and superior Slayer monster names"
		+ " adapted from the listed Old School RuneScape Wiki articles (revisions given per row) by Old School RuneScape"
		+ " Wiki contributors. This dataset is a modified, partial snapshot (only task, boss and NPC names kept) and is"
		+ " shared under the same CC BY-NC-SA 3.0 license.";

	/** One Old School RuneScape Wiki article at one revision. Immutable. */
	static final class Source
	{
		private final String article;
		private final String page;
		private final long revision;

		private Source(String article, String page, long revision)
		{
			this.article = article;
			this.page = page;
			this.revision = revision;
		}

		/** The article's title, for example "Grotesque Guardians". */
		String getArticle()
		{
			return article;
		}

		/** The article's address. */
		String getPage()
		{
			return page;
		}

		/** The article's revision the row was taken from. */
		long getRevision()
		{
			return revision;
		}
	}

	/** One NPC name, with one gameval id that has it. Immutable. */
	static final class Npc
	{
		private final int id;
		private final String name;

		private Npc(int id, String name)
		{
			this.id = id;
			this.name = name;
		}

		/** One of the NPC's ids (RuneLite 1.13.4 gameval {@code NpcID}), to show where the name came from. */
		int getId()
		{
			return id;
		}

		/** The NPC's name as the game gives it, for example "Dusk". */
		String getName()
		{
			return name;
		}
	}

	/** One Slayer task's Slayer boss. Immutable. */
	static final class Boss
	{
		private final String task;
		private final String name;
		private final List<Npc> npcs;
		private final Source source;

		private Boss(String task, String name, Source source, Npc... npcs)
		{
			this.task = task;
			this.name = name;
			this.source = source;
			this.npcs = Collections.unmodifiableList(Arrays.asList(npcs));
		}

		/** The Slayer task's name, as the game's task table gives it (for example "Gargoyles"). */
		String getTask()
		{
			return task;
		}

		/**
		 * The boss's name, as its kill count message gives it (for example "Grotesque Guardians"): the name a task done
		 * on it is shown and grouped under ({@link LedgerRecord#getVariant}).
		 */
		String getName()
		{
			return name;
		}

		/** Every NPC that dies in one of its kills (Dusk and Dawn); never empty. */
		List<Npc> getNpcs()
		{
			return npcs;
		}

		/** The Wiki article that says the boss is fought on this task. */
		Source getSource()
		{
			return source;
		}
	}

	/** The Wiki's list of Slayer bosses: the Boss article's "Slayer bosses" section. */
	static final Source LIST_SOURCE = wiki("Boss", "Boss#Slayer_bosses", 15390364L);
	/** The Wiki's list of superior Slayer monsters. */
	static final Source SUPERIORS_SOURCE = wiki("Superior slayer monster", "Superior_slayer_monster", 15389037L);

	private static final Source ARAXXOR = wiki("Araxxor", "Araxxor", 15396994L);

	private static final List<Boss> BOSSES = Collections.unmodifiableList(Arrays.asList(
		new Boss("Gargoyles", "Grotesque Guardians", wiki("Grotesque Guardians", "Grotesque_Guardians", 15387555L),
			npc(NpcID.GARGBOSS_DUSK_DEATH, "Dusk"), npc(NpcID.GARGBOSS_DAWN_DEATH, "Dawn")),
		new Boss("Cave kraken", "Kraken", wiki("Kraken", "Kraken", 15383981L),
			npc(NpcID.SLAYER_KRAKEN_BOSS, "Kraken")),
		new Boss("Hellhounds", "Cerberus", wiki("Cerberus", "Cerberus", 15397004L),
			npc(NpcID.CERBERUS_ATTACKING, "Cerberus")),
		new Boss("Smoke devils", "Thermonuclear smoke devil",
			wiki("Thermonuclear smoke devil", "Thermonuclear_smoke_devil", 15369525L),
			npc(NpcID.SMOKE_DEVIL_BOSS, "Thermonuclear smoke devil")),
		new Boss("Abyssal demons", "Abyssal Sire", wiki("Abyssal Sire", "Abyssal_Sire", 15392068L),
			npc(NpcID.ABYSSALSIRE_SIRE_APOCALYPSE, "Abyssal Sire")),
		new Boss("Hydras", "Alchemical Hydra", wiki("Alchemical Hydra", "Alchemical_Hydra", 15375803L),
			npc(NpcID.HYDRABOSS_FINALDEATH, "Alchemical Hydra")),
		new Boss("Araxytes", "Araxxor", ARAXXOR, npc(NpcID.ARAXXOR, "Araxxor")),
		new Boss("Spiders", "Araxxor", ARAXXOR, npc(NpcID.ARAXXOR, "Araxxor")),
		new Boss("Gryphons", "Shellbane gryphon", wiki("Shellbane gryphon", "Shellbane_gryphon", 15387213L),
			npc(NpcID.GRYPHON_BOSS, "Shellbane gryphon"))
	));

	/**
	 * The superior Slayer monsters ({@link #SUPERIORS_SOURCE}) of the tasks with a Slayer boss or a Catacombs variant;
	 * hellhounds, cave kraken, spiders and the dragons have none. On any other task the variant is always the standard
	 * monster, so its superiors need no row. A superior only appears on its own task.
	 */
	private static final List<Npc> SUPERIORS = Collections.unmodifiableList(Arrays.asList(
		npc(NpcID.SUPERIOR_GARGOYLE, "Marble gargoyle"),
		npc(NpcID.SUPERIOR_SMOKE_DEVIL, "Nuclear smoke devil"),
		npc(NpcID.SUPERIOR_ABYSSAL_DEMON, "Greater abyssal demon"),
		npc(NpcID.SUPERIOR_HYDRA, "Colossal Hydra"),
		npc(NpcID.SUPERIOR_ARAXYTE, "Dreadborn Araxyte"),
		npc(NpcID.SUPERIOR_GRYPHON, "Dire gryphon"),
		npc(NpcID.SUPERIOR_ABBERANT_SPECTRE, "Abhorrent spectre"),
		npc(NpcID.SUPERIOR_KOUREND_SPECTRE, "Repugnant spectre"),
		npc(NpcID.SUPERIOR_NECHRYAEL, "Nechryarch"),
		npc(NpcID.SUPERIOR_BLOODVELD, "Insatiable Bloodveld"),
		npc(NpcID.SUPERIOR_KOUREND_BLOODVELD, "Insatiable mutated Bloodveld"),
		npc(NpcID.SUPERIOR_JELLY, "Vitreous Jelly"),
		npc(NpcID.SUPERIOR_KOUREND_JELLY, "Vitreous Warped Jelly"),
		npc(NpcID.SUPERIOR_CHILLED_JELLY, "Vitreous Chilled Jelly"),
		npc(NpcID.SUPERIOR_BANSHEE, "Screaming banshee"),
		npc(NpcID.SUPERIOR_KOUREND_BANSHEE, "Screaming twisted banshee")
	));

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

	/** The listed superiors, in a fixed order. */
	static List<Npc> superiors()
	{
		return SUPERIORS;
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

	/** The names of {@code npcs}, in order. */
	static List<String> names(List<Npc> npcs)
	{
		final List<String> names = new ArrayList<>(npcs.size());
		for (Npc npc : npcs)
		{
			names.add(npc.name);
		}
		return names;
	}

	private static Set<String> bossNpcKeys()
	{
		final Set<String> keys = new HashSet<>();
		for (Boss boss : BOSSES)
		{
			keys.addAll(keys(boss.npcs));
		}
		return Collections.unmodifiableSet(keys);
	}

	private static Set<String> keys(List<Npc> npcs)
	{
		final Set<String> keys = new HashSet<>();
		for (Npc npc : npcs)
		{
			keys.add(TaskStatistics.normalize(npc.name));
		}
		return Collections.unmodifiableSet(keys);
	}

	private static Npc npc(int id, String name)
	{
		return new Npc(id, name);
	}

	private static Source wiki(String article, String path, long revision)
	{
		return new Source(article, "https://oldschool.runescape.wiki/w/" + path, revision);
	}
}
