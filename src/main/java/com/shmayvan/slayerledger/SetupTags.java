package com.shmayvan.slayerledger;

import java.util.*;
import lombok.*;

/**
 * A task's setup tags (V2 plan 4.3): cannon, style and gear focus, each with its detected value, whether that
 * value is an estimate (a migrated 1.x task, {@link SetupMigration}) and the player's override ({@link SetupOverrides}).
 * This is the one place that decides a tag's <b>effective</b> value: the override when there is one, else the detected
 * value. An overridden tag is never estimated. The history tooltip shows the effective values, and the grouping,
 * filters and advisor read only them ({@link #isCannon()}, {@link #getStyle()}, {@link #getGear()} and their
 * {@code is...Estimated}), the cannon task name too ({@link LedgerRecord#cannonTask}). Immutable; pure (no RuneLite
 * types).
 *
 * <p>Cannon is never Unknown: a record without a stored detected cannon falls back to its supplies
 * ({@link CannonTasks#fired}), as 1.x classified it. Style and gear may be {@link SetupStyle#UNKNOWN} and
 * {@link GearFocus#UNKNOWN}, which are never estimated.</p>
 */
@EqualsAndHashCode(doNotUseGetters = true)
@ToString(doNotUseGetters = true)
final class SetupTags
{
	/** The last tooltip line when a detected value is an estimate. */
	static final String ESTIMATE_NOTE = "~ estimated: saved before setup was recorded";

	/** The detected cannon (the fallback for a record that stored none), whatever the override. */
	@Getter
	private final boolean detectedCannon;
	/** The detected style (never {@code null}), whatever the override. */
	@Getter
	private final SetupStyle detectedStyle;
	/** The detected gear focus (never {@code null}), whatever the override. */
	@Getter
	private final GearFocus detectedGear;
	private final boolean cannonEstimated;
	private final boolean styleEstimated;
	private final boolean gearEstimated;
	/** The player's overrides (never {@code null}). */
	@Getter
	private final SetupOverrides overrides;
	/**
	 * Whether a detected value or an override was stored (a record saved or migrated by 2.0, or a live session).
	 * {@code false} only for a record built without them (never one read through {@link LedgerStore}), whose tags are
	 * then all fallbacks.
	 */
	@Getter
	private final boolean recorded;

	private SetupTags(boolean detectedCannon, boolean cannonEstimated, SetupStyle detectedStyle,
		boolean styleEstimated, GearFocus detectedGear, boolean gearEstimated, SetupOverrides overrides,
		boolean recorded)
	{
		this.detectedCannon = detectedCannon;
		this.detectedStyle = detectedStyle == null ? SetupStyle.UNKNOWN : detectedStyle;
		this.detectedGear = detectedGear == null ? GearFocus.UNKNOWN : detectedGear;
		this.cannonEstimated = cannonEstimated;
		// Unknown is never an estimate (V2 plan 4.3: the two are treated differently).
		this.styleEstimated = styleEstimated && this.detectedStyle != SetupStyle.UNKNOWN;
		this.gearEstimated = gearEstimated && this.detectedGear != GearFocus.UNKNOWN;
		this.overrides = overrides == null ? SetupOverrides.NONE : overrides;
		this.recorded = recorded;
	}

	/**
	 * Detected tags (none estimated) with the given overrides, as a live session or a new record has them.
	 *
	 * @param style {@code null} is {@link SetupStyle#UNKNOWN}
	 * @param gear  {@code null} is {@link GearFocus#UNKNOWN}
	 */
	static SetupTags detected(boolean cannon, SetupStyle style, GearFocus gear, SetupOverrides overrides)
	{
		return new SetupTags(cannon, false, style, false, gear, false, overrides, true);
	}

	/**
	 * The tags of a migrated task: the estimated cannon and style (a style of Unknown is not an estimate) and an Unknown
	 * gear focus, with no overrides.
	 */
	static SetupTags estimated(boolean cannon, SetupStyle style)
	{
		return new SetupTags(cannon, true, style, true, GearFocus.UNKNOWN, false, SetupOverrides.NONE, true);
	}

	/**
	 * A session's detected tags, as the live task shows them and its record keeps them, from its kill contexts
	 * ({@link SetupDetection}): Cannon when at least half of its {@code taskKills} were cannon kills, the style covering
	 * at least half of its styled kills, and the gear focus of most kills. A session with no kill context counted (one
	 * restored from a 1.x version's file, or before its first kill's context is known) falls back to its fired
	 * cannonballs for the cannon ({@link CannonTasks#fired}), as a record without tags does, with an Unknown style and
	 * gear focus.
	 *
	 * @param supplies  the session's supplies used, or {@code null}
	 * @param kills     the session's kill contexts
	 * @param taskKills the session's task kills that may have a kill context ({@link SessionTracker#contextKills}): its
	 *                  kill count, less the kills a 1.x version counted before a restore
	 */
	static SetupTags forSession(ItemTally supplies, SetupKills kills, int taskKills, SetupOverrides overrides)
	{
		final SetupKills counted = kills == null ? SetupKills.NONE : kills;
		final boolean cannon = counted.getCannonKills() == null ? CannonTasks.fired(supplies)
			: SetupDetection.cannon(counted.getCannonKills(), taskKills);
		return detected(cannon, SetupDetection.taskStyle(counted.getStyleKills()),
			SetupDetection.taskGear(counted.getGearKills()), overrides);
	}

	/**
	 * The record's tags as stored. A tag without a stored detected value is Unknown, except the cannon, which falls back
	 * to the record's supplies. Records read through {@link LedgerStore} were saved or migrated by 2.0 and always store
	 * every detected value.
	 */
	static SetupTags of(LedgerRecord record)
	{
		final Boolean cannon = record.getDetectedCannon();
		final SetupOverrides overrides = SetupOverrides.from(record);
		final boolean recorded = cannon != null || record.getDetectedStyle() != null
			|| record.getDetectedGear() != null || !overrides.isEmpty();
		return new SetupTags(cannon != null ? cannon : CannonTasks.fired(record.getSupplies()),
			Boolean.TRUE.equals(record.getCannonEstimated()), record.getDetectedStyle(),
			Boolean.TRUE.equals(record.getStyleEstimated()), record.getDetectedGear(),
			Boolean.TRUE.equals(record.getGearEstimated()), overrides, recorded);
	}

	/** Sets the record's detected values, estimated flags ({@code null} when not estimated) and overrides. */
	void copyTo(LedgerRecord record)
	{
		record.setDetectedCannon(detectedCannon);
		record.setCannonEstimated(cannonEstimated ? Boolean.TRUE : null);
		record.setDetectedStyle(detectedStyle);
		record.setStyleEstimated(styleEstimated ? Boolean.TRUE : null);
		record.setDetectedGear(detectedGear);
		record.setGearEstimated(gearEstimated ? Boolean.TRUE : null);
		overrides.copyTo(record);
	}

	/** A copy with other overrides ({@code null} for none). */
	SetupTags withOverrides(SetupOverrides newOverrides)
	{
		return new SetupTags(detectedCannon, cannonEstimated, detectedStyle, styleEstimated, detectedGear,
			gearEstimated, newOverrides, true);
	}

	/** The effective cannon: the override, else the detected value. */
	boolean isCannon()
	{
		return overrides.getCannon() != null ? overrides.getCannon() : detectedCannon;
	}

	/** Whether {@link #isCannon()} is an estimate: detected on a migrated task and not overridden. */
	boolean isCannonEstimated()
	{
		return overrides.getCannon() == null && cannonEstimated;
	}

	/** The effective style: the override, else the detected value (never {@code null}). */
	SetupStyle getStyle()
	{
		return overrides.getStyle() != null ? overrides.getStyle() : detectedStyle;
	}

	/** Whether {@link #getStyle()} is an estimate: detected on a migrated task and not overridden. */
	boolean isStyleEstimated()
	{
		return overrides.getStyle() == null && styleEstimated;
	}

	/** The effective gear focus: the override, else the detected value (never {@code null}). */
	GearFocus getGear()
	{
		return overrides.getGear() != null ? overrides.getGear() : detectedGear;
	}

	/** Whether {@link #getGear()} is an estimate: detected on a migrated task and not overridden. */
	boolean isGearEstimated()
	{
		return overrides.getGear() == null && gearEstimated;
	}

	/** Whether the detected cannon is an estimate (a migrated task), whatever the override. */
	boolean isDetectedCannonEstimated()
	{
		return cannonEstimated;
	}

	/** Whether the detected style is an estimate, whatever the override. */
	boolean isDetectedStyleEstimated()
	{
		return styleEstimated;
	}

	/** Whether the detected gear focus is an estimate, whatever the override. */
	boolean isDetectedGearEstimated()
	{
		return gearEstimated;
	}

	/** Whether any detected value is an estimate, overridden or not. */
	boolean hasEstimates()
	{
		return cannonEstimated || styleEstimated || gearEstimated;
	}

	/** "Yes" or "No", as a cannon tag is shown. */
	static String cannonLabel(boolean cannon)
	{
		return cannon ? "Yes" : "No";
	}

	/** A shown value: {@code ~} before an estimate. */
	static String shown(String label, boolean estimated)
	{
		return estimated ? "~" + label : label;
	}

	/**
	 * The tooltip lines of the tags, as plain text: {@code Cannon: ~Yes}, {@code Style: ~Barrage} and
	 * {@code Gear: Unknown}, an override followed by {@code (set by you; detected ~Yes)}, and a last line explaining
	 * {@code ~} when a detected value is an estimate.
	 */
	List<String> lines()
	{
		final List<String> lines = new ArrayList<>();
		lines.add("Cannon: " + shown(cannonLabel(isCannon()), isCannonEstimated())
			+ overridden(overrides.getCannon() != null, shown(cannonLabel(detectedCannon), cannonEstimated)));
		lines.add("Style: " + shown(getStyle().getLabel(), isStyleEstimated())
			+ overridden(overrides.getStyle() != null, shown(detectedStyle.getLabel(), styleEstimated)));
		lines.add("Gear: " + shown(getGear().getLabel(), isGearEstimated())
			+ overridden(overrides.getGear() != null, shown(detectedGear.getLabel(), gearEstimated)));
		if (hasEstimates())
		{
			lines.add(ESTIMATE_NOTE);
		}
		return lines;
	}

	private static String overridden(boolean overridden, String detected)
	{
		return overridden ? " (set by you; detected " + detected + ")" : "";
	}
}
