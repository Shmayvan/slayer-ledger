package com.shmayvan.slayerledger;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.MenuAction;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.ComponentOrientation;
import net.runelite.client.ui.overlay.components.ImageComponent;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.ProgressBarComponent;
import net.runelite.client.ui.overlay.components.SplitComponent;

/**
 * The in-game task overlay in look A, OSRS-native (DESIGN_PRINCIPLES.md 2 and 4.2): the rows of
 * {@link OverlayRows} built from RuneLite's components, a {@link LineComponent} per line, a
 * {@link ProgressBarComponent} for the bar and the task icon beside the identity's two lines in a
 * {@link SplitComponent}, as the XP tracker's overlay does; {@link OverlayIconLine} draws the two lines with small
 * images.
 *
 * <p>Rendered on the client thread every frame, so it only reads the plugin's snapshot
 * ({@link SlayerLedgerPlugin#overlayData()}), joins the rows and loads the icons, which RuneLite caches (item icons
 * through {@link ItemManager#getImage}, the tag sprites through {@link SpriteManager#getSprite}, both on the client
 * thread). Nothing heavier: the runway and the rest are worked out on the tick ({@link OverlayFeed}).</p>
 *
 * <ul>
 * <li><b>Background.</b> RuneLite's standard one, so the player's "Overlay color" setting applies.</li>
 * <li><b>Width.</b> {@link #DEFAULT_WIDTH} by default, set once; a resize by the player wins
 * ({@link OverlayPanel#render} uses the overlay's preferred size when there is one).</li>
 * <li><b>Font.</b> The one RuneLite hands the overlay (the Interface font setting). Only the key number and a critical
 * runway are drawn in RuneScape Bold, and only while that font is the default RuneScape Plain. Text is cut to the
 * width with the live font's metrics ({@link OverlayRows#fitted}).</li>
 * <li><b>Colours.</b> From {@link LedgerTheme}: values in white, the task name in the game's orange, states, the
 * runway and a loss in the game's yellow, a profit in the light green; every text keeps the black shadow. The bar has an
 * opaque track.</li>
 * </ul>
 */
final class SlayerLedgerOverlay extends OverlayPanel
{
	/** The overlay's default width: 152 px of content inside the standard 4 px borders. */
	static final int DEFAULT_WIDTH = 160;
	/** The bar's height: the component's minimum. */
	static final int BAR_HEIGHT = 16;
	/** Between rows. */
	static final int ROW_GAP = 1;

	private final SlayerLedgerPlugin plugin;
	private final SlayerLedgerConfig config;
	private final ItemManager itemManager;
	private final SpriteManager spriteManager;

	SlayerLedgerOverlay(SlayerLedgerPlugin plugin, SlayerLedgerConfig config, ItemManager itemManager,
		SpriteManager spriteManager)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		this.itemManager = itemManager;
		this.spriteManager = spriteManager;
		setPosition(OverlayPosition.TOP_LEFT);
		setPriority(Overlay.PRIORITY_LOW);
		// Set once: OverlayPanel uses the player's resize, when there is one, instead.
		panelComponent.setPreferredSize(new Dimension(DEFAULT_WIDTH, 0));
		panelComponent.setGap(new Point(0, ROW_GAP));
		addMenuEntry(MenuAction.RUNELITE_OVERLAY_CONFIG, OverlayManager.OPTION_CONFIGURE, "Slayer Ledger overlay");
	}

	/** The groups the settings turn on. */
	static OverlayRows.Groups groups(SlayerLedgerConfig config)
	{
		return new OverlayRows.Groups(config.overlayProgress(), config.overlayPace(), config.overlayHaul(),
			config.overlayRunway());
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showTaskOverlay())
		{
			return null;
		}
		final List<OverlayRows.Row> rows = OverlayRows.rows(plugin.overlayData(), groups(config));
		if (rows.isEmpty())
		{
			return null;
		}
		final Font font = graphics.getFont();
		// Bold only over the default Plain (DESIGN_PRINCIPLES.md 2): any other font the player chose is kept as it is.
		final Font bold = FontManager.getRunescapeFont().equals(font) ? FontManager.getRunescapeBoldFont() : null;
		final FontMetrics metrics = graphics.getFontMetrics(font);
		final FontMetrics boldMetrics = bold == null ? null : graphics.getFontMetrics(bold);
		final Dimension size = getPreferredSize() != null ? getPreferredSize() : panelComponent.getPreferredSize();
		final int width = size.width - 2 * ComponentConstants.STANDARD_BORDER;
		for (OverlayRows.Row row : rows)
		{
			final LayoutableRenderableEntity component = component(OverlayRows.fitted(row, width, metrics, boldMetrics),
				bold);
			if (component != null)
			{
				panelComponent.getChildren().add(component);
			}
		}
		return super.render(graphics);
	}

	/** The component that draws {@code row}, already fitted; {@code bold} is the Bold font, or {@code null}. */
	private LayoutableRenderableEntity component(OverlayRows.Row row, Font bold)
	{
		switch (row.getKind())
		{
			case IDENTITY:
				return identity(row);
			case BAR:
			{
				final ProgressBarComponent bar = new ProgressBarComponent();
				bar.setLabelDisplayMode(ProgressBarComponent.LabelDisplayMode.TEXT_ONLY);
				bar.setCenterLabel(row.getLeft());
				bar.setMinimum(0);
				bar.setMaximum(row.getTotal());
				bar.setValue(row.getDone());
				bar.setForegroundColor(LedgerTheme.BAR_FILL);
				bar.setBackgroundColor(LedgerTheme.TRACK);
				bar.setFontColor(LedgerTheme.VALUE);
				bar.setPreferredSize(new Dimension(0, BAR_HEIGHT));
				return bar;
			}
			case RUNWAY:
			{
				final Image icon = row.getIconItemId() == null ? null : itemManager.getImage(row.getIconItemId());
				return OverlayIconLine.leading(icon, OverlayRows.SMALL_ICON_WIDTH, OverlayRows.SMALL_ICON_HEIGHT,
					row.getLeft(), color(row.getLeftTone()), row.isBold() ? bold : null);
			}
			default:
				return LineComponent.builder()
					.left(row.getLeft())
					.right(row.getRight())
					.leftColor(color(row.getLeftTone()))
					.rightColor(color(row.getRightTone()))
					.leftFont(row.isBold() ? bold : null)
					.build();
		}
	}

	/** The task icon beside the name and the spot line with its tag sprites; the two lines alone without an icon. */
	private LayoutableRenderableEntity identity(OverlayRows.Row row)
	{
		final List<Image> tags = new ArrayList<>(row.getTags().size());
		for (SetupTagIcons.Icon tag : row.getTags())
		{
			final Image image = tag.isItem() ? itemManager.getImage(tag.getId()) : spriteManager.getSprite(tag.getId(), 0);
			if (image != null)
			{
				tags.add(image);
			}
		}
		final SplitComponent lines = SplitComponent.builder()
			.first(LineComponent.builder().left(row.getLeft()).leftColor(color(row.getLeftTone())).build())
			.second(OverlayIconLine.trailing(row.getSecond(), LedgerTheme.VALUE, tags, OverlayRows.TAG_SIZE))
			.orientation(ComponentOrientation.VERTICAL)
			.build();
		final BufferedImage icon = row.getIconItemId() == null ? null : itemManager.getImage(row.getIconItemId());
		if (icon == null)
		{
			return lines;
		}
		return SplitComponent.builder()
			.first(new ImageComponent(icon))
			.second(lines)
			.orientation(ComponentOrientation.HORIZONTAL)
			.gap(new Point(OverlayRows.ICON_GAP, 0))
			.build();
	}

	/** The {@link LedgerTheme} colour of a tone. */
	static Color color(OverlayRows.Tone tone)
	{
		switch (tone)
		{
			case ALERT:
				return LedgerTheme.ALERT;
			case NAME:
				return LedgerTheme.HEADING;
			case GOOD:
				return LedgerTheme.GOOD;
			default:
				return LedgerTheme.VALUE;
		}
	}
}
