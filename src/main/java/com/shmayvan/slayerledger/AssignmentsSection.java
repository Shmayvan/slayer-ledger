package com.shmayvan.slayerledger;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.border.EmptyBorder;

/**
 * The collapsed "Skipped / blocked" section of {@link SlayerLedgerPanel}: assignments that ended without a saved
 * session, which never count as played tasks. Rows ({@link LedgerHistoryModel#assignmentRows}) are built only while the
 * section is open and its "Skipped / blocked" setting shows it. EDT only.
 *
 * <p>Look A (DESIGN_PRINCIPLES.md 3.3, 3.8): a quiet log, flat on the page. Each entry is two lines in
 * Small: the task's icon ({@link TaskIcons}, or an empty slot of the same size for a name that is no known task;
 * asked for only as the rows are built) and the monster, with the outcome word on the right, both in
 * {@link LedgerTheme#LABEL}; then the date and the master in {@link LedgerTheme#HINT}, each only when known. The row
 * takes {@link LedgerTheme#HOVER} under the mouse, since it has a right-click menu; every text colour keeps 4.5:1 on
 * both backgrounds.</p>
 */
final class AssignmentsSection
{
	/** Shown instead of the rows while there are none (DESIGN_PRINCIPLES.md 3.9: plain, one line). */
	static final String EMPTY_TEXT = "No skipped or blocked tasks yet.";

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
	/**
	 * What the rows are and how to delete one, on two lines that each fit the sidebar (as one line it needs 248 px of
	 * the 213); shown only above rows.
	 */
	private final JPanel hint = new JPanel(new GridLayout(2, 1, 0, 0));
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
		hint.setName("assignments.hint");
		list.setName("assignments.list");
		// A section header (DESIGN_PRINCIPLES.md 3.3).
		LedgerTheme.styleSectionHeader(header, title, toggle);
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
		body.setBackground(LedgerTheme.PAGE);
		hint.setOpaque(false);
		hint.add(LedgerTheme.label("Not counted as played tasks.", LedgerTheme.smallFont(), LedgerTheme.HINT));
		hint.add(LedgerTheme.label("Right-click an entry to delete it.", LedgerTheme.smallFont(), LedgerTheme.HINT));
		hint.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		hint.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(hint);
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(LedgerTheme.PAGE);
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

	/**
	 * Shows the section as it now is: the title with the count, and while open and shown, the rows, or one plain line
	 * without any.
	 */
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
			boolean empty = model.assignments().isEmpty();
			hint.setVisible(!empty);
			if (empty)
			{
				JLabel none = LedgerTheme.label(EMPTY_TEXT, LedgerTheme.smallFont(), LedgerTheme.LABEL);
				// A name for tests; no visible effect.
				none.setName("assignments.empty");
				none.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
				none.setAlignmentX(Component.LEFT_ALIGNMENT);
				list.add(none);
			}
			DateTimeFormatter date = AssignmentLog.shortDate(ZoneId.systemDefault());
			for (AssignmentRow entry : model.assignmentRows())
			{
				JPanel row = row(model.account(), entry, date);
				row.setAlignmentX(Component.LEFT_ALIGNMENT);
				list.add(row);
			}
		}
		relayout.run();
	}

	/**
	 * One entry: the task's icon and the monster, cut short with {@code …} when it doesn't fit (the tooltip has it
	 * whole), with the outcome word on the right; under the name, the date and the master
	 * ({@link #details}). Hover shows the row can be right-clicked.
	 */
	private JPanel row(long account, AssignmentRow shownRow, DateTimeFormatter date)
	{
		AssignmentEntry entry = shownRow.getEntry();
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setOpaque(true);
		row.setBackground(LedgerTheme.PAGE);
		row.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
		row.setToolTipText(shownRow.getTooltip());

		FitLabel name = new FitLabel(shownRow.getMonster());
		// A name for tests, which read the icon and the text as shown from it; no visible effect.
		name.setName("assignments.name");
		name.setFont(LedgerTheme.smallFont());
		name.setForeground(LedgerTheme.LABEL);
		SmallItemIcon.applyTask(icons, name, entry.getMonster());
		JLabel outcome = LedgerTheme.label(AssignmentOutcome.orUnknown(entry.getOutcome()).getLabel(),
			LedgerTheme.smallFont(), LedgerTheme.LABEL);
		row.add(line(name, outcome));

		String details = details(entry, date);
		if (!details.isEmpty())
		{
			FitLabel second = new FitLabel(details);
			second.setFont(LedgerTheme.smallFont());
			second.setForeground(LedgerTheme.HINT);
			// Under the name, past the icon's slot.
			second.setBorder(BorderFactory.createEmptyBorder(0, ItemListView.ICON_WIDTH + name.getIconTextGap(), 0, 0));
			row.add(line(second, null));
		}

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
				row.setBackground(LedgerTheme.HOVER);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(LedgerTheme.PAGE);
			}
		});
		return row;
	}

	/**
	 * One line of a row: {@code left} fills it (cut short if too long), {@code right}, if any, is right-aligned. Both
	 * use the row's right-click menu.
	 */
	private static JPanel line(JComponent left, JComponent right)
	{
		JPanel line = new JPanel(new BorderLayout(6, 0));
		line.setOpaque(false);
		line.setAlignmentX(Component.LEFT_ALIGNMENT);
		line.setInheritsPopupMenu(true);
		left.setInheritsPopupMenu(true);
		line.add(left, BorderLayout.CENTER);
		if (right != null)
		{
			right.setInheritsPopupMenu(true);
			line.add(right, BorderLayout.EAST);
		}
		return line;
	}

	/**
	 * A row's second line, the date the assignment ended and its master, each only when known:
	 * {@code 10/26/26 · Duradel/Kuradal}; empty when neither is.
	 */
	private static String details(AssignmentEntry entry, DateTimeFormatter date)
	{
		List<String> parts = new ArrayList<>();
		if (entry.getEndedAt() > 0)
		{
			parts.add(date.format(Instant.ofEpochMilli(entry.getEndedAt())));
		}
		String master = SlayerMaster.displayName(entry.getSlayerMaster());
		if (master != null)
		{
			parts.add(master);
		}
		return String.join(AssignmentLog.SEPARATOR, parts);
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
