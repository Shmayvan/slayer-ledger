package com.shmayvan.slayerledger;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import lombok.*;
import net.runelite.client.ui.*;

/**
 * A titled list of the sidebar, the part {@link ItemListView} (text lines) and {@link ItemGridView} (item icons)
 * share: a title with its row count, then "None" while the list is empty, or the body a subclass fills from the rows.
 * A collapsible list (Overall totals' grids) opens and closes from its title ("Show" or "Hide") and starts closed.
 * Rows the caller left out (the loot Ground Items hides) are counted in the title, "Loot received (12,
 * 3 hidden)", explained in the header's tooltip ({@link #hiddenNote}), and an empty list then says they are all
 * hidden instead of "None". Subclasses call {@link #rebuild} at the end of their constructor.
 *
 * <p>The live task's lists are flat sections of the page under a sub-header ({@link #useSubHeader}): the title
 * in Bold in the heading colour over a 1 px rule, a value on the right ({@link #setHeaderValue}), always open, and
 * only the rows left out counted in the title, {@code Loot (3 hidden)} (DESIGN_PRINCIPLES.md 3.6). A list inside a
 * block of a flat section (Overall totals', a history row's details) has a quieter nested sub-header
 * ({@link #useNestedSubHeader}): Small, no rule. The advisor card's Bring grids sit on its parchment note
 * ({@link #useParchment}). EDT only.</p>
 *
 * @param <T> what one row is
 */
abstract class TitledListView<T> extends JPanel
{
	private final String title;
	private final boolean collapsible;
	private final JLabel titleLabel = new JLabel();
	private final JLabel toggleLabel = new JLabel();
	/** The sub-header's value on the right ({@link #setHeaderValue}); in the header only for a sub-header. */
	private final JLabel valueLabel = new JLabel();
	private final JPanel header = new JPanel(new BorderLayout(6, 0));
	/** What the header's tooltip says without hidden rows ({@code null}: no tooltip). */
	private final String headerTip;
	private final JPanel body = new JPanel();
	/** Rows shown; empty before the first {@link #setRows}. */
	private List<T> rows;
	/** Whether a collapsible list is open; always true otherwise. */
	@Getter
	private boolean expanded;
	/** Rows the caller left out of {@link #rows}, counted in the title; 0 for none. */
	private int hiddenCount;
	/** Whether the title shows the row count ({@link #hideCount}). */
	private boolean countShown = true;
	/** Whether the list is a flat section's sub-header ({@link #useSubHeader}). */
	private boolean subHeader;
	/** The colour of "None" and "All hidden": the hint colour, muted ink on parchment ({@link #useParchment}). */
	private Color noneColor = LedgerTheme.HINT;
	/** How many times the title and body were drawn ({@link #getBuildCount}). */
	private int builds;

	/** @param noun what the rows are, for the tooltips ("items", "skills") */
	TitledListView(String title, boolean collapsible, Color background, String noun)
	{
		this.title = title;
		this.collapsible = collapsible;
		this.expanded = !collapsible;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(background);

		// Names for tests, which find the parts by them (searching from the list itself); no visible effect.
		header.setName("itemList.header");
		titleLabel.setName("itemList.title");
		toggleLabel.setName("itemList.toggle");
		valueLabel.setName("itemList.value");
		body.setName("itemList.body");
		header.setOpaque(false);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		titleLabel.setFont(FontManager.getRunescapeSmallFont());
		titleLabel.setForeground(collapsible ? LedgerTheme.VALUE : ColorScheme.LIGHT_GRAY_COLOR);
		header.add(titleLabel, BorderLayout.CENTER);
		if (collapsible)
		{
			toggleLabel.setFont(FontManager.getRunescapeSmallFont());
			toggleLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			header.add(toggleLabel, BorderLayout.EAST);
			header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			headerTip = "Click to show or hide the " + noun;
			header.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1)
					{
						toggle();
					}
				}
			});
		}
		else
		{
			headerTip = null;
		}
		header.setToolTipText(headerTip);
		add(header);

		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		body.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(body);
		rows = Collections.emptyList();
	}

	/** Shows {@code newRows} (already sorted); nothing is rebuilt when they are the rows already shown. */
	void setRows(List<? extends T> newRows)
	{
		setRows(newRows, 0);
	}

	/**
	 * Shows {@code newRows} (already sorted), with {@code hidden} more left out by the caller; nothing is
	 * rebuilt when both are what is shown already.
	 */
	void setRows(List<? extends T> newRows, int hidden)
	{
		int newHidden = Math.max(0, hidden);
		if (newRows.equals(rows) && newHidden == hiddenCount)
		{
			return;
		}
		rows = new ArrayList<>(newRows);
		hiddenCount = newHidden;
		rebuild();
	}

	/**
	 * The header tooltip's lines on {@code hidden} rows left out (HTML, lines joined by {@code <br>}, no player or game
	 * text), or {@code null} to say nothing.
	 */
	String hiddenNote(int hidden)
	{
		return null;
	}

	/** Opens or closes a collapsible list, as a click on its title does. */
	void toggle()
	{
		if (collapsible)
		{
			expanded = !expanded;
			rebuild();
		}
	}

	/**
	 * The title as shown, with " | Show" or " | Hide" for a collapsible list, and " | " and the value for a sub-header
	 * with one.
	 */
	String getHeaderText()
	{
		if (collapsible)
		{
			return titleLabel.getText() + " | " + toggleLabel.getText();
		}
		String value = valueLabel.getText();
		return subHeader && value != null && !value.isEmpty() ? titleLabel.getText() + " | " + value
			: titleLabel.getText();
	}

	/**
	 * Makes the list a flat section of the page with a sub-header (DESIGN_PRINCIPLES.md 3.3, 3.6): the title in Bold in
	 * {@link LedgerTheme#HEADING} over a 1 px rule, a value on the right ({@link #setHeaderValue}), the page's
	 * background, and only the rows left out counted in the title. A collapsible list has no value: its
	 * {@code Show}/{@code Hide} stays on the right, in Small in {@link LedgerTheme#LABEL}, as a section header's. Call
	 * it once, after the constructor; a list nested in a block calls {@link #useNestedSubHeader} instead.
	 */
	void useSubHeader()
	{
		subHeader = true;
		countShown = false;
		setBackground(LedgerTheme.PAGE);
		LedgerTheme.styleSectionHeader(header, titleLabel, valueLabel);
		valueLabel.setForeground(LedgerTheme.VALUE);
		if (collapsible)
		{
			toggleLabel.setFont(LedgerTheme.smallFont());
			toggleLabel.setForeground(LedgerTheme.LABEL);
		}
		else
		{
			header.add(valueLabel, BorderLayout.EAST);
		}
		body.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		rebuild();
	}

	/**
	 * Makes the list a sub-header nested in a block of a flat section (phase 9d, Overall totals' Monsters
	 * assigned and item grids; phase 9c, a history row's item grids and XP list; DESIGN_PRINCIPLES.md 3.8): as
	 * {@link #useSubHeader}, but quieter than the block's title above it, so it is clear where one block ends and the
	 * next begins: the title in Small in {@link LedgerTheme#VALUE} with no rule under it
	 * ({@link LedgerTheme#styleNestedHeader}), a collapsible list's {@code Show}/{@code Hide} or another list's value
	 * still on the right in Small in {@link LedgerTheme#LABEL} (pass that colour to {@link #setHeaderValue}). Call it
	 * once, after the constructor, instead of {@link #useSubHeader}.
	 */
	final void useNestedSubHeader()
	{
		// The subclass's sub-header look first (the grid's stone cells, the list's colours), then the quieter title.
		useSubHeader();
		LedgerTheme.styleNestedHeader(header, titleLabel, collapsible ? toggleLabel : valueLabel);
		header.revalidate();
		header.repaint();
	}

	/**
	 * Makes the list part of the advisor's parchment note (DESIGN_PRINCIPLES.md 4.2): a plain title in
	 * Small in {@link LedgerTheme#INK} with no count, rule or tooltip, "None" in {@link LedgerTheme#MUTED_INK}, and no
	 * background of its own, so the note's {@link LedgerTheme#PARCHMENT} shows through. Meant for a list that isn't
	 * collapsible. Call it once, after the constructor.
	 */
	void useParchment()
	{
		countShown = false;
		noneColor = LedgerTheme.MUTED_INK;
		setOpaque(false);
		setBackground(LedgerTheme.PARCHMENT);
		titleLabel.setFont(LedgerTheme.smallFont());
		titleLabel.setForeground(LedgerTheme.INK);
		body.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
		rebuild();
	}

	/**
	 * Shows {@code text} on the right of a sub-header ({@link #useSubHeader}) in {@code color}, with {@code tooltip}
	 * (HTML or {@code null}); the empty text for none. Only the label changes: nothing is rebuilt.
	 */
	void setHeaderValue(String text, Color color, String tooltip)
	{
		valueLabel.setText(text == null ? "" : text);
		valueLabel.setForeground(color);
		valueLabel.setToolTipText(tooltip);
	}

	/** Draws the title and, while the list is open, "None" or the rows ({@link #fillBody}). */
	final void rebuild()
	{
		builds++;
		titleLabel.setText(titleText());
		toggleLabel.setText(expanded ? "Hide" : "Show");
		String note = hiddenCount > 0 ? hiddenNote(hiddenCount) : null;
		header.setToolTipText(note == null ? headerTip
			: "<html>" + (headerTip == null ? "" : headerTip + ".<br>") + note + "</html>");
		body.removeAll();
		body.setVisible(expanded);
		if (expanded)
		{
			if (rows.isEmpty())
			{
				JPanel none = new JPanel(new BorderLayout());
				none.setOpaque(false);
				none.setAlignmentX(Component.LEFT_ALIGNMENT);
				none.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
				none.add(label(hiddenCount > 0 ? "All hidden" : "None", noneColor),
					BorderLayout.CENTER);
				body.add(none);
			}
			else
			{
				fillBody(body, rows);
			}
		}
		revalidate();
		repaint();
	}

	/**
	 * "Title (12)", the rows shown, or "Title (12, 3 hidden)" when rows are left out; the same for collapsible and
	 * fixed lists.
	 */
	private String titleText()
	{
		if (!countShown)
		{
			return hiddenCount > 0 ? title + " (" + hiddenCount + " hidden)" : title;
		}
		return title + " (" + rows.size() + (hiddenCount > 0 ? ", " + hiddenCount + " hidden" : "") + ")";
	}

	/**
	 * Shows the plain title without the row count, for a list whose lines count something else (Monsters assigned:
	 * its lines are assignment shares, so a count of monsters in the title would read like a count of assignments).
	 */
	void hideCount()
	{
		countShown = false;
		titleLabel.setText(titleText());
	}

	/**
	 * Adds the components that show {@code shown} (never empty) to {@code body}, a vertical box; their alignment must
	 * be {@link Component#LEFT_ALIGNMENT}.
	 */
	abstract void fillBody(JPanel body, List<T> shown);

	/**
	 * How many times the title and body were drawn. Performance seam: tests assert this doesn't grow on the refresh
	 * timer.
	 */
	int getBuildCount()
	{
		return builds;
	}

	/** A label in the sidebar's small font. */
	static JLabel label(String text, Color color)
	{
		JLabel label = new JLabel(text);
		label.setForeground(color);
		label.setFont(FontManager.getRunescapeSmallFont());
		return label;
	}
}
