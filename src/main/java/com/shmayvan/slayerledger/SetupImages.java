package com.shmayvan.slayerledger;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;
import lombok.*;

/**
 * The sidebar's setup tag images: the game images {@link SetupTagIcons} names, the same ones the overlay draws,
 * for the live card's setup toggles ({@link SetupToggles}) and the Task history rows' tag icons
 * ({@link HistoryListSection}). The panel makes one and hands it to both through {@link SidebarIcons}, so each image is
 * asked for once from {@link SetupIcons} (the plugin's {@code loadSetupIcon}: the cannon barrels from
 * {@code ItemManager}, the spell and skill sprites from {@code SpriteManager}), the first time either needs it, and kept
 * while the panel lasts.
 *
 * <p>An image handed over at once (an item icon, which may still be blank) is returned by {@link #get}; one handed over
 * later (a sprite, which waits for the game's cache) is kept and announced to every listener ({@link #addListener}), on
 * the EDT. When a blank item icon has been drawn, every listener's repaint runs, on any thread. EDT only, but for those
 * repaints.</p>
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class SetupImages
{
	/** Where the images come from (the plugin's {@code loadSetupIcon}; a fake in tests). */
	private final SetupIcons source;
	/** The images that have arrived. */
	private final Map<SetupTagIcons.Icon, Image> images = new EnumMap<>(SetupTagIcons.Icon.class);
	/** The images asked for: each only once. */
	private final Set<SetupTagIcons.Icon> requested = EnumSet.noneOf(SetupTagIcons.Icon.class);
	/** Told on the EDT which image arrived after it was asked for. */
	private final List<Consumer<SetupTagIcons.Icon>> listeners = new ArrayList<>();
	/** Run, on any thread, when an item icon handed over blank has been drawn. */
	private final List<Runnable> repaints = new CopyOnWriteArrayList<>();
	/** True while an image is asked for, so one handed over at once is returned rather than announced. */
	private boolean asking;

	/**
	 * Adds a user of the images: {@code arrived} is told on the EDT which image arrived after it was asked for, and
	 * {@code repaint} runs, on any thread, when an item icon handed over blank has been drawn. Both are kept while the
	 * panel lasts.
	 */
	void addListener(Consumer<SetupTagIcons.Icon> arrived, Runnable repaint)
	{
		listeners.add(arrived);
		repaints.add(repaint);
	}

	/**
	 * {@code icon}'s image as it has arrived, asking for it the first time; {@code null} for {@code null} or an image
	 * not arrived yet (its listeners are told when it does).
	 */
	Image get(SetupTagIcons.Icon icon)
	{
		if (icon == null)
		{
			return null;
		}
		if (requested.add(icon))
		{
			asking = true;
			try
			{
				source.load(icon, image -> arrived(icon, image), this::repaintAll);
			}
			finally
			{
				asking = false;
			}
		}
		return images.get(icon);
	}

	/** {@code icon}'s image was handed over (EDT): kept and, unless it came at once, announced. */
	private void arrived(SetupTagIcons.Icon icon, Image image)
	{
		if (image == null)
		{
			return;
		}
		images.put(icon, image);
		if (asking)
		{
			return;
		}
		for (Consumer<SetupTagIcons.Icon> listener : new ArrayList<>(listeners))
		{
			listener.accept(icon);
		}
	}

	private void repaintAll()
	{
		for (Runnable repaint : repaints)
		{
			repaint.run();
		}
	}
}
