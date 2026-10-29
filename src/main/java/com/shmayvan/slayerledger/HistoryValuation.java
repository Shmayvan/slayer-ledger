package com.shmayvan.slayerledger;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * How the shown history is valued (split from {@link LedgerHistoryModel}): the "Loot price" mode, today's GE
 * prices received and asked for, the wait for them and its expiry, and {@link #valuationVersion}, bumped whenever
 * any of these changes what the history is worth. Saved tasks are always valued at today's prices (the rules
 * are {@link HistoryPricing}'s).
 *
 * <p>Threading: EDT only, like the model that owns it; the exception is {@link #isWaitingForCurrentPrices}, which reads
 * one volatile field. It only keeps the state: the model decides what a change means (dropping the statistics,
 * re-checking the chat preview) and tells its listener. Callers write through the model.</p>
 */
final class HistoryValuation
{
	/**
	 * How long the views valued at today's prices wait for the prices asked for before they are redrawn with the
	 * prices there are.
	 */
	static final long CURRENT_PRICES_WAIT_MILLIS = 3_000;

	/** Asks the plugin for today's GE prices of item ids (EDT). */
	private final Consumer<Set<Integer>> currentPriceRequests;
	/** How loot is valued (the "Loot price" setting). */
	private LootPriceMode lootPriceMode;
	/**
	 * Today's GE unit prices received from the plugin this session, by item id as tallied; items whose price was
	 * unavailable have no entry (and are asked for again when the records change).
	 */
	private final Map<Integer, Long> currentPrices = new HashMap<>();
	/** {@link #currentPrices}, read-only, as handed to the views. */
	private final Map<Integer, Long> shownPrices = Collections.unmodifiableMap(currentPrices);
	/** Ids asked for whose answer has not arrived yet, so they are not asked for twice. */
	private final Set<Integer> currentPricesPending = new HashSet<>();
	/**
	 * While the views wait for today's prices: ids whose answer came back without a price and that the plugin is
	 * looking up again ({@link PriceRetry}), for example because RuneLite's price list had not loaded yet at client
	 * start. The wait goes on until they arrive too, or the wait runs out; emptied whenever the wait ends.
	 */
	private final Set<Integer> currentPricesRetrying = new HashSet<>();
	/**
	 * Whether the views valued at today's prices keep what they show until the prices asked for arrive, instead of
	 * briefly showing {@code ~} and lower totals for the items not priced yet. Never longer than
	 * {@link #CURRENT_PRICES_WAIT_MILLIS}. Written on the EDT only; volatile so the plugin may check it on the client
	 * thread ({@link #isWaitingForCurrentPrices}).
	 */
	private volatile boolean waitingForCurrentPrices;
	/** When {@link #waitingForCurrentPrices} began ({@link System#nanoTime}). */
	private long waitingForCurrentPricesSince;
	/**
	 * Loot items left out of the loot value, worked out by the model from Ground Items' hidden list and the
	 * item names.
	 */
	private HiddenLoot hiddenLoot = HiddenLoot.NONE;
	/** Bumped whenever how the history is valued changes: the mode, today's prices or the hidden loot. */
	private long valuationVersion;

	/**
	 * @param lootPriceMode        the "Loot price" mode; {@code null} is GE
	 * @param currentPriceRequests asks the plugin for today's GE prices of item ids (EDT)
	 */
	HistoryValuation(LootPriceMode lootPriceMode, Consumer<Set<Integer>> currentPriceRequests)
	{
		this.lootPriceMode = lootPriceMode == null ? LootPriceMode.GE : lootPriceMode;
		this.currentPriceRequests = currentPriceRequests;
	}

	LootPriceMode lootPriceMode()
	{
		return lootPriceMode;
	}

	/** Sets the "Loot price" mode ({@code null} is GE); returns whether it changed, which bumps the version. */
	boolean setLootPriceMode(LootPriceMode mode)
	{
		LootPriceMode newMode = mode == null ? LootPriceMode.GE : mode;
		if (newMode == lootPriceMode)
		{
			return false;
		}
		lootPriceMode = newMode;
		valuationVersion++;
		return true;
	}

	/** Loot items left out of the loot value; never {@code null}. */
	HiddenLoot hiddenLoot()
	{
		return hiddenLoot;
	}

	/**
	 * Sets the loot items left out of the loot value ({@code null} is none); returns whether they changed, which bumps
	 * the version.
	 */
	boolean setHiddenLoot(HiddenLoot hidden)
	{
		HiddenLoot h = hidden == null ? HiddenLoot.NONE : hidden;
		if (h.equals(hiddenLoot))
		{
			return false;
		}
		hiddenLoot = h;
		valuationVersion++;
		return true;
	}

	/**
	 * Today's GE prices to value the history at: read-only, never {@code null}, and changing as prices arrive (copy it
	 * to keep it). An item without an entry has no price yet, so it is left out and its total marked {@code ~}.
	 */
	Map<Integer, Long> shownPrices()
	{
		return shownPrices;
	}

	/**
	 * Asks the plugin (once, in one batch) for today's GE price of every item in {@code records} that has none yet and
	 * is not already asked for.
	 */
	void requestCurrentPrices(List<LedgerRecord> records)
	{
		Set<Integer> ids = HistoryPricing.itemIds(records);
		ids.removeAll(currentPrices.keySet());
		ids.removeAll(currentPricesPending);
		if (!ids.isEmpty())
		{
			currentPricesPending.addAll(ids);
			currentPriceRequests.accept(ids);
		}
	}

	/**
	 * Adds today's GE prices the plugin looked up ({@code prices}, for those of {@code requested} that it found);
	 * {@code requested} is no longer pending. While the views wait for prices, the ids of {@code retrying} (those the
	 * plugin looks up again, {@link PriceRetry}) keep the wait going until they arrive ({@link #isWaitOver}). Bumps
	 * the version, so the cached views are worked out again, only when some price is new or changed; returns whether
	 * it did.
	 *
	 * @param retrying ids of {@code requested} that came back without a price and are looked up again; may be empty
	 */
	boolean addCurrentPrices(Set<Integer> requested, Map<Integer, Long> prices, Set<Integer> retrying)
	{
		currentPricesPending.removeAll(requested);
		currentPricesRetrying.removeAll(prices.keySet());
		if (waitingForCurrentPrices)
		{
			for (Integer id : retrying)
			{
				if (!prices.containsKey(id))
				{
					currentPricesRetrying.add(id);
				}
			}
		}
		boolean changed = false;
		for (Map.Entry<Integer, Long> e : prices.entrySet())
		{
			Long old = currentPrices.put(e.getKey(), e.getValue());
			changed |= !Objects.equals(e.getValue(), old);
		}
		if (changed)
		{
			valuationVersion++;
		}
		return changed;
	}

	/** Whether some prices asked for have not arrived yet. */
	boolean isCurrentPricesPending()
	{
		return !currentPricesPending.isEmpty();
	}

	/**
	 * Whether a wait for today's prices may end: every price asked for has been answered, and none that came back
	 * unavailable is still being looked up again.
	 */
	boolean isWaitOver()
	{
		return currentPricesPending.isEmpty() && currentPricesRetrying.isEmpty();
	}

	/**
	 * After {@link #requestCurrentPrices}: whether the views valued at today's prices should keep what they show until
	 * the prices asked for arrive (some asked for are on their way, or, in a wait that already began, are being looked
	 * up again). Starts the wait, or keeps one that already began; ends it otherwise.
	 */
	boolean waitForCurrentPrices()
	{
		if (isWaitOver())
		{
			stopWaiting();
			return false;
		}
		if (!waitingForCurrentPrices)
		{
			waitingForCurrentPrices = true;
			waitingForCurrentPricesSince = System.nanoTime();
		}
		return true;
	}

	/**
	 * Whether the views valued at today's prices keep what they show until the prices asked for arrive. Any
	 * thread (one volatile read).
	 */
	boolean isWaitingForCurrentPrices()
	{
		return waitingForCurrentPrices;
	}

	/** Ends any wait for today's prices; the prices being looked up again no longer hold anything back. */
	void stopWaiting()
	{
		waitingForCurrentPrices = false;
		currentPricesRetrying.clear();
	}

	/**
	 * Whether a wait for today's prices has lasted {@link #CURRENT_PRICES_WAIT_MILLIS} (an answer never came, or a
	 * price looked up again was still unavailable); if so, the pending ids are forgotten so the missing prices may be
	 * asked for again, and the caller ends the wait.
	 */
	boolean currentPricesWaitExpired()
	{
		if (waitingForCurrentPrices && System.nanoTime() - waitingForCurrentPricesSince
			>= TimeUnit.MILLISECONDS.toNanos(CURRENT_PRICES_WAIT_MILLIS))
		{
			currentPricesPending.clear();
			currentPricesRetrying.clear();
			return true;
		}
		return false;
	}

	/** Test seam: lets a wait for today's prices run out at once, as if no answer came in time. */
	void expireCurrentPricesWait()
	{
		waitingForCurrentPricesSince -= TimeUnit.MILLISECONDS.toNanos(CURRENT_PRICES_WAIT_MILLIS);
	}

	/** Bumped whenever how the history is valued changes: the mode, today's prices or the hidden loot. */
	long valuationVersion()
	{
		return valuationVersion;
	}
}
