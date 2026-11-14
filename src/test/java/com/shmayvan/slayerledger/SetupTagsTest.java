package com.shmayvan.slayerledger;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The setup tags' value classes: the effective value of each tag ({@link SetupTags}: the override, else the
 * detected value, with whether it is an estimate), the overrides ({@link SetupOverrides}), the kill-context counts
 * ({@link SetupKills}) and the choices of the history row's "Setup..." dialog ({@link SetupChoices}).
 */
public class SetupTagsTest
{
	@Test
	public void theOverrideWinsAndIsNeverAnEstimate()
	{
		final SetupTags migrated = SetupTags.estimated(true, SetupStyle.BARRAGE);
		assertTrue(migrated.isCannon());
		assertTrue(migrated.isCannonEstimated());
		assertEquals(SetupStyle.BARRAGE, migrated.getStyle());
		assertTrue(migrated.isStyleEstimated());
		assertEquals(GearFocus.UNKNOWN, migrated.getGear());
		assertFalse("Unknown is no estimate", migrated.isGearEstimated());

		final SetupTags overridden = migrated.withOverrides(SetupOverrides.of(false, SetupStyle.MELEE, GearFocus.PRAYER));
		assertFalse(overridden.isCannon());
		assertFalse(overridden.isCannonEstimated());
		assertEquals(SetupStyle.MELEE, overridden.getStyle());
		assertFalse(overridden.isStyleEstimated());
		assertEquals(GearFocus.PRAYER, overridden.getGear());
		assertTrue("the detected values are kept", overridden.isDetectedCannon());
		assertTrue(overridden.isDetectedCannonEstimated());
		assertEquals(SetupStyle.BARRAGE, overridden.getDetectedStyle());
		assertTrue(overridden.hasEstimates());
	}

	@Test
	public void unknownIsNeverAnEstimate()
	{
		final SetupTags tags = SetupTags.estimated(false, SetupStyle.UNKNOWN);
		assertEquals(SetupStyle.UNKNOWN, tags.getStyle());
		assertFalse(tags.isStyleEstimated());
		assertTrue("the cannon still is one", tags.isCannonEstimated());
		final SetupTags live = SetupTags.detected(false, null, null, null);
		assertEquals(SetupStyle.UNKNOWN, live.getStyle());
		assertEquals(GearFocus.UNKNOWN, live.getGear());
		assertFalse(live.hasEstimates());
	}

	@Test
	public void aRecordsTagsRoundTripThroughItsFields()
	{
		final SetupTags tags = SetupTags.estimated(true, SetupStyle.RANGED)
			.withOverrides(SetupOverrides.NONE.withGear(GearFocus.STRENGTH));
		final LedgerRecord record = new LedgerRecord();
		tags.copyTo(record);
		assertEquals(Boolean.TRUE, record.getCannonEstimated());
		assertNull("not estimated is left out", record.getGearEstimated());
		assertEquals(tags, SetupTags.of(record));
		assertTrue(SetupTags.of(record).isRecorded());
	}

	@Test
	public void aRecordWithoutStoredTagsFallsBackToItsSupplies()
	{
		final LedgerRecord record = new LedgerRecord();
		assertFalse(SetupTags.of(record).isRecorded());
		assertFalse(SetupTags.of(record).isCannon());
		record.setSupplies(Collections.singletonMap(SupplyTracker.CANNONBALL, 40L));
		assertTrue("as 1.x classified it", SetupTags.of(record).isCannon());
		assertFalse(SetupTags.of(record).isCannonEstimated());
		assertEquals(SetupStyle.UNKNOWN, SetupTags.of(record).getStyle());
		assertEquals("no tooltip lines", "", LedgerHistory.setupTooltip(record));
		record.setStyleOverride(SetupStyle.MAGIC);
		assertTrue("an override is stored", SetupTags.of(record).isRecorded());
	}

	@Test
	public void theTooltipLinesMarkEstimatesAndOverrides()
	{
		assertEquals(Arrays.asList("Cannon: ~Yes", "Style: ~Barrage", "Gear: Unknown", SetupTags.ESTIMATE_NOTE),
			SetupTags.estimated(true, SetupStyle.BARRAGE).lines());
		assertEquals(Arrays.asList("Cannon: No (set by you; detected ~Yes)", "Style: ~Barrage",
			"Gear: Prayer (set by you; detected Unknown)", SetupTags.ESTIMATE_NOTE),
			SetupTags.estimated(true, SetupStyle.BARRAGE).withOverrides(SetupOverrides.of(false, null, GearFocus.PRAYER))
				.lines());
		assertEquals(Arrays.asList("Cannon: No", "Style: Unknown", "Gear: Unknown"),
			SetupTags.detected(false, SetupStyle.UNKNOWN, GearFocus.UNKNOWN, null).lines());

		final LedgerRecord record = new LedgerRecord();
		SetupTags.estimated(false, SetupStyle.MIXED).copyTo(record);
		assertEquals("<br>Cannon: ~No<br>Style: ~Mixed<br>Gear: Unknown<br>~ estimated: saved before setup was recorded",
			LedgerHistory.setupTooltip(record));
	}

	@Test
	public void overridesTreatUnknownAsNone()
	{
		assertSame(SetupOverrides.NONE, SetupOverrides.of(null, SetupStyle.UNKNOWN, GearFocus.UNKNOWN));
		assertTrue(SetupOverrides.NONE.isEmpty());
		final SetupOverrides some = SetupOverrides.NONE.withCannon(true).withStyle(SetupStyle.MAGIC);
		assertEquals(SetupOverrides.of(true, SetupStyle.MAGIC, null), some);
		assertNull(some.withStyle(SetupStyle.UNKNOWN).getStyle());
		assertSame(SetupOverrides.NONE, some.withCannon(null).withStyle(null));

		final SessionSnapshot snapshot = new SessionSnapshot();
		some.copyTo(snapshot);
		assertEquals(some, SetupOverrides.from(snapshot));
		snapshot.setGearOverride(GearFocus.UNKNOWN);
		assertEquals("a hand-edited Unknown reads as none", some, SetupOverrides.from(snapshot));
	}

	@Test
	public void killContextsAreCountsWithAPrayerBonusRange()
	{
		assertTrue(SetupKills.NONE.isEmpty());
		assertNull(SetupKills.NONE.getCannonKills());
		final SetupKills kills = SetupKills.NONE
			.withKill(true, null, GearFocus.PRAYER, 30)
			.withKill(false, SetupStyle.BARRAGE, GearFocus.PRAYER, 25)
			.withKill(false, SetupStyle.BARRAGE, GearFocus.MIXED, null)
			.withKill(false, SetupStyle.MELEE, null, 8);
		assertEquals(Integer.valueOf(1), kills.getCannonKills());
		assertEquals(2, kills.styleKills(SetupStyle.BARRAGE));
		assertEquals(1, kills.styleKills(SetupStyle.MELEE));
		assertEquals(0, kills.styleKills(SetupStyle.RANGED));
		assertEquals(2, kills.gearKills(GearFocus.PRAYER));
		assertEquals(Integer.valueOf(8), kills.getPrayerBonusMin());
		assertEquals(Integer.valueOf(30), kills.getPrayerBonusMax());

		final LedgerRecord record = new LedgerRecord();
		kills.copyTo(record);
		assertEquals(kills, SetupKills.from(record));
		final SessionSnapshot snapshot = new SessionSnapshot();
		kills.copyTo(snapshot);
		assertEquals(kills, SetupKills.from(snapshot));

		final SetupKills none = SetupKills.of(0, null, null, null, null);
		assertEquals("counted, but no cannon kill", Integer.valueOf(0), none.getCannonKills());
		assertFalse(none.isEmpty());
	}

	@Test
	public void killContextsReadFromAFileAreCleaned()
	{
		final Map<String, Integer> styles = new HashMap<>();
		styles.put("BARRAGE", 4);
		styles.put("CHINCHOMPA", 2);
		styles.put(" ", 3);
		styles.put("MAGIC", 0);
		final SetupKills kills = SetupKills.of(-1, styles, null, 30, 5);
		assertEquals(Integer.valueOf(0), kills.getCannonKills());
		assertEquals("a later build's style is kept", 2, (int) kills.getStyleKills().get("CHINCHOMPA"));
		assertEquals(2, kills.getStyleKills().size());
		assertNull("a range out of order is dropped", kills.getPrayerBonusMin());
		assertSame(SetupKills.NONE, SetupKills.of(null, Collections.emptyMap(), null, null, 4));
	}

	private static <T> List<String> labels(List<SetupChoices.Choice<T>> choices)
	{
		return choices.stream().map(SetupChoices.Choice::getLabel).collect(Collectors.toList());
	}

	@Test
	public void theDialogsFirstChoiceClearsTheOverrideAndShowsTheDetectedValue()
	{
		final SetupTags tags = SetupTags.estimated(true, SetupStyle.BARRAGE)
			.withOverrides(SetupOverrides.of(null, SetupStyle.MELEE, null));
		final List<SetupChoices.Choice<Boolean>> cannon = SetupChoices.cannon(tags);
		assertEquals(Arrays.asList("Detected: ~Yes", "Yes", "No"), labels(cannon));
		assertNull(cannon.get(0).getValue());
		assertEquals(Boolean.FALSE, cannon.get(2).getValue());
		assertEquals(0, SetupChoices.indexOf(cannon, null));

		final List<SetupChoices.Choice<SetupStyle>> style = SetupChoices.style(tags);
		assertEquals(Arrays.asList("Detected: ~Barrage", "Melee", "Ranged", "Barrage", "Magic", "Mixed"), labels(style));
		assertEquals("the override is selected", 1, SetupChoices.indexOf(style, SetupStyle.MELEE));
		assertEquals("Unknown can't be set", 0, SetupChoices.indexOf(style, SetupStyle.UNKNOWN));

		final List<SetupChoices.Choice<GearFocus>> gear = SetupChoices.gear(tags);
		assertEquals(Arrays.asList("Detected: Unknown", "Prayer", "Strength", "Mixed"), labels(gear));
		assertEquals(GearFocus.STRENGTH, gear.get(2).getValue());

		final LedgerRecord record = new LedgerRecord();
		record.setMonster("Dust devils");
		assertEquals("Setup of this Dust devils task:", SetupChoices.prompt(record));
	}
}
