package com.shmayvan.slayerledger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.runelite.api.NPC;
import net.runelite.api.gameval.NpcID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Variants and Slayer bosses as wired in the plugin ({@link PluginHarness}): the player's kills of a task's
 * Slayer boss are tallied by NPC name, the task box and the overlay's Task row show the boss once the kills say so (or
 * {@code + boss} for a few boss kills), and the saved record keeps the variant and the boss flag, which the History
 * list shows. A 1.x ledger's records get the flag when read. Nothing here waits for the panel's refresh timer.
 * {@link TaskVariantsTest} covers the rules.
 */
public class HarnessTaskVariantsTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int GARGOYLES = 46;
	private static final int CAVE_KRAKEN = 84;
	private static final String GG = "Grotesque Guardians";

	@Rule
	public final PluginHarness harness = new PluginHarness();

	private final AtomicLong now = new AtomicLong(1_000_000L);
	/** The next NPC scene index, so every NPC is a new one. */
	private int nextIndex = 1;

	@Before
	public void setUp()
	{
		harness.startUp();
		harness.setInventory();
		harness.setTrackerClock(now::get);
	}

	private <T> T onEdt(Supplier<T> read)
	{
		final AtomicReference<T> result = new AtomicReference<>();
		harness.onEdt(() -> result.set(read.get()));
		return result.get();
	}

	/**
	 * The player hits the NPCs and they die; the task count goes down by {@code taskKills} on the next tick (2 minutes
	 * later).
	 */
	private void kill(int taskKills, NPC... npcs)
	{
		for (NPC npc : npcs)
		{
			harness.hitsplat(npc, true);
		}
		for (NPC npc : npcs)
		{
			harness.npcDies(npc);
		}
		now.addAndGet(2 * 60_000L);
		harness.setRemaining(harness.tracker().getCurrentRemaining() - taskKills);
		harness.tick();
	}

	private NPC npc(int id, String name)
	{
		return harness.npc(nextIndex++, id, name);
	}

	/** One Grotesque Guardians kill: Dusk and Dawn die together, taking {@code taskKills} off the task. */
	private void killGuardians(int taskKills)
	{
		kill(taskKills, npc(NpcID.GARGBOSS_DAWN_DEATH, "Dawn"), npc(NpcID.GARGBOSS_DUSK_DEATH, "Dusk"));
	}

	/** Completes the task and lets its grace window pass, so it is saved. */
	private void complete()
	{
		now.addAndGet(60_000L);
		harness.completeTask();
		harness.tick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS + 1);
	}

	private String taskBoxRow()
	{
		return onEdt(() ->
		{
			harness.panel().refresh();
			return harness.ui().getTaskBox().getRows().get(0);
		});
	}

	private String taskBoxTooltip()
	{
		return onEdt(() -> harness.ui().getTaskBox().getTaskTooltip());
	}

	/** The overlay's task name, or {@code null} when it shows none. */
	private String overlayTask()
	{
		for (OverlayRows.Row row : OverlayRows.rows(harness.overlayData(), OverlayRows.Groups.ALL))
		{
			if (row.getKind() == OverlayRows.Kind.IDENTITY)
			{
				return row.getLeft();
			}
		}
		return null;
	}

	@Test
	public void grotesqueGuardiansKillsOnAGargoyleTaskSetTheVariantAndTheBossFlag()
	{
		harness.setTask(GARGOYLES, "Gargoyles", 6, 6);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		assertEquals("Gargoyles", overlayTask());

		killGuardians(1);
		assertEquals(Arrays.asList(new NpcKills("Dawn", 1), new NpcKills("Dusk", 1)), harness.tracker().getNpcKills());
		assertEquals(GG, overlayTask());
		assertEquals(GG, taskBoxRow());
		assertTrue(taskBoxTooltip(), taskBoxTooltip().contains("Task: Gargoyles<br>Doing: " + GG + " (from your kills)"));

		// A gargoyle: a tie is the standard monster, with the boss kill still shown.
		kill(1, npc(NpcID.SLAYER_GARGOYLE_1, "Gargoyle"));
		assertEquals("Gargoyles + boss", overlayTask());
		assertEquals("Gargoyles + boss", taskBoxRow());
		assertTrue(taskBoxTooltip(), taskBoxTooltip().contains("<br>Boss kills: " + GG));

		// With Double Trouble a Guardians kill takes two off the task; it still counts as one kill of the boss.
		killGuardians(2);
		assertEquals(GG, overlayTask());
		killGuardians(1);
		complete();

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals("Gargoyles", record.getMonster());
		assertEquals(GG, record.getVariant());
		assertEquals(Boolean.TRUE, record.getBossKills());
		harness.drain();
		assertEquals(Arrays.asList(GG), onEdt(() -> harness.ui().getHistory().getTitleTexts()));
		String tooltip = onEdt(() -> harness.ui().getHistory().getTooltips().get(0));
		assertTrue(tooltip, tooltip.contains("<br>Slayer boss of Gargoyles"));
	}

	@Test
	public void aFewKrakenKillsOnACaveKrakenTaskShowAsBossKills()
	{
		harness.setTask(CAVE_KRAKEN, "Cave kraken", 4, 4);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		kill(1, npc(NpcID.SLAYER_KRAKEN, "Cave kraken"));
		kill(1, npc(NpcID.SLAYER_KRAKEN, "Cave kraken"));
		assertEquals("Cave kraken", overlayTask());
		kill(1, npc(NpcID.SLAYER_KRAKEN_BOSS, "Kraken"));
		assertEquals("Cave kraken + boss", overlayTask());
		assertEquals("Cave kraken + boss", taskBoxRow());
		kill(1, npc(NpcID.SLAYER_KRAKEN, "Cave kraken"));
		complete();

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertNull("the cave kraken did most of the task", record.getVariant());
		assertEquals(Boolean.TRUE, record.getBossKills());
		harness.drain();
		assertEquals(Arrays.asList("Cave kraken"), onEdt(() -> harness.ui().getHistory().getTitleTexts()));
		String tooltip = onEdt(() -> harness.ui().getHistory().getTooltips().get(0));
		assertTrue(tooltip, tooltip.contains("<br>Boss kills: Kraken"));
	}

	@Test
	public void aOneXLedgerGetsTheBossFlagWhenRead() throws IOException
	{
		// Synthetic 1.x records: a gargoyle task with Grotesque Guardians kill counts, and one without.
		File ledger = new File(harness.ledgerDirectory(), ACCOUNT + ".json");
		assertTrue(harness.ledgerDirectory().isDirectory() || harness.ledgerDirectory().mkdirs());
		String oneX = "{\"schemaVersion\":2,\"records\":["
			+ "{\"schemaVersion\":2,\"id\":\"gg\",\"monster\":\"Gargoyles\",\"creatureId\":46,\"kills\":10,"
			+ "\"activeMillis\":600000,\"startedAt\":1000,\"endedAt\":601000,\"completed\":true,\"partial\":false,"
			+ "\"bossKillCounts\":[{\"boss\":\"Grotesque Guardians\",\"firstKc\":100,\"lastKc\":102,\"kills\":3}]},"
			+ "{\"schemaVersion\":2,\"id\":\"plain\",\"monster\":\"Gargoyles\",\"creatureId\":46,\"kills\":10,"
			+ "\"activeMillis\":600000,\"startedAt\":1000,\"endedAt\":501000,\"completed\":true,\"partial\":false}]}";
		Files.write(ledger.toPath(), oneX.getBytes(StandardCharsets.UTF_8));
		harness.loginAndSettle(ACCOUNT);
		harness.drain();

		List<LedgerRecord> records = harness.records(ACCOUNT);
		assertEquals(Boolean.TRUE, records.stream().filter(r -> "gg".equals(r.getId())).findFirst().get().getBossKills());
		assertEquals(Boolean.FALSE,
			records.stream().filter(r -> "plain".equals(r.getId())).findFirst().get().getBossKills());
		List<String> tooltips = onEdt(() -> harness.ui().getHistory().getTooltips());
		assertEquals(2, tooltips.size());
		assertTrue(tooltips.get(0), tooltips.get(0).contains("<br>Boss kills: " + GG));
		assertFalse(tooltips.get(1), tooltips.get(1).contains("Boss kills"));
		// Reading changed nothing on disk.
		assertEquals(oneX, new String(Files.readAllBytes(ledger.toPath()), StandardCharsets.UTF_8));
	}
}
