package com.shmayvan.slayerledger;

/**
 * One "Skipped / blocked" row: its entry, the monster's name and the HTML tooltip. The outcome word, date and master
 * shown on the row come from the entry ({@link AssignmentsSection}).
 */
final class AssignmentRow
{
	private final AssignmentEntry entry;
	private final String monster;
	private final String tooltip;

	AssignmentRow(AssignmentEntry entry, String monster, String tooltip)
	{
		this.entry = entry;
		this.monster = monster;
		this.tooltip = tooltip;
	}

	AssignmentEntry getEntry()
	{
		return entry;
	}

	String getMonster()
	{
		return monster;
	}

	String getTooltip()
	{
		return tooltip;
	}
}
