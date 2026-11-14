package com.shmayvan.slayerledger;

import java.awt.Color;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.border.Border;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.JagexColors;

/**
 * The sidebar's colour tokens and fonts (docs/DESIGN_PRINCIPLES.md 4.1, 4.4), with the values of the visual direction
 * the owner chose, A: OSRS-native (2026-11-09). Every colour of the sidebar comes from here: a token means the
 * same thing wherever it is used, and no other sidebar class writes a colour literal. Where a value is one of
 * RuneLite's own colours, the token refers to the {@link ColorScheme} or {@link JagexColors} constant rather than
 * copying its hex.
 * The text tokens' contrast ratios against {@link #CARD} and {@link #PAGE} are in the doc's 4.2 table and
 * {@code scripts/contrast.py}.
 *
 * <p>The fonts are the three RuneScape fonts {@link FontManager} returns, at their native size: they are pixel fonts
 * and lose their crisp edges at any other.</p>
 */
final class LedgerTheme
{
	/** The panel's background, under every flat section. */
	static final Color PAGE = new Color(0x2A241C);
	/** The two raised cards' background (the live task and, from 9b, the advisor). */
	static final Color CARD = new Color(0x3E3529);
	/**
	 * A flat row's background under the mouse, which shows it can be clicked (DESIGN_PRINCIPLES.md 3.3, 3.7 Hover, 4.2's
	 * {@code hover}; the Task history's rows, 9c, and Skipped / blocked's, 9d): the card's stone, so every text token
	 * keeps its 4.5:1 there ({@link #HINT}, the lowest, 4.80:1). Not a card: no bevel goes with it.
	 */
	static final Color HOVER = CARD;
	/** A card's outer bevel line: the card colour times 0.8, as RuneLite's {@code BackgroundComponent} frames. */
	static final Color BEVEL_OUTER = new Color(0x322A21);
	/** A card's inner bevel line: the card colour times 1.2; also the flat sections' 1 px rules. */
	static final Color BEVEL_INNER = new Color(0x4A4031);
	/** The progress bar's empty part. */
	static final Color TRACK = PAGE;
	/** The progress bar's filled part: the game's interface orange (7.14:1 against the track). */
	static final Color BAR_FILL = JagexColors.DARK_ORANGE_INTERFACE_TEXT;
	/** Section headers and card names: the game's interface orange {@code #FF981F}. */
	static final Color HEADING = JagexColors.DARK_ORANGE_INTERFACE_TEXT;
	/** Labels: the left side of a row. */
	static final Color LABEL = new Color(0xD2BF94);
	/** Values: the right side of a row. */
	static final Color VALUE = Color.WHITE;
	/** The one key number of an area, in {@link #boldFont()}. */
	static final Color KEY = Color.WHITE;
	/** Quiet text: second lines, the running clock, small notes. */
	static final Color HINT = new Color(0xB3A27E);
	/** A gain, such as a profit; never the only cue (a sign or word goes with it). */
	static final Color GOOD = ColorScheme.GRAND_EXCHANGE_PRICE;
	/**
	 * Warnings, critical warnings and negative values: the game's yellow {@code #FFFF00}. Their level is told by words,
	 * signs and weight, never by another colour.
	 */
	static final Color ALERT = JagexColors.YELLOW_INTERFACE_TEXT;

	/** The parchment of the advisor note ({@link AdvisorCardSection}), the one surface that uses it. */
	static final Color PARCHMENT = new Color(0xD8C8A0);
	/** Text on {@link #PARCHMENT} (8.33:1). */
	static final Color INK = new Color(0x3A2A16);
	/** Quiet text on {@link #PARCHMENT} (5.03:1). */
	static final Color MUTED_INK = new Color(0x5E4B2E);
	/** Negative values and warnings on {@link #PARCHMENT} (5.63:1). */
	static final Color RED_INK = new Color(0x8A1C12);
	/**
	 * The advisor note's 1 px edge (the chosen mockup's) and the 1 px rule between its blocks: a darker
	 * {@link #PARCHMENT}. Not for text.
	 */
	static final Color PARCHMENT_EDGE = new Color(0xB8A678);
	/**
	 * The item cells of the advisor note's Bring grids: {@link #INK} at 14% over {@link #PARCHMENT}, so the slots stand
	 * out from the note as the live grids' stone cells do from the page. Not for text.
	 */
	static final Color PARCHMENT_CELL = new Color(0xC2B28D);

	/** Inside padding of a card, in pixels (as RuneLite's {@code LootTrackerBox} pads its title): 199 px inside. */
	static final int CARD_PADDING = 7;

	private LedgerTheme()
	{
	}

	/** Panel values that matter and history row names: RuneScape Plain 12. */
	static Font plainFont()
	{
		return FontManager.getRunescapeFont();
	}

	/** Card names, section headers and the one key number per area: RuneScape Bold 12. */
	static Font boldFont()
	{
		return FontManager.getRunescapeBoldFont();
	}

	/** Panel body text, second lines and hints: RuneScape Small. */
	static Font smallFont()
	{
		return FontManager.getRunescapeSmallFont();
	}

	/**
	 * A raised card's frame: the two-tone bevel (outer {@link #BEVEL_OUTER}, inner {@link #BEVEL_INNER}, 1 px each)
	 * around {@link #CARD_PADDING} of padding. Square corners, no shadow.
	 */
	static Border cardBorder()
	{
		return BorderFactory.createCompoundBorder(
			BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BEVEL_OUTER, 1),
				BorderFactory.createLineBorder(BEVEL_INNER, 1)),
			BorderFactory.createEmptyBorder(CARD_PADDING - 2, CARD_PADDING - 2, CARD_PADDING - 2, CARD_PADDING - 2));
	}

	/** Makes {@code card} a raised card: {@link #CARD} with {@link #cardBorder()}. */
	static void styleCard(JComponent card)
	{
		card.setOpaque(true);
		card.setBackground(CARD);
		card.setBorder(cardBorder());
	}

	/** A label in {@code font} and {@code color}. */
	static JLabel label(String text, Font font, Color color)
	{
		JLabel label = new JLabel(text);
		label.setFont(font);
		label.setForeground(color);
		return label;
	}

	/**
	 * Styles a section header (DESIGN_PRINCIPLES.md 3.3): the title in Bold in {@link #HEADING}, the right-hand
	 * {@code Show}/{@code Hide} (or a value) in Small in {@link #LABEL}, on the page with a 1 px rule under it.
	 */
	static void styleSectionHeader(JComponent header, JLabel title, JLabel right)
	{
		header.setOpaque(true);
		header.setBackground(PAGE);
		header.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BEVEL_INNER),
			BorderFactory.createEmptyBorder(0, 0, 2, 0)));
		title.setFont(boldFont());
		title.setForeground(HEADING);
		if (right != null)
		{
			right.setFont(smallFont());
			right.setForeground(LABEL);
		}
	}

	/**
	 * Styles a sub-header nested in a block of a flat section (DESIGN_PRINCIPLES.md 3.8; Overall totals' Monsters
	 * assigned and item grids, and a history row's details, 9c): quieter than the block's title above
	 * it, so it is clear where one block ends. The title in Small in {@link #VALUE}, the right-hand
	 * {@code Show}/{@code Hide} or value (if any) in Small in {@link #LABEL}, on the page with no rule under it.
	 */
	static void styleNestedHeader(JComponent header, JLabel title, JLabel right)
	{
		header.setOpaque(true);
		header.setBackground(PAGE);
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
		title.setFont(smallFont());
		title.setForeground(VALUE);
		if (right != null)
		{
			right.setFont(smallFont());
			right.setForeground(LABEL);
		}
	}
}
