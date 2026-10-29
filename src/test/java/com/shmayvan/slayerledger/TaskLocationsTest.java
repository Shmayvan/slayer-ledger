package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.google.gson.Gson;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.Test;

public class TaskLocationsTest
{
	private static final Gson GSON = new Gson();
	private static final TaskLocations LOCATIONS = TaskLocations.load(GSON, TaskLocations.SNAPSHOT_RESOURCE);

	@Test
	public void bundledSnapshotLoadsWithItsMetadata()
	{
		assertNotNull(LOCATIONS);
		assertEquals("2026-10-27", LOCATIONS.getRetrieved());
		assertEquals("Old School RuneScape Wiki", LOCATIONS.getSource());
		assertEquals("CC BY-NC-SA 3.0", LOCATIONS.getLicense());
		assertTrue(LOCATIONS.getAttribution().contains("Old School RuneScape Wiki contributors"));
		assertTrue(LOCATIONS.tasks().size() > 100);
	}

	@Test
	public void knownTasksListTheWikiLocationsInOrder()
	{
		// Slayer task/Dust devils, revision 15382347 ("Location comparison").
		assertEquals(Arrays.asList("Catacombs of Kourend", "Wilderness Slayer Cave", "Smoke Dungeon"),
			LOCATIONS.locationsFor("Dust devils"));
		// Slayer task/Kurask, revision 15373542.
		assertEquals(Arrays.asList("Fremennik Slayer Dungeon", "Iorwerth Dungeon", "Kurask Lair"),
			LOCATIONS.locationsFor("Kurask"));
		// Floor qualifiers are dropped: Slayer Tower (top floor), (basement) and (roof) are one location.
		assertEquals(Arrays.asList("Slayer Tower"), LOCATIONS.locationsFor("Gargoyles"));
		// From the monster article when there is no task page: Frost dragon, revision 15357143.
		assertEquals(Arrays.asList("Grimstone Dungeon"), LOCATIONS.locationsFor("Frost dragons"));
	}

	@Test
	public void lookupNormalisesTheTaskName()
	{
		List<String> expected = LOCATIONS.locationsFor("Dust devils");
		assertEquals(expected, LOCATIONS.locationsFor("  dust DEVILS "));
		assertEquals(expected, LOCATIONS.locationsFor("DUST DEVILS"));
	}

	@Test
	public void unknownOrMissingTasksHaveNoLocations()
	{
		assertTrue(LOCATIONS.locationsFor("Not a task").isEmpty());
		assertTrue(LOCATIONS.locationsFor("").isEmpty());
		assertTrue(LOCATIONS.locationsFor(null).isEmpty());
		// Boss tasks are not in the snapshot.
		assertTrue(LOCATIONS.locationsFor("Zulrah").isEmpty());
	}

	@Test
	public void locationListsAreUnmodifiable()
	{
		assertUnmodifiable(LOCATIONS.locationsFor("Dust devils"));
		assertUnmodifiable(LOCATIONS.locationsFor("Not a task"));
		assertUnmodifiable(LOCATIONS.tasks());
	}

	@Test
	public void everyTaskNameIsACoreSlayerTaskName() throws Exception
	{
		Set<String> coreNames = coreTaskNames();
		for (String task : LOCATIONS.tasks())
		{
			assertTrue("Not a core Task name: " + task, coreNames.contains(task));
		}
	}

	@Test
	public void everyTaskHasUniqueNonBlankLocations()
	{
		for (String task : LOCATIONS.tasks())
		{
			List<String> locations = LOCATIONS.locationsFor(task);
			assertTrue(task, !locations.isEmpty());
			Set<String> seen = new HashSet<>();
			for (String location : locations)
			{
				assertTrue(task, location != null && !location.trim().isEmpty());
				assertEquals(task, location.trim(), location);
				assertTrue(task + ": " + location, seen.add(location.toLowerCase(Locale.ROOT)));
			}
		}
	}

	/** A ", " followed by a lowercase word: a trailing description ("Lizardman Canyon, west side"). */
	private static final Pattern COMMA_DESCRIPTION = Pattern.compile(", \\p{Ll}");
	/** Directions, positions, levels, parts and rooms, matched case-insensitively. */
	private static final Pattern DESCRIPTIVE_WORDS = Pattern.compile(
		"(?i)\\bnear |\\b(north|south|east|west)(-(east|west))? of\\b|\\blevel |\\bpart |\\brooms?\\b"
			+ "|\\b(outside|inside|around|along|between|beneath|underneath|entrance|side|floor"
			+ "|northern|southern|eastern|western)\\b");
	/** The only disambiguators allowed after a place name. */
	private static final Pattern ALLOWED_SUFFIX = Pattern.compile(" \\((Wilderness|task-only|Cave|west)\\)$");
	/** Stronghold of Security floors are written "Stronghold of Security: Floor"; only the floor counts toward the length. */
	private static final String STRONGHOLD_PREFIX = "Stronghold of Security: ";
	/** The longest place name, "Miscellania and Etceteria Dungeon", is 33 characters. */
	private static final int MAX_NAME_LENGTH = 33;
	/** Small words that stay lowercase in a title-case place name ("Ruins of Tapoyauik", "Burgh de Rott"). */
	private static final Set<String> LOWERCASE_WORDS = new HashSet<>(Arrays.asList("of", "the", "and", "de"));

	@Test
	public void locationsAreShortPlaceNames()
	{
		for (String task : LOCATIONS.tasks())
		{
			for (String location : LOCATIONS.locationsFor(task))
			{
				String where = task + ": " + location;
				assertFalse(where, COMMA_DESCRIPTION.matcher(location).find());
				assertFalse(where, DESCRIPTIVE_WORDS.matcher(location).find());
				String name = location.startsWith(STRONGHOLD_PREFIX)
					? location.substring(STRONGHOLD_PREFIX.length()) : location;
				assertTrue(where, name.length() <= MAX_NAME_LENGTH);
				String base = ALLOWED_SUFFIX.matcher(name).replaceAll("");
				// Only a known disambiguator may follow the name, and no ", " outside the Stronghold prefix.
				assertFalse(where, base.contains("(") || base.contains(", "));
				// Title case, ignoring the allowed disambiguator.
				for (String word : base.split(" "))
				{
					assertTrue(where, LOWERCASE_WORDS.contains(word) || !Character.isLowerCase(word.charAt(0)));
				}
			}
		}
	}

	@Test
	public void locationsUseOneSpellingPerPlace()
	{
		// Aliases replaced by the Wiki's article titles.
		Set<String> banned = new HashSet<>(Arrays.asList(
			"Stronghold Slayer Dungeon", "Fremennik Slayer Cave", "Taverley dungeon", "Killerwatt plane",
			"The Underground Pass", "Edgeville Dungeon (Wilderness)"));
		for (String task : LOCATIONS.tasks())
		{
			for (String location : LOCATIONS.locationsFor(task))
			{
				// Exact match: "Taverley dungeon" is banned, "Taverley Dungeon" is the article title.
				assertFalse(task + ": " + location, banned.contains(location));
			}
		}
	}

	@Test
	public void snapshotCoversEveryRegularCoreTask() throws Exception
	{
		// Boss tasks and Revenants are left out on purpose; a new or dropped task must update this set.
		Set<String> expectedMissing = new HashSet<>(Arrays.asList(EXPECTED_MISSING));
		Set<String> missing = new TreeSet<>(coreTaskNames());
		missing.removeAll(LOCATIONS.tasks());
		assertEquals(new TreeSet<>(expectedMissing), missing);
	}

	private static final String[] EXPECTED_MISSING = {
		// Boss tasks.
		"Araxxor", "Barrows Brothers", "Callisto", "Cerberus", "Commander Zilyana", "Crazy Archaeologists",
		"Dagannoth Kings", "Deranged Archaeologist", "Duke Sucellus", "General Graardor", "K'ril Tsutsaroth",
		"Kree'arra", "Sarachnis", "Scorpia", "The Abyssal Sire", "The Alchemical Hydra", "The Cave Kraken Boss",
		"The Chaos Elemental", "The Chaos Fanatic", "The Giant Mole", "The Grotesque Guardians",
		"The Kalphite Queen", "The King Black Dragon", "The Leviathan", "The Maggot King", "The Phantom Muspah",
		"The Shellbane Gryphon", "The Thermonuclear Smoke Devil", "The Whisperer", "TzKal-Zuk", "TzTok-Jad",
		"Vardorvis", "Venenatis", "Vet'ion", "Vorkath", "Zulrah",
		// Also left out of the snapshot.
		"Revenants",
	};

	@Test
	public void missingMalformedOrInvalidResourcesLoadAsNull()
	{
		assertNull(TaskLocations.load(GSON, "no-such-task-locations.json"));
		assertNull(TaskLocations.load(GSON, "task-locations-malformed.json"));
		// Valid JSON, but the same task twice (after normalising).
		assertNull(TaskLocations.load(GSON, "task-locations-invalid.json"));
	}

	@Test
	public void validationRejectsBadSnapshots()
	{
		assertNotNull(TaskLocations.parse(GSON, new StringReader(snapshot(1, "\"Smoke Dungeon\""))));
		assertRejected("null");
		assertRejected(snapshot(2, "\"Smoke Dungeon\""));
		assertRejected(snapshot(1, ""));
		assertRejected(snapshot(1, "\" \""));
		assertRejected(snapshot(1, "null"));
		assertRejected(snapshot(1, "\"Smoke Dungeon\", \" smoke dungeon \""));
		assertRejected(snapshot(1, "\"Smoke Dungeon\"").replace("\"attribution\": \"Test\"", "\"attribution\": \" \""));
		assertRejected(snapshot(1, "\"Smoke Dungeon\"").replace("\"revision\": 1", "\"revision\": 0"));
		assertRejected(snapshot(1, "\"Smoke Dungeon\"").replace("\"task\": \"Dust devils\"", "\"task\": \"\""));
		assertRejected(snapshot(1, "\"Smoke Dungeon\"").replace(", \"locations\": [\"Smoke Dungeon\"]", ""));
		assertRejected("{ \"version\": 1, \"source\": \"s\", \"license\": \"l\", \"licenseUrl\": \"u\", "
			+ "\"contributors\": \"c\", \"retrieved\": \"r\", \"attribution\": \"a\", \"tasks\": [] }");
		assertRejected("{ \"version\": 1, \"source\": \"s\", \"license\": \"l\", \"licenseUrl\": \"u\", "
			+ "\"contributors\": \"c\", \"retrieved\": \"r\", \"attribution\": \"a\", \"tasks\": [ null ] }");
	}

	private static String snapshot(int version, String locations)
	{
		return "{ \"version\": " + version + ", \"source\": \"Old School RuneScape Wiki\", "
			+ "\"license\": \"CC BY-NC-SA 3.0\", \"licenseUrl\": \"https://creativecommons.org/licenses/by-nc-sa/3.0/\", "
			+ "\"contributors\": \"Old School RuneScape Wiki contributors\", \"retrieved\": \"2026-10-27\", "
			+ "\"attribution\": \"Test\", \"tasks\": [ { \"task\": \"Dust devils\", "
			+ "\"article\": \"Slayer task/Dust devils\", "
			+ "\"page\": \"https://oldschool.runescape.wiki/w/Slayer_task/Dust_devils\", \"revision\": 1"
			+ ", \"locations\": [" + locations + "] } ] }";
	}

	private static void assertRejected(String json)
	{
		try
		{
			TaskLocations.parse(GSON, new StringReader(json));
			fail("Expected the snapshot to be rejected: " + json);
		}
		catch (RuntimeException expected)
		{
			// Rejected, as load() would turn into null.
		}
	}

	private static void assertUnmodifiable(List<String> list)
	{
		try
		{
			list.add("Somewhere");
			fail("Expected an unmodifiable list");
		}
		catch (UnsupportedOperationException expected)
		{
			// Unmodifiable.
		}
	}

	/** The names in RuneLite's core Slayer {@code Task} enum (package-private, so read by reflection). */
	private static Set<String> coreTaskNames() throws Exception
	{
		Class<?> taskClass = Class.forName("net.runelite.client.plugins.slayer.Task");
		Method getName = taskClass.getDeclaredMethod("getName");
		getName.setAccessible(true);
		Set<String> names = new HashSet<>();
		for (Object task : taskClass.getEnumConstants())
		{
			names.add((String) getName.invoke(task));
		}
		assertTrue(names.size() > 100);
		return names;
	}
}
