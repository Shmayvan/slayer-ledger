package com.shmayvan.slayerledger;

import java.util.*;
import net.runelite.api.*;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ItemID;

/**
 * The style of one of the player's attacks (V2 plan 4.3), decided when its animation starts, from the animation
 * and the worn weapon. Pure: the plugin reads the animation, the worn weapon's equipment stats and the player's target
 * once per game tick and passes the numbers; ids are the RuneLite 1.13.4 gameval constants.
 *
 * <ul>
 * <li><b>Barrage</b>: the multi-target Ancient Magicks cast, {@link #BARRAGE_ANIMATIONS}: {@code ZAROS_VERTICAL_CASTING}
 * (1979) with its walking ({@code _WALKMERGE}, 10092) and priority ({@code _PRIORITY}, 7753) variants. Burst and Barrage
 * share these animations, so both count as Barrage.</li>
 * <li><b>Magic</b>: any other spell's cast, {@link #SPELL_ANIMATIONS}: Rush and Blitz ({@code ZAROS_CASTING}, 1978, and
 * {@code ZAROS_CASTING_WALKMERGE}, 10091), the standard spellbook's strike, bolt and blast, wave and surge casts (with and
 * without a staff, standing and walking), Crumble Undead, Iban Blast, Magic Dart, the god spells and the Arceuus
 * offensive spells (grasp and demonbane). A spell cast is Magic whatever weapon is worn, so a spell autocast from a
 * staff that also has melee bonuses (a staff of the dead, Ahrim's staff) is still Magic.</li>
 * <li>Any other animation: the worn weapon's style ({@link #weaponStyle}): Melee, Ranged or Magic.</li>
 * </ul>
 *
 * <p>Eating and drinking ({@code HUMAN_EAT}, 829, {@link #NOT_ATTACKS}) is never an attack. The plugin counts an
 * animation as an attack only when it starts (frame 0, as {@link ChargedWeaponUsage} counts attacks) while the player is
 * interacting with an NPC, so loading a cannon or picking an item up is not one, and only when the player gained XP in
 * one of {@link #ATTACK_XP_SKILLS} on that tick, so a block (the player's defend animation when an NPC hits them) is
 * not one either. A Burst or Barrage and a chinchompa also hit the NPCs around their target ({@link #splashes}).</p>
 */
final class AttackStyles
{
	/** The multi-target Ancient Magicks (Burst and Barrage) cast animations. */
	static final Set<Integer> BARRAGE_ANIMATIONS = ids(AnimationID.ZAROS_VERTICAL_CASTING,
		AnimationID.ZAROS_VERTICAL_CASTING_WALKMERGE, AnimationID.ZAROS_VERTICAL_CASTING_PRIORITY);

	/** Other spells' cast animations: Rush and Blitz, and the standard and Arceuus combat spells. */
	static final Set<Integer> SPELL_ANIMATIONS = ids(
		// Ancient Magicks' single-target spells (Rush and Blitz).
		AnimationID.ZAROS_CASTING, AnimationID.ZAROS_CASTING_WALKMERGE,
		// Strike, bolt and blast.
		AnimationID.HUMAN_CASTSTRIKE, AnimationID.HUMAN_CASTSTRIKE_STAFF, AnimationID.HUMAN_CASTSTRIKE_WALKMERGE,
		AnimationID.HUMAN_CASTSTRIKE_STAFF_WALKMERGE,
		// Wave (also a trident's and a Sanguinesti staff's cast) and surge.
		AnimationID.HUMAN_CASTWAVE, AnimationID.HUMAN_CASTWAVE_STAFF, AnimationID.HUMAN_CASTWAVE_WALKMERGE,
		AnimationID.HUMAN_CASTWAVE_STAFF_WALKMERGE, AnimationID.HUMAN_CAST_SURGE, AnimationID.HUMAN_CAST_SURGE_WALKMERGE,
		// Crumble Undead, Iban Blast, Magic Dart and the god spells.
		AnimationID.HUMAN_CASTCRUMBLEUNDEAD, AnimationID.HUMAN_CASTCRUMBLEUNDEAD_STAFF, AnimationID.HUMAN_CASTIBANBLAST,
		AnimationID.SLAYER_MAGICDART_CAST, AnimationID.HUMAN_CASTING,
		// Arceuus grasp and demonbane spells.
		AnimationID.HUMAN_SPELLCAST_GRASP, AnimationID.HUMAN_SPELLCAST_DEMONBANE);

	/** Animations that are never an attack: eating and drinking. */
	static final Set<Integer> NOT_ATTACKS = ids(AnimationID.HUMAN_EAT);

	/**
	 * The chinchompas (grey, red and black; RuneLite 1.13.4's gameval {@code ItemID}), whose attacks also hit up to 11
	 * or 12 NPCs in a 3x3 area around their target in multicombat (OSRS Wiki, "Black chinchompa").
	 */
	static final Set<Integer> CHINCHOMPAS = ids(ItemID.CHINCHOMPA_CAPTURED, ItemID.CHINCHOMPA_BIG_CAPTURED,
		ItemID.CHINCHOMPA_BLACK);

	/**
	 * The skills whose XP gained on an animation's first tick confirms it is an attack. Every attack of the
	 * player's that hits gains some on its own tick, before its hitsplat: Hitpoints XP for any damage dealt, with the
	 * Attack, Strength or Defence XP of melee, and a spell's base Magic XP even when it splashes. Ranged XP alone is
	 * left out: the cannon gains it on its own, with no Hitpoints XP (OSRS Wiki, "Dwarf multicannon"), while the
	 * player's own ranged attack that hits gains Hitpoints XP with it. An attack that misses gains nothing, and its 0
	 * hitsplat then gets no style. A block gains nothing either.
	 */
	static final Skill[] ATTACK_XP_SKILLS = {Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.MAGIC, Skill.HITPOINTS};

	/** No animation. */
	static final int NO_ANIMATION = -1;

	private AttackStyles()
	{
	}

	private static Set<Integer> ids(int... ids)
	{
		final Set<Integer> set = new HashSet<>();
		for (int id : ids)
		{
			set.add(id);
		}
		return Collections.unmodifiableSet(set);
	}

	/** Whether {@code animationId} may start an attack: an animation, and not eating or drinking. */
	static boolean mayBeAttack(int animationId)
	{
		return animationId != NO_ANIMATION && !NOT_ATTACKS.contains(animationId);
	}

	/** Whether {@code animationId} is a spell's cast ({@link #BARRAGE_ANIMATIONS} or {@link #SPELL_ANIMATIONS}). */
	static boolean isSpell(int animationId)
	{
		return BARRAGE_ANIMATIONS.contains(animationId) || SPELL_ANIMATIONS.contains(animationId);
	}

	/**
	 * Whether an attack starting with {@code animationId} while {@code weaponId} is wielded also hits the NPCs around its
	 * target: a Burst or Barrage cast ({@link #BARRAGE_ANIMATIONS}), or a throw of {@link #CHINCHOMPAS} (any other spell
	 * cast while holding them hits its target alone).
	 */
	static boolean splashes(int animationId, int weaponId)
	{
		return BARRAGE_ANIMATIONS.contains(animationId)
			|| (!SPELL_ANIMATIONS.contains(animationId) && CHINCHOMPAS.contains(weaponId));
	}

	/**
	 * The style of an attack starting with {@code animationId}: Barrage or Magic for a spell's cast, otherwise
	 * {@code weaponStyle}.
	 *
	 * @param weaponStyle the worn weapon's style ({@link #weaponStyle}), or {@code null} when not known
	 * @return the style, or {@code null} when neither the animation nor the weapon tells it
	 */
	static SetupStyle attackStyle(int animationId, SetupStyle weaponStyle)
	{
		if (BARRAGE_ANIMATIONS.contains(animationId))
		{
			return SetupStyle.BARRAGE;
		}
		if (SPELL_ANIMATIONS.contains(animationId))
		{
			return SetupStyle.MAGIC;
		}
		return weaponStyle;
	}

	/**
	 * A weapon's style from its equipment stats (RuneLite's {@code ItemManager.getItemStats}, whose bonuses are the OSRS
	 * Wiki's): <b>Ranged</b> when it has a ranged strength bonus, or a ranged attack bonus above each of its melee and
	 * magic attack bonuses; otherwise <b>Magic</b> when its magic attack bonus is above each of its melee and ranged
	 * ones; otherwise <b>Melee</b>.
	 *
	 * <p>So the blowpipe, bows, crossbows, darts, knives and chinchompas are Ranged (darts have no ranged attack bonus,
	 * only ranged strength), tridents and powered staves are Magic, and so are staves without melee bonuses (a kodai
	 * wand, a nightmare staff). A staff with melee bonuses above its magic one (a staff of the dead, Ahrim's or the
	 * ancient staff) is Melee: it attacks in melee unless it casts, and a cast is told by its animation
	 * ({@link #attackStyle}). Checked against the OSRS Wiki's bonuses of common Slayer weapons on 2026-11-09 (listed
	 * in {@code docs/COUNTING_RULES.md}, "Setup tags"; {@code AttackStylesTest} keeps them).</p>
	 */
	static SetupStyle weaponStyle(int stab, int slash, int crush, int magic, int range, int rangedStrength)
	{
		final int melee = Math.max(stab, Math.max(slash, crush));
		if (rangedStrength > 0 || range > Math.max(melee, magic))
		{
			return SetupStyle.RANGED;
		}
		if (magic > Math.max(melee, range))
		{
			return SetupStyle.MAGIC;
		}
		return SetupStyle.MELEE;
	}
}
