package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.List;
import java.util.concurrent.Future;
import net.runelite.api.ChatMessageType;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * How a running session leaves memory, through the plugin's wiring ({@link PluginHarness}): logout keeps it and
 * persists a copy, plugin stop and client exit persist it as the active session, the next login restores it (or saves
 * it as partial when the task no longer matches), Stop saves it as partial, and each path saves exactly once. Also
 * checks that loot counts only while the session is RUNNING. {@link SessionRestoreTest} and
 * {@link LootTrackingTest} cover the tracker's side.
 */
public class HarnessSessionEndTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int TASK = 42;
	private static final String TASK_NAME = "Abyssal demons";

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.BONES, "Bones", 100, 1);
		harness.defineItem(ItemID.ABYSSAL_WHIP, "Abyssal whip", 1_500_000, 72_000);
		harness.startUp();
		harness.setTask(TASK, TASK_NAME, 10, 10);
		harness.loginAndSettle(ACCOUNT);
	}

	/** Plays and kills two of the ten (two kills, one bones). */
	private void playAndKillTwo()
	{
		assertTrue(harness.play());
		harness.setRemaining(8);
		harness.lootOnNextTick(ItemID.BONES, 1);
		harness.tick();
	}

	@Test
	public void logoutPersistsTheSessionAndReloginResumesItInMemory()
	{
		playAndKillTwo();
		harness.logout();

		assertEquals(SessionTracker.State.PAUSED, harness.sessionState());
		SessionSnapshot active = harness.activeSession(ACCOUNT);
		assertNotNull("persisted in case the client is closed", active);
		assertEquals(2, active.getKills());
		assertEquals(8, active.getRemaining());
		assertEquals(Long.valueOf(1), active.getLoot().get(ItemID.BONES));
		assertTrue("no record on logout", harness.records(ACCOUNT).isEmpty());

		harness.loginAndSettle(ACCOUNT);
		assertEquals("kept in memory, paused", SessionTracker.State.PAUSED, harness.sessionState());
		assertTrue(harness.canPlay());
		assertTrue(harness.play());
		harness.setRemaining(7);
		harness.tick();
		harness.stop();

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isPartial());
		assertEquals(3, record.getKills());
		assertEquals(active.getSessionId(), record.getId());
		assertNull("the active file goes once the record is saved", harness.activeSession(ACCOUNT));
	}

	@Test
	public void sessionPersistedAtLogoutIsRestoredFromFileAfterARestart()
	{
		playAndKillTwo();
		harness.logout();
		final String sessionId = harness.activeSession(ACCOUNT).getSessionId();
		harness.shutDown();
		assertNotNull(harness.activeSession(ACCOUNT));
		assertTrue(harness.records(ACCOUNT).isEmpty());

		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		assertEquals("restored as paused", SessionTracker.State.PAUSED, harness.sessionState());
		assertTrue(harness.tracker().isRestoredPending());
		assertTrue(harness.play());
		harness.setRemaining(7);
		harness.lootOnNextTick(ItemID.ABYSSAL_WHIP, 1);
		harness.tick();
		harness.stop();

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(sessionId, record.getId());
		assertEquals(3, record.getKills());
		assertEquals(Long.valueOf(1), record.getLoot().get(ItemID.BONES));
		assertEquals(Long.valueOf(1), record.getLoot().get(ItemID.ABYSSAL_WHIP));
		assertNull(harness.activeSession(ACCOUNT));
	}

	@Test
	public void pluginStopWhileRunningPersistsTheActiveSessionWithoutARecord()
	{
		playAndKillTwo();
		harness.shutDown();

		SessionSnapshot active = harness.activeSession(ACCOUNT);
		assertNotNull(active);
		assertEquals(2, active.getKills());
		assertTrue(harness.records(ACCOUNT).isEmpty());

		harness.startUp();
		harness.ticks(PluginHarness.SETTLE_TICKS);
		assertEquals("restored after the plugin is turned back on", SessionTracker.State.PAUSED,
			harness.sessionState());
		assertEquals(2, harness.tracker().getKills());
		assertTrue(harness.records(ACCOUNT).isEmpty());
	}

	@Test
	public void clientExitWhileRunningPersistsTheActiveSessionAndWaitsForTheWrite()
	{
		playAndKillTwo();
		List<Future<?>> waitedFor = harness.clientShutdown();

		assertEquals(1, waitedFor.size());
		assertTrue(waitedFor.get(0).isDone());
		SessionSnapshot active = harness.activeSession(ACCOUNT);
		assertNotNull(active);
		assertEquals(2, active.getKills());
		assertTrue(harness.records(ACCOUNT).isEmpty());
	}

	@Test
	public void reloginWithADifferentTaskSavesThePartialSessionOnce()
	{
		playAndKillTwo();
		harness.logout();
		harness.setTask(TASK + 1, "Greater demons", 20, 20);
		harness.loginAndSettle(ACCOUNT);

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isPartial());
		assertFalse(record.isCompleted());
		assertEquals(TASK_NAME, record.getMonster());
		assertEquals(2, record.getKills());
		assertNull(harness.activeSession(ACCOUNT));
		assertEquals(SessionTracker.State.IDLE, harness.sessionState());

		harness.ticks(5);
		harness.logout();
		harness.shutDown();
		assertEquals(1, harness.records(ACCOUNT).size());
	}

	@Test
	public void restoredFileWithADifferentTaskIsSavedAsPartial()
	{
		playAndKillTwo();
		harness.shutDown();
		harness.logout();
		harness.setTask(TASK + 1, "Greater demons", 20, 20);
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isPartial());
		assertEquals(TASK_NAME, record.getMonster());
		assertNull(harness.activeSession(ACCOUNT));
		assertEquals(SessionTracker.State.IDLE, harness.sessionState());
	}

	@Test
	public void worldHopPausesTheSessionWithoutSavingIt()
	{
		playAndKillTwo();
		harness.hop();
		harness.ticks(PluginHarness.SETTLE_TICKS);

		assertEquals(SessionTracker.State.PAUSED, harness.sessionState());
		assertTrue(harness.records(ACCOUNT).isEmpty());
		assertTrue(harness.play());
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
	}

	@Test
	public void stopSavesAPartialRecordOnce()
	{
		playAndKillTwo();
		harness.stop();

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isPartial());
		assertEquals(2, record.getKills());
		assertEquals(SessionTracker.State.IDLE, harness.sessionState());

		harness.ticks(3);
		harness.logout();
		harness.clientShutdown();
		harness.shutDown();
		assertEquals(1, harness.records(ACCOUNT).size());
		assertNull(harness.activeSession(ACCOUNT));
	}

	@Test
	public void accountSwitchPersistsTheOtherAccountsSession()
	{
		playAndKillTwo();
		harness.logout();
		harness.loginAndSettle(PluginHarness.OTHER_ACCOUNT);

		assertEquals(SessionTracker.State.IDLE, harness.sessionState());
		SessionSnapshot active = harness.activeSession(ACCOUNT);
		assertNotNull(active);
		assertEquals(2, active.getKills());
		assertTrue(harness.records(ACCOUNT).isEmpty());
		assertTrue(harness.records(PluginHarness.OTHER_ACCOUNT).isEmpty());
		assertNull(harness.activeSession(PluginHarness.OTHER_ACCOUNT));
	}

	@Test
	public void lootCountsOnlyWhileRunning()
	{
		harness.lootOnNextTick(ItemID.ABYSSAL_WHIP, 1);
		harness.tick();
		assertTrue(harness.play());
		harness.lootOnNextTick(ItemID.BONES, 1);
		harness.tick();
		harness.pause();
		harness.lootOnNextTick(ItemID.ABYSSAL_WHIP, 2);
		harness.tick();
		harness.postLoot(ItemID.ABYSSAL_WHIP, 3);
		assertTrue(harness.play());
		harness.postLoot(ItemID.BONES, 2);
		harness.stop();
		harness.lootOnNextTick(ItemID.ABYSSAL_WHIP, 4);
		harness.tick();

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(Long.valueOf(3), record.getLoot().get(ItemID.BONES));
		assertNull("idle, paused and stopped loot is not counted", record.getLoot().get(ItemID.ABYSSAL_WHIP));
	}

	@Test
	public void pickpocketLootIsNotCounted()
	{
		assertTrue(harness.play());
		harness.chat(ChatMessageType.SPAM, "You pick the H.A.M. Member's pocket.");
		// The server sends the pickpocket's NPC loot on that tick.
		harness.lootOnNextTick(ItemID.ABYSSAL_WHIP, 1);
		harness.tick();
		harness.lootOnNextTick(ItemID.BONES, 1);
		harness.tick();
		harness.stop();

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertNull(record.getLoot().get(ItemID.ABYSSAL_WHIP));
		assertEquals(Long.valueOf(1), record.getLoot().get(ItemID.BONES));
	}
}
