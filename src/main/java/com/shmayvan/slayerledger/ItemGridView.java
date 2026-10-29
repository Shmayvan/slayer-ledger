package com.shmayvan.slayerledger;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.Border;
import net.runelite.client.ui.ColorScheme;

/**
 * Supplies or loot as a grid of item icons, {@value #COLUMNS} to a row like RuneLite's Loot Tracker, in the
 * rows' order ({@link ItemDetails#BY_VALUE}). Each icon has its quantity drawn on it (a dose, bite or charge count for
 * items counted that way; the tooltip says which), and the row's tooltip ({@link ItemDetails.Row#tooltip()}: the full
 * name, exact quantity, value, unit prices and why the alch hint is shown). Loot whose high alch beats its GE price
 * (the alch hint, {@link ItemDetails#ALCH_MARKER}) gets a thin orange border. Every item is shown: five to a row keep
 * long lists short, so there is no "+N more". The title, "None" and opening and closing are
 * {@link TitledListView}'s. Loot grids leave out the items Ground Items hides, coins included
 * ({@link #setRows(List, HiddenLoot)}). EDT only.
 */
final class ItemGridView extends TitledListView<ItemDetails.Row>
{
	/** Icons per row, as in Loot Tracker. */
	static final int COLUMNS = 5;
	/** Height of a cell: an item sprite (32 px) and the 1 px border above and below it. */
	static final int CELL_HEIGHT = 34;
	/** The alch hint's border. */
	static final Color ALCH_BORDER_COLOR = ColorScheme.BRAND_ORANGE;
	/** Background of a cell, lighter than the list's so the slots stand out. */
	private static final Color CELL_BACKGROUND = ColorScheme.DARK_GRAY_COLOR;

	private final ItemIcons icons;

	ItemGridView(String title, boolean collapsible, Color background, ItemIcons icons)
	{
		super(title, collapsible, background, "items");
		this.icons = icons;
		rebuild();
	}

	/**
	 * Shows the rows {@code hidden} does not hide ({@code null}: all of them), and how many it hides in the title and
	 * the header's tooltip.
	 */
	void setRows(List<ItemDetails.Row> rows, HiddenLoot hidden)
	{
		List<ItemDetails.Row> shown = hidden == null ? rows : hidden.visibleRows(rows);
		setRows(shown, rows.size() - shown.size());
	}

	@Override
	String hiddenNote(int hidden)
	{
		return (hidden == 1 ? "1 item is" : hidden + " items are") + " hidden by your Ground Items<br>hidden list;"
			+ " hidden items<br>are left out of loot value, except coins.";
	}

	@Override
	void fillBody(JPanel body, List<ItemDetails.Row> shown)
	{
		JPanel grid = new JPanel(new GridLayout(0, COLUMNS, 1, 1));
		// Names for tests, which find the cells from the list itself; no visible effect.
		grid.setName("itemGrid.grid");
		grid.setOpaque(false);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (ItemDetails.Row row : shown)
		{
			grid.add(cell(row));
		}
		// Empty slots fill the last row, as in Loot Tracker.
		for (int i = shown.size(); i % COLUMNS != 0; i++)
		{
			grid.add(slot(new JLabel(), BorderFactory.createEmptyBorder(1, 1, 1, 1)));
		}
		body.add(grid);
	}

	private JLabel cell(ItemDetails.Row row)
	{
		JLabel cell = slot(new JLabel(), row.isAlchHint()
			? BorderFactory.createLineBorder(ALCH_BORDER_COLOR, 1)
			: BorderFactory.createEmptyBorder(1, 1, 1, 1));
		cell.setName("itemGrid.cell");
		cell.setToolTipText(row.tooltip());
		icons.apply(cell, row.getItemId(), iconQuantity(row.getQuantity()));
		return cell;
	}

	/**
	 * A slot of the grid. Its preferred width is minimal, so the grid never widens the sidebar: the grid takes the
	 * list's width and its five columns share it (about 38 px each in the sidebar, room for a 36 px sprite).
	 */
	private static JLabel slot(JLabel label, Border border)
	{
		label.setOpaque(true);
		label.setBackground(CELL_BACKGROUND);
		label.setHorizontalAlignment(SwingConstants.CENTER);
		label.setVerticalAlignment(SwingConstants.CENTER);
		label.setBorder(border);
		label.setPreferredSize(new Dimension(1, CELL_HEIGHT));
		return label;
	}

	/** The quantity to draw on an icon: the row's, capped to an {@code int} (the game shortens it, "10M"). */
	static int iconQuantity(long quantity)
	{
		return (int) Math.max(0, Math.min(Integer.MAX_VALUE, quantity));
	}
}
