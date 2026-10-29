package com.shmayvan.slayerledger;

import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Point;
import javax.accessibility.Accessible;
import javax.swing.JComboBox;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.plaf.basic.BasicComboPopup;

/**
 * Widens a drop-down's open list to fit its longest choice, up to a maximum, while the closed box keeps its own
 * (prototype) width, so a narrow box in the sidebar still shows long names in full when opened. The list is never
 * narrower than the box, and keeps its right edge on the box's (the sidebar is on the right of the client). EDT only.
 *
 * <p>The look and feel sizes the open list from the box's display size (its prototype) as it opens, so the list is
 * widened just after it is shown, and shown again at its new size.</p>
 */
final class ComboPopupWidener implements PopupMenuListener
{
	/** The widest the open list gets, in pixels: a 40-character location name and a scroll bar fit. */
	static final int MAX_WIDTH = 320;

	private final int maxWidth;

	private ComboPopupWidener(int maxWidth)
	{
		this.maxWidth = maxWidth;
	}

	/** Makes {@code box}'s open list fit its longest choice, at most {@code maxWidth} pixels wide. */
	static void install(JComboBox<?> box, int maxWidth)
	{
		box.addPopupMenuListener(new ComboPopupWidener(maxWidth));
	}

	/**
	 * The open list's width: its content's, at most {@code maxWidth}, and never narrower than {@code boxWidth} (the
	 * width the look and feel gives it).
	 */
	static int width(int boxWidth, int contentWidth, int maxWidth)
	{
		return Math.max(boxWidth, Math.min(contentWidth, maxWidth));
	}

	/** The scroll pane holding {@code box}'s open list, or {@code null} when its look and feel has no such list. */
	static JScrollPane listScroller(JComboBox<?> box)
	{
		BasicComboPopup popup = popup(box);
		return popup == null ? null
			: (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, popup.getList());
	}

	private static BasicComboPopup popup(JComboBox<?> box)
	{
		if (box.getUI() == null)
		{
			return null;
		}
		Accessible child = box.getUI().getAccessibleChild(box, 0);
		return child instanceof BasicComboPopup ? (BasicComboPopup) child : null;
	}

	@Override
	public void popupMenuWillBecomeVisible(PopupMenuEvent event)
	{
		JComboBox<?> box = (JComboBox<?>) event.getSource();
		// The look and feel sizes the list to the box as it opens: widen it once it is up.
		SwingUtilities.invokeLater(() -> widen(box));
	}

	@Override
	public void popupMenuWillBecomeInvisible(PopupMenuEvent event)
	{
	}

	@Override
	public void popupMenuCanceled(PopupMenuEvent event)
	{
	}

	/**
	 * Widens {@code box}'s open list to fit its choices ({@link #width}); while it is shown, shows it again at its new
	 * size, moved left so its right edge stays where it was.
	 */
	void widen(JComboBox<?> box)
	{
		BasicComboPopup popup = popup(box);
		JScrollPane scroller = listScroller(box);
		if (popup == null || scroller == null)
		{
			return;
		}
		Insets popupInsets = popup.getInsets();
		Insets scrollerInsets = scroller.getInsets();
		int boxWidth = Math.max(box.getWidth() - popupInsets.left - popupInsets.right, scroller.getWidth());
		int content = popup.getList().getPreferredSize().width + scrollerInsets.left + scrollerInsets.right;
		if (box.getItemCount() > box.getMaximumRowCount())
		{
			content += scroller.getVerticalScrollBar().getPreferredSize().width;
		}
		int wanted = width(boxWidth, content, maxWidth);
		Dimension size = scroller.getPreferredSize();
		if (wanted == size.width)
		{
			return;
		}
		Dimension wider = new Dimension(wanted, size.height);
		scroller.setPreferredSize(wider);
		scroller.setMaximumSize(wider);
		scroller.setMinimumSize(wider);
		if (popup.isShowing())
		{
			Point at = popup.getLocationOnScreen();
			int grown = wanted - scroller.getWidth();
			// Moving a shown list shows it again, at its new size; pack() covers the case it doesn't move.
			popup.setLocation(at.x - grown, at.y);
			popup.pack();
		}
	}
}
