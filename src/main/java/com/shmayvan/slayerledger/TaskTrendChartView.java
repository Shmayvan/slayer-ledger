package com.shmayvan.slayerledger;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;

/** Small Swing chart of one metric across completed tasks. */
final class TaskTrendChartView extends JPanel
{
	private static final int LEFT = 54;
	private static final int RIGHT = 8;
	private static final int TOP = 12;
	private static final int BOTTOM = 28;
	private static final int HEIGHT = 150;
	private static final int POINT_RADIUS = 3;
	/** Aimed-for number of y-axis steps; nice-number steps give 3 to 6. */
	private static final int TARGET_STEPS = 4;
	private static final Stroke LINE = new BasicStroke(1.5f);
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT);
	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT);

	private List<TaskChart.Point> points = Collections.emptyList();
	private String metricLabel = "";
	private int[] pointXs = new int[0];
	private int[] pointYs = new int[0];
	private DateTimeFormatter dateFormat = DATE.withZone(ZoneId.systemDefault());
	private DateTimeFormatter dateTimeFormat = DATE_TIME.withZone(ZoneId.systemDefault());

	TaskTrendChartView()
	{
		setOpaque(true);
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setToolTipText("");
		// No preferred width, so it never widens the sidebar; the box layout stretches it across.
		setPreferredSize(new Dimension(0, HEIGHT));
		setMaximumSize(new Dimension(Integer.MAX_VALUE, HEIGHT));
	}

	/** Plots {@code points}, labelled {@code metricLabel} (as the metric choice shows it) in the tooltips. */
	void setData(List<TaskChart.Point> points, String metricLabel, ZoneId zone)
	{
		this.points = points == null ? Collections.emptyList() : new ArrayList<>(points);
		this.metricLabel = metricLabel == null ? "" : metricLabel;
		ZoneId shownZone = zone == null ? ZoneId.systemDefault() : zone;
		dateFormat = DATE.withZone(shownZone);
		dateTimeFormat = DATE_TIME.withZone(shownZone);
		pointXs = new int[0];
		pointYs = new int[0];
		repaint();
	}

	/** Test seam: the number of points drawn, which the chart doesn't show as text. */
	int getPointCount()
	{
		return points.size();
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		super.paintComponent(graphics);
		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setFont(getFont());
			FontMetrics font = g.getFontMetrics();
			if (points.isEmpty())
			{
				g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
				g.drawString("No completed task data.", 8, TOP + font.getAscent());
				pointXs = new int[0];
				pointYs = new int[0];
				return;
			}

			int width = Math.max(1, getWidth() - LEFT - RIGHT);
			int height = Math.max(1, getHeight() - TOP - BOTTOM);
			int x0 = LEFT;
			int y0 = TOP + height;
			long minimum = Long.MAX_VALUE;
			long maximum = Long.MIN_VALUE;
			for (TaskChart.Point point : points)
			{
				minimum = Math.min(minimum, point.getRatePerHour());
				maximum = Math.max(maximum, point.getRatePerHour());
			}
			long[] ticks = ticks(minimum, maximum);
			double low = ticks[0];
			double span = Math.max(1d, (double) ticks[ticks.length - 1] - low);
			pointXs = new int[points.size()];
			pointYs = new int[points.size()];

			String lastLabel = null;
			for (long tick : ticks)
			{
				int y = y0 - (int) Math.round(height * ((tick - low) / span));
				g.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
				g.drawLine(x0, y, x0 + width, y);
				// Short amounts can round two ticks to the same text; label only the first.
				String label = LedgerHistory.formatAmount(tick, false);
				if (!label.equals(lastLabel))
				{
					g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
					g.drawString(label, Math.max(0, LEFT - font.stringWidth(label) - 5), y + font.getAscent() / 2);
					lastLabel = label;
				}
			}

			long firstTime = points.get(0).getEndedAt();
			long lastTime = points.get(points.size() - 1).getEndedAt();
			for (int i = 0; i < points.size(); i++)
			{
				TaskChart.Point point = points.get(i);
				double xRatio = lastTime == firstTime
					? (points.size() == 1 ? 0.5d : i / (double) (points.size() - 1))
					: (point.getEndedAt() - firstTime) / (double) (lastTime - firstTime);
				double yRatio = (point.getRatePerHour() - low) / span;
				pointXs[i] = x0 + (int) Math.round(width * xRatio);
				pointYs[i] = y0 - (int) Math.round(height * yRatio);
			}

			g.setColor(ColorScheme.BRAND_ORANGE);
			g.setStroke(LINE);
			for (int i = 1; i < points.size(); i++)
			{
				g.drawLine(pointXs[i - 1], pointYs[i - 1], pointXs[i], pointYs[i]);
			}
			for (int i = 0; i < points.size(); i++)
			{
				g.fillOval(pointXs[i] - POINT_RADIUS, pointYs[i] - POINT_RADIUS, POINT_RADIUS * 2 + 1,
					POINT_RADIUS * 2 + 1);
			}
			g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
			int dateY = y0 + font.getAscent() + 5;
			String firstDate = dateFormat.format(Instant.ofEpochMilli(firstTime));
			if (points.size() == 1)
			{
				// A single point sits in the middle; its date goes under it.
				int x = pointXs[0] - font.stringWidth(firstDate) / 2;
				g.drawString(firstDate, Math.max(0, Math.min(x, getWidth() - font.stringWidth(firstDate))), dateY);
				return;
			}
			String lastDate = dateFormat.format(Instant.ofEpochMilli(lastTime));
			g.drawString(firstDate, x0, dateY);
			int lastLabelX = x0 + width - font.stringWidth(lastDate);
			if (!firstDate.equals(lastDate))
			{
				g.drawString(lastDate, Math.max(x0, lastLabelX), dateY);
			}
		}
		finally
		{
			g.dispose();
		}
	}

	@Override
	public String getToolTipText(MouseEvent event)
	{
		int nearest = -1;
		double nearestDistance = 9d;
		for (int i = 0; i < pointXs.length; i++)
		{
			double distance = Math.hypot(event.getX() - pointXs[i], event.getY() - pointYs[i]);
			if (distance < nearestDistance)
			{
				nearest = i;
				nearestDistance = distance;
			}
		}
		if (nearest < 0)
		{
			return null;
		}
		TaskChart.Point point = points.get(nearest);
		StringBuilder tooltip = new StringBuilder("<html>")
			.append(LedgerHistory.escapeHtml(dateTimeFormat.format(Instant.ofEpochMilli(point.getEndedAt()))))
			.append("<br>").append(LedgerHistory.escapeHtml(metricLabel)).append(": ")
			.append(LedgerHistory.formatAmount(point.getRatePerHour(), point.isApproximate()));
		if (point.getNote() != null && !point.getNote().trim().isEmpty())
		{
			tooltip.append("<br>Note: ").append(LedgerHistory.escapeHtml(point.getNote()));
		}
		if (point.isApproximate())
		{
			tooltip.append("<br>~ means some item prices were unavailable, or an older task kept its saved totals.");
		}
		return tooltip.append("</html>").toString();
	}

	/**
	 * Y-axis ticks covering {@code minimum..maximum}: multiples of a 1, 2 or 5 times a power of ten step, about
	 * {@link #TARGET_STEPS} of them, the first at or below the minimum and the last at or above the maximum.
	 */
	static long[] ticks(long minimum, long maximum)
	{
		long low = minimum;
		long high = maximum;
		if (low == high)
		{
			long padding = Math.max(1, Math.abs(low) / 10);
			low -= padding;
			high += padding;
		}
		long step = niceStep((high - (double) low) / TARGET_STEPS);
		long first = Math.floorDiv(low, step) * step;
		long last = -Math.floorDiv(-high, step) * step;
		int count = (int) Math.min(TARGET_STEPS * 3L, (last - first) / step) + 1;
		long[] ticks = new long[count];
		for (int i = 0; i < count; i++)
		{
			ticks[i] = first + i * step;
		}
		return ticks;
	}

	private static long niceStep(double raw)
	{
		if (raw <= 1)
		{
			return 1;
		}
		double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
		double fraction = raw / magnitude;
		double nice = fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10;
		return Math.max(1, (long) (nice * magnitude));
	}
}
