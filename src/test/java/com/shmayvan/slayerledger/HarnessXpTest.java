package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * XP gained per task as wired in the plugin ({@link PluginHarness}): read once per settled tick before the task
 * is observed, counted only while RUNNING, never from a login's stats, and kept through logout and a restart.
 * {@link SessionTrackerXpTest} covers the tracker's rules.
 */
public class HarnessXpTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int TASK = 42;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.setSkillXp(Skill.SLAYER, 1_000_000);
		harness.setSkillXp(Skill.HITPOINTS, 2_000_000);
		harness.setSkillXp(Skill.MAGIC, 3_000_000);
		harness.startUp();
		harness.setTask(TASK, "Abyssal demons", 100, 100);
		harness.loginAndSettle(ACCOUNT);
	}

	private static Map<String, Long> xp(Object... skillAndXp)
	{
		Map<String, Long> map = new TreeMap<>();
		for (int i = 0; i < skillAndXp.length; i += 2)
		{
			map.put((String) skillAndXp[i], ((Number) skillAndXp[i + 1]).longValue());
		}
		return map;
	}

	@Test
	public void xpIsCountedOnlyWhileRunning()
	{
		harness.gainXp(Skill.SLAYER, 500);
		harness.tick();
		assertTrue(harness.startNow());
		harness.gainXp(Skill.SLAYER, 120);
		harness.gainXp(Skill.MAGIC, 300);
		harness.tick();
		harness.pause();
		harness.gainXp(Skill.SLAYER, 9_999);
		harness.ticks(2);
		assertTrue("resume", harness.startNow());
		harness.gainXp(Skill.HITPOINTS, 40);
		harness.tick();
		harness.endTask();

		assertEquals(xp("SLAYER", 120, "MAGIC", 300, "HITPOINTS", 40), harness.onlyRecord(ACCOUNT).getXpGained());
	}

	@Test
	public void aReconnectsStatBurstIsNotCounted()
	{
		assertTrue(harness.startNow());
		harness.gainXp(Skill.SLAYER, 100);
		harness.tick();
		// A lost connection stops the session like a logout. The stats read 0 until the login re-sends them, then jump.
		harness.setGameState(GameState.CONNECTION_LOST);
		harness.clearSkillXp();
		harness.setGameState(GameState.LOGGED_IN);
		harness.tick();
		harness.setSkillXp(Skill.SLAYER, 1_000_200);
		harness.setSkillXp(Skill.HITPOINTS, 2_000_000);
		harness.setSkillXp(Skill.MAGIC, 3_000_000);
		harness.ticks(PluginHarness.SETTLE_TICKS);
		assertEquals(SessionTracker.State.LOGGED_OUT, harness.sessionState());
		assertTrue("restarted after the reconnect", harness.startNow());
		harness.gainXp(Skill.SLAYER, 7);
		harness.tick();
		harness.endTask();

		assertEquals(xp("SLAYER", 107), harness.onlyRecord(ACCOUNT).getXpGained());
	}

	@Test
	public void aHopIsNeverAGain()
	{
		assertTrue(harness.startNow());
		harness.gainXp(Skill.SLAYER, 10);
		harness.tick();
		harness.hop();
		harness.gainXp(Skill.SLAYER, 50_000);
		harness.ticks(PluginHarness.SETTLE_TICKS);
		assertEquals("a hop is not a stop", SessionTracker.State.RUNNING, harness.sessionState());
		harness.gainXp(Skill.SLAYER, 5);
		harness.tick();
		harness.endTask();

		assertEquals(xp("SLAYER", 15), harness.onlyRecord(ACCOUNT).getXpGained());
	}

	@Test
	public void anotherAccountsStatsAreNotCounted()
	{
		assertTrue(harness.startNow());
		harness.gainXp(Skill.SLAYER, 10);
		harness.tick();
		harness.logout();
		// The other account has more XP in every skill: a naive diff would count all of it.
		harness.setSkillXp(Skill.SLAYER, 5_000_000);
		harness.setSkillXp(Skill.HITPOINTS, 9_000_000);
		harness.setSkillXp(Skill.MAGIC, 9_000_000);
		harness.loginAndSettle(ACCOUNT + 1);
		assertTrue(harness.startNow());
		harness.gainXp(Skill.MAGIC, 3);
		harness.tick();
		harness.endTask();

		assertEquals(xp("MAGIC", 3), harness.onlyRecord(ACCOUNT + 1).getXpGained());
		SessionSnapshot first = harness.activeSession(ACCOUNT);
		assertNotNull("the first account's session is persisted", first);
		assertEquals(xp("SLAYER", 10), first.getXpGained());
	}

	@Test
	public void theCompletionTickCountsAndTheGraceWindowDoesNot()
	{
		harness.setRemaining(1);
		harness.tick();
		assertEquals("the drop was a kill: it started the session", SessionTracker.State.RUNNING, harness.sessionState());
		harness.tick();
		harness.completeTask();
		harness.gainXp(Skill.SLAYER, 300);
		harness.tick();
		assertTrue("held for late loot", harness.tracker().hasPendingCompletion());
		harness.gainXp(Skill.SLAYER, 999);
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals(xp("SLAYER", 300), record.getXpGained());
	}

	@Test
	public void xpSurvivesLogoutAndARestart()
	{
		assertTrue(harness.startNow());
		harness.gainXp(Skill.SLAYER, 400);
		harness.tick();
		harness.logout();
		assertEquals(xp("SLAYER", 400), harness.activeSession(ACCOUNT).getXpGained());
		harness.shutDown();

		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		assertEquals("restored as logged out", SessionTracker.State.LOGGED_OUT, harness.sessionState());
		assertEquals(xp("SLAYER", 400), harness.tracker().getXpGained());
		assertTrue(harness.startNow());
		harness.gainXp(Skill.SLAYER, 100);
		harness.tick();
		harness.endTask();

		assertEquals(xp("SLAYER", 500), harness.onlyRecord(ACCOUNT).getXpGained());
		assertNull(harness.activeSession(ACCOUNT));
	}

	@Test
	public void panelShowsTheLiveXpAndTheSavedTasksXp()
	{
		assertTrue(harness.startNow());
		harness.gainXp(Skill.SLAYER, 1_500);
		harness.gainXp(Skill.HITPOINTS, 500);
		harness.tick();
		final String[] live = new String[3];
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			live[0] = harness.ui().getXpText();
			live[1] = String.join(" / ", harness.ui().getLiveXp().getLineTexts());
			live[2] = String.join(" / ", harness.ui().getLiveXp().getLineIcons());
		});
		assertEquals("the stats' XP", "2,000", live[0]);
		assertEquals("Slayer | 1,500 / Hitpoints | 500", live[1]);
		// Each line has its skill's small icon from SkillIconManager, asked for on the EDT.
		assertEquals("skill SLAYER 18x16 / skill HITPOINTS 18x16", live[2]);

		harness.endTask();
		final Object[] saved = new Object[2];
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			saved[0] = harness.ui().getXpText();
			// The row has no room for the XP since its tooltip has it.
			saved[1] = harness.ui().getHistory().getTooltips().get(0);
			harness.ui().getHistory().clickRow(0);
		});
		harness.drain();
		final Object[] details = new Object[2];
		harness.onEdt(() ->
		{
			details[0] = harness.ui().getHistory().getDetailsText(0);
			details[1] = harness.ui().getHistory().getDetailsXpIcons(0);
		});
		assertNull("hidden once the session ended", saved[0]);
		// Too little active time for a rate, so none.
		assertTrue(saved[1].toString(), saved[1].toString().contains("<br>XP: 2,000<br>"));
		String text = details[0].toString();
		assertTrue(text, text.contains("XP gained, Slayer | 1,500, Hitpoints | 500"));
		assertEquals(Arrays.asList("skill SLAYER 18x16", "skill HITPOINTS 18x16"), details[1]);
	}
}
