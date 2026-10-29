package com.shmayvan.slayerledger;

import com.google.gson.Gson;
import com.google.inject.Binder;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Slayer Ledger",
	description = "Tracks your Slayer tasks: kills, time, loot, supplies and XP, with task history, estimates and trends.",
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
	 * {@code locationName.<regionId>}. Kept per RuneScape profile, like {@link #BLOWPIPE_DART_KEY}.
	 */
	static final String LOCATION_NAME_KEY_PREFIX = "locationName.";
	/**
	 * RSProfile config key prefix (in {@link SlayerLedgerConfig#GROUP}) of a map region the player chose Skip for in
	 * the "Name this location" prompt: {@code locationSkip.<regionId>}, {@code true}. Cleared by the "Ask again
	 * about skipped places" setting.
	 */
	static final String LOCATION_SKIP_KEY_PREFIX = "locationSkip.";

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

	private final CompletionGraceTicker completionGraceTicker = new CompletionGraceTicker();

	/**
	 * Source of the ledger directory, or {@code null} for the plugin directory ({@link #getPluginDirectory()}). Only
	 * tests set it (see {@link #setLedgerDirectory}), so they never touch the real RuneLite directory.
	 */
	private LedgerStore.DirectorySource ledgerDirectorySource;
	/**
	 * The bundled drop snapshot the luck meter reads (relative to {@link LuckMeter}). Only tests change it (see
	 * {@link #setLuckSnapshotResource}), to start the plugin without it.
	 */
	private String luckSnapshotResource = LuckMeter.SNAPSHOT_RESOURCE;
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
	 * prices, names and settings to it, the panel observes and shows it, and it decides the new-task chat preview and
	 * the overlay's historical time per kill. Used on the EDT only (see
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
	/**
	 * Client thread. The "not tracking" nudge after a task kill while idle or paused. Kept across plugin
	 * restarts, so an assignment already nudged in this client run is not nudged again.
	 */
	private final TrackingNudge trackingNudge = new TrackingNudge();
	private int settleTicks = LOGIN_SETTLE_TICKS;
	/** Tick on which a completed session was held for late loot; its grace countdown starts on the next tick. */
	private int completionHeldTick = -1;
	/** Check for a persisted active session after the next settled observation (new login or account). */
	private boolean restoreCheckNeeded;
	/**
	 * Client thread. Why a logout or world hop paused the running session ({@link AutoPauseMessage}), told in chat on
	 * the next login (messages queued while not logged in may be lost); {@code null} when nothing is to be told.
	 */
	private AutoPauseMessage.Reason pendingAutoPause;
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
	/** Blowpipe and trident use, counted per attack; client thread only. */
	private final ChargedWeaponUsage chargedWeaponUsage = new ChargedWeaponUsage();
	/** Keeps loading or charging the blowpipe or a trident out of the supplies diff; client thread only. */
	private final ChargeExclusions chargeExclusions = new ChargeExclusions();
	/** NPCs the player damaged while a session ran, whose deaths are the player's kills; client thread only. */
	private final DamagedNpcs damagedNpcs = new DamagedNpcs();
	/**
	 * The blowpipe's dart item id ({@link ChargedWeaponUsage#NO_ITEM} while unknown), mirrored from the RSProfile
	 * config ({@link #BLOWPIPE_DART_KEY}). Written on the client thread, and on RuneScapeProfileChanged (any thread).
	 */
	private volatile int blowpipeDartId = ChargedWeaponUsage.NO_ITEM;
	/** Whether the panel was last asked to show the "Check your blowpipe" hint; client thread only. */
	private boolean dartHintShown;
	/** Which bank openings are new bank trips; client thread only. */
	private final BankTripCounter bankTripCounter = new BankTripCounter();
	/** Which region the panel's "Name this location" prompt asks about; client thread only. */
	private final LocationPrompt locationPrompt = new LocationPrompt();
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
	 * Id of the paused session whose values were worked out while paused with every price known, or {@code null}. A
	 * paused session's loot and supplies can't change, so it is not valued again until it is resumed or the "Loot
	 * price" setting changes. Client thread only.
	 */
	private String pausedValuesSession;
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
	/** True while an active-session file is being loaded; Play is disabled so it cannot race the restore. */
	private volatile boolean restorePending;
	/** Session awaiting the Keep/Discard dialog; saved as partial on shutdown if still unresolved. */
	private final AtomicReference<SessionTracker.Summary> pendingPrompt = new AtomicReference<>();

	@Override
	protected void startUp()
	{
		// Until the client-thread block below has run; see clientStateReady.
		clientStateReady = false;
		tracker = new SessionTracker(System::currentTimeMillis);
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
			lookups::requestCurrentPrices, lookups::requestItemNames, this::postAssignmentPreview,
			this::publishHistoricalPace, executor, SwingUtilities::invokeLater);
		final Gson luckGson = gson;
		final String luckResource = luckSnapshotResource;
		historyModel.setLuckMeterSource(() -> luckGson == null ? null : LuckMeter.load(luckGson, luckResource));
		historyModel.setGroundItemsHidden(groundItemsHidden);
		panel = new SlayerLedgerPanel(this, tracker, historyModel, gson);
		// startUp runs on the EDT, so the panel's sections can be set here.
		for (SlayerLedgerPanel.OptionalSection section : SlayerLedgerPanel.OptionalSection.values())
		{
			panel.setSectionShown(section, isSectionShown(section));
		}
		historyModel.setTaskAdvisor(config.taskAdvisor(), taskAdvisorMetric());
		taskOverlay = new SlayerLedgerOverlay(this, config);
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
		ignoredLoot = IgnoredLoot.parse(config.ignoredLoot());
		notableDropThreshold = NotableDrops.threshold(config.notableDropThreshold());
		// Queued (startUp runs on the EDT). Client-thread state is reset here, not on the EDT, so it is only ever touched
		// on the client thread; the handlers wait for it (clientStateReady).
		clientThread.invoke(() ->
		{
			try
			{
				restoreCheckNeeded = true;
				pendingAutoPause = null;
				supplyTracker = new SupplyTracker(supplyReader::classifyItem);
				supplyReader.reset();
				bankTripCounter.reset();
				sessionValuation.reset();
				// Item names are looked up afresh in each run of the plugin.
				hiddenLoot.setList(GroundItemsHidden.NONE);
				streakCounter = StreakMilestone.NO_COUNTER;
				// A not-tracking row from before the plugin was turned off must not show; the nudged assignment is
				// kept. Not in resetClientState, so a hop keeps the row. The overlay shows nothing until this has run.
				trackingNudge.hideRow();
				resetClientState();
				// Today's prices are looked up afresh in each run of the plugin, and so are item and location names.
				lookups.reset();
				locationPrompt.reset();
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
				// "Ask again about skipped places" ticked while the plugin was off.
				applyLocationSkipReset();
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

	/** Test seam: keeps the ledger files in {@code directory} instead of the plugin directory. Call before startUp. */
	void setLedgerDirectory(Filepath directory)
	{
		setLedgerDirectorySource(() -> directory);
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

	/** Test seam: reads the luck meter's drop snapshot from {@code resource} instead. Call before startUp. */
	void setLuckSnapshotResource(String resource)
	{
		luckSnapshotResource = resource;
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
	 * way. A "Panel sections" setting shows or hides its sidebar section on the EDT. "Ask again about skipped
	 * places" is applied on the client thread.
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
		else if (SlayerLedgerConfig.RESET_LOCATION_SKIPS.equals(event.getKey()))
		{
			clientThread.invoke(this::applyLocationSkipReset);
		}
		else if (SlayerLedgerConfig.TASK_ADVISOR.equals(event.getKey())
			|| SlayerLedgerConfig.TASK_ADVISOR_GOAL.equals(event.getKey()))
		{
			final boolean enabled = config.taskAdvisor();
			final TaskRanking.Metric metric = taskAdvisorMetric();
			final LedgerHistoryModel m = historyModel;
			if (m != null)
			{
				SwingUtilities.invokeLater(() -> m.setTaskAdvisor(enabled, metric));
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
			case SlayerLedgerConfig.SHOW_WHAT_TO_BRING:
				return SlayerLedgerPanel.OptionalSection.WHAT_TO_BRING;
			case SlayerLedgerConfig.SHOW_TRENDS:
				return SlayerLedgerPanel.OptionalSection.TRENDS;
			case SlayerLedgerConfig.SHOW_OVERALL_TOTALS:
				return SlayerLedgerPanel.OptionalSection.OVERALL_TOTALS;
			case SlayerLedgerConfig.SHOW_LUCK_METER:
				return SlayerLedgerPanel.OptionalSection.LUCK_METER;
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
			case WHAT_TO_BRING:
				return config.showWhatToBring();
			case TRENDS:
				return config.showTrends();
			case OVERALL_TOTALS:
				return config.showOverallTotals();
			case LUCK_METER:
				return config.showLuckMeter();
			default:
				return config.showSkippedBlocked();
		}
	}

	/** The "Task advisor goal" setting's metric, Profit/h when unset. */
	private TaskRanking.Metric taskAdvisorMetric()
	{
		final TaskAdvisorGoal goal = config.taskAdvisorGoal();
		return (goal == null ? TaskAdvisorGoal.PROFIT : goal).metric();
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
				noteAutoPause(tracker.autoPause(), AutoPauseMessage.Reason.LOGOUT);
				cancelPendingRestore();
				// Keep the session in memory (a re-login resumes it) but persist it in case the client is closed.
				persistOnLogout();
				restoreCheckNeeded = true;
				// The next login may be a different account; never show or adopt the old task.
				tracker.clearCurrentTask();
				trackingNudge.hideRow();
				streakCounter = StreakMilestone.NO_COUNTER;
				loggedIn = false;
				resetClientState();
				blowpipeDartId = ChargedWeaponUsage.NO_ITEM;
				// The next login may be another profile; a later kill in the region asks again.
				hideLocationPrompt();
				// Hide the history while logged out; it is reloaded on the next login (any account).
				setHistoryAccount(SessionTracker.NO_ACCOUNT);
				break;
			case HOPPING:
				noteAutoPause(tracker.autoPause(), AutoPauseMessage.Reason.WORLD_HOP);
				// fall through
			case LOGGING_IN:
			case CONNECTION_LOST:
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
	 * Client thread. Remembers why a logout or world hop paused the session, if it did ({@code paused}); a session
	 * that was already paused or idle keeps any earlier reason, so the first cause is the one told.
	 */
	private void noteAutoPause(boolean paused, AutoPauseMessage.Reason reason)
	{
		if (paused)
		{
			pendingAutoPause = reason;
		}
	}

	/**
	 * Client thread, on the first settled tick after the login, once any relogin restore check has run. Posts one chat
	 * line telling the player that a logout or world hop paused the session, then forgets the reason. Nothing is posted when the session is no longer paused
	 * (resumed, stopped, or another account's session was put away) or when it belongs to another account.
	 */
	private void announceAutoPause()
	{
		final AutoPauseMessage.Reason reason = pendingAutoPause;
		pendingAutoPause = null;
		if (reason == null || tracker.getState() != SessionTracker.State.PAUSED
			|| tracker.getSessionAccountHash() != client.getAccountHash())
		{
			return;
		}
		postChatLine(AutoPauseMessage.format(reason));
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
	 * Client thread. Another account or RuneScape profile is active: its region names and skipped regions are read
	 * afresh, and the history model forgets the names it has (the panel then hides the prompt). A pending "Ask again
	 * about skipped places" applies to it.
	 */
	private void forgetLocationNames()
	{
		lookups.forgetLocationNames();
		locationPrompt.hide();
		locationPrompt.forgetSkips();
		applyLocationSkipReset();
		final LedgerHistoryModel m = historyModel;
		if (m != null)
		{
			// The panel hides its prompt when the model forgets the names.
			SwingUtilities.invokeLater(m::clearLocationNames);
		}
	}

	/** Client thread. Hides the "Name this location" prompt without skipping its region. */
	private void hideLocationPrompt()
	{
		locationPrompt.hide();
		final SlayerLedgerPanel p = panel;
		if (p != null)
		{
			SwingUtilities.invokeLater(p::hideLocationPrompt);
		}
	}

	/**
	 * EDT. Saves the player's name for map region {@code region} (the prompt's Save): kept in the RSProfile config, so
	 * it names the running session, every later task there and, for display, saved tasks there without a name.
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
			locationPrompt.named(region);
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
	 * EDT. The prompt's Skip: {@code region} is not asked about again on this RuneScape profile, also after a restart
	 * (kept in its RSProfile config), until the "Ask again about skipped places" setting clears it.
	 */
	void skipLocationPrompt(int region)
	{
		clientThread.invoke(() ->
		{
			// Written first: the write may create the profile, and the profile change it posts forgets the skips held.
			lookups.saveLocationSkip(region);
			locationPrompt.skip(region);
		});
	}

	/**
	 * Client thread. While "Ask again about skipped places" is ticked and a RuneScape profile is active: forgets that
	 * profile's skipped regions (in its config and here) and unticks the setting. Without a profile it stays ticked
	 * until one is active ({@link #forgetLocationNames}).
	 */
	private void applyLocationSkipReset()
	{
		if (!config.resetLocationSkips() || !lookups.clearLocationSkips())
		{
			return;
		}
		locationPrompt.forgetSkips();
		configManager.unsetConfiguration(SlayerLedgerConfig.GROUP, SlayerLedgerConfig.RESET_LOCATION_SKIPS);
	}

	/**
	 * Client thread, after the task is observed. Asks the player to name the region of a kill just counted when it has
	 * no name and the task has no assigned area (see {@link SessionTracker#takeRegionToName()}).
	 */
	private void offerLocationPrompt(int region)
	{
		if (!LocationTally.isRegion(region) || !locationPrompt.offer(region, lookups.locationName(region) != null,
			lookups.isLocationSkipped(region)))
		{
			return;
		}
		final SlayerLedgerPanel p = panel;
		if (p != null)
		{
			// The task's Wiki locations are added by the panel, which reads the bundled list.
			final String monster = tracker.getSessionMonster() != null
				? tracker.getSessionMonster() : tracker.getCurrentMonster();
			final List<String> ownNames = lookups.profileLocationNames();
			SwingUtilities.invokeLater(() -> p.showLocationPrompt(region, monster, ownNames));
		}
	}

	/**
	 * Client thread. The map region of the local player's tile ({@link WorldPoint#fromLocalInstance}, so an instance is
	 * its template's region), or {@link LocationTally#NO_REGION} while there is no player or scene, or while the player
	 * is on a Sailing boat (the tile is in the boat's own world view, whose template region is not where the boat is).
	 * Never throws, so the task is always observed.
	 */
	private int readRegion()
	{
		try
		{
			final Player player = client.getLocalPlayer();
			final LocalPoint local = player == null ? null : player.getLocalLocation();
			final WorldView view = local == null ? null : client.getWorldView(local.getWorldView());
			if (view == null || !view.isTopLevel())
			{
				return LocationTally.NO_REGION;
			}
			return WorldPoint.fromLocalInstance(client, local).getRegionID();
		}
		catch (RuntimeException e)
		{
			log.debug("Unable to read the local player's region", e);
			return LocationTally.NO_REGION;
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
		// Before observing the task, so supplies used on the tick the task completes still count.
		trackSupplies();
		// The menu clicks and messages that open a charging window arrive before this tick's GameTick, so trackSupplies
		// has already left the charged materials out of this tick's diff. Weapon use comes from the attack itself, not
		// the diff, so it is counted next and valued with the rest of this tick's supplies.
		trackChargedWeapons();
		updateSessionValues();

		if (settleTicks > 0)
		{
			settleTicks--;
			return;
		}

		// Before observing the task, so XP gained on the tick the task completes still counts; a completion held for
		// late loot is no longer running, so it gets no XP after its completion tick.
		observeSkillXp();

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

		// Decide before observing so Play stays disabled from the first settled observation until the restore ran.
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
		final int region = readRegion();
		switch (tracker.observe(name, creatureId, remaining, originalAmount, assignment, region, completionSignaled))
		{
			case COMPLETED:
				log.debug("Slayer task completed");
				// The completion was signalled on this tick, so the incremented counter is this completion's streak.
				taskRewards.startCompletion(tracker.getSessionId(), counters, completionSignal.incrementedCounter());
				tracker.setCompletionRewards(taskRewards.getSessionId(), taskRewards.earned(), taskRewards.streak());
				// A running session waits COMPLETION_LOOT_GRACE_TICKS ticks for the final kill's loot before it is
				// saved (see CompletionGraceTicker); a paused one is saved now.
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
		// After the session's decision, so the final kill of a task held for late loot is offered too.
		offerLocationPrompt(tracker.takeRegionToName());
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
		// After the session's decision, so a kill a running session counted, or the kill that completed it, never nudges.
		nudgeIfNotTracking(name, creatureId, remaining, originalAmount);

		if (pendingFileRestore || pendingReloginSnapshot != null)
		{
			runPendingRestoreCheck(remaining, counters);
		}
		// After the restore check and this tick's task decision, so a session that one of them ended gets no line.
		if (pendingAutoPause != null && !pendingFileRestore && pendingReloginSnapshot == null)
		{
			announceAutoPause();
		}
		// Last, so a session this tick started, restored or ended is seen.
		refreshHistoryModelIfLiveChanged();
	}

	/**
	 * Client thread, every settled tick. When the task, its kills left, its location or the session changed
	 * ({@link LedgerHistoryModel#liveInputs}), asks the history model on the EDT to check the waiting chat preview
	 * again and publish the overlay's historical time per kill; otherwise, while the model waits for today's prices,
	 * asks it to end that wait once it has run out. So none of these waits for the panel's refresh timer.
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

	/** Test seam: the sidebar panel, or {@code null} while the plugin is stopped. Use it on the EDT. */
	SlayerLedgerPanel panel()
	{
		return panel;
	}

	/** Test seam: stands {@code valuation} in for the live session valuation. Call on the client thread. */
	void setSessionValuation(SessionValuation valuation)
	{
		sessionValuation = valuation;
	}

	/** Test seam: the game-tick subscriber the plugin registers on the event bus next to itself. */
	Object completionGraceTicker()
	{
		return completionGraceTicker;
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
	 * Client thread, every settled tick. Tells the player, once per assignment, that a task kill was not tracked because
	 * no session is running or the session is paused ({@link TrackingNudge}); the overlay row comes from
	 * {@link #overlayData()}. Only informs: it never starts or resumes a session. Quiet while a restore check is pending
	 * (the session may yet be restored) and for another account's session.
	 */
	private void nudgeIfNotTracking(String name, int creatureId, int remaining, int originalAmount)
	{
		SessionTracker.State state = tracker.getState();
		final boolean suppressed = restorePending || pendingFileRestore || pendingReloginSnapshot != null
			|| accountHash == SessionTracker.NO_ACCOUNT
			|| (state == SessionTracker.State.PAUSED && tracker.getSessionAccountHash() != accountHash);
		final TrackingNudge.Line line = trackingNudge.update(accountHash, name, creatureId, remaining, originalAmount,
			state, suppressed);
		if (line == TrackingNudge.Line.NONE)
		{
			return;
		}
		postChatLine(TrackingNudge.message(line));
	}

	/**
	 * Client thread, every settled tick, whether or not a session is running (only varps and varbits are read). Saves
	 * each assignment that ended without a session, and asks the history model for a new one's chat preview; see
	 * {@link AssignmentWatch}.
	 */
	private void watchAssignments(AssignmentWatch.Reading reading)
	{
		final List<AssignmentWatch.Ended> ended = assignmentWatch.update(reading, tracker.takeSessionTaskKeys(),
			System.currentTimeMillis());
		// Taken every tick, so a new assignment is previewed on the tick it is seen or never.
		final boolean started = assignmentWatch.takeStarted();
		final long account = accountHash;
		if (account == SessionTracker.NO_ACCOUNT)
		{
			return;
		}
		if (started && config.assignmentPreview())
		{
			// The tracker took this tick's task before the watch did. The history is on the EDT; the history model
			// posts the line through postAssignmentPreview once it has what it needs.
			final int creatureId = tracker.getCurrentCreatureId();
			final String monster = tracker.getCurrentMonster();
			final LedgerHistoryModel m = historyModel;
			if (m != null)
			{
				SwingUtilities.invokeLater(() -> m.requestAssignmentPreview(account, creatureId, monster));
			}
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
	 * NPC loot. Counted only while the session is RUNNING, or for a just-completed session during its late-loot grace
	 * window (enforced by {@link SessionTracker#addLoot}). Loot from any NPC counts, not only the task monster; see
	 * {@link SessionTracker#addLoot}. Bones and demonic ashes a bonecrusher or ash sanctifier destroyed are left out
	 * ({@link CrushedLootFilter}), judged by what is carried at the time of this drop, and so are the player's ignored
	 * items ({@link IgnoredLoot}); see {@link LootFilter}. The kept loot's notable stacks ({@link NotableDrops}) go
	 * with it; they are only priced while loot is recorded ({@link SessionTracker#isRecordingLoot}), so no price
	 * lookups run for loot outside a session.
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
		// Priced only while recording. A Play click between the check and addLoot records this kill's loot without its
		// notable stacks (at most one drop's entries); addLoot still decides loot and drops in one step.
		final boolean priced = !kept.isEmpty() && tracker.isRecordingLoot();
		tracker.addLoot(kept, priced ? findNotableDrops(kept) : null);
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
			return NotableDrops.find(loot, prices, lootPriceMode, notableDropThreshold, this::lootItemName,
				System.currentTimeMillis());
		}
		catch (RuntimeException e)
		{
			log.debug("Unable to value loot for notable drops", e);
			return null;
		}
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
	 * superior spawns ({@link SuperiorSpawnMessage}; RUNNING only, see {@link SessionTracker#addSuperiorSpawn}), learns
	 * the blowpipe's dart type from its check message ({@link BlowpipeMessage}), lets a charging message close a
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
		if (PICKPOCKET_REGEX.matcher(event.getMessage()).matches())
		{
			pickpocketTick = client.getTickCount();
		}
		if (SuperiorSpawnMessage.matches(type != ChatMessageType.MESBOX, event.getMessage()))
		{
			tracker.addSuperiorSpawn();
		}
		final BlowpipeMessage.Darts darts = BlowpipeMessage.parse(event.getMessage());
		if (darts != null)
		{
			// A check message naming a dart type this plugin doesn't know also forgets the type: darts aren't counted.
			setBlowpipeDartId(darts.isLoaded() ? darts.getDartId() : ChargedWeaponUsage.NO_ITEM);
		}
		chargeExclusions.messageSeen(event.getMessage(), client.getTickCount());
	}

	/**
	 * Client thread. Remembers "Drop" clicks so the dropped item's decrease is not counted as a supply, and loading,
	 * charging, unloading or uncharging the blowpipe or a trident ({@link ChargeExclusions}). Only reads the click;
	 * the menu is never changed.
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
				chargeExclusions.menuOptionClicked(option, useItemIds(event), tick);
			}
			return;
		}
		if ("Unload".equalsIgnoreCase(plain) || "Uncharge".equalsIgnoreCase(plain))
		{
			chargeExclusions.menuOptionClicked(option, new int[]{itemId}, tick);
			if (ChargedWeaponUsage.BLOWPIPES.contains(itemId))
			{
				// The darts come out; the type is learned again from the next check message.
				setBlowpipeDartId(ChargedWeaponUsage.NO_ITEM);
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
			final boolean active = state == SessionTracker.State.RUNNING || state == SessionTracker.State.PAUSED;
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
	 * ({@link #onHitsplatApplied}) is tallied under its name ({@link SessionTracker#addNpcKill}).
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
			if (damagedNpcs.takeDied(worldViewId(npc), npc.getIndex()))
			{
				// Tallied only while RUNNING (the tracker decides); an NPC outside the Catacombs that has a variant's
				// name is not tallied at all (CatacombsVariants#tallyName).
				tracker.addNpcKill(CatacombsVariants.tallyName(npc.getId(), npc.getName()));
			}
		}
	}

	/**
	 * Client thread. Remembers an NPC the player hit while a session is running, so its death counts as the
	 * player's kill in the session's kills per NPC name ({@link SessionTracker#addNpcKill}). Only the player's own
	 * hitsplats count ({@code Hitsplat.isMine()}); {@link DamagedNpcs} keeps a bounded number of NPCs.
	 */
	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (!clientStateReady || !(event.getActor() instanceof NPC) || event.getHitsplat() == null
			|| !event.getHitsplat().isMine() || tracker.getState() != SessionTracker.State.RUNNING)
		{
			return;
		}
		final NPC npc = (NPC) event.getActor();
		damagedNpcs.damaged(worldViewId(npc), npc.getIndex());
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
	 * Client thread. Reads this tick's tracked containers and adds what was used to the session (RUNNING only; the
	 * diff and its rules are in {@link SupplyTracker}). The baseline always moves to this tick, so changes made inside
	 * an excluded interface are never counted later. While no session is running (idle, paused or a completion held
	 * for late loot) the containers are not read and the baseline is dropped: nothing would be counted, and Play
	 * (start or resume) takes a fresh baseline ({@link #takeSupplyBaseline}); only the inventory is read, once after it
	 * changes, to note its potions ({@link #notePotionVariants}). Two exceptions keep the old behaviour:
	 * the excluded interfaces are still checked every tick, so one closed just before Play still shields the first
	 * ticks after it; and while a cannon is set up or loaded ({@link #cannonInUse}) the containers are still diffed
	 * (without counting), so cannonballs loaded before Play are still told apart by type
	 * ({@link SupplyTracker}'s cannonball type is inferred from what leaves the inventory on a load).
	 */
	private void trackSupplies()
	{
		final int tick = client.getTickCount();
		boolean excludedOpen = false;
		for (int group : SUPPLY_EXCLUDED_INTERFACES)
		{
			// An interface is open while its root widget is loaded (as core plugins check).
			if (client.getWidget(group, 0) != null)
			{
				excludedOpen = true;
				break;
			}
		}
		supplyExclusions.beginTick(tick, excludedOpen);

		final boolean running = tracker.getState() == SessionTracker.State.RUNNING;
		if (!running && !cannonInUse())
		{
			supplyTracker.reset();
			notePotionVariants();
			return;
		}
		final SupplyTracker.Observation observation = supplyReader.readSupplyObservation();
		if (observation == null)
		{
			supplyTracker.reset();
			return;
		}
		final boolean excluded = supplyExclusions.isTickExcluded(tick);
		// Charged materials are skipped both ways: loading darts is not a use, and unloading them is not a return.
		final SupplyTracker.Usage usage = supplyTracker.update(observation, running, excluded,
			supplyExclusions.droppedItems(), chargeExclusions.excludedItems(tick));
		if (!usage.isEmpty())
		{
			tracker.addSupplies(usage.getUsed(), usage.getReturned(), usage.getDoses());
		}
	}

	/**
	 * Client thread, while no supplies diff runs. The diff used to see the inventory every tick, and each potion's use
	 * is recorded under the highest-dose variant the tracker has seen ({@link SupplyTracker}); so, after the inventory
	 * changed, its items alone are read once and their potions noted. A potion drunk from 4 to 3 doses before
	 * Play is still recorded as the 4-dose item.
	 *
	 * <p>Each read notes every item whose data is loaded. An item whose data is not loaded yet is retried on the next
	 * idle ticks, at most {@link #INVENTORY_NOTE_TRIES} reads per change: apart from a lookup that threw, only a
	 * wieldable item waiting for its item stats can be in that state ({@link SupplyReader#classifyItem}), and that is
	 * never a potion, so stopping loses no potion. Counting is unchanged: the diff classifies every item it sees while
	 * a session runs.</p>
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
	 * Client thread. Whether the player's cannon is being set up or is set up ({@code DROPCANNON} not 0; core
	 * CannonPlugin treats 4 as placed, 1.13.4) or holds cannonballs ({@code ROCKTHROWER}). A cannon can only be loaded
	 * once it is set up, which takes several ticks, so the supplies diff has a baseline before any load.
	 */
	private boolean cannonInUse()
	{
		return client.getVarpValue(VarPlayerID.DROPCANNON) != 0 || client.getVarpValue(VarPlayerID.ROCKTHROWER) > 0;
	}

	/**
	 * Client thread. Counts this tick's blowpipe shot or trident cast ({@link ChargedWeaponUsage}) from the worn weapon
	 * and cape, the local player's animation and frame, and the trident cast graphic ({@code Actor#hasSpotAnim},
	 * 1.13.4), and adds what it used to the session's supplies. Only while RUNNING: otherwise the carry-overs and the
	 * dart hint are reset, so a stopped or paused session never counts a shot later. Also shows or hides the panel's
	 * "Check your blowpipe" hint.
	 */
	private void trackChargedWeapons()
	{
		final Player player = tracker.getState() == SessionTracker.State.RUNNING ? client.getLocalPlayer() : null;
		if (player == null)
		{
			chargedWeaponUsage.reset();
			publishDartHint(false);
			return;
		}
		final ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		final int weaponId = SupplyReader.wornItemId(worn, EquipmentInventorySlot.WEAPON);
		final int animationId = player.getAnimation();
		boolean tridentGraphic = false;
		if (ChargedWeaponUsage.TRIDENT_ANIMATIONS.contains(animationId))
		{
			for (int graphic : ChargedWeaponUsage.TRIDENT_GRAPHICS)
			{
				tridentGraphic |= player.hasSpotAnim(graphic);
			}
		}
		final Map<Integer, Integer> used = chargedWeaponUsage.update(ChargedWeaponUsage.Tick.builder()
			.tick(client.getTickCount())
			.weaponId(weaponId)
			.animationId(animationId)
			.animationFrame(player.getAnimationFrame())
			.capeId(SupplyReader.wornItemId(worn, EquipmentInventorySlot.CAPE))
			// Dizana's quiver setting (varbit 9803) is not verified in game yet: assume it saves 80%.
			.quiverAmmoSave(null)
			.tridentGraphic(tridentGraphic)
			.vorkathsHeadUpgrade(config.vorkathsHeadUpgrade())
			.dartId(blowpipeDartId)
			.build());
		if (!used.isEmpty())
		{
			final ItemTally items = new ItemTally();
			used.forEach(items::add);
			tracker.addSupplies(items, null, null);
		}
		publishDartHint(chargedWeaponUsage.isDartCheckNeeded() && ChargedWeaponUsage.BLOWPIPES.contains(weaponId));
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
	 * Client thread. Values the running or paused session's loot and supplies for the panel, at most once every
	 * {@link #SESSION_VALUE_TICKS} ticks. ItemManager is only called here, on the client thread; {@link SessionValuation}
	 * caches unit prices for the session and skips the lookups when nothing changed. Results reach the panel on the
	 * EDT, and only when they differ from what it shows. When no session is active the values are cleared.
	 *
	 * <p>A paused session's loot and supplies can't change, so once it has been valued while paused with every GE price
	 * known, it is not valued again (no copy of its tallies is taken) until it is resumed or the "Loot price" setting
	 * changes. While a price is unavailable it keeps being valued, so the retry can still fill it in.</p>
	 */
	private void updateSessionValues()
	{
		final SessionTracker.State state = tracker.getState();
		if (state != SessionTracker.State.RUNNING && state != SessionTracker.State.PAUSED)
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
		if (state != SessionTracker.State.PAUSED)
		{
			pausedValuesSession = null;
		}
		else if (!modeChanged && pausedValuesSession != null && pausedValuesSession.equals(tracker.getSessionId()))
		{
			// Valued while paused already, in this mode and with every price known: nothing can have changed.
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
		pausedValuesSession = state == SessionTracker.State.PAUSED && values.getLootUnpriced() == 0
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
	 * Client thread. Takes a fresh supplies baseline now, so nothing from before a Play (start or resume) is counted.
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

	boolean play()
	{
		if (!loggedIn || restorePending || !tracker.play(accountHash))
		{
			return false;
		}
		// Supplies count from now: anything used before Play (while idle or paused) is never counted.
		clientThread.invoke(this::takeSupplyBaseline);
		refreshHistoryModel();
		return true;
	}

	void pause()
	{
		tracker.pause();
		refreshHistoryModel();
	}

	void stop()
	{
		endSession(SaveDecision.EndReason.PLAYER_STOP);
		refreshHistoryModel();
	}

	boolean canPlay()
	{
		return loggedIn && !restorePending && tracker.canPlay(accountHash);
	}

	/**
	 * What the task overlay shows, or {@code null} when no running or paused session has a task (idle, a no-task
	 * session, or a completed one held for late loot) and no not-tracking nudge is shown. The task comes from one
	 * {@link SessionTracker#liveTask()} call; the live values and the historical time per kill
	 * ({@link #publishHistoricalPace}) are those last published, used only when they belong to the same session. The
	 * Task row shows the name the task box shows: the Catacombs variant and {@code (cannon)}
	 * ({@link SessionTracker#getCurrentTaskSelection}). While IDLE after a task kill
	 * ({@link TrackingNudge}), only the nudge is shown; it goes as soon as the session is no longer IDLE. Called by the
	 * overlay on the client thread every frame, so it stays cheap.
	 */
	OverlayRows.Data overlayData()
	{
		if (tracker == null || !clientStateReady)
		{
			return null;
		}
		SessionTracker.LiveTask task = tracker.liveTask();
		if (task == null)
		{
			return trackingNudge.isRowShown() && tracker.getState() == SessionTracker.State.IDLE
				? OverlayRows.Data.notTracking() : null;
		}

		SessionValuation.Values values = publishedValues;
		if (values != null && !Objects.equals(task.getSessionId(), values.getSessionId()))
		{
			values = null;
		}
		final OverlayRows.HistoricalPace pace = historicalPace;
		// The variant and cannon use the task box shows. The variant is used only when it is this task's,
		// as a separate call could see a new session; a new session can't have fired a cannonball in between.
		final TaskSelection selection = tracker.getCurrentTaskSelection().forTask(task.getMonster());
		return new OverlayRows.Data(task, values, pace == null ? null : pace.millisPerKillFor(task), selection);
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
	 * region, the region's name and other records are kept. Never touches the active session.
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
				return saveRecord(account, record, () -> announceCompletedTask(record, mode));
			}
			case SAVE_PARTIAL:
				return saveRecord(account, LedgerRecord.fromSummary(summary, true), null);
			case SAVE_PARTIAL_WITH_NOTICE:
			{
				final LedgerRecord record = LedgerRecord.fromSummary(summary, true);
				final SlayerLedgerPanel noticePanel = panel;
				// The notice is posted only after the append succeeds, so a failed save never claims "saved".
				return saveRecord(account, record, noticePanel == null ? null
					: () -> SwingUtilities.invokeLater(() -> noticePanel.showPartialNotice(account, record)));
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
	 * Any thread. Queues a completed-task summary after its ledger record has been appended successfully; it is worked
	 * out on the client thread, where the loot Ground Items hides is known.
	 */
	private void announceCompletedTask(LedgerRecord record, LootPriceMode mode)
	{
		clientThread.invoke(() ->
		{
			final String message = TaskCompleteMessage.format(record, mode,
				hiddenLoot(record.getLoot() == null ? null : record.getLoot().keySet()));
			if (message == null)
			{
				return;
			}
			postChatLine(message);
			if (config.taskCompleteNotification())
			{
				notifier.notify(message);
			}
		});
	}

	/**
	 * EDT. Queues a new assignment's chat preview ({@link AssignmentPreviewMessage}) like the task-complete
	 * summary, unless the "New-task chat preview" setting was turned off meanwhile.
	 */
	void postAssignmentPreview(String message)
	{
		clientThread.invoke(() ->
		{
			if (config.assignmentPreview())
			{
				postChatLine(message);
			}
		});
	}

	/**
	 * Client thread. Posts one of the plugin's chat lines (task complete, assignment preview, auto-pause, not
	 * tracking) as a console message named "Slayer Ledger". The plain text is always escaped with
	 * {@link Text#escapeJagex}, so any player-typed part (a location name, a note) shows as typed and can't add tags.
	 * The lines are plain text with no markup of their own, so nothing is escaped twice.
	 */
	private void postChatLine(String text)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.name("Slayer Ledger")
			.runeLiteFormattedMessage(Text.escapeJagex(text))
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
	 * Client thread. On logout the in-memory session is kept (and auto-paused) but a copy is persisted in case the
	 * client is closed before the next login.
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
		}
	}

	/**
	 * Captures the record's item prices on the client thread (prices are recorded as of the save), and its location
	 * name when the task had no assigned area (the player's name for its dominant region, as of the save), then appends
	 * it on the executor and removes the account's active session file if it holds the same session. {@code onSaved},
	 * if given, runs on the executor after a successful append. If pricing fails the record is saved without values.
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
				if (record.getLocation() == null && record.getLocationRegion() != null)
				{
					record.setLocation(TaskLocation.recordName(null, lookups.locationName(record.getLocationRegion())));
				}
			}
			catch (RuntimeException e)
			{
				log.warn("Unable to name the location of Slayer Ledger record {}", record.getId(), e);
			}
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
	private static final class KeptRecords
	{
		/** The updated record list, or {@code null} if no kept record was saved. */
		final List<LedgerRecord> records;
		/** Whether a kept record could not be saved and is still waiting. */
		final boolean failed;

		KeptRecords(List<LedgerRecord> records, boolean failed)
		{
			this.records = records;
			this.failed = failed;
		}
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
	 * Client thread. Restores a loaded active session as PAUSED if the task still matches, otherwise saves it as a
	 * partial record with the Discard notice.
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
					handleEndedSession(snapshot.toSummary(), SaveDecision.EndReason.RESTORE_MISMATCH);
					break;
			}
		}
		finally
		{
			restorePending = false;
		}
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
		tracker.clearCurrentTask();
		// The XP read so far was the previous account's.
		resetXpBaseline();
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
		// The next reading is a baseline too; the nudged assignment is kept, so a relogin onto it is not nudged again.
		trackingNudge.reset();
		settleTicks = LOGIN_SETTLE_TICKS;
		// Containers and the tick count are re-sent after (re)login or a hop; start supplies from a fresh baseline.
		if (supplyTracker != null)
		{
			supplyTracker.reset();
		}
		// Includes the plugin starting while logged in, when no ItemContainerChanged comes for the current inventory.
		inventoryNoteTries = INVENTORY_NOTE_TRIES;
		supplyExclusions.reset();
		chargedWeaponUsage.reset();
		chargeExclusions.reset();
		// NPC indexes are re-sent after (re)login or a hop.
		damagedNpcs.clear();
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
