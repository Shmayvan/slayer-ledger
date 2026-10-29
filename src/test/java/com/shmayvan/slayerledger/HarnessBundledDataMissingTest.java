package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;

/**
 * The plugin started with neither bundled Wiki resource (the drop snapshot and the task location list), as it would
 * ship if the CC BY-NC-SA data had to be left out: the luck meter says its data is unavailable, the "Name this
 * location" prompt suggests nothing, and nothing fails ({@link PluginHarness} fails the test on any swallowed error).
 */
public class HarnessBundledDataMissingTest
{
	private static final int TASK = 42;
	private static final int SMOKE = 12_946;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Test
	public void thePluginWorksWithoutEitherBundledResource()
	{
		harness.plugin.setLuckSnapshotResource("missing-drop-snapshot.json");
		harness.startUp();
		harness.onEdt(() -> harness.ui().getLocationPrompt().setTaskLocationsResource("missing-task-locations.json"));
		harness.setInventory();
		harness.setTask(TASK, "Dust devils", 20, 20);
		harness.setPlayerRegion(SMOKE);
		harness.loginAndSettle(PluginHarness.ACCOUNT);

		// The location prompt still appears for a new place, with no suggestions, and still saves a typed name.
		assertTrue(harness.play());
		harness.setRemaining(harness.tracker().getCurrentRemaining() - 1);
		harness.tick();
		final int[] region = new int[1];
		harness.onEdt(() -> region[0] = harness.ui().getLocationPrompt().getRegion());
		assertEquals(SMOKE, region[0]);
		final AtomicReference<List<String>> suggestions = new AtomicReference<>();
		harness.onEdt(() -> suggestions.set(harness.ui().getLocationPrompt().getSuggestions()));
		assertEquals(Collections.emptyList(), suggestions.get());
		harness.onEdt(() -> harness.ui().getLocationPrompt().save("Smoke Dungeon"));
		harness.drain();
		assertEquals("Smoke Dungeon", harness.rsProfileConfig(SlayerLedgerConfig.GROUP,
			SlayerLedgerPlugin.LOCATION_NAME_KEY_PREFIX + SMOKE));

		// The luck meter shows only that its data is unavailable, even with loot history to compare.
		LedgerRecord record = new LedgerRecord();
		record.setMonster("Abyssal demons");
		record.setKills(512);
		record.setCompleted(true);
		record.setPartial(false);
		record.setLoot(Collections.singletonMap(1001, 1L));
		harness.onEdt(() -> harness.historyModel().setRecords(PluginHarness.ACCOUNT,
			Collections.singletonList(record)));
		harness.onEdt(() -> harness.ui().getLuckMeter().clickTitle());
		harness.drain();
		final AtomicReference<List<String>> rows = new AtomicReference<>();
		harness.onEdt(() -> rows.set(harness.ui().getLuckMeter().getRows()));
		assertEquals(Collections.singletonList("Luck meter data unavailable"), rows.get());

		harness.shutDown();
	}
}
