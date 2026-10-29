package com.shmayvan.slayerledger;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.SpotanimID;

/**
 * Counts what the toxic blowpipe and the tridents use up, from attacks seen once per game tick. The client exposes
 * no live dart, scale or trident charge count, so (like established Hub plugins) this counts attacks by animation
 * and multiplies by the known per-attack cost. Pure: the plugin reads the local player and worn items on each
 * GameTick into a {@link Tick} and merges the items returned by {@link #update} into the session's supplies. Not
 * thread-safe; the plugin uses it on the client thread only.
 *
 * <p>Rules (rates from the OSRS Wiki, ids from the RuneLite 1.13.4 gameval constants):</p>
 * <ul>
 *     <li>An attack is the local player's attack animation at frame 0 while the matching weapon is worn. At most one
 *     attack counts per tick: a second {@link #update} for the same tick (or an earlier one) returns nothing.</li>
 *     <li><b>Blowpipe</b> ({@link #BLOWPIPES}, animation {@link #BLOWPIPE_ANIMATIONS}): each shot uses 2/3 of a
 *     Zulrah's scale, carried over in thirds, so three shots use two scales. Each shot also uses a fraction of a dart
 *     that depends on the worn cape-slot device (see {@link #dartLossHundredths}): none 1.00, Ava's attractor 0.40,
 *     accumulator 0.28, assembler 0.20, ranging or max cape 0.28 (0.20 with the Vorkath's head upgrade), Dizana's
 *     quiver 0.20 unless the plugin passes a verified quiver setting. Dart fractions carry over in hundredths, so 100
 *     shots with an assembler use exactly 20 darts.</li>
 *     <li>If the dart type is unknown (no check message seen yet) a shot counts its scales only, no dart fraction is
 *     carried, and {@link #isDartCheckNeeded()} turns on until a tick arrives with a known dart type. When the dart
 *     type changes, the dart carry-over is dropped: it belonged to the old darts.</li>
 *     <li><b>Tridents</b> ({@link #SEAS_TRIDENTS}, {@link #SWAMP_TRIDENTS}, charged versions only): a cast is a
 *     wave-staff animation ({@link #TRIDENT_ANIMATIONS}) at frame 0 while the plugin also sees a trident cast graphic
 *     ({@link #TRIDENT_GRAPHICS}); the animation alone is shared with ordinary wave spells. A cast uses one charge,
 *     valued at what recharging it costs: seas 1 death, 1 chaos, 5 fire runes and 10 coins; swamp 1 death, 1 chaos, 5
 *     fire runes and 1 Zulrah's scale.</li>
 * </ul>
 */
final class ChargedWeaponUsage
{
	/** No item (no dart type known, nothing worn). */
	static final int NO_ITEM = -1;

	/** Loaded toxic blowpipe and its blazing (ornament) version. */
	static final Set<Integer> BLOWPIPES = ids(ItemID.TOXIC_BLOWPIPE_LOADED, ItemID.TOXIC_BLOWPIPE_LOADED_ORNAMENT);
	/** Blowpipe attack animations (regular and ornament). */
	static final Set<Integer> BLOWPIPE_ANIMATIONS = ids(AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK,
		AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK_ORNAMENT);

	/** Charged tridents of the seas: regular (full and part charged), (e), and their (o) versions. */
	static final Set<Integer> SEAS_TRIDENTS = ids(ItemID.TOTS, ItemID.TOTS_CHARGED, ItemID.TOTS_I_CHARGED,
		ItemID.TOTS_ORN, ItemID.TOTS_CHARGED_ORN, ItemID.TOTS_I_CHARGED_ORN);
	/** Charged tridents of the swamp: regular, (e), and their (o) versions. */
	static final Set<Integer> SWAMP_TRIDENTS = ids(ItemID.TOXIC_TOTS_CHARGED, ItemID.TOXIC_TOTS_I_CHARGED,
		ItemID.TOXIC_TOTS_CHARGED_ORN, ItemID.TOXIC_TOTS_I_CHARGED_ORN);
	/** The wave-spell staff animations a trident cast plays (also used by ordinary wave spells). */
	static final Set<Integer> TRIDENT_ANIMATIONS = ids(AnimationID.HUMAN_CASTWAVE_STAFF,
		AnimationID.HUMAN_CASTWAVE_STAFF_WALKMERGE);
	/** Trident cast graphics (spotanims): seas, swamp, and their ornament versions. */
	static final Set<Integer> TRIDENT_GRAPHICS = ids(SpotanimID.SLAYER_TOTS_CASTING, SpotanimID.TOXIC_TOTS_CASTING,
		SpotanimID.SLAYER_TOTS_CASTING_ORN_LEAGUES6, SpotanimID.TOXIC_TOTS_CASTING_ORN_LEAGUES6);

	/** Ava's attractor: 40% of darts lost. */
	static final Set<Integer> ATTRACTORS = ids(ItemID.ANMA_30_REWARD);
	/** Ava's accumulator and the accumulator max cape: 28% lost. */
	static final Set<Integer> ACCUMULATORS = ids(ItemID.ANMA_50_REWARD, ItemID.SKILLCAPE_MAX_ANMA);
	/** Ava's assembler, the Masori assembler and their max capes, with the (l) versions: 20% lost. */
	static final Set<Integer> ASSEMBLERS = ids(ItemID.AVAS_ASSEMBLER, ItemID.AVAS_ASSEMBLER_TROUVER,
		ItemID.AVAS_ASSEMBLER_MASORI, ItemID.AVAS_ASSEMBLER_MASORI_TROUVER, ItemID.SKILLCAPE_MAX_ASSEMBLER,
		ItemID.SKILLCAPE_MAX_ASSEMBLER_TROUVER, ItemID.SKILLCAPE_MAX_ASSEMBLER_MASORI,
		ItemID.SKILLCAPE_MAX_ASSEMBLER_MASORI_TROUVER);
	/** The ranging cape, ranging cape(t) and max cape: 28% lost, or 20% with the Vorkath's head upgrade. */
	static final Set<Integer> RANGING_CAPES = ids(ItemID.SKILLCAPE_RANGING, ItemID.SKILLCAPE_RANGING_TRIMMED,
		ItemID.SKILLCAPE_MAX, ItemID.SKILLCAPE_MAX_WORN);
	/** Dizana's quiver (charged, uncharged and blessed) and Dizana's max cape, with the (l) versions. */
	static final Set<Integer> QUIVERS = ids(ItemID.DIZANAS_QUIVER_CHARGED, ItemID.DIZANAS_QUIVER_CHARGED_TROUVER,
		ItemID.DIZANAS_QUIVER_UNCHARGED, ItemID.DIZANAS_QUIVER_UNCHARGED_TROUVER, ItemID.DIZANAS_QUIVER_INFINITE,
		ItemID.DIZANAS_QUIVER_INFINITE_TROUVER, ItemID.SKILLCAPE_MAX_DIZANAS, ItemID.SKILLCAPE_MAX_DIZANAS_TROUVER);

	/** Hundredths of a dart lost per shot with no device. */
	static final int NO_DEVICE_HUNDREDTHS = 100;
	static final int ATTRACTOR_HUNDREDTHS = 40;
	static final int ACCUMULATOR_HUNDREDTHS = 28;
	static final int ASSEMBLER_HUNDREDTHS = 20;

	/** Thirds of a Zulrah's scale used per blowpipe shot. */
	static final int SCALE_THIRDS_PER_SHOT = 2;

	static final int FIRE_RUNES_PER_CAST = 5;
	static final int COINS_PER_SEAS_CAST = 10;

	/** Last tick handled; a second update for it (or an earlier tick) counts nothing. */
	private int lastTick = Integer.MIN_VALUE;
	/** Dart hundredths used but not yet reported as a whole dart, for {@link #carryDartId}. */
	private int dartHundredths;
	/** The dart type {@link #dartHundredths} belongs to. */
	private int carryDartId = NO_ITEM;
	/** Scale thirds used but not yet reported as a whole scale. */
	private int scaleThirds;
	private boolean dartCheckNeeded;

	/**
	 * What the plugin reads on one game tick. Ids are {@link #NO_ITEM} (or any non-matching id) when nothing is worn.
	 */
	@Value
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	static class Tick
	{
		/** The client's tick count. */
		int tick;
		/** Worn weapon item id. */
		@Builder.Default
		int weaponId = NO_ITEM;
		/** The local player's animation id (-1 for none). */
		@Builder.Default
		int animationId = -1;
		/** The local player's animation frame. */
		int animationFrame;
		/** Worn cape-slot item id (Ava's devices, ranging and max capes, Dizana's quiver). */
		@Builder.Default
		int capeId = NO_ITEM;
		/**
		 * Dizana's quiver ammo-saving setting, from varbit {@code DIZANAS_QUIVER_AMMO_SAVE} (9803), or {@code null} to
		 * ignore it and assume the quiver saves 80%. Its mapping is unverified (see {@link #quiverLossHundredths}), so
		 * the plugin should pass {@code null} until it has been checked in game.
		 */
		Integer quiverAmmoSave;
		/** Whether a trident cast graphic ({@link #TRIDENT_GRAPHICS}) is on the local player this tick. */
		boolean tridentGraphic;
		/** The "Ranging/max cape has Vorkath's head upgrade" setting. */
		boolean vorkathsHeadUpgrade;
		/** The blowpipe's dart item id from the last check message, or {@link #NO_ITEM} if unknown. */
		@Builder.Default
		int dartId = NO_ITEM;
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

	/**
	 * Hundredths of a dart lost per blowpipe shot with {@code capeId} worn.
	 *
	 * @param quiverAmmoSave      Dizana's quiver setting, or {@code null} to assume 80% saved
	 * @param vorkathsHeadUpgrade whether a ranging or max cape has the Vorkath's head upgrade (80% instead of 72%)
	 */
	static int dartLossHundredths(int capeId, Integer quiverAmmoSave, boolean vorkathsHeadUpgrade)
	{
		if (ASSEMBLERS.contains(capeId))
		{
			return ASSEMBLER_HUNDREDTHS;
		}
		if (ACCUMULATORS.contains(capeId))
		{
			return ACCUMULATOR_HUNDREDTHS;
		}
		if (ATTRACTORS.contains(capeId))
		{
			return ATTRACTOR_HUNDREDTHS;
		}
		if (RANGING_CAPES.contains(capeId))
		{
			return vorkathsHeadUpgrade ? ASSEMBLER_HUNDREDTHS : ACCUMULATOR_HUNDREDTHS;
		}
		if (QUIVERS.contains(capeId))
		{
			return quiverLossHundredths(quiverAmmoSave);
		}
		return NO_DEVICE_HUNDREDTHS;
	}

	/**
	 * Hundredths of a dart lost per shot with Dizana's quiver. The quiver can be set to save 80%, 72% or 60%. UNVERIFIED
	 * mapping, taken from the order geheur/weapon-charges offers (0 = 80%, 1 = 72%, 2 = 60%); any other value, and
	 * {@code null}, is treated as 80%. Needs an in-game check of varbit 9803 before the plugin relies on it.
	 */
	static int quiverLossHundredths(Integer quiverAmmoSave)
	{
		if (quiverAmmoSave == null)
		{
			return ASSEMBLER_HUNDREDTHS;
		}
		switch (quiverAmmoSave)
		{
			case 1:
				return ACCUMULATOR_HUNDREDTHS;
			case 2:
				return ATTRACTOR_HUNDREDTHS;
			default:
				return ASSEMBLER_HUNDREDTHS;
		}
	}

	/**
	 * Handles one game tick and returns the items used by an attack on it (item id to quantity, only whole items; empty
	 * if none). Call once per tick, in tick order.
	 */
	Map<Integer, Integer> update(Tick tick)
	{
		if (tick.getTick() <= lastTick)
		{
			return Collections.emptyMap();
		}
		lastTick = tick.getTick();

		final int dartId = tick.getDartId();
		if (dartId != NO_ITEM)
		{
			dartCheckNeeded = false;
		}
		if (dartId != carryDartId)
		{
			dartHundredths = 0;
			carryDartId = dartId;
		}

		if (tick.getAnimationFrame() != 0)
		{
			return Collections.emptyMap();
		}
		final int weaponId = tick.getWeaponId();
		final int animationId = tick.getAnimationId();
		final Map<Integer, Integer> used = new HashMap<>();
		if (BLOWPIPES.contains(weaponId) && BLOWPIPE_ANIMATIONS.contains(animationId))
		{
			blowpipeShot(tick, used);
		}
		else if (tick.isTridentGraphic() && TRIDENT_ANIMATIONS.contains(animationId))
		{
			if (SEAS_TRIDENTS.contains(weaponId))
			{
				tridentCast(used);
				add(used, ItemID.COINS, COINS_PER_SEAS_CAST);
			}
			else if (SWAMP_TRIDENTS.contains(weaponId))
			{
				tridentCast(used);
				add(used, ItemID.SNAKEBOSS_SCALE, 1);
			}
		}
		return used;
	}

	private void blowpipeShot(Tick tick, Map<Integer, Integer> used)
	{
		scaleThirds += SCALE_THIRDS_PER_SHOT;
		add(used, ItemID.SNAKEBOSS_SCALE, scaleThirds / 3);
		scaleThirds %= 3;

		if (tick.getDartId() == NO_ITEM)
		{
			dartCheckNeeded = true;
			return;
		}
		dartHundredths += dartLossHundredths(tick.getCapeId(), tick.getQuiverAmmoSave(), tick.isVorkathsHeadUpgrade());
		add(used, tick.getDartId(), dartHundredths / 100);
		dartHundredths %= 100;
	}

	private static void tridentCast(Map<Integer, Integer> used)
	{
		add(used, ItemID.DEATHRUNE, 1);
		add(used, ItemID.CHAOSRUNE, 1);
		add(used, ItemID.FIRERUNE, FIRE_RUNES_PER_CAST);
	}

	private static void add(Map<Integer, Integer> used, int itemId, int quantity)
	{
		if (quantity > 0)
		{
			used.merge(itemId, quantity, Integer::sum);
		}
	}

	/**
	 * Whether a blowpipe shot was counted without a known dart type (scales only) and no dart type has been known since.
	 * The panel can show "Check your blowpipe".
	 */
	boolean isDartCheckNeeded()
	{
		return dartCheckNeeded;
	}

	/**
	 * Forgets the fractional carry-overs, the hint and the last tick (session start or end, login, hop, plugin start:
	 * the tick count may restart).
	 */
	void reset()
	{
		lastTick = Integer.MIN_VALUE;
		dartHundredths = 0;
		carryDartId = NO_ITEM;
		scaleThirds = 0;
		dartCheckNeeded = false;
	}
}
