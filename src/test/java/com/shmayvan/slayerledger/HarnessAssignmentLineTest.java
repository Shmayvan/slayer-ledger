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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * The advisor's chat line on assignment (which replaced the old preview) as wired in the plugin
 * ({@link PluginHarness}): one line for a task assigned while the plugin watches, with the advisor's recommendation,
 * what to bring and the counterpart, in the player's chat colours; the no-history wording without completed tasks;
 * none for a task already there at a login, hop or restore, and none with the "Advisor chat line" setting off.
 * {@link TaskPlanTextTest} covers the words, {@link TaskPlannerTest} the plan.
 */
public class HarnessAssignmentLineTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final long MINUTE = 60_000L;
	private static final int DEMONS = 42;
	private static final int GARGOYLES = 46;
	private static final int DEATH_RUNE = ItemID.DEATHRUNE;
	/**
	 * The line for 20 Abyssal demons from the saved tasks: three barrage tasks in the Catacombs, a minute a kill with
	 * 20 death runes a kill, and three cannon tasks in the Smoke Dungeon at two minutes a kill, using nothing.
	 */
	private static final String LINE = "<colHIGHLIGHT>Abyssal demons x20:<colNORMAL> barrage, Catacombs, ~20 min;"
		+ " ~400 death (3 tasks). Cannon, Smoke Dungeon: ~40 min (3).";
	/** Numbers the records' ids: the store skips a duplicate id, so they must never repeat. */
	private static final AtomicInteger IDS = new AtomicInteger();

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp() throws IOException
	{
		save(barrage(TestSpots.CATACOMBS_ID, 10), barrage(TestSpots.CATACOMBS_ID, 10),
			barrage(TestSpots.CATACOMBS_ID, 10), cannon(), cannon(), cannon());
		harness.defineItem(DEATH_RUNE, "Death rune", 200, 90);
		harness.startUp();
		harness.setInventory();
		harness.setPlayerRegion(TestSpots.UNLISTED);
	}

	/**
	 * A completed Abyssal demons task of 10 kills: {@code minutes} long at {@code spotId}, with the setup given, valued
	 * (no loot; its supplies at today's prices).
	 */
	private static LedgerRecord task(String spotId, boolean cannon, SetupStyle style, int minutes)
	{
		LedgerRecord record = new LedgerRecord();
		record.setId("task-" + IDS.incrementAndGet());
		record.setCreatureId(DEMONS);
		record.setMonster("Abyssal demons");
		record.setKills(10);
		record.setActiveMillis(minutes * MINUTE);
		record.setCompleted(true);
		record.setLootGeValue(0L);
		record.setLootHaValue(0L);
		record.setSuppliesGeValue(0L);
		record.setSpotId(spotId);
		SetupTags.detected(cannon, style, GearFocus.UNKNOWN, SetupOverrides.NONE).copyTo(record);
		return record;
	}

	/** A barrage task without a cannon at {@code spotId}: {@code minutes} long, 200 death runes. */
	private static LedgerRecord barrage(String spotId, int minutes)
	{
		LedgerRecord record = task(spotId, false, SetupStyle.BARRAGE, minutes);
		record.setSupplies(Collections.singletonMap(DEATH_RUNE, 200L));
		return record;
	}

	/** A melee cannon task in the Smoke Dungeon: 20 minutes. */
	private static LedgerRecord cannon()
	{
		return task(TestSpots.SMOKE_ID, true, SetupStyle.MELEE, 20);
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

	private boolean linePending()
	{
		final AtomicBoolean pending = new AtomicBoolean();
		harness.onEdt(() -> pending.set(harness.historyModel().isAssignmentLinePending()));
		return pending.get();
	}

	@Test
	public void aNewAssignmentWithHistoryPostsTheAdvisorsLineOnce()
	{
		loginWithoutTask();
		assign(DEMONS, "Abyssal demons", 20);

		assertEquals(Collections.singletonList(LINE), lines());
		assertTrue("within the budget", LINE.replace("<colHIGHLIGHT>", "").replace("<colNORMAL>", "").length()
			<= TaskPlanText.MAX_CHAT_LENGTH);
		// Seen again on every tick, and through the first kill (which starts the session): still once.
		harness.ticks(5);
		harness.setRemaining(19);
		harness.ticks(2);
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		assertEquals(Collections.singletonList(LINE), lines());
	}

	@Test
	public void theLineFollowsTheAdvisorMetric()
	{
		harness.setAdvisorMetric(AdvisorMetric.CHEAPEST);
		loginWithoutTask();
		assign(DEMONS, "Abyssal demons", 20);

		// The cannon tasks used no supplies, so they are the cheapest; the barrage tasks are the counterpart.
		assertEquals(Collections.singletonList("<colHIGHLIGHT>Abyssal demons x20:<colNORMAL> cannon, melee, Smoke"
			+ " Dungeon, ~40 min (3 tasks). No cannon, Catacombs: ~20 min (3)."), lines());
	}

	@Test
	public void aTaskAfterACompletedOneIsNewToo()
	{
		harness.setTask(GARGOYLES, "Gargoyles", 5, 5);
		harness.loginAndSettle(ACCOUNT);
		harness.completeTask();
		harness.ticks(AssignmentWatch.END_WINDOW_TICKS + 1);
		assign(DEMONS, "Abyssal demons", 20);

		// The completed task's own line comes first (its final kill was tracked), then the new task's, once.
		final List<String> lines = lines();
		assertEquals(LINE, lines.get(lines.size() - 1));
		assertEquals(1, Collections.frequency(lines, LINE));
	}

	@Test
	public void noHistoryPostsTheNoHistoryLine()
	{
		loginWithoutTask();
		assign(GARGOYLES, "Gargoyles", 20);
		harness.ticks(3);

		assertEquals(Collections.singletonList("<colHIGHLIGHT>Gargoyles x20:<colNORMAL> no completed tasks yet."),
			lines());
		assertFalse("posted, not waiting", linePending());
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
		assertTrue(harness.startNow());
		harness.setRemaining(19);
		harness.tick();
		harness.logout();
		harness.shutDown();

		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(5);

		assertEquals("restored", SessionTracker.State.LOGGED_OUT, harness.sessionState());
		// No advisor line: only the line saying the restored session waits for the next kill.
		assertEquals(Collections.singletonList(SlayerLedgerPlugin.WAITING_LINE), lines());
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
		harness.config.advisorChatLine = false;
		loginWithoutTask();
		assign(DEMONS, "Abyssal demons", 20);
		harness.ticks(3);

		assertNoLine();
	}

	@Test
	public void theSettingTurnedOffWhileTheLineWaitsPostsNothing()
	{
		loginWithoutTask();
		// As while the ledger is still being read.
		harness.onEdt(() -> harness.historyModel().clearRecords(ACCOUNT, SlayerLedgerPanel.LOADING_MESSAGE));
		assign(DEMONS, "Abyssal demons", 20);
		assertTrue(linePending());

		harness.config.advisorChatLine = false;
		final List<LedgerRecord> records = harness.records(ACCOUNT);
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, records));
		harness.drain();
		assertFalse(linePending());
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
		assertTrue("waits for the records", linePending());

		final List<LedgerRecord> records = harness.records(ACCOUNT);
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, records));
		harness.drain();
		// And still once when the records are shown again.
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, records));
		harness.ticks(2);
		assertEquals(Collections.singletonList(LINE), lines());
	}

	@Test
	public void aLogoutWhileTheLineWaitsPostsNothingAfterTheNextLogin()
	{
		loginWithoutTask();
		// As while the ledger is still being read.
		harness.onEdt(() -> harness.historyModel().clearRecords(ACCOUNT, SlayerLedgerPanel.LOADING_MESSAGE));
		assign(DEMONS, "Abyssal demons", 20);
		assertTrue("waits for the records", linePending());

		// The task is still there at the next login: a task you had at a login gets no line.
		harness.logout();
		assertFalse("dropped at the logout", linePending());
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(3);
		assertNoLine();
	}

	/**
	 * Runs the work a tick queued, the plan worked out off the EDT included, until it settles, but with the plugin's
	 * history model unset while the client thread runs, so the names the chat line asks for never come.
	 */
	private void settleLosingNames()
	{
		final LedgerHistoryModel model = harness.historyModel();
		for (int i = 0; i < 5; i++)
		{
			harness.flushEdt();
			harness.runExecutor();
			harness.flushEdt();
			harness.plugin.setHistoryModel(null);
			try
			{
				harness.runClientThread();
			}
			finally
			{
				harness.plugin.setHistoryModel(model);
			}
		}
		harness.flushEdt();
	}

	@Test
	public void aNameThatNeverArrivesIsShownByItsIdAfterFiveSeconds()
	{
		loginWithoutTask();
		final AtomicLong now = new AtomicLong(System.currentTimeMillis());
		harness.setTrackerClock(now::get);
		harness.setTask(DEMONS, "Abyssal demons", 20, 20);
		harness.tickWithoutDrain();
		settleLosingNames();
		assertTrue("waits for the death rune's name", linePending());
		harness.ticks(3);
		assertNoLine();

		// Five seconds on, with nothing else changing: the next settled tick has the line posted as it is.
		now.addAndGet(LedgerHistoryModel.ASSIGNMENT_LINE_NAMES_WAIT_MILLIS);
		harness.tick();
		assertEquals(Collections.singletonList("<colHIGHLIGHT>Abyssal demons x20:<colNORMAL> barrage, Catacombs,"
			+ " ~20 min; ~400 item " + DEATH_RUNE + " (3 tasks). Cannon, Smoke Dungeon: ~40 min (3)."), lines());
		assertFalse(linePending());
		harness.ticks(3);
		assertEquals("once", 1, lines().size());
	}

	@Test
	public void aRegionsTypedNameIsNotReadAsTags() throws IOException
	{
		// Three faster barrage tasks in a region the spot table doesn't list, which the player named.
		final String region = SlayerSpots.regionKey(TestSpots.UNLISTED);
		save(barrage(region, 8), barrage(region, 8), barrage(region, 8));
		harness.setRsProfileConfig(SlayerLedgerConfig.GROUP,
			SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX + TestSpots.UNLISTED, "<b>Cave");
		loginWithoutTask();
		assign(DEMONS, "Abyssal demons", 20);

		assertEquals(Collections.singletonList("<colHIGHLIGHT>Abyssal demons x20:<colNORMAL> barrage, <lt>b<gt>Cave,"
			+ " ~16 min; ~400 death (3 tasks). Cannon, Smoke Dungeon: ~40 min (3)."), lines());
	}
}
