package com.shmayvan.slayerledger;

import java.util.*;
import java.util.function.*;

/**
 * Works out the overlay's {@link OverlayRows.Snapshot} once per settled tick, so that drawing a frame computes
 * nothing: the task's name with its variant and cannon, its icon, the spot's short name, the setup tag sprites, the
 * session's superiors and XP, the supply runway ({@link SupplyRunway}) and the superior moment line. It reads only the
 * pure {@link SessionTracker} and what the plugin hands it, so it is tested without a client. Client thread only: the
 * plugin calls {@link #update} at the end of each settled tick and publishes the result in a volatile field.
 *
 * <p>Kept between ticks: the superiors seen per session, so the moment line shows for {@link #MOMENT_TICKS} ticks
 * (about 10 seconds) after the count goes up (the plugin's superior detection, {@link SuperiorSpawnMessage}); the
 * supplies carried earlier in the session, so one that runs out keeps its runway at none
 * ({@link SupplyRunway#withRunOut}); what the supply counting left out since the last snapshot
 * ({@link #suppliesLeftOut}); and the history's supplies per kill by supply key, worked out again only when another
 * history figure is published for the session ({@link OverlayRows.HistoricalPace}). All of it is forgotten while the
 * overlay is off ({@link #reset}), so turning it back on shows no moment for superiors that came before.</p>
 *
 * <p>Only a fall to none that was counted as use runs a supply out. After a tick the supply counting left out as a
 * whole (the bank, a deposit box or another interface that moves items, a rune pouch dropped, a death) the supplies
 * carried earlier are exactly those carried after it, so a supply banked on purpose is forgotten, not run out. After
 * a tick that left only some items out (dropped, stored in the master scroll book, loaded into or emptied from a
 * charged weapon) only their supplies are, so dropping the vial of the last prayer dose leaves prayer run out.</p>
 */
final class OverlayFeed
{
	/** How long the superior moment line shows: 17 ticks, about 10 seconds. */
	static final int MOMENT_TICKS = 17;
	private static final int NO_TICK = Integer.MIN_VALUE;

	/** The session whose superiors {@link #superiorsSeen} counts, or {@code null}. */
	private String momentSession;
	private int superiorsSeen;
	/** The tick the latest moment started on, or {@link #NO_TICK}. */
	private int momentFrom = NO_TICK;
	/** Which of its session's superiors the latest moment is about. */
	private int momentNumber;

	/** The session whose supplies {@link #carriedEarlier} holds, or {@code null}. */
	private String runwaySession;
	/** Each supply carried earlier in {@link #runwaySession}, as last carried, by supply key. */
	private final Map<String, SupplyRunway.Supply> carriedEarlier = new TreeMap<>();
	/** The supply keys carried at the last update: only these can leave on the next tick. */
	private final Set<String> carriedLast = new HashSet<>();
	/** Whether the supply counting left a whole tick out since the last {@link #update}. */
	private boolean tickLeftOut;
	/** Items whose changes the supply counting left out since the last {@link #update}. */
	private final Set<Integer> itemsLeftOut = new HashSet<>();

	/** The history figures {@link #historyByKey} was worked out from, or {@code null}. */
	private OverlayRows.HistoricalPace keyedPace;
	private Map<String, Double> historyByKey = Collections.emptyMap();

	/**
	 * Forgets the moment, the supplies carried earlier and the cached history figures: at the plugin's start, and on
	 * each tick while the overlay is off.
	 */
	void reset()
	{
		momentSession = null;
		superiorsSeen = 0;
		momentFrom = NO_TICK;
		momentNumber = 0;
		runwaySession = null;
		carriedEarlier.clear();
		carriedLast.clear();
		tickLeftOut = false;
		itemsLeftOut.clear();
		keyedPace = null;
		historyByKey = Collections.emptyMap();
	}

	/**
	 * What the supply counting left out on a tick, from the plugin's supplies diff on every tick (also those that make
	 * no snapshot, the login's settle ticks, say), kept until the next {@link #update}: a fall to none then is no
	 * run-out.
	 *
	 * @param wholeTick whether nothing was counted on the tick ({@link SupplyExclusions#isTickExcluded}: an
	 *                  interface that moves items, such as the bank or a deposit box, a rune pouch dropped, a death)
	 * @param itemIds   items whose changes were left out on their own ({@link SupplyExclusions#droppedItems},
	 *                  {@link ChargeExclusions#excludedItems}); {@code null} for none
	 */
	void suppliesLeftOut(boolean wholeTick, Collection<Integer> itemIds)
	{
		tickLeftOut |= wholeTick;
		if (itemIds != null)
		{
			itemsLeftOut.addAll(itemIds);
		}
	}

	/**
	 * The snapshot for this tick: a session's, or an assignment's waiting for its first kill or Ended (identity, size
	 * and kills left only); {@code null} with neither. First forgets the supplies whose fall the supply counting left
	 * out since the last update ({@link #suppliesLeftOut}), with or without a session.
	 *
	 * @param spots       the bundled spot table
	 * @param regionNames the player's names for map regions, for an unlisted region's spot ({@code null} for none)
	 * @param carried     what is carried now ({@link SupplyRunway#carrying}, with the blowpipe's contents), or
	 *                    {@link SupplyRunway.Carrying#UNSEEN}
	 * @param keyOf       an item's supply key ({@link SupplyTracker#supplyKey})
	 * @param pace        the history figures last published, or {@code null}
	 * @param tick        this tick's count
	 */
	OverlayRows.Snapshot update(SessionTracker tracker, SlayerSpots spots, IntFunction<String> regionNames,
		SupplyRunway.Carrying carried, IntFunction<String> keyOf, OverlayRows.HistoricalPace pace, int tick)
	{
		forgetLeftOut(keyOf);
		final SessionTracker.LiveTask live = tracker.liveTask();
		if (live == null)
		{
			final SessionTracker.Status status = tracker.getStatus();
			if (status != SessionTracker.Status.WAITING_FOR_FIRST_KILL && status != SessionTracker.Status.ENDED)
			{
				return null;
			}
			final String monster = tracker.getCurrentMonster();
			return OverlayRows.Snapshot.builder()
				.taskName(monster == null ? null : LedgerRecord.displayName(monster, tracker.getCurrentCreatureId()))
				.iconItemId(TaskIcons.itemId(monster))
				.spotName(areaSpot(spots, tracker.getCurrentContext().getLocation()))
				.assigned(tracker.getCurrentOriginalAmount())
				.remaining(tracker.getCurrentRemaining())
				.caveTask(TaskCaves.isCaveTask(monster))
				.build();
		}

		final String monster = live.getMonster();
		final TaskSelection selection = tracker.getCurrentTaskSelection().forTask(monster);
		final TaskVariants.Decision variant = tracker.getCurrentVariant().forTask(monster);
		final TaskSpot spot = tracker.getTaskSpot();
		final String spotName = spot.getSpotId() != null
			? TaskPlanText.shortSpotName(spot.getSpotId(), spots, regionNames)
			: areaSpot(spots, tracker.getSessionTaskLocation());
		final int superiors = tracker.getSuperiorsSpawned();
		final int remaining = live.getRemaining();
		return OverlayRows.Snapshot.builder()
			.sessionId(live.getSessionId())
			.taskName(monster == null ? null : OverlayRows.taskName(monster, live.getCreatureId(), selection, variant))
			.iconItemId(TaskIcons.itemId(monster))
			.spotName(spotName)
			.tags(SetupTagIcons.of(tracker.getSetupTags()))
			// The kills left are known only while the session is on the observed task, whose size this is.
			.assigned(remaining > 0 ? tracker.getCurrentOriginalAmount() : OverlayRows.UNKNOWN)
			.superiors(superiors)
			.xp(SkillXp.total(tracker.getXpGained()))
			.runway(runway(tracker, live, carried, keyOf, pace))
			.moment(moment(live.getSessionId(), superiors, tick))
			.caveTask(TaskCaves.isCaveTask(monster))
			.build();
	}

	/** The short name of the spot an assigned area (Konar's) names, or {@code null}. */
	private static String areaSpot(SlayerSpots spots, String area)
	{
		final String key = spots == null || area == null ? null : spots.spotNamed(area);
		return key == null ? null : spots.shortName(key);
	}

	/**
	 * Forgets the supplies carried earlier whose fall the supply counting left out since the last update
	 * ({@link #suppliesLeftOut}): all of them after a tick left out as a whole, else those of the items left out
	 * ({@code keyOf}: an item's supply key) that were carried at the last update. An item left out that wasn't carried
	 * then didn't leave (a charge window lists every material it could touch), so a supply that ran out earlier stays
	 * run out. The runway then remembers what is carried now, so after a tick left out as
	 * a whole the supplies carried earlier are exactly those carried after it.
	 */
	private void forgetLeftOut(IntFunction<String> keyOf)
	{
		if (tickLeftOut)
		{
			carriedEarlier.clear();
		}
		else if (keyOf != null)
		{
			for (Integer itemId : itemsLeftOut)
			{
				final String key = itemId == null ? null : keyOf.apply(itemId);
				if (key != null && carriedLast.contains(key))
				{
					carriedEarlier.remove(key);
				}
			}
		}
		tickLeftOut = false;
		itemsLeftOut.clear();
	}

	/**
	 * The runway for the session ({@link SupplyRunway#scarcest}), or {@code null}: what is carried now, with the
	 * supplies carried earlier in the session that ran out ({@link SupplyRunway#withRunOut}).
	 */
	private SupplyRunway.Warning runway(SessionTracker tracker, SessionTracker.LiveTask live,
		SupplyRunway.Carrying carried, IntFunction<String> keyOf, OverlayRows.HistoricalPace pace)
	{
		final SupplyRunway.Carrying now = carried == null ? SupplyRunway.Carrying.UNSEEN : carried;
		if (!Objects.equals(live.getSessionId(), runwaySession))
		{
			runwaySession = live.getSessionId();
			carriedEarlier.clear();
		}
		final List<SupplyRunway.Supply> supplies = SupplyRunway.withRunOut(now, carriedEarlier.values());
		carriedLast.clear();
		for (SupplyRunway.Supply supply : now.getSupplies())
		{
			if (supply.getAmount() > 0)
			{
				carriedEarlier.put(supply.getKey(), supply);
				carriedLast.add(supply.getKey());
			}
		}
		final int remaining = live.getRemaining();
		if (remaining <= 0 || supplies.isEmpty() || keyOf == null)
		{
			return null;
		}
		final int kills = live.getKills();
		// Only the rate the rule uses is worked out.
		if (kills >= SupplyRunway.MIN_TASK_KILLS)
		{
			final Map<String, Long> used = SupplyRunway.byKey(tracker.getSupplies().toMap(), keyOf);
			return SupplyRunway.scarcest(supplies, used, kills, Collections.emptyMap(), remaining);
		}
		return SupplyRunway.scarcest(supplies, Collections.emptyMap(), kills, history(live, pace, keyOf), remaining);
	}

	/** The history's supplies per kill by supply key, for {@code live}'s session; cached per published figures. */
	private Map<String, Double> history(SessionTracker.LiveTask live, OverlayRows.HistoricalPace pace,
		IntFunction<String> keyOf)
	{
		if (pace == null || !pace.belongsTo(live))
		{
			return Collections.emptyMap();
		}
		// The model publishes new figures after each kill; equal ones are not keyed again.
		if (!pace.equals(keyedPace))
		{
			keyedPace = pace;
			historyByKey = SupplyRunway.perKillByKey(pace.getSuppliesPerKill(), keyOf);
		}
		return historyByKey;
	}

	/**
	 * The moment line while it shows: {@link #MOMENT_TICKS} ticks from the tick the session's superior count went up.
	 * A session first seen with superiors already counted (restored, say) shows none for them.
	 */
	private String moment(String sessionId, int superiors, int tick)
	{
		if (sessionId == null || !sessionId.equals(momentSession))
		{
			momentSession = sessionId;
			superiorsSeen = superiors;
			momentFrom = NO_TICK;
		}
		else if (superiors > superiorsSeen)
		{
			superiorsSeen = superiors;
			momentNumber = superiors;
			momentFrom = tick;
		}
		else
		{
			superiorsSeen = superiors;
		}
		return momentFrom != NO_TICK && tick >= momentFrom && tick - momentFrom < MOMENT_TICKS
			? OverlayRows.momentText(momentNumber) : null;
	}
}
