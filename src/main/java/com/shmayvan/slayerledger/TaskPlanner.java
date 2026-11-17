package com.shmayvan.slayerledger;

import java.math.*;
import java.util.*;
import lombok.*;

/**
 * The advisor's planner (V2 plan 5 and 6): from the player's completed tasks of the current task's creature, how
 * to do the kills left, how long it takes and what to bring. Pure: no client, Swing or file access, so the plugin can
 * work it out off the EDT from an immutable snapshot ({@link Request}).
 *
 * <p><b>Pool.</b> The samples are the records that are completed, not partial, with kills and active time
 * ({@link TaskStatistics#isTaskSample}) and of the current task's creature ({@link TaskStatistics#isSample}: by
 * creature key, or by name for a record or task without one), every version pooled. Boss tasks are their own pool: a
 * boss task's plan uses only boss-task records, any other task's only the others. A Wilderness task's (Krystilia's)
 * plan uses only Wilderness tasks ({@link TaskType#of} the record's master and creature key), since its kills count
 * only in the Wilderness.</p>
 *
 * <p><b>Options.</b> The samples are grouped by spot ({@link SlayerSpots#recordSpot}), whether their variant is the
 * task's Slayer boss (most kills were the boss: {@link LedgerRecord#getVariant} names the boss of
 * {@link TaskVariants#bossName}), the effective cannon and the effective style ({@link SetupTags#of}). An estimated
 * value groups under its own value, and the option carries the estimated mark. A sample with an Unknown spot or style
 * is in no option; it counts only in the "All setups" summary. The boss flag ({@link TaskVariants#hasBossKills}) is
 * reported per option and doesn't split them. A task with an assigned area (Konar's, whose kills
 * count only there) keeps only the options at the spot the area names ({@link SlayerSpots#spotNamed}); with none
 * there it keeps them all and the plan notes it ({@link TaskPlan#isAreaFallback}), and an area naming no spot keeps
 * them all.</p>
 *
 * <p><b>Each option</b> has its sample size n; the time for the kills left (the median time per kill of its tasks
 * times the kills left: {@link #percentile}); and what to bring: per item, the {@value #BRING_PERCENTILE}th percentile
 * of its use per kill over the tasks with supply data ({@link #hasSupplyData}; a task that didn't use it counts 0),
 * times the kills left, rounded up to whole items, potions from doses, multi-bite food from bites and charged items
 * from charges ({@link LedgerRecord#getSupplyDoses}), a potion's ids of one family as one item
 * ({@link Request#getPotionFamilies}).
 * The items are sorted most valuable first (at today's GE price; unpriced last, then by amount and id) and cut to
 * {@value #MAX_BRING_ITEMS}, with the rest kept apart ({@link TaskPlan.Option#getBringRest}). With the kills left
 * unknown (0 or less) nothing is scaled to them: the times are for one kill and nothing is to bring
 * ({@link TaskPlan#hasKillsLeft}).</p>
 *
 * <p><b>Recommendation.</b> The fastest option ({@link #ORDER}: least time for the kills left) among those with at
 * least {@value #MIN_TASKS} tasks; with none, the option with the most tasks, ties broken by time, which the plan marks
 * thin ({@link TaskPlan#isRecommendationThin}). With no samples the plan is {@link TaskPlan#empty}, which for a
 * Wilderness task notes when the creature has other completed tasks ({@link TaskPlan#isWildernessHistoryMissing}).</p>
 */
final class TaskPlanner
{
	/** Tasks an option needs to be chosen on its time alone. */
	static final int MIN_TASKS = 3;
	/** Bring items a plan shows before "+N more". */
	static final int MAX_BRING_ITEMS = 8;
	/** The percentile of the use per kill each bring amount is from: a safe amount, not an average one. */
	static final int BRING_PERCENTILE = 80;

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
	/**
	 * Below any fraction of an item the records can give, above the rounding of {@link MathContext#DECIMAL128}: a whole
	 * amount worked out as 71.000...01 is 71, not 72.
	 */
	private static final BigDecimal ROUNDING_SLACK = new BigDecimal("1e-20");
	private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);

	/** The options' last tie-break, so the order never depends on the records' order: spot, boss, cannon, style. */
	private static final Comparator<TaskPlan.Option> KEY_ORDER = Comparator
		.comparing(TaskPlan.Option::getSpot)
		.thenComparing(TaskPlan.Option::isBoss)
		.thenComparing(TaskPlan.Option::isCannon)
		.thenComparing(TaskPlan.Option::getStyle);

	/**
	 * The options' order: least time for the kills left first (Fastest finish); ties by more tasks, then the option's
	 * key.
	 */
	static final Comparator<TaskPlan.Option> ORDER = Comparator
		.<TaskPlan.Option>comparingLong(option -> option.getTime().getMillis())
		.thenComparing(Comparator.comparingInt(TaskPlan.Option::getTasks).reversed())
		.thenComparing(KEY_ORDER);

	private TaskPlanner()
	{
	}

	/**
	 * What the planner works from, captured where the plan is asked for: the current task, the kills left, the records
	 * (not changed by the planner) and today's prices, as {@link LedgerViews}' background views capture them.
	 * Immutable once built (the records collection and the prices map must not change while the plan is worked out).
	 * Set the creature key: the builder's default, 0, is a creature.
	 */
	@Value
	@Builder
	static class Request
	{
		/**
		 * The task's creature key ({@link SessionTracker#creatureKey}; boss tasks below
		 * {@link SessionTracker#NO_CREATURE}), or {@link SessionTracker#NO_CREATURE} to match by name.
		 */
		int creatureId;
		/** The task's name. */
		String task;
		/**
		 * The task's type, or {@code null} when unknown; {@link TaskType#BOSS} picks the boss pool and
		 * {@link TaskType#WILDERNESS} only Wilderness tasks.
		 */
		TaskType taskType;
		/**
		 * The kills left; 0 or less when unknown ({@link SessionTracker#UNKNOWN_AMOUNT}), and the plan is then per kill
		 * ({@link TaskPlan#hasKillsLeft}).
		 */
		int killsLeft;
		/**
		 * The name of the task's assigned area (Konar's, {@link TaskContext#getLocation}), whose spot the options are
		 * limited to; {@code null} for none.
		 */
		String area;
		Collection<LedgerRecord> records;
		/** Today's GE unit prices ({@link HistoryPricing}), which value the bring items; {@code null} is none. */
		Map<Integer, Long> prices;
		/** The spot table; {@code null} is {@link SlayerSpots#NONE}. */
		SlayerSpots spots;
		/**
		 * Each potion id's family, its name without the dose suffix ({@link #potionFamilies}), so a potion a record
		 * keeps under two ids (its key moved to a higher-dose variant mid-session, {@link SupplyTracker}) is one bring
		 * item; {@code null} is none, every id on its own.
		 */
		Map<Integer, String> potionFamilies;
	}

	/** The plan for {@code request} (see the class rules); {@link TaskPlan#empty} with no samples. */
	static TaskPlan plan(Request request)
	{
		final int kills = request.getKillsLeft();
		final boolean wilderness = request.getTaskType() == TaskType.WILDERNESS;
		final List<LedgerRecord> samples = samples(request, wilderness);
		if (samples.isEmpty())
		{
			// A Wilderness task whose creature has only other tasks: said so, not "no completed tasks".
			return TaskPlan.empty(request, kills, wilderness && !samples(request, false).isEmpty());
		}
		// With the kills left unknown, the times are for one kill.
		final int scaleKills = kills > 0 ? kills : 1;
		final SlayerSpots spots = request.getSpots() == null ? SlayerSpots.NONE : request.getSpots();
		final Map<Integer, Long> prices = request.getPrices() == null ? Collections.emptyMap() : request.getPrices();

		final Map<OptionKey, List<LedgerRecord>> groups = new LinkedHashMap<>();
		int excluded = 0;
		for (LedgerRecord record : samples)
		{
			final String spot = spots.recordSpot(record);
			final SetupTags tags = SetupTags.of(record);
			if (spot == null || tags.getStyle() == SetupStyle.UNKNOWN)
			{
				// Counted in All setups only (V2 plan 5).
				excluded++;
				continue;
			}
			groups.computeIfAbsent(new OptionKey(spot, isBossVariant(record), tags.isCannon(), tags.getStyle()),
				key -> new ArrayList<>()).add(record);
		}

		List<TaskPlan.Option> options = new ArrayList<>();
		for (Map.Entry<OptionKey, List<LedgerRecord>> group : groups.entrySet())
		{
			options.add(option(group.getKey(), group.getValue(), kills, request, prices));
		}
		options.sort(ORDER);

		// An assigned area (Konar's): its kills count only there, so only the options at its spot, when there are any.
		final String areaSpot = areaSpot(request.getArea(), spots);
		boolean areaFallback = false;
		if (areaSpot != null)
		{
			final List<TaskPlan.Option> there = new ArrayList<>();
			for (TaskPlan.Option option : options)
			{
				if (areaSpot.equals(option.getSpot()))
				{
					there.add(option);
				}
			}
			if (there.isEmpty())
			{
				areaFallback = !options.isEmpty();
			}
			else
			{
				options = there;
			}
		}

		return TaskPlan.of(request, kills, new TaskPlan.Summary(samples.size(), time(samples, scaleKills)),
			Collections.unmodifiableList(options), excluded, areaSpot, areaFallback, pick(options));
	}

	/**
	 * The spot assigned area {@code area} names ({@link SlayerSpots#spotNamed}), or {@code null} for no area or no spot
	 * of its name.
	 */
	private static String areaSpot(String area, SlayerSpots spots)
	{
		return area == null || area.trim().isEmpty() ? null : spots.spotNamed(area);
	}

	/**
	 * Whether {@code record} says what supplies it used: it recorded some, or it was saved by 2.0, which stores
	 * none when nothing was used. A 1.x record without supplies is unknown, since 1.x also saved none when its supply
	 * counting had stopped, and that empty list is what made such a task's Magic XP Barrage when it was migrated
	 * ({@link SetupMigration#barrageRunes}). A migrated record's schema version reads 3 in memory and once the file is
	 * next written, so it is told by its estimated cannon tag, which only {@link SetupMigration} sets.
	 */
	static boolean hasSupplyData(LedgerRecord record)
	{
		if (record.getSupplies() != null)
		{
			return true;
		}
		return record.getSchemaVersion() >= SetupMigration.MIGRATED_VERSION
			&& !Boolean.TRUE.equals(record.getCannonEstimated());
	}

	/**
	 * The best of {@code options} in {@link #ORDER} among those with at least {@link #MIN_TASKS} tasks; with none, the
	 * one with the most tasks, ties in {@link #ORDER}. {@code null} for no options.
	 */
	private static TaskPlan.Option pick(List<TaskPlan.Option> options)
	{
		TaskPlan.Option best = null;
		for (TaskPlan.Option option : options)
		{
			if (option.getTasks() >= MIN_TASKS && (best == null || ORDER.compare(option, best) < 0))
			{
				best = option;
			}
		}
		if (best != null)
		{
			return best;
		}
		final Comparator<TaskPlan.Option> mostTasks = Comparator.comparingInt(TaskPlan.Option::getTasks).reversed()
			.thenComparing(ORDER);
		for (TaskPlan.Option option : options)
		{
			if (best == null || mostTasks.compare(option, best) < 0)
			{
				best = option;
			}
		}
		return best;
	}

	/**
	 * The request's samples (see the class rules), in the records' order; Wilderness tasks only when
	 * {@code wilderness}.
	 */
	private static List<LedgerRecord> samples(Request request, boolean wilderness)
	{
		final List<LedgerRecord> samples = new ArrayList<>();
		if (request.getRecords() == null)
		{
			return samples;
		}
		final boolean boss = request.getTaskType() == TaskType.BOSS
			|| request.getCreatureId() < SessionTracker.NO_CREATURE;
		for (LedgerRecord record : request.getRecords())
		{
			if (TaskStatistics.isTaskSample(record)
				&& TaskStatistics.isSample(record, request.getCreatureId(), request.getTask())
				&& isBossTask(record) == boss
				&& (!wilderness || isWildernessTask(record)))
			{
				samples.add(record);
			}
		}
		return samples;
	}

	/** Whether the record is a Wilderness task: Krystilia's ({@link TaskType#of} its master and creature key). */
	private static boolean isWildernessTask(LedgerRecord record)
	{
		return TaskType.of(record.getSlayerMaster(), record.getCreatureId()) == TaskType.WILDERNESS;
	}

	private static boolean isBossTask(LedgerRecord record)
	{
		return record.getCreatureId() != null && record.getCreatureId() < SessionTracker.NO_CREATURE;
	}

	/**
	 * Whether the record's variant is its task's Slayer boss: most of its kills were the boss ({@link TaskVariants}),
	 * for example a gargoyle task done on the Grotesque Guardians.
	 */
	static boolean isBossVariant(LedgerRecord record)
	{
		final String boss = TaskStatistics.normalize(TaskVariants.bossName(record.getMonster()));
		return boss != null && boss.equals(TaskStatistics.normalize(record.getVariant()));
	}

	private static TaskPlan.Option option(OptionKey key, List<LedgerRecord> tasks, int kills, Request request,
		Map<Integer, Long> prices)
	{
		boolean cannonEstimated = false;
		boolean styleEstimated = false;
		int bossKillTasks = 0;
		for (LedgerRecord record : tasks)
		{
			final SetupTags tags = SetupTags.of(record);
			cannonEstimated |= tags.isCannonEstimated();
			styleEstimated |= tags.isStyleEstimated();
			if (TaskVariants.hasBossKills(record))
			{
				bossKillTasks++;
			}
		}
		// Unknown kills left: nothing to bring, and the time of one kill.
		final List<TaskPlan.BringItem> bring = kills > 0 ? bring(tasks, kills, prices, request.getPotionFamilies())
			: Collections.emptyList();
		// The grid's items; the rest are "+N more".
		final int shown = Math.min(MAX_BRING_ITEMS, bring.size());
		final int scaleKills = kills > 0 ? kills : 1;
		return TaskPlan.Option.builder()
			.spot(key.spot)
			.boss(key.boss)
			.cannon(key.cannon)
			.style(key.style)
			.cannonEstimated(cannonEstimated)
			.styleEstimated(styleEstimated)
			.bossKillTasks(bossKillTasks)
			.tasks(tasks.size())
			.time(time(tasks, scaleKills))
			.bring(Collections.unmodifiableList(new ArrayList<>(bring.subList(0, shown))))
			.bringRest(Collections.unmodifiableList(new ArrayList<>(bring.subList(shown, bring.size()))))
			.build();
	}

	/** The time for {@code kills} kills from {@code tasks}' times per kill (see {@link TaskPlan.Time}). */
	static TaskPlan.Time time(List<LedgerRecord> tasks, int kills)
	{
		final List<BigDecimal> perKill = new ArrayList<>(tasks.size());
		for (LedgerRecord record : tasks)
		{
			perKill.add(BigDecimal.valueOf(Math.max(0, record.getActiveMillis()))
				.divide(BigDecimal.valueOf(record.getKills()), MathContext.DECIMAL128));
		}
		Collections.sort(perKill);
		final BigDecimal median = percentile(perKill, 50);
		return new TaskPlan.Time(round(median), round(median.multiply(BigDecimal.valueOf(kills))));
	}

	/**
	 * What to bring for {@code kills} kills from {@code tasks} (see the class rules), every item, sorted most valuable
	 * first. Only tasks with supply data count ({@link #hasSupplyData}). The ids of one potion family
	 * ({@code families}: id to family, {@code null} for none) are one item: a task's doses of them all, in potions of
	 * the family's id with the most doses (the lower id on a tie), which the item is keyed and priced by.
	 */
	static List<TaskPlan.BringItem> bring(List<LedgerRecord> tasks, int kills, Map<Integer, Long> prices,
		Map<Integer, String> families)
	{
		final List<LedgerRecord> withSupplies = new ArrayList<>();
		final Map<Object, Line> lines = new LinkedHashMap<>();
		for (LedgerRecord record : tasks)
		{
			if (!hasSupplyData(record))
			{
				continue;
			}
			withSupplies.add(record);
			if (record.getSupplies() == null)
			{
				continue;
			}
			for (Map.Entry<Integer, Long> item : record.getSupplies().entrySet())
			{
				if (item.getKey() != null && item.getValue() != null && item.getValue() > 0)
				{
					final int id = item.getKey();
					final int perItem = perItem(record, id);
					final Object key = lineKey(id, families);
					final Line line = lines.get(key);
					if (line == null)
					{
						lines.put(key, new Line(id, perItem));
					}
					else
					{
						line.offer(id, perItem);
					}
				}
			}
		}
		final List<TaskPlan.BringItem> items = new ArrayList<>();
		final BigDecimal killsLeft = BigDecimal.valueOf(kills);
		for (Map.Entry<Object, Line> line : lines.entrySet())
		{
			final List<BigDecimal> perKill = new ArrayList<>(withSupplies.size());
			for (LedgerRecord record : withSupplies)
			{
				perKill.add(itemsPerKill(record, line.getKey(), line.getValue(), families));
			}
			Collections.sort(perKill);
			final long quantity = toLong(percentile(perKill, BRING_PERCENTILE).multiply(killsLeft)
				.subtract(ROUNDING_SLACK).setScale(0, RoundingMode.CEILING));
			if (quantity <= 0)
			{
				continue;
			}
			final int id = line.getValue().itemId;
			final Long price = prices.get(id);
			items.add(new TaskPlan.BringItem(id, quantity, price == null || price <= 0 ? null
				: toLong(BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(quantity)))));
		}
		items.sort(Comparator
			.comparing(TaskPlan.BringItem::getValue, Comparator.nullsLast(Comparator.reverseOrder()))
			.thenComparing(Comparator.comparingLong(TaskPlan.BringItem::getQuantity).reversed())
			.thenComparingInt(TaskPlan.BringItem::getItemId));
		return items;
	}

	/**
	 * The bring line of supply {@code id}: its potion family's name when {@code families} has one, else the id itself.
	 * A name and an id are never equal, and an id is either always in a family or never, so no two lines share an item.
	 */
	private static Object lineKey(int id, Map<Integer, String> families)
	{
		final String family = families == null ? null : families.get(id);
		return family == null ? (Object) id : family;
	}

	/** Doses (bites, charges) in one item of {@code id} as the record counts it, or 1 for one counted in items. */
	private static int perItem(LedgerRecord record, int id)
	{
		final Integer perItem = record.getSupplyDoses() == null ? null : record.getSupplyDoses().get(id);
		return perItem != null && perItem > 1 ? perItem : 1;
	}

	/**
	 * Whole items of line {@code key} the record used per kill: for one id, its doses, bites or charges divided by the
	 * count in one item ({@link LedgerRecord#getSupplyDoses}), else its items; for a potion family, the doses of all
	 * its ids divided by the doses in one potion of the line's item. 0 when it used none.
	 */
	private static BigDecimal itemsPerKill(LedgerRecord record, Object key, Line line, Map<Integer, String> families)
	{
		final Map<Integer, Long> supplies = record.getSupplies();
		if (supplies == null || record.getKills() <= 0)
		{
			return BigDecimal.ZERO;
		}
		long used = 0;
		int perItem = line.perItem;
		if (key instanceof Integer)
		{
			final Long quantity = supplies.get(key);
			used = quantity == null ? 0 : quantity;
			perItem = perItem(record, (Integer) key);
		}
		else
		{
			for (Map.Entry<Integer, Long> item : supplies.entrySet())
			{
				if (item.getKey() != null && item.getValue() != null && item.getValue() > 0
					&& key.equals(lineKey(item.getKey(), families)))
				{
					used = saturatedAdd(used, item.getValue());
				}
			}
		}
		if (used <= 0)
		{
			return BigDecimal.ZERO;
		}
		final long divisor = (long) record.getKills() * perItem;
		return BigDecimal.valueOf(used).divide(BigDecimal.valueOf(divisor), MathContext.DECIMAL128);
	}

	/**
	 * The potion families of the potions {@code records} used, for {@link Request#getPotionFamilies}: each id a record
	 * counts in doses ({@link LedgerRecord#getSupplyDoses}; not the bites or charges of {@link ItemDetails#doseUnit})
	 * whose name {@code names} knows, to that name without its dose suffix ({@link ItemDetails#displayName}), lower
	 * case, as {@link SupplyTracker} names a potion's family: "Prayer potion(4)" and "Prayer potion(3)" are both
	 * "prayer potion" ({@link ItemDetails#potionFamily}, which the supplies grids merge by too). Worked out where
	 * item names are known; an id whose name is unknown stays on its own.
	 */
	static Map<Integer, String> potionFamilies(Collection<LedgerRecord> records, ItemDetails.ItemNames names)
	{
		final Map<Integer, String> families = new HashMap<>();
		if (records == null || names == null)
		{
			return families;
		}
		for (LedgerRecord record : records)
		{
			if (record == null || record.getSupplyDoses() == null)
			{
				continue;
			}
			for (Integer id : record.getSupplyDoses().keySet())
			{
				if (id == null || families.containsKey(id))
				{
					continue;
				}
				final String family = ItemDetails.potionFamily(id, names);
				if (family != null)
				{
					families.put(id, family);
				}
			}
		}
		return families;
	}

	/**
	 * The {@code percent}th percentile of {@code sorted} (ascending), by linear interpolation between the closest
	 * ranks: the rank is {@code (n - 1) * percent / 100}, so with one value every percentile is it, the 50th of an even
	 * count
	 * is the mean of the middle two, and the 20th of 5 values lies 80% of the way from the lowest to the second.
	 *
	 * @return the percentile, or {@code null} for no values
	 */
	static BigDecimal percentile(List<BigDecimal> sorted, int percent)
	{
		final int n = sorted.size();
		if (n == 0)
		{
			return null;
		}
		final BigDecimal rank = BigDecimal.valueOf((long) (n - 1) * percent).divide(HUNDRED);
		final int lower = rank.intValue();
		final BigDecimal fraction = rank.subtract(BigDecimal.valueOf(lower));
		final BigDecimal low = sorted.get(lower);
		if (fraction.signum() == 0 || lower + 1 >= n)
		{
			return low;
		}
		return low.add(sorted.get(lower + 1).subtract(low).multiply(fraction));
	}

	private static long round(BigDecimal value)
	{
		return toLong(value.setScale(0, RoundingMode.HALF_UP));
	}

	/** A whole {@code value} as a long, saturating at the {@code long} range. */
	private static long toLong(BigDecimal value)
	{
		if (value.compareTo(LONG_MAX) > 0)
		{
			return Long.MAX_VALUE;
		}
		return value.signum() < 0 && value.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0 ? Long.MIN_VALUE
			: value.longValue();
	}

	private static long saturatedAdd(long left, long right)
	{
		try
		{
			return Math.addExact(left, right);
		}
		catch (ArithmeticException e)
		{
			return right >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
		}
	}

	/** One bring line's item: an id, or a potion family's id with the most doses in a potion (on a tie the lower). */
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	private static final class Line
	{
		private int itemId;
		/** Doses (bites, charges) in one item of {@link #itemId}, 1 for an item counted whole. */
		private int perItem;

		/** Takes {@code id} as the item when it holds more doses in one item, or as many with a lower id. */
		private void offer(int id, int dosesPerItem)
		{
			if (dosesPerItem > perItem || dosesPerItem == perItem && id < itemId)
			{
				itemId = id;
				perItem = dosesPerItem;
			}
		}
	}

	/** What splits the options: spot, boss variant, effective cannon and effective style. */
	@EqualsAndHashCode
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	private static final class OptionKey
	{
		private final String spot;
		private final boolean boss;
		private final boolean cannon;
		private final SetupStyle style;
	}
}
