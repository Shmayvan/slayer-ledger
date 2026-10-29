package com.shmayvan.slayerledger;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * One boss's kill count during a task, from the game's kill count messages ({@link KillCountMessage}). Serialized with
 * Gson in {@link LedgerRecord} and {@link SessionSnapshot}; effectively immutable (no setters). Pure (no RuneLite
 * types). The rules are in {@link BossKillCounts}.
 */
@Getter
@EqualsAndHashCode
@ToString
final class BossKillCount
{
	/** The boss's name as the first counted message gave it, without colour tags. */
	private String boss;
	/** The kill count in the first counted message of the task. */
	private int firstKc;
	/** The kill count in the latest counted message of the task (the boss's kill count now). */
	private int lastKc;
	/** Kills counted on the task: messages with a higher count than the last, while recording. At least 1. */
	private int kills;

	/** For Gson. */
	private BossKillCount()
	{
	}

	BossKillCount(String boss, int firstKc, int lastKc, int kills)
	{
		this.boss = boss;
		this.firstKc = firstKc;
		this.lastKc = lastKc;
		this.kills = kills;
	}
}
