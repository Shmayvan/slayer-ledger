package com.shmayvan.slayerledger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import com.google.gson.Gson;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.util.Providers;
import java.awt.Image;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.ScriptEvent;
import net.runelite.api.ScriptID;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.DBTableID;
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
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.game.ItemStats;
import net.runelite.client.game.LootManager;
import net.runelite.client.game.NpcUtil;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.Filepath;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.mockito.invocation.InvocationOnMock;

/**
 * Drives the real {@link SlayerLedgerPlugin} through the client's wiring, for plugin-level tests. Use it as a JUnit
 * rule ({@code @Rule public final PluginHarness harness = new PluginHarness();}); see {@code docs/RUNELITE_DEVELOPMENT.md}
 * for when to use it instead of a pure-class test.
 *
 * <p>What is real: the plugin is injected by Guice from a module whose bindings mirror the client's (the plugin is
 * bound and installed as a module the way {@code PluginManager} does, so its {@code @Provides} config method is used);
 * {@link #startUp()} and {@link #shutDown()} run on the EDT and register/unregister the plugin with a real
 * {@link EventBus} in the same order as {@code PluginManager}, so misnamed or duplicate handlers fail here exactly as
 * in the client; the plugin's own subscribers (the priority -1 completion grace ticker) are registered on the same
 * bus; the real {@link LootManager} is on the bus too and turns the loot reported by {@link #lootOnNextTick} into
 * {@link ServerNpcLoot} from its own {@code onGameTick}, so the bus's ordering (priority, then class name) decides
 * where that loot lands relative to the plugin's tick handlers; {@link ClientThread} is the real class (its queueing
 * and re-run-on-false semantics are untouched); the Swing panel is created and updated on the real EDT; and ledger
 * files are written by the real {@link LedgerStore} to a temporary folder, never {@code ~/.runelite}.</p>
 *
 * <p>What is faked: {@link Client}, {@link ItemManager}, item compositions, item containers, the local player, the
 * top-level world view, NPC definitions, NPCs ({@link #npc}) and interface widgets are thread-checked Mockito fakes;
 * the client, item and container fakes return values the test sets (game state, tick count, account, varps/varbits,
 * inventory/equipment, open interfaces, the item dialog's text, the selected "Use" item, task names, item definitions
 * and prices), and the local player its animation, frame, spotanims and location ({@link #setPlayerRegion}; none
 * until set; on a Sailing boat's world view with {@link #setPlayerOnBoat}); {@link ClientToolbar},
 * {@link ConfigManager} and LootManager's {@link NpcUtil} are mocks (the config manager keeps one RSProfile's config
 * in memory, and with {@link #setNoRsProfileWhileLoggedOut} has no profile while logged out); the config is
 * {@link HarnessConfig}. {@code ItemManager.getImage} is the one fake call allowed off the client thread, as RuneLite
 * allows it: the panel calls it on the EDT (the harness fails a call from any other thread), it returns a blank image
 * that is never drawn, and {@link #iconText} tells what an icon shows. {@link SkillIconManager} is a fake too, for the
 * XP lists' skill icons: EDT only as well, a blank image per call, read by {@link #iconText}.</p>
 *
 * <p>Threading. The test thread plays the client thread: {@code client.isClientThread()} is true on it, except while
 * it runs an executor task. So, as in the client, {@code clientThread.invoke} runs at once on the test thread but is
 * queued when called from the EDT or the executor, and {@code invokeLater} is always queued. Queued client-thread
 * work, executor tasks ({@link DeterministicExecutor}) and EDT runnables run only when the harness drains them:
 * {@link #drain()} repeats "client thread queue, executor queue, EDT flush" until nothing is left (an EDT flush also
 * runs EDT work posted by EDT work). Every helper that mimics a client frame ({@link #tick()}, {@link #post}, the
 * game state helpers and the EDT actions) drains afterwards, so by the time it returns all asynchronous work it
 * caused has finished, in the order the client would run it. Use {@link #postWithoutDrain},
 * {@link #runClientThread()}, {@link #runExecutor()} and {@link #flushEdt()} to observe the state in between.
 * A client-thread task that keeps returning {@code false} is re-queued forever, as in the client; {@link #drain()}
 * then fails after {@value #MAX_DRAIN_PASSES} passes instead of hanging.</p>
 *
 * <p>Checks. When the test ends the harness shuts the plugin down if it is still running, drains, deletes the
 * temporary folder and fails the test if anything went wrong out of sight: an exception swallowed by the event bus,
 * the client thread, the executor or the EDT, an uncaught exception on any thread (the harness installs the default
 * uncaught exception handler for the test), or a call to any of the client fakes above made off the client thread.
 * It also counts the fake calls made with plugin code on the stack ({@link SlayerLedgerPlugin} or the helpers it
 * injects, {@link ClientLookups}, {@link SupplyReader} and {@link SlayerDbLookups}; {@link #pluginCallCount()}), so a
 * test can show that a stopped plugin's handlers no longer read the client. Work started from other classes,
 * such as the panel's Swing timer, is not counted.</p>
 */
final class PluginHarness implements TestRule
{
	/** Ticks after login until the plugin has read the task: its LOGIN_SETTLE_TICKS (2) plus the observing tick. */
	static final int SETTLE_TICKS = 3;
	static final long ACCOUNT = 1_234_567_890L;
	/** The RSProfile key the config manager mock reports for its one profile. */
	static final String RS_PROFILE_KEY = "rsprofile.harness";
	static final long OTHER_ACCOUNT = 987_654_321L;
	/** NPC whose kills {@link #lootOnNextTick} reports; {@code client.getNpcDefinition} names it LOOT_NPC_NAME. */
	static final int LOOT_NPC_ID = 415;
	static final String LOOT_NPC_NAME = "Abyssal demon";
	static final int MAX_DRAIN_PASSES = 100;
	/** {@link #playerWorldX} while the local player has no location. */
	private static final int NO_LOCATION = Integer.MIN_VALUE;
	/** Tiles from the scene's base to the local player's tile, as in a loaded scene (the player near its centre). */
	private static final int SCENE_OFFSET = 52;
	/** DB row ids handed out for task names: the task id plus this offset. */
	private static final int TASK_ROW_OFFSET = 10_000;
	/** DB row ids handed out for boss task sublist rows: the boss id plus this offset. */
	private static final int BOSS_SUBLIST_ROW_OFFSET = 30_000;
	/** DB row ids handed out for boss task names (the sublist row's task row): the boss id plus this offset. */
	private static final int BOSS_TASK_ROW_OFFSET = 40_000;
	/** DB row ids handed out for Slayer area names: the area id plus this offset. */
	private static final int AREA_ROW_OFFSET = 50_000;
	/** {@code ClientThread.invoke()} and {@code invokeTickEnd()}, package-private; see {@link HarnessClientThread}. */
	private static final Method INVOKE = clientThreadFrameMethod("invoke");
	private static final Method INVOKE_TICK_END = clientThreadFrameMethod("invokeTickEnd");

	/**
	 * Plugin config with a settable value; Keep/Discard prompts are off by default (turn them on with
	 * {@link #scriptKeepDiscardPrompt}, which also scripts the dialog's answer).
	 */
	static final class HarnessConfig implements SlayerLedgerConfig
	{
		volatile boolean confirmIncomplete;
		volatile boolean taskCompleteNotification;
		volatile boolean assignmentPreview = true;
		volatile boolean showTaskOverlay = true;
		volatile boolean overlayTask = true;
		volatile boolean overlayKills = true;
		volatile boolean overlayTimeLeft = true;
		volatile boolean overlayProfit = true;
		volatile BonecrusherMode bonecrusherHandling = BonecrusherMode.AUTO;
		volatile String ignoredLoot = "";
		volatile LootPriceMode lootPriceMode = LootPriceMode.GE;
		volatile boolean vorkathsHeadUpgrade;
		volatile int notableDropThreshold = NotableDrops.DEFAULT_THRESHOLD;
		volatile boolean showWhatToBring = true;
		volatile boolean showTrends = true;
		volatile boolean showOverallTotals = true;
		volatile boolean showLuckMeter = true;
		volatile boolean showSkippedBlocked = true;
		volatile boolean taskAdvisor = true;
		volatile TaskAdvisorGoal taskAdvisorGoal = TaskAdvisorGoal.PROFIT;
		volatile boolean resetLocationSkips;
		volatile boolean hideGroundItemsHidden = true;

		@Override
		public boolean hideGroundItemsHidden()
		{
			return hideGroundItemsHidden;
		}

		@Override
		public boolean resetLocationSkips()
		{
			return resetLocationSkips;
		}

		@Override
		public boolean taskAdvisor()
		{
			return taskAdvisor;
		}

		@Override
		public TaskAdvisorGoal taskAdvisorGoal()
		{
			return taskAdvisorGoal;
		}

		@Override
		public boolean showWhatToBring()
		{
			return showWhatToBring;
		}

		@Override
		public boolean showTrends()
		{
			return showTrends;
		}

		@Override
		public boolean showOverallTotals()
		{
			return showOverallTotals;
		}

		@Override
		public boolean showLuckMeter()
		{
			return showLuckMeter;
		}

		@Override
		public boolean showSkippedBlocked()
		{
			return showSkippedBlocked;
		}

		@Override
		public int notableDropThreshold()
		{
			return notableDropThreshold;
		}

		@Override
		public boolean vorkathsHeadUpgrade()
		{
			return vorkathsHeadUpgrade;
		}

		@Override
		public LootPriceMode lootPriceMode()
		{
			return lootPriceMode;
		}

		@Override
		public boolean confirmIncomplete()
		{
			return confirmIncomplete;
		}

		@Override
		public boolean taskCompleteNotification()
		{
			return taskCompleteNotification;
		}

		@Override
		public boolean assignmentPreview()
		{
			return assignmentPreview;
		}

		@Override
		public boolean showTaskOverlay()
		{
			return showTaskOverlay;
		}

		@Override
		public boolean overlayTask()
		{
			return overlayTask;
		}

		@Override
		public boolean overlayKills()
		{
			return overlayKills;
		}

		@Override
		public boolean overlayTimeLeft()
		{
			return overlayTimeLeft;
		}

		@Override
		public boolean overlayProfit()
		{
			return overlayProfit;
		}

		@Override
		public String ignoredLoot()
		{
			return ignoredLoot;
		}

		@Override
		public BonecrusherMode bonecrusherHandling()
		{
			return bonecrusherHandling;
		}
	}

	/** Definition behind an item composition fake. */
	private static final class ItemDef
	{
		final String name;
		final String[] actions;
		final boolean tradeable;
		final int haPrice;

		ItemDef(String name, String[] actions, boolean tradeable, int haPrice)
		{
			this.name = name;
			this.actions = actions;
			this.tradeable = tradeable;
			this.haPrice = haPrice;
		}
	}

	final Client client = mock(Client.class, withSettings().stubOnly().defaultAnswer(this::answerClient));
	final ItemManager itemManager = mock(ItemManager.class,
		withSettings().stubOnly().defaultAnswer(this::answerItemManager));
	final SkillIconManager skillIconManager = mock(SkillIconManager.class,
		withSettings().stubOnly().defaultAnswer(this::answerSkillIconManager));
	final ClientToolbar clientToolbar = mock(ClientToolbar.class);
	final ConfigManager configManager = mock(ConfigManager.class);
	final ChatMessageManager chatMessageManager = mock(ChatMessageManager.class);
	final Notifier notifier = mock(Notifier.class);
	final OverlayManager overlayManager = mock(OverlayManager.class);
	final HarnessConfig config = new HarnessConfig();
	final DeterministicExecutor executor = new DeterministicExecutor();
	final Gson gson = new Gson();
	final EventBus eventBus = new EventBus(e -> recordError("event subscriber", e));
	final HarnessClientThread clientThread = new HarnessClientThread();
	final SlayerLedgerPlugin plugin = new SlayerLedgerPlugin();

	private final TemporaryFolder folder = new TemporaryFolder();
	private final List<Throwable> errors = new CopyOnWriteArrayList<>();
	private final AtomicInteger pluginCalls = new AtomicInteger();
	private volatile Thread clientThreadOwner;
	private Thread.UncaughtExceptionHandler previousUncaughtHandler;
	private File ledgerDirectory;
	private boolean running;
	private LootManager lootManager;

	// Fake client state; written by the test thread, read by the plugin on the client thread.
	private volatile GameState gameState = GameState.LOGIN_SCREEN;
	private volatile int tickCount = 1;
	private volatile long accountHash = SessionTracker.NO_ACCOUNT;
	private final Map<Integer, Integer> varps = new HashMap<>();
	private final Map<Integer, Integer> varbits = new HashMap<>();
	/** XP of each skill for {@code client.getSkillExperience}; 0 when not set (as the client reads before the stats sync). */
	private final Map<Skill, Integer> skillXp = new EnumMap<>(Skill.class);
	private final Map<Integer, Item[]> containers = new HashMap<>();
	private final Map<Integer, ItemContainer> containerFakes = new HashMap<>();
	private final Set<Integer> openInterfaces = new HashSet<>();
	private final Map<Integer, String> taskNames = new HashMap<>();
	private final Map<Integer, String> areaNames = new HashMap<>();
	private final Map<Integer, String> bossNames = new HashMap<>();
	// Local player animation state, see setAnimation and setSpotAnims.
	private volatile int animation = -1;
	private volatile int animationFrame;
	private final Set<Integer> spotAnims = new HashSet<>();
	private final Player localPlayer = mock(Player.class, withSettings().stubOnly().defaultAnswer(
		this::answerPlayer));
	/**
	 * The local player's tile ({@link #setPlayerRegion}); {@link #NO_LOCATION} for none, when
	 * {@code Player.getLocalLocation()} and the world views read {@code null}.
	 */
	private volatile int playerWorldX = NO_LOCATION;
	private volatile int playerWorldY = NO_LOCATION;
	/** The top-level world view (not an instance) around the local player's tile, while it has one. */
	private final WorldView worldView = mock(WorldView.class, withSettings().stubOnly().defaultAnswer(
		this::answerWorldView));
	/** Id of the Sailing boat's world view ({@link #setPlayerOnBoat}). */
	private static final int BOAT_WORLD_VIEW = 1;
	/** A Sailing boat's world view, not top-level; the local player's tile is in it while {@link #playerOnBoat}. */
	private final WorldView boatView = mock(WorldView.class, withSettings().stubOnly().defaultAnswer(
		this::answerWorldView));
	private volatile boolean playerOnBoat;
	/** Whether the RSProfile config has no profile (reads {@code null}) while no account is logged in. */
	private volatile boolean noRsProfileWhileLoggedOut;
	/** Text of the item dialog (InterfaceID.Objectbox.TEXT), or {@code null} while none is shown. */
	private volatile String objectboxText;
	/** Item id of the selected widget ("Use" on an item), or -1 for none. */
	private volatile int selectedItemId = -1;
	/** The RSProfile config of the logged-in profile, as the config manager mock stores it (group.key to value). */
	private final Map<String, String> rsProfileConfig = new HashMap<>();
	/**
	 * The other plugins' (profile-wide) config the plugin reads, as the config manager mock returns it (group.key to
	 * value; a missing key reads {@code null}): Ground Items' lists and on/off state. Ground Items' hidden list
	 * starts empty, as if the player had cleared it, so its built-in default hides nothing in tests that don't ask for
	 * it; see {@link #setGroundItemsConfig}.
	 */
	private final Map<String, String> otherConfig = new ConcurrentHashMap<>();
	private final Widget openWidget = mock(Widget.class, withSettings().stubOnly().defaultAnswer(
		invocation -> answerChecked("Widget", invocation)));
	private final Map<Integer, NPCComposition> npcDefinitions = new HashMap<>();
	private final Map<Integer, ItemDef> itemDefs = new HashMap<>();
	private final Map<Integer, Long> gePrices = new HashMap<>();
	/** {@code ItemManager.getItemComposition} calls by item id, see {@link #itemCompositionLookups}. */
	private final Map<Integer, Integer> itemCompositionLookups = new ConcurrentHashMap<>();
	/** {@code ItemManager.getItemPrice} calls by item id, see {@link #itemPriceLookups}. */
	private final Map<Integer, Integer> itemPriceLookups = new ConcurrentHashMap<>();
	/**
	 * Images handed out by the {@code ItemManager.getImage} and {@code SkillIconManager.getSkillImage} fakes, with what
	 * they show; see {@link #iconText}.
	 */
	private final Map<Image, String> itemImages = Collections.synchronizedMap(new IdentityHashMap<>());
	/** Calls to the {@code ItemManager.getImage} fake so far; see {@link #itemImageRequests}. */
	private final AtomicInteger itemImageRequests = new AtomicInteger();
	/** Items defined by {@link #defineStackableWeapon}. */
	private final Set<Integer> stackableWeapons = new HashSet<>();
	/** Noted item id to its unnoted item id, see {@link #defineNotedItem}. */
	private final Map<Integer, Integer> notedItems = new HashMap<>();
	private final Map<Integer, ItemComposition> compositions = new HashMap<>();
	/** How the Keep/Discard dialog is answered ({@link #scriptKeepDiscardPrompt}); read on the EDT. */
	private volatile PromptAnswer promptAnswer = PromptAnswer.KEEP;
	/** Keep/Discard dialogs shown so far. */
	private final AtomicInteger promptsShown = new AtomicInteger();
	/** The Keep/Discard dialog left open ({@link PromptAnswer#LEAVE_OPEN}), or {@code null} while none is. */
	private volatile OpenPrompt openPrompt;

	// ---- Rule lifecycle ----

	@Override
	public Statement apply(Statement base, Description description)
	{
		return new Statement()
		{
			@Override
			public void evaluate() throws Throwable
			{
				before();
				Throwable failure = null;
				try
				{
					base.evaluate();
				}
				catch (Throwable t)
				{
					failure = t;
				}
				try
				{
					after();
				}
				catch (Throwable t)
				{
					if (failure == null)
					{
						failure = t;
					}
					else
					{
						failure.addSuppressed(t);
					}
				}
				if (failure != null)
				{
					throw failure;
				}
			}
		};
	}

	private void before() throws IOException
	{
		clientThreadOwner = Thread.currentThread();
		// Catches what the EDT (or any other thread) would only print. The default handler, not a per-thread one, so
		// an EDT the toolkit replaces (it shuts an idle EDT down and starts a new one) is covered too.
		previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((thread, e) -> recordError("uncaught on " + thread.getName(), e));
		try
		{
			setUp();
		}
		catch (IOException | RuntimeException | Error e)
		{
			// after() does not run when before() fails, so put the previous handler back here.
			Thread.setDefaultUncaughtExceptionHandler(previousUncaughtHandler);
			throw e;
		}
	}

	private void setUp() throws IOException
	{
		folder.create();
		ledgerDirectory = folder.newFolder("slayer-ledger");
		// Default "no ammo" value of the quiver varp.
		varps.put(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO, -1);

		Injector parent = Guice.createInjector(binder ->
		{
			binder.bind(Client.class).toProvider(Providers.of(client));
			binder.bind(ClientThread.class).toInstance(clientThread);
			binder.bind(ClientToolbar.class).toProvider(Providers.of(clientToolbar));
			binder.bind(OverlayManager.class).toProvider(Providers.of(overlayManager));
			binder.bind(ConfigManager.class).toProvider(Providers.of(configManager));
			binder.bind(ChatMessageManager.class).toProvider(Providers.of(chatMessageManager));
			binder.bind(ItemManager.class).toProvider(Providers.of(itemManager));
			binder.bind(SkillIconManager.class).toProvider(Providers.of(skillIconManager));
			binder.bind(Notifier.class).toProvider(Providers.of(notifier));
			binder.bind(EventBus.class).toInstance(eventBus);
			binder.bind(ScheduledExecutorService.class).toInstance(executor);
			binder.bind(Gson.class).toInstance(gson);
			binder.bind(NpcUtil.class).toInstance(mock(NpcUtil.class));
		});
		// The real LootManager, created by Guice as in the client (it registers itself with the event bus).
		lootManager = parent.getInstance(LootManager.class);
		when(configManager.getConfig(SlayerLedgerConfig.class)).thenReturn(config);
		// Unsetting "Ask again about skipped places" (the plugin unticks it) reads as its default afterwards.
		doAnswer(invocation ->
		{
			config.resetLocationSkips = false;
			return null;
		}).when(configManager).unsetConfiguration(SlayerLedgerConfig.GROUP, SlayerLedgerConfig.RESET_LOCATION_SKIPS);
		otherConfig.put(GroundItemsHidden.GROUP + "." + GroundItemsHidden.HIDDEN_ITEMS_KEY, "");
		when(configManager.getConfiguration(anyString(), anyString())).thenAnswer(invocation ->
			otherConfig.get(invocation.getArgument(0) + "." + invocation.getArgument(1)));
		// The RSProfile config of one profile, in memory: set stores the value's string form, as ConfigManager does.
		// With noRsProfileWhileLoggedOut, as ConfigManager after logout: no profile, so every value reads null.
		when(configManager.getRSProfileConfiguration(anyString(), anyString())).thenAnswer(invocation ->
			noRsProfileWhileLoggedOut && accountHash == SessionTracker.NO_ACCOUNT ? null
				: rsProfileConfig.get(invocation.getArgument(0) + "." + invocation.getArgument(1)));
		doAnswer(invocation ->
		{
			rsProfileConfig.put(invocation.getArgument(0) + "." + invocation.getArgument(1),
				String.valueOf(invocation.<Object>getArgument(2)));
			return null;
		}).when(configManager).setRSProfileConfiguration(anyString(), anyString(), any());
		doAnswer(invocation ->
		{
			rsProfileConfig.remove(invocation.getArgument(0) + "." + invocation.getArgument(1));
			return null;
		}).when(configManager).unsetRSProfileConfiguration(anyString(), anyString());
		// The keys of the one profile, as ConfigManager lists them (whole keys after the group, "prefix..."); none, as
		// with no profile, while the RSProfile config reads null.
		when(configManager.getRSProfileKey()).thenAnswer(invocation ->
			noRsProfileWhileLoggedOut && accountHash == SessionTracker.NO_ACCOUNT ? null : RS_PROFILE_KEY);
		when(configManager.getRSProfileConfigurationKeys(anyString(), any(), anyString())).thenAnswer(invocation ->
		{
			final List<String> keys = new ArrayList<>();
			if (!RS_PROFILE_KEY.equals(invocation.getArgument(1)))
			{
				return keys;
			}
			final String prefix = invocation.getArgument(0) + "." + invocation.getArgument(2);
			for (String key : new TreeSet<>(rsProfileConfig.keySet()))
			{
				if (key.startsWith(prefix))
				{
					keys.add(key.substring(((String) invocation.getArgument(0)).length() + 1));
				}
			}
			return keys;
		});
		// As PluginModuleFactory.PluginModule: bind the instance and install the plugin as a module.
		parent.createChildInjector(binder ->
		{
			binder.bind(SlayerLedgerPlugin.class).toInstance(plugin);
			binder.install(plugin);
		});
		plugin.setLedgerDirectory(Filepath.Unchecked.getRooted(ledgerDirectory.toPath()));
		plugin.setKeepIncompleteDialog(this::showKeepDiscardPrompt);
	}

	private void after()
	{
		Throwable cleanupFailure = null;
		try
		{
			if (running)
			{
				shutDown();
			}
			drain();
		}
		catch (RuntimeException | Error e)
		{
			// Still report the errors collected so far; they often explain the failure.
			cleanupFailure = e;
		}
		finally
		{
			try
			{
				// Never leave the EDT in a dialog's nested loop. The plugin has stopped, so the late answer saves nothing.
				closeOpenPrompt(false);
			}
			catch (RuntimeException | Error e)
			{
				if (cleanupFailure == null)
				{
					cleanupFailure = e;
				}
			}
			if (lootManager != null)
			{
				eventBus.unregister(lootManager);
			}
			folder.delete();
			Thread.setDefaultUncaughtExceptionHandler(previousUncaughtHandler);
		}
		final List<Throwable> all = new ArrayList<>(errors);
		if (!all.isEmpty())
		{
			AssertionError error = new AssertionError(all.size() + " error(s) out of sight of the test; first: "
				+ all.get(0), all.get(0));
			for (Throwable t : all.subList(1, all.size()))
			{
				error.addSuppressed(t);
			}
			if (cleanupFailure != null)
			{
				error.addSuppressed(cleanupFailure);
			}
			throw error;
		}
		if (cleanupFailure instanceof RuntimeException)
		{
			throw (RuntimeException) cleanupFailure;
		}
		if (cleanupFailure != null)
		{
			throw (Error) cleanupFailure;
		}
	}

	// ---- Plugin lifecycle (as PluginManager) ----

	/**
	 * Starts the plugin like {@code PluginManager.startPlugin}: on the EDT, {@code startUp()} then
	 * {@code eventBus.register(plugin)}. If either throws, the plugin is stopped again (unregister, {@code shutDown()})
	 * and the original exception is rethrown; an exception from that {@code shutDown()} is attached as suppressed.
	 */
	void startUp()
	{
		if (running)
		{
			throw new IllegalStateException("plugin already running");
		}
		try
		{
			onEdt(() ->
			{
				plugin.startUp();
				eventBus.register(plugin);
			});
		}
		catch (RuntimeException | Error e)
		{
			try
			{
				onEdt(this::stopOnEdt);
			}
			catch (RuntimeException | Error stopFailure)
			{
				e.addSuppressed(stopFailure);
			}
			drain();
			throw e;
		}
		running = true;
		drain();
	}

	/**
	 * Starts the plugin like {@link #startUp()} but leaves the work it queued (its client-thread block among it) queued,
	 * so a test can post client-thread events in between, as a client frame may run them before the queue is drained.
	 * Call {@link #drain()} afterwards.
	 */
	void startUpWithoutDrain()
	{
		if (running)
		{
			throw new IllegalStateException("plugin already running");
		}
		onEdt(() ->
		{
			plugin.startUp();
			eventBus.register(plugin);
		});
		running = true;
	}

	/** Stops the plugin like {@code PluginManager.stopPlugin}: on the EDT, unregister then {@code shutDown()}. */
	void shutDown()
	{
		try
		{
			onEdt(this::stopOnEdt);
		}
		finally
		{
			running = false;
		}
		drain();
	}

	private void stopOnEdt()
	{
		eventBus.unregister(plugin);
		plugin.shutDown();
	}

	boolean isRunning()
	{
		return running;
	}

	// ---- Events and ticks (client thread) ----

	/** Posts an event on the client thread, then drains (like the client's next frame). */
	void post(Object event)
	{
		postWithoutDrain(event);
		drain();
	}

	/** Posts an event on the client thread without draining any queued work. */
	void postWithoutDrain(Object event)
	{
		checkOnTestThread();
		eventBus.post(event);
	}

	/**
	 * One game tick as {@code Hooks.tick()} runs it: {@link GameTick} is posted with the current tick count
	 * ({@link LootManager} posts the loot reported by {@link #lootOnNextTick} from its handler), then the tick count
	 * goes up, then queued work is drained.
	 */
	void tick()
	{
		postWithoutDrain(new GameTick());
		tickCount++;
		drain();
	}

	void ticks(int n)
	{
		for (int i = 0; i < n; i++)
		{
			tick();
		}
	}

	/** The tick count {@code client.getTickCount()} returns now (the one the next {@link GameTick} is posted with). */
	int tickCount()
	{
		return tickCount;
	}

	/** Sets the game state and posts {@link GameStateChanged}. */
	void setGameState(GameState state)
	{
		gameState = state;
		GameStateChanged event = new GameStateChanged();
		event.setGameState(state);
		post(event);
	}

	/** Sets the game state without posting an event (for example, logged in before the plugin starts). */
	void setGameStateSilently(GameState state)
	{
		gameState = state;
	}

	void setAccountHash(long hash)
	{
		accountHash = hash;
	}

	/**
	 * Logs in to {@code account} with the account hash set: LOGGING_IN, LOADING (the first scene load), then
	 * LOGGED_IN, each posted as {@link GameStateChanged}. Does not tick.
	 */
	void login(long account)
	{
		accountHash = account;
		setGameState(GameState.LOGGING_IN);
		setGameState(GameState.LOADING);
		setGameState(GameState.LOGGED_IN);
	}

	/** {@link #login} then {@link #SETTLE_TICKS} ticks, after which the plugin has read the current task. */
	void loginAndSettle(long account)
	{
		login(account);
		ticks(SETTLE_TICKS);
	}

	/** Logs out to the login screen; {@code client.getAccountHash()} reads -1 there. */
	void logout()
	{
		accountHash = SessionTracker.NO_ACCOUNT;
		setGameState(GameState.LOGIN_SCREEN);
	}

	/**
	 * Hops worlds on the same account: HOPPING, LOADING (the new world's scene), then LOGGED_IN, each posted as
	 * {@link GameStateChanged}. Does not tick.
	 */
	void hop()
	{
		setGameState(GameState.HOPPING);
		setGameState(GameState.LOADING);
		setGameState(GameState.LOGGED_IN);
	}

	/**
	 * Client shutdown as {@code ClientUI.shutdownClient}: {@link ClientShutdown} is posted on the EDT, then (while the
	 * client thread and executor keep running, here: one drain) its consumers are awaited.
	 *
	 * @return the futures the plugin asked the client to wait for, after waiting
	 */
	List<Future<?>> clientShutdown()
	{
		final ClientShutdown event = new ClientShutdown();
		onEdt(() -> eventBus.post(event));
		drain();
		final List<Future<?>> futures = new ArrayList<>(event.getTasks());
		event.waitForAllConsumers(Duration.ofSeconds(1));
		return futures;
	}

	// ---- Slayer task varps (VarPlayerID / VarbitID ids read by the plugin) ----

	/**
	 * Sets the assigned task: SLAYER_TARGET, SLAYER_COUNT and SLAYER_COUNT_ORIGINAL, and a SlayerTask DB row naming it.
	 */
	void setTask(int taskId, String name, int remaining, int assigned)
	{
		varps.put(VarPlayerID.SLAYER_TARGET, taskId);
		varps.put(VarPlayerID.SLAYER_COUNT, remaining);
		varps.put(VarPlayerID.SLAYER_COUNT_ORIGINAL, assigned);
		taskNames.put(taskId, name);
	}

	/**
	 * Sets an assigned boss task: SLAYER_TARGET {@value SessionTracker#BOSS_TASK_TARGET}, SLAYER_TARGET_BOSSID,
	 * SLAYER_COUNT and SLAYER_COUNT_ORIGINAL, with a SlayerTaskSublist DB row leading to a SlayerTask row naming it, as
	 * the game's tables do.
	 */
	void setBossTask(int bossId, String name, int remaining, int assigned)
	{
		varps.put(VarPlayerID.SLAYER_TARGET, SessionTracker.BOSS_TASK_TARGET);
		varbits.put(VarbitID.SLAYER_TARGET_BOSSID, bossId);
		varps.put(VarPlayerID.SLAYER_COUNT, remaining);
		varps.put(VarPlayerID.SLAYER_COUNT_ORIGINAL, assigned);
		bossNames.put(bossId, name);
	}

	/**
	 * Puts the local player in map region {@code region} (on its centre tile, outside any instance), as
	 * {@code WorldPoint.fromLocalInstance(client, player.getLocalLocation()).getRegionID()} reads it.
	 */
	void setPlayerRegion(int region)
	{
		playerWorldY = ((region & 0xFF) << 6) + 32;
		playerWorldX = ((region >> 8) << 6) + 32;
	}

	/**
	 * Puts the local player's tile on a Sailing boat ({@code true}): {@code Player.getLocalLocation()} is in the boat's
	 * world view, which is not top-level; {@code false} puts it back in the top-level world view.
	 */
	void setPlayerOnBoat(boolean onBoat)
	{
		playerOnBoat = onBoat;
	}

	/**
	 * As ConfigManager after logout: the RSProfile config has no profile while no account is logged in (the account
	 * hash reads -1), so every value reads {@code null} until the next login.
	 */
	void setNoRsProfileWhileLoggedOut()
	{
		noRsProfileWhileLoggedOut = true;
	}

	/** The local player has no location (no scene), as before {@link #setPlayerRegion} is called. */
	void clearPlayerLocation()
	{
		playerWorldX = NO_LOCATION;
		playerWorldY = NO_LOCATION;
	}

	/** Sets SLAYER_COUNT (kills left). */
	void setRemaining(int remaining)
	{
		varps.put(VarPlayerID.SLAYER_COUNT, remaining);
	}

	/** Completes the task as the game does: SLAYER_COUNT drops to 0 and SLAYER_TASKS_COMPLETED goes up by one. */
	void completeTask()
	{
		varps.put(VarPlayerID.SLAYER_COUNT, 0);
		varbits.merge(VarbitID.SLAYER_TASKS_COMPLETED, 1, Integer::sum);
	}

	/**
	 * Sets who assigned the task: SLAYER_MASTER and SLAYER_AREA, with a SlayerArea DB row naming the area when
	 * {@code areaName} is not {@code null}.
	 */
	void setAssignment(int master, int areaId, String areaName)
	{
		varbits.put(VarbitID.SLAYER_MASTER, master);
		varps.put(VarPlayerID.SLAYER_AREA, areaId);
		if (areaName != null)
		{
			areaNames.put(areaId, areaName);
		}
	}

	/** Sets SLAYER_POINTS, the player's Slayer reward points. */
	void setSlayerPoints(int points)
	{
		varbits.put(VarbitID.SLAYER_POINTS, points);
	}

	/**
	 * Completes the task with its reward: SLAYER_COUNT drops to 0, SLAYER_TASKS_COMPLETED goes up by one and
	 * SLAYER_POINTS by {@code points}, all on the same tick.
	 */
	void completeTask(int points)
	{
		completeTask();
		varbits.merge(VarbitID.SLAYER_POINTS, points, Integer::sum);
	}

	/** Sets the XP {@code client.getSkillExperience(skill)} returns. */
	void setSkillXp(Skill skill, int xp)
	{
		skillXp.put(skill, xp);
	}

	/** Adds {@code xp} to the XP {@code client.getSkillExperience(skill)} returns, as an XP drop does. */
	void gainXp(Skill skill, int xp)
	{
		skillXp.merge(skill, xp, Integer::sum);
	}

	/** Sets every skill's XP to 0, as the client reads before a login's stats arrive. */
	void clearSkillXp()
	{
		skillXp.clear();
	}

	void setVarp(int varp, int value)
	{
		varps.put(varp, value);
	}

	void setVarbit(int varbit, int value)
	{
		varbits.put(varbit, value);
	}

	// ---- Items ----

	/** Defines an item for {@code ItemManager.getItemComposition} and {@code getItemPrice}. */
	void defineItem(int id, String name, long gePrice, int haPrice, String... inventoryActions)
	{
		itemDefs.put(id, new ItemDef(name, inventoryActions, true, haPrice));
		gePrices.put(id, gePrice);
	}

	/**
	 * Defines a stackable weapon such as darts, which the plugin counts as ammo: a {@link #defineItem} item that is
	 * stackable and whose item stats are loaded, with the weapon slot.
	 */
	void defineStackableWeapon(int id, String name, long gePrice)
	{
		defineItem(id, name, gePrice, 0, null, "Wield", null, null, "Drop");
		stackableWeapons.add(id);
	}

	/**
	 * Defines a noted item: its composition is noted ({@code getNote()} is not -1) and links to {@code unnotedId}, whose
	 * name it shares, as in the game; {@code ItemManager.canonicalize} returns the unnoted id.
	 */
	void defineNotedItem(int notedId, int unnotedId)
	{
		notedItems.put(notedId, unnotedId);
	}

	/** Sets the "Ignored loot" setting and posts {@link ConfigChanged} for it, as the config panel does. */
	void setIgnoredLoot(String value)
	{
		config.ignoredLoot = value;
		ConfigChanged event = new ConfigChanged();
		event.setGroup(SlayerLedgerConfig.GROUP);
		event.setKey(SlayerLedgerConfig.IGNORED_LOOT);
		event.setNewValue(value);
		post(event);
	}

	/** Sets the "Loot price" setting and posts {@link ConfigChanged} for it, as the config panel does. */
	void setLootPriceMode(LootPriceMode mode)
	{
		config.lootPriceMode = mode;
		ConfigChanged event = new ConfigChanged();
		event.setGroup(SlayerLedgerConfig.GROUP);
		event.setKey(SlayerLedgerConfig.LOOT_PRICE_MODE);
		event.setNewValue(mode.name());
		post(event);
	}

	/** Sets the "Notable drop value" setting and posts {@link ConfigChanged} for it, as the config panel does. */
	void setNotableDropThreshold(int gp)
	{
		config.notableDropThreshold = gp;
		ConfigChanged event = new ConfigChanged();
		event.setGroup(SlayerLedgerConfig.GROUP);
		event.setKey(SlayerLedgerConfig.NOTABLE_DROP_THRESHOLD);
		event.setNewValue(Integer.toString(gp));
		post(event);
	}

	/**
	 * Sets a "Panel sections" setting ({@code key} is one of the {@code SlayerLedgerConfig.SHOW_...} keys) and posts
	 * {@link ConfigChanged} for it, as the config panel does.
	 */
	void setPanelSection(String key, boolean shown)
	{
		switch (key)
		{
			case SlayerLedgerConfig.SHOW_WHAT_TO_BRING:
				config.showWhatToBring = shown;
				break;
			case SlayerLedgerConfig.SHOW_TRENDS:
				config.showTrends = shown;
				break;
			case SlayerLedgerConfig.SHOW_OVERALL_TOTALS:
				config.showOverallTotals = shown;
				break;
			case SlayerLedgerConfig.SHOW_LUCK_METER:
				config.showLuckMeter = shown;
				break;
			case SlayerLedgerConfig.SHOW_SKIPPED_BLOCKED:
				config.showSkippedBlocked = shown;
				break;
			default:
				throw new IllegalArgumentException(key);
		}
		ConfigChanged event = new ConfigChanged();
		event.setGroup(SlayerLedgerConfig.GROUP);
		event.setKey(key);
		event.setNewValue(Boolean.toString(shown));
		post(event);
	}

	/** Sets the "Task advisor" setting and posts {@link ConfigChanged} for it, as the config panel does. */
	void setTaskAdvisor(boolean enabled)
	{
		config.taskAdvisor = enabled;
		ConfigChanged event = new ConfigChanged();
		event.setGroup(SlayerLedgerConfig.GROUP);
		event.setKey(SlayerLedgerConfig.TASK_ADVISOR);
		event.setNewValue(Boolean.toString(enabled));
		post(event);
	}

	/**
	 * Ticks "Ask again about skipped places" and posts {@link ConfigChanged} for it, as the config panel does; while the
	 * plugin is stopped only the value is set, as the event then reaches no plugin.
	 */
	void tickResetLocationSkips()
	{
		config.resetLocationSkips = true;
		if (running)
		{
			ConfigChanged event = new ConfigChanged();
			event.setGroup(SlayerLedgerConfig.GROUP);
			event.setKey(SlayerLedgerConfig.RESET_LOCATION_SKIPS);
			event.setNewValue("true");
			post(event);
		}
	}

	/** Whether "Ask again about skipped places" is ticked (the plugin unticks it once done). */
	boolean isResetLocationSkipsTicked()
	{
		return config.resetLocationSkips;
	}

	/**
	 * Sets one of Ground Items' settings ({@code group} is {@link GroundItemsHidden#GROUP}) or its on/off state
	 * ({@link GroundItemsHidden#PLUGIN_ENABLED_GROUP}), {@code null} to unset it (never edited), and posts
	 * {@link ConfigChanged} for it, as ConfigManager does when the player edits it.
	 */
	void setGroundItemsConfig(String group, String key, String value)
	{
		if (value == null)
		{
			otherConfig.remove(group + "." + key);
		}
		else
		{
			otherConfig.put(group + "." + key, value);
		}
		ConfigChanged event = new ConfigChanged();
		event.setGroup(group);
		event.setKey(key);
		event.setNewValue(value);
		post(event);
	}

	/** Sets Ground Items' hidden list and posts {@link ConfigChanged} for it; {@code null} is never edited. */
	void setGroundItemsHidden(String value)
	{
		setGroundItemsConfig(GroundItemsHidden.GROUP, GroundItemsHidden.HIDDEN_ITEMS_KEY, value);
	}

	/** Sets "Hide Ground Items' hidden items" and posts {@link ConfigChanged} for it, as the config panel does. */
	void setHideGroundItemsHidden(boolean on)
	{
		config.hideGroundItemsHidden = on;
		ConfigChanged event = new ConfigChanged();
		event.setGroup(SlayerLedgerConfig.GROUP);
		event.setKey(SlayerLedgerConfig.HIDE_GROUND_ITEMS_HIDDEN);
		event.setNewValue(String.valueOf(on));
		post(event);
	}

	/** Sets the "Task advisor goal" setting and posts {@link ConfigChanged} for it, as the config panel does. */
	void setTaskAdvisorGoal(TaskAdvisorGoal goal)
	{
		config.taskAdvisorGoal = goal;
		ConfigChanged event = new ConfigChanged();
		event.setGroup(SlayerLedgerConfig.GROUP);
		event.setKey(SlayerLedgerConfig.TASK_ADVISOR_GOAL);
		event.setNewValue(goal.name());
		post(event);
	}

	/** How many times {@code ItemManager.getItemComposition} was called for {@code id}, for example to name it. */
	int itemCompositionLookups(int id)
	{
		return itemCompositionLookups.getOrDefault(id, 0);
	}

	/** How many times {@code ItemManager.getItemPrice} was called for {@code id}, for example to price a drop. */
	int itemPriceLookups(int id)
	{
		return itemPriceLookups.getOrDefault(id, 0);
	}

	/** Changes the GE price {@code ItemManager.getItemPrice} returns for a defined item (0 makes it unavailable). */
	void setGePrice(int id, long gePrice)
	{
		gePrices.put(id, gePrice);
	}

	static Item item(int id, int quantity)
	{
		return new Item(id, quantity);
	}

	/**
	 * Sets the inventory ({@link InventoryID#INV}); no items means an empty but loaded inventory. Posts
	 * {@link ItemContainerChanged}, as the client does when a container changes (without draining).
	 */
	void setInventory(Item... items)
	{
		containers.put(InventoryID.INV, items.clone());
		postWithoutDrain(new ItemContainerChanged(InventoryID.INV, containerFake(InventoryID.INV)));
	}

	/** Makes the inventory unloaded ({@code getItemContainer} returns {@code null}). */
	void clearInventory()
	{
		containers.remove(InventoryID.INV);
	}

	/** Puts an item in one equipment slot ({@link InventoryID#WORN}); posts {@link ItemContainerChanged}, as setInventory. */
	void setWorn(EquipmentInventorySlot slot, int id, int quantity)
	{
		Item[] worn = containers.computeIfAbsent(InventoryID.WORN, k -> new Item[EquipmentInventorySlot.values().length]);
		worn[slot.getSlotIdx()] = new Item(id, quantity);
		postWithoutDrain(new ItemContainerChanged(InventoryID.WORN, containerFake(InventoryID.WORN)));
	}

	/**
	 * Reports NPC loot the way the game does, for {@link LootManager} to post as {@link ServerNpcLoot} during the next
	 * {@link #tick()}: the game runs its loot tracker script, and the client posts {@link ScriptPreFired} for
	 * {@code LOOTTRACKER_ADD_LOOT} with the NPC ({@link #LOOT_NPC_ID}), an event id, the item and the quantity. Loot
	 * reported before the same tick shares an event id, so it arrives as one {@link ServerNpcLoot}.
	 */
	void lootOnNextTick(int id, int quantity)
	{
		final ScriptEvent scriptEvent = mock(ScriptEvent.class, withSettings().stubOnly());
		// Argument 0 is the script; LootManager reads the NPC, event, item and quantity from 1 to 4.
		when(scriptEvent.getArguments()).thenReturn(new Object[]{
			ScriptID.LOOTTRACKER_ADD_LOOT, LOOT_NPC_ID, tickCount, id, quantity});
		final ScriptPreFired event = new ScriptPreFired(ScriptID.LOOTTRACKER_ADD_LOOT);
		event.setScriptEvent(scriptEvent);
		postWithoutDrain(event);
	}

	/** Posts {@link ServerNpcLoot} now (between ticks), then drains. */
	void postLoot(int id, int quantity)
	{
		post(new ServerNpcLoot(mock(NPCComposition.class), Collections.singletonList(new ItemStack(id, quantity))));
	}

	// ---- Interfaces, menu, actors, chat ----

	/** Opens an interface: its root widget is loaded from now on and {@link WidgetLoaded} is posted. */
	void openInterface(int groupId)
	{
		openInterfaces.add(groupId);
		WidgetLoaded event = new WidgetLoaded();
		event.setGroupId(groupId);
		post(event);
	}

	/** Closes an interface: its root widget is gone and {@link WidgetClosed} is posted. */
	void closeInterface(int groupId)
	{
		openInterfaces.remove(groupId);
		post(new WidgetClosed(groupId, 0, true));
	}

	/** Posts {@link MenuOptionClicked} for {@code option} on an inventory item. */
	void clickMenu(String option, int itemId)
	{
		MenuEntry entry = mock(MenuEntry.class, withSettings().stubOnly());
		when(entry.getOption()).thenReturn(option);
		when(entry.getItemId()).thenReturn(itemId);
		post(new MenuOptionClicked(entry));
	}

	/**
	 * Posts {@link MenuOptionClicked} for "Use" of the selected item {@code selectedId} on the inventory item
	 * {@code targetId}: a {@code WIDGET_TARGET_ON_WIDGET} entry, with {@code client.getSelectedWidget()} holding the
	 * selected item while it is posted.
	 */
	void clickUseOn(int selectedId, int targetId)
	{
		MenuEntry entry = mock(MenuEntry.class, withSettings().stubOnly());
		when(entry.getOption()).thenReturn("Use");
		when(entry.getType()).thenReturn(MenuAction.WIDGET_TARGET_ON_WIDGET);
		when(entry.getItemId()).thenReturn(targetId);
		selectedItemId = selectedId;
		try
		{
			post(new MenuOptionClicked(entry));
		}
		finally
		{
			selectedItemId = -1;
		}
	}

	/**
	 * Shows the item dialog ({@code InterfaceID.OBJECTBOX}) with {@code text}: its text widget holds it from now on and
	 * {@link WidgetLoaded} is posted. {@code null} hides it.
	 */
	void showObjectbox(String text)
	{
		objectboxText = text;
		if (text == null)
		{
			closeInterface(InterfaceID.OBJECTBOX);
		}
		else
		{
			openInterface(InterfaceID.OBJECTBOX);
		}
	}

	/** Sets the local player's animation and its frame. */
	void setAnimation(int animationId, int frame)
	{
		animation = animationId;
		animationFrame = frame;
	}

	/** Sets the spotanims (graphics) on the local player; none clears them. */
	void setSpotAnims(int... ids)
	{
		synchronized (spotAnims)
		{
			spotAnims.clear();
			for (int id : ids)
			{
				spotAnims.add(id);
			}
		}
	}

	/** The RSProfile config value the plugin stored under {@code group.key}, or {@code null}. */
	String rsProfileConfig(String group, String key)
	{
		return rsProfileConfig.get(group + "." + key);
	}

	/**
	 * Reads an RSProfile config value through the config manager, as the plugin does (see
	 * {@link #setNoRsProfileWhileLoggedOut}).
	 */
	String readRsProfileConfiguration(String group, String key)
	{
		return configManager.getRSProfileConfiguration(group, key);
	}

	/** Sets an RSProfile config value, as if saved in an earlier run ({@code null} unsets it). */
	void setRsProfileConfig(String group, String key, String value)
	{
		if (value == null)
		{
			rsProfileConfig.remove(group + "." + key);
		}
		else
		{
			rsProfileConfig.put(group + "." + key, value);
		}
	}

	/** Posts {@link ActorDeath} for the local player. */
	void localPlayerDies()
	{
		post(new ActorDeath(localPlayer));
	}

	/** Posts {@link ActorDeath} for some other actor. */
	void otherActorDies()
	{
		post(new ActorDeath(mock(Actor.class)));
	}

	/**
	 * An NPC fake in the top-level world view with the given scene index, id and name; its getters are checked
	 * to be called on the client thread, like the other fakes.
	 */
	NPC npc(int index, int id, String name)
	{
		return npc(worldView, index, id, name);
	}

	/** An NPC fake like {@link #npc(int, int, String)}, in a Sailing boat's world view (not top-level). */
	NPC npcOnBoat(int index, int id, String name)
	{
		return npc(boatView, index, id, name);
	}

	/** An NPC fake like {@link #npc(int, int, String)} whose world view reads {@code null}. */
	NPC npcWithoutWorldView(int index, int id, String name)
	{
		return npc(null, index, id, name);
	}

	private NPC npc(WorldView view, int index, int id, String name)
	{
		return mock(NPC.class, withSettings().stubOnly().defaultAnswer(invocation ->
		{
			final String method = invocation.getMethod().getName();
			if (invocation.getMethod().getDeclaringClass() != Object.class)
			{
				checkClientThread("NPC." + method);
			}
			switch (method)
			{
				case "getIndex":
					return index;
				case "getId":
					return id;
				case "getName":
					return name;
				case "getWorldView":
					return view;
				default:
					return RETURNS_DEFAULTS.answer(invocation);
			}
		}));
	}

	/** Posts {@link HitsplatApplied} on {@code actor}: the local player's hit when {@code mine}, else someone else's. */
	void hitsplat(Actor actor, boolean mine)
	{
		final HitsplatApplied event = new HitsplatApplied();
		event.setActor(actor);
		event.setHitsplat(new Hitsplat()
		{
			@Override
			public int getHitsplatType()
			{
				return mine ? HitsplatID.DAMAGE_ME : HitsplatID.DAMAGE_OTHER;
			}

			@Override
			public int getAmount()
			{
				return 5;
			}

			@Override
			public int getDisappearsOnGameCycle()
			{
				return 0;
			}
		});
		post(event);
	}

	/** Posts {@link ActorDeath} for {@code npc}. */
	void npcDies(NPC npc)
	{
		post(new ActorDeath(npc));
	}

	/** Posts {@link NpcDespawned} for {@code npc}. */
	void npcDespawns(NPC npc)
	{
		post(new NpcDespawned(npc));
	}

	void chat(ChatMessageType type, String message)
	{
		post(new ChatMessage(null, type, "", message, null, 0));
	}

	// ---- Panel actions (EDT) ----

	/** Presses Play, on the EDT as the panel does. */
	boolean play()
	{
		final boolean[] result = new boolean[1];
		onEdt(() -> result[0] = plugin.play());
		drain();
		return result[0];
	}

	void pause()
	{
		onEdt(plugin::pause);
		drain();
	}

	void stop()
	{
		onEdt(plugin::stop);
		drain();
	}

	boolean canPlay()
	{
		final boolean[] result = new boolean[1];
		onEdt(() -> result[0] = plugin.canPlay());
		return result[0];
	}

	// ---- Keep/Discard prompt (EDT) ----

	/** How the scripted Keep/Discard dialog answers. */
	enum PromptAnswer
	{
		/** Keep: saves the session as a partial record. */
		KEEP,
		/** Discard: saves nothing. */
		DISCARD,
		/**
		 * The dialog stays open: as a modal dialog does, the EDT runs other work in a nested event loop until
		 * {@link #answerOpenPrompt} answers it.
		 */
		LEAVE_OPEN
	}

	/** A dialog left open: the nested event loop it runs on the EDT and the answer it will return. */
	private static final class OpenPrompt
	{
		final SecondaryLoop loop;
		final CountDownLatch returned = new CountDownLatch(1);
		volatile boolean keep;

		OpenPrompt(SecondaryLoop loop)
		{
			this.loop = loop;
		}
	}

	/**
	 * Turns on "Confirm before saving incomplete tasks" and answers each Keep/Discard dialog with {@code answer}
	 * instead of showing it. The dialog runs where the plugin shows the real one (on the EDT, from its invokeLater).
	 */
	void scriptKeepDiscardPrompt(PromptAnswer answer)
	{
		config.confirmIncomplete = true;
		promptAnswer = answer;
	}

	/** The number of Keep/Discard dialogs shown so far. */
	int promptsShown()
	{
		return promptsShown.get();
	}

	/** Whether a Keep/Discard dialog left open ({@link PromptAnswer#LEAVE_OPEN}) is still open. */
	boolean isPromptOpen()
	{
		return openPrompt != null;
	}

	/**
	 * Answers the open Keep/Discard dialog (Keep when {@code keep}), then drains. Fails if none is open.
	 *
	 * @return the client-thread and executor tasks the answer queued (counted after the EDT has run the plugin's code
	 * after the dialog, before draining); a save or delete queues at least one, an answer the plugin ignores none. The
	 * ledger skips a record whose id it already holds, so this, not the record count, shows a late answer saved nothing.
	 */
	int answerOpenPrompt(boolean keep)
	{
		checkOnTestThread();
		final int before = clientThread.pendingCount() + executor.pendingCount();
		if (!closeOpenPrompt(keep))
		{
			throw new AssertionError("no Keep/Discard dialog is open");
		}
		flushEdt();
		final int queued = clientThread.pendingCount() + executor.pendingCount() - before;
		drain();
		return queued;
	}

	/**
	 * Closes the open dialog, if any, with {@code keep} and waits until it has returned to the plugin. The plugin's
	 * code after the dialog runs in the same EDT event, so the next {@link #flushEdt()} waits for it too.
	 *
	 * @return whether a dialog was open
	 */
	private boolean closeOpenPrompt(boolean keep)
	{
		final OpenPrompt open = openPrompt;
		if (open == null)
		{
			return false;
		}
		open.keep = keep;
		open.loop.exit();
		try
		{
			if (!open.returned.await(10, TimeUnit.SECONDS))
			{
				throw new AssertionError("the Keep/Discard dialog did not close");
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
		return true;
	}

	/** EDT. The scripted Keep/Discard dialog (see {@link #scriptKeepDiscardPrompt}). */
	private boolean showKeepDiscardPrompt(SlayerLedgerPanel panel, SessionTracker.Summary summary)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			recordError("Keep/Discard dialog", new AssertionError("shown off the EDT, on "
				+ Thread.currentThread().getName()));
		}
		promptsShown.incrementAndGet();
		final PromptAnswer answer = promptAnswer;
		if (answer != PromptAnswer.LEAVE_OPEN)
		{
			return answer == PromptAnswer.KEEP;
		}
		if (openPrompt != null)
		{
			recordError("Keep/Discard dialog", new AssertionError("a second dialog opened while one was open"));
		}
		final OpenPrompt open = new OpenPrompt(Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop());
		openPrompt = open;
		try
		{
			// Returns once closeOpenPrompt exits it; meanwhile the EDT keeps running other events, as under a modal dialog.
			open.loop.enter();
		}
		finally
		{
			openPrompt = null;
			open.returned.countDown();
		}
		return open.keep;
	}

	// ---- Saved files ----

	File ledgerDirectory()
	{
		return ledgerDirectory;
	}

	/** The account's saved records, read with a fresh {@link LedgerStore} (empty when there is no file). */
	List<LedgerRecord> records(long account)
	{
		try
		{
			return new LedgerStore(Filepath.Unchecked.getRooted(ledgerDirectory.toPath()), gson).load(account);
		}
		catch (IOException e)
		{
			throw new AssertionError("unable to read the ledger", e);
		}
	}

	/** The account's saved assignment entries, read with a fresh {@link LedgerStore} (empty when there is no file). */
	List<AssignmentEntry> assignments(long account)
	{
		try
		{
			return new LedgerStore(Filepath.Unchecked.getRooted(ledgerDirectory.toPath()), gson).loadContents(account)
				.getAssignments();
		}
		catch (IOException e)
		{
			throw new AssertionError("unable to read the ledger", e);
		}
	}

	/** Deletes an assignment entry as the "Skipped / blocked" section's confirmed Delete does, then drains. */
	void deleteAssignment(long account, String id)
	{
		onEdt(() -> plugin.deleteAssignment(account, id));
		drain();
	}

	/** The account's single saved record; fails unless there is exactly one. */
	LedgerRecord onlyRecord(long account)
	{
		final List<LedgerRecord> records = records(account);
		if (records.size() != 1)
		{
			throw new AssertionError("expected exactly one record, found " + records.size() + ": " + records);
		}
		return records.get(0);
	}

	/**
	 * The account's active-session file, parsed directly (not via {@link LedgerStore#readActive}, which may delete
	 * it), or {@code null} if there is none.
	 */
	SessionSnapshot activeSession(long account)
	{
		final Path path = new File(ledgerDirectory, account + ".active.json").toPath();
		if (!Files.exists(path))
		{
			return null;
		}
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
		{
			return gson.fromJson(reader, SessionSnapshot.class);
		}
		catch (IOException e)
		{
			throw new AssertionError("unable to read the active session", e);
		}
	}

	/** The plugin's session tracker (read-only use: state and held completion). */
	SessionTracker tracker()
	{
		return plugin.tracker();
	}

	/** The plugin's sidebar panel, or {@code null} while the plugin is stopped. Use it on the EDT ({@link #onEdt}). */
	SlayerLedgerPanel panel()
	{
		return plugin.panel();
	}

	/**
	 * Reads and drives the plugin's sidebar panel through its named components ({@link PanelProbe}). Use it on the EDT
	 * ({@link #onEdt}), while the plugin runs.
	 */
	PanelProbe ui()
	{
		return new PanelProbe(panel(), this::iconText);
	}

	/** How many item icons ({@code ItemManager.getImage}) were asked for so far, by anything. */
	int itemImageRequests()
	{
		return itemImageRequests.get();
	}

	/**
	 * What an icon from the {@code ItemManager.getImage} fake shows: {@code "<item id>"}, or
	 * {@code "<item id> x<quantity>"} when the quantity is drawn on it; from the {@code SkillIconManager} fake,
	 * {@code "skill <SKILL>"} for a small icon ({@code "skill <SKILL> large"} for a large one); {@code null} for no icon
	 * or another icon.
	 */
	String iconText(Icon icon)
	{
		return icon instanceof ImageIcon ? itemImages.get(((ImageIcon) icon).getImage()) : null;
	}

	SessionTracker.State sessionState()
	{
		return tracker().getState();
	}

	/** The plugin's history model; use it on the EDT ({@link #onEdt}). */
	LedgerHistoryModel historyModel()
	{
		return plugin.historyModel();
	}

	/** Points the plugin's session tracker at {@code clock} (it reads the system clock otherwise). */
	void setTrackerClock(LongSupplier clock)
	{
		tracker().setClock(clock);
	}

	OverlayRows.Data overlayData()
	{
		return plugin.overlayData();
	}

	// ---- Draining ----

	/**
	 * Runs queued client-thread work, executor tasks and EDT runnables, repeatedly, until none are left (everything
	 * asynchronous the last action caused has finished).
	 */
	void drain()
	{
		checkOnTestThread();
		for (int pass = 0; pass < MAX_DRAIN_PASSES; pass++)
		{
			final boolean ran = runClientThread() | runExecutor() > 0;
			flushEdt();
			if (!ran && clientThread.pendingCount() == 0 && executor.pendingCount() == 0)
			{
				return;
			}
		}
		throw new AssertionError("queued work did not settle after " + MAX_DRAIN_PASSES + " passes (a client-thread"
			+ " task returning false is re-queued forever)");
	}

	/**
	 * Runs the client thread's queues once, as a client frame does ({@code ClientThread.invoke()} then
	 * {@code invokeTickEnd()}). A task returning {@code false} stays queued.
	 *
	 * @return whether anything was queued
	 */
	boolean runClientThread()
	{
		checkOnTestThread();
		final boolean any = clientThread.pendingCount() > 0;
		if (any)
		{
			clientThread.runQueues();
		}
		return any;
	}

	/** Runs queued executor tasks (and tasks they queue). @return the number run */
	int runExecutor()
	{
		checkOnTestThread();
		return executor.runPending(e -> recordError("executor task", e));
	}

	/**
	 * Waits until the EDT has run everything posted to it, including runnables posted by EDT runnables while it
	 * waited: it runs a check on the EDT, which sees the work still queued behind it, and repeats until there is none
	 * (checked on the EDT because, from another thread, an event the EDT has taken but not yet run looks like an empty
	 * queue). Fails after {@value #MAX_DRAIN_PASSES} rounds instead of hanging.
	 */
	void flushEdt()
	{
		checkOnTestThread();
		final boolean[] more = new boolean[1];
		for (int pass = 0; pass < MAX_DRAIN_PASSES; pass++)
		{
			onEdt(() -> more[0] = Toolkit.getDefaultToolkit().getSystemEventQueue().peekEvent() != null);
			if (!more[0])
			{
				return;
			}
		}
		throw new AssertionError("EDT work did not settle after " + MAX_DRAIN_PASSES + " rounds (EDT work keeps"
			+ " posting more)");
	}

	/** A runnable that may throw (plugin.startUp and shutDown are declared to). */
	interface EdtAction
	{
		void run() throws Exception;
	}

	/** Runs {@code action} on the EDT and waits; its exception is rethrown here (checked ones wrapped). */
	void onEdt(EdtAction action)
	{
		final Throwable[] thrown = new Throwable[1];
		final Runnable wrapper = () ->
		{
			try
			{
				action.run();
			}
			catch (Throwable t)
			{
				thrown[0] = t;
			}
		};
		try
		{
			if (SwingUtilities.isEventDispatchThread())
			{
				wrapper.run();
			}
			else
			{
				SwingUtilities.invokeAndWait(wrapper);
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
		catch (InvocationTargetException e)
		{
			throw new AssertionError(e.getCause());
		}
		final Throwable t = thrown[0];
		if (t instanceof RuntimeException)
		{
			throw (RuntimeException) t;
		}
		if (t instanceof Error)
		{
			throw (Error) t;
		}
		if (t != null)
		{
			throw new RuntimeException(t);
		}
	}

	// ---- Fakes ----

	/** Whether the current thread counts as the client thread (the test thread, outside executor tasks). */
	boolean isClientThread()
	{
		return Thread.currentThread() == clientThreadOwner && !executor.isRunningTask();
	}

	private void checkOnTestThread()
	{
		if (Thread.currentThread() != clientThreadOwner)
		{
			throw new IllegalStateException("harness methods must be called on the test thread");
		}
	}

	/** Every call to a client fake: checks it is on the client thread and counts it if plugin code is on the stack. */
	private void checkClientThread(String what)
	{
		if (!isClientThread())
		{
			recordError("thread check", new AssertionError(what + " called off the client thread, on "
				+ Thread.currentThread().getName() + (executor.isRunningTask() ? " (executor task)" : "")));
		}
		if (calledFromPlugin())
		{
			pluginCalls.incrementAndGet();
		}
	}

	/**
	 * The plugin's own classes: {@link SlayerLedgerPlugin} and the client-reading helpers it injects, whose
	 * client-thread tasks run with no plugin frame below them.
	 */
	private static final List<String> PLUGIN_CLASSES = Arrays.asList(SlayerLedgerPlugin.class.getName(),
		ClientLookups.class.getName(), SupplyReader.class.getName(), SlayerDbLookups.class.getName());

	/** Whether plugin code ({@link #PLUGIN_CLASSES}, with their nested classes and lambdas) is on the current stack. */
	private static boolean calledFromPlugin()
	{
		return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
		{
			final String name = frame.getClassName();
			for (String plugin : PLUGIN_CLASSES)
			{
				if (name.equals(plugin) || name.startsWith(plugin + "$"))
				{
					return true;
				}
			}
			return false;
		}));
	}

	/**
	 * Number of calls made so far to the client fakes ({@link Client}, {@link ItemManager}, item compositions and
	 * containers, the local player, NPC definitions, widgets) with plugin code ({@link #PLUGIN_CLASSES}), including its
	 * nested classes and lambdas, on the stack. A plugin that is stopped and left nothing subscribed makes none.
	 */
	int pluginCallCount()
	{
		return pluginCalls.get();
	}

	/**
	 * Returns and forgets the errors collected so far (swallowed exceptions, uncaught exceptions and thread-check
	 * failures), for a test that expects some; any left when the test ends fail it.
	 */
	List<Throwable> takeErrors()
	{
		final List<Throwable> taken = new ArrayList<>(errors);
		errors.removeAll(taken);
		return taken;
	}

	private void recordError(String where, Throwable t)
	{
		errors.add(new AssertionError("in " + where + ": " + t, t));
	}

	private static boolean intParams(Method method, int count)
	{
		final Class<?>[] types = method.getParameterTypes();
		if (types.length != count)
		{
			return false;
		}
		for (Class<?> type : types)
		{
			if (type != int.class)
			{
				return false;
			}
		}
		return true;
	}

	private Object answerClient(InvocationOnMock invocation) throws Throwable
	{
		final Method method = invocation.getMethod();
		if (method.getDeclaringClass() == Object.class)
		{
			return RETURNS_DEFAULTS.answer(invocation);
		}
		final String name = method.getName();
		if ("isClientThread".equals(name))
		{
			return isClientThread();
		}
		checkClientThread("Client." + name);
		switch (name)
		{
			case "getGameState":
				return gameState;
			case "getTickCount":
				return tickCount;
			case "getAccountHash":
				return accountHash;
			case "getLocalPlayer":
				return localPlayer;
			case "getWorldView":
				if (playerWorldX == NO_LOCATION)
				{
					return null;
				}
				return invocation.<Integer>getArgument(0) == BOAT_WORLD_VIEW ? boatView : worldView;
			case "getTopLevelWorldView":
				return playerWorldX == NO_LOCATION ? null : worldView;
			case "getNpcDefinition":
				return npcDefinition(invocation.getArgument(0));
			case "getVarpValue":
				if (intParams(method, 1))
				{
					return varps.getOrDefault(invocation.<Integer>getArgument(0), 0);
				}
				break;
			case "getSkillExperience":
				if (invocation.getArgument(0) instanceof Skill)
				{
					return skillXp.getOrDefault(invocation.<Skill>getArgument(0), 0);
				}
				break;
			case "getVarbitValue":
				if (intParams(method, 1))
				{
					return varbits.getOrDefault(invocation.<Integer>getArgument(0), 0);
				}
				break;
			case "getItemContainer":
				if (intParams(method, 1))
				{
					final int id = invocation.getArgument(0);
					return containers.containsKey(id) ? containerFake(id) : null;
				}
				break;
			case "getSelectedWidget":
				return selectedItemId >= 0 ? itemWidget(selectedItemId) : null;
			case "getWidget":
				if (intParams(method, 1))
				{
					final String text = objectboxText;
					return invocation.<Integer>getArgument(0) == InterfaceID.Objectbox.TEXT && text != null
						&& openInterfaces.contains(InterfaceID.OBJECTBOX) ? textWidget(text) : null;
				}
				if (intParams(method, 2))
				{
					final int group = invocation.getArgument(0);
					final int child = invocation.getArgument(1);
					return child == 0 && openInterfaces.contains(group) ? openWidget : null;
				}
				break;
			case "getDBRowsByValue":
				if (invocation.<Integer>getArgument(0) == DBTableID.SlayerTask.ID
					&& invocation.<Integer>getArgument(1) == DBTableID.SlayerTask.COL_ID
					&& taskNames.containsKey(invocation.<Object>getArgument(3)))
				{
					return Collections.singletonList(TASK_ROW_OFFSET + (Integer) invocation.getArgument(3));
				}
				if (invocation.<Integer>getArgument(0) == DBTableID.SlayerArea.ID
					&& invocation.<Integer>getArgument(1) == DBTableID.SlayerArea.COL_AREA_ID
					&& areaNames.containsKey(invocation.<Object>getArgument(3)))
				{
					return Collections.singletonList(AREA_ROW_OFFSET + (Integer) invocation.getArgument(3));
				}
				if (invocation.<Integer>getArgument(0) == DBTableID.SlayerTaskSublist.ID
					&& invocation.<Integer>getArgument(1) == DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID
					&& bossNames.containsKey(invocation.<Object>getArgument(3)))
				{
					return Collections.singletonList(BOSS_SUBLIST_ROW_OFFSET + (Integer) invocation.getArgument(3));
				}
				return Collections.emptyList();
			case "getDBTableField":
			{
				final int row = invocation.getArgument(0);
				if (row >= AREA_ROW_OFFSET)
				{
					final String areaName = areaNames.get(row - AREA_ROW_OFFSET);
					return new Object[]{invocation.<Integer>getArgument(1) == DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER
						? areaName : null};
				}
				if (row >= BOSS_TASK_ROW_OFFSET)
				{
					final String bossName = bossNames.get(row - BOSS_TASK_ROW_OFFSET);
					return new Object[]{invocation.<Integer>getArgument(1) == DBTableID.SlayerTask.COL_NAME_UPPERCASE
						? bossName : null};
				}
				if (row >= BOSS_SUBLIST_ROW_OFFSET)
				{
					return new Object[]{invocation.<Integer>getArgument(1) == DBTableID.SlayerTaskSublist.COL_TASK
						? BOSS_TASK_ROW_OFFSET + row - BOSS_SUBLIST_ROW_OFFSET : null};
				}
				final String taskName = taskNames.get(row - TASK_ROW_OFFSET);
				if (invocation.<Integer>getArgument(1) == DBTableID.SlayerTask.COL_NAME_UPPERCASE && taskName != null)
				{
					return new Object[]{taskName};
				}
				return new Object[]{null};
			}
			default:
				break;
		}
		return RETURNS_DEFAULTS.answer(invocation);
	}

	private ItemContainer containerFake(int id)
	{
		return containerFakes.computeIfAbsent(id, k -> mock(ItemContainer.class, withSettings().stubOnly().defaultAnswer(
			invocation ->
			{
				final String name = invocation.getMethod().getName();
				if (invocation.getMethod().getDeclaringClass() != Object.class)
				{
					checkClientThread("ItemContainer." + name);
				}
				final Item[] items = containers.getOrDefault(k, new Item[0]);
				switch (name)
				{
					case "getItems":
						return Arrays.stream(items).filter(i -> i != null).toArray(Item[]::new);
					case "getItem":
					{
						final int slot = invocation.getArgument(0);
						return slot >= 0 && slot < items.length ? items[slot] : null;
					}
					case "getId":
						return k;
					default:
						return RETURNS_DEFAULTS.answer(invocation);
				}
			})));
	}

	/** NPC definition fake for {@code client.getNpcDefinition}, named {@link #LOOT_NPC_NAME}. */
	private NPCComposition npcDefinition(int id)
	{
		return npcDefinitions.computeIfAbsent(id, k -> mock(NPCComposition.class,
			withSettings().stubOnly().defaultAnswer(invocation ->
			{
				final String name = invocation.getMethod().getName();
				if (invocation.getMethod().getDeclaringClass() != Object.class)
				{
					checkClientThread("NPCComposition." + name);
				}
				switch (name)
				{
					case "getId":
						return k;
					case "getName":
						return LOOT_NPC_NAME;
					default:
						return RETURNS_DEFAULTS.answer(invocation);
				}
			})));
	}

	/** The local player fake: its animation, frame and spotanims as the test set them. */
	private Object answerPlayer(InvocationOnMock invocation) throws Throwable
	{
		if (invocation.getMethod().getDeclaringClass() == Object.class)
		{
			return RETURNS_DEFAULTS.answer(invocation);
		}
		final String name = invocation.getMethod().getName();
		checkClientThread("Player." + name);
		switch (name)
		{
			case "getAnimation":
				return animation;
			case "getAnimationFrame":
				return animationFrame;
			case "hasSpotAnim":
				synchronized (spotAnims)
				{
					return spotAnims.contains(invocation.<Integer>getArgument(0));
				}
			case "getLocalLocation":
				// The tile SCENE_OFFSET tiles into the scene, at the tile's centre.
				return playerWorldX == NO_LOCATION ? null
					: new LocalPoint((SCENE_OFFSET << 7) + 64, (SCENE_OFFSET << 7) + 64,
						playerOnBoat ? BOAT_WORLD_VIEW : WorldView.TOPLEVEL);
			default:
				return RETURNS_DEFAULTS.answer(invocation);
		}
	}

	/**
	 * The top-level world view (and the boat's, which differs only in its id and in not being top-level): never an
	 * instance, plane 0, its base SCENE_OFFSET tiles below the player's tile.
	 */
	private Object answerWorldView(InvocationOnMock invocation) throws Throwable
	{
		if (invocation.getMethod().getDeclaringClass() == Object.class)
		{
			return RETURNS_DEFAULTS.answer(invocation);
		}
		final String name = invocation.getMethod().getName();
		checkClientThread("WorldView." + name);
		switch (name)
		{
			case "getId":
				return invocation.getMock() == boatView ? BOAT_WORLD_VIEW : WorldView.TOPLEVEL;
			case "isTopLevel":
				return invocation.getMock() != boatView;
			case "getPlane":
				return 0;
			case "isInstance":
				return false;
			case "getBaseX":
				return playerWorldX - SCENE_OFFSET;
			case "getBaseY":
				return playerWorldY - SCENE_OFFSET;
			default:
				return RETURNS_DEFAULTS.answer(invocation);
		}
	}

	/** A thread-checked widget fake holding an item (the selected "Use" item). */
	private Widget itemWidget(int itemId)
	{
		return mock(Widget.class, withSettings().stubOnly().defaultAnswer(invocation ->
		{
			if (invocation.getMethod().getDeclaringClass() != Object.class)
			{
				checkClientThread("Widget." + invocation.getMethod().getName());
			}
			return "getItemId".equals(invocation.getMethod().getName()) ? itemId : RETURNS_DEFAULTS.answer(invocation);
		}));
	}

	/** A thread-checked widget fake holding a text (the item dialog's text). */
	private Widget textWidget(String text)
	{
		return mock(Widget.class, withSettings().stubOnly().defaultAnswer(invocation ->
		{
			if (invocation.getMethod().getDeclaringClass() != Object.class)
			{
				checkClientThread("Widget." + invocation.getMethod().getName());
			}
			return "getText".equals(invocation.getMethod().getName()) ? text : RETURNS_DEFAULTS.answer(invocation);
		}));
	}

	/** Default answer of a fake with no state of its own (widgets): only checks the thread. */
	private Object answerChecked(String type, InvocationOnMock invocation) throws Throwable
	{
		if (invocation.getMethod().getDeclaringClass() != Object.class)
		{
			checkClientThread(type + "." + invocation.getMethod().getName());
		}
		return RETURNS_DEFAULTS.answer(invocation);
	}

	/**
	 * The {@code SkillIconManager} fake. RuneLite's reads only bundled images (no client), but its cache is not
	 * thread-safe, so the panel calls it on the EDT, as the XP tracker does; the harness fails a call from any other
	 * thread. It is not counted as a client read.
	 */
	private Object answerSkillIconManager(InvocationOnMock invocation) throws Throwable
	{
		final Method method = invocation.getMethod();
		if (method.getDeclaringClass() == Object.class)
		{
			return RETURNS_DEFAULTS.answer(invocation);
		}
		if (!SwingUtilities.isEventDispatchThread())
		{
			recordError("thread check", new AssertionError("SkillIconManager." + method.getName()
				+ " called off the EDT, on " + Thread.currentThread().getName()));
		}
		if ("getSkillImage".equals(method.getName()))
		{
			final boolean small = method.getParameterCount() == 2 && invocation.<Boolean>getArgument(1);
			final BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
			itemImages.put(image, "skill " + invocation.<Skill>getArgument(0).name() + (small ? "" : " large"));
			return image;
		}
		return RETURNS_DEFAULTS.answer(invocation);
	}

	private Object answerItemManager(InvocationOnMock invocation) throws Throwable
	{
		final Method method = invocation.getMethod();
		if (method.getDeclaringClass() == Object.class)
		{
			return RETURNS_DEFAULTS.answer(invocation);
		}
		final String name = method.getName();
		if ("getImage".equals(name) && method.getParameterCount() == 3)
		{
			// RuneLite allows getImage on any thread (it queues the sprite to the client thread); the panel calls it on
			// the EDT, as Loot Tracker does. It reads nothing from the client, so it is not counted as a client read.
			if (!SwingUtilities.isEventDispatchThread())
			{
				recordError("thread check", new AssertionError("ItemManager.getImage called off the EDT, on "
					+ Thread.currentThread().getName()));
			}
			itemImageRequests.incrementAndGet();
			final AsyncBufferedImage image = new AsyncBufferedImage(clientThread, Constants.ITEM_SPRITE_WIDTH,
				Constants.ITEM_SPRITE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
			final int quantity = invocation.getArgument(1);
			itemImages.put(image, invocation.<Integer>getArgument(0)
				+ (invocation.<Boolean>getArgument(2) ? " x" + quantity : ""));
			return image;
		}
		checkClientThread("ItemManager." + name);
		switch (name)
		{
			case "canonicalize":
				return notedItems.getOrDefault(invocation.<Integer>getArgument(0), invocation.getArgument(0));
			case "getItemPrice":
				if (intParams(method, 1))
				{
					itemPriceLookups.merge(invocation.<Integer>getArgument(0), 1, Integer::sum);
					return gePrices.getOrDefault(invocation.<Integer>getArgument(0), 0L);
				}
				break;
			case "getItemComposition":
				itemCompositionLookups.merge(invocation.<Integer>getArgument(0), 1, Integer::sum);
				return composition(invocation.getArgument(0));
			case "getItemStats":
				// Only stackable weapons have stats (loaded); other items' stats are never loaded.
				return stackableWeapons.contains(invocation.<Integer>getArgument(0)) ? new ItemStats(true, 0, 0,
					ItemEquipmentStats.builder().slot(EquipmentInventorySlot.WEAPON.getSlotIdx()).build()) : null;
			default:
				break;
		}
		return RETURNS_DEFAULTS.answer(invocation);
	}

	/** Item composition fake; an undefined id is an untradeable item with no actions (never a supply). */
	private ItemComposition composition(int id)
	{
		return compositions.computeIfAbsent(id, k -> mock(ItemComposition.class, withSettings().stubOnly().defaultAnswer(
			invocation ->
			{
				final String name = invocation.getMethod().getName();
				if (invocation.getMethod().getDeclaringClass() != Object.class)
				{
					checkClientThread("ItemComposition." + name);
				}
				final Integer unnoted = notedItems.get(k);
				final ItemDef def = itemDefs.getOrDefault(k, new ItemDef("Item " + k, new String[0], false, 0));
				switch (name)
				{
					case "getId":
						return k;
					case "getName":
						return unnoted != null && itemDefs.containsKey(unnoted) ? itemDefs.get(unnoted).name : def.name;
					case "getNote":
						// Any template id other than -1 marks a noted item.
						return unnoted != null ? 799 : -1;
					case "getLinkedNoteId":
						return unnoted != null ? unnoted : -1;
					case "getPlaceholderTemplateId":
						return -1;
					case "getInventoryActions":
						return def.actions.clone();
					case "isTradeable":
						return def.tradeable;
					case "isStackable":
						return stackableWeapons.contains(k);
					case "getHaPrice":
						return def.haPrice;
					default:
						return RETURNS_DEFAULTS.answer(invocation);
				}
			})));
	}

	/** Looks up a package-private {@link ClientThread} frame method; see {@link HarnessClientThread}. */
	private static Method clientThreadFrameMethod(String name)
	{
		try
		{
			final Method method = ClientThread.class.getDeclaredMethod(name);
			method.setAccessible(true);
			return method;
		}
		catch (NoSuchMethodException e)
		{
			throw new AssertionError("ClientThread." + name
				+ "() not found; has RuneLite changed how Hooks run the client thread's queues?", e);
		}
	}

	/**
	 * The real {@link ClientThread}, with every queued or inline task wrapped so an exception it throws is reported
	 * (the real class only logs it) and so the harness knows how many tasks are queued. Its queues are run through
	 * its package-private {@code invoke()} and {@code invokeTickEnd()}, which the client's Hooks call every frame.
	 * Those are called by reflection: a test-source helper in {@code net.runelite.client.callback} could call them
	 * directly, but the client jar is signed, so the JVM refuses unsigned classes in its packages
	 * ({@code SecurityException: signer information does not match}). The methods are looked up once, when
	 * PluginHarness loads, so a RuneLite update that renames them fails every harness test with a clear message.
	 */
	final class HarnessClientThread extends ClientThread
	{
		/** Tasks in either queue: queued, or run inline and re-queued after returning {@code false}. */
		private final AtomicInteger pending = new AtomicInteger();

		@Override
		public void invoke(BooleanSupplier r)
		{
			// Runs inline on the client thread (a false result queues it, see Captured); otherwise calls invokeLater.
			super.invoke(capture(r));
		}

		@Override
		public void invokeLater(BooleanSupplier r)
		{
			final Captured captured = capture(r);
			captured.queued();
			super.invokeLater(captured);
		}

		@Override
		public void invokeAtTickEnd(Runnable r)
		{
			final Captured captured = capture(() ->
			{
				r.run();
				return true;
			});
			captured.queued();
			super.invokeAtTickEnd(captured::getAsBoolean);
		}

		private Captured capture(BooleanSupplier r)
		{
			if (r instanceof Captured)
			{
				return (Captured) r;
			}
			return new Captured(r);
		}

		int pendingCount()
		{
			return pending.get();
		}

		/** Runs the queued tasks, then the tick-end tasks, as a client frame does. */
		void runQueues()
		{
			runFrameMethod(INVOKE);
			runFrameMethod(INVOKE_TICK_END);
		}

		private void runFrameMethod(Method method)
		{
			try
			{
				method.invoke(this);
			}
			catch (InvocationTargetException e)
			{
				recordError("ClientThread." + method.getName(), e.getCause());
			}
			catch (IllegalAccessException e)
			{
				throw new AssertionError(e);
			}
		}

		/**
		 * A client-thread task that reports its exception before the client thread swallows it, and keeps
		 * {@link #pending} up to date: counted while it is in a queue, until it returns {@code true} or throws (both of
		 * which remove it, inline or queued).
		 */
		private final class Captured implements BooleanSupplier
		{
			private final BooleanSupplier task;
			private boolean inQueue;

			Captured(BooleanSupplier task)
			{
				this.task = task;
			}

			synchronized void queued()
			{
				if (!inQueue)
				{
					inQueue = true;
					pending.incrementAndGet();
				}
			}

			private synchronized void done()
			{
				if (inQueue)
				{
					inQueue = false;
					pending.decrementAndGet();
				}
			}

			@Override
			public boolean getAsBoolean()
			{
				final boolean result;
				try
				{
					result = task.getAsBoolean();
				}
				catch (RuntimeException | Error e)
				{
					done();
					recordError("client thread task", e);
					throw e;
				}
				if (result)
				{
					done();
				}
				else
				{
					// Re-run later: ClientThread keeps it queued, or queues it if it ran inline.
					queued();
				}
				return result;
			}
		}
	}
}
