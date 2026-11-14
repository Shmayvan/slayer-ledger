package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/** The number rules of docs/DESIGN_PRINCIPLES.md 5.5, with the doc's own examples ({@link LedgerFormat}). */
public class LedgerFormatTest
{
	@Test
	public void gpAndXpInFullUnderTenThousandThenThreeSignificantFigures()
	{
		assertEquals("950", LedgerFormat.amount(950));
		assertEquals("1,235", LedgerFormat.amount(1_235));
		assertEquals("9,999", LedgerFormat.amount(9_999));
		assertEquals("12.4K", LedgerFormat.amount(12_400));
		assertEquals("477K", LedgerFormat.amount(477_000));
		assertEquals("58.6K", LedgerFormat.amount(58_600));
		assertEquals("1.24M", LedgerFormat.amount(1_240_000));
		assertEquals("2.1B", LedgerFormat.amount(2_100_000_000L));
		assertEquals("no trailing zeros", "10K", LedgerFormat.amount(10_000));
		// RuneLite's stack format keeps the zeros it cuts to ("10.0K", "1.00M", "58.0K"); the sidebar drops them.
		assertEquals("10K", LedgerFormat.amount(10_050));
		assertEquals("1M", LedgerFormat.amount(1_005_000));
		assertEquals("58K", LedgerFormat.amount(58_049));
		assertEquals("1.5M", LedgerFormat.amount(1_500_000));
		assertEquals("1.05M", LedgerFormat.amount(1_050_000));
		assertEquals("100K", LedgerFormat.amount(100_000));
		assertEquals("-10K", LedgerFormat.amount(-10_050));
		assertEquals("+1M", LedgerFormat.signed(1_005_000L, false));
		assertEquals("58K", LedgerFormat.perHour(58_049.4));
		assertEquals("~477K", LedgerFormat.amount(477_000L, true));
		assertEquals(LedgerFormat.NONE, LedgerFormat.amount(null, false));
	}

	@Test
	public void profitIsAlwaysSignedExceptZeroWithTheTildeFirst()
	{
		assertEquals("+212K", LedgerFormat.signed(212_431L, false));
		assertEquals("-38K", LedgerFormat.signed(-38_000L, false));
		assertEquals("0", LedgerFormat.signed(0L, false));
		assertEquals("~+212K", LedgerFormat.signed(212_431L, true));
		assertEquals("~-100", LedgerFormat.signed(-100L, true));
		assertEquals("the hyphen, never the minus sign the fonts lack", '-', LedgerFormat.signed(-1L, false).charAt(0));
		assertEquals(LedgerFormat.NONE, LedgerFormat.signed(null, false));
	}

	@Test
	public void killsHaveCommasAndProgressHasSpaces()
	{
		assertEquals("1,412", LedgerFormat.kills(1_412));
		assertEquals("73 / 201", LedgerFormat.progress(73, 201));
		assertEquals("1,000 / 1,200", LedgerFormat.progress(1_000, 1_200));
	}

	@Test
	public void theRunningClockIsMinutesAndSecondsUnderAnHourThenHours()
	{
		assertEquals("0:00", LedgerFormat.clock(0));
		assertEquals("0:00", LedgerFormat.clock(-5_000));
		assertEquals("21:54", LedgerFormat.clock((21 * 60 + 54) * 1000L));
		assertEquals("59:59", LedgerFormat.clock((59 * 60 + 59) * 1000L + 999));
		assertEquals("1:03:31", LedgerFormat.clock((3600 + 3 * 60 + 31) * 1000L));
	}

	@Test
	public void paceIsMinutesAndSecondsPerKillAndRatesAreRounded()
	{
		assertEquals("0:18/kill", LedgerFormat.pace(18_000));
		assertEquals("half up", "0:19/kill", LedgerFormat.pace(18_500));
		assertEquals("1:05/kill", LedgerFormat.pace(65_000));
		assertEquals("200", LedgerFormat.perHour(200.4));
		assertEquals("120K", LedgerFormat.perHour(120_000));
	}

	/** A completed duration in text (5.5), as the Task history rows show a task's active time. */
	@Test
	public void aCompletedDurationIsInMinutesThenHoursAndMinutes()
	{
		assertEquals("52 min", LedgerFormat.duration((52 * 60 + 10) * 1000L));
		assertEquals("half up", "53 min", LedgerFormat.duration((52 * 60 + 30) * 1000L));
		assertEquals("1 h 1 min", LedgerFormat.duration(61 * 60_000L));
		assertEquals("2 h", LedgerFormat.duration(120 * 60_000L));
		assertEquals("59 min and a half rounds up to the hour", "1 h", LedgerFormat.duration(59 * 60_000L + 30_000));
		assertEquals("<1 min", LedgerFormat.duration(29_999));
		assertEquals("1 min", LedgerFormat.duration(30_000));
		assertEquals("0 min", LedgerFormat.duration(0));
		assertEquals("0 min", LedgerFormat.duration(-5_000));
		assertEquals("54 min", LedgerFormat.minutes(54));
		assertEquals("1 h 6 min", LedgerFormat.minutes(66));
	}
}
