package com.shmayvan.slayerledger;

import java.awt.Image;
import java.util.function.Consumer;

/**
 * Where the sidebar's setup tag images come from: the game images {@link SetupTagIcons} names, asked for once
 * each through {@link SetupImages} for the live card's toggles and the Task history rows. The plugin backs it with
 * RuneLite's {@code ItemManager} (the cannon barrels item) and {@code SpriteManager} (the spell and skill sprites),
 * {@link SlayerLedgerPlugin#loadSetupIcon}; tests pass a fake. Called on the EDT.
 */
@FunctionalInterface
interface SetupIcons
{
	/**
	 * Asks for {@code icon}'s image and hands it to {@code show} on the EDT, at once or later (a sprite waits for the
	 * game's cache). An item icon may be handed over blank and drawn afterwards: {@code repaint} is then run, on any
	 * thread, once it is drawn.
	 */
	void load(SetupTagIcons.Icon icon, Consumer<Image> show, Runnable repaint);
}
