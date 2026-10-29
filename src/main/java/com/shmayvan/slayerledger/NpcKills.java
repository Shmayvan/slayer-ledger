package com.shmayvan.slayerledger;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * The player's kills of one NPC name in a session. Serialized with Gson in {@link SessionSnapshot}; effectively
 * immutable (no setters). Pure (no RuneLite types). The rules are in {@link NpcKillTally}.
 */
@Getter
@EqualsAndHashCode
@ToString
final class NpcKills
{
	/** The NPC's name as the game gives it, trimmed (for example "Deviant spectre"). */
	private String name;
	/** Deaths of NPCs with this name that the player had damaged; at least 1. */
	private int kills;

	/** For Gson. */
	private NpcKills()
	{
	}

	NpcKills(String name, int kills)
	{
		this.name = name;
		this.kills = kills;
	}
}
