package com.shmayvan.slayerledger;

import com.google.gson.*;
import com.google.inject.*;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.time.Clock;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.regex.*;
import javax.inject.Inject;
import javax.swing.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.*;
import net.runelite.client.*;
import net.runelite.client.callback.*;
import net.runelite.client.chat.*;
import net.runelite.client.config.*;
import net.runelite.client.eventbus.*;
import net.runelite.client.events.*;
import net.runelite.client.game.*;
import net.runelite.client.plugins.*;
import net.runelite.client.ui.*;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.util.*;

@Slf4j
@PluginDescriptor(
	name = "Slayer Ledger",
	description = "Tracks your Slayer tasks: kills, time, loot, supplies and XP, with task history, estimates and totals.",
	tags = {"slayer", "tracker", "task", "statistics", "loot", "xp", "supplies"},
	internalName = SlayerLedgerPlugin.INTERNAL_NAME,
	legacyDataDirectory = SlayerLedgerPlugin.LEGACY_DATA_DIRECTORY
)
public class SlayerLedgerPlugin extends Plugin
{
	/** SLAYER_MODIFIER_ID value for an amount modifier (task extension), as used by core SlayerPlugin. */
	private static final int AMOUNT_MODIFIER_ID = 2;
	/** Ticks to ignore task varps after (re)login so the server can resync them. */
	private static final int LOGIN_SETTLE_TICKS = 2;
	/** The chat line on the first settled tick after a login while a session waits for the next kill (V2 plan 4.1). */
	static final String WAITING_LINE = "Slayer Ledger: waiting for your next kill";
	/** Ticks before a task kill whose NPC deaths are paired with it (V2 plan 4.1: T - 2 to T + 1). */
	private static final int PAIR_TICKS_BEFORE = 2;
	/** Most NPC names {@link #hitNpcNames} keeps between two ticks; a tick's hits name far fewer. */
	private static final int MAX_HIT_NPC_NAMES = 64;
	/** {@link #zeroDropTick} while the task count has not dropped to zero. */
	private static final int NO_TICK = Integer.MIN_VALUE;
	/**
	 * Plugin Hub internal name; {@link #getPluginDirectory()} keeps the ledger files in
	 * {@code ~/.runelite/plugin-data/slayer-ledger}.
	 */
	static final String INTERNAL_NAME = "slayer-ledger";
	/**
	 * Directory under {@code ~/.runelite} that earlier versions used. RuneLite moves it to the plugin directory on the
	 * first {@link #getPluginDirectory()} call if the plugin directory does not exist yet.
	 */
	static final String LEGACY_DATA_DIRECTORY = "slayer-ledger";
	/** Pickpocket chat message; the server also sends NPC loot for pickpockets. Same pattern as core LootTrackerPlugin. */
	private static final Pattern PICKPOCKET_REGEX = Pattern.compile("You pick (the )?(?<target>.+)'s? pocket.*");
	/** Interfaces during (and just after) which no supplies are counted; see {@link SupplyExclusions}. */
	private static final int[] SUPPLY_EXCLUDED_INTERFACES = SupplyExclusions.excludedInterfaces();
	/** {@link #openSupplyExcludedInterface()} when none of {@link #SUPPLY_EXCLUDED_INTERFACES} is open. */
	private static final int NO_INTERFACE = -1;
	/** The live session's loot and supply values are recomputed at most once per this many game ticks. */
	private static final int SESSION_VALUE_TICKS = 2;
	/**
	 * Idle ticks the inventory is read for its potions after it changes while an item's data is still not loaded
	 * ({@link #notePotionVariants}). Such an item is a wieldable item without item stats, never a potion; one that never
	 * gets stats would otherwise keep every idle tick reading the inventory.
	 */
	static final int INVENTORY_NOTE_TRIES = 5;
	/** Every skill, in {@link Skill#ordinal()} order; the index of {@link #xpReading} and {@link #xpLastSent}. */
	private static final Skill[] SKILLS = Skill.values();
	/**
	 * Blocked-task varps (runelite-api 1.13.4 gameval). How their slots encode tasks is not documented, so they are
	 * not decoded: {@link AssignmentWatch} only checks whether any of them, or {@link #BLOCKED_TASK_VARBITS}, changed
	 * when an assignment ended (a block).
	 */
	private static final int[] BLOCKED_TASK_VARPS = {
		VarPlayerID.SLAYER_REWARDS_BLOCKED, VarPlayerID.SLAYER_REWARDS_BLOCKED_2, VarPlayerID.SLAYER_REWARDS_BLOCKED_3,
		VarPlayerID.SLAYER_REWARDS_BLOCKED_4, VarPlayerID.SLAYER_REWARDS_BLOCKED_5, VarPlayerID.SLAYER_REWARDS_BLOCKED_6,
		VarPlayerID.SLAYER_REWARDS_BLOCKED_7, VarPlayerID.SLAYER_REWARDS_BLOCKED_8, VarPlayerID.SLAYER_REWARDS_BLOCKED_9,
		VarPlayerID.SLAYER_REWARDS_BLOCKED_10, VarPlayerID.SLAYER_REWARDS_BLOCKED_11,
		VarPlayerID.SLAYER_REWARDS_BLOCKED_12, VarPlayerID.SLAYER_REWARDS_BLOCKED_13,
		VarPlayerID.SLAYER_REWARDS_BLOCKED_14, VarPlayerID.SLAYER_REWARDS_BLOCKED_DIARY_1,
		VarPlayerID.SLAYER_REWARDS_BLOCKED_DIARY_2
	};
	/**
	 * Per-master blocked-task varbits (runelite-api 1.13.4 gameval; each master has its own block list, OSRS wiki
	 * "Slayer Rewards"). Not decoded either; the {@code _OLD} ones from before per-master lists are left out.
	 */
	private static final int[] BLOCKED_TASK_VARBITS = {
		VarbitID.SLAYER_BLOCKED_TURAEL_1, VarbitID.SLAYER_BLOCKED_TURAEL_2, VarbitID.SLAYER_BLOCKED_TURAEL_3,
		VarbitID.SLAYER_BLOCKED_TURAEL_4, VarbitID.SLAYER_BLOCKED_TURAEL_5, VarbitID.SLAYER_BLOCKED_TURAEL_6,
		VarbitID.SLAYER_BLOCKED_MAZCHNA_1, VarbitID.SLAYER_BLOCKED_MAZCHNA_2, VarbitID.SLAYER_BLOCKED_MAZCHNA_3,
		VarbitID.SLAYER_BLOCKED_MAZCHNA_4, VarbitID.SLAYER_BLOCKED_MAZCHNA_5, VarbitID.SLAYER_BLOCKED_MAZCHNA_6,
		VarbitID.SLAYER_BLOCKED_VANNAKA_1, VarbitID.SLAYER_BLOCKED_VANNAKA_2, VarbitID.SLAYER_BLOCKED_VANNAKA_3,
		VarbitID.SLAYER_BLOCKED_VANNAKA_4, VarbitID.SLAYER_BLOCKED_VANNAKA_5, VarbitID.SLAYER_BLOCKED_VANNAKA_6,
		VarbitID.SLAYER_BLOCKED_CHAELDAR_1, VarbitID.SLAYER_BLOCKED_CHAELDAR_2, VarbitID.SLAYER_BLOCKED_CHAELDAR_3,
		VarbitID.SLAYER_BLOCKED_CHAELDAR_4, VarbitID.SLAYER_BLOCKED_CHAELDAR_5, VarbitID.SLAYER_BLOCKED_CHAELDAR_6,
		VarbitID.SLAYER_BLOCKED_KONAR_1, VarbitID.SLAYER_BLOCKED_KONAR_2, VarbitID.SLAYER_BLOCKED_KONAR_3,
		VarbitID.SLAYER_BLOCKED_KONAR_4, VarbitID.SLAYER_BLOCKED_KONAR_5, VarbitID.SLAYER_BLOCKED_KONAR_6,
		VarbitID.SLAYER_BLOCKED_NIEVE_1, VarbitID.SLAYER_BLOCKED_NIEVE_2, VarbitID.SLAYER_BLOCKED_NIEVE_3,
		VarbitID.SLAYER_BLOCKED_NIEVE_4, VarbitID.SLAYER_BLOCKED_NIEVE_5, VarbitID.SLAYER_BLOCKED_NIEVE_6,
		VarbitID.SLAYER_BLOCKED_DURADEL_1, VarbitID.SLAYER_BLOCKED_DURADEL_2, VarbitID.SLAYER_BLOCKED_DURADEL_3,
		VarbitID.SLAYER_BLOCKED_DURADEL_4, VarbitID.SLAYER_BLOCKED_DURADEL_5, VarbitID.SLAYER_BLOCKED_DURADEL_6,
		VarbitID.SLAYER_BLOCKED_KRYSTILIA_1, VarbitID.SLAYER_BLOCKED_KRYSTILIA_2, VarbitID.SLAYER_BLOCKED_KRYSTILIA_3,
		VarbitID.SLAYER_BLOCKED_KRYSTILIA_4, VarbitID.SLAYER_BLOCKED_KRYSTILIA_5, VarbitID.SLAYER_BLOCKED_KRYSTILIA_6,
		VarbitID.SLAYER_BLOCKED_TURAEL_DIARY, VarbitID.SLAYER_BLOCKED_MAZCHNA_DIARY,
		VarbitID.SLAYER_BLOCKED_VANNAKA_DIARY, VarbitID.SLAYER_BLOCKED_CHAELDAR_DIARY,
		VarbitID.SLAYER_BLOCKED_KONAR_DIARY, VarbitID.SLAYER_BLOCKED_NIEVE_DIARY,
		VarbitID.SLAYER_BLOCKED_DURADEL_DIARY, VarbitID.SLAYER_BLOCKED_KRYSTILIA_DIARY,
		VarbitID.SLAYER_BLOCKED_MORTIMER_1, VarbitID.SLAYER_BLOCKED_MORTIMER_2
	};
	/**
	 * RSProfile config key (in {@link SlayerLedgerConfig#GROUP}) of the blowpipe's dart item id from the last check
	 * message; unset while unknown. Kept per RuneScape profile (account), as core plugins keep per-account item state.
	 */
	static final String BLOWPIPE_DART_KEY = "blowpipeDartId";
	/**
	 * RSProfile config key prefix (in {@link SlayerLedgerConfig#GROUP}) of the player's name for a map region:
	 * {@code locationName.<regionId>}. Kept per RuneScape profile, like {@link #BLOWPIPE_DART_KEY}. Named in 1.x's
	 * naming prompt, or since 2.0 by renaming a region the spot table doesn't list.
	 */
	static final String LOCATION_NAME_KEY_PREFIX = "locationName.";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private SlayerLedgerConfig config;

	@Inject
	private Gson gson;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ItemManager itemManager;

	/** The XP lists' small skill icons ({@link #applySkillIcon}). EDT only. */
	@Inject
	private SkillIconManager skillIconManager;

	/**
	 * The setup tag sprites: {@code getSprite} on the client thread for the overlay ({@link SlayerLedgerOverlay}),
	 * {@code getSpriteAsync} from the EDT for the sidebar's, on the live card's toggles and the Task history rows
	 * ({@link #loadSetupIcon}).
	 */
	@Inject
	private SpriteManager spriteManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private Notifier notifier;

	/** Today's prices, item names and region names, with their caches; see {@link ClientLookups}. */
	@Inject
	private ClientLookups lookups;

	/** Reads the carried supplies and classifies items; see {@link SupplyReader}. */
	@Inject
	private SupplyReader supplyReader;

	/** The task and area names from the Slayer DB tables; see {@link SlayerDbLookups}. */
	@Inject
	private SlayerDbLookups dbLookups;

	/**
	 * The session tracker's clock: the system clock unless the injector binds another, an optional dependency as
	 * RuneLite's own {@code ItemManager} reads its price thresholds.
	 */
	@com.google.inject.Inject(optional = true)
	private Clock clock = Clock.systemUTC();

	private final CompletionGraceTicker completionGraceTicker = new CompletionGraceTicker();

	/**
	 * Source of the ledger directory, or {@code null} for the plugin directory ({@link #getPluginDirectory()}). Only
	 * tests set it (see {@link #setLedgerDirectorySource}), so they never touch the real RuneLite directory.
	 */
	private LedgerStore.DirectorySource ledgerDirectorySource;
	/**
	 * The bundled Slayer spot table (relative to {@link SlayerSpots}). Only tests change it (see
	 * {@link #setSpotsResource}), to start the plugin without it.
	 */
	private String spotsResource = SlayerSpots.SNAPSHOT_RESOURCE;
	/**
	 * The bundled Slayer spot table, read in {@link #startUp}; {@link SlayerSpots#NONE} when it could not be
	 * read. Immutable, so any thread may use it.
	 */
	private volatile SlayerSpots spots = SlayerSpots.NONE;
	/**
	 * EDT. Shows the Keep/Discard dialog for an unfinished session and returns whether to keep it: the panel's modal
	 * {@link SlayerLedgerPanel#confirmKeepIncomplete}. Only tests replace it (see {@link #setKeepIncompleteDialog}),
	 * so they can script the answer or leave the dialog open without showing one.
	 */
	private BiPredicate<SlayerLedgerPanel, SessionTracker.Summary> keepIncompleteDialog =
		SlayerLedgerPanel::confirmKeepIncomplete;

	// Written on the EDT (all four in startUp, panel and historyModel also in shutDown) and read on the client thread
	// and the executor, so volatile.
	private volatile SessionTracker tracker;
	private volatile LedgerStore store;
	private volatile SlayerLedgerPanel panel;
	/**
	 * The shown account's history and what is worked out from it: the plugin writes the records, entries,
	 * prices, names and settings to it, the panel observes and shows it, and it decides the advisor's plan and the
	 * overlay's historical time per kill. Used on the EDT only (see
	 * {@link LedgerHistoryModel}); the client thread only reads the reference to queue EDT work.
	 */
	private volatile LedgerHistoryModel historyModel;
	/**
	 * What the history model's live checks last depended on ({@link LedgerHistoryModel#liveInputs}), so they are asked
	 * for only when it changes; client thread only.
	 */
	private List<Object> lastLiveInputs;
	private NavigationButton navButton;
	private SlayerLedgerOverlay taskOverlay;

	// Client thread state
	private final CompletionSignal completionSignal = new CompletionSignal();
	/** Slayer points and streak of a completion, read on the same ticks as {@link #completionSignal}. */
	private final TaskRewards taskRewards = new TaskRewards();
	/**
	 * Client thread. Which completion counter is the current streak ({@link StreakMilestone}): the current task's
	 * master's, else the counter the last completion raised, kept until the next task; {@link
	 * StreakMilestone#NO_COUNTER} when neither is known. Forgotten at the login screen and in startUp.
	 */
	private int streakCounter = StreakMilestone.NO_COUNTER;
	/** Assignments that end without a session, read on the same ticks as {@link #completionSignal}. */
	private final AssignmentWatch assignmentWatch = new AssignmentWatch();
	private int settleTicks = LOGIN_SETTLE_TICKS;
	/** Tick on which a completed session was held for late loot; its grace countdown starts on the next tick. */
	private int completionHeldTick = -1;
	/** Check for a persisted active session after the next settled observation (new login or account). */
	private boolean restoreCheckNeeded;
	/**
	 * Client thread. Whether the next settled login tick (once any restore check has run) tells the player, in one chat
	 * line, that a session waits for their next kill ({@link #WAITING_LINE}). Set by a logout, a lost connection and
	 * the plugin's start; not by a hop, which is not a stop.
	 */
	private boolean waitingLineDue;
	/**
	 * Client thread. The clock time of the task kill seen on {@link #pairTick} ({@link SessionTracker#getLastKillAt()}),
	 * paired again on the next settled tick with the deaths of that tick too; {@link SessionTracker#NO_TIME} when none.
	 */
	private long pairKillAt = SessionTracker.NO_TIME;
	/** Client thread. The tick {@link #pairKillAt}'s kill was seen on. */
	private int pairTick;
	/**
	 * Client thread. The first tick {@link #pairKillAt}'s kill is paired from: 2 ticks before it, or before the drop to
	 * zero it confirms ({@link #zeroDropTick}), whichever is earlier.
	 */
	private int pairFromTick;
	/**
	 * Client thread. The tick the task count last dropped to zero, while it still reads zero: a completion on a later
	 * tick confirms that drop as the final kill, whose deaths are paired from 2 ticks before it; or
	 * {@link #NO_TICK}.
	 */
	private int zeroDropTick = NO_TICK;
	/** Client thread. The task count read on the last settled tick, or -1 when not read since the last login or hop. */
	private int lastRemainingRead = -1;
	/**
	 * Client thread. Whether a bracelet of slaughter activated since the last settled tick: a task kill that left the
	 * count unchanged ({@link SessionTracker#braceletKill}).
	 */
	private boolean slaughterKillPending;
	/**
	 * Client thread. Superior spawns whose message came while the session wasn't running but a task was assigned: the
	 * kill that spawned one may (re)start the session on the same tick or the next ({@link #noteKillTick}), and then it
	 * counts. {@link #heldSuperiorTick} is the tick of the latest one's message.
	 */
	private int heldSuperiorSpawns;
	private int heldSuperiorTick;
	/** Copy of the session persisted at the last logout; re-verified against the task after the next login. */
	private SessionSnapshot logoutSnapshot;
	// Pending restore check, deferred while the task count still reads zero after login.
	private boolean pendingFileRestore;
	private SessionSnapshot pendingReloginSnapshot;
	private int restoreWaitTicks;
	/** Incremented for every active-session load; only the latest load may clear restorePending. */
	private int loadGeneration;
	/** Tick of the last pickpocket message; server NPC loot on that tick is pickpocket loot, not a kill. */
	private int pickpocketTick = -1;
	/** Per-tick supplies diff; client thread only. */
	private SupplyTracker supplyTracker;
	/** Which ticks and dropped items are left out of supply counting. */
	private final SupplyExclusions supplyExclusions = new SupplyExclusions();
	/** A running session's ticks in a row whose supplies were not counted, for a log line; client thread only. */
	private final SkippedSupplyTicks skippedSupplyTicks = new SkippedSupplyTicks();
	/** Blowpipe and trident use, counted per attack; client thread only. */
	private final ChargedWeaponUsage chargedWeaponUsage = new ChargedWeaponUsage();
	/** Keeps loading or charging the blowpipe or a trident out of the supplies diff; client thread only. */
	private final ChargeExclusions chargeExclusions = new ChargeExclusions();
	/** NPCs the player damaged while a session ran, whose deaths are the player's kills; client thread only. */
	private final DamagedNpcs damagedNpcs = new DamagedNpcs();
	/** The player's attacks of the last few ticks, which each hit is put down to; client thread only. */
	private final PlayerAttacks playerAttacks = new PlayerAttacks();
	/** When the player's cannon last fired, for cannon kills; client thread only. */
	private final CannonFire cannonFire = new CannonFire();
	/**
	 * Client thread. Names of the NPCs the player hit since the last GameTick: activity for Away
	 * ({@link SessionTracker#taskNpcHit}) once that tick's supplies and XP are read, so they are not after it.
	 */
	private final Set<String> hitNpcNames = new LinkedHashSet<>();
	/**
	 * Client thread. The task kills counted on the last settled tick, whose setup contexts are counted on the next one
	 * ({@link #countKillContexts}), when the deaths paired with them are all known; or {@code null}.
	 */
	private KillContextsDue killContextsDue;
	/**
	 * The blowpipe's dart item id ({@link ChargedWeaponUsage#NO_ITEM} while unknown), mirrored from the RSProfile
	 * config ({@link #BLOWPIPE_DART_KEY}). Written on the client thread, and on RuneScapeProfileChanged (any thread).
	 */
	private volatile int blowpipeDartId = ChargedWeaponUsage.NO_ITEM;
	/** Whether the panel was last asked to show the "Check your blowpipe" hint; client thread only. */
	private boolean dartHintShown;
	/** What the blowpipe holds as its last check message said, less the shots since; client thread only. */
	private final BlowpipeContents blowpipeContents = new BlowpipeContents();
	/** Whether a blowpipe was worn on the last tick its shots were counted; client thread only. */
	private boolean blowpipeWorn;
	/** Zulrah's scales carried outside the blowpipe on the last supplies read; client thread only. */
	private long looseScales;
	/** Works out the overlay's snapshot each settled tick; client thread only. */
	private final OverlayFeed overlayFeed = new OverlayFeed();
	/**
	 * The overlay's snapshot of the last settled tick ({@link OverlayFeed}), or {@code null}: written on the client
	 * thread at the end of the tick, read by the overlay on the client thread every frame ({@link #overlayData()}).
	 * Immutable.
	 */
	private volatile OverlayRows.Snapshot overlaySnapshot;
	/**
	 * Whether a settled tick has reached {@link #publishOverlaySnapshot} since the plugin's start or the last logout, so
	 * the tracker holds the logged-in account's task. Until then the overlay shows nothing ({@link #overlayData()}): the
	 * last account's state (an Ended assignment, say) lasts until this account's task is read. Reset in
	 * {@code startUp} (EDT) and at a logout, set on the client thread; read by the overlay on the client thread.
	 */
	private volatile boolean overlaySettled;
	/** Which bank openings are new bank trips; client thread only. */
	private final BankTripCounter bankTripCounter = new BankTripCounter();
	/** Live session values for the panel, with unit prices cached for the session; tests may replace it. */
	private SessionValuation sessionValuation = new SessionValuation();
	/**
	 * The parsed "Ignored loot" setting. Parsed in startUp and again on each change of the setting (ConfigChanged may
	 * arrive on any thread, so it is volatile); read on the client thread when loot arrives.
	 */
	private volatile IgnoredLoot ignoredLoot = IgnoredLoot.NONE;
	/**
	 * The "Loot price" setting. Read in startUp and again when it changes (on any thread, so it is volatile); used on
	 * the client thread for the live values, and sent to the panel for the history.
	 */
	private volatile LootPriceMode lootPriceMode = LootPriceMode.GE;
	/**
	 * The "Notable drop value" setting, in gp. Read in startUp and again when it changes (on any thread, so it is
	 * volatile); used on the client thread when loot arrives.
	 */
	private volatile long notableDropThreshold = NotableDrops.DEFAULT_THRESHOLD;
	/**
	 * What Ground Items' hidden list hides from the loot, or {@link GroundItemsHidden#NONE} while the "Hide
	 * Ground Items' hidden items" setting or Ground Items is off. Read from the config in startUp and again when one of
	 * them changes (on any thread, so it is volatile); used on the client thread for the live values and the
	 * task-complete line, and sent to the history model on the EDT. Immutable.
	 */
	private volatile GroundItemsHidden groundItemsHidden = GroundItemsHidden.NONE;
	/** Which live and just-saved loot ids {@link #groundItemsHidden} hides, from the item names; client thread only. */
	private final HiddenLootResolver hiddenLoot = new HiddenLootResolver();
	/**
	 * Values last sent to the panel, or {@code null} when none are shown. They carry their session's id, so a new
	 * session is always sent, even with equal amounts.
	 */
	private volatile SessionValuation.Values publishedValues;
	/**
	 * The historical time per kill the sidebar shows for the active session's task (and location) until the live pace
	 * takes over, or {@code null} when it has no history or no session is active. Written by the history model on the
	 * EDT ({@link #publishHistoricalPace}), read by the overlay on the client thread ({@link #overlayData()});
	 * immutable.
	 */
	private volatile OverlayRows.HistoricalPace historicalPace;
	/** Tick the live session values were last computed (or tried, if that failed). */
	private int sessionValueTick = SupplyExclusions.NEVER_TICK;
	/** "Loot price" setting the live session values were last computed (or tried) in; client thread only. */
	private LootPriceMode sessionValueMode;
	/** Ground Items' hidden list the live session values were last computed (or tried) with; client thread only. */
	private GroundItemsHidden sessionValueHidden;
	/**
	 * Id of the stopped (Away, Paused or Logged out) session whose values were worked out while stopped with every
	 * price known, or {@code null}. A stopped session's supplies can't change (they are buffered) and its loot only by a
	 * paired task kill's late loot, which clears this ({@link #onServerNpcLoot}), so it is not valued again until then,
	 * until it restarts or until the "Loot price" setting changes. Client thread only.
	 */
	private String pausedValuesSession;
	/**
	 * Id of the session whose notable drop line last said {@link NotableDropMessage#PAID_FOR}, or {@code null}: the
	 * clause is said once per task ({@link #announceNotableDrop}), and a drop of another session (a new id) may say it
	 * again. Client thread only.
	 */
	private String paidForSessionId;
	/** This settled tick's XP of every skill, by {@link #SKILLS} index; reused every tick. Client thread only. */
	private final int[] xpReading = new int[SKILLS.length];
	/** The XP reading last passed to the tracker, by {@link #SKILLS} index; valid while {@link #xpSent}. */
	private final int[] xpLastSent = new int[SKILLS.length];
	/** Whether the tracker's XP baseline is {@link #xpLastSent} (see {@link #observeSkillXp}). Client thread only. */
	private boolean xpSent;
	/**
	 * Idle ticks left to read the inventory and note its potions ({@link SupplyTracker#noteVariants}) while no supplies
	 * diff runs, or 0 once noted: {@link #INVENTORY_NOTE_TRIES} when the inventory changes and on (re)login or a hop.
	 * Client thread only.
	 */
	private int inventoryNoteTries;

	// Shared between client thread and EDT
	/**
	 * False from the start of startUp until its client-thread block has reset the client-thread state. startUp runs on
	 * the EDT and only queues that block, so the plugin's client-thread handlers (registered right after startUp) may
	 * run before it; while this is false they return at once instead of using the previous run's state. The block
	 * reads the game state and account from the client itself, so nothing a skipped event carried is lost.
	 */
	private volatile boolean clientStateReady;
	private volatile boolean loggedIn;
	private volatile long accountHash = SessionTracker.NO_ACCOUNT;
	/**
	 * Account whose saved records the panel may show: the logged-in account, or {@link SessionTracker#NO_ACCOUNT}
	 * at the login screen. Written on the client thread; ledger results are applied on the EDT only while it still
	 * matches, so a stale load can never show another account's records.
	 */
	private volatile long historyAccount = SessionTracker.NO_ACCOUNT;
	/** Latest task-completion counters read on the client thread (regular, Wilderness, Mortimer). */
	private volatile int[] lastCompletionCounters;
	/**
	 * The task streak the panel's "Next bonus" line is for: the {@link #streakCounter} counter as last read on a
	 * settled tick, or {@code null} when unknown. Written on the client thread, read by the panel on the EDT.
	 */
	private volatile Integer currentTaskStreak;
	/**
	 * True while an active-session file is being loaded; no kill starts a new session and Resume is disabled, so
	 * neither races the restore.
	 */
	private volatile boolean restorePending;
	/** Session awaiting the Keep/Discard dialog; saved as partial on shutdown if still unresolved. */
	private final AtomicReference<SessionTracker.Summary> pendingPrompt = new AtomicReference<>();

	@Override
	protected void startUp()
	{
		// Until the client-thread block below has run; see clientStateReady.
		clientStateReady = false;
		tracker = new SessionTracker(clock::millis);
		tracker.setAwayMinutes(config.awayMinutes());
		// Read once per run; the tracker, the history model and the kill reads share it.
		spots = SlayerSpots.load(gson, spotsResource);
		tracker.setSpots(spots);
		// The store resolves its directory on its first operation; all of them run on the executor.
		store = new LedgerStore(ledgerDirectorySource != null ? ledgerDirectorySource : this::getPluginDirectory, gson);
		// getPluginDirectory does file IO and may move the legacy directory, so resolve it now, off the EDT, rather
		// than on the first save (possibly while the client exits). A failure is retried by the next operation.
		final LedgerStore startStore = store;
		executor.execute(() ->
		{
			try
			{
				startStore.directory();
			}
			catch (IOException e)
			{
				log.warn("Unable to open the Slayer Ledger directory; records cannot be loaded or saved", e);
			}
		});
		lootPriceMode = readLootPriceMode();
		groundItemsHidden = readGroundItemsHidden();
		// The lookups answer whichever history model is current when an answer is ready.
		lookups.setHistoryModelSource(this::historyModel);
		historyModel = new LedgerHistoryModel(tracker, lootPriceMode, lookups::requestLocationNames,
			lookups::requestCurrentPrices, lookups::requestItemNames, this::publishHistoricalPace, executor,
			SwingUtilities::invokeLater);
		historyModel.setGroundItemsHidden(groundItemsHidden);
		historyModel.setSpots(spots);
		panel = new SlayerLedgerPanel(this, tracker, historyModel);
		// startUp runs on the EDT, so the panel's sections can be set here.
		for (SlayerLedgerPanel.OptionalSection section : SlayerLedgerPanel.OptionalSection.values())
		{
			panel.setSectionShown(section, isSectionShown(section));
		}
		taskOverlay = new SlayerLedgerOverlay(this, config, itemManager, spriteManager);
		overlayManager.add(taskOverlay);

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Slayer Ledger")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		restorePending = false;
		publishedValues = null;
		historicalPace = null;
		overlaySnapshot = null;
		overlaySettled = false;
		ignoredLoot = IgnoredLoot.parse(config.ignoredLoot());
		notableDropThreshold = NotableDrops.threshold(config.notableDropThreshold());
		// Queued (startUp runs on the EDT). Client-thread state is reset here, not on the EDT, so it is only ever touched
		// on the client thread; the handlers wait for it (clientStateReady).
		clientThread.invoke(() ->
		{
			try
			{
				restoreCheckNeeded = true;
				// A session restored from the last run waits for the next kill: said once, after the login.
				waitingLineDue = true;
				supplyTracker = new SupplyTracker(supplyReader::classifyItem);
				supplyReader.reset();
				overlayFeed.reset();
				blowpipeContents.forget();
				bankTripCounter.reset();
				sessionValuation.reset();
				// Item names are looked up afresh in each run of the plugin.
				hiddenLoot.setList(GroundItemsHidden.NONE);
				streakCounter = StreakMilestone.NO_COUNTER;
				resetClientState();
				// Today's prices are looked up afresh in each run of the plugin, and so are item and location names.
				lookups.reset();
				lastLiveInputs = null;
				// Assignments are watched afresh too; one first seen now is checked against the saved records.
				assignmentWatch.forget();
				// From the client, not from GameStateChanged: one posted before this block ran was skipped.
				loggedIn = client.getGameState() == GameState.LOGGED_IN;
				if (loggedIn)
				{
					checkAccount();
					loadBlowpipeDartId();
				}
			}
			finally
			{
				// Last: the handlers may now use the client-thread state. Even after a failure here, as before this
				// guard, so one bad lookup can't leave the plugin deaf for the rest of the run.
				clientStateReady = true;
			}
		});
		// Last, so the ticker only runs once everything it uses exists. If startUp fails, RuneLite calls shutDown, which
		// unregisters it; the unregister first makes a repeated registration impossible.
		eventBus.unregister(completionGraceTicker);
		eventBus.register(completionGraceTicker);
	}

	@Override
	protected void shutDown()
	{
		// An unresolved Keep/Discard dialog is saved as partial; a completed session still held for late loot is saved
		// as completed (by endSession); an in-progress session is persisted as the account's active session (not a
		// ledger record) and restored on the next login if the task matches. A held completion is saved by endSession,
		// so the grace ticker is stopped first. RuneLite also calls this after a failed startUp, so anything may be
		// missing; the UI is always removed.
		eventBus.unregister(completionGraceTicker);
		try
		{
			if (tracker != null && store != null)
			{
				savePendingPrompt();
				// An Ended assignment has no session; its marker keeps it Ended in the next run.
				persistEndedMarker();
				endSession(SaveDecision.EndReason.SHUTDOWN);
			}
		}
		finally
		{
			if (taskOverlay != null)
			{
				overlayManager.remove(taskOverlay);
				taskOverlay = null;
			}
			if (navButton != null)
			{
				clientToolbar.removeNavigation(navButton);
			}
			if (panel != null)
			{
				panel.shutDown();
			}
			panel = null;
			if (historyModel != null)
			{
				historyModel.shutDown();
			}
			historyModel = null;
			navButton = null;
		}
		loggedIn = false;
		accountHash = SessionTracker.NO_ACCOUNT;
		historyAccount = SessionTracker.NO_ACCOUNT;
	}

	/** Test seam: answers the Keep/Discard dialog with {@code dialog} (on the EDT) instead. Call before startUp. */
	void setKeepIncompleteDialog(BiPredicate<SlayerLedgerPanel, SessionTracker.Summary> dialog)
	{
		keepIncompleteDialog = dialog;
	}

	/** Test seam: resolves the ledger directory from {@code source} instead. Call before startUp. */
	void setLedgerDirectorySource(LedgerStore.DirectorySource source)
	{
		ledgerDirectorySource = source;
	}

	/** Test seam: reads the Slayer spot table from {@code resource} instead. Call before startUp. */
	void setSpotsResource(String resource)
	{
		spotsResource = resource;
	}

	/**
	 * Binds the plugin's own helpers here, so Guice keeps them in the plugin's injector. Left to just-in-time
	 * binding they would land in RuneLite's main injector and keep this plugin's classes loaded after a Plugin Hub
	 * update or uninstall.
	 */
	@Override
	public void configure(Binder binder)
	{
		binder.bind(ClientLookups.class);
		binder.bind(SupplyReader.class);
		binder.bind(SlayerDbLookups.class);
	}

	@Provides
	SlayerLedgerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SlayerLedgerConfig.class);
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		Future<?> pending = savePendingPrompt();
		if (pending != null)
		{
			event.waitFor(pending);
		}
		Future<?> completion = flushPendingCompletion();
		if (completion != null)
		{
			event.waitFor(completion);
		}
		Future<?> marker = persistEndedMarker();
		if (marker != null)
		{
			event.waitFor(marker);
		}
		Future<?> save = endSession(SaveDecision.EndReason.SHUTDOWN);
		if (save != null)
		{
			event.waitFor(save);
		}
	}

	/**
	 * Any thread. Re-parses the "Ignored loot" setting when it changes (loot already counted is not changed), reads a
	 * new "Notable drop value" (notable drops already listed are kept), and applies a new "Loot price" setting: the
	 * live values follow on the next game tick and the history is redrawn on the EDT. A change of "Hide Ground Items'
	 * hidden items", of Ground Items' hidden or highlighted list, or Ground Items turned on or off, is applied the same
	 * way. A "Panel sections" setting shows or hides its sidebar section on the EDT.
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (isGroundItemsHiddenKey(event.getGroup(), event.getKey()))
		{
			applyGroundItemsHidden();
			return;
		}
		if (!SlayerLedgerConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if (SlayerLedgerConfig.HIDE_GROUND_ITEMS_HIDDEN.equals(event.getKey()))
		{
			applyGroundItemsHidden();
		}
		else if (SlayerLedgerConfig.IGNORED_LOOT.equals(event.getKey()))
		{
			ignoredLoot = IgnoredLoot.parse(config.ignoredLoot());
		}
		else if (SlayerLedgerConfig.NOTABLE_DROP_THRESHOLD.equals(event.getKey()))
		{
			notableDropThreshold = NotableDrops.threshold(config.notableDropThreshold());
		}
		else if (SlayerLedgerConfig.LOOT_PRICE_MODE.equals(event.getKey()))
		{
			final LootPriceMode mode = readLootPriceMode();
			lootPriceMode = mode;
			final LedgerHistoryModel m = historyModel;
			if (m != null)
			{
				SwingUtilities.invokeLater(() -> m.setLootPriceMode(mode));
			}
		}
		else if (SlayerLedgerConfig.AWAY_MINUTES.equals(event.getKey()))
		{
			// The tracker is synchronized; the next settled tick checks Away against the new limit.
			final SessionTracker t = tracker;
			if (t != null)
			{
				t.setAwayMinutes(config.awayMinutes());
			}
		}
		else
		{
			final SlayerLedgerPanel.OptionalSection section = optionalSection(event.getKey());
			final SlayerLedgerPanel p = panel;
			if (section != null && p != null)
			{
				final boolean shown = isSectionShown(section);
				SwingUtilities.invokeLater(() -> p.setSectionShown(section, shown));
			}
		}
	}

	/** The optional sidebar section a "Panel sections" setting's key shows, or {@code null} for any other key. */
	private static SlayerLedgerPanel.OptionalSection optionalSection(String key)
	{
		if (key == null)
		{
			return null;
		}
		switch (key)
		{
			case SlayerLedgerConfig.SHOW_OVERALL_TOTALS:
				return SlayerLedgerPanel.OptionalSection.OVERALL_TOTALS;
			case SlayerLedgerConfig.SHOW_SKIPPED_BLOCKED:
				return SlayerLedgerPanel.OptionalSection.SKIPPED_BLOCKED;
			default:
				return null;
		}
	}

	/** Whether the "Panel sections" settings show {@code section} in the sidebar. */
	private boolean isSectionShown(SlayerLedgerPanel.OptionalSection section)
	{
		switch (section)
		{
			case OVERALL_TOTALS:
				return config.showOverallTotals();
			default:
				return config.showSkippedBlocked();
		}
	}

	/**
	 * The "Loot price" setting, GE when unset. A saved value that is no longer a {@link LootPriceMode} (the removed
	 * "Higher of the two") also reads as GE: RuneLite's config proxy returns the item's default when it cannot parse
	 * the value.
	 */
	private LootPriceMode readLootPriceMode()
	{
		final LootPriceMode mode = config.lootPriceMode();
		return mode == null ? LootPriceMode.GE : mode;
	}

	/** Whether a config change of {@code group} and {@code key} can change what {@link #groundItemsHidden} hides. */
	private static boolean isGroundItemsHiddenKey(String group, String key)
	{
		if (GroundItemsHidden.GROUP.equals(group))
		{
			return GroundItemsHidden.HIDDEN_ITEMS_KEY.equals(key) || GroundItemsHidden.HIGHLIGHTED_ITEMS_KEY.equals(key);
		}
		return GroundItemsHidden.PLUGIN_ENABLED_GROUP.equals(group) && GroundItemsHidden.PLUGIN_ENABLED_KEY.equals(key);
	}

	/**
	 * Any thread. What Ground Items' lists hide from the loot now, read from the config (Ground Items' own
	 * settings are only read, never written; ConfigManager reads are safe on any thread).
	 */
	private GroundItemsHidden readGroundItemsHidden()
	{
		return GroundItemsHidden.effective(config.hideGroundItemsHidden(),
			configManager.getConfiguration(GroundItemsHidden.PLUGIN_ENABLED_GROUP, GroundItemsHidden.PLUGIN_ENABLED_KEY),
			configManager.getConfiguration(GroundItemsHidden.GROUP, GroundItemsHidden.HIDDEN_ITEMS_KEY),
			configManager.getConfiguration(GroundItemsHidden.GROUP, GroundItemsHidden.HIGHLIGHTED_ITEMS_KEY));
	}

	/**
	 * Any thread. Reads {@link #groundItemsHidden} again; when it changed, the live values follow on the next game tick
	 * and the history model works out the hidden loot again on the EDT. Synchronized so that concurrent
	 * {@code ConfigChanged} events read, store and hand over their lists one at a time, in order: the last list read
	 * is the one kept and the last one the model gets.
	 */
	private synchronized void applyGroundItemsHidden()
	{
		final GroundItemsHidden list = readGroundItemsHidden();
		if (list.equals(groundItemsHidden))
		{
			return;
		}
		groundItemsHidden = list;
		final LedgerHistoryModel m = historyModel;
		if (m != null)
		{
			SwingUtilities.invokeLater(() -> m.setGroundItemsHidden(list));
		}
	}

	/**
	 * Client thread. Which of {@code itemIds} {@link #groundItemsHidden} hides, from their item compositions' names (a
	 * note's is its unnoted item's, as Ground Items matches it).
	 */
	private HiddenLoot hiddenLoot(Collection<Integer> itemIds)
	{
		hiddenLoot.setList(groundItemsHidden);
		return hiddenLoot.resolve(itemIds, lookups.lootItemNames()::name);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (!clientStateReady)
		{
			return;
		}
		switch (event.getGameState())
		{
			case LOGIN_SCREEN:
				// A completed session held for late loot is saved now; it is never persisted as the active session.
				flushPendingCompletion();
				// A logout stops the session: its segment closes now, and the next kill after a login restarts it.
				tracker.loggedOut();
				waitingLineDue = true;
				cancelPendingRestore();
				// Keep the session in memory (the next kill after a re-login restarts it) but persist it in case the
				// client is closed; or an Ended assignment's marker.
				persistOnLogout();
				restoreCheckNeeded = true;
				// The next login may be a different account; never show or adopt the old task.
				tracker.clearCurrentTask();
				// The history is cleared below, so the next login's first settled tick must tell the model the task
				// again, even when nothing changed (the same account, task and place): it asks for the advisor's plan.
				lastLiveInputs = null;
				streakCounter = StreakMilestone.NO_COUNTER;
				loggedIn = false;
				resetClientState();
				blowpipeDartId = ChargedWeaponUsage.NO_ITEM;
				// The next login may be a different account, with another blowpipe and assignment.
				blowpipeContents.forget();
				overlaySnapshot = null;
				// Nothing on the overlay after the next login until its first settled tick has read that account's task.
				overlaySettled = false;
				// Hide the history while logged out; it is reloaded on the next login (any account).
				setHistoryAccount(SessionTracker.NO_ACCOUNT);
				break;
			case CONNECTION_LOST:
				// A lost connection stops the session like a logout (V2 plan 4.1); it is persisted if the login
				// screen follows.
				tracker.loggedOut();
				waitingLineDue = true;
				// fall through
			case HOPPING:
				// A hop is not a stop: time keeps running.
			case LOGGING_IN:
				// No game ticks count the grace window down while not logged in; save a held completion now.
				flushPendingCompletion();
				loggedIn = false;
				resetClientState();
				break;
			case LOGGED_IN:
				loggedIn = true;
				checkAccount();
				loadBlowpipeDartId();
				break;
			default:
				break;
		}
	}

	/**
	 * Client thread, on the first settled tick after a login (or the plugin's start), once any restore check has run.
	 * Posts one chat line ({@link #WAITING_LINE}) when a session of this account waits for the next kill: kept across
	 * the logout, or restored from the last run. Nothing is posted when there is none, when it already restarted, or
	 * when it belongs to another account.
	 */
	private void announceWaiting()
	{
		waitingLineDue = false;
		if (tracker.getStatus() == SessionTracker.Status.WAITING_FOR_NEXT_KILL
			&& tracker.getSessionAccountHash() == client.getAccountHash())
		{
			postChatLine(WAITING_LINE);
		}
	}

	/**
	 * Any thread (ConfigManager posts it from wherever the profile changed). Another RuneScape profile is active, for
	 * example a different account or world type: its blowpipe dart type is read.
	 */
	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		loadBlowpipeDartId();
		// Logging out leaves no profile; the names are kept until another profile is active.
		if (event.getNewProfile() != null)
		{
			clientThread.invoke(this::forgetLocationNames);
		}
	}

	/**
	 * Client thread. Another account or RuneScape profile is active: its region names are read afresh, and the history
	 * model forgets the names it has.
	 */
	private void forgetLocationNames()
	{
		lookups.forgetLocationNames();
		final LedgerHistoryModel m = historyModel;
		if (m != null)
		{
			SwingUtilities.invokeLater(m::clearLocationNames);
		}
	}

	/**
	 * EDT. Renames map region {@code region}, a region the spot table doesn't list (its spot is {@code region:N}),
	 * for every task there (the history's "Rename place"): the name is kept in the RSProfile config, as 1.x
	 * kept the names its prompt asked for, so it names the running session, every later task there and the saved tasks
	 * there without a name of their own. Saved files are not rewritten.
	 *
	 * @return whether the name was taken; {@code false} when nothing is left after {@link TaskLocation#normalizeName}
	 */
	boolean saveLocationName(int region, String name)
	{
		final String normalized = TaskLocation.normalizeName(name);
		if (normalized == null || !LocationTally.isRegion(region))
		{
			return false;
		}
		clientThread.invoke(() ->
		{
			lookups.saveLocationName(region, normalized);
			final LedgerHistoryModel m = historyModel;
			if (m != null)
			{
				SwingUtilities.invokeLater(() -> m.addLocationNames(Collections.singleton(region),
					Collections.singletonMap(region, normalized)));
			}
		});
		return true;
	}

	/**
	 * Client thread. Where the local player stands ({@link KillPlace#at}): the map region and the Slayer spot of the
	 * player's tile ({@link WorldPoint#fromLocalInstance}, so an instance is its template's tile and plane), or
	 * {@link KillPlace#NONE} while there is no player or scene, or while the player is on a Sailing boat (the tile is in
	 * the boat's own world view, whose template is not where the boat is). Never throws, so the task is always observed.
	 */
	private KillPlace readPlace()
	{
		try
		{
			final Player player = client.getLocalPlayer();
			final LocalPoint local = player == null ? null : player.getLocalLocation();
			final WorldView view = local == null ? null : client.getWorldView(local.getWorldView());
			if (view == null || !view.isTopLevel())
			{
				return KillPlace.NONE;
			}
			final WorldPoint point = WorldPoint.fromLocalInstance(client, local);
			return KillPlace.at(spots, point.getX(), point.getY(), point.getPlane());
		}
		catch (RuntimeException e)
		{
			log.debug("Unable to read the local player's location", e);
			return KillPlace.NONE;
		}
	}

	/** Any thread. Reads the active RuneScape profile's blowpipe dart type from the RSProfile config. */
	private void loadBlowpipeDartId()
	{
		final String saved = configManager.getRSProfileConfiguration(SlayerLedgerConfig.GROUP, BLOWPIPE_DART_KEY);
		int dartId = ChargedWeaponUsage.NO_ITEM;
		if (saved != null)
		{
			try
			{
				dartId = Integer.parseInt(saved.trim());
			}
			catch (NumberFormatException e)
			{
				log.debug("Ignoring a saved blowpipe dart type that is not an item id: {}", saved);
			}
		}
		blowpipeDartId = dartId;
	}

	/**
	 * Client thread. Remembers the blowpipe's dart type for this RuneScape profile (or forgets it, with
	 * {@link ChargedWeaponUsage#NO_ITEM}).
	 */
	private void setBlowpipeDartId(int dartId)
	{
		if (dartId == blowpipeDartId)
		{
			return;
		}
		blowpipeDartId = dartId;
		if (dartId == ChargedWeaponUsage.NO_ITEM)
		{
			configManager.unsetRSProfileConfiguration(SlayerLedgerConfig.GROUP, BLOWPIPE_DART_KEY);
		}
		else
		{
			configManager.setRSProfileConfiguration(SlayerLedgerConfig.GROUP, BLOWPIPE_DART_KEY, dartId);
		}
	}

	/**
	 * Counts down the grace window of a completed session held for late loot, and saves it when the window ends. A
	 * separate subscriber because the event bus requires handlers to be named after their event and a class can have
	 * only one {@code onGameTick(GameTick)}. Registered at a lower priority than the default-priority tick handlers, so
	 * LootManager has already posted this tick's ServerNpcLoot; the completion tick itself is not counted. Loot from the
	 * completion tick through {@link SessionTracker#COMPLETION_LOOT_GRACE_TICKS} ticks later is therefore included.
	 */
	private final class CompletionGraceTicker
	{
		/** Client thread. */
		@Subscribe(priority = -1)
		public void onGameTick(GameTick tick)
		{
			if (!clientStateReady || client.getGameState() != GameState.LOGGED_IN || client.getTickCount() == completionHeldTick)
			{
				return;
			}
			handleEndedSession(tracker.tickCompletion(), SaveDecision.EndReason.SYSTEM);
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!clientStateReady)
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		checkAccount();
		lookups.retryCurrentPrices();
		// Read before this tick's supplies and XP, so a kill seen on this tick that restarts the session counts them
		// (KillPairing.withTickStart): its own XP is read just before the kill is seen. This tick's attack is timed by it
		// too, so a fight backdated to the attack counts its XP.
		final long tickStart = tracker.now();
		// Before observing the task, so supplies used on the tick the task completes still count.
		trackSupplies();
		// The menu clicks and messages that open a charging window arrive before this tick's GameTick, so trackSupplies
		// has already left the charged materials out of this tick's diff. Weapon use comes from the attack itself, not
		// the diff, so it is counted next and valued with the rest of this tick's supplies. This tick's attack and
		// cannon shot are noted with it.
		trackChargedWeapons(tickStart);
		trackCannon();
		updateSessionValues();

		if (settleTicks > 0)
		{
			settleTicks--;
			applyHits();
			return;
		}

		// Before observing the task, so XP gained on the tick the task completes still counts, and XP of a kill that
		// restarts the session is buffered for it; a completion held for late loot is no longer running, so it gets no XP
		// after its completion tick.
		observeSkillXp();
		// After this tick's supplies, XP and attack, before the deaths are paired.
		applyHits();

		final int remaining = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
		final int[] counters = {
			client.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED),
			client.getVarbitValue(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED),
			client.getVarpValue(VarPlayerID.SLAYER_MORTIMER_TASKS_COMPLETED)
		};
		lastCompletionCounters = counters;
		final boolean completionSignaled = completionSignal.update(counters);
		final int points = client.getVarbitValue(VarbitID.SLAYER_POINTS);
		taskRewards.update(points);
		// A completed session held for late loot takes points that arrive after its completion tick.
		if (taskRewards.isFollowing() && !tracker.setCompletionRewards(taskRewards.getSessionId(), taskRewards.earned(),
			taskRewards.streak()))
		{
			// Saved (or no longer held): nothing left to update.
			taskRewards.stopFollowing();
		}

		String name = null;
		int creatureId = SessionTracker.NO_CREATURE;
		int originalAmount = SessionTracker.UNKNOWN_AMOUNT;
		TaskContext assignment = TaskContext.NONE;
		if (remaining > 0)
		{
			final int taskId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
			final int bossId = taskId == SessionTracker.BOSS_TASK_TARGET
				? client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID) : 0;
			creatureId = SessionTracker.creatureKey(taskId, bossId);
			name = dbLookups.lookupTaskName(taskId, bossId, creatureId);
			originalAmount = readOriginalAmount();
			assignment = readAssignment();
		}

		// Decided before observing, so no kill starts a new session from the first settled observation until the
		// restore ran (a new session would block the restore).
		if (restoreCheckNeeded)
		{
			restoreCheckNeeded = false;
			pendingFileRestore = accountHash != SessionTracker.NO_ACCOUNT
				&& tracker.getState() == SessionTracker.State.IDLE;
			// A session kept in memory across logout must pass the same checks as one restored from disk.
			pendingReloginSnapshot = pendingFileRestore ? null : logoutSnapshot;
			logoutSnapshot = null;
			restoreWaitTicks = 0;
			if (pendingFileRestore || pendingReloginSnapshot != null)
			{
				restorePending = true;
			}
		}

		// Read with the task, so the kills this observation counts are tallied where the player is.
		final KillPlace place = readPlace();
		final int tickCount = client.getTickCount();
		// The previous tick's kill is paired again with the deaths of that tick too (T - 2 to T + 1), and its kills'
		// setup contexts are counted with their styles.
		pairPreviousKill(tickCount);
		countKillContexts(tickCount);
		// This tick's kill, if the count dropped, is paired with the deaths from 2 ticks ago (V2 plan 4.1); they
		// backdate a (re)start and name the known task NPCs. A (re)start also counts this tick's supplies and XP.
		KillPairing pairing = damagedNpcs.pairing(tickCount - PAIR_TICKS_BEFORE, tickCount).withTickStart(tickStart);
		final boolean restoreChecking = restorePending || pendingFileRestore || pendingReloginSnapshot != null;
		if (restoreChecking)
		{
			// A new session would block the restore of the last one; an existing session still restarts.
			pairing = pairing.withStartBlocked();
		}
		final long killAtBefore = tracker.getLastKillAt();
		final SessionTracker.State stateBefore = tracker.getState();
		// No session has kills while IDLE, so this also covers a kill that starts one.
		final int killsBefore = tracker.getKills();
		final SessionTracker.ObserveResult result = tracker.observe(name, creatureId, remaining, originalAmount,
			assignment, place, completionSignaled, pairing);
		if (slaughterKillPending)
		{
			// A bracelet of slaughter kept the count: still a task kill, so activity (or a restart), with no kill counted.
			slaughterKillPending = false;
			tracker.braceletKill(pairing);
		}
		// Before a completion is held (which resets the tracker's kills), the kills this tick counted.
		final int killsCounted = Math.max(0, tracker.getKills() - killsBefore);
		// A drop to zero is the final kill only once a completion confirms it, possibly on a later tick.
		if (remaining > 0)
		{
			zeroDropTick = NO_TICK;
		}
		else if (lastRemainingRead > 0)
		{
			zeroDropTick = tickCount;
		}
		lastRemainingRead = remaining;
		noteKillTick(tickCount, killAtBefore, stateBefore);
		noteKillContexts(tickCount, killsCounted);
		switch (result)
		{
			case COMPLETED:
				log.debug("Slayer task completed");
				// The completion was signalled on this tick, so the incremented counter is this completion's streak.
				taskRewards.startCompletion(tracker.getSessionId(), counters, completionSignal.incrementedCounter());
				tracker.setCompletionRewards(taskRewards.getSessionId(), taskRewards.earned(), taskRewards.streak());
				// A running session waits COMPLETION_LOOT_GRACE_TICKS ticks for the final kill's loot before it is
				// saved (see CompletionGraceTicker). The final kill restarted a stopped session first, so it is running;
				// one that completed without a kill being seen is saved now.
				if (tracker.holdCompletion())
				{
					completionHeldTick = client.getTickCount();
				}
				else
				{
					taskRewards.stopFollowing();
					endSession(SaveDecision.EndReason.SYSTEM);
				}
				break;
			case TASK_CHANGED:
				log.debug("Slayer task changed during a session");
				// The tracker already holds the new task as "current", ready for the next session.
				endSession(SaveDecision.EndReason.SYSTEM);
				break;
			default:
				break;
		}
		// After the session's decision, so the master the tracker filled in for this task is used.
		updateTaskStreak(remaining, counters);
		// After the session's decision, so a session that took this task on this tick marks it as played.
		watchAssignments(AssignmentWatch.Reading.builder()
			.monster(name)
			.creatureId(creatureId)
			.remaining(remaining)
			.originalAmount(originalAmount)
			.context(assignment)
			.points(points)
			.counters(counters)
			.blockedValues(readBlockedTaskValues())
			.completionSignaled(completionSignaled)
			.build());
		// Jad and Zuk tasks (V2 plan 4.1): inside the Fight Caves or the Inferno with that task assigned is Running. While
		// a drop to zero waits for its completion the task has no name, so the session's is used.
		final String caveTask = name != null ? name : tracker.getSessionMonster();
		tracker.setInTaskCave(TaskCaves.isInside(caveTask, place.getRegion()), !restoreChecking);
		// After this tick's kills, which are activity, and the cave, inside which Away never starts.
		tracker.checkAway();

		if (pendingFileRestore || pendingReloginSnapshot != null)
		{
			runPendingRestoreCheck(remaining, counters);
		}
		// After the restore check (a file restore is applied when its load comes back) and this tick's task decision,
		// so a session that one of them restored is told about and one they ended or restarted gets no line.
		if (waitingLineDue && !restorePending && !pendingFileRestore && pendingReloginSnapshot == null)
		{
			announceWaiting();
		}
		// Last, so a session this tick started, restored or ended is seen.
		refreshHistoryModelIfLiveChanged();
		publishOverlaySnapshot(tickCount);
	}

	/**
	 * Client thread, at the end of each settled tick. Works out the overlay's snapshot ({@link OverlayFeed}): the
	 * identity, superiors, XP, the supply runway and the superior moment, so the overlay computes nothing while it draws
	 *. The runway reads the supplies the diff saw this tick ({@link SupplyTracker#carried}; none seen without its
	 * baseline) with what a worn blowpipe holds, the session's use, and the history's use per kill published with its
	 * time per kill. Nothing while the overlay is off, and the feed forgets what it kept between ticks, so turning the
	 * overlay back on shows no moment for a superior that came while it was off. Either way the overlay may show again
	 * ({@link #overlaySettled}): this tick has read the logged-in account's task.
	 */
	private void publishOverlaySnapshot(int tick)
	{
		if (!config.showTaskOverlay() || supplyTracker == null)
		{
			overlaySnapshot = null;
			overlayFeed.reset();
			overlaySettled = true;
			return;
		}
		final SupplyRunway.Carrying carried = SupplyRunway.carrying(SupplyRunway.carried(supplyTracker.carried()),
			supplyTracker.hasBaseline(), blowpipeWorn, blowpipeContents, looseScales);
		overlaySnapshot = overlayFeed.update(tracker, spots, lookups::locationName, carried, supplyTracker::supplyKey,
			historicalPace, tick);
		overlaySettled = true;
	}

	/**
	 * Client thread, each settled tick before the task is observed. A task kill seen on an earlier tick is paired again
	 * with the deaths from 2 ticks before it to 1 tick after ({@link SessionTracker#pairKill}): an NPC can die on the
	 * tick after the count dropped, and it may still backdate the restart the kill caused. When that kill completed the
	 * task, its session is held for late loot and no longer runs, so a death paired with it on the tick after was
	 * refused as it died: it is tallied into the held session now ({@link SessionTracker#addHeldNpcKill}).
	 */
	private void pairPreviousKill(int tick)
	{
		if (pairKillAt == SessionTracker.NO_TIME || tick <= pairTick)
		{
			return;
		}
		final long killAt = pairKillAt;
		pairKillAt = SessionTracker.NO_TIME;
		tracker.pairKill(killAt, damagedNpcs.pairing(pairFromTick, pairTick + 1));
		damagedNpcs.tallyUntallied(pairFromTick, pairTick + 1, name -> tracker.addHeldNpcKill(killAt, name));
	}

	/**
	 * Client thread, after this tick's task decision. Remembers this tick's task kill, if there was one, for
	 * {@link #pairPreviousKill} on the next tick: its deaths are paired from 2 ticks before it, or before the drop to
	 * zero a completion on this tick confirms ({@link #zeroDropTick}). When the kill started or restarted the
	 * session:
	 * <ul>
	 * <li>tallies the deaths paired with it so far (from that tick to this one), which died before the session ran and
	 * so were refused then ({@link DamagedNpcs#tallyUntallied}; a death on T + 1 is tallied as it dies, and none
	 * twice);</li>
	 * <li>counts the superior spawns held since the tick before ({@link #heldSuperiorSpawns}).</li>
	 * </ul>
	 * Held superior spawns are dropped after the tick after their message. The supplies diff runs while the task is
	 * assigned, so a (re)start needs no new baseline: the tracker counts its warm-up from the buffer.
	 */
	private void noteKillTick(int tick, long killAtBefore, SessionTracker.State stateBefore)
	{
		final long killAt = tracker.getLastKillAt();
		final int from = (zeroDropTick != NO_TICK ? Math.min(tick, zeroDropTick) : tick) - PAIR_TICKS_BEFORE;
		if (killAt != killAtBefore)
		{
			pairKillAt = killAt;
			pairTick = tick;
			pairFromTick = from;
		}
		if (stateBefore != SessionTracker.State.RUNNING && tracker.getState() == SessionTracker.State.RUNNING)
		{
			damagedNpcs.tallyUntallied(from, tick, tracker::addNpcKill);
			while (heldSuperiorSpawns > 0)
			{
				tracker.addSuperiorSpawn();
				heldSuperiorSpawns--;
			}
		}
		if (heldSuperiorTick < tick)
		{
			// Held since an earlier tick, so this tick was its last chance: no kill of the session spawned it.
			heldSuperiorSpawns = 0;
		}
	}

	/**
	 * The task kills one settled tick counted, waiting for the next one, when the deaths paired with them are all
	 * known, to be counted as setup contexts ({@link SlayerLedgerPlugin#countKillContexts}). What is read on the kill's
	 * own tick (the cannon and the worn gear) is kept with them.
	 */
	@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
	private static final class KillContextsDue
	{
		/** The kills' clock time ({@link SessionTracker#getLastKillAt()} just after them). */
		final long killAt;
		/** The tick they were counted on. */
		final int tick;
		/** The first tick their deaths are paired from (as {@link SlayerLedgerPlugin#pairFromTick}). */
		final int fromTick;
		final int kills;
		final boolean cannon;
		final Integer prayerBonus;
	}

	/**
	 * Client thread, after this tick's task decision. When the tick counted task kills, keeps them for
	 * {@link #countKillContexts} on the next settled tick, with what only this tick can tell: whether the cannon
	 * fired in the last {@link CannonFire#FIRE_WINDOW_TICKS} ticks, and the worn prayer bonus ({@link #readPrayerBonus}).
	 * Their deaths are paired from 2 ticks before the kill, or before the drop to zero a completion on this tick
	 * confirms, as {@link #noteKillTick} pairs them.
	 */
	private void noteKillContexts(int tick, int kills)
	{
		if (kills <= 0)
		{
			return;
		}
		final int from = (zeroDropTick != NO_TICK ? Math.min(tick, zeroDropTick) : tick) - PAIR_TICKS_BEFORE;
		killContextsDue = new KillContextsDue(tracker.getLastKillAt(), tick, from, kills, cannonFire.firedWithin(tick),
			readPrayerBonus());
	}

	/**
	 * Client thread, each settled tick before the task is observed. Counts the setup contexts of the kills the previous
	 * settled tick counted ({@link SessionTracker#addKillContext}): each with the cannon and gear focus of its
	 * tick, and the style of one of the deaths paired with them, from 2 ticks before to 1 tick after
	 * ({@link DamagedNpcs#takeStyles}); kills with no such death (a cannon's, say) have no style. A completion held for
	 * late loot takes its final kill's contexts too.
	 */
	private void countKillContexts(int tick)
	{
		final KillContextsDue due = killContextsDue;
		if (due == null || tick <= due.tick)
		{
			return;
		}
		killContextsDue = null;
		final List<SetupStyle> styles = damagedNpcs.takeStyles(due.fromTick, due.tick + 1, due.kills);
		final GearFocus gear = due.prayerBonus == null ? null : SetupDetection.gearFocus(due.prayerBonus);
		for (int i = 0; i < due.kills; i++)
		{
			tracker.addKillContext(due.killAt, due.cannon, i < styles.size() ? styles.get(i) : null, gear,
				due.prayerBonus);
		}
	}

	/**
	 * Client thread. The total prayer bonus of the worn items, from their equipment stats
	 * ({@code ItemManager.getItemStats}, RuneLite's copy of the OSRS Wiki's bonuses), for a kill's gear focus;
	 * {@code null} when nothing worn has stats (none worn, or the stats not loaded yet). A worn item without stats adds
	 * nothing.
	 */
	private Integer readPrayerBonus()
	{
		final ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (worn == null)
		{
			return null;
		}
		int total = 0;
		boolean known = false;
		for (Item item : worn.getItems())
		{
			if (item == null || item.getId() < 0)
			{
				continue;
			}
			final ItemStats stats = itemManager.getItemStats(item.getId());
			if (stats != null && stats.getEquipment() != null)
			{
				total += stats.getEquipment().getPrayer();
				known = true;
			}
		}
		return known ? total : null;
	}

	/**
	 * Client thread, each tick after its supplies and XP are read: puts this tick's hits (and any left from the
	 * settling ticks) down to the player's attacks ({@link DamagedNpcs#applyHits}), and makes the hits on the
	 * session's known task NPCs activity for Away ({@link SessionTracker#taskNpcHit}) now, so this tick's supplies and
	 * XP are not after the last activity.
	 */
	private void applyHits()
	{
		damagedNpcs.applyHits(playerAttacks);
		for (String name : hitNpcNames)
		{
			tracker.taskNpcHit(name);
		}
		hitNpcNames.clear();
	}

	/**
	 * Client thread, every settled tick and after a loaded active session is applied ({@link #applyActiveSession},
	 * which runs between ticks). When the task, its master, its kills left, its location or the session changed
	 * ({@link LedgerHistoryModel#liveInputs}), asks the history model on the EDT to ask for the advisor's plan and
	 * publish the overlay's historical time per kill; otherwise, while the model waits for today's prices, asks it to
	 * end that wait once it has run out. So neither waits for the panel's refresh timer, and a new kills left asks for
	 * a new plan at most once a tick.
	 */
	private void refreshHistoryModelIfLiveChanged()
	{
		final List<Object> inputs = LedgerHistoryModel.liveInputs(tracker);
		final LedgerHistoryModel m = historyModel;
		if (!inputs.equals(lastLiveInputs))
		{
			lastLiveInputs = inputs;
			if (m != null)
			{
				SwingUtilities.invokeLater(m::refreshLive);
			}
		}
		else if (m != null && m.isWaitingForCurrentPrices())
		{
			SwingUtilities.invokeLater(m::endExpiredCurrentPricesWait);
		}
	}

	/** Test seam: the history model, or {@code null} while the plugin is stopped. Use it on the EDT. */
	LedgerHistoryModel historyModel()
	{
		return historyModel;
	}

	/**
	 * Test seam: replaces (or, with {@code null}, unsets) the history model, so a test can lose the answers the client
	 * thread would send it.
	 */
	void setHistoryModel(LedgerHistoryModel model)
	{
		historyModel = model;
	}

	/** Test seam: the session tracker, or {@code null} before the plugin first starts. */
	SessionTracker tracker()
	{
		return tracker;
	}

	/** Test seam: stands {@code valuation} in for the live session valuation. Call on the client thread. */
	void setSessionValuation(SessionValuation valuation)
	{
		sessionValuation = valuation;
	}

	/** EDT, after a panel action changed the session: the history model's live checks run at once. */
	private void refreshHistoryModel()
	{
		final LedgerHistoryModel m = historyModel;
		if (m != null)
		{
			m.refreshLive();
		}
	}

	/**
	 * Client thread, every settled tick, whether or not a session is running (only varps and varbits are read). Saves
	 * each assignment that ended without a session; see {@link AssignmentWatch}.
	 */
	private void watchAssignments(AssignmentWatch.Reading reading)
	{
		final List<AssignmentWatch.Ended> ended = assignmentWatch.update(reading, tracker.takeSessionTaskKeys(),
			System.currentTimeMillis());
		final long account = accountHash;
		if (account == SessionTracker.NO_ACCOUNT)
		{
			return;
		}
		for (AssignmentWatch.Ended e : ended)
		{
			log.debug("Slayer assignment ended without a session: {}", e.getEntry().getOutcome());
			executor.execute(() ->
			{
				try
				{
					publishAssignments(account, store.appendAssignment(account, e.getEntry(), e.isCheckRecords()));
				}
				catch (IOException | RuntimeException ex)
				{
					log.warn("Unable to save a Slayer Ledger assignment entry", ex);
				}
			});
		}
	}

	/**
	 * Client thread, every settled tick. Publishes the streak for the panel's "Next bonus" line: the counter of
	 * the current task's master while there is a task; right after a completion the counter it raised, so the line
	 * shows the new streak until the next task. Unknown (hidden) while the master is unknown or not in the table.
	 */
	private void updateTaskStreak(int remaining, int[] counters)
	{
		if (remaining > 0)
		{
			streakCounter = StreakMilestone.counterFor(tracker.getCurrentContext().getMaster());
		}
		else if (completionSignal.incrementedCounter() != CompletionSignal.NO_COUNTER)
		{
			streakCounter = completionSignal.incrementedCounter();
		}
		currentTaskStreak = streakCounter >= 0 && streakCounter < counters.length ? counters[streakCounter] : null;
	}

	/**
	 * The current task streak for the panel's "Next bonus" line, or {@code null} when unknown (not logged in, no
	 * settled reading yet, no task or completion seen since logging in, or the master is unknown). Any thread.
	 */
	Integer currentTaskStreak()
	{
		return currentTaskStreak;
	}

	/** Client thread. The blocked-task varps then varbits, in a fixed order. */
	private int[] readBlockedTaskValues()
	{
		final int[] values = new int[BLOCKED_TASK_VARPS.length + BLOCKED_TASK_VARBITS.length];
		int i = 0;
		for (int varp : BLOCKED_TASK_VARPS)
		{
			values[i++] = client.getVarpValue(varp);
		}
		for (int varbit : BLOCKED_TASK_VARBITS)
		{
			values[i++] = client.getVarbitValue(varbit);
		}
		return values;
	}

	/**
	 * Client thread. NPC kill loot from the game's own loot tracker, the event core LootTrackerPlugin (1.13.3) uses for
	 * NPC loot. Where it goes, with the name of the NPC it came from, is decided by
	 * {@link SessionTracker#addLoot(ItemTally, List, String)}: the running session (or a just-completed one during its
	 * late-loot grace window), a stopped session when it is a known task NPC's (a paired task kill's late loot), or the
	 * buffer outside segments while a task is assigned. Loot from any NPC counts, not only the task monster. Bones and
	 * demonic ashes a bonecrusher or ash sanctifier destroyed are left out ({@link CrushedLootFilter}), judged by what is
	 * carried at the time of this drop, and so are the player's ignored items ({@link IgnoredLoot}); see
	 * {@link LootFilter}. The kept loot's notable stacks ({@link NotableDrops}) go with it; they are only priced while
	 * loot may be kept ({@link SessionTracker#isRecordingLoot}), so no price lookups run while no task is assigned.
	 * Araxxor's harvest after the task its kill completed was saved goes to that task's record instead
	 * ({@link SessionTracker#takeHarvestRecord}, {@link #addHarvestToRecord}). Notable stacks a session takes get
	 * the notable drop line ({@link #announceNotableDrop}).
	 */
	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		if (!clientStateReady)
		{
			return;
		}
		if (pickpocketTick == client.getTickCount())
		{
			// The server sends NPC loot for pickpockets too; like core LootTracker, ignore it.
			return;
		}
		final ItemTally items = new ItemTally();
		for (ItemStack stack : event.getItems())
		{
			items.add(stack.getId(), stack.getQuantity());
		}
		final ItemTally kept = new LootFilter(crushedLootFilter(), ignoredLoot).apply(items, lookups.lootItemNames());
		final NPCComposition npc = event.getComposition();
		final String npcName = npc == null ? null : npc.getName();
		final long account = accountHash;
		final String harvestRecord = tracker.takeHarvestRecord(account, npcName);
		if (harvestRecord != null)
		{
			addHarvestToRecord(account, harvestRecord, kept);
			return;
		}
		// Priced only while recording. A Resume click between the check and addLoot records this kill's loot without its
		// notable stacks (at most one drop's entries); addLoot still decides loot and drops in one step. Passed on even
		// with nothing kept: Araxxor's loot is its harvest whatever it kept, so no harvest window opens after it.
		final boolean priced = !kept.isEmpty() && tracker.isRecordingLoot();
		final List<NotableDrop> drops = priced ? findNotableDrops(kept) : null;
		final boolean added = tracker.addLoot(kept, drops, npcName);
		if (added && tracker.getState().isClockStopped())
		{
			// A stopped session took a paired task kill's late loot: valued again (see updateSessionValues).
			pausedValuesSession = null;
		}
		if (added)
		{
			announceNotableDrop(drops, npcName);
		}
	}

	/**
	 * Client thread. Adds Araxxor's harvest that came after the task its kill completed was saved to that task's record
	 *, as if it had come before the save: its items are priced now, as a save prices a record
	 * ({@link LedgerRecord#captureValues}), and its notable stacks found at the "Loot price" setting, as for any loot;
	 * {@link LedgerStore#addLoot} adds them on the executor, after the record's own save, which the client thread queued
	 * there first. The ledger is then reloaded, like the note and setup edits ({@link #editRecordNote}). The task-complete
	 * chat line went out with the save, and this loot gets no notable drop line: the task it is added to has ended.
	 */
	private void addHarvestToRecord(long account, String id, ItemTally loot)
	{
		if (loot.isEmpty())
		{
			return;
		}
		final ItemValuation.UnitPrices prices = new ItemValuation.UnitPrices();
		try
		{
			ItemValuation.capturePrices(loot, lookups.priceLookup(), prices);
		}
		catch (RuntimeException e)
		{
			log.warn("Unable to price Araxxor's loot for Slayer Ledger record {}", id, e);
		}
		final List<NotableDrop> drops = findNotableDrops(loot, prices);
		executor.execute(() ->
		{
			boolean failed = false;
			try
			{
				store.addLoot(account, id, loot, prices, drops);
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Unable to add Araxxor's loot to Slayer Ledger record {}", id, e);
				failed = true;
			}
			// Reload either way so the panel reflects what is actually on disk.
			reloadLedger(account);
			if (failed)
			{
				// Posted after the reload's update, so the refresh does not immediately clear it.
				publishHistoryError(account, SlayerLedgerPanel.HARVEST_FAILED_MESSAGE);
			}
		});
	}

	/**
	 * Client thread. The notable stacks of one kill's kept loot, valued now at the "Loot price" setting through
	 * {@code ItemManager} (see {@link NotableDrops}); none when the lookup fails.
	 */
	private List<NotableDrop> findNotableDrops(ItemTally loot)
	{
		if (loot == null || loot.isEmpty())
		{
			return null;
		}
		try
		{
			final ItemValuation.UnitPrices prices = new ItemValuation.UnitPrices();
			ItemValuation.capturePrices(loot, lookups.priceLookup(), prices);
			return findNotableDrops(loot, prices);
		}
		catch (RuntimeException e)
		{
			log.debug("Unable to value loot for notable drops", e);
			return null;
		}
	}

	/**
	 * Client thread. The notable stacks of one kill's kept loot at its unit prices, at the "Loot price" setting (see
	 * {@link NotableDrops}); none when that fails.
	 */
	private List<NotableDrop> findNotableDrops(ItemTally loot, ItemValuation.UnitPrices prices)
	{
		try
		{
			return NotableDrops.find(loot, prices, lootPriceMode, notableDropThreshold, this::lootItemName,
				System.currentTimeMillis());
		}
		catch (RuntimeException e)
		{
			log.debug("Unable to value loot for notable drops", e);
			return null;
		}
	}

	/**
	 * Client thread. Posts the notable drop line ({@link NotableDropMessage}) for one kill's notable stacks that a
	 * session took (not buffered loot), unless the "Notable drop line" setting is off: the most valuable item that
	 * Ground Items' hidden list doesn't hide ({@link #withoutHiddenDrops}; hidden coins still count), with the task's
	 * kill number while its kills left are known (the overlay bar's kills done of the assigned size). It says the task
	 * is paid for only when the session's supply cost so far, as last valued ({@link #publishedValues}, at most
	 * {@link #SESSION_VALUE_TICKS} ticks old), is known exactly and the drop's value covers it, and only once per task
	 * ({@link #paidForSessionId}).
	 */
	private void announceNotableDrop(List<NotableDrop> drops, String npcName)
	{
		if (drops == null || drops.isEmpty() || !config.notableDropLine())
		{
			return;
		}
		final LootPriceMode mode = lootPriceMode;
		final NotableDrop drop = NotableDropMessage.pick(withoutHiddenDrops(drops), mode);
		if (drop == null)
		{
			return;
		}
		int killNumber = 0;
		int taskSize = 0;
		final SessionTracker.LiveTask live = tracker.liveTask();
		final int assigned = tracker.getCurrentOriginalAmount();
		if (live != null && live.getRemaining() > 0 && assigned >= live.getRemaining())
		{
			killNumber = assigned - live.getRemaining();
			taskSize = assigned;
		}
		final SessionValuation.Values values = publishedValues;
		final Long suppliesCost = values != null && values.getSuppliesUnpriced() == 0 && live != null
			&& Objects.equals(values.getSessionId(), live.getSessionId()) ? values.getSuppliesGe() : null;
		final Long value = drop.value(mode);
		final String sessionId = live == null ? null : live.getSessionId();
		final boolean paidFor = value != null && sessionId != null && !sessionId.equals(paidForSessionId)
			&& NotableDropMessage.paysForTask(value, suppliesCost);
		final String message = NotableDropMessage.chatMessage(drop, mode, npcName, killNumber, taskSize, paidFor);
		if (message != null)
		{
			postChatMessage(message);
			if (paidFor)
			{
				paidForSessionId = sessionId;
			}
		}
	}

	/**
	 * Client thread. {@code drops} without the item stacks Ground Items' hidden list hides from the loot value
	 * ({@link #hiddenLoot}, {@link HiddenLoot#leavesOutAny}): every hidden item but coins, which always count. Pets are
	 * kept. {@code drops} itself when none is left out.
	 */
	private List<NotableDrop> withoutHiddenDrops(List<NotableDrop> drops)
	{
		final List<Integer> ids = new ArrayList<>();
		for (NotableDrop drop : drops)
		{
			if (drop != null && drop.getItemId() != null)
			{
				ids.add(drop.getItemId());
			}
		}
		final HiddenLoot hidden = hiddenLoot(ids);
		if (!hidden.leavesOutAny(ids))
		{
			return drops;
		}
		final List<NotableDrop> shown = new ArrayList<>(drops.size());
		for (NotableDrop drop : drops)
		{
			if (drop == null || drop.getItemId() == null
				|| !hidden.leavesOutAny(Collections.singletonList(drop.getItemId())))
			{
				shown.add(drop);
			}
		}
		return shown;
	}

	/** Client thread. An item's name for a notable drop, or {@code null} when the lookup fails. */
	private String lootItemName(int itemId)
	{
		try
		{
			return lookups.lootItemNames().name(itemId);
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/**
	 * Client thread. The bonecrusher filter for a loot drop now: in Auto mode, from the items in the inventory and worn
	 * right now (the player may switch the crusher on and off mid-task).
	 */
	private CrushedLootFilter crushedLootFilter()
	{
		final BonecrusherMode mode = config.bonecrusherHandling();
		if (!CrushedLootFilter.needsCarriedItems(mode))
		{
			return CrushedLootFilter.forDrop(mode, null);
		}
		final List<Integer> carried = new ArrayList<>();
		for (int containerId : new int[]{InventoryID.INV, InventoryID.WORN})
		{
			final ItemContainer container = client.getItemContainer(containerId);
			if (container != null)
			{
				for (Item item : container.getItems())
				{
					carried.add(item.getId());
				}
			}
		}
		return CrushedLootFilter.forDrop(mode, carried);
	}

	/**
	 * Client thread. Remembers the tick of a pickpocket so its server NPC loot is not counted as kill loot, counts
	 * superior spawns ({@link SuperiorSpawnMessage}; RUNNING only, see {@link SessionTracker#addSuperiorSpawn}, or held
	 * for a kill that (re)starts the session on this tick or the next, see {@link #heldSuperiorSpawns}), counts
	 * a Slayer bracelet charge as a supply ({@link SlayerBracelets}; as any supply, see
	 * {@link SessionTracker#addSupplies}), learns the blowpipe's dart type
	 * from its check message ({@link BlowpipeMessage}), lets a charging message close a
	 * {@link ChargeExclusions} window, adds a pet drop ({@link PetMessage}) to the notable drops (by the loot rules,
	 * see {@link SessionTracker#addPet}), and counts a boss kill count message ({@link KillCountMessage}; by the loot
	 * rules and {@link BossKillCounts}, see {@link SessionTracker#addBossKillCount}).
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!clientStateReady)
		{
			return;
		}
		final ChatMessageType type = event.getType();
		// The chat types core ScreenshotPlugin (pets) and ChatCommandsPlugin (kill counts) check.
		final boolean notificationType = type == ChatMessageType.GAMEMESSAGE || type == ChatMessageType.SPAM
			|| type == ChatMessageType.TRADE || type == ChatMessageType.FRIENDSCHATNOTIFICATION;
		final NotableDrop.Pet pet = PetMessage.parse(notificationType, event.getMessage());
		if (pet != null)
		{
			tracker.addPet(NotableDrop.pet(pet, System.currentTimeMillis()));
		}
		final KillCountMessage.Result killCount = KillCountMessage.parse(notificationType, event.getMessage());
		if (killCount != null && killCount.isBossKill())
		{
			tracker.addBossKillCount(killCount.getBoss(), killCount.getKc());
		}
		if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM && type != ChatMessageType.MESBOX)
		{
			return;
		}
		if (PICKPOCKET_REGEX.matcher(GameMessageText.plain(event.getMessage())).matches())
		{
			pickpocketTick = client.getTickCount();
		}
		if (SuperiorSpawnMessage.matches(type != ChatMessageType.MESBOX, event.getMessage())
			&& !tracker.addSuperiorSpawn() && tracker.getCurrentRemaining() > 0)
		{
			// The kill that spawned it may (re)start the session on this tick's GameTick or the next (noteKillTick).
			heldSuperiorSpawns++;
			heldSuperiorTick = client.getTickCount();
		}
		final int bracelet = type != ChatMessageType.MESBOX ? SlayerBracelets.chargeUsed(event.getMessage())
			: SlayerBracelets.NONE;
		if (bracelet != SlayerBracelets.NONE)
		{
			// One charge of the worn bracelet: counted while RUNNING, else buffered while a task is assigned
			// (SessionTracker.addSupplies).
			final ItemTally used = new ItemTally();
			used.add(bracelet, 1);
			tracker.addSupplies(used, null, Collections.singletonMap(bracelet, SlayerBracelets.CHARGES_PER_BRACELET));
			// A bracelet of slaughter kept the count after a task kill: handled on the next settled tick, with the NPC
			// deaths around it. An expeditious bracelet's double drop needs nothing: the kills follow the count.
			if (bracelet == SlayerBracelets.SLAUGHTER_BRACELET)
			{
				slaughterKillPending = true;
			}
		}
		final BlowpipeMessage.Darts darts = BlowpipeMessage.parse(event.getMessage());
		if (darts != null)
		{
			// A check message naming a dart type this plugin doesn't know also forgets the type: darts aren't counted.
			setBlowpipeDartId(darts.isLoaded() ? darts.getDartId() : ChargedWeaponUsage.NO_ITEM);
			// The overlay's runway counts what it holds.
			blowpipeContents.messageSeen(darts);
		}
		else if (BlowpipeMessage.isOutOfScales(event.getMessage()))
		{
			blowpipeContents.outOfScales();
		}
		chargeExclusions.messageSeen(event.getMessage(), client.getTickCount());
	}

	/**
	 * Client thread. Remembers "Drop" clicks so the dropped item's decrease is not counted as a supply, teleport scrolls
	 * stored in the master scroll book ({@link SupplyExclusions#useClicked}), and loading, charging, unloading or
	 * uncharging the blowpipe, a trident or a powered staff ({@link ChargeExclusions}). Only reads the click; the menu
	 * is never changed.
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!clientStateReady)
		{
			return;
		}
		final String option = event.getMenuOption();
		if (option == null)
		{
			return;
		}
		final String plain = Text.removeTags(option).trim();
		final int itemId = event.getItemId();
		final int tick = client.getTickCount();
		if ("Drop".equalsIgnoreCase(plain))
		{
			if (itemId >= 0)
			{
				// Dropping a rune pouch counts nothing on these ticks: the runes inside leave with the pouch.
				supplyExclusions.dropClicked(itemId, SupplyReader.isRunePouch(itemId), tick);
			}
			return;
		}
		if ("Use".equalsIgnoreCase(plain))
		{
			// Only the click that uses the selected item on another one; selecting an item is also "Use".
			if (event.getMenuAction() == MenuAction.WIDGET_TARGET_ON_WIDGET)
			{
				final int[] used = useItemIds(event);
				chargeExclusions.menuOptionClicked(option, used, tick);
				supplyExclusions.useClicked(used, tick);
				for (int id : used)
				{
					if (ChargedWeaponUsage.BLOWPIPES.contains(id))
					{
						// Darts or scales loaded: what it holds is known again only from a check message.
						blowpipeContents.forget();
						break;
					}
				}
			}
			return;
		}
		if ("Charge".equalsIgnoreCase(plain))
		{
			// The powered staves' charge prompt takes the runes from the inventory.
			chargeExclusions.menuOptionClicked(option, new int[]{itemId}, tick);
			return;
		}
		if ("Unload".equalsIgnoreCase(plain) || "Uncharge".equalsIgnoreCase(plain))
		{
			chargeExclusions.menuOptionClicked(option, new int[]{itemId}, tick);
			if (ChargedWeaponUsage.BLOWPIPES.contains(itemId))
			{
				// The darts come out; the type is learned again from the next check message.
				setBlowpipeDartId(ChargedWeaponUsage.NO_ITEM);
				blowpipeContents.forget();
			}
		}
	}

	/**
	 * Client thread. The items of an item-on-item "Use": the target (the clicked entry's item and its widget's item)
	 * and the selected item ({@code client.getSelectedWidget()}, as core CompostTracker reads it, 1.13.4).
	 */
	private int[] useItemIds(MenuOptionClicked event)
	{
		final Widget target = event.getWidget();
		final Widget selected = client.getSelectedWidget();
		return new int[]{
			event.getItemId(),
			target != null ? target.getItemId() : -1,
			selected != null ? selected.getItemId() : -1
		};
	}

	/**
	 * Client thread. An excluded interface opening; also covers one opened and closed between two ticks. A bank opening
	 * may be a bank trip ({@link BankTripCounter}; RUNNING only).
	 */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (!clientStateReady)
		{
			return;
		}
		final int tick = client.getTickCount();
		supplyExclusions.interfaceChanged(event.getGroupId(), tick);
		if (event.getGroupId() == InterfaceID.OBJECTBOX)
		{
			// The trident's "You add N charges to the weapon." is an item dialog. Its text may be set after the
			// interface loads, so it is read later on the client thread, as core ItemChargePlugin reads the dialog's
			// item (1.13.4).
			clientThread.invokeLater(this::readObjectboxText);
		}
		if (BankTripCounter.isBankInterface(event.getGroupId()))
		{
			final SessionTracker.State state = tracker.getState();
			final boolean active = state.isActive();
			if (bankTripCounter.bankOpened(tick, state == SessionTracker.State.RUNNING,
				active ? tracker.getSessionId() : null))
			{
				tracker.addBankTrip();
			}
		}
	}

	/** Client thread. Lets the item dialog's text close a {@link ChargeExclusions} window. */
	private void readObjectboxText()
	{
		final Widget text = client.getWidget(InterfaceID.Objectbox.TEXT);
		if (text != null)
		{
			chargeExclusions.messageSeen(text.getText(), client.getTickCount());
		}
	}

	/**
	 * Client thread. An excluded interface closing; items it moved may only arrive on the next tick. A bank closing
	 * starts the same-trip window.
	 */
	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (!clientStateReady)
		{
			return;
		}
		final int tick = client.getTickCount();
		supplyExclusions.interfaceChanged(event.getGroupId(), tick);
		if (BankTripCounter.isBankInterface(event.getGroupId()))
		{
			bankTripCounter.bankClosed(tick);
		}
	}

	/**
	 * Client thread. A death of the local player: counted while RUNNING ({@link SessionTracker#addDeath}), and the
	 * items that leave the inventory after it are not supplies used. A death of an NPC the player damaged
	 * ({@link #onHitsplatApplied}) is kept for pairing with a task kill around it ({@link DamagedNpcs#pairing}, V2 plan
	 * 4.1) and tallied under its name ({@link SessionTracker#addNpcKill}): now while RUNNING, else by the kill it
	 * is paired with if that kill (re)starts the session ({@link #noteKillTick}).
	 */
	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (!clientStateReady)
		{
			return;
		}
		if (event.getActor() != null && event.getActor() == client.getLocalPlayer())
		{
			supplyExclusions.playerDied(client.getTickCount());
			tracker.addDeath();
		}
		else if (event.getActor() instanceof NPC)
		{
			final NPC npc = (NPC) event.getActor();
			final int worldViewId = worldViewId(npc);
			if (!damagedNpcs.isDamaged(worldViewId, npc.getIndex()))
			{
				return;
			}
			final String name = npc.getName();
			// An NPC outside the Catacombs with a variant's name is not tallied at all (CatacombsVariants#tallyName).
			final String tallyName = CatacombsVariants.tallyName(npc.getId(), name);
			// Tallied now only while RUNNING (the tracker decides). A death before the kill that (re)starts the session
			// is tallied by that kill (noteKillTick), once.
			final boolean tallied = tracker.addNpcKill(tallyName);
			damagedNpcs.died(worldViewId, npc.getIndex(), name, tallyName, tallied, client.getTickCount(),
				tracker.now(), tracker.getAwayMillis());
		}
	}

	/**
	 * Client thread. Remembers an NPC the player hit, with when their run of hits on it began (V2 plan 4.1), while a
	 * task is assigned, a session exists or a completion is held for late loot (its final kill's last hits may land the
	 * tick after the task count's drop): its death is the player's kill in the session's kills per NPC name
	 * ({@link SessionTracker#addNpcKill}), and a task kill paired with it is backdated to that start. It is
	 * remembered now, so a death later in this tick finds it. The rest waits for this tick's GameTick, after its
	 * supplies, XP and attack are read ({@link #applyHits}): the hit is put down to the player's attack, for its
	 * style and to start its run no later than the attack's tick, and a hit on one of the session's known task
	 * NPCs is activity for Away ({@link SessionTracker#taskNpcHit}). Only the player's own hitsplats count
	 * ({@code Hitsplat.isMine()}); {@link DamagedNpcs} keeps a bounded number of NPCs.
	 */
	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (!clientStateReady || !(event.getActor() instanceof NPC) || event.getHitsplat() == null
			|| !event.getHitsplat().isMine())
		{
			return;
		}
		if (!tracker.getState().isActive() && tracker.getCurrentRemaining() <= 0 && !tracker.hasPendingCompletion())
		{
			return;
		}
		final NPC npc = (NPC) event.getActor();
		damagedNpcs.damaged(worldViewId(npc), npc.getIndex(), tracker.now(), tracker.getAwayMillis(),
			client.getTickCount());
		final String name = npc.getName();
		if (name != null && hitNpcNames.size() < MAX_HIT_NPC_NAMES)
		{
			hitNpcNames.add(name);
		}
	}

	/** Client thread. Forgets a despawned NPC the player damaged: its index can be reused. */
	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		if (!clientStateReady || event.getNpc() == null)
		{
			return;
		}
		damagedNpcs.forget(worldViewId(event.getNpc()), event.getNpc().getIndex());
	}

	/**
	 * Client thread. The id of the world view {@code npc} is in: NPC indexes are unique only within one world
	 * view (the top-level one, or a boat's), so {@link DamagedNpcs} keeps both. An NPC without a world view is taken
	 * as top-level ({@link WorldView#TOPLEVEL}), the same way on its hit, death and despawn.
	 */
	private static int worldViewId(NPC npc)
	{
		final WorldView worldView = npc.getWorldView();
		return worldView == null ? WorldView.TOPLEVEL : worldView.getId();
	}

	/**
	 * Client thread. Reads this tick's tracked containers and adds what was used ({@link SessionTracker#addSupplies};
	 * the diff and its rules are in {@link SupplyTracker}): to the session while it runs, otherwise to the buffer
	 * outside segments, which a (re)start counts from its warm-up (V2 plan 4.1). The baseline always moves to this
	 * tick, so changes made inside an excluded interface are never counted later.
	 *
	 * <p>The diff runs while the tracker collects ({@link SessionTracker#isCollecting}): a session is running, or a task
	 * is assigned and not Ended (Waiting for the first kill, Away, Paused, after a login). While no task is assigned
	 * (none, or a completion held for late loot) or it is Ended, the containers are not read and the baseline is
	 * dropped; only the inventory is read, once after it changes, to note its potions
	 * ({@link #notePotionVariants}), and the excluded interfaces are still checked every tick, so one closed just before
	 * the diff resumes still shields its first ticks.</p>
	 *
	 * <p>A running session's ticks left out in a long stretch leave a log line with the reason
	 * ({@link #noteSkippedSupplyTick}), so a silent stop leaves a trace.</p>
	 *
	 * <p>Every tick, diffed or not, tells the overlay's feed what the counting leaves out (the whole tick, or dropped
	 * and charged items; {@link OverlayFeed#suppliesLeftOut}), so a supply that leaves the inventory then, banked or
	 * dropped, is forgotten by the runway rather than shown as run out.</p>
	 */
	private void trackSupplies()
	{
		final int tick = client.getTickCount();
		final int openGroup = openSupplyExcludedInterface();
		supplyExclusions.beginTick(tick, openGroup != NO_INTERFACE);
		final boolean excluded = supplyExclusions.isTickExcluded(tick);
		final Set<Integer> dropped = supplyExclusions.droppedItems();
		final Set<Integer> charged = chargeExclusions.excludedItems(tick);
		// Every tick, counted or not: a supply that leaves on such a tick (banked, dropped) never runs out.
		overlayFeed.suppliesLeftOut(excluded, dropped);
		overlayFeed.suppliesLeftOut(false, charged);

		final boolean running = tracker.getState() == SessionTracker.State.RUNNING;
		looseScales = 0;
		if (!tracker.isCollecting())
		{
			skippedSupplyTicks.reset();
			supplyTracker.reset();
			notePotionVariants();
			return;
		}
		final SupplyTracker.Observation observation = supplyReader.readSupplyObservation();
		if (observation == null)
		{
			supplyTracker.reset();
			noteSkippedSupplyTick(running, tick, openGroup, false);
			return;
		}
		looseScales = observation.getItems().getOrDefault(SupplyRunway.ZULRAH_SCALES, 0L);
		// Charged materials are skipped both ways: loading darts is not a use, and unloading them is not a return.
		final SupplyTracker.Usage usage = supplyTracker.update(observation, true, excluded, dropped, charged);
		if (!usage.isEmpty())
		{
			tracker.addSupplies(usage.getUsed(), usage.getReturned(), usage.getDoses());
		}
		if (excluded)
		{
			noteSkippedSupplyTick(running, tick, openGroup, true);
		}
		else
		{
			skippedSupplyTicks.reset();
		}
	}

	/**
	 * Client thread. The first of {@link #SUPPLY_EXCLUDED_INTERFACES} open now, or {@link #NO_INTERFACE}. An interface
	 * is open while its root widget is loaded and shown ({@code !isHidden()}, as core ItemStatPlugin checks the GE offers
	 * interface, 1.13.4). A loaded root widget alone is not enough: the game's own widget lookup also loads a
	 * group whenever a script or server packet refers to one of its components, without opening it or posting
	 * WidgetLoaded, so a group can stay loaded after its interface was closed. Its root widget then has no parent, so
	 * it reads hidden; taking it for open would leave every later tick out of supply counting.
	 */
	private int openSupplyExcludedInterface()
	{
		for (int group : SUPPLY_EXCLUDED_INTERFACES)
		{
			final Widget root = client.getWidget(group, 0);
			if (root != null && !root.isHidden())
			{
				return group;
			}
		}
		return NO_INTERFACE;
	}

	/**
	 * Client thread. A tick whose supplies were not counted. While a session runs, an unusually long stretch of such
	 * ticks leaves a rate-limited log line with the reason ({@link SkippedSupplyTicks}): the first at debug level, the
	 * repeats (ten minutes or more, rare for a real visit) at info, so a silent stop of supply counting leaves a trace
	 * in the default client log. Cheap: the reason is only worked out when a line is due.
	 *
	 * @param openGroup       the excluded interface open on this tick, or {@link #NO_INTERFACE}
	 * @param inventoryLoaded whether the inventory could be read
	 */
	private void noteSkippedSupplyTick(boolean running, int tick, int openGroup, boolean inventoryLoaded)
	{
		if (!running)
		{
			skippedSupplyTicks.reset();
			return;
		}
		if (!skippedSupplyTicks.skipped())
		{
			return;
		}
		final String reason;
		if (!inventoryLoaded)
		{
			reason = "the inventory is not loaded";
		}
		else if (openGroup != NO_INTERFACE)
		{
			reason = "excluded interface " + openGroup + " is open";
		}
		else
		{
			reason = supplyExclusions.exclusionReason(tick);
		}
		final int length = skippedSupplyTicks.length();
		if (length > SkippedSupplyTicks.FIRST_REPORT_TICKS)
		{
			// Ten minutes or more is rare for a real visit, so it reaches the default (INFO) client log.
			log.info("Supplies not counted for {} game ticks in a row while the session runs: {}", length, reason);
		}
		else
		{
			log.debug("Supplies not counted for {} game ticks in a row while the session runs: {}", length, reason);
		}
	}

	/**
	 * Client thread, while no supplies diff runs (no task assigned, or it is Ended). The diff used to see the inventory
	 * every tick, and each potion's use is recorded under the highest-dose variant the tracker has seen
	 * ({@link SupplyTracker}); so, after the inventory changed, its items alone are read once and their potions noted
	 *. A potion drunk from 4 to 3 doses before a task is assigned is still recorded as the 4-dose item.
	 *
	 * <p>Each read notes every item whose data is loaded. An item whose data is not loaded yet is retried on the next
	 * idle ticks, at most {@link #INVENTORY_NOTE_TRIES} reads per change: apart from a lookup that threw, only a
	 * wieldable item waiting for its item stats, which is never a potion, or a members item seen on a free world
	 * ({@link SupplyReader#classifyItem}) can be in that state. A login or hop to a members world reads the inventory
	 * again, so stopping loses no potion. Counting is unchanged: the diff classifies every item it sees while it
	 * runs.</p>
	 */
	private void notePotionVariants()
	{
		if (inventoryNoteTries <= 0)
		{
			return;
		}
		final SupplyTracker.Observation inventory = supplyReader.readInventoryObservation();
		if (inventory == null || supplyTracker.noteVariants(inventory))
		{
			// Not loaded: the client posts ItemContainerChanged when it is. Noted: nothing left to read.
			inventoryNoteTries = 0;
		}
		else
		{
			// Item data not loaded yet: noted again next tick, a few times at most.
			inventoryNoteTries--;
		}
	}

	/** Client thread. The inventory changed: its potions are noted on the next tick no diff runs. */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (clientStateReady && event.getContainerId() == InventoryID.INV)
		{
			inventoryNoteTries = INVENTORY_NOTE_TRIES;
		}
	}

	/**
	 * Client thread. Counts this tick's blowpipe shot or trident or powered staff cast ({@link ChargedWeaponUsage}) from
	 * the worn weapon and cape, the local player's animation and frame, and the weapon's cast graphics
	 * ({@link ChargedWeaponUsage#castGraphics}, {@code Actor#hasSpotAnim}, 1.13.4), and adds what it used
	 * ({@link SessionTracker#addSupplies}: to the session while it runs, otherwise to the buffer outside segments).
	 * Only while the supplies diff runs ({@link SessionTracker#isCollecting}): otherwise the carry-overs, the dart
	 * hint and the recent attacks are reset, apart from the attacks while a completion is held for late loot, whose
	 * final kill's last hits may land on the next tick. Also shows or hides the panel's "Check your blowpipe"
	 * hint, while the session runs, and notes this tick's attack for the styles of the hits it lands
	 * ({@link #noteAttack}).
	 *
	 * @param tickStart when this tick began (clock millis), before its supplies and XP were read
	 */
	private void trackChargedWeapons(long tickStart)
	{
		final Player player = tracker.isCollecting() ? client.getLocalPlayer() : null;
		if (player == null)
		{
			chargedWeaponUsage.reset();
			// What the blowpipe holds is kept, between tasks and after End task too, though shots now are not taken out
			// of it: only loading, Unload, Uncharge, logout and another account forget it.
			blowpipeWorn = false;
			if (tracker.hasPendingCompletion())
			{
				playerAttacks.forgetXp();
			}
			else
			{
				playerAttacks.clear();
			}
			publishDartHint(false);
			return;
		}
		final ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		final int weaponId = SupplyReader.wornItemId(worn, EquipmentInventorySlot.WEAPON);
		final int animationId = player.getAnimation();
		final int animationFrame = player.getAnimationFrame();
		noteAttack(player, weaponId, animationId, animationFrame, tickStart);
		boolean castGraphic = false;
		for (int graphic : ChargedWeaponUsage.castGraphics(weaponId, animationId))
		{
			castGraphic |= player.hasSpotAnim(graphic);
		}
		final Map<Integer, Integer> used = chargedWeaponUsage.update(ChargedWeaponUsage.Tick.builder()
			.tick(client.getTickCount())
			.weaponId(weaponId)
			.animationId(animationId)
			.animationFrame(animationFrame)
			.capeId(SupplyReader.wornItemId(worn, EquipmentInventorySlot.CAPE))
			// Dizana's quiver setting (varbit 9803) is not verified in game yet: assume it saves 80%.
			.quiverAmmoSave(null)
			.castGraphic(castGraphic)
			.vorkathsHeadUpgrade(config.vorkathsHeadUpgrade())
			.dartId(blowpipeDartId)
			.build());
		blowpipeWorn = ChargedWeaponUsage.BLOWPIPES.contains(weaponId);
		if (!used.isEmpty())
		{
			final ItemTally items = new ItemTally();
			used.forEach(items::add);
			tracker.addSupplies(items, null, null);
			if (blowpipeWorn)
			{
				blowpipeContents.used(used);
			}
		}
		publishDartHint(tracker.getState() == SessionTracker.State.RUNNING && chargedWeaponUsage.isDartCheckNeeded()
			&& ChargedWeaponUsage.BLOWPIPES.contains(weaponId));
	}

	/**
	 * Client thread. Notes the player's attack starting this tick on the NPC they are interacting with
	 * ({@link PlayerAttacks}): an animation at its first frame (as {@link ChargedWeaponUsage} counts attacks), not
	 * eating or drinking, while the player is interacting with an NPC, so loading a cannon or picking something up is
	 * not one, and with XP gained this tick in {@link AttackStyles#ATTACK_XP_SKILLS}, so a block is not one either. The
	 * XP is read every tick for that. Its style comes from the animation and the worn weapon
	 * ({@link AttackStyles#attackStyle}); an attack whose style isn't known (the weapon's stats not loaded yet) is not
	 * noted.
	 */
	private void noteAttack(Player player, int weaponId, int animationId, int animationFrame, long tickStart)
	{
		final boolean xpRose = playerAttacks.xpRose(readAttackXp());
		final Actor target = player.getInteracting();
		if (animationFrame != 0 || !AttackStyles.mayBeAttack(animationId) || !(target instanceof NPC) || !xpRose)
		{
			return;
		}
		final NPC npc = (NPC) target;
		final SetupStyle weapon = AttackStyles.isSpell(animationId) ? null : weaponStyle(weaponId);
		playerAttacks.add(client.getTickCount(), AttackStyles.attackStyle(animationId, weapon), tickStart,
			DamagedNpcs.key(worldViewId(npc), npc.getIndex()), AttackStyles.splashes(animationId, weaponId));
	}

	/**
	 * Client thread. The total XP of {@link AttackStyles#ATTACK_XP_SKILLS} now, or {@link PlayerAttacks#NO_XP} before
	 * the stats are loaded (Hitpoints XP reads 0, as {@link #observeSkillXp} checks).
	 */
	private long readAttackXp()
	{
		if (client.getSkillExperience(Skill.HITPOINTS) <= 0)
		{
			return PlayerAttacks.NO_XP;
		}
		long total = 0;
		for (Skill skill : AttackStyles.ATTACK_XP_SKILLS)
		{
			total += client.getSkillExperience(skill);
		}
		return total;
	}

	/**
	 * Client thread. The worn weapon's style from its equipment stats ({@link AttackStyles#weaponStyle}): Melee when
	 * nothing is wielded (unarmed), {@code null} when its stats are not loaded.
	 */
	private SetupStyle weaponStyle(int weaponId)
	{
		if (weaponId == ChargedWeaponUsage.NO_ITEM)
		{
			return SetupStyle.MELEE;
		}
		final ItemStats stats = itemManager.getItemStats(weaponId);
		final ItemEquipmentStats e = stats == null ? null : stats.getEquipment();
		if (e == null)
		{
			return null;
		}
		return AttackStyles.weaponStyle(e.getAstab(), e.getAslash(), e.getAcrush(), e.getAmagic(), e.getArange(),
			e.getRstr());
	}

	/**
	 * Client thread. Reads whether the player's cannon is placed ({@code DROPCANNON} 4) and decayed
	 * ({@code MCANNON_DECAYED} 1), as core CannonPlugin reads them (1.13.4), and its loaded cannonballs
	 * ({@code ROCKTHROWER}) for {@link CannonFire}. Only while the supplies diff runs
	 * ({@link SessionTracker#isCollecting}), so nothing is read while no task is assigned; otherwise its shots are
	 * forgotten.
	 */
	private void trackCannon()
	{
		if (!tracker.isCollecting())
		{
			cannonFire.reset();
			return;
		}
		cannonFire.update(client.getTickCount(), client.getVarpValue(VarPlayerID.DROPCANNON) == CannonFire.PLACED,
			client.getVarbitValue(VarbitID.MCANNON_DECAYED) == 1, client.getVarpValue(VarPlayerID.ROCKTHROWER));
	}

	/** Client thread. Shows or hides the panel's "Check your blowpipe" hint (on the EDT) when it changes. */
	private void publishDartHint(boolean shown)
	{
		if (shown == dartHintShown)
		{
			return;
		}
		dartHintShown = shown;
		final SlayerLedgerPanel p = panel;
		if (p != null)
		{
			SwingUtilities.invokeLater(() -> p.setDartHint(shown));
		}
	}

	/**
	 * Client thread. Values the active session's loot and supplies for the panel, at most once every
	 * {@link #SESSION_VALUE_TICKS} ticks. ItemManager is only called here, on the client thread; {@link SessionValuation}
	 * caches unit prices for the session and skips the lookups when nothing changed. Results reach the panel on the
	 * EDT, and only when they differ from what it shows. When no session is active the values are cleared.
	 *
	 * <p>A stopped (Away, Paused or Logged out) session's supplies can't change (they are buffered), nor its loot apart
	 * from a paired task kill's late loot, so once it has been valued while stopped with every GE price known, it is not
	 * valued again (no copy of its tallies is taken) until such loot arrives, it restarts or the "Loot price" setting
	 * changes. While a price is unavailable it keeps being valued, so the retry can still fill it in.</p>
	 */
	private void updateSessionValues()
	{
		final SessionTracker.State state = tracker.getState();
		if (!state.isActive())
		{
			if (publishedValues != null)
			{
				sessionValuation.reset();
				publishSessionValues(null);
			}
			// The next session is valued on its first tick.
			sessionValueTick = SupplyExclusions.NEVER_TICK;
			pausedValuesSession = null;
			return;
		}
		final LootPriceMode mode = lootPriceMode;
		final GroundItemsHidden hiddenList = groundItemsHidden;
		// A new "Loot price" setting or hidden list is valued at once (once: a failing valuation is still throttled).
		final boolean modeChanged = mode != sessionValueMode || !hiddenList.equals(sessionValueHidden);
		final boolean stopped = state.isClockStopped();
		if (!stopped)
		{
			pausedValuesSession = null;
		}
		else if (!modeChanged && pausedValuesSession != null && pausedValuesSession.equals(tracker.getSessionId()))
		{
			// Valued while stopped (Away, Paused or Logged out) already, in this mode and with every price known:
			// nothing can have changed.
			return;
		}
		final int tick = client.getTickCount();
		// Throttled whether or not the last valuation succeeded, so a failing price lookup is not retried every tick.
		if (!modeChanged && tick >= sessionValueTick && tick - sessionValueTick < SESSION_VALUE_TICKS)
		{
			return;
		}
		sessionValueTick = tick;
		sessionValueMode = mode;
		sessionValueHidden = hiddenList;
		final SessionTracker.Summary active = tracker.activeSummary();
		if (active == null)
		{
			return;
		}
		final SessionValuation.Values values;
		try
		{
			final HiddenLoot hidden = hiddenLoot(active.getLoot() == null ? null : active.getLoot().itemIds());
			values = sessionValuation.update(active.getSessionId(), active.getLoot(), active.getSupplies(),
				active.getSupplyDoses(), mode, hidden, lookups.priceLookup(), tick);
		}
		catch (RuntimeException e)
		{
			log.debug("Unable to value the Slayer Ledger session", e);
			return;
		}
		// The state read above: a session resumed meanwhile is RUNNING on the next tick, which clears this again.
		pausedValuesSession = stopped && values.getLootUnpriced() == 0
			&& values.getSuppliesUnpriced() == 0 ? active.getSessionId() : null;
		if (!values.equals(publishedValues))
		{
			publishSessionValues(values);
		}
	}

	/** Client thread. Sends the live session values (or {@code null} to clear them) to the panel on the EDT. */
	private void publishSessionValues(SessionValuation.Values values)
	{
		publishedValues = values;
		final SlayerLedgerPanel p = panel;
		if (p != null)
		{
			SwingUtilities.invokeLater(() -> p.setSessionValues(values));
		}
	}

	/**
	 * Client thread. Takes a fresh supplies baseline now, so nothing from before it is counted: only for the
	 * {@link #startSessionNow} test seam, which counts nothing from before it as the removed Play did. A real (re)start
	 * needs none: the diff runs while a task is assigned, and the tracker counts the warm-up from its buffer.
	 */
	private void takeSupplyBaseline()
	{
		if (supplyTracker == null)
		{
			return;
		}
		supplyTracker.reset();
		// Fractional darts and scales from before are not carried into the started or resumed session.
		chargedWeaponUsage.reset();
		final SupplyTracker.Observation observation = supplyReader.readSupplyObservation();
		if (observation != null)
		{
			supplyTracker.update(observation, false, true, Collections.emptySet());
		}
	}

	/**
	 * Client thread. Runs the pending file-restore or re-login check, deferring while the task count still reads
	 * zero (possibly not yet synced) for up to {@link SessionTracker#RESTORE_SYNC_MAX_TICKS} ticks.
	 */
	private void runPendingRestoreCheck(int remaining, int[] counters)
	{
		if (SessionTracker.shouldDeferRestoreCheck(remaining, restoreWaitTicks))
		{
			restoreWaitTicks++;
			return;
		}
		final boolean fileRestore = pendingFileRestore;
		final SessionSnapshot reloginSnapshot = pendingReloginSnapshot;
		pendingFileRestore = false;
		pendingReloginSnapshot = null;

		if (fileRestore)
		{
			// restorePending is cleared when the load is applied.
			loadActiveSession(accountHash);
		}
		if (reloginSnapshot != null)
		{
			try
			{
				if (!tracker.activeSessionStillMatches(reloginSnapshot, counters))
				{
					log.debug("Session {} no longer matches the task after re-login", reloginSnapshot.getSessionId());
					endSession(SaveDecision.EndReason.RESTORE_MISMATCH);
				}
			}
			finally
			{
				restorePending = false;
			}
		}
	}

	/** Client thread. Drops any pending restore check and invalidates in-flight loads (logout, account change). */
	private void cancelPendingRestore()
	{
		pendingFileRestore = false;
		pendingReloginSnapshot = null;
		restoreWaitTicks = 0;
		loadGeneration++;
		restorePending = false;
	}


	// ---- Panel actions (EDT) ----

	/**
	 * Test seam: starts a session at once ({@link SessionTracker#startNow}), as the removed Play did, for tests whose
	 * subject is not how a session starts; the player starts sessions with a task kill.
	 */
	boolean startSessionNow()
	{
		if (!loggedIn || restorePending || !tracker.startNow(accountHash))
		{
			return false;
		}
		// Supplies count from now: anything used before is never counted.
		clientThread.invoke(this::takeSupplyBaseline);
		refreshHistoryModel();
		return true;
	}

	/** Pause: the running session's segment closes now. */
	void pause()
	{
		tracker.pause();
		refreshHistoryModel();
	}

	/**
	 * Resume, while Paused: opens a segment at once, a restart, so the supplies used in the warm-up before it count
	 * (V2 plan 4.1); the supplies diff kept running while paused, so it needs no new baseline.
	 */
	boolean resume()
	{
		if (!canResume() || !tracker.resume(accountHash))
		{
			return false;
		}
		refreshHistoryModel();
		return true;
	}

	/** Whether Resume would succeed: logged in, no restore check pending, and the session is this account's and Paused. */
	boolean canResume()
	{
		return loggedIn && !restorePending && tracker.canResume(accountHash);
	}

	/**
	 * End task: the session, if any, is saved through the Keep / Discard decision (as Stop was), and its assignment is
	 * Ended: no kill on it starts a session until Track again (V2 plan 4.1).
	 */
	void endTask()
	{
		// Any completed session still held for late loot is saved first, as completed.
		flushPendingCompletion();
		handleEndedSession(tracker.endTask(), SaveDecision.EndReason.PLAYER_STOP);
		refreshHistoryModel();
	}

	/**
	 * Track again: the Ended assignment waits for its first kill again. Its marker, if a logout wrote one (Track again
	 * can be pressed at the login screen), is deleted, so the next login or client start can't make it Ended again.
	 */
	void trackAgain()
	{
		final long account = tracker.getEndedAccount();
		if (tracker.trackAgain())
		{
			deleteEndedMarker(account);
		}
		refreshHistoryModel();
	}

	/**
	 * What the task overlay shows, or {@code null} when there is nothing to show: no active session with a task (idle, a
	 * no-task session, or a completed one held for late loot) and no assigned task waiting for its first kill or Ended.
	 * The task comes from one {@link SessionTracker#liveTask()} call, which carries its status for the state line, its
	 * kills, active time and kills left, so the clock and a Pause show at once. The rest is what was last published,
	 * used only when it belongs to the same session: the tick's snapshot ({@link #publishOverlaySnapshot}: the name with
	 * its variant and cannon, the spot, the tags, superiors, XP, the runway and the moment), the live values and the
	 * historical time per kill ({@link #publishHistoricalPace}). Without a session, the state line with the assignment's
	 * snapshot (V2 plan 4.1: the state shows whenever time isn't running). Nothing after the plugin's start or a logout
	 * until the first settled tick has read the logged-in account's task ({@link #overlaySettled}), so another account
	 * never sees the last one's state. Called by the overlay on the client thread every frame, so it only reads.
	 */
	OverlayRows.Data overlayData()
	{
		if (tracker == null || !clientStateReady || !overlaySettled)
		{
			return null;
		}
		final OverlayRows.Snapshot snapshot = overlaySnapshot;
		SessionTracker.LiveTask task = tracker.liveTask();
		if (task == null)
		{
			final SessionTracker.Status status = tracker.getStatus();
			return loggedIn && (status == SessionTracker.Status.WAITING_FOR_FIRST_KILL
				|| status == SessionTracker.Status.ENDED) ? OverlayRows.Data.stateOnly(status,
				snapshot != null && snapshot.getSessionId() == null ? snapshot : null) : null;
		}

		SessionValuation.Values values = publishedValues;
		if (values != null && !Objects.equals(task.getSessionId(), values.getSessionId()))
		{
			values = null;
		}
		final OverlayRows.HistoricalPace pace = historicalPace;
		return new OverlayRows.Data(task,
			snapshot != null && Objects.equals(task.getSessionId(), snapshot.getSessionId()) ? snapshot : null,
			values, pace == null ? null : pace.millisPerKillFor(task));
	}

	/**
	 * EDT, from the history model ({@link LedgerHistoryModel#refreshHistoricalPace}) whenever the records, a region
	 * name, the task, its location or the session change; never from a timer. Publishes the historical time per kill
	 * the sidebar's "Time left" uses for the active session, or {@code null} to clear it (no session, or no history for
	 * its task), so the overlay shows the same estimate. Only a volatile write: no other work.
	 */
	void publishHistoricalPace(OverlayRows.HistoricalPace pace)
	{
		historicalPace = pace;
	}

	/**
	 * Puts an item's icon on a sidebar label ({@link ItemIcons}), with the quantity drawn when it is above 1, as
	 * Loot Tracker does. Called on the EDT, as Loot Tracker calls it: unlike the rest of {@code ItemManager},
	 * {@code getImage} may be called from any thread. It returns a cached image at once (blank until drawn) and queues
	 * the sprite drawing to the client thread; {@code addTo} repaints the label once the sprite is drawn.
	 */
	void applyItemIcon(JLabel label, int itemId, int quantity)
	{
		AsyncBufferedImage image = itemManager.getImage(itemId, quantity, quantity > 1);
		if (image != null)
		{
			image.addTo(label);
		}
	}

	/**
	 * Puts a skill's small icon on a sidebar label ({@link SkillIcons}), as the XP tracker shows before a skill's
	 * name; a key that is no {@link Skill} constant (a skill this client does not know) leaves the label without an
	 * icon.
	 *
	 * <p>Checked against RuneLite 1.13.4: {@code SkillIconManager.getSkillImage(Skill skill, boolean small)} returns a
	 * {@code BufferedImage} loaded from the client's {@code skill_icons_small} resources (16 by 16 px, Ranged 18) and
	 * caches it per skill. It reads nothing from the client, so it needs no client thread, but its cache is an
	 * unsynchronized array: it is called only on the EDT, as the XP tracker and Skill Calculator panels call it.</p>
	 */
	void applySkillIcon(JLabel label, String skill)
	{
		if (skill == null)
		{
			return;
		}
		Skill constant;
		try
		{
			constant = Skill.valueOf(skill);
		}
		catch (IllegalArgumentException e)
		{
			return;
		}
		BufferedImage image = skillIconManager.getSkillImage(constant, true);
		if (image != null)
		{
			label.setIcon(new ImageIcon(image));
		}
	}

	/**
	 * Loads a setup tag's game image for the sidebar ({@link SetupIcons}, the live card's toggles and the Task
	 * history rows, which ask for each image once through {@link SetupImages}), on the EDT. The cannon barrels come
	 * from {@code ItemManager.getImage}, as {@link #applyItemIcon}: a cached image at once, drawn later on the client
	 * thread, which then runs {@code repaint}. A spell or skill sprite comes from
	 * {@code SpriteManager.getSpriteAsync}, which reads only its own cache and otherwise queues the read on the client
	 * thread (until the game's cache is loaded); it calls back there, or at once when cached, so the image is handed to
	 * {@code show} with {@code invokeLater} (DESIGN_PRINCIPLES.md 4.5).
	 */
	void loadSetupIcon(SetupTagIcons.Icon icon, Consumer<Image> show, Runnable repaint)
	{
		if (icon.isItem())
		{
			AsyncBufferedImage image = itemManager.getImage(icon.getId(), 1, false);
			if (image != null)
			{
				image.onLoaded(repaint);
				show.accept(image);
			}
			return;
		}
		spriteManager.getSpriteAsync(icon.getId(), 0,
			(BufferedImage sprite) -> SwingUtilities.invokeLater(() -> show.accept(sprite)));
	}

	/**
	 * Deletes a saved ledger record (history Delete or the partial notice's Discard) on the executor, then reloads
	 * the account's ledger and refreshes the panel. Never touches the active (in-progress) session or its file.
	 */
	void deleteRecord(long account, String id)
	{
		if (account == SessionTracker.NO_ACCOUNT || id == null)
		{
			return;
		}
		executor.execute(() ->
		{
			boolean failed = false;
			try
			{
				store.delete(account, id);
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Unable to delete Slayer Ledger record", e);
				failed = true;
			}
			// Reload either way so the panel reflects what is actually on disk.
			reloadLedger(account);
			if (failed)
			{
				// Posted after the reload's update, so the refresh does not immediately clear it.
				publishHistoryError(account, SlayerLedgerPanel.DELETE_FAILED_MESSAGE);
			}
		});
	}

	/**
	 * Deletes an assignment entry (the "Skipped / blocked" section's Delete) on the executor, then shows the account's
	 * entries again. Never touches the records or the active session.
	 */
	void deleteAssignment(long account, String id)
	{
		if (account == SessionTracker.NO_ACCOUNT || id == null)
		{
			return;
		}
		executor.execute(() ->
		{
			try
			{
				publishAssignments(account, store.deleteAssignment(account, id));
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Unable to delete Slayer Ledger assignment entry", e);
				// Reload so the panel reflects what is actually on disk, then post the error after that update.
				reloadLedger(account);
				publishHistoryError(account, SlayerLedgerPanel.DELETE_ENTRY_FAILED_MESSAGE);
			}
		});
	}


	/**
	 * Sets the note of the session the panel's note box belongs to (see {@link SessionTracker#setNote}). If that
	 * session has ended and was handed off for saving meanwhile (for example the task completed while the player was
	 * typing), the note is written to its saved record instead: via the client thread, so the record's save, which is
	 * queued on the executor from the client thread, is queued first and the note lands after it.
	 */
	void setSessionNote(long account, String sessionId, String note)
	{
		if (sessionId == null || tracker.setNote(sessionId, note))
		{
			return;
		}
		clientThread.invokeLater(() -> editRecordNote(account, sessionId, note));
	}

	/**
	 * Sets the setup overrides (cannon, style, gear; each {@code null} to clear it) of the session the panel's setup
	 * toggles belong to ({@link SessionTracker#setSetupOverrides}). If that session has ended and was handed off for saving
	 * meanwhile, they are written to its saved record instead, through the client thread as {@link #setSessionNote}
	 * does, so the record's save is queued first.
	 */
	void setSessionSetup(long account, String sessionId, SetupOverrides overrides)
	{
		if (sessionId == null || tracker.setSetupOverrides(sessionId, overrides))
		{
			return;
		}
		clientThread.invokeLater(() -> editRecordSetup(account, sessionId, overrides));
	}

	/**
	 * Sets (or, with an empty note, clears) the note of a saved ledger record on the executor, then reloads the
	 * account's ledger and refreshes the panel, like {@link #deleteRecord}. A record that no longer exists is left
	 * alone. Never touches the active (in-progress) session or its file.
	 */
	void editRecordNote(long account, String id, String note)
	{
		if (account == SessionTracker.NO_ACCOUNT || id == null)
		{
			return;
		}
		final String normalized = TaskNote.normalize(note);
		executor.execute(() ->
		{
			boolean failed = false;
			try
			{
				store.updateNote(account, id, normalized);
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Unable to update the note of Slayer Ledger record", e);
				failed = true;
			}
			// Reload either way so the panel reflects what is actually on disk.
			reloadLedger(account);
			if (failed)
			{
				// Posted after the reload's update, so the refresh does not immediately clear it.
				publishHistoryError(account, SlayerLedgerPanel.EDIT_NOTE_FAILED_MESSAGE);
			}
		});
	}

	/**
	 * Sets (or, with an empty name, clears) the location name of a saved ledger record on the executor, then reloads
	 * the account's ledger and refreshes the panel, like {@link #editRecordNote}. Only that record's name changes: its
	 * spot, its region, the region's name and other records are kept, so it still groups with its spot's other
	 * tasks. Never touches the active session.
	 */
	void editRecordLocation(long account, String id, String location)
	{
		if (account == SessionTracker.NO_ACCOUNT || id == null)
		{
			return;
		}
		final String normalized = TaskLocation.normalizeName(location);
		executor.execute(() ->
		{
			boolean failed = false;
			try
			{
				store.updateLocation(account, id, normalized);
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Unable to update the location of Slayer Ledger record", e);
				failed = true;
			}
			// Reload either way so the panel reflects what is actually on disk.
			reloadLedger(account);
			if (failed)
			{
				// Posted after the reload's update, so the refresh does not immediately clear it.
				publishHistoryError(account, SlayerLedgerPanel.EDIT_LOCATION_FAILED_MESSAGE);
			}
		});
	}

	/**
	 * Sets the player's setup overrides (cannon, style, gear; each {@code null} to clear it) of a saved ledger record on
	 * the executor, then reloads the account's ledger and refreshes the panel, like {@link #editRecordNote}. Only that
	 * record's overrides change: its detected tags and other records are kept. Never touches the active session (whose
	 * overrides are set through {@link #setSessionSetup}).
	 */
	void editRecordSetup(long account, String id, SetupOverrides overrides)
	{
		if (account == SessionTracker.NO_ACCOUNT || id == null)
		{
			return;
		}
		final SetupOverrides value = overrides == null ? SetupOverrides.NONE : overrides;
		executor.execute(() ->
		{
			boolean failed = false;
			try
			{
				store.updateSetup(account, id, value);
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Unable to update the setup of Slayer Ledger record", e);
				failed = true;
			}
			// Reload either way so the panel reflects what is actually on disk.
			reloadLedger(account);
			if (failed)
			{
				// Posted after the reload's update, so the refresh does not immediately clear it.
				publishHistoryError(account, SlayerLedgerPanel.EDIT_SETUP_FAILED_MESSAGE);
			}
		});
	}

	/** Any thread. Shows a non-modal error above the history list, guarded like {@link #publishRecords}. */
	private void publishHistoryError(long account, String message)
	{
		final SlayerLedgerPanel p = panel;
		if (p == null)
		{
			return;
		}
		SwingUtilities.invokeLater(() ->
		{
			if (account == historyAccount)
			{
				p.showHistoryError(account, message);
			}
		});
	}

	// ---- Internals ----

	/**
	 * Stops the active session (if any) and handles it according to {@link SaveDecision}. May be called from the
	 * client thread or the EDT; the prompt and notice are always shown on the EDT and file IO runs on the executor.
	 *
	 * @return the pending file write, or {@code null} if nothing is being written yet
	 */
	private Future<?> endSession(SaveDecision.EndReason reason)
	{
		// Any completed session still held for late loot is saved first, as completed. Its write is not returned;
		// ClientShutdown waits for it separately via flushPendingCompletion.
		flushPendingCompletion();
		return handleEndedSession(tracker.stopAndReset(), reason);
	}

	/**
	 * Saves, as completed, a completed session still held for late loot (see {@link SessionTracker#holdCompletion()}),
	 * without waiting for the rest of its grace window. The tracker hands each held session out exactly once.
	 *
	 * @return the pending save, or {@code null} if no session was held
	 */
	private Future<?> flushPendingCompletion()
	{
		return handleEndedSession(tracker.takePendingCompletion(), SaveDecision.EndReason.SYSTEM);
	}

	private Future<?> handleEndedSession(SessionTracker.Summary summary, SaveDecision.EndReason reason)
	{
		if (summary == null)
		{
			return null;
		}
		final long account = summary.getAccountHash();
		final String sessionId = summary.getSessionId();
		switch (SaveDecision.decide(summary, reason, config.confirmIncomplete()))
		{
			case SAVE_COMPLETED:
			{
				final LedgerRecord record = LedgerRecord.fromSummary(summary, false);
				final LootPriceMode mode = lootPriceMode;
				return saveRecord(account, record, () -> announceCompletedTask(account, record, mode));
			}
			case SAVE_PARTIAL:
				return saveRecord(account, LedgerRecord.fromSummary(summary, true), null);
			case SAVE_PARTIAL_WITH_NOTICE:
			{
				final LedgerRecord record = LedgerRecord.fromSummary(summary, true);
				final SlayerLedgerPanel noticePanel = panel;
				// The notice is posted only after the append succeeds, so a failed save never claims "saved"; and only
				// while that account is still the one logged in (an account switch clears it, see checkAccount).
				return saveRecord(account, record, noticePanel == null ? null
					: () -> SwingUtilities.invokeLater(() ->
					{
						if (account == accountHash)
						{
							noticePanel.showPartialNotice(account, record);
						}
					}));
			}
			case PERSIST_ACTIVE:
				return persistActive(summary);
			case PROMPT:
			{
				final SlayerLedgerPanel promptPanel = panel;
				final BiPredicate<SlayerLedgerPanel, SessionTracker.Summary> dialog = keepIncompleteDialog;
				pendingPrompt.set(summary);
				SwingUtilities.invokeLater(() ->
				{
					boolean keep = promptPanel == null || dialog.test(promptPanel, summary);
					// If shutdown already saved the pending session, compareAndSet fails and nothing is saved twice.
					if (pendingPrompt.compareAndSet(summary, null))
					{
						if (keep)
						{
							saveRecord(account, LedgerRecord.fromSummary(summary, true), null);
						}
						else
						{
							deleteActive(account, sessionId);
						}
					}
				});
				return null;
			}
			case DISCARD:
			default:
				log.debug("Discarding session without a task or account");
				return deleteActive(account, sessionId);
		}
	}

	/**
	 * Any thread. Queues a completed task's chat lines after its ledger record has been appended successfully. First,
	 * on the EDT, the history model works out whether the task is a record-fast one
	 * ({@link LedgerHistoryModel#fastestTaskLine}, from the account's records, which the append's
	 * {@link #publishRecords} gave it before this runs); none without a model (the plugin is stopping) or once another
	 * account's history is shown. Then {@link #postCompletedTask} posts the lines.
	 */
	private void announceCompletedTask(long account, LedgerRecord record, LootPriceMode mode)
	{
		final LedgerHistoryModel m = historyModel;
		if (m == null)
		{
			postCompletedTask(record, mode, null);
			return;
		}
		SwingUtilities.invokeLater(() -> postCompletedTask(record, mode,
			account == historyAccount ? m.fastestTaskLine(account, record) : null));
	}

	/**
	 * Any thread. On the client thread, where the loot Ground Items hides is known: the task-complete line
	 * ({@link TaskCompleteMessage}, its "Task-complete line" setting) and its notification ("Task-complete
	 * notification", the same text, whether or not the line is on), then the record-fast task line
	 * ({@link FastestTaskMessage}) unless its "Record task line" setting is off. When the record line follows, the
	 * task-complete line leaves out its {@code Gz.}, so only one of them carries personality.
	 *
	 * @param fastestLine the record-fast task line as a chat message, or {@code null} when the task is no record
	 */
	private void postCompletedTask(LedgerRecord record, LootPriceMode mode, String fastestLine)
	{
		clientThread.invoke(() ->
		{
			final boolean fastest = fastestLine != null && config.recordTaskLine();
			final String line = TaskCompleteMessage.format(record, mode,
				hiddenLoot(record.getLoot() == null ? null : record.getLoot().keySet()), !fastest);
			if (line == null)
			{
				return;
			}
			if (config.taskCompleteLine())
			{
				postChatMessage(TaskCompleteMessage.chatMessage(record, line));
			}
			if (config.taskCompleteNotification())
			{
				notifier.notify(line);
			}
			if (fastest)
			{
				postChatMessage(fastestLine);
			}
		});
	}


	/**
	 * Client thread. Posts the plugin's plain-text chat line (waiting for the next kill) as a console message named
	 * "Slayer Ledger". The plain text is always escaped with {@link Text#escapeJagex}, so it can't add tags. The line is
	 * plain text with no markup of its own, so nothing is escaped twice. The other lines are built with
	 * {@link net.runelite.client.chat.ChatMessageBuilder} and posted with {@link #postChatMessage}.
	 */
	private void postChatLine(String text)
	{
		postChatMessage(Text.escapeJagex(text));
	}

	/**
	 * Client thread. Posts a chat message already in RuneLite's format (escaped plain text and
	 * {@link net.runelite.client.chat.ChatColorType} tags, as {@link net.runelite.client.chat.ChatMessageBuilder}
	 * builds it) as a console message named "Slayer Ledger".
	 */
	private void postChatMessage(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.name("Slayer Ledger")
			.runeLiteFormattedMessage(message)
			.build());
	}

	/**
	 * Saves, as partial, a session whose Keep/Discard dialog is still open (client exit or plugin shutdown).
	 *
	 * @return the pending save, or {@code null} if no dialog was pending
	 */
	private Future<?> savePendingPrompt()
	{
		return handleEndedSession(pendingPrompt.getAndSet(null), SaveDecision.EndReason.PROMPT_UNRESOLVED);
	}

	/**
	 * Writes the session as the account's active session file (on the executor). The session is not turned into a
	 * ledger record.
	 */
	private Future<?> persistActive(SessionTracker.Summary summary)
	{
		return persistActive(SessionSnapshot.fromSummary(summary, lastCompletionCounters, System.currentTimeMillis()));
	}

	private Future<?> persistActive(SessionSnapshot snapshot)
	{
		final long account = snapshot.getAccountHash();
		return executor.submit(() ->
		{
			try
			{
				store.writeActive(account, snapshot);
				log.debug("Persisted active Slayer Ledger session {}", snapshot.getSessionId());
			}
			catch (IOException e)
			{
				log.warn("Unable to persist the active Slayer Ledger session", e);
			}
		});
	}

	/**
	 * Client thread. On logout the in-memory session is kept (and Logged out) but a copy is persisted in case the
	 * client is closed before the next login. Without a session, an Ended assignment's marker is persisted instead.
	 */
	private void persistOnLogout()
	{
		final SessionTracker.Summary summary = tracker.activeSummary();
		if (summary != null
			&& SaveDecision.decide(summary, SaveDecision.EndReason.LOGOUT, config.confirmIncomplete())
			== SaveDecision.Action.PERSIST_ACTIVE)
		{
			logoutSnapshot = SessionSnapshot.fromSummary(summary, lastCompletionCounters, System.currentTimeMillis());
			persistActive(logoutSnapshot);
		}
		else
		{
			logoutSnapshot = null;
			if (summary == null)
			{
				persistEndedMarker();
			}
		}
	}

	/**
	 * Any thread. Persists the Ended assignment's marker ({@link SessionTracker#endedMarker}) as the account's active
	 * session file, so the assignment stays Ended after a client restart; it never becomes a record. Only while no
	 * session is active (End task ended it), so it never replaces a session's file.
	 *
	 * @return the pending write, or {@code null} when nothing is Ended
	 */
	private Future<?> persistEndedMarker()
	{
		if (tracker.getState().isActive())
		{
			return null;
		}
		final SessionSnapshot marker = tracker.endedMarker(lastCompletionCounters, System.currentTimeMillis());
		return marker == null ? null : persistActive(marker);
	}

	/**
	 * Captures the record's item prices on the client thread (prices are recorded as of the save), then appends it on
	 * the executor and removes the account's active session file if it holds the same session. Its spot was decided
	 * with the record ({@link LedgerRecord#fromSummary}); no location name is saved. {@code onSaved}, if given,
	 * runs on the executor after a successful append. If pricing fails the record is saved without values.
	 *
	 * @return completes when the write has finished (or failed)
	 */
	private Future<?> saveRecord(long account, LedgerRecord record, Runnable onSaved)
	{
		final CompletableFuture<Void> done = new CompletableFuture<>();
		// Runs immediately when already on the client thread. The client thread keeps running while ClientShutdown
		// consumers are awaited, so this also completes on client exit.
		clientThread.invoke(() ->
		{
			try
			{
				record.captureValues(lookups.priceLookup());
			}
			catch (RuntimeException e)
			{
				log.warn("Unable to price Slayer Ledger record {}", record.getId(), e);
			}
			finally
			{
				try
				{
					executor.execute(() ->
					{
						try
						{
							writeRecord(account, record, onSaved);
						}
						finally
						{
							done.complete(null);
						}
					});
				}
				catch (RejectedExecutionException e)
				{
					log.warn("Unable to save Slayer Ledger record {}", record.getId(), e);
					done.completeExceptionally(e);
				}
			}
		});
		return done;
	}

	/**
	 * Executor. Appends the record and removes the matching active session file, then saves any records kept by
	 * earlier failed saves. If the append fails, the record is kept in a side file for the next save or ledger load
	 * ({@link LedgerStore#writeUnsaved}) and the panel shows {@link SlayerLedgerPanel#SAVE_FAILED_MESSAGE}.
	 */
	private void writeRecord(long account, LedgerRecord record, Runnable onSaved)
	{
		List<LedgerRecord> updatedRecords;
		try
		{
			updatedRecords = store.append(account, record);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Unable to save Slayer Ledger record {}; keeping it to save later", record.getId(), e);
			boolean keptRecord = false;
			try
			{
				store.writeUnsaved(account, record);
				keptRecord = true;
			}
			catch (IOException | RuntimeException keepFailure)
			{
				log.warn("Unable to keep unsaved Slayer Ledger record {}", record.getId(), keepFailure);
			}
			if (keptRecord)
			{
				// A stale active file of this session (from a logout mid-task) must not become a second, partial
				// record of it; readActive also ignores a session that is kept.
				try
				{
					store.deleteActive(account, record.getId());
				}
				catch (IOException | RuntimeException deleteFailure)
				{
					log.warn("Unable to remove the active session of kept Slayer Ledger record {}",
						record.getId(), deleteFailure);
				}
			}
			publishHistoryError(account, SlayerLedgerPanel.SAVE_FAILED_MESSAGE);
			return;
		}

		final KeptRecords kept = saveKeptRecords(account);
		if (kept.records != null)
		{
			updatedRecords = kept.records;
		}

		try
		{
			publishRecords(account, updatedRecords);
		}
		catch (RuntimeException e)
		{
			log.warn("Unable to publish saved Slayer Ledger records for account {}", account, e);
		}
		if (kept.failed)
		{
			// Posted after the records' update, so the refresh does not immediately clear it.
			publishHistoryError(account, SlayerLedgerPanel.SAVE_FAILED_MESSAGE);
		}
		// After the append: a crash in between leaves an active file whose id is already in the ledger,
		// which readActive detects and removes instead of restoring it.
		try
		{
			store.deleteActive(account, record.getId());
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Unable to remove saved Slayer Ledger active session {}", record.getId(), e);
		}
		// A partial notice for an earlier session stays until a later session is saved: this one.
		final SlayerLedgerPanel savedPanel = panel;
		if (savedPanel != null)
		{
			SwingUtilities.invokeLater(() -> savedPanel.sessionSaved(record.getId()));
		}
		if (onSaved != null)
		{
			try
			{
				onSaved.run();
			}
			catch (RuntimeException e)
			{
				log.warn("Unable to run post-save action for Slayer Ledger record {}", record.getId(), e);
			}
		}
	}

	/**
	 * Executor. Appends the records kept by earlier failed saves ({@link LedgerStore#appendUnsaved}). Their post-save
	 * actions (the task-complete chat line) are not run: the task ended earlier.
	 */
	private KeptRecords saveKeptRecords(long account)
	{
		try
		{
			return new KeptRecords(store.appendUnsaved(account), false);
		}
		catch (LedgerStore.UnsavedRecordsException e)
		{
			log.warn("Unable to save kept Slayer Ledger records; keeping them for the next try", e);
			// Any kept records saved before the failure are in the ledger; show them.
			return new KeptRecords(e.getRecords(), true);
		}
		catch (IOException | RuntimeException e)
		{
			// The side files could not be listed (for example no ledger directory), so none are known to be waiting.
			log.warn("Unable to check for kept Slayer Ledger records", e);
			return new KeptRecords(null, false);
		}
	}

	/** The result of {@link #saveKeptRecords}. */
	@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
	private static final class KeptRecords
	{
		/** The updated record list, or {@code null} if no kept record was saved. */
		final List<LedgerRecord> records;
		/** Whether a kept record could not be saved and is still waiting. */
		final boolean failed;
	}

	private Future<?> deleteActive(long account, String sessionId)
	{
		if (account == SessionTracker.NO_ACCOUNT || sessionId == null)
		{
			return null;
		}
		return executor.submit(() ->
		{
			try
			{
				store.deleteActive(account, sessionId);
			}
			catch (IOException e)
			{
				log.warn("Unable to delete the active Slayer Ledger session", e);
			}
		});
	}

	/** Any thread. Deletes the account's Ended marker, if its active session file holds one, on the executor. */
	private Future<?> deleteEndedMarker(long account)
	{
		if (account == SessionTracker.NO_ACCOUNT)
		{
			return null;
		}
		return executor.submit(() ->
		{
			try
			{
				store.deleteEndedMarker(account);
			}
			catch (IOException e)
			{
				log.warn("Unable to delete the Ended Slayer Ledger assignment's marker", e);
			}
		});
	}

	/**
	 * Client thread. Loads the account's active session file on the executor, then applies it on the client thread.
	 */
	private void loadActiveSession(long account)
	{
		final int generation = ++loadGeneration;
		executor.execute(() ->
		{
			SessionSnapshot snapshot = null;
			try
			{
				snapshot = store.readActive(account);
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Unable to read the active Slayer Ledger session", e);
			}
			finally
			{
				// Always hand back to the client thread so restorePending is cleared.
				final SessionSnapshot loaded = snapshot;
				clientThread.invokeLater(() -> applyActiveSession(account, loaded, generation));
			}
		});
	}

	/**
	 * Client thread. Restores a loaded active session as Logged out (the next task kill restarts it) if the task still
	 * matches, otherwise saves it as a partial record with the Discard notice. An Ended assignment's marker restores
	 * only the Ended flag, and is deleted either way once read: it never becomes a record.
	 */
	private void applyActiveSession(long account, SessionSnapshot snapshot, int generation)
	{
		if (generation != loadGeneration)
		{
			// Superseded by a newer load (or cancelled by logout); that one owns restorePending.
			return;
		}
		try
		{
			if (snapshot == null || account != accountHash)
			{
				// Nothing to restore, or another account logged in meanwhile (the file stays for later).
				return;
			}
			if (!tracker.isTaskObserved())
			{
				// Logged out (or switched) before this ran; try again after the next settled observation.
				restoreCheckNeeded = true;
				return;
			}
			final SessionTracker.Summary active = tracker.activeSummary();
			if (active != null && snapshot.getSessionId() != null && snapshot.getSessionId().equals(active.getSessionId()))
			{
				// Already in memory (e.g. a stale load after a quick plugin restart); never turn it into a record too.
				return;
			}
			// The file is per account; it is authoritative for which account the session belongs to.
			snapshot.setAccountHash(account);
			if (snapshot.isEndedMarker())
			{
				applyEndedMarker(account, snapshot);
				return;
			}
			switch (tracker.restore(snapshot, lastCompletionCounters))
			{
				case RESTORED:
					log.debug("Restored Slayer Ledger session {}", snapshot.getSessionId());
					break;
				case REFUSED:
					// A session is active in memory; keep the file and check again after the next login.
					restoreCheckNeeded = true;
					return;
				case MISMATCH:
				default:
					log.debug("Active Slayer Ledger session {} does not match the current task", snapshot.getSessionId());
					// Its spot is decided with the table, as for a live session.
					handleEndedSession(snapshot.toSummary(spots), SaveDecision.EndReason.RESTORE_MISMATCH);
					break;
			}
		}
		finally
		{
			restorePending = false;
			// Applied after the tick's own live check: a session it restored (or ended) is told to the history model
			// now, not on the next tick, so its live checks see it whatever the EDT ran first.
			refreshHistoryModelIfLiveChanged();
		}
	}

	/**
	 * Client thread. An Ended assignment's marker: the assignment stays Ended when it is still the current one
	 * ({@link SessionTracker#restoreEnded}); the marker is deleted either way (the next logout writes a new one while it
	 * is Ended), unless the check must wait for a session in memory.
	 */
	private void applyEndedMarker(long account, SessionSnapshot marker)
	{
		final SessionTracker.RestoreResult result = tracker.restoreEnded(marker, lastCompletionCounters);
		if (result == SessionTracker.RestoreResult.REFUSED)
		{
			// A session is active in memory; keep the file and check again after the next login.
			restoreCheckNeeded = true;
			return;
		}
		log.debug("Ended Slayer Ledger assignment {}: {}", marker.getSessionId(), result);
		deleteActive(account, marker.getSessionId());
	}

	/** Loads the account's ledger on the executor and shows it in the panel. */
	private void loadLedger(long account)
	{
		executor.execute(() -> reloadLedger(account));
	}

	/**
	 * Executor. Saves the records kept by earlier failed saves, then reads the account's ledger and shows it in the
	 * panel, or a load-failure message; while a kept record still cannot be saved, the save-failed message too.
	 */
	private void reloadLedger(long account)
	{
		final boolean keptRecordsFailed = saveKeptRecords(account).failed;
		try
		{
			final LedgerStore.Contents contents = store.loadContents(account);
			publishRecords(account, contents.getRecords());
			publishAssignments(account, contents.getAssignments());
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Unable to load Slayer Ledger records", e);
			publishHistoryMessage(account, SlayerLedgerPanel.LOAD_FAILED_MESSAGE);
		}
		if (keptRecordsFailed)
		{
			// Posted after the history's update, so the refresh does not immediately clear it.
			publishHistoryError(account, SlayerLedgerPanel.SAVE_FAILED_MESSAGE);
		}
	}

	/**
	 * Any thread. Gives the history model (which the panel shows) an account's records on the EDT, but only if that
	 * account's history is still the one to show when the update runs; results for a previous account or a
	 * logged-out client are dropped.
	 */
	private void publishRecords(long account, List<LedgerRecord> records)
	{
		final LedgerHistoryModel m = historyModel;
		if (m == null)
		{
			return;
		}
		final List<LedgerRecord> copy = new ArrayList<>(records);
		SwingUtilities.invokeLater(() ->
		{
			if (account == historyAccount)
			{
				m.setRecords(account, copy);
			}
		});
	}

	/**
	 * Any thread. Gives the history model an account's entries for the panel's "Skipped / blocked" section, guarded
	 * like {@link #publishRecords}. Entries never go to the played-task history.
	 */
	private void publishAssignments(long account, List<AssignmentEntry> entries)
	{
		final LedgerHistoryModel m = historyModel;
		if (m == null)
		{
			return;
		}
		final List<AssignmentEntry> copy = new ArrayList<>(entries);
		SwingUtilities.invokeLater(() ->
		{
			if (account == historyAccount)
			{
				m.setAssignments(account, copy);
			}
		});
	}

	/** Any thread. Like {@link #publishRecords} but replaces the history with a message. */
	private void publishHistoryMessage(long account, String message)
	{
		final LedgerHistoryModel m = historyModel;
		if (m == null)
		{
			return;
		}
		SwingUtilities.invokeLater(() ->
		{
			if (account == historyAccount)
			{
				m.clearRecords(account, message);
			}
		});
	}

	/**
	 * Client thread. Changes whose history the panel shows, clearing it at once (so another account's rows are never
	 * left on screen), and loads the new account's ledger.
	 */
	private void setHistoryAccount(long account)
	{
		if (account == historyAccount)
		{
			return;
		}
		historyAccount = account;
		if (account == SessionTracker.NO_ACCOUNT)
		{
			publishHistoryMessage(account, SlayerLedgerPanel.LOGGED_OUT_MESSAGE);
		}
		else
		{
			publishHistoryMessage(account, SlayerLedgerPanel.LOADING_MESSAGE);
			loadLedger(account);
		}
	}

	/**
	 * Client thread. Detects account changes, persists sessions that belong to another account and loads the
	 * ledger.
	 */
	private void checkAccount()
	{
		final long hash = client.getAccountHash();
		// Also covers re-login to the same account, whose history was hidden at the login screen.
		setHistoryAccount(hash);
		if (hash == accountHash)
		{
			return;
		}
		accountHash = hash;
		tracker.setAccount(hash);
		tracker.clearCurrentTask();
		// A partial notice of the previous account's session is not this account's to Discard.
		final SlayerLedgerPanel p = panel;
		if (p != null)
		{
			SwingUtilities.invokeLater(() -> p.accountChanged(hash));
		}
		// The XP read so far was the previous account's.
		resetXpBaseline();
		// So was the blowpipe.
		blowpipeContents.forget();
		// The watched assignment belonged to the previous account.
		assignmentWatch.forget();
		cancelPendingRestore();
		restoreCheckNeeded = true;
		// A completed session held for late loot is saved under its own account, as completed.
		flushPendingCompletion();
		// Another account's session is persisted as that account's active session, not saved as partial.
		handleEndedSession(tracker.stopAndResetIfOtherAccount(hash), SaveDecision.EndReason.ACCOUNT_SWITCH);
		// The region names were the previous account's: an account with no RuneScape profile yet posts no profile
		// change. Forgotten after the saves above (named on this thread, as they ran), so those still get their names.
		forgetLocationNames();
	}

	/**
	 * Client thread, every settled tick. Reads every skill's XP ({@code Client#getSkillExperience}, as core
	 * XpTrackerPlugin reads it) into a reused array and gives it to the tracker ({@link SessionTracker#observeXp}),
	 * keyed by the {@link Skill} constant's name for {@link SkillXp}; {@code null} while the stats are not synced
	 * (Hitpoints XP reads 0, which no synced account has: Hitpoints starts at level 10). A reading equal to the last one
	 * the tracker got is not passed again: the tracker would find no gain and keep an equal baseline, so only
	 * ticks with an XP change build a map. {@link #resetXpBaseline} keeps this in step with the tracker's baseline.
	 */
	private void observeSkillXp()
	{
		if (client.getSkillExperience(Skill.HITPOINTS) <= 0)
		{
			xpSent = false;
			tracker.observeXp(null);
			return;
		}
		for (int i = 0; i < SKILLS.length; i++)
		{
			xpReading[i] = client.getSkillExperience(SKILLS[i]);
		}
		if (xpSent && Arrays.equals(xpReading, xpLastSent))
		{
			return;
		}
		final Map<String, Integer> xp = new HashMap<>();
		for (int i = 0; i < SKILLS.length; i++)
		{
			xp.put(SKILLS[i].name(), xpReading[i]);
		}
		tracker.observeXp(xp);
		System.arraycopy(xpReading, 0, xpLastSent, 0, SKILLS.length);
		xpSent = true;
	}

	/**
	 * Client thread. Drops the tracker's XP baseline ({@link SessionTracker#resetXpBaseline}), so the next settled
	 * reading is only a baseline, and makes {@link #observeSkillXp} pass that reading even if it equals the last one.
	 */
	private void resetXpBaseline()
	{
		tracker.resetXpBaseline();
		xpSent = false;
	}

	private void resetClientState()
	{
		completionSignal.reset();
		// Shown again from the next settled reading; the counter it is for is kept across a hop.
		currentTaskStreak = null;
		// The stats are re-sent after (re)login or a hop: the next settled reading is only a baseline.
		resetXpBaseline();
		taskRewards.reset();
		// Drops an undecided end; the next reading is a baseline. The current assignment is kept (not ended by logout).
		assignmentWatch.reset();
		settleTicks = LOGIN_SETTLE_TICKS;
		// Containers are re-sent after (re)login or a hop; start supplies from a fresh baseline.
		if (supplyTracker != null)
		{
			supplyTracker.reset();
		}
		// Includes the plugin starting while logged in, when no ItemContainerChanged comes for the current inventory.
		inventoryNoteTries = INVENTORY_NOTE_TRIES;
		supplyExclusions.reset();
		skippedSupplyTicks.reset();
		chargedWeaponUsage.reset();
		chargeExclusions.reset();
		// NPC indexes are re-sent after (re)login or a hop; the deaths kept for pairing go with them, and so do the
		// attacks, cannon shots, hits and kills their styles and contexts would come from.
		damagedNpcs.clear();
		playerAttacks.clear();
		cannonFire.reset();
		hitNpcNames.clear();
		killContextsDue = null;
		pairKillAt = SessionTracker.NO_TIME;
		// The first settled reading after it is a baseline, never a drop.
		zeroDropTick = NO_TICK;
		lastRemainingRead = -1;
		slaughterKillPending = false;
		heldSuperiorSpawns = 0;
		// Hidden after a login or hop, and in step with a new panel (startUp), which starts with it hidden.
		publishDartHint(false);
		sessionValueTick = SupplyExclusions.NEVER_TICK;
		// A paused session is valued once more after a login or hop, as on its first tick.
		pausedValuesSession = null;
	}

	/**
	 * Client thread. Assigned task size as computed by core SlayerPlugin (1.13.3): SLAYER_COUNT_ORIGINAL adjusted by
	 * an amount modifier when SLAYER_MODIFIER_ID is 2.
	 */
	private int readOriginalAmount()
	{
		int amount = client.getVarpValue(VarPlayerID.SLAYER_COUNT_ORIGINAL);
		if (client.getVarbitValue(VarbitID.SLAYER_MODIFIER_ID) == AMOUNT_MODIFIER_ID)
		{
			boolean negative = client.getVarbitValue(VarbitID.SLAYER_MODIFIER_NEGATIVE) == 1;
			int value = client.getVarbitValue(VarbitID.SLAYER_MODIFIER_VALUE);
			amount += negative ? -value : value;
		}
		return amount > 0 ? amount : SessionTracker.UNKNOWN_AMOUNT;
	}

	/**
	 * Client thread. Who assigned the current task: the SLAYER_MASTER varbit and the SLAYER_AREA varp (Konar's
	 * location), with the area named from the SlayerArea DB table as core SlayerPlugin does (1.13.4).
	 */
	private TaskContext readAssignment()
	{
		final int master = client.getVarbitValue(VarbitID.SLAYER_MASTER);
		final int area = client.getVarpValue(VarPlayerID.SLAYER_AREA);
		return TaskContext.assignment(master, area, area > 0 ? dbLookups.lookupAreaName(area) : null);
	}
}
