package com.shmayvan.slayerledger;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import net.runelite.client.ui.overlay.components.TextComponent;

/**
 * One overlay line of text with small images beside it, which no RuneLite component draws (DESIGN_PRINCIPLES.md 2: own
 * drawing only where no component can do the job): the runway's supply icon before its text, and the identity's
 * second line, the spot's short name with the setup tag sprites after it. The text goes through RuneLite's
 * {@link TextComponent}, so it has the same 1 px black shadow as every other overlay line, at the same baseline as a
 * {@code LineComponent} of the same font. Each image is scaled into its box, keeping its shape, and centred on the
 * line. The caller cuts the text to fit ({@link OverlayRows#fitted}).
 */
final class OverlayIconLine implements LayoutableRenderableEntity
{
	private final String text;
	private final Color color;
	/** The text's font, or {@code null} for the overlay's. */
	private final Font font;
	private final Image leading;
	private final int leadingWidth;
	private final int leadingHeight;
	private final List<Image> trailing;
	private final int trailingSize;

	private final Rectangle bounds = new Rectangle();
	private Point preferredLocation = new Point();
	private Dimension preferredSize = new Dimension(ComponentConstants.STANDARD_WIDTH, 0);

	private OverlayIconLine(String text, Color color, Font font, Image leading, int leadingWidth, int leadingHeight,
		List<Image> trailing, int trailingSize)
	{
		this.text = text == null ? "" : text;
		this.color = color;
		this.font = font;
		this.leading = leading;
		this.leadingWidth = leadingWidth;
		this.leadingHeight = leadingHeight;
		this.trailing = trailing;
		this.trailingSize = trailingSize;
	}

	/** An icon in a {@code width} by {@code height} box, then the text (the runway). */
	static OverlayIconLine leading(Image icon, int width, int height, String text, Color color, Font font)
	{
		return new OverlayIconLine(text, color, font, icon, width, height, Collections.emptyList(), 0);
	}

	/** The text, then the images, each in a {@code size} square box (the identity's spot and tags). */
	static OverlayIconLine trailing(String text, Color color, List<Image> images, int size)
	{
		return new OverlayIconLine(text, color, null, null, 0, 0, new ArrayList<>(images), size);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final Font textFont = font != null ? font : graphics.getFont();
		final FontMetrics metrics = graphics.getFontMetrics(textFont);
		final int textHeight = metrics.getHeight();
		final int imageHeight = Math.max(leading != null ? leadingHeight : 0, trailing.isEmpty() ? 0 : trailingSize);
		final int height = Math.max(textHeight, imageHeight);
		int x = preferredLocation.x;
		final int y = preferredLocation.y;

		final Object interpolation = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		try
		{
			if (leading != null)
			{
				drawFitted(graphics, leading, x, y + (height - leadingHeight) / 2, leadingWidth, leadingHeight);
				x += leadingWidth + OverlayRows.TEXT_ICON_GAP;
			}
			if (!text.isEmpty())
			{
				final TextComponent component = new TextComponent();
				// As LineComponent draws it: the baseline a font height below the line's top.
				component.setPosition(x, y + (height - textHeight) / 2 + textHeight);
				component.setText(text);
				component.setColor(color);
				component.setFont(textFont);
				component.render(graphics);
				x += metrics.stringWidth(text) + OverlayRows.TEXT_ICON_GAP;
			}
			for (Image image : trailing)
			{
				drawFitted(graphics, image, x, y + (height - trailingSize) / 2, trailingSize, trailingSize);
				x += trailingSize + OverlayRows.TAG_GAP;
			}
		}
		finally
		{
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				interpolation != null ? interpolation : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		}

		final Dimension dimension = new Dimension(preferredSize.width, height);
		bounds.setLocation(preferredLocation);
		bounds.setSize(dimension);
		return dimension;
	}

	/** Draws {@code image} scaled into the {@code width} by {@code height} box, keeping its shape, centred in it. */
	private static void drawFitted(Graphics2D graphics, Image image, int x, int y, int width, int height)
	{
		final int imageWidth = image.getWidth(null);
		final int imageHeight = image.getHeight(null);
		if (imageWidth <= 0 || imageHeight <= 0)
		{
			return;
		}
		final double scale = Math.min(width / (double) imageWidth, height / (double) imageHeight);
		final int drawWidth = Math.max(1, (int) Math.round(imageWidth * scale));
		final int drawHeight = Math.max(1, (int) Math.round(imageHeight * scale));
		graphics.drawImage(image, x + (width - drawWidth) / 2, y + (height - drawHeight) / 2, drawWidth, drawHeight,
			null);
	}

	@Override
	public Rectangle getBounds()
	{
		return bounds;
	}

	@Override
	public void setPreferredLocation(Point position)
	{
		preferredLocation = position;
	}

	@Override
	public void setPreferredSize(Dimension dimension)
	{
		preferredSize = dimension;
	}
}
