package com.shmayvan.slayerledger;

import lombok.*;

/**
 * One "Skipped / blocked" row: its entry, the monster's name and the HTML tooltip. The outcome word, date and master
 * shown on the row come from the entry ({@link AssignmentsSection}).
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PACKAGE)
final class AssignmentRow
{
	private final AssignmentEntry entry;
	private final String monster;
	private final String tooltip;
}
