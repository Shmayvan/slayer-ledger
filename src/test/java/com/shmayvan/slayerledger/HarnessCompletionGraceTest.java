package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Future;
import net.runelite.api.ChatMessageType;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.chat.QueuedMessage;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * The late-loot grace window as wired in the plugin ({@link PluginHarness}): the priority -1 grace ticker runs after
 * the default-priority handlers and LootManager's loot, a completed session is saved exactly once as completed when
 * the window ends, and every other way out (logout, hop, plugin stop, client exit) saves it at once.
 * {@link CompletionGraceTest} covers the tracker's side of the window.
 */
public class HarnessCompletionGraceTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int TASK = 42;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.BONES, "Bones", 100, 1);
		harness.defineItem(ItemID.ABYSSAL_WHIP, "Abyssal whip", 1_500_000, 72_000);
		harness.defineItem(ItemID.COINS, "Coins", 1, 1);
		harness.startUp();
		harness.setTask(TASK, "Abyssal demons", 3, 3);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		harness.setRemaining(1);
		harness.lootOnNextTick(ItemID.BONES, 1);
		harness.tick();
	}

	/** Kills the last monster: the completion is read on this tick and the session is held for late loot. */
	private void completeOnNextTick()
	{
		harness.completeTask();
		harness.tick();
		assertTrue("held for late loot", harness.tracker().hasPendingCompletion());
		assertTrue("not saved during the grace window", harness.records(ACCOUNT).isEmpty());
	}

	@Test
	public void lootPostedOnTheCompletionTickIsCounted()
	{
		harness.completeTask();
		// LootManager posts this during the completion tick. It counts whichever side of the hold it lands on, so this
		// does not test ordering; lootWithinTheGraceWindowCountsAndLaterLootDoesNot does.
		harness.lootOnNextTick(ItemID.ABYSSAL_WHIP, 1);
		harness.tick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertFalse(record.isPartial());
		assertEquals(3, record.getKills());
		assertEquals(Long.valueOf(1), record.getLoot().get(ItemID.ABYSSAL_WHIP));
		assertEquals(Long.valueOf(1), record.getLoot().get(ItemID.BONES));
		// Priced on the client thread when saved (the harness fails the test on an off-thread ItemManager call).
		assertEquals(Long.valueOf(1_500_100), record.getLootGeValue());
	}

	@Test
	public void successfulCompletionPostsOneChatSummaryAndNoNotificationByDefault()
	{
		harness.lootOnNextTick(ItemID.ABYSSAL_WHIP, 1);
		completeOnNextTick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);

		ArgumentCaptor<QueuedMessage> message = ArgumentCaptor.forClass(QueuedMessage.class);
		verify(harness.chatMessageManager, times(1)).queue(message.capture());
		assertEquals(ChatMessageType.CONSOLE, message.getValue().getType());
		assertEquals("Slayer Ledger", message.getValue().getName());
		assertTrue(message.getValue().getRuneLiteFormattedMessage().startsWith("Slayer task complete: 3 Abyssal demons · "));
		assertTrue(message.getValue().getRuneLiteFormattedMessage().endsWith(" · +1.5M"));
		verifyNoInteractions(harness.notifier);
	}

	@Test
	public void notificationCanBeEnabledForSuccessfulCompletion()
	{
		harness.config.taskCompleteNotification = true;
		completeOnNextTick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);

		ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
		verify(harness.notifier, times(1)).notify(message.capture());
		assertTrue(message.getValue().startsWith("Slayer task complete: 3 Abyssal demons · "));
	}

	@Test
	public void completionSummaryIsPostedOnlyOnce()
	{
		completeOnNextTick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
		harness.ticks(5);
		harness.logout();

		verify(harness.chatMessageManager, times(1)).queue(any(QueuedMessage.class));
	}

	@Test
	public void failedSaveDoesNotPostCompletionSummary() throws IOException
	{
		harness.config.taskCompleteNotification = true;
		// A ledger from a newer plugin version is never overwritten, so the completed record's append fails.
		String json = "{\"schemaVersion\":" + (LedgerStore.FILE_SCHEMA_VERSION + 1) + ",\"records\":[]}";
		Path ledger = harness.ledgerDirectory().toPath().resolve(ACCOUNT + ".json");
		Files.write(ledger, json.getBytes(StandardCharsets.UTF_8));

		completeOnNextTick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);

		assertFalse("the save was attempted", harness.tracker().hasPendingCompletion());
		assertEquals("the newer ledger is left alone", json, new String(Files.readAllBytes(ledger), StandardCharsets.UTF_8));
		verify(harness.chatMessageManager, never()).queue(any(QueuedMessage.class));
		verifyNoInteractions(harness.notifier);
	}

	@Test
	public void partialSaveDoesNotPostCompletionSummary()
	{
		harness.config.taskCompleteNotification = true;
		harness.stop();

		assertTrue(harness.onlyRecord(ACCOUNT).isPartial());
		verify(harness.chatMessageManager, never()).queue(any(QueuedMessage.class));
		verifyNoInteractions(harness.notifier);
	}

	@Test
	public void lootWithinTheGraceWindowCountsAndLaterLootDoesNot()
	{
		completeOnNextTick();
		harness.lootOnNextTick(ItemID.BONES, 2);
		harness.tick();
		assertTrue("one grace tick left", harness.tracker().hasPendingCompletion());
		assertTrue(harness.records(ACCOUNT).isEmpty());

		// The last grace tick: LootManager's loot arrives before the ticker releases the session.
		harness.lootOnNextTick(ItemID.COINS, 500);
		harness.tick();
		assertFalse(harness.tracker().hasPendingCompletion());
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals(Long.valueOf(3), record.getLoot().get(ItemID.BONES));
		assertEquals(Long.valueOf(500), record.getLoot().get(ItemID.COINS));

		harness.lootOnNextTick(ItemID.ABYSSAL_WHIP, 1);
		harness.ticks(3);
		record = harness.onlyRecord(ACCOUNT);
		assertNull("loot after the window is not added", record.getLoot().get(ItemID.ABYSSAL_WHIP));
		assertNull(harness.activeSession(ACCOUNT));
	}

	@Test
	public void lootPostedBetweenTicksDuringTheWindowCounts()
	{
		completeOnNextTick();
		harness.postLoot(ItemID.ABYSSAL_WHIP, 1);
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
		assertEquals(Long.valueOf(1), harness.onlyRecord(ACCOUNT).getLoot().get(ItemID.ABYSSAL_WHIP));
	}

	@Test
	public void logoutDuringTheWindowSavesTheCompletedSessionOnce()
	{
		completeOnNextTick();
		harness.logout();
		assertFalse(harness.tracker().hasPendingCompletion());
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertNull("a completed session is never persisted as active", harness.activeSession(ACCOUNT));

		harness.setTask(TASK + 1, "Greater demons", 20, 20);
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(5);
		harness.shutDown();
		assertEquals(1, harness.records(ACCOUNT).size());
		assertNull(harness.activeSession(ACCOUNT));
	}

	@Test
	public void worldHopDuringTheWindowSavesTheCompletedSession()
	{
		completeOnNextTick();
		harness.hop();
		assertFalse(harness.tracker().hasPendingCompletion());
		assertTrue(harness.onlyRecord(ACCOUNT).isCompleted());
		harness.ticks(PluginHarness.SETTLE_TICKS + SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
		assertEquals(1, harness.records(ACCOUNT).size());
	}

	@Test
	public void pluginStopDuringTheWindowSavesTheCompletedSession()
	{
		completeOnNextTick();
		harness.lootOnNextTick(ItemID.ABYSSAL_WHIP, 1);
		harness.tick();
		harness.shutDown();

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertFalse(record.isPartial());
		assertEquals(Long.valueOf(1), record.getLoot().get(ItemID.ABYSSAL_WHIP));
		assertNull(harness.activeSession(ACCOUNT));
	}

	@Test
	public void clientExitDuringTheWindowSavesTheCompletedSessionAndWaitsForIt()
	{
		completeOnNextTick();
		List<Future<?>> waitedFor = harness.clientShutdown();

		assertEquals("the held completion's save", 1, waitedFor.size());
		for (Future<?> future : waitedFor)
		{
			assertTrue(future.isDone());
			assertFalse(future.isCancelled());
		}
		assertTrue(harness.onlyRecord(ACCOUNT).isCompleted());
		assertFalse(harness.tracker().hasPendingCompletion());
	}

	@Test
	public void stopDuringTheWindowKeepsTheCompletedRecordAndNewSessionApart()
	{
		completeOnNextTick();
		// A new task, and a new session, while the old one is still held.
		harness.setTask(TASK + 1, "Greater demons", 20, 20);
		harness.tick();
		assertTrue(harness.play());
		harness.stop();

		List<LedgerRecord> records = harness.records(ACCOUNT);
		assertEquals(2, records.size());
		LedgerRecord completed = records.stream().filter(LedgerRecord::isCompleted).findFirst().orElse(null);
		LedgerRecord partial = records.stream().filter(LedgerRecord::isPartial).findFirst().orElse(null);
		assertEquals("Abyssal demons", completed.getMonster());
		assertEquals("Greater demons", partial.getMonster());
		assertFalse(harness.tracker().hasPendingCompletion());
	}
}
