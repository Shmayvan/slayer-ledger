package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.PluginHarness.item;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.ChatMessageType;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * The task overlay as wired in the plugin ({@link PluginHarness}): registration, what it shows as the session
 * changes, the snapshot the ticks build without the panel, live values matched by session, the settings, the
 * width, the completion grace window and a re-login. {@link OverlayRowsTest} covers the rows' text.
 */
public class HarnessOverlayTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final long MINUTE = 60_000;
	private static final String[] DRINK = {"Drink", null, null, "Empty", "Drop"};
	private static final int[] PRAYER = {-1, ItemID._1DOSEPRAYERRESTORE, ItemID._2DOSEPRAYERRESTORE,
		ItemID._3DOSEPRAYERRESTORE, ItemID._4DOSEPRAYERRESTORE};

	@Rule
	public final PluginHarness harness = new PluginHarness();

	/** The tracker's clock, so active time (and the time-left estimate) is under the test's control. */
	private final AtomicLong now = new AtomicLong(1_000_000_000L);

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.BONES, "Bones", 100, 1);
		for (int dose = 1; dose <= 4; dose++)
		{
			harness.defineItem(PRAYER[dose], "Prayer potion(" + dose + ")", 2_000L * dose, 0, DRINK);
		}
		harness.startUp();
		useTestClock();
		harness.setTask(42, "Abyssal demons", 12, 12);
		harness.loginAndSettle(ACCOUNT);
	}

	/** Points the plugin's session tracker at {@link #now} (it reads the system clock otherwise). */
	private void useTestClock()
	{
		harness.setTrackerClock(now::get);
	}

	/** The overlay the plugin registered. */
	private SlayerLedgerOverlay overlay()
	{
		ArgumentCaptor<Overlay> overlay = ArgumentCaptor.forClass(Overlay.class);
		verify(harness.overlayManager, times(1)).add(overlay.capture());
		return (SlayerLedgerOverlay) overlay.getValue();
	}

	/**
	 * Renders the overlay as the client does (on the client thread, in RuneScape Plain, the default Interface font);
	 * {@code null} when it draws nothing.
	 */
	private Dimension render()
	{
		BufferedImage image = new BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			graphics.setFont(FontManager.getRunescapeFont());
			return overlay().render(graphics);
		}
		finally
		{
			graphics.dispose();
		}
	}

	/** The rows the overlay shows now, with every group on. */
	private List<OverlayRows.Row> rows()
	{
		return OverlayRows.rows(harness.overlayData(), OverlayRows.Groups.ALL);
	}

	/** The row of {@code kind} now, or {@code null}. */
	private OverlayRows.Row row(OverlayRows.Kind kind)
	{
		for (OverlayRows.Row row : rows())
		{
			if (row.getKind() == kind)
			{
				return row;
			}
		}
		return null;
	}

	private List<String> text()
	{
		List<String> text = new ArrayList<>();
		for (OverlayRows.Row row : rows())
		{
			text.add(row.toString());
		}
		return text;
	}

	@Test
	public void registersOverlayAndShowsTheStateWheneverTimeIsNotRunning()
	{
		OverlayRows.Data waiting = harness.overlayData();
		assertNotNull("an assigned task waiting for its first kill shows its state", waiting);
		assertNull("no session yet", waiting.getTask());
		assertEquals(SessionTracker.Status.WAITING_FOR_FIRST_KILL, waiting.getStatus());
		assertEquals("STATE: Waiting for first kill", rows().get(0).toString());
		assertEquals("IDENTITY: Abyssal demons", rows().get(1).toString());
		assertEquals("BAR: 0 / 12", rows().get(2).toString());
		assertNotNull(render());

		assertTrue(harness.startNow());
		OverlayRows.Data running = harness.overlayData();
		assertNotNull(running);
		assertEquals("Abyssal demons", running.getTaskName());
		assertEquals(12, running.getTask().getRemaining());
		assertEquals(OverlayRows.Kind.IDENTITY, rows().get(0).getKind());
		assertNotNull("drawn while running", render());

		harness.pause();
		OverlayRows.Data paused = harness.overlayData();
		assertNotNull(paused);
		assertEquals(SessionTracker.Status.PAUSED, paused.getTask().getStatus());
		assertEquals("at once, without a tick", "STATE: Paused / Resume in sidebar", rows().get(0).toString());
		assertEquals(12, paused.getTask().getRemaining());
		assertNotNull("drawn while paused", render());

		harness.endTask();
		OverlayRows.Data ended = harness.overlayData();
		assertNull("an ended session has no task", ended.getTask());
		assertEquals(SessionTracker.Status.ENDED, ended.getStatus());
		assertEquals("STATE: Ended / See sidebar", rows().get(0).toString());
		assertNotNull("its state is drawn", render());
		harness.tick();
		assertEquals("the assignment's identity from the next tick", "IDENTITY: Abyssal demons",
			rows().get(1).toString());

		harness.setTask(42, "Abyssal demons", 0, 12);
		harness.tick();
		assertNull("no task: nothing to show", harness.overlayData());
		assertNull(render());

		harness.shutDown();
		verify(harness.overlayManager, times(1)).remove(any(Overlay.class));
	}

	@Test
	public void killsAndTimeLeftUpdateAfterAKill()
	{
		assertTrue(harness.startNow());
		assertEquals("12 left", row(OverlayRows.Kind.PROGRESS).getLeft());
		assertEquals("not estimable yet", LedgerFormat.UNKNOWN, row(OverlayRows.Kind.PROGRESS).getRight());
		assertNull("the pace once known", row(OverlayRows.Kind.PACE));

		now.addAndGet(4 * MINUTE);
		harness.setRemaining(8);
		harness.tick();

		assertEquals("IDENTITY: Abyssal demons", row(OverlayRows.Kind.IDENTITY).toString());
		assertEquals("BAR: 4 / 12", row(OverlayRows.Kind.BAR).toString());
		// One kill a minute so far, 8 left.
		assertEquals("PROGRESS: 8 left / ~8 min", row(OverlayRows.Kind.PROGRESS).toString());
		assertEquals("PACE: 1:00/kill / 60/h", row(OverlayRows.Kind.PACE).toString());
		assertEquals("ELAPSED: 4:00 elapsed / 0 superiors", row(OverlayRows.Kind.ELAPSED).toString());
	}

	@Test
	public void theSnapshotUpdatesOnTicksWithoutThePanel()
	{
		// The panel is never shown: its refresh timer doesn't run, and the overlay doesn't need it.
		harness.onEdt(() -> harness.panel().showingChanged(false));
		AtomicBoolean timer = new AtomicBoolean(true);
		harness.onEdt(() -> timer.set(harness.panel().isRefreshTimerRunning()));
		assertFalse(timer.get());

		harness.setTask(42, "Abyssal demons", 30, 30);
		harness.setInventory(prayer(20));
		harness.tick();
		assertTrue(harness.startNow());
		harness.tick();

		// A superior: the moment line for about 10 seconds, from the next tick.
		harness.chat(ChatMessageType.GAMEMESSAGE, SuperiorSpawnMessage.TEXT);
		assertNull("not before the tick", row(OverlayRows.Kind.MOMENT));
		harness.tick();
		assertEquals("MOMENT: Superior spawned.", row(OverlayRows.Kind.MOMENT).toString());
		assertEquals("1 superior", row(OverlayRows.Kind.ELAPSED).getRight());
		harness.ticks(OverlayFeed.MOMENT_TICKS);
		assertNull(row(OverlayRows.Kind.MOMENT));

		// Ten kills, a dose each: this task's rate. 10 doses left last 10 kills, with 20 left.
		for (int kill = 1; kill <= 10; kill++)
		{
			now.addAndGet(MINUTE / 2);
			harness.setInventory(prayer(20 - kill));
			harness.setRemaining(30 - kill);
			harness.tick();
		}
		assertEquals("BAR: 10 / 30", row(OverlayRows.Kind.BAR).toString());
		OverlayRows.Row runway = row(OverlayRows.Kind.RUNWAY);
		assertEquals("Prayer: ~10 kills", runway.getLeft());
		assertEquals(Integer.valueOf(ItemID._4DOSEPRAYERRESTORE), runway.getIconItemId());
		assertFalse(runway.isBold());

		// One more: 9 doses for 9 kills, critical.
		now.addAndGet(MINUTE / 2);
		harness.setInventory(prayer(9));
		harness.setRemaining(19);
		harness.tick();
		assertEquals("Prayer: 9 left", row(OverlayRows.Kind.RUNWAY).getLeft());
		assertTrue(row(OverlayRows.Kind.RUNWAY).isBold());
		assertNotNull("drawn on the client thread, icons and all", render());

		// All drunk: the warning stays, at none, until potions are carried again.
		harness.setInventory(prayer(0));
		harness.tick();
		OverlayRows.Row out = row(OverlayRows.Kind.RUNWAY);
		assertEquals("Prayer: 0 left", out.getLeft());
		assertTrue(out.isBold());
		assertEquals(Integer.valueOf(ItemID._4DOSEPRAYERRESTORE), out.getIconItemId());
		harness.ticks(3);
		assertEquals("Prayer: 0 left", row(OverlayRows.Kind.RUNWAY).getLeft());
		assertNotNull(render());
		// While the inventory isn't loaded nothing is known to have run out.
		harness.clearInventory();
		harness.tick();
		assertNull(row(OverlayRows.Kind.RUNWAY));
		harness.setInventory(prayer(0));
		harness.tick();
		assertEquals("Prayer: 0 left", row(OverlayRows.Kind.RUNWAY).getLeft());

		// More potions: they outlast the task, so no runway.
		harness.setInventory(prayer(40));
		harness.tick();
		assertNull(row(OverlayRows.Kind.RUNWAY));

		// The overlay off: no snapshot is worked out.
		harness.config.showTaskOverlay = false;
		harness.tick();
		assertNull(harness.overlayData().getSnapshot());
		assertNull(render());
	}

	@Test
	public void aBlowpipesDartsCountFromItsCheckMessageLessItsShots()
	{
		harness.defineStackableWeapon(ItemID.RUNE_DART, "Rune dart", 100);
		harness.defineItem(ItemID.SNAKEBOSS_SCALE, "Zulrah's scales", 150, 0, null, null, null, null, "Drop");
		harness.setTask(42, "Abyssal demons", 30, 30);
		harness.setWorn(EquipmentInventorySlot.WEAPON, ItemID.TOXIC_BLOWPIPE_LOADED, 1);
		harness.chat(ChatMessageType.GAMEMESSAGE,
			"Darts: <col=007f00>Rune dart x 1,000</col>. Scales: <col=007f00>5,000 (33.3%)</col>.");
		harness.tick();
		assertTrue(harness.startNow());

		// Ten kills, a shot each: a dart a kill (no cape-slot device). 990 darts outlast the 20 left.
		for (int kill = 1; kill <= 10; kill++)
		{
			harness.setAnimation(AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK, 0);
			harness.setRemaining(30 - kill);
			harness.tick();
			harness.setAnimation(-1, 0);
		}
		assertNull(row(OverlayRows.Kind.RUNWAY));

		// Checked again with 15 inside: they last 15 kills.
		harness.chat(ChatMessageType.GAMEMESSAGE,
			"Darts: <col=007f00>Rune dart x 15</col>. Scales: <col=007f00>5,000 (33.3%)</col>.");
		harness.tick();
		assertEquals("Darts: ~15 kills", row(OverlayRows.Kind.RUNWAY).getLeft());
		assertEquals(Integer.valueOf(ItemID.RUNE_DART), row(OverlayRows.Kind.RUNWAY).getIconItemId());

		// Another shot and kill: 14 darts, still a dart a kill.
		harness.setAnimation(AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK, 0);
		harness.setRemaining(19);
		harness.tick();
		harness.setAnimation(-1, 0);
		assertEquals("Darts: ~14 kills", row(OverlayRows.Kind.RUNWAY).getLeft());

		// Loading it: what it holds is unknown until the next check, so nothing shows.
		harness.clickUseOn(ItemID.RUNE_DART, ItemID.TOXIC_BLOWPIPE_LOADED);
		harness.tick();
		assertNull(row(OverlayRows.Kind.RUNWAY));
	}

	/** A worn toxic blowpipe, with its rune darts and Zulrah's scales defined, and an empty inventory loaded. */
	private void wearBlowpipe()
	{
		harness.defineStackableWeapon(ItemID.RUNE_DART, "Rune dart", 100);
		harness.defineItem(ItemID.SNAKEBOSS_SCALE, "Zulrah's scales", 150, 0, null, null, null, null, "Drop");
		harness.setWorn(EquipmentInventorySlot.WEAPON, ItemID.TOXIC_BLOWPIPE_LOADED, 1);
		harness.setInventory();
	}

	/** The blowpipe's check message: {@code darts} rune darts and 5,000 scales. */
	private void checkBlowpipe(int darts)
	{
		harness.chat(ChatMessageType.GAMEMESSAGE, "Darts: <col=007f00>Rune dart x " + darts
			+ "</col>. Scales: <col=007f00>5,000 (33.3%)</col>.");
	}

	/**
	 * {@code kills} task kills, a blowpipe shot each (a dart a kill: no cape-slot device), the last leaving {@code left}
	 * kills left.
	 */
	private void shootAndKill(int kills, int left)
	{
		for (int kill = kills - 1; kill >= 0; kill--)
		{
			harness.setAnimation(AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK, 0);
			harness.setRemaining(left + kill);
			harness.tick();
			harness.setAnimation(-1, 0);
		}
	}

	/** The runway's text now, or {@code null} for no runway. */
	private String runway()
	{
		OverlayRows.Row runway = row(OverlayRows.Kind.RUNWAY);
		return runway == null ? null : runway.getLeft();
	}

	@Test
	public void aBlowpipesContentsAreKeptAcrossACompletedTaskAndTheNextAssignment()
	{
		wearBlowpipe();
		harness.setTask(42, "Abyssal demons", 10, 10);
		harness.tick();
		checkBlowpipe(35);
		harness.tick();
		assertTrue(harness.startNow());
		// Nine kills, a dart each, then the tenth completes the task: 25 darts left.
		shootAndKill(9, 1);
		harness.setAnimation(AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK, 0);
		harness.completeTask();
		harness.tick();
		harness.setAnimation(-1, 0);
		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
		assertTrue(harness.onlyRecord(ACCOUNT).isCompleted());
		// A while without a task: no shots are counted, and what the blowpipe holds is kept.
		harness.ticks(5);
		assertNull(harness.overlayData());

		// The next assignment: 10 more kills, a dart each, leave 15 darts for the 20 kills left.
		harness.setTask(42, "Abyssal demons", 30, 30);
		harness.tick();
		assertTrue(harness.startNow());
		shootAndKill(10, 20);
		assertEquals("Darts: ~15 kills", runway());
	}

	@Test
	public void aBlowpipesContentsAreKeptAcrossEndTask()
	{
		wearBlowpipe();
		harness.setTask(42, "Abyssal demons", 30, 30);
		harness.tick();
		checkBlowpipe(25);
		harness.tick();
		assertTrue(harness.startNow());

		harness.endTask();
		harness.ticks(5);
		harness.trackAgain();
		harness.tick();
		assertTrue(harness.startNow());
		// 10 kills, a dart each: 15 darts for the 20 kills left.
		shootAndKill(10, 20);
		assertEquals("Darts: ~15 kills", runway());
	}

	@Test
	public void aBlowpipesContentsAreForgottenOnUnloadUnchargeLoadingAndLogout()
	{
		wearBlowpipe();
		harness.setTask(42, "Abyssal demons", 30, 30);
		harness.tick();
		assertTrue(harness.startNow());
		checkBlowpipe(1_000);
		shootAndKill(10, 20);
		assertNull("990 darts outlast the 20 kills left", runway());
		checkBlowpipe(15);
		harness.tick();
		assertEquals("Darts: ~15 kills", runway());

		// Each forgets what it holds: no darts or scales count (not even as run out) until the next check.
		harness.clickMenu("Unload", ItemID.TOXIC_BLOWPIPE_LOADED);
		harness.tick();
		assertNull("after Unload", runway());
		checkBlowpipe(15);
		harness.tick();
		assertEquals("Darts: ~15 kills", runway());

		harness.clickMenu("Uncharge", ItemID.TOXIC_BLOWPIPE_LOADED);
		harness.tick();
		assertNull("after Uncharge", runway());
		checkBlowpipe(15);
		harness.tick();
		assertEquals("Darts: ~15 kills", runway());

		harness.clickUseOn(ItemID.SNAKEBOSS_SCALE, ItemID.TOXIC_BLOWPIPE_LOADED);
		harness.tick();
		assertNull("after loading scales", runway());
		checkBlowpipe(15);
		harness.tick();
		assertEquals("Darts: ~15 kills", runway());

		harness.logout();
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(2);
		assertEquals(SessionTracker.Status.WAITING_FOR_NEXT_KILL, harness.status());
		assertEquals("20 left", row(OverlayRows.Kind.PROGRESS).getLeft());
		assertNull("after a logout", runway());
		checkBlowpipe(15);
		harness.tick();
		assertEquals("Darts: ~15 kills", runway());
	}

	@Test
	public void aBlowpipeOutOfScalesOrDartsShowsNoneLeft()
	{
		wearBlowpipe();
		harness.setTask(42, "Abyssal demons", 30, 30);
		harness.tick();
		assertTrue(harness.startNow());
		checkBlowpipe(1_000);
		shootAndKill(10, 20);
		assertNull(runway());

		// No scales, the darts not mentioned: the scales it held ran out.
		harness.chat(ChatMessageType.GAMEMESSAGE, "Your blowpipe needs to be charged with Zulrah's scales.");
		harness.tick();
		OverlayRows.Row scales = row(OverlayRows.Kind.RUNWAY);
		assertEquals("Scales: 0 left", scales.getLeft());
		assertTrue(scales.isBold());
		assertEquals(Integer.valueOf(ItemID.SNAKEBOSS_SCALE), scales.getIconItemId());

		// Charged and checked again: nothing runs out.
		checkBlowpipe(1_000);
		harness.tick();
		assertNull(runway());

		// Neither: both ran out, and the darts come first.
		harness.chat(ChatMessageType.GAMEMESSAGE, "Your blowpipe has run out of scales and darts.");
		harness.tick();
		assertEquals("Darts: 0 left", runway());
		assertEquals(Integer.valueOf(ItemID.RUNE_DART), row(OverlayRows.Kind.RUNWAY).getIconItemId());
	}

	@Test
	public void anUncheckedBlowpipeUnequippedAfterALoginHasNotRunOut()
	{
		wearBlowpipe();
		harness.setTask(42, "Abyssal demons", 30, 30);
		harness.tick();
		assertTrue(harness.startNow());
		checkBlowpipe(1_000);
		shootAndKill(10, 20);
		checkBlowpipe(15);
		harness.tick();
		assertEquals("Darts: ~15 kills", runway());

		// Logged out and in: what it holds is forgotten.
		harness.logout();
		harness.loginAndSettle(ACCOUNT);
		harness.ticks(2);
		assertNull(runway());

		// Unequipped before the next check: its darts and scales may be in it, so they haven't run out.
		harness.setWorn(EquipmentInventorySlot.WEAPON, -1, 0);
		harness.setInventory(item(ItemID.TOXIC_BLOWPIPE_LOADED, 1));
		harness.tick();
		assertNull(runway());
		harness.ticks(3);
		assertNull(runway());
	}

	/** Ten task kills from 30 left, drinking a dose each from 20 doses: this task's rate, a dose a kill. */
	private void drinkADoseAKill()
	{
		harness.setTask(42, "Abyssal demons", 30, 30);
		harness.setInventory(prayer(20));
		harness.tick();
		assertTrue(harness.startNow());
		harness.tick();
		for (int kill = 1; kill <= 10; kill++)
		{
			now.addAndGet(MINUTE / 2);
			harness.setInventory(prayer(20 - kill));
			harness.setRemaining(30 - kill);
			harness.tick();
		}
		assertEquals("10 doses for the 20 kills left", "Prayer: ~10 kills", runway());
	}

	@Test
	public void aSupplyBankedOnPurposeIsForgottenNotRunOut()
	{
		harness.defineItem(ItemID.VIAL_EMPTY, "Vial", 5, 0, null, null, null, null, "Drop");
		drinkADoseAKill();

		// Every prayer potion deposited, to switch setups: forgotten, not run out.
		harness.openInterface(InterfaceID.BANKMAIN);
		harness.setInventory();
		harness.tick();
		harness.closeInterface(InterfaceID.BANKMAIN);
		harness.ticks(SupplyExclusions.INTERFACE_TICKS + 1);
		assertNull("banked", runway());

		// The next kill, and a superior: no runway, so nothing hides the superior line.
		now.addAndGet(MINUTE / 2);
		harness.setRemaining(19);
		harness.tick();
		assertEquals("BAR: 11 / 30", row(OverlayRows.Kind.BAR).toString());
		assertNull(runway());
		harness.chat(ChatMessageType.GAMEMESSAGE, SuperiorSpawnMessage.TEXT);
		harness.tick();
		assertNull(runway());
		assertEquals("MOMENT: Superior spawned.", row(OverlayRows.Kind.MOMENT).toString());

		// Withdrawn again, then the last dose drunk outside the bank: that runs out, also once its vial is dropped.
		harness.openInterface(InterfaceID.BANKMAIN);
		harness.setInventory(prayer(1));
		harness.tick();
		harness.closeInterface(InterfaceID.BANKMAIN);
		harness.ticks(SupplyExclusions.INTERFACE_TICKS + 1);
		assertEquals("Prayer: 1 left", runway());
		harness.setInventory(item(ItemID.VIAL_EMPTY, 1));
		harness.tick();
		assertEquals("Prayer: 0 left", runway());
		assertTrue(row(OverlayRows.Kind.RUNWAY).isBold());
		harness.clickMenu("Drop", ItemID.VIAL_EMPTY);
		harness.setInventory();
		harness.tick();
		assertEquals("Prayer: 0 left", runway());
		harness.ticks(3);
		assertEquals("Prayer: 0 left", runway());
	}

	@Test
	public void theLastFoodDroppedHasNotRunOut()
	{
		harness.defineItem(ItemID.SHARK, "Shark", 900, 300, "Eat", null, null, null, "Drop");
		harness.setTask(42, "Abyssal demons", 30, 30);
		harness.setInventory(sharks(20));
		harness.tick();
		assertTrue(harness.startNow());
		harness.tick();
		// Ten kills, a shark each: 10 sharks for the 20 kills left.
		for (int kill = 1; kill <= 10; kill++)
		{
			now.addAndGet(MINUTE / 2);
			harness.setInventory(sharks(20 - kill));
			harness.setRemaining(30 - kill);
			harness.tick();
		}
		assertEquals("Food: ~10 kills", runway());

		// All of them dropped: not eaten, so they haven't run out.
		harness.clickMenu("Drop", ItemID.SHARK);
		harness.setInventory();
		harness.tick();
		assertNull(runway());
		harness.ticks(3);
		assertNull(runway());
	}

	/** {@code count} sharks, one a slot. */
	private static Item[] sharks(int count)
	{
		Item[] sharks = new Item[count];
		Arrays.fill(sharks, item(ItemID.SHARK, 1));
		return sharks;
	}

	@Test
	public void aSuperiorWhileTheOverlayIsOffShowsNoMomentOnceItIsBackOn()
	{
		assertTrue(harness.startNow());
		harness.tick();

		harness.config.showTaskOverlay = false;
		harness.chat(ChatMessageType.GAMEMESSAGE, SuperiorSpawnMessage.TEXT);
		harness.ticks(2);
		harness.config.showTaskOverlay = true;
		harness.tick();
		assertEquals("1 superior", row(OverlayRows.Kind.ELAPSED).getRight());
		assertNull("no moment for a superior that came while it was off", row(OverlayRows.Kind.MOMENT));

		// The next one has its moment.
		harness.chat(ChatMessageType.GAMEMESSAGE, SuperiorSpawnMessage.TEXT);
		harness.tick();
		assertEquals("MOMENT: Another superior (2nd).", row(OverlayRows.Kind.MOMENT).toString());
	}

	@Test
	public void afterALogoutAnotherAccountNeverSeesTheLastAccountsTask()
	{
		// This account's assignment, Ended: its identity shows under the state line.
		harness.endTask();
		harness.tick();
		assertEquals("STATE: Ended / See sidebar", rows().get(0).toString());
		assertEquals("IDENTITY: Abyssal demons", rows().get(1).toString());

		harness.logout();
		assertNull("nothing while logged out", harness.overlayData());

		// Another account, with another task, logs in: nothing of the first account shows, not even its Ended state line,
		// until the first settled tick has read this account's task.
		harness.setTask(43, "Bloodvelds", 50, 50);
		harness.login(ACCOUNT + 1);
		for (int i = 0; i < PluginHarness.SETTLE_TICKS; i++)
		{
			assertNull("tick " + i + ": " + rows(), harness.overlayData());
			assertNull("tick " + i, render());
			harness.tick();
		}
		assertEquals(Arrays.asList("STATE: Waiting for first kill", "IDENTITY: Bloodvelds", "BAR: 0 / 50"), text());
		assertNotNull(render());
	}

	@Test
	public void aHopKeepsTheOverlayUpThroughItsSettleTicks()
	{
		assertTrue(harness.startNow());
		harness.tick();

		// The same account, its time still running: only a logout waits for the task to be read again.
		harness.hop();
		for (int i = 0; i <= PluginHarness.SETTLE_TICKS; i++)
		{
			assertNotNull("tick " + i, harness.overlayData());
			assertEquals("tick " + i, "Abyssal demons", harness.overlayData().getTaskName());
			harness.tick();
		}
	}

	/** {@code doses} of prayer potion: as many 4-dose potions as fit, and the rest in one. */
	private static Item[] prayer(int doses)
	{
		List<Item> items = new ArrayList<>();
		if (doses / 4 > 0)
		{
			items.add(item(ItemID._4DOSEPRAYERRESTORE, doses / 4));
		}
		if (doses % 4 > 0)
		{
			items.add(item(PRAYER[doses % 4], 1));
		}
		return items.toArray(new Item[0]);
	}

	@Test
	public void profitComesFromThisSessionsPublishedValuesOnly()
	{
		assertTrue(harness.startNow());
		assertNull("not valued yet", row(OverlayRows.Kind.HAUL));
		harness.postLoot(ItemID.BONES, 1);
		harness.tick();
		assertEquals("HAUL: +100 / 0 XP", row(OverlayRows.Kind.HAUL).toString());

		// A new session between ticks: the published values still belong to the old one.
		harness.endTask();
		harness.trackAgain();
		assertTrue(harness.startNow());
		assertNull("the previous session's values are ignored", row(OverlayRows.Kind.HAUL));
		assertNull(harness.overlayData().getValues());

		// Valued once the 2-tick throttle from the old session's valuation has passed.
		harness.ticks(2);
		assertEquals("the new session's own values", "0", row(OverlayRows.Kind.HAUL).getLeft());
	}

	@Test
	public void settingsHideTheOverlay()
	{
		assertTrue(harness.startNow());
		assertNotNull(render());

		harness.config.showTaskOverlay = false;
		assertNull("the master toggle hides it", render());

		harness.config.showTaskOverlay = true;
		harness.config.overlayProgress = false;
		harness.config.overlayPace = false;
		harness.config.overlayHaul = false;
		harness.config.overlayRunway = false;
		assertNull("nothing to draw with every group off while running", render());
		harness.pause();
		assertNotNull("the state line is never off", render());
		harness.resume();

		harness.config.overlayPace = true;
		assertNotNull(render());
		assertEquals(Collections.singletonList(OverlayRows.Kind.ELAPSED),
			kinds(OverlayRows.rows(harness.overlayData(), SlayerLedgerOverlay.groups(harness.config))));
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

	@Test
	public void theWidthIs160UntilThePlayerResizesIt()
	{
		assertTrue(harness.startNow());
		harness.tick();
		// PanelComponent sizes a frame by the children of the frame before, so each size shows from the second.
		render();
		assertEquals(SlayerLedgerOverlay.DEFAULT_WIDTH, render().width);
		assertEquals(160, SlayerLedgerOverlay.DEFAULT_WIDTH);

		// OverlayManager sets the player's resize as the overlay's preferred size.
		overlay().setPreferredSize(new Dimension(220, 0));
		render();
		assertEquals(220, render().width);
		overlay().setPreferredSize(null);
		render();
		assertEquals("set once: back to the default", 160, render().width);
	}

	@Test
	public void hiddenDuringTheCompletionGraceWindowAndAfterTheSave()
	{
		assertTrue(harness.startNow());
		harness.setRemaining(1);
		harness.tick();
		assertEquals("1 left", row(OverlayRows.Kind.PROGRESS).getLeft());

		harness.completeTask();
		harness.tick();
		assertTrue("held for late loot", harness.tracker().hasPendingCompletion());
		assertNull("hidden once the final kill completes the task", harness.overlayData());
		assertNull(render());

		harness.ticks(SessionTracker.COMPLETION_LOOT_GRACE_TICKS);
		assertTrue(harness.onlyRecord(ACCOUNT).isCompleted());
		assertNull("hidden after the save", harness.overlayData());
		assertNull(render());
	}

	@Test
	public void aPausedSessionAfterReLoginIsNeverShownAsComplete()
	{
		assertTrue(harness.startNow());
		harness.setRemaining(10);
		harness.tick();
		harness.pause();
		harness.logout();

		harness.login(ACCOUNT);
		for (int i = 0; i < PluginHarness.SETTLE_TICKS; i++)
		{
			// Until the task is read again the overlay shows nothing, and the live task's kills left are unknown, never 0.
			assertNull("tick " + i, harness.overlayData());
			assertEquals("tick " + i, SessionTracker.LiveTask.UNKNOWN_REMAINING,
				harness.tracker().liveTask().getRemaining());
			harness.tick();
		}
		assertEquals("Abyssal demons", harness.overlayData().getTaskName());
		assertEquals("10 left", row(OverlayRows.Kind.PROGRESS).getLeft());
		assertEquals("BAR: 2 / 12", row(OverlayRows.Kind.BAR).toString());
	}

	@Test
	public void aClearedTaskWaitingForItsCompletionShowsUnknownKillsLeft()
	{
		assertTrue(harness.startNow());
		// The count reads 0 without a completion: possibly transient, so the session waits.
		harness.setRemaining(0);
		harness.tick();
		assertEquals(SessionTracker.State.RUNNING, harness.sessionState());

		assertEquals(SessionTracker.LiveTask.UNKNOWN_REMAINING, harness.overlayData().getTask().getRemaining());
		assertEquals("Abyssal demons", harness.overlayData().getTaskName());
		assertEquals("no kill counted yet", "PROGRESS: 0 killed / ? left",
			row(OverlayRows.Kind.PROGRESS).toString());
		assertNull(row(OverlayRows.Kind.BAR));
	}

	/** A completed Abyssal demons task: 10 kills in 10 minutes, so one minute a kill. */
	private static LedgerRecord priorTask()
	{
		LedgerRecord prior = new LedgerRecord();
		prior.setCreatureId(42);
		prior.setMonster("Abyssal demons");
		prior.setKills(10);
		prior.setActiveMillis(10 * MINUTE);
		prior.setCompleted(true);
		return prior;
	}

	/** The sidebar's time-left line after a refresh (on the EDT, as the panel's timer runs it). */
	private String sidebarTimeLeft()
	{
		String[] text = new String[1];
		harness.onEdt(() ->
		{
			harness.panel().refresh();
			text[0] = harness.ui().getTimeLeftText();
		});
		return text[0];
	}

	/** The overlay's time left. */
	private String timeLeft()
	{
		return row(OverlayRows.Kind.PROGRESS).getRight();
	}

	@Test
	public void earlyInATaskTheOverlayShowsTheSidebarsHistoricalEstimate()
	{
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, Collections.singletonList(priorTask())));
		assertTrue(harness.startNow());

		// The same estimate in both; the overlay writes it as DESIGN_PRINCIPLES.md 5.5 does.
		assertEquals("12 kills at the history's minute a kill", "Time left: ~12m", sidebarTimeLeft());
		assertEquals("~12 min", timeLeft());

		// 1 kill in 30 seconds: too little for the live pace, so still the history, for the 11 left.
		now.addAndGet(MINUTE / 2);
		harness.setRemaining(11);
		harness.tick();
		assertEquals("Time left: ~11m", sidebarTimeLeft());
		assertEquals("~11 min", timeLeft());

		// 3 kills in 90 seconds: the live pace (30 seconds a kill, 9 left) takes over in both.
		now.addAndGet(MINUTE);
		harness.setRemaining(9);
		harness.tick();
		assertEquals("4m 30s rounds half up", "Time left: ~5m", sidebarTimeLeft());
		assertEquals("~5 min", timeLeft());
	}

	@Test
	public void theHistoricalEstimateIsClearedWithItsSession()
	{
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, Collections.singletonList(priorTask())));
		assertTrue(harness.startNow());
		assertEquals("Time left: ~12m", sidebarTimeLeft());
		assertEquals("~12 min", timeLeft());

		// A new session before the panel's next refresh: the old session's estimate is still the published one.
		harness.endTask();
		harness.onEdt(() -> harness.historyModel().setRecords(ACCOUNT, Collections.emptyList()));
		harness.trackAgain();
		assertTrue(harness.startNow());
		assertEquals("the previous session's estimate is not reused before the panel refreshes",
			LedgerFormat.UNKNOWN, timeLeft());
		// The live card has no room for "estimating...": it shows the time left as unknown, its tooltip says why.
		assertEquals("Time left: " + LiveSessionSection.TIME_LEFT_UNKNOWN, sidebarTimeLeft());
		assertEquals(LedgerFormat.UNKNOWN, timeLeft());
	}
}
