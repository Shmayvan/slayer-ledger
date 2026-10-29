package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link NotableDrops} and {@link NotableDrop}: which loot stacks are notable at a threshold and price mode, the cap
 * per task, order, text, and how they go through the session, snapshots, records and JSON.
 */
public class NotableDropsTest
{
	private static final int WHIP = 4151;
	private static final int KEY = 23083;
	private static final int BAR = 2363;
	private static final int UNPRICED = 11840;
	private static final long TIME = 1_000L;

	private final Gson gson = new Gson();
	private ItemValuation.UnitPrices prices;

	@Before
	public void setUp()
	{
		prices = new ItemValuation.UnitPrices();
		prices.getGe().put(WHIP, 1_500_000L);
		prices.getHa().put(WHIP, 72_000L);
		// Untradeable: a GE price of 0, valued at its high alch value in GE mode.
		prices.getGe().put(KEY, 0L);
		prices.getHa().put(KEY, 120_000L);
		prices.getGe().put(BAR, 12_000L);
		prices.getHa().put(BAR, 7_500L);
		// GE price unavailable.
		prices.getGeMissing().add(UNPRICED);
		prices.getHa().put(UNPRICED, 300_000L);
	}

	private static ItemTally tally(long... idQuantityPairs)
	{
		ItemTally t = new ItemTally();
		for (int i = 0; i < idQuantityPairs.length; i += 2)
		{
			t.add((int) idQuantityPairs[i], idQuantityPairs[i + 1]);
		}
		return t;
	}

	private List<NotableDrop> find(ItemTally drop, LootPriceMode mode, long threshold)
	{
		return NotableDrops.find(drop, prices, mode, threshold, id -> "Item " + id, TIME);
	}

	private static NotableDrop item(int id, long value, long time)
	{
		return NotableDrop.item(id, "Item " + id, 1, value, value, time);
	}

	@Test
	public void stackAtTheThresholdIsNotable()
	{
		assertEquals(1, find(tally(BAR, 25), LootPriceMode.GE, 300_000).size());
		assertTrue("1 gp below", find(tally(BAR, 25), LootPriceMode.GE, 300_001).isEmpty());
		NotableDrop bars = find(tally(BAR, 25), LootPriceMode.GE, 300_000).get(0);
		assertEquals(Integer.valueOf(BAR), bars.getItemId());
		assertEquals("Item " + BAR, bars.getName());
		assertEquals(25, bars.getQuantity());
		assertEquals(Long.valueOf(300_000), bars.getGeValue());
		assertEquals(Long.valueOf(187_500), bars.getHaValue());
		assertEquals(TIME, bars.getTime());
		assertFalse(bars.isPet());
	}

	@Test
	public void valuedInTheLootPriceMode()
	{
		List<NotableDrop> ge = find(tally(WHIP, 1, KEY, 1, BAR, 1, UNPRICED, 1), LootPriceMode.GE, 100_000);
		assertEquals("whip, and the untradeable key at its high alch value; not the unpriced item", 2, ge.size());
		assertEquals(Integer.valueOf(WHIP), ge.get(0).getItemId());
		assertEquals(Integer.valueOf(KEY), ge.get(1).getItemId());
		assertEquals(Long.valueOf(120_000), ge.get(1).getGeValue());

		List<NotableDrop> ha = find(tally(WHIP, 1, KEY, 1, BAR, 1, UNPRICED, 1), LootPriceMode.HIGH_ALCH, 100_000);
		assertEquals(2, ha.size());
		// In item id order.
		assertEquals(Integer.valueOf(UNPRICED), ha.get(0).getItemId());
		assertNull("GE value unknown", ha.get(0).getGeValue());
		assertEquals(Long.valueOf(300_000), ha.get(0).getHaValue());
		assertEquals(Integer.valueOf(KEY), ha.get(1).getItemId());
	}

	@Test
	public void nothingFoundForEmptyInput()
	{
		assertTrue(find(null, LootPriceMode.GE, 1).isEmpty());
		assertTrue(find(new ItemTally(), LootPriceMode.GE, 1).isEmpty());
		assertTrue(NotableDrops.find(tally(WHIP, 1), null, LootPriceMode.GE, 1, null, TIME).isEmpty());
		assertNull("names are optional", NotableDrops.find(tally(WHIP, 1), prices, null, 1, null, TIME).get(0)
			.getName());
	}

	@Test
	public void thresholdIsAtLeastOneGp()
	{
		assertEquals(1, NotableDrops.threshold(0));
		assertEquals(1, NotableDrops.threshold(-5));
		assertEquals(100_000, NotableDrops.threshold(100_000));
		prices.getGe().put(BAR, 0L);
		prices.getHa().put(BAR, 0L);
		assertTrue("a worthless item is never notable", find(tally(BAR, 1), LootPriceMode.GE, 0).isEmpty());
	}

	@Test
	public void stackValueSaturates()
	{
		assertEquals(Long.valueOf(Long.MAX_VALUE), NotableDrops.stackValue(Long.MAX_VALUE / 2, 3));
		assertNull(NotableDrops.stackValue(null, 3));
		assertNull(NotableDrops.stackValue(5L, 0));
		assertEquals(Long.valueOf(0), NotableDrops.stackValue(0L, 3));
	}

	@Test
	public void capKeepsTheMostValuableAndEveryPet()
	{
		List<NotableDrop> drops = new ArrayList<>();
		for (int i = 0; i < NotableDrops.MAX_PER_TASK; i++)
		{
			drops.add(item(1000 + i, 100_000 + i, i));
		}
		NotableDrop pet = NotableDrop.pet(NotableDrop.Pet.RECEIVED, 50);
		List<NotableDrop> withPet = NotableDrops.add(drops, Collections.singletonList(pet));
		assertEquals(NotableDrops.MAX_PER_TASK, withPet.size());
		assertEquals("the cheapest item made way", Integer.valueOf(1001), withPet.get(0).getItemId());
		assertEquals("added last", pet, withPet.get(withPet.size() - 1));

		List<NotableDrop> cheap = NotableDrops.add(withPet, Collections.singletonList(item(9, 1, 60)));
		assertEquals("a drop worth less than all kept ones is left out", withPet, cheap);

		// Among equal values the oldest goes first.
		List<NotableDrop> equal = new ArrayList<>();
		for (int i = 0; i <= NotableDrops.MAX_PER_TASK; i++)
		{
			equal.add(item(2000 + i, 5, i));
		}
		List<NotableDrop> bounded = NotableDrops.sanitize(equal);
		assertEquals(NotableDrops.MAX_PER_TASK, bounded.size());
		assertEquals(Integer.valueOf(2001), bounded.get(0).getItemId());

		// Pets above an item at the largest value.
		assertTrue(NotableDrops.rank(pet) > NotableDrops.rank(item(1, Long.MAX_VALUE, 0)));
	}

	@Test
	public void sanitizeDropsNullsAndNewestFirstOrdersByTime()
	{
		NotableDrop a = item(1, 200_000, 10);
		NotableDrop b = item(2, 300_000, 30);
		NotableDrop c = item(3, 400_000, 30);
		NotableDrop d = item(4, 500_000, 20);
		assertEquals(Arrays.asList(a, b), NotableDrops.sanitize(Arrays.asList(a, null, b)));
		assertTrue(NotableDrops.sanitize(null).isEmpty());
		assertEquals("equal times: the later-added first", Arrays.asList(c, b, d, a),
			NotableDrops.newestFirst(Arrays.asList(a, b, c, d)));
	}

	@Test
	public void petAndValues()
	{
		NotableDrop pet = NotableDrop.pet(null, 5);
		assertTrue(pet.isPet());
		assertEquals(NotableDrop.Pet.RECEIVED, pet.getPet());
		assertNull(pet.getItemId());
		assertNull(pet.value(LootPriceMode.GE));
		NotableDrop whip = NotableDrop.item(WHIP, "Abyssal whip", 1, 1_500_000L, 72_000L, 5);
		assertEquals(Long.valueOf(1_500_000), whip.value(null));
		assertEquals(Long.valueOf(72_000), whip.value(LootPriceMode.HIGH_ALCH));
	}

	@Test
	public void formatting()
	{
		NotableDrop whip = NotableDrop.item(WHIP, "Abyssal whip", 1, 1_500_000L, 72_000L, 5);
		assertEquals("Abyssal whip: 1.5M", LedgerHistory.formatNotableDrop(whip, LootPriceMode.GE, false));
		assertEquals("Abyssal whip: 72,000 gp", LedgerHistory.formatNotableDrop(whip, LootPriceMode.HIGH_ALCH, true));
		NotableDrop bars = NotableDrop.item(BAR, "Rune bar", 1500, 18_000_000L, null, 5);
		assertEquals("Rune bar x 1,500: 18M", LedgerHistory.formatNotableDrop(bars, LootPriceMode.GE, false));
		assertEquals("Rune bar x 1,500: -", LedgerHistory.formatNotableDrop(bars, LootPriceMode.HIGH_ALCH, false));
		assertEquals("Item 7: 5", LedgerHistory.formatNotableDrop(NotableDrop.item(7, null, 1, 5L, 5L, 0),
			LootPriceMode.GE, false));
		assertEquals("Pet", LedgerHistory.formatNotableDrop(NotableDrop.pet(NotableDrop.Pet.RECEIVED, 0), null,
			false));
		assertEquals("Pet (already owned)", LedgerHistory.formatNotableDrop(
			NotableDrop.pet(NotableDrop.Pet.DUPLICATE, 0), null, true));
	}

	@Test
	public void liveLinesAndTooltip()
	{
		ItemTally loot = tally(KEY, 2, ClueScrollIds.HARD[0], 1, ClueScrollIds.ELITE[0], 2);
		List<NotableDrop> drops = Arrays.asList(item(1, 100_000, 1), item(2, 200_000, 2),
			NotableDrop.item(3, "A <b>", 1, 300_000L, 1L, 3), item(4, 400_000, 4));
		assertEquals(Arrays.asList("Keys: 2 brimstone", "Clues: 1 hard, 2 elite", "Notable drops:", "  Item 4: 400K",
			"  A <b>: 300K", "  Item 2: 200K", "  +1 more"),
			LedgerHistory.liveTaskLootLines(KeysAndClues.of(loot), drops, LootPriceMode.GE));
		assertTrue(LedgerHistory.liveTaskLootLines(KeysAndClues.NONE, null, LootPriceMode.GE).isEmpty());
		assertEquals("<br>Notable drops:<br>&nbsp;&nbsp;Item 4: 400,000 gp<br>&nbsp;&nbsp;A &lt;b&gt;: 300,000 gp"
				+ "<br>&nbsp;&nbsp;Item 2: 200,000 gp<br>&nbsp;&nbsp;Item 1: 100,000 gp",
			LedgerHistory.notableDropsTooltip(drops, LootPriceMode.GE));
		assertEquals("", LedgerHistory.notableDropsTooltip(Collections.emptyList(), LootPriceMode.GE));
	}

	@Test
	public void historyTooltipShowsOnlyKnownData()
	{
		LedgerRecord old = new LedgerRecord();
		assertEquals("no loot, no drops: no lines", "", LedgerHistory.taskLootTooltip(old, LootPriceMode.GE));
		old.setLoot(Collections.singletonMap(BAR, 5L));
		assertEquals("", LedgerHistory.taskLootTooltip(old, LootPriceMode.GE));
		old.setLoot(Collections.singletonMap(KEY, 1L));
		old.setNotableDrops(Collections.singletonList(NotableDrop.pet(NotableDrop.Pet.RECEIVED, 1)));
		assertEquals("<br>Keys: 1 brimstone<br>Notable drops:<br>&nbsp;&nbsp;Pet",
			LedgerHistory.taskLootTooltip(old, LootPriceMode.GE));
	}

	@Test
	public void sessionKeepsDropsOnlyWhileRunningAndHandsThemOn()
	{
		long[] now = {1_000_000L};
		SessionTracker tracker = new SessionTracker(() -> now[0]);
		tracker.observe("Kraken", 42, 10, 10, TaskContext.NONE, false);
		NotableDrop whip = item(WHIP, 1_500_000, 1);
		NotableDrop pet = NotableDrop.pet(NotableDrop.Pet.RECEIVED, 2);
		assertFalse("idle", tracker.isRecordingLoot());
		assertFalse(tracker.addLoot(tally(WHIP, 1), Collections.singletonList(whip)));
		assertFalse(tracker.addPet(pet));
		assertTrue(tracker.play(555L));
		assertTrue(tracker.isRecordingLoot());
		assertTrue(tracker.addLoot(tally(WHIP, 1), Collections.singletonList(whip)));
		assertTrue(tracker.addPet(pet));
		assertFalse(tracker.addPet(null));
		assertFalse("no loot, no drops", tracker.addLoot(new ItemTally(), Collections.singletonList(whip)));
		tracker.pause();
		assertFalse(tracker.isRecordingLoot());
		assertFalse(tracker.addLoot(tally(WHIP, 1), Collections.singletonList(whip)));
		assertFalse(tracker.addPet(pet));
		assertEquals(Arrays.asList(whip, pet), tracker.getNotableDrops());

		// Through a snapshot (JSON) and a restore.
		SessionSnapshot snapshot = SessionSnapshot.fromSummary(tracker.summary(), null, now[0]);
		SessionSnapshot read = gson.fromJson(gson.toJson(snapshot), SessionSnapshot.class);
		assertEquals(Arrays.asList(whip, pet), read.getNotableDrops());
		assertEquals(Arrays.asList(whip, pet), read.toSummary().getNotableDrops());
		SessionTracker restored = new SessionTracker(() -> now[0]);
		restored.observe("Kraken", 42, 10, 10, TaskContext.NONE, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, restored.restore(read, null));
		assertEquals(Arrays.asList(whip, pet), restored.getNotableDrops());

		// Into a record and back from JSON.
		LedgerRecord record = LedgerRecord.fromSummary(tracker.summary(), true);
		LedgerRecord readRecord = gson.fromJson(gson.toJson(record), LedgerRecord.class);
		assertEquals(Arrays.asList(whip, pet), readRecord.getNotableDrops());

		// None: stored as null.
		tracker.stop();
		SessionTracker empty = new SessionTracker(() -> now[0]);
		empty.observe("Kraken", 42, 10, 10, TaskContext.NONE, false);
		assertTrue(empty.play(555L));
		assertNull(LedgerRecord.fromSummary(empty.summary(), true).getNotableDrops());
		assertNull(SessionSnapshot.fromSummary(empty.summary(), null, now[0]).getNotableDrops());
	}

	@Test
	public void heldCompletionTakesLateDropsAndAnOldSnapshotHasNone()
	{
		long[] now = {1_000_000L};
		SessionTracker tracker = new SessionTracker(() -> now[0]);
		tracker.observe("Kraken", 42, 1, 10, TaskContext.NONE, false);
		assertTrue(tracker.play(555L));
		tracker.observe("Kraken", 42, 0, 10, TaskContext.NONE, true);
		assertTrue(tracker.holdCompletion());
		assertTrue(tracker.isRecordingLoot());
		NotableDrop whip = item(WHIP, 1_500_000, 1);
		NotableDrop pet = NotableDrop.pet(NotableDrop.Pet.RECEIVED, 2);
		assertTrue(tracker.addLoot(tally(WHIP, 1), Collections.singletonList(whip)));
		assertTrue(tracker.addPet(pet));
		SessionTracker.Summary held = tracker.takePendingCompletion();
		assertEquals(Arrays.asList(whip, pet), held.getNotableDrops());

		SessionSnapshot old = gson.fromJson("{\"schemaVersion\":2,\"sessionId\":\"s\",\"accountHash\":555,"
			+ "\"monster\":\"Kraken\",\"creatureId\":42,\"initialAmount\":10,\"kills\":1,\"remaining\":9}",
			SessionSnapshot.class);
		assertNull(old.getNotableDrops());
		SessionTracker restored = new SessionTracker(() -> now[0]);
		restored.observe("Kraken", 42, 9, 10, TaskContext.NONE, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, restored.restore(old, null));
		assertTrue(restored.getNotableDrops().isEmpty());
	}
}
