package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.Icon;
import org.junit.Test;

/**
 * The masters' faces ({@link MasterIcons}) and the task names Trends' icons come from: every master name has a
 * file, a missing image is an empty slot, a bundled one is read once and drawn in the 18 by 16 slot; a Catacombs
 * variant or cannon group in Trends carries its task's name.
 */
public class MasterIconsTest
{
	@Test
	public void everyMasterNameHasAFile()
	{
		Map<String, String> expected = new HashMap<>();
		expected.put("Turael/Aya", "turael_chathead.png");
		expected.put("Mazchna", "mazchna_chathead.png");
		expected.put("Vannaka", "vannaka_chathead.png");
		expected.put("Chaeldar", "chaeldar_chathead.png");
		// A shared name uses the first master's face.
		expected.put("Duradel/Kuradal", "duradel_chathead.png");
		expected.put("Nieve/Steve", "nieve_chathead.png");
		expected.put("Krystilia", "krystilia_chathead.png");
		expected.put("Konar", "konar_quo_maten_chathead.png");
		expected.put("Spria", "spria_chathead.png");
		expected.put("Mortimer", "mortimer_chathead.png");
		for (int master = 1; SlayerMaster.hasName(master); master++)
		{
			String name = SlayerMaster.displayName(master);
			assertEquals(name, expected.get(name), MasterIcons.fileName(name));
		}
		assertEquals(expected.size(), SlayerMaster.MORTIMER);
		assertNull(MasterIcons.fileName(SlayerMaster.displayName(42)));
		assertNull(MasterIcons.fileName(null));
	}

	@Test
	public void aMissingImageIsAnEmptySlot()
	{
		// As if the masters/ folder were deleted: no code change, no exception, an empty slot of the same size.
		MasterIcons icons = new MasterIcons("no-such-folder/");
		for (int master = 1; SlayerMaster.hasName(master); master++)
		{
			Icon icon = icons.icon(SlayerMaster.displayName(master));
			assertSame(SmallItemIcon.EMPTY, icon);
			assertEquals(ItemListView.ICON_WIDTH, icon.getIconWidth());
			assertEquals(ItemListView.ICON_HEIGHT, icon.getIconHeight());
		}
		assertSame(SmallItemIcon.EMPTY, new MasterIcons().icon("Unknown master (42)"));
		assertSame(SmallItemIcon.EMPTY, new MasterIcons().icon(null));
	}

	@Test
	public void anUnreadableImageIsAnEmptySlot()
	{
		// A test resource with Konar's file name that is no PNG: an empty slot, no exception.
		MasterIcons icons = new MasterIcons("masters-unreadable/");
		assertNotNull(MasterIcons.class.getResource("masters-unreadable/" + MasterIcons.fileName("Konar")));
		assertSame(SmallItemIcon.EMPTY, icons.icon("Konar"));
		assertSame(SmallItemIcon.EMPTY, icons.icon("Konar"));
	}

	@Test
	public void bundledImagesAreReadOnceAndFitTheSlot()
	{
		MasterIcons icons = new MasterIcons();
		for (int master = 1; SlayerMaster.hasName(master); master++)
		{
			String name = SlayerMaster.displayName(master);
			Icon icon = icons.icon(name);
			if (MasterIcons.class.getResource(MasterIcons.DIRECTORY + MasterIcons.fileName(name)) == null)
			{
				// Removed for the hub: the empty slot.
				assertSame(name, SmallItemIcon.EMPTY, icon);
				continue;
			}
			assertTrue(name, icon instanceof MasterIcons.MasterIcon);
			assertEquals(MasterIcons.fileName(name), ((MasterIcons.MasterIcon) icon).getFile());
			assertEquals(ItemListView.ICON_WIDTH, icon.getIconWidth());
			assertEquals(ItemListView.ICON_HEIGHT, icon.getIconHeight());
			assertSame("kept, not read again", icon, icons.icon(name));
			// Draws inside its slot without throwing.
			BufferedImage canvas = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = canvas.createGraphics();
			icon.paintIcon(null, g, 5, 5);
			g.dispose();
			assertEquals(0, canvas.getRGB(4, 4) >>> 24);
			assertEquals(0, canvas.getRGB(5 + ItemListView.ICON_WIDTH, 5 + ItemListView.ICON_HEIGHT) >>> 24);
		}
	}

	@Test
	public void scalingKeepsTheAspectRatio()
	{
		BufferedImage tall = MasterIcons.scaled(new BufferedImage(66, 102, BufferedImage.TYPE_INT_ARGB), 36, 32);
		assertEquals(Arrays.asList(21, 32), Arrays.asList(tall.getWidth(), tall.getHeight()));
		BufferedImage wide = MasterIcons.scaled(new BufferedImage(119, 60, BufferedImage.TYPE_INT_ARGB), 36, 32);
		assertEquals(Arrays.asList(36, 18), Arrays.asList(wide.getWidth(), wide.getHeight()));
		// Already small: kept as it is.
		BufferedImage small = new BufferedImage(10, 8, BufferedImage.TYPE_INT_ARGB);
		assertSame(small, MasterIcons.scaled(small, 36, 32));
	}

	@Test
	public void trendsGroupsCarryTheirTasksName()
	{
		LedgerRecord plain = record("Dust devils", 3);
		LedgerRecord deviant = record("Aberrant spectres", 2);
		deviant.setVariant("Deviant spectres");
		LedgerRecord cannon = record("Dust devils", 1);
		cannon.setSupplies(Collections.singletonMap(SupplyTracker.CANNONBALL, 100L));
		List<LedgerRecord> records = Arrays.asList(plain, deviant, cannon);

		Map<String, String> tasks = new HashMap<>();
		for (TaskRanking.Entry entry : TaskRanking.rank(records, TaskRanking.Metric.XP, LootPriceMode.GE, null))
		{
			tasks.put(entry.getMonster(), entry.getTask());
		}
		Map<String, String> expected = new HashMap<>();
		expected.put("Dust devils", "Dust devils");
		expected.put("Deviant spectres", "Aberrant spectres");
		expected.put("Dust devils (cannon)", "Dust devils");
		assertEquals(expected, tasks);

		Map<String, String> chart = new HashMap<>();
		for (TaskChart.Monster monster : TaskChart.monsters(records, TaskRanking.Metric.XP, LootPriceMode.GE, null))
		{
			chart.put(monster.getLabel(), monster.getTask());
		}
		assertEquals(expected, chart);
		assertEquals(TaskIcons.itemId("Aberrant spectres"), TaskIcons.itemId(chart.get("Deviant spectres")));
	}

	private static LedgerRecord record(String monster, long endedAt)
	{
		LedgerRecord record = new LedgerRecord();
		record.setMonster(monster);
		record.setEndedAt(endedAt);
		record.setKills(10);
		record.setActiveMillis(30 * 60_000L);
		record.setXpGained(Collections.singletonMap(SkillXp.SLAYER, 1_000L));
		record.setCompleted(true);
		record.setPartial(false);
		return record;
	}
}
