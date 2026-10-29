package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.ChatMessageType;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Keys, clue scrolls and notable drops as wired in the plugin ({@link PluginHarness}): counted from loot received while
 * the session runs, notable stacks valued at the "Notable drop value" and "Loot price" settings, pet messages, the live
 * panel, the saved record and its history tooltip, and the active session file. {@link KeysAndCluesTest},
 * {@link NotableDropsTest} and {@link PetMessageTest} cover the rules.
 */
public class HarnessTaskLootTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int TASK = 42;
	private static final int HARD_CLUE = ClueScrollIds.HARD[0];
	private static final int ELITE_CLUE = ClueScrollIds.ELITE[0];
	private static final String PET_TEXT = "<col=ef1020>You have a funny feeling like you're being followed.</col>";

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.startUp();
		harness.setTask(TASK, "Abyssal demons", 50, 50);
		harness.setVarbit(VarbitID.SLAYER_TASKS_COMPLETED, 10);
		harness.defineItem(ItemID.ABYSSAL_WHIP, "Abyssal whip", 1_500_000, 72_000);
		harness.defineItem(ItemID.RUNE_PLATEBODY, "Rune platebody", 100_000, 39_000);
		harness.defineItem(ItemID.RUNE_FULL_HELM, "Rune full helm", 99_999, 21_120);
		harness.defineItem(ItemID.KONAR_KEY, "Brimstone key", 0, 0);
	}

	private void loot(int... idQuantityPairs)
	{
		for (int i = 0; i < idQuantityPairs.length; i += 2)
		{
			harness.lootOnNextTick(idQuantityPairs[i], idQuantityPairs[i + 1]);
		}
		harness.tick();
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

	/**
	 * The history rows. The brimstone key has no GE price, so the history waits for it until the wait runs out
	 *; the wait is run out first.
	 */
	private List<String> historyText()
	{
		final List<?>[] rows = new List<?>[1];
		harness.onEdt(() ->
		{
			harness.historyModel().expireCurrentPricesWait();
			harness.historyModel().endExpiredCurrentPricesWait();
			rows[0] = harness.ui().getHistory().getValuesText();
		});
		@SuppressWarnings("unchecked")
		final List<String> result = (List<String>) rows[0];
		return result;
	}

	private List<NotableDrop> sessionDrops()
	{
		return harness.tracker().getNotableDrops();
	}

	@Test
	public void lootWhileRunningCountsAndLootWhilePausedDoesNot()
	{
		harness.loginAndSettle(ACCOUNT);
		assertNull("nothing to show yet", liveLines());
		assertTrue(harness.play());
		assertNull(liveLines());

		loot(ItemID.ABYSSAL_WHIP, 1, ItemID.KONAR_KEY, 2, HARD_CLUE, 1);
		assertEquals(Arrays.asList("Keys: 2 brimstone", "Clues: 1 hard", "Notable drops:", "  Abyssal whip: 1.5M"),
			liveLines());
		assertEquals("<html>Notable drops:<br>&nbsp;&nbsp;Abyssal whip: 1,500,000 gp</html>", liveTooltip());

		harness.pause();
		loot(ItemID.ABYSSAL_WHIP, 1, ItemID.KONAR_KEY, 1, ELITE_CLUE, 1);
		assertEquals("paused: nothing counted", Arrays.asList("Keys: 2 brimstone", "Clues: 1 hard",
			"Notable drops:", "  Abyssal whip: 1.5M"), liveLines());
		assertEquals(1, sessionDrops().size());

		harness.stop();
		assertNull("hidden once the session ends", liveLines());
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(Long.valueOf(2), record.getLoot().get(ItemID.KONAR_KEY));
		assertEquals(1, record.getNotableDrops().size());
		NotableDrop whip = record.getNotableDrops().get(0);
		assertEquals(Integer.valueOf(ItemID.ABYSSAL_WHIP), whip.getItemId());
		assertEquals("Abyssal whip", whip.getName());
		assertEquals(1, whip.getQuantity());
		assertEquals(Long.valueOf(1_500_000), whip.getGeValue());
		assertEquals(Long.valueOf(72_000), whip.getHaValue());
		assertFalse(whip.isPet());

		String row = historyText().get(0);
		assertTrue(row, row.contains("<br>Keys: 2 brimstone<br>Clues: 1 hard<br>Notable drops:"
			+ "<br>&nbsp;&nbsp;Abyssal whip: 1,500,000 gp"));
	}

	/**
	 * Notable drops are priced through ItemManager only while loot is recorded: never for loot while idle, but still
	 * while running and for the final kill's loot in the held-completion grace window.
	 */
	@Test
	public void notableDropsArePricedOnlyWhileLootIsRecorded()
	{
		harness.setTask(TASK, "Abyssal demons", 2, 50);
		harness.loginAndSettle(ACCOUNT);
		final int idleLookups = harness.itemPriceLookups(ItemID.ABYSSAL_WHIP);
		loot(ItemID.ABYSSAL_WHIP, 1);
		assertEquals("no price lookup for loot while idle", idleLookups,
			harness.itemPriceLookups(ItemID.ABYSSAL_WHIP));

		assertTrue(harness.play());
		loot(ItemID.ABYSSAL_WHIP, 1);
		assertTrue("priced while running", harness.itemPriceLookups(ItemID.ABYSSAL_WHIP) > idleLookups);
		assertEquals(1, sessionDrops().size());

		harness.setRemaining(1);
		harness.tick();
		harness.completeTask();
		harness.tick();
		assertTrue(harness.tracker().hasPendingCompletion());
		final int heldLookups = harness.itemPriceLookups(ItemID.ABYSSAL_WHIP);
		loot(ItemID.ABYSSAL_WHIP, 1);
		assertTrue("priced in the grace window", harness.itemPriceLookups(ItemID.ABYSSAL_WHIP) > heldLookups);
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals("the running kill's and the final kill's whips", 2, record.getNotableDrops().size());
	}

	@Test
	public void thresholdBoundaryAndChangingTheSetting()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());

		loot(ItemID.RUNE_PLATEBODY, 1, ItemID.RUNE_FULL_HELM, 1);
		assertEquals("exactly the threshold is notable; 1 gp below is not", 1, sessionDrops().size());
		assertEquals(Integer.valueOf(ItemID.RUNE_PLATEBODY), sessionDrops().get(0).getItemId());

		harness.setNotableDropThreshold(200_000);
		loot(ItemID.RUNE_PLATEBODY, 1);
		assertEquals("below the new threshold", 1, sessionDrops().size());
		loot(ItemID.RUNE_PLATEBODY, 2);
		assertEquals("one stack of two is worth the threshold", 2, sessionDrops().size());
		assertEquals(Long.valueOf(200_000), sessionDrops().get(1).getGeValue());
		assertEquals("  Rune platebody x 2: 200K", liveLines().get(1));

		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		harness.setNotableDropThreshold(100_000);
		loot(ItemID.ABYSSAL_WHIP, 1);
		assertEquals("the whip alchs for 72K: not notable in high alch mode", 2, sessionDrops().size());
		loot(ItemID.RUNE_PLATEBODY, 3);
		assertEquals(3, sessionDrops().size());
		assertEquals("values follow the setting", Arrays.asList("Notable drops:", "  Rune platebody x 3: 117K",
			"  Rune platebody x 2: 78K", "  Rune platebody: 39K"), liveLines());
	}

	@Test
	public void petMessageWhileRunningIsANotableDrop()
	{
		harness.loginAndSettle(ACCOUNT);
		harness.chat(ChatMessageType.GAMEMESSAGE, PET_TEXT);
		assertTrue(harness.play());
		harness.pause();
		harness.chat(ChatMessageType.GAMEMESSAGE, PET_TEXT);
		assertTrue("not counted while paused or idle", sessionDrops().isEmpty());

		assertTrue(harness.play());
		harness.chat(ChatMessageType.PUBLICCHAT, PET_TEXT);
		assertTrue("not a game message", sessionDrops().isEmpty());
		harness.chat(ChatMessageType.GAMEMESSAGE, PET_TEXT);
		harness.chat(ChatMessageType.GAMEMESSAGE,
			"You have a funny feeling like you would have been followed...");
		assertEquals(Arrays.asList("Notable drops:", "  Pet (already owned)", "  Pet"), liveLines());

		harness.stop();
		List<NotableDrop> drops = harness.onlyRecord(ACCOUNT).getNotableDrops();
		assertEquals(2, drops.size());
		assertEquals(NotableDrop.Pet.RECEIVED, drops.get(0).getPet());
		assertEquals(NotableDrop.Pet.DUPLICATE, drops.get(1).getPet());
	}

	@Test
	public void finalKillDropsDuringTheGraceWindowGoToTheCompletedTask()
	{
		harness.setTask(TASK, "Abyssal demons", 1, 50);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		harness.completeTask();
		harness.tick();
		assertTrue(harness.tracker().hasPendingCompletion());
		harness.chat(ChatMessageType.GAMEMESSAGE, PET_TEXT);
		loot(ItemID.ABYSSAL_WHIP, 1, ItemID.KONAR_KEY, 1);
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals(2, record.getNotableDrops().size());
		assertTrue(record.getNotableDrops().get(0).isPet());
		assertEquals(Integer.valueOf(ItemID.ABYSSAL_WHIP), record.getNotableDrops().get(1).getItemId());
		assertEquals(Long.valueOf(1), record.getLoot().get(ItemID.KONAR_KEY));
	}

	@Test
	public void activeSessionKeepsItsNotableDropsAcrossARestart()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		loot(ItemID.ABYSSAL_WHIP, 1, HARD_CLUE, 1);
		harness.logout();
		harness.shutDown();
		SessionSnapshot snapshot = harness.activeSession(ACCOUNT);
		assertEquals(1, snapshot.getNotableDrops().size());
		assertEquals(Long.valueOf(1_500_000), snapshot.getNotableDrops().get(0).getGeValue());

		// Today's price has dropped; the drop keeps the value it had.
		harness.setGePrice(ItemID.ABYSSAL_WHIP, 1_000_000);
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		assertEquals(SessionTracker.State.PAUSED, harness.sessionState());
		assertEquals(Arrays.asList("Clues: 1 hard", "Notable drops:", "  Abyssal whip: 1.5M"), liveLines());

		assertTrue(harness.play());
		loot(ItemID.ABYSSAL_WHIP, 1);
		harness.stop();
		List<NotableDrop> drops = harness.onlyRecord(ACCOUNT).getNotableDrops();
		assertEquals(2, drops.size());
		assertEquals(Long.valueOf(1_500_000), drops.get(0).getGeValue());
		assertEquals(Long.valueOf(1_000_000), drops.get(1).getGeValue());
	}

	@Test
	public void olderRecordsStillLoadAndRecordsWithLootGainKeyCounts() throws IOException
	{
		assertTrue(harness.ledgerDirectory().isDirectory() || harness.ledgerDirectory().mkdirs());
		String json = "{\"schemaVersion\":2,\"records\":[{\"schemaVersion\":2,\"id\":\"old\",\"monster\":\"Kraken\","
			+ "\"creatureId\":42,\"initialAmount\":3,\"kills\":3,\"activeMillis\":60000,\"startedAt\":1000,"
			+ "\"endedAt\":61000,\"completed\":true,\"partial\":false},"
			+ "{\"schemaVersion\":2,\"id\":\"withLoot\",\"monster\":\"Kraken\",\"creatureId\":42,\"initialAmount\":3,"
			+ "\"kills\":3,\"activeMillis\":60000,\"startedAt\":2000,\"endedAt\":62000,\"completed\":true,"
			+ "\"partial\":false,\"loot\":{\"" + ItemID.KONAR_KEY + "\":1,\"995\":500}}]}";
		Files.write(harness.ledgerDirectory().toPath().resolve(ACCOUNT + ".json"), json.getBytes(StandardCharsets.UTF_8));

		harness.loginAndSettle(ACCOUNT);
		List<LedgerRecord> records = harness.records(ACCOUNT);
		assertEquals(2, records.size());
		for (LedgerRecord r : records)
		{
			assertNull(r.getNotableDrops());
		}
		List<String> rows = historyText();
		assertEquals(2, rows.size());
		assertTrue("derived from the saved loot", rows.get(0).endsWith("<br>Deaths: -<br>Keys: 1 brimstone</html>"));
		assertTrue("unchanged", rows.get(1).endsWith("<br>Deaths: -</html>"));
	}
}
