package com.shmayvan.slayerledger;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;

/**
 * The player's overrides of a task's setup tags (V2 plan 4.3): cannon, style and gear focus, each {@code null}
 * when not overridden (the detected value counts, see {@link SetupTags}). Shared by {@link SessionTracker} (the live
 * task), {@link SessionTracker.Summary}, {@link SessionSnapshot} and {@link LedgerRecord}, which keep them as flat
 * fields of their own ({@code cannonOverride}, {@code styleOverride}, {@code gearOverride}). Immutable; an
 * {@link SetupStyle#UNKNOWN} or {@link GearFocus#UNKNOWN} override is no override. No RuneLite dependencies.
 */
@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SetupOverrides
{
	/** Nothing overridden. */
	static final SetupOverrides NONE = new SetupOverrides(null, null, null);

	/** {@code true} for "done with a cannon", {@code false} for "without", {@code null} for no override. */
	Boolean cannon;
	/** The style the player set, or {@code null}; never {@link SetupStyle#UNKNOWN}. */
	SetupStyle style;
	/** The gear focus the player set, or {@code null}; never {@link GearFocus#UNKNOWN}. */
	GearFocus gear;

	/** The given overrides, each {@code null} (or Unknown) for none. */
	static SetupOverrides of(Boolean cannon, SetupStyle style, GearFocus gear)
	{
		final SetupStyle s = style == SetupStyle.UNKNOWN ? null : style;
		final GearFocus g = gear == GearFocus.UNKNOWN ? null : gear;
		return cannon == null && s == null && g == null ? NONE : new SetupOverrides(cannon, s, g);
	}

	/** Whether nothing is overridden. */
	boolean isEmpty()
	{
		return cannon == null && style == null && gear == null;
	}

	/** A copy with the cannon override set ({@code null} clears it). */
	SetupOverrides withCannon(Boolean newCannon)
	{
		return of(newCannon, style, gear);
	}

	/** A copy with the style override set ({@code null} or Unknown clears it). */
	SetupOverrides withStyle(SetupStyle newStyle)
	{
		return of(cannon, newStyle, gear);
	}

	/** A copy with the gear override set ({@code null} or Unknown clears it). */
	SetupOverrides withGear(GearFocus newGear)
	{
		return of(cannon, style, newGear);
	}

	/** The record's overrides as stored. */
	static SetupOverrides from(LedgerRecord record)
	{
		return of(record.getCannonOverride(), record.getStyleOverride(), record.getGearOverride());
	}

	/** The snapshot's overrides as stored. */
	static SetupOverrides from(SessionSnapshot snapshot)
	{
		return of(snapshot.getCannonOverride(), snapshot.getStyleOverride(), snapshot.getGearOverride());
	}

	/** Sets the record's override fields ({@code null} where there is none). */
	void copyTo(LedgerRecord record)
	{
		record.setCannonOverride(cannon);
		record.setStyleOverride(style);
		record.setGearOverride(gear);
	}

	/** Sets the snapshot's override fields ({@code null} where there is none). */
	void copyTo(SessionSnapshot snapshot)
	{
		snapshot.setCannonOverride(cannon);
		snapshot.setStyleOverride(style);
		snapshot.setGearOverride(gear);
	}
}
