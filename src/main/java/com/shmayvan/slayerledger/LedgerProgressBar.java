package com.shmayvan.slayerledger;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import net.runelite.client.ui.components.ProgressBar;

/**
 * The live task card's progress bar (DESIGN_PRINCIPLES.md 2.4 and 3.4): RuneLite's {@link ProgressBar}, drawn in
 * {@link LedgerTheme}'s track and fill, with its centre label (the kills done of the task, {@code 73 / 201}) as wide as
 * the bar. RuneLite lays its left, centre and right labels out in a 1 by 3 grid, which gives the centre one a third of
 * the width: in the card's 199 px that cut {@code 2,000 / 3,985} (72 px in RuneScape Small) to {@code 1 / 3,...}. Here
 * every label spans the bar; the left and right ones are never set, so they draw nothing. Its preferred size stays
 * the 100 by 16 px RuneLite's constructor sets, so the labels never decide its width: 100 px is well inside the card,
 * whose bar row stretches it to the card's width, and 16 px is the bar's height. EDT only.
 */
final class LedgerProgressBar extends ProgressBar
{
	LedgerProgressBar()
	{
		setBackground(LedgerTheme.TRACK);
		setForeground(LedgerTheme.BAR_FILL);
		setLayout(new FullWidthLayout());
	}

	/** The labels overlap (each spans the bar), so Swing must not assume they tile it when it repaints one. */
	@Override
	public boolean isOptimizedDrawingEnabled()
	{
		return false;
	}

	/**
	 * Gives every label the whole bar. Its sizes ask for no width; only the minimum size comes from it, as RuneLite's
	 * constructor sets the bar's preferred size.
	 */
	private static final class FullWidthLayout implements LayoutManager
	{
		@Override
		public void addLayoutComponent(String name, Component component)
		{
		}

		@Override
		public void removeLayoutComponent(Component component)
		{
		}

		@Override
		public Dimension preferredLayoutSize(Container parent)
		{
			Insets insets = parent.getInsets();
			int height = 0;
			for (Component label : parent.getComponents())
			{
				height = Math.max(height, label.getPreferredSize().height);
			}
			return new Dimension(insets.left + insets.right, insets.top + insets.bottom + height);
		}

		@Override
		public Dimension minimumLayoutSize(Container parent)
		{
			return preferredLayoutSize(parent);
		}

		@Override
		public void layoutContainer(Container parent)
		{
			Insets insets = parent.getInsets();
			int width = Math.max(0, parent.getWidth() - insets.left - insets.right);
			int height = Math.max(0, parent.getHeight() - insets.top - insets.bottom);
			for (Component label : parent.getComponents())
			{
				label.setBounds(insets.left, insets.top, width, height);
			}
		}
	}
}
