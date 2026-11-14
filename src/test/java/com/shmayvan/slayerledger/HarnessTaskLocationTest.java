package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.TestSpots.CATACOMBS;
import static com.shmayvan.slayerledger.TestSpots.CATACOMBS_ID;
import static com.shmayvan.slayerledger.TestSpots.CATACOMBS_WEST;
import static com.shmayvan.slayerledger.TestSpots.CORSAIR;
import static com.shmayvan.slayerledger.TestSpots.CORSAIR_ID;
import static com.shmayvan.slayerledger.TestSpots.HYDRA_LAIR_ID;
import static com.shmayvan.slayerledger.TestSpots.KARUULM_ID;
import static com.shmayvan.slayerledger.TestSpots.KARUULM_SHARED;
import static com.shmayvan.slayerledger.TestSpots.KRAKEN_COVE_ID;
import static com.shmayvan.slayerledger.TestSpots.KRAKEN_ROOM_ID;
import static com.shmayvan.slayerledger.TestSpots.KRAKEN_SHARED;
import static com.shmayvan.slayerledger.TestSpots.SMOKE;
import static com.shmayvan.slayerledger.TestSpots.SMOKE_ID;
import static com.shmayvan.slayerledger.TestSpots.STRONGHOLD;
import static com.shmayvan.slayerledger.TestSpots.STRONGHOLD_ID;
import static com.shmayvan.slayerledger.TestSpots.TOWER;
import static com.shmayvan.slayerledger.TestSpots.TOWER_ID;
import static com.shmayvan.slayerledger.TestSpots.UNLISTED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.client.events.RuneScapeProfileChanged;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Task locations as wired in the plugin ({@link PluginHarness}): each settled tick the player's tile
 * (region, plane and bounds) is read with the task and its counted kills are tallied at its Slayer spot; the spot with
 * the most kills is saved on the record with its region, and named by the player's own names, then the spot table's.
 * Records saved before spots get theirs from their region. A region the table doesn't list is a spot of its own, which
 * the player can rename for every task there; a task renamed on its own keeps its spot. No naming prompt appears.
 * {@link LocationTallyTest}, {@link SlayerSpotsTest}, {@link TaskLocationTest} and {@link SessionTrackerLocationTest}
 * cover the rules.
 */
public class HarnessTaskLocationTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int TASK = 42;
	private static final int KONAR_AREA = 7;
	private static final String UNLISTED_KEY = "region:" + UNLISTED;

	@Rule
	public final PluginHarness harness = new PluginHarness();
	/** The tracker's clock: a minute passes with each kill, so tasks saved one after another sort newest first. */
	private final AtomicLong now = new AtomicLong(1_700_000_000_000L);

	@Before
	public void setUp()
	{
		harness.startUp();
		harness.setTrackerClock(now::get);
		harness.setInventory();
		harness.setTask(TASK, "Dust devils", 20, 20);
		harness.setPlayerRegion(SMOKE);
	}

	/** Kills {@code n} more of the task in {@code region} (the count drops on the next tick). */
	private void kill(int n, int region)
	{
		harness.setPlayerRegion(region);
		killHere(n);
	}

	/** Kills {@code n} more of the task on tile {@code (x, y)} of {@code plane}. */
	private void killAt(int n, int x, int y, int plane)
	{
		harness.setPlayerTile(x, y, plane);
		killHere(n);
	}

	private void killHere(int n)
	{
		now.addAndGet(60_000L);
		harness.setRemaining(harness.tracker().getCurrentRemaining() - n);
		harness.tick();
	}

	/**
	 * The task box's location (its second line's tooltip) after a refresh, once a name it asked the plugin for has
	 * arrived.
	 */
	private String liveLocation()
	{
		harness.onEdt(() -> harness.panel().refresh());
		harness.drain();
		final AtomicReference<String> text = new AtomicReference<>();
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			text.set(harness.ui().getTaskBox().getLocationText());
		});
		return text.get();
	}

	/** The history rows' tooltips, once the region names they asked for have arrived. */
	private List<String> tooltips()
	{
		harness.drain();
		final AtomicReference<List<String>> tooltips = new AtomicReference<>();
		harness.onEdt(() -> tooltips.set(harness.ui().getHistory().getTooltips()));
		return tooltips.get();
	}

	private List<String> locationFilterOptions()
	{
		harness.drain();
		final AtomicReference<List<String>> options = new AtomicReference<>();
		harness.onEdt(() -> options.set(harness.ui().getHistory().getLocationFilterOptions()));
		return options.get();
	}

	private List<String> locationFilterKeys()
	{
		final AtomicReference<List<String>> keys = new AtomicReference<>();
		harness.onEdt(() -> keys.set(harness.ui().getHistory().getLocationFilterKeys()));
		return keys.get();
	}

	private List<String> menuItems(int row)
	{
		final AtomicReference<List<String>> menu = new AtomicReference<>();
		harness.onEdt(() -> menu.set(harness.ui().getHistory().getMenuItems(row)));
		return menu.get();
	}

	private String nameKey(int region)
	{
		return harness.rsProfileConfig(SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX + region);
	}

	private void nameRegion(int region, String name)
	{
		harness.setRsProfileConfig(SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX + region,
			name);
	}

	/** Writes {@code records} (JSON objects) as the account's ledger file, as a 1.x build wrote it. */
	private void writeLedger(String... records) throws IOException
	{
		assertTrue(harness.ledgerDirectory().isDirectory() || harness.ledgerDirectory().mkdirs());
		String json = "{\"schemaVersion\":2,\"records\":[" + String.join(",", records) + "]}";
		Files.write(harness.ledgerDirectory().toPath().resolve(ACCOUNT + ".json"), json.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Writes the account's active-session file of a Dust devils task with 7 kills, as an earlier run left it (schema
	 * {@code version}), with the kills per place {@code tally} (a JSON array).
	 */
	private void writeActiveSession(int version, String tally) throws IOException
	{
		assertTrue(harness.ledgerDirectory().isDirectory() || harness.ledgerDirectory().mkdirs());
		final long saved = now.get() - 5 * 60_000L;
		final String json = "{\"schemaVersion\":" + version + ",\"sessionId\":\"left\",\"accountHash\":" + ACCOUNT
			+ ",\"monster\":\"Dust devils\",\"creatureId\":" + TASK + ",\"initialAmount\":20,\"originalAmount\":20,"
			+ "\"kills\":7,\"activeMillis\":600000,\"startedAt\":" + (saved - 20 * 60_000L) + ",\"savedAt\":" + saved
			+ ",\"remaining\":13,\"locationKills\":" + tally + "}";
		Files.write(new File(harness.ledgerDirectory(), ACCOUNT + ".active.json").toPath(),
			json.getBytes(StandardCharsets.UTF_8));
	}

	/** Logs in with another task than the active-session file's, so the file is saved as a partial task. */
	private LedgerRecord savedAsMismatch()
	{
		harness.setTask(TASK + 1, "Greater demons", 20, 20);
		harness.loginAndSettle(ACCOUNT);
		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isPartial());
		assertEquals("Dust devils", record.getMonster());
		assertNull(harness.activeSession(ACCOUNT));
		return record;
	}

	/** A 1.x record of {@code monster}, completed, with {@code extra} members (for example its region). */
	private static String oneX(String id, String monster, long endedAt, String extra)
	{
		return "{\"schemaVersion\":2,\"id\":\"" + id + "\",\"monster\":\"" + monster + "\",\"creatureId\":42,"
			+ "\"initialAmount\":3,\"kills\":3,\"activeMillis\":60000,\"startedAt\":" + (endedAt - 60_000)
			+ ",\"endedAt\":" + endedAt + ",\"completed\":true,\"partial\":false" + extra + "}";
	}

	@Test
	public void killsInTwoSpotsSaveTheOneWithTheMostKills()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		kill(2, SMOKE);
		kill(3, CATACOMBS);
		kill(1, SMOKE);
		assertEquals("the place killed at most recently last",
			Arrays.asList(new LocationKills(CATACOMBS, CATACOMBS_ID, 3), new LocationKills(SMOKE, SMOKE_ID, 3)),
			harness.tracker().getLocationKills());
		assertEquals("a tie: both list dust devils, so the most recent kill", "Location: Smoke Dungeon",
			liveLocation());
		kill(1, CATACOMBS);
		assertEquals("Location: Catacombs of Kourend", liveLocation());
		AtomicReference<String> details = new AtomicReference<>();
		harness.onEdt(() -> details.set(harness.ui().getTaskBox().getLocationLine()));
		assertEquals("the location's own line", "Catacombs of Kourend", details.get());
		harness.endTask();

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(CATACOMBS_ID, record.getSpotId());
		assertEquals(Integer.valueOf(CATACOMBS), record.getLocationRegion());
		assertNull("no name is saved", record.getLocation());
		assertTrue(tooltips().get(0), tooltips().get(0).contains("<br>Location: Catacombs of Kourend<br>"));
	}

	@Test
	public void catacombsAndStrongholdSpectresAreDifferentSpots()
	{
		harness.setTask(TASK, "Aberrant spectres", 20, 20);
		harness.setPlayerRegion(CATACOMBS);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		kill(5, CATACOMBS);
		harness.endTask();
		harness.trackAgain();
		assertTrue(harness.startNow());
		kill(4, STRONGHOLD);
		assertEquals("Location: Stronghold Slayer Cave", liveLocation());
		harness.endTask();

		List<LedgerRecord> records = harness.records(ACCOUNT);
		assertEquals(2, records.size());
		assertEquals(CATACOMBS_ID, records.get(0).getSpotId());
		assertEquals(STRONGHOLD_ID, records.get(1).getSpotId());
		assertEquals("the same monster, two places", Arrays.asList(LedgerHistory.ALL, "Catacombs of Kourend",
			"Stronghold Slayer Cave"), locationFilterOptions());
	}

	@Test
	public void theHydraLairAndKaruulmShareARegionAndThePlaneDecides()
	{
		harness.setTask(TASK, "Hydras", 20, 20);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		// Region 5536: hydras on the floor above the lair (plane 1) are Karuulm's, the boss's room (plane 0) its own.
		killAt(3, 1364, 10265, 1);
		assertEquals(Collections.singletonList(new LocationKills(KARUULM_SHARED, KARUULM_ID, 3)),
			harness.tracker().getLocationKills());
		assertEquals("Location: Karuulm Slayer Dungeon", liveLocation());
		killAt(4, 1364, 10265, 0);
		assertEquals("Location: Alchemical Hydra's lair", liveLocation());
		harness.endTask();
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(HYDRA_LAIR_ID, record.getSpotId());
		assertEquals(Integer.valueOf(KARUULM_SHARED), record.getLocationRegion());
	}

	@Test
	public void theKrakensBoxSeparatesItsRoomFromTheCove()
	{
		harness.setTask(TASK, "Cave kraken", 20, 20);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		killAt(2, 2250, 10000, 0);
		killAt(1, 2278, 10034, 0);
		assertEquals(Arrays.asList(new LocationKills(KRAKEN_SHARED, KRAKEN_COVE_ID, 2),
			new LocationKills(KRAKEN_SHARED, KRAKEN_ROOM_ID, 1)), harness.tracker().getLocationKills());
		assertEquals("Location: Kraken Cove", liveLocation());
		killAt(2, 2290, 10040, 0);
		harness.endTask();
		assertEquals(KRAKEN_ROOM_ID, harness.onlyRecord(ACCOUNT).getSpotId());
		assertTrue(tooltips().get(0), tooltips().get(0).contains("<br>Location: Kraken Cove boss room<br>"));
	}

	@Test
	public void anUnlistedRegionIsASpotOfItsOwnRenamedForEveryTaskThere()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		kill(2, UNLISTED);
		assertEquals("Location: Region 12342", liveLocation());
		harness.endTask();
		harness.trackAgain();
		assertTrue(harness.startNow());
		kill(1, UNLISTED);
		harness.endTask();
		List<LedgerRecord> records = harness.records(ACCOUNT);
		assertEquals(UNLISTED_KEY, records.get(0).getSpotId());
		assertEquals(UNLISTED_KEY, records.get(1).getSpotId());
		assertEquals(Integer.valueOf(UNLISTED), records.get(0).getLocationRegion());
		for (String tooltip : tooltips())
		{
			assertTrue(tooltip, tooltip.contains("<br>Location: Region 12342<br>"));
		}
		assertEquals("only a task in an unlisted region can rename its place", Arrays.asList("Edit note",
			"Edit location", HistoryListSection.RENAME_PLACE_MENU_ITEM, HistoryListSection.SETUP_MENU_ITEM,
			"Delete task"), menuItems(0));
		assertEquals(Arrays.asList(LedgerHistory.ALL, "Region 12342"), locationFilterOptions());

		// The history's "Rename place": kept per profile, it names every task there and the next one.
		final AtomicReference<Boolean> taken = new AtomicReference<>();
		harness.onEdt(() -> taken.set(harness.plugin.saveLocationName(UNLISTED, "  ")));
		assertFalse("an empty name is not taken", taken.get());
		harness.onEdt(() -> taken.set(harness.plugin.saveLocationName(UNLISTED, "  Edgeville   bank ")));
		assertTrue(taken.get());
		harness.drain();
		assertEquals("Edgeville bank", nameKey(UNLISTED));
		for (String tooltip : tooltips())
		{
			assertTrue(tooltip, tooltip.contains("<br>Location: Edgeville bank<br>"));
		}
		assertEquals(Arrays.asList(LedgerHistory.ALL, "Edgeville bank"), locationFilterOptions());
		assertEquals(Arrays.asList(LedgerHistory.ALL, UNLISTED_KEY), locationFilterKeys());
		harness.trackAgain();
		assertTrue(harness.startNow());
		kill(1, UNLISTED);
		assertEquals("Location: Edgeville bank", liveLocation());
		assertNull("the files are not rewritten", harness.records(ACCOUNT).get(0).getLocation());
	}

	@Test
	public void aRenameTakesAtMostTheNameLimit()
	{
		harness.loginAndSettle(ACCOUNT);
		final String typed = "Edgeville, the far west part near the bridge";
		harness.onEdt(() -> harness.plugin.saveLocationName(UNLISTED, typed));
		harness.drain();
		assertEquals(typed.substring(0, TaskLocation.MAX_NAME_LENGTH).trim(), nameKey(UNLISTED));
	}

	@Test
	public void aOneXNameForARegionNamesItsTasks()
	{
		nameRegion(UNLISTED, "Edgeville");
		nameRegion(CATACOMBS, "Cata");
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		kill(1, UNLISTED);
		assertEquals("Location: Edgeville", liveLocation());
		kill(2, CATACOMBS);
		assertEquals("a listed spot too: the player's name first", "Location: Cata", liveLocation());
		harness.endTask();
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(CATACOMBS_ID, record.getSpotId());
		assertNull("the region's name is not saved, so a later rename shows", record.getLocation());
		assertTrue(tooltips().get(0), tooltips().get(0).contains("<br>Location: Cata<br>"));
		assertEquals("a listed spot is not renamed for all its tasks", Arrays.asList("Edit note", "Edit location",
			HistoryListSection.SETUP_MENU_ITEM, "Delete task"), menuItems(0));
	}

	@Test
	public void aTaskRenamedOnItsOwnKeepsItsSpotAndGroupsWithTheOthers()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		kill(2, SMOKE);
		harness.endTask();
		harness.trackAgain();
		assertTrue(harness.startNow());
		kill(1, SMOKE);
		harness.endTask();
		// The ledger file keeps them in the order saved; the history shows the newest first.
		final LedgerRecord newest = harness.records(ACCOUNT).get(1);
		final AtomicReference<String> hint = new AtomicReference<>();
		harness.onEdt(() -> hint.set(harness.ui().getHistoryEditHint()));
		assertEquals("the hint names every menu item",
			"<html>Right-click a task to edit its note, location or setup, or delete it.</html>", hint.get());

		harness.onEdt(() -> harness.plugin.editRecordLocation(ACCOUNT, newest.getId(), "  Smoke  dungeon (west) "));
		harness.drain();
		LedgerRecord edited = harness.records(ACCOUNT).get(1);
		assertEquals("Smoke dungeon (west)", edited.getLocation());
		assertEquals("the spot is kept", SMOKE_ID, edited.getSpotId());
		newest.setLocation("Smoke dungeon (west)");
		assertEquals("only the name changes", newest, edited);
		assertTrue(tooltips().get(0), tooltips().get(0).contains("<br>Location: Smoke dungeon (west)<br>"));
		assertTrue(tooltips().get(1), tooltips().get(1).contains("<br>Location: Smoke Dungeon<br>"));
		assertEquals("one place, still shown by its spot's name: the rename names that task only",
			Arrays.asList(LedgerHistory.ALL, "Smoke Dungeon"), locationFilterOptions());
		harness.onEdt(() -> harness.ui().getHistory().selectLocationFilter("Smoke Dungeon"));
		harness.drain();
		final AtomicReference<List<String>> rows = new AtomicReference<>();
		harness.onEdt(() -> rows.set(harness.ui().getHistory().getTooltips()));
		assertEquals("both tasks", 2, rows.get().size());

		harness.onEdt(() -> harness.plugin.editRecordLocation(ACCOUNT, newest.getId(), " "));
		harness.drain();
		assertNull(harness.records(ACCOUNT).get(1).getLocation());
		assertTrue("its spot's name shows again", tooltips().get(0).contains("<br>Location: Smoke Dungeon<br>"));
	}

	@Test
	public void recordsSavedBeforeSpotsGetTheirsFromTheirRegion() throws IOException
	{
		writeLedger(
			oneX("stronghold", "Aberrant spectres", 9_000_000, ",\"locationRegion\":9624,\"location\":\"Spectres\""),
			oneX("cove", "Waterfiends", 8_000_000, ",\"locationRegion\":9116"),
			oneX("shared", "Cave kraken", 7_000_000, ",\"locationRegion\":9116"),
			oneX("konar", "Cave kraken", 6_000_000, ",\"locationRegion\":9116,\"taskLocation\":\"Kraken Cove\""),
			oneX("unlisted", "Dust devils", 5_000_000, ",\"locationRegion\":12342"),
			oneX("none", "Dust devils", 4_000_000, ""));
		harness.loginAndSettle(ACCOUNT);
		List<LedgerRecord> records = harness.records(ACCOUNT);
		SlayerSpots spots = TestSpots.TABLE;
		assertEquals(STRONGHOLD_ID, spots.recordSpot(records.get(0)));
		assertEquals("a shared region: the one spot listing the task", KRAKEN_COVE_ID, spots.recordSpot(records.get(1)));
		assertNull("a shared region two of whose spots list the task: Unknown", spots.recordSpot(records.get(2)));
		assertEquals(UNLISTED_KEY, spots.recordSpot(records.get(4)));
		assertNull("no region: Unknown", spots.recordSpot(records.get(5)));
		for (LedgerRecord record : records)
		{
			assertNull("worked out when read, not stored", record.getSpotId());
		}

		List<String> tooltips = tooltips();
		assertTrue(tooltips.get(0), tooltips.get(0).contains("<br>Location: Spectres<br>"));
		assertTrue(tooltips.get(1), tooltips.get(1).contains("<br>Location: Kraken Cove<br>"));
		assertTrue(tooltips.get(2), tooltips.get(2).contains("<br>Location: Unknown<br>"));
		assertTrue("an Unknown spot shows its assigned area", tooltips.get(3).contains("<br>Location: Kraken Cove<br>"));
		assertTrue(tooltips.get(4), tooltips.get(4).contains("<br>Location: Region 12342<br>"));
		assertTrue(tooltips.get(5), tooltips.get(5).contains("<br>Location: Unknown<br>"));
		assertEquals(Arrays.asList(LedgerHistory.ALL, KRAKEN_COVE_ID, HistoryFilter.NAME_PREFIX + "kraken cove",
			UNLISTED_KEY, STRONGHOLD_ID, HistoryFilter.NO_LOCATION), locationFilterKeys());
		assertEquals("two places alike to the eye: the cove's spot, and the Konar task with no spot",
			Arrays.asList(LedgerHistory.ALL, "Kraken Cove", "Kraken Cove", "Region 12342", "Spectres",
				HistoryFilter.NO_LOCATION_LABEL), locationFilterOptions());
	}

	@Test
	public void theOwnersNameForARegionStaysTheNameOfItsSpot() throws IOException
	{
		// Synthetic: region 7820 named "Myth's Guild" in 1.x, which the table calls the Corsair Cove Dungeon. One task
		// saved with that name, one saved before the region was named.
		nameRegion(CORSAIR, "Myth's Guild");
		harness.setTask(TASK, "Blue dragons", 20, 20);
		writeLedger(
			oneX("named", "Blue dragons", 9_000_000, ",\"locationRegion\":7820,\"location\":\"Myth's Guild\""),
			oneX("unnamed", "Red dragons", 8_000_000, ",\"locationRegion\":7820"));
		harness.loginAndSettle(ACCOUNT);
		for (LedgerRecord record : harness.records(ACCOUNT))
		{
			assertEquals(CORSAIR_ID, TestSpots.TABLE.recordSpot(record));
		}
		for (String tooltip : tooltips())
		{
			assertTrue(tooltip, tooltip.contains("<br>Location: Myth's Guild<br>"));
		}
		assertEquals(Arrays.asList(LedgerHistory.ALL, CORSAIR_ID), locationFilterKeys());
		assertEquals(Arrays.asList(LedgerHistory.ALL, "Myth's Guild"), locationFilterOptions());

		// A new task there is the same spot and takes the same name.
		assertTrue(harness.startNow());
		kill(2, CORSAIR);
		assertEquals("Location: Myth's Guild", liveLocation());
		harness.endTask();
		assertEquals(CORSAIR_ID, harness.records(ACCOUNT).get(2).getSpotId());
		assertEquals(Arrays.asList(LedgerHistory.ALL, CORSAIR_ID), locationFilterKeys());
	}

	@Test
	public void theNamingPromptNoLongerAppears()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		kill(1, UNLISTED);
		kill(1, SMOKE);
		harness.endTask();
		harness.drain();
		final AtomicReference<Component> prompt = new AtomicReference<>();
		harness.onEdt(() -> prompt.set(SwingLookup.findOrNull(harness.panel(), "locationPrompt")));
		assertNull("no prompt in the sidebar", prompt.get());
		assertNull("nothing is named by itself", nameKey(UNLISTED));
		assertNull(harness.rsProfileConfig(SlayerLedgerConfig.GROUP, "locationSkip." + UNLISTED));
	}

	@Test
	public void aTypedLocationFirstOnTheDetailsLineIsNotHtml()
	{
		nameRegion(UNLISTED, "<html><b>x");
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		kill(1, UNLISTED);
		assertEquals("Location: <html><b>x", liveLocation());

		// The typed location is the whole of its line.
		final AtomicReference<String> details = new AtomicReference<>();
		final AtomicReference<String> tooltip = new AtomicReference<>();
		harness.onEdt(() ->
		{
			details.set(harness.ui().getTaskBox().getLocationLine());
			tooltip.set(harness.ui().getTaskBox().getDetailsTooltip());
		});
		assertEquals("a leading space keeps Swing from rendering it as HTML", " <html><b>x", details.get());
		assertTrue(tooltip.get(), tooltip.get().contains("&lt;html&gt;&lt;b&gt;x"));
		assertFalse(tooltip.get(), tooltip.get().contains("<b>"));
	}

	@Test
	public void aKonarTaskShowsItsAreaUntilItsKillsDecideItsSpot()
	{
		harness.setAssignment(SlayerMaster.KONAR, KONAR_AREA, "Kraken Cove");
		harness.loginAndSettle(ACCOUNT);
		final AtomicReference<String> details = new AtomicReference<>();
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			details.set(harness.ui().getTaskBox().getLocationLine());
		});
		assertEquals("the assigned area", "Kraken Cove", details.get());
		assertTrue(harness.startNow());
		kill(2, SMOKE);
		assertEquals("Location: Smoke Dungeon", liveLocation());
		harness.endTask();
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(SMOKE_ID, record.getSpotId());
		assertEquals(Integer.valueOf(SMOKE), record.getLocationRegion());
		assertNull(record.getLocation());
		assertEquals("Kraken Cove", record.getTaskLocation());
	}

	@Test
	public void theFinalKillOfACompletedTaskIsTallied()
	{
		harness.setTask(TASK, "Dust devils", 2, 20);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		kill(1, CATACOMBS);
		harness.setPlayerRegion(SMOKE);
		harness.completeTask();
		harness.tick();
		assertTrue(harness.tracker().hasPendingCompletion());
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals("a tie goes to the most recent kill", SMOKE_ID, record.getSpotId());
	}

	@Test
	public void aKillWhilePausedRestartsTheSessionAndIsTallied()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		kill(1, SMOKE);
		harness.pause();
		kill(3, CATACOMBS);
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals(Arrays.asList(new LocationKills(SMOKE, SMOKE_ID, 1), new LocationKills(CATACOMBS, CATACOMBS_ID, 3)),
			harness.tracker().getLocationKills());
		harness.endTask();
		assertEquals(CATACOMBS_ID, harness.onlyRecord(ACCOUNT).getSpotId());
	}

	@Test
	public void noLocationMeansNoTally()
	{
		harness.clearPlayerLocation();
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		killHere(2);
		harness.endTask();
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(2, record.getKills());
		assertNull(record.getLocationRegion());
		assertNull(record.getSpotId());
		assertNull(record.getLocation());
		assertTrue(tooltips().get(0), tooltips().get(0).contains("<br>Location: Unknown<br>"));
	}

	@Test
	public void theTallySurvivesARestore()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		kill(3, CATACOMBS);
		kill(1, SMOKE);
		harness.logout();
		harness.shutDown();
		assertEquals(Arrays.asList(new LocationKills(CATACOMBS, CATACOMBS_ID, 3), new LocationKills(SMOKE, SMOKE_ID, 1)),
			harness.activeSession(ACCOUNT).getLocationKills());

		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		assertEquals(SessionTracker.State.LOGGED_OUT, harness.sessionState());
		assertTrue(harness.startNow());
		kill(1, SMOKE);
		harness.endTask();
		assertEquals(CATACOMBS_ID, harness.onlyRecord(ACCOUNT).getSpotId());
	}

	@Test
	public void aOneXFileSavedForAnotherTaskTakesItsSpotFromTheTable() throws IOException
	{
		// A 1.x tally, by region alone: the Catacombs' two regions have 4 kills between them, the Smoke Dungeon 3.
		writeActiveSession(2, "[{\"region\":" + CATACOMBS + ",\"kills\":2},{\"region\":" + CATACOMBS_WEST
			+ ",\"kills\":2},{\"region\":" + SMOKE + ",\"kills\":3}]");
		LedgerRecord record = savedAsMismatch();
		assertEquals("its regions' spots, as for a live task", CATACOMBS_ID, record.getSpotId());
		assertEquals("the spot's region with the most kills, the most recent of equals", Integer.valueOf(CATACOMBS_WEST),
			record.getLocationRegion());
		assertTrue(tooltips().get(0), tooltips().get(0).contains("<br>Location: Catacombs of Kourend<br>"));
	}

	@Test
	public void aFileSavedForAnotherTaskBreaksATieForTheSpotListingTheTask() throws IOException
	{
		// Two kills each; the Slayer Tower, killed at most recently, has no dust devils.
		writeActiveSession(SessionSnapshot.CURRENT_SCHEMA_VERSION, "[{\"region\":" + CATACOMBS + ",\"spot\":\""
			+ CATACOMBS_ID + "\",\"kills\":2},{\"region\":" + TOWER + ",\"spot\":\"" + TOWER_ID + "\",\"kills\":2}]");
		LedgerRecord record = savedAsMismatch();
		assertEquals(CATACOMBS_ID, record.getSpotId());
		assertEquals(Integer.valueOf(CATACOMBS), record.getLocationRegion());
	}

	@Test
	public void olderRecordsShowUnknown() throws IOException
	{
		writeLedger(oneX("old", "Kraken", 61_000, ""));
		harness.loginAndSettle(ACCOUNT);
		LedgerRecord old = harness.onlyRecord(ACCOUNT);
		assertNull(old.getLocationRegion());
		assertNull(old.getLocation());
		String tooltip = tooltips().get(0);
		assertTrue(tooltip, tooltip.contains("<br>Location: Unknown<br>"));
		assertEquals(Arrays.asList(LedgerHistory.ALL, HistoryFilter.NO_LOCATION_LABEL), locationFilterOptions());
	}

	@Test
	public void anotherProfilesNamesAreNotUsed()
	{
		nameRegion(UNLISTED, "Edgeville");
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		kill(1, UNLISTED);
		assertEquals("Location: Edgeville", liveLocation());
		// Another profile becomes active, without the name.
		nameRegion(UNLISTED, null);
		harness.post(new RuneScapeProfileChanged("a", "b"));
		assertEquals("Location: Region 12342", liveLocation());
	}

	@Test
	public void anotherAccountWithoutAProfileChangeDoesNotUseTheNames()
	{
		harness.setNoRsProfileWhileLoggedOut();
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		kill(1, UNLISTED);
		harness.onEdt(() -> harness.plugin.saveLocationName(UNLISTED, "Edgeville"));
		harness.drain();
		assertEquals("Location: Edgeville", liveLocation());
		harness.endTask();
		harness.logout();

		// The other account has no RuneScape profile yet: no profile change is posted, and its config has no names.
		nameRegion(UNLISTED, null);
		harness.loginAndSettle(PluginHarness.OTHER_ACCOUNT);
		assertTrue(harness.startNow());
		kill(1, UNLISTED);
		assertEquals("the first account's name is not shown", "Location: Region 12342", liveLocation());
		harness.endTask();
		assertEquals(UNLISTED_KEY, harness.onlyRecord(PluginHarness.OTHER_ACCOUNT).getSpotId());
		assertTrue(tooltips().get(0), tooltips().get(0).contains("<br>Location: Region 12342<br>"));
	}

	@Test
	public void aHeldCompletionKeepsItsSpotWhenTheAccountChanges()
	{
		harness.setTask(TASK, "Dust devils", 2, 20);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		kill(1, SMOKE);
		harness.completeTask();
		harness.tick();
		assertTrue(harness.tracker().hasPendingCompletion());

		// Another account, seen on a tick with no profile change.
		harness.setAccountHash(PluginHarness.OTHER_ACCOUNT);
		harness.tick();
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals(SMOKE_ID, record.getSpotId());
		assertEquals(Integer.valueOf(SMOKE), record.getLocationRegion());
	}

	@Test
	public void killsFromABoatHaveNoRegion()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		harness.setPlayerOnBoat(true);
		kill(2, SMOKE);
		assertTrue(harness.tracker().getLocationKills().isEmpty());
		harness.setPlayerOnBoat(false);
		kill(1, CATACOMBS);
		assertEquals(Collections.singletonList(new LocationKills(CATACOMBS, CATACOMBS_ID, 1)),
			harness.tracker().getLocationKills());
		harness.endTask();
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(3, record.getKills());
		assertEquals(CATACOMBS_ID, record.getSpotId());
		assertEquals(Integer.valueOf(CATACOMBS), record.getLocationRegion());
	}
}
