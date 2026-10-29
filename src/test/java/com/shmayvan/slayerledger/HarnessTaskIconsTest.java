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
 * The task icons of Overall totals as wired in the plugin: By monster's group names and By master's monsters
 * assigned show the task's item sprite at the small slot size, through the {@code ItemManager.getImage} fake, on the
 * EDT; an unknown task keeps an empty slot of the same size; nothing is asked for while the section is closed or by
 * the refresh timer.
 */
public class HarnessTaskIconsTest
{
	/** A name that is no task, long enough to test the sidebar's width. */
	private static final String UNKNOWN = "Lava strykewyrms in the Wilderness Slayer Cave";
	private static final String SLOT = " " + ItemListView.ICON_WIDTH + "x" + ItemListView.ICON_HEIGHT;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Test
	public void byMonsterNamesShowTheirTasksIcon()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		List<LedgerRecord> records = new ArrayList<>();
		for (int i = 0; i < 3; i++)
		{
			records.add(monster("Dust devils"));
		}
		for (int i = 0; i < 2; i++)
		{
			LedgerRecord deviant = monster("Aberrant spectres");
			deviant.setVariant("Deviant spectres");
			records.add(deviant);
		}
		LedgerRecord cannon = monster("Dust devils");
		cannon.setSupplies(Collections.singletonMap(SupplyTracker.CANNONBALL, 200L));
		records.add(cannon);
		records.add(monster(UNKNOWN));
		harness.onEdt(() ->
		{
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, records);
			harness.ui().getOverallTotals().clickTitle();
			harness.ui().getOverallTotals().setGrouping(OverallTotals.Grouping.MONSTER);
		});

		List<String> rows = rows();
		assertEquals(4, rows.size());
		// The names read as before: the icon is on the name's label, not in its text.
		assertTrue(rows.get(0), rows.get(0).startsWith("Dust devils | 3 tasks\n"));
		assertTrue(rows.get(1), rows.get(1).startsWith("Deviant spectres | 2 tasks\n"));
		assertTrue(rows.get(2), rows.get(2).startsWith("Dust devils (cannon) | 1 task\n"));
		assertTrue(rows.get(3), rows.get(3).startsWith(UNKNOWN + " | 1 task\n"));
		// A Catacombs variant and a cannon task show their task's icon; an unknown task an empty slot of the same size.
		assertEquals(Arrays.asList(ItemID.SLAYERGUIDE_DUSTDEVIL + SLOT, ItemID.SLAYERGUIDE_ABERRANTSPECTER + SLOT,
			ItemID.SLAYERGUIDE_DUSTDEVIL + SLOT, "-" + SLOT),
			onEdt(() -> harness.ui().getOverallTotals().getNameIcons()));

		int width = onEdt(() -> harness.ui().getOverallTotals().getListPreferredWidth());
		assertTrue("wants " + width, width <= PluginPanel.PANEL_WIDTH);

		// Overall and By month have no icons; By master shows the master's face (HarnessMoreIconsTest).
		for (OverallTotals.Grouping grouping : Arrays.asList(OverallTotals.Grouping.ALL,
			OverallTotals.Grouping.MONTH))
		{
			harness.onEdt(() -> harness.ui().getOverallTotals().setGrouping(grouping));
			harness.drain();
			assertEquals(grouping.toString(), Collections.singletonList("none"),
				onEdt(() -> harness.ui().getOverallTotals().getNameIcons()));
		}
	}

	@Test
	public void monstersAssignedLinesShowTheirTasksIcon()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		LedgerRecord deviant = monster("Aberrant spectres");
		deviant.setVariant("Deviant spectres");
		LedgerRecord cannon = monster("Dust devils");
		cannon.setSupplies(Collections.singletonMap(SupplyTracker.CANNONBALL, 200L));
		List<LedgerRecord> records = Arrays.asList(monster("Dust devils"), monster("Dust devils"), cannon, deviant);
		List<AssignmentEntry> entries = Arrays.asList(entry("Abyssal demons"), entry(UNKNOWN));
		harness.onEdt(() ->
		{
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, records);
			harness.historyModel().setAssignments(PluginHarness.ACCOUNT, entries);
			harness.ui().getOverallTotals().clickTitle();
			harness.ui().getOverallTotals().setGrouping(OverallTotals.Grouping.MASTER);
		});
		rows();

		// Variants and cannon tasks count as their task, with its icon; the unknown name keeps an empty slot.
		assertEquals(Arrays.asList("Dust devils | 3 / 6 (50%)", "Aberrant spectres | 1 / 6 (17%)",
			"Abyssal demons | 1 / 6 (17%)", UNKNOWN + " | 1 / 6 (17%)"),
			onEdt(() -> harness.ui().getOverallTotals().getAssignments(0).getLineTexts()));
		assertEquals(Arrays.asList(ItemID.SLAYERGUIDE_DUSTDEVIL + SLOT, ItemID.SLAYERGUIDE_ABERRANTSPECTER + SLOT,
			ItemID.SLAYERGUIDE_ABYSSALDEMON + SLOT, "-" + SLOT),
			onEdt(() -> harness.ui().getOverallTotals().getAssignments(0).getLineIcons()));
		// The master's name shows the master's face, or an empty slot when the image is not bundled.
		String konar = MasterIcons.fileName("Konar");
		assertEquals(Collections.singletonList(MasterIcons.class.getResource(MasterIcons.DIRECTORY + konar) != null
			? "master:" + konar + SLOT : "-" + SLOT), onEdt(() -> harness.ui().getOverallTotals().getNameIcons()));

		int width = onEdt(() -> harness.ui().getOverallTotals().getListPreferredWidth());
		assertTrue("wants " + width, width <= PluginPanel.PANEL_WIDTH);

		// Equal lines are not drawn again: the same entries again rebuild the totals but ask for no icon.
		int before = harness.itemImageRequests();
		int builds = onEdt(() -> harness.ui().getOverallTotals().getBuildCount());
		harness.onEdt(() -> harness.historyModel().setAssignments(PluginHarness.ACCOUNT, new ArrayList<>(entries)));
		harness.drain();
		assertTrue("the totals were built again",
			onEdt(() -> harness.ui().getOverallTotals().getBuildCount()) > builds);
		assertEquals(before, harness.itemImageRequests());
	}

	@Test
	public void noIconIsAskedForWhileClosedOrByTheRefreshTimer()
	{
		harness.startUp();
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		List<LedgerRecord> records = Arrays.asList(monster("Dust devils"), monster("Kurask"));
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT, records));
		harness.drain();
		// The Task history rows have asked for theirs; the closed totals ask for none.
		int before = harness.itemImageRequests();
		harness.onEdt(() ->
		{
			harness.ui().getOverallTotals().setGrouping(OverallTotals.Grouping.MONSTER);
			harness.panel().refresh();
		});
		harness.drain();
		assertEquals("closed: no icons", before, harness.itemImageRequests());

		// Opening asks for the two icons once, on the EDT (the fake fails a call from any other thread).
		harness.onEdt(() -> harness.ui().getOverallTotals().clickTitle());
		rows();
		assertEquals(before + 2, harness.itemImageRequests());
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			harness.panel().refresh();
		});
		harness.drain();
		assertEquals("the refresh timer asks for none", before + 2, harness.itemImageRequests());

		// Closed again, new records ask for none in the totals: only the one Task history row asks for its icon.
		harness.onEdt(() ->
		{
			harness.ui().getOverallTotals().clickTitle();
			harness.historyModel().setRecords(PluginHarness.ACCOUNT, Collections.singletonList(monster("Nechryael")));
		});
		harness.drain();
		assertEquals(before + 3, harness.itemImageRequests());
	}

	/** A completed Konar task of {@code name}, without values. */
	private static LedgerRecord monster(String name)
	{
		LedgerRecord record = new LedgerRecord();
		record.setId(UUID.randomUUID().toString());
		record.setMonster(name);
		record.setSlayerMaster(SlayerMaster.KONAR);
		record.setEndedAt(1);
		record.setKills(10);
		record.setActiveMillis(30 * 60_000L);
		record.setCompleted(true);
		record.setPartial(false);
		return record;
	}

	/** A Konar "Skipped / blocked" entry. */
	private static AssignmentEntry entry(String name)
	{
		AssignmentEntry entry = new AssignmentEntry();
		entry.setId(UUID.randomUUID().toString());
		entry.setMonster(name);
		entry.setSlayerMaster(SlayerMaster.KONAR);
		entry.setOutcome(AssignmentOutcome.SKIPPED);
		entry.setEndedAt(1);
		return entry;
	}

	/** Reads {@code read} on the EDT. */
	private <T> T onEdt(Supplier<T> read)
	{
		AtomicReference<T> value = new AtomicReference<>();
		harness.onEdt(() -> value.set(read.get()));
		return value.get();
	}

	/** The Overall totals rows, after the totals worked out off the EDT arrive. */
	private List<String> rows()
	{
		harness.drain();
		return onEdt(() -> harness.ui().getOverallTotals().getRows());
	}
}
