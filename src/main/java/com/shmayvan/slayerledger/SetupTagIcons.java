package com.shmayvan.slayerledger;

import java.util.*;
import lombok.*;
import net.runelite.api.gameval.*;

/**
 * The small sprites that stand for a task's setup tags (V2 plan 4.3, DESIGN_PRINCIPLES.md 2.1 and 3.4): the overlay's
 * identity line shows them after the spot's name, and the sidebar can show the same ones. Pure: it says which
 * game image stands for which tag; the caller loads it (an item icon through {@code ItemManager}, a sprite through
 * {@code SpriteManager}) at the size it needs.
 *
 * <p>The images are the game's own: the cannon barrels item for a cannon task; Ice Barrage's spell icon for Barrage;
 * the Ranged, Magic and Attack skill icons for the Ranged, Magic and Melee styles; the Prayer and Strength skill icons
 * for the gear focus. Mixed and Unknown have no sprite: a tag that says nothing gets no image (the tooltips still name
 * it, and the live card's setup toggles show a short word in its place).</p>
 *
 * <p>The sidebar (the live card's setup toggles and the Task history rows) shows the same images, each asked for
 * once on the EDT through {@link SetupImages} (the plugin's {@code loadSetupIcon}: {@code ItemManager} for the cannon
 * barrels, {@code SpriteManager.getSpriteAsync} for the sprites). Each history row's icon comes with how its tag was
 * decided ({@link #tags}): an estimate on a migrated 1.x task, or set by the player.</p>
 */
final class SetupTagIcons
{
	/** Added to a tag's tooltip when its value is an estimate (a migrated 1.x task, {@link SetupTags}). */
	static final String ESTIMATED = " (estimated)";
	/** Added to a tag's tooltip when the player set its value (an override, {@link SetupOverrides}). */
	static final String SET_BY_YOU = " (set by you)";

	/** One tag's image: an item icon or a game sprite (sprite archive, frame 0). */
	@Getter
	@AllArgsConstructor
	enum Icon
	{
		/** A cannon task: the cannon barrels item ({@code ItemID.TWPART3}, "Cannon barrels"). */
		CANNON("Cannon", ItemID.TWPART3, true),
		/** Barrage style: Ice Barrage's spell icon ({@code SpriteID.Magicon2.ICE_BARRAGE}). */
		BARRAGE("Barrage", SpriteID.Magicon2.ICE_BARRAGE, false),
		/** Ranged style: the Ranged skill icon. */
		RANGED("Ranged", SpriteID.Staticons.RANGED, false),
		/** Magic style (other spells): the Magic skill icon. */
		MAGIC("Magic", SpriteID.Staticons.MAGIC, false),
		/** Melee style: the Attack skill icon. */
		MELEE("Melee", SpriteID.Staticons.ATTACK, false),
		/** Prayer gear focus: the Prayer skill icon. */
		PRAYER("Prayer gear", SpriteID.Staticons.PRAYER, false),
		/** Strength gear focus: the Strength skill icon. */
		STRENGTH("Strength gear", SpriteID.Staticons.STRENGTH, false);

		/** What the image stands for, for a tooltip: {@code Cannon}, {@code Barrage}, {@code Prayer gear}. */
		private final String name;
		/** The item id ({@link #isItem}) or the sprite archive id. */
		private final int id;
		/** Whether {@link #getId} is an item id (else a sprite archive, drawn from frame 0). */
		private final boolean item;
	}

	/**
	 * One effective tag's image with how its value was decided, for the sidebar: an estimate (a migrated 1.x task) or
	 * set by the player (never both: an overridden tag is never estimated, {@link SetupTags}). Immutable.
	 */
	@Getter
	@EqualsAndHashCode
	@AllArgsConstructor(access = AccessLevel.PACKAGE)
	static final class Tag
	{
		private final Icon icon;
		/** Whether the value is an estimate: detected on a migrated 1.x task and not set by the player. */
		private final boolean estimated;
		/** Whether the player set the value (an override). */
		private final boolean setByYou;

		/**
		 * The icon's tooltip, plain text: what it stands for ({@link Icon#getName}), then {@link #ESTIMATED} or
		 * {@link #SET_BY_YOU}: {@code Cannon}, {@code Barrage (estimated)}, {@code Prayer gear (set by you)}.
		 */
		String getTooltip()
		{
			return icon.getName() + (estimated ? ESTIMATED : "") + (setByYou ? SET_BY_YOU : "");
		}

		@Override
		public String toString()
		{
			return getTooltip();
		}
	}

	private SetupTagIcons()
	{
	}

	/**
	 * The images for {@code tags}' effective values, in a fixed order (cannon, style, gear focus); empty for
	 * {@code null}. Mixed and Unknown add none.
	 */
	static List<Icon> of(SetupTags tags)
	{
		final List<Tag> shown = tags(tags);
		final List<Icon> icons = new ArrayList<>(shown.size());
		for (Tag tag : shown)
		{
			icons.add(tag.getIcon());
		}
		return Collections.unmodifiableList(icons);
	}

	/**
	 * {@link #of}'s images, each with how its tag was decided (estimated or set by the player), in the same order;
	 * empty for {@code null}.
	 */
	static List<Tag> tags(SetupTags tags)
	{
		if (tags == null)
		{
			return Collections.emptyList();
		}
		final SetupOverrides overrides = tags.getOverrides();
		final List<Tag> shown = new ArrayList<>(3);
		if (tags.isCannon())
		{
			shown.add(new Tag(Icon.CANNON, tags.isCannonEstimated(), overrides.getCannon() != null));
		}
		final Icon style = style(tags.getStyle());
		if (style != null)
		{
			shown.add(new Tag(style, tags.isStyleEstimated(), overrides.getStyle() != null));
		}
		final Icon gear = gear(tags.getGear());
		if (gear != null)
		{
			shown.add(new Tag(gear, tags.isGearEstimated(), overrides.getGear() != null));
		}
		return Collections.unmodifiableList(shown);
	}

	/** The image for an attack style, or {@code null} for Mixed, Unknown and {@code null}. */
	static Icon style(SetupStyle style)
	{
		if (style == null)
		{
			return null;
		}
		switch (style)
		{
			case MELEE:
				return Icon.MELEE;
			case RANGED:
				return Icon.RANGED;
			case BARRAGE:
				return Icon.BARRAGE;
			case MAGIC:
				return Icon.MAGIC;
			default:
				return null;
		}
	}

	/** The image for a gear focus, or {@code null} for Mixed, Unknown and {@code null}. */
	static Icon gear(GearFocus gear)
	{
		if (gear == null)
		{
			return null;
		}
		switch (gear)
		{
			case PRAYER:
				return Icon.PRAYER;
			case STRENGTH:
				return Icon.STRENGTH;
			default:
				return null;
		}
	}
}
