package com.shmayvan.slayerledger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.InterfaceID;

/**
 * Decides which game ticks, and which items, are left out of supply counting. Pure: the plugin passes in the tick
 * numbers and events it sees (on the client thread) and asks before each supplies diff. Not thread-safe.
 *
 * <p>Rules:</p>
 * <ul>
 *     <li>While an excluded interface (see {@link #isExcludedInterface}) is open, and for {@link #INTERFACE_TICKS}
 *     ticks after it was last seen open, opened or closed, the whole tick is excluded.</li>
 *     <li>Dropping a rune pouch excludes the whole tick the same way, because the runes inside leave with it.</li>
 *     <li>For {@link #DEATH_TICKS} ticks after the local player dies the whole tick is excluded (items move to the
 *     gravestone on respawn).</li>
 *     <li>An item on which "Drop" was clicked {@link #DROP_WINDOW_TICKS} ticks ago or less is not a supply.</li>
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

	/** Tick on which "Drop" was last clicked, per item id; pruned by {@link #beginTick}. */
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
		return tick - exclusionTick <= INTERFACE_TICKS || tick - deathTick <= DEATH_TICKS;
	}

	/** A copy of the item ids dropped within the window, as of the last {@link #beginTick}. */
	Set<Integer> droppedItems()
	{
		return new HashSet<>(dropTicks.keySet());
	}

	/** Forgets everything (login, hop, plugin start): the tick count is re-sent and may restart. */
	void reset()
	{
		dropTicks.clear();
		exclusionTick = NEVER_TICK;
		deathTick = NEVER_TICK;
	}
}
