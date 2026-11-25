package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.PickupResult;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.Pos;

import com.zenith.feature.player.Input;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import static com.zenith.Globals.BOT;

/**
 * The sorter's pickup spot ({@code platforms[].sorterPickup}): items fall past
 * it while they are sorted, so a bot standing there picks them up without a click. Walks onto {@code standAt} as every
 * walk does (an edge: {@link EdgeApproach}'s stops and sneak apply), then holds still and watches the inventory:
 * it ends when every wanted item is carried in the wanted amount ({@code covered}), nothing more came for
 * {@code stallTicks} ({@code stalled}), {@code maxTicks} passed ({@code time}) or at most {@code minFree} slots are
 * empty ({@code slots}). It sends nothing and never leaves the cell; a bot found away from it fails the task.
 */
final class PickupStandTask extends ZenithTask {
    private enum Phase { WALK_PRIME, WALK, CENTRE, STAND, LEAVE }

    private final Pos standAt;
    private final @Nullable Pos prime;
    private final Map<String, Integer> want;
    private final int stallTicks;
    private final int maxTicks;
    private final int minFree;
    private final Consumer<PickupResult> result;
    private Phase phase;
    private Map<String, Integer> first = Map.of();
    private long standSince;
    private long lastGain;
    private Map<String, Integer> start = Map.of();
    private int carriedLast;

    PickupStandTask(ZenithBotActions ctx, @Nullable Pos prime, Pos standAt, Map<String, Integer> want,
                    int stallTicks, int maxTicks, int minFree, Consumer<PickupResult> result) {
        super(ctx, WalkTask.walkTimeout(standAt) + (prime == null ? 0 : WalkTask.walkTimeout(prime))
            + maxTicks + 400);
        this.prime = prime;
        this.phase = prime == null ? Phase.WALK : Phase.WALK_PRIME;
        this.standAt = standAt;
        this.want = want;
        this.stallTicks = Math.max(1, stallTicks);
        this.maxTicks = Math.max(1, maxTicks);
        this.minFree = minFree;
        this.result = result;
    }

    @Override
    public String name() {
        return "pickup stand at " + standAt;
    }

    @Override
    protected SprintLegs.Walk walkKind() {
        return SprintLegs.Walk.DROP_POINT;
    }

    @Override
    protected String progress() {
        return " (phase " + phase + ")";
    }

    private Map<String, Integer> carried() {
        Map<String, Integer> m = new TreeMap<>();
        for (String id : want.keySet()) m.put(id, Inv.count(s -> id.equals(Inv.id(s))));
        return m;
    }

    private static int sum(Map<String, Integer> m) {
        int n = 0;
        for (int v : m.values()) n += v;
        return n;
    }

    // ---- a leg along soul sand (SoulSandRoute): waypoints, then the target
    private @Nullable Pos legFor;
    private List<Pos> waypoints = List.of();
    private int waypoint;
    private int reprimes;
    private boolean atEdgeOnce;
    private PickupWatch watch;

    /** Walks to within a block of {@code target} (a plain walk, never the break retry), via a soul sand row when one exists. */
    private TaskStatus walkLeg(Pos target) {
        if (!target.equals(legFor)) {
            legFor = target;
            waypoint = 0;
            waypoints = route(target);
            legStart = now;
            legX = BOT.getX();
            legZ = BOT.getZ();
        }
        while (waypoint < waypoints.size()) {
            Pos w = waypoints.get(waypoint);
            GoalNearXZ g = new GoalNearXZ(w.x(), w.y(), w.z(), 1, 1);
            TaskStatus s = feetIn(g) ? TaskStatus.SUCCESS : walkRaw(List.of(g), () -> feetIn(g), Walker.MAX_FAILURES);
            if (s == TaskStatus.RUNNING) return s;
            walker.stop();
            if (s == TaskStatus.FAILED) {
                LOG.info("Pickup stand: the soul sand row's waypoint {} cannot be walked to; straight on", w);
                waypoints = List.of();
                break;
            }
            waypoint++;
        }
        // within a block of the cell: an item falling past it is still picked up from beside it (pickup box: the
        // player's, 1 block wider each side), and the cell itself may not be a walkable goal for the pathfinder
        // (live 2026-10-14 02:54: "pathing to GoalNearXZ[..., range=0, yTolerance=1] failed 5 times")
        GoalNearXZ goal = new GoalNearXZ(target.x(), target.y(), target.z(), 1, 1);
        TaskStatus s = feetIn(goal) ? TaskStatus.SUCCESS : walkRaw(List.of(goal), () -> feetIn(goal), Walker.MAX_FAILURES);
        if (s == TaskStatus.SUCCESS) {
            walker.stop();
            noteLeg(target.equals(standAt) ? "edge" : "prime");
            legFor = null;
        }
        return s;
    }

    private List<Pos> route(Pos target) {
        if (!cfg.build.soulSandRoute) return List.of();
        // the run row replaces the scan: Walker runs the platform's row itself (RowRun)
        if (cfg.build.rowRun && cfg.site.platforms.stream().anyMatch(p -> p != null && p.enabled && p.runRow != null))
            return List.of();
        var world = ctx.world();
        Pos from = new Pos((int) Math.floor(BOT.getX()), target.y(), (int) Math.floor(BOT.getZ()));
        java.util.function.Function<Pos, String> block = p -> world.block(p).id();
        SoulSandRoute.Route r = SoulSandRoute.plan(from, target, Math.max(0, cfg.build.soulSandRouteSpread), block,
            world::isLoaded);
        if (r == null) {
            LOG.info("Pickup stand: no soul sand row for the walk to {}; cells that are not: {}", target,
                SoulSandRoute.diagnose(from, target, block, world::isLoaded));
            return List.of();
        }
        LOG.info("Pickup stand: walking to {} along {}={} ({} % soul sand with a clear walking level, {} % on the straight"
            + " line)", target, r.alongX() ? "z" : "x", r.row(), Math.round(100 * r.share()),
            Math.round(100 * r.directShare()));
        return r.waypoints();
    }

    // ---- stepping onto the exact cell: sneaking, with the plugin's own keys, centred within 0.2 (live: Baritone refuses
    // the exact cell, the bot waited beside the edge and the items fell past)
    static final double ON_CELL = 0.2;
    static final int CENTRE_TICKS = 40;
    private long centreSince;
    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;
    private long legStart;
    // ---- leaving the edge (live 2026-10-14 04:25: the walk back to the prime cell started without sneaking while the bot
    // hung over the chute at the edge, and it dropped 4 blocks into the 1-cell sorter chute): sneaking back onto the cell
    // the walk arrived on (solid floor the pathfinder stood on) before anything else moves the bot
    static final double OFF_EDGE = 0.25;
    static final int LEAVE_TICKS = 60;
    private double safeX = Double.NaN;
    private double safeZ = Double.NaN;
    private long leaveSince;
    private @Nullable String leaveWhy;
    private @Nullable Map<String, Integer> leaveHave;
    private double legX;
    private double legZ;

    private double offX() {
        return standAt.x() + 0.5 - BOT.getX();
    }

    private double offZ() {
        return standAt.z() + 0.5 - BOT.getZ();
    }

    private boolean onCell() {
        return Math.abs(offX()) <= ON_CELL && Math.abs(offZ()) <= ON_CELL;
    }

    /** One tick sneaking towards the cell's centre (no keys once within {@link #ON_CELL}). */
    private void sneakTowardsCell() {
        var in = Input.builder().sneaking(true);
        if (!onCell()) {
            int[] keys = Aim.movementKeys(BOT.getYaw(), offX(), offZ());
            in = in.pressingForward(keys[0] > 0).pressingBack(keys[0] < 0).pressingLeft(keys[1] > 0).pressingRight(keys[1] < 0);
        }
        clicker.submit(now, null, null, in.build());
    }

    private void noteLeg(String what) {
        double d = Math.hypot(BOT.getX() - legX, BOT.getZ() - legZ);
        double secs = Math.max(1, now - legStart) / 20.0;
        LOG.info("Pickup stand: {} leg {} blocks in {} s = {} blocks/s", what, Math.round(d),
            String.format("%.1f", secs), String.format("%.1f", d / secs));
    }

    private TaskStatus enterStand() {
        if (prime != null) LOG.info("Pickup stand: at the edge {}, waiting", standAt);
        phase = Phase.STAND;
        lastGain = now;
        carriedLast = sum(carried());
        if (!atEdgeOnce) {
            atEdgeOnce = true;
            standSince = now;
            start = carried();
            carriedLast = sum(start);
            if (cfg.build.sorterPickupWatchRadius > 0) watch = new PickupWatch(want.keySet());
        }
        sneakTowardsCell();
        return TaskStatus.RUNNING;
    }

    /** Off the edge first (sneaking), then the stand ends with {@code why}, or (null) the walk back to the prime cell. */
    private TaskStatus leave(@Nullable String why, @Nullable Map<String, Integer> have) {
        leaveWhy = why;
        leaveHave = have;
        leaveSince = now;
        if (Double.isNaN(safeX)) {
            safeX = BOT.getX();
            safeZ = BOT.getZ();
        }
        phase = Phase.LEAVE;
        return TaskStatus.RUNNING;
    }

    private boolean covered(Map<String, Integer> have) {
        for (var e : want.entrySet()) if (have.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        return true;
    }

    private TaskStatus finish(String why, Map<String, Integer> have) {
        Map<String, Integer> gained = new TreeMap<>();
        Map<String, Integer> base = atEdgeOnce ? start : first;
        have.forEach((id, n) -> {
            int d = n - base.getOrDefault(id, 0);
            if (d > 0) gained.put(id, d);
        });
        LOG.info("Pickup stand {}: {} after {} ticks, gained {}{}", standAt, why, atEdgeOnce ? now - standSince : 0, gained,
            watch == null ? "" : ", items seen in flight " + watch.seen());
        result.accept(new PickupResult(gained, why, have));
        return TaskStatus.SUCCESS;
    }

    /** Below the stand's feet cell this many cells are looked at; nothing to land on in them is no floor. */
    static final int FLOOR_LOOK_DOWN = 4;

    /**
     * Why the stand cell has no floor: nothing at the feet, no water or lava, and nothing to land on within
     * {@link #FLOOR_LOOK_DOWN} cells below it (a drop of more than 3 blocks); null when it has one, or a cell of the
     * column is not loaded (not known yet: looked at again before the walk onto it). Live 2026-10-27: the stand was the
     * top of a dry 1x1 shaft; after a login the walk there dropped the bot 4 blocks into it, boxed in.
     */
    static @Nullable String noFloor(Pos stand, EdgeDrop.Cells w) {
        for (int d = 0; d <= FLOOR_LOOK_DOWN; d++) {
            Pos c = stand.offset(0, -d, 0);
            if (!w.loaded(c)) return null;
            if (w.fluid(c) || w.collisionTop(w.block(c)) > 0) return null;
        }
        return "nothing to stand on within " + FLOOR_LOOK_DOWN + " blocks under it (a drop of more than 3 blocks)";
    }

    /** {@link #noFloor} in the world: the stand fails at once with a WARN instead of walking onto it. */
    private @Nullable TaskStatus refuseFloorless() {
        String why = noFloor(standAt, EdgeApproach.cells(ctx.world(), zones));
        if (why == null) return null;
        LOG.warn("Pickup stand: refusing the stand cell {}: {}; the restock goes on without it", standAt, why);
        return fail("the stand cell " + standAt + " has no floor: " + why);
    }

    private boolean floorChecked;

    @Override
    protected TaskStatus step() {
        if (!floorChecked) {
            floorChecked = true;
            TaskStatus refused = refuseFloorless();
            if (refused != null) return refused;
        }
        switch (phase) {
            case WALK_PRIME -> {
                // the sorter ticks only near the bot (simulation distance 5 chunks): the north centre of the platform
                // first, so its upstream part runs, then straight on to the edge (the owner, 2026-10-14)
                TaskStatus s = walkLeg(prime);
                if (s == TaskStatus.FAILED) return fail("walking to the prime cell " + prime + ": " + walker.error());
                if (s == TaskStatus.RUNNING) return s;
                if (reprimes == 0) first = carried();
                else {
                    Map<String, Integer> have = carried();
                    if (covered(have)) return finish("covered", have);
                    if (now - standSince >= maxTicks) return finish("time", have);
                }
                LOG.info("Pickup stand: at the prime cell {}, on to the edge", prime);
                phase = Phase.WALK;
                return TaskStatus.RUNNING;
            }
            case WALK -> {
                if (!standAt.equals(legFor)) {
                    // the column may not have been loaded at the start (looked at again before each walk onto it)
                    TaskStatus refused = refuseFloorless();
                    if (refused != null) return refused;
                }
                TaskStatus s = walkLeg(standAt);
                if (s == TaskStatus.FAILED) return fail("walking to " + standAt + ": " + walker.error());
                if (s == TaskStatus.RUNNING) return s;
                TaskStatus refused = refuseFloorless(); // within a block of it now: loaded for sure
                if (refused != null) return refused;
                safeX = BOT.getX();
                safeZ = BOT.getZ();
                phase = Phase.CENTRE;
                centreSince = now;
                return TaskStatus.RUNNING;
            }
            case CENTRE -> {
                // onto the exact cell, sneaking: the pathfinder stopped up to a block short of it, and items fall past
                // only that cell
                if (onCell() && BOT.isOnGround() && Math.hypot(BOT.getX() - lastX, BOT.getZ() - lastZ) < 0.01
                    || now - centreSince >= CENTRE_TICKS) {
                    if (!onCell()) LOG.info("Pickup stand: could not center on {} in {} ticks (off by {}, {}); waiting where"
                        + " it stands", standAt, CENTRE_TICKS, String.format("%.2f", offX()), String.format("%.2f", offZ()));
                    else LOG.info("Pickup stand: on the cell {}", standAt);
                    return enterStand();
                }
                lastX = BOT.getX();
                lastZ = BOT.getZ();
                sneakTowardsCell();
                return TaskStatus.RUNNING;
            }
            case STAND -> {
                sneakTowardsCell(); // sneaking keeps it on the edge; it steps back onto the cell if it slid off

                if (Math.abs(BOT.getX() - (standAt.x() + 0.5)) > 1.5 || Math.abs(BOT.getZ() - (standAt.z() + 0.5)) > 1.5
                    || BOT.getY() < standAt.y() - 0.5) return fail("left the pickup stand " + standAt);
                Map<String, Integer> now_ = carried();
                int total = sum(now_);
                if (total > carriedLast) lastGain = now;
                carriedLast = total;
                if (watch != null) {
                    watch.update(now, ctx.world().itemsNear(standAt, cfg.build.sorterPickupWatchRadius));
                    if (watch.lastActive() > lastGain) lastGain = watch.lastActive(); // items are on their way: keep waiting
                }
                if (covered(now_)) return leave("covered", now_);
                if (Inv.freeSlots() <= minFree) return leave("slots", now_);
                if (now - lastGain >= stallTicks) {
                    // nothing more is coming (the sorter's far part unloads while the bot waits here): back to the
                    // middle and again to the edge while something is still needed (the owner, 2026-10-14)
                    if (prime != null && reprimes < Math.max(0, cfg.build.sorterPickupReprimes)
                        && now - standSince < maxTicks) {
                        reprimes++;
                        LOG.info("Pickup stand: nothing came for {} ticks; back to the prime cell {} and again ({} of {})",
                            now - lastGain, prime, reprimes, cfg.build.sorterPickupReprimes);
                        return leave(null, null);
                    }
                    return leave("stalled", now_);
                }
                if (now - standSince >= maxTicks) return leave("time", now_);
                return TaskStatus.RUNNING;
            }
            case LEAVE -> {
                // sneaking back off the edge onto the cell the walk arrived on; only then may anything walk
                double dx = safeX - BOT.getX();
                double dz = safeZ - BOT.getZ();
                boolean off = Math.abs(dx) <= OFF_EDGE && Math.abs(dz) <= OFF_EDGE && BOT.isOnGround();
                if (off || now - leaveSince >= LEAVE_TICKS) {
                    if (!off) LOG.warn("Pickup stand: not back off the edge in {} ticks (off by {}, {}); going on sneaking",
                        LEAVE_TICKS, String.format("%.2f", dx), String.format("%.2f", dz));
                    clicker.submit(now, null, null, Input.builder().sneaking(true).build());
                    if (leaveWhy != null) return finish(leaveWhy, leaveHave);
                    phase = Phase.WALK_PRIME;
                    return TaskStatus.RUNNING;
                }
                int[] keys = Aim.movementKeys(BOT.getYaw(), dx, dz);
                clicker.submit(now, null, null, Input.builder().sneaking(true).pressingForward(keys[0] > 0)
                    .pressingBack(keys[0] < 0).pressingLeft(keys[1] > 0).pressingRight(keys[1] < 0).build());
                return TaskStatus.RUNNING;
            }
        }
        return fail("bad phase " + phase);
    }

    @Nullable Phase phase() {
        return phase;
    }
}
