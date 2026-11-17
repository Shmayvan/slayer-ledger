package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.chat.QueuedMessage;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * The automatic session lifecycle through the plugin's events (V2 plan 4.1): a task kill starts the session,
 * backdated to the player's first hit on the NPCs paired with it, with their kills per NPC and the superior they
 * spawned; hops keep it running; logouts stop it until the next kill; Away; Pause and Resume; End task and Track again;
 * the sidebar's buttons and partial notice; and what is saved or persisted on each path.
 * {@link SessionSegmentsTest} covers the same rules on the tracker alone.
 */
public class HarnessAutomaticTrackingTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int TASK = 42;
	private static final String TASK_NAME = "Abyssal demons";
	private static final int DEMON = 415;
	private static final long MINUTE = 60_000L;
	private static final String SLAUGHTER = "Your bracelet of slaughter prevents your slayer count from decreasing. It has"
		+ " 29 charges left.";
	private static final String EXPEDITIOUS = "Your expeditious bracelet helps you progress your slayer task faster. It"
		+ " has 29 charges left.";
	private static final String SUPERIOR = "<col=ef1020>A superior foe has appeared...</col>";

	@Rule
	public final PluginHarness harness = new PluginHarness();

	/** The tracker's clock: hits, deaths and segments are timed by it. */
	private final AtomicLong now = new AtomicLong(1_000_000_000L);
	private int nextIndex = 1;

	@Before
	public void setUp()
	{
		harness.startUp();
		harness.setTrackerClock(now::get);
		harness.setTask(TASK, TASK_NAME, 50, 50);
		harness.loginAndSettle(ACCOUNT);
	}

	private NPC demon()
	{
		return harness.npc(nextIndex++, DEMON, "Abyssal demon");
	}

	/** The count drops by {@code n} on the next tick. */
	private void countDrops(int n)
	{
		harness.setRemaining(harness.tracker().getCurrentRemaining() - n);
		harness.tick();
	}

	/** A kill with no NPC of the player's dying (a cannon-only kill): the count drops on the next tick. */
	private void cannonKill()
	{
		countDrops(1);
	}

	/** A fight: the player's first hit now, more hits for {@code millis}, then the NPC dies and the count drops. */
	private void fight(long millis)
	{
		final NPC npc = demon();
		harness.hitsplat(npc, true);
		now.addAndGet(millis);
		harness.hitsplat(npc, true);
		harness.npcDies(npc);
		countDrops(1);
	}

	private long active()
	{
		return harness.tracker().getActiveMillis();
	}

	/** The chat lines posted so far. */
	private List<String> chatLines()
	{
		ArgumentCaptor<QueuedMessage> messages = ArgumentCaptor.forClass(QueuedMessage.class);
		verify(harness.chatMessageManager, atLeast(0)).queue(messages.capture());
		return messages.getAllValues().stream().map(QueuedMessage::getRuneLiteFormattedMessage)
			.collect(Collectors.toList());
	}

	/** The overlay's first row, as its two sides, or {@code null} when it shows nothing. */
	private String overlayFirstRow()
	{
		List<OverlayRows.Row> rows = OverlayRows.rows(harness.overlayData(), OverlayRows.Groups.ALL);
		return rows.isEmpty() ? null : rows.get(0).getLeft() + " / " + rows.get(0).getRight();
	}

	/**
	 * The sidebar's status strip after a refresh: the state word, then only the buttons shown (Pause or Resume, End
	 * task or Track again), {@code " (off)"} on one that can't be pressed.
	 */
	private String panel()
	{
		final String[] shown = new String[1];
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			shown[0] = harness.ui().getStatusStrip();
		});
		return shown[0];
	}

	/** The status strip's clock after a refresh: the elapsed time beside "Running", or "" without one. */
	private String clock()
	{
		final String[] shown = new String[1];
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			shown[0] = harness.ui().getStatusClock();
		});
		return shown[0];
	}

	/** The sidebar's partial-save notice after a refresh, or {@code null} while it is hidden. */
	private String partialNotice()
	{
		final String[] shown = new String[1];
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			shown[0] = harness.ui().getPartialNotice();
		});
		return shown[0];
	}

	/** Presses Pause or Resume in the sidebar as it shows now (refreshed, as on screen). */
	private void clickPause()
	{
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			harness.ui().clickPauseButton();
		});
		harness.drain();
	}

	/** Presses End task or Track again in the sidebar as it shows now (refreshed, as on screen). */
	private void clickEnd()
	{
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			harness.ui().clickEndButton();
		});
		harness.drain();
	}

	// ---- Starts ----

	@Test
	public void theFirstKillStartsTheSessionBackdatedToTheFirstHit()
	{
		assertEquals(SessionTracker.Status.WAITING_FOR_FIRST_KILL, harness.status());
		assertEquals("no Pause before the first kill", "Waiting for first kill | End task", panel());
		assertEquals("", clock());
		assertEquals("Waiting for first kill / ", overlayFirstRow());

		now.addAndGet(5 * MINUTE);
		fight(50_000);
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals(1, harness.tracker().getKills());
		assertEquals("the whole first fight counts", 50_000, active());
		assertEquals(Collections.singletonList("abyssal demon"),
			harness.tracker().summary().getSegments().getTaskNpcs());
		assertEquals("Running | Pause | End task", panel());
		assertEquals("the elapsed clock beside Running", "0:50", clock());
		assertEquals("no state line while running", TASK_NAME + " / ", overlayFirstRow());

		// The next tick pairs the kill again: nothing changes.
		harness.tick();
		assertEquals(50_000, active());
	}

	@Test
	public void aBarrageMultiKillIsPairedWithEveryNpcThatDied()
	{
		final NPC first = demon();
		final NPC second = harness.npc(nextIndex++, DEMON, "Greater abyssal demon");
		final NPC third = demon();
		harness.hitsplat(second, true);
		now.addAndGet(10_000);
		harness.hitsplat(first, true);
		now.addAndGet(10_000);
		harness.hitsplat(third, true);
		now.addAndGet(40_000);
		harness.npcDies(first);
		harness.npcDies(second);
		harness.npcDies(third);
		countDrops(3);

		assertEquals(3, harness.tracker().getKills());
		assertEquals("from the earliest engagement start", 60_000, active());
		assertEquals("in the order they died", java.util.Arrays.asList("abyssal demon", "greater abyssal demon"),
			harness.tracker().summary().getSegments().getTaskNpcs());
	}

	@Test
	public void aCannonOnlyFirstKillStartsAtTheKill()
	{
		now.addAndGet(MINUTE);
		cannonKill();
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals(0, active());
		now.addAndGet(5_000);
		assertEquals(5_000, active());
	}

	@Test
	public void anNpcHitLongBeforeOutsideItsEngagementDoesNotStretchTheStart()
	{
		final NPC old = demon();
		harness.hitsplat(old, true);
		now.addAndGet(30 * MINUTE);
		final NPC fresh = demon();
		harness.hitsplat(fresh, true);
		now.addAndGet(20_000);
		harness.npcDies(old);
		harness.npcDies(fresh);
		countDrops(2);

		assertEquals(2, harness.tracker().getKills());
		assertEquals("from the fresh engagement, not the hit half an hour ago", 20_000, active());
	}

	@Test
	public void aDeathOnTheTickAfterTheCountDropStillBackdatesTheStart()
	{
		final NPC npc = demon();
		harness.hitsplat(npc, true);
		now.addAndGet(30_000);
		countDrops(1);
		assertEquals("nothing paired yet: starts at the kill", 0, active());
		harness.npcDies(npc);
		harness.tick();
		assertEquals("backdated once the death is seen", 30_000, active());
	}

	@Test
	public void aBraceletOfSlaughterKillRestartsAStoppedSessionWithoutAKill()
	{
		fight(10_000);
		now.addAndGet(MINUTE);
		harness.pause();
		now.addAndGet(10 * MINUTE);
		// Ten minutes of ticks: the first fight's death is long out of the pairing window.
		harness.ticks(DamagedNpcs.DEATH_KEEP_TICKS + 2);

		final NPC npc = demon();
		harness.hitsplat(npc, true);
		now.addAndGet(15_000);
		harness.npcDies(npc);
		harness.chat(ChatMessageType.GAMEMESSAGE, SLAUGHTER);
		harness.tick();

		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals("the kills follow the task count", 1, harness.tracker().getKills());
		assertEquals(10_000 + MINUTE + 15_000, active());
	}

	@Test
	public void theKillsThatStartAndRestartTheSessionAreTalliedByNpcOnce()
	{
		// Its NPC died before the session ran, so it is tallied by the kill that starts it.
		fight(10_000);
		assertEquals(Collections.singletonList(new NpcKills("Abyssal demon", 1)), harness.tracker().getNpcKills());
		harness.tick();
		assertEquals("not again when the kill is paired on the next tick",
			Collections.singletonList(new NpcKills("Abyssal demon", 1)), harness.tracker().getNpcKills());

		harness.pause();
		now.addAndGet(MINUTE);
		fight(10_000);
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals("the restarting kill's NPC", Collections.singletonList(new NpcKills("Abyssal demon", 2)),
			harness.tracker().getNpcKills());

		// A restart whose NPC dies on the tick after the count drop: tallied as it dies, once.
		harness.pause();
		final NPC npc = demon();
		harness.hitsplat(npc, true);
		countDrops(1);
		harness.npcDies(npc);
		harness.ticks(DamagedNpcs.DEATH_KEEP_TICKS + 1);
		assertEquals(Collections.singletonList(new NpcKills("Abyssal demon", 3)), harness.tracker().getNpcKills());
		assertEquals(3, harness.tracker().getKills());
	}

	@Test
	public void aSuperiorSpawnedByTheKillThatStartsOrRestartsTheSessionCounts()
	{
		// The kill that spawns it: the message comes before the tick its count drops on.
		final NPC npc = demon();
		harness.hitsplat(npc, true);
		harness.npcDies(npc);
		harness.chat(ChatMessageType.GAMEMESSAGE, SUPERIOR);
		countDrops(1);
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals("spawned by the first kill", 1, harness.tracker().getSuperiorsSpawned());

		// A restart on the tick after the message.
		harness.pause();
		harness.chat(ChatMessageType.GAMEMESSAGE, SUPERIOR);
		harness.tick();
		assertEquals(SessionTracker.State.PAUSED, harness.sessionState());
		cannonKill();
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals("held for the tick after its message", 2, harness.tracker().getSuperiorsSpawned());

		// Two ticks before the restart: no kill of the session spawned it.
		harness.pause();
		harness.chat(ChatMessageType.GAMEMESSAGE, SUPERIOR);
		harness.ticks(2);
		cannonKill();
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals(2, harness.tracker().getSuperiorsSpawned());
	}

	@Test
	public void anExpeditiousDoubleDropCountsTwoKills()
	{
		fight(10_000);
		final NPC npc = demon();
		harness.hitsplat(npc, true);
		harness.npcDies(npc);
		harness.chat(ChatMessageType.GAMEMESSAGE, EXPEDITIOUS);
		countDrops(2);
		assertEquals(3, harness.tracker().getKills());
	}

	// ---- Hops, logouts and restores ----

	@Test
	public void aHopKeepsTimeRunning()
	{
		cannonKill();
		now.addAndGet(MINUTE);
		harness.hop();
		harness.ticks(PluginHarness.SETTLE_TICKS);
		assertEquals("a hop is not a stop", SessionTracker.State.RUNNING, harness.sessionState());
		now.addAndGet(MINUTE);
		assertEquals(2 * MINUTE, active());
		assertTrue("no waiting line after a hop", chatLines().isEmpty());
	}

	@Test
	public void aLogoutThenALoginThenAKillRestartsWithoutTheLoggedOutTime()
	{
		cannonKill();
		now.addAndGet(2 * MINUTE);
		harness.logout();
		assertEquals(SessionTracker.State.LOGGED_OUT, harness.sessionState());
		final long loggedOut = now.get();
		SessionSnapshot persisted = harness.activeSession(ACCOUNT);
		assertNotNull("persisted at the logout", persisted);
		assertEquals("LOGGED_OUT", persisted.getTrackingState());
		assertEquals(Long.valueOf(loggedOut), persisted.getSegmentClosedAt());

		now.addAndGet(60 * MINUTE);
		harness.loginAndSettle(ACCOUNT);
		assertEquals("a login is not a restart", SessionTracker.State.LOGGED_OUT, harness.sessionState());
		assertEquals(SessionTracker.Status.WAITING_FOR_NEXT_KILL, harness.status());
		assertEquals(Collections.singletonList(SlayerLedgerPlugin.WAITING_LINE), chatLines());
		assertEquals("Waiting for next kill / ", overlayFirstRow());
		assertEquals("Waiting for next kill | End task", panel());
		harness.ticks(5);
		assertEquals("said once", 1, chatLines().size());

		now.addAndGet(MINUTE);
		fight(15_000);
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals(2, harness.tracker().getKills());
		assertEquals("the logged-out hour is not task time", 2 * MINUTE + 15_000, active());
		assertTrue("the restart is never before the logout",
			harness.tracker().summary().getStartedAt() <= loggedOut);
	}

	@Test
	public void aClientRestartRestoresTheSessionAsLoggedOutAndTheNextKillRestartsIt()
	{
		fight(20_000);
		now.addAndGet(MINUTE);
		harness.logout();
		harness.shutDown();

		harness.startUp();
		harness.setTrackerClock(now::get);
		now.addAndGet(30 * MINUTE);
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(2);
		assertEquals("restored as after a logout, not Paused", SessionTracker.State.LOGGED_OUT,
			harness.sessionState());
		assertTrue(harness.tracker().isRestoredPending());
		assertEquals(Collections.singletonList(SlayerLedgerPlugin.WAITING_LINE), chatLines());
		assertFalse("no Resume after a restore", harness.resume());

		fight(10_000);
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals(2, harness.tracker().getKills());
		assertEquals(20_000 + MINUTE + 10_000, active());
		assertFalse(harness.tracker().isRestoredPending());
	}

	@Test
	public void noWaitingLineWhenTheReloginCheckEndsTheSessionBecauseItsTaskChanged()
	{
		cannonKill();
		harness.logout();
		// A new assignment of the same monster while logged out (on another device): the count went up.
		harness.setTask(TASK, TASK_NAME, 60, 60);
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(2);

		assertEquals("ended by the re-login check", SessionTracker.State.IDLE, harness.sessionState());
		assertTrue("saved as partial", harness.onlyRecord(ACCOUNT).isPartial());
		assertEquals(SessionTracker.Status.WAITING_FOR_FIRST_KILL, harness.status());
		assertFalse(chatLines().contains(SlayerLedgerPlugin.WAITING_LINE));
	}

	@Test
	public void noWaitingLineForASessionEndedBeforeTheLogout()
	{
		fight(10_000);
		harness.endTask();
		harness.logout();
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(2);

		assertEquals(SessionTracker.Status.ENDED, harness.status());
		assertFalse(chatLines().contains(SlayerLedgerPlugin.WAITING_LINE));
	}

	@Test
	public void aKillDuringAPendingRestoreCheckStartsNothingAndTheRestoredSessionRestartsOnTheNextKill()
	{
		fight(20_000);
		now.addAndGet(MINUTE);
		harness.logout();
		harness.shutDown();

		harness.startUp();
		harness.setTrackerClock(now::get);
		harness.login(ACCOUNT);
		harness.ticks(PluginHarness.SETTLE_TICKS - 1);
		// The settled tick reads the task and asks for the saved session; the load has not come back yet.
		harness.tickWithoutDrain();
		// A kill now starts no new session: it would block the restore.
		harness.setRemaining(48);
		harness.tickWithoutDrain();
		assertEquals(SessionTracker.State.IDLE, harness.sessionState());

		harness.drain();
		assertEquals("restored as after a logout", SessionTracker.State.LOGGED_OUT, harness.sessionState());
		assertEquals(1, harness.tracker().getKills());
		now.addAndGet(MINUTE);
		fight(10_000);
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals("the kill during the check was not tracked", 2, harness.tracker().getKills());
		assertEquals(20_000 + MINUTE + 10_000, active());
		assertTrue("no record: no session was started over the restored one", harness.records(ACCOUNT).isEmpty());
	}

	@Test
	public void aOneXActiveFileRestoresAsLoggedOut() throws IOException
	{
		harness.logout();
		harness.shutDown();
		final long saved = now.get() - 5 * MINUTE;
		final String json = "{\"schemaVersion\":2,\"sessionId\":\"from-1x\",\"accountHash\":" + ACCOUNT
			+ ",\"monster\":\"Abyssal demons\",\"creatureId\":" + TASK + ",\"initialAmount\":60,\"originalAmount\":50,"
			+ "\"kills\":10,\"activeMillis\":600000,\"startedAt\":" + (saved - 20 * MINUTE) + ",\"savedAt\":" + saved
			+ ",\"remaining\":50}";
		Files.write(new File(harness.ledgerDirectory(), ACCOUNT + ".active.json").toPath(),
			json.getBytes(StandardCharsets.UTF_8));

		harness.startUp();
		harness.setTrackerClock(now::get);
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(2);
		assertEquals(SessionTracker.State.LOGGED_OUT, harness.sessionState());
		assertEquals(10, harness.tracker().getKills());
		assertEquals(10 * MINUTE, active());

		fight(30_000);
		assertEquals(11, harness.tracker().getKills());
		assertEquals(10 * MINUTE + 30_000, active());
		harness.endTask();
		assertEquals("from-1x", harness.onlyRecord(ACCOUNT).getId());
	}

	// ---- Away ----

	@Test
	public void aBankTripUnderTheLimitIsCounted()
	{
		cannonKill();
		now.addAndGet(4 * MINUTE);
		harness.openInterface(InterfaceID.BANKMAIN);
		harness.closeInterface(InterfaceID.BANKMAIN);
		harness.tick();
		now.addAndGet(4 * MINUTE);
		harness.tick();
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		fight(30_000);
		assertEquals(8 * MINUTE + 30_000, active());
		assertEquals(1, harness.tracker().getBankTrips());
	}

	@Test
	public void aLongSingleKillOnAKnownTaskNpcDoesNotGoAway()
	{
		fight(5_000);
		final NPC boss = demon();
		for (int i = 0; i < 15; i++)
		{
			now.addAndGet(MINUTE);
			harness.hitsplat(boss, true);
			harness.tick();
			assertEquals("minute " + i, SessionTracker.State.RUNNING, harness.sessionState());
		}
		harness.npcDies(boss);
		countDrops(1);
		assertEquals(2, harness.tracker().getKills());
		assertEquals(5_000 + 15 * MINUTE, active());
	}

	@Test
	public void aLongBreakGoesAwayAndTheClockStepsBackOnce()
	{
		cannonKill();
		now.addAndGet(3 * MINUTE);
		cannonKill();
		now.addAndGet(SessionTracker.DEFAULT_AWAY_MINUTES * MINUTE);
		harness.tick();
		assertEquals(SessionTracker.State.AWAY, harness.sessionState());
		assertEquals("the clock steps back to the last kill", 3 * MINUTE, active());
		assertEquals("Away / Back on your next kill", overlayFirstRow());
		assertEquals("Away | End task", panel());

		now.addAndGet(30 * MINUTE);
		harness.ticks(3);
		assertEquals("once", 3 * MINUTE, active());

		fight(20_000);
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals(3 * MINUTE + 20_000, active());
	}

	@Test
	public void fightingOtherNpcsPastTheLimitGoesAway()
	{
		fight(10_000);
		// The kill's pairing is settled (an NPC dying on the next tick would be paired with it).
		harness.tick();
		// A raid: the player hits and kills other NPCs every minute, never a known task NPC, and no task kill.
		final NPC boss = harness.npc(nextIndex++, 7540, "Tekton");
		for (int minute = 1; minute < SessionTracker.DEFAULT_AWAY_MINUTES; minute++)
		{
			now.addAndGet(MINUTE);
			harness.hitsplat(boss, true);
			final NPC add = harness.npc(nextIndex++, 7559, "Deathly ranger");
			harness.hitsplat(add, true);
			harness.npcDies(add);
			harness.tick();
			assertEquals("minute " + minute, SessionTracker.State.RUNNING, harness.sessionState());
		}
		now.addAndGet(MINUTE);
		harness.hitsplat(boss, true);
		harness.tick();
		assertEquals(SessionTracker.State.AWAY, harness.sessionState());
		assertEquals("the raid is not task time", 10_000, active());
		assertEquals(1, harness.tracker().getKills());
	}

	@Test
	public void theAwayLimitFollowsTheSetting()
	{
		harness.setAwayMinutes(2);
		cannonKill();
		now.addAndGet(2 * MINUTE);
		harness.tick();
		assertEquals(SessionTracker.State.AWAY, harness.sessionState());
	}

	// ---- Manual controls ----

	@Test
	public void pauseThenResume()
	{
		cannonKill();
		now.addAndGet(MINUTE);
		clickPause();
		assertEquals(SessionTracker.State.PAUSED, harness.sessionState());
		assertEquals("Paused | Resume | End task", panel());
		assertEquals("Paused / Resume in sidebar", overlayFirstRow());

		now.addAndGet(10 * MINUTE);
		clickPause();
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals("Running | Pause | End task", panel());
		now.addAndGet(MINUTE);
		assertEquals(2 * MINUTE, active());
	}

	@Test
	public void pauseThenAKillRestartIsClampedToThePause()
	{
		cannonKill();
		final NPC npc = demon();
		harness.hitsplat(npc, true);
		now.addAndGet(MINUTE);
		harness.pause();
		final long paused = now.get();
		now.addAndGet(5 * MINUTE);
		harness.hitsplat(npc, true);
		harness.npcDies(npc);
		countDrops(1);

		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals("the fight began before the pause; only the part after it counts", MINUTE + (now.get() - paused),
			active());
	}

	@Test
	public void endTaskThenNoAutoRestartThenTrackAgain()
	{
		fight(10_000);
		clickEnd();
		LedgerRecord partial = harness.onlyRecord(ACCOUNT);
		assertTrue(partial.isPartial());
		assertEquals(1, partial.getKills());
		assertEquals(SessionTracker.Status.ENDED, harness.status());
		assertEquals("Ended | Track again", panel());
		assertEquals("Ended / See sidebar", overlayFirstRow());

		fight(10_000);
		cannonKill();
		assertEquals("no auto-restart on an Ended assignment", SessionTracker.State.IDLE, harness.sessionState());
		assertEquals(1, harness.records(ACCOUNT).size());

		clickEnd();
		assertEquals(SessionTracker.Status.WAITING_FOR_FIRST_KILL, harness.status());
		assertEquals("Waiting for first kill | End task", panel());
		fight(10_000);
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals(1, harness.tracker().getKills());
		assertEquals(1, harness.records(ACCOUNT).size());
	}

	@Test
	public void aSidebarClickActsOnWhatItsButtonShowed()
	{
		final String[] shown = new String[1];
		cannonKill();
		clickPause();
		assertEquals("Paused | Resume | End task", panel());
		// A kill restarts the session before the sidebar refreshes; the player then clicks the Resume it still shows.
		// The sidebar's one-second refresh may already have run here, so the stale label is put back by hand.
		cannonKill();
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		harness.onEdt(() ->
		{
			harness.ui().showPauseButtonAs("Resume");
			shown[0] = harness.ui().getPauseButtonText();
			harness.ui().clickPauseButton();
		});
		harness.drain();
		assertEquals("Resume", shown[0]);
		assertEquals("a Resume click never pauses", SessionTracker.State.RUNNING, harness.sessionState());

		harness.endTask();
		assertEquals("Ended | Track again", panel());
		// A new assignment clears Ended before the sidebar refreshes; the player clicks the Track again it still shows.
		harness.setTask(43, "Black demons", 30, 30);
		harness.tick();
		assertEquals(SessionTracker.Status.WAITING_FOR_FIRST_KILL, harness.status());
		harness.onEdt(() ->
		{
			harness.ui().showEndButtonAs("Track again");
			shown[0] = harness.ui().getEndButtonText();
			harness.ui().clickEndButton();
		});
		harness.drain();
		assertEquals("Track again", shown[0]);
		assertEquals("a Track again click never ends the new assignment", SessionTracker.Status.WAITING_FOR_FIRST_KILL,
			harness.status());
	}

	@Test
	public void aNewAssignmentAfterEndTaskIsTrackedNormally()
	{
		fight(10_000);
		harness.endTask();
		assertEquals(SessionTracker.Status.ENDED, harness.status());
		harness.setTask(43, "Black demons", 30, 30);
		harness.tick();
		assertEquals(SessionTracker.Status.WAITING_FOR_FIRST_KILL, harness.status());
		cannonKill();
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals("Black demons", harness.tracker().getSessionMonster());
	}

	@Test
	public void aLogoutWithOneKillLeftThenTheFinalKillRestartsBeforeTheCompletion()
	{
		cannonKill();
		harness.setRemaining(1);
		harness.tick();
		now.addAndGet(MINUTE);
		harness.logout();
		now.addAndGet(30 * MINUTE);
		harness.loginAndSettle(ACCOUNT);
		assertEquals(SessionTracker.State.LOGGED_OUT, harness.sessionState());

		final NPC npc = demon();
		harness.hitsplat(npc, true);
		now.addAndGet(40_000);
		harness.npcDies(npc);
		harness.completeTask();
		harness.tick();
		assertTrue("restarted first, so held for its loot", harness.tracker().hasPendingCompletion());
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals(50, record.getKills());
		assertEquals("the backdated last fight counts", MINUTE + 40_000, record.getActiveMillis());
		assertNull("its active file goes with the save", harness.activeSession(ACCOUNT));
	}

	// ---- Save paths ----

	@Test
	public void endTaskWhileWaitingSavesNothingAndStaysEndedAcrossAClientRestart()
	{
		harness.endTask();
		assertEquals(SessionTracker.Status.ENDED, harness.status());
		assertEquals(0, harness.promptsShown());
		cannonKill();
		assertEquals(SessionTracker.State.IDLE, harness.sessionState());

		harness.logout();
		SessionSnapshot marker = harness.activeSession(ACCOUNT);
		assertTrue("the Ended assignment's marker", marker.isEndedMarker());
		harness.shutDown();

		harness.startUp();
		harness.setTrackerClock(now::get);
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(2);
		assertEquals("still Ended after the restart", SessionTracker.Status.ENDED, harness.status());
		assertNull("the marker is read once", harness.activeSession(ACCOUNT));
		assertTrue("a marker is never a record", harness.records(ACCOUNT).isEmpty());
		assertTrue(chatLines().isEmpty());
		cannonKill();
		assertEquals(SessionTracker.State.IDLE, harness.sessionState());

		harness.trackAgain();
		cannonKill();
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
	}

	@Test
	public void trackAgainAtTheLoginScreenHoldsAtTheNextLogin()
	{
		fight(10_000);
		harness.endTask();
		harness.logout();
		assertTrue(harness.activeSession(ACCOUNT).isEndedMarker());
		assertEquals("Ended | Track again", panel());

		clickEnd();
		assertNull("Track again deletes the marker", harness.activeSession(ACCOUNT));
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(2);
		assertEquals(SessionTracker.Status.WAITING_FOR_FIRST_KILL, harness.status());
		cannonKill();
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
	}

	@Test
	public void thePartialNoticeStaysWhileTheNextSessionRunsUntilItIsSaved()
	{
		fight(10_000);
		// A new assignment ends the session without End task: saved as partial, with the notice and its Discard.
		harness.setTask(43, "Black demons", 30, 30);
		harness.tick();
		final String notice = "<html>Previous session saved as partial (" + TASK_NAME + ", 1 kills).</html>";
		assertEquals(notice, partialNotice());

		cannonKill();
		assertEquals("Black demons", harness.tracker().getSessionMonster());
		assertEquals("kept when the next session starts", notice, partialNotice());
		harness.ticks(3);
		assertEquals(notice, partialNotice());

		harness.completeTask();
		harness.tick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
		assertEquals(2, harness.records(ACCOUNT).size());
		assertNull("gone once the next session is saved", partialNotice());
	}

	@Test
	public void thePartialNoticesDiscardDeletesItsRecord()
	{
		fight(10_000);
		harness.setTask(43, "Black demons", 30, 30);
		harness.tick();
		assertTrue(harness.onlyRecord(ACCOUNT).isPartial());
		cannonKill();

		harness.onEdt(() ->
		{
			harness.panel().refresh();
			harness.ui().clickPartialNoticeDiscard();
		});
		harness.drain();
		assertTrue(harness.records(ACCOUNT).isEmpty());
		assertNull(partialNotice());
		assertEquals("the running session is untouched", SessionTracker.State.RUNNING, harness.sessionState());
	}

	@Test
	public void aPartialNoticeIsClearedWhenAnotherAccountLogsIn()
	{
		fight(10_000);
		harness.setTask(43, "Black demons", 30, 30);
		harness.tick();
		final String notice = "<html>Previous session saved as partial (" + TASK_NAME + ", 1 kills).</html>";
		assertEquals(notice, partialNotice());
		harness.logout();
		assertEquals("kept at the login screen", notice, partialNotice());

		harness.loginAndSettle(PluginHarness.OTHER_ACCOUNT);
		assertNull("not the other account's to Discard", partialNotice());
		assertEquals("the record stays", 1, harness.records(ACCOUNT).size());
	}

	@Test
	public void aPartialNoticeStaysWhenTheSameAccountLogsInAgain()
	{
		fight(10_000);
		harness.setTask(43, "Black demons", 30, 30);
		harness.tick();
		final String notice = "<html>Previous session saved as partial (" + TASK_NAME + ", 1 kills).</html>";
		harness.logout();
		harness.loginAndSettle(ACCOUNT);
		assertEquals(notice, partialNotice());
	}

	@Test
	public void anEndedMarkerForAnotherTaskIsDroppedWithoutARecord()
	{
		fight(10_000);
		harness.endTask();
		assertEquals(1, harness.records(ACCOUNT).size());
		harness.logout();
		assertTrue(harness.activeSession(ACCOUNT).isEndedMarker());

		harness.setTask(43, "Black demons", 30, 30);
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(2);
		assertEquals(SessionTracker.Status.WAITING_FOR_FIRST_KILL, harness.status());
		assertNull(harness.activeSession(ACCOUNT));
		assertEquals("no second record", 1, harness.records(ACCOUNT).size());
	}

	@Test
	public void aLostConnectionStopsTheSessionLikeALogout()
	{
		cannonKill();
		now.addAndGet(MINUTE);
		harness.setGameState(GameState.CONNECTION_LOST);
		assertEquals(SessionTracker.State.LOGGED_OUT, harness.sessionState());
		now.addAndGet(5 * MINUTE);
		harness.setGameState(GameState.LOGGED_IN);
		harness.ticks(PluginHarness.SETTLE_TICKS);
		assertEquals(Collections.singletonList(SlayerLedgerPlugin.WAITING_LINE), chatLines());
		assertEquals(MINUTE, active());
		cannonKill();
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertTrue(harness.records(ACCOUNT).isEmpty());
	}
}
