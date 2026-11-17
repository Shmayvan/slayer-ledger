package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.SupplyTrackerTest.ADAMANT_ARROW;
import static com.shmayvan.slayerledger.SupplyTrackerTest.DEATH_RUNE;
import static com.shmayvan.slayerledger.SupplyTrackerTest.GLORY_4;
import static com.shmayvan.slayerledger.SupplyTrackerTest.PRAYER_2;
import static com.shmayvan.slayerledger.SupplyTrackerTest.PRAYER_3;
import static com.shmayvan.slayerledger.SupplyTrackerTest.PRAYER_4;
import static com.shmayvan.slayerledger.SupplyTrackerTest.SHARK;
import static com.shmayvan.slayerledger.SupplyTrackerTest.SUPER_COMBAT_3;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * The supply runway's rule ({@link SupplyRunway}): what counts and under which short name, the units (doses),
 * this task's use per kill against the history's, the scarcest of several supplies and the two levels; and what the
 * supplies diff reports carried ({@link SupplyTracker#carried}).
 */
public class SupplyRunwayTest
{
	private static final String PRAYER = SupplyTracker.familyKey("Prayer potion");
	private static final String SHARKS = SupplyTracker.itemKey(SHARK);
	private static final String BALLS = SupplyTracker.itemKey(SupplyTracker.CANNONBALL);

	private static SupplyRunway.Supply prayer(long doses)
	{
		return new SupplyRunway.Supply(PRAYER, PRAYER_4, SupplyRunway.Kind.PRAYER, doses);
	}

	private static SupplyRunway.Supply sharks(long sharks)
	{
		return new SupplyRunway.Supply(SHARKS, SHARK, SupplyRunway.Kind.FOOD, sharks);
	}

	private static SupplyRunway.Supply cannonballs(long balls)
	{
		return new SupplyRunway.Supply(BALLS, SupplyTracker.CANNONBALL, SupplyRunway.Kind.CANNONBALLS, balls);
	}

	private static Map<String, Long> used(Object... keysAndAmounts)
	{
		Map<String, Long> used = new HashMap<>();
		for (int i = 0; i < keysAndAmounts.length; i += 2)
		{
			used.put((String) keysAndAmounts[i], ((Number) keysAndAmounts[i + 1]).longValue());
		}
		return used;
	}

	private static Map<String, Double> perKill(Object... keysAndAmounts)
	{
		Map<String, Double> perKill = new HashMap<>();
		for (int i = 0; i < keysAndAmounts.length; i += 2)
		{
			perKill.put((String) keysAndAmounts[i], ((Number) keysAndAmounts[i + 1]).doubleValue());
		}
		return perKill;
	}

	@Test
	public void thisTasksUseFromTenKillsBeforeThatTheHistorys()
	{
		List<SupplyRunway.Supply> carried = Collections.singletonList(prayer(20));
		// The task so far: 2 doses a kill. The history: half a dose a kill.
		Map<String, Long> task = used(PRAYER, 18);
		Map<String, Double> history = perKill(PRAYER, 0.5);

		// 9 kills: the history's rate, 20 doses for 40 kills.
		assertEquals(new SupplyRunway.Warning(SupplyRunway.Kind.PRAYER, PRAYER_4, 40),
			SupplyRunway.scarcest(carried, used(PRAYER, 18), 9, history, 128));
		// 10 kills: this task's 18 doses in 10 kills, so 20 doses last 11 kills.
		assertEquals(new SupplyRunway.Warning(SupplyRunway.Kind.PRAYER, PRAYER_4, 11),
			SupplyRunway.scarcest(carried, task, SupplyRunway.MIN_TASK_KILLS, history, 128));
		assertEquals(10, SupplyRunway.MIN_TASK_KILLS);
		// Without history the first kills show nothing.
		assertNull(SupplyRunway.scarcest(carried, task, 9, Collections.emptyMap(), 128));
		// From 10 kills a supply this task hasn't used lasts, whatever the history says.
		assertNull(SupplyRunway.scarcest(carried, Collections.emptyMap(), 10, history, 128));
	}

	@Test
	public void onlyWhenItRunsOutBeforeTheTaskEnds()
	{
		List<SupplyRunway.Supply> carried = Collections.singletonList(prayer(20));
		Map<String, Double> history = perKill(PRAYER, 0.5);

		assertNull("lasts the 40 kills left", SupplyRunway.scarcest(carried, null, 0, history, 40));
		assertNull("lasts more", SupplyRunway.scarcest(carried, null, 0, history, 12));
		assertEquals(40, SupplyRunway.scarcest(carried, null, 0, history, 41).getKills());
		assertNull("kills left unknown", SupplyRunway.scarcest(carried, null, 0, history, OverlayRows.UNKNOWN));
		assertNull(SupplyRunway.scarcest(null, null, 0, history, 41));
	}

	@Test
	public void criticalWithinNineKills()
	{
		Map<String, Double> history = perKill(PRAYER, 1);

		SupplyRunway.Warning ten = SupplyRunway.scarcest(Collections.singletonList(prayer(10)), null, 0, history, 50);
		assertEquals(10, ten.getKills());
		assertFalse(ten.isCritical());
		SupplyRunway.Warning nine = SupplyRunway.scarcest(Collections.singletonList(prayer(9)), null, 0, history, 50);
		assertTrue(nine.isCritical());
		assertEquals(9, SupplyRunway.CRITICAL_KILLS);
	}

	@Test
	public void anyCarriedLastsAtLeastOneKillAndNoneLastsNone()
	{
		// Half a kill's worth: 1 left, at either rate, so 0 left only ever means none is carried.
		SupplyRunway.Warning half = SupplyRunway.scarcest(Collections.singletonList(prayer(1)), null, 0,
			perKill(PRAYER, 2), 50);
		assertEquals(1, half.getKills());
		assertTrue(half.isCritical());
		assertEquals(1, SupplyRunway.scarcest(Collections.singletonList(cannonballs(3)), used(BALLS, 100), 20, null, 50)
			.getKills());

		SupplyRunway.Warning none = SupplyRunway.scarcest(Collections.singletonList(prayer(0)), null, 0,
			perKill(PRAYER, 2), 50);
		assertEquals(0, none.getKills());
		assertEquals(0, SupplyRunway.scarcest(Collections.singletonList(prayer(0)), used(PRAYER, 20), 10, null, 50)
			.getKills());
		// None carried of a supply the rate doesn't use: nothing to warn about.
		assertNull(SupplyRunway.scarcest(Collections.singletonList(prayer(0)), used(SHARKS, 20), 10, null, 50));
		assertNull(SupplyRunway.scarcest(Collections.singletonList(prayer(0)), null, 0, perKill(SHARKS, 1), 50));
		// The rounded kills are the ones compared: with one kill left, 1 left is not fewer; none is.
		assertNull(SupplyRunway.scarcest(Collections.singletonList(prayer(1)), null, 0, perKill(PRAYER, 2), 1));
		assertEquals(0, SupplyRunway.scarcest(Collections.singletonList(prayer(0)), null, 0, perKill(PRAYER, 2), 1)
			.getKills());
	}

	@Test
	public void aSupplyCarriedEarlierAndGoneNowCountsAtNone()
	{
		List<SupplyRunway.Supply> earlier = Arrays.asList(prayer(20), sharks(10));
		SupplyRunway.Supply dartsEarlier = new SupplyRunway.Supply(SupplyTracker.itemKey(ItemID.RUNE_DART),
			ItemID.RUNE_DART, SupplyRunway.Kind.DARTS, 15);
		SupplyRunway.Supply scalesEarlier = new SupplyRunway.Supply(SupplyTracker.itemKey(SupplyRunway.ZULRAH_SCALES),
			SupplyRunway.ZULRAH_SCALES, SupplyRunway.Kind.SCALES, 900);

		// The sharks are carried now; the prayer potions are gone, so they count at none, with their icon.
		List<SupplyRunway.Supply> now = SupplyRunway.withRunOut(new SupplyRunway.Carrying(
			Collections.singletonList(sharks(4)), true, false), earlier);
		assertEquals(2, now.size());
		assertEquals(4, now.get(0).getAmount());
		assertEquals(PRAYER, now.get(1).getKey());
		assertEquals(PRAYER_4, now.get(1).getItemId());
		assertEquals(SupplyRunway.Kind.PRAYER, now.get(1).getKind());
		assertEquals(0, now.get(1).getAmount());
		assertEquals(new SupplyRunway.Warning(SupplyRunway.Kind.PRAYER, PRAYER_4, 0),
			SupplyRunway.scarcest(now, used(PRAYER, 30, SHARKS, 2), 10, null, 50));

		// Nothing read on the tick: nothing ran out.
		assertTrue(SupplyRunway.withRunOut(SupplyRunway.Carrying.UNSEEN, earlier).isEmpty());
		assertFalse(SupplyRunway.Carrying.UNSEEN.isSeen());
		// Nothing carried earlier: what is carried now.
		assertEquals(1, SupplyRunway.withRunOut(
			new SupplyRunway.Carrying(Collections.singletonList(sharks(4)), true, false), Collections.emptyList()).size());

		// Another supply of the kind carried instead: karambwans after the sharks, another dart type. Not another rune:
		// a spell needs each of its runes.
		SupplyRunway.Supply karambwans = new SupplyRunway.Supply(SupplyTracker.itemKey(ItemID.TBWT_COOKED_KARAMBWAN),
			ItemID.TBWT_COOKED_KARAMBWAN, SupplyRunway.Kind.FOOD, 8);
		assertEquals(Collections.singletonList(karambwans), SupplyRunway.withRunOut(new SupplyRunway.Carrying(
			Collections.singletonList(karambwans), true, false), Collections.singletonList(sharks(10))));
		SupplyRunway.Supply amethyst = new SupplyRunway.Supply(SupplyTracker.itemKey(ItemID.AMETHYST_DART),
			ItemID.AMETHYST_DART, SupplyRunway.Kind.DARTS, 900);
		assertEquals(Collections.singletonList(amethyst), SupplyRunway.withRunOut(new SupplyRunway.Carrying(
			Collections.singletonList(amethyst), true, false), Collections.singletonList(dartsEarlier)));
		SupplyRunway.Supply deaths = new SupplyRunway.Supply(SupplyTracker.itemKey(DEATH_RUNE), DEATH_RUNE,
			SupplyRunway.Kind.RUNES, 300);
		SupplyRunway.Supply bloods = new SupplyRunway.Supply(SupplyTracker.itemKey(ItemID.BLOODRUNE), ItemID.BLOODRUNE,
			SupplyRunway.Kind.RUNES, 300);
		List<SupplyRunway.Supply> runes = SupplyRunway.withRunOut(new SupplyRunway.Carrying(
			Collections.singletonList(bloods), true, false), Collections.singletonList(deaths));
		assertEquals(2, runes.size());
		assertEquals(DEATH_RUNE, runes.get(1).getItemId());
		assertEquals(0, runes.get(1).getAmount());

		// Darts and scales a blowpipe holds but that can't be counted never run out; the rest still can.
		List<SupplyRunway.Supply> blowpipe = SupplyRunway.withRunOut(new SupplyRunway.Carrying(
			Collections.emptyList(), true, true), Arrays.asList(prayer(20), dartsEarlier, scalesEarlier));
		assertEquals(Collections.singletonList(SupplyRunway.Kind.PRAYER), kinds(blowpipe));
		List<SupplyRunway.Supply> counted = SupplyRunway.withRunOut(new SupplyRunway.Carrying(
			Collections.emptyList(), true, false), Arrays.asList(dartsEarlier, scalesEarlier));
		assertEquals(Arrays.asList(SupplyRunway.Kind.DARTS, SupplyRunway.Kind.SCALES), kinds(counted));
	}

	@Test
	public void aBlowpipesDartsAndScalesAreUnseenWhileTheyCantBeCounted()
	{
		BlowpipeContents contents = new BlowpipeContents();
		// Not checked, worn or not: a blowpipe unequipped since a login may hold darts and scales, so neither runs out
		// (nor do thrown darts, which can't be told from them).
		assertTrue(SupplyRunway.blowpipeUnseen(false, contents));
		assertTrue(SupplyRunway.blowpipeUnseen(false, null));
		assertTrue(SupplyRunway.blowpipeUnseen(true, contents));
		assertTrue(SupplyRunway.blowpipeUnseen(true, null));
		SupplyRunway.Supply dartsEarlier = new SupplyRunway.Supply(SupplyTracker.itemKey(ItemID.RUNE_DART),
			ItemID.RUNE_DART, SupplyRunway.Kind.DARTS, 15);
		SupplyRunway.Carrying unequipped = SupplyRunway.carrying(Collections.singletonList(sharks(1)), true, false,
			contents, 0);
		assertFalse(unequipped.isSeen(SupplyRunway.Kind.DARTS));
		assertFalse(unequipped.isSeen(SupplyRunway.Kind.SCALES));
		assertTrue(unequipped.isSeen(SupplyRunway.Kind.FOOD));
		assertEquals(Collections.singletonList(SupplyRunway.Kind.FOOD),
			kinds(SupplyRunway.withRunOut(unequipped, Collections.singletonList(dartsEarlier))));

		contents.messageSeen(new BlowpipeMessage.Darts(true, ItemID.RUNE_DART, 15, 900));
		assertFalse("worn and checked", SupplyRunway.blowpipeUnseen(true, contents));
		assertTrue("checked but not worn: its darts aren't counted", SupplyRunway.blowpipeUnseen(false, contents));

		contents.messageSeen(BlowpipeMessage.parse("Your blowpipe has run out of scales and darts."));
		assertFalse("known to be empty", SupplyRunway.blowpipeUnseen(false, contents));

		SupplyRunway.Carrying carrying = SupplyRunway.carrying(Collections.singletonList(sharks(1)), true, true,
			new BlowpipeContents(), 50);
		assertTrue(carrying.isSeen());
		assertTrue(carrying.isSeen(SupplyRunway.Kind.FOOD));
		assertFalse(carrying.isSeen(SupplyRunway.Kind.DARTS));
		assertFalse(carrying.isSeen(SupplyRunway.Kind.SCALES));
		assertEquals(Collections.singletonList(SupplyRunway.Kind.FOOD), kinds(carrying.getSupplies()));

		// The containers not read: a worn blowpipe's known contents are still carried, but nothing runs out.
		SupplyRunway.Carrying unread = SupplyRunway.carrying(Collections.emptyList(), false, true, contents, 0);
		assertFalse(unread.isSeen());
		assertFalse(unread.isSeen(SupplyRunway.Kind.FOOD));
		assertTrue("known to be empty: no darts or scales", unread.getSupplies().isEmpty());
		contents.messageSeen(new BlowpipeMessage.Darts(true, ItemID.RUNE_DART, 15, 900));
		unread = SupplyRunway.carrying(Collections.emptyList(), false, true, contents, 0);
		assertEquals(Arrays.asList(SupplyRunway.Kind.DARTS, SupplyRunway.Kind.SCALES), kinds(unread.getSupplies()));
		assertEquals(unread.getSupplies(), SupplyRunway.withRunOut(unread, Collections.singletonList(prayer(20))));
	}

	@Test
	public void theScarcestOfSeveralSuppliesWins()
	{
		List<SupplyRunway.Supply> carried = Arrays.asList(prayer(60), sharks(12), cannonballs(900));
		// 20 kills so far: 30 doses, 10 sharks and 400 cannonballs.
		Map<String, Long> task = used(PRAYER, 30, SHARKS, 10, BALLS, 400);

		// Prayer lasts 40 kills, sharks 24, cannonballs 45: the sharks.
		SupplyRunway.Warning warning = SupplyRunway.scarcest(carried, task, 20, null, 100);
		assertEquals(new SupplyRunway.Warning(SupplyRunway.Kind.FOOD, SHARK, 24), warning);

		// Only the supplies that run out count: with 30 kills left only the sharks do.
		assertEquals(SupplyRunway.Kind.FOOD, SupplyRunway.scarcest(carried, task, 20, null, 30).getKind());
		// With 24 left none does.
		assertNull(SupplyRunway.scarcest(carried, task, 20, null, 24));

		// A tie goes to the first kind: Prayer before Food.
		SupplyRunway.Warning tie = SupplyRunway.scarcest(Arrays.asList(sharks(20), prayer(40)), used(PRAYER, 20,
			SHARKS, 10), 10, null, 100);
		assertEquals(SupplyRunway.Kind.PRAYER, tie.getKind());
		assertEquals(20, tie.getKills());
	}

	@Test
	public void potionsCountInDosesOfEverySize()
	{
		SupplyTracker tracker = new SupplyTracker(SupplyTrackerTest.CLASSIFIER);
		// Two Prayer potion(4)s, a (3) and a (2): 13 doses, one supply.
		tracker.update(new SupplyTracker.Observation().add(PRAYER_4, 2).add(PRAYER_3, 1).add(PRAYER_2, 1)
			.add(SHARK, 5).add(SUPER_COMBAT_3, 1).add(GLORY_4, 1), true, false, Collections.emptySet());

		List<SupplyRunway.Supply> carried = SupplyRunway.carried(tracker.carried());
		Map<String, SupplyRunway.Supply> byKey = new HashMap<>();
		for (SupplyRunway.Supply supply : carried)
		{
			byKey.put(supply.getKey(), supply);
		}
		assertEquals("prayer and sharks; not the super combat or the glory's charges", 2, carried.size());
		assertEquals(13, byKey.get(PRAYER).getAmount());
		assertEquals("the icon is the largest size seen", PRAYER_4, byKey.get(PRAYER).getItemId());
		assertEquals(SupplyRunway.Kind.PRAYER, byKey.get(PRAYER).getKind());
		assertEquals(5, byKey.get(SHARKS).getAmount());

		// The session's use, recorded under the (4) and the (3), is one supply too: 6 doses.
		assertEquals(PRAYER, tracker.supplyKey(PRAYER_3));
		assertEquals(SHARKS, tracker.supplyKey(SHARK));
		Map<Integer, Long> session = new HashMap<>();
		session.put(PRAYER_4, 4L);
		session.put(PRAYER_3, 2L);
		session.put(SHARK, 3L);
		Map<String, Long> task = SupplyRunway.byKey(session, tracker::supplyKey);
		assertEquals(Long.valueOf(6), task.get(PRAYER));
		Map<Integer, Double> history = new HashMap<>();
		history.put(PRAYER_4, 0.25);
		history.put(PRAYER_3, 0.25);
		assertEquals(0.5, SupplyRunway.perKillByKey(history, tracker::supplyKey).get(PRAYER), 1e-9);

		// 13 doses at 6 in 12 kills last 26 kills; the 5 sharks at 3 in 12 kills 20, so they are the scarcest.
		assertEquals(26, SupplyRunway.scarcest(Collections.singletonList(byKey.get(PRAYER)), task, 12, null, 100)
			.getKills());
		assertEquals(new SupplyRunway.Warning(SupplyRunway.Kind.FOOD, SHARK, 20),
			SupplyRunway.scarcest(carried, task, 12, null, 100));
	}

	@Test
	public void theCannonsLoadCountsAsCarried()
	{
		SupplyTracker tracker = new SupplyTracker(SupplyTrackerTest.CLASSIFIER);
		tracker.update(new SupplyTracker.Observation().add(SupplyTracker.CANNONBALL, 200).setCannonBalls(30), true,
			false, Collections.emptySet());

		List<SupplyRunway.Supply> carried = SupplyRunway.carried(tracker.carried());
		assertEquals(1, carried.size());
		assertEquals(230, carried.get(0).getAmount());
		assertEquals(SupplyRunway.Kind.CANNONBALLS, carried.get(0).getKind());

		tracker.reset();
		assertTrue("nothing without a baseline", tracker.carried().isEmpty());
	}

	@Test
	public void whatCountsAndItsShortName()
	{
		assertEquals(SupplyRunway.Kind.PRAYER, SupplyRunway.kind(SupplyTracker.Category.POTION, "Prayer potion", 1));
		assertEquals(SupplyRunway.Kind.RESTORES, SupplyRunway.kind(SupplyTracker.Category.POTION, "Super restore", 1));
		assertEquals(SupplyRunway.Kind.RESTORES, SupplyRunway.kind(SupplyTracker.Category.POTION,
			"Blighted super restore", 1));
		assertEquals(SupplyRunway.Kind.RESTORES, SupplyRunway.kind(SupplyTracker.Category.POTION, "Sanfew serum", 1));
		assertNull(SupplyRunway.kind(SupplyTracker.Category.POTION, "Super combat potion", 1));
		assertNull(SupplyRunway.kind(SupplyTracker.Category.POTION, "Prayer regeneration potion", 1));
		assertEquals(SupplyRunway.Kind.FOOD, SupplyRunway.kind(SupplyTracker.Category.FOOD, null, SHARK));
		assertEquals(SupplyRunway.Kind.CANNONBALLS, SupplyRunway.kind(SupplyTracker.Category.CANNONBALL, null, 2));
		assertEquals(SupplyRunway.Kind.RUNES, SupplyRunway.kind(SupplyTracker.Category.RUNE, null, DEATH_RUNE));
		assertEquals(SupplyRunway.Kind.AMMO, SupplyRunway.kind(SupplyTracker.Category.AMMO, null, ADAMANT_ARROW));
		assertEquals(SupplyRunway.Kind.DARTS, SupplyRunway.kind(SupplyTracker.Category.AMMO, null,
			ItemID.AMETHYST_DART));
		assertNull(SupplyRunway.kind(SupplyTracker.Category.TELEPORT_TABLET, null, 1));
		assertNull(SupplyRunway.kind(SupplyTracker.Category.CHARGES, "charges:1712", GLORY_4));
		assertNull(SupplyRunway.kind(SupplyTracker.Category.SLAYER_CONSUMABLE, null, 1));
		assertNull(SupplyRunway.kind(null, null, 1));

		List<String> names = Arrays.asList("Prayer", "Restores", "Food", "Cannonballs", "Runes", "Ammo", "Darts",
			"Scales");
		for (SupplyRunway.Kind kind : SupplyRunway.Kind.values())
		{
			assertEquals(names.get(kind.ordinal()), kind.getShortName());
		}
	}

	@Test
	public void aWornBlowpipesDartsAndScalesCountOnceItWasChecked()
	{
		SupplyRunway.Supply spareDarts = new SupplyRunway.Supply(SupplyTracker.itemKey(ItemID.AMETHYST_DART),
			ItemID.AMETHYST_DART, SupplyRunway.Kind.DARTS, 100);
		List<SupplyRunway.Supply> carried = Arrays.asList(sharks(10), spareDarts);
		BlowpipeContents contents = new BlowpipeContents();

		// Not checked: neither darts nor scales count, as the blowpipe could hold anything.
		List<SupplyRunway.Supply> unknown = SupplyRunway.withBlowpipe(carried, true, contents, 5_000);
		assertEquals(Collections.singletonList(SupplyRunway.Kind.FOOD), kinds(unknown));
		// Without a blowpipe worn, thrown darts count as they are.
		assertEquals(carried, SupplyRunway.withBlowpipe(carried, false, contents, 5_000));

		contents.messageSeen(new BlowpipeMessage.Darts(true, ItemID.AMETHYST_DART, 1_200, 8_000));
		List<SupplyRunway.Supply> known = SupplyRunway.withBlowpipe(carried, true, contents, 5_000);
		assertEquals(Arrays.asList(SupplyRunway.Kind.FOOD, SupplyRunway.Kind.DARTS, SupplyRunway.Kind.SCALES),
			kinds(known));
		assertEquals("the spare darts and the blowpipe's", 1_300, known.get(1).getAmount());
		assertEquals("the blowpipe's scales and the loose ones", 13_000, known.get(2).getAmount());
		assertEquals(SupplyRunway.ZULRAH_SCALES, known.get(2).getItemId());

		// Without spare darts the blowpipe's still count.
		List<SupplyRunway.Supply> onlyInside = SupplyRunway.withBlowpipe(Collections.singletonList(sharks(10)), true,
			contents, 0);
		assertEquals(1_200, onlyInside.get(1).getAmount());
		assertEquals(SupplyTracker.itemKey(ItemID.AMETHYST_DART), onlyInside.get(1).getKey());
	}

	private static List<SupplyRunway.Kind> kinds(List<SupplyRunway.Supply> supplies)
	{
		List<SupplyRunway.Kind> kinds = new java.util.ArrayList<>();
		for (SupplyRunway.Supply supply : supplies)
		{
			kinds.add(supply.getKind());
		}
		return kinds;
	}
}
