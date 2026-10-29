package com.shmayvan.slayerledger;

import java.awt.Component;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import javax.swing.Icon;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.ListCellRenderer;

/**
 * The task icons of a drop-down's choices (the Luck meter's Task, Trends' Monster), at the small slot size
 * ({@link SmallItemIcon}). A renderer runs whenever Swing paints or measures the box, so it must not ask for sprites:
 * the section asks for its choices' icons with {@link #request} when it builds them (open and shown, never on the
 * refresh timer), each item once for the panel's life, and the renderer ({@link #install}) only reads what was asked
 * for; a choice not asked for, or no known task, shows {@link SmallItemIcon#EMPTY}. A sprite that arrives later
 * repaints the box. EDT only.
 */
final class TaskIconCache
{
	private final ItemIcons icons;
	/** Repainted when a sprite arrives. */
	private final Component target;
	/** The small icon of each item asked for. */
	private final Map<Integer, Icon> byItem = new HashMap<>();

	/**
	 * @param icons  where the sprites come from
	 * @param target repainted when a sprite arrives (the drop-down)
	 */
	TaskIconCache(ItemIcons icons, Component target)
	{
		this.icons = icons;
		this.target = target;
	}

	/** Asks for the icon of the task {@code task} ({@link TaskIcons}) unless it was asked for already. */
	void request(String task)
	{
		Integer itemId = TaskIcons.itemId(task);
		if (itemId == null || byItem.containsKey(itemId))
		{
			return;
		}
		// A label only to receive the sprite; when it arrives later, its repaint repaints the drop-down instead.
		JLabel holder = new JLabel()
		{
			@Override
			public void repaint(long tm, int x, int y, int width, int height)
			{
				if (target != null)
				{
					target.repaint();
				}
			}
		};
		SmallItemIcon.apply(icons, holder, itemId);
		byItem.put(itemId, holder.getIcon());
	}

	/** The icon asked for {@code task}, or {@link SmallItemIcon#EMPTY}; never asks for one. */
	Icon get(String task)
	{
		Integer itemId = TaskIcons.itemId(task);
		Icon icon = itemId == null ? null : byItem.get(itemId);
		return icon == null ? SmallItemIcon.EMPTY : icon;
	}

	/**
	 * Wraps {@code box}'s renderer (the look and feel's, as the History filters keep it) so each choice shows the icon of
	 * its task ({@code task} of the choice) before its text; no icon for an empty box.
	 */
	<T> void install(JComboBox<T> box, Function<T, String> task)
	{
		ListCellRenderer<? super T> renderer = box.getRenderer();
		box.setRenderer((JList<? extends T> list, T value, int index, boolean selected, boolean focus) ->
		{
			Component shown = renderer.getListCellRendererComponent(list, value, index, selected, focus);
			if (shown instanceof JLabel)
			{
				JLabel label = (JLabel) shown;
				label.setIconTextGap(4);
				label.setIcon(value == null ? null : get(task.apply(value)));
			}
			return shown;
		});
	}
}
