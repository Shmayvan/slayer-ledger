package com.shmayvan.slayerledger;

import java.util.*;
import lombok.*;
import net.runelite.api.gameval.*;

/**
 * Leaves out of kill loot the items a bonecrusher or ash sanctifier destroys. The game's NPC loot (ServerNpcLoot)
 * still lists bones that a bonecrusher crushed (seen in game: 39 wyrm bones for 39 wyrm kills with a bonecrusher),
 * so the plugin filters them itself. Pure: the plugin reads the inventory and worn items at each loot drop, on the
 * client thread, and passes the item ids in; nothing here touches the client.
 *
 * <p>Detection is per loot drop, because the player may take the crusher off or put it in the bank mid-task. In
 * {@link BonecrusherMode#AUTO} a bonecrusher or bonecrusher necklace anywhere in the inventory or worn excludes the
 * {@link #BONES}, and an ash sanctifier excludes the {@link #DEMONIC_ASHES}. Charges are not checked: the charge
 * varbits are not read by any core plugin, so whether the client always has them is unknown, and a wrong reading would
 * count crushed bones. Auto therefore assumes the item is charged; {@link BonecrusherMode#NEVER_EXCLUDE} covers an
 * uncharged one.</p>
 *
 * <p>Item ids are the RuneLite 1.13.6 {@code net.runelite.api.gameval.ItemID} constants; the bones and ashes are the
 * bundled {@code crushed-loot.json} ({@link BundledData}; none when it can't be read). Only unnoted ids are listed: the
 * crushers do not crush noted bones.</p>
 */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
final class CrushedLootFilter
{
	/** The bonecrusher (inventory) and the bonecrusher necklace (inventory or worn). */
	static final Set<Integer> BONECRUSHERS = ids(ItemID.BONECRUSHER, ItemID.BONECRUSHER_NECKLACE);
	static final Set<Integer> ASH_SANCTIFIERS = ids(ItemID.ASH_SANCTIFIER);

	/**
	 * Buryable bones that monsters drop, which a bonecrusher crushes. Bones only obtained otherwise (blessed bones, quest
	 * and minigame bones) are not listed; they are not kill loot.
	 */
	static final Set<Integer> BONES = BundledData.idSet("crushed-loot.json", "bones");

	/** Demonic ashes, which an ash sanctifier scatters. Plain ashes are not demonic and are always kept. */
	static final Set<Integer> DEMONIC_ASHES = BundledData.idSet("crushed-loot.json", "demonicAshes");

	/** Keeps everything. */
	static final CrushedLootFilter KEEP_ALL = new CrushedLootFilter(false, false);

	private final boolean excludeBones;
	private final boolean excludeAshes;

	/**
	 * The filter for one loot drop.
	 *
	 * @param mode           the player's "Bonecrusher handling" setting ({@code null} is treated as AUTO)
	 * @param carriedItemIds ids of every item in the inventory and worn at the time of the drop; only used in AUTO
	 */
	static CrushedLootFilter forDrop(BonecrusherMode mode, Iterable<Integer> carriedItemIds)
	{
		if (mode == BonecrusherMode.NEVER_EXCLUDE)
		{
			return KEEP_ALL;
		}
		if (mode == BonecrusherMode.ALWAYS_EXCLUDE)
		{
			return new CrushedLootFilter(true, true);
		}
		boolean crusher = false;
		boolean sanctifier = false;
		if (carriedItemIds != null)
		{
			for (Integer id : carriedItemIds)
			{
				crusher |= id != null && BONECRUSHERS.contains(id);
				sanctifier |= id != null && ASH_SANCTIFIERS.contains(id);
			}
		}
		return crusher || sanctifier ? new CrushedLootFilter(crusher, sanctifier) : KEEP_ALL;
	}

	/** Whether the mode needs the carried items at all (only AUTO does). */
	static boolean needsCarriedItems(BonecrusherMode mode)
	{
		return mode != BonecrusherMode.NEVER_EXCLUDE && mode != BonecrusherMode.ALWAYS_EXCLUDE;
	}

	/** Whether {@code itemId} counts as loot under this filter. */
	boolean keeps(int itemId)
	{
		return !(excludeBones && BONES.contains(itemId)) && !(excludeAshes && DEMONIC_ASHES.contains(itemId));
	}

	/** A copy of {@code loot} without the items this filter leaves out. */
	ItemTally apply(ItemTally loot)
	{
		ItemTally kept = new ItemTally();
		if (loot != null)
		{
			for (int id : loot.itemIds())
			{
				if (keeps(id))
				{
					kept.add(id, loot.get(id));
				}
			}
		}
		return kept;
	}

	private static Set<Integer> ids(Integer... ids)
	{
		return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(ids)));
	}
}
