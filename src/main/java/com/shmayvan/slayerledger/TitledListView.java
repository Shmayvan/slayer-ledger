package com.shmayvan.slayerledger;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * A titled list of the sidebar, the part {@link ItemListView} (text lines) and {@link ItemGridView} (item icons)
 * share: a title with its row count, then "None" while the list is empty, or the body a subclass fills from the rows.
 * A collapsible list (the live ones) opens and closes from its title ("Show" or "Hide") and starts closed. Rows the
 * caller left out (the loot Ground Items hides) are counted in the title, "Loot received (12,
 * 3 hidden)", explained in the header's tooltip ({@link #hiddenNote}), and an empty list then says they are all
 * hidden instead of "None". Subclasses call {@link #rebuild} at the end of their constructor. EDT only.
 *
 * @param <T> what one row is
 */
abstract class TitledListView<T> extends JPanel
{
	private final String title;
	private final boolean collapsible;
	private final JLabel titleLabel = new JLabel();
	private final JLabel toggleLabel = new JLabel();
	private final JPanel header = new JPanel(new BorderLayout(6, 0));
	/** What the header's tooltip says without hidden rows ({@code null}: no tooltip). */
	private final String headerTip;
	private final JPanel body = new JPanel();
	/** Rows shown; empty before the first {@link #setRows}. */
	private List<T> rows;
	/** Whether a collapsible list is open; always true otherwise. */
	private boolean expanded;
	/** Rows the caller left out of {@link #rows}, counted in the title; 0 for none. */
	private int hiddenCount;
	/** Whether the title shows the row count ({@link #hideCount}). */
	private boolean countShown = true;

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
		body.setName("itemList.body");
		header.setOpaque(false);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		titleLabel.setFont(FontManager.getRunescapeSmallFont());
		titleLabel.setForeground(collapsible ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
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

	boolean isExpanded()
	{
		return expanded;
	}

	/** The title as shown, with " | Show" or " | Hide" for a collapsible list. */
	String getHeaderText()
	{
		return collapsible ? titleLabel.getText() + " | " + toggleLabel.getText() : titleLabel.getText();
	}

	/** Draws the title and, while the list is open, "None" or the rows ({@link #fillBody}). */
	final void rebuild()
	{
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
				none.add(label(hiddenCount > 0 ? "All hidden" : "None", ColorScheme.MEDIUM_GRAY_COLOR),
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
			return title;
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

	/** A label in the sidebar's small font. */
	static JLabel label(String text, Color color)
	{
		JLabel label = new JLabel(text);
		label.setForeground(color);
		label.setFont(FontManager.getRunescapeSmallFont());
		return label;
	}
}
