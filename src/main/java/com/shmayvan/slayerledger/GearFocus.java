package com.shmayvan.slayerledger;

import lombok.*;

/**
 * A task's gear focus setup tag (V2 plan 4.3): whether its kills were made in prayer gear, strength (damage) gear
 * or something between. Saved by name in {@link LedgerRecord} and {@link SessionSnapshot}; a name this build doesn't
 * know reads as {@code null}, which counts as {@link #UNKNOWN}. Pure (no RuneLite types).
 */
@Getter
@RequiredArgsConstructor
enum GearFocus
{
	PRAYER("Prayer"),
	STRENGTH("Strength"),
	MIXED("Mixed"),
	/** Not known: a migrated task, or one saved before gear was detected. */
	UNKNOWN("Unknown");

	/** The focus as shown, for example "Prayer". */
	private final String label;
}
