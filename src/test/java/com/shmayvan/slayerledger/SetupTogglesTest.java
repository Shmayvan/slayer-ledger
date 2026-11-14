package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.awt.AWTEvent;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import org.junit.Test;

/**
 * The live card's setup toggles on their own: the word drawn for a value without a sprite, the override mark,
 * the tooltip's lines, the images asked for once each, a click on an open menu's toggle and the row's width in the
 * RuneScape fonts. The plugin-level behaviour (the menus, the overrides reaching the session or record) is in
 * {@link HarnessSetupDetectionTest}.
 */
public class SetupTogglesTest
{
	/** The images asked for, and the callbacks that hand them over. */
	private final List<SetupTagIcons.Icon> loads = new ArrayList<>();
	private final List<Consumer<Image>> pending = new ArrayList<>();
	private final SetupToggles toggles = new SetupToggles(new SetupImages((icon, show, repaint) ->
	{
		loads.add(icon);
		pending.add(show);
	}));

	private static SetupTags tags(boolean cannon, SetupStyle style, GearFocus gear, SetupOverrides overrides)
	{
		return SetupTags.detected(cannon, style, gear, overrides);
	}

	private AbstractButton toggle(SetupToggles.Tag tag)
	{
		return SwingLookup.find(toggles.getComponent(), tag.key(), AbstractButton.class);
	}

	@Test
	public void aValueWithoutASpriteIsAShortWordAndUnknownIsAQuestionMark()
	{
		SetupTags unknown = tags(false, SetupStyle.UNKNOWN, GearFocus.UNKNOWN, null);
		assertEquals("No", SetupToggles.placeholder(SetupToggles.Tag.CANNON, unknown));
		assertEquals("?", SetupToggles.placeholder(SetupToggles.Tag.STYLE, unknown));
		assertEquals("?", SetupToggles.placeholder(SetupToggles.Tag.GEAR, unknown));
		SetupTags mixed = tags(true, SetupStyle.MIXED, GearFocus.MIXED, null);
		assertNull("the cannon barrels", SetupToggles.placeholder(SetupToggles.Tag.CANNON, mixed));
		assertEquals("Mix", SetupToggles.placeholder(SetupToggles.Tag.STYLE, mixed));
		assertEquals("Mix", SetupToggles.placeholder(SetupToggles.Tag.GEAR, mixed));
		SetupTags sprites = tags(false, SetupStyle.BARRAGE, GearFocus.PRAYER, null);
		assertNull(SetupToggles.placeholder(SetupToggles.Tag.STYLE, sprites));
		assertNull(SetupToggles.placeholder(SetupToggles.Tag.GEAR, sprites));
		assertEquals(SetupTagIcons.Icon.BARRAGE, SetupToggles.Tag.STYLE.icon(sprites));
		assertEquals(SetupTagIcons.Icon.PRAYER, SetupToggles.Tag.GEAR.icon(sprites));
		assertEquals(SetupTagIcons.Icon.CANNON, SetupToggles.Tag.CANNON.icon(mixed));
	}

	@Test
	public void theTooltipSaysTheTagItsValueWhoSetItAndAnEstimate()
	{
		SetupKills kills = SetupKills.NONE.withKill(false, SetupStyle.MELEE, GearFocus.STRENGTH, 8);
		SetupTags detected = SetupTags.forSession(null, kills, 1, null);
		assertEquals("<html>Style: Melee<br>Detected<br>Kills by style: Melee 1<br>" + SetupToggles.HINT + "</html>",
			SetupToggles.tooltip(SetupToggles.Tag.STYLE, detected, kills, 1));
		assertEquals("<html>Gear: Strength<br>Detected<br>Kills by gear: Strength 1<br>Prayer bonus: +8<br>"
			+ SetupToggles.HINT + "</html>", SetupToggles.tooltip(SetupToggles.Tag.GEAR, detected, kills, 1));

		SetupTags set = detected.withOverrides(SetupOverrides.of(true, null, null));
		assertEquals("<html>Cannon: Yes<br>Set by you; detected No<br>Cannon kills: 0 of 1<br>" + SetupToggles.HINT
			+ "</html>", SetupToggles.tooltip(SetupToggles.Tag.CANNON, set, kills, 1));
		assertEquals("Yes, set by you", SetupToggles.description(SetupToggles.Tag.CANNON, set));
		assertEquals("Melee", SetupToggles.description(SetupToggles.Tag.STYLE, set));

		// A migrated task's estimate: ~ before the value and the note, also when the player set it.
		SetupTags estimated = SetupTags.estimated(true, SetupStyle.BARRAGE);
		assertEquals("<html>Style: ~Barrage<br>Detected<br>" + SetupTags.ESTIMATE_NOTE + "<br>" + SetupToggles.HINT
			+ "</html>", SetupToggles.tooltip(SetupToggles.Tag.STYLE, estimated, SetupKills.NONE, 0));
		assertEquals("<html>Style: Melee<br>Set by you; detected ~Barrage<br>" + SetupTags.ESTIMATE_NOTE + "<br>"
			+ SetupToggles.HINT + "</html>", SetupToggles.tooltip(SetupToggles.Tag.STYLE,
			estimated.withOverrides(SetupOverrides.of(null, SetupStyle.MELEE, null)), SetupKills.NONE, 0));
		assertEquals("Unknown is no estimate", "<html>Gear: Unknown<br>Detected<br>" + SetupToggles.HINT + "</html>",
			SetupToggles.tooltip(SetupToggles.Tag.GEAR, estimated, SetupKills.NONE, 0));
	}

	@Test
	public void anOverrideHasTheCornerMarkAndUnknownItsQuestionMarkInTheHintColour()
	{
		toggles.show(tags(false, SetupStyle.UNKNOWN, GearFocus.UNKNOWN, SetupOverrides.of(true, null, null)),
			SetupKills.NONE, 0);
		BufferedImage cannon = paint(toggle(SetupToggles.Tag.CANNON).getIcon());
		BufferedImage gear = paint(toggle(SetupToggles.Tag.GEAR).getIcon());
		assertEquals("the mark", LedgerTheme.HEADING.getRGB(), cannon.getRGB(cannon.getWidth() - 1, 0));
		assertEquals("no mark", 0, gear.getRGB(gear.getWidth() - 1, 0) >>> 24);
		assertTrue("the ? is drawn in the hint colour", hasColour(gear, LedgerTheme.HINT.getRGB()));
		assertEquals("Unknown", ((ImageIcon) toggle(SetupToggles.Tag.GEAR).getIcon()).getDescription());
	}

	@Test
	public void eachImageIsAskedForOnceAndDrawnWhenItArrives()
	{
		SetupTags barrage = tags(false, SetupStyle.BARRAGE, GearFocus.PRAYER, null);
		toggles.show(barrage, SetupKills.NONE, 0);
		assertEquals(Arrays.asList(SetupTagIcons.Icon.BARRAGE, SetupTagIcons.Icon.PRAYER), loads);
		assertNull("not arrived yet", ((ImageIcon) toggle(SetupToggles.Tag.STYLE).getIcon()).getImage());

		BufferedImage sprite = new BufferedImage(25, 25, BufferedImage.TYPE_INT_ARGB);
		pending.get(0).accept(sprite);
		Icon icon = toggle(SetupToggles.Tag.STYLE).getIcon();
		assertSame(sprite, ((ImageIcon) icon).getImage());
		assertEquals("fitted into the 18 by 16 slot", Arrays.asList(SetupToggles.SLOT_WIDTH, SetupToggles.SLOT_HEIGHT),
			Arrays.asList(icon.getIconWidth(), icon.getIconHeight()));

		// Back and forth: the images are kept, never asked for again; the same tags redraw nothing.
		int builds = toggles.getBuildCount();
		toggles.show(barrage, SetupKills.NONE, 0);
		assertEquals(builds, toggles.getBuildCount());
		toggles.show(tags(false, SetupStyle.MELEE, GearFocus.PRAYER, null), SetupKills.NONE, 0);
		toggles.show(barrage, SetupKills.NONE, 0);
		assertEquals(Arrays.asList(SetupTagIcons.Icon.BARRAGE, SetupTagIcons.Icon.PRAYER, SetupTagIcons.Icon.MELEE),
			loads);
		assertSame(sprite, ((ImageIcon) toggle(SetupToggles.Tag.STYLE).getIcon()).getImage());
		assertEquals(builds + 2, toggles.getBuildCount());
	}

	/**
	 * A click on the toggle of an open menu only closes it: Swing closes an open menu on a mouse press outside it,
	 * before the component under the mouse gets the press ({@code BasicPopupMenuUI}'s mouse grabber), and the toggle
	 * fires on the release, which used to open the menu again. Headless, no menu can show, so a stand-in for the grabber
	 * closes the menu the test calls open on a press, as Swing does; the toggle gets real mouse events. Whether Swing
	 * does the same in the client is the in-game check. Opening fills the menu, so an empty menu was not opened.
	 */
	@Test
	public void aClickOnTheToggleOfAnOpenMenuOnlyClosesIt() throws Exception
	{
		final AbstractButton style = toggle(SetupToggles.Tag.STYLE);
		final JPopupMenu menu = style.getComponentPopupMenu();
		final boolean[] open = new boolean[1];
		final AWTEventListener grabber = event ->
		{
			if (open[0] && event.getID() == MouseEvent.MOUSE_PRESSED)
			{
				open[0] = false;
				for (PopupMenuListener listener : menu.getPopupMenuListeners())
				{
					listener.popupMenuWillBecomeInvisible(new PopupMenuEvent(menu));
					listener.popupMenuCanceled(new PopupMenuEvent(menu));
				}
			}
		};
		Toolkit.getDefaultToolkit().addAWTEventListener(grabber, AWTEvent.MOUSE_EVENT_MASK);
		try
		{
			SwingUtilities.invokeAndWait(() ->
			{
				toggles.show(tags(false, SetupStyle.MELEE, GearFocus.PRAYER, null), SetupKills.NONE, 0);
				final JComponent row = toggles.getComponent();
				row.setSize(row.getPreferredSize());
				row.doLayout();

				click(style, 1_000);
				assertTrue("a click opens it", menu.getComponentCount() > 0);

				// Open: the press closes it, and the click it starts must not open it again.
				open[0] = true;
				menu.removeAll();
				click(style, 2_000);
				assertFalse("the press closed it", open[0]);
				assertEquals("not opened again", 0, menu.getComponentCount());
				click(style, 3_000);
				assertTrue("the next click opens it", menu.getComponentCount() > 0);

				// A press that closed it and slid off the toggle (no click) leaves nothing behind for the next click.
				open[0] = true;
				menu.removeAll();
				pressAndSlideOff(style, 4_000);
				assertEquals("no click", 0, menu.getComponentCount());
				click(style, 5_000);
				assertTrue(menu.getComponentCount() > 0);

				open[0] = true;
				menu.removeAll();
				pressAndSlideOff(style, 6_000);
			});
			// Nor for Space, a click with no mouse event.
			SwingUtilities.invokeAndWait(style::doClick);
			assertTrue("Space opens it", menu.getComponentCount() > 0);
		}
		finally
		{
			Toolkit.getDefaultToolkit().removeAWTEventListener(grabber);
		}
	}

	/** A left click on {@code button}, as Swing dispatches it: a press, then a release over it, at {@code when}. */
	private static void click(AbstractButton button, long when)
	{
		button.dispatchEvent(mouse(button, MouseEvent.MOUSE_PRESSED, when, InputEvent.BUTTON1_DOWN_MASK,
			MouseEvent.BUTTON1));
		button.dispatchEvent(mouse(button, MouseEvent.MOUSE_RELEASED, when + 50, 0, MouseEvent.BUTTON1));
	}

	/** A left press on {@code button}, then the mouse leaves it and is released elsewhere: no click. */
	private static void pressAndSlideOff(AbstractButton button, long when)
	{
		button.dispatchEvent(mouse(button, MouseEvent.MOUSE_PRESSED, when, InputEvent.BUTTON1_DOWN_MASK,
			MouseEvent.BUTTON1));
		button.dispatchEvent(mouse(button, MouseEvent.MOUSE_EXITED, when + 20, InputEvent.BUTTON1_DOWN_MASK,
			MouseEvent.NOBUTTON));
		button.dispatchEvent(mouse(button, MouseEvent.MOUSE_RELEASED, when + 50, 0, MouseEvent.BUTTON1));
	}

	private static MouseEvent mouse(AbstractButton button, int id, long when, int modifiers, int which)
	{
		return new MouseEvent(button, id, when, modifiers, 2, 2, 1, false, which);
	}

	@Test
	public void theRowFitsTheCardWithItsWidestWords()
	{
		// Mixed style and gear are the widest: "Mix" (18 px in Small) in a 20 px slot.
		toggles.show(tags(false, SetupStyle.MIXED, GearFocus.MIXED, null), SetupKills.NONE, 0);
		int width = toggles.getComponent().getPreferredSize().width;
		assertTrue("wants " + width, width <= 199);
		toggles.show(tags(true, SetupStyle.BARRAGE, GearFocus.STRENGTH, null), SetupKills.NONE, 0);
		assertTrue(toggles.getComponent().getPreferredSize().width <= width);
		assertFalse(toggles.isMenuOpen());
	}

	private static BufferedImage paint(Icon icon)
	{
		BufferedImage canvas = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		try
		{
			icon.paintIcon(null, g, 0, 0);
		}
		finally
		{
			g.dispose();
		}
		return canvas;
	}

	private static boolean hasColour(BufferedImage image, int argb)
	{
		for (int x = 0; x < image.getWidth(); x++)
		{
			for (int y = 0; y < image.getHeight(); y++)
			{
				if (image.getRGB(x, y) == argb)
				{
					return true;
				}
			}
		}
		return false;
	}
}
