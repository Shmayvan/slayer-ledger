package com.shmayvan.slayerledger;

import java.util.*;
import lombok.*;

/**
 * One saved Slayer task session. Serialized to JSON with Gson; optional fields can be added without breaking older
 * files because Gson ignores missing and unknown fields (and omits {@code null} fields when writing).
 *
 * <p>Schema history: version 1 had no item data. Version 2 adds the optional loot, supplies, unit-price and value
 * fields below; a version 1 record simply has them all {@code null}. The version was raised because an older build
 * would silently drop these fields when it rewrites the ledger file; see {@link LedgerStore#FILE_SCHEMA_VERSION}.
 * {@link #supplyDoses} was added to version 2 later, as an optional field, before any record with supplies existed;
 * version 2 records without supplies simply have it {@code null}. {@link #lootUnpricedItems} and
 * {@link #suppliesUnpricedItems} were added the same way; a record without them had every price available (or, if
 * saved by an earlier build, stored an unavailable GE price as 0). {@link #superiorsSpawned} and {@link #bankTrips} were
 * added the same way; records saved before they were counted have them {@code null}. {@link #note} was added the same way; older
 * records have none. {@link #deaths} was added the same way; records saved before deaths were counted have it
 * {@code null}. {@link #slayerMaster}, {@link #slayerArea}, {@link #taskLocation}, {@link #pointsEarned} and
 * {@link #taskStreak} were added the same way; older records have them {@code null} (unknown). {@link #notableDrops} was
 * added the same way; older records have it {@code null} and show no notable drops. Keys and clue scrolls are not
 * stored: they are derived from {@link #loot} ({@link KeysAndClues}). {@link #bossKillCounts} was added the same way;
 * older records have it {@code null} and show no boss kill count. {@link #xpGained} was added the same way; older
 * records have it {@code null} and show "-" for their XP. {@link #locationRegion} and {@link #location} were added the
 * same way; older records have them {@code null} and show "Unknown" for their location. {@link #variant} was added
 * the same way; older records have it {@code null}, so they count as the standard monster. Whether a task was
 * done with a cannon was not stored: 1.x derived it from {@link #supplies}; since version 3 it is the Cannon
 * tag ({@link #cannonTask}).</p>
 *
 * <p>Version 3 (2.0) adds the setup tags: the detected cannon, style and gear focus ({@link #detectedCannon},
 * {@link #detectedStyle}, {@link #detectedGear}), whether each is an estimate ({@link #cannonEstimated} and so on) and
 * the player's overrides ({@link #cannonOverride} and so on), read only through {@link SetupTags}. It also adds the
 * kills by NPC ({@link #npcKills}), the setup kill-context counts ({@link #cannonKills}, {@link #styleKills},
 * {@link #gearKills}, {@link #prayerBonusMin}, {@link #prayerBonusMax}; see {@link SetupKills}) and the spot
 * ({@link #spotId}). The version was raised because the setup tags change what a task's cannon means: a 1.x build
 * would ignore the player's overrides and classify the task by its own rule, so it must not write a 2.0 ledger. A
 * record below version 3 is migrated when it is read ({@link SetupMigration}): it gets estimated tags and version 3 in
 * memory, and keeps them once the file is next written.</p>
 *
 * <p>{@link #bossKills} was added to version 3 later, as an optional field, before any released build wrote
 * version 3. A 1.x record gets it when it is migrated ({@link SetupMigration}, {@link TaskVariants#migrate}), from its
 * {@link #npcKills} when it has any or else its {@link #bossKillCounts}, and keeps it once the file is next written.
 * Now {@link #variant} names any kind a task can be done on besides its own monsters ({@link TaskVariants}):
 * its Catacombs variant, as before, or its Slayer boss.</p>
 */
@Data
public class LedgerRecord
{
	public static final int CURRENT_SCHEMA_VERSION = 3;

	private int schemaVersion = CURRENT_SCHEMA_VERSION;
	private String id;
	private String monster;
	/**
	 * Slayer task creature key, or {@code null} when unknown: the SLAYER_TARGET varp value, or for boss tasks
	 * {@code -2 - bossId} (SLAYER_TARGET_BOSSID). See {@link SessionTracker#creatureKey(int, int)}.
	 */
	private Integer creatureId;
	/** Remaining task amount when the session started. */
	private int initialAmount;
	/** Assigned task size (SLAYER_COUNT_ORIGINAL plus any extension modifier), or {@code null} when unknown. */
	private Integer originalAmount;
	private int kills;
	private long activeMillis;
	private long startedAt;
	private long endedAt;
	private boolean completed;
	/** True when the player chose to keep an incomplete session. */
	private boolean partial;

	/** NPC loot received while the session was running: item id (as received, possibly noted) to quantity. */
	private Map<Integer, Long> loot;
	/**
	 * Supplies used while the session was running: item id to quantity, or {@code null} when none (and in older
	 * records). For a potion (an id in {@link #supplyDoses}) the quantity is in <em>doses</em>, keyed by the
	 * highest-dose variant of that potion seen since the plugin started. For a pizza, pie or cake (also an id in
	 * {@link #supplyDoses}) it is in <em>bites</em>, keyed by the whole item. For teleport jewellery or the fungicide
	 * spray (also an id in {@link #supplyDoses}) it is in <em>charges</em>, keyed by the highest-charge item; see
	 * {@link SupplyTracker}.
	 */
	private Map<Integer, Long> supplies;
	/**
	 * Doses (bites, charges) in one item, for every potion, multi-bite food and charged item id in {@link #supplies}:
	 * the supply quantity of that id counts doses, bites or charges, each valued at the id's unit price divided by this
	 * number. {@code null} when no potions, multi-bite food or charged items were used.
	 */
	private Map<Integer, Integer> supplyDoses;
	/**
	 * Grand Exchange unit price of every item in {@link #loot} and {@link #supplies}, captured when the record was
	 * saved. Noted items are priced as the unnoted item; coins at face value; untradeable items at 0. An item whose GE
	 * price was unavailable (not loaded yet, or a failed lookup) is left out; see {@link ItemValuation}.
	 * {@code null} when no prices were captured.
	 */
	private Map<Integer, Long> unitGePrices;
	/** High-alchemy unit value of every item in {@link #loot} and {@link #supplies}, captured with the GE prices. */
	private Map<Integer, Long> unitHaPrices;
	/**
	 * Total GE value of {@link #loot} at {@link #unitGePrices} as captured (an untradeable item at 0), or {@code null}
	 * when not valued (older records). The history shows a record with unit prices at {@link LootPricing}'s GE value
	 * instead, which counts a GE price of 0 at its high-alchemy value; see {@link HistoryPricing}.
	 */
	private Long lootGeValue;
	/** Total high-alchemy value of {@link #loot}, or {@code null} when not valued. */
	private Long lootHaValue;
	/**
	 * Total GE value of {@link #supplies} (potions per dose, see {@link #supplyDoses}), or {@code null} when not
	 * valued or no supplies were recorded.
	 */
	private Long suppliesGeValue;
	/** Total high-alchemy value of {@link #supplies}, or {@code null} when not valued or not recorded. */
	private Long suppliesHaValue;
	/**
	 * Number of distinct {@link #loot} items whose GE price was unavailable when valued (so {@link #lootGeValue} leaves
	 * them out), or {@code null} when there were none.
	 */
	private Integer lootUnpricedItems;
	/** Like {@link #lootUnpricedItems}, for {@link #supplies} and {@link #suppliesGeValue}. */
	private Integer suppliesUnpricedItems;
	/** Superior Slayer monster spawns seen while the session was running, or {@code null} when not counted. */
	private Integer superiorsSpawned;
	/** Bank trips made while the session was running (see {@link BankTripCounter}), or {@code null} when not counted. */
	private Integer bankTrips;
	/** Deaths of the local player while the session was running, or {@code null} when not counted. */
	private Integer deaths;
	/**
	 * The player's free-text note on the task (for example "Barrage"), {@link TaskNote#normalize normalized}, or
	 * {@code null} when none. Can be edited after saving.
	 */
	private String note;
	/**
	 * SLAYER_MASTER value of the master who assigned the task (see {@link SlayerMaster}), or {@code null} when unknown.
	 * The task's {@link TaskType} is derived from it and {@link #creatureId}; it is not stored.
	 */
	private Integer slayerMaster;
	/** SLAYER_AREA value (Konar's assigned area), or {@code null} when the task had none or it is unknown. */
	private Integer slayerArea;
	/** Name of {@link #slayerArea} from the game's SlayerArea table, or {@code null}. */
	private String taskLocation;
	/**
	 * Slayer points the completion gave (see {@link TaskRewards}), or {@code null} when the task wasn't completed, the
	 * change couldn't be trusted, or the record is older. Never negative.
	 */
	private Integer pointsEarned;
	/**
	 * The streak the completion raised (regular, Wilderness or Mortimer), as it stood afterwards, or {@code null} when
	 * unknown or not completed.
	 */
	private Integer taskStreak;
	/**
	 * Notable drops received while the session was running (see {@link NotableDrops}), in the order they arrived, with
	 * their values when they dropped; at most {@link NotableDrops#MAX_PER_TASK}. {@code null} when there were none (and
	 * in older records).
	 */
	private List<NotableDrop> notableDrops;
	/**
	 * Boss kill counts from the game's kill count messages while the session was running (see {@link BossKillCounts}),
	 * one per boss in the order first seen; at most {@link BossKillCounts#MAX_PER_TASK}. {@code null} when there were
	 * none (and in older records).
	 */
	private List<BossKillCount> bossKillCounts;
	/**
	 * XP gained per skill while the session was running (see {@link SessionTracker#observeXp}), keyed by the name of
	 * RuneLite's {@code Skill} constant (e.g. {@code "SLAYER"}). Empty when the session gained none; {@code null} in
	 * older records (shown as "-") and in a partial saved from a snapshot written before XP was tracked.
	 */
	private Map<String, Long> xpGained;
	/**
	 * The map region the task's location was decided from (see {@link LocationTally#decide}): the region with the
	 * most kills in the task's spot, or with no spot the region with the most kills of all (1.x saved the region with
	 * the most kills); {@code null} when no kill was tallied (and in older records).
	 */
	private Integer locationRegion;
	/**
	 * The task's own location name, which the history shows before any other ({@link TaskLocation#recordLocation}):
	 * since 2.0 only a name the player gave this task ("Edit location"); a 1.x record also kept the name its
	 * location had when it was saved (its assigned Konar or Wilderness area, else the player's name for
	 * {@link #locationRegion}). {@code null} when it has none. Can be edited after saving; never decides the spot.
	 */
	private String location;
	/**
	 * The task's Slayer spot (V2 plan 4.5): the bundled spot table's stable id ({@link SlayerSpots}), or
	 * {@code region:N} for a region the table doesn't list, decided from its kills when it was saved
	 * ({@link LocationTally#decide}); {@code null} when unknown, and in records saved before spots, whose spot is worked
	 * out from {@link #locationRegion} each time it is read ({@link SlayerSpots#recordSpot}).
	 */
	private String spotId;
	/**
	 * The kind the task was mostly done on besides its own monsters, by its name, decided from the session's kills per
	 * NPC name and its task kills when it was saved ({@link TaskVariants#decide}): its Catacombs of Kourend variant
	 * ({@link CatacombsVariants.Variant#getName}, for example "Deviant spectres") or,, its Slayer boss
	 * ({@link SlayerBosses.Boss#getName}, for example "Grotesque Guardians" on a gargoyle task); {@code null} for the
	 * standard monster (and in older records). The session's kills per NPC name are kept in {@link #npcKills} since
	 * version 3.
	 */
	private String variant;
	/**
	 * Whether the task's kills include its Slayer boss (V2 plan 4.4, {@link TaskVariants#bossKills}), for example
	 * a Grotesque Guardians kill on a gargoyle task, however few; {@code false} for a boss task (its own pool) or a task
	 * without a Slayer boss. {@code null} only in a record built without it (none is saved so, and a migrated 1.x
	 * record gets it), which reads as no boss kills.
	 */
	private Boolean bossKills;
	/**
	 * The session's kills per NPC name, in the order first seen ({@link NpcKillTally}, kept since version 3), or {@code null} when none (and in older records).
	 */
	private List<NpcKills> npcKills;
	/**
	 * Task kills made while the cannon was firing ({@link SetupKills}), or {@code null} when no kill context was
	 * counted (older records).
	 */
	private Integer cannonKills;
	/** Task kills per style ({@link SetupStyle} name), or {@code null} when none were counted. */
	private Map<String, Integer> styleKills;
	/** Task kills per gear focus ({@link GearFocus} name), or {@code null} when none were counted. */
	private Map<String, Integer> gearKills;
	/** The lowest worn prayer bonus seen at a task kill, or {@code null} when none was read. */
	private Integer prayerBonusMin;
	/** The highest worn prayer bonus seen at a task kill, or {@code null} when none was read. */
	private Integer prayerBonusMax;
	/**
	 * The detected cannon tag (V2 plan 4.3): whether the task was done with a cannon, as detected (or, for a migrated
	 * record, estimated from its cannonballs); {@code null} only in a record built without tags. Read the effective
	 * value through {@link SetupTags}, never this field.
	 */
	private Boolean detectedCannon;
	/** {@code true} when {@link #detectedCannon} is an estimate (a migrated record); {@code null} otherwise. */
	private Boolean cannonEstimated;
	/** The player's cannon override, or {@code null} for none (see {@link SetupOverrides}). Can be edited after saving. */
	private Boolean cannonOverride;
	/** The detected (or, migrated, estimated) style tag; {@code null} reads as {@link SetupStyle#UNKNOWN}. */
	private SetupStyle detectedStyle;
	/** {@code true} when {@link #detectedStyle} is an estimate (a migrated record); {@code null} otherwise. */
	private Boolean styleEstimated;
	/** The player's style override, or {@code null} for none. Can be edited after saving. */
	private SetupStyle styleOverride;
	/** The detected gear focus tag; {@code null} reads as {@link GearFocus#UNKNOWN}. */
	private GearFocus detectedGear;
	/** {@code true} when {@link #detectedGear} is an estimate; {@code null} otherwise (migrated records' gear is Unknown). */
	private Boolean gearEstimated;
	/** The player's gear focus override, or {@code null} for none. Can be edited after saving. */
	private GearFocus gearOverride;

	/**
	 * The name the record is shown and grouped under: its {@link #versionName}, with
	 * {@link CannonTasks#SUFFIX} for a {@link #cannonTask cannon task}, for example "Deviant spectres" or
	 * "Dust devils (cannon)". The history list and its Monster filter, the task-complete chat line and Overall totals By
	 * monster group by it (with {@link TaskStatistics#normalize}). The advisor's main advice and the Skipped / blocked
	 * rows pool every version
	 * and cannon use by task, the 2.0 advisor's plan ({@link TaskPlanner}) pools every version in options split by
	 * cannon, and {@link TaskStatistics} filters by creature plus a
	 * {@link TaskSelection}.
	 */
	String groupName()
	{
		return CannonTasks.name(versionName(), cannonTask());
	}

	/**
	 * Its {@link #variant} when it has one (its Catacombs variant or Slayer boss), else its
	 * {@link #monster}; without the cannon suffix.
	 */
	String versionName()
	{
		return variant != null && !variant.trim().isEmpty() ? variant.trim() : monster;
	}

	/**
	 * Whether the task counts as a cannon task: its effective Cannon tag ({@link SetupTags#of}: the
	 * player's override, else the detected value), so the name, the Monster filter and the Cannon filter agree. A
	 * record without a stored tag (none read through {@link LedgerStore}) falls back to its fired cannonballs
	 * ({@link CannonTasks#fired}), as 1.x classified it.
	 */
	boolean cannonTask()
	{
		return SetupTags.of(this).isCannon();
	}

	/**
	 * Name to show for a task, falling back to "Unknown task (id)" when the name lookup failed.
	 */
	static String displayName(String monster, int creatureId)
	{
		if (monster != null)
		{
			return monster;
		}
		if (creatureId == SessionTracker.NO_CREATURE)
		{
			return "Unknown task";
		}
		if (creatureId < SessionTracker.NO_CREATURE)
		{
			return "Unknown boss task (" + (-2 - creatureId) + ")";
		}
		return "Unknown task (" + creatureId + ")";
	}

	static LedgerRecord fromSummary(SessionTracker.Summary summary, boolean partial)
	{
		LedgerRecord record = new LedgerRecord();
		// The session id becomes the record id, so appending the same session twice is detectable.
		record.setId(summary.getSessionId() != null ? summary.getSessionId() : UUID.randomUUID().toString());
		record.setMonster(displayName(summary.getMonster(), summary.getCreatureId()));
		record.setCreatureId(summary.getCreatureId() == SessionTracker.NO_CREATURE ? null : summary.getCreatureId());
		record.setInitialAmount(summary.getInitialAmount());
		record.setOriginalAmount(summary.getOriginalAmount() == SessionTracker.UNKNOWN_AMOUNT ? null : summary.getOriginalAmount());
		record.setKills(summary.getKills());
		record.setActiveMillis(summary.getActiveMillis());
		record.setStartedAt(summary.getStartedAt());
		record.setEndedAt(summary.getEndedAt());
		record.setCompleted(summary.isCompleted());
		record.setPartial(partial);
		summary.getSessionLoot().copyTo(record);
		summary.getCounters().copyTo(record);
		record.setNote(summary.getNote());
		final TaskContext context = summary.getContext();
		record.setSlayerMaster(context.getMaster());
		record.setSlayerArea(context.getArea());
		record.setTaskLocation(context.getLocation());
		// Points and streak describe a completion; an incomplete session never has them.
		record.setPointsEarned(summary.isCompleted() ? context.getPointsEarned() : null);
		record.setTaskStreak(summary.isCompleted() ? context.getTaskStreak() : null);
		record.setXpGained(xpGainedFor(summary));
		// The spot its kills decided. No location name is saved: the history names it from the spot, the region's
		// name and the player's own name for this task.
		final TaskSpot spot = summary.getTaskSpot();
		record.setLocationRegion(spot.getRegion());
		record.setSpotId(spot.getSpotId());
		record.setVariant(TaskVariants.decide(summary.getMonster(), summary.getCreatureId(), summary.getNpcKills(),
			summary.getKills()).getVariant());
		record.setNpcKills(summary.getNpcKills().isEmpty() ? null : new ArrayList<>(summary.getNpcKills()));
		// From the kills per NPC name and the boss kill counts just copied.
		record.setBossKills(TaskVariants.bossKills(record));
		summary.getSetupKills().copyTo(record);
		summary.getSetupTags().copyTo(record);
		return record;
	}

	/** The summary's XP gained per skill as stored (possibly empty), or {@code null} when unknown. */
	static Map<String, Long> xpGainedFor(SessionTracker.Summary summary)
	{
		final Map<String, Long> xp = summary.getXpGained();
		return xp == null ? null : new TreeMap<>(xp);
	}

	/**
	 * Captures unit prices for the record's loot (and supplies, when present) and sets the value totals. Called once,
	 * when the record is saved, on whatever thread the lookup requires (the plugin's lookup uses the client thread).
	 * Loot totals are set even when no loot was received (0), so a valued record can be told apart from an older one.
	 * Unit prices are per item; potion supplies are valued per dose (unit price / {@link #supplyDoses}).
	 * Supply totals stay {@code null} when no supplies were recorded. Items whose GE price was unavailable are left out
	 * of {@link #unitGePrices} and the GE totals, and counted in {@link #lootUnpricedItems} /
	 * {@link #suppliesUnpricedItems}.
	 */
	void captureValues(ItemValuation.PriceLookup lookup)
	{
		ItemTally lootTally = ItemTally.fromMap(loot);
		ItemTally suppliesTally = supplies == null ? null : ItemTally.fromMap(supplies);
		ItemValuation.UnitPrices prices = new ItemValuation.UnitPrices();
		ItemValuation.capturePrices(lootTally, lookup, prices);
		ItemValuation.capturePrices(suppliesTally, lookup, prices);
		unitGePrices = prices.getGe().isEmpty() ? null : prices.getGe();
		unitHaPrices = prices.getHa().isEmpty() ? null : prices.getHa();
		lootGeValue = ItemValuation.total(lootTally, prices.getGe());
		lootHaValue = ItemValuation.total(lootTally, prices.getHa());
		if (suppliesTally != null)
		{
			suppliesGeValue = ItemValuation.total(suppliesTally, prices.getGe(), supplyDoses);
			suppliesHaValue = ItemValuation.total(suppliesTally, prices.getHa(), supplyDoses);
		}
		lootUnpricedItems = nullIfZero(ItemValuation.countUnpriced(lootTally, prices.getGe()));
		suppliesUnpricedItems = nullIfZero(ItemValuation.countUnpriced(suppliesTally, prices.getGe()));
	}

	/**
	 * Adds loot that came after the record was saved (Araxxor's harvest after the task-completing kill), as if it
	 * had come before the save. The items join {@link #loot} and its notable stacks join {@link #notableDrops}
	 * ({@link NotableDrops#add}: the most valuable are kept). A valued record ({@link LedgerHistory#isValued}) also takes
	 * the new items' unit prices, captured when the loot came ({@code prices}, by {@link #captureValues}' rules), where it
	 * has none of its own (an item it already priced keeps its saved price), and its loot totals and unpriced count are
	 * worked out again from them, as {@link #captureValues} works them out. Supplies are unchanged. A record saved
	 * without values gets the items and notable drops, no prices.
	 *
	 * @param items  the loot
	 * @param prices the loot's unit prices when it came, or {@code null} when it could not be priced
	 * @param drops  the loot's notable stacks, or {@code null} when none
	 * @return whether the record changed: {@code false} only for no loot
	 */
	boolean addLateLoot(ItemTally items, ItemValuation.UnitPrices prices, List<NotableDrop> drops)
	{
		if (items == null || items.isEmpty())
		{
			return false;
		}
		final ItemTally lootTally = ItemTally.fromMap(loot);
		lootTally.merge(items);
		loot = lootTally.toMap();
		final List<NotableDrop> notable = NotableDrops.add(notableDrops, drops);
		notableDrops = notable.isEmpty() ? null : notable;
		if (lootGeValue == null)
		{
			return true;
		}
		final Map<Integer, Long> ge = unitGePrices == null ? new TreeMap<>() : new TreeMap<>(unitGePrices);
		final Map<Integer, Long> ha = unitHaPrices == null ? new TreeMap<>() : new TreeMap<>(unitHaPrices);
		if (prices != null)
		{
			for (int id : items.itemIds())
			{
				final Long gePrice = prices.getGe().get(id);
				if (gePrice != null)
				{
					ge.putIfAbsent(id, gePrice);
				}
				final Long haPrice = prices.getHa().get(id);
				if (haPrice != null)
				{
					ha.putIfAbsent(id, haPrice);
				}
			}
		}
		unitGePrices = ge.isEmpty() ? null : ge;
		unitHaPrices = ha.isEmpty() ? null : ha;
		lootGeValue = ItemValuation.total(lootTally, unitGePrices);
		lootHaValue = ItemValuation.total(lootTally, unitHaPrices);
		lootUnpricedItems = nullIfZero(ItemValuation.countUnpriced(lootTally, unitGePrices));
		return true;
	}

	private static Integer nullIfZero(int n)
	{
		return n == 0 ? null : n;
	}
}
