package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import org.junit.Test;

/**
 * The overlay's rows ({@link OverlayRows}): each row's text, the four groups in every combination, every state
 * (the caves too), an unknown task size, the runway's two levels and its place over the moment line, and fitting the
 * text to the width with a narrow font.
 */
public class OverlayRowsTest
{
	/** The mockup's Dust devils task (DESIGN_PRINCIPLES.md 4.2): 73 / 201 after 21:54, 18 seconds a kill. */
	private static final int ASSIGNED = 201;
	private static final int LEFT = 128;
	private static final int KILLS = 73;
	private static final long ACTIVE = (21 * 60 + 54) * 1000L;

	private static SessionTracker.LiveTask task(SessionTracker.Status status, int kills, long activeMillis,
		int remaining)
	{
		return new SessionTracker.LiveTask(status, "session", "Dust devils", 42, kills, activeMillis, remaining);
	}

	private static SessionTracker.LiveTask running()
	{
		return task(SessionTracker.Status.RUNNING, KILLS, ACTIVE, LEFT);
	}

	private static OverlayRows.Snapshot.SnapshotBuilder snapshot()
	{
		return OverlayRows.Snapshot.builder()
			.sessionId("session")
			.taskName("Dust devils")
			.iconItemId(4)
			.spotName("Catacombs")
			.tags(Arrays.asList(SetupTagIcons.Icon.BARRAGE, SetupTagIcons.Icon.PRAYER))
			.assigned(ASSIGNED)
			.superiors(1)
			.xp(58_600);
	}

	private static SessionValuation.Values values(long loot, int lootUnpriced, long supplies)
	{
		return SessionValuation.Values.builder().sessionId("session").lootPriceMode(LootPriceMode.GE).lootValue(loot)
			.lootValueUnpriced(lootUnpriced).lootGe(loot).lootHa(loot).suppliesGe(supplies).suppliesHa(supplies)
			.lootUnpriced(lootUnpriced).build();
	}

	private static OverlayRows.Data data(SessionTracker.LiveTask task, OverlayRows.Snapshot snapshot,
		SessionValuation.Values values)
	{
		return new OverlayRows.Data(task, snapshot, values, null);
	}

	/** The mockup's state, every group on. */
	private static OverlayRows.Data mockup()
	{
		return data(running(), snapshot().build(), values(300_000, 0, 88_000));
	}

	private static List<String> text(List<OverlayRows.Row> rows)
	{
		List<String> text = new ArrayList<>();
		for (OverlayRows.Row row : rows)
		{
			text.add(row.toString());
		}
		return text;
	}

	private static List<OverlayRows.Kind> kinds(List<OverlayRows.Row> rows)
	{
		List<OverlayRows.Kind> kinds = new ArrayList<>();
		for (OverlayRows.Row row : rows)
		{
			kinds.add(row.getKind());
		}
		return kinds;
	}

	private static OverlayRows.Row row(List<OverlayRows.Row> rows, OverlayRows.Kind kind)
	{
		for (OverlayRows.Row row : rows)
		{
			if (row.getKind() == kind)
			{
				return row;
			}
		}
		return null;
	}

	@Test
	public void theMockupsRowsInTheirFixedOrder()
	{
		List<OverlayRows.Row> rows = OverlayRows.rows(mockup(), OverlayRows.Groups.ALL);

		assertEquals(Arrays.asList(
			"IDENTITY: Dust devils | Catacombs",
			"BAR: 73 / 201",
			"PROGRESS: 128 left / ~38 min",
			"PACE: 0:18/kill / 200/h",
			"ELAPSED: 21:54 elapsed / 1 superior",
			"HAUL: +212K / 58.6K XP"), text(rows));
		// Six text lines (the identity is two) and the bar: DESIGN_PRINCIPLES.md 2.1's default.
		OverlayRows.Row identity = rows.get(0);
		assertEquals(Integer.valueOf(4), identity.getIconItemId());
		assertEquals(Arrays.asList(SetupTagIcons.Icon.BARRAGE, SetupTagIcons.Icon.PRAYER), identity.getTags());
		assertEquals(OverlayRows.Tone.NAME, identity.getLeftTone());
		OverlayRows.Row bar = rows.get(1);
		assertEquals(73, bar.getDone());
		assertEquals(201, bar.getTotal());
		// The one key number, Bold while the overlay font is Plain.
		assertTrue(rows.get(2).isKey());
		assertTrue(rows.get(2).isBold());
		for (OverlayRows.Row other : rows)
		{
			if (other.getKind() != OverlayRows.Kind.PROGRESS)
			{
				assertFalse(other.toString(), other.isBold());
			}
		}
		assertEquals("a profit is light green", OverlayRows.Tone.GOOD, rows.get(5).getLeftTone());
		assertEquals(OverlayRows.Tone.VALUE, rows.get(5).getRightTone());
	}

	@Test
	public void theNumbersAreTheSidebarsFormats()
	{
		SessionValuation.Values values = values(300_000, 0, 88_000);
		assertEquals(LiveSessionSection.liveProfitText(values), OverlayRows.profitText(values));
		SessionValuation.Values approximate = values(100, 1, 200);
		assertEquals("~-100", OverlayRows.profitText(approximate));
		assertEquals(LiveSessionSection.liveProfitText(approximate), OverlayRows.profitText(approximate));
		assertEquals("+2.1M", OverlayRows.profitText(values(2_500_000, 0, 400_000)));
		assertEquals("0", OverlayRows.profitText(values(0, 0, 0)));
		assertEquals("950 XP", OverlayRows.xpText(950));
		assertEquals("1,235 XP", OverlayRows.xpText(1_235));
		assertEquals("1 superior", OverlayRows.superiorsText(1));
		assertEquals("0 superiors", OverlayRows.superiorsText(0));
		assertEquals("12 superiors", OverlayRows.superiorsText(12));
	}

	@Test
	public void lossAndZeroProfitTones()
	{
		List<OverlayRows.Row> loss = OverlayRows.rows(data(running(), snapshot().build(), values(100, 0, 38_100)),
			OverlayRows.Groups.ALL);
		assertEquals("-38K", row(loss, OverlayRows.Kind.HAUL).getLeft());
		assertEquals(OverlayRows.Tone.ALERT, row(loss, OverlayRows.Kind.HAUL).getLeftTone());
		List<OverlayRows.Row> zero = OverlayRows.rows(data(running(), snapshot().build(), values(0, 0, 0)),
			OverlayRows.Groups.ALL);
		assertEquals(OverlayRows.Tone.VALUE, row(zero, OverlayRows.Kind.HAUL).getLeftTone());
	}

	@Test
	public void everyCombinationOfTheFourGroups()
	{
		OverlayRows.Snapshot withRunway = snapshot()
			.runway(new SupplyRunway.Warning(SupplyRunway.Kind.PRAYER, 2434, 40)).moment("Superior spawned.").build();
		OverlayRows.Snapshot withMoment = snapshot().moment("Superior spawned.").build();
		for (int bits = 0; bits < 16; bits++)
		{
			boolean progress = (bits & 1) != 0;
			boolean pace = (bits & 2) != 0;
			boolean haul = (bits & 4) != 0;
			boolean runway = (bits & 8) != 0;
			OverlayRows.Groups groups = new OverlayRows.Groups(progress, pace, haul, runway);
			String name = groups.toString();

			List<OverlayRows.Row> rows = OverlayRows.rows(data(running(), withRunway, values(300_000, 0, 88_000)),
				groups);
			List<OverlayRows.Kind> expected = new ArrayList<>();
			if (progress)
			{
				expected.addAll(Arrays.asList(OverlayRows.Kind.IDENTITY, OverlayRows.Kind.BAR,
					OverlayRows.Kind.PROGRESS));
			}
			if (pace)
			{
				expected.add(OverlayRows.Kind.PACE);
			}
			if (pace || haul)
			{
				expected.add(OverlayRows.Kind.ELAPSED);
			}
			if (haul)
			{
				expected.add(OverlayRows.Kind.HAUL);
			}
			if (runway)
			{
				expected.add(OverlayRows.Kind.RUNWAY);
			}
			else if (haul)
			{
				expected.add(OverlayRows.Kind.MOMENT);
			}
			assertEquals(name, expected, kinds(rows));

			// The line that pairs Pace and Haul shows only the enabled one's value, each in its place.
			OverlayRows.Row elapsed = row(rows, OverlayRows.Kind.ELAPSED);
			if (elapsed != null)
			{
				assertEquals(name, pace ? "21:54 elapsed" : "", elapsed.getLeft());
				assertEquals(name, haul ? "1 superior" : "", elapsed.getRight());
			}

			// Without a runway the moment belongs to Haul alone.
			List<OverlayRows.Row> momentRows = OverlayRows.rows(data(running(), withMoment, null), groups);
			assertEquals(name, haul, row(momentRows, OverlayRows.Kind.MOMENT) != null);
			assertNull(name, row(momentRows, OverlayRows.Kind.RUNWAY));
		}
		assertTrue("every group off: nothing while running",
			OverlayRows.rows(mockup(), OverlayRows.Groups.NONE).isEmpty());
	}

	@Test
	public void everyStoppedStateHasItsLineFirstWhateverTheGroups()
	{
		String[][] expected = {
			{"AWAY", "STATE: Away / Back on your next kill"},
			{"PAUSED", "STATE: Paused / Resume in sidebar"},
			{"WAITING_FOR_NEXT_KILL", "STATE: Waiting for next kill"},
			{"LOGGED_OUT", "STATE: Logged out"},
		};
		for (String[] state : expected)
		{
			SessionTracker.Status status = SessionTracker.Status.valueOf(state[0]);
			OverlayRows.Data stopped = data(task(status, KILLS, ACTIVE, LEFT), snapshot().build(), null);
			for (OverlayRows.Groups groups : new OverlayRows.Groups[]{OverlayRows.Groups.ALL, OverlayRows.Groups.NONE})
			{
				List<OverlayRows.Row> rows = OverlayRows.rows(stopped, groups);
				assertEquals(state[0], state[1], rows.get(0).toString());
				assertEquals(OverlayRows.Tone.ALERT, rows.get(0).getLeftTone());
				assertEquals(OverlayRows.Tone.ALERT, rows.get(0).getRightTone());
				if (groups == OverlayRows.Groups.NONE)
				{
					assertEquals("only the state line", 1, rows.size());
				}
			}
			// Paused keeps its numbers: the bar keeps its fill (DESIGN_PRINCIPLES.md 2.4).
			assertEquals(OverlayRows.Kind.IDENTITY, OverlayRows.rows(stopped, OverlayRows.Groups.ALL).get(1).getKind());
		}
		assertNull(OverlayRows.stateRow(SessionTracker.Status.RUNNING, false));
		assertNull(OverlayRows.stateRow(SessionTracker.Status.NO_TASK, false));
		assertNull(OverlayRows.stateRow(null, false));
	}

	@Test
	public void awayAfterLeavingTheCavesSaysToGoBackIn()
	{
		OverlayRows.Snapshot jad = snapshot().taskName("TzTok-Jad").caveTask(true).build();
		List<OverlayRows.Row> away = OverlayRows.rows(data(task(SessionTracker.Status.AWAY, 0, 600_000, 1), jad,
			null), OverlayRows.Groups.NONE);
		assertEquals("STATE: Away / Back when you go in", away.get(0).toString());
		assertEquals(OverlayRows.CAVE_HINT, away.get(0).getRight());

		// Waiting for the next kill or the first, it starts again when the player goes in too: a short label, so the
		// hint fits beside it (DESIGN_PRINCIPLES.md 2.2).
		List<OverlayRows.Row> next = OverlayRows.rows(data(task(SessionTracker.Status.WAITING_FOR_NEXT_KILL, 0,
			600_000, 1), jad, null), OverlayRows.Groups.ALL);
		assertEquals("STATE: Waiting / Go in to resume", next.get(0).toString());
		assertEquals(OverlayRows.Tone.ALERT, next.get(0).getLeftTone());
		assertEquals(OverlayRows.Tone.ALERT, next.get(0).getRightTone());
		OverlayRows.Snapshot assignment = OverlayRows.Snapshot.builder().taskName("TzKal-Zuk").assigned(1).remaining(1)
			.caveTask(true).build();
		List<OverlayRows.Row> first = OverlayRows.rows(OverlayRows.Data.stateOnly(
			SessionTracker.Status.WAITING_FOR_FIRST_KILL, assignment), OverlayRows.Groups.ALL);
		assertEquals(Arrays.asList("STATE: Waiting / Go in to start", "IDENTITY: TzKal-Zuk", "BAR: 0 / 1"),
			text(first));
		assertEquals("STATE: Waiting for first kill", OverlayRows.rows(OverlayRows.Data.stateOnly(
			SessionTracker.Status.WAITING_FOR_FIRST_KILL, null), OverlayRows.Groups.ALL).get(0).toString());

		// Other states of a cave task keep their own hints.
		List<OverlayRows.Row> paused = OverlayRows.rows(data(task(SessionTracker.Status.PAUSED, 0, 600_000, 1), jad,
			null), OverlayRows.Groups.NONE);
		assertEquals("STATE: Paused / Resume in sidebar", paused.get(0).toString());
		assertEquals("STATE: Logged out", OverlayRows.stateRow(SessionTracker.Status.LOGGED_OUT, true).toString());
		assertEquals("STATE: Ended / See sidebar", OverlayRows.stateRow(SessionTracker.Status.ENDED, true).toString());
		assertEquals("Back when you go in", OverlayRows.stateRow(SessionTracker.Status.AWAY, true).getRight());
		assertEquals("Back on your next kill", OverlayRows.stateRow(SessionTracker.Status.AWAY, false).getRight());
		// Other tasks keep the status's own words.
		assertEquals("STATE: Waiting for next kill",
			OverlayRows.stateRow(SessionTracker.Status.WAITING_FOR_NEXT_KILL, false).toString());
		assertEquals("STATE: Waiting for first kill",
			OverlayRows.stateRow(SessionTracker.Status.WAITING_FOR_FIRST_KILL, false).toString());
	}

	/**
	 * DESIGN_PRINCIPLES.md 2.2, "Fixed copy fits": at the default width every state line (any task, the caves too), every
	 * superior moment line and the runway's longest examples fit whole in RuneScape Plain 16, the default overlay font
	 * (Bold for a critical runway), so none is cut short with the ellipsis.
	 */
	@Test
	public void theFixedCopyFitsTheDefaultWidthInTheRuneScapeFonts()
	{
		BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			FontMetrics plain = graphics.getFontMetrics(FontManager.getRunescapeFont());
			FontMetrics bold = graphics.getFontMetrics(FontManager.getRunescapeBoldFont());
			// 152 px of content inside the 160 px overlay's borders.
			int width = SlayerLedgerOverlay.DEFAULT_WIDTH - 2 * ComponentConstants.STANDARD_BORDER;
			assertEquals(152, width);

			List<String> stateLines = new ArrayList<>();
			for (SessionTracker.Status status : SessionTracker.Status.values())
			{
				for (boolean caveTask : new boolean[]{false, true})
				{
					OverlayRows.Row state = OverlayRows.stateRow(status, caveTask);
					if (state != null)
					{
						assertFitsWhole(state, width, plain, bold);
						stateLines.add(state.toString());
					}
				}
			}
			// The cave lines: 41 + 3 + 75, 41 + 3 + 88 and 29 + 3 + 111 px. "Waiting for next kill" with "Back when you go
			// in" didn't fit: 109 + 3 + 111 = 223 px.
			assertTrue(stateLines.contains("STATE: Waiting / Go in to start"));
			assertTrue(stateLines.contains("STATE: Waiting / Go in to resume"));
			assertTrue(stateLines.contains("STATE: Away / Back when you go in"));
			assertTrue(plain.stringWidth("Waiting for next kill") + plain.stringWidth(" ")
				+ plain.stringWidth(OverlayRows.CAVE_HINT) > width);

			// The moment line for a session's superiors up to the 99th; the widest, "Superior spawned (29th).", is 142 px.
			// They rotate between at most 3 variants (DESIGN_PRINCIPLES.md 5.2), and every variant fits.
			Set<String> variants = new HashSet<>();
			for (int number = 1; number <= 99; number++)
			{
				String moment = OverlayRows.momentText(number);
				assertFitsWhole(OverlayRows.Row.line(OverlayRows.Kind.MOMENT, moment, null), width, plain, bold);
				assertTrue(moment, plain.stringWidth(moment) <= width);
				variants.add(moment.replaceAll("\\(\\d+(st|nd|rd|th)\\)", "(N)"));
			}
			assertEquals(new HashSet<>(Arrays.asList("Superior spawned.", "Another superior (N).",
				"Superior spawned (N).")), variants);
			// The runway beside its icon: the longest name in a warning and critical (in Bold).
			assertFitsWhole(OverlayRows.runwayRow(new SupplyRunway.Warning(SupplyRunway.Kind.CANNONBALLS, 2, 90)), width,
				plain, bold);
			assertFitsWhole(OverlayRows.runwayRow(new SupplyRunway.Warning(SupplyRunway.Kind.CANNONBALLS, 2, 9)), width,
				plain, bold);
		}
		finally
		{
			graphics.dispose();
		}
	}

	/** {@code row} fitted to {@code width} keeps its text whole. */
	private static void assertFitsWhole(OverlayRows.Row row, int width, FontMetrics plain, FontMetrics bold)
	{
		OverlayRows.Row fitted = OverlayRows.fitted(row, width, plain, bold);
		assertEquals(row + " at " + width + " px", row.getLeft(), fitted.getLeft());
		assertEquals(row + " at " + width + " px", row.getRight(), fitted.getRight());
	}

	@Test
	public void withoutASessionTheStateIdentityAndBarShow()
	{
		OverlayRows.Snapshot assignment = OverlayRows.Snapshot.builder().taskName("Dust devils").iconItemId(4)
			.assigned(201).remaining(201).build();

		List<OverlayRows.Row> waiting = OverlayRows.rows(
			OverlayRows.Data.stateOnly(SessionTracker.Status.WAITING_FOR_FIRST_KILL, assignment), OverlayRows.Groups.ALL);
		assertEquals(Arrays.asList("STATE: Waiting for first kill", "IDENTITY: Dust devils", "BAR: 0 / 201"),
			text(waiting));

		List<OverlayRows.Row> ended = OverlayRows.rows(
			OverlayRows.Data.stateOnly(SessionTracker.Status.ENDED, OverlayRows.Snapshot.builder().taskName("Dust devils")
				.assigned(201).remaining(150).build()),
			OverlayRows.Groups.ALL);
		assertEquals(Arrays.asList("STATE: Ended / See sidebar", "IDENTITY: Dust devils", "BAR: 51 / 201"),
			text(ended));

		// The groups don't hide the state; before the first tick's snapshot it is alone.
		assertEquals(Collections.singletonList("STATE: Ended / See sidebar"), text(OverlayRows.rows(
			OverlayRows.Data.stateOnly(SessionTracker.Status.ENDED, assignment), OverlayRows.Groups.NONE)));
		assertEquals(Collections.singletonList("STATE: Waiting for first kill"), text(OverlayRows.rows(
			OverlayRows.Data.stateOnly(SessionTracker.Status.WAITING_FOR_FIRST_KILL, null), OverlayRows.Groups.ALL)));
		assertTrue(OverlayRows.rows(null, OverlayRows.Groups.ALL).isEmpty());
	}

	@Test
	public void unknownKillsLeftHaveNoBarAndShowTheKillsDone()
	{
		OverlayRows.Data data = data(task(SessionTracker.Status.RUNNING, KILLS, ACTIVE,
			SessionTracker.LiveTask.UNKNOWN_REMAINING), snapshot().assigned(OverlayRows.UNKNOWN).build(), null);

		List<OverlayRows.Row> rows = OverlayRows.rows(data, OverlayRows.Groups.ALL);

		assertNull("no bar", row(rows, OverlayRows.Kind.BAR));
		assertEquals("73 killed", row(rows, OverlayRows.Kind.PROGRESS).getLeft());
		assertEquals("? left", row(rows, OverlayRows.Kind.PROGRESS).getRight());
		assertEquals(OverlayRows.UNKNOWN_REMAINING + " left", row(rows, OverlayRows.Kind.PROGRESS).getRight());
		assertFalse("no key number to stress", row(rows, OverlayRows.Kind.PROGRESS).isBold());
	}

	@Test
	public void anUnknownAssignedSizeHasNoBarButTheKillsLeft()
	{
		List<OverlayRows.Row> rows = OverlayRows.rows(data(running(), snapshot().assigned(OverlayRows.UNKNOWN).build(),
			null), OverlayRows.Groups.ALL);

		assertNull(row(rows, OverlayRows.Kind.BAR));
		assertEquals("PROGRESS: 128 left / ~38 min", row(rows, OverlayRows.Kind.PROGRESS).toString());

		// Before the first snapshot: the name alone, from the session's task; no bar, no tags.
		List<OverlayRows.Row> early = OverlayRows.rows(data(running(), null, null), OverlayRows.Groups.ALL);
		assertEquals(Arrays.asList("IDENTITY: Dust devils", "PROGRESS: 128 left / ~38 min",
			"PACE: 0:18/kill / 200/h", "ELAPSED: 21:54 elapsed"), text(early));
	}

	@Test
	public void timeLeftFollowsThePaceThenTheHistory()
	{
		// 1 kill in 30 s: too little for the pace, so the history's 45 s a kill for the 40 left.
		OverlayRows.Data early = new OverlayRows.Data(task(SessionTracker.Status.RUNNING, 1, 30_000, 40), null, null,
			45_000L);
		assertEquals("~30 min", row(OverlayRows.rows(early, OverlayRows.Groups.ALL), OverlayRows.Kind.PROGRESS)
			.getRight());
		// No history: unknown, and no pace yet.
		List<OverlayRows.Row> unknown = OverlayRows.rows(data(task(SessionTracker.Status.RUNNING, 1, 30_000, 40), null,
			null), OverlayRows.Groups.ALL);
		assertEquals(LedgerFormat.UNKNOWN, row(unknown, OverlayRows.Kind.PROGRESS).getRight());
		assertNull("the pace shows once known", row(unknown, OverlayRows.Kind.PACE));
		// Over an hour.
		assertEquals("~1 h 5 min", LedgerFormat.timeLeft(60_000 * 10, 10, 65, null));
		assertEquals(LedgerFormat.NONE, LedgerFormat.timeLeft(60_000, 10, 0, null));
	}

	@Test
	public void bothRunwayLevels()
	{
		OverlayRows.Snapshot warning = snapshot()
			.runway(new SupplyRunway.Warning(SupplyRunway.Kind.PRAYER, 2434, 40)).build();
		OverlayRows.Row row = row(OverlayRows.rows(data(running(), warning, null), OverlayRows.Groups.ALL),
			OverlayRows.Kind.RUNWAY);
		assertEquals("Prayer: ~40 kills", row.getLeft());
		assertEquals(OverlayRows.Tone.ALERT, row.getLeftTone());
		assertEquals(Integer.valueOf(2434), row.getIconItemId());
		assertFalse(row.isBold());

		OverlayRows.Snapshot critical = snapshot()
			.runway(new SupplyRunway.Warning(SupplyRunway.Kind.CANNONBALLS, 2, 9)).build();
		OverlayRows.Row criticalRow = row(OverlayRows.rows(data(running(), critical, null), OverlayRows.Groups.ALL),
			OverlayRows.Kind.RUNWAY);
		assertEquals("Cannonballs: 9 left", criticalRow.getLeft());
		assertEquals(OverlayRows.Tone.ALERT, criticalRow.getLeftTone());
		assertTrue("critical is the overlay's other Bold text", criticalRow.isBold());

		assertEquals("Food: ~10 kills", OverlayRows.runwayText(new SupplyRunway.Warning(SupplyRunway.Kind.FOOD, 1, 10),
			true));
		assertEquals("Runes: ~1,200", OverlayRows.runwayText(new SupplyRunway.Warning(SupplyRunway.Kind.RUNES, 1,
			1_200), false));
		assertEquals("Prayer: 0 left", OverlayRows.runwayText(new SupplyRunway.Warning(SupplyRunway.Kind.PRAYER, 1, 0),
			true));

		// Supplies that outlast the task leave no runway in the snapshot, so no row.
		assertNull(row(OverlayRows.rows(mockup(), OverlayRows.Groups.ALL), OverlayRows.Kind.RUNWAY));
	}

	@Test
	public void theRunwayWinsOverTheMoment()
	{
		OverlayRows.Snapshot both = snapshot().runway(new SupplyRunway.Warning(SupplyRunway.Kind.PRAYER, 2434, 40))
			.moment("Another superior (2nd).").build();
		List<OverlayRows.Row> rows = OverlayRows.rows(data(running(), both, values(1, 0, 0)), OverlayRows.Groups.ALL);

		assertEquals(OverlayRows.Kind.RUNWAY, rows.get(rows.size() - 1).getKind());
		assertNull("a moment never hides a warning", row(rows, OverlayRows.Kind.MOMENT));
		// At most 8 lines and the bar: the identity (two), four two-value lines and one runway or moment.
		assertEquals(7, rows.size());

		List<OverlayRows.Row> moment = OverlayRows.rows(data(running(), snapshot().moment("Another superior (2nd).")
			.build(), null), OverlayRows.Groups.ALL);
		assertEquals("MOMENT: Another superior (2nd).", moment.get(moment.size() - 1).toString());
		assertEquals(OverlayRows.Tone.VALUE, moment.get(moment.size() - 1).getLeftTone());
	}

	@Test
	public void theMomentLines()
	{
		assertEquals("Superior spawned.", OverlayRows.momentText(1));
		assertEquals("Another superior (2nd).", OverlayRows.momentText(2));
		assertEquals("Superior spawned (3rd).", OverlayRows.momentText(3));
		assertEquals("Superior spawned (11th).", OverlayRows.momentText(11));
		assertEquals("Another superior (12th).", OverlayRows.momentText(12));
		// The rotation is fixed by the number, so the same superior always gets the same line.
		assertEquals("Another superior (4th).", OverlayRows.momentText(4));
		assertEquals("Superior spawned (5th).", OverlayRows.momentText(5));
		assertEquals("21st", OverlayRows.ordinal(21));
		assertEquals("22nd", OverlayRows.ordinal(22));
		assertEquals("113th", OverlayRows.ordinal(113));
		assertEquals("101st", OverlayRows.ordinal(101));
	}

	@Test
	public void theNameShowsTheVariantAndCannon()
	{
		assertEquals("Dust devils", OverlayRows.taskName("Dust devils", 42, null, null));
		assertEquals("Dust devils (cannon)", OverlayRows.taskName("Dust devils", 42,
			TaskSelection.ANY.withCannon(true), null));
		// Without a snapshot the session's own task name.
		assertEquals("Dust devils", data(running(), null, null).getTaskName());
		assertEquals("Wyrms (cannon)", data(running(), snapshot().taskName("Wyrms (cannon)").build(), null)
			.getTaskName());
	}

	@Test
	public void historicalPaceBelongsToItsSessionOnly()
	{
		OverlayRows.HistoricalPace pace = new OverlayRows.HistoricalPace("session", 45_000L,
			Collections.singletonMap(2434, 0.5));

		assertEquals(Long.valueOf(45_000L), pace.millisPerKillFor(running()));
		assertNull(pace.millisPerKillFor(new SessionTracker.LiveTask(SessionTracker.Status.RUNNING, "other",
			"Wyrms", 42, 1, 30_000, 40)));
		assertNull(pace.millisPerKillFor(null));
		assertEquals(Collections.singletonMap(2434, 0.5), pace.getSuppliesPerKill());
		assertTrue(new OverlayRows.HistoricalPace("session", 1L, null).getSuppliesPerKill().isEmpty());
		assertFalse(pace.equals(new OverlayRows.HistoricalPace("session", 45_000L, null)));
		assertEquals(pace, new OverlayRows.HistoricalPace("session", 45_000L, Collections.singletonMap(2434, 0.5)));
	}

	// ---- Fitting to the width ----

	/** A font whose every character is {@code width} pixels wide, the ellipsis too. */
	private static final class FixedMetrics extends FontMetrics
	{
		private final int width;

		FixedMetrics(int width)
		{
			super(new Font(Font.MONOSPACED, Font.PLAIN, 12));
			this.width = width;
		}

		@Override
		public int charWidth(char ch)
		{
			return width;
		}

		@Override
		public int stringWidth(String text)
		{
			return text.length() * width;
		}

		@Override
		public int getHeight()
		{
			return 16;
		}
	}

	@Test
	public void longNamesAreCutWithAnEllipsisNeverWrapped()
	{
		FontMetrics narrow = new FixedMetrics(10);
		OverlayRows.Row identity = OverlayRows.identityRow("Thermonuclear smoke devil", 4, "Smoke Dungeon",
			Arrays.asList(SetupTagIcons.Icon.CANNON, SetupTagIcons.Icon.RANGED));

		// 152 px of content: 112 beside the icon, so 11 characters with the ellipsis.
		OverlayRows.Row fitted = OverlayRows.fitted(identity, 152, narrow, null);
		assertEquals("Thermonucl…", fitted.getLeft());
		assertTrue(narrow.stringWidth(fitted.getLeft()) <= 152 - OverlayRows.ICON_WIDTH - OverlayRows.ICON_GAP);
		// The spot beside two 16 px tags: 112 - 34 - 3 = 75 px, 7 characters.
		assertEquals("Smoke…", fitted.getSecond());
		assertEquals("Smoke…", OverlayRows.truncate("Smoke Dungeon", 70, narrow));
		assertEquals("its tags stay", identity.getTags(), fitted.getTags());

		// A name that fits is left alone.
		assertEquals("Dust devils", OverlayRows.fitted(OverlayRows.identityRow("Dust devils", 4, "Catacombs",
			Collections.emptyList()), 152, narrow, null).getLeft());
		// Without an icon the name takes the full width.
		assertEquals("Thermonuclear s…", OverlayRows.fitted(OverlayRows.identityRow("Thermonuclear smoke devil", null,
			null, null), 160, narrow, null).getLeft());
	}

	@Test
	public void theStateLineKeepsItsLabelAndCutsTheHint()
	{
		FontMetrics narrow = new FixedMetrics(10);
		OverlayRows.Row state = OverlayRows.stateRow(SessionTracker.Status.AWAY, false);

		// "Away" (40) and a space (10) leave 100 px: the hint is cut to 9 characters and the ellipsis.
		OverlayRows.Row fitted = OverlayRows.fitted(state, 150, narrow, null);
		assertEquals("Away", fitted.getLeft());
		assertEquals("Back on y…", fitted.getRight());
		assertTrue(narrow.stringWidth(fitted.getLeft() + " " + fitted.getRight()) <= 150);

		// No useful room on the right: the left alone, cut.
		String[] tight = OverlayRows.fit("Waiting for next kill", "x", 100, narrow, narrow);
		assertEquals("Waiting f…", tight[0]);
		assertEquals("", tight[1]);
		// Both fit: unchanged.
		String[] both = OverlayRows.fit("Paused", "Resume in sidebar", 240, narrow, narrow);
		assertEquals("Paused", both[0]);
		assertEquals("Resume in sidebar", both[1]);
		// The bar's label is never cut.
		assertEquals("73 / 201", OverlayRows.fitted(OverlayRows.barRow(73, 201), 20, narrow, null).getLeft());
		// Nothing fits, not even the ellipsis.
		assertEquals("", OverlayRows.truncate("Away", 5, narrow));
		assertEquals(OverlayRows.ELLIPSIS, OverlayRows.truncate("Away", 10, narrow));
	}

	/** The progress line for 1,128 kills left at 16.6 seconds a kill: {@code 1,128 left} / {@code ~5 h 12 min}. */
	private static OverlayRows.Row longTask()
	{
		return OverlayRows.progressRow(task(SessionTracker.Status.RUNNING, 100, 1_660_000L, 1_128), null);
	}

	/** {@code row} fitted to {@code width}, as {@code left / right}. */
	private static String fitted(OverlayRows.Row row, int width, FontMetrics plain, FontMetrics bold)
	{
		OverlayRows.Row fitted = OverlayRows.fitted(row, width, plain, bold);
		return fitted.getLeft() + " / " + fitted.getRight();
	}

	@Test
	public void valueLinesDropUnitWordsThenWholeValuesNeverDigits()
	{
		FontMetrics narrow = new FixedMetrics(10);
		OverlayRows.Row progress = longTask();
		assertEquals("PROGRESS: 1,128 left / ~5 h 12 min", progress.toString());

		// 100 + 10 + 110 px: whole. Then the time left's unit word, then the kills left's, then whole values.
		assertEquals("1,128 left / ~5 h 12 min", fitted(progress, 220, narrow, narrow));
		assertEquals("1,128 left / ~5 h 12", fitted(progress, 219, narrow, narrow));
		assertEquals("1,128 left / ~5 h 12", fitted(progress, 180, narrow, narrow));
		assertEquals("1,128 / ~5 h 12", fitted(progress, 179, narrow, narrow));
		assertEquals("1,128 / ~5 h 12", fitted(progress, 130, narrow, narrow));
		assertEquals("1,128 left / ", fitted(progress, 129, narrow, narrow));
		assertEquals("1,128 / ", fitted(progress, 99, narrow, narrow));
		assertEquals(" / ", fitted(progress, 49, narrow, narrow));

		// The pace: "/kill" goes, "/h" stays with its number; then the kills per hour go whole.
		OverlayRows.Row pace = row(OverlayRows.rows(mockup(), OverlayRows.Groups.ALL), OverlayRows.Kind.PACE);
		assertEquals("0:18/kill / 200/h", fitted(pace, 150, narrow, narrow));
		assertEquals("0:18 / 200/h", fitted(pace, 149, narrow, narrow));
		assertEquals("0:18/kill / ", fitted(pace, 99, narrow, narrow));
		assertEquals("0:18 / ", fitted(pace, 89, narrow, narrow));

		// The profit and XP have no unit word to drop: the XP goes whole.
		OverlayRows.Row haul = row(OverlayRows.rows(mockup(), OverlayRows.Groups.ALL), OverlayRows.Kind.HAUL);
		assertEquals("+212K / 58.6K XP", fitted(haul, 140, narrow, narrow));
		assertEquals("+212K / ", fitted(haul, 139, narrow, narrow));
		assertEquals(" / ", fitted(haul, 49, narrow, narrow));

		// The kills done with the kills left unknown: "? left" has no unit word, "73 killed" has one.
		OverlayRows.Row unknown = OverlayRows.progressRow(task(SessionTracker.Status.RUNNING, KILLS, ACTIVE,
			SessionTracker.LiveTask.UNKNOWN_REMAINING), null);
		assertEquals("73 killed / ? left", fitted(unknown, 160, narrow, narrow));
		assertEquals("73 / ? left", fitted(unknown, 159, narrow, narrow));
		assertEquals("73 / ? left", fitted(unknown, 90, narrow, narrow));
		assertEquals("73 / ", fitted(unknown, 89, narrow, narrow));

		// No value line ever shows the ellipsis, whatever the width.
		for (OverlayRows.Row row : Arrays.asList(progress, pace, haul, unknown))
		{
			for (int width = 0; width <= 240; width++)
			{
				String text = fitted(row, width, narrow, narrow);
				assertFalse(row + " at " + width + ": " + text, text.contains(OverlayRows.ELLIPSIS));
			}
		}
		assertEquals(" min", OverlayRows.unitWord(OverlayRows.Kind.PROGRESS, false, "~38 min"));
		assertEquals("", OverlayRows.unitWord(OverlayRows.Kind.PROGRESS, false, "~2 h"));
		assertEquals("", OverlayRows.unitWord(OverlayRows.Kind.HAUL, false, "58.6K XP"));
		assertEquals("", OverlayRows.unitWord(OverlayRows.Kind.STATE, true, "Away"));
	}

	@Test
	public void at120PxInTheRuneScapeFontsNoValueIsCut()
	{
		BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			FontMetrics plain = graphics.getFontMetrics(FontManager.getRunescapeFont());
			FontMetrics bold = graphics.getFontMetrics(FontManager.getRunescapeBoldFont());
			// The overlay resized to 120 px: 112 px of content inside its borders.
			int width = 120 - 2 * ComponentConstants.STANDARD_BORDER;

			// The time left keeps its digits: before this fix it was cut to "~5 h 1…".
			assertEquals("1,128 left / ~5 h 12", fitted(longTask(), width, plain, bold));
			OverlayRows.Row pace = OverlayRows.paceRow(task(SessionTracker.Status.RUNNING, 3_000, 54_000_000L, 1));
			assertEquals("PACE: 0:18/kill / 200/h", pace.toString());
			assertEquals("0:18/kill / 200/h", fitted(pace, width, plain, bold));
		}
		finally
		{
			graphics.dispose();
		}
	}

	@Test
	public void theKeyNumberIsMeasuredInBold()
	{
		FontMetrics plain = new FixedMetrics(6);
		FontMetrics bold = new FixedMetrics(10);
		OverlayRows.Row progress = row(OverlayRows.rows(mockup(), OverlayRows.Groups.ALL), OverlayRows.Kind.PROGRESS);

		// "128 left" is 80 px in Bold, a space 6 and "~38 min" 42: 128 fits 128, not 127.
		assertEquals("~38 min", OverlayRows.fitted(progress, 128, plain, bold).getRight());
		// Then the unit word goes: "~38" is 18 px, so it fits from 104.
		assertEquals("~38", OverlayRows.fitted(progress, 127, plain, bold).getRight());
		assertEquals("~38", OverlayRows.fitted(progress, 104, plain, bold).getRight());
		// Then the key number's: "128" is 30 px in Bold.
		assertEquals("128 / ~38", fitted(progress, 103, plain, bold));
		assertEquals("128 / ~38", fitted(progress, 54, plain, bold));
		assertEquals("128 / ", fitted(progress, 53, plain, bold));
		// In another font no Bold: measured in it.
		assertEquals("~38 min", OverlayRows.fitted(progress, 96, plain, null).getRight());
	}

	@Test
	public void theRunwayDropsKillsFirstThenCuts()
	{
		FontMetrics narrow = new FixedMetrics(10);
		OverlayRows.Row runway = OverlayRows.runwayRow(new SupplyRunway.Warning(SupplyRunway.Kind.CANNONBALLS, 2, 90));
		int beside = OverlayRows.SMALL_ICON_WIDTH + OverlayRows.TEXT_ICON_GAP;

		assertEquals("Cannonballs: ~90 kills", OverlayRows.fitted(runway, beside + 220, narrow, null).getLeft());
		assertEquals("Cannonballs: ~90", OverlayRows.fitted(runway, beside + 219, narrow, null).getLeft());
		assertEquals("Cannonballs: ~90", OverlayRows.fitted(runway, beside + 160, narrow, null).getLeft());
		assertEquals("Cannonballs: ~…", OverlayRows.fitted(runway, beside + 159, narrow, null).getLeft());

		// Critical is measured in Bold.
		OverlayRows.Row critical = OverlayRows.runwayRow(new SupplyRunway.Warning(SupplyRunway.Kind.PRAYER, 2434, 6));
		assertEquals("Prayer: 6 left", OverlayRows.fitted(critical, beside + 140, new FixedMetrics(6),
			narrow).getLeft());
		assertEquals("Prayer: 6 l…", OverlayRows.fitted(critical, beside + 120, new FixedMetrics(6), narrow)
			.getLeft());
	}

	@Test
	public void theElapsedLineDropsItsWordBeforeCuttingTheSuperiors()
	{
		FontMetrics narrow = new FixedMetrics(10);
		OverlayRows.Row elapsed = OverlayRows.Row.line(OverlayRows.Kind.ELAPSED, "1:03:31 elapsed", "2 superiors");

		// 150 + 10 + 110 = 270 px: whole.
		OverlayRows.Row whole = OverlayRows.fitted(elapsed, 270, narrow, null);
		assertEquals("1:03:31 elapsed", whole.getLeft());
		assertEquals("2 superiors", whole.getRight());
		// One pixel less: the clock alone, the superiors whole.
		OverlayRows.Row shorter = OverlayRows.fitted(elapsed, 269, narrow, null);
		assertEquals("1:03:31", shorter.getLeft());
		assertEquals("2 superiors", shorter.getRight());
		assertEquals("1:03:31 / 2 superiors", fitted(elapsed, 190, narrow, null));
		// Then the superiors go whole, never cut: the elapsed time keeps its word while it fits.
		assertEquals("1:03:31 elapsed / ", fitted(elapsed, 189, narrow, null));
		assertEquals("1:03:31 / ", fitted(elapsed, 149, narrow, null));
		// Without the superiors (Haul off) the word stays while it fits.
		assertEquals("1:03:31 elapsed", OverlayRows.fitted(OverlayRows.Row.line(OverlayRows.Kind.ELAPSED,
			"1:03:31 elapsed", null), 150, narrow, null).getLeft());
		// Without the elapsed time (Pace off) the superiors go whole.
		OverlayRows.Row superiors = OverlayRows.Row.line(OverlayRows.Kind.ELAPSED, null, "2 superiors");
		assertEquals(" / 2 superiors", fitted(superiors, 110, narrow, null));
		assertEquals(" / ", fitted(superiors, 109, narrow, null));
		// Only this line's value drops " elapsed".
		assertEquals(" / ", fitted(OverlayRows.Row.line(OverlayRows.Kind.HAUL, "1:03:31 elapsed", null), 110,
			narrow, null));
	}

	@Test
	public void theMomentIsCutToo()
	{
		OverlayRows.Row moment = OverlayRows.Row.line(OverlayRows.Kind.MOMENT, "Superior spawned (12th).", null);
		assertEquals("Superior spawned (12th).", OverlayRows.fitted(moment, 240, new FixedMetrics(10), null)
			.getLeft());
		assertEquals("Superior s…", OverlayRows.fitted(moment, 110, new FixedMetrics(10), null).getLeft());
	}
}
