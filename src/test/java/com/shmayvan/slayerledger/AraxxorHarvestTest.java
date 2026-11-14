package com.shmayvan.slayerledger;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.client.util.Filepath;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Araxxor's harvest after its task-completing kill. Araxxor drops its loot only when the player harvests its
 * corpse, which can come after the task was saved. The harvest window ({@link SessionTracker#takeHarvestRecord}) opens
 * when a completion whose final kill was paired with Araxxor's death is released at the end of its grace window, and
 * takes one Araxxor loot of the same account within {@link SessionTracker#ARAXXOR_LOOT_MILLIS} of that kill, for the
 * task's record; the record takes it with {@link LedgerRecord#addLateLoot}, through {@link LedgerStore#addLoot}.
 * {@link HarnessAraxxorHarvestTest} covers the plugin's wiring.
 */
public class AraxxorHarvestTest
{
	private static final long ACCOUNT = 555L;
	private static final long OTHER_ACCOUNT = 556L;
	private static final int ARAXYTES = 105;
	private static final long MINUTE = 60_000L;
	private static final int WHIP = 4151;
	private static final int BONES = 526;
	private static final int UNPRICED = 9999;

	/** Whip 1.5M GE / 72K HA, bones 100 GE / 0 HA, coins at face value; {@link #UNPRICED} has no GE price yet. */
	private static final ItemValuation.PriceLookup PRICES = lookup(100);
	/** As {@link #PRICES}, but bones cost 150 now. */
	private static final ItemValuation.PriceLookup LATER_PRICES = lookup(150);

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private long now;
	private SessionTracker tracker;

	@Before
	public void setUp()
	{
		now = 1_000_000L;
		tracker = new SessionTracker(() -> now);
		tracker.setAccount(ACCOUNT);
		observe(3, false);
	}

	private static ItemValuation.PriceLookup lookup(long bonesPrice)
	{
		return new ItemValuation.PriceLookup()
		{
			@Override
			public int canonicalize(int itemId)
			{
				return itemId;
			}

			@Override
			public long gePrice(int canonicalId)
			{
				return canonicalId == WHIP ? 1_500_000 : canonicalId == BONES ? bonesPrice : 0;
			}

			@Override
			public boolean isTradeable(int canonicalId)
			{
				return true;
			}

			@Override
			public long haPrice(int canonicalId)
			{
				return canonicalId == WHIP ? 72_000 : 0;
			}
		};
	}

	private static ItemTally tally(int... idQuantity)
	{
		final ItemTally tally = new ItemTally();
		for (int i = 0; i < idQuantity.length; i += 2)
		{
			tally.add(idQuantity[i], idQuantity[i + 1]);
		}
		return tally;
	}

	/** The task count reads {@code remaining}, with the NPCs named {@code paired} dying around it. */
	private SessionTracker.ObserveResult observe(int remaining, boolean completion, String... paired)
	{
		return tracker.observe("Araxytes", ARAXYTES, remaining, 3, TaskContext.NONE, LocationTally.NO_REGION, completion,
			paired.length == 0 ? KillPairing.NONE : KillPairing.of(now, Arrays.asList(paired)));
	}

	/**
	 * A 3-kill task: an araxyte, then a final kill with {@code finalPaired} dying around it, held for late loot.
	 *
	 * @return the held session's id
	 */
	private String completeAndHold(String... finalPaired)
	{
		now += MINUTE;
		observe(2, false, "Araxyte");
		assertEquals(SessionTracker.State.RUNNING, tracker.getState());
		now += MINUTE;
		assertEquals(SessionTracker.ObserveResult.COMPLETED, observe(0, true, finalPaired));
		final String id = tracker.getSessionId();
		assertTrue(tracker.holdCompletion());
		return id;
	}

	/** Lets the grace window pass: the completion is released for saving. */
	private SessionTracker.Summary release()
	{
		SessionTracker.Summary summary = null;
		for (int i = 0; i < SessionTracker.COMPLETION_LOOT_GRACE_TICKS; i++)
		{
			summary = tracker.tickCompletion();
		}
		assertNotNull(summary);
		return summary;
	}

	// ---- The harvest window ----

	@Test
	public void theHarvestOfAnAraxxorFinalKillIsForItsRecordOnce()
	{
		final String id = completeAndHold("Araxxor");
		assertNull("not while held: the held task takes it", tracker.takeHarvestRecord(ACCOUNT, "Araxxor"));
		assertEquals(id, release().getSessionId());

		now += MINUTE;
		assertNull("another NPC's loot is not the harvest", tracker.takeHarvestRecord(ACCOUNT, "Araxyte"));
		assertNull("loot of an unknown NPC neither", tracker.takeHarvestRecord(ACCOUNT, null));
		assertEquals("Araxxor's, a minute after the kill", id, tracker.takeHarvestRecord(ACCOUNT, "araxxor"));
		assertNull("once per harvest", tracker.takeHarvestRecord(ACCOUNT, "Araxxor"));
	}

	@Test
	public void theWindowLastsFiveMinutesFromTheKill()
	{
		final String id = completeAndHold("Araxxor");
		release();
		now += SessionTracker.ARAXXOR_LOOT_MILLIS;
		assertEquals("at 5 minutes", id, tracker.takeHarvestRecord(ACCOUNT, "Araxxor"));

		setUp();
		completeAndHold("Araxxor");
		release();
		now += SessionTracker.ARAXXOR_LOOT_MILLIS + 1;
		assertNull("after 5 minutes", tracker.takeHarvestRecord(ACCOUNT, "Araxxor"));
	}

	@Test
	public void onlyTheSameAccountsHarvestWithNoTaskKillSince()
	{
		completeAndHold("Araxxor");
		release();
		now += MINUTE;
		assertNull("another account", tracker.takeHarvestRecord(OTHER_ACCOUNT, "Araxxor"));
		assertNull("which ended the window", tracker.takeHarvestRecord(ACCOUNT, "Araxxor"));

		setUp();
		completeAndHold("Araxxor");
		release();
		// A new task, and a kill on it: the corpse is long gone.
		now += MINUTE;
		tracker.observe("Kurask", 47, 20, 20, false);
		now += MINUTE;
		tracker.observe("Kurask", 47, 19, 20, TaskContext.NONE, LocationTally.NO_REGION, false, KillPairing.NONE);
		assertEquals(SessionTracker.State.RUNNING, tracker.getState());
		assertNull(tracker.takeHarvestRecord(ACCOUNT, "Araxxor"));
	}

	@Test
	public void noWindowWhenTheFinalKillWasNotAraxxors()
	{
		// Araxxor died around an earlier kill; the final kill was an araxyte's.
		now += MINUTE;
		observe(2, false, "Araxxor");
		now += MINUTE;
		assertEquals(SessionTracker.ObserveResult.COMPLETED, observe(0, true, "Araxyte"));
		assertTrue(tracker.holdCompletion());
		release();
		now += MINUTE;
		assertNull(tracker.takeHarvestRecord(ACCOUNT, "Araxxor"));

		// Nothing paired at all (a kill nobody saw die).
		setUp();
		completeAndHold();
		release();
		now += MINUTE;
		assertNull(tracker.takeHarvestRecord(ACCOUNT, "Araxxor"));
	}

	@Test
	public void araxxorsDeathOnTheTickAfterTheFinalKillCountsToo()
	{
		final String id = completeAndHold();
		final long killAt = tracker.getLastKillAt();
		tracker.pairKill(killAt, KillPairing.of(now, Collections.singletonList("Araxxor")));
		release();
		now += MINUTE;
		assertEquals(id, tracker.takeHarvestRecord(ACCOUNT, "Araxxor"));
	}

	@Test
	public void aHarvestInTheGraceWindowGoesToTheHeldTaskAndOpensNoWindow()
	{
		completeAndHold("Araxxor");
		assertTrue(tracker.addLoot(tally(WHIP, 1), null, "Araxxor"));
		final SessionTracker.Summary summary = release();
		assertEquals(1, summary.getLoot().get(WHIP));
		now += MINUTE;
		assertNull(tracker.takeHarvestRecord(ACCOUNT, "Araxxor"));
	}

	@Test
	public void aHarvestBeforeTheCompletionIsHeldOpensNoWindow()
	{
		// The count reads 0 before the completion confirms it; the harvest comes in between.
		now += MINUTE;
		observe(2, false, "Araxyte");
		now += MINUTE;
		assertEquals(SessionTracker.ObserveResult.TASK_CLEARED, observe(0, false, "Araxxor"));
		now += 600;
		assertTrue("the running session takes it", tracker.addLoot(tally(WHIP, 1), null, "Araxxor"));
		now += 600;
		assertEquals(SessionTracker.ObserveResult.COMPLETED, observe(0, true));
		assertTrue(tracker.holdCompletion());
		assertEquals(1, release().getLoot().get(WHIP));
		now += MINUTE;
		assertNull("its record already has it", tracker.takeHarvestRecord(ACCOUNT, "Araxxor"));
	}

	@Test
	public void aHarvestWithNoItemsKeptOpensNoWindow()
	{
		// All of it crushed or ignored: the plugin passes it on empty.
		completeAndHold("Araxxor");
		assertFalse(tracker.addLoot(new ItemTally(), null, "Araxxor"));
		release();
		now += MINUTE;
		assertNull(tracker.takeHarvestRecord(ACCOUNT, "Araxxor"));

		// The same before the completion is held.
		setUp();
		now += MINUTE;
		observe(2, false, "Araxyte");
		now += MINUTE;
		observe(0, false, "Araxxor");
		assertFalse(tracker.addLoot(new ItemTally(), null, "Araxxor"));
		assertEquals(SessionTracker.ObserveResult.COMPLETED, observe(0, true));
		assertTrue(tracker.holdCompletion());
		release();
		now += MINUTE;
		assertNull(tracker.takeHarvestRecord(ACCOUNT, "Araxxor"));
	}

	@Test
	public void anEarlierAraxxorsHarvestLeavesTheWindowOpen()
	{
		// Araxxor's loot before the final kill was another Araxxor's.
		assertFalse(tracker.addLoot(tally(WHIP, 1), null, "Araxxor"));
		final String id = completeAndHold("Araxxor");
		assertFalse("another NPC's loot is no harvest", tracker.addLoot(new ItemTally(), null, "Araxyte"));
		release();
		now += MINUTE;
		assertEquals(id, tracker.takeHarvestRecord(ACCOUNT, "Araxxor"));
	}

	@Test
	public void anEarlyReleaseEndsTheWindow()
	{
		// Released early (a logout, hop or account change during the grace window): no window.
		completeAndHold("Araxxor");
		assertNotNull(tracker.takePendingCompletion());
		now += MINUTE;
		assertNull(tracker.takeHarvestRecord(ACCOUNT, "Araxxor"));

		// The same paths end an open window, also with nothing held.
		setUp();
		completeAndHold("Araxxor");
		release();
		assertNull(tracker.takePendingCompletion());
		now += MINUTE;
		assertNull(tracker.takeHarvestRecord(ACCOUNT, "Araxxor"));
	}

	// ---- The record ----

	/** A completed record with 2 bones, valued at {@link #PRICES}. */
	private static LedgerRecord valuedRecord()
	{
		final LedgerRecord record = new LedgerRecord();
		record.setId("r1");
		record.setMonster("Araxytes");
		record.setCompleted(true);
		record.setLoot(tally(BONES, 2).toMap());
		record.captureValues(PRICES);
		return record;
	}

	/** The harvest: a whip (notable), 3 bones, 5,000 coins and an item without a GE price yet. */
	private static ItemTally harvest()
	{
		return tally(WHIP, 1, BONES, 3, ItemValuation.COINS, 5_000, UNPRICED, 1);
	}

	@Test
	public void lateLootJoinsTheRecordAsIfItCameBeforeTheSave()
	{
		final LedgerRecord record = valuedRecord();
		assertEquals(Long.valueOf(200), record.getLootGeValue());
		final ItemValuation.UnitPrices prices = ItemValuation.capturePrices(harvest(), LATER_PRICES);
		final List<NotableDrop> drops = NotableDrops.find(harvest(), prices, LootPriceMode.GE, 100_000,
			itemId -> itemId == WHIP ? "Abyssal whip" : null, now);
		assertEquals(1, drops.size());

		assertTrue(record.addLateLoot(harvest(), prices, drops));
		final Map<Integer, Long> loot = new TreeMap<>();
		loot.put(BONES, 5L);
		loot.put(ItemValuation.COINS, 5_000L);
		loot.put(WHIP, 1L);
		loot.put(UNPRICED, 1L);
		assertEquals(loot, record.getLoot());
		assertEquals("the saved price is kept", Long.valueOf(100), record.getUnitGePrices().get(BONES));
		assertEquals(Long.valueOf(1_500_000), record.getUnitGePrices().get(WHIP));
		assertFalse(record.getUnitGePrices().containsKey(UNPRICED));
		assertEquals(Long.valueOf(72_000), record.getUnitHaPrices().get(WHIP));
		assertEquals(Long.valueOf(5 * 100 + 5_000 + 1_500_000), record.getLootGeValue());
		assertEquals(Long.valueOf(5_000 + 72_000), record.getLootHaValue());
		assertEquals(Integer.valueOf(1), record.getLootUnpricedItems());
		assertEquals(drops, record.getNotableDrops());
		assertNull("no supplies were recorded", record.getSuppliesGeValue());

		assertFalse("nothing to add", record.addLateLoot(new ItemTally(), prices, null));
	}

	@Test
	public void aRecordSavedWithoutValuesGetsTheItemsAndNotableDropsNoPrices()
	{
		final LedgerRecord record = new LedgerRecord();
		record.setId("r1");
		record.setLoot(tally(BONES, 2).toMap());
		final ItemValuation.UnitPrices prices = ItemValuation.capturePrices(tally(WHIP, 1), PRICES);
		final List<NotableDrop> drops = NotableDrops.find(tally(WHIP, 1), prices, LootPriceMode.GE, 100_000,
			itemId -> itemId == WHIP ? "Abyssal whip" : null, now);
		assertEquals(1, drops.size());
		assertTrue(record.addLateLoot(tally(WHIP, 1), prices, drops));
		assertEquals(Long.valueOf(1), record.getLoot().get(WHIP));
		assertEquals(Long.valueOf(2), record.getLoot().get(BONES));
		assertEquals("the notable drop is kept", drops, record.getNotableDrops());
		assertNull(record.getLootGeValue());
		assertNull(record.getLootHaValue());
		assertNull(record.getUnitGePrices());
		assertNull(record.getUnitHaPrices());
	}

	@Test
	public void theStoreAddsLateLootToThatRecordOnly() throws IOException
	{
		final Gson gson = new Gson();
		final File dir = new File(temp.getRoot(), "slayer-ledger");
		final LedgerStore store = new LedgerStore(Filepath.Unchecked.getRooted(dir.toPath()), gson);
		final LedgerRecord other = valuedRecord();
		other.setId("other");
		store.append(ACCOUNT, other);
		store.append(ACCOUNT, valuedRecord());
		final File ledger = new File(dir, ACCOUNT + ".json");

		final ItemValuation.UnitPrices prices = ItemValuation.capturePrices(tally(WHIP, 1), PRICES);
		final List<LedgerRecord> records = store.addLoot(ACCOUNT, "r1", tally(WHIP, 1), prices, null);
		assertEquals(records, store.load(ACCOUNT));
		assertEquals(other, records.get(0));
		assertEquals(Long.valueOf(1), records.get(1).getLoot().get(WHIP));
		assertEquals(Long.valueOf(200 + 1_500_000), records.get(1).getLootGeValue());

		final String before = new String(Files.readAllBytes(ledger.toPath()), StandardCharsets.UTF_8);
		assertEquals(2, store.addLoot(ACCOUNT, "missing", tally(WHIP, 1), prices, null).size());
		assertEquals("a missing record changes nothing", before,
			new String(Files.readAllBytes(ledger.toPath()), StandardCharsets.UTF_8));
	}
}
