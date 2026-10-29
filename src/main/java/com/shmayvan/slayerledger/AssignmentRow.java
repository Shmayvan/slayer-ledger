package com.shmayvan.slayerledger;

/** One "Skipped / blocked" row. */
final class AssignmentRow
{
	private final AssignmentEntry entry;
	private final String monster;
	private final String status;
	private final String tooltip;

	AssignmentRow(AssignmentEntry entry, String monster, String status, String tooltip)
	{
		this.entry = entry;
		this.monster = monster;
		this.status = status;
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

	String getStatus()
	{
		return status;
	}

	String getTooltip()
	{
		return tooltip;
	}
}
