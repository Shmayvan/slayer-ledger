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
import net.runelite.api.ChatMessageType;
import net.runelite.api.gameval.VarbitID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Boss kill counts as wired in the plugin ({@link PluginHarness}): kill count messages counted while the session runs
 * (or a completed task waits for its last loot), for a boss task or a regular task named after the boss, the boss
 * task's name, the live panel, the saved record and its history tooltip, and the active session file.
 * {@link KillCountMessageTest} and {@link BossKillCountsTest} cover the rules.
 */
public class HarnessBossKillCountTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int VORKATH_BOSS_ID = 31;
	private static final int DKS_BOSS_ID = 5;
	private static final int JAD_TASK = 93;
	private static final int REGULAR_TASK = 42;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.startUp();
		harness.setVarbit(VarbitID.SLAYER_TASKS_COMPLETED, 10);
	}

	private static String kc(String boss, String count)
	{
		return "Your " + boss + " kill count is: <col=ff0000>" + count + "</col>.";
	}

	private void chat(String message)
	{
		harness.chat(ChatMessageType.GAMEMESSAGE, message);
	}

	private List<String> liveLines()
	{
		final List<?>[] lines = new List<?>[1];
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			lines[0] = harness.ui().getTaskLootLines();
		});
		@SuppressWarnings("unchecked")
		final List<String> result = (List<String>) lines[0];
		return result;
	}

	private String liveTooltip()
	{
		final String[] text = new String[1];
		harness.onEdt(() -> text[0] = harness.ui().getTaskLootTooltip());
		return text[0];
	}

	private List<String> historyText()
	{
		final List<?>[] rows = new List<?>[1];
		harness.onEdt(() -> rows[0] = harness.ui().getHistory().getValuesText());
		@SuppressWarnings("unchecked")
		final List<String> result = (List<String>) rows[0];
		return result;
	}

	@Test
	public void bossTaskCountsItsBossWhileRunning()
	{
		harness.setBossTask(VORKATH_BOSS_ID, "Vorkath", 3, 3);
		harness.loginAndSettle(ACCOUNT);
		chat(kc("Vorkath", "140"));
		assertTrue(harness.play());
		assertNull("nothing to show yet; idle messages don't count", liveLines());

		chat(kc("Vorkath", "141"));
		chat("Your <col=ff0000>Vorkath</col> kill count is: <col=ff0000>1,142</col>.");
		chat("Your Prifddinas Agility Course lap count is: @mes_hl_red@2</col>.");
		harness.chat(ChatMessageType.PUBLICCHAT, kc("Vorkath", "2000"));
		assertEquals(Collections.singletonList("Boss KC: 1,142 (+2)"), liveLines());
		assertEquals("<html>Boss KC: 1,142 (+2)</html>", liveTooltip());

		harness.stop();
		assertNull("hidden once the session ends", liveLines());
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals("the boss task is named after its boss", "Vorkath", record.getMonster());
		assertEquals(Collections.singletonList(new BossKillCount("Vorkath", 141, 1142, 2)), record.getBossKillCounts());
		String row = historyText().get(0);
		assertTrue(row, row.contains("<br>Type: Boss<br>Boss KC: 1,142 (+2)"));
	}

	@Test
	public void bossTaskWithSeveralBossNamesListsEach()
	{
		harness.setBossTask(DKS_BOSS_ID, "Dagannoth Kings", 5, 5);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		chat(kc("Dagannoth Rex", "50"));
		chat(kc("Dagannoth Prime", "20"));
		chat(kc("Dagannoth Rex", "51"));
		assertEquals(Arrays.asList("Dagannoth Rex KC: 51 (+2)", "Dagannoth Prime KC: 20 (+1)"), liveLines());

		harness.stop();
		assertEquals(2, harness.onlyRecord(ACCOUNT).getBossKillCounts().size());
		String row = historyText().get(0);
		assertTrue(row, row.contains("<br>Dagannoth Rex KC: 51 (+2)<br>Dagannoth Prime KC: 20 (+1)"));
	}

	@Test
	public void regularTaskCountsOnlyTheBossItIsNamedAfter()
	{
		harness.setTask(JAD_TASK, "TzTok-Jad", 1, 1);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		chat(kc("Vorkath", "141"));
		assertNull("another boss: ignored on a regular task", liveLines());
		chat(kc("TzTok-Jad", "2"));
		assertEquals(Collections.singletonList("Boss KC: 2 (+1)"), liveLines());

		harness.stop();
		assertEquals(Collections.singletonList(new BossKillCount("TzTok-Jad", 2, 2, 1)),
			harness.onlyRecord(ACCOUNT).getBossKillCounts());
	}

	@Test
	public void regularTaskWithoutABossNameIgnoresKillCounts()
	{
		harness.setTask(REGULAR_TASK, "Abyssal demons", 50, 50);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		chat(kc("Abyssal Sire", "12"));
		assertNull(liveLines());
		harness.stop();
		assertNull(harness.onlyRecord(ACCOUNT).getBossKillCounts());
		assertFalse(historyText().get(0).contains("KC"));
	}

	@Test
	public void messagesWhilePausedDoNotCount()
	{
		harness.setBossTask(VORKATH_BOSS_ID, "Vorkath", 5, 5);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		chat(kc("Vorkath", "141"));
		harness.pause();
		chat(kc("Vorkath", "142"));
		chat(kc("Vorkath", "143"));
		assertEquals("paused: nothing counted", Collections.singletonList("Boss KC: 141 (+1)"), liveLines());

		assertTrue(harness.play());
		chat(kc("Vorkath", "144"));
		assertEquals("the latest count includes the paused kills; the gain doesn't",
			Collections.singletonList("Boss KC: 144 (+2)"), liveLines());
	}

	@Test
	public void finalKillCountDuringTheGraceWindowGoesToTheCompletedTask()
	{
		harness.setTask(JAD_TASK, "TzTok-Jad", 1, 1);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		harness.completeTask();
		harness.tick();
		assertTrue(harness.tracker().hasPendingCompletion());
		// The player gets a new task and presses Play before the final KC message arrives.
		harness.setTask(JAD_TASK, "TzTok-Jad", 1, 1);
		assertTrue("a new session started", harness.play());
		chat(kc("TzTok-Jad", "3"));
		chat(kc("Vorkath", "141"));
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals(Collections.singletonList(new BossKillCount("TzTok-Jad", 3, 3, 1)), record.getBossKillCounts());
	}

	@Test
	public void activeSessionKeepsItsKillCountsAcrossARestart()
	{
		harness.setBossTask(VORKATH_BOSS_ID, "Vorkath", 5, 5);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		chat(kc("Vorkath", "141"));
		harness.logout();
		harness.shutDown();
		SessionSnapshot snapshot = harness.activeSession(ACCOUNT);
		assertEquals(Collections.singletonList(new BossKillCount("Vorkath", 141, 141, 1)),
			snapshot.getBossKillCounts());

		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		assertEquals(SessionTracker.State.PAUSED, harness.sessionState());
		assertEquals(Collections.singletonList("Boss KC: 141 (+1)"), liveLines());

		assertTrue(harness.play());
		chat(kc("Vorkath", "142"));
		harness.stop();
		assertEquals(Collections.singletonList(new BossKillCount("Vorkath", 141, 142, 2)),
			harness.onlyRecord(ACCOUNT).getBossKillCounts());
	}

	@Test
	public void olderRecordsStillLoadWithoutKillCounts() throws IOException
	{
		assertTrue(harness.ledgerDirectory().isDirectory() || harness.ledgerDirectory().mkdirs());
		String json = "{\"schemaVersion\":2,\"records\":[{\"schemaVersion\":2,\"id\":\"old\",\"monster\":\"Vorkath\","
			+ "\"creatureId\":-33,\"initialAmount\":3,\"kills\":3,\"activeMillis\":60000,\"startedAt\":1000,"
			+ "\"endedAt\":61000,\"completed\":true,\"partial\":false}]}";
		Files.write(harness.ledgerDirectory().toPath().resolve(ACCOUNT + ".json"), json.getBytes(StandardCharsets.UTF_8));

		harness.loginAndSettle(ACCOUNT);
		List<LedgerRecord> records = harness.records(ACCOUNT);
		assertEquals(1, records.size());
		assertNull(records.get(0).getBossKillCounts());
		String row = historyText().get(0);
		assertFalse(row, row.contains("KC"));
		assertTrue("unchanged: " + row, row.endsWith("<br>Deaths: -<br>Type: Boss</html>"));
	}
}
