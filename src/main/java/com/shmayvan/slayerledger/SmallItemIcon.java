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
 * full sprite. Now the task icons also show in Task history and Skipped / blocked ({@link #applyTask}), and
 * the setup tags' game images on the Task history rows ({@link #applyTag}), each in a
 * {@link #TAG_SIZE} square as the overlay draws them. The image keeps its shape, centred in its box. EDT only.
 */
final class SmallItemIcon extends ImageIcon
{
	/** The side of a setup tag's square icon box: the overlay's ({@link OverlayRows#TAG_SIZE}). */
	static final int TAG_SIZE = OverlayRows.TAG_SIZE;

	/** An empty slot of the same size, for a task without a known icon, so the names stay aligned. */
	static final Icon EMPTY = empty(ItemListView.ICON_WIDTH, ItemListView.ICON_HEIGHT);

	/** An empty tag box, for a tag whose icon could not be had, so the boxes stay the same size. */
	static final Icon EMPTY_TAG = empty(TAG_SIZE, TAG_SIZE);

	private final int width;
	private final int height;

	private SmallItemIcon(Image image, int width, int height)
	{
		super(image);
		this.width = width;
		this.height = height;
	}

	/** An icon that draws nothing, {@code width} by {@code height}. */
	private static Icon empty(int width, int height)
	{
		return new Icon()
		{
			@Override
			public void paintIcon(Component c, Graphics g, int x, int y)
			{
			}

			@Override
			public int getIconWidth()
			{
				return width;
			}

			@Override
			public int getIconHeight()
			{
				return height;
			}
		};
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
		label.setIcon(full instanceof ImageIcon
			? new SmallItemIcon(((ImageIcon) full).getImage(), ItemListView.ICON_WIDTH, ItemListView.ICON_HEIGHT)
			: EMPTY);
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

	/**
	 * Puts a setup tag's game image ({@link SetupTagIcons.Icon}: the cannon barrels, Ice Barrage's spell icon, the skill
	 * icons, as the overlay and the live card's toggles show them) on {@code label} in a {@link #TAG_SIZE} square,
	 * from the sidebar's {@link SetupImages}, which asks for it the first time; {@link #EMPTY_TAG} until it has arrived.
	 * Returns whether the image is on the label. EDT only.
	 */
	static boolean applyTag(SetupImages images, JLabel label, SetupTagIcons.Icon tag)
	{
		Image image = images.get(tag);
		label.setIcon(image == null ? EMPTY_TAG : new SmallItemIcon(image, TAG_SIZE, TAG_SIZE));
		return image != null;
	}

	@Override
	public int getIconWidth()
	{
		return width;
	}

	@Override
	public int getIconHeight()
	{
		return height;
	}

	@Override
	public synchronized void paintIcon(Component c, Graphics g, int x, int y)
	{
		Image image = getImage();
		int imageWidth = image.getWidth(c);
		int imageHeight = image.getHeight(c);
		int drawWidth = width;
		int drawHeight = height;
		if (imageWidth > 0 && imageHeight > 0)
		{
			// Keeps the image's shape: the 36 by 32 item sprites fill the 18 by 16 slot, a cannon a tag box's width.
			double scale = Math.min((double) width / imageWidth, (double) height / imageHeight);
			drawWidth = Math.max(1, (int) Math.round(imageWidth * scale));
			drawHeight = Math.max(1, (int) Math.round(imageHeight * scale));
		}
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g2.drawImage(image, x + (width - drawWidth) / 2, y + (height - drawHeight) / 2, drawWidth, drawHeight, c);
		}
		finally
		{
			g2.dispose();
		}
	}
}
