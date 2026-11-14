package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.JagexColors;
import org.junit.Assume;
import org.junit.Test;

/**
 * {@link LedgerTheme}'s tokens are direction A's values in docs/DESIGN_PRINCIPLES.md 4.2 (read from the doc itself, so
 * the two can't drift apart, where the doc is in the checkout), RuneLite's constants where the value is one of
 * theirs, and still meet their contrast targets (WCAG 2.x, as {@code scripts/contrast.py} measures them).
 */
public class LedgerThemeTest
{
	private static final String DOC = "docs/DESIGN_PRINCIPLES.md";

	@Test
	public void theTokensAreDirectionAsValuesInTheDoc() throws IOException
	{
		Path path = Paths.get(DOC);
		// The public plugin repository ships only docs/COUNTING_RULES.md (docs/PUBLISHING.md): there the values in
		// theTokensAreDirectionAsValues are the check.
		Assume.assumeTrue(DOC + " is not in this checkout", Files.isRegularFile(path));
		String doc = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
		Map<String, Color> tokens = new LinkedHashMap<>();
		tokens.put("page", LedgerTheme.PAGE);
		tokens.put("card", LedgerTheme.CARD);
		// A flat row's hover (9d): the card colour, so text keeps the card's ratios.
		tokens.put("hover", LedgerTheme.HOVER);
		tokens.put("track", LedgerTheme.TRACK);
		tokens.put("barFill", LedgerTheme.BAR_FILL);
		tokens.put("heading", LedgerTheme.HEADING);
		tokens.put("label", LedgerTheme.LABEL);
		tokens.put("value", LedgerTheme.VALUE);
		tokens.put("key \\(one per area\\)", LedgerTheme.KEY);
		tokens.put("hint", LedgerTheme.HINT);
		tokens.put("good", LedgerTheme.GOOD);
		tokens.put("alert", LedgerTheme.ALERT);
		// The advisor note's edge and Bring cells: A's only, never text.
		tokens.put("parchmentEdge \\(not text\\)", LedgerTheme.PARCHMENT_EDGE);
		tokens.put("parchmentCell \\(not text\\)", LedgerTheme.PARCHMENT_CELL);
		for (Map.Entry<String, Color> token : tokens.entrySet())
		{
			// The 4.2 table's row: "| page | `#2A241C` | ..." (A is the first value column).
			Matcher row = Pattern.compile("^\\| " + token.getKey() + " \\| `(#[0-9A-F]{6})`", Pattern.MULTILINE)
				.matcher(doc);
			assertTrue(token.getKey() + " is in the table", row.find());
			assertEquals(token.getKey(), row.group(1), hex(token.getValue()));
		}
		assertEquals(docHex(doc, "outer `(#[0-9A-F]{6})`"), hex(LedgerTheme.BEVEL_OUTER));
		assertEquals(docHex(doc, "inner `(#[0-9A-F]{6})`"), hex(LedgerTheme.BEVEL_INNER));
		assertEquals(docHex(doc, "Parchment `(#[0-9A-F]{6})`"), hex(LedgerTheme.PARCHMENT));
		Matcher inks = Pattern.compile("Its ink colours: `(#[0-9A-F]{6})`[^,]*, muted `(#[0-9A-F]{6})`[^,]*, "
			+ "red `(#[0-9A-F]{6})`").matcher(doc);
		assertTrue("the parchment's inks", inks.find());
		assertEquals(inks.group(1), hex(LedgerTheme.INK));
		assertEquals(inks.group(2), hex(LedgerTheme.MUTED_INK));
		assertEquals(inks.group(3), hex(LedgerTheme.RED_INK));
	}

	/** The values themselves, so a change to the doc is a change to this test too; they need no doc to check. */
	@Test
	public void theTokensAreDirectionAsValues()
	{
		assertEquals("#2A241C", hex(LedgerTheme.PAGE));
		assertEquals("#3E3529", hex(LedgerTheme.CARD));
		assertEquals("#3E3529", hex(LedgerTheme.HOVER));
		assertEquals("#322A21", hex(LedgerTheme.BEVEL_OUTER));
		assertEquals("#4A4031", hex(LedgerTheme.BEVEL_INNER));
		assertEquals("#2A241C", hex(LedgerTheme.TRACK));
		assertEquals("#FF981F", hex(LedgerTheme.BAR_FILL));
		assertEquals("#FF981F", hex(LedgerTheme.HEADING));
		assertEquals("#D2BF94", hex(LedgerTheme.LABEL));
		assertEquals("#FFFFFF", hex(LedgerTheme.VALUE));
		assertEquals("#FFFFFF", hex(LedgerTheme.KEY));
		assertEquals("#B3A27E", hex(LedgerTheme.HINT));
		assertEquals("#6EE16E", hex(LedgerTheme.GOOD));
		assertEquals("#FFFF00", hex(LedgerTheme.ALERT));
		assertEquals("#D8C8A0", hex(LedgerTheme.PARCHMENT));
		assertEquals("#3A2A16", hex(LedgerTheme.INK));
		assertEquals("#5E4B2E", hex(LedgerTheme.MUTED_INK));
		assertEquals("#8A1C12", hex(LedgerTheme.RED_INK));
		assertEquals("#B8A678", hex(LedgerTheme.PARCHMENT_EDGE));
		assertEquals("#C2B28D", hex(LedgerTheme.PARCHMENT_CELL));
	}

	@Test
	public void theBringCellIsInkAtFourteenPercentOverParchment()
	{
		// As the chosen mockup draws the note's cells: rgba(58, 42, 22, 0.14) over the parchment.
		assertEquals(LedgerTheme.PARCHMENT_CELL, over(LedgerTheme.INK, 0.14, LedgerTheme.PARCHMENT));
	}

	@Test
	public void runeLitesOwnColoursAreItsConstants()
	{
		assertEquals(JagexColors.DARK_ORANGE_INTERFACE_TEXT, LedgerTheme.HEADING);
		assertEquals(JagexColors.DARK_ORANGE_INTERFACE_TEXT, LedgerTheme.BAR_FILL);
		assertEquals(JagexColors.YELLOW_INTERFACE_TEXT, LedgerTheme.ALERT);
		assertEquals(ColorScheme.GRAND_EXCHANGE_PRICE, LedgerTheme.GOOD);
		assertEquals(LedgerTheme.PAGE, LedgerTheme.TRACK);
		// A flat row's hover is the card's stone (9d), where textMeetsItsContrastTargets checks every text token.
		assertEquals(LedgerTheme.CARD, LedgerTheme.HOVER);
	}

	@Test
	public void theBevelIsTheCardTimesPointEightAndOnePointTwo()
	{
		// RuneLite's BackgroundComponent frames overlays with these offsets.
		assertEquals(scaled(LedgerTheme.CARD, 0.8), LedgerTheme.BEVEL_OUTER);
		assertEquals(scaled(LedgerTheme.CARD, 1.2), LedgerTheme.BEVEL_INNER);
	}

	@Test
	public void textMeetsItsContrastTargets()
	{
		for (Color text : new Color[]{LedgerTheme.HEADING, LedgerTheme.LABEL, LedgerTheme.VALUE, LedgerTheme.KEY,
			LedgerTheme.HINT, LedgerTheme.GOOD, LedgerTheme.ALERT})
		{
			assertTrue(hex(text) + " on the card", contrast(text, LedgerTheme.CARD) >= 4.5);
			assertTrue(hex(text) + " on the page", contrast(text, LedgerTheme.PAGE) >= 4.5);
			// A history or Skipped / blocked row under the mouse (9c and 9d, DESIGN_PRINCIPLES.md 3.7 Hover).
			assertTrue(hex(text) + " on a hovered row", contrast(text, LedgerTheme.HOVER) >= 4.5);
		}
		assertEquals("bar fill on track (doc: 7.14)", 7.14, contrast(LedgerTheme.BAR_FILL, LedgerTheme.TRACK), 0.01);
		assertEquals("alert vs heading (doc: 2.00)", 2.00, contrast(LedgerTheme.ALERT, LedgerTheme.HEADING), 0.01);
		for (Color ink : new Color[]{LedgerTheme.INK, LedgerTheme.MUTED_INK, LedgerTheme.RED_INK})
		{
			assertTrue(hex(ink) + " on parchment", contrast(ink, LedgerTheme.PARCHMENT) >= 4.5);
		}
		// The alch hint's border (DESIGN_PRINCIPLES.md 3.6) on the card's stone, where the live grids' cells are.
		assertTrue(contrast(ItemGridView.ALCH_BORDER_COLOR, LedgerTheme.CARD) >= 3);
	}

	@Test
	public void theFontsAreFontManagersAtTheirNativeSize()
	{
		assertEquals(FontManager.getRunescapeFont(), LedgerTheme.plainFont());
		assertEquals(FontManager.getRunescapeBoldFont(), LedgerTheme.boldFont());
		assertEquals(FontManager.getRunescapeSmallFont(), LedgerTheme.smallFont());
	}

	private static String docHex(String doc, String pattern)
	{
		Matcher m = Pattern.compile(pattern).matcher(doc);
		assertTrue(pattern, m.find());
		return m.group(1);
	}

	private static String hex(Color c)
	{
		return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
	}

	/** {@code fg} at {@code alpha} over {@code bg}, rounded per channel. */
	private static Color over(Color fg, double alpha, Color bg)
	{
		return new Color((int) Math.round(fg.getRed() * alpha + bg.getRed() * (1 - alpha)),
			(int) Math.round(fg.getGreen() * alpha + bg.getGreen() * (1 - alpha)),
			(int) Math.round(fg.getBlue() * alpha + bg.getBlue() * (1 - alpha)));
	}

	private static Color scaled(Color c, double factor)
	{
		return new Color((int) Math.round(c.getRed() * factor), (int) Math.round(c.getGreen() * factor),
			(int) Math.round(c.getBlue() * factor));
	}

	/** WCAG 2.x contrast ratio. */
	private static double contrast(Color a, Color b)
	{
		double la = luminance(a);
		double lb = luminance(b);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	private static double luminance(Color c)
	{
		return 0.2126 * linear(c.getRed()) + 0.7152 * linear(c.getGreen()) + 0.0722 * linear(c.getBlue());
	}

	private static double linear(int channel)
	{
		double v = channel / 255.0;
		return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
	}
}
