package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/** Location names: validation, the name a record is saved with, what the panel shows, and the prompt's state. */
public class TaskLocationTest
{
	private static final int SMOKE = 12_946;
	private static final int TOWER = 13_623;

	@Test
	public void namesAreTrimmedCollapsedAndCut()
	{
		assertEquals("Smoke Dungeon", TaskLocation.normalizeName("  Smoke \t  Dungeon\n"));
		assertNull("empty", TaskLocation.normalizeName(""));
		assertNull("blank", TaskLocation.normalizeName(" \n\t "));
		assertNull(TaskLocation.normalizeName(null));
		String longName = "Catacombs of Kourend, the north-westernmost corner";
		String cut = TaskLocation.normalizeName(longName);
		assertEquals(TaskLocation.MAX_NAME_LENGTH, cut.length());
		assertEquals(longName.substring(0, TaskLocation.MAX_NAME_LENGTH).trim(), cut);
	}

	@Test
	public void aRecordIsSavedWithItsAssignedAreaFirst()
	{
		assertEquals("Kraken Cove", TaskLocation.recordName("Kraken Cove", "My cave"));
		assertEquals("My cave", TaskLocation.recordName(null, " My cave "));
		assertEquals("My cave", TaskLocation.recordName("  ", "My cave"));
		assertNull(TaskLocation.recordName(null, null));
	}

	@Test
	public void historyShowsTheRecordsNameThenItsAreaThenItsRegionsNameThenUnknown()
	{
		Map<Integer, String> names = new HashMap<>();
		names.put(SMOKE, "Smoke Dungeon");
		LedgerRecord record = new LedgerRecord();
		assertEquals("an older record", TaskLocation.UNKNOWN, TaskLocation.displayName(record, names::get));

		record.setLocationRegion(TOWER);
		assertEquals("a region nobody named", TaskLocation.UNKNOWN, TaskLocation.displayName(record, names::get));
		record.setLocationRegion(SMOKE);
		assertEquals("named after it was saved", "Smoke Dungeon", TaskLocation.displayName(record, names::get));
		record.setTaskLocation("Kraken Cove");
		assertEquals("an older Konar record", "Kraken Cove", TaskLocation.displayName(record, names::get));
		record.setLocation("Edited");
		assertEquals("Edited", TaskLocation.displayName(record, names::get));
	}

	@Test
	public void aRecordsLocationIsNullWhenUnknownAndFollowsItsRegionOnceNamed()
	{
		Map<Integer, String> names = new HashMap<>();
		LedgerRecord record = new LedgerRecord();
		assertNull(TaskLocation.recordLocation(record, names::get));
		record.setLocationRegion(SMOKE);
		assertNull("saved before the region was named", TaskLocation.recordLocation(record, names::get));
		names.put(SMOKE, " Smoke  Dungeon ");
		assertEquals("named since", "Smoke Dungeon", TaskLocation.recordLocation(record, names::get));
		record.setTaskLocation("Kraken Cove");
		assertEquals("Kraken Cove", TaskLocation.recordLocation(record, names::get));
		record.setLocation("Edited");
		assertEquals("Edited", TaskLocation.recordLocation(record, names::get));
	}

	@Test
	public void theCurrentNameIsTheAreaThenTheSessionsRegionThenWhereThePlayerIs()
	{
		Map<Integer, String> names = new HashMap<>();
		names.put(TOWER, "Slayer Tower");
		assertNull(TaskLocation.currentName(null, null, LocationTally.NO_REGION, names::get));
		assertNull("unnamed", TaskLocation.currentName(null, SMOKE, SMOKE, names::get));
		assertEquals("where the player is", "Slayer Tower", TaskLocation.currentName(null, null, TOWER, names::get));
		assertEquals("an unnamed session region falls back too", "Slayer Tower",
			TaskLocation.currentName(null, SMOKE, TOWER, names::get));
		names.put(SMOKE, "Smoke Dungeon");
		assertEquals("the session's region first", "Smoke Dungeon",
			TaskLocation.currentName(null, SMOKE, TOWER, names::get));
		assertEquals("the assigned area first", "Kraken Cove",
			TaskLocation.currentName(" Kraken Cove ", SMOKE, TOWER, names::get));
		assertEquals("Slayer Tower", TaskLocation.currentName(null, null, TOWER, names::get));
	}

	@Test
	public void locationsMatchIgnoringCaseAndSpacing()
	{
		assertTrue(TaskLocation.sameLocation("Smoke Dungeon", "  smoke   DUNGEON "));
		assertFalse(TaskLocation.sameLocation("Smoke Dungeon", "Smoke Devil Dungeon"));
		assertFalse("no name never matches", TaskLocation.sameLocation(null, null));
		assertFalse(TaskLocation.sameLocation(" ", " "));
		assertFalse(TaskLocation.sameLocation("Smoke Dungeon", null));
		assertEquals("smoke dungeon", TaskLocation.matchKey(" Smoke\tDungeon "));
		assertNull(TaskLocation.matchKey(""));
	}

	@Test
	public void suggestionsAreTheWikisThenThePlayersOwnWithoutRepeats()
	{
		assertEquals(Arrays.asList("Smoke Dungeon", "Catacombs of Kourend", "Ancient cavern", "my spot", "Zul spot"),
			TaskLocation.suggestions(Arrays.asList("Smoke Dungeon", " Catacombs  of Kourend", "smoke dungeon"),
				Arrays.asList("Zul spot", "catacombs of kourend", "my spot", null, " ", "Ancient cavern")));
		assertTrue(TaskLocation.suggestions(null, null).isEmpty());
		assertEquals(Collections.singletonList("Mine"),
			TaskLocation.suggestions(Collections.emptyList(), Collections.singletonList("Mine")));
	}

	@Test
	public void theLiveNameIsTheAreaElseTheRegionsName()
	{
		Map<Integer, String> names = new HashMap<>();
		names.put(SMOKE, "Smoke Dungeon");
		assertEquals("Kraken Cove", TaskLocation.liveName("Kraken Cove", SMOKE, names::get));
		assertEquals("Smoke Dungeon", TaskLocation.liveName(null, SMOKE, names::get));
		assertNull(TaskLocation.liveName(null, TOWER, names::get));
		assertNull("no kills yet", TaskLocation.liveName(null, null, names::get));
		assertEquals("Location: Smoke Dungeon", TaskLocation.format("Smoke Dungeon"));
		assertEquals("Location: Unknown", TaskLocation.format(null));
	}

	@Test
	public void thePromptAsksAboutOneUnnamedRegionAtATime()
	{
		LocationPrompt prompt = new LocationPrompt();
		assertFalse("a named region", prompt.offer(SMOKE, true, false));
		assertFalse("no region", prompt.offer(LocationTally.NO_REGION, false, false));
		assertTrue(prompt.offer(SMOKE, false, false));
		assertEquals(SMOKE, prompt.getShown());
		assertFalse("already shown", prompt.offer(SMOKE, false, false));
		assertFalse("one at a time", prompt.offer(TOWER, false, false));

		prompt.named(TOWER);
		assertEquals("another region's name doesn't close it", SMOKE, prompt.getShown());
		prompt.named(SMOKE);
		assertEquals(LocationTally.NO_REGION, prompt.getShown());
		assertTrue(prompt.offer(TOWER, false, false));
	}

	@Test
	public void aSkippedRegionIsNotAskedAboutAgainUntilReset()
	{
		LocationPrompt prompt = new LocationPrompt();
		assertTrue(prompt.offer(SMOKE, false, false));
		prompt.skip(SMOKE);
		assertEquals(LocationTally.NO_REGION, prompt.getShown());
		assertFalse(prompt.offer(SMOKE, false, false));
		assertTrue(prompt.offer(TOWER, false, false));
		prompt.hide();
		assertTrue("hidden is not skipped", prompt.offer(TOWER, false, false));

		prompt.reset();
		assertEquals(LocationTally.NO_REGION, prompt.getShown());
		assertTrue("a new run asks again", prompt.offer(SMOKE, false, false));
	}

	@Test
	public void aSkipKeptInTheProfileIsNotAskedAboutAndForgetSkipsKeepsTheShownPrompt()
	{
		LocationPrompt prompt = new LocationPrompt();
		assertFalse("skipped in an earlier run", prompt.offer(SMOKE, false, true));
		assertTrue(prompt.offer(TOWER, false, false));
		prompt.skip(TOWER);
		assertTrue(prompt.offer(SMOKE, false, false));
		prompt.forgetSkips();
		assertEquals("still shown", SMOKE, prompt.getShown());
		prompt.hide();
		assertTrue("asked again", prompt.offer(TOWER, false, false));
	}
}
