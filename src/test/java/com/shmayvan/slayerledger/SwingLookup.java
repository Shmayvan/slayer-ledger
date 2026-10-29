package com.shmayvan.slayerledger;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.text.JTextComponent;

/**
 * Finds Swing components by the name production code gives them ({@link Component#setName}) and reads or drives them
 * the way the player sees and uses them, so tests need no getter per label in the panel. A name is unique within the
 * container a test searches: names inside a reused view ({@link ItemListView}, {@link ItemGridView}) repeat, so
 * search from that view's own named root. Only components in the tree are found: a row the panel has removed (rather
 * than hidden) is not there.
 * Use it on the EDT.
 */
final class SwingLookup
{
	private SwingLookup()
	{
	}

	/** The first component named {@code name} in {@code root}'s tree (depth first, {@code root} included). */
	static Component find(Container root, String name)
	{
		Component found = findOrNull(root, name);
		if (found == null)
		{
			throw new AssertionError("no component named " + name);
		}
		return found;
	}

	/** {@link #find}, cast to {@code type}. */
	static <T> T find(Container root, String name, Class<T> type)
	{
		return type.cast(find(root, name));
	}

	/** The first component named {@code name} in {@code root}'s tree, or {@code null} when none is in it. */
	static Component findOrNull(Container root, String name)
	{
		if (name.equals(root.getName()))
		{
			return root;
		}
		for (Component child : root.getComponents())
		{
			if (name.equals(child.getName()))
			{
				return child;
			}
			if (child instanceof Container)
			{
				Component found = findOrNull((Container) child, name);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	/** Whether a component named {@code name} is in {@code root}'s tree (added, visible or not). */
	static boolean isInTree(Container root, String name)
	{
		return findOrNull(root, name) != null;
	}

	/** {@code parent}'s direct children named {@code name}, in order. */
	static List<Component> children(Container parent, String name)
	{
		List<Component> found = new ArrayList<>();
		for (Component child : parent.getComponents())
		{
			if (name.equals(child.getName()))
			{
				found.add(child);
			}
		}
		return found;
	}

	/** The text a label, button or text field shows. */
	static String text(Component component)
	{
		if (component instanceof JLabel)
		{
			return ((JLabel) component).getText();
		}
		if (component instanceof AbstractButton)
		{
			return ((AbstractButton) component).getText();
		}
		if (component instanceof JTextComponent)
		{
			return ((JTextComponent) component).getText();
		}
		throw new AssertionError("no text on " + component);
	}

	/** The text of the component named {@code name} in {@code root}. */
	static String text(Container root, String name)
	{
		return text(find(root, name));
	}

	/** The tooltip of the component named {@code name} in {@code root}. */
	static String tooltip(Container root, String name)
	{
		return find(root, name, JComponent.class).getToolTipText();
	}

	/** Whether the component named {@code name} in {@code root} is set visible (its own flag, as the panel sets it). */
	static boolean isVisible(Container root, String name)
	{
		return find(root, name).isVisible();
	}

	/**
	 * A history-style line ({@link SlayerLedgerPanel#historyLine}): the texts of {@code line}'s labels, left to right,
	 * joined by {@code " | "}.
	 */
	static String lineText(Component line)
	{
		StringBuilder sb = new StringBuilder();
		for (Component c : ((Container) line).getComponents())
		{
			if (sb.length() > 0)
			{
				sb.append(" | ");
			}
			sb.append(((JLabel) c).getText());
		}
		return sb.toString();
	}

	/** A left click, as the player does it. */
	static void click(Component component)
	{
		click(component, MouseEvent.BUTTON1, 1);
	}

	/** Sends a mouse click with {@code button} and {@code clickCount} to {@code component}, as Swing would. */
	static void click(Component component, int button, int clickCount)
	{
		component.dispatchEvent(new MouseEvent(component, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 1,
			1, clickCount, false, button));
	}

	/** {@code box}'s choices, in order, each as {@code label} shows it. */
	static <T> List<String> items(JComboBox<T> box, Function<T, String> label)
	{
		List<String> items = new ArrayList<>();
		for (int i = 0; i < box.getItemCount(); i++)
		{
			items.add(label.apply(box.getItemAt(i)));
		}
		return items;
	}

	/** Lays out {@code container} and everything in it; {@code validate()} needs a native peer, which a headless test has not. */
	static void layOut(Container container)
	{
		container.doLayout();
		for (Component child : container.getComponents())
		{
			if (child instanceof Container)
			{
				layOut((Container) child);
			}
		}
	}
}
