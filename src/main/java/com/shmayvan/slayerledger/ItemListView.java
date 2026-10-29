package com.shmayvan.slayerledger;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * A text list of the sidebar ({@link TitledListView}): one line per {@link ItemListView.Line}, a small skill icon
 * ({@link SkillIcons}, as RuneLite's XP tracker shows), the name (cut short, with the full name in the tooltip) and the
 * amount on the right. Every line keeps an icon-sized slot before its name, empty for a line with no skill, so the
 * names stay aligned and the lines keep one height. A line can show a small item sprite in the slot instead
 * ({@link Line#getIconItemId}, {@link SmallItemIcon}; Overall totals' monsters assigned). At most
 * {@link ItemDetails#MAX_ROWS} lines show until "+N more" is clicked. The per-skill XP lists use it, with
 * {@link SkillXp.Row} lines; supplies and loot are item icons ({@link ItemGridView}). EDT only.
 */
class ItemListView extends TitledListView<ItemListView.Line>
{
	/** One line of a list: a name on the left, an amount on the right and a tooltip. */
	interface Line
	{
		/** Name to show (never {@code null}); cut short when too long. */
		String getName();

		/**
		 * Key of the skill whose small icon shows before the name (the name of RuneLite's {@code Skill} constant), or
		 * {@code null} for none.
		 */
		String getSkill();

		/**
		 * Item whose sprite shows small in the icon slot when the line has no skill, or {@code null} for none
		 * (the slot stays empty).
		 */
		default Integer getIconItemId()
		{
			return null;
		}

		/** Right-aligned amount text. */
		String amountText();

		/** HTML tooltip of the line. */
		String tooltip();
	}

	/**
	 * Width of the icon slot before a name: the small skill icons are 16 px wide (Ranged 18 px; checked against
	 * RuneLite 1.13.4's {@code skill_icons_small}).
	 */
	static final int ICON_WIDTH = 18;
	/** Height of the icon slot, as in the XP tracker's compact rows; Ranged's 18 px icon loses a pixel at each edge. */
	static final int ICON_HEIGHT = 16;

	/** What the lines are ("skills"), for the tooltips. */
	private final String noun;
	private final SkillIcons icons;
	/** Draws the item sprites of lines with {@link Line#getIconItemId}. */
	private final ItemIcons itemIcons;
	private final JLabel moreLabel = new JLabel();
	/** Whether every line is shown, past {@link ItemDetails#MAX_ROWS}. */
	private boolean showAll;
	/** Told when the player clicks "+N more" or "Show less", or {@code null}. */
	private Consumer<Boolean> showAllListener;

	/**
	 * @param noun what the lines are, for the tooltips ("skills")
	 * @param icons draws the small skill icons before the names
	 */
	ItemListView(String title, boolean collapsible, Color background, String noun, SkillIcons icons)
	{
		this(title, collapsible, background, noun, icons, (label, itemId, quantity) ->
		{
		});
	}

	/**
	 * @param noun what the lines are, for the tooltips ("monsters")
	 * @param icons draws the small skill icons before the names
	 * @param itemIcons draws the item sprites of lines with {@link Line#getIconItemId}, scaled to the slot
	 */
	ItemListView(String title, boolean collapsible, Color background, String noun, SkillIcons icons,
		ItemIcons itemIcons)
	{
		super(title, collapsible, background, noun);
		this.noun = noun;
		this.icons = icons;
		this.itemIcons = itemIcons;
		// Name for tests, which find it from the list itself; no visible effect.
		moreLabel.setName("itemList.more");
		moreLabel.setFont(FontManager.getRunescapeSmallFont());
		moreLabel.setForeground(ColorScheme.BRAND_ORANGE);
		moreLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		moreLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		moreLabel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1)
				{
					toggleShowAll();
				}
			}
		});
		rebuild();
	}

	/** Shows every line, or only the first {@link ItemDetails#MAX_ROWS} again, as a click on "+N more" does. */
	void toggleShowAll()
	{
		showAll = !showAll;
		rebuild();
		if (showAllListener != null)
		{
			showAllListener.accept(showAll);
		}
	}

	/**
	 * Starts with every line shown (or not), and tells {@code listener} when the player changes it, so a list that is
	 * built again (a history row's XP) keeps the choice.
	 */
	void setShowAll(boolean shown, Consumer<Boolean> listener)
	{
		showAllListener = listener;
		if (shown != showAll)
		{
			showAll = shown;
			rebuild();
		}
	}

	@Override
	void fillBody(JPanel body, List<Line> shown)
	{
		for (Line row : ItemDetails.visible(shown, showAll))
		{
			body.add(rowLine(row));
		}
		int hidden = ItemDetails.hiddenCount(shown.size());
		if (hidden > 0)
		{
			moreLabel.setText(showAll ? "Show less" : "+" + hidden + " more");
			moreLabel.setToolTipText(showAll ? "Show only the top " + ItemDetails.MAX_ROWS
				: "Click to show all " + shown.size() + " " + noun);
			body.add(moreLabel);
		}
	}

	/** The skill icon's slot, then the name filling the line (cut short if too long); the amount is right-aligned. */
	private JPanel rowLine(Line row)
	{
		JPanel line = new JPanel(new BorderLayout(4, 0));
		line.setOpaque(false);
		line.setAlignmentX(Component.LEFT_ALIGNMENT);
		line.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
		line.add(iconSlot(row), BorderLayout.WEST);
		JLabel name = label(row.getName(), Color.WHITE);
		// The name asks for no width of its own, so a long one is cut short in the space left rather than widening
		// the sidebar (long monster names in "Monsters assigned").
		name.setPreferredSize(new Dimension(0, name.getPreferredSize().height));
		line.add(name, BorderLayout.CENTER);
		JPanel right = new JPanel(new BorderLayout(4, 0));
		right.setOpaque(false);
		right.add(label(row.amountText(), ColorScheme.LIGHT_GRAY_COLOR), BorderLayout.EAST);
		line.add(right, BorderLayout.EAST);
		// The tooltip has the full name and the exact amounts.
		line.setToolTipText(row.tooltip());
		return line;
	}

	/**
	 * A fixed-size slot with the skill's small icon, else the line's item sprite at the same size, or empty for a line
	 * with neither, so the names line up.
	 */
	private JLabel iconSlot(Line row)
	{
		String skill = row.getSkill();
		Integer itemId = row.getIconItemId();
		JLabel slot = new JLabel();
		// Name for tests, which read the icon from it; no visible effect.
		slot.setName("itemList.icon");
		Dimension size = new Dimension(ICON_WIDTH, ICON_HEIGHT);
		slot.setPreferredSize(size);
		slot.setMinimumSize(size);
		slot.setHorizontalAlignment(SwingConstants.CENTER);
		slot.setVerticalAlignment(SwingConstants.CENTER);
		if (skill != null)
		{
			icons.apply(slot, skill);
		}
		else if (itemId != null)
		{
			SmallItemIcon.apply(itemIcons, slot, itemId);
		}
		return slot;
	}
}
