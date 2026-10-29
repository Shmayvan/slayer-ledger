package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import javax.swing.JComboBox;
import javax.swing.JScrollPane;
import javax.swing.event.PopupMenuListener;
import org.junit.Test;

/**
 * A drop-down's open list widening to fit its longest choice. Headless, the list is never shown, so these check
 * the size it is given; the move to keep its right edge in place needs a screen (checked in game).
 */
public class ComboPopupWidenerTest
{
	private static final String LONG = "Catacombs of Kourend, the far north end";

	private static JComboBox<String> box(String... items)
	{
		JComboBox<String> box = new JComboBox<>(items);
		box.setPrototypeDisplayValue("Abyssal demons");
		box.setSize(box.getPreferredSize());
		return box;
	}

	private static ComboPopupWidener widener(JComboBox<String> box)
	{
		for (PopupMenuListener listener : box.getPopupMenuListeners())
		{
			if (listener instanceof ComboPopupWidener)
			{
				return (ComboPopupWidener) listener;
			}
		}
		throw new AssertionError("no widener");
	}

	@Test
	public void theWidthIsTheContentsAtLeastTheBoxAndAtMostTheMaximum()
	{
		assertEquals(250, ComboPopupWidener.width(120, 250, 320));
		assertEquals("never narrower than the box", 120, ComboPopupWidener.width(120, 80, 320));
		assertEquals("capped", 320, ComboPopupWidener.width(120, 500, 320));
		assertEquals("a box wider than the cap stays as it is", 400, ComboPopupWidener.width(400, 500, 320));
	}

	@Test
	public void theListWidensToItsLongestChoiceAndTheBoxKeepsItsWidth()
	{
		JComboBox<String> box = box("All", LONG);
		int closed = box.getPreferredSize().width;
		ComboPopupWidener.install(box, 1_000);
		widener(box).widen(box);
		JScrollPane scroller = ComboPopupWidener.listScroller(box);
		assertNotNull(scroller);
		int list = scroller.getPreferredSize().width;
		assertTrue("wider than the box: " + list + " > " + closed, list > closed);
		assertEquals("the closed box keeps its width", closed, box.getPreferredSize().width);
	}

	@Test
	public void aLongChoiceIsCappedAndShortOnesKeepTheBoxWidth()
	{
		JComboBox<String> capped = box("All", LONG);
		int cap = capped.getWidth() + 20;
		ComboPopupWidener.install(capped, cap);
		widener(capped).widen(capped);
		assertEquals(cap, ComboPopupWidener.listScroller(capped).getPreferredSize().width);

		JComboBox<String> narrow = box("All", "Abyss");
		ComboPopupWidener.install(narrow, 1_000);
		widener(narrow).widen(narrow);
		int list = ComboPopupWidener.listScroller(narrow).getPreferredSize().width;
		assertTrue("the box's width, less the list's border: " + list + " of " + narrow.getWidth(),
			list <= narrow.getWidth() && list >= narrow.getWidth() - 10);
	}
}
