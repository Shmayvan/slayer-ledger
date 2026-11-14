package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.game.ItemStack;
import net.runelite.client.events.ServerNpcLoot;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Araxxor's harvest after its task-completing kill as wired in the plugin ({@link PluginHarness}). Araxxor drops
 * its loot only when the player harvests its corpse, which can come after the task was saved (2 ticks after the count
 * reached 0). A harvest within 5 minutes of that final kill is added to the saved record, once: its loot, its values
 * and its notable drop, which the History row and its item details then show, with Ground Items' hidden items left out
 * of the value as for any loot; if that can't be written, the History says so. A harvest the game reports on the save's
 * tick goes with the save. Araxxor loot is not added after 5 minutes, after a logout, a hop or an account switch,
 * after a final kill that wasn't Araxxor's, or once the harvest came before the save (also before the completion was
 * held, or with every item ignored). {@link AraxxorHarvestTest} covers the rules.
 */
public class HarnessAraxxorHarvestTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int ARAXYTES = 105;
	private static final long MINUTE = 60_000L;
	private static final int COINS = ItemID.COINS;
	/** Ticks between two kills: more than a kill's pairing window, so each kill pairs only its own NPC's death. */
	private static final int FIGHT_TICKS = 5;
	/** {@link #historyShown} for the saved task before its harvest: the araxyte's 1,000 coins. */
	private static final List<String> BEFORE_HARVEST = Arrays.asList("+1,000",
		"Loot: 1,000 gp GE, 1,000 gp high alch", "Loot received | 1,000 [Coins | " + COINS + " x1000]");

	@Rule
	public final PluginHarness harness = new PluginHarness();

	private final AtomicLong now = new AtomicLong(1_000_000_000L);
	private int nextIndex = 1;

	@Before
	public void setUp()
	{
		harness.defineItem(COINS, "Coins", 1, 1);
		harness.defineItem(ItemID.RUNE_PLATEBODY, "Rune platebody", 38_000, 39_000);
		harness.defineItem(ItemID.BONES, "Bones", 100, 1);
		harness.setGroundItemsHidden("Bones");
		harness.startUp();
		harness.setTrackerClock(now::get);
		harness.setNotableDropThreshold(30_000);
		harness.setTask(ARAXYTES, "Araxytes", 2, 2);
		harness.loginAndSettle(ACCOUNT);
	}

	private <T> T onEdt(Supplier<T> read)
	{
		final AtomicReference<T> value = new AtomicReference<>();
		harness.onEdt(() -> value.set(read.get()));
		return value.get();
	}

	/** The player hits {@code name} and it dies; nothing else happens until the next tick. */
	private void hitAndKill(int id, String name)
	{
		final NPC npc = harness.npc(nextIndex++, id, name);
		harness.hitsplat(npc, true);
		harness.npcDies(npc);
	}

	/**
	 * The task: an araxyte kill that drops 1,000 coins, then the final kill of {@code finalId} named {@code finalName},
	 * which completes it; the task is saved 2 ticks later.
	 */
	private void completeTask(int finalId, String finalName)
	{
		playToTheFinalKill(finalId, finalName);
		harness.completeTask();
		harness.tick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS + 1);
		harness.drain();
		final LedgerRecord saved = harness.onlyRecord(ACCOUNT);
		assertTrue(saved.isCompleted());
		assertEquals(Collections.singletonMap(COINS, 1_000L), saved.getLoot());
	}

	/**
	 * The task up to its final kill: an araxyte kill that drops 1,000 coins, a fight later the death of {@code finalId}
	 * named {@code finalName}, and a minute; the task count is not read yet.
	 */
	private void playToTheFinalKill(int finalId, String finalName)
	{
		hitAndKill(NpcID.ARAXYTE_NORMAL, "Araxyte");
		now.addAndGet(MINUTE);
		harness.setRemaining(1);
		harness.tick();
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		harness.postLoot("Araxyte", COINS, 1_000);
		// A fight later, so the araxyte's death is not paired with the final kill.
		harness.ticks(FIGHT_TICKS);

		hitAndKill(finalId, finalName);
		now.addAndGet(MINUTE);
	}

	/** Araxxor's harvest arrives, as the game's loot tracker reports it: one loot event from Araxxor. */
	private void harvest(ItemStack... stacks)
	{
		final NPCComposition araxxor = mock(NPCComposition.class, withSettings().stubOnly());
		when(araxxor.getName()).thenReturn("Araxxor");
		harness.post(new ServerNpcLoot(araxxor, Arrays.asList(stacks)));
		harness.drain();
	}

	private void harvest()
	{
		harvest(new ItemStack(ItemID.RUNE_PLATEBODY, 1), new ItemStack(ItemID.BONES, 3), new ItemStack(COINS, 5_000));
	}

	/**
	 * As the player sees the History row: its profit, the loot line of its tooltip, and its item details' loot
	 * grid (opened), whose sub-header shows the loot value.
	 */
	private List<String> historyShown()
	{
		harness.drain();
		return onEdt(() ->
		{
			final PanelProbe.HistoryListProbe history = harness.ui().getHistory();
			if (history.getDetailsText(0) == null)
			{
				history.clickRow(0);
			}
			final PanelProbe.ItemGridProbe grid = history.getDetailsGrid(0, "Loot received");
			return Arrays.asList(history.getValuesText().get(0).split(" \\| ")[0],
				lootLine(history.getTooltips().get(0)), grid.getHeaderText() + " " + grid.getCellTexts());
		});
	}

	/** The {@code Loot: ...} line of a row tooltip, or the whole tooltip when it has none. */
	private static String lootLine(String tooltip)
	{
		for (String line : tooltip.split("<br>"))
		{
			if (line.startsWith("Loot: "))
			{
				return line;
			}
		}
		return tooltip;
	}

	@Test
	public void aHarvestAMinuteAfterTheFinalKillLandsInTheSavedRecordOnce()
	{
		completeTask(NpcID.ARAXXOR, "Araxxor");
		assertEquals(BEFORE_HARVEST, historyShown());

		now.addAndGet(MINUTE);
		harvest();
		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		final Map<Integer, Long> loot = new TreeMap<>();
		loot.put(COINS, 6_000L);
		loot.put(ItemID.RUNE_PLATEBODY, 1L);
		loot.put(ItemID.BONES, 3L);
		assertEquals(loot, record.getLoot());
		assertEquals(Long.valueOf(6_000 + 38_000 + 300), record.getLootGeValue());
		assertEquals(Long.valueOf(6_000 + 39_000 + 3), record.getLootHaValue());
		assertEquals(Long.valueOf(38_000), record.getUnitGePrices().get(ItemID.RUNE_PLATEBODY));
		assertEquals(Long.valueOf(39_000), record.getUnitHaPrices().get(ItemID.RUNE_PLATEBODY));
		assertNull(record.getLootUnpricedItems());
		assertEquals(1, record.getNotableDrops().size());
		assertEquals("Rune platebody", record.getNotableDrops().get(0).getName());
		assertEquals(Long.valueOf(38_000), record.getNotableDrops().get(0).getGeValue());
		assertTrue("still completed", record.isCompleted());

		// The History row and its details follow, at today's prices, with the hidden bones left out of the value.
		assertEquals(Arrays.asList("+44K", "Loot: 44,000 gp GE, 45,000 gp high alch", "Loot received (1 hidden) | 44K "
			+ "[Rune platebody | " + ItemID.RUNE_PLATEBODY + ", Coins | " + COINS + " x6000]"), historyShown());
		final String tooltip = onEdt(() -> harness.ui().getHistory().getTooltips().get(0));
		assertTrue(tooltip, tooltip.contains("Rune platebody"));

		// Once per harvest: more Araxxor loot is not this task's.
		now.addAndGet(MINUTE);
		harvest(new ItemStack(ItemID.RUNE_PLATEBODY, 1));
		assertEquals(loot, harness.onlyRecord(ACCOUNT).getLoot());
	}

	@Test
	public void aHarvestAfterFiveMinutesIsNotAdded()
	{
		completeTask(NpcID.ARAXXOR, "Araxxor");
		// The final kill's time is the completion tick's: 5 minutes and a moment after it.
		now.addAndGet(SessionTracker.ARAXXOR_LOOT_MILLIS + 1);
		harvest();
		assertEquals(Collections.singletonMap(COINS, 1_000L), harness.onlyRecord(ACCOUNT).getLoot());
		assertEquals(BEFORE_HARVEST, historyShown());
	}

	@Test
	public void aHarvestWithNoAraxxorFinalKillIsNotAdded()
	{
		// Araxxor died earlier in the task, not at its final kill.
		hitAndKill(NpcID.ARAXXOR, "Araxxor");
		now.addAndGet(MINUTE);
		harness.setRemaining(1);
		harness.tick();
		harness.ticks(FIGHT_TICKS);
		hitAndKill(NpcID.ARAXYTE_NORMAL, "Araxyte");
		now.addAndGet(MINUTE);
		harness.completeTask();
		harness.tick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS + 1);
		harness.drain();
		final LedgerRecord saved = harness.onlyRecord(ACCOUNT);
		assertNull(saved.getLoot());

		now.addAndGet(MINUTE);
		harvest();
		assertNull(harness.onlyRecord(ACCOUNT).getLoot());
	}

	@Test
	public void aLogoutEndsTheWindow()
	{
		completeTask(NpcID.ARAXXOR, "Araxxor");
		harness.logout();
		harness.loginAndSettle(ACCOUNT);
		now.addAndGet(MINUTE);
		harvest();
		assertEquals(Collections.singletonMap(COINS, 1_000L), harness.onlyRecord(ACCOUNT).getLoot());
	}

	@Test
	public void aHopEndsTheWindow()
	{
		completeTask(NpcID.ARAXXOR, "Araxxor");
		harness.hop();
		harness.ticks(PluginHarness.SETTLE_TICKS);
		now.addAndGet(MINUTE);
		harvest();
		assertEquals(Collections.singletonMap(COINS, 1_000L), harness.onlyRecord(ACCOUNT).getLoot());
	}

	@Test
	public void anAccountSwitchEndsTheWindow()
	{
		completeTask(NpcID.ARAXXOR, "Araxxor");
		// Another account seen on a tick, then this one again: the harvest comes for the task's own account.
		harness.setAccountHash(PluginHarness.OTHER_ACCOUNT);
		harness.tick();
		harness.setAccountHash(ACCOUNT);
		harness.ticks(PluginHarness.SETTLE_TICKS);
		now.addAndGet(MINUTE);
		harvest();
		assertEquals(Collections.singletonMap(COINS, 1_000L), harness.onlyRecord(ACCOUNT).getLoot());
	}

	@Test
	public void aFailedWriteShowsTheMessage() throws IOException
	{
		completeTask(NpcID.ARAXXOR, "Araxxor");
		// A newer version rewrote the ledger meanwhile: it is never overwritten, so the harvest can't be added.
		final String id = harness.onlyRecord(ACCOUNT).getId();
		final File ledger = new File(harness.ledgerDirectory(), ACCOUNT + ".json");
		final String newer = "{\"schemaVersion\":" + (LedgerStore.FILE_SCHEMA_VERSION + 1) + ",\"records\":[{\"id\":\""
			+ id + "\",\"monster\":\"Araxytes\",\"kills\":2,\"completed\":true}]}";
		Files.write(ledger.toPath(), newer.getBytes(StandardCharsets.UTF_8));

		now.addAndGet(MINUTE);
		harvest();
		assertEquals("<html>" + SlayerLedgerPanel.HARVEST_FAILED_MESSAGE + "</html>",
			onEdt(() -> harness.ui().getHistory().getErrorText()));
		assertEquals(newer, new String(Files.readAllBytes(ledger.toPath()), StandardCharsets.UTF_8));
	}

	/**
	 * Araxxor's harvest reported by the game on the tick the held task is released for saving: the loot tracker's
	 * {@code ServerNpcLoot} comes before the release (the plugin's grace ticker runs after LootManager), so the held
	 * task takes it and is saved with it, once.
	 */
	@Test
	public void aHarvestOnTheReleaseTickIsSavedWithTheTaskOnce()
	{
		playToTheFinalKill(NpcID.ARAXXOR, "Araxxor");
		harness.completeTask();
		harness.tick();
		assertTrue("held", harness.tracker().hasPendingCompletion());
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS - 1);
		assertTrue("still held", harness.tracker().hasPendingCompletion());
		assertTrue(harness.records(ACCOUNT).isEmpty());

		harness.lootOnNextTick(NpcID.ARAXXOR, "Araxxor", ItemID.RUNE_PLATEBODY, 1);
		harness.tick();
		assertFalse("released", harness.tracker().hasPendingCompletion());
		final Map<Integer, Long> loot = new TreeMap<>();
		loot.put(COINS, 1_000L);
		loot.put(ItemID.RUNE_PLATEBODY, 1L);
		assertEquals(loot, harness.onlyRecord(ACCOUNT).getLoot());
		assertEquals(Long.valueOf(1_000 + 38_000), harness.onlyRecord(ACCOUNT).getLootGeValue());

		// Its harvest is in: more Araxxor loot is not this task's.
		now.addAndGet(MINUTE);
		harvest();
		assertEquals(loot, harness.onlyRecord(ACCOUNT).getLoot());
	}

	/** Araxxor's harvest reported by the game on the tick after the save: it is added to the saved task. */
	@Test
	public void aHarvestOnTheTickAfterTheSaveIsAddedThroughTheGamesLootPath()
	{
		completeTask(NpcID.ARAXXOR, "Araxxor");
		now.addAndGet(MINUTE);
		harness.lootOnNextTick(NpcID.ARAXXOR, "Araxxor", ItemID.RUNE_PLATEBODY, 1);
		harness.tick();
		harness.drain();
		final Map<Integer, Long> loot = new TreeMap<>();
		loot.put(COINS, 1_000L);
		loot.put(ItemID.RUNE_PLATEBODY, 1L);
		assertEquals(loot, harness.onlyRecord(ACCOUNT).getLoot());
	}

	/**
	 * The count reads 0 a tick before the completion confirms it, and the harvest comes in between, while the
	 * session still runs. The task is saved with it, so no harvest window opens after the save.
	 */
	@Test
	public void aHarvestBeforeTheCompletionIsHeldOpensNoWindow()
	{
		playToTheFinalKill(NpcID.ARAXXOR, "Araxxor");
		harness.setRemaining(0);
		harness.tick();
		assertFalse("not completed yet", harness.tracker().hasPendingCompletion());
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());
		harvest(new ItemStack(ItemID.RUNE_PLATEBODY, 1));
		harness.completeTask();
		harness.tick();
		assertTrue(harness.tracker().hasPendingCompletion());
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS + 1);
		final Map<Integer, Long> loot = new TreeMap<>();
		loot.put(COINS, 1_000L);
		loot.put(ItemID.RUNE_PLATEBODY, 1L);
		assertEquals(loot, harness.onlyRecord(ACCOUNT).getLoot());

		now.addAndGet(MINUTE);
		harvest();
		assertEquals("another Araxxor's loot is not this task's", loot, harness.onlyRecord(ACCOUNT).getLoot());
	}

	/** A harvest whose items are all ignored (or crushed) is still the harvest, so no harvest window opens. */
	@Test
	public void aHarvestWithEveryItemIgnoredOpensNoWindow()
	{
		playToTheFinalKill(NpcID.ARAXXOR, "Araxxor");
		harness.completeTask();
		harness.tick();
		assertTrue(harness.tracker().hasPendingCompletion());
		harness.setIgnoredLoot("Bones");
		harvest(new ItemStack(ItemID.BONES, 3));
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS + 1);
		assertEquals(Collections.singletonMap(COINS, 1_000L), harness.onlyRecord(ACCOUNT).getLoot());

		harness.setIgnoredLoot("");
		now.addAndGet(MINUTE);
		harvest();
		assertEquals("another Araxxor's loot is not this task's", Collections.singletonMap(COINS, 1_000L),
			harness.onlyRecord(ACCOUNT).getLoot());
	}
}
