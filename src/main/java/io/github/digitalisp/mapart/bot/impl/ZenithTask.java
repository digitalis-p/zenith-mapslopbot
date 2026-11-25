package io.github.digitalisp.mapart.bot.impl;

import com.zenith.Proxy;
import com.zenith.feature.pathfinder.goals.Goal;
import com.zenith.feature.player.Input;
import com.zenith.mc.block.Direction;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.jspecify.annotations.Nullable;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import io.github.digitalisp.mapart.bot.BotTask;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.config.SiteRules;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;

/**
 * Common base of the ZenithProxy task implementations: tick counter, overall timeout, walking, input and
 * hotbar helpers, and release of everything the task started (Baritone request + pathing guard, open container).
 */
abstract class ZenithTask extends BotTask {
    static final ComponentLogger LOG = ComponentLogger.logger("MapArt-Bot");

    protected final ZenithBotActions ctx;
    protected final MapArtConfig cfg;
    /** {@code site.noGoZones} when the task was made. */
    protected final NoGoZones zones;
    protected final Walker walker;
    protected final Clicker clicker = new Clicker(this);
    protected final Hotbar hotbar;
    /** Ticks since the task started (1 on the first tick). */
    protected long now;
    boolean artRecoveryAllowed = true;
    /** {@link #walkBreaking}: held while a walk may break blocks; one such walk per task. */
    private RefCountedOverride.@Nullable Handle breakHold;
    private boolean breakTried;
    /** The breaking walk's own goal when the task's is far (the near storage floor), null: the task's goals. */
    private @Nullable GoalNearXZ breakOut;
    private @Nullable DripstoneBreakThrough artRecovery;
    private PlaceLaneTask.@Nullable BreakOut artBreaking;
    protected boolean recoverArtWalks() { return artRecoveryAllowed; }
    private final long timeoutTicks;
    private boolean closedStrayContainer;
    /** Container id this task opened and must close on release, or 0. */
    protected int ownContainerId;
    /** What the walker's elytra flight legs get from this task ({@link FlightLeg}). */
    private final FlightLeg.Host flightHost;
    /** The per-tick zone entry check and the zone the bot may leave ({@link ZoneEntry}). */
    private final ZoneEntry zoneEntry = new ZoneEntry();

    ZenithTask(ZenithBotActions ctx, long timeoutTicks) {
        this(ctx, timeoutTicks, ctx.noGoZones());
    }

    /** A task with its own no-go zones (the foot route out of a zone walks inside it: {@link FootRouteTask}). */
    ZenithTask(ZenithBotActions ctx, long timeoutTicks, NoGoZones zones) {
        this.ctx = ctx;
        this.cfg = ctx.config();
        this.zones = zones;
        this.walker = new Walker(zones, () -> cfg.build.boxedInEscape, () -> cfg.build.noGoCornerDetour,
            () -> cfg.build.walkStepOut);
        this.walker.edge(new EdgeApproach(this::edgeSneakHere, this::edgeDescentsAt, this::edgeHolesAt, zones, ctx.world()));
        this.walker.plannedFalls(() -> cfg.build.edgePlannedFalls);
        this.walker.rowRun(this::rowCfg);
        this.hotbar = new Hotbar(this, clicker, () -> cfg.build.hotbarMoveFence, this instanceof PlaceLaneTask,
            () -> cfg.build.hotbarMoveForeignStateId);
        this.flightHost = new FlightLeg.Host() {
            @Override public MapArtConfig.BuildCfg build() { return cfg.build; }
            @Override public Clicker clicker() { return clicker; }
            @Override public Hotbar hotbar() { return hotbar; }
            @Override public SprintLegs.Walk kind() { return walkKind(); }
            @Override public boolean inRestock() { return ctx.inRestock(); }
            @Override public boolean mayFly() { return flightAllowed(); }
        };
        this.walker.flight(new FlightLeg(flightHost));
        this.timeoutTicks = timeoutTicks;
    }

    /** {@code build.edgeSneak}, except in the boxes of {@code site.edgeSneakOff} (stairs). */
    private boolean edgeSneakHere() {
        if (!cfg.build.edgeSneak) return false;
        if (cfg.site.edgeSneakOff.isEmpty()) return true;
        Pos f = Walker.feet();
        return SiteRules.regionAt(cfg.site.edgeSneakOff, f.x(), f.y(), f.z()) == null;
    }

    /** {@code build.edgeDescents}: a planned step down into {@code to} is checked, except in {@code site.edgeSneakOff}. */
    private boolean edgeDescentsAt(Pos to) {
        if (!cfg.build.edgeDescents) return false;
        return cfg.site.edgeSneakOff.isEmpty() || SiteRules.regionAt(cfg.site.edgeSneakOff, to.x(), to.y(), to.z()) == null;
    }

    /** {@code build.edgeHoles}: holes are guarded at {@code p} (the feet, a move's end), except in {@code site.edgeSneakOff}. */
    private boolean edgeHolesAt(Pos p) {
        if (!cfg.build.edgeHoles) return false;
        return cfg.site.edgeSneakOff.isEmpty() || SiteRules.regionAt(cfg.site.edgeSneakOff, p.x(), p.y(), p.z()) == null;
    }

    @Override
    protected final TaskStatus onTick() {
        now++;
        if (timeoutTicks > 0 && now > timeoutTicks) return fail("timed out after " + now + " ticks" + progress());
        if (!CACHE.getPlayerCache().isAlive()) return fail("player is dead");
        if (!closedStrayContainer) {
            closedStrayContainer = true;
            if (!opensContainers() && Inv.containerOpen()) closeContainerNow();
        }
        String entered = checksNoGoZones() ? enteredNoGoZone() : null;
        if (entered != null) return fail(entered);
        // the chestplate back on in place of the elytra the restock's flights wore (build.elytraLegs, FlightLeg); an
        // elytra worn that the plugin did not put on is taken over first (a restart or a player's control left it on)
        if (FlightLeg.backstopDue() && FlightLeg.backstop(flightHost, now, clicker, walker.flight())) return TaskStatus.RUNNING;
        // a gear swap's clicks still going out: nothing else touches the inventory or opens a menu meanwhile
        if (FlightLeg.gearClicksPending()) {
            clicker.submit(now, null, null, null);
            return TaskStatus.RUNNING;
        }
        // a flight of this task's walk still in the air lands first: the step cannot end the walk mid-air (Walker#landFirst)
        TaskStatus aloft = walker.landFirst(now);
        if (aloft != null) return aloft;
        return step();
    }

    /**
     * The no-go rule's last line ({@link #enteredNoGoZone}) applies to this task. False only for a task that does not
     * move the bot itself while it rides a vehicle through the zones (the colour-suppression ride). Whatever a task
     * answers, the check is off while the bot sits in a vehicle ({@link ZoneEntry}): boarding a cart whose rail lies in
     * a zone or a lethal-edge box is no entry.
     */
    protected boolean checksNoGoZones() {
        return true;
    }

    /**
     * Last line of the no-go rule, for every way the bot moves (Baritone, the lane walk's own keys, a knock-back): the
     * feet centre entering a zone ({@link NoGoZones#FEET_MARGIN} deep: a corner or an edge clipped for a tick while the
     * bot turns is no entry, {@link NoGoZones#deepAt}) stops Baritone at once and fails the task. Leaving the zone the
     * bot stood in on the task's first tick is allowed, until the feet cell is out of it. Seated in a vehicle nothing is
     * an entry, and the zone the bot gets out in may be left like the first tick's. Null while all is well.
     */
    private @Nullable String enteredNoGoZone() {
        if (zones.isEmpty()) return null;
        Pos feet = Walker.feet();
        NoGoZones.Zone z = zoneEntry.entered(zones, now == 1, ctx.world().vehicle() != null, feet, BOT.getX(), BOT.getY(),
            BOT.getZ());
        if (z == null) return null;
        BARITONE.getPathingBehavior().forceCancel();
        EdgeApproach.halt(); // Baritone's keys of this tick were already submitted
        return "entered no-go zone " + z + " at " + feet.key() + "; stopped";
    }

    /**
     * Refusal for touching (breaking, pressing, opening, placing into) {@code pos}: it lies in a protected region
     * ({@code site.protectedRegions}). Null when allowed.
     */
    protected @Nullable String protectedRegionRefusal(Pos pos, String what) {
        MapArtConfig.RegionCfg r = SiteRules.protectedRegionAt(cfg.site, pos);
        if (r == null) return null;
        return "refusing to " + what + " " + pos + ": inside protected region "
            + SiteRules.describe(cfg.site.protectedRegions, r);
    }

    protected abstract TaskStatus step();

    /** Extra text appended to the timeout message. */
    protected String progress() {
        return "";
    }

    /** Container tasks return true so a container they are about to use is not closed on the first tick. */
    protected boolean opensContainers() {
        return false;
    }

    @Override
    protected void release() {
        releaseBreak();
        if (artRecovery != null) artRecovery.cancel();
        if (artBreaking != null) artBreaking.cancel();
        walker.stop();
        if (ownContainerId != 0 && Inv.openContainerId() == ownContainerId) closeContainerNow();
        ownContainerId = 0;
        onRelease();
    }

    protected void onRelease() { }

    protected double reach() {
        return cfg.build.reach;
    }

    /**
     * What this task's walk is now ({@link SprintLegs.Walk}): its sprint rule follows from it
     * ({@link ZenithBotActions#sprintRule}). Default: a plain {@code walkTo}.
     */
    protected SprintLegs.Walk walkKind() {
        return SprintLegs.Walk.WALK;
    }

    /** This task's walks may fly ({@link FlightLeg}, its kind's switch permitting); false for a retreat from mobs. */
    protected boolean flightAllowed() {
        return true;
    }

    /** Gives the walker this tick's sprint rule for {@link #walkKind} (null: no sprint), before it ticks. */
    private void sprintRule() {
        SprintLegs.Walk kind = walkKind();
        walker.sprint(ctx.sprintRule(kind), kind.name() + (kind.restockLeg() && ctx.inRestock() ? ", restock" : ""));
    }

    /**
     * Before a walk starts: turns a yaw beyond ±180 back ({@link Clicker#unwindYaw}), noting it for the walk's start line
     * ({@link Walker#noteUnwind}). Always false with {@code build.walkShortTurns}: Baritone's turns then go the short way
     * ({@link WalkYaw}), so the bot's yaw never needs to be brought back and the walk starts at once.
     */
    private boolean unwindYaw() {
        if (cfg.build.walkShortTurns) return false;
        float before = BOT.getYaw();
        if (!clicker.unwindYaw(now)) return false;
        walker.noteUnwind(before);
        return true;
    }

    /** Walks until the feet block is within {@code range} blocks horizontally of target (Baritone's own test). */
    protected TaskStatus walkNear(Pos target, int range, int yTolerance) {
        GoalNearXZ goal = new GoalNearXZ(target.x(), target.y(), target.z(), range, yTolerance);
        if (!walker.active() && !feetIn(goal) && unwindYaw()) return TaskStatus.RUNNING;
        sprintRule();
        return walkRecover(List.of(goal), () -> feetIn(goal), Walker.MAX_FAILURES);
    }

    /** Feet block (ZenithProxy PlayerContext convention: y + 0.1251 for soul sand) inside the goal. */
    protected static boolean feetIn(Goal goal) {
        return goal.isInGoal((int) Math.floor(BOT.getX()), (int) Math.floor(BOT.getY() + 0.1251), (int) Math.floor(BOT.getZ()));
    }

    /** Walks with escalating goals until {@code arrived} (e.g. "target reachable and visible"). */
    protected TaskStatus walkUntil(List<? extends Goal> goals, BooleanSupplier arrived) {
        return walkUntil(goals, arrived, Walker.MAX_FAILURES);
    }

    /** {@link #walkUntil(List, BooleanSupplier)} failing after {@code retries} failed path requests. */
    protected TaskStatus walkUntil(List<? extends Goal> goals, BooleanSupplier arrived, int retries) {
        if (!walker.active() && !arrived.getAsBoolean() && unwindYaw()) return TaskStatus.RUNNING;
        sprintRule();
        return walkRecover(goals, arrived, retries);
    }

    /** Raw legs used while opening a corridor: never recursively open another one. */
    final TaskStatus walkRaw(List<? extends Goal> goals, BooleanSupplier arrived, int retries) {
        sprintRule();
        return walker.tick(now, goals, arrived, retries);
    }

    protected final TaskStatus walkRecover(List<? extends Goal> goals, BooleanSupplier arrived, int retries) {
        if (!recoverArtWalks() || goals.stream().anyMatch(g -> !(g instanceof GoalNearXZ)))
            return walkRaw(goals, arrived, retries);
        if (breakHold != null) return breakingLeg(goals, arrived, retries);
        TaskStatus s = walkRecoverArt(goals, arrived, retries);
        if (s != TaskStatus.FAILED || breakTried || !cfg.build.baritoneBreakEscape) return s;
        // the last resort: once more with the pathfinder allowed to break (BreakGuard), from wherever the bot is sealed;
        // a far goal (a chest 300 blocks off) first only to the nearest storage floor, then on as usual (live remu
        // [2,2]: the breaking walk to the far cartography table found no path; "pathfinder goto" to the near storage did)
        GoalNearXZ last = (GoalNearXZ) goals.getLast();
        Pos f = Walker.feet();
        breakOut = null;
        if (Math.hypot(last.x() - f.x(), last.z() - f.z()) > BREAK_NEAR) {
            Pos best = null;
            for (Pos c : storageFloor()) if (best == null || Math.hypot(c.x() - f.x(), c.z() - f.z())
                < Math.hypot(best.x() - f.x(), best.z() - f.z())) best = c;
            if (best != null) breakOut = new GoalNearXZ(best.x(), best.y(), best.z(), 2, 2);
        }
        LOG.info("{} walk failed ({}); once more with the pathfinder allowed to break blocks where no walk exists{}",
            walkKind(), walker.error(), breakOut != null ? ", first to the near storage at " + breakOut : "");
        walker.stop();
        return breakingLeg(goals, arrived, retries);
    }

    /** Goals farther than this go by the near storage when a walk has to break its way out. */
    static final int BREAK_NEAR = 64;

    private TaskStatus breakingLeg(List<? extends Goal> goals, BooleanSupplier arrived, int retries) {
        GoalNearXZ out = breakOut;
        if (out == null) return walkBreaking(goals, arrived, retries);
        TaskStatus s = walkBreaking(List.of(out), () -> feetIn(out), retries);
        if (s == TaskStatus.SUCCESS) {
            breakOut = null;
            walker.stop();
            return TaskStatus.RUNNING; // out: the task's own walk goes on as usual
        }
        return s;
    }

    /**
     * A walk the pathfinder may break blocks for ({@link BreakGuard}: only where no walk exists), at most once per task;
     * the hold ends with the walk. Never for {@link #recoverArtWalks} false tasks (the map station: the map would show
     * the holes) unless the caller is a lane's own last resort ({@link PlaceLaneTask}).
     */
    protected final TaskStatus walkBreaking(List<? extends Goal> goals, BooleanSupplier arrived, int retries) {
        if (breakHold == null) {
            if (breakTried) return TaskStatus.FAILED;
            breakTried = true;
            breakHold = BreakGuard.acquire();
        }
        BreakGuard.enforce();
        TaskStatus s = walkRaw(goals, arrived, retries);
        if (s != TaskStatus.RUNNING) {
            releaseBreak();
            LOG.info("{} walk with breaking allowed: {}", walkKind(), s == TaskStatus.SUCCESS ? "arrived" : walker.error());
        }
        return s;
    }

    private void releaseBreak() {
        if (breakHold != null) breakHold.release();
        breakHold = null;
    }

    /**
     * Floor cells of the material storage regions near the bot (within 160 blocks of a region's centre, at most 4000):
     * the near sorter the walks to and from the tile pass.
     */
    private List<Pos> storageFloor() {
        List<Pos> out = new java.util.ArrayList<>();
        Pos f = Walker.feet();
        BreakIn.Grid g = artWalkGrid();
        for (var r : io.github.digitalisp.mapart.config.CsMaterials.allRegions(cfg.site)) {
            if (r == null || r.min == null || r.max == null) continue;
            if (Math.max(Math.abs(f.x() - (r.min.x() + r.max.x()) / 2), Math.abs(f.z() - (r.min.z() + r.max.z()) / 2)) > 160)
                continue;
            // the stand row: the chests' own floor level, one cell in front of or behind them (live remu [2,2]: a cell
            // on the art level beside the walkway was no way out; the stand row at the chests' level was)
            int y = r.min.y();
            for (int x = r.min.x(); x <= r.max.x() && out.size() < 4000; x++)
                for (int z : new int[]{r.min.z() - 1, r.max.z() + 1}) {
                    Pos c = new Pos(x, y, z);
                    if (zones.at(c) == null && g.passable(c)) out.add(c);
                }
            for (int z = r.min.z(); z <= r.max.z() && out.size() < 4000; z++)
                for (int x : new int[]{r.min.x() - 1, r.max.x() + 1}) {
                    Pos c = new Pos(x, y, z);
                    if (zones.at(c) == null && g.passable(c)) out.add(c);
                }
        }
        return out;
    }

    private TaskStatus walkRecoverArt(List<? extends Goal> goals, BooleanSupplier arrived, int retries) {
        if (artRecovery == null) artRecovery = new DripstoneBreakThrough(new DripstoneBreakThrough.Io() {
            @Override public boolean enabled() { return cfg.build.dripstoneBreakThrough; }
            @Override public Pos feet() { return Walker.feet(); }
            @Override public BreakIn.Grid grid() { return artWalkGrid(); }
            @Override public BreakIn.Target target(Pos p) {
                var t = ctx.walkArtAt(p);
                if (t == null) return null;
                boolean air = TopFaceRules.placeMethod(t.block(), PlaceMode.of(cfg.build)) == PlaceMethod.AIRPLACE;
                return new BreakIn.Target(p, air, BlockStates.collisionTop(t.block()) > LaneRun.TALL);
            }
            @Override public BreakIn.Reach reach() { return new BreakIn.Reach(ZenithTask.this.reach() - 0.2, cfg.build.airPlaceRange - 0.2); }
            @Override public TaskStatus breakWalls(List<Pos> walls, List<Pos> path) {
                if (artBreaking == null) artBreaking = PlaceLaneTask.BreakOut.recovery(ZenithTask.this, walls, path);
                TaskStatus status = artBreaking.tick();
                ctx.repairWalkCells(walls.reversed());
                if (status != TaskStatus.RUNNING) artBreaking = null;
                return status;
            }
            @Override public void stop() { walker.stop(); }
            @Override public void cancelBreaks() {
                if (artBreaking != null) artBreaking.cancel();
                artBreaking = null;
            }
            @Override public void restore(List<Pos> cells) { ctx.repairWalkCells(cells); }
            @Override public void log(String line) { LOG.info(line); }
            @Override public List<Pos> approachSeeds() { return storageFloor(); }
            @Override public String relative(Pos p) {
                for (var pl : cfg.site.platforms) if (p.y() == pl.artY && p.x() >= pl.minX && p.x() < pl.minX + 128
                    && p.z() >= pl.minZ && p.z() < pl.minZ + 128)
                    return "[X" + (p.x() - pl.minX) + "," + p.y() + ",Z" + (p.z() - pl.minZ) + "]";
                return p.key();
            }
        });
        return artRecovery.tick(goals.stream().map(g -> (GoalNearXZ) g).toList(), walkKind().name(),
            () -> walkRaw(goals, arrived, retries));
    }

    BreakIn.Grid artWalkGrid() {
        var world = ctx.world();
        return new BreakIn.Grid() {
            boolean clear(Pos p) {
                if (!world.isLoaded(p) || world.hasFluid(p)) return false;
                var b = world.block(p);
                return b.isAir() || BlockStates.collisionTop(b) == 0;
            }
            boolean safe(Pos p) {
                return world.isLoaded(p) && world.isLoaded(p.below()) && !world.hasFluid(p) && zones.at(p) == null;
            }
            /**
             * Low blocks with a collision box Baritone neither walks through nor onto: candles (live 2026-10-13, remu
             * [2,2]: the grid walked over white candles to a way off the tile Baritone could not take, so the escape
             * from the sealed area was never planned).
             */
            boolean baritoneWall(BlockSpec b) {
                return b.id().endsWith("candle");
            }
            @Override public boolean passable(Pos p) {
                if (!safe(p)) return false;
                var b = world.block(p);
                if (baritoneWall(b)) return false;
                return BlockStates.collisionTop(b) <= LaneRun.TALL
                    ? BlockStates.blocksPlacement(world.stateId(p.below())) && clear(p.above())
                    : BlockStates.walkOnTop(b) && clear(p.above()) && clear(p.above().above());
            }
            @Override public boolean floor(Pos p) { return passable(p) && BlockStates.collisionTop(world.block(p)) <= LaneRun.TALL; }
            @Override public boolean breakable(Pos p) {
                var target = ctx.walkArtAt(p);
                if (!safe(p) || target == null || target.itemId() == null) return false;
                var b = world.block(p);
                if (!baritoneWall(b) && (BlockStates.collisionTop(b) <= LaneRun.TALL || BlockStates.walkOnTop(b))) return false;
                if (!WallRoute.clearedAnyway(cfg.site, p, b.id()) || SiteRules.protectedAt(cfg.site, ctx.protectedIds(), p, b.id())
                    || SiteRules.protectedRegionAt(cfg.site, p) != null) return false;
                return (ItemIds.ns(target.itemId()).equals("minecraft:pointed_dripstone") || Inv.count(Inv.is(target.itemId())) > 0)
                    && BlockStates.blocksPlacement(world.stateId(p.below())) && clear(p.above());
            }
            @Override public double outlineTop(Pos p) {
                if (!world.isLoaded(p)) return 1;
                int state = world.stateId(p);
                double top = 0;
                for (var box : com.zenith.Globals.BLOCK_DATA.getInteractionBoxesFromBlockStateId(state))
                    top = Math.max(top, box.maxY());
                return top;
            }
            @Override public boolean standAllowed(Pos p) { return zones.at(p) == null; }
        };
    }

    /**
     * Escalating goals around a block to interact with: within 3, 2, then 1 block horizontally, feet within 2 blocks
     * of the block's height. Only for blocks on the bot's own floor (art-layer breaks and placements, buttons): the
     * goals ignore line of sight, and a block more than 2 above the floor has no goal cell at all. Containers use
     * stand spots instead ({@link Approach}, {@link StandSpots}).
     */
    protected static List<GoalNearXZ> approachGoals(Pos target) {
        return List.of(
            new GoalNearXZ(target.x(), target.y(), target.z(), 3, 2),
            new GoalNearXZ(target.x(), target.y(), target.z(), 2, 2),
            new GoalNearXZ(target.x(), target.y(), target.z(), 1, 2));
    }

    /** The run rows of the platforms that have one ({@code platforms[].runRow}, {@code build.rowRun}). */
    private RowRun.Cfg rowCfg() {
        if (!cfg.build.rowRun) return RowRun.Cfg.OFF;
        java.util.List<Pos> rows = new java.util.ArrayList<>();
        for (var p : cfg.site.platforms) if (p != null && p.enabled && p.runRow != null) rows.add(p.runRow);
        return rows.isEmpty() ? RowRun.Cfg.OFF : new RowRun.Cfg(rows, cfg.build.rowRunBand, cfg.build.rowRunMinDx);
    }

    /** Keeps the input owned and the bot still (rotation unchanged). */
    protected void holdStill() {
        clicker.submit(now, null, null, null);
    }

    /**
     * {@link Approach.Io} on ZenithProxy for this task: the bot, the cached world, Baritone, our clicks. The target
     * blocks come from {@code targets} (known once the chunk is loaded); {@code allowed} limits where the bot may stand.
     * For a placement {@link #configure} narrows each target to one face, makes every input of the task sneak (a
     * sneak-click places against a crafting table instead of opening it; the eye is then 1.27 above the feet) and keeps
     * the bot's box out of the cell being filled.
     */
    class ApproachIo implements Approach.Io<Aiming.Shot> {
        private final Supplier<List<Pos>> targets;
        private final StandSpots.CellRule allowed;
        private @Nullable Map<Pos, Direction> faces;
        private boolean sneak;
        private @Nullable Pos keepClear;

        ApproachIo(Supplier<List<Pos>> targets, StandSpots.CellRule allowed) {
            this.targets = targets;
            this.allowed = allowed;
        }

        /** Only these faces of the targets; sneak while getting ready and clicking; keep the box out of {@code clear}. */
        void configure(@Nullable Map<Pos, Direction> onlyFaces, boolean sneakToClick, @Nullable Pos clear) {
            this.faces = onlyFaces;
            this.sneak = sneakToClick;
            this.keepClear = clear;
        }

        @Override
        public double eyeHeight() {
            return sneak ? Aim.SNEAK_EYE_HEIGHT : Aim.EYE_HEIGHT;
        }

        @Override
        public boolean readyToClick() {
            if (sneak && !BOT.isSneaking()) return false;
            return keepClear == null
                || !Aim.cellOverlaps(keepClear.x(), keepClear.y(), keepClear.z(), Aim.playerBox(BOT.getX(), BOT.getY(), BOT.getZ(), 0.05));
        }

        @Override public double x() { return BOT.getX(); }
        @Override public double y() { return BOT.getY(); }
        @Override public double z() { return BOT.getZ(); }
        @Override public boolean onGround() { return BOT.isOnGround(); }
        @Override public float yaw() { return BOT.getYaw(); }

        @Override
        public boolean isLoaded(Pos p) {
            return ctx.world().isLoaded(p);
        }

        @Override
        public BlockSpec block(Pos p) {
            return ctx.world().block(p);
        }

        @Override
        public boolean hasFluid(Pos p) {
            return ctx.world().hasFluid(p);
        }

        @Override
        public double collisionTop(BlockSpec spec) {
            return BlockStates.collisionTop(spec);
        }

        @Override
        public boolean walksOn(BlockSpec floor) {
            return !cfg.build.standSpotWalkable || BlockStates.pathfinderWalksOn(floor);
        }

        @Override
        public boolean allowed(Pos feet) {
            return allowed.test(feet) && zones.at(feet) == null
                && (keepClear == null || !StandSpots.covers(feet, keepClear));
        }

        @Override
        public String excludedBecause(Pos feet) {
            if (!allowed.test(feet)) return allowed.refused();
            NoGoZones.Zone z = zones.at(feet);
            return z != null ? "in no-go zone " + z.label() : StandSpots.IN_THE_WAY;
        }

        @Override
        public boolean visibleFrom(double ex, double ey, double ez, double reach) {
            return Aiming.faceCentre(ex, ey, ez, targets.get(), reach, false, faces) != null;
        }

        @Override
        public String hiddenFrom(double ex, double ey, double ez, double reach) {
            return Aiming.hiddenFrom(ex, ey, ez, targets.get(), reach, faces);
        }

        @Override
        public Aiming.@Nullable Shot aim(double reach) {
            return Aiming.faceCentre(Aiming.eyeX(), Aiming.eyeY(), Aiming.eyeZ(), targets.get(), reach, true, faces);
        }

        @Override
        public Aiming.@Nullable Shot recheck(Aiming.Shot aimed, double reach) {
            var r = aimed.ray();
            return Aiming.check(r.x(), r.y(), r.z(), r.direction(), BOT.getYaw(), BOT.getPitch(), reach);
        }

        @Override
        public TaskStatus walk(GoalNearXZ goal, BooleanSupplier arrived) {
            return walkUntil(List.of(goal), arrived);
        }

        @Override
        public TaskStatus walk(GoalNearXZ goal, BooleanSupplier arrived, int retries) {
            if (!walker.active() && !arrived.getAsBoolean() && unwindYaw()) return TaskStatus.RUNNING;
            sprintRule();
            return walkRecover(List.of(goal), arrived, retries);
        }

        @Override
        public String walkError() {
            return walker.error();
        }

        @Override
        public void stopWalking() {
            walker.stop();
        }

        @Override
        public void submit(@Nullable Float yaw, @Nullable Float pitch, int forward, int strafe) {
            Input in = Input.builder()
                .pressingForward(forward > 0)
                .pressingBack(forward < 0)
                .pressingLeft(strafe > 0)
                .pressingRight(strafe < 0)
                .sneaking(sneak)
                .build();
            clicker.submit(now, yaw, pitch, in);
        }

        @Override
        public void holdStill() {
            if (sneak) clicker.submit(now, null, null, Input.builder().sneaking(true).build());
            else ZenithTask.this.holdStill();
        }

        @Override
        public boolean canClick() {
            return clicker.canClick(now);
        }

        @Override
        public boolean click(Aiming.Shot shot) {
            return Clicker.sendUseItemOn(shot.ray());
        }

        @Override
        public void log(String line) {
            LOG.info(line);
        }
    }

    /**
     * {@link FlightGear.Click}s as ZenithProxy actions in menu {@code containerId} (0: the player inventory), as
     * {@link FlightLeg} sends its gear swaps: a hotbar-key trade ({@code MoveToHotbarSlot}, SWAP) or a plain left click
     * ({@code ClickItem}, PICKUP).
     */
    static List<com.zenith.feature.inventory.actions.InventoryAction> clickActions(int containerId,
                                                                                    List<FlightGear.Click> clicks) {
        List<com.zenith.feature.inventory.actions.InventoryAction> out = new java.util.ArrayList<>(clicks.size());
        for (FlightGear.Click k : clicks) {
            out.add(k.swapKey()
                ? new com.zenith.feature.inventory.actions.MoveToHotbarSlot(containerId, k.slot(),
                    org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction.from(k.hotbarKey()))
                : new com.zenith.feature.inventory.actions.ClickItem(containerId, k.slot(),
                    org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction.LEFT_CLICK));
        }
        return out;
    }

    /** Hotbar index 0..8 holds nothing (the open menu's view of it while one is open). */
    static boolean hotbarEmpty(int h) {
        return Inv.empty(Inv.stack(ItemIds.HOTBAR_START + h));
    }

    /** Closes whatever container is open right now (direct packet; the cache updates on send). */
    static void closeContainerNow() {
        var client = Proxy.getInstance().getClient();
        int id = Inv.openContainerId();
        if (client == null || !client.isConnected() || id == 0) return;
        client.sendAsync(new ServerboundContainerClosePacket(id));
    }
}
