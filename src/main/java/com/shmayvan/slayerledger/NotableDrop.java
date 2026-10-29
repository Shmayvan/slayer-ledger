package com.shmayvan.slayerledger;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * One notable drop of a task: a loot item stack worth at least the "Notable drop value" setting, or a pet. Its values
 * are kept as they were when it dropped, so the history doesn't depend on later prices. Serialized with Gson in
 * {@link LedgerRecord} and {@link SessionSnapshot}; effectively immutable (no setters). Pure (no RuneLite types). The
 * rules are in {@link NotableDrops}.
 */
@Getter
@EqualsAndHashCode
@ToString
final class NotableDrop
{
	/** What a pet message said. */
	enum Pet
	{
		/** A pet dropped (it follows you, or went to your inventory). */
		RECEIVED,
		/** A pet dropped that you already have, so you didn't receive it. */
		DUPLICATE
	}

	/** Item id as received (possibly noted), or {@code null} for a pet. */
	private Integer itemId;
	/** The item's name when it dropped, or {@code null} for a pet (or when the lookup failed). */
	private String name;
	/** Quantity of the stack; 1 for a pet. */
	private long quantity;
	/**
	 * Value of the stack at the GE price (an untradeable item at its high-alchemy value, as {@link LootPricing} does),
	 * or {@code null} when unknown (and for a pet).
	 */
	private Long geValue;
	/** Value of the stack at its high-alchemy value, or {@code null} when unknown (and for a pet). */
	private Long haValue;
	/** When it dropped (epoch milliseconds). */
	private long time;
	/** For a pet, what its message said; {@code null} for an item. */
	private Pet pet;

	/** For Gson. */
	private NotableDrop()
	{
	}

	private NotableDrop(Integer itemId, String name, long quantity, Long geValue, Long haValue, long time, Pet pet)
	{
		this.itemId = itemId;
		this.name = name;
		this.quantity = quantity;
		this.geValue = geValue;
		this.haValue = haValue;
		this.time = time;
		this.pet = pet;
	}

	/** An item stack worth {@code geValue} / {@code haValue} in all (each {@code null} when unknown). */
	static NotableDrop item(int itemId, String name, long quantity, Long geValue, Long haValue, long time)
	{
		return new NotableDrop(itemId, name, quantity, geValue, haValue, time, null);
	}

	/** A pet message seen at {@code time}. */
	static NotableDrop pet(Pet pet, long time)
	{
		return new NotableDrop(null, null, 1, null, null, time, pet == null ? Pet.RECEIVED : pet);
	}

	/** Whether this is a pet rather than an item stack. */
	boolean isPet()
	{
		return pet != null;
	}

	/** The stack's value in {@code mode} ({@code null} counts as GE), or {@code null} when unknown or a pet. */
	Long value(LootPriceMode mode)
	{
		return mode == LootPriceMode.HIGH_ALCH ? haValue : geValue;
	}
}
