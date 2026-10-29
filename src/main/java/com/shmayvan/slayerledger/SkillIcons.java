package com.shmayvan.slayerledger;

import javax.swing.JLabel;

/**
 * Where the small skill icons before the names of the XP lists ({@link ItemListView}) come from. The plugin backs
 * it with RuneLite's {@code SkillIconManager} ({@link SlayerLedgerPlugin#applySkillIcon}); tests pass a fake. Called
 * on the EDT.
 */
@FunctionalInterface
interface SkillIcons
{
	/**
	 * Puts the small icon of {@code skill} (the name of RuneLite's {@code Skill} constant, as {@link SkillXp} keys
	 * skills) on {@code label}; leaves the label without an icon for a key that is no skill.
	 */
	void apply(JLabel label, String skill);
}
