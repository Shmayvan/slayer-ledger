package com.shmayvan.slayerledger;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import javax.swing.event.*;
import lombok.*;

/**
 * The live task card's setup tags (DESIGN_PRINCIPLES.md 3.4, V2 plan 4.3, sprite toggles): Cannon,
 * Style and Gear as three small toggles in one row. Each is its tag's name in Small and, after it, the game image
 * {@link SetupTagIcons} has for the tag's effective value, fitted into the sidebar's 18 by 16 icon slot. A value with no
 * image has a short word in its place, drawn in {@link LedgerTheme}'s colours: {@code ?} (in the hint colour) for
 * Unknown, {@code Mix} for Mixed and {@code No} for no cannon. A value the player set has a small corner mark in the
 * heading colour, and its tooltip says {@code Set by you}: the mark is never the only cue. Each tooltip gives the tag,
 * its value, whether it was detected or set by you, whether the detected value is an estimate and what it was detected
 * from.
 *
 * <p>A toggle is a button, so Tab reaches it and Space presses it, as the status strip's buttons. A press, or a right
 * click, opens a menu of the tag's {@link SetupChoices}: the detected value first, which clears the player's override,
 * then every value they can set, with the current choice selected; a click on the toggle while its menu is open closes
 * it. A pick goes to {@link #setOnChoose}'s listener and shows at once. The row asks only for the width its three
 * toggles need (188 to 192 px in the RuneScape fonts), inside the card's 199 px. The images come from the sidebar's
 * {@link SetupImages}, which asks for each once, shared with the Task history rows; the toggles are redrawn only when
 * the tags change ({@link #show}), never while a menu is open. EDT only.</p>
 */
final class SetupToggles
{
	/** A toggle's tooltip's last line. */
	static final String HINT = "Click to set it yourself; Detected: uses the detected value.";
	/** The word drawn for Unknown, in the hint colour. */
	static final String UNKNOWN_WORD = "?";
	/** The image slot: the sidebar's small icon slot, as the XP lists' ({@link ItemListView}). */
	static final int SLOT_WIDTH = ItemListView.ICON_WIDTH;
	static final int SLOT_HEIGHT = ItemListView.ICON_HEIGHT;
	/** The override mark: a right triangle in the slot's top right corner, this many pixels a side. */
	static final int MARK_SIZE = 6;
	/** Space between a toggle's name and its image. */
	private static final int ICON_GAP = 3;
	/** The least space between two toggles; the room left over is shared between the gaps. */
	private static final int TOGGLE_GAP = 4;

	/** A setup tag: its name, and how its value, override, image, choices and details are read. */
	@Getter
	@RequiredArgsConstructor
	enum Tag
	{
		CANNON("Cannon"),
		STYLE("Style"),
		GEAR("Gear");

		/** The tag as shown: {@code Cannon}, {@code Style}, {@code Gear}. */
		private final String label;

		/** The name of its toggle's parts ({@code setup.cannon}, ...), for tests; no visible effect. */
		String key()
		{
			return "setup." + name().toLowerCase(Locale.ROOT);
		}

		/** The effective value as shown, {@code ~} before an estimate: {@code Yes}, {@code ~Barrage}, {@code Unknown}. */
		String shown(SetupTags tags)
		{
			switch (this)
			{
				case CANNON:
					return SetupTags.shown(SetupTags.cannonLabel(tags.isCannon()), tags.isCannonEstimated());
				case STYLE:
					return SetupTags.shown(tags.getStyle().getLabel(), tags.isStyleEstimated());
				default:
					return SetupTags.shown(tags.getGear().getLabel(), tags.isGearEstimated());
			}
		}

		/** The detected value as shown, whatever the override. */
		String detectedShown(SetupTags tags)
		{
			switch (this)
			{
				case CANNON:
					return SetupTags.shown(SetupTags.cannonLabel(tags.isDetectedCannon()),
						tags.isDetectedCannonEstimated());
				case STYLE:
					return SetupTags.shown(tags.getDetectedStyle().getLabel(), tags.isDetectedStyleEstimated());
				default:
					return SetupTags.shown(tags.getDetectedGear().getLabel(), tags.isDetectedGearEstimated());
			}
		}

		/** Whether the detected value is an estimate (a migrated task), whatever the override. */
		boolean isDetectedEstimated(SetupTags tags)
		{
			switch (this)
			{
				case CANNON:
					return tags.isDetectedCannonEstimated();
				case STYLE:
					return tags.isDetectedStyleEstimated();
				default:
					return tags.isDetectedGearEstimated();
			}
		}

		/** Whether the player set the tag. */
		boolean isOverridden(SetupTags tags)
		{
			final SetupOverrides overrides = tags.getOverrides();
			switch (this)
			{
				case CANNON:
					return overrides.getCannon() != null;
				case STYLE:
					return overrides.getStyle() != null;
				default:
					return overrides.getGear() != null;
			}
		}

		/** The image of the effective value, or {@code null} for one without (no cannon, Mixed, Unknown). */
		SetupTagIcons.Icon icon(SetupTags tags)
		{
			switch (this)
			{
				case CANNON:
					return tags.isCannon() ? SetupTagIcons.Icon.CANNON : null;
				case STYLE:
					return SetupTagIcons.style(tags.getStyle());
				default:
					return SetupTagIcons.gear(tags.getGear());
			}
		}

		/** The menu's choices ({@link SetupChoices}): the detected value first, then every value that can be set. */
		List<? extends SetupChoices.Choice<?>> choices(SetupTags tags)
		{
			switch (this)
			{
				case CANNON:
					return SetupChoices.cannon(tags);
				case STYLE:
					return SetupChoices.style(tags);
				default:
					return SetupChoices.gear(tags);
			}
		}

		/** The index of the current choice in {@link #choices}: the override's, or 0 (detected) without one. */
		int selected(SetupTags tags)
		{
			final SetupOverrides overrides = tags.getOverrides();
			switch (this)
			{
				case CANNON:
					return SetupChoices.indexOf(SetupChoices.cannon(tags), overrides.getCannon());
				case STYLE:
					return SetupChoices.indexOf(SetupChoices.style(tags), overrides.getStyle());
				default:
					return SetupChoices.indexOf(SetupChoices.gear(tags), overrides.getGear());
			}
		}

		/** {@code overrides} with this tag's override set to a choice's value ({@code null} clears it). */
		SetupOverrides with(SetupOverrides overrides, Object value)
		{
			switch (this)
			{
				case CANNON:
					return overrides.withCannon((Boolean) value);
				case STYLE:
					return overrides.withStyle((SetupStyle) value);
				default:
					return overrides.withGear((GearFocus) value);
			}
		}

		/** What the tag was detected from ({@link SetupDetection}), plain text. */
		List<String> details(SetupKills kills, int taskKills)
		{
			switch (this)
			{
				case CANNON:
					return SetupDetection.cannonDetails(kills, taskKills);
				case STYLE:
					return SetupDetection.styleDetails(kills);
				default:
					return SetupDetection.gearDetails(kills);
			}
		}
	}

	/** The tag images, asked for once each and kept (shared with the Task history rows). */
	private final SetupImages images;
	/** The row of the three toggles; shown by the live section while a session is active. */
	private final JPanel row = new JPanel();
	private final Map<Tag, JButton> toggles = new EnumMap<>(Tag.class);
	private final Map<Tag, JPopupMenu> menus = new EnumMap<>(Tag.class);
	/**
	 * The toggle whose open menu the mouse press now going on closed, else {@code null}, with that press's time
	 * ({@link #menuClosing}): the click that press starts must not open the menu again.
	 */
	private Tag closedByPress;
	private long closingPressTime;
	/** Told the overrides the player picked. */
	private Consumer<SetupOverrides> onChoose = overrides -> { };
	/** The tags shown, or {@code null} before a session's are; the menus and a pick read them. */
	private SetupTags shown;
	/** What the tooltips' details come from, as last shown. */
	private SetupKills kills = SetupKills.NONE;
	private int taskKills;
	/** How many times the toggles were redrawn for new tags ({@link #getBuildCount}). */
	private int builds;

	/** @param images the sidebar's setup tag images */
	SetupToggles(SetupImages images)
	{
		this.images = images;
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		// Names for tests, which find the parts by them; no visible effect.
		row.setName("setup");
		for (Tag tag : Tag.values())
		{
			if (tag != Tag.CANNON)
			{
				row.add(Box.createHorizontalStrut(TOGGLE_GAP));
				row.add(Box.createHorizontalGlue());
			}
			row.add(toggle(tag));
		}
		images.addListener(this::arrived, row::repaint);
	}

	private JButton toggle(Tag tag)
	{
		final JButton toggle = new JButton(tag.getLabel());
		toggle.setName(tag.key());
		toggle.setFont(LedgerTheme.smallFont());
		toggle.setForeground(LedgerTheme.LABEL);
		toggle.setBackground(LedgerTheme.CARD);
		toggle.setFocusPainted(false);
		toggle.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LedgerTheme.BEVEL_INNER, 1),
			BorderFactory.createEmptyBorder(2, 4, 2, 4)));
		// The name, then the image.
		toggle.setHorizontalTextPosition(SwingConstants.LEADING);
		toggle.setIconTextGap(ICON_GAP);
		toggle.setIcon(new Slot(null, null, null, false, null, SLOT_WIDTH));

		final JPopupMenu menu = new JPopupMenu();
		menu.setName(tag.key() + ".menu");
		// Filled from the tags shown each time it opens: a right click, or Shift+F10, opens it as a click does.
		menu.addPopupMenuListener(new PopupMenuListener()
		{
			@Override
			public void popupMenuWillBecomeVisible(PopupMenuEvent e)
			{
				fillMenu(tag);
			}

			@Override
			public void popupMenuWillBecomeInvisible(PopupMenuEvent e)
			{
				menuClosing(tag, EventQueue.getCurrentEvent());
			}

			@Override
			public void popupMenuCanceled(PopupMenuEvent e)
			{
			}
		});
		toggle.setComponentPopupMenu(menu);
		toggle.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				pressed(e);
			}
		});
		toggle.addActionListener(e -> clicked(tag, EventQueue.getCurrentEvent()));
		toggles.put(tag, toggle);
		menus.put(tag, menu);
		return toggle;
	}

	/** The row of toggles, for the card. */
	JComponent getComponent()
	{
		return row;
	}

	/** Sets who is told the overrides the player picked (the live section, which saves them to its session). */
	void setOnChoose(Consumer<SetupOverrides> listener)
	{
		onChoose = listener == null ? overrides -> { } : listener;
	}

	/** Forgets the tags shown (another session or none): the next {@link #show} draws its own. */
	void clear()
	{
		shown = null;
	}

	/**
	 * Shows {@code tags}, detected from {@code setupKills} over {@code killCount} task kills (those that may have a kill
	 * context, {@link SessionTracker#getContextKills}, which the Cannon tooltip's "of" counts): the toggles are redrawn
	 * only when the tags differ from those shown and no menu is open (a pick shows at once, so the tags it led to draw
	 * nothing new); the tooltips change only when their text does.
	 */
	void show(SetupTags tags, SetupKills setupKills, int killCount)
	{
		kills = setupKills == null ? SetupKills.NONE : setupKills;
		taskKills = killCount;
		if (!Objects.equals(tags, shown) && !isMenuOpen())
		{
			shown = tags;
			builds++;
			for (Tag tag : Tag.values())
			{
				draw(tag);
			}
		}
		updateTooltips();
	}

	/** Whether the player has a toggle's menu open. */
	boolean isMenuOpen()
	{
		for (JPopupMenu menu : menus.values())
		{
			if (menu.isVisible())
			{
				return true;
			}
		}
		return false;
	}

	/** How many times the toggles were redrawn for new tags or a pick (the live section's performance seam). */
	int getBuildCount()
	{
		return builds;
	}

	/** Draws {@code tag}'s toggle for the tags shown: its image (or word) and the override mark. */
	private void draw(Tag tag)
	{
		final JButton toggle = toggles.get(tag);
		if (shown == null)
		{
			toggle.setIcon(new Slot(null, null, null, false, null, SLOT_WIDTH));
			return;
		}
		final String word = placeholder(tag, shown);
		final Image image = images.get(tag.icon(shown));
		final FontMetrics metrics = toggle.getFontMetrics(LedgerTheme.smallFont());
		final int width = word == null ? SLOT_WIDTH : Math.max(SLOT_WIDTH, metrics.stringWidth(word) + 2);
		final Color color = word != null && word.equals(UNKNOWN_WORD) ? LedgerTheme.HINT : LedgerTheme.VALUE;
		toggle.setIcon(new Slot(image, word, color, tag.isOverridden(shown), description(tag, shown), width));
	}

	/** {@code icon}'s image arrived after it was asked for (EDT): drawn on the toggle that shows it. */
	private void arrived(SetupTagIcons.Icon icon)
	{
		if (shown == null)
		{
			return;
		}
		for (Tag tag : Tag.values())
		{
			if (tag.icon(shown) == icon)
			{
				draw(tag);
			}
		}
	}

	private void updateTooltips()
	{
		for (Tag tag : Tag.values())
		{
			final JButton toggle = toggles.get(tag);
			final String tooltip = shown == null ? null : tooltip(tag, shown, kills, taskKills);
			if (!Objects.equals(tooltip, toggle.getToolTipText()))
			{
				toggle.setToolTipText(tooltip);
			}
		}
	}

	/**
	 * {@code tag}'s menu is closing while {@code event} is dispatched. Swing closes an open menu on a mouse press outside
	 * it before the component under the mouse gets the press ({@code BasicPopupMenuUI}'s mouse grabber), and the toggle
	 * acts on the release: so a press on the menu's own toggle is noted, and the click it starts only closes the menu
	 * ({@link #clicked}). Any other close (a pick, Escape, a press elsewhere) notes nothing.
	 */
	private void menuClosing(Tag tag, AWTEvent event)
	{
		if (event instanceof MouseEvent && event.getID() == MouseEvent.MOUSE_PRESSED
			&& ((MouseEvent) event).getComponent() == toggles.get(tag))
		{
			closedByPress = tag;
			closingPressTime = ((MouseEvent) event).getWhen();
		}
	}

	/** A mouse press on a toggle, after any menu it closed: a press that closed none forgets an older one's note. */
	private void pressed(MouseEvent press)
	{
		if (press.getWhen() != closingPressTime)
		{
			closedByPress = null;
		}
	}

	/**
	 * {@code tag}'s toggle fired (a mouse click, or Space, while {@code event} is dispatched): opens its menu, unless the
	 * click's press just closed that menu ({@link #menuClosing}), so a second click on an open menu's toggle closes it.
	 */
	private void clicked(Tag tag, AWTEvent event)
	{
		final boolean closedThisMenu = closedByPress == tag && event instanceof MouseEvent;
		closedByPress = null;
		if (!closedThisMenu)
		{
			openMenu(tag);
		}
	}

	/** Fills {@code tag}'s menu and opens it under the toggle. */
	private void openMenu(Tag tag)
	{
		fillMenu(tag);
		final JButton toggle = toggles.get(tag);
		// Only a toggle on screen has a place to open it (never in the headless tests).
		if (toggle.isShowing() && shown != null)
		{
			menus.get(tag).show(toggle, 0, toggle.getHeight());
		}
	}

	/** Fills {@code tag}'s menu with its choices for the tags shown, the current one selected. */
	private void fillMenu(Tag tag)
	{
		final JPopupMenu menu = menus.get(tag);
		menu.removeAll();
		if (shown == null)
		{
			return;
		}
		final List<? extends SetupChoices.Choice<?>> choices = tag.choices(shown);
		final int selected = tag.selected(shown);
		final ButtonGroup group = new ButtonGroup();
		for (int i = 0; i < choices.size(); i++)
		{
			final SetupChoices.Choice<?> choice = choices.get(i);
			final JRadioButtonMenuItem item = new JRadioButtonMenuItem(choice.getLabel(), i == selected);
			item.setFont(LedgerTheme.smallFont());
			final Object value = choice.getValue();
			item.addActionListener(e -> choose(tag, value));
			group.add(item);
			menu.add(item);
		}
	}

	/** The player picked {@code value} for {@code tag} ({@code null}: the detected value): shown, then passed on. */
	private void choose(Tag tag, Object value)
	{
		if (shown == null)
		{
			return;
		}
		final SetupOverrides chosen = tag.with(shown.getOverrides(), value);
		if (chosen.equals(shown.getOverrides()))
		{
			return;
		}
		shown = shown.withOverrides(chosen);
		builds++;
		draw(tag);
		updateTooltips();
		onChoose.accept(chosen);
	}

	/**
	 * The word drawn in place of an image for a value without one, or {@code null} for a value with an image:
	 * {@code ?} for Unknown, {@code Mix} for Mixed, {@code No} for no cannon. The fonts have no glyph that could stand
	 * for these, and emoji are not allowed (DESIGN_PRINCIPLES.md 4.4, 6).
	 */
	static String placeholder(Tag tag, SetupTags tags)
	{
		if (tag.icon(tags) != null)
		{
			return null;
		}
		switch (tag)
		{
			case CANNON:
				return SetupTags.cannonLabel(false);
			case STYLE:
				return tags.getStyle() == SetupStyle.UNKNOWN ? UNKNOWN_WORD : "Mix";
			default:
				return tags.getGear() == GearFocus.UNKNOWN ? UNKNOWN_WORD : "Mix";
		}
	}

	/**
	 * The toggle image's accessible description: the value as shown, with {@code , set by you} when the player set it
	 * ({@code Barrage, set by you}).
	 */
	static String description(Tag tag, SetupTags tags)
	{
		return tag.shown(tags) + (tag.isOverridden(tags) ? ", set by you" : "");
	}

	/**
	 * A toggle's tooltip, as HTML with no player text in it: the tag and its value ({@code Style: Barrage}),
	 * {@code Detected} or {@code Set by you; detected Melee}, the estimate note when the detected value is an estimate
	 * ({@link SetupTags#ESTIMATE_NOTE}), what it was detected from, and {@link #HINT}.
	 */
	static String tooltip(Tag tag, SetupTags tags, SetupKills kills, int taskKills)
	{
		final List<String> lines = new ArrayList<>();
		lines.add(tag.getLabel() + ": " + tag.shown(tags));
		lines.add(tag.isOverridden(tags) ? "Set by you; detected " + tag.detectedShown(tags) : "Detected");
		if (tag.isDetectedEstimated(tags))
		{
			lines.add(SetupTags.ESTIMATE_NOTE);
		}
		lines.addAll(tag.details(kills == null ? SetupKills.NONE : kills, taskKills));
		lines.add(HINT);
		return "<html>" + String.join("<br>", lines) + "</html>";
	}

	/**
	 * A toggle's image slot: the tag's image fitted into it (scaled down, never up, keeping its shape, centred), or a
	 * word centred in it, with the override mark in its top right corner. {@link #getImage()} is the image as it
	 * arrived, {@link #getDescription()} the value it shows.
	 */
	static final class Slot extends ImageIcon
	{
		private final String word;
		private final Color wordColor;
		private final boolean marked;
		private final int width;

		private Slot(Image image, String word, Color wordColor, boolean marked, String description, int width)
		{
			if (image != null)
			{
				setImage(image);
			}
			setDescription(description);
			this.word = word;
			this.wordColor = wordColor;
			this.marked = marked;
			this.width = width;
		}

		@Override
		public int getIconWidth()
		{
			return width;
		}

		@Override
		public int getIconHeight()
		{
			return SLOT_HEIGHT;
		}

		@Override
		public synchronized void paintIcon(Component c, Graphics g, int x, int y)
		{
			final Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				final Image image = getImage();
				if (image != null)
				{
					drawFitted(g2, image, x, y, c);
				}
				else if (word != null)
				{
					g2.setFont(LedgerTheme.smallFont());
					g2.setColor(wordColor);
					final FontMetrics metrics = g2.getFontMetrics();
					g2.drawString(word, x + (width - metrics.stringWidth(word)) / 2,
						y + (SLOT_HEIGHT + metrics.getAscent() - metrics.getDescent()) / 2);
				}
				if (marked)
				{
					g2.setColor(LedgerTheme.HEADING);
					g2.fillPolygon(new int[]{x + width - MARK_SIZE, x + width, x + width},
						new int[]{y, y, y + MARK_SIZE}, 3);
				}
			}
			finally
			{
				g2.dispose();
			}
		}

		private void drawFitted(Graphics2D g2, Image image, int x, int y, Component c)
		{
			final int imageWidth = image.getWidth(null);
			final int imageHeight = image.getHeight(null);
			if (imageWidth <= 0 || imageHeight <= 0)
			{
				return;
			}
			final double scale = Math.min(1, Math.min(width / (double) imageWidth, SLOT_HEIGHT / (double) imageHeight));
			final int drawWidth = Math.max(1, (int) Math.round(imageWidth * scale));
			final int drawHeight = Math.max(1, (int) Math.round(imageHeight * scale));
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g2.drawImage(image, x + (width - drawWidth) / 2, y + (SLOT_HEIGHT - drawHeight) / 2, drawWidth, drawHeight,
				c);
		}
	}
}
