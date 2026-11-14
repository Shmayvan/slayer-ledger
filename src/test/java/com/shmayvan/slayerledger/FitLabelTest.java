package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicHTML;
import net.runelite.client.ui.FontManager;
import org.junit.Test;

/**
 * {@link FitLabel}: no width of its own, a text that doesn't fit is cut short with the fonts' own ellipsis, and its
 * text is never rendered as HTML.
 */
public class FitLabelTest
{
	private static <T> T onEdt(Supplier<T> read) throws Exception
	{
		List<T> result = new ArrayList<>(1);
		SwingUtilities.invokeAndWait(() -> result.add(read.get()));
		return result.get(0);
	}

	@Test
	public void aLongTextIsCutShortWithAnEllipsisAndKeptInFull() throws Exception
	{
		String name = "Thermonuclear smoke devil (cannon)";
		String[] shown = onEdt(() ->
		{
			FitLabel label = new FitLabel(name);
			label.setFont(FontManager.getRunescapeBoldFont());
			String before = label.getText();
			label.setBounds(0, 0, 120, 16);
			FontMetrics metrics = label.getFontMetrics(label.getFont());
			return new String[]{before, label.getText(), label.getFullText(),
				Integer.toString(metrics.stringWidth(label.getText())),
				Integer.toString(label.getPreferredSize().width)};
		});
		assertEquals("before it is laid out, the whole text", name, shown[0]);
		assertTrue(shown[1], shown[1].endsWith(FitLabel.ELLIPSIS));
		assertTrue(shown[1], name.startsWith(shown[1].substring(0, shown[1].length() - 1)));
		assertTrue("fits: " + shown[3], Integer.parseInt(shown[3]) <= 120);
		assertEquals(name, shown[2]);
		assertEquals("asks for no width", "0", shown[4]);
	}

	@Test
	public void aTextThatFitsIsShownWholeAndFollowsTheWidth() throws Exception
	{
		String[] shown = onEdt(() ->
		{
			FitLabel label = new FitLabel("Dust devils");
			label.setFont(FontManager.getRunescapeBoldFont());
			label.setBounds(0, 0, 157, 16);
			String wide = label.getText();
			label.setBounds(0, 0, 30, 16);
			String narrow = label.getText();
			label.setBounds(0, 0, 157, 16);
			label.setText("Abyssal demons");
			return new String[]{wide, narrow, label.getText()};
		});
		assertEquals("Dust devils", shown[0]);
		assertTrue(shown[1], shown[1].endsWith(FitLabel.ELLIPSIS) && shown[1].length() < "Dust devils".length());
		assertEquals("a new text fits the width it has", "Abyssal demons", shown[2]);
	}

	@Test
	public void aSpaceBeforeTheEllipsisIsDroppedButALeadingOneStays() throws Exception
	{
		String[] shown = onEdt(() ->
		{
			FitLabel label = new FitLabel("Dust devils");
			label.setFont(FontManager.getRunescapeBoldFont());
			FontMetrics metrics = label.getFontMetrics(label.getFont());
			// Room for "Dust " and the ellipsis, not for "Dust d".
			label.setBounds(0, 0, metrics.stringWidth("Dust ") + metrics.stringWidth(FitLabel.ELLIPSIS), 16);
			String cut = label.getText();
			label.setText(" Dust devils");
			label.setBounds(0, 0, metrics.stringWidth(" Dust") + metrics.stringWidth(FitLabel.ELLIPSIS), 16);
			return new String[]{cut, label.getText()};
		});
		assertEquals("Dust" + FitLabel.ELLIPSIS, shown[0]);
		assertEquals(" Dust" + FitLabel.ELLIPSIS, shown[1]);
	}

	@Test
	public void playerTypedHtmlStaysPlainTextWholeOrCutShort() throws Exception
	{
		String typed = "<html><b>My spot</b> with a very long name that will not fit the card";
		Object[] shown = onEdt(() ->
		{
			// As TaskBoxView sets a typed location that starts with "<html": after a space.
			FitLabel guarded = new FitLabel();
			guarded.setFont(FontManager.getRunescapeSmallFont());
			guarded.setText(" " + typed);
			guarded.setBounds(0, 0, 157, 12);
			// And without the space: whole before it is laid out, then cut short.
			FitLabel bare = new FitLabel(typed);
			bare.setFont(FontManager.getRunescapeSmallFont());
			boolean wholeIsHtml = bare.getClientProperty(BasicHTML.propertyKey) != null;
			bare.setBounds(0, 0, 157, 12);
			return new Object[]{guarded.getText(), guarded.getClientProperty(BasicHTML.propertyKey), wholeIsHtml,
				bare.getText(), bare.getClientProperty(BasicHTML.propertyKey)};
		});
		String guarded = (String) shown[0];
		assertTrue(guarded, guarded.startsWith(" <html><b>My spot") && guarded.endsWith(FitLabel.ELLIPSIS));
		assertNull("cut short, the guarded text is not rendered as HTML", shown[1]);
		assertFalse("whole, a typed <html> is not rendered as HTML", (Boolean) shown[2]);
		String bare = (String) shown[3];
		assertTrue(bare, bare.startsWith("<html><b>My spot") && bare.endsWith(FitLabel.ELLIPSIS));
		assertNull("nor cut short", shown[4]);
	}
}
