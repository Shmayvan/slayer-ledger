package com.shmayvan.slayerledger;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * Shows the active Slayer Ledger session without the sidebar open. Rendered on the client thread every frame, so it
 * only reads the plugin's small snapshot ({@link SlayerLedgerPlugin#overlayData()}); the rows come from
 * {@link OverlayRows}.
 */
final class SlayerLedgerOverlay extends OverlayPanel
{
	/** Colour of warning rows such as {@link OverlayRows#PAUSED_LABEL}: RuneLite's orange for in-progress states. */
	static final Color WARNING_COLOR = ColorScheme.PROGRESS_INPROGRESS_COLOR;

	private final SlayerLedgerPlugin plugin;
	private final SlayerLedgerConfig config;

	SlayerLedgerOverlay(SlayerLedgerPlugin plugin, SlayerLedgerConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		setPriority(Overlay.PRIORITY_LOW);
		addMenuEntry(MenuAction.RUNELITE_OVERLAY_CONFIG, OverlayManager.OPTION_CONFIGURE, "Slayer Ledger overlay");
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showTaskOverlay())
		{
			return null;
		}

		List<OverlayRows.Row> rows = OverlayRows.rows(plugin.overlayData(), config.overlayTask(), config.overlayKills(),
			config.overlayTimeLeft(), config.overlayProfit());
		if (rows.isEmpty())
		{
			return null;
		}
		for (OverlayRows.Row row : rows)
		{
			final LineComponent.LineComponentBuilder line = LineComponent.builder()
				.left(row.getLabel())
				.right(row.getValue());
			if (row.isWarning())
			{
				line.leftColor(WARNING_COLOR).rightColor(WARNING_COLOR);
			}
			panelComponent.getChildren().add(line.build());
		}
		return super.render(graphics);
	}
}
