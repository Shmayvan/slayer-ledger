package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.ui.PluginPanel;
import org.junit.Rule;
import org.junit.Test;

/**
 * The task icons as wired in the plugin: Task history rows, Skipped / blocked rows, the Luck meter's Task
 * choices, Trends' List rows and Monster choices, through the {@code ItemManager.getImage} fake on the EDT, and the
 * masters' faces in Overall totals' By master. Variants and cannon tasks show their task's icon, unknown tasks an
 * empty slot of the same size; nothing widens the sidebar; nothing is asked for while a section is closed or by the
 * refresh timer.
 */
public class HarnessMoreIconsTest
{
	/** A name that is no task, long enough to test the sidebar's width. */
	private static final String UNKNOWN = "Lava strykewyrms in the Wilderness Slayer Cave";
	private static final String SLOT = " " + ItemListView.ICON_WIDTH + "x" + ItemListView.ICON_HEIGHT;
	private static final String DUST_DEVIL = ItemID.SLAYERGUIDE_DUSTDEVIL + SLOT;
	private static final String SPECTRE = ItemID.SLAYERGUIDE_ABERRANTSPECTER + SLOT;
	private static final String EMPTY = "-" + SLOT;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Test
	public void historyRowsShowTheirTasksIcon()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT, versions(1)));
		harness.drain();

		assertEquals(Arrays.asList("Dust devils | Completed", "Deviant spectres | Completed",
			"Dust devils (cannon) | Completed", UNKNOWN + " | Completed"),
			onEdt(() -> harness.ui().getHistory().getTitleTexts()));
		// A Catacombs variant and a cannon task show their task's icon; an unknown task an empty slot of the same size.
		assertEquals(Arrays.asList(DUST_DEVIL, SPECTRE, DUST_DEVIL, EMPTY),
			onEdt(() -> harness.ui().getHistory().getNameIcons()));
		int width = onEdt(() -> harness.ui().getHistory().getListPreferredWidth());
		assertTrue("wants " + width, width <= PluginPanel.PANEL_WIDTH);

		// The refresh timer asks for none.
		int before = harness.itemImageRequests();
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			harness.panel().refresh();
		});
		harness.drain();
		assertEquals(before, harness.itemImageRequests());
	}

	@Test
	public void skippedAndBlockedRowsShowTheirTasksIcon()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		List<AssignmentEntry> entries = Arrays.asList(entry("Dust devils", 3), entry("Aberrant spectres", 2),
			entry(UNKNOWN, 1));
		int before = harness.itemImageRequests();
		harness.onEdt(() ->
		{
			harness.historyModel().setAssignments(PluginHarness.ACCOUNT, entries);
			harness.panel().refresh();
		});
		harness.drain();
		assertEquals("closed: no icons", before, harness.itemImageRequests());

		harness.onEdt(() -> harness.ui().getAssignments().toggle());
		harness.drain();
		List<String> rows = onEdt(() -> harness.ui().getAssignments().getRows());
		assertTrue(rows.toString(), rows.get(0).startsWith("Dust devils | "));
		assertTrue(rows.toString(), rows.get(2).startsWith(UNKNOWN + " | "));
		assertEquals(Arrays.asList(DUST_DEVIL, SPECTRE, EMPTY),
			onEdt(() -> harness.ui().getAssignments().getNameIcons()));
		assertEquals(before + 2, harness.itemImageRequests());
		int width = onEdt(() -> harness.ui().getAssignments().getListPreferredWidth());
		assertTrue("wants " + width, width <= PluginPanel.PANEL_WIDTH);

		harness.onEdt(() -> harness.panel().refresh());
		harness.drain();
		assertEquals("the refresh timer asks for none", before + 2, harness.itemImageRequests());
	}

	@Test
	public void luckMeterTaskChoicesShowTheirIcons()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		List<LedgerRecord> records = Arrays.asList(monster("Abyssal demons"), monster("Dust devils"));
		// The meter counts tasks with loot data.
		records.forEach(record -> record.setLoot(Collections.emptyMap()));
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT, records));
		harness.drain();
		// The history rows have asked for theirs; the closed Luck meter asks for none.
		int before = harness.itemImageRequests();
		harness.onEdt(() -> harness.panel().refresh());
		harness.drain();
		assertEquals("closed: no icons", before, harness.itemImageRequests());
		assertEquals(Collections.emptyList(), onEdt(() -> harness.ui().getLuckMeter().getTaskIcons()));

		harness.onEdt(() -> harness.ui().getLuckMeter().clickTitle());
		harness.drain();
		assertEquals(Arrays.asList("Abyssal demons | " + ItemID.SLAYERGUIDE_ABYSSALDEMON + SLOT,
			"Dust devils | " + DUST_DEVIL), onEdt(() -> harness.ui().getLuckMeter().getTaskIcons()));
		// Each task's icon once: painting the choices again asks for none.
		assertEquals(before + 2, harness.itemImageRequests());
		onEdt(() -> harness.ui().getLuckMeter().getTaskIcons());
		harness.onEdt(() -> harness.panel().refresh());
		harness.drain();
		assertEquals(before + 2, harness.itemImageRequests());

		int width = onEdt(() -> harness.ui().getLuckMeter().getTaskPreferredWidth());
		assertTrue("wants " + width, width <= PluginPanel.PANEL_WIDTH);
	}

	@Test
	public void trendsRowsAndMonsterChoicesShowTheirTasksIcon()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		List<LedgerRecord> records = versions(2);
		int before = harness.itemImageRequests();
		harness.onEdt(() ->
		{
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, records);
			harness.ui().getTrends().setMetric(TaskRanking.Metric.XP);
			harness.panel().refresh();
		});
		harness.drain();
		// The history rows ask for theirs; Trends, closed, for none.
		int afterHistory = harness.itemImageRequests();
		harness.onEdt(() -> harness.panel().refresh());
		harness.drain();
		assertEquals(afterHistory, harness.itemImageRequests());
		assertTrue(afterHistory > before);

		harness.onEdt(() -> harness.ui().getTrends().clickTitle());
		harness.drain();
		List<String> rows = onEdt(() -> harness.ui().getTrends().getRows());
		assertEquals(rows.toString(), 4, rows.size());
		List<String> icons = onEdt(() -> harness.ui().getTrends().getRowIcons());
		for (int i = 0; i < rows.size(); i++)
		{
			assertEquals(rows.get(i), expectedIcon(rows.get(i)), icons.get(i));
		}
		// One per List row with a known task: the unknown task's empty slot asks for none.
		assertEquals(afterHistory + 3, harness.itemImageRequests());
		harness.onEdt(() -> harness.panel().refresh());
		harness.drain();
		assertEquals("the refresh timer asks for none", afterHistory + 3, harness.itemImageRequests());

		harness.onEdt(() -> harness.ui().getTrends().clickView(TrendsSection.View.CHART));
		harness.drain();
		List<String> choices = onEdt(() -> harness.ui().getTrends().getMonsterIcons());
		assertEquals(choices.toString(), 4, choices.size());
		for (String choice : choices)
		{
			String label = choice.substring(0, choice.indexOf(" | "));
			assertEquals(choice, label + " | " + expectedIcon(label), choice);
		}
		// Each task's icon once (Dust devils and its cannon group share one).
		int afterChart = harness.itemImageRequests();
		assertEquals(afterHistory + 3 + 2, afterChart);
		onEdt(() -> harness.ui().getTrends().getMonsterIcons());
		harness.onEdt(() -> harness.panel().refresh());
		harness.drain();
		assertEquals(afterChart, harness.itemImageRequests());

		int width = onEdt(() -> harness.ui().getTrends().getPreferredWidth());
		assertTrue("wants " + width, width <= PluginPanel.PANEL_WIDTH);
	}

	@Test
	public void byMasterNamesShowTheMastersFace()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		List<LedgerRecord> records = new ArrayList<>();
		// Every master (a shared name uses one face), and a value not in the table.
		for (int master = 1; master <= SlayerMaster.MORTIMER; master++)
		{
			LedgerRecord record = monster("Dust devils");
			record.setSlayerMaster(master);
			// Newest first by master, so the groups show in table order.
			record.setEndedAt(100 - master);
			records.add(record);
		}
		LedgerRecord unknown = monster("Dust devils");
		unknown.setSlayerMaster(42);
		records.add(unknown);
		harness.onEdt(() ->
		{
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, records);
			harness.ui().getOverallTotals().clickTitle();
			harness.ui().getOverallTotals().setGrouping(OverallTotals.Grouping.MASTER);
		});
		harness.drain();

		List<String> rows = onEdt(() -> harness.ui().getOverallTotals().getRows());
		List<String> icons = onEdt(() -> harness.ui().getOverallTotals().getNameIcons());
		assertEquals(rows.size(), icons.size());
		List<String> seen = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++)
		{
			String name = rows.get(i).substring(0, rows.get(i).indexOf(" | "));
			String file = MasterIcons.fileName(name);
			// A missing image (the masters/ folder deleted for the hub) is an empty slot of the same size.
			String expected = file != null && MasterIcons.class.getResource(MasterIcons.DIRECTORY + file) != null
				? "master:" + file + SLOT : EMPTY;
			assertEquals(name, expected, icons.get(i));
			seen.add(name);
		}
		assertTrue(seen.toString(), seen.contains("Duradel/Kuradal"));
		assertTrue(seen.toString(), seen.contains("Unknown master (42)"));
		assertEquals(seen.toString(), 11, seen.size());
		int width = onEdt(() -> harness.ui().getOverallTotals().getListPreferredWidth());
		assertTrue("wants " + width, width <= PluginPanel.PANEL_WIDTH);
	}

	/** The icon a Trends name shows: its task's, from the name without a cannon suffix; an empty slot otherwise. */
	private static String expectedIcon(String row)
	{
		if (row.contains("Dust devils"))
		{
			return DUST_DEVIL;
		}
		return row.contains("Deviant spectres") ? SPECTRE : EMPTY;
	}

	/**
	 * {@code count} completed Konar tasks each of Dust devils, Deviant spectres (an Aberrant spectres task), Dust
	 * devils with a cannon and {@link #UNKNOWN}, newest first in that order, with Slayer XP for Trends.
	 */
	private static List<LedgerRecord> versions(int count)
	{
		List<LedgerRecord> records = new ArrayList<>();
		long ended = 1_000_000;
		for (int i = 0; i < count; i++)
		{
			records.add(at(monster("Dust devils"), ended--));
			LedgerRecord deviant = monster("Aberrant spectres");
			deviant.setVariant("Deviant spectres");
			records.add(at(deviant, ended--));
			LedgerRecord cannon = monster("Dust devils");
			cannon.setSupplies(Collections.singletonMap(SupplyTracker.CANNONBALL, 200L));
			records.add(at(cannon, ended--));
			records.add(at(monster(UNKNOWN), ended--));
		}
		records.sort((a, b) -> Long.compare(b.getEndedAt(), a.getEndedAt()));
		return records;
	}

	private static LedgerRecord at(LedgerRecord record, long endedAt)
	{
		record.setEndedAt(endedAt);
		return record;
	}

	/** A completed Konar task of {@code name}, with Slayer XP and no values. */
	private static LedgerRecord monster(String name)
	{
		LedgerRecord record = new LedgerRecord();
		record.setId(UUID.randomUUID().toString());
		record.setMonster(name);
		record.setSlayerMaster(SlayerMaster.KONAR);
		record.setEndedAt(1);
		record.setKills(10);
		record.setActiveMillis(30 * 60_000L);
		record.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 1_000L));
		record.setCompleted(true);
		record.setPartial(false);
		return record;
	}

	/** A Konar "Skipped / blocked" entry, ended at {@code endedAt}. */
	private static AssignmentEntry entry(String name, long endedAt)
	{
		AssignmentEntry entry = new AssignmentEntry();
		entry.setId(UUID.randomUUID().toString());
		entry.setMonster(name);
		entry.setSlayerMaster(SlayerMaster.KONAR);
		entry.setOutcome(AssignmentOutcome.SKIPPED);
		entry.setEndedAt(endedAt);
		return entry;
	}

	/** Reads {@code read} on the EDT. */
	private <T> T onEdt(Supplier<T> read)
	{
		AtomicReference<T> value = new AtomicReference<>();
		harness.onEdt(() -> value.set(read.get()));
		return value.get();
	}
}
