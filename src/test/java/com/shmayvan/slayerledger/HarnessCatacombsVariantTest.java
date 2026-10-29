package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.NPC;
import net.runelite.api.gameval.NpcID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Catacombs variants as wired in the plugin ({@link PluginHarness}): the player's hits ({@code HitsplatApplied})
 * and NPC deaths ({@code ActorDeath}) while a session runs are tallied per NPC name (by world view and index), the task
 * box and the overlay's Task row show the variant once the kills say so, and the saved record keeps it, so the History
 * list and Trends show it as its own monster. {@link CatacombsVariantsTest} and {@link CatacombsVariantGroupingTest}
 * cover the rules.
 */
public class HarnessCatacombsVariantTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int TASK = 7;
	private static final String SPECTRES = "Aberrant spectres";
	private static final String DEVIANT = "Deviant spectres";

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
		harness.setTask(TASK, SPECTRES, 5, 5);
		harness.loginAndSettle(ACCOUNT);
	}

	/** The player hits a new NPC and it dies; the task count goes down on the next tick (2 minutes later). */
	private void kill(int id, String name)
	{
		NPC npc = harness.npc(nextIndex++, id, name);
		harness.hitsplat(npc, true);
		harness.npcDies(npc);
		now.addAndGet(2 * 60_000L);
		harness.setRemaining(harness.tracker().getCurrentRemaining() - 1);
		harness.tick();
	}

	private void killDeviant()
	{
		kill(NpcID.KOUREND_SPECTRE, "Deviant spectre");
	}

	private void killAberrant()
	{
		kill(NpcID.SLAYER_ABBERANT_SPECTRE_1, "Aberrant spectre");
	}

	/** Completes the task and lets its grace window pass, so it is saved. */
	private void complete()
	{
		now.addAndGet(60_000L);
		harness.completeTask();
		harness.tick();
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS + 1);
	}

	private List<String> taskBoxRows()
	{
		AtomicReference<List<String>> rows = new AtomicReference<>();
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			rows.set(harness.ui().getTaskBox().getRows());
		});
		return rows.get();
	}

	@Test
	public void variantKillsShowInTheTaskBoxAndAreSavedWithTheTask()
	{
		assertTrue(harness.play());
		assertEquals(SPECTRES + " · 5 left", taskBoxRows().get(0));

		killDeviant();
		assertEquals(Arrays.asList(new NpcKills("Deviant spectre", 1)), harness.tracker().getNpcKills());
		assertEquals(DEVIANT + " · 4 left", taskBoxRows().get(0));
		AtomicReference<String> tooltip = new AtomicReference<>();
		harness.onEdt(() -> tooltip.set(harness.ui().getTaskBox().getTaskTooltip()));
		assertTrue(tooltip.get(), tooltip.get().contains("Task: Aberrant spectres<br>Doing: Deviant spectres"));

		killAberrant();
		assertEquals("a tie is the standard monster", SPECTRES + " · 3 left", taskBoxRows().get(0));
		killDeviant();
		killDeviant();
		complete();

		LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals(SPECTRES, record.getMonster());
		assertEquals(DEVIANT, record.getVariant());
	}

	/** The overlay's Task row value, or {@code null} when it has none. */
	private String overlayTask()
	{
		return overlayValue("Task");
	}

	/** The overlay's {@code label} row value, or {@code null} when it has none. */
	private String overlayValue(String label)
	{
		for (OverlayRows.Row row : OverlayRows.rows(harness.overlayData(), true, true, true, true))
		{
			if (label.equals(row.getLabel()))
			{
				return row.getValue();
			}
		}
		return null;
	}

	/** Asserts the overlay's Task row shows {@code task} and its Left row {@code left}. */
	private void assertOverlayTask(String task, String left)
	{
		assertOverlayTask(null, task, left);
	}

	/** As {@link #assertOverlayTask(String, String)}, with {@code message} on failure. */
	private void assertOverlayTask(String message, String task, String left)
	{
		assertEquals(message, task, overlayTask());
		assertEquals(message, left, overlayValue("Left"));
	}

	@Test
	public void theOverlayTaskRowShowsTheVariantLikeTheTaskBox()
	{
		assertTrue(harness.play());
		assertOverlayTask("the task before any kill", SPECTRES, "5");

		killDeviant();
		assertOverlayTask(DEVIANT, "4");
		assertEquals(DEVIANT + " · 4 left", taskBoxRows().get(0));

		killAberrant();
		assertOverlayTask("a tie is the standard monster", SPECTRES, "3");
		killDeviant();
		assertOverlayTask(DEVIANT, "2");

		// Paused, the decision stands.
		harness.pause();
		assertOverlayTask(DEVIANT, "2");
	}

	@Test
	public void anUnrelatedKillDoesNotDecideTheTask()
	{
		assertTrue(harness.play());
		// An Ankou on the way: tallied, but not a task kill.
		NPC ankou = harness.npc(nextIndex++, NpcID.KOUREND_ANKOU, "Ankou");
		harness.hitsplat(ankou, true);
		harness.npcDies(ankou);
		harness.tick();
		assertEquals(Arrays.asList(new NpcKills("Ankou", 1)), harness.tracker().getNpcKills());
		assertEquals(TaskSelection.ANY, harness.tracker().getCurrentTaskSelection());
		assertEquals(SPECTRES + " · 5 left", taskBoxRows().get(0));

		killDeviant();
		assertEquals(TaskSelection.of(DEVIANT), harness.tracker().getCurrentTaskSelection());
		assertEquals(DEVIANT + " · 4 left", taskBoxRows().get(0));
	}

	@Test
	public void npcsAreToldApartByWorldView()
	{
		assertTrue(harness.play());
		// The same index on a boat is another NPC: its death isn't the one the player hit.
		NPC hit = harness.npc(nextIndex, NpcID.KOUREND_SPECTRE, "Deviant spectre");
		NPC onBoat = harness.npcOnBoat(nextIndex, NpcID.KOUREND_SPECTRE, "Deviant spectre");
		nextIndex++;
		harness.hitsplat(hit, true);
		harness.npcDies(onBoat);
		harness.npcDespawns(onBoat);
		assertTrue(harness.tracker().getNpcKills().isEmpty());
		harness.npcDies(hit);
		assertEquals(Arrays.asList(new NpcKills("Deviant spectre", 1)), harness.tracker().getNpcKills());

		// An NPC without a world view is kept as top-level.
		NPC noView = harness.npcWithoutWorldView(nextIndex++, NpcID.KOUREND_SPECTRE, "Deviant spectre");
		harness.hitsplat(noView, true);
		harness.npcDies(noView);
		assertEquals(Arrays.asList(new NpcKills("Deviant spectre", 2)), harness.tracker().getNpcKills());
	}

	@Test
	public void onlyNpcsThePlayerHitWhileRunningAreTallied()
	{
		// Before Play: not remembered, so its death is not tallied.
		NPC early = harness.npc(nextIndex++, NpcID.KOUREND_SPECTRE, "Deviant spectre");
		harness.hitsplat(early, true);
		assertTrue(harness.play());
		harness.npcDies(early);
		// Someone else's hits only.
		NPC others = harness.npc(nextIndex++, NpcID.KOUREND_SPECTRE, "Deviant spectre");
		harness.hitsplat(others, false);
		harness.npcDies(others);
		// Hit, then despawned before dying (for example out of view).
		NPC gone = harness.npc(nextIndex++, NpcID.KOUREND_SPECTRE, "Deviant spectre");
		harness.hitsplat(gone, true);
		harness.npcDespawns(gone);
		harness.npcDies(gone);
		// A Greater Nechryael outside the Catacombs is not a variant kill.
		NPC wilderness = harness.npc(nextIndex++, NpcID.WILD_CAVE_NECHRYAEL, "Greater Nechryael");
		harness.hitsplat(wilderness, true);
		harness.npcDies(wilderness);
		assertTrue(harness.tracker().getNpcKills().isEmpty());

		// Paused: hits are not remembered.
		harness.pause();
		NPC paused = harness.npc(nextIndex++, NpcID.KOUREND_SPECTRE, "Deviant spectre");
		harness.hitsplat(paused, true);
		assertTrue(harness.play());
		harness.npcDies(paused);
		assertTrue(harness.tracker().getNpcKills().isEmpty());

		// A logout forgets the NPCs hit before it.
		NPC beforeHop = harness.npc(nextIndex++, NpcID.KOUREND_SPECTRE, "Deviant spectre");
		harness.hitsplat(beforeHop, true);
		harness.hop();
		harness.ticks(5);
		assertTrue(harness.play());
		harness.npcDies(beforeHop);
		assertTrue(harness.tracker().getNpcKills().isEmpty());
		assertNull(harness.tracker().getCurrentTaskSelection().getVariant());
	}

	@Test
	public void theEstimatePoolsUntilKillsDecideAndTheAdvisorComparesTheVersions()
	{
		List<LedgerRecord> history = new java.util.ArrayList<>();
		for (int i = 0; i < TaskAdvisor.MIN_TASKS; i++)
		{
			history.add(saved(null, 100_000L));
			history.add(saved(DEVIANT, 400_000L));
		}
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, history));
		harness.drain();

		// Before any kill both versions count.
		assertTrue(estimate()[0], estimate()[0].startsWith("History (6 tasks)"));
		assertTrue(estimate()[1], estimate()[1].contains("Counts Deviant spectres tasks too"));
		// One monster only: no main advice, but the versions are compared.
		assertEquals(Arrays.asList(SPECTRES + " · 5 left", "Better: Deviant spectres"), taskBoxRows());
		AtomicReference<String> tooltip = new AtomicReference<>();
		harness.onEdt(() -> tooltip.set(harness.ui().getTaskBox().getAdviceTooltip()));
		assertTrue("the line in full first, as the sidebar may cut it off: " + tooltip.get(),
			tooltip.get().startsWith("<html>Better: Deviant spectres<br>"));
		assertTrue(tooltip.get(), tooltip.get().contains(
			"Profit (GE)/h: Deviant spectres ~400K (n=3), Aberrant spectres ~100K (n=3)"));

		assertTrue(harness.play());
		killDeviant();
		assertTrue(estimate()[0], estimate()[0].startsWith("History (3 tasks)"));
		assertTrue(estimate()[1], estimate()[1].contains("Only your Deviant spectres tasks count."));
		killAberrant();
		assertTrue("a tie is standard", estimate()[0].startsWith("History (3 tasks)"));
		assertTrue(estimate()[1], estimate()[1].contains("Your Deviant spectres tasks are left out."));
	}

	/** The History estimate's text and tooltip, after a refresh. */
	private String[] estimate()
	{
		String[] result = new String[2];
		harness.drain();
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			result[0] = harness.ui().getHistoryEstimate().getText();
			result[1] = harness.ui().getHistoryEstimate().getTooltip();
		});
		return result;
	}

	/** An hour-long completed spectres task of 10 kills making {@code profit}, saved earlier with totals only (~). */
	private static LedgerRecord saved(String variant, long profit)
	{
		LedgerRecord record = new LedgerRecord();
		record.setId(java.util.UUID.randomUUID().toString());
		record.setCreatureId(TASK);
		record.setMonster(SPECTRES);
		record.setVariant(variant);
		record.setKills(10);
		record.setInitialAmount(10);
		record.setOriginalAmount(10);
		record.setActiveMillis(60 * 60_000L);
		record.setEndedAt(500_000L);
		record.setCompleted(true);
		record.setLootGeValue(profit);
		record.setSuppliesGeValue(0L);
		return record;
	}

	@Test
	public void historyAndTrendsShowTheVariantAsItsOwnMonster()
	{
		assertTrue(harness.play());
		for (int i = 0; i < 4; i++)
		{
			killAberrant();
		}
		complete();

		harness.setTask(TASK, SPECTRES, 5, 5);
		harness.tick();
		assertTrue(harness.play());
		for (int i = 0; i < 4; i++)
		{
			killDeviant();
		}
		complete();

		List<LedgerRecord> records = harness.records(ACCOUNT);
		assertEquals(2, records.size());
		harness.drain();
		AtomicReference<List<String>> filter = new AtomicReference<>();
		AtomicReference<List<String>> titles = new AtomicReference<>();
		harness.onEdt(() ->
		{
			filter.set(harness.ui().getHistory().getFilterOptions());
			titles.set(harness.ui().getHistory().getTitleTexts());
		});
		assertEquals(Arrays.asList(LedgerHistory.ALL, SPECTRES, DEVIANT), filter.get());
		assertEquals("newest first", Arrays.asList(DEVIANT + " | Completed", SPECTRES + " | Completed"), titles.get());

		harness.onEdt(() -> harness.ui().getTrends().clickTitle());
		harness.drain();
		AtomicReference<List<String>> rows = new AtomicReference<>();
		harness.onEdt(() -> rows.set(harness.ui().getTrends().getRows()));
		assertEquals(rows.get().toString(), 2, rows.get().size());
		assertTrue(rows.get().toString(), rows.get().stream().anyMatch(row -> row.contains(". " + DEVIANT + " |")));
		assertTrue(rows.get().toString(), rows.get().stream().anyMatch(row -> row.contains(". " + SPECTRES + " |")));
		assertFalse(rows.get().toString(), rows.get().stream().anyMatch(row -> row.contains("(n=2)")));
	}
}
