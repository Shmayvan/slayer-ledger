package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * The new-assignment chat preview as wired in the plugin ({@link PluginHarness}): one line for a task assigned
 * while the plugin watches, from the same statistics as the History estimate; none for a task already there at a
 * login, hop or restore, none without history, none with the setting off. {@link AssignmentPreviewMessageTest} covers
 * the line.
 */
public class HarnessAssignmentPreviewTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final long MINUTE = 60_000L;
	private static final int DEMONS = 42;
	private static final int GARGOYLES = 46;
	private static final int CATACOMBS = 6_557;
	private static final int UNNAMED = 13_623;
	/** The line for 20 Abyssal demons from the three saved tasks anywhere. */
	private static final String LINE = "Abyssal demons (20): ~0:20:00 · +180K (GE) · ~2 bank trips (from 3 tasks)";

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp() throws IOException
	{
		save(task(null), task(null), task(null));
		SavedItems.define(harness);
		harness.startUp();
		harness.setInventory();
		harness.setPlayerRegion(UNNAMED);
	}

	/**
	 * A completed Abyssal demons task: 10 kills in 10 minutes, loot 100K GE, supplies 10K, 1 bank trip; with its items
	 * ({@link SavedItems}).
	 */
	private static LedgerRecord task(String location)
	{
		LedgerRecord record = new LedgerRecord();
		record.setId("task-" + System.nanoTime());
		record.setCreatureId(DEMONS);
		record.setMonster("Abyssal demons");
		record.setKills(10);
		record.setActiveMillis(10 * MINUTE);
		record.setCompleted(true);
		record.setLootGeValue(100_000L);
		record.setLootHaValue(200_000L);
		record.setSuppliesGeValue(10_000L);
		record.setBankTrips(1);
		record.setLocation(location);
		return SavedItems.withItems(record);
	}

	/** Saves the records to the account's ledger file, read when the account logs in. */
	private void save(LedgerRecord... records) throws IOException
	{
		LedgerStore store = new LedgerStore(Filepath.Unchecked.getRooted(harness.ledgerDirectory().toPath()),
			harness.gson);
		for (LedgerRecord record : records)
		{
			store.append(ACCOUNT, record);
		}
	}

	/** Logs in with no task, and lets the plugin's post-login reading see that there is none. */
	private void loginWithoutTask()
	{
		harness.setRemaining(0);
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(AssignmentWatch.SYNC_MAX_TICKS);
	}

	/** A master assigns a task, seen on the next tick. */
	private void assign(int taskId, String name, int amount)
	{
		harness.setTask(taskId, name, amount, amount);
		harness.tick();
	}

	private List<String> lines()
	{
		ArgumentCaptor<QueuedMessage> messages = ArgumentCaptor.forClass(QueuedMessage.class);
		verify(harness.chatMessageManager, atLeast(0)).queue(messages.capture());
		for (QueuedMessage message : messages.getAllValues())
		{
			assertEquals(ChatMessageType.CONSOLE, message.getType());
			assertEquals("Slayer Ledger", message.getName());
		}
		return messages.getAllValues().stream().map(QueuedMessage::getRuneLiteFormattedMessage)
			.collect(Collectors.toList());
	}

	private void assertNoLine()
	{
		verify(harness.chatMessageManager, never()).queue(any(QueuedMessage.class));
	}

	@Test
	public void aNewAssignmentWithHistoryPostsOneLine()
	{
		loginWithoutTask();
		assign(DEMONS, "Abyssal demons", 20);

		assertEquals(Collections.singletonList(LINE), lines());
		// Seen again on every tick, and through a kill: still once. The idle kill also posts the not-tracking line.
		harness.ticks(5);
		harness.setRemaining(19);
		harness.ticks(2);
		assertEquals(Arrays.asList(LINE, "Kill not counted: Slayer Ledger isn't tracking. Press Play in the sidebar."),
			lines());
	}

	@Test
	public void aTaskAfterACompletedOneIsNewToo()
	{
		harness.setTask(GARGOYLES, "Gargoyles", 5, 5);
		harness.loginAndSettle(ACCOUNT);
		harness.completeTask();
		harness.ticks(AssignmentWatch.END_WINDOW_TICKS + 1);
		assign(DEMONS, "Abyssal demons", 20);

		assertEquals(Collections.singletonList(LINE), lines());
	}

	@Test
	public void noHistoryPostsNothing()
	{
		loginWithoutTask();
		assign(GARGOYLES, "Gargoyles", 20);
		harness.ticks(3);

		assertNoLine();
		final AtomicBoolean pending = new AtomicBoolean(true);
		harness.onEdt(() -> pending.set(harness.historyModel().isAssignmentPreviewPending()));
		assertFalse("dropped, not waiting", pending.get());
	}

	@Test
	public void aTaskAlreadyAssignedAtLoginPostsNothing()
	{
		harness.setTask(DEMONS, "Abyssal demons", 20, 20);
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(5);

		assertNoLine();
	}

	@Test
	public void aRelogOrHopWithTheTaskStillAssignedPostsNothingMore()
	{
		loginWithoutTask();
		assign(DEMONS, "Abyssal demons", 20);
		verify(harness.chatMessageManager, times(1)).queue(any(QueuedMessage.class));

		harness.hop();
		harness.ticks(PluginHarness.SETTLE_TICKS + 3);
		harness.logout();
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(3);

		verify(harness.chatMessageManager, times(1)).queue(any(QueuedMessage.class));
	}

	@Test
	public void aRestoredSessionPostsNothing()
	{
		harness.setTask(DEMONS, "Abyssal demons", 20, 20);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		harness.setRemaining(19);
		harness.tick();
		harness.logout();
		harness.shutDown();

		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(5);

		assertEquals("restored", SessionTracker.State.PAUSED, harness.sessionState());
		assertNoLine();
	}

	@Test
	public void aPluginStartWhileLoggedInPostsNothing()
	{
		harness.shutDown();
		harness.setTask(DEMONS, "Abyssal demons", 20, 20);
		harness.setAccountHash(ACCOUNT);
		harness.setGameStateSilently(GameState.LOGGED_IN);
		harness.startUp();
		harness.ticks(5);

		assertNoLine();
	}

	@Test
	public void theSettingOffPostsNothing()
	{
		harness.config.assignmentPreview = false;
		loginWithoutTask();
		assign(DEMONS, "Abyssal demons", 20);
		harness.ticks(3);

		assertNoLine();
	}

	@Test
	public void aNewAssignmentBeforeTheHistoryLoadedIsPostedOnceItHas()
	{
		loginWithoutTask();
		// As while the ledger is still being read.
		harness.onEdt(() -> harness.historyModel().clearRecords(ACCOUNT, SlayerLedgerPanel.LOADING_MESSAGE));
		assign(DEMONS, "Abyssal demons", 20);
		harness.ticks(2);
		assertNoLine();
		final AtomicBoolean pending = new AtomicBoolean();
		harness.onEdt(() -> pending.set(harness.historyModel().isAssignmentPreviewPending()));
		assertTrue("waits for the records", pending.get());

		final List<LedgerRecord> records = harness.records(ACCOUNT);
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, records));
		harness.drain();
		// And still once when the records are shown again.
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, records));
		harness.ticks(2);
		assertEquals(Collections.singletonList(LINE), lines());
	}

	@Test
	public void theHistoryWhereThePlayerIsComesFirst() throws IOException
	{
		save(task("Catacombs of Kourend"));
		harness.setRsProfileConfig(SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX + CATACOMBS,
			"Catacombs of Kourend");
		harness.setPlayerRegion(CATACOMBS);
		loginWithoutTask();
		assign(DEMONS, "Abyssal demons", 20);

		assertEquals(Collections.singletonList(
			"Abyssal demons (20): ~0:20:00 · +180K (GE) · ~2 bank trips (from 1 task at Catacombs of Kourend)"), lines());
	}

	@Test
	public void aTypedLocationNameIsNotReadAsTags() throws IOException
	{
		save(task("<col=ff0000>Cave"));
		harness.setRsProfileConfig(SlayerLedgerConfig.GROUP, SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX + CATACOMBS,
			"<col=ff0000>Cave");
		harness.setPlayerRegion(CATACOMBS);
		loginWithoutTask();
		assign(DEMONS, "Abyssal demons", 20);

		assertEquals(Collections.singletonList(
			"Abyssal demons (20): ~0:20:00 · +180K (GE) · ~2 bank trips (from 1 task at <lt>col=ff0000<gt>Cave)"),
			lines());
	}
}
