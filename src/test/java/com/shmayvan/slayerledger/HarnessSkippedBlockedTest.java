package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.ui.PluginPanel;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * The "Skipped / blocked" section in look A (DESIGN_PRINCIPLES.md 3.8, 3.9), read through
 * {@link PanelProbe}: each entry's date, task icon and monster, outcome word and master, in quiet colours that keep
 * 4.5:1; the row's right-click menu on both of its lines; one plain line without entries; long names cut short at the
 * sidebar's width; and no rebuild by the refresh timer or the hover. {@link HarnessAssignmentLogTest} covers how
 * entries are made.
 */
public class HarnessSkippedBlockedTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final String SLOT = " " + ItemListView.ICON_WIDTH + "x" + ItemListView.ICON_HEIGHT;
	private static final String DUST_DEVIL = ItemID.SLAYERGUIDE_DUSTDEVIL + SLOT;
	private static final String EMPTY_SLOT = "-" + SLOT;
	private static final long DAY = 24L * 3_600_000L;
	private static final long ENDED = 1_760_000_000_000L;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
	}

	@Test
	public void eachEntryGivesItsDateIconMonsterOutcomeAndMaster()
	{
		AssignmentEntry skipped = entry("Dust devils", AssignmentOutcome.SKIPPED, 5, ENDED);
		AssignmentEntry blocked = entry("Gargoyles", AssignmentOutcome.BLOCKED, SlayerMaster.KONAR, ENDED - DAY);
		AssignmentEntry untracked = entry("Hellhounds", AssignmentOutcome.COMPLETED, null, ENDED - 2 * DAY);
		// Neither a date nor a master: only the first line.
		AssignmentEntry unknown = entry("Not a Slayer task", null, null, 0);
		setEntries(skipped, blocked, untracked, unknown);
		harness.onEdt(() -> harness.ui().getAssignments().toggle());

		DateTimeFormatter date = AssignmentLog.shortDate(ZoneId.systemDefault());
		List<String> rows = rows();
		assertEquals(4, rows.size());
		assertTrue(rows.get(0), rows.get(0).startsWith("Dust devils | Skipped | " + day(date, ENDED)
			+ " · Duradel/Kuradal | <html>Dust devils<br>"));
		assertTrue(rows.get(1), rows.get(1).startsWith("Gargoyles | Blocked | " + day(date, ENDED - DAY)
			+ " · Konar | <html>"));
		assertTrue(rows.get(2), rows.get(2).startsWith("Hellhounds | Completed (untracked) | "
			+ day(date, ENDED - 2 * DAY) + " | <html>"));
		assertTrue(rows.get(3), rows.get(3).startsWith("Not a Slayer task | Ended | <html>"));
		// The task's icon, or an empty slot of the same size, so the names line up.
		assertEquals(DUST_DEVIL, onEdt(() -> harness.ui().getAssignments().getNameIcons()).get(0));
		assertEquals(EMPTY_SLOT, onEdt(() -> harness.ui().getAssignments().getNameIcons()).get(3));

		// Quieter than the rest (Small, the label and hint colours), never below 4.5:1 on the page or under the mouse:
		// the hover is the card's stone, and LedgerThemeTest checks every text token on the page and the card.
		assertEquals(Arrays.asList(LedgerTheme.LABEL, LedgerTheme.LABEL, LedgerTheme.HINT),
			onEdt(() -> harness.ui().getAssignments().getRowColors(0)));
		assertEquals(Arrays.asList(LedgerTheme.LABEL, LedgerTheme.LABEL),
			onEdt(() -> harness.ui().getAssignments().getRowColors(3)));
		assertEquals(LedgerTheme.CARD, LedgerTheme.HOVER);
	}

	@Test
	public void rightClickingEitherLineOfAnEntryOpensItsMenuWithDeleteOnlyForAnEntryWithAnId()
	{
		AssignmentEntry withId = entry("Dust devils", AssignmentOutcome.SKIPPED, 5, ENDED);
		// LedgerStore.deleteAssignment matches by id, so an entry without one can't be deleted safely.
		AssignmentEntry withoutId = entry("Gargoyles", AssignmentOutcome.BLOCKED, SlayerMaster.KONAR, ENDED - DAY);
		withoutId.setId(null);
		setEntries(withId, withoutId);
		harness.onEdt(() -> harness.ui().getAssignments().toggle());
		assertEquals(2, rows().size());

		assertEquals(Collections.singletonList("Delete entry"),
			onEdt(() -> harness.ui().getAssignments().getMenuItems(0)));
		assertEquals(Collections.singletonList("-Delete entry"),
			onEdt(() -> harness.ui().getAssignments().getMenuItems(1)));
		// Each label of both lines, the monster, the outcome and the date and master under them, opens the row's menu.
		DateTimeFormatter date = AssignmentLog.shortDate(ZoneId.systemDefault());
		assertEquals(Arrays.asList("Dust devils: row menu", "Skipped: row menu",
			day(date, ENDED) + " · Duradel/Kuradal: row menu"),
			onEdt(() -> harness.ui().getAssignments().getLabelMenus(0)));
		assertEquals(Arrays.asList("Gargoyles: row menu", "Blocked: row menu",
			day(date, ENDED - DAY) + " · Konar: row menu"),
			onEdt(() -> harness.ui().getAssignments().getLabelMenus(1)));
	}

	@Test
	public void noEntriesIsOnePlainLine()
	{
		harness.onEdt(() -> harness.ui().getAssignments().toggle());
		assertEquals("Skipped / blocked (0) | Hide", onEdt(() -> harness.ui().getAssignments().getHeaderText()));
		assertEquals("No skipped or blocked tasks yet.", onEdt(() -> harness.ui().getAssignments().getEmptyText()));
		assertTrue(rows().isEmpty());
		assertFalse("no hint about deleting rows that aren't there",
			onEdt(() -> harness.ui().getAssignments().isHintShown()));

		setEntries(entry("Dust devils", AssignmentOutcome.SKIPPED, 5, ENDED));
		assertNull(onEdt(() -> harness.ui().getAssignments().getEmptyText()));
		assertEquals(1, rows().size());
		assertTrue(onEdt(() -> harness.ui().getAssignments().isHintShown()));

		// Deleting the last one shows the one line again.
		setEntries();
		assertEquals(AssignmentsSection.EMPTY_TEXT, onEdt(() -> harness.ui().getAssignments().getEmptyText()));
		assertFalse(onEdt(() -> harness.ui().getAssignments().isHintShown()));
	}

	@Test
	public void longNamesAreCutShortAtTheSidebarsWidth()
	{
		String longName = "Lava strykewyrms in the Wilderness Slayer Cave";
		setEntries(entry(longName, AssignmentOutcome.COMPLETED, 12, ENDED),
			entry("Dust devils", AssignmentOutcome.SKIPPED, 5, ENDED - DAY));
		harness.onEdt(() -> harness.ui().getAssignments().toggle());

		int width = onEdt(() -> harness.ui().getAssignments().layOut());
		assertTrue("list " + width, width > 0 && width <= PluginPanel.PANEL_WIDTH);
		assertTrue(onEdt(() -> harness.ui().getAssignments().getListPreferredWidth()) <= PluginPanel.PANEL_WIDTH);
		List<String> lines = onEdt(() -> harness.ui().getAssignments().getFirstLinesShown());
		// The long name is cut short with "…" and the outcome still fits beside it; the tooltip has the name whole.
		String first = lines.get(0);
		assertTrue(first, first.endsWith(" | Completed (untracked) (fits)"));
		String shownName = first.substring(0, first.indexOf(" | "));
		assertTrue(shownName, shownName.endsWith(FitLabel.ELLIPSIS)
			&& longName.startsWith(shownName.substring(0, shownName.length() - 1)));
		assertTrue(rows().get(0), rows().get(0).contains("| <html>" + longName + "<br>"));
		assertEquals("Dust devils | Skipped (fits)", lines.get(1));
	}

	@Test
	public void theRefreshTimerAndTheHoverRebuildNothing()
	{
		setEntries(entry("Dust devils", AssignmentOutcome.SKIPPED, 5, ENDED),
			entry("Gargoyles", AssignmentOutcome.BLOCKED, 5, ENDED - DAY));
		harness.onEdt(() -> harness.ui().getAssignments().toggle());
		int builds = onEdt(() -> harness.ui().getAssignments().getBuildCount());
		int requests = harness.itemImageRequests();

		harness.onEdt(() ->
		{
			harness.panel().refresh();
			harness.panel().refresh();
		});
		harness.drain();
		assertEquals(builds, (int) onEdt(() -> harness.ui().getAssignments().getBuildCount()));
		assertEquals("no icon asked for again", requests, harness.itemImageRequests());

		// The hover shows the row can be right-clicked, and draws only its background.
		assertEquals(LedgerTheme.PAGE, onEdt(() -> harness.ui().getAssignments().getRowBackground(1)));
		harness.onEdt(() -> harness.ui().getAssignments().hoverRow(1, true));
		assertEquals(LedgerTheme.HOVER, onEdt(() -> harness.ui().getAssignments().getRowBackground(1)));
		harness.onEdt(() -> harness.ui().getAssignments().hoverRow(1, false));
		assertEquals(LedgerTheme.PAGE, onEdt(() -> harness.ui().getAssignments().getRowBackground(1)));
		assertEquals(builds, (int) onEdt(() -> harness.ui().getAssignments().getBuildCount()));
	}

	/** Sets the account's entries, as a load of its ledger does. */
	private void setEntries(AssignmentEntry... entries)
	{
		harness.onEdt(() -> harness.historyModel().setAssignments(ACCOUNT, Arrays.asList(entries)));
		harness.drain();
	}

	private List<String> rows()
	{
		harness.drain();
		return onEdt(() -> harness.ui().getAssignments().getRows());
	}

	/** Reads {@code read} on the EDT. */
	private <T> T onEdt(Supplier<T> read)
	{
		AtomicReference<T> value = new AtomicReference<>();
		harness.onEdt(() -> value.set(read.get()));
		return value.get();
	}

	private static String day(DateTimeFormatter date, long millis)
	{
		return date.format(Instant.ofEpochMilli(millis));
	}

	private static AssignmentEntry entry(String name, AssignmentOutcome outcome, Integer master, long endedAt)
	{
		AssignmentEntry entry = new AssignmentEntry();
		entry.setId(name + endedAt);
		entry.setMonster(name);
		entry.setOutcome(outcome);
		entry.setSlayerMaster(master);
		entry.setEndedAt(endedAt);
		return entry;
	}
}
