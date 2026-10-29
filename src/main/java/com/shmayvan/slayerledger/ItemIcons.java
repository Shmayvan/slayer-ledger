package com.shmayvan.slayerledger;

import javax.swing.JLabel;

/**
 * Where the sidebar's item icons ({@link ItemGridView}) come from. The plugin backs it with RuneLite's
 * {@code ItemManager} ({@link SlayerLedgerPlugin#applyItemIcon}); tests pass a fake. Called on the EDT.
 */
@FunctionalInterface
interface ItemIcons
{
	/**
	 * Puts the icon of {@code itemId} (a noted id shows as a note) on {@code label}, with {@code quantity} drawn on it
	 * when it is above 1. The icon may be drawn later; the label is repainted when it is.
	 */
	void apply(JLabel label, int itemId, int quantity);
}
