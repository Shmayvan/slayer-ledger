package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.runelite.client.util.WildcardMatcher;

/**
 * The player's "Ignored loot" setting, parsed: items left out of kill loot when it is captured. Pure: names are passed
 * in by the plugin, which reads them from item compositions on the client thread.
 *
 * <p>The setting is a comma-separated list. An entry of only digits is an item id; any other entry is an item name,
 * matched case-insensitively, in which {@code *} matches any run of characters (RuneLite's {@link WildcardMatcher}, as
 * used by the core Ground Items plugin). Blank entries are skipped. A noted item matches through its unnoted item: its
 * unnoted id and name.</p>
 *
 * <p>Immutable, so a parsed list can be shared between threads.</p>
 */
final class IgnoredLoot
{
	/** Ignores nothing. */
	static final IgnoredLoot NONE = new IgnoredLoot(Collections.emptySet(), Collections.emptyList());

	private final Set<Integer> ids;
	/** Name patterns, lower-cased and trimmed. */
	private final List<String> names;

	private IgnoredLoot(Set<Integer> ids, List<String> names)
	{
		this.ids = ids;
		this.names = names;
	}

	/**
	 * Parses the setting.
	 *
	 * @param setting the comma-separated list, or {@code null}
	 */
	static IgnoredLoot parse(String setting)
	{
		if (setting == null || setting.trim().isEmpty())
		{
			return NONE;
		}
		final Set<Integer> ids = new HashSet<>();
		final List<String> names = new ArrayList<>();
		for (String raw : setting.split(","))
		{
			final String entry = raw.trim();
			if (entry.isEmpty())
			{
				continue;
			}
			if (entry.chars().allMatch(c -> c >= '0' && c <= '9'))
			{
				try
				{
					ids.add(Integer.parseInt(entry));
					continue;
				}
				catch (NumberFormatException e)
				{
					// Too large to be an item id; then it can only match a name.
				}
			}
			final String name = entry.toLowerCase(Locale.ROOT);
			if (!names.contains(name))
			{
				names.add(name);
			}
		}
		if (ids.isEmpty() && names.isEmpty())
		{
			return NONE;
		}
		return new IgnoredLoot(Collections.unmodifiableSet(ids), Collections.unmodifiableList(names));
	}

	/** Whether nothing is ignored (so no item names need to be looked up). */
	boolean isEmpty()
	{
		return ids.isEmpty() && names.isEmpty();
	}

	/** Whether any entry is a name (so item names need to be looked up). */
	boolean hasNames()
	{
		return !names.isEmpty();
	}

	/**
	 * Whether a dropped item is ignored.
	 *
	 * @param itemId         the item id as dropped (possibly noted)
	 * @param unnotedId      the unnoted item's id ({@code itemId} itself when not noted)
	 * @param unnotedName    the unnoted item's name, or {@code null} when unknown (then only ids can match)
	 */
	boolean ignores(int itemId, int unnotedId, String unnotedName)
	{
		if (ids.contains(itemId) || ids.contains(unnotedId))
		{
			return true;
		}
		if (unnotedName == null)
		{
			return false;
		}
		final String name = unnotedName.trim();
		for (String pattern : names)
		{
			if (WildcardMatcher.matches(pattern, name))
			{
				return true;
			}
		}
		return false;
	}
}
