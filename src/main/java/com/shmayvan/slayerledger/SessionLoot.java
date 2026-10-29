package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.Builder;

/**
 * What a session received and used: its loot, supplies (with their doses), notable drops and boss kill counts. Shared by
 * {@link SessionTracker.Summary}, {@link SessionSnapshot} and {@link LedgerRecord}: the persisted classes keep these as
 * flat fields of their own (their JSON must not change), and this class converts to and from them in one place.
 * Immutable; build one with {@link #builder()} or {@link #toBuilder()}. No RuneLite dependencies.
 */
public final class SessionLoot
{
	/** Nothing received or used. */
	static final SessionLoot EMPTY = builder().build();

	private final ItemTally loot;
	private final ItemTally supplies;
	private final Map<Integer, Integer> supplyDoses;
	private final List<NotableDrop> notableDrops;
	private final List<BossKillCount> bossKillCounts;

	/**
	 * Copies everything it is given, so later changes to the arguments don't reach it.
	 *
	 * @param loot           NPC loot, or {@code null} for none
	 * @param supplies       supplies used, potions in doses, or {@code null} for none
	 * @param supplyDoses    doses per item for potion ids in {@code supplies} (only positive entries are kept), or
	 *                       {@code null} for none
	 * @param notableDrops   notable drops in the order they arrived, or {@code null} for none
	 * @param bossKillCounts boss kill counts in the order first seen, or {@code null} for none
	 */
	@Builder(toBuilder = true)
	private SessionLoot(ItemTally loot, ItemTally supplies, Map<Integer, Integer> supplyDoses,
		List<NotableDrop> notableDrops, List<BossKillCount> bossKillCounts)
	{
		this.loot = loot == null ? new ItemTally() : new ItemTally(loot);
		this.supplies = supplies == null ? new ItemTally() : new ItemTally(supplies);
		final Map<Integer, Integer> doses = new TreeMap<>();
		SessionTracker.putDoses(doses, supplyDoses);
		this.supplyDoses = Collections.unmodifiableMap(doses);
		this.notableDrops = Collections.unmodifiableList(NotableDrops.sanitize(notableDrops));
		this.bossKillCounts = Collections.unmodifiableList(BossKillCounts.sanitize(bossKillCounts));
	}

	/** A copy of the loot (never {@code null}). */
	public ItemTally getLoot()
	{
		return new ItemTally(loot);
	}

	/** A copy of the supplies used, potions in doses (never {@code null}). */
	public ItemTally getSupplies()
	{
		return new ItemTally(supplies);
	}

	/** A copy of the doses per item for potion ids in {@link #getSupplies()} (never {@code null}). */
	public Map<Integer, Integer> getSupplyDoses()
	{
		return new TreeMap<>(supplyDoses);
	}

	/** Notable drops in the order they arrived (never {@code null}; unmodifiable). */
	public List<NotableDrop> getNotableDrops()
	{
		return notableDrops;
	}

	/** Boss kill counts in the order first seen (never {@code null}; unmodifiable). */
	public List<BossKillCount> getBossKillCounts()
	{
		return bossKillCounts;
	}

	/** A copy with extra loot (may be {@code null}) merged in and extra notable drops (likewise) added. */
	SessionLoot withAddedLoot(ItemTally extra, List<NotableDrop> extraNotableDrops)
	{
		final ItemTally merged = new ItemTally(loot);
		if (extra != null)
		{
			merged.merge(extra);
		}
		return toBuilder().loot(merged).notableDrops(NotableDrops.add(notableDrops, extraNotableDrops)).build();
	}

	/** A copy with one more boss kill count message counted ({@link BossKillCounts#add}). */
	SessionLoot withBossKillCount(String boss, int kc)
	{
		return toBuilder().bossKillCounts(BossKillCounts.add(bossKillCounts, boss, kc)).build();
	}

	/** The snapshot's loot as stored. */
	static SessionLoot from(SessionSnapshot snapshot)
	{
		return builder()
			.loot(ItemTally.fromMap(snapshot.getLoot()))
			.supplies(ItemTally.fromMap(snapshot.getSupplies()))
			.supplyDoses(snapshot.getSupplyDoses())
			.notableDrops(snapshot.getNotableDrops())
			.bossKillCounts(snapshot.getBossKillCounts())
			.build();
	}

	/** Sets the snapshot's loot, supplies, doses, notable drop and boss kill count fields. */
	void copyTo(SessionSnapshot snapshot)
	{
		snapshot.setLoot(loot.toMap());
		snapshot.setSupplies(supplies.toMap());
		snapshot.setSupplyDoses(dosesFor(snapshot.getSupplies(), supplyDoses));
		snapshot.setNotableDrops(notableDropsOrNull());
		snapshot.setBossKillCounts(bossKillCountsOrNull());
	}

	/** Sets the record's loot, supplies, doses, notable drop and boss kill count fields. */
	void copyTo(LedgerRecord record)
	{
		record.setLoot(loot.toMap());
		record.setSupplies(supplies.toMap());
		record.setSupplyDoses(dosesFor(record.getSupplies(), supplyDoses));
		record.setNotableDrops(notableDropsOrNull());
		record.setBossKillCounts(bossKillCountsOrNull());
	}

	/** The notable drops as stored, or {@code null} when there are none. */
	private List<NotableDrop> notableDropsOrNull()
	{
		return notableDrops.isEmpty() ? null : new ArrayList<>(notableDrops);
	}

	/** The boss kill counts as stored, or {@code null} when there are none. */
	private List<BossKillCount> bossKillCountsOrNull()
	{
		return bossKillCounts.isEmpty() ? null : new ArrayList<>(bossKillCounts);
	}

	/** The dose entries for ids present in {@code supplies}, or {@code null} when there are none. */
	static Map<Integer, Integer> dosesFor(Map<Integer, Long> supplies, Map<Integer, Integer> doses)
	{
		if (supplies == null || doses == null)
		{
			return null;
		}
		Map<Integer, Integer> result = new TreeMap<>();
		for (Map.Entry<Integer, Integer> e : doses.entrySet())
		{
			if (e.getKey() != null && e.getValue() != null && e.getValue() > 0 && supplies.containsKey(e.getKey()))
			{
				result.put(e.getKey(), e.getValue());
			}
		}
		return result.isEmpty() ? null : result;
	}
}
