package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.TestSpots.TABLE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * The Task history's setup filters (V2 plan 5): Task type, Cannon, Style and Gear, each "Any" or one value, on
 * the records' effective tags. A record whose value is unknown shows only under "Any", and an estimated value is its
 * value. The Monster filter's "(cannon)" names follow the same Cannon tag. {@link HistoryFilterTest} covers Monster,
 * Location and Master.
 */
public class HistoryFilterSetupTest
{
	private static final int CREATURE = 42;

	/** A record of {@code master}'s with the detected tags given (none estimated, no overrides). */
	private static LedgerRecord record(String id, Integer master, boolean cannon, SetupStyle style, GearFocus gear)
	{
		final LedgerRecord record = new LedgerRecord();
		record.setId(id);
		record.setMonster("Dust devils");
		record.setCreatureId(CREATURE);
		record.setSlayerMaster(master);
		SetupTags.detected(cannon, style, gear, SetupOverrides.NONE).copyTo(record);
		return record;
	}

	/** Newest first, as the model holds them. */
	private static List<LedgerRecord> history()
	{
		final LedgerRecord boss = record("boss", 6, false, SetupStyle.MELEE, GearFocus.PRAYER);
		boss.setCreatureId(-2 - 7);
		boss.setMonster("Cerberus");
		// A migrated task: an estimated cannon and Barrage, gear Unknown.
		final LedgerRecord migrated = record("migrated", 6, false, SetupStyle.BARRAGE, GearFocus.UNKNOWN);
		SetupTags.estimated(true, SetupStyle.BARRAGE).copyTo(migrated);
		// Detected Magic, set to Ranged by the player.
		final LedgerRecord overridden = record("overridden", 5, false, SetupStyle.MAGIC, GearFocus.STRENGTH);
		overridden.setStyleOverride(SetupStyle.RANGED);
		overridden.setGearOverride(GearFocus.MIXED);
		return Arrays.asList(
			record("regular", 6, true, SetupStyle.MELEE, GearFocus.PRAYER),
			record("konar", SlayerMaster.KONAR, false, SetupStyle.MAGIC, GearFocus.STRENGTH),
			record("wilderness", SlayerMaster.KRYSTILIA, false, SetupStyle.MIXED, GearFocus.MIXED),
			record("noMaster", null, false, SetupStyle.UNKNOWN, GearFocus.UNKNOWN),
			boss,
			migrated,
			overridden);
	}

	private static List<String> ids(HistoryFilter filter)
	{
		final List<String> ids = new ArrayList<>();
		for (LedgerRecord record : filter.apply(history(), TABLE, region -> null))
		{
			ids.add(record.getId());
		}
		return ids;
	}

	private static HistoryFilter any()
	{
		return new HistoryFilter(null, null, null);
	}

	@Test
	public void anyShowsEveryRecordUnknownValuesIncluded()
	{
		assertEquals(7, ids(any().withTaskType(null).withCannon(null).withStyle(null).withGear(null)).size());
	}

	@Test
	public void taskTypeFromTheMasterAndTheCreatureKey()
	{
		assertEquals(Arrays.asList("regular", "migrated", "overridden"), ids(any().withTaskType(TaskType.REGULAR)));
		assertEquals(Arrays.asList("konar"), ids(any().withTaskType(TaskType.KONAR)));
		assertEquals(Arrays.asList("wilderness"), ids(any().withTaskType(TaskType.WILDERNESS)));
		assertEquals("whoever assigned it", Arrays.asList("boss"), ids(any().withTaskType(TaskType.BOSS)));
	}

	@Test
	public void cannonIsTheEffectiveValueAndAnEstimateCountsAsItsValue()
	{
		assertEquals(Arrays.asList("regular", "migrated"), ids(any().withCannon(true)));
		assertEquals(Arrays.asList("konar", "wilderness", "noMaster", "boss", "overridden"),
			ids(any().withCannon(false)));
	}

	@Test
	public void theMonsterFiltersCannonNameFollowsTheCannonTagAsTheCannonFilterDoes()
	{
		// A brief cannon setup: cannonballs fired, but too few cannon kills for the tag.
		final LedgerRecord brief = record("brief", 6, false, SetupStyle.MELEE, GearFocus.PRAYER);
		brief.setSupplies(Collections.singletonMap(SupplyTracker.CANNONBALL, 20L));
		// No cannonballs recorded, set to Yes by the player.
		final LedgerRecord setYes = record("setYes", 6, false, SetupStyle.MELEE, GearFocus.PRAYER);
		setYes.setCannonOverride(true);
		// Cannonballs fired, set to No by the player.
		final LedgerRecord setNo = record("setNo", 6, true, SetupStyle.MELEE, GearFocus.PRAYER);
		setNo.setSupplies(Collections.singletonMap(SupplyTracker.CANNONBALL, 500L));
		setNo.setCannonOverride(false);
		// A migrated task: its estimated cannon from its cannonballs.
		final LedgerRecord migrated = record("migrated", 6, false, SetupStyle.UNKNOWN, GearFocus.UNKNOWN);
		migrated.setSupplies(Collections.singletonMap(SupplyTracker.CANNONBALL, 50L));
		SetupTags.estimated(true, SetupStyle.MELEE).copyTo(migrated);
		final List<LedgerRecord> records = Arrays.asList(brief, setYes, setNo, migrated);

		assertEquals(Arrays.asList("Dust devils", "Dust devils (cannon)", "Dust devils", "Dust devils (cannon)"),
			Arrays.asList(LedgerHistory.monster(brief), LedgerHistory.monster(setYes), LedgerHistory.monster(setNo),
				LedgerHistory.monster(migrated)));
		for (boolean cannon : new boolean[]{true, false})
		{
			final HistoryFilter byName = new HistoryFilter(cannon ? "Dust devils (cannon)" : "Dust devils", null, null);
			assertEquals("cannon " + cannon, any().withCannon(cannon).apply(records, TABLE, region -> null),
				byName.apply(records, TABLE, region -> null));
		}

		// A record built without tags (none is read from the file so) still goes by its cannonballs.
		final LedgerRecord untagged = new LedgerRecord();
		untagged.setMonster("Dust devils");
		untagged.setSupplies(Collections.singletonMap(SupplyTracker.CANNONBALL, 50L));
		assertEquals("Dust devils (cannon)", untagged.groupName());
	}

	@Test
	public void styleShowsUnknownOnlyUnderAnyAndAnEstimatedBarrageIsBarrage()
	{
		assertEquals(Arrays.asList("regular", "boss"), ids(any().withStyle(SetupStyle.MELEE)));
		assertEquals("the player's override", Arrays.asList("overridden"), ids(any().withStyle(SetupStyle.RANGED)));
		assertEquals(Arrays.asList("migrated"), ids(any().withStyle(SetupStyle.BARRAGE)));
		assertEquals(Arrays.asList("konar"), ids(any().withStyle(SetupStyle.MAGIC)));
		assertEquals(Arrays.asList("wilderness"), ids(any().withStyle(SetupStyle.MIXED)));
		assertEquals("Unknown is no choice: Any", 7, ids(any().withStyle(SetupStyle.UNKNOWN)).size());
	}

	@Test
	public void gearShowsUnknownOnlyUnderAny()
	{
		assertEquals(Arrays.asList("regular", "boss"), ids(any().withGear(GearFocus.PRAYER)));
		assertEquals(Arrays.asList("konar"), ids(any().withGear(GearFocus.STRENGTH)));
		assertEquals("the override", Arrays.asList("wilderness", "overridden"), ids(any().withGear(GearFocus.MIXED)));
		assertEquals("Unknown is no choice: Any", 7, ids(any().withGear(GearFocus.UNKNOWN)).size());
	}

	@Test
	public void theFiltersCombineWithEachOtherAndTheMonsterFilter()
	{
		assertEquals(Arrays.asList("regular"), ids(any().withTaskType(TaskType.REGULAR).withCannon(true)
			.withStyle(SetupStyle.MELEE).withGear(GearFocus.PRAYER)));
		assertTrue(ids(any().withCannon(true).withStyle(SetupStyle.MAGIC)).isEmpty());
		assertEquals(Arrays.asList("boss"),
			ids(new HistoryFilter("Cerberus", null, null).withGear(GearFocus.PRAYER)));
		assertEquals("a later choice replaces the earlier", Arrays.asList("konar"),
			ids(any().withStyle(SetupStyle.MELEE).withStyle(SetupStyle.MAGIC)));
	}

	@Test
	public void theChoicesAndTheirLabels()
	{
		assertEquals(Arrays.asList(null, TaskType.REGULAR, TaskType.KONAR, TaskType.WILDERNESS, TaskType.BOSS),
			HistoryFilter.TASK_TYPE_CHOICES);
		assertEquals(Arrays.asList(null, true, false), HistoryFilter.CANNON_CHOICES);
		assertEquals(Arrays.asList(null, SetupStyle.MELEE, SetupStyle.RANGED, SetupStyle.BARRAGE, SetupStyle.MAGIC,
			SetupStyle.MIXED), HistoryFilter.STYLE_CHOICES);
		assertEquals(Arrays.asList(null, GearFocus.PRAYER, GearFocus.STRENGTH, GearFocus.MIXED),
			HistoryFilter.GEAR_CHOICES);

		final List<String> labels = new ArrayList<>();
		for (List<?> choices : Arrays.asList(HistoryFilter.TASK_TYPE_CHOICES, HistoryFilter.CANNON_CHOICES,
			HistoryFilter.STYLE_CHOICES, HistoryFilter.GEAR_CHOICES))
		{
			for (Object choice : choices)
			{
				labels.add(HistoryFilter.setupLabel(choice));
			}
		}
		assertEquals(Arrays.asList("Any", "Regular", "Konar", "Wilderness", "Boss", "Any", "Yes", "No", "Any", "Melee",
			"Ranged", "Barrage", "Magic", "Mixed", "Any", "Prayer", "Strength", "Mixed"), labels);
	}
}
