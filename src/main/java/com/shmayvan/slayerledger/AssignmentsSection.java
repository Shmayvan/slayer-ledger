package com.shmayvan.slayerledger;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BiConsumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The collapsed "Skipped / blocked" section of {@link SlayerLedgerPanel}: assignments that ended without a saved
 * session, which never count as played tasks. Rows ({@link LedgerHistoryModel#assignmentRows}) are built only while the
 * section is open and its "Skipped / blocked" setting shows it. EDT only.
 *
 * <p>Each row's monster shows its task's icon ({@link TaskIcons}), or an empty slot of the same size for a name
 * that is no known task; asked for only as the rows are built.</p>
 */
final class AssignmentsSection
{
	private final LedgerHistoryModel model;
	/** Revalidates and repaints the panel after a change. */
	private final Runnable relayout;
	/** Parent of the delete confirmation. */
	private final Component dialogParent;
	/** Deletes the entry with the given id from the account's log, after the player confirmed it. */
	private final BiConsumer<Long, String> delete;
	/** Draws the rows' task icons. */
	private final ItemIcons icons;

	private final JPanel header = new JPanel(new BorderLayout(6, 0));
	private final JLabel title = new JLabel();
	private final JLabel toggle = new JLabel();
	private final JPanel body = new JPanel();
	private final JPanel list = new JPanel();
	/** Whether the section is open; collapsed whenever the panel is created (owner decision). */
	private boolean expanded;
	/** How many times the rows were built. */
	private int builds;
	/** The "Skipped / blocked" setting; while off, no rows are built. The panel hides the components. */
	private boolean shown = true;

	/** @param icons draws the rows' task icons */
	AssignmentsSection(LedgerHistoryModel model, Runnable relayout, Component dialogParent,
		BiConsumer<Long, String> delete, ItemIcons icons)
	{
		this.model = model;
		this.relayout = relayout;
		this.dialogParent = dialogParent;
		this.delete = delete;
		this.icons = icons;
	}

	/** Adds the header and the body to {@code content}, collapsed. */
	void addTo(JPanel content)
	{
		// Names for tests, which find the parts by them; no visible effect.
		header.setName("assignments.header");
		title.setName("assignments.title");
		toggle.setName("assignments.toggle");
		body.setName("assignments.body");
		list.setName("assignments.list");
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		toggle.setFont(FontManager.getRunescapeSmallFont());
		toggle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		header.add(title, BorderLayout.WEST);
		header.add(toggle, BorderLayout.EAST);
		header.setToolTipText("<html>Assignments that ended without a saved session: skipped, blocked,"
			+ " reset,<br>or completed while no session was tracked. They don't count as played tasks.</html>");
		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggle();
			}
		});
		SlayerLedgerPanel.addRow(content, header);

		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel hint = new JLabel("Not counted as played tasks. Right-click to delete.");
		hint.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(hint);
		body.add(Box.createVerticalStrut(4));
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		list.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(list);
		SlayerLedgerPanel.addRow(content, body);
		rebuild();
	}

	/** Opens or closes the section, as a click on its header does. */
	void toggle()
	{
		expanded = !expanded;
		rebuild();
	}

	/**
	 * Applies the "Skipped / blocked" setting: hidden, the section drops its rows and builds none; shown again, it
	 * is rebuilt at once if it was open.
	 */
	void setShown(boolean value)
	{
		if (shown != value)
		{
			shown = value;
			rebuild();
		}
	}

	/** Shows the section as it now is: the title with the count, and while open and shown, the rows. */
	void rebuild()
	{
		title.setText("Skipped / blocked (" + model.assignments().size() + ")");
		toggle.setText(expanded ? "Hide" : "Show");
		body.setVisible(expanded);
		list.removeAll();
		// No rows while collapsed or hidden.
		if (expanded && shown)
		{
			builds++;
			if (model.assignments().isEmpty())
			{
				JLabel empty = new JLabel("None yet.");
				empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				empty.setFont(FontManager.getRunescapeSmallFont());
				empty.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
				empty.setAlignmentX(Component.LEFT_ALIGNMENT);
				list.add(empty);
			}
			for (AssignmentRow entry : model.assignmentRows())
			{
				if (list.getComponentCount() > 0)
				{
					list.add(Box.createVerticalStrut(2));
				}
				JPanel row = row(model.account(), entry);
				row.setAlignmentX(Component.LEFT_ALIGNMENT);
				list.add(row);
			}
		}
		relayout.run();
	}

	private JPanel row(long account, AssignmentRow shownRow)
	{
		AssignmentEntry entry = shownRow.getEntry();
		JLabel name = SlayerLedgerPanel.historyLabel(shownRow.getMonster(), Color.WHITE);
		// A name for tests, which read the icon from it; no visible effect.
		name.setName("assignments.name");
		SmallItemIcon.applyTask(icons, name, entry.getMonster());
		JPanel row = SlayerLedgerPanel.historyLine(name,
			SlayerLedgerPanel.historyLabel(shownRow.getStatus(), ColorScheme.LIGHT_GRAY_COLOR));
		row.setOpaque(true);
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		row.setToolTipText(shownRow.getTooltip());

		JPopupMenu menu = new JPopupMenu();
		menu.setBorder(new EmptyBorder(5, 5, 5, 5));
		JMenuItem deleteItem = new JMenuItem("Delete entry");
		// LedgerStore.deleteAssignment matches by id; an entry without one cannot be targeted safely.
		deleteItem.setEnabled(entry.getId() != null);
		deleteItem.addActionListener(e -> confirmDelete(account, entry));
		menu.add(deleteItem);
		row.setComponentPopupMenu(menu);

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});
		return row;
	}

	/** Asks before deleting an assignment entry; Cancel or closing the dialog does nothing. */
	private void confirmDelete(long account, AssignmentEntry entry)
	{
		String[] options = {"Delete", "Cancel"};
		int choice = JOptionPane.showOptionDialog(dialogParent, AssignmentLog.deleteConfirmation(entry),
			"Slayer Ledger", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
		if (choice != 0 || entry.getId() == null || account == SessionTracker.NO_ACCOUNT)
		{
			return;
		}
		delete.accept(account, entry.getId());
	}

	/** How many times the rows were built. Performance seam: tests assert this doesn't grow on the refresh timer. */
	int getBuildCount()
	{
		return builds;
	}
}
