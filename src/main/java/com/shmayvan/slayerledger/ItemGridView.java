package com.shmayvan.slayerledger;

import java.awt.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import javax.swing.border.*;
import net.runelite.client.ui.*;

/**
 * Supplies or loot as a grid of item icons, {@value #COLUMNS} to a row like RuneLite's Loot Tracker, in the
 * rows' order ({@link ItemDetails#BY_VALUE}). Each cell is a {@value #CELL_WIDTH} by {@value #CELL_HEIGHT} item
 * sprite with 1 px between cells, 184 px for a row (DESIGN_PRINCIPLES.md 3.6), which fits inside a card. Each icon
 * has its quantity drawn on it (a dose, bite or charge count for items counted that way; the tooltip says which), and
 * the row's tooltip ({@link ItemDetails.Row#tooltip()}: the full
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
	/** Width of a cell: an item sprite. */
	static final int CELL_WIDTH = 36;
	/** Height of a cell: an item sprite. The alch hint's 1 px border is drawn over the sprite's edge. */
	static final int CELL_HEIGHT = 32;
	/** The alch hint's border (DESIGN_PRINCIPLES.md 3.6 keeps RuneLite's brand orange for it). */
	static final Color ALCH_BORDER_COLOR = ColorScheme.BRAND_ORANGE;

	private final ItemIcons icons;
	/**
	 * Background of a cell, so the slots stand out from the list's: RuneLite's grey under the history and totals, the
	 * stone of a card for the live task's grids on the page ({@link #useSubHeader}), a darker parchment on the advisor's
	 * note ({@link #useParchment}).
	 */
	private Color cellBackground = ColorScheme.DARK_GRAY_COLOR;
	/** Each icon's tooltip (HTML): the row's own ({@link ItemDetails.Row#tooltip()}) unless set otherwise. */
	private Function<ItemDetails.Row, String> cellTooltip = ItemDetails.Row::tooltip;

	ItemGridView(String title, boolean collapsible, Color background, ItemIcons icons)
	{
		super(title, collapsible, background, "items");
		this.icons = icons;
		rebuild();
	}

	/**
	 * The advisor's Bring grid: on its parchment note ({@link TitledListView#useParchment}), with
	 * {@link LedgerTheme#PARCHMENT_CELL} slots and each icon's tooltip from {@code tooltip} (HTML; it starts with the
	 * item's name), since a thing to bring has an amount and no saved prices. Call it once, after the constructor.
	 */
	void useParchment(Function<ItemDetails.Row, String> tooltip)
	{
		cellBackground = LedgerTheme.PARCHMENT_CELL;
		cellTooltip = tooltip;
		useParchment();
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

	/** A flat sub-header's grid (the live task's): its cells in the card's stone. */
	@Override
	void useSubHeader()
	{
		cellBackground = LedgerTheme.CARD;
		super.useSubHeader();
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
			grid.add(slot(new JLabel(), null));
		}
		// The cells keep their size: the grid never stretches to the list's width.
		grid.setMaximumSize(grid.getPreferredSize());
		body.add(grid);
	}

	private JLabel cell(ItemDetails.Row row)
	{
		JLabel cell = slot(new JLabel(),
			row.isAlchHint() ? BorderFactory.createLineBorder(ALCH_BORDER_COLOR, 1) : null);
		cell.setName("itemGrid.cell");
		cell.setToolTipText(cellTooltip.apply(row));
		icons.apply(cell, row.getItemId(), iconQuantity(row.getQuantity()));
		return cell;
	}

	/** A slot of the grid: one item sprite's size, so a row of five is 184 px wide and never widens the sidebar. */
	private JLabel slot(JLabel label, Border border)
	{
		label.setOpaque(true);
		label.setBackground(cellBackground);
		label.setHorizontalAlignment(SwingConstants.CENTER);
		label.setVerticalAlignment(SwingConstants.CENTER);
		label.setBorder(border);
		label.setPreferredSize(new Dimension(CELL_WIDTH, CELL_HEIGHT));
		return label;
	}

	/** The quantity to draw on an icon: the row's, capped to an {@code int} (the game shortens it, "10M"). */
	static int iconQuantity(long quantity)
	{
		return (int) Math.max(0, Math.min(Integer.MAX_VALUE, quantity));
	}
}
