package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.ui.PluginPanel;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * The sidebar's shell in direction A as wired in the plugin, read through {@link PanelProbe}: the
 * status strip's words and buttons per state, the live task card's header, bar and stats in the overlay's order, the
 * supplies and loot grids and the XP under their sub-headers inside the live section, the width budget (long values
 * shown whole, a long name cut short), Trends and the Luck meter gone, and a refresh with nothing new rebuilding
 * nothing. Every refresh here is called by the test; nothing waits for the panel's refresh timer.
 */
public class HarnessLiveCardTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final long MINUTE = 60_000;
	private static final int TASK = 42;
	private static final String DEMONS = "Abyssal demons";

	@Rule
	public final PluginHarness harness = new PluginHarness();

	/** The tracker's clock, so active time, pace and the time left are under the test's control. */
	private final AtomicLong now = new AtomicLong(1_000_000_000L);

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.BONES, "Bones", 100, 1);
		// The client's stats have arrived (Hitpoints above 0), so the XP the tests gain counts from this baseline.
		harness.setSkillXp(Skill.SLAYER, 1_000_000);
		harness.setSkillXp(Skill.HITPOINTS, 2_000_000);
		harness.startUp();
		harness.setTrackerClock(now::get);
		harness.setTask(TASK, DEMONS, 201, 201);
		harness.loginAndSettle(ACCOUNT);
	}

	private <T> T onEdt(Supplier<T> read)
	{
		List<T> result = new ArrayList<>(1);
		harness.onEdt(() -> result.add(read.get()));
		return result.get(0);
	}

	/** Refreshes the panel, as its timer would, then reads it. */
	private <T> T refreshed(Function<PanelProbe, T> read)
	{
		return onEdt(() ->
		{
			harness.panel().refresh();
			return read.apply(harness.ui());
		});
	}

	/** Starts a session and kills 3 in a minute: 198 of 201 left. */
	private void threeKillsInAMinute()
	{
		assertTrue(harness.startNow());
		now.addAndGet(MINUTE);
		harness.setRemaining(198);
		harness.tick();
	}

	@Test
	public void theStatusStripSaysTheStateInWordsAndShowsOnlyTheValidButtons()
	{
		assertEquals("no Pause before the first kill", "Waiting for first kill | End task",
			refreshed(PanelProbe::getStatusStrip));
		assertEquals("", refreshed(PanelProbe::getStatusClock));

		assertTrue(harness.startNow());
		now.addAndGet(21 * MINUTE + 54_000);
		assertEquals("Running | Pause | End task", refreshed(PanelProbe::getStatusStrip));
		assertEquals("the elapsed clock, m:ss under an hour", "21:54", refreshed(PanelProbe::getStatusClock));
		now.addAndGet(41 * MINUTE + 37_000);
		assertEquals("then h:mm:ss", "1:03:31", refreshed(PanelProbe::getStatusClock));

		harness.pause();
		assertEquals("Paused | Resume | End task", refreshed(PanelProbe::getStatusStrip));
		assertEquals("no clock while paused", "", refreshed(PanelProbe::getStatusClock));
		assertTrue(harness.resume());
		assertEquals("Running | Pause | End task", refreshed(PanelProbe::getStatusStrip));

		harness.endTask();
		assertEquals("Ended | Track again", refreshed(PanelProbe::getStatusStrip));
		harness.trackAgain();
		assertEquals("Waiting for first kill | End task", refreshed(PanelProbe::getStatusStrip));

		harness.setTask(TASK, DEMONS, 0, 201);
		harness.ticks(3);
		assertEquals("no buttons without a task", "No task", refreshed(PanelProbe::getStatusStrip));
		assertFalse(refreshed(PanelProbe::isPauseButtonShown));
		assertFalse(refreshed(PanelProbe::isEndButtonShown));
	}

	@Test
	public void theCardShowsTheHeaderTheBarAndTheStatsInTheOverlaysOrder()
	{
		// Before the first kill: the header only, its master line with the task's size.
		assertEquals(Arrays.asList(DEMONS, "201 left"), refreshed(ui -> ui.getTaskBox().getRows()));
		assertTrue(refreshed(ui -> ui.getTaskBox().getDetailsTooltip()).contains("Kills left: 201"));
		assertTrue(refreshed(ui -> ui.getTaskBox().getTaskTooltip()).contains("Kills left: 201"));
		assertEquals("the task's item sprite, 36 by 32", TaskIcons.itemId(DEMONS) + " 36x32",
			refreshed(PanelProbe::getTaskIcon));
		assertFalse("no stats before the first kill", refreshed(PanelProbe::isStatsShown));
		assertNull("no bar before the first kill", refreshed(PanelProbe::getBarText));

		// A session with no kills yet: the stats have the kills left, so the master line doesn't; what isn't known
		// yet is ?.
		assertTrue(harness.startNow());
		assertEquals(Collections.singletonList(DEMONS), refreshed(ui -> ui.getTaskBox().getRows()));
		assertEquals(Arrays.asList(
			"Kills left | 201", "Time left | ?",
			"Pace | ?", "Kills/h | ?",
			"Elapsed | 0:00", "Superiors | 0",
			"Profit | ?", "XP | 0"), refreshed(PanelProbe::getStatsRows));

		// 3 kills in a minute.
		now.addAndGet(MINUTE);
		harness.setRemaining(198);
		harness.tick();
		harness.postLoot(ItemID.BONES, 3);
		harness.gainXp(Skill.SLAYER, 1_500);
		harness.ticks(2);

		assertEquals("3 / 201", refreshed(PanelProbe::getBarText));
		assertEquals(1, (int) refreshed(PanelProbe::getBarPercentage));
		assertEquals(Arrays.asList(
			"Kills left | 198", "Time left | ~1h 06m",
			"Pace | 0:20/kill", "Kills/h | 180",
			"Elapsed | 1:00", "Superiors | 0",
			"Profit | +300", "XP | 1,500"), refreshed(PanelProbe::getStatsRows));
		// The one key number is Kills left, in Bold; a gain is green and signed, never the colour alone.
		assertEquals(LedgerTheme.KEY, refreshed(ui -> ui.statColor("killsLeft")));
		assertEquals(LedgerTheme.GOOD, refreshed(ui -> ui.statColor("profit")));
		assertTrue(refreshed(ui -> ui.statTooltip("xp")).contains("Slayer"));

		// Deaths and bank trips: a fifth row once either happened.
		harness.localPlayerDies();
		harness.tick();
		List<String> rows = refreshed(PanelProbe::getStatsRows);
		assertEquals(Arrays.asList("Deaths | 1", "Bank trips | 0"), rows.subList(8, 10));

		harness.endTask();
		assertFalse("no stats once the session ended", refreshed(PanelProbe::isStatsShown));
		assertNull(refreshed(PanelProbe::getBarText));
		assertEquals("the master line has the task's size again", Arrays.asList(DEMONS, "198 left"),
			refreshed(ui -> ui.getTaskBox().getRows()));
	}

	@Test
	public void theGridsAndTheXpAreInTheLiveSectionUnderSubHeaders()
	{
		threeKillsInAMinute();
		harness.postLoot(ItemID.BONES, 3);
		harness.gainXp(Skill.SLAYER, 1_500);
		harness.ticks(2);

		List<String> order = refreshed(PanelProbe::getContentOrder);
		int card = order.indexOf("liveCard");
		assertTrue(order.toString(), order.indexOf("session") < card);
		assertTrue(order.toString(), card < order.indexOf("liveSupplies"));
		assertTrue(order.toString(), order.indexOf("liveSupplies") < order.indexOf("liveLoot"));
		assertTrue(order.toString(), order.indexOf("liveLoot") < order.indexOf("liveXp"));
		assertTrue("the advisor's place comes after the live section: " + order,
			order.indexOf("liveXp") < order.indexOf("advisor"));

		assertEquals("Supplies used | 0", refreshed(ui -> ui.getLiveSupplies().getHeaderText()));
		assertEquals(Collections.singletonList("None"), refreshed(ui -> ui.getLiveSupplies().getCellTexts()));
		assertEquals("Loot | 300", refreshed(ui -> ui.getLiveLoot().getHeaderText()));
		assertEquals(Collections.singletonList("Bones | " + ItemID.BONES + " x3"),
			refreshed(ui -> ui.getLiveLoot().getCellTexts()));
		assertEquals("five slots to a row", 5, (int) refreshed(ui -> ui.getLiveLoot().getSlotCount()));
		assertEquals("XP gained | 90K/h", refreshed(ui -> ui.getLiveXp().getHeaderText()));
		assertEquals(Collections.singletonList("Slayer | 1,500"), refreshed(ui -> ui.getLiveXp().getLineTexts()));

		// Flat sub-headers (DESIGN_PRINCIPLES.md 3.4): Bold in the heading colour over a 1 px rule, the value on the
		// right. (The lists nested in Overall totals' blocks are quieter: HarnessOverallTotalsTest.)
		assertEquals("Bold heading | Small value | rule", refreshed(ui -> ui.getLiveLoot().getHeaderLook()));
		assertEquals("Bold heading | Small value | rule", refreshed(ui -> ui.getLiveSupplies().getHeaderLook()));
		assertEquals("Bold heading | Small value | rule", refreshed(ui -> ui.getLiveXp().getHeaderLook()));
	}

	/**
	 * Lays the panel out at the sidebar's width with the scroll bar showing, then reads it. RuneLite's PluginPanel is
	 * 225 px wide beside its 17 px scroll bar: 213 px of content (DESIGN_PRINCIPLES.md 3.1), the live card as wide,
	 * 199 px inside it.
	 */
	private <T> T laidOut(Function<PanelProbe, T> read)
	{
		Object[] result = refreshed(ui -> new Object[]{ui.layOutLiveCard(), read.apply(ui)});
		Object[] widths = (Object[]) result[0];
		assertEquals("the panel", PluginPanel.PANEL_WIDTH, widths[0]);
		assertEquals("the content and the card", PluginPanel.PANEL_WIDTH - 2 * PluginPanel.BORDER_OFFSET, widths[1]);
		@SuppressWarnings("unchecked")
		T value = (T) result[1];
		return value;
	}

	@Test
	public void longValuesShowWholeAtTheSidebarsWidthWithTheirFullTextInTheTooltip()
	{
		// A long task: 2,000 kills in 10:03:31 of active time (0:18 a kill), 1,985 left (~9h 59m at that pace), and
		// a 1.24M drop.
		harness.defineItem(ItemID.DRAGON_BONES, "Dragon bones", 1_240_000, 1);
		harness.setTask(TASK + 1, "Dust devils", 3_985, 3_985);
		harness.tick();
		assertTrue(harness.startNow());
		now.addAndGet((10 * 3600 + 3 * 60 + 31) * 1000L);
		harness.setRemaining(1_985);
		harness.tick();
		harness.postLoot(ItemID.DRAGON_BONES, 1);
		harness.ticks(2);

		List<String> rows = Arrays.asList(
			"Kills left | 1,985", "Time left | ~9h 59m",
			"Pace | 0:18/kill", "Kills/h | 199",
			"Elapsed | 10:03:31", "Superiors | 0",
			"Profit | +1.24M", "XP | 0");
		assertEquals(rows, refreshed(PanelProbe::getStatsRows));
		// Shown whole: a grid of two equal columns cut "~9h 59m" short.
		assertEquals(rows, laidOut(PanelProbe::getStatsRowsShown));
		// The bar's label has the bar's whole width: RuneLite's bar gave it a third and cut it to "1 / 3,...".
		assertEquals("2,000 / 3,985", refreshed(PanelProbe::getBarText));
		assertEquals("2,000 / 3,985", laidOut(PanelProbe::getBarTextShown));
		List<String> tooltips = refreshed(PanelProbe::getStatsTooltips);
		for (int i = 0; i < rows.size(); i++)
		{
			String full = rows.get(i).replace(" | ", ": ");
			assertTrue("the whole value in the tooltip: " + tooltips.get(i), tooltips.get(i).startsWith(full)
				|| tooltips.get(i).startsWith("<html>" + full + "<br>"));
		}
	}

	@Test
	public void tenHoursLeftShowWholeBesideAShortClock()
	{
		// 3 kills in a minute, 1,815 left: ~10h 05m at that pace, the widest time left a task usually has.
		harness.setTask(TASK + 1, "Dust devils", 1_818, 1_818);
		harness.tick();
		assertTrue(harness.startNow());
		now.addAndGet(MINUTE);
		harness.setRemaining(1_815);
		harness.tick();

		assertEquals(Arrays.asList("Kills left | 1,815", "Time left | ~10h 05m", "Pace | 0:20/kill", "Kills/h | 180",
			"Elapsed | 1:00"), laidOut(ui -> ui.getStatsRowsShown().subList(0, 5)));
	}

	@Test
	public void aLongNameIsCutShortWithItsFullTextInTheTooltipAndNothingWidensThePanel()
	{
		String name = "Lava strykewyrms in the Wilderness Slayer Cave";
		harness.setTask(TASK + 1, name, 201, 201);
		harness.tick();
		threeKillsInAMinute();

		// No icon for a name that is no known task, so the name has the card's whole width.
		assertEquals("none", refreshed(PanelProbe::getTaskIcon));
		// The name's line as shown (PanelProbe#layOutLiveCard).
		String shown = laidOut(ui -> (String) ui.layOutLiveCard()[4]);
		assertTrue(shown, shown.endsWith(FitLabel.ELLIPSIS));
		assertTrue(shown, name.startsWith(shown.substring(0, shown.length() - 1)));
		assertTrue(refreshed(ui -> ui.getTaskBox().getTaskTooltip()).contains(name));
		assertEquals("the full name is kept", Collections.singletonList(name),
			refreshed(ui -> ui.getTaskBox().getRows()));
		assertTrue("the setup toggles fit the card", refreshed(PanelProbe::getLiveSetupPreferredWidth) <= 199);
		int widest = refreshed(PanelProbe::getLiveSectionPreferredWidth);
		assertTrue("nothing in the live section asks for more than the content's 213 px: " + widest,
			widest <= PluginPanel.PANEL_WIDTH - 2 * PluginPanel.BORDER_OFFSET);
	}

	@Test
	public void trendsAndTheLuckMeterAreGone()
	{
		assertEquals(Arrays.asList(SlayerLedgerPanel.OptionalSection.OVERALL_TOTALS,
			SlayerLedgerPanel.OptionalSection.SKIPPED_BLOCKED),
			Arrays.asList(SlayerLedgerPanel.OptionalSection.values()));
		for (String part : new String[]{"trendsBlock", "trends.header", "luckBlock", "luck.header"})
		{
			assertFalse(part, onEdt(() -> harness.ui().isInPanel(part)));
		}
		assertNull("the drop snapshot is no longer bundled",
			SlayerLedgerPlugin.class.getResource("slayer-drop-snapshot.json"));
	}

	@Test
	public void aRefreshWithNothingNewRebuildsNothing()
	{
		threeKillsInAMinute();
		harness.postLoot(ItemID.BONES, 3);
		harness.gainXp(Skill.SLAYER, 1_500);
		harness.ticks(2);
		Function<PanelProbe, List<Integer>> builds = ui -> Arrays.asList(ui.getLiveCardBuildCount(),
			ui.getLiveListsBuildCount(), ui.getLiveSetupBuildCount());
		List<Integer> before = refreshed(builds);
		for (int i = 0; i < 5; i++)
		{
			assertEquals(before, refreshed(builds));
		}

		// New loot redraws the grids, not the card's icon or the setup toggles.
		harness.postLoot(ItemID.BONES, 1);
		harness.ticks(2);
		List<Integer> after = refreshed(builds);
		assertEquals(before.get(0), after.get(0));
		assertTrue(after.toString(), after.get(1) > before.get(1));
		assertEquals(before.get(2), after.get(2));
		assertEquals("and then nothing again", after, refreshed(builds));
	}
}
