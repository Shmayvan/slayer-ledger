package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;

/**
 * {@link ItemListView} as the XP list: each line has its skill's small icon before the name, from a fake
 * {@link SkillIcons} (no {@code SkillIconManager}), and a line with no skill keeps an empty slot of the same size. The
 * top ten, "+N more" and tooltips are as before. {@link HarnessXpTest} and {@link HarnessItemListsTest} cover the
 * lists as wired in the plugin.
 */
public class ItemListViewTest
{
	/** Skills the fake knows; like the plugin, it leaves the label without an icon for any other key. */
	private static final List<String> KNOWN = Arrays.asList("ATTACK", "STRENGTH", "DEFENCE", "RANGED", "PRAYER",
		"MAGIC", "HITPOINTS", "SLAYER", "AGILITY", "HERBLORE", "THIEVING", "CRAFTING");

	/** What each fake icon shows, by its image. */
	private final Map<Image, String> shown = Collections.synchronizedMap(new IdentityHashMap<>());
	/** Every icon asked for, in order. */
	private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
	private final SkillIcons icons = (label, skill) ->
	{
		assertTrue("icons are put on labels on the EDT", SwingUtilities.isEventDispatchThread());
		requests.add(skill);
		if (KNOWN.contains(skill))
		{
			Image image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
			shown.put(image, "skill " + skill);
			label.setIcon(new ImageIcon(image));
		}
	};

	private String iconText(Icon icon)
	{
		return icon instanceof ImageIcon ? shown.get(((ImageIcon) icon).getImage()) : null;
	}

	private static void onEdt(Runnable action)
	{
		try
		{
			SwingUtilities.invokeAndWait(action);
		}
		catch (InterruptedException | InvocationTargetException e)
		{
			throw new AssertionError(e.getCause() != null ? e.getCause() : e);
		}
	}

	private ItemListView xpList()
	{
		return new ItemListView(SkillXp.LIST_TITLE, false, ColorScheme.DARKER_GRAY_COLOR, SkillXp.LIST_NOUN, icons);
	}

	private PanelProbe.ItemListProbe probe(ItemListView list)
	{
		return new PanelProbe.ItemListProbe(list, this::iconText);
	}

	/** {@code count} skills of {@link #KNOWN}, the first with the most XP. */
	private static Map<String, Long> xp(int count)
	{
		Map<String, Long> xp = new LinkedHashMap<>();
		for (int i = 0; i < count; i++)
		{
			xp.put(KNOWN.get(i), (long) (count - i) * 100);
		}
		return xp;
	}

	@Test
	public void eachLineShowsItsSkillsIconBeforeTheName()
	{
		onEdt(() ->
		{
			ItemListView list = xpList();
			Map<String, Long> xp = new LinkedHashMap<>();
			xp.put("SLAYER", 1_500L);
			xp.put("HITPOINTS", 500L);
			list.setRows(SkillXp.rows(xp, 0));
			assertEquals(Arrays.asList("Slayer | 1,500", "Hitpoints | 500"), probe(list).getLineTexts());
			assertEquals(Arrays.asList("skill SLAYER 18x16", "skill HITPOINTS 18x16"), probe(list).getLineIcons());
			assertEquals(Arrays.asList("SLAYER", "HITPOINTS"), requests);
		});
	}

	@Test
	public void aLineWithNoSkillIconKeepsAnEmptySlotOfTheSameSize()
	{
		onEdt(() ->
		{
			ItemListView list = xpList();
			Map<String, Long> xp = new LinkedHashMap<>();
			xp.put("SLAYER", 200L);
			xp.put("FUTURE_SKILL", 100L);
			list.setRows(SkillXp.rows(xp, 0));
			assertEquals(Arrays.asList("Slayer | 200", "Future skill | 100"), probe(list).getLineTexts());
			assertEquals(Arrays.asList("skill SLAYER 18x16", "- 18x16"), probe(list).getLineIcons());
		});
	}

	@Test
	public void aLineWithNoSkillIsNotAskedForAnIcon()
	{
		onEdt(() ->
		{
			ItemListView list = xpList();
			list.setRows(Collections.singletonList(new ItemListView.Line()
			{
				@Override
				public String getName()
				{
					return "Total";
				}

				@Override
				public String getSkill()
				{
					return null;
				}

				@Override
				public String amountText()
				{
					return "1,000";
				}

				@Override
				public String tooltip()
				{
					return "Total";
				}
			}));
			assertEquals(Collections.singletonList("Total | 1,000"), probe(list).getLineTexts());
			assertEquals(Collections.singletonList("- 18x16"), probe(list).getLineIcons());
			assertEquals(Collections.emptyList(), requests);
		});
	}

	@Test
	public void theTopTenShowUntilMoreIsClickedWithTheirIcons()
	{
		onEdt(() ->
		{
			ItemListView list = xpList();
			List<Boolean> told = new ArrayList<>();
			list.setShowAll(false, told::add);
			list.setRows(SkillXp.rows(xp(12), 0));
			List<String> lines = probe(list).getLineTexts();
			assertEquals(11, lines.size());
			assertEquals("Attack | 1,200", lines.get(0));
			assertEquals("+2 more", lines.get(10));
			assertEquals(10, probe(list).getLineIcons().size());
			assertEquals("skill ATTACK 18x16", probe(list).getLineIcons().get(0));

			probe(list).clickShowAll();
			lines = probe(list).getLineTexts();
			assertEquals(13, lines.size());
			assertEquals("Show less", lines.get(12));
			assertEquals(12, probe(list).getLineIcons().size());
			assertEquals("skill CRAFTING 18x16", probe(list).getLineIcons().get(11));
			assertEquals(Collections.singletonList(true), told);
		});
	}

	@Test
	public void tooltipsAreTheRowsOwn()
	{
		onEdt(() ->
		{
			ItemListView list = xpList();
			List<SkillXp.Row> rows = SkillXp.rows(Collections.singletonMap("SLAYER", 1_500L), 3_600_000);
			list.setRows(rows);
			assertEquals(Collections.singletonList(rows.get(0).tooltip()), probe(list).getLineTooltips());
			assertEquals("<html>Slayer: 1,500 XP<br>1,500 XP per hour</html>", rows.get(0).tooltip());
		});
	}

	@Test
	public void fixedAndCollapsibleListsCountTheirRowsInTheTitleAlike()
	{
		onEdt(() ->
		{
			// The count covers every row, also those behind "+N more".
			ItemListView fixed = xpList();
			assertEquals("XP gained (0)", probe(fixed).getHeaderText());
			fixed.setRows(SkillXp.rows(xp(12), 0));
			assertEquals("XP gained (12)", probe(fixed).getHeaderText());

			ItemListView collapsible = new ItemListView(SkillXp.LIST_TITLE, true, ColorScheme.DARKER_GRAY_COLOR,
				SkillXp.LIST_NOUN, icons);
			collapsible.setRows(SkillXp.rows(xp(3), 0));
			assertEquals("XP gained (3) | Show", probe(collapsible).getHeaderText());
			collapsible.toggle();
			assertEquals("XP gained (3) | Hide", probe(collapsible).getHeaderText());
		});
	}
}
