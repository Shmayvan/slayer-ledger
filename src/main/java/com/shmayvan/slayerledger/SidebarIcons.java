package com.shmayvan.slayerledger;

import lombok.*;

/**
 * The sidebar's icon sources, passed as one so a section's constructor stays within 7 parameters: item icons
 * for the supply and loot grids, small skill icons for the XP lists, and the setup tag images of the live card's
 * toggles and the Task history rows. The panel builds it from the plugin; tests use fakes. Used on the EDT.
 */
@Value
class SidebarIcons
{
	/** Icons of the item grids ({@link ItemGridView}). */
	ItemIcons items;
	/** Small icons before the skill names of the XP lists ({@link ItemListView}). */
	SkillIcons skills;
	/**
	 * The setup tags' game images, each asked for once and shared by the live card's toggles ({@link SetupToggles}) and
	 * the Task history rows.
	 */
	SetupImages setup;
}
