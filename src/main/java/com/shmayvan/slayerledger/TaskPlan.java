package com.shmayvan.slayerledger;

import java.util.*;
import lombok.*;

/**
 * The advisor's plan for the current task (V2 plan 6), as {@link TaskPlanner} works it out from the player's
 * completed tasks: the options (the task's setups, grouped by spot, boss variant, cannon and style), the recommendation
 * and the creature-level "All setups" summary. Item names and spot names are not part of it: the plan is worked out off
 * the EDT and the names are asked for where it is shown. Immutable; pure (no RuneLite types).
 */
@Value
@Builder(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
class TaskPlan
{
	/** The task's name as given ({@link LedgerRecord#displayName}), or {@code null} when unknown. */
	String task;
	/**
	 * The task's creature key as asked for ({@link TaskPlanner.Request#getCreatureId}); with {@link #getTask},
	 * {@link #getTaskType} and {@link #getArea} the assignment the plan is for ({@link #isFor}).
	 */
	int creatureId;
	/** The task's type as asked for ({@link TaskPlanner.Request#getTaskType}), or {@code null} when unknown. */
	TaskType taskType;
	/** The name of the task's assigned area as asked for (Konar's, {@link TaskPlanner.Request#getArea}), or null. */
	String area;
	/**
	 * The kills the plan is for: the kills left; 0 or less when they are unknown ({@link #hasKillsLeft}), and the plan
	 * is then per kill.
	 */
	int killsLeft;
	/** Every completed task of the creature, the ones left out of the options included. */
	Summary allSetups;
	/**
	 * The options, fastest first ({@link TaskPlanner#ORDER}): every option, or on a task with an assigned area
	 * that names a spot ({@link #getAreaSpot}) the options at that spot, unless none is there
	 * ({@link #isAreaFallback}). Unmodifiable.
	 */
	List<Option> options;
	/** The creature's completed tasks in no option because their spot or style is Unknown. */
	int excludedTasks;
	/**
	 * The spot the task's assigned area names ({@link SlayerSpots#spotNamed}: Konar's area, where the kills must be
	 * made), which the options are limited to; {@code null} without an area or a spot of its name.
	 */
	String areaSpot;
	/**
	 * Whether no option is at {@link #getAreaSpot}, so the options are every option instead; noted, since the history
	 * then has no task done where the kills count.
	 */
	boolean areaFallback;
	/** The fastest option (see {@link TaskPlanner}), or {@code null} without options. */
	Option recommendation;
	/**
	 * Whether this is a Wilderness task's plan with no completed Wilderness task of the creature, though it has other
	 * completed tasks (which a Wilderness task doesn't use, {@link TaskPlanner}): the card says "no completed
	 * Wilderness tasks yet" ({@link TaskPlanText#noHistoryLine}). Only ever true for a plan without history ({@link #hasHistory}).
	 */
	boolean wildernessHistoryMissing;

	/**
	 * A plan for {@code request}'s assignment with no completed task of the creature: no options and an "All setups"
	 * of 0 tasks; {@code wildernessHistoryMissing} says whether it is a Wilderness task's whose creature has completed
	 * tasks, just none in the Wilderness ({@link #isWildernessHistoryMissing}).
	 */
	static TaskPlan empty(TaskPlanner.Request request, int killsLeft, boolean wildernessHistoryMissing)
	{
		return builder()
			.task(request.getTask())
			.creatureId(request.getCreatureId())
			.taskType(request.getTaskType())
			.area(request.getArea())
			.killsLeft(killsLeft)
			.allSetups(Summary.NONE)
			.options(Collections.emptyList())
			.recommendation(null)
			.wildernessHistoryMissing(wildernessHistoryMissing)
			.build();
	}

	/** A plan for {@code request}'s assignment; {@link TaskPlanner} makes the options list unmodifiable. */
	static TaskPlan of(TaskPlanner.Request request, int killsLeft, Summary allSetups, List<Option> options,
		int excludedTasks, String areaSpot, boolean areaFallback, Option recommendation)
	{
		return builder()
			.task(request.getTask())
			.creatureId(request.getCreatureId())
			.taskType(request.getTaskType())
			.area(request.getArea())
			.killsLeft(killsLeft)
			.allSetups(allSetups == null ? Summary.NONE : allSetups)
			.options(options)
			.excludedTasks(excludedTasks)
			.areaSpot(areaSpot)
			.areaFallback(areaFallback)
			.recommendation(recommendation)
			.build();
	}

	/**
	 * Whether the plan has any completed task of the creature to work from; {@code false} for the plan that says "no
	 * completed tasks yet". The kills left don't matter: unknown, the plan is per kill ({@link #hasKillsLeft}).
	 */
	boolean hasHistory()
	{
		return allSetups.getTasks() > 0;
	}

	/**
	 * Whether this is a plan of the assignment given: the same creature key, name, type and assigned area. Its kills
	 * left, records and prices may differ (the kept plan {@link LedgerViews#plan} shows while the current one is
	 * worked out).
	 */
	boolean isFor(int creatureId, String task, TaskType taskType, String area)
	{
		return this.creatureId == creatureId && Objects.equals(this.task, task) && this.taskType == taskType
			&& Objects.equals(this.area, area);
	}

	/**
	 * Whether the kills left are known (more than 0). Without them nothing is scaled to them: each {@link Time} is for
	 * one kill (the median per kill), and nothing is to bring.
	 */
	boolean hasKillsLeft()
	{
		return killsLeft > 0;
	}

	/**
	 * Whether the recommendation is based on fewer than {@link TaskPlanner#MIN_TASKS} tasks: no option had that many,
	 * so it is the option with the most ({@link TaskPlanText#basedOn}).
	 */
	boolean isRecommendationThin()
	{
		return recommendation != null && recommendation.getTasks() < TaskPlanner.MIN_TASKS;
	}

	/**
	 * The time for the kills left: the median time per kill of the tasks ({@link TaskPlanner#percentile}) times the
	 * kills left; for one kill when the kills left are unknown ({@link #hasKillsLeft}). Immutable.
	 */
	@Value
	static class Time
	{
		/** The median time per kill, rounded to the millisecond. */
		long perKillMillis;
		/** The median time per kill times the kills left. */
		long millis;
	}

	/** The creature-level "All setups" line: its completed tasks and their time. Immutable. */
	@Value
	static class Summary
	{
		/** No completed task. */
		static final Summary NONE = new Summary(0, null);

		/** The completed tasks: the sample size n. */
		int tasks;
		/** Their time for the kills left, or {@code null} without tasks. */
		Time time;
	}

	/**
	 * One thing to bring: a supply in whole items for the kills left (potions, multi-bite food and charged items in
	 * whole items, not doses, bites or charges), keyed by the item id as tallied; a potion kept under several ids (its
	 * family, {@link TaskPlanner.Request#getPotionFamilies}) by the one with the most doses. Immutable.
	 */
	@Value
	static class BringItem
	{
		int itemId;
		/** Whole items. */
		long quantity;
		/** The quantity at today's GE unit price, or {@code null} without one; for the order. */
		Long value;
	}

	/**
	 * One option: the completed tasks of the creature with the same spot, boss variant, effective cannon and effective
	 * style ({@link SetupTags}), and what they say about doing the kills left that way. Immutable; built by
	 * {@link TaskPlanner}.
	 */
	@Value
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	static class Option
	{
		/** The spot key ({@link SlayerSpots}: a table id or {@code region:N}); never the Unknown spot. */
		String spot;
		/**
		 * Whether the tasks' variant is the task's Slayer boss (most kills were the boss), for example the Grotesque
		 * Guardians on a gargoyle task.
		 */
		boolean boss;
		/** The effective cannon of the tasks. */
		boolean cannon;
		/** The effective style of the tasks; never {@link SetupStyle#UNKNOWN}. */
		SetupStyle style;
		/** Whether some task's cannon is an estimate (a migrated task). */
		boolean cannonEstimated;
		/** Whether some task's style is an estimate (a migrated task). */
		boolean styleEstimated;
		/** How many of the tasks had a kill of the task's Slayer boss ({@link TaskVariants#hasBossKills}); shown. */
		int bossKillTasks;
		/** The completed tasks: the sample size n. */
		int tasks;
		/** The time for the kills left. */
		Time time;
		/**
		 * What to bring, most valuable first: at most {@link TaskPlanner#MAX_BRING_ITEMS}; none when the kills left are
		 * unknown. Unmodifiable.
		 */
		List<BringItem> bring;
		/**
		 * The bring items past the first {@link TaskPlanner#MAX_BRING_ITEMS}, in the same order: "+N more", listed in
		 * its tooltip. Unmodifiable; empty unless there are more.
		 */
		@Builder.Default
		List<BringItem> bringRest = Collections.emptyList();

		/** How many bring items are past the first {@link TaskPlanner#MAX_BRING_ITEMS}: the N of "+N more". */
		int getBringMore()
		{
			return bringRest.size();
		}

		/** Whether the option carries the "estimated" mark: some task's cannon or style is an estimate. */
		boolean isEstimated()
		{
			return cannonEstimated || styleEstimated;
		}
	}
}
