package io.github.digitalisp.mapart.testutil;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.PlacementListener;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.impl.AdaptivePacer;
import io.github.digitalisp.mapart.bot.impl.AirPlace;
import io.github.digitalisp.mapart.bot.impl.AirPlaceGuard;
import io.github.digitalisp.mapart.bot.impl.LaneHotbar;
import io.github.digitalisp.mapart.bot.impl.Aim;
import io.github.digitalisp.mapart.bot.impl.GoalNearXZ;
import io.github.digitalisp.mapart.bot.impl.LaneRun;
import io.github.digitalisp.mapart.bot.impl.PlacePipeline;
import io.github.digitalisp.mapart.bot.impl.TopFaceRules;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A flat map-art platform for {@link LaneRun} without ZenithProxy: the floor (y = artY − 1) under the tile, the
 * noobline (full blocks on the row north of it at art level), and the art layer with each block's collision and
 * outline shape. The player has a 0.6 × 1.8 box, walks with vanilla-like acceleration and friction, is stopped by full
 * blocks, jumps (0.42 up, gravity 0.08) and falls; its eye is 1.62 above the feet. Aiming is a real raycast against the
 * outline shapes; the server side refuses a placement into an occupied cell or into the player's box, and confirms
 * accepted ones after {@link #confirmDelay} ticks. Every click takes the next block prediction sequence, and the
 * server acknowledges it ({@code ackedSequence}) {@link #confirmDelay} ticks later, after the block (if placed) shows,
 * like vanilla's answer to a use-item-on. Baritone is a shortest path over the cells (a step up costs a jump)
 * that takes walking time and leaves the bot up to 0.2 off the goal cell's centre.
 *
 * <p>With {@link #echoes} the run watches the server's block updates: like vanilla, every click the "server" handles
 * is answered with a block update for the clicked block and the cell next to the clicked face (the clicked cell and the
 * one above it for an airplace) before its ack. {@link #limiter} drops a click before it is handled (Paper's click
 * limiter: no block update, no ack of its own; a later click's ack covers its sequence), {@link #refuse} has the
 * "server" handle and refuse it, {@link #anticheat} cancels it in front of the server (Grim: an ack of exactly its
 * sequence, no block update), {@link #unanswered} has it handled without block updates and acked only up to a higher
 * sequence (vanilla: out of interaction range, and not the last click of its server tick).
 *
 * <p>Airplace ({@link #airPlace}) places into the cell itself with no rotation and no line of sight; the "server"
 * refuses it beyond {@link #airServerRange} of the eye (to the cell centre). With {@link #silent} the looks are silent:
 * the real rotation stays and a click is checked against the last silent rotation (the printer's grimRotate).
 *
 * <p><b>The server rejects movement</b>. Two worlds: the proxy's view (what
 * {@code block}, {@code blocksPlacement}, the bot's own physics and the Baritone stand-in see) and the server's (what
 * decides the bot's movement and the placements). {@link #ghost} puts a full block only the proxy shows (a rejected
 * placement it kept), {@link #hiddenBlock} one only the server has. While either is set, the "server" moves the bot
 * itself every tick with the same keys from its own state; a claimed position it does not reach is set back to the last
 * accepted one (the bot stops dead: {@link #setbacks}); a Baritone path that walks into a server-only block or onto a
 * ghost ends set back in front of it and stalls ({@link #stallTicks}), {@link #STALL_RETRIES} retries, then FAILS like
 * the Walker. After a setback the server resends the blocks around the bot ({@link #resyncOnSetback}), as 2b2t does.
 * {@link #frozen}: the server accepts no movement at all. Without any of these the movement is exactly as before.
 * {@link #maxFeetY} is the highest the feet got (Baritone paths included), {@link #narrowStandTicks} the ticks the bot
 * stood still in a floor cell with two or more full-block sides. Stand-spot walks ({@code walk} with {@code noClimb})
 * are checked like PlaceLaneTask's: a path that climbs inside the tile, or one past an {@link #avoid} cell, is refused
 * ({@link LaneRun#pathRefusal}).
 *
 * <p>Per tick: {@link #beginTick}, then {@code LaneRun.tick}, then {@link #endTick} (the movement packet).
 */
public final class LaneSim implements LaneRun.Io<LaneSim.Shot> {
    public static final double WALK = 0.2158;

    /**
     * A rotation and the support block it was aimed at; {@code side}: the neighbour of the cell (support + 1) whose side
     * face it hits instead (a side-face LOOK click, {@link LaneRun.Io#aimSide}), null for the support's top face.
     */
    public record Shot(float yaw, float pitch, Pos support, @Nullable Pos side) implements PlacePipeline.Rotation {
        public Shot(float yaw, float pitch, Pos support) {
            this(yaw, pitch, support, null);
        }
    }

    /** Art-layer blocks (y = artY), set by placements. */
    public interface Blocks {
        BlockSpec get(Pos p);

        void set(Pos p, BlockSpec b);
    }

    /** Inventory counts; {@code take} removes one. */
    public interface Items {
        int count(String item);

        boolean take(String item);
    }

    private final Blocks blocks;
    private final Items items;
    public final int artY;
    public final int minX;
    public final int minZ;
    public final int size;
    public final AdaptivePacer pacer;
    private final Random rnd;

    /** Placements refused by the "server" at this rate (answered and acked without the block). */
    public double missRate;
    public int confirmDelay = 2;
    /** Block prediction sequence of the last click, and the highest one the server acknowledged. */
    private int sequence;
    private int ackedSequence = -1;
    public boolean noobline = true;
    public BlockSpec platformBlock = BlockSpec.of("minecraft:moss_block");
    /** Airplace allowed ({@link LaneRun.Io#airPlaceReady}). */
    public boolean airPlaceReady = true;
    /** Looks are sent silently: the real rotation never changes; clicks are checked against {@link #silentYaw}. */
    public boolean silent;
    /**
     * Block ids Baritone neither walks through nor onto, and the run may not stand on (pointed dripstone, pots:
     * ZenithProxy's MovementHelper; {@link #standOnTop}); none by default (every block a full cube, as before).
     */
    public java.util.Set<String> wallIds = java.util.Set.of();
    /**
     * The {@link #wallIds} blocks collide as a thin column, x and z in [5/16, 11/16] of their cell (pointed dripstone
     * without its random offset): the box goes partly into their cell. False (default): a full cell, as before.
     */
    public boolean thinWalls;
    /** The thin columns' offset along x and z (vanilla: random per cell, up to ±0.125). */
    public double thinWallOffset;
    /** Velocity a tick of movement keys adds on the ground: 0.098 walking; Soul Speed on soul sand (0.49 a tick) 0.2225. */
    public double groundAccel = 0.098;
    /** {@link LaneRun.Io#wallBrake} ({@code build.laneWallBrake}). */
    public boolean wallBrake = true;
    /** {@link LaneRun.Io#stepBackClearPath} ({@code build.laneStepBackClearPath}). */
    public boolean stepBackClearPath;
    /** {@link LaneRun.Io#forwardClearPath} ({@code build.laneForwardClearPath}, default true). */
    public boolean forwardClearPath = true;
    /** {@link LaneRun.Io#startOnFloor} ({@code build.laneStartOnFloor}). */
    public boolean startOnFloor;
    /** {@link LaneRun.Io#startTail} ({@code build.laneStartTail}). */
    public boolean startTail;
    /**
     * A second level one block above the art layer (docs/COLOR_SUPPRESSION.md): blocks
     * at artY + 1 are part of the world ({@link #block}, the server's placements, collision, the raycast, Baritone's
     * cells). A raised block over air is a wall at head height for a box on the floor (it can neither pass under it nor
     * climb it: two high) and one step up from on top of an art-layer block; its top is a cell Baritone may stand on.
     * False (default): the world above the art layer is air, as before.
     */
    public boolean raisedLevel;
    /** {@link LaneRun.Io#raisedLevel} ({@code build.suppressionMergeLevels}): the run is told about the raised level. */
    public boolean raisedRun;
    /** Ticks the bot stood (its own movement) on top of a raised block, and stand spots on one (raised level). */
    public int raisedStands;
    public int raisedSpots;
    /** Eye → cell centre beyond which the "server" refuses an airplace. */
    public double airServerRange = 5.0;
    /** The run watches block updates ({@link LaneRun.Io#echoMark}); false: acks alone, as before. */
    public boolean echoes;
    /** Clicks (by target cell) the server's click limiter drops: never handled, never answered or acked themselves. */
    public Predicate<Pos> limiter = p -> false;
    /** Clicks (by target cell) the "server" handles and refuses: block updates and ack, no block. */
    public Predicate<Pos> refuse = p -> false;
    /** Clicks (by target cell) an anti-cheat cancels in front of the server: an ack of exactly their sequence only. */
    public Predicate<Pos> anticheat = p -> false;
    /** Clicks (by target cell) handled without block updates, acked only up to a higher sequence: look dropped. */
    public Predicate<Pos> unanswered = p -> false;
    private long echoCount;
    private final Map<Pos, Long> echoAt = new HashMap<>();
    private final java.util.Set<Integer> exactAcks = new java.util.HashSet<>();

    // player
    public double x;
    public double y;
    public double z;
    private float yaw;
    private float pitch = 60;
    private double vx;
    private double vz;
    private double vy;
    private boolean onGround = true;
    private boolean hCollision;

    // this tick's input
    private long now;
    private boolean submitted;
    private boolean prevSubmitted;
    private @Nullable Float reqYaw;
    private @Nullable Float reqPitch;
    private @Nullable Float silentReqYaw;
    private @Nullable Float silentReqPitch;
    /** The last silent look the "server" saw (null: none yet). */
    private @Nullable Float silentYaw;
    private @Nullable Float silentPitch;
    private int placementsThisTick;
    private int fwd;
    private int strafe;
    private boolean jump;

    // hotbar: slot 0 food, 1-8 materials
    private final String[] hotbar = new String[9];
    private int held = 1;
    private int roundRobin;
    /**
     * Stack mode (0 = off: one stack per item, its count the item's total). Above 0 every hotbar slot holds its own stack
     * of at most this many items; a lane begins with {@link #hotbarStacksPerItem} stacks of each of its items in the
     * hotbar (as stock allows), the rest is in the main inventory; a click uses the held slot's stack, and
     * {@link #ensureHeld} moves a stack from the main inventory into a slot (an empty one first).
     */
    public int stackSize;
    public int hotbarStacksPerItem = 1;
    /** Stack mode: the server's count per hotbar slot, and what the cache shows ({@link #countLag} ticks late). */
    private final int[] slotCount = new int[9];
    private final java.util.ArrayDeque<int[]> slotHistory = new java.util.ArrayDeque<>();
    /** Stack mode: moves from the main inventory ({@link #ensureHeld}). */
    public int stackMoves;

    // lane
    private final Map<Pos, BlockSpec> targets = new HashMap<>();
    private @Nullable PlacementListener listener;
    private final List<long[]> dueAt = new ArrayList<>();
    private final List<Runnable> due = new ArrayList<>();

    // Baritone
    private @Nullable List<GoalNearXZ> walkGoals;
    private int walkGoal;
    private int walkTicksLeft;
    private double @Nullable [] walkDest;
    private String walkError = "";

    // statistics
    public double walked;
    public int jumps;
    public int baritoneWalks;
    public double baritoneDistance;
    public int clicks;
    public record SentClick(long tick, Pos cell) { }
    public final List<SentClick> sentClicks = new ArrayList<>();
    public record TimedClick(long sendMillis, long arrivalMillis, int sequence, int slot, Pos cell) { }
    public final List<TimedClick> timedClicks = new ArrayList<>();
    public boolean millisecondArrivals;
    public int networkLatencyMillis = 50;
    public java.util.function.IntUnaryOperator clickJitterMillis = seq -> 0;
    public boolean dispatchReady = true;
    public boolean subTickEnabled = true;
    public boolean failSwing;
    public boolean containerOpen;
    public boolean requestBusy;
    public record Selection(long sendMillis, int slot) { }
    public final List<Selection> selections = new ArrayList<>();
    public record WireEvent(long millis, String kind, int slot, int sequence) { }
    public final List<WireEvent> wireEvents = new ArrayList<>();
    public boolean hasScheduledClick() { return timers.stream().anyMatch(t -> !t.cancelled); }
    public int dispatchDelayMillis;
    private long clockNanos;
    private final List<Timer> timers = new ArrayList<>();
    private static final class Timer {
        long at;
        Runnable send;
        boolean cancelled;
        Timer(long at, Runnable send) { this.at = at; this.send = send; }
    }

    @Override public long nanoTime() { return clockNanos; }
    @Override public boolean subTickAvailable() { return subTickEnabled; }
    @Override public int airPlacesSent() { return airPlacer.placed(); }
    @Override public boolean subTickReady() { return dispatchReady && submitted && !containerOpen && !requestBusy; }
    @Override public Runnable scheduleClick(long at, Runnable send) {
        Timer timer = new Timer(at + dispatchDelayMillis * 1_000_000L, send);
        timers.add(timer);
        return () -> timer.cancelled = true;
    }

    /** Run the same-loop timers between complete movement ticks, in deadline order. */
    public void advanceMillis(long millis) {
        while (true) {
            Timer next = timers.stream().filter(t -> !t.cancelled && t.at <= millis * 1_000_000L)
                .min(java.util.Comparator.comparingLong(t -> t.at)).orElse(null);
            if (next == null) break;
            timers.remove(next);
            clockNanos = Math.max(clockNanos, next.at);
            next.send.run();
        }
        timers.removeIf(t -> t.cancelled);
        clockNanos = Math.max(clockNanos, millis * 1_000_000L);
    }
    /** Airplaces sent (also counted in {@link #clicks}). */
    public int airPlaces;
    /**
     * Side-face clicks sent (also counted in {@link #clicks}), the least pitch among them, and those the "server" (as
     * vanilla and Grim) would not take: the clicked block not a full block, the eye on the inner side of the clicked
     * face (Grim PositionPlace), the look not pitched down, or the ray of the click's rotation not on that face.
     */
    public int sideClicks;
    public float minSidePitch = Float.POSITIVE_INFINITY;
    public int badSideClicks;
    /** Airplaces the "server" refused as too far. */
    public int farRejects;
    /** Rotation requests that changed the real rotation, and silent looks sent. */
    public int realTurns;
    public int silentLooks;
    /** Most placements (clicks + airplaces) sent in one tick (must stay 1). */
    public int maxPlacementsPerTick;
    public int accepted;
    public int occupiedRejects;
    public int boxRejects;
    public int dropped;
    /**
     * Placements the server accepted whose block the proxy does not show yet (the block update still on its way), and
     * the movement ticks whose box went into the collision of one of them that the box was not in the tick before:
     * vanilla's {@code isPlayerCollidingWithAnythingNew} answers such a move with a teleport back (live 2026-10-23, the
     * first teleport of the step-back setback bursts). Counted only; the movement is not changed. The tick of each.
     */
    private final Map<Pos, BlockSpec> inFlight = new HashMap<>();
    private final Map<Pos, Long> inFlightAt = new HashMap<>();
    public int inFlightEntries;
    public final List<Long> inFlightEntryTicks = new ArrayList<>();
    public final List<String> inFlightHits = new ArrayList<>();
    /**
     * Moves whose box newly went (in x and z, at the art layer) into a cell beside the box's own centre cell (a side
     * column the box pokes into, riding off the line) while that cell's accepted block was in flight, or shown in the
     * same tick ({@link #RACE_TICKS} ticks before): the live 2026-10-27 race, the block update 1-56 ms ahead of the move that
     * enters the cell (Paper's isPlayerCollidingWithAnythingNew, then Grim's setbacks). Counted only.
     */
    public int sideRaceEntries;
    public final List<String> sideRaceHits = new ArrayList<>();
    public static final int RACE_TICKS = 0;
    private final Map<Pos, Long> shownAt = new HashMap<>();
    /**
     * Paper's click limiter in ticks (0 = off; ServerGamePacketListenerImpl.checkLimit): a
     * window starts at the first click after the last one ended and lasts this many ticks; its clicks after the
     * {@link #paperWindowMax}th are dropped as {@link #limiter} drops them (never handled, never answered or acked).
     */
    public int paperWindowTicks;
    public int paperWindowMax = 9;
    /** Arrival jitter per sequence, 0..2 ticks in adaptive-window tests. FIFO preserves TCP packet order. */
    public java.util.function.IntUnaryOperator clickJitterTicks = seq -> 0;
    private long lastArrivalMillis = -1;
    private int responseDelay;
    public final int[] jitterCounts = new int[3];
    private long paperWindowStart = -1;
    private int paperWindowCount;
    /** Clicks the {@link #paperWindowTicks} limiter dropped. */
    public int paperDrops;
    /** Most clicks in any {@link #slidingTicks} ticks in a row (fast placement's own window). */
    public int slidingTicks = 6;
    public int maxPerSlidingWindow;
    private final java.util.ArrayDeque<Long> clickTicks = new java.util.ArrayDeque<>();
    /** Ticks with two or more clicks; most full blocks (collision taller than a step) placed in one tick. */
    public int multiClickTicks;
    public int maxSolidsPerTick;
    private int solidsThisTick;
    /** Clicks: dropped by {@link #limiter}, refused ({@link #refuse}), cancelled ({@link #anticheat}), unanswered. */
    public int limiterDrops;
    public int refusals;
    public int anticheatCancels;
    public int unansweredClicks;
    /** Clicks with no target there or the wrong item in hand (the lane logic must never do this). */
    public int wrongClicks;
    public int pocketTicks;
    public int fellOff;
    public final Map<String, Integer> skipReasons = new TreeMap<>();
    public final List<String> log = new ArrayList<>();
    /** Called when the server accepts a placement (before it shows in the world). */
    public @Nullable Consumer<Placement> onAccept;
    /** Feet cells a stand spot may use ({@link LaneRun.Io#standAllowed}, e.g. outside the no-go zones). */
    public Predicate<Pos> standCells = p -> true;
    /** Stand spots walked to (goals of range 0 only, the last one: {@code LaneRun}'s SPOT walks), in order. */
    public final List<Pos> spotGoals = new ArrayList<>();
    /** Setback cells ({@code PlaceLaneTask}'s {@code avoid}): no stand cell next to them, no stand-spot walk past them. */
    public final java.util.Set<Pos> avoid = new java.util.HashSet<>();

    // the server rejects movement (class javadoc)
    /** Art-layer blocks only the proxy shows (a rejected placement it kept), and blocks only the server has. */
    private final Map<Pos, BlockSpec> ghosts = new HashMap<>();
    private final Map<Pos, BlockSpec> hidden = new HashMap<>();
    /** The server accepts no movement at all: every move is set back, every Baritone path stalls at once. */
    public boolean frozen;
    /**
     * After a setback the server resends the blocks around the bot (2b2t's Grim: a 3x4x5 box), so the proxy learns a
     * {@link #hiddenBlock} and drops a {@link #ghost} within {@link #RESYNC} cells of the bot's feet. Default on.
     */
    public boolean resyncOnSetback = true;
    public static final int RESYNC = 2;
    public int resyncedCells;
    /** Baritone retries after a stalled path before the walk FAILS (the Walker's MAX_FAILURES). */
    public static final int STALL_RETRIES = 3;
    /** Ticks a path set back in front of a cell the server does not have as planned takes before it counts as stalled. */
    public int stallTicks = 200;
    /** The server's own state of the bot (moved with the same keys every tick while a ghost or hidden block is set). */
    private double srvX;
    private double srvY;
    private double srvZ;
    private double srvVx;
    private double srvVy;
    private double srvVz;
    private boolean srvOnGround = true;
    /** Movement ticks the server set back, and Baritone paths that ended set back in front of a cell. */
    public int setbacks;
    public int stalledPaths;
    /** Highest feet height reached (ticks and Baritone paths). */
    public double maxFeetY = Double.NEGATIVE_INFINITY;
    /** Ticks the bot stood still on the floor in a cell with two or more full-block sides, and where (first few). */
    public int narrowStandTicks;
    public final List<String> narrowStands = new ArrayList<>();
    /** Stand-spot walks whose path was refused ({@link LaneRun#pathRefusal}), and the reasons (first few). */
    public int refusedWalks;
    public final List<String> refusedPaths = new ArrayList<>();
    /** The stand-spot walk in progress is checked ({@code walk} with {@code noClimb}); no climbing then. */
    private boolean spotWalk;
    private boolean noClimb;
    private int stalls;
    /** Feet cells of the last route ({@link #route}), the bot's cell first. */
    private List<Pos> routeCells = List.of();
    /** The leg walked now ends set back in front of a cell ({@link #serverBreak}); the highest feet on it. */
    private boolean legStalled;
    private double legMaxY;
    /**
     * The Baritone stand-in moves the bot along its route while it walks (each tick a share of the way), instead of
     * standing it at the start until the leg's time is up: what the in-reach greedy places on the way sees the bot where
     * it is. Off (default): as before.
     */
    public boolean baritoneMoves;
    /** Ticks of the leg walked now, the bot's feet when it began, and how far along its route the bot is (cells). */
    private int legTicks;
    private double legX;
    private double legY;
    private double legZ;
    private int legIndex;
    /** Legs on whose route ahead a full block appeared while the bot walked it ({@link #baritoneMoves}). */
    public int pathBlocked;
    public final List<String> pathBlocks = new ArrayList<>();
    /** Cells of later lanes the runs placed (the in-reach greedy: {@link LaneRun.Io#placedExtra}). */
    public int extrasPlaced;
    /** Clicks sent while the Baritone stand-in walked (the in-reach greedy). */
    public int walkingClicks;

    public LaneSim(Blocks blocks, Items items, int minX, int minZ, int artY, AdaptivePacer pacer, long seed) {
        this.blocks = blocks;
        this.items = items;
        this.minX = minX;
        this.minZ = minZ;
        this.artY = artY;
        this.size = 128;
        this.pacer = pacer;
        this.rnd = new Random(seed);
    }

    /** Stand area for {@link LaneRun.Params}: the tile. */
    public LaneRun.Area tileArea() {
        return new LaneRun.Area(minX, minZ, minX + size - 1, minZ + size - 1);
    }

    /** Puts the bot at (x, y, z) standing still. */
    public void teleport(double nx, double ny, double nz) {
        x = nx;
        y = ny;
        z = nz;
        vx = vz = vy = 0;
        onGround = true;
        syncServer();
        maxFeetY = Math.max(maxFeetY, ny);
    }

    /** A full block at {@code p} (art layer) that only the proxy shows: the server has air there. */
    public void ghost(Pos p, BlockSpec b) {
        ghosts.put(p, b);
    }

    /** A full block at {@code p} (art layer) that only the server has: the proxy shows air there. */
    public void hiddenBlock(Pos p, BlockSpec b) {
        hidden.put(p, b);
    }

    /** The server's state of the bot is the bot's (after a teleport, a Baritone path, a setback). */
    private void syncServer() {
        srvX = x;
        srvY = y;
        srvZ = z;
        srvVx = vx;
        srvVy = vy;
        srvVz = vz;
        srvOnGround = onGround;
    }

    /** Proxy and server disagree somewhere (or nothing moves): the server checks the bot's movement. */
    private boolean overlay() {
        return frozen || !ghosts.isEmpty() || !hidden.isEmpty();
    }

    /** A setback: counted, the trail marked, the blocks around the bot resent ({@link #resyncOnSetback}). */
    private void setback(String what) {
        setbacks++;
        trail.addLast("t" + now + " SETBACK " + what);
        if (!resyncOnSetback) return;
        int fx = (int) Math.floor(x);
        int fz = (int) Math.floor(z);
        for (var it = hidden.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (Math.abs(e.getKey().x() - fx) <= RESYNC && Math.abs(e.getKey().z() - fz) <= RESYNC) {
                blocks.set(e.getKey(), e.getValue());
                it.remove();
                resyncedCells++;
            }
        }
        int before = ghosts.size();
        ghosts.keySet().removeIf(p -> Math.abs(p.x() - fx) <= RESYNC && Math.abs(p.z() - fz) <= RESYNC);
        resyncedCells += before - ghosts.size();
    }

    /** The cells of {@code load} are targets too (the in-reach greedy places them from this lane's run). */
    public void addTargets(List<Lane> load) {
        for (Lane l : load) for (Placement p : l.placements()) targets.put(p.pos(), p.block());
    }

    /** Starts a lane: its targets are what the "server" places for the matching item. */
    public void begin(Lane lane, PlacementListener l) {
        // Standalone runs and engine tasks both restart their lane clocks.
        lastArrivalMillis = paperWindowStart = -1;
        paperWindowCount = 0;
        listener = l;
        targets.clear();
        noClickUntil = Long.MIN_VALUE; // runLane counts its ticks from 1 again
        keysLastTick = false;
        for (Placement p : lane.placements()) targets.put(p.pos(), p.block());
        walkGoals = null;
        if (stackSize > 0 && hotbar[1] == null) {
            int slot = 1;
            for (Placement p : lane.placements()) {
                String item = p.itemId();
                if (Arrays.asList(hotbar).contains(item)) continue;
                for (int k = 0; k < hotbarStacksPerItem && slot <= 8 && mainCount(item) > 0; k++) {
                    hotbar[slot] = item;
                    slotCount[slot] = Math.min(stackSize, mainCount(item));
                    slot++;
                }
                if (slot > 8) break;
            }
            return;
        }
        if (hotbar[1] == null) {
            int slot = 1;
            for (Placement p : lane.placements()) {
                String item = p.itemId();
                if (slot > 8) break;
                if (Arrays.asList(hotbar).contains(item)) continue;
                hotbar[slot++] = item;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- tick protocol

    /** The current tick. */
    public long now() {
        return now;
    }

    public void beginTickAt(long tick, long millis) {
        beginTick(tick);
        clockNanos = millis * 1_000_000L;
    }

    public void beginTick(long tick) {
        now = tick;
        clockNanos = tick * 50_000_000L;
        if (staleMoveReports && now > movedAt && now < fenceUntil) staleReports++;
        recordCounts();
        for (int i = 0; i < due.size(); i++) {
            if (dueAt.get(i)[0] <= now) {
                Runnable r = due.remove(i);
                dueAt.remove(i);
                i--;
                r.run();
            }
        }
        moveStillLastTick = submitted && fwd == 0 && strafe == 0 && !jump;
        prevSubmitted = submitted;
        submitted = false;
        reqYaw = null;
        reqPitch = null;
        silentReqYaw = null;
        silentReqPitch = null;
        placementsThisTick = 0;
        solidsThisTick = 0;
        fwd = 0;
        strafe = 0;
        jump = false;
    }

    /** ZenithProxy's Bot: the movement packet with this tick's rotation and keys. */
    public void endTick() {
        endTickBody();
        wireEvents.add(new WireEvent(clockNanos / 1_000_000L, "move", held, 0));
    }

    private void endTickBody() {
        if ((reqYaw != null && reqYaw != yaw) || (reqPitch != null && reqPitch != pitch)) realTurns++;
        if (reqYaw != null) yaw = reqYaw;
        if (reqPitch != null) pitch = reqPitch;
        if (silentReqYaw != null) {
            silentYaw = silentReqYaw;
            silentPitch = silentReqPitch;
            silentLooks++;
        }
        if (walkGoals != null && !submitted) return; // Baritone owns the movement
        boolean keys = fwd != 0 || strafe != 0;
        if (keys && !keysLastTick) {
            walkStarts++;
            noClickUntil = now + 1 + walkStartClickLoss;
        }
        keysLastTick = keys;
        double[] w = {0, 0};
        if (fwd != 0 || strafe != 0) {
            w = Aim.keysToWorld(yaw, fwd, strafe);
            double l = Math.hypot(w[0], w[1]);
            w[0] /= l;
            w[1] /= l;
        }
        double ox = x;
        double oy = y;
        double oz = z;
        boolean og = onGround;
        double[] st = {x, y, z, vx, vy, vz, onGround ? 1 : 0, 0, 0, 0};
        move(st, w, jump, false);
        x = st[0];
        y = st[1];
        z = st[2];
        vx = st[3];
        vy = st[4];
        vz = st[5];
        onGround = st[6] != 0;
        hCollision = st[7] != 0;
        if (st[8] != 0) jumps++;
        if (st[9] != 0) fellOff++; // walked off the tile (off it already: elsewhere)
        walked += Math.hypot(x - ox, z - oz);
        if (!inFlight.isEmpty()) checkInFlight(ox, oy, oz);
        if (!inFlight.isEmpty() || !shownAt.isEmpty()) checkSideRace(ox, oz);
        if (overlay()) checkMove(w, ox, oy, oz, og);
        else syncServer();
        maxFeetY = Math.max(maxFeetY, y);
        if (raisedLevel && onGround && y >= artY + 2 - 1e-6) raisedStands++;
        if (walkGoals == null && onGround && y < artY + 0.5 && fwd == 0 && strafe == 0 && !jump) {
            int fx = (int) Math.floor(x);
            int fz = (int) Math.floor(z);
            if (walledSides(fx, fz) >= 2) {
                narrowStandTicks++;
                if (narrowStands.size() < 10) {
                    narrowStands.add(fx + "," + fz + " tick " + now + (progress == null ? "" : progress.get()));
                }
            }
        }
        trail.addLast(String.format("t%d %.2f,%.2f,%.2f keys %d/%d j=%b g=%b hc=%b %s", now, x, y, z, fwd, strafe, jump, onGround,
            hCollision, progress == null ? "" : progress.get()));
        if (trail.size() > 40) trail.removeFirst();
        if (onGround && inPocket()) pocket("walk");
    }

    // ---------------------------------------------------------------------------------------------- world

    /** Collision top of a state: full blocks 1, carpets 1/16, one candle 6/16, plates/plants/lichen 0. */
    public static double collisionTopOf(BlockSpec b) {
        if (b == null || b.isAir()) return 0;
        String id = b.id();
        if (id.endsWith("_carpet")) return 1.0 / 16;
        if (isCandle(id)) return 6.0 / 16;
        if (isThin(id)) return 0;
        return 1;
    }

    /** A candle (one: live j0002 places white candles as single candles): collision and outline 2×6×2 pixels. */
    private static boolean isCandle(String id) {
        return id.equals("minecraft:candle") || id.endsWith("_candle");
    }

    private static boolean isThin(String id) {
        return id.endsWith("pressure_plate") || id.endsWith("_roots") || id.endsWith("eyeblossom")
            || id.endsWith("_petals") || id.endsWith("lichen") || id.endsWith("resin_clump") || id.endsWith("short_grass")
            || id.endsWith("dandelion") || id.endsWith("poppy");
    }

    /** Outline (raycast) box in block-local coordinates, or null for air. */
    static double @Nullable [] outline(BlockSpec b) {
        if (b == null || b.isAir()) return null;
        String id = b.id();
        double p = 1.0 / 16;
        if (id.endsWith("_carpet") || id.endsWith("lichen") || id.endsWith("resin_clump")) return new double[]{0, 0, 0, 1, p, 1};
        if (id.endsWith("pressure_plate")) return new double[]{p, 0, p, 1 - p, p, 1 - p};
        if (isCandle(id)) return new double[]{7 * p, 0, 7 * p, 9 * p, 6 * p, 9 * p};
        if (id.endsWith("_petals")) return new double[]{0, 0, 0, 1, 3 * p, 1};
        if (id.endsWith("_roots") || id.endsWith("short_grass")) return new double[]{2 * p, 0, 2 * p, 14 * p, 13 * p, 14 * p};
        if (id.endsWith("eyeblossom") || id.endsWith("dandelion") || id.endsWith("poppy")) {
            return new double[]{5 * p, 0, 5 * p, 11 * p, 10 * p, 11 * p};
        }
        return new double[]{0, 0, 0, 1, 1, 1};
    }

    private boolean inTile(int cx, int cz) {
        return cx >= minX && cx < minX + size && cz >= minZ && cz < minZ + size;
    }

    /** Block at art level as the proxy shows it, including the noobline (and a {@link #ghost}). */
    private BlockSpec art(int cx, int cz) {
        if (!ghosts.isEmpty()) {
            BlockSpec g = ghosts.get(new Pos(cx, artY, cz));
            if (g != null) return g;
        }
        return worldArt(cx, cz);
    }

    /** Block at art level as the server has it (a {@link #hiddenBlock}, no ghost). */
    private BlockSpec serverArt(int cx, int cz) {
        if (!hidden.isEmpty()) {
            BlockSpec h = hidden.get(new Pos(cx, artY, cz));
            if (h != null) return h;
        }
        return worldArt(cx, cz);
    }

    private BlockSpec worldArt(int cx, int cz) {
        if (noobline && cz == minZ - 1 && cx >= minX && cx < minX + size) return BlockSpec.of("minecraft:cobblestone");
        return blocks.get(new Pos(cx, artY, cz));
    }

    private boolean solidAt(int cx, int cz) {
        return collisionTopOf(art(cx, cz)) > 0.6;
    }

    /** {@link #raisedLevel}: the block one above the art layer (air without the level). */
    private BlockSpec raised(int cx, int cz) {
        if (!raisedLevel) return BlockSpec.AIR;
        BlockSpec b = blocks.get(new Pos(cx, artY + 1, cz));
        return b == null ? BlockSpec.AIR : b;
    }

    /** A raised block with collision: a wall at head height for a box on the floor. */
    private boolean raisedSolid(int cx, int cz) {
        return raisedLevel && collisionTopOf(raised(cx, cz)) > 0;
    }

    /** A wall for a box on the floor: a full block at art level or a raised block. */
    private boolean wallAt(int cx, int cz) {
        return solidAt(cx, cz) || raisedSolid(cx, cz);
    }

    /** The box at feet height {@code py} overlaps a raised block ({@link #raisedLevel}). */
    private boolean collidesRaised(double px, double py, double pz) {
        if (py >= artY + 2 - 1e-9 || py + 1.8 <= artY + 1 + 1e-9) return false;
        for (int cx = (int) Math.floor(px - 0.3 + 1e-9); cx <= (int) Math.floor(px + 0.3 - 1e-9); cx++) {
            for (int cz = (int) Math.floor(pz - 0.3 + 1e-9); cz <= (int) Math.floor(pz + 0.3 - 1e-9); cz++) {
                if (raisedSolid(cx, cz)) return true;
            }
        }
        return false;
    }

    /** A full block at art level in the server's world ({@code server}) or the proxy's. */
    private boolean solidAt(int cx, int cz, boolean server) {
        return collisionTopOf(server ? serverArt(cx, cz) : art(cx, cz)) > 0.6;
    }

    /** Full blocks among the four neighbours at art level. */
    public int walledSides(int cx, int cz) {
        int n = 0;
        if (wallAt(cx + 1, cz)) n++;
        if (wallAt(cx - 1, cz)) n++;
        if (wallAt(cx, cz + 1)) n++;
        if (wallAt(cx, cz - 1)) n++;
        return n;
    }

    /** This tick's move went into the collision of an accepted block the proxy does not show yet ({@link #inFlight}). */
    private void checkInFlight(double ox, double oy, double oz) {
        for (Map.Entry<Pos, BlockSpec> e : inFlight.entrySet()) {
            double top = collisionTopOf(e.getValue());
            if (top <= 0) continue;
            Pos c = e.getKey();
            if (!boxInBlock(x, y, z, c, top) || boxInBlock(ox, oy, oz, c, top)) continue;
            inFlightEntries++;
            inFlightEntryTicks.add(now);
            if (inFlightHits.size() < 20) {
                inFlightHits.add(String.format("t%d into %d,%d %s (clicked t%d) from %.2f,%.2f to %.2f,%.2f%s", now, c.x(),
                    c.z(), e.getValue().id(), inFlightAt.getOrDefault(c, -1L), ox, oz, x, z,
                    progress == null ? "" : progress.get()));
            }
        }
    }

    /** {@link #sideRaceEntries}: this tick's move went into a side cell whose block is in flight or just shown. */
    private void checkSideRace(double ox, double oz) {
        shownAt.values().removeIf(t -> now - t > RACE_TICKS);
        java.util.Set<Pos> cells = new java.util.HashSet<>(inFlight.keySet());
        for (Map.Entry<Pos, BlockSpec> e : inFlight.entrySet()) if (collisionTopOf(e.getValue()) <= 0) cells.remove(e.getKey());
        cells.addAll(shownAt.keySet());
        int fx = (int) Math.floor(x);
        int fz = (int) Math.floor(z);
        for (Pos c : cells) {
            if (c.y() != artY || (c.x() == fx && c.z() == fz)) continue;
            if (!footIn(x, z, c) || footIn(ox, oz, c) || y >= c.y() + 1) continue;
            sideRaceEntries++;
            if (sideRaceHits.size() < 20) {
                sideRaceHits.add(String.format("t%d into %d,%d (%s) from %.2f,%.2f to %.2f,%.2f", now, c.x(), c.z(),
                    inFlight.containsKey(c) ? "in flight" : "shown t" + shownAt.get(c), ox, oz, x, z));
            }
        }
    }

    /** The player's box at (px, pz), deflated by 1e-5, overlaps cell {@code c} in x and z. */
    private static boolean footIn(double px, double pz, Pos c) {
        double d = 1e-5;
        return px + 0.3 - d > c.x() && px - 0.3 + d < c.x() + 1 && pz + 0.3 - d > c.z() && pz - 0.3 + d < c.z() + 1;
    }

    /** The player's box at feet (px, py, pz), deflated by 1e-5 as vanilla's check, in a block of height {@code top}. */
    private static boolean boxInBlock(double px, double py, double pz, Pos c, double top) {
        double d = 1e-5;
        return px + 0.3 - d > c.x() && px - 0.3 + d < c.x() + 1 && pz + 0.3 - d > c.z() && pz - 0.3 + d < c.z() + 1
            && py + d < c.y() + top && py + 1.8 - d > c.y();
    }

    private boolean collides(double px, double py, double pz, boolean server) {
        if (raisedLevel && collidesRaised(px, py, pz)) return true;
        if (py >= artY + 1 - 1e-9) return false;
        for (int cx = (int) Math.floor(px - 0.3 + 1e-9); cx <= (int) Math.floor(px + 0.3 - 1e-9); cx++) {
            for (int cz = (int) Math.floor(pz - 0.3 + 1e-9); cz <= (int) Math.floor(pz + 0.3 - 1e-9); cz++) {
                if (solidAt(cx, cz, server) && (!thin(cx, cz, server) || inColumn(px, pz, cx + thinWallOffset,
                    cz + thinWallOffset))) return true;
            }
        }
        return false;
    }

    /**
     * With {@link #thinWalls}: how far the box moves along {@code d} on axis x (0) or z (2) before it touches what it
     * collides with (vanilla moves up to the face); 0 without (the move is dropped, as before).
     */
    private double contact(double[] st, int axis, double d, boolean server) {
        if (!thinWalls) return 0;
        double lo = 0;
        double hi = 1;
        for (int i = 0; i < 30; i++) {
            double mid = (lo + hi) / 2;
            if (collides(axis == 0 ? st[0] + d * mid : st[0], st[1], axis == 2 ? st[2] + d * mid : st[2], server)) hi = mid;
            else lo = mid;
        }
        return d * lo;
    }

    /** A {@link #thinWalls} column at art level in cell (cx, cz). */
    private boolean thin(int cx, int cz, boolean server) {
        if (!thinWalls) return false;
        BlockSpec b = server ? serverArt(cx, cz) : art(cx, cz);
        return b != null && wallIds.contains(b.id());
    }

    /** The box at feet (px, pz) overlaps the thin column of the cell with corner (cx, cz). */
    private static boolean inColumn(double px, double pz, double cx, double cz) {
        return px + 0.3 > cx + 5 / 16.0 + 1e-9 && px - 0.3 < cx + 11 / 16.0 - 1e-9
            && pz + 0.3 > cz + 5 / 16.0 + 1e-9 && pz - 0.3 < cz + 11 / 16.0 - 1e-9;
    }

    /**
     * One movement tick of a body {@code st} = {x, y, z, vx, vy, vz, onGround, horizontal collision, jumped, walked off
     * the tile} (flags 0/1) with the world direction {@code w} of the keys, in the proxy's world (the bot's own physics)
     * or the server's ({@link #checkMove}).
     */
    private void move(double[] st, double[] w, boolean jump, boolean server) {
        boolean ground0 = st[6] != 0;
        st[7] = 0;
        st[8] = 0;
        st[9] = 0;
        if (jump && ground0) {
            st[4] = 0.42;
            ground0 = false;
            st[8] = 1;
        }
        double fr = ground0 ? 0.546 : 0.91;
        double acc = ground0 ? groundAccel : 0.02;
        st[3] = st[3] * fr + w[0] * acc;
        st[5] = st[5] * fr + w[1] * acc;
        double ox = st[0];
        double oz = st[2];
        if (collides(st[0] + st[3], st[1], st[2], server)) {
            st[7] = 1;
            st[0] += contact(st, 0, st[3], server);
            st[3] = 0;
        } else {
            st[0] += st[3];
        }
        if (collides(st[0], st[1], st[2] + st[5], server)) {
            st[7] = 1;
            st[2] += contact(st, 2, st[5], server);
            st[5] = 0;
        } else {
            st[2] += st[5];
        }
        double ground = groundLevel(st[0], st[2], server);
        if (Double.isNaN(ground)) {
            if (!Double.isNaN(groundLevel(ox, oz, server))) st[9] = 1;
            ground = st[1];
        }
        if (!ground0) {
            double ny = st[1] + st[4];
            st[4] = (st[4] - 0.08) * 0.98;
            if (ny <= ground) {
                ny = ground;
                st[4] = 0;
                ground0 = true;
            }
            st[1] = ny;
        } else if (ground < st[1] - 1e-9) {
            ground0 = false;
            st[4] = 0;
        }
        st[6] = ground0 ? 1 : 0;
    }

    /**
     * The server moves its own state of the bot with this tick's keys in its world; if the bot claims a position the
     * server does not reach, the bot is set back to (ax, ay, az), its last accepted position, standing still.
     */
    private void checkMove(double[] w, double ax, double ay, double az, boolean aGround) {
        double[] s = {srvX, srvY, srvZ, srvVx, srvVy, srvVz, srvOnGround ? 1 : 0, 0, 0, 0};
        move(s, w, jump, true);
        if (frozen) {
            s[0] = ax;
            s[1] = ay;
            s[2] = az;
        }
        if (Math.abs(s[0] - x) > 1e-6 || Math.abs(s[1] - y) > 1e-6 || Math.abs(s[2] - z) > 1e-6) {
            String what = String.format("claimed %.2f,%.2f,%.2f server %.2f,%.2f,%.2f -> %.2f,%.2f,%.2f",
                x, y, z, s[0], s[1], s[2], ax, ay, az);
            x = ax;
            y = ay;
            z = az;
            vx = vy = vz = 0;
            onGround = aGround;
            hCollision = false;
            syncServer();
            setback(what);
            return;
        }
        srvX = s[0];
        srvY = s[1];
        srvZ = s[2];
        srvVx = s[3];
        srvVy = s[4];
        srvVz = s[5];
        srvOnGround = s[6] != 0;
    }

    /** Highest standing surface under the box: artY + 1 on a full block, artY on the floor; NaN over the void. */
    private double groundLevel(double px, double pz, boolean server) {
        double g = Double.NaN;
        for (int cx = (int) Math.floor(px - 0.3 + 1e-9); cx <= (int) Math.floor(px + 0.3 - 1e-9); cx++) {
            for (int cz = (int) Math.floor(pz - 0.3 + 1e-9); cz <= (int) Math.floor(pz + 0.3 - 1e-9); cz++) {
                double h = raisedSolid(cx, cz) ? artY + 2
                    : solidAt(cx, cz, server) && (!thin(cx, cz, server)
                    || inColumn(px, pz, cx + thinWallOffset, cz + thinWallOffset)) ? artY + 1
                    : inTile(cx, cz) ? artY : Double.NaN;
                if (!Double.isNaN(h) && (Double.isNaN(g) || h > g)) g = h;
            }
        }
        return g;
    }

    // ---------------------------------------------------------------------------------------------- raycast

    /**
     * True if the ray from the eye with this rotation first hits the top face of {@code support} (the floor under an
     * art cell) within {@code reach}.
     */
    private boolean hitsTop(double ex, double ey, double ez, float ryaw, float rpitch, Pos support, double reach) {
        double[] d = Aim.viewVector(ryaw, rpitch);
        if (d[1] >= 0) return false;
        double tFloor = (artY - ey) / d[1];
        if (tFloor < 0 || tFloor > reach) return false;
        double hx = ex + d[0] * tFloor;
        double hz = ez + d[2] * tFloor;
        if ((int) Math.floor(hx) != support.x() || (int) Math.floor(hz) != support.z()) return false;
        if (!inTile(support.x(), support.z())) return false;
        int x0 = (int) Math.floor(Math.min(ex, hx)) - 1;
        int x1 = (int) Math.floor(Math.max(ex, hx)) + 1;
        int z0 = (int) Math.floor(Math.min(ez, hz)) - 1;
        int z1 = (int) Math.floor(Math.max(ez, hz)) + 1;
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                double[] r = raisedLevel ? outline(raised(cx, cz)) : null;
                if (r != null) {
                    double t = slab(ex, ey, ez, d, cx + r[0], artY + 1 + r[1], cz + r[2], cx + r[3], artY + 1 + r[4], cz + r[5]);
                    if (t >= 0 && t < tFloor - 1e-9) return false;
                }
                double[] b = outline(art(cx, cz));
                if (b == null) continue;
                double t = slab(ex, ey, ez, d, cx + b[0], artY + b[1], cz + b[2], cx + b[3], artY + b[4], cz + b[5]);
                if (t >= 0 && t < tFloor - 1e-9) return false;
            }
        }
        return true;
    }

    /** Entry distance of the ray into the box, or -1. */
    private static double slab(double ex, double ey, double ez, double[] d, double x0, double y0, double z0,
                               double x1, double y1, double z1) {
        double tmin = 0;
        double tmax = Double.POSITIVE_INFINITY;
        double[] o = {ex, ey, ez};
        double[] lo = {x0, y0, z0};
        double[] hi = {x1, y1, z1};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-12) {
                if (o[a] < lo[a] || o[a] > hi[a]) return -1;
                continue;
            }
            double t1 = (lo[a] - o[a]) / d[a];
            double t2 = (hi[a] - o[a]) / d[a];
            if (t1 > t2) {
                double t = t1;
                t1 = t2;
                t2 = t;
            }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return -1;
        }
        return tmin;
    }

    private @Nullable Shot shoot(double ex, double ey, double ez, Pos support, double reach, double jitter, int maxRays) {
        List<double[]> pts = Aim.topFacePoints(ex, ey, ez, support.x(), support.y(), support.z(), 0.15, jitter, rnd);
        int rays = 0;
        for (double[] p : pts) {
            if (Aim.distSq(ex, ey, ez, p[0], p[1], p[2]) > reach * reach) continue;
            if (rays++ >= maxRays) break;
            float[] rot = Aim.rotationTo(ex, ey, ez, p[0], p[1], p[2]);
            if (hitsTop(ex, ey, ez, rot[0], rot[1], support, reach)) return new Shot(rot[0], rot[1], support);
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------- LaneRun.Io

    @Override public double x() { return x; }
    @Override public double y() { return y; }
    @Override public double z() { return z; }
    @Override public double eyeY() { return y + Aim.EYE_HEIGHT; }
    @Override public float yaw() { return yaw; }
    @Override public boolean onGround() { return onGround; }
    @Override public boolean horizontalCollision() { return hCollision; }
    @Override public boolean isLoaded(Pos pos) { return true; }

    @Override
    public BlockSpec block(Pos pos) {
        if (pos.y() == artY - 1 && inTile(pos.x(), pos.z())) return platformBlock;
        if (pos.y() == artY) return art(pos.x(), pos.z());
        if (pos.y() == artY + 1) return raised(pos.x(), pos.z());
        return BlockSpec.AIR;
    }

    @Override
    public boolean raisedLevel() {
        return raisedRun;
    }

    @Override
    public boolean blocksPlacement(Pos pos) {
        return !block(pos).isAir();
    }

    @Override public boolean hasFluid(Pos pos) { return false; }

    @Override
    public double collisionTop(BlockSpec spec) {
        return collisionTopOf(spec);
    }

    @Override
    public boolean standOnTop(BlockSpec spec) {
        return spec != null && !wallIds.contains(spec.id()) && collisionTopOf(spec) >= 1.0;
    }

    /** The Baritone stand-in finds a way from the bot to within 1 of cell (cx, cz) (the bot is not walled in). */
    public boolean reachable(int cx, int cz) {
        List<Pos> keep = routeCells;
        boolean ok = route(new GoalNearXZ(cx, artY, cz, 1, 2)) != null;
        routeCells = keep;
        return ok;
    }

    @Override
    public double outlineTop(BlockSpec spec) {
        double[] b = outline(spec);
        return b == null ? 0 : b[4];
    }

    @Override
    public boolean matches(BlockSpec target, BlockSpec actual) {
        return TopFaceRules.equivalent(target, actual);
    }

    @Override
    public boolean inReach(Pos s, double reach) {
        double ex = x;
        double ey = eyeY();
        double ez = z;
        double nx = Aim.clamp(ex, s.x(), s.x() + 1.0);
        double ny = Aim.clamp(ey, s.y(), s.y() + 1.0);
        double nz = Aim.clamp(ez, s.z(), s.z() + 1.0);
        return Aim.distSq(ex, ey, ez, nx, ny, nz) <= reach * reach;
    }

    @Override
    public @Nullable Shot aim(Pos support, double reach) {
        return shoot(x, eyeY(), z, support, reach, 0.12, 4);
    }

    @Override
    public @Nullable Shot recheck(Pos support, double reach) {
        if (silent) {
            if (silentYaw == null) return null;
            return hitsTop(x, eyeY(), z, silentYaw, silentPitch, support, reach) ? new Shot(silentYaw, silentPitch, support) : null;
        }
        return hitsTop(x, eyeY(), z, yaw, pitch, support, reach) ? new Shot(yaw, pitch, support) : null;
    }

    @Override
    public boolean visibleFrom(double ex, double ey, double ez, Pos support, double reach) {
        return shoot(ex, ey, ez, support, reach, 0, 7) != null;
    }

    @Override
    public @Nullable Shot aimSide(Pos cell, double reach) {
        return shootSide(x, eyeY(), z, cell, reach, 0.12, 4);
    }

    @Override
    public @Nullable Shot recheckSide(Pos cell, double reach) {
        float ry;
        float rp;
        if (silent) {
            if (silentYaw == null) return null;
            ry = silentYaw;
            rp = silentPitch;
        } else {
            ry = yaw;
            rp = pitch;
        }
        if (rp < Aim.SIDE_MIN_PITCH) return null;
        Hit h = firstHit(x, eyeY(), z, ry, rp, reach);
        if (h == null || h.block().y() != artY) return null;
        int dx = h.block().x() - cell.x();
        int dz = h.block().z() - cell.z();
        if (Math.abs(dx) + Math.abs(dz) != 1 || h.face() != Aim.sideFace(dx, dz) || !solidAt(h.block().x(), h.block().z())
            || wallIds.contains(art(h.block().x(), h.block().z()).id())) {
            return null;
        }
        return new Shot(ry, rp, cell.below(), h.block());
    }

    @Override
    public boolean sideVisibleFrom(double ex, double ey, double ez, Pos cell, double reach) {
        return shootSide(ex, ey, ez, cell, reach, 0, Aim.SIDE_SPOT_RAYS) != null;
    }

    /** A side-face shot for {@code cell} from an eye at (ex, ey, ez), as {@code Aiming.sideFaceFrom} does it. */
    private @Nullable Shot shootSide(double ex, double ey, double ez, Pos cell, double reach, double jitter, int maxRays) {
        List<int[]> sides = new ArrayList<>(4);
        for (int[] d : Aim.SIDES) {
            if (!Aim.outsideSideFace(ex, ez, cell.x(), cell.z(), d[0], d[1], 0.05)) continue;
            if (!solidAt(cell.x() + d[0], cell.z() + d[1])) continue;
            // a side face is clicked only on a full cube (BlockStates.sideClickable): never a pointed dripstone or a pot
            if (wallIds.contains(art(cell.x() + d[0], cell.z() + d[1]).id())) continue;
            sides.add(d);
        }
        sides.sort(java.util.Comparator.comparingDouble(d -> Aim.distSq(ex, ey, ez,
            cell.x() + 0.5 + d[0] * 0.5, cell.y() + 0.5, cell.z() + 0.5 + d[1] * 0.5)));
        for (int[] d : sides) {
            Pos n = new Pos(cell.x() + d[0], artY, cell.z() + d[1]);
            int face = Aim.sideFace(d[0], d[1]);
            int rays = 0;
            for (double[] p : Aim.facePoints(ex, ey, ez, n.x(), n.y(), n.z(), new int[]{-d[0], 0, -d[1]}, 0.15, jitter, rnd)) {
                if (Aim.distSq(ex, ey, ez, p[0], p[1], p[2]) > reach * reach) continue;
                float[] rot = Aim.rotationTo(ex, ey, ez, p[0], p[1], p[2]);
                if (rot[1] < Aim.SIDE_MIN_PITCH) continue;
                if (rays++ >= maxRays) break;
                Hit h = firstHit(ex, ey, ez, rot[0], rot[1], reach);
                if (h != null && h.block().equals(n) && h.face() == face) return new Shot(rot[0], rot[1], cell.below(), n);
            }
        }
        return null;
    }

    /** The first block a ray hits: the block, the face it enters (Aim face codes) and the distance. */
    record Hit(Pos block, int face, double t) { }

    /**
     * First hit of the ray from (ex, ey, ez) with this rotation within {@code reach}: an art-layer outline box or the
     * floor's top face (the tile's floor block below the art layer), null if none.
     */
    private @Nullable Hit firstHit(double ex, double ey, double ez, float ryaw, float rpitch, double reach) {
        double[] d = Aim.viewVector(ryaw, rpitch);
        Hit best = null;
        if (d[1] < 0) {
            double tFloor = (artY - ey) / d[1];
            if (tFloor >= 0 && tFloor <= reach) {
                int fx = (int) Math.floor(ex + d[0] * tFloor);
                int fz = (int) Math.floor(ez + d[2] * tFloor);
                if (inTile(fx, fz)) best = new Hit(new Pos(fx, artY - 1, fz), Aim.UP, tFloor);
            }
        }
        double ex1 = ex + d[0] * reach;
        double ez1 = ez + d[2] * reach;
        for (int cx = (int) Math.floor(Math.min(ex, ex1)) - 1; cx <= (int) Math.floor(Math.max(ex, ex1)) + 1; cx++) {
            for (int cz = (int) Math.floor(Math.min(ez, ez1)) - 1; cz <= (int) Math.floor(Math.max(ez, ez1)) + 1; cz++)
              for (int lv = 0; lv <= (raisedLevel ? 1 : 0); lv++) {
                double[] b = outline(lv == 0 ? art(cx, cz) : raised(cx, cz));
                if (b == null) continue;
                double[] lo = {cx + b[0], artY + lv + b[1], cz + b[2]};
                double[] hi = {cx + b[3], artY + lv + b[4], cz + b[5]};
                double[] o = {ex, ey, ez};
                double tmin = 0;
                double tmax = Double.POSITIVE_INFINITY;
                int axis = -1;
                boolean miss = false;
                for (int a = 0; a < 3 && !miss; a++) {
                    if (Math.abs(d[a]) < 1e-12) {
                        if (o[a] < lo[a] || o[a] > hi[a]) miss = true;
                        continue;
                    }
                    double t1 = (lo[a] - o[a]) / d[a];
                    double t2 = (hi[a] - o[a]) / d[a];
                    double tn = Math.min(t1, t2);
                    double tf = Math.max(t1, t2);
                    if (tn > tmin) {
                        tmin = tn;
                        axis = a;
                    }
                    tmax = Math.min(tmax, tf);
                    if (tmin > tmax) miss = true;
                }
                if (miss || axis < 0 || tmin > reach) continue;
                int face = switch (axis) {
                    case 0 -> d[0] > 0 ? Aim.WEST : Aim.EAST;
                    case 1 -> d[1] > 0 ? Aim.DOWN : Aim.UP;
                    default -> d[2] > 0 ? Aim.NORTH : Aim.SOUTH;
                };
                if (best == null || tmin < best.t()) best = new Hit(new Pos(cx, artY + lv, cz), face, tmin);
              }
        }
        return best;
    }

    @Override
    public int count(String item) {
        int n = cached(item);
        if (hideUnknown && stackSize > 0) for (int i = 0; i < 9; i++) if (hidden(i, item)) n -= cachedSlot(i);
        return n;
    }

    /**
     * Stack mode with {@link #knownSlot}: unknown hotbar slots are hidden from counts, slots and the held item as
     * PlaceLaneTask's ZenithIo hides them ({@link LaneRun.Io#slotKnown}); false = the sim's older view (slots only).
     */
    public boolean hideUnknown;
    /** {@link LaneRun.Io#watchdogTicks}: 0 = the lane watchdog off (every older test). */
    public int watchdogTicks;
    /** {@link LaneRun.Io#watchdogHotbarTicks}: 0 = hotbar changes always count as progress (every older test). */
    public int watchdogHotbarTicks;

    private boolean hidden(int slot, String item) {
        return hideUnknown && !knownSlot.test(slot) && item.equals(shownItem(slot)) && cachedSlot(slot) > 0;
    }

    /** {@code build.unknownSlotResync} as ZenithIo reads it: false = unknown items count as missing (before 2026-10-15). */
    public boolean unknownResync = true;

    /** The previous tick's input executed and pressed no movement key (what an inventory click waits for). */
    public boolean stillLastTick() {
        return moveStillLastTick;
    }

    @Override
    public int unknownCount(String item) {
        if (!unknownResync) return 0;
        int n = 0;
        if (stackSize > 0) for (int i = 0; i < 9; i++) if (hidden(i, item)) n += cachedSlot(i);
        return n;
    }

    @Override
    public boolean moveSource(String item) {
        return stackSize <= 0 || mainCount(item) > 0;
    }

    @Override
    public String itemState(String item) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            if (!item.equals(shownItem(i)) || (stackSize > 0 && cachedSlot(i) <= 0)) continue;
            sb.append("slot ").append(i).append(" x").append(stackSize > 0 ? cachedSlot(i) : cached(item))
                .append(knownSlot.test(i) ? "" : " unknown").append(i == fencedSlot() ? " fenced" : "").append(", ");
        }
        return sb + "main inventory " + (stackSize > 0 ? mainCount(item) : 0);
    }

    @Override
    public int watchdogTicks() {
        return watchdogTicks;
    }

    @Override
    public int watchdogHotbarTicks() {
        return watchdogHotbarTicks;
    }

    @Override
    public String holdState() {
        return "input lost " + lostInputs + " ticks, still last tick " + moveStillLastTick;
    }

    @Override
    public @Nullable String heldItem() {
        if (hideUnknown && !knownSlot.test(held)) return null;
        String item = shownItem(held);
        if (stackSize > 0) return item != null && cachedSlot(held) > 0 ? item : null;
        return item != null && cached(item) > 0 ? item : null;
    }

    /** Stack mode: the count of hotbar slot {@code i} the cache shows. */
    private int cachedSlot(int i) {
        if (staleMoveReports && now > movedAt && now < fenceUntil && i == movedSlot) return movedOldCount;
        if (countLag > 0 && !slotHistory.isEmpty()) return slotHistory.peekFirst()[i];
        return slotCount[i];
    }

    /** Stack mode: items of {@code item} outside the hotbar (the server's state). */
    private int mainCount(String item) {
        int n = items.count(item);
        for (int i = 0; i < 9; i++) if (item.equals(hotbar[i])) n -= slotCount[i];
        return n;
    }

    /** The count the client's cache shows ({@link #countLag} ticks late), the server's with no lag. */
    private int cached(String item) {
        if (countLag > 0) {
            Map<String, Integer> then = countHistory.isEmpty() ? null : countHistory.peekFirst();
            if (then != null && then.containsKey(item)) return then.get(item);
        }
        return items.count(item);
    }

    @Override
    public int heldSlot() {
        return held;
    }

    @Override
    public int hotbarSlot(String item) {
        if (stackSize > 0) {
            for (int i = 1; i < 9; i++) if (item.equals(shownItem(i)) && cachedSlot(i) > 0 && !hidden(i, item)) return i;
            return -1;
        }
        if (cached(item) <= 0) return -1; // like Inv.find: an empty stack is no stack
        for (int i = 1; i < 9; i++) if (item.equals(shownItem(i))) return i;
        return -1;
    }

    @Override
    public int[] hotbarSlots(String item) {
        if (stackSize <= 0) return LaneRun.Io.super.hotbarSlots(item);
        int[] out = new int[9];
        int n = 0;
        for (int i = 1; i < 9; i++) if (item.equals(shownItem(i)) && cachedSlot(i) > 0 && !hidden(i, item)) out[n++] = i;
        return Arrays.copyOf(out, n);
    }

    @Override
    public boolean inventoryBusy() {
        return now < requestUntil || now < fenceUntil;
    }

    @Override
    public void ensureHeld(String item) {
        if (inventoryBusy()) return;
        if ((moveRequestTicks > 0 || moveFenceTicks > 0) && !moveStillLastTick) return;
        if (stackSize > 0) {
            if (fwd != 0 || strafe != 0 || jump || hotbarSlot(item) >= 0 || mainCount(item) <= 0) return;
            int slot = -1;
            for (int i = 1; i < 9 && slot < 0; i++) if (hotbar[i] == null || slotCount[i] <= 0) slot = i;
            if (slot < 0) slot = nextUse != null ? farthest() : 1 + Math.floorMod(roundRobin++, 8);
            if (!canPrefetchInto(slot)) return;
            moved(slot, hotbar[slot], slotCount[slot]);
            int n = Math.min(stackSize, mainCount(item));
            hotbar[slot] = item; // the stack there goes back to the main inventory
            slotCount[slot] = n;
            for (int[] h : slotHistory) h[slot] = n; // the cache predicts its own move (ZenithProxy's click prediction)
            if (prefetchRank < 0) select(slot);
            stackMoves++;
            inventoryMoves++;
            return;
        }
        if (fwd != 0 || strafe != 0 || jump || hotbarSlot(item) >= 0 || items.count(item) <= 0) return;
        int slot = -1;
        if (nextUse != null) for (int i = 1; i < 9 && slot < 0; i++) if (hotbar[i] == null) slot = i;
        if (slot < 0) slot = nextUse != null ? farthest() : 1 + Math.floorMod(roundRobin++, 8);
        if (!canPrefetchInto(slot)) return;
        moved(slot, hotbar[slot], hotbarCount(slot));
        hotbar[slot] = item;
        if (prefetchRank < 0) select(slot);
        inventoryMoves++;
    }

    /** Moves from the main inventory into the hotbar ({@link #ensureHeld}), both modes. */
    public int inventoryMoves;
    /** Opt-in delayed move model: request execution, then predicted/stale cache until confirmation. */
    public int moveRequestTicks;
    public int moveFenceTicks;
    public boolean staleMoveReports;
    private boolean moveStillLastTick;
    private long requestUntil;
    private long fenceUntil;
    private long movedAt = -1;
    private int movedSlot = -1;
    private String movedOldItem;
    private int movedOldCount;
    private long fenceWaited;
    public int fenceClicks;
    public int prefetchMoves;
    public Runnable inventoryPriority = () -> { };
    @Override public void inventoryNeeded() { inventoryPriority.run(); }
    public java.util.function.IntPredicate knownSlot = slot -> true;
    @Override public boolean slotKnown(int slot) { return knownSlot.test(slot); }
    public boolean lookaheadPrefetch;
    public boolean lookaheadEvict;
    public int staleReports;
    public int moveRuleViolations;
    public final List<Long> moveTicks = new ArrayList<>();

    private String shownItem(int slot) {
        return staleMoveReports && now > movedAt && now < fenceUntil && slot == movedSlot
            ? movedOldItem : hotbar[slot];
    }

    private void moved(int slot, String oldItem, int oldCount) {
        wireEvents.add(new WireEvent(clockNanos / 1_000_000L, "inventory", held, 0));
        if (moveRequestTicks == 0 && moveFenceTicks == 0) return;
        if (!moveStillLastTick || placementsThisTick > 0 || inventoryBusy()) moveRuleViolations++;
        movedAt = now;
        movedSlot = slot;
        movedOldItem = oldItem;
        movedOldCount = oldCount;
        requestUntil = now + moveRequestTicks;
        fenceUntil = now + moveFenceTicks;
        fenceWaited += moveFenceTicks;
        moveTicks.add(now);
    }

    @Override
    public String moveFence() {
        return moveTicks.isEmpty() ? "" : "; " + moveTicks.size() + " hotbar moves waited " + fenceWaited
            + " t for the server";
    }

    @Override
    public int fencedSlot() {
        return now < fenceUntil ? movedSlot : -1;
    }

    @Override
    public boolean clickInventoryBusy(boolean fenceClicks) {
        return requestBusy || containerOpen || now < requestUntil || (!fenceClicks && now < fenceUntil);
    }

    public int layoutMoves;
    public final Map<String, Integer> layoutStacks = new HashMap<>();

    /**
     * The pre-lane layout never settles: every move swaps slot 1 between a full stack and a single item (the layout
     * cycle of 2026-10-21, {@code LaneHotbarTest}).
     */
    public boolean layoutCycle;

    @Override
    public boolean layoutHotbar(List<String> cells) {
        if (stackSize <= 0) return true;
        if (inventoryBusy()) return false;
        if (layoutCycle) {
            if (!moveStillLastTick) return false;
            int count = slotCount[1] == stackSize ? 1 : stackSize;
            moved(1, hotbar[1], slotCount[1]);
            slotCount[1] = count;
            for (int[] h : slotHistory) h[1] = count;
            layoutMoves++;
            stackMoves++;
            inventoryMoves++;
            return false;
        }
        List<LaneHotbar.Stack> slots = new ArrayList<>();
        for (int slot = 1; slot <= 6; slot++)
            slots.add(new LaneHotbar.Stack(slot, hotbar[slot], slotCount[slot]));
        LaneHotbar.Move move = LaneHotbar.next(cells, slots, this::mainCount);
        if (move == null) {
            for (int slot = 1; slot <= 6; slot++) if (slotCount[slot] > 0)
                layoutStacks.merge(hotbar[slot], 1, Integer::sum);
            return true;
        }
        if (!moveStillLastTick) return false;
        int slot = move.slot();
        int count = Math.min(stackSize, mainCount(move.item()));
        moved(slot, hotbar[slot], slotCount[slot]);
        hotbar[slot] = move.item();
        slotCount[slot] = count;
        for (int[] h : slotHistory) h[slot] = count;
        layoutMoves++;
        stackMoves++;
        inventoryMoves++;
        return false;
    }

    @Override
    public boolean prefetchHeld(String item, java.util.function.ToIntFunction<String> nextUse,
                             java.util.function.IntPredicate replaceable) {
        this.nextUse = nextUse;
        this.prefetchReplaceable = replaceable;
        this.prefetchRank = nextUse.applyAsInt(item);
        int before = inventoryMoves;
        try {
            ensureHeld(item);
        } finally {
            this.nextUse = null;
            this.prefetchReplaceable = null;
            this.prefetchRank = -1;
        }
        prefetchMoves += inventoryMoves - before;
        return inventoryMoves > before;
    }

    @Override
    public boolean lookaheadPrefetch() { return lookaheadPrefetch; }

    @Override
    public void prefetchAhead(List<String> cells, java.util.function.IntUnaryOperator available,
                              java.util.function.IntPredicate replaceable) {
        if (inventoryBusy() || !moveStillLastTick || placementsThisTick > 0
            || fwd != 0 || strafe != 0 || jump || stackSize <= 0) return;
        List<io.github.digitalisp.mapart.bot.impl.LanePrefetch.Stack> slots = new ArrayList<>();
        for (int slot = 0; slot < 9; slot++) slots.add(new io.github.digitalisp.mapart.bot.impl.LanePrefetch.Stack(
            slot, hotbar[slot], available.applyAsInt(slot), slot > 0 && replaceable.test(slot)));
        var move = io.github.digitalisp.mapart.bot.impl.LanePrefetch.next(cells, slots, held, this::mainCount, lookaheadEvict);
        if (move == null) return;
        int slot = move.slot();
        int count = Math.min(stackSize, mainCount(move.item()));
        moved(slot, hotbar[slot], slotCount[slot]);
        hotbar[slot] = move.item();
        slotCount[slot] = count;
        for (int[] h : slotHistory) h[slot] = count;
        prefetchMoves++;
        stackMoves++;
        inventoryMoves++;
    }

    private java.util.function.IntPredicate prefetchReplaceable;
    private int prefetchRank = -1;

    private boolean canPrefetchInto(int slot) {
        return prefetchReplaceable == null || (prefetchReplaceable.test(slot)
            && (hotbar[slot] == null || nextUse.applyAsInt(hotbar[slot]) == Integer.MAX_VALUE));
    }
    /** The lane's ranking of the next use per material while {@link #ensureHeld(String, java.util.function.ToIntFunction)} runs. */
    private java.util.function.@Nullable ToIntFunction<String> nextUse;

    /** Belady's rule as Hotbar.chooseTarget: the slot (1-8, not the held one) whose item is needed last. */
    private int farthest() {
        int best = -1;
        int far = Integer.MIN_VALUE;
        for (int k = 0; k < 8; k++) {
            int i = 1 + Math.floorMod(roundRobin + k, 8);
            if (i == held || hotbar[i] == null) continue;
            int u = nextUse.applyAsInt(hotbar[i]);
            if (best < 0 || u > far) {
                best = i;
                far = u;
            }
        }
        if (best < 0) best = 1 + Math.floorMod(roundRobin, 8);
        roundRobin = best; // ties go round robin from the slot after the last one replaced
        return best;
    }

    @Override
    public void ensureHeld(String item, java.util.function.ToIntFunction<String> nextUse) {
        this.nextUse = nextUse;
        try {
            ensureHeld(item);
        } finally {
            this.nextUse = null;
        }
    }

    /**
     * Another module owns the input from now on (a higher-priority input request every tick): each request of the run
     * is rejected outright ({@link #lostTicks} counts them), nothing of it executes, no tick is a still tick.
     */
    public boolean inputLost;
    private int lostInputs;

    @Override
    public void submit(@Nullable Float ryaw, @Nullable Float rpitch, int forward, int str, boolean j) {
        if (inputLost) {
            lostInputs++;
            return;
        }
        lostInputs = 0;
        submitted = true;
        if (silent) {
            // grimRotate: PlayerInput + PosRot(target) now, the real rotation is re-sent unchanged
            reqYaw = null;
            reqPitch = null;
            silentReqYaw = ryaw;
            silentReqPitch = rpitch;
        } else {
            reqYaw = ryaw;
            reqPitch = rpitch;
        }
        fwd = forward;
        strafe = str;
        jump = j;
    }

    @Override
    public void holdStill() {
        submit(null, null, 0, 0, false);
    }

    @Override public int lostTicks() { return lostInputs; }
    @Override public boolean ownedLastTick() { return prevSubmitted; }
    @Override public boolean canClick() { return submitted && prevSubmitted && now >= noClickUntil; }

    /** Ticks with movement keys right after a tick without (the walk's own input; Baritone's walks not counted). */
    public int walkStarts;
    /**
     * Ticks without a click after each walk start (0: none). The routing study fitted about 0.85 ticks lost per walk
     * start on 2b2t (mechanism unidentified); a stand-in for it in comparisons of walk styles.
     */
    public int walkStartClickLoss;
    private boolean keysLastTick;
    private long noClickUntil = Long.MIN_VALUE;

    @Override
    public boolean select(int slot) {
        selections.add(new Selection(clockNanos / 1_000_000L, slot));
        wireEvents.add(new WireEvent(clockNanos / 1_000_000L, "select", slot, 0));
        held = slot;
        return true;
    }

    @Override
    public int clickSequence() {
        return sequence;
    }

    @Override
    public int ackedSequence() {
        return ackedSequence;
    }

    @Override
    public boolean ackedExactly(int seq) {
        return exactAcks.contains(seq);
    }

    @Override
    public long echoMark() {
        return echoes ? echoCount : -1;
    }

    @Override
    public boolean echoedSince(Pos pos, long mark) {
        Long at = echoAt.get(pos);
        return at != null && at > mark;
    }

    @Override
    public int hotbarCount(int slot) {
        String item = slot >= 0 && slot < 9 ? shownItem(slot) : null;
        if (item != null && hidden(slot, item)) return 0;
        if (stackSize > 0) return item == null ? 0 : cachedSlot(slot);
        return item == null ? 0 : cached(item);
    }

    /**
     * Ticks the client's inventory counts ({@link #count}, {@link #heldItem}, {@link #hotbarSlot}, {@link #hotbarCount})
     * lag behind the server's (2b2t: about one round trip), so a stack looks fuller than it is while clicks are in
     * flight; the "server" still refuses a click with an empty hand ({@link #wrongClicks}). 0 = no lag.
     */
    public int countLag;
    private final java.util.ArrayDeque<Map<String, Integer>> countHistory = new java.util.ArrayDeque<>();

    private void recordCounts() {
        if (countLag <= 0) return;
        if (stackSize > 0) {
            slotHistory.addLast(slotCount.clone());
            while (slotHistory.size() > countLag) slotHistory.removeFirst();
        }
        Map<String, Integer> now = new HashMap<>();
        for (String item : hotbar) if (item != null) now.put(item, items.count(item));
        countHistory.addLast(now);
        while (countHistory.size() > countLag) countHistory.removeFirst();
    }

    @Override
    public boolean airPlaceReady() {
        return airPlaceReady;
    }

    /** Actual AirPlace packet helper, with a virtual swap cache; the logical material stacks stay in their slots. */
    public record WirePacket(long tick, String kind, int slot, int sequence) { }
    public final List<WirePacket> airPackets = new ArrayList<>();
    private final AirPlaceGuard airGuard = new AirPlaceGuard();
    private boolean offhandSwapped;
    private final AirPlaceGuard.View airView = new AirPlaceGuard.View() {
        @Override public boolean inGame() { return true; }
        @Override public String offhand() { return offhandSwapped ? hotbar[held] : AirPlaceGuard.TOTEM; }
        @Override public String hotbar(int slot) {
            return offhandSwapped && slot == held ? AirPlaceGuard.TOTEM : hotbar[slot];
        }
        @Override public int heldSlot() { return held; }
        @Override public boolean containerOpen() { return false; }
        @Override public boolean inventoryBusy() { return now < requestUntil; }
        @Override public boolean placesBlock(String item) { return !AirPlaceGuard.TOTEM.equals(item); }
        @Override public int slotOf(String item) { return -1; }
    };
    private final AirPlace airPlacer = new AirPlace(airGuard, new AirPlace.Port() {
        @Override public boolean select(int slot) { return LaneSim.this.select(slot); }
        @Override public boolean swapOffhand() {
            airPackets.add(new WirePacket(now, "swap", held, 0));
            wireEvents.add(new WireEvent(clockNanos / 1_000_000L, "swap", held, 0));
            offhandSwapped = !offhandSwapped;
            return true;
        }
        @Override public int useItemOnInside(int x, int y, int z) {
            Pos cell = new Pos(x, y, z);
            airPlaces++;
            boolean far = Aim.distSq(LaneSim.this.x, eyeY(), LaneSim.this.z, x + 0.5, y + 0.5, z + 0.5)
                > airServerRange * airServerRange;
            if (!send(cell, far, cell.above())) return -1;
            airPackets.add(new WirePacket(now, "use", held, sequence));
            wireEvents.add(new WireEvent(clockNanos / 1_000_000L, "use", held, sequence));
            return sequence;
        }
        @Override public void swingOffhand() {
            airPackets.add(new WirePacket(now, "swing", held, 0));
            wireEvents.add(new WireEvent(clockNanos / 1_000_000L, "swing", held, 0));
            if (failSwing) throw new IllegalStateException("swing failed");
        }
    });

    @Override
    public void beginAirPlaces(boolean batch) {
        airGuard.tick(airView, new AirPlace.Port() {
            @Override public boolean select(int slot) { return LaneSim.this.select(slot); }
            @Override public boolean swapOffhand() { throw new AssertionError("unexpected recovery in LaneSim"); }
            @Override public int useItemOnInside(int x, int y, int z) { return -1; }
            @Override public void swingOffhand() { }
        });
        airPlacer.beginBatch(batch);
    }

    @Override
    public void endAirPlaces() {
        airPlacer.endBatch();
        if (offhandSwapped) throw new AssertionError("offhand left swapped at tick end");
    }

    @Override
    public boolean airPlace(Pos cell) {
        return airPlacer.place(airView, held, cell) == AirPlace.Result.SENT;
    }

    @Override
    public boolean click(Shot shot) {
        Pos cell = shot.support().above();
        if (shot.side() != null) {
            sideClicks++;
            minSidePitch = Math.min(minSidePitch, shot.pitch());
            Pos n = shot.side();
            int dx = n.x() - cell.x();
            int dz = n.z() - cell.z();
            Hit h = firstHit(x, eyeY(), z, shot.yaw(), shot.pitch(), 6);
            boolean ok = n.y() == artY && Math.abs(dx) + Math.abs(dz) == 1 && solidAt(n.x(), n.z())
                && Aim.outsideSideFace(x, z, cell.x(), cell.z(), dx, dz, 0)
                && shot.pitch() >= Aim.SIDE_MIN_PITCH
                && h != null && h.block().equals(n) && h.face() == Aim.sideFace(dx, dz);
            if (!ok) badSideClicks++;
            return send(cell, false, n);
        }
        return send(cell, false, shot.support());
    }

    /**
     * One use-item-on into {@code cell} with the held item: the next block prediction sequence, acknowledged
     * {@link #confirmDelay} ticks later (after the block, if placed, and the block updates for {@code cell} and
     * {@code other}, the clicked block or the one next to the clicked face); {@code far}: an airplace beyond the
     * server's range (acked without block updates, as vanilla out of interaction range).
     */
    private boolean send(Pos cell, boolean far, Pos other) {
        clicks++;
        sentClicks.add(new SentClick(now, cell));
        if (now == movedAt || now < requestUntil || (now < fenceUntil && held == movedSlot)) moveRuleViolations++;
        if (now < fenceUntil) fenceClicks++;
        if (walkGoals != null && !submitted) walkingClicks++;
        maxPlacementsPerTick = Math.max(maxPlacementsPerTick, ++placementsThisTick);
        if (placementsThisTick == 2) multiClickTicks++;
        BlockSpec planned0 = targets.get(cell);
        if (planned0 != null && collisionTopOf(planned0) > 0.6) {
            maxSolidsPerTick = Math.max(maxSolidsPerTick, ++solidsThisTick);
        }
        clickTicks.addLast(now);
        while (clickTicks.peekFirst() <= now - slidingTicks) clickTicks.pollFirst();
        maxPerSlidingWindow = Math.max(maxPerSlidingWindow, clickTicks.size());
        int seq = ++sequence;
        int jitter = clickJitterTicks.applyAsInt(seq);
        if (jitter < 0 || jitter > 2) throw new IllegalArgumentException("click jitter must be 0..2 ticks");
        jitterCounts[jitter]++;
        long sendMillis = millisecondArrivals ? clockNanos / 1_000_000L : now * 50;
        long arrivalMillis = Math.max(sendMillis + (millisecondArrivals
            ? networkLatencyMillis + clickJitterMillis.applyAsInt(seq) : jitter * 50), lastArrivalMillis);
        timedClicks.add(new TimedClick(sendMillis, arrivalMillis, seq, held, cell));
        lastArrivalMillis = arrivalMillis;
        responseDelay = confirmDelay + (int) ((arrivalMillis - now * 50 + (millisecondArrivals ? 49 : 0)) / 50);
        if (paperWindowTicks > 0) {
            // Paper: if (last != -1 && t - last < threshold && limited++ >= 8) drop; window restarts at t - last >= threshold
            if (paperWindowStart != -1 && arrivalMillis - paperWindowStart < paperWindowTicks * 50L
                && paperWindowCount++ >= paperWindowMax - 1) {
                paperDrops++;
                limiterDrops++;
                return true;
            }
            if (paperWindowStart == -1 || arrivalMillis - paperWindowStart >= paperWindowTicks * 50L) {
                paperWindowStart = arrivalMillis;
                paperWindowCount = 0;
            }
        }
        if (anticheat.test(cell)) {
            anticheatCancels++;
            later(() -> ack(seq));
            return true;
        }
        if (limiter.test(cell)) {
            limiterDrops++;
            return true;
        }
        if (unanswered.test(cell)) {
            unansweredClicks++;
            later(() -> ackedSequence = Math.max(ackedSequence, seq)); // covered, never acked exactly
            return true;
        }
        try {
            if (far) {
                farRejects++;
                return true;
            }
            if (refuse.test(cell)) {
                refusals++;
                return true;
            }
            return answer(cell);
        } finally {
            if (!far) {
                later(() -> {
                    echoAt.put(cell, ++echoCount);
                    echoAt.put(other, ++echoCount);
                });
            }
            later(() -> ack(seq)); // the ack, after the block updates (due runs in order)
        }
    }

    private void later(Runnable r) {
        dueAt.add(new long[]{now + responseDelay});
        due.add(r);
    }

    private void ack(int seq) {
        ackedSequence = Math.max(ackedSequence, seq);
        exactAcks.add(seq);
    }

    /** The "server" side of a placement into {@code cell} with the held item. */
    private boolean answer(Pos cell) {
        String item = hotbar[held];
        BlockSpec planned = targets.get(cell);
        if (planned == null && art != null) { // a break-in's corridor: the art's block goes up again
            Placement p = art.apply(cell);
            if (p != null) planned = p.block();
        }
        BlockSpec target = planned;
        if (item == null || target == null || items.count(item) <= 0 || !item.equals(itemOf(target))
            || (stackSize > 0 && slotCount[held] <= 0)) {
            wrongClicks++;
            return true;
        }
        if (!(cell.y() == artY ? serverArt(cell.x(), cell.z()) : block(cell)).isAir()) { // the server's world
            occupiedRejects++;
            return true;
        }
        if (collisionTopOf(target) > 0 && Aim.cellOverlaps(cell.x(), cell.y(), cell.z(), Aim.playerBox(x, y, z, 0))) {
            boxRejects++;
            return true;
        }
        if (missRate > 0 && rnd.nextDouble() < missRate) {
            dropped++;
            return true;
        }
        accepted++;
        trail.addLast(String.format("t%d PLACE %d,%d %s from %.2f,%.2f,%.2f", now, cell.x(), cell.z(), target.id(), x, y, z));
        items.take(item);
        if (stackSize > 0) slotCount[held]--;
        if (onAccept != null) onAccept.accept(new Placement(cell, target, item));
        inFlight.put(cell, target);
        inFlightAt.put(cell, now);
        dueAt.add(new long[]{now + responseDelay});
        due.add(() -> {
            blocks.set(cell, target);
            ghosts.remove(cell); // the block update replaces what the proxy kept
            inFlight.remove(cell);
            inFlightAt.remove(cell);
            if (collisionTopOf(target) > 0) shownAt.put(cell, now);
        });
        return true;
    }

    private static String itemOf(BlockSpec b) {
        return b.id();
    }

    @Override
    public boolean standAllowed(Pos feet) {
        return standCells.test(feet) && LaneRun.nearAvoid(feet, avoid) == null;
    }

    /** Feet cells beside a drop, water or a no-go zone ({@link LaneRun.Io#edgeHazard}, the edge stops). */
    public Predicate<Pos> edgeCells = p -> false;
    /** {@code build.edgeSpotsLast} and {@code build.edgeSneak} of the run. */
    public boolean edgeSpotsLast;
    public boolean edgeSneak;
    /** Ticks the run submitted with the sneak key, and the feet cells it sneaked in. */
    public int sneakTicks;
    public final java.util.Set<Pos> sneakCells = new java.util.HashSet<>();

    @Override
    public @Nullable String edgeHazard(Pos feet) {
        return edgeCells.test(feet) ? "a test edge" : null;
    }

    @Override
    public boolean edgeSpotsLast() {
        return edgeSpotsLast;
    }

    @Override
    public boolean edgeSneak() {
        return edgeSneak;
    }

    @Override
    public void submit(@Nullable Float ryaw, @Nullable Float rpitch, int forward, int str, boolean j, boolean sneak) {
        submit(ryaw, rpitch, forward, str, j);
        if (sneak) {
            sneakTicks++;
            sneakCells.add(new Pos((int) Math.floor(x), (int) Math.floor(y + 1e-6), (int) Math.floor(z)));
        }
    }

    // ---------------------------------------------------------------------------------------------- break-in

    /** VERIFY's art for the break-in ({@link LaneRun.Io#artAt}); null: none (a BUILD lane). */
    public boolean dripstoneBreakThrough;
    @Override public boolean dripstoneBreakThrough() { return dripstoneBreakThrough; }
    @Override public boolean wallBrake() { return wallBrake; }
    @Override public boolean stepBackClearPath() { return stepBackClearPath; }
    @Override public boolean forwardClearPath() { return forwardClearPath; }
    @Override public boolean startOnFloor() { return startOnFloor; }
    @Override public boolean startTail() { return startTail; }
    public java.util.function.@Nullable Function<Pos, @Nullable Placement> art;
    /** Blocks {@link #breakWalls} broke, in order. */
    public final List<Pos> brokenWalls = new ArrayList<>();
    /** Ticks a block in reach takes to break. */
    public int breakTicks = 4;
    private @Nullable List<Pos> breaking;
    private int breakTicksLeft;
    private String breakError = "";

    @Override
    public @Nullable Placement artAt(Pos cell) {
        return art == null ? null : art.apply(cell);
    }

    /** A wall Baritone cannot pass ({@link #wallIds}) on a cell of the art. */
    @Override
    public boolean breakableWall(Pos cell) {
        return art != null && art.apply(cell) != null && wallIds.contains(block(cell).id());
    }

    /**
     * The walls in order: one within reach of the eye (4.5 to its nearest point) breaks after {@link #breakTicks}; one
     * out of reach is walked to by the Baritone stand-in (the path's cell before it, or its last cell).
     */
    @Override
    public TaskStatus breakWalls(List<Pos> walls, List<Pos> path) {
        if (!walls.equals(breaking)) {
            breaking = List.copyOf(walls);
            breakTicksLeft = breakTicks;
        }
        Pos next = null;
        for (Pos w : walls) {
            if (!block(w).isAir()) {
                next = w;
                break;
            }
        }
        if (next == null) {
            breaking = null;
            walkGoals = null;
            return TaskStatus.SUCCESS;
        }
        double nx = Aim.clamp(x, next.x(), next.x() + 1.0);
        double ny = Aim.clamp(eyeY(), next.y(), next.y() + 1.0);
        double nz = Aim.clamp(z, next.z(), next.z() + 1.0);
        if (Aim.distSq(x, eyeY(), z, nx, ny, nz) <= 4.5 * 4.5) {
            walkGoals = null;
            if (--breakTicksLeft > 0) return TaskStatus.RUNNING;
            blocks.set(next, BlockSpec.AIR);
            brokenWalls.add(next);
            breakTicksLeft = breakTicks;
            return TaskStatus.RUNNING;
        }
        int i = path.indexOf(next);
        Pos stand = i > 0 ? path.get(i - 1) : path.getLast();
        Pos goal = new Pos(stand.x(), artY, stand.z());
        TaskStatus s = baritone(List.of(new GoalNearXZ(stand.x(), artY, stand.z(), 0, 2)),
            () -> Math.floor(x) == goal.x() && Math.floor(z) == goal.z());
        if (s == TaskStatus.FAILED) {
            breaking = null;
            breakError = "cannot walk to " + goal + ": " + walkError;
            return TaskStatus.FAILED;
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public String breakError() {
        return breakError;
    }

    @Override
    public TaskStatus walk(List<GoalNearXZ> goals, BooleanSupplier arrived) {
        spotWalk = false;
        noClimb = false;
        return baritone(goals, arrived);
    }

    /** Where each Baritone leg ends: this much past its goal along x (a walk that arrives off its target). */
    public double arrivalShiftX;

    /** A stand-spot walk: every path is checked like PlaceLaneTask's ({@link LaneRun#pathRefusal}). */
    @Override
    public TaskStatus walk(List<GoalNearXZ> goals, BooleanSupplier arrived, boolean noClimb) {
        spotWalk = true;
        this.noClimb = noClimb;
        return baritone(goals, arrived);
    }

    /** Goals the Baritone stand-in finds no path to (e.g. a lane start entered only by a refused step down). */
    public Predicate<GoalNearXZ> noPath = g -> false;

    /**
     * Where the Baritone stand-in ends a leg instead of its route's end: {x, y, z} feet, or null for the route. The real
     * pathfinder stops as soon as the feet cell is in the goal, also off the tile: live (soul sand) on the top edge of the
     * glass wall two blocks high south of the tile's last row, its box still on the wall (LaneStartFloorTest).
     */
    public java.util.function.Function<GoalNearXZ, double @Nullable []> legEnd = g -> null;

    /** Path-request retries asked for by stand-spot walks with a limit (a last-resort spot: 0). */
    public final java.util.Set<Integer> spotWalkRetries = new java.util.TreeSet<>();

    @Override
    public TaskStatus walk(List<GoalNearXZ> goals, BooleanSupplier arrived, boolean noClimb, int retries) {
        spotWalkRetries.add(retries);
        return walk(goals, arrived, noClimb);
    }

    /** The Baritone stand-in: legs in order, each a {@link #route} walked in its time, then a teleport to its end. */
    private TaskStatus baritone(List<GoalNearXZ> goals, BooleanSupplier arrived) {
        if (arrived.getAsBoolean()) {
            walkGoals = null;
            return TaskStatus.SUCCESS;
        }
        if (walkGoals == null || !walkGoals.equals(goals)) {
            walkGoals = List.copyOf(goals);
            walkGoal = 0;
            walkDest = null;
            stalls = 0;
            if (goals.stream().allMatch(g -> g.range() == 0 && g.yTolerance() == 0)) {
                GoalNearXZ g = goals.getLast();
                spotGoals.add(new Pos(g.x(), g.y(), g.z()));
                if (raisedSolid(g.x(), g.z())) raisedSpots++;
            }
        }
        if (walkDest == null) {
            if (walkGoal >= walkGoals.size()) {
                walkError = "reached " + walkGoals.getLast() + " but target still not reachable/visible";
                walkGoals = null;
                return TaskStatus.FAILED;
            }
            double[] d = noPath.test(walkGoals.get(walkGoal)) ? null : route(walkGoals.get(walkGoal));
            double[] end = legEnd.apply(walkGoals.get(walkGoal));
            if (end != null) {
                Pos at = new Pos((int) Math.floor(end[0]), (int) Math.floor(end[1] + 0.1251), (int) Math.floor(end[2]));
                routeCells = List.of(new Pos((int) Math.floor(x), (int) Math.floor(y + 0.1251), (int) Math.floor(z)), at);
                d = new double[]{end[0], end[1], end[2], Math.hypot(end[0] - x, end[2] - z), 0};
            }
            if (d == null) {
                walkError = "no path to " + walkGoals.get(walkGoal);
                walkGoals = null;
                return TaskStatus.FAILED;
            }
            if (spotWalk) {
                String why = LaneRun.pathRefusal(routeCells, tileArea(), avoid, noClimb);
                if (why != null) {
                    walkError = "the path to " + walkGoals.get(walkGoal) + " " + why + "; not walking it";
                    refusedWalks++;
                    if (refusedPaths.size() < 20) refusedPaths.add("tick " + now + ": " + walkError);
                    walkGoals = null;
                    return TaskStatus.FAILED;
                }
            }
            legStalled = false;
            int bad = overlay() ? serverBreak(routeCells) : -1;
            if (bad > 0) {
                // the server does not have that cell as planned (a block only it has, a ghost to step onto): the bot
                // gets set back in front of it until Baritone gives the path up
                Pos before = routeCells.get(bad - 1);
                int climbs = 0;
                for (int i = 1; i < bad; i++) if (routeCells.get(i).y() > routeCells.get(i - 1).y()) climbs++;
                d = new double[]{before.x() + 0.5, standHeight(before.x(), before.z()), before.z() + 0.5,
                    bad - 1 + climbs, climbs};
                legStalled = true;
                legMaxY = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < bad; i++) legMaxY = Math.max(legMaxY, routeCells.get(i).y());
            } else {
                legMaxY = Double.NEGATIVE_INFINITY;
                for (Pos c : routeCells) legMaxY = Math.max(legMaxY, c.y());
            }
            walkDest = d;
            walkTicksLeft = (int) Math.ceil(d[3] / WALK) + 2 * (int) d[4] + (legStalled ? stallTicks : 0);
            legTicks = walkTicksLeft;
            legX = x;
            legY = y;
            legZ = z;
            legIndex = 0;
            baritoneWalks++;
            baritoneDistance += d[3];
            jumps += (int) d[4];
        }
        if (--walkTicksLeft > 0) {
            if (baritoneMoves && !legStalled) moveAlongLeg();
            return TaskStatus.RUNNING;
        }
        double[] d = walkDest;
        walkDest = null;
        teleport(d[0] + arrivalShiftX, d[1], d[2]);
        maxFeetY = Math.max(maxFeetY, legMaxY);
        if (legStalled) {
            setback("path of " + walkGoals.get(walkGoal) + " stalled");
            stalledPaths++;
            if (++stalls > STALL_RETRIES) {
                walkError = "pathing to " + walkGoals.get(walkGoal) + " stalled " + stalls + " times";
                walkGoals = null;
                return TaskStatus.FAILED;
            }
            return TaskStatus.RUNNING; // Baritone plans the same path again
        }
        if (inPocket()) pocket("baritone");
        if (arrived.getAsBoolean()) {
            walkGoals = null;
            return TaskStatus.SUCCESS;
        }
        walkGoal++;
        return TaskStatus.RUNNING;
    }

    /**
     * {@link #baritoneMoves}: the bot at its share of the leg's way (ticks gone / leg ticks) along the route's cell
     * centres (the start and the end at the bot's real positions), on the cell's floor or block; a full block that
     * appeared on the route ahead counts as {@link #pathBlocked}.
     */
    private void moveAlongLeg() {
        double[] d = walkDest;
        List<Pos> cells = routeCells;
        int n = cells.size();
        if (d == null || n < 2) return;
        double[] px = new double[n];
        double[] pz = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = i == 0 ? legX : i == n - 1 ? d[0] : cells.get(i).x() + 0.5;
            pz[i] = i == 0 ? legZ : i == n - 1 ? d[2] : cells.get(i).z() + 0.5;
        }
        double total = 0;
        for (int i = 1; i < n; i++) total += Math.hypot(px[i] - px[i - 1], pz[i] - pz[i - 1]);
        double want = total * (1.0 - (double) walkTicksLeft / Math.max(1, legTicks));
        int i = 1;
        double seg = 0;
        while (i < n - 1 && want > (seg = Math.hypot(px[i] - px[i - 1], pz[i] - pz[i - 1]))) {
            want -= seg;
            i++;
        }
        seg = Math.hypot(px[i] - px[i - 1], pz[i] - pz[i - 1]);
        double f = seg < 1e-9 ? 1 : Math.min(1, want / seg);
        double ox = x;
        double oz = z;
        x = px[i - 1] + (px[i] - px[i - 1]) * f;
        z = pz[i - 1] + (pz[i] - pz[i - 1]) * f;
        Pos c = cells.get(f < 0.5 ? i - 1 : i);
        double h = standHeight(c.x(), c.z());
        y = Double.isNaN(h) ? legY : h;
        vx = x - ox;
        vz = z - oz;
        vy = 0;
        onGround = true;
        syncServer();
        maxFeetY = Math.max(maxFeetY, y);
        walked += Math.hypot(x - ox, z - oz);
        legIndex = f < 0.5 ? i - 1 : i;
        for (int k = legIndex + 1; k < n; k++) {
            Pos a = cells.get(k);
            if (a.y() == artY && wallAt(a.x(), a.z())) {
                pathBlocked++;
                if (pathBlocks.size() < 10) pathBlocks.add("tick " + now + ": " + a + " on the route ahead of " + legIndex);
                legStalled = true; // walk the rest as a stalled leg: no more moving along it
                break;
            }
        }
    }

    /** A Baritone stand-in leg is under way ({@link #baritoneMoves}: the bot moves along it). */
    @Override
    public boolean clickWhileWalking() {
        return walkGoals != null && walkDest != null && !submitted;
    }

    /** The route's cells from where the bot is on to the leg's end; null when no leg is under way. */
    @Override
    public @Nullable List<Pos> walkPath() {
        if (walkGoals == null || walkDest == null) return null;
        return List.copyOf(routeCells.subList(Math.min(legIndex, routeCells.size()), routeCells.size()));
    }

    @Override
    public void placedExtra(Placement p) {
        extrasPlaced++;
        extraCells.add(p);
        placed(p);
    }

    /** The cells reported by {@link #placedExtra}, in order. */
    public final List<Placement> extraCells = new ArrayList<>();

    /** The Baritone stand-in walks this tick (the run submitted no input of its own). */
    public boolean walking() {
        return walkGoals != null && !submitted;
    }

    @Override
    public String walkError() {
        return walkError;
    }

    @Override
    public void stopWalking() {
        walkGoals = null;
        walkDest = null;
    }

    /**
     * Baritone stand-in: cheapest cell inside the goal over the cells of the tile (4-neighbour steps, a step up costs
     * one more), or straight there from off the tile. Returns {x, y, z, distance, climbs}: feet position up to 0.2 off
     * the cell centre.
     */
    private double @Nullable [] route(GoalNearXZ g) {
        int sx = (int) Math.floor(x);
        int sz = (int) Math.floor(z);
        double ox = (rnd.nextDouble() * 2 - 1) * 0.2;
        double oz = (rnd.nextDouble() * 2 - 1) * 0.2;
        Pos start = new Pos(sx, (int) Math.floor(y + 0.1251), sz);
        routeCells = List.of(start);
        if (!inTile(sx, sz)) {
            for (int r = 0; r <= g.range(); r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        int cx = g.x() + dx;
                        int cz = g.z() + dz;
                        double h = standHeight(cx, cz);
                        if (Double.isNaN(h) || !g.isInGoal(cx, (int) Math.floor(h), cz)) continue;
                        double dist = Math.hypot(cx + 0.5 - x, cz + 0.5 - z);
                        Pos end = new Pos(cx, (int) Math.floor(h), cz);
                        routeCells = baritoneMoves ? straightCells(start, end) : List.of(start, end);
                        return new double[]{cx + 0.5 + ox, h, cz + 0.5 + oz, dist, 0};
                    }
                }
            }
            return null;
        }
        Map<Long, double[]> best = new HashMap<>(); // cost, climbs
        Map<Long, Long> prev = new HashMap<>();
        PriorityQueue<double[]> q = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        q.add(new double[]{0, sx, sz, 0});
        best.put(key(sx, sz), new double[]{0, 0});
        while (!q.isEmpty()) {
            double[] c = q.poll();
            int cx = (int) c[1];
            int cz = (int) c[2];
            double[] known = best.get(key(cx, cz));
            if (known != null && known[0] < c[0]) continue;
            double h = standHeight(cx, cz);
            if (!Double.isNaN(h) && g.isInGoal(cx, (int) Math.floor(h), cz)) {
                List<Pos> cells = new ArrayList<>();
                for (Long k = key(cx, cz); k != null; k = prev.get(k)) {
                    int px = (int) (k >> 32);
                    int pz = (int) (long) k;
                    double ph = standHeight(px, pz);
                    cells.add(px == sx && pz == sz ? start : new Pos(px, (int) Math.floor(ph), pz));
                }
                java.util.Collections.reverse(cells);
                routeCells = cells;
                return new double[]{cx + 0.5 + ox, h, cz + 0.5 + oz, c[0], c[3]};
            }
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                if (!inTile(nx, nz)) continue;
                double nh = standHeight(nx, nz);
                if (Double.isNaN(nh)) continue;
                // two blocks up (the floor to a raised block's top) is no move Baritone makes
                if (raisedLevel && !Double.isNaN(h) && nh > h + 1.5) continue;
                boolean up = !Double.isNaN(h) && nh > h + 0.5;
                double cost = c[0] + 1 + (up ? 1 : 0);
                double[] k = best.get(key(nx, nz));
                if (k != null && k[0] <= cost) continue;
                best.put(key(nx, nz), new double[]{cost, c[3] + (up ? 1 : 0)});
                prev.put(key(nx, nz), key(cx, cz));
                q.add(new double[]{cost, nx, nz, c[3] + (up ? 1 : 0)});
            }
        }
        return null;
    }

    /** Feet cells along the straight walk from {@code a} to {@code b} (both included), each once, in order. */
    private List<Pos> straightCells(Pos a, Pos b) {
        List<Pos> out = new ArrayList<>();
        out.add(a);
        double ax = x;
        double az = z;
        double bx = b.x() + 0.5;
        double bz = b.z() + 0.5;
        int n = (int) Math.ceil(Math.hypot(bx - ax, bz - az) / 0.25);
        for (int i = 1; i <= n; i++) {
            int cx = (int) Math.floor(ax + (bx - ax) * i / n);
            int cz = (int) Math.floor(az + (bz - az) * i / n);
            Pos last = out.getLast();
            if (last.x() == cx && last.z() == cz) continue;
            double h = standHeight(cx, cz);
            out.add(new Pos(cx, Double.isNaN(h) ? a.y() : (int) Math.floor(h), cz));
        }
        if (!out.getLast().equals(b)) out.add(b);
        return out;
    }

    /** First cell of a path (after the bot's own) the server does not have as the proxy shows it, or -1. */
    private int serverBreak(List<Pos> cells) {
        if (frozen && cells.size() > 1) return 1;
        for (int i = 1; i < cells.size(); i++) {
            Pos c = cells.get(i);
            if (solidAt(c.x(), c.z(), true) != solidAt(c.x(), c.z(), false)) return i;
        }
        return -1;
    }

    private double standHeight(int cx, int cz) {
        // on top of a raised block (no room under it: the floor cell there is no cell to stand in)
        if (raisedSolid(cx, cz)) return inTile(cx, cz) ? artY + 2 : Double.NaN;
        if (!wallIds.isEmpty()) {
            BlockSpec b = art(cx, cz);
            if (b != null && wallIds.contains(b.id())) return Double.NaN; // neither through nor onto
        }
        if (solidAt(cx, cz)) return artY + 1;
        return inTile(cx, cz) ? artY : Double.NaN;
    }

    private static long key(int a, int b) {
        return ((long) a << 32) ^ (b & 0xffffffffL);
    }

    @Override
    public AdaptivePacer pacer() {
        return pacer;
    }

    @Override
    public void placed(Placement p) {
        if (listener != null) listener.placed(p);
    }

    @Override
    public void skipped(Placement p, String reason) {
        skipReasons.merge(reason.replaceAll("[0-9.,-]+", "#"), 1, Integer::sum);
        if (listener != null) listener.skipped(p, reason);
    }

    @Override
    public void log(String line) {
        log.add(line);
        if (line.contains(" stuck at ") && stuckViews.size() < 20) {
            List<String> t = new ArrayList<>(trail);
            stuckViews.add(line + (progress == null ? "" : progress.get()) + " lane " + laneFrom + " -> " + laneTo + "\n"
                + view(4) + String.join("\n", t.subList(Math.max(0, t.size() - 12), t.size())));
        }
    }

    /** A view of the world around the bot at each "stuck" line of a run (the first few), with the trail before it. */
    public final List<String> stuckViews = new ArrayList<>();

    /**
     * The cells within {@code r} of the bot, north row first: '@' the bot, '#' a full block at art level, '^' a raised
     * block, '_' a lower block, '.' floor.
     */
    public String view(int r) {
        int fx = (int) Math.floor(x);
        int fz = (int) Math.floor(z);
        StringBuilder sb = new StringBuilder();
        for (int cz = fz - r; cz <= fz + r; cz++) {
            for (int cx = fx - r; cx <= fx + r; cx++) {
                char c = cx == fx && cz == fz ? '@' : raisedSolid(cx, cz) ? '^' : solidAt(cx, cz) ? '#'
                    : art(cx, cz).isAir() ? '.' : '_';
                sb.append(c);
            }
            sb.append(cz == fz ? String.format(" <- z %d (feet y %+.2f)", cz, y - artY) : "").append('\n');
        }
        return sb.toString();
    }

    /** Runs one lane to the end (at most {@code maxTicks}); returns the final status. */
    public TaskStatus runLane(Lane lane, LaneRun.Params params, PlacementListener l, int maxTicks) {
        return runLane(lane, params, l, maxTicks, (t, run) -> true);
    }

    /**
     * {@link #runLane} with {@code control} asked before every world tick {@code t}: false skips the run's tick, as the
     * engine does not tick a parked task (the world goes on: confirmations land, the bot stands). The run's own tick
     * counter only counts the ticks it was ticked, like a task's.
     */
    public TaskStatus runLane(Lane lane, LaneRun.Params params, PlacementListener l, int maxTicks,
                              java.util.function.BiPredicate<Long, LaneRun<Shot>> control) {
        return runLane(lane, params, l, maxTicks, control, List.of());
    }

    /** {@link #runLane} with the later lanes of the load ({@code load}: the in-reach greedy may place their cells). */
    public TaskStatus runLane(Lane lane, LaneRun.Params params, PlacementListener l, int maxTicks, List<Lane> load) {
        return runLane(lane, params, l, maxTicks, (t, run) -> true, load);
    }

    private @Nullable Pos laneFrom;
    private @Nullable Pos laneTo;

    private TaskStatus runLane(Lane lane, LaneRun.Params params, PlacementListener l, int maxTicks,
                               java.util.function.BiPredicate<Long, LaneRun<Shot>> control, List<Lane> load) {
        laneFrom = lane.from();
        laneTo = lane.to();
        begin(lane, l);
        addTargets(load);
        LaneRun<Shot> run = new LaneRun<>(lane, this, params, load);
        progress = () -> " lane " + lane.index() + run.progress();
        long taskTick = 0;
        for (long t = 1; t <= maxTicks; t++) {
            beginTick(t);
            if (!control.test(t, run)) {
                endTick();
                continue;
            }
            TaskStatus s = run.tick(++taskTick);
            endTick();
            advanceMillis((t + 1) * 50 - 1);
            if (s != TaskStatus.RUNNING) {
                run.close();
                if (s == TaskStatus.FAILED) log.add("FAILED: " + run.failure());
                // let the last confirmations land
                for (int i = 0; i <= confirmDelay; i++) beginTick(t + 1 + i);
                lastTicks = t;
                return s;
            }
        }
        run.close();
        lastTicks = maxTicks;
        log.add("TIMEOUT after " + maxTicks + " ticks" + run.progress());
        return TaskStatus.FAILED;
    }

    /** Ticks the last {@link #runLane} took. */
    public long lastTicks;

    private java.util.function.@Nullable Supplier<String> progress;

    /** Where and how the bot stood walled in (first few). */
    public final List<String> pockets = new ArrayList<>();

    /** Last positions (debugging). */
    public final java.util.ArrayDeque<String> trail = new java.util.ArrayDeque<>();
    public String firstPocketTrail = "";

    private void pocket(String how) {
        if (pocketTicks == 0) firstPocketTrail = String.join("\n", trail);
        pocketTicks++;
        if (pockets.size() < 10) {
            pockets.add(how + " at " + (int) Math.floor(x) + "," + (int) Math.floor(z) + " tick " + now
                + (progress == null ? "" : progress.get()));
        }
    }

    /** Feet cell is a floor cell with full blocks on all four sides. */
    public boolean inPocket() {
        return y < artY + 0.5 && walledSides((int) Math.floor(x), (int) Math.floor(z)) == 4;
    }
}
