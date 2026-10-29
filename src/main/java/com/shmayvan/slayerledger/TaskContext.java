package com.shmayvan.slayerledger;

import java.util.Objects;

/**
 * Who assigned a task and what completing it gave: the raw SLAYER_MASTER and SLAYER_AREA values, the area's name, and
 * (for a completed task) the Slayer points earned and the streak afterwards. Every part is {@code null} when unknown.
 * Immutable; pure (no RuneLite types). The task's {@link TaskType} is derived from it, see {@link #type(Integer)}.
 */
final class TaskContext
{
	static final TaskContext NONE = new TaskContext(null, null, null, null, null);

	private final Integer master;
	private final Integer area;
	private final String location;
	private final Integer pointsEarned;
	private final Integer taskStreak;

	private TaskContext(Integer master, Integer area, String location, Integer pointsEarned, Integer taskStreak)
	{
		this.master = master;
		this.area = area;
		this.location = location;
		this.pointsEarned = pointsEarned;
		this.taskStreak = taskStreak;
	}

	/**
	 * The assignment as read from the game.
	 *
	 * @param master   SLAYER_MASTER value; {@link SlayerMaster#NONE} or less is unknown
	 * @param area     SLAYER_AREA value (Konar's location); 0 or less is none
	 * @param location the area's name, or {@code null} when there is none or the lookup failed
	 */
	static TaskContext assignment(int master, int area, String location)
	{
		final boolean hasArea = area > 0;
		return of(master > SlayerMaster.NONE ? master : null, hasArea ? area : null,
			hasArea && location != null && !location.isEmpty() ? location : null, null, null);
	}

	/** A context from stored values ({@code null} for each unknown part); {@link #NONE} when all are unknown. */
	static TaskContext of(Integer master, Integer area, String location, Integer pointsEarned, Integer taskStreak)
	{
		if (master == null && area == null && location == null && pointsEarned == null && taskStreak == null)
		{
			return NONE;
		}
		return new TaskContext(master, area, location, pointsEarned, taskStreak);
	}

	/** This context with the completion's points earned and streak (each {@code null} when not trusted). */
	TaskContext withRewards(Integer newPointsEarned, Integer newTaskStreak)
	{
		return of(master, area, location, newPointsEarned, newTaskStreak);
	}

	/**
	 * This context with the assignment parts it doesn't know yet taken from {@code later}, a later reading of the same
	 * task's assignment: the master once its varbit has synced, the area, or the area's name once its lookup worked.
	 * Known parts are kept.
	 */
	TaskContext fillAssignment(TaskContext later)
	{
		if (later == null)
		{
			return this;
		}
		final Integer newMaster = master != null ? master : later.master;
		Integer newArea = area;
		String newLocation = location;
		if (area == null && later.area != null)
		{
			newArea = later.area;
			newLocation = later.location;
		}
		else if (area != null && location == null && area.equals(later.area))
		{
			newLocation = later.location;
		}
		return of(newMaster, newArea, newLocation, pointsEarned, taskStreak);
	}

	/** SLAYER_MASTER value, or {@code null} when unknown. */
	Integer getMaster()
	{
		return master;
	}

	/** SLAYER_AREA value, or {@code null} when the task has no area (or it is unknown). */
	Integer getArea()
	{
		return area;
	}

	/** The area's name, or {@code null}. */
	String getLocation()
	{
		return location;
	}

	/** Slayer points the completion gave, or {@code null} when unknown or not trusted. */
	Integer getPointsEarned()
	{
		return pointsEarned;
	}

	/** The streak the completion raised, as it stood afterwards, or {@code null} when unknown. */
	Integer getTaskStreak()
	{
		return taskStreak;
	}

	/** The task's type for the given creature key; see {@link TaskType#of}. */
	TaskType type(Integer creatureId)
	{
		return TaskType.of(master, creatureId);
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof TaskContext))
		{
			return false;
		}
		TaskContext other = (TaskContext) o;
		return Objects.equals(master, other.master) && Objects.equals(area, other.area)
			&& Objects.equals(location, other.location) && Objects.equals(pointsEarned, other.pointsEarned)
			&& Objects.equals(taskStreak, other.taskStreak);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(master, area, location, pointsEarned, taskStreak);
	}

	@Override
	public String toString()
	{
		return "TaskContext(master=" + master + ", area=" + area + ", location=" + location + ", pointsEarned="
			+ pointsEarned + ", taskStreak=" + taskStreak + ")";
	}
}
