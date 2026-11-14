package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntFunction;
import net.runelite.api.gameval.ItemID;

/**
 * The overlay's supply runway (DESIGN_PRINCIPLES.md 2.5): how many kills each carried supply lasts at the
 * current rate, and the scarcest one when it runs out before the task ends. Pure; the plugin hands it what is carried
 * and what was used, on the client thread once per tick, never while the overlay draws. The rule is written out in
 * docs/COUNTING_RULES.md ("Supply runway").
 *
 * <ul>
 * <li><b>What counts.</b> Only supplies with a short name ({@link Kind}): prayer potions, restores (super restores,
 * blighted super restores, sanfew serums), food, cannonballs, runes, ammo, darts and Zulrah's scales. Other potions,
 * teleports, jewellery charges and Slayer consumables don't.</li>
 * <li><b>Units.</b> Potions count in doses, food eaten in parts in bites, everything else in items; the carried amount
 * and the use are in the same unit and summed per supply ({@link SupplyTracker#supplyKey}), so every dose of every
 * potion size of a family counts as one supply.</li>
 * <li><b>Per-kill use.</b> This task's once its session has {@link #MIN_TASK_KILLS} kills: what it used divided by its
 * kills (a supply it hasn't used lasts). Before that the history's, the same completed tasks the time left uses
 * ({@link TaskStatistics#getSuppliesPerKill}); none without history.</li>
 * <li><b>Kills it lasts.</b> The carried amount divided by the per-kill use, rounded down, but at least 1 while any of
 * it is carried: 0 means none is.</li>
 * <li><b>Run out.</b> A supply carried earlier in the session and gone now counts at none ({@link #withRunOut}), so its
 * warning stays at {@code Prayer: 0 left} until it or another of its kind (but not another rune) is carried again,
 * the session ends or the rate in use no longer uses it. Only a fall to none counted as use runs a supply out: a tick
 * the supply counting leaves out (the bank, a drop) forgets what left with it ({@link OverlayFeed}). Not while the
 * containers weren't read, nor darts and scales while a blowpipe may hold some that aren't counted
 * ({@link #blowpipeUnseen}).</li>
 * <li><b>Shown.</b> Only the scarcest supply (fewest kills; a tie goes to the first {@link Kind}, then the lower item
 * id), and only when it lasts fewer kills than are left on the task. Critical within {@link #CRITICAL_KILLS}
 * kills.</li>
 * </ul>
 */
final class SupplyRunway
{
	/** A supply that lasts this many kills or fewer is critical: {@code Prayer: 6 left}, in Bold. */
	static final int CRITICAL_KILLS = 9;
	/** From this many kills in the session, its own use per kill replaces the history's. */
	static final int MIN_TASK_KILLS = 10;
	/** Zulrah's scales: the blowpipe's ammunition ({@code ItemID.SNAKEBOSS_SCALE}). */
	static final int ZULRAH_SCALES = ItemID.SNAKEBOSS_SCALE;

	/** The potion families counted as {@link Kind#PRAYER}, lower case. */
	private static final List<String> PRAYER_FAMILIES = Collections.singletonList("prayer potion");
	/** The potion families counted as {@link Kind#RESTORES}, lower case. */
	private static final List<String> RESTORE_FAMILIES = Collections.unmodifiableList(Arrays.asList(
		"super restore", "blighted super restore", "sanfew serum"));

	/** What a counted supply is, with its short name (DESIGN_PRINCIPLES.md 2.5), in tie-break order. */
	enum Kind
	{
		PRAYER("Prayer"),
		RESTORES("Restores"),
		FOOD("Food"),
		CANNONBALLS("Cannonballs"),
		RUNES("Runes"),
		AMMO("Ammo"),
		DARTS("Darts"),
		SCALES("Scales");

		private final String shortName;

		Kind(String shortName)
		{
			this.shortName = shortName;
		}

		/** The runway's name for it: {@code Prayer}, {@code Cannonballs}. */
		String getShortName()
		{
			return shortName;
		}
	}

	/** A supply the player carries now. Immutable. */
	static final class Supply
	{
		private final String key;
		private final int itemId;
		private final Kind kind;
		private final long amount;

		/**
		 * @param key    the supply's key ({@link SupplyTracker#supplyKey})
		 * @param itemId the item whose icon the runway shows (a potion's largest size seen)
		 * @param amount how much is carried, in the unit its use is counted in (doses, bites or items)
		 */
		Supply(String key, int itemId, Kind kind, long amount)
		{
			this.key = Objects.requireNonNull(key);
			this.itemId = itemId;
			this.kind = Objects.requireNonNull(kind);
			this.amount = amount;
		}

		String getKey()
		{
			return key;
		}

		int getItemId()
		{
			return itemId;
		}

		Kind getKind()
		{
			return kind;
		}

		long getAmount()
		{
			return amount;
		}
	}

	/**
	 * What the player carries on a tick, for the runway: the counted supplies, whether the containers were read at all,
	 * and whether a toxic blowpipe may hold darts and scales that can't be counted ({@link #blowpipeUnseen}). Immutable.
	 */
	static final class Carrying
	{
		/** Nothing known carried: the containers were not read on the tick, and no blowpipe's contents are known. */
		static final Carrying UNSEEN = new Carrying(Collections.emptyList(), false, false);

		private final List<Supply> supplies;
		private final boolean seen;
		private final boolean blowpipeUnseen;

		/** What the containers read on the tick carry ({@link #Carrying(List, boolean, boolean)}). */
		Carrying(List<Supply> supplies, boolean blowpipeUnseen)
		{
			this(supplies, true, blowpipeUnseen);
		}

		/**
		 * @param supplies       the counted supplies carried ({@link #carried}, {@link #withBlowpipe})
		 * @param seen           whether the containers were read on the tick (no task assigned, or the inventory not
		 *                       loaded): without them only a worn blowpipe's known contents are carried, and nothing
		 *                       counts as run out
		 * @param blowpipeUnseen whether a blowpipe may hold darts and scales that can't be counted
		 */
		Carrying(List<Supply> supplies, boolean seen, boolean blowpipeUnseen)
		{
			this.supplies = supplies == null ? Collections.emptyList()
				: Collections.unmodifiableList(new ArrayList<>(supplies));
			this.seen = seen;
			this.blowpipeUnseen = blowpipeUnseen;
		}

		/** The counted supplies carried; empty when {@link #UNSEEN}. */
		List<Supply> getSupplies()
		{
			return supplies;
		}

		/** Whether the containers were read on the tick. */
		boolean isSeen()
		{
			return seen;
		}

		/**
		 * Whether the amount of {@code kind} carried is known, so that none of it means it ran out: not while the
		 * containers weren't read, nor darts and scales while a blowpipe may hold some that can't be counted.
		 */
		boolean isSeen(Kind kind)
		{
			return seen && !(blowpipeUnseen && (kind == Kind.DARTS || kind == Kind.SCALES));
		}
	}

	/** The runway to show: a supply and the kills it lasts. Immutable. */
	static final class Warning
	{
		private final Kind kind;
		private final int itemId;
		private final long kills;

		Warning(Kind kind, int itemId, long kills)
		{
			this.kind = Objects.requireNonNull(kind);
			this.itemId = itemId;
			this.kills = kills;
		}

		Kind getKind()
		{
			return kind;
		}

		/** The item whose icon is shown. */
		int getItemId()
		{
			return itemId;
		}

		/** The kills the carried amount lasts (rounded down, at least 1 while any is carried); 0 once it ran out. */
		long getKills()
		{
			return kills;
		}

		/** Whether it runs out within {@link #CRITICAL_KILLS} kills. */
		boolean isCritical()
		{
			return kills <= CRITICAL_KILLS;
		}

		@Override
		public boolean equals(Object other)
		{
			if (!(other instanceof Warning))
			{
				return false;
			}
			final Warning that = (Warning) other;
			return kind == that.kind && itemId == that.itemId && kills == that.kills;
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(kind, itemId, kills);
		}

		@Override
		public String toString()
		{
			return kind.getShortName() + " (" + itemId + "): " + kills;
		}
	}

	private SupplyRunway()
	{
	}

	/**
	 * What a tracked supply counts as for the runway, or {@code null} when it doesn't count.
	 *
	 * @param category its supplies category ({@link SupplyTracker.ItemInfo#getCategory})
	 * @param family   a potion's family (its name without the dose), or {@code null}
	 * @param itemId   the item (a dart the blowpipe takes is {@link Kind#DARTS}, other ammo {@link Kind#AMMO})
	 */
	static Kind kind(SupplyTracker.Category category, String family, int itemId)
	{
		if (category == null)
		{
			return null;
		}
		switch (category)
		{
			case POTION:
			{
				final String name = family == null ? "" : family.trim().toLowerCase(Locale.ROOT);
				if (PRAYER_FAMILIES.contains(name))
				{
					return Kind.PRAYER;
				}
				return RESTORE_FAMILIES.contains(name) ? Kind.RESTORES : null;
			}
			case FOOD:
				return Kind.FOOD;
			case CANNONBALL:
				return Kind.CANNONBALLS;
			case RUNE:
				return Kind.RUNES;
			case AMMO:
				return BlowpipeMessage.isDart(itemId) ? Kind.DARTS : Kind.AMMO;
			default:
				return null;
		}
	}

	/** The carried supplies that count ({@link #kind}), from what the supplies diff last saw. */
	static List<Supply> carried(Collection<SupplyTracker.Carried> carried)
	{
		if (carried == null || carried.isEmpty())
		{
			return Collections.emptyList();
		}
		final List<Supply> supplies = new ArrayList<>(carried.size());
		for (SupplyTracker.Carried item : carried)
		{
			final Kind kind = kind(item.getCategory(), item.getFamily(), item.getItemId());
			if (kind != null && item.getAmount() > 0)
			{
				supplies.add(new Supply(item.getKey(), item.getItemId(), kind, item.getAmount()));
			}
		}
		return supplies;
	}

	/**
	 * {@code carried} with what a worn toxic blowpipe holds: its darts added to the darts of their type, and its Zulrah's
	 * scales with the loose ones carried. While its contents are not known ({@link BlowpipeContents}) no darts or scales
	 * count, since what it holds could be anything; without a blowpipe worn {@code carried} is returned as it is.
	 *
	 * @param looseScales Zulrah's scales carried outside the blowpipe
	 */
	static List<Supply> withBlowpipe(List<Supply> carried, boolean blowpipeWorn, BlowpipeContents contents,
		long looseScales)
	{
		if (!blowpipeWorn)
		{
			return carried;
		}
		final boolean known = contents != null && contents.isKnown();
		final List<Supply> supplies = new ArrayList<>(carried.size() + 2);
		final String dartKey = known && contents.getDartId() != ChargedWeaponUsage.NO_ITEM
			? SupplyTracker.itemKey(contents.getDartId()) : null;
		boolean dartsAdded = false;
		for (Supply supply : carried)
		{
			if (supply.getKind() == Kind.DARTS || supply.getKind() == Kind.SCALES)
			{
				if (!known)
				{
					continue;
				}
				if (supply.getKey().equals(dartKey))
				{
					supplies.add(new Supply(supply.getKey(), supply.getItemId(), Kind.DARTS,
						saturatedAdd(supply.getAmount(), contents.getDarts())));
					dartsAdded = true;
					continue;
				}
			}
			supplies.add(supply);
		}
		if (known)
		{
			if (dartKey != null && !dartsAdded && contents.getDarts() > 0)
			{
				supplies.add(new Supply(dartKey, contents.getDartId(), Kind.DARTS, contents.getDarts()));
			}
			final long scales = saturatedAdd(contents.getScales(), Math.max(0, looseScales));
			if (scales > 0)
			{
				supplies.add(new Supply(SupplyTracker.itemKey(ZULRAH_SCALES), ZULRAH_SCALES, Kind.SCALES, scales));
			}
		}
		return supplies;
	}

	/**
	 * Whether a toxic blowpipe may hold darts and scales the runway can't count, so that none of them carried says
	 * nothing about running out: whenever its contents are not known, worn or not (worn, {@link #withBlowpipe} then
	 * counts no darts or scales at all; not worn, the player may still have one, unequipped since a login or a load
	 * made its contents unknown, say), or not worn while it is known to hold some (they count only while it is worn).
	 * So without a blowpipe checked since the login, thrown darts don't run out either.
	 */
	static boolean blowpipeUnseen(boolean blowpipeWorn, BlowpipeContents contents)
	{
		if (contents == null || !contents.isKnown())
		{
			return true;
		}
		return !blowpipeWorn && (contents.getDarts() > 0 || contents.getScales() > 0);
	}

	/**
	 * What is carried on a tick, for the runway: {@code carried} with a worn blowpipe's contents
	 * ({@link #withBlowpipe}), and whether the blowpipe may hold darts and scales that can't be counted
	 * ({@link #blowpipeUnseen}).
	 *
	 * @param carried the counted supplies the containers hold ({@link #carried}); empty when they weren't read
	 * @param seen    whether the containers were read on the tick ({@link Carrying#isSeen})
	 */
	static Carrying carrying(List<Supply> carried, boolean seen, boolean blowpipeWorn, BlowpipeContents contents,
		long looseScales)
	{
		return new Carrying(withBlowpipe(carried, blowpipeWorn, contents, looseScales), seen,
			blowpipeUnseen(blowpipeWorn, contents));
	}

	/**
	 * {@code carrying}'s supplies, with each supply carried earlier in the session ({@code earlier}) that is not carried
	 * now added at none, so that its runway shows it ran out: only when its amount is seen ({@link Carrying#isSeen}),
	 * and not while another supply of its kind is carried (karambwans after the sharks ran out, another dart type),
	 * except runes, each of which a spell needs.
	 *
	 * @param earlier the supplies carried earlier in the session, each as last carried (its key, icon and kind)
	 */
	static List<Supply> withRunOut(Carrying carrying, Collection<Supply> earlier)
	{
		final List<Supply> supplies = new ArrayList<>(carrying.getSupplies());
		if (earlier == null || earlier.isEmpty() || !carrying.isSeen())
		{
			return supplies;
		}
		final Set<String> carried = new HashSet<>();
		final Set<Kind> kinds = EnumSet.noneOf(Kind.class);
		for (Supply supply : supplies)
		{
			carried.add(supply.getKey());
			if (supply.getAmount() > 0)
			{
				kinds.add(supply.getKind());
			}
		}
		for (Supply supply : earlier)
		{
			final boolean replaced = supply.getKind() != Kind.RUNES && kinds.contains(supply.getKind());
			if (!carried.contains(supply.getKey()) && !replaced && carrying.isSeen(supply.getKind()))
			{
				supplies.add(new Supply(supply.getKey(), supply.getItemId(), supply.getKind(), 0));
				carried.add(supply.getKey());
			}
		}
		return supplies;
	}

	/** {@code items} (item id to amount) summed per supply key ({@code keyOf}); ids without a key are left out. */
	static Map<String, Long> byKey(Map<Integer, Long> items, IntFunction<String> keyOf)
	{
		final Map<String, Long> byKey = new TreeMap<>();
		if (items != null)
		{
			items.forEach((id, amount) ->
			{
				final String key = id == null || amount == null || amount <= 0 ? null : keyOf.apply(id);
				if (key != null)
				{
					byKey.merge(key, amount, SupplyRunway::saturatedAdd);
				}
			});
		}
		return byKey;
	}

	/** {@code perKill} (item id to use per kill) summed per supply key ({@code keyOf}); ids without a key are left out. */
	static Map<String, Double> perKillByKey(Map<Integer, Double> perKill, IntFunction<String> keyOf)
	{
		final Map<String, Double> byKey = new TreeMap<>();
		if (perKill != null)
		{
			perKill.forEach((id, amount) ->
			{
				final String key = id == null || amount == null || !(amount > 0) ? null : keyOf.apply(id);
				if (key != null)
				{
					byKey.merge(key, amount, Double::sum);
				}
			});
		}
		return byKey;
	}

	/**
	 * The runway to show, or {@code null}: the carried supply that lasts the fewest kills, when that is fewer than
	 * {@code killsLeft} (see the class comment for the rule).
	 *
	 * @param carried        the supplies carried now
	 * @param taskUse        this session's use so far, per supply key, in the same units
	 * @param taskKills      this session's kills
	 * @param historyPerKill the history's use per kill, per supply key; empty without history
	 * @param killsLeft      the kills left on the task; nothing is shown when it is not known ({@code <= 0})
	 */
	static Warning scarcest(Collection<Supply> carried, Map<String, Long> taskUse, int taskKills,
		Map<String, Double> historyPerKill, int killsLeft)
	{
		if (carried == null || killsLeft <= 0)
		{
			return null;
		}
		final boolean ownRate = taskKills >= MIN_TASK_KILLS;
		Supply best = null;
		long bestKills = Long.MAX_VALUE;
		for (Supply supply : carried)
		{
			final long kills = ownRate ? lastsAtTaskRate(supply, taskUse, taskKills)
				: lastsAtHistoryRate(supply, historyPerKill);
			if (kills < 0 || kills >= killsLeft)
			{
				continue;
			}
			if (best == null || kills < bestKills || (kills == bestKills && before(supply, best)))
			{
				best = supply;
				bestKills = kills;
			}
		}
		return best == null ? null : new Warning(best.getKind(), best.getItemId(), bestKills);
	}

	/** Kills {@code supply} lasts at the session's own rate ({@link #atLeastOne}), or -1 when the session used none. */
	private static long lastsAtTaskRate(Supply supply, Map<String, Long> taskUse, int taskKills)
	{
		final Long used = taskUse == null ? null : taskUse.get(supply.getKey());
		if (used == null || used <= 0 || taskKills <= 0)
		{
			return -1;
		}
		// carried / (used / kills), rounded down, exactly.
		try
		{
			return atLeastOne(supply, Math.multiplyExact(Math.max(0, supply.getAmount()), (long) taskKills) / used);
		}
		catch (ArithmeticException e)
		{
			return Long.MAX_VALUE;
		}
	}

	/** Kills {@code supply} lasts at the history's rate ({@link #atLeastOne}), or -1 when the history has no use of it. */
	private static long lastsAtHistoryRate(Supply supply, Map<String, Double> historyPerKill)
	{
		final Double perKill = historyPerKill == null ? null : historyPerKill.get(supply.getKey());
		if (perKill == null || !(perKill > 0))
		{
			return -1;
		}
		// A small allowance, so 20 doses at 0.5 a kill are 40 kills, not 39.999...
		final double kills = Math.floor(Math.max(0, supply.getAmount()) / perKill + 1e-9);
		return atLeastOne(supply, kills >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) kills);
	}

	/** {@code kills}, but at least 1 while any of {@code supply} is carried: 0 kills means none is. */
	private static long atLeastOne(Supply supply, long kills)
	{
		return supply.getAmount() > 0 ? Math.max(1, kills) : kills;
	}

	/** The tie-break: the earlier kind, then the lower item id. */
	private static boolean before(Supply a, Supply b)
	{
		final int byKind = a.getKind().compareTo(b.getKind());
		return byKind != 0 ? byKind < 0 : a.getItemId() < b.getItemId();
	}

	private static long saturatedAdd(long a, long b)
	{
		final long sum = a + b;
		return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
	}
}
