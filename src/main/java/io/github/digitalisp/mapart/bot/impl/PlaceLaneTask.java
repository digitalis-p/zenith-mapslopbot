package io.github.digitalisp.mapart.bot.impl;

import com.zenith.feature.player.Input;
import com.zenith.mc.block.CollisionBox;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.PlacementListener;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.config.SiteRules;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.zenith.Globals.BLOCK_DATA;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.INVENTORY;

/**
 * The placement engine on ZenithProxy: {@link LaneRun} makes the decisions,
 * this task is its {@link LaneRun.Io}.
 *
 * <p>Walking: Baritone (with place/break/inventory off, {@link Walker}) brings the bot to the lane start and to stand
 * spots; along the lane the task submits its own movement keys every tick, relative to the yaw it is looking with,
 * so rotation and movement stay in one input request (Baritone would fight over the rotation) and the bot stops
 * exactly where needed. Clicks are self-sent use-item-on packets with the exact hit point ({@link Clicker}); a hotbar
 * slot change is sent directly before the click; a move from the main inventory happens in a still tick without a
 * click ({@link Hotbar}).
 *
 * <p>Placement modes: airplace goes through {@link AirPlace} on
 * {@link ZenithAirPlace} with the shared {@link AirPlaceGuard}; while the guard is busy (the cache shows an airplace
 * swap) no click and no inventory move happens. With silent looks ({@code rotatePlayer} false, {@code grimRotate}
 * true) the rotation request keeps the real rotation and the look is sent by {@link SilentRotation} at the end of the
 * tick, after the click (the printer's order: click k, then the look for k+1); the next tick's click is checked
 * against that look.
 *
 * <p>Setback cells ({@code avoid}, from the engine): stand spots keep one cell away from them and stand-spot walks never
 * pass them; walks to floor stand spots in AIRPLACE mode refuse a path that climbs ({@link LaneRun#pathRefusal}).
 */
final class PlaceLaneTask extends ZenithTask {
    /** Lane starts walled off broken out of per task before it fails as before ({@code build.laneBreakOut}). */
    static final int MAX_BREAK_OUTS = 3;
    /** Cells around the bot and the lane start the break-out route may use. */
    static final int BREAK_OUT_MARGIN = 24;

    private LaneRun<Aiming.Shot> run;
    private final Lane lane;
    private final PlacementListener listener;
    private final LaneRun.Params params;
    private final List<Lane> load;
    /** Breaking out to a lane start the walk could not reach ({@code build.laneBreakOut}), or null. */
    private @Nullable BreakOut breakOut;
    private int breakOuts;
    /** The lane start the last-resort walk with breaking allowed goes to ({@link ZenithTask#walkBreaking}). */
    private @Nullable Pos breakWalkTo;
    private boolean breakWalked;
    /** The lane runs from stand spots only ({@code build.laneStartSpots}): once per task. */
    private boolean spotsRun;
    /**
     * VERIFY break-in ({@code build.verifyBreakIn}, {@link LaneRun} "Break-in"): the tile's placement per cell (null: a
     * BUILD lane, no break-in), the walls being broken for the run, and why the last ones failed.
     */
    private final @Nullable Function<Pos, @Nullable Placement> art;
    private @Nullable BreakOut breakIn;
    private @Nullable List<Pos> breakInWalls;
    private String breakInError = "";
    /** The engine asked to park and has not resumed yet (a fresh run after a break-out parks as well). */
    private boolean parkWanted;
    private final int laneIndex;
    /** Hotbar stacks of this lane's materials: a move into the hotbar replaces anything else first. */
    private final Predicate<ItemStack> laneMaterial;

    private final AirPlaceGuard guard = AirPlaceGuard.shared();
    private final AirPlace airPlace = new AirPlace(guard, ZenithAirPlace.INSTANCE);
    /** Target state per cell of this lane and its load, for the airplace face ({@link AirPlace#face}). */
    private final java.util.Map<Pos, io.github.digitalisp.mapart.model.BlockSpec> targets = new java.util.HashMap<>();
    /**
     * Looks go silently (rotatePlayer false, grimRotate true, the reflection into ZenithProxy's Bot works, and server
     * setbacks have not turned them off for this run: {@link SilentRotation#usable}).
     */
    private final boolean silent;
    /** This tick's silent look ({@link #pendingYaw}/{@link #pendingPitch}), sent after the click at the end of the tick. */
    private boolean pending;
    private float pendingYaw;
    private float pendingPitch;
    private float silentYaw;
    private float silentPitch;
    /** A silent look was sent (the rotation a click is checked against), until the next park. */
    private boolean silentSent;
    /**
     * Feet cells where the server set the bot back (setback bursts): no stand
     * spot and no stand-spot walk within one cell of them.
     */
    private final Set<Pos> avoid;
    private final LaneRun.@Nullable Area standArea;
    /** {@link LaneRun.Io#raisedLevel}: {@link #raisedLevel(MapArtConfig, Lane)} of this lane. */
    private final boolean raised;
    /** Path checks for stand-spot walks ({@link Walker#pathCheck}): setback cells only, and no climbing as well. */
    private final Function<List<Pos>, @Nullable String> avoidCheck;
    private final Function<List<Pos>, @Nullable String> floorCheck;
    private boolean announced;
    /** The walk in progress goes to the lane start or back to a park position ({@link ZenithIo#walk(List, BooleanSupplier)}). */
    private boolean startWalk;
    /** Hotbar slots this task's pre-lane layout moved a stack into ({@code build.laneLayoutPin}, {@link LaneHotbar}). */
    private final Set<Integer> layoutPinned = new HashSet<>();

    PlaceLaneTask(ZenithBotActions ctx, Lane lane, PlacementListener listener) {
        this(ctx, lane, listener, Set.of());
    }

    /**
     * {@code avoid}: feet cells of setback bursts the engine saw; stand spots
     * keep one cell away from them ({@link ZenithIo#standAllowed}) and no stand-spot walk passes them.
     */
    PlaceLaneTask(ZenithBotActions ctx, Lane lane, PlacementListener listener, Set<Pos> avoid) {
        this(ctx, lane, listener, avoid, List.of());
    }

    /**
     * {@code load}: the later lanes of the restock load (their cells still to place) for the in-reach greedy
     * ({@code build.placeInReachGreedy}, {@link LaneRun} "In-reach greedy"); ignored when it is off.
     */
    PlaceLaneTask(ZenithBotActions ctx, Lane lane, PlacementListener listener, Set<Pos> avoid, List<Lane> load) {
        this(ctx, lane, listener, avoid, load, null);
    }

    /**
     * {@code art}: the tile's placement per cell for VERIFY's break-in ({@code build.verifyBreakIn}; null in BUILD:
     * no break-in).
     */
    PlaceLaneTask(ZenithBotActions ctx, Lane lane, PlacementListener listener, Set<Pos> avoid, List<Lane> load,
                  @Nullable Function<Pos, @Nullable Placement> art) {
        this(ctx, lane, listener, avoid, load, art, false);
    }

    PlaceLaneTask(ZenithBotActions ctx, Lane lane, PlacementListener listener, Set<Pos> avoid, List<Lane> load,
                  @Nullable Function<Pos, @Nullable Placement> art, boolean repair) {
        super(ctx, timeoutFor(lane) + (repair && ctx.config().build.verifySealOut && load != null
            ? load.stream().mapToLong(PlaceLaneTask::timeoutFor).sum() : 0));
        this.art = art;
        MapArtConfig.BuildCfg b = ctx.config().build;
        // silent looks off for the run once server setbacks follow them (SilentLookGuard, "Setback escape")
        this.silent = PlaceMode.silentRotation(b) && SilentRotation.usable();
        this.avoid = Set.copyOf(avoid);
        this.standArea = standArea(ctx.config(), lane);
        this.raised = raisedLevel(ctx.config(), lane);
        this.avoidCheck = cells -> LaneRun.pathRefusal(cells, standArea, this.avoid, false);
        this.floorCheck = cells -> LaneRun.pathRefusal(cells, standArea, this.avoid, true);
        LaneRun.Params c = LaneRun.Params.of(b, standArea);
        LaneRun.Params params = new LaneRun.Params(c.reach(), c.reachStepMargin(), c.placeConfirmTicks(), c.pipelined(),
            c.intervalMin(), c.intervalMax(), c.standArea(), c.mode(), c.airPlaceRange(), silent, c.walkWalled(),
            c.pacing(), c.greedy(), c.sideFaces(), c.trimEnds(), c.openSegment(), c.wayOut(), c.keepReachable(),
            art != null && b.verifyBreakIn, repair && b.verifySealOut);
        this.lane = lane;
        this.listener = listener;
        this.params = params;
        this.load = load == null ? List.of() : load;
        this.run = new LaneRun<>(lane, new ZenithIo(listener), params, this.load);
        this.laneIndex = lane.index();
        for (Placement p : lane.placements()) targets.put(p.pos(), p.block());
        if (load != null) for (Lane l : load) for (Placement p : l.placements()) targets.putIfAbsent(p.pos(), p.block());
        Set<String> items = new HashSet<>();
        for (Placement p : lane.placements()) items.add(ItemIds.material(p.itemId()));
        this.laneMaterial = s -> items.contains(Inv.id(s));
    }

    /** Walking time per cell and per block of lane, plus stand spots for what the walk leaves. */
    private static long timeoutFor(Lane lane) {
        int len = Math.abs(lane.to().x() - lane.from().x()) + Math.abs(lane.to().z() - lane.from().z());
        return 2400L + lane.placements().size() * 60L + len * 40L;
    }

    /**
     * Cells of the platform tile the lane lies on (stand spots stay on it: never the noobline or the machines). On a
     * colour-suppression platform (docs/COLOR_SUPPRESSION.md) the lane's level is one of its glass floors + 1 or + 2,
     * and its noobline row (on the glass too) belongs to the area.
     */
    static LaneRun.@Nullable Area standArea(MapArtConfig config, Lane lane) {
        Pos p = lane.placements().isEmpty() ? lane.from() : lane.placements().getFirst().pos();
        for (MapArtConfig.PlatformCfg pc : config.site.platforms) {
            if (pc.isColorSuppression() && pc.glassY != null
                && (pc.glassY.contains(p.y() - 1) || pc.glassY.contains(p.y() - 2))
                && p.x() >= pc.minX && p.x() <= pc.minX + 127 && p.z() >= pc.minZ - 1 && p.z() <= pc.minZ + 127)
                return new LaneRun.Area(pc.minX, pc.minZ - 1, pc.minX + 127, pc.minZ + 127);
            if (pc.isColorSuppression()) continue;
            var g = pc.geometry();
            if (g.artY() == p.y() && g.contains(p.x(), p.z())) {
                return new LaneRun.Area(g.minX(), g.minZ(), g.minX() + 127, g.minZ() + 127);
            }
        }
        return null;
    }

    /**
     * The lane may meet raised cells, one block above its height ({@code build.suppressionMergeLevels}, LaneRun "Raised
     * cells"): it holds one, or the switch is on and the lane is on the level-0 height (glass + 1) of a
     * colour-suppression layer, where the level-1 cells stand one higher. Either way the lane placer reads the column.
     */
    static boolean raisedLevel(MapArtConfig config, Lane lane) {
        int y = lane.from().y();
        for (Placement p : lane.placements()) if (p.pos().y() == y + 1) return true;
        if (!config.build.suppressionMergeLevels) return false;
        Pos f = lane.from();
        for (MapArtConfig.PlatformCfg pc : config.site.platforms) {
            if (pc.isColorSuppression() && pc.glassY != null && pc.glassY.contains(y - 1)
                && f.x() >= pc.minX && f.x() <= pc.minX + 127 && f.z() >= pc.minZ - 1 && f.z() <= pc.minZ + 127) return true;
        }
        return false;
    }

    boolean lookaheadEnabled() {
        return cfg.build.placeHotbarLookahead && cfg.build.hotbarMoveFence
            && cfg.build.placeDuringMoveFence && cfg.build.placeStackSwitch;
    }

    @Override
    protected boolean recoverArtWalks() { return false; }

    @Override
    public String name() {
        return "placeLane " + laneIndex;
    }

    @Override
    protected String progress() {
        return run.progress();
    }

    @Override
    protected TaskStatus step() {
        if (InventoryEchoes.SESSION.recovering()) {
            run.suspendClicks();
            holdStill();
            hotbar.busy(now); // after a still tick, request the full answer without a placement
            return run.watchdog(now); // bounded by the session's recovery bound; the watchdog stays the backstop
        }
        if (!announced) {
            announced = true;
            if (!avoid.isEmpty()) {
                LOG.info("Lane {}: avoiding {} setback cells: {}", laneIndex, avoid.size(),
                    avoid.stream().map(Pos::key).sorted().toList());
            }
        }
        if (breakWalkTo != null) {
            Pos to = breakWalkTo;
            // build.laneStartOnFloor: the feet on the art layer or one block up, and off the tile's border tops, as the
            // lane start's own walk; else the wall's edge above the start "arrived"
            GoalNearXZ g = new GoalNearXZ(to.x(), to.y(), to.z(), 1, cfg.build.laneStartOnFloor ? 1 : 2);
            walker.border(startBorder());
            TaskStatus w = walkBreaking(List.of(g), () -> feetIn(g), Walker.MAX_FAILURES);
            if (w == TaskStatus.RUNNING) return w;
            walker.border(null);
            breakWalkTo = null;
            if (w == TaskStatus.FAILED) return fail("cannot reach lane start " + to + " even breaking: " + walker.error());
            restartRun();
            run = new LaneRun<>(lane, new ZenithIo(listener), params, load);
            if (parkWanted) run.requestPark();
            return TaskStatus.RUNNING;
        }
        if (breakOut != null) {
            TaskStatus b = breakOut.tick();
            if (b == TaskStatus.RUNNING) return b;
            String why = breakOut.failure;
            breakOut = null;
            if (b == TaskStatus.FAILED) return fail(why);
            // the walls are gone: the lane from its start again (nothing was placed: its walk never reached it)
            restartRun();
            run = new LaneRun<>(lane, new ZenithIo(listener), params, load);
            if (parkWanted) run.requestPark();
            return TaskStatus.RUNNING;
        }
        TaskStatus s = run.tick(now);
        if (pending) {
            pending = false;
            if (SilentRotation.send(pendingYaw, pendingPitch)) {
                silentYaw = Clicker.continuousYaw(pendingYaw);
                silentPitch = Math.max(-90f, Math.min(90f, pendingPitch));
                silentSent = true;
            }
        }
        if (s == TaskStatus.FAILED) {
            Pos start = run.unreachedStart();
            // also after the dripstone break-through (at most 6 blocks) gave up: live 2026-10-13 (remu [0,2]) with this
            // break-out off under it, VERIFY's "cannot reach lane start" came back 4 passes in a row -> BLOCKED
            if (start != null && cfg.build.laneBreakOut && breakOuts < MAX_BREAK_OUTS) {
                BreakOut b = BreakOut.plan(this, start, run.failure());
                if (b != null) {
                    breakOuts++;
                    breakOut = b;
                    return TaskStatus.RUNNING;
                }
            }
            if (start != null && cfg.build.baritoneBreakEscape && breakWalkTo == null && !breakWalked) {
                // the last resort: the pathfinder walks to the start, breaking where no walk exists (BreakGuard)
                LOG.info("Lane {}: start {} out of reach ({}); walking there with the pathfinder allowed to break",
                    laneIndex, start, run.failure());
                breakWalkTo = start;
                breakWalked = true;
                return TaskStatus.RUNNING;
            }
            if (start != null && cfg.build.laneStartSpots && !spotsRun
                && (breakWalked || !cfg.build.baritoneBreakEscape)) {
                // still out of reach (live 2026-10-23, j0015 [4,1] lane 14: the tile's corner cell beside the east
                // trench is entered only by a step down toward it, which the edge check refuses; 6 attempts -> BLOCKED
                // twice): the whole lane from stand spots, as a walled lane goes
                LOG.info("Lane {}: start {} still out of reach ({}); placing the lane from stand spots", laneIndex,
                    start, run.failure());
                spotsRun = true;
                restartRun();
                run = new LaneRun<>(lane, new ZenithIo(listener), params, load);
                run.standSpotsOnly();
                if (parkWanted) run.requestPark();
                return TaskStatus.RUNNING;
            }
            return fail(run.failure());
        }
        return s;
    }

    /**
     * The tile's border tops for the walks to the lane start ({@code build.laneStartOnFloor}, {@link BorderTop}); null
     * when the switch is off or the lane has no stand area.
     */
    private @Nullable BorderTop startBorder() {
        if (!cfg.build.laneStartOnFloor || standArea == null) return null;
        return BorderTop.of(standArea, lane.from().y());
    }

    /**
     * Before the lane runs again (after the break walk, a break-out, or for the stand-spot run): with {@code
     * build.laneStartOnFloor} the walker forgets the failed walk's goal list ({@link Walker#forget}), so the run's
     * walk to its start, with the same goals, starts at the first of them instead of failing at once.
     */
    private void restartRun() {
        if (cfg.build.laneStartOnFloor) walker.forget();
    }

    @Override
    public boolean requestPark() {
        parkWanted = true;
        run.requestPark();
        return true;
    }

    @Override
    public boolean parked() {
        return run.parked();
    }

    /**
     * The walk in progress ({@link SprintLegs.Walk}): to the lane start or back to it (the first one after a restock is
     * a restock leg; the in-reach greedy's airplaces wait for the quiet window), to a stand spot, or along a break-out
     * route. The lane's own walk while it places submits its own keys and never sprints.
     */
    @Override
    protected SprintLegs.Walk walkKind() {
        return startWalk ? SprintLegs.Walk.LANE_START : breakOut != null || breakIn != null
            ? SprintLegs.Walk.LANE_BREAK_OUT : SprintLegs.Walk.STAND_SPOT;
    }

    /** The restock legs end with the first lane task at the latest. */
    @Override
    protected void onRelease() {
        run.close();
        if (breakOut != null) breakOut.cancel();
        if (breakInWalls != null) ctx.repairWalkCells(breakInWalls.reversed());
        if (breakIn != null) breakIn.cancel();
        ctx.laneStartReached();
    }

    /**
     * Break-out ({@code build.laneBreakOut}): the walk to the lane start failed, and
     * the way there is walled off by placed blocks the pathfinder can neither walk through nor onto (pointed dripstone,
     * pots). The fewest of them on one route ({@link WallRoute}; the start cell itself when it holds one) are broken,
     * those in reach by a break task (the usual tool rules), the bot walking along the route to bring the next into
     * reach; then the lane starts again. VERIFY places them again.
     */
    static final class BreakOut {
        private final ZenithTask task;
        private final WallRoute.Route route;
        private final List<Pos> left;
        private final String reason;
        /** Logged when the walls are gone. */
        private final String done;
        private @Nullable BreakBlocksTask breaking;
        private int walks;
        private int breaks;
        String failure;

        private BreakOut(PlaceLaneTask task, WallRoute.Route route, String reason) {
            this(task, route, reason, "broke out (" + route.walls().size() + " block(s)); starting the lane again");
        }

        private BreakOut(ZenithTask task, WallRoute.Route route, String reason, String done) {
            this.task = task;
            this.route = route;
            this.left = new ArrayList<>(route.walls());
            this.reason = reason;
            this.failure = reason;
            this.done = done;
        }

        static BreakOut recovery(ZenithTask task, List<Pos> walls, List<Pos> path) {
            return new BreakOut(task, new WallRoute.Route(List.copyOf(walls), List.copyOf(path)),
                "dripstone break-through", "");
        }

        /** VERIFY's break-in: {@code walls} along {@code path}, walls off the path from its last cell. */
        static BreakOut breakIn(PlaceLaneTask task, List<Pos> walls, List<Pos> path) {
            return new BreakOut(task, new WallRoute.Route(List.copyOf(walls), List.copyOf(path)), "break-in",
                "break-in: " + walls.size() + " block(s) broken");
        }

        /** The route from the bot to {@code start} through the fewest walls it may break; null if it needs none. */
        static @Nullable BreakOut plan(PlaceLaneTask task, Pos start, String reason) {
            int artY = start.y();
            Pos feet = new Pos((int) Math.floor(BOT.getX()), artY, (int) Math.floor(BOT.getZ()));
            WallRoute.Route r = WallRoute.plan(task.grid(), feet, start, 0, BREAK_OUT_MARGIN);
            if (r == null || r.walls().isEmpty()) return null;
            List<String> named = new ArrayList<>();
            for (Pos w : r.walls()) named.add(ItemIds.path(task.ctx.world().block(w).id()) + " at " + w);
            LOG.info("Lane {}: {}; walled off by blocks the pathfinder can neither walk through nor onto: breaking {}"
                    + " ({} of them, on the one route with the fewest; VERIFY places them again)", task.laneIndex,
                reason, String.join(", ", named), named.size());
            return new BreakOut(task, r, reason);
        }

        TaskStatus tick() {
            BreakBlocksTask b = breaking;
            if (b != null) {
                TaskStatus s = b.tick();
                if (s == TaskStatus.RUNNING) return s;
                breaking = null;
            }
            left.removeIf(p -> task.ctx.world().isLoaded(p) && task.ctx.world().block(p).isAir());
            if (left.isEmpty()) {
                if (!done.isEmpty()) LOG.info("Lane {}: {}", ((PlaceLaneTask) task).laneIndex, done);
                return TaskStatus.SUCCESS;
            }
            if (b != null && b.failure() != null) {
                failure = reason + "; breaking out failed: " + b.failure();
                return TaskStatus.FAILED;
            }
            // the walls in reach now, in route order
            double reach = Math.max(1.0, task.cfg.build.reach - 0.3);
            List<Pos> now = new ArrayList<>();
            for (Pos w : left) if (w.distSq(BOT.getX(), BOT.getEyeY(), BOT.getZ()) <= reach * reach) now.add(w);
            if (!now.isEmpty()) {
                if (++breaks > route.walls().size() + 2) {
                    failure = reason + "; breaking out: " + left.size() + " block(s) still standing after " + (breaks - 1)
                        + " break tasks";
                    return TaskStatus.FAILED;
                }
                task.walker.stop();
                breaking = new BreakBlocksTask(task.ctx, now, p -> { });
                breaking.artRecoveryAllowed = false;
                return TaskStatus.RUNNING;
            }
            // the next one is out of reach: along the route to the cell before it
            int i = route.path().indexOf(left.getFirst());
            // a wall off the path (a break-in's, beside the stand cell): from the path's last cell
            Pos stand = i > 0 ? route.path().get(i - 1) : i < 0 && !route.path().isEmpty() ? route.path().getLast() : null;
            if (stand == null || walks > (task instanceof PlaceLaneTask lane && lane.params.sealOut()
                ? route.path().size() + 2 : 2 * MAX_BREAK_OUTS)) {
                failure = reason + "; breaking out: " + left.getFirst() + " stays out of reach";
                return TaskStatus.FAILED;
            }
            TaskStatus w = task.walkRaw(List.of(new GoalNearXZ(stand.x(), stand.y(), stand.z(), 0, 2)),
                () -> feetIn(new GoalNearXZ(stand.x(), stand.y(), stand.z(), 0, 2)), Walker.MAX_FAILURES);
            if (w == TaskStatus.FAILED) {
                failure = reason + "; breaking out: cannot walk to " + stand + ": " + task.walker.error();
                return TaskStatus.FAILED;
            }
            if (w == TaskStatus.SUCCESS) walks++;
            return TaskStatus.RUNNING;
        }

        void cancel() {
            BreakBlocksTask b = breaking;
            if (b != null) b.cancel();
            breaking = null;
        }
    }

    /**
     * The world at the art level for {@link WallRoute}: passable a floor cell (nothing taller than a step, a support,
     * head room) or a full cube Baritone climbs onto; breakable a block taller than a step that is no full cube (pointed
     * dripstone, a pot) among the platform's clearable blocks, not protected, in no protected region or no-go zone.
     */
    private WallRoute.Grid grid() {
        var w = ctx.world();
        java.util.Set<String> protectedIds = ctx.protectedIds();
        java.util.function.Predicate<Pos> clear = p -> {
            BlockSpec b = w.block(p);
            return b.isAir() || BlockStates.collisionTop(b) == 0.0;
        };
        return new WallRoute.Grid() {
            @Override
            public boolean passable(Pos c) {
                if (!w.isLoaded(c) || !w.isLoaded(c.below()) || w.hasFluid(c) || zones.at(c) != null) return false;
                BlockSpec b = w.block(c);
                if (!b.isAir() && BlockStates.collisionTop(b) > LaneRun.TALL) {
                    return BlockStates.walkOnTop(b) && clear.test(c.above()) && clear.test(c.above().above());
                }
                return BlockStates.blocksPlacement(w.stateId(c.below())) && clear.test(c.above());
            }

            @Override
            public boolean breakable(Pos c) {
                if (!w.isLoaded(c) || !w.isLoaded(c.below()) || zones.at(c) != null) return false;
                BlockSpec b = w.block(c);
                if (b.isAir() || BlockStates.collisionTop(b) <= LaneRun.TALL || BlockStates.walkOnTop(b)) return false;
                if (SiteRules.protectedAt(cfg.site, protectedIds, c, b.id())) return false;
                if (SiteRules.protectedRegionAt(cfg.site, c) != null) return false;
                if (!WallRoute.clearedAnyway(cfg.site, c, b.id())) return false;
                return BlockStates.blocksPlacement(w.stateId(c.below())) && clear.test(c.above());
            }
        };
    }

    /** No click or inventory move in the first tick back: it needs a tick whose input was ours again. */
    @Override
    public void resume() {
        parkWanted = false;
        clicker.reset();
        silentSent = false;
        run.resume();
    }

    /** {@link LaneRun.Io} on ZenithProxy for this task (client tick thread). */
    private final class ZenithIo implements LaneRun.Io<Aiming.Shot> {
        private final PlacementListener listener;
        private EdgeDrop.@Nullable Cells edgeCells;
        private boolean dispatching;

        ZenithIo(PlacementListener listener) {
            this.listener = listener;
        }

        @Override public double x() { return dispatching ? CACHE.getPlayerCache().getX() : BOT.getX(); }
        @Override public double y() { return dispatching ? CACHE.getPlayerCache().getY() : BOT.getY(); }
        @Override public double z() { return dispatching ? CACHE.getPlayerCache().getZ() : BOT.getZ(); }
        @Override public double eyeY() { return dispatching ? y() + (BOT.getEyeY() - BOT.getY()) : BOT.getEyeY(); }
        @Override public void beginSubTick() { dispatching = true; }
        @Override public void endSubTick() { dispatching = false; }
        @Override public float yaw() { return BOT.getYaw(); }
        @Override public boolean onGround() { return BOT.isOnGround(); }
        @Override public boolean horizontalCollision() { return BOT.isHorizontalCollision(); }

        @Override
        public boolean isLoaded(Pos pos) {
            return ctx.world().isLoaded(pos);
        }

        @Override
        public BlockSpec block(Pos pos) {
            return ctx.world().block(pos);
        }

        /** Not in a no-go zone, and not within one cell (x, z; feet y within 1) of a setback cell. */
        @Override
        public boolean standAllowed(Pos feet) {
            return zones.at(feet) == null && LaneRun.nearAvoid(feet, avoid) == null;
        }

        /** {@link EdgeDrop#beside} on the cached world and the no-go zones. */
        @Override
        public @Nullable String edgeHazard(Pos feet) {
            if (edgeCells == null) edgeCells = EdgeApproach.cells(ctx.world(), zones);
            return EdgeDrop.beside(feet, edgeCells);
        }

        @Override
        public boolean edgeSpotsLast() {
            return cfg.build.edgeSpotsLast;
        }

        @Override
        public boolean edgeSneak() {
            return cfg.build.edgeSneak;
        }

        @Override public boolean dripstoneBreakThrough() { return cfg.build.dripstoneBreakThrough; }
        @Override public boolean wallBrake() { return cfg.build.laneWallBrake; }
        @Override public boolean stepBackClearPath() { return cfg.build.laneStepBackClearPath; }
        @Override public boolean forwardClearPath() { return cfg.build.laneForwardClearPath; }
        @Override public boolean startOnFloor() { return cfg.build.laneStartOnFloor; }
        @Override public boolean startTail() { return cfg.build.laneStartTail; }
        @Override public boolean raisedLevel() { return raised; }
        @Override public List<Pos> walkRepairs() { return ctx.walkRepairs(); }

        @Override
        public @Nullable Placement artAt(Pos cell) {
            return art == null ? ctx.walkArtAt(cell) : art.apply(cell);
        }

        /**
         * {@link #grid()}'s breakable walls on a cell of the tile's art that can go up again after the break-in: its
         * item is carried, or the block drops itself (pointed dripstone does, whatever breaks it; a decorated pot
         * broken with a tool shatters).
         */
        @Override
        public boolean breakableWall(Pos cell) {
            Placement p = artAt(cell);
            if (p == null || p.itemId() == null || !grid().breakable(cell)) return false;
            return ItemIds.ns(p.itemId()).equals("minecraft:pointed_dripstone") || count(p.itemId()) > 0;
        }

        @Override
        public TaskStatus breakWalls(List<Pos> walls, List<Pos> path) {
            BreakOut b = breakIn;
            if (b == null || !walls.equals(breakInWalls)) {
                b = cfg.build.dripstoneBreakThrough ? BreakOut.recovery(PlaceLaneTask.this, walls, path)
                    : BreakOut.breakIn(PlaceLaneTask.this, walls, path);
                breakIn = b;
                breakInWalls = List.copyOf(walls);
            }
            TaskStatus s = b.tick();
            ctx.repairWalkCells(walls.reversed());
            if (s == TaskStatus.RUNNING) return s;
            if (s == TaskStatus.FAILED) breakInError = b.failure;
            breakIn = null;
            breakInWalls = null;
            return s;
        }

        @Override
        public void cancelBreakWalls() {
            if (breakInWalls != null) ctx.repairWalkCells(breakInWalls.reversed());
            if (breakIn != null) breakIn.cancel();
            breakIn = null;
            breakInWalls = null;
        }

        @Override
        public String breakError() {
            return breakInError;
        }

        @Override
        public boolean blocksPlacement(Pos pos) {
            return BlockStates.blocksPlacement(ctx.world().stateId(pos));
        }

        @Override
        public boolean hasFluid(Pos pos) {
            return ctx.world().hasFluid(pos);
        }

        @Override
        public double collisionTop(BlockSpec spec) {
            return BlockStates.collisionTop(spec);
        }

        @Override
        public boolean standOnTop(BlockSpec spec) {
            return BlockStates.walkOnTop(spec);
        }

        @Override
        public double outlineTop(BlockSpec spec) {
            int id = BlockStates.stateId(spec);
            if (id < 0) return 1.0;
            double top = 0;
            for (CollisionBox cb : BLOCK_DATA.getInteractionBoxesFromBlockStateId(id)) top = Math.max(top, cb.maxY());
            return top;
        }

        @Override
        public boolean matches(BlockSpec target, BlockSpec actual) {
            return ctx.catalog().matches(target, actual);
        }

        @Override
        public boolean inReach(Pos support, double reach) {
            return Aiming.cellInReach(support.x(), support.y(), support.z(), reach);
        }

        @Override
        public Aiming.@Nullable Shot aim(Pos support, double reach) {
            return Aiming.topFace(support.x(), support.y(), support.z(), reach);
        }

        @Override
        public Aiming.@Nullable Shot recheck(Pos support, double reach) {
            if (silent) {
                // the look sent silently last tick, from the eye now
                if (!silentSent) return null;
                return Aiming.check(support.x(), support.y(), support.z(), com.zenith.mc.block.Direction.UP,
                    silentYaw, silentPitch, reach);
            }
            return Aiming.topFaceNow(support.x(), support.y(), support.z(), reach);
        }

        @Override
        public boolean visibleFrom(double ex, double ey, double ez, Pos support, double reach) {
            return Aiming.topFaceVisibleFrom(ex, ey, ez, support.x(), support.y(), support.z(), reach);
        }

        @Override
        public Aiming.@Nullable Shot aimSide(Pos cell, double reach) {
            return Aiming.sideFace(cell.x(), cell.y(), cell.z(), reach);
        }

        @Override
        public Aiming.@Nullable Shot recheckSide(Pos cell, double reach) {
            if (silent) {
                // the look sent silently last tick, from the eye now
                if (!silentSent) return null;
                return Aiming.checkSide(cell.x(), cell.y(), cell.z(), silentYaw, silentPitch, reach);
            }
            return Aiming.checkSide(cell.x(), cell.y(), cell.z(), BOT.getYaw(), BOT.getPitch(), reach);
        }

        @Override
        public boolean sideVisibleFrom(double ex, double ey, double ez, Pos cell, double reach) {
            return Aiming.sideFaceVisibleFrom(ex, ey, ez, cell.x(), cell.y(), cell.z(), reach);
        }

        @Override
        public int count(String item) {
            int count = Inv.count(Inv.is(item));
            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = Inv.stack(ItemIds.HOTBAR_START + slot);
                if (!slotKnown(slot) && !Inv.empty(stack) && Inv.is(item).test(stack)) count -= stack.getAmount();
            }
            return count + (swappedSlot() >= 0 ? offhandAmount(item) : 0);
        }

        @Override
        public @Nullable String heldItem() {
            int w = swappedSlot();
            if (w >= 0 && w == Inv.heldSlot()) return offhandId();
            return slotKnown(Inv.heldSlot()) ? Inv.id(Inv.mainHand()) : null;
        }

        @Override
        public int heldSlot() {
            return Inv.heldSlot();
        }

        @Override
        public int hotbarSlot(String item) {
            int[] slots = hotbarSlots(item);
            int slot = slots.length == 0 ? -1 : slots[0];
            if (slot < 0) {
                int w = swappedSlot();
                if (w >= 0 && offhandAmount(item) > 0) return w;
            }
            return slot;
        }

        @Override
        public boolean inventoryBusy() {
            return hotbar.busy(now) || INVENTORY.hasActiveRequest()
                || (!airPlace.batchOpen() && guard.busy(ZenithAirPlace.INSTANCE));
        }

        @Override
        public boolean parkBusy() {
            return hotbar.busy(now) || INVENTORY.hasActiveRequest() || (!airPlace.batchOpen()
                && guard.busy(ZenithAirPlace.INSTANCE) && !guard.checkWanted(ZenithAirPlace.INSTANCE));
        }

        @Override
        public boolean clickInventoryBusy(boolean fenceClicks) {
            return (fenceClicks ? hotbar.requestBusy(now) : hotbar.busy(now))
                || INVENTORY.hasActiveRequest() || (!airPlace.batchOpen() && guard.busy(ZenithAirPlace.INSTANCE));
        }

        @Override
        public int fencedSlot() {
            return hotbar.fencedSlot(now);
        }

        @Override
        public boolean prefetchHeld(String item, java.util.function.ToIntFunction<String> nextUse,
                                    java.util.function.IntPredicate replaceable) {
            if (inventoryBusy() || guard.busy(ZenithAirPlace.INSTANCE)) return false;
            hotbar.ensureHeld(Inv.is(item), Hotbar.MATERIAL_SLOTS, now, laneMaterial, slot -> {
                String id = Inv.id(Inv.stack(ItemIds.HOTBAR_START + slot));
                return id == null ? Integer.MAX_VALUE : nextUse.applyAsInt(id);
            }, true, replaceable);
            return hotbar.busy(now);
        }

        @Override
        public boolean lookaheadPrefetch() {
            return lookaheadEnabled();
        }

        @Override
        public boolean rollingClickWindow() {
            return lookaheadEnabled() && cfg.build.placeRollingClickWindow;
        }

        @Override
        public boolean slotKnown(int slot) { return InventoryEchoes.SESSION.known(slot); }

        /** Unknown hotbar slots holding {@code item}: unconfirmed, not missing, while they ask for the full inventory. */
        @Override
        public int unknownCount(String item) {
            if (!cfg.build.unknownSlotResync) return 0;
            int n = 0;
            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = Inv.stack(ItemIds.HOTBAR_START + slot);
                if (!slotKnown(slot) && !Inv.empty(stack) && Inv.is(item).test(stack)) n += stack.getAmount();
            }
            return n;
        }

        /** A stack of {@code item} in the main inventory ({@link Hotbar#ensureHeld} moves from there). */
        @Override
        public boolean moveSource(String item) {
            for (int slot = ItemIds.MAIN_START; slot < ItemIds.HOTBAR_START; slot++) {
                ItemStack stack = Inv.stack(slot);
                if (!Inv.empty(stack) && Inv.is(item).test(stack)) return true;
            }
            return false;
        }

        /** "hotbar 1 x26 unknown, 4 x1 unknown; main inventory 0": the cache's view and what placement may use. */
        @Override
        public String itemState(String item) {
            List<String> slots = new ArrayList<>();
            int fenced = hotbar.fencedSlot(now);
            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = Inv.stack(ItemIds.HOTBAR_START + slot);
                if (Inv.empty(stack) || !Inv.is(item).test(stack)) continue;
                slots.add(slot + " x" + stack.getAmount() + (slotKnown(slot) ? "" : " unknown")
                    + (slot == fenced ? " fenced" : "") + (slot == Inv.heldSlot() ? " held" : ""));
            }
            int main = 0;
            for (int slot = ItemIds.MAIN_START; slot < ItemIds.HOTBAR_START; slot++) {
                ItemStack stack = Inv.stack(slot);
                if (!Inv.empty(stack) && Inv.is(item).test(stack)) main += stack.getAmount();
            }
            return "hotbar " + (slots.isEmpty() ? "none" : String.join(", ", slots)) + "; main inventory " + main;
        }

        /** What can hold a lane: the input, this task's and anyone's inventory requests, the move fence, the guard. */
        @Override
        public String holdState() {
            return "input lost " + clicker.lostTicks() + " ticks, owned last tick " + clicker.ownedLastTick(now)
                + "; inventory request (any) " + INVENTORY.hasActiveRequest() + ", " + hotbar.state(now)
                + "; airplace guard busy " + guard.busy(ZenithAirPlace.INSTANCE) + "; container open "
                + Inv.containerOpen() + "; sprinting " + BOT.isSprinting();
        }

        @Override
        public int watchdogTicks() { return cfg.build.laneWatchdogTicks; }

        @Override
        public int watchdogHotbarTicks() { return cfg.build.laneWatchdogHotbarTicks; }

        @Override
        public void inventoryNeeded() { InventoryEchoes.SESSION.inventoryNeeded(); }

        @Override
        public void prefetchAhead(List<String> cells, java.util.function.IntUnaryOperator available,
                                  java.util.function.IntPredicate replaceable) {
            if (inventoryBusy() || guard.busy(ZenithAirPlace.INSTANCE)) return;
            List<LanePrefetch.Stack> slots = new java.util.ArrayList<>();
            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = Inv.stack(ItemIds.HOTBAR_START + slot);
                slots.add(new LanePrefetch.Stack(slot, Inv.id(stack), available.applyAsInt(slot),
                    slotKnown(slot) && replaceable.test(slot) && Hotbar.prefetchSlot(slot, stack)));
            }
            LanePrefetch.Move move = LanePrefetch.next(cells, slots, Inv.heldSlot(), item -> {
                int count = 0;
                for (int source = ItemIds.MAIN_START; source < ItemIds.HOTBAR_START; source++) {
                    ItemStack stack = Inv.stack(source);
                    if (!Inv.empty(stack) && Inv.is(item).test(stack)) count += stack.getAmount();
                }
                return count;
            }, cfg.build.placeHotbarLookaheadEvict);
            if (move != null) hotbar.prefetchMove(move.item(), move.slot(), now);
        }

        @Override
        public boolean layoutHotbar(List<String> cells) {
            if (inventoryBusy()) return false;
            List<LaneHotbar.Stack> slots = new java.util.ArrayList<>();
            for (int slot = 1; slot <= 6; slot++) {
                ItemStack stack = Inv.stack(ItemIds.HOTBAR_START + slot);
                if (Hotbar.layoutSlot(slot, stack))
                    slots.add(new LaneHotbar.Stack(slot, Inv.id(stack), Inv.empty(stack) ? 0 : stack.getAmount()));
            }
            boolean pin = cfg.build.laneLayoutPin;
            LaneHotbar.Move move = LaneHotbar.next(cells, slots, item -> {
                int count = 0;
                for (int slot = ItemIds.MAIN_START; slot < ItemIds.HOTBAR_START; slot++) {
                    ItemStack stack = Inv.stack(slot);
                    if (!Inv.empty(stack) && Inv.is(item).test(stack)) count += stack.getAmount();
                }
                return count;
            }, slot -> pin && layoutPinned.contains(slot));
            if (move == null) return true;
            if (hotbar.layoutMove(move.item(), move.slot(), now)) layoutPinned.add(move.slot());
            return false;
        }

        @Override
        public void beginAirPlaces(boolean batch) { airPlace.beginBatch(batch); }

        @Override
        public void endAirPlaces() { airPlace.endBatch(); }

        @Override
        public String moveFence() {
            return hotbar.fenceSummary();
        }

        @Override
        public void ensureHeld(String item) {
            if (guard.busy(ZenithAirPlace.INSTANCE)) return; // the cache may show an airplace swap: no inventory move
            hotbar.ensureHeld(Inv.is(item), Hotbar.MATERIAL_SLOTS, now, laneMaterial);
        }

        /** The move replaces the lane material stack needed last ({@link Hotbar#chooseTarget}, Belady's rule). */
        @Override
        public void ensureHeld(String item, java.util.function.ToIntFunction<String> nextUse) {
            if (guard.busy(ZenithAirPlace.INSTANCE)) return; // the cache may show an airplace swap: no inventory move
            hotbar.ensureHeld(Inv.is(item), Hotbar.MATERIAL_SLOTS, now, laneMaterial, slot -> {
                String id = Inv.id(Inv.stack(ItemIds.HOTBAR_START + slot));
                return id == null ? Integer.MAX_VALUE : nextUse.applyAsInt(id);
            });
        }

        @Override
        public void submit(@Nullable Float yaw, @Nullable Float pitch, int forward, int strafe, boolean jump) {
            submit(yaw, pitch, forward, strafe, jump, false);
        }

        @Override
        public void submit(@Nullable Float yaw, @Nullable Float pitch, int forward, int strafe, boolean jump,
                           boolean sneak) {
            Input in = Input.builder()
                .pressingForward(forward > 0)
                .pressingBack(forward < 0)
                .pressingLeft(strafe > 0)
                .pressingRight(strafe < 0)
                .jumping(jump)
                .sneaking(sneak)
                .build();
            if (silent && yaw != null && pitch != null) {
                // keep the real rotation; the look goes silently after this tick's click (step)
                pending = true;
                pendingYaw = yaw;
                pendingPitch = pitch;
                clicker.submit(now, null, null, in);
                return;
            }
            clicker.submit(now, yaw, pitch, in);
        }

        @Override
        public void holdStill() {
            PlaceLaneTask.this.holdStill();
        }

        @Override
        public int lostTicks() {
            return clicker.lostTicks();
        }

        @Override
        public boolean ownedLastTick() {
            return clicker.ownedLastTick(now) && !Inv.containerOpen();
        }

        @Override
        public boolean canClick() {
            return clicker.canClick(now);
        }

        @Override
        public boolean select(int slot) {
            return Clicker.selectNow(slot);
        }

        @Override
        public boolean click(Aiming.Shot shot) {
            return Clicker.sendUseItemOn(shot.ray());
        }

        /** ZenithProxy's block prediction counter: the click took the next sequence ({@code startPredicting}). */
        @Override
        public int clickSequence() {
            return CACHE.getChunkCache().getBlockStatePredictionHandler().currentSequence();
        }

        @Override
        public int ackedSequence() {
            return BlockAcks.latest();
        }

        @Override
        public boolean ackedExactly(int sequence) {
            return BlockAcks.ackedExactly(sequence);
        }

        /** Block updates are watched by the map-art module's packet handler ({@link BlockEchoes}). */
        @Override
        public long echoMark() {
            return BlockEchoes.mark();
        }

        @Override
        public boolean echoedSince(Pos pos, long mark) {
            return BlockEchoes.since(pos, mark);
        }

        /** Hotbar indexes holding {@code item}; the slot of an airplace swap the cache shows counts by the offhand stack. */
        @Override
        public int[] hotbarSlots(String item) {
            int w = swappedSlot();
            Predicate<ItemStack> is = Inv.is(item);
            int[] out = new int[9];
            int n = 0;
            for (int i = 0; i < 9; i++) {
                if (!slotKnown(i)) continue;
                ItemStack s = Inv.stack(ItemIds.HOTBAR_START + i);
                boolean has = i == w ? offhandAmount(item) > 0 : !Inv.empty(s) && is.test(s);
                if (has) out[n++] = i;
            }
            return java.util.Arrays.copyOf(out, n);
        }

        @Override
        public int hotbarCount(int slot) {
            if (!slotKnown(slot)) return 0;
            // while the cache shows an airplace swap, the stack of that hotbar slot is the one in the offhand
            ItemStack s = slot == swappedSlot() ? offhand() : Inv.stack(ItemIds.HOTBAR_START + slot);
            return s == null || Inv.empty(s) ? 0 : s.getAmount();
        }

        /**
         * Hotbar slot whose stack the cache shows in the offhand (an airplace swap, {@link AirPlaceGuard#swappedSlot}),
         * or -1. Counts and slots then refer to the hotbar slot the stack lives in.
         */
        private int swappedSlot() {
            return guard.swappedSlot(ZenithAirPlace.INSTANCE);
        }

        private @Nullable ItemStack offhand() {
            return Inv.cache().getPlayerInventory().getItemStack(ItemIds.OFFHAND);
        }

        private @Nullable String offhandId() {
            return Inv.id(offhand());
        }

        private int offhandAmount(String item) {
            ItemStack s = offhand();
            return s != null && !Inv.empty(s) && ItemIds.material(item).equals(Inv.id(s)) ? s.getAmount() : 0;
        }

        @Override
        public boolean airPlaceReady() {
            return guard.airPlaceAvailable(ZenithAirPlace.INSTANCE);
        }

        @Override
        public long clickNanoTime() { return Clicker.lastClickNanos; }

        @Override
        public boolean subTickAvailable() { return cfg.build.placeSubTickDispatch; }

        @Override
        public int airPlacesSent() { return airPlace.placed(); }

        @Override
        public boolean subTickReady() {
            var client = com.zenith.Proxy.getInstance().getClient();
            return !isFinished() && cfg.enabled && !cfg.paused && cfg.build.placeSubTickDispatch
                && !com.zenith.Proxy.getInstance().hasActivePlayer() && client != null && client.isConnected()
                && client.isOnline() && !client.isInQueue() && CACHE.getPlayerCache().isAlive()
                && clicker.completedInput();
        }

        @Override
        public Runnable scheduleClick(long at, Runnable send) {
            var client = com.zenith.Proxy.getInstance().getClient();
            LaneRun<Aiming.Shot> scheduledRun = run;
            var future = client.getClientEventLoop().schedule(() -> {
                if (client == com.zenith.Proxy.getInstance().getClient()) send.run();
                else scheduledRun.close();
            }, Math.max(0, at - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
            return () -> future.cancel(false);
        }

        @Override
        public boolean airPlace(Pos cell) {
            return airPlace.place(ZenithAirPlace.INSTANCE, Inv.heldSlot(), cell, AirPlace.face(targets.get(cell)))
                == AirPlace.Result.SENT;
        }

        /**
         * The lane start, back to a park position: no path check. The first lane start after a restock is the restock's
         * last leg ({@link SprintLegs}); once it is reached the restock legs are over. With {@code
         * build.laneStartOnFloor} the tile's border tops look far to Baritone ({@link Walker#border}) while it walks.
         */
        @Override
        public TaskStatus walk(List<GoalNearXZ> goals, BooleanSupplier arrived) {
            walker.pathCheck(null);
            walker.border(startBorder());
            startWalk = true;
            TaskStatus s;
            try {
                s = walkUntil(goals, arrived);
            } finally {
                startWalk = false;
            }
            if (s != TaskStatus.RUNNING) {
                walker.border(null);
                ctx.laneStartReached();
            }
            return s;
        }

        /**
         * A stand-spot walk: every planned path is checked ({@link Walker#pathCheck}; a refusal FAILS the walk with
         * "the path to GOAL climbs onto a block at x,y,z; not walking it", which the lane logs): never past a setback
         * cell, and with {@code noClimb} never a step up inside the stand area. The check is cleared when the walk ends.
         */
        @Override
        public TaskStatus walk(List<GoalNearXZ> goals, BooleanSupplier arrived, boolean noClimb) {
            return walk(goals, arrived, noClimb, Walker.MAX_FAILURES);
        }

        @Override
        public TaskStatus walk(List<GoalNearXZ> goals, BooleanSupplier arrived, boolean noClimb, int retries) {
            walker.pathCheck(noClimb ? floorCheck : avoid.isEmpty() ? null : avoidCheck);
            walker.border(null);
            TaskStatus s = walkUntil(goals, arrived, retries);
            if (s != TaskStatus.RUNNING) walker.pathCheck(null);
            return s;
        }

        @Override
        public String walkError() {
            return walker.error();
        }

        @Override
        public void stopWalking() {
            walker.pathCheck(null);
            walker.border(null);
            walker.stop();
        }

        /**
         * Baritone walks for this task (the task submits no input of its own this tick), no container is open and the
         * bot does not sprint: an airplace needs no rotation, so it may go out this tick (the in-reach greedy). The
         * packets still leave from the engine's tick handler, before the movement packet Baritone's input makes. Never
         * while sprinting, nor within {@code build.sprintQuietTicks} of it ({@link SprintLegs#clickWhileWalking}): placing
         * while sprinting has had no anti-cheat check of its own, and every other click waits the same window.
         */
        @Override
        public boolean clickWhileWalking() {
            // never while an elytra flight of the walk is busy: a click selects a material's slot (Walker#flightBusy)
            return !walker.flightBusy() && SprintLegs.clickWhileWalking(walker.active(), Inv.containerOpen(),
                SprintLegs.Watch.SESSION, BOT.isSprinting());
        }

        @Override
        public @Nullable List<Pos> walkPath() {
            return walker.remainingPath();
        }

        @Override
        public AdaptivePacer pacer() {
            return ctx.pacer();
        }

        @Override
        public void placed(Placement p) {
            // LaneRun counts a block as placed from a stand spot in its SPOT phase (its "N from M stand spots")
            if (run.phase() == LaneRun.Phase.SPOT) listener.placedFromStandSpot(p);
            else listener.placed(p);
        }

        /** A later lane's cell (the in-reach greedy): a walking placement, whatever the run's phase. */
        @Override
        public void placedExtra(Placement p) {
            listener.placed(p);
        }

        @Override
        public void skipped(Placement p, String reason) {
            listener.skipped(p, reason);
        }

        @Override
        public void log(String line) {
            LOG.info(line);
        }
    }
}
