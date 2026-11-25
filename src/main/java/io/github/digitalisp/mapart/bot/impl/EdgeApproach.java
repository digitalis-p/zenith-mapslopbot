package io.github.digitalisp.mapart.bot.impl;

import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.INPUTS;

/**
 * Edge stops for one {@link Walker} ({@code build.edgeSneak}): a walk that ends in a
 * feet cell beside a drop, water or a no-go zone ({@link EdgeDrop}) must not slide past the edge.
 *
 * <p><b>Sneak.</b> Vanilla's sneak edge stop ({@code maybeBackOffFromEdge}) shortens each tick's move so the box stays
 * over a floor; ZenithProxy 3.7.2's Bot runs the same code whenever its input sneaks, and Grim simulates it (it is
 * vanilla movement: the shift key goes out in the player input packet). So:
 * <ul>
 * <li><b>Final approach</b>: while Baritone walks and the feet cell borders a hazard within {@link #FINAL_APPROACH}
 * blocks of the end of its path, Baritone's input of the tick sneaks (and does not sprint): set after Baritone's tick,
 * before ZenithProxy's InputManager applies it ({@link #applyToBaritone}). Not while the path's current or next move
 * steps down (a planned descent the edge stop would refuse).</li>
 * <li><b>Stop</b>: arrived in such a cell, the walk does not end while the bot still moves: Baritone is stopped and the
 * walk holds the sneak key alone (no movement key) until the bot stands still on the ground, at most
 * {@link #SETTLE_TICKS} ticks ({@link #settling}).</li>
 * <li><b>Halt</b>: a walk that stops or refuses Baritone's path in a tick ({@link #halt}) clears Baritone's movement keys
 * of that tick, which were already submitted (the "one movement at most" gap of the no-go zones): live, two such pushes
 * took the bot off the edge it had slid to.</li>
 * </ul>
 * Why sneak and not only a slower approach: the soul speed boots make a walk on soul sand 0.49 blocks a tick, which
 * slides about 0.6 blocks after the keys go up; a stop short of the cell would have to guess that slide, and a push of
 * any later move would still go over. The sneak stop holds whatever the speed, and costs only the last cells' speed.
 */
final class EdgeApproach {
    /** Horizontal blocks from the end of Baritone's path within which an edge cell is walked sneaking. */
    static final double FINAL_APPROACH = 2.5;
    /** Horizontal speed (blocks a tick) below which the bot stands still (ZenithProxy zeroes velocity below 0.003). */
    static final double STOPPED = 0.003;
    /** Ticks an arrival waits at most for the bot to stand still. */
    static final int SETTLE_TICKS = 20;

    /** What the walker wants done to Baritone's input in this tick ({@link #applyToBaritone}). */
    record Want(boolean sneak, boolean halt) { }

    private static final Want SNEAK = new Want(true, false);
    private static final Want HALT = new Want(false, true);
    /** This tick's want of the walking walker, taken by {@link #applyToBaritone}. Client tick thread. */
    private static volatile @Nullable Want pending;

    /** An edge approach that never acts (tests, walkers without a world). */
    static final EdgeApproach OFF = new EdgeApproach(() -> false, NoGoZones.NONE, null);

    private final BooleanSupplier enabled;
    /** Where a planned step down is checked ({@link #descent}): {@code build.edgeDescents}, outside the stair boxes. */
    private final Predicate<Pos> descentsAt;
    private final NoGoZones zones;
    private final @Nullable WorldView world;
    private @Nullable Pos cachedFeet;
    private @Nullable String cachedHazard;
    private List<int[]> cachedDirs = List.of();
    private long cachedAt = Long.MIN_VALUE;
    private long settleStart = -1;
    private boolean announced;
    private boolean announcedToward;
    /** Horizontal speed (blocks a tick) toward a hazard's side from which the bot counts as moving toward it. */
    static final double MOVING = 0.02;
    /** The walk whose stop left the bot moving toward a hazard: sneak until it stands still ({@link #stopped}). */
    private static volatile @Nullable EdgeApproach hold;
    private static int holdTicks;
    private static final Object HOLD = new Object();
    private final Object owner = new Object();

    EdgeApproach(BooleanSupplier enabled, NoGoZones zones, @Nullable WorldView world) {
        this(enabled, p -> false, zones, world);
    }

    EdgeApproach(BooleanSupplier enabled, Predicate<Pos> descentsAt, NoGoZones zones, @Nullable WorldView world) {
        this(enabled, descentsAt, p -> false, zones, world);
    }

    EdgeApproach(BooleanSupplier enabled, Predicate<Pos> descentsAt, Predicate<Pos> holesAt, NoGoZones zones,
                 @Nullable WorldView world) {
        this.enabled = enabled;
        this.descentsAt = descentsAt;
        this.holesAt = holesAt;
        this.zones = zones;
        this.world = world;
    }

    // ---- holes (build.edgeHoles): the feet never go into a cell with a drop of more than 3 blocks, or water or lava

    /** Where holes are guarded ({@code build.edgeHoles}, outside the stair boxes): the feet cell, or a move's end. */
    private final Predicate<Pos> holesAt;
    /** The walk whose Baritone keys this tick are guarded ({@link #arm}), and its planned route. Client tick thread. */
    private static volatile @Nullable EdgeApproach armed;
    private @Nullable Supplier<@Nullable List<Pos>> armedRoute;
    /** Planned path cells this many from the current move's start count as planned (a fall, a walk through water). */
    static final int PLANNED_CELLS = 5;
    private final Map<Pos, String> holes = new HashMap<>();
    private long holesAt20 = Long.MIN_VALUE;
    private double topSpeed;
    /** The walk speed a jump is played with at least (Soul Speed on soul sand: 0.49 a tick; live 2026-10-28 0.21 from still). */
    static final double JUMP_TOP_SPEED = 0.49;
    /** Sprinting multiplies what the movement keys add on the ground (vanilla's sprint speed modifier, ×1.3). */
    static final double SPRINT_ACCEL = 1.3;
    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;
    private long lastNs;
    private final Set<String> guardLogged = new HashSet<>();
    private boolean inHoleLogged;
    /** The last corner cut found ({@link #corner}) and its free side cell (null: none walkable). */
    private NoGoZones.@Nullable Violation lastCorner;
    private @Nullable Pos lastCornerSide;

    /**
     * What the hole guard does to this tick's keys: (forward, strafe) instead of Baritone's when {@code keys}; sneak;
     * {@code noJump}: Baritone's jump of this tick is taken away ({@link #jumpWhy}).
     */
    record Guard(int forward, int strafe, boolean keys, boolean sneak, String why, boolean noJump) {
        Guard(int forward, int strafe, boolean keys, boolean sneak, String why) {
            this(forward, strafe, keys, sneak, why, false);
        }
    }

    // ---- a jump the guard keeps taking away: the walk hands back (live 2026-10-29 15:39-15:41, the map station walk)

    /** The jump the guard took away in its last tick ({@link #guardNow}), for {@link #takeRefusal}; null none. */
    private @Nullable String refusedWhy;
    /** This walk's watch on refused jumps ({@link RefusalWatch}); reset with the walk. */
    final RefusalWatch refusals = new RefusalWatch();

    /** The guard's jump refusal since the last call ("x,y,z: what; no jump"), or null; taken once. Client tick thread. */
    @Nullable String takeRefusal() {
        String r = refusedWhy;
        refusedWhy = null;
        return r;
    }

    /**
     * Whether the guard keeps taking the walk's jump away while the bot stands where it was: refusals at most
     * {@link #GAP} ticks apart for {@link #TICKS} ticks (5 s), the bot within {@link #MOVED} of where they began. Then
     * the walk hands back ({@code Walker}: planned again around the jump once, else failed) instead of stalling: live
     * 2026-10-29 the guard took a step-up's jump away for 2 minutes (Baritone's ascend timed out again and again, the
     * walk stalled 4 times, the map station walk was retried) until the "No progress" alert. Pure.
     */
    static final class RefusalWatch {
        /** Refused for this many ticks (5 s): the walk hands back, before its own stall (10 s, {@code STALL_TICKS}). */
        static final int TICKS = 100;
        /** Ticks without a refusal that end a run of them (Baritone plans again after its ascend timed out, say). */
        static final int GAP = 40;
        /** Blocks the bot may move from where the refusals began (it stands still meanwhile). */
        static final double MOVED = 0.5;
        private long first = -1;
        private long last = -1;
        private double x;
        private double z;
        private @Nullable String why;

        /**
         * One tick: {@code refused} the guard's refusal since the last tick (null none), the bot at (bx, bz). The refusal
         * once it has gone on {@link #TICKS} ticks (the watch starts again), else null.
         */
        @Nullable String tick(long now, @Nullable String refused, double bx, double bz) {
            if (first >= 0 && (now - last > GAP || Math.hypot(bx - x, bz - z) > MOVED)) first = -1;
            if (refused != null) {
                if (first < 0) {
                    first = now;
                    x = bx;
                    z = bz;
                }
                last = now;
                why = refused;
            }
            if (first < 0 || now - first < TICKS) return null;
            first = -1;
            return why;
        }

        void reset() {
            first = -1;
        }

        /** The jump's end: the first of the route's next two cells (from the current move's start) above the feet. */
        static @Nullable Pos jumpEnd(List<Pos> route, Pos feet) {
            for (int i = 0; i < route.size() && i <= 2; i++) if (route.get(i).y() > feet.y()) return route.get(i);
            return null;
        }

        /**
         * Whether the walk plans again around the jump's end {@code up} rather than failing: not done yet for this goal
         * list, an end known that is neither the feet cell nor in the goal, and a corner detour left.
         */
        static boolean replan(boolean done, @Nullable Pos up, int detours, int maxDetours, Pos feet, boolean upInGoal) {
            return !done && up != null && !up.equals(feet) && !upInGoal && detours < maxDetours;
        }
    }

    /** When a walk last handed back on refused jumps ({@link #troubled}), System.nanoTime; and the walk's way. */
    private static volatile long troubleNs;
    private static volatile @Nullable String troubleWay;
    /** A hand-back on refused jumps is named by {@link #trouble} this long after it (3 min). */
    static final long TROUBLE_NS = 180_000_000_000L;

    /** A walk handed back on refused jumps ({@code way}: "on the way to the map station"). */
    static void troubled(String way) {
        troubleWay = way;
        troubleNs = System.nanoTime();
    }

    /**
     * The way of the last walk handed back on refused jumps within {@link #TROUBLE_NS} ("on the way to the map
     * station"), or null: for the "No progress" alert.
     */
    static @Nullable String trouble() {
        String w = troubleWay;
        return w != null && System.nanoTime() - troubleNs <= TROUBLE_NS ? w : null;
    }

    /** Vanilla's jump: vertical speed 0.42, then −0.08 and × 0.98 a tick. */
    static final double JUMP_VY = 0.42;
    /** Horizontal drag a tick in the air (vanilla 0.91; on the ground it is {@link NukeGuard#FRICTION}). */
    static final double AIR_DRAG = 0.91;
    /** What the movement keys add a tick in the air (vanilla 0.02 × 0.98). */
    static final double AIR_ACCEL = 0.0196;
    /** A sprint jump's push along the facing (vanilla 0.2). */
    static final double SPRINT_JUMP = 0.2;

    /**
     * A jump of this tick ({@code build.edgeHoles}): the hazard cell ("x,y,z: what") the feet point comes within
     * {@link NukeGuard#FEET_MARGIN} of on the jump's way, or null. Played forward as vanilla flies it: the jump tick on
     * the ground (velocity (vx, vz) plus {@code accel} along unit direction {@code keys}, a sprint jump's push too, then
     * ground friction), the flight back down to the feet level (air drag, {@link #AIR_ACCEL} along the keys), then the
     * slide on the ground after the landing. The ground model of {@link NukeGuard#decide} does not see it: in the air
     * the keys barely change the way, and the drag is 0.91, not 0.55, so a jump goes about twice as far.
     *
     * <p>Live 2026-10-28 (soul sand, [3,4] NUKE leftovers, from the replay's track): the bot stood still in the east
     * edge column, 0.49 west of the item trench (feet point x 127.51; the trench is x 128). The leftover walk's first
     * tick jumped east (0.21 that tick, 0.13, 0.11, 0.09 in the air); the guard looked first in the air and turned the
     * keys, which changed nothing: the box came down at x 128.33, wholly over the trench, and fell 3 blocks into it.
     */
    static @Nullable String jumpWhy(double fx, double fz, int level, double vx, double vz, double accel, double[] keys,
                                    boolean sprint, Function<Pos, @Nullable String> hole) {
        return jumpWhy(fx, fz, level, vx, vz, accel, keys, sprint, hole, NO_BLOCKS);
    }

    /** No blocks in the way of a jump ({@link #jumpWhy} as before 2026-10-29: the flight passes through everything). */
    static final ToDoubleFunction<Pos> NO_BLOCKS = c -> 0;
    /** Half the player box's width (vanilla 0.6 wide). */
    static final double HALF_WIDTH = 0.3;
    /** The player box's height (vanilla 1.8). */
    static final double HEIGHT = 1.8;
    /** What the bot steps up on the ground without a jump (vanilla 0.6: a slab, a carpet). */
    static final double STEP_UP = 0.6;
    /** The flight is moved in steps of at most this many blocks an axis, each one checked against the blocks. */
    static final double FLIGHT_STEP = 0.05;

    /**
     * {@link #jumpWhy}, with the blocks in the box's way ({@code solid}: the height of the collision box of the block in
     * a cell, as {@link EdgeDrop.Cells#floorTop}; 0 none). The box (0.6 wide, 1.8 high) runs into a block it does not
     * clear, and that axis of its speed is lost, as vanilla's collision does; a box over a block lands on its top. The
     * feet point is judged against the hole cells of the start's feet level, as before: a step's column is no hole one
     * up either, and a hole of that level is one a level up too. The jump tick's own move is made at the jump's height
     * (vanilla moves up first).
     *
     * <p>Live 2026-10-29 15:39 (the walk to the map station after a tile): Baritone's ascend (a 1-block step
     * up) from still, beside a drop of more than 3 blocks. The flight without blocks went on at the start's feet level
     * through the step's block and on past it, its feet point reached a hole cell of that level, and the jump was taken
     * away every time ("the keys (1, 0) would take the feet into …: a drop of more than 3 blocks; no jump"): Baritone's
     * ascend timed out, the walk stalled 4 times, and the map was not filled. With the blocks the box stops at the
     * step's face until it is a block up, then lands on the step.
     */
    static @Nullable String jumpWhy(double fx, double fz, int level, double vx, double vz, double accel, double[] keys,
                                    boolean sprint, Function<Pos, @Nullable String> hole, ToDoubleFunction<Pos> solid) {
        double push = sprint ? SPRINT_JUMP : 0;
        double sx = vx + (accel + push) * keys[0];
        double sz = vz + (accel + push) * keys[1];
        double px = fx;
        double pz = fz;
        double y = floorUnder(px, pz, level, solid); // the box's bottom above the feet level (a carpet, a slab)
        double vy = JUMP_VY;
        boolean onGround = false; // the jump tick leaves the ground
        for (int i = 0; i < NukeGuard.SLIDE_TICKS; i++) {
            if (i > 0 && !onGround) {
                sx += AIR_ACCEL * keys[0];
                sz += AIR_ACCEL * keys[1];
            } else if (i > 0 && Math.hypot(sx, sz) <= 0.003) {
                break; // landed and slid to a stop
            }
            boolean flying = !onGround;
            if (flying) { // up first, as vanilla moves: the jump tick, the flight
                double ny = y + vy;
                vy = (vy - 0.08) * 0.98;
                double floor = floorUnder(px, pz, level, solid);
                if (i > 0 && ny <= floor) {
                    ny = floor;
                    onGround = true;
                }
                y = ny;
            }
            int n = Math.max(1, (int) Math.ceil(Math.max(Math.abs(sx), Math.abs(sz)) / FLIGHT_STEP));
            double nx = px;
            double nz = pz;
            boolean hitX = false;
            boolean hitZ = false;
            for (int k = 0; k < n; k++) {
                if (!hitX) {
                    double t = nx + sx / n;
                    if (blocked(t, nz, y, level, onGround, solid)) hitX = true;
                    else nx = t;
                }
                if (!hitZ) {
                    double t = nz + sz / n;
                    if (blocked(nx, t, y, level, onGround, solid)) hitZ = true;
                    else nz = t;
                }
            }
            if (onGround) y = Math.max(y, floorUnder(nx, nz, level, solid)); // stepped onto a slab or a carpet
            // the cells of the start's feet level: a column with a block there (a step) is no hole one up either (its
            // floor is right under), and a hole there is a hole one up too
            String h = NukeGuard.slideFeet(fx, fz, px, pz, nx, nz, level, hole);
            if (h != null) return h;
            px = nx;
            pz = nz;
            if (hitX) sx = 0;
            if (hitZ) sz = 0;
            double drag = flying && i > 0 ? AIR_DRAG : NukeGuard.FRICTION; // the landing tick still flew
            sx *= drag;
            sz *= drag;
        }
        return null;
    }

    /** The highest block top (at most 1) under the box at feet point (x, z), feet level {@code level}; 0: none. */
    private static double floorUnder(double x, double z, int level, ToDoubleFunction<Pos> solid) {
        double top = 0;
        for (int cx = (int) Math.floor(x - HALF_WIDTH + 1e-7); cx <= (int) Math.floor(x + HALF_WIDTH - 1e-7); cx++) {
            for (int cz = (int) Math.floor(z - HALF_WIDTH + 1e-7); cz <= (int) Math.floor(z + HALF_WIDTH - 1e-7); cz++) {
                top = Math.max(top, Math.min(1, solid.applyAsDouble(new Pos(cx, level, cz))));
            }
        }
        return top;
    }

    /**
     * The box at feet point (x, z), its bottom {@code y} above the feet level, overlaps a block of the feet level or the
     * two above it that it does not step onto (on the ground: a top no more than {@link #STEP_UP} above the box).
     */
    private static boolean blocked(double x, double z, double y, int level, boolean onGround, ToDoubleFunction<Pos> solid) {
        for (int cx = (int) Math.floor(x - HALF_WIDTH + 1e-7); cx <= (int) Math.floor(x + HALF_WIDTH - 1e-7); cx++) {
            for (int cz = (int) Math.floor(z - HALF_WIDTH + 1e-7); cz <= (int) Math.floor(z + HALF_WIDTH - 1e-7); cz++) {
                for (int k = 0; k <= 2; k++) {
                    double top = solid.applyAsDouble(new Pos(cx, level + k, cz));
                    if (top <= 0) continue;
                    double hi = k + top;
                    if (k >= y + HEIGHT - 1e-7 || hi <= y + 1e-7) continue; // above or under the box
                    if (onGround && hi - y <= STEP_UP + 1e-7) continue; // stepped onto
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * This tick Baritone walks for this walker: its keys are guarded after its tick ({@link #applyToBaritone}), with the
     * walk's planned route ({@code route}: feet cells from the current move's start; null unknown). Client tick thread.
     */
    void arm(Supplier<@Nullable List<Pos>> route) {
        armedRoute = route;
        armed = this;
    }

    /** {@link EdgeDrop#holeAt} looked up through a cache renewed every 20 ticks. */
    private @Nullable String hole(Pos c) {
        WorldView w = world;
        if (w == null) return null;
        long tick = System.nanoTime() / 50_000_000L;
        if (tick - holesAt20 >= 20 || holes.size() > 4096) {
            holes.clear();
            holesAt20 = tick;
        }
        String h = holes.get(c);
        if (h == null) {
            String a = EdgeDrop.holeAt(c, cells(w, zones));
            h = a == null ? "" : a;
            holes.put(c, h);
        }
        return h.isEmpty() ? null : h;
    }

    /** Columns (x, z) of the route's first {@link #PLANNED_CELLS} cells at or below {@code level}: planned, not holes. */
    static Set<Long> planned(@Nullable List<Pos> route, int level) {
        Set<Long> out = new HashSet<>();
        if (route == null) return out;
        for (int i = 0; i < route.size() && i < PLANNED_CELLS; i++) {
            Pos p = route.get(i);
            if (p.y() <= level) out.add(EdgeDrop.column(p.x(), p.z()));
        }
        return out;
    }

    /**
     * The hole guard for Baritone's keys of this tick (forward, strafe at {@code yaw}), pure: with them on top of
     * velocity (vx, vz) for one tick and the slide after it ({@link NukeGuard#slide}, the feet point), the feet at
     * (fx, fz) must go into no hole cell at {@code level} other than the one they are in ({@code hole}). Else the keys
     * nearest them within 50° that keep out (turned), no key (holding back), or the keys against the velocity
     * (braking), as {@link NukeGuard#decide}. Feet already in a hole cell sneak (vanilla's edge stop keeps the box on
     * the rim). Null: nothing to do.
     */
    static @Nullable Guard guard(double fx, double fz, int level, double vx, double vz, double accel, double yaw,
                                 int forward, int strafe, Function<Pos, @Nullable String> hole) {
        return guard(fx, fz, level, vx, vz, accel, yaw, forward, strafe, false, false, hole);
    }

    /**
     * {@link #guard}, with Baritone's jump of this tick ({@code jump}, a sprint jump with {@code sprint}): a jump whose
     * flight takes the feet point near a hole cell ({@link #jumpWhy}, with the keys the guard leaves or gives) is
     * taken away ({@link Guard#noJump}): the walk does not ascend there (it stalls and plans again), it does not fly
     * into the hole.
     */
    static @Nullable Guard guard(double fx, double fz, int level, double vx, double vz, double accel, double yaw,
                                 int forward, int strafe, boolean jump, boolean sprint,
                                 Function<Pos, @Nullable String> hole) {
        return guard(fx, fz, level, vx, vz, accel, yaw, forward, strafe, jump, sprint, hole, NO_BLOCKS);
    }

    /** {@link #guard} with the blocks a jump's box runs into or lands on ({@code solid}, {@link #jumpWhy}). */
    static @Nullable Guard guard(double fx, double fz, int level, double vx, double vz, double accel, double yaw,
                                 int forward, int strafe, boolean jump, boolean sprint,
                                 Function<Pos, @Nullable String> hole, ToDoubleFunction<Pos> solid) {
        Guard g = groundGuard(fx, fz, level, vx, vz, accel, yaw, forward, strafe, hole);
        if (!jump) return g;
        int f = g != null && g.keys() ? g.forward() : forward;
        int s = g != null && g.keys() ? g.strafe() : strafe;
        double[] k = f == 0 && s == 0 ? new double[]{0, 0} : unit(Aim.keysToWorld(yaw, f, s));
        String why = jumpWhy(fx, fz, level, vx, vz, accel, k, sprint, hole, solid);
        if (why == null) return g;
        if (g != null && g.keys()) return new Guard(f, s, true, g.sneak(), g.why() + "; no jump", true);
        Pos feet = new Pos((int) Math.floor(fx), level, (int) Math.floor(fz));
        return new Guard(forward, strafe, true, hole.apply(feet) != null, why + "; no jump", true);
    }

    private static double[] unit(double[] w) {
        double l = Math.hypot(w[0], w[1]);
        return l < 1e-9 ? new double[]{0, 0} : new double[]{w[0] / l, w[1] / l};
    }

    /** {@link #guard} without a jump: the ground model ({@link NukeGuard#decide}, the feet point). */
    private static @Nullable Guard groundGuard(double fx, double fz, int level, double vx, double vz, double accel,
                                               double yaw, int forward, int strafe,
                                               Function<Pos, @Nullable String> hole) {
        Pos feet = new Pos((int) Math.floor(fx), level, (int) Math.floor(fz));
        String inHole = hole.apply(feet);
        double mx = 0;
        double mz = 0;
        if (forward != 0 || strafe != 0) {
            double[] k = Aim.keysToWorld(yaw, forward, strafe);
            double l = Math.hypot(k[0], k[1]);
            mx = k[0] / l;
            mz = k[1] / l;
        }
        NukeGuard.Decision d = NukeGuard.decide(fx, fz, level, vx, vz, accel, yaw, mx, mz, hole, true);
        if (d == null) {
            return inHole == null ? null : new Guard(forward, strafe, false, true, feet.key() + ": " + inHole + "; in it");
        }
        int[] k = d.mx() == 0 && d.mz() == 0 ? new int[]{0, 0} : Aim.movementKeys(yaw, d.mx(), d.mz());
        return new Guard(k[0], k[1], true, inHole != null, d.hazard() + "; " + d.how());
    }

    /** The hole guard in the world for Baritone's input {@code in} (null: no keys) at {@code yaw}. Client tick thread. */
    private @Nullable Guard guardNow(@Nullable Input in, float yaw) {
        if (world == null) return null;
        Pos feet = Walker.feet();
        if (!holesAt.test(feet)) return null;
        double fx = BOT.getX();
        double fz = BOT.getZ();
        long ns = System.nanoTime();
        if (!Double.isNaN(lastX) && ns - lastNs < 80_000_000L) {
            double moved = Math.hypot(fx - lastX, fz - lastZ);
            if (moved < NukeGuard.TOP_SPEED_MAX) topSpeed = Math.max(topSpeed, moved);
        }
        lastX = fx;
        lastZ = fz;
        lastNs = ns;
        Supplier<@Nullable List<Pos>> r = armedRoute;
        List<Pos> route = r == null ? null : r.get();
        if (route != null && descentAhead(route)) return null; // a planned step down or fall: Baritone's own
        Set<Long> planned = planned(route, feet.y());
        var v = BOT.getVelocity();
        int f = in == null ? 0 : (in.pressingForward ? 1 : 0) - (in.pressingBack ? 1 : 0);
        int s = in == null ? 0 : (in.pressingLeft ? 1 : 0) - (in.pressingRight ? 1 : 0);
        double speed = Math.hypot(v.getX(), v.getZ());
        // a jump from the ground (Baritone's ascend or "wrong Y" hop): its flight is played too; Soul Speed's push at
        // least while the walk has not shown its speed yet (a first tick from still)
        boolean jump = in != null && in.jumping && BOT.isOnGround();
        boolean sprint = in != null && in.sprinting;
        double accel = groundAccel(topSpeed, speed, soulFloor(feet), sprint);
        if (jump) accel = Math.max(accel, NukeGuard.accel(JUMP_TOP_SPEED));
        Function<Pos, @Nullable String> holes = c -> planned.contains(EdgeDrop.column(c.x(), c.z())) ? null : hole(c);
        ToDoubleFunction<Pos> solid = solid(world);
        Guard g = guard(fx, fz, feet.y(), v.getX(), v.getZ(), accel, yaw, f, s, jump, sprint, holes, solid);
        float body = BOT.getYaw();
        boolean turning = Math.abs(WalkYaw.botTurn(body, yaw)) > TURN_SAME;
        if (turning) g = whileTurning(g, guard(fx, fz, feet.y(), v.getX(), v.getZ(), accel, body, f, s, jump, sprint,
            holes, solid));
        if (g != null && g.noJump()) refusedWhy = g.why(); // for the walk's refusal watch (takeRefusal)
        if (g == null) return null;
        if (g.keys() && guardLogged.add(g.why())) {
            ZenithTask.LOG.info("Edge stop: at {}, {} moving {} blocks a tick, the keys ({}, {}) would take the feet into {}",
                String.format("%.2f", fx), String.format("%.2f", fz), String.format("%.2f", speed), f, s, g.why());
        } else if (!g.keys() && !inHoleLogged) {
            inHoleLogged = true;
            ZenithTask.LOG.info("Edge stop: the feet are in {}; sneaking", g.why());
        }
        return g;
    }

    /**
     * What this tick's movement keys add on the ground, for the hole guard's model: what the walk's speed so far implies
     * ({@link NukeGuard#accel}), and at least what the floor gives from still. On soul sand or soul soil that is Soul
     * Speed's push ({@link #JUMP_TOP_SPEED}: 0.22 a tick), else walking's 0.098; ×{@link #SPRINT_ACCEL} while the keys
     * sprint. The measured speed alone is not enough: it is the first tick's move, not the top speed it is taken for.
     * Local drill 2026-10-28: standing still on the east edge column, the walk's first tick sprinted
     * 0.29 east on Soul Speed (0.222 × 1.3); the guard had assumed 0.098, let the tick through, turned the keys 0.07
     * short of the trench's cell in the next one, and the feet point went 0.18 into it (the slab row, water 1 below).
     */
    static double groundAccel(double topSpeed, double speed, boolean soulFloor, boolean sprint) {
        double least = (soulFloor ? NukeGuard.accel(JUMP_TOP_SPEED) : NukeGuard.ACCEL_MIN) * (sprint ? SPRINT_ACCEL : 1);
        return Math.max(NukeGuard.accel(Math.max(topSpeed, speed)), least);
    }

    /** Baritone's yaw and the bot's are the same turn when they differ by no more than this (degrees). */
    static final double TURN_SAME = 5;

    /**
     * The guard while Baritone turns ({@link #guardNow}): its keys are judged at Baritone's yaw ({@code atTarget}) and
     * at the yaw the bot still has ({@code atBody}); the tick's move may be made at either. Either one guarding the keys
     * holds them back (no key: the same at any yaw, and no jump) until the turn is made; a sneak of either stays.
     * Local drill 2026-10-28: the walk's second tick from the trench's rim had Baritone's yaw south-west
     * while the bot still faced east, and the model saw no hazard at that yaw. Pure.
     */
    static @Nullable Guard whileTurning(@Nullable Guard atTarget, @Nullable Guard atBody) {
        if (atTarget == null) return atBody == null ? null : atBody.keys() ? held(atBody, false) : atBody;
        if (atBody == null) return atTarget.keys() ? held(atTarget, false) : atTarget;
        if (!atTarget.keys() && !atBody.keys()) return atTarget;
        return held(atBody.keys() ? atBody : atTarget, atTarget.sneak() || atBody.sneak());
    }

    /** No key this tick (a jump is taken away with the keys too, {@link #applyToBaritone}). */
    private static Guard held(Guard g, boolean sneak) {
        return new Guard(0, 0, true, g.sneak() || sneak, g.why() + "; turning, held", g.noJump());
    }

    /** The block under the feet cell is soul sand or soul soil (Soul Speed's floor). Client tick thread. */
    private boolean soulFloor(Pos feet) {
        WorldView w = world;
        if (w == null) return false;
        BlockSpec b = w.block(new Pos(feet.x(), feet.y() - 1, feet.z()));
        if (b == null) return false;
        String n = b.id();
        return n.endsWith("soul_sand") || n.endsWith("soul_soil");
    }

    /** The feet stand in a hole cell ({@link EdgeDrop#holeAt}) where holes are guarded. Client tick thread. */
    private boolean inHole() {
        Pos feet = Walker.feet();
        return world != null && holesAt.test(feet) && hole(feet) != null;
    }

    /**
     * Holes in a planned path: the first diagonal move past a hole's corner
     * ({@link EdgeDrop#cornerCut}), as a violation the walker plans around ({@link #cornerFix}), or null. Client tick
     * thread.
     */
    NoGoZones.@Nullable Violation corner(List<Pos> cells) {
        WorldView w = world;
        if (w == null || cornersWaived) return null;
        EdgeDrop.Cells c = cells(w, zones);
        EdgeDrop.Corner k = EdgeDrop.cornerCut(cells, c, holesAt);
        if (k == null) return null;
        NoGoZones.Violation v = new NoGoZones.Violation("passes the corner of " + k.hole().key() + " (" + k.hazard()
            + ") from " + k.from().key() + " to " + k.to().key(), k.from(), k.to());
        int y = Math.max(k.from().y(), k.to().y());
        Pos a = new Pos(k.from().x(), y, k.to().z());
        Pos b = new Pos(k.to().x(), y, k.from().z());
        Pos other = a.x() == k.hole().x() && a.z() == k.hole().z() ? b : a;
        lastCorner = v;
        lastCornerSide = walkable(other, c) ? other : null;
        return v;
    }

    /** Feet cell {@code p} can be stood in: room at the feet and the head, a floor right under it, no hazard beside. */
    private static boolean walkable(Pos p, EdgeDrop.Cells c) {
        if (!c.loaded(p) || !c.loaded(p.above()) || !c.loaded(p.below())) return false;
        if (c.collisionTop(c.block(p)) > 0 || c.collisionTop(c.block(p.above())) > 0) return false;
        return c.collisionTop(c.block(p.below())) > 0 && !c.fluid(p) && !c.noGo(p);
    }

    /** Whether {@code v} is this walker's hole corner ({@link #corner}). */
    boolean ownsCorner(NoGoZones.Violation v) {
        return v == lastCorner;
    }

    /**
     * The way around a hole's corner {@code v} ({@link #corner}): once ({@code viaTried}), through the move's other
     * side cell when it can be stood in (the move's two straight halves keep off the hole); else {@link #WAIVE}: no
     * cell is made to look far ({@link #cornersWaived}). {@code avoided} and {@code keep} are the zones' arguments, unused.
     */
    NoGoZones.@Nullable CornerFix cornerFix(NoGoZones.Violation v, Set<Pos> avoided, boolean viaTried, Predicate<Pos> keep) {
        Pos side = lastCornerSide;
        if (!viaTried && side != null) {
            int dy = Math.abs(v.from().y() - v.to().y());
            return new NoGoZones.CornerFix(null, new GoalNearXZ(side.x(), side.y(), side.z(), 0, 1 + dy));
        }
        cornersWaived = true;
        return WAIVE;
    }

    /**
     * {@link #cornerFix}'s answer once the side cell was tried (or there is none): the walk takes the planned moves past
     * hole corners from now on, with the per-tick guard ({@link #guard}) keeping the feet point out of the hole.
     */
    static final NoGoZones.CornerFix WAIVE = new NoGoZones.CornerFix(null, null);
    /**
     * This walk no longer plans around hole corners ({@link #WAIVE}); until {@link #reset}. Making a move's end or
     * start look far instead (as the zones do) can wall off the only way: live 2026-10-28 (soul sand, the walk off the
     * art layer after the nuke), the move's end was the one cell of a 1-wide walkway between two drops beside the tile,
     * every later path had to go round it, none could, and three walks failed (BLOCKED).
     */
    private boolean cornersWaived;

    /** Whether {@link #descent} can find anything (a world to look at). */
    boolean checksDescents() {
        return world != null;
    }

    /**
     * Edge descents: the first move of a planned path (feet cells) that steps down
     * toward a hazard ({@link EdgeDrop#descent}), as a violation the walker plans around like a zone's corner cut (the
     * move's end made to look far, {@link NoGoZones#cornerFix}), or fails on; null: none. Client tick thread.
     */
    NoGoZones.@Nullable Violation descent(List<Pos> cells) {
        WorldView w = world;
        if (w == null) return null;
        EdgeDrop.Descent d = EdgeDrop.descent(cells, cells(w, zones), descentsAt);
        if (d == null) return null;
        return new NoGoZones.Violation("steps down from " + d.from().key() + " to " + d.to().key() + " toward "
            + d.toward().key() + " (" + d.hazard() + ")", d.from(), d.to(), true);
    }

    /**
     * Whether Baritone's keys sneak this tick: the feet cell borders a hazard ({@code hazard} not null), the path does
     * not step down now or next, and the bot is within {@link #FINAL_APPROACH} of the path's end or moves toward the
     * hazard ({@code toward}: a turn into an edge column mid-path overshoots as well). Pure.
     */
    static boolean sneak(@Nullable String hazard, double distanceToEnd, boolean descentAhead, boolean toward) {
        return hazard != null && !descentAhead && (distanceToEnd <= FINAL_APPROACH || toward);
    }

    /**
     * The velocity (vx, vz) points at one of the hazards beside the feet ({@code dirs}, (dx, dz) as in
     * {@link EdgeDrop#AROUND}): toward a side neighbour faster than {@link #MOVING}, or toward a diagonal one on both
     * axes. Walking along an edge (the hazards to one side, diagonally ahead) is not toward. Pure.
     */
    static boolean toward(List<int[]> dirs, double vx, double vz) {
        for (int[] d : dirs) {
            boolean side = d[0] == 0 || d[1] == 0;
            if (side ? vx * d[0] + vz * d[1] > MOVING : vx * d[0] > MOVING / 2 && vz * d[1] > MOVING / 2) return true;
        }
        return false;
    }

    /** The path (feet cells from the current move's start) steps down in its first two moves. Pure. */
    static boolean descentAhead(List<Pos> remaining) {
        for (int i = 0; i + 1 < remaining.size() && i < 2; i++) {
            if (remaining.get(i + 1).y() < remaining.get(i).y()) return true;
        }
        return false;
    }

    /** The bot stands still: on the ground and slower than {@link #STOPPED}. Pure. */
    static boolean still(boolean onGround, double vx, double vz) {
        return onGround && Math.hypot(vx, vz) < STOPPED;
    }

    /**
     * One tick of a walk Baritone drives: asks for the sneak ({@link #sneak}) given the path still to walk (null:
     * unknown, no sneak). Client tick thread.
     */
    void walking(@Nullable List<Pos> remaining) {
        settleStart = -1;
        if (!enabled.getAsBoolean()) return;
        Pos feet = Walker.feet();
        String hazard = hazard(feet); // looked up once per feet cell
        if (hazard == null) return;
        Pos end = remaining == null || remaining.isEmpty() ? null : remaining.getLast();
        double d = end == null ? Double.MAX_VALUE : Math.hypot(end.x() + 0.5 - BOT.getX(), end.z() + 0.5 - BOT.getZ());
        var v = BOT.getVelocity();
        boolean toward = toward(cachedDirs, v.getX(), v.getZ());
        if (!sneak(hazard, d, remaining != null && descentAhead(remaining), toward)) return;
        if (d <= FINAL_APPROACH && !announced) {
            announced = true;
            ZenithTask.LOG.info("Edge stop: walking the last {} blocks to {} sneaking ({})", String.format("%.1f", d),
                end.key(), hazard);
        } else if (d > FINAL_APPROACH && !announcedToward) {
            announcedToward = true;
            ZenithTask.LOG.info("Edge stop: at {} moving {} blocks a tick toward {}; sneaking while it does", feet.key(),
                String.format("%.2f", Math.hypot(v.getX(), v.getZ())), hazard);
        }
        pending = SNEAK;
    }

    /**
     * The walk ended (arrived, refused, stopped by the no-go position check, cancelled): a bot still moving toward a
     * hazard beside its feet holds the sneak key alone until it stands still or turns away, at most
     * {@link #SETTLE_TICKS} ticks ({@link #tickHold}, over whatever input the next task submits). Client tick thread.
     */
    void stopped() {
        var v = BOT.getVelocity();
        if (still(BOT.isOnGround(), v.getX(), v.getZ())) return;
        boolean inHole = inHole(); // the feet in a hole's cell (build.edgeHoles): sneak while it still moves
        String h = enabled.getAsBoolean() ? hazard(Walker.feet()) : null;
        if (inHole) h = Walker.feet().key() + ": " + hole(Walker.feet()) + " (the feet's own cell)";
        else if (h == null || !toward(cachedDirs, v.getX(), v.getZ())) return;
        hold = this;
        holdTicks = 0;
        ZenithTask.LOG.info("Edge stop: the walk ended at {} moving {} blocks a tick toward {}; sneaking until it stands still",
            Walker.feet().key(), String.format("%.2f", Math.hypot(v.getX(), v.getZ())), h);
    }

    /** The hold of {@link #stopped}: the sneak key alone, above any task's input, until still or turned away. */
    private static void tickHold() {
        EdgeApproach h = hold;
        if (h == null) return;
        var v = BOT.getVelocity();
        if (++holdTicks > SETTLE_TICKS || still(BOT.isOnGround(), v.getX(), v.getZ())
            || !h.inHole() && (h.hazard(Walker.feet()) == null || !toward(h.cachedDirs, v.getX(), v.getZ()))) {
            hold = null;
            return;
        }
        Input in = BARITONE.getInputOverrideHandler().currentInput;
        if (in != null) {
            in.sneaking = true;
            in.sprinting = false;
        }
        INPUTS.submit(InputRequest.builder().owner(HOLD).priority(Clicker.PRIORITY + 2)
            .input(Input.builder().sneaking(true).build()).build());
    }

    /**
     * The walk arrived: true while it must hold on (the feet cell borders a hazard and the bot still moves): the sneak
     * key alone, no movement key, at most {@link #SETTLE_TICKS} ticks. False once still (or off, or no hazard). The
     * caller stops Baritone while this is true.
     */
    boolean settling(long now) {
        if (!enabled.getAsBoolean()) return false;
        var v = BOT.getVelocity();
        if (still(BOT.isOnGround(), v.getX(), v.getZ())) return false;
        String hazard = hazard(Walker.feet());
        if (hazard == null && inHole()) hazard = Walker.feet().key() + ": " + hole(Walker.feet()) + " (the feet's own cell)";
        if (hazard == null) return false;
        if (settleStart < 0) {
            settleStart = now;
            ZenithTask.LOG.info("Edge stop: arrived at {} moving {} blocks a tick next to {}; sneaking until it stands still",
                Walker.feet().key(), String.format("%.2f", Math.hypot(v.getX(), v.getZ())), hazard);
        }
        if (now - settleStart > SETTLE_TICKS) return false;
        pending = HALT; // Baritone's keys of this tick, if it submitted any before the stop
        Input in = Input.builder().sneaking(true).build();
        INPUTS.submit(InputRequest.builder().owner(owner).priority(Clicker.PRIORITY).input(in).build());
        return true;
    }

    /** The walk stopped or refused Baritone's path this tick: its keys of this tick are cleared. */
    static void halt() {
        pending = HALT;
    }

    /** A new walk (goal list), or the walk ended. */
    void reset() {
        settleStart = -1;
        cornersWaived = false;
        announced = false;
        announcedToward = false;
        guardLogged.clear();
        inHoleLogged = false;
        refusals.reset();
        refusedWhy = null;
    }

    /** The hazard beside {@code feet} ({@link EdgeDrop#beside}), looked up again when the feet move or every 20 ticks. */
    @Nullable String hazard(Pos feet) {
        WorldView w = world;
        if (w == null) return null;
        long tick = System.nanoTime() / 50_000_000L;
        if (!feet.equals(cachedFeet) || tick - cachedAt >= 20) {
            cachedFeet = feet;
            cachedAt = tick;
            EdgeDrop.Cells c = cells(w, zones);
            cachedDirs = EdgeDrop.directions(feet, c);
            if (cachedDirs.isEmpty()) {
                cachedHazard = null;
            } else {
                Pos n = feet.offset(cachedDirs.getFirst()[0], 0, cachedDirs.getFirst()[1]);
                cachedHazard = n.key() + ": " + EdgeDrop.at(n, c);
            }
        }
        return cachedHazard;
    }

    /**
     * The height of the block in a cell for a jump's box ({@link #jumpWhy}): its {@link BlockStates#floorTop} (a plate on
     * one side, a ladder say, is none: the flight goes on past it, on the safe side), 0 for air and unloaded cells.
     */
    static ToDoubleFunction<Pos> solid(WorldView w) {
        return c -> {
            if (!w.isLoaded(c)) return 0;
            BlockSpec b = w.block(c);
            return b == null || b.isAir() ? 0 : BlockStates.floorTop(b);
        };
    }

    /** {@link EdgeDrop.Cells} over a world view and the no-go zones. */
    static EdgeDrop.Cells cells(WorldView w, NoGoZones zones) {
        return new EdgeDrop.Cells() {
            @Override public boolean loaded(Pos p) { return w.isLoaded(p); }
            @Override public BlockSpec block(Pos p) { return w.block(p); }
            @Override public boolean fluid(Pos p) { return w.hasFluid(p); }
            @Override public double collisionTop(BlockSpec b) { return b == null || b.isAir() ? 0 : BlockStates.collisionTop(b); }
            @Override public double floorTop(BlockSpec b) { return b == null || b.isAir() ? 0 : BlockStates.floorTop(b); }
            @Override public boolean noGo(Pos feet) { return zones.at(feet) != null; }
        };
    }

    /**
     * After Baritone's tick, before ZenithProxy's InputManager (MapArtModule): applies this tick's want to the input
     * Baritone submitted. Sneak: its keys sneak and do not sprint; a Baritone tick without keys gets a request of its
     * own with the sneak key and Baritone's rotation (short turn as {@code build.walkShortTurns} would). Halt: no
     * movement key, no jump, no sprint. Then the hole guard of the armed walk ({@link #arm}, {@link #guard}): its keys
     * replace Baritone's (no sprint, no jump), and its sneak is added. Client tick thread.
     */
    static void applyToBaritone(boolean shortTurns) {
        tickHold();
        Want w = pending;
        pending = null;
        EdgeApproach a = armed;
        armed = null;
        Input in = BARITONE.getInputOverrideHandler().currentInput;
        if (w != null && w.halt()) {
            if (in == null) return;
            in.pressingForward = false;
            in.pressingBack = false;
            in.pressingLeft = false;
            in.pressingRight = false;
            in.jumping = false;
            in.sprinting = false;
            return;
        }
        if (w == null && a == null) return;
        if (!BARITONE.getPathingBehavior().isPathing()) return;
        var rot = BARITONE.getLookBehavior().currentRotation;
        Guard g = a == null ? null : a.guardNow(in, rot != null ? rot.yaw() : BOT.getYaw());
        boolean sneak = (w != null && w.sneak()) || (g != null && g.sneak());
        boolean keys = g != null && g.keys();
        if (!sneak && !keys) return;
        if (in != null) {
            if (sneak) in.sneaking = true;
            in.sprinting = false;
            if (keys) {
                in.pressingForward = g.forward() > 0;
                in.pressingBack = g.forward() < 0;
                in.pressingLeft = g.strafe() > 0;
                in.pressingRight = g.strafe() < 0;
                in.jumping = false; // also g.noJump(): a jump into a hole's way is taken away
            }
            return;
        }
        if (!sneak && g.forward() == 0 && g.strafe() == 0) return; // no keys, and none wanted
        // above the short turn's request (same rotation rule), which would otherwise win this tick
        Input own = Input.builder().sneaking(sneak).pressingForward(keys && g.forward() > 0)
            .pressingBack(keys && g.forward() < 0).pressingLeft(keys && g.strafe() > 0)
            .pressingRight(keys && g.strafe() < 0).build();
        var b = InputRequest.builder().owner(OFF).priority(Clicker.PRIORITY + 1).input(own);
        if (rot != null) {
            Float yaw = shortTurns ? WalkYaw.shortTurn(BOT.getYaw(), rot.yaw()) : null;
            b.yaw(yaw != null ? yaw : rot.yaw()).pitch(rot.pitch());
        }
        INPUTS.submit(b.build());
    }
}
