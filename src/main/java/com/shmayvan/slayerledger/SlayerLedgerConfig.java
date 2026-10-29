package com.shmayvan.slayerledger;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

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
		description = "The in-game overlay showing the running or paused session's task.",
		position = 3
	)
	String taskOverlaySection = "taskOverlay";

	@ConfigSection(
		name = "Task advisor",
		description = "Consider skip / Consider block advice in the sidebar's task box, from your completed tasks.",
		position = 4
	)
	String taskAdvisorSection = "taskAdvisorSection";

	@ConfigSection(
		name = "Panel sections",
		description = "Choose which optional sections the Slayer Ledger sidebar shows.",
		position = 5
	)
	String panelSectionsSection = "panelSections";

	@ConfigSection(
		name = "Charged weapons",
		description = "How charged weapons' supplies are estimated.",
		position = 6
	)
	String chargedWeaponsSection = "chargedWeapons";

	@ConfigItem(
		keyName = "confirmIncomplete",
		name = "Confirm before saving incomplete tasks",
		position = 0,
		section = trackingSection,
		description = "When stopping a session before the task is complete, ask whether to keep it (saved as partial)"
			+ " or discard it. When off, incomplete sessions are kept as partial automatically."
	)
	default boolean confirmIncomplete()
	{
		return true;
	}

	@ConfigItem(
		keyName = "taskCompleteNotification",
		name = "Task-complete notification",
		position = 0,
		section = chatSection,
		description = "Show a RuneLite notification when a completed Slayer task is saved. The in-game chat summary is"
			+ " always shown."
	)
	default boolean taskCompleteNotification()
	{
		return false;
	}

	@ConfigItem(
		keyName = "assignmentPreview",
		name = "New-task chat preview",
		position = 1,
		section = chatSection,
		description = "When a new Slayer task is assigned, post one chat line with what your completed tasks of it took"
			+ " for the kills left: time, profit and bank trips. Nothing is posted without completed history."
	)
	default boolean assignmentPreview()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showTaskOverlay",
		name = "Show task overlay",
		position = 0,
		section = taskOverlaySection,
		description = "Show the Slayer Ledger overlay while a session is running or paused, and its Not tracking"
			+ " reminder after an untracked task kill."
	)
	default boolean showTaskOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "overlayTask",
		name = "Show task row",
		position = 1,
		section = taskOverlaySection,
		description = "Show the task name and remaining kills in the Slayer Ledger overlay."
	)
	default boolean overlayTask()
	{
		return true;
	}

	@ConfigItem(
		keyName = "overlayKills",
		name = "Show kills row",
		position = 2,
		section = taskOverlaySection,
		description = "Show the session's kills in the Slayer Ledger overlay."
	)
	default boolean overlayKills()
	{
		return true;
	}

	@ConfigItem(
		keyName = "overlayTimeLeft",
		name = "Show time left row",
		position = 3,
		section = taskOverlaySection,
		description = "Show the estimated time left in the Slayer Ledger overlay."
	)
	default boolean overlayTimeLeft()
	{
		return true;
	}

	@ConfigItem(
		keyName = "overlayProfit",
		name = "Show profit row",
		position = 4,
		section = taskOverlaySection,
		description = "Show the session's profit in the Slayer Ledger overlay."
	)
	default boolean overlayProfit()
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

	String RESET_LOCATION_SKIPS = "resetLocationSkips";

	@ConfigItem(
		keyName = RESET_LOCATION_SKIPS,
		name = "Ask again about skipped places",
		position = 3,
		section = trackingSection,
		description = "Tick while logged in to forget the places you chose Skip for in the Name this location box on"
			+ " this account, so kills there ask for a name again. It clears itself once done; the box shows unticked the next time the settings are opened."
	)
	default boolean resetLocationSkips()
	{
		return false;
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

	String SHOW_WHAT_TO_BRING = "showWhatToBring";

	@ConfigItem(
		keyName = SHOW_WHAT_TO_BRING,
		name = "What to bring",
		position = 0,
		section = panelSectionsSection,
		description = "Show the What to bring section in the sidebar."
	)
	default boolean showWhatToBring()
	{
		return true;
	}

	String SHOW_TRENDS = "showTrends";

	@ConfigItem(
		keyName = SHOW_TRENDS,
		name = "Trends",
		position = 1,
		section = panelSectionsSection,
		description = "Show the Trends section in the sidebar."
	)
	default boolean showTrends()
	{
		return true;
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

	String SHOW_LUCK_METER = "showLuckMeter";

	@ConfigItem(
		keyName = SHOW_LUCK_METER,
		name = "Luck meter",
		position = 3,
		section = panelSectionsSection,
		description = "Show the Luck meter section in the sidebar."
	)
	default boolean showLuckMeter()
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

	String TASK_ADVISOR = "taskAdvisor";

	@ConfigItem(
		keyName = TASK_ADVISOR,
		name = "Task advisor",
		position = 0,
		section = taskAdvisorSection,
		description = "Show advice for the current task in the sidebar's task box: Consider block when the monster's"
			+ " rate on the goal is in the bottom 10% of your monsters and it has 5+ tasks, Consider skip in the bottom"
			+ " 25%, otherwise Do. Only monsters with 3+ completed tasks and 30+ minutes count, and at least 6 must."
			+ " Boss tasks are ranked separately. Advice only."
	)
	default boolean taskAdvisor()
	{
		return true;
	}

	String TASK_ADVISOR_GOAL = "taskAdvisorGoal";

	@ConfigItem(
		keyName = TASK_ADVISOR_GOAL,
		name = "Task advisor goal",
		position = 1,
		section = taskAdvisorSection,
		description = "Which rate the task advisor compares, as the Trends list shows it: profit per hour (at the Loot"
			+ " price setting), Slayer XP per hour or Slayer points per hour."
	)
	default TaskAdvisorGoal taskAdvisorGoal()
	{
		return TaskAdvisorGoal.PROFIT;
	}
}
