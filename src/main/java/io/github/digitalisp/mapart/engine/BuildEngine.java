package io.github.digitalisp.mapart.engine;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.digitalisp.mapart.bot.BotActions;
import io.github.digitalisp.mapart.bot.BotTask;
import io.github.digitalisp.mapart.bot.FightArea;
import io.github.digitalisp.mapart.bot.Hit;
import io.github.digitalisp.mapart.bot.MobHits;
import io.github.digitalisp.mapart.bot.MobMemory;
import io.github.digitalisp.mapart.bot.ContainerVisit;
import io.github.digitalisp.mapart.bot.DisconnectDrop;
import io.github.digitalisp.mapart.bot.EscapeRequest;
import io.github.digitalisp.mapart.bot.BoxedInDrop;
import io.github.digitalisp.mapart.bot.FallRescue;
import io.github.digitalisp.mapart.bot.InventoryView;
import io.github.digitalisp.mapart.bot.MapView;
import io.github.digitalisp.mapart.bot.PlacementListener;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.TransferResult;
import io.github.digitalisp.mapart.bot.WithdrawMode;
import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.bot.RideWatch;
import io.github.digitalisp.mapart.bot.impl.ItemIds;
import io.github.digitalisp.mapart.bot.impl.NoGoZones;
import io.github.digitalisp.mapart.bot.impl.PlaceMode;
import io.github.digitalisp.mapart.bot.impl.StandSpots;
import io.github.digitalisp.mapart.bot.impl.Valuables;
import io.github.digitalisp.mapart.catalog.BlockCatalog;
import io.github.digitalisp.mapart.catalog.Eyeblossoms;
import io.github.digitalisp.mapart.config.CsMaterials;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.config.MapArtConfig.ActuatorCfg;
import io.github.digitalisp.mapart.config.MapArtConfig.DropPointCfg;
import io.github.digitalisp.mapart.config.MapArtConfig.PlatformCfg;
import io.github.digitalisp.mapart.config.SiteRules;
import io.github.digitalisp.mapart.coord.Abandon;
import io.github.digitalisp.mapart.coord.Claim;
import io.github.digitalisp.mapart.coord.ClaimMenuGate;
import io.github.digitalisp.mapart.coord.Coordination;
import io.github.digitalisp.mapart.coord.LocalCoordination;
import io.github.digitalisp.mapart.coord.SharedStock;
import io.github.digitalisp.mapart.coord.Teammates;
import io.github.digitalisp.mapart.coord.TileProgress;
import io.github.digitalisp.mapart.coord.TileUnitSpec;
import io.github.digitalisp.mapart.coord.Unit;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.model.TileStatus;
import io.github.digitalisp.mapart.plan.BuildPlanner;
import io.github.digitalisp.mapart.plan.FarLoadFill;
import io.github.digitalisp.mapart.plan.FarStorage;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.plan.LaneFlip;
import io.github.digitalisp.mapart.plan.LevelMerge;
import io.github.digitalisp.mapart.plan.RestockLoads;
import io.github.digitalisp.mapart.plan.RestockPlanner;
import io.github.digitalisp.mapart.render.ColorResolver;
import io.github.digitalisp.mapart.render.Renderer;
import io.github.digitalisp.mapart.stock.StockIndex;
import io.github.digitalisp.mapart.store.EngineCheckpoint;
import io.github.digitalisp.mapart.store.JobStore;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static io.github.digitalisp.mapart.engine.EngineTuning.*;

/**
 * The tile lifecycle state machine.
 *
 * <p>Threading: the tick entry points ({@link #onBotTickStarting()}, {@link #onBotTick()},
 * {@link #onBotTickStopped()}, {@link #shutdown()}) are synchronized and normally run on the client tick
 * thread. {@link EngineControl} methods and {@link #onDisconnect()} only post commands, which are
 * drained at the start of the next tick. Store writes go through one ordered writer on the executor;
 * tile loading and PNG rendering run on the executor too.
 *
 * <p>Every phase is a "step" that looks at the world and inventory and either starts one task, changes
 * phase, or sleeps. Nothing about build progress is remembered: after a disconnect, a pause or a failure
 * the phase is simply re-entered and re-derives what is left.
 */
public final class BuildEngine implements EngineControl {
    private static final Set<String> PLATFORM_KINDS = Set.of("MOSS", "SOUL_SAND");
    private static final Set<String> ANVILS = Set.of("minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil");
    private static final Set<String> FOODS = Set.of(
        "minecraft:golden_carrot", "minecraft:cooked_beef", "minecraft:bread", "minecraft:cooked_porkchop",
        "minecraft:cooked_mutton", "minecraft:cooked_chicken", "minecraft:cooked_salmon", "minecraft:cooked_cod",
        "minecraft:baked_potato", "minecraft:carrot", "minecraft:apple", "minecraft:pumpkin_pie",
        "minecraft:cooked_rabbit", "minecraft:melon_slice", "minecraft:sweet_berries");
    private static final String AUTO_EAT = "AutoEat";
    private static final String AUTO_TOTEM = "AutoTotem";
    private static final String ANTI_AFK = "AntiAFK";

    private final MapArtConfig config;
    private final JobStore store;
    private final BotActions bot;
    private final BuildPlanner planner;
    private final RestockPlanner restockPlanner;
    /** {@link #keepForLoad}'s cheap estimate of a crowded load (the greedy fill). */
    private final GreedyRestockPlanner crowdEstimate = new GreedyRestockPlanner();
    /** The elytra legs' supply: the rocket chest a sign names ({@code build.elytraLegs}, {@link #flightKit}). */
    private final ElytraKit kit = new ElytraKit();
    private final BlockCatalog catalog;
    private final Renderer renderer;
    private final ColorResolver colors;
    private final Notifier notifier;
    private final Clock clock;
    private final Executor executor;
    private final ModuleSwitch modules;
    private final Logger log;
    private final SerialWriter writer;
    /**
     * The seam to the job source and the shared resources: {@link LocalCoordination} for the bot (the local queue,
     * claims granted at once, stock.json); tests drive the engine through a scripted one.
     */
    private final Coordination coord;
    /** The stock snapshots this bot reads off open containers go through it (compare-and-set on the shared index). */
    private final SharedStock sharedStock;
    /** The bot's menu gate: the coordination's {@code chest:} and {@code station:} claims around every menu. */
    private final ClaimMenuGate menuGate;

    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private volatile EngineStatus status;
    private volatile String pauseReason = "";
    private volatile boolean safetyLocked;
    /** Guards {@link #firstStop} and {@link #laterStops} (pause() and resume() run on any thread). */
    private final Object stopLock = new Object();
    /** "SAFETY STOP: reason" of the first stop while safety-locked: it stays the head of the pause reason. */
    private String firstStop = "";
    /** Reasons of the safety stops (and pauses) after the first one while safety-locked, oldest first. */
    private List<String> laterStops = List.of();
    private volatile Runnable haltHook = () -> { };
    private volatile Runnable configSaver = () -> { };

    // ---- tick-thread state ----
    private boolean ticking;
    /**
     * The bot's ticks (re)started: the first decision looks for what the disconnect dropped
     * ({@link #recoverDisconnectDrops}).
     */
    private boolean dropsDue;
    private boolean restored;
    private boolean resumeBeforeRestore;
    private long tick;
    private long wakeTick;
    private boolean acted;

    private Phase phase = Phase.IDLE;
    /** The current phase is one of the ride's (RIDE_BOARD to RIDE_END); for the teleport guard, any thread. */
    private volatile boolean ridePhase;

    /** Any thread: the bot boards, rides or leaves the muncher's cart ({@link TeleportGuard#rideMode}). */
    public boolean ridePhase() {
        return ridePhase;
    }
    private long phaseSince;
    private Phase blockedFrom;
    private String note = "";
    private int failures;
    private Ctx ctx = new Ctx();
    private BotTask task;
    private Runnable taskOk;
    private Consumer<String> taskFail;
    /**
     * The step attempt ({@link Ctx#attempt}) the running task was started for, and its context: a task cancelled before
     * it ended (a disconnect, a pause, a back-off) gives it back, so the step tries the same chest again instead of
     * counting a fetch that never finished (gate: RENAME went BLOCKED "no XP ... no XP chest tried yet").
     */
    private String taskAttempt;
    private Ctx taskAttemptCtx;
    private String parkedAttempt;
    private Ctx parkedAttemptCtx;
    /** Setback back-off ({@link #backoff}): standing still until {@link #backoffUntil} (clock millis). */
    private boolean backingOff;
    private long backoffUntil;
    private String noteBeforeBackoff = "";
    private long lastBackoffNotice = -1;   // clock millis; -1 = never
    /**
     * Setback escape ladder: the rung of the escape that runs once the
     * back-off is over, or runs now (0 = none), and the burst's feet cell (null: from where the bot stands then). A
     * pause, a stop, a disconnect, a player taking control or a newer back-off drops it.
     */
    private int escapeRung;
    private @Nullable Pos escapeAt;
    /** The running task is the escape of {@link #escapeRung}: not a step attempt, a failure is only logged. */
    private boolean escaping;
    /** Clock millis escapes started, within {@link TeleportGuard#BACKOFF_ESCALATION_WINDOW_MS} (the stuck alert). */
    private final Deque<Long> escapeStarts = new ArrayDeque<>();
    /** Fall rescue ({@code build.fallRescue}): notices a fallen bot. */
    private final FallWatch fallWatch = new FallWatch();
    /** The running rescue task, or the wait before the next try of a failed one; null when none. */
    private @Nullable BotTask fallTask;
    /** Clock millis falls were seen, within {@link #FALL_RESCUE_WINDOW_MS}. */
    private final Deque<Long> fallStarts = new ArrayDeque<>();
    /** Ticks one try of a fall rescue may take (the climb's blocks placed, jumped onto and broken again). */
    static final int FALL_RESCUE_TICKS = 2400;
    /** Falls within {@link #FALL_RESCUE_WINDOW_MS} after which a further one is alerted (once per window); rescued all the same. */
    static final int MAX_FALL_RESCUES = 3;
    static final long FALL_RESCUE_WINDOW_MS = 10 * 60_000L;
    /** Seconds before the next try of a failed fall rescue, by failures in a row (the last one repeats). */
    static final int[] FALL_RETRY_SECONDS = {15, 30, 60, 120, 240};
    /** Failed tries in a row that got the feet no higher than before, after which the engine goes BLOCKED ("Bot fell"). */
    static final int MAX_FALL_TRIES = 4;
    /** The current fall: failed tries in a row, those of them without progress, the highest feet y seen. */
    private int fallFailures;
    private int fallStuck;
    private int fallBestY;
    /**
     * Blocks the last rescue try placed and left standing (cell → id): the next try takes them over, even one for a
     * later fall (a try stopped half way, by a fight or a stop, leaves its blocks). Cleared once a try got out (the
     * blocks it could not break again were alerted then).
     */
    private volatile Map<Pos, String> fallOwn = Map.of();
    /** The current fall: the highest feet level the last try stood at ({@link FallRescue.Ended#standY}). */
    private volatile int fallStandY = FallRescue.ANY_LEVEL;
    /** The current fall could not be undone, but the bot is in water: it holds its place and tries on (alerted once). */
    private boolean fallHolding;
    /** Clock millis of the last "Bot keeps falling" alert. */
    private long fallRepeatAlerted = Long.MIN_VALUE / 2;
    /** The running task is a re-sync ({@link #resync}): not a step attempt, its end is only logged. */
    private boolean resyncing;
    /** Keep-alive while the bot waits: when it is due, and which way it turns. */
    private final KeepAlive keepAlive = new KeepAlive();
    /** The keep-alive in flight (two ticks), outside the task slot; null when none. */
    private BotTask keepAliveTask;
    /** Its head turn in degrees, and how long the bot had been idle when it started (ms). */
    private float keepAliveTurn;
    private long keepAliveIdle;

    // current tile
    private Job job;
    private Job.TileState tile;
    private PlatformCfg platform;
    /** The coordination's unit for the current tile ({@code tile:<x>,<z>}), or null without a current tile. */
    private @Nullable Unit unit;
    /** The current tile's {@code platform:<id>} claim (exclusive), from SELECT to FINISH_TILE; null without one. */
    private @Nullable Claim platformClaim;
    /** The unit's last progress report: when, with how many cells placed, in which phase ({@link #reportUnit}). */
    private long unitReportedAt;
    private int unitReportedPlaced = -1;
    private @Nullable Phase unitReportedPhase;
    /** The facts of the last report ({@link #unitFacts}); -1 before the first. */
    private int unitReportedFacts = -1;
    /** While a phase runs, a unit's progress goes out at most this often (and only when the placed count changed). */
    static final long UNIT_PROGRESS_MS = 5_000L;
    /**
     * Lane tiles: this bot's earliest band start per tile ({@code <map>/<i>,<j>}), for the Map done post's time
     * ({@link #adoptTileStart}). The last 64 tiles; tick thread.
     */
    private final Map<String, Long> ownBandStarts = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 64;
        }
    };
    /** The tail's {@code tile.startedAt} is its tile's first band start (else the tail's own start). */
    private boolean tileStartKnown;
    private TilePlan plan;
    private TilePlan.ExpectedMap expected;
    /** Colour-suppression ride: the current job's art, rebuilt from its passes for the map checks, and its job id. */
    private io.github.digitalisp.mapart.model.SuppressionArt csArt;
    private String csArtJob;
    /** {@link #expected} holds the finished map's colours (after the ride), not the fill's. */
    private boolean csExpectedFinal;
    /** The machine guard's reference per platform id (from the store, or recorded before the first ride). */
    private final Map<String, MachineGuard.Snapshot> guardReference = new HashMap<>();
    private boolean resumeCandidate;
    private boolean aborting;
    private boolean builtNotified;
    private int clearAttempts;
    private int repairPasses;
    private int fillAttempts;
    private int foreignInspections;
    private int noProgressRounds;
    private int lastRestockWithdrawn = -1;
    /**
     * Seconds of restock the loads of this tile lost to leftovers kept instead of dropped ({@code build.lazyDrop}): each restock that
     * keeps them adds what dropping would have saved; the drop comes when the total pays for the trip. Ski rental: the
     * kept slots cost the same again every restock, so a limit per restock alone would never drop them.
     */
    private double lazyDropDebt;
    /** Items the sorter did not return in time on this tile ({@link PlatformCfg#sorterRefills}): far storage as usual. */
    private final Set<String> sorterGaveUp = new HashSet<>();
    /**
     * Per platform id, the items this engine run put into that platform's sorter: the art layer's blocks at each NUKE
     * (a finished map's nuke feeds the next tile), and materials dropped or thrown back into the sorter's stream. Only
     * those are "on their way back through the sorter" in the logs; any other near shortage needs restocking.
     */
    private final Map<String, Set<String>> sorterFedBy = new HashMap<>();
    /**
     * Items a far load filled from far storage while the near chests still held some ({@code build.farLoadFill}):
     * those chests were not opened, so the next load that would go far for the item looks at them first.
     */
    private final Set<String> fillUnseen = new HashSet<>();
    /** An item short in the near chests by at least this many cells is worth a stand at the pickup spot. */
    private static final int PICKUP_MIN_MISSING = 64;
    /** VERIFY: cells missing at the last counted look ({@code build.verifyProgressFree}); -1 at a tile's start. */
    private int lastVerifyMissing = -1;
    /**
     * Pickup stands in a row that brought nothing (or a failed one: {@link #PICKUP_EMPTY_MAX}); at the limit no more stands
     * this tile (live 2026-10-14 02:54-03:00: five stands, none gained an item, 8 s or more each).
     */
    private int pickupEmpty;
    static final int PICKUP_EMPTY_MAX = 2;
    /** Before a pickup stand the short items' near chests are looked at again once their last look is this old. */
    static final long PICKUP_LOOK_AGE_MS = 45_000L;
    private List<Lane> buildLanes;
    /** Leading {@link #buildLanes} the restock load was planned for, a partially loaded last one included. */
    private int buildLanesLoaded;
    /**
     * {@link #buildLanes} end where the restock planner cut the round ({@link RestockPlanner.Plan#stopAtCut}) and lanes
     * are left after them: the round ends in RESTOCK, not VERIFY.
     */
    private boolean buildRoundCut;
    /**
     * The restock left far-storage cells out of {@link #buildLanes} ({@code build.farTripMinCells}): when the round has
     * run every lane it goes back to RESTOCK for them (fetched once nothing else is left), not to VERIFY.
     */
    private boolean buildFarLeftOut;
    /** Loads planned again per RESTOCK without a few far cells, at most (each round leaves out more items). */
    private static final int FAR_REPLANS = 4;
    private List<Lane> lanesCache;
    private Map<String, Integer> needsCache;
    private EngineCheckpoint restoredCheckpoint;
    /**
     * Cells a BUILD lane skipped while their item was in the inventory (out of reach, no line of sight, not
     * confirmed, ...). More material cannot help them, so BUILD and its restock planning leave them to VERIFY's repair
     * lanes instead of running the same lane again every round. Cleared when VERIFY hands back to RESTOCK.
     */
    private final Set<Pos> deferred = new HashSet<>();
    /**
     * Feet cells of this tile's setback bursts: the lanes never stand in, or
     * walk through, a cell within one block of them, nor does an escape. Cleared with {@link #deferred} when a tile
     * starts ({@link #activate}) and ends ({@link #clearCurrent}), not when VERIFY hands back to RESTOCK: the spot stays
     * bad.
     */
    private final Set<Pos> stuckCells = new LinkedHashSet<>();
    /** Items the last no-progress BUILD rounds lacked: STOCK_CHECK re-looks at the containers that could hold them. */
    private Set<String> recheckItems;

    // per connection: map ids are randomised per session on 2b2t
    private Integer mapId;
    private Integer lockedMapId;
    /**
     * The map this tile stored ({@link #mapDone}), for the unit's done report: its id as the post named it (kept
     * across a reconnect); null = none (not stored yet, or stored by an earlier holder: the id then comes with the
     * handed-over progress).
     */
    private @Nullable Integer storedMapId;
    /** Filled maps the bot created or locked in this connection: only its own maps are locked, renamed or stored. */
    private final Set<Integer> ownMaps = new HashSet<>();
    /** Fresh maps made for this tile because no data arrived for the held one ({@link #missingMapData}). */
    private int freshMaps;
    /**
     * Pictures of this tile's area on the bot's own maps of it (the held map at the cover wait's polls), newest last, at
     * most {@link #TILE_PICTURES}: an unlocked map that shows one of them is this tile's too, however much of the art a
     * cover hides ({@link #showsTilePicture}). Per tile, kept across reconnects (the pixels do not change with the ids),
     * not across restarts.
     */
    private final List<byte[]> tilePictures = new ArrayList<>();
    /**
     * Working items whose chests all took none at the last try (full, or none configured): RESTOCK does not walk there
     * again for them; the next STORE tries once more ({@link #tidyWorkingItems}). Engine lifetime.
     */
    private final Set<String> tidyRefused = new HashSet<>();

    // the account is the owner's own: only materials leave the inventory (Leftovers)
    /** Item ids the bot withdrew from material chests (engine lifetime). */
    private final Set<String> withdrawnMaterials = new HashSet<>();
    /**
     * Item ids of the blocks the NUKE broke on an art layer (engine lifetime): their drops are materials the bot picked up
     * itself, also when the art was foreign and no chest ever held them (local 10x run: a foreign nuke's gray terracotta
     * counted as the owner's and could not make room for the XP bottles).
     */
    private final Set<String> nukedMaterials = new HashSet<>();
    /** Tool ids taken from the tool chests (engine lifetime): worn ones of these may go back there. */
    private final Set<String> toolChestIds = new HashSet<>();
    /** Per connection: the kept non-material items last logged, and last alerted for crowding the inventory. */
    private String keptLogged;
    private String crowdedAlerted;

    // stock + queue
    private Set<Pos> materialContainers;
    /** The colour-suppression chests are in {@link #materialContainers} (found while a CS job was current). */
    private boolean csDiscovered;
    /**
     * Storage regions a partial seed left to discover ({@link DiscoverySeed}, {@code build.seedDiscoveryPartial}), by
     * signature part; null = none (the discovery is complete or still to do as a whole).
     */
    private @Nullable Set<String> pendingRegions;
    /** The index row's materials: item id → the x of its chest column (read with the palette), or null before the first read. */
    private Map<String, Integer> csColumns;
    /**
     * Full near sweeps (tried, or a full scan that read every near container), per platform; with
     * {@code build.nearScanPersist} also kept in the stock index and restored after a restart ({@link #restoreNearTimes}).
     */
    private final Map<PlatformGeometry, Long> nearScannedAt = new HashMap<>();
    /** Targeted near looks, per platform and item; kept like {@link #nearScannedAt}. */
    private final Map<PlatformGeometry, Map<String, Long>> nearLookedAt = new HashMap<>();
    /** Material containers whose scan failed (e.g. out of reach), per session; skipped once at the limit. */
    private final Map<Pos, Integer> scanFailures = new HashMap<>();
    private boolean forceScan;
    private final Map<String, Long> waitingCheckedAt = new HashMap<>();
    private final Map<String, Map<String, Integer>> waitingNeeds = new HashMap<>();
    private final Map<String, String> lastShortAlert = new HashMap<>();
    private final Set<String> warned = new HashSet<>();
    // partial build (PartialBuild): the current tile waits on its platform for the cells the stock cannot cover
    /** Clock time the wait began; -1 = not waiting. */
    private long materialsWaitSince = -1;
    /** Clock time of the next look at the chests while waiting. */
    private long materialsRecheckAt;
    /** What the wait is for (item → cells). */
    private Map<String, Integer> materialsShort = Map.of();
    /** What the stock had to bring when the wait began (the needs less the inventory): want less short is in storage. */
    private Map<String, Integer> materialsWant = Map.of();
    /**
     * What the inventory held of the short items when the wait began (item → count): a change looks again at once
     * ({@code build.stockWaitWakeOnChange}).
     */
    private Map<String, Integer> materialsCarried = Map.of();
    /**
     * Why the waiting tile looks again before {@link #materialsRecheckAt} (bot ticks started, the inventory changed);
     * null = no reason.
     */
    private String materialsWake;
    /**
     * This round builds only what the bot carries ({@code build.partialBuildCarried}): RESTOCK, BUILD and VERIFY leave
     * out every cell the inventory does not cover ({@link #uncovered}), so no trip is made. Ends at the next STOCK_CHECK.
     */
    private boolean carriedRound;
    /**
     * The last carried round of this tile: what it carried (item → cells) and the tile's placed count then. The same
     * again with nothing placed since (cells it cannot place) is not run again: the tile waits.
     */
    private Map<String, Integer> carriedRoundItems = Map.of();
    private int carriedRoundPlaced = -1;
    /** The tile waits with carried cells the last carried round could not place: logged once. */
    private boolean carriedRoundNoted;
    /**
     * Stock-aware SELECT ({@code build.skipShortTiles}): what each tile needs in all ("jobId x,z" → item → cells), read
     * once per run off the tick thread.
     */
    private final Map<String, Map<String, Integer>> tileNeeds = new HashMap<>();
    /** A map set aside for lack of materials with nothing placed: for the next start line and "Nothing to build". */
    private record ShortMap(Job job, Job.TileState tile, Map<String, Integer> needs, Map<String, Integer> shortfall) { }
    /** Maps set aside and not started since ("jobId x,z" → why), in the order they were set aside. */
    private final Map<String, ShortMap> shortMaps = new LinkedHashMap<>();
    /** Maps set aside since the last start line: the next start line names them ("instead of …"). */
    private final List<ShortMap> skipped = new ArrayList<>();
    /** Short items of the last "Nothing to build" alert while idle; null once a map started since. */
    private String nothingAlerted;
    /** The current tile's start line is still to be posted: when STOCK_CHECK lets it build (it may be set aside). */
    private boolean announceStart;

    // carry-over between tiles (build.carryOver)
    /** The tile built next on the current platform with its plan there, loaded at the NUKE of a stored map; or null. */
    private @Nullable CarryNext carryNext;
    /** The current map's NUKE left {@link #carryNext}'s cells in place: FINISH_TILE marks that tile begun there. */
    private boolean carriedOver;
    /** Cells {@link #carryNext} had in place when that NUKE ended. */
    private int carriedCells;

    /**
     * The tile built next on the current platform: its key ({@code <job> <x>,<z>}), its log label, the job and tile
     * and its plan on this platform.
     */
    private record CarryNext(String key, String label, @Nullable Job job, Job.@Nullable TileState tile, TilePlan plan) { }

    /** {@link CarryNext} before its plan is read. */
    private record CarrySource(String key, String label, @Nullable Job job, Job.@Nullable TileState tile,
                               java.util.concurrent.Callable<TilePlan> load) { }

    // the base's map counter (site.mapCounter), pressed once per stored map in COUNT
    /** The button found in {@code site.mapCounterColumn} this run (null: not yet, or {@code mapCounter.pos} is set). */
    private volatile Pos counterFound;
    /** "x,z" of the column {@link #counterFound} was found in (the config may change). */
    private String counterFoundIn;
    /** Map counter problems already alerted this run (a missing or doubled button: once, not per map). */
    private final Set<String> counterAlerted = new HashSet<>();
    /** Clock time the bot ticks last (re)started: a (re)connect, or a controlling player leaving. -1 = not yet. */
    private long inGameSince = -1;
    /** Chunk (x, z) of the map counter tracked by {@link #trackCounterChunk}, and since when it is loaded (-1: not). */
    private long counterChunk = Long.MIN_VALUE;
    private long counterChunkSince = -1;
    /** Last good state of the counter's cube after a clean press ({@link MapCounter#normalize}d); null = none yet. */
    private Map<Pos, BlockSpec> counterGood;
    /** A jam was alerted and the counter has not checked clean since ({@link #counterJam}). */
    private boolean counterEpisode;
    /** Counts owed: maps stored while the counter looked jammed, pressed before the next map's own press. */
    private int counterOwed;
    /** The maps the owed counts are for (for the log), oldest first. */
    private final Deque<String> counterOwedFor = new ArrayDeque<>();
    /** Owed counts pressed since the counter last checked clean all the way (for the "working again" line). */
    private int counterOwedPressed;
    /** Maps in a row whose COUNT found the cube different from {@link #counterGood} with no piston stuck out. */
    private int counterOddVisits;
    /** The current recovery took the counter's changed state as its new normal. */
    private boolean counterAdopted;

    // module suppression
    private boolean suppressing;
    private final List<String> suppressed = new ArrayList<>();

    // eating (the engine eats itself; ZenithProxy's AutoEat is suppressed)
    private long eatRetryTick;
    private int foodFetches;
    private int eatFailures;
    private boolean outOfFood;

    // the offhand's item put back from the inventory (bot.offhandRepairWanted)
    /** No repair before this tick (after a failed one). */
    private long offhandRetryTick;
    /** Repairs failed in a row; at {@link #OFFHAND_MAX_FAILURES} the engine stops trying and alerts. */
    private int offhandFailures;

    // vitals: hunger and health checked every tick (checkVitals), acted on between tasks (tendVitals)
    /** The task set aside at a safe point while the bot eats or stands still, with its completion handlers. */
    private BotTask parkedTask;
    private Runnable parkedOk;
    private Consumer<String> parkedFail;
    /** The running task that was asked to park, and whether it can. */
    private BotTask askedToPark;
    private boolean parkAccepted;
    /** The running task is the engine's own eat or food fetch: never interrupted for hunger or health. */
    private boolean vitalsTask;
    /** Low health: the engine stands still (eating if it can) until health is back to {@code HEALTH_RESUME}. */
    private boolean healthHold;
    private float holdStartHealth;
    private long lastLowHealthAlert = -1; // clock millis
    /** Times this health hold left the cell because health still dropped there ({@code safety.leaveWhenHurt}). */
    private int holdLeaves;
    /** No totem (offhand and inventory) since this tick, or -1 ({@code safety.requireTotem}). */
    private long totemGoneSince = -1;
    /** The "No totem" alert went out for this episode. */
    private boolean noTotemAlerted;
    private static final String TOTEM = "minecraft:totem_of_undying";
    /**
     * Ticks without a totem before it counts as missing (5 s). Live 2026-10-13 00:18: after a server setback the
     * offhand read as gray terracotta for about 2 s and then as the totem again, with no move by the plugin (a stale
     * inventory view); 2 s would have turned that into a "No totem" alert and a hold.
     */
    static final int TOTEM_GONE_TICKS = 100;
    /** Health hold: a drop of this much while standing still makes the bot leave the cell (not yet BLOCKED). */
    static final float LEAVE_DROP = 1f;
    /** Health hold: leaves per hold before a further drop goes BLOCKED. */
    static final int MAX_HOLD_LEAVES = 2;
    /**
     * Status note while eating ({@link #EATING_NOTE}), mending a tool ({@link #MENDING_NOTE}), fetching food or holding
     * for health; it overrides the phase note.
     */
    private String vitalsNote = "";

    // 2b2t's scheduled restart (RestartHold)
    private final RestartHold restart = new RestartHold();
    /** The hold is in force (as of this tick): carts off, no NUKE, no new clear press. Its start is announced once. */
    private boolean restartHolding;
    /**
     * Clock time of the last server restart the engine saw (the connection lost, or the bot moved to the queue, with a
     * restart announced); 0 = none on record. Kept in the checkpoint. 2b2t restarts {@code site.restartEveryMinutes}
     * after it ({@link #restartInsideRide}).
     */
    long lastServerRestartAt;
    /** The hold's own task (switching the carts off): never asked to stop for the hold. */
    private BotTask restartTask;
    /** The hold switched the carts off before a clear (CLEAR, or a timed-out WAIT_CLEAR): ON again before the press. */
    private boolean restartCartsOff;

    // starving rescue (rescueStarving): food while the engine does not work (paused, SAFETY STOP, BLOCKED, IDLE)
    /** The running task is the starving rescue's fetch or eat: ticked and given the modules while not working. */
    private boolean rescueTask;
    /** The rescue waits until this tick after bot ticks start (inventory and chunks arrive first). */
    private long rescueReadyTick;
    /** A safety stop (kill switch, teleport guard) in this connection: no rescue until the next one or resume. */
    private boolean rescueHeld;
    /** Food chests the rescue fetched from in this starving episode ({@link #trackFoodEpisode}). */
    private final Set<Pos> rescueTried = new HashSet<>();
    /** This episode's rescue tried every food chest without food: no more fetches until the episode ends. */
    private boolean rescueSpent;
    /** Tick the rescue first waited for an unloaded food chest in this episode; -1 = not waiting. */
    private long rescueWaitSince = -1;
    /** The "Starving" alert went out in this episode. */
    private boolean starvingAnnounced;

    // tools never break: mended with XP at safe points, swapped at the floor, else BLOCKED (careForTools)
    private long toolRetryTick;
    private long xpRetryTick;
    private int xpFetches;
    /** What this round of XP fetches for mending found (why no bottles came). */
    private final XpFetchLog mendXpFetch = new XpFetchLog();
    private int toolStops;
    private final Map<String, Integer> toolFetches = new HashMap<>();
    private final Map<String, Long> swapRetryTick = new HashMap<>();
    private final Map<String, Integer> mendTrouble = new HashMap<>();
    /** Tool ids being mended: mending goes on up to mendTargetPercent once it started below mendBelowPercent. */
    private final Set<String> mendingIds = new HashSet<>();
    /** Tool kinds whose worn tool could be neither mended nor swapped (cleared on resume or when one is usable). */
    private final Set<String> toolsOut = new HashSet<>();
    /** The NUKE's wear per tool id and the XP bottles a nuke needs. */
    private final NukeXp nukeXp = new NukeXp();
    /** Mend tasks started so far: a pause at a lane end that started none ends the pauses of that NUKE. */
    private int mendsStarted;
    /** A mend's XP fetch found the inventory full (pickups on the way): its try was given back once, room comes first. */
    private boolean xpFullRetried;
    /**
     * The NUKE break task running now (mending during the clear, the lane-end deferral): its targets, the blocks broken
     * so far and the uses per tool id when it started.
     */
    private int nukeTaskTargets;
    private int nukeTaskBroken;
    private Map<String, Integer> nukeTaskUses = Map.of();
    /** WAIT_CLEAR: a mend ran since the clear was last polled; the next one waits for a poll ({@link #mendDuringClear}). */
    private boolean clearMendAwaitsPoll;
    /** The server simulation distance last logged ({@link #trackSimulationDistance}); 0 = none yet. */
    private int simDistanceLogged;

    // progress
    private final RateTracker rate = new RateTracker(RATE_WINDOW_MS);
    private long lastProgressAt;
    private long placedCounter;
    /** Small server setbacks not yet added to the tile's counters ({@link #noteSetback}; any thread). */
    private final java.util.concurrent.atomic.AtomicInteger setbacksSeen = new java.util.concurrent.atomic.AtomicInteger();

    private final PlacementListener listener = new PlacementListener() {
        @Override
        public void placed(Placement placement) {
            placedCounter++;
            if (tile != null && !raisedPlaced(placement) && tile.placed < tile.total) tile.placed++;
            rate.record(clock.millis());
            deferred.remove(placement.pos());
        }

        @Override
        public void skipped(Placement placement, String reason) {
            log.debug("skipped {} at {}: {}", placement.block(), placement.pos(), reason);
            // judged by the inventory, not by the reason text: with the item in hand a restock cannot help
            if (phase != Phase.BUILD || tile == null) return;
            if (bot.inventory().count(placement.itemId()) > 0) {
                if (deferred.add(placement.pos())) ctx.deferred++;
            } else {
                // the lane ran short of this item: the cell waits for the next load (counted for the round's log)
                ctx.cellsWithoutItems++;
                ctx.lanesShort.add(ctx.laneIdx);
            }
        }
    };

    public BuildEngine(MapArtConfig config, JobStore store, BotActions bot, BuildPlanner planner,
                       RestockPlanner restockPlanner, BlockCatalog catalog, Renderer renderer, ColorResolver colors,
                       Notifier notifier, Clock clock, Executor executor, ModuleSwitch modules) {
        this(config, store, bot, planner, restockPlanner, catalog, renderer, colors, notifier, clock, executor, modules,
            LoggerFactory.getLogger("MapArt/engine"));
    }

    /** One bot: {@link LocalCoordination} on {@code store}. */
    public BuildEngine(MapArtConfig config, JobStore store, BotActions bot, BuildPlanner planner,
                       RestockPlanner restockPlanner, BlockCatalog catalog, Renderer renderer, ColorResolver colors,
                       Notifier notifier, Clock clock, Executor executor, ModuleSwitch modules, Logger log) {
        this(config, store, bot, planner, restockPlanner, catalog, renderer, colors, notifier, clock, executor, modules,
            log, new LocalCoordination(store));
    }

    /**
     * {@code coord}: where the jobs come from and who arbitrates the shared resources (tests). The store
     * keeps the engine's own files (checkpoint, job state, tiles, blobs) either way.
     */
    public BuildEngine(MapArtConfig config, JobStore store, BotActions bot, BuildPlanner planner,
                       RestockPlanner restockPlanner, BlockCatalog catalog, Renderer renderer, ColorResolver colors,
                       Notifier notifier, Clock clock, Executor executor, ModuleSwitch modules, Logger log,
                       Coordination coord) {
        this.coord = coord;
        this.sharedStock = new SharedStock(coord);
        this.menuGate = new ClaimMenuGate(coord);
        bot.menuGate(menuGate);
        this.config = config;
        this.store = store;
        this.bot = bot;
        this.planner = planner;
        this.restockPlanner = restockPlanner;
        this.catalog = catalog;
        this.renderer = renderer;
        this.colors = colors;
        this.notifier = notifier;
        this.clock = clock;
        this.executor = executor;
        this.modules = modules;
        this.log = log;
        this.writer = new SerialWriter(executor, log);
        this.phaseSince = clock.millis();
        if (config.paused && config.pauseReason != null) {
            pauseReason = config.pauseReason;
            safetyLocked = pauseReason.startsWith("SAFETY STOP");
            if (safetyLocked) firstStop = pauseReason;
        }
        publishStatus();
    }

    // =====================================================================================
    // EngineControl (any thread)
    // =====================================================================================

    @Override
    public void pause(String reason) {
        String r = reason == null || reason.isBlank() ? "paused" : reason;
        persistPause(r, () -> {
            log.info("paused: {}", r);
            notifier.info("Paused", r + currentLine());
        });
    }

    /**
     * A pause (any thread, never blocks): persisted now, so the tick gate holds from the next tick on; then, drained on
     * the tick thread before anything is ticked, the task and any setback escape are cancelled, the modules given back
     * and {@code announce} runs (it may read the tick-thread state: job, phase). While safety-locked the safety stop
     * stays the reason shown and {@code r} is listed after it. Only {@link #resume()} carries on.
     */
    private void persistPause(String r, Runnable announce) {
        String shown = r;
        synchronized (stopLock) {
            if (safetyLocked) {
                // the safety stop stays the reason shown; the pause is listed after it
                laterStops = append(laterStops, "paused: " + r);
                shown = safetyReason(firstStop, laterStops);
            }
        }
        pauseReason = shown;
        config.paused = true;
        config.pauseReason = shown;
        saveConfig();
        commands.add(() -> {
            dropEscape("paused");
            cancelTask();
            ctx = new Ctx();
            restoreModules();
            announce.run();
            publishStatus();
        });
        publishPauseFlag();
    }

    /** "\nCurrent: #j0042 Name [0,0] (BUILD)" for Discord lines, or "" without a job. Tick thread. */
    private String currentLine() {
        return job != null ? "\nCurrent: " + label() + " (" + phase + ")" : "";
    }

    /**
     * Safety halt (teleport guard): stop every bot action now, persist a pause, alert once. Only {@link #resume()}
     * restarts the engine. Takes the engine lock: never call it on the Netty channel thread, where it deadlocks with a
     * tick waiting for a send (the adapter runs the teleport guard's halts on the tick thread, see {@link TickThread}).
     */
    public synchronized void emergencyStop(String reason) {
        try {
            haltHook.run();
        } catch (RuntimeException e) {
            log.error("halt hook failed", e);
        }
        dropEscape("SAFETY STOP");
        cancelTask();
        ctx = new Ctx();
        rescueHeld = true; // the starving rescue does not walk the bot after a stop in this connection
        boolean first;
        synchronized (stopLock) {
            first = !safetyLocked;
            safetyLocked = true;
            if (first) {
                firstStop = "SAFETY STOP: " + reason;
                laterStops = List.of();
            } else {
                // e.g. the bot died, then the respawn and its teleport stop again: the first reason is what happened
                laterStops = append(laterStops, reason);
            }
            pauseReason = safetyReason(firstStop, laterStops);
        }
        config.paused = true;
        config.pauseReason = pauseReason;
        saveConfig();
        restoreModules();
        if (first) log.error("SAFETY STOP: {}", reason);
        else log.error("SAFETY STOP again (the first reason stays in the status): {}", reason);
        if (first) {
            alertHere("SAFETY STOP", reason
                + "\nAll bot actions halted. Check the bot in game, then `" + config.discord.commandPrefix + " resume`.");
        }
        publishStatus();
    }

    /** Later safety stops listed in the pause reason; more are counted, not listed. */
    static final int LATER_STOPS_SHOWN = 3;

    /**
     * Pause reason while safety-locked: the first stop, then the later ones as "(+2 later: respawn ...; server teleported
     * ...)". At most {@link #LATER_STOPS_SHOWN} are listed (the oldest), each cut to 120 characters.
     */
    static String safetyReason(String first, List<String> later) {
        if (later.isEmpty()) return first;
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < later.size() && i < LATER_STOPS_SHOWN; i++) {
            String s = later.get(i);
            shown.add(s.length() > 120 ? s.substring(0, 119) + "…" : s);
        }
        return first + " (+" + later.size() + " later: " + String.join("; ", shown)
            + (later.size() > shown.size() ? "; …" : "") + ")";
    }

    private static List<String> append(List<String> l, String s) {
        List<String> out = new ArrayList<>(l);
        out.add(s);
        return List.copyOf(out);
    }

    /** Status note prefix while the bot eats, in any phase (the Discord presence shows "Eating"). */
    public static final String EATING_NOTE = "eating";
    /** Status note prefix while a tool is mended with XP (the Discord presence shows "Mending tools"). */
    public static final String MENDING_NOTE = "mending tools";

    /**
     * Status note while a setback back-off stands still, and its prefix while the escape after it runs
     * ({@code !map status}; the Discord presence looks for it).
     */
    public static final String BACKOFF_NOTE = "backing off: server setbacks";
    /** At most one Discord line about back-offs per this interval (each back-off is logged). */
    private static final long BACKOFF_NOTICE_INTERVAL_MS = 60_000L;

    /**
     * Setback back-off (teleport guard: a burst of small server setbacks). Runs the halt hook now; the next
     * tick cancels the current task before it ticks anything, and the engine then stands still for {@code millis}: no
     * movement, clicks or inventory actions. Afterwards it re-enters the current phase with a fresh step, as after a
     * cancel. Not a pause: nothing is persisted and no resume is needed.
     *
     * <p>Any thread, never blocks. Packet handlers run on the Netty channel thread; waiting there for the engine lock
     * would deadlock against a tick that holds it while waiting for a send on that same thread
     * ({@code sendAwait} of the hotbar select).
     *
     * <p>No escape follows this form: the teleport guard's back-offs come through
     * {@link #backoff(String, long, Pos, int)}.
     */
    public void backoff(String reason, long millis) {
        backoff(reason, millis, null, 0);
    }

    /**
     * {@link #backoff(String, long)} for a burst at {@code at} (the bot's feet cell, null if unknown) on rung
     * {@code rung} (1-based; 0 = no escape) of the escape ladder. The same task
     * restarting at the same spot was set back again at once (2026-10-11), so once the back-off is over, and before the
     * phase carries on with a fresh step, the engine runs {@link BotActions#escape}: the bot steps away from {@code at}
     * and this tile's earlier burst cells ({@link #stuckCells}, which the lanes keep away from from now on too). The
     * escape is not a step attempt: a failure is logged and the phase carries on all the same (the next burst is the
     * next rung). A pause, a stop, a disconnect, a player taking control or a newer back-off drops it (the newer
     * back-off's escape replaces it). Any thread, never blocks, like the 2-argument form.
     */
    public void backoff(String reason, long millis, @Nullable Pos at, int rung) {
        long now = clock.millis();
        try {
            haltHook.run();
        } catch (RuntimeException e) {
            log.error("halt hook failed", e);
        }
        commands.add(() -> applyBackoff(reason, millis, now, at, rung));
    }

    /** Pause reason prefix of {@link #stuck}: needs a human (the Discord presence shows do-not-disturb). */
    public static final String STUCK_PREFIX = "stuck: ";

    /**
     * The setback escape ladder is used up: stop Baritone now (the halt hook),
     * then pause with an alert, as {@link #pause} does: persisted at once, the next tick cancels the task before it
     * ticks anything, and only {@link #resume()} carries on. Not a SAFETY STOP: nothing moved the bot far, so the
     * safety lock stays off and the starving rescue may still fetch food. Already paused (any pause or SAFETY STOP):
     * logged, no second alert, the reason shown stays. Any thread; never blocks or takes the engine lock (the adapter
     * calls it on the tick thread, {@link TickThread#guardSink}).
     */
    public void stuck(String reason) {
        try {
            haltHook.run();
        } catch (RuntimeException e) {
            log.error("halt hook failed", e);
        }
        if (config.paused) {
            log.warn("stuck while already paused (no new alert): {}", reason);
            return;
        }
        persistPause(STUCK_PREFIX + reason, () -> {
            int tried = escapesTried();
            escapeStarts.clear(); // the ladder starts over after the resume
            log.error("STUCK, paused: {} ({} escapes tried)", reason, tried);
            alertHere("Stuck: paused", reason + "\nThe bot tried " + tried
                + (tried == 1 ? " escape" : " escapes") + " and is paused. Check it in game, then `"
                + config.discord.commandPrefix + " resume`.");
        });
    }

    /** Tick thread (drained before anything is ticked): cancel the task, start standing still, plan the escape. */
    private void applyBackoff(String reason, long millis, long at, @Nullable Pos cell, int rung) {
        dropEscape("a newer back-off"); // pending or running: this back-off's escape replaces it
        cancelTask();
        ctx = new Ctx();
        backoffUntil = Math.max(backoffUntil, at + Math.max(0, millis));
        if (!backingOff) {
            backingOff = true;
            noteBeforeBackoff = note;
        }
        boolean acting = !config.paused && phase != Phase.BLOCKED; // else it stands still anyway: no note, no line
        if (acting) note = BACKOFF_NOTE;
        if (cell != null && tile != null && stuckCells.add(cell))
            log.info("setback burst cell {} kept away from for the rest of this tile ({} cells)", cell.key(),
                stuckCells.size());
        boolean escape = acting && rung > 0;
        if (escape) {
            escapeRung = rung;
            escapeAt = cell;
        }
        long secs = Math.ceilDiv(Math.max(0, millis), 1000L);
        log.warn("backing off: standing still for {} s ({}): {}{}", secs, phase, reason, !escape ? ""
            : "; then escape rung " + rungText(rung) + " from " + (cell != null ? cell.key() : "where the bot stands"));
        long now = clock.millis();
        if (acting && (lastBackoffNotice < 0 || now - lastBackoffNotice >= BACKOFF_NOTICE_INTERVAL_MS)) {
            lastBackoffNotice = now;
            notifier.detail("Backing off", reason + ". Standing still for " + secs + " s, then "
                + (escape ? "stepping away (escape " + rungText(rung) + ")." : "carrying on.") + currentLine());
        }
        publishStatus();
    }

    /**
     * Repeated setbacks onto one spot (teleport guard, {@code safety.setbackResyncRepeats}): on the next tick the
     * current task is cancelled and {@link BotActions#resync}
     * runs at {@code at} (the spot's feet cell), an out-of-step task like the escape: the bot stops, resyncs its
     * movement state and re-requests the blocks around the spot from the server, then the phase carries on with a fresh
     * step, as after a back-off. Skipped (logged) while paused, BLOCKED, backing off, escaping (the escape resyncs too)
     * or already resyncing. Any thread, never blocks.
     */
    public void resync(String reason, Pos at) {
        commands.add(() -> applyResync(reason, at));
    }

    /** Tick thread (drained before anything is ticked). */
    private void applyResync(String reason, Pos at) {
        String skip = config.paused ? "paused" : safetyLocked ? "SAFETY STOP" : phase == Phase.BLOCKED ? "BLOCKED"
            : backingOff ? "a back-off stands still" : escapeRung > 0 ? "an escape is pending or runs (it resyncs too)"
            : resyncing ? "a resync runs" : null;
        if (skip != null) {
            log.info("resync at {} skipped ({}): {}", at.key(), skip, reason);
            return;
        }
        cancelTask();
        ctx = new Ctx();
        log.warn("resync at {} ({}): {}; re-requesting the blocks around it from the server, then {} carries on",
            at.key(), phase, reason, phase);
        notifier.detail("Resync", reason + ". Re-requesting the blocks around " + at.key()
            + " from the server, then carrying on." + currentLine());
        BotTask t = bot.resync(at);
        run(t, () -> resyncDone(t, null), r -> resyncDone(t, r));
        resyncing = true;
        publishStatus();
    }

    /** The resync task ended: logged either way, then the phase carries on (a failure is not a step failure). */
    private void resyncDone(BotTask t, @Nullable String failure) {
        resyncing = false;
        if (failure == null) log.info("{} done; carrying on in {}", t.name(), phase);
        else log.warn("{} failed: {}; carrying on in {}", t.name(), failure, phase);
    }

    /** "2 of 3" (out of {@code safety.setbackBackoffMaxPerWindow}), or "2" when the ladder never ends (0). */
    private String rungText(int rung) {
        int max = config.safety.setbackBackoffMaxPerWindow;
        return max > 0 ? rung + " of " + max : String.valueOf(rung);
    }

    /**
     * Tick thread, right after the back-off is over (not paused, not BLOCKED): the escape of {@link #escapeRung}, an
     * out-of-step task like the vitals' ones. Its end ({@link #escapeDone}) lets the phase carry on with a fresh step.
     * Not in WAIT_CLEAR: there the bot stands where it waits for the clear on purpose (soul sand: the centre).
     */
    private void startEscape() {
        int rung = escapeRung;
        if (phase == Phase.WAIT_CLEAR) {
            dropEscape("the bot stays where it waits for the clear (" + phase + ")");
            return;
        }
        Pos at = escapeAt != null ? escapeAt : bot.world().playerBlock();
        Set<Pos> avoid = new LinkedHashSet<>(stuckCells);
        avoid.add(at);
        long now = clock.millis();
        escapeStarts.addLast(now);
        escapesTried();
        log.warn("escape rung {} at {}: stepping away from where the server kept setting the bot back (keeping away"
            + " from {}), then {} carries on", rungText(rung), at.key(), keys(avoid), phase);
        note = BACKOFF_NOTE + "; stepping away (escape " + rungText(rung) + ")";
        BotTask t = bot.escape(new EscapeRequest(at.x() + 0.5, at.y(), at.z() + 0.5, rung, avoid));
        run(t, () -> escapeDone(rung, t, null), r -> escapeDone(rung, t, r));
        escaping = true;
    }

    /** The escape task ended: logged either way, then the phase carries on (a failure is not a step failure). */
    private void escapeDone(int rung, BotTask t, @Nullable String failure) {
        clearEscape();
        if (failure == null) log.info("escape rung {} done: {}; carrying on in {}", rungText(rung), t.name(), phase);
        else log.warn("escape rung {} failed: {}; carrying on in {}", rungText(rung), failure, phase);
    }

    /**
     * Drops the pending or running escape, logged with why; call it before {@link #cancelTask}, which cancels a running
     * one's task (and otherwise drops it as "cut short").
     */
    private void dropEscape(String why) {
        if (escapeRung <= 0) return;
        log.info("escape rung {} dropped: {}", rungText(escapeRung), why);
        clearEscape();
    }

    private void clearEscape() {
        escapeRung = 0;
        escapeAt = null;
        escaping = false;
        if (!backingOff && note.startsWith(BACKOFF_NOTE)) note = noteBeforeBackoff;
    }

    /**
     * Fall rescue ({@code build.fallRescue}): every working tick {@link FallWatch}
     * looks at the bot's feet. A fall seen cancels the task and runs {@link BotActions#rescueFall} (at most
     * {@link #FALL_RESCUE_TICKS} a try; in WAIT_CLEAR it first holds still until {@code build.fallRescueClearHoldSeconds}
     * after the press); once out, the phase carries on. A try that fails is tried again after
     * {@link #FALL_RETRY_SECONDS} (one "Bot fell" alert at the first failure); BLOCKED with a "Bot fell" alert only after
     * {@link #MAX_FALL_TRIES} failed tries in a row that got the feet no higher. A fall more than {@link #MAX_FALL_RESCUES}
     * times within {@link #FALL_RESCUE_WINDOW_MS} is alerted once per window and rescued all the same. Never
     * disconnects.
     */
    private void checkFall() {
        if (fallTask != null) {
            if (task == fallTask && !fallTask.isFinished()) return; // the rescue (or the wait before a retry) runs
            fallTask = null; // replaced (a fight, a stop): watch again
        }
        if (!config.build.fallRescue) return;
        WorldView w = bot.world();
        Pos feet = w.playerBlock();
        // the water test looks 0.2 above the feet: on a soul sand floor (feet y172.875) the block cell is the soul sand's,
        // and a bot standing on the edge over a channel would be "in" the channel's top water cell (local run 2026-10-13)
        Pos wet = new Pos(feet.x(), (int) Math.floor(w.playerY() + FallWatch.WET_ABOVE), feet.z());
        FallWatch.Fall f = fallWatch.tick(config.site.platforms, w.playerX(), w.playerY(), w.playerZ(),
            w.hasFluid(wet), w.vehicle() != null);
        String fallWhy;
        int floorY;
        if (f != null) {
            fallWhy = f.why();
            floorY = f.platform() != null ? f.platform().artY : FallRescue.ANY_LEVEL;
        } else {
            // a walk failed boxed in after a drop it cannot walk back up, off the tiles FallWatch watches (live
            // 2026-10-27: 4 blocks into a dry 1x1 shaft by the sorter pickup stand, 7 cells north of the tile): the same
            // rescue, back up to the level it dropped from
            BoxedInDrop d = bot.takeBoxedInDrop();
            if (d == null) return;
            fallWhy = d.why();
            floorY = d.from().y();
        }
        fallWatch.reset();
        long now = clock.millis();
        while (!fallStarts.isEmpty() && fallStarts.peekFirst() < now - FALL_RESCUE_WINDOW_MS) fallStarts.pollFirst();
        String at = feet.key();
        if (fallStarts.size() >= MAX_FALL_RESCUES && now - fallRepeatAlerted >= FALL_RESCUE_WINDOW_MS) {
            fallRepeatAlerted = now;
            String body = "The bot fell " + (fallStarts.size() + 1) + " times within " + FALL_RESCUE_WINDOW_MS / 60_000
                + " min, now at " + at + " (" + fallWhy + "). It gets itself out each time; whatever keeps dropping it there"
                + " wants a look.";
            log.warn("Bot keeps falling: {}", body);
            alertHere("Bot keeps falling", body);
        }
        fallStarts.addLast(now);
        fallFailures = 0;
        fallStuck = 0;
        fallBestY = feet.y();
        fallHolding = false;
        dropEscape("the bot fell");
        cancelTask();
        log.warn("Bot fell: {} at {}; getting it out onto dry ground{} ({} s at most a try), then {} carries on", fallWhy,
            at, floorY == FallRescue.ANY_LEVEL ? "" : " at y" + floorY, FALL_RESCUE_TICKS / 20, phase);
        rescueFall(at, fallWhy, floorY, 0);
    }

    /**
     * One try of the fall rescue from where the bot is now, after holding its place {@code waitTicks} (the wait before
     * another try: in a channel the rescue keeps the bot in its cell against the current, which a plain wait would let
     * carry it to the channel's end; local run 2026-10-23).
     */
    private void rescueFall(String at, String why, int floorY, int waitTicks) {
        Pos feet = bot.world().playerBlock();
        int hold = Math.max(fallHoldTicks(), waitTicks);
        note = "the bot fell at " + at + ": getting it out" + (fallFailures > 0 ? " (try " + (fallFailures + 1) + ")" : "");
        BotTask t = bot.rescueFall(new FallRescue(feet, floorY, FALL_RESCUE_TICKS, why, hold, fallOwn,
            left -> fallLeftBlocks(at, left), e -> {
                fallOwn = e.own();
                fallStandY = e.standY();
            }));
        fallTask = t;
        run(t, () -> {
            fallTask = null;
            fallOwn = Map.of();
            fallHolding = false;
            log.info("fall rescue done: the bot is out at {}{}; carrying on in {}", bot.world().playerBlock().key(),
                fallFailures > 0 ? " after " + (fallFailures + 1) + " tries" : "", phase);
            fallFailures = 0;
            fallStuck = 0;
            note = "";
            wakeTick = tick;
        }, r -> {
            fallTask = null;
            fallTryFailed(at, why, floorY, r);
        });
    }

    /**
     * WAIT_CLEAR: ticks the rescue holds still before it climbs, until {@code build.fallRescueClearHoldSeconds} after
     * the press (the phase starts with it); 0 otherwise.
     */
    private int fallHoldTicks() {
        if (phase != Phase.WAIT_CLEAR || config.build.fallRescueClearHoldSeconds <= 0) return 0;
        long left = config.build.fallRescueClearHoldSeconds * 1000L - (clock.millis() - phaseSince);
        return left <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, left / 50);
    }

    /**
     * A try that did not get the bot out: another one after a wait, or BLOCKED after tries without progress. In water
     * not BLOCKED: there a bot left to itself drifts with the current to the channel's end, and at the west channel's
     * north end down the drain to the chunk loaders (local run 2026-10-23). It holds its place and tries on every
     * 240 s, with one alert; once the owner got it out it carries on by itself.
     */
    private void fallTryFailed(String at, String why, int floorY, String failure) {
        // progress: higher than ever standing on something (a bot bobbing in a channel's water is none)
        int y = fallStandY != FallRescue.ANY_LEVEL ? fallStandY : bot.world().playerBlock().y();
        fallStandY = FallRescue.ANY_LEVEL;
        fallFailures++;
        if (y > fallBestY) {
            fallBestY = y;
            fallStuck = 0;
        } else {
            fallStuck++;
        }
        if (fallStuck >= MAX_FALL_TRIES && !fallInWater()) {
            fell(at, why, failure + " (" + fallFailures + " tries, the last " + fallStuck + " no higher)");
            return;
        }
        int wait = FALL_RETRY_SECONDS[Math.min(fallFailures - 1, FALL_RETRY_SECONDS.length - 1)];
        if (fallStuck >= MAX_FALL_TRIES) wait = FALL_RETRY_SECONDS[FALL_RETRY_SECONDS.length - 1];
        log.warn("fall rescue try {} failed: {}; trying again in {} s", fallFailures, failure, wait);
        if (fallStuck >= MAX_FALL_TRIES && !fallHolding) {
            fallHolding = true;
            Map<Pos, String> own = fallOwn;
            alertHere("Bot fell", "The bot fell at " + at + " ("
                + why + ") and could not get out: " + failure + " (" + fallFailures + " tries, the last " + fallStuck
                + " no higher).\nIt is in water: it holds its place there (left to itself the current carries it to the"
                + " channel's end) and tries again every " + wait + " s. No BLOCKED, no disconnect. Get it out by hand;"
                + " once out it carries on by itself." + (own.isEmpty() ? "" : "\nBlocks the rescue placed still stand at "
                + String.join("; ", own.entrySet().stream().map(e -> e.getKey().key() + " " + e.getValue()).toList())
                + "."));
        }
        if (fallFailures == 1) {
            alertHere("Bot fell", "The bot fell at " + at + " ("
                + why + ") and did not get out yet: " + failure + ".\nIt tries again in " + wait + " s (longer waits after"
                + " that). After " + MAX_FALL_TRIES + " tries in a row that get it no higher it stops (BLOCKED), or, in"
                + " water, holds its place and tries on. No disconnect.");
        }
        rescueFall(at, why, floorY, wait * 20);
        note = "the bot fell at " + at + ": " + (fallHolding ? "could not get out; holding its place in the water, " : "")
            + "rescue try " + (fallFailures + 1) + " in " + wait + " s";
    }

    /**
     * The bot is in water or lava: the feet's cell, the one 0.2 above them (as {@link #checkFall} looks) or the one
     * below them (a try may end mid-jump over 1-deep water: local run 2026-10-23, BLOCKED at feet y171 over the channel).
     */
    private boolean fallInWater() {
        WorldView w = bot.world();
        Pos feet = w.playerBlock();
        int y = (int) Math.floor(w.playerY());
        return w.hasFluid(new Pos(feet.x(), (int) Math.floor(w.playerY() + FallWatch.WET_ABOVE), feet.z()))
            || w.hasFluid(new Pos(feet.x(), y, feet.z())) || w.hasFluid(new Pos(feet.x(), y - 1, feet.z()));
    }

    /** The rescue got out but left blocks of its own in the world: one alert (they may hold up a channel's items). */
    private void fallLeftBlocks(String at, String blocks) {
        String body = "After the fall at " + at + " the rescue climbed out but could not break its own blocks again: "
            + blocks + ". They may hold up the channel's water and items; break them by hand.";
        log.warn("Fall rescue left blocks: {}", body);
        alertHere("Fall rescue left blocks", body);
    }

    /** A fall the rescue could not undo: BLOCKED with the one "Bot fell" alert (the plugin never disconnects). */
    private void fell(String at, String why, String failure) {
        String reason = "the bot fell at " + at + " (" + why + ") and did not get out: " + failure;
        Map<Pos, String> own = fallOwn;
        String left = own.isEmpty() ? "" : "\nBlocks the rescue placed still stand at " + String.join("; ", own.entrySet()
            .stream().map(e -> e.getKey().key() + " " + e.getValue()).toList()) + ": break them too (they hold up the"
            + " channel's water and items).";
        block(reason, "Bot fell", (job != null ? label() + " (" + phase + ")\n" : "") + "The bot fell at " + at + " ("
            + why + ") and could not get out: " + failure + ".\nIt stays there (no disconnect). Get it out, then `"
            + config.discord.commandPrefix + " resume`." + left);
    }

    /** Escapes started within the escalation window (older ones are forgotten). */
    private int escapesTried() {
        long cutoff = clock.millis() - TeleportGuard.BACKOFF_ESCALATION_WINDOW_MS;
        while (!escapeStarts.isEmpty() && escapeStarts.peekFirst() < cutoff) escapeStarts.pollFirst();
        return escapeStarts.size();
    }

    /** "x,y,z; x,y,z" for log lines. */
    private static String keys(Collection<Pos> cells) {
        List<String> out = new ArrayList<>(cells.size());
        for (Pos p : cells) out.add(p.key());
        return String.join("; ", out);
    }

    // ---- keep-alive ----

    /**
     * End of every tick, working or not (paused, BLOCKED, a back-off): a task, running or parked, counts as the bot
     * acting; without one, once the bot has been idle for {@code build.keepAliveSeconds} (0 = off), starts the keep-alive
     * ({@link BotActions#keepAlive}: a head turn, then an arm swing, standing still), ticked from the next tick by
     * {@link #tickKeepAlive}. Never during a back-off or a pending escape, a SAFETY STOP, while the bot layer is busy
     * (a menu open, Baritone walking: {@link BotActions#isBusy}), or while ZenithProxy's AntiAFK is on (the engine gave
     * the modules back and it is enabled: it keeps the account active itself). A player in control stops bot ticks, so
     * nothing runs then; when the ticks start again the idle time starts afresh ({@link #onBotTickStarting}).
     */
    private void keepAliveWhenIdle() {
        long now = clock.millis();
        if (task != null || parkedTask != null) {
            keepAlive.acted(now);
            return;
        }
        if (keepAliveTask != null || !keepAlive.due(now, config.build.keepAliveSeconds)) return;
        if (safetyLocked || escapeRung > 0 || (backingOff && now < backoffUntil)) return;
        if (modules.isEnabled(ANTI_AFK) || bot.isBusy()) return;
        keepAliveTurn = keepAlive.turn();
        keepAliveIdle = keepAlive.idleMillis(now);
        keepAliveTask = bot.keepAlive(keepAliveTurn);
    }

    /**
     * Ticks the keep-alive in flight: true while it runs (it has this tick to itself). Its end is logged: INFO for the
     * first one in a wait state, DEBUG for the others and for one that did not complete (tried again
     * {@code KEEP_ALIVE_RETRY_MS} later). Never a Discord line.
     */
    private boolean tickKeepAlive() {
        BotTask t = keepAliveTask;
        TaskStatus s = t.tick();
        if (s == TaskStatus.RUNNING) return true;
        keepAliveTask = null;
        long now = clock.millis();
        if (s != TaskStatus.SUCCESS) {
            keepAlive.failed(now);
            log.debug("keep-alive not done: {}; trying again in {} s", t.failure(), KEEP_ALIVE_RETRY_MS / 1000);
            return false;
        }
        keepAlive.sent(now);
        String where = keepAliveState();
        if (keepAlive.firstIn(keepAliveKey())) {
            log.info("keep-alive: idle {} s {}; swinging every {} s", keepAliveIdle / 1000, where,
                config.build.keepAliveSeconds);
        } else {
            log.debug("keep-alive: swung and turned {} degrees (idle {} s {})", Math.round(keepAliveTurn),
                keepAliveIdle / 1000, where);
        }
        return false;
    }

    /** "in FILL_MAP (Waiting: platform above in use (80 pixels covered))", "while paused (reason)", for the log. */
    private String keepAliveState() {
        if (config.paused) return "while paused" + (pauseReason.isBlank() ? "" : " (" + pauseReason + ")");
        String n = vitalsNote.isEmpty() ? note : vitalsNote;
        return "in " + phase + (phase == Phase.BLOCKED && blockedFrom != null ? " from " + blockedFrom : "")
            + (n == null || n.isBlank() ? "" : " (" + n + ")");
    }

    /** The wait state, for the INFO line: a phase entered anew (or a new pause) is a new one; eating in it is not. */
    private String keepAliveKey() {
        return (config.paused ? "paused " + pauseReason + " " : "") + phase + " " + phaseSince;
    }

    /** Tick gate: true while a back-off stands still; once it is over the phase carries on with a fresh step. */
    private boolean backoffHolds() {
        if (!backingOff) return false;
        if (clock.millis() < backoffUntil) {
            if (task != null && !stoppedDefence) cancelTask(); // self-defence goes on (defendWhileStopped)
            return true;
        }
        backingOff = false;
        if (BACKOFF_NOTE.equals(note)) note = noteBeforeBackoff;
        log.info("back-off over; carrying on in {}", phase);
        return false;
    }

    /** Persists the plugin config (the adapter passes ZenithProxy's async config save). */
    public void setConfigSaver(Runnable saver) {
        configSaver = saver == null ? () -> { } : saver;
    }

    private void saveConfig() {
        try {
            configSaver.run();
        } catch (RuntimeException e) {
            log.warn("config save failed: {}", e.toString());
        }
    }

    /**
     * Extra halt action for {@link #emergencyStop}, {@link #backoff} and {@link #stuck}, run in the call. The adapter
     * stops Baritone here, on the tick thread ({@link TickThread#hook}): Baritone is not thread-safe.
     */
    public void setHaltHook(Runnable hook) {
        haltHook = hook == null ? () -> { } : hook;
    }

    public boolean safetyLocked() {
        return safetyLocked;
    }

    @Override
    public void resume() {
        boolean wasPaused = config.paused;
        config.paused = false;
        config.pauseReason = "";
        pauseReason = "";
        synchronized (stopLock) {
            safetyLocked = false;
            firstStop = "";
            laterStops = List.of();
        }
        saveConfig();
        commands.add(() -> {
            resetToolCare();
            rescueHeld = false;
            spareFetchTried = false; // a spare put into the cart chests meanwhile is fetched
            endFoodEpisode(); // resume retries all of it, the food chests included
            if (counterEpisode || counterOwed > 0) {
                log.info("map counter: jam state cleared by resume; {} owed counts kept for the next stored map",
                    counterOwed);
            }
            counterEpisode = false; // the next COUNT takes the counter as it finds it (a stuck piston still holds it)
            counterGood = null;
            counterOddVisits = 0;
            counterAdopted = false;
            if (!restored) {
                resumeBeforeRestore = true; // a BLOCKED checkpoint is not restored as blocked
                return;
            }
            boolean wasBlocked = phase == Phase.BLOCKED;
            if (wasBlocked) {
                Phase back = blockedFrom != null ? blockedFrom : (job != null ? Phase.INSPECT : Phase.IDLE);
                blockedFrom = null;
                clearAttempts = 0;
                repairPasses = 0;
                fillAttempts = 0;
                foreignInspections = 0;
                noProgressRounds = 0;
                go(back);
            } else {
                failures = 0;
            }
            wakeTick = tick;
            if (wasPaused || wasBlocked) {
                log.info("resumed in {}", phase);
                notifier.info("Resumed", job != null ? label() + " (" + phase + ")" : "Idle; checking the queue.");
            }
            publishStatus();
        });
        publishPauseFlag();
    }

    @Override
    public EngineStatus status() {
        return status;
    }

    @Override
    public void requestStockScan() {
        commands.add(() -> {
            forceScan = true;
            scanFailures.clear();
            waitingCheckedAt.clear();
            if (phase == Phase.IDLE || waitingForMaterials()) wakeTick = tick;
        });
    }

    @Override
    public void queueChanged() {
        mapNeedsCache.clear(); // a re-substitution may have changed tiles: read them again
        commands.add(() -> {
            tileNeeds.clear(); // a re-substitution may have changed tiles with nothing placed: read their needs again
            if (phase == Phase.IDLE) wakeTick = tick;
            if (restored && job != null && !aborting && jobCancelled(job)) abortTile();
        });
    }

    // =====================================================================================
    // Adapter entry points
    // =====================================================================================

    /** ClientBotTick.Starting: (re)connected or a controlling player left. Re-enter the phase with a fresh task. */
    public synchronized void onBotTickStarting() {
        ticking = true;
        dropsDue = true; // the first decision (after a fight or a health hold): what a disconnect dropped
        // before the commands: a hit queued while a player had control (or before it took it) is no hit now
        botTicks = true;
        tickStretch++;
        endHitEpisode();
        drainCommands();
        inGameSince = clock.millis(); // the map counter waits site.mapCounterLoginDelaySeconds from here
        counterChunkSince = -1;
        cancelTask();
        ctx = new Ctx();
        failures = 0;
        keepAlive.acted(clock.millis()); // a reconnect, or a player in control until now: the idle time starts here
        if (restart.backAfterRestart() && restartHolding) {
            restartHolding = false;
            log.info("server restart over: back in game; the carts, NUKEs and clears run as usual again");
        }
        endFoodEpisode(); // the starving rescue looks again (a restocked food chest)
        fallWatch.reset();
        fallTask = null;
        rescueReadyTick = tick + START_DELAY_TICKS;
        lanesCache = null;
        needsCache = null;
        buildLanes = null;
        wakeTick = tick + START_DELAY_TICKS;
        if (config.build.stockWaitWakeOnChange) {
            // a player handing control back may have brought materials (live 2026-10-13, ellen [3,4]: cyan put into the
            // inventory waited 7 min for the next look): the waiting tile looks again at once, held jobs are due again
            waitingCheckedAt.clear();
            if (waitingForMaterials())
                materialsWake = "bot ticks started again (a player handed control back, or a reconnect)";
        }
        log.info("bot ticks starting ({}{})", phase, job != null ? ", " + label() : "");
        publishStatus();
    }

    /** ClientBotTick: one engine step. */
    public synchronized void onBotTick() {
        if (!ticking) onBotTickStarting();
        tick++;
        drainCommands();
        trackCounterChunk();
        trackSimulationDistance();
        trackLostCart();
        if (!restored) {
            restoreTick();
            publishStatus();
            return;
        }
        tickCoordination();
        updateModuleSuppression();
        trackFoodEpisode();
        updateRestartHold();
        updateSprintGate();
        if (keepAliveTask != null && tickKeepAlive()) {
            publishStatus();
            return; // the keep-alive's turn is in flight: nothing else this tick
        }
        if (config.paused || phase == Phase.BLOCKED || backoffHolds()) {
            // not working; the exceptions are self-defence (a fight, or a retreat, while a listed mob hurts the bot),
            // the starving rescue (food only: no phase step, no building) and the keep-alive (a swing and a head turn,
            // standing still)
            if (!defendWhileStopped()) tickRescue();
            keepAliveWhenIdle();
            updateModuleSuppression();
            publishStatus();
            return;
        }
        checkVitals();
        checkOffhand();
        checkCombat();
        checkRestartTask();
        checkFall();
        checkGear(); // the durability guard: a worn piece at the floor or due stops the task (docs "Durability guard")
        watchRocketSign();
        if (task != null) {
            keepAlive.acted(clock.millis());
            TaskStatus s = task.tick();
            if (s == TaskStatus.RUNNING && !(parkAccepted && askedToPark == task && task.parked())) {
                maybeReportProgress();
                publishStatus();
                return;
            }
            if (s == TaskStatus.RUNNING) parkTask();
            else finishTask(s);
        }
        // the back-off is over: the setback escape before the phase carries on
        if (task == null && escapeRung > 0 && !escaping) startEscape();
        if (task == null && parkedTask == null && tick < wakeTick) tendGear(GearMode.ASLEEP); // the guard in waits
        int steps = 0;
        while (task == null && tick >= wakeTick && !config.paused && phase != Phase.BLOCKED
            && steps++ < MAX_STEPS_PER_TICK) {
            acted = false;
            try {
                if (parkedTask != null && restartStops()) dropParkedForRestart();
                if (parkedTask != null) {
                    // nothing but eating, fighting, standing still and the gear until the parked task carries on
                    if (!tendVitals() && !fight() && !tendGear(GearMode.PARKED)) resumeParked();
                } else if (!recoverDisconnectDrops() && !tendVitals() && !leaveRaisedStand() && !fight()
                    && !careForTools()) {
                    step();
                }
            } catch (RuntimeException e) {
                log.error("engine step failed in {}", phase, e);
                stepFailed("internal error: " + e);
            }
            if (!acted) {
                log.debug("no decision in {}; polling", phase);
                sleep(POLL_TICKS);
            }
        }
        keepAliveWhenIdle();
        updateModuleSuppression();
        maybeReportProgress();
        publishStatus();
    }

    /** ClientBotTick.Stopped: a player took control or the client disconnected. */
    public synchronized void onBotTickStopped() {
        ticking = false;
        botTicks = false;
        tickStretch++;
        dropEscape("bot ticks stopped (a player took control, or a disconnect)");
        cancelTask();
        restoreModules();
        releaseAutoArmor();
        log.info("bot ticks stopped ({})", phase);
        countForTile(null); // the tile counters so far reach the job file before a restart
        saveJob(job);
        publishStatus();
    }

    /** ClientDisconnectEvent: forget per-session state (map ids, a stop's hold on the starving rescue). Thread-safe. */
    public void onDisconnect() {
        commands.add(() -> {
            dropEscape("disconnected");
            cancelTask();
            rescueHeld = false; // a stop holds the starving rescue for its own connection only
            mapId = null;
            lockedMapId = null;
            ownMaps.clear(); // the next connection has new map ids
            // the preload lap counts for its own connection only (the chunks it loaded are gone with the bot)
            preloadCleared = null;
            preloadedJob = null;
            keptLogged = null;
            crowdedAlerted = null;
            lanesCache = null;
            needsCache = null;
            buildLanes = null;
            // drained on the first tick of the next connection: the map counter's login wait starts over
            inGameSince = clock.millis();
            counterChunkSince = -1;
            restartConnectionLost(); // an announced restart happened: its hold ends when bot ticks start again
        });
    }

    /**
     * A system chat line (SystemChatEvent: never a player's chat or a whisper; also ZenithProxy's ServerRestartingEvent
     * from 2b2t's action bar). A restart warning ("Server restarting in 5 minutes...") sets the time the restart is due
     * ({@link RestartHold}); anything else is ignored. Thread-safe.
     */
    public void onServerMessage(String text) {
        OptionalLong in = RestartHold.parse(text);
        if (in.isEmpty()) return;
        long now = clock.millis();
        commands.add(() -> restart.warned(now, in.getAsLong(), text));
    }

    /** QueueStartEvent: 2b2t moved the bot to its queue (what a restart does to a bot that stays connected). Thread-safe. */
    public void onRequeued() {
        commands.add(this::restartConnectionLost);
    }

    /** The connection was lost or the bot went to the queue: with a restart announced, that restart, now on record. */
    private void restartConnectionLost() {
        ridePauseLive = false; // a paused ride's stand: the next connection has its chunks to load again
        if (restart.announced()) {
            lastServerRestartAt = clock.millis();
            log.info("server restart on record{}", config.site.restartEveryMinutes > 0 ? ": the next one is expected "
                + config.site.restartEveryMinutes / 60 + " h " + config.site.restartEveryMinutes % 60 + " min from now" : "");
            if (restored) saveCheckpoint();
        }
        restart.connectionLost();
    }

    /** Plugin unload / proxy shutdown: stop the task, give modules back, flush the checkpoint. */
    public synchronized void shutdown() {
        cancelTask();
        restoreModules();
        releaseAutoArmor();
        ticking = false;
        botTicks = false;
        tickStretch++;
        if (restored) saveCheckpoint();
        publishStatus();
    }

    // =====================================================================================
    // Tick machinery
    // =====================================================================================

    private void drainCommands() {
        Runnable r;
        while ((r = commands.poll()) != null) {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.error("engine command failed", e);
            }
        }
    }

    private void finishTask(TaskStatus s) {
        BotTask t = task;
        Runnable ok = taskOk;
        Consumer<String> fail = taskFail;
        task = null;
        taskOk = null;
        taskFail = null;
        rescueTask = false;
        stoppedDefence = false; // a handler may start the next one (fightFailed)
        taskAttempt = null; // it ended: the attempt counts
        taskAttemptCtx = null;
        try {
            if (s == TaskStatus.SUCCESS) {
                if (ok != null) ok.run();
            } else if (fail != null) {
                fail.accept(t.failure());
            } else {
                stepFailed(t.failure());
            }
        } catch (RuntimeException e) {
            log.error("task completion handler failed in {}", phase, e);
            stepFailed("internal error: " + e);
        }
    }

    private void run(BotTask t) {
        run(t, null, null);
    }

    private void run(BotTask t, Runnable onSuccess) {
        run(t, onSuccess, null);
    }

    /** Starts {@code t} (ticked from the next tick). {@code onFailure} null = retry with backoff, then BLOCKED. */
    private void run(BotTask t, Runnable onSuccess, Consumer<String> onFailure) {
        if (task != null) {
            log.warn("replacing unfinished task {}", task.name());
            giveBackAttempt(taskAttempt, taskAttemptCtx, task);
            task.cancel();
        }
        task = t;
        taskOk = onSuccess;
        taskFail = onFailure;
        // the attempt this step just counted for it (null for hooks such as eating or tool care, run outside a step)
        taskAttempt = ctx.lastAttempt;
        taskAttemptCtx = ctx;
        ctx.lastAttempt = null;
        vitalsTask = false;
        rescueTask = false;
        askedToPark = null;
        parkAccepted = false;
        acted = true;
        log.debug("[{}] task {}", phase, t.name());
    }

    /**
     * Cancels the running task and a parked one; the step attempts they were started for are given back. A running
     * escape ends with its task (a pending one stays: the back-off stands still before it).
     */
    private void cancelTask() {
        if (inCombat || combatWanted) endCombat("cut short");
        if (task != null) {
            giveBackAttempt(taskAttempt, taskAttemptCtx, task);
            task.cancel();
            log.debug("cancelled {}", task.name());
        }
        if (escaping) dropEscape("cut short");
        if (resyncing) {
            resyncing = false;
            log.info("resync cut short");
        }
        if (keepAliveTask != null) {
            keepAliveTask.cancel();
            keepAliveTask = null;
        }
        task = null;
        taskOk = null;
        taskFail = null;
        taskAttempt = null;
        taskAttemptCtx = null;
        if (parkedTask != null) {
            giveBackAttempt(parkedAttempt, parkedAttemptCtx, parkedTask);
            parkedTask.cancel();
            log.debug("cancelled parked {}", parkedTask.name());
        }
        parkedTask = null;
        parkedOk = null;
        parkedFail = null;
        parkedAttempt = null;
        parkedAttemptCtx = null;
        askedToPark = null;
        parkAccepted = false;
        vitalsTask = false;
        rescueTask = false;
        stoppedDefence = false;
        vitalsNote = "";
    }

    /**
     * A task cut short is not a try: its step attempt goes back to the context it was counted in, if that context still
     * runs (a fresh context, after a reconnect or a phase change, starts from zero anyway).
     */
    private void giveBackAttempt(String key, Ctx in, BotTask t) {
        if (key == null || in == null || in != ctx || t.isFinished()) return;
        in.giveBack(key);
        log.info("{} cut short: {} will be tried again", t.name(), key);
    }

    private void sleep(int ticks) {
        wakeTick = tick + Math.max(1, ticks);
        acted = true;
    }

    private void go(Phase next) {
        if (bandUnit() && tileEndPhase(next)) {
            // a lane band ends at VERIFY (finishBand): the map, store, nuke and clear are its tile's tail's
            log.error("{}: a lane band never runs {}; given back", label(), next);
            failBand("band_phase", "the band would have gone to " + next);
            return;
        }
        cancelTask();
        if (next != Phase.STOCK_CHECK) endMaterialsWait();
        countForTile(next);
        if (next != phase) log.info("phase {} -> {}{}", phase, next, job != null ? " (" + label() + ")" : "");
        // the walks of a restock, up to the next lane's start, are restock legs (build.sprintRestockLegs)
        bot.restockLegs(next == Phase.RESTOCK || (next == Phase.BUILD && phase == Phase.RESTOCK));
        phase = next;
        ridePhase = next == Phase.RIDE_BOARD || next == Phase.RIDE_PRELOAD || next == Phase.RIDE || next == Phase.RIDE_END
            || (next == Phase.RIDE_RESET && job != null && job.lostRunOpen); // the lap and the walkway of finishLostRun
        phaseSince = clock.millis();
        ctx = new Ctx();
        updateSprintGate();
        failures = 0;
        note = "";
        acted = true;
        saveCheckpoint();
        reportUnit(true);
    }

    /**
     * Plain walks may sprint ({@code build.sprintWalks}) in every phase but
     * while the clear runs: none in WAIT_CLEAR, and in CLEAR only on the first attempt before its press went out (the
     * walk to the button, off the art layer and the sweeps); BLOCKED from either (a self-defence chase): none.
     * {@code blockedFrom}: the phase BLOCKED came from, or null. Pure.
     */
    static boolean walksMaySprint(Phase phase, @Nullable Phase blockedFrom, boolean clearPressed, int clearAttempts) {
        if (phase == Phase.BLOCKED) return blockedFrom != Phase.CLEAR && blockedFrom != Phase.WAIT_CLEAR;
        if (phase == Phase.WAIT_CLEAR) return false;
        if (phase == Phase.RIDE) return false; // the walk off the rail field after a lost ride: one-wide lines over a drop
        return phase != Phase.CLEAR || (!clearPressed && clearAttempts <= 1);
    }

    /** Tells the bot whether plain walks may sprint now ({@link #walksMaySprint}); every tick and at each change. */
    private void updateSprintGate() {
        // the walk on foot to the booth for a lost ride's cart (finishLostRun): the walkway over the drop, never at a
        // sprint. Only that leg: without sprint the pathfinder takes a corner by a diagonal (cheaper by a hair than two
        // straight moves), the zone check refuses the diagonal past the lap corridor's corner and the plan around it
        // closes the corridor (live 2026-11-14: "the preload lap did not start")
        boolean lostRunWalk = phase == Phase.RIDE_RESET && job != null && job.lostRunOpen && !job.lostCartBack
            && ctx != null && ctx.lostStage == LR_WALK;
        bot.sprintWalks(!lostRunWalk && walksMaySprint(phase, blockedFrom, ctx != null && ctx.pressSent, clearAttempts));
        TilePlan artPlan = plan;
        boolean artWalk = phase != Phase.NUKE && phase != Phase.CLEAR && phase != Phase.WAIT_CLEAR;
        bot.artWalkContext(artWalk && artPlan != null ? artPlan::placement : null, deferred::remove);
    }

    /** A step could not complete: retry the phase from scratch with exponential backoff, BLOCKED after the limit. */
    private void stepFailed(String reason) {
        failures++;
        log.warn("[{}] {} (attempt {}/{})", phase, reason, failures, MAX_TASK_RETRIES + 1);
        if (failures > MAX_TASK_RETRIES) {
            block(phase + ": " + reason);
            return;
        }
        note = reason;
        ctx = new Ctx();
        long backoff = Math.min((long) BACKOFF_BASE_TICKS << Math.min(failures - 1, 10), BACKOFF_MAX_TICKS);
        sleep((int) backoff);
    }

    /** Needs a human: alert, keep everything as is, wait for resume(). */
    private void block(String reason) {
        block(reason, "Map art blocked", null);
    }

    /** {@link #block(String)} with the alert's own title and body (null: the usual "Phase …: reason" body). */
    private void block(String reason, String title, @Nullable String body) {
        cancelTask();
        bot.restockLegs(false);
        if (phase != Phase.BLOCKED) blockedFrom = phase;
        phase = Phase.BLOCKED;
        updateSprintGate();
        phaseSince = clock.millis();
        note = reason;
        acted = true;
        log.error("BLOCKED ({}): {}", blockedFrom, reason);
        // the reason first, without the phase name it may start with; the place and the step last
        String why = reason.startsWith(blockedFrom + ": ") ? reason.substring(blockedFrom.name().length() + 2) : reason;
        alert(title, body != null ? body : why + "\nFix it, then `" + config.discord.commandPrefix + " resume`."
            + (job != null ? whereLine() + (platform != null ? " on platform " + platform.id : "") + ", while "
            + AlertText.doing(blockedFrom) : ""));
        restoreModules();
        saveCheckpoint();
        reportUnit(true); // the unit stays held while a human looks; its progress shows BLOCKED and why
    }

    private void step() {
        updateOpenings();
        updateStockScope();
        Ctx stepCtx = ctx;
        stepCtx.lastAttempt = null;
        try {
            dispatchStep();
        } finally {
            stepCtx.lastAttempt = null; // an attempt without a task (e.g. the chests ran out) is tied to nothing
            ctx.lastAttempt = null;
        }
    }

    private void dispatchStep() {
        if (job != null && !aborting && phase != Phase.SELECT && jobCancelled(job)) {
            abortTile();
            return;
        }
        if (awaitPlatformClaim()) return;
        if (restartHolding && restartCartsOffBetweenTasks()) return;
        // the ride tile's map phases compare colours with the art, which is loaded first
        if (job != null && tile != null && platform != null && rideMapPhase(phase) && rideTile() && awaitCsArt()) return;
        switch (phase) {
            case IDLE -> stepIdle();
            case SELECT -> stepSelect();
            case INSPECT -> stepInspect();
            case STOCK_CHECK -> stepStockCheck();
            case GEAR -> stepGear();
            case RESTOCK -> stepRestock();
            case BUILD -> stepBuild();
            case VERIFY -> stepVerify();
            case FILL_MAP -> stepFillMap();
            case CHECK_MAP -> stepCheckMap();
            case RIDE_BOARD -> stepRideBoard();
            case RIDE_PRELOAD -> stepRidePreload();
            case RIDE -> stepRide();
            case RIDE_END -> stepRideEnd();
            case RIDE_RESET -> stepRideReset();
            case LOCK -> stepLock();
            case RENAME -> stepRename();
            case STORE -> stepStore();
            case COUNT -> stepCount();
            case NUKE -> stepNuke();
            case CLEAR -> stepClear();
            case WAIT_CLEAR -> stepWaitClear();
            case FINISH_TILE -> stepFinishTile();
            case BLOCKED -> acted = true;
        }
    }

    // =====================================================================================
    // Coordination: units, the platform claim, abandon, progress
    // =====================================================================================

    /**
     * Every tick from the restore on, working or not (paused, BLOCKED): the shared stock writes settle, and a unit the
     * coordination took back, or whose platform claim was lost, is abandoned at once.
     */
    private void tickCoordination() {
        if (task != null && task == stepOffTask && !inCombat && !combatWanted && stepOffMoot()) {
            cancelTask();
            cutStepOff();
        }
        tickSpots();
        sharedStock.tick();
        Abandon a = coord.pollAbandon();
        if (a == null && unit != null && platformClaim != null
            && (platformClaim.state() == Claim.State.LOST || platformClaim.state() == Claim.State.DENIED)) {
            a = new Abandon(unit, Abandon.Reason.CLAIM_LOST, platformClaim.key() + " " + platformClaim.state()
                + (platformClaim.reason() != null ? " (" + platformClaim.reason() + ")" : ""));
        }
        if (a != null) abandon(a);
    }

    /**
     * Stops working on the current unit now (revoked, lease lost, platform claim lost): the running task is cancelled
     * (a container task closes its menu and gives its claim back), every menu claim and the platform claim go back, the
     * engine parks IDLE where the bot stands. Nothing in the world is touched: the tile stays as it is for the next
     * holder, who reads it from the world (INSPECT resumes a matching partial build). A revoked unit or a lost lease is
     * not reported (the unit is no longer this bot's; a report would carry a stale fence); a lost platform claim gives
     * the still held unit back ({@code claim_lost}) so the coordinator can hand it on. An abandon for another grant
     * than the current one is ignored. A bot standing on the art layer (in a clear sweep) then steps off it to an exit
     * outside every sweep ({@link #stepOffAfterAbandon}), so the next holder's lanes and NUKE never meet it there.
     */
    private void abandon(Abandon a) {
        if (unit == null || !unit.sameGrant(a.unit())) return;
        log.info("unit {} of job {} abandoned ({}{}) in {}: stopped; the tile stays as it is for its next holder",
            unit.key(), unit.job(), a.reason(), a.detail().isEmpty() ? "" : ": " + a.detail(), phase);
        dropEscape("unit abandoned");
        cancelTask();
        menuGate.releaseAll();
        releaseSpots();
        countForTile(null);
        saveJob(job);
        if (a.reason() == Abandon.Reason.CLAIM_LOST) coord.release(unit, "claim_lost");
        unit = null; // taken back or given back: nothing more to report
        abandonExits = exitsAfterAbandon();
        abandonPlatform = platform != null ? platform.id : null;
        abandonWalks = 0;
        abandonWhy = "the map was taken back";
        clearCurrent();
        blockedFrom = null;
        go(Phase.IDLE);
        notifier.detail("Map given up", "The coordinator took the map back (" + a.reason() + "); idle.");
    }

    /** After an abandon on the art layer: where the bot steps off to (outside every clear sweep); empty = stay put. */
    private List<Pos> abandonExits = List.of();
    private @Nullable String abandonPlatform;
    private int abandonWalks;
    /** Why the bot steps off ({@link #stepOffAfterAbandon}'s log lines). */
    private String abandonWhy = "the map was taken back";
    /** The walk {@link #stepOffAfterAbandon} started (cut short by {@link #tickCoordination} when moot). */
    private @Nullable BotTask stepOffTask;

    /**
     * A step-off pending or under way is moot once this bot holds the next unit of that same platform: it builds there
     * itself and stays on the art layer.
     */
    private boolean stepOffMoot() {
        if (abandonExits.isEmpty() || abandonPlatform == null || phase != Phase.IDLE) return false;
        Candidate c = findCandidate();
        return c != null && c.platform() != null && abandonPlatform.equals(c.platform().id);
    }

    private void cutStepOff() {
        log.info("not stepping off the art layer of {}: its next map is this bot's", abandonPlatform);
        abandonExits = List.of();
        abandonWalks = 0;
        stepOffTask = null;
    }
    /**
     * The exit off the current platform's art layer (the centre structure, when it is outside every clear sweep) when
     * the bot stands on it or elsewhere in a clear sweep; empty when it stands outside them (a chest, the storage), or
     * nothing is configured. Never a clear stand spot: a bot without the tile's tail walking up to the clear button
     * looks like, and stands one click from, a clear; with no other exit it stays where it stands.
     */
    private List<Pos> exitsAfterAbandon() {
        if (platform == null || (plan == null && platform.center == null)) return List.of();
        Predicate<Pos> away = awayFromClearSweeps();
        if (away.test(botFeet())) return List.of();
        Pos c = feetAt(centerPos());
        if (away.test(c) && !byAClear(c)) return List.of(c);
        Pos off = offLayerExit(platform);
        if (off != null) return List.of(off);
        log.info("no exit off the art layer of {} away from its clear stands; staying where it stands", platform.id);
        return List.of();
    }

    /** Within {@link #CLEAR_KEEP_AWAY} blocks (and 3 up or down) of a clear actuator or its stand spot, any platform. */
    private boolean byAClear(Pos p) {
        for (PlatformCfg pc : config.site.platforms) {
            if (pc.clear == null) continue;
            for (ActuatorCfg a : pc.clear) {
                if (a == null) continue;
                for (Pos q : new Pos[] {a.pos, a.standAt}) {
                    if (q != null && Math.abs(q.x() - p.x()) <= CLEAR_KEEP_AWAY && Math.abs(q.z() - p.z()) <= CLEAR_KEEP_AWAY
                        && Math.abs(q.y() - p.y()) <= 3) return true;
                }
            }
        }
        return false;
    }

    /** A step-off exit this close to a clear button or its stand spot is never walked to. */
    static final int CLEAR_KEEP_AWAY = 4;

    /**
     * IDLE after an abandon: walks off the art layer to an exit outside every clear sweep, at most {@link #EXIT_WALKS}
     * times; the walk never breaks a block (the plugin never allows Baritone to). True while it walks.
     */
    private boolean stepOffAfterAbandon() {
        if (abandonExits.isEmpty()) return false;
        if (stepOffMoot()) {
            cutStepOff();
            return false;
        }
        boolean off = awayFromClearSweeps().test(botFeet());
        if (off || abandonWalks >= EXIT_WALKS) {
            if (!off) log.warn("still on the art layer of {} after {} walks off it ({}); idle where it stands",
                abandonPlatform, abandonWalks, abandonWhy);
            else log.info("off the art layer of {} ({}); idle", abandonPlatform, abandonWhy);
            abandonExits = List.of();
            abandonWalks = 0;
            return false;
        }
        Pos to = abandonExits.get(abandonWalks % abandonExits.size());
        abandonWalks++;
        note = "stepping off the art layer (" + abandonWhy + ")";
        log.info("stepping off the art layer of {} ({})", abandonPlatform, abandonWhy);
        log.debug("abandon exit {}", to.key());
        BotTask walk = bot.walkTo(to, EXIT_STAND_RANGE);
        run(walk, null, r -> log.debug("walk off the art layer failed: {}", r));
        stepOffTask = walk;
        return true;
    }

    /** The current tile's platform claim is still being waited for: nothing happens on the platform until it is held. */
    private boolean awaitPlatformClaim() {
        if (job == null || platformClaim == null || platformClaim.held()) return false;
        if (platformClaim.state() == Claim.State.WAITING) note = "waiting for platform " + platform.id
            + (tailUnit() ? " (the tile's band workers leave it first)" : " (another bot works there)");
        sleep(POLL_TICKS); // lost or denied: tickCoordination abandons the unit
        return true;
    }

    /**
     * Asks for the current platform ({@code platform:<id>}, waiting in line): exclusive for a tile or a tail (one bot
     * holds it), shared for a lane band (any number of band workers; a tail's
     * exclusive request waits until the last has left, and while it waits no new band worker enters). Any claim this bot
     * still holds on a platform goes back first (the release is sent before the request), so a band worker that gets
     * the tail never waits for itself.
     */
    private void claimPlatform() {
        releasePlatform();
        abandonExits = List.of(); // a new tile: a step-off still pending is moot
        abandonWalks = 0;
        platformClaim = coord.claim(Claim.platform(platform.id),
            bandUnit() ? Claim.Mode.SHARED : Claim.Mode.EXCLUSIVE, true);
    }

    /** The current unit is a lane band of its tile. */
    private boolean bandUnit() {
        return unit != null && unit.band();
    }

    /** The current unit is its tile's tail. */
    private boolean tailUnit() {
        return unit != null && unit.tail();
    }

    /** The band a unit builds, or null. */
    private static TileUnitSpec.@Nullable Band bandOf(@Nullable Unit u) {
        return u == null || u.spec() == null ? null : u.spec().band();
    }

    /**
     * A lane band is over here: done (VERIFY found every cell of the band right) or given back for another try. Never a
     * map, store or nuke (the tile's tail does those). The bot steps off the art layer keeping the shared platform claim
     * until it is off, unless its next unit is this platform's ({@link #stepOffMoot}).
     */
    private void finishBand() {
        repairPasses = 0;
        log.info("{} verified: {} blocks; band done (the tile's tail makes the map)", label(), plan.total);
        notifier.detail("Band built", label() + ": " + plan.total + " blocks verified on " + platform.id + ".");
        if (unit != null) coord.done(unit, tileProgress());
        unit = null; // done: nothing left to give back
        saveJob(job);
        clearCurrent();
        go(Phase.SELECT);
    }

    /**
     * The band cannot go on here ({@code reason}): one WARN, the unit failed with retry (another holder, or this one
     * later, tries again; never for good), the bot steps off as after a done band. Nothing on the platform is nuked.
     */
    private void failBand(String reason, String detail) {
        log.warn("{}: {}; the band goes back to the coordinator for another try ({}); nothing is nuked", label(), detail,
            reason);
        if (unit != null) coord.fail(unit, reason, true, detail);
        unit = null;
        clearCurrent();
        go(Phase.SELECT);
    }

    /** Phases of a whole tile's end that a band never runs (its tail does). */
    private static boolean tileEndPhase(Phase p) {
        return p.ordinal() >= Phase.FILL_MAP.ordinal() && p != Phase.BLOCKED;
    }

    // ---- plugin-side claims with wait: sorter:, drop:, station:counter  ----

    /** A spot claim: given back when its task ends ({@code task} set) or else when the phase it was asked in ends. */
    private static final class SpotHold {
        final Claim claim;
        final Phase phase;
        @Nullable BotTask task;

        SpotHold(Claim claim, Phase phase) {
            this.claim = claim;
            this.phase = phase;
        }
    }

    private final Map<String, SpotHold> spots = new LinkedHashMap<>();

    /**
     * Exclusive use of {@code key} ({@link Claim#COUNTER}, {@link Claim#sorter}, {@link Claim#drop}) with wait: true
     * while another bot holds it (the step waits a poll and comes back; the note says {@code what}), false once held.
     * One bot (local mode) holds every claim at once.
     */
    private boolean awaitSpot(String key, String what) {
        SpotHold h = spots.get(key);
        if (h == null || h.claim.over()) {
            h = new SpotHold(coord.claim(key, Claim.Mode.EXCLUSIVE, true), phase);
            spots.put(key, h);
        }
        if (h.claim.held()) return false;
        note = "waiting for " + what + " (another bot uses it)";
        sleep(POLL_TICKS);
        return true;
    }

    /** The task just started ({@link #task}) holds spot {@code key}: given back when it ends, however it ends. */
    private void spotTask(String key) {
        SpotHold h = spots.get(key);
        if (h != null) h.task = task;
    }

    /** Every tick: spots whose task ended, or whose phase is over, go back. */
    private void tickSpots() {
        if (spots.isEmpty()) return;
        for (var it = spots.entrySet().iterator(); it.hasNext(); ) {
            SpotHold h = it.next().getValue();
            if (h.task != null ? h.task.isFinished() : h.phase != phase) {
                h.claim.release();
                it.remove();
            }
        }
    }

    private void releaseSpots() {
        for (SpotHold h : spots.values()) h.claim.release();
        spots.clear();
    }

    private void releasePlatform() {
        if (platformClaim != null) platformClaim.release();
        platformClaim = null;
    }

    /**
     * The current tile ends without being finished here (set aside, nothing to build, a cancelled job's clean-up done):
     * a unit still held goes back to the coordination ({@code reason}), and the platform claim with it.
     */
    private void endUnit(String reason) {
        if (unit != null) coord.release(unit, reason);
        unit = null;
        unitReportedAt = 0;
        unitReportedPlaced = -1;
        unitReportedPhase = null;
        unitReportedFacts = -1;
        releasePlatform();
    }

    /**
     * The unit's progress ({@link Coordination#progress}): at every phase change ({@code phaseChanged}) and on the tick a
     * fact the world cannot show changes (stored, counted, ride pressed / settled: {@link #unitFacts}), otherwise at most
     * every {@link #UNIT_PROGRESS_MS} while the placed count changes (lanes, repairs). Placed cells come from the world
     * (INSPECT's survey, VERIFY's scan) and the lane listener between them.
     */
    private void reportUnit(boolean phaseChanged) {
        if (unit == null || tile == null) return;
        long now = clock.millis();
        int facts = unitFacts();
        if (!phaseChanged && facts == unitReportedFacts && (now - unitReportedAt < UNIT_PROGRESS_MS
            || (tile.placed == unitReportedPlaced && phase == unitReportedPhase))) return;
        unitReportedAt = now;
        unitReportedPlaced = tile.placed;
        unitReportedPhase = phase;
        unitReportedFacts = facts;
        coord.progress(unit, tile.placed, tile.total, tileProgress());
    }

    /** The current tile's facts a successor must not lose, as bits: stored, counted, ride pressed, ride settled. */
    private int unitFacts() {
        return (tile.status == TileStatus.STORED ? 1 : 0) | (tile.counted ? 2 : 0) | (tile.ridePressed ? 4 : 0)
            | (tile.rideSettled ? 8 : 0);
    }

    /**
     * Lane tiles, at a unit's start: a band records the tile's start for this bot; a tail goes by this bot's own
     * earliest band start of the tile, if it built one, so its Map done post times the whole tile from its first band.
     */
    private void noteTileStart() {
        tileStartKnown = false;
        if (unit == null) return;
        String key = Unit.tileKey(job.id, tile.x, tile.z);
        if (unit.band()) {
            ownBandStarts.merge(key, tile.startedAt, Math::min);
        } else if (unit.tail()) {
            Long own = ownBandStarts.get(key);
            if (own != null) adoptTileStart(own);
        }
    }

    /** {@code at} (a band start of the tail's tile) becomes the tile's start when it is earlier than the one it has. */
    private void adoptTileStart(long at) {
        if (at <= 0 || tile == null) return;
        tileStartKnown = true;
        if (tile.startedAt == 0 || at < tile.startedAt) tile.startedAt = at;
    }

    /** The tile no longer needs its start record (stored, or its job cancelled): this bot's memory. */
    private void forgetTileStart() {
        if (job == null || tile == null || !(bandUnit() || tailUnit())) return;
        ownBandStarts.remove(Unit.tileKey(job.id, tile.x, tile.z));
    }

    /** What a successor cannot read from the world ({@link TileProgress}), as of now. */
    private TileProgress tileProgress() {
        return new TileProgress(tile.placed, tile.total, phase.name(), platform != null ? platform.id : null,
            tile.status == TileStatus.STORED, tile.counted, tile.ridePressed, tile.rideSettled, clearAttempts,
            repairPasses, fillAttempts, tile.startedAt, tile.mapName, note, tile.unlockedMaps, null,
            bandUnit() ? null : storedMapId, null);
    }

    /**
     * A successor's start: the facts the previous holders reported that the world cannot show. A stored map is
     * never built or stored again (INSPECT goes on with COUNT/NUKE), a counted one never counted again; the retry
     * counters carry over. The map item itself stays with the account that made it: a successor makes its own.
     */
    private void adoptHandedOver(TileProgress h) {
        if (h.stored()) tile.status = TileStatus.STORED;
        tile.counted |= h.counted();
        tile.ridePressed |= h.ridePressed();
        tile.rideSettled |= h.rideSettled();
        if (h.startedAt() > 0 && (tile.startedAt == 0 || h.startedAt() < tile.startedAt)) tile.startedAt = h.startedAt();
        clearAttempts = Math.max(clearAttempts, h.clearAttempts());
        repairPasses = Math.max(repairPasses, h.repairPasses());
        fillAttempts = Math.max(fillAttempts, h.fillAttempts());
        // this account's own maps of the tile (the coordination drops another account's count): after a restart
        // the held map is found again (tileUnlockedMap) and the stale ones go to the scrap chest (nextStaleMap)
        tile.unlockedMaps = Math.max(tile.unlockedMaps, h.unlockedMaps());
        // the map an earlier holder stored: its id goes on with this holder's done report (its render does not)
        if (h.stored() && h.mapId() != null && storedMapId == null) storedMapId = h.mapId();
    }

    // =====================================================================================
    // Restore from checkpoint
    // =====================================================================================

    private record Restore(EngineCheckpoint cp, Job job, Job.TileState tile, PlatformCfg platform, TilePlan plan,
                           String problem) { }

    private void restoreTick() {
        if (task == null) {
            run(new AsyncTask<>("restore checkpoint", executor, this::loadRestore, this::applyRestore), null, reason -> {
                log.error("could not restore the checkpoint ({}); starting idle", reason);
                restored = true;
                phase = Phase.IDLE;
                phaseSince = clock.millis();
                ctx = new Ctx();
            });
        }
        TaskStatus s = task.tick();
        if (s != TaskStatus.RUNNING) finishTask(s);
    }

    /** Executor thread. */
    private Restore loadRestore() throws IOException {
        EngineCheckpoint cp = store.loadCheckpoint();
        if (cp == null || cp.jobId == null) return new Restore(cp, null, null, null, null, null);
        Job j = coord.job(cp.jobId).orElse(null);
        if (j == null) return new Restore(cp, null, null, null, null, "job " + cp.jobId + " no longer exists");
        Job.TileState t = j.tile(new TileCoord(cp.tileX, cp.tileZ));
        if (t == null) return new Restore(cp, null, null, null, null, "tile " + cp.tileX + "," + cp.tileZ + " missing");
        PlatformCfg p = platformById(cp.platformId);
        if (p == null) return new Restore(cp, null, null, null, null, "platform " + cp.platformId + " not configured");
        TilePlan tp = new TilePlan(store.loadTile(j.id, t.coord()), geometryFor(j, t, p), catalog, fixtures(j, t, p),
            bandOf(coord.unit(j, t)));
        return new Restore(cp, j, t, p, tp, null);
    }

    private void applyRestore(Restore r) {
        restored = true;
        restoredCheckpoint = r.cp();
        if (r.cp() != null && r.cp().lastServerRestartAt > 0) lastServerRestartAt = r.cp().lastServerRestartAt;
        long now = clock.millis();
        if (r.problem() != null) log.warn("checkpoint not resumable: {}", r.problem());
        if (r.job() == null) {
            phase = Phase.IDLE;
            phaseSince = now;
            ctx = new Ctx();
            log.info("engine ready (idle)");
            return;
        }
        EngineCheckpoint cp = r.cp();
        Unit u = coord.unit(r.job(), r.tile());
        if (u != null && !java.util.Objects.equals(bandOf(u), r.plan().band)) u = null; // another unit of the tile now
        if (u == null) {
            // the checkpoint's tile is no longer granted to this bot (its next holder reads it from the world)
            log.info("checkpoint's tile {} of job {} is not this bot's unit any more; starting idle", r.tile().coord(),
                r.job().id);
            phase = Phase.IDLE;
            phaseSince = now;
            ctx = new Ctx();
            saveCheckpoint();
            return;
        }
        job = r.job();
        tile = r.tile();
        platform = r.platform();
        unit = u;
        claimPlatform();
        plan = r.plan();
        config.site.openings = openingsFor(job, platform);
        expected = null;
        clearAttempts = cp.clearAttempts;
        repairPasses = cp.repairPasses;
        fillAttempts = cp.fillAttempts;
        resumeCandidate = true;
        aborting = job.status == JobStatus.CANCELLED || job.status == JobStatus.FAILED;
        Phase saved = parsePhase(cp.phase);
        String savedNote = cp.note == null ? "" : cp.note;
        Phase origin = saved;
        if (saved == Phase.BLOCKED) {
            origin = parsePhase(cp.blockedFrom);
            if (savedNote.startsWith("[") && savedNote.indexOf(']') > 0) {
                // checkpoints before blockedFrom existed encoded it as a "[PHASE] " note prefix
                Phase legacy = parsePhase(savedNote.substring(1, savedNote.indexOf(']')));
                if (legacy != null) {
                    if (origin == null) origin = legacy;
                    savedNote = savedNote.substring(savedNote.indexOf(']') + 1).trim();
                }
            }
        }
        Phase target = origin == null || origin == Phase.IDLE || origin == Phase.SELECT || origin == Phase.BLOCKED
            ? Phase.INSPECT : origin;
        // a stored map goes on with the clean-up, the map counter first if it was not pressed yet (a restart between
        // STORE and the press presses once; one after the press, tile.counted, never again)
        if (tile.status == TileStatus.STORED && target.ordinal() < Phase.NUKE.ordinal()) target = afterStore();
        else if (aborting && target.ordinal() < Phase.NUKE.ordinal()) target = Phase.NUKE;
        rate.reset(now);
        lastProgressAt = now;
        ctx = new Ctx();
        failures = 0;
        wakeTick = tick + START_DELAY_TICKS;
        if (saved == Phase.BLOCKED && resumeBeforeRestore) {
            log.info("checkpoint was BLOCKED ({}: {}); resumed by request", target, savedNote);
            clearAttempts = 0;
            repairPasses = 0;
            fillAttempts = 0;
            phase = target;
            phaseSince = now;
            saveCheckpoint();
        } else if (saved == Phase.BLOCKED) {
            phase = Phase.BLOCKED;
            blockedFrom = target;
            note = savedNote;
            phaseSince = cp.phaseSince > 0 ? cp.phaseSince : now;
            log.warn("restored BLOCKED ({}): {}", blockedFrom, note);
        } else {
            phase = target;
            phaseSince = cp.phaseSince > 0 ? cp.phaseSince : now;
            log.info("resuming {} at {}", label(), phase);
            if (target != saved) saveCheckpoint();
        }
    }

    // =====================================================================================
    // IDLE / SELECT
    // =====================================================================================

    private void stepIdle() {
        if (stepOffAfterAbandon()) return;
        if (forceScan) {
            if (stockRoutine(true)) return;
            forceScan = false;
            ctx = new Ctx();
        }
        if (findCandidate() != null) {
            go(Phase.SELECT);
            return;
        }
        sleep(IDLE_POLL_TICKS);
    }

    private record Candidate(Job job, Job.TileState tile, PlatformCfg platform) { }

    private void stepSelect() {
        if (resumeNearScan()) return;
        Candidate next = findCandidate();
        // a colour-suppression map's passes go in order whatever the stock (docs/COLOR_SUPPRESSION.md): the next pass is never
        // weighed against the stock index, nor are its chests re-read "before skipping" it. At SELECT no job is current,
        // so the index shows the site's chests, not the platform's own: live 2026-11-13 every pass looked short and
        // 310 sorter containers were rescanned before each (4 minutes). STOCK_CHECK looks, in the job's own scope
        Candidate c = config.build.skipShortTiles && !(next != null && next.job().isSuppression())
            ? chooseByStock(next) : next;
        if (c == READING) return; // reading the stock index or the tiles' materials
        if (c == null) {
            alertNothingToBuild(); // maps wait for materials and none can be built: a human has to restock
            skipped.clear();
            clearCurrent();
            go(Phase.IDLE);
            return;
        }
        if (passOrderDue(c)) return;
        boolean resume = (c.tile().status == TileStatus.ACTIVE && c.platform().id.equals(c.tile().platformId))
            || checkpointMatches(c);
        Job j = c.job();
        Job.TileState t = c.tile();
        PlatformCfg p = c.platform();
        TileUnitSpec.Band band = bandOf(coord.unit(j, t)); // a lane band: the plan filtered to its cells
        run(new AsyncTask<>("load tile " + j.id + " " + t.x + "," + t.z, executor,
                () -> new TilePlan(store.loadTile(j.id, t.coord()), geometryFor(j, t, p), catalog, fixtures(j, t, p), band),
                tp -> activate(c, tp, resume)),
            null,
            reason -> {
                log.error("tile {} of job {} unreadable: {}", t.coord(), j.id, reason);
                j.status = JobStatus.FAILED;
                j.statusDetail = "tile " + t.coord() + " unreadable: " + reason;
                saveJob(j);
                Unit u = coord.unit(j, t);
                if (u != null) coord.fail(u, "tile_unreadable", false, reason);
                alert("Job failed: " + j.name, j.statusDetail + "\n(#" + j.id + ")");
                go(Phase.SELECT);
            });
    }

    /** Colour-suppression jobs whose pass order was looked at in this engine run ({@link #passOrderDue}). */
    private final Set<String> passOrderChecked = new HashSet<>();

    /**
     * A colour-suppression job that has not started any pass takes the platform's current order
     * ({@code platform.topLayerFirst}) when it is first selected: its stored passes are put in that order
     * ({@link PassOrder}; off the tick thread, then SELECT runs again), and a change a restart cut short is finished.
     * A job with a pass started keeps the order it has. True while the passes are being written.
     */
    private boolean passOrderDue(Candidate c) {
        Job j = c.job();
        if (!j.isSuppression() || !c.platform().isColorSuppression() || passOrderChecked.contains(j.id)) return false;
        if (!PassOrder.unstarted(j)) {
            passOrderChecked.add(j.id);
            return false;
        }
        boolean topFirst = c.platform().topLayerFirst;
        run(new AsyncTask<>("pass order of " + j.id, executor, () -> java.util.Optional.ofNullable(PassOrder.write(store, j, topFirst)),
            change -> {
                passOrderChecked.add(j.id);
                if (change.isEmpty()) return;
                PassOrder.apply(j, change.get());
                saveJob(j);
                try {
                    store.saveBlob(PassOrder.blobName(j.id), null);
                } catch (IOException | RuntimeException e) {
                    log.warn("the pass order journal of {} was not removed: {}", j.id, e.toString());
                }
                tileNeeds.keySet().removeIf(k -> k.startsWith(j.id + " "));
                mapNeedsCache.keySet().removeIf(k -> k.startsWith(j.id + " "));
                nooblineMerge.keySet().removeIf(k -> k.startsWith(j.id + "/")); // the passes' indices changed
                raisedRowMerge.keySet().removeIf(k -> k.startsWith(j.id + "/"));
                rowArts.keySet().removeIf(k -> k.startsWith(j.id + "/"));
                levelArts.keySet().removeIf(k -> k.startsWith(j.id + "/"));
                if (j.id.equals(csArtJob)) csArt = null;
                log.info("job {} had not started: its passes now go in the platform's order, the {} layer first", j.id,
                    topFirst ? "top" : "bottom");
            }), null, reason -> {
                // its tiles may be in either order now: never printed from that
                passOrderChecked.add(j.id);
                log.error("the passes of job {} could not be put in the platform's order: {}", j.id, reason);
                j.status = JobStatus.FAILED;
                j.statusDetail = "pass order not changed: " + reason;
                saveJob(j);
                alert("Job failed: " + j.name, j.statusDetail + "\n(#" + j.id + ")");
                go(Phase.SELECT);
            });
        return true;
    }

    private void activate(Candidate c, TilePlan tp, boolean resume) {
        long now = clock.millis();
        Unit next = coord.unit(c.job(), c.tile());
        if (next == null || !java.util.Objects.equals(bandOf(next), tp.band)) {
            // the unit was taken back (or another unit of the tile came) while its tile loaded; SELECT looks again
            go(Phase.SELECT);
            return;
        }
        if (unit != null && !unit.sameGrant(next)) endUnit("set_aside"); // another tile was still current
        job = c.job();
        tile = c.tile();
        platform = c.platform();
        unit = next;
        claimPlatform();
        TileProgress handed = unit.handedOver();
        plan = tp;
        config.site.openings = openingsFor(job, platform);
        expected = null;
        // a unit another account worked on (retried, or its progress shows a start): the platform's blocks that match
        // the tile are its build, to resume, never foreign (a successor's tile is PENDING and it has no checkpoint)
        resumeCandidate = resume || unit.resumable();
        aborting = false;
        builtNotified = false;
        clearAttempts = 0;
        repairPasses = 0;
        fillAttempts = 0;
        foreignInspections = 0;
        noProgressRounds = 0;
        lastRestockWithdrawn = -1;
        lazyDropDebt = 0;
        sorterGaveUp.clear();
        lastVerifyMissing = -1;
        pickupEmpty = 0;
        mapId = null;
        lockedMapId = null;
        storedMapId = null;
        freshMaps = 0;
        tilePictures.clear();
        buildLanes = null;
        buildFarLeftOut = false;
        lanesCache = null;
        needsCache = null;
        deferred.clear();
        stuckCells.clear();
        recheckItems = null;
        endMaterialsWait();
        warned.clear();
        rate.reset(now);
        lastProgressAt = now;
        // a tile begun by the previous map's carry-over and never selected is still announced as started
        boolean fresh = (tile.status == TileStatus.PENDING || tile.carried > 0 && tile.startedAt == 0)
            && !unit.resumable();
        carryNext = null;
        carriedOver = false;
        boolean recheck = job.status == JobStatus.WAITING_MATERIALS;
        if (job.status == JobStatus.QUEUED) job.status = JobStatus.ACTIVE;
        tile.status = TileStatus.ACTIVE;
        tile.platformId = platform.id;
        if (handed != null) adoptHandedOver(handed);
        if (tile.startedAt == 0) tile.startedAt = now;
        noteTileStart();
        tile.total = plan.total;
        saveJob(job);
        if (!plan.unplaceable.isEmpty()) log.warn("tile {} has states without a placing item: {}", tile.coord(), plan.unplaceable);
        log.info("selected {} on platform {} ({} blocks{})", label(), platform.id, plan.total,
            recheck ? ", re-checking materials" : unit.band() ? ", a lane band (platform shared)"
                : unit.tail() ? ", the tile's tail (platform exclusive)" : resume ? ", resume candidate" : "");
        // every map's start is posted (the owner wants each map announced, even in quiet mode), but only once STOCK_CHECK lets
        // it build: a map set aside for lack of materials is named in the start line of the map built instead. A lane
        // band or a tail is a part of a map: no start post (the tail's Map done post is the map's one line)
        announceStart = fresh && (config.build.skipShortTiles || !recheck);
        if (!fresh && !recheck && !unit.band() && !unit.tail())
            notifier.detail("Resuming " + job.name, label() + " on platform " + platform.id);
        go(Phase.INSPECT);
    }

    /**
     * Colour-suppression jobs that share a platform are built one at a time, in queue order: it is {@code j}'s turn
     * when it is the first unfinished one of them that has started (a pass begun or done: its blocks are on the
     * layers), else the first in the queue. A later job never takes the layers while an earlier one waits (for
     * materials, for its re-check). True for every other job.
     */
    static boolean suppressionTurn(List<Job> jobs, Job j) {
        if (!j.isSuppression()) return true;
        Job first = null;
        for (Job o : jobs) {
            if (o == null || !o.isSuppression() || o.status == null || o.status.terminal()) continue;
            if (o != j && java.util.Collections.disjoint(o.platformIds, j.platformIds)) continue;
            boolean started = false;
            for (Job.TileState t : o.tiles) started |= t != null && t.status != TileStatus.PENDING;
            if (started) return o == j;
            if (first == null) first = o;
        }
        return first == null || first == j;
    }

    private boolean checkpointMatches(Candidate c) {
        EngineCheckpoint cp = restoredCheckpoint;
        return cp != null && c.job().id.equals(cp.jobId) && cp.tileX == c.tile().x && cp.tileZ == c.tile().z
            && c.platform().id.equals(cp.platformId);
    }

    /** Next job/tile/platform: ACTIVE jobs, then QUEUED, then WAITING_MATERIALS that are due for a re-check. */
    private Candidate findCandidate() {
        long now = clock.millis();
        List<Job> jobs = coord.jobs();
        for (JobStatus want : List.of(JobStatus.ACTIVE, JobStatus.QUEUED, JobStatus.WAITING_MATERIALS)) {
            for (Job j : jobs) {
                if (j.status != want) continue;
                if (want == JobStatus.WAITING_MATERIALS && !waitingDue(j, now)) continue;
                Job.TileState t = openTile(j);
                if (t == null) {
                    completeJob(j);
                    continue;
                }
                // only a tile this bot holds the unit of (one bot: every tile)
                if (coord.unit(j, t) == null && (t = grantedTile(j)) == null) continue;
                t = mainFirst(j, t);
                // colour-suppression jobs on one platform: one at a time, in queue order
                if (!suppressionTurn(jobs, j)) continue;
                PlatformCfg p = choosePlatform(j, t, jobs);
                if (p == null) {
                    warnOnce("noplatform:" + j.id, j.platformIds.stream().anyMatch(id -> usablePlatform(id) != null)
                        ? "job " + j.id + " waits: its platforms " + j.platformIds + " hold another job's partly built map"
                        : "job " + j.id + " has no enabled " + (j.isSuppression() ? "COLOR_SUPPRESSION" : "MOSS/SOUL_SAND")
                            + " platform among " + j.platformIds);
                    continue;
                }
                return new Candidate(j, t, p);
            }
        }
        return null;
    }

    // ---- stock-aware SELECT (build.skipShortTiles) ----

    /** An unfinished tile with nothing placed, and whether its job may be tried for a partial build now. */
    private record Option(Candidate c, boolean due) { }

    /** {@link #chooseByStock}: still reading the stock index or the tiles' materials. */
    private static final Candidate READING = new Candidate(null, null, null);

    private static String tileKey(Job j, Job.TileState t) {
        return j.id + " " + t.x + "," + t.z;
    }

    /** Per tile key: the map's item counts for {@link #largestMapNeeds} (any thread; dropped on a queue change). */
    private final Map<String, Map<String, Integer>> mapNeedsCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * {@link EngineControl#largestMapNeeds}: per item, the most one unfinished map needs, from the stored tiles
     * ({@link TilePlan#allNeeds} on the tile's platform). A color-suppression job is one map: the sum of its passes
     * not yet verified. A tile that cannot be read counts as nothing. Live 2026-10-15: {@code !map stock} compared
     * the stock with the queued jobs' summed materials, as if no block came back.
     */
    @Override
    public Map<String, Integer> largestMapNeeds(List<Job> jobs) {
        Map<String, Integer> peak = new TreeMap<>();
        for (Job j : jobs) {
            // a color-suppression job is one map: its passes all stand until the ride, so it needs their sum (live
            // 2026-11-15: the stock command said "nothing short" but for 164 brown concrete, by the biggest pass,
            // with six queued maps needing up to 3,232 more jungle planks than all storage held)
            final Map<String, Integer> whole = j.isSuppression() ? new TreeMap<>() : null;
            for (Job.TileState t : j.tiles) {
                if (t.status == TileStatus.STORED || t.status == TileStatus.FAILED) continue;
                Map<String, Integer> needs = mapNeedsCache.computeIfAbsent(tileKey(j, t), k -> readMapNeeds(j, t));
                if (whole != null) needs.forEach((id, n) -> whole.merge(id, n, Integer::sum));
                else needs.forEach((id, n) -> peak.merge(id, n, Math::max));
            }
            if (whole != null) whole.forEach((id, n) -> peak.merge(id, n, Math::max));
        }
        return peak;
    }

    private Map<String, Integer> readMapNeeds(Job j, Job.TileState t) {
        PlatformCfg p = platformById(t.platformId);
        if (p == null) for (String id : j.platformIds) if ((p = platformById(id)) != null) break;
        if (p == null) return Map.of();
        try {
            return new TilePlan(store.loadTile(j.id, t.coord()), geometryFor(j, t, p), catalog, fixtures(p)).allNeeds();
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    private static String label(Job j, Job.TileState t) {
        return "#" + j.id + " " + j.name + " [" + t.x + "," + t.z + "]";
    }

    /**
     * The tile SELECT takes, by stock ({@code build.skipShortTiles}); {@link #READING} while the tiles' materials are
     * read (off the tick thread, once per tile and run), null when there is none. {@code inOrder} is the first job's
     * next tile in order ({@link #findCandidate}, null when none is due), as before. With {@code nearScanFirst}, a short
     * near index is refreshed before passing over the in-order tile, unless a recent full near sweep exists.
     * Otherwise it weighs the stock index as it is ({@link #coveredElsewhere} reconsiders at STOCK_CHECK).
     * <ol>
     *   <li>A tile with blocks placed goes on first: a partly built platform hosts no other tile.</li>
     *   <li>Otherwise every unfinished tile of every unfinished job is weighed against one stock snapshot (the stock
     *   index plus the inventory): the first in queue and map order the stock covers completely is taken, also of a job
     *   held as WAITING_MATERIALS (another of its maps may be fully stocked).</li>
     *   <li>None: with partial builds the tile with the most coverable blocks among the jobs due for a try (not held as
     *   WAITING_MATERIALS in the last {@code waitingRecheckMinutes}); STOCK_CHECK then looks again at the chests and
     *   builds it partly or holds it. Without partial builds the tile in order, as before.</li>
     * </ol>
     */
    private Candidate chooseByStock(@Nullable Candidate inOrder) {
        List<Job> jobs = coord.jobs();
        Candidate started = startedCandidate(jobs);
        if (started != null) return started;
        List<Option> opts = freshOptions(jobs);
        if (opts.isEmpty()) return inOrder;
        if (opts.size() == 1 && opts.getFirst().due()) return opts.getFirst().c(); // nothing to choose from
        if (readTileNeeds(opts)) return READING;
        if (inOrder != null && config.build.nearScanFirst) {
            Map<String, Integer> needs = tileNeeds.get(tileKey(inOrder.job(), inOrder.tile()));
            PlatformGeometry geo = geometryFor(inOrder.job(), inOrder.tile(), inOrder.platform());
            if (needs != null && geo.equals(ctx.nearChecked) && PartialBuild.missing(needs, nearAvailable(geo)) == 0)
                return inOrder;
            // a sorter-refilled platform: the near chests return what the last map left, however long it takes
            if (needs != null && PartialBuild.missing(needs, creditedAvail(available(), needs, inOrder.platform(), geo)) == 0)
                return inOrder;
            if (needs != null && PartialBuild.missing(needs, available()) > 0
                && PartialBuild.missing(needs, nearAvailable(geo)) > 0) {
                Map<String, Integer> near = nearAvailable(geo);
                Set<String> nearShort = new TreeSet<>();
                needs.forEach((id, n) -> {
                    if (n > near.getOrDefault(id, 0)) nearShort.add(id);
                });
                String why = "before skipping " + label(inOrder.job(), inOrder.tile());
                if (nearScanFirst(geo, nearShort, why, false)) return READING;
                if (PartialBuild.missing(needs, nearAvailable(geo)) == 0) return inOrder;
                // still short after the targeted look: every near container (build.nearRecheckAll, at most once a
                // minute), never at a map start with nothing short (the owner's rule of 2026-10-13: no sweep per decision)
                if (nearLookAll(geo, nearShort, why, false)) return READING;
                if (PartialBuild.missing(needs, nearAvailable(geo)) == 0) return inOrder;
            }
        }
        Map<String, Integer> availAll = available();
        Option best = null;
        int bestCells = -1;
        List<ShortMap> passed = new ArrayList<>();
        for (Option o : opts) {
            Map<String, Integer> needs = tileNeeds.getOrDefault(tileKey(o.c().job(), o.c().tile()), Map.of());
            Map<String, Integer> avail = creditedAvail(availAll, needs, o);
            int missing = PartialBuild.missing(needs, avail);
            if (missing == 0) {
                if (inOrder != null && o.c().tile() != inOrder.tile())
                    log.info("{} is fully stocked: building it before {}", label(o.c().job(), o.c().tile()),
                        label(inOrder.job(), inOrder.tile()));
                // the maps before it in order are short by the stock index: its start line names them
                for (ShortMap m : passed) {
                    skipped.removeIf(x -> tileKey(x.job(), x.tile()).equals(tileKey(m.job(), m.tile())));
                    skipped.add(m);
                }
                return o.c();
            }
            Map<String, Integer> lack = new TreeMap<>();
            needs.forEach((id, n) -> {
                if (n > avail.getOrDefault(id, 0)) lack.put(id, n - avail.getOrDefault(id, 0));
            });
            passed.add(new ShortMap(o.c().job(), o.c().tile(), needs, lack));
            int cells = PartialBuild.cells(needs) - missing;
            if (o.due() && cells > bestCells) {
                best = o;
                bestCells = cells;
            }
        }
        if (!config.build.partialBuild || best == null) return inOrder;
        if (inOrder != null && best.c().tile() != inOrder.tile())
            log.info("no queued map is fully stocked; {} has the most blocks in stock ({}): trying it before {}",
                label(best.c().job(), best.c().tile()), bestCells, label(inOrder.job(), inOrder.tile()));
        return best.c();
    }

    /**
     * Items the other unfinished tiles with nothing placed lack by the stock index (tiles read before), for STOCK_CHECK's
     * look at the chests: one look then tells whether one of them is fully stocked ({@link #coveredElsewhere}).
     */
    private Set<String> shortElsewhere() {
        Set<String> out = new TreeSet<>();
        if (!config.build.skipShortTiles) return out;
        Map<String, Integer> availAll = available();
        for (Option o : freshOptions(coord.jobs())) {
            if (isCurrent(o.c())) continue;
            Map<String, Integer> needs = tileNeeds.get(tileKey(o.c().job(), o.c().tile()));
            if (needs == null) continue;
            Map<String, Integer> avail = creditedAvail(availAll, needs, o);
            needs.forEach((id, n) -> {
                if (n > avail.getOrDefault(id, 0)) out.add(id);
            });
        }
        return out;
    }

    private boolean isCurrent(Candidate c) {
        return c.tile() == tile || (c.job().id.equals(job.id) && c.tile().x == tile.x && c.tile().z == tile.z);
    }

    /** The first tile, in queue order, with blocks placed on its platform (ACTIVE, placed above 0), or null. */
    private Candidate startedCandidate(List<Job> jobs) {
        for (Job j : jobs) {
            if (j.status == null || j.status.terminal()) continue;
            for (Job.TileState t : j.tiles) {
                if (t.status != TileStatus.ACTIVE || t.placed <= 0 || t.platformId == null) continue;
                if (coord.unit(j, t) == null) continue; // not this bot's unit (one bot: every tile is)
                PlatformCfg p = usablePlatform(t.platformId);
                if (p != null) return new Candidate(j, t, p);
            }
        }
        return null;
    }

    /**
     * Every unfinished tile with nothing placed of every unfinished job, in queue and map order, with a platform: what
     * the stock-aware choice weighs. Never a colour-suppression job's pass: its passes go strictly in order and its
     * stock is the platform's own chests (docs/COLOR_SUPPRESSION.md), which the index does not show unless the job is current.
     */
    private List<Option> freshOptions(List<Job> jobs) {
        long now = clock.millis();
        List<Option> out = new ArrayList<>();
        for (Job j : jobs) {
            if (j.status == null || j.status.terminal() || j.isSuppression()) continue;
            boolean due = j.status != JobStatus.WAITING_MATERIALS || waitingDue(j, now);
            for (Job.TileState t : j.tiles) {
                boolean fresh = t.status == TileStatus.PENDING || (t.status == TileStatus.ACTIVE && t.placed <= 0);
                if (!fresh) continue;
                if (coord.unit(j, t) == null) continue; // not this bot's unit (one bot: every tile is)
                PlatformCfg p = choosePlatform(j, t, jobs);
                if (p != null) out.add(new Option(new Candidate(j, t, p), due));
            }
        }
        return out;
    }

    /**
     * {@code build.suppressionMergeNoobline}: the noobline pass whose row main pass {@code t} places with its own
     * lanes, or null. It is the pass stored right before {@code t}, of the same layer and level, not yet verified.
     * Never for the job's last pass: the ride follows that one ({@link #rideTile}), so its noobline pass stays first.
     */
    static Job.@Nullable TileState nooblineTwin(Job j, Job.TileState t) {
        Job.SuppressionPass main = suppressionPass(j, t);
        if (main == null || main.noobline || t.x < 1 || t.x == j.suppression.size() - 1) return null;
        Job.SuppressionPass noob = j.suppression.get(t.x - 1);
        if (!noob.noobline || noob.layer != main.layer || noob.level != main.level) return null;
        Job.TileState n = j.tile(new TileCoord(t.x - 1, 0));
        return n == null || n.status == TileStatus.STORED || n.status == TileStatus.FAILED ? null : n;
    }

    /** The main pass, still to finish, whose lanes place noobline pass {@code t}'s row ({@link #nooblineTwin}), or null. */
    static Job.@Nullable TileState mainOfNoobline(Job j, Job.TileState t) {
        Job.SuppressionPass noob = suppressionPass(j, t);
        if (noob == null || !noob.noobline) return null;
        Job.TileState m = j.tile(new TileCoord(t.x + 1, 0));
        if (m == null || m.status == TileStatus.STORED || m.status == TileStatus.FAILED) return null;
        return nooblineTwin(j, m) == t ? m : null;
    }

    /**
     * Noobline pass {@code t} comes after the main pass of its level (stored right after it): that one is verified or
     * has blocks placed while {@code t} is not verified ({@code build.suppressionMergeNoobline} chose it first).
     */
    static boolean mainBuiltFirst(Job j, Job.TileState t) {
        Job.SuppressionPass noob = suppressionPass(j, t);
        if (noob == null || !noob.noobline || t.status == TileStatus.STORED || t.x + 1 >= j.suppression.size()) return false;
        Job.SuppressionPass main = j.suppression.get(t.x + 1);
        Job.TileState m = j.tile(new TileCoord(t.x + 1, 0));
        return m != null && !main.noobline && main.layer == noob.layer && main.level == noob.level
            && (m.status == TileStatus.STORED || m.placed > 0);
    }

    /** Arts of noobline passes (their row 0 is the row) by "job/pass index", read once a pass; the current job's only. */
    private final Map<String, TileArt> rowArts = new HashMap<>();

    /** The art of noobline pass {@code t} ({@link #rowArts}), or null when it cannot be read (logged). */
    private @Nullable TileArt rowArt(Job.TileState t) {
        String key = job.id + "/" + t.x;
        TileArt a = rowArts.get(key);
        if (a != null) return a;
        try {
            a = store.loadTile(job.id, t.coord());
        } catch (IOException e) {
            log.warn("noobline row of {} not read ({}): its own pass builds it", label(), e.toString());
            return null;
        }
        rowArts.keySet().removeIf(k -> !k.startsWith(job.id + "/")); // a tile art is ~100 KB: never another job's
        rowArts.put(key, a);
        return a;
    }

    /**
     * {@code build.suppressionMergeNoobline}: {@code lanes} of the current main pass with the cells of its level's
     * noobline row still to place ({@link #nooblineTwin}). Each cell goes to the lane whose walking column is nearest
     * (within the lane's half width) when that lane's north end lies in the map's first two rows: the end moves to the
     * noobline row (on the glass too, where the noobline pass's own lanes start), and the cells are placed first on a
     * walk south, last on a walk north. Cells without such a lane stay for the noobline pass. With
     * {@code build.suppressionMergeLevels} a level-0 main pass that carries its layer's level-1 cells
     * ({@link #withLevels}) carries the level-1 noobline row as well, one block higher ({@link #raisedRowTwin}).
     */
    private List<Lane> withNoobline(List<Lane> lanes, WorldView w, int halfWidth) {
        if (!config.build.suppressionMergeNoobline || !suppressionTile() || plan == null || plan.band != null) return lanes;
        Job.TileState twin = nooblineTwin(job, tile);
        TileArt row = twin == null || !nooblineMerges(job, tile, twin) ? null : rowArt(twin);
        Job.TileState up = raisedRowTwin(job, tile);
        TileArt upRow = up == null || !raisedRowMerges(job, tile, up) ? null : rowArt(up);
        if (row == null && upRow == null) return lanes;
        PlatformGeometry g = plan.geo;
        int z = g.minZ() - 1;
        List<Lane> out = new ArrayList<>(lanes);
        int added = 0;
        for (int i = 0; i < out.size(); i++) {
            Lane l = out.get(i);
            boolean south = l.from().z() <= l.to().z();
            Pos north = south ? l.from() : l.to();
            if (north.z() > g.minZ() + 1) continue;
            int c = north.x();
            List<Placement> cells = new ArrayList<>();
            for (int d = 0; d <= halfWidth; d++)
                for (int x : d == 0 ? new int[] {c} : new int[] {c - d, c + d}) {
                    int col = x - g.minX();
                    if (col < 0 || col >= TileArt.SIZE || nearestLane(out, x, halfWidth) != i) continue;
                    BlockSpec spec = row == null ? null : row.get(col, 0);
                    int dy = 0;
                    if (spec == null && upRow != null) { // no column holds both levels
                        spec = upRow.get(col, 0);
                        dy = 1;
                    }
                    if (spec == null) continue;
                    Pos pos = new Pos(x, g.artY() + dy, z);
                    String item = catalog.placementItem(spec).orElse(null);
                    if (item == null || !w.block(pos).isAir()) continue; // placed, or a wrong block: the noobline pass's
                    cells.add(new Placement(pos, spec, item));
                }
            if (cells.isEmpty()) continue;
            added += cells.size();
            List<Placement> ps = new ArrayList<>(l.placements().size() + cells.size());
            if (south) ps.addAll(cells);
            ps.addAll(l.placements());
            if (!south) ps.addAll(cells);
            Pos end = new Pos(c, north.y(), z);
            out.set(i, new Lane(l.index(), south ? end : l.from(), south ? l.to() : end, List.copyOf(ps)));
        }
        if (added > 0) log.debug("{}: {} cells of the noobline row go with this pass's lanes", label(), added);
        return out;
    }

    // ---- build.suppressionMergeLevels: both levels of a layer in one sweep (docs/COLOR_SUPPRESSION.md) ----

    /**
     * {@code build.suppressionMergeLevels}: the level-1 main pass whose cells level-0 main pass {@code t} places with
     * its own lanes, or null: the pass of the same layer at level 1 holding the map rows, not yet verified. The stored
     * order is unchanged (level 0 comes first), so the ride still follows the job's last pass.
     */
    static Job.@Nullable TileState levelTwin(Job j, Job.TileState t) {
        Job.SuppressionPass main = suppressionPass(j, t);
        if (main == null || main.noobline || main.level != 0) return null;
        for (int i = 0; i < j.suppression.size(); i++) {
            Job.SuppressionPass p = j.suppression.get(i);
            if (p.layer != main.layer || p.level != 1 || p.noobline) continue;
            Job.TileState u = j.tile(new TileCoord(i, 0));
            return u == null || u.status == TileStatus.STORED || u.status == TileStatus.FAILED ? null : u;
        }
        return null;
    }

    /**
     * The level-1 noobline pass whose row level-0 main pass {@code t} carries with {@code build.suppressionMergeLevels}
     * and {@code build.suppressionMergeNoobline} (its layer's level-1 noobline pass, not yet verified), or null. Only
     * while {@code t} carries the level-1 cells ({@link #levelTwin}).
     */
    Job.@Nullable TileState raisedRowTwin(Job j, Job.TileState t) {
        if (!config.build.suppressionMergeLevels || !config.build.suppressionMergeNoobline || levelTwin(j, t) == null) return null;
        Job.SuppressionPass main = suppressionPass(j, t);
        for (int i = 0; i < j.suppression.size(); i++) {
            Job.SuppressionPass p = j.suppression.get(i);
            if (p.layer != main.layer || p.level != 1 || !p.noobline) continue;
            Job.TileState u = j.tile(new TileCoord(i, 0));
            return u == null || u.status == TileStatus.STORED || u.status == TileStatus.FAILED ? null : u;
        }
        return null;
    }

    /** {@link #raisedRowMerges} per "job/main x/row x". */
    private final Map<String, Boolean> raisedRowMerge = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The material rule of {@link #nooblineMerges} for the level-1 noobline row carried by level-0 main pass
     * {@code main}: every item of the row is one the sweep places anyway (its own cells or the level-1 cells it
     * carries). Read once (three tiles from the store); a tile that cannot be read: no merge.
     */
    private boolean raisedRowMerges(Job j, Job.TileState main, Job.TileState row) {
        String key = j.id + "/" + main.x + "/" + row.x;
        Boolean known = raisedRowMerge.get(key);
        if (known != null) return known;
        boolean merges;
        try {
            Job.TileState up = levelTwin(j, main);
            Set<String> items = artItems(store.loadTile(j.id, row.coord()), 0);
            Set<String> own = artItems(store.loadTile(j.id, main.coord()), -1);
            if (up != null) own.addAll(artItems(store.loadTile(j.id, up.coord()), -1));
            Set<String> missing = new TreeSet<>(items);
            missing.removeAll(own);
            merges = !items.isEmpty() && missing.isEmpty();
            if (!merges && !items.isEmpty()) {
                log.info("{}: the level-1 noobline row needs {}, which this sweep does not place: the row keeps its own pass",
                    label(j, main), missing);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("level-1 noobline row of {} not compared with its sweep ({}): its own pass builds it", label(j, main),
                e.toString());
            merges = false;
        }
        raisedRowMerge.put(key, merges);
        return merges;
    }

    /** Arts of level-1 main passes by "job/pass index", read once a pass ({@link #withLevels}); the current job's only. */
    private final Map<String, TileArt> levelArts = new HashMap<>();

    /**
     * {@code build.suppressionMergeLevels}: the lanes of the current level-0 main pass planned over its own cells and
     * the cells of its layer's level-1 main pass still air in the world ({@link #levelTwin}), those one block higher
     * ({@link LevelMerge}); null when the pass carries none (the switch off, another pass, a band, no airplace: the
     * planner's plan as before). The pass's plan stays its own level: INSPECT, VERIFY and its repair lanes, the stock
     * check and the progress are this level's; the raised cells a lane leaves are the level-1 pass's. A pass whose lanes
     * carry raised cells is marked ({@link Job.TileState#carriesRaised}): the level-1 noobline pass then plans its row
     * alone.
     */
    private @Nullable List<Lane> withLevels(WorldView w, int halfWidth) {
        if (!config.build.suppressionMergeLevels || !suppressionTile() || plan == null || plan.band != null) return null;
        if (PlaceMode.of(config.build) != PlaceMode.AIRPLACE || !bot.airPlaceAvailable()) return null;
        Job.TileState twin = levelTwin(job, tile);
        if (twin == null) return null;
        PlatformGeometry g = plan.geo;
        PlatformGeometry g1 = geometryFor(job, twin, platform);
        if (g1.minX() != g.minX() || g1.minZ() != g.minZ() || g1.artY() != g.artY() + 1) return null;
        String key = job.id + "/" + twin.x;
        TileArt up = levelArts.get(key);
        if (up == null) {
            try {
                up = store.loadTile(job.id, twin.coord());
            } catch (IOException e) {
                log.warn("level-1 cells of {} not read ({}): their own pass builds them", label(), e.toString());
                return null;
            }
            levelArts.keySet().removeIf(k -> !k.startsWith(job.id + "/"));
            levelArts.put(key, up);
        }
        // a raised cell placed, or a wrong block there: the level-1 pass's (its VERIFY)
        List<Lane> lanes = LevelMerge.lanes(planner, plan.art, up, g, catalog, halfWidth, plan.notBuildableIn(w),
            p -> !w.block(p.pos()).isAir());
        int raised = LevelMerge.raisedCells(lanes);
        twin.placedAhead = Math.max(0, twin.total - raised); // the progress counts what this sweep placed for it
        if (raised > 0 && !tile.carriesRaised) {
            tile.carriesRaised = true;
            saveJob(job);
            log.info("{}: its lanes carry {} cells of the level-1 pass, one block higher (build.suppressionMergeLevels)",
                label(), raised);
        } else if (raised > 0) {
            log.debug("{}: {} level-1 cells go with this pass's lanes", label(), raised);
        }
        return lanes;
    }

    /**
     * A placement one block above the current pass's own level ({@code build.suppressionMergeLevels}): a cell of
     * another pass. Inside the map rows it is the level-1 main pass's and counts there for the progress
     * ({@link Job.TileState#placedAhead}); it never counts for the pass whose lanes placed it. Live 2026-11-15
     * (j0010): the lower sweep counted the raised cells as its own until its total was reached, the upper pass stood
     * at 0 until its own verify, and the status said 85% and "~7h 20m left" with every block in place.
     */
    private boolean raisedPlaced(Placement p) {
        if (job == null || tile == null || plan == null || !tile.carriesRaised) return false;
        PlatformGeometry g = plan.geo;
        if (p.pos().y() != g.artY() + 1) return false;
        Job.TileState twin = levelTwin(job, tile);
        int px = p.pos().x() - g.minX();
        int pz = p.pos().z() - g.minZ();
        if (twin != null && px >= 0 && pz >= 0 && px < TileArt.SIZE && pz < TileArt.SIZE
            && twin.placedAhead < twin.total) twin.placedAhead++;
        return true;
    }

    /** Some level-0 main pass of layer {@code layer} of {@code j} carried raised cells ({@link Job.TileState#carriesRaised}). */
    static boolean raisedCarried(Job j, int layer) {
        if (j == null || j.suppression == null) return false;
        for (int i = 0; i < j.suppression.size(); i++) {
            Job.SuppressionPass p = j.suppression.get(i);
            if (p.layer != layer || p.level != 0 || p.noobline) continue;
            Job.TileState t = j.tile(new TileCoord(i, 0));
            if (t != null && t.carriesRaised) return true;
        }
        return false;
    }

    /** The lane of {@code lanes} whose walking column is nearest column {@code x} (west on ties), within {@code halfWidth}; -1: none. */
    private static int nearestLane(List<Lane> lanes, int x, int halfWidth) {
        int best = -1, bestD = Integer.MAX_VALUE;
        for (int i = 0; i < lanes.size(); i++) {
            int d = Math.abs(lanes.get(i).from().x() - x);
            if (d <= halfWidth && d < bestD) {
                best = i;
                bestD = d;
            }
        }
        return best;
    }

    /** Reads what the tiles of {@code opts} not read yet need, off the tick thread; true while it runs. */
    private boolean readTileNeeds(List<Option> opts) {
        List<Candidate> todo = new ArrayList<>();
        for (Option o : opts) if (!tileNeeds.containsKey(tileKey(o.c().job(), o.c().tile()))) todo.add(o.c());
        if (todo.isEmpty()) return false;
        run(new AsyncTask<>("read the materials of " + todo.size() + " tiles", executor, () -> {
            Map<String, Map<String, Integer>> out = new HashMap<>();
            for (Candidate x : todo) {
                Map<String, Integer> needs;
                try {
                    needs = new TilePlan(store.loadTile(x.job().id, x.tile().coord()),
                        geometryFor(x.job(), x.tile(), x.platform()), catalog,
                        fixtures(x.platform())).allNeeds();
                } catch (IOException | RuntimeException e) {
                    // unreadable: taken first, and SELECT's own read fails the job as before
                    needs = Map.of();
                }
                out.put(tileKey(x.job(), x.tile()), needs);
            }
            return out;
        }, tileNeeds::putAll), null, r -> log.warn("reading the tiles' materials failed: {}", r));
        return true;
    }

    /** One stock snapshot: the stock index's totals plus the inventory. */
    private Map<String, Integer> available() {
        Map<String, Integer> m = new HashMap<>(coord.stock().totals());
        bot.inventory().contents().forEach((id, n) -> m.merge(id, n, Integer::sum));
        return m;
    }

    /**
     * Another tile with nothing placed that the stock index (plus the inventory) covers completely, read before
     * ({@link #tileNeeds}); null when none, or {@code build.skipShortTiles} is off.
     */
    private Candidate coveredElsewhere() {
        if (!config.build.skipShortTiles) return null;
        List<Job> jobs = coord.jobs();
        Map<String, Integer> availAll = available();
        for (Option o : freshOptions(jobs)) {
            if (isCurrent(o.c())) continue;
            Map<String, Integer> needs = tileNeeds.get(tileKey(o.c().job(), o.c().tile()));
            if (needs != null && PartialBuild.missing(needs, creditedAvail(availAll, needs, o)) == 0) return o.c();
        }
        return null;
    }

    private boolean waitingDue(Job j, long now) {
        Long last = waitingCheckedAt.get(j.id);
        if (last != null && now - last < waitingRecheckMs()) return false;
        Map<String, Integer> needs = waitingNeeds.get(j.id);
        if (needs != null && stockFresh(now) && !coord.stock().shortfall(needs).isEmpty()) {
            waitingCheckedAt.put(j.id, now);
            log.debug("job {} still short (fresh stock)", j.id);
            return false;
        }
        return true;
    }

    /**
     * Every material container has a snapshot younger than the {@code stockMaxAgeMinutes} ceiling. With no ceiling (0,
     * the default) never: a waiting job is then re-checked every {@code waitingRecheckMinutes} by STOCK_CHECK's targeted
     * re-look, since only a look at the chests can tell that someone restocked them.
     */
    private boolean stockFresh(long now) {
        if (materialContainers == null || config.build.stockMaxAgeMinutes <= 0) return false;
        long maxAge = config.build.stockMaxAgeMinutes * 60_000L;
        StockIndex st = coord.stock();
        synchronized (st) {
            for (Pos p : scoped(materialContainers)) {
                StockIndex.Snapshot s = st.containers.get(p.key());
                if (s == null || now - s.scannedAt > maxAge) return false;
            }
        }
        return true;
    }

    /**
     * {@code build.suppressionMergeNoobline}: the pass to build when {@code t} is the next open one. A noobline pass
     * with nothing placed waits for the main pass of its level ({@link #mainOfNoobline}), whose lanes place its row:
     * that main pass, when this bot holds its unit. Every other tile: {@code t}.
     */
    private Job.TileState mainFirst(Job j, Job.TileState t) {
        if (!config.build.suppressionMergeNoobline || !j.isSuppression() || t.placed > 0) return t;
        Job.TileState main = mainOfNoobline(j, t);
        return main != null && coord.unit(j, main) != null && nooblineMerges(j, main, t) ? main : t;
    }

    /** {@link #nooblineMerges} per "job/main x/noobline x"; read on the tick thread and the tile loader's. */
    private final Map<String, Boolean> nooblineMerge = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * {@code build.suppressionMergeNoobline}: the row of noobline pass {@code noob} goes with the lanes of its main pass
     * {@code main} only when the main pass places every item of the row itself. Otherwise each of the main pass's loads
     * would carry the row's item in a slot of its own for a few cells at its lanes' ends: on the bottom layer of the
     * local 10x run of 2026-11-14 (a noobline row of moss, a main pass without moss) that was two restock rounds more
     * than the noobline pass saves, one of them a first load of 7 cells. Such a level keeps its stored order: the
     * noobline pass first, with its own lanes, as without the switch. Read once per level (two tiles from the store);
     * a tile that cannot be read: no merge.
     */
    private boolean nooblineMerges(Job j, Job.TileState main, Job.TileState noob) {
        String key = j.id + "/" + main.x + "/" + noob.x;
        Boolean known = nooblineMerge.get(key);
        if (known != null) return known;
        boolean merges;
        try {
            Set<String> row = artItems(store.loadTile(j.id, noob.coord()), 0);
            Set<String> own = artItems(store.loadTile(j.id, main.coord()), -1);
            Set<String> missing = new TreeSet<>(row);
            missing.removeAll(own);
            merges = !row.isEmpty() && missing.isEmpty();
            if (!merges && !row.isEmpty()) {
                log.info("{}: its noobline row needs {}, which this pass does not place: the row keeps its own pass",
                    label(j, main), missing);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("noobline row of {} not compared with its main pass ({}): its own pass builds it", label(j, main),
                e.toString());
            merges = false;
        }
        nooblineMerge.put(key, merges);
        return merges;
    }

    /** The items {@code art} places, in row {@code row} only (0..127) or everywhere (-1). */
    private Set<String> artItems(TileArt art, int row) {
        Set<String> out = new HashSet<>();
        for (int pz = row < 0 ? 0 : row; pz <= (row < 0 ? TileArt.SIZE - 1 : row); pz++)
            for (int px = 0; px < TileArt.SIZE; px++) {
                BlockSpec spec = art.get(px, pz);
                if (spec != null) out.add(catalog.placementItem(spec).orElse(spec.id()));
            }
        return out;
    }

    private static Job.TileState openTile(Job j) {
        for (Job.TileState t : j.tiles) if (t.status == TileStatus.ACTIVE) return t;
        for (Job.TileState t : j.tiles) if (t.status == TileStatus.PENDING) return t;
        return null;
    }

    /** {@link #openTile} among the tiles the coordination gives this bot a unit for ({@link Coordination#unit}), or null. */
    private Job.@Nullable TileState grantedTile(Job j) {
        for (Job.TileState t : j.tiles) if (t.status == TileStatus.ACTIVE && coord.unit(j, t) != null) return t;
        for (Job.TileState t : j.tiles) if (t.status == TileStatus.PENDING && coord.unit(j, t) != null) return t;
        return null;
    }

    private PlatformCfg choosePlatform(Job j, Job.TileState t, List<Job> jobs) {
        if (t.status == TileStatus.ACTIVE && t.platformId != null) {
            PlatformCfg p = usablePlatform(t.platformId);
            if (p != null && p.isColorSuppression() == j.isSuppression()) return p;
        }
        // avoid platforms holding another (waiting) job's partial tile when there is a choice; with partial builds never
        // one holding placed blocks of another tile, this job's own included (a partly built map waiting for
        // materials): it would be nuked for this one
        Set<String> reserved = new HashSet<>();
        Set<String> built = new HashSet<>();
        for (Job o : jobs) {
            if (o.status == null || o.status.terminal()) continue;
            for (Job.TileState ot : o.tiles) {
                if (ot == t || ot.status != TileStatus.ACTIVE || ot.platformId == null) continue;
                if (o != j) reserved.add(ot.platformId);
                if (config.build.partialBuild && ot.placed > 0) built.add(ot.platformId);
            }
        }
        PlatformCfg first = null;
        for (String id : j.platformIds) {
            PlatformCfg p = usablePlatform(id);
            if (p == null || built.contains(p.id) || p.isColorSuppression() != j.isSuppression()) continue;
            if (first == null) first = p;
            if (!reserved.contains(p.id)) return p;
        }
        return first;
    }

    /**
     * {@link #fixtures(PlatformCfg)} for the plan of tile {@code t}. With {@code build.suppressionMergeNoobline} a
     * noobline pass's plan is its row alone: the 127 rows south of it, the map rows its geometry covers one row down,
     * are left out of every check (the main pass of its level is built first and stands there). A level whose row
     * keeps its own pass ({@link #nooblineMerges}) has the plan it has without the switch. A level-1 noobline pass
     * whose layer's level-0 sweep carried the level-1 cells ({@code build.suppressionMergeLevels},
     * {@link #raisedCarried}) plans its row alone too, whatever the switches say now: those map rows hold them.
     */
    private Set<Pos> fixtures(Job j, Job.TileState t, PlatformCfg p) {
        Set<Pos> s = fixtures(p);
        Job.SuppressionPass pass = suppressionPass(j, t);
        if (pass == null || !pass.noobline || !p.isColorSuppression()) return s;
        // also with the switch off again, once the main pass was built first (a job that ran across the change), and
        // for a level-1 row once its layer's level-0 sweep carried the level-1 cells (build.suppressionMergeLevels)
        if (!(pass.level == 1 && raisedCarried(j, pass.layer)) && !mainBuiltFirst(j, t)) {
            if (!config.build.suppressionMergeNoobline) return s;
            Job.TileState main = mainOfNoobline(j, t);
            if (main == null || !nooblineMerges(j, main, t)) return s;
        }
        PlatformGeometry g = geometryFor(j, t, p);
        for (int pz = 1; pz < TileArt.SIZE; pz++) for (int px = 0; px < TileArt.SIZE; px++) s.add(g.cell(px, pz));
        return s;
    }

    /** Actuator, probe and collection blocks of a platform; any that sit on the art layer are left out of every check. */
    private static Set<Pos> fixtures(PlatformCfg p) {
        Set<Pos> s = new HashSet<>();
        if (p.clear != null) {
            for (ActuatorCfg a : p.clear) {
                if (a == null) continue;
                if (a.pos != null) s.add(a.pos);
                if (a.probe != null) s.add(a.probe);
            }
        }
        for (ActuatorCfg a : new ActuatorCfg[]{p.collectionOn, p.collectionOff}) if (a != null && a.pos != null) s.add(a.pos);
        if (p.collectionIndicator != null) s.add(p.collectionIndicator);
        return s;
    }

    private PlatformCfg usablePlatform(String id) {
        PlatformCfg p = platformById(id);
        if (p == null || !p.enabled || p.kind == null) return null;
        return PLATFORM_KINDS.contains(p.kind.toUpperCase(Locale.ROOT)) || p.isColorSuppression() ? p : null;
    }

    // ---- colour suppression (docs/COLOR_SUPPRESSION.md): each tile of such a job is one build pass ----

    /**
     * Where tile {@code t} of {@code j} is built on {@code p}: the platform's own art layer, or for a pass of a
     * colour-suppression map the level it builds (glass + 1 + level of its layer) and, for the noobline pass, the row
     * north of the map (its tile row 0 holds the noobline).
     */
    static PlatformGeometry geometryFor(Job j, Job.TileState t, PlatformCfg p) {
        Job.SuppressionPass pass = suppressionPass(j, t);
        if (pass == null || !p.isColorSuppression() || p.glassY == null || pass.layer >= p.glassY.size())
            return p.geometry();
        return new PlatformGeometry(p.minX, p.minZ - (pass.noobline ? 1 : 0), p.glassY.get(pass.layer) + 1 + pass.level);
    }

    /** The pass tile {@code t} of a colour-suppression job builds, or null (a map tile). */
    static Job.SuppressionPass suppressionPass(Job j, Job.TileState t) {
        if (j == null || t == null || !j.isSuppression() || t.z != 0 || t.x < 0 || t.x >= j.suppression.size()) return null;
        return j.suppression.get(t.x);
    }

    /** The current tile is a pass of a colour-suppression map on its platform. */
    private boolean suppressionTile() {
        return platform != null && platform.isColorSuppression() && suppressionPass(job, tile) != null;
    }

    /**
     * Before every step (tasks read the zones when they are made): the current colour-suppression job's openings; with
     * none, the openings of before as long as the bot's feet or head are still in one of them, so that after a pass, a
     * cancel or the last pass it walks out by the access path and never through the no-go zones around the layers
     * (local test 2026-10-12: the zones back while the bot stood on the bottom glass, it "left the zone" across the
     * walkways and took a fall). Empty otherwise.
     */
    private void updateOpenings() {
        List<MapArtConfig.RegionCfg> want = openingsFor(job, platform);
        // build.elytraKitFarTrip: the kit's trip to a far rocket chest never takes the ride's ways (its walkway runs
        // 120 blocks toward the sorter and ends in the booth: live 2026-11-15 the walk ended up on it)
        if (!want.isEmpty() && ctx != null && ctx.kitFarClosed) want = kitFarOpenings(want);
        // a ride paused for a restart: the lane-end hold's own cell is walked into (and nothing else of the rail field)
        if (!want.isEmpty() && tile != null && tile.ridePause != null) want = withPauseCell(want, tile.ridePause);
        List<MapArtConfig.RegionCfg> now = config.site.openings;
        if (!want.isEmpty()) {
            config.site.openings = want;
            config.site.hazards = csHazards(platform);
            config.site.repairable = List.copyOf(SiteRules.suppressionArtCells(platform));
            return;
        }
        config.site.repairable = List.of();
        // still up there (a cancel, after the last pass, a restart while it stood on the layers): the openings and
        // lethal edges of that platform stay until it is down, so it leaves by the access path
        Pos feet = botFeet();
        for (PlatformCfg p : config.site.platforms) {
            if (p == null || !p.enabled || !p.isColorSuppression()) continue;
            List<MapArtConfig.RegionCfg> o = csOpenings(p);
            if (SiteRules.regionAt(o, feet.x(), feet.y(), feet.z()) != null
                || SiteRules.regionAt(o, feet.x(), feet.y() + 1, feet.z()) != null) {
                if (now == null || now.isEmpty()) log.info("the bot stands on color-suppression platform {}: its "
                    + "openings and lethal edges apply until it is down", p.id);
                config.site.openings = List.copyOf(o);
                config.site.hazards = csHazards(p);
                return;
            }
        }
        config.site.openings = List.of();
        config.site.hazards = List.of();
    }

    /** The layers' lethal edges ({@link #hazardsFor}) plus the ride's ({@code ride.edges}) when the platform rides. */
    private List<MapArtConfig.RegionCfg> csHazards(PlatformCfg p) {
        List<MapArtConfig.RegionCfg> h = hazardsFor(p);
        if (p.ride == null || !p.ride.configured()) return h;
        boolean lap = p.ride.preloads() && !p.ride.preload.edges.isEmpty();
        if (p.ride.edges.isEmpty() && !lap) return h;
        List<MapArtConfig.RegionCfg> all = new ArrayList<>(h);
        all.addAll(p.ride.edges);
        if (lap) all.addAll(p.ride.preload.edges);
        return List.copyOf(all);
    }

    /** Lethal edges of colour-suppression platforms ({@link EdgeHazards}), read once complete, by platform id. */
    private final Map<String, List<MapArtConfig.RegionCfg>> hazardCache = new HashMap<>();

    private List<MapArtConfig.RegionCfg> hazardsFor(PlatformCfg p) {
        List<MapArtConfig.RegionCfg> h = hazardCache.get(p.id);
        if (h != null) return h;
        WorldView w = bot.world();
        EdgeHazards.Result r = EdgeHazards.compute(p, new EdgeHazards.Blocks() {
            @Override
            public boolean loaded(int x, int y, int z) {
                return w.isLoaded(new Pos(x, y, z));
            }

            @Override
            public BlockSpec block(int x, int y, int z) {
                return w.block(new Pos(x, y, z));
            }
        });
        if (r.complete()) {
            hazardCache.put(p.id, r.zones());
            log.info("color-suppression platform {}: {} lethal edge cells, kept clear by {} no-go boxes", p.id,
                r.lethal(), r.zones().size());
        }
        return r.zones();
    }

    /**
     * Lanes of a colour-suppression pass whose ends lie in or next to a no-go zone or lethal-edge box end one cell
     * further in, so the walk never reaches the edge; the cells at the edge are placed from there (airplace reach).
     */
    private List<Lane> safeLanes(List<Lane> lanes) {
        if (!suppressionTile()) return lanes;
        // every zone, not only the lethal edges: a walk overshooting its end into a no-go zone is stopped there too
        // (local 128x128 run: two top-layer lanes stepped past the south edge onto the machinery's side)
        NoGoZones hz = NoGoZones.of(config.site.noGoZones, config.site.openings, config.site.hazards);
        // one more cell: a walk stopping at its end overshoots up to half a block (local test: lanes entered the margin)
        // along the lane only (suppressionLaneEndFallback): a cell beside the walking line is never walked into, and in the
        // last column its east neighbour lies in the machinery zone: every cell of the lane was "unsafe" and the lane
        // collapsed to one cell (local CS run 2026-10-14, lane 14 of the bottom pass: BLOCKED)
        boolean axis = config.build.suppressionLaneEndFallback;
        List<Lane> out = new ArrayList<>(lanes.size());
        for (Lane l : lanes) {
            int ax = axis ? Integer.signum(l.to().x() - l.from().x()) : 0;
            int az = axis ? Integer.signum(l.to().z() - l.from().z()) : 0;
            boolean along = ax != 0 || az != 0;
            out.addAll(clampLanes(List.of(l), p -> hz.at(p) != null
                || (along ? hz.at(p.x() + ax, p.y(), p.z() + az) != null || hz.at(p.x() - ax, p.y(), p.z() - az) != null
                    : hz.at(p.x() + 1, p.y(), p.z()) != null || hz.at(p.x() - 1, p.y(), p.z()) != null
                    || hz.at(p.x(), p.y(), p.z() + 1) != null || hz.at(p.x(), p.y(), p.z() - 1) != null),
                axis ? p -> hz.at(p) != null : null));
        }
        int moved = 0;
        for (int i = 0; i < out.size(); i++) if (out.get(i) != lanes.get(i)) moved++;
        log.debug("safe lanes: {} of {} lane ends moved ({} hazard boxes, {} openings)", moved, lanes.size(),
            config.site.hazards.size(), config.site.openings.size());
        return out;
    }

    /** {@link #safeLanes}: each end moved along the lane towards the other until {@code unsafe} is false. Pure. */
    static List<Lane> clampLanes(List<Lane> lanes, Predicate<Pos> unsafe) {
        return clampLanes(lanes, unsafe, null);
    }

    /**
     * {@link #clampLanes(List, Predicate)}; with {@code relaxed} (the cell itself in a zone), an end with no cell beside
     * it that passes {@code unsafe} takes the nearest that passes {@code relaxed}.
     */
    static List<Lane> clampLanes(List<Lane> lanes, Predicate<Pos> unsafe, @Nullable Predicate<Pos> relaxed) {
        List<Lane> out = new ArrayList<>(lanes.size());
        for (Lane l : lanes) {
            Pos a = l.from(), b = l.to();
            int dz = Integer.signum(b.z() - a.z()), dx = Integer.signum(b.x() - a.x());
            while (unsafe.test(a) && !a.equals(b)) a = new Pos(a.x() + dx, a.y(), a.z() + dz);
            while (unsafe.test(b) && !b.equals(a)) b = new Pos(b.x() - dx, b.y(), b.z() - dz);
            // a lane lying wholly at an edge (the noobline pass is one row): the nearest safe cell beside it
            a = nearestSafe(a, unsafe);
            b = nearestSafe(b, unsafe);
            if (relaxed != null) {
                if (unsafe.test(a)) a = nearestSafe(a, relaxed);
                if (unsafe.test(b)) b = nearestSafe(b, relaxed);
            }
            out.add(a.equals(l.from()) && b.equals(l.to()) ? l : new Lane(l.index(), a, b, l.placements()));
        }
        return out;
    }

    /** {@code p}, or the nearest cell beside it (x first, then z, up to 4 away) that is not {@code unsafe}. */
    private static Pos nearestSafe(Pos p, Predicate<Pos> unsafe) {
        if (!unsafe.test(p)) return p;
        for (int r = 1; r <= 4; r++)
            for (int[] d : new int[][]{{-r, 0}, {r, 0}, {0, r}, {0, -r}}) {
                Pos q = new Pos(p.x() + d[0], p.y(), p.z() + d[1]);
                if (!unsafe.test(q)) return q;
            }
        return p;
    }

    private List<MapArtConfig.RegionCfg> kitFarBase;
    private List<MapArtConfig.RegionCfg> kitFarOpenings;

    /**
     * {@code base} (a colour-suppression job's openings) without the ride's ({@code ride.access}, the preload lap's):
     * the platform's own ({@link SiteRules#suppressionOpenings}). The same list while {@code base} does not change.
     */
    private List<MapArtConfig.RegionCfg> kitFarOpenings(List<MapArtConfig.RegionCfg> base) {
        if (kitFarOpenings != null && base.equals(kitFarBase)) return kitFarOpenings;
        List<MapArtConfig.RegionCfg> o = SiteRules.suppressionOpenings(platform);
        kitFarBase = base;
        kitFarOpenings = o.isEmpty() ? base : List.copyOf(o);
        return kitFarOpenings;
    }

    private Pos pauseCellFor;
    private List<MapArtConfig.RegionCfg> pauseCellBase;
    private List<MapArtConfig.RegionCfg> pauseCellOpenings;

    /** {@code base} plus the cell {@code hold} (feet and head room); the same list while neither changes. */
    private List<MapArtConfig.RegionCfg> withPauseCell(List<MapArtConfig.RegionCfg> base, Pos hold) {
        if (pauseCellOpenings != null && hold.equals(pauseCellFor) && base.equals(pauseCellBase)) return pauseCellOpenings;
        MapArtConfig.RegionCfg cell = new MapArtConfig.RegionCfg();
        cell.min = hold;
        cell.max = hold.offset(0, 2, 0);
        cell.name = "ride: the paused ride's lane-end hold";
        List<MapArtConfig.RegionCfg> o = new ArrayList<>(base);
        o.add(cell);
        pauseCellFor = hold;
        pauseCellBase = base;
        pauseCellOpenings = List.copyOf(o);
        return pauseCellOpenings;
    }

    /** The no-go zones and protected regions a job opens while it is the current one: none unless colour suppression. */
    private static List<MapArtConfig.RegionCfg> openingsFor(Job j, PlatformCfg p) {
        return j != null && j.isSuppression() ? csOpenings(p) : List.of();
    }

    /** A colour-suppression platform's openings: the art volumes and access boxes, plus the ride's when it rides. */
    private static List<MapArtConfig.RegionCfg> csOpenings(PlatformCfg p) {
        List<MapArtConfig.RegionCfg> o = new ArrayList<>(SiteRules.suppressionOpenings(p));
        if (p != null && p.ride != null && p.ride.configured()) {
            o.addAll(p.ride.access);
            if (p.ride.preloads()) o.addAll(p.ride.preload.access);
        }
        return List.copyOf(o);
    }

    private PlatformCfg platformById(String id) {
        if (id == null) return null;
        for (PlatformCfg p : config.site.platforms) if (id.equals(p.id)) return p;
        return null;
    }

    /** All tiles done: DONE (or FAILED if a tile failed), with a summary. */
    private void completeJob(Job j) {
        if (j.status.terminal()) return;
        boolean allStored = !j.tiles.isEmpty();
        for (Job.TileState t : j.tiles) allStored &= t.status == TileStatus.STORED;
        boolean rode = false;
        // ridden: the reset was seen (ride.waitForReset), or the launch is on record and the map stored
        boolean settled = false;
        for (Job.TileState t : j.tiles) {
            rode |= t.rideSettled || (t.ridePressed && t.status == TileStatus.STORED);
            settled |= t.rideSettled;
        }
        boolean cs = allStored && j.isSuppression() && !rode;
        j.status = cs ? JobStatus.READY_FOR_RIDE : allStored ? JobStatus.DONE : JobStatus.FAILED;
        j.statusDetail = cs ? "printed and verified: ready for the ride" : allStored ? "" : "some tiles failed";
        saveJob(j);
        waitingCheckedAt.remove(j.id);
        waitingNeeds.remove(j.id);
        lastShortAlert.keySet().removeIf(k -> k.startsWith(j.id + " "));
        shortMaps.keySet().removeIf(k -> k.startsWith(j.id + " "));
        skipped.removeIf(m -> m.job().id.equals(j.id));
        tileNeeds.keySet().removeIf(k -> k.startsWith(j.id + " "));
        log.info("job {} {}", j.id, j.status);
        if (cs) {
            // the one always-on line of a colour-suppression map (docs/COLOR_SUPPRESSION.md): no map, no clear follow
            String where = j.tiles.getFirst().platformId == null ? "" : " on " + j.tiles.getFirst().platformId;
            notifier.info("Ready for the ride: " + j.name, "#" + j.id + " " + j.name + ": both layers printed and "
                + "verified" + where + ". The ride, the lock and the reset are manual.");
        } else if (allStored && j.isSuppression()) {
            notifier.info("Job done: " + j.name, "#" + j.id + " " + j.name + ": the color-suppression map was printed ("
                + j.tiles.size() + " passes), ridden, locked and stored" + (settled ? "; the muncher reset." : "."));
        } else if (allStored) notifier.info("Job done: " + j.name, jobSummary(j));
        else alert("Job failed: " + j.name, j.statusDetail + "\n(#" + j.id + ")");
    }

    // =====================================================================================
    // INSPECT / STOCK_CHECK / GEAR
    // =====================================================================================

    private void stepInspect() {
        if (tile.status == TileStatus.STORED) {
            go(afterStore());
            return;
        }
        if (lostRunDue()) {
            log.warn("{}: a ride of this map was lost and its cart is not back: the muncher's run is finished first", label());
            go(Phase.RIDE_RESET);
            return;
        }
        if (awaitPlatformLoaded()) return;
        WorldView w = bot.world();
        TilePlan.Survey s = plan.survey(w);
        if (bandUnit()) {
            inspectBand(w, s);
            return;
        }
        if (mainStandsAlready(w, s)) return;
        if (layerStillClearing(s)) return;
        if (s.clear()) {
            log.info("platform {} is clear", platform.id);
            replan(w); // lanes + needs cached while the platform is in view
            go(Phase.STOCK_CHECK);
            return;
        }
        // a colour-suppression pass with blocks placed and not yet begun here: the main pass a noobline pass found
        // standing (mainStandsAlready), or one no noobline pass looked at before it (mainStandsHere)
        if ((resumeCandidate || (suppressionTile() && (tile.placed > 0 || mainStandsHere(s)))) && !s.fluid()
            && s.matchRatio() >= RESUME_MATCH_RATIO) {
            replan(w); // lanes + needs cached while the platform is in view
            saveJob(job);
            log.info("platform {} holds this tile: {}/{} correct, {} wrong; resuming", platform.id, s.correct(), plan.total, s.wrong().size());
            go(Phase.STOCK_CHECK);
            return;
        }
        if (!s.fluid() && s.wrong().isEmpty()) {
            // every block there is this tile's own block in its own cell: nothing to take away, so the build is resumed
            // even when nobody marked the tile begun
            replan(w);
            saveJob(job);
            log.info("platform {} holds part of this tile and no other block: {}/{} correct; resuming", platform.id, s.correct(), plan.total);
            go(Phase.STOCK_CHECK);
            return;
        }
        foreignInspections++;
        String what = s.nonAir() + " non-air blocks" + (s.fluid() ? " and fluid" : "")
            + String.format(Locale.ROOT, " (%.0f%% match this tile)", s.matchRatio() * 100);
        if (suppressionTile()) {
            // the muncher clears these layers during the (manual) ride; the bot never nukes them (docs/COLOR_SUPPRESSION.md)
            block("color-suppression platform " + platform.id + " is not reset: " + what + " at y" + plan.geo.artY()
                + " where " + label() + " builds; clear it by hand (or finish the ride), then resume");
            return;
        }
        if (foreignInspections >= FOREIGN_BLOCK_AT) {
            block("platform " + platform.id + " is still not clear after " + (foreignInspections - 1) + " clears: " + what);
            return;
        }
        log.info("platform {} has foreign blocks: {}; nuke + clear", platform.id, what);
        if (foreignInspections >= FOREIGN_ALERT_AT)
            alertHere("Platform not clear again", "Platform " + platform.id + " still shows " + what + " after a clear. The"
                + " bot clears it again by itself; if this keeps happening, check the clear in game.");
        else notifier.info("Clearing platform first", label() + ": platform " + platform.id + " has " + what + ".");
        go(Phase.NUKE);
    }

    /**
     * INSPECT of a colour-suppression noobline pass whose plan still covers the rows of its level's main pass (not
     * {@link #mainBuiltFirst}), on a level that holds that main pass's own blocks: a print over what a lost run left
     * on the layers (live 2026-11-15; {@link #lostRunSettled}), every pass back to PENDING. The main pass then counts
     * as built first (its {@code placed} is what stands), so this pass's plan is its row alone
     * ({@link #fixtures(Job, Job.TileState, PlatformCfg)}), and the main pass after it resumes over its own blocks
     * (INSPECT: a colour-suppression pass with blocks placed is a resume candidate). Other blocks in the main pass's
     * cells are that pass's to break in VERIFY, as long as they are few: at most {@link #MAIN_STRAYS_MAX} and the
     * level matching its art by {@code RESUME_MATCH_RATIO} (live: four blocks the stalled sweeper had pushed one
     * cell west). True when the plan was made again (INSPECT looks again); false when the pass is inspected as before.
     */
    private boolean mainStandsAlready(WorldView w, TilePlan.Survey s) {
        if (!suppressionTile() || s.fluid() || s.wrong().isEmpty() || mainBuiltFirst(job, tile)) return false;
        Job.SuppressionPass noob = suppressionPass(job, tile);
        if (noob == null || !noob.noobline || tile.x + 1 >= job.suppression.size()) return false;
        Job.SuppressionPass main = job.suppression.get(tile.x + 1);
        Job.TileState m = job.tile(new TileCoord(tile.x + 1, 0));
        if (m == null || main.noobline || main.layer != noob.layer || main.level != noob.level
            || m.status == TileStatus.STORED || m.status == TileStatus.FAILED) return false;
        try {
            TilePlan mp = new TilePlan(store.loadTile(job.id, m.coord()), geometryFor(job, m, platform), catalog,
                fixtures(job, m, platform));
            if (!mp.allLoaded(w)) return false;
            TilePlan.Survey ms = mp.survey(w);
            if (ms.fluid() || ms.correct() == 0 || ms.wrong().size() > MAIN_STRAYS_MAX
                || ms.matchRatio() < RESUME_MATCH_RATIO) return false;
            // what this pass's plan calls wrong must stand (at least partly) in the main pass's cells: else nothing
            // changes by leaving them out
            boolean inMain = false;
            for (Pos p : s.wrong()) {
                if (mp.covers(p)) {
                    inMain = true;
                    break;
                }
            }
            if (!inMain) return false;
            m.placed = ms.correct();
            saveJob(job);
            plan = new TilePlan(store.loadTile(job.id, tile.coord()), geometryFor(job, tile, platform), catalog,
                fixtures(job, tile, platform), bandOf(coord.unit(job, tile)));
            log.info("{}: the level holds {} blocks of its main pass ({} other blocks in its cells, left to that pass):"
                + " that pass counts as built first, this pass is its row alone", label(), ms.correct(), ms.wrong().size());
        } catch (IOException | RuntimeException e) {
            log.warn("{}: the main pass of this row's level could not be read ({}); inspected as before", label(), e.toString());
            return false;
        }
        sleep(1);
        return true;
    }

    /** {@link #mainStandsAlready}: at most this many blocks in the main pass's cells that are not its own. */
    static final int MAIN_STRAYS_MAX = 64;

    /**
     * INSPECT of a colour-suppression main pass not begun before, on a level that holds its own blocks and at most
     * {@link #MAIN_STRAYS_MAX} others in its cells (the caller adds {@code RESUME_MATCH_RATIO}): the rule of
     * {@link #mainStandsAlready} for a main pass whose noobline pass did not look at its cells first. With
     * {@code build.suppressionMergeNoobline} the main pass of a level is chosen before its noobline pass
     * ({@link #mainFirst}), and with {@code build.suppressionMergeLevels} the level-1 noobline pass plans its row alone
     * ({@link #raisedCarried}), so {@link #mainStandsAlready} never runs for it: a print over a lost run's leftover with
     * one stray block, or one wrong block among the cells a level-0 sweep carried, ended BLOCKED "not reset" at this
     * pass. It resumes over what stands; the other blocks are its own to break in VERIFY.
     */
    private boolean mainStandsHere(TilePlan.Survey s) {
        Job.SuppressionPass pass = suppressionPass(job, tile);
        return pass != null && !pass.noobline && s.correct() > 0 && s.wrong().size() <= MAIN_STRAYS_MAX;
    }

    /** {@link #layerStillClearing}: the job and layer waited for, the lowest count seen and the tick it was seen. */
    private String layerWaitKey;
    private int layerWaitBest;
    private long layerWaitSince;

    /**
     * INSPECT of a colour-suppression job's first pass on a layer, on a platform that rides (docs/COLOR_SUPPRESSION.md):
     * the muncher may still be clearing the previous map from that layer (the bottom layer is cleared for minutes
     * after the cart reached the brake). While the layer's art volume (both levels, the noobline row) holds blocks and
     * none of them is this pass's own, the pass waits where the bot stands (never on that layer: INSPECT does not walk
     * onto it), looking again every {@link #SETTLE_POLL_TICKS} ticks, as long as the count keeps going down; a count
     * that has not gone down for {@code ride.settleMinutes} is BLOCKED "not reset". True while waiting (or blocked).
     * Not for a later pass of the layer or a resumed one (the old rule), nor without a configured ride.
     */
    private boolean layerStillClearing(TilePlan.Survey s) {
        if (!suppressionTile() || platform.ride == null || !platform.ride.configured() || job.suppression == null
            || tile.x >= job.suppression.size() || s.correct() > 0) return false;
        int layer = job.suppression.get(tile.x).layer;
        if (mainBuiltFirst(job, tile)) return false; // the layer holds the main pass of this row's level: not the layer's first pass
        // (a noobline pass waiting for this main pass, build.suppressionMergeNoobline, is not an earlier pass of the layer)
        Job.TileState twin = config.build.suppressionMergeNoobline ? nooblineTwin(job, tile) : null;
        for (int i = 0; i < tile.x; i++)
            if (job.suppression.get(i).layer == layer && (twin == null || twin.x != i)) return false;
        int n = layerBlocks(layer);
        if (n <= 0) {
            if (n == 0 && layerWaitKey != null) log.info("the {} layer of {} is empty: {} starts", layerName(layer), platform.id, label());
            if (n == 0) layerWaitKey = null;
            return false; // empty, or not in view (the old rule decides on the pass's own cells)
        }
        String key = job.id + "/" + layer;
        if (!key.equals(layerWaitKey) || n < layerWaitBest) {
            if (!key.equals(layerWaitKey)) log.info("{}: the {} layer of {} still holds {} blocks of the last map: waiting for"
                + " the muncher to clear it", label(), layerName(layer), platform.id, n);
            layerWaitKey = key;
            layerWaitBest = n;
            layerWaitSince = tick;
        }
        int limit = platform.ride.settleMinutes;
        if (tick - layerWaitSince >= limit * 1200L) {
            layerWaitKey = null; // a resume looks again and gives it the time again
            block("color-suppression platform " + platform.id + " is not reset: " + n + " blocks on the " + layerName(layer)
                + " layer, and the count has not gone down for " + limit + " min, where " + label() + " builds; clear it"
                + " by hand, then resume");
            return true;
        }
        note = LAYER_WAIT_NOTE + layerName(layer) + " layer: " + n + " blocks left";
        sleep(SETTLE_POLL_TICKS);
        return true;
    }

    /** Status note while a layer's first pass waits for the muncher to clear the layer (then the layer and the count). */
    public static final String LAYER_WAIT_NOTE = "waiting for the muncher to clear the ";

    private static String layerName(int layer) {
        return layer == io.github.digitalisp.mapart.model.SuppressionArt.BOTTOM ? "bottom" : "top";
    }

    /** At most this many blocks inside a band that are not its own are broken at INSPECT; more fails the band. */
    static final int BAND_FOREIGN_BREAK_MAX = 64;

    /**
     * INSPECT of a lane band: never a nuke, never "Clearing platform first"; the cells
     * outside the band are not looked at (other bots' work, or nobody's yet). The band's own blocks are resumed. A block
     * inside the band that is not its own is broken cell by cell (then placed by BUILD / VERIFY), when every such cell can
     * be broken and there are at most {@link #BAND_FOREIGN_BREAK_MAX} of them (more is another map's art: the band is
     * failed and nothing broken); otherwise, or after two break rounds that leave some, the band is failed with retry,
     * once, with a WARN.
     */
    private void inspectBand(WorldView w, TilePlan.Survey s) {
        if (s.fluid()) {
            failBand("fluid_in_band", "fluid on the art layer inside the band");
            return;
        }
        if (s.wrong().isEmpty()) {
            replan(w);
            saveJob(job);
            if (s.correct() > 0) log.info("platform {}: {}/{} of {}'s cells correct, no other block in it; resuming",
                platform.id, s.correct(), plan.total, unit.spec().band().label());
            else log.info("platform {}: {} is clear; building it", platform.id, unit.spec().band().label());
            go(Phase.STOCK_CHECK);
            return;
        }
        if (s.wrong().size() > BAND_FOREIGN_BREAK_MAX) {
            // that many is another map's art (or a clear that failed), never a few misplaced blocks: nothing is broken
            failBand("foreign_block_in_band", s.wrong().size() + " blocks inside the band are not its own (more than "
                + BAND_FOREIGN_BREAK_MAX + ": another map's art?); none broken");
            return;
        }
        List<Pos> stuck = unbreakable(w, s.wrong());
        if (!stuck.isEmpty()) {
            failBand("foreign_block_in_band", stuck.size() + " blocks inside the band that are not its own cannot be "
                + "broken (e.g. " + w.block(stuck.getFirst()).id() + whyUnbreakable(w, stuck.getFirst()) + ")");
            return;
        }
        Ctx c = ctx;
        if (c.breakRounds++ < 2) {
            log.info("{}: breaking {} blocks inside the band that are not its own, cell by cell (never a nuke)", label(),
                s.wrong().size());
            runBreak(s.wrong(), () -> c.breakRounds--, false,
                r -> log.warn("[INSPECT] band break round {} of 2: {}", c.breakRounds, r));
            return;
        }
        failBand("foreign_block_in_band", s.wrong().size() + " blocks inside the band that are not its own did not break");
    }

    private void stepStockCheck() {
        if (csPaletteRefused()) return;
        if (resumeNearScan()) return;
        if (carriedRound) {
            // a carried round ends here and the step decides again, on a settled inventory: the last placements' slot
            // updates may still be on their way (local 10x run: "1 lime carpet" carried right after all 200 were
            // placed started a round of its own that ran no lane)
            carriedRound = false;
            sleep(POLL_TICKS);
            return;
        }
        if (awaitMaterials()) return; // the tile waits on its platform for materials: nothing until the next look
        Map<String, Integer> needs = currentNeeds();
        if (needs == null) return;
        Ctx c = ctx;
        if (recheckItems != null && c.scanQueue == null) {
            // BUILD made no progress for lack of these items: a targeted look, never a full rescan
            Set<String> items = recheckItems;
            recheckItems = null;
            Set<Pos> extra = items.isEmpty() ? Set.of() : shortageCandidates(items);
            if (!extra.isEmpty()) {
                log.info("no build progress without {}; re-checking {} material containers that hold or could hold them",
                    items, extra.size());
                c.extraScan = extra;
            }
        }
        if (stockRoutine(forceScan, needs.keySet(), c.extraScan)) return;
        forceScan = false;
        Map<String, Integer> want = minusInventory(needs);
        Map<String, Integer> shortfall = stockShortfall(want);
        // carried items never wait: what the inventory covers is built at once, before any walk to the chests, unless
        // the stock (by the index) is worth a trip anyway; the round then takes them along
        if (!shortfall.isEmpty() && !c.relooked && !worthATrip(needs, want, shortfall) && buildCarried(needs, shortfall))
            return;
        if (!shortfall.isEmpty() && config.build.nearRecheckAll) {
            // the targeted look first, as at SELECT: the near containers that hold or held the short items. Every near
            // container only when that leaves them short. Live 2026-11-15 (j0009, pass 6): resin block short by the
            // index and 4,296 in its own chests (refilled since the last look); the sweep of all 502 near containers of
            // the colour-suppression platform took 7:16, a targeted look there takes about 75 s.
            if (nearScanFirst(plan.geo, shortfall.keySet(), "before skipping " + label(), false)) return;
            shortfall = stockShortfall(want);
        }
        if (!shortfall.isEmpty() && nearLookAll(plan.geo, shortfall.keySet(), "before skipping " + label(), true)) return;
        // A completed sweep changed the index: decide again before holding or looking at far containers.
        shortfall = stockShortfall(want);
        if (!shortfall.isEmpty() && !c.relooked) {
            // the index may be out of date: look again at chests that could have received the missing items; with
            // nothing placed yet, also those the other unstarted maps lack (one of them may be fully stocked)
            c.relooked = true;
            Set<String> items = new TreeSet<>(shortfall.keySet());
            if (tile.placed <= 0 && !job.isSuppression()) items.addAll(shortElsewhere());
            Set<Pos> extra = shortageCandidates(items);
            if (!extra.isEmpty()) {
                log.info("short of {}; rescanning {} empty or matching material containers near known stock",
                    items, extra.size());
                c.extraScan = extra;
                c.scanQueue = null;
                c.stockSaved = false;
                acted = true;
                return;
            }
        }
        if (!shortfall.isEmpty()) {
            int remaining = PartialBuild.cells(needs);
            int shortCells = PartialBuild.cells(shortfall);
            // never for a colour-suppression pass: its job is not set aside for another map (and the index shows only
            // its own chests while it is current, so no other map could be judged)
            Candidate other = tile.placed <= 0 && !job.isSuppression() ? coveredElsewhere() : null;
            if (other != null) {
                // nothing placed yet and another map is fully stocked: that one first (SELECT takes it)
                log.info("{} is short of {}; {} is fully stocked: building that first", label(), PartialBuild.items(shortfall),
                    label(other.job(), other.tile()));
                holdForMaterials(shortfall, want);
                return;
            }
            boolean worth = worthATrip(needs, want, shortfall);
            switch (PartialBuild.decide(config.build.partialBuild, config.build.partialBuildMinShare, remaining,
                shortCells, tile.placed > 0, worth)) {
                case PARTIAL -> buildPartly(shortfall, remaining);
                case WAIT -> {
                    if (!buildCarried(needs, shortfall)) waitForMaterials(shortfall, remaining, want);
                }
                default -> holdForMaterials(shortfall, want);
            }
            return;
        }
        boolean waited = waitingForMaterials();
        endMaterialsWait();
        if (job.status == JobStatus.WAITING_MATERIALS) {
            job.status = JobStatus.ACTIVE;
            job.statusDetail = "";
            saveJob(job);
            if (waited) notifier.info("Materials available", label() + ": placing the rest of the map.");
            else if (!announceStart) notifier.detail("Materials available", label() + ": continuing.");
        } else if (job.statusDetail != null && job.statusDetail.startsWith("short: ")) {
            job.statusDetail = ""; // a partial build whose missing items came: nothing is short any more
            saveJob(job);
        }
        waitingNeeds.remove(job.id);
        waitingCheckedAt.remove(job.id);
        lastShortAlert.remove(tileKey(job, tile));
        started(null);
        go(Phase.GEAR);
    }

    /** "199 cyan terracotta, 1 crimson nylium" (the job's status detail and logs). */
    private static String compact(Map<String, Integer> shortfall) {
        return ShortageText.amounts(shortfall);
    }

    /**
     * The current map builds (STOCK_CHECK → GEAR): its start line, once ({@link #announceStart}), naming the maps set
     * aside before it ("Started ellen: tile 3,2 (6 of 28 maps done) instead of tile 3,3"). Maps are named by tile and
     * progress is the count of stored maps: an index ("map 16 of 28") read as 15 maps done. {@code partly}: the partial
     * build's sentence, or null with full stock. Info, so quiet mode posts it too.
     */
    private void started(@Nullable String partly) {
        shortMaps.remove(tileKey(job, tile));
        nothingAlerted = null;
        List<ShortMap> before = new ArrayList<>();
        for (ShortMap m : skipped) if (!tileKey(m.job(), m.tile()).equals(tileKey(job, tile))) before.add(m);
        skipped.clear();
        if (!announceStart) return;
        announceStart = false;
        int n = job.tiles.size();
        if (suppressionTile()) {
            // the passes of one map: its first pass is the map's start line (always on), the others are detail. On
            // Discord a pass has no number (owner 2026-11-14: "What does pass mean?"): the layer in words, as in the
            // presence (StatusWords); the pass number is in the log
            Job.SuppressionPass sp = suppressionPass(job, tile);
            String layer = layerName(sp.layer) + " layer";
            log.info("{}: pass {} of {} ({} y+{}{}), {} blocks", label(), tile.x + 1, n, layer, sp.level,
                sp.noobline ? ", noobline" : "", plan.total);
            io.github.digitalisp.mapart.command.StatusWords.Blocks whole =
                io.github.digitalisp.mapart.command.StatusWords.suppressionBlocks(job, tile.x, tile.placed, plan.total);
            String more = partly != null ? "\n" + partly : "";
            // the map's first pass: the first stored one, or with build.suppressionMergeNoobline the main pass built
            // before it (then the noobline pass that follows is detail)
            boolean first = tile.x == 0 ? !mainBuiltFirst(job, tile)
                : tile.x == 1 && config.build.suppressionMergeNoobline && nooblineTwin(job, tile) != null;
            if (first) notifier.info("Started color-suppression map " + job.name, "The " + layer + " first, on platform "
                + platform.id + ": " + ShortageText.count((int) whole.total()) + " blocks in the whole map." + more);
            else notifier.detail("Color suppression " + job.name + ": " + layer + (sp.level > 0 ? ", upper level" : "")
                + (sp.noobline ? ", noobline row" : ""), ShortageText.count(plan.total) + " blocks on platform " + platform.id
                + "." + more);
            return;
        }
        StringBuilder title = new StringBuilder("Started ").append(job.name);
        if (n > 1) title.append(": ").append(ShortageText.mapOf(job, tile));
        if (partly != null) title.append(", partly");
        if (n > 1) title.append(" (").append(ShortageText.done(job)).append(')');
        if (!before.isEmpty()) {
            List<String> refs = new ArrayList<>();
            for (int k = 0; k < before.size() && k < 3; k++)
                refs.add(ShortageText.mapIn(job, before.get(k).job(), before.get(k).tile()));
            if (before.size() > 3) refs.add((before.size() - 3) + " more");
            title.append(" instead of ").append(refs.size() == 1 ? refs.getFirst()
                : String.join(", ", refs.subList(0, refs.size() - 1)) + " and " + refs.getLast());
        }
        StringBuilder body = new StringBuilder(label()).append(" on platform ").append(platform.id).append(": ")
            .append(ShortageText.count(plan.total)).append(" blocks.");
        if (tile.carried > 0)
            body.append(' ').append(ShortageText.count(tile.carried))
                .append(" of them were left in place by the map before (carry-over).");
        if (partly != null) body.append('\n').append(partly);
        for (int k = 0; k < before.size() && k < 5; k++) {
            ShortMap m = before.get(k);
            body.append("\nSkipped for now: ").append(ShortageText.mapIn(job, m.job(), m.tile())).append(" needs ")
                .append(ShortageText.needs(m.shortfall())).append('.');
        }
        if (before.size() > 5) body.append("\n… and ").append(before.size() - 5).append(" more maps skipped.");
        if (!before.isEmpty())
            body.append("\n").append(before.size() == 1 ? "It is" : "They are").append(" checked again when the next map is chosen.");
        notifier.info(title.toString(), body.toString());
    }

    /**
     * The map is set aside for lack of materials while another may be built (STOCK_CHECK): logged and remembered, no
     * alert. The next start line names it ({@link #started}); "Nothing to build" lists it when no map can be built
     * ({@link #alertNothingToBuild}).
     */
    private void holdForMaterials(Map<String, Integer> shortfall, Map<String, Integer> want) {
        String compact = compact(shortfall);
        endMaterialsWait();
        job.status = JobStatus.WAITING_MATERIALS;
        job.statusDetail = "short: " + compact;
        saveJob(job);
        waitingCheckedAt.put(job.id, clock.millis());
        waitingNeeds.put(job.id, want);
        ShortMap m = new ShortMap(job, tile, new TreeMap<>(needsCache != null ? needsCache : want), new TreeMap<>(shortfall));
        String key = tileKey(job, tile);
        shortMaps.remove(key);
        shortMaps.put(key, m);
        skipped.removeIf(x -> tileKey(x.job(), x.tile()).equals(key));
        skipped.add(m);
        announceStart = false;
        log.warn("{} set aside for now: needs {}", label(), ShortageText.needs(shortfall));
        if (config.build.skipShortTiles && tile.placed <= 0 && tile.status == TileStatus.ACTIVE) {
            // nothing of it is placed: the map waits as unstarted, so it holds no platform and SELECT weighs it again
            tile.status = TileStatus.PENDING;
            tile.platformId = null;
            tile.startedAt = 0;
            saveJob(job);
        }
        endUnit("out_of_materials"); // given back (LocalCoordination: nothing reported)
        clearCurrent();
        go(Phase.SELECT);
    }

    /**
     * SELECT found no map to build while maps wait for materials: the one alert (with the mention) that a human has to
     * restock. Once per set of short items until a map starts again; it lists every waiting map with what it needs and,
     * per job, what all its maps left still need beyond the stock (a shopping list).
     */
    private void alertNothingToBuild() {
        List<ShortMap> waiting = new ArrayList<>();
        for (ShortMap m : shortMaps.values()) {
            Job live = coord.job(m.job().id).orElse(null);
            if (live == null || live.status == null || live.status.terminal()) continue;
            if (m.tile().status == TileStatus.STORED || m.tile().status == TileStatus.FAILED) continue;
            waiting.add(m);
        }
        if (waiting.isEmpty()) return;
        Set<String> items = new TreeSet<>();
        for (ShortMap m : waiting) items.addAll(m.shortfall().keySet());
        String key = items.toString();
        if (key.equals(nothingAlerted)) return;
        nothingAlerted = key;
        // the title names what is out; the first line says what to do and keeps "Nothing to build" (the ops watchers)
        Map<String, Integer> all = new TreeMap<>();
        for (ShortMap m : waiting) m.shortfall().forEach((id, n) -> all.merge(id, n, Math::max));
        StringBuilder body = new StringBuilder("Nothing to build: ")
            .append(waiting.size() == 1 ? "the queued map is" : "every queued map is")
            .append(" short of materials. Put them in the storage chests; the bot looks again every ")
            .append(waitingRecheckMs() / 60_000).append(" min.");
        for (int k = 0; k < waiting.size() && k < 5; k++) {
            ShortMap m = waiting.get(k);
            body.append("\n").append(ShortageText.fullMap(m.job(), m.tile())).append(" needs:")
                .append(ShortageText.lines(m.shortfall(), coord.stock().unstocked(m.shortfall().keySet())));
        }
        if (waiting.size() > 5) body.append("\n… and ").append(waiting.size() - 5).append(" more maps.");
        Map<String, Map<String, Integer>> listed = new HashMap<>(); // per job: the most a map line above names
        for (int k = 0; k < waiting.size() && k < 5; k++) {
            ShortMap m = waiting.get(k);
            m.shortfall().forEach((id, n) -> listed.computeIfAbsent(m.job().id, x -> new HashMap<>()).merge(id, n, Math::max));
        }
        Set<String> jobs = new LinkedHashSet<>();
        for (ShortMap m : waiting)
            if (jobs.add(m.job().id)) body.append(shoppingList(m.job(), listed.getOrDefault(m.job().id, Map.of())));
        log.warn("nothing to build: {} maps short of {}", waiting.size(), items);
        alert(AlertText.outOf(all), body.toString());
    }

    /**
     * "\nFor the rest of ellen (13 maps left; ...), restock:" and one line per item: what the job's unfinished maps need
     * beyond the stock index and the inventory, ONE MAP AT A TIME. Every map's blocks come back to the chests after its
     * clear (the nuke breaks them, the collection and the sorter return them), so the stock must cover the largest of
     * the maps left, not their sum: per item, the most one map left needs less what is on hand, and for the maps after
     * the one on the platform less what that one returns once cleared (its placed blocks). Live 2026-10-15 (Galileo, 78
     * maps left): the summed list asked for 359,465 black carpet, which the owner called useless; with 10k cyan
     * terracotta on hand and a map that needs 12k, it says 2k. Maps whose materials were not read yet are counted
     * apart. Empty when it names nothing beyond {@code listed} (what the map lines above already say).
     */
    private String shoppingList(Job j, Map<String, Integer> listed) {
        Map<String, Integer> avail = available();
        // the map on the platform: what it still needs now, and what it gives back after its clear
        Map<String, Integer> current = null;
        Map<String, Integer> returned = Map.of();
        Map<String, Integer> peak = new TreeMap<>(); // per item, the most one later map needs
        int left = 0;
        int unread = 0;
        for (Job.TileState t : j.tiles) {
            if (t.status == TileStatus.STORED || t.status == TileStatus.FAILED) continue;
            left++;
            String key = tileKey(j, t);
            if (t == tile && needsCache != null) {
                current = needsCache;
                Map<String, Integer> full = tileNeeds.get(key);
                if (full != null) {
                    Map<String, Integer> back = new HashMap<>();
                    full.forEach((id, n) -> {
                        int placed = n - needsCache.getOrDefault(id, 0);
                        if (placed > 0) back.put(id, placed);
                    });
                    returned = back;
                }
                continue;
            }
            Map<String, Integer> needs = shortMaps.containsKey(key) ? shortMaps.get(key).needs() : tileNeeds.get(key);
            if (needs == null) {
                unread++;
                continue;
            }
            needs.forEach((id, n) -> peak.merge(id, n, Math::max));
        }
        if (left <= 1) return "";
        Map<String, Integer> buy = new TreeMap<>();
        if (current != null) current.forEach((id, n) -> {
            int more = n - avail.getOrDefault(id, 0);
            if (more > 0) buy.merge(id, more, Math::max);
        });
        Map<String, Integer> back = returned;
        peak.forEach((id, n) -> {
            int more = n - avail.getOrDefault(id, 0) - back.getOrDefault(id, 0);
            if (more > 0) buy.merge(id, more, Math::max);
        });
        if (buy.isEmpty()) return "";
        boolean news = false;
        for (var e : buy.entrySet()) if (e.getValue() > listed.getOrDefault(e.getKey(), 0)) news = true;
        if (!news) return "";
        return "\nFor the rest of " + j.name + " (" + left + " maps left; each map's blocks come back after its clear,"
            + " so this is for the largest map left), restock:" + ShortageText.lines(buy,
            coord.stock().unstocked(buy.keySet())) + (unread > 0 ? "\n(" + unread + " of its maps not counted: not read yet)" : "");
    }

    // ---- partial build (PartialBuild) ----

    /** Status note prefix while the tile waits on its platform for materials (the Discord presence shows it, idle). */
    public static final String MATERIALS_NOTE = "waiting for materials";

    /**
     * STOCK_CHECK found the tile short of some items, but the stock covers the rest: GEAR and RESTOCK go on, and the
     * restock loads, BUILD and VERIFY leave out the cells the stock cannot cover ({@link #uncovered}).
     */
    private void buildPartly(Map<String, Integer> shortfall, int remaining) {
        String compact = compact(shortfall);
        int shortCells = PartialBuild.cells(shortfall);
        boolean waited = waitingForMaterials() || job.status == JobStatus.WAITING_MATERIALS;
        endMaterialsWait();
        job.status = JobStatus.ACTIVE;
        job.statusDetail = "short: " + compact + "; building the rest";
        saveJob(job);
        waitingNeeds.remove(job.id);
        waitingCheckedAt.remove(job.id);
        log.warn("{} short of materials: {}; building the {} of {} cells the stock covers, the rest waits", label(),
            compact, remaining - shortCells, remaining);
        String partly = "Building the " + ShortageText.count(remaining - shortCells) + " of " + ShortageText.count(remaining)
            + " blocks in stock now; it needs " + ShortageText.needs(shortfall) + " before the map is filled.";
        if (announceStart) started(partly);
        else {
            started(null);
            notifier.detail(waited ? "Materials available" : "Building partly", label() + ": " + partly);
        }
        go(Phase.GEAR);
    }

    /** The tile waits on its platform for materials (STOCK_CHECK with {@link #MATERIALS_NOTE}). */
    private boolean waitingForMaterials() {
        return materialsWaitSince >= 0;
    }

    /**
     * Every cell the stock covers is placed: the tile waits on its platform (job WAITING_MATERIALS, the note "waiting
     * for materials: …", the presence idle, the keep-alive on) and STOCK_CHECK looks again every
     * {@code waitingRecheckMinutes} at the chests that hold or could hold the items ({@link #awaitMaterials}), and at
     * once when bot ticks start again or the inventory's count of a short item changes
     * ({@code build.stockWaitWakeOnChange}). {@code want}: the needs less the inventory (what the stock must bring).
     */
    private void waitForMaterials(Map<String, Integer> shortfall, int remaining, Map<String, Integer> want) {
        long now = clock.millis();
        boolean first = !waitingForMaterials();
        if (first) materialsWaitSince = now;
        materialsShort = new TreeMap<>(shortfall);
        materialsWant = new TreeMap<>(want);
        materialsCarried = carriedOf(shortfall.keySet());
        materialsWake = null;
        materialsRecheckAt = now + waitingRecheckMs();
        note = MATERIALS_NOTE + ": " + PartialBuild.items(shortfall);
        String detail = "short: " + compact(shortfall) + "; the rest is built";
        if (job.status != JobStatus.WAITING_MATERIALS || !detail.equals(job.statusDetail)) {
            job.status = JobStatus.WAITING_MATERIALS;
            job.statusDetail = detail;
            saveJob(job);
        }
        long minutes = waitingRecheckMs() / 60_000;
        if (first) {
            log.warn("{}: every block the stock covers is placed ({} of {}); waiting on platform {} for {}; looking again"
                + " every {} min", label(), tile.placed, plan.total, platform.id, PartialBuild.items(shortfall), minutes);
            alertOutOfMaterials();
        } else {
            // what the stock adds (with carried items built apart) or, as before, the inventory and the stock together
            int inStock = (config.build.partialBuildCarried ? PartialBuild.cells(want) : remaining)
                - PartialBuild.cells(shortfall);
            int min = config.build.partialTripMinCells;
            String until = min > 0
                ? PartialBuild.tripCells(PartialBuild.loadCells(config.build.reservedSlots), min) + " blocks"
                : "a load's worth";
            log.info("{}: still short of {}{}; looking again in {} min", label(), PartialBuild.items(shortfall),
                inStock > 0 ? " (" + inStock + " blocks in stock: not worth a trip until " + until + " or all of an item"
                    + " is there)" : "", minutes);
        }
        ctx = new Ctx(); // the look is over: the next one starts afresh when it is due (awaitMaterials)
        sleep(IDLE_POLL_TICKS);
    }

    /**
     * The one alert (with the mention) of a started map waiting on its platform for materials: nothing else can be built,
     * a started map keeps its platform until it is finished. Once per tile and set of short items. Title "Out of white
     * carpet"; the body says how much to put where first ({@link AlertText#outOfMaterials}). False when already sent.
     */
    private boolean alertOutOfMaterials() {
        if (job == null || tile == null || platform == null || materialsShort.isEmpty()) return false;
        String key = materialsShort.keySet().toString();
        if (key.equals(lastShortAlert.get(tileKey(job, tile)))) return false;
        lastShortAlert.put(tileKey(job, tile), key);
        Map<String, Integer> inStock = new HashMap<>();
        materialsShort.forEach((id, n) -> inStock.put(id, materialsWant.getOrDefault(id, n) - n));
        AlertText.Alert a = AlertText.outOfMaterials(ShortageText.fullMap(job, tile), platform.id, materialsShort, inStock,
            coord.stock().unstocked(materialsShort.keySet()), tile.placed, plan != null ? plan.total : tile.total,
            waitingRecheckMs() / 60_000, shoppingList(job, materialsShort));
        alert(a.title(), a.body() + refLine());
        return true;
    }

    /**
     * STOCK_CHECK while the tile waits for materials: true while it only waits (polled, nothing else runs, the keep-alive
     * swings). When the next look is due ({@code waitingRecheckMinutes}, a requested scan, or with
     * {@code build.stockWaitWakeOnChange} bot ticks started again or the inventory's count of a short item changed, polled
     * every second), false with a fresh context: the step looks again at the chests that hold or could hold the items
     * (STOCK_CHECK's targeted re-look, never a full rescan; carried items are built first, {@link #buildCarried}) and
     * decides again.
     */
    private boolean awaitMaterials() {
        if (!waitingForMaterials() || ctx.materialsLook) return false;
        long now = clock.millis();
        boolean wake = config.build.stockWaitWakeOnChange;
        if (wake && materialsWake == null) {
            // the inventory's count of a short item changed (handed over, picked up, taken): look again at once
            Map<String, Integer> carried = carriedOf(materialsShort.keySet());
            if (!carried.equals(materialsCarried)) {
                List<String> changes = new ArrayList<>();
                carried.forEach((id, n) -> {
                    int was = materialsCarried.getOrDefault(id, 0);
                    if (n != was) changes.add(ShortageText.item(id) + " " + was + " -> " + n);
                });
                materialsWake = "the inventory changed (" + String.join(", ", changes) + ")";
            }
        }
        if (now < materialsRecheckAt && !forceScan && materialsWake == null) {
            note = MATERIALS_NOTE + ": " + PartialBuild.items(materialsShort);
            sleep(wake ? POLL_TICKS : IDLE_POLL_TICKS); // with wake on change: the inventory is watched every second
            return true;
        }
        if (materialsWake != null && now < materialsRecheckAt && !forceScan)
            log.info("{}: waiting for {} since {}; {}: looking again at once", label(), PartialBuild.items(materialsShort),
                formatDuration(now - materialsWaitSince), materialsWake);
        else log.info("{}: waiting for {} since {}; looking again at {}", label(),
            PartialBuild.items(materialsShort), formatDuration(now - materialsWaitSince),
            config.build.nearRecheckAll && config.build.nearScanFirst ? "every near container"
                : "the chests that hold or could hold them");
        materialsWake = null;
        ctx = new Ctx();
        ctx.materialsLook = true;
        acted = true;
        return false;
    }

    private void endMaterialsWait() {
        materialsWaitSince = -1;
        materialsRecheckAt = 0;
        materialsShort = Map.of();
        materialsWant = Map.of();
        materialsCarried = Map.of();
        materialsWake = null;
    }

    // ---- sorter refills (PlatformCfg.sorterRefills) ----

    /** The platform collects and sorts everything back into its near chests. */
    private static boolean refills(@Nullable PlatformCfg p) {
        return p != null && p.sorterRefills;
    }

    /**
     * {@code id} is something this platform's near chests return: one of them holds it or held it before (a sorter
     * chest the bot emptied; {@link StockIndex.Snapshot#held}). A colour no near chest ever held is not (a new colour
     * must come from far storage), nor an item that gave up waiting on this tile.
     */
    private boolean nearReclaimable(String id, PlatformGeometry geo) {
        if (sorterGaveUp.contains(id)) return false;
        StockIndex st = coord.stock();
        synchronized (st) {
            for (var e : st.containers.entrySet()) {
                Pos p = Pos.parseKey(e.getKey());
                if (!isMaterialContainer(p) || FarStorage.far(geo, config.build.farStorageBlocks, p)) continue;
                if (holds(e.getValue(), Set.of(id))) return true;
            }
        }
        return false;
    }

    /** {@code shortfall} without the items the sorter of {@code p} returns (it covers them up to the need). */
    private Map<String, Integer> sorterCredited(Map<String, Integer> shortfall, @Nullable PlatformCfg p,
                                                @Nullable PlatformGeometry geo) {
        if (!refills(p) || geo == null || shortfall.isEmpty()) return shortfall;
        Map<String, Integer> out = new TreeMap<>(shortfall);
        out.keySet().removeIf(id -> nearReclaimable(id, geo));
        return out;
    }

    /** The stock index's shortfall of {@code want} for the current tile; sorter-refilled items count as in stock. */
    private Map<String, Integer> stockShortfall(Map<String, Integer> want) {
        return sorterCredited(coord.stock().shortfall(want), platform, plan == null ? null : plan.geo);
    }

    /** SELECT's stock snapshot for a tile on {@code p}: sorter-refilled items count as in stock up to {@code needs}. */
    private Map<String, Integer> creditedAvail(Map<String, Integer> avail, Map<String, Integer> needs,
                                               @Nullable PlatformCfg p, @Nullable PlatformGeometry geo) {
        if (!refills(p) || geo == null) return avail;
        Map<String, Integer> out = null;
        for (var e : needs.entrySet()) {
            if (e.getValue() <= avail.getOrDefault(e.getKey(), 0) || !nearReclaimable(e.getKey(), geo)) continue;
            if (out == null) out = new HashMap<>(avail);
            out.put(e.getKey(), e.getValue());
        }
        return out == null ? avail : out;
    }

    private Map<String, Integer> creditedAvail(Map<String, Integer> avail, Map<String, Integer> needs, Option o) {
        return creditedAvail(avail, needs, o.c().platform(), geometryFor(o.c().job(), o.c().tile(), o.c().platform()));
    }

    /**
     * Per item, the cells of {@code needs} (less the inventory) that the near chests do not hold yet but the sorter
     * will return: RESTOCK leaves out those cells (their last ones in build order) instead of going to far storage.
     * Empty off a refilling platform, in a carried round and for items far storage must supply.
     */
    private Map<String, Integer> sorterLag(Map<String, Integer> needs) {
        if (!refills(platform) || carriedRound || plan == null || needs == null || needs.isEmpty()) return Map.of();
        Map<String, Integer> near = nearCounts(plan.geo);
        Map<String, Integer> totals = coord.stock().totals();
        Map<String, Integer> lag = new TreeMap<>();
        minusInventory(needs).forEach((id, n) -> {
            int short_ = n - near.getOrDefault(id, 0);
            // a big gap is faster from far storage than from the sorter's trickle (live 2026-10-14 01:20, remu [2,0]:
            // ~7,000 white carpet short, VERIFY rounds of ~300 every 30 s); build.sorterRefillMaxCells. With none of it in
            // far storage the sorter is the only source, whatever the gap (live 2026-10-15, Galileo [0,1]: the nukes of
            // the maps before had put the yellow terracotta into the sorter; the far trip found none, four rounds
            // without a load, then BLOCKED "materials cannot be obtained")
            boolean farHasNone = totals.getOrDefault(id, 0) <= near.getOrDefault(id, 0);
            if (short_ > 0 && (short_ <= config.build.sorterRefillMaxCells || farHasNone) && nearReclaimable(id, plan.geo))
                lag.put(id, short_);
        });
        return lag;
    }

    /**
     * The sorter's pickup spot ({@link PlatformCfg#sorterPickup}) of a refilling platform, when it is loaded: items fall
     * past it while they are sorted, so standing there fills the inventory faster than the chests do.
     */
    private @Nullable Pos pickupSpot() {
        if (!refills(platform) || platform.sorterPickup == null || carriedRound || pickupEmpty >= PICKUP_EMPTY_MAX) return null;
        return bot.world().isLoaded(platform.sorterPickup) ? platform.sorterPickup : null;
    }

    /** Lagging items the plan wants more of than the near chests hold: item → count (the trickle case). */
    private Map<String, Integer> pickupMissing(Ctx c) {
        Map<String, Integer> out = new TreeMap<>();
        if (pickupSpot() == null || c.pickupStarted) return out;
        c.unavailable.forEach((id, n) -> {
            if (n >= PICKUP_MIN_MISSING && nearReclaimable(id, plan.geo)) out.put(id, n);
        });
        return out;
    }

    /** Junk of the inventory to the junk spot ({@link PlatformCfg#junkDropStand}), once per RESTOCK. True: a task runs. */
    private boolean throwJunk(Ctx c, Leftovers lo, InventoryView inv) {
        if (c.junkThrown || platform.junkDropStand == null || platform.junkDropAt == null || !junkSpotNear()) return false;
        Map<String, Integer> thrown = lo.junk(inv.contents(), Map.of(), catalog::maxStackSize);
        thrown.keySet().removeIf(id -> !lo.isJunk(id));
        if (thrown.isEmpty()) return false;
        String spot = Claim.drop(platform.junkDropStand);
        if (awaitSpot(spot, "the junk spot")) return true;
        c.junkThrown = true;
        log.info("throwing junk {} at the junk spot (standing at {})", thrown, platform.junkDropStand);
        run(bot.dropItems(platform.junkDropStand, platform.junkDropAt, lo::isJunk, Map.of(),
            m -> log.info("threw away junk {}", m)), null, r -> log.warn("junk throw at {} failed: {}",
            platform.junkDropStand, r));
        spotTask(spot);
        return true;
    }

    /**
     * After a pickup stand, still at the edge ({@code build.pickupThrowBack}): the materials it brought that the tile does
     * not need go back into the sorter's fall, then the junk to the junk spot ({@code junkDropStand}, a few blocks off).
     * Never what the tile needs, a valuable, food, a tool, XP bottles or what was carried before the stand. True: a task
     * runs.
     */
    private boolean afterPickup(Ctx c) {
        Map<String, Integer> before = c.pickupBefore;
        InventoryView inv = bot.inventory();
        c.leftovers = null; // the junk rule looks at where the bot is now
        Leftovers lo = leftovers(c);
        if (!c.pickupReturned) {
            Pos spot = platform.sorterPickup;
            if (spot != null && c.needs != null) {
                Map<String, Integer> out = new TreeMap<>();
                lo.junk(inv.contents(), c.needs, catalog::maxStackSize).forEach((id, n) -> {
                    int brought = inv.count(id) - before.getOrDefault(id, 0);
                    // what the stand waited for stays, even when the load plan left its cells out as sorter lag
                    if (brought > 0 && !lo.isJunk(id) && !c.sorterLag.containsKey(id)) out.put(id, Math.min(n, brought));
                });
                // the throw-back is into the sorter's stream: one bot there at once
                if (!out.isEmpty() && awaitSpot(Claim.sorter(platform.id), "the sorter")) return true;
                c.pickupReturned = true;
                if (!out.isEmpty()) {
                    Pos target = platform.sorterPickupReturn != null ? platform.sorterPickupReturn
                        : new Pos(spot.x(), spot.y() - 1, spot.z());
                    Map<String, Integer> keep = new TreeMap<>();
                    out.forEach((id, n) -> keep.put(id, inv.count(id) - n));
                    log.info("sorter pickup: throwing back what no load needs: {} (at {})", out, target);
                    sorterFed().addAll(out.keySet());
                    run(bot.throwBack(spot, target, out::containsKey, keep, m -> log.info("threw back {}", m)), null,
                        r -> log.warn("throwing back at the pickup edge failed: {}", r));
                    spotTask(Claim.sorter(platform.id));
                    return true;
                }
            }
            c.pickupReturned = true;
        }
        if (throwJunk(c, lo, inv)) return true;
        c.pickupBefore = null;
        return false;
    }

    /**
     * Stands at the pickup spot until {@code missing} (item → count) is carried on top of what the inventory holds, or
     * nothing more comes ({@code build.sorterPickupStallSeconds}), the time is up ({@code sorterPickupMaxSeconds}) or
     * the inventory is full ({@code sorterPickupMinFreeSlots}); then the load is planned again from the inventory. An
     * item still short by {@code sorterPickupFarMinCells} or more that far storage holds goes there in a full load
     * instead of another trickle. A failed stand (setback, fall) is logged and not tried again this RESTOCK. True: running.
     */
    private boolean startPickup(Ctx c, Map<String, Integer> missing) {
        Pos spot = pickupSpot();
        if (spot == null) return false;
        // one bot in the sorter's item stream at once (two take each other's items)
        if (awaitSpot(Claim.sorter(platform.id), "the sorter")) return true;
        c.pickupStarted = true;
        Map<String, Integer> want = new TreeMap<>();
        InventoryView inv = bot.inventory();
        missing.forEach((id, n) -> want.put(id, inv.count(id) + n));
        log.info("restock: standing at the sorter's pickup spot for {} (the near chests hold less than the load needs)",
            PartialBuild.items(missing));
        note = "picking up from the sorter";
        if (config.build.pickupThrowBack) c.pickupBefore = new TreeMap<>(inv.contents());
        run(bot.standForItems(platform.sorterPickupPrime, spot, want,
                config.build.sorterPickupStallSeconds * 20,
                config.build.sorterPickupMaxSeconds * 20, config.build.sorterPickupMinFreeSlots, r -> {
                if (r.gained().isEmpty() && ++pickupEmpty >= PICKUP_EMPTY_MAX)
                    log.warn("sorter pickup: {} stands in a row brought nothing; no more stands this tile", pickupEmpty);
                else if (!r.gained().isEmpty()) pickupEmpty = 0;
                if (!"covered".equals(r.reason())) {
                    Map<String, Integer> near = nearCounts(plan.geo);
                    for (var e : want.entrySet()) {
                        int still = e.getValue() - r.carried().getOrDefault(e.getKey(), 0);
                        int far = stocked(e.getKey()) - near.getOrDefault(e.getKey(), 0);
                        if (still >= config.build.sorterPickupFarMinCells && far >= still) {
                            log.info("restock: the sorter's pickup did not cover {} ({} short, {} in far storage): a full load"
                                + " from far storage, no more trickle", e.getKey(), still, far);
                            sorterGaveUp.add(e.getKey());
                        }
                    }
                }
                c.lanes = null; // the load is planned again from the inventory
                c.withdrawals = null;
                c.loadPlan = null;
                c.loadPlans = null;
                c.unavailable.clear();
            }), null, r -> {
                pickupEmpty = PICKUP_EMPTY_MAX; // the spot cannot be reached or kept: not again this tile
                log.warn("sorter pickup stand failed: {}; no more stands this tile", r);
            });
        spotTask(Claim.sorter(platform.id));
        return true;
    }

    /** The cells RESTOCK leaves out: the stock's uncovered ones and the sorter's lag ({@link Ctx#sorterLag}). */
    private Map<String, Integer> leftOut(Ctx c) {
        Map<String, Integer> unc = uncovered(needsCache);
        c.sorterLag = sorterLag(needsCache);
        if (c.sorterLag.isEmpty()) return unc;
        Map<String, Integer> merged = new TreeMap<>(unc);
        c.sorterLag.forEach((id, n) -> merged.merge(id, n, Math::max));
        String now = c.sorterLag.toString();
        if (!now.equals(c.lagLogged)) {
            c.lagLogged = now;
            int lanes = buildable(PartialBuild.withoutShort(c.lanes, merged)).size();
            Map<String, Integer> fed = new TreeMap<>(c.sorterLag);
            fed.keySet().retainAll(sorterFed());
            Map<String, Integer> plain = new TreeMap<>(c.sorterLag);
            plain.keySet().removeAll(sorterFed());
            // only what this platform's nukes or drops put into the sorter is on its way back (live 2026-10-29: white carpet that
            // simply needed new stock was logged as "the sorter is still returning them")
            if (!fed.isEmpty())
                log.info("restock: {} not back in the near chests yet (the sorter is still returning them): those cells"
                    + " wait for a later load, no far trip; {} lanes can be built now", PartialBuild.items(fed), lanes);
            if (!plain.isEmpty())
                log.info("restock: {} short in the near chests, and none of it went into this platform's sorter: it"
                    + " needs restocking; those cells wait for a later load (far storage if the near count does not rise for {}"
                    + " min); {} lanes can be built now", PartialBuild.items(plain), config.build.sorterRefillMinutes,
                    lanes);
        }
        return merged;
    }

    /** Near chests that hold or held one of {@code items} and the empty ones beside them, for the wait's looks. */
    private Set<Pos> sorterLookAt(PlatformGeometry geo, Set<String> items) {
        Set<Pos> out = new LinkedHashSet<>();
        if (materialContainers == null) return out;
        StockIndex st = coord.stock();
        int blocks = config.build.farStorageBlocks;
        synchronized (st) {
            List<Pos> holders = new ArrayList<>();
            for (Pos p : scoped(materialContainers)) {
                StockIndex.Snapshot s = st.containers.get(p.key());
                if (s != null && !FarStorage.far(geo, blocks, p) && holds(s, items)) holders.add(p);
            }
            for (Pos p : scoped(materialContainers)) {
                if (FarStorage.far(geo, blocks, p) || scanFailures.getOrDefault(p, 0) >= SCAN_FAILURES_SKIP) continue;
                if (holders.contains(p)) {
                    out.add(p);
                    continue;
                }
                StockIndex.Snapshot s = st.containers.get(p.key());
                if (s != null && s.items.isEmpty()
                    && holders.stream().anyMatch(h -> Math.abs(h.x() - p.x()) <= 1 && Math.abs(h.z() - p.z()) <= 2))
                    out.add(p);
            }
        }
        return out;
    }

    /**
     * Nothing but sorter-lag cells is left to build: stand by the first output chest and look at the chests that hold
     * or held the items every {@code build.sorterRefillPollSeconds}; the cells come back into the lanes when the near
     * count rises (the relook replans the load). The near count flat for {@code build.sorterRefillMinutes}: the items
     * are given up on for this tile and fetched from far storage as before. True while it waits or acts.
     */
    private boolean sorterWait(Ctx c) {
        long now = clock.millis();
        Set<String> items = c.sorterLag.keySet();
        Map<String, Integer> near = nearCounts(plan.geo);
        int total = 0;
        for (String id : items) total += near.getOrDefault(id, 0);
        long pollMs = Math.max(1, config.build.sorterRefillPollSeconds) * 1000L;
        if (c.lagSince < 0) {
            c.lagSince = now;
            c.lagBest = total;
            c.lagBestAt = now;
            c.lagLookAt = now + pollMs;
            log.info("restock: nothing is left to build but {}: waiting by the sorter chests, looking every {} s; far storage"
                + " if the near count does not rise for {} min", PartialBuild.items(c.sorterLag),
                config.build.sorterRefillPollSeconds, config.build.sorterRefillMinutes);
        }
        if (total > c.lagBest) {
            log.info("restock: the sorter returned {} {} (near {} now); looking at the lanes again", total - c.lagBest,
                items, total);
            c.lagBest = total;
            c.lagBestAt = now;
        }
        if (now - c.lagBestAt >= (long) (config.build.sorterRefillMinutes * 60_000L)) {
            // every near container before far storage (build.nearRecheckAll): the stock may sit in a chest the wait's
            // looks never open (live 2026-10-29: carpet put by hand into another platform chest); a sweep within the
            // last nearRecheckAllSeconds stands for it. A rise found is seen at the top on the next call
            c.nearFullChecked = null;
            if (nearLookAll(plan.geo, items, "before far storage", true)) return true;
            if (nearCounts(plan.geo).entrySet().stream().filter(e -> items.contains(e.getKey()))
                .mapToInt(Map.Entry::getValue).sum() > c.lagBest) {
                sleep(POLL_TICKS); // risen: seen at the top next time
                return true;
            }
            log.warn("restock: the near count of {} has not risen for {} min: fetching them from far storage", items,
                config.build.sorterRefillMinutes);
            sorterGaveUp.addAll(items);
            c.sorterLag = Map.of();
            c.lagSince = -1;
            c.lanes = null;
            c.withdrawals = null;
            c.loadPlan = null;
            c.loadPlans = null;
            acted = true;
            return true;
        }
        if (pickupSpot() != null && !c.pickupStarted) {
            Map<String, Integer> lagging = new TreeMap<>(c.sorterLag);
            lagging.values().removeIf(n -> n < 1);
            if (startPickup(c, lagging)) return true;
        }
        if (!c.lagWalked) {
            c.lagWalked = true;
            Pos at = sorterLookAt(plan.geo, items).stream().findFirst().orElse(null);
            if (at != null) {
                note = "waiting for the sorter";
                run(bot.walkTo(at, 3));
                return true;
            }
        }
        if (now < c.lagLookAt) {
            note = "waiting for the sorter: " + PartialBuild.items(c.sorterLag);
            sleep(POLL_TICKS);
            return true;
        }
        Set<Pos> look = sorterLookAt(plan.geo, items);
        c.lagLookAt = Long.MAX_VALUE; // set again when the look is over (stepRestock's relook)
        if (look.isEmpty()) {
            c.lagLookAt = now + pollMs;
            sleep(POLL_TICKS);
            return true;
        }
        c.extraScan = look;
        c.scanQueue = null;
        c.stockSaved = false;
        c.restockRelook = true;
        acted = true;
        return true;
    }

    /**
     * Per item, the cells of {@code needs} that neither the inventory nor the stock index covers: the restock loads,
     * BUILD and VERIFY leave out that many cells of the item (its last ones in build order). Empty when partial builds
     * are off ({@code build.partialBuild}) or nothing is short.
     */
    private Map<String, Integer> uncovered(Map<String, Integer> needs) {
        if (!config.build.partialBuild || needs == null || needs.isEmpty()) return Map.of();
        // a carried round: everything the inventory does not cover waits (no trip this round)
        if (carriedRound) return minusInventory(needs);
        return stockShortfall(minusInventory(needs));
    }

    /** Partial build: every item of {@code needs} is short (some of its cells are {@link #uncovered}). */
    private boolean shortOnly(Map<String, Integer> needs) {
        Map<String, Integer> uncovered = uncovered(needs);
        return !uncovered.isEmpty() && uncovered.keySet().containsAll(needs.keySet());
    }

    /**
     * A started tile short of materials: is a trip worth it ({@link PartialBuild#worthATrip})? With carried items built
     * apart ({@code build.partialBuildCarried}) only what the stock adds beyond the inventory ({@code want}) counts, since
     * the carried cells need no trip; else the inventory and the stock together ({@code needs}), as before 2026-10-13.
     */
    private boolean worthATrip(Map<String, Integer> needs, Map<String, Integer> want, Map<String, Integer> shortfall) {
        return PartialBuild.worthATrip(config.build.partialBuildCarried ? want : needs, shortfall,
            PartialBuild.loadCells(config.build.reservedSlots), config.build.partialTripMinCells);
    }

    /** The bot's count of each item of {@code items} (0 included). */
    private Map<String, Integer> carriedOf(Set<String> items) {
        InventoryView inv = bot.inventory();
        Map<String, Integer> m = new TreeMap<>();
        for (String id : items) m.put(id, inv.count(id));
        return m;
    }

    /**
     * Carried items never wait ({@code build.partialBuildCarried}; live 2026-10-13, ellen [3,4]: cyan terracotta put
     * into the inventory waited for the next look and a load's worth): a started tile short of materials whose stock
     * part is not worth a trip builds the cells the inventory covers at once. The round ({@link #carriedRound}) leaves
     * out every other cell, so RESTOCK withdraws nothing and BUILD starts from where the bot is. A round with the same
     * items carried and nothing placed since the last one (cells it cannot place) is not repeated: false, the tile waits.
     */
    private boolean buildCarried(Map<String, Integer> needs, Map<String, Integer> shortfall) {
        if (!config.build.partialBuild || !config.build.partialBuildCarried || tile.placed <= 0) return false;
        Map<String, Integer> have = PartialBuild.carried(needs, bot.inventory().contents());
        if (have.isEmpty()) return false;
        if (have.equals(carriedRoundItems) && tile.placed == carriedRoundPlaced) {
            if (!carriedRoundNoted) {
                carriedRoundNoted = true;
                log.info("{}: the last round with {} carried placed none of them; they wait with the rest", label(),
                    PartialBuild.items(have));
            }
            return false;
        }
        carriedRoundItems = have;
        carriedRoundPlaced = tile.placed;
        carriedRoundNoted = false;
        // cells an earlier round skipped with their item in hand are tried again with what is carried now (as VERIFY
        // does when it hands back to RESTOCK); the check above stops a round that places none of them from repeating
        deferred.clear();
        int cells = PartialBuild.cells(have);
        String compact = compact(shortfall);
        boolean waited = waitingForMaterials() || job.status == JobStatus.WAITING_MATERIALS;
        endMaterialsWait();
        job.status = JobStatus.ACTIVE;
        job.statusDetail = "short: " + compact + "; building the rest";
        saveJob(job);
        waitingNeeds.remove(job.id);
        waitingCheckedAt.remove(job.id);
        log.warn("{} short of materials: {}; building the {} cells the inventory covers ({}) at once, no trip", label(),
            compact, cells, PartialBuild.items(have));
        String partly = "Placing the " + ShortageText.count(cells) + " blocks the bot carries now; it needs "
            + ShortageText.needs(shortfall) + " before the map is filled.";
        if (announceStart) started(partly);
        else {
            started(null);
            notifier.detail(waited ? "Materials available" : "Building partly", label() + ": " + partly);
        }
        go(Phase.GEAR);
        carriedRound = true; // after go(): it is a field of the round, not of the phase
        return true;
    }

    private void stepGear() {
        InventoryView inv = bot.inventory();
        MapArtConfig.StorageCfg st = config.site.storage;
        // a colour-suppression job that rides: the ride's kit comes along from the first pass on (the supply chests
        // are by the base, the fill and the ride far up by the muncher)
        if (rideJob() && rideKit()) return;
        if (!inv.hasTools()) {
            int a = ctx.attempt("tools");
            if (a < st.toolChests.size()) {
                run(bot.withdraw(st.toolChests.get(a), toolsWanted(), r -> toolChestIds.addAll(r.moved().keySet())), null,
                    r -> log.warn("tool withdraw failed: {}", r));
                return;
            }
            alertOnce("tools", "No tools", "No pickaxe/axe/shovel in the inventory or the tool chests; breaking blocks will be slow.");
        }
        // a platform that fights carries a weapon: live 2026-10-14 01:47 (soul sand) 8 pillagers were fought bare-handed,
        // health 20 -> 8.1, while an axe sat in the tool chest (tools were fetched only when none were carried)
        if (platform != null && platform.fightsMobs() && config.combat.enabled && !st.toolChests.isEmpty()
            && !carriesWeapon(inv)) {
            int a = ctx.attempt("weapon");
            if (a < st.toolChests.size()) {
                run(bot.withdraw(st.toolChests.get(a), weaponWanted(), r -> toolChestIds.addAll(r.moved().keySet())), null,
                    r -> log.warn("weapon withdraw failed: {}", r));
                return;
            }
            if (ctx.attempt("weapon-warned") == 0) // a log line, not a Discord alert (discord quiet by default)
                log.warn("no axe above the tool safety floor in the inventory or the tool chests: fights are bare-handed");
        }
        if (foodCarried(inv) < foodMin()) {
            int a = ctx.attempt("food");
            if (a < st.foodChests.size()) {
                run(bot.withdraw(st.foodChests.get(a), foodWanted(inv), r -> { }), null, r -> log.warn("food withdraw failed: {}", r));
                return;
            }
            if (foodCarried(inv) == 0) alertOnce("food", "No food", "No food in the inventory or the food chests.");
        }
        boolean bootsWaitXp = config.build.gearGuard ? bootsWaitForXp(inv) : bootsRetired && inv.count(XP_BOTTLE) <= 0;
        if (platform.soulSpeedBoots && !isBoots(inv.bootsItem()) && bootsWaitXp) {
            // taken off worn out: on again only with bottles to mend them (else they would wear on until they break)
            List<Pos> xp = st.xpChests;
            int a = ctx.attempt("boots-xp");
            if (a < xp.size()) {
                run(bot.withdraw(xp.get(a), Map.of(XP_BOTTLE, Math.max(bootsXpWanted(inv), Math.max(BOOTS_MEND_PROBE_BOTTLES,
                    Math.max(1, config.map.xpBottlesPerTrip)))), r -> { }), null, r -> log.warn("XP for the boots failed: {}", r));
                return;
            }
        } else if (platform.soulSpeedBoots && !isBoots(inv.bootsItem())) {
            String held = heldBoots(inv);
            if (held != null) {
                if (bootsRetired) bootsRetired = false; // bottles carried: worn again, mended at the next safe point
                if (ctx.attempt("equip") < 2) {
                    run(equipBoots(inv, held), null, r -> log.warn("equip boots failed: {}", r));
                    return;
                }
            } else {
                List<String> ids = bootsIds();
                int a = ctx.attempt("boots");
                if (a < st.bootChests.size() * ids.size()) {
                    run(bot.withdraw(st.bootChests.get(a / ids.size()), Map.of(ids.get(a % ids.size()), 1), r -> { }),
                        null, r -> log.warn("boots withdraw failed: {}", r));
                    return;
                }
            }
            alertOnce("boots", "No Soul Speed boots", "Building on " + platform.id + " without Soul Speed boots.");
        }
        if (config.build.wearBoots && inv.bootsItem() == null) {
            // the trimmed kit: carried boots worn free a slot for materials. Only into an
            // empty feet slot; a worn piece is never moved
            String held = heldBoots(inv);
            if (held != null && ctx.attempt("wear") < 2) {
                log.info("wearing {}: frees an inventory slot for materials", held);
                run(equipBoots(inv, held), null, r -> log.warn("wearing {} failed: {}", held, r));
                return;
            }
        }
        if (fallKitDue(inv)) return;
        if (ensureCollection(false)) return;
        go(Phase.RESTOCK);
    }

    /**
     * The fall rescue's climbing kit ({@code build.fallRescueKit}): the first
     * {@code build.fallRescueBlocks} entry the material chests hold, else the first entry; null when the kit is off.
     */
    private @Nullable String fallKitItem() {
        MapArtConfig.BuildCfg b = config.build;
        if (!b.fallRescue || !b.fallRescuePillar || b.fallRescueKit <= 0 || b.fallRescueBlocks.isEmpty()) return null;
        for (String id : b.fallRescueBlocks) if (id != null && stocked(id) > 0) return id;
        return b.fallRescueBlocks.getFirst();
    }

    /** What the leftovers keep of the kit: its block → {@code build.fallRescueKit}; empty when the kit is off. */
    private Map<String, Integer> fallKit() {
        String id = fallKitItem();
        return id == null ? Map.of() : Map.of(id, config.build.fallRescueKit);
    }

    /** {@code needs} and the kit on top: what a deposit or drop of leftovers keeps. */
    private Map<String, Integer> withFallKit(Map<String, Integer> needs) {
        Map<String, Integer> m = new HashMap<>(needs);
        fallKit().forEach((id, n) -> m.merge(id, n, Integer::sum));
        return m;
    }

    /**
     * GEAR: the kit fetched from a material chest when the bot carries fewer than half of it of every
     * {@code build.fallRescueBlocks} entry (whole stacks: what goes beyond the kit leaves with the next leftovers).
     * Two chests at most a round. True while the withdrawal runs.
     */
    private boolean fallKitDue(InventoryView inv) {
        String id = fallKitItem();
        if (id == null) return false;
        int kit = config.build.fallRescueKit;
        for (String b : config.build.fallRescueBlocks) if (b != null && inv.count(b) >= Math.max(1, kit / 2)) return false;
        List<Pos> chests = new ArrayList<>();
        StockIndex st = coord.stock();
        synchronized (st) {
            for (Pos c : withdrawOrder(st.containersWith(id))) if (isMaterialContainer(c)) chests.add(c);
        }
        int a = ctx.attempt("fall-kit");
        if (a < Math.min(chests.size(), 2)) {
            Pos chest = chests.get(a);
            int want = kit - inv.count(id);
            log.info("fall rescue kit: fetching {} {} from {} ({} carried)", want, id, chest, inv.count(id));
            run(bot.withdraw(chest, Map.of(id, want), r -> afterTransfer(chest, r)), null,
                r -> log.warn("fall rescue kit: withdraw from {} failed: {}", chest, r));
            return true;
        }
        if (ctx.attempt("fall-kit-warned") == 0)
            log.warn("fall rescue kit: no {} in the material chests; a climb uses what the bot carries", id);
        return false;
    }

    // =====================================================================================
    // RESTOCK / BUILD / VERIFY
    // =====================================================================================

    /** One withdrawal of the load: the chest, what to take, and the stand cell of the restock sweep (or null). */
    private record Withdrawal(Pos chest, Map<String, Integer> items, Pos standAt) { }

    private void stepRestock() {
        if (descendToFoot()) return;
        if (resumeNearScan()) return;
        Ctx c = ctx;
        if (c.pickupBefore != null && afterPickup(c)) return;
        if (forceScan && !c.scanDone) {
            if (stockRoutine(true)) return;
            forceScan = false;
            c.scanDone = true;
        }
        if (c.restockRelook) {
            // withdrawals came up short: the targeted re-look, then the load is planned again from the fresh snapshots
            if (stockRoutine(false, null, c.extraScan)) return;
            c.restockRelook = false;
            if (c.lagSince >= 0) c.lagLookAt = clock.millis() + Math.max(1, config.build.sorterRefillPollSeconds) * 1000L;
            c.withdrawals = null;
            c.loadPlan = null;
            c.loadPlans = null;
            c.unavailable.clear();
            if (config.build.partialBuild) c.lanes = null; // the cells left out follow the fresh snapshots
        }
        WorldView w = bot.world();
        InventoryView inv = bot.inventory();
        if (c.lanes == null) {
            if (plan.allLoaded(w)) c.lanes = replan(w);
            else if (lanesCache != null) c.lanes = config.build.suppressionFreshLanes && suppressionTile() ? stillOpen(lanesCache) : lanesCache;
            else {
                if (awaitPlatformLoaded()) return;
                c.lanes = replan(w);
            }
            c.needs = needsOf(c.lanes); // cells left to VERIFY included: their items stay unless crowded (keepForLoad)
            // partial build: no load is planned for the cells the stock cannot cover (each short item's last cells)
            Map<String, Integer> uncovered = leftOut(c);
            c.buildable = buildable(PartialBuild.withoutShort(c.lanes, uncovered));
            c.farLeftOut = null; // the far rules look at each load again (FarStorage.fewFarCells)
            c.farReplans = 0;
            if (!uncovered.isEmpty()) {
                log.info("restock: leaving out {} cells the stock cannot cover ({}); {} lanes left", PartialBuild.cells(uncovered),
                    PartialBuild.items(uncovered), c.buildable.size());
            }
        }
        if (!carriedRound && config.build.nearScanFirst && c.withdrawals == null) {
            Map<String, Integer> near = nearAvailable(plan.geo);
            Map<String, Integer> counts = nearCounts(plan.geo);
            Set<String> farItems = new TreeSet<>();
            for (var e : c.needs.entrySet()) {
                if (e.getValue() > near.getOrDefault(e.getKey(), 0) && stocked(e.getKey()) > counts.getOrDefault(e.getKey(), 0))
                    farItems.add(e.getKey());
            }
            // sorterRefills: the sorter returns them, so there is no far trip to sweep every near chest for (live
            // 2026-10-14 01:05-01:08: a 3-minute sweep of all 118 after a reconnect, for white carpet still in the sorter)
            if (refills(platform)) farItems.removeIf(id -> nearReclaimable(id, plan.geo));
            if (!farItems.isEmpty() && nearScanFirst(plan.geo, farItems, "before a far trip", true)) return;
            if (c.nearRefreshed) {
                c.nearRefreshed = false;
                c.buildable = buildable(PartialBuild.withoutShort(c.lanes, leftOut(c)));
                c.loadPlan = null;
                c.loadPlans = null;
                c.preDropPlans = null;
                c.noDropPlans = null;
                c.keep = null;
            }
        }
        if (c.buildable.isEmpty()) {
            if (!c.sorterLag.isEmpty() && sorterWait(c)) return;
            go(Phase.VERIFY);
            return;
        }
        if (c.lagSince >= 0) {
            log.info("restock: the sorter returned what was missing (waited {}): {} lanes can be built", 
                formatDuration(clock.millis() - c.lagSince), c.buildable.size());
            c.lagSince = -1;
        }
        if (carriedRound && !c.depositDone) {
            // a carried round makes no trip: nothing goes back (no withdrawal needs the room) and the load is planned
            // for the carried cells only; the leftovers wait for the next real restock
            c.depositDone = true;
            c.declogged = true;
        }
        if (!c.depositDone) {
            // XP bottles left from a mend that hold a slot beyond build.reservedSlots go back first (unless a mend is
            // due): that slot is the load's. Within the reserve they cost no material slot and stay until STORE
            boolean crowding = inv.count(XP_BOTTLE) > 0 && keepSlots(inv)
                - MapPaneAtStore.kitSlots(config.build.mapPaneAtStore, inv) > config.build.reservedSlots;
            if ((crowding || c.tidySince >= 0) && tidyWorkingItems(c, false)) return;
            // materials only: the account is the owner's own, everything else in it stays (Leftovers)
            Leftovers lo = leftovers(c);
            noteKept(inv, lo);
            if (c.keep == null) c.keep = keepForLoad(c, inv, lo);
            Map<String, Integer> junk = lo.junk(inv.contents(), c.keep, catalog::maxStackSize);
            Map<String, Integer> keep = c.keep;
            boolean dropJunk = true;
            if (!junk.isEmpty() && (config.build.mergeDrops || config.build.lazyDrop) && !c.declogged && !c.dropStarted) {
                // one drop, not two (live: a crowded load's return right after the leftovers, 1-6 s more each): the
                // load's crowded check first, on the inventory as the leftovers drop leaves it; what crowds the load
                // goes along with the leftovers
                if (c.preDropPlans == null) {
                    planLoad(c, inv, junk, p -> c.preDropPlans = p);
                    if (c.preDropPlans == null) return; // planning off the tick thread
                }
                RestockLoads.Choice lp = c.preDropPlans;
                if (lp.clogged() && config.build.mergeDrops) {
                    c.declogged = true;
                    log.info("crowded load: {} carried for later lanes hold {} slot{}; without them the load covers {}"
                        + " cells instead of {}: returning them with the leftovers", lp.unused(), lp.unusedSlots(),
                        lp.unusedSlots() == 1 ? "" : "s", lp.freedCells(), lp.keptCells());
                    junk = new TreeMap<>(junk);
                    keep = new TreeMap<>(keep);
                    for (String id : lp.unused().keySet()) {
                        junk.put(id, Math.max(1, inv.count(id)));
                        keep.put(id, 0);
                    }
                    c.keep = keep;
                } else if (!lp.clogged() && config.build.lazyDrop
                    && !(config.build.suppressionDropLeftovers && suppressionTile())) {
                    // (a colour-suppression pass always drops them: see build.suppressionDropLeftovers)
                    // the leftovers drop is a walk to the drop point and back (12-26 s live): only when the load
                    // covers enough more cells without them. The same load planned with them carried
                    if (c.noDropPlans == null) {
                        planLoad(c, inv, Map.of(), false, p -> c.noDropPlans = p);
                        if (c.noDropPlans == null) return; // planning off the tick thread
                    }
                    RestockLoads.Choice nd = c.noDropPlans;
                    double gain = RestockLoads.gainSeconds(c.buildable, lp.kept(), nd.kept());
                    if (!nd.clogged() && gain >= 0 && lazyDropDebt + gain < config.build.lazyDropSeconds) {
                        dropJunk = false;
                        lazyDropDebt += gain;
                        log.info("leftovers stay: {} would save {} s of restock now ({} s so far); a trip to the drop point"
                            + " costs about {} s", junk, String.format(Locale.ROOT, "%.1f", gain),
                            String.format(Locale.ROOT, "%.1f", lazyDropDebt), config.build.lazyDropSeconds);
                    }
                }
            }
            if (!junk.isEmpty() && junk.keySet().stream().anyMatch(lo::isJunk)) {
                // junk is thrown at its own spot, never into the sorter with the leftovers
                junk = new TreeMap<>(junk);
                junk.keySet().removeIf(lo::isJunk);
            }
            if (dropJunk && !junk.isEmpty()) {
                c.dropStarted = true;
                lazyDropDebt = 0;
                if (returnLeftovers(c, junk, id -> lo.material(id) && !lo.isJunk(id), keep, "")) return;
            }
            if (throwJunk(c, lo, inv)) return;
            c.depositDone = true;
        }
        if (c.withdrawals == null && !carriedRound && foodCarried(inv) < foodMin()) {
            // at the chests anyway: top up food before the load is planned (it takes a slot); a carried round is not at
            // the chests (GEAR got the food it needs)
            int a = c.attempt("food");
            if (a < config.site.storage.foodChests.size()) {
                run(bot.withdraw(config.site.storage.foodChests.get(a), foodWanted(inv), r -> { }), null,
                    r -> log.warn("food withdraw failed: {}", r));
                return;
            }
        }
        // build.elytraLegs: the elytra and the rockets from the rocket chest before the load is planned (each takes a
        // slot); a carried round is not at the chests
        if (c.withdrawals == null && !carriedRound && config.build.elytraLegs && flightKit(c, inv)) return;
        if (c.withdrawals == null) {
            if (c.loadPlan == null) {
                // planned again after a re-look; a planner task cut short (pause, reconnect) left no plan: planned anew
                if (c.loadPlans == null) planLoad(c, inv);
                if (c.loadPlans == null) return; // planning off the tick thread
                RestockLoads.Choice lp = c.loadPlans;
                if (!c.declogged && lp.clogged()) {
                    // carried materials this load does not use hold slots it could use (live j0002: 29 restocks of
                    // "0 + 36 cells"): they go back to storage, and the load is planned again without them
                    c.declogged = true;
                    log.info("crowded load: {} carried for later lanes hold {} slot{}; without them the load covers {} cells"
                        + " instead of {}: returning them", lp.unused(), lp.unusedSlots(), lp.unusedSlots() == 1 ? "" : "s",
                        lp.freedCells(), lp.keptCells());
                    Map<String, Integer> keep = new TreeMap<>(c.needs);
                    for (String id : lp.unused().keySet()) keep.put(id, 0);
                    Leftovers lo = leftovers(c);
                    if (returnLeftovers(c, lp.unused(), lo::material, keep, "unused-")) {
                        c.loadPlans = null;
                        return;
                    }
                }
                if (fewFarCells(c, lp.kept())) {
                    c.loadPlans = null; // planned again without them
                    return;
                }
                c.loadPlan = lp.kept();
                c.farBatch = c.farBatching == null ? null : c.farBatching.batchOf(c.loadPlan);
            }
            RestockPlanner.Plan rp = c.loadPlan;
            alertIfCrowded(c, inv, c.plannedHeld, c.plannedFree, rp);
            c.lanesCovered = rp.lanesCovered();
            c.lanesLoaded = rp.lanesLoaded();
            c.withdrawals = allocate(rp.withdraw(), c, farOn() ? served(c, rp) : null);
            Map<String, Integer> trickle = pickupMissing(c);
            // the chests the sorter refills first: their last snapshots are as old as the last visit (live 2026-10-14
            // ~04:00: stand after stand between the centre and the edge while the white carpet chests were full)
            if (!trickle.isEmpty() && !c.pickupLooked) {
                c.pickupLooked = true;
                if (nearLookAll(plan.geo, trickle.keySet(), "before a pickup stand", false, PICKUP_LOOK_AGE_MS)) {
                    c.withdrawals = null;
                    c.loadPlan = null;
                    c.loadPlans = null;
                    c.unavailable.clear();
                    return;
                }
            }
            if (!trickle.isEmpty() && startPickup(c, trickle)) return;
            // Also guard far withdrawals caused only by stack round-up when the far-storage rules are off.
            Set<String> farItems = new TreeSet<>();
            for (Withdrawal withdrawal : c.withdrawals)
                if (farContainer(withdrawal.chest())) farItems.addAll(withdrawal.items().keySet());
            // build.farLoadFill: what an earlier load left in the near chests (and the sorter put back since) is
            // looked at before this load goes far for the same item: those chests were not opened since
            Set<String> unseen = new TreeSet<>(farItems);
            unseen.retainAll(fillUnseen);
            if (!unseen.isEmpty()) {
                boolean looking = nearScanFirst(plan.geo, unseen, "before a far trip after a far load fill", true, 0);
                fillUnseen.removeAll(unseen);
                if (looking) {
                    c.withdrawals = null;
                    c.loadPlan = null;
                    c.loadPlans = null;
                    return;
                }
            }
            if (!farItems.isEmpty() && nearScanFirst(plan.geo, farItems, "before a far trip", true)) {
                c.withdrawals = null;
                c.loadPlan = null;
                c.loadPlans = null;
                return;
            }
            log.info("restock: {} lanes left, load covers {}{}{}, withdraw {}{}{}{}", c.buildable.size(), rp.lanesCovered(),
                rp.partialCells() > 0 ? " + " + rp.partialCells() + " cells of the next" : "",
                rp.flipped() ? " (walked the other way: first lane S→N)" : "",
                rp.withdraw(), c.unavailable.isEmpty() ? "" : ", not in stock index: " + c.unavailable,
                deferred.isEmpty() ? "" : "; " + deferred.size() + " cells left to VERIFY",
                rp.rounds() > 0 ? String.format(Locale.ROOT, " (planned: %d load%s left, about %.0f s of restock)",
                    rp.rounds(), rp.rounds() == 1 ? "" : "s", rp.seconds()) : "");
            logFarTrip(c);
            if (!c.fillLeftNear.isEmpty()) {
                fillUnseen.addAll(c.fillLeftNear.keySet());
                log.info("restock: far load fill (build.farLoadFill): all the load serves of {} from far storage; the"
                    + " near chests keep {} for the next loads", c.fillLeftNear.keySet(), c.fillLeftNear);
            }
            if (c.withdrawals.size() >= 2) {
                // one pass along the sorter instead of nearest-first hops, in the direction that ends nearer the lane
                Deque<Withdrawal> wds = c.withdrawals;
                List<Pos> chests = new ArrayList<>();
                for (Withdrawal x : wds) chests.add(x.chest());
                planSweep(chests, LaneFlip.start(c.buildable, rp), "restock", visits -> reorder(wds, visits));
                return;
            }
        }
        Withdrawal wd = c.withdrawals.poll();
        if (wd != null) {
            WithdrawMode mode = config.build.topUpStacks ? WithdrawMode.TOP_UP : WithdrawMode.WHOLE_STACKS;
            run(bot.withdraw(wd.chest(), wd.standAt(), wd.items(), mode, r -> {
                c.withdrawn += sum(r.moved());
                r.moved().forEach((id, n) -> {
                    if (n != null && n > 0) withdrawnMaterials.add(id); // a material: its leftovers may go back
                });
                afterTransfer(wd.chest(), r);
                if (r.problem() != null) log.info("withdraw from {}: {}", wd.chest(), r.problem());
                c.restockShort.addAll(stockShort(wd.items(), r));
            }), null, r -> log.warn("withdraw from {} failed: {}", wd.chest(), r));
            return;
        }
        if (!c.restockShort.isEmpty() && !c.restockRelooked) {
            // a chest held less than its snapshot said (taken since, or never there): look again at the chests that
            // hold or could hold those items and plan the rest of the load from what is really there
            c.restockRelooked = true;
            Set<Pos> extra = shortageCandidates(c.restockShort);
            if (!extra.isEmpty()) {
                log.info("withdrawals came up short of {}; re-checking {} material containers that hold or could hold"
                    + " them, then planning the load again", c.restockShort, extra.size());
                c.extraScan = extra;
                c.scanQueue = null;
                c.stockSaved = false;
                c.restockRelook = true;
                acted = true;
                return;
            }
        }
        if (c.lanesCovered >= c.buildable.size() && !carriedRound) {
            // last load of the tile: bring the empty map and the pane along (one each with the trimmed kit); never in a
            // carried round, which cannot finish the tile and makes no trip
            MapArtConfig.StorageCfg st = config.site.storage;
            if (inv.count(MAP) < 1) {
                int a = c.attempt("map");
                if (a < st.mapChests.size()) {
                    run(bot.withdraw(st.mapChests.get(a), null, Map.of(MAP, 1), mapPaneMode(), r -> { }), null,
                        r -> log.warn("map withdraw failed: {}", r));
                    return;
                }
            }
            if (inv.count(PANE) < 1) {
                int a = c.attempt("pane");
                if (a < st.paneChests.size()) {
                    run(bot.withdraw(st.paneChests.get(a), null, Map.of(PANE, 1), mapPaneMode(), r -> { }), null,
                        r -> log.warn("pane withdraw failed: {}", r));
                    return;
                }
            }
        }
        if (c.withdrawn > 0) saveStock();
        lastRestockWithdrawn = c.withdrawn;
        RestockPlanner.Plan rp = c.loadPlan;
        if (rp != null && rp.stopAtCut()) {
            // the round ends where the planner cut it: the covered lanes and the first cells of the next, nothing more
            buildLanes = RestockLoads.cutLanes(c.buildable, rp);
            buildLanesLoaded = buildLanes.size();
            buildRoundCut = rp.lanesCovered() < c.buildable.size();
        } else {
            buildLanes = LaneFlip.walked(c.buildable, rp);
            buildLanesLoaded = c.lanesLoaded;
            buildRoundCut = false;
        }
        buildFarLeftOut = c.farLeftOut != null;
        go(Phase.BUILD);
    }

    /**
     * Plans this RESTOCK's load into {@code c.loadPlans}: on the tick thread for a cheap planner, else off it (the
     * planner's {@link RestockPlanner#expensive}: the split DP takes tens of ms) as a task whose result arrives on the
     * tick thread. The request is built here, on the tick thread, from copies (the planner thread reads nothing of the
     * engine's).
     */
    private void planLoad(Ctx c, InventoryView inv) {
        planLoad(c, inv, Map.of(), true, p -> c.loadPlans = p);
    }

    private void planLoad(Ctx c, InventoryView inv, Map<String, Integer> gone, Consumer<RestockLoads.Choice> sink) {
        planLoad(c, inv, gone, false, sink);
    }

    /**
     * {@link #planLoad(Ctx, InventoryView)} on the inventory as a drop of {@code gone} (item id → count) will leave it:
     * those counts less, their slots free (an item that goes whole frees the slots it holds, a surplus its whole
     * stacks, as {@link #keepForLoad} counts them). {@code sink} gets the answer on the tick thread. With far-storage
     * batching ({@link #farBatchingOn}) the planner is {@link FarStorage.Batching}; {@code forLoad}: this is the load
     * itself (not the crowded check before the leftovers drop), whose batching planner {@code c.farBatching} names the
     * batch.
     */
    private void planLoad(Ctx c, InventoryView inv, Map<String, Integer> gone, boolean forLoad,
                          Consumer<RestockLoads.Choice> sink) {
        Map<String, Integer> held = new TreeMap<>();
        Map<String, Integer> heldSlots = new TreeMap<>();
        Map<String, Integer> slotsUsed = inv.slotsUsed();
        Map<String, Integer> stacks = new HashMap<>();
        int freed = 0;
        for (var e : gone.entrySet()) {
            int g = e.getValue() == null ? 0 : e.getValue();
            int have = inv.count(e.getKey());
            if (g <= 0 || have <= 0) continue;
            int max = Math.max(1, catalog.maxStackSize(e.getKey()));
            freed += g >= have ? slotsUsed.getOrDefault(e.getKey(), (have + max - 1) / max) : g / max;
        }
        for (String id : c.needs.keySet()) {
            int max = Math.max(1, catalog.maxStackSize(id));
            stacks.put(id, max);
            int g = Math.max(0, gone.getOrDefault(id, 0));
            int n = inv.count(id) - g;
            if (n <= 0) continue;
            held.put(id, n);
            Integer s = slotsUsed.get(id);
            if (s != null && s > 0) heldSlots.put(id, g > 0 ? Math.max((n + max - 1) / max, s - g / max) : s);
        }
        // empty slots for materials; the planner adds the slots the held materials occupy. Items the bot does not
        // touch (the owner's) are not empty slots: the load is planned around them
        int free = materialSlots(inv);
        // the tile's last load leaves room for the empty map and the pane fetched after it
        int forMapPane = (inv.count(MAP) < 1 ? 1 : 0) + (inv.count(PANE) < 1 ? 1 : 0) - (inv.freeSlots() - free);
        free += freed;
        RestockPlanner.Site site = new RestockPlanner.Site(botFeet(), depots(c.needs.keySet())::get,
            RestockPlanner.Costs.DEFAULT, config.build.restockLaneFlip);
        // planned over the lanes BUILD will run: a stalled cell's lane must not stand in for the next lane's load
        RestockPlanner.Request req = new RestockPlanner.Request(c.buildable, held, heldSlots, free,
            id -> stacks.getOrDefault(id, 64), Math.max(0, forMapPane), config.build.topUpStacks, site);
        c.plannedHeld = held;
        c.plannedFree = free;
        if (forLoad) log.debug("restock plan: free {} invFree {} reserved {} keepSlots {} held {} heldSlots {} needs {} gone {}",
            free, inv.freeSlots(), config.build.reservedSlots, keepSlots(inv), held, heldSlots, c.needs, gone);
        // what may go back when it crowds the load: materials the stock index holds enough of for the rest of the tile
        // (as keepForLoad: a returned stack is not counted on to come back from the sorter in time)
        Leftovers lo = leftovers(c);
        Set<String> returnable = new HashSet<>();
        for (String id : held.keySet()) {
            // with far-storage batching: only what near storage can supply again for the rest of the tile (a far item
            // returned here would cost another far trip)
            if (lo.material(id) && stocked(id) >= c.needs.getOrDefault(id, 0)
                && nearStocked(id) >= c.needs.getOrDefault(id, 0)) returnable.add(id);
        }
        FarStorage.Stock far = farBatchingOn() ? farStock(c.needs.keySet()) : FarStorage.Stock.NONE;
        RestockPlanner planner = far.anyFar() ? new FarStorage.Batching(restockPlanner, far) : restockPlanner;
        if (forLoad) c.farBatching = planner instanceof FarStorage.Batching b ? b : null;
        // read here, on the tick thread (the planner thread reads nothing of the engine's)
        boolean keepReused = config.build.crowdKeepReused;
        boolean laneCells = config.build.crowdLaneCells;
        if (!planner.expensive()) {
            sink.accept(RestockLoads.choose(planner, req, returnable::contains, keepReused, laneCells));
            return;
        }
        long started = System.nanoTime();
        run(new AsyncTask<>("plan restock load", executor,
            () -> RestockLoads.choose(planner, req, returnable::contains, keepReused, laneCells), p -> {
            sink.accept(p);
            log.debug("restock load planned in {} ms", (System.nanoTime() - started) / 1_000_000);
        }), null, r -> {
            // never stuck on the planner: the greedy load instead
            log.warn("restock planner failed ({}); planning this load greedily", r);
            RestockPlanner greedy = far.anyFar() ? new FarStorage.Batching(new GreedyRestockPlanner(), far)
                : new GreedyRestockPlanner();
            if (forLoad) c.farBatching = greedy instanceof FarStorage.Batching b ? b : null;
            sink.accept(RestockLoads.choose(greedy, req, returnable::contains, keepReused, laneCells));
        });
    }

    /** The far-storage rules apply to this tile ({@code build.farStorage}, {@code build.farStorageBlocks}). */
    private boolean farOn() {
        return config.build.farStorage && config.build.farStorageBlocks > 0 && plan != null;
    }

    /** Far-storage batching ({@code build.farStorageBatching}) for this RESTOCK: never in a carried round (no trip). */
    private boolean farBatchingOn() {
        return farOn() && config.build.farStorageBatching && !carriedRound;
    }

    /**
     * {@code id} in the material containers of near storage, by the stock index; all of them without far-storage
     * batching ({@link #farBatchingOn}), so the rules that use it are as before then.
     */
    private int nearStocked(String id) {
        if (!farBatchingOn()) return stocked(id);
        StockIndex st = coord.stock();
        int n = 0;
        synchronized (st) {
            for (Pos chest : st.containersWith(id)) {
                if (!isMaterialContainer(chest) || farContainer(chest)) continue;
                StockIndex.Snapshot s = st.containers.get(chest.key());
                if (s != null) n += s.items.getOrDefault(id, 0);
            }
        }
        return n;
    }

    /**
     * Item → cells the load {@code rp} serves: its cells in the lanes BUILD runs, and the cells of its far batch
     * ({@link FarStorage.Batching#batchOf}) where more.
     */
    private Map<String, Integer> served(Ctx c, RestockPlanner.Plan rp) {
        Map<String, Integer> m = new HashMap<>(FarStorage.cells(RestockLoads.cutLanes(c.buildable, rp)));
        FarStorage.Batch b = c.farBatching == null ? null : c.farBatching.batchOf(rp);
        if (b != null) b.cells().forEach((id, n) -> m.merge(id, n, Math::max));
        return m;
    }

    /** {@code chest} is far storage for the current platform ({@code build.farStorageBlocks}; whatever the switch). */
    private boolean farContainer(Pos chest) {
        return FarStorage.far(plan == null ? null : plan.geo, config.build.farStorageBlocks, chest);
    }

    /** Index of the first material region holding {@code p} ({@code site.storage.materialRegions}); −1 = none. */
    private int materialRegionOf(Pos p) {
        List<MapArtConfig.RegionCfg> regions = CsMaterials.allRegions(config.site);
        for (int i = 0; i < regions.size(); i++) if (regions.get(i) != null && regions.get(i).contains(p)) return i;
        return -1;
    }

    /**
     * The stock of {@code items} split into near and far storage ({@link FarStorage.Stock}), by the stock index's
     * material containers; the far part per material region, in the order a withdrawal reaches them.
     */
    private FarStorage.Stock farStock(Collection<String> items) {
        Map<String, Integer> near = new HashMap<>();
        Map<String, Map<Integer, Integer>> far = new HashMap<>();
        StockIndex st = coord.stock();
        synchronized (st) {
            for (String id : items) {
                for (Pos chest : withdrawOrder(st.containersWith(id))) {
                    if (!isMaterialContainer(chest)) continue;
                    StockIndex.Snapshot s = st.containers.get(chest.key());
                    int n = s == null ? 0 : s.items.getOrDefault(id, 0);
                    if (n <= 0) continue;
                    if (farContainer(chest)) {
                        far.computeIfAbsent(id, k -> new LinkedHashMap<>()).merge(materialRegionOf(chest), n, Integer::sum);
                    } else {
                        near.merge(id, n, Integer::sum);
                    }
                }
            }
        }
        if (!ctx.sorterLag.isEmpty()) ctx.sorterLag.keySet().forEach(far::remove); // the sorter returns them: no far batch
        return new FarStorage.Stock(near, far);
    }

    /**
     * The order a withdrawal takes containers in: closest to the platform's centre first ({@link #nearestToPlatform});
     * with far storage ({@link #farOn}) every near container before any far one.
     */
    private List<Pos> withdrawOrder(List<Pos> l) {
        List<Pos> out = nearestToPlatform(l);
        if (!farOn()) return out;
        List<Pos> near = new ArrayList<>(out.size());
        List<Pos> far = new ArrayList<>();
        for (Pos p : out) (farContainer(p) ? far : near).add(p);
        near.addAll(far);
        return near;
    }

    /**
     * No far trip for a few blocks ({@code build.farTripMinCells}; live 2026-10-13 07:47: a load wanted 64 purple
     * terracotta, the near chest had 51, and the sweep walked about 250 blocks to the moss sorter for about 12): when
     * the cells of {@code load} that only far storage can supply are more than 0 and fewer than the minimum, those
     * items' far cells are left out of this RESTOCK's lanes ({@link FarStorage#fewFarCells}) and the load is planned
     * again, when a later load of the tile goes there anyway (they go with it); else this load takes them now. The
     * cells a far batch takes along count ({@link #served}): a batched load is a trip for all of them. True when cells
     * were left out (plan again).
     */
    private boolean fewFarCells(Ctx c, RestockPlanner.Plan load) {
        if (!farOn() || carriedRound || c.farReplans >= FAR_REPLANS) return false;
        Set<String> items = needsOf(c.buildable).keySet();
        Map<String, Integer> held = c.plannedHeld != null ? c.plannedHeld : carriedOf(items);
        FarStorage.LeftOut few = FarStorage.fewFarCells(c.buildable, served(c, load), held, farStock(items),
            config.build.farTripMinCells);
        if (few == null) return false;
        c.farReplans++;
        c.buildable = few.lanes();
        Map<String, Integer> all = new TreeMap<>(c.farLeftOut == null ? Map.of() : c.farLeftOut.cells());
        few.cells().forEach((id, n) -> all.merge(id, n, Integer::sum));
        c.farLeftOut = new FarStorage.LeftOut(few.lanes(), all, few.load());
        log.info("restock: the load would go to far storage for only {} cells ({}), fewer than {}: leaving out {} cells"
            + " of {} for a later load that goes there anyway; {} lanes left", few.loadCells(),
            PartialBuild.items(few.load()), config.build.farTripMinCells, few.count(), PartialBuild.items(few.cells()),
            c.buildable.size());
        return true;
    }

    /** The load's far batch, and when the load visits far storage: the far containers, their items and distance (log). */
    private void logFarTrip(Ctx c) {
        if (c.farBatch != null) {
            FarStorage.Batch b = c.farBatch;
            log.info("restock: far storage batch: {} for the far cells of the next {} of {} lanes ({} cells): later loads"
                + " need no far trip for them", b.take(), b.lanes(), b.ofLanes(), b.count());
        }
        if (config.build.farStorageBlocks <= 0 || plan == null) return;
        Map<String, Integer> items = new TreeMap<>();
        int chests = 0;
        double nearest = Double.MAX_VALUE;
        for (Withdrawal w : c.withdrawals) {
            if (!farContainer(w.chest())) continue;
            chests++;
            w.items().forEach((id, n) -> items.merge(id, n, Integer::sum));
            nearest = Math.min(nearest, FarStorage.distanceToArea(plan.geo, w.chest().x(), w.chest().z()));
        }
        if (chests == 0) return;
        log.info("restock: far trip: {} from {} far container{} ({} blocks from the platform)", items, chests,
            chests == 1 ? "" : "s", Math.round(nearest));
    }

    /** Where each item is withdrawn: the material container holding it closest to the platform, as allocate takes it. */
    private Map<String, Pos> depots(Collection<String> items) {
        Map<String, Pos> out = new HashMap<>();
        StockIndex st = coord.stock();
        synchronized (st) {
            for (String id : items) {
                for (Pos chest : withdrawOrder(st.containersWith(id))) {
                    if (!isMaterialContainer(chest)) continue;
                    out.put(id, chest);
                    break;
                }
            }
        }
        return out;
    }

    private WithdrawMode mapPaneMode() {
        return config.build.exactMapAndPane ? WithdrawMode.EXACT : WithdrawMode.WHOLE_STACKS;
    }

    private void stepBuild() {
        Ctx c = ctx;
        List<Lane> lanes = buildLanes;
        if (lanes == null) {
            go(Phase.RESTOCK);
            return;
        }
        if (c.placedAtStart < 0) c.placedAtStart = placedCounter;
        WorldView w = bot.world();
        InventoryView inv = bot.inventory();
        while (c.laneIdx < lanes.size()) {
            Lane lane = buildable(lanes.get(c.laneIdx)); // cells skipped earlier with the item in hand are VERIFY's
            Map<String, Integer> need = new TreeMap<>();
            if (lane != null) {
                for (Placement p : lane.placements())
                    if (w.block(p.pos()).isAir()) need.merge(p.itemId(), 1, Integer::sum); // wrong blocks are VERIFY's
            }
            if (need.isEmpty()) {
                c.laneIdx++;
                continue;
            }
            boolean any = false;
            boolean all = true;
            for (var e : need.entrySet()) {
                int have = inv.count(e.getKey());
                if (have > 0) any = true;
                if (have < e.getValue()) all = false;
            }
            if (!all) {
                // the lanes the load was planned for run with what is there (the partially loaded last one, or a short
                // withdrawal): the lane task skips the cells without items, and they stay for the next round
                boolean loaded = c.laneIdx < buildLanesLoaded;
                if (c.lanesRun > 0 && !loaded) break; // the load ran out: restock
                if (!any) {
                    // nothing for this lane even right after a restock (not obtainable now): VERIFY/STOCK_CHECK deal with it
                    c.laneIdx++;
                    c.lanesSkipped++;
                    continue;
                }
                c.ranShort = true;
            }
            // the walk up the access path comes before the lane is counted: inside runLane it used the lane up (a round
            // from the stair's foot ran its one lane "0 placed" and went back to RESTOCK; local CS run 2026-10-14)
            if (suppressionTile() && climbToPlatform()) return;
            c.laneIdx++;
            c.lanesRun++;
            runLane(lane, () -> greedyLoad(lanes.subList(c.laneIdx, Math.max(c.laneIdx,
                Math.min(buildLanesLoaded, lanes.size()))), true));
            return;
        }
        long placed = placedCounter - c.placedAtStart;
        // a lane run short still has cells for the next load: RESTOCK, not VERIFY (which would count a repair pass); so
        // has a round the planner cut before the last lane, and one whose restock left far-storage cells out (they are
        // fetched by the next RESTOCK once nothing else is left: no repair pass for them)
        boolean ranAll = c.laneIdx >= lanes.size() && !c.ranShort && !buildRoundCut;
        boolean finished = ranAll && !buildFarLeftOut;
        buildLanes = null;
        // a round that only handed cells to VERIFY still converges: those cells are not run again
        if (placed == 0 && lastRestockWithdrawn <= 0 && c.deferred == 0) noProgressRounds++;
        else noProgressRounds = 0;
        // lanes run short of items (cells skipped for a missing item) and lanes not run at all for lack of items
        log.info("build round: {} lanes run{}, {} not run for lack of items, {} placed{}, {}", c.lanesRun,
            c.lanesShort.isEmpty() ? "" : " (" + c.lanesShort.size() + " short of items: " + c.cellsWithoutItems
                + " cells skipped for a missing item)",
            c.lanesSkipped, placed, c.deferred > 0 ? ", " + c.deferred + " left to VERIFY" : "",
            finished ? "all lanes done" : ranAll ? "the far-storage cells next" : "load used up");
        saveJob(job);
        if (noProgressRounds >= NO_PROGRESS_BLOCK) {
            // lanes that ran with all their items and placed nothing: the bot could not get on, the items are there
            // (live 2026-10-19 13:09, ABD [7,1]: wedged in a dripstone cell, 8 lane watchdog lines, and the alert's
            // "materials cannot be obtained" sent the operators to the stock for 21 minutes)
            boolean hadItems = c.lanesRun > 0 && c.lanesShort.isEmpty() && c.lanesSkipped == 0 && !c.ranShort;
            block("no build progress in " + noProgressRounds + " restock rounds; " + (hadItems
                ? "the lanes ran with their items and placed nothing: the bot cannot get on (see the lane lines)"
                : "materials cannot be obtained"));
            return;
        }
        if (noProgressRounds >= NO_PROGRESS_RESCAN) {
            // the stock index may be wrong about these items: a targeted look in STOCK_CHECK, not a full rescan
            recheckItems = lacking(lanes);
            go(Phase.STOCK_CHECK);
            return;
        }
        go(finished ? Phase.VERIFY : Phase.RESTOCK);
    }

    /**
     * Starts {@code lane}. With the in-reach greedy ({@code build.placeInReachGreedy}) the lane goes without the cells
     * already placed (an earlier lane of the load placed them early and
     * reported them then) and with {@code load}, the later lanes of the load, whose cells it may place when they come
     * into reach; the lane's listener counts those too.
     */
    /**
     * A colour-suppression platform with an {@code entry}: true (and the walk started) while the bot's feet are below
     * the bottom glass, so the next walk to the layers starts from the access path. Once per step context.
     */
    private boolean climbToPlatform() {
        if (platform == null || !platform.isColorSuppression() || platform.entry == null || platform.glassY == null
            || platform.glassY.isEmpty() || platform.glassY.getFirst() == null) return false;
        if (botFeet().y() >= platform.glassY.getFirst() - 1 || ctx.attempt("climb-entry") > 0) return false;
        log.info("below color-suppression platform {}: to its access path first ({})", platform.id, platform.entry.key());
        run(bot.walkTo(platform.entry, 1), null, r -> log.warn("entry {} not reached: {}", platform.entry.key(), r));
        return true;
    }

    /**
     * A colour-suppression platform with a {@code foot}: true (and the walk started) while the bot's feet are on the
     * platform's levels, so the restock starts from the walkway at the bottom of the access path. Once per step context.
     */
    private boolean descendToFoot() {
        if (platform == null || !platform.isColorSuppression() || platform.foot == null || platform.glassY == null
            || platform.glassY.isEmpty() || platform.glassY.getFirst() == null) return false;
        if (!platform.materialRegions.isEmpty()) return false; // the platform's own chests are up here
        if (botFeet().y() < platform.glassY.getFirst() - 1 || ctx.attempt("descend-foot") > 0) return false;
        log.info("on color-suppression platform {}: to the foot of its access path first ({})", platform.id,
            platform.foot.key());
        run(bot.walkTo(platform.foot, 1), null, r -> log.warn("foot {} not reached: {}", platform.foot.key(), r));
        return true;
    }

    private void runLane(Lane planned, java.util.function.Supplier<List<Lane>> load) {
        runLane(planned, load, false);
    }

    /**
     * {@code repair}: a VERIFY repair lane, which gets the tile's art for the break-in to a walled-in cell
     * ({@code build.verifyBreakIn}).
     */
    private void runLane(Lane planned, java.util.function.Supplier<List<Lane>> load, boolean repair) {
        if (suppressionTile() && climbToPlatform()) return;
        // the zones of now, not of when the lanes were planned (a colour-suppression pass: lethal edges, openings)
        Lane lane = safeLanes(List.of(planned)).getFirst();
        if (lane != planned) log.info("lane {}: ends moved off the zones at run time: {} -> {}, {} -> {}", lane.index(),
            planned.from().key(), lane.from().key(), planned.to().key(), lane.to().key());
        TilePlan tp = plan;
        java.util.function.Function<Pos, @Nullable Placement> art = (config.build.dripstoneBreakThrough || repair && (config.build.verifyBreakIn || config.build.verifySealOut)) && tp != null
            ? tp::placement : null;
        if (repair && config.build.verifySealOut && art != null) {
            List<Lane> later = load.get();
            run(bot.placeRepairLane(lane, laneListener(lane, later), Set.copyOf(stuckCells), later, art));
            return;
        }
        if (!config.build.placeInReachGreedy) {
            run(art == null ? bot.placeLane(lane, laneListener(lane), Set.copyOf(stuckCells))
                : bot.placeLane(lane, laneListener(lane), Set.copyOf(stuckCells), null, art));
            return;
        }
        Lane open = unplaced(lane);
        List<Lane> later = load.get();
        run(art == null ? bot.placeLane(open, laneListener(open, later), Set.copyOf(stuckCells), later)
            : bot.placeLane(open, laneListener(open, later), Set.copyOf(stuckCells), later, art));
    }

    /**
     * The in-reach greedy's load: {@code lanes} each without the cells already placed ({@link #unplaced}) and, in BUILD
     * ({@code deferredOut}), without the cells left to VERIFY; empty lanes dropped.
     */
    private List<Lane> greedyLoad(List<Lane> lanes, boolean deferredOut) {
        List<Lane> out = new ArrayList<>(lanes.size());
        for (Lane l : lanes) {
            Lane b = deferredOut ? buildable(l) : l;
            if (b == null) continue;
            b = unplaced(b);
            if (!b.placements().isEmpty()) out.add(b);
        }
        return out;
    }

    /**
     * The cached lanes without the cells already placed in the loaded world, and without the lanes that leave empty
     * ({@code build.suppressionFreshLanes}): a platform with chunks out of view cannot be re-planned (the cache is the
     * pass's plan), and the in-reach greedy places the cells of the next lanes, so the cache named lanes done long ago:
     * restocks for them and rounds with "0 lanes run" (local CS run 2026-10-14, a minute each).
     */
    private List<Lane> stillOpen(List<Lane> cached) {
        List<Lane> out = new ArrayList<>(cached.size());
        for (Lane l : cached) {
            Lane u = unplaced(l);
            if (!u.placements().isEmpty()) out.add(u);
        }
        return out;
    }

    /** {@code lane} without the cells whose target block the world already shows. */
    private Lane unplaced(Lane lane) {
        WorldView w = bot.world();
        List<Placement> ps = new ArrayList<>(lane.placements().size());
        for (Placement p : lane.placements()) if (!catalog.matches(p.block(), w.block(p.pos()))) ps.add(p);
        return ps.size() == lane.placements().size() ? lane : new Lane(lane.index(), lane.from(), lane.to(), List.copyOf(ps));
    }

    /** Items the BUILD cells of {@code lanes} still need beyond the inventory. */
    private Set<String> lacking(List<Lane> lanes) {
        WorldView w = bot.world();
        Map<String, Integer> need = new TreeMap<>();
        for (Lane l : buildable(lanes))
            for (Placement p : l.placements()) if (w.block(p.pos()).isAir()) need.merge(p.itemId(), 1, Integer::sum);
        return new LinkedHashSet<>(minusInventory(need).keySet());
    }

    /** {@code lane} without the cells left to VERIFY ({@link #deferred}); null when none is left. */
    private Lane buildable(Lane lane) {
        if (deferred.isEmpty()) return lane;
        List<Placement> ps = new ArrayList<>(lane.placements().size());
        for (Placement p : lane.placements()) if (!deferred.contains(p.pos())) ps.add(p);
        if (ps.isEmpty()) return null;
        return ps.size() == lane.placements().size() ? lane : new Lane(lane.index(), lane.from(), lane.to(), List.copyOf(ps));
    }

    private List<Lane> buildable(List<Lane> lanes) {
        if (deferred.isEmpty()) return lanes;
        List<Lane> out = new ArrayList<>(lanes.size());
        for (Lane l : lanes) {
            Lane b = buildable(l);
            if (b != null) out.add(b);
        }
        return out;
    }

    private void stepVerify() {
        if (awaitPlatformLoaded()) return;
        Ctx c = ctx;
        WorldView w = bot.world();
        TilePlan.Survey s = plan.survey(w);
        tile.placed = s.correct();
        if (s.complete() && bandUnit()) {
            finishBand();
            return;
        }
        if (s.complete()) {
            repairPasses = 0;
            saveJob(job);
            log.info("{} verified: {} blocks", label(), plan.total);
            if (!builtNotified) {
                builtNotified = true;
                notifier.detail("Tile built", label() + ": " + plan.total + " blocks verified on " + platform.id + ".");
            }
            if (suppressionTile()) {
                // a pass of a colour-suppression map: no map of its own, and the platform is cleared by the ride; the
                // last pass goes on with the fill and the ride when the platform rides itself (ride.enabled)
                go(rideTile() ? Phase.FILL_MAP : Phase.FINISH_TILE);
                return;
            }
            go(Phase.FILL_MAP);
            return;
        }
        if (s.wrong().isEmpty() && !s.fluid() && !s.missing().isEmpty() && shortOnly(plan.remainingNeeds(w))) {
            // partial build: every cell left is of an item the inventory and the stock lack. STOCK_CHECK decides
            // whether what there is of them is worth a trip, or the tile waits; no repair pass is counted (nothing here
            // needs repairing) and no trip is made for a few blocks of an item that is nearly out
            log.info("{}: every block the stock covers is placed; {} cells are of items short in stock", label(),
                s.missing().size());
            // cells that only wait for stock never use up repair passes (build.verifyStockWaitFree): the passes so far
            // repaired everything else, and the cells to come after the wait get the full count
            if (config.build.verifyStockWaitFree && repairPasses > 0) {
                log.info("{}: repair passes back to 0 for the stock wait (were {})", label(), repairPasses);
                repairPasses = 0;
            }
            saveJob(job);
            go(Phase.STOCK_CHECK);
            return;
        }
        // sorterRefills: cells that only wait for the sorter to return their item use up no repair pass; RESTOCK waits
        // for it (or, after its no-rise limit, goes to far storage). Live 2026-10-14 00:58, remu [0,7]: the passes ran
        // out on 33 white carpet cells still in the sorter and the tile was BLOCKED.
        // Every item of the cells left must be one the sorter is still returning (short near even with the inventory):
        // a cell count does not work, the inventory's share of the item is not in the lag (live 01:21, remu [2,0]:
        // 6,933 cells left, all white carpet, counted as passes 4 and 5 and BLOCKED)
        Map<String, Integer> left = s.wrong().isEmpty() && !s.fluid() && !s.missing().isEmpty()
            ? plan.remainingNeeds(w) : Map.of();
        Map<String, Integer> lag = left.isEmpty() ? Map.of() : sorterLag(left);
        boolean lagOnly = !lag.isEmpty() && left.entrySet().stream().allMatch(e -> e.getValue() <= 0 || lag.containsKey(e.getKey()));
        // a pass that placed a good part of what was missing is progress, not a failed repair (a big stock gap is filled
        // over several restock rounds): only passes that get nowhere count towards maxRepairPasses
        boolean progress = !c.passCounted && config.build.verifyProgressFree && s.wrong().isEmpty()
            && lastVerifyMissing >= 0 && s.missing().size() <= lastVerifyMissing - Math.max(64, lastVerifyMissing / 10);
        if (!c.passCounted) lastVerifyMissing = s.missing().size();
        if (!c.passCounted && lagOnly) {
            c.passCounted = true;
            log.info("verify: the {} cells left wait for the sorter ({}); no repair pass counted (pass {}/{})",
                s.missing().size(), PartialBuild.items(lag), repairPasses, config.build.maxRepairPasses);
        } else if (progress) {
            c.passCounted = true;
            log.info("verify: {} missing, progress since the last pass; no repair pass counted (pass {}/{})",
                s.missing().size(), repairPasses, config.build.maxRepairPasses);
        }
        if (!c.passCounted) {
            c.passCounted = true;
            repairPasses++;
            int waiting = config.build.verifyStockWaitFree && s.wrong().isEmpty()
                ? Math.min(s.missing().size(), PartialBuild.cells(uncovered(plan.remainingNeeds(w)))) : 0;
            String waits = waiting > 0 ? " (" + waiting + " of them wait for stock)" : "";
            log.info("verify pass {}/{}: {} wrong, {} missing{}{}", repairPasses, config.build.maxRepairPasses,
                s.wrong().size(), s.missing().size(), waits, s.fluid() ? ", fluid present" : "");
            if (repairPasses > config.build.maxRepairPasses) {
                block("tile does not match after " + config.build.maxRepairPasses + " repair passes: "
                    + s.wrong().size() + " wrong, " + s.missing().size() + " missing" + waits
                    + sample(s.wrong(), s.missing()));
                return;
            }
            saveCheckpoint();
        }
        if (!s.wrong().isEmpty() && !c.wrongWait) {
            List<Pos> stuck = unbreakable(w, s.wrong());
            if (!stuck.isEmpty() && bandUnit()) {
                failBand("foreign_block_in_band", stuck.size() + " blocks inside the band cannot be broken (e.g. "
                    + w.block(stuck.getFirst()).id() + whyUnbreakable(w, stuck.getFirst()) + ")");
                return;
            }
            if (!stuck.isEmpty()) {
                block("cannot break " + stuck.size() + " blocks on the art layer, e.g. " + w.block(stuck.getFirst()) + " at "
                    + stuck.getFirst() + whyUnbreakable(w, stuck.getFirst()));
                return;
            }
            if (c.breakRounds++ < 2) {
                List<Pos> covers = coversOfWalledIn(w, s.wrong());
                List<Pos> targets = s.wrong();
                if (covers.isEmpty()) {
                    log.info("breaking {} wrong blocks", s.wrong().size());
                } else {
                    log.info("breaking {} wrong blocks, and first {} blocks of the level above that cover walled-in ones"
                        + " (its pass places them again)", s.wrong().size(), covers.size());
                    targets = new ArrayList<>(covers);
                    targets.addAll(s.wrong());
                }
                // a round that leaves some unbroken does not fail the step: after two such rounds the wrong blocks left
                // wait for the next pass and the missing cells go first (live 2026-10-12 17:32, ellen [0,2]: one
                // unreachable carpet spent passes 2-4 and the 77 missing cells were never placed)
                runBreak(targets, () -> c.breakRounds--, false,
                    r -> log.warn("[VERIFY] break round {} of 2: {}", c.breakRounds, r));
                return;
            }
            log.info("{} wrong blocks did not break: they wait for the next pass, the missing cells go first",
                s.wrong().size());
            c.wrongWait = true;
        }
        if (s.fluid() && bandUnit()) {
            failBand("fluid_in_band", "fluid on the art layer inside the band");
            return;
        }
        if (s.fluid()) {
            block("fluid on the art layer of platform " + platform.id);
            return;
        }
        Map<String, Integer> all = plan.remainingNeeds(w);
        // partial build: the cells the stock cannot cover wait (each short item's last ones); the rest is repaired
        Map<String, Integer> uncovered = uncovered(all);
        Map<String, Integer> need = PartialBuild.minus(all, uncovered);
        if (!holds(bot.inventory(), need)) {
            Map<String, Integer> want = minusInventory(need);
            deferred.clear(); // BUILD tries every missing cell again with the new load; this pass is counted
            go(stockShortfall(want).isEmpty() ? Phase.RESTOCK : Phase.STOCK_CHECK);
            return;
        }
        if (c.repairLanes == null) {
            List<Lane> lanes = safeLanes(planner.plan(plan.art, plan.geo, catalog, PlaceMode.laneHalfWidth(config.build, bot.airPlaceAvailable()), plan.doneIn(w)));
            c.repairLanes = new ArrayDeque<>(PartialBuild.withoutShort(lanes, uncovered));
            log.info("placing {} missing blocks in {} lanes{}", s.missing().size() - PartialBuild.cells(uncovered),
                c.repairLanes.size(), uncovered.isEmpty() ? ""
                    : "; " + PartialBuild.cells(uncovered) + " wait for " + PartialBuild.items(uncovered));
        }
        Lane lane = c.repairLanes.poll();
        if (lane != null) {
            lane = unplaced(lane); // the seal-out may already have refilled this later lane
            if (lane.placements().isEmpty()) return;
            runLane(lane, () -> greedyLoad(List.copyOf(c.repairLanes), false), true);
            return;
        }
        go(Phase.VERIFY);
    }

    // =====================================================================================
    // FILL_MAP / CHECK_MAP / LOCK / RENAME / STORE
    // =====================================================================================

    private void stepFillMap() {
        if (suppressionTile()) {
            stepCsFill();
            return;
        }
        if (fillAttempts >= config.map.maxFillAttempts) {
            block("the map did not match the art after " + fillAttempts + " attempts");
            return;
        }
        InventoryView inv = bot.inventory();
        MapArtConfig.StorageCfg st = config.site.storage;
        // right after a login the tile's chunks are not there yet: an empty survey is no "nothing above the art any
        // more" (local 10x: a reconnect in the cover wait counted a fill attempt that way), and the maps need them too
        if (awaitPlatformLoaded()) return;
        if (awaitTileMapKnown()) return;
        boolean again = tileUnlockedMap() != null; // the tile's map already carried is held again, never a second one
        if (!again && inv.count(MAP) < 1) {
            if (!withdrawOne(st.mapChests, MAP, "map")) block("no empty maps: the map chests are empty");
            return;
        }
        if (!again && inv.count(PANE) < 1) {
            if (!withdrawOne(st.paneChests, PANE, "pane")) block("no glass panes: the pane chests are empty");
            return;
        }
        if (awaitUncovered(again)) return;
        if (awaitEyeblossoms()) return;
        io.github.digitalisp.mapart.bot.MapHold hold = fillHold();
        String where = hold.nearStand() != null ? hold.nearStand() + " (near stand; station " + mapStandPos() + ")"
            : String.valueOf(mapStandPos());
        if (again) {
            int id = mapId;
            log.info("holding map {} again at {} (attempt {})", id, where, fillAttempts + 1);
            run(bot.holdMap(mapStandPos(), id, config.map.holdTicks, hold), () -> {
                fillAttempts++;
                go(Phase.CHECK_MAP);
            });
            return;
        }
        log.info("creating the map at {} (attempt {})", where, fillAttempts + 1);
        run(bot.createMap(mapStandPos(), config.map.holdTicks, this::madeMap, hold), () -> {
            fillAttempts++;
            go(Phase.CHECK_MAP);
        });
    }

    /** Ticks held before {@code map.holdUntilMatch} may end a fill: one full pass of vanilla's 16 column groups, +4. */
    static final int HOLD_MATCH_MIN_TICKS = 20;

    /**
     * FILL_MAP's create or hold options: with {@code map.fillNearStand} the covering
     * cell on the bot's way to the cartography table ({@link FillStand}), on the tile's first fill only (a later one,
     * after a mismatch, at the station as before) and only where the server's view distance, a chunk spare, reaches
     * every tile corner (the server draws a map pixel only from a loaded chunk); none on an eyeblossom tile, in a cover
     * wait or on a tile off the map grid, where the station stays. With {@code map.holdUntilMatch} the hold ends once
     * the map shows the art (CHECK_MAP's own test, {@link #mapShowsArt}).
     */
    private io.github.digitalisp.mapart.bot.MapHold fillHold() {
        Pos near = null;
        WorldView w = bot.world();
        int reach = (w.viewDistance() - 1) * 16;
        if (config.map.fillNearStand && fillAttempts == 0 && reach > 0 && plan.geo.gridAligned()
            && plan.blossomCount() == 0 && !coverWaiting()) {
            MapArtConfig.SiteCfg site = config.site;
            PlatformGeometry geo = plan.geo;
            near = FillStand.choose(geo, w.playerX(), w.playerZ(), site.cartographyTable, FillStand.MARGIN,
                c -> FillStand.farthestCorner(geo, c) <= reach
                    && SiteRules.noGoZoneAt(site, c) == null && SiteRules.noGoZoneAt(site, c.above()) == null);
        }
        java.util.function.IntPredicate shows = config.map.holdUntilMatch ? this::mapShowsArt : null;
        return new io.github.digitalisp.mapart.bot.MapHold(near, shows, HOLD_MATCH_MIN_TICKS);
    }

    /** Status note prefix while FILL_MAP waits for blocks above the art to go (the presence shows the note). */
    public static final String COVER_NOTE = "Waiting: platform above in use";

    // The cover wait is engine state, not per-phase (Ctx): the CHECK_MAP → FILL_MAP hand-over, a pause, a back-off or
    // eating during the wait neither posts the start line again nor restarts its clocks.
    /** Tile of the current or last cover wait ({@link #coverKey}); null = none. */
    private String coverWaitTile;
    /** Clock time the cover wait began, and of its last Discord line. */
    private long coverWaitSince;
    private long coverRemindedAt;
    /** Clock time nothing covered the art any more; -1 while the wait runs. */
    private long coverClearedAt = -1;

    /**
     * FILL_MAP gate, before the eyeblossoms (it takes precedence): a map pixel shows the topmost block with a map
     * colour, so blocks above the art layer (someone using the colour-suppression platform above the tile) hide the
     * art on the map, and no repair can change that ({@link Occlusion}). While anything covers the map, no fill attempt
     * is counted and nothing is repaired: the bot stands at the map centre holding the tile's unlocked map (created
     * first if there is none; a held map keeps updating) and surveys the chunk cache every 3 s. The wait posts one
     * Discord line when it starts, a reminder every {@code map.occludedReminderMinutes}, and goes BLOCKED after
     * {@code map.occludedMaxWaitMinutes} when that is set (0 = wait as long as it takes). Covered again within
     * {@code COVER_RESUME_MS} of clearing (someone still busy above), the same wait goes on: no second start line, same
     * clocks. Once nothing covers the art, a held map that already shows it goes to CHECK_MAP (and so to LOCK);
     * otherwise the fill carries on as normal (eyeblossoms, a counted hold, CHECK_MAP). Columns not loaded are walked
     * to once, then left unchecked (never guessed). True while the step must stop here.
     */
    private boolean awaitUncovered(boolean again) {
        Ctx c = ctx;
        Occlusion o = Occlusion.survey(plan.geo, catalog, bot.world());
        if (o.unloaded() > 0 && c.attempt("cover-walk") == 0) {
            run(bot.walkTo(mapStandPos(), CENTER_RANGE)); // look from the map centre; the fill goes there anyway
            return true;
        }
        long now = clock.millis();
        if (!o.any()) {
            if (o.unloaded() > 0)
                warnOnce("cover-unloaded", label() + ": " + Occlusion.count(o.unloaded(), "column") + " over the map not"
                    + " loaded at the center: blocks above the art not checked there");
            return coverCleared(again, now);
        }
        if (breakStrays(c, o)) return true;
        if (!coverWaiting()) {
            if (!resumeCoverWait(now, o)) {
                startCoverWait(now, o);
                notifier.info("Waiting for the platform above", label() + ": " + coverWaitText(o));
            }
        } else if (config.map.occludedReminderMinutes > 0
            && now - coverRemindedAt >= config.map.occludedReminderMinutes * 60_000L) {
            coverRemindedAt = now;
            log.info("{}: still covered after {}: {}", label(), formatDuration(now - coverWaitSince), o.describe());
            notifier.info("Still waiting for the platform above", label() + " (waiting " + formatDuration(now - coverWaitSince)
                + "): " + coverWaitText(o));
        }
        int maxMin = Math.max(0, config.map.occludedMaxWaitMinutes);
        if (maxMin > 0 && now - coverWaitSince >= maxMin * 60_000L) {
            coverWaitTile = null; // resume waits again, with a fresh start line
            block("the platform above is still in use after " + maxMin + " min: " + o.describe() + ". The map would "
                + "show those blocks, so it is not locked. Resume to wait again.");
            return true;
        }
        note = coverNote(o);
        c.blossomWaitSince = -1; // the eyeblossom wait (and its time limit) starts over once the art is uncovered
        if (!again) {
            log.info("{}: creating the map to hold while the art is covered (not a fill attempt)", label());
            run(bot.createMap(mapStandPos(), COVER_HOLD_TICKS, this::madeMap));
            return true;
        }
        int id = mapId;
        boolean inHand = bot.maps().heldMapId().filter(h -> h == id).isPresent();
        if (!inHand || distanceToMapStand() > CENTER_RANGE + 1) {
            run(bot.holdMap(mapStandPos(), id, COVER_HOLD_TICKS)); // back in the main hand (after eating, say)
            return true;
        }
        notePicture(id); // what the held map shows: it is found again by it after a reconnect, however covered
        sleep(COVER_POLL_TICKS);
        return true;
    }

    /**
     * VERIFY of a colour-suppression pass on the lower level of its layer: the blocks right above those of
     * {@code wrong} that no face of can be seen (a block on each side and one above; the glass below). Live
     * 2026-11-15: a block the stalled sweeper had pushed one cell west stood between four blocks of its own level
     * under a block of the upper level, and four repair passes ended BLOCKED on "cannot reach". The cover is broken
     * first; it is a cell of the upper level's main pass, which is still to come and places it again (or, were it
     * no block of that pass, would break it itself). Empty when that pass is stored or failed, or there is none.
     */
    private List<Pos> coversOfWalledIn(WorldView w, List<Pos> wrong) {
        if (!suppressionTile() || job.suppression == null || tile.x >= job.suppression.size()) return List.of();
        Job.SuppressionPass here = job.suppression.get(tile.x);
        boolean later = false;
        for (int i = 0; i < job.suppression.size(); i++) {
            Job.SuppressionPass up = job.suppression.get(i);
            Job.TileState ut = job.tile(new TileCoord(i, 0));
            if (i != tile.x && !up.noobline && up.layer == here.layer && up.level == here.level + 1 && ut != null
                && ut.status != TileStatus.STORED && ut.status != TileStatus.FAILED) later = true;
        }
        if (!later) return List.of();
        List<Pos> covers = new ArrayList<>();
        for (Pos p : wrong) {
            Pos up = p.above();
            if (w.block(up).isAir() || w.block(p.offset(1, 0, 0)).isAir() || w.block(p.offset(-1, 0, 0)).isAir()
                || w.block(p.offset(0, 0, 1)).isAir() || w.block(p.offset(0, 0, -1)).isAir()) continue;
            covers.add(up);
        }
        return covers;
    }

    /** Tries at breaking the strays right on the art layer before the cover wait takes over. */
    static final int STRAY_BREAK_TRIES = 3;

    /**
     * Blocks right on the art layer of a map tile ({@link Occlusion#onlyStrays()}: a lost click's carpet on a full
     * block, live 2026-10-25 after a kick to the queue) are no platform above in use: nobody else takes them away, and
     * the cover wait would wait forever. They are broken, up to {@link #STRAY_BREAK_TRIES} times; then the
     * cover wait takes over. Never for a colour-suppression pass (its y+1 level is art). True while a break runs.
     */
    private boolean breakStrays(Ctx c, Occlusion o) {
        if (!o.onlyStrays() || job.isSuppression()) return false;
        int tried = c.attempt("stray-break");
        if (tried >= STRAY_BREAK_TRIES) {
            if (tried == STRAY_BREAK_TRIES)
                log.warn("{}: {} right on the art layer still there after {} tries at breaking; waiting like for the"
                    + " platform above", label(), o.strayPixels(plan.geo), STRAY_BREAK_TRIES);
            return false;
        }
        List<Pos> strays = o.strays();
        // next to it first with the plain walker: the break run's pocket guard takes the target's height (art y + 1)
        // for the floor, and over carpets and full blocks every way there looks walled in (live 2026-10-25 13:51)
        Pos first = strays.getFirst();
        WorldView w = bot.world();
        double dx = w.playerX() - (first.x() + 0.5);
        double dz = w.playerZ() - (first.z() + 0.5);
        if (dx * dx + dz * dz > 2.25 && c.attempt("stray-walk") < STRAY_BREAK_TRIES) {
            c.attempts.merge("stray-break", -1, Integer::sum); // the walk is no try at breaking
            run(bot.walkTo(first, 1), null, r -> log.warn("{}: the walk to the stray on the art layer failed ({})",
                label(), r));
            return true;
        }
        log.warn("{}: {} covered by {} right on the art layer ({}): a stray, no platform above; breaking {} (try {} of"
            + " {})", label(), Occlusion.count(o.covered(), "pixel"), o.blocksText(), o.strayPixels(plan.geo),
            strays.size() == 1 ? "it" : "them", tried + 1, STRAY_BREAK_TRIES);
        runBreak(strays, () -> { }, false, r -> log.warn("{}: breaking the strays on the art layer failed ({}); looking"
            + " again", label(), r));
        return true;
    }

    /** Nothing covers the art: ends a cover wait. True when the held map already shows the art (CHECK_MAP next). */
    private boolean coverCleared(boolean again, long now) {
        if (!coverWaiting()) return false;
        coverClearedAt = now;
        if (note != null && note.startsWith(COVER_NOTE)) note = "";
        boolean shows = again && mapShowsArt(mapId);
        log.info("{}: nothing above the art any more after {} s; {}", label(), (now - coverWaitSince) / 1000,
            shows ? "the held map shows the art" : "filling the map");
        if (!shows) return false;
        go(Phase.CHECK_MAP);
        return true;
    }

    /** This tile's cover wait runs. */
    private boolean coverWaiting() {
        return coverKey().equals(coverWaitTile) && coverClearedAt < 0;
    }

    /** This tile's last cover wait cleared less than {@code COVER_RESUME_MS} ago: it goes on (true). */
    private boolean resumeCoverWait(long now, Occlusion o) {
        if (!coverKey().equals(coverWaitTile) || coverClearedAt < 0 || now - coverClearedAt >= COVER_RESUME_MS)
            return false;
        log.info("{}: covered again {} s after it cleared: the wait goes on ({}): {}", label(),
            (now - coverClearedAt) / 1000, formatDuration(now - coverWaitSince), o.describe());
        coverClearedAt = -1;
        return true;
    }

    private void startCoverWait(long now, Occlusion o) {
        coverWaitTile = coverKey();
        coverWaitSince = now;
        coverRemindedAt = now;
        coverClearedAt = -1;
        int maxMin = Math.max(0, config.map.occludedMaxWaitMinutes);
        log.warn("{}: {}; waiting at the map center{}", label(), o.describe(),
            maxMin > 0 ? " up to " + maxMin + " min" : " until they are gone");
    }

    private String coverKey() {
        return job.id + " " + tile.x + "," + tile.z + " " + platform.id;
    }

    /** "Waiting for the platform above: 3,200 pixels covered by blocks at y201 (e.g. white_concrete). The map is ..." */
    private String coverWaitText(Occlusion o) {
        int maxMin = Math.max(0, config.map.occludedMaxWaitMinutes);
        return "Waiting for the platform above: " + o.describe() + ". The map is held and will be locked only when it"
            + " matches" + (maxMin > 0 ? "; BLOCKED if still covered after " + maxMin + " min." : ".");
    }

    /** "Waiting: platform above in use (3,200 pixels covered)". */
    private static String coverNote(Occlusion o) {
        return COVER_NOTE + (o.covered() > 0 ? " (" + Occlusion.count(o.covered(), "pixel") + " covered)"
            : " (the row north of the map covered)");
    }

    /**
     * Share of this tile's art pixels map {@code id} shows (0..1): the uncovered part of the art for a map held through a
     * cover wait, next to none for a map of another place or a blank one. -1 = unknown: no data for the map, or the
     * tile's expected picture cannot be worked out (chunks not loaded).
     */
    private double artShare(int id) {
        if (!expectedReady()) return -1;
        Optional<byte[]> data = bot.maps().colors(id);
        if (data.isEmpty()) return -1;
        byte[] px = data.get();
        boolean[] check = expected.check();
        byte[] want = expected.packed();
        int n = 0;
        int ok = 0;
        for (int i = 0; i < check.length && i < px.length; i++) {
            if (!check[i]) continue;
            n++;
            if (px[i] == want[i]) ok++;
        }
        return n == 0 ? -1 : (double) ok / n;
    }

    /** The map is this tile's by its pixels: at least {@link #TILE_MAP_MIN_SHARE} of the art shows on it. */
    private boolean showsTileArt(int id) {
        return artShare(id) >= TILE_MAP_MIN_SHARE;
    }

    /** Pictures of the tile's area kept ({@link #tilePictures}). */
    static final int TILE_PICTURES = 3;
    /** Least share of a kept picture's pixels a map must show the same to count as a map of the tile's area. */
    static final double TILE_PICTURE_MIN_SHARE = 0.5;

    /** Keeps what map {@code id} of this tile shows now as one of the {@link #tilePictures} (when it changed). */
    private void notePicture(int id) {
        Optional<byte[]> data = bot.maps().colors(id);
        if (data.isEmpty()) return;
        byte[] px = data.get();
        if (!tilePictures.isEmpty() && Arrays.equals(tilePictures.getLast(), px)) return;
        tilePictures.add(px.clone());
        while (tilePictures.size() > TILE_PICTURES) tilePictures.removeFirst();
    }

    /**
     * Best share (0..1) of a kept picture of the tile's area ({@link #tilePictures}) map {@code id} shows the same,
     * counted over the picture's coloured pixels; -1 = no data or no picture kept.
     */
    private double pictureShare(int id) {
        if (tilePictures.isEmpty()) return -1;
        Optional<byte[]> data = bot.maps().colors(id);
        if (data.isEmpty()) return -1;
        byte[] px = data.get();
        double best = -1;
        for (byte[] ref : tilePictures) {
            int n = 0;
            int same = 0;
            for (int i = 0; i < ref.length && i < px.length; i++) {
                if (ref[i] == 0) continue;
                n++;
                if (px[i] == ref[i]) same++;
            }
            if (n > 0) best = Math.max(best, (double) same / n);
        }
        return best;
    }

    /** The map shows the tile's area as one of its kept pictures does ({@link #TILE_PICTURE_MIN_SHARE}). */
    private boolean showsTilePicture(int id) {
        return pictureShare(id) >= TILE_PICTURE_MIN_SHARE;
    }

    /**
     * Least share of the tile's art pixels ({@link #artShare}) for an unlocked map to count as this tile's after its id
     * changed: live, a cover wait hid 40 % of the art; a map of another place or a blank map shows next to none of it.
     */
    static final double TILE_MAP_MIN_SHARE = 0.25;
    /** FILL_MAP waits this many seconds at most for the data of carried maps before it decides to make a map. */
    private static final int MAP_FIND_WAITS = 5;

    /**
     * FILL_MAP: the tile's unlocked map the bot already carries, held again instead of a new one being made (live 2b2t,
     * 2026-10-11: a cover wait made one more map after each of two reconnects and after a restart, and three stale maps
     * stayed in the inventory). In this connection: the map made or found ({@link #mapId}, {@link #ownMaps}). After a
     * reconnect or restart (new map ids, own-map memory empty), and only while the tile counts a map the bot made and has
     * not locked ({@code TileState.unlockedMaps}, persisted; without one, a map showing the art is the owner's): by its pixels,
     * the unlocked map that shows the picture the held map showed before (a reconnect: {@link #tilePictures}, however
     * much a cover hides), else the one that shows the most of the art, at least {@link #TILE_MAP_MIN_SHARE}
     * ({@link #artShare}: a restart); else the unlocked map in the main hand (a restart under a cover over most of the
     * art; the server gives the bot back its selected slot). One of the owner's maps of another place matches none of the
     * pixel tests. The map found is remembered as the bot's for the connection. Null = none: FILL_MAP makes one.
     */
    private Integer tileUnlockedMap() {
        List<Integer> ids = bot.inventory().filledMapIds();
        MapView maps = bot.maps();
        if (mapId != null && ids.contains(mapId) && !maps.isLocked(mapId)) return mapId;
        mapId = null;
        List<Integer> unlocked = new ArrayList<>();
        for (Integer id : ids) if (!maps.isLocked(id)) unlocked.add(id);
        if (unlocked.isEmpty()) return null;
        Integer held = maps.heldMapId().filter(unlocked::contains).orElse(null);
        if (held != null && ownMaps.contains(held)) return mapId = held;
        for (int i = unlocked.size() - 1; i >= 0; i--) if (ownMaps.contains(unlocked.get(i))) return mapId = unlocked.get(i);
        // without a map of its own out for this tile, an unlocked map showing the art is the owner's: never taken
        if (tile.unlockedMaps <= 0) return null;
        Integer found = bestShare(unlocked, held, this::pictureShare, TILE_PICTURE_MIN_SHARE);
        String how = found == null ? null : Math.round(pictureShare(found) * 100) + " % the same as the map held before";
        if (found == null) {
            found = bestShare(unlocked, held, this::artShare, TILE_MAP_MIN_SHARE);
            if (found != null) how = Math.round(artShare(found) * 100) + " % of the art";
        }
        if (found == null && held != null) {
            found = held;
            how = "the main hand: the tile has a map made and not locked; " + Math.max(0, Math.round(artShare(held) * 100))
                + " % of the art shows";
        }
        if (found == null) {
            warnOnce("map-gone", label() + ": the map made for this tile is not found among " + unlocked.size()
                + " unlocked map" + (unlocked.size() == 1 ? "" : "s") + " (taken out?); making a new one");
            return null;
        }
        log.info("{}: unlocked map {} found again ({}); holding it, no new map", label(), found, how);
        ownMaps.add(found);
        return mapId = found;
    }

    /** Of {@code ids}, the one with the highest {@code share} of at least {@code min} (a tie: the held one); or null. */
    private static Integer bestShare(List<Integer> ids, Integer held, java.util.function.IntToDoubleFunction share,
                                     double min) {
        Integer best = null;
        double most = -1;
        for (int i = ids.size() - 1; i >= 0; i--) {
            int id = ids.get(i);
            double s = share.applyAsDouble(id);
            if (s < min) continue;
            if (s > most || (s == most && held != null && id == held)) {
                best = id;
                most = s;
            }
        }
        return best;
    }

    /**
     * FILL_MAP, before {@link #tileUnlockedMap} decides: while the tile counts a map the bot made and has not locked,
     * unlocked maps not known as the bot's (after a reconnect or a restart) are told apart by their pixels. That needs
     * the tile's expected picture (its chunks: FILL_MAP waits for them first) and the maps' data (waits up to
     * {@link #MAP_FIND_WAITS} s for it after a login). True while the step must stop here.
     */
    private boolean awaitTileMapKnown() {
        if (tile.unlockedMaps <= 0) return false;
        List<Integer> ids = bot.inventory().filledMapIds();
        MapView maps = bot.maps();
        if (mapId != null && ids.contains(mapId) && !maps.isLocked(mapId)) return false;
        List<Integer> unknown = new ArrayList<>();
        for (Integer id : ids) {
            if (maps.isLocked(id)) continue;
            if (ownMaps.contains(id)) return false;
            unknown.add(id);
        }
        if (unknown.isEmpty()) return false;
        Ctx c = ctx;
        for (int id : unknown) {
            if (maps.colors(id).isPresent()) continue;
            if (c.attempt("map-find-data") >= MAP_FIND_WAITS) return false;
            sleep(20);
            return true;
        }
        return false;
    }

    /** The map {@code id} (unlocked, or locked by the bot) shows exactly the expected colours. */
    private boolean mapShowsArt(int id) {
        Optional<byte[]> data = bot.maps().colors(id);
        if (data.isEmpty()) return false;
        if (!expectedReady()) return false;
        return expected.mismatches(data.get()) == 0;
    }

    /** Status note prefix while FILL_MAP waits for the world to flip the tile's eyeblossoms (the presence shows it). */
    public static final String EYEBLOSSOM_NOTE = "waiting for eyeblossoms to ";

    /**
     * FILL_MAP gate: eyeblossoms show a different map colour open (night) and closed (day), and the server flips them
     * all with the time of day. The map is filled only while every eyeblossom pixel shows the state the art wants
     * (the target's state; its colour is the expected one). Until then the bot stands at the map centre, so the tile's
     * chunks keep ticking, and polls the chunk cache every few seconds, at most {@code map.eyeblossomWaitMinutes};
     * then it goes BLOCKED with an alert. A tile wanting both states can never match: BLOCKED at once (uploads with
     * both are rejected; this catches jobs queued before that check). True while the step must stop here.
     */
    private boolean awaitEyeblossoms() {
        if (plan.blossomCount() == 0) return false;
        Ctx c = ctx;
        if (plan.blossomsWantOpen && plan.blossomsWantClosed) {
            block("this map wants open and closed eyeblossoms, which flip together with the time of day, so the map "
                + "can never show both colors. Cancel the job and upload it with one kind per map.");
            return true;
        }
        boolean wantOpen = plan.blossomsWantOpen;
        String waitNote = EYEBLOSSOM_NOTE + Eyeblossoms.change(wantOpen);
        WorldView w = bot.world();
        if ((!plan.allLoaded(w) || distanceToMapStand() > CENTER_RANGE + 1) && c.attempt("blossom-walk") < 2) {
            run(bot.walkTo(mapStandPos(), CENTER_RANGE));
            return true;
        }
        if (!plan.allLoaded(w)) {
            stepFailed("platform " + platform.id + " chunks not loaded for the eyeblossom check");
            return true;
        }
        int off = plan.blossomsNotFlipped(w);
        long now = clock.millis();
        if (off == 0) {
            if (c.blossomWaitSince >= 0)
                log.info("eyeblossoms are {} after {} s; filling the map", wantOpen ? "open" : "closed",
                    (now - c.blossomWaitSince) / 1000);
            if (note != null && note.startsWith(EYEBLOSSOM_NOTE)) note = "";
            return false;
        }
        int limitMin = Math.max(0, config.map.eyeblossomWaitMinutes);
        if (c.blossomWaitSince < 0) {
            c.blossomWaitSince = now;
            note = waitNote;
            log.info("{}: {} of {} eyeblossoms are {}; waiting up to {} min at the map center", label(), off,
                plan.blossomCount(), wantOpen ? "closed" : "open", limitMin);
            notifier.info("Waiting for eyeblossoms", label() + ": " + off + " of " + plan.blossomCount()
                + " eyeblossoms are " + (wantOpen ? "closed" : "open") + ". The art wants them "
                + (wantOpen ? "open (night)" : "closed (day)") + ", so the map is filled once they "
                + Eyeblossoms.change(wantOpen) + "; waiting up to " + limitMin + " min.");
        }
        if (now - c.blossomWaitSince >= limitMin * 60_000L) {
            block("eyeblossoms did not " + Eyeblossoms.change(wantOpen) + " within " + limitMin + " min: " + off
                + " of " + plan.blossomCount() + " still " + (wantOpen ? "closed" : "open")
                + ", so the map would show the wrong color. Resume to wait again.");
            return true;
        }
        note = waitNote;
        sleep(BLOSSOM_POLL_TICKS);
        return true;
    }

    /**
     * Status note prefix while CHECK_MAP holds a map whose data has not arrived (the Discord presence shows idle: the bot
     * only waits for the server).
     */
    public static final String MAP_DATA_NOTE = "waiting for map data";

    private void stepCheckMap() {
        if (suppressionTile()) {
            stepCsCheckMap();
            return;
        }
        Integer id = currentUnlockedMap();
        if (id == null) {
            if (currentLockedMap() != null) go(Phase.LOCK);
            else go(Phase.FILL_MAP);
            return;
        }
        Optional<byte[]> data = bot.maps().colors(id);
        if (data.isEmpty()) {
            if (ctx.attempt("hold") < MISSING_DATA_HOLDS) {
                note = MAP_DATA_NOTE + " for map " + id;
                run(bot.holdMap(mapStandPos(), id, config.map.holdTicks));
                return;
            }
            note = "no map data for map " + id + ": making a fresh map";
            missingMapData(id);
            return;
        }
        if (note != null && note.startsWith(MAP_DATA_NOTE)) note = "";
        if (expected == null) {
            if (!plan.allLoaded(bot.world())) {
                if (ctx.attempt("map-walk") == 0) {
                    run(bot.walkTo(mapStandPos(), CENTER_RANGE));
                    return;
                }
                stepFailed("platform chunks not loaded for the map check");
                return;
            }
            expected = plan.expectedMap(catalog, bot.maps(), bot.world());
        }
        byte[] actual = data.get().clone();
        int bad = expected.mismatches(actual);
        if (bad == 0) {
            log.info("map {} matches the art", id);
            go(Phase.LOCK);
            return;
        }
        // eyeblossom pixels expect the 1.21.4 colour of the wanted state; they differ if the flowers flipped during the
        // fill, or on a server whose colours differ (the 1.21.11 test server shows closed ones as 6, not 49)
        int blossomBad = plan.blossomMismatches(expected, actual);
        String onBlossoms = blossomBad > 0 ? " (" + blossomBad + " on eyeblossom pixels)" : "";
        TilePlan.ExpectedMap exp = expected;
        String diffName = "mapdiff-" + job.id + "-" + tile.x + "-" + tile.z + ".png";
        // blocks above the art (the platform above in use) hide it on the map: repairing the art layer cannot help, so
        // this fill is not counted and FILL_MAP waits for them to go, holding the map (awaitUncovered). The mismatch
        // post is the wait's start line; covered again soon after a wait cleared, that wait goes on without a post.
        Occlusion o = Occlusion.survey(plan.geo, catalog, bot.world());
        if (o.any()) {
            fillAttempts = Math.max(0, fillAttempts - 1);
            long now = clock.millis();
            boolean goesOn = coverWaiting() || resumeCoverWait(now, o);
            if (!goesOn) startCoverWait(now, o);
            log.warn("map {} has {} mismatching pixels{}: covered from above, not repairing", id, bad, onBlossoms);
            if (!goesOn) renderAsync(() -> renderer.mapDiff(actual, exp.forDiff(actual), colors, RENDER_SCALE),
                "Map mismatch: " + job.name, label() + ": " + bad + " pixels differ" + onBlossoms
                    + " (not counted as a fill attempt). " + coverWaitText(o), diffName);
            go(Phase.FILL_MAP);
            return;
        }
        String unchecked = o.unloaded() > 0
            ? "; blocks above " + Occlusion.count(o.unloaded(), "column") + " not checked (not loaded)" : "";
        log.info("map {} mismatches (x,z on the tile): {}", id, exp.sample(actual, 12));
        log.warn("map {} has {} mismatching pixels{} (attempt {}/{}){}", id, bad, onBlossoms, fillAttempts,
            config.map.maxFillAttempts, unchecked);
        renderAsync(() -> renderer.mapDiff(actual, exp.forDiff(actual), colors, RENDER_SCALE),
            "Map mismatch: " + job.name, label() + ": " + bad + " pixels differ" + onBlossoms + " (attempt " + fillAttempts
                + "/" + config.map.maxFillAttempts + ")" + unchecked + "; repairing.", diffName);
        if (fillAttempts >= config.map.maxFillAttempts) {
            block("map shows " + bad + " wrong pixels" + onBlossoms + " after " + fillAttempts + " attempts");
            return;
        }
        go(Phase.VERIFY);
    }

    /**
     * CHECK_MAP held map {@code id} {@link EngineTuning#MISSING_DATA_HOLDS} times and still has no pixels for it (2b2t,
     * 2026-10-11: ZenithProxy's full map cache kept none of map 990's 122 packets). Holding longer cannot help: vanilla
     * 1.21.4 sends a holder the whole map once, in the first packet to its {@code MapItemSavedData.HoldingPlayer}, and
     * after that only changed pixels. That holder lives as long as the player's connection: a slot move, a drop and
     * re-pick or a trip through a chest keep it (it is only dropped when another holder's tick finds the bot without
     * the map). Only a new map id (a new map, or the locked copy) or a new connection gets the whole picture again.
     * So: one fresh map per tile, made at the centre like a fill (not counted as a fill attempt; the old map stays in the
     * inventory); if that brings no data either, BLOCKED asking for a reconnect. The plugin never reconnects itself.
     */
    private void missingMapData(int id) {
        if (freshMaps >= MAX_FRESH_MAPS) {
            blockNoMapData(id, freshMaps > 0 ? " (a fresh map: the one before got none either)" : "");
            return;
        }
        if (bot.inventory().count(MAP) < 1) {
            if (!withdrawOne(config.site.storage.mapChests, MAP, "map"))
                blockNoMapData(id, " (no empty map for a fresh one: the map chests are empty)");
            return;
        }
        freshMaps++;
        log.warn("{}: no map data received for map {} after holding it {} times; making a fresh map (a new map id gets"
            + " all its pixels)", label(), id, MISSING_DATA_HOLDS);
        notifier.info("No map data", label() + ": the server's data for map " + id + " never arrived (the map is kept)."
            + " Making a fresh map at the center.");
        run(bot.createMap(mapStandPos(), config.map.holdTicks, this::madeMap), () -> go(Phase.CHECK_MAP));
    }

    /** The last resort of {@link #missingMapData}: a human reconnects (map ids and data come anew), then resumes. */
    private void blockNoMapData(int id, String detail) {
        block("no map data received for map " + id + detail + ". The server sends a map's pixels whole only once per"
            + " connection, and neither the plugin nor ZenithProxy got them. Reconnect the bot (ZenithProxy `disconnect`,"
            + " then `connect`): its maps get new ids and all their data again");
    }

    private void stepLock() {
        Integer id = currentUnlockedMap();
        if (id == null) {
            go(currentLockedMap() != null ? afterLock() : Phase.FILL_MAP);
            return;
        }
        if (!mapShowsArt(id)) {
            // only a map that shows this tile's art is locked (after a restart into LOCK the unlocked map found may be
            // one the bot did not make): CHECK_MAP looks at it first
            go(Phase.CHECK_MAP);
            return;
        }
        if (bot.inventory().count(PANE) < 1) {
            if (!withdrawOne(config.site.storage.paneChests, PANE, "pane")) block("no glass panes: the pane chests are empty");
            return;
        }
        Pos table = rideTile() ? ride().cartographyTable : config.site.cartographyTable;
        if (table == null) {
            block("site.cartographyTable is not configured");
            return;
        }
        run(bot.lockMap(table, id, lid -> ownMaps.add(lockedMapId = lid)), () -> {
            mapId = null;
            tile.unlockedMaps = Math.max(0, tile.unlockedMaps - 1);
            saveJob(job);
            log.info("map locked ({})", lockedMapId);
            notifier.detail("Map locked", label());
            go(afterLock());
        });
    }

    private Phase afterLock() {
        return config.map.rename ? Phase.RENAME : Phase.STORE;
    }

    private void stepRename() {
        Ctx c = ctx;
        Integer id = currentLockedMap();
        if (id == null) {
            go(Phase.LOCK);
            return;
        }
        InventoryView inv = bot.inventory();
        String wanted = mapName();
        if (inv.mapName(id).filter(wanted::equals).isPresent()) {
            // e.g. renamed before a restart: no XP, no anvil use
            tile.mapName = wanted;
            saveJob(job);
            log.info("map {} is already named \"{}\"", id, wanted);
            go(Phase.STORE);
            return;
        }
        MapArtConfig.StorageCfg st = config.site.storage;
        int perTrip = Math.max(1, config.map.xpBottlesPerTrip);
        // worn boots wear on every platform (Soul Speed on the soul soil walkway): mend them wherever they are worn
        boolean mending = (platform.soulSpeedBoots || config.build.wearBoots) && isBoots(inv.bootsItem())
            && inv.bootsDurability() < (rideTile() && config.build.suppressionMendBeforeRide
                ? config.build.bootsMendBelowPercent / 100.0 : BOOTS_MIN_DURABILITY)
            && c.bottlesThrown < MAX_MEND_TRIPS * perTrip;
        if (inv.xpLevel() < 1 || mending) {
            if (inv.xpLevel() < 1 && c.bottlesThrown >= (MAX_MEND_TRIPS + 2) * perTrip) {
                block("thrown " + c.bottlesThrown + " XP bottles but the level is still 0");
                return;
            }
            int bottles = inv.count(XP_BOTTLE);
            if (bottles > 0) {
                int n = Math.min(bottles, XP_THROW_CHUNK);
                c.bottlesThrown += n;
                run(bot.throwXp(n));
                return;
            }
            int a = c.attempt("xp");
            if (a < st.xpChests.size()) {
                if (a == 0) c.xpFetch.clear();
                Pos chest = st.xpChests.get(a);
                run(bot.withdraw(chest, Map.of(XP_BOTTLE, perTrip), r -> {
                    if (sum(r.moved()) > 0) {
                        c.resetAttempt("xp");
                        c.xpFetch.clear();
                    } else {
                        c.xpFetch.none(chest, r);
                    }
                }), null, r -> {
                    log.warn("XP withdraw failed: {}", r);
                    c.xpFetch.failed(chest, r);
                });
                return;
            }
            if (inv.xpLevel() < 1) {
                block("no XP for renaming: " + c.xpFetch.cause(st.xpChests));
                return;
            }
            warnOnce("mend", "boots not fully mended (" + Math.round(inv.bootsDurability() * 100) + "%): "
                + c.xpFetch.cause(st.xpChests));
        }
        if (mending) warnOnce("mend-limit", "boots still below 95% after " + c.bottlesThrown + " bottles; continuing");
        Pos anvil = rideTile() && ride().anvil != null ? ride().anvil : config.site.anvil;
        if (anvil == null) {
            block("site.anvil is not configured (map.rename is on)");
            return;
        }
        WorldView w = bot.world();
        if (!w.isLoaded(anvil)) {
            if (c.attempt("anvil-walk") == 0) {
                run(bot.walkTo(anvil, 3));
                return;
            }
            stepFailed("anvil chunk at " + anvil + " not loaded");
            return;
        }
        if (!ANVILS.contains(w.block(anvil).id())) {
            if (inv.count(ANVIL) < 1) {
                if (!withdrawOne(st.anvilChests, ANVIL, "anvil")) block("the anvil at " + anvil + " is gone and the anvil chests are empty");
                return;
            }
            if (c.attempt("anvil-place") < 2) {
                log.info("placing a replacement anvil at {}", anvil);
                run(bot.placeBlock(anvil, ANVIL), () -> sleep(5));
                return;
            }
            stepFailed("could not place a replacement anvil at " + anvil);
            return;
        }
        String name = mapName();
        run(bot.renameMap(anvil, id, name), () -> {
            tile.mapName = name;
            saveJob(job);
            log.info("map renamed to \"{}\"", name);
            go(Phase.STORE);
        });
    }

    /**
     * {@code ride.reuseCart}, at the start of STORE (after the lock and the name, the bot still in the booth): the cart
     * taken off the brake goes back on the start rail, placed from the start stand without getting in. Once per map
     * ({@code tile.cartReturnDue} / {@code tile.cartReturned}, saved: a restart neither skips it nor places a second
     * cart). A cart already on the rail: nothing is placed, the carried one is kept. Any failure is an alert and the
     * map is stored all the same; the minecart stays in the inventory (it is never deposited or thrown away:
     * {@link #keepAlways}) and the next ride puts it on the rail when it boards. True while acting.
     */
    private boolean returnCart() {
        if (!rideTile() || !tile.cartReturnDue || tile.cartReturned || tile.status == TileStatus.STORED) return false;
        MapArtConfig.RideCfg r = ride();
        if (!r.reuseCart || bot.inventory().count(CART) < 1) {
            // not picked up (alerted at RIDE_END), or the setting was switched off in between
            if (r.reuseCart) log.warn("{}: no minecart to put back on the start rail {}", label(), r.startRail.key());
            tile.cartReturned = true;
            saveJob(job);
            return false;
        }
        log.info("putting the cart back on the start rail {} from {}", r.startRail.key(), r.startStand.key());
        boolean[] put = new boolean[1];
        run(bot.placeCart(r.startRail, r.startStand, p -> put[0] = p), () -> {
            tile.cartReturned = true;
            saveJob(job);
            if (put[0]) log.info("the cart is back on the start rail {}", r.startRail.key());
            else {
                log.warn("{}: a cart stands on the start rail {} already: none placed, the carried one is kept", label(),
                    r.startRail.key());
                warnOnce("cart-there-" + job.id, label() + ": a cart stood on the start rail already; the carried"
                    + " minecart stays in the inventory");
            }
        }, reason -> {
            tile.cartReturned = true; // given up: the map comes first
            saveJob(job);
            log.warn("{}: the cart is not back on the start rail {}: {}", label(), r.startRail.key(), reason);
            alertHere("Cart not back on the start rail", "The cart is not back on the start rail: " + reason
                + ". The minecart stays in the inventory; the map is stored as usual. The next ride puts the cart on the"
                + " rail itself if none stands there.");
        });
        return true;
    }

    private void stepStore() {
        Ctx c = ctx;
        if (returnCart()) return;
        if (tile.status == TileStatus.STORED && rideTile()) {
            go(afterStore()); // the count, then the reset check (the bot walks back up to the muncher for it)
            return;
        }
        if (tile.status == TileStatus.STORED) {
            // the map is in the output chest and the bot stands by the chests: the working items the next tile does
            // not need go back now (no extra walk), then COUNT or NUKE. A restart here goes on with COUNT or NUKE.
            if (tidyWorkingItems(c, true)) return;
            if (fetchNukeXp(c)) return; // the next NUKE's XP, while the bot stands by the XP chest
            // build.mapPaneAtStore: the next tile's map and pane, so its last load makes no trip to their chests
            if (MapPaneAtStore.fetch(config.build.mapPaneAtStore, bot.inventory(), config.site.storage, c::attempt,
                (chest, item) -> {
                    log.info("{}: taking 1 {} at {} for the next tile (build.mapPaneAtStore)", label(), item, chest);
                    run(bot.withdraw(chest, null, Map.of(item, 1), mapPaneMode(), r -> { }), null,
                        r -> log.warn("{} withdraw for the next tile failed: {}", item, r));
                })) return;
            go(afterStore());
            return;
        }
        Integer id = currentLockedMap();
        if (id == null && c.storeLastChest != null) {
            // the last deposit's read-back came before the server's answer (the cache still showed the map, the menu
            // was closed); the map has left the inventory since: it went into that chest (10x under
            // load: BLOCKED "took no map" with the map in the chest)
            log.info("{}: the map left the inventory after the deposit's menu closed: stored in the output chest tried last",
                label());
            storedIn(c, c.storeLastId, c.storeLastChest);
            return;
        }
        if (id == null) {
            block("the locked map for " + label() + " is not in the inventory");
            return;
        }
        if (!c.rendered) {
            c.rendered = true;
            c.finalPixels = bot.maps().colors(id).map(byte[]::clone).orElse(null); // rendered with "Map done"
        }
        List<Pos> out = rideTile() && !ride().outputChests.isEmpty() ? ride().outputChests : config.site.storage.outputChests;
        if (out.isEmpty()) {
            block("no output chest configured");
            return;
        }
        int a = c.attempt("output");
        if (a >= out.size()) {
            // "full" only when every output chest was seen full; otherwise what went wrong where
            block(c.storeFull >= c.storeProblems.size() ? "the output chests are full"
                : "could not store the map: " + String.join("; ", c.storeProblems));
            return;
        }
        Pos chest = out.get(a);
        run(bot.depositMap(chest, id, r -> {
            c.storedMoved = r.moved().getOrDefault(FILLED_MAP, 0);
            c.storeNote = r.problem();
            c.storeSeenFull = r.after() != null && r.after().freeSlots() <= 0;
        }), () -> {
            if (c.storedMoved <= 0) {
                if (!c.storeSeenFull) {
                    c.storeLastChest = chest;
                    c.storeLastId = id;
                    if (!c.storeRechecked) {
                        // the inventory may not show the server's answer yet: look again in a moment, then try the
                        // same chest once more if the map is still there
                        c.storeRechecked = true;
                        c.giveBack("output");
                        log.info("{}: the map still shows in the inventory after the deposit; looking again", label());
                        sleep(STORE_RECHECK_TICKS);
                        return;
                    }
                }
                String why = c.storeSeenFull ? "full" : "took no map" + (c.storeNote != null ? " (" + c.storeNote + ")" : "");
                if (c.storeSeenFull) c.storeFull++;
                c.storeProblems.add("output chest " + chest + " " + why);
                log.warn("output chest {} {}", chest, why);
                return; // next step tries the next output chest
            }
            storedIn(c, id, chest);
            // the next step tidies (tidyWorkingItems), then goes on with COUNT or NUKE
        }, r -> {
            log.warn("deposit into output chest {} failed: {}", chest, r);
            c.storeProblems.add("output chest " + chest + " failed: " + r);
        });
    }

    /** Ticks STORE waits before it looks again at a map a deposit seemed to leave in the inventory. */
    static final int STORE_RECHECK_TICKS = 20;

    /** The locked map {@code id} is in output chest {@code chest}: the tile is stored. */
    private void storedIn(Ctx c, int id, Pos chest) {
        tile.status = TileStatus.STORED;
        tile.finishedAt = clock.millis();
        tile.placed = tile.total;
        lockedMapId = null;
        saveJob(job);
        log.info("{} stored in {} as map #{} of this connection", suppressionTile() ? "#" + job.id + " " + job.name
            + " (color suppression)" : label(), chest, id);
        mapDone(id, chest, c.finalPixels);
    }

    /**
     * The one post per stored map, whatever {@code discord.verbose} says: name, job, tile, maps stored of the job and
     * how long the map took (selected → stored; a lane tile from its first band), with the final render when the
     * map's pixels were read. Neither the job id nor the map id is posted (the map id is only this connection's); the
     * "stored in" log line has both. {@code id} is the stored item's {@code map_id} component in this connection, which
     * the done result carries.
     */
    private void mapDone(int id, Pos chest, byte[] px) {
        storedMapId = id;
        countForTile(null);
        log.info("tile summary {}: {}", label(), tileSummary());
        int stored = 0;
        for (Job.TileState t : job.tiles) if (t.status == TileStatus.STORED) stored++;
        int n = job.tiles.size();
        if (suppressionTile()) {
            stored = 1; // a colour-suppression job is one map; its tiles are build passes
            n = 1;
        }
        // a lane tile's time runs from its first band to the store; unknown, the tail's own time says so
        String took = tailUnit() && !tileStartKnown ? ", its tail took " : ", this one took ";
        forgetTileStart();
        String title = "Map done: " + (tile.mapName != null ? tile.mapName : mapName());
        // a colour-suppression map is the job's one map: no pass coordinate in its line
        String body = job.name + (suppressionTile() ? "" : ", tile " + tile.x + "," + tile.z) + " on " + platform.id + "\n" + stored + " of " + n + (n == 1 ? " map" : " maps") + " done"
            + (tile.startedAt > 0 && tile.finishedAt >= tile.startedAt
                ? took + formatDuration(tile.finishedAt - tile.startedAt) : "")
            + "\nOutput chest " + chest.key();
        if (px == null) notifier.info(title, body);
        else renderAsync(() -> renderer.mapData(px, colors, RENDER_SCALE), title, body,
            "map-" + job.id + "-" + tile.x + "-" + tile.z + ".png");
    }

    // ---- tile counters: the "tile summary" line at Map done (tools/metrics/map_metrics.py reads it) ----

    /** A small server setback the teleport guard judged. Any thread; never blocks or takes the engine lock. */
    public void noteSetback() {
        setbacksSeen.incrementAndGet();
    }

    /** This tile's counters ({@link Job.TileState#metrics}), made on first use. Tick thread, with a tile. */
    private io.github.digitalisp.mapart.model.TileMetrics tileMetrics() {
        if (tile.metrics == null) {
            tile.metrics = new io.github.digitalisp.mapart.model.TileMetrics();
            tile.metrics.fromStart = tile.placed == 0;
        }
        return tile.metrics;
    }

    /**
     * Tick thread, before a phase change ({@code next}) and at other save points (null): the setbacks seen since the
     * last call go to the tile (dropped without one), and a RESTOCK round that ends in BUILD counts, with its load.
     */
    private void countForTile(@Nullable Phase next) {
        int setbacks = setbacksSeen.getAndSet(0);
        if (tile == null) return;
        io.github.digitalisp.mapart.model.TileMetrics m = tileMetrics();
        m.setbacks += setbacks;
        if (phase == Phase.RESTOCK && next == Phase.BUILD) {
            m.restocks++;
            if (lastRestockWithdrawn > 0) {
                m.loads++;
                m.withdrawn += lastRestockWithdrawn;
            }
        }
    }

    /**
     * {@link #listener} for one lane run, counting for the tile summary: each cell that was air (or not loaded) when
     * the run started counts once when placed, from a stand spot or walking; a cell a re-run finds placed already (the
     * run before it was cut short) does not count again.
     */
    private PlacementListener laneListener(Lane lane) {
        return laneListener(lane, List.of());
    }

    /** {@link #laneListener(Lane)} counting the cells of {@code load} too (the in-reach greedy places them early). */
    private PlacementListener laneListener(Lane lane, List<Lane> load) {
        WorldView w = bot.world();
        Set<Pos> open = new HashSet<>();
        for (Placement p : lane.placements()) if (w.block(p.pos()).isAir()) open.add(p.pos());
        for (Lane l : load) for (Placement p : l.placements()) if (w.block(p.pos()).isAir()) open.add(p.pos());
        if (tile != null) tileMetrics().laneRuns++;
        return new PlacementListener() {
            @Override
            public void placed(Placement p) {
                count(p, false);
                listener.placed(p);
            }

            @Override
            public void placedFromStandSpot(Placement p) {
                count(p, true);
                listener.placed(p);
            }

            @Override
            public void skipped(Placement p, String reason) {
                listener.skipped(p, reason);
            }

            private void count(Placement p, boolean fromSpot) {
                if (tile == null || !open.remove(p.pos())) return;
                io.github.digitalisp.mapart.model.TileMetrics m = tileMetrics();
                if (phase == Phase.VERIFY) m.repaired++;
                else m.placed++;
                if (fromSpot) m.fromStandSpots++;
            }
        };
    }

    /**
     * One line, no coordinates: "placed 15936 (build 15903, repair 33), from stand spots 10618, walking 5318; lane runs
     * 97; restocks 51 (49 loads, 13090 items); setbacks 412" (+ "; counted from mid-tile only" when counting began late).
     */
    private String tileSummary() {
        io.github.digitalisp.mapart.model.TileMetrics m = tileMetrics();
        int all = m.placed + m.repaired;
        return "placed " + all + " (build " + m.placed + ", repair " + m.repaired + "), from stand spots "
            + m.fromStandSpots + ", walking " + (all - m.fromStandSpots) + "; lane runs " + m.laneRuns + "; restocks "
            + m.restocks + " (" + m.loads + " loads, " + m.withdrawn + " items); setbacks " + m.setbacks
            + (m.fromStart ? "" : "; counted from mid-tile only");
    }

    // =====================================================================================
    // COUNT: the base's map counter
    // =====================================================================================

    /** Status note while COUNT presses the map counter. */
    public static final String COUNTER_NOTE = "pressing the map counter";
    /** Status note while the map counter button is still pressed (by someone else) and COUNT waits for it to pop. */
    public static final String COUNTER_WAIT_NOTE = "map counter: waiting for the button to pop";
    /** Status note while COUNT waits after a (re)connect before it presses ({@code %d}: seconds left). */
    public static final String COUNTER_LOGIN_NOTE = "map counter: waiting %d s after login";
    /** Status note while COUNT checks that the blocks around the counter went back to their state before the press. */
    public static final String COUNTER_CHECK_NOTE = "map counter: checking it went back to its start state";
    /** Alert title of the jam check ({@link #counterJam}). */
    public static final String COUNTER_JAM_TITLE = "Map counter may be jammed";
    /** Info title once a counter that looked jammed checks clean again and the owed counts are pressed. */
    public static final String COUNTER_OK_TITLE = "Map counter working again";

    /**
     * After STORE: COUNT while the map counter is configured and this stored map is not counted yet, else what follows
     * the count ({@link #afterCount}). A ridden colour-suppression map is counted like every map, right after it is
     * stored, and only then waits for the muncher (owner 2026-11-13; before, RIDE_RESET came first and a reset that
     * blocked left the map uncounted). Also where a restart with a stored tile goes on.
     */
    private Phase afterStore() {
        return counterDue() ? Phase.COUNT : afterCount();
    }

    /** After COUNT (pressed, owed, skipped or not configured): the ride tile's reset check until it settled, else NUKE. */
    private Phase afterCount() {
        // ride.waitForReset off: the job is done once the map is stored and counted (stepNuke ends a colour-suppression
        // tile without a nuke); the next map's passes wait for their own layer (layerStillClearing)
        if (job != null && tile != null && platform != null && rideTile() && !tile.rideSettled && ride().waitForReset)
            return Phase.RIDE_RESET;
        return Phase.NUKE;
    }

    private boolean counterDue() {
        return MapCounter.enabled(config.site) && tile != null && tile.status == TileStatus.STORED && !tile.counted;
    }

    @Override
    public Pos mapCounterButton() {
        return counterFound;
    }

    /**
     * {@code mapart site}: the rocket chest ({@code build.elytraLegs}, {@link ElytraKit}) as the engine would pick it
     * now (listed, or the nearest a sign names), the one it remembers, and the signs in view, each naming one with
     * the block it is on. Reads the chunk cache from the command's thread (copies; a chunk that does not read is
     * skipped).
     */
    @Override
    public String rocketChestReport() {
        WorldView w = bot.world();
        List<WorldView.Sign> signs = w.signs();
        Pos feet = botFeet();
        List<Pos> named = ElytraKit.signedChests(signs, w::block, feet);
        StringBuilder sb = new StringBuilder("Rocket chest (build.elytraLegs ")
            .append(config.build.elytraLegs ? "on" : "off").append("): ");
        Pos listed = config.site.storage.rocketChests.stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (listed != null) sb.append("listed at ").append(listed.key());
        else if (!named.isEmpty()) sb.append("found by its sign at ").append(named.get(0).key())
            .append(named.size() > 1 ? " (nearest of " + named.size() + ")" : "");
        else sb.append("none (no site.storage.rocketChests, no sign saying \"rocket\" on a chest or barrel in view)");
        if (kit.found() != null) sb.append("; remembered ").append(kit.found().key());
        String census = w.blockEntityCensus();
        sb.append("; in view: ").append(census.isBlank() ? signs.size() + " signs" : census);
        int shown = 0;
        for (WorldView.Sign s : signs) {
            if (!ElytraKit.names(s) || shown++ >= 4) continue;
            Pos at = ElytraKit.attached(s);
            sb.append("; sign ").append(s.pos().key()).append(' ').append(s.block().id().replace("minecraft:", ""))
                .append(s.block().props().containsKey("facing") ? "[" + s.block().props().get("facing") + "]" : "")
                .append(" on ").append(at == null ? "nothing" : at.key() + " " + w.block(at).id().replace("minecraft:", ""));
        }
        if (shown == 0 && !signs.isEmpty()) {
            WorldView.Sign s = signs.get(0);
            String t = s.text().replace('\n', '|');
            sb.append("; none says \"rocket\" (first: ").append(s.block().id().replace("minecraft:", "")).append(" \"")
                .append(t.length() > 60 ? t.substring(0, 60) + "..." : t).append("\")");
        }
        return sb.toString();
    }

    /**
     * COUNT: presses the base's map counter ({@code site.mapCounter}) once for the map just stored, while the bot still
     * stands by the supply chests, then NUKE. The press is recorded per tile ({@code TileState.counted}) once the button
     * showed it, so a restart between STORE and the press presses once and a restart after it never again. No press
     * before the bot has been in game, and the counter's chunk loaded, for {@code site.mapCounterLoginDelaySeconds}
     * (real time: right after a login the counter's pistons lag and jam). A button still pressed by someone else is
     * waited for until it pops (at most {@code COUNTER_POP_WAIT_MS}). Then the jam rules ({@link #counterReady}, after
     * each press {@link #checkCounter}): a counter that looks jammed is not pressed, the map's count is owed, and owed
     * counts are pressed before this map's own once it checks clean again. Non-critical: nothing here goes BLOCKED. A
     * press that fails is one alert for that map; a counter that cannot be found (no button, several, misconfigured,
     * protected) is one alert per run, and only a block whose id ends in {@code _button} is ever pressed.
     */
    private void stepCount() {
        Ctx c = ctx;
        if (c.counterCheck != null) {
            checkCounter(c);
            return;
        }
        if (!counterDue()) {
            go(afterCount());
            return;
        }
        MapArtConfig.SiteCfg site = config.site;
        String problem = MapCounter.configProblem(site);
        if (problem != null) {
            counterSkipped("config: " + problem, problem);
            return;
        }
        // the press and its read-back: one bot at once (another's press would read as this map's count)
        if (awaitSpot(Claim.COUNTER, "the map counter")) return;
        note = COUNTER_NOTE;
        Pos button = counterButton(c);
        if (button == null) return; // walking there, waiting for the chunk, or skipped
        MapArtConfig.RegionCfg region = SiteRules.protectedRegionAt(site, button);
        if (region != null) {
            counterSkipped("protected " + button.key(), "the button at " + button.key() + " lies in protected region "
                + SiteRules.describe(site.protectedRegions, region) + ", where nothing is ever pressed");
            return;
        }
        long wait = counterLoginWait();
        if (wait > 0) {
            long secs = Math.ceilDiv(wait, 1000L);
            if (!c.counterLoginLogged) {
                c.counterLoginLogged = true;
                log.info("map counter at {}: waiting {} s after login before pressing (site.mapCounterLoginDelaySeconds"
                    + " {})", button.key(), secs, site.mapCounterLoginDelaySeconds);
            }
            note = String.format(Locale.ROOT, COUNTER_LOGIN_NOTE, secs);
            sleep(COUNTER_POLL_TICKS);
            return;
        }
        if (MapCounter.pressed(bot.world().block(button))) {
            long now = clock.millis();
            if (c.counterWaitSince < 0) {
                c.counterWaitSince = now;
                log.info("map counter at {} is pressed already: waiting for it to pop", button.key());
            } else if (now - c.counterWaitSince > COUNTER_POP_WAIT_MS) {
                counterFailed("the button at " + button.key() + " stayed pressed for " + COUNTER_POP_WAIT_MS / 1000 + " s");
                return;
            }
            note = COUNTER_WAIT_NOTE;
            sleep(COUNTER_POLL_TICKS);
            return;
        }
        c.counterWaitSince = -1;
        int radius = MapCounter.checkRadius(site);
        Map<Pos, BlockSpec> before = radius > 0 ? MapCounter.snapshot(bot.world(), button, radius) : null;
        if (before != null && !counterReady(c, button, before)) return;
        pressCounter(c, button, radius, before);
    }

    /**
     * One press: an owed count while any are owed ({@link #counterOwed}), else this map's own. With the jam check on,
     * {@link #checkCounter} follows; without it, the next press (or NUKE).
     */
    private void pressCounter(Ctx c, Pos button, int radius, Map<Pos, BlockSpec> before) {
        boolean owedPress = counterOwed > 0;
        if (before != null && counterGood == null) counterGood = MapCounter.normalize(before);
        note = COUNTER_NOTE;
        run(bot.pressButton(button, config.site.mapCounter.standAt), () -> {
            if (owedPress) {
                counterOwed--;
                counterOwedPressed++;
                String forMap = counterOwedFor.pollFirst();
                log.info("map counter pressed for an owed count{} ({} still owed): button {} clicked from {}",
                    forMap == null ? "" : " (" + forMap + ")", counterOwed, button.key(), botFeet().key());
            } else {
                tile.counted = true;
                saveJob(job);
                log.info("map counter pressed for {}: button {} clicked from {}", label(), button.key(), botFeet().key());
            }
            if (before == null) {
                counterPressed();
                return;
            }
            c.counterCheck = new CounterCheck(button, radius, before, clock.millis());
            note = COUNTER_CHECK_NOTE;
            sleep(COUNTER_POLL_TICKS);
        }, why -> {
            if (owedPress) { // this map's own press never came: its count waits with the owed ones
                counterOwed++;
                counterOwedFor.addLast(label());
            }
            counterFailed(why);
        });
    }

    /** A press went through (and checked clean): the next one while this map's own is still to come, else done. */
    private void counterPressed() {
        if (!tile.counted) {
            sleep(COUNTER_POLL_TICKS); // the next press: stepCount waits for the button to pop first
            return;
        }
        if (counterEpisode || counterOwedPressed > 0) {
            int n = counterOwedPressed;
            String how = counterAdopted
                ? " The blocks around it had changed for " + COUNTER_ADOPT_VISITS + " maps in a row with no piston"
                    + " stuck out, so that state is now taken as its normal one."
                : "";
            String msg = n == 0 ? "Map counter working again: it checked clean; no counts were owed." + how
                : "Map counter working again: " + n + (n == 1 ? " owed count" : " owed counts") + " pressed." + how;
            log.info(msg);
            notifier.info(COUNTER_OK_TITLE, label() + "\n" + msg);
        }
        counterEpisode = false;
        counterAdopted = false;
        counterOwedPressed = 0;
        go(afterCount());
    }

    /**
     * Before a press with the jam check on: false (and the map's count owed, or a short wait) while the counter looks
     * jammed. A piston stuck out ({@link MapCounter#stuck}) for {@code COUNTER_SETTLE_MS} is a jam (an alert when no
     * jam episode runs yet). During an episode, the blocks must also match the last good state after a press
     * ({@link #counterGood}, signal properties ignored); after {@code COUNTER_ADOPT_VISITS} maps in a row that differ
     * with no piston stuck out, the state now is taken as the new normal (the owner may have rebuilt something).
     */
    private boolean counterReady(Ctx c, Pos button, Map<Pos, BlockSpec> now) {
        List<Pos> stuck = MapCounter.stuck(now);
        if (!stuck.isEmpty()) {
            long t = clock.millis();
            if (c.counterStuckSince < 0) c.counterStuckSince = t;
            if (t - c.counterStuckSince < COUNTER_SETTLE_MS) { // a piston still on its way back
                note = COUNTER_CHECK_NOTE;
                sleep(COUNTER_POLL_TICKS);
                return false;
            }
            counterOddVisits = 0;
            Pos p = stuck.getFirst();
            String what = "a piston is stuck out before the press (" + now.get(p) + " at " + p.key() + ")";
            counterJam("Map counter may be jammed: " + what + ". Check it in game.");
            oweCount(what);
            return false;
        }
        c.counterStuckSince = -1;
        if (counterEpisode && counterGood != null) {
            List<Pos> diff = MapCounter.differs(counterGood, now);
            if (!diff.isEmpty()) {
                Pos p = diff.getFirst();
                counterOddVisits++;
                String what = diff.size() + (diff.size() == 1 ? " block differs" : " blocks differ")
                    + " from its last good state (e.g. " + now.get(p) + " at " + p.key() + ", was " + counterGood.get(p)
                    + ")";
                if (counterOddVisits < COUNTER_ADOPT_VISITS) {
                    oweCount(what + ", " + counterOddVisits + " of " + COUNTER_ADOPT_VISITS + " maps in a row");
                    return false;
                }
                log.warn("map counter at {}: {} for {} maps in a row with no piston stuck out: taking it as its normal"
                    + " state ({})", button.key(), what, COUNTER_ADOPT_VISITS, keys(diff));
                counterGood = MapCounter.normalize(now);
                counterAdopted = true;
            }
        }
        counterOddVisits = 0;
        return true;
    }

    /** This map's count is owed: not pressed now, pressed by itself once the counter checks clean (WARN, no alert). */
    private void oweCount(String why) {
        counterOwed++;
        counterOwedFor.addLast(label());
        log.warn("map counter not pressed for {}: {}; {} {} owed, pressed once it checks clean again", label(), why,
            counterOwed, counterOwed == 1 ? "count" : "counts");
        go(afterCount());
    }

    /** A jam: one alert per episode (until the counter checks clean and the owed counts are pressed), else a WARN. */
    private void counterJam(String msg) {
        if (counterEpisode) {
            log.warn("{} (still; alerted already)", msg);
            return;
        }
        counterEpisode = true;
        counterAdopted = false;
        log.warn(msg);
        alertHere(COUNTER_JAM_TITLE, msg + " Later maps are not pressed while it looks jammed;"
            + " their counts are kept and pressed by themselves once it checks clean again.");
    }

    /**
     * Milliseconds COUNT still waits before it presses: {@code site.mapCounterLoginDelaySeconds} of real time (the
     * engine clock, not ticks) after the bot ticks last (re)started and after the counter's chunk was last loaded
     * ({@link #trackCounterChunk}); 0 = press now.
     */
    private long counterLoginWait() {
        long delay = MapCounter.loginDelayMillis(config.site);
        if (delay <= 0) return 0;
        long now = clock.millis();
        long since = Math.max(inGameSince < 0 ? now : inGameSince, counterChunkSince < 0 ? now : counterChunkSince);
        return Math.max(0, since + delay - now);
    }

    /**
     * Logs the server's simulation distance when it is first known and whenever it changes (login,
     * set_simulation_distance): the presence rule of the clear depends on it.
     */
    private void trackSimulationDistance() {
        WorldView w = bot.world();
        int d = w.simulationDistance();
        if (d <= 0 || d == simDistanceLogged) return;
        log.info("server simulation distance {} chunks (view distance {}){}", d, w.viewDistance(),
            simDistanceLogged > 0 ? ", was " + simDistanceLogged : "");
        simDistanceLogged = d;
    }

    /**
     * Every tick: since when the map counter's chunk ({@code mapCounter.pos}, else the {@code mapCounterColumn}) has
     * been loaded without a break, for {@link #counterLoginWait}. Reset when the bot ticks (re)start and on a
     * disconnect.
     */
    private void trackCounterChunk() {
        MapArtConfig.SiteCfg site = config.site;
        Pos at = MapCounter.enabled(site) ? site.mapCounter.pos : null;
        if (at == null && MapCounter.enabled(site) && site.mapCounterColumn != null) {
            MapArtConfig.ColumnCfg col = site.mapCounterColumn;
            Pos known = counterFound;
            if (known != null && known.x() == col.x && known.z() == col.z) {
                at = known;
            } else {
                int[] range = MapCounter.scanRange(site);
                at = new Pos(col.x, range != null ? (range[0] + range[1]) / 2 : bot.world().playerBlock().y(), col.z);
            }
        }
        if (at == null || !bot.world().isLoaded(at)) {
            counterChunkSince = -1;
            return;
        }
        long chunk = ((long) (at.x() >> 4) << 32) | ((at.z() >> 4) & 0xFFFFFFFFL);
        if (chunk != counterChunk || counterChunkSince < 0) {
            counterChunk = chunk;
            counterChunkSince = clock.millis();
        }
    }

    /** COUNT after a press: the blocks around the button before it ({@link MapCounter#snapshot}), for the jam check. */
    private static final class CounterCheck {
        final Pos button;
        final int radius;
        final Map<Pos, BlockSpec> before;
        /** Clock time the press task succeeded (the button showed {@code powered=true}). */
        final long pressedAt;
        /** Clock time the button was first seen popped; -1 = not yet. */
        long poppedAt = -1;

        CounterCheck(Pos button, int radius, Map<Pos, BlockSpec> before, long pressedAt) {
            this.button = button;
            this.radius = radius;
            this.before = before;
            this.pressedAt = pressedAt;
        }
    }

    /**
     * The jam check after a press ({@code site.mapCounterCheckRadius} > 0): once the button popped
     * ({@code powered=false}; at most {@code COUNTER_POP_WAIT_MS}) and {@code COUNTER_SETTLE_MS} more passed, the loaded
     * blocks around it must match their state before the press with {@link MapCounter#normalize} (signal properties
     * such as the display's power and lit ignored, any anvil the same), and no piston may be stuck out. Clean: the state
     * becomes the last good one ({@link #counterGood}) and the next press, if any, follows. Otherwise a jam
     * ({@link #counterJam}): nothing is fixed, and if this map's own press is still to come, its count is owed. A press
     * counts either way. A pause, reconnect or back-off during the check drops it.
     */
    private void checkCounter(Ctx c) {
        CounterCheck k = c.counterCheck;
        WorldView w = bot.world();
        long now = clock.millis();
        note = COUNTER_CHECK_NOTE;
        if (k.poppedAt < 0) {
            if (MapCounter.pressed(w.block(k.button)) && now - k.pressedAt < COUNTER_POP_WAIT_MS) {
                sleep(COUNTER_POLL_TICKS);
                return;
            }
            k.poppedAt = now;
        }
        if (now - k.poppedAt < COUNTER_SETTLE_MS) {
            sleep(COUNTER_POLL_TICKS);
            return;
        }
        c.counterCheck = null;
        Map<Pos, BlockSpec> after = MapCounter.snapshot(w, k.before);
        List<Pos> changed = MapCounter.differs(k.before, after);
        List<Pos> stuck = MapCounter.stuck(after);
        if (changed.isEmpty() && stuck.isEmpty()) {
            counterGood = MapCounter.normalize(after);
            List<Pos> raw = MapCounter.differsRaw(k.before, after);
            log.info("map counter at {} back to its start state ({} blocks within {} checked; {} changed only in"
                + " signal properties{})", k.button.key(), after.size(), k.radius, raw.size(), changes(raw, k.before, after));
            counterPressed();
            return;
        }
        String msg;
        if (!changed.isEmpty()) {
            Pos first = changed.getFirst();
            int n = changed.size();
            msg = "Map counter may be jammed: " + n + (n == 1 ? " block" : " blocks") + " around the counter did not"
                + " return to " + (n == 1 ? "its" : "their") + " start state (e.g. " + after.get(first) + " at "
                + first.key() + ", was " + k.before.get(first) + "). Check it in game.";
        } else {
            Pos p = stuck.getFirst();
            msg = "Map counter may be jammed: a piston is stuck out after the press (" + after.get(p) + " at " + p.key()
                + "). Check it in game.";
        }
        log.warn("map counter at {}: {} of {} blocks within {} changed{}; {} stuck pistons", k.button.key(),
            changed.size(), after.size(), k.radius, changes(changed, k.before, after), stuck.size());
        counterOddVisits = 0;
        counterJam(msg);
        if (!tile.counted) oweCount("the counter looked jammed after the press for an owed count");
        else go(afterCount());
    }

    /** ": x,y,z a → b, ..." for at most 8 of {@code cells} (the log line of a check), or "" for none. */
    private static String changes(List<Pos> cells, Map<Pos, BlockSpec> before, Map<Pos, BlockSpec> after) {
        if (cells.isEmpty()) return "";
        List<String> out = new ArrayList<>();
        for (Pos p : cells) {
            if (out.size() == 8) {
                out.add("… " + (cells.size() - 8) + " more");
                break;
            }
            out.add(p.key() + " " + before.get(p) + " → " + after.get(p));
        }
        return ": " + String.join("; ", out);
    }

    /**
     * The map counter button: {@code mapCounter.pos}, or the one button in {@code mapCounterColumn} between
     * {@link MapCounter#scanRange} (found once per run and logged, checked again before every press). Null while the bot
     * walks towards it or waits for its chunk, and after the counter was skipped (no button there, or several).
     */
    private Pos counterButton(Ctx c) {
        MapArtConfig.SiteCfg site = config.site;
        WorldView w = bot.world();
        Pos fixed = site.mapCounter.pos;
        if (fixed != null) {
            if (!counterLoaded(c, fixed, fixed)) return null;
            BlockSpec b = w.block(fixed);
            if (MapCounter.isButton(b)) return fixed;
            counterSkipped("no button " + fixed.key(), "site.mapCounter.pos " + fixed.key() + " holds " + b.id()
                + ", not a button");
            return null;
        }
        MapArtConfig.ColumnCfg col = site.mapCounterColumn;
        String column = col.x + "," + col.z;
        Pos known = counterFound;
        if (known != null && column.equals(counterFoundIn)) {
            if (!counterLoaded(c, known, known)) return null;
            if (MapCounter.isButton(w.block(known))) return known;
            log.warn("map counter button at {} is gone ({} there); looking in column {} again", known.key(),
                w.block(known).id(), column);
            counterFound = null;
        }
        int[] range = MapCounter.scanRange(site);
        if (range == null) range = MapCounter.scanRange(botFeet().y());
        Pos low = new Pos(col.x, range[0], col.z);
        Pos high = new Pos(col.x, range[1], col.z);
        if (!counterLoaded(c, new Pos(col.x, (range[0] + range[1]) / 2, col.z), low, high)) return null;
        MapCounter.Scan s = MapCounter.scan(w, col.x, col.z, range[0], range[1]);
        String where = "column " + column + " (y " + range[0] + " to " + range[1] + ")";
        if (s.unloaded() > 0) {
            counterFailed(s.unloaded() + " blocks of " + where + " are not loaded");
            return null;
        }
        if (s.buttons().size() == 1) {
            Pos found = s.buttons().getFirst();
            counterFound = found;
            counterFoundIn = column;
            log.info("map counter button found at {} ({})", found.key(), w.block(found).id());
            return found;
        }
        if (s.buttons().isEmpty()) {
            List<Pos> near = MapCounter.around(w, col.x, col.z, range[0], range[1], 4);
            counterSkipped("none in " + column, "no button in " + where + (near.isEmpty() ? ""
                : "; buttons beside it: " + keys(near) + " (set site.mapCounter.pos if one of them is the counter)"));
        } else {
            counterSkipped("several in " + column, s.buttons().size() + " buttons in " + where + " (" + keys(s.buttons())
                + "): it cannot tell which one counts (set site.mapCounter.pos)");
        }
        return null;
    }

    /**
     * True when every cell of {@code cells} is loaded. Otherwise walks towards {@code target} once, waits up to
     * {@code LOAD_WAIT_TICKS} for the chunk, then gives the press up for this map ({@link #counterFailed}).
     */
    private boolean counterLoaded(Ctx c, Pos target, Pos... cells) {
        WorldView w = bot.world();
        boolean loaded = true;
        for (Pos p : cells) loaded &= w.isLoaded(p);
        if (loaded) return true;
        if (c.attempt("counter-walk") == 0) {
            run(bot.walkTo(target, 3), null, r -> log.warn("walk to the map counter at {} failed: {}", target.key(), r));
            return false;
        }
        if (c.loadWaitTicks < LOAD_WAIT_TICKS) {
            c.loadWaitTicks += POLL_TICKS;
            sleep(POLL_TICKS);
            return false;
        }
        counterFailed("the map counter at " + target.x() + "," + target.z() + " is not loaded");
        return false;
    }

    /** The press failed for this map: one alert, then NUKE (never BLOCKED: the counter is not critical). */
    private void counterFailed(String why) {
        log.warn("map counter not pressed for {}: {}", label(), why);
        alertHere("Map counter not pressed", "The map counter was not pressed: " + why
            + ". The map is stored and the bot carries on.");
        go(afterCount());
    }

    /**
     * The counter cannot be pressed at all (no button in the column, several, not a button, a protected region, a bad
     * config): one alert per run and problem, a log line for every map, then NUKE. Nothing else is pressed.
     */
    private void counterSkipped(String key, String why) {
        log.warn("map counter not pressed for {}: {}", label(), why);
        if (counterAlerted.add(key)) {
            alertHere("Map counter not pressed", "The map counter was not pressed: " + why
                + ". The map is stored and the bot carries on. Maps stored until this is fixed are not counted (alerted"
                + " once).");
        }
        go(afterCount());
    }

    // =====================================================================================
    // Server restart hold (RestartHold)
    // =====================================================================================

    /** Status note prefix while the restart hold keeps the carts off and a NUKE or clear press waiting. */
    public static final String RESTART_NOTE = "server restart due";
    /** Status note prefix while a ride waits for the scheduled restart ({@link #restartInsideRide}). */
    public static final String RESTART_EXPECTED_NOTE = "server restart expected";
    /** Seconds on top of the expected ride for {@link #restartInsideRide}: the viewpoints, the lap, the boarding, a margin. */
    static final int RIDE_RESTART_MARGIN_S = 900;
    /** A scheduled restart not seen this long after its time: the schedule is off (a restart nobody saw); no more hold. */
    static final long RESTART_SCHEDULE_GRACE_MS = 30 * 60_000L;

    /**
     * 2b2t restarts {@code everyMinutes} after its last restart (live 2026-10-18 to 11-14: 3 d 5 h 7 min, within half a
     * minute, five times in a row and after each unscheduled one). True when a ride of {@code rideMs} started at
     * {@code now} would still be under way at the next one: a restart mid-ride leaves the rider seated but lets the
     * stasis pearls go, the sweeper stops and the lane ends pulse on without it (the map lost, the muncher stalled).
     * False with the schedule off (0), no restart on record, the next one far enough away, or its time passed by
     * {@link #RESTART_SCHEDULE_GRACE_MS} with none seen.
     */
    static boolean restartInsideRide(long now, long lastRestartAt, int everyMinutes, long rideMs) {
        if (everyMinutes <= 0 || lastRestartAt <= 0) return false;
        long due = lastRestartAt + everyMinutes * 60_000L;
        return now + rideMs >= due && now <= due + RESTART_SCHEDULE_GRACE_MS;
    }

    /**
     * Every tick: the hold starts once {@code site.restartHoldMinutes} before the announced restart (one INFO line, one
     * detail line on Discord) and ends {@link RestartHold#GRACE_MS} after it with no restart. Its end after a restart is
     * {@link #onBotTickStarting}'s.
     */
    private void updateRestartHold() {
        long now = clock.millis();
        if (restart.expired(now)) {
            if (restartHolding) {
                log.info("server restart hold over: no restart within {} min of the announced time; the carts, NUKEs and"
                    + " clears run as usual again", RestartHold.GRACE_MS / 60_000);
                wakeTick = tick;
            }
            restartHolding = false;
            return;
        }
        boolean holds = restart.holds(now, config.site.restartHoldMinutes);
        if (holds == restartHolding) return;
        restartHolding = holds;
        if (!holds) {
            log.info("server restart hold off (site.restartHoldMinutes {})", config.site.restartHoldMinutes);
            wakeTick = tick;
            return;
        }
        long min = restart.minutesLeft(now);
        String what = phase == Phase.NUKE && nukeNeedsCarts()
            ? "the NUKE stops at its next safe point and waits with the carts off"
            : phase == Phase.NUKE ? "the NUKE goes on (no carts here), then the clear waits"
            : phase == Phase.CLEAR && !ctx.pressSent ? "the clear waits with the carts off, nothing pressed"
            : phase == Phase.CLEAR || phase == Phase.WAIT_CLEAR ? "the clear under way finishes, then the carts go off"
            : "the carts go off if on, and no NUKE or clear starts";
        log.info("server restart in about {} min (\"{}\"): {} until the bot is back after the restart ({}{})", min,
            restart.text(), what, phase, job != null ? ", " + label() : "");
        notifier.detail("Server restart", "Restart in about " + min + " min: " + what
            + " until the bot is back after it." + currentLine());
    }

    /** The hold stops the running (or parked) task: NUKE's, or a CLEAR's before any press of it went out. */
    private boolean restartStops() {
        return restartHolding && (phase == Phase.NUKE && nukeNeedsCarts() || phase == Phase.CLEAR && !ctx.pressSent);
    }

    /**
     * The NUKE runs with the item collection on (moss: the carts take its drops), so the hold stops it. Without a
     * collection to switch (soul sand: hoppers under the floor) it goes on, and only the clear after it waits.
     */
    private boolean nukeNeedsCarts() {
        return platform != null && platform.collectionOn != null && platform.collectionOn.pos != null;
    }

    /**
     * Every tick while the engine acts: in the hold, the running task of a NUKE or of a CLEAR not pressed yet is asked to
     * stop at its next safe point (on the ground, nothing in flight); a press already aiming or clicking runs to its end.
     * The parked task is then dropped ({@link #dropParkedForRestart}). Never the hold's own carts-off task or the vitals'.
     */
    private void checkRestartTask() {
        if (!restartStops() || task == null || task == restartTask || vitalsTask || rescueTask || askedToPark == task)
            return;
        askedToPark = task;
        parkAccepted = task.requestPark();
        log.debug("server restart: {}", parkAccepted ? "stopping " + task.name() + " at its next safe point"
            : "finishing " + task.name() + " first (it cannot stop midway)");
    }

    /** The parked NUKE or CLEAR task is not resumed: the phase starts over after the hold (a clear not counted). */
    private void dropParkedForRestart() {
        parkedTask.cancel();
        log.debug("dropped parked {} for the server restart", parkedTask.name());
        parkedTask = null;
        parkedOk = null;
        parkedFail = null;
        parkedAttempt = null;
        parkedAttemptCtx = null;
        if (phase == Phase.CLEAR) uncountClear(ctx);
        ctx = new Ctx();
    }

    /** A CLEAR that pressed nothing yet waits for the restart: its attempt is not counted. */
    private void uncountClear(Ctx c) {
        if (!c.counted) return;
        c.counted = false;
        c.clearActs = null;
        c.actuator = 0;
        c.leverStage = 0;
        clearAttempts = Math.max(0, clearAttempts - 1);
    }

    /** The carts off through the usual actuator and indicator check; the task it starts is the hold's own. */
    private boolean holdCartsOff() {
        if (!ensureCollection(false)) return false;
        restartTask = task;
        return true;
    }

    /**
     * In the hold, outside NUKE, CLEAR and WAIT_CLEAR (they hold by themselves): carts that read ON (the lamp loaded and
     * lit; e.g. INSPECT after a clear, before GEAR switches them off) go off between two tasks. True while acting.
     */
    private boolean restartCartsOffBetweenTasks() {
        if (job == null || platform == null || platform.collectionIndicator == null || phase == Phase.NUKE
            || phase == Phase.CLEAR || phase == Phase.WAIT_CLEAR || phase == Phase.BLOCKED) return false;
        WorldView w = bot.world();
        if (!w.isLoaded(platform.collectionIndicator) || !collectionIsOn(w)) return false;
        return holdCartsOff();
    }

    /** WAIT_CLEAR, the layer clear in the hold: no run-on; the carts off now, then on as after the run-on. */
    private void restartEndsRunOn(Ctx c) {
        c.runOnCut = true; // the layer read clear: not read (and logged) again while the carts go off
        if (holdCartsOff()) return;
        log.info("item collection run-on on {} cut short: {}", platform.id, RESTART_NOTE);
        leaveAfterClear();
    }

    // =====================================================================================
    // NUKE / CLEAR / WAIT_CLEAR / FINISH_TILE
    // =====================================================================================

    private void stepNuke() {
        if (suppressionTile()) {
            go(Phase.FINISH_TILE); // never nuked: the muncher clears the layers (docs/COLOR_SUPPRESSION.md)
            return;
        }
        Ctx c = ctx;
        WorldView w = bot.world();
        if (!c.toggled && plan.allLoaded(w) && plan.survey(w).clear()) {
            go(afterClear());
            return;
        }
        if (restartHolding && nukeNeedsCarts()) {
            // the carts off and nothing broken until the restart is over (its drops would wait on the floor)
            if (holdCartsOff()) return;
            note = RESTART_NOTE + ": the NUKE waits with the carts off";
            sleep(POLL_TICKS);
            return;
        }
        if (ensureCollection(true)) return;
        c.toggled = true;
        restartCartsOff = false;
        if (awaitPlatformLoaded()) return;
        if (c.carry == null && decideCarryOver(w)) return; // the next tile's plan is read first
        TilePlan keep = carryTeardown() ? carryNext.plan() : null;
        if (!c.sorterNoted && keep == null) { // a carry-over feeds the sorter only what it breaks (noteNukedMaterials)
            c.sorterNoted = true;
            noteSorterFed(w);
        }
        List<Pos> targets = keep != null ? CarryOver.targets(keep, w) : plan.nukeTargets(w, platform.clearableBlocks);
        List<Pos> stuck = unbreakable(w, targets);
        if (!stuck.isEmpty() && keep != null) {
            // a cell the carry-over would break turned unbreakable since the decision: the normal end of tile
            log.info("{}: carry-over dropped: {} blocks it would break cannot be broken (e.g. {} at {}{}); nuke and clear",
                label(), stuck.size(), w.block(stuck.getFirst()), stuck.getFirst(), whyUnbreakable(w, stuck.getFirst()));
            c.carry = false;
            return;
        }
        if (!stuck.isEmpty()) {
            block("protected blocks on the art layer of " + platform.id + " (the clear would break the machines): "
                + stuck.size() + ", e.g. " + w.block(stuck.getFirst()) + " at " + stuck.getFirst()
                + whyUnbreakable(w, stuck.getFirst()));
            return;
        }
        if (!targets.isEmpty()) {
            if (c.nukeRounds++ >= NUKE_ROUNDS) {
                stepFailed(targets.size() + " blocks did not break");
                return;
            }
            if (hopFrom(platform)) return; // on the raised centre (a restart there): down to the art layer first
            if (keep != null) {
                note = "breaking " + targets.size() + " blocks the next tile does not keep";
                log.info("carry-over: breaking {} blocks on {} that {} does not keep", targets.size(), platform.id,
                    carryNext.label());
            } else {
                note = "breaking " + targets.size() + " blocks";
                log.info("nuking {} blocks on {}", targets.size(), platform.id);
            }
            noteNukedMaterials(w, targets);
            runBreak(serpentine(targets), () -> c.nukeRounds--, true);
            return;
        }
        if (keep != null) {
            // every block left is the next tile's own in its cell: no clear (it would push the carpets off and the full
            // blocks into the machines); the next tile resumes from them
            carriedOver = true;
            carriedCells = keep.correctCount(w);
            log.info("carry-over on {}: {} of {} cells of {} kept; no clear", platform.id, carriedCells, keep.total,
                carryNext.label());
            go(Phase.FINISH_TILE);
            return;
        }
        TilePlan.Survey s = plan.survey(w);
        if (s.clear()) {
            go(afterClear());
            return;
        }
        if (s.fluid()) {
            block("fluid on the art layer of " + platform.id + "; the piston clear cannot remove it");
            return;
        }
        // the breaks left the bot on the art layer: off it (shortest way to the centre structure) before CLEAR
        if (leaveClearSweep("after the nuke")) return;
        go(Phase.CLEAR);
    }

    // ---- carry-over between tiles (build.carryOver) ----

    /**
     * This NUKE ends this bot's own stored map with {@code build.carryOver} on: not a cancel, a band, nor colour
     * suppression.
     */
    private boolean carryOverApplies() {
        return config.build.carryOver && tile != null && tile.status == TileStatus.STORED && !aborting && plan != null
            && plan.band == null && platform != null && !platform.isColorSuppression() && !suppressionTile() && !bandUnit();
    }

    /** The NUKE under way keeps the next tile's cells ({@link #decideCarryOver} took it; no cancel since). */
    private boolean carryTeardown() {
        return phase == Phase.NUKE && ctx != null && Boolean.TRUE.equals(ctx.carry) && carryNext != null && !aborting;
    }

    /**
     * The tile built next on this platform once the current one is done, or null with the reason in {@code why}: the
     * map SELECT takes next ({@link #nextInOrder}), only when it is settled: with {@code build.skipShortTiles} it must also be the first
     * map with nothing placed in queue and map order (SELECT may otherwise take another by stock), and it goes on this
     * platform.
     */
    private @Nullable CarrySource carrySource(StringBuilder why) {
        PlatformCfg p = platform;
        List<Job> jobs = coord.jobs();
        Candidate next = nextInOrder(jobs);
        if (next == null) {
            why.append("no other map in the queue");
            return null;
        }
        if (config.build.skipShortTiles) {
            Option first = null;
            for (Option o : freshOptions(jobs)) {
                if (o.due()) {
                    first = o;
                    break;
                }
            }
            if (first == null || first.c().tile() != next.tile()) {
                why.append("the next map is not settled (").append(label(next.job(), next.tile())).append(" in order, ")
                    .append(first == null ? "none" : label(first.c().job(), first.c().tile())).append(" first by stock)");
                return null;
            }
        }
        if (!next.platform().id.equals(p.id)) {
            why.append(label(next.job(), next.tile())).append(" goes on platform ").append(next.platform().id);
            return null;
        }
        if (next.job().isSuppression()) {
            why.append(label(next.job(), next.tile())).append(" is a color-suppression pass");
            return null;
        }
        Job j = next.job();
        Job.TileState t = next.tile();
        return new CarrySource(tileKey(j, t), label(j, t), j, t,
            () -> new TilePlan(store.loadTile(j.id, t.coord()), geometryFor(j, t, p), catalog, fixtures(p)));
    }

    /**
     * What {@link #findCandidate} would take once the current tile is done, without its side effects (it completes jobs):
     * the open tile of the first ACTIVE, then QUEUED, then due WAITING_MATERIALS job that has a platform.
     */
    private @Nullable Candidate nextInOrder(List<Job> jobs) {
        long now = clock.millis();
        for (JobStatus want : List.of(JobStatus.ACTIVE, JobStatus.QUEUED, JobStatus.WAITING_MATERIALS)) {
            for (Job j : jobs) {
                if (j.status != want) continue;
                if (want == JobStatus.WAITING_MATERIALS && !waitingDue(j, now)) continue;
                Job.TileState t = openTile(j);
                if (t == null || t == tile || coord.unit(j, t) == null) continue;
                PlatformCfg p = choosePlatform(j, t, jobs);
                if (p != null) return new Candidate(j, t, p);
            }
        }
        return null;
    }

    /**
     * NUKE of a stored map with {@code build.carryOver}: decides once per phase entry ({@code ctx.carry}) whether the end
     * of tile keeps the cells the next tile on this platform shares with what is on the art layer now. The next tile's
     * plan is read first, off the tick thread (true while it is read; the step runs again). Then both estimates from the
     * art layer as it is ({@link CarryOver#estimate}) and one log line. Carry-over only when it is at least
     * {@code build.carryOverMargin} faster, no fluid is on the layer, every block it would break can be broken, and
     * (local, with {@code build.skipShortTiles}) the stock covers what the next tile still needs afterwards: a short
     * next tile would keep the platform while another map could be built. Decided again after a restart or reconnect
     * from the world as it is then (half a carry-over only makes the next one cheaper).
     */
    private boolean decideCarryOver(WorldView w) {
        Ctx c = ctx;
        c.carry = false;
        if (!carryOverApplies()) return false;
        StringBuilder why = new StringBuilder();
        CarrySource src = carrySource(why);
        if (src == null) {
            log.info("{}: no carry-over ({}); nuke and clear", label(), why);
            return false;
        }
        if (carryNext == null || !carryNext.key().equals(src.key())) {
            carryNext = null;
            c.carry = null;
            run(new AsyncTask<>("read the next tile " + src.label(), executor, src.load(),
                    tp -> carryNext = new CarryNext(src.key(), src.label(), src.job(), src.tile(), tp)),
                null, r -> {
                    c.carry = false;
                    log.warn("{}: no carry-over: the next tile {} could not be read ({}); nuke and clear", label(),
                        src.label(), r);
                });
            return true;
        }
        TilePlan next = carryNext.plan();
        if (!next.geo.equals(plan.geo)) {
            log.info("{}: no carry-over ({} is built on another art layer); nuke and clear", label(), carryNext.label());
            return false;
        }
        double margin = config.build.carryOverMargin;
        CarryOver.Estimate e = CarryOver.estimate(plan, next, w, platform.clearableBlocks);
        String head = String.format(Locale.ROOT, "%s: carry-over to %s on %s: %s (margin %.0f %%)", label(),
            carryNext.label(), platform.id, e.describe(), Math.max(0, Math.min(0.9, margin)) * 100);
        String no = null;
        if (e.fluid()) no = "fluid on the art layer";
        else if (e.kept() == 0) no = "no cell in common";
        else if (!e.carry(margin)) no = "not faster by the margin";
        else {
            List<Pos> stuck = unbreakable(w, CarryOver.targets(next, w));
            if (!stuck.isEmpty()) {
                no = stuck.size() + " blocks it would break cannot be broken (e.g. " + w.block(stuck.getFirst()).id()
                    + whyUnbreakable(w, stuck.getFirst()) + ")";
            } else if (carryNext.tile() != null && config.build.skipShortTiles) {
                int miss = PartialBuild.missing(next.remainingNeeds(w), available());
                if (miss > 0) no = "the next tile would still be short of " + miss + " blocks";
            }
        }
        if (no != null) {
            log.info("{}: nuke and clear ({})", head, no);
            return false;
        }
        c.carry = true;
        log.info("{}: carrying over (about {} min saved)", head,
            String.format(Locale.ROOT, "%.1f", e.savedSeconds() / 60));
        return false;
    }

    /**
     * FINISH_TILE after a carry-over: the next tile is begun on this platform with the cells kept (ACTIVE, {@code placed}
     * and {@code carried} set), so SELECT takes it first as any started tile and its INSPECT resumes it. Nothing when that
     * tile was started, finished or cancelled meanwhile. Returns the "Tile done" sentence, or null.
     */
    private @Nullable String markCarried(CarryNext n) {
        Job j = n.job();
        Job.TileState t = n.tile();
        if (j == null || t == null) return carriedCells > 0
            ? ShortageText.count(carriedCells) + " blocks stay for " + n.label() + " (carry-over)." : null;
        boolean fresh = t.status == TileStatus.PENDING || t.status == TileStatus.ACTIVE && t.placed <= 0;
        if (j.status == null || j.status.terminal() || !fresh || carriedCells <= 0) return null;
        t.status = TileStatus.ACTIVE;
        t.platformId = platform.id;
        t.placed = carriedCells;
        t.carried = carriedCells;
        t.total = n.plan().total;
        if (j != job) saveJob(j);
        log.info("{} begun on {} with {} cells carried over", n.label(), platform.id, carriedCells);
        return ShortageText.count(carriedCells) + " blocks stay for " + ShortageText.mapIn(job, j, t) + " (carry-over).";
    }

    /**
     * The items of every block on the art layer before this NUKE (broken or cleared by the machine): what the
     * collection takes into the platform's sorter ({@link #sorterFedBy}).
     */
    private void noteSorterFed(WorldView w) {
        PlatformGeometry g = plan.geo;
        Set<String> blocks = new HashSet<>();
        for (int dz = 0; dz < TileArt.SIZE; dz++)
            for (int dx = 0; dx < TileArt.SIZE; dx++) {
                Pos p = new Pos(g.minX() + dx, g.artY(), g.minZ() + dz);
                if (!w.isLoaded(p)) continue;
                BlockSpec b = w.block(p);
                if (!b.isAir()) blocks.add(b.id());
            }
        for (String b : blocks) {
            String item = catalog.placementItem(BlockSpec.of(b)).orElse(b);
            if (!Valuables.isValuable(item)) sorterFed().add(item);
        }
    }

    /** {@link #sorterFedBy} of the current platform (a scratch set when there is none). */
    private Set<String> sorterFed() {
        return sorterFedBy.computeIfAbsent(platform == null ? "" : platform.id, k -> new HashSet<>());
    }

    /** The items of the blocks about to be nuked count as materials from now on ({@link #nukedMaterials}). */
    private void noteNukedMaterials(WorldView w, List<Pos> targets) {
        Set<String> blocks = new HashSet<>();
        for (Pos p : targets) blocks.add(w.block(p).id());
        boolean added = false;
        for (String b : blocks) {
            String item = catalog.placementItem(BlockSpec.of(b)).orElse(b);
            if (Valuables.isValuable(item)) continue;
            added |= nukedMaterials.add(item);
            sorterFed().add(item); // the nuke's drops go through this platform's collection and sorter
        }
        if (added) ctx.leftovers = null; // worked out again with them
    }

    /** {@code p.offLayerAt} as a feet cell when it is outside every clear sweep and away from the clears, else null. */
    private @Nullable Pos offLayerExit(PlatformCfg p) {
        if (p.offLayerAt == null) return null;
        Pos o = feetAt(p.offLayerAt);
        if (awayFromClearSweeps().test(o) && !byAClear(o)) return o;
        warnOnce("off-layer-" + p.id, "site.platforms " + p.id + " offLayerAt is inside a clear sweep or within "
            + CLEAR_KEEP_AWAY + " blocks of a clear button or its stand spot; not used");
        return null;
    }

    /** Ranges of the walk off the art layer: to the centre structure, and to a clear actuator's standAt. */
    private static final int EXIT_CENTER_RANGE = CENTER_RANGE;
    private static final int EXIT_STAND_RANGE = 1;
    /** Walks off the art layer per phase before the bot is still found on it: BLOCKED (never press from there). */
    private static final int EXIT_WALKS = 3;

    /**
     * Never on the art layer, nor elsewhere in a clear sweep, when a clear actuator is pressed: the press walks from
     * where the bot stands, and from the art layer that path runs over the carpets and plates the machines are about to
     * sweep (gate B: after a NUKE the bot walked along the art layer to the stairs while the CLEAR step was already
     * running). The bot first walks to an exit ({@link #sweepExits}); Baritone takes the shortest way there. True while
     * it does so (a walk task started) or after BLOCKED.
     *
     * <p>With no exit outside the sweeps configured (the centre and every clear standAt inside one) it warns once and
     * carries on: the press itself still never stands in a sweep ({@code UseRun}), only the way there is not chosen.
     */
    private boolean leaveClearSweep(String when) {
        Predicate<Pos> away = awayFromClearSweeps();
        Pos feet = botFeet();
        if (away.test(feet)) return false;
        List<Pos> exits = sweepExits(away);
        if (exits.isEmpty()) {
            warnOnce("sweep-exit-" + platform.id, "no way off the art layer of " + platform.id + " is configured outside"
                + " the clear sweep (center " + centerPos().key() + " and every clear standAt are inside it): the clear"
                + " button is approached from where the bot stands");
            return false;
        }
        int a = ctx.attempt("sweep-exit");
        if (a >= EXIT_WALKS) {
            if (!onArtLayer(feet)) {
                warnOnce("sweep-exit-stuck-" + platform.id, "still in the clear sweep of " + platform.id + " at "
                    + feet.key() + " after " + a + " walks to " + keys(exits) + "; pressing from the stand spot");
                return false;
            }
            block("cannot get off the art layer of " + platform.id + " before the clear: still at " + feet.key()
                + " after " + a + " walks to " + keys(exits) + ". The clear is never pressed from the art layer; check"
                + " the way to the center structure in game");
            return true;
        }
        Pos to = exits.get(a % exits.size());
        int range = to.equals(feetAt(centerPos())) ? EXIT_CENTER_RANGE : EXIT_STAND_RANGE;
        log.info("leaving the {} of {} {}: walking from {} to {}", onArtLayer(feet) ? "art layer" : "clear sweep",
            platform.id, when, feet.key(), to.key());
        note = "leaving the art layer before the clear";
        run(bot.walkTo(to, range), null, r -> log.warn("walk off the art layer of {} to {} failed: {}", platform.id,
            to.key(), r));
        return true;
    }

    /**
     * Where the bot waits outside every clear sweep before a press, in order: the centre structure ({@code center}: the
     * moss bridge at feet +6, up its stairs; also where the press is made from), then each clear actuator's standAt.
     * Only cells away from the sweeps; empty when there is none.
     */
    private List<Pos> sweepExits(Predicate<Pos> away) {
        List<Pos> out = new ArrayList<>();
        Pos c = feetAt(centerPos());
        if (away.test(c)) out.add(c);
        if (platform.clear != null) {
            for (ActuatorCfg a : platform.clear) {
                if (a == null || a.standAt == null) continue;
                Pos s = feetAt(a.standAt);
                if (away.test(s) && !out.contains(s)) out.add(s);
            }
        }
        return out;
    }

    /** The feet cell a configured stand position means: itself when it is open, else the cell above (the block stood on). */
    private Pos feetAt(Pos p) {
        WorldView w = bot.world();
        return !w.isLoaded(p) || w.block(p).isAir() || !w.block(p.above()).isAir() ? p : p.above();
    }

    /** Outside every configured platform's clear sweep ({@link StandSpots#awayFromClearSweeps}, as for the presses). */
    private Predicate<Pos> awayFromClearSweeps() {
        List<PlatformGeometry> gs = new ArrayList<>();
        for (PlatformCfg p : config.site.platforms) gs.add(p.geometry());
        return StandSpots.awayFromClearSweeps(gs);
    }

    /** Feet on this platform's art layer ({@link StandSpots#onArtLayer}: the cells a placement never stands in). */
    private boolean onArtLayer(Pos feet) {
        return StandSpots.onArtLayer(plan != null ? plan.geo : platform.geometry(), feet);
    }

    private Pos botFeet() {
        WorldView w = bot.world();
        return StandSpots.feetCell(w.playerX(), w.playerY(), w.playerZ());
    }

    private static String keys(List<Pos> ps) {
        List<String> out = new ArrayList<>();
        for (Pos p : ps) out.add(p.key());
        return String.join(", ", out);
    }

    private void stepClear() {
        Ctx c = ctx;
        List<ActuatorCfg> acts = platform.clear;
        if (acts == null || acts.isEmpty()) {
            block("platform " + platform.id + " has no clear actuator configured");
            return;
        }
        WorldView w = bot.world();
        if (!c.pressSent) {
            if (restartHolding) {
                // no new clear press until the restart is over: a flying machine cut off mid-way breaks
                uncountClear(c);
                if (holdCartsOff()) {
                    restartCartsOff = true;
                    return;
                }
                note = RESTART_NOTE + ": the clear waits (no press until the restart is over)";
                sleep(POLL_TICKS);
                return;
            }
            // the hold switched the carts off: on again for the clear's drops
            if (restartCartsOff && ensureCollection(true)) return;
            restartCartsOff = false;
        }
        if (!c.counted) {
            if (plan.allLoaded(w) && plan.survey(w).clear()) {
                go(afterClear());
                return;
            }
            if (c.clearActs == null && chooseClearActuators(c, acts)) return;
            c.counted = true;
            clearAttempts++;
            updateSprintGate(); // a retry: no sprint from its first walk on
            log.info("clear on {} (attempt {}): going to press {}", platform.id, clearAttempts, describe(c.clearActs));
            if (clearAttempts == 1) notifier.detail("Clearing", label() + ": piston clear on " + platform.id + ".");
        }
        while (c.actuator < c.clearActs.size()) {
            ActuatorCfg a = c.clearActs.get(c.actuator);
            boolean lever = "LEVER".equalsIgnoreCase(a.mode);
            // once a held lever is on, the bot waits and flips it back from where it pressed it, on time: no walk
            boolean holding = lever && a.holdTicks > 0 && c.leverStage != 0;
            if (!holding && leaveClearSweep("before pressing the clear")) return;
            if (!lever || a.holdTicks <= 0) {
                c.actuator++;
                run(bot.useBlock(a.pos, a.standAt), () -> pressedClear(a, "pressing clear"));
                return;
            }
            switch (c.leverStage) {
                case 0 -> {
                    c.leverStage = 1;
                    if (leverState(a.pos) == Boolean.TRUE) {
                        c.pressSent = true; // already on: the machines run
                        acted = true;
                        return;
                    }
                    run(bot.useBlock(a.pos, a.standAt), () -> {
                        pressedClear(a, "pressing clear (lever on, held " + a.holdTicks + " ticks)");
                        sleep(2);
                    });
                }
                case 1 -> {
                    c.leverStage = 2;
                    run(bot.waitTicks(a.holdTicks));
                }
                default -> {
                    c.leverStage = 0;
                    c.actuator++;
                    if (leverState(a.pos) == Boolean.FALSE) {
                        acted = true;
                        return;
                    }
                    run(bot.useBlock(a.pos, a.standAt), () -> pressedClear(a, "releasing the clear lever"));
                }
            }
            return;
        }
        go(Phase.WAIT_CLEAR);
    }

    /**
     * Logged when the press went out: the use task ends on the tick the block's state answered the click (one round
     * trip after it), the first moment the engine knows of it. The walk there came before (gate B logged
     * "pressing clear" when the step began, 2.4 s before the click).
     */
    private void pressedClear(ActuatorCfg a, String what) {
        ctx.pressSent = true; // the clear runs: a restart warning from now on lets it finish
        updateSprintGate(); // and no walk sprints until it is over
        log.info("{} on {} (attempt {}): {} {} clicked from {}", what, platform.id, clearAttempts, a.mode, a.pos.key(),
            botFeet().key());
    }

    /**
     * Picks the clear actuators whose side probe matches the world (moss: the flying machines are parked on that
     * button's side) into {@code c.clearActs}. True while walking/waiting for a probe chunk or after BLOCKED.
     */
    private boolean chooseClearActuators(Ctx c, List<ActuatorCfg> acts) {
        WorldView w = bot.world();
        List<ActuatorCfg> chosen = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (ActuatorCfg a : acts) {
            if (a == null || a.pos == null) continue;
            if (a.probe == null || a.probeBlock == null || a.probeBlock.isBlank()) {
                chosen.add(a);
                continue;
            }
            if (!w.isLoaded(a.probe)) {
                if (c.attempt("probe-walk") == 0) {
                    run(bot.walkTo(a.standAt != null ? a.standAt : a.pos, 2));
                    return true;
                }
                if (c.loadWaitTicks < LOAD_WAIT_TICKS) {
                    c.loadWaitTicks += POLL_TICKS;
                    sleep(POLL_TICKS);
                    return true;
                }
                block("the side probe " + a.probe.key() + " of the clear button at " + a.pos.key() + " is not loaded");
                return true;
            }
            BlockSpec at = w.block(a.probe);
            boolean match = catalog.matches(BlockSpec.parse(a.probeBlock), at);
            seen.add("button " + a.pos.key() + ": probe " + a.probe.key() + " is " + at.id() + (match ? "" : ", wants " + a.probeBlock));
            if (match) chosen.add(a);
        }
        if (chosen.isEmpty()) {
            block("no clear button applies on " + platform.id + " (" + String.join("; ", seen)
                + "). The flying machines may be stuck mid-way or broken; check them in game.");
            return true;
        }
        c.clearActs = chosen;
        return false;
    }

    private static String describe(List<ActuatorCfg> acts) {
        List<String> out = new ArrayList<>();
        for (ActuatorCfg a : acts) out.add(a.mode + " " + a.pos.key());
        return String.join(", ", out);
    }

    /** Status note prefix while the item collection runs on after the clear (the Discord presence looks for it). */
    public static final String COLLECTING_NOTE = "carts collecting";

    private void stepWaitClear() {
        clearMendAwaitsPoll = false;
        WorldView w = bot.world();
        boolean loaded = plan.allLoaded(w);
        if ((platform.stayDuringClear || !loaded) && !atClearStand() && ctx.attempt("walk") < 3) {
            run(bot.walkTo(centerPos(), 1));
            return;
        }
        Ctx c = ctx;
        if (!c.presenceLogged) {
            // the presence rule: where the bot waits against the server's simulation distance
            c.presenceLogged = true;
            Pos feet = botFeet();
            ClearPresence.Check pc = ClearPresence.check(platform.id, plan.geo, feet.x(), feet.z(),
                w.simulationDistance(), w.viewDistance());
            if (pc.covered()) log.info(pc.text());
            else log.warn(pc.text());
        }
        if (c.collecting != null || c.runOnCut) {
            if (restartHolding || c.runOnCut) restartEndsRunOn(c);
            else collectionRunOn(c, collectionItems());
            return;
        }
        if (loaded) {
            TilePlan.Survey s = plan.survey(w);
            if (s.clear()) {
                // WAIT_CLEAR starts right after the last press: the machines' time to empty the art layer, as seen
                // by the bot (polled, so up to a poll interval late)
                log.info("platform {} clear {} s after the clear was pressed (art layer read all air)", platform.id,
                    String.format(Locale.ROOT, "%.1f", (clock.millis() - phaseSince) / 1000.0));
                clearAttempts = 0;
                long cap = collectionRunOnMillis();
                if (cap <= 0) {
                    leaveAfterClear();
                    return;
                }
                if (restartHolding) {
                    restartEndsRunOn(c);
                    return;
                }
                c.collecting = new CollectionWait(clock.millis(), cap, Math.max(0, platform.collectionQuietSeconds) * 1000L);
                ItemCount items = collectionItems();
                if (items.why() == null) {
                    log.info("item collection on {} keeps running until no dropped items are left on the platform for {} s,"
                        + " at most {} s ({} now)", platform.id, Math.max(0, platform.collectionQuietSeconds), cap / 1000,
                        items.n().getAsInt());
                } else {
                    log.info("item collection on {} keeps running {} s for items in transit (no item count: {})",
                        platform.id, cap / 1000, items.why());
                }
                collectionRunOn(c, items);
                return;
            }
            note = s.nonAir() + " blocks left";
        }
        long elapsed = clock.millis() - phaseSince;
        if (elapsed >= platform.clearTimeoutSeconds * 1000L) {
            if (restartHolding && clearAttempts <= platform.clearRetries) {
                // the retry is a new press: it waits for the restart, the carts off meanwhile
                if (holdCartsOff()) {
                    restartCartsOff = true;
                    return;
                }
                note = RESTART_NOTE + ": the clear retry waits (no press until the restart is over)";
                sleep(POLL_TICKS);
                return;
            }
            if (clearAttempts <= platform.clearRetries) {
                log.warn("clear on {} timed out after {} s; retrying", platform.id, elapsed / 1000);
                notifier.info("Clear timed out", label() + ": platform " + platform.id + " not clear after "
                    + elapsed / 1000 + " s (" + note + "); pressing again.");
                go(Phase.CLEAR);
                return;
            }
            block("platform " + platform.id + " not clear after " + clearAttempts + " clear attempts (" + note + ")");
            return;
        }
        sleep(POLL_TICKS);
    }

    /**
     * WAIT_CLEAR once the art layer read clear: stays put with the item collection still ON (items the pistons pushed
     * off are still in transit) until no dropped item has been seen on the platform for
     * {@code collectionQuietSeconds}, at most {@code collectionRunAfterClearSeconds} ({@link CollectionWait}; the
     * whole cap when the items cannot be counted), then goes on; OFF follows at FINISH_TILE / GEAR as before. The clear
     * timeout no longer applies here. The wait lives in the phase context, so a restart, reconnect or pause during the
     * run-on simply starts it over.
     */
    private void collectionRunOn(Ctx c, ItemCount items) {
        CollectionWait.Step s = c.collecting.poll(clock.millis(), items.n());
        if (s.done()) {
            log.info(s.text());
            leaveAfterClear();
            return;
        }
        note = COLLECTING_NOTE + ", " + s.text();
        sleep(POLL_TICKS);
    }

    /** Dropped-item entities on the platform now, or why they cannot be counted ({@code n} empty). */
    private record ItemCount(OptionalInt n, String why) {
        static ItemCount none(String why) {
            return new ItemCount(OptionalInt.empty(), why);
        }
    }

    /**
     * Dropped-item entities in the collection volume: the tile's x/z, from floor - 2 to art + 3 (items on the floor,
     * the carts under it, items still bouncing). Items off the tile (the drop channel, the edges outside it) are not
     * counted: the carts under the tile may never reach them. Entities stream only within the server's entity tracking
     * range of the bot, never beyond its view distance, so the count is trusted only from the centre (where WAIT_CLEAR
     * keeps the bot with {@code stayDuringClear}) and only when the view distance reaches the tile's farthest corner
     * from there; without it the cap applies as a fixed run-on. The server's item tracking range itself (Paper default
     * 96 blocks, configurable) is unknown: a smaller one hides the far edge, where the sweep drops its last items.
     */
    private ItemCount collectionItems() {
        WorldView w = bot.world();
        if (!atClearStand()) return ItemCount.none("the bot is not at the center");
        PlatformGeometry g = plan.geo;
        double corner = 0;
        for (int x : new int[]{g.minX(), g.minX() + TileArt.SIZE})
            for (int z : new int[]{g.minZ(), g.minZ() + TileArt.SIZE})
                corner = Math.max(corner, Math.hypot(x - w.playerX(), z - w.playerZ()));
        int vd = w.viewDistance();
        if (vd * 16 < corner) {
            return ItemCount.none(String.format(Locale.ROOT, "the view distance (%d chunks, %d blocks) does not reach the"
                + " tile corners (%.0f blocks)", vd, vd * 16, corner));
        }
        OptionalInt n = w.itemEntities(new Pos(g.minX(), g.artY() - COLLECTION_BELOW, g.minZ()),
            new Pos(g.minX() + TileArt.SIZE - 1, g.artY() + COLLECTION_ABOVE, g.minZ() + TileArt.SIZE - 1));
        return n.isPresent() ? new ItemCount(n, null) : ItemCount.none("no entity data for the platform");
    }

    /** Run-on after the clear in ms; 0 when the platform has no collection to switch off, or the setting is 0. */
    private long collectionRunOnMillis() {
        ActuatorCfg off = platform.collectionOff;
        if (off == null || off.pos == null) return 0;
        return Math.max(0, platform.collectionRunAfterClearSeconds) * 1000L;
    }

    /**
     * The clear is over: with {@code hopDownTo} (soul sand) the bot drops from the centre structure to the floor first
     * (the owner 2026-10-11: "it needs to stay in the center while the platform clears, and then can simply hop down from
     * where it is onto the soulsand"), when {@link RaisedStand#hop} allows it (on the structure, health at least
     * {@code hopDownMinHealth}); otherwise, or when the drop fails, the next walk takes the walkway. Then
     * {@link #afterClear}. Once per phase entry.
     */
    private void leaveAfterClear() {
        if (platform.hopDownTo != null && ctx.attempt("hop") == 0) {
            WorldView w = bot.world();
            float hp = bot.inventory().health();
            String why = RaisedStand.hop(platform, w.playerX(), w.playerY(), w.playerZ(), hp);
            if (why == null) {
                int drop = RaisedStand.drop(platform);
                log.info("hopping down from the center of {} to {}: {} blocks, about {} HP before armor (health {})",
                    platform.id, platform.hopDownTo.key(), drop, RaisedStand.fallDamage(drop, 0.125), Math.round(hp));
                note = "hopping down to the floor";
                run(bot.hopDown(platform.hopDownTo, drop), () -> go(afterClear()), r -> {
                    log.warn("hop down on {} to {} failed: {}; walking down instead", platform.id,
                        platform.hopDownTo.key(), r);
                    go(afterClear());
                });
                return;
            }
            log.info("not hopping down on {}: {}; walking down instead", platform.id, why);
        }
        go(afterClear());
    }

    /** Hops down from a raised centre that failed in a row (reset by a hop that worked); at the limit BLOCKED. */
    private int raisedHopFailures;
    private static final int RAISED_HOP_FAILURES = 3;

    /**
     * Between two tasks, outside the clear: a bot standing on a platform's raised centre structure with
     * {@code hopDownTo} (soul sand: the cross, whose walkway is one-way) hops down to the floor first, since no walk
     * leads off it (local test 2026-10-11: ZenithProxy's pathfinder finds "No path" from the cross). With health below
     * {@code hopDownMinHealth} it stands there instead (eating is {@code tendVitals}'s, run before this; health comes
     * back by itself with food in the bar) and hops once health allows. After the clear WAIT_CLEAR hops itself
     * ({@link #leaveAfterClear}). True while it hops or waits.
     */
    private boolean leaveRaisedStand() {
        // NUKE: its last walk goes up to the centre for the clear; it hops down itself before breaking (hopFrom)
        if (phase == Phase.IDLE || phase == Phase.SELECT || phase == Phase.NUKE || phase == Phase.CLEAR
            || phase == Phase.WAIT_CLEAR || phase == Phase.BLOCKED) return false;
        for (PlatformCfg p : config.site.platforms) if (p != null && p.enabled && hopFrom(p)) return true;
        return false;
    }

    /** {@link #leaveRaisedStand} for one platform: true while it hops down from {@code p}'s structure or waits to. */
    private boolean hopFrom(PlatformCfg p) {
        WorldView w = bot.world();
        if (p.hopDownTo == null || !RaisedStand.onStructure(p, w.playerX(), w.playerY(), w.playerZ())) return false;
        float hp = bot.inventory().health();
        String why = RaisedStand.hop(p, w.playerX(), w.playerY(), w.playerZ(), hp);
        if (why != null) {
            // health comes back only with the bar at 18 or more: top it up while waiting (local 10x: 20 -> 17.5 -> 14.9
            // over two maps with the bar between the eating level and 18)
            InventoryView inv = bot.inventory();
            if (hp < p.hopDownMinHealth && inv.foodLevel() < 20 && foodCarried(inv) > 0 && engineEats()
                && eat(inv, inv.foodLevel(), hp, false)) return true;
            note = "on the center of " + p.id + ": " + why + "; waiting to hop down";
            warnOnce("hop-wait-" + p.id, "on the raised center of " + p.id + " in " + phase + ": " + why
                + "; standing still until it can hop down to " + p.hopDownTo.key());
            sleep(HOP_WAIT_TICKS);
            return true;
        }
        int drop = RaisedStand.drop(p);
        log.info("on the raised center of {} in {}: hopping down to {} ({} blocks, about {} HP before armor, health {})",
            p.id, phase, p.hopDownTo.key(), drop, RaisedStand.fallDamage(drop, 0.125), Math.round(hp));
        note = "hopping down to the floor";
        run(bot.hopDown(p.hopDownTo, drop), () -> raisedHopFailures = 0, r -> {
            raisedHopFailures++;
            log.warn("hop down on {} to {} failed ({} in a row): {}", p.id, p.hopDownTo.key(), raisedHopFailures, r);
            if (raisedHopFailures >= RAISED_HOP_FAILURES) {
                raisedHopFailures = 0;
                block("stuck on the raised center of " + p.id + ": " + RAISED_HOP_FAILURES + " hops down to "
                    + p.hopDownTo.key() + " failed (last: " + r + "). Check the floor below the cross in game");
            }
        });
        return true;
    }

    /** Re-check interval while the bot waits on a raised centre for health to hop down. */
    private static final int HOP_WAIT_TICKS = 40;

    private Phase afterClear() {
        return tile.status == TileStatus.STORED || aborting ? Phase.FINISH_TILE : Phase.INSPECT;
    }

    private void stepFinishTile() {
        if (ensureCollection(false)) return;
        if (aborting) {
            log.info("cancelled {} cleaned up", label());
            notifier.info("Cancelled job cleaned up", label() + (suppressionTile()
                ? ": the blocks on color-suppression platform " + platform.id + " stay (the bot never clears it)."
                : ": platform " + platform.id + " cleared."));
            forgetTileStart();
            endUnit("job_cancelled");
            clearCurrent();
            go(Phase.SELECT);
            return;
        }
        if (tile.finishedAt == 0) tile.finishedAt = clock.millis();
        tile.status = TileStatus.STORED;
        String carried = carriedOver && carryNext != null ? markCarried(carryNext) : null;
        if (unit != null) coord.done(unit, tileProgress(), carriedOver && carryNext != null && carryNext.tile() == null
            ? carryNext.key() : null);
        unit = null; // finished: nothing left to give back
        boolean allStored = true;
        for (Job.TileState t : job.tiles) allStored &= t.status == TileStatus.STORED;
        if (allStored) completeJob(job);
        else {
            saveJob(job);
            notifier.detail("Tile done", label() + "; next map of " + job.name + "."
                + (carried != null ? " " + carried : ""));
        }
        clearCurrent();
        go(Phase.SELECT);
    }

    // =====================================================================================
    // COLOUR SUPPRESSION: the ride (docs/COLOR_SUPPRESSION.md)
    // =====================================================================================

    /** Status note while the bot rides the muncher. */
    public static final String RIDE_NOTE = "riding the muncher";
    /** Status note while the pre-ride machine guard waits for machines still on their way home. */
    public static final String GUARD_WAIT_NOTE = "waiting for the machines to come home";
    /** Status note while RIDE_RESET waits for the layers to empty and the machines to come home. */
    public static final String RESET_WAIT_NOTE = "waiting for the muncher to reset";
    /** Minecart item. */
    static final String CART = "minecraft:minecart";
    /** Cells the machine guard and the stasis scan read per engine tick (the client tick thread). */
    static final int GUARD_BUDGET = 8000;
    /** RIDE_END with ride.reuseCart: the broken cart's item is looked for this often, this many times. */
    static final int CART_PICKUP_POLL_TICKS = 10;
    static final int CART_PICKUP_POLLS = 10;
    /** RIDE_RESET looks again this often (ticks). */
    static final int SETTLE_POLL_TICKS = 400;

    /** The current tile is the last pass of a colour-suppression map and its platform rides the muncher itself. */
    private boolean rideTile() {
        return suppressionTile() && platform.ride != null && platform.ride.configured()
            && tile.x == job.suppression.size() - 1;
    }

    private MapArtConfig.RideCfg ride() {
        return platform.ride;
    }

    /** Phases of the ride tile that look at the map's colours: the art must be loaded first. */
    private static boolean rideMapPhase(Phase p) {
        return switch (p) {
            case FILL_MAP, CHECK_MAP, RIDE_BOARD, RIDE_PRELOAD, RIDE, RIDE_END, LOCK, RENAME, STORE -> true;
            default -> false;
        };
    }

    /** Loads {@link #csArt} for the current job on the executor. True while it loads. */
    private boolean awaitCsArt() {
        if (csArt != null && job.id.equals(csArtJob)) return false;
        Job j = job;
        run(new AsyncTask<>("load the color-suppression art", executor, () -> loadCsArt(j), a -> {
            csArt = a;
            csArtJob = j.id;
            expected = null;
        }), null, r -> stepFailed("could not load the color-suppression art: " + r));
        return true;
    }

    /** Executor thread: the art rebuilt from the job's stored passes. */
    private io.github.digitalisp.mapart.model.SuppressionArt loadCsArt(Job j) throws IOException {
        List<io.github.digitalisp.mapart.model.SuppressionArt.Pass> passes = new ArrayList<>();
        for (int i = 0; i < j.suppression.size(); i++) {
            Job.SuppressionPass sp = j.suppression.get(i);
            passes.add(new io.github.digitalisp.mapart.model.SuppressionArt.Pass(sp.layer, sp.level, sp.noobline,
                store.loadTile(j.id, new TileCoord(i, 0))));
        }
        return io.github.digitalisp.mapart.model.SuppressionArt.fromPasses(passes);
    }

    /**
     * {@link #expected} for a colour-suppression map ({@link io.github.digitalisp.mapart.render.SuppressionMaps}): what the map
     * shows after the fill (both layers), or with {@code afterRide} the finished map. False until the art is loaded.
     */
    private boolean csExpectedReady(boolean afterRide) {
        if (csArt == null || job == null || !job.id.equals(csArtJob)) return false;
        if (expected == null || csExpectedFinal != afterRide) {
            byte[] packed = afterRide ? io.github.digitalisp.mapart.render.SuppressionMaps.expected(csArt, catalog)
                : io.github.digitalisp.mapart.render.SuppressionMaps.afterFill(csArt, catalog);
            boolean[] check = new boolean[packed.length];
            for (int i = 0; i < packed.length; i++) check[i] = packed[i] != 0;
            expected = new TilePlan.ExpectedMap(packed, check);
            csExpectedFinal = afterRide;
        }
        return true;
    }

    /** The ride's map phase is past the ride (the finished map is expected), counting a BLOCKED one by where it was. */
    private boolean pastRide() {
        Phase p = phase == Phase.BLOCKED && blockedFrom != null ? blockedFrom : phase;
        return p.ordinal() >= Phase.RIDE_END.ordinal() && p != Phase.BLOCKED;
    }

    /**
     * FILL_MAP of the ride tile: the kit for the ride first (an empty map, a glass pane for the lock in the booth, a
     * minecart, an XP level for the rename, a full hunger bar), then the map made and held at the top-layer centre with
     * both layers in place. Every pixel is written then; the ride later writes only the dominant ones again.
     */
    private void stepCsFill() {
        if (fillAttempts >= config.map.maxFillAttempts) {
            block("the map did not show the art after " + fillAttempts + " fills");
            return;
        }
        if (awaitCsArt()) return;
        InventoryView inv = bot.inventory();
        boolean again = tileUnlockedMap() != null;
        if (rideKit()) return;
        if (engineEats() && inv.foodLevel() < 20 && foodCarried(inv) > 0 && ctx.attempt("ride-meal") < 3) {
            log.info("eating before the ride (hunger {}/20)", inv.foodLevel());
            run(bot.eat(foodIds()));
            return;
        }
        if (climbToPlatform()) return;
        if (again) {
            int id = mapId;
            log.info("holding map {} again at the center (fill {})", id, fillAttempts + 1);
            run(bot.holdMap(mapStandPos(), id, config.map.holdTicks), () -> {
                fillAttempts++;
                go(Phase.CHECK_MAP);
            });
            return;
        }
        log.info("filling the color-suppression map at {} (fill {})", mapStandPos(), fillAttempts + 1);
        run(bot.createMap(mapStandPos(), config.map.holdTicks, this::madeMap), () -> {
            fillAttempts++;
            go(Phase.CHECK_MAP);
        });
    }

    /** The current tile is any pass of a colour-suppression job whose platform rides the muncher itself. */
    private boolean rideJob() {
        return suppressionTile() && platform.ride != null && platform.ride.configured();
    }

    /**
     * The ride's kit in the inventory (GEAR of every pass, and FILL_MAP): an empty map unless the tile's unlocked map is
     * carried, a glass pane for the lock in the booth, a minecart, an XP level for the rename. True while fetching (or
     * blocked when a chest list has none).
     */
    private boolean rideKit() {
        MapArtConfig.RideCfg r = ride();
        InventoryView inv = bot.inventory();
        MapArtConfig.StorageCfg st = config.site.storage;
        boolean mapCarried = inv.count(MAP) > 0 || (phase != Phase.GEAR && tileUnlockedMap() != null);
        if (!mapCarried) {
            if (!withdrawOne(st.mapChests, MAP, "map")) block("no empty map for the ride: the map chests are empty");
            return true;
        }
        if (inv.count(PANE) < 1) {
            if (!withdrawOne(st.paneChests, PANE, "pane")) block("no glass pane for the lock in the booth: the pane chests are empty");
            return true;
        }
        // the XP chests are with the other supplies by the base; the cart chests may be up by the platform: XP first
        if (config.map.rename && (inv.xpLevel() < 1 || bootsNeedMendBeforeRide(inv)) && rideXp()) return true;
        // ride.reuseCart: the platform's own cart stands on the start rail; a spare is carried only once that one is lost
        boolean lost = r.reuseCart && job != null && job.cartLost;
        if ((!r.reuseCart || lost) && inv.count(CART) < 1) {
            if (!withdrawOne(r.cartChests, CART, "minecart")) {
                String why = "no minecart for the ride: " + (r.cartChests.isEmpty() ? "ride.cartChests is not configured"
                    : "none came from ride.cartChests (empty or out of reach, see the log)");
                if (lost) {
                    // not BLOCKED here: the print goes on, and a cart someone put on the start rail meanwhile is
                    // boarded as it is. Boarding stops the ride if there is still no cart then
                    spareFetchTried = true;
                    warnOnce("spare-cart", label() + ": " + why + ". The platform's cart was lost: put a minecart on the"
                        + " start rail or into the cart chest before the ride.");
                    return false;
                }
                block(why);
            }
            return true;
        }
        return false;
    }

    /**
     * {@code Job.cartLost}: the cart chests were asked for a spare and gave none (or RIDE_BOARD sent the bot for one)
     * since the last resume, restart or boarding. RIDE_BOARD then boards what stands on the start rail instead of
     * going for a spare again.
     */
    private boolean spareFetchTried;

    /** {@code ride.reuseCart}: the platform's own cart is gone; the ride's kit takes a spare from {@code ride.cartChests}. */
    private void cartLost(String what) {
        job.cartLost = true;
        saveJob(job);
        log.warn("{}: {}: a spare is taken from ride.cartChests before the next ride", label(), what);
        alert("Minecart lost", label() + ": " + what + ". A spare is taken from the cart chests before the next ride.");
    }

    /**
     * Worn boots below {@link #BOOTS_MIN_DURABILITY} after the print (a CS map is hours on the layers): mended before the
     * ride from the XP bottles the kit carries, instead of by RENAME's trip to the supply column (about 200 blocks and the
     * stair each way, from the booth; local CS run 2026-10-14). {@code build.suppressionMendBeforeRide}, default on.
     */
    private boolean bootsNeedMendBeforeRide(InventoryView inv) {
        return config.build.suppressionMendBeforeRide && (platform.soulSpeedBoots || config.build.wearBoots)
            && isBoots(inv.bootsItem()) && inv.bootsDurability() < BOOTS_MIN_DURABILITY;
    }

    /** XP for the rename in the booth, fetched before the ride (the XP chests are far from the booth). True while acting. */
    private boolean rideXp() {
        InventoryView inv = bot.inventory();
        int bottles = inv.count(XP_BOTTLE);
        if (bottles > 0) {
            if (ctx.attempt("ride-xp-throw") >= 6) {
                if (inv.xpLevel() >= 1) return false; // mending the boots: good enough, RENAME mends what is left
                block("thrown XP bottles six times before the ride but the level is still 0");
                return true;
            }
            run(bot.throwXp(Math.min(bottles, XP_THROW_CHUNK)));
            return true;
        }
        List<Pos> chests = config.site.storage.xpChests;
        int a = ctx.attempt("ride-xp");
        if (a >= chests.size()) {
            if (inv.xpLevel() >= 1) return false; // mending the boots only
            block("no XP for the rename in the booth: the XP chests are empty");
            return true;
        }
        run(bot.withdraw(chests.get(a), Map.of(XP_BOTTLE, Math.max(1, config.map.xpBottlesPerTrip)), r -> { }));
        return true;
    }

    /** CHECK_MAP of the ride tile: the held map must show exactly what the fill writes (both layers). */
    private void stepCsCheckMap() {
        if (awaitCsArt()) return;
        Integer id = currentUnlockedMap();
        if (id == null) {
            go(Phase.FILL_MAP);
            return;
        }
        Optional<byte[]> data = bot.maps().colors(id);
        if (data.isEmpty()) {
            if (ctx.attempt("hold") < MISSING_DATA_HOLDS) {
                note = MAP_DATA_NOTE + " for map " + id;
                run(bot.holdMap(mapStandPos(), id, config.map.holdTicks));
                return;
            }
            missingMapData(id);
            return;
        }
        if (note != null && note.startsWith(MAP_DATA_NOTE)) note = "";
        csExpectedReady(false);
        byte[] actual = data.get().clone();
        int bad = expected.mismatches(actual);
        if (bad == 0) {
            log.info("map {} shows both layers as filled; ready to ride", id);
            go(Phase.RIDE_BOARD);
            return;
        }
        TilePlan.ExpectedMap exp = expected;
        log.warn("map {} has {} pixels off the filled art (fill {}/{}): {}", id, bad, fillAttempts,
            config.map.maxFillAttempts, exp.sample(actual, 12));
        renderAsync(() -> renderer.mapDiff(actual, exp.forDiff(actual), colors, RENDER_SCALE), "Map mismatch: " + job.name,
            label() + ": " + bad + " pixels differ from the filled art (fill " + fillAttempts + "/"
                + config.map.maxFillAttempts + ").", "mapdiff-" + job.id + "-fill.png");
        if (fillAttempts >= config.map.maxFillAttempts) {
            block("the filled map shows " + bad + " pixels off the art after " + fillAttempts + " fills (a wrong block on a"
                + " layer?); the ride is not started");
            return;
        }
        go(Phase.FILL_MAP);
    }

    /**
     * RIDE_BOARD: the machine guard's reading against its reference (recorded now on the platform's first ride) and the
     * stasis pearls, both from the viewpoints; then a cart on the start rail and the bot in it.
     */
    private void stepRideBoard() {
        MapArtConfig.RideCfg r = ride();
        MapArtConfig.PreloadCfg pre = preload();
        if (bot.world().vehicle() != null) {
            go(inPreloadCart() ? Phase.RIDE_PRELOAD : Phase.RIDE);
            return;
        }
        Integer id = currentUnlockedMap();
        if (id == null) {
            block("the filled map is not in the inventory before the ride");
            return;
        }
        if (tile.ridePause != null) {
            if (tile.ridePauseStage < RP_RELOAD) go(Phase.RIDE); // still at the stand: RIDE's
            else ridePauseBoard(r, pre);
            return;
        }
        // ride.reuseCart with the platform's cart lost: one more look into the cart chests per resume (a spare put there
        // after the kit found none), else what stands on the start rail is boarded
        boolean spare = r.reuseCart && job.cartLost && !spareFetchTried && !r.cartChests.isEmpty();
        if ((!r.reuseCart || spare) && bot.inventory().count(CART) < 1 && ctx.attempt("cart-check") == 0) {
            if (spare) spareFetchTried = true;
            go(Phase.FILL_MAP); // fetches a cart and holds the map again
            return;
        }
        if (restartHolding) {
            // a server restart is due: a ride cut by the restart leaves the machines mid-cycle; it starts afterwards
            note = "server restart due: the ride waits";
            sleep(SETTLE_POLL_TICKS);
            return;
        }
        Ctx c = ctx;
        long rideMs = (rideExpectedSeconds() + RIDE_RESTART_MARGIN_S) * 1000L;
        if (restartInsideRide(clock.millis(), lastServerRestartAt, config.site.restartEveryMinutes, rideMs)) {
            // the scheduled restart would come mid-ride (live 2026-11-14: the map lost and the muncher stalled): the
            // ride starts after it; both layers and the filled map keep
            long min = Math.max(0, (lastServerRestartAt + config.site.restartEveryMinutes * 60_000L - clock.millis()) / 60_000);
            note = RESTART_EXPECTED_NOTE + " in about " + min + " min: the ride waits for it";
            if (!c.restartWaitLogged) {
                c.restartWaitLogged = true;
                log.info("{}: the scheduled server restart is expected in about {} min, inside the ride ({} min with its"
                    + " checks and a margin): the ride starts after it", label(), min, rideMs / 60_000);
                notifier.detail("Ride waits for the restart", label() + ": 2b2t's scheduled restart is expected in about "
                    + min + " min, inside the ride. The ride starts after it; the print and the filled map keep.");
            }
            sleep(SETTLE_POLL_TICKS);
            return;
        }
        // a fresh preload lap was ridden right after these checks passed (same connection): straight into the main cart
        boolean lapFresh = pre != null && preloadFresh();
        if (!c.preChecked && !lapFresh) {
            // the guard differed at the last look: read it again from where its differing chunks are in view
            if (c.guardSpot != null && !guardReread()) return;
            Reading rd = viewpointReading(true);
            if (rd == null) return;
            if (!preRideChecks(rd)) return;
            c.preChecked = true;
        }
        if (pre != null && !lapFresh) {
            preloadCleared = job.id;
            go(Phase.RIDE_PRELOAD);
            return;
        }
        log.info("boarding the cart on {} (map {}){}", r.startRail.key(), id, pre == null ? "" : "; the preload lap ended "
            + (clock.millis() - preloadedAt) / 1000 + " s ago");
        run(bot.boardCart(r.startRail, r.startStand, cid -> log.info("in minecart {}", cid)), () -> {
            rideReboards = 0;
            tile.ridePressed = false;
            job.cartLost = false; // a cart is on the track again
            spareFetchTried = false;
            saveJob(job);
            preloadCleared = null;
            preloadedJob = null;
            go(Phase.RIDE);
        }, reason -> {
            // ride.reuseCart with no cart on the start rail and none carried (the platform's own cart was lost at an
            // earlier ride's end): a spare from the cart chests, once. FILL_MAP's kit fetches it and holds the map again
            if (r.reuseCart && !job.cartLost && !r.cartChests.isEmpty() && reason != null
                && reason.contains(BotActions.NO_CART_CARRIED)) {
                cartLost("no minecart stands on the start rail " + r.startRail.key() + " and none is carried");
                spareFetchTried = true;
                go(Phase.FILL_MAP);
                return;
            }
            stepFailed(reason);
        });
    }

    // ---- the preload lap (ride.preload, docs/COLOR_SUPPRESSION.md) ----

    /** Status note while the bot rides the preload lap. */
    public static final String PRELOAD_NOTE = "riding the preload lap";
    /** Job whose pre-ride checks passed on this connection with the lap still to ride; null after a disconnect. */
    private String preloadCleared;
    /** Job whose preload lap ended on this connection at {@link #preloadedAt}; null after a disconnect or the boarding. */
    private String preloadedJob;
    private long preloadedAt;

    /** The platform's preload lap, or null when none is configured. */
    private MapArtConfig.PreloadCfg preload() {
        MapArtConfig.RideCfg r = ride();
        return r != null && r.preloads() ? r.preload : null;
    }

    /**
     * The preload lap of this job counts for the boarding now: ridden on this connection (a restart of the plugin or
     * a disconnect forgets it) and ended at most {@code preload.validMinutes} ago.
     */
    private boolean preloadFresh() {
        MapArtConfig.PreloadCfg pre = preload();
        if (pre == null || job == null || !job.id.equals(preloadedJob)) return false;
        return pre.validMinutes <= 0 || clock.millis() - preloadedAt <= pre.validMinutes * 60_000L;
    }

    /** The cart the bot sits in is the preload lap's: its launch is on record, or it stands on the lap's start rail. */
    private boolean inPreloadCart() {
        MapArtConfig.PreloadCfg pre = preload();
        WorldView.Thing v = bot.world().vehicle();
        // (a paused ride's lap, after the main launch: ridePauseBoard)
        if (pre == null || v == null || (tile.ridePressed && tile.ridePause == null)) return false;
        return tile.preloadPressed || (v.horizontalDistSq(pre.startRail.x() + 0.5, pre.startRail.z() + 0.5) <= 1.5 * 1.5
            && Math.abs(v.y() - pre.startRail.y()) <= 1.5);
    }

    private static boolean within(Pos a, Pos b, int horizontal, int vertical) {
        return Math.abs(a.x() - b.x()) <= horizontal && Math.abs(a.z() - b.z()) <= horizontal
            && Math.abs(a.y() - b.y()) <= vertical;
    }

    /**
     * RIDE_PRELOAD, entered from RIDE_BOARD once the pre-ride checks passed: into the lap's own cart on its start rail
     * (never placed, never broken), the lap's button from the seat, the lap with the ride's watch rules and no filled
     * map in the hand, out of the cart at the lap's end, then back to RIDE_BOARD, which boards the main cart at once.
     * Any failure is BLOCKED (no retry) and nothing of the ride is started. After a restart: in the cart with the
     * launch on record the lap is ridden on without a second press; out of the cart the checks run again first
     * (RIDE_BOARD), and with the launch on record away from the lap's start it is BLOCKED.
     */
    private void stepRidePreload() {
        MapArtConfig.RideCfg r = ride();
        MapArtConfig.PreloadCfg pre = preload();
        if (pre == null) {
            go(Phase.RIDE_BOARD);
            return;
        }
        Ctx c = ctx;
        Pos end = pre.endRail();
        WorldView.Thing v = bot.world().vehicle();
        if (v == null) {
            Pos feet = botFeet();
            if (c.lapDone) {
                if (!within(feet, end, 4, 3)) {
                    block("out of the preload cart at " + feet.key() + ", not by its rail " + end.key()
                        + ": the ride is not started");
                    return;
                }
                if (currentUnlockedMap() == null) {
                    block("the filled map is not in the inventory after the preload lap; the ride is not started");
                    return;
                }
                go(Phase.RIDE_BOARD);
                return;
            }
            if (tile.preloadPressed) {
                if (!within(feet, pre.startStand, 6, 3) && within(feet, pre.startStand, 32, 3) && !c.lapWalkBack) {
                    // out of the lap's cart on the track near its start (a restart mid-lap puts the bot off at the
                    // cart): back to the start on foot, once, then the lap is ridden again. Live 2026-11-24 05:00,
                    // j0040 shrimp [6,0]: a deploy in the lap left the bot 12 blocks along the track, BLOCKED
                    c.lapWalkBack = true;
                    log.warn("out of the preload cart at {} after its launch, {} from its start {}: walking back to it,"
                        + " then the lap again", feet.key(), String.format(Locale.ROOT, "%.0f",
                            Math.hypot(feet.x() - pre.startStand.x(), feet.z() - pre.startStand.z())),
                        pre.startStand.key());
                    note = "walking back to the preload lap's start";
                    run(bot.walkTo(pre.startStand, 1));
                    return;
                }
                if (!within(feet, pre.startStand, 6, 3)) {
                    block("not in the cart after the preload lap's launch (a restart or a reconnect mid-lap?): the bot is at "
                        + feet.key() + ", not by the lap's start " + pre.startStand.key() + ". The cart comes back to its"
                        + " start rail by itself; bring the bot back to the start, then resume: the checks and the lap run"
                        + " again. The ride is not started");
                    return;
                }
                log.warn("the preload lap was launched but the bot is out of the cart by its start: the lap is ridden again");
                tile.preloadPressed = false;
                saveJob(job);
            }
            if (preloadFresh() || !job.id.equals(preloadCleared) || restartHolding) {
                // lap ridden already, or the pre-ride checks (and the restart hold) come first: RIDE_BOARD decides
                go(Phase.RIDE_BOARD);
                return;
            }
            if (c.preloadSpare) {
                // the lap's own cart is not on its start rail: a spare from the cart chests is put there and boarded
                // (it stays on the lap's loop as its cart). Live 2026-11-24 03:08, j0040 shrimp [6,0]: the first ride
                // after 5 days of soul sand, no cart on the preload rail, BLOCKED
                if (bot.inventory().count(CART) < 1) {
                    if (withdrawOne(r.cartChests, CART, "preload minecart")) return;
                    block("the preload lap did not start: no minecart stands on its start rail " + pre.startRail.key()
                        + " and none came from ride.cartChests. Put a minecart on that rail or into the cart chest,"
                        + " then resume. Nothing was pressed; the ride is not started.");
                    return;
                }
                log.info("preload lap: putting a spare minecart on {} and boarding it", pre.startRail.key());
                run(bot.boardCart(pre.startRail, pre.startStand, cid -> log.info("in the preload minecart {}", cid)), null,
                    reason -> block("the preload lap did not start: " + reason + ". Nothing was pressed; the ride is"
                        + " not started."));
                return;
            }
            log.info("preload lap: boarding the cart on {}", pre.startRail.key());
            run(bot.boardOwnCart(pre.startRail, pre.startStand, cid -> log.info("in the preload minecart {}", cid)), null,
                reason -> {
                    if (reason != null && reason.contains("no minecart stands on the rail")
                        && (bot.inventory().count(CART) > 0 || !r.cartChests.isEmpty())) {
                        log.warn("preload lap: no minecart on its start rail {}: a carried one or a spare from ride.cartChests goes there",
                            pre.startRail.key());
                        c.preloadSpare = true;
                        return;
                    }
                    block("the preload lap did not start: " + reason + ". Nothing was pressed; the ride is not"
                        + " started.");
                });
            return;
        }
        if (tile.ridePressed && tile.ridePause == null) {
            go(Phase.RIDE); // the main cart after its launch: never the lap's
            return;
        }
        if (c.lapDone || preloadFresh()) {
            // the lap ended (also when a pause forgot this phase's state in between): out, never a second lap
            c.lapDone = true;
            run(bot.leaveCart(), null, reason -> block("getting out of the preload cart failed: " + reason
                + ". The ride is not started."));
            return;
        }
        boolean press = !tile.preloadPressed;
        RideWatch watch = new RideWatch(pre.stallMinutes * 1200, pre.maxMinutes * 1200, r.minHealth, r.swingSeconds * 20);
        note = PRELOAD_NOTE;
        log.info("preload lap{}", press ? ": launch button first" : " (launched before)");
        notifier.detail("Preload lap started", label() + ": riding the preload track before the muncher ride.");
        run(bot.preloadRide(press ? pre.button : null, end, pre.pushYaw, watch, () -> {
            tile.preloadPressed = true;
            saveJob(job);
        }), () -> {
            note = "";
            tile.preloadPressed = false;
            tile.preloadLaps++;
            saveJob(job);
            ctx.lapDone = true;
            if (job.id.equals(preloadCleared)) {
                preloadedJob = job.id;
                preloadedAt = clock.millis();
                log.info("the preload lap ended at {} (lap {} of this map)", end.key(), tile.preloadLaps);
            } else {
                // ridden on after a restart: this connection's checks did not come before it, so it does not count
                log.warn("the preload lap ended at {}, but it was launched before a restart: the checks and the lap run again",
                    end.key());
            }
        }, reason -> block("the preload lap stopped: " + reason + ". The bot stays where it is and the ride is not"
            + " started."));
    }

    /** The guard and pearl verdicts on a full pre-ride reading. True when the ride may start (false: blocked). */
    private boolean preRideChecks(Reading rd) {
        MapArtConfig.RideCfg r = ride();
        String pearls = pearlProblem(rd);
        if (pearls != null) {
            block("stasis chunk loaders: " + pearls + ". Without them the machine chunks unload during the ride (2b2t has"
                + " no forceload) and the flying machines break. The ride is not started.");
            return false;
        }
        if (r.machine.isEmpty()) return true;
        int total = guardChunksTotal();
        if (rd.machine.chunks() < total) {
            List<String> missing = new ArrayList<>();
            for (long k : boxChunks(r.machine))
                if (!rd.machine.chunkKeys().contains(k) && missing.size() < 3)
                    missing.add("x " + ((int) (k >> 32) << 4) + ", z " + ((int) k << 4));
            block("machine guard: only " + rd.machine.chunks() + " of " + total + " machine chunks came into view from the"
                + " viewpoints " + viewpoints(r.startStand) + " (not seen: the chunk at " + String.join("; ", missing)
                + "); add a viewpoint (ride.viewpoints) that sees the rest");
            return false;
        }
        MachineGuard.Snapshot ref = guardRef();
        if (ref == null) {
            guardReference.put(platform.id, rd.machine);
            saveGuardRef(rd.machine);
            log.info("machine guard reference recorded for {}: {} cells in {} chunks", platform.id, rd.machine.cells(),
                rd.machine.chunks());
            notifier.info("Machine reference recorded", label() + ": the muncher's machinery on " + platform.id + " ("
                + String.format(Locale.ROOT, "%,d", rd.machine.cells()) + " blocks) is the reference every ride is"
                + " compared with from now on.");
            return true;
        }
        MachineGuard.Diff d = MachineGuard.compare(ref, rd.machine, r.machine);
        if (!d.clean() || d.chunksNotSeen() > 0) {
            // machines of the last ride may still be on their way home (the job no longer waits for the reset,
            // ride.waitForReset): read again while the difference keeps changing (guardDiffers)
            if (!guardDiffers(d)) return false;
            // the next look: from the nearest viewpoint that had every differing chunk in view, without walking the
            // others again; none (or one that failed before): the whole walk, as before
            Ctx c = ctx;
            c.guardBase = rd;
            c.guardChunks = d.chunks();
            c.guardSpot = c.guardSpotFailed ? null : guardSpot(rd, d.chunks());
            c.guardSpotWalked = false;
            return false;
        }
        log.info("machine guard before the ride: {} chunks as recorded", d.chunksCompared());
        return true;
    }

    /**
     * The pre-ride guard differs from its reference ({@code d}): the wait goes on while the difference keeps changing
     * between readings (machines of the last ride on their way home), for up to {@code ride.settleMinutes}. The same
     * difference (the same cells, the same blocks: {@link MachineGuard.Diff#signature}) in consecutive readings for
     * {@code ride.guardConfirmMinutes} is not machines moving: BLOCKED at once (live 2026-11-14: 35 minutes on a
     * cosmetic change of 64 cells that never changed). True while waiting (the next look is due after the sleep);
     * false once BLOCKED.
     */
    private boolean guardDiffers(MachineGuard.Diff d) {
        MapArtConfig.RideCfg r = ride();
        Ctx c = ctx;
        if (c.guardWaitSince < 0) c.guardWaitSince = tick;
        boolean same = c.guardReads > 0 && d.signature() == c.guardSignature;
        if (!same) {
            c.guardSignature = d.signature();
            c.guardSameSince = tick;
            // every differing cell, for the log only (real positions): what the summary's one example leaves open
            if (d.cellsDiffer() > 0) log.info("machine guard before the ride: differing cells ({} of {}): {}",
                d.sample().size(), d.cellsDiffer(), d.cells());
        }
        c.guardReads++;
        boolean unchanged = same && tick - c.guardSameSince >= Math.max(0, r.guardConfirmMinutes) * 1200L;
        if (!unchanged && tick - c.guardWaitSince < r.settleMinutes * 1200L) {
            note = GUARD_WAIT_NOTE + " before the ride: " + d.summary();
            if (c.settleLogged++ % 6 == 0) log.info("{}", note);
            sleep(SETTLE_POLL_TICKS);
            return true;
        }
        log.warn("machine guard before the ride: {} after {} s and {} readings", unchanged ? "the difference has not changed for "
            + (tick - c.guardSameSince) / 20 + " s (not machines on their way home)" : "still different",
            (tick - c.guardWaitSince) / 20, c.guardReads);
        c.guardSpot = null;
        block("machine guard before the ride: " + d.summary() + ". The muncher is not as recorded; the bot does"
            + " nothing more to the machinery. Fix it (or delete blobs/" + guardBlobName() + " to record it again),"
            + " then resume.");
        return false;
    }

    /**
     * The viewpoint nearest the bot from which the last walk had all of {@code chunks} in view ({@link Reading#seenFrom});
     * null when none had (the whole walk is read again).
     */
    private @Nullable Pos guardSpot(Reading rd, Set<Long> chunks) {
        Pos at = botFeet();
        Pos best = null;
        double bestD = Double.MAX_VALUE;
        for (Map.Entry<Pos, Set<Long>> e : rd.seenFrom.entrySet()) {
            if (!e.getValue().containsAll(chunks)) continue;
            Pos p = e.getKey();
            double dist = (double) (p.x() - at.x()) * (p.x() - at.x()) + (double) (p.z() - at.z()) * (p.z() - at.z());
            if (dist < bestD) {
                bestD = dist;
                best = p;
            }
        }
        return best;
    }

    /**
     * One more look at a pre-ride guard that differed, from {@link Ctx#guardSpot} only (the nearest viewpoint that saw
     * every differing chunk): the walk there once, then a reading where the bot stands; the chunks not in view from
     * there count as last read. False while acting, waiting or BLOCKED. True when the whole walk is due again: the
     * difference is gone (the full reading, guard and stasis pearls, confirms it before the boarding), or the differing
     * chunks did not come into view from the spot (then every look is the whole walk, as before).
     */
    private boolean guardReread() {
        MapArtConfig.RideCfg r = ride();
        Ctx c = ctx;
        Pos spot = c.guardSpot;
        if (!c.guardSpotWalked) {
            c.guardSpotWalked = true;
            Pos feet = botFeet();
            if (Math.abs(feet.x() - spot.x()) > 2 || Math.abs(feet.z() - spot.z()) > 2 || Math.abs(feet.y() - spot.y()) > 3) {
                run(bot.walkTo(spot, 2), null, why -> {
                    log.warn("viewpoint {} not reached for the guard's next look ({}); the whole walk again", spot.key(), why);
                    c.guardSpot = null;
                    c.guardSpotFailed = true;
                });
                return false;
            }
        }
        Reading here = readHere();
        if (here == null) return false;
        MachineGuard.Snapshot ref = guardRef();
        if (ref == null || here.machine == null || c.guardBase == null || c.guardBase.machine == null
            || !here.machine.chunkKeys().containsAll(c.guardChunks)) {
            log.info("the guard's differing chunks are not all in view from {}: the whole walk again", spot.key());
            c.guardSpot = null;
            c.guardSpotFailed = true;
            return true;
        }
        MachineGuard.merge(here.machine, c.guardBase.machine);
        MachineGuard.Diff d = MachineGuard.compare(ref, here.machine, r.machine);
        if (d.clean() && d.chunksNotSeen() == 0) {
            log.info("machine guard before the ride: the difference is gone after {} s; reading the viewpoints once more",
                (tick - c.guardWaitSince) / 20);
            c.guardSpot = null;
            return true;
        }
        guardDiffers(d);
        return false;
    }

    /** RIDE: from the seat to the brake, map in hand ({@code BotActions.ride}). Any failure is BLOCKED (no retry). */
    private void stepRide() {
        MapArtConfig.RideCfg r = ride();
        Integer id = currentUnlockedMap();
        if (id == null) {
            block("the unlocked map is not in the inventory in the cart");
            return;
        }
        if (tile.ridePause != null) {
            ridePause(id);
            return;
        }
        WorldView.Thing v = bot.world().vehicle();
        if (v == null) {
            if (!tile.ridePressed) {
                go(Phase.RIDE_BOARD);
                return;
            }
            if (rideOutSettling()) return;
            // a ride given up is not boarded again: its recovery walks to the booth, where the cart of the ended run
            // stands in reach on the brake
            if (!tile.rideLost && reboardRide()) return;
            if (recoverLostRide()) return;
            block("not in the cart after the launch (a restart or a reconnect mid-ride?): the machines may be"
                + " mid-cycle. Check the muncher; finish the map by hand or cancel the job");
            return;
        }
        rideOutTick = -1;
        boolean press = !tile.ridePressed;
        RideWatch watch = new RideWatch(r.stallMinutes * 1200, r.maxRideMinutes * 1200, r.minHealth, r.swingSeconds * 20);
        note = RIDE_NOTE;
        log.info("riding the muncher with map {}{}", id, press ? "; launch button first" : " (launched before)");
        notifier.detail("Ride started", label() + ": map " + id + " in hand, about " + (rideExpectedSeconds() + 30) / 60
            + " minutes to the booth.");
        // the cart the bot put back on a lane after a pause (ridePauseBoard) needs the push; never again once it left
        Pos back = tile.ridePauseRail;
        Float resumeYaw = !press && back != null && v.horizontalDistSq(back.x() + 0.5, back.z() + 0.5) <= 1.5 * 1.5
            && Math.abs(v.y() - back.y()) <= 1.5 ? tile.ridePauseYaw : null;
        if (resumeYaw != null) log.info("the cart stands on {} where the bot put it back: the push along yaw {} first",
            back.key(), resumeYaw);
        ridePausePending = null;
        BotActions.RideOptions opts = new BotActions.RideOptions(r.restartPause ? this::restartPauseNow : null, resumeYaw);
        run(bot.ride(press ? r.button : null, r.brake, id, r.pushYaw, watch, () -> {
            tile.ridePressed = true;
            tile.rideStartedAt = clock.millis();
            tile.rideEndedAt = 0;
            saveJob(job);
            publishStatus(); // the ride's clock starts in the status with the launch
        }, opts), () -> {
            LaneEnd paused = ridePausePending;
            ridePausePending = null;
            if (paused != null) {
                beginRidePause(paused);
                return;
            }
            note = "";
            if (tile.rideStartedAt > 0) {
                tile.rideEndedAt = clock.millis();
                saveJob(job);
                rideExpectedFor = null; // the next ride is expected to take what this one did
                log.info("the cart stopped at the brake, {} after the launch", formatDuration(tile.rideEndedAt - tile.rideStartedAt));
            } else {
                log.info("the cart stopped at the brake");
            }
            go(Phase.RIDE_END);
        }, reason -> {
            // out of the cart mid-ride (live 2026-11-14 and 2026-11-16: the anti-cheat set the riding bot back and the
            // server put it out beside its cart): nothing is decided on this tick's picture. The next steps wait for
            // it to settle (rideOutSettling), then get back in (reboardRide) or recover the lost ride (recoverLostRide)
            if (bot.world().vehicle() == null && rideReboards < RIDE_REBOARDS && nearCart() != null) {
                log.warn("the ride stopped out of the cart ({}); a cart is in reach: getting back in once the server's"
                    + " picture has settled", reason);
                return;
            }
            if (bot.world().vehicle() == null && tile.ridePressed && ride().recoverLostRide) {
                log.warn("the ride stopped out of the cart ({}) with no cart in reach at this moment: looking again"
                    + " once the server's picture has settled", reason);
                return;
            }
            block("the ride stopped: " + reason + ". The bot stays where it is and does nothing more to the machinery.");
        });
    }

    // ---- a ride paused for an announced server restart (ride.restartPause, docs/COLOR_SUPPRESSION.md) ----

    /** Status note of a ride paused for a server restart (then what the bot is doing). */
    public static final String RIDE_PAUSE_NOTE = "server restart due: the ride is paused at a lane end";
    /** Stages of the pause ({@code tile.ridePauseStage}): out of the cart, the cart broken, the stand, then back. */
    static final int RP_LEAVE = 1, RP_BREAK = 2, RP_STAND = 3, RP_RELOAD = 4, RP_BOARD = 5;
    /** The cart counts as standing in a hold after this many still ticks. */
    static final int RP_STILL_MIN = 20;
    /**
     * A cart that has stood in its hold longer than this may be let go any moment (a hold lasts under a minute): the
     * bot stays in and gets out at the next hold on the walkway's side instead, unless the restart is nearer than
     * {@link #RP_LAST_CHANCE_MS} (then now or never).
     */
    static final int RP_STILL_MAX = 400;
    static final long RP_LAST_CHANCE_MS = 150_000L;
    /** One hold of the map on the stand; repeated until the restart. */
    static final int RP_HOLD_TICKS = 200;
    /** The stand began on this connection (a disconnect or a new process: the chunks are to be loaded again). */
    private boolean ridePauseLive;
    /** Set by {@link #restartPauseNow} when it ends the ride task: the lane end the cart stands in. */
    private LaneEnd ridePausePending;

    /**
     * A lane end of the muncher's rail field, read from the blocks around a cart standing in its hold: {@code hold}
     * the slope rail's cell the cart leans in (its block column is the rider's), {@code corner} the plain rail west of
     * it under the closed iron trapdoor, {@code rail} the next lane's first level rail (up the two-rail ramp from the
     * corner) and {@code yaw} the look along that lane (0 south, 180 north).
     */
    record LaneEnd(Pos hold, Pos corner, Pos rail, float yaw) { }

    private static boolean isRail(BlockSpec b) {
        return b != null && b.id().endsWith("rail");
    }

    /** The lane end whose hold a cart at {@code cx, cy, cz} stands in, or null when it stands in none. */
    static @Nullable LaneEnd laneEnd(WorldView w, double cx, double cy, double cz) {
        int bx = (int) Math.floor(cx), by = (int) Math.floor(cy), bz = (int) Math.floor(cz);
        for (int y = by; y >= by - 1; y--) {
            Pos hold = new Pos(bx, y, bz);
            if (!isRail(w.block(hold))) continue;
            Pos corner = hold.offset(-1, 0, 0);
            BlockSpec c = w.block(corner);
            if (!"minecraft:rail".equals(c.id())) continue;
            BlockSpec door = w.block(corner.above());
            if (!"minecraft:iron_trapdoor".equals(door.id()) || "true".equals(door.props().get("open"))) continue;
            String shape = c.props().get("shape");
            int dz = "south_east".equals(shape) ? 1 : "north_east".equals(shape) ? -1 : 0;
            if (dz == 0) continue;
            // the next lane from the corner: up its ramp (a rail a level higher each step) to the first level rail
            Pos p = corner;
            for (int k = 1; k <= 6; k++) {
                Pos next = p.offset(0, 0, dz);
                if (!isRail(w.block(next))) next = next.above();
                if (!isRail(w.block(next))) break;
                p = next;
                if ("north_south".equals(w.block(p).props().get("shape"))) return new LaneEnd(hold, corner, p, dz > 0 ? 0f : 180f);
            }
        }
        return null;
    }

    /**
     * {@link BotActions.RideStop} of the ride: a server restart is announced and the cart stands in a lane-end hold
     * the bot can walk away from ({@link #walkwayBeside}; from any other hold it rides one more lane). True ends the
     * ride task with the bot seated; {@link #beginRidePause} follows.
     */
    private boolean restartPauseNow(WorldView.Thing cart, long still) {
        MapArtConfig.RideCfg r = ride();
        if (r == null || !r.restartPause || tile == null || !tile.ridePressed || !restart.announced()) return false;
        long left = restart.msLeft(clock.millis());
        if (left < -RestartHold.GRACE_MS || still < RP_STILL_MIN) return false;
        if (still > RP_STILL_MAX && left > RP_LAST_CHANCE_MS) return false;
        LaneEnd e = laneEnd(bot.world(), cart.x(), cart.y(), cart.z());
        if (e == null || !walkwayBeside(r, e.hold())) return false;
        ridePausePending = e;
        return true;
    }

    /**
     * A cell north or south of the hold, where the server puts the bot when it gets out of the cart, lies in one of
     * the ride's access boxes: there is an opened way from this hold. The near row has the walkway (live from the
     * start); the far row its strip, once that, the west stairs and the floor under the rail field are opened
     * (owner 2026-11-15: "i built a glass walkway it can use to get around on the west side").
     */
    private static boolean walkwayBeside(MapArtConfig.RideCfg r, Pos hold) {
        for (int dz = -1; dz <= 1; dz += 2)
            if (SiteRules.regionAt(r.access, hold.x(), hold.y(), hold.z() + dz) != null) return true;
        return false;
    }

    private void beginRidePause(LaneEnd e) {
        tile.ridePause = e.hold();
        tile.ridePauseRail = e.rail();
        tile.ridePauseYaw = e.yaw();
        tile.ridePauseStage = RP_LEAVE;
        saveJob(job);
        long min = restart.minutesLeft(clock.millis());
        log.warn("{}: server restart in about {} min (\"{}\") and the cart stands in the lane-end hold {}: the ride is"
            + " paused there (out of the cart, the cart broken, the map in hand on the hold's cell until the restart)",
            label(), min, restart.text(), e.hold().key());
        notifier.info("Ride paused for the restart", label() + ": restart in about " + min + " min. The bot gets out at a"
            + " lane end, breaks the cart and stands there with the map; after the restart it loads the chunks again"
            + " and rides on.");
    }

    private void ridePauseStage(int stage) {
        tile.ridePauseStage = stage;
        saveJob(job);
    }

    /**
     * RIDE with {@code tile.ridePause} set, one step per call (the owner's procedure, 2026-11-15): the map out of the
     * hand and out of the cart (the dismount puts the bot beside the cart, a block off the seat's column), the cart
     * broken and picked up, then the map in hand on the hold's own cell until the restart. After it (a new
     * connection) the rest is RIDE_BOARD's ({@link #ridePauseBoard}); a restart that does not come within its grace
     * sends the bot straight back into the cart.
     */
    private void ridePause(int id) {
        Pos hold = tile.ridePause;
        WorldView.Thing v = bot.world().vehicle();
        switch (tile.ridePauseStage) {
            case RP_LEAVE -> {
                if (v == null) {
                    ridePauseStage(RP_BREAK);
                    return;
                }
                note = RIDE_PAUSE_NOTE + ": getting out";
                run(bot.leaveCart(), () -> ridePauseStage(RP_BREAK), reason -> block("server restart due: getting out"
                    + " of the cart at the lane end " + hold.key() + " failed: " + reason + ". The bot does nothing more"
                    + " to the machinery."));
            }
            case RP_BREAK -> {
                if (v != null) {
                    ridePauseStage(RP_LEAVE);
                    return;
                }
                note = RIDE_PAUSE_NOTE + ": taking the cart off the rail";
                run(bot.breakCart(hold), () -> {
                    if (bot.inventory().count(CART) < 1) {
                        block("server restart due: the cart at the lane end " + hold.key() + " was not picked up. Put a"
                            + " minecart in the bot's inventory, then resume");
                        return;
                    }
                    ridePauseLive = true;
                    ridePauseStage(RP_STAND);
                }, reason -> block("server restart due: breaking the cart at the lane end " + hold.key() + " failed: "
                    + reason));
            }
            case RP_STAND -> {
                if (!ridePauseLive) {
                    log.info("{}: back after the restart on the lane-end hold {}: the map out of the hand, the"
                        + " viewpoints and the preload lap, then back into the cart", label(), hold.key());
                    notifier.detail("Ride resumes after the restart", label() + ": loading the chunks again (viewpoints,"
                        + " preload lap), then back into the cart at the lane end.");
                    ridePauseStage(RP_RELOAD);
                    go(Phase.RIDE_BOARD);
                    return;
                }
                if (!restart.announced()) {
                    log.info("{}: the announced restart did not come: back into the cart at {} (the same connection,"
                        + " nothing to load again)", label(), hold.key());
                    ridePauseStage(RP_BOARD);
                    go(Phase.RIDE_BOARD);
                    return;
                }
                note = RIDE_PAUSE_NOTE + ": map in hand on " + hold.key() + ", waiting for the restart";
                run(bot.holdMapExactly(hold, id, RP_HOLD_TICKS), null, reason -> block("server restart due: the stand"
                    + " on the lane-end hold " + hold.key() + " failed: " + reason + ". The map is out of the hand."));
            }
            default -> go(Phase.RIDE_BOARD);
        }
    }

    /**
     * RIDE_BOARD of a paused ride ({@code tile.ridePauseStage} RP_RELOAD or RP_BOARD). RP_RELOAD, after the restart:
     * the map out of the hand first, then the viewpoints (the platform's chunks and the stasis pearls by them; the
     * machine guard is not compared, the machines stand mid-run) and the preload lap. RP_BOARD: back to the hold's
     * cell, the cart onto the next lane's first level rail, in; RIDE then pushes it off with the map in hand.
     */
    private void ridePauseBoard(MapArtConfig.RideCfg r, MapArtConfig.PreloadCfg pre) {
        Ctx c = ctx;
        Pos hold = tile.ridePause;
        Pos rail = tile.ridePauseRail;
        // a hold off the start rail's row: the way to the walkway and back leads round the rail field (the far row's
        // strip, the west stairs, the floor under the field). Asked for a viewpoint from there the pathfinder heads for
        // the strip's end nearest it and stalls (local 2026-11-15: no viewpoint reached, then BLOCKED on the pearls),
        // so a foot route (walkOnFoot: its own search through the opened cells, walked in hops) leads both ways
        boolean far = Math.abs(hold.z() - r.startRail.z()) > 1;
        if (tile.ridePauseStage == RP_RELOAD) {
            if (pre == null || !preloadFresh()) {
                if (!c.pauseStowed) {
                    note = RIDE_PAUSE_NOTE + ": the map out of the hand";
                    run(bot.leaveCart(), () -> ctx.pauseStowed = true, reason -> block("after the restart the map did"
                        + " not leave the hand on " + hold.key() + ": " + reason));
                    return;
                }
                if (far && !c.pauseAtWalkway && inRailField(r, hold, botFeet())) {
                    note = RIDE_PAUSE_NOTE + ": the way round to the walkway";
                    // to the booth, the walkway's near end: the route search sees only the chunks the client has (local
                    // 2026-11-15: from the south-west stair the walkway east of 96 blocks was not in view, no route).
                    // Only from inside the field: out of it (bot ticks anew in the middle of the viewpoints) the
                    // pathfinder has its usual ways
                    log.info("{}: the hold {} lies off the start rail's row: on foot round to the booth by the brake {}"
                        + " first", label(), hold.key(), r.brake.key());
                    run(bot.walkOnFoot(r.brake, 2), () -> ctx.pauseAtWalkway = true, reason -> block("after the"
                        + " restart the way from the lane-end hold " + hold.key() + " round to the walkway failed: "
                        + reason + ". The ride is not taken up again; the cart is in the inventory and the map out of"
                        + " the hand."));
                    return;
                }
                note = RIDE_PAUSE_NOTE + ": loading the chunks again";
                Reading rd = viewpointReading(true);
                if (rd == null) return;
                String pearls = pearlProblem(rd);
                if (pearls != null) {
                    block("after the restart, stasis chunk loaders: " + pearls + ". The ride is not taken up again; the"
                        + " cart is in the inventory and the map out of the hand.");
                    return;
                }
                if (pre != null) {
                    preloadCleared = job.id;
                    go(Phase.RIDE_PRELOAD);
                    return;
                }
            }
            ridePauseStage(RP_BOARD);
        }
        // (one foot route from wherever the lap or the last viewpoint ended: with a short view distance it goes in legs,
        // each to the reached cell with the fewest steps left through the opened cells, FootRouteTask)
        if (far && !c.pauseBackAtHold && !within(botFeet(), hold, 2, 1)) {
            note = RIDE_PAUSE_NOTE + ": the way round back to the hold";
            log.info("{}: on foot round to the lane-end hold {}", label(), hold.key());
            run(bot.walkOnFoot(hold, 1), () -> ctx.pauseBackAtHold = true, reason -> block("the paused ride was not"
                + " taken up again: the way round back to the lane-end hold " + hold.key() + " failed: " + reason
                + ". The cart is in the inventory and the map out of the hand."));
            return;
        }
        note = RIDE_PAUSE_NOTE + ": back into the cart";
        log.info("{}: back to the lane-end hold {}: the cart onto {} and in", label(), hold.key(), rail.key());
        run(bot.boardCart(rail, hold, cid -> log.info("in minecart {}", cid)), () -> {
            tile.ridePause = null;
            tile.ridePauseStage = 0;
            rideReboards = 0;
            saveJob(job);
            preloadCleared = null;
            preloadedJob = null;
            notifier.detail("Ride goes on", label() + ": back in the cart at the lane end; the ride goes on.");
            go(Phase.RIDE);
        }, reason -> block("the paused ride was not taken up again at the lane end " + hold.key() + " (the cart onto "
            + rail.key() + ", then in): " + reason + ". The bot does nothing more to the machinery."));
    }

    /**
     * The feet are inside the rail field beyond the start rail's row, on the hold's side: between the start rail's and
     * the brake's columns (the stair two columns past the brake included), more than a row off the start rail's row
     * and at most four below the hold (the floor under the field lies three below; the preload lap's track under it,
     * five below, is not the field: local 2026-11-15, the lap's stand counted as in it). From there only the way
     * round (far-row strip, stairs, the floor under the field) leads out.
     */
    static boolean inRailField(MapArtConfig.RideCfg r, Pos hold, Pos feet) {
        int x0 = Math.min(r.startRail.x(), r.brake.x()), x1 = Math.max(r.startRail.x(), r.brake.x());
        if (r.brake.x() < r.startRail.x()) {
            x0 -= 2;
            x1 -= 1;
        } else {
            x0 += 1;
            x1 += 2;
        }
        int side = Integer.signum(hold.z() - r.startRail.z());
        return feet.x() >= x0 && feet.x() <= x1 && (feet.z() - r.startRail.z()) * side > 1 && feet.y() >= hold.y() - 4;
    }

    /** Status note while a lost ride waits for the muncher's run to end. */
    public static final String RIDE_LOST_NOTE = "the ride was lost: waiting for the muncher's run to end";
    /** Seconds after the ride's expected end before a lost ride's recovery walks anywhere. */
    static final int RIDE_LOST_MARGIN_S = 120;

    /**
     * RIDE after the launch, the bot out of the cart and no cart in reach ({@code ride.recoverLostRide}): the cart went
     * on with the muncher, so this map's ride is lost. One step of the recovery per call: wait where the bot stands
     * until the muncher's run has ended (the expected ride time after the launch, as if the bot had ridden), walk to
     * the booth, take the cart off the brake, put it back on the start rail, then print the map again
     * ({@link #reprintAfterLostRide}). False (the caller blocks) with the switch off.
     */
    private boolean recoverLostRide() {
        MapArtConfig.RideCfg r = ride();
        if (!r.recoverLostRide) return false;
        Ctx c = ctx;
        if (!tile.rideLost) {
            tile.rideLost = true;
            saveJob(job);
            log.warn("{}: out of the cart after the launch and no cart in reach: the ride is lost; the map is printed"
                + " again once the muncher's run has ended", label());
            alert("Ride lost", label() + ": the bot is out of the cart and the cart went on with the muncher. The map"
                + " is printed again once the muncher's run has ended; the bot fetches the cart from the brake itself.");
        }
        long endAt = tile.rideStartedAt + (rideExpectedSeconds() + RIDE_LOST_MARGIN_S) * 1000L;
        if (tile.rideStartedAt > 0 && clock.millis() < endAt) {
            note = RIDE_LOST_NOTE;
            sleep(200);
            return true;
        }
        note = "the ride was lost: fetching the cart";
        if (!c.lostAtBooth) {
            log.info("lost ride: walking to the booth by the brake {}", r.brake.key());
            run(bot.walkOnFoot(r.brake, 1), () -> ctx.lostAtBooth = true,
                reason -> block("the ride was lost and the walk to the booth failed: " + reason + ". Bring the bot to"
                    + " the walkway and the cart back to the start rail, then resume: the map is printed again"));
            return true;
        }
        if (!c.cartBroken) {
            c.cartBroken = true;
            c.lostCartStood = cartBy(r.brake) != null;
            run(bot.breakCart(r.brake), null, reason -> warnOnce("cart-break", label()
                + ": the cart of the lost ride was not taken off the brake (" + reason + ")"));
            return true;
        }
        boolean carried = bot.inventory().count(CART) > 0;
        if (!carried && c.attempt("cart-pickup") < CART_PICKUP_POLLS) {
            sleep(CART_PICKUP_POLL_TICKS);
            return true;
        }
        // ride.reuseCart: the platform's own cart goes back on the start rail; without it the bot keeps its cart
        if (carried && r.reuseCart && !c.lostCartPlaced) {
            c.lostCartPlaced = true;
            log.info("lost ride: putting the cart back on the start rail {} from {}", r.startRail.key(), r.startStand.key());
            run(bot.placeCart(r.startRail, r.startStand, placed -> log.info(placed
                ? "the cart is back on the start rail {}" : "a cart stands on the start rail {} already", r.startRail.key())),
                null, reason -> warnOnce("cart-return", label() + ": the cart was not put back on the start rail ("
                    + reason + "); it stays in the inventory"));
            return true;
        }
        // no cart on the brake: the muncher's run has not ended (the cart stands on the track); the first pass fetches it
        // before it prints (finishLostRun)
        boolean cartOut = !carried && !c.lostCartPlaced;
        if (cartOut && c.lostCartStood && r.reuseCart && !r.cartChests.isEmpty()) {
            // the cart stood by the brake and came off it, only the item was not picked up: the run has ended
            cartLost("the cart of the lost ride came off the brake " + r.brake.key() + " but was not picked up");
            cartOut = false;
        }
        if (cartOut) log.warn("{}: no cart stood on the brake after the lost ride: its run is finished before the map is"
            + " printed again", label());
        reprintAfterLostRide(cartOut);
        return true;
    }

    /**
     * The current colour-suppression job is printed again from its first pass after a lost ride: every pass back to
     * PENDING with nothing placed and no ride on record; the unlocked map the bot made stays the last pass's (it is
     * filled again). The layers are empty by now (the muncher's run ended); a layer still being cleared is waited for by
     * its first pass as after any ride.
     */
    private void reprintAfterLostRide(boolean cartOut) {
        Job j = job;
        j.lostRunOpen = cartOut;
        j.lostCartBack = false;
        for (Job.TileState t : j.tiles) {
            t.status = TileStatus.PENDING;
            t.startedAt = 0;
            t.finishedAt = 0;
            t.placed = 0;
            t.placedAhead = 0;
            t.counted = false;
            t.metrics = null;
            t.ridePressed = false;
            t.rideStartedAt = 0;
            t.rideEndedAt = 0;
            t.cartReturnDue = false;
            t.cartReturned = false;
            t.preloadPressed = false;
            t.preloadLaps = 0;
            t.rideSettled = false;
            t.rideLost = false;
            t.ridePause = null;
            t.ridePauseStage = 0;
            t.ridePauseRail = null;
            t.carried = 0;
            t.carriesRaised = false; // build.suppressionMergeLevels: the sweep of this print marks it again
        }
        rideReboards = 0;
        saveJob(j);
        log.warn("{}: printing the map again after the lost ride ({} passes)", label(), j.tiles.size());
        notifier.info("Printing again", j.name + ": the ride was lost, the map is printed again from its"
            + " first pass.");
        clearCurrent();
        go(Phase.SELECT);
    }

    // ---- a lost ride whose cart did not reach the brake (job.lostRunOpen) ----

    /** Status note of {@link #finishLostRun} (then what it is doing). */
    public static final String LOST_RUN_NOTE = "the lost ride's cart is still out: ";
    private static final int LR_VIEW = 0, LR_BOARD = 1, LR_LAP = 2, LR_LEAVE = 3, LR_WALK = 4, LR_WAIT = 5, LR_BREAK = 6,
        LR_PICKUP = 7, LR_PLACE = 8;
    /** The booth looks for the cart this often (ticks); it has arrived once it stood by the brake for this many looks. */
    static final int LOST_CART_POLL_TICKS = 100;
    static final int LOST_CART_LOOKS = 2;
    /**
     * With a preload lap: the booth waits this long for the cart (ticks), then the lap is ridden again, at most this
     * many times (a whole ride's 64 lanes and a margin). An empty cart moves only in chunks that tick, and under the
     * lanes there are no stasis pearls (only along the field's north and south ends): the cart runs while the bot is
     * near and stands still otherwise. From the booth the lanes' south third does not tick. Live 2026-11-14: one lane
     * per lap; the cart left the north hold within 30 s of the bot's return and stood at z 80 of the next lane until
     * the next lap.
     */
    static final int LOST_CART_ROUND_TICKS = 1800;
    static final int LOST_CART_ROUNDS = 80;

    /** {@link #trackLostCart}: minecart id to where it was last logged (tenths of a block, platform-relative) and when. */
    private final Map<Integer, long[]> cartsSeen = new HashMap<>();

    /**
     * Every tick while a lost ride's cart is out ({@code job.lostRunOpen}, the cart not back), in every phase: each
     * minecart the client tracks (not the one the bot sits in) goes to the log when it comes into view and whenever it
     * has moved 3 blocks since, at most every 5 s, relative to the platform's corner. The lap passes under the rail
     * field's sides: where the cart stands, and whether it moves, is in the log afterwards.
     */
    private void trackLostCart() {
        if (job == null || !job.lostRunOpen || job.lostCartBack || platform == null) {
            if (!cartsSeen.isEmpty()) cartsSeen.clear();
            return;
        }
        if (tick % 10 != 0) return;
        WorldView w = bot.world();
        WorldView.Thing own = w.vehicle();
        for (WorldView.Thing t : w.entities(Set.of("minecart"))) {
            if (own != null && own.id() == t.id()) continue;
            long x = Math.round((t.x() - platform.minX) * 10), y = Math.round(t.y() * 10), z = Math.round((t.z() - platform.minZ) * 10);
            long[] last = cartsSeen.get(t.id());
            if (last != null && (tick - last[3] < 100
                || (Math.abs(x - last[0]) < 30 && Math.abs(y - last[1]) < 30 && Math.abs(z - last[2]) < 30))) continue;
            log.info("lost ride: minecart {} {} at [cs{}, {}, cs{}], the bot at [cs{}, {}, cs{}]", t.id(),
                last == null ? "in view" : "moved", String.format(Locale.ROOT, "%+.1f", x / 10.0), y / 10.0,
                String.format(Locale.ROOT, "%+.1f", z / 10.0), String.format(Locale.ROOT, "%+.1f", w.playerX() - platform.minX),
                String.format(Locale.ROOT, "%.1f", w.playerY()), String.format(Locale.ROOT, "%+.1f", w.playerZ() - platform.minZ));
            cartsSeen.put(t.id(), new long[]{x, y, z, tick});
        }
    }

    /** The current pass belongs to a job with a lost ride whose cart is not back ({@link #finishLostRun} comes first). */
    private boolean lostRunDue() {
        return job != null && job.lostRunOpen && suppressionTile() && platform.ride != null && platform.ride.configured()
            && platform.ride.recoverLostRide;
    }

    /** The minecarts the client tracks, relative to the platform's corner (for the log). */
    private String cartsInView() {
        List<WorldView.Thing> carts = bot.world().entities(Set.of("minecart"));
        StringBuilder sb = new StringBuilder("minecarts in view: ").append(carts.size());
        for (WorldView.Thing t : carts)
            sb.append(String.format(Locale.ROOT, " [cs%+.1f, %.1f, cs%+.1f]", t.x() - platform.minX, t.y(), t.z() - platform.minZ));
        return sb.toString();
    }

    /** The minecart standing by {@code brake} (the range of the task that breaks it), or null. */
    private WorldView.@Nullable Thing cartBy(Pos brake) {
        for (WorldView.Thing t : bot.world().entities(Set.of("minecart")))
            if (t.horizontalDistSq(brake.x() + 0.5, brake.z() + 0.5) <= 2.5 * 2.5 && Math.abs(t.y() - brake.y()) <= 2) return t;
        return null;
    }

    /**
     * RIDE_RESET with {@code job.lostRunOpen} (live 2026-11-14): after a lost ride the cart did not stand on the brake,
     * because the proxy was restarted during the run: the bot's stasis pearls hold their chunks only once the new
     * connection has loaded them, so the cart stopped where the bot's own simulation distance ended and the last lanes
     * were never munched. One step per call: the viewpoints (the pearls under the layers, and what the layers and the
     * machine guard show, for the log), the preload lap (the pearls under the rail field), on foot to the booth, wait
     * there for the cart (as long as a whole ride at most), take it off the brake and put it back on the start rail.
     * True once the cart is back ({@code job.lostCartBack}); the reset wait of RIDE_RESET follows, then the map is
     * printed again ({@link #lostRunSettled}).
     */
    private boolean finishLostRun() {
        if (job.lostCartBack) return true;
        MapArtConfig.RideCfg r = ride();
        MapArtConfig.PreloadCfg pre = preload();
        Ctx c = ctx;
        WorldView w = bot.world();
        boolean carried = bot.inventory().count(CART) > 0;
        // a restart between the pick-up and the start rail: the cart is carried
        if (c.lostStage == LR_VIEW && carried && r.reuseCart && w.vehicle() == null) c.lostStage = LR_PLACE;
        switch (c.lostStage) {
            case LR_VIEW -> {
                WorldView.Thing seat = w.vehicle();
                if (seat != null && seat.horizontalDistSq(r.brake.x() + 0.5, r.brake.z() + 0.5) <= 2.5 * 2.5
                    && Math.abs(seat.y() - r.brake.y()) <= 2) {
                    // seated in the cart that stands by the brake (live 2026-11-15: a run ridden to its end with no map
                    // in the hand): out of it in the booth; it comes off the brake and goes back like the lost ride's
                    note = LOST_RUN_NOTE + "out of the cart by the brake";
                    log.info("lost ride: seated in the cart by the brake {}: getting out; the cart goes back on the start rail",
                        r.brake.key());
                    run(bot.leaveCart(), () -> c.lostStage = LR_BREAK,
                        reason -> block("lost ride: getting out of the cart by the brake failed: " + reason));
                    return false;
                }
                if (seat != null) {
                    block("in a cart at the start of the lost ride's recovery; get out, then resume");
                    return false;
                }
                note = LOST_RUN_NOTE + "reading the machine";
                if (c.vpOrder == null && c.reading == null && climbToPlatform()) return false;
                Reading rd = viewpointReading(true);
                if (rd == null) return false;
                String pearls = pearlProblem(rd);
                if (pearls != null) warnOnce("lost-run-pearls", label() + ": stasis chunk loaders: " + pearls
                    + ". The lost ride's cart only moves while the bot is near it.");
                MachineGuard.Snapshot ref = r.machine.isEmpty() ? null : guardRef();
                MachineGuard.Diff d = ref == null || rd.machine == null ? null : MachineGuard.compare(ref, rd.machine, r.machine);
                log.info("lost ride: {} blocks on the layers; machine guard: {}; {}", layerBlocks(),
                    d == null ? "no reading" : d.clean() ? "as recorded" : d.summary(), cartsInView());
                c.lostStage = pre != null ? LR_BOARD : LR_WALK;
                sleep(1);
            }
            case LR_BOARD -> {
                note = LOST_RUN_NOTE + "riding the preload lap";
                log.info("lost ride: the preload lap first (the pearls under the rail field): boarding the cart on {}",
                    pre.startRail.key());
                run(bot.boardOwnCart(pre.startRail, pre.startStand, cid -> log.info("in the preload minecart {}", cid)),
                    () -> c.lostStage = LR_LAP, reason -> block("lost ride: the preload lap did not start: " + reason));
            }
            case LR_LAP -> {
                note = LOST_RUN_NOTE + "riding the preload lap";
                RideWatch watch = new RideWatch(pre.stallMinutes * 1200, pre.maxMinutes * 1200, r.minHealth, r.swingSeconds * 20);
                run(bot.preloadRide(pre.button, pre.endRail(), pre.pushYaw, watch, () -> { }), () -> {
                    c.lostStage = LR_LEAVE;
                    log.info("lost ride: the preload lap ended; {}", cartsInView());
                }, reason -> block("lost ride: the preload lap stopped: " + reason));
            }
            case LR_LEAVE -> run(bot.leaveCart(), () -> c.lostStage = LR_WALK,
                reason -> block("lost ride: getting out of the preload cart failed: " + reason));
            case LR_WALK -> {
                note = LOST_RUN_NOTE + "walking to the booth";
                log.info("lost ride: on foot to the booth by the brake {}", r.brake.key());
                run(bot.walkOnFoot(r.brake, 1), () -> {
                    c.lostStage = LR_WAIT;
                    c.lostWaitSince = tick;
                }, reason -> block("lost ride: the walk to the booth failed: " + reason));
            }
            case LR_WAIT -> {
                WorldView.Thing cart = cartBy(r.brake);
                if (cart == null) c.lostCartSeen = 0;
                else if (++c.lostCartSeen >= LOST_CART_LOOKS) {
                    log.info("lost ride: the cart stands by the brake {} ({} min after the bot came): taking it off",
                        r.brake.key(), (tick - c.lostWaitSince) / 1200);
                    c.lostStage = LR_BREAK;
                    sleep(1);
                    return false;
                }
                long waited = tick - c.lostWaitSince;
                // with a lap: short waits with a lap between them; without one: one wait as long as a whole ride
                long limit = pre != null ? LOST_CART_ROUND_TICKS : (rideExpectedSeconds() + RIDE_LOST_MARGIN_S) * 20L;
                if (waited >= limit && pre != null && c.lostRounds + 1 < LOST_CART_ROUNDS) {
                    c.lostRounds++;
                    c.lostCartSeen = 0;
                    log.info("lost ride: no cart at the brake after {} min in the booth ({}): the lap again ({} of {})",
                        waited / 1200, cartsInView(), c.lostRounds + 1, LOST_CART_ROUNDS);
                    c.lostStage = LR_BOARD;
                    sleep(1);
                    return false;
                }
                if (waited >= limit) {
                    block("the cart of the lost ride did not come to the brake (" + (pre != null ? LOST_CART_ROUNDS
                        + " laps, " + limit / 1200 + " min in the booth after each" : limit / 1200 + " min in the booth")
                        + "; " + cartsInView() + "): the muncher's run is not finished, the layers are not cleared and"
                        + " the next ride's cart would run into it");
                    return false;
                }
                note = LOST_RUN_NOTE + "waiting in the booth for it (" + waited / 1200 + " min"
                    + (pre != null ? ", lap " + (c.lostRounds + 1) : "") + ")";
                if (c.lostLogged++ % 6 == 0)
                    log.info("lost ride: waiting in the booth for the cart, {} min so far; {}", waited / 1200, cartsInView());
                sleep(LOST_CART_POLL_TICKS);
            }
            case LR_BREAK -> run(bot.breakCart(r.brake), () -> c.lostStage = LR_PICKUP, reason -> {
                log.warn("lost ride: the cart was not taken off the brake ({})", reason);
                c.lostStage = LR_PICKUP;
            });
            case LR_PICKUP -> {
                if (!carried && c.attempt("lost-cart-pickup") < CART_PICKUP_POLLS) {
                    sleep(CART_PICKUP_POLL_TICKS);
                    return false;
                }
                if (!carried) {
                    if (r.reuseCart && !r.cartChests.isEmpty()) {
                        // live 2026-11-16: BLOCKED here with the run finished, and the item was gone an hour later
                        cartLost("the cart of the lost ride came off the brake " + r.brake.key() + " but was not picked up");
                        lostCartIsBack();
                        return false;
                    }
                    block("the cart of the lost ride stood by the brake " + r.brake.key() + " but is not in the inventory"
                        + " after the break: look for it in the booth; the next ride needs one on the start rail");
                    return false;
                }
                c.lostStage = LR_PLACE;
                sleep(1);
            }
            default -> {
                if (!r.reuseCart) { // the bot keeps its own cart for the next ride
                    lostCartIsBack();
                    return false;
                }
                note = LOST_RUN_NOTE + "putting it back on the start rail";
                log.info("lost ride: putting the cart back on the start rail {} from {}", r.startRail.key(), r.startStand.key());
                run(bot.placeCart(r.startRail, r.startStand, placed -> log.info(placed
                    ? "the cart is back on the start rail {}" : "a cart stands on the start rail {} already", r.startRail.key())),
                    this::lostCartIsBack, reason -> {
                        warnOnce("cart-return", label() + ": the cart was not put back on the start rail (" + reason
                            + "); it stays in the inventory");
                        lostCartIsBack();
                    });
            }
        }
        return false;
    }

    private void lostCartIsBack() {
        job.lostCartBack = true;
        saveJob(job);
        updateSprintGate();
        log.info("lost ride: the cart is off the track; waiting for the muncher to reset");
        sleep(1);
    }

    /** A lost run's leftover with every machine home counts as final after this many minutes of the same count. */
    static final int LEFTOVER_CONFIRM_MINUTES = 1;

    private static int leftoverConfirmMinutes(MapArtConfig.RideCfg r) {
        return Math.min(LEFTOVER_CONFIRM_MINUTES, Math.max(0, r.guardConfirmMinutes));
    }

    /**
     * The reset wait after {@link #finishLostRun} ended: the machines home and both layers empty, or {@code left}
     * blocks of the job's own print still on them (a run that ended short; {@link #stepRideReset}). The map is printed
     * again, over what is left.
     */
    private void lostRunSettled(int left) {
        job.lostRunOpen = false;
        job.lostCartBack = false;
        saveJob(job);
        if (left == 0) {
            log.info("lost ride: the muncher's run has ended (both layers empty, the machines as recorded): {} is printed"
                + " again", job.name);
            notifier.detail("Muncher reset", job.name + ": the lost ride's run has ended and the cart is back; printing again.");
        } else {
            log.info("lost ride: the muncher's run has ended short (the machines as recorded, {} blocks left on the layers,"
                + " the same for {} min): {} is printed again over what is left", left, leftoverConfirmMinutes(ride()),
                job.name);
            notifier.info("Muncher reset", job.name + ": the machines are home and " + left + " blocks of the last print"
                + " are left on the layers; printing again over them.");
        }
        note = "";
        clearCurrent();
        go(Phase.SELECT);
    }

    /** Re-boardings of the cart in one ride ({@link #reboardRide}); reset at each boarding from the start rail. */
    private int rideReboards;
    /** At most this many re-boardings per ride; then BLOCKED as before. */
    static final int RIDE_REBOARDS = 3;
    /** A cart this close to the bot (blocks, each axis) counts as the bot's own held cart. */
    static final double RIDE_REBOARD_REACH = 4.0;

    /** The nearest minecart within {@link #RIDE_REBOARD_REACH} of the bot on each axis, or null. */
    private WorldView.@Nullable Thing nearCart() {
        WorldView w = bot.world();
        WorldView.Thing best = null;
        double bestD = Double.MAX_VALUE;
        for (WorldView.Thing t : w.entities(java.util.Set.of("minecart"))) {
            if (Math.abs(t.x() - w.playerX()) > RIDE_REBOARD_REACH || Math.abs(t.z() - w.playerZ()) > RIDE_REBOARD_REACH
                || Math.abs(t.y() - w.playerY()) > 2.5) continue;
            double d = t.horizontalDistSq(w.playerX(), w.playerZ());
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        return best;
    }

    /**
     * RIDE after the launch with the bot out of the cart: a cart within reach is the bot's own (held at a lane entry, or
     * standing where the server put the bot beside it), so the bot gets back in and the ride goes on without a second
     * press. False (the caller blocks) with no cart in reach or the re-boardings used up.
     */
    private boolean reboardRide() {
        WorldView.Thing cart = nearCart();
        if (cart == null || rideReboards >= RIDE_REBOARDS) return false;
        rideReboards++;
        Pos rail = new Pos((int) Math.floor(cart.x()), (int) Math.floor(cart.y() + 0.01), (int) Math.floor(cart.z()));
        Pos feet = botFeet();
        log.warn("out of the cart {} min after the launch (re-boarding {} of {}): getting back into minecart {} on {}",
            tile.rideStartedAt > 0 ? (clock.millis() - tile.rideStartedAt) / 60000 : -1, rideReboards, RIDE_REBOARDS,
            cart.id(), rail.key());
        notifier.detail("Back into the cart", label() + ": out of the cart mid-ride; the cart is in reach, getting back in.");
        run(bot.boardOwnCart(rail, feet, cid -> log.info("back in minecart {}", cid)), () -> { }, reason -> {
            // with the lost ride's recovery on, a failed try is not BLOCKED (live 2026-11-16: one failed try blocked
            // the ride and the cart went on): the next step tries again while a cart is in reach and tries are left,
            // then the recovery takes over
            if (ride().recoverLostRide) {
                log.warn("getting back into the cart failed ({}); {} of {} tries used", reason, rideReboards, RIDE_REBOARDS);
                return;
            }
            block("out of the cart mid-ride and getting back in failed: " + reason + ". Check the muncher;"
                + " finish the map by hand or cancel the job");
        });
        return true;
    }

    /**
     * Ticks the bot does nothing after it is found out of the cart mid-ride. Live 2026-11-14 and 2026-11-16: the
     * anti-cheat set the riding bot back to the seat it boarded in, moved the cart there for the client and had the
     * server put the bot out; the dismount teleport beside the real cart and the cart's real position came within the
     * same second. A decision on the first picture walked the bot to a cart that was not there.
     */
    static final int RIDE_OUT_SETTLE_TICKS = 30;
    /** After the settle a cart in reach is looked for this long before the ride counts as lost. */
    static final int RIDE_OUT_FIND_TICKS = 200;
    /** The engine tick the bot was first found out of the cart after the launch; -1 while it rides. */
    private long rideOutTick = -1;

    /**
     * RIDE after the launch with the bot out of the cart: true while nothing is to be decided yet. The first
     * {@link #RIDE_OUT_SETTLE_TICKS} the bot only waits (no key, no click); then, with re-boardings left, it waits up to
     * {@link #RIDE_OUT_FIND_TICKS} more for a cart to show in reach. False afterwards and for a ride given up before.
     */
    private boolean rideOutSettling() {
        if (tile.rideLost) return false;
        if (rideOutTick < 0) {
            rideOutTick = tick;
            log.warn("out of the cart after the launch: nothing is done for {} ticks, until the server's picture has"
                + " settled", RIDE_OUT_SETTLE_TICKS);
        }
        long out = tick - rideOutTick;
        if (out < RIDE_OUT_SETTLE_TICKS) {
            sleep(2);
            return true;
        }
        if (rideReboards < RIDE_REBOARDS && nearCart() == null && out < RIDE_OUT_SETTLE_TICKS + RIDE_OUT_FIND_TICKS) {
            sleep(5);
            return true;
        }
        return false;
    }

    /**
     * RIDE_END: out of the cart in the booth (the map out of the hand first), the cart taken off the brake, the map
     * compared with the finished art; LOCK only when it matches.
     */
    private void stepRideEnd() {
        MapArtConfig.RideCfg r = ride();
        if (bot.world().vehicle() != null) {
            run(bot.leaveCart(), null, reason -> block("getting out of the cart failed: " + reason));
            return;
        }
        Pos feet = botFeet();
        if (Math.abs(feet.x() - r.brake.x()) > 4 || Math.abs(feet.z() - r.brake.z()) > 6
            || Math.abs(feet.y() - r.brake.y()) > 3) {
            block("out of the cart at " + feet.key() + ", not in the booth by the brake " + r.brake.key());
            return;
        }
        Ctx c = ctx;
        if (!c.cartBroken) {
            c.cartBroken = true;
            run(bot.breakCart(r.brake), null, reason -> warnOnce("cart-break", label()
                + ": the cart was not taken off the brake (" + reason + "); the next ride's cart may stop short"));
            return;
        }
        if (r.reuseCart && !tile.cartReturnDue) {
            // the platform's own cart goes back on the start rail before the map is stored (stepStore): the item must
            // be in the inventory for that; the pick-up may lag the break by a moment
            boolean carried = bot.inventory().count(CART) > 0;
            if (!carried && c.attempt("cart-pickup") < CART_PICKUP_POLLS) {
                sleep(CART_PICKUP_POLL_TICKS);
                return;
            }
            tile.cartReturnDue = true;
            tile.cartReturned = false;
            saveJob(job);
            if (!carried) {
                log.warn("{}: no minecart in the inventory {} ticks after the cart on the brake {} was broken", label(),
                    CART_PICKUP_POLLS * CART_PICKUP_POLL_TICKS, r.brake.key());
                alertHere("Minecart not picked up", "The minecart was not picked up after the ride (none in the inventory "
                    + CART_PICKUP_POLLS * CART_PICKUP_POLL_TICKS / 20 + " s after the cart on the brake was broken). It"
                    + " cannot be put back on the start rail; the map is finished and stored as usual. Look for the cart"
                    + " by the brake.");
            } else log.info("the cart is off the brake and in the inventory: it goes back on the start rail after the name");
        }
        Integer id = currentUnlockedMap();
        if (id == null) {
            if (currentLockedMap() != null) go(Phase.LOCK);
            else block("the map is not in the inventory after the ride");
            return;
        }
        Optional<byte[]> data = bot.maps().colors(id);
        if (data.isEmpty()) {
            block("no map data for map " + id + " after the ride; it stays unlocked in the inventory");
            return;
        }
        csExpectedReady(true);
        byte[] actual = data.get().clone();
        int bad = expected.mismatches(actual);
        if (bad > 0) {
            TilePlan.ExpectedMap exp = expected;
            // where they are (the diff picture goes to Discord only): a pattern by the brake or a lane end shows a cause
            log.info("map {} after the ride: {} pixels off (x,z on the tile): {}", id, bad, exp.sample(actual, 40));
            renderAsync(() -> renderer.mapDiff(actual, exp.forDiff(actual), colors, RENDER_SCALE),
                "Map mismatch after the ride: " + job.name, label() + ": " + bad + " pixels differ from the finished art.",
                "mapdiff-" + job.id + "-ride.png");
            if (reprintAfterBadRide(id, bad)) return;
            block("after the ride map " + id + " shows " + bad + " pixels off the finished art (diff posted). It is not"
                + " locked and stays in the inventory: lock it by hand if it is good enough (never east of the red glass"
                + " with it in hand), or cancel the job");
            return;
        }
        log.info("map {} shows the finished art", id);
        notifier.detail("Ride done", label() + ": the map shows the finished art; locking it in the booth.");
        go(Phase.LOCK);
    }

    /** Reprints of one job after a ride whose map did not show the finished art ({@link #reprintAfterBadRide}). */
    static final int BAD_RIDE_REPRINTS = 2;

    /**
     * RIDE_END with a map that does not show the finished art ({@code ride.recoverLostRide}; live 2026-11-14: a server
     * restart 11 minutes into the ride: the bot came back seated in its cart, but from then on the sweep ran behind
     * the cart and the dominant pixels of every column still to come kept their fillers). The ride cannot be done
     * again on these layers (the muncher took them), so the map is printed again as after a lost ride: the cart back
     * on the start rail and the reset waited for ({@link #finishLostRun} with the cart carried), then every pass from
     * the first ({@link #reprintAfterLostRide}); the unlocked map stays in the inventory and is filled again. False
     * (the caller blocks, as before) with the switch off or after {@link #BAD_RIDE_REPRINTS} such reprints of the job.
     */
    private boolean reprintAfterBadRide(int id, int bad) {
        MapArtConfig.RideCfg r = ride();
        if (!r.recoverLostRide || job.badRides >= BAD_RIDE_REPRINTS) return false;
        job.badRides++;
        boolean carried = bot.inventory().count(CART) > 0;
        log.warn("{}: after the ride map {} shows {} pixels off the finished art: the map is printed again (reprint {}"
            + " of {} after a bad ride){}", label(), id, bad, job.badRides, BAD_RIDE_REPRINTS,
            r.reuseCart && carried ? "; the cart goes back on the start rail first" : "");
        alert("Ride failed", label() + ": after the ride the map shows " + bad + " pixels off the finished art (diff"
            + " posted). It is not locked; the map is printed again once the muncher has reset (reprint " + job.badRides
            + " of " + BAD_RIDE_REPRINTS + ").");
        // the cart carried from the brake goes back on the start rail and the reset is waited for (finishLostRun)
        reprintAfterLostRide(r.reuseCart && carried);
        return true;
    }

    /**
     * RIDE_RESET, after the stored map: both layers empty and the machines as recorded, read from the viewpoints (in
     * reverse: the bot walks back from the booth) and then again from the last one every {@link #SETTLE_POLL_TICKS}
     * ticks, for up to {@code ride.settleMinutes}. Then COUNT or FINISH_TILE; else BLOCKED with what differs.
     */
    private void stepRideReset() {
        MapArtConfig.RideCfg r = ride();
        Ctx c = ctx;
        if (job.lostRunOpen && !finishLostRun()) return;
        // from the output chest and the counter at the base: up the access path first, then the viewpoints
        if (c.settleBase == null && c.vpOrder == null && climbToPlatform()) return;
        if (c.settleSince < 0) c.settleSince = tick;
        Reading rd;
        if (c.settleBase == null) {
            rd = viewpointReading(false);
            if (rd == null) return;
            c.settleBase = rd;
        } else {
            Reading here = readHere();
            if (here == null) return;
            if (here.machine != null && c.settleBase.machine != null) MachineGuard.merge(here.machine, c.settleBase.machine);
            rd = here;
        }
        int layers = layerBlocks();
        MachineGuard.Snapshot ref = r.machine.isEmpty() ? null : guardRef();
        MachineGuard.Diff d = ref == null || rd.machine == null ? null : MachineGuard.compare(ref, rd.machine, r.machine);
        boolean clean = d == null || (d.clean() && d.chunksNotSeen() == 0);
        if (layers == 0 && clean && job.lostRunOpen) {
            lostRunSettled(0);
            return;
        }
        // a lost run that ended short of its sweep (live 2026-11-15: the top sweeper stalled by a server restart and
        // was set right by hand): every machine is as recorded and blocks of this job's own print are left. Nothing
        // sweeps any more once the count has been the same for LEFTOVER_CONFIRM_MINUTES (a run just launched has left
        // home long before the cart is back and the viewpoints are walked; the minute is the margin), so the map is
        // printed again over what is left (INSPECT resumes a platform that holds part of the tile)
        if (job.lostRunOpen && layers > 0 && d != null && clean) {
            if (layers != c.leftover) {
                c.leftover = layers;
                c.leftoverSince = tick;
            } else if (tick - c.leftoverSince >= leftoverConfirmMinutes(r) * 1200L) {
                lostRunSettled(layers);
                return;
            }
        } else {
            c.leftover = -1;
        }
        if (layers == 0 && clean) {
            tile.rideSettled = true;
            saveJob(job);
            log.info("the muncher reset: both layers empty{}", d == null ? "" : ", " + d.chunksCompared() + " machine chunks as recorded");
            notifier.detail("Muncher reset", label() + ": both layers empty and the machines home.");
            note = "";
            go(counterDue() ? Phase.COUNT : Phase.FINISH_TILE); // counted before the wait, unless a restart came between
            return;
        }
        String what = (layers < 0 ? "the layers not in view" : layers == 0 ? "the layers empty" : layers + " blocks still on the layers")
            + (clean ? "" : "; machine guard: " + d.summary());
        // not while a leftover with every machine home is being confirmed (above): that wait ends by itself
        if (c.leftover < 0 && tick - c.settleSince >= r.settleMinutes * 1200L) {
            block("after the ride the muncher did not reset within " + r.settleMinutes + " min: " + what
                + ". The bot does nothing more to the machinery.");
            return;
        }
        note = RESET_WAIT_NOTE + ": " + what;
        if (c.settleLogged++ % 6 == 0) log.info("{}", note);
        sleep(SETTLE_POLL_TICKS);
    }

    /** One look from the viewpoints: the machine boxes read (null without boxes) and the stasis cells seen. */
    private static final class Reading {
        MachineGuard.Snapshot machine;
        /** Stasis cell (its trapdoor) -> a pearl was seen in it. */
        final Map<Pos, Boolean> cells = new HashMap<>();
        /** Chunks of the stasis boxes searched for cells. */
        final Set<Long> stasisChunks = new HashSet<>();
        /** Viewpoint (or where the bot stood for it) -> the machine chunks in view from there. */
        final Map<Pos, Set<Long>> seenFrom = new LinkedHashMap<>();
        /** The machine chunks of the last {@code readAt}. */
        Set<Long> lastSeen = Set.of();
    }

    /**
     * The viewpoint nearest the middle of the art, or null without viewpoints: both layers and the machines around
     * them are in view from it. The reset wait reads from where its walk ended, so that walk ends here (live
     * 2026-11-14: a walk that began at the start stand ended on the far walkway, "the layers not in view", and the
     * machine chunks out of view kept their first reading: the wait could not end).
     */
    private @Nullable Pos artViewpoint() {
        Pos best = null;
        double bestD = Double.MAX_VALUE;
        double cx = platform.minX + 63.5, cz = platform.minZ + 63.5;
        for (Pos p : ride().viewpoints) {
            double d = (p.x() - cx) * (p.x() - cx) + (p.z() - cz) * (p.z() - cz);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /**
     * The viewpoints in walking order: each time the nearest one not visited yet from where the bot stands, and
     * {@code last} at the end when it is one of them (before the ride the start stand: the bot boards there; after
     * it {@link #artViewpoint}).
     */
    private List<Pos> viewpoints(Pos last) {
        MapArtConfig.RideCfg r = ride();
        List<Pos> left = new ArrayList<>(r.viewpoints.isEmpty() ? List.of(r.startStand) : r.viewpoints);
        boolean keepLast = last != null && left.remove(last);
        List<Pos> out = new ArrayList<>();
        Pos at = botFeet();
        while (!left.isEmpty()) {
            Pos best = null;
            double bestD = Double.MAX_VALUE;
            for (Pos p : left) {
                double d = (double) (p.x() - at.x()) * (p.x() - at.x()) + (double) (p.z() - at.z()) * (p.z() - at.z());
                if (d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
            left.remove(best);
            out.add(best);
            at = best;
        }
        if (keepLast) out.add(last);
        return out;
    }

    /** Chunk keys ({@link MachineGuard#chunkKey}) of the chunk columns {@code boxes} touch. */
    private static Set<Long> boxChunks(List<MapArtConfig.RegionCfg> boxes) {
        Set<Long> out = new LinkedHashSet<>();
        for (MapArtConfig.RegionCfg b : boxes) {
            if (b.min == null || b.max == null) continue;
            for (int cx = Math.min(b.min.x(), b.max.x()) >> 4; cx <= Math.max(b.min.x(), b.max.x()) >> 4; cx++)
                for (int cz = Math.min(b.min.z(), b.max.z()) >> 4; cz <= Math.max(b.min.z(), b.max.z()) >> 4; cz++)
                    out.add(MachineGuard.chunkKey(cx, cz));
        }
        return out;
    }

    /**
     * Walks the viewpoints in turn and reads at each (machine boxes, stasis cells and pearls); null while acting, the
     * whole reading at the end (the context is ready for the next one then).
     */
    private Reading viewpointReading(boolean beforeRide) {
        Ctx c = ctx;
        if (c.vpOrder == null) c.vpOrder = viewpoints(beforeRide ? ride().startStand : artViewpoint());
        List<Pos> vps = c.vpOrder;
        if (c.reading == null) c.reading = new Reading();
        // right after a login the platform's lethal edges are not known yet (unloaded cells count as lethal): wait
        if (!hazardCache.containsKey(platform.id) && c.attempt("vp-hazards") < 30) {
            sleep(20);
            return null;
        }
        while (c.vp < vps.size()) {
            Pos vp = vps.get(c.vp);
            if (!c.vpWalked) {
                c.vpWalked = true;
                Pos feet = botFeet();
                if (Math.abs(feet.x() - vp.x()) > 2 || Math.abs(feet.z() - vp.z()) > 2 || Math.abs(feet.y() - vp.y()) > 3) {
                    if (climbToPlatform()) {
                        c.vpWalked = false;
                        return null;
                    }
                    // a viewpoint out of reach is tried once more, then read from as near as the walk got (the verdict
                    // shows what was not seen)
                    run(bot.walkTo(vp, 2), null, r -> {
                        if (c.attempt("vp-walk-" + vp.key()) < 1) {
                            c.vpWalked = false;
                            sleep(40);
                            log.info("viewpoint {} not reached ({}); trying again", vp.key(), r);
                        } else log.warn("viewpoint {} not reached ({}); reading from {}", vp.key(), r, botFeet().key());
                    });
                    return null;
                }
            }
            if (!readAt(c.reading)) return null;
            c.reading.seenFrom.put(vp, c.reading.lastSeen);
            c.vp++;
            c.vpWalked = false;
        }
        Reading done = c.reading;
        c.reading = null;
        c.vp = 0;
        c.vpOrder = null;
        return done;
    }

    /** A reading where the bot stands now (no walk); null while scanning. */
    private Reading readHere() {
        Ctx c = ctx;
        if (c.reading == null) c.reading = new Reading();
        if (!readAt(c.reading)) return null;
        Reading done = c.reading;
        c.reading = null;
        return done;
    }

    /** Reads the loaded part of the machine boxes into {@code into} (budgeted per tick) and the stasis cells. True when done. */
    private boolean readAt(Reading into) {
        Ctx c = ctx;
        MapArtConfig.RideCfg r = ride();
        if (!r.machine.isEmpty()) {
            if (c.guardScan == null) c.guardScan = new MachineGuard.Scan(r.machine, guardExcluded());
            if (!c.guardScan.step(guardBlocks(), GUARD_BUDGET)) {
                sleep(1);
                return false;
            }
            MachineGuard.Snapshot s = c.guardScan.result();
            c.guardScan = null;
            into.lastSeen = s.chunkKeys();
            if (into.machine == null) into.machine = s;
            else MachineGuard.merge(into.machine, s);
        }
        lookAtStasis(into);
        return true;
    }

    /**
     * Finds the stasis cells (a trapdoor over water or a bubble column, or over one air block above it) in the loaded chunks of {@code ride.stasis} not
     * searched yet, and marks each cell within {@code ride.pearlSightRange} blocks (horizontal) of the bot: a pearl seen
     * in its column (from the cell down to 10 below) or not. A cell once seen with a pearl stays so for the reading.
     */
    private void lookAtStasis(Reading into) {
        MapArtConfig.RideCfg r = ride();
        if (r.stasis.isEmpty()) return;
        WorldView w = bot.world();
        for (MapArtConfig.RegionCfg box : r.stasis) {
            if (box.min == null || box.max == null) continue;
            int x0 = Math.min(box.min.x(), box.max.x()), x1 = Math.max(box.min.x(), box.max.x());
            int y0 = Math.min(box.min.y(), box.max.y()), y1 = Math.max(box.min.y(), box.max.y());
            int z0 = Math.min(box.min.z(), box.max.z()), z1 = Math.max(box.min.z(), box.max.z());
            for (int cx = x0 >> 4; cx <= x1 >> 4; cx++)
                for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                    long key = MachineGuard.chunkKey(cx, cz);
                    if (into.stasisChunks.contains(key) || !w.isLoaded(new Pos(cx << 4, y0, cz << 4))) continue;
                    into.stasisChunks.add(key);
                    for (int x = Math.max(x0, cx << 4); x <= Math.min(x1, (cx << 4) + 15); x++)
                        for (int z = Math.max(z0, cz << 4); z <= Math.min(z1, (cz << 4) + 15); z++)
                            for (int y = y0; y <= y1; y++) {
                                Pos p = new Pos(x, y, z);
                                if (!w.block(p).id().endsWith("_trapdoor")) continue;
                                // the pearl floats at the top of the bubble column, right under the trapdoor or one
                                // air block below it (the owner's cells: bubble column up to y162, air, trapdoor at y164)
                                String below = w.block(new Pos(x, y - 1, z)).id();
                                String below2 = w.block(new Pos(x, y - 2, z)).id();
                                if (fluidColumn(below) || (below.endsWith(":air") && fluidColumn(below2)))
                                    into.cells.putIfAbsent(p, false);
                            }
                }
        }
        double range = Math.max(1, r.pearlSightRange);
        double px = w.playerX(), pz = w.playerZ();
        List<WorldView.Thing> pearls = w.entities(Set.of("ender_pearl"));
        for (Map.Entry<Pos, Boolean> e : into.cells.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) continue;
            Pos p = e.getKey();
            double dx = p.x() + 0.5 - px, dz = p.z() + 0.5 - pz;
            if (dx * dx + dz * dz > range * range) continue;
            boolean seen = false;
            for (WorldView.Thing t : pearls)
                if ((int) Math.floor(t.x()) == p.x() && (int) Math.floor(t.z()) == p.z() && t.y() <= p.y() + 1
                    && t.y() >= p.y() - 10) seen = true;
            e.setValue(seen);
            if (!seen) into.cells.put(p, null); // looked at: no pearl
        }
    }

    private static boolean fluidColumn(String id) {
        return id.endsWith(":water") || id.endsWith(":bubble_column");
    }

    /**
     * What is wrong with the stasis cells of a reading: chunks of the boxes never in view, fewer cells than
     * {@code ride.stasisCells}, cells never within sight, cells without a pearl. Null when all is well (or no boxes).
     */
    private String pearlProblem(Reading rd) {
        MapArtConfig.RideCfg r = ride();
        if (r.stasis.isEmpty()) return null;
        int chunks = 0;
        for (MapArtConfig.RegionCfg box : r.stasis) {
            if (box.min == null || box.max == null) continue;
            for (int cx = Math.min(box.min.x(), box.max.x()) >> 4; cx <= Math.max(box.min.x(), box.max.x()) >> 4; cx++)
                for (int cz = Math.min(box.min.z(), box.max.z()) >> 4; cz <= Math.max(box.min.z(), box.max.z()) >> 4; cz++)
                    if (!rd.stasisChunks.contains(MachineGuard.chunkKey(cx, cz))) chunks++;
        }
        // with the number of cells configured, a chunk never in view is fine as long as every cell was found
        if (chunks > 0 && r.stasisCells <= 0) return chunks + " chunks of the stasis boxes never came into view from the"
            + " viewpoints (add a viewpoint, ride.viewpoints, or set ride.stasisCells)";
        if (r.stasisCells > 0 && rd.cells.size() < r.stasisCells)
            return "found " + rd.cells.size() + " cells, ride.stasisCells says " + r.stasisCells
                + (chunks > 0 ? " (" + chunks + " chunks of the stasis boxes never came into view from the viewpoints)" : "");
        List<Pos> empty = new ArrayList<>(), unseen = new ArrayList<>();
        for (Map.Entry<Pos, Boolean> e : rd.cells.entrySet()) {
            if (e.getValue() == null) empty.add(e.getKey());
            else if (!e.getValue()) unseen.add(e.getKey());
        }
        if (!empty.isEmpty()) return empty.size() + " of " + rd.cells.size() + " cells show no ender pearl, e.g. "
            + empty.getFirst().key();
        if (!unseen.isEmpty()) {
            // too far from every walkable viewpoint for the server to send their pearls (its tracking range): not
            // checkable before the launch; the guard after the ride is the backstop (docs/COLOR_SUPPRESSION.md)
            String msg = unseen.size() + " of " + rd.cells.size() + " stasis cells were never within " + r.pearlSightRange
                + " blocks of a viewpoint (e.g. " + unseen.getFirst().key() + "), so their pearls were not checked";
            log.warn("{}", msg);
            warnOnce("stasis-unseen-" + unseen.size(), label() + ": " + msg + ".");
        }
        log.info("stasis: {} of {} cells checked, all with a pearl", rd.cells.size() - unseen.size(), rd.cells.size());
        return null;
    }

    /** Non-air cells on both layers (art volumes and noobline rows); -1 when a chunk of them is not loaded. */
    private int layerBlocks() {
        return layerBlocks(-1);
    }

    /** Non-air blocks in the art volume of {@code layer} (0 bottom, 1 top; -1 both); -1 when not all in view. */
    private int layerBlocks(int layer) {
        WorldView w = bot.world();
        int n = 0;
        List<MapArtConfig.RegionCfg> boxes = SiteRules.suppressionArtCells(platform);
        for (int b = 0; b < boxes.size(); b++) {
            if (layer >= 0 && b != layer) continue;
            MapArtConfig.RegionCfg box = boxes.get(b);
            int x0 = Math.min(box.min.x(), box.max.x()), x1 = Math.max(box.min.x(), box.max.x());
            int y0 = Math.min(box.min.y(), box.max.y()), y1 = Math.max(box.min.y(), box.max.y());
            int z0 = Math.min(box.min.z(), box.max.z()), z1 = Math.max(box.min.z(), box.max.z());
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++) {
                    if (!w.isLoaded(new Pos(x, y0, z))) return -1;
                    for (int y = y0; y <= y1; y++) if (!w.block(new Pos(x, y, z)).isAir()) n++;
                }
        }
        return n;
    }

    private int guardChunksTotal() {
        return new MachineGuard.Scan(ride().machine, guardExcluded()).chunksTotal();
    }

    /**
     * The guard leaves out the art volumes, {@code ride.machineIgnore} and the cell of the booth's anvil: the anvil is
     * the bot's own working item, it wears with every name and then breaks, and RENAME places the next one. Live
     * 2026-11-16: the anvil broke at the seventh name, and "anvil -> air" in the muncher box BLOCKED the next ride.
     */
    private Predicate<Pos> guardExcluded() {
        List<MapArtConfig.RegionCfg> out = new ArrayList<>(SiteRules.suppressionArtCells(platform));
        out.addAll(ride().machineIgnore);
        Pos anvil = ride().anvil != null ? ride().anvil : config.site.anvil;
        return p -> p.equals(anvil) || SiteRules.regionAt(out, p.x(), p.y(), p.z()) != null;
    }

    private MachineGuard.Blocks guardBlocks() {
        WorldView w = bot.world();
        return new MachineGuard.Blocks() {
            @Override
            public boolean loaded(int x, int y, int z) {
                return w.isLoaded(new Pos(x, y, z));
            }

            @Override
            public String id(int x, int y, int z) {
                return w.block(new Pos(x, y, z)).id();
            }
        };
    }

    private String guardBlobName() {
        return "cs-machine-" + platform.id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_") + ".bin";
    }

    /** Platforms whose guard reference is known to lie in the store: read from it, or written and read back. */
    private final Set<String> guardStored = new HashSet<>();

    /**
     * The platform's guard reference: in memory, else from the store when taken with the same boxes; null = none. A
     * reference whose stored blob has been deleted since is dropped from memory too, so "delete the blob, then resume"
     * (the BLOCKED message) records a new one without a proxy restart (live 2026-11-15: the restart was needed).
     */
    private MachineGuard.Snapshot guardRef() {
        MachineGuard.Snapshot s = guardReference.get(platform.id);
        if (s != null) {
            if (!guardStored.contains(platform.id) || guardBlobThere()) return s;
            log.info("the machine guard reference {} was deleted: a new one is recorded at the next reading", guardBlobName());
            guardReference.remove(platform.id);
            guardStored.remove(platform.id);
            return null;
        }
        try {
            byte[] b = store.loadBlob(guardBlobName());
            if (b == null) return null;
            s = MachineGuard.read(new java.io.ByteArrayInputStream(b));
            if (s.layout() != MachineGuard.layoutOf(ride().machine)) {
                log.warn("the machine guard reference of {} was taken with other boxes: a new one is recorded", platform.id);
                return null;
            }
            guardReference.put(platform.id, s);
            guardStored.add(platform.id);
            return s;
        } catch (IOException | RuntimeException e) {
            log.warn("cannot read the machine guard reference {}: {}", guardBlobName(), e.toString());
            return null;
        }
    }

    /** True unless the store answers that the guard's blob is not there; a store that cannot be read counts as there. */
    private boolean guardBlobThere() {
        try {
            return store.loadBlob(guardBlobName()) != null;
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    private void saveGuardRef(MachineGuard.Snapshot s) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            MachineGuard.write(s, out);
            store.saveBlob(guardBlobName(), out.toByteArray());
            if (store.loadBlob(guardBlobName()) != null) guardStored.add(platform.id);
        } catch (IOException | RuntimeException e) {
            log.warn("cannot save the machine guard reference {}: {}", guardBlobName(), e.toString());
        }
    }

    // =====================================================================================
    // Cancellation
    // =====================================================================================

    private boolean jobCancelled(Job j) {
        Optional<Job> live = coord.job(j.id);
        if (live.isEmpty()) return true;
        JobStatus s = live.get().status;
        return s == JobStatus.CANCELLED || s == JobStatus.FAILED;
    }

    /**
     * The current job was cancelled: stop, but still nuke + clear the platform, then pick the next job. A BLOCKED job
     * leaves BLOCKED for the clean-up at once (what blocked it was that job's), unless the engine is paused or
     * safety-locked: then the clean-up starts on resume.
     */
    private void abortTile() {
        if (bandUnit()) { // a lane band never nukes: its cells stay for the tile's next holders
            log.info("job {} cancelled during {} of {}; the band is given back, nothing cleared", job.id, phase, label());
            forgetTileStart(); // the cancelled job's tile will not be stored: its start record goes now
            endUnit("job_cancelled");
            clearCurrent();
            go(Phase.SELECT);
            return;
        }
        aborting = true;
        boolean held = config.paused || safetyLocked;
        log.info("job {} cancelled during {}; {}", job.id, phase,
            held ? "the platform is cleared after resume (paused)" : "clearing the platform");
        notifier.info("Job cancelled", label() + (suppressionTile()
            ? ": stopping; the blocks on color-suppression platform " + platform.id + " stay (never cleared by the bot)."
            : held
            ? ": the engine is paused; it clears platform " + platform.id + " after `" + config.discord.commandPrefix
                + " resume`."
            : ": stopping; clearing platform " + platform.id + "."));
        switch (phase) {
            case IDLE, SELECT -> {
                forgetTileStart();
                endUnit("job_cancelled");
                clearCurrent();
                go(Phase.SELECT);
            }
            // a map already stored is still counted (it is in the output chest): COUNT runs to its end, then NUKE
            case COUNT, NUKE, CLEAR, WAIT_CLEAR, FINISH_TILE -> acted = true;
            // the colour-suppression ride is never broken off halfway: the bot may sit in the cart, the machines mid-cycle
            case RIDE, RIDE_END, RIDE_RESET -> block("cancelled during the ride (" + phase + "): the bot stops where it"
                + " is and does nothing more to the machinery. Finish by hand, or resume to let the bot finish the map");
            case RIDE_BOARD, RIDE_PRELOAD -> {
                if (bot.world().vehicle() != null) block("cancelled while in the cart before the launch: get out by hand");
                else go(Phase.NUKE);
            }
            case BLOCKED -> {
                Phase back = blockedFrom != null && blockedFrom.ordinal() >= Phase.COUNT.ordinal() ? blockedFrom
                    : tile.status == TileStatus.STORED ? afterStore() : Phase.NUKE;
                if (held) {
                    blockedFrom = back; // resume() goes on from there
                    acted = true;
                    saveCheckpoint();
                } else {
                    log.info("leaving BLOCKED ({}) for the clean-up: {}", blockedFrom, note);
                    blockedFrom = null;
                    clearAttempts = 0;
                    repairPasses = 0;
                    fillAttempts = 0;
                    foreignInspections = 0;
                    noProgressRounds = 0;
                    wakeTick = tick;
                    go(back);
                }
            }
            default -> go(tile.status == TileStatus.STORED ? afterStore() : Phase.NUKE);
        }
    }

    private void clearCurrent() {
        endUnit("set_aside"); // a unit still held here was not finished (done, cancelled and abandoned ones are null)
        job = null;
        tile = null;
        platform = null;
        // the openings stay while the bot still stands in them (updateOpenings): it leaves by the access path
        plan = null;
        expected = null;
        aborting = false;
        resumeCandidate = false;
        buildLanes = null;
        lanesCache = null;
        needsCache = null;
        deferred.clear();
        stuckCells.clear();
        recheckItems = null;
        endMaterialsWait();
        carryNext = null;
        carriedOver = false;
        carriedCells = 0;
        carriedRound = false;
        carriedRoundItems = Map.of();
        carriedRoundPlaced = -1;
        carriedRoundNoted = false;
        mapId = null;
        lockedMapId = null;
        storedMapId = null;
        freshMaps = 0;
        tilePictures.clear();
        restoredCheckpoint = null;
    }

    // =====================================================================================
    // Stock
    // =====================================================================================

    private boolean stockRoutine(boolean all) {
        return stockRoutine(all, null, null);
    }

    /**
     * The snapshot is past the optional {@code build.stockMaxAgeMinutes} ceiling. 0 (the default) = never: snapshots
     * are trusted whatever their age, because every way the stock changes is caught where it matters. The bot's own
     * withdrawals and deposits refresh the chest they open; chests filled since (the sorter after a clear) show up as
     * a STOCK_CHECK shortfall and its targeted re-look; chests emptied since (someone took items) show up as a short
     * withdrawal, which re-looks and re-plans the load (RESTOCK).
     */
    private boolean stale(StockIndex.Snapshot s, long now) {
        int minutes = config.build.stockMaxAgeMinutes;
        return minutes > 0 && now - s.scannedAt > minutes * 60_000L;
    }

    /**
     * Items of a withdrawal that the chest really lacked: fewer moved than planned and fewer left in the chest than
     * the missing amount (so not a full inventory).
     */
    private static Set<String> stockShort(Map<String, Integer> planned, TransferResult r) {
        Set<String> out = new LinkedHashSet<>();
        if (r == null || r.after() == null) return out;
        Map<String, Integer> moved = r.moved() == null ? Map.of() : r.moved();
        planned.forEach((id, want) -> {
            int got = moved.getOrDefault(id, 0);
            if (got < want && r.after().items().getOrDefault(id, 0) < want - got) out.add(id);
        });
        return out;
    }

    /**
     * Runs the bot's sweep planner ({@link BotActions#planVisits}) over {@code nearestFirst}; {@code done} gets every
     * container once in sweep order with its stand cell. When planning fails the nearest-first order is used.
     */
    private void planSweep(List<Pos> nearestFirst, String what, Consumer<List<ContainerVisit>> done) {
        planSweep(nearestFirst, null, what, done);
    }

    /**
     * {@link #planSweep(List, String, Consumer)} for a walk that goes on to {@code then} (a restock: the next lane's
     * start), so the sweep runs in the direction that ends nearer it; null = from the end nearer the bot.
     */
    private void planSweep(List<Pos> nearestFirst, Pos then, String what, Consumer<List<ContainerVisit>> done) {
        List<Pos> order = List.copyOf(nearestFirst);
        run(bot.planVisits(order, then, visits -> {
            List<ContainerVisit> all = complete(order, visits);
            log.info("{} sweep: {} containers from {} stand spots{}", what, all.size(), stops(all),
                unplanned(all) > 0 ? " (" + unplanned(all) + " without one, last)" : "");
            done.accept(all);
        }), null, r -> {
            log.warn("could not plan the {} sweep ({}); nearest first", what, r);
            done.accept(complete(order, List.of()));
        });
    }

    /** {@code visits} cut to {@code order}'s containers, each once, the missing ones appended without a stand cell. */
    private static List<ContainerVisit> complete(List<Pos> order, List<ContainerVisit> visits) {
        Set<Pos> want = new LinkedHashSet<>(order);
        List<ContainerVisit> out = new ArrayList<>();
        if (visits != null) {
            for (ContainerVisit v : visits) if (v != null && want.remove(v.container())) out.add(v);
        }
        for (Pos p : want) out.add(new ContainerVisit(p, null));
        return out;
    }

    /** Stand cells walked to in a sweep (consecutive visits from the same cell are one stop). */
    private static int stops(List<ContainerVisit> visits) {
        int n = 0;
        Pos last = null;
        for (ContainerVisit v : visits) {
            if (v.standAt() != null && !v.standAt().equals(last)) n++;
            last = v.standAt();
        }
        return n;
    }

    private static int unplanned(List<ContainerVisit> visits) {
        int n = 0;
        for (ContainerVisit v : visits) if (v.standAt() == null) n++;
        return n;
    }

    /** The withdrawals in sweep order with their stand cells; any the plan left out stay at the end. */
    private static void reorder(Deque<Withdrawal> wds, List<ContainerVisit> visits) {
        Map<Pos, Withdrawal> byChest = new LinkedHashMap<>();
        for (Withdrawal w : wds) byChest.put(w.chest(), w);
        wds.clear();
        for (ContainerVisit v : visits) {
            Withdrawal w = byChest.remove(v.container());
            if (w != null) wds.add(new Withdrawal(w.chest(), w.items(), v.standAt()));
        }
        wds.addAll(byChest.values());
    }

    /**
     * Containers worth a fresh look when {@code items} seem short: every stale one, those holding the items, plus
     * empty ones in the same or a neighbouring sorter column (|dx| ≤ 1, |dz| ≤ 2, any height) of a known holder. When
     * no container is known to hold an item, every empty container is a candidate. Containers scanned in the last
     * minute are left out.
     */
    private Set<Pos> shortageCandidates(Set<String> items) {
        Set<Pos> out = new LinkedHashSet<>();
        if (materialContainers == null) return out;
        long now = clock.millis();
        StockIndex st = coord.stock();
        synchronized (st) {
            List<Pos> holders = new ArrayList<>();
            boolean anyUnknown = false;
            for (String id : items) {
                List<Pos> h = st.containersWith(id);
                if (h.isEmpty()) anyUnknown = true;
                holders.addAll(h);
            }
            for (Pos p : scoped(materialContainers)) {
                if (scanFailures.getOrDefault(p, 0) >= SCAN_FAILURES_SKIP) continue;
                StockIndex.Snapshot s = st.containers.get(p.key());
                if (s != null && now - s.scannedAt < RESCAN_MIN_AGE_MS) continue;
                boolean holds = s != null && s.items.keySet().stream().anyMatch(items::contains);
                boolean empty = s == null || s.items.isEmpty();
                if (holds || s == null || stale(s, now)) out.add(p);
                else if (empty && (anyUnknown || holders.stream().anyMatch(h -> Math.abs(h.x() - p.x()) <= 1 && Math.abs(h.z() - p.z()) <= 2)))
                    out.add(p);
            }
        }
        return out;
    }

    /**
     * Discovery (first time or when forced) then scans: of all material containers ({@code all}), else of those never
     * scanned, those in {@code extra} (a targeted re-look) and those past the age ceiling ({@link #stale}; none by
     * default). With {@code relevant} set, a stale container is only rescanned if its last snapshot holds one of those
     * items (the skipped ones are counted in {@code ctx.staleSkipped}). The scans run in the bot's planned sweep
     * ({@link #planSweep}): one pass along the storage, every container opened from the first stand spot that sees it.
     * True while working.
     */
    private boolean stockRoutine(boolean all, Set<String> relevant, Set<Pos> extra) {
        return stockRoutine(ctx, all, relevant, extra, null);
    }

    /** The same sweep and snapshot handling, optionally restricted to one platform's near containers. */
    private boolean stockRoutine(Ctx c, boolean all, Set<String> relevant, Set<Pos> extra,
                                 @Nullable PlatformGeometry near) {
        if (near == null && !all && materialContainers == null) seedDiscovery();
        if (near == null && all) pendingRegions = null; // a requested scan discovers every region
        if (near != null ? !c.discoveryDone : materialContainers == null || (all && !c.discoveryDone)
            || (pendingRegions != null && !c.discoveryDone)
            || (scopePlatform() != null && !csDiscovered && !c.discoveryDone)) {
            if (discover(c, near)) return true;
        }
        Set<Pos> containers = near == null ? scoped(materialContainers) : c.discovered;
        if (c.scanQueue == null) {
            c.scannedContainers.clear(); // Freshness belongs to this sweep, not earlier scans in the same phase.
            long now = clock.millis();
            StockIndex st = coord.stock();
            List<Pos> q = new ArrayList<>();
            int skipped = 0;
            int unscanned = 0;
            int targeted = 0;
            synchronized (st) {
                Set<Integer> neededCols = csNeededColumns(relevant);
                for (Pos p : containers) {
                    StockIndex.Snapshot s = st.containers.get(p.key());
                    boolean wanted = extra != null && extra.contains(p);
                    // a colour-suppression job reads only the chest columns of the materials it needs (the index row says which)
                    if (!all && !wanted && neededCols != null && CsMaterials.inAny(CsMaterials.csRegions(config.site), p)
                        && !neededCols.contains(p.x())) continue;
                    if (!(all || wanted || s == null || stale(s, now))) continue;
                    if (scanFailures.getOrDefault(p, 0) >= SCAN_FAILURES_SKIP) {
                        skipped++;
                        if (near != null) c.scanFailed++;
                        continue;
                    }
                    if (!all && !wanted && s != null && relevant != null
                        && s.items.keySet().stream().noneMatch(relevant::contains)) {
                        c.staleSkipped++;
                        continue;
                    }
                    q.add(p);
                    if (s == null) unscanned++;
                    else if (wanted) targeted++;
                }
            }
            if (!q.isEmpty() || skipped > 0) {
                int old = q.size() - unscanned - targeted;
                String why = all ? "requested" : String.join(", ", java.util.stream.Stream.of(
                        unscanned > 0 ? unscanned + " never scanned" : "", targeted > 0 ? targeted + " targeted" : "",
                        old > 0 ? old + " stale" : "")
                    .filter(x -> !x.isEmpty()).toList());
                log.info("scanning {} material containers ({}){}{}", q.size(), why.isEmpty() ? "none" : why,
                    skipped > 0 ? "; skipping " + skipped + " that could not be scanned before" : "",
                    c.staleSkipped > 0 ? "; " + c.staleSkipped + " stale ones hold nothing needed now" : "");
            }
            List<Pos> nearest = byDistance(q);
            c.scanLater = null;
            c.replannedFor = null;
            c.replans = 0;
            if (config.build.nearRecheckAll && near == null && plan != null) {
                // the platform's own containers first, in a sweep of their own; the far ones after, planned when the near
                // ones are done. One plan over both left most near ones without a stand cell, opened last (live
                // 2026-10-29: "264 containers from 11 stand spots (199 without one, last)")
                List<Pos> own = new ArrayList<>();
                List<Pos> far = new ArrayList<>();
                for (Pos p : nearest) (farContainer(p) ? far : own).add(p);
                if (!own.isEmpty() && !far.isEmpty()) {
                    nearest = own;
                    c.scanLater = far;
                }
            }
            if (nearest.size() >= 2) {
                c.replannedFor = inView(nearest);
                planSweep(nearest, "scan", visits -> c.scanQueue = new ArrayDeque<>(visits));
                return true;
            }
            c.scanQueue = new ArrayDeque<>(complete(nearest, List.of()));
        }
        if (c.scanQueue.isEmpty() && c.scanLater != null) {
            List<Pos> later = byDistance(c.scanLater);
            c.scanLater = null;
            c.replannedFor = inView(later);
            c.replans = 0;
            if (later.size() >= 2) {
                planSweep(later, "scan", visits -> c.scanQueue = new ArrayDeque<>(visits));
                return true;
            }
            c.scanQueue = new ArrayDeque<>(complete(later, List.of()));
        }
        if (replanUnplanned(c)) return true;
        ContainerVisit visit = c.scanQueue.poll();
        if (visit != null) {
            Pos next = visit.container();
            run(bot.scan(next, visit.standAt(), snap -> {
                scanFailures.remove(next);
                c.scannedContainers.add(next);
                recordSnapshot(next, snap.items(), snap.freeSlots(), snap.totalSlots());
            }), null, r -> {
                c.scanFailed++;
                int n = scanFailures.merge(next, 1, Integer::sum);
                // the bot already tried every stand spot it found; the second failure skips it for the session
                if (n == SCAN_FAILURES_SKIP) log.warn("material container {} skipped until `scan`: {}", next, r);
                else if (n < SCAN_FAILURES_SKIP) log.info("scan of {} failed ({}x), retried later: {}", next, n, r);
            });
            return true;
        }
        if (!c.stockSaved) {
            c.stockSaved = true;
            saveStock();
            if (near == null && c.unloadedCells == 0 && !c.scannedContainers.isEmpty()) {
                // A normal full STOCK_CHECK/requested sweep also satisfies the near-first age limit.
                Set<PlatformGeometry> platforms = new HashSet<>();
                if (plan != null) platforms.add(plan.geo);
                for (PlatformCfg p : config.site.platforms)
                    platforms.add(new PlatformGeometry(p.minX, p.minZ, p.artY));
                for (PlatformGeometry geo : platforms) {
                    List<Pos> own = containers.stream()
                        .filter(p -> !FarStorage.far(geo, config.build.farStorageBlocks, p)).toList();
                    if (!own.isEmpty() && c.scannedContainers.containsAll(own)) markNearSwept(geo, clock.millis());
                }
            }
            if (c.scanFailed > 0) {
                List<String> bad = new ArrayList<>();
                scanFailures.forEach((p, n) -> {
                    if (n >= SCAN_FAILURES_SKIP && bad.size() < 5) bad.add(p.key());
                });
                log.warn("{} material container scans failed this round", c.scanFailed);
                if (!bad.isEmpty()) alertOnce("unscannable", "Material chests unreachable",
                    "Some material chests could not be opened twice and are skipped until `" + config.discord.commandPrefix
                        + " scan`; e.g. " + String.join(", ", bad) + ". Narrow site.storage.materialRegions to reachable chests.");
            }
        }
        return false;
    }

    /**
     * The next scan has no stand cell (its blocks were not in view when the sweep was planned): the containers of the
     * queue left without one that are in view now get a sweep plan of their own, as a requested scan's after its
     * discovery walk ({@code build.nearRecheckAll}; at most {@link #MAX_REPLANS} times a round, and never twice for the
     * same containers). True while it plans.
     */
    private boolean replanUnplanned(Ctx c) {
        ContainerVisit head = c.scanQueue.peek();
        if (!config.build.nearRecheckAll || head == null || head.standAt() != null || c.replans >= MAX_REPLANS) return false;
        WorldView w = bot.world();
        Set<Pos> now = new LinkedHashSet<>();
        for (ContainerVisit v : c.scanQueue) if (v.standAt() == null && w.isLoaded(v.container())) now.add(v.container());
        // only those that came into view since a plan of them (a plan that found no cell for them in view stands)
        if (c.replannedFor != null) now.removeAll(c.replannedFor);
        if (now.size() < 2) return false;
        if (c.replannedFor == null) c.replannedFor = new HashSet<>();
        c.replannedFor.addAll(now);
        c.replans++;
        List<ContainerVisit> rest = new ArrayList<>();
        for (ContainerVisit v : c.scanQueue) if (!now.contains(v.container())) rest.add(v);
        c.scanQueue = new ArrayDeque<>(rest);
        log.info("scan sweep: {} containers came into view without a stand cell; planning them again", now.size());
        planSweep(byDistance(new ArrayList<>(now)), "scan", visits -> {
            Deque<ContainerVisit> q = new ArrayDeque<>(visits);
            q.addAll(rest);
            c.scanQueue = q;
        });
        return true;
    }

    /** The containers of {@code l} whose block is loaded now (a sweep plan can give them a stand cell). */
    private Set<Pos> inView(Collection<Pos> l) {
        WorldView w = bot.world();
        Set<Pos> out = new HashSet<>();
        for (Pos p : l) if (w.isLoaded(p)) out.add(p);
        return out;
    }

    /** {@link #replanUnplanned} at most this many times per scan round. */
    private static final int MAX_REPLANS = 3;

    /** The material regions as one string: a stored discovery is only reused while it matches ({@link StockIndex#discovered}). */
    private String regionSignature() {
        StringBuilder sb = new StringBuilder();
        for (MapArtConfig.RegionCfg r : CsMaterials.allRegions(config.site))
            if (r != null && r.min != null && r.max != null) sb.append(r.min.key()).append('>').append(r.max.key()).append(';');
        return sb.toString();
    }

    /**
     * After a restart, {@code materialContainers} is null and the first STOCK_CHECK would discover: walk to each
     * region's middle to load it and read every block (live 2026-10-13: 61 s, two walks of 188 blocks). With
     * {@code build.seedDiscoveryFromStock} the containers the last complete discovery stored with the stock index are
     * taken instead, when the material regions are the same; those now in a protected region or a role of their own
     * (output chest, ...) are dropped. A requested scan ({@code !map scan}) still discovers. With
     * {@code build.seedDiscoveryPartial} a changed set of storage regions takes the stored containers of the regions
     * still the same and leaves only the new or changed ones to discover ({@link DiscoverySeed}, {@link #pendingRegions}).
     */
    private void seedDiscovery() {
        if (!config.build.seedDiscoveryFromStock) return;
        List<Pos> known = coord.stock().discoveredFor(regionSignature());
        DiscoverySeed.Result part = null;
        if (known == null && config.build.seedDiscoveryPartial && scopePlatform() == null && config.site.storage != null
            && config.site.storage.materialRegions != null) {
            StockIndex st = coord.stock();
            synchronized (st) {
                part = DiscoverySeed.of(st.discovered == null ? null : st.discovered.stream().map(Pos::parseKey).toList(),
                    st.discoveredRegions, config.site.storage.materialRegions);
            }
            if (part != null) known = part.known();
        }
        if (known == null) return;
        Set<Pos> roles = roleContainers();
        Set<Pos> out = new LinkedHashSet<>();
        for (Pos p : known) {
            if (roles.contains(p) || SiteRules.protectedRegionAt(config.site, p) != null) continue;
            for (MapArtConfig.RegionCfg r : CsMaterials.allRegions(config.site)) {
                if (r != null && r.contains(p)) {
                    out.add(p);
                    break;
                }
            }
        }
        if (out.isEmpty()) return;
        materialContainers = out;
        csDiscovered = out.stream().anyMatch(q -> CsMaterials.inAny(CsMaterials.csRegions(config.site), q));
        if (part == null) {
            log.info("{} material containers taken from the stock index (the last discovery; no walk to the regions)",
                out.size());
            return;
        }
        Set<String> pending = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        for (MapArtConfig.RegionCfg r : part.pending()) {
            pending.add(DiscoverySeed.key(r));
            names.add(r.name != null ? "\"" + r.name + "\"" : r.min.key() + " to " + r.max.key());
        }
        pendingRegions = pending;
        log.info("{} material containers taken from the stock index (the last discovery, regions unchanged); {} new or"
            + " changed material regions to discover: {}", out.size(), pending.size(), String.join(", ", names));
    }

    private boolean discover(Ctx c, @Nullable PlatformGeometry near) {
        // a colour-suppression job finds its own chests (the zones around them are open only then), others the site's
        List<MapArtConfig.RegionCfg> regions = scopePlatform() != null ? CsMaterials.csRegions(config.site)
            : config.site.storage.materialRegions;
        // a partial seed (seedDiscovery): only the regions it left to discover, the seeded containers stay
        Set<String> only = near == null && scopePlatform() == null ? pendingRegions : null;
        if (c.discovered == null) c.discovered = new LinkedHashSet<>();
        while (c.regionIdx < regions.size()) {
            MapArtConfig.RegionCfg r = regions.get(c.regionIdx);
            if (r == null || r.min == null || r.max == null || (only != null && !only.contains(DiscoverySeed.key(r)))) {
                c.regionIdx++;
                continue;
            }
            if (near != null) {
                r = nearRegion(r, near);
                if (r == null) {
                    c.regionIdx++;
                    continue;
                }
            }
            Pos centre = new Pos(near == null ? (r.min.x() + r.max.x()) / 2
                : Math.clamp(near.center().x(), r.min.x(), r.max.x()), (r.min.y() + r.max.y()) / 2,
                near == null ? (r.min.z() + r.max.z()) / 2 : Math.clamp(near.center().z(), r.min.z(), r.max.z()));
            if (!c.regionWalked) {
                c.regionWalked = true;
                // only to load it: from the middle the scan sweep would walk to one end and back along it all
                if (!regionLoaded(r, centre)) {
                    run(bot.walkTo(centre, 3));
                    return true;
                }
            }
            if (!regionLoaded(r, centre) && c.regionWait < LOAD_WAIT_TICKS / 3) {
                c.regionWait += POLL_TICKS;
                sleep(POLL_TICKS);
                return true;
            }
            run(new ContainerDiscovery(bot.world(), r, roleContainers(), res -> {
                for (Pos p : res.containers()) {
                    // a chest in a protected region is never opened: not material stock
                    if (near != null && FarStorage.far(near, config.build.farStorageBlocks, p)) continue;
                    if (SiteRules.protectedRegionAt(config.site, p) == null) c.discovered.add(p);
                    else log.info("material container {} lies in a protected region; not used", p);
                }
                c.unloadedCells += res.unloaded();
            }), () -> {
                c.regionIdx++;
                c.regionWalked = false;
                c.regionWait = 0;
            });
            return true;
        }
        c.discoveryDone = true;
        if (near != null) {
            // Drop vanished near chests only after complete discovery; retain every far snapshot.
            if (c.unloadedCells == 0) {
                StockIndex st = coord.stock();
                Map<String, Integer> lost = new TreeMap<>();
                int[] dropped = {0};
                synchronized (st) {
                    st.containers.entrySet().removeIf(e -> {
                        Pos p = Pos.parseKey(e.getKey());
                        boolean gone = isMaterialContainer(p) && !FarStorage.far(near, config.build.farStorageBlocks, p)
                            && !c.discovered.contains(p);
                        if (gone) {
                            dropped[0]++;
                            e.getValue().items.forEach((id, n) -> lost.merge(id, n, Integer::sum));
                        }
                        return gone;
                    });
                }
                // what the index forgets here is never counted again until a scan finds it: said, so a lost count
                // can be traced (live 2026-10-29: 832 white carpet left the index between two looks, cause unknown)
                if (!lost.isEmpty())
                    log.info("near discovery: {} near containers in the stock index are gone; their counts leave it: {}",
                        dropped[0], lost);
                if (materialContainers != null)
                    materialContainers.removeIf(p -> !FarStorage.far(near, config.build.farStorageBlocks, p)
                        && !c.discovered.contains(p));
            }
            return false; // Never replace the full discovery with a near subset.
        }
        if (scopePlatform() != null) csDiscovered = true;
        Set<Pos> merged = new LinkedHashSet<>(c.discovered);
        if (materialContainers != null) {
            // the other storage's containers stay (a CS job's discovery finds only the CS chests, and the reverse); after
            // a partial seed every seeded container stays
            for (Pos q : materialContainers)
                if (only != null || CsMaterials.inAny(CsMaterials.csRegions(config.site), q) != (scopePlatform() != null))
                    merged.add(q);
        }
        materialContainers = merged;
        if (only != null) {
            pendingRegions = null;
            log.info("found {} material containers in {} new or changed regions ({} positions not loaded); {} in all",
                c.discovered.size(), only.size(), c.unloadedCells, materialContainers.size());
        } else {
            log.info("found {} material containers in {} regions ({} positions not loaded)",
                materialContainers.size(), regions.size(), c.unloadedCells);
        }
        if (pendingRegions != null) {
            // a colour-suppression job's discovery while storage regions still wait: not a complete discovery to store
            log.info("material regions still to discover after this job's: not stored as the discovery");
        } else if (c.unloadedCells > 0) {
            warnOnce("discovery-unloaded", c.unloadedCells + " storage positions were not loaded during discovery; "
                + "make each material region fit in view distance of its center");
        } else {
            StockIndex st = coord.stock();
            synchronized (st) {
                st.containers.keySet().removeIf(k -> !materialContainers.contains(Pos.parseKey(k)));
                st.setDiscovered(materialContainers, regionSignature());
            }
            saveStock();
        }
        if (materialContainers.isEmpty() && !regions.isEmpty())
            alertOnce("no-containers", "No material chests found", "No chest/barrel found in site.storage.materialRegions.");
        return false;
    }

    /** Clip discovery to the near area; a far region is never walked to for a near sweep. */
    private MapArtConfig.RegionCfg nearRegion(MapArtConfig.RegionCfg r, PlatformGeometry geo) {
        int blocks = config.build.farStorageBlocks;
        if (blocks <= 0) return r;
        int minX = Math.max(r.min.x(), geo.minX() - blocks);
        int maxX = Math.min(r.max.x(), geo.minX() + TileArt.SIZE - 1 + blocks);
        int minZ = Math.max(r.min.z(), geo.minZ() - blocks);
        int maxZ = Math.min(r.max.z(), geo.minZ() + TileArt.SIZE - 1 + blocks);
        if (minX > maxX || minZ > maxZ) return null;
        int nearestX = Math.clamp(geo.center().x(), minX, maxX);
        int nearestZ = Math.clamp(geo.center().z(), minZ, maxZ);
        if (FarStorage.distanceToArea(geo, nearestX, nearestZ) > blocks) return null;
        MapArtConfig.RegionCfg clipped = new MapArtConfig.RegionCfg();
        clipped.min = new Pos(minX, r.min.y(), minZ);
        clipped.max = new Pos(maxX, r.max.y(), maxZ);
        return clipped;
    }

    /** Actual near index counts, independent of the far-storage and batching kill switches. */
    private Map<String, Integer> nearCounts(PlatformGeometry geo) {
        Map<String, Integer> out = new TreeMap<>();
        StockIndex st = coord.stock();
        synchronized (st) {
            for (var e : st.containers.entrySet()) {
                Pos p = Pos.parseKey(e.getKey());
                if (!isMaterialContainer(p) || FarStorage.far(geo, config.build.farStorageBlocks, p)) continue;
                e.getValue().items.forEach((id, n) -> out.merge(id, n, Integer::sum));
            }
        }
        return out;
    }

    private Map<String, Integer> nearAvailable(PlatformGeometry geo) {
        Map<String, Integer> out = nearCounts(geo);
        bot.inventory().contents().forEach((id, n) -> out.merge(id, n, Integer::sum));
        return out;
    }

    /** The near look under way (a phase's first step): true while it runs. */
    private boolean resumeNearScan() {
        Ctx c = ctx;
        return c.nearScan != null && nearScanFirst(c.nearGeometry, c.nearItems, c.nearReason, c.nearWantFull);
    }

    /**
     * Near storage first ({@code build.nearScanFirst}), true while it runs.
     * A targeted look first: the near containers that hold or could hold {@code items} ({@link #nearCandidates}),
     * once per phase and, per item, once per {@code build.nearScanMaxAgeMinutes}. With {@code full} (still short, a
     * far trip or a hold follows: RESTOCK, STOCK_CHECK; not SELECT, which only orders the tiles) the targeted look then
     * returns true once, the caller decides again on the fresh index and, still short, gets a sweep of every near
     * container, at most once per {@code build.nearScanFullMaxAgeMinutes}: for stock put by hand into an unrelated
     * chest. Both count when tried, also with failed scans (those chests are retried by the usual rule).
     * Live 2026-10-13 (49b696b): every decision swept all 118 soul sand containers, twice in a row at one map
     * boundary (2:09 and 1:38), the first with 59 failed scans and so not counted.
     */
    private boolean nearScanFirst(PlatformGeometry geo, Set<String> items, String reason, boolean full) {
        return nearScanFirst(geo, items, reason, full, Math.max(0, config.build.nearScanMaxAgeMinutes) * 60_000L);
    }

    /** {@link #nearScanFirst(PlatformGeometry, Set, String, boolean)} with a look older than {@code ageMs} due again. */
    private boolean nearScanFirst(PlatformGeometry geo, Set<String> items, String reason, boolean full, long ageMs) {
        return nearScanFirst(geo, items, reason, full, ageMs, false);
    }

    /**
     * A waiting look ({@code build.nearRecheckAll}): every near container of
     * {@code geo}, not only those the stock index says hold or could hold {@code items}, unless a full near sweep ran in
     * the last {@code build.nearRecheckAllSeconds} or in this phase (then no look: the index is that fresh). Off: the
     * targeted look of {@link #nearScanFirst(PlatformGeometry, Set, String, boolean, long)}. Live 2026-10-29: white
     * carpet put by hand into another platform chest; the targeted looks read 5 and 4 chests and said "now 0 near",
     * and the bot went to far storage and a 264-container sweep.
     */
    private boolean nearLookAll(PlatformGeometry geo, Set<String> items, String reason, boolean full, long ageMs) {
        return nearScanFirst(geo, items, reason, full, ageMs, true);
    }

    private boolean nearLookAll(PlatformGeometry geo, Set<String> items, String reason, boolean full) {
        return nearLookAll(geo, items, reason, full, Math.max(0, config.build.nearScanMaxAgeMinutes) * 60_000L);
    }

    private boolean nearScanFirst(PlatformGeometry geo, Set<String> items, String reason, boolean full, long ageMs,
                                  boolean all) {
        if (!config.build.nearScanFirst) {
            ctx.nearScan = null;
            return false;
        }
        Ctx c = ctx;
        if (c.nearScan == null) {
            long now = clock.millis();
            long age = ageMs;
            restoreNearTimes(geo, now);
            Map<String, Long> looked = nearLookedAt.getOrDefault(geo, Map.of());
            Set<String> due = new TreeSet<>();
            boolean sweep;
            if (all && config.build.nearRecheckAll) {
                // every near container, at most once per nearRecheckAllSeconds (and once per phase)
                sweep = !geo.equals(c.nearFullChecked) && !recent(nearScannedAt.get(geo),
                    Math.max(0, config.build.nearRecheckAllSeconds) * 1000L, now);
            } else {
                if (!geo.equals(c.nearChecked) && !recent(nearScannedAt.get(geo), age, now))
                    for (String id : items) if (!recent(looked.get(id), age, now)) due.add(id);
                sweep = full && due.isEmpty() && !geo.equals(c.nearFullChecked) && !recent(nearScannedAt.get(geo),
                    Math.max(0, config.build.nearScanFullMaxAgeMinutes) * 60_000L, now);
            }
            if (due.isEmpty() && !sweep) return false;
            c.nearScan = new Ctx();
            c.nearGeometry = geo;
            c.nearReason = reason;
            c.nearItems = Set.copyOf(items);
            c.nearWantFull = full;
            c.nearFull = due.isEmpty();
            c.nearDue = due;
            c.nearExtra = null;
        }
        Ctx scan = c.nearScan;
        if (!c.nearFull && c.nearExtra == null) {
            // the candidates among the near containers found now (the full discovery may not have run yet)
            if (!scan.discoveryDone && discover(scan, geo)) return true;
            c.nearExtra = nearCandidates(geo, c.nearDue, scan.discovered);
        }
        // targeted: the candidates (and near containers never read); full: every near container, recent ones too
        if (c.nearFull ? stockRoutine(scan, true, null, null, geo) : stockRoutine(scan, false, c.nearDue, c.nearExtra, geo))
            return true;
        long now = clock.millis();
        c.nearScan = null;
        c.nearRefreshed = true;
        if (materialContainers != null) materialContainers.addAll(scan.discovered);
        Map<String, Long> looked = nearLookedAt.computeIfAbsent(geo, g -> new HashMap<>());
        if (c.nearFull) {
            c.nearFullChecked = geo;
            nearScannedAt.put(geo, now);
        } else {
            c.nearChecked = geo;
        }
        Set<String> lookedFor = c.nearFull ? c.nearItems : c.nearDue;
        for (String id : lookedFor) looked.put(id, now);
        if (config.build.nearScanPersist) {
            StockIndex st = coord.stock();
            String key = StockIndex.platformKey(geo.minX(), geo.minZ(), geo.artY());
            if (c.nearFull) st.nearSwept(key, now);
            st.nearLooked(key, lookedFor, now);
            saveStock();
        }
        if (scan.scanFailed > 0 || scan.unloadedCells > 0)
            log.warn("near storage {} incomplete: {} failed or skipped scans, {} unloaded positions; those chests are"
                + " retried by the usual rule", c.nearFull ? "sweep" : "look", scan.scanFailed, scan.unloadedCells);
        Map<String, Integer> counts = nearCounts(geo);
        String what = c.nearFull ? "re-scanned (every near container)" : "re-checked (" + scan.scannedContainers.size()
            + " of the near containers that hold or could hold them)";
        for (String id : new TreeSet<>(c.nearFull ? c.nearItems : c.nearDue))
            log.info("near storage {} {}: {} now {} near", what, c.nearReason, id, counts.getOrDefault(id, 0));
        // a far trip to follow: the caller decides again on the fresh index next tick, then asks for the full sweep
        return c.nearWantFull && !c.nearFull;
    }

    private static boolean recent(@Nullable Long at, long age, long now) {
        return at != null && age > 0 && now - at < age;
    }

    /** A full near sweep of {@code geo} at {@code at}: this run's memory, and the stock index ({@code build.nearScanPersist}). */
    private void markNearSwept(PlatformGeometry geo, long at) {
        nearScannedAt.put(geo, at);
        if (!config.build.nearScanPersist) return;
        coord.stock().nearSwept(StockIndex.platformKey(geo.minX(), geo.minZ(), geo.artY()), at);
        saveStock();
    }

    /** Platforms whose near look times this engine run took over from the stock index. */
    private final Set<PlatformGeometry> nearRestored = new HashSet<>();

    /**
     * Once per platform and engine run ({@code build.nearScanPersist}): the near sweep and look times the stock index
     * kept from before a restart join this run's (the later of both counts); times ahead of {@code now} (a clock
     * change) are dropped, so they can never keep a look from coming due.
     */
    private void restoreNearTimes(PlatformGeometry geo, long now) {
        if (!config.build.nearScanPersist || !nearRestored.add(geo)) return;
        StockIndex st = coord.stock();
        String key = StockIndex.platformKey(geo.minX(), geo.minZ(), geo.artY());
        Long swept = st.nearSweptAt(key);
        int n = 0;
        if (swept != null && swept <= now) {
            nearScannedAt.merge(geo, swept, Math::max);
            n++;
        }
        Map<String, Long> looked = nearLookedAt.computeIfAbsent(geo, g -> new HashMap<>());
        for (var e : st.nearLookedAt(key).entrySet()) {
            if (e.getValue() == null || e.getValue() > now) continue;
            looked.merge(e.getKey(), e.getValue(), Math::max);
            n++;
        }
        if (n > 0) log.info("near storage times restored from the stock index: last full sweep {} min ago, {} item looks",
            swept != null && swept <= now ? (now - swept) / 60_000 : "never", looked.size());
    }

    /**
     * Near containers worth a targeted look for {@code items}: never read, stale ({@link #stale}), holding one of them
     * or having held one in an earlier snapshot (a sorter chest the bot emptied, which the sorter fills again:
     * {@link StockIndex.Snapshot#held}), and empty ones in the same or a neighbouring sorter column (|dx| ≤ 1,
     * |dz| ≤ 2) of such a holder. An item no near container ever held gets no targeted look: the full near sweep
     * before a far trip or a hold covers it. Those read in the last minute and those skipped after two failed scans are
     * left out, as in {@link #shortageCandidates}.
     */
    private Set<Pos> nearCandidates(PlatformGeometry geo, Set<String> items, Collection<Pos> containers) {
        Set<Pos> out = new LinkedHashSet<>();
        long now = clock.millis();
        int blocks = config.build.farStorageBlocks;
        StockIndex st = coord.stock();
        synchronized (st) {
            List<Pos> holders = new ArrayList<>();
            for (Pos p : containers) {
                StockIndex.Snapshot s = st.containers.get(p.key());
                if (s != null && !FarStorage.far(geo, blocks, p) && holds(s, items)) holders.add(p);
            }
            for (Pos p : containers) {
                if (FarStorage.far(geo, blocks, p) || scanFailures.getOrDefault(p, 0) >= SCAN_FAILURES_SKIP) continue;
                StockIndex.Snapshot s = st.containers.get(p.key());
                if (s != null && now - s.scannedAt < RESCAN_MIN_AGE_MS) continue;
                if (s == null || stale(s, now) || holds(s, items)) out.add(p);
                else if (s.items.isEmpty()
                    && holders.stream().anyMatch(h -> Math.abs(h.x() - p.x()) <= 1 && Math.abs(h.z() - p.z()) <= 2))
                    out.add(p);
            }
        }
        return out;
    }

    /** The snapshot holds one of {@code items} or held one before. */
    private static boolean holds(StockIndex.Snapshot s, Set<String> items) {
        for (String id : items)
            if (s.items.getOrDefault(id, 0) > 0 || s.held != null && s.held.contains(id)) return true;
        return false;
    }

    private boolean regionLoaded(MapArtConfig.RegionCfg r, Pos centre) {
        WorldView w = bot.world();
        return w.isLoaded(centre) && w.isLoaded(r.min) && w.isLoaded(r.max)
            && w.isLoaded(new Pos(r.min.x(), r.min.y(), r.max.z())) && w.isLoaded(new Pos(r.max.x(), r.min.y(), r.min.z()));
    }

    private Set<Pos> roleContainers() {
        MapArtConfig.StorageCfg s = config.site.storage;
        Set<Pos> out = new LinkedHashSet<>();
        for (List<Pos> l : List.of(s.returnChests, s.mapChests, s.paneChests, s.outputChests, s.xpChests,
            s.anvilChests, s.toolChests, s.foodChests, s.bootChests, s.scrapMapChests, s.rocketChests)) {
            for (Pos p : l) if (p != null) out.add(p);
        }
        for (MapArtConfig.PlatformCfg pl : config.site.platforms) {
            if (pl != null && pl.returnChests != null) for (Pos p : pl.returnChests) if (p != null) out.add(p);
        }
        if (kit.found() != null) out.add(kit.found()); // the rocket chest a sign names (build.elytraLegs)
        return out;
    }

    /** The containers of {@code all} that the current job's storage covers ({@link #scopePlatform}); null stays null. */
    private Set<Pos> scoped(Set<Pos> all) {
        if (all == null) return null;
        PlatformCfg cs = scopePlatform();
        Set<Pos> out = new LinkedHashSet<>();
        for (Pos q : all) if (CsMaterials.inScope(config.site, cs, q)) out.add(q);
        return out;
    }

    /** The x of the chest columns that hold {@code items} (the index row), or null when unknown or not a CS job. */
    private @Nullable Set<Integer> csNeededColumns(@Nullable Set<String> items) {
        if (items == null || csColumns == null || scopePlatform() == null) return null;
        Set<Integer> out = new java.util.HashSet<>();
        for (String id : items) {
            Integer x = csColumns.get(id);
            if (x == null) return null; // an item the row does not name: look everywhere
            out.add(x);
        }
        return out;
    }

    private boolean isMaterialContainer(Pos p) {
        if (!CsMaterials.inScope(config.site, scopePlatform(), p)) return false;
        if (materialContainers != null) return materialContainers.contains(p);
        if (roleContainers().contains(p)) return false;
        for (MapArtConfig.RegionCfg r : CsMaterials.allRegions(config.site))
            if (r != null && r.min != null && r.max != null && r.contains(p)) return true;
        return false;
    }

    /**
     * The colour-suppression platform whose own chests the current job uses ({@code platforms[cs].materialRegions}), or
     * null: any other job, or none configured. A colour-suppression job restocks and checks stock there only.
     */
    private @Nullable PlatformCfg scopePlatform() {
        if (job == null || !job.isSuppression()) return null;
        for (PlatformCfg p : config.site.platforms)
            if (p != null && p.enabled && p.isColorSuppression() && !p.materialRegions.isEmpty()) return p;
        return null;
    }

    /** The stock index sees the current job's storage only ({@link #scopePlatform}). Before every step. */
    private void updateStockScope() {
        PlatformCfg cs = scopePlatform();
        if (CsMaterials.live() == null) {
            StockIndex st = coord.stock();
            synchronized (st) {
                if (st.csPalette != null && !st.csPalette.isEmpty()) CsMaterials.setLive(new TreeSet<>(st.csPalette));
            }
        }
        boolean any = !CsMaterials.csRegions(config.site).isEmpty();
        coord.stock().setScope(any ? p -> CsMaterials.inScope(config.site, cs, p) : null);
    }

    /**
     * A colour-suppression job may use only the blocks of its material chests: the index row's floor blocks (read from
     * the world when in view, else the last read, else {@code allowedBlocks}, else what the CS chests held when last
     * scanned). A block outside them blocks the job with the names. True when blocked.
     */
    private boolean csPaletteRefused() {
        PlatformCfg cs = scopePlatform();
        if (cs == null && (job == null || !job.isSuppression())) return false;
        if (cs == null) {
            for (PlatformCfg p : config.site.platforms) if (p != null && p.enabled && p.isColorSuppression()) cs = p;
        }
        if (cs == null) return false;
        WorldView w = bot.world();
        Map<String, Integer> row = CsMaterials.row(cs.materialRow, pos -> w.isLoaded(pos) ? w.block(pos).id() : null);
        if (row != null) {
            csColumns = row;
            CsMaterials.setLive(row.keySet());
            StockIndex st = coord.stock();
            boolean changed;
            synchronized (st) {
                changed = st.csPalette == null || !new TreeSet<>(st.csPalette).equals(new TreeSet<>(row.keySet()));
                if (changed) st.csPalette = new ArrayList<>(row.keySet());
            }
            if (changed) saveStock();
        }
        Set<String> fromRow = row != null ? row.keySet() : CsMaterials.live();
        Set<String> allowed = CsMaterials.allowed(cs, fromRow);
        if (allowed == null && !cs.materialRegions.isEmpty()) {
            Set<String> held = coord.stock().totals().keySet();
            if (!held.isEmpty()) allowed = new TreeSet<>(held);
        }
        Set<String> bad = CsMaterials.refused(job.materials.keySet(), allowed);
        if (bad.isEmpty()) return false;
        block("color-suppression map " + label() + " uses blocks the color-suppression chests do not hold: "
            + String.join(", ", bad) + " (allowed: " + (allowed == null ? "any" : String.join(", ", allowed)) + ")");
        return true;
    }

    /** What an open material container held: to the shared stock index, compare-and-set ({@link SharedStock}). */
    private void recordSnapshot(Pos p, Map<String, Integer> items, int free, int total) {
        if (!isMaterialContainer(p)) return;
        sharedStock.record(p, items, free, total, clock.millis());
    }

    private void afterTransfer(Pos chest, TransferResult r) {
        if (r == null || r.after() == null) return;
        recordSnapshot(chest, r.after().items(), r.after().freeSlots(), r.after().totalSlots());
    }

    /**
     * Splits a withdraw plan over material containers by their last snapshots; unknown remainder → c.unavailable. With
     * {@code serve} (far storage on: item → cells the load serves) near containers go first and a far container gives
     * at most what those cells need beyond the inventory and the near part ({@link FarStorage#farCap}); the rest of a
     * whole-stack request is not needed and is not fetched from far. With {@code build.farLoadFill} an item this split
     * takes from far storage anyway comes from there first, all the load serves of it ({@link FarLoadFill}).
     */
    private Deque<Withdrawal> allocate(Map<String, Integer> withdraw, Ctx c, @Nullable Map<String, Integer> serve) {
        c.fillLeftNear.clear();
        // this split's own remainder: a plan dropped after a near look ("before a far trip") left its remainder here,
        // and the next split added to it (a stale "not in stock index", and pickup stands for stock that is there)
        c.unavailable.clear();
        Map<Pos, Map<String, Integer>> byChest = new LinkedHashMap<>();
        InventoryView inv = bot.inventory();
        StockIndex st = coord.stock();
        synchronized (st) {
            // the chest holding the item closest to the platform first, so each platform uses its own sorter and another
            // platform's (enabled or not) only when that runs dry; the fullest first among equally close ones
            for (var e : withdraw.entrySet()) {
                int left = e.getValue();
                if (left <= 0) continue;
                List<Pos> chests = withdrawOrder(st.containersWith(e.getKey()));
                int taken = 0; // near and far so far: the far cap counts both
                boolean capped = false;
                // the sorter returns it (sorterRefills): never a far container, not even for a load's last few
                // (live 2026-10-14 00:53: a far trip for 5 white carpet beyond the near chests' 860)
                boolean lagging = c.sorterLag.containsKey(e.getKey());
                // build.farLoadFill: a load that goes far for the item anyway takes all it serves of it there
                String id = e.getKey();
                boolean fill = serve != null && config.build.farLoadFill && !lagging
                    && FarLoadFill.goesFar(left, chests, this::farContainer, p -> materialStock(st, p, id),
                        serve.getOrDefault(id, 0), inv.count(id));
                if (fill) chests = FarLoadFill.farFirst(chests, this::farContainer);
                Map<Pos, Integer> given = new HashMap<>();
                int fromFar = 0;
                for (Pos chest : chests) {
                    if (left <= 0) break;
                    if (!isMaterialContainer(chest)) continue;
                    if (lagging && farContainer(chest)) continue;
                    StockIndex.Snapshot s = st.containers.get(chest.key());
                    int take = Math.min(left, s == null ? 0 : s.items.getOrDefault(e.getKey(), 0));
                    boolean far = serve != null && farContainer(chest);
                    // filled from far: the near containers give the rest of the served cells, not the stack round-up
                    if (far || fill) {
                        int cap = FarStorage.farCap(serve.getOrDefault(e.getKey(), 0), inv.count(e.getKey()), taken);
                        if (take > cap) capped = true;
                        take = Math.min(take, cap);
                    }
                    if (take <= 0) continue;
                    byChest.computeIfAbsent(chest, k -> new TreeMap<>()).merge(e.getKey(), take, Integer::sum);
                    given.merge(chest, take, Integer::sum);
                    left -= take;
                    taken += take;
                    if (far) fromFar += take;
                }
                int nearLeft = fill && fromFar > 0 ? nearLeft(st, chests, given, id) : 0;
                if (nearLeft > 0) c.fillLeftNear.put(id, nearLeft);
                if (left > 0 && !capped) c.unavailable.merge(e.getKey(), left, Integer::sum);
            }
        }
        Deque<Withdrawal> q = new ArrayDeque<>();
        for (Pos chest : byDistance(new ArrayList<>(byChest.keySet()))) q.add(new Withdrawal(chest, byChest.get(chest), null));
        return q;
    }

    /** {@code id} the near material containers among {@code chests} hold beyond what this split takes. */
    private int nearLeft(StockIndex st, List<Pos> chests, Map<Pos, Integer> given, String id) {
        int n = 0;
        for (Pos p : chests) if (!farContainer(p)) n += Math.max(0, materialStock(st, p, id) - given.getOrDefault(p, 0));
        return n;
    }

    /** {@code id} in the last snapshot of material container {@code p} (0: none, or no material container). */
    private int materialStock(StockIndex st, Pos p, String id) {
        if (!isMaterialContainer(p)) return 0;
        StockIndex.Snapshot s = st.containers.get(p.key());
        return s == null ? 0 : s.items.getOrDefault(id, 0);
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    /** True if the caller must stop this step: walking to the platform or waiting for its chunks. */
    private boolean awaitPlatformLoaded() {
        if (plan.allLoaded(bot.world())) return false;
        // a colour-suppression platform right after a login: its lethal edges count every unloaded cell as lethal, so
        // a walk now would be refused; the chunks come in by themselves within a few seconds
        if (suppressionTile() && !hazardCache.containsKey(platform.id) && ctx.attempt("hazards-login") < 30) {
            sleep(20);
            return true;
        }
        if (!ctx.walkedToPlatform) {
            ctx.walkedToPlatform = true;
            Pos press = nukeLoadStand();
            if (press != null) {
                log.info("NUKE of {} with the chunks not loaded: walking straight to the clear stand {} (the art holds"
                    + " nothing the piston clear cannot remove)", platform.id, press.key());
                note = "walking to the clear stand";
                run(bot.walkTo(press, 1));
                return true;
            }
            Pos entry = phase == Phase.NUKE && config.build.nukeEntryCorner ? NukeEntry.stand(platform) : null;
            if (entry != null) {
                // build.nukeEntryCorner: onto the art layer where the way in arrives; the sweep starts from there
                ctx.walkedToEntry = true;
                log.info("NUKE of {} with the chunks not loaded: walking to {} by the {} corner, where the way onto the"
                    + " art layer arrives (build.nukeEntryCorner)", platform.id, entry.key(), platform.nukeEndCorner);
                note = "walking to the art layer's entry corner";
                Ctx c = ctx;
                run(bot.walkTo(entry, NukeEntry.RANGE), null, r -> {
                    log.warn("walk to the entry corner of {} failed ({}): on to the map station", platform.id, r);
                    c.loadWaitTicks = Math.max(c.loadWaitTicks, NukeEntry.WAIT_TICKS);
                });
                return true;
            }
            Pos load = loadStand();
            if (load != mapStandPos()) {
                log.info("{} with the chunks not loaded: walking to the foot of its access {} (its centre lies in the"
                    + " machinery's no-go zone)", platform.id, load.key());
                note = "walking to the platform's access";
            }
            run(bot.walkTo(load, CENTER_RANGE));
            return true;
        }
        if (ctx.walkedToEntry && ctx.loadWaitTicks >= NukeEntry.WAIT_TICKS) {
            ctx.walkedToEntry = false;
            ctx.loadWaitTicks = 0; // the station's wait as before
            log.info("NUKE of {}: the chunks are still not all loaded at the entry corner; walking on to the map"
                + " station", platform.id);
            run(bot.walkTo(mapStandPos(), CENTER_RANGE));
            return true;
        }
        if (ctx.loadWaitTicks >= LOAD_WAIT_TICKS) {
            stepFailed("platform " + platform.id + " chunks not loaded");
            return true;
        }
        ctx.loadWaitTicks += POLL_TICKS;
        sleep(POLL_TICKS);
        return true;
    }

    /**
     * Where NUKE walks to load the platform, or null for the map station: the clear stand (a clear actuator's
     * {@code standAt} outside every sweep, else the centre) when the bot waits the clear out at the centre
     * ({@code stayDuringClear}), this tile is stored (the NUKE follows our own finished art, not a foreign platform) and
     * the art holds no block the piston clear cannot remove, so there is nothing to break and the walk only goes to the
     * place the press is made from. Otherwise the map station on the floor: a nuke starts from the art layer, and the
     * centre is reached only by the long walkway. {@code build.nukeLoadFromCentre} off = always the station.
     */
    private @Nullable Pos nukeLoadStand() {
        if (phase != Phase.NUKE || !config.build.nukeLoadFromCentre || suppressionTile() || !platform.stayDuringClear
            || platform.center == null || tile == null || tile.status != TileStatus.STORED) return null;
        if (plan.artNeedsNuke(platform.clearableBlocks)) return null;
        // a carry-over may break carpets: it starts from the art layer like a nuke
        if (carryOverApplies() && carrySource(new StringBuilder()) != null) return null;
        Predicate<Pos> away = awayFromClearSweeps();
        if (platform.clear != null) {
            for (ActuatorCfg a : platform.clear) if (a != null && a.standAt != null && away.test(feetAt(a.standAt))) return a.standAt;
        }
        return away.test(feetAt(centerPos())) ? centerPos() : null;
    }

    /** Remaining needs from the world when loaded, else the last observed needs; null while walking there. */
    private Map<String, Integer> currentNeeds() {
        WorldView w = bot.world();
        if (needsCache != null && !plan.allLoaded(w)) return needsCache;
        if (awaitPlatformLoaded()) return null;
        replan(w);
        return needsCache;
    }

    private List<Lane> replan(WorldView w) {
        int halfWidth = PlaceMode.laneHalfWidth(config.build, bot.airPlaceAvailable());
        List<Lane> planned = withLevels(w, halfWidth); // build.suppressionMergeLevels: both levels in one sweep
        if (planned == null) planned = planner.plan(plan.art, plan.geo, catalog, halfWidth, plan.notBuildableIn(w));
        List<Lane> lanes = safeLanes(withNoobline(planned, w, halfWidth));
        lanesCache = lanes;
        needsCache = plan.remainingNeeds(w);
        tile.placed = plan.correctCount(w);
        return lanes;
    }

    private static Map<String, Integer> needsOf(List<Lane> lanes) {
        Map<String, Integer> m = new TreeMap<>();
        for (Lane l : lanes) for (Placement p : l.placements()) m.merge(p.itemId(), 1, Integer::sum);
        return m;
    }

    private Map<String, Integer> minusInventory(Map<String, Integer> needs) {
        InventoryView inv = bot.inventory();
        Map<String, Integer> m = new TreeMap<>();
        needs.forEach((k, v) -> {
            int left = v - inv.count(k);
            if (left > 0) m.put(k, left);
        });
        return m;
    }

    private long waitingRecheckMs() {
        return Math.max(1, config.build.waitingRecheckMinutes) * 60_000L;
    }

    private static boolean holds(InventoryView inv, Map<String, Integer> need) {
        for (var e : need.entrySet()) if (inv.count(e.getKey()) < e.getValue()) return false;
        return true;
    }

    /** Never deposited: keepItems (tools, food, boots), the configured foods, maps, panes, XP bottles, a spare anvil. */
    private boolean keepAlways(String id) {
        return config.build.keepItems.contains(id) || id.equals(MAP) || id.equals(FILLED_MAP) || id.equals(PANE)
            || id.equals(XP_BOTTLE) || id.equals(ANVIL) || id.equals(CART) || foodIds().contains(id);
    }

    /**
     * This RESTOCK's material rule ({@link Leftovers}): withdrawn materials, the current tile's needs and every
     * unfinished job's material list, and anything a material (sorter) chest holds or once held.
     */
    private Leftovers leftovers(Ctx c) {
        if (c.leftovers != null) return c.leftovers;
        Set<String> known = new HashSet<>(withdrawnMaterials);
        known.addAll(nukedMaterials);
        if (c.needs != null) known.addAll(c.needs.keySet());
        if (needsCache != null) known.addAll(needsCache.keySet());
        if (job != null && job.materials != null) known.addAll(job.materials.keySet());
        for (Job j : coord.jobs()) {
            if (j != null && (j.status == null || !j.status.terminal()) && j.materials != null) known.addAll(j.materials.keySet());
        }
        StockIndex stock = coord.stock();
        return c.leftovers = new Leftovers(this::keepAlways, known, stock::everHeld, junkTest(known)).reserving(fallKit());
    }

    /** The platform's junk spot is set and not a long detour from here (or from the leftovers drop point). */
    private boolean junkSpotNear() {
        if (!config.build.dropJunk || !anyJunkRule() || platform == null
            || platform.junkDropStand == null || platform.junkDropAt == null) return false;
        Pos stand = platform.junkDropStand;
        Pos feet = botFeet();
        if (Math.hypot(feet.x() - stand.x(), feet.z() - stand.z()) <= config.build.junkDropMaxDetour) return true;
        for (DropPointCfg d : ownPlatformOnly(config.site.storage.returnDrops, x -> x == null ? null : x.target))
            if (d != null && d.standAt != null
                && Math.hypot(d.standAt.x() - stand.x(), d.standAt.z() - stand.z()) <= config.build.junkDropMaxDetour)
                return true; // the bot is at the drop point after the leftovers drop
        return false;
    }

    /**
     * {@code build.dropJunk}: the mob drops and other junk the bot picks up ({@code build.junkItems}), and with
     * {@code build.junkNukeDrops} what breaking a material's block gave back instead of it ({@link NukeDrops}), go out
     * with the leftovers at the drop point. Never a valuable, a working item, food, an item of
     * a queued job's materials ({@code known}); and only with a junk spot ({@link PlatformCfg#junkDropStand}) within
     * {@code build.junkDropMaxDetour} blocks: junk never goes into the sorter.
     */
    private Predicate<String> junkTest(Collection<String> known) {
        // junk is junk wherever the bot stands (live 2026-10-16: away from the junk spot the pillager drops counted as
        // "items the bot won't touch" in the Few free slots alert); only the throw waits for the spot to be near
        // (throwJunk). A platform without a junk spot has no junk: it stays in the inventory, never goes to the sorter
        if (!config.build.dropJunk || !anyJunkRule() || platform == null || platform.junkDropStand == null
            || platform.junkDropAt == null) return id -> false;
        Set<String> junk = new HashSet<>();
        for (String id : config.build.junkItems) junk.add(ItemIds.path(id));
        Set<String> used = new HashSet<>();
        for (String id : known) used.add(ItemIds.path(id));
        // build.junkNukeDrops: what breaking a material's block gives back (clay balls of clay), as if listed
        StockIndex stock = coord.stock();
        Predicate<String> material = block -> used.contains(ItemIds.path(block)) || stock.everHeld(ItemIds.ns(block));
        boolean drops = config.build.junkNukeDrops;
        return id -> {
            String path = ItemIds.path(id);
            return (junk.contains(path) || (drops && NukeDrops.fromMaterial(id, material))) && !used.contains(path)
                && (!Valuables.isValuable(id) || Valuables.junkWhenPlain(id)) && !keepAlways(id);
        };
    }

    /** Some junk rule is on: {@code build.junkItems} lists items, or {@code build.junkNukeDrops}. */
    private boolean anyJunkRule() {
        return !config.build.junkItems.isEmpty() || config.build.junkNukeDrops;
    }

    /**
     * {@code build.elytraLegs}, in RESTOCK before the load is planned: an elytra
     * from the rocket chest when none is carried, or the spare for one at the durability floor that Mending cannot
     * raise (it goes back); the flight rockets topped up to {@code elytraRocketStock} when under
     * {@code elytraRocketLow}. One visit each at most per RESTOCK. No rocket chest, or one without what is missing: one
     * log line (no Discord notice), and the restock legs walk without them ({@code FlightLeg}'s fall-back); a visit
     * that found none holds the next one for {@link ElytraKit#EMPTY_RETRY_MS}. True while a task runs.
     *
     * <p>{@code build.elytraKitFarTrip}: a job that keeps to its platform goes to a rocket chest that is far storage
     * for it by the platform's access path ({@link #kitWayThere}), and comes back by it ({@link #kitWayBack}).
     */
    private boolean flightKit(Ctx c, InventoryView inv) {
        if (c.kitDone) return false;
        if (!c.kitVisited) {
            if (kitVisit(c, inv)) return true;
            c.kitVisited = true;
        }
        if (kitWayBack(c)) return true;
        if (c.kitFarSince >= 0) {
            log.info("Elytra kit: the trip to the far rocket chest and back to the entry took {} s; {} flight rockets"
                + " carried", (clock.millis() - c.kitFarSince + 500) / 1000, inv.flightRockets());
            c.kitFarSince = -1;
        }
        c.kitFar = false;
        if (c.kitFarClosed) {
            c.kitFarClosed = false;
            updateOpenings(); // the ride's openings again for the tasks from here on
        }
        c.kitDone = true;
        return false;
    }

    /** {@link #flightKit}'s visits to the rocket chest: true while a task runs, false when there is nothing more to do. */
    private boolean kitVisit(Ctx c, InventoryView inv) {
        ElytraKit.Need need = ElytraKit.need(inv.armor(), inv.flightRockets(), inv.count(XP_BOTTLE), config.build);
        if (!need.any()) return false;
        Pos known = kit.chest(config.site.storage.rocketChests, bot.world(), botFeet(), clock.millis(),
            line -> log.info("Elytra kit: {}", line));
        if (known == null) {
            log.info("Elytra kit: {}; no rocket chest (site.storage.rocketChests is empty and no sign saying \"rocket\" is on"
                + " a chest or barrel in the loaded chunks): the restock legs walk without it", need.why());
            return false;
        }
        // a colour-suppression job keeps to its platform and its own chests: a rocket chest that is far storage for
        // it is no trip for the kit. Live 2026-11-15 09:28: from the cs chests the walk to the soul sand sorter's
        // rocket chest (195 blocks west, 42 down; its sign seen from the ride's walkway that morning) had no way,
        // ended on the ride's walkway and turned west and east there for 4.5 minutes until the owner saw it. A chest
        // a "rocket" sign names by the platform is used instead when there is one. build.elytraKitFarTrip: the far
        // chest all the same, by the platform's access path (down to its foot, back by its foot and its entry)
        PlatformCfg own = scopePlatform();
        boolean farTrip = false;
        if (own != null) {
            Pos near = ElytraKit.forPlatform(known, ElytraKit.signedChests(bot.world().signs(), bot.world()::block,
                botFeet()), own.minX, own.minZ, config.build.farStorageBlocks);
            String noWay = near != null ? null : !config.build.elytraKitFarTrip ? "" : ElytraKit.farTripProblem(own);
            if (near == null && noWay != null) {
                log.info("Elytra kit: {}; the rocket chest at {} is far storage for platform {} (more than {} blocks"
                    + " from it) and no \"rocket\" sign names a chest by the platform: no trip for the kit from a"
                    + " color-suppression job{}, the flights go on with what is carried", need.why(), known, own.id,
                    config.build.farStorageBlocks, noWay.isEmpty() ? "" : " (build.elytraKitFarTrip: " + noWay + ")");
                return false;
            }
            if (near == null) {
                farTrip = true;
            } else {
                if (!near.equals(known) && c.attempt("kit-near") == 0)
                    log.info("Elytra kit: the rocket chest at {} is far storage for platform {}; the one at {} by the"
                        + " platform is used", known, own.id, near);
                known = near;
            }
        }
        Pos chest = known;
        if (c.kitFarFailed) return false; // build.elytraKitFarTrip: the way there gave up (its own line said why)
        long now = clock.millis();
        // a look this RESTOCK is no hold (its own line said what it found)
        long elytraAgo = need.elytra() && !c.attempts.containsKey("kit-elytra") ? kit.emptyAgo(true, chest, now) : -1;
        long rocketsAgo = need.rockets() > 0 && !c.attempts.containsKey("kit-rockets") ? kit.emptyAgo(false, chest, now)
            : -1;
        if ((elytraAgo >= 0 || rocketsAgo >= 0) && c.attempt("kit-held") == 0) {
            log.info("Elytra kit: {}; the rocket chest at {} had no {} at the last look {} s ago (the next look after {}"
                + " min): the restock legs walk without them", need.why(), chest,
                elytraAgo >= 0 && rocketsAgo >= 0 ? "elytra and no flight rockets" : elytraAgo >= 0 ? "elytra"
                    : "flight rockets", Math.max(elytraAgo, rocketsAgo), ElytraKit.EMPTY_RETRY_MS / 60_000);
        }
        if (farTrip) {
            // build.elytraKitFarTrip: a visit is due (not held, not made this RESTOCK): the way there first
            boolean due = (need.elytra() && elytraAgo < 0 && !c.attempts.containsKey("kit-elytra"))
                || (need.rockets() > 0 && rocketsAgo < 0 && !c.attempts.containsKey("kit-rockets"));
            if (due && kitWayThere(c, own, chest, need)) return true;
            if (c.kitFarFailed) return false; // still above the foot after the walk to it
        }
        if (need.elytra() && elytraAgo < 0 && c.attempt("kit-elytra") == 0) {
            int max = inv.armor().stream().filter(a -> ElytraKit.ELYTRA.equals(a.itemId())).mapToInt(InventoryView.Armor::max)
                .max().orElse(ElytraKit.ELYTRA_MAX);
            int floor = ElytraKit.floorUses(max, config.build.elytraMinPercent);
            run(bot.exchangeElytra(chest, floor, r -> {
                int took = r.moved().getOrDefault(ElytraKit.ELYTRA, 0);
                if (took > 0) kit.stocked(true);
                else kit.empty(true, chest, clock.millis());
                if (took > 0) log.info("Elytra kit: took an elytra from the rocket chest at {} ({})", chest, need.why());
                else log.info("Elytra kit: no elytra above {} uses in the rocket chest at {} ({}): the restock legs walk"
                    + " without one{}", floor, chest, need.why(),
                    r.problem() == null || r.problem().startsWith("no elytra above") ? "" : "; " + r.problem());
            }), null, r -> {
                kit.empty(true, chest, clock.millis());
                log.info("Elytra kit: the elytra exchange at {} failed: {}", chest, r);
            });
            return true;
        }
        if (need.rockets() > 0 && rocketsAgo < 0 && c.attempt("kit-rockets") == 0) {
            int before = inv.flightRockets();
            run(bot.withdraw(chest, null, Map.of(ElytraKit.ROCKET, need.rockets()), WithdrawMode.TOP_UP, r -> {
                int took = r.moved().getOrDefault(ElytraKit.ROCKET, 0);
                if (took > 0) kit.stocked(false);
                else kit.empty(false, chest, clock.millis());
                if (took > 0) log.info("Elytra kit: took {} rockets from the rocket chest at {} ({} carried before)", took, chest,
                    before);
                else log.info("Elytra kit: no flight rockets (plain, duration 1-3) in the rocket chest at {} ({}): the restock"
                    + " legs walk once they are out{}", chest, need.why(), r.problem() == null ? "" : "; " + r.problem());
            }), null, r -> {
                kit.empty(false, chest, clock.millis());
                log.info("Elytra kit: the rocket withdrawal at {} failed: {}", chest, r);
            });
            return true;
        }
        return false;
    }

    /**
     * {@code build.elytraKitFarTrip}, the way to a rocket chest that is far storage for the job's platform
     * (the far trip): a bot above the {@code foot} of the platform's access path (on
     * the platform or on the path) walks down to the foot first; the visit's own leg then starts on the storage walkway
     * (a restock leg: it flies where the planner passes it, else it walks). Live 2026-11-15 09:28: straight from the
     * cs chests the pathfinder's partial paths led onto the ride's walkway (it runs 120 blocks toward the chest, 48
     * above the storage walkway, and ends in the booth). The ride's openings are closed while the trip is under way
     * ({@link #updateOpenings}): no path of it can enter them. A trip that starts on them (the booth after a ride, the
     * start stand: {@link #onRideWays}) keeps them open for the walk down, its only way out, and closes them at the
     * foot. The foot not reached: no visit, the hold of an empty
     * chest ({@link ElytraKit#EMPTY_RETRY_MS}), the print goes on with what is carried. True while the walk runs.
     */
    private boolean kitWayThere(Ctx c, PlatformCfg own, Pos chest, ElytraKit.Need need) {
        if (c.kitFarFailed) return false;
        boolean up = ElytraKit.aboveFoot(botFeet().y(), own.foot);
        if (!c.kitFar) {
            c.kitFar = true;
            c.kitFarSince = clock.millis();
            // a RESTOCK that starts on the ride's ways (the booth after a ride, the start stand): they are its only
            // way out, and stay open for the walk down; closed from the foot on
            boolean rideWays = up && onRideWays(own, botFeet());
            if (!rideWays) closeRideWays(c); // from this trip's first task on (tasks read the zones when they are made)
            log.info("Elytra kit: {}; the rocket chest at {} is far storage for platform {} (build.elytraKitFarTrip): {}{}",
                need.why(), chest, own.id, up ? "down the access path to its foot " + own.foot.key() + " first, then to"
                    + " the chest, and back by the foot and the entry " + own.entry.key()
                    : "the bot is at the foot's level: to the chest from here, and back by the foot " + own.foot.key()
                    + " and the entry " + own.entry.key(), rideWays ? " (the bot stands on the ride's ways: they stay"
                    + " open until it is at the foot)" : "");
        }
        if (!up) {
            closeRideWays(c);
            return false;
        }
        if (c.attempt("kit-far-down") == 0) {
            run(bot.walkTo(own.foot, 1), null, r -> kitFarFailed(c, need, chest, "the foot " + own.foot.key()
                + " of the access path was not reached: " + r));
            return true;
        }
        // the walk ended and the bot is still above the foot: no leg toward the chest from up here
        kitFarFailed(c, need, chest, "the bot is still above the foot " + own.foot.key() + " after the walk to it");
        return false;
    }

    /** The ride's openings closed for the rest of the kit's far trip ({@link #updateOpenings}), at once. */
    private void closeRideWays(Ctx c) {
        if (c.kitFarClosed) return;
        c.kitFarClosed = true;
        updateOpenings();
    }

    /** {@link ElytraKit#onRideWays} for the platform's own openings ({@link SiteRules#suppressionOpenings}). */
    private boolean onRideWays(PlatformCfg own, Pos feet) {
        return ElytraKit.onRideWays(config.site.noGoZones, SiteRules.suppressionOpenings(own), feet);
    }

    /** {@link #kitWayThere} gave up: the visit is held like one that found the chest empty; one line. */
    private void kitFarFailed(Ctx c, ElytraKit.Need need, Pos chest, String why) {
        c.kitFarFailed = true;
        c.kitFarSince = -1; // no trip was made: no line for its time
        long now = clock.millis();
        if (need.elytra()) kit.empty(true, chest, now);
        if (need.rockets() > 0) kit.empty(false, chest, now);
        log.info("Elytra kit: no trip to the far rocket chest at {}: {}; the flights go on with what is carried (the next"
            + " try after {} min)", chest, why, ElytraKit.EMPTY_RETRY_MS / 60_000);
    }

    /**
     * {@code build.elytraKitFarTrip}, the way back to the platform of a job that keeps to it: a bot below the
     * platform's levels and farther from its map area than far storage ({@code build.farStorageBlocks}: at the far
     * rocket chest) walks to the {@code foot} of the access path, then to its {@code entry}; the RESTOCK's own walks go
     * on from there, as from the storage at the job's start. By where the bot stands, not by what this RESTOCK did: a
     * trip cut short (a pause, a reconnect) is finished by the next RESTOCK, and a RESTOCK that dropped its leftovers
     * at a far drop point comes back the same way (local 10x 2026-11-15: 257 blocks west of the platform, 243 blocks
     * to the foot in 404 ticks). A walk that fails is one line; the next one is tried all the same. True while a walk
     * runs.
     */
    private boolean kitWayBack(Ctx c) {
        PlatformCfg own = scopePlatform();
        if (own == null || !config.build.elytraKitFarTrip || ElytraKit.farTripProblem(own) != null) return false;
        Pos feet = botFeet();
        if (ElytraKit.onPlatform(feet.y(), own.glassY)) return false;
        if (!c.attempts.containsKey("kit-back-foot")
            && !FarStorage.far(own.minX, own.minZ, config.build.farStorageBlocks, feet)) return false;
        if (c.attempt("kit-back-foot") == 0) {
            closeRideWays(c); // far from the platform and below it: not on them
            log.info("Elytra kit: back to platform {} from {}: to the foot {} of its access path, then its entry {}",
                own.id, feet, own.foot.key(), own.entry.key());
            run(bot.walkTo(own.foot, 1), null, r -> log.warn("Elytra kit: on the way back the foot {} was not reached: {}",
                own.foot.key(), r));
            return true;
        }
        if (c.attempt("kit-back-entry") == 0) {
            run(bot.walkTo(own.entry, 1), null, r -> log.warn("Elytra kit: on the way back the entry {} was not reached:"
                + " {}", own.entry.key(), r));
            return true;
        }
        return false;
    }

    /**
     * {@code build.elytraLegs} without {@code site.storage.rocketChests}: looks for the rocket chest's sign every tick,
     * throttled to {@link ElytraKit#SEARCH_EVERY_MS} by the kit, so the chest is known before a RESTOCK that starts
     * out of its view (live view distance; locally the first RESTOCK starts at the station chests 245 blocks away).
     */
    private void watchRocketSign() {
        if (!config.build.elytraLegs || config.site.storage.rocketChests.stream().anyMatch(java.util.Objects::nonNull)) return;
        try {
            kit.chest(List.of(), bot.world(), botFeet(), clock.millis(), line -> log.info("Elytra kit: {}", line));
        } catch (RuntimeException e) {
            log.debug("Elytra kit: the sign search failed: {}", e.toString());
        }
    }

    /** Logs the items kept although they are no materials (offhand included), once per connection and item set. */
    private void noteKept(InventoryView inv, Leftovers lo) {
        Map<String, Integer> offhand = inv.offhand();
        Map<String, Integer> kept = lo.untouchable(inv.contents());
        if (offhand.isEmpty() && kept.isEmpty()) return;
        String key = offhand.keySet() + "|" + kept.keySet();
        if (key.equals(keptLogged)) return;
        keptLogged = key;
        int n = Leftovers.count(offhand, kept);
        log.info("keeping {} non-material item{}: {}", n, n == 1 ? "" : "s", Leftovers.describe(offhand, kept));
    }

    /**
     * Items the bot does not touch hold slots; when they leave too few to load even one whole lane (one would fit with
     * those slots free), one alert per connection and item set. The bot goes on with the slots it has (partial lanes,
     * more trips) and never gets rid of them. Judged with the greedy fill whatever planner chose the load: the split
     * planner cuts lanes on purpose, so its load says nothing about whether one whole lane would fit.
     */
    private void alertIfCrowded(Ctx c, InventoryView inv, Map<String, Integer> held, int free, RestockPlanner.Plan load) {
        if (held == null || c.buildable.isEmpty()) return;
        GreedyRestockPlanner greedy = new GreedyRestockPlanner();
        RestockPlanner.Plan rp = load.stopAtCut() ? greedy.plan(c.buildable, held, free, catalog::maxStackSize) : load;
        if (rp.lanesCovered() > 0) return;
        Leftovers lo = leftovers(c);
        // the elytra and the rockets are the flights' kit (build.elytraLegs), carried on purpose, also while the flights
        // are switched off: not "items the bot won't touch" for this alert (their slots still count for the load). With
        // the elytra worn (a flight's gear swap until the chestplate goes back on, or a player's flight on the account),
        // the carried chestplate is the kit's slot too
        Map<String, Integer> used = new TreeMap<>(inv.slotsUsed());
        Map<String, Integer> contents = new TreeMap<>(inv.contents());
        List<String> kitIds = new ArrayList<>(List.of(ElytraKit.ELYTRA, ElytraKit.ROCKET));
        List<InventoryView.Armor> armor = inv.armor();
        boolean elytraWorn = armor.stream().anyMatch(a -> a.worn() && a.slot() == InventoryView.Armor.CHEST
            && ElytraKit.ELYTRA.equals(a.itemId()));
        if (elytraWorn) armor.stream().filter(a -> !a.worn() && a.itemId().endsWith("_chestplate"))
            .forEach(a -> kitIds.add(a.itemId()));
        for (String id : kitIds) {
            used.remove(id);
            contents.remove(id);
        }
        int slots = lo.untouchableSlots(used);
        if (slots <= 0) return;
        RestockPlanner.Plan roomy = greedy.plan(c.buildable, held, free + slots, catalog::maxStackSize);
        if (roomy.lanesCovered() <= 0) return; // not their fault: the lane needs more slots than the inventory has
        Map<String, Integer> kept = lo.untouchable(contents);
        String key = kept.keySet().toString();
        if (key.equals(crowdedAlerted)) return;
        crowdedAlerted = key;
        String body = slots + " slot" + (slots == 1 ? "" : "s") + " hold items the bot won't touch: "
            + Leftovers.describe(Map.of(), kept) + ". With them each load covers " + rp.partialCells()
            + " cells of the next lane (with those slots free: " + roomy.lanesCovered() + " lane"
            + (roomy.lanesCovered() == 1 ? "" : "s") + "). Continuing with fewer slots and more trips; they are never"
            + " deposited or dropped. Take them out of the bot's inventory to speed it up.";
        log.warn("Few free slots: {}", body);
        alertHere("Few free slots", body);
    }

    /**
     * Free slots usable for materials, keeping {@code reservedSlots} for tools, food, map, panes. Only empty slots
     * count: the items the bot does not touch (the owner's) keep theirs, so the load is planned around them.
     */
    private int materialSlots(InventoryView inv) {
        int reserveFree = Math.max(0, config.build.reservedSlots - keepSlots(inv));
        return Math.max(0, inv.freeSlots() - reserveFree);
    }

    /**
     * Slots the engine's working and kept items hold ({@link #keepAlways}: tools, food, maps, panes, XP bottles, ...),
     * as {@link InventoryView#slotsUsed} counts them (filled maps never stack: one slot each).
     */
    private int keepSlots(InventoryView inv) {
        int n = 0;
        for (var e : inv.slotsUsed().entrySet()) if (keepAlways(e.getKey())) n += Math.max(0, e.getValue());
        return n;
    }

    /**
     * What RESTOCK keeps of the held materials ({@link Ctx#keep}): {@code c.needs}, less the partial stacks the load
     * has no use for when their slots let it cover more. Live (j0002, 2b2t, 61d6411): RESTOCK kept every partial
     * stack a later lane still needed. Whole-stack withdrawals leave one per odd block, so on a mixed art the slots
     * filled up with colours the next lane does not use and each load got the slot or two left ("load covers 0 + 36
     * cells of the next, withdraw {gray_carpet=36}", "load covers 0, withdraw {}"): 29 restocks in 5 lanes.
     *
     * <p>Only while the load is crowded: smaller than the lane it ends in, which then takes another trip. The
     * materials that go: held, used by no lane of the load (the lanes it reaches with those slots free), and in stock
     * for all the tile still needs of them (the dropped stack is not counted on to come back from the sorter). The
     * load must cover more cells without them. The items of the cells left to VERIFY go too (live: 27 such cells held
     * up to that many slots): VERIFY fetches them again through RESTOCK. Everything that is no material stays
     * ({@link Leftovers}). Planned on the inventory after the leftovers RESTOCK gets rid of anyway, as the load is.
     */
    private Map<String, Integer> keepForLoad(Ctx c, InventoryView inv, Leftovers lo) {
        if (c.buildable.isEmpty()) return c.needs;
        Map<String, Integer> junk = lo.junk(inv.contents(), c.needs, catalog::maxStackSize);
        Map<String, Integer> slotsUsed = inv.slotsUsed();
        int free = materialSlots(inv);
        for (var e : junk.entrySet()) {
            if (c.needs.containsKey(e.getKey())) free += e.getValue() / Math.max(1, catalog.maxStackSize(e.getKey()));
            else free += slotsUsed.getOrDefault(e.getKey(), 0);
        }
        Map<String, Integer> held = new TreeMap<>();
        for (String id : c.needs.keySet()) {
            int n = inv.count(id) - junk.getOrDefault(id, 0);
            if (n > 0) held.put(id, n);
        }
        // the greedy fill estimates the crowding cheaply on the tick thread, whatever planner chooses the load (the
        // split DP runs off it, once per load). With the load's own top-up rule: without it the estimate fell short of
        // the load (a topped-up partial stack takes no slot more), and an item dropped here as not used by the lanes
        // the estimate reached was withdrawn again by the same load
        RestockPlanner.Plan now = crowdEstimate.plan(new RestockPlanner.Request(c.buildable, held, Map.of(), free,
            catalog::maxStackSize, 0, config.build.topUpStacks, null));
        if (held.isEmpty() || now.lanesCovered() >= c.buildable.size()) return c.needs;
        int before = cellsLoaded(c.buildable, now);
        if (before >= c.buildable.get(now.lanesCovered()).placements().size()) return c.needs; // not crowded
        Set<String> keep = itemsOf(c.buildable.subList(0, 1));
        // with far-storage batching an item near storage cannot supply again stays too: it would cost a far trip
        for (String id : held.keySet()) {
            if (!lo.material(id) || stocked(id) < c.needs.get(id) || nearStocked(id) < c.needs.get(id)) keep.add(id);
        }
        RestockPlanner.Plan plan = now;
        Map<String, Integer> idle = new TreeMap<>();
        for (int round = 0; round <= held.size(); round++) {
            // the window grows to the lanes the load reaches (their items stay) until it holds all the load uses: an
            // item dropped here is never withdrawn again for the same load
            Map<String, Integer> kept = new TreeMap<>(held);
            idle.clear();
            int freed = 0;
            for (var e : held.entrySet()) {
                if (keep.contains(e.getKey())) continue;
                kept.remove(e.getKey());
                idle.put(e.getKey(), e.getValue());
                int surplus = junk.getOrDefault(e.getKey(), 0) / Math.max(1, catalog.maxStackSize(e.getKey()));
                freed += Math.max(1, slotsUsed.getOrDefault(e.getKey(), 1) - surplus); // whole surplus stacks: in free
            }
            if (idle.isEmpty()) return c.needs;
            plan = crowdEstimate.plan(new RestockPlanner.Request(c.buildable, kept, Map.of(), free + freed,
                catalog::maxStackSize, 0, config.build.topUpStacks, null));
            int reached = Math.min(c.buildable.size(), Math.max(1, plan.lanesLoaded()));
            Set<String> window = itemsOf(c.buildable.subList(0, reached));
            if (keep.containsAll(window)) break;
            keep.addAll(window);
        }
        int after = cellsLoaded(c.buildable, plan);
        if (after <= before) return c.needs;
        int lanes = Math.max(1, plan.lanesLoaded());
        log.info("making room: {} not used by the next {} lane{}; without them the load covers {} cells instead of {}",
            idle, lanes, lanes == 1 ? "" : "s", after, before);
        Map<String, Integer> out = new TreeMap<>(c.needs);
        out.keySet().removeAll(idle.keySet());
        return out;
    }

    private static Set<String> itemsOf(List<Lane> lanes) {
        Set<String> s = new HashSet<>();
        for (Lane l : lanes) for (Placement p : l.placements()) s.add(p.itemId());
        return s;
    }

    /** Cells of {@code lanes} the load of {@code p} covers: the whole lanes and the partial one. */
    private static int cellsLoaded(List<Lane> lanes, RestockPlanner.Plan p) {
        int n = p.partialCells();
        for (int i = 0; i < Math.min(p.lanesCovered(), lanes.size()); i++) n += lanes.get(i).placements().size();
        return n;
    }

    /** {@code id} in the material containers, by the stock index. */
    private int stocked(String id) {
        StockIndex st = coord.stock();
        int n = 0;
        synchronized (st) {
            for (Pos chest : st.containersWith(id)) {
                if (!isMaterialContainer(chest)) continue;
                StockIndex.Snapshot s = st.containers.get(chest.key());
                if (s != null) n += s.items.getOrDefault(id, 0);
            }
        }
        return n;
    }

    /**
     * One {@code item} from the next of {@code chests}: the empty map and the pane exactly one each with the trimmed kit
     * ({@code build.exactMapAndPane}), the anvil exactly one with {@code build.exactAnvil}, else as a whole stack. False
     * when every chest was tried.
     */
    private boolean withdrawOne(List<Pos> chests, String item, String purpose) {
        int a = ctx.attempt(purpose);
        if (a >= chests.size()) return false;
        WithdrawMode mode = item.equals(MAP) || item.equals(PANE) ? mapPaneMode()
            : item.equals(ANVIL) && config.build.exactAnvil ? WithdrawMode.EXACT : WithdrawMode.WHOLE_STACKS;
        run(bot.withdraw(chests.get(a), null, Map.of(item, 1), mode, r -> { }), null,
            r -> log.warn("{} withdraw failed: {}", purpose, r));
        return true;
    }

    /**
     * Puts the platform's item collection (moss hopper minecarts) in the wanted state by pressing
     * {@code collectionOn}/{@code collectionOff}, checked against {@code collectionIndicator}. Without an indicator
     * the actuator is pressed once per phase entry. True while acting.
     */
    private boolean ensureCollection(boolean on) {
        ActuatorCfg act = on ? platform.collectionOn : platform.collectionOff;
        if (act == null || act.pos == null) return false;
        String key = on ? "collection-on" : "collection-off";
        Pos ind = platform.collectionIndicator;
        if (ind == null) {
            if (ctx.attempt(key) > 0) return false;
            log.info("item collection on {} -> {} (no indicator; pressing once)", platform.id, on ? "ON" : "OFF");
            run(bot.useBlock(act.pos, act.standAt), () -> sleep(TOGGLE_SETTLE_TICKS));
            return true;
        }
        WorldView w = bot.world();
        if (!w.isLoaded(ind) || !w.isLoaded(act.pos)) {
            if (ctx.attempt(key + "-walk") == 0) {
                run(bot.walkTo(act.standAt != null ? act.standAt : act.pos, 3));
                return true;
            }
            stepFailed("item collection indicator at " + ind.key() + " is not loaded");
            return true;
        }
        if (collectionIsOn(w) == on) return false;
        if (ctx.attempt(key) >= TOGGLE_TRIES) {
            stepFailed("item collection did not switch " + (on ? "on" : "off") + " (indicator " + w.block(ind) + ")");
            return true;
        }
        log.info("item collection on {} -> {}", platform.id, on ? "ON" : "OFF");
        run(bot.useBlock(act.pos, act.standAt), () -> sleep(TOGGLE_SETTLE_TICKS));
        return true;
    }

    private boolean collectionIsOn(WorldView w) {
        String want = platform.collectionIndicatorOn;
        if (want == null || want.isBlank()) want = "minecraft:redstone_lamp[lit=true]";
        return catalog.matches(BlockSpec.parse(want), w.block(platform.collectionIndicator));
    }

    /**
     * RESTOCK: gets rid of {@code junk} into a return chest, or, without return chests, by dropping it at a
     * configured drop point (moss: the water channel to the sorter). Only the items of {@code junk} that are
     * {@code material} may go, down to {@code c.needs}: the bot task is asked for nothing else, so it moves the planned
     * set and never a partial surplus of a material still needed, which {@link Leftovers#junk} keeps (live gate C:
     * "dropping {orange_terracotta=60}" also dropped three carpets the tile still needed). {@code needs}: what stays;
     * {@code key}: prefix of this caller's attempt counters in {@code c}. True while a task runs.
     */
    private boolean returnLeftovers(Ctx c, Map<String, Integer> junk, Predicate<String> material,
                                    Map<String, Integer> needs, String key) {
        return returnLeftovers(c, junk, material, needs, key, null);
    }

    /** {@code onMoved}: runs when the task got something out of the inventory (null: nothing). */
    private boolean returnLeftovers(Ctx c, Map<String, Integer> junk, Predicate<String> material,
                                    Map<String, Integer> needs, String key, @Nullable Runnable onMoved) {
        Predicate<String> planned = id -> junk.containsKey(id) && material.test(id);
        MapArtConfig.StorageCfg st = config.site.storage;
        // the platform's own return chests win over the site's chests and drop points (PlatformCfg.returnChests)
        boolean own = platform != null && platform.returnChests != null && !platform.returnChests.isEmpty();
        List<Pos> rc = own ? platform.returnChests : ownPlatformOnly(st.returnChests, x -> x);
        if (!rc.isEmpty()) {
            int a = c.attempt(key + "deposit");
            if (a < rc.size()) {
                Pos chest = rc.get(a);
                log.info("depositing {} into {}", junk, chest);
                run(bot.deposit(chest, planned, withFallKit(needs), r -> {
                        afterTransfer(chest, r);
                        if (onMoved != null && sum(r.moved()) > 0) onMoved.run();
                    }),
                    null, r -> log.warn("deposit into {} failed: {}", chest, r));
                return true;
            }
            warnOnce("deposit", "could not deposit " + junk + " (return chests full)");
            return false;
        }
        List<DropPointCfg> drops = ownPlatformOnly(st.returnDrops, x -> x == null ? null : x.target);
        int a = c.attempt(key + "drop");
        while (a < drops.size()) {
            DropPointCfg d = drops.get(a);
            if (d != null && d.standAt != null && d.target != null) {
                String spot = Claim.drop(d.standAt);
                if (awaitSpot(spot, "the drop point")) {
                    c.giveBack(key + "drop"); // waiting is no try
                    return true;
                }
                log.info("dropping {} into {} (standing at {})", junk, d.target, d.standAt);
                run(bot.dropItems(d.standAt, d.target, planned, withFallKit(needs),
                        m -> {
                            log.info("dropped {}", m);
                            if (m != null) m.forEach((id, n) -> {
                                if (n != null && n > 0) sorterFed().add(id); // into the platform's sorter stream
                            });
                            if (onMoved != null && m != null && !m.isEmpty()) onMoved.run();
                        }),
                    null, r -> log.warn("drop at {} failed: {}", d.standAt, r));
                spotTask(spot);
                return true;
            }
            a = c.attempt(key + "drop");
        }
        warnOnce("deposit", "could not get rid of " + junk + " (no return chest or drop point configured, or all failed)");
        return false;
    }

    // ---- working items ----

    /**
     * Puts the bot's working items that are not needed until later back into their own chests, one chest visit per task
     * (items sharing a chest go together); never drops them and never takes anything else. Live (2b2t, 2026-10-11):
     * three stale maps of a cover wait, a glass pane and XP bottles stayed in the inventory long after their use, taking
     * slots from every load.
     * <ul>
     *   <li>{@code tileDone} (STORE, after the locked map is in the output chest; the bot stands by the chests): this
     *   tile's stale filled maps ({@link #nextStaleMap}) go into {@code storage.scrapMapChests} (empty: the map chests),
     *   one by one; glass panes and empty maps all go back (keep 0: the next tile's last load brings one each,
     *   {@code build.exactMapAndPane}; with {@code build.mapPaneAtStore} one of each stays for the next tile).</li>
     *   <li>XP bottles go back unless a mend is due or under way ({@link #mendDue}): at STORE, and at RESTOCK when they
     *   hold a slot beyond {@code build.reservedSlots} (the load's; within the reserve they cost none, and the XP chest
     *   may be far from the material chests: the moss site has it with the output chest). A mend fetches its bottles
     *   when it starts, a rename when it runs ({@code careForTools}, RENAME), as before.</li>
     *   <li>With {@code build.exactAnvil}, at STORE: every anvil the bot holds goes into {@code storage.anvilChests}
     *   (RENAME takes exactly one when the anvil block is gone).</li>
     * </ul>
     * A chest that takes nothing (full) or fails: the next of its kind; all tried: a warning, the items stay. The owner's
     * maps (another place, or locked) are never touched. True while a task runs.
     */
    private boolean tidyWorkingItems(Ctx c, boolean tileDone) {
        InventoryView inv = bot.inventory();
        MapArtConfig.StorageCfg st = config.site.storage;
        if (tileDone) {
            Integer stale = nextStaleMap(c);
            if (stale != null) {
                List<Pos> chests = st.scrapMapChests.isEmpty() ? st.mapChests : st.scrapMapChests;
                int i = c.tidyChest.getOrDefault(FILLED_MAP, 0);
                if (i >= chests.size()) {
                    warnOnce("tidy-scrap", label() + ": no scrap map chest took the stale map " + stale
                        + (chests.isEmpty() ? " (none configured: site.storage.scrapMapChests or mapChests)" : " (full)")
                        + "; it stays in the inventory");
                    c.scrapSkipped.add(stale);
                    return tidyWorkingItems(c, true);
                }
                Pos chest = chests.get(i);
                tidyStarted(c);
                log.info("{}: putting stale map {} of this tile into {} (scrap map chest)", label(), stale, chest);
                run(bot.depositMap(chest, stale, r -> {
                    if (r.moved().getOrDefault(FILLED_MAP, 0) > 0) {
                        c.tidied.merge(FILLED_MAP, 1, Integer::sum);
                        tile.unlockedMaps = Math.max(0, tile.unlockedMaps - 1);
                        saveJob(job);
                    } else {
                        c.tidyChest.put(FILLED_MAP, i + 1);
                    }
                }), null, r -> {
                    log.warn("putting map {} into {} failed: {}", stale, chest, r);
                    c.tidyChest.put(FILLED_MAP, i + 1);
                });
                return true;
            }
        }
        Map<String, List<Pos>> chestsOf = new LinkedHashMap<>();
        if (tileDone) {
            chestsOf.put(PANE, st.paneChests);
            chestsOf.put(MAP, st.mapChests);
        }
        if (!mendDue()) chestsOf.put(XP_BOTTLE, st.xpChests);
        // spare anvils (an old whole-stack replacement): RENAME fetches the next one when the block is gone
        if (tileDone && config.build.exactAnvil && !st.anvilChests.isEmpty()) chestsOf.put(ANVIL, st.anvilChests);
        // at STORE the bottles the next NUKE needs stay
        int xpKeep = tileDone ? nukeBottles(c) : 0;
        Pos chest = null;
        Map<String, Integer> keep = new TreeMap<>();
        for (var e : chestsOf.entrySet()) {
            String item = e.getKey();
            int keepN = item.equals(XP_BOTTLE) ? xpKeep : MapPaneAtStore.keep(config.build.mapPaneAtStore, item);
            if (inv.count(item) <= keepN || config.build.keepItems.contains(item)) continue;
            // above what stays, only a whole stack's worth goes back (the deposit moves whole stacks)
            if (keepN > 0 && inv.count(item) - keepN < Math.max(1, catalog.maxStackSize(item))) continue;
            // chests that took none (full) are tried again at the next STORE only, not at every RESTOCK
            if (!tileDone && tidyRefused.contains(item)) continue;
            int i = c.tidyChest.getOrDefault(item, 0);
            if (i >= e.getValue().size()) {
                tidyRefused.add(item);
                warnOnce("tidy-" + item, label() + ": " + inv.count(item) + " " + item + " stay in the inventory: "
                    + (e.getValue().isEmpty() ? "no chest for them is configured" : "their chests took none (full?)"));
                continue;
            }
            tidyRefused.remove(item);
            Pos p = e.getValue().get(i);
            if (chest == null) chest = p;
            if (p.equals(chest)) keep.put(item, keepN);
        }
        if (chest == null) {
            if (c.tidySince >= 0 && !c.tidyLogged) {
                c.tidyLogged = true;
                log.info("{}: working items put back {} in {} s", label(), c.tidied.isEmpty() ? "(none taken)" : c.tidied,
                    String.format(Locale.ROOT, "%.1f", (clock.millis() - c.tidySince) / 1000.0));
            }
            return false;
        }
        Pos target = chest;
        tidyStarted(c);
        Map<String, Integer> had = new TreeMap<>();
        for (String item : keep.keySet()) had.put(item, inv.count(item));
        log.info("{}: putting {} back into {}", label(), had, target);
        run(bot.returnWorkingItems(target, keep, r -> r.moved().forEach((id, n) -> {
            if (n != null && n > 0) c.tidied.merge(id, n, Integer::sum);
        })), () -> {
            // what this chest did not take goes to the next chest of its kind
            for (var k : keep.entrySet()) {
                if (bot.inventory().count(k.getKey()) > k.getValue()) c.tidyChest.merge(k.getKey(), 1, Integer::sum);
            }
        }, r -> {
            log.warn("putting {} back into {} failed: {}", keep.keySet(), target, r);
            for (String item : keep.keySet()) c.tidyChest.merge(item, 1, Integer::sum);
        });
        return true;
    }

    private void tidyStarted(Ctx c) {
        if (c.tidySince < 0) c.tidySince = clock.millis();
    }

    /**
     * The next of this tile's stale filled maps (STORE, the tile's locked map stored): an unlocked map the bot made in
     * this connection; else, while the tile still counts maps the bot made and carries ({@code TileState.unlockedMaps}),
     * one whose pixels show the tile's art ({@link #showsTileArt}) or a picture of the tile's area a held map showed
     * ({@link #showsTilePicture}): the bot's map under a new id after a reconnect or restart. The count keeps a map the owner
     * made of the platform himself out once the bot's own are put away. Locked maps and maps of other places are the owner's
     * and stay. Null = none left.
     */
    private Integer nextStaleMap(Ctx c) {
        MapView maps = bot.maps();
        List<Integer> unlocked = new ArrayList<>();
        for (Integer id : bot.inventory().filledMapIds()) {
            if (id == null || maps.isLocked(id) || c.scrapSkipped.contains(id)) continue;
            if (ownMaps.contains(id)) return id;
            unlocked.add(id);
        }
        if (tile.unlockedMaps <= 0) return null;
        for (Integer id : unlocked) if (showsTileArt(id) || showsTilePicture(id)) return id;
        return null;
    }

    /** A carried Mending tool wants mending now, or a mend is under way: its XP bottles stay ({@link #careForTools}). */
    private boolean mendDue() {
        if (!mendingIds.isEmpty()) return true;
        ToolCare care = ToolCare.of(config.build);
        for (InventoryView.Tool t : bot.inventory().tools()) {
            if (t.mending() && mendTrouble.getOrDefault(t.itemId(), 0) < MEND_TROUBLE_LIMIT && care.wantsMend(t)) return true;
        }
        return false;
    }

    /** Lever/button {@code powered} property, or null if unloaded or not readable. */
    private Boolean leverState(Pos p) {
        WorldView w = bot.world();
        if (!w.isLoaded(p)) return null;
        String v = w.block(p).props().get("powered");
        return v == null ? null : Boolean.valueOf(v);
    }

    /**
     * The cells the bot must not break (NUKE, VERIFY): a fluid, a protected block id ({@link SiteRules#protectedIds}:
     * site.protectedBlocks, the machinery defaults, platform floors) or a cell in a protected region
     * ({@code site.protectedRegions}), whatever the block. The caller goes BLOCKED; nothing is broken.
     */
    private List<Pos> unbreakable(WorldView w, Collection<Pos> cells) {
        Set<String> ids = SiteRules.protectedIds(config.site);
        List<Pos> out = new ArrayList<>();
        for (Pos p : cells) {
            BlockSpec b = w.block(p);
            boolean repairable = SiteRules.repairable(config.site, p, b.id()); // a CS job's own art cells
            if (TilePlan.isFluidBlock(b) || (ids.contains(b.id()) && !repairable)
                || (platform != null && b.id().equals(platform.floorBlock) && !repairable)
                || SiteRules.protectedRegionAt(config.site, p) != null) out.add(p);
        }
        return out;
    }

    /** " (protected region #1 "x")" or " (protected block)" for an {@link #unbreakable} cell; "" for a fluid. */
    private String whyUnbreakable(WorldView w, Pos p) {
        MapArtConfig.RegionCfg r = SiteRules.protectedRegionAt(config.site, p);
        if (r != null) return " (protected region " + SiteRules.label(config.site.protectedRegions, r) + ")";
        return TilePlan.isFluidBlock(w.block(p)) ? "" : " (protected block)";
    }

    private Pos centerPos() {
        return platform.center != null ? platform.center : plan.geo.center();
    }

    /**
     * Where the map is filled and checked, and where the bot goes to load the tile: {@code mapStandAt}, else the
     * centre (soul sand: the floor under the button cross, which the bot reaches from the art layer; the cross itself
     * only by the long east walkway).
     */
    /**
     * Where INSPECT (and a NUKE with no entry walk) walks to load the platform: {@link #mapStandPos}, except on a
     * colour-suppression platform with a {@code foot}: its centre stands among the layers inside the machinery's no-go
     * zone, so the walk there is refused ("the goal lies inside no-go zone"); the foot of the access path is outside it
     * and within view of the layers. Live 2026-11-24 00:49, j0040 shrimp [1,0]: the first CS job after 75 h on soul
     * sand, the platform not loaded, 6 refused walks, BLOCKED (earlier CS jobs began with the bot already beside it).
     */
    private Pos loadStand() {
        if (suppressionTile() && platform.foot != null) return platform.foot;
        return mapStandPos();
    }

    private Pos mapStandPos() {
        return platform.mapStandAt != null ? platform.mapStandAt : centerPos();
    }

    /** Horizontal distance from the bot to {@link #mapStandPos}'s column. */
    private double distanceToMapStand() {
        WorldView w = bot.world();
        Pos c = mapStandPos();
        double dx = w.playerX() - (c.x() + 0.5);
        double dz = w.playerZ() - (c.z() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Where WAIT_CLEAR keeps the bot: within reach of the centre's column and, when the centre stands above the
     * flying machines (moss bridge, soul sand cross), on it rather than on the floor below ({@link RaisedStand}).
     */
    private boolean atClearStand() {
        WorldView w = bot.world();
        return RaisedStand.atStand(platform, centerPos(), w.playerX(), w.playerY(), w.playerZ());
    }

    /**
     * The tile's unlocked map: the one in use, else the carried unlocked map found again. After a reconnect (2b2t gives
     * every map a new id and the own-map memory is empty) or a restart that is: one the bot made in this connection, else
     * the one whose pixels are closest to this tile's art (found by content, so one of the owner's unlocked maps is not taken
     * for it), else the held one, else the last in slot order. LOCK still locks only a map that shows the art.
     */
    private Integer currentUnlockedMap() {
        List<Integer> ids = bot.inventory().filledMapIds();
        MapView maps = bot.maps();
        if (mapId != null && ids.contains(mapId) && !maps.isLocked(mapId)) return mapId;
        mapId = null;
        List<Integer> unlocked = new ArrayList<>();
        for (Integer id : ids) if (!maps.isLocked(id)) unlocked.add(id);
        if (unlocked.isEmpty()) return null;
        Integer held = maps.heldMapId().filter(unlocked::contains).orElse(null);
        if (held != null && ownMaps.contains(held)) return mapId = held;
        for (int i = unlocked.size() - 1; i >= 0; i--) if (ownMaps.contains(unlocked.get(i))) return mapId = unlocked.get(i);
        // a paused ride's stand (ridePause): the map in the hand is the ride's, it was there when the connection went.
        // Not by its pixels: mid-ride an older unlocked map of the same art may be nearer the finished picture
        if (held != null && tile != null && tile.ridePause != null) return mapId = held;
        if (unlocked.size() > 1) {
            Integer best = closestToArt(unlocked, held);
            if (best != null) {
                log.info("{}: map {} found again by its pixels among {} unlocked maps", label(), best, unlocked.size());
                return mapId = best;
            }
        }
        return mapId = held != null ? held : unlocked.getLast();
    }

    /**
     * Of {@code candidates}, the map with the fewest pixels off this tile's art (a tie goes to the held one, then the later
     * slot); null when none has data yet or this tile's expected picture cannot be worked out (chunks not loaded).
     */
    private Integer closestToArt(List<Integer> candidates, Integer held) {
        if (!expectedReady()) return null;
        Integer best = null;
        int fewest = Integer.MAX_VALUE;
        for (int i = candidates.size() - 1; i >= 0; i--) {
            int id = candidates.get(i);
            Optional<byte[]> data = bot.maps().colors(id);
            if (data.isEmpty()) continue;
            int bad = expected.mismatches(data.get());
            if (bad < fewest || (bad == fewest && held != null && id == held)) {
                best = id;
                fewest = bad;
            }
        }
        return best;
    }

    /** {@link #expected} is known, worked out now if this tile's chunks are loaded. */
    private boolean expectedReady() {
        if (job != null && tile != null && platform != null && suppressionTile()) return csExpectedReady(pastRide());
        if (expected != null) return true;
        if (job == null || plan == null || !plan.allLoaded(bot.world())) return false;
        expected = plan.expectedMap(catalog, bot.maps(), bot.world());
        return true;
    }

    /**
     * The tile's locked map. Without a known id (after a reconnect: new map ids) only a locked map the bot made counts:
     * locked by it in this connection, already named for this tile, or showing exactly this tile's art. Any other
     * locked map is the owner's and is never renamed or stored.
     */
    private Integer currentLockedMap() {
        List<Integer> ids = bot.inventory().filledMapIds();
        MapView maps = bot.maps();
        if (lockedMapId != null && ids.contains(lockedMapId)) return lockedMapId;
        lockedMapId = null;
        Optional<Integer> held = maps.heldMapId();
        if (held.isPresent() && ids.contains(held.get()) && maps.isLocked(held.get()) && ownLockedMap(held.get()))
            return lockedMapId = held.get();
        for (int i = ids.size() - 1; i >= 0; i--)
            if (maps.isLocked(ids.get(i)) && ownLockedMap(ids.get(i))) return lockedMapId = ids.get(i);
        return null;
    }

    /** The bot's own locked map: remembered from this connection, or found again by the tile's name or its exact art. */
    private boolean ownLockedMap(int id) {
        if (ownMaps.contains(id)) return true;
        if (job == null || plan == null) return false;
        boolean named = bot.inventory().mapName(id).filter(mapName()::equals).isPresent();
        if (!named && !mapShowsArt(id)) return false;
        log.info("{}: locked map {} found again by its {}", label(), id, named ? "name" : "pixels");
        ownMaps.add(id); // its id for the rest of this connection
        return true;
    }

    /** createMap's callback: the new map is the tile's, and one the bot made (counted on the tile: {@code unlockedMaps}). */
    private void madeMap(Integer id) {
        mapId = id;
        if (id == null) return;
        ownMaps.add(id);
        if (tile != null) {
            tile.unlockedMaps++;
            saveJob(job);
        }
    }

    /** A tool that came from or belongs in the tool chest: only such a worn tool goes back there. */
    private boolean toolChestTool(String id) {
        if (id == null) return false;
        if (toolChestIds.contains(id)) return true;
        if (InventoryView.toolKind(id) == null) return false;
        for (String k : config.build.keepItems) if (id.equals(ItemIds.ns(k))) return true;
        return false;
    }

    private String mapName() {
        String jobName = job.name == null || job.name.isBlank() ? job.id : job.name;
        // a colour-suppression job is one map: its tiles i,0 are build passes, never part of the name (the first live
        // ride's map was named "... 7,0", owner 2026-11-13)
        boolean cs = job.isSuppression();
        boolean single = cs || job.tilesX * job.tilesZ == 1;
        String format = single && config.map.nameFormatSingle != null ? config.map.nameFormatSingle
            : cs ? "{job}" : config.map.nameFormat;
        return fitName(format, jobName, tile.x, tile.z, job.id, NAME_MAX);
    }

    /**
     * {@code format} filled in, at most {@code max} characters (the anvil's limit counts UTF-16 chars): when it is longer,
     * the job name is shortened so the tile's coordinates stay whole; only a format whose other parts alone are too
     * long is cut at the end.
     */
    static String fitName(String format, String jobName, int x, int z, String id, int max) {
        String fill = format.replace("{x}", String.valueOf(x)).replace("{z}", String.valueOf(z)).replace("{id}", id);
        String s = fill.replace("{job}", jobName);
        if (s.length() > max && fill.contains("{job}")) {
            int room = max - (s.length() - jobName.length());
            if (room > 0) {
                String cut = jobName.substring(0, Math.min(jobName.length(), room));
                if (!cut.isEmpty() && Character.isHighSurrogate(cut.charAt(cut.length() - 1)))
                    cut = cut.substring(0, cut.length() - 1);
                s = fill.replace("{job}", cut.strip());
            }
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** An axe above {@code build.toolSafetyFloor} is carried: the fight's weapon ({@code Weapons.pick}: sword, then axe). */
    private boolean carriesWeapon(InventoryView inv) {
        for (InventoryView.Tool t : inv.tools())
            if ("axe".equals(t.kind()) && t.remaining() > config.build.toolSafetyFloor) return true;
        return false;
    }

    /** What GEAR asks the tool chest for when no weapon is carried: the axes of {@code build.keepItems}. */
    private Map<String, Integer> weaponWanted() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String id : config.build.keepItems) if (id.endsWith("_axe")) m.put(id, 1);
        if (m.isEmpty()) m.put("minecraft:diamond_axe", 1);
        return m;
    }

    private Map<String, Integer> toolsWanted() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String id : config.build.keepItems)
            if (id.endsWith("_pickaxe") || id.endsWith("_axe") || id.endsWith("_shovel")) m.put(id, 1);
        if (m.isEmpty()) m.put("minecraft:diamond_pickaxe", 1);
        return m;
    }

    /** Food is topped up when fewer items than this are carried (0 when foodToKeep is 0). */
    private int foodMin() {
        return Math.max(0, Math.min(FOOD_MIN, config.build.foodToKeep));
    }

    /** What a food chest visit asks for: the configured foods ({@link #foodIds}) up to {@code foodToKeep}. */
    private Map<String, Integer> foodWanted(InventoryView inv) {
        int want = Math.max(1, config.build.foodToKeep - foodCarried(inv));
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String id : foodIds()) m.put(id, want);
        if (m.isEmpty()) m.put("minecraft:golden_carrot", want);
        return m;
    }

    /**
     * Configured food carried (hotbar + main): the only food the engine counts, so golden apples and the like never
     * stand in for it (it fetches or alerts instead).
     */
    private int foodCarried(InventoryView inv) {
        int n = 0;
        for (String id : foodIds()) n += inv.count(id);
        return n;
    }

    private List<String> bootsIds() {
        List<String> l = new ArrayList<>();
        for (String id : config.build.keepItems) if (isBoots(id)) l.add(id);
        if (l.isEmpty()) l.addAll(List.of("minecraft:netherite_boots", "minecraft:diamond_boots"));
        return l;
    }

    private String heldBoots(InventoryView inv) {
        if (config.build.gearGuard) {
            InventoryView.Armor a = wearableBoots(inv);
            return a == null ? null : a.itemId();
        }
        for (String id : bootsIds()) if (inv.count(id) > 0) return id;
        return null;
    }

    /**
     * {@code build.gearGuard}: the carried boots GEAR may wear (bootsIds order, the most durable of an id): never at
     * the hard floor, Mending ones below their threshold only with the bottles to mend them ({@link #gearWearable}).
     */
    private InventoryView.Armor wearableBoots(InventoryView inv) {
        GearGuard g = GearGuard.of(config.build);
        int bottles = inv.count(XP_BOTTLE);
        List<InventoryView.Armor> carried = GearGuard.carried(inv.armor());
        for (String id : bootsIds()) {
            InventoryView.Armor best = null;
            for (InventoryView.Armor a : carried) {
                if (!a.itemId().equals(ItemIds.ns(id)) || !gearWearable(g, a, bottles)) continue;
                if (best == null || a.fraction() > best.fraction()) best = a;
            }
            if (best != null) return best;
        }
        return null;
    }

    /** {@code build.gearGuard}: carried boots, none wearable, but a Mending pair would be with bottles: fetch XP first. */
    private boolean bootsWaitForXp(InventoryView inv) {
        if (wearableBoots(inv) != null) return false;
        for (InventoryView.Armor a : GearGuard.carried(inv.armor())) {
            if (GearGuard.isBoots(a) && gearMendable(a)) return true;
        }
        return false;
    }

    /** Bottles that bring the most worn carried Mending boots to 95 % (what GEAR fetches before wearing them). */
    private int bootsXpWanted(InventoryView inv) {
        int n = 0;
        for (InventoryView.Armor a : GearGuard.carried(inv.armor())) {
            if (!GearGuard.isBoots(a) || !a.mending()) continue;
            n = Math.max(n, (int) Math.ceil(Math.max(0, a.max() * GearGuard.TARGET - a.remaining()) / GearGuard.USES_PER_BOTTLE));
        }
        return Math.min(MEND_MAX_BOTTLES, n);
    }

    /** Equips {@code id}: with the guard, the wearable pair of that id (of two, the right one). */
    private BotTask equipBoots(InventoryView inv, String id) {
        InventoryView.Armor a = config.build.gearGuard ? wearableBoots(inv) : null;
        return a != null && a.itemId().equals(id) ? bot.equip(id, a.slot()) : bot.equip(id);
    }

    private static boolean isBoots(String id) {
        return id != null && id.endsWith("_boots");
    }

    private static int sum(Map<String, Integer> m) {
        int n = 0;
        if (m != null) for (int v : m.values()) n += v;
        return n;
    }

    /**
     * The entries of {@code l} in this platform's own area (nearer its centre than any other platform's centre, enabled
     * or not: its return chests, drop points), in the configured order; all of {@code l} only when the platform has none.
     * Leftovers never travel to another platform: a drop that fails or leaves an item behind is tried again at the next
     * restock (live 2026-10-12: one crimson nylium left by the moss drop was carried 256 blocks to the soul sand drop).
     */
    private <T> List<T> ownPlatformOnly(List<T> l, java.util.function.Function<T, Pos> at) {
        if (l == null || l.isEmpty() || platform == null) return l;
        List<Pos> others = new ArrayList<>();
        for (MapArtConfig.PlatformCfg p : config.site.platforms)
            if (p.center != null && p != platform) others.add(p.center);
        if (others.isEmpty()) return l;
        Pos home = centerPos();
        List<T> own = new ArrayList<>();
        for (T t : l) { Pos q = at.apply(t); if (q != null && ownSorter(q, home, others)) own.add(t); }
        return own.isEmpty() ? l : own;
    }

    /** True if {@code chest} is nearer {@code home} (this platform's centre) than every other platform's centre. */
    private static boolean ownSorter(Pos chest, Pos home, List<Pos> others) {
        double own = chest.distSq(home.x(), home.y(), home.z());
        for (Pos o : others) if (chest.distSq(o.x(), o.y(), o.z()) < own) return false;
        return true;
    }

    /**
     * {@code l} closest to this platform's centre first (stable): not to the bot, which a stock scan or a drop may have
     * taken to a far sorter. Without a platform, closest to the bot.
     */
    private List<Pos> nearestToPlatform(List<Pos> l) {
        if (platform == null) return byDistance(l);
        Pos c = centerPos();
        List<Pos> out = new ArrayList<>(l);
        out.sort(Comparator.comparingDouble(p -> p.distSq(c.x(), c.y(), c.z())));
        return out;
    }

    private List<Pos> byDistance(List<Pos> l) {
        WorldView w = bot.world();
        double x = w.playerX(), y = w.playerY(), z = w.playerZ();
        List<Pos> out = new ArrayList<>(l);
        out.sort(Comparator.comparingDouble(p -> p.distSq(x, y, z)));
        return out;
    }

    /** Column-by-column, alternating direction, so the breaker walks a short route. */
    private static List<Pos> serpentine(List<Pos> l) {
        List<Pos> out = new ArrayList<>(l);
        out.sort((a, b) -> a.x() != b.x() ? Integer.compare(a.x(), b.x())
            : (Math.floorMod(a.x(), 2) == 0 ? Integer.compare(a.z(), b.z()) : Integer.compare(b.z(), a.z())));
        return out;
    }

    private static String sample(List<Pos> wrong, List<Pos> missing) {
        Pos p = !wrong.isEmpty() ? wrong.getFirst() : !missing.isEmpty() ? missing.getFirst() : null;
        return p == null ? "" : " (e.g. " + p + ")";
    }

    private static Phase parsePhase(String s) {
        if (s == null) return null;
        try {
            return Phase.valueOf(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The unit's part for an alert's reference: "lanes 2-3", "tail", or empty for a whole map. */
    private String unitPart() {
        return bandUnit() ? unit.spec().band().label() : tailUnit() ? "tail" : "";
    }

    /** An alert's last line, the place it is about: "\nSnowy Angel tile 6,1 (#fbw204nw4zvwd, lanes 2-3)"; empty without a job. */
    private String whereLine() {
        return job == null ? "" : "\n" + AlertText.where(job, tile, unitPart());
    }

    /** An alert's last line when its text names the map already: "\n(#fbw204nw4zvwd, lanes 2-3)". */
    private String refLine() {
        return job == null ? "" : "\n" + AlertText.ref(job, unitPart());
    }

    /** Every engine alert goes out here: item ids in plain words ("white carpet"). */
    private void alert(String title, String body) {
        notifier.alert(AlertText.plain(title), AlertText.plain(body));
    }

    /** {@link #alert} with the place it is about as the last line ({@link #whereLine}): the cause first, ids last. */
    private void alertHere(String title, String body) {
        alert(title, body + whereLine());
    }

    private String label() {
        if (job == null) return "-";
        String part = bandUnit() ? " " + unit.spec().band().label() : tailUnit() ? " tail" : "";
        return "#" + job.id + " " + job.name + (tile != null ? " [" + tile.x + "," + tile.z + "]" + part : "");
    }

    private String jobSummary(Job j) {
        int blocks = 0;
        long start = Long.MAX_VALUE;
        long end = 0;
        List<String> names = new ArrayList<>();
        for (Job.TileState t : j.tiles) {
            blocks += t.total;
            if (t.startedAt > 0) start = Math.min(start, t.startedAt);
            end = Math.max(end, t.finishedAt);
            if (t.mapName != null) names.add(t.mapName);
        }
        String took = start == Long.MAX_VALUE || end < start ? "?" : formatDuration(end - start);
        return "#" + j.id + " " + j.name + ": " + j.tiles.size() + " map" + (j.tiles.size() == 1 ? "" : "s")
            + " (" + j.tilesX + "x" + j.tilesZ + "), " + blocks + " blocks, " + took
            + (names.isEmpty() ? "" : "\nMaps: " + String.join(", ", names));
    }

    private static String formatDuration(long ms) {
        long m = ms / 60_000;
        return m >= 60 ? (m / 60) + " h " + (m % 60) + " min" : m + " min";
    }

    private void warnOnce(String key, String message) {
        if (warned.add("warn:" + key)) log.warn(message);
    }

    private void alertOnce(String key, String title, String body) {
        if (warned.add("alert:" + key)) {
            log.warn("{}: {}", title, body);
            alertHere(title, body);
        }
    }

    private void renderAsync(Supplier<byte[]> png, String title, String body, String fileName) {
        renderAsync(png, title, body, fileName, false);
    }

    /** {@code verboseOnly}: posted as a routine step ({@link Notifier#detailImage}, {@link Notifier#detail}). */
    private void renderAsync(Supplier<byte[]> png, String title, String body, String fileName, boolean verboseOnly) {
        try {
            executor.execute(() -> {
                try {
                    if (verboseOnly) notifier.detailImage(title, body, fileName, png.get());
                    else notifier.image(title, body, fileName, png.get());
                } catch (RuntimeException e) {
                    log.warn("render failed: {}", e.toString());
                    if (verboseOnly) notifier.detail(title, body);
                    else notifier.info(title, body);
                }
            });
        } catch (RuntimeException e) {
            log.warn("render rejected: {}", e.toString());
        }
    }

    // ---- persistence ----

    private void saveJob(Job j) {
        if (j != null) writer.submit(() -> store.save(j));
    }

    private void saveStock() {
        writer.submit(store::saveStock);
    }

    private void saveCheckpoint() {
        EngineCheckpoint cp = new EngineCheckpoint();
        if (job != null && tile != null && platform != null) {
            cp.jobId = job.id;
            cp.tileX = tile.x;
            cp.tileZ = tile.z;
            cp.platformId = platform.id;
            cp.stored = tile.status == TileStatus.STORED;
        }
        cp.phase = phase.name();
        cp.phaseSince = phaseSince;
        cp.clearAttempts = clearAttempts;
        cp.repairPasses = repairPasses;
        cp.fillAttempts = fillAttempts;
        cp.blockedFrom = phase == Phase.BLOCKED ? (blockedFrom != null ? blockedFrom : Phase.INSPECT).name() : null;
        cp.note = note;
        cp.lastServerRestartAt = lastServerRestartAt;
        writer.submit(() -> store.saveCheckpoint(cp));
    }

    // ---- eating and health (vitals) ----

    /** The engine eats by itself (and keeps ZenithProxy's AutoEat off while active). */
    private boolean engineEats() {
        return config.build.eatFoodLevel > 0;
    }

    /** Eat now, even mid-task: the hunger bar at {@code FOOD_CRITICAL} or below, or low health with the bar not full. */
    private static boolean urgentHunger(int food, float health) {
        return food <= FOOD_CRITICAL || (health <= EAT_HEALTH && food < 20);
    }

    /** Eat at the next safe point: the bar at or below {@code eatFoodLevel}, or low health with the bar not full. */
    private boolean hungry(int food, float health) {
        return food < 20 && (food <= config.build.eatFoodLevel || health <= EAT_HEALTH);
    }

    /**
     * Every tick while the engine acts (not paused, BLOCKED or backing off), whatever task runs: starts and ends a
     * low-health hold, and asks the running task to park at its next safe point when the bot must eat or stand still now
     * (critical hunger, low health). A task that cannot park runs to its end (they are short). The eating itself
     * happens between tasks ({@link #tendVitals}).
     */
    private void checkVitals() {
        if (!suppressing) return;
        InventoryView inv = bot.inventory();
        float hp = inv.health();
        int food = inv.foodLevel();
        if (hp <= 0) return; // dead: the teleport guard's death stop takes it from here
        if (!healthHold && hp <= HEALTH_STOP) startHealthHold(hp, food);
        else if (healthHold && hp >= HEALTH_RESUME) endHealthHold(hp);
        boolean noTotem = totemMissing(inv);
        if (noTotem) alertNoTotem(inv);
        else if (totemGoneSince < 0) noTotemAlerted = false;
        if (task == null || vitalsTask || askedToPark == task) return;
        boolean eatNow = engineEats() && urgentHunger(food, hp) && tick >= eatRetryTick;
        if (!healthHold && !eatNow && !noTotem) return;
        askedToPark = task;
        parkAccepted = task.requestPark();
        log.warn("hunger {}/20, health {}/20{}: {}", food, fmt(hp), noTotem ? ", no totem" : "", parkAccepted
            ? "stopping " + task.name() + " at its next safe point to "
                + (eatNow ? "eat" : healthHold ? "stand still" : "wait for a totem")
            : "finishing " + task.name() + " first (it cannot stop midway)");
    }

    /**
     * {@code safety.requireTotem}: no totem of undying in the offhand, and none in the inventory that the offhand repair
     * puts back (AIRPLACE mode with {@code build.offhandRepair}; in LOOK mode nothing does, the owner's AutoTotem being
     * suppressed).
     */
    private boolean totemMissing(InventoryView inv) {
        if (!config.safety.requireTotem || inv.offhand().getOrDefault(TOTEM, 0) > 0 || totemRepairable(inv)) {
            totemGoneSince = -1;
            return false;
        }
        if (totemGoneSince < 0) totemGoneSince = tick;
        // the cache may have lost a totem the server has (drill gs2): no "No totem" before the server's full inventory
        // has answered the repair's check (its own failures end the wait: offhandRepairDue)
        if (offhandRepairDue() && bot.offhandCheckWanted()) return false;
        // a moment without one (the inventory arriving after a login, an item on the cursor) is no missing totem
        return tick - totemGoneSince >= TOTEM_GONE_TICKS;
    }

    private boolean totemRepairable(InventoryView inv) {
        return inv.count(TOTEM) > 0 && config.build.offhandRepair && PlaceMode.of(config.build) == PlaceMode.AIRPLACE;
    }

    /** One always-on "No totem" alert per episode (the offhand holding a totem again ends it). */
    private void alertNoTotem(InventoryView inv) {
        if (noTotemAlerted) return;
        noTotemAlerted = true;
        Map<String, Integer> off = inv.offhand();
        String holds = off.isEmpty() ? "nothing" : String.join(", ", off.keySet());
        log.warn("no totem: the offhand holds {}, the inventory {}; no walking task until one is in the offhand", holds,
            inv.count(TOTEM) > 0 ? "has " + inv.count(TOTEM) + " that nothing puts back" : "has none");
        int carried = inv.count(TOTEM);
        alertHere("No totem", "The offhand holds " + holds
            + (carried > 0 ? "; the inventory has " + carried + " totem(s) but nothing puts one back (LOOK mode or"
                + " build.offhandRepair off)" : " and the inventory has no totem of undying (one was used up?)")
            + ". The bot stops where it stands (it still fights and eats) until a totem is in the offhand; then it"
            + " carries on. Kill switch: safety.requireTotem.");
    }

    /**
     * Every tick while the engine acts: the offhand's item should go back from the inventory (the airplace guard):
     * the running task is asked to park at its next safe point; the repair itself
     * runs between tasks ({@link #tendVitals}). A task that cannot park runs to its end.
     */
    private void checkOffhand() {
        if (!suppressing || task == null || vitalsTask || askedToPark == task || !offhandRepairDue()) return;
        askedToPark = task;
        parkAccepted = task.requestPark();
        log.warn("offhand without its item: {}", parkAccepted
            ? "stopping " + task.name() + " at its next safe point to put it back"
            : "finishing " + task.name() + " first (it cannot stop midway)");
    }

    private boolean offhandRepairDue() {
        return tick >= offhandRetryTick && offhandFailures < OFFHAND_MAX_FAILURES && bot.offhandRepairWanted();
    }

    /** Puts the offhand's item back (between tasks, or while a task is parked). True if it started the task. */
    private boolean restoreOffhand() {
        if (!offhandRepairDue()) {
            if (!bot.offhandRepairWanted()) offhandFailures = 0; // repaired, or nothing to put back: a new episode
            return false;
        }
        log.info("putting the offhand's item back{}", parkedTask != null ? "; " + parkedTask.name() + " waits" : "");
        runVitals(bot.restoreOffhand(), () -> offhandFailures = 0, r -> {
            offhandFailures++;
            offhandRetryTick = tick + OFFHAND_RETRY_TICKS;
            log.warn("putting the offhand's item back failed ({} in a row): {}", offhandFailures, r);
            if (offhandFailures >= OFFHAND_MAX_FAILURES) {
                alertOnce("offhand-repair", "Offhand repair failed", "Putting the offhand's item back failed "
                    + offhandFailures + " times (" + r + "). Airplace stays off; lanes place by LOOK. Put the totem"
                    + " into the offhand by hand.");
            }
        });
        return true;
    }

    private void startHealthHold(float hp, int food) {
        healthHold = true;
        holdStartHealth = hp;
        holdLeaves = 0;
        log.warn("low health {}/20 (hunger {}/20): stopping where the bot stands until it is back to {}/20", fmt(hp),
            food, fmt(HEALTH_RESUME));
        long now = clock.millis();
        if (lastLowHealthAlert >= 0 && now - lastLowHealthAlert < LOW_HEALTH_ALERT_MS) return;
        lastLowHealthAlert = now;
        alertHere("Low health", "Health " + fmt(hp)
            + "/20, hunger " + food + "/20. The bot stopped where it stands and "
            + (food < 20 ? "eats" : "waits") + " to regenerate; it carries on at " + fmt(HEALTH_RESUME)
            + "/20. A fall, a mob, fire? Check it in game.");
    }

    private void endHealthHold(float hp) {
        healthHold = false;
        log.info("health back to {}/20; carrying on", fmt(hp));
        wakeTick = Math.min(wakeTick, tick);
    }

    /**
     * Between tasks, and while a task is parked: eats (standing still), fetches food, stands still for low health or
     * goes BLOCKED. Normal eating (at or below {@code eatFoodLevel}) waits for a calm phase: not IDLE, SELECT, CLEAR
     * (a lever may be held for a set time) or BLOCKED; urgent eating ({@link #urgentHunger}, low health) happens in any
     * phase except while a lever is held, and a parked task waits until the bar is above {@code eatFoodLevel} again.
     * True if it started a task, holds (sleeps) or blocked; false when nothing is needed now (a parked task resumes).
     * IDLE with nothing to do (the modules are back, AutoEat eats): only the starving rescue ({@link #rescueStarving}).
     */
    private boolean tendVitals() {
        if (!suppressing) return rescueStarving();
        InventoryView inv = bot.inventory();
        int food = inv.foodLevel();
        float hp = inv.health();
        if (hp <= 0) return false;
        boolean urgent = healthHold || urgentHunger(food, hp);
        boolean calm = phase != Phase.IDLE && phase != Phase.SELECT && phase != Phase.CLEAR && phase != Phase.BLOCKED;
        boolean leverHeld = phase == Phase.CLEAR && ctx.leverStage != 0;
        boolean mayEat = engineEats() && !leverHeld && (calm || urgent || parkedTask != null);
        if (mayEat && hungry(food, hp) && eat(inv, food, hp, urgent)) return true;
        if (!leverHeld && restoreOffhand()) return true;
        // a fight comes before standing still: a bot standing still under fire dies (live 2026-10-12, pillagers)
        if (combatWanted) return false;
        if (healthHold && !leverHeld) return holdForHealth(inv, food, hp);
        if (!leverHeld && totemMissing(inv)) {
            vitalsNote = "no totem: waiting for one in the offhand";
            sleep(VITALS_POLL_TICKS);
            return true;
        }
        vitalsNote = "";
        return false;
    }

    /** Eats one food item, or fetches food from the food chests. True if it started a task or blocked. */
    private boolean eat(InventoryView inv, int food, float hp, boolean urgent) {
        boolean haveFood = foodCarried(inv) > 0;
        if (haveFood) outOfFood = false;
        if (starving(food, hp, haveFood)) return true;
        if (tick < eatRetryTick) return false;
        String waits = parkedTask != null ? "; " + parkedTask.name() + " waits" : "";
        if (haveFood) {
            foodFetches = 0;
            log.info("eating (hunger {}/20, health {}){}", food, Math.round(hp), waits);
            vitalsNote = EATING_NOTE + ": hunger " + food + "/20, health " + fmt(hp) + "/20";
            runVitals(bot.eat(foodIds()), () -> eatFailures = 0, r -> {
                eatFailures++;
                eatRetryTick = tick + (urgent ? EAT_RETRY_URGENT_TICKS : EAT_RETRY_TICKS);
                log.warn("eating failed ({} in a row): {}", eatFailures, r);
            });
            return true;
        }
        List<Pos> chests = config.site.storage.foodChests;
        if (foodFetches < chests.size()) {
            Pos chest = chests.get(foodFetches++);
            log.info("out of food (hunger {}/20, health {}): fetching from the food chest{}", food, Math.round(hp), waits);
            if (starvingNow(food, hp)) announceStarving(hp, "");
            vitalsNote = "fetching food: hunger " + food + "/20, health " + fmt(hp) + "/20";
            runVitals(bot.withdraw(chest, foodWanted(inv), r -> { }), null, r -> log.warn("food withdraw failed: {}", r));
            return true;
        }
        foodFetches = 0;
        outOfFood = true;
        alertOnce("food", "No food", "No food in the inventory or the food chests (hunger " + food + "/20, health "
            + fmt(hp) + "/20).");
        if (starving(food, hp, false)) return true;
        eatRetryTick = tick + EAT_RETRY_TICKS;
        return false;
    }

    /**
     * BLOCKED (true) when the bot cannot keep itself alive: hunger at {@code FOOD_CRITICAL} or below, or health at
     * {@code HEALTH_BLOCK} or below, while out of food everywhere or with eating failing again and again. Resume retries
     * all of it. BLOCKED gives ZenithProxy's modules back (AutoEat, and AutoDisconnect if it is on).
     */
    private boolean starving(int food, float hp, boolean haveFood) {
        if (food > FOOD_CRITICAL && hp > HEALTH_BLOCK) return false;
        if (haveFood ? eatFailures < EAT_FAILURES_BLOCK : !outOfFood) return false;
        String why = haveFood ? "eating failed " + eatFailures + " times in a row"
            : "out of food: no food in the inventory or the food chests";
        outOfFood = false;
        eatFailures = 0;
        eatRetryTick = 0;
        block(why + " (hunger " + food + "/20, health " + fmt(hp) + "/20)");
        return true;
    }

    /**
     * Low health with nothing to eat right now: stand still for natural regeneration (the bar at 18 or more). BLOCKED
     * when it cannot come (out of food with the bar below 18), or when health still drops to {@code HEALTH_BLOCK} while
     * the bot stands still (a mob, fire: a human must look).
     */
    private boolean holdForHealth(InventoryView inv, int food, float hp) {
        List<WorldView.Mob> threats = hitByMobs() && !duringClear() ? threats() : List.of();
        if (!threats.isEmpty() && fightPossible()) {
            // the next combat poll starts the fight (a fight comes before leaving and before BLOCKED)
            vitalsNote = "low health " + fmt(hp) + "/20, hurt with " + Combat.describe(threats) + " around: fighting";
            sleep(COMBAT_POLL_TICKS);
            return true;
        }
        // never during the clear: the bot stays where it waits for it while the machines move (startEscape too)
        if (config.safety.leaveWhenHurt && holdLeaves < MAX_HOLD_LEAVES && !duringClear()) {
            // mobs hurting a bot that cannot fight them (combat off, a fight that ran out of time): away at once
            if (hp <= holdStartHealth - LEAVE_DROP || !threats.isEmpty()) {
                leaveHarm(hp, threats);
                return true;
            }
        }
        if (hp <= HEALTH_BLOCK && hp < holdStartHealth) {
            block("health " + fmt(hp) + "/20 and still dropping while standing still (from " + fmt(holdStartHealth)
                + "/20): check the bot in game");
            return true;
        }
        if (food < REGEN_FOOD && foodCarried(inv) == 0 && outOfFood) {
            block("health " + fmt(hp) + "/20 with hunger " + food + "/20 and no food to regenerate: out of food in the"
                + " inventory and the food chests");
            return true;
        }
        vitalsNote = "low health " + fmt(hp) + "/20: standing still to regenerate";
        sleep(VITALS_POLL_TICKS);
        return true;
    }

    /**
     * {@code safety.leaveWhenHurt}: health still drops while the bot stands still for it (water, fire, a block at its
     * head, something it cannot see): it leaves the cell instead of staying there. A short walk to open floor with no
     * fluid and no hazard at the feet or head, 4 to 6 blocks away and not next to this cell (the setback escape's
     * planner, its widest ring); the hold then goes on there with the health it has. A leave that finds no way out is
     * logged; the next drop to {@code HEALTH_BLOCK} goes BLOCKED as before.
     */
    private void leaveHarm(float hp, List<WorldView.Mob> threats) {
        holdLeaves++;
        WorldView w = bot.world();
        Pos here = new Pos((int) Math.floor(w.playerX()), (int) Math.floor(w.playerY() + 0.1251),
            (int) Math.floor(w.playerZ()));
        String why = threats.isEmpty() ? "still dropping while standing still (from " + fmt(holdStartHealth) + "/20)"
            : "hurt with " + Combat.describe(threats) + " around and no fight";
        log.warn("health {}/20 {}: {} {} ({} of {})", fmt(hp), why, threats.isEmpty() ? "leaving" : "retreating from",
            here, holdLeaves, MAX_HOLD_LEAVES);
        vitalsNote = "low health " + fmt(hp) + "/20, " + why + ": leaving " + here;
        Runnable rebase = () -> holdStartHealth = bot.inventory().health();
        BotTask t = threats.isEmpty() ? bot.escape(new EscapeRequest(w.playerX(), w.playerY(), w.playerZ(), 3, Set.of(here)))
            : bot.retreat(threats);
        runVitals(t, rebase, r -> {
            log.warn("leaving {} failed: {}", here, r);
            rebase.run();
        });
    }

    /**
     * A fight would start for the {@link #threats} of a hurt bot: there is a fight area (combat and self-defence on,
     * or the platform fights), one of them is not set aside as out of reach, and no cooldown holds them off
     * ({@link #meleeThreat}).
     */
    private boolean fightPossible() {
        if (combatArea() == null) return false;
        forgetIgnoredWhenHit();
        List<WorldView.Mob> fightable = nearBot(notSetAside(listedTypes()));
        if (fightable.isEmpty()) return false;
        return clock.millis() >= combatCooldownUntil || meleeThreat(fightable);
    }

    /** Listed mobs ({@code combat.mobs}) within {@code combat.radius} of the bot, whether or not combat is on. */
    private List<WorldView.Mob> threats() {
        Set<String> types = listedTypes();
        return types.isEmpty() ? List.of() : nearBot(mobsSeen(types));
    }

    /** The mobs of {@code types} the client sees, never a teammate. */
    private List<WorldView.Mob> mobsSeen(Set<String> types) {
        return Combat.withoutTeammates(bot.world().mobs(types), teammates);
    }

    /** {@code combat.mobs} as the entity cache names them. */
    private Set<String> listedTypes() {
        return Combat.types(config.combat);
    }

    /** The mobs of {@code mobs} within {@code combat.radius} of the bot. */
    private List<WorldView.Mob> nearBot(List<WorldView.Mob> mobs) {
        WorldView w = bot.world();
        double r = Math.max(1, config.combat.radius);
        return mobs.stream().filter(m -> m.distSq(w.playerX(), w.playerY(), w.playerZ()) <= r * r).toList();
    }

    /**
     * The mobs of {@code types} the client sees, without those set aside as out of reach. Every such mob is looked at,
     * not only those in a fight area: a record goes only when its mob is gone from the entity cache (killed,
     * despawned, unloaded), never because the bot walked away from it ({@link MobMemory}).
     */
    private List<WorldView.Mob> notSetAside(Set<String> types) {
        if (types.isEmpty()) return List.of();
        // build.fightSetAsideUnreachable: by entity id, kept while the client does not track the mob (SetAsideMobs)
        if (config.build.fightSetAsideUnreachable) return setAsideMobs.without(mobsSeen(types), setAsideLook());
        return ignoredMobs.without(mobsSeen(types));
    }

    /** Where the bot is now, for the set-aside's reach rule and time limit ({@link SetAsideMobs.Look}). */
    private SetAsideMobs.Look setAsideLook() {
        WorldView w = bot.world();
        long limit = Math.max(1, config.build.fightSetAsideMinutes) * 1200L;
        return new SetAsideMobs.Look(tick, limit, w.playerX(), w.playerY(), w.playerZ(),
            platform != null ? platform.artY : Double.NaN);
    }

    /** {@link #run} for the vitals' own tasks (eat, food fetch): never asked to park. */
    private void runVitals(BotTask t, Runnable onSuccess, Consumer<String> onFailure) {
        run(t, onSuccess, onFailure);
        vitalsTask = true;
    }

    /**
     * The first decision after the bot's ticks (re)started: what the
     * disconnect may have dropped ({@code BotActions.takeDisconnectDrops}: a stack on the cursor, or in the cartography
     * table's or anvil's slots, which the server drops when the player leaves). With
     * {@code build.recoverDisconnectDrops} a valuable one ({@link Valuables}: tools, armour, the elytra, maps, totems,
     * XP bottles, rockets) is picked up from within {@link EngineTuning#DROP_RADIUS} blocks before anything else, one
     * log line for the lot ({@link #logDropOutcomes}). A material (carpets; the full stack a top-up had on the cursor)
     * is not worth the walk: a debug line. A fight or a health hold goes first (the pickup waits for the next decision
     * after it; a dropped stack lasts 5 minutes). True if it started the pickup.
     */
    private boolean recoverDisconnectDrops() {
        if (!dropsDue || combatWanted || healthHold) return false;
        dropsDue = false;
        List<DisconnectDrop> drops = bot.takeDisconnectDrops();
        if (drops.isEmpty()) return false;
        List<DisconnectDrop> worth = new ArrayList<>();
        for (DisconnectDrop d : drops) {
            if (config.build.recoverDisconnectDrops && Valuables.isValuable(d.item())) {
                worth.add(d);
            } else {
                log.debug("Disconnect drop: {} {} {} when the connection ended (at {}): {}", d.count(), d.item(),
                    d.where(), d.at(), config.build.recoverDisconnectDrops ? "a material, not worth the walk"
                        : "build.recoverDisconnectDrops off");
            }
        }
        if (worth.isEmpty()) return false;
        log.debug("Disconnect drop: looking for {} around {}", worth, worth.getFirst().at());
        runVitals(bot.recoverDrops(worth, DROP_RADIUS, this::logDropOutcomes), null,
            r -> log.info("Disconnect drop: the pickup ended early ({}); carrying on", r));
        return true;
    }

    /**
     * The pickup's one log line, without coordinates: what came back, what is lost (a stack still there in the last
     * look before the connection ended, not found on the ground and not back in the inventory). A stack back in the
     * inventory was never dropped (the click that took it had not reached the server) or was picked up again at the
     * rejoin: debug only. So is a stack seen only in the moments before and not found (the clicks that moved it on had
     * reached the server). A cursor stack looked for and found neither on the ground nor in the inventory most likely
     * never left the container it was being taken from (that click had not reached the server): said so, not called
     * lost.
     */
    private void logDropOutcomes(List<DisconnectDrop.Outcome> outcomes) {
        List<String> parts = new ArrayList<>();
        for (DisconnectDrop.Outcome o : outcomes) {
            DisconnectDrop d = o.drop();
            String item = ItemIds.path(d.item());
            int lost = d.count() - o.pickedUp();
            if (o.back()) {
                log.debug("Disconnect drop: {} {} back in the inventory ({} when the connection ended): not dropped,"
                    + " or picked up again at the rejoin", d.count(), item, d.where());
            } else if (o.pickedUp() > 0) {
                parts.add("picked up " + o.pickedUp() + " " + item + " (" + d.where() + ")"
                    + (d.last() && lost > 0 && o.missed() != null ? ", " + lost + " lost: " + o.missed() : ""));
            } else if (d.last() && DisconnectDrop.CURSOR.equals(d.where()) && o.missed() != null
                && o.missed().startsWith(DisconnectDrop.NONE_ON_THE_GROUND)) {
                parts.add(d.count() + " " + item + " not found (" + d.where() + " when the connection ended; "
                    + o.missed() + ", nor back in the inventory): most likely still in the container it was being"
                    + " taken from");
            } else if (d.last()) {
                parts.add(d.count() + " " + item + " lost (" + d.where() + " when the connection ended; " + o.missed() + ")");
            } else {
                log.debug("Disconnect drop: no {} on the ground ({}): the clicks that moved it on had gone through",
                    item, o.missed());
            }
        }
        if (!parts.isEmpty()) log.info("Disconnect drop: {}", String.join("; ", parts));
    }

    /** The running task stopped at a safe point: set it aside until the bot has eaten or stood still. */
    private void parkTask() {
        parkedTask = task;
        parkedOk = taskOk;
        parkedFail = taskFail;
        parkedAttempt = taskAttempt;
        parkedAttemptCtx = taskAttemptCtx;
        task = null;
        taskOk = null;
        taskFail = null;
        taskAttempt = null;
        taskAttemptCtx = null;
        askedToPark = null;
        parkAccepted = false;
        InventoryView inv = bot.inventory();
        log.info("{} stopped at a safe point (hunger {}/20, health {}/20)", parkedTask.name(), inv.foodLevel(),
            fmt(inv.health()));
    }

    /** Nothing more to eat or wait for: the parked task carries on (from where the bot stands now). */
    private void resumeParked() {
        BotTask t = parkedTask;
        task = t;
        taskOk = parkedOk;
        taskFail = parkedFail;
        taskAttempt = parkedAttempt;
        taskAttemptCtx = parkedAttemptCtx;
        parkedTask = null;
        parkedOk = null;
        parkedFail = null;
        parkedAttempt = null;
        parkedAttemptCtx = null;
        vitalsTask = false;
        rescueTask = false;
        vitalsNote = "";
        acted = true;
        log.info("carrying on with {}", t.name());
        t.resume();
    }

    // ---- starving rescue: food while the engine does not work ----
    //
    // Health-based disconnects are not the plugin's: ZenithProxy's AutoDisconnect does that, configured by the owner.
    // Eating is the plugin's (it switches AutoEat off while it works), and starvation damage goes on while the engine
    // is paused, SAFETY STOP-locked or BLOCKED (2b2t is Hard: starvation kills). So the engine keeps one duty while it
    // does not work: a starving bot with no food gets food from the food chest.

    /**
     * Starving: the hunger bar at 0 (starvation damage), or health at or below {@code HEALTH_BLOCK} with the bar too
     * low to regenerate.
     */
    static boolean starvingNow(int food, float hp) {
        return food <= 0 || (hp <= HEALTH_BLOCK && food < REGEN_FOOD);
    }

    /**
     * Every tick (bot ticks on): a starving episode lasts while no food is carried and the bar is at
     * {@code FOOD_CRITICAL} or below or health at {@code HEALTH_BLOCK} or below. Once food is carried, or both are
     * above, the episode is over: the next one fetches and alerts again.
     */
    private void trackFoodEpisode() {
        InventoryView inv = bot.inventory();
        if (inv.health() <= 0) return;
        boolean need = foodCarried(inv) == 0 && (inv.foodLevel() <= FOOD_CRITICAL || inv.health() <= HEALTH_BLOCK);
        if (!need && (rescueSpent || starvingAnnounced || !rescueTried.isEmpty() || rescueWaitSince >= 0)) {
            endFoodEpisode();
        }
    }

    /** Forgets the rescue's food chests tried and the alert: the next starving episode starts afresh. */
    private void endFoodEpisode() {
        rescueTried.clear();
        rescueSpent = false;
        rescueWaitSince = -1;
        starvingAnnounced = false;
    }

    /**
     * While not working (paused, SAFETY STOP, BLOCKED, a back-off): ticks the rescue's task, or starts one. Any other
     * task was cancelled by the pause, stop or block and is never ticked here.
     */
    private void tickRescue() {
        if (task == null) {
            if (!rescueStarving()) tendGear(GearMode.STOPPED); // the durability guard while not working
            return;
        }
        if (!rescueTask) return;
        TaskStatus s = task.tick();
        if (s == TaskStatus.RUNNING) return;
        finishTask(s);
        vitalsNote = "";
    }

    /**
     * The starving rescue, while the engine does not work: paused, SAFETY STOP-locked, BLOCKED ({@link #tickRescue}),
     * or IDLE with nothing to do ({@link #tendVitals}). Only when starving ({@link #starvingNow}), from
     * {@code START_DELAY_TICKS} after bot ticks start (inventory and chunks arrive first), not during a setback back-off,
     * and not after a safety stop in this connection (the kill switch and the teleport guard mean "do not move") until
     * a new connection or resume.
     * <ul>
     *   <li>Food carried: ZenithProxy's AutoEat eats it (the engine gives the modules back while not working). With
     *       AutoEat off, and {@code build.eatFoodLevel} above 0, the engine eats.</li>
     *   <li>No food carried: a food fetch, each food chest once per episode (a fetch cut short by a pause or a back-off
     *       does not count), in config order. A chest whose chunk is not loaded is waited for up to
     *       {@code RESCUE_LOAD_WAIT_TICKS}, then counts as out of reach. The fetch finds its own stand spot (the walk
     *       search); one it cannot reach fails, and the next chest is tried. The fetch runs with the modules off like any
     *       task (AutoEat must not eat from the open chest menu); they come back after it.</li>
     *   <li>Every chest tried and no food: the "No food" alert and BLOCKED (once per episode). No disconnect.</li>
     * </ul>
     * Nothing else runs: no phase step, no building, no other walk. The pause, the safety lock and BLOCKED stay. True if
     * it started a task or blocked.
     */
    private boolean rescueStarving() {
        if (!ticking || !restored || tick < rescueReadyTick) return false;
        if (backingOff && clock.millis() < backoffUntil) return false;
        InventoryView inv = bot.inventory();
        float hp = inv.health();
        int food = inv.foodLevel();
        if (hp <= 0 || !starvingNow(food, hp)) {
            vitalsNote = "";
            return false;
        }
        if (rescueHeld) {
            if (!starvingAnnounced) {
                starvingAnnounced = true;
                String body = "Starving (hunger " + food + "/20, health " + fmt(hp) + "/20), but the safety stop in this"
                    + " connection holds the food fetch. `" + config.discord.commandPrefix + " resume`, or a reconnect,"
                    + " lets the bot get food. Health disconnects are ZenithProxy's AutoDisconnect.";
                log.warn("Starving: {}", body);
                alertHere("Starving", body);
            }
            return false;
        }
        if (foodCarried(inv) > 0) {
            if (modules.isEnabled(AUTO_EAT) || !engineEats() || tick < eatRetryTick) {
                vitalsNote = "";
                return false; // AutoEat eats (or the owner eats by hand with eatFoodLevel 0)
            }
            log.warn("Starving: eating (hunger {}/20, health {}); AutoEat is off", food, fmt(hp));
            vitalsNote = EATING_NOTE + ": hunger " + food + "/20, health " + fmt(hp) + "/20";
            runRescue(bot.eat(foodIds()), () -> eatFailures = 0, r -> {
                eatFailures++;
                eatRetryTick = tick + EAT_RETRY_URGENT_TICKS;
                log.warn("eating failed ({} in a row): {}", eatFailures, r);
            });
            return true;
        }
        if (rescueSpent) return false;
        Pos chest = null;
        boolean waiting = false;
        for (Pos p : config.site.storage.foodChests) {
            if (p == null || rescueTried.contains(p)) continue;
            if (bot.world().isLoaded(p)) {
                chest = p;
                break;
            }
            waiting = true;
        }
        if (chest == null && waiting) {
            if (rescueWaitSince < 0) {
                rescueWaitSince = tick;
                log.warn("Starving (health {}): waiting for the food chest's chunk to load", fmt(hp));
            }
            if (tick - rescueWaitSince < RESCUE_LOAD_WAIT_TICKS) return false;
        }
        if (chest == null) {
            rescueSpent = true;
            vitalsNote = "";
            String why = config.site.storage.foodChests.isEmpty() ? "no food chest configured (site.storage.foodChests)"
                : waiting ? "the food chests gave none, or are not loaded (the bot is far from them)"
                : "the food chests gave none";
            String body = "Starving with no food (hunger " + food + "/20, health " + fmt(hp) + "/20): " + why + ". "
                + "Restock the food chest, then `" + config.discord.commandPrefix + " resume`, or reconnect the bot: "
                + "the next connection fetches again. Health disconnects are ZenithProxy's AutoDisconnect.";
            log.warn("No food: {}", body);
            alertHere("No food", body);
            if (phase != Phase.BLOCKED) {
                block("out of food: " + why + " (hunger " + food + "/20, health " + fmt(hp) + "/20)");
                return true;
            }
            return false;
        }
        rescueWaitSince = -1;
        announceStarving(hp, rescueState());
        vitalsNote = "starving: fetching food from the food chest (health " + fmt(hp) + "/20)";
        // tried once the fetch ends either way; one cut short (a pause, a back-off) is tried again
        Pos tried = chest;
        runRescue(bot.withdraw(chest, foodWanted(inv), r -> { }), () -> rescueTried.add(tried), r -> {
            rescueTried.add(tried);
            log.warn("food fetch failed: {}", r);
        });
        return true;
    }

    /** {@link #run} for the starving rescue: a vitals task (never parked) that also runs while not working. */
    private void runRescue(BotTask t, Runnable onSuccess, Consumer<String> onFailure) {
        runVitals(t, onSuccess, onFailure);
        rescueTask = true;
    }

    /** What stays while the rescue fetches: the safety lock, the pause, BLOCKED; "" while working. */
    private String rescueState() {
        if (safetyLocked) return "The SAFETY STOP stays; only the food fetch runs.";
        if (config.paused) return "The pause stays; only the food fetch runs.";
        if (phase == Phase.BLOCKED) return "BLOCKED stays; only the food fetch runs.";
        return "";
    }

    /** Logs and alerts "Starving: fetching food from the food chest (health N)", once per starving episode. */
    private void announceStarving(float hp, String state) {
        String line = "Starving: fetching food from the food chest (health " + fmt(hp) + ")";
        log.warn("{}{}", line, state.isEmpty() ? "" : ". " + state);
        if (starvingAnnounced) return;
        starvingAnnounced = true;
        alertHere("Starving", line + "." + (state.isEmpty() ? "" : " " + state));
    }

    /** How long the starving rescue waits for an unloaded food chest's chunk before it counts as out of reach (5 s). */
    static final int RESCUE_LOAD_WAIT_TICKS = 100;

    private static String fmt(float health) {
        return health == Math.rint(health) ? String.valueOf((int) health) : String.format(Locale.ROOT, "%.1f", health);
    }

    /**
     * The only foods the engine eats, counts and fetches: {@code build.foodItems} in order, then the foods listed in
     * {@code keepItems}. Never golden apples, enchanted golden apples or other {@link Valuables}, even when listed.
     */
    private List<String> foodIds() {
        Set<String> l = new LinkedHashSet<>();
        if (config.build.foodItems != null) for (String id : config.build.foodItems) if (id != null) l.add(ItemIds.ns(id));
        for (String id : config.build.keepItems) if (id != null && FOODS.contains(ItemIds.ns(id))) l.add(ItemIds.ns(id));
        l.removeIf(Valuables::isValuable);
        return new ArrayList<>(l);
    }

    // ---- tools: never break one ----

    /**
     * Tools never break. Runs between tasks like {@link #tendVitals}, while the bot
     * stands; not in IDLE, SELECT, CLEAR (lever timing), WAIT_CLEAR (soul sand: stay at the centre) or BLOCKED.
     * <ol>
     *   <li>A Mending tool at or below {@code mendBelowPercent} (or the floor) is mended to {@code mendTargetPercent}
     *       with {@link BotActions#mendTool}, a chunk of bottles at a time; bottles come from the XP chests when fewer
     *       are carried than the estimate needs.</li>
     *   <li>A tool at the floor that is not mended now (no Mending, no XP, Mending not working) is swapped at a tool
     *       chest for one above the floor ({@link BotActions#exchangeTool}, the worn one goes back), unless it has
     *       Mending and a spare of its kind is carried (it waits for XP).</li>
     *   <li>Neither works: one alert; the next break that needs that kind goes BLOCKED ({@link #runBreak}).</li>
     * </ol>
     * True if it started a task.
     */
    /** Boots mend between tasks ({@code build.bootsMendBelowPercent}): the episode, its bottles, its start, the retry tick. */
    private boolean bootsMending;
    private int bootsBottles;
    private double bootsMendFrom;
    private long bootsRetryTick;
    /** Bottles one boots mend may throw before it gives up for a while. */
    static final int BOOTS_MEND_MAX_BOTTLES = 128;
    /**
     * Worn boots at or below this durability with no XP to mend them are taken off (they never break). Live 2026-10-14:
     * Soul Speed boots mended to 100 % at 18:47 wore out during an 8.5-hour stock wait (storage sweeps on soul soil every
     * 5 minutes) with no XP bottles carried: the mend only ran with bottles in the inventory, and nothing fetched any.
     */
    static final double BOOTS_OFF_DURABILITY = 0.06;
    /** XP chests tried for the boots this round (each once), and when the next round may start. */
    private int bootsXpFetches;
    private long bootsXpRetryTick;
    /** The boots were taken off worn out: GEAR wears them again only with XP bottles carried to mend them. */
    private boolean bootsRetired;
    /** Bottles after which a mend that has not raised the durability counts as not working (no Mending). */
    static final int BOOTS_MEND_PROBE_BOTTLES = 24;

    /**
     * Worn boots below {@code build.bootsMendBelowPercent}: XP bottles from the inventory, {@link EngineTuning#XP_THROW_CHUNK}
     * at a time, until they are at {@link EngineTuning#BOOTS_MIN_DURABILITY} (live 2026-10-13: Soul Speed boots at 26 %
     * mid-map; the rename's mend comes only at the map's end). No bottles carried, no Mending (no rise after
     * {@link #BOOTS_MEND_PROBE_BOTTLES}) or the cap: one warning, and no new try for 10 minutes. True if it threw.
     */
    private boolean mendBoots(InventoryView inv) {
        int below = config.build.bootsMendBelowPercent;
        if (below <= 0 || !isBoots(inv.bootsItem())) {
            bootsMending = false;
            return false;
        }
        double d = inv.bootsDurability();
        if (!bootsMending && d < below / 100.0 && inv.count(XP_BOTTLE) <= 0) {
            // no bottles carried: fetch some (each XP chest once a round), else take the boots off before they break
            List<Pos> xp = config.site.storage.xpChests;
            if (tick >= bootsXpRetryTick && bootsXpFetches < xp.size()) {
                int n = Math.max(BOOTS_MEND_PROBE_BOTTLES, Math.max(1, config.map.xpBottlesPerTrip));
                Pos chest = xp.get(bootsXpFetches++);
                log.warn("boots at {}% and no XP bottles carried: fetching {} from {}", Math.round(d * 100), n, chest);
                run(bot.withdraw(chest, Map.of(XP_BOTTLE, n), r -> {
                    if (sum(r.moved()) > 0) bootsXpFetches = 0;
                }), null, r -> log.warn("XP for the boots from {} failed: {}", chest, r));
                return true;
            }
            if (bootsXpFetches >= xp.size()) {
                bootsXpFetches = 0;
                bootsXpRetryTick = tick + 20L * 600;
            }
            if (d <= BOOTS_OFF_DURABILITY) {
                bootsRetired = true;
                log.warn("boots at {}% and no XP to mend them: taking them off so they never break", Math.round(d * 100));
                alertOnce("boots-off", "Boots taken off", "The Soul Speed boots are at " + Math.round(d * 100)
                    + "% and no XP bottles could be fetched: taken off so they never break. Put XP bottles in the XP chest.");
                run(bot.unequipBoots(), null, r -> log.warn("taking the boots off failed: {}", r));
                return true;
            }
            return false;
        }
        if (!bootsMending) {
            if (d >= below / 100.0 || tick < bootsRetryTick || inv.count(XP_BOTTLE) <= 0) return false;
            bootsMending = true;
            bootsBottles = 0;
            bootsMendFrom = d;
            log.warn("boots at {}%: mending them to {}% with the carried XP bottles", Math.round(d * 100),
                Math.round(BOOTS_MIN_DURABILITY * 100));
        }
        if (d >= BOOTS_MIN_DURABILITY) {
            log.info("boots mended: {}% -> {}% with {} XP bottles", Math.round(bootsMendFrom * 100), Math.round(d * 100),
                bootsBottles);
            bootsMending = false;
            return false;
        }
        int have = inv.count(XP_BOTTLE);
        String stop = have <= 0 ? "no XP bottles left in the inventory"
            : bootsBottles >= BOOTS_MEND_MAX_BOTTLES ? "thrown " + bootsBottles + " bottles"
            : bootsBottles >= BOOTS_MEND_PROBE_BOTTLES && d <= bootsMendFrom + 0.005
                ? "no rise after " + bootsBottles + " bottles (no Mending?)" : null;
        if (stop != null) {
            log.warn("boots mend stopped at {}% ({}); trying again in 10 min", Math.round(d * 100), stop);
            bootsMending = false;
            bootsRetryTick = tick + 20L * 600;
            return false;
        }
        int n = Math.min(have, XP_THROW_CHUNK);
        bootsBottles += n;
        run(bot.throwXp(n));
        return true;
    }

    // ---- the durability guard: worn armour, the mend watchdog ----

    /**
     * Where the durability guard acts ({@code build.gearGuard}): between two tasks while working (any phase, IDLE
     * too), with a task parked for it, while the working engine sleeps (a wait), and while it does not work (paused,
     * BLOCKED, a back-off). What it may do depends on it ({@link #gearMayThrow}, {@link #gearMayFetch}).
     */
    private enum GearMode { BETWEEN, PARKED, ASLEEP, STOPPED }

    /** A running task is looked at for the gear every this many ticks (1 s). */
    static final int GEAR_CHECK_TICKS = 20;
    /** A task asked to stop for a piece at the hard floor and still running after this long is cut short (30 s). */
    static final int GEAR_FORCE_TICKS = 600;
    /** Bottles one armour mend may throw before it counts as not working for a while. */
    static final int GEAR_MEND_MAX_BOTTLES = 160;
    /** An armour mend that raised nothing after this many bottles: Mending missing or not working. */
    static final int GEAR_PROBE_BOTTLES = 24;
    /** Each XP chest is tried once per round for the armour; a round that brought none waits this long (10 min). */
    static final int GEAR_XP_RETRY_TICKS = 20 * 600;
    /** A piece Mending did not raise (or the bottle cap) is not mended for this long (10 min); at the floor it comes off. */
    static final int GEAR_NO_MEND_TICKS = 20 * 600;
    /** A take-off or wear that failed is tried again after this long (1 min). */
    static final int GEAR_RETRY_TICKS = 20 * 60;
    private static final String AUTO_ARMOR = "AutoArmor";

    /** A mend of the worn armour runs: its bottles, and the worn Mending pieces' uses when it began. */
    private boolean gearMending;
    private int gearBottles;
    private int gearMendFrom;
    /** Item ids Mending did not raise (or the cap stopped), not mended again until the tick. */
    private final Map<String, Long> gearNoMend = new HashMap<>();
    private int gearXpFetches;
    private long gearXpRetryTick;
    private final XpFetchLog gearXpLog = new XpFetchLog();
    private long gearRetryTick;
    /** The running task asked to stop for the gear, and when. */
    private @Nullable BotTask gearParkFor;
    private long gearParkTick;
    /** Pieces the guard took off (armour slot → item id): worn again once they can be mended. */
    private final Map<Integer, String> gearTakenOff = new HashMap<>();
    /** The alerts of this episode per piece (one each until it can be worn again). */
    private final Set<String> gearAlerted = new HashSet<>();
    /** The guard switched ZenithProxy's AutoArmor off: it would put a worn-out piece back on (it ignores durability). */
    private boolean autoArmorHeld;
    /** A take-off found no free slot: the piece (armour slot), else -1. */
    private int gearNeedsRoom = -1;
    /** The guard's own last task (never asked to stop for the gear). */
    private @Nullable BotTask gearTask;
    private final MendWatch mendWatch = new MendWatch();

    /**
     * The durability guard for the worn armour, in every state the bot ticks in ({@code build.gearGuard}). In order:
     * <ol>
     *   <li>A worn piece at the hard floor ({@code build.armorOffPercent}): mended there and then with the carried
     *       bottles when it has Mending that works and throwing is fine now; else taken off into the inventory (one
     *       alert per piece and episode).</li>
     *   <li>A worn Mending piece below its mend threshold (or a mend under way): {@link EngineTuning#XP_THROW_CHUNK}
     *       bottles at a time from the inventory until every worn Mending piece is at 95 %. No bottles: fetched from
     *       the XP chests (each once a round) where walking is fine; the bottles a fetch brings are thrown at once (no
     *       retry wait holds the mend). No rise after {@link #GEAR_PROBE_BOTTLES}, or {@link #GEAR_MEND_MAX_BOTTLES}
     *       thrown: that piece is not mended for {@link #GEAR_NO_MEND_TICKS} (taken off at the floor meanwhile).</li>
     *   <li>A piece the guard took off is worn again once enough bottles are carried to mend it.</li>
     *   <li>The mend watchdog ({@code build.mendWatchdogMinutes}) for the carried Mending tools.</li>
     * </ol>
     * True if it started a task (or blocked).
     */
    private boolean tendGear(GearMode mode) {
        if (!config.build.gearGuard || !ticking || !restored || tick < rescueReadyTick) return false;
        InventoryView inv = bot.inventory();
        if (inv.health() <= 0) return false;
        GearGuard g = GearGuard.of(config.build);
        List<InventoryView.Armor> all = inv.armor();
        List<InventoryView.Armor> worn = GearGuard.worn(all);
        int bottles = inv.count(XP_BOTTLE);
        holdAutoArmor(g, all, bottles);
        watchGear(g, worn, bottles, mode);
        boolean mayThrow = gearMayThrow(mode);
        if (gearNeedsRoom >= 0 && gearMakeRoom(inv, mode)) return true;
        InventoryView.Armor off = GearGuard.mostWorn(worn, g::atOff);
        if (off != null) {
            if (mayThrow && bottles > 0 && gearMendable(off)) return gearMend(g, worn, bottles, mode, "at the hard floor");
            return gearTakeOff(g, off, bottles, mode);
        }
        InventoryView.Armor due = GearGuard.mostWorn(worn, a -> g.wantsMend(a) && gearMendable(a));
        if (due == null && !gearMending) gearToppedUp = false;
        if (due != null && !gearMending && !gearToppedUp && mode != GearMode.PARKED && phase != Phase.NUKE
            && bottles < gearBottlesWanted(inv, worn) && gearMayFetch(mode) && tick >= gearXpRetryTick) {
            // too few carried for the whole mend (live 10-23: the boots ran dry at 83 %): one top-up first, for the
            // armour and the carried Mending tools together (they share the XP). Not with a task parked, nor in the
            // NUKE (the carried bottles first there, as for the tools)
            gearToppedUp = true;
            if (gearFetchXp(inv, worn, due, mode)) return true;
        }
        if (due != null || gearMending) {
            if (bottles > 0) {
                if (mayThrow) return gearMend(g, worn, bottles, mode, due != null ? "below " + g.belowPercent(due) + "%" : "");
            } else if (due != null && gearMayFetch(mode)) {
                if (gearFetchXp(inv, worn, due, mode)) return true;
            } else if (gearMending) {
                // out of bottles where none can be fetched: the mend goes on once bottles are carried (no wait)
                gearMending = false;
                log.info("worn armor mend paused at {}: no XP bottles carried{}", GearGuard.list(damaged(worn)),
                    mode == GearMode.STOPPED ? " (" + stoppedWhy() + ": no walk to the XP chest)" : "");
            }
        } else {
            // a piece the guard took off waits for the bottles to mend it
            InventoryView.Armor waiting = gearWaitingForXp(g, all, bottles);
            if (waiting != null && gearMayFetch(mode) && gearFetchXp(inv, worn, waiting, mode)) return true;
        }
        if (mode != GearMode.STOPPED && mayThrow && gearWearAgain(g, all, bottles, mode)) return true;
        if (toolWatchdog(mode)) return true;
        if (mode == GearMode.PARKED) gearParkFor = null; // done with the parked task: a later need asks it again
        return false;
    }

    /** A top-up fetch ran for this armour mend (one per episode). */
    private boolean gearToppedUp;

    /**
     * Bottles that bring the worn Mending armour to 95 % and the carried Mending tools to {@code mendTargetPercent}
     * (Mending spreads the XP over all of them), at most {@link EngineTuning#MEND_MAX_BOTTLES}.
     */
    private int gearBottlesWanted(InventoryView inv, List<InventoryView.Armor> worn) {
        List<InventoryView.Armor> mendable = new ArrayList<>();
        for (InventoryView.Armor a : worn) if (gearMendable(a)) mendable.add(a);
        long n = GearGuard.bottlesToMend(mendable) + toolBottlesWanted(inv);
        return (int) Math.min(MEND_MAX_BOTTLES, n);
    }

    /** Bottles that bring every carried Mending tool Mending repairs to {@code mendTargetPercent}. */
    private int toolBottlesWanted(InventoryView inv) {
        ToolCare care = ToolCare.of(config.build);
        int n = 0;
        for (InventoryView.Tool t : inv.tools()) {
            if (!t.mending() || care.mended(t) || mendTrouble.getOrDefault(t.itemId(), 0) >= MEND_TROUBLE_LIMIT) continue;
            n += care.bottlesToMend(t, MEND_MAX_BOTTLES);
        }
        return n;
    }

    /** The worn Mending pieces not mended yet. */
    private static List<InventoryView.Armor> damaged(List<InventoryView.Armor> worn) {
        List<InventoryView.Armor> l = new ArrayList<>();
        for (InventoryView.Armor a : worn) if (a.mending() && !GearGuard.mended(a)) l.add(a);
        return l;
    }

    /** Mending works on {@code a} as far as the guard knows (it has Mending, and no recent mend failed to raise it). */
    private boolean gearMendable(InventoryView.Armor a) {
        return a.mending() && tick >= gearNoMend.getOrDefault(a.itemId(), 0L);
    }

    /**
     * Throwing XP bottles where the bot stands is fine now: never in a fight, in a CLEAR (a lever may be held) or in a
     * cart; while stopped not under the safety lock (the kill switch, "do not move") or a setback back-off.
     */
    private boolean gearMayThrow(GearMode mode) {
        if (inCombat || combatWanted) return false;
        if (mode == GearMode.STOPPED) return !safetyLocked && !(backingOff && clock.millis() < backoffUntil);
        return switch (phase) {
            case CLEAR, RIDE_BOARD, RIDE_PRELOAD, RIDE, RIDE_END -> false;
            default -> true;
        };
    }

    /**
     * A walk to the XP chest is fine now: while working, between tasks or with a task parked (or in the waits of IDLE
     * and STOCK_CHECK); never while stopped, in a fight, holding still for health, during a clear or the colour
     * suppression's ride.
     */
    private boolean gearMayFetch(GearMode mode) {
        if (mode == GearMode.STOPPED || inCombat || combatWanted || healthHold) return false;
        if (config.site.storage.xpChests.isEmpty()) return false;
        if (mode == GearMode.ASLEEP && phase != Phase.IDLE && phase != Phase.STOCK_CHECK) return false;
        return switch (phase) {
            case CLEAR, WAIT_CLEAR, SELECT, RIDE_BOARD, RIDE_PRELOAD, RIDE, RIDE_END, RIDE_RESET, FILL_MAP, CHECK_MAP -> false;
            default -> true;
        };
    }

    /** Runs a gear task: a rescue task while stopped (it runs then), else a vitals task (never asked to park). */
    private void gearRun(GearMode mode, BotTask t, @Nullable Runnable ok, Consumer<String> fail) {
        if (mode == GearMode.STOPPED) runRescue(t, ok, fail);
        else runVitals(t, ok, fail);
        gearTask = t;
    }

    /** One chunk of bottles for the worn Mending pieces; false when they are mended or the mend stopped. */
    private boolean gearMend(GearGuard g, List<InventoryView.Armor> worn, int bottles, GearMode mode, String why) {
        List<InventoryView.Armor> mendable = new ArrayList<>();
        for (InventoryView.Armor a : worn) if (gearMendable(a)) mendable.add(a);
        List<InventoryView.Armor> low = damaged(mendable);
        int uses = GearGuard.uses(mendable);
        if (!gearMending) {
            if (low.isEmpty()) return false;
            gearMending = true;
            gearBottles = 0;
            gearMendFrom = uses;
            log.info("worn armor {}{}: mending it to {}% with the carried XP bottles ({} carried, about {} needed){}",
                GearGuard.list(low), why.isEmpty() ? "" : " (" + why + ")", Math.round(GearGuard.TARGET * 100), bottles,
                GearGuard.bottlesToMend(mendable), parkedTask != null ? "; " + parkedTask.name() + " waits" : "");
        }
        if (low.isEmpty()) {
            log.info("worn armor mended with {} XP bottles: {}", gearBottles, GearGuard.list(mendable));
            gearMending = false;
            for (InventoryView.Armor a : mendable) mendWatch.mended("armor:" + a.slot(), tick);
            vitalsNote = "";
            return false;
        }
        String stop = gearBottles >= GEAR_MEND_MAX_BOTTLES ? "thrown " + gearBottles + " bottles"
            : gearBottles >= GEAR_PROBE_BOTTLES && uses <= gearMendFrom
                ? "no rise after " + gearBottles + " bottles (no Mending, or the XP goes elsewhere)" : null;
        if (stop != null) {
            for (InventoryView.Armor a : low) gearNoMend.put(a.itemId(), tick + GEAR_NO_MEND_TICKS);
            log.warn("worn armor mend stopped at {} ({}); not mended for {} min (taken off at the hard floor"
                + " meanwhile)", GearGuard.list(low), stop, GEAR_NO_MEND_TICKS / 1200);
            gearMending = false;
            vitalsNote = "";
            return false;
        }
        int n = Math.min(bottles, XP_THROW_CHUNK);
        gearBottles += n;
        vitalsNote = MENDING_NOTE + ": " + GearGuard.list(low);
        gearRun(mode, bot.throwXp(n), () -> vitalsNote = "", r -> {
            vitalsNote = "";
            log.warn("XP throw for the worn armor failed: {}", r);
        });
        return true;
    }

    /**
     * {@code a} is at the hard floor and is not mended now: it comes off into the inventory. With no free slot, room is
     * made first ({@link #gearMakeRoom}).
     */
    private boolean gearTakeOff(GearGuard g, InventoryView.Armor a, int bottles, GearMode mode) {
        if (tick < gearRetryTick) return false;
        String why = gearWhyNotMended(a, bottles, mode);
        if (bot.inventory().freeSlots() <= 0) {
            gearNeedsRoom = a.slot();
            return gearMakeRoom(bot.inventory(), mode);
        }
        log.warn("{} ({}) is at the hard floor ({} uses or fewer) and {}: taking it off so it never breaks",
            GearGuard.describe(a), GearGuard.slotName(a.slot()), g.offUses(a), why);
        // no bottles but the XP chests are still to be tried: fetched next, and the alert only if none come
        boolean fetchNext = a.mending() && gearMendable(a) && bottles <= 0 && gearMayFetch(mode) && tick >= gearXpRetryTick;
        gearRun(mode, bot.unequipArmor(a.slot()), () -> {
            gearTakenOff.put(a.slot(), a.itemId());
            gearNeedsRoom = -1;
            if (fetchNext) {
                log.info("{} is off; XP bottles are fetched to mend it, then it is worn again", a.name());
                return;
            }
            gearAlert("off:" + a.itemId(), gearOffTitle(a), GearGuard.describe(a) + " (" + GearGuard.slotName(a.slot())
                + ") reached the hard floor and " + why + ": taken off into the inventory so it never breaks. "
                + (a.mending() ? "It is worn again once XP bottles are carried to mend it: put XP bottles in the XP chest."
                    : "Without Mending it is not worn again: replace it."));
        }, r -> {
            gearRetryTick = tick + GEAR_RETRY_TICKS;
            log.warn("taking {} off failed: {}", a.name(), r);
            if (r != null && r.contains(BotActions.NO_FREE_SLOT)) gearNeedsRoom = a.slot();
        });
        return true;
    }

    /** "Boots taken off" for the boots, "Armour taken off" for the rest. */
    private static String gearOffTitle(InventoryView.Armor a) {
        return GearGuard.isBoots(a) ? "Boots taken off" : "Armor taken off";
    }

    /** A Mending piece the guard took off, carried, that waits for the bottles to mend it (most worn first), or null. */
    private InventoryView.Armor gearWaitingForXp(GearGuard g, List<InventoryView.Armor> all, int bottles) {
        InventoryView.Armor w = null;
        for (InventoryView.Armor a : GearGuard.carried(all)) {
            if (!gearTakenOff.containsValue(a.itemId()) || !gearMendable(a) || gearWearable(g, a, bottles)) continue;
            if (w == null || a.fraction() < w.fraction()) w = a;
        }
        return w;
    }

    /** Why {@code a} is not mended now, for the log and the alert. */
    private String gearWhyNotMended(InventoryView.Armor a, int bottles, GearMode mode) {
        if (!a.mending()) return "it has no Mending";
        if (!gearMendable(a)) return "Mending did not raise it (no mend for now)";
        if (bottles <= 0) return "no XP bottles are carried (" + gearXpLog.cause(config.site.storage.xpChests) + ")";
        return "it cannot be mended here now (" + (mode == GearMode.STOPPED ? stoppedWhy() : phase.name())
            + (inCombat || combatWanted ? ", a fight" : "") + ")";
    }

    /**
     * A take-off found the inventory full: while working, the leftover materials go first ({@link #makeRoom}); when
     * nothing can go (or the engine does not work, or may not walk now), BLOCKED (working: it stands still, so the
     * piece wears no further) or one alert (stopped), and the take-off is tried again every minute.
     */
    private boolean gearMakeRoom(InventoryView inv, GearMode mode) {
        if (inv.freeSlots() > 0) {
            gearNeedsRoom = -1;
            return false;
        }
        int slot = gearNeedsRoom;
        InventoryView.Armor a = GearGuard.inSlot(inv.armor(), slot);
        if (a == null) {
            gearNeedsRoom = -1;
            return false;
        }
        String what = GearGuard.describe(a) + " (" + GearGuard.slotName(slot) + ")";
        if (mode == GearMode.PARKED) {
            // room is made between tasks: the parked task is dropped (its step runs again afterwards)
            dropParkedForGear(what + " must come off and the inventory has no free slot");
            return true;
        }
        if ((mode == GearMode.BETWEEN || mode == GearMode.ASLEEP) && gearMayFetch(mode)
            && makeRoom(inv, 1, "taking off the worn-out " + a.name())) {
            return true;
        }
        if (mode == GearMode.STOPPED) {
            if (gearAlerted.add("room:" + a.itemId())) {
                log.warn("{} is at the hard floor, cannot be mended and the inventory has no free slot to take it off;"
                    + " the bot stays {}", what, stoppedWhy());
                alertHere("Armor at the floor", what + " is at the hard floor,"
                    + " cannot be mended (" + gearWhyNotMended(a, inv.count(XP_BOTTLE), mode) + ") and the inventory has"
                    + " no free slot to take it off. The bot stays " + stoppedWhy() + ". Free a slot or put XP bottles in"
                    + " the XP chest.");
            }
            gearRetryTick = tick + GEAR_RETRY_TICKS;
            return false;
        }
        gearNeedsRoom = -1;
        gearAlerted.add("room:" + a.itemId()); // the block's own alert is the one for this piece (no second one)
        block(what + " is at the hard floor, cannot be mended (" + gearWhyNotMended(a, inv.count(XP_BOTTLE), mode)
            + ") and the inventory has no free slot to take it off: stopped where it stands so it never breaks."
            + " Free a slot or put XP bottles in the XP chest");
        return true;
    }

    /**
     * Too few bottles for a due worn piece (or for one the guard took off, {@code due} carried): XP from the next XP
     * chest of this round. A round that brought none waits {@link #GEAR_XP_RETRY_TICKS}; for a piece taken off, that
     * is its alert. True if it started the fetch.
     */
    private boolean gearFetchXp(InventoryView inv, List<InventoryView.Armor> worn, InventoryView.Armor due, GearMode mode) {
        List<Pos> xp = config.site.storage.xpChests;
        if (tick < gearXpRetryTick) return false;
        String what = GearGuard.describe(due) + (due.worn() ? " (" + GearGuard.slotName(due.slot()) + ")" : " (taken off)");
        if (gearXpFetches >= xp.size()) {
            gearXpFetches = 0;
            gearXpRetryTick = tick + GEAR_XP_RETRY_TICKS;
            log.warn("{} is due for mending and no XP bottles came from the XP chests ({}); trying again in {} min", what,
                gearXpLog.cause(xp), GEAR_XP_RETRY_TICKS / 1200);
            if (!due.worn()) {
                gearAlert("off:" + due.itemId(), gearOffTitle(due), what + " was taken off at the hard floor so it never"
                    + " breaks, and no XP bottles came from the XP chests (" + gearXpLog.cause(xp) + "). It stays in the"
                    + " inventory and is worn again once XP bottles are carried to mend it: put XP bottles in the XP"
                    + " chest.");
            }
            return false;
        }
        if (mode == GearMode.PARKED) {
            // no walk with a task parked: it is dropped (its step runs again) and the fetch runs between tasks
            dropParkedForGear(what + " needs XP bottles from the XP chest");
            return true;
        }
        int own = due.worn() ? 0
            : (int) Math.ceil(Math.max(0, due.max() * GearGuard.TARGET - due.remaining()) / GearGuard.USES_PER_BOTTLE);
        // the worn armour, the piece taken off and the carried Mending tools together, less what is carried
        int want = gearBottlesWanted(inv, worn) + own - inv.count(XP_BOTTLE);
        int n = Math.min(MEND_MAX_BOTTLES, Math.max(GEAR_PROBE_BOTTLES,
            Math.max(Math.max(1, config.map.xpBottlesPerTrip), want)));
        if (mode == GearMode.BETWEEN && xpRoom(inv, 0) < n
            && makeRoom(inv, slotsFor(inv, XP_BOTTLE, n), "XP bottles to mend the worn armor")) {
            return true;
        }
        if (gearXpFetches == 0) gearXpLog.clear();
        Pos chest = xp.get(gearXpFetches++);
        log.warn("{} is due for mending and too few XP bottles are carried ({}): fetching {} from XP chest {}{}", what,
            inv.count(XP_BOTTLE), n, chest, parkedTask != null ? "; " + parkedTask.name() + " waits" : "");
        BotTask t = bot.withdraw(chest, Map.of(XP_BOTTLE, n), r -> {
            if (sum(r.moved()) > 0) {
                gearXpFetches = 0;
                gearXpLog.clear();
            } else {
                gearXpLog.none(chest, r);
            }
        });
        Consumer<String> fail = r -> {
            log.warn("XP for the worn armor from {} failed: {}", chest, r);
            gearXpLog.failed(chest, r);
        };
        if (mode == GearMode.PARKED) runVitals(t, null, fail);
        else run(t, null, fail);
        gearTask = t;
        return true;
    }

    /** A piece the guard took off is worn again once it can be mended (enough bottles carried, Mending working). */
    private boolean gearWearAgain(GearGuard g, List<InventoryView.Armor> all, int bottles, GearMode mode) {
        if (gearTakenOff.isEmpty() || tick < gearRetryTick) return false;
        for (var e : new ArrayList<>(gearTakenOff.entrySet())) {
            int slot = e.getKey();
            String id = e.getValue();
            if (GearGuard.inSlot(all, slot) != null || (slot == InventoryView.Armor.FEET && bot.inventory().bootsItem() != null)) {
                gearTakenOff.remove(slot); // something is worn there again (GEAR's boots, a player)
                continue;
            }
            InventoryView.Armor best = null;
            boolean carried = false;
            for (InventoryView.Armor a : GearGuard.carried(all)) {
                if (!a.itemId().equals(id)) continue;
                carried = true;
                if (!gearWearable(g, a, bottles)) continue;
                if (best == null || a.fraction() > best.fraction()) best = a;
            }
            if (!carried) {
                gearTakenOff.remove(slot); // no longer carried (put away)
                continue;
            }
            if (best == null) continue;
            InventoryView.Armor b = best;
            log.info("wearing {} again: {} XP bottles carried to mend it", GearGuard.describe(b), bottles);
            gearRun(mode, bot.equip(id, b.slot()), () -> {
                gearTakenOff.remove(slot);
                gearAlerted.remove("off:" + id);
                gearAlerted.remove("room:" + id);
            }, r -> {
                gearRetryTick = tick + GEAR_RETRY_TICKS;
                log.warn("wearing {} again failed: {}", id, r);
            });
            return true;
        }
        return false;
    }

    /**
     * A carried piece may be worn ({@link GearGuard#wearable}), and a Mending one below its threshold only with the
     * bottles to bring it there (at the average XP per bottle) and Mending working.
     */
    private boolean gearWearable(GearGuard g, InventoryView.Armor a, int bottles) {
        if (!g.wearable(a, bottles)) return false;
        if (!a.mending()) return true;
        boolean low = g.atOff(a) || (long) a.remaining() * 100 < (long) a.max() * g.belowPercent(a);
        if (!low) return true;
        if (!gearMendable(a)) return false;
        // enough to bring it to its threshold (and well clear of the floor) at the average XP per bottle
        double to = Math.max(a.max() * g.belowPercent(a) / 100.0, 2.0 * g.offUses(a));
        int need = (int) Math.ceil(Math.max(0, to - a.remaining()) / GearGuard.USES_PER_BOTTLE);
        return bottles >= Math.max(1, need);
    }

    /** The alert of one piece's episode, once (the episode ends when the piece is worn again). */
    private void gearAlert(String key, String title, String body) {
        if (!gearAlerted.add(key)) return;
        log.warn("{}: {}", title, body);
        alertHere(title, body);
    }

    /**
     * ZenithProxy's AutoArmor (when the owner has it on) wears the best armour material with no look at durability: a
     * piece taken off worn out would go straight back on while the modules are back (IDLE, BLOCKED, paused, a fight).
     * While a carried piece cannot be worn ({@link #gearWearable}), AutoArmor stays off; it comes back after.
     */
    private void holdAutoArmor(GearGuard g, List<InventoryView.Armor> all, int bottles) {
        boolean unwearable = false;
        for (InventoryView.Armor a : GearGuard.carried(all)) if (!gearWearable(g, a, bottles)) unwearable = true;
        if (unwearable && !autoArmorHeld) {
            try {
                if (!modules.isEnabled(AUTO_ARMOR)) return;
                modules.setEnabled(AUTO_ARMOR, false);
                autoArmorHeld = true;
                log.info("AutoArmor off while a worn-out armor piece is carried (it would put it back on)");
            } catch (RuntimeException e) {
                log.warn("could not disable module {}: {}", AUTO_ARMOR, e.toString());
            }
        } else if (!unwearable && autoArmorHeld) {
            releaseAutoArmor();
        }
    }

    /** AutoArmor back as the owner had it (also when bot ticks stop and on shutdown). */
    private void releaseAutoArmor() {
        if (!autoArmorHeld) return;
        autoArmorHeld = false;
        if (suppressing && config.build.disableModules.stream().anyMatch(AUTO_ARMOR::equalsIgnoreCase)) {
            suppressed.add(AUTO_ARMOR); // off while working anyway: back with the other modules
            return;
        }
        try {
            modules.setEnabled(AUTO_ARMOR, true);
            log.info("AutoArmor back on");
        } catch (RuntimeException e) {
            log.warn("could not re-enable module {}: {}", AUTO_ARMOR, e.toString());
        }
    }

    /**
     * The mend watchdog's clocks for the worn pieces ({@code build.mendWatchdogMinutes}). A piece due for that long
     * without a successful mend is reported once with what held it, its "no mend" wait is lifted and it is mended at
     * the next safe point (the running task is asked to stop for it). True while one is overdue.
     */
    private boolean watchGear(GearGuard g, List<InventoryView.Armor> worn, int bottles, GearMode mode) {
        long limit = config.build.mendWatchdogMinutes * 1200L;
        Set<String> present = new HashSet<>();
        boolean over = false;
        for (InventoryView.Armor a : worn) {
            if (!a.mending()) continue;
            String key = "armor:" + a.slot();
            present.add(key);
            if (!g.wantsMend(a) && !(gearMending && !GearGuard.mended(a))) {
                mendWatch.fine(key);
                continue;
            }
            mendWatch.due(key, tick);
            if (!mendWatch.overdue(key, tick, limit)) continue;
            over = true;
            if (mendWatch.report(key)) {
                log.warn("mend watchdog: {} ({}) has been due for mending for {} min without a successful mend ({});"
                    + " mending it at the next safe point", GearGuard.describe(a), GearGuard.slotName(a.slot()),
                    mendWatch.dueFor(key, tick) / 1200, gearHolds(a, bottles, mode));
                gearNoMend.remove(a.itemId());
                mendWatch.mended(key, tick); // the next report comes one more watchdog period on, if still unmended
            }
        }
        mendWatch.retain("armor:", present);
        return over;
    }

    /** What held the mend of {@code a}, for the watchdog's line. */
    private String gearHolds(InventoryView.Armor a, int bottles, GearMode mode) {
        List<String> l = new ArrayList<>();
        if (bottles <= 0) l.add("no XP bottles carried");
        if (bottles <= 0 && tick < gearXpRetryTick)
            l.add("XP fetch waits " + (gearXpRetryTick - tick) / 20 + " s (" + gearXpLog.cause(config.site.storage.xpChests) + ")");
        if (tick < gearNoMend.getOrDefault(a.itemId(), 0L)) l.add("its last mend raised nothing");
        if (!gearMayThrow(mode)) l.add("no XP throws in " + (mode == GearMode.STOPPED ? stoppedWhy() : phase.name()));
        if (task != null) l.add(task.name() + " running");
        return l.isEmpty() ? "nothing held it: it wore faster than it was mended" : String.join("; ", l);
    }

    /**
     * Every tick while working, whatever task runs (like {@link #checkVitals}): a worn piece at the hard floor, one
     * below {@code build.armorParkPercent} with bottles carried (or fetchable), or one the watchdog says is overdue asks
     * the running task to stop at its next safe point; the guard acts there ({@link #tendGear}). A task that does not
     * stop for a piece at the floor within {@link #GEAR_FORCE_TICKS} is cut short (never a fight, a fall rescue, an
     * escape or the vitals' and rescue's own tasks).
     */
    private void checkGear() {
        if (!config.build.gearGuard || task == null || vitalsTask || rescueTask || task == gearTask || gearNeedsRoom >= 0
            || task == fallTask || escaping) {
            return; // a fall rescue and a setback escape finish first: the guard acts after them
        }
        if (gearParkFor != null && gearParkFor != task) gearParkFor = null;
        if (tick % GEAR_CHECK_TICKS != 0) return;
        InventoryView inv = bot.inventory();
        if (inv.health() <= 0) return;
        GearGuard g = GearGuard.of(config.build);
        List<InventoryView.Armor> worn = GearGuard.worn(inv.armor());
        int bottles = inv.count(XP_BOTTLE);
        boolean forced = watchGear(g, worn, bottles, GearMode.BETWEEN);
        InventoryView.Armor off = GearGuard.mostWorn(worn, g::atOff);
        boolean fetchable = gearMayFetch(GearMode.BETWEEN) && tick >= gearXpRetryTick;
        InventoryView.Armor want = off != null ? off : GearGuard.mostWorn(worn, a -> gearMendable(a)
            && (g.urgent(a) || (forced && g.wantsMend(a))) && (bottles > 0 || fetchable));
        if (want == null) return;
        if (gearParkFor == task) {
            if (off != null && tick - gearParkTick >= GEAR_FORCE_TICKS && gearMayCut()) dropTaskForGear(off);
            return;
        }
        gearParkFor = task;
        gearParkTick = tick;
        if (askedToPark == task) return; // already stopping (vitals, a restart): the guard acts at that safe point
        askedToPark = task;
        parkAccepted = task.requestPark();
        log.warn("{} ({}) {}: {}", GearGuard.describe(want), GearGuard.slotName(want.slot()),
            off != null ? "is at the hard floor" : "is due for mending", parkAccepted
                ? "stopping " + task.name() + " at its next safe point for it"
                : "finishing " + task.name() + " first (it cannot stop midway)"
                    + (off != null ? "; cut short after " + GEAR_FORCE_TICKS / 20 + " s" : ""));
    }

    /**
     * The parked task is dropped for the gear: what it needs (room to take a piece off, XP bottles from the XP chest) is
     * done between tasks, never with a task parked. {@code why} is the log line's start.
     */
    private void dropParkedForGear(String why) {
        BotTask t = parkedTask;
        log.warn("{}: {} is dropped (its step runs again) and that is done between tasks", why, t.name());
        giveBackAttempt(parkedAttempt, parkedAttemptCtx, t);
        t.cancel();
        parkedTask = null;
        parkedOk = null;
        parkedFail = null;
        parkedAttempt = null;
        parkedAttemptCtx = null;
        gearParkFor = null;
        acted = true;
    }

    /** The running task may be cut short for a piece at the hard floor. */
    private boolean gearMayCut() {
        return !inCombat && !combatWanted && !escaping && !stoppedDefence && task != restartTask && task != fallTask;
    }

    /** Cuts the running task short (its step runs again afterwards); the guard takes the piece off or mends it. */
    private void dropTaskForGear(InventoryView.Armor a) {
        BotTask t = task;
        log.warn("{} is at the hard floor and {} did not stop for it within {} s: cut short so the {} never breaks",
            GearGuard.describe(a), t.name(), (tick - gearParkTick) / 20, a.name());
        giveBackAttempt(taskAttempt, taskAttemptCtx, t);
        t.cancel();
        task = null;
        taskOk = null;
        taskFail = null;
        taskAttempt = null;
        taskAttemptCtx = null;
        askedToPark = null;
        parkAccepted = false;
        gearParkFor = null;
        wakeTick = Math.min(wakeTick, tick);
    }

    /** Tools the mend watchdog took on: mended on at every safe point until they read mended, or a mend gains nothing. */
    private final Set<String> watchdogTools = new HashSet<>();

    /**
     * The mend watchdog for the carried Mending tools ({@code build.mendWatchdogMinutes}): a tool due for mending
     * ({@link ToolCare#wantsMend}) for that long without a successful mend is reported once with what held it (the
     * "Mending does not repair" count, a retry wait, a mend left for the clear, no bottles, the phase); its trouble count
     * and the tool-care waits are lifted, and it is mended on at every safe point, whatever the phase, until it reads
     * mended: between tasks and in the waits of IDLE and STOCK_CHECK by {@link #mendTool} (it fetches bottles),
     * elsewhere (stopped, a parked task) with the carried bottles only. A mend that gains nothing (not taken by the worn
     * armour) ends it; the next report comes one watchdog period on. True if it started a task.
     */
    private boolean toolWatchdog(GearMode mode) {
        long limit = config.build.mendWatchdogMinutes * 1200L;
        if (limit <= 0) {
            watchdogTools.clear();
            return false;
        }
        InventoryView inv = bot.inventory();
        List<InventoryView.Tool> tools = inv.tools();
        ToolCare care = ToolCare.of(config.build);
        Set<String> present = new HashSet<>();
        InventoryView.Tool over = null;
        for (InventoryView.Tool t : tools) {
            if (!t.mending()) continue;
            String key = "tool:" + t.itemId();
            present.add(key);
            if (care.mended(t)) watchdogTools.remove(t.itemId());
            boolean taken = watchdogTools.contains(t.itemId());
            if (!care.wantsMend(t) && !taken) {
                if (care.mended(t) || !mendingIds.contains(t.itemId())) mendWatch.fine(key);
                continue;
            }
            mendWatch.due(key, tick);
            if ((taken || mendWatch.overdue(key, tick, limit)) && (over == null || t.fraction() < over.fraction())) over = t;
        }
        mendWatch.retain("tool:", present);
        watchdogTools.removeIf(id -> !present.contains("tool:" + id));
        if (over == null) return false;
        String id = over.itemId();
        String key = "tool:" + id;
        int have = inv.count(XP_BOTTLE);
        boolean walk = (mode == GearMode.BETWEEN || mode == GearMode.ASLEEP) && gearMayFetch(mode);
        boolean now = gearMayThrow(mode) && (have > 0 || walk);
        if (watchdogTools.add(id)) {
            log.warn("mend watchdog: {} has been due for mending for {} min without a successful mend ({}); mending it {}",
                ToolCare.describe(over), mendWatch.dueFor(key, tick) / 1200, toolHolds(over, care, inv, mode),
                now ? "now" : "at the next safe point with bottles");
            mendWatch.mended(key, tick); // the next report one watchdog period on, if it is still unmended then
            mendTrouble.remove(id);
            toolRetryTick = 0;
            xpRetryTick = 0;
            xpFetches = 0;
        }
        if (!now) return false;
        if (walk) return mendTool(inv, tools, over, care);
        int n = Math.min(have, Math.min(care.bottlesToMend(over, MEND_MAX_BOTTLES), MEND_CHUNK));
        if (n <= 0) return false;
        int before = ToolCare.mendingUses(tools, id);
        int armorBefore = GearGuard.uses(GearGuard.worn(inv.armor()));
        gearRun(mode, bot.mendTool(id, n, care.targetFraction()), () -> {
            vitalsNote = "";
            afterMend(id, before, armorBefore);
        }, r -> {
            vitalsNote = "";
            watchdogTools.remove(id);
            log.warn("mending {} failed: {}", id, r);
        });
        vitalsNote = MENDING_NOTE + ": " + ToolCare.describe(over);
        return true;
    }

    /** What held the mend of {@code t}, for the watchdog's line. */
    private String toolHolds(InventoryView.Tool t, ToolCare care, InventoryView inv, GearMode mode) {
        List<String> l = new ArrayList<>();
        int trouble = mendTrouble.getOrDefault(t.itemId(), 0);
        if (trouble >= MEND_TROUBLE_LIMIT) l.add("marked \"Mending does not repair\" after " + trouble + " mends that gained nothing");
        if (tick < toolRetryTick) l.add("tool-care wait " + (toolRetryTick - tick) / 20 + " s");
        int have = inv.count(XP_BOTTLE);
        if (have <= 0) l.add("no XP bottles carried (" + mendXpFetch.cause(config.site.storage.xpChests) + ")");
        if (have <= 0 && tick < xpRetryTick) l.add("XP fetch waits " + (xpRetryTick - tick) / 20 + " s");
        if (ctx.mendDeferred.contains(t.itemId())) l.add("left for the clear");
        if (mode == GearMode.STOPPED) l.add(stoppedWhy());
        else if (phase == Phase.IDLE || phase == Phase.SELECT || phase == Phase.CLEAR || phase == Phase.BLOCKED)
            l.add("no tool mends in " + phase);
        if (!suppressing && mode != GearMode.STOPPED) l.add("not working (modules back)");
        if (parkedTask != null) l.add(parkedTask.name() + " parked");
        return l.isEmpty() ? "nothing held it: it wore faster than it was mended" : String.join("; ", l);
    }

    /** Forgets the guard's waits (resume, a new connection): a piece is looked at afresh. */
    private void resetGear() {
        gearMending = false;
        gearNoMend.clear();
        gearXpFetches = 0;
        gearXpRetryTick = 0;
        gearXpLog.clear();
        gearRetryTick = 0;
        gearParkFor = null;
        gearAlerted.clear();
        gearNeedsRoom = -1;
        mendWatch.clear();
        watchdogTools.clear();
    }

    /** Uses left over the worn Mending armour (what a tool mend's XP may have gone to instead). */
    private int wornArmorUses() {
        return config.build.gearGuard ? GearGuard.uses(GearGuard.worn(bot.inventory().armor())) : 0;
    }

    private boolean careForTools() {
        if (tendGear(GearMode.BETWEEN)) return true;
        if (!suppressing || tick < toolRetryTick) return false;
        if (phase == Phase.WAIT_CLEAR) return mendDuringClear();
        if (phase == Phase.IDLE || phase == Phase.SELECT || phase == Phase.CLEAR || phase == Phase.WAIT_CLEAR
            || phase == Phase.BLOCKED) {
            return false;
        }
        InventoryView inv = bot.inventory();
        if (!config.build.gearGuard && mendBoots(inv)) return true;
        List<InventoryView.Tool> tools = inv.tools();
        if (tools.isEmpty()) return false;
        ToolCare care = ToolCare.of(config.build);
        toolsOut.removeIf(k -> tools.stream().anyMatch(t -> k.equals(t.kind()) && !care.atFloor(t)));
        mendingIds.removeIf(id -> tools.stream().noneMatch(t -> t.itemId().equals(id) && t.mending() && !care.mended(t)));
        if (nukeMending() && inv.count(XP_BOTTLE) <= 0) {
            // in the NUKE a mend goes on only with the carried bottles: no walk to the XP chest for a tool above its
            // threshold; at or below it, it is fetched for
            mendingIds.removeIf(id -> tools.stream().noneMatch(t -> t.itemId().equals(id) && care.wantsMend(t)));
        }
        // mending during the clear: in the NUKE a due tool that finishes it well above the floor is mended in WAIT_CLEAR
        // (also one whose mend began at a lane end); the floor stop stays the backstop
        boolean waitInNuke = nukeMending() && config.build.mendDuringClear;
        InventoryView.Tool m = ToolCare.mostWorn(tools, t -> t.mending()
            && mendTrouble.getOrDefault(t.itemId(), 0) < MEND_TROUBLE_LIMIT
            && (care.wantsMend(t) || (mendingIds.contains(t.itemId()) && !care.mended(t)))
            && !(waitInNuke && mendWaitsForClear(ctx, t, care, inv)));
        if (m != null && mendTool(inv, tools, m, care)) return true;
        List<InventoryView.Tool> worn = new ArrayList<>(tools);
        worn.removeIf(t -> !care.atFloor(t));
        worn.sort(Comparator.comparingDouble(InventoryView.Tool::fraction));
        for (InventoryView.Tool t : worn) {
            String kind = t.kind();
            boolean mendable = t.mending() && mendTrouble.getOrDefault(t.itemId(), 0) < MEND_TROUBLE_LIMIT;
            if (mendable && care.hasSpare(tools, t)) continue; // waits for XP; the spare breaks meanwhile
            // a worn tool that is not a tool-chest tool is the owner's: it stays in the inventory, the spare breaks
            if (!toolChestTool(t.itemId()) && care.hasSpare(tools, t)) continue;
            if (toolsOut.contains(kind) || tick < swapRetryTick.getOrDefault(kind, 0L)) continue;
            if (swapTool(t, care)) return true;
        }
        return false;
    }

    /**
     * WAIT_CLEAR: the bot waits at the centre while the flying
     * machines sweep and the carts collect, and the presence rule keeps it there. With {@code build.mendDuringClear} it
     * mends its carried Mending tools up to the target there with the bottles it carries, standing still: no fetch, no
     * swap, no walk. {@link EngineTuning#MEND_CHUNK} bottles at a time while the clear runs,
     * {@link EngineTuning#CLEAR_RUNON_MEND_CHUNK} during the carts' run-on, and the clear is polled between two mends, so
     * its end and the run-on's are seen on time. True if it started a mend.
     */
    private boolean mendDuringClear() {
        if (!config.build.mendDuringClear || clearMendAwaitsPoll || platform == null) return false;
        if (!atClearStand()) return false;
        InventoryView inv = bot.inventory();
        int have = inv.count(XP_BOTTLE);
        if (have <= 0) return false;
        List<InventoryView.Tool> tools = inv.tools();
        ToolCare care = ToolCare.of(config.build);
        InventoryView.Tool t = ToolCare.mostWorn(tools, x -> x.mending() && !care.mended(x)
            && mendTrouble.getOrDefault(x.itemId(), 0) < MEND_TROUBLE_LIMIT);
        if (t == null) return false;
        int want = care.bottlesToMend(t, MEND_MAX_BOTTLES);
        int n = Math.min(have, Math.min(want, ctx.collecting != null ? CLEAR_RUNON_MEND_CHUNK : MEND_CHUNK));
        if (n <= 0) return false;
        String id = t.itemId();
        int before = ToolCare.mendingUses(tools, id);
        int armorBefore = wornArmorUses();
        clearMendAwaitsPoll = true;
        log.info("mending {} during the clear with {} XP bottles (about {} to reach {} %)", ToolCare.describe(t), n,
            want, care.targetPercent);
        run(bot.mendTool(id, n, care.targetFraction()), () -> {
            vitalsNote = "";
            afterMend(id, before, armorBefore);
        }, r -> {
            vitalsNote = "";
            toolRetryTick = tick + MEND_RETRY_TICKS;
            log.warn("mending {} during the clear failed: {}", id, r);
            mendTroubled(id);
        });
        vitalsNote = MENDING_NOTE + ": " + ToolCare.describe(t);
        return true;
    }

    /** Mends {@code t}, or fetches bottles for it. False when there is no XP to do it with. */
    private boolean mendTool(InventoryView inv, List<InventoryView.Tool> tools, InventoryView.Tool t, ToolCare care) {
        int want = care.bottlesToMend(t, MEND_MAX_BOTTLES);
        if (want <= 0) return false;
        int have = inv.count(XP_BOTTLE);
        List<Pos> chests = config.site.storage.xpChests;
        // in the NUKE the carried bottles come first, where the bot stands (a lane end); the XP chest only without any
        boolean carriedFirst = nukeMending() && have > 0;
        if (have < want && !carriedFirst && tick >= xpRetryTick) {
            if (xpFetches < chests.size()) {
                // with the durability guard, also the worn Mending armour's share (Mending spreads the XP over both)
                int n = Math.min(MEND_MAX_BOTTLES, Math.max(Math.max(1, config.map.xpBottlesPerTrip), want - have
                    + (config.build.gearGuard ? GearGuard.bottlesToMend(GearGuard.worn(inv.armor())) : 0)));
                if ((xpRoom(inv, have) < n || phase == Phase.NUKE)
                    && makeRoom(inv, slotsFor(inv, XP_BOTTLE, n), "XP bottles to mend " + ToolCare.describe(t))) {
                    return true;
                }
                if (xpFetches == 0) mendXpFetch.clear();
                Pos chest = chests.get(xpFetches++);
                log.info("fetching {} XP bottles to mend {}", n, ToolCare.describe(t));
                run(bot.withdraw(chest, Map.of(XP_BOTTLE, n), r -> {
                    if (sum(r.moved()) > 0) {
                        xpFetches = 0;
                        xpFullRetried = false;
                        mendXpFetch.clear();
                    } else if (!xpFullRetried && r.problem() != null && r.problem().contains("inventory full")) {
                        // drops picked up on the way filled the room it had: once more, after making room (local
                        // 10x run: one free slot at the lane end, none at the XP chest)
                        xpFullRetried = true;
                        xpFetches = Math.max(0, xpFetches - 1);
                        log.info("XP chest {}: the inventory filled up on the way (picked-up drops): making room, then"
                            + " once more", chest);
                    } else {
                        mendXpFetch.none(chest, r);
                    }
                }), null, r -> {
                    log.warn("XP withdraw failed: {}", r);
                    mendXpFetch.failed(chest, r);
                });
                return true;
            }
            xpFetches = 0;
            xpRetryTick = tick + TOOL_RETRY_TICKS;
        }
        if (have <= 0) {
            mendingIds.remove(t.itemId());
            warnOnce("mend-no-xp-" + t.itemId(), "no XP bottles to mend " + ToolCare.describe(t)
                + ": " + mendXpFetch.cause(chests) + "; it is used down to " + care.floor + " uses, then swapped");
            return false;
        }
        int n = Math.min(have, Math.min(want, MEND_CHUNK));
        String id = t.itemId();
        int before = ToolCare.mendingUses(tools, id);
        int armorBefore = wornArmorUses();
        mendingIds.add(id);
        mendsStarted++;
        log.info("mending {} with {} XP bottles (about {} to reach {} %)", ToolCare.describe(t), n, want,
            care.targetPercent);
        run(bot.mendTool(id, n, care.targetFraction()), () -> {
            vitalsNote = "";
            afterMend(id, before, armorBefore);
        }, r -> {
            vitalsNote = "";
            toolRetryTick = tick + MEND_RETRY_TICKS;
            log.warn("mending {} failed: {}", id, r);
            mendTroubled(id);
        });
        vitalsNote = MENDING_NOTE + ": " + ToolCare.describe(t);
        return true;
    }

    /** XP bottles the inventory can take: its empty slots, and the room left in a partial bottle stack. */
    private int xpRoom(InventoryView inv, int have) {
        int max = Math.max(1, catalog.maxStackSize(XP_BOTTLE));
        return inv.freeSlots() * max + (have % max == 0 ? 0 : max - have % max);
    }

    /** In the NUKE with {@code build.nukeMend}: carried bottles first, mends at lane ends (docs "Mending in the nuke"). */
    private boolean nukeMending() {
        return phase == Phase.NUKE && config.build.nukeMend;
    }

    /** Empty slots {@code n} more of {@code id} need beyond the room left in a partial stack of it. */
    private int slotsFor(InventoryView inv, String id, int n) {
        int max = Math.max(1, catalog.maxStackSize(id));
        int have = inv.count(id);
        int partial = have % max == 0 ? 0 : max - have % max;
        return (Math.max(0, n - partial) + max - 1) / max;
    }

    /**
     * Fewer than {@code slots} empty slots for {@code forWhat} (XP bottles to mend, for the nuke, a tool from the tool
     * chest; live gate C, NUKE: the XP chest "gave no bottles ... (inventory full)"): first gets rid of the leftover
     * materials, as RESTOCK does ({@link Leftovers}: materials only, above what the tile still needs), into a return
     * chest or at a drop point, all of them in one go. In the NUKE, when those free too few slots, every material goes
     * (the nuke's pickups; what the tile needs is fetched again at RESTOCK). Items that are no materials (the owner's, tools,
     * food, XP, maps) never go. Each return chest and drop point is tried once per phase, and again after one that took
     * something (pickups fill the inventory again during a long NUKE). True while that task runs; false when there is
     * enough room, nothing to get rid of, or every place was tried.
     */
    private boolean makeRoom(InventoryView inv, int slots, String forWhat) {
        // in the NUKE the walk off the tile picks up more drops: room for those too
        int lacking = slots + (phase == Phase.NUKE ? NUKE_PICKUP_SLOTS : 0) - inv.freeSlots();
        if (lacking <= 0) return false;
        Ctx c = ctx;
        Map<String, Integer> needs = tileNeeds(c);
        Leftovers lo = leftovers(c);
        Map<String, Integer> junk = lo.junk(inv.contents(), needs, catalog::maxStackSize);
        Map<String, Integer> keep = needs;
        String also = "";
        if (phase == Phase.NUKE && !needs.isEmpty() && slotsFreed(inv, junk) < lacking) {
            Map<String, Integer> all = lo.junk(inv.contents(), Map.of(), catalog::maxStackSize);
            if (slotsFreed(inv, all) > slotsFreed(inv, junk)) {
                junk = all;
                keep = Map.of();
                also = " (the tile's own materials too: fetched again at RESTOCK)";
            }
        }
        if (junk.isEmpty()) return false;
        log.info("no room for {}: getting rid of leftover materials first{}", forWhat, also);
        String key = "room-";
        return returnLeftovers(c, junk, lo::material, keep, key, () -> {
            c.resetAttempt(key + "deposit");
            c.resetAttempt(key + "drop");
        });
    }

    /** Slots that going {@code junk} (id → count, {@link Leftovers#junk}) frees: whole stacks, or every slot of an id. */
    private int slotsFreed(InventoryView inv, Map<String, Integer> junk) {
        Map<String, Integer> used = inv.slotsUsed();
        Map<String, Integer> have = inv.contents();
        int n = 0;
        for (var e : junk.entrySet()) {
            int max = Math.max(1, catalog.maxStackSize(e.getKey()));
            n += e.getValue() >= have.getOrDefault(e.getKey(), 0) ? used.getOrDefault(e.getKey(), 0) : e.getValue() / max;
        }
        return n;
    }

    /**
     * What the current tile still needs, for leftovers outside RESTOCK: RESTOCK's own needs; nothing once the tile's map
     * is stored (or without a tile); else the remaining needs from the world, or the last observed ones while its chunks
     * are not loaded, or before that the whole tile's (more is kept, never less).
     */
    private Map<String, Integer> tileNeeds(Ctx c) {
        if (c.needs != null) return c.needs;
        if (tile == null || plan == null || tile.status == TileStatus.STORED) return Map.of();
        WorldView w = bot.world();
        if (plan.allLoaded(w)) return plan.remainingNeeds(w);
        return needsCache != null ? needsCache : plan.allNeeds();
    }

    /**
     * What the XP fetches of one round (each XP chest tried once) found when no bottle came, for the message: a chest
     * seen without bottles, a withdraw that failed and why, or one that moved none for another reason (inventory full).
     * Gate B showed "the XP chests are empty" while the withdraw had failed ("input not held still") at a full chest.
     */
    static final class XpFetchLog {
        private final List<String> problems = new ArrayList<>();
        private int empty;

        void clear() {
            problems.clear();
            empty = 0;
        }

        /** The withdraw task failed with {@code reason}. */
        void failed(Pos chest, String reason) {
            problems.add("withdraw from XP chest " + chest.key() + " failed (" + reason + ")");
        }

        /** The withdraw ran but moved no bottle. */
        void none(Pos chest, TransferResult r) {
            boolean seenEmpty = r.after() != null && r.after().items().getOrDefault(XP_BOTTLE, 0) <= 0;
            if (seenEmpty) {
                empty++;
                problems.add("XP chest " + chest.key() + " is empty");
            } else {
                problems.add("XP chest " + chest.key() + " gave no bottles"
                    + (r.problem() != null ? " (" + r.problem() + ")" : "")
                    + (r.after() != null ? " although it holds " + r.after().items().getOrDefault(XP_BOTTLE, 0) : ""));
            }
        }

        /** Why there are no bottles: every chest seen empty, else what happened at each one. */
        String cause(List<Pos> chests) {
            if (chests.isEmpty()) return "no XP chest is configured (site.storage.xpChests)";
            if (problems.isEmpty()) return "none carried and no XP chest tried yet";
            if (empty == problems.size()) return problems.size() == 1 ? "the XP chest is empty" : "the XP chests are empty";
            return String.join("; ", problems);
        }
    }

    /**
     * After a tool mend: a gain clears its trouble count; none counts towards "Mending does not repair", unless the worn
     * Mending armour gained meanwhile ({@code armorBefore}, the worn pieces' uses before it; -1 unknown): Mending gave
     * the XP to a damaged piece, which is no fault of the tool's Mending (live 2026-10-17, 10-18: one-bottle mends that
     * "gained nothing" twice in a row). The tool stays due and is mended on.
     */
    private void afterMend(String id, int before, int armorBefore) {
        int after = ToolCare.mendingUses(bot.inventory().tools(), id);
        if (after > before) {
            mendTrouble.remove(id);
            toolStops = 0;
            mendWatch.mended("tool:" + id, tick);
            log.info("mended {}: {} -> {} uses", id, before, after);
            return;
        }
        int armorAfter = wornArmorUses();
        if (armorBefore >= 0 && armorAfter > armorBefore) {
            log.info("mending {} gained nothing ({} -> {} uses): the worn armor took the XP ({} -> {} uses); not"
                + " counted, the mend goes on", id, before, after, armorBefore, armorAfter);
            return;
        }
        log.warn("mending {} gained nothing ({} -> {} uses)", id, before, after);
        watchdogTools.remove(id); // the watchdog's mend ends here; it reports again one period on
        mendTroubled(id);
    }

    private void mendTroubled(String id) {
        int n = mendTrouble.merge(id, 1, Integer::sum);
        if (n >= MEND_TROUBLE_LIMIT) {
            alertOnce("mend-broken-" + id, "Mending does not repair " + id, n + " XP throws in a row did not repair "
                + id + ". It is used down to " + ToolCare.of(config.build).floor + " uses and then swapped at the"
                + " tool chest. Check its Mending, and damaged Mending boots (they share the XP).");
        }
    }

    /** Swaps the worn {@code t} at the next tool chest. False when every tool chest was tried (then alerts once). */
    private boolean swapTool(InventoryView.Tool t, ToolCare care) {
        String kind = t.kind();
        List<Pos> chests = config.site.storage.toolChests;
        int a = toolFetches.getOrDefault(kind, 0);
        if (a < chests.size()) {
            if (makeRoom(bot.inventory(), 1, "a " + kind + " from the tool chest")) return true;
            toolFetches.put(kind, a + 1);
            Pos chest = chests.get(a);
            log.info("{} is at the safety floor ({} uses){}: swapping it at tool chest {}", ToolCare.describe(t),
                care.floor, t.mending() ? " and cannot be mended now" : "", chest);
            run(bot.exchangeTool(chest, kind, care.floor, this::toolChestTool, r -> afterSwap(kind, chest, r, care)), null,
                r -> log.warn("tool swap at {} failed: {}", chest, r));
            return true;
        }
        toolFetches.remove(kind);
        swapRetryTick.put(kind, tick + TOOL_RETRY_TICKS);
        if (care.hasSpare(bot.inventory().tools(), t)) return false;
        toolsOut.add(kind);
        alertOnce("tool-out-" + kind, "Tool at the safety floor", worn(t) + ". Tools never break, so no more"
            + " blocks that need a " + kind + " are broken; the engine stops when one is next needed. Put XP bottles in"
            + " the XP chest or a " + kind + " in the tool chest, then `" + config.discord.commandPrefix + " resume`"
            + " if it stopped.");
        return false;
    }

    private void afterSwap(String kind, Pos chest, TransferResult r, ToolCare care) {
        if (r.problem() != null) log.info("tool chest {}: {}", chest, r.problem());
        if (sum(r.moved()) > 0) {
            toolFetches.remove(kind);
            toolStops = 0;
            log.info("took {} from tool chest {}", r.moved(), chest);
            toolChestIds.addAll(r.moved().keySet());
        }
        List<InventoryView.Tool> now = bot.inventory().tools();
        boolean carried = now.stream().anyMatch(o -> kind.equals(o.kind()));
        if (carried && now.stream().noneMatch(o -> kind.equals(o.kind()) && o.mending())) {
            alertOnce("no-mending-" + kind, "No Mending " + kind, "No " + kind + " with Mending is carried. Tools"
                + " without Mending cannot be repaired: each is used down to " + care.floor + " uses and then swapped"
                + " at the tool chest. Put a Mending " + kind + " in the tool chest.");
        }
    }

    /** "diamond_pickaxe at 12/1561, no XP bottles, no spare pickaxe above 20 uses in the tool chests". */
    private String worn(InventoryView.Tool t) {
        String why = !t.mending() ? "" : mendTrouble.getOrDefault(t.itemId(), 0) >= MEND_TROUBLE_LIMIT
            ? ", Mending does not repair it"
            : ", no XP bottles (" + mendXpFetch.cause(config.site.storage.xpChests) + ")";
        return ToolCare.describe(t) + why + ", no spare " + t.kind() + " above " + ToolCare.of(config.build).floor
            + " uses in the tool chests";
    }

    /**
     * Runs a break task. A stop at the tool safety floor ({@link BotActions#TOOL_AT_FLOOR}) is not a failed round:
     * {@code notCounted} takes the round back and {@link #careForTools} mends or swaps the tool before the step runs
     * again. When that was impossible for the kind the task needed, the engine goes BLOCKED instead: no block is ever
     * broken with a worn tool.
     */
    private void runBreak(List<Pos> targets, Runnable notCounted) {
        runBreak(targets, notCounted, false);
    }

    /**
     * {@code nuke}: the NUKE's breaks. Their wear per tool id is measured ({@link NukeXp}), and with
     * {@code build.nukeMend} a FAST sweep stops at a lane end for a mend ({@link #nukePause}); such a stop
     * ({@link BotActions#BREAK_PAUSED}) is not a failed round either.
     */
    private void runBreak(List<Pos> targets, Runnable notCounted, boolean nuke) {
        runBreak(targets, notCounted, nuke, this::stepFailed);
    }

    /** {@code failed}: what a failed round (not a pause, not the tool floor) does; by default the step fails. */
    private void runBreak(List<Pos> targets, Runnable notCounted, boolean nuke, Consumer<String> failed) {
        runBreak(targets, notCounted, nuke, failed, null);
    }

    /** {@code ok}: run after the task succeeded (and its wear was measured); null = nothing. */
    private void runBreak(List<Pos> targets, Runnable notCounted, boolean nuke, Consumer<String> failed,
                          @Nullable Runnable ok) {
        Map<String, Integer> usesBefore = NukeXp.usesById(bot.inventory().tools());
        int[] broken = {0};
        if (nuke) {
            nukeTaskTargets = targets.size();
            nukeTaskBroken = 0;
            nukeTaskUses = usesBefore;
            ctx.nukeTaskSeen = true;
        }
        Consumer<Pos> counted = p -> {
            broken[0]++;
            if (nuke) nukeTaskBroken++;
        };
        // the NUKE clears the art layer afterwards: blocks that are no target in the bot's way may go (anywhere else
        // they are art to re-place)
        // a carry-over NUKE is followed by no clear: the blocks it keeps are the next tile's art (VERIFY's rules)
        BotTask breakTask = bot.breakBlocks(targets, counted, nuke && config.build.nukeMend ? this::nukePause : null,
            nuke && !carryTeardown());
        Runnable measure = () -> {
            if (!nuke) return;
            nukeXp.observe(usesBefore, NukeXp.usesById(bot.inventory().tools()), broken[0]);
            nukeTaskUses = Map.of(); // now in the measured wear: no live sample of an ended task (a mend follows)
        };
        run(breakTask, ok == null ? measure : () -> {
            measure.run();
            ok.run();
        }, r -> {
            measure.run();
            if (r != null && r.contains(BotActions.BREAK_PAUSED)) {
                notCounted.run();
                log.info("breaking paused ({}): mending first", r);
                return;
            }
            if (r == null || !r.contains(BotActions.TOOL_AT_FLOOR)) {
                failed.accept(r);
                return;
            }
            notCounted.run();
            toolStops++;
            ToolCare care = ToolCare.of(config.build);
            List<InventoryView.Tool> tools = bot.inventory().tools();
            for (InventoryView.Tool t : tools) {
                if (care.atFloor(t) && toolsOut.contains(t.kind()) && r.contains("no " + t.kind() + " above")
                    && !care.hasSpare(tools, t)) {
                    block(worn(t) + ": refusing to break more blocks");
                    return;
                }
            }
            if (toolStops > TOOL_STOPS_BLOCK) {
                block("breaking stopped at the tool safety floor " + toolStops + " times without a successful mend"
                    + " or swap (" + r + "): refusing to break more blocks");
                return;
            }
            log.info("breaking paused ({}); mending or swapping the tool first", r);
            toolRetryTick = 0;
            xpRetryTick = 0;
            swapRetryTick.clear();
        });
    }

    /**
     * NUKE, FAST sweep ({@code build.nukeMend}): asked at each lane end. A
     * reason stops the break task there, so that a Mending tool at or below {@code mendBelowPercent} is mended now
     * ({@link #careForTools}) instead of at the safety floor. Null (go on) when nothing is due, when it cannot be mended
     * now (no bottle carried and no XP chest to try yet, Mending not working, a mend just failed), or when the last
     * pause of this NUKE started no mend.
     */
    private @Nullable String nukePause() {
        Ctx c = ctx;
        if (!nukeMending() || !suppressing || tick < toolRetryTick) return null;
        if (c.pauseMends >= 0 && mendsStarted == c.pauseMends) return null;
        ToolCare care = ToolCare.of(config.build);
        InventoryView inv = bot.inventory();
        InventoryView.Tool m = ToolCare.mostWorn(inv.tools(), t -> t.mending()
            && mendTrouble.getOrDefault(t.itemId(), 0) < MEND_TROUBLE_LIMIT && care.wantsMend(t)
            && !mendWaitsForClear(c, t, care, inv));
        if (m == null) return null;
        boolean carried = inv.count(XP_BOTTLE) > 0;
        if (!carried && (config.site.storage.xpChests.isEmpty() || tick < xpRetryTick)) return null;
        c.pauseMends = mendsStarted;
        return ToolCare.describe(m) + " is due for mending (" + (carried ? inv.count(XP_BOTTLE) + " XP bottles carried"
            : "XP bottles from the XP chest") + ")";
    }

    /**
     * Mending during the clear: the NUKE's mend of {@code t} (due, or begun at a lane end and not
     * at its target yet) waits while the measured wear says it finishes the rest of this break task well above the floor
     * ({@link NukeXp#lastsTheNuke}: unmeasured never waits): with bottles carried it is thrown in WAIT_CLEAR, without
     * them after the clear (the XP chest lies on the way to the carts' off switch), never with a walk out of the NUKE.
     * The rest of the NUKE (later rounds) is a few blocks; the floor stop stays the backstop. Logged once per tool and
     * NUKE.
     */
    private boolean mendWaitsForClear(Ctx c, InventoryView.Tool t, ToolCare care, InventoryView inv) {
        // no break task of this NUKE yet (its start, a reconnect, a retry): the counts are not this NUKE's
        if (!config.build.mendDuringClear || !c.nukeTaskSeen || care.atFloor(t)) return false;
        int left = Math.max(0, nukeTaskTargets - nukeTaskBroken);
        Integer before = nukeTaskUses.get(t.itemId());
        int now = NukeXp.usesById(inv.tools()).getOrDefault(t.itemId(), 0);
        boolean sample = before != null && before >= now;
        double w = nukeXp.measuredWear(t.itemId(), sample ? before - now : 0, sample ? nukeTaskBroken : 0);
        if (!NukeXp.lastsTheNuke(t, left, w, care)) return false;
        if (c.mendDeferred.add(t.itemId())) {
            log.info("{}: its mend waits for the clear{}: {} blocks left in this round cost at most {} uses"
                + " ({} per block measured), {} kept above the floor", ToolCare.describe(t),
                inv.count(XP_BOTTLE) > 0 ? "" : " (no XP bottles carried: fetched after it)", left,
                (int) Math.ceil(NukeXp.wearBound(left, w)), String.format(Locale.ROOT, "%.2f", w),
                (int) Math.ceil(t.max() * NukeXp.DEFER_RESERVE));
        }
        return true;
    }

    /**
     * XP bottles the next NUKE of this tile needs ({@link NukeXp#bottles}: its full blocks, the carried Mending tools,
     * the wear measured so far; with {@code build.mendDuringClear} enough to bring the tools back to the mend target in
     * WAIT_CLEAR); 0 without {@code build.nukeMend}. Worked out once per STORE.
     */
    private int nukeBottles(Ctx c) {
        if (c.nukeBottles >= 0) return c.nukeBottles;
        if (!config.build.nukeMend || plan == null || platform == null) return c.nukeBottles = 0;
        c.nukeBlocks = plan.nukeCount(platform.clearableBlocks);
        return c.nukeBottles = nukeXp.bottles(c.nukeBlocks, bot.inventory().tools(), ToolCare.of(config.build),
            config.build.mendDuringClear);
    }

    /**
     * STORE, once the working items went back (the bot stands by the chests, the inventory has room): tops the carried
     * XP bottles up to what the NUKE needs ({@link #nukeBottles}), each XP chest tried once; room is made first if
     * needed. Short (chests empty): a warning; the NUKE fetches at a lane end. True while a task runs.
     */
    private boolean fetchNukeXp(Ctx c) {
        int want = nukeBottles(c);
        InventoryView inv = bot.inventory();
        int have = inv.count(XP_BOTTLE);
        int more = want - have;
        if (more <= 0) return false;
        List<Pos> chests = config.site.storage.xpChests;
        if (c.nukeXpTries >= chests.size()) {
            if (c.nukeXpTries == chests.size() && !chests.isEmpty()) {
                c.nukeXpTries++;
                log.warn("{}: only {} of the {} XP bottles for the nuke carried ({}); the nuke fetches more when a"
                    + " tool is due", label(), have, want, mendXpFetch.cause(chests));
            }
            return false;
        }
        if (xpRoom(inv, have) < more && makeRoom(inv, slotsFor(inv, XP_BOTTLE, more), "XP bottles for the nuke")) {
            return true;
        }
        Pos chest = chests.get(c.nukeXpTries++);
        if (c.nukeXpTries == 1) mendXpFetch.clear();
        log.info("{}: XP for the nuke: {} blocks at {}: taking {} XP bottles at {} (carrying {}, {} wanted)", label(),
            c.nukeBlocks, nukeXp.describe(inv.tools()), more, chest, have, want);
        run(bot.withdraw(chest, Map.of(XP_BOTTLE, more), r -> {
            if (sum(r.moved()) <= 0) mendXpFetch.none(chest, r);
        }), null, r -> {
            log.warn("XP withdraw for the nuke failed: {}", r);
            mendXpFetch.failed(chest, r);
        });
        return true;
    }

    private void resetToolCare() {
        toolRetryTick = 0;
        xpRetryTick = 0;
        xpFetches = 0;
        mendXpFetch.clear();
        toolStops = 0;
        toolFetches.clear();
        swapRetryTick.clear();
        mendTrouble.clear();
        mendingIds.clear();
        toolsOut.clear();
        xpFullRetried = false;
        resetGear();
    }

    // ---- module suppression ----

    // ---- combat ----

    /** A listed mob was seen in the fight area: the running task parks, then {@link #fight} runs. */
    private boolean combatWanted;
    /** A fight runs: the build modules are given back and KillAura is on. */
    private boolean inCombat;
    /** The engine switched KillAura on for this fight (and switches it off after). */
    private boolean combatKillAura;
    private long combatStartedAt;
    private long combatCooldownUntil;
    private String combatSeen = "";
    private static final int COMBAT_POLL_TICKS = 10;
    private static final String KILL_AURA = "KillAura";
    /** The fight running (or wanted) is self-defence away from the platform's fight area ({@link Combat#defenceArea}). */
    private boolean defending;
    /** Tick a listed mob last hit the bot ({@link #noteHit}), or -1. */
    private long mobHitTick = -1;
    /** Hits by listed mobs so far ({@link MobHits#count}: the fight task notices a new one by it). */
    private long mobHitCount;
    /** The listed mobs that hit the bot in this hurt episode, by entity id, the latest hit last. */
    private final Map<Integer, Attacker> attackers = new LinkedHashMap<>();

    /** A listed mob that hit the bot: the mob as it was then, the damage type, the tick. */
    private record Attacker(WorldView.Thing mob, String damage, long tick) {
    }

    /** What the fight task reads ({@link BotActions#fight}): the hit count and the mobs that hit the bot lately. */
    private final MobHits mobHits = new MobHits() {
        @Override
        public long count() {
            return mobHitCount;
        }

        @Override
        public Set<Integer> attackers() {
            return recentAttackers();
        }
    };
    /** Damage that starts nothing, as last logged ("minecraft:fall"), and when: one line per kind and hurt window. */
    private String quietDamage = "";
    private long quietDamageTick = -1;
    /** Bot ticks run (from {@link #onBotTickStarting} to {@link #onBotTickStopped}); read by {@link #onHit}. */
    private volatile boolean botTicks;
    /** Bumped whenever bot ticks start or stop: a hit queued in one stretch of bot ticks never counts in the next. */
    private volatile int tickStretch;
    /** A hit this recent counts as "being hurt" (pillagers shoot every 2-3 s). */
    static final int HURT_WINDOW_TICKS = 200;
    /**
     * Mobs a fight could not reach that had not hit the bot (a pillager in the water channel under the moss walkway):
     * they start no fight until a listed mob hits the bot after {@link #ignoredAt}.
     */
    private final MobMemory ignoredMobs = new MobMemory();
    private long ignoredAt;
    /**
     * {@code build.fightSetAsideUnreachable}: the same mobs by entity id, kept while the client does not track them; the
     * one memory read while the switch is on ({@link SetAsideMobs}). Its log lines go to {@link #log} when written.
     */
    private final SetAsideMobs setAsideMobs = new SetAsideMobs(this::logSetAside);
    /** A mob set aside by {@link #setAsideMobs} (switch on): never counted in the engine's fight areas. */
    private final java.util.function.Predicate<WorldView.Mob> setAsideMob = this::setAsideHides;

    private void logSetAside(String line) {
        log.info("{}", line);
    }

    /** {@code m} is set aside now (switch on; one {@link SetAsideMobs#hides} look). */
    private boolean setAsideHides(WorldView.Mob m) {
        return config.build.fightSetAsideUnreachable && setAsideMobs.hides(m, setAsideLook());
    }
    /**
     * Who is a teammate (the config's {@code teammates} list): never a fight target, a threat or an attacker. The
     * process-wide instance; tests set their own ({@link #teammates(Teammates)}).
     */
    private volatile Teammates teammates = Teammates.shared();
    /** A teammate among the mobs: never counted in a fight area ({@link FightArea#exempting}). */
    private final java.util.function.Predicate<WorldView.Mob> teammateMob = m -> Combat.teammate(teammates, m);

    /** Uses {@code t} for the teammate check instead of the shared instance (tests). */
    void teammates(Teammates t) {
        teammates = t == null ? Teammates.shared() : t;
    }

    /** {@code a} with the teammates never counted in it, or null. */
    private @Nullable FightArea exemptTeammates(@Nullable FightArea a) {
        return a == null ? null : a.exempting(teammateMob);
    }

    /**
     * The fight area now (null: no fight here or in this phase): the platform's, else, while the bot is being hurt or a
     * self-defence fight runs, the mobs around the bot ({@link Combat#defenceArea}).
     */
    private FightArea combatArea() {
        return exemptSetAside(combatAreaWithSetAside());
    }

    /** {@link #combatArea} with the set-aside mobs still counted (teammates never). */
    private FightArea combatAreaWithSetAside() {
        WorldView w = bot.world();
        FightArea a = Combat.area(config.combat, platform, phase, w.playerX(), w.playerY(), w.playerZ());
        if (a != null || !(defending || hitByMobs())) return exemptTeammates(a);
        return exemptTeammates(Combat.defenceArea(config.combat, phase, w.playerX(), w.playerY(), w.playerZ()));
    }

    /**
     * {@code a} with the mobs set aside as out of reach never counted ({@code build.fightSetAsideUnreachable}): no fight
     * starts for them and a fight for other mobs does not chase them; a hit or coming within reach ends that at once
     * ({@link SetAsideMobs}). Off: {@code a} itself.
     */
    private @Nullable FightArea exemptSetAside(@Nullable FightArea a) {
        if (a == null || !config.build.fightSetAsideUnreachable) return a;
        return a.exempting(a.exempt().or(setAsideMob));
    }

    /** Where the fight is, for log and Discord lines: the platform, or the bot (self-defence, or no platform). */
    private String fightPlace() {
        return defending || platform == null ? "the bot" : platform.id;
    }

    /**
     * A listed mob ({@code combat.mobs}) hit the bot within the last {@link #HURT_WINDOW_TICKS} ticks: its blow, or a
     * projectile it shot ({@link #noteHit}). Only this is "being hurt" for self-defence, the health hold's retreat and
     * the out-of-reach memory; a fall, flying into a wall, fire, drowning, starving or a player's hit never is.
     */
    private boolean hitByMobs() {
        return mobHitTick >= 0 && tick - mobHitTick <= HURT_WINDOW_TICKS;
    }

    /**
     * The server's damage event on the bot (its {@code damage_event} packet, resolved against the entity cache on the
     * client loop by MapArtModule). Thread-safe: queued for the next tick. Damage while
     * bot ticks are stopped (a player controls the account) never counts, nor damage queued before bot ticks stopped
     * and started again. Live 2026-10-13: while the owner flew the account the bot fell and flew into walls; when bot ticks
     * started again the health inference took that as a hit and fought two harmless pillagers in a water channel.
     */
    public void onHit(Hit hit) {
        boolean live = botTicks;
        int stretch = tickStretch;
        commands.add(() -> noteHit(hit, live && stretch == tickStretch));
    }

    /**
     * A damage event on the tick thread: a hit by a listed mob ({@link Combat#attacker}) starts or extends the hurt
     * episode ({@link #hitByMobs}) and names the mob as an attacker (fought first); anything else is logged and
     * starts nothing.
     */
    private void noteHit(Hit hit, boolean live) {
        WorldView.Thing a = live ? Combat.attacker(hit, listedTypes(), teammates) : null;
        if (a == null) {
            String why = !live ? "while bot ticks were stopped (a player in control)"
                : Combat.byTeammate(hit, teammates) ? "from a teammate (never a threat)" : "with no listed mob behind it";
            String kind = why + " " + hit.type() + (hit.cause() != null ? " " + hit.cause().type() : "");
            if (!kind.equals(quietDamage) || quietDamageTick < 0 || tick - quietDamageTick > HURT_WINDOW_TICKS)
                log.info("damage {} {}: not a hit by mobs, no fight", hit.describe(), why);
            quietDamage = kind;
            quietDamageTick = tick;
            return;
        }
        recentAttackers(); // the episode's older attackers go
        if (config.build.fightSetAsideUnreachable) {
            // the attacker, and every hostile mob set aside (one of them may be shooting), count again at once
            String back = setAsideMobs.hit(a.id());
            if (!back.isEmpty()) log.info("a mob hit the bot ({}): {}", attacker(a), back);
        }
        boolean known = hitByMobs() && attackers.containsKey(a.id());
        mobHitTick = tick;
        mobHitCount++;
        attackers.remove(a.id()); // the latest hit last
        attackers.put(a.id(), new Attacker(a, hit.type(), tick));
        if (!known) log.info("hit by {} ({}): self-defense and the health hold count it", attacker(a), hit.describe());
    }

    /** "pillager #412 at 12.5,64.0,-3.5" for log lines. */
    private static String attacker(WorldView.Thing a) {
        String at = Double.isNaN(a.x()) ? "" : String.format(Locale.ROOT, " at %.1f,%.1f,%.1f", a.x(), a.y(), a.z());
        return a.type() + " #" + a.id() + at;
    }

    /**
     * Entity ids of the listed mobs that hit the bot within the hurt window, and, while a fight is wanted or runs, every
     * one that hit it before: the fight may start late (a task finishing first) or last longer than the window, and the
     * mob that shot is still the one to go for (local run 2026-10-13 at 10x: the window ran out before the fight began).
     */
    private Set<Integer> recentAttackers() {
        if (!combatWanted && !inCombat) attackers.values().removeIf(x -> tick - x.tick() > HURT_WINDOW_TICKS);
        return Set.copyOf(attackers.keySet());
    }

    /** "pillager #412 (minecraft:arrow)": the mobs that hit the bot within the hurt window, the latest first. */
    private String attackersText() {
        recentAttackers();
        List<Attacker> list = new ArrayList<>(attackers.values());
        java.util.Collections.reverse(list);
        StringBuilder sb = new StringBuilder();
        for (Attacker x : list) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(x.mob().type()).append(" #").append(x.mob().id()).append(" (").append(x.damage()).append(')');
        }
        return sb.isEmpty() ? "a listed mob" : sb.toString();
    }

    /** Bot ticks (re)start: whatever hit the bot before (a player had control since) is over. */
    private void endHitEpisode() {
        mobHitTick = -1;
        attackers.clear();
    }

    /**
     * Every tick while the engine acts: looks for listed mobs in the fight area (every {@link #COMBAT_POLL_TICKS}) and
     * asks the running task to park at its next safe point; ends a fight that ran over {@code combat.maxSeconds}.
     */
    private void checkCombat() {
        long now = clock.millis();
        if (inCombat) {
            checkFightTime(now);
            return;
        }
        if (combatWanted) {
            askToParkForCombat();
            return;
        }
        if (tick % COMBAT_POLL_TICKS != 0) return;
        FightArea counted = combatAreaWithSetAside();
        if (counted == null) return;
        FightArea area = exemptSetAside(counted);
        forgetIgnoredWhenHit();
        List<WorldView.Mob> mobs = area.filter(notSetAside(area.types()));
        if (config.build.fightSetAsideUnreachable) logSetAsideSkips(counted);
        if (mobs.isEmpty()) return;
        if (now < combatCooldownUntil && !meleeThreat(mobs)) return;
        combatWanted = true;
        WorldView w = bot.world();
        defending = Combat.area(config.combat, platform, phase, w.playerX(), w.playerY(), w.playerZ()) == null;
        combatSeen = Combat.describe(mobs);
        log.warn("{} near {} in {}{}: {}", combatSeen, fightPlace(), phase,
            defending ? " while it is being hit by " + attackersText() + " (self-defense)" : "", task == null
            ? "fighting now"
            : vitalsTask ? "fighting after " + task.name()
            : "stopping " + task.name() + " at its next safe point to fight");
        // no task: the fight starts on this tick's step, not after the phase's poll (IDLE sleeps 10 s; local run
        // 2026-10-13: "fighting now" in IDLE, the fight a poll later)
        if (task == null) wakeTick = Math.min(wakeTick, tick);
        askToParkForCombat();
    }

    /**
     * A fight over {@code combat.maxSeconds} ends with one alert; the mobs are ignored for
     * {@code combat.cooldownSeconds} (all but one that hurts the bot from within {@link #MELEE_RANGE},
     * {@link #meleeThreat}). With {@code build.fightSetAsideUnreachable}, every mob of the fight area still out of
     * reach ({@link SetAsideMobs#outOfReach}: never one within reach, never a creeper close by or fusing) is set aside
     * too, so a time-out never loops on the same unreachable mob (live 2026-10-30: a creeper under the floor, 120 s of
     * fighting and 60 s of work, round after round).
     */
    private void checkFightTime(long now) {
        int max = Math.max(1, config.combat.maxSeconds);
        if (now - combatStartedAt < max * 1000L || task == null || !vitalsTask) return;
        List<WorldView.Mob> outOfReach = outOfReachAtTimeOut();
        BotTask t = task;
        task = null;
        taskOk = null;
        taskFail = null;
        vitalsTask = false;
        stoppedDefence = false;
        t.cancel();
        combatCooldownUntil = now + Math.max(0, config.combat.cooldownSeconds) * 1000L;
        alertHere("Fight not over", "Still fighting " + combatSeen + " near " + fightPlace()
            + " after " + max + " s. The bot goes back to work and ignores mobs for "
            + config.combat.cooldownSeconds + " s. If they keep coming, clear them in game.");
        endCombat("ran out of time after " + max + " s");
        if (!outOfReach.isEmpty()) setAside(outOfReach);
    }

    /** {@link #checkFightTime}'s backstop: the fight area's mobs not set aside and out of reach now (switch on). */
    private List<WorldView.Mob> outOfReachAtTimeOut() {
        if (!config.build.fightSetAsideUnreachable) return List.of();
        FightArea area = combatArea();
        if (area == null) return List.of();
        SetAsideMobs.Look at = setAsideLook();
        return area.filter(notSetAside(area.types())).stream().filter(m -> SetAsideMobs.outOfReach(m, at)).toList();
    }

    /**
     * {@code mobs} start no fight until a listed mob hits the bot after now. Hits are noted at the start of a tick
     * (the commands), so a hit the fight task already saw this tick is no new one.
     */
    private void setAside(List<WorldView.Mob> mobs) {
        ignoredMobs.addAll(mobs);
        ignoredAt = tick;
        if (!config.build.fightSetAsideUnreachable || mobs.isEmpty()) return;
        setAsideMobs.addAll(mobs, tick);
        StringBuilder sb = new StringBuilder();
        for (WorldView.Mob m : mobs) sb.append(sb.isEmpty() ? "" : ", ").append(SetAsideMobs.describe(m));
        log.info("set aside as out of reach (build.fightSetAsideUnreachable): {}; no fight for them until they hit the"
            + " bot, come within reach or {} min pass", sb, Math.max(1, config.build.fightSetAsideMinutes));
    }

    /**
     * A listed mob hit the bot after the out-of-reach mobs were set aside: they count again. Only a hit by a mob
     * ({@link #hitByMobs}); a fall or any other damage leaves them set aside. With {@code build.fightSetAsideUnreachable}
     * the position memory is not read: it is emptied without a line ({@link #noteHit} ends the set-aside by id).
     */
    private void forgetIgnoredWhenHit() {
        if (ignoredMobs.isEmpty() || mobHitTick <= ignoredAt) return;
        if (!config.build.fightSetAsideUnreachable)
            log.info("a mob hit the bot: {} mob(s) left alone as out of reach count again", ignoredMobs.size());
        ignoredMobs.clear();
    }

    /**
     * {@code build.fightSetAsideUnreachable}, every poll: the mobs of the fight area set aside as out of reach, one line
     * per mob and minute ({@link SetAsideMobs#skipLogDue}), so a fight not started for them shows in the log.
     */
    private void logSetAsideSkips(FightArea counted) {
        if (setAsideMobs.isEmpty()) return;
        for (WorldView.Mob m : counted.filter(mobsSeen(counted.types()))) {
            if (!setAsideMobs.contains(m) || !setAsideMobs.skipLogDue(m, tick)) continue;
            log.info("{} is set aside as out of reach ({} s ago): no fight for it until it hits the bot, comes within"
                + " reach or {} min pass", SetAsideMobs.describe(m), SetAsideMobs.seconds(setAsideMobs.age(m, tick)),
                Math.max(1, config.build.fightSetAsideMinutes));
        }
    }

    /** A listed mob this close (blocks, feet to feet) to a hurt bot is fought in the fight cooldown too. */
    static final double MELEE_RANGE = 4.0;

    /**
     * The bot is being hurt and one of {@code mobs} is within {@link #MELEE_RANGE}: a mob at the bot can be hit where
     * it stands, so the cooldown after a fight that ran out of time does not hold it off. A mob further away (a
     * pillager shooting from out of reach, the usual reason a fight runs out of time) stays ignored for the cooldown;
     * the health hold's retreat ({@code safety.leaveWhenHurt}) takes the bot away from it instead.
     */
    private boolean meleeThreat(List<WorldView.Mob> mobs) {
        if (!hitByMobs()) return false;
        WorldView w = bot.world();
        double r2 = MELEE_RANGE * MELEE_RANGE;
        return mobs.stream().anyMatch(m -> m.distSq(w.playerX(), w.playerY(), w.playerZ()) <= r2);
    }

    private void askToParkForCombat() {
        if (task == null || vitalsTask || askedToPark == task) return;
        askedToPark = task;
        parkAccepted = task.requestPark();
        if (!parkAccepted) log.info("finishing {} first (it cannot stop midway), then fighting", task.name());
    }

    /**
     * Between tasks (also with a task parked): the fight. The build modules come back (AutoTotem keeps the totem,
     * AutoEat eats), KillAura is switched on when it is off, one always-on line goes out, and {@code fight} runs until
     * the area is quiet; then the modules are suppressed again and the parked task carries on. True while it runs.
     */
    private boolean fight() {
        if (!combatWanted) return false;
        FightArea area = combatArea();
        if (area == null) {
            endCombat("no fight in " + phase);
            return false;
        }
        if (!inCombat && area.filter(notSetAside(area.types())).isEmpty()) {
            // gone before it began (killed, out of range: the bot retreated meanwhile)
            log.info("{} gone before the fight began; back to work", combatSeen);
            endCombat(null);
            return false;
        }
        startFight(area);
        return true;
    }

    /** Starts (or carries on with) the fight task in {@code area} ({@link #fight}, {@link #defendWhileStopped}). */
    private void startFight(FightArea area) {
        if (!inCombat) {
            inCombat = true;
            combatStartedAt = clock.millis();
            restoreModules();
            if (!modules.isEnabled(KILL_AURA)) {
                modules.setEnabled(KILL_AURA, true);
                combatKillAura = modules.isEnabled(KILL_AURA);
            }
            notifier.info("Fighting mobs", (job != null ? label() + ": " : "") + combatSeen + " near " + fightPlace()
                + ". Work stops until they are gone.");
        }
        // stopped (self-defence while paused or BLOCKED): the BLOCKED reason stays the phase note
        if (stopped()) vitalsNote = "fighting " + combatSeen;
        else note = "fighting " + combatSeen;
        int quiet = Math.max(1, config.combat.quietSeconds) * 20;
        runVitals(bot.fight(area, config.build.toolSafetyFloor, quiet, out -> setAside(out), mobHits),
            () -> endCombat(null), this::fightFailed);
    }

    /**
     * The fight task failed: hurt by mobs it cannot walk to (CombatTask), or an error. Standing there is no defence,
     * so a bot that is being hurt with listed mobs around retreats from them ({@code safety.leaveWhenHurt}; not during
     * the clear, not while safety-locked). They are then set aside like unreachable mobs: the next hit starts the next
     * fight, not the hits that made this one fail (the bot walks back into their range after the retreat).
     */
    private void fightFailed(String r) {
        endCombat("the fight task failed: " + r);
        if (!hitByMobs() || !config.safety.leaveWhenHurt || safetyLocked || duringClear()) return;
        List<WorldView.Mob> near = threats();
        if (near.isEmpty()) return;
        setAside(near);
        log.warn("hit by {} with {} around and the fight failed: retreating; they count again once a mob hits the bot"
            + " again", attackersText(), Combat.describe(near));
        boolean stopped = stopped();
        runVitals(bot.retreat(near), null, x -> log.warn("retreat failed: {}", x));
        stoppedDefence = stopped;
    }

    /** Paused, BLOCKED or backing off: the engine does not work ({@link #onBotTick}'s gate). */
    private boolean stopped() {
        return config.paused || phase == Phase.BLOCKED || (backingOff && clock.millis() < backoffUntil);
    }

    /** Why the engine does not work, for log lines: "paused", "safety-stopped", "BLOCKED", "backing off". */
    private String stoppedWhy() {
        if (config.paused) return safetyLocked ? "safety-stopped" : "paused";
        return phase == Phase.BLOCKED ? "BLOCKED" : "backing off";
    }

    /** CLEAR, WAIT_CLEAR, or BLOCKED from one of them: the bot stays where the clear wants it (no leave, no fight). */
    private boolean duringClear() {
        Phase at = phase == Phase.BLOCKED && blockedFrom != null ? blockedFrom : phase;
        return at == Phase.CLEAR || at == Phase.WAIT_CLEAR;
    }

    /** The running task is a fight or retreat started while stopped ({@link #defendWhileStopped}). */
    private boolean stoppedDefence;
    /** Retreats while stopped in this hurt episode (back to 0 once the bot is not being hurt). */
    private int stoppedRetreats;

    /**
     * Paused, BLOCKED or backing off (self-defence while stopped): nothing works, but a
     * bot being hit by a listed mob ({@link #hitByMobs}) with a listed mob within {@code combat.radius} fights back
     * ({@code combat.selfDefense}; the cooldown after a fight that ran out of time does not apply: it exists to let the
     * work carry on, and there is none), or, with no fight possible (combat or self-defence off), retreats from them
     * ({@code safety.leaveWhenHurt}, at most {@link #MAX_HOLD_LEAVES} per hurt episode); then it stands still again. A
     * fight that cannot reach the mobs hurting the bot fails and retreats ({@link #fightFailed}). The pause, BLOCKED
     * and the back-off stay as they are. Never during the clear ({@link #duringClear}). Safety-locked (a SAFETY STOP,
     * the kill switch: "do not move"): a fight in reach only, no chase, no retreat. Live 2026-10-12: the health hold
     * went BLOCKED and the bot stood still under two pillagers' arrows until it died. True while its fight or retreat
     * runs (nothing else this tick).
     */
    private boolean defendWhileStopped() {
        InventoryView inv = bot.inventory();
        if (task != null) {
            if (!stoppedDefence) return false; // the starving rescue's fetch or eat
            if (inCombat) checkFightTime(clock.millis());
            if (task == null) return true;
            TaskStatus s = task.tick();
            if (s != TaskStatus.RUNNING) finishTask(s);
            return true;
        }
        if (!hitByMobs()) stoppedRetreats = 0;
        if (!ticking || !restored || tick % COMBAT_POLL_TICKS != 0 || !hitByMobs() || inv.health() <= 0
            || duringClear()) return false;
        forgetIgnoredWhenHit();
        List<WorldView.Mob> near = nearBot(notSetAside(listedTypes()));
        if (near.isEmpty()) return false;
        WorldView w = bot.world();
        String why = stoppedWhy();
        FightArea area = exemptTeammates(Combat.defenceArea(config.combat, w.playerX(), w.playerY(), w.playerZ(),
            !safetyLocked));
        if (area != null) {
            combatWanted = true;
            defending = true;
            combatSeen = Combat.describe(near);
            log.warn("{} near the bot while it is {} and being hit by {} (self-defense): fighting{}; it stays {}",
                combatSeen, why, attackersText(), safetyLocked ? " what comes in reach (no chase)" : "", why);
            startFight(area);
            stoppedDefence = true;
            return true;
        }
        if (!config.safety.leaveWhenHurt || safetyLocked || stoppedRetreats >= MAX_HOLD_LEAVES) return false;
        stoppedRetreats++;
        log.warn("hit by {} with {} around while {} and no fight (combat or self-defense off): retreating ({} of {})",
            attackersText(), Combat.describe(near), why, stoppedRetreats, MAX_HOLD_LEAVES);
        runVitals(bot.retreat(near), null, x -> log.warn("retreat failed: {}", x));
        stoppedDefence = true;
        return true;
    }

    /** The fight is over ({@code why} null) or cut short: KillAura back off if the engine switched it on. */
    private void endCombat(@Nullable String why) {
        boolean was = inCombat;
        inCombat = false;
        combatWanted = false;
        defending = false;
        if (combatKillAura) {
            modules.setEnabled(KILL_AURA, false);
            combatKillAura = false;
        }
        if (!was) return;
        long s = (clock.millis() - combatStartedAt) / 1000;
        String then = stopped() ? "it stays " + stoppedWhy() : "back to work";
        if (why == null) log.info("fight over after {} s ({}); {}", s, combatSeen, then);
        else log.warn("fight ended after {} s: {}", s, why);
        notifier.detail("Fight over", (job != null ? label() + ": " : "") + combatSeen + ", " + s + " s"
            + (why == null ? "" : " (" + why + ")") + ". " + Character.toUpperCase(then.charAt(0)) + then.substring(1)
            + ".");
        if (note != null && note.startsWith("fighting")) note = "";
        if (vitalsNote.startsWith("fighting")) vitalsNote = "";
    }

    private void updateModuleSuppression() {
        // IDLE counts as active while a requested stock scan walks to the containers
        boolean working = phase != Phase.IDLE || task != null || parkedTask != null || forceScan;
        // the starving rescue's fetch (or eat) runs with the modules off like any task, also while paused or BLOCKED:
        // AutoEat must not eat from the open chest menu. Between its tasks they are back, and AutoEat eats the food.
        boolean rescuing = task != null && rescueTask;
        boolean want = ticking && restored && !inCombat
            && (rescuing || (!config.paused && phase != Phase.BLOCKED && working));
        if (want && !suppressing) suppressModules();
        else if (!want && suppressing) restoreModules();
    }

    /**
     * {@code build.disableModules}, plus AutoEat while the engine eats by itself, plus AutoTotem in AIRPLACE mode. Saved
     * configs keep their own list (a new default never reaches them), so these two are added here: AutoEat would eat
     * from an open chest menu, and AutoTotem, seeing the offhand without its totem between the two swaps of an
     * airplace, would move one with an inventory click while the bot walks (Grim MultiActionsC) and spend one of
     * {@link io.github.digitalisp.mapart.bot.impl.AirPlaceGuard}'s corrective swaps. In LOOK mode nothing swaps the offhand:
     * AutoTotem acts only after a totem was used up, so the list decides (the default lists it).
     */
    private List<String> modulesToSuppress() {
        List<String> names = new ArrayList<>(config.build.disableModules);
        if (engineEats()) addModule(names, AUTO_EAT);
        if (PlaceMode.of(config.build) == PlaceMode.AIRPLACE) addModule(names, AUTO_TOTEM);
        return names;
    }

    private static void addModule(List<String> names, String name) {
        if (names.stream().noneMatch(name::equalsIgnoreCase)) names.add(name);
    }

    private void suppressModules() {
        suppressing = true;
        for (String name : modulesToSuppress()) {
            try {
                if (modules.isEnabled(name)) {
                    modules.setEnabled(name, false);
                    suppressed.add(name);
                }
            } catch (RuntimeException e) {
                log.warn("could not disable module {}: {}", name, e.toString());
            }
        }
        if (!suppressed.isEmpty()) log.info("disabled modules while building: {}", suppressed);
    }

    private void restoreModules() {
        if (!suppressing) return;
        suppressing = false;
        for (String name : suppressed) {
            try {
                modules.setEnabled(name, true);
            } catch (RuntimeException e) {
                log.warn("could not re-enable module {}: {}", name, e.toString());
            }
        }
        if (!suppressed.isEmpty()) log.info("re-enabled modules: {}", suppressed);
        suppressed.clear();
    }

    // ---- progress + status ----

    private void maybeReportProgress() {
        reportUnit(false); // the coordination's progress (every 5 s at most; LocalCoordination drops it)
        if (job == null || tile == null) return;
        if (phase != Phase.RESTOCK && phase != Phase.BUILD && phase != Phase.VERIFY) return;
        long interval = config.discord.progressIntervalMinutes * 60_000L;
        if (interval <= 0) return;
        long now = clock.millis();
        if (now - lastProgressAt < interval) return;
        lastProgressAt = now;
        notifier.progress(job, tile.x, tile.z, tile.placed, tile.total, printEta(now));
        saveJob(job);
    }

    /** Any thread: reflect a pause/resume request in the status snapshot right away. */
    private void publishPauseFlag() {
        EngineStatus s = status;
        status = new EngineStatus(s.enabled(), config.paused, config.paused ? pauseReason : "", s.phase(), s.jobId(),
            s.jobName(), s.tileX(), s.tileZ(), s.platformId(), s.placed(), s.total(), s.note(), s.phaseSince(),
            s.etaSeconds(), s.rideStartedAt(), s.rideExpectedSeconds());
    }

    private void publishStatus() {
        boolean riding = phase == Phase.RIDE && tile != null && tile.ridePressed && tile.rideStartedAt > 0;
        status = new EngineStatus(config.enabled, config.paused, config.paused ? pauseReason : "", phase,
            job != null ? job.id : null, job != null ? job.name : null,
            tile != null ? tile.x : -1, tile != null ? tile.z : -1,
            platform != null ? platform.id : null,
            tile != null ? tile.placed : 0, tile != null ? tile.total : 0,
            vitalsNote.isEmpty() ? note : vitalsNote, phaseSince, printEta(clock.millis()),
            riding ? tile.rideStartedAt : 0, riding ? rideExpectedSeconds() : 0);
    }

    /**
     * Seconds until the print is done at the current rate, for the status and the progress post; -1 when not known or
     * not printing (RESTOCK, BUILD, VERIFY). A colour-suppression map: the blocks of all its passes still to place,
     * plus the ride when the platform rides (one map, one ETA); other jobs: the current map.
     */
    private long printEta(long now) {
        if (job == null || tile == null) return -1;
        if (phase != Phase.RESTOCK && phase != Phase.BUILD && phase != Phase.VERIFY) return -1;
        int left = Math.max(0, tile.total - tile.placed);
        boolean cs = job.isSuppression();
        if (cs) for (Job.TileState t : job.tiles)
            if (t != tile && t.status != TileStatus.STORED) left += Math.max(0, t.total - t.progress());
        long eta = rate.etaSeconds(left, now);
        if (eta < 0) return -1;
        return cs && platform != null && platform.ride != null && platform.ride.configured()
            ? eta + rideExpectedSeconds() : eta;
    }

    /**
     * How long this platform's ride is expected to take: its last completed ride on record (launch to brake, the
     * newest by its end) when that lies between half and one and a half times {@code ride.expectedMinutes}, else that
     * value. Looked up once per platform and again after each ride.
     */
    private long rideExpectedSeconds() {
        if (platform == null || platform.ride == null) return 0;
        if (platform.id.equals(rideExpectedFor)) return rideExpected;
        long best;
        try {
            best = expectedRideSeconds(store.list(), platform.id, platform.ride.expectedMinutes);
        } catch (RuntimeException e) {
            log.debug("the last ride's duration could not be read: {}", e.toString());
            best = Math.max(1, platform.ride.expectedMinutes) * 60L;
        }
        rideExpectedFor = platform.id;
        rideExpected = best;
        return best;
    }

    /**
     * {@link #rideExpectedSeconds}: the newest completed ride on {@code platformId} among {@code jobs} (launch to
     * brake) when it lies between half and one and a half times {@code expectedMinutes}, else {@code expectedMinutes}.
     */
    static long expectedRideSeconds(List<Job> jobs, String platformId, int expectedMinutes) {
        long cfg = Math.max(1, expectedMinutes) * 60L;
        long best = cfg;
        long newest = 0;
        for (Job j : jobs) {
            if (j == null || !j.isSuppression()) continue;
            for (Job.TileState t : j.tiles) {
                if (t == null || !platformId.equals(t.platformId) || t.rideStartedAt <= 0
                    || t.rideEndedAt <= t.rideStartedAt || t.rideEndedAt <= newest) continue;
                long took = (t.rideEndedAt - t.rideStartedAt) / 1000;
                if (took * 2 < cfg || took * 2 > cfg * 3) continue;
                newest = t.rideEndedAt;
                best = took;
            }
        }
        return best;
    }

    /** {@link #rideExpectedSeconds}: the platform it was looked up for (null = look again) and the value. */
    private String rideExpectedFor;
    private long rideExpected;

    /** Phase-local working state; replaced on every transition, failure, pause and restart. */
    private static final class Ctx {
        final Map<String, Integer> attempts = new HashMap<>();
        // colour-suppression ride
        boolean preChecked;
        boolean cartBroken;
        /** Lost-ride recovery: the bot reached the booth; the cart was put back on the start rail. */
        boolean lostAtBooth;
        boolean lostCartPlaced;
        /** recoverLostRide: a cart stood by the brake when the bot came to take it off. */
        boolean lostCartStood;
        /** {@link BuildEngine#finishLostRun}: its stage, the tick the bot came to the booth, looks with the cart there. */
        int lostStage;
        long lostWaitSince = -1;
        int lostCartSeen;
        int lostLogged;
        int lostRounds;
        /** RIDE_PRELOAD: the lap ended in this phase (the bot gets out, then RIDE_BOARD). */
        boolean lapDone;
        /** RIDE_PRELOAD: no cart stood on the lap's start rail: a spare from ride.cartChests is put there. */
        boolean preloadSpare;
        /** RIDE_PRELOAD: the walk back to the lap's start after getting out on the track was tried. */
        boolean lapWalkBack;
        Reading reading;
        List<Pos> vpOrder;
        int vp;
        boolean vpWalked;
        MachineGuard.Scan guardScan;
        /** RIDE_BOARD of a paused ride: the map left the hand before the walk to the viewpoints. */
        boolean pauseStowed;
        /** RIDE_BOARD of a ride paused at a hold off the start rail's row: the foot routes to the walkway and back. */
        boolean pauseAtWalkway;
        boolean pauseBackAtHold;
        Reading settleBase;
        long settleSince = -1;
        int settleLogged;
        /**
         * RIDE_RESET of a lost run with every machine home: the blocks left on the layers at the last look (-1: none
         * being confirmed) and the tick since which that count has been the same.
         */
        int leftover = -1;
        long leftoverSince;
        /** RIDE_BOARD: the wait for the scheduled restart went to the log. */
        boolean restartWaitLogged;
        /** RIDE_BOARD: tick the pre-ride guard first differed (it is read again until ride.settleMinutes passed). */
        long guardWaitSince = -1;
        /**
         * RIDE_BOARD, the pre-ride guard's wait ({@link BuildEngine#guardDiffers}): readings that differed so far, the
         * last one's {@link MachineGuard.Diff#signature} and the tick since which it has been the same.
         */
        int guardReads;
        long guardSignature;
        long guardSameSince;
        /** ... the last walk's whole reading, the chunks that differed in it, and where the next look is taken from. */
        Reading guardBase;
        Set<Long> guardChunks = Set.of();
        Pos guardSpot;
        boolean guardSpotWalked;
        boolean guardSpotFailed;
        boolean walkedToPlatform;
        /** build.nukeEntryCorner: the load walk went to the entry corner (on to the map station if they do not load). */
        boolean walkedToEntry;
        int loadWaitTicks;
        /** NUKE: the art layer's items were noted as going into the sorter ({@link BuildEngine#noteSorterFed}). */
        boolean sorterNoted;
        /** NUKE: this entry's carry-over decision ({@link BuildEngine#decideCarryOver}); null = not decided yet. */
        Boolean carry;
        // near-only sweep has its own context: normal STOCK_CHECK scans keep their queue and discovery.
        Ctx nearScan;
        PlatformGeometry nearGeometry;
        /** The targeted near look ran in this phase ... */
        PlatformGeometry nearChecked;
        /** ... and the full near sweep. */
        PlatformGeometry nearFullChecked;
        String nearReason;
        Set<String> nearItems;
        /** The look under way: asked with a far trip to follow; the full sweep; the items and containers it reads. */
        boolean nearWantFull;
        boolean nearFull;
        Set<String> nearDue;
        Set<Pos> nearExtra;
        boolean nearRefreshed;
        /** Item → cells that wait for the sorter to return them ({@link PlatformCfg#sorterRefills}); empty = none. */
        Map<String, Integer> sorterLag = Map.of();
        /** The wait for the sorter began at this time (ms; -1 = not waiting) and its best near count, and when. */
        long lagSince = -1;
        int lagBest;
        long lagBestAt;
        long lagLookAt;
        boolean lagWalked;
        /** The stand at the sorter's pickup spot was started this RESTOCK (once; {@link PlatformCfg#sorterPickup}). */
        boolean pickupStarted;
        /** The near chests of the short items were looked at before a pickup stand (once per RESTOCK). */
        boolean pickupLooked;
        /** The junk was thrown at its spot this RESTOCK (once). */
        boolean junkThrown;
        /** The inventory before the pickup stand (item → count): the throw back at the edge follows it; null = none due. */
        Map<String, Integer> pickupBefore;
        boolean pickupReturned;
        String lagLogged = "";
        // stock routine
        boolean discoveryDone;
        Set<Pos> discovered;
        int regionIdx;
        boolean regionWalked;
        int regionWait;
        int unloadedCells;
        /** The scans of this round in sweep order; null until planned. */
        Deque<ContainerVisit> scanQueue;
        /**
         * The far containers of this round ({@code build.nearRecheckAll}): planned and scanned after the near ones, in a
         * sweep of their own; null = none waiting.
         */
        List<Pos> scanLater;
        /** The containers planned again this round after they came into view ({@link #replanUnplanned}). */
        Set<Pos> replannedFor;
        int replans;
        int scanFailed;
        final Set<Pos> scannedContainers = new HashSet<>();
        int staleSkipped;
        Set<Pos> extraScan;
        boolean relooked;
        boolean stockSaved;
        boolean scanDone;
        /** STOCK_CHECK: this context is the waiting tile's look at the chests ({@link BuildEngine#awaitMaterials}). */
        boolean materialsLook;
        // restock
        List<Lane> lanes;
        /** {@link #lanes} without the cells left to VERIFY: what the load is planned for and BUILD runs. */
        List<Lane> buildable;
        Map<String, Integer> needs;
        /** What this RESTOCK keeps of the materials: {@link #needs} less the partial stacks the load has no use for. */
        Map<String, Integer> keep;
        /** What this RESTOCK may get rid of (materials only). */
        Leftovers leftovers;
        boolean depositDone;
        Deque<Withdrawal> withdrawals;
        final Map<String, Integer> unavailable = new TreeMap<>();
        /** Items a withdrawal found missing although the stock index promised them. */
        final Set<String> restockShort = new LinkedHashSet<>();
        /** The re-look for {@link #restockShort} was started (at most once per RESTOCK) ... */
        boolean restockRelooked;
        /** ... and its scans are still running (the load is planned again after them). */
        boolean restockRelook;
        int lanesCovered;
        /** {@link #lanesCovered} plus a partially loaded next lane. */
        int lanesLoaded;
        /** The load of this RESTOCK (null until planned; planned again after a re-look). */
        RestockPlanner.Plan loadPlan;
        /** The planner's answer before the unused-materials check ({@link RestockLoads#choose}); null until it is back. */
        RestockLoads.Choice loadPlans;
        /** Carried materials the load did not use were returned once this RESTOCK (never twice). */
        boolean declogged;
        /** The elytra kit was looked at this RESTOCK ({@code build.elytraLegs}, {@link #flightKit}). */
        boolean kitDone;
        /** {@link #flightKit}: the visits to the rocket chest are over this RESTOCK (the way back may still run). */
        boolean kitVisited;
        /**
         * {@code build.elytraKitFarTrip}: the trip to a far rocket chest is under way; the ride's openings are closed for
         * it ({@link #updateOpenings}; at once, or from the foot on when the trip starts on the ride's ways, and for the
         * way back); it gave up on the way there; when it began (ms; -1: no trip).
         */
        boolean kitFar;
        boolean kitFarClosed;
        boolean kitFarFailed;
        long kitFarSince = -1;
        /**
         * The crowded check planned before the leftovers drop, on the inventory as that drop leaves it
         * ({@code build.mergeDrops}): what crowds the load goes with the leftovers. Null until it is back.
         */
        RestockLoads.Choice preDropPlans;
        /** The same load planned with the leftovers carried ({@code build.lazyDrop}); null until it is back. */
        RestockLoads.Choice noDropPlans;
        /** The leftovers drop of this RESTOCK was started (the crowded check before it is not planned again). */
        boolean dropStarted;
        /** Held materials and free slots the load was planned with (the crowded-inventory check). */
        Map<String, Integer> plannedHeld;
        int plannedFree;
        int withdrawn;
        /** Far-storage cells left out of {@link #buildable} this RESTOCK ({@code build.farTripMinCells}); null = none. */
        FarStorage.LeftOut farLeftOut;
        /** Loads planned again this RESTOCK without a few far cells ({@link BuildEngine#fewFarCells}). */
        int farReplans;
        /** The batching planner of {@link #loadPlans} ({@code build.farStorageBatching}); null = not batching. */
        FarStorage.Batching farBatching;
        /** What {@link #loadPlan} takes along from far storage for later lanes; null = no batch. */
        FarStorage.Batch farBatch;
        /** Items this load fills from far storage → what the near chests still hold of them ({@code build.farLoadFill}). */
        final Map<String, Integer> fillLeftNear = new TreeMap<>();
        // build
        long placedAtStart = -1;
        int laneIdx;
        int lanesRun;
        int lanesSkipped;
        /** A lane was run without all of its items (partially loaded): the round ends in RESTOCK, not VERIFY. */
        boolean ranShort;
        /** Cells handed to VERIFY this round ({@link BuildEngine#deferred}). */
        int deferred;
        /** Lanes run this round that skipped cells because their item was not carried, and those cells. */
        final Set<Integer> lanesShort = new HashSet<>();
        int cellsWithoutItems;
        // verify
        boolean passCounted;
        int breakRounds;
        /** VERIFY: the wrong blocks left after the break rounds wait for the next pass; the missing cells go now. */
        boolean wrongWait;
        Deque<Lane> repairLanes;
        // rename / store
        int bottlesThrown;
        /** RENAME: what the XP fetches found when no bottle came. */
        final XpFetchLog xpFetch = new XpFetchLog();
        boolean rendered;
        /** STORE: the locked map's pixels, read before the deposit, for the "Map done" render; null = none. */
        byte[] finalPixels;
        int storedMoved;
        /** STORE: the last deposit's problem and whether it saw the chest without a free slot. */
        String storeNote;
        boolean storeSeenFull;
        /** STORE: what went wrong at each output chest tried, and how many of them were full. */
        final List<String> storeProblems = new ArrayList<>();
        int storeFull;
        /** STORE: the last output chest whose deposit left the map showing in the inventory (not full), and that map. */
        @Nullable Pos storeLastChest;
        int storeLastId;
        /** STORE: such a deposit was looked at again once (the same chest tried once more). */
        boolean storeRechecked;
        // working items put back (tidyWorkingItems: STORE, RESTOCK)
        /** Per item ({@code FILLED_MAP} for the stale maps): index of the chest of its kind to try next. */
        final Map<String, Integer> tidyChest = new HashMap<>();
        /** Stale maps no scrap chest took: left in the inventory. */
        final Set<Integer> scrapSkipped = new HashSet<>();
        /** What went back, item id → count, and since when (clock millis; -1 = nothing yet). */
        final Map<String, Integer> tidied = new TreeMap<>();
        long tidySince = -1;
        boolean tidyLogged;
        // nuke / clear
        boolean toggled;
        int nukeRounds;
        /** {@link BuildEngine#mendsStarted} at this NUKE's last pause at a lane end; -1 = none yet. */
        int pauseMends = -1;
        /** NUKE: tool ids whose due mend was logged as waiting for the clear ({@link BuildEngine#mendWaitsForClear}). */
        final Set<String> mendDeferred = new HashSet<>();
        /** NUKE: a break task of this NUKE ran ({@link BuildEngine#runBreak}): its counts are this NUKE's. */
        boolean nukeTaskSeen;
        /** WAIT_CLEAR: the presence line was logged ({@link ClearPresence}). */
        boolean presenceLogged;
        /** STORE: XP bottles for the next NUKE ({@link BuildEngine#nukeBottles}); -1 = not worked out yet. */
        int nukeBottles = -1;
        int nukeBlocks;
        /** STORE: XP chests tried for the NUKE's bottles. */
        int nukeXpTries;
        boolean counted;
        List<ActuatorCfg> clearActs;
        int actuator;
        int leverStage;
        /** CLEAR: a press of this clear went out (or its lever was on): the clear runs, a restart warning lets it finish. */
        boolean pressSent;
        /** WAIT_CLEAR: the item collection run-on, from the moment the art layer first read clear; null = not yet. */
        CollectionWait collecting;
        /** WAIT_CLEAR: the layer read clear inside the restart hold: no run-on, the carts go off and the bot leaves. */
        boolean runOnCut;
        /** FILL_MAP: clock time the eyeblossom wait began; -1 = not waiting. */
        long blossomWaitSince = -1;
        /** COUNT: clock time the map counter button was first seen still pressed; -1 = not waiting. */
        long counterWaitSince = -1;
        /** COUNT: the login wait was logged. */
        boolean counterLoginLogged;
        /** COUNT: the jam check after the press; null = no press yet (or no check). */
        CounterCheck counterCheck;
        /** COUNT: clock time a piston was first seen stuck out before the press; -1 = none. */
        long counterStuckSince = -1;

        /** The last attempt counted in the running step: the task the step starts is tied to it ({@link #run}). */
        String lastAttempt;

        /** Returns how often {@code key} was attempted before, and counts this attempt. */
        int attempt(String key) {
            lastAttempt = key;
            return attempts.merge(key, 1, Integer::sum) - 1;
        }

        void resetAttempt(String key) {
            attempts.remove(key);
        }

        /** Uncounts one attempt of {@code key}: its task was cut short before it ended. */
        void giveBack(String key) {
            attempts.computeIfPresent(key, (k, n) -> n > 1 ? n - 1 : null);
        }
    }
}
