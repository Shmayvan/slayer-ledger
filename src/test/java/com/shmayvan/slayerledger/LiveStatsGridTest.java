package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * The live card's stats grid rules ({@link LiveSessionSection#leftColumnWidth},
 * {@link LiveSessionSection#statTooltip}): how the card's width is split between the two columns, and each stat's
 * tooltip with its whole value first. {@link HarnessLiveCardTest} lays the grid out at the sidebar's width.
 */
public class LiveStatsGridTest
{
	/** The room for both columns inside the card at the sidebar's width: 199 px, less the 6 px between them. */
	private static final int ROOM = 193;

	@Test
	public void bothSteadyWidthsFitSoTheRoomLeftOverIsShared()
	{
		// Elapsed's clock at its widest under 10 hours (87) and the time left's (99): 7 px over, the odd one right.
		assertEquals(90, LiveSessionSection.leftColumnWidth(ROOM, 87, 99, 80, 98));
		assertEquals("exactly full", 89, LiveSessionSection.leftColumnWidth(ROOM, 89, 104, 89, 104));
	}

	@Test
	public void whenTheSteadyWidthsDontFitTheLeftGivesWhatTheRightNeeds()
	{
		// "~10h 00m" (105) beside a short clock: the left keeps only what the right leaves it.
		assertEquals(88, LiveSessionSection.leftColumnWidth(ROOM, 93, 105, 80, 105));
		// A long left value beside a short right one: the left keeps its steady width.
		assertEquals(93, LiveSessionSection.leftColumnWidth(ROOM, 93, 105, 93, 66));
	}

	@Test
	public void whenTheValuesDontFitEachColumnGetsItsShare()
	{
		assertEquals(93 * ROOM / 198, LiveSessionSection.leftColumnWidth(ROOM, 93, 105, 93, 105));
		assertEquals("no room yet", 0, LiveSessionSection.leftColumnWidth(-6, 87, 99, 80, 98));
	}

	@Test
	public void theTooltipStartsWithTheLabelAndTheWholeValue()
	{
		assertEquals("Kills left: 1,985", LiveSessionSection.statTooltip("Kills left", "1,985", null));
		assertEquals("<html>Pace: ?<br>Shown after 3 kills &amp; 1 minute.</html>",
			LiveSessionSection.statTooltip("Pace", "?", "Shown after 3 kills & 1 minute."));
		assertEquals("<html>Time left: ~&lt;1m<br>Kills per hour: 180<br>Assumes the same pace.</html>",
			LiveSessionSection.statTooltip("Time left", "~<1m",
				"<html>Kills per hour: 180<br>Assumes the same pace.</html>"));
	}
}
