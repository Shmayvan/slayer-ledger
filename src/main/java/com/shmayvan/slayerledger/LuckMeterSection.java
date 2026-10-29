package com.shmayvan.slayerledger;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The collapsed "Luck meter" section of {@link SlayerLedgerPanel}: selected drops in completed tasks against
 * their expected count ({@link LuckMeter}, worked out by the history model). Nothing is compared or named while the
 * section is collapsed or its "Luck meter" setting hides it, and never by the panel's refresh timer. EDT only.
 *
 * <p>The Task choices show each task's icon ({@link TaskIcons}) before its name, asked for when the choices are
 * built (open and shown) and drawn by a wrapper around the look and feel's renderer ({@link TaskIconCache}). The box is
 * sized from a short name, so a long one is cut short in the closed box rather than widening the sidebar, and its open
 * list widens to fit ({@link ComboPopupWidener}).</p>
 */
final class LuckMeterSection
{
	private final LedgerHistoryModel model;
	/** Revalidates and repaints the panel after a change. */
	private final Runnable relayout;

	private final JPanel header = new JPanel(new BorderLayout(6, 0));
	private final JLabel title = new JLabel("Luck meter");
	private final JLabel toggle = new JLabel();
	private final JPanel body = new JPanel();
	private final JComboBox<String> task = new JComboBox<>();
	/** The Task choices' icons. */
	private final TaskIconCache taskIcons;
	private final JLabel summary = new JLabel();
	private final JPanel list = new JPanel();
	private boolean expanded;
	private boolean updatingTasks;
	/** The "Luck meter" setting; while off, nothing is built, open or not. The panel hides the components. */
	private boolean shown = true;
	/** How many times the rows were built. */
	private int builds;

	/** @param icons draws the Task choices' icons */
	LuckMeterSection(LedgerHistoryModel model, Runnable relayout, ItemIcons icons)
	{
		this.model = model;
		this.relayout = relayout;
		this.taskIcons = new TaskIconCache(icons, task);
	}

	/** Adds the header and the body to {@code content}, collapsed. */
	void addTo(JPanel content)
	{
		// Names for tests, which find the parts by them; no visible effect.
		header.setName("luck.header");
		title.setName("luck.title");
		toggle.setName("luck.toggle");
		body.setName("luck.body");
		task.setName("luck.task");
		summary.setName("luck.summary");
		list.setName("luck.list");
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		toggle.setFont(FontManager.getRunescapeSmallFont());
		toggle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		header.add(title, BorderLayout.WEST);
		header.add(toggle, BorderLayout.EAST);
		header.setToolTipText("<html>Compare selected drops in completed tasks with their expected count."
			+ "<br>Every kill of the task counts toward expected at the standard monster's rate: variants,"
			+ "<br>superiors, task bosses (Abyssal Sire, Grotesque Guardians), Greater nechryael in the"
			+ "<br>Catacombs and any location are pooled, so the comparison is approximate."
			+ "<br>Only tasks with loot data count. Rare drops vary a lot over small samples."
			+ "<br>Drop rates: Old School RuneScape Wiki contributors, CC BY-NC-SA 3.0.</html>");
		MouseAdapter toggleListener = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggle();
			}
		};
		header.addMouseListener(toggleListener);
		title.addMouseListener(toggleListener);
		toggle.addMouseListener(toggleListener);
		SlayerLedgerPanel.addRow(content, header);

		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JPanel taskRow = SlayerLedgerPanel.selectorRow("Task", task);
		task.setFocusable(false);
		taskIcons.install(task, choice -> choice);
		// Sized from a short name, not the longest choice, so neither a long name nor the icon widens the sidebar.
		task.setPrototypeDisplayValue("Abyssal demons");
		ComboPopupWidener.install(task, ComboPopupWidener.MAX_WIDTH);
		task.setToolTipText("Choose a supported task; totals pool its completed tasks with loot data.");
		task.addActionListener(e ->
		{
			if (!updatingTasks && expanded && shown)
			{
				buildRows();
			}
		});
		SlayerLedgerPanel.addRow(body, taskRow);

		summary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		summary.setFont(FontManager.getRunescapeSmallFont());
		summary.setBorder(BorderFactory.createEmptyBorder(4, 8, 2, 4));
		summary.setAlignmentX(Component.LEFT_ALIGNMENT);
		SlayerLedgerPanel.addRow(body, summary);
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		list.setAlignmentX(Component.LEFT_ALIGNMENT);
		SlayerLedgerPanel.addRow(body, list);
		body.setVisible(false);
		SlayerLedgerPanel.addRow(content, body);
		rebuild();
	}

	/** Opens or closes the section. */
	void toggle()
	{
		expanded = !expanded;
		rebuild();
	}

	/**
	 * Applies the "Luck meter" setting: hidden, the section drops its rows and compares nothing; shown again, it
	 * is rebuilt from the current records at once if it was open.
	 */
	void setShown(boolean value)
	{
		if (shown != value)
		{
			shown = value;
			rebuild();
		}
	}

	/** Rebuilds the section when it is open and shown, as item names arrive; does nothing otherwise. */
	void itemNamesChanged()
	{
		if (expanded && shown)
		{
			rebuild();
		}
	}

	/**
	 * Shows the section as it now is: when open and shown, refreshes the task choices and compares the current records;
	 * otherwise drops the rows.
	 */
	void rebuild()
	{
		toggle.setText(expanded ? "Hide" : "Show");
		body.setVisible(expanded);
		if (!expanded || !shown)
		{
			// Nothing is compared (or named) while collapsed or hidden; opening or showing the section compares the
			// current records.
			list.removeAll();
			relayout.run();
			return;
		}
		List<String> tasks = model.luckTasks();
		if (tasks == null)
		{
			tasks = Collections.emptyList();
		}
		String previous = (String) task.getSelectedItem();
		updatingTasks = true;
		try
		{
			DefaultComboBoxModel<String> choices = new DefaultComboBoxModel<>();
			for (String choice : tasks)
			{
				choices.addElement(choice);
				// Each task's icon once; open and shown only, never on the refresh timer.
				taskIcons.request(choice);
			}
			task.setModel(choices);
			if (previous != null && tasks.contains(previous))
			{
				task.setSelectedItem(previous);
			}
			task.setEnabled(!tasks.isEmpty());
		}
		finally
		{
			updatingTasks = false;
		}
		buildRows();
	}

	private void buildRows()
	{
		builds++;
		list.removeAll();
		summary.setToolTipText(null);
		LuckView view = model.luck((String) task.getSelectedItem());
		switch (view.getState())
		{
			case UNAVAILABLE:
				summary.setText("");
				addMessage("Luck meter data unavailable");
				break;
			case NO_SAMPLES:
				summary.setText("");
				addMessage("No completed tasks with loot data.");
				break;
			case NAMES_LOADING:
				summary.setText("");
				addMessage("Loading saved loot item names...");
				break;
			default:
				LuckMeter.Summary result = view.getSummary();
				summary.setText(SlayerLedgerPanel.sampleTasks(result.getTaskCount()) + " / " + result.getKills()
					+ " kills with loot data");
				for (int i = 0; i < result.getDrops().size(); i++)
				{
					list.add(row(result, result.getDrops().get(i), view.isNamesFailed()));
					if (i + 1 < result.getDrops().size())
					{
						list.add(Box.createVerticalStrut(2));
					}
				}
				if (view.isNamesFailed())
				{
					addMessage("Some item names unavailable.");
				}
				break;
		}
		relayout.run();
	}

	/**
	 * One drop in two short two-column lines that fit the sidebar, like a history row: the item name (truncates) and
	 * the ratio, or {@code too few kills} below 1 expected drop (owner decision), then observed / expected.
	 */
	private static JPanel row(LuckMeter.Summary summary, LuckMeter.DropEntry drop, boolean namesFailed)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 4));

		JLabel name = SlayerLedgerPanel.historyLabel(drop.getItem(), Color.WHITE);
		String ratio = drop.hasRatio() ? String.format(Locale.ROOT, "%.1fx", drop.getRatio()) : "too few kills";
		row.add(SlayerLedgerPanel.historyLine(name, SlayerLedgerPanel.historyLabel(ratio, ColorScheme.LIGHT_GRAY_COLOR)));
		row.add(SlayerLedgerPanel.historyLine(
			SlayerLedgerPanel.historyLabel("Observed / expected:", ColorScheme.LIGHT_GRAY_COLOR),
			SlayerLedgerPanel.historyLabel(drop.getActual() + " / " + String.format(Locale.ROOT, "%.2f",
				drop.getExpected()), ColorScheme.LIGHT_GRAY_COLOR)));
		for (Component line : row.getComponents())
		{
			((JComponent) line).setAlignmentX(Component.LEFT_ALIGNMENT);
		}
		row.setToolTipText(tooltip(summary, drop, namesFailed));
		return row;
	}

	/** The drop row's tooltip (HTML-escaped): exact numbers, the Poisson chance and the caveats. */
	static String tooltip(LuckMeter.Summary summary, LuckMeter.DropEntry drop, boolean namesFailed)
	{
		StringBuilder text = new StringBuilder("<html>").append(LedgerHistory.escapeHtml(drop.getItem()))
			.append("<br>Observed: ").append(drop.getActual())
			.append("<br>Expected: ").append(String.format(Locale.ROOT, "%.4f", drop.getExpected()))
			.append(" (").append(summary.getKills()).append(" kills x ")
			.append(drop.getNumerator()).append('/').append(drop.getDenominator()).append(')');
		if (drop.hasRatio())
		{
			text.append("<br>Observed / expected: ").append(String.format(Locale.ROOT, "%.2fx", drop.getRatio()));
		}
		else
		{
			text.append("<br>Too few kills for a ratio (under 1 expected drop).");
		}
		text.append("<br>Chance of at least ").append(drop.getActual()).append(": ")
			.append(formatChance(drop.getChanceOfAtLeastObserved())).append(" (Poisson model)")
			.append("<br>Rare drops vary a lot; small samples say little about luck.")
			.append("<br>n = ").append(summary.getTaskCount()).append(" tasks with loot data.")
			.append("<br>All task kills count at the standard monster's rate, so this is approximate.");
		if (namesFailed)
		{
			text.append("<br>Some item names were unavailable; observed counts may be incomplete.");
		}
		return text.append("</html>").toString();
	}

	/** A probability as a percentage, never rounding an uncertain value to 0% or 100%. */
	static String formatChance(double chance)
	{
		if (chance >= 1)
		{
			return "100%";
		}
		if (chance <= 0)
		{
			return "0%";
		}
		if (chance < 0.001)
		{
			return "<0.1%";
		}
		if (chance > 0.999)
		{
			return ">99.9%";
		}
		return String.format(Locale.ROOT, "%.1f%%", chance * 100);
	}

	private void addMessage(String text)
	{
		JLabel message = new JLabel(text);
		message.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		message.setFont(FontManager.getRunescapeSmallFont());
		message.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));
		message.setAlignmentX(Component.LEFT_ALIGNMENT);
		list.add(message);
	}

	/** How many times the rows were built. Performance seam: tests assert this doesn't grow on the refresh timer. */
	int getBuildCount()
	{
		return builds;
	}
}
