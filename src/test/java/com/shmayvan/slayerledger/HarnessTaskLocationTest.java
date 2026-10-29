package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.client.events.RuneScapeProfileChanged;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Task locations (Part A) as wired in the plugin ({@link PluginHarness}): the local player's region is read with
 * the task on each settled tick and counted kills are tallied there, the dominant region and its name are saved on the
 * record, an unnamed region asks for a name in the panel (never for a Konar area), names are kept in the RSProfile
 * config, as are skipped regions until the player asks again, the tally survives a restart, and a saved
 * record's location can be edited. {@link LocationTallyTest},
 * {@link TaskLocationTest} and {@link SessionTrackerLocationTest} cover the rules.
 */
public class HarnessTaskLocationTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int TASK = 42;
	private static final int SMOKE = 12_946;
	private static final int CATACOMBS = 6_557;
	private static final int KONAR_AREA = 7;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.startUp();
		harness.setInventory();
		harness.setTask(TASK, "Dust devils", 20, 20);
		harness.setPlayerRegion(SMOKE);
	}

	/** Kills {@code n} more of the task in {@code region} (the count drops on the next tick). */
	private void kill(int n, int region)
	{
		harness.setPlayerRegion(region);
		harness.setRemaining(harness.tracker().getCurrentRemaining() - n);
		harness.tick();
	}

	private int promptRegion()
	{
		final int[] region = new int[1];
		harness.onEdt(() -> region[0] = harness.ui().getLocationPrompt().getRegion());
		return region[0];
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

	/** Types {@code name} in the prompt and clicks Save, then lets the plugin save it. */
	private void saveName(String name)
	{
		harness.onEdt(() -> harness.ui().getLocationPrompt().save(name));
		harness.drain();
	}

	/** Clicks the prompt's Skip, then lets the plugin take it. */
	private void skip()
	{
		harness.onEdt(() -> harness.ui().getLocationPrompt().skip());
		harness.drain();
	}

	/** The history rows' tooltips, once the region names they asked for have arrived. */
	private List<String> tooltips()
	{
		harness.drain();
		final AtomicReference<List<String>> tooltips = new AtomicReference<>();
		harness.onEdt(() -> tooltips.set(harness.ui().getHistory().getTooltips()));
		return tooltips.get();
	}

	private String nameKey(int region)
	{
		return harness.rsProfileConfig(SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX + region);
	}

	@Test
	public void killsInTwoRegionsSaveTheDominantOne()
	{
		harness.setRsProfileConfig(SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX + CATACOMBS,
			"Catacombs");
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		kill(2, SMOKE);
		kill(3, CATACOMBS);
		kill(1, SMOKE);
		assertEquals(Arrays.asList(new LocationKills(SMOKE, 3), new LocationKills(CATACOMBS, 3)),
			harness.tracker().getLocationKills());
		kill(1, CATACOMBS);
		assertEquals("Location: Catacombs", liveLocation());
		AtomicReference<String> details = new AtomicReference<>();
		harness.onEdt(() -> details.set(harness.ui().getTaskBox().getDetailsText()));
		assertEquals("no master or streak known: the location alone", "Catacombs", details.get());
		harness.stop();

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(Integer.valueOf(CATACOMBS), record.getLocationRegion());
		assertEquals("Catacombs", record.getLocation());
		assertTrue(tooltips().get(0), tooltips().get(0).contains("<br>Location: Catacombs<br>"));
	}

	@Test
	public void aTypedLocationFirstOnTheDetailsLineIsNotHtml()
	{
		harness.setRsProfileConfig(SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX + SMOKE,
			"<html><b>x");
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		kill(1, SMOKE);
		assertEquals("Location: <html><b>x", liveLocation());

		// No master or streak known, so the typed location comes first on the line.
		final AtomicReference<String> details = new AtomicReference<>();
		final AtomicReference<String> tooltip = new AtomicReference<>();
		harness.onEdt(() ->
		{
			details.set(harness.ui().getTaskBox().getDetailsText());
			tooltip.set(harness.ui().getTaskBox().getDetailsTooltip());
		});
		assertEquals("a leading space keeps Swing from rendering it as HTML", " <html><b>x", details.get());
		assertTrue(tooltip.get(), tooltip.get().contains("&lt;html&gt;&lt;b&gt;x"));
		assertFalse(tooltip.get(), tooltip.get().contains("<b>"));
	}

	@Test
	public void anUnnamedRegionAsksForANameAndSavingNamesIt()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		assertEquals("no kill yet", LocationTally.NO_REGION, promptRegion());
		kill(1, SMOKE);
		assertEquals(SMOKE, promptRegion());
		assertNull("unnamed, so not shown", liveLocation());

		saveName("   ");
		assertEquals("an empty name is not saved", SMOKE, promptRegion());
		saveName("  Smoke   Dungeon ");
		assertEquals(LocationTally.NO_REGION, promptRegion());
		assertEquals("kept per profile", "Smoke Dungeon", nameKey(SMOKE));
		assertEquals("the running session takes it", "Location: Smoke Dungeon", liveLocation());
		kill(1, SMOKE);
		assertEquals("named, so not asked again", LocationTally.NO_REGION, promptRegion());
		harness.stop();
		assertEquals("Smoke Dungeon", harness.onlyRecord(ACCOUNT).getLocation());
	}

	@Test
	public void aRegionNamedAfterARecordWasSavedNamesItForDisplayOnly()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		kill(2, SMOKE);
		harness.stop();
		LedgerRecord saved = harness.onlyRecord(ACCOUNT);
		assertEquals(Integer.valueOf(SMOKE), saved.getLocationRegion());
		assertNull(saved.getLocation());
		assertTrue(tooltips().get(0).contains("<br>Location: Unknown<br>"));

		assertEquals("the prompt stays after the session", SMOKE, promptRegion());
		saveName("Smoke Dungeon");
		assertTrue(tooltips().get(0), tooltips().get(0).contains("<br>Location: Smoke Dungeon<br>"));
		assertNull("the file is not rewritten", harness.onlyRecord(ACCOUNT).getLocation());
	}

	/** The RSProfile config value of {@code region}'s skip, or {@code null}. */
	private String skipKey(int region)
	{
		return harness.rsProfileConfig(SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.LOCATION_SKIP_KEY_PREFIX + region);
	}

	@Test
	public void aSkipIsKeptInTheProfileAcrossRestartsUntilAskedAgain()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		kill(1, SMOKE);
		skip();
		assertEquals(LocationTally.NO_REGION, promptRegion());
		assertEquals("kept per profile", "true", skipKey(SMOKE));
		kill(1, SMOKE);
		assertEquals("skipped", LocationTally.NO_REGION, promptRegion());
		kill(1, CATACOMBS);
		assertEquals("another region still asks", CATACOMBS, promptRegion());
		skip();
		harness.stop();
		assertNull(nameKey(SMOKE));

		harness.shutDown();
		harness.startUp();
		harness.ticks(PluginHarness.SETTLE_TICKS);
		assertTrue(harness.play());
		kill(1, SMOKE);
		assertEquals("still skipped after a restart", LocationTally.NO_REGION, promptRegion());
		kill(1, CATACOMBS);
		assertEquals(LocationTally.NO_REGION, promptRegion());

		harness.tickResetLocationSkips();
		assertFalse("unticks itself", harness.isResetLocationSkipsTicked());
		assertNull(skipKey(SMOKE));
		assertNull(skipKey(CATACOMBS));
		kill(1, SMOKE);
		assertEquals("asked again", SMOKE, promptRegion());
	}

	@Test
	public void askingAgainWhileThePluginIsOffAppliesOnTheNextStart()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		kill(1, SMOKE);
		skip();
		harness.stop();
		harness.shutDown();

		harness.tickResetLocationSkips();
		harness.startUp();
		assertFalse(harness.isResetLocationSkipsTicked());
		assertNull(skipKey(SMOKE));
		harness.ticks(PluginHarness.SETTLE_TICKS);
		assertTrue(harness.play());
		kill(1, SMOKE);
		assertEquals(SMOKE, promptRegion());
	}

	@Test
	public void askingAgainWithoutAProfileWaitsForOne()
	{
		harness.setNoRsProfileWhileLoggedOut();
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		kill(1, SMOKE);
		skip();
		harness.stop();
		harness.logout();

		harness.tickResetLocationSkips();
		assertTrue("no profile: still ticked", harness.isResetLocationSkipsTicked());
		harness.loginAndSettle(ACCOUNT);
		harness.post(new RuneScapeProfileChanged(null, PluginHarness.RS_PROFILE_KEY));
		assertFalse(harness.isResetLocationSkipsTicked());
		assertNull(skipKey(SMOKE));
		assertTrue(harness.play());
		kill(1, SMOKE);
		assertEquals(SMOKE, promptRegion());
	}

	@Test
	public void aKonarAreaNeedsNoPrompt()
	{
		harness.setAssignment(SlayerMaster.KONAR, KONAR_AREA, "Kraken Cove");
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		kill(2, SMOKE);
		assertEquals(LocationTally.NO_REGION, promptRegion());
		assertEquals("Location: Kraken Cove", liveLocation());
		harness.stop();
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(Integer.valueOf(SMOKE), record.getLocationRegion());
		assertEquals("Kraken Cove", record.getLocation());
	}

	@Test
	public void theFinalKillOfACompletedTaskIsTalliedAndOffered()
	{
		harness.setTask(TASK, "Dust devils", 2, 20);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		kill(1, CATACOMBS);
		skip();
		harness.setPlayerRegion(SMOKE);
		harness.completeTask();
		harness.tick();
		assertTrue(harness.tracker().hasPendingCompletion());
		assertEquals(SMOKE, promptRegion());
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals("a tie goes to the region seen first", Integer.valueOf(CATACOMBS), record.getLocationRegion());
	}

	@Test
	public void pausedKillsAreNotTallied()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		kill(1, SMOKE);
		skip();
		harness.pause();
		kill(3, CATACOMBS);
		assertEquals("no prompt for a paused kill", LocationTally.NO_REGION, promptRegion());
		assertEquals(Collections.singletonList(new LocationKills(SMOKE, 1)), harness.tracker().getLocationKills());
		harness.stop();
		assertEquals(Integer.valueOf(SMOKE), harness.onlyRecord(ACCOUNT).getLocationRegion());
	}

	@Test
	public void noLocationMeansNoTallyAndNoPrompt()
	{
		harness.clearPlayerLocation();
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		kill(2, LocationTally.NO_REGION);
		assertEquals(LocationTally.NO_REGION, promptRegion());
		harness.stop();
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(2, record.getKills());
		assertNull(record.getLocationRegion());
		assertNull(record.getLocation());
	}

	@Test
	public void theRegionTallySurvivesARestore()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		kill(3, CATACOMBS);
		kill(1, SMOKE);
		harness.logout();
		harness.shutDown();
		assertEquals(Arrays.asList(new LocationKills(CATACOMBS, 3), new LocationKills(SMOKE, 1)),
			harness.activeSession(ACCOUNT).getLocationKills());

		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		assertEquals(SessionTracker.State.PAUSED, harness.sessionState());
		assertTrue(harness.play());
		kill(2, SMOKE);
		harness.stop();
		assertEquals(Integer.valueOf(CATACOMBS), harness.onlyRecord(ACCOUNT).getLocationRegion());
	}

	@Test
	public void editingASavedRecordsLocationRewritesOnlyItsName()
	{
		harness.setRsProfileConfig(SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX + SMOKE,
			"Smoke Dungeon");
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		kill(2, SMOKE);
		harness.stop();
		final LedgerRecord saved = harness.onlyRecord(ACCOUNT);
		assertEquals("Smoke Dungeon", saved.getLocation());
		final AtomicReference<List<String>> menu = new AtomicReference<>();
		harness.onEdt(() -> menu.set(harness.ui().getHistory().getMenuItems(0)));
		assertEquals(Arrays.asList("Edit note", "Edit location", "Delete task"), menu.get());
		final AtomicReference<String> hint = new AtomicReference<>();
		harness.onEdt(() -> hint.set(harness.ui().getHistoryEditHint()));
		assertEquals("the hint names every menu item",
			"<html>Right-click a task to edit its note or location, or delete it.</html>", hint.get());

		harness.onEdt(() -> harness.plugin.editRecordLocation(ACCOUNT, saved.getId(), "  Smoke  dungeon (west) "));
		harness.drain();
		LedgerRecord edited = harness.onlyRecord(ACCOUNT);
		assertEquals("Smoke dungeon (west)", edited.getLocation());
		saved.setLocation("Smoke dungeon (west)");
		assertEquals("only the name changes", saved, edited);
		assertEquals("the region's name is kept", "Smoke Dungeon", nameKey(SMOKE));
		assertTrue(tooltips().get(0), tooltips().get(0).contains("<br>Location: Smoke dungeon (west)<br>"));

		harness.onEdt(() -> harness.plugin.editRecordLocation(ACCOUNT, saved.getId(), " "));
		harness.drain();
		assertNull(harness.onlyRecord(ACCOUNT).getLocation());
		assertTrue("its region's name shows again", tooltips().get(0).contains("<br>Location: Smoke Dungeon<br>"));
	}

	@Test
	public void olderRecordsShowUnknown() throws IOException
	{
		assertTrue(harness.ledgerDirectory().isDirectory() || harness.ledgerDirectory().mkdirs());
		String json = "{\"schemaVersion\":2,\"records\":[{\"schemaVersion\":2,\"id\":\"old\",\"monster\":\"Kraken\","
			+ "\"creatureId\":42,\"initialAmount\":3,\"kills\":3,\"activeMillis\":60000,\"startedAt\":1000,"
			+ "\"endedAt\":61000,\"completed\":true,\"partial\":false}]}";
		Files.write(harness.ledgerDirectory().toPath().resolve(ACCOUNT + ".json"), json.getBytes(StandardCharsets.UTF_8));

		harness.loginAndSettle(ACCOUNT);
		LedgerRecord old = harness.onlyRecord(ACCOUNT);
		assertNull(old.getLocationRegion());
		assertNull(old.getLocation());
		String tooltip = tooltips().get(0);
		assertTrue(tooltip, tooltip.contains("<br>Location: Unknown<br>"));
		assertFalse("no region to name", LocationTally.isRegion(promptRegion()));
	}

	@Test
	public void anotherProfilesNamesAreNotUsed()
	{
		harness.setRsProfileConfig(SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX + SMOKE,
			"Smoke Dungeon");
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		kill(1, SMOKE);
		assertEquals("Location: Smoke Dungeon", liveLocation());
		// Another profile becomes active, without the name.
		harness.setRsProfileConfig(SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX + SMOKE, null);
		harness.post(new RuneScapeProfileChanged("a", "b"));
		assertNull(liveLocation());
		kill(1, SMOKE);
		assertEquals(SMOKE, promptRegion());
	}

	@Test
	public void anotherAccountWithoutAProfileChangeDoesNotUseTheNames()
	{
		harness.setNoRsProfileWhileLoggedOut();
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		kill(1, SMOKE);
		saveName("Smoke Dungeon");
		assertEquals("Location: Smoke Dungeon", liveLocation());
		harness.stop();
		harness.logout();

		// The other account has no RuneScape profile yet: no profile change is posted, and its config has no names.
		harness.setRsProfileConfig(SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX + SMOKE, null);
		harness.loginAndSettle(PluginHarness.OTHER_ACCOUNT);
		assertTrue(harness.play());
		kill(1, SMOKE);
		assertNull("the first account's name is not shown", liveLocation());
		assertEquals("the region is asked about", SMOKE, promptRegion());
		harness.stop();
		assertNull("nor saved", harness.onlyRecord(PluginHarness.OTHER_ACCOUNT).getLocation());
		assertEquals("Smoke Dungeon", harness.onlyRecord(ACCOUNT).getLocation());
	}

	@Test
	public void aHeldCompletionIsNamedBeforeTheAccountChanges()
	{
		harness.setRsProfileConfig(SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX + SMOKE,
			"Smoke Dungeon");
		harness.setTask(TASK, "Dust devils", 2, 20);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		kill(1, SMOKE);
		assertEquals("Location: Smoke Dungeon", liveLocation());
		harness.completeTask();
		harness.tick();
		assertTrue(harness.tracker().hasPendingCompletion());

		// Another account, seen on a tick with no profile change, whose config has no names.
		harness.setRsProfileConfig(SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX + SMOKE, null);
		harness.setAccountHash(PluginHarness.OTHER_ACCOUNT);
		harness.tick();
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals("named with its own account's name", "Smoke Dungeon", record.getLocation());

		harness.setTask(TASK, "Dust devils", 20, 20);
		harness.ticks(PluginHarness.SETTLE_TICKS);
		assertTrue(harness.play());
		kill(1, SMOKE);
		assertNull(liveLocation());
		assertEquals(SMOKE, promptRegion());
	}

	@Test
	public void aCompletionSavedAtLogoutIsNamedWithNoProfile()
	{
		harness.setNoRsProfileWhileLoggedOut();
		harness.setRsProfileConfig(SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX + SMOKE,
			"Smoke Dungeon");
		harness.setTask(TASK, "Dust devils", 2, 20);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		kill(1, SMOKE);
		harness.completeTask();
		harness.tick();
		assertTrue(harness.tracker().hasPendingCompletion());
		harness.logout();
		assertNull("no profile at the login screen", harness.readRsProfileConfiguration(SlayerLedgerConfig.GROUP,
			SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX + SMOKE));
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals("named from the names read while logged in", "Smoke Dungeon", record.getLocation());
	}

	@Test
	public void killsFromABoatHaveNoRegion()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		harness.setPlayerOnBoat(true);
		kill(2, SMOKE);
		assertEquals("no prompt", LocationTally.NO_REGION, promptRegion());
		assertTrue(harness.tracker().getLocationKills().isEmpty());
		harness.setPlayerOnBoat(false);
		kill(1, CATACOMBS);
		assertEquals(Collections.singletonList(new LocationKills(CATACOMBS, 1)), harness.tracker().getLocationKills());
		harness.stop();
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(3, record.getKills());
		assertEquals(Integer.valueOf(CATACOMBS), record.getLocationRegion());
	}

	@Test
	public void thePromptsFieldTakesAtMostTheNameLimit()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		kill(1, SMOKE);
		final String typed = "Smoke Dungeon, the far west part near the bridge";
		final AtomicReference<String> field = new AtomicReference<>();
		harness.onEdt(() ->
		{
			harness.ui().getLocationPrompt().save(typed);
			field.set(harness.ui().getLocationPrompt().getText());
		});
		harness.drain();
		assertEquals(typed.substring(0, TaskLocation.MAX_NAME_LENGTH), field.get());
		assertEquals(typed.substring(0, TaskLocation.MAX_NAME_LENGTH).trim(), nameKey(SMOKE));
	}
}
