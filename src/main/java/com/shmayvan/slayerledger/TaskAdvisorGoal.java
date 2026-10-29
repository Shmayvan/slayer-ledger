package com.shmayvan.slayerledger;

/** The "Task advisor goal" setting: which rate the task advisor ({@link TaskAdvisor}) compares. */
public enum TaskAdvisorGoal
{
	/** Loot in the "Loot price" mode minus supplies at GE, per hour. */
	PROFIT("Profit/h", TaskRanking.Metric.PROFIT),
	/** Slayer XP per hour. */
	SLAYER_XP("Slayer XP/h", TaskRanking.Metric.XP),
	/** Slayer points per hour, from tasks tracked from the start of the assignment, leaving out bonus (milestone) tasks. */
	POINTS("Points/h", TaskRanking.Metric.POINTS);

	private final String displayName;
	private final TaskRanking.Metric metric;

	TaskAdvisorGoal(String displayName, TaskRanking.Metric metric)
	{
		this.displayName = displayName;
		this.metric = metric;
	}

	/** The Trends metric with the same rules. */
	TaskRanking.Metric metric()
	{
		return metric;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
