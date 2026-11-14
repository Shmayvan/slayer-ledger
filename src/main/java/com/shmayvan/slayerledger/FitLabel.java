package com.shmayvan.slayerledger;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Insets;
import javax.swing.Icon;
import javax.swing.JLabel;

/**
 * A sidebar label that never widens the sidebar (DESIGN_PRINCIPLES.md 3.1): it asks for no width of its own, takes
 * the width its container gives it, and when its text doesn't fit there it shows the longest start of it that does,
 * followed by {@code …} (which the RuneScape fonts have), measured with the label's own font. Its full text stays in
 * {@link #getFullText()}; the caller puts it in the tooltip where it matters. Before it is first laid out (width 0)
 * it shows the full text. Its text is always plain: Swing's HTML rendering is turned off ({@code html.disable}), so
 * player-typed text that starts with {@code <html>} shows as typed, whole or cut short. EDT only.
 */
final class FitLabel extends JLabel
{
	/** Ends a text cut short. */
	static final String ELLIPSIS = "…";

	/** The text as set; {@link #getText()} is what fits. */
	private String fullText = "";

	FitLabel()
	{
		super();
		disableHtml();
	}

	FitLabel(String text)
	{
		super();
		disableHtml();
		setText(text);
	}

	/** Shows any text as plain text, before the first one is set. */
	private void disableHtml()
	{
		putClientProperty("html.disable", Boolean.TRUE);
	}

	@Override
	public void setText(String text)
	{
		fullText = text == null ? "" : text;
		super.setText(fit(fullText, getWidth()));
	}

	/** The text as set, before any cut. */
	String getFullText()
	{
		return fullText == null ? "" : fullText;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void reshape(int x, int y, int w, int h)
	{
		super.reshape(x, y, w, h);
		String shown = fit(getFullText(), w);
		if (!shown.equals(super.getText()))
		{
			super.setText(shown);
		}
	}

	/** No width of its own, so a long text is cut short rather than widening the sidebar; its own height. */
	@Override
	public Dimension getPreferredSize()
	{
		if (isPreferredSizeSet())
		{
			return super.getPreferredSize();
		}
		return new Dimension(0, super.getPreferredSize().height);
	}

	/** Any width a box layout gives it, its own height. */
	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, super.getPreferredSize().height);
	}

	/**
	 * {@code text}, or the longest start of it that fits {@code width} with {@link #ELLIPSIS} after it, without the
	 * spaces before the ellipsis (a leading space stays); the whole text while the label has no width yet or no font.
	 */
	private String fit(String text, int width)
	{
		if (text == null || text.isEmpty() || width <= 0 || getFont() == null)
		{
			return text == null ? "" : text;
		}
		FontMetrics metrics = getFontMetrics(getFont());
		Insets insets = getInsets();
		int available = width - insets.left - insets.right;
		Icon icon = getIcon();
		if (icon != null)
		{
			available -= icon.getIconWidth() + getIconTextGap();
		}
		if (metrics.stringWidth(text) <= available)
		{
			return text;
		}
		int ellipsis = metrics.stringWidth(ELLIPSIS);
		int end = text.length();
		while (end > 0 && metrics.stringWidth(text.substring(0, end)) + ellipsis > available)
		{
			end--;
		}
		while (end > 0 && text.charAt(end - 1) == ' ')
		{
			end--;
		}
		return text.substring(0, end) + ELLIPSIS;
	}
}
