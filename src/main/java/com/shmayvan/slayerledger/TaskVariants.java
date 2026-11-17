package com.shmayvan.slayerledger;

import java.util.*;
import lombok.*;

/**
 * A task's variant and boss flag (V2 plan 4.4), decided from its kills per NPC name ({@link NpcKillTally}). Pure
 * (no RuneLite types).
 *
 * <p><b>Kinds.</b> A task's kinds are the NPC kinds it can be done on besides its own monsters: its Catacombs variant
 * ({@link CatacombsVariants}, named as the 1.x records name it, for example "Deviant spectres") and its Slayer boss
 * ({@link SlayerBosses}, for example "Grotesque Guardians" on a gargoyle task). A kind's kills are the tallied kills of
 * its most-killed NPC name: every NPC of a kind dies in each of its kills (Dusk and Dawn), so a Grotesque Guardians kill
 * counts once.</p>
 *
 * <p><b>Variant.</b> The kind with the most task kills in the tally, leaving out superiors
 * ({@link SlayerBosses#isSuperiorName}): a kind's kills against the task's own monsters' kills, which are the tally's other
 * kills, but never more than the task kills (the game's task counter) the kinds and superiors leave over. That bound
 * keeps other monsters out (an Ankou on the way, death spawns, a boss's minions, anything else caught in a barrage),
 * and the tally keeps a boss whose kill takes off two task kills (the Grotesque Guardians with the Double Trouble reward)
 * from counting its second one as a gargoyle. Task kills the tally missed (a death not seen, or one before the tally
 * existed) count for neither; in older versions a Catacombs variant needed more than half of the task kills, so they counted
 * as the standard monster. A tie, no task kills, or a task without a kind is the standard monster ({@code null}).</p>
 *
 * <p><b>Boss flag.</b> Kills of the task's Slayer boss, any number, set it: in the tally, or for a record without a
 * tally (one saved by 1.x), in its boss kill counts ({@link BossKillCounts}). Boss tasks (task type Boss) are their own
 * pool, so they have neither a variant nor the flag.</p>
 */
final class TaskVariants
{
	/** What a live task's name ends with when it has boss kills but its name doesn't say so: {@code " + boss"}. */
	static final String BOSS_SUFFIX = " + boss";

	/** The variant and boss flag decided for one task. Immutable. */
	@EqualsAndHashCode
	@ToString
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	static final class Decision
	{
		/** No variant and no boss kills, for no task. */
		static final Decision NONE = new Decision(null, null, false, false);

		/** The task the decision is for, or {@code null} for {@link #NONE}. */
		@Getter
		private final String task;
		/** The variant's name (a kind's), or {@code null} for the standard monster. */
		@Getter
		private final String variant;
		private final boolean variantIsBoss;
		/** Whether the kills include the task's Slayer boss. */
		@Getter
		private final boolean bossKills;

		/** Whether the variant is the task's Slayer boss. */
		boolean isVariantBoss()
		{
			return variantIsBoss;
		}

		/**
		 * This decision when it is for {@code taskName} (matched by {@link TaskStatistics#normalize}), else
		 * {@link #NONE}. Guards a decision read in a separate call from the task it is shown with, which could see another
		 * session (the overlay's Task row).
		 */
		Decision forTask(String taskName)
		{
			final String key = TaskStatistics.normalize(task);
			return key != null && key.equals(TaskStatistics.normalize(taskName)) ? this : NONE;
		}

		/**
		 * The name a live task is shown under, as the saved one is ({@link LedgerRecord#groupName}): the variant's name
		 * when there is one, else {@code taskName}, with {@link CannonTasks#SUFFIX} when {@code cannon}, then
		 * {@link #BOSS_SUFFIX} when there are boss kills and the name isn't the boss's own. For example
		 * "Grotesque Guardians", "Deviant spectres (cannon)" or "Cave kraken + boss".
		 */
		String displayName(String taskName, boolean cannon)
		{
			final String name = CannonTasks.name(variant != null ? variant : taskName, cannon);
			return name != null && bossKills && !variantIsBoss ? name + BOSS_SUFFIX : name;
		}
	}

	/** One kind of a task: its name, its NPC names' {@link TaskStatistics#normalize} keys, whether it is a boss. */
	private static final class Kind
	{
		private final String name;
		private final List<String> npcKeys;
		private final boolean boss;

		private Kind(String name, List<String> npcNames, boolean boss)
		{
			this.name = name;
			final List<String> keys = new ArrayList<>(npcNames.size());
			for (String npcName : npcNames)
			{
				keys.add(TaskStatistics.normalize(npcName));
			}
			this.npcKeys = Collections.unmodifiableList(keys);
			this.boss = boss;
		}
	}

	/**
	 * Each task's kinds by its {@link TaskStatistics#normalize}d name, worked out once (the overlay asks every frame):
	 * its Catacombs variant, then its Slayer boss.
	 */
	private static final Map<String, List<Kind>> KINDS = kindsByTask();

	private TaskVariants()
	{
	}

	/**
	 * The variant and boss flag of a task from its kills (see the class rules).
	 *
	 * @param taskName   the task's name, or {@code null} when unknown (no kinds)
	 * @param creatureId the task's creature key ({@link SessionTracker#creatureKey}); boss tasks are below
	 *                   {@link SessionTracker#NO_CREATURE}
	 * @param tally      the kills per NPC name ({@link NpcKillTally}), or {@code null} for none
	 * @param taskKills  the task kills, as the game's task counter counted them
	 */
	static Decision decide(String taskName, int creatureId, List<NpcKills> tally, int taskKills)
	{
		final List<Kind> kinds = kinds(taskName, creatureId);
		if (kinds.isEmpty())
		{
			return Decision.NONE;
		}
		// Each kind's kills per NPC name, added up over a name's entries (a file's tally may repeat one), without
		// copying the tally: the overlay asks every frame.
		final long[][] npcKills = new long[kinds.size()][];
		for (int i = 0; i < kinds.size(); i++)
		{
			npcKills[i] = new long[kinds.get(i).npcKeys.size()];
		}
		long superiors = 0;
		long others = 0;
		for (NpcKills entry : tally == null ? Collections.<NpcKills>emptyList() : tally)
		{
			final String key = entry == null || entry.getKills() <= 0 ? null : TaskStatistics.normalize(entry.getName());
			if (key == null)
			{
				continue;
			}
			if (addToKind(kinds, npcKills, key, entry.getKills()))
			{
				continue;
			}
			if (SlayerBosses.isSuperiorName(key))
			{
				superiors += entry.getKills();
			}
			else
			{
				others += entry.getKills();
			}
		}
		// A kind's kills are its most-killed NPC's: all of its NPCs die in each of its kills.
		final long[] kindKills = new long[kinds.size()];
		boolean bossKills = false;
		long kindTotal = 0;
		int best = -1;
		boolean tie = false;
		for (int i = 0; i < kinds.size(); i++)
		{
			for (long kills : npcKills[i])
			{
				kindKills[i] = Math.max(kindKills[i], kills);
			}
			bossKills |= kinds.get(i).boss && kindKills[i] > 0;
			kindTotal += kindKills[i];
			if (best < 0 || kindKills[i] > kindKills[best])
			{
				best = i;
				tie = false;
			}
			else if (kindKills[i] == kindKills[best])
			{
				tie = true;
			}
		}
		// The task's own monsters: the tally's other kills, at most what the task kills leave over.
		final long standard = Math.min(others, Math.max(0, taskKills - kindTotal - superiors));
		final boolean variant = taskKills > 0 && !tie && kindKills[best] > standard;
		final Kind kind = kinds.get(best);
		return new Decision(taskName.trim(), variant ? kind.name : null, variant && kind.boss, bossKills);
	}

	/**
	 * A saved record's boss flag (see the class rules): from its kills per NPC name when it has any, else from its boss
	 * kill counts; {@code false} for a boss task or a task without a Slayer boss.
	 */
	static boolean bossKills(LedgerRecord record)
	{
		final SlayerBosses.Boss boss = record == null || isBossTask(record.getCreatureId()) ? null
			: SlayerBosses.forTask(record.getMonster());
		if (boss == null)
		{
			return false;
		}
		final List<NpcKills> tally = NpcKillTally.sanitize(record.getNpcKills());
		if (!tally.isEmpty())
		{
			for (String npcName : boss.getNpcNames())
			{
				if (NpcKillTally.killsOf(tally, npcName) > 0)
				{
					return true;
				}
			}
			return false;
		}
		for (BossKillCount count : BossKillCounts.sanitize(record.getBossKillCounts()))
		{
			if (BossKillCounts.sameName(count.getBoss(), boss.getName()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Gives a record without a boss flag its flag ({@link #bossKills(LedgerRecord)}), in place; a record that has one
	 * keeps it. {@link SetupMigration} calls it for a 1.x record when it is read. A version 3 record written before the flag existed
	 * keeps a null flag on file; read it through {@link #hasBossKills}. Its variant is left as saved: a 1.x
	 * record's is a Catacombs variant's name or none, which this version names the same way.
	 *
	 * @return whether the record was given its flag
	 */
	static boolean migrate(LedgerRecord record)
	{
		if (record == null || record.getBossKills() != null)
		{
			return false;
		}
		record.setBossKills(bossKills(record));
		return true;
	}

	/**
	 * Whether {@code record}'s task included a kill of its Slayer boss: its saved flag, or, for a record saved without
	 * one (a version 3 record written before the flag existed), the flag worked out from its kills by name and boss kill
	 * counts, without changing the record. Grouping and the advisor read the flag only through this.
	 */
	static boolean hasBossKills(LedgerRecord record)
	{
		if (record == null)
		{
			return false;
		}
		final Boolean saved = record.getBossKills();
		return saved != null ? saved : bossKills(record);
	}

	/** The name of the Slayer boss of {@code taskName} ({@link SlayerBosses#forTask}), or {@code null} for none. */
	static String bossName(String taskName)
	{
		final SlayerBosses.Boss boss = SlayerBosses.forTask(taskName);
		return boss == null ? null : boss.getName();
	}

	/**
	 * Whether {@code npcName} is an NPC name a variant decision reads: a Catacombs variant's, a Slayer boss's or a
	 * listed superior's. The tally always takes these ({@link NpcKillTally#MAX_NAMES}).
	 */
	static boolean isDecidingNpcName(String npcName)
	{
		return CatacombsVariants.isVariantNpcName(npcName) || SlayerBosses.isBossNpcName(npcName)
			|| SlayerBosses.isSuperiorName(npcName);
	}

	/** The task's kinds, in a fixed order; none for a boss task or an unknown one. */
	private static List<Kind> kinds(String taskName, int creatureId)
	{
		final String key = isBossTask(creatureId) ? null : TaskStatistics.normalize(taskName);
		final List<Kind> kinds = key == null ? null : KINDS.get(key);
		return kinds == null ? Collections.emptyList() : kinds;
	}

	private static Map<String, List<Kind>> kindsByTask()
	{
		final Map<String, List<Kind>> byTask = new HashMap<>();
		for (CatacombsVariants.Variant variant : CatacombsVariants.all())
		{
			byTask.computeIfAbsent(TaskStatistics.normalize(variant.getTask()), task -> new ArrayList<>(2))
				.add(new Kind(variant.getName(), Collections.singletonList(variant.getNpcName()), false));
		}
		for (SlayerBosses.Boss boss : SlayerBosses.all())
		{
			byTask.computeIfAbsent(TaskStatistics.normalize(boss.getTask()), task -> new ArrayList<>(2))
				.add(new Kind(boss.getName(), boss.getNpcNames(), true));
		}
		final Map<String, List<Kind>> kinds = new HashMap<>();
		for (Map.Entry<String, List<Kind>> entry : byTask.entrySet())
		{
			kinds.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
		}
		return Collections.unmodifiableMap(kinds);
	}

	/** Adds {@code kills} of the NPC name {@code key} to its kind's, if it is one of {@code kinds}' NPCs. */
	private static boolean addToKind(List<Kind> kinds, long[][] npcKills, String key, int kills)
	{
		for (int i = 0; i < kinds.size(); i++)
		{
			final int npc = kinds.get(i).npcKeys.indexOf(key);
			if (npc >= 0)
			{
				npcKills[i][npc] += kills;
				return true;
			}
		}
		return false;
	}

	private static boolean isBossTask(Integer creatureId)
	{
		return creatureId != null && creatureId < SessionTracker.NO_CREATURE;
	}
}
