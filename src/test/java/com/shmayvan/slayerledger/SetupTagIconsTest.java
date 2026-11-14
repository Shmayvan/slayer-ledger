package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.SpriteID;
import org.junit.Test;

/** The setup tags' sprites ({@link SetupTagIcons}). */
public class SetupTagIconsTest
{
	@Test
	public void cannonStyleThenGear()
	{
		assertEquals(Arrays.asList(SetupTagIcons.Icon.CANNON, SetupTagIcons.Icon.BARRAGE, SetupTagIcons.Icon.PRAYER),
			SetupTagIcons.of(SetupTags.detected(true, SetupStyle.BARRAGE, GearFocus.PRAYER, null)));
		assertEquals(Arrays.asList(SetupTagIcons.Icon.RANGED, SetupTagIcons.Icon.STRENGTH),
			SetupTagIcons.of(SetupTags.detected(false, SetupStyle.RANGED, GearFocus.STRENGTH, null)));
		assertEquals(Collections.singletonList(SetupTagIcons.Icon.MELEE),
			SetupTagIcons.of(SetupTags.detected(false, SetupStyle.MELEE, GearFocus.MIXED, null)));
		assertEquals(Collections.singletonList(SetupTagIcons.Icon.MAGIC),
			SetupTagIcons.of(SetupTags.detected(false, SetupStyle.MAGIC, GearFocus.UNKNOWN, null)));
		assertTrue("Mixed and Unknown have no sprite", SetupTagIcons.of(SetupTags.detected(false, SetupStyle.MIXED,
			GearFocus.UNKNOWN, null)).isEmpty());
		assertTrue(SetupTagIcons.of(null).isEmpty());
		assertNull(SetupTagIcons.style(SetupStyle.UNKNOWN));
		assertNull(SetupTagIcons.gear(null));
	}

	@Test
	public void thePlayersOverridesWin()
	{
		SetupTags tags = SetupTags.detected(false, SetupStyle.MELEE, GearFocus.STRENGTH,
			SetupOverrides.NONE.withCannon(true).withStyle(SetupStyle.RANGED));

		assertEquals(Arrays.asList(SetupTagIcons.Icon.CANNON, SetupTagIcons.Icon.RANGED, SetupTagIcons.Icon.STRENGTH),
			SetupTagIcons.of(tags));
	}

	@Test
	public void theGamesOwnImages()
	{
		assertTrue(SetupTagIcons.Icon.CANNON.isItem());
		assertEquals(ItemID.TWPART3, SetupTagIcons.Icon.CANNON.getId());
		assertFalse(SetupTagIcons.Icon.BARRAGE.isItem());
		assertEquals(SpriteID.Magicon2.ICE_BARRAGE, SetupTagIcons.Icon.BARRAGE.getId());
		assertEquals(SpriteID.Staticons.RANGED, SetupTagIcons.Icon.RANGED.getId());
		assertEquals(SpriteID.Staticons.MAGIC, SetupTagIcons.Icon.MAGIC.getId());
		assertEquals(SpriteID.Staticons.ATTACK, SetupTagIcons.Icon.MELEE.getId());
		assertEquals(SpriteID.Staticons.PRAYER, SetupTagIcons.Icon.PRAYER.getId());
		assertEquals(SpriteID.Staticons.STRENGTH, SetupTagIcons.Icon.STRENGTH.getId());
		assertEquals("Prayer gear", SetupTagIcons.Icon.PRAYER.getName());
	}

	@Test
	public void eachTagSaysWhetherItIsAnEstimateOrSetByThePlayer()
	{
		// A migrated 1.x task: an estimated cannon and Barrage, gear Unknown (no icon).
		assertEquals(Arrays.asList("Cannon (estimated)", "Barrage (estimated)"),
			tooltips(SetupTags.estimated(true, SetupStyle.BARRAGE)));
		// Detected, then the style and gear set by the player.
		SetupTags tags = SetupTags.detected(true, SetupStyle.MAGIC, GearFocus.STRENGTH,
			SetupOverrides.NONE.withStyle(SetupStyle.RANGED).withGear(GearFocus.PRAYER));
		assertEquals(Arrays.asList("Cannon", "Ranged (set by you)", "Prayer gear (set by you)"), tooltips(tags));
		// An estimate the player overrode is no longer an estimate.
		SetupTags migrated = SetupTags.estimated(false, SetupStyle.BARRAGE)
			.withOverrides(SetupOverrides.NONE.withCannon(true));
		List<SetupTagIcons.Tag> shown = SetupTagIcons.tags(migrated);
		assertEquals(Arrays.asList("Cannon (set by you)", "Barrage (estimated)"), tooltips(migrated));
		assertTrue(shown.get(0).isSetByYou());
		assertFalse(shown.get(0).isEstimated());
		assertTrue(shown.get(1).isEstimated());
		assertFalse(shown.get(1).isSetByYou());
		// The same images as the overlay's, in the same order.
		assertEquals(SetupTagIcons.of(tags), Arrays.asList(SetupTagIcons.Icon.CANNON, SetupTagIcons.Icon.RANGED,
			SetupTagIcons.Icon.PRAYER));
		assertEquals(new SetupTagIcons.Tag(SetupTagIcons.Icon.CANNON, false, false), SetupTagIcons.tags(tags).get(0));
		assertTrue("Mixed set by the player still has no icon", SetupTagIcons.tags(SetupTags.detected(false,
			SetupStyle.MELEE, GearFocus.PRAYER, SetupOverrides.NONE.withStyle(SetupStyle.MIXED)
				.withGear(GearFocus.MIXED))).isEmpty());
		assertTrue(SetupTagIcons.tags(null).isEmpty());
	}

	/**
	 * The sidebar draws each tag's own game image ({@link SmallItemIcon#applyTag}), as the overlay does, in a 16 px box
	 *: Barrage is Ice Barrage's spell icon, not the Magic skill icon. A sprite arrives later: until then the box
	 * is empty, and the same size.
	 */
	@Test
	public void theSidebarDrawsEachTagsOwnGameImage()
	{
		List<SetupTagIcons.Icon> asked = new ArrayList<>();
		List<Consumer<Image>> pending = new ArrayList<>();
		BufferedImage barrels = new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
		SetupImages images = new SetupImages((icon, show, repaint) ->
		{
			asked.add(icon);
			if (icon.isItem())
			{
				show.accept(barrels);
			}
			else
			{
				pending.add(show);
			}
		});

		JLabel cannon = new JLabel();
		assertTrue(SmallItemIcon.applyTag(images, cannon, SetupTagIcons.Icon.CANNON));
		assertEquals(SmallItemIcon.TAG_SIZE, cannon.getIcon().getIconWidth());
		assertEquals(SmallItemIcon.TAG_SIZE, cannon.getIcon().getIconHeight());
		assertEquals(barrels, ((ImageIcon) cannon.getIcon()).getImage());

		JLabel barrage = new JLabel();
		assertFalse(SmallItemIcon.applyTag(images, barrage, SetupTagIcons.Icon.BARRAGE));
		assertEquals("not arrived: an empty box of the same size", SmallItemIcon.EMPTY_TAG, barrage.getIcon());
		BufferedImage iceBarrage = new BufferedImage(25, 25, BufferedImage.TYPE_INT_ARGB);
		pending.get(0).accept(iceBarrage);
		assertTrue(SmallItemIcon.applyTag(images, barrage, SetupTagIcons.Icon.BARRAGE));
		assertEquals(iceBarrage, ((ImageIcon) barrage.getIcon()).getImage());
		assertEquals(SmallItemIcon.TAG_SIZE, barrage.getIcon().getIconWidth());
		assertEquals("Ice Barrage's own sprite, asked for once", Arrays.asList(SetupTagIcons.Icon.CANNON,
			SetupTagIcons.Icon.BARRAGE), asked);
	}

	private static List<String> tooltips(SetupTags tags)
	{
		List<String> tooltips = new ArrayList<>();
		for (SetupTagIcons.Tag tag : SetupTagIcons.tags(tags))
		{
			tooltips.add(tag.getTooltip());
		}
		return tooltips;
	}
}
