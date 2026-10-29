package com.shmayvan.slayerledger;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JLabel;

/**
 * An item sprite drawn at the size of the sidebar's small icon slot ({@link ItemListView#ICON_WIDTH} by
 * {@link ItemListView#ICON_HEIGHT}, half the 36 by 32 item sprite), for the task icons of Overall totals, so a
 * line with one is no taller or wider than an XP list's skill line. The sprite comes through the item grids' path
 * ({@link ItemIcons}, RuneLite's {@code ItemManager.getImage}, on the EDT) and is scaled as it is painted, so a sprite
 * that arrives later is drawn when the label repaints, as {@link ItemIcons#apply} promises. {@link #getImage()} is the
 * full sprite. The task icons also show in Task history, Skipped / blocked and Trends ({@link #applyTask}),
 * and in the Luck meter's and Trends' choices ({@link TaskIconCache}). EDT only.
 */
final class SmallItemIcon extends ImageIcon
{
	/** An empty slot of the same size, for a task without a known icon, so the names stay aligned. */
	static final Icon EMPTY = new Icon()
	{
		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
		}

		@Override
		public int getIconWidth()
		{
			return ItemListView.ICON_WIDTH;
		}

		@Override
		public int getIconHeight()
		{
			return ItemListView.ICON_HEIGHT;
		}
	};

	private SmallItemIcon(Image image)
	{
		super(image);
	}

	/**
	 * Puts {@code itemId}'s sprite on {@code label} at the small slot size, through {@code icons}; {@link #EMPTY} when
	 * {@code icons} puts no image on it.
	 */
	static void apply(ItemIcons icons, JLabel label, int itemId)
	{
		label.setIcon(null);
		icons.apply(label, itemId, 1);
		Icon full = label.getIcon();
		label.setIcon(full instanceof ImageIcon ? new SmallItemIcon(((ImageIcon) full).getImage()) : EMPTY);
	}

	/**
	 * Puts the icon of the Slayer task {@code task} ({@link TaskIcons}; pass the assigned name, so a Catacombs variant
	 * or cannon task shows its task's) before {@code label}'s text at the small slot size, or {@link #EMPTY} for a name
	 * that is no known task, so the names line up. The label then asks for no width of its own, so the
	 * icon can't widen the sidebar: a long name is cut short in the space its line gives it.
	 */
	static void applyTask(ItemIcons icons, JLabel label, String task)
	{
		label.setIconTextGap(4);
		Integer itemId = TaskIcons.itemId(task);
		if (itemId == null)
		{
			label.setIcon(EMPTY);
		}
		else
		{
			apply(icons, label, itemId);
		}
		label.setPreferredSize(new Dimension(0, label.getPreferredSize().height));
	}

	@Override
	public int getIconWidth()
	{
		return ItemListView.ICON_WIDTH;
	}

	@Override
	public int getIconHeight()
	{
		return ItemListView.ICON_HEIGHT;
	}

	@Override
	public synchronized void paintIcon(Component c, Graphics g, int x, int y)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g2.drawImage(getImage(), x, y, getIconWidth(), getIconHeight(), c);
		}
		finally
		{
			g2.dispose();
		}
	}
}
