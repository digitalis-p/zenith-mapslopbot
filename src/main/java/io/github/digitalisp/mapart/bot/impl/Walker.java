package io.github.digitalisp.mapart.bot.impl;

import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.calc.IPath;
import com.zenith.feature.pathfinder.executor.PathExecutor;
import com.zenith.feature.pathfinder.goals.Goal;
import com.zenith.feature.pathfinder.movement.MovementHelper;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.World;
import com.zenith.mc.block.BlockPos;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.INPUTS;

/**
 * Walks with ZenithProxy's Baritone while {@link PathingGuard} holds place/break/inventory off. Takes a list of
 * goals tried in order: when Baritone reports a goal reached but the caller's arrival test is still false (e.g. no
 * line of sight yet), the next, tighter goal is used. A pathing request that completes false (calculation failed,
 * lost control) or stalls is retried up to {@link #MAX_FAILURES} times per goal (or the caller's number) with a short
 * back-off, then the walk fails. Owned by one task; {@link #stop()} must be called on release.
 *
 * <p><b>No-go zones</b> ({@link NoGoZones}, {@code site.noGoZones}): Baritone gets each goal {@linkplain
 * NoGoZones#avoiding without the cells in a zone}; a goal lying wholly inside zones is skipped (the walk fails when no
 * goal is left); every path Baritone computes (current and next segment) is checked before it is walked, and a path
 * that enters a zone stops Baritone at once and fails the walk (no retry: the same path would come back). The walk
 * does not count as arrived while the feet are in a zone. A bot that starts inside a zone may walk out of it.
 * One exception ({@code build.noGoCornerDetour}): a path whose fault is a move between two free cells that cuts a
 * zone's corner is planned again around it ({@link NoGoZones#cornerFix}): the move's end looking far, or a walk through
 * the step's free side cell first, at most {@link #MAX_CORNER_DETOURS} times per goal list, so Baritone takes the
 * legal way round (live 2026-10-13: drop walks and a lane start on the soul sand platform failed on such diagonals
 * past the turnaround station's zone).
 *
 * <p><b>Border tops</b> ({@link #border}, {@code build.laneStartOnFloor}): the owner may give a walk the tile's border
 * tops ({@link BorderTop}), which Baritone's goal then treats like a detour cell that is never in the goal: A* takes
 * them only when nothing else gets there. Walks to a lane start.
 *
 * <p><b>Path checks</b> ({@link #pathCheck}): the owner may add its own rule for the cells of every path (feet cells,
 * in order), e.g. "no climbing onto the art layer's blocks" for the lane's stand spots and the setback escape. A path
 * it refuses is handled like one entering a no-go zone: Baritone stops and the walk FAILS with the check's reason.
 *
 * <p><b>Goal churn</b> ({@link GoalChurn}): a caller whose goal changes again and again while the bot does not move (each
 * change restarts the path, and with it the stall timer) gets the walk FAILED after {@link GoalChurn#LIMIT} changes.
 *
 * <p><b>Boxed in</b> ({@code build.boxedInEscape}, {@link #boxedIn}): a failed path whose start reaches fewer than
 * {@link LocalReach#BOXED_IN_MOVEMENTS} movements of ZenithProxy's own move set means no goal can be reached from here.
 * Once per walk the bot then walks back along its own recent track ({@link #TRACK}), which led in, with its own keys,
 * and paths again from the first track cell outside the spot. A way in that was a drop of two blocks or more cannot be
 * walked back: the usual retries go on and the walk's failure ends with "; boxed in at ...: the way in was a drop of N
 * blocks ...".
 *
 * <p><b>Step out</b> ({@code build.walkStepOut}, {@link StepOut}): before each path request, a bot on the ground whose
 * feet cell holds a block the pathfinder cannot walk through that rises above the feet ({@link StepOut#wedged}: pointed
 * dripstone, the box can stand partly in its cell; not a bottom slab the bot stands on) first steps with its own keys into the nearest neighbour cell it can stand in (not in a no-go zone), at most
 * {@link StepOut#MAX_TRIES} times per goal list and {@link StepOut#MAX_TICKS} ticks each. The pathfinder plans from the
 * feet cell, and from such a cell its first movement pushes into the block for ever.
 *
 * <p><b>Sprint</b> ({@link #sprint}): the owner gives the walk its {@link SprintLegs.Rule} each tick ({@link
 * SprintLegs#rule}: a restock leg, a plain walk, or none); Baritone's allowSprint is set to whether the rule allows it
 * now (hunger, the walk's age, the distance left to the goal), and to false for every walk without one
 * ({@link PathingGuard#sprint}). A walk is everything from its first tick to {@link #stop}: a goal that changes while
 * the bot walks (a chase after a moving mob) keeps the walk's one-sprint latch and age ({@code build.sprintOnce}), so
 * the sprint does not start again mid-walk.
 */
final class Walker {
    static final int MAX_FAILURES = 3;
    static final int STALL_TICKS = 200;
    /** Ticks one step back along the track may take ({@link #tickBack}). */
    static final int BACK_STEP_TICKS = 40;
    /** Plans per goal list made again around the end of a move that cut a no-go zone's corner. */
    static final int MAX_CORNER_DETOURS = 4;
    /** The session's track (feet cells stood on), noted every bot tick ({@link #noteTrack}). Client tick thread. */
    static final TrackBack TRACK = new TrackBack();
    /**
     * The newest boxed-in check that found the way in was a drop it cannot climb back (live 2026-10-27: 4 blocks into a
     * dry 1x1 shaft beside the sorter pickup stand); the engine takes it ({@code takeBoxedInDrop}) and starts the fall
     * rescue. Client tick thread.
     */
    static volatile io.github.digitalisp.mapart.bot.@Nullable BoxedInDrop boxedDrop;

    private final NoGoZones zones;
    /** {@code build.boxedInEscape}: walk back along the track when boxed in. */
    private final BooleanSupplier escape;
    /** {@code build.noGoCornerDetour}: plan again around a move that cuts a zone's corner. */
    private final BooleanSupplier cornerDetour;
    /** {@code build.edgePlannedFalls}: a planned fall of more than 3 blocks is never walked ({@link EdgeDrop#longFall}). */
    private BooleanSupplier plannedFalls = () -> false;
    /** {@code build.walkStepOut}: step out of a feet cell the pathfinder cannot start from first ({@link StepOut}). */
    private final BooleanSupplier stepOut;
    /** The step out running: the cell stepped into, or null; the cell left, the tick it began, and the tries. */
    private @Nullable Pos stepTo;
    private @Nullable Pos stepFrom;
    private long stepStart;
    private int stepTries;
    /** Cells made to look far for this goal list: ends of moves that cut a zone's corner. */
    private final Set<Pos> detour = new LinkedHashSet<>();
    /** The free side cell of a corner-cutting step, walked to before the goal ({@link NoGoZones.CornerFix#via}), or null. */
    private @Nullable GoalNearXZ via;
    /** A walk through a side cell was planned for this goal list (once). */
    private boolean viaTried;
    /** The walk back out of a boxed-in spot: the steps left, or null. */
    private @Nullable List<Pos> back;
    private int backIndex;
    private long backStepStart;
    /** "boxed in at ..." of the walk back, for its log lines. */
    private String boxedAt = "";
    /** The boxed-in check ran for this goal list (once per walk). */
    private boolean backTried;
    /** "; boxed in at ...: why there is no way back", appended to the walk's failure; "" otherwise. */
    private String boxedNote = "";
    private RefCountedOverride.@Nullable Handle guard;
    private @Nullable PathingRequestFuture future;
    private @Nullable Goal goal;
    private int goalIndex;
    private List<? extends Goal> goals = List.of();
    private int failures;
    private int retries = MAX_FAILURES;
    private long retryAt;
    private long progressTick;
    private double px;
    private double py;
    private double pz;
    private String error = "";
    /** The zone the bot stood in when this walk (goal list) began: walking out of it is allowed. */
    private NoGoZones.@Nullable Zone leaving;
    /** Paths already checked against the zones (identity). */
    private @Nullable IPath checkedCurrent;
    private @Nullable IPath checkedNext;
    /**
     * The walk whose paths {@link #checkBeforeInputs} checks in the tick Baritone planned them, before its keys apply;
     * and what it found for the walk's next tick. Client tick thread.
     */
    private static @Nullable Walker zoneWalker;
    private NoGoZones.@Nullable Violation preInputViolation;
    /** The owner's own path rule ({@link #pathCheck}), or null. */
    private @Nullable Function<List<Pos>, @Nullable String> pathCheck;
    private final GoalChurn churn = new GoalChurn();
    /** Path requests around one spot ({@link PathChurn}): a walk that turns back and forth is given up. */
    private final PathChurn paths = new PathChurn();
    /** The sprint rule of this walk (a restock leg, a plain walk), or null: no sprint. */
    private SprintLegs.@Nullable Rule sprintRule;
    /** What the walk is ({@link SprintLegs.Walk}, for the log line). */
    private String sprintKind = "";
    /** Tick the current goal list began (the sprint rule's walk age). */
    private long walkStart;
    /** Whether this walk let Baritone sprint on its last tick. */
    private boolean sprinting;
    private boolean diagSprinting;
    /** The walk whose one-sprint latch {@link #beforeBaritone} applies (one walk at a time). */
    private static volatile @Nullable Walker sprintWalker;
    private boolean diagAllowed;
    private long diagStart;
    private final SprintLegs.Once once = new SprintLegs.Once();
    /** Ticks walked with a sprint rule, and of those the ticks the bot sprinted (one log line per sprinting walk). */
    private int legTicks;
    private int legSprintTicks;
    /** The run row ({@link RowRun}): the config of this tick, the walk's plan (null: none), and its progress. */
    private java.util.function.Supplier<RowRun.Cfg> rowCfg = () -> RowRun.Cfg.OFF;
    private RowRun.@Nullable Plan rowPlan;
    private @Nullable GoalNearXZ rowVia;
    private RowRun.@Nullable Controller rowCtl;
    private boolean rowAlong;
    private long rowStartTick;
    private double rowStartX;
    private double rowLastX = Double.NaN;
    /** Edge stops ({@code build.edgeSneak}, {@link EdgeApproach}); off unless the owner sets one. */
    private EdgeApproach edge = EdgeApproach.OFF;
    /** The tile's border tops for this walk ({@link #border}), or null. */
    private @Nullable BorderTop border;
    /** The walk's elytra flight ({@link FlightLeg}, {@code build.elytraLegs}), or null: every walk on foot. */
    private @Nullable FlightLeg flight;
    /** The tick of the last {@link #tick} (the flight leg's log line when the walk stops). */
    private long lastNow;
    /**
     * The start-up of a walk, for its one log line ("Walk start"): the yaw unwind before
     * the walk ({@link #noteUnwind}), the tick Baritone was asked, the tick it was pathing, the tick the feet first
     * moved. -1: not noted.
     */
    private int unwindTicks;
    private float unwindFrom;
    private long requestTick = -1;
    private long pathingTick = -1;
    private double startX;
    private double startZ;
    private float startYaw;

    Walker() {
        this(NoGoZones.NONE);
    }

    Walker(NoGoZones zones) {
        this(zones, () -> false);
    }

    Walker(NoGoZones zones, BooleanSupplier escape) {
        this(zones, escape, () -> false);
    }

    Walker(NoGoZones zones, BooleanSupplier escape, BooleanSupplier cornerDetour) {
        this(zones, escape, cornerDetour, () -> false);
    }

    Walker(NoGoZones zones, BooleanSupplier escape, BooleanSupplier cornerDetour, BooleanSupplier stepOut) {
        this.zones = zones;
        this.escape = escape;
        this.cornerDetour = cornerDetour;
        this.stepOut = stepOut;
    }

    /** Notes this tick's feet cell in {@link #TRACK} (every bot tick, whatever runs). Client tick thread. */
    static void noteTrack() {
        TRACK.note(feet(), BOT.isOnGround());
    }

    String error() {
        return error;
    }

    /** The edge stops of this walker's walks ({@link EdgeApproach}). */
    void edge(EdgeApproach e) {
        this.edge = e;
    }

    /**
     * Sets (null: clears) the tile's border tops for the walk from now on ({@link BorderTop}, {@code
     * build.laneStartOnFloor}): Baritone's goal gets them as cells that look far and are never in it ({@link
     * NoGoZones#avoiding(Goal, Set, BorderTop)}), from the next path request on. The owner sets it before each tick of
     * a walk to a lane start and clears it when that walk ends.
     */
    void border(@Nullable BorderTop b) {
        this.border = b;
    }

    /**
     * Stops, and forgets the goal list: the next walk starts at its first goal even with a list equal to the last one
     * ({@code build.laneStartOnFloor}). Without it a walk that FAILED keeps its list and its goal index past the end,
     * and a later walk with the same list (the lane run started again after the break walk to its start arrived at
     * once: the same start, the same two goals) fails at its first tick with "no walking goal left".
     */
    void forget() {
        stop();
        goals = List.of();
        goalIndex = 0;
        failures = 0;
        retryAt = 0;
    }

    boolean active() {
        return future != null || guard != null;
    }

    /**
     * An elytra flight of this walk is busy (its gear, the rockets into the hand, in the air, the chestplate back on):
     * the walk's own placements wait ({@code PlaceLaneTask.clickWhileWalking}). Live 2026-10-25..27: 9
     * LANE_START take-offs ended without a boost, "the main hand holds minecraft:black_carpet, not the leg's rockets":
     * the in-reach greedy airplaced the next lane's cells from the hotbar while the flight stood and took off.
     */
    boolean flightBusy() {
        return flight != null && flight.busy();
    }

    /** The run row of this walker's walks ({@link RowRun}); the default has none. */
    void rowRun(java.util.function.Supplier<RowRun.Cfg> cfg) {
        this.rowCfg = cfg;
    }

    /** The elytra flight legs of this walker's walks ({@link FlightLeg}); the default has none. */
    void flight(@Nullable FlightLeg f) {
        this.flight = f;
    }

    /** This walker's flight leg, or null. */
    @Nullable FlightLeg flight() {
        return flight;
    }

    /** {@link #landFirst} waits at most this many ticks for one touch-down (the flight's own limit is 600, then BAIL). */
    static final int LAND_FIRST_MAX = 900;
    private int landHeld;
    private boolean landNoted;

    /**
     * Before the task's step: a flight of this walk ({@link FlightLeg#inFlight}) flies on to its touch-down and its
     * stop (its keys this tick) and the step waits, so nothing the task does can end the walk in the air. Live
     * 2026-10-25: a far-storage CONTAINER leg took off on the way; the task's own goal check (within 4 of the
     * chest, feet at the storage floor) passed in the glide and the task stopped the walk: the flight bailed out and
     * the bot walked 5 blocks back (locally twice, 11 blocks). RUNNING while it waits; null when there is nothing to
     * wait for, or after {@link #LAND_FIRST_MAX} ticks of one wait (WARN; the step goes on as before).
     */
    @Nullable TaskStatus landFirst(long now) {
        if (flight == null || !flight.inFlight()) {
            landHeld = 0;
            landNoted = false;
            return null;
        }
        if (landHeld >= LAND_FIRST_MAX) {
            if (landHeld++ == LAND_FIRST_MAX)
                ZenithTask.LOG.warn("Elytra leg: still flying after the task waited {} ticks for the touch-down; the task goes on",
                    LAND_FIRST_MAX);
            return null;
        }
        landHeld++;
        if (!landNoted && !goals.isEmpty() && flight.aloft()) {
            Pos f = feet();
            if (goals.getLast().isInGoal(f.x(), f.y(), f.z())) {
                // where the walk would have ended in the air before (the live case); once a wait
                landNoted = true;
                ZenithTask.LOG.info("Elytra leg: in the walk's goal while still in the air ({} ticks into the flight's wait);"
                    + " the task waits for the touch-down", landHeld);
            }
        }
        lastNow = now;
        if (guard == null) guard = PathingGuard.acquire();
        else PathingGuard.enforce();
        flight.tick(now);
        markProgress(now);
        if (!flight.busy()) {
            walkStart = now;
            once.reset();
        }
        return TaskStatus.RUNNING;
    }

    /**
     * Sets (null: clears) a rule every planned path must pass, besides the no-go zones: given the path's feet cells in
     * order, it returns why the path must not be walked (e.g. "climbs onto a block at x,y,z"), or null. A refused path
     * stops Baritone and FAILS the walk with "the path to GOAL REASON; not walking it". Paths already checked are
     * checked again under a new rule.
     */
    /** Sets the switch of the planned-fall rule ({@code build.edgePlannedFalls}). */
    void plannedFalls(BooleanSupplier on) {
        this.plannedFalls = on;
    }

    void pathCheck(@Nullable Function<List<Pos>, @Nullable String> check) {
        if (check != pathCheck) {
            checkedCurrent = null;
            checkedNext = null;
        }
        pathCheck = check;
    }

    /**
     * The sprint rule for this walk from now on ({@link SprintLegs#rule}), or null (the default) for no sprint, and
     * what the walk is ({@code kind}, for the log line). Set before each {@link #tick}; a change takes effect on that
     * tick.
     */
    void sprint(SprintLegs.@Nullable Rule rule, String kind) {
        this.sprintRule = rule;
        this.sprintKind = kind;
    }

    /** Whether this walk let Baritone sprint on its last tick (tests, logs). */
    boolean sprinting() {
        return sprinting;
    }

    TaskStatus tick(long now, Goal goal, BooleanSupplier arrived) {
        return tick(now, List.of(goal), arrived);
    }

    TaskStatus tick(long now, List<? extends Goal> goalList, BooleanSupplier arrived) {
        return tick(now, goalList, arrived, MAX_FAILURES);
    }

    /** {@link #tick(long, List, BooleanSupplier)} giving up after {@code maxRetries} + 1 failed paths per goal. */
    TaskStatus tick(long now, List<? extends Goal> goalList, BooleanSupplier arrived, int maxRetries) {
        retries = Math.max(0, maxRetries);
        lastNow = now;
        NoGoZones.Zone here = zones.isEmpty() ? null : zones.at(feet());
        boolean flying = flight != null && flight.busy(); // in the air or swapping gear: no arrival until it is down
        if (here == null && !flying && arrived.getAsBoolean()) {
            if (edge.settling(now)) { // beside a drop and still moving: sneak until it stands still (EdgeApproach)
                stopPath();
                return TaskStatus.RUNNING;
            }
            if (flight != null) flight.walkEnded(now, true);
            paths.reset(); // arrived: a task of many short walks around one spot is no churn
            stop();
            return TaskStatus.SUCCESS;
        }
        if (!goalList.equals(goals)) {
            // the goal moved while the bot walks (a chase): the same walk, its sprint latch and age go on
            boolean walking = guard != null;
            stopPath();
            if (!goals.isEmpty() && churn.changed(now, BOT.getX(), BOT.getZ())) {
                error = "the walking goal changed " + churn.changes() + " times in " + churn.span(now)
                    + " ticks without the bot moving (last " + goals.getFirst() + ", now " + goalList.getFirst() + ")";
                churn.reset();
                goals = List.of();
                stop();
                return TaskStatus.FAILED;
            }
            goals = List.copyOf(goalList);
            goalIndex = 0;
            failures = 0;
            retryAt = 0;
            leaving = here;
            if (!walking) {
                walkStart = now;
                once.reset();
            }
            back = null;
            backTried = false;
            boxedNote = "";
            stepTo = null;
            stepTries = 0;
            edge.reset();
            detour.clear();
            jumpDetoured = false;
            via = null;
            viaTried = false;
            endRow();
            if (!walking) startRow(goalList);
            if (!walking && flight != null && flight.start(now, goalList, zones, border)) endRow(); // flies first
        }
        if (here == null) leaving = null; // out of it: going back in counts as entering
        if (goals.isEmpty()) {
            error = "no walking goal";
            stop();
            return TaskStatus.FAILED;
        }
        boolean botSprintingNow = BOT.isSprinting();
        if (sprintRule != null) {
            if (botSprintingNow && !diagSprinting) diagStart = now;
            if (!botSprintingNow && diagSprinting && diagAllowed) {
                // not the rule's stop (stop distance, hunger): Baritone's or the Bot's own (a wall bump), mid-walk
                ZenithTask.LOG.info("Sprint stopped by the walk itself after {} ticks: distance left {}, collision {}, input {}",
                    now - diagStart, goals.isEmpty() || goalIndex >= goals.size()
                        || !(goals.get(goalIndex) instanceof GoalNearXZ g0) ? "?"
                        : String.format("%.2f", SprintLegs.distanceLeft(g0, BOT.getX(), BOT.getZ())),
                    SprintDiag.collision(), SprintDiag.input());
            }
            diagSprinting = botSprintingNow;
        }
        sprinting = sprintNow(now);
        if (sprintRule != null && sprintRule.once()) {
            sprinting = once.allow(sprinting, BOT.isSprinting(), (int) Math.floor(BOT.getX()), (int) Math.floor(BOT.getZ()),
                SprintLegs.Watch.SESSION.ticks(), sprintRule.restartTicks(), sprintRule.lateStart());
        }
        PathingGuard.sprint(sprinting);
        diagAllowed = sprinting;
        if (sprintRule != null && sprintRule.once()) sprintWalker = this;
        if (sprintRule != null) {
            legTicks++;
            if (BOT.isSprinting()) legSprintTicks++;
        }
        if (guard == null) guard = PathingGuard.acquire();
        else PathingGuard.enforce();
        if (flight != null && flight.busy()) {
            flight.tick(now);
            markProgress(now);
            if (!flight.busy()) {
                // down: the walk on from here is a walk of its own for the sprint rule (age, one-sprint latch)
                walkStart = now;
                once.reset();
            }
            return TaskStatus.RUNNING;
        }
        if (back != null) return tickBack(now);
        if (stepTo != null) return tickStep(now);
        if (rowAlong) return tickRow(now);

        if (future == null) {
            if (now < retryAt) return TaskStatus.RUNNING;
            if (startStep(now)) return TaskStatus.RUNNING;
            NoGoZones.Zone inside = null;
            while (goalIndex < goals.size() && goals.get(goalIndex) instanceof GoalNearXZ g
                && (inside = zones.covering(g)) != null) goalIndex++;
            if (goalIndex >= goals.size()) {
                error = inside != null ? "the goal " + goals.getLast() + " lies inside no-go zone " + inside
                    : "no walking goal left";
                stop();
                return TaskStatus.FAILED;
            }
            if (paths.asked(now, BOT.getX(), BOT.getZ())) {
                // path after path and the bot still around one spot (live 2026-11-15: 400 paths in 4.5 minutes, west
                // and east in turn; every step counted as progress): the caller's fallback instead
                error = "pathing to " + goals.get(goalIndex) + ": " + paths.asks() + " paths asked for in "
                    + paths.span(now) + " ticks with the bot within " + (int) PathChurn.RADIUS
                    + " blocks of one spot (the walk turns back and forth)";
                paths.reset();
                stop();
                return TaskStatus.FAILED;
            }
            goal = zones.avoiding(via != null ? via : goals.get(goalIndex), detour, border);
            future = BARITONE.pathTo(goal);
            // from this tick on: Baritone may plan and take the path's first movement in this very tick (live
            // 2026-10-16 05:08, Galileo [8,1]: the first step of a walk from the east lane end went onto the parked
            // machines again, because the walk was registered for checkBeforeInputs only a tick later)
            zoneWalker = this;
            edge.arm(this::plannedRoute); // its first keys too (after a login: the first ticks of a walk)
            if (requestTick < 0) {
                requestTick = now;
                pathingTick = -1;
                startX = BOT.getX();
                startZ = BOT.getZ();
                startYaw = BOT.getYaw();
            }
            markProgress(now);
            return TaskStatus.RUNNING;
        }
        watchStart(now);
        zoneWalker = this;
        NoGoZones.Violation entered = preInputViolation != null ? preInputViolation : plannedPathEntersZone();
        preInputViolation = null;
        if (entered != null) {
            BARITONE.getPathingBehavior().forceCancel();
            EdgeApproach.halt(); // Baritone's keys of this tick were already submitted
            future = null;
            Goal inner = goals.get(goalIndex);
            NoGoZones.CornerFix fix = cornerFix(entered, inner, feet());
            if (fix != null) {
                int n = detour.size() + (viaTried ? 1 : 0) + 1;
                if (fix.avoid() != null) {
                    detour.add(fix.avoid());
                    ZenithTask.LOG.info("the path to {} {} ({}); planning again around {} ({} of {})",
                        inner, entered.text(), detourWhy(entered), fix.avoid().key(), n, MAX_CORNER_DETOURS);
                } else if (fix.via() == null) {
                    // EdgeApproach.WAIVE: the hole corners of this walk are walked under the per-tick hole guard
                    ZenithTask.LOG.info("the path to {} {} ({}); the side cell was tried, walking it with the edge guard",
                        inner, entered.text(), detourWhy(entered));
                } else {
                    via = fix.via();
                    viaTried = true;
                    ZenithTask.LOG.info("the path to {} {} ({}); walking through {},{},{} first ({} of {})", inner,
                        entered.text(), detourWhy(entered), via.x(), via.y(), via.z(), n, MAX_CORNER_DETOURS);
                }
                markProgress(now);
                return TaskStatus.RUNNING;
            }
            int tried = detour.size() + (viaTried ? 1 : 0);
            error = "the path to " + inner + " " + entered.text() + "; not walking it"
                + (tried == 0 ? "" : " (planned again around the corner " + tried + " time" + (tried == 1 ? "" : "s")
                + " first)");
            stop();
            return TaskStatus.FAILED;
        }
        if (future.isCompleted()) {
            boolean reached = future.isAccepted();
            future = null;
            if (reached && via != null && via == rowVia && rowPlan != null) {
                // on the row at the start x: along it with the plugin's own keys
                rowAlong = true;
                rowCtl = new RowRun.Controller(rowPlan, 0.3);
                rowStartTick = now;
                rowStartX = BOT.getX();
                rowLastX = BOT.getX();
                via = null;
                failures = 0;
                return TaskStatus.RUNNING;
            }
            if (reached && via != null) {
                via = null; // through the side cell: on to the goal itself
                failures = 0;
                return TaskStatus.RUNNING;
            }
            if (reached) {
                // Baritone is in the goal but the caller's test failed: try the next, tighter goal
                if (++goalIndex >= goals.size()) {
                    error = here != null
                        ? "reached " + goal + " but the feet are in no-go zone " + here
                        : "reached " + goal + " but the arrival test still fails (target out of reach or sight)";
                    stop();
                    return TaskStatus.FAILED;
                }
                failures = 0;
                return TaskStatus.RUNNING;
            }
            if (via != null && via == rowVia && rowPlan != null) {
                ZenithTask.LOG.info("Row run: the way to the row {} failed; straight to the goal", via);
                endRow();
                via = null;
                failures = 0;
                return TaskStatus.RUNNING;
            }
            TaskStatus boxed = boxedIn(now);
            if (boxed != null) return boxed;
            return failed(now, "pathing to " + goal + " failed");
        }
        if (flight != null && here == null && via == null && flight.onTheWay(now, this::plannedRoute)) {
            // build.elytraOnTheWayBlocks: a flight passes from here; the leg stops the bot and takes off
            stopPath();
            markProgress(now);
            return TaskStatus.RUNNING;
        }
        edge.walking(remainingPath());
        edge.arm(this::plannedRoute); // the hole guard on Baritone's keys of this tick (EdgeApproach.applyToBaritone)
        String refused = edge.refusals.tick(now, edge.takeRefusal(), BOT.getX(), BOT.getZ());
        if (refused != null) return jumpRefused(now, refused);
        double moved = Aim.distSq(BOT.getX(), BOT.getY(), BOT.getZ(), px, py, pz);
        if (moved > 0.25 * 0.25) {
            markProgress(now);
        } else if (now - progressTick > STALL_TICKS) {
            stopPath();
            return failed(now, "pathing to " + goal + " stalled");
        }
        return TaskStatus.RUNNING;
    }

    /**
     * The hole guard took this walk's jump away for {@link EdgeApproach.RefusalWatch#TICKS} ticks while the bot stood
     * still ({@code why}: the last refusal). Once a goal list, the path is planned again with the jump's end (the
     * path's next cell up) looking far; else the walk fails at once, without retries (they would stall the same way),
     * so the caller's fallback runs. Live 2026-10-29 15:39-15:41: an ascend beside a drop of more than 3 blocks on the
     * walk to the map station, refused for 2 minutes until the "No progress" alert.
     */
    private TaskStatus jumpRefused(long now, String why) {
        Pos feet = feet();
        List<Pos> route = plannedRoute();
        Pos up = route == null ? null : EdgeApproach.RefusalWatch.jumpEnd(route, feet);
        Goal inner = goals.get(Math.min(goalIndex, goals.size() - 1));
        String way = plainWay(sprintKind);
        EdgeApproach.troubled(way);
        long secs = EdgeApproach.RefusalWatch.TICKS / 20;
        if (EdgeApproach.RefusalWatch.replan(jumpDetoured, up, detour.size(), MAX_CORNER_DETOURS, feet,
            up != null && inner.isInGoal(up.x(), up.y(), up.z()))) {
            jumpDetoured = true;
            BARITONE.getPathingBehavior().forceCancel();
            EdgeApproach.halt();
            future = null;
            detour.add(up);
            ZenithTask.LOG.info("the edge guard refused the jump at {} for {} s ({}) {}; planning again around {}",
                feet.key(), secs, why, way, up.key());
            markProgress(now);
            return TaskStatus.RUNNING;
        }
        error = "the edge guard refused a jump beside a hole at " + feet.key() + " for " + secs + " s (" + why + ")"
            + (jumpDetoured ? "; planned again around the jump once" : "");
        ZenithTask.LOG.info("the walk to {} hands back: {}", inner, error);
        stop();
        return TaskStatus.FAILED;
    }

    /** This walk planned again around a jump the hole guard kept taking away ({@link #jumpRefused}); per goal list. */
    private boolean jumpDetoured;

    /** "on the way to the map station" for a walk kind's name ({@link SprintLegs.Walk}, as {@link #sprint} gets it). */
    static String plainWay(String kind) {
        String k = kind == null ? "" : kind;
        int comma = k.indexOf(',');
        if (comma >= 0) k = k.substring(0, comma);
        return switch (k) {
            case "MAP_STATION" -> "on the way to the map station";
            case "CONTAINER" -> "on the way to a chest";
            case "LANE_START", "STAND_SPOT", "LANE_BREAK_OUT" -> "on the way to a lane";
            case "DROP_POINT" -> "on the way to the drop point";
            case "BREAK_TRANSIT" -> "between blocks it breaks";
            case "BUTTON" -> "on the way to a button";
            case "PLACE" -> "on the way to a placement";
            case "CART" -> "on the way to the cart rail";
            default -> "on a walk";
        };
    }

    // ---- the run row (RowRun)

    private void endRow() {
        rowPlan = null;
        rowVia = null;
        rowCtl = null;
        rowAlong = false;
        rowLastX = Double.NaN;
    }

    /** Plans the walk's run along the row when its start and goal are in the storage band: Baritone to the row first. */
    private void startRow(List<? extends Goal> goalList) {
        RowRun.Cfg c = rowCfg.get();
        if (c.rows().isEmpty() || goalList.isEmpty() || !(goalList.getFirst() instanceof GoalNearXZ g)) return;
        RowRun.Plan p = RowRun.plan(c.rows(), BOT.getX(), BOT.getY(), BOT.getZ(), g.x(), g.z(), c.band(), c.minDx());
        if (p == null) return;
        rowPlan = p;
        rowVia = new GoalNearXZ(p.start().x(), p.start().y(), p.start().z(), 0, 1);
        via = rowVia;
        ZenithTask.LOG.info("Row run: to the row z {} at x {}, then along it to x {} (goal {})", p.rowZ(), p.start().x(),
            p.goalX(), g);
    }

    /** One tick of the key walk along the row; RUNNING, or the walk goes on to Baritone's path to the goal. */
    private TaskStatus tickRow(long now) {
        double x = BOT.getX();
        double vx = Double.isNaN(rowLastX) ? 0 : x - rowLastX;
        RowRun.Plan p = rowPlan;
        String abort = null;
        if (Math.abs(vx) > 1.5) abort = "moved unexpectedly (a setback?)";
        else if (!zones.isEmpty()) {
            int ahead = (int) Math.floor(x) + 2 * p.dir();
            if (zones.covering(new GoalNearXZ(ahead, p.rowY(), p.rowZ(), 0, 0)) != null) abort = "a no-go zone ahead";
        }
        RowRun.Keys k = abort != null ? new RowRun.Keys(false, 0, 0, false, abort)
            : rowCtl.tick(x, BOT.getZ(), vx);
        rowLastX = x;
        if (k.abort() != null) {
            ZenithTask.LOG.info("Row run: stopped at x {} ({}); straight to the goal", String.format("%.1f", x), k.abort());
            endRow();
            return TaskStatus.RUNNING;
        }
        if (k.done()) {
            double secs = Math.max(1, now - rowStartTick) / 20.0;
            double d = Math.abs(x - rowStartX);
            ZenithTask.LOG.info("Row run x {} -> {} in {} s = {} b/s", Math.round(rowStartX), Math.round(x),
                String.format("%.1f", secs), String.format("%.1f", d / secs));
            endRow();
            return TaskStatus.RUNNING;
        }
        Input in = Input.builder().pressingForward(k.forward()).pressingRight(k.strafeRight() > 0)
            .pressingLeft(k.strafeRight() < 0).sprinting(sprinting && k.forward()).build();
        INPUTS.submit(InputRequest.builder().owner(this).priority(Clicker.PRIORITY).input(in).yaw(k.yaw()).pitch(0).build());
        return TaskStatus.RUNNING;
    }

    /**
     * After a failed path, once per walk with {@code build.boxedInEscape}: if the pathfinder's own moves reach fewer than
     * {@link LocalReach#BOXED_IN_MOVEMENTS} movements from the feet (boxed in), the bot walks back along its own track
     * ({@link TrackBack}): RUNNING while it does. Null: not boxed in, the check is off or done, or the track cannot be
     * walked back (the way in was a drop, the bot was moved): the usual retries go on, and the walk's failure says why.
     */
    private @Nullable TaskStatus boxedIn(long now) {
        if (backTried || !escape.getAsBoolean()) return null;
        backTried = true;
        Pos at = feet();
        LocalReach.Result r = LocalReach.explore(at, LocalReach.zenith(), LocalReach.BOXED_IN_MOVEMENTS);
        if (!r.boxedIn()) return null;
        boxedAt = "boxed in at " + at.key() + " (" + r.cells().size() + " cells, " + r.movements() + " movements)";
        TrackBack.Plan plan = TrackBack.plan(TRACK.newestFirst(), at,
            c -> !r.cells().contains(c) && LocalReach.resumable(c));
        if (plan.steps() == null) {
            boxedNote = "; " + boxedAt + ": " + plan.problem();
            ZenithTask.LOG.warn("{}: {}", boxedAt, plan.problem());
            Pos from = plan.dropFrom();
            if (from != null) boxedDrop = new io.github.digitalisp.mapart.bot.BoxedInDrop(at, from, from.y() - at.y());
            return null;
        }
        back = plan.steps();
        backIndex = 0;
        backStepStart = now;
        ZenithTask.LOG.warn("{}: walking back along its own track to {} ({} steps)", boxedAt, back.getLast().key(),
            back.size());
        return TaskStatus.RUNNING;
    }

    /**
     * One tick of the walk back: forward toward the centre of the next track cell, jumping when it is higher; the next
     * step once the feet stand in it. After the last step Baritone paths to the goal again (failures reset). A step that
     * takes {@link #BACK_STEP_TICKS} ends the walk back; the usual retries go on from where the bot stands.
     */
    private TaskStatus tickBack(long now) {
        List<Pos> steps = back;
        Pos at = feet();
        int reached = steps.lastIndexOf(at); // a step walked past in one tick counts too
        if (reached >= backIndex && BOT.isOnGround()) {
            backIndex = reached;
            if (++backIndex >= steps.size()) {
                ZenithTask.LOG.info("{}: walked back out to {}; pathing again", boxedAt, at.key());
                back = null;
                failures = 0;
                retryAt = now;
                return TaskStatus.RUNNING;
            }
            backStepStart = now;
        }
        Pos target = steps.get(backIndex);
        if (now - backStepStart > BACK_STEP_TICKS) {
            boxedNote = "; " + boxedAt + ": the step back from " + at.key() + " to " + target.key() + " took over "
                + BACK_STEP_TICKS + " ticks";
            ZenithTask.LOG.warn("{}", boxedNote.substring(2));
            back = null;
            return failed(now, "pathing to " + goal + " failed");
        }
        float yaw = (float) Math.IEEEremainder(
            Math.toDegrees(Math.atan2(target.z() + 0.5 - BOT.getZ(), target.x() + 0.5 - BOT.getX())) - 90, 360);
        Input in = Input.builder().pressingForward(true).jumping(target.y() > at.y() && BOT.isOnGround()).build();
        INPUTS.submit(InputRequest.builder().owner(this).priority(Clicker.PRIORITY).input(in).yaw(yaw).pitch(0).build());
        return TaskStatus.RUNNING;
    }

    /**
     * Before a path request ({@code build.walkStepOut}, {@link StepOut}): on the ground with the feet in a cell the
     * pathfinder cannot walk through, the step out into the nearest neighbour cell the bot can stand in begins (true).
     * False: nothing to step out of, the switch is off, the tries are used up, or no neighbour can be stood in.
     */
    private boolean startStep(long now) {
        if (!stepOut.getAsBoolean() || stepTries >= StepOut.MAX_TRIES || !BOT.isOnGround()) return false;
        Pos at = feet();
        boolean through = MovementHelper.canWalkThrough(at.x(), at.y(), at.z());
        if (through) return false;
        BlockSpec spec = BlockStates.spec(World.getBlockStateId(at.x(), at.y(), at.z()));
        if (!StepOut.wedged(BOT.getY(), at.y(), BlockStates.collisionTop(spec), through)) return false;
        stepTries++;
        String block = spec.id();
        Pos to = StepOut.target(BOT.getX(), BOT.getZ(), at, this::standable);
        if (to == null) {
            ZenithTask.LOG.warn("Step out: the feet stand in {} ({}), which the pathfinder cannot start from; no cell "
                + "beside it to stand in, pathing from here", at.key(), block);
            return false;
        }
        stepTo = to;
        stepFrom = at;
        stepStart = now;
        ZenithTask.LOG.info("Step out: the feet stand in {} ({}), which the pathfinder cannot start from; stepping into {}",
            at.key(), block, to.key());
        return true;
    }

    /** A cell the step out may end in: the pathfinder walks through it and the cell above, on its floor, no zone. */
    private boolean standable(Pos c) {
        return MovementHelper.canWalkThrough(c.x(), c.y(), c.z()) && MovementHelper.canWalkThrough(c.x(), c.y() + 1, c.z())
            && MovementHelper.canWalkOn(c.x(), c.y() - 1, c.z()) && (zones.isEmpty() || zones.at(c) == null);
    }

    /**
     * One tick of the step out: forward toward {@link StepOut#aim} in the target cell, no keys once the feet stand in a
     * cell the pathfinder can start from (the next tick paths). Over {@link StepOut#MAX_TICKS}: pathing from where the
     * bot stands.
     */
    private TaskStatus tickStep(long now) {
        Pos at = feet();
        Pos to = stepTo;
        if (BOT.isOnGround() && !at.equals(stepFrom) && MovementHelper.canWalkThrough(at.x(), at.y(), at.z())) {
            ZenithTask.LOG.info("Step out: in {} after {} ticks; pathing", at.key(), now - stepStart);
            stepTo = null;
            markProgress(now);
            return TaskStatus.RUNNING;
        }
        if (now - stepStart > StepOut.MAX_TICKS) {
            ZenithTask.LOG.warn("Step out of {} into {} took over {} ticks (feet in {}); pathing from here",
                stepFrom == null ? "?" : stepFrom.key(), to.key(), StepOut.MAX_TICKS, at.key());
            stepTo = null;
            markProgress(now);
            return TaskStatus.RUNNING;
        }
        double[] a = StepOut.aim(BOT.getX(), BOT.getZ(), to);
        float yaw = StepOut.yaw(BOT.getX(), BOT.getZ(), a[0], a[1]);
        Input in = Input.builder().pressingForward(true).build();
        INPUTS.submit(InputRequest.builder().owner(this).priority(Clicker.PRIORITY).input(in).yaw(yaw).pitch(0).build());
        return TaskStatus.RUNNING;
    }

    /**
     * Every bot tick after Baritone's tick, before ZenithProxy's InputManager applies its keys: the running walk's paths
     * are checked now ({@link #plannedPathEntersZone}), so a path Baritone planned and started this tick never takes its
     * first movement into a no-go zone: Baritone stops and this tick's keys are cleared; the walk's next tick handles it
     * as before (a corner detour or FAILED). Live 2026-10-15 (Galileo [7,0]): a restock path from the east lane end
     * stepped onto the parked machines in its first movement, before the walk's own check of the next tick; the walk
     * after it, out of the zone, dropped into the collection trench and the bot could not climb out (BLOCKED).
     */
    static void checkBeforeInputs() {
        Walker w = zoneWalker;
        if (w == null || w.preInputViolation != null || w.guard == null) return;
        NoGoZones.Violation v = w.plannedPathEntersZone();
        if (v == null) return;
        w.preInputViolation = v;
        BARITONE.getPathingBehavior().forceCancel();
        EdgeApproach.halt(); // clears Baritone's keys of this tick (EdgeApproach.applyToBaritone, right after)
    }

    /**
     * Where Baritone's current or next path segment for our goal first enters a no-go zone, or null. Each path object is
     * checked once. Runs on the client tick thread, like Baritone's own tick: a segment Baritone found and started in
     * the same tick may take one movement before this sees it; the task's position check stops the bot on entering.
     */
    private NoGoZones.@Nullable Violation plannedPathEntersZone() {
        if ((!zones.checks() && pathCheck == null && !edge.checksDescents() && !plannedFalls.getAsBoolean()) || goal == null
            || !BARITONE.isGoalActive(goal)) return null;
        var behaviour = BARITONE.getPathingBehavior();
        PathExecutor cur = behaviour.getCurrent();
        PathExecutor next = behaviour.getNext();
        IPath c = cur == null ? null : cur.getPath();
        IPath n = next == null ? null : next.getPath();
        if (c != null && c != checkedCurrent) {
            NoGoZones.Violation v = violation(cells(c));
            if (v != null) return v;
            checkedCurrent = c;
        }
        if (n != null && n != checkedNext) {
            NoGoZones.Violation v = violation(cells(n));
            if (v != null) return v;
            checkedNext = n;
        }
        return null;
    }

    /**
     * The way around {@code v} ({@link NoGoZones#cornerFix}): with {@code build.noGoCornerDetour} and tries left for this
     * goal list, a cell to make look far (never a cell of the goal or the feet cell) or a side cell to walk through
     * first. Null: the walk fails as before.
     */
    private NoGoZones.@Nullable CornerFix cornerFix(NoGoZones.Violation v, Goal inner, Pos feet) {
        if (!cornerDetour.getAsBoolean() || detour.size() + (viaTried ? 1 : 0) >= MAX_CORNER_DETOURS) return null;
        if (edge.ownsCorner(v))
            return edge.cornerFix(v, detour, viaTried, c -> c.equals(feet) || inner.isInGoal(c.x(), c.y(), c.z()));
        return zones.cornerFix(v, detour, viaTried, c -> c.equals(feet) || inner.isInGoal(c.x(), c.y(), c.z()));
    }

    /** The log's name for the one move a detour plans around. */
    private String detourWhy(NoGoZones.Violation v) {
        if (edge.ownsCorner(v)) return "a move past a hole's corner";
        return v.descent() ? "a step down toward an edge" : "a move past the zone's corner";
    }

    /**
     * Feet cells Baritone will still walk for our goal: the current segment from its position on, then the next one;
     * null when no path runs for our goal yet, or the planned segments do not end in it (more is still to be planned).
     * Client tick thread (Baritone's own).
     */
    @Nullable List<Pos> remainingPath() {
        List<Pos> out = plannedRoute();
        if (out == null) return null;
        Pos end = out.getLast();
        return goal.isInGoal(end.x(), end.y(), end.z()) ? out : null;
    }

    /**
     * The feet cells Baritone still walks for our goal (the current segment from its position on, then the next one),
     * whether or not they end in the goal (a goal not loaded yet: they end where the loaded world does); null when no
     * path runs for our goal. The elytra leg's long hops stop on it ({@link FlightLeg#withLongHop}). Client tick thread.
     */
    @Nullable List<Pos> plannedRoute() {
        if (future == null || goal == null || !BARITONE.isGoalActive(goal)) return null;
        var behaviour = BARITONE.getPathingBehavior();
        PathExecutor cur = behaviour.getCurrent();
        IPath c = cur == null ? null : cur.getPath();
        if (c == null || c.positions().isEmpty()) return null;
        List<Pos> out = new ArrayList<>();
        List<BlockPos> ps = c.positions();
        for (int i = Math.max(0, Math.min(cur.getPosition(), ps.size() - 1)); i < ps.size(); i++) {
            BlockPos b = ps.get(i);
            out.add(new Pos(b.x(), b.y(), b.z()));
        }
        PathExecutor next = behaviour.getNext();
        IPath n = next == null ? null : next.getPath();
        if (n != null && !n.positions().isEmpty()) out.addAll(cells(n));
        return out;
    }

    /**
     * Why a path with these feet cells must not be walked (a no-go zone, a step down toward an edge, then the owner's
     * check), or null.
     */
    private NoGoZones.@Nullable Violation violation(List<Pos> cells) {
        NoGoZones.Violation v = !zones.checks() ? null : zones.violation(cells, leaving);
        if (v == null && plannedFalls.getAsBoolean()) {
            // no ends given: nothing to plan around, the walk fails at once (the pathfinder took the fall because
            // nothing else leads to the goal)
            // the pathfinder's own drop limit: 3, or what FallGuard raised it to for a walk that drops on purpose (the
            // hop down from the soul sand centre); a fall into water is not bounded by it, so it is refused
            int max = Math.max(EdgeDrop.MAX_PLANNED_FALL,
                com.zenith.Globals.CONFIG.client.extra.pathfinder.maxFallHeightNoWater);
            EdgeDrop.Fall f = EdgeDrop.longFall(cells, max);
            if (f != null) v = new NoGoZones.Violation("falls " + f.blocks() + " blocks from " + f.from().key() + " to "
                + f.to().key() + " (a planned fall of more than " + max + " blocks)", null, null);
        }
        if (v == null) v = edge.descent(cells);
        if (v == null) v = edge.corner(cells);
        if (v == null && pathCheck != null) {
            String own = pathCheck.apply(cells);
            if (own != null) v = new NoGoZones.Violation(own, null, null);
        }
        return v;
    }

    private static List<Pos> cells(IPath path) {
        List<Pos> out = new ArrayList<>(path.length());
        for (BlockPos b : path.positions()) out.add(new Pos(b.x(), b.y(), b.z()));
        return out;
    }

    /** Feet cell (ZenithProxy PlayerContext convention: y + 0.1251 for soul sand). */
    static Pos feet() {
        return new Pos((int) Math.floor(BOT.getX()), (int) Math.floor(BOT.getY() + 0.1251), (int) Math.floor(BOT.getZ()));
    }

    private TaskStatus failed(long now, String why) {
        if (++failures > retries) {
            error = why + (failures == 1 ? " once" : " " + failures + " times") + boxedNote;
            stop();
            return TaskStatus.FAILED;
        }
        retryAt = now + 10L * failures;
        return TaskStatus.RUNNING;
    }

    /** The yaw unwind ({@link Clicker#unwindYaw}) turned the bot this tick, before its walk (one call per tick). */
    void noteUnwind(float yawBefore) {
        if (unwindTicks++ == 0) unwindFrom = yawBefore;
    }

    /**
     * One INFO line per walk start: ticks from the task's first tick to the request, to Baritone pathing, to the feet
     * moving (0.05 blocks), and the yaw unwind before it. Tells the unwind from Baritone's planning and start latency.
     */
    private void watchStart(long now) {
        if (requestTick < 0) return;
        if (pathingTick < 0 && BARITONE.getPathingBehavior().isPathing()) pathingTick = now;
        if (Aim.distSq(BOT.getX(), 0, BOT.getZ(), startX, 0, startZ) < 0.05 * 0.05 && now - requestTick < STALL_TICKS) return;
        ZenithTask.LOG.info("Walk start: task tick {}; unwind {} ticks from yaw {}; Baritone asked at {}, pathing at {}, "
                + "first step at {} (yaw {} -> {})", now, unwindTicks, unwindTicks > 0 ? String.format("%.0f", unwindFrom) : "-",
            requestTick, pathingTick, now, String.format("%.0f", startYaw), String.format("%.0f", BOT.getYaw()));
        requestTick = -1;
        unwindTicks = 0;
    }

    private void markProgress(long now) {
        progressTick = now;
        px = BOT.getX();
        py = BOT.getY();
        pz = BOT.getZ();
    }

    /** Cancels our Baritone request (only if Baritone is still pathing to our goal). */
    private void stopPath() {
        if (future != null && !future.isCompleted() && goal != null && BARITONE.isGoalActive(goal)) {
            BARITONE.stop();
            EdgeApproach.halt(); // its keys of this tick, if it already submitted them
        }
        future = null;
    }

    /**
     * Before Baritone's tick: Baritone reads allowSprint in its own tick, which runs ahead of the plugin's, so the
     * latch's verdict after a stop would come one tick late and let the bot start again for a tick (a stop, a start and
     * a stop within two ticks: each one races the server's attribute update). {@link SprintLegs.Once} with this tick's
     * sprint state, and allowSprint off at once when the walk may not sprint any more.
     */
    static void beforeBaritone() {
        Walker w = sprintWalker;
        if (w == null || !w.sprinting || w.sprintRule == null || !w.sprintRule.once()) return;
        if (!w.once.allow(true, BOT.isSprinting(), (int) Math.floor(BOT.getX()), (int) Math.floor(BOT.getZ()),
            SprintLegs.Watch.SESSION.ticks(), w.sprintRule.restartTicks(), w.sprintRule.lateStart())) {
            w.sprinting = false;
            PathingGuard.sprint(false);
        }
    }

    /** Stops walking and releases the pathing guard. Idempotent. */
    void stop() {
        if (flight != null) flight.walkEnded(lastNow, false);
        endRow();
        if (sprintWalker == this) sprintWalker = null;
        if (zoneWalker == this) zoneWalker = null;
        preInputViolation = null;
        requestTick = -1;
        unwindTicks = 0;
        back = null;
        stepTo = null;
        via = null; // a walk taken up again plans from where the bot stands
        stopPath();
        if (guard != null) {
            edge.stopped(); // still moving toward an edge: sneak until still (EdgeApproach)
            if (sprinting) PathingGuard.sprint(false);
            guard.release();
            guard = null;
        }
        sprinting = false;
        edge.reset();
        boolean late = sprintRule != null && sprintRule.once() && once.late() && legTicks > 0;
        if (legSprintTicks > 0 || late) {
            ZenithTask.LOG.info("Sprint leg to {} ({}): sprinted {} of {} ticks{}", goals.isEmpty() ? "?" : goals.getLast(),
                sprintKind, legSprintTicks, legTicks, !late ? "" : once.lateStarted() && legSprintTicks > 0
                    ? " (a late start: the feet left their first block first)"
                    : " (no sprint: the feet left their first block first)");
        }
        legTicks = 0;
        legSprintTicks = 0;
    }

    /**
     * Whether Baritone may sprint this tick: a sprint rule, and {@link SprintLegs#allowed} with the hunger bar, the
     * walk's age and the distance from the feet to the current goal (a {@link GoalNearXZ}; any other goal: no sprint).
     */
    private boolean sprintNow(long now) {
        SprintLegs.Rule rule = sprintRule;
        if (rule == null || goals.isEmpty() || goalIndex >= goals.size()) return false;
        // through a corner's side cell first: that is where the bot arrives next (NoGoZones.cornerFix)
        Goal active = via != null ? via : goals.get(goalIndex);
        if (!(active instanceof GoalNearXZ g)) return false;
        int food = CACHE.getPlayerCache().getThePlayer().getFood();
        return SprintLegs.allowed(rule, now - walkStart, food, SprintLegs.distanceLeft(g, BOT.getX(), BOT.getZ()));
    }
}
