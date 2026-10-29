package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.SwingLookup.children;
import static com.shmayvan.slayerledger.SwingLookup.click;
import static com.shmayvan.slayerledger.SwingLookup.find;
import static com.shmayvan.slayerledger.SwingLookup.findOrNull;
import static com.shmayvan.slayerledger.SwingLookup.items;
import static com.shmayvan.slayerledger.SwingLookup.lineText;
import static com.shmayvan.slayerledger.SwingLookup.text;
import static com.shmayvan.slayerledger.SwingLookup.tooltip;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.Border;
import javax.swing.border.LineBorder;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.JTextComponent;
import net.runelite.client.ui.PluginPanel;

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

	/** Whether the live values section is shown. */
	boolean isValuesShown()
	{
		return SwingLookup.isVisible(panel, "values");
	}

	/** Text of the live loot, supplies and profit lines, or {@code null} while they are hidden. */
	String getValuesText()
	{
		if (!isValuesShown())
		{
			return null;
		}
		return text(panel, "values.loot") + " | " + text(panel, "values.supplies") + " | "
			+ text(panel, "values.profit");
	}

	/** The live values section's tooltip, or {@code null} while it is hidden. */
	String getValuesTooltip()
	{
		return isValuesShown() ? tooltip(panel, "values") : null;
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

	/** Text of the live XP line, or {@code null} while it is not in the progress box. */
	String getXpText()
	{
		Component xp = findOrNull(progress(), "progress.xp");
		return xp == null ? null : text(xp);
	}

	/** Whether the time-left line is in the progress box. */
	boolean isTimeLeftShown()
	{
		return findOrNull(progress(), "progress.timeLeft") != null;
	}

	/** Text of the time-left line, or {@code null} while it is not in the progress box. */
	String getTimeLeftText()
	{
		Component timeLeft = findOrNull(progress(), "progress.timeLeft");
		return timeLeft == null ? null : text(timeLeft);
	}

	/** Tooltip of the time-left line, or {@code null} while it is not in the progress box. */
	String getTimeLeftTooltip()
	{
		Component timeLeft = findOrNull(progress(), "progress.timeLeft");
		return timeLeft == null ? null : ((JComponent) timeLeft).getToolTipText();
	}

	/** Text of the superiors, deaths and bank trips line, or {@code null} while it is not in the progress box. */
	String getCountsText()
	{
		Component counts = findOrNull(progress(), "progress.counts");
		if (counts == null)
		{
			return null;
		}
		Container line = (Container) counts;
		return text(line, "progress.superiors") + " | " + text(line, "progress.deaths") + " | "
			+ text(line, "progress.bankTrips");
	}

	private Container progress()
	{
		return find(panel, "progress", Container.class);
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

	/** Text of the saved-task count, e.g. {@code "Saved tasks: 3"}. */
	String getSavedTasksText()
	{
		return text(panel, "savedTasks");
	}

	// ---- Optional sections ----

	/** Whether an optional section takes space in the panel (its "Panel sections" setting is on). */
	boolean isSectionShown(SlayerLedgerPanel.OptionalSection section)
	{
		return SwingLookup.isVisible(panel, blockName(section));
	}

	/** The preferred height of an optional section's header (the What to bring box for {@code WHAT_TO_BRING}). */
	int getSectionHeaderHeight(SlayerLedgerPanel.OptionalSection section)
	{
		switch (section)
		{
			case WHAT_TO_BRING:
				return find(panel, "bring").getPreferredSize().height;
			case TRENDS:
				return find(panel, "trends.header").getPreferredSize().height;
			case OVERALL_TOTALS:
				return find(panel, "totals.header").getPreferredSize().height;
			case LUCK_METER:
				return find(panel, "luck.header").getPreferredSize().height;
			default:
				return find(panel, "assignments.header").getPreferredSize().height;
		}
	}

	private static String blockName(SlayerLedgerPanel.OptionalSection section)
	{
		switch (section)
		{
			case WHAT_TO_BRING:
				return "bringBlock";
			case TRENDS:
				return "trendsBlock";
			case OVERALL_TOTALS:
				return "totalsBlock";
			case LUCK_METER:
				return "luckBlock";
			default:
				return "assignmentsBlock";
		}
	}

	/**
	 * After laying the panel out at the sidebar's width: the width the History title gets and the width it wants, then
	 * the same for its " (N tasks)" count, the count's offset from the title, and 1 when the time has a line of its
	 * own.
	 */
	int[] layOutHistoryEstimateTitle()
	{
		panel.setSize(PluginPanel.PANEL_WIDTH, 4000);
		SwingLookup.layOut(panel);
		Component title = find(panel, "estimate.title");
		Component count = find(panel, "estimate.count");
		Component time = find(panel, "estimate.time");
		return new int[]{title.getWidth(), title.getPreferredSize().width,
			count.getWidth(), count.getPreferredSize().width,
			count.getX() - title.getX(),
			"estimate.timeLine".equals(time.getParent().getName()) ? 1 : 0};
	}

	// ---- Sections ----

	TaskBoxProbe getTaskBox()
	{
		return new TaskBoxProbe(find(panel, "taskBox", Container.class));
	}

	LocationPromptProbe getLocationPrompt()
	{
		return new LocationPromptProbe(panel);
	}

	HistoryEstimateProbe getHistoryEstimate()
	{
		return new HistoryEstimateProbe(panel);
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

	TrendsProbe getTrends()
	{
		return new TrendsProbe(panel, iconText);
	}

	OverallTotalsProbe getOverallTotals()
	{
		return new OverallTotalsProbe(panel, iconText);
	}

	LuckMeterProbe getLuckMeter()
	{
		return new LuckMeterProbe(panel, iconText);
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

	/**
	 * Each choice of {@code box}, in order, as its renderer shows it: {@code text | icon}, the icon as
	 * {@link #iconText(Icon, Function)} reads it.
	 */
	private static <T> List<String> choiceIcons(JComboBox<T> box, Function<Icon, String> iconText)
	{
		List<String> icons = new ArrayList<>();
		for (int i = 0; i < box.getItemCount(); i++)
		{
			Component shown = box.getRenderer().getListCellRendererComponent(new JList<>(), box.getItemAt(i), i,
				false, false);
			icons.add(text(shown) + " | " + iconText(((JLabel) shown).getIcon(), iconText));
		}
		return icons;
	}

	/** Each row of {@code list}: a label's text, or a panel's lines ({@code left | right}) joined by a newline. */
	private static List<String> multiLineRows(Container list)
	{
		List<String> rows = new ArrayList<>();
		for (Component component : list.getComponents())
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
					lines.add(lineText(line));
				}
				rows.add(String.join("\n", lines));
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
	 * The task box: the task line, then (while known) the details line and the advice row. The master, type, location
	 * and next bonus are read from the details line's tooltip, which has each of them in full.
	 */
	static final class TaskBoxProbe
	{
		private final Container box;

		TaskBoxProbe(Container box)
		{
			this.box = box;
		}

		/** The details line ("Konar · Kraken Cove · bonus in 3"), or {@code null} while it is not in the box. */
		String getDetailsText()
		{
			return optionalText("taskBox.details");
		}

		/** The details line's tooltip as set (HTML, not unescaped), or {@code null} while the line is not in the box. */
		String getDetailsTooltip()
		{
			Component details = findOrNull(box, "taskBox.details");
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

		/** The advice row, or {@code null} while it is not in the box. */
		String getAdviceText()
		{
			return optionalText("taskBox.advice");
		}

		/** The advice row's tooltip, or {@code null} while it is not in the box. */
		String getAdviceTooltip()
		{
			Component advice = findOrNull(box, "taskBox.advice");
			return advice == null ? null : ((JComponent) advice).getToolTipText();
		}

		/** The task box's rows as shown, in order. */
		List<String> getRows()
		{
			List<String> rows = new ArrayList<>();
			for (Component c : box.getComponents())
			{
				rows.add(text(c));
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

		/** The line of the details tooltip that starts with {@code prefix}, unescaped, or {@code null}. */
		private String detail(String prefix)
		{
			Component details = findOrNull(box, "taskBox.details");
			String tooltip = details == null ? null : ((JComponent) details).getToolTipText();
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
			return label == null ? null : text(label);
		}
	}

	/** The inline "Name this location" prompt. */
	static final class LocationPromptProbe
	{
		private final SlayerLedgerPanel panel;
		private final Container prompt;

		LocationPromptProbe(SlayerLedgerPanel panel)
		{
			this.panel = panel;
			this.prompt = find(panel, "locationPrompt", Container.class);
		}

		/** The region the prompt asks about, or {@link LocationTally#NO_REGION} while hidden (the panel's test seam). */
		int getRegion()
		{
			return panel.getLocationPromptRegion();
		}

		/** Reads the bundled task location list from {@code resource} instead (the panel's test seam). */
		void setTaskLocationsResource(String resource)
		{
			panel.setTaskLocationsResource(resource);
		}

		/** The text field's text. */
		String getText()
		{
			return field().getText();
		}

		/** The names the prompt suggests, in its list's order. */
		List<String> getSuggestions()
		{
			return items(box(), s -> s);
		}

		/** Picks suggestion {@code index} from the list, as the player would. */
		void pickSuggestion(int index)
		{
			box().setSelectedIndex(index);
		}

		/** Types {@code name} and presses Enter, as the player would. */
		void enter(String name)
		{
			JTextComponent field = field();
			field.setText(name);
			if (field instanceof JTextField)
			{
				((JTextField) field).postActionEvent();
			}
		}

		/** Fires the combo box's edit event, as Swing does when the field loses focus (e.g. clicking Skip). */
		void loseFocus(String typed)
		{
			JTextComponent field = field();
			field.setText(typed);
			box().actionPerformed(new ActionEvent(field, ActionEvent.ACTION_PERFORMED, ""));
		}

		/** Types {@code name} and clicks Save, as the player would. */
		void save(String name)
		{
			field().setText(name);
			find(prompt, "locationPrompt.save", AbstractButton.class).doClick();
		}

		/** Clicks Skip. */
		void skip()
		{
			find(prompt, "locationPrompt.skip", AbstractButton.class).doClick();
		}

		@SuppressWarnings("unchecked")
		private JComboBox<String> box()
		{
			return find(prompt, "locationPrompt.box", JComboBox.class);
		}

		private JTextComponent field()
		{
			return (JTextComponent) box().getEditor().getEditorComponent();
		}
	}

	/** The History estimate and What to bring below it. */
	static final class HistoryEstimateProbe
	{
		private final SlayerLedgerPanel panel;

		HistoryEstimateProbe(SlayerLedgerPanel panel)
		{
			this.panel = panel;
		}

		/**
		 * The estimate's lines, "History (N tasks) | N kills ~time | Loot (GE): ... | Supplies: ...", or {@code null}
		 * while it is hidden.
		 */
		String getText()
		{
			if (!SwingLookup.isVisible(panel, "estimate"))
			{
				return null;
			}
			return text(panel, "estimate.title") + text(panel, "estimate.count") + " | " + text(panel, "estimate.time")
				+ " | " + text(panel, "estimate.loot") + " | " + text(panel, "estimate.supplies");
		}

		/** The estimate's tooltip. */
		String getTooltip()
		{
			return tooltip(panel, "estimate");
		}

		/** How many times the estimate's text and tooltip were built (performance seam). */
		int getBuildCount()
		{
			return panel.getHistoryEstimateBuildCount();
		}

		/** Clicks the What to bring header, as the player does. */
		void clickBringTitle()
		{
			click(find(panel, "bring.header"));
		}

		/** Opens or closes What to bring: a click on its header. */
		void toggleBring()
		{
			clickBringTitle();
		}

		/**
		 * What to bring's header, "What to bring (N tasks) | Show | ~N bank trips | N supplies" (an unknown part
		 * empty), or {@code null} while it is hidden.
		 */
		String getBringText()
		{
			if (!SwingLookup.isVisible(panel, "bring"))
			{
				return null;
			}
			return text(panel, "bring.title") + " | " + text(panel, "bring.toggle") + " | " + text(panel, "bring.trips")
				+ " | " + text(panel, "bring.count");
		}

		/** The header tooltip of What to bring. */
		String getBringTooltip()
		{
			return tooltip(panel, "bring.header");
		}

		/** What to bring's rows as "name | amount" (or just the message), in order; empty while closed. */
		List<String> getBringLines()
		{
			List<String> lines = new ArrayList<>();
			Container rows = find(panel, "bring.rows", Container.class);
			if (!SwingLookup.isVisible(panel, "bring") || !rows.isVisible())
			{
				return lines;
			}
			for (Component c : rows.getComponents())
			{
				lines.add(lineText(c));
			}
			return lines;
		}

		/** The tooltip of What to bring's {@code index}th row. */
		String getBringRowTooltip(int index)
		{
			return ((JComponent) find(panel, "bring.rows", Container.class).getComponent(index)).getToolTipText();
		}

		/** How many times What to bring was built (performance seam). */
		int getBringBuildCount()
		{
			return panel.getBringBuildCount();
		}
	}

	/** The task history: filter, the error line and the rows with their item details. */
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

		/** The width the shown rows want, to check that they fit the sidebar ({@code PluginPanel.PANEL_WIDTH}). */
		int getListPreferredWidth()
		{
			return list.getPreferredSize().width;
		}

		/** The first line of each row shown, in order: {@code monster | status}. */
		List<String> getTitleTexts()
		{
			List<String> titles = new ArrayList<>();
			for (Component c : rows())
			{
				titles.add(lineText(((JPanel) c).getComponent(0)));
			}
			return titles;
		}

		/** The loot, supplies and profit text and the tooltip of each row shown, in order, one string per row. */
		List<String> getValuesText()
		{
			List<String> values = new ArrayList<>();
			for (Component c : rows())
			{
				JPanel row = (JPanel) c;
				JPanel profitLine = (JPanel) row.getComponent(2);
				values.add(lineText(row.getComponent(3)) + " | "
					+ ((JLabel) profitLine.getComponent(profitLine.getComponentCount() - 1)).getText() + " | "
					+ row.getToolTipText());
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
			for (Component c : details(index).getComponents())
			{
				if (c instanceof ItemGridView && ((ItemGridView) c).getHeaderText().startsWith(title + " ("))
				{
					return new ItemGridProbe((ItemGridView) c, iconText).getCellTooltips();
				}
			}
			return new ArrayList<>();
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

		/** Clicks "+N more" (or "Show less") on the {@code title} text list (the XP) below the {@code index}th row. */
		void clickShowAll(int index, String title)
		{
			for (Component c : details(index).getComponents())
			{
				if (c instanceof ItemListView && ((ItemListView) c).getHeaderText().startsWith(title + " ("))
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

		/** The XP line of each row shown, in order, as {@code "XP: 12,345 | 40,100/h"}. */
		List<String> getXpText()
		{
			List<String> xp = new ArrayList<>();
			for (Component row : rows())
			{
				xp.add(lineText(((Container) row).getComponent(4)));
			}
			return xp;
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

		/** The Location filter's choices, in order, as the player sees them ({@link HistoryFilter#label}). */
		@SuppressWarnings("unchecked")
		List<String> getLocationFilterOptions()
		{
			return items((JComboBox<String>) find(panel, "history.locationFilter", JComboBox.class),
				HistoryFilter::label);
		}

		/**
		 * Selects the choice {@code option} in the Location filter, as the player does: {@link HistoryFilter#NO_LOCATION}
		 * for the "No location" entry.
		 */
		void selectLocationFilter(String option)
		{
			find(panel, "history.locationFilter", JComboBox.class).setSelectedItem(option);
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
		 * The value each filter shows, as {@code "Monster | Location | Master"}, {@code "All"} for a filter showing
		 * every task.
		 */
		String getFilterSelections()
		{
			return find(panel, "history.filter", JComboBox.class).getSelectedItem() + " | "
				+ HistoryFilter.label((String) find(panel, "history.locationFilter", JComboBox.class).getSelectedItem())
				+ " | " + find(panel, "history.masterFilter", JComboBox.class).getSelectedItem();
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

		/** The preferred width of the three filter rows together. */
		int getFiltersPreferredWidth()
		{
			return find(panel, "history.filters").getPreferredSize().width;
		}

		/** The text shown instead of the rows ("No saved tasks yet." and so on), or {@code null} while rows are shown. */
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

	/** The Trends section: List or Chart, Metric and Monster. */
	static final class TrendsProbe extends CollapsibleProbe
	{
		private final Function<Icon, String> iconText;

		TrendsProbe(SlayerLedgerPanel panel, Function<Icon, String> iconText)
		{
			super(panel, "trends");
			this.iconText = iconText;
		}

		/** What the icon before each List row's name shows, in rank order ({@link PanelProbe#iconText}). */
		List<String> getRowIcons()
		{
			return labelIcons(panels(list()), "trends.name", iconText);
		}

		/** Each Monster choice as its renderer shows it: {@code label | icon}. */
		List<String> getMonsterIcons()
		{
			return choiceIcons(monster(), iconText);
		}

		/** The width the List rows and the Monster choice want, the larger, to check that they fit the sidebar. */
		int getPreferredWidth()
		{
			return Math.max(list().getPreferredSize().width, part("monsterRow").getPreferredSize().width);
		}

		/** Clicks the List or Chart button, as the player does. */
		void clickView(TrendsSection.View target)
		{
			((AbstractButton) part(target == TrendsSection.View.LIST ? "listButton" : "chartButton")).doClick();
		}

		/** The shown view, as its selected button shows it. */
		TrendsSection.View getView()
		{
			return ((AbstractButton) part("chartButton")).isSelected() ? TrendsSection.View.CHART
				: TrendsSection.View.LIST;
		}

		/** Whether the Monster choice is shown. */
		boolean isMonsterChoiceShown()
		{
			return part("monsterRow").isVisible();
		}

		/** Selects a metric, as the combo box does. */
		void setMetric(TaskRanking.Metric value)
		{
			metric().setSelectedItem(value);
		}

		/** The selected metric as the combo box shows it, through its renderer. */
		String getMetricText()
		{
			JComboBox<TaskRanking.Metric> metric = metric();
			Component shown = metric.getRenderer().getListCellRendererComponent(new JList<>(),
				(TaskRanking.Metric) metric.getSelectedItem(), -1, false, false);
			return ((JLabel) shown).getText();
		}

		/** Every Metric choice in order, as the combo box's list shows them, through its renderer. */
		List<String> getMetricChoices()
		{
			JComboBox<TaskRanking.Metric> metric = metric();
			List<String> choices = new ArrayList<>();
			for (int i = 0; i < metric.getItemCount(); i++)
			{
				Component shown = metric.getRenderer().getListCellRendererComponent(new JList<>(),
					metric.getItemAt(i), i, false, false);
				choices.add(((JLabel) shown).getText());
			}
			return choices;
		}

		/** Selects a chart monster by its displayed label. */
		void setMonster(String label)
		{
			JComboBox<TaskChart.Monster> monster = monster();
			for (int i = 0; i < monster.getItemCount(); i++)
			{
				if (Objects.equals(monster.getItemAt(i).getLabel(), label))
				{
					monster.setSelectedIndex(i);
					return;
				}
			}
		}

		/** The chart's monster choices. */
		List<String> getMonsters()
		{
			return items(monster(), TaskChart.Monster::getLabel);
		}

		/** The list rows, in rank order: {@code name | rate (n=...)}, or the empty message. */
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
					rows.add(lineText(component));
				}
			}
			return rows;
		}

		/** The tooltip of each list row, in rank order. */
		List<String> getTooltips()
		{
			return rowTooltips(list());
		}

		/** Number of plotted task points. */
		int getPointCount()
		{
			return getChart().getPointCount();
		}

		/** The chart, to read its point tooltips. */
		TaskTrendChartView getChart()
		{
			return (TaskTrendChartView) part("chart");
		}

		/** How many times the shown view was built (performance seam). */
		int getBuildCount()
		{
			return panel.getTrendsBuildCount();
		}

		@SuppressWarnings("unchecked")
		private JComboBox<TaskRanking.Metric> metric()
		{
			return (JComboBox<TaskRanking.Metric>) part("metric");
		}

		@SuppressWarnings("unchecked")
		private JComboBox<TaskChart.Monster> monster()
		{
			return (JComboBox<TaskChart.Monster>) part("monster");
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

		/** The tooltip of the task count in the title line of the visible entry {@code index}. */
		String getTaskCountTooltip(int index)
		{
			Container title = (Container) entry(index).getComponent(0);
			return ((JComponent) title.getComponent(title.getComponentCount() - 1)).getToolTipText();
		}

		/**
		 * What the icon before each visible entry's name shows (By monster's task; By master's face), in
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

	/** The Luck meter section. */
	static final class LuckMeterProbe extends CollapsibleProbe
	{
		private final Function<Icon, String> iconText;

		LuckMeterProbe(SlayerLedgerPanel panel, Function<Icon, String> iconText)
		{
			super(panel, "luck");
			this.iconText = iconText;
		}

		/** Each Task choice as its renderer shows it: {@code task | icon}. */
		List<String> getTaskIcons()
		{
			@SuppressWarnings("unchecked")
			JComboBox<String> box = (JComboBox<String>) part("task");
			return choiceIcons(box, iconText);
		}

		/** The width the Task choice wants, to check that it fits the sidebar ({@code PluginPanel.PANEL_WIDTH}). */
		int getTaskPreferredWidth()
		{
			return part("task").getParent().getPreferredSize().width;
		}

		/** Selects a task for the comparison, as the combo box does. */
		void setTask(String value)
		{
			((JComboBox<?>) part("task")).setSelectedItem(value);
		}

		/** The summary line. */
		String getSummary()
		{
			return text(part("summary"));
		}

		/** The visible rows, each as its lines ({@code left | right}) joined by a newline, or the message. */
		List<String> getRows()
		{
			return multiLineRows(list());
		}

		/** The tooltip of each visible row. */
		List<String> getTooltips()
		{
			return rowTooltips(list());
		}

		/** How many times the rows were built (performance seam). */
		int getBuildCount()
		{
			return panel.getLuckMeterBuildCount();
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

		/** Each row shown, in order, as {@code "<name> | <outcome · date> | <tooltip>"}; empty while closed. */
		List<String> getRows()
		{
			List<String> rows = new ArrayList<>();
			for (Component c : list().getComponents())
			{
				if (c instanceof JPanel)
				{
					rows.add(lineText(c) + " | " + ((JPanel) c).getToolTipText());
				}
			}
			return rows;
		}

		/** How many times the rows were built (performance seam). */
		int getBuildCount()
		{
			return panel.getAssignmentsBuildCount();
		}
	}
}
