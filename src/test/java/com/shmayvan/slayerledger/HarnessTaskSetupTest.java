package com.shmayvan.slayerledger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Setup tags as wired in the plugin ({@link PluginHarness}): a saved task's "Setup..." menu item, its overrides
 * saved on the executor through {@link SlayerLedgerPlugin#editRecordSetup} and shown in the row's tooltip, the live
 * task's overrides carried through a logout and a client restart to its record, and a 1.x ledger shown with its
 * estimated tags without being rewritten. Nothing here waits for the panel's refresh timer: the rows are rebuilt when
 * the records change, and every read follows a drain.
 */
public class HarnessTaskSetupTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int TASK = 42;

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.startUp();
		harness.setInventory();
		harness.setTask(TASK, "Dust devils", 10, 10);
	}

	private <T> T onEdt(Supplier<T> read)
	{
		final AtomicReference<T> result = new AtomicReference<>();
		harness.onEdt(() -> result.set(read.get()));
		return result.get();
	}

	/** The first history row's tooltip, after everything queued has run. */
	private String tooltip()
	{
		harness.drain();
		return onEdt(() -> harness.ui().getHistory().getTooltips().get(0));
	}

	private void editSetup(String id, SetupOverrides overrides)
	{
		harness.onEdt(() -> harness.plugin.editRecordSetup(ACCOUNT, id, overrides));
		harness.drain();
	}

	private File ledgerFile()
	{
		return new File(harness.ledgerDirectory(), ACCOUNT + ".json");
	}

	private String readLedger() throws IOException
	{
		return new String(Files.readAllBytes(ledgerFile().toPath()), StandardCharsets.UTF_8);
	}

	private void writeLedger(String json) throws IOException
	{
		assertTrue(harness.ledgerDirectory().isDirectory() || harness.ledgerDirectory().mkdirs());
		Files.write(ledgerFile().toPath(), json.getBytes(StandardCharsets.UTF_8));
	}

	/** Plays a task briefly and ends it, so it is saved as a partial record. */
	private LedgerRecord savedTask()
	{
		assertTrue(harness.startNow());
		harness.endTask();
		return harness.onlyRecord(ACCOUNT);
	}

	@Test
	public void aSavedTasksSetupIsSetFromItsMenuShownInItsTooltipAndCleared()
	{
		harness.loginAndSettle(ACCOUNT);
		final LedgerRecord saved = savedTask();
		assertEquals(Boolean.FALSE, saved.getDetectedCannon());
		assertEquals(SetupStyle.UNKNOWN, saved.getDetectedStyle());
		final List<String> menu = onEdt(() -> harness.ui().getHistory().getMenuItems(0));
		assertEquals(Arrays.asList("Edit note", "Edit location", "Setup…", "Delete task"), menu);
		String tooltip = tooltip();
		assertTrue(tooltip, tooltip.contains("<br>Cannon: No<br>Style: Unknown<br>Gear: Unknown<br>"));
		assertFalse("nothing estimated", tooltip.contains(SetupTags.ESTIMATE_NOTE));

		editSetup(saved.getId(), SetupOverrides.of(true, SetupStyle.BARRAGE, GearFocus.PRAYER));
		final LedgerRecord edited = harness.onlyRecord(ACCOUNT);
		assertEquals(SetupOverrides.of(true, SetupStyle.BARRAGE, GearFocus.PRAYER), SetupOverrides.from(edited));
		SetupOverrides.of(true, SetupStyle.BARRAGE, GearFocus.PRAYER).copyTo(saved);
		assertEquals("only the overrides change", saved, edited);
		tooltip = tooltip();
		assertTrue(tooltip, tooltip.contains("<br>Cannon: Yes (set by you; detected No)<br>Style: Barrage (set by you;"
			+ " detected Unknown)<br>Gear: Prayer (set by you; detected Unknown)<br>"));

		editSetup(saved.getId(), SetupOverrides.of(null, SetupStyle.BARRAGE, null));
		assertEquals(SetupOverrides.of(null, SetupStyle.BARRAGE, null), SetupOverrides.from(harness.onlyRecord(ACCOUNT)));
		editSetup(saved.getId(), SetupOverrides.NONE);
		assertEquals(SetupOverrides.NONE, SetupOverrides.from(harness.onlyRecord(ACCOUNT)));
		tooltip = tooltip();
		assertTrue(tooltip, tooltip.contains("<br>Cannon: No<br>Style: Unknown<br>Gear: Unknown<br>"));
		assertNull(onEdt(() -> harness.ui().getHistory().getErrorText()));
	}

	@Test
	public void aSavedTasksOverridesSurviveAClientRestart()
	{
		harness.loginAndSettle(ACCOUNT);
		final String id = savedTask().getId();
		editSetup(id, SetupOverrides.of(false, SetupStyle.MELEE, null));

		harness.logout();
		harness.shutDown();
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		assertEquals(SetupOverrides.of(false, SetupStyle.MELEE, null), SetupOverrides.from(harness.onlyRecord(ACCOUNT)));
		final String tooltip = tooltip();
		assertTrue(tooltip, tooltip.contains("<br>Style: Melee (set by you; detected Unknown)<br>"));
	}

	@Test
	public void theLiveTasksOverridesSurviveALogoutAndARestartAndReachItsRecord()
	{
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		final String id = harness.tracker().getSessionId();
		final SetupOverrides mine = SetupOverrides.of(true, SetupStyle.RANGED, GearFocus.STRENGTH);
		assertTrue(onEdt(() -> harness.tracker().setSetupOverrides(id, mine)));

		harness.logout();
		final SessionSnapshot snapshot = harness.activeSession(ACCOUNT);
		assertEquals(mine, SetupOverrides.from(snapshot));
		harness.shutDown();

		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		assertEquals("restored as logged out", SessionTracker.State.LOGGED_OUT, harness.sessionState());
		assertEquals(mine, harness.tracker().getSetupOverrides());
		assertTrue(harness.startNow());
		harness.endTask();

		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(id, record.getId());
		assertEquals(mine, SetupOverrides.from(record));
		final SetupTags tags = SetupTags.of(record);
		assertTrue("the override wins", tags.isCannon());
		assertFalse("over the detected value", tags.isDetectedCannon());
		assertEquals(SetupStyle.RANGED, tags.getStyle());
		assertEquals(GearFocus.STRENGTH, tags.getGear());
	}

	@Test
	public void aOneXLedgerShowsItsEstimatedTagsAndIsOnlyRewrittenByAnEdit() throws IOException
	{
		// Synthetic, shaped like a 1.x ledger: a barrage task whose supplies were not counted.
		final String oneX = "{\"schemaVersion\":2,\"records\":[{\"schemaVersion\":2,\"id\":\"old\","
			+ "\"monster\":\"Dust devils\",\"creatureId\":42,\"initialAmount\":180,\"kills\":180,"
			+ "\"activeMillis\":1800000,\"startedAt\":1762500000000,\"endedAt\":1762501800000,\"completed\":true,"
			+ "\"partial\":false,\"xpGained\":{\"ATTACK\":12000,\"HITPOINTS\":30000,\"MAGIC\":54000,"
			+ "\"SLAYER\":24000,\"STRENGTH\":24000}}]}";
		writeLedger(oneX);
		harness.loginAndSettle(ACCOUNT);
		String tooltip = tooltip();
		assertTrue(tooltip, tooltip.contains("<br>Cannon: ~No<br>Style: ~Barrage<br>Gear: Unknown<br>"
			+ SetupTags.ESTIMATE_NOTE + "<br>"));
		assertEquals("reading never rewrites the ledger", oneX, readLedger());

		editSetup("old", SetupOverrides.NONE.withStyle(SetupStyle.MAGIC));
		final LedgerRecord edited = harness.onlyRecord(ACCOUNT);
		assertEquals(LedgerRecord.CURRENT_SCHEMA_VERSION, edited.getSchemaVersion());
		assertEquals(Boolean.TRUE, edited.getStyleEstimated());
		assertEquals(SetupStyle.BARRAGE, edited.getDetectedStyle());
		tooltip = tooltip();
		assertTrue(tooltip, tooltip.contains("<br>Cannon: ~No<br>Style: Magic (set by you; detected ~Barrage)<br>"));
		assertTrue(readLedger(), readLedger().startsWith("{\"schemaVersion\":" + LedgerStore.FILE_SCHEMA_VERSION));
	}

	@Test
	public void aFailedEditShowsTheMessageAndLeavesANewerLedgerAlone() throws IOException
	{
		final String newer = "{\"schemaVersion\":" + (LedgerStore.FILE_SCHEMA_VERSION + 1) + ",\"records\":[{\"id\":"
			+ "\"future\",\"monster\":\"Hydras\",\"kills\":9,\"completed\":true}]}";
		writeLedger(newer);
		harness.loginAndSettle(ACCOUNT);
		editSetup("future", SetupOverrides.of(true, null, null));
		assertEquals("<html>" + SlayerLedgerPanel.EDIT_SETUP_FAILED_MESSAGE + "</html>",
			onEdt(() -> harness.ui().getHistory().getErrorText()));
		assertEquals(newer, readLedger());
	}

	/** With no kill context counted (no kill yet), the live cannon falls back to the cannonballs fired. */
	@Test
	public void theLiveCannonIsDetectedFromTheCannonballsFiredUntilAKillsContextIsCounted()
	{
		harness.defineItem(SupplyTracker.CANNONBALL, "Cannonball", 200, 5);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.startNow());
		final ItemTally fired = new ItemTally();
		fired.add(SupplyTracker.CANNONBALL, 20);
		// As the plugin adds them, on the client thread (the test thread).
		assertTrue(harness.tracker().addSupplies(fired, null, null));
		assertTrue(harness.tracker().getSetupTags().isCannon());
		harness.endTask();
		assertEquals(Boolean.TRUE, harness.onlyRecord(ACCOUNT).getDetectedCannon());
		final String tooltip = tooltip();
		assertTrue(tooltip, tooltip.contains("<br>Cannon: Yes<br>"));
	}
}
