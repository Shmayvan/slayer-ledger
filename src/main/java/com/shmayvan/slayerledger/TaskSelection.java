package com.shmayvan.slayerledger;

import java.util.Objects;

/**
 * Which of a task's records the current task's statistics use, on two independent dimensions:
 * <ul>
 * <li>the version: any (the standard monster and its Catacombs variant pooled, as before variants were told
 * apart), only the standard monster's, or only one variant's;</li>
 * <li>the cannon: any (tasks with and without a cannon pooled), only cannon tasks, or only tasks without one
 * ({@link CannonTasks}).</li>
 * </ul>
 * Immutable; compares by value so it can be part of a cache key. Pure (no RuneLite types).
 */
final class TaskSelection
{
	/** Every record of the task, whichever version and with or without a cannon. */
	static final TaskSelection ANY = new TaskSelection(true, null, null);
	/** Only records of the standard monster (no {@link LedgerRecord#getVariant variant}), with or without a cannon. */
	static final TaskSelection STANDARD = new TaskSelection(false, null, null);

	private final boolean anyVariant;
	/** The variant's name ({@link CatacombsVariants.Variant#getName}), or {@code null} for any and standard. */
	private final String variant;
	/** {@code true} for cannon tasks only, {@code false} for tasks without a cannon only, {@code null} for both. */
	private final Boolean cannon;

	private TaskSelection(boolean anyVariant, String variant, Boolean cannon)
	{
		this.anyVariant = anyVariant;
		this.variant = variant;
		this.cannon = cannon;
	}

	/** Only the records of the variant {@code name} (with or without a cannon); {@link #STANDARD} for a blank name. */
	static TaskSelection of(String name)
	{
		return TaskStatistics.normalize(name) == null ? STANDARD : new TaskSelection(false, name.trim(), null);
	}

	/**
	 * This selection, limited to cannon tasks ({@code true}), tasks without a cannon ({@code false}) or both
	 * ({@code null}). Without a cannon limit, {@link #ANY} and {@link #STANDARD} stay the same instances.
	 */
	TaskSelection withCannon(Boolean cannon)
	{
		if (Objects.equals(cannon, this.cannon))
		{
			return this;
		}
		if (cannon == null && variant == null)
		{
			return anyVariant ? ANY : STANDARD;
		}
		return new TaskSelection(anyVariant, variant, cannon);
	}

	/** This selection with the version pooled ({@link #ANY}'s), keeping its cannon limit. */
	TaskSelection withAnyVariant()
	{
		return anyVariant ? this : ANY.withCannon(cannon);
	}

	/**
	 * This selection for the task {@code taskName}: kept when its variant is that task's Catacombs variant (or it has
	 * none), else with the version pooled ({@link #withAnyVariant}). Guards a selection read in a separate call from the
	 * task it is shown with, which could see another session (the overlay's Task row).
	 */
	TaskSelection forTask(String taskName)
	{
		if (variant == null)
		{
			return this;
		}
		final CatacombsVariants.Variant taskVariant = CatacombsVariants.forTask(taskName);
		return taskVariant != null && variant.equals(taskVariant.getName()) ? this : withAnyVariant();
	}

	/** The variant's name, or {@code null} when the version is any or standard. */
	String getVariant()
	{
		return variant;
	}

	/** Whether the version is pooled (standard and variant alike). */
	boolean isAnyVariant()
	{
		return anyVariant;
	}

	/** {@code true} for cannon tasks only, {@code false} for tasks without a cannon only, {@code null} for both. */
	Boolean getCannon()
	{
		return cannon;
	}

	/** Whether {@code record}'s version and cannon use are ones this selects (its task is matched elsewhere). */
	boolean matches(LedgerRecord record)
	{
		if (cannon != null && cannon != record.cannonTask())
		{
			return false;
		}
		if (anyVariant)
		{
			return true;
		}
		return Objects.equals(TaskStatistics.normalize(variant), TaskStatistics.normalize(record.getVariant()));
	}

	/**
	 * The name the live task is shown under with this selection, the same way {@link LedgerRecord#groupName} names a
	 * saved one: the variant's name when there is one, else {@code task}, with {@link CannonTasks#SUFFIX} for cannon
	 * tasks only. A pooled cannon dimension adds nothing.
	 */
	String displayName(String task)
	{
		return CannonTasks.name(variant != null ? variant : task, Boolean.TRUE.equals(cannon));
	}

	/**
	 * A short key for caches: the version ({@code *} for any, empty for standard, else the normalized name), then
	 * {@code /} and the cannon ({@code *} for both, {@code c} with, {@code n} without).
	 */
	String key()
	{
		String version;
		if (anyVariant)
		{
			version = "*";
		}
		else
		{
			String normalized = TaskStatistics.normalize(variant);
			version = normalized == null ? "" : normalized;
		}
		return version + "/" + (cannon == null ? "*" : cannon ? "c" : "n");
	}

	@Override
	public boolean equals(Object other)
	{
		return other instanceof TaskSelection && key().equals(((TaskSelection) other).key());
	}

	@Override
	public int hashCode()
	{
		return key().hashCode();
	}

	@Override
	public String toString()
	{
		String version = anyVariant ? "ANY" : variant == null ? "STANDARD" : variant;
		return cannon == null ? version : version + (cannon ? " (cannon)" : " (no cannon)");
	}
}
