package com.shmayvan.slayerledger;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.NpcID;

/**
 * The Catacombs of Kourend variants of Slayer task monsters, for example Deviant spectres on an Aberrant spectres
 * task, and the rule that decides from a session's kills ({@link NpcKillTally}) whether a task was done on its variant.
 * Pure (no RuneLite types; the ids are the RuneLite 1.13.4 gameval {@code NpcID} constants, plain ints).
 *
 * <p>The table is curated: one entry per task with a Catacombs-only variant, keyed by the NPC id the game uses in the
 * Catacombs ({@code NpcID.KOUREND_*}, ids 7272 to 7279). Each id and its NPC name were checked against the RuneLite
 * 1.13.4 gameval {@code NpcID} constants and their javadoc names; {@code CatacombsVariantsTest} checks the constants
 * again. Some variants also live outside the Catacombs under the same name with other ids (Greater Nechryael in the
 * Wilderness Slayer Cave, Mutated Bloodveld in Meiyerditch, Brutal dragons in Lithkren and Tapoyauik); those are not
 * Catacombs variants, so {@link #tallyName} leaves them out of the tally.</p>
 *
 * <p><b>Majority rule.</b> A task counts as its variant when the session's tallied kills of the variant are more than
 * half of the session's task kills (the kills the game's task counter counted, {@link SessionTracker#getKills}). Other
 * tallied kills (an Ankou on the way, Greater Nechryael death spawns, other monsters in a barrage) don't count either
 * way. A tie, no task kills, or a task without a variant is the standard monster.</p>
 */
final class CatacombsVariants
{
	/** One task's Catacombs variant. Immutable. */
	static final class Variant
	{
		private final String task;
		private final int npcId;
		private final String npcName;
		private final String name;

		private Variant(String task, int npcId, String npcName, String name)
		{
			this.task = task;
			this.npcId = npcId;
			this.npcName = npcName;
			this.name = name;
		}

		/** The Slayer task's name, as the game's task table gives it (for example "Aberrant spectres"). */
		String getTask()
		{
			return task;
		}

		/** The variant's NPC id in the Catacombs. */
		int getNpcId()
		{
			return npcId;
		}

		/** The variant's NPC name, as the game gives it (for example "Deviant spectre"). */
		String getNpcName()
		{
			return npcName;
		}

		/** The name a variant task is shown and grouped under, like a task name (for example "Deviant spectres"). */
		String getName()
		{
			return name;
		}
	}

	private static final List<Variant> VARIANTS = Collections.unmodifiableList(Arrays.asList(
		new Variant("Aberrant spectres", NpcID.KOUREND_SPECTRE, "Deviant spectre", "Deviant spectres"),
		new Variant("Nechryael", NpcID.KOUREND_NECHRYAEL, "Greater Nechryael", "Greater nechryael"),
		new Variant("Bloodveld", NpcID.KOUREND_BLOODVELD, "Mutated Bloodveld", "Mutated bloodveld"),
		new Variant("Jellies", NpcID.KOUREND_JELLY, "Warped Jelly", "Warped jellies"),
		new Variant("Banshees", NpcID.KOUREND_BANSHEE, "Twisted Banshee", "Twisted banshees"),
		new Variant("Black dragons", NpcID.KOUREND_BLACK_DRAGON, "Brutal black dragon", "Brutal black dragons"),
		new Variant("Blue dragons", NpcID.KOUREND_BLUE_DRAGON, "Brutal blue dragon", "Brutal blue dragons"),
		new Variant("Red dragons", NpcID.KOUREND_RED_DRAGON, "Brutal red dragon", "Brutal red dragons")
	));

	private CatacombsVariants()
	{
	}

	/** Every variant in the table, in a fixed order. */
	static List<Variant> all()
	{
		return VARIANTS;
	}

	/** The task's variant ({@code taskName} matched by {@link TaskStatistics#normalize}), or {@code null} for none. */
	static Variant forTask(String taskName)
	{
		String key = TaskStatistics.normalize(taskName);
		if (key == null)
		{
			return null;
		}
		for (Variant variant : VARIANTS)
		{
			if (key.equals(TaskStatistics.normalize(variant.task)))
			{
				return variant;
			}
		}
		return null;
	}

	/** Whether {@code npcName} (matched by {@link TaskStatistics#normalize}) is a Catacombs variant's NPC name. */
	static boolean isVariantNpcName(String npcName)
	{
		String key = TaskStatistics.normalize(npcName);
		if (key == null)
		{
			return false;
		}
		for (Variant variant : VARIANTS)
		{
			if (key.equals(TaskStatistics.normalize(variant.npcName)))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The name to tally a dead NPC's kill under: its trimmed name, or {@code null} (not tallied) for a blank name or an
	 * NPC outside the Catacombs that has a variant's name (so it never counts as the variant).
	 */
	static String tallyName(int npcId, String npcName)
	{
		String key = TaskStatistics.normalize(npcName);
		if (key == null)
		{
			return null;
		}
		for (Variant variant : VARIANTS)
		{
			if (key.equals(TaskStatistics.normalize(variant.npcName)) && npcId != variant.npcId)
			{
				return null;
			}
		}
		return npcName.trim();
	}

	/**
	 * The variant the session's kills say {@code taskName} was done on ({@link Variant#getName}), or {@code null} for
	 * the standard monster: a task without a variant, no task kills, or the variant's tallied kills not more than half
	 * of the task kills.
	 *
	 * @param tally     the session's kills per NPC name ({@link NpcKillTally}), or {@code null} for none
	 * @param taskKills the session's task kills, as the game's task counter counted them
	 */
	static String decide(String taskName, List<NpcKills> tally, int taskKills)
	{
		Variant variant = forTask(taskName);
		if (variant == null || taskKills <= 0)
		{
			return null;
		}
		long variantKills = NpcKillTally.killsOf(tally, variant.npcName);
		return variantKills * 2 > taskKills ? variant.name : null;
	}

	/**
	 * Which records the live task's statistics use: {@link TaskSelection#ANY} while the kills don't decide (a task
	 * without a variant, or no task kills yet), else the version {@link #decide} gives for the same numbers.
	 */
	static TaskSelection selection(String taskName, List<NpcKills> tally, int taskKills)
	{
		if (forTask(taskName) == null || taskKills <= 0)
		{
			return TaskSelection.ANY;
		}
		return TaskSelection.of(decide(taskName, tally, taskKills));
	}
}
