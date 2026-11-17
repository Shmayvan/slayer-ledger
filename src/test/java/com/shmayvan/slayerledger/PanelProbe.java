package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.SwingLookup.children;
import static com.shmayvan.slayerledger.SwingLookup.click;
import static com.shmayvan.slayerledger.SwingLookup.find;
import static com.shmayvan.slayerledger.SwingLookup.findOrNull;
import static com.shmayvan.slayerledger.SwingLookup.items;
import static com.shmayvan.slayerledger.SwingLookup.lineText;
import static com.shmayvan.slayerledger.SwingLookup.text;
import static com.shmayvan.slayerledger.SwingLookup.tooltip;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.PopupMenuListener;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.ProgressBar;

/**
 * Reads and drives {@link SlayerLedgerPanel} as the player sees and uses it, through the names its components carry
 * ({@link SwingLookup}): the text of its rows, their tooltips, which parts are shown, and clicks and picks. One probe
 * per section mirrors that section's view. The build counts are the panel's performance seams. Use it on the EDT.
 */
final class PanelProbe
{
	private final SlayerLedgerPanel panel;
	/** What an item icon shows ({@link PluginHarness#iconText}), for the item grids. */
	private final Function<Icon, String> iconText;

	PanelProbe(SlayerLedgerPanel panel, Function<Icon, String> iconText)
	{
		this.panel = panel;
		this.iconText = iconText;
	}

	// ---- Live session ----

	/** Whether the "Check your blowpipe" hint is shown. */
	boolean isDartHintShown()
	{
		return SwingLookup.isVisible(panel, "dartHint");
	}

	/** Whether the live values are shown: the supplies and loot grids under their sub-headers, with their values. */
	boolean isValuesShown()
	{
		return SwingLookup.isVisible(panel, "liveSupplies");
	}

	/**
	 * The live values, {@code "Loot: 344K | Supplies: 132K | Profit: +212K"}: the loot's and the supplies' values from
	 * their grids' sub-headers and the stats' profit; {@code null} while the grids are hidden.
	 */
	String getValuesText()
	{
		if (!isValuesShown())
		{
			return null;
		}
		return "Loot: " + headerValue("liveLoot") + " | Supplies: " + headerValue("liveSupplies") + " | Profit: "
			+ statValue("profit");
	}

	/** The live values' tooltip (on the profit and the grids' values), or {@code null} while the grids are hidden. */
	String getValuesTooltip()
	{
		return isValuesShown() ? tooltip(panel, "stats.profit") : null;
	}

	/** The value on the right of the live grid or list named {@code name}'s sub-header. */
	private String headerValue(String name)
	{
		return text(find(panel, name, Container.class), "itemList.value");
	}

	// ---- Live task card ----

	/** Whether the live card's stats grid is shown (while a session is active). */
	boolean isStatsShown()
	{
		return SwingLookup.isVisible(panel, "stats");
	}

	/**
	 * The stats grid's cells, in order (left to right, then down), each {@code "<label> | <value>"} with the value in
	 * full ({@link FitLabel#getFullText()}); empty while the grid is hidden.
	 */
	List<String> getStatsRows()
	{
		return statsRows(false);
	}

	/**
	 * The stats grid's cells as the player sees them, after {@link #layOutLiveCard}: as {@link #getStatsRows}, with
	 * each value as displayed, whole or cut short with {@link FitLabel#ELLIPSIS}.
	 */
	List<String> getStatsRowsShown()
	{
		return statsRows(true);
	}

	private List<String> statsRows(boolean shown)
	{
		List<String> rows = new ArrayList<>();
		Container stats = find(panel, "stats", Container.class);
		if (!stats.isVisible())
		{
			return rows;
		}
		for (Component cell : stats.getComponents())
		{
			Container c = (Container) cell;
			FitLabel value = (FitLabel) c.getComponent(1);
			rows.add(text(c.getComponent(0)) + " | " + (shown ? value.getText() : value.getFullText()));
		}
		return rows;
	}

	/** The tooltips of the stats grid's cells, in the order of {@link #getStatsRows}. */
	List<String> getStatsTooltips()
	{
		List<String> tooltips = new ArrayList<>();
		for (Component cell : find(panel, "stats", Container.class).getComponents())
		{
			tooltips.add(((JComponent) cell).getToolTipText());
		}
		return tooltips;
	}

	/** The value of the stats cell {@code key} ({@code "profit"}, {@code "timeLeft"} and so on), in full. */
	String statValue(String key)
	{
		return find(panel, "stats." + key + ".value", FitLabel.class).getFullText();
	}

	/** The tooltip of the stats cell {@code key}. */
	String statTooltip(String key)
	{
		return tooltip(panel, "stats." + key);
	}

	/** The colour of the stats cell {@code key}'s value. */
	Color statColor(String key)
	{
		return find(panel, "stats." + key + ".value").getForeground();
	}

	/** The progress bar's label ({@code "73 / 201"}), or {@code null} while the bar is hidden. */
	String getBarText()
	{
		if (!SwingLookup.isVisible(panel, "progress"))
		{
			return null;
		}
		// RuneLite's ProgressBar: its left, centre and right labels, in that order.
		return text(find(panel, "progress.bar", Container.class).getComponent(1));
	}

	/**
	 * The progress bar's label as Swing draws it after {@link #layOutLiveCard}: whole, or cut short with {@code ...}
	 * when its room is too narrow (Swing's own clipping, as a {@code JLabel} paints it).
	 */
	String getBarTextShown()
	{
		JLabel label = (JLabel) find(panel, "progress.bar", Container.class).getComponent(1);
		Insets insets = label.getInsets();
		Rectangle view = new Rectangle(insets.left, insets.top, label.getWidth() - insets.left - insets.right,
			label.getHeight() - insets.top - insets.bottom);
		return SwingUtilities.layoutCompoundLabel(label, label.getFontMetrics(label.getFont()), label.getText(), null,
			label.getVerticalAlignment(), label.getHorizontalAlignment(), label.getVerticalTextPosition(),
			label.getHorizontalTextPosition(), view, new Rectangle(), new Rectangle(), 0);
	}

	/** How full the progress bar is, in percent. */
	int getBarPercentage()
	{
		return find(panel, "progress.bar", ProgressBar.class).getPercentage();
	}

	/**
	 * What the live card's task icon shows ({@link PanelProbe#iconText}): {@code "<item id> 36x32"} when its slot is
	 * that size too, or {@code "none"} while it is hidden.
	 */
	String getTaskIcon()
	{
		JLabel icon = find(panel, "taskBox.icon", JLabel.class);
		if (!icon.isVisible())
		{
			return "none";
		}
		String shown = iconText(icon.getIcon(), iconText);
		String slot = icon.getPreferredSize().width + "x" + icon.getPreferredSize().height;
		return shown.endsWith(" " + slot) ? shown : shown + " in a " + slot + " slot";
	}

	/** How many times the live card's icon was asked for (performance seam). */
	int getLiveCardBuildCount()
	{
		return panel.getLiveCardBuildCount();
	}

	/** How many times the live grids and XP list were drawn (performance seam). */
	int getLiveListsBuildCount()
	{
		return panel.getLiveListsBuildCount();
	}

	/**
	 * After laying the panel out at the sidebar's width: the panel's width, the live card's width, the widest part
	 * inside the card, and the width and shown text of the card's name line.
	 */
	Object[] layOutLiveCard()
	{
		panel.setSize(PluginPanel.PANEL_WIDTH, 4000);
		SwingLookup.layOut(panel);
		Container card = find(panel, "liveCard", Container.class);
		int widest = 0;
		for (Component part : card.getComponents())
		{
			widest = Math.max(widest, part.getWidth());
		}
		FitLabel name = find(panel, "taskBox.task", FitLabel.class);
		return new Object[]{panel.getWidth(), card.getWidth(), widest, name.getWidth(), name.getText()};
	}

	/** Whether a component named {@code name} is anywhere in the panel (shown or not). */
	boolean isInPanel(String name)
	{
		return SwingLookup.isInTree(panel, name);
	}

	/** The names of the panel's top-level parts, in order (the unnamed gaps and labels left out). */
	List<String> getContentOrder()
	{
		List<String> names = new ArrayList<>();
		for (Component part : ((Container) panel.getComponent(0)).getComponents())
		{
			if (part.getName() != null)
			{
				names.add(part.getName());
			}
		}
		return names;
	}

	/**
	 * The widest preferred width of the status strip and the live section's parts (the card, its grids and XP, the
	 * task loot, the note and the dart hint), to check that none of them widens the sidebar.
	 */
	int getLiveSectionPreferredWidth()
	{
		int widest = 0;
		for (String name : new String[]{"session", "liveCard", "dartHint", "liveSupplies", "liveLoot", "liveXp",
			"taskLoot", "note"})
		{
			widest = Math.max(widest, find(panel, name).getPreferredSize().width);
		}
		return widest;
	}

	/** The preferred width of every part of the panel's content, the widest, to check that nothing widens it. */
	int getContentPreferredWidth()
	{
		Container content = (Container) panel.getComponent(0);
		int widest = 0;
		for (Component part : content.getComponents())
		{
			if (part.isVisible())
			{
				widest = Math.max(widest, part.getPreferredSize().width);
			}
		}
		return widest;
	}

	/** The live "Supplies used" icons. */
	ItemGridProbe getLiveSupplies()
	{
		return new ItemGridProbe(find(panel, "liveSupplies", ItemGridView.class), iconText);
	}

	/** The live "Loot received" icons. */
	ItemGridProbe getLiveLoot()
	{
		return new ItemGridProbe(find(panel, "liveLoot", ItemGridView.class), iconText);
	}

	/** The live per-skill XP list. */
	ItemListProbe getLiveXp()
	{
		return new ItemListProbe(find(panel, "liveXp", ItemListView.class), iconText);
	}

	/** Whether the live item lists are shown. */
	boolean isLiveItemListsShown()
	{
		return SwingLookup.isVisible(panel, "liveSupplies") && SwingLookup.isVisible(panel, "liveLoot");
	}

	/** The live XP total in the stats ({@code "58.6K"}), or {@code null} while the stats are hidden. */
	String getXpText()
	{
		return isStatsShown() ? statValue("xp") : null;
	}

	/** Whether the time left is shown (the stats, while a session is active). */
	boolean isTimeLeftShown()
	{
		return isStatsShown();
	}

	/** The time left as {@code "Time left: ~12m"} (its label and value), or {@code null} while the stats are hidden. */
	String getTimeLeftText()
	{
		return isStatsShown() ? "Time left: " + statValue("timeLeft") : null;
	}

	/** Tooltip of the time left, or {@code null} while the stats are hidden. */
	String getTimeLeftTooltip()
	{
		return isStatsShown() ? statTooltip("timeLeft") : null;
	}

	/**
	 * The superiors, and once either happened the deaths and bank trips, as {@code "Superiors: 1 | Deaths: 0 |
	 * Bank trips: 2"} ({@code "Superiors: 1"} while the deaths and bank trips row is out of the grid); {@code null}
	 * while the stats are hidden.
	 */
	String getCountsText()
	{
		if (!isStatsShown())
		{
			return null;
		}
		String counts = "Superiors: " + statValue("superiors");
		Container stats = find(panel, "stats", Container.class);
		if (findOrNull(stats, "stats.deaths") != null)
		{
			counts += " | Deaths: " + statValue("deaths") + " | Bank trips: " + statValue("bankTrips");
		}
		return counts;
	}

	/**
	 * Lines of the boss kill counts, keys, clue scrolls and notable drops section, or {@code null} while it is hidden.
	 */
	List<String> getTaskLootLines()
	{
		Container section = find(panel, "taskLoot", Container.class);
		if (!section.isVisible())
		{
			return null;
		}
		List<String> lines = new ArrayList<>();
		for (Component label : section.getComponents())
		{
			lines.add(text(label));
		}
		return lines;
	}

	/** Tooltip of the boss kill counts, keys, clue scrolls and notable drops section. */
	String getTaskLootTooltip()
	{
		return tooltip(panel, "taskLoot");
	}

	/** Whether the note box is shown. */
	boolean isNoteShown()
	{
		return SwingLookup.isVisible(panel, "note");
	}

	/** Text in the note box's editor. */
	String getNoteText()
	{
		Object item = noteBox().getEditor().getItem();
		return item == null ? "" : item.toString();
	}

	/** The note box's list of recent notes. */
	List<String> getRecentNotes()
	{
		return items(noteBox(), Objects::toString);
	}

	/** Types {@code text} into the note box and presses Enter. */
	void typeNote(String text)
	{
		JComboBox<String> box = noteBox();
		box.getEditor().setItem(text);
		Component editor = box.getEditor().getEditorComponent();
		if (editor instanceof JTextField)
		{
			((JTextField) editor).postActionEvent();
		}
		else
		{
			box.actionPerformed(new ActionEvent(editor, ActionEvent.ACTION_PERFORMED, ""));
		}
	}

	@SuppressWarnings("unchecked")
	private JComboBox<String> noteBox()
	{
		return find(panel, "note.box", JComboBox.class);
	}

	/** Whether the live task's setup toggles are shown. */
	boolean isLiveSetupShown()
	{
		return SwingLookup.isVisible(panel, "setup");
	}

	/**
	 * What the live setup toggle for {@code tag} ({@code "cannon"}, {@code "style"} or {@code "gear"}) shows, as
	 * {@code "<name> | <image> | <value>"}: its name, its image as {@link PluginHarness#iconText} reads it
	 * and its slot's size ({@code "sprite <archive> 18x16"}, {@code "<item id> 18x16"}; {@code "- 18x16"} for a word in
	 * its place or an image not arrived yet) and its value as the image describes it, {@code "Barrage, set by you"}.
	 */
	String getLiveSetupToggle(String tag)
	{
		AbstractButton toggle = setupToggle(tag);
		String image = iconText(toggle.getIcon(), iconText);
		return toggle.getText() + " | " + image + " | " + ((ImageIcon) toggle.getIcon()).getDescription();
	}

	/**
	 * Whether the live setup toggle for {@code tag} draws the override mark: the top right pixel of its image slot,
	 * painted, is the heading colour.
	 */
	boolean isLiveSetupMarked(String tag)
	{
		AbstractButton toggle = setupToggle(tag);
		Icon icon = toggle.getIcon();
		BufferedImage canvas = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(),
			BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		try
		{
			icon.paintIcon(toggle, g, 0, 0);
		}
		finally
		{
			g.dispose();
		}
		int corner = canvas.getRGB(icon.getIconWidth() - 1, 0);
		return (corner >>> 24) != 0 && (corner & 0xFFFFFF) == (LedgerTheme.HEADING.getRGB() & 0xFFFFFF);
	}

	/** The live setup toggle's tooltip for {@code tag}. */
	String getLiveSetupTooltip(String tag)
	{
		return setupToggle(tag).getToolTipText();
	}

	/**
	 * The items the live setup toggle's menu for {@code tag} holds now, without opening it: each label, with
	 * {@code "* "} before the selected one; empty before the menu was first opened.
	 */
	List<String> getLiveSetupMenu(String tag)
	{
		List<String> items = new ArrayList<>();
		for (Component c : setupToggle(tag).getComponentPopupMenu().getComponents())
		{
			if (c instanceof JMenuItem)
			{
				JMenuItem item = (JMenuItem) c;
				items.add((item.isSelected() ? "* " : "") + item.getText());
			}
		}
		return items;
	}

	/**
	 * Clicks the live setup toggle for {@code tag}, as the player does, which opens its menu (headless, the menu is
	 * filled but has no screen to open on), and returns the menu's items ({@link #getLiveSetupMenu}).
	 */
	List<String> clickLiveSetup(String tag)
	{
		setupToggle(tag).doClick();
		return getLiveSetupMenu(tag);
	}

	/**
	 * Presses and releases Space on the live setup toggle for {@code tag} through its own key bindings, as a player
	 * with the keyboard focus on it does, and returns its menu's items ({@link #getLiveSetupMenu}).
	 */
	List<String> pressSpaceOnLiveSetup(String tag)
	{
		AbstractButton toggle = setupToggle(tag);
		for (String key : new String[]{"pressed SPACE", "released SPACE"})
		{
			Object name = toggle.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key));
			if (name == null)
			{
				throw new AssertionError("no " + key + " binding on the " + tag + " toggle");
			}
			toggle.getActionMap().get(name).actionPerformed(new ActionEvent(toggle, ActionEvent.ACTION_PERFORMED,
				null));
		}
		return getLiveSetupMenu(tag);
	}

	/** Whether the live setup toggle for {@code tag} can take the keyboard focus, so Tab reaches it. */
	boolean isLiveSetupFocusable(String tag)
	{
		AbstractButton toggle = setupToggle(tag);
		return toggle.isFocusable() && toggle.isRequestFocusEnabled() && toggle.isEnabled();
	}

	/**
	 * Clicks the live setup toggle for {@code tag}, then its menu's item labelled {@code label}, as the player would.
	 */
	void chooseLiveSetup(String tag, String label)
	{
		List<String> shown = clickLiveSetup(tag);
		for (Component c : setupToggle(tag).getComponentPopupMenu().getComponents())
		{
			if (c instanceof JMenuItem && label.equals(((JMenuItem) c).getText()))
			{
				((JMenuItem) c).doClick();
				return;
			}
		}
		throw new AssertionError("no setup choice " + label + " in " + shown);
	}

	/** The width the live setup toggles want, to check that they fit the card's 199 px. */
	int getLiveSetupPreferredWidth()
	{
		return find(panel, "setup").getPreferredSize().width;
	}

	/**
	 * After {@link #layOutLiveCard}: the setup row's width, then for each toggle (cannon, style, gear) its left edge,
	 * width and preferred width in the row.
	 */
	List<Integer> getLiveSetupLayout()
	{
		List<Integer> layout = new ArrayList<>();
		layout.add(find(panel, "setup").getWidth());
		for (String tag : new String[]{"cannon", "style", "gear"})
		{
			AbstractButton toggle = setupToggle(tag);
			layout.add(toggle.getX());
			layout.add(toggle.getWidth());
			layout.add(toggle.getPreferredSize().width);
		}
		return layout;
	}

	/** How many times the live setup toggles were redrawn (performance seam). */
	int getLiveSetupBuildCount()
	{
		return panel.getLiveSetupBuildCount();
	}

	private AbstractButton setupToggle(String tag)
	{
		return find(panel, "setup." + tag, AbstractButton.class);
	}

	/** Text of the saved-task count, e.g. {@code "Saved tasks: 3"}. */
	String getSavedTasksText()
	{
		return text(panel, "savedTasks");
	}

	// ---- Status strip: tracking state and controls (V2 plan 4.1, DESIGN_PRINCIPLES.md 3.4a) ----

	/** The status strip's state word, e.g. {@code "Waiting for first kill"}. */
	String getTrackingState()
	{
		return text(panel, "session.state");
	}

	/** The status strip's clock beside the state word ({@code "21:54"}), or {@code ""} when none is shown. */
	String getStatusClock()
	{
		return text(panel, "session.clock");
	}

	/**
	 * The status strip as the player reads it: the state word, then each button shown, left to right, with
	 * {@code " (off)"} when it can't be pressed: {@code "Paused | Resume | End task"}, {@code "Ended | Track again"}.
	 * The clock is read with {@link #getStatusClock}.
	 */
	String getStatusStrip()
	{
		StringBuilder sb = new StringBuilder(getTrackingState());
		for (String name : new String[]{"session.pauseResume", "session.endTask"})
		{
			AbstractButton button = find(panel, name, AbstractButton.class);
			if (button.isVisible())
			{
				sb.append(" | ").append(button.getText()).append(button.isEnabled() ? "" : " (off)");
			}
		}
		return sb.toString();
	}

	/** Whether the Pause / Resume button is shown. */
	boolean isPauseButtonShown()
	{
		return find(panel, "session.pauseResume").isVisible();
	}

	/** Whether the End task / Track again button is shown. */
	boolean isEndButtonShown()
	{
		return find(panel, "session.endTask").isVisible();
	}

	/** The Pause / Resume button's text. */
	String getPauseButtonText()
	{
		return find(panel, "session.pauseResume", AbstractButton.class).getText();
	}

	/** Whether the Pause / Resume button can be pressed. */
	boolean isPauseButtonEnabled()
	{
		return find(panel, "session.pauseResume", AbstractButton.class).isEnabled();
	}

	/** Presses Pause or Resume, as the player does: only while it is shown. */
	void clickPauseButton()
	{
		AbstractButton button = find(panel, "session.pauseResume", AbstractButton.class);
		if (!button.isVisible())
		{
			throw new AssertionError("Pause / Resume is not shown");
		}
		button.doClick();
	}

	/** Puts {@code text} on the Pause/Resume button as a refresh would, to stand for a label not yet refreshed. */
	void showPauseButtonAs(String text)
	{
		find(panel, "session.pauseResume", AbstractButton.class).setText(text);
	}

	/** The End task / Track again button's text. */
	String getEndButtonText()
	{
		return find(panel, "session.endTask", AbstractButton.class).getText();
	}

	/** Whether the End task / Track again button can be pressed. */
	boolean isEndButtonEnabled()
	{
		return find(panel, "session.endTask", AbstractButton.class).isEnabled();
	}

	/** Presses End task or Track again, as the player does: only while it is shown. */
	void clickEndButton()
	{
		AbstractButton button = find(panel, "session.endTask", AbstractButton.class);
		if (!button.isVisible())
		{
			throw new AssertionError("End task / Track again is not shown");
		}
		button.doClick();
	}

	/** Puts {@code text} on the End task / Track again button, to stand for a label not yet refreshed. */
	void showEndButtonAs(String text)
	{
		find(panel, "session.endTask", AbstractButton.class).setText(text);
	}

	/** The "Previous session saved as partial" notice's text, or {@code null} while it is hidden. */
	String getPartialNotice()
	{
		return SwingLookup.isVisible(panel, "partialNotice") ? text(panel, "partialNotice.text") : null;
	}

	/** Presses the partial notice's Discard, as the player does. */
	void clickPartialNoticeDiscard()
	{
		find(panel, "partialNotice.discard", AbstractButton.class).doClick();
	}

	// ---- Optional sections ----

	/** Whether an optional section takes space in the panel (its "Panel sections" setting is on). */
	boolean isSectionShown(SlayerLedgerPanel.OptionalSection section)
	{
		return SwingLookup.isVisible(panel, blockName(section));
	}

	/** The preferred height of an optional section's header. */
	int getSectionHeaderHeight(SlayerLedgerPanel.OptionalSection section)
	{
		switch (section)
		{
			case OVERALL_TOTALS:
				return find(panel, "totals.header").getPreferredSize().height;
			default:
				return find(panel, "assignments.header").getPreferredSize().height;
		}
	}

	private static String blockName(SlayerLedgerPanel.OptionalSection section)
	{
		switch (section)
		{
			case OVERALL_TOTALS:
				return "totalsBlock";
			default:
				return "assignmentsBlock";
		}
	}

	// ---- Sections ----

	TaskBoxProbe getTaskBox()
	{
		return new TaskBoxProbe(find(panel, "taskBox", Container.class));
	}

	AdvisorCardProbe getAdvisor()
	{
		return new AdvisorCardProbe(panel, iconText);
	}

	HistoryListProbe getHistory()
	{
		return new HistoryListProbe(panel, iconText);
	}

	/** The history's right-click hint under its list. */
	String getHistoryEditHint()
	{
		return text(panel, "history.editHint");
	}

	OverallTotalsProbe getOverallTotals()
	{
		return new OverallTotalsProbe(panel, iconText);
	}

	AssignmentsProbe getAssignments()
	{
		return new AssignmentsProbe(panel, iconText);
	}

	/**
	 * What {@code icon} shows: {@code "none"} without one; else {@code "<item id>"} as {@code iconText}
	 * reads an item sprite, {@code "master:<file>"} for a master's face or {@code "-"} for an empty slot, then its size
	 * as {@code "18x16"}, so a test can see empty and filled slots line up.
	 */
	static String iconText(Icon icon, Function<Icon, String> iconText)
	{
		if (icon == null)
		{
			return "none";
		}
		String shown = icon instanceof MasterIcons.MasterIcon ? "master:" + ((MasterIcons.MasterIcon) icon).getFile()
			: iconText.apply(icon);
		return (shown == null ? "-" : shown) + " " + icon.getIconWidth() + "x" + icon.getIconHeight();
	}

	/** {@link #iconText(Icon, Function)} of the label named {@code name} in each of {@code rows}, in order. */
	private static List<String> labelIcons(List<Component> rows, String name, Function<Icon, String> iconText)
	{
		List<String> icons = new ArrayList<>();
		for (Component row : rows)
		{
			Component label = findOrNull((Container) row, name);
			icons.add(iconText(label == null ? null : ((JLabel) label).getIcon(), iconText));
		}
		return icons;
	}

	/** The rows (panels) of {@code list}, in order. */
	private static List<Component> panels(Container list)
	{
		List<Component> rows = new ArrayList<>();
		for (Component component : list.getComponents())
		{
			if (component instanceof JPanel)
			{
				rows.add(component);
			}
		}
		return rows;
	}

	/** The tooltip of each row (panel) of {@code list}. */
	private static List<String> rowTooltips(Container list)
	{
		List<String> tooltips = new ArrayList<>();
		for (Component component : list.getComponents())
		{
			if (component instanceof JPanel)
			{
				tooltips.add(((JPanel) component).getToolTipText());
			}
		}
		return tooltips;
	}

	/**
	 * How the header of {@code list} (a {@link TitledListView}) looks: the title's {@link #look}, then the right-hand
	 * label's ({@code Show}/{@code Hide} or the value) when it shows text, then {@code "rule"} or {@code "no rule"} for
	 * a line under the header, joined by {@code " | "}: {@code "Bold heading | Small value | rule"}.
	 */
	private static String headerLook(Container list)
	{
		Container header = find(list, "itemList.header", Container.class);
		List<String> parts = new ArrayList<>();
		parts.add(look(find(header, "itemList.title")));
		for (Component c : header.getComponents())
		{
			if (!"itemList.title".equals(c.getName()) && c instanceof JLabel && !text(c).isEmpty())
			{
				parts.add(look(c));
			}
		}
		parts.add(hasRule(((JComponent) header).getBorder()) ? "rule" : "no rule");
		return String.join(" | ", parts);
	}

	/**
	 * How {@code label} looks: its font, {@code "Bold"}, {@code "Plain"} or {@code "Small"} ({@link LedgerTheme}'s
	 * three) or the font itself, then its colour as the {@link LedgerTheme} token's name ({@code "heading"}) or hex.
	 */
	private static String look(Component label)
	{
		Font font = label.getFont();
		String fontName = font.equals(LedgerTheme.boldFont()) ? "Bold" : font.equals(LedgerTheme.plainFont())
			? "Plain" : font.equals(LedgerTheme.smallFont()) ? "Small" : font.toString();
		Color color = label.getForeground();
		String colorName = color.equals(LedgerTheme.HEADING) ? "heading" : color.equals(LedgerTheme.LABEL) ? "label"
			: color.equals(LedgerTheme.VALUE) ? "value" : color.equals(LedgerTheme.HINT) ? "hint"
			: String.format("#%06X", color.getRGB() & 0xFFFFFF);
		return fontName + " " + colorName;
	}

	/** Whether {@code border} draws a line (a matte or line border, alone or inside a compound one). */
	private static boolean hasRule(Border border)
	{
		if (border instanceof CompoundBorder)
		{
			return hasRule(((CompoundBorder) border).getOutsideBorder())
				|| hasRule(((CompoundBorder) border).getInsideBorder());
		}
		if (border instanceof MatteBorder)
		{
			Insets insets = ((MatteBorder) border).getBorderInsets();
			return insets.top + insets.left + insets.bottom + insets.right > 0;
		}
		return border instanceof LineBorder;
	}

	/** One {@link ItemGridView}, found by its own name; its parts are named within it. */
	static final class ItemGridProbe
	{
		private final ItemGridView grid;
		private final Function<Icon, String> iconText;

		/** @param iconText what an icon shows ({@link PluginHarness#iconText}) */
		ItemGridProbe(ItemGridView grid, Function<Icon, String> iconText)
		{
			this.grid = grid;
			this.iconText = iconText;
		}

		/** The title as shown, with " | Show" or " | Hide" for a collapsible list. */
		String getHeaderText()
		{
			return grid.getHeaderText();
		}

		/** How the header looks ({@link PanelProbe#headerLook}): {@code "Small value | Small label | no rule"}. */
		String getHeaderLook()
		{
			return headerLook(grid);
		}

		/** The title's tooltip (the hidden items' note), or {@code null} without one. */
		String getHeaderTooltip()
		{
			return ((JComponent) find(grid, "itemList.header")).getToolTipText();
		}

		/**
		 * Each icon shown, in order, as {@code "<name> | <icon>"}: the name is the tooltip's first line, the icon what
		 * {@code iconText} says it shows ({@code "<item id>"}, or {@code "<item id> x<quantity>"} with the quantity
		 * drawn), then {@code " | (alch)"} when the icon has the alch hint's border. {@code "None"} for an empty list;
		 * empty while the list is closed.
		 */
		List<String> getCellTexts()
		{
			List<String> texts = new ArrayList<>();
			Container body = find(grid, "itemList.body", Container.class);
			if (!body.isVisible())
			{
				return texts;
			}
			if (findOrNull(body, "itemGrid.grid") == null)
			{
				texts.add(lineText(body.getComponent(0)));
				return texts;
			}
			for (JLabel cell : cells())
			{
				texts.add(tooltipName(cell.getToolTipText()) + " | " + iconText.apply(cell.getIcon())
					+ (isAlchMarked(cell) ? " | " + ItemDetails.ALCH_MARKER : ""));
			}
			return texts;
		}

		/** The tooltip of each icon shown, in order, as set (HTML). */
		List<String> getCellTooltips()
		{
			List<String> tooltips = new ArrayList<>();
			for (JLabel cell : cells())
			{
				tooltips.add(cell.getToolTipText());
			}
			return tooltips;
		}

		/** How many slots the grid has, the empty ones that fill its last row included; 0 without a grid. */
		int getSlotCount()
		{
			Component found = findOrNull(grid, "itemGrid.grid");
			return found == null ? 0 : ((Container) found).getComponentCount();
		}

		/** Opens or closes a collapsible list: a click on its title. */
		void toggle()
		{
			click(find(grid, "itemList.header"));
		}

		/** The icons shown, in order; none while the list is closed or empty. */
		private List<JLabel> cells()
		{
			List<JLabel> cells = new ArrayList<>();
			Component found = findOrNull(grid, "itemGrid.grid");
			if (found != null && find(grid, "itemList.body").isVisible())
			{
				for (Component c : children((Container) found, "itemGrid.cell"))
				{
					cells.add((JLabel) c);
				}
			}
			return cells;
		}

		/** Whether {@code cell} has the alch hint's orange border. */
		private static boolean isAlchMarked(JLabel cell)
		{
			Border border = cell.getBorder();
			return border instanceof LineBorder
				&& ItemGridView.ALCH_BORDER_COLOR.equals(((LineBorder) border).getLineColor());
		}

		/** The first line of an item tooltip ({@code "<html>Shark<br>..."}), unescaped. */
		private static String tooltipName(String tooltip)
		{
			String name = tooltip.substring("<html>".length(), tooltip.indexOf("<br>"));
			return name.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
		}
	}

	/** One {@link ItemListView}, found by its own name; its parts are named within it. */
	static final class ItemListProbe
	{
		private final ItemListView list;
		private final Function<Icon, String> iconText;

		/** @param iconText what a skill icon shows ({@link PluginHarness#iconText}) */
		ItemListProbe(ItemListView list, Function<Icon, String> iconText)
		{
			this.list = list;
			this.iconText = iconText;
		}

		/** The title as shown, with " | Show" or " | Hide" for a collapsible list. */
		String getHeaderText()
		{
			return list.getHeaderText();
		}

		/** How the header looks ({@link PanelProbe#headerLook}): {@code "Small value | no rule"}. */
		String getHeaderLook()
		{
			return headerLook(list);
		}

		/**
		 * Each line shown, in order, as {@code "<name> | <amount>"}, then the "+N more" line; {@code "None"} for an
		 * empty list; empty while the list is closed.
		 */
		List<String> getLineTexts()
		{
			List<String> lines = new ArrayList<>();
			Container body = find(list, "itemList.body", Container.class);
			if (!body.isVisible())
			{
				return lines;
			}
			for (Component c : body.getComponents())
			{
				if ("itemList.more".equals(c.getName()))
				{
					lines.add(text(c));
				}
				else if (c instanceof JPanel)
				{
					lines.add(itemLineText((JPanel) c));
				}
			}
			return lines;
		}

		/**
		 * What the icon slot before each line's name shows, in order, as {@code iconText} reads it
		 * ({@code "skill SLAYER"}), or {@code "-"} for an empty slot; then each slot's size as {@code "18x16"}, so a
		 * test can see empty and filled slots line up. Empty while the list is closed or empty.
		 */
		List<String> getLineIcons()
		{
			List<String> icons = new ArrayList<>();
			Container body = find(list, "itemList.body", Container.class);
			if (!body.isVisible())
			{
				return icons;
			}
			for (Component c : body.getComponents())
			{
				if (c instanceof JPanel)
				{
					for (Component part : ((JPanel) c).getComponents())
					{
						if ("itemList.icon".equals(part.getName()))
						{
							JLabel slot = (JLabel) part;
							String shown = slot.getIcon() == null ? "-" : iconText.apply(slot.getIcon());
							icons.add(shown + " " + slot.getPreferredSize().width + "x"
								+ slot.getPreferredSize().height);
						}
					}
				}
			}
			return icons;
		}

		/** The tooltip of each line shown, in order (not of "+N more"); empty while the list is closed. */
		List<String> getLineTooltips()
		{
			List<String> tooltips = new ArrayList<>();
			Container body = find(list, "itemList.body", Container.class);
			if (body.isVisible())
			{
				for (Component c : body.getComponents())
				{
					if (c instanceof JPanel)
					{
						tooltips.add(((JPanel) c).getToolTipText());
					}
				}
			}
			return tooltips;
		}

		/** Opens or closes a collapsible list: a click on its title. */
		void toggle()
		{
			click(find(list, "itemList.header"));
		}

		/** Clicks "+N more" (or "Show less"). */
		void clickShowAll()
		{
			click(find(list, "itemList.more"));
		}

		private static String itemLineText(JPanel line)
		{
			StringBuilder sb = new StringBuilder();
			for (Component c : line.getComponents())
			{
				if ("itemList.icon".equals(c.getName()))
				{
					// The skill icon's slot: read by getLineIcons.
					continue;
				}
				if (c instanceof JPanel)
				{
					for (Component inner : ((JPanel) c).getComponents())
					{
						sb.append(" | ").append(((JLabel) inner).getText());
					}
				}
				else
				{
					sb.append(((JLabel) c).getText());
				}
			}
			return sb.toString();
		}
	}

	/**
	 * The live card's header: the task's name, then (while known) the location line and the master line. The master,
	 * type, location and next bonus are read from the master line's tooltip, which has each of them in full.
	 */
	static final class TaskBoxProbe
	{
		private final Container box;

		TaskBoxProbe(Container box)
		{
			this.box = box;
		}

		/** The master line ("Konar · bonus in 3"), or {@code null} while it is not in the header. */
		String getDetailsText()
		{
			return optionalText("taskBox.details");
		}

		/** The location line ("Kraken Cove"), or {@code null} while it is not in the header. */
		String getLocationLine()
		{
			return optionalText("taskBox.location");
		}

		/**
		 * The tooltip of the master and location lines (they share it) as set (HTML, not unescaped), or {@code null}
		 * while neither is in the header.
		 */
		String getDetailsTooltip()
		{
			Component details = findOrNull(box, "taskBox.details");
			if (details == null)
			{
				details = findOrNull(box, "taskBox.location");
			}
			return details == null ? null : ((JComponent) details).getToolTipText();
		}

		/** The task line's tooltip, or {@code null} when it has none. */
		String getTaskTooltip()
		{
			return find(box, "taskBox.task", JLabel.class).getToolTipText();
		}

		/** The "Next bonus" line of the details tooltip, or {@code null} while it is not there. */
		String getStreakHintText()
		{
			return detail("Next bonus: ");
		}

		/** The header's lines in order, each in full ({@link FitLabel#getFullText()}). */
		List<String> getRows()
		{
			List<String> rows = new ArrayList<>();
			for (Component c : find(box, "taskBox.lines", Container.class).getComponents())
			{
				rows.add(((FitLabel) c).getFullText());
			}
			return rows;
		}

		/** The "Location" line of the details tooltip (the running or paused session's), or {@code null}. */
		String getLocationText()
		{
			return detail("Location: ");
		}

		/**
		 * The master and task type lines of the details tooltip, as {@code "Master: ... | Type: ..."}, or {@code null}
		 * while there is no task (the tooltip has neither).
		 */
		String getAssignmentText()
		{
			String master = detail("Master: ");
			String type = detail("Type: ");
			return master == null && type == null ? null : master + " | " + type;
		}

		/** The line of the master and location tooltip that starts with {@code prefix}, unescaped, or {@code null}. */
		private String detail(String prefix)
		{
			String tooltip = getDetailsTooltip();
			if (tooltip == null)
			{
				return null;
			}
			for (String line : tooltip.replace("<html>", "").replace("</html>", "").split("<br>"))
			{
				if (line.startsWith(prefix))
				{
					return line.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
				}
			}
			return null;
		}

		private String optionalText(String name)
		{
			Component label = findOrNull(box, name);
			return label == null || label.getParent() == null ? null : ((FitLabel) label).getFullText();
		}
	}

	/**
	 * The advisor card: its title and parchment note, or its no-history line. A part not in the card
	 * reads {@code null}.
	 */
	static final class AdvisorCardProbe
	{
		private final SlayerLedgerPanel panel;
		private final Function<Icon, String> iconText;

		AdvisorCardProbe(SlayerLedgerPanel panel, Function<Icon, String> iconText)
		{
			this.panel = panel;
			this.iconText = iconText;
		}

		private Container card()
		{
			return find(panel, "advisorCard", Container.class);
		}

		/** The part named {@code name} in the card now, or {@code null}. */
		private Component part(String name)
		{
			return findOrNull(card(), name);
		}

		/** Whether the card takes space in the panel. */
		boolean isShown()
		{
			return SwingLookup.isVisible(panel, "advisor");
		}

		/** The title, its lines in full joined by a space, or {@code null} while it is not in the card. */
		String getTitle()
		{
			return isShown() ? sentence(part("advisor.title")) : null;
		}

		/** The title's lines as wrapped (at most two), each in full. */
		List<String> getTitleLines()
		{
			return lines(part("advisor.title"));
		}

		/**
		 * The title's lines as the player sees them after {@link #layOut}, whole or the last cut short with
		 * {@link FitLabel#ELLIPSIS}.
		 */
		List<String> getTitleLinesShown()
		{
			return shownLines(part("advisor.title"));
		}

		/** The title's tooltip (HTML). */
		String getTitleTooltip()
		{
			return ((JComponent) part("advisor.title")).getToolTipText();
		}

		/**
		 * The lines of the option block {@code which} ({@code "recommendation"}), in order: its head as
		 * {@code "left | right"} with texts in full ({@code "Barrage, Catacombs | 3 tasks"}, the name's lines joined by
		 * a space), its time ({@code "~1 h"}), then {@code "Bring"}, each Bring icon as
		 * {@link ItemGridProbe#getCellTexts} reads it and {@code "+N more"}; {@code null} while the block is not in the
		 * card.
		 */
		List<String> getBlock(String which)
		{
			Component block = isShown() ? part("advisor." + which) : null;
			if (block == null)
			{
				return null;
			}
			List<String> lines = new ArrayList<>();
			for (Component c : ((Container) block).getComponents())
			{
				if (c instanceof ItemGridView)
				{
					ItemGridProbe grid = new ItemGridProbe((ItemGridView) c, iconText);
					lines.add(grid.getHeaderText());
					lines.addAll(grid.getCellTexts());
				}
				else if (("advisor." + which + ".head").equals(c.getName()))
				{
					lines.add(String.join(" ", getNameLines(which)) + " | "
						+ fullText(find((Container) c, "advisor." + which + ".tasks")));
				}
				else if (c instanceof JPanel)
				{
					lines.add(labelsText((Container) c));
				}
				else if (c instanceof JLabel)
				{
					lines.add(fullText(c));
				}
			}
			return lines;
		}

		/** The name of option block {@code which} as wrapped (at most two lines), each line in full. */
		List<String> getNameLines(String which)
		{
			List<String> texts = new ArrayList<>();
			for (JLabel line : nameLabels(which))
			{
				texts.add(fullText(line));
			}
			return texts;
		}

		/**
		 * The name of option block {@code which} as the player sees it after {@link #layOut}: its lines, whole or the
		 * last cut short with {@link FitLabel#ELLIPSIS}.
		 */
		List<String> getNameLinesShown(String which)
		{
			List<String> texts = new ArrayList<>();
			for (JLabel line : nameLabels(which))
			{
				texts.add(line.getText());
			}
			return texts;
		}

		/** The labels of option block {@code which}'s name, in order: the one beside the sample size first. */
		private List<JLabel> nameLabels(String which)
		{
			List<JLabel> labels = new ArrayList<>();
			collect(find(card(), "advisor." + which + ".head", Container.class), "advisor." + which + ".name", labels);
			return labels;
		}

		/** Adds the labels named {@code name} in {@code root}'s tree to {@code found}, in tree order. */
		private static void collect(Container root, String name, List<JLabel> found)
		{
			for (Component child : root.getComponents())
			{
				if (name.equals(child.getName()))
				{
					found.add((JLabel) child);
				}
				else if (child instanceof Container)
				{
					collect((Container) child, name, found);
				}
			}
		}

		/** The Bring grid of block {@code which}, for its tooltips and slots. */
		ItemGridProbe getBring(String which)
		{
			return new ItemGridProbe(find(card(), "advisor." + which + ".bring", ItemGridView.class), iconText);
		}

		/** The tooltip (HTML) of block {@code which}'s {@code +N more}, or {@code null} while it has none. */
		String getBringMoreTooltip(String which)
		{
			Component more = isShown() ? part("advisor." + which + ".bringMore") : null;
			return more == null ? null : ((JComponent) more).getToolTipText();
		}

		/** {@code "All setups (6 tasks) | ~1 h 8 min"}, or {@code null} while it is not in the card. */
		String getAllSetups()
		{
			Component line = isShown() ? part("advisor.allSetups") : null;
			return line == null ? null : labelsText((Container) line);
		}

		/** The no-history sentence, its lines joined by a space, or {@code null} while it is not in the card. */
		String getNoHistoryLine()
		{
			return isShown() ? sentence(part("advisor.noHistory")) : null;
		}

		/** The no-history sentence's lines as broken to fit the card, each in full. */
		List<String> getNoHistoryLines()
		{
			return lines(part("advisor.noHistory"));
		}

		/**
		 * The no-history sentence's lines as the player sees them after {@link #layOut}, whole or cut short with
		 * {@link FitLabel#ELLIPSIS}.
		 */
		List<String> getNoHistoryLinesShown()
		{
			return shownLines(part("advisor.noHistory"));
		}

		/** A Konar task's note when its area has no task yet, its lines joined by a space, or {@code null}. */
		String getAreaNote()
		{
			return isShown() ? sentence(part("advisor.areaNote")) : null;
		}

		/** A Konar task's note's lines as broken to fit the note. */
		List<String> getAreaNoteLines()
		{
			return lines(part("advisor.areaNote"));
		}

		/** The note's line when no option has a known setup, or {@code null}. */
		String getSetupUnknown()
		{
			return isShown() ? fullText(part("advisor.setupUnknown")) : null;
		}

		/** The preferred width of the card's row, to check that it never widens the sidebar. */
		int getPreferredWidth()
		{
			return find(panel, "advisor").getPreferredSize().width;
		}

		/**
		 * Lays the panel out at the sidebar's width (so the lines show what fits: {@link #getTitleLinesShown},
		 * {@link #getNameLinesShown}), and returns the panel's width, the card's and the widest part inside the card.
		 */
		int[] layOut()
		{
			panel.setSize(PluginPanel.PANEL_WIDTH, 4000);
			SwingLookup.layOut(panel);
			Container card = card();
			return new int[]{panel.getWidth(), card.getWidth(), widest(card)};
		}

		/** The widest component anywhere in {@code root}, laid out. */
		private static int widest(Container root)
		{
			int widest = root.getWidth();
			for (Component c : root.getComponents())
			{
				widest = Math.max(widest, c instanceof Container ? widest((Container) c) : c.getWidth());
			}
			return widest;
		}

		/** How many times the card's content was built (performance seam). */
		int getBuildCount()
		{
			return panel.getAdvisorCardBuildCount();
		}

		/** The texts of {@code line}'s labels, left to right, in full, the empty ones left out, joined by " | ". */
		private static String labelsText(Container line)
		{
			List<String> texts = new ArrayList<>();
			for (Component c : line.getComponents())
			{
				String text = c instanceof JLabel ? fullText(c) : null;
				if (text != null && !text.isEmpty())
				{
					texts.add(text);
				}
			}
			return String.join(" | ", texts);
		}

		/** A label's text in full ({@link FitLabel#getFullText()}), or {@code null} for none. */
		private static String fullText(Component label)
		{
			if (label == null)
			{
				return null;
			}
			return label instanceof FitLabel ? ((FitLabel) label).getFullText() : ((JLabel) label).getText();
		}

		/** A sentence broken into lines (one label each), joined by a space again, or {@code null} for none. */
		private static String sentence(Component lines)
		{
			return lines == null ? null : String.join(" ", lines(lines));
		}

		/** The lines of a sentence broken into lines, each in full. */
		private static List<String> lines(Component lines)
		{
			List<String> texts = new ArrayList<>();
			for (Component line : ((Container) lines).getComponents())
			{
				texts.add(fullText(line));
			}
			return texts;
		}

		/** The lines of a sentence broken into lines as shown, whole or cut short ({@link JLabel#getText}). */
		private static List<String> shownLines(Component lines)
		{
			List<String> texts = new ArrayList<>();
			for (Component line : ((Container) lines).getComponents())
			{
				texts.add(((JLabel) line).getText());
			}
			return texts;
		}
	}

	/**
	 * The task history (look A): its header, the filters with the setup filters behind More filters,
	 * the error line, and the two-line rows with their item details.
	 */
	static final class HistoryListProbe
	{
		private final SlayerLedgerPanel panel;
		private final Container list;
		private final Function<Icon, String> iconText;

		HistoryListProbe(SlayerLedgerPanel panel, Function<Icon, String> iconText)
		{
			this.panel = panel;
			this.list = find(panel, "history.list", Container.class);
			this.iconText = iconText;
		}

		/** The history rows shown, in order (without their details). */
		private List<Component> rows()
		{
			return children(list, "history.row");
		}

		/** The item details below the {@code index}th row, or {@code null} while they are closed. */
		private Container details(int index)
		{
			Component row = rows().get(index);
			Component[] all = list.getComponents();
			for (int i = 0; i < all.length - 1; i++)
			{
				if (all[i] == row)
				{
					return "history.details".equals(all[i + 1].getName()) ? (Container) all[i + 1] : null;
				}
			}
			return null;
		}

		/** The section header's title, with the count of filters set: {@code Task history \u00b7 2 filters}. */
		String getHeaderTitle()
		{
			return text(panel, "history.title");
		}

		/** Whether Clear filters shows beside More filters (only while a filter is set). */
		boolean isClearFiltersShown()
		{
			return SwingLookup.isVisible(panel, "history.clearFilters");
		}

		/** Clicks Clear filters beside More filters, as the player does. */
		void clickClearFilters()
		{
			find(panel, "history.clearFilters", AbstractButton.class).doClick();
		}

		/** Whether the empty list offers Clear filters (only when the filters match no task). */
		boolean isEmptyClearShown()
		{
			Component clear = findOrNull(list, "history.empty.clear");
			return clear != null && clear.isVisible();
		}

		/** Clicks Clear filters under "No tasks match these filters.", as the player does. */
		void clickEmptyClear()
		{
			find(list, "history.empty.clear", AbstractButton.class).doClick();
		}

		/** The tooltip of each history row shown, in order. */
		List<String> getTooltips()
		{
			List<String> tooltips = new ArrayList<>();
			for (Component row : rows())
			{
				tooltips.add(((JComponent) row).getToolTipText());
			}
			return tooltips;
		}

		/** The right-click menu items of the {@code index}th row, as {@code "Edit note"} or {@code "-Edit note"} when disabled. */
		List<String> getMenuItems(int index)
		{
			List<String> items = new ArrayList<>();
			for (Component c : ((JComponent) rows().get(index)).getComponentPopupMenu().getComponents())
			{
				if (c instanceof JMenuItem)
				{
					items.add((c.isEnabled() ? "" : "-") + ((JMenuItem) c).getText());
				}
			}
			return items;
		}

		/** What the icon before each shown row's monster shows, in order ({@link PanelProbe#iconText}). */
		List<String> getNameIcons()
		{
			return labelIcons(rows(), "history.name", iconText);
		}

		/** How the name of the {@code index}th row shown looks ({@link PanelProbe#look}): {@code "Plain value"}. */
		String getNameLook(int index)
		{
			return look(find((Container) rows().get(index), "history.name"));
		}

		/** The width the shown rows want, to check that they fit the sidebar ({@code PluginPanel.PANEL_WIDTH}). */
		int getListPreferredWidth()
		{
			return list.getPreferredSize().width;
		}

		/**
		 * Each row's name in full, in order, with {@code " | Partial"} for a partial task: the only status a row names
		 *.
		 */
		List<String> getTitleTexts()
		{
			List<String> titles = new ArrayList<>();
			for (Component c : rows())
			{
				Component status = findOrNull((Container) c, "history.status");
				titles.add(fullText(c, "history.name") + (status == null ? "" : " | " + text(status)));
			}
			return titles;
		}

		/**
		 * Each row as the player reads it, in full (before any cut): {@code "name | duration / date \u00b7 location |
		 * profit"}, with {@code "Partial | "} before the date of a partial task.
		 */
		List<String> getRowTexts()
		{
			List<String> texts = new ArrayList<>();
			for (Component c : rows())
			{
				texts.add(rowText(c, true));
			}
			return texts;
		}

		/**
		 * Each row's texts as shown after {@link #layOut}: {@link #getRowTexts}'s form, with each long part cut short
		 * with {@code \u2026} as it fits the sidebar.
		 */
		List<String> getRowTextsShown()
		{
			List<String> texts = new ArrayList<>();
			for (Component c : rows())
			{
				texts.add(rowText(c, false));
			}
			return texts;
		}

		private static String rowText(Component row, boolean full)
		{
			Container parts = (Container) row;
			Component status = findOrNull(parts, "history.status");
			return (full ? fullText(row, "history.name") : text(parts, "history.name")) + " | "
				+ text(parts, "history.duration") + " / " + (status == null ? "" : text(status) + " | ")
				+ (full ? fullText(row, "history.when") : text(parts, "history.when")) + " | "
				+ text(parts, "history.profit");
		}

		private static String fullText(Component row, String name)
		{
			return find((Container) row, name, FitLabel.class).getFullText();
		}

		/** The profit's colour on each row shown, in order. */
		List<Color> getProfitColors()
		{
			List<Color> colors = new ArrayList<>();
			for (Component c : rows())
			{
				colors.add(find((Container) c, "history.profit").getForeground());
			}
			return colors;
		}

		/**
		 * After {@link #layOut}: each shown row's setup tag icons, in order, each as {@code "<what the icon shows>
		 * <size>: <tooltip>"}, the tooltip being what Swing shows with the mouse over the icon's centre, for example
		 * {@code "sprite 1234 16x16: Barrage (estimated)"} ({@link PanelProbe#iconText}; a sprite not arrived yet is
		 * {@code "- 16x16"}).
		 */
		List<List<String>> getTagIcons()
		{
			List<List<String>> rows = new ArrayList<>();
			for (Component c : rows())
			{
				List<String> tags = new ArrayList<>();
				for (Component tag : tags(c))
				{
					tags.add(PanelProbe.iconText(((JLabel) tag).getIcon(), iconText) + ": " + tooltipAt(tag));
				}
				rows.add(tags);
			}
			return rows;
		}

		/** The setup tag icons of {@code row}, in order. */
		private static List<Component> tags(Component row)
		{
			return children(find((Container) row, "history.tags", Container.class), "history.tag");
		}

		/** The row {@code part} is in (or {@code part}, a row). */
		private static JComponent rowOf(Component part)
		{
			Component row = part;
			while (!"history.row".equals(row.getName()))
			{
				row = row.getParent();
			}
			return (JComponent) row;
		}

		/** The centre of {@code part} in the coordinates of its row. */
		private static Point centreInRow(Component part)
		{
			return SwingUtilities.convertPoint(part, part.getWidth() / 2, part.getHeight() / 2, rowOf(part));
		}

		/**
		 * The tooltip Swing shows with the mouse over {@code part}'s centre: the tooltip of the component that gets the
		 * mouse there ({@link #mouseTarget}), asked with the mouse's point, as {@code ToolTipManager} asks it.
		 */
		private static String tooltipAt(Component part)
		{
			JComponent row = rowOf(part);
			Point point = centreInRow(part);
			JComponent target = (JComponent) mouseTarget(row, point);
			Point local = SwingUtilities.convertPoint(row, point, target);
			return target.getToolTipText(mouse(target, MouseEvent.MOUSE_MOVED, local.x, local.y, MouseEvent.NOBUTTON));
		}

		/**
		 * The component that gets the mouse at {@code point} of {@code root}, as AWT's lightweight dispatcher picks it:
		 * the deepest component shown there that listens to the mouse (a mouse or motion listener, which a tooltip adds
		 * too, or a popup menu of its own), else {@code root}.
		 */
		private static Component mouseTarget(Component root, Point point)
		{
			Component at = SwingUtilities.getDeepestComponentAt(root, point.x, point.y);
			while (at != null && at != root && !listensToMouse(at))
			{
				at = at.getParent();
			}
			return at == null ? root : at;
		}

		private static boolean listensToMouse(Component c)
		{
			return c.getMouseListeners().length > 0 || c.getMouseMotionListeners().length > 0
				|| c instanceof JComponent && !((JComponent) c).getInheritsPopupMenu()
				&& ((JComponent) c).getComponentPopupMenu() != null;
		}

		/**
		 * After {@link #layOut}: the tooltip Swing shows with the mouse over the centre of the {@code index}th row's
		 * part named {@code part}, for example {@code "history.name"}.
		 */
		String getTooltipOver(int index, String part)
		{
			return tooltipAt(find((Container) rows().get(index), part));
		}

		/**
		 * After {@link #layOut}: whether the mouse over the centre of each tag icon of the {@code index}th row reaches the
		 * row itself ({@link #mouseTarget}), so moving onto an icon is no exit from the row and its click, hover and menu
		 * work there.
		 */
		boolean rowGetsTheMouseOverItsTags(int index)
		{
			Component row = rows().get(index);
			for (Component tag : tags(row))
			{
				if (mouseTarget(row, centreInRow(tag)) != row)
				{
					return false;
				}
			}
			return !tags(row).isEmpty();
		}

		/**
		 * Lays the panel out at the sidebar's width ({@code PluginPanel.PANEL_WIDTH}), as the player sees it, and
		 * returns the panel's width, the list's and the widest part of any row.
		 */
		int[] layOut()
		{
			// Headless, no peer validates the panel: forget the box layouts' cached sizes, as validate() would.
			invalidateTree(panel);
			panel.setSize(PluginPanel.PANEL_WIDTH, 4000);
			SwingLookup.layOut(panel);
			int widest = 0;
			for (Component row : rows())
			{
				for (Component part : ((Container) row).getComponents())
				{
					widest = Math.max(widest, part.getX() + part.getWidth());
				}
				widest = Math.max(widest, row.getWidth());
			}
			return new int[]{panel.getWidth(), list.getWidth(), widest};
		}

		private static void invalidateTree(Container root)
		{
			root.invalidate();
			for (Component child : root.getComponents())
			{
				if (child instanceof Container)
				{
					invalidateTree((Container) child);
				}
			}
		}

		/**
		 * After {@link #layOut}: whether the header's title fits its header, and More filters and Clear filters fit their
		 * line side by side.
		 */
		boolean headerAndFilterLinksFit()
		{
			Component title = find(panel, "history.title");
			Component header = find(panel, "history.header");
			Component more = find(panel, "history.moreFilters");
			Component clear = find(panel, "history.clearFilters");
			Component links = find(panel, "history.filterLinks");
			return title.getPreferredSize().width <= header.getWidth() && title.getWidth() >= title.getPreferredSize().width
				&& more.getX() + more.getWidth() <= clear.getX() && clear.getX() + clear.getWidth() <= links.getWidth()
				&& links.getWidth() <= PluginPanel.PANEL_WIDTH;
		}

		/**
		 * After {@link #layOut}: whether every part of each row lies inside the row and no two parts of a line overlap
		 * (the tags after the date and location, the profit right of both).
		 */
		boolean partsFitTheirRows()
		{
			for (Component row : rows())
			{
				Container parts = (Container) row;
				int right = row.getWidth() - parts.getInsets().right;
				for (Component part : parts.getComponents())
				{
					if (part.getX() < 0 || part.getX() + part.getWidth() > right)
					{
						return false;
					}
				}
				Component when = find(parts, "history.when");
				Component tags = find(parts, "history.tags");
				Component profit = find(parts, "history.profit");
				Component name = find(parts, "history.name");
				Component duration = find(parts, "history.duration");
				if (when.getX() + when.getWidth() > tags.getX() || tags.getX() + tags.getWidth() > profit.getX()
					|| name.getX() + name.getWidth() > duration.getX())
				{
					return false;
				}
			}
			return true;
		}

		/** The loot, supplies and profit text and the tooltip of each row shown, in order: {@code "profit | tooltip"}. */
		List<String> getValuesText()
		{
			List<String> values = new ArrayList<>();
			for (Component c : rows())
			{
				values.add(text((Container) c, "history.profit") + " | " + ((JComponent) c).getToolTipText());
			}
			return values;
		}

		/** Sends a mouse click with {@code button} and {@code clickCount} to the {@code index}th row, as Swing would. */
		void mouseClickRow(int index, int button, int clickCount)
		{
			click(rows().get(index), button, clickCount);
		}

		/** Opens or closes the item details of the {@code index}th row shown: a left click on it. */
		void clickRow(int index)
		{
			click(rows().get(index));
		}

		/**
		 * After {@link #layOut}: a left click on the centre of the {@code index}th row's first tag icon, sent to the
		 * component that gets the mouse there ({@link #mouseTarget}), as Swing sends it.
		 */
		void clickRowTag(int index)
		{
			Component tag = tags(rows().get(index)).get(0);
			JComponent row = rowOf(tag);
			Point point = centreInRow(tag);
			Component target = mouseTarget(row, point);
			Point local = SwingUtilities.convertPoint(row, point, target);
			target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
				local.x, local.y, 1, false, MouseEvent.BUTTON1));
		}

		/**
		 * After {@link #layOut}: the right-click menu items over the {@code index}th row's first tag icon, as
		 * {@link #getMenuItems} gives them: the menu of the component that gets the mouse there ({@link #mouseTarget}),
		 * which Swing's look and feel shows on a popup trigger.
		 */
		List<String> getMenuItemsOverTag(int index)
		{
			Component tag = tags(rows().get(index)).get(0);
			JComponent target = (JComponent) mouseTarget(rowOf(tag), centreInRow(tag));
			List<String> items = new ArrayList<>();
			for (Component c : target.getComponentPopupMenu().getComponents())
			{
				if (c instanceof JMenuItem)
				{
					items.add((c.isEnabled() ? "" : "-") + ((JMenuItem) c).getText());
				}
			}
			return items;
		}

		/** Whether the {@code index}th row shows the hover background ({@link LedgerTheme#HOVER}). */
		boolean isRowHovered(int index)
		{
			return LedgerTheme.HOVER.equals(rows().get(index).getBackground());
		}

		/** The mouse enters the {@code index}th row, as Swing reports it. */
		void mouseEnterRow(int index)
		{
			Component row = rows().get(index);
			row.dispatchEvent(mouse(row, MouseEvent.MOUSE_ENTERED, 1, 1, MouseEvent.NOBUTTON));
		}

		/**
		 * The {@code index}th row hears the mouse leave, as Swing reports it: at a point outside it, or ({@code inside})
		 * at its centre, as when the right-click menu or a dialog takes the mouse while it is still over the row.
		 */
		void mouseExitRow(int index, boolean inside)
		{
			Component row = rows().get(index);
			int x = inside ? row.getWidth() / 2 : -5;
			int y = inside ? row.getHeight() / 2 : -5;
			row.dispatchEvent(mouse(row, MouseEvent.MOUSE_EXITED, x, y, MouseEvent.NOBUTTON));
		}

		private static MouseEvent mouse(Component source, int id, int x, int y, int button)
		{
			return new MouseEvent(source, id, System.currentTimeMillis(), 0, x, y, 0, false, button);
		}

		/**
		 * The item details below the {@code index}th row shown, one string per line (the price line, then each list's
		 * title and its icons or lines, as {@link ItemGridProbe#getCellTexts} and {@link ItemListProbe#getLineTexts}
		 * read them), or {@code null} while they are closed.
		 */
		List<String> getDetailsText(int index)
		{
			Container details = details(index);
			if (details == null)
			{
				return null;
			}
			List<String> lines = new ArrayList<>();
			for (Component c : details.getComponents())
			{
				if (c instanceof ItemGridView)
				{
					ItemGridProbe grid = new ItemGridProbe((ItemGridView) c, iconText);
					lines.add(grid.getHeaderText());
					lines.addAll(grid.getCellTexts());
				}
				else if (c instanceof ItemListView)
				{
					ItemListProbe itemList = new ItemListProbe((ItemListView) c, iconText);
					lines.add(itemList.getHeaderText());
					lines.addAll(itemList.getLineTexts());
				}
				else if (c instanceof JLabel)
				{
					lines.add(((JLabel) c).getText());
				}
			}
			return lines;
		}

		/**
		 * The tooltip of each icon of the {@code title} grid below the {@code index}th row, in order; empty when that
		 * grid is not there.
		 */
		List<String> getDetailsTooltips(int index, String title)
		{
			ItemGridProbe grid = getDetailsGrid(index, title);
			return grid == null ? new ArrayList<>() : grid.getCellTooltips();
		}

		/** The first item grid below the {@code index}th row whose title starts with {@code title}, or {@code null}. */
		ItemGridProbe getDetailsGrid(int index, String title)
		{
			for (Component c : details(index).getComponents())
			{
				if (c instanceof ItemGridView && ((ItemGridView) c).getHeaderText().startsWith(title))
				{
					return new ItemGridProbe((ItemGridView) c, iconText);
				}
			}
			return null;
		}

		/**
		 * How the header of the first list (item grid or XP list) below the {@code index}th row whose title starts with
		 * {@code title} looks ({@link PanelProbe#headerLook}): {@code "Small value | Small label | no rule"};
		 * {@code null} when there is no such list.
		 */
		String getDetailsHeaderLook(int index, String title)
		{
			for (Component c : details(index).getComponents())
			{
				if (c instanceof TitledListView && ((TitledListView<?>) c).getHeaderText().startsWith(title))
				{
					return headerLook((Container) c);
				}
			}
			return null;
		}

		/** Clicks "+N more" (or "Show less") on the {@code title} text list (the XP) below the {@code index}th row. */
		void clickShowAll(int index, String title)
		{
			for (Component c : details(index).getComponents())
			{
				if (c instanceof ItemListView && ((ItemListView) c).getHeaderText().startsWith(title))
				{
					new ItemListProbe((ItemListView) c, iconText).clickShowAll();
				}
			}
		}

		/**
		 * The skill icon of each line of the XP list below the {@code index}th row, as
		 * {@link ItemListProbe#getLineIcons} reads them; empty when there is no XP list.
		 */
		List<String> getDetailsXpIcons(int index)
		{
			for (Component c : details(index).getComponents())
			{
				if (c instanceof ItemListView)
				{
					return new ItemListProbe((ItemListView) c, iconText).getLineIcons();
				}
			}
			return new ArrayList<>();
		}

		/** The error shown above the list, or {@code null} when none is shown. */
		String getErrorText()
		{
			Component error = find(panel, "history.error");
			return error.isVisible() ? text(error) : null;
		}

		/** The Monster filter's choices, in order. */
		@SuppressWarnings("unchecked")
		List<String> getFilterOptions()
		{
			return items((JComboBox<String>) find(panel, "history.filter", JComboBox.class), s -> s);
		}

		/** Selects {@code option} in the Monster filter, as the player does. */
		void selectFilter(String option)
		{
			find(panel, "history.filter", JComboBox.class).setSelectedItem(option);
		}

		/**
		 * The Location filter's choices, in order, as the player sees them (its renderer's text, a
		 * choice is a spot key shown by its label).
		 */
		@SuppressWarnings("unchecked")
		List<String> getLocationFilterOptions()
		{
			JComboBox<String> box = (JComboBox<String>) find(panel, "history.locationFilter", JComboBox.class);
			return items(box, item -> rendered(box, item));
		}

		/** The Location filter's choices themselves (spot keys and the like), in order. */
		@SuppressWarnings("unchecked")
		List<String> getLocationFilterKeys()
		{
			return items((JComboBox<String>) find(panel, "history.locationFilter", JComboBox.class), s -> s);
		}

		/**
		 * Selects the first choice of the Location filter shown as {@code option}, as the player does;
		 * {@link HistoryFilter#NO_LOCATION} selects the "No location" entry of the tasks without a location, and
		 * {@link LedgerHistory#ALL} every task.
		 */
		@SuppressWarnings("unchecked")
		void selectLocationFilter(String option)
		{
			JComboBox<String> box = (JComboBox<String>) find(panel, "history.locationFilter", JComboBox.class);
			if (HistoryFilter.NO_LOCATION.equals(option) || LedgerHistory.ALL.equals(option))
			{
				box.setSelectedItem(option);
				return;
			}
			for (int i = 0; i < box.getItemCount(); i++)
			{
				String item = box.getItemAt(i);
				if (!HistoryFilter.NO_LOCATION.equals(item) && option.equals(rendered(box, item)))
				{
					box.setSelectedIndex(i);
					return;
				}
			}
			throw new AssertionError("No Location choice shown as " + option + ": " + getLocationFilterOptions());
		}

		/** The Master filter's choices, in order. */
		@SuppressWarnings("unchecked")
		List<String> getMasterFilterOptions()
		{
			return items((JComboBox<String>) find(panel, "history.masterFilter", JComboBox.class), s -> s);
		}

		/** Selects {@code option} in the Master filter, as the player does. */
		void selectMasterFilter(String option)
		{
			find(panel, "history.masterFilter", JComboBox.class).setSelectedItem(option);
		}

		/**
		 * The choices of the setup filter named {@code name} ({@code "history.taskTypeFilter"},
		 * {@code "history.cannonFilter"}, {@code "history.styleFilter"} or {@code "history.gearFilter"}), in order.
		 */
		@SuppressWarnings("unchecked")
		List<String> getSetupFilterOptions(String name)
		{
			return items((JComboBox<String>) find(panel, name, JComboBox.class), s -> s);
		}

		/** Selects {@code option} ("Any", "Yes", "Barrage" and so on) in the setup filter named {@code name}. */
		void selectSetupFilter(String name, String option)
		{
			find(panel, name, JComboBox.class).setSelectedItem(option);
		}

		/** Whether the filter named {@code name} is shown (the setup filters only while More filters is open). */
		boolean isFilterShown(String name)
		{
			return SwingLookup.isVisible(panel, name);
		}

		/** The More filters toggle's text: {@code More filters}, {@code More filters (1 set)} or {@code Hide more filters}. */
		String getMoreFiltersText()
		{
			return text(panel, "history.moreFilters");
		}

		/** Clicks the More filters toggle, as the player does. */
		void clickMoreFilters()
		{
			find(panel, "history.moreFilters", AbstractButton.class).doClick();
		}

		/**
		 * The value each filter shows, as {@code "Monster | Location | Master"}, {@code "All"} for a filter showing
		 * every task.
		 */
		@SuppressWarnings("unchecked")
		String getFilterSelections()
		{
			JComboBox<String> location = (JComboBox<String>) find(panel, "history.locationFilter", JComboBox.class);
			return find(panel, "history.filter", JComboBox.class).getSelectedItem() + " | "
				+ rendered(location, (String) location.getSelectedItem())
				+ " | " + find(panel, "history.masterFilter", JComboBox.class).getSelectedItem();
		}

		/** The value each setup filter shows, as {@code "Task type | Cannon | Style | Gear"}, {@code "Any"} for none. */
		String getSetupFilterSelections()
		{
			return find(panel, "history.taskTypeFilter", JComboBox.class).getSelectedItem() + " | "
				+ find(panel, "history.cannonFilter", JComboBox.class).getSelectedItem() + " | "
				+ find(panel, "history.styleFilter", JComboBox.class).getSelectedItem() + " | "
				+ find(panel, "history.gearFilter", JComboBox.class).getSelectedItem();
		}

		/** The text {@code box}'s renderer shows for {@code item}, as the player sees it. */
		private static String rendered(JComboBox<String> box, String item)
		{
			Component c = box.getRenderer().getListCellRendererComponent(new JList<>(), item, -1, false, false);
			return c instanceof JLabel ? ((JLabel) c).getText() : String.valueOf(item);
		}

		/** The tooltip of the filter named {@code name} ({@code "history.locationFilter"} and so on). */
		String getFilterTooltip(String name)
		{
			return find(panel, name, JComboBox.class).getToolTipText();
		}

		/**
		 * Runs the filter named {@code name}'s open-list widener as when the list opens ({@link ComboPopupWidener}).
		 *
		 * @return whether the filter has one
		 */
		boolean widenFilterList(String name)
		{
			JComboBox<?> box = find(panel, name, JComboBox.class);
			for (PopupMenuListener listener : box.getPopupMenuListeners())
			{
				if (listener instanceof ComboPopupWidener)
				{
					((ComboPopupWidener) listener).widen(box);
					return true;
				}
			}
			return false;
		}

		/** The preferred width of the open list of the filter named {@code name}. */
		int getFilterListPreferredWidth(String name)
		{
			return ComboPopupWidener.listScroller(find(panel, name, JComboBox.class)).getPreferredSize().width;
		}

		/** The preferred width of the filter named {@code name}. */
		int getFilterPreferredWidth(String name)
		{
			return find(panel, name, JComboBox.class).getPreferredSize().width;
		}

		/** The preferred width of the filter rows together. */
		int getFiltersPreferredWidth()
		{
			return find(panel, "history.filters").getPreferredSize().width;
		}

		/** The text shown instead of the rows ("No saved tasks yet. ..." and so on), or {@code null} while rows are shown. */
		String getEmptyText()
		{
			Component empty = findOrNull(list, "history.empty");
			return empty == null ? null : text(empty);
		}

		/** How many history rows are shown (built). */
		int getRowCount()
		{
			return rows().size();
		}

		/** The "Show N more" button's text, or {@code null} while it is not shown. */
		String getShowMoreText()
		{
			Component button = findOrNull(list, "history.showMore");
			return button == null ? null : ((AbstractButton) button).getText();
		}

		/** The "Show N more" button's tooltip, or {@code null} while it is not shown. */
		String getShowMoreTooltip()
		{
			Component button = findOrNull(list, "history.showMore");
			return button == null ? null : ((AbstractButton) button).getToolTipText();
		}

		/** Clicks "Show N more", as the player does. */
		void clickShowMore()
		{
			find(list, "history.showMore", AbstractButton.class).doClick();
		}

		/** How many times the rows were built (performance seam). */
		int getBuildCount()
		{
			return panel.getHistoryListBuildCount();
		}
	}

	/** A collapsible section with a header (title, Show/Hide) and a body, named {@code <prefix>.header} and so on. */
	private abstract static class CollapsibleProbe
	{
		final SlayerLedgerPanel panel;
		private final String prefix;

		CollapsibleProbe(SlayerLedgerPanel panel, String prefix)
		{
			this.panel = panel;
			this.prefix = prefix;
		}

		Component part(String name)
		{
			return find(panel, prefix + "." + name);
		}

		/** Clicks the section title, as the player does. */
		void clickTitle()
		{
			click(part("title"));
		}

		/** Opens or closes the section: a click on its header. */
		void toggle()
		{
			click(part("header"));
		}

		/** Whether the section is open. */
		boolean isExpanded()
		{
			return part("body").isVisible();
		}

		/** The header row. */
		Component getHeader()
		{
			return part("header");
		}

		Container list()
		{
			return (Container) part("list");
		}
	}

	/** The Overall totals section. */
	static final class OverallTotalsProbe extends CollapsibleProbe
	{
		private final Function<Icon, String> iconText;

		OverallTotalsProbe(SlayerLedgerPanel panel, Function<Icon, String> iconText)
		{
			super(panel, "totals");
			this.iconText = iconText;
		}

		/** Selects a grouping, as the combo box does. */
		void setGrouping(OverallTotals.Grouping value)
		{
			((JComboBox<?>) part("grouping")).setSelectedItem(value);
		}

		/**
		 * The visible entries, each as its lines ({@code left | right}) joined by a newline, or the empty message. The
		 * item grids below an entry's lines are read with {@link #getSupplies} and {@link #getLoot}.
		 */
		List<String> getRows()
		{
			List<String> rows = new ArrayList<>();
			for (Component component : list().getComponents())
			{
				if (component instanceof JLabel)
				{
					rows.add(((JLabel) component).getText());
				}
				else if (component instanceof JPanel)
				{
					List<String> lines = new ArrayList<>();
					for (Component line : ((JPanel) component).getComponents())
					{
						if (!"totals.items".equals(line.getName()))
						{
							lines.add(lineText(line));
						}
					}
					rows.add(String.join("\n", lines));
				}
			}
			return rows;
		}

		/** The "Supplies used" grid of the visible entry {@code index}. */
		ItemGridProbe getSupplies(int index)
		{
			return new ItemGridProbe(find(entry(index), "totals.supplies", ItemGridView.class), iconText);
		}

		/** The "Loot received" grid of the visible entry {@code index}. */
		ItemGridProbe getLoot(int index)
		{
			return new ItemGridProbe(find(entry(index), "totals.loot", ItemGridView.class), iconText);
		}

		/** The "Monsters assigned" list of the visible By master entry {@code index}. */
		ItemListProbe getAssignments(int index)
		{
			return new ItemListProbe(find(entry(index), "totals.assignments", ItemListView.class), iconText);
		}

		/** How the name in the title line of the visible entry {@code index} looks ({@link PanelProbe#look}). */
		String getNameLook(int index)
		{
			return look(find(entry(index), "totals.name"));
		}

		/** The tooltip of the task count in the title line of the visible entry {@code index}. */
		String getTaskCountTooltip(int index)
		{
			Container title = (Container) entry(index).getComponent(0);
			return ((JComponent) title.getComponent(title.getComponentCount() - 1)).getToolTipText();
		}

		/**
		 * What the icon before each visible entry's name shows (By monster's task, By master's face), in
		 * order, as {@link PanelProbe#iconText} reads it: {@code "<item id>"}, {@code "master:<file>"}, {@code "-"} for
		 * an empty slot or {@code "none"} for a name without one; then the icon's size as {@code "18x16"}.
		 */
		List<String> getNameIcons()
		{
			return labelIcons(panels(list()), "totals.name", iconText);
		}

		/** Whether the visible entry {@code index} has a "Monsters assigned" list (only By master's do). */
		boolean hasAssignments(int index)
		{
			return findOrNull(entry(index), "totals.assignments") != null;
		}

		/** The width the shown entries want, to check that they fit the sidebar ({@code PluginPanel.PANEL_WIDTH}). */
		int getListPreferredWidth()
		{
			return list().getPreferredSize().width;
		}

		/**
		 * Each visible entry's rows below its title line, as {@code "<label>"}: the left-hand label of each, in order,
		 * so a test can see that a grouping's blocks have the same rows.
		 */
		List<List<String>> getRowLabels()
		{
			List<List<String>> labels = new ArrayList<>();
			for (Component entry : panels(list()))
			{
				List<String> rows = new ArrayList<>();
				Component[] lines = ((Container) entry).getComponents();
				for (int i = 1; i < lines.length; i++)
				{
					if (!"totals.items".equals(lines[i].getName()))
					{
						rows.add(text(((Container) lines[i]).getComponent(0)));
					}
				}
				labels.add(rows);
			}
			return labels;
		}

		/**
		 * The text colours of the visible entry {@code index}'s lines, in the order {@link #getRows} reads them: per
		 * line, the colours of its labels left to right.
		 */
		List<List<Color>> getLineColors(int index)
		{
			List<List<Color>> colors = new ArrayList<>();
			for (Component line : entry(index).getComponents())
			{
				if ("totals.items".equals(line.getName()))
				{
					continue;
				}
				List<Color> parts = new ArrayList<>();
				for (Component part : ((Container) line).getComponents())
				{
					parts.add(part.getForeground());
				}
				colors.add(parts);
			}
			return colors;
		}

		/**
		 * Lays the panel out at the sidebar's width ({@code PluginPanel.PANEL_WIDTH}), as the player sees it, and
		 * returns the totals list's width.
		 */
		int layOut()
		{
			panel.setSize(PluginPanel.PANEL_WIDTH, 4000);
			SwingLookup.layOut(panel);
			return list().getWidth();
		}

		/** After {@link #layOut}: each visible entry's name as shown, whole or cut short with {@link FitLabel#ELLIPSIS}. */
		List<String> getNamesShown()
		{
			List<String> names = new ArrayList<>();
			for (Component entry : panels(list()))
			{
				names.add(((JLabel) find((Container) entry, "totals.name")).getText());
			}
			return names;
		}

		/**
		 * After {@link #layOut}: the lines of the visible entries (title and rows, as {@link #getRows} reads them)
		 * whose parts don't fit side by side in the list: a part past the line's right edge, or the value on the right
		 * over the label on the left. Empty when every line fits.
		 */
		List<String> getLinesThatDontFit()
		{
			List<String> crowded = new ArrayList<>();
			int width = list().getWidth();
			for (Component entry : panels(list()))
			{
				for (Component line : ((Container) entry).getComponents())
				{
					if ("totals.items".equals(line.getName()))
					{
						continue;
					}
					Component[] parts = ((Container) line).getComponents();
					Component first = parts[0];
					Component last = parts[parts.length - 1];
					boolean overlaps = parts.length > 1 && first.getX() + first.getWidth() > last.getX();
					if (overlaps || entry.getX() + line.getX() + last.getX() + last.getWidth() > width)
					{
						crowded.add(lineText(line));
					}
				}
			}
			return crowded;
		}

		/** The Group choices, in order, as the combo box shows them. */
		List<String> getGroupings()
		{
			@SuppressWarnings("unchecked")
			JComboBox<OverallTotals.Grouping> box = (JComboBox<OverallTotals.Grouping>) part("grouping");
			return items(box, String::valueOf);
		}

		/** The visible entry {@code index} (its lines and grids). */
		private Container entry(int index)
		{
			List<Container> entries = new ArrayList<>();
			for (Component component : list().getComponents())
			{
				if (component instanceof JPanel)
				{
					entries.add((Container) component);
				}
			}
			return entries.get(index);
		}

		/** The tooltip of each visible entry. */
		List<String> getTooltips()
		{
			return rowTooltips(list());
		}

		/** How many times the rows were built (performance seam). */
		int getBuildCount()
		{
			return panel.getOverallTotalsBuildCount();
		}
	}

	/** The Skipped / blocked section. */
	static final class AssignmentsProbe extends CollapsibleProbe
	{
		private final Function<Icon, String> iconText;

		AssignmentsProbe(SlayerLedgerPanel panel, Function<Icon, String> iconText)
		{
			super(panel, "assignments");
			this.iconText = iconText;
		}

		/** What the icon before each shown row's monster shows, in order ({@link PanelProbe#iconText}). */
		List<String> getNameIcons()
		{
			return labelIcons(panels(list()), "assignments.name", iconText);
		}

		/** The width the shown rows want, to check that they fit the sidebar ({@code PluginPanel.PANEL_WIDTH}). */
		int getListPreferredWidth()
		{
			return list().getPreferredSize().width;
		}

		/** Skipped / blocked listens on its header only; its title isn't clickable, so use {@link #toggle()}. */
		@Override
		void clickTitle()
		{
			throw new UnsupportedOperationException("Skipped / blocked has no clickable title; use toggle()");
		}

		/** The header, as {@code "Skipped / blocked (n) | Show"}. */
		String getHeaderText()
		{
			return text(part("title")) + " | " + text(part("toggle"));
		}

		/**
		 * Each row shown, in order, as {@code "<monster> | <outcome> | <date · master> | <tooltip>"}: its lines'
		 * labels, the first line's then the second's (when the row has one), then its tooltip; empty while closed.
		 */
		List<String> getRows()
		{
			List<String> rows = new ArrayList<>();
			for (Component c : list().getComponents())
			{
				if (c instanceof JPanel)
				{
					List<String> lines = new ArrayList<>();
					for (Component line : ((Container) c).getComponents())
					{
						lines.add(lineText(line));
					}
					rows.add(String.join(" | ", lines) + " | " + ((JPanel) c).getToolTipText());
				}
			}
			return rows;
		}

		/** The one line shown instead of the rows while there are none, or {@code null} while rows (or none) show. */
		String getEmptyText()
		{
			Component empty = findOrNull(list(), "assignments.empty");
			return empty == null ? null : text(empty);
		}

		/** Whether the hint above the rows ("Not counted as played tasks.", how to delete) is shown (set visible). */
		boolean isHintShown()
		{
			return part("hint").isVisible();
		}

		/**
		 * Lays the panel out at the sidebar's width ({@code PluginPanel.PANEL_WIDTH}), as the player sees it, and
		 * returns the list's width.
		 */
		int layOut()
		{
			panel.setSize(PluginPanel.PANEL_WIDTH, 4000);
			SwingLookup.layOut(panel);
			return list().getWidth();
		}

		/**
		 * After {@link #layOut}: each row's monster as shown, whole or cut short with {@link FitLabel#ELLIPSIS}, then
		 * {@code " | "} and its outcome, with {@code " (fits)"} when the outcome sits right of the monster inside the
		 * row.
		 */
		List<String> getFirstLinesShown()
		{
			List<String> lines = new ArrayList<>();
			for (Component row : panels(list()))
			{
				Container first = (Container) ((Container) row).getComponent(0);
				Component name = first.getComponent(0);
				Component outcome = first.getComponent(1);
				boolean fits = name.getX() + name.getWidth() <= outcome.getX()
					&& outcome.getX() + outcome.getWidth() <= first.getWidth();
				lines.add(text(name) + " | " + text(outcome) + (fits ? " (fits)" : ""));
			}
			return lines;
		}

		/** The text colours of the shown row {@code index}'s labels, in the order {@link #getRows} reads them. */
		List<Color> getRowColors(int index)
		{
			List<Color> colors = new ArrayList<>();
			for (Component line : ((Container) panels(list()).get(index)).getComponents())
			{
				for (Component label : ((Container) line).getComponents())
				{
					colors.add(label.getForeground());
				}
			}
			return colors;
		}

		/**
		 * The right-click menu items of the shown row {@code index}, as {@code "Delete entry"}, or
		 * {@code "-Delete entry"} when disabled.
		 */
		List<String> getMenuItems(int index)
		{
			List<String> items = new ArrayList<>();
			for (Component c : ((JComponent) panels(list()).get(index)).getComponentPopupMenu().getComponents())
			{
				if (c instanceof JMenuItem)
				{
					items.add((c.isEnabled() ? "" : "-") + ((JMenuItem) c).getText());
				}
			}
			return items;
		}

		/**
		 * The menu a right-click on each label of the shown row {@code index} opens (Swing shows a component's
		 * {@link JComponent#getComponentPopupMenu}), in the order {@link #getRows} reads them, as
		 * {@code "<label text>: row menu"} when it is the row's own menu, else {@code ": other menu"} or
		 * {@code ": no menu"}.
		 */
		List<String> getLabelMenus(int index)
		{
			JComponent row = (JComponent) panels(list()).get(index);
			List<String> menus = new ArrayList<>();
			for (Component line : row.getComponents())
			{
				for (Component label : ((Container) line).getComponents())
				{
					JPopupMenu menu = ((JComponent) label).getComponentPopupMenu();
					menus.add(text(label) + ": " + (menu == null ? "no menu"
						: menu == row.getComponentPopupMenu() ? "row menu" : "other menu"));
				}
			}
			return menus;
		}

		/** The background of the shown row {@code index}: the page, or the hover colour under the mouse. */
		Color getRowBackground(int index)
		{
			return panels(list()).get(index).getBackground();
		}

		/** Moves the mouse onto ({@code true}) or off the shown row {@code index}, as the player does. */
		void hoverRow(int index, boolean on)
		{
			Component row = panels(list()).get(index);
			row.dispatchEvent(new MouseEvent(row, on ? MouseEvent.MOUSE_ENTERED : MouseEvent.MOUSE_EXITED,
				System.currentTimeMillis(), 0, 1, 1, 0, false));
		}

		/** How many times the rows were built (performance seam). */
		int getBuildCount()
		{
			return panel.getAssignmentsBuildCount();
		}
	}
}
