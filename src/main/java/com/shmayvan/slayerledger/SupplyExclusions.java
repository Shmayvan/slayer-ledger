package com.shmayvan.slayerledger;

import java.util.*;
import net.runelite.api.gameval.*;

/**
 * Decides which game ticks, and which items, are left out of supply counting. Pure: the plugin passes in the tick
 * numbers and events it sees (on the client thread) and asks before each supplies diff. Not thread-safe.
 *
 * <p>Rules:</p>
 * <ul>
 *     <li>While an excluded interface (see {@link #isExcludedInterface}) is open, and for {@link #INTERFACE_TICKS}
 *     ticks after it was last seen open, opened or closed, the whole tick is excluded. The plugin decides "open" from
 *     the interface's root widget being loaded and shown: the game can keep a closed interface's group loaded,
 *     which must not exclude anything.</li>
 *     <li>Dropping a rune pouch excludes the whole tick the same way, because the runes inside leave with it.</li>
 *     <li>For {@link #DEATH_TICKS} ticks after the local player dies the whole tick is excluded (items move to the
 *     gravestone on respawn).</li>
 *     <li>An item on which "Drop" was clicked {@link #DROP_WINDOW_TICKS} ticks ago or less is not a supply, and nor is a
 *     teleport scroll used on the master scroll book that recently (it is stored in the book, see
 *     {@link #useClicked}).</li>
 *     <li>Each window covers only ticks from the one it was recorded on: a tick before it (the tick count restarted)
 *     is outside it, so no window can last longer than its length. RuneLite 1.13.4's tick count keeps counting
 *     across logins and hops ({@code Hooks.tick()} is the only thing that changes it), but this does not rely on
 *     that.</li>
 * </ul>
 */
final class SupplyExclusions
{
	/** Ticks after an excluded interface was last seen open (or opened or closed) during which nothing is counted. */
	static final int INTERFACE_TICKS = 2;
	/** A decrease of an item is not a supply if "Drop" was clicked on that item this many ticks ago or less. */
	static final int DROP_WINDOW_TICKS = 2;
	/** Ticks after the local player dies during which nothing is counted. */
	static final int DEATH_TICKS = 10;
	/** Far enough in the past that no tick window above includes it, without overflowing tick arithmetic. */
	static final int NEVER_TICK = Integer.MIN_VALUE / 2;

	/** The bank, deposit box and group storage; also counted as bank trips (see {@link BankTripCounter}). */
	private static final int[] BANK_INTERFACES = {
		InterfaceID.BANKMAIN, InterfaceID.BANK_DEPOSITBOX, InterfaceID.SHARED_BANK
	};

	/**
	 * Interfaces that move items without using them (bank, deposit box, group storage, seed vault, Grand Exchange,
	 * shops, trade, Death's office and coffer).
	 */
	private static final int[] EXCLUDED_INTERFACES = {
		InterfaceID.BANKMAIN, InterfaceID.BANK_DEPOSITBOX, InterfaceID.SHARED_BANK, InterfaceID.SEED_VAULT,
		InterfaceID.GE_OFFERS, InterfaceID.GE_COLLECT, InterfaceID.SHOPMAIN, InterfaceID.TRADEMAIN,
		InterfaceID.TRADECONFIRM, InterfaceID.DEATH_OFFICE, InterfaceID.DEATH_COFFER
	};

	/**
	 * The master scroll book, empty and filled (RuneLite 1.13.4 {@code net.runelite.api.gameval.ItemID}
	 * {@code BOOKOFSCROLLS_EMPTY} and {@code BOOKOFSCROLLS_CHARGED}).
	 */
	static final Set<Integer> SCROLL_BOOKS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		ItemID.BOOKOFSCROLLS_EMPTY, ItemID.BOOKOFSCROLLS_CHARGED)));

	/** Tick on which "Drop" was last clicked (or a scroll stored), per item id; pruned by {@link #beginTick}. */
	private final Map<Integer, Integer> dropTicks = new HashMap<>();
	/** Last tick an excluded interface was open, opened or closed (or a rune pouch was dropped). */
	private int exclusionTick = NEVER_TICK;
	/** Tick the local player last died. */
	private int deathTick = NEVER_TICK;

	/** A copy of the bank interface group ids (the bank, deposit box and group storage), all of them excluded. */
	static int[] bankInterfaces()
	{
		return BANK_INTERFACES.clone();
	}

	/** A copy of the excluded interface group ids. */
	static int[] excludedInterfaces()
	{
		return EXCLUDED_INTERFACES.clone();
	}

	static boolean isExcludedInterface(int groupId)
	{
		for (int group : EXCLUDED_INTERFACES)
		{
			if (group == groupId)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * An interface opened (WidgetLoaded) or closed (WidgetClosed) on {@code tick}; excludes the tick if it is an
	 * excluded interface. Covers an interface opened and closed between two ticks, and items it moved that only
	 * arrive on the next tick.
	 */
	void interfaceChanged(int groupId, int tick)
	{
		if (isExcludedInterface(groupId))
		{
			exclusionTick = tick;
		}
	}

	/** "Drop" was clicked on {@code itemId} on {@code tick}; dropping a rune pouch excludes the whole tick. */
	void dropClicked(int itemId, boolean runePouch, int tick)
	{
		dropTicks.put(itemId, tick);
		if (runePouch)
		{
			exclusionTick = tick;
		}
	}

	/**
	 * An item was used on another on {@code tick} ({@code itemIds}: both items, in any order; other ids are ignored). A
	 * teleport scroll ({@link SupplyTracker#TELEPORT_SCROLLS}) used on the master scroll book, or the book on a scroll,
	 * is stored in the book (OSRS Wiki "Master scroll book": scrolls are stored by using them on it), so its decrease
	 * is not counted, as for a drop. The book's stored scrolls are not tracked.
	 */
	void useClicked(int[] itemIds, int tick)
	{
		if (itemIds == null)
		{
			return;
		}
		boolean book = false;
		for (int itemId : itemIds)
		{
			book |= SCROLL_BOOKS.contains(itemId);
		}
		if (!book)
		{
			return;
		}
		for (int itemId : itemIds)
		{
			if (SupplyTracker.TELEPORT_SCROLLS.contains(itemId))
			{
				dropTicks.put(itemId, tick);
			}
		}
	}

	/** The local player died on {@code tick}. */
	void playerDied(int tick)
	{
		deathTick = tick;
	}

	/**
	 * Start of a supplies diff on {@code tick}: records whether an excluded interface is open now and forgets drop
	 * clicks outside the {@link #DROP_WINDOW_TICKS} window (or from a later tick).
	 */
	void beginTick(int tick, boolean excludedInterfaceOpen)
	{
		if (excludedInterfaceOpen)
		{
			exclusionTick = tick;
		}
		dropTicks.values().removeIf(t -> t > tick || tick - t > DROP_WINDOW_TICKS);
	}

	/** Whether nothing may be counted on {@code tick} (an excluded interface or a rune pouch drop, or a death). */
	boolean isTickExcluded(int tick)
	{
		return within(tick, exclusionTick, INTERFACE_TICKS) || within(tick, deathTick, DEATH_TICKS);
	}

	/**
	 * Why {@code tick} is excluded, for the plugin's debug line about a long stretch of uncounted ticks; {@code null}
	 * when it is not.
	 */
	String exclusionReason(int tick)
	{
		if (within(tick, exclusionTick, INTERFACE_TICKS))
		{
			return "an excluded interface was open, opened or closed (or a rune pouch dropped) on tick "
				+ exclusionTick;
		}
		if (within(tick, deathTick, DEATH_TICKS))
		{
			return "the local player died on tick " + deathTick;
		}
		return null;
	}

	/**
	 * Whether {@code tick} is {@code from} or at most {@code length} ticks after it. A tick before {@code from} (the
	 * tick count restarted) is not.
	 */
	private static boolean within(int tick, int from, int length)
	{
		return tick >= from && (long) tick - from <= length;
	}

	/** A copy of the item ids dropped within the window, as of the last {@link #beginTick}. */
	Set<Integer> droppedItems()
	{
		return new HashSet<>(dropTicks.keySet());
	}

	/** Forgets everything (login, hop, lost connection, plugin start): nothing from before them is excluded after. */
	void reset()
	{
		dropTicks.clear();
		exclusionTick = NEVER_TICK;
		deathTick = NEVER_TICK;
	}
}
