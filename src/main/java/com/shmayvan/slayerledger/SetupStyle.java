package com.shmayvan.slayerledger;

import lombok.*;

/**
 * A task's attack style setup tag (V2 plan 4.3): how most of its kills were made. Saved by name in
 * {@link LedgerRecord} and {@link SessionSnapshot}; a name this build doesn't know reads as {@code null}, which counts
 * as {@link #UNKNOWN}. Pure (no RuneLite types).
 */
@Getter
@RequiredArgsConstructor
enum SetupStyle
{
	MELEE("Melee"),
	RANGED("Ranged"),
	/** Ancient Magicks' multi-target spells: Burst and Barrage share their cast animations. */
	BARRAGE("Barrage"),
	/** Any other spell, including Rush and Blitz. */
	MAGIC("Magic"),
	/** No style did most of the work: a tie, or none reached half. */
	MIXED("Mixed"),
	/** Nothing to tell it from: no styled kill, or (on a migrated task) no combat XP left to estimate from. */
	UNKNOWN("Unknown");

	/** The style as shown, for example "Barrage". */
	private final String label;
}
