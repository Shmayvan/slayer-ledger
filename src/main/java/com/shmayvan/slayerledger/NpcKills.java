package com.shmayvan.slayerledger;

import lombok.*;

/**
 * The player's kills of one NPC name in a session. Serialized with Gson in {@link SessionSnapshot}; effectively
 * immutable (no setters). Pure (no RuneLite types). The rules are in {@link NpcKillTally}.
 */
@Getter
@EqualsAndHashCode
@ToString
@AllArgsConstructor(access = AccessLevel.PACKAGE)
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
}
