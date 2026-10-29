package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * {@link KillCountMessage}: the message texts are the ones core's ChatCommandsPluginTest (RuneLite 1.13.4, tag
 * {@code runelite-parent-1.13.4}) feeds core's KILLCOUNT_PATTERN.
 */
public class KillCountMessageTest
{
	private static KillCountMessage.Result parse(String message)
	{
		return KillCountMessage.parse(true, message);
	}

	private static void assertBossKill(String message, String boss, int kc)
	{
		KillCountMessage.Result result = parse(message);
		assertEquals(message, new KillCountMessage.Result(boss, kc, true), result);
	}

	private static void assertOtherCount(String message, String name, int kc)
	{
		KillCountMessage.Result result = parse(message);
		assertEquals(message, new KillCountMessage.Result(name, kc, false), result);
	}

	@Test
	public void bossKillCounts()
	{
		assertBossKill("Your Corporeal Beast kill count is: <col=ff0000>4</col>.", "Corporeal Beast", 4);
		assertBossKill("Your Kree'arra kill count is: <col=ff0000>4</col>.", "Kree'arra", 4);
		assertBossKill("Your TzKal-Zuk kill count is: <col=ff0000>2</col>.", "TzKal-Zuk", 2);
		assertBossKill("Your TzTok-Jad kill count is: <col=ff0000>2</col>.", "TzTok-Jad", 2);
		assertBossKill("Your Grotesque Guardians kill count is: <col=ff0000>179</col>.", "Grotesque Guardians", 179);
		assertBossKill("Your Tempoross kill count is: <col=ff0000>60</col>.", "Tempoross", 60);
	}

	@Test
	public void noFullStopAndThousandsSeparators()
	{
		assertBossKill("Your Nightmare kill count is: <col=ff0000>1130</col>", "Nightmare", 1130);
		assertBossKill("Your Nightmare kill count is: <col=ff0000>1,130</col>", "Nightmare", 1130);
	}

	@Test
	public void colouredBossNamesAndNoSpaceBeforeTheCount()
	{
		assertBossKill("Your <col=a53fff>Corrupted Hunllef (Echo)</col> kill count is: <col=ff3045>31</col>",
			"Corrupted Hunllef (Echo)", 31);
		assertBossKill("Your <col=6800bf>Kalphite Queen (Echo)</col> kill count is:<col=e00a19>1</col>",
			"Kalphite Queen (Echo)", 1);
	}

	@Test
	public void bareCountIsABossKill()
	{
		assertBossKill("Your Barrows chest count is: <col=ff0000>277</col>.", "Barrows chest", 277);
	}

	@Test
	public void otherCountsParseButAreNotBossKills()
	{
		assertOtherCount("Your completed Theatre of Blood count is: <col=ff0000>73</col>.", "Theatre of Blood", 73);
		assertOtherCount("Your completed Tombs of Amascut: Expert Mode count is: <col=ff0000>1</col>.",
			"Tombs of Amascut: Expert Mode", 1);
		assertOtherCount("Your completion count for TzHaar-Ket-Rak's First Challenge is: <col=ff0000>1</col>.",
			"TzHaar-Ket-Rak's First Challenge", 1);
		assertOtherCount("Your subdued Wintertodt count is: <col=ff0000>4</col>.", "Wintertodt", 4);
		assertOtherCount("Your herbiboar harvest count is: <col=ff0000>4091</col>.", "herbiboar", 4091);
		assertOtherCount("Your Gauntlet completion count is: <col=ff0000>123</col>.", "Gauntlet", 123);
		assertOtherCount("Your Prifddinas Agility Course lap count is: @mes_hl_red@2</col>.",
			"Prifddinas Agility Course", 2);
		assertOtherCount("Your Agility Arena Total Ticket count is: <col=ff0000>206</col>.", "Agility Arena", 206);
		assertFalse(parse("Your completed Chambers of Xeric count is: <col=ff0000>51</col>.").isBossKill());
	}

	@Test
	public void notKillCounts()
	{
		assertNull("wrong chat type", KillCountMessage.parse(false,
			"Your Kree'arra kill count is: <col=ff0000>4</col>."));
		assertNull(parse(null));
		assertNull("the count must be coloured, as in core", parse("Your Kree'arra kill count is: 4."));
		assertNull("no prefix or count word: core ignores it", parse("Your Kree'arra is: <col=ff0000>4</col>."));
		assertNull("too large", parse("Your Kree'arra kill count is: <col=ff0000>99,999,999,999</col>."));
		assertNull(parse("A superior foe has appeared..."));
		assertNull(parse("Fight duration: <col=ff0000>1:23</col>. Personal best: 1:01"));
	}

	@Test
	public void bossNameHasNoTagsLeft()
	{
		KillCountMessage.Result result = parse("Your <col=ff0000>Vorkath</col> kill count is: <col=ff0000>9</col>.");
		assertEquals("Vorkath", result.getBoss());
		assertTrue(result.isBossKill());
	}
}
