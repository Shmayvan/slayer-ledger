package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * The panel's live session lines as wired in the plugin ({@link PluginHarness}): the time-left line, live values
 * that belong to another session, and the valuation throttle when pricing fails. {@link TaskTimeEstimateTest} and
 * {@link SessionValuationTest} cover the calculations.
 */
public class HarnessLiveValuesTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	/** A valuation that throws (an unexpected failure) until {@link #fail} is cleared, counting its calls. */
	private static final class FailingValuation extends SessionValuation
	{
		volatile boolean fail = true;
		int attempts;

		@Override
		Values update(String sessionId, ItemTally loot, ItemTally supplies, Map<Integer, Integer> doses,
			LootPriceMode mode, HiddenLoot hidden, ItemValuation.PriceLookup lookup, int tick)
		{
			attempts++;
			if (fail)
			{
				throw new IllegalStateException("valuation failure (set by the test)");
			}
			return super.update(sessionId, loot, supplies, doses, mode, hidden, lookup, tick);
		}
	}

	/** Stands {@code valuation} in for the plugin's live session valuation. */
	private void replaceSessionValuation(SessionValuation valuation)
	{
		harness.plugin.setSessionValuation(valuation);
	}

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.BONES, "Bones", 100, 1);
		harness.startUp();
		harness.setTask(42, "Abyssal demons", 10, 10);
		harness.loginAndSettle(ACCOUNT);
	}

	/** Refreshes the panel from the tracker (as its timer does) and returns whether the time-left line is shown. */
	private boolean timeLeftShown()
	{
		final boolean[] shown = new boolean[1];
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			shown[0] = harness.ui().isTimeLeftShown();
		});
		return shown[0];
	}

	private String timeLeftText()
	{
		final String[] text = new String[1];
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			text[0] = harness.ui().getTimeLeftText();
		});
		return text[0];
	}

	private boolean valuesShown()
	{
		final boolean[] shown = new boolean[1];
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			shown[0] = harness.ui().isValuesShown();
		});
		return shown[0];
	}

	@Test
	public void timeLeftIsShownOnlyWhileRunningOrPaused()
	{
		assertFalse("idle", timeLeftShown());

		assertTrue(harness.play());
		assertTrue("running", timeLeftShown());
		assertEquals("no kills yet", LiveSessionSection.TIME_LEFT_ESTIMATING, timeLeftText());

		harness.pause();
		assertTrue("paused", timeLeftShown());

		harness.stop();
		assertFalse("stopped", timeLeftShown());
	}

	@Test
	public void valuesOfAnotherSessionAreIgnored()
	{
		assertTrue(harness.play());
		harness.ticks(2);
		assertTrue("the plugin's own values are shown", valuesShown());

		final String current = harness.tracker().getSessionId();
		assertNotNull(current);
		harness.onEdt(() -> harness.panel().setSessionValues(
			new SessionValuation.Values("an-ended-session", 1_000, 0, 0, 0, 0, 0)));
		assertFalse("values of another session are not shown", valuesShown());

		harness.onEdt(() -> harness.panel().setSessionValues(new SessionValuation.Values(current, 1_000, 0, 0, 0, 0, 0)));
		assertTrue(valuesShown());
	}

	@Test
	public void newSessionWithEqualAmountsIsStillShown()
	{
		assertTrue(harness.play());
		harness.ticks(2);
		assertTrue(valuesShown());
		final String first = harness.tracker().getSessionId();

		// Stop and start again between ticks: the new session's values have the same (zero) amounts as the old one's.
		harness.stop();
		assertTrue(harness.play());
		assertNotEquals(first, harness.tracker().getSessionId());
		harness.ticks(2);
		assertTrue("the new session's values are published although the amounts are equal", valuesShown());
	}

	@Test
	public void shownValuesFilter()
	{
		SessionValuation.Values values = new SessionValuation.Values("s1", 0, 0, 0, 0, 0, 0);
		assertSame(values, LiveSessionSection.shownValues(values, SessionTracker.State.RUNNING, "s1"));
		assertSame(values, LiveSessionSection.shownValues(values, SessionTracker.State.PAUSED, "s1"));
		assertNull(LiveSessionSection.shownValues(values, SessionTracker.State.RUNNING, "s2"));
		assertNull(LiveSessionSection.shownValues(values, SessionTracker.State.RUNNING, null));
		assertNull(LiveSessionSection.shownValues(values, SessionTracker.State.STOPPED, "s1"));
		assertNull(LiveSessionSection.shownValues(null, SessionTracker.State.RUNNING, "s1"));
	}

	@Test
	public void aLootPriceChangeIsValuedAtOnceButAFailingValuationStaysThrottled()
	{
		final FailingValuation valuation = new FailingValuation();
		replaceSessionValuation(valuation);
		assertTrue(harness.play());
		harness.ticks(1);
		assertEquals(1, valuation.attempts);

		// The next tick would be throttled; a new setting is valued on it anyway, but only once.
		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		harness.ticks(1);
		assertEquals(2, valuation.attempts);
		harness.ticks(1);
		assertEquals(2, valuation.attempts);
		harness.ticks(1);
		assertEquals(3, valuation.attempts);
	}

	@Test
	public void failingValuationIsStillThrottled()
	{
		final FailingValuation valuation = new FailingValuation();
		replaceSessionValuation(valuation);
		assertTrue(harness.play());
		harness.postLoot(ItemID.BONES, 1);

		// A new session is valued on its first tick.
		harness.ticks(1);
		assertEquals(1, valuation.attempts);

		// The first valuation fails, so no values have ever been shown.
		harness.ticks(9);
		// One attempt every 2 ticks, not one every tick.
		assertEquals(5, valuation.attempts);
		assertFalse(valuesShown());

		// Once valuing works again the values come back.
		valuation.fail = false;
		harness.ticks(2);
		assertTrue(valuesShown());
		harness.stop();
		assertEquals(Long.valueOf(100), harness.onlyRecord(ACCOUNT).getLootGeValue());
	}
}
