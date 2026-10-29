package com.shmayvan.slayerledger;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.Icon;
import lombok.extern.slf4j.Slf4j;

/**
 * The Slayer masters' face icons of Overall totals' By master headers: each master's chathead image from the
 * Old School RuneScape Wiki, bundled as a class resource under {@link #DIRECTORY} (Jagex's art, credited in the README).
 *
 * <p>Every image is optional: a missing or unreadable file gives {@link SmallItemIcon#EMPTY}, the same 18 by 16 slot,
 * and nothing throws, so deleting the {@code masters/} folder removes the faces with no code change.
 * Images are read with {@code getResourceAsStream}, as the bundled JSON is (no
 * {@code java.io} file APIs), the first time a master's icon is asked for, then kept (a missing one is remembered too).
 * A name two masters share ({@link SlayerMaster#displayName}, for example {@code Duradel/Kuradal}) shows the first
 * master's face; an unknown master shows the empty slot. EDT only.</p>
 *
 * <p>Images are scaled at load to twice the slot (keeping their aspect ratio, in halving steps so the downscale stays
 * smooth) and drawn into the slot at paint time with bicubic interpolation, centred, keeping the aspect ratio
 * ({@link MasterIcon}).</p>
 */
@Slf4j
final class MasterIcons
{
	/** Where the images are, relative to this class's package. */
	static final String DIRECTORY = "masters/";

	/**
	 * The image file of each master name {@link SlayerMaster#displayName} gives. A shared name uses the first master's
	 * face: Turael for Turael/Aya, Duradel for Duradel/Kuradal, Nieve for Nieve/Steve.
	 */
	private static final Map<String, String> FILES;

	static
	{
		Map<String, String> files = new HashMap<>();
		files.put("Turael/Aya", "turael_chathead.png");
		files.put("Mazchna", "mazchna_chathead.png");
		files.put("Vannaka", "vannaka_chathead.png");
		files.put("Chaeldar", "chaeldar_chathead.png");
		files.put("Duradel/Kuradal", "duradel_chathead.png");
		files.put("Nieve/Steve", "nieve_chathead.png");
		files.put("Krystilia", "krystilia_chathead.png");
		files.put("Konar", "konar_quo_maten_chathead.png");
		files.put("Spria", "spria_chathead.png");
		files.put("Mortimer", "mortimer_chathead.png");
		FILES = Collections.unmodifiableMap(files);
	}

	/** Where this loader reads the images from. */
	private final String directory;
	/** The icon of each file asked for; a file that could not be read maps to {@link SmallItemIcon#EMPTY}. */
	private final Map<String, Icon> icons = new HashMap<>();

	MasterIcons()
	{
		this(DIRECTORY);
	}

	/** Test seam: reads the images from {@code directory} (relative to this class's package) instead. */
	MasterIcons(String directory)
	{
		this.directory = directory;
	}

	/** The image file of the master named {@code master} ({@link SlayerMaster#displayName}), or {@code null}. */
	static String fileName(String master)
	{
		return master == null ? null : FILES.get(master);
	}

	/**
	 * The face of the master named {@code master} ({@link SlayerMaster#displayName}) at the small slot size, or
	 * {@link SmallItemIcon#EMPTY} for an unknown master or a missing or unreadable image. Read once, then kept.
	 */
	Icon icon(String master)
	{
		String file = fileName(master);
		if (file == null)
		{
			return SmallItemIcon.EMPTY;
		}
		return icons.computeIfAbsent(file, this::load);
	}

	private Icon load(String file)
	{
		String resource = directory + file;
		try (InputStream stream = MasterIcons.class.getResourceAsStream(resource))
		{
			if (stream == null)
			{
				log.debug("Slayer master image {} is missing", resource);
				return SmallItemIcon.EMPTY;
			}
			BufferedImage image;
			// As RuneLite's ImageUtil: ImageIO is not safe to use from several threads at once.
			synchronized (ImageIO.class)
			{
				image = ImageIO.read(stream);
			}
			if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0)
			{
				log.debug("Slayer master image {} is unreadable", resource);
				return SmallItemIcon.EMPTY;
			}
			return new MasterIcon(file, scaled(image, 2 * ItemListView.ICON_WIDTH, 2 * ItemListView.ICON_HEIGHT));
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Unable to load the Slayer master image {}", resource, e);
			return SmallItemIcon.EMPTY;
		}
	}

	/**
	 * {@code image} fitted into {@code maxWidth} by {@code maxHeight}, keeping its aspect ratio, halving the size in
	 * bilinear steps first so the result stays smooth; an image already that small is kept.
	 */
	static BufferedImage scaled(BufferedImage image, int maxWidth, int maxHeight)
	{
		double scale = Math.min(1, Math.min(maxWidth / (double) image.getWidth(), maxHeight / (double) image.getHeight()));
		int targetWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
		int targetHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
		BufferedImage current = image;
		while (current.getWidth() != targetWidth || current.getHeight() != targetHeight)
		{
			int width = Math.max(targetWidth, current.getWidth() / 2);
			int height = Math.max(targetHeight, current.getHeight() / 2);
			BufferedImage next = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = next.createGraphics();
			try
			{
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
				g.drawImage(current, 0, 0, width, height, null);
			}
			finally
			{
				g.dispose();
			}
			current = next;
		}
		return current;
	}

	/**
	 * A master's face in the small slot ({@link ItemListView#ICON_WIDTH} by {@link ItemListView#ICON_HEIGHT}), scaled
	 * as it is painted with bicubic interpolation, keeping its aspect ratio, centred.
	 */
	static final class MasterIcon implements Icon
	{
		/** The image's file name, for tests. */
		private final String file;
		private final BufferedImage image;

		private MasterIcon(String file, BufferedImage image)
		{
			this.file = file;
			this.image = image;
		}

		/** The image's file name (under {@link #DIRECTORY}). */
		String getFile()
		{
			return file;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			double scale = Math.min(getIconWidth() / (double) image.getWidth(),
				getIconHeight() / (double) image.getHeight());
			int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
			int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
				g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
				g2.drawImage(image, x + (getIconWidth() - width) / 2, y + (getIconHeight() - height) / 2, width,
					height, null);
			}
			finally
			{
				g2.dispose();
			}
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
	}
}
