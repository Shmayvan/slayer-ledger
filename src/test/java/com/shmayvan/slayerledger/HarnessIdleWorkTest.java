package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Less work while nothing is shown or tracked, as wired in the plugin ({@link PluginHarness}):
 * <ul>
 * <li>the panel's one-second refresh timer runs only while the panel is on screen (its hierarchy listener's
 * {@code showingChanged}, driven directly here since nothing is showing headless; {@code onActivate} /
 * {@code onDeactivate} are only hints), and never after shutdown; the new-task chat preview and the overlay's
 * historical pace don't need it;</li>
 * <li>the supplies diff reads no containers while no session is running, unless a cannon is set up or loaded (so its
 * cannonball type is still inferred from a load before Play); only the inventory is read, once after it changes (a
 * few times at most while an item's data is not loaded), so a potion's use is still recorded under the
 * highest-dose variant seen;</li>
 * <li>an XP reading equal to the last one is not passed to the tracker again, which still counts every gain after a
 * hop or reconnect;</li>
 * <li>a paused session is not valued again once valued while paused with every price known, unless the "Loot price"
 * setting changes or it is resumed.</li>
 * </ul>
 */
public class HarnessIdleWorkTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final long MINUTE = 60_000L;
	private static final int DEMONS = 42;
	/** The line for 20 Abyssal demons from three saved tasks, as in HarnessHistoryModelTest. */
	private static final String LINE = "Abyssal demons (20): ~0:20:00 · +180K (GE) · ~2 bank trips (from 3 tasks)";

	@Rule
	public final PluginHarness harness = new PluginHarness();

	/** The tracker's clock, so active time (and the time-left estimate) is under the test's control. */
	private final AtomicLong now = new AtomicLong(1_000_000_000L);

	/** A valuation that counts its calls and keeps the last values it worked out. */
	private static final class CountingValuation extends SessionValuation
	{
		int calls;
		Values last;

		@Override
		Values update(String sessionId, ItemTally loot, ItemTally supplies, Map<Integer, Integer> doses,
			LootPriceMode mode, HiddenLoot hidden, ItemValuation.PriceLookup lookup, int tick)
		{
			calls++;
			last = super.update(sessionId, loot, supplies, doses, mode, hidden, lookup, tick);
			return last;
		}
	}

	/**
	 * A completed Abyssal demons task: 10 kills in 10 minutes, loot 100K GE, supplies 10K, 1 bank trip; with its items
	 * ({@link SavedItems}).
	 */
	private static LedgerRecord task()
	{
		LedgerRecord record = new LedgerRecord();
		record.setId("task-" + System.nanoTime());
		record.setCreatureId(DEMONS);
		record.setMonster("Abyssal demons");
		record.setKills(10);
		record.setActiveMillis(10 * MINUTE);
		record.setCompleted(true);
		record.setLootGeValue(100_000L);
		record.setLootHaValue(200_000L);
		record.setSuppliesGeValue(10_000L);
		record.setBankTrips(1);
		return SavedItems.withItems(record);
	}

	/** Saves three tasks to the account's ledger file (read when the account logs in) and starts the plugin. */
	private void startWithHistory() throws IOException
	{
		LedgerStore store = new LedgerStore(Filepath.Unchecked.getRooted(harness.ledgerDirectory().toPath()),
			harness.gson);
		for (int i = 0; i < 3; i++)
		{
			store.append(ACCOUNT, task());
		}
		SavedItems.define(harness);
		harness.startUp();
		harness.setTrackerClock(now::get);
		harness.setInventory();
	}

	private boolean timerRunning()
	{
		return timerRunning(harness.panel());
	}

	private boolean timerRunning(SlayerLedgerPanel panel)
	{
		final AtomicBoolean running = new AtomicBoolean();
		harness.onEdt(() -> running.set(panel.isRefreshTimerRunning()));
		return running.get();
	}

	/** The panel comes on screen ({@code true}) or goes off it, as its hierarchy listener reports (on the EDT). */
	private void showing(boolean shown)
	{
		harness.onEdt(() -> harness.panel().showingChanged(shown));
	}

	/** Opens the panel's tab, then shows another one, as the client does (on the EDT). */
	private void showThenHidePanel()
	{
		harness.onEdt(() ->
		{
			harness.panel().showingChanged(true);
			harness.panel().onActivate();
		});
		assertTrue("runs while shown", timerRunning());
		harness.onEdt(() ->
		{
			harness.panel().showingChanged(false);
			harness.panel().onDeactivate();
		});
		assertFalse("stopped once hidden", timerRunning());
	}

	private boolean timeLeftShown()
	{
		final AtomicBoolean shown = new AtomicBoolean();
		harness.onEdt(() -> shown.set(harness.ui().isTimeLeftShown()));
		return shown.get();
	}

	private List<String> chatLines()
	{
		ArgumentCaptor<QueuedMessage> messages = ArgumentCaptor.forClass(QueuedMessage.class);
		verify(harness.chatMessageManager, atLeast(0)).queue(messages.capture());
		return messages.getAllValues().stream().map(QueuedMessage::getRuneLiteFormattedMessage)
			.collect(Collectors.toList());
	}

	/** The overlay's "Time left" value now, with every row on. */
	private String overlayTimeLeft()
	{
		for (OverlayRows.Row row : OverlayRows.rows(harness.overlayData(), true, true, true, true))
		{
			if (row.getLabel().equals("Time left"))
			{
				return row.getValue();
			}
		}
		return null;
	}

	// ---- The panel's refresh timer ----

	@Test
	public void theRefreshTimerRunsOnlyWhileThePanelIsShown()
	{
		harness.startUp();
		harness.setTask(DEMONS, "Abyssal demons", 10, 10);
		harness.loginAndSettle(ACCOUNT);
		assertFalse("a new tab is not shown, so the timer doesn't run", timerRunning());

		assertTrue(harness.play());
		showing(true);
		assertTrue(timerRunning());
		assertTrue("shown at once: the panel refreshes when it comes on screen", timeLeftShown());

		showing(false);
		assertFalse(timerRunning());
		showing(true);
		assertTrue("runs again when shown again", timerRunning());

		harness.onEdt(() -> harness.panel().shutDown());
		assertFalse("stopped when the plugin stops", timerRunning());
	}

	@Test
	public void aDeactivationWhileThePanelIsStillShownDoesNotFreezeIt()
	{
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		showing(true);
		// RuneLite 1.13.4: removing another plugin's tab can make the client deactivate this one while it stays shown.
		harness.onEdt(() -> harness.panel().onDeactivate());
		assertTrue("still refreshed while on screen", timerRunning());
		showing(false);
		assertFalse(timerRunning());
	}

	@Test
	public void anActivationWhileThePanelIsOffScreenStartsNothing()
	{
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		harness.onEdt(() -> harness.panel().onActivate());
		assertFalse(timerRunning());
		showing(true);
		harness.onEdt(() -> harness.panel().onActivate());
		assertTrue("an activation while shown keeps it running", timerRunning());
	}

	@Test
	public void nothingStartsTheTimerAfterShutdown()
	{
		harness.startUp();
		harness.loginAndSettle(ACCOUNT);
		final SlayerLedgerPanel panel = harness.panel();
		showing(true);
		harness.shutDown();
		assertFalse("stopped with the plugin", timerRunning(panel));

		// RuneLite 1.13.4: removing this tab can make the client activate this panel after the plugin stopped.
		harness.onEdt(panel::onActivate);
		harness.onEdt(() -> panel.showingChanged(true));
		assertFalse("a late activation or showing change starts nothing", timerRunning(panel));
	}

	@Test
	public void theChatPreviewIsPostedWithThePanelHidden() throws IOException
	{
		startWithHistory();
		showThenHidePanel();
		harness.setRemaining(0);
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(AssignmentWatch.SYNC_MAX_TICKS);

		harness.setTask(DEMONS, "Abyssal demons", 20, 20);
		harness.tick();

		assertEquals(Collections.singletonList(LINE), chatLines());
		assertFalse(timerRunning());
	}

	@Test
	public void theOverlayPaceUpdatesWithThePanelHidden() throws IOException
	{
		startWithHistory();
		showThenHidePanel();
		harness.setTask(DEMONS, "Abyssal demons", 12, 12);
		harness.loginAndSettle(ACCOUNT);

		assertTrue(harness.play());
		assertEquals("12 kills at the history's minute a kill", "~12m", overlayTimeLeft());

		// 1 kill in 30 seconds: too little for the live pace, so still the history, for the 11 left.
		now.addAndGet(MINUTE / 2);
		harness.setRemaining(11);
		harness.tick();
		assertEquals("~11m", overlayTimeLeft());
		assertFalse(timerRunning());
	}

	// ---- Supplies ----

	/** Client reads the plugin makes over {@code ticks} ticks. */
	private int callsOver(int ticks)
	{
		final int before = harness.pluginCallCount();
		harness.ticks(ticks);
		return harness.pluginCallCount() - before;
	}

	@Test
	public void idleAndPausedTicksReadNoSupplies()
	{
		harness.defineItem(ItemID.SHARK, "Shark", 1_000, 300, "Eat");
		harness.defineStackableWeapon(ItemID.RUNE_DART, "Rune dart", 100);
		harness.startUp();
		harness.setTask(DEMONS, "Abyssal demons", 100, 100);
		harness.loginAndSettle(ACCOUNT);

		// No containers loaded: the reads a tick makes whatever the containers hold.
		harness.clearInventory();
		final int idleWithoutContainers = callsOver(5);
		harness.setInventory(PluginHarness.item(ItemID.SHARK, 1), PluginHarness.item(ItemID.SHARK, 1));
		harness.setWorn(EquipmentInventorySlot.AMMO, ItemID.RUNE_DART, 500);
		// The first idle tick after a change reads the inventory alone, for its potions; later ones read nothing.
		final int afterChange = callsOver(1);
		final int idle = callsOver(5);
		assertEquals("idle ticks with an unchanged inventory read no containers", idleWithoutContainers, idle);
		final int oneIdleTick = callsOver(1);
		assertTrue("the inventory is read once after it changes (" + afterChange + " > " + oneIdleTick + ")",
			afterChange > oneIdleTick);

		assertTrue(harness.play());
		final int running = callsOver(5);
		assertTrue("a running session reads them (" + running + " > " + idle + ")", running > idle);

		harness.pause();
		// Past the paused session's first valuation, which reads the tick count.
		harness.ticks(2);
		final int paused = callsOver(5);
		harness.clearInventory();
		assertEquals("paused ticks read none either", paused, callsOver(5));

		// Nothing used while paused is counted after Resume; what is used after it is.
		harness.setInventory(PluginHarness.item(ItemID.SHARK, 1));
		harness.tick();
		assertTrue("resume", harness.play());
		harness.tick();
		harness.setInventory();
		harness.tick();
		harness.stop();
		assertEquals(Long.valueOf(1), harness.onlyRecord(ACCOUNT).getSupplies().get(ItemID.SHARK));
	}

	@Test
	public void cannonballsLoadedBeforePlayAreStillToldApartByType()
	{
		harness.defineItem(SupplyTracker.GRANITE_CANNONBALL, "Granite cannonball", 500, 1);
		harness.defineItem(SupplyTracker.CANNONBALL, "Cannonball", 200, 1);
		harness.startUp();
		harness.setTask(DEMONS, "Abyssal demons", 100, 100);
		harness.setInventory(PluginHarness.item(SupplyTracker.GRANITE_CANNONBALL, 50),
			PluginHarness.item(SupplyTracker.CANNONBALL, 10));
		harness.loginAndSettle(ACCOUNT);

		// The cannon is set up, then loaded with 30 granite cannonballs, all before Play.
		harness.setVarp(VarPlayerID.DROPCANNON, 4);
		harness.tick();
		harness.setInventory(PluginHarness.item(SupplyTracker.GRANITE_CANNONBALL, 20),
			PluginHarness.item(SupplyTracker.CANNONBALL, 10));
		harness.setVarp(VarPlayerID.ROCKTHROWER, 30);
		harness.tick();

		assertTrue(harness.play());
		harness.tick();
		harness.setVarp(VarPlayerID.ROCKTHROWER, 28);
		harness.tick();
		harness.stop();

		final Map<Integer, Long> used = harness.onlyRecord(ACCOUNT).getSupplies();
		assertEquals(Long.valueOf(2), used.get(SupplyTracker.GRANITE_CANNONBALL));
		assertEquals(null, used.get(SupplyTracker.CANNONBALL));
	}

	@Test
	public void aPotionDrunkBeforePlayIsStillRecordedUnderItsHighestDoseSeen()
	{
		final String[] drink = {"Drink", null, null, "Empty", "Drop"};
		harness.defineItem(ItemID._4DOSEPRAYERRESTORE, "Prayer potion(4)", 8_000, 100, drink);
		harness.defineItem(ItemID._3DOSEPRAYERRESTORE, "Prayer potion(3)", 6_000, 80, drink);
		harness.defineItem(ItemID._2DOSEPRAYERRESTORE, "Prayer potion(2)", 4_000, 60, drink);
		harness.startUp();
		harness.setTask(DEMONS, "Abyssal demons", 100, 100);
		harness.setInventory(PluginHarness.item(ItemID._4DOSEPRAYERRESTORE, 1));
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(3);

		// One dose drunk while idle: Play's baseline sees only the 3-dose potion.
		harness.setInventory(PluginHarness.item(ItemID._3DOSEPRAYERRESTORE, 1));
		harness.ticks(3);
		assertTrue(harness.play());
		harness.tick();
		harness.setInventory(PluginHarness.item(ItemID._2DOSEPRAYERRESTORE, 1));
		harness.tick();
		harness.stop();

		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals("one dose, under the 4-dose item, as when every idle tick was diffed",
			Collections.singletonMap(ItemID._4DOSEPRAYERRESTORE, 1L), record.getSupplies());
		assertEquals(Collections.singletonMap(ItemID._4DOSEPRAYERRESTORE, 4), record.getSupplyDoses());
	}

	@Test
	public void aPotionPickedUpWhileIdleIsNotedFromTheContainerEvent()
	{
		final String[] drink = {"Drink", null, null, "Empty", "Drop"};
		harness.defineItem(ItemID._4DOSEPRAYERRESTORE, "Prayer potion(4)", 8_000, 100, drink);
		harness.defineItem(ItemID._3DOSEPRAYERRESTORE, "Prayer potion(3)", 6_000, 80, drink);
		harness.defineItem(ItemID._2DOSEPRAYERRESTORE, "Prayer potion(2)", 4_000, 60, drink);
		harness.startUp();
		harness.setTask(DEMONS, "Abyssal demons", 100, 100);
		// Logged in with no potion: the login's own noting sees an empty inventory.
		harness.setInventory();
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(3);

		// Only the ItemContainerChanged event can get the 4-dose potion noted.
		harness.setInventory(PluginHarness.item(ItemID._4DOSEPRAYERRESTORE, 1));
		harness.tick();
		harness.setInventory(PluginHarness.item(ItemID._3DOSEPRAYERRESTORE, 1));
		harness.ticks(3);
		assertTrue(harness.play());
		harness.tick();
		harness.setInventory(PluginHarness.item(ItemID._2DOSEPRAYERRESTORE, 1));
		harness.tick();
		harness.stop();

		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(Collections.singletonMap(ItemID._4DOSEPRAYERRESTORE, 1L), record.getSupplies());
		assertEquals(Collections.singletonMap(ItemID._4DOSEPRAYERRESTORE, 4), record.getSupplyDoses());
	}

	@Test
	public void anItemWithoutStatsStopsTheIdleInventoryReadsAfterAFewTicks()
	{
		final String[] drink = {"Drink", null, null, "Empty", "Drop"};
		// Wieldable, but ItemManager has no stats for it: never classified, so never cached.
		harness.defineItem(ItemID.ABYSSAL_WHIP, "Abyssal whip", 1_000_000, 72_000, null, "Wield", null, null, "Drop");
		harness.defineItem(ItemID.SHARK, "Shark", 1_000, 300, "Eat");
		harness.defineItem(ItemID._4DOSEPRAYERRESTORE, "Prayer potion(4)", 8_000, 100, drink);
		harness.defineItem(ItemID._3DOSEPRAYERRESTORE, "Prayer potion(3)", 6_000, 80, drink);
		harness.defineItem(ItemID._2DOSEPRAYERRESTORE, "Prayer potion(2)", 4_000, 60, drink);
		harness.startUp();
		harness.setTask(DEMONS, "Abyssal demons", 100, 100);
		harness.setInventory(PluginHarness.item(ItemID.SHARK, 1));
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(3);
		final int idle = callsOver(5);

		harness.setInventory(PluginHarness.item(ItemID.ABYSSAL_WHIP, 1),
			PluginHarness.item(ItemID._4DOSEPRAYERRESTORE, 1));
		final int retrying = callsOver(1);
		assertTrue("read while the whip is unknown (" + retrying + " > 0)", retrying > 0);
		harness.ticks(SlayerLedgerPlugin.INVENTORY_NOTE_TRIES);
		assertEquals("no more reads once the tries are used up", idle, callsOver(5));

		// The potion was noted on the first read all the same.
		harness.setInventory(PluginHarness.item(ItemID.ABYSSAL_WHIP, 1),
			PluginHarness.item(ItemID._3DOSEPRAYERRESTORE, 1));
		harness.ticks(SlayerLedgerPlugin.INVENTORY_NOTE_TRIES + 1);
		assertTrue(harness.play());
		harness.tick();
		harness.setInventory(PluginHarness.item(ItemID.ABYSSAL_WHIP, 1),
			PluginHarness.item(ItemID._2DOSEPRAYERRESTORE, 1));
		harness.tick();
		harness.stop();
		assertEquals("one dose, under the 4-dose item",
			Collections.singletonMap(ItemID._4DOSEPRAYERRESTORE, 1L), harness.onlyRecord(ACCOUNT).getSupplies());
	}

	// ---- XP ----

	private static Map<String, Long> xp(String skill, long amount)
	{
		Map<String, Long> map = new TreeMap<>();
		map.put(skill, amount);
		return map;
	}

	@Test
	public void anUnchangedReadingAfterAHopIsStillTheNewBaseline()
	{
		harness.setSkillXp(Skill.SLAYER, 1_000_000);
		harness.setSkillXp(Skill.HITPOINTS, 2_000_000);
		harness.startUp();
		harness.setTask(DEMONS, "Abyssal demons", 100, 100);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		harness.gainXp(Skill.SLAYER, 10);
		harness.tick();
		harness.ticks(3);

		// The hop drops the tracker's baseline; the stats read the same after it, and must be its new baseline.
		harness.hop();
		harness.ticks(PluginHarness.SETTLE_TICKS);
		assertTrue("resume after the hop", harness.play());
		harness.gainXp(Skill.SLAYER, 5);
		harness.tick();
		harness.stop();

		assertEquals(xp("SLAYER", 15), harness.onlyRecord(ACCOUNT).getXpGained());
	}

	// ---- Paused session values ----

	private CountingValuation startWithCountingValuation()
	{
		harness.defineItem(ItemID.BONES, "Bones", 100, 1);
		harness.startUp();
		harness.setTask(DEMONS, "Abyssal demons", 10, 10);
		harness.loginAndSettle(ACCOUNT);
		final CountingValuation valuation = new CountingValuation();
		harness.plugin.setSessionValuation(valuation);
		return valuation;
	}

	@Test
	public void aPausedSessionIsValuedOnceUntilTheModeChangesOrItResumes()
	{
		final CountingValuation valuation = startWithCountingValuation();
		assertTrue(harness.play());
		harness.postLoot(ItemID.BONES, 1);
		harness.ticks(4);
		assertEquals("valued every 2 ticks while running", 2, valuation.calls);

		harness.pause();
		harness.ticks(2);
		final int afterPause = valuation.calls;
		assertEquals("valued once more while paused", 3, afterPause);
		harness.ticks(10);
		assertEquals("then not again", afterPause, valuation.calls);
		assertEquals(100, valuation.last.getLootValue());

		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		harness.tick();
		assertEquals("a new Loot price setting is valued while paused", afterPause + 1, valuation.calls);
		assertEquals(LootPriceMode.HIGH_ALCH, valuation.last.getLootPriceMode());
		assertEquals(1, valuation.last.getLootValue());
		final AtomicBoolean shown = new AtomicBoolean();
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			shown.set(harness.ui().isValuesShown());
		});
		assertTrue(shown.get());
		harness.ticks(6);
		assertEquals("and then not again", afterPause + 1, valuation.calls);

		assertTrue("resume", harness.play());
		harness.ticks(4);
		assertEquals("valued every 2 ticks again", afterPause + 3, valuation.calls);
	}

	@Test
	public void aPausedSessionWithAnUnavailablePriceKeepsRetryingIt()
	{
		final CountingValuation valuation = startWithCountingValuation();
		harness.setGePrice(ItemID.BONES, 0);
		assertTrue(harness.play());
		harness.postLoot(ItemID.BONES, 1);
		harness.tick();
		harness.pause();
		harness.ticks(2);
		assertNotNull(valuation.last);
		assertEquals(1, valuation.last.getLootUnpriced());
		final int paused = valuation.calls;

		harness.setGePrice(ItemID.BONES, 100);
		harness.ticks(SessionValuation.RETRY_TICKS + 2);
		assertTrue("still valued while a price is missing", valuation.calls > paused);
		assertEquals("the retry filled it in", 0, valuation.last.getLootUnpriced());
		assertEquals(100, valuation.last.getLootValue());

		final int priced = valuation.calls;
		harness.ticks(6);
		assertEquals("then valued no more", priced, valuation.calls);
	}
}
