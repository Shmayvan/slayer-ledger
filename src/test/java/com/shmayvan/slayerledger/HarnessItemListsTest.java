package com.shmayvan.slayerledger;

import static com.shmayvan.slayerledger.PluginHarness.item;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * The item lists as wired in the plugin ({@link PluginHarness}), shown as item icons: the live "Supplies
 * used" and "Loot received" icons follow the session, a saved task's items are shown at its saved prices, an old task
 * says it has no item details, and item names are looked up on the client thread (the harness fails any
 * {@code ItemManager} call off it, but for {@code getImage}, which the panel calls on the EDT) and reach the panel on
 * the EDT. Each icon reads as {@code "<name> | <item id> x<quantity drawn>"} ({@link PanelProbe.ItemGridProbe}).
 * {@link ItemDetailsTest} covers the rows themselves and {@link ItemGridViewTest} the grid.
 */
public class HarnessItemListsTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final String[] EAT = {"Eat", null, null, null, "Drop"};
	private static final String[] DRINK = {"Drink", null, null, "Empty", "Drop"};

	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.SHARK, "Shark", 800, 50, EAT);
		harness.defineItem(ItemID._4DOSEPRAYERRESTORE, "Prayer potion(4)", 8_000, 100, DRINK);
		harness.defineItem(ItemID._3DOSEPRAYERRESTORE, "Prayer potion(3)", 6_000, 80, DRINK);
		// High alch above GE: the alch hint.
		harness.defineItem(ItemID.RUNE_PLATEBODY, "Rune platebody", 38_000, 39_000);
		harness.defineItem(ItemID.BONES, "Bones", 100, 1);
		// The alch hint subtracts the nature rune: 39,000 - 90 still beats 38,000.
		harness.defineItem(ItemID.NATURERUNE, "Nature rune", 90, 108);
		harness.startUp();
	}

	private <T> T onEdt(java.util.function.Supplier<T> read)
	{
		AtomicReference<T> value = new AtomicReference<>();
		harness.onEdt(() -> value.set(read.get()));
		return value.get();
	}

	private List<String> liveSupplies()
	{
		return onEdt(() -> harness.ui().getLiveSupplies().getCellTexts());
	}

	private List<String> liveLoot()
	{
		return onEdt(() -> harness.ui().getLiveLoot().getCellTexts());
	}

	private List<String> details(int row)
	{
		return onEdt(() -> harness.ui().getHistory().getDetailsText(row));
	}

	private List<String> liveLootTooltips()
	{
		return onEdt(() -> harness.ui().getLiveLoot().getCellTooltips());
	}

	/** Asserts that each tooltip contains its expected text, in order. */
	private static void assertTooltips(List<String> tooltips, String... expected)
	{
		assertEquals(tooltips.toString(), expected.length, tooltips.size());
		for (int i = 0; i < expected.length; i++)
		{
			assertTrue(tooltips.get(i), tooltips.get(i).contains(expected[i]));
		}
	}

	@Test
	public void liveListsFollowLootAndSupplies()
	{
		harness.setInventory(item(ItemID.SHARK, 10), item(ItemID._4DOSEPRAYERRESTORE, 2));
		harness.setTask(42, "Abyssal demons", 10, 10);
		harness.loginAndSettle(ACCOUNT);
		assertFalse("no lists without a session", onEdt(() -> harness.ui().isLiveItemListsShown()));

		assertTrue(harness.play());
		harness.ticks(2);
		assertTrue(onEdt(() -> harness.ui().isLiveItemListsShown()));
		assertEquals("closed at first", "Supplies used (0) | Show",
			onEdt(() -> harness.ui().getLiveSupplies().getHeaderText()));
		assertEquals("Loot received (0) | Show", onEdt(() -> harness.ui().getLiveLoot().getHeaderText()));
		assertTrue(liveSupplies().isEmpty());

		harness.onEdt(() ->
		{
			harness.ui().getLiveSupplies().toggle();
			harness.ui().getLiveLoot().toggle();
		});
		assertEquals(Collections.singletonList("None"), liveSupplies());
		assertEquals(Collections.singletonList("None"), liveLoot());

		// Two sharks eaten, one dose drunk, a rune platebody and bones looted.
		harness.setInventory(item(ItemID.SHARK, 8), item(ItemID._4DOSEPRAYERRESTORE, 1),
			item(ItemID._3DOSEPRAYERRESTORE, 1));
		harness.postLoot(ItemID.RUNE_PLATEBODY, 1);
		harness.postLoot(ItemID.BONES, 3);
		harness.ticks(2);
		assertEquals("Supplies used (2) | Hide", onEdt(() -> harness.ui().getLiveSupplies().getHeaderText()));
		// One dose shows the potion without a number; the tooltip says it is a dose.
		assertEquals(Arrays.asList("Prayer potion | " + ItemID._4DOSEPRAYERRESTORE, "Shark | " + ItemID.SHARK + " x2"),
			liveSupplies());
		assertTooltips(onEdt(() -> harness.ui().getLiveSupplies().getCellTooltips()),
			"Quantity: 1 dose<br>Value (GE): 2,000 gp", "Quantity: 2<br>Value (GE): 1,600 gp");
		assertEquals(Arrays.asList("Rune platebody | " + ItemID.RUNE_PLATEBODY + " | (alch)",
			"Bones | " + ItemID.BONES + " x3"), liveLoot());
		assertTooltips(liveLootTooltips(), "Value (GE): 38,000 gp", "Value (GE): 300 gp");
		assertTrue(liveLootTooltips().get(0).contains(ItemDetails.ALCH_MARKER + " High alch"));

		// The "Loot price" setting applies to the loot values.
		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		harness.tick();
		assertEquals(Arrays.asList("Rune platebody | " + ItemID.RUNE_PLATEBODY + " | (alch)",
			"Bones | " + ItemID.BONES + " x3"), liveLoot());
		assertTooltips(liveLootTooltips(), "Value (high alch): 39,000 gp", "Value (high alch): 3 gp");

		harness.postLoot(ItemID.RUNE_PLATEBODY, 1);
		harness.ticks(2);
		assertEquals(Arrays.asList("Rune platebody | " + ItemID.RUNE_PLATEBODY + " x2 | (alch)",
			"Bones | " + ItemID.BONES + " x3"), liveLoot());
		assertTooltips(liveLootTooltips(), "Value (high alch): 78,000 gp", "Value (high alch): 3 gp");

		harness.stop();
		assertFalse("hidden once the session ends", onEdt(() ->
		{
			harness.panel().refresh();
			return harness.ui().isLiveItemListsShown();
		}));
	}

	@Test
	public void historyShowsTodaysPricesAndNamesArriveFromTheClientThread() throws IOException
	{
		writeLedger();
		// Today's GE prices differ from the saved ones: the details follow the history totals, today's GE and saved
		// high alch.
		harness.setGePrice(ItemID.RUNE_PLATEBODY, 50_000);
		harness.setGePrice(ItemID.SHARK, 900);
		harness.loginAndSettle(ACCOUNT);
		assertEquals(2, onEdt(() -> harness.ui().getHistory().getValuesText()).size());
		assertNull("closed until clicked", details(0));

		int calls = harness.pluginCallCount();
		// The click and the first draw happen on the EDT, before the client thread has looked up any name.
		List<String> beforeNames = onEdt(() ->
		{
			harness.ui().getHistory().clickRow(0);
			return harness.ui().getHistory().getDetailsText(0);
		});
		assertEquals(Arrays.asList(SlayerLedgerPanel.ITEMS_AT_CURRENT_PRICES, "Supplies used (2)",
			"Item " + ItemID._4DOSEPRAYERRESTORE + " | " + ItemID._4DOSEPRAYERRESTORE + " x6",
			"Item " + ItemID.SHARK + " | " + ItemID.SHARK + " x3", "Loot received (1)",
			"Item " + ItemID.RUNE_PLATEBODY + " | " + ItemID.RUNE_PLATEBODY), beforeNames);
		assertEquals("no client read on the EDT (getImage is not one)", calls, harness.pluginCallCount());

		harness.drain();
		assertTrue("names were looked up through ItemManager", harness.pluginCallCount() > calls);
		// At 50,000 gp on the GE, alching the platebody doesn't pay: no alch border.
		assertEquals(Arrays.asList(SlayerLedgerPanel.ITEMS_AT_CURRENT_PRICES, "Supplies used (2)",
			"Prayer potion | " + ItemID._4DOSEPRAYERRESTORE + " x6", "Shark | " + ItemID.SHARK + " x3",
			"Loot received (1)", "Rune platebody | " + ItemID.RUNE_PLATEBODY), details(0));
		assertTooltips(onEdt(() -> harness.ui().getHistory().getDetailsTooltips(0, "Supplies used")),
			"Quantity: 6 doses<br>Value (GE): 12,000 gp", "Quantity: 3<br>Value (GE): 2,700 gp");
		assertTooltips(onEdt(() -> harness.ui().getHistory().getDetailsTooltips(0, "Loot received")),
			"Value (GE): 50,000 gp");

		// Names are cached: closing and opening again looks nothing up.
		calls = harness.pluginCallCount();
		harness.onEdt(() -> harness.ui().getHistory().clickRow(0));
		assertNull(details(0));
		harness.onEdt(() -> harness.ui().getHistory().clickRow(0));
		harness.drain();
		assertEquals(calls, harness.pluginCallCount());
	}

	@Test
	public void onlyASingleLeftClickOpensATask() throws IOException
	{
		writeLedger();
		harness.loginAndSettle(ACCOUNT);
		harness.onEdt(() -> harness.ui().getHistory().mouseClickRow(0, MouseEvent.BUTTON3, 1));
		harness.drain();
		assertNull("a right click opens the menu, not the items", details(0));
		harness.onEdt(() ->
		{
			harness.ui().getHistory().mouseClickRow(0, MouseEvent.BUTTON1, 1);
			harness.ui().getHistory().mouseClickRow(0, MouseEvent.BUTTON1, 2);
		});
		harness.drain();
		assertTrue("a double click opens the task once", details(0) != null);
	}

	@Test
	public void olderTasksHaveNoItemDetails() throws IOException
	{
		writeLedger();
		harness.loginAndSettle(ACCOUNT);
		harness.onEdt(() -> harness.ui().getHistory().clickRow(1));
		harness.drain();
		assertEquals(Collections.singletonList(ItemDetails.NO_ITEM_DETAILS), details(1));
		assertNull("only the clicked task opens", details(0));
	}

	@Test
	public void longItemListsShowEveryIcon() throws IOException
	{
		assertTrue(harness.ledgerDirectory().isDirectory() || harness.ledgerDirectory().mkdirs());
		StringBuilder loot = new StringBuilder();
		StringBuilder ge = new StringBuilder();
		for (int i = 1; i <= 12; i++)
		{
			String sep = i == 1 ? "" : ",";
			loot.append(sep).append("\"").append(20_000 + i).append("\":1");
			ge.append(sep).append("\"").append(20_000 + i).append("\":").append(i * 100);
			// Today's GE price, which the details show: the same as saved.
			harness.defineItem(20_000 + i, "Item " + (20_000 + i), i * 100, 0);
		}
		String json = "{\"schemaVersion\":2,\"records\":[{\"schemaVersion\":2,\"id\":\"many\",\"monster\":\"Cows\","
			+ "\"initialAmount\":5,\"kills\":5,\"activeMillis\":60000,\"startedAt\":1000,\"endedAt\":2000,"
			+ "\"completed\":true,\"partial\":false,\"loot\":{" + loot + "},\"unitGePrices\":{" + ge + "},"
			+ "\"lootGeValue\":7800,\"lootHaValue\":0}]}";
		Files.write(harness.ledgerDirectory().toPath().resolve(ACCOUNT + ".json"),
			json.getBytes(StandardCharsets.UTF_8));
		harness.loginAndSettle(ACCOUNT);
		harness.onEdt(() -> harness.ui().getHistory().clickRow(0));
		harness.drain();

		List<String> lines = details(0);
		// Price line, "Supplies used (0)", "None", "Loot received (12)", all 12 icons: no "+N more".
		assertEquals(lines.toString(), 16, lines.size());
		assertEquals("highest value first", "Item 20012 | 20012", lines.get(4));
		assertEquals("Item 20001 | 20001", lines.get(15));

		// Redrawn (here by the "Loot price" setting): still every icon.
		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		lines = details(0);
		assertEquals(16, lines.size());
		// No high alch values were saved: every item is unpriced, so they are sorted by name.
		assertEquals("Item 20001 | 20001", lines.get(4));
		assertEquals("Item 20012 | 20012", lines.get(15));
	}

	@Test
	public void longXpListsShowTheTopTenUntilAskedAndKeepThatChoice() throws IOException
	{
		assertTrue(harness.ledgerDirectory().isDirectory() || harness.ledgerDirectory().mkdirs());
		String[] skills = {"ATTACK", "STRENGTH", "DEFENCE", "RANGED", "PRAYER", "MAGIC", "HITPOINTS", "SLAYER",
			"AGILITY", "HERBLORE", "THIEVING", "CRAFTING"};
		StringBuilder xp = new StringBuilder();
		for (int i = 0; i < skills.length; i++)
		{
			xp.append(i == 0 ? "" : ",").append("\"").append(skills[i]).append("\":").append((i + 1) * 100);
		}
		String json = "{\"schemaVersion\":2,\"records\":[{\"schemaVersion\":2,\"id\":\"many\",\"monster\":\"Cows\","
			+ "\"initialAmount\":5,\"kills\":5,\"activeMillis\":3600000,\"startedAt\":1000,\"endedAt\":2000,"
			+ "\"completed\":true,\"partial\":false,\"xpGained\":{" + xp + "}}]}";
		Files.write(harness.ledgerDirectory().toPath().resolve(ACCOUNT + ".json"),
			json.getBytes(StandardCharsets.UTF_8));
		harness.loginAndSettle(ACCOUNT);
		harness.onEdt(() -> harness.ui().getHistory().clickRow(0));
		harness.drain();

		List<String> lines = details(0);
		// "No item details", the XP title, 10 skills, "+2 more": the XP list is still text.
		assertEquals(lines.toString(), 13, lines.size());
		assertEquals("Crafting | 1,200", lines.get(2));
		assertEquals("+2 more", lines.get(12));
		// One skill icon per line shown, none for "+2 more".
		List<String> icons = onEdt(() -> harness.ui().getHistory().getDetailsXpIcons(0));
		assertEquals(10, icons.size());
		assertEquals("skill CRAFTING 18x16", icons.get(0));

		harness.onEdt(() -> harness.ui().getHistory().clickShowAll(0, SkillXp.LIST_TITLE));
		assertEquals("Show less", details(0).get(14));
		// Redrawn (here by the "Loot price" setting): still showing all.
		harness.setLootPriceMode(LootPriceMode.HIGH_ALCH);
		lines = details(0);
		assertEquals(15, lines.size());
		assertEquals("Attack | 100", lines.get(13));
		icons = onEdt(() -> harness.ui().getHistory().getDetailsXpIcons(0));
		assertEquals(12, icons.size());
		assertEquals("skill ATTACK 18x16", icons.get(11));
	}

	@Test
	public void aSavedSkillThisClientDoesNotKnowGetsAnEmptyIconSlot() throws IOException
	{
		assertTrue(harness.ledgerDirectory().isDirectory() || harness.ledgerDirectory().mkdirs());
		// A key that is no Skill constant, as a file from a newer client could hold.
		String json = "{\"schemaVersion\":2,\"records\":[{\"schemaVersion\":2,\"id\":\"future\",\"monster\":\"Cows\","
			+ "\"initialAmount\":5,\"kills\":5,\"activeMillis\":3600000,\"startedAt\":1000,\"endedAt\":2000,"
			+ "\"completed\":true,\"partial\":false,\"xpGained\":{\"SLAYER\":200,\"FUTURE_SKILL\":100}}]}";
		Files.write(harness.ledgerDirectory().toPath().resolve(ACCOUNT + ".json"),
			json.getBytes(StandardCharsets.UTF_8));
		harness.loginAndSettle(ACCOUNT);
		harness.onEdt(() -> harness.ui().getHistory().clickRow(0));
		harness.drain();

		List<String> lines = details(0);
		assertEquals(Arrays.asList("Slayer | 200", "Future skill | 100"), lines.subList(lines.size() - 2, lines.size()));
		// No icon, but a slot of the same size, so both names line up.
		assertEquals(Arrays.asList("skill SLAYER 18x16", "- 18x16"),
			onEdt(() -> harness.ui().getHistory().getDetailsXpIcons(0)));
	}

	/**
	 * Writes a ledger with a task saved with items and prices (newest) and one saved before items were tracked.
	 */
	private void writeLedger() throws IOException
	{
		assertTrue(harness.ledgerDirectory().isDirectory() || harness.ledgerDirectory().mkdirs());
		int potion = ItemID._4DOSEPRAYERRESTORE;
		int shark = ItemID.SHARK;
		int platebody = ItemID.RUNE_PLATEBODY;
		String json = "{\"schemaVersion\":2,\"records\":["
			+ "{\"schemaVersion\":2,\"id\":\"new\",\"monster\":\"Abyssal demons\",\"initialAmount\":10,\"kills\":10,"
			+ "\"activeMillis\":60000,\"startedAt\":3000,\"endedAt\":63000,\"completed\":true,\"partial\":false,"
			+ "\"loot\":{\"" + platebody + "\":1},"
			+ "\"supplies\":{\"" + shark + "\":3,\"" + potion + "\":6},"
			+ "\"supplyDoses\":{\"" + potion + "\":4},"
			+ "\"unitGePrices\":{\"" + platebody + "\":30000,\"" + shark + "\":500,\"" + potion + "\":6000},"
			+ "\"unitHaPrices\":{\"" + platebody + "\":39000,\"" + shark + "\":50,\"" + potion + "\":100},"
			+ "\"lootGeValue\":30000,\"lootHaValue\":39000,\"suppliesGeValue\":10500,\"suppliesHaValue\":300},"
			+ "{\"schemaVersion\":1,\"id\":\"old\",\"monster\":\"Cows\",\"initialAmount\":5,\"kills\":5,"
			+ "\"activeMillis\":60000,\"startedAt\":1000,\"endedAt\":2000,\"completed\":true,\"partial\":false}"
			+ "]}";
		Files.write(harness.ledgerDirectory().toPath().resolve(ACCOUNT + ".json"),
			json.getBytes(StandardCharsets.UTF_8));
	}
}
