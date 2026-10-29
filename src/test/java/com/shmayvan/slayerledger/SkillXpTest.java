package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;

public class SkillXpTest
{
	private static Map<String, Integer> reading(Object... skillAndXp)
	{
		Map<String, Integer> map = new HashMap<>();
		for (int i = 0; i < skillAndXp.length; i += 2)
		{
			map.put((String) skillAndXp[i], (Integer) skillAndXp[i + 1]);
		}
		return map;
	}

	private static Map<String, Long> xp(Object... skillAndXp)
	{
		Map<String, Long> map = new TreeMap<>();
		for (int i = 0; i < skillAndXp.length; i += 2)
		{
			map.put((String) skillAndXp[i], ((Number) skillAndXp[i + 1]).longValue());
		}
		return map;
	}

	@Test
	public void gainsAreTheSkillsThatWentUp()
	{
		Map<String, Long> gains = SkillXp.gains(reading("SLAYER", 1000, "HITPOINTS", 5000, "PRAYER", 300),
			reading("SLAYER", 1250, "HITPOINTS", 5100, "PRAYER", 300));
		assertEquals(xp("SLAYER", 250, "HITPOINTS", 100), gains);
	}

	@Test
	public void noChangeIsNoGain()
	{
		assertEquals(Collections.emptyMap(), SkillXp.gains(reading("SLAYER", 1000), reading("SLAYER", 1000)));
	}

	@Test
	public void anyDecreaseMeansTheReadingsCantBeCompared()
	{
		// Another account, or a reading from before the stats synced: never a gain, even for the skill that went up.
		assertNull(SkillXp.gains(reading("SLAYER", 1000, "HITPOINTS", 5000),
			reading("SLAYER", 900_000, "HITPOINTS", 4000)));
	}

	@Test
	public void aMissingReadingCantBeCompared()
	{
		assertNull(SkillXp.gains(null, reading("SLAYER", 1)));
		assertNull(SkillXp.gains(reading("SLAYER", 1), null));
	}

	@Test
	public void aSkillMissingFromEitherReadingIsLeftOut()
	{
		assertEquals(xp("SLAYER", 5), SkillXp.gains(reading("SLAYER", 10, "PRAYER", 1),
			reading("SLAYER", 15, "MAGIC", 99)));
	}

	@Test
	public void addMergesAndSaturates()
	{
		Map<String, Long> into = xp("SLAYER", 100);
		SkillXp.add(into, xp("SLAYER", 50, "MAGIC", 10));
		SkillXp.add(into, null);
		assertEquals(xp("SLAYER", 150, "MAGIC", 10), into);
		SkillXp.add(into, xp("MAGIC", Long.MAX_VALUE));
		assertEquals(Long.valueOf(Long.MAX_VALUE), into.get("MAGIC"));
	}

	@Test
	public void sanitizeKeepsOnlyPositiveEntries()
	{
		Map<String, Long> raw = new HashMap<>();
		raw.put("SLAYER", 10L);
		raw.put("MAGIC", 0L);
		raw.put("PRAYER", -5L);
		raw.put("RANGED", null);
		raw.put(null, 7L);
		assertEquals(xp("SLAYER", 10), SkillXp.sanitize(raw));
		assertTrue(SkillXp.sanitize(null).isEmpty());
	}

	@Test
	public void totalAddsEverySkill()
	{
		assertEquals(360, SkillXp.total(xp("SLAYER", 250, "HITPOINTS", 100, "PRAYER", 10)));
		assertEquals(0, SkillXp.total(null));
	}

	@Test
	public void perHourNeedsAMinuteOfActiveTime()
	{
		assertNull(SkillXp.perHour(1000, TaskTimeEstimate.MIN_ACTIVE_MILLIS - 1));
		assertEquals(Long.valueOf(60_000), SkillXp.perHour(1000, TaskTimeEstimate.MIN_ACTIVE_MILLIS));
		assertEquals(Long.valueOf(40_000), SkillXp.perHour(20_000, 30 * 60_000));
	}

	@Test
	public void skillNames()
	{
		assertEquals("Hitpoints", SkillXp.skillName("HITPOINTS"));
		assertEquals("Slayer", SkillXp.skillName("SLAYER"));
		assertEquals("Unknown skill", SkillXp.skillName(null));
	}

	@Test
	public void totalTextShowsTheRateOnceThereIsOne()
	{
		Map<String, Long> gained = xp("SLAYER", 15_000, "HITPOINTS", 5_000);
		assertEquals("XP: 20K", SkillXp.totalText(gained, 30_000));
		assertEquals("XP: 20K (40K/h)", SkillXp.totalText(gained, 30 * 60_000));
		assertEquals("XP: 0 (0/h)", SkillXp.totalText(xp(), 30 * 60_000));
	}

	@Test
	public void unrecordedXpShowsADash()
	{
		assertEquals("XP: -", SkillXp.totalText(null, 60_000));
		assertTrue(SkillXp.rows(null, 60_000).isEmpty());
		assertFalse(SkillXp.tooltip(null, 60_000).contains("<html>"));
	}

	@Test
	public void rowsAreHighestFirstThenByName()
	{
		List<SkillXp.Row> rows = SkillXp.rows(xp("PRAYER", 100, "SLAYER", 900, "MAGIC", 100), 60 * 60_000);
		assertEquals(Arrays.asList("Slayer", "Magic", "Prayer"),
			Arrays.asList(rows.get(0).getName(), rows.get(1).getName(), rows.get(2).getName()));
		assertEquals("900", rows.get(0).amountText());
		assertEquals(Long.valueOf(900), rows.get(0).getPerHour());
		assertEquals("<html>Slayer: 900 XP<br>900 XP per hour</html>", rows.get(0).tooltip());
		assertNull("no rate without enough active time", SkillXp.rows(xp("SLAYER", 1), 0).get(0).getPerHour());
	}

	@Test
	public void tooltipListsEverySkill()
	{
		assertEquals("<html>XP gained: 1,500<br>XP per hour: 1,500<br>Slayer: 1,000<br>Hitpoints: 500"
			+ "<br>Counted only while the session runs.</html>",
			SkillXp.tooltip(xp("SLAYER", 1000, "HITPOINTS", 500), 60 * 60_000));
	}
}
