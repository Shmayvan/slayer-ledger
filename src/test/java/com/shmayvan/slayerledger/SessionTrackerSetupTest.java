package com.shmayvan.slayerledger;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import net.runelite.client.util.Filepath;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The live task's setup: the player's overrides set and cleared through {@link SessionTracker}, carried to
 * the saved record and through the active-session file (a logout and a client restart), the kills by NPC kept beside
 * them, and the kill contexts counted per task kill ({@link SessionTracker#addKillContext}) that the detected tags come
 * from. A session with no kill context falls back to its cannonballs for the cannon.
 */
public class SessionTrackerSetupTest
{
	private static final int DEMON_ID = 42;
	private static final long ACCOUNT = 555L;
	private static final int[] COUNTERS = {100, 2, 0};

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final Gson gson = new Gson();
	private long now;
	private SessionTracker tracker;

	@Before
	public void setUp()
	{
		now = 1_000_000L;
		tracker = new SessionTracker(() -> now);
		tracker.observe("Abyssal demons", DEMON_ID, 50, 150, false);
	}

	private static ItemTally items(int id, long quantity)
	{
		final ItemTally tally = new ItemTally();
		tally.add(id, quantity);
		return tally;
	}

	@Test
	public void overridesAreSetAndClearedOnTheLiveTaskAndWinOverTheDetectedValues()
	{
		assertFalse("no session", tracker.setSetupOverrides(null, SetupOverrides.of(true, null, null)));
		assertTrue(tracker.startNow(ACCOUNT));
		final String id = tracker.getSessionId();
		assertEquals(SetupOverrides.NONE, tracker.getSetupOverrides());
		SetupTags tags = tracker.getSetupTags();
		assertFalse(tags.isCannon());
		assertEquals(SetupStyle.UNKNOWN, tags.getStyle());
		assertEquals(GearFocus.UNKNOWN, tags.getGear());
		assertFalse("live tags are never estimates", tags.isCannonEstimated() || tags.isStyleEstimated());

		assertFalse("another session", tracker.setSetupOverrides("other", SetupOverrides.of(true, null, null)));
		final SetupOverrides mine = SetupOverrides.of(true, SetupStyle.BARRAGE, GearFocus.PRAYER);
		assertTrue(tracker.setSetupOverrides(id, mine));
		tags = tracker.getSetupTags();
		assertTrue(tags.isCannon());
		assertEquals(SetupStyle.BARRAGE, tags.getStyle());
		assertEquals(GearFocus.PRAYER, tags.getGear());
		assertFalse("the detected cannon is kept", tags.isDetectedCannon());

		tracker.pause();
		assertTrue("paused", tracker.setSetupOverrides(id, mine.withCannon(null).withGear(null)));
		tags = tracker.getSetupTags();
		assertFalse("cleared: the detected cannon again", tags.isCannon());
		assertEquals(SetupStyle.BARRAGE, tags.getStyle());
		assertEquals(GearFocus.UNKNOWN, tags.getGear());

		assertTrue("all cleared", tracker.setSetupOverrides(id, null));
		assertEquals(SetupOverrides.NONE, tracker.getSetupOverrides());
	}

	@Test
	public void killContextsAreCountedForTheSessionsLastKillAndDecideItsTags()
	{
		assertTrue(tracker.startNow(ACCOUNT));
		assertFalse("no kill yet", tracker.addKillContext(tracker.getLastKillAt(), true, null, null, null));
		now += 600;
		tracker.observe("Abyssal demons", DEMON_ID, 48, 150, false);
		final long killAt = tracker.getLastKillAt();
		assertEquals(2, tracker.getKills());
		assertTrue(tracker.addKillContext(killAt, true, SetupStyle.BARRAGE, GearFocus.PRAYER, 30));
		assertTrue(tracker.addKillContext(killAt, false, null, GearFocus.PRAYER, 30));
		assertFalse("another kill's", tracker.addKillContext(killAt - 1, true, SetupStyle.MELEE, null, null));

		SetupTags tags = tracker.getSetupTags();
		assertTrue("1 cannon kill of 2", tags.isCannon());
		assertEquals("the only styled kill", SetupStyle.BARRAGE, tags.getStyle());
		assertEquals(GearFocus.PRAYER, tags.getGear());

		now += 600;
		tracker.observe("Abyssal demons", DEMON_ID, 47, 150, false);
		assertTrue(tracker.addKillContext(tracker.getLastKillAt(), false, SetupStyle.MELEE, GearFocus.STRENGTH, 8));
		tags = tracker.getSetupTags();
		assertFalse("1 cannon kill of 3", tags.isCannon());
		assertEquals("a tie between Barrage and Melee", SetupStyle.MIXED, tags.getStyle());
		assertEquals(GearFocus.PRAYER, tags.getGear());

		tracker.pause();
		assertFalse("a context arriving after a later kill is refused", tracker.addKillContext(killAt, true, null,
			null, null));
		assertTrue("a stopped session still takes its last kill's", tracker.addKillContext(tracker.getLastKillAt(),
			false, SetupStyle.MELEE, GearFocus.STRENGTH, 6));
		final LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), true);
		assertEquals(Integer.valueOf(1), record.getCannonKills());
		assertEquals(SetupStyle.MELEE, record.getDetectedStyle());
		assertEquals("2 Prayer kills and 2 Strength", GearFocus.MIXED, record.getDetectedGear());
		assertEquals(Integer.valueOf(6), record.getPrayerBonusMin());
		assertEquals(Integer.valueOf(30), record.getPrayerBonusMax());
		assertFalse("no session", tracker.addKillContext(killAt, true, null, null, null));
	}

	@Test
	public void theFinalKillsContextReachesTheCompletionHeldForLateLoot()
	{
		tracker.startNow(ACCOUNT);
		now += 600;
		tracker.observe("Abyssal demons", DEMON_ID, 0, 150, true);
		final long killAt = tracker.getLastKillAt();
		assertTrue(tracker.holdCompletion());
		assertTrue(tracker.addKillContext(killAt, true, SetupStyle.RANGED, GearFocus.STRENGTH, 6));
		final LedgerRecord record = LedgerRecord.fromSummary(tracker.takePendingCompletion(), false);
		assertEquals(Integer.valueOf(1), record.getCannonKills());
		assertEquals(SetupStyle.RANGED, record.getDetectedStyle());
		assertFalse("no longer held", tracker.addKillContext(killAt, true, null, null, null));
	}

	/** With no kill context counted, the detected cannon falls back to the cannonballs fired. */
	@Test
	public void theDetectedCannonComesFromTheCannonballsFiredAndTheOverrideStillWins()
	{
		assertTrue(tracker.startNow(ACCOUNT));
		tracker.addSupplies(items(SupplyTracker.CANNONBALL, 30), null, null);
		assertTrue(tracker.getSetupTags().isCannon());
		assertTrue(tracker.setSetupOverrides(tracker.getSessionId(), SetupOverrides.of(false, null, null)));
		assertFalse(tracker.getSetupTags().isCannon());
		assertTrue(tracker.getSetupTags().isDetectedCannon());

		final LedgerRecord record = LedgerRecord.fromSummary(tracker.stopAndReset(), true);
		assertEquals(Boolean.TRUE, record.getDetectedCannon());
		assertEquals(Boolean.FALSE, record.getCannonOverride());
		assertFalse(SetupTags.of(record).isCannon());
		assertFalse("grouping follows the effective Cannon tag", record.cannonTask());
		assertEquals("Abyssal demons", record.groupName());
	}

	@Test
	public void theOverridesAndKillsByNpcAreCarriedToTheSavedRecord()
	{
		assertTrue(tracker.startNow(ACCOUNT));
		tracker.addNpcKill("Abyssal demon");
		tracker.addNpcKill("Abyssal demon");
		tracker.addNpcKill("Ankou");
		tracker.setSetupOverrides(tracker.getSessionId(), SetupOverrides.of(null, SetupStyle.MELEE, null));
		final SessionTracker.Summary ended = tracker.stopAndReset();
		assertEquals(SetupOverrides.of(null, SetupStyle.MELEE, null), ended.getSetupOverrides());

		final LedgerRecord record = LedgerRecord.fromSummary(ended, false);
		assertEquals(Arrays.asList(new NpcKills("Abyssal demon", 2), new NpcKills("Ankou", 1)), record.getNpcKills());
		assertEquals(SetupStyle.MELEE, record.getStyleOverride());
		assertEquals(SetupStyle.UNKNOWN, record.getDetectedStyle());
		assertEquals(SetupStyle.MELEE, SetupTags.of(record).getStyle());
		assertNull("no kill context counted", record.getCannonKills());
		assertEquals(record, gson.fromJson(gson.toJson(record), LedgerRecord.class));

		assertEquals("the next session starts without overrides", SetupOverrides.NONE, tracker.getSetupOverrides());
		assertFalse("the ended session takes none", tracker.setSetupOverrides(ended.getSessionId(),
			SetupOverrides.of(true, null, null)));
	}

	@Test
	public void anOverrideSetDuringTheGraceWindowReachesTheHeldSession()
	{
		tracker.startNow(ACCOUNT);
		final String id = tracker.getSessionId();
		tracker.observe("Abyssal demons", DEMON_ID, 0, 150, true);
		assertTrue(tracker.holdCompletion());
		assertTrue(tracker.setSetupOverrides(id, SetupOverrides.of(true, null, null)));
		final SessionTracker.Summary held = tracker.takePendingCompletion();
		assertEquals(Boolean.TRUE, LedgerRecord.fromSummary(held, false).getCannonOverride());
		assertFalse("no longer held", tracker.setSetupOverrides(id, SetupOverrides.NONE));
	}

	@Test
	public void overridesAndKillContextsSurviveTheActiveSessionFileAndARestore() throws IOException
	{
		tracker.startNow(ACCOUNT);
		tracker.addNpcKill("Abyssal demon");
		final SetupOverrides mine = SetupOverrides.of(false, SetupStyle.RANGED, GearFocus.STRENGTH);
		tracker.setSetupOverrides(tracker.getSessionId(), mine);
		tracker.loggedOut();
		final SessionSnapshot snapshot = SessionSnapshot.fromSummary(tracker.activeSummary(), COUNTERS, now);
		assertEquals(Boolean.FALSE, snapshot.getCannonOverride());
		assertEquals(SetupStyle.RANGED, snapshot.getStyleOverride());
		assertEquals(GearFocus.STRENGTH, snapshot.getGearOverride());
		assertNull(snapshot.getCannonKills());
		// A snapshot holding kill contexts, as the plugin counts them, must keep them.
		final SetupKills kills = SetupKills.NONE.withKill(true, SetupStyle.RANGED, GearFocus.STRENGTH, 3)
			.withKill(false, SetupStyle.RANGED, GearFocus.MIXED, 18);
		kills.copyTo(snapshot);

		// Through the active-session file, as a logout and a client restart write and read it.
		final LedgerStore store = new LedgerStore(Filepath.Unchecked.getRooted(
			new File(temp.getRoot(), "slayer-ledger").toPath()), gson);
		store.writeActive(ACCOUNT, snapshot);
		final SessionSnapshot read = store.readActive(ACCOUNT);
		assertEquals(snapshot, read);
		assertEquals(SessionSnapshot.CURRENT_SCHEMA_VERSION, read.getSchemaVersion());

		final SessionTracker restored = new SessionTracker(() -> now);
		restored.observe("Abyssal demons", DEMON_ID, 50, 150, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, restored.restore(read, COUNTERS));
		assertEquals(mine, restored.getSetupOverrides());
		assertEquals(kills, restored.getSetupKills());
		assertEquals(SetupStyle.RANGED, restored.getSetupTags().getStyle());
		assertEquals("written back unchanged", read,
			SessionSnapshot.fromSummary(restored.activeSummary(), COUNTERS, read.getSavedAt()));

		final LedgerRecord record = LedgerRecord.fromSummary(restored.stopAndReset(), true);
		assertEquals(Integer.valueOf(1), record.getCannonKills());
		assertEquals(Collections.singletonMap("RANGED", 2), record.getStyleKills());
		assertEquals(Integer.valueOf(3), record.getPrayerBonusMin());
		assertEquals(Integer.valueOf(18), record.getPrayerBonusMax());
		assertEquals(mine, SetupOverrides.from(record));

		assertEquals("as a partial record when it cannot be restored", mine,
			SetupOverrides.from(LedgerRecord.fromSummary(read.toSummary(SlayerSpots.NONE), true)));
		assertEquals(kills, SetupKills.from(LedgerRecord.fromSummary(read.toSummary(SlayerSpots.NONE), true)));
	}

	/**
	 * a cannon task a 1.x version started keeps its Cannon tag after its next kill. Its 10 kills recorded no
	 * context, so the cannon share counts only the kills since the restore; the style and gear never counted them.
	 */
	@Test
	public void aCannonTaskRestoredFromA1xFileCountsOnlyTheKillsSinceForItsCannonTag() throws IOException
	{
		final SessionSnapshot oneX = gson.fromJson("{\"schemaVersion\":2,\"sessionId\":\"s\",\"accountHash\":555,"
			+ "\"monster\":\"Abyssal demons\",\"creatureId\":42,\"originalAmount\":150,\"remaining\":50,\"kills\":10,"
			+ "\"supplies\":{\"" + SupplyTracker.CANNONBALL + "\":120}}", SessionSnapshot.class);
		assertEquals(10, oneX.killsWithoutContexts());
		assertEquals(SessionTracker.RestoreResult.RESTORED, tracker.restore(oneX, COUNTERS));
		assertTrue("no context yet: the cannonballs fired", tracker.getSetupTags().isCannon());
		assertEquals(0, tracker.getContextKills());

		now += 60_000;
		tracker.observe("Abyssal demons", DEMON_ID, 49, 150, false);
		assertTrue(tracker.addKillContext(tracker.getLastKillAt(), true, SetupStyle.MELEE, GearFocus.STRENGTH, 8));
		assertEquals(11, tracker.getKills());
		assertEquals("the live Cannon tooltip's kills", 1, tracker.getContextKills());
		SetupTags tags = tracker.getSetupTags();
		assertTrue("1 cannon kill of the 1 since the restore, not of 11", tags.isCannon());
		assertEquals(SetupStyle.MELEE, tags.getStyle());
		assertEquals(GearFocus.STRENGTH, tags.getGear());

		now += 60_000;
		tracker.observe("Abyssal demons", DEMON_ID, 48, 150, false);
		assertTrue(tracker.addKillContext(tracker.getLastKillAt(), false, SetupStyle.MELEE, GearFocus.STRENGTH, 8));
		assertTrue("1 of 2: half", tracker.getSetupTags().isCannon());
		assertEquals(2, tracker.getContextKills());

		// Logged out and restored after a client restart: the active file keeps the kills without a context.
		tracker.loggedOut();
		final SessionSnapshot persisted = SessionSnapshot.fromSummary(tracker.activeSummary(), COUNTERS, now);
		assertEquals(Integer.valueOf(10), persisted.getKillsBeforeContexts());
		final LedgerStore store = new LedgerStore(Filepath.Unchecked.getRooted(
			new File(temp.getRoot(), "slayer-ledger").toPath()), gson);
		store.writeActive(ACCOUNT, persisted);
		final SessionTracker restored = new SessionTracker(() -> now);
		restored.observe("Abyssal demons", DEMON_ID, 48, 150, false);
		assertEquals(SessionTracker.RestoreResult.RESTORED, restored.restore(store.readActive(ACCOUNT), COUNTERS));
		assertTrue(restored.getSetupTags().isCannon());
		assertEquals(2, restored.getContextKills());
		assertEquals("as a partial record when it cannot be restored", Boolean.TRUE,
			LedgerRecord.fromSummary(persisted.toSummary(SlayerSpots.NONE), true).getDetectedCannon());

		final LedgerRecord record = LedgerRecord.fromSummary(restored.stopAndReset(), true);
		assertEquals(Boolean.TRUE, record.getDetectedCannon());
		assertEquals(Integer.valueOf(1), record.getCannonKills());
		assertEquals("Abyssal demons (cannon)", record.groupName());

		// A third kill without the cannon: 1 of 3 since the restore, so no longer a cannon task.
		final SessionTracker third = new SessionTracker(() -> now);
		third.observe("Abyssal demons", DEMON_ID, 48, 150, false);
		third.restore(store.readActive(ACCOUNT), COUNTERS);
		now += 60_000;
		third.observe("Abyssal demons", DEMON_ID, 47, 150, false);
		assertTrue(third.addKillContext(third.getLastKillAt(), false, SetupStyle.MELEE, GearFocus.STRENGTH, 8));
		assertFalse(third.getSetupTags().isCannon());
	}

	/** a 2.0 session's kills all count for its cannon share, also those whose context was lost. */
	@Test
	public void aRestored2_0SessionCountsAllItsKillsForItsCannonTag()
	{
		final SessionSnapshot mixed = gson.fromJson("{\"schemaVersion\":3,\"sessionId\":\"s\",\"accountHash\":555,"
			+ "\"monster\":\"Abyssal demons\",\"creatureId\":42,\"originalAmount\":150,\"remaining\":50,\"kills\":4,"
			+ "\"cannonKills\":1,\"styleKills\":{\"MELEE\":3}}", SessionSnapshot.class);
		assertEquals(0, mixed.killsWithoutContexts());
		assertEquals(SessionTracker.RestoreResult.RESTORED, tracker.restore(mixed, COUNTERS));
		assertFalse("1 of 4", tracker.getSetupTags().isCannon());
		now += 60_000;
		tracker.observe("Abyssal demons", DEMON_ID, 49, 150, false);
		tracker.addKillContext(tracker.getLastKillAt(), true, null, null, null);
		assertFalse("2 of 5", tracker.getSetupTags().isCannon());
		now += 60_000;
		tracker.observe("Abyssal demons", DEMON_ID, 48, 150, false);
		tracker.addKillContext(tracker.getLastKillAt(), true, null, null, null);
		assertTrue("3 of 6", tracker.getSetupTags().isCannon());
		assertEquals(6, tracker.getContextKills());
		assertNull(SessionSnapshot.fromSummary(tracker.activeSummary(), COUNTERS, now).getKillsBeforeContexts());

		// A 2.0 session whose kills all lost their context (a logout on the next tick) has none left out either.
		final SessionSnapshot lost = gson.fromJson("{\"schemaVersion\":3,\"sessionId\":\"t\",\"accountHash\":555,"
			+ "\"kills\":4}", SessionSnapshot.class);
		assertEquals(0, lost.killsWithoutContexts());
	}

	@Test
	public void aSnapshotFromBeforeSetupRestoresWithNone()
	{
		final SessionSnapshot old = gson.fromJson("{\"schemaVersion\":2,\"sessionId\":\"s\",\"accountHash\":555,"
			+ "\"creatureId\":42,\"remaining\":50,\"kills\":3}", SessionSnapshot.class);
		assertEquals(SessionTracker.RestoreResult.RESTORED, tracker.restore(old, COUNTERS));
		assertEquals(SetupOverrides.NONE, tracker.getSetupOverrides());
		assertEquals(SetupKills.NONE, tracker.getSetupKills());
		assertTrue("overrides can be set after the restore",
			tracker.setSetupOverrides("s", SetupOverrides.of(true, null, null)));
	}
}
