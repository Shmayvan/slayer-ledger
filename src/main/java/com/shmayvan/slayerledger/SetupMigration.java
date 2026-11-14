package com.shmayvan.slayerledger;

import java.util.Map;
import net.runelite.api.gameval.ItemID;

/**
 * Migrates a 1.x ledger record to the 2.0 record (V2 plan 4.3) when it is read: a record whose
 * {@link LedgerRecord#getSchemaVersion() schemaVersion} is below {@link #MIGRATED_VERSION} gets estimated setup tags and
 * the current schema version, in memory. {@link LedgerStore} calls it for every record it reads, so a record's saved
 * form changes only when the file is next written, and a record is never migrated twice. Pure (no RuneLite types; the
 * rune ids are gameval {@code ItemID} constants, plain ints).
 *
 * <p>The rules (a 1.x record has no kill contexts, so they work from what it kept):</p>
 * <ul>
 * <li><b>Cannon</b>: from the cannonballs used ({@link CannonTasks#fired}), as 1.x classified it: yes when any were
 * fired, no otherwise (also for a record saved before supplies were tracked). Estimated, because 2.0 tags a cannon task
 * only when most of its kills were cannon kills.</li>
 * <li><b>Style</b>: estimated from the XP shares of the combat skills ({@link #estimateStyle}). Melee XP is Attack and
 * Strength, plus Defence when there is any of them; Defence alone (longrange, defensive casting, or a melee Defence
 * stance) can't be told apart, so it is left out. Ranged XP is left out on a cannon task, since the cannon gives Ranged
 * XP. Magic XP is Barrage when the supplies include Ancient Magicks runes or are empty ({@link #barrageRunes}),
 * otherwise Magic. The style with at least half of that XP is the task's style; a tie between two styles, or none
 * reaching half, is Mixed. With no such XP left (no XP kept, or a cannon task done with ranged), the style is Unknown,
 * which is not an estimate.</li>
 * <li><b>Gear</b>: Unknown (1.x kept no equipment).</li>
 * </ul>
 * <p>It also gets the boss flag ({@link TaskVariants#migrate}): from its kills by NPC when it has any (a 1.x
 * record has none), else from its boss kill counts. Its variant is kept as saved: a 1.x record's is a Catacombs
 * variant's name or none, named as this version names it. Kills by NPC, the kill-context counts, overrides and the spot
 * are left as they are (none).</p>
 */
final class SetupMigration
{
	/** The record schema version that introduced the setup tags; a record below it is migrated when read. */
	static final int MIGRATED_VERSION = 3;

	private static final String ATTACK = "ATTACK";
	private static final String STRENGTH = "STRENGTH";
	private static final String DEFENCE = "DEFENCE";
	private static final String RANGED = "RANGED";
	private static final String MAGIC = "MAGIC";

	private SetupMigration()
	{
	}

	/**
	 * Migrates {@code record} in place when its schema version is below {@link #MIGRATED_VERSION}: sets its estimated
	 * tags, its boss flag and {@link LedgerRecord#CURRENT_SCHEMA_VERSION}. Leaves a 2.0 (or newer) record alone.
	 *
	 * @return whether it was migrated
	 */
	static boolean migrate(LedgerRecord record)
	{
		if (record == null || record.getSchemaVersion() >= MIGRATED_VERSION)
		{
			return false;
		}
		final boolean cannon = CannonTasks.fired(record.getSupplies());
		final SetupStyle style = estimateStyle(record.getXpGained(), record.getSupplies(), cannon);
		final SetupOverrides kept = SetupOverrides.from(record);
		SetupTags.estimated(cannon, style).withOverrides(kept).copyTo(record);
		TaskVariants.migrate(record);
		// This step's own version, so a later step added after it still runs on a record it migrated.
		record.setSchemaVersion(MIGRATED_VERSION);
		return true;
	}

	/**
	 * A 1.x task's estimated style from its XP gained per skill (see the class comment).
	 *
	 * @param xp       XP gained per skill, keyed by RuneLite's {@code Skill} names, or {@code null} when not kept
	 * @param supplies supplies used (item id to quantity), or {@code null} when not kept
	 * @param cannon   whether it was a cannon task, so its Ranged XP is left out
	 * @return the style, {@link SetupStyle#UNKNOWN} when no XP is left to estimate from
	 */
	static SetupStyle estimateStyle(Map<String, Long> xp, Map<Integer, Long> supplies, boolean cannon)
	{
		if (xp == null)
		{
			return SetupStyle.UNKNOWN;
		}
		final long attackStrength = add(xp(xp, ATTACK), xp(xp, STRENGTH));
		final long defence = xp(xp, DEFENCE);
		final long magic = xp(xp, MAGIC);
		final long melee;
		if (attackStrength > 0)
		{
			melee = add(attackStrength, defence);
		}
		else if (defence > add(xp(xp, RANGED), magic))
		{
			// A melee Defence stance (4 Defence XP per damage): longrange gives as much Ranged XP as Defence XP, and
			// defensive casting less Defence XP than Magic XP, so neither gets here.
			melee = defence;
		}
		else
		{
			melee = 0;
		}
		final long ranged = cannon ? 0 : xp(xp, RANGED);
		final long total = add(add(melee, ranged), magic);
		if (total <= 0)
		{
			return SetupStyle.UNKNOWN;
		}
		final SetupStyle magicStyle = barrageRunes(supplies) ? SetupStyle.BARRAGE : SetupStyle.MAGIC;
		final long[] shares = {melee, ranged, magic};
		final SetupStyle[] styles = {SetupStyle.MELEE, SetupStyle.RANGED, magicStyle};
		long most = 0;
		int mostIndex = -1;
		boolean tie = false;
		for (int i = 0; i < shares.length; i++)
		{
			if (shares[i] > most)
			{
				most = shares[i];
				mostIndex = i;
				tie = false;
			}
			else if (shares[i] == most && most > 0)
			{
				tie = true;
			}
		}
		// At least half (most >= total - most, without overflow), and no other style with as much.
		if (mostIndex < 0 || tie || most < total - most)
		{
			return SetupStyle.MIXED;
		}
		return styles[mostIndex];
	}

	/**
	 * Whether a task's Magic XP counts as Barrage: its supplies are empty (none kept, as on a task whose supplies were
	 * not counted) or include Ancient Magicks runes in their own proportions. Every Barrage or Blitz uses between half
	 * and twice as many blood runes as death runes; every Burst uses about twice as many chaos runes as death runes
	 * (a Rush, like a trident cast, uses as many). A trident's charges are counted as one death and one chaos rune per
	 * cast ({@link ChargedWeaponUsage}), so they don't count. A Sanguinesti staff's blood runes, or Tumeken's shadow's
	 * chaos runes, with a few death runes from thralls fall far outside both proportions, so they don't either.
	 */
	static boolean barrageRunes(Map<Integer, Long> supplies)
	{
		if (isEmpty(supplies))
		{
			return true;
		}
		final long death = quantity(supplies, ItemID.DEATHRUNE);
		final long blood = quantity(supplies, ItemID.BLOODRUNE);
		final long chaos = quantity(supplies, ItemID.CHAOSRUNE);
		if (death <= 0)
		{
			return false;
		}
		// Blood between half and twice the death runes (Barrage, Blitz); chaos between 1.5 and 2.5 times (Burst).
		final boolean bloodRatio = blood > 0 && 2 * blood >= death && blood <= 2 * death;
		final boolean chaosRatio = 2 * chaos >= 3 * death && 2 * chaos <= 5 * death;
		return bloodRatio || chaosRatio;
	}

	private static boolean isEmpty(Map<Integer, Long> supplies)
	{
		if (supplies == null)
		{
			return true;
		}
		for (Long quantity : supplies.values())
		{
			if (quantity != null && quantity > 0)
			{
				return false;
			}
		}
		return true;
	}

	private static long quantity(Map<Integer, Long> supplies, int itemId)
	{
		final Long quantity = supplies.get(itemId);
		return quantity == null ? 0 : Math.max(0, quantity);
	}

	private static long xp(Map<String, Long> xp, String skill)
	{
		final Long gained = xp.get(skill);
		return gained == null ? 0 : Math.max(0, gained);
	}

	private static long add(long a, long b)
	{
		final long sum = a + b;
		return sum < 0 ? Long.MAX_VALUE : sum;
	}
}
