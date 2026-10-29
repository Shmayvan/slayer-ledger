package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.awt.Container;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;

/**
 * {@link ItemGridView}: supplies and loot as item icons, five to a row, read through
 * {@link PanelProbe.ItemGridProbe} with a fake {@link ItemIcons} (no {@code ItemManager}). Each icon reads as
 * {@code "<name> | <item id> x<quantity>"}, with {@code " | (alch)"} for the alch hint's border. The XP list stays
 * text ({@link ItemListView}). {@link HarnessItemListsTest} covers the grids as wired in the plugin.
 */
public class ItemGridViewTest
{
	/** What each fake icon shows, by its image. */
	private final Map<Image, String> shown = Collections.synchronizedMap(new IdentityHashMap<>());
	/** Every icon asked for, in order. */
	private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
	private final ItemIcons icons = (label, itemId, quantity) ->
	{
		assertTrue("icons are put on labels on the EDT", SwingUtilities.isEventDispatchThread());
		Image image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		shown.put(image, itemId + " x" + quantity);
		requests.add(itemId + " x" + quantity);
		label.setIcon(new ImageIcon(image));
	};

	private String iconText(Icon icon)
	{
		return icon instanceof ImageIcon ? shown.get(((ImageIcon) icon).getImage()) : null;
	}

	private static ItemDetails.Row row(int id, String name, long quantity, Long value, boolean alch)
	{
		return ItemDetails.Row.builder()
			.itemId(id)
			.name(name)
			.quantity(quantity)
			.unit(ItemDetails.Unit.ITEMS)
			.value(value)
			.priceName("GE")
			.unitGe(value == null ? null : value / Math.max(1, quantity))
			.unitHa(null)
			.dosesPerItem(null)
			.alchHint(alch)
			.build();
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

	private PanelProbe.ItemGridProbe probe(ItemGridView grid)
	{
		return new PanelProbe.ItemGridProbe(grid, this::iconText);
	}

	@Test
	public void showsEveryRowAsAnIconInOrderWithItsTooltip()
	{
		List<ItemDetails.Row> rows = new ArrayList<>();
		for (int i = 0; i < 7; i++)
		{
			rows.add(row(100 + i, "Item " + i, i + 1, 1_000L - i, false));
		}
		onEdt(() ->
		{
			ItemGridView grid = new ItemGridView("Loot received", false, ColorScheme.DARKER_GRAY_COLOR, icons);
			grid.setRows(rows);
			PanelProbe.ItemGridProbe probe = probe(grid);
			assertEquals("Loot received (7)", probe.getHeaderText());
			assertEquals(Arrays.asList("Item 0 | 100 x1", "Item 1 | 101 x2", "Item 2 | 102 x3", "Item 3 | 103 x4",
				"Item 4 | 104 x5", "Item 5 | 105 x6", "Item 6 | 106 x7"), probe.getCellTexts());
			List<String> tooltips = probe.getCellTooltips();
			for (int i = 0; i < rows.size(); i++)
			{
				assertEquals(rows.get(i).tooltip(), tooltips.get(i));
			}
			// Two rows of five; three empty slots fill the second.
			assertEquals(2 * ItemGridView.COLUMNS, probe.getSlotCount());
		});
	}

	@Test
	public void theAlchHintIsAnOrangeBorder()
	{
		onEdt(() ->
		{
			ItemGridView grid = new ItemGridView("Loot received", false, ColorScheme.DARKER_GRAY_COLOR, icons);
			grid.setRows(Arrays.asList(row(1127, "Rune platebody", 1, 38_000L, true),
				row(526, "Bones", 3, 300L, false)));
			assertEquals(Arrays.asList("Rune platebody | 1127 x1 | (alch)", "Bones | 526 x3"),
				probe(grid).getCellTexts());
		});
	}

	@Test
	public void aCollapsibleGridStartsClosedCountsItsItemsAndSaysNoneWhenEmpty()
	{
		onEdt(() ->
		{
			ItemGridView grid = new ItemGridView("Supplies used", true, ColorScheme.DARKER_GRAY_COLOR, icons);
			PanelProbe.ItemGridProbe probe = probe(grid);
			assertEquals("Supplies used (0) | Show", probe.getHeaderText());
			assertTrue(probe.getCellTexts().isEmpty());

			probe.toggle();
			assertTrue(grid.isExpanded());
			assertEquals("Supplies used (0) | Hide", probe.getHeaderText());
			assertEquals(Collections.singletonList("None"), probe.getCellTexts());
			assertEquals(0, probe.getSlotCount());

			grid.setRows(Arrays.asList(row(385, "Shark", 2, 1_600L, false), row(2434, "Prayer potion", 1, null,
				false)));
			assertEquals("Supplies used (2) | Hide", probe.getHeaderText());
			assertEquals(Arrays.asList("Shark | 385 x2", "Prayer potion | 2434 x1"), probe.getCellTexts());

			probe.toggle();
			assertEquals("Supplies used (2) | Show", probe.getHeaderText());
			assertTrue("closed: no icons", probe.getCellTexts().isEmpty());
		});
	}

	@Test
	public void iconsAreOnlyAskedForWhileOpenAndWhenTheRowsChange()
	{
		List<ItemDetails.Row> rows = Arrays.asList(row(385, "Shark", 2, 1_600L, false));
		onEdt(() ->
		{
			ItemGridView grid = new ItemGridView("Supplies used", true, ColorScheme.DARKER_GRAY_COLOR, icons);
			grid.setRows(rows);
			assertTrue("closed: nothing drawn", requests.isEmpty());
			grid.toggle();
			assertEquals(Collections.singletonList("385 x2"), requests);
			grid.setRows(new ArrayList<>(rows));
			assertEquals("the same rows are not drawn again", 1, requests.size());
		});
	}

	@Test
	public void theGridNeverWidensTheSidebar()
	{
		onEdt(() ->
		{
			ItemGridView grid = new ItemGridView("Loot received", false, ColorScheme.DARKER_GRAY_COLOR, icons);
			List<ItemDetails.Row> rows = new ArrayList<>();
			for (int i = 0; i < 12; i++)
			{
				rows.add(row(100 + i, "Item " + i, 1, 100L, false));
			}
			grid.setRows(rows);
			Container cells = (Container) SwingLookup.find(grid, "itemGrid.grid");
			// Five columns take the list's width; the grid itself asks for less than one 36 px sprite.
			assertTrue(cells.getPreferredSize().toString(), cells.getPreferredSize().width < 36);
			assertEquals(3 * ItemGridView.CELL_HEIGHT + 2, cells.getPreferredSize().height);
		});
	}

	@Test
	public void hiddenLootIsLeftOutAndCountedInTheTitleAndTooltip()
	{
		List<ItemDetails.Row> rows = Arrays.asList(row(1127, "Rune platebody", 1, 38_000L, false),
			row(526, "Bones", 3, 300L, false), row(995, "Coins", 500, 500L, false));
		onEdt(() ->
		{
			ItemGridView grid = new ItemGridView("Loot received", true, ColorScheme.DARKER_GRAY_COLOR, icons);
			PanelProbe.ItemGridProbe probe = probe(grid);
			grid.toggle();
			grid.setRows(rows, HiddenLoot.of(Arrays.asList(526, 995)));
			assertEquals("Loot received (1, 2 hidden) | Hide", probe.getHeaderText());
			assertEquals(Collections.singletonList("Rune platebody | 1127 x1"), probe.getCellTexts());
			String tip = probe.getHeaderTooltip();
			assertTrue(tip, tip.startsWith("<html>Click to show or hide the items.<br>2 items are hidden by your"
				+ " Ground Items<br>hidden list"));
			assertTrue("coins are hidden like any item and counted in the hidden number", tip.endsWith(
				"2 items are hidden by your Ground Items<br>hidden list; hidden items<br>are left out of loot value,"
					+ " except coins.</html>"));

			// Every item hidden: said instead of "None".
			grid.setRows(rows, HiddenLoot.of(Arrays.asList(1127, 526, 995)));
			assertEquals("Loot received (0, 3 hidden) | Hide", probe.getHeaderText());
			assertEquals(Collections.singletonList("All hidden"), probe.getCellTexts());

			// Nothing hidden: as before, with the plain tooltip.
			grid.setRows(rows, HiddenLoot.NONE);
			assertEquals("Loot received (3) | Hide", probe.getHeaderText());
			assertEquals("Click to show or hide the items", probe.getHeaderTooltip());
			grid.setRows(Collections.emptyList(), null);
			assertEquals(Collections.singletonList("None"), probe.getCellTexts());
		});
	}

	@Test
	public void aFixedGridSaysHowManyAreHiddenAndSkipsEqualRows()
	{
		List<ItemDetails.Row> rows = Arrays.asList(row(1127, "Rune platebody", 1, 38_000L, false),
			row(526, "Bones", 1, 100L, false));
		onEdt(() ->
		{
			ItemGridView grid = new ItemGridView("Loot received", false, ColorScheme.DARKER_GRAY_COLOR, icons);
			PanelProbe.ItemGridProbe probe = probe(grid);
			assertNull("no tooltip without hidden items", probe.getHeaderTooltip());
			grid.setRows(rows, HiddenLoot.of(Collections.singleton(526)));
			assertEquals("Loot received (1, 1 hidden)", probe.getHeaderText());
			assertTrue(probe.getHeaderTooltip(), probe.getHeaderTooltip().startsWith("<html>1 item is hidden by"));
			int drawn = requests.size();
			grid.setRows(new ArrayList<>(rows), HiddenLoot.of(Collections.singleton(526)));
			assertEquals("the same rows and hidden count are not drawn again", drawn, requests.size());
			grid.setRows(rows, HiddenLoot.NONE);
			assertEquals("Loot received (2)", probe.getHeaderText());
			assertNull(probe.getHeaderTooltip());

			// The same header format as a collapsible list, without " | Show" or " | Hide".
			grid.setRows(rows, HiddenLoot.of(Arrays.asList(1127, 526)));
			assertEquals("Loot received (0, 2 hidden)", probe.getHeaderText());
			assertEquals(Collections.singletonList("All hidden"), probe.getCellTexts());
			grid.setRows(Collections.emptyList(), null);
			assertEquals("Loot received (0)", probe.getHeaderText());
			assertEquals(Collections.singletonList("None"), probe.getCellTexts());
		});
	}

	@Test
	public void quantitiesAboveAnIntAreCapped()
	{
		assertEquals(Integer.MAX_VALUE, ItemGridView.iconQuantity(Long.MAX_VALUE));
		assertEquals(14, ItemGridView.iconQuantity(14));
		assertEquals(0, ItemGridView.iconQuantity(-1));
	}

	@Test
	public void theXpListStaysText()
	{
		onEdt(() ->
		{
			ItemListView xp = new ItemListView(SkillXp.LIST_TITLE, false, ColorScheme.DARKER_GRAY_COLOR,
				SkillXp.LIST_NOUN, (label, skill) ->
				{
				});
			xp.setRows(SkillXp.rows(Collections.singletonMap("SLAYER", 1_500L), 0));
			assertEquals(Collections.singletonList("Slayer | 1,500"),
				new PanelProbe.ItemListProbe(xp, this::iconText).getLineTexts());
			assertFalse(SwingLookup.isInTree(xp, "itemGrid.grid"));
		});
	}
}
