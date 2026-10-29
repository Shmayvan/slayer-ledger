package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import net.runelite.client.util.Text;
import net.runelite.client.util.WildcardMatcher;

/**
 * The core Ground Items plugin's hidden-items list, parsed, so Slayer Ledger hides the same items from its loot:
 * they are left out of the "Loot received" grids and out of the loot value. Pure: item names are passed in. Read only;
 * Slayer Ledger never writes Ground Items' settings.
 *
 * <p>Rules, as {@code GroundItemsPlugin} (RuneLite 1.13.4) applies its lists:</p>
 * <ul>
 *     <li>Each list is split on commas with RuneLite's {@link Text#fromCSV}: entries are trimmed and blank ones
 *     skipped.</li>
 *     <li>An entry with {@code *} is a wildcard, matched with RuneLite's {@link WildcardMatcher} ({@code *} matches
 *     any run of characters); any other entry must equal the item's name. Both ignore case.</li>
 *     <li>The highlighted list overrides the hidden one: an exact highlighted entry beats any hidden entry, and a
 *     wildcard highlighted entry beats a wildcard hidden entry, but an exact hidden entry beats a wildcard highlighted
 *     one ({@code GroundItemsPlugin.isHiddenOrHighlighted}).</li>
 *     <li>An entry with a quantity condition ("Coins &lt; 1000", "Bones &gt; 5") is ignored, in either list. Ground
 *     Items checks it against each stack on the ground; Slayer Ledger keeps only a task's totals, so it can't tell
 *     which drops the condition held for. "Name &gt; 0" has no condition (it holds for every stack).</li>
 *     <li>The name matched is the item's own name. A noted item's name is its unnoted item's name, so a note matches
 *     like the item.</li>
 *     <li>A list never edited reads as {@code null} from the config; Ground Items then uses its built-in default
 *     ({@link #DEFAULT_HIDDEN_ITEMS}), and so does Slayer Ledger.</li>
 * </ul>
 *
 * <p>Immutable, so a parsed list can be shared between threads; equal lists are {@link #equals equal}.</p>
 */
final class GroundItemsHidden
{
	/** Ground Items' config group ({@code GroundItemsConfig.GROUP}). */
	static final String GROUP = "grounditems";
	/** Ground Items' hidden-items key. */
	static final String HIDDEN_ITEMS_KEY = "hiddenItems";
	/** Ground Items' highlighted-items key. */
	static final String HIGHLIGHTED_ITEMS_KEY = "highlightedItems";
	/**
	 * Ground Items' built-in hidden list ({@code GroundItemsConfig.getHiddenItems()} in RuneLite 1.13.4), used while
	 * the setting was never edited. Recheck it when the RuneLite version is raised.
	 */
	static final String DEFAULT_HIDDEN_ITEMS = "Vial, Ashes, Coins, Bones, Bucket, Jug, Seaweed";
	/** The config group RuneLite keeps every plugin's on/off state in ({@code RuneLiteConfig.GROUP_NAME}). */
	static final String PLUGIN_ENABLED_GROUP = "runelite";
	/** Ground Items' on/off key: its class name in lower case ({@code PluginManager.isPluginEnabled}). */
	static final String PLUGIN_ENABLED_KEY = "grounditemsplugin";

	/** Hides nothing. */
	static final GroundItemsHidden NONE = new GroundItemsHidden(Collections.emptyList(), Collections.emptyList());

	/** No match, a wildcard match and an exact match, in Ground Items' order of strength. */
	private static final int NO_MATCH = 0;
	private static final int WILDCARD = 1;
	private static final int EXACT = 2;

	private final List<Entry> hidden;
	private final List<Entry> highlighted;

	private GroundItemsHidden(List<Entry> hidden, List<Entry> highlighted)
	{
		this.hidden = hidden;
		this.highlighted = highlighted;
	}

	/**
	 * What Slayer Ledger hides: nothing while its "Hide Ground Items' hidden items" setting is off or the Ground Items
	 * plugin is turned off, otherwise Ground Items' lists ({@link #parse}).
	 *
	 * @param settingOn          Slayer Ledger's setting
	 * @param groundItemsEnabled Ground Items' on/off value from the config ({@link #PLUGIN_ENABLED_KEY}); {@code null}
	 *                           (never changed) is on, as Ground Items is on by default
	 * @param hiddenSetting      Ground Items' hidden list, or {@code null} when never edited
	 * @param highlightedSetting Ground Items' highlighted list, or {@code null} when never edited
	 */
	static GroundItemsHidden effective(boolean settingOn, String groundItemsEnabled, String hiddenSetting,
		String highlightedSetting)
	{
		if (!settingOn || groundItemsEnabled != null && !Boolean.parseBoolean(groundItemsEnabled))
		{
			return NONE;
		}
		return parse(hiddenSetting, highlightedSetting);
	}

	/**
	 * Parses Ground Items' lists; see the class rules.
	 *
	 * @param hiddenSetting      the hidden list; {@code null} (never edited) is {@link #DEFAULT_HIDDEN_ITEMS}
	 * @param highlightedSetting the highlighted list; {@code null} (never edited) is empty, its default
	 */
	static GroundItemsHidden parse(String hiddenSetting, String highlightedSetting)
	{
		List<Entry> hidden = entries(hiddenSetting == null ? DEFAULT_HIDDEN_ITEMS : hiddenSetting);
		if (hidden.isEmpty())
		{
			return NONE;
		}
		return new GroundItemsHidden(hidden, entries(highlightedSetting == null ? "" : highlightedSetting));
	}

	/** Whether nothing is hidden, so no item names need to be looked up. */
	boolean isEmpty()
	{
		return hidden.isEmpty();
	}

	/**
	 * Whether Ground Items hides an item named {@code name} (its own name; a note's is the unnoted item's); {@code null}
	 * (unknown) never is.
	 */
	boolean hides(String name)
	{
		if (name == null || hidden.isEmpty())
		{
			return false;
		}
		int highlight = match(highlighted, name);
		if (highlight == EXACT)
		{
			return false;
		}
		int hide = match(hidden, name);
		return hide == EXACT || hide == WILDCARD && highlight != WILDCARD;
	}

	/** Ground Items' {@code ItemList.matches}: an exact entry first, then a wildcard one. */
	private static int match(List<Entry> entries, String name)
	{
		for (Entry e : entries)
		{
			if (!e.wildcard && e.name.equalsIgnoreCase(name))
			{
				return EXACT;
			}
		}
		for (Entry e : entries)
		{
			if (e.wildcard && WildcardMatcher.matches(e.name, name))
			{
				return WILDCARD;
			}
		}
		return NO_MATCH;
	}

	/** The list's entries without a quantity condition, in order. */
	private static List<Entry> entries(String setting)
	{
		List<Entry> result = new ArrayList<>();
		for (String raw : Text.fromCSV(setting))
		{
			Entry entry = Entry.parse(raw);
			if (entry != null && !result.contains(entry))
			{
				result.add(entry);
			}
		}
		return Collections.unmodifiableList(result);
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof GroundItemsHidden))
		{
			return false;
		}
		GroundItemsHidden g = (GroundItemsHidden) o;
		return hidden.equals(g.hidden) && highlighted.equals(g.highlighted);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(hidden, highlighted);
	}

	@Override
	public String toString()
	{
		return "GroundItemsHidden{hidden=" + hidden + ", highlighted=" + highlighted + "}";
	}

	/** One entry of a list: a name or wildcard pattern. */
	private static final class Entry
	{
		private final String name;
		private final boolean wildcard;

		private Entry(String name, boolean wildcard)
		{
			this.name = name;
			this.wildcard = wildcard;
		}

		/**
		 * Parses an entry as {@code ItemThreshold.fromName} does: a trailing {@code < n} or {@code > n} is a quantity
		 * condition. Returns {@code null} for an empty entry or one with a condition (see the class rules).
		 */
		static Entry parse(String entry)
		{
			if (entry == null || entry.isEmpty())
			{
				return null;
			}
			boolean wildcard = entry.contains("*");
			String name = entry;
			for (int i = entry.length() - 1; i >= 0; i--)
			{
				char c = entry.charAt(i);
				if (c >= '0' && c <= '9' || Character.isWhitespace(c))
				{
					continue;
				}
				if ((c == '<' || c == '>') && i + 1 < entry.length())
				{
					int quantity;
					try
					{
						quantity = Integer.parseInt(entry.substring(i + 1).trim());
					}
					catch (NumberFormatException e)
					{
						// Ground Items then counts it as "> 0", which every stack meets.
						quantity = 0;
						c = '>';
					}
					if (c == '<' || quantity != 0)
					{
						return null;
					}
					name = entry.substring(0, i);
				}
				break;
			}
			name = name.trim();
			return name.isEmpty() ? null : new Entry(name, wildcard);
		}

		@Override
		public boolean equals(Object o)
		{
			return o instanceof Entry && ((Entry) o).wildcard == wildcard && ((Entry) o).name.equals(name);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(name, wildcard);
		}

		@Override
		public String toString()
		{
			return name;
		}
	}
}
