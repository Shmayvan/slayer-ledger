package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Test;

/**
 * The sidebar's setup tag images ({@link SetupImages}): each asked for once for every user, an image handed over
 * at once returned, one handed over later announced, and a drawn item icon repainting every user.
 */
public class SetupImagesTest
{
	/** The images asked for, and the callbacks that hand them over or repaint. */
	private final List<SetupTagIcons.Icon> loads = new ArrayList<>();
	private final List<Consumer<Image>> shows = new ArrayList<>();
	private final List<Runnable> repaints = new ArrayList<>();
	/** The cannon barrels come at once (an item icon, maybe still blank); the sprites later. */
	private final BufferedImage barrels = new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
	private final SetupImages images = new SetupImages((icon, show, repaint) ->
	{
		loads.add(icon);
		shows.add(show);
		repaints.add(repaint);
		if (icon.isItem())
		{
			show.accept(barrels);
		}
	});

	@Test
	public void eachImageIsAskedForOnceForEveryUser()
	{
		List<SetupTagIcons.Icon> toggles = new ArrayList<>();
		List<SetupTagIcons.Icon> history = new ArrayList<>();
		images.addListener(toggles::add, () -> { });
		images.addListener(history::add, () -> { });

		assertNull("none for no image", images.get(null));
		assertNull("a sprite comes later", images.get(SetupTagIcons.Icon.BARRAGE));
		assertNull(images.get(SetupTagIcons.Icon.BARRAGE));
		assertEquals("asked for once", Collections.singletonList(SetupTagIcons.Icon.BARRAGE), loads);

		BufferedImage sprite = new BufferedImage(25, 25, BufferedImage.TYPE_INT_ARGB);
		shows.get(0).accept(sprite);
		assertEquals("every user is told", Collections.singletonList(SetupTagIcons.Icon.BARRAGE), toggles);
		assertEquals(Collections.singletonList(SetupTagIcons.Icon.BARRAGE), history);
		assertSame(sprite, images.get(SetupTagIcons.Icon.BARRAGE));
		assertEquals("kept, never asked for again", 1, loads.size());

		// No image (a sprite the game lacks) is not kept, and announces nothing.
		images.get(SetupTagIcons.Icon.PRAYER);
		shows.get(1).accept(null);
		assertNull(images.get(SetupTagIcons.Icon.PRAYER));
		assertEquals(Arrays.asList(SetupTagIcons.Icon.BARRAGE, SetupTagIcons.Icon.PRAYER), loads);
		assertEquals(1, toggles.size());
	}

	@Test
	public void anImageHandedOverAtOnceIsReturnedAndADrawnItemIconRepaintsEveryUser()
	{
		List<SetupTagIcons.Icon> told = new ArrayList<>();
		int[] repainted = new int[2];
		images.addListener(told::add, () -> repainted[0]++);
		images.addListener(told::add, () -> repainted[1]++);

		assertSame("at once", barrels, images.get(SetupTagIcons.Icon.CANNON));
		assertTrue("returned, not announced", told.isEmpty());
		assertSame(barrels, images.get(SetupTagIcons.Icon.CANNON));
		assertEquals(Collections.singletonList(SetupTagIcons.Icon.CANNON), loads);

		// The blank icon has been drawn (on the client thread): every user repaints.
		repaints.get(0).run();
		assertEquals(Arrays.asList(1, 1), Arrays.asList(repainted[0], repainted[1]));
	}
}
