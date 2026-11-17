package com.shmayvan.slayerledger;

import net.runelite.client.config.*;

@ConfigGroup(SlayerLedgerConfig.GROUP)
public interface SlayerLedgerConfig extends Config
{
	String GROUP = "slayerledger";

	// Sections in the order the config panel shows them: the core tracking and value settings first, the
	// niche charged-weapon setting last. Their keys and the items' keys are unchanged, so saved settings are kept.

	@ConfigSection(
		name = "Tracking",
		description = "What a session counts and how incomplete tasks are saved.",
		position = 0
	)
	String trackingSection = "tracking";

	@ConfigSection(
		name = "Values",
		description = "How loot is valued and which drops are listed as notable.",
		position = 1
	)
	String valuesSection = "values";

	@ConfigSection(
		name = "Chat",
		description = "The chat lines and notification the plugin posts.",
		position = 2
	)
	String chatSection = "chat";

	@ConfigSection(
		name = "Task overlay",
		description = "The in-game overlay showing the assigned task's progress, and its four groups of rows.",
		position = 3
	)
	String taskOverlaySection = "taskOverlay";

	@ConfigSection(
		name = "Panel sections",
		description = "Choose which optional sections the Slayer Ledger sidebar shows.",
		position = 4
	)
	String panelSectionsSection = "panelSections";

	@ConfigSection(
		name = "Charged weapons",
		description = "How charged weapons' supplies are estimated.",
		position = 5
	)
	String chargedWeaponsSection = "chargedWeapons";

	@ConfigItem(
		keyName = "confirmIncomplete",
		name = "Confirm before saving incomplete tasks",
		position = 0,
		section = trackingSection,
		description = "When you press End task before the task is complete, ask whether to keep the session (saved as"
			+ " partial) or discard it. When off, incomplete sessions are kept as partial automatically."
	)
	default boolean confirmIncomplete()
	{
		return true;
	}

	String AWAY_MINUTES = "awayMinutes";

	@ConfigItem(
		keyName = AWAY_MINUTES,
		name = "Away after",
		position = 4,
		section = trackingSection,
		description = "After this many minutes without a task kill or a hit on a task monster, the session goes Away:"
			+ " its time stops at your last kill or hit, and your next task kill starts it again."
	)
	@Range(min = SessionTracker.MIN_AWAY_MINUTES, max = SessionTracker.MAX_AWAY_MINUTES)
	@Units(Units.MINUTES)
	default int awayMinutes()
	{
		return SessionTracker.DEFAULT_AWAY_MINUTES;
	}

	String TASK_COMPLETE_LINE = "taskCompleteLine";

	@ConfigItem(
		keyName = TASK_COMPLETE_LINE,
		name = "Task-complete line",
		position = 0,
		section = chatSection,
		description = "When a completed Slayer task is saved, post one chat line with its kills, active time, profit"
			+ " and superiors."
	)
	default boolean taskCompleteLine()
	{
		return true;
	}

	@ConfigItem(
		keyName = "taskCompleteNotification",
		name = "Task-complete notification",
		position = 1,
		section = chatSection,
		description = "Show a RuneLite notification when a completed Slayer task is saved, with the same text as the"
			+ " task-complete line. Works whether or not that line is on."
	)
	default boolean taskCompleteNotification()
	{
		return false;
	}

	String RECORD_TASK_LINE = "recordTaskLine";

	@ConfigItem(
		keyName = RECORD_TASK_LINE,
		name = "Record task line",
		position = 2,
		section = chatSection,
		description = "When a completed task has your best time per kill for that monster (same Catacombs variant or"
			+ " boss, and same cannon use), post one chat line with its time and how far under your old best it was."
			+ " Needs 3 or more earlier completed tasks of it."
	)
	default boolean recordTaskLine()
	{
		return true;
	}

	String NOTABLE_DROP_LINE = "notableDropLine";

	@ConfigItem(
		keyName = NOTABLE_DROP_LINE,
		name = "Notable drop line",
		position = 3,
		section = chatSection,
		description = "Post one chat line when a loot item stack from one kill during a task is worth at least the"
			+ " Notable drop value: the item, its value and the monster or kill number."
	)
	default boolean notableDropLine()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showTaskOverlay",
		name = "Show task overlay",
		position = 0,
		section = taskOverlaySection,
		description = "Show the Slayer Ledger overlay while a Slayer task is assigned: its progress and, whenever the"
			+ " clock isn't running, its state (waiting for a kill, Away, Paused or Ended)."
	)
	default boolean showTaskOverlay()
	{
		return true;
	}

	/**
	 * The overlay's Progress and identity group. The key 1.x saved its "Show task row" under (the task name and
	 * kills left), kept so the player's choice carries over.
	 */
	String OVERLAY_PROGRESS = "overlayTask";

	@ConfigItem(
		keyName = OVERLAY_PROGRESS,
		name = "Progress and identity",
		position = 1,
		section = taskOverlaySection,
		description = "Show the task's icon and name, its spot and setup, the progress bar, and the kills left with the"
			+ " time left."
	)
	default boolean overlayProgress()
	{
		return true;
	}

	/** The overlay's Pace group. */
	String OVERLAY_PACE = "overlayPace";

	@ConfigItem(
		keyName = OVERLAY_PACE,
		name = "Pace",
		position = 2,
		section = taskOverlaySection,
		description = "Show the time per kill and kills per hour (after 3 kills and a minute), and the elapsed time."
	)
	default boolean overlayPace()
	{
		return true;
	}

	/**
	 * The overlay's Haul group. The key 1.x saved its "Show profit row" under, kept so the player's choice carries
	 * over.
	 */
	String OVERLAY_HAUL = "overlayProfit";

	@ConfigItem(
		keyName = OVERLAY_HAUL,
		name = "Haul",
		position = 3,
		section = taskOverlaySection,
		description = "Show the superiors, profit and XP, and one line for about 10 seconds when a superior spawns."
	)
	default boolean overlayHaul()
	{
		return true;
	}

	/** The overlay's Supply runway group. */
	String OVERLAY_RUNWAY = "overlayRunway";

	@ConfigItem(
		keyName = OVERLAY_RUNWAY,
		name = "Supply runway",
		position = 4,
		section = taskOverlaySection,
		description = "Warn when a supply you carry (prayer, restores, food, cannonballs, runes, ammo, darts or scales)"
			+ " will run out before the task ends: at this task's rate after 10 kills, before that at your completed"
			+ " tasks' rate."
	)
	default boolean overlayRunway()
	{
		return true;
	}

	@ConfigItem(
		keyName = "bonecrusherHandling",
		name = "Bonecrusher handling",
		position = 1,
		section = trackingSection,
		description = "Auto: leave bones out of kill loot while a bonecrusher or bonecrusher necklace is in your"
			+ " inventory or worn, and demonic ashes while an ash sanctifier is (charges are not checked)."
			+ " Always exclude / Never exclude: always leave bones and demonic ashes out, or always count them."
	)
	default BonecrusherMode bonecrusherHandling()
	{
		return BonecrusherMode.AUTO;
	}

	String IGNORED_LOOT = "ignoredLoot";

	@ConfigItem(
		keyName = IGNORED_LOOT,
		name = "Ignored loot",
		position = 2,
		section = trackingSection,
		description = "Items to leave out of kill loot, separated by commas: item names (not case-sensitive; * matches"
			+ " anything, for example *seed) or item ids. Noted drops match the unnoted item. Only loot received from"
			+ " now on is affected; saved tasks are unchanged."
	)
	default String ignoredLoot()
	{
		return "";
	}

	String LOOT_PRICE_MODE = "lootPriceMode";

	@ConfigItem(
		keyName = LOOT_PRICE_MODE,
		name = "Loot price",
		position = 0,
		section = valuesSection,
		description = "How loot is valued in the panel and the task history: GE price or high alch value. Supplies are"
			+ " always valued at GE prices. Profit follows this setting."
	)
	default LootPriceMode lootPriceMode()
	{
		return LootPriceMode.GE;
	}

	String NOTABLE_DROP_THRESHOLD = "notableDropThreshold";

	@ConfigItem(
		keyName = NOTABLE_DROP_THRESHOLD,
		name = "Notable drop value",
		position = 1,
		section = valuesSection,
		description = "A loot item stack from one kill worth at least this much, at the Loot price setting, is listed as"
			+ " a notable drop of the task. Pets are always listed. Only drops from now on are affected."
	)
	@Range(min = 1)
	@Units(" gp")
	default int notableDropThreshold()
	{
		return NotableDrops.DEFAULT_THRESHOLD;
	}

	String HIDE_GROUND_ITEMS_HIDDEN = "hideGroundItemsHidden";

	@ConfigItem(
		keyName = HIDE_GROUND_ITEMS_HIDDEN,
		name = "Hide Ground Items' hidden items",
		position = 2,
		section = valuesSection,
		description = "Leave the items on the Ground Items plugin's hidden list (while that plugin is on) out of the"
			+ " Loot received icons and, except coins, out of loot value and profit everywhere, saved tasks included."
			+ " Saved loot is not changed: turn this off, or edit the list, and the values follow."
	)
	default boolean hideGroundItemsHidden()
	{
		return true;
	}

	String VORKATHS_HEAD_UPGRADE = "vorkathsHeadUpgrade";

	@ConfigItem(
		keyName = VORKATHS_HEAD_UPGRADE,
		name = "Ranging/max cape has Vorkath's head upgrade",
		position = 0,
		section = chargedWeaponsSection,
		description = "Turn on if the ranging cape or max cape you wear has the Vorkath's head upgrade. Blowpipe darts"
			+ " are then counted as 80% saved with that cape worn, instead of 72%. Ava's devices are detected"
			+ " without this setting."
	)
	default boolean vorkathsHeadUpgrade()
	{
		return false;
	}

	String SHOW_OVERALL_TOTALS = "showOverallTotals";

	@ConfigItem(
		keyName = SHOW_OVERALL_TOTALS,
		name = "Overall totals",
		position = 2,
		section = panelSectionsSection,
		description = "Show the Overall totals section in the sidebar."
	)
	default boolean showOverallTotals()
	{
		return true;
	}

	String SHOW_SKIPPED_BLOCKED = "showSkippedBlocked";

	@ConfigItem(
		keyName = SHOW_SKIPPED_BLOCKED,
		name = "Skipped / blocked",
		position = 4,
		section = panelSectionsSection,
		description = "Show the Skipped / blocked section in the sidebar."
	)
	default boolean showSkippedBlocked()
	{
		return true;
	}
}
