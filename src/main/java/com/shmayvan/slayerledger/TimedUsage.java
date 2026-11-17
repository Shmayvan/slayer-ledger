package com.shmayvan.slayerledger;

import java.util.*;
import lombok.*;

/**
 * Supplies, XP and loot, each stamped with the clock time it arrived (V2 plan 4.1), for the use a session may still
 * count or give back. {@link SessionTracker} keeps two: the buffer outside segments, from which a (re)start counts the
 * XP and loot from the segment start and the supplies from 3 minutes before it; and the open segment's use since its
 * last activity, which is taken back into the buffer if Away starts.
 *
 * <p>Additions at the same clock time share one entry. Bounded: each addition drops the entries more than
 * {@link #MAX_AGE_MILLIS} older than it, and the oldest beyond {@link #MAX_ENTRIES}. Pure (no RuneLite types); not
 * synchronized (the tracker's lock guards it).</p>
 */
final class TimedUsage
{
	/** How long an entry is kept: the buffer holds at most 60 minutes (V2 plan 4.1). */
	static final long MAX_AGE_MILLIS = 60 * 60_000L;
	/**
	 * Most entries kept. The plugin adds supplies, XP and loot a few times per game tick at most, and only on ticks when
	 * something was used or gained; this bounds even an hour of fighting with every part on every tick.
	 */
	static final int MAX_ENTRIES = 12_000;

	/**
	 * What arrived at one clock time. Each part is {@code null} when nothing of it arrived. Changed only by its
	 * {@link TimedUsage}.
	 */
	@Getter
	@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
	static final class Entry
	{
		/** When it arrived (clock millis). */
		private final long at;
		/** Supplies used, potions in doses (as {@link SessionTracker#addSupplies}). */
		private ItemTally used;
		/** Ammo or cannonballs that came back; each takes that much off the item's use, never below zero. */
		private ItemTally returned;
		/** Doses (bites, charges) per item for the potion ids in {@link #used}. */
		private Map<Integer, Integer> doses;
		/** XP gained per skill ({@link SkillXp}). */
		private Map<String, Long> xp;
		private ItemTally loot;
		/** Notable stacks of {@link #loot}, and pets. */
		private List<NotableDrop> drops;

		private boolean hasSupplies()
		{
			return used != null || returned != null;
		}

		private boolean hasCounted()
		{
			return xp != null || loot != null || drops != null;
		}

		private boolean isEmpty()
		{
			return !hasSupplies() && !hasCounted();
		}

		/** Moves this entry's supplies to a new entry at the same time, which is returned. */
		private Entry takeSupplies()
		{
			final Entry taken = new Entry(at);
			taken.used = used;
			taken.returned = returned;
			taken.doses = doses;
			used = null;
			returned = null;
			doses = null;
			return taken;
		}

		/** Moves this entry's XP and loot into {@code into}. */
		private void moveCountedTo(Entry into)
		{
			into.xp = xp;
			into.loot = loot;
			into.drops = drops;
			xp = null;
			loot = null;
			drops = null;
		}
	}

	/**
	 * Oldest first. The tracker adds each one at its clock's time now, which only moves on, and Away gives the buffer
	 * back what it took at times after anything the buffer still holds (the buffer fills only while no segment is
	 * open, and a restart leaves only what came before the segment), so {@link #dropBefore}, {@link #dropUpTo} and
	 * {@link #prune} drop from the front and stop at the first newer entry, at little cost per addition. A clock that
	 * went back can leave an entry out of order: the drops then stop at it and leave older ones behind for a later drop,
	 * {@link #prune} or {@link #clear}, while {@link #take} and {@link #takeAfter} still go by each entry's own time.
	 */
	private final Deque<Entry> entries = new ArrayDeque<>();

	/** Adds supplies used and returned at {@code at}; empty or {@code null} parts are left out. */
	void addSupplies(long at, ItemTally used, ItemTally returned, Map<Integer, Integer> doses)
	{
		final boolean hasUsed = used != null && !used.isEmpty();
		final boolean hasReturned = returned != null && !returned.isEmpty();
		if (!hasUsed && !hasReturned)
		{
			return;
		}
		final Entry entry = entryAt(at);
		if (hasUsed)
		{
			if (entry.used == null)
			{
				entry.used = new ItemTally();
			}
			entry.used.merge(used);
			if (doses != null && !doses.isEmpty())
			{
				if (entry.doses == null)
				{
					entry.doses = new TreeMap<>();
				}
				SessionTracker.putDoses(entry.doses, doses);
			}
		}
		if (hasReturned)
		{
			if (entry.returned == null)
			{
				entry.returned = new ItemTally();
			}
			entry.returned.merge(returned);
		}
		prune(at);
	}

	/** Adds XP gained at {@code at} ({@link SkillXp#gains}); {@code null} or empty adds nothing. */
	void addXp(long at, Map<String, Long> gains)
	{
		if (gains == null || gains.isEmpty())
		{
			return;
		}
		final Entry entry = entryAt(at);
		if (entry.xp == null)
		{
			entry.xp = new TreeMap<>();
		}
		SkillXp.add(entry.xp, gains);
		prune(at);
	}

	/** Adds loot and its notable drops (either may be {@code null} or empty, for example a pet's drop alone). */
	void addLoot(long at, ItemTally items, List<NotableDrop> notableDrops)
	{
		final boolean hasItems = items != null && !items.isEmpty();
		final boolean hasDrops = notableDrops != null && !notableDrops.isEmpty();
		if (!hasItems && !hasDrops)
		{
			return;
		}
		final Entry entry = entryAt(at);
		if (hasItems)
		{
			if (entry.loot == null)
			{
				entry.loot = new ItemTally();
			}
			entry.loot.merge(items);
		}
		if (hasDrops)
		{
			if (entry.drops == null)
			{
				entry.drops = new ArrayList<>();
			}
			for (NotableDrop drop : notableDrops)
			{
				if (drop != null)
				{
					entry.drops.add(drop);
				}
			}
		}
		prune(at);
	}

	/** Adds everything in {@code entry} at its own time, as if it had arrived here. */
	void add(Entry entry)
	{
		if (entry == null)
		{
			return;
		}
		addSupplies(entry.at, entry.used, entry.returned, entry.doses);
		addXp(entry.at, entry.xp);
		addLoot(entry.at, entry.loot, entry.drops);
	}

	/**
	 * Removes and returns, oldest first, the supplies of the entries at or after {@code suppliesFrom} and the XP and
	 * loot of the entries at or after {@code countFrom}. What is left of each entry stays.
	 */
	List<Entry> take(long suppliesFrom, long countFrom)
	{
		final List<Entry> taken = new ArrayList<>();
		final Iterator<Entry> it = entries.iterator();
		while (it.hasNext())
		{
			final Entry entry = it.next();
			Entry part = null;
			if (entry.at >= suppliesFrom && entry.hasSupplies())
			{
				part = entry.takeSupplies();
			}
			if (entry.at >= countFrom && entry.hasCounted())
			{
				if (part == null)
				{
					part = new Entry(entry.at);
				}
				entry.moveCountedTo(part);
			}
			if (part != null)
			{
				taken.add(part);
			}
			if (entry.isEmpty())
			{
				it.remove();
			}
		}
		taken.sort((a, b) -> Long.compare(a.at, b.at));
		return taken;
	}

	/** Removes and returns the entries after {@code after}, newest first, so they can be undone in reverse order. */
	List<Entry> takeAfter(long after)
	{
		final List<Entry> taken = new ArrayList<>();
		final Iterator<Entry> it = entries.iterator();
		while (it.hasNext())
		{
			final Entry entry = it.next();
			if (entry.at > after)
			{
				taken.add(entry);
				it.remove();
			}
		}
		taken.sort((a, b) -> Long.compare(b.at, a.at));
		return taken;
	}

	/** Drops the entries before {@code at}, from the front (see {@link #entries}). */
	void dropBefore(long at)
	{
		while (!entries.isEmpty() && entries.peekFirst().at < at)
		{
			entries.removeFirst();
		}
	}

	/**
	 * Drops the entries at or before {@code at}, from the front (see {@link #entries}): called on every addition to the
	 * open segment's use since its last activity, so it must not scan what it keeps.
	 */
	void dropUpTo(long at)
	{
		while (!entries.isEmpty() && entries.peekFirst().at <= at)
		{
			entries.removeFirst();
		}
	}

	void clear()
	{
		entries.clear();
	}

	boolean isEmpty()
	{
		return entries.isEmpty();
	}

	/** How many entries are kept. */
	int size()
	{
		return entries.size();
	}

	/** The entry for {@code at}: the newest one when it has that time, otherwise a new one. */
	private Entry entryAt(long at)
	{
		final Entry newest = entries.peekLast();
		if (newest != null && newest.at == at)
		{
			return newest;
		}
		final Entry entry = new Entry(at);
		entries.addLast(entry);
		return entry;
	}

	/**
	 * Drops the oldest entries while they are more than {@link #MAX_AGE_MILLIS} before {@code now}, then the oldest
	 * beyond {@link #MAX_ENTRIES}. Only from the front, so each addition costs little; an entry left behind by a clock
	 * that went back goes with the others once it reaches the front.
	 */
	private void prune(long now)
	{
		if (now > Long.MIN_VALUE + MAX_AGE_MILLIS)
		{
			final long oldest = now - MAX_AGE_MILLIS;
			while (!entries.isEmpty() && entries.peekFirst().at < oldest)
			{
				entries.removeFirst();
			}
		}
		while (entries.size() > MAX_ENTRIES)
		{
			entries.removeFirst();
		}
	}
}
