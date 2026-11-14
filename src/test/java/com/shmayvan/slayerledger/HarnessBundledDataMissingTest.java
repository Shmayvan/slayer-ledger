package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;

/**
 * The plugin started without its bundled Slayer spot table, as it would ship if the CC BY-NC-SA data had to be left
 * out: a task is saved with its region but no spot (a later build with the table works its spot out from the region)
 * and named only by the player's own region names, and nothing fails ({@link PluginHarness} fails the test on any
 * swallowed error). The Slayer boss table is in code ({@link SlayerBosses}); the luck meter's drop snapshot was removed
 * with the Luck meter.
 */
public class HarnessBundledDataMissingTest
{
	private static final int TASK = 42;
	private static final int SMOKE = 12_946;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Test
	public void thePluginWorksWithoutTheBundledSpotTable()
	{
		harness.plugin.setSpotsResource("missing-slayer-spots.json");
		harness.startUp();
		harness.setInventory();
		harness.setTask(TASK, "Dust devils", 20, 20);
		harness.setPlayerRegion(SMOKE);
		harness.loginAndSettle(PluginHarness.ACCOUNT);

		// Kills still count and are tallied in their region, with no spot.
		assertTrue(harness.startNow());
		harness.setRemaining(harness.tracker().getCurrentRemaining() - 1);
		harness.tick();
		assertEquals(Collections.singletonList(new LocationKills(SMOKE, 1)), harness.tracker().getLocationKills());
		assertEquals(new TaskSpot(null, SMOKE), harness.tracker().getTaskSpot());
		harness.endTask();
		LedgerRecord saved = harness.onlyRecord(PluginHarness.ACCOUNT);
		assertEquals(Integer.valueOf(SMOKE), saved.getLocationRegion());
		assertNull(saved.getSpotId());
		assertNull(saved.getLocation());
		assertEquals("a later build with the table works it out", TestSpots.SMOKE_ID,
			TestSpots.TABLE.recordSpot(saved));
		harness.drain();
		final AtomicReference<List<String>> tooltips = new AtomicReference<>();
		harness.onEdt(() -> tooltips.set(harness.ui().getHistory().getTooltips()));
		assertTrue(tooltips.get().get(0), tooltips.get().get(0).contains("<br>Location: Unknown<br>"));

		// The player's own name for the region still names it.
		harness.onEdt(() -> harness.plugin.saveLocationName(SMOKE, "Smoke Dungeon"));
		harness.drain();
		harness.onEdt(() -> tooltips.set(harness.ui().getHistory().getTooltips()));
		assertTrue(tooltips.get().get(0), tooltips.get().get(0).contains("<br>Location: Smoke Dungeon<br>"));

		harness.shutDown();
	}
}
