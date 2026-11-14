package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * When a long stretch of uncounted supply ticks is due for a debug line.
 */
public class SkippedSupplyTicksTest
{
	private final SkippedSupplyTicks skipped = new SkippedSupplyTicks();

	/** The stretch lengths at which a report is due, over {@code ticks} skipped ticks in a row. */
	private List<Integer> reportsOver(int ticks)
	{
		final List<Integer> reports = new ArrayList<>();
		for (int i = 0; i < ticks; i++)
		{
			if (skipped.skipped())
			{
				reports.add(skipped.length());
			}
		}
		return reports;
	}

	@Test
	public void aShortStretchIsNeverReported()
	{
		assertTrue(reportsOver(SkippedSupplyTicks.FIRST_REPORT_TICKS - 1).isEmpty());
		assertEquals(SkippedSupplyTicks.FIRST_REPORT_TICKS - 1, skipped.length());
	}

	@Test
	public void aLongStretchIsReportedAfterAMinuteThenEveryTenMinutes()
	{
		assertEquals(Arrays.asList(100, 1_100, 2_100), reportsOver(2_500));
	}

	@Test
	public void resetEndsTheStretch()
	{
		reportsOver(SkippedSupplyTicks.FIRST_REPORT_TICKS - 1);
		skipped.reset();
		assertEquals(0, skipped.length());
		assertFalse(skipped.skipped());
		assertEquals("a new stretch starts from one", Arrays.asList(100), reportsOver(99));
	}
}
