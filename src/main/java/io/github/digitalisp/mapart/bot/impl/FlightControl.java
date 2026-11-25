package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;

/**
 * The elytra flight leg's controller ({@code build.elytraLegs}): one tick of keys
 * (yaw, pitch, jump, a rocket) from the bot's state, for a flight planned by {@link FlightPlanner}. Pure; ZenithProxy's
 * Bot does the moving with these keys, as a vanilla client would ({@link FlightLeg} submits them).
 *
 * <p>Take-off: jump (on the ground), let go, jump again in the air (ZenithProxy's Bot then starts the glide and sends
 * START_ELYTRA_FLYING, as vanilla), and once the glide is on, one rocket. Then the flight: every tick the pitch is
 * chosen by look-ahead ({@link #choose}): for each candidate pitch held from now on, {@link ElytraPhysics} predicts
 * the flight to its touch-down with the boost that is left, and the pitch whose rest point (touch-down plus the slide,
 * {@link ElytraPhysics#slide}) lies nearest R, landing on the pad, over every floor, under every roof (the end rods
 * over the tile) and into no wall on the way, and not too fast, wins. The yaw points at the flight line a few blocks
 * ahead (pure pursuit). Another rocket goes when no pitch reaches the pad any more, or when one would land as well and
 * {@link #ROCKET_SAVES} ticks sooner. On the ground the bot looks down a little (no lift) and waits until the server
 * ended the glide and it stands still.
 *
 * <p>Trouble ends the flight with a reason: on the ground before the glide or the boost ({@link Keys#abort}, the walk
 * goes on from there), or in the air (the controller "bails": the look-ahead then picks the soonest and slowest
 * touch-down on any floor, under every roof and into no wall, {@link #DESCENT_PITCH} when no pitch is safe, until the
 * feet are on the ground; no more rockets).
 */
final class FlightControl {
    /**
     * Down a little: the descent when every pitch of the look-ahead leads into a block or out of the corridor, and the
     * landing of a flight whose walk ended in the air ({@link FlightLeg}). Never up: a climb runs into the end rods.
     */
    static final float DESCENT_PITCH = 10f;
    /** The look on the ground while the server ends the glide: down a little, so the glide gives no lift. */
    static final float GROUND_PITCH = 15f;
    /** Jumps the take-off presses before it gives up (an input of the tick may go to another request). */
    static final int JUMP_TRIES = 3;
    /** Pitch during the take-off and while the first rocket comes. */
    static final float CLIMB_PITCH = -20f;
    /** The candidate pitches of the look-ahead, degrees, and the most the pitch changes in a tick. */
    static final float PITCH_MIN = -40f;
    static final float PITCH_MAX = 40f;
    static final float PITCH_STEP = 2.5f;
    static final float PITCH_RATE = 20f;
    /** Pure pursuit: the yaw points at the flight line this many blocks ahead of the bot (or at R, if nearer). */
    static final double PURSUIT = 8;
    /** Look-ahead horizon in ticks. */
    static final int HORIZON = 400;
    /** Margin above a column's top that counts as touching it, outside the pad. */
    static final double CONTACT = 0.2;

    /**
     * {@code slideTicks}: ticks the client keeps the glide on the ground (the server's answer); {@code boostTicks}: the
     * boost counted on for a rocket (a duration-1 rocket boosts 20-31 ticks: the least); {@code rocketDelay}: ticks from
     * the use to the boost (a round trip); {@code maxRockets}: per flight; {@code maxLandSpeed}: blocks a tick at the
     * touch-down above which a landing costs score; {@code maxTicks}: the flight's limit; {@code boostMax}: the
     * longest boost a rocket may give by vanilla; {@code boostWorst}: the longest it may last in bot ticks, the server's
     * stretch included (durations 2 and 3: what the flight plans for, {@link #chooseBoth}, {@link #overshoots}).
     */
    record Cfg(int slideTicks, int boostTicks, int rocketDelay, int maxRockets, double maxLandSpeed, int maxTicks,
               int boostMax, int boostWorst) {
        static Cfg defaults() {
            return forDuration(3, 1, 3);
        }

        /**
         * A flight with rockets of flight duration {@code d}: vanilla's lifetime is 10 * (1 + d) + nextInt(6) +
         * nextInt(7) ticks, so the boost counted on is 10 * (1 + d) and the worst case 10 * (1 + d) + 11 (d 1: 20-31,
         * d 3: 40-51).
         */
        static Cfg forDuration(int slideTicks, int d, int maxRockets) {
            return forDuration(slideTicks, d, maxRockets,
                d <= 1 ? FlightControl.boostMax(d) : FlightControl.boostMax(d) + GATE_SLACK_TICKS);
        }

        /** This with {@code n} rockets a flight. */
        Cfg withRockets(int n) {
            return new Cfg(slideTicks, boostTicks, rocketDelay, n, maxLandSpeed, maxTicks, boostMax, boostWorst);
        }

        /** {@link #forDuration(int, int, int)} planning for boosts of up to {@code boostWorst} bot ticks. */
        static Cfg forDuration(int slideTicks, int d, int maxRockets, int boostWorst) {
            return new Cfg(slideTicks, FlightControl.boostMin(d), 4, maxRockets, 1.8, 600, FlightControl.boostMax(d),
                Math.max(FlightControl.boostMax(d), boostWorst));
        }
    }

    /** The shortest boost of a duration-{@code d} rocket (vanilla lifetime 10 * (1 + d) + 0 + 0). */
    static int boostMin(int d) {
        return 10 * (1 + Math.max(0, d));
    }

    /** The longest boost of a duration-{@code d} rocket (10 * (1 + d) + 5 + 6). */
    static int boostMax(int d) {
        return boostMin(d) + 11;
    }

    /** {@link #minLegBlocks(int, int, int)} with vanilla's longest boost (no server stretch). */
    static int minLegBlocks(int d, int configured) {
        return minLegBlocks(d, configured, boostMax(d));
    }

    /**
     * The shortest leg (blocks) that may fly with duration-{@code d} rockets whose boost may last up to
     * {@code worstBoost} bot ticks: the take-off rocket boosts from the start (about 1.6 blocks a tick) and must burn out
     * with room to glide down onto the pad. Duration 1: {@code build.elytraMinBlocks} (60). Longer:
     * from the simulation sweeps (every leg kind, round trips 2-8 ticks, boosts from 40 to
     * the worst, one rocket: on the pad, no contact, at most 1.8 b/t, rest at most 4 past R): {@link #MIN_LEG_BY_WORST},
     * interpolated and rounded up to 5; {@link Integer#MAX_VALUE} (walk) past its last row. A cheap first filter: each
     * leg that passes it flies only if {@link #preflight} finds its landing.
     */
    static int minLegBlocks(int d, int configured, int worstBoost) {
        if (d <= 1) return configured;
        int[][] t = MIN_LEG_BY_WORST;
        if (worstBoost <= t[0][0]) return Math.max(configured, t[0][1]);
        for (int i = 1; i < t.length; i++) {
            if (worstBoost <= t[i][0]) {
                double f = (double) (worstBoost - t[i - 1][0]) / (t[i][0] - t[i - 1][0]);
                double blocks = t[i - 1][1] + f * (t[i][1] - t[i - 1][1]);
                return Math.max(configured, (int) Math.ceil(blocks / 5.0 - 1e-9) * 5);
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * Worst boost (bot ticks) to the shortest duration-3 leg that may land with it: the shortest the simulation sweep
     * flew (bare tile and dense built art, every leg kind; {@link #preflight} decides each leg).
     * 5.31's rows were 95, 110 and 120, from the controller before {@link #both}.
     */
    static final int[][] MIN_LEG_BY_WORST = {{51, 90}, {60, 105}, {66, 115}};

    /**
     * The longest boost (bot ticks) a duration-{@code d} rocket may give now: vanilla's longest ({@link #boostMax}),
     * stretched by a server below 20 TPS (the bot counts its own 20 ticks a second while the server's rocket lives
     * 40-51 of its ticks), and at least {@link #WATCH_MARGIN} over the longest boost of the recent flights
     * ({@code recentMax}; none: {@link #NO_HISTORY_MARGIN} over vanilla's longest, live 2026-10-25 09:10: a first flight
     * 7 s after a redeploy). Duration 1: vanilla's. A boost longer still is burnt in flight
     * ({@link FlightControl#burnKeys}).
     */
    static int worstBoost(int d, double tps, int recentMax) {
        int base = boostMax(d);
        if (d <= 1) return base;
        int fromTps = Double.isNaN(tps) || tps <= 1 ? base : (int) Math.ceil(base * 20.0 / Math.min(20.0, tps) - 1e-9);
        int fromSeen = recentMax > 0 ? recentMax + WATCH_MARGIN : base + NO_HISTORY_MARGIN;
        return Math.max(base, Math.max(fromTps, fromSeen));
    }

    /**
     * Bot ticks over the longest recent boost that the next flight plans for ({@link #worstBoost}): 5 (3 before live
     * 2026-10-25 08:33, a boost of 59 against 51 + 3 planned for).
     */
    static final int WATCH_MARGIN = 5;
    /** Bot ticks over vanilla's longest boost a flight plans for with no recent boost seen ({@link #worstBoost}). */
    static final int NO_HISTORY_MARGIN = 5;

    /**
     * The boosts of the recent one-rocket flights with durations 2 and 3 (bot ticks; the last {@link #SIZE}, none older
     * than {@link #MAX_AGE_MS}): the server's stretch as the bot measures it.
     */
    static final class BoostWatch {
        static final int SIZE = 5;
        static final long MAX_AGE_MS = 15 * 60_000L;
        private final int[] ticks = new int[SIZE];
        private final long[] at = new long[SIZE];
        private int n;
        private int next;

        synchronized void add(int boostTicks, long nowMs) {
            ticks[next] = boostTicks;
            at[next] = nowMs;
            next = (next + 1) % SIZE;
            n = Math.min(SIZE, n + 1);
        }

        /** The longest recent boost, 0 for none. */
        synchronized int recentMax(long nowMs) {
            int max = 0;
            for (int i = 0; i < n; i++) if (nowMs - at[i] <= MAX_AGE_MS) max = Math.max(max, ticks[i]);
            return max;
        }
    }

    /**
     * The bot this tick: feet position, velocity, ground, the glide flag, whether a rocket of ours boosts, rockets
     * carried, and whether a rocket may be used now ({@code canRocket}: held in the main hand, and the look hits no block
     * within reach, where a vanilla client's right click would use it on that block instead).
     */
    record In(double x, double y, double z, double vx, double vy, double vz, boolean onGround, boolean fallFlying,
              boolean boosting, int rockets, float pitch, boolean canRocket) {
        double hSpeed() {
            return Math.hypot(vx, vz);
        }
    }

    /** This tick's keys; {@code done}: landed and still; {@code abort}: why the flight ended early (null otherwise). */
    record Keys(float yaw, float pitch, boolean jump, boolean rocket, boolean done, @Nullable String abort) { }

    /**
     * {@code BURN}: the boost lasts past the longest planned for ({@link #burnKeys}); {@code BRAKE}: a short landing's
     * brake ({@link FlightBrake}), or the slow landing after a burn.
     */
    enum Phase { JUMP, RELEASE, START, ROCKET, WAIT_BOOST, FLY, BURN, BRAKE, BAIL, LANDED }

    private final FlightPlanner.Profile p;
    private final Cfg cfg;
    /**
     * The longest boost (bot ticks) the flight plans for now: {@link Cfg#boostWorst}, raised in flight when the server
     * slows down ({@link #raiseWorst}).
     */
    private int worstNow;
    /**
     * A boost past the longest planned for ({@link #burnKeys}): since that tick (-1: none); the cruise on toward the
     * landing and the spin hover's ticks; the spin's yaw, its sense and the height it holds.
     */
    private int burnTick = -1;
    private int cruiseTicks;
    private int spinTicks;
    private boolean spinning;
    private float spinYaw;
    private int spinDir = 1;
    private double spinY = Double.NaN;
    /** No more rockets this flight (a burn). */
    private boolean noMoreRockets;
    /** The brake's target ({@link Phase#BRAKE}): T for a short landing, else the pad's nearest point after a burn. */
    private double brakeX;
    private double brakeZ;
    private int brakeTick = -1;
    /** Players' feet (x, y, z) near the flight, from {@link #players}: a short landing's spot must stay clear of them. */
    private java.util.List<double[]> players = java.util.List.of();
    /** Why the flight gave up its landing in the air for a player (the leg's line), or null. */
    private @Nullable String playerWhy;
    private Phase phase = Phase.JUMP;
    private int ticks;
    private int phaseTicks;
    private int rocketsFired;
    private int boostAge = -1;
    private int pendingSince = -1;
    private float pitch;
    private float yaw;
    private @Nullable String bailWhy;
    /** Touch-down: where, how fast, the tick; ticks until the glide flag cleared. */
    private double tdX = Double.NaN;
    private double tdZ;
    private double tdH;
    private double tdVy;
    private int tdTick = -1;
    private int flagTicks = -1;
    private int boostTicksSeen;
    private int brakeTicks;
    /** Jumps pressed for the take-off ({@link #JUMP_TRIES}). */
    private int jumps;
    /** Speeds of the last tick in the air. */
    private double airH;
    private double airVy;
    private @Nullable Choice lastChoice;
    /** Set when the leg's walk ended in the air ({@link #endInTheAir}): the next tick bails out with it. */
    private @Nullable String forcedBail;

    /** An arc flight's way ({@link FlightPlanner#planArc}), null for a straight one; the bot's place on it. */
    private final @Nullable FlightPath path;
    private double pathAt;

    FlightControl(FlightPlanner.Profile profile, Cfg cfg, float startPitch) {
        this.p = profile;
        this.cfg = cfg;
        this.worstNow = cfg.boostWorst();
        this.pitch = startPitch;
        this.path = profile.path;
        this.brakeX = profile.restX;
        this.brakeZ = profile.restZ;
        this.yaw = takeoffYaw(profile);
    }

    /**
     * The take-off's yaw (the jump, the glide start and the take-off rocket look this way at {@link #CLIMB_PITCH}): toward
     * R, or along an arc's take-off line.
     */
    static float takeoffYaw(FlightPlanner.Profile profile) {
        if (profile.path == null) return ElytraPhysics.yawTo(profile.startX, profile.startZ, profile.restX, profile.restZ);
        double[] h = profile.path.heading(0);
        return ElytraPhysics.yawTo(0, 0, h[0], h[1]);
    }

    /**
     * The eyes over the take-off floor while the take-off rocket may go ({@link Phase#ROCKET}, up to 3 ticks waiting):
     * the jump 1.0-1.25 up, the glide's pose with its eyes 0.4 over the feet. {@link FlightLeg} looks along
     * {@link #takeoffYaw} from both before it takes off.
     */
    static final double ROCKET_EYE_LOW = 1.35;
    static final double ROCKET_EYE_HIGH = 1.65;

    /** Pure pursuit on an arc's way: the yaw aims this far ahead on the take-off line and the turn ... */
    static final double PURSUIT_ARC = 5;

    /** An arc flight's yaw for a bot at (x, z), place {@code at} on the way: at the way {@link #arcLook} ahead. */
    private float arcYaw(double x, double z, double at) {
        double[] t = path.aim(at, arcLook(at));
        return ElytraPhysics.yawTo(x, z, t[0], t[1]);
    }

    /** ... {@link #PURSUIT_ARC} until the turn ends, then up to {@link #PURSUIT} over the final line's first blocks. */
    private double arcLook(double at) {
        double past = at - path.turnEnd();
        return past <= 0 ? PURSUIT_ARC : Math.min(PURSUIT, PURSUIT_ARC + past);
    }

    /**
     * One look-ahead tick: {@link ElytraPhysics#fly} with yaw {@code yawC}, or on an arc flight with the yaw the
     * controller would give at that state (its place on the way carried in {@link ElytraPhysics.State#along}; NaN:
     * the bot's own).
     */
    private ElytraPhysics.State next(ElytraPhysics.State s, float yawC, float pitchC, boolean boost) {
        return next(s, yawC, pitchC, boost, false);
    }

    /**
     * {@link #next(ElytraPhysics.State, float, float, boolean)}; {@code mayBrake}: no boost on or coming at this tick,
     * so a short landing's brake ({@link FlightBrake}) starts once the state is within its reach of T
     * ({@link #brakeReach}); a state already braking ({@link ElytraPhysics.State#braking}) brakes on in any case.
     */
    private ElytraPhysics.State next(ElytraPhysics.State s, float yawC, float pitchC, boolean boost, boolean mayBrake) {
        double at = path == null ? Double.NaN : Double.isNaN(s.along()) ? pathAt : s.along();
        if (s.braking() || !boost && mayBrake && p.spot && brakeReach(s.x(), s.z(), s.hSpeed(), at)) {
            float last = s.braking() && !Float.isNaN(s.yaw()) ? s.yaw() : plainYaw(s, yawC, at);
            float by = FlightBrake.yaw(s.x(), s.z(), s.vx(), s.vz(), brakeX, brakeZ, last);
            float bp = FlightBrake.pitch(s.x(), s.y(), s.z(), s.vx(), s.vz(), brakeX, brakeZ, p.surface);
            ElytraPhysics.State n = ElytraPhysics.fly(s, by, bp, boost).braked(by);
            return path == null ? n : n.along(path.track(n.x(), n.z(), at));
        }
        if (path == null && !offWay) return ElytraPhysics.fly(s, yawC, pitchC, boost);
        float y = plainYaw(s, yawC, at);
        if (path == null) return ElytraPhysics.fly(s, y, pitchC, boost);
        ElytraPhysics.State n = ElytraPhysics.fly(s, y, pitchC, boost);
        return n.along(path.track(n.x(), n.z(), at));
    }

    /** The look-ahead's yaw at state {@code s} without a brake: {@code yawC}, or on an arc flight its way's ({@link #next}). */
    private float plainYaw(ElytraPhysics.State s, float yawC, double at) {
        if (!offWay) return path == null ? yawC : arcYaw(s.x(), s.z(), at);
        return Float.isNaN(relYaw) || s.hSpeed() < 0.05 ? yawC : ElytraPhysics.yawTo(0, 0, s.vx(), s.vz()) + relYaw;
    }

    /**
     * A short landing's brake starts here: within {@link FlightBrake#trigger} of the brake's target (T) at horizontal
     * speed {@code h}, and on an arc flight past its turn ({@code at}: the place on the way).
     */
    private boolean brakeReach(double x, double z, double h, double at) {
        if (path != null && !leftWay && !Double.isNaN(at) && at < path.turnEnd()) return false;
        return Math.hypot(brakeX - x, brakeZ - z) <= FlightBrake.trigger(h);
    }

    /**
     * Arc flights off the way ({@link #leaveTheWay}): the look turned this far from the heading every tick, a braking
     * spiral (vanilla's glide bleeds a tenth of the speed a tick with the look 90 degrees off); NaN: the yaw held.
     */
    private float relYaw = Float.NaN;
    /** The spiral turns {@link #leaveTheWay} looks at (degrees off the heading). */
    static final float[] SPIRAL_YAWS = {-90f, 90f, -60f, 60f};

    /**
     * Arc flights: the look-ahead flies the yaw it is given, not the way's pursuit ({@link #leaveTheWay}); set while it
     * looks at those candidates, and from the tick the flight leaves its way on.
     */
    private boolean offWay;
    /**
     * Arc flights: the flight left its way (the pad out of its reach): from then on the held heading, fixed yaws off
     * it or a braking spiral ({@link #leaveTheWay}), always for the soonest, slowest safe touch-down.
     */
    private boolean leftWay;
    /** Yaws about the bot's heading that {@link #leaveTheWay} holds, degrees. */
    static final float[] OFF_WAY_YAWS = {0f, -30f, 30f, -60f, 60f};
    /** {@link #OFF_WAY_YAWS} with players near: wider turns too, away from a landing they took. */
    static final float[] AWAY_YAWS = {0f, -30f, 30f, -60f, 60f, -90f, 90f, -120f, 120f};

    /**
     * Arc flights, the boost over and the pad out of reach along the way (an early end of the boost, the turn not
     * flown out), or a bail-out: the soonest, slowest safe touch-down (a bail-out's scoring) of the way's choice
     * {@code onWay} (until the flight has left the way), a yaw held at the bot's heading or {@link #OFF_WAY_YAWS} off
     * it, and a braking spiral ({@link #SPIRAL_YAWS}). A glide after a boost cannot turn as tight as the way, so the
     * way's turn may carry it wide into a wall; one of those lands it slowly on the floor near it instead. Sets
     * {@link #yaw} when one of them wins; the flight keeps to it from then on (the yaw or the spiral it chose is the
     * next tick's first candidate).
     */
    private Choice leaveTheWay(ElytraPhysics.State now, In in, @Nullable Choice onWay, int boostIn, int boostLeft) {
        double h = Math.hypot(in.vx(), in.vz());
        float head = h < 0.1 ? yaw : ElytraPhysics.yawTo(0, 0, in.vx(), in.vz());
        Choice best = leftWay ? null : onWay;
        float bestYaw = Float.NaN;
        float bestRel = Float.NaN;
        boolean keepRel = leftWay && !Float.isNaN(relYaw);
        float heldRel = relYaw;
        offWay = true;
        try {
            // players near the landing: turns away from them as well (a teammate on the spot, live 2026-10-25 test)
            float[] holds = players.isEmpty() ? OFF_WAY_YAWS : AWAY_YAWS;
            int n = holds.length + SPIRAL_YAWS.length;
            // the choice held so far first (a turn toward its yaw, or its spiral, goes on)
            for (int i = leftWay ? -1 : 0; i < n; i++) {
                float rel = i < 0 ? heldRel : i < holds.length ? Float.NaN : SPIRAL_YAWS[i - holds.length];
                float cand = i < 0 ? (keepRel ? head + heldRel : yaw) : i < holds.length ? head + holds[i]
                    : head + rel;
                relYaw = rel;
                Choice ch = choose(now, cand, boostIn, boostLeft, pitch, true);
                if (best == null || ch.score() < best.score() - 1e-9) {
                    best = ch;
                    bestYaw = cand;
                    bestRel = rel;
                }
            }
        } finally {
            offWay = leftWay;
            relYaw = heldRel;
        }
        if (!Float.isNaN(bestYaw)) {
            leftWay = true;
            offWay = true;
            yaw = bestYaw;
            relYaw = bestRel;
        }
        return best;
    }

    /** The arc flight's way, or null. */
    @Nullable FlightPath path() {
        return path;
    }

    /**
     * The place along the flight of a look-ahead state at (x, z): on an arc flight its place on the way (carried in
     * {@link ElytraPhysics.State#along}, NaN: the bot's own), else along the line. On the final line the two agree.
     */
    private double alongOf(double x, double z, double tracked) {
        if (path == null) return p.along(x, z);
        return Double.isNaN(tracked) ? pathAt : tracked;
    }

    /** Off the flight at (x, z): on an arc flight the distance off the way at its place {@code at}, else across the line. */
    private double acrossOf(double x, double z, double at) {
        if (path == null) return p.across(x, z);
        double[] q = path.point(at);
        double[] h = path.heading(at);
        return (x - q[0]) * -h[1] + (z - q[1]) * h[0];
    }

    /** The flight's heading at the bot's place: an arc's way there, else the line's. */
    private double[] dirNow() {
        if (path == null) return new double[]{p.dirX, p.dirZ};
        if (leftWay) return new double[]{-Math.sin(Math.toRadians(yaw)), Math.cos(Math.toRadians(yaw))};
        return path.heading(Math.min(pathAt, path.length()));
    }

    /** The bot's place on an arc flight's way (0 for a straight one). */
    double pathAt() {
        return pathAt;
    }

    Phase phase() {
        return phase;
    }

    int ticks() {
        return ticks;
    }

    int rocketsFired() {
        return rocketsFired;
    }

    int boostTicks() {
        return boostTicksSeen;
    }

    /**
     * Where the feet touched down (NaN before), the speeds of the last tick in the air before it, its tick and the ticks
     * until the glide flag cleared.
     */
    double touchX() {
        return tdX;
    }

    double touchZ() {
        return tdZ;
    }

    double touchSpeed() {
        return tdH;
    }

    double touchVy() {
        return tdVy;
    }

    int touchTick() {
        return tdTick;
    }

    int flagTicks() {
        return flagTicks;
    }

    /**
     * The burn of a boost past the longest planned for, in words for the leg's line ("" when none): when it began, the
     * ticks of cruise on toward the landing and of spin hover.
     */
    String burnWords() {
        if (burnTick < 0) return "";
        if (hovering) {
            return String.format("hovered: the boost burned from flight tick %d (%d ticks on toward the spot, %d spinning over"
                + " it)", burnTick, cruiseTicks, spinTicks);
        }
        return String.format("%s: burned from flight tick %d (%d ticks on toward the landing, %d spinning in place)",
            burnEarly ? "no landing with the longest boost now planned for (" + worstNow + " ticks)"
                : "the boost ran past the " + worstNow + " ticks planned for", burnTick, cruiseTicks, spinTicks);
    }

    /** The burn began before the longest boost planned for: no landing with it from there ({@link #fly}). */
    private boolean burnEarly;
    /** The burn is a hover's ({@link FlightPlanner.Profile#hover}), from the boost's start. */
    private boolean hovering;

    /** The tick the brake began (-1: none), for the leg's line. */
    int brakeTick() {
        return brakeTick;
    }

    /** The longest boost the flight plans for now ({@link #raiseWorst}). */
    int worstNow() {
        return worstNow;
    }

    /**
     * The server slowed down in flight (its TPS, a longer recent boost): the boost counted on may last up to {@code worst}
     * bot ticks; never lowered. The look-ahead's worst case and the burn's start ({@link #burnAt}) follow it.
     */
    void raiseWorst(int worst) {
        if (worst > worstNow) worstNow = worst;
    }

    /**
     * Players' feet near the flight this tick (x, y, z; the bot itself not among them): a short landing's spot (or a
     * long pad's touch-down) with one within {@link #PLAYER_MARGIN} is given up before the touch-down's commit
     * ({@link #playerTaken}).
     */
    void players(java.util.List<double[]> feet) {
        this.players = feet == null ? java.util.List.of() : feet;
    }

    /** Why the landing was given up in the air for a player, or null. */
    @Nullable String playerWhy() {
        return playerWhy;
    }

    /**
     * The server sends nothing this tick for {@link TakeoffGuard#SILENT_MS} or longer (frozen: live 2026-10-25 08:47, 37 s
     * while the client simulated on). Its rocket's end cannot come meanwhile: a boost past the shortest burns at once
     * (the spin, no cruise on), and a landing is not done until the server speaks again.
     */
    void serverSilent(boolean on) {
        silent = on;
    }

    private boolean silent;

    /** One tick. */
    Keys tick(In in) {
        ticks++;
        phaseTicks++;
        if (teleported && ticks <= tpUntil && in.onGround()) {
            // the bot's ground flag right after a server teleport is the packet's, not the place's (local 10x
            // 2026-10-25: a setback 6.6 blocks back left the bot 3.5 up with "on the ground"; the flight took it for the
            // touch-down and sank at the landing's pitch): on the ground only at the corridor's floor
            double fl = p.top((int) Math.floor(in.x()), (int) Math.floor(in.z()));
            if (Double.isNaN(fl) || in.y() - fl > TP_GROUND_SLACK) {
                in = new In(in.x(), in.y(), in.z(), in.vx(), in.vy(), in.vz(), false, in.fallFlying(), in.boosting(),
                    in.rockets(), in.pitch(), in.canRocket());
                if (phase == Phase.LANDED && phaseTicks <= tpUntil - tpFrom + 1) {
                    // the false ground already ended the flight: in the air again (a bail-out from here)
                    tdX = Double.NaN;
                    to(Phase.BAIL);
                    if (bailWhy == null) bailWhy = "a server teleport in the air";
                }
            }
        }
        if (path != null) pathAt = path.track(in.x(), in.z(), pathAt);
        if (!in.onGround()) {
            airH = in.hSpeed();
            airVy = in.vy();
        }
        if (in.boosting()) {
            boostTicksSeen++;
            boostAge = boostAge < 0 ? 0 : boostAge + 1;
            pendingSince = -1;
        } else if (boostAge >= 0) {
            boostAge = -1;
        }
        if (teleported && phase != Phase.LANDED) {
            // a server teleport or setback moved the bot (local check 2026-10-25: one during a take-off, the flight went
            // on from the new place and hit a wall, 4.1 HP): before the glide the take-off ends; in the air the burn's
            // way down (under a boost a cruise on or a spin in place, then the landing from there, the brake or a
            // bail-out, all looked ahead in the corridor's world); off the corridor a hold in place
            double floor = p.top((int) Math.floor(in.x()), (int) Math.floor(in.z()));
            if (!in.fallFlying() && (phase == Phase.JUMP || phase == Phase.RELEASE || phase == Phase.START
                || phase == Phase.ROCKET)) {
                // before the glide: the take-off ends (a fall from under TP_GLIDE_HEIGHT does no harm); moved high over
                // the corridor's floor, the glide on and down as a bail-out (no fall)
                double apex = in.y() - floor + (in.vy() > 0 ? in.vy() * in.vy() / ElytraPhysics.GRAVITY : 0);
                if (in.onGround() || Double.isNaN(floor) || apex < TP_GLIDE_HEIGHT) {
                    return abort("a server teleport during the take-off");
                }
                tpJump = !tpJump;
                return keys(0f, tpJump, false);
            }
            if (in.onGround()) return land(in);
            if (tpHold || tpFresh && Double.isNaN(floor)) {
                tpHold = true;
                if (bailWhy == null) bailWhy = "a server teleport off the flight's corridor";
                if (phase != Phase.BAIL) to(Phase.BAIL);
                return holdKeys(in);
            }
            if (tpFresh) {
                tpFresh = false;
                // the glide on before the take-off's rocket (moved high): no rocket, the look-ahead's glide from here
                if (phase == Phase.JUMP || phase == Phase.RELEASE || phase == Phase.START || phase == Phase.ROCKET) {
                    to(Phase.FLY);
                }
            }
        }
        if (forcedBail != null && phase != Phase.LANDED && phase != Phase.BAIL) {
            String why = forcedBail;
            forcedBail = null;
            return bail(why, in);
        }
        if (ticks > cfg.maxTicks() && phase != Phase.LANDED && phase != Phase.BAIL) {
            return bail("the flight took over " + cfg.maxTicks() + " ticks", in);
        }
        switch (phase) {
            case JUMP -> {
                if (!in.onGround()) return abort("not on the ground at the take-off");
                to(Phase.RELEASE);
                return keys(CLIMB_PITCH, true, false);
            }
            case RELEASE -> {
                if (!in.onGround()) {
                    to(Phase.START);
                    return keys(CLIMB_PITCH, false, false);
                }
                if (phaseTicks > 3) {
                    // the jump's input may have gone to another request of the tick (a task's last one): again
                    if (++jumps < JUMP_TRIES) to(Phase.JUMP);
                    else return abort("the jump did not leave the ground (" + JUMP_TRIES + " jumps)");
                }
                return keys(CLIMB_PITCH, false, false);
            }
            case START -> {
                if (in.onGround()) return abort("back on the ground before the glide started");
                to(Phase.ROCKET);
                return keys(CLIMB_PITCH, true, false);
            }
            case ROCKET -> {
                if (!in.fallFlying()) return in.onGround() ? abort("the glide did not start") : bail("the glide did not start", in);
                if (in.rockets() <= 0) return bail("no rocket left", in);
                if (!in.canRocket()) {
                    if (phaseTicks > 3) return bail("no rocket could be used (not held, or a block in the look)", in);
                    return keys(CLIMB_PITCH, false, false);
                }
                rocketsFired++;
                pendingSince = ticks;
                to(Phase.WAIT_BOOST);
                return keys(CLIMB_PITCH, false, true);
            }
            case WAIT_BOOST -> {
                if (in.onGround()) return abort("on the ground before the rocket boosted");
                if (!in.fallFlying()) return bail("the server ended the glide before the boost", in);
                if (in.boosting() || phaseTicks > cfg.rocketDelay() + 12) {
                    to(Phase.FLY);
                    return fly(in);
                }
                yaw = pursuitYaw(in.x(), in.z());
                return keys(CLIMB_PITCH, false, false);
            }
            case FLY -> {
                if (in.onGround()) return land(in);
                if (!in.fallFlying()) return bail("the server ended the glide in the air", in);
                return fly(in);
            }
            case BURN -> {
                if (in.onGround()) return land(in);
                if (!in.fallFlying()) return bail("the server ended the glide in the air", in);
                return burnKeys(in);
            }
            case BRAKE -> {
                if (in.onGround()) return land(in);
                if (!in.fallFlying()) return bail("the server ended the glide in the air", in);
                return brakeKeys(in);
            }
            case BAIL -> {
                if (in.onGround()) return land(in);
                // a boost past the longest planned for: spin in place until it ends, then down (a bail-out's landing)
                if (in.fallFlying() && in.boosting() && boostAge >= burnAt()) return spinKeys(in, state(in));
                return bailKeys(in);
            }
            case LANDED -> {
                return land(in);
            }
        }
        return abort("unknown phase");
    }

    private Keys land(In in) {
        if (phase != Phase.LANDED) {
            if (Double.isNaN(tdX)) {
                tdX = in.x();
                tdZ = in.z();
                tdH = airH;
                tdVy = airVy;
                tdTick = ticks;
            }
            phase = Phase.LANDED;
            phaseTicks = 0;
        }
        if (!in.fallFlying() && flagTicks < 0) flagTicks = phaseTicks;
        if (in.fallFlying() && in.boosting()) {
            // a rocket that still boosts on the ground (a boost past the longest planned for: live 2026-10-25 08:47, 85
            // ticks, the bot slid on at 1.3-1.7 b/t toward a no-go zone while it looked ahead): look straight down. The
            // boost then pushes into the ground and halves the speed across it a tick (v + (1.5 L - v) / 2 with L down)
            groundBoostTicks++;
            return keys(GROUND_BOOST_PITCH, false, false);
        }
        if (in.fallFlying()) {
            // the glide goes on on the ground until the server's answer: brake by turning the back to the flight
            // (ElytraPhysics.slide); a short landing or a slow one after a burn keeps its brake's look (it holds over T)
            if (phase == Phase.LANDED && brakeTick >= 0) {
                yaw = FlightBrake.yaw(in.x(), in.z(), in.vx(), in.vz(), brakeX, brakeZ, yaw);
            } else {
                double[] d = in.hSpeed() > 0.05 ? new double[]{in.vx() / in.hSpeed(), in.vz() / in.hSpeed()} : dirNow();
                brakeTicks++;
                boolean right = acrossOf(in.x(), in.z(), pathAt) > 0;
                yaw = brakeTicks == 1
                    ? (right ? ElytraPhysics.yawTo(0, 0, d[1], -d[0]) : ElytraPhysics.yawTo(0, 0, -d[1], d[0]))
                    : ElytraPhysics.yawTo(0, 0, -d[0], -d[1]);
            }
        }
        // done only standing still with the glide off (live 2026-10-25 08:47: "done" after 60 ticks while it still slid
        // at 1.34 b/t ended the walk, and the next path ran into the no-go zones); LAND_MAX ticks at the most
        boolean still = !in.fallFlying() && in.onGround() && in.hSpeed() < 0.05 && !in.boosting();
        if (still && !silent || phaseTicks > LAND_MAX && !silent) {
            Keys k = keys(GROUND_PITCH, false, false);
            return new Keys(k.yaw(), k.pitch(), false, false, true, bailWhy);
        }
        return keys(GROUND_PITCH, false, false);
    }

    /** The look on the ground while a rocket still boosts: straight down ({@link #land}). */
    static final float GROUND_BOOST_PITCH = 90f;
    /** Ticks after the touch-down the landing waits at the most for the bot to stand still with the glide off. */
    static final int LAND_MAX = 200;
    /** Ticks on the ground with a rocket still boosting (the leg's line). */
    private int groundBoostTicks;

    int groundBoostTicks() {
        return groundBoostTicks;
    }

    /** The bot this tick as a look-ahead state. */
    private static ElytraPhysics.State state(In in) {
        return new ElytraPhysics.State(in.x(), in.y(), in.z(), in.vx(), in.vy(), in.vz());
    }

    /** Ticks until the boost counted on starts, and how long it lasts: a rocket that boosts, or one on its way. */
    private int[] boostWindow(In in) {
        int boostLeft;
        if (in.boosting()) boostLeft = Math.max(1, cfg.boostTicks() - Math.max(0, boostAge));
        else boostLeft = 0;
        int boostIn = 0;
        if (!in.boosting() && pendingSince >= 0) {
            boostIn = Math.max(0, cfg.rocketDelay() - (ticks - pendingSince));
            boostLeft = cfg.boostTicks();
            if (ticks - pendingSince > cfg.rocketDelay() + 12) pendingSince = -1; // that rocket never came
        }
        return new int[]{boostIn, boostLeft};
    }

    /**
     * The longest the boost counted on may still last (durations 2 and 3: {@link Cfg#boostMax} plus
     * {@link #GATE_SLACK_TICKS}, the stretch a server slower than the bot adds); 0 when no boost is on or coming, or for
     * duration 1 (flies as since 5.25).
     */
    private int worstBoostLeft(In in) {
        if (cfg.boostTicks() <= boostMin(1) || cfg.boostMax() <= cfg.boostTicks()) return 0;
        int worst = worstNow;
        if (in.boosting()) return Math.max(1, worst - Math.max(0, boostAge));
        return pendingSince >= 0 ? worst : 0;
    }

    /** An arc whose boost ends this far past its turn or less, the pad out of reach: down off the way (blocks). */
    static final double LEAVE_PAST_TURN = 3;

    private Keys fly(In in) {
        if (p.hover && in.boosting() && burnTick < 0) {
            // a hover (a leg shorter than the boost's reach, the clear stand over the end rods): the boost burnt from its
            // start, on toward T at the band's middle and a spin over it until the boost ends, then the brake down
            hovering = true;
            return startBurn(in, false);
        }
        if (in.boosting() && (boostAge >= burnAt() || silent && boostAge >= cfg.boostTicks())) {
            // the boost lasts past the longest planned for (a server that lags in flight: live 2026-10-25 08:47, 85
            // ticks against 54): no landing while it pushes; on toward the landing, then a spin in place until it ends
            return startBurn(in, false);
        }
        if (!players.isEmpty() && !playerCommit) {
            // players near the landing: none of their cells is touched down on; the landing itself given up for one
            // before the commit (out of the boost, within PLAYER_CHECK of it), a bail-out away from them instead
            String taken = playerTaken();
            if (taken != null && !in.boosting() && pendingSince < 0 && mayDodge(in) && toLanding(in) < PLAYER_CHECK) {
                return dodge(taken, in);
            }
        }
        if (p.spot && !in.boosting() && pendingSince < 0 && !leftWay
            && brakeReach(in.x(), in.z(), in.hSpeed(), path == null ? Double.NaN : pathAt)) {
            // a short landing: the brake onto T from here (FlightBrake)
            startBrake(p.restX, p.restZ);
            return brakeKeys(in);
        }
        yaw = pursuitYaw(in.x(), in.z());
        int[] bw = boostWindow(in);
        int boostIn = bw[0];
        int boostLeft = bw[1];
        int worstLeft = worstBoostLeft(in);
        ElytraPhysics.State now = new ElytraPhysics.State(in.x(), in.y(), in.z(), in.vx(), in.vy(), in.vz());
        if (relay && rocketsFired == 1) {
            Keys k = relayKeys(in, now, boostIn, boostLeft, worstLeft);
            if (k != null) return k;
        }
        if (leftWay) {
            // arc flights off the way: down, the yaw or the spiral leaveTheWay chose
            Choice down = leaveTheWay(now, in, null, boostIn, boostLeft);
            lastChoice = down;
            return steer(down.pitch(), false);
        }
        Choice best = worstLeft > boostLeft ? chooseBoth(now, yaw, boostIn, boostLeft, worstLeft, pitch)
            : choose(now, yaw, boostIn, boostLeft, pitch);
        lastChoice = best;
        tpMisses = teleported && in.boosting() && best.score() >= OFF_PAD ? tpMisses + 1 : 0;
        if (tpMisses >= TP_MISSES) {
            // after a server teleport no way onto the landing under this boost, tick after tick: the burn (a cruise on
            // or a spin in place until it ends, then the landing from there), not the best of the misses (simulation
            // 2026-10-25: from 28 blocks aside that dived onto the built art at 1.5 b/t under the boost; the first
            // ticks after a jump aside often miss while the look comes round)
            return startBurn(in, true);
        }
        if (in.boosting() && boostAge >= cfg.boostTicks() && worstLeft > boostLeft && burnsEarly(best)) {
            // past the shortest boost, the longest now planned for (raised in flight by a TPS dip) has no landing from
            // here: it would come down with the rocket still pushing. The burn now, as past the longest
            return startBurn(in, true);
        }
        boolean rocket = false;
        boolean may = !noMoreRockets && !in.boosting() && pendingSince < 0 && rocketsFired < cfg.maxRockets() && in.rockets() > 0
            && in.canRocket() && alongOf(in.x(), in.z(), Double.NaN) < p.length() - 20;
        boolean longBoost = cfg.boostTicks() > boostMin(1);
        if (longBoost) {
            // durations 2 and 3: another rocket only when the look-ahead lands it on the pad with either boost
            if (may && best.score() >= OFF_PAD && best.shortBy() > SHORT_FOR_ROCKET) {
                Choice boosted = rocketLands(now, yaw, pitch);
                if (boosted != null) {
                    rocket = true;
                    best = boosted;
                    lastChoice = best;
                }
            }
        } else if (may && best.score() >= OFF_PAD && best.shortBy() > SHORT_FOR_ROCKET
            && !overshoots(new ElytraPhysics.State(in.x(), in.y(), in.z(), in.vx(), in.vy(), in.vz()), yaw)) {
            rocket = true;
        } else if (may && best.score() < OFF_PAD && best.ticks() > ROCKET_SAVES && ticks % 5 == 0
            && !overshoots(new ElytraPhysics.State(in.x(), in.y(), in.z(), in.vx(), in.vy(), in.vz()), yaw)) {
            // the pad is reached without one, but slowly: a rocket now if it lands as well and saves ROCKET_SAVES ticks
            Choice boosted = choose(new ElytraPhysics.State(in.x(), in.y(), in.z(), in.vx(), in.vy(), in.vz()), yaw,
                cfg.rocketDelay(), cfg.boostTicks(), pitch);
            if (boosted.score() <= best.score() + 5 && best.ticks() - boosted.ticks() >= ROCKET_SAVES) {
                rocket = true;
                best = boosted;
                lastChoice = best;
            }
        }
        // arc flights: the boost over, no rocket that lands on the pad, and no safe touch-down along the way, or the pad
        // out of reach before the turn is flown out (an early end of the boost: the way's turn carries the glide wide
        // into a wall): down off the way. On the final line a touch-down short of the pad stays on the way, as on a
        // straight flight (the look-ahead is short for some ticks after the boost's end, then finds the pad: the
        // simulation 2026-10-25, 22 legs whose boost ended a tick early, all on the pad)
        if (path != null && !rocket && boostIn + boostLeft == 0 && worstLeft == 0 && pendingSince < 0
            && (best.score() >= INVALID || best.score() >= OFF_PAD && best.shortBy() > 0
            && pathAt < path.turnEnd() + LEAVE_PAST_TURN)) {
            best = leaveTheWay(now, in, choose(now, yaw, 0, 0, pitch, true), boostIn, boostLeft);
            lastChoice = best;
        }
        if (rocket) {
            rocketsFired++;
            pendingSince = ticks;
        }
        return steer(best.pitch(), rocket);
    }

    /** This tick's keys toward pitch {@code target} (at most {@link #PITCH_RATE} from the last). */
    private Keys steer(float target, boolean rocket) {
        float next = Math.max(pitch - PITCH_RATE, Math.min(pitch + PITCH_RATE, target));
        return keys(next, false, rocket);
    }

    // ---- a boost past the longest planned for (the burn), the brake, players near the landing

    /**
     * Blocks before the landing (T, or R along the flight) under which a burn spins in place instead of going on: the
     * spin's drift on (about 2.5 blocks from 1.7 b/t) and 2 more, so it circles a block or two before T, where the brake
     * lands from (the simulation 2026-10-25: spun from 8 before T, the brake from 6 away and 2 up came down short).
     */
    static final double SPIN_AT = 4.5;
    /** The spin's turn a tick, degrees (the landing's quarter turn on the ground turns as much). */
    static final float SPIN_RATE = 90f;
    /** Ticks a burn's cruise on is looked ahead for ({@link #cruiseSafe}), and a spin ({@link #spinProbe}). */
    static final int CRUISE_PROBE = 6;
    static final int SPIN_PROBE = 24;
    /** After a burn the brake aims at the pad's line from this far before R at the most ({@link #landingPoint}). */
    static final double LAND_BACK = 12;
    static final double LAND_FRONT = 2;
    /** Horizontal room kept between a landing and any player's feet (blocks), and the height difference that counts. */
    static final double PLAYER_MARGIN = 2.5;
    static final double PLAYER_DY = 3;
    /** Under this height over the landing's floor the touch-down is committed: no more bail-outs for a player or a block. */
    static final double COMMIT_HEIGHT = 1.0;
    /** A player near the landing ends the flight in a bail-out from this many blocks before it (out of the boost). */
    static final double PLAYER_CHECK = 40;

    /**
     * Boost ticks after which the flight burns ({@link #burnKeys}): the longest planned for ({@link #worstNow});
     * duration 1 (several rockets): vanilla's longest and the gate's slack.
     */
    private int burnAt() {
        if (cfg.boostTicks() <= boostMin(1)) return boostMax(1) + GATE_SLACK_TICKS;
        return Math.max(1, worstNow);
    }

    /**
     * A boost past the longest planned for ({@link Phase#BURN}): a landing now would come down with the rocket still
     * pushing, and the slide would run on at 1.5 b/t (live 2026-10-25 08:47: 85 ticks against 54, touch-down at 1.69
     * b/t, the bot slid on toward a no-go zone). While it boosts: on toward the landing at the band's middle
     * ({@link #holdY}) while that is more than {@link #SPIN_AT} away and the next {@link #CRUISE_PROBE} ticks of it touch
     * nothing; else a spin in place ({@link #spinKeys}) until it ends. Then the landing from there ({@link #afterBurn}).
     */
    private Keys burnKeys(In in) {
        if (!players.isEmpty() && dodgePlace == null && in.y() - p.surface > COMMIT_HEIGHT) {
            // a player on the landing while the boost burns: the spin over a place beside it (dodge after it)
            String taken = playerTaken();
            if (taken != null) {
                double[] tg0 = burnTarget();
                dodgePlace = dodgePlaceNear(tg0[0], tg0[1]);
                if (dodgePlace != null) playerWhy = taken;
            }
        }
        ElytraPhysics.State now = state(in);
        if (!in.boosting()) return afterBurn(in, now);
        double[] tg = burnTarget();
        if (!spinning && !silent && Math.hypot(tg[0] - in.x(), tg[1] - in.z()) > SPIN_AT && cruiseSafe(now)) {
            cruiseTicks++;
            yaw = pursuitYaw(in.x(), in.z());
            return steer(hold(now, Double.isNaN(p.bandLow) ? in.y() : holdY()), false);
        }
        return spinKeys(in, now);
    }

    /** The burn ({@link #burnKeys}) from this tick; {@code early}: before the longest boost planned for ({@link #burnsEarly}). */
    private Keys startBurn(In in, boolean early) {
        burnTick = ticks;
        burnEarly = early;
        noMoreRockets = true;
        relay = false;
        to(Phase.BURN);
        return burnKeys(in);
    }

    /**
     * The look-ahead's best way ({@link #chooseBoth}) misses the landing because of the longest boost: it goes wrong
     * with it, or comes down off the pad (or spot), under the boost or past R with it.
     */
    static boolean burnsEarly(Choice best) {
        return best.score() >= WORST_UNSAFE && best.score() < INVALID
            || best.score() >= OFF_PAD && best.score() < NO_TOUCH && (best.miss() & MISS_WORST) != 0;
    }

    /**
     * Where a burn spins ({@link #spinKeys} steers its circle over it) and the brake after it lands: T for a short
     * landing; else the flight line {@link #BURN_PAD_BACK} before R (on the pad, whose cells run 20 back from R).
     */
    private double[] burnTarget() {
        if (dodgePlace != null) return dodgePlace;
        double a = Math.max(0, p.length() - (p.high ? 0 : p.spot ? BURN_SPOT_BACK : BURN_PAD_BACK));
        return new double[]{p.startX + p.dirX * a, p.startZ + p.dirZ * a};
    }

    /**
     * A long pad's burn target lies this far before R ({@link #burnTarget}); a short landing's this far before T along
     * its final line (the spin circles at 0.8 b/t: the brake after it turns that toward T, never on past it into what
     * lies beyond: the simulation 2026-10-25, a spin right over a drop point 2.5 from the sorter wall).
     */
    static final double BURN_PAD_BACK = 6;
    static final double BURN_SPOT_BACK = 2;
    /*
     * Over the end rods ({@link FlightPlanner.Profile#high}) the spin circles over T itself, higher
     * ({@link #HIGH_SPIN_UP}): the clear stand is 2 wide with the tile 6 below it on every side, and the brake from a
     * circle 2 before T came down beside it (the simulation 2026-10-25: slow from the spin, the glide's lift brings the
     * bot back toward T at about 0.015 b/t each tick while it sinks 0.15 a tick).
     */

    /** Blocks from the bot to its landing: to T for a short landing, else along the flight (its way) to R. */
    private double toLanding(In in) {
        if (p.spot) return Math.hypot(p.restX - in.x(), p.restZ - in.z());
        if (path != null && !leftWay) return path.length() - pathAt;
        return p.length() - p.along(in.x(), in.z());
    }

    /** A burn's cruise on under the boost touches down nowhere and into nothing in the next {@link #CRUISE_PROBE} ticks. */
    private boolean cruiseSafe(ElytraPhysics.State now) {
        if (Double.isNaN(p.bandLow) || leftWay) return false;
        ElytraPhysics.State[] st = {now};
        double[] near = {0};
        // the boost on all through: any touch-down counts as one under the boost
        return run(st, pursuitYaw(now.x(), now.z()), 0f, holdY(), 0, 0, CRUISE_PROBE, 0f, Integer.MAX_VALUE / 2, near) == null;
    }

    /**
     * A spin in place under a boost that lasts too long: the look turns {@link #SPIN_RATE} a tick, so the boost's push
     * (half the velocity a tick toward 1.5 times the look) goes round and cancels itself: from 1.6 b/t the bot drifts
     * about 2.5 blocks on, then circles within a block at about 0.8 b/t, the pitch holding its height
     * ({@link #holdPitch}: within the band, at least {@link #SPIN_UP} over the landing's floor where the band allows).
     * Its sense: the one whose next {@link #SPIN_PROBE} ticks touch down nowhere and into nothing (else the one that
     * does so later). In a burn the turn a tick is one of {@link #SPIN_STEPS}: the one whose next {@link #SPIN_LOOK}
     * ticks circle nearest the burn's target ({@link #burnTarget}), so the circle drifts over it and the brake after the
     * boost comes straight down; in a bail-out a plain {@link #SPIN_RATE}.
     */
    private Keys spinKeys(In in, ElytraPhysics.State now) {
        if (!spinning) {
            spinning = true;
            spinYaw = yaw;
            spinY = Double.isNaN(p.bandLow) ? in.y() : Math.max(p.bandLow - 0.5, Math.min(in.y(), Math.max(holdY(), p.bandLow - 0.5)));
            if (!Double.isNaN(p.bandLow) && phase == Phase.BURN) {
                // a burn: up to SPIN_UP over the landing's floor where the roofs near its target allow (the brake after
                // it needs the height to turn the spin's 0.8 b/t back toward T)
                spinY = Math.max(spinY, Math.min(spinRoom(burnTarget()), p.surface + (p.high ? HIGH_SPIN_UP : SPIN_UP)));
            }
            int right = spinProbe(now, 1, spinY);
            int left = spinProbe(now, -1, spinY);
            spinDir = right >= left ? 1 : -1;
        }
        spinTicks++;
        float step = SPIN_RATE;
        if (phase == Phase.BURN) {
            double[] tg = burnTarget();
            double bestD = Double.POSITIVE_INFINITY;
            for (float d : SPIN_STEPS) {
                double dist = spinCentre(now, spinYaw + d * spinDir, spinY, tg);
                if (dist < bestD - 1e-9) {
                    bestD = dist;
                    step = d;
                }
            }
        }
        spinYaw = (float) Math.IEEEremainder(spinYaw + step * spinDir, 360);
        yaw = spinYaw;
        return steer(hold(now, spinY), false);
    }

    /** A burn's spin holds this high over the landing's floor where the roofs allow ({@link #spinRoom}). */
    static final double SPIN_UP = 4.0;
    /** ... over the end rods ({@link FlightPlanner.Profile#high}): open sky, the brake's time to come back over T. */
    static final double HIGH_SPIN_UP = 8.0;
    /** ... looking at the roofs this far around its target (blocks). */
    static final int SPIN_ROOM = 4;

    /**
     * The highest feet a burn's spin may hold around {@code tg}: under every roof within {@link #SPIN_ROOM} by
     * {@link FlightPlanner#ROOF_MARGIN}, under the profile's ceiling, and at least the band's bottom.
     */
    private double spinRoom(double[] tg) {
        double hi = p.ceiling - ElytraPhysics.BOX_HEIGHT - 1;
        int bx = (int) Math.floor(tg[0]);
        int bz = (int) Math.floor(tg[1]);
        for (int x = bx - SPIN_ROOM; x <= bx + SPIN_ROOM; x++) {
            for (int z = bz - SPIN_ROOM; z <= bz + SPIN_ROOM; z++) {
                hi = Math.min(hi, p.roof(x, z) - ElytraPhysics.BOX_HEIGHT - FlightPlanner.ROOF_MARGIN);
            }
        }
        return Math.max(hi, Double.isNaN(p.bandLow) ? hi : p.bandLow);
    }
    /** A burn's spin turns one of these a tick ({@link #spinKeys}), and looks this many ticks ahead for its circle. */
    static final float[] SPIN_STEPS = {60f, 90f, 120f};
    static final int SPIN_LOOK = 8;

    /**
     * The middle of the next {@link #SPIN_LOOK} ticks of a spin whose first look is {@code firstYaw} (then
     * {@link #SPIN_RATE} a tick), height {@code y0} held, from {@code tg}: blocks; infinite when they touch down or into a
     * block.
     */
    private double spinCentre(ElytraPhysics.State s, float firstYaw, double y0, double[] tg) {
        float yw = firstYaw;
        float pt = pitch;
        double sx = 0;
        double sz = 0;
        double[] near = {0};
        for (int t = 1; t <= SPIN_LOOK; t++) {
            pt = Math.max(pt - PITCH_RATE, Math.min(pt + PITCH_RATE, hold(s, y0)));
            ElytraPhysics.State n = ElytraPhysics.fly(s, yw, pt, true);
            if (path != null) n = n.along(path.track(n.x(), n.z(), Double.isNaN(s.along()) ? pathAt : s.along()));
            if (sweep(s, n, t, pt, Integer.MAX_VALUE / 2, true, near) != null) return Double.POSITIVE_INFINITY;
            sx += n.x();
            sz += n.z();
            s = n;
            yw += SPIN_RATE * spinDir;
        }
        return Math.hypot(sx / SPIN_LOOK - tg[0], sz / SPIN_LOOK - tg[1]);
    }

    /** A spin's ({@link #spinKeys}) first look-ahead tick that touches down or into a block; past {@link #SPIN_PROBE}: none. */
    private int spinProbe(ElytraPhysics.State s, int dir, double y0) {
        float yw = yaw;
        float pt = pitch;
        double[] near = {0};
        for (int t = 1; t <= SPIN_PROBE; t++) {
            yw += SPIN_RATE * dir;
            pt = Math.max(pt - PITCH_RATE, Math.min(pt + PITCH_RATE, hold(s, y0)));
            ElytraPhysics.State n = ElytraPhysics.fly(s, yw, pt, true);
            if (path != null) n = n.along(path.track(n.x(), n.z(), Double.isNaN(s.along()) ? pathAt : s.along()));
            if (sweep(s, n, t, pt, Integer.MAX_VALUE / 2, true, near) != null) return t;
            s = n;
        }
        return SPIN_PROBE + 1;
    }

    /**
     * The boost ended after a burn: the landing from here, no more rockets. The plan's own when the look-ahead finds it
     * (on the pad or the spot: the flight goes on as planned); else the brake ({@link FlightBrake}) toward the landing's
     * nearest point ({@link #landingPoint}) when its way comes to rest on the pad or the spot; else a bail-out (the
     * soonest, slowest safe touch-down: a brake off the spot came down beside a walkway and slid into its side).
     */
    private Keys afterBurn(In in, ElytraPhysics.State now) {
        spinning = false;
        if (!players.isEmpty() && mayDodge(in)) {
            // a player on the landing: the brake onto a place beside it (or a bail-out away from them), not onto it
            String taken = playerTaken();
            if (taken != null) return dodge(taken, in);
        }
        float look = yaw;
        yaw = pursuitYaw(in.x(), in.z());
        double[] t = landingPoint(in.x(), in.z());
        boolean near = Math.hypot(t[0] - in.x(), t[1] - in.z()) <= BRAKE_FAR + FlightBrake.trigger(in.hSpeed());
        double bx = brakeX;
        double bz = brakeZ;
        brakeX = t[0];
        brakeZ = t[1];
        if (p.spot && near && predict(now.braked(look), look, 0f, 0, 0).score() < OFF_PAD) {
            // over T after the spin: the brake straight down first
            yaw = look;
            startBrake(t[0], t[1]);
            return brakeKeys(in);
        }
        Choice c = choose(now, yaw, 0, 0, pitch);
        if (c.score() < OFF_PAD) {
            brakeX = bx;
            brakeZ = bz;
            to(Phase.FLY);
            lastChoice = c;
            return steer(c.pitch(), false);
        }
        // the brake toward the landing's point: onto the pad, or a slow touch-down beside it or the spot (its slide is
        // a block at most); a bail-out's soonest touch-down may lie anywhere
        if (near && (!p.spot && predict(now.braked(look), look, 0f, 0, 0).score() < OFF_PAD
            || slowTouch(predict(now.braked(look), look, 0f, 0, 0, true, Double.NaN)))) {
            yaw = look;
            startBrake(t[0], t[1]);
            return brakeKeys(in);
        }
        brakeX = bx;
        brakeZ = bz;
        return bail("the boost ran " + boostTicksSeen + " ticks, " + worstNow + " planned for; no landing from there", in);
    }

    /**
     * A burn's brake looks at landings this much nearer than its trigger at the speed ({@link FlightBrake#trigger}) at
     * the most (blocks): further, the glide's own or a bail-out.
     */
    static final double BRAKE_FAR = 8;

    /**
     * The look-ahead's touch-downs sink under this (b/t): the sweeps' limit is 0.5; the margin covers the brake's own
     * turn, which the look-ahead flies at a fixed yaw (FlightLongHopTest: a hop's brake after a dive came down at
     * 0.503 where the look-ahead had seen under 0.5).
     */
    static final double SINK_LIMIT = 0.46;

    /** A brake's touch-down scored as a bail-out's ({@link #sweep}, {@code down}): at most {@link #SPOT_MAX_SPEED}, sinking under {@link #SINK_LIMIT}, no boost, no roof near. */
    private static boolean slowTouch(Choice down) {
        return down.score() < OFF_PAD && down.score() - 0.01 * down.ticks() <= 5 * SPOT_MAX_SPEED + 1e-9;
    }

    /**
     * The landing's point nearest (x, z): T for a short landing; else on the pad's line from {@link #LAND_BACK} before R
     * to {@link #LAND_FRONT} before it.
     */
    private double[] landingPoint(double x, double z) {
        if (p.spot) return new double[]{p.restX, p.restZ};
        double a = Math.max(p.length() - LAND_BACK, Math.min(p.length() - LAND_FRONT, p.along(x, z)));
        return new double[]{p.startX + p.dirX * a, p.startZ + p.dirZ * a};
    }

    /**
     * A player took the landing (within {@link #PLAYER_MARGIN} of the spot or the pad's rest point) above the commit:
     * the brake onto a place beside it instead, {@link #PLAYER_MARGIN} + {@link #DODGE_OFF} from the player in one of 8
     * directions around them (the places nearest the bot's own stop first), when the look-ahead's slow touch-down there
     * passes (no block, no player, no step at speed: as after a burn); else a bail-out away from them (live check
     * 2026-10-25: bail-outs at 1.6 b/t over the built art touched blocks at speed; a slow brake beside the teammate
     * does not). Players move: the place is checked again each tick.
     */
    private Keys dodge(String taken, In in) {
        playerWhy = taken;
        ElytraPhysics.State now = state(in);
        double h = in.hSpeed();
        double sx = in.x() + (h > 0.05 ? in.vx() / h * 4 * h : 0);
        double sz = in.z() + (h > 0.05 ? in.vz() / h * 4 * h : 0);
        java.util.List<double[]> places = new java.util.ArrayList<>();
        for (double[] f : players) {
            if (Math.abs(f[1] - p.surface) > PLAYER_DY) continue;
            for (int k = 0; k < 8; k++) {
                double a = Math.PI / 4 * k;
                double x = f[0] + Math.cos(a) * (PLAYER_MARGIN + DODGE_OFF);
                double z = f[2] + Math.sin(a) * (PLAYER_MARGIN + DODGE_OFF);
                if (!playerClose(x, p.surface, z, 0, 0, 0)) places.add(new double[]{x, z, Math.hypot(x - sx, z - sz)});
            }
        }
        places.sort(java.util.Comparator.comparingDouble(q -> q[2]));
        double bx = brakeX;
        double bz = brakeZ;
        float look = yaw;
        for (double[] q : places) {
            brakeX = q[0];
            brakeZ = q[1];
            Choice c = predict(now.braked(look), look, 0f, 0, 0, true, Double.NaN);
            // a slow touch-down, or one at the brake's drift toward a place still far (0.4 b/t: the slide is a block
            // at most, it meets no block, sinking under 0.5)
            if (c.score() < OFF_PAD && c.score() - 0.01 * c.ticks() <= 5 * DODGE_SPEED + 1e-9) {
                dodging = true;
                startBrake(q[0], q[1]);
                return brakeKeys(in);
            }
        }
        brakeX = bx;
        brakeZ = bz;
        return bail(taken, in);
    }

    /**
     * Whether a landing a player took is still given up ({@link #dodge}): over {@link #COMMIT_HEIGHT}, and when fast
     * (over {@link #DODGE_SPEED} across) over {@link #FAST_COMMIT_HEIGHT}: lower, a dodge or bail-out at full speed
     * comes down among blocks it cannot check in time (simulation 2026-10-25: a teammate who stepped onto the walkway's
     * spot with the bot 1.3-1.7 over it at 1.5 b/t; the bail-out touched a block at speed), and the planned landing is the
     * safer one: a player is no block (no damage, the server pushes the two apart).
     */
    private boolean mayDodge(In in) {
        if (playerCommit) return false;
        double up = in.y() - p.surface;
        double h = in.hSpeed();
        if (up <= COMMIT_HEIGHT
            || h > DODGE_SPEED && up <= FAST_COMMIT_HEIGHT && toLanding(in) <= FlightBrake.trigger(h) + FAST_COMMIT_NEAR) {
            playerCommit = true; // from here the planned landing, players or not (the look-ahead no longer avoids them)
            return false;
        }
        return true;
    }

    /**
     * {@link #mayDodge}: a fast approach is committed this low over the landing's floor (blocks) once the landing lies
     * within the brake's trigger distance and this many blocks more.
     */
    static final double FAST_COMMIT_HEIGHT = 2.0;
    static final double FAST_COMMIT_NEAR = 4.0;
    /** The landing is committed against players ({@link #mayDodge}): the look-ahead no longer avoids their cells. */
    private boolean playerCommit;

    /** {@link #dodge}: past the player's margin by this much (blocks); whether the brake is a dodge's. */
    static final double DODGE_OFF = 1.0;
    /** {@link #dodge}: the fastest touch-down across it takes (the brake's drift is at most {@link FlightBrake#V_MAX}). */
    static final double DODGE_SPEED = 0.5;
    private boolean dodging;
    /**
     * A landing a player took while the boost burns: the place beside it the burn spins over instead ({@link #burnTarget}),
     * so the brake after it starts slow over that place (live check 2026-10-25: a burn on toward a taken spot left the
     * bot 16 blocks short at 1.65 b/t, too fast for a slow touch-down beside it); null: none.
     */
    private double @Nullable [] dodgePlace;

    /**
     * The place beside a landing players took nearest (tx, tz): {@link #PLAYER_MARGIN} + {@link #DODGE_OFF} from a player
     * in one of 8 directions, clear of every player, flat around ({@link #flatAround}); null: none.
     */
    /**
     * Every column within {@link #DODGE_CLEAR} of (x, z) known to the look-ahead and at the landing's floor height (within
     * {@link FlightPlanner#SPOT_LEVEL_EPS}): a spin there at about 1 b/t, ending in any direction, meets no wall and no
     * step before its brake has stopped it (simulation 2026-10-25: a spin moved beside the sorter wall touched it).
     */
    private boolean flatAround(double x, double z) {
        int r = (int) Math.ceil(DODGE_CLEAR);
        int cx = (int) Math.floor(x);
        int cz = (int) Math.floor(z);
        for (int ix = cx - r; ix <= cx + r; ix++) {
            for (int iz = cz - r; iz <= cz + r; iz++) {
                if (cellDist(x, z, ix, iz) > DODGE_CLEAR) continue;
                double top = p.top(ix, iz);
                if (Double.isNaN(top) || Math.abs(top - p.surface) > FlightPlanner.SPOT_LEVEL_EPS + 1e-9) return false;
            }
        }
        return true;
    }

    /** {@link #flatAround}: the room a spin moved beside a taken landing needs. */
    static final double DODGE_CLEAR = 3.5;

    private double @Nullable [] dodgePlaceNear(double tx, double tz) {
        double[] best = null;
        double bestD = Double.MAX_VALUE;
        for (double[] f : players) {
            if (Math.abs(f[1] - p.surface) > PLAYER_DY) continue;
            for (int k = 0; k < 8; k++) {
                double a = Math.PI / 4 * k;
                double x = f[0] + Math.cos(a) * (PLAYER_MARGIN + DODGE_OFF);
                double z = f[2] + Math.sin(a) * (PLAYER_MARGIN + DODGE_OFF);
                if (playerClose(x, p.surface, z, 0, 0, 0)) continue;
                if (!flatAround(x, z)) continue;
                double d = Math.hypot(x - tx, z - tz);
                if (d < bestD) {
                    bestD = d;
                    best = new double[]{x, z};
                }
            }
        }
        return best;
    }

    private void startBrake(double tx, double tz) {
        brakeX = tx;
        brakeZ = tz;
        brakeTick = ticks;
        to(Phase.BRAKE);
    }

    /**
     * The brake ({@link Phase#BRAKE}): the look by {@link FlightBrake} toward its target, the pitch at most
     * {@link #PITCH_RATE} a tick from the last. Each tick, above {@link #COMMIT_HEIGHT}: a player near the landing, or a
     * brake whose way on to the touch-down runs into a block, ends it in a bail-out (a touch-down away from players).
     */
    private Keys brakeKeys(In in) {
        boolean free = in.y() - p.surface > COMMIT_HEIGHT;
        if (free) {
            String taken = playerTaken();
            if (dodging) {
                // the brake onto a place beside a landing a player took: that place itself must stay clear
                if (playerClose(brakeX, p.surface, brakeZ, 0, 0, 0)) {
                    return bail("a player near the place beside the landing too", in);
                }
            } else if (taken != null && mayDodge(in)) {
                return dodge(taken, in);
            }
        }
        Choice c = predict(state(in).braked(yaw), yaw, 0f, 0, 0);
        lastChoice = c;
        if (free && c.score() >= INVALID) return bail("the brake's way on to the touch-down runs into a block", in);
        yaw = FlightBrake.yaw(in.x(), in.z(), in.vx(), in.vz(), brakeX, brakeZ, yaw);
        return steer(FlightBrake.pitch(in.x(), in.y(), in.z(), in.vx(), in.vz(), brakeX, brakeZ, p.surface), false);
    }

    /** Cells within {@link #PLAYER_MARGIN} of a player's feet: no touch-down there ({@link #sweep}); from {@link #players}. */
    private final java.util.Set<Long> blocked = new java.util.HashSet<>();

    /**
     * Why the landing is taken by a player (one within {@link #PLAYER_MARGIN} of a cell of the spot, or of a long pad,
     * within {@link #PLAYER_DY} of its floor), or null. Updates {@link #blocked}.
     */
    private @Nullable String playerTaken() {
        blocked.clear();
        String why = null;
        int r = (int) Math.ceil(PLAYER_MARGIN);
        for (double[] f : players) {
            for (int x = (int) Math.floor(f[0]) - r; x <= (int) Math.floor(f[0]) + r; x++) {
                for (int z = (int) Math.floor(f[2]) - r; z <= (int) Math.floor(f[2]) + r; z++) {
                    if (cellDist(f[0], f[2], x, z) < PLAYER_MARGIN) blocked.add(FlightPlanner.Profile.key(x, z));
                }
            }
            if (why != null || Math.abs(f[1] - p.surface) > PLAYER_DY) continue;
            for (long k : p.pad) {
                if (cellDist(f[0], f[2], (int) (k >> 32), (int) k) < PLAYER_MARGIN) {
                    why = String.format("a player %.1f blocks from the %s", Math.hypot(f[0] - p.restX, f[2] - p.restZ),
                        p.spot ? "spot" : "pad's rest point");
                    break;
                }
            }
        }
        return why;
    }

    /**
     * A player within {@link #PLAYER_MARGIN} (and {@link #PLAYER_DY} up or down) of a touch-down at (x, y, z) or of its
     * slide's end, {@code slide} blocks on along (dx, dz).
     */
    private boolean playerClose(double x, double y, double z, double dx, double dz, double slide) {
        double ex = x + dx * slide;
        double ez = z + dz * slide;
        for (double[] f : players) {
            if (Math.abs(f[1] - y) > PLAYER_DY) continue;
            if (Math.hypot(f[0] - x, f[2] - z) < PLAYER_MARGIN || Math.hypot(f[0] - ex, f[2] - ez) < PLAYER_MARGIN) return true;
        }
        return false;
    }

    /** Distance from point (px, pz) to the square of cell (x, z). */
    static double cellDist(double px, double pz, int x, int z) {
        double dx = Math.max(0, Math.max(x - px, px - (x + 1)));
        double dz = Math.max(0, Math.max(z - pz, pz - (z + 1)));
        return Math.hypot(dx, dz);
    }

    /**
     * A relay flight's tick ({@link #relay}) until its second rocket goes: while the first boosts, the cruise at the
     * band's middle; after it, a level glide (pitch 0) until the look-ahead lands a second rocket on the pad with either
     * boost, then that rocket. Null when the relay gives up (the cruise would run into a block, the glide went on
     * {@link #RELAY_GLIDE_MAX} ticks or would): the flight goes on as a one-rocket one.
     */
    private @Nullable Keys relayKeys(In in, ElytraPhysics.State now, int boostIn, int boostLeft, int worstLeft) {
        if (in.boosting() || pendingSince >= 0) {
            double hy = holdY();
            Choice h = Double.isNaN(hy) ? null
                : both(now, yaw, 0f, hy, boostIn, boostLeft, Math.max(boostLeft, worstLeft), Double.POSITIVE_INFINITY);
            if (h == null || h.score() >= WORST_UNSAFE) {
                relay = false;
                return null;
            }
            lastChoice = h;
            return steer(h.pitch(), false);
        }
        relayGlide++;
        if (rocketsFired < cfg.maxRockets() && in.rockets() > 0 && in.canRocket()) {
            Choice r = rocketLands(now, yaw, pitch);
            if (r != null) {
                rocketsFired++;
                pendingSince = ticks;
                relay = false;
                lastChoice = r;
                return steer(r.pitch(), true);
            }
        }
        Choice g = predict(now, yaw, 0f, 0, 0);
        if (relayGlide > RELAY_GLIDE_MAX || g.score() >= INVALID || g.ticks() <= 3) {
            relay = false;
            return null;
        }
        lastChoice = g;
        return steer(0f, false);
    }

    /** Ticks a relay flight glides after its first boost waiting for the second rocket's landing ({@link #relayKeys}). */
    static final int RELAY_GLIDE_MAX = 20;

    /**
     * Durations 2 and 3, a rocket in the air: the look-ahead's choice when it lands on the pad with either boost, its boost
     * starting after {@link Cfg#rocketDelay} or {@link #LATE_ROCKET} ticks later (a slower server's round trip: the
     * simulation 2026-10-25 put a relay's second rocket 6.8 past R with a delay of 8); else null.
     */
    @Nullable Choice rocketLands(ElytraPhysics.State now, float yaw, float current) {
        Choice r = chooseBoth(now, yaw, cfg.rocketDelay(), cfg.boostTicks(), worstNow, current);
        if (r.score() >= OFF_PAD) return null;
        Choice late = chooseBoth(now, yaw, cfg.rocketDelay() + LATE_ROCKET, cfg.boostTicks(), worstNow, current);
        return late.score() < OFF_PAD ? r : null;
    }

    /** Ticks a rocket's boost may start later than {@link Cfg#rocketDelay} ({@link #rocketLands}). */
    static final int LATE_ROCKET = 4;

    /**
     * Durations 2 and 3, a leg too long for one rocket's glide ({@link FlightLeg#withLanding}): two rockets, the second
     * where the look-ahead lands it on the pad with either boost ({@link #relayKeys}). Needs {@code maxRockets} 2.
     */
    FlightControl relay() {
        relay = true;
        return this;
    }

    private boolean relay;
    private int relayGlide;

    /**
     * {@link #relay}'s pre-flight check: the cruise at the band's middle through the first boost (from
     * {@link Cfg#boostTicks} to {@link Cfg#boostWorst}), then up to {@link #RELAY_GLIDE_MAX} ticks of level glide, and
     * at some even tick of it a second rocket the look-ahead lands on the pad with either boost.
     */
    static boolean relayLands(FlightPlanner.Profile p, Cfg cfg, double x, double y, double z) {
        if (cfg.maxRockets() < 2 || Double.isNaN(p.bandLow)) return false;
        FlightControl fc = new FlightControl(p, cfg, 0f);
        float yaw = fc.yaw;
        double hy = fc.holdY();
        ElytraPhysics.State s = new ElytraPhysics.State(x, y + TAKEOFF_UP, z, 0, 0.16, 0);
        for (int i = 0; i < 2; i++) s = ElytraPhysics.fly(s, yaw, 0f, true);
        for (int len : new int[]{Math.max(1, cfg.boostTicks() - 2), Math.max(1, cfg.boostWorst() - 2)}) {
            ElytraPhysics.State[] st = {s};
            double[] near = {0};
            if (fc.run(st, yaw, 0f, hy, 0, 0, len, 0f, len, near) != null) return false;
            boolean ok = false;
            for (int k = 0; k <= RELAY_GLIDE_MAX && !ok; k++) {
                if (k > 0 && fc.run(st, yaw, 0f, Double.NaN, Integer.MAX_VALUE, len + k - 1, len + k, 0f, 0, near) != null) {
                    break;
                }
                if (k % 2 == 0) ok = fc.rocketLands(st[0], yaw, 0f) != null;
            }
            if (!ok) return false;
        }
        return true;
    }

    /** Score of a prediction that touches down off the pad; one that never touches down; one that hits or leaves. */
    static final double OFF_PAD = 1000;
    static final double NO_TOUCH = 2000;
    static final double INVALID = 1e9;
    /**
     * A rocket now would, with the longest boost it may give ({@link Cfg#boostMax}), carry the bot past the pad (the
     * best pitch comes to rest more than {@link #REST_PAST_MAX} past R, or touches down beyond the pad) or land it too
     * fast ({@link Choice#hard}). Then no rocket goes (the flight lands short, the walk goes on), so a long-duration
     * rocket never overshoots. Durations 2 and 3 only: duration 1 flies as since 5.25.
     */
    boolean overshoots(ElytraPhysics.State s, float yaw) {
        // duration 1 as tested since 5.25 (its 11 ticks of spread the controller absorbs tick by tick)
        if (cfg.boostTicks() <= boostMin(1) || cfg.boostMax() <= cfg.boostTicks()) return false;
        Choice worst = choose(s, yaw, cfg.rocketDelay(), cfg.boostWorst(), pitch);
        if (worst.score() >= OFF_PAD) return worst.shortBy() < 0; // off the pad or no touch-down: unsafe only past R
        return worst.shortBy() < -REST_PAST_MAX || worst.hard();
    }

    /** Blocks past R that a rocket's worst case may come to rest at ({@link #overshoots}). */
    static final double REST_PAST_MAX = 4;
    /**
     * Blocks past R the look-ahead lets a rest point lie, durations 2 and 3 ({@link #REST_PAST_MAX} less a block for
     * the slide's spread); past it the landing counts as off the pad.
     */
    static final double REST_AIM = 3;

    /**
     * Bot ticks the gate adds to vanilla's longest boost: the bot counts its own ticks while the rocket exists, and a
     * server ticking slower than the bot stretches the boost (local 10x 2026-10-24: duration-3 boosts of 49-66 bot
     * ticks against vanilla's 40-51; a server below 20 TPS live does the same).
     */
    static final int GATE_SLACK_TICKS = 15;

    /** Blocks short of R (no pitch reaching the pad) that take another rocket. */
    static final double SHORT_FOR_ROCKET = 8;
    /** Ticks to the touch-down a rocket must save to go when the pad is reached without it (1 s). */
    static final int ROCKET_SAVES = 20;
    /**
     * Score per tick to the touch-down: a block of the walk on from the rest point costs about 2 ticks (10 blocks a
     * second), so a block short of R weighs as much as 2 ticks of flight.
     */
    static final double TIME_WEIGHT = 0.5;
    /** Score added to a touch-down while a rocket still boosts (it pushes the slide on until the glide ends). */
    static final double BOOSTED = 30;

    /**
     * The look-ahead's verdict: the pitch, its score, how far short of R its rest point lies (negative: past R);
     * {@code hard}: its touch-down is too fast (over {@code maxLandSpeed}, sinking over {@link #SINK_LIMIT}, or still boosted);
     * {@code miss}: why a touch-down scores as off the pad ({@link #MISS_OFF_PAD} and the others, with
     * {@link #MISS_WORST} when the longest boost's way decided it), 0 when it does not.
     */
    record Choice(float pitch, double score, double shortBy, int ticks, boolean hard, int miss) {
        Choice(float pitch, double score, double shortBy, int ticks) {
            this(pitch, score, shortBy, ticks, false, 0);
        }

        Choice(float pitch, double score, double shortBy, int ticks, boolean hard) {
            this(pitch, score, shortBy, ticks, hard, 0);
        }
    }

    /** {@link Choice#miss}: down off the pad. */
    static final int MISS_OFF_PAD = 1;
    /** {@link Choice#miss}: durations 2 and 3, down while the rocket still boosts. */
    static final int MISS_BOOSTED = 2;
    /** {@link Choice#miss}: durations 2 and 3, at rest over {@link #REST_AIM} past R. */
    static final int MISS_PAST = 3;
    /** {@link Choice#miss}: durations 2 and 3, the way comes near a roof. */
    static final int MISS_NEAR_ROOF = 4;
    /** {@link Choice#miss}: a short landing, down faster than {@link #SPOT_MAX_SPEED}. */
    static final int MISS_FAST = 5;
    /** {@link Choice#miss}: a short landing, at rest with the box not within the spot's cells. */
    static final int MISS_OFF_SPOT = 6;
    /** {@link Choice#miss} flag: the longest boost's way, not the shortest's. */
    static final int MISS_WORST = 8;

    /** {@link Choice#miss} in words, or null when it is 0. */
    static @Nullable String missWords(int miss) {
        String what = switch (miss & 7) {
            case MISS_OFF_PAD -> "down off the pad";
            case MISS_BOOSTED -> "down while the rocket boosts";
            case MISS_PAST -> "at rest over " + (int) REST_AIM + " past R";
            case MISS_OFF_SPOT -> "at rest off the spot";
            case MISS_NEAR_ROOF -> "near a roof";
            case MISS_FAST -> "down on the spot too fast";
            default -> null;
        };
        if (what == null) return null;
        return (miss & MISS_WORST) != 0 ? "with the longest boost " + what : what;
    }

    /** The best candidate pitch held from now on (see the class comment); ties go to the pitch nearest {@code current}. */
    Choice choose(ElytraPhysics.State s, float yaw, int boostIn, int boostLeft, float current) {
        return choose(s, yaw, boostIn, boostLeft, current, false);
    }

    /**
     * {@link #choose}; {@code down}: a bail-out's choice (see {@link #predict(ElytraPhysics.State, float, float, int,
     * int, boolean, double)}). When every pitch leads into a block or out of the corridor: {@link #DESCENT_PITCH}, down onto
     * the floor below.
     */
    Choice choose(ElytraPhysics.State s, float yaw, int boostIn, int boostLeft, float current, boolean down) {
        float rampWas = rampFrom;
        rampFrom = current;
        try {
            return chooseFrom(s, yaw, boostIn, boostLeft, current, down);
        } finally {
            rampFrom = rampWas;
        }
    }

    private Choice chooseFrom(ElytraPhysics.State s, float yaw, int boostIn, int boostLeft, float current, boolean down) {
        Choice best = null;
        // the candidates: a pitch held from now on; with a boost on or coming, also: level at the band's middle while it
        // boosts, then a pitch held
        double hold = !down && boostIn + boostLeft > 0 && !Double.isNaN(p.bandLow) ? holdY() : Double.NaN;
        for (int pass = 0; pass < (Double.isNaN(hold) ? 1 : 2); pass++) {
            for (float c = PITCH_MIN; c <= PITCH_MAX + 1e-3; c += PITCH_STEP) {
                Choice ch = predict(s, yaw, c, boostIn, boostLeft, down, pass == 0 ? Double.NaN : hold);
                if (best == null || ch.score() < best.score() - 1e-9 || Math.abs(ch.score() - best.score()) <= 1e-9
                    && Math.abs(ch.pitch() - current) < Math.abs(best.pitch() - current)) {
                    best = ch;
                }
            }
        }
        if (best.score() >= INVALID) return new Choice(DESCENT_PITCH, INVALID, best.shortBy(), 0);
        return best;
    }

    /**
     * {@link #choose} for a boost of unknown length (durations 2 and 3: duration-3 boosts of
     * 49-66 bot ticks at 10x). A candidate is what the bot does while the rocket boosts: a pitch held, or a height held
     * ({@link #holdHeights}). The bot sees the boost end and chooses again, so the look-ahead does too ({@link #both}):
     * with the shortest boost left ({@code boostLeft}) the best glide from where it ends; with the longest
     * ({@code worstLeft}), when the shortest has passed, the best of a few ways on under the boost
     * ({@link #EXTENSION}), then the best glide. The candidate scores the worse of the two, so it lands on the pad
     * whenever the rocket burns out. One safe only with the shortest boost scores {@link #WORST_UNSAFE} over that: kept
     * over the dive (local 10x 2026-10-25: a worst case that went wrong with every pitch dived the bot into the built
     * art, fly_into_wall). None safe even then: {@link #DESCENT_PITCH}, as {@link #choose}.
     */
    Choice chooseBoth(ElytraPhysics.State s, float yaw, int boostIn, int boostLeft, int worstLeft, float current) {
        float rampWas = rampFrom;
        rampFrom = current;
        try {
            return chooseBothFrom(s, yaw, boostIn, boostLeft, worstLeft, current);
        } finally {
            rampFrom = rampWas;
        }
    }

    private Choice chooseBothFrom(ElytraPhysics.State s, float yaw, int boostIn, int boostLeft, int worstLeft,
                                  float current) {
        Choice best = null;
        double[] holds = holdHeights();
        int fixed = Math.round((PITCH_MAX - PITCH_MIN) / PITCH_STEP) + 1;
        for (int i = 0; i < fixed + holds.length; i++) {
            float c = i < fixed ? PITCH_MIN + i * PITCH_STEP : 0f;
            double hold = i < fixed ? Double.NaN : holds[i - fixed];
            Choice ch = both(s, yaw, c, hold, boostIn, boostLeft, worstLeft,
                best == null ? Double.POSITIVE_INFINITY : best.score());
            if (ch == null) continue;
            if (best == null || ch.score() < best.score() - 1e-9 || Math.abs(ch.score() - best.score()) <= 1e-9
                && Math.abs(ch.pitch() - current) < Math.abs(best.pitch() - current)) {
                best = ch;
            }
        }
        if (best.score() >= INVALID) return new Choice(DESCENT_PITCH, INVALID, best.shortBy(), 0);
        return best;
    }

    /**
     * The look-ahead's verdict before the take-off (durations 2 and 3): {@link #chooseBoth} two ticks into the boost of
     * a take-off from feet ({@code x}, {@code y}, {@code z}), {@link #TAKEOFF_UP} up, with boosts from
     * {@link Cfg#boostTicks} to {@link Cfg#boostWorst}. A score of {@link #OFF_PAD} or more: no way found onto the pad
     * whenever the rocket burns out, so the leg walks (local 10x 2026-10-25: a flight that took off so came down 30
     * blocks short in the built art, fly_into_wall).
     */
    static Choice preflight(FlightPlanner.Profile p, Cfg cfg, double x, double y, double z) {
        FlightControl fc = new FlightControl(p, cfg, 0f);
        float yaw = fc.yaw;
        ElytraPhysics.State s = new ElytraPhysics.State(x, y + TAKEOFF_UP, z, 0, 0.16, 0, 0);
        for (int i = 0; i < 2; i++) s = fc.next(s, yaw, 0f, true);
        if (fc.path != null) yaw = fc.arcYaw(s.x(), s.z(), s.along());
        return fc.chooseBoth(s, yaw, 0, Math.max(1, cfg.boostTicks() - 2), Math.max(1, cfg.boostWorst() - 2), 0f);
    }

    /** Height over the take-off's feet where the first rocket starts to boost (local 10x traces: 1.2-1.5). */
    static final double TAKEOFF_UP = 1.3;

    /**
     * One simulated flight ({@link #simulate}): whether it came down on the pad or spot (the look-ahead's own verdict on
     * the touch-down: on its cells, at most {@link #SPOT_MAX_SPEED}, the slide's rest within the spot, no boost on), why
     * not, the touch-down's speeds across and down, its distance from R (T), its tick and the spin's ticks.
     */
    record Sim(boolean landed, @Nullable String why, double touchH, double touchVy, double offT, int ticks, int spinTicks) { }

    /**
     * The controller itself flown in its own physics ({@link ElytraPhysics}, the profile as the world), as FlightSim's Bot
     * flies it: from the feet ({@code x}, {@code y}, {@code z}) on the take-off's floor the jump, the glide's start, the
     * rocket, its boost {@code delay} ticks after the use for {@code boostLen} ticks (the bot sees a tick late that it
     * boosts and that it ended: the server's rocket), each tick's keys from {@link #tick}, until the touch-down the
     * look-ahead's sweep finds. A move into a block or out of the profile, a touch-down before the boost, a bail-out or an
     * abort fails it.
     */
    static Sim simulate(FlightPlanner.Profile p, Cfg cfg, double x, double y, double z, int boostLen, int delay) {
        return simulate(p, cfg, x, y, z, boostLen, delay, false);
    }

    /** {@link #simulate}; {@code bailOk}: a bail-out flies on to its touch-down (it fails only into a block). */
    static Sim simulate(FlightPlanner.Profile p, Cfg cfg, double x, double y, double z, int boostLen, int delay,
                        boolean bailOk) {
        FlightControl fc = new FlightControl(p, cfg, 0f);
        ElytraPhysics.State s = new ElytraPhysics.State(x, y, z, 0, 0, 0);
        boolean ground = true;
        boolean flying = false;
        boolean wasJump = false;
        int rockets = 1;
        int tick = 0;
        int boostFrom = Integer.MAX_VALUE;
        int boostTo = Integer.MAX_VALUE;
        double[] near = {0};
        for (int i = 0; i < cfg.maxTicks(); i++) {
            boolean seen = flying && tick >= boostFrom && tick < boostTo;
            Keys k = fc.tick(new In(s.x(), s.y(), s.z(), s.vx(), s.vy(), s.vz(), ground, flying, seen, rockets, fc.pitch,
                rockets > 0));
            double off = Math.hypot(s.x() - p.restX, s.z() - p.restZ);
            if (k.abort() != null) return new Sim(false, "ended: " + k.abort(), 0, 0, off, i, fc.spinTicks);
            if (fc.phase == Phase.BAIL && !bailOk) return new Sim(false, "bailed out: " + fc.bailWhy, 0, 0, off, i, fc.spinTicks);
            if (k.done()) return new Sim(false, fc.bailWhy == null ? "down" : "bailed out: " + fc.bailWhy, fc.touchSpeed(),
                fc.touchVy(), off, i, fc.spinTicks);
            tick++;
            if (k.rocket() && flying && rockets > 0) {
                rockets--;
                boostFrom = tick + delay;
                boostTo = boostFrom + boostLen;
            }
            if (k.jump() && !wasJump && !ground && !flying) flying = true;
            if (k.jump() && ground && !flying) s = new ElytraPhysics.State(s.x(), s.y(), s.z(), 0, ElytraPhysics.JUMP, 0);
            wasJump = k.jump();
            if (!flying) {
                // the jump off the take-off's floor
                ElytraPhysics.State n = ElytraPhysics.fall(s);
                ground = n.y() <= y;
                s = ground ? new ElytraPhysics.State(n.x(), y, n.z(), 0, 0, 0) : n;
                continue;
            }
            ground = false;
            boolean boost = tick >= boostFrom && tick < boostTo;
            ElytraPhysics.State n = ElytraPhysics.fly(s, k.yaw(), k.pitch(), boost);
            if (fc.phase == Phase.BRAKE) n = n.braked(k.yaw());
            int left = boost ? boostTo - tick : tick < boostFrom ? Integer.MAX_VALUE / 4 : 0;
            Choice v = fc.sweep(s, n, 1, k.pitch(), 1 + Math.max(0, left - 1), false, near);
            if (v != null) {
                off = Math.hypot(n.x() - p.restX, n.z() - p.restZ);
                if (v.score() >= INVALID) {
                    return new Sim(false, String.format("into a block at %.1f,%.1f,%.1f", n.x(), n.y(), n.z()), n.hSpeed(),
                        n.vy(), off, i + 1, fc.spinTicks);
                }
                if (tick < boostFrom) return new Sim(false, "down before the boost", n.hSpeed(), n.vy(), off, i + 1, 0);
                boolean on = v.score() < OFF_PAD;
                String miss = missWords(v.miss());
                return new Sim(on, on ? null : miss == null ? "off the spot" : miss, n.hSpeed(), n.vy(), off, i + 1,
                    fc.spinTicks);
            }
            s = n;
        }
        return new Sim(false, "still in the air after " + cfg.maxTicks() + " ticks", 0, 0, 0, cfg.maxTicks(), fc.spinTicks);
    }

    /**
     * A hover's pre-flight check ({@link FlightPlanner.Profile#hover}): {@link #simulate} with boosts from
     * {@link Cfg#boostTicks} to {@link Cfg#boostWorst} ({@link #HOVER_STEP} apart, the worst too), each with the rocket's
     * boost {@link #HOVER_DELAYS} ticks after its use (round trips: the spin's phase at the boost's end); each must come
     * down on the spot. A boost longer still spins on over T ({@link #burnKeys}). Its verdict: null why when every one
     * lands, and the worst touch-down's numbers.
     */
    record HoverCheck(@Nullable String why, double touchH, double sink, double offT, int ticks) { }

    static HoverCheck hoverPreflight(FlightPlanner.Profile p, Cfg cfg, double x, double y, double z) {
        double h = 0;
        double sink = 0;
        double off = 0;
        int ticks = 0;
        for (int delay : HOVER_DELAYS) {
            for (int len = cfg.boostTicks(); ; len = Math.min(cfg.boostWorst(), len + HOVER_STEP)) {
                Sim s = simulate(p, cfg, x, y, z, len, delay);
                if (!s.landed()) {
                    return new HoverCheck(String.format("a hover with a boost of %d ticks (%d after the rocket): %s", len, delay,
                        s.why()), s.touchH(), -s.touchVy(), s.offT(), s.ticks());
                }
                h = Math.max(h, s.touchH());
                sink = Math.max(sink, -s.touchVy());
                off = Math.max(off, s.offT());
                ticks = Math.max(ticks, s.ticks());
                if (len >= cfg.boostWorst()) break;
            }
        }
        return new HoverCheck(null, h, sink, off, ticks);
    }

    /**
     * An arc flight whose boost ends early ({@link #simulate}, a bail-out flown on): why it would come down into a block
     * or hard, or null. The plan counts on the shortest boost through the turn; a boost that ends before it leaves the
     * glide to carry the bot wide of the turn (FlightArcTest, an arc onto the walkway over dense full blocks: a 28-tick
     * boost ended at the turn's start and the glide ran into the sorter wall at 1.5 b/t; live 2026-10-25 13:27 an arc
     * leg met a block of the art at 0.83 b/t 14.8 short of R, its cause not in the INFO lines). Boosts of
     * {@link #EARLY_BOOSTS} ticks, the rocket's boost 4 ticks after its use; a glide out of the profile counts as into
     * a block.
     */
    static @Nullable String earlyEndProblem(FlightPlanner.Profile p, Cfg cfg, double x, double y, double z) {
        for (int len : EARLY_BOOSTS) {
            if (len >= cfg.boostTicks()) continue;
            Sim s = simulate(p, cfg, x, y, z, len, 4, true);
            String bad = s.why() != null && s.why().startsWith("into a block") ? s.why()
                : s.why() != null && s.why().startsWith("still in the air") ? s.why()
                : s.touchH() > EARLY_TOUCH_MAX ? String.format("down at %.2f b/t", s.touchH())
                : s.touchVy() <= -0.5 ? String.format("down sinking %.2f", -s.touchVy()) : null;
            if (bad != null) return String.format("a boost that ends after %d ticks: %s", len, bad);
        }
        return null;
    }

    /** Boosts shorter than any planned for that an arc must survive ({@link #earlyEndProblem}). */
    static final int[] EARLY_BOOSTS = {20, 28};
    /** {@link #earlyEndProblem}'s fastest touch-down (b/t), as the arc sweeps' rule. */
    static final double EARLY_TOUCH_MAX = 1.8;

    /** Ticks from the rocket's use to its boost that {@link #hoverPreflight} simulates (the round trips of the tests). */
    static final int[] HOVER_DELAYS = {4, 2, 8};
    /** Boost ticks between the boosts {@link #hoverPreflight} simulates. */
    static final int HOVER_STEP = 1;

    /** Score over a candidate's own for one that goes wrong with the longest boost ({@link #chooseBoth}). */
    static final double WORST_UNSAFE = 1e6;
    /** The glide's pitches after a boost ends: from {@link #GLIDE_MIN} to {@link #PITCH_MAX}, this far apart. */
    static final float GLIDE_STEP = 5f;
    static final float GLIDE_MIN = -20f;
    /** Pitches the look-ahead tries while a boost lasts longer than the shortest ({@link #both}), besides going on. */
    static final float[] EXTENSION = {2.5f, 5f, 7.5f};

    /**
     * The heights a boosted cruise may hold ({@link #chooseBoth}): the band's middle ({@link #holdY}), its bottom, and
     * half a block under it (still over every floor by the planner's clearance less half a block); none without a band.
     */
    double[] holdHeights() {
        if (Double.isNaN(p.bandLow)) return new double[0];
        double mid = holdY();
        return mid > p.bandLow + 1e-6 ? new double[]{mid, p.bandLow, p.bandLow - 0.5} : new double[]{mid, p.bandLow - 0.5};
    }

    /**
     * One candidate of {@link #chooseBoth}: pitch {@code c} held while it boosts, or (not NaN) height {@code hold}; the
     * choice's pitch is this tick's. Null when it cannot score under {@code bound} (its shortest boost alone scores
     * more).
     */
    @Nullable Choice both(ElytraPhysics.State s, float yawC, float c, double hold, int boostIn, int boostLeft, int worstLeft,
                          double bound) {
        float first = Double.isNaN(hold) ? c : hold(s, hold);
        int endA = boostIn + boostLeft;
        int endB = boostIn + worstLeft;
        double[] near = {0};
        ElytraPhysics.State[] st = {s};
        Choice a = run(st, yawC, c, hold, boostIn, 0, endA, first, endA, near);
        boolean ended = a != null; // touched down or went wrong while it boosts
        ElytraPhysics.State s1 = st[0];
        double near1 = near[0];
        if (a == null) a = bestGlide(s1, yawC, endA, first, near1, endA);
        if (a.score() >= INVALID) return a;
        if (a.score() > bound + 1e-9) return null;
        Choice b = null;
        if (ended) {
            st[0] = s;
            near[0] = 0;
            b = run(st, yawC, c, hold, boostIn, 0, endB, first, endB, near);
            if (b == null) b = bestGlide(st[0], yawC, endB, first, near[0], endB);
        } else {
            for (int k = -1; k < EXTENSION.length; k++) {
                st[0] = s1;
                near[0] = near1;
                Choice v = k < 0 ? run(st, yawC, c, hold, boostIn, endA, endB, first, endB, near)
                    : run(st, yawC, EXTENSION[k], Double.NaN, boostIn, endA, endB, first, endB, near);
                if (v == null) v = bestGlide(st[0], yawC, endB, first, near[0], endB);
                if (b == null || v.score() < b.score() - 1e-9) b = v;
                if (b.score() <= a.score()) break;
            }
        }
        double score = b.score() >= INVALID ? WORST_UNSAFE + a.score() : Math.max(a.score(), b.score());
        int miss = b.score() > a.score() && b.miss() != 0 ? b.miss() | MISS_WORST : a.miss();
        return new Choice(first, score, a.shortBy(), Math.max(a.ticks(), b.ticks()), a.hard() || b.hard(), miss);
    }

    /**
     * Look-ahead ticks {@code t0} + 1 to {@code t1} from state {@code st[0]}: pitch {@code c} held, or (not NaN) height
     * {@code hold}, boosted after {@code boostIn}. The verdict when it touches down or goes wrong; else null, and
     * {@code st[0]} is the state at {@code t1}.
     */
    private @Nullable Choice run(ElytraPhysics.State[] st, float yawC, float c, double hold, int boostIn, int t0, int t1,
                                 float first, int boostEnd, double[] near) {
        ElytraPhysics.State s = st[0];
        for (int t = t0 + 1; t <= t1; t++) {
            ElytraPhysics.State n = next(s, yawC, ramp(Double.isNaN(hold) ? c : hold(s, hold), t), t > boostIn);
            Choice v = sweep(s, n, t, first, boostEnd, false, near);
            if (v != null) return v;
            s = n;
        }
        st[0] = s;
        return null;
    }

    /** The best glide from state {@code s} at look-ahead tick {@code t0}, the boost over: a pitch held. */
    private Choice bestGlide(ElytraPhysics.State s, float yawC, int t0, float first, double near0, int boostEnd) {
        Choice best = null;
        for (float q = GLIDE_MIN; q <= PITCH_MAX + 1e-3; q += GLIDE_STEP) {
            Choice ch = glide(s, yawC, q, t0, first, near0, boostEnd);
            if (best == null || ch.score() < best.score() - 1e-9) best = ch;
        }
        return best;
    }

    /** From state {@code s} at look-ahead tick {@code t0}: pitch {@code q} held, no boost, to the touch-down. */
    private Choice glide(ElytraPhysics.State s, float yawC, float q, int t0, float first, double near0, int boostEnd) {
        double[] near = {near0};
        for (int t = t0 + 1; t <= HORIZON; t++) {
            ElytraPhysics.State n = next(s, yawC, ramp(q, t), false, true);
            Choice v = sweep(s, n, t, first, boostEnd, false, near);
            if (v != null) return v;
            s = n;
        }
        double remaining = p.length() - alongOf(s.x(), s.z(), s.along());
        return new Choice(first, NO_TOUCH + Math.abs(remaining) + near[0], remaining, HORIZON);
    }

    /** The height a boosted cruise holds: the middle of the planner's band (over every floor, under every roof). */
    double holdY() {
        double hi = Math.min(p.bandHigh, p.bandLow + 3);
        return Math.max(p.bandLow, (p.bandLow + hi) / 2);
    }

    /** The pitch that holds height {@code y0} under a boost: down while rising or above it, up while below. */
    static float holdPitch(ElytraPhysics.State s, double y0) {
        return holdPitch(s, y0, HOLD_LIMIT);
    }

    /** {@link #holdPitch(ElytraPhysics.State, double)} at most {@code limit} degrees either way. */
    static float holdPitch(ElytraPhysics.State s, double y0, double limit) {
        return (float) Math.max(-limit, Math.min(limit, s.vy() * 60 - (y0 - s.y()) * 10));
    }

    /** This flight's height hold: {@link #HIGH_HOLD_LIMIT} over the end rods ({@link FlightPlanner.Profile#high}). */
    private float hold(ElytraPhysics.State s, double y0) {
        return holdPitch(s, y0, p.high ? HIGH_HOLD_LIMIT : HOLD_LIMIT);
    }

    /** The most the height hold pitches, degrees. */
    static final double HOLD_LIMIT = 20;
    /**
     * ... over the end rods ({@link FlightPlanner#planHigh}): the climb from under the rods to the band over them (8 or
     * 9 blocks; at 35 degrees a boost climbs 0.86 b/t, 1.2 along).
     */
    static final double HIGH_HOLD_LIMIT = 35;

    /**
     * The pitch the bot holds at the look-ahead's start while {@link #choose} or {@link #chooseBoth} looks (NaN: no
     * ramp): a candidate's pitch comes from it at most {@link #PITCH_RATE} a tick, as {@link #steer} sends it (the
     * simulation 2026-10-25: a dive chosen at 40 degrees from 8 went 28 the first tick, and the next look found every
     * way into an end rod's corner).
     */
    private float rampFrom = Float.NaN;

    /** Pitch {@code target} as the bot holds it at look-ahead tick {@code t} ({@link #rampFrom}). */
    private float ramp(float target, int t) {
        if (Float.isNaN(rampFrom)) return target;
        float most = PITCH_RATE * t;
        return rampFrom + Math.max(-most, Math.min(most, target - rampFrom));
    }

    /** Predicts holding {@code pitchC} from state {@code s}: the score of its rest point (see {@link #choose}). */
    Choice predict(ElytraPhysics.State s, float yawC, float pitchC, int boostIn, int boostLeft) {
        return predict(s, yawC, pitchC, boostIn, boostLeft, false, Double.NaN);
    }

    /**
     * Predicts holding {@code pitchC}; {@code down}: a bail-out's scoring (the soonest, slowest touch-down on any
     * floor, R and the pad do not count). The move of every tick is looked at in steps of at most {@link #SUB} blocks:
     * the box under every roof ({@link FlightPlanner.Profile#roof}; touching one, or coming within
     * {@link FlightPlanner#ROOF_MARGIN} at more than 1 tick ahead, costs {@link #INVALID} or {@link #NEAR_ROOF}), never
     * into the side of a column whose floor stands above the box's bottom (a wall: {@link #INVALID}), and the first
     * step whose box is on or within {@link #CONTACT} of a floor is the touch-down. {@code hold} (not NaN): until the
     * boost ends the pitch holds that height ({@link #holdPitch}), {@code pitchC} only after; the choice's pitch is then
     * this tick's hold pitch.
     */
    Choice predict(ElytraPhysics.State s, float yawC, float pitchC, int boostIn, int boostLeft, boolean down, double hold) {
        double[] near = {0};
        boolean holding = !Double.isNaN(hold);
        float first = holding ? hold(s, hold) : pitchC;
        for (int t = 1; t <= HORIZON; t++) {
            boolean boost = t > boostIn && t <= boostIn + boostLeft;
            float pt = ramp(holding && t <= boostIn + boostLeft ? hold(s, hold) : pitchC, t);
            ElytraPhysics.State n = next(s, yawC, pt, boost, !down && t > boostIn + boostLeft);
            Choice v = sweep(s, n, t, first, boostIn + boostLeft, down, near);
            if (v != null) return v;
            s = n;
        }
        double remaining = p.length() - alongOf(s.x(), s.z(), s.along());
        return new Choice(first, NO_TOUCH + Math.abs(remaining) + near[0], remaining, HORIZON);
    }

    /**
     * The look-ahead's tick {@code t}, the move from {@code s} to {@code n} (see {@link #predict(ElytraPhysics.State,
     * float, float, int, int, boolean, double)}): the verdict when it touches down or goes wrong, null when the flight
     * goes on. {@code boostEnd}: the tick the boost counted on ends; {@code near}: the near-roof score so far (updated).
     */
    private @Nullable Choice sweep(ElytraPhysics.State s, ElytraPhysics.State n, int t, float first, int boostEnd,
                                   boolean down, double[] near) {
        if (n.y() + ElytraPhysics.BOX_HEIGHT > p.ceiling) return new Choice(first, INVALID, 0, 0);
        if (lPathWall(s, n, t)) return new Choice(first, INVALID, 0, 0);
        double mx = n.x() - s.x();
        double my = n.y() - s.y();
        double mz = n.z() - s.z();
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt(mx * mx + my * my + mz * mz) / SUB));
        double px = s.x();
        double pz = s.z();
        for (int j = 1; j <= steps; j++) {
            double f = (double) j / steps;
            double x = s.x() + mx * f;
            double y = s.y() + my * f;
            double z = s.z() + mz * f;
            boolean onPad = false;
            boolean offPad = false;
            int x0 = (int) Math.floor(x - ElytraPhysics.BOX_HALF);
            int x1 = (int) Math.floor(x + ElytraPhysics.BOX_HALF);
            int z0 = (int) Math.floor(z - ElytraPhysics.BOX_HALF);
            int z1 = (int) Math.floor(z + ElytraPhysics.BOX_HALF);
            double floorWas = Double.NEGATIVE_INFINITY; // the highest floor under the box at the step before
            for (int cx = (int) Math.floor(px - ElytraPhysics.BOX_HALF); cx <= (int) Math.floor(px + ElytraPhysics.BOX_HALF); cx++) {
                for (int cz = (int) Math.floor(pz - ElytraPhysics.BOX_HALF); cz <= (int) Math.floor(pz + ElytraPhysics.BOX_HALF); cz++) {
                    double tw = p.top(cx, cz);
                    if (!Double.isNaN(tw)) floorWas = Math.max(floorWas, tw);
                }
            }
            for (int cx = x0; cx <= x1; cx++) {
                for (int cz = z0; cz <= z1; cz++) {
                    double top = p.top(cx, cz);
                    if (Double.isNaN(top)) return new Choice(first, INVALID, 0, 0);
                    double roof = p.roof(cx, cz);
                    if (y + ElytraPhysics.BOX_HEIGHT > roof) return new Choice(first, INVALID, 0, 0);
                    // durations 2 and 3: as bad as off the pad (the simulation 2026-10-25: a climb to stretch the glide
                    // ran on just under the counter's line and hit it)
                    if (t > 1 && y + ElytraPhysics.BOX_HEIGHT > roof - FlightPlanner.ROOF_MARGIN) {
                        near[0] = cfg.boostTicks() > boostMin(1) ? OFF_PAD : NEAR_ROOF;
                    }
                    boolean was = cx >= (int) Math.floor(px - ElytraPhysics.BOX_HALF)
                        && cx <= (int) Math.floor(px + ElytraPhysics.BOX_HALF)
                        && cz >= (int) Math.floor(pz - ElytraPhysics.BOX_HALF)
                        && cz <= (int) Math.floor(pz + ElytraPhysics.BOX_HALF);
                    // into the side of a column standing above the box's bottom: a wall, whatever the floor; a short
                    // landing's spot cell a carpet over the soul sand beside it, or any such step under a brake, is its
                    // touch-down when no higher than the floor the box comes down on (the step's dip into the floor), or
                    // met slowly (under 0.3 b/t the server deals no fly_into_wall damage: the stop is the landing)
                    if (!was && y < top - WALL_EPS) {
                        boolean spotEdge = (p.spot && p.pad(cx, cz) || n.braking())
                            && top - y <= FlightPlanner.SPOT_LEVEL_EPS + 1e-9
                            && (top <= floorWas + 1e-6
                            || n.hSpeed() < EDGE_SPEED && top - floorWas <= FlightPlanner.SPOT_LEVEL_EPS + 1e-9);
                        if (!spotEdge) return new Choice(first, INVALID, 0, 0);
                    }
                    // a player's place: never down there (players())
                    if (y < top + CONTACT && !playerCommit && !blocked.isEmpty()
                        && blocked.contains(FlightPlanner.Profile.key(cx, cz))) {
                        return new Choice(first, INVALID, 0, 0);
                    }
                    // arc flights: never down on a column far over the floors of the take-off and the pad (the sorter
                    // wall's top beside the walkway lies in the bail-out's apron)
                    if (y < top + CONTACT && top > p.touchMax) return new Choice(first, INVALID, 0, 0);
                    // build.elytraOverWater: never down on water (a canal: the fall rescue's climb) in a glide or a bail-out
                    if (y < top + CONTACT && p.water(cx, cz)) return new Choice(first, INVALID, 0, 0);
                    if (p.pad(cx, cz)) {
                        if (y <= top) onPad = true;
                    } else if (y < top + CONTACT) {
                        offPad = true;
                    }
                }
            }
            px = x;
            pz = z;
            if (offPad || onPad) {
                double h = n.hSpeed();
                int boostOn = Math.max(0, boostEnd - t);
                // players: neither the touch-down nor the slide's end within PLAYER_MARGIN of one (the cells under the
                // box at the touch-down are checked above; the slide carries it on)
                if (!players.isEmpty() && !playerCommit && playerClose(x, y, z, h > 0.05 ? n.vx() / h : 0, h > 0.05 ? n.vz() / h : 0,
                    h > 0.05 ? ElytraPhysics.slide(h, cfg.slideTicks(), boostOn) : 0)) {
                    return new Choice(first, INVALID, 0, 0);
                }
                // arc flights, and a touch-down off the pad after a server teleport: the slide on the ground after the
                // touch-down runs into no block (its way back over the walkway, the art's blocks: a touch-down off the
                // way slides where the planner did not look; simulation 2026-10-25, after a teleport a landing short
                // slid into the walkway's side at 0.44 b/t). On the pad or spot the planner checked the slide's room, for
                // the planned approach only: a bail-out's touch-down comes from any heading and is checked anywhere when
                // faster than SPOT_MAX_SPEED (the simulation 2026-10-25: a bail-out onto the drop point's spot at 0.83
                // b/t slid into the sorter wall, then along it into the turnaround station's no-go box; a slower one
                // slides a block at most, and checking those too left no bail-out beside a teammate by the wall)
                if ((path != null || down && h > SPOT_MAX_SPEED || teleported && offPad) && h > 0.05 && slideHits(x, y, z, n.vx() / h, n.vz() / h,
                    ElytraPhysics.slide(h, cfg.slideTicks(), boostOn), teleported ? TP_SLIDE_MARGIN : 0, h, n.vy())) {
                    return new Choice(first, INVALID, 0, 0);
                }
                if (down) {
                    double score = 0.01 * t + h * 5 + (n.vy() < -SINK_LIMIT ? 50 : 0) + (boostOn > 0 ? BOOSTED : 0) + near[0];
                    return new Choice(first, score, 0, t);
                }
                if (p.spot) return spotScore(first, x, z, n, h, t, boostOn, offPad, near[0]);
                // on an arc flight the place on the way (the state's, tracked), its point the nearest to (x, z)
                double at = alongOf(x, z, n.along());
                double rest = at + ElytraPhysics.slide(h, cfg.slideTicks(), boostOn);
                double err = rest - p.length();
                // past R counts double (walls and steps lie ahead of the pad, the walk's goal behind it)
                double score = (err > 0 ? 2 * err : -err) + TIME_WEIGHT * t + Math.max(0, h - cfg.maxLandSpeed()) * 20
                    + (n.vy() < -SINK_LIMIT ? 50 : 0) + (boostOn > 0 ? BOOSTED : 0) + Math.abs(acrossOf(x, z, at)) * 2 + near[0];
                // off the pad; or, durations 2 and 3: down while the rocket still boosts (it pushes the slide on, the
                // simulation's rests went 5-23 past R), or coming to rest past REST_AIM, onto ground the planner did not
                // look at (dense art in the simulation 2026-10-25: rests 5-7 past R into the next block)
                boolean longer = cfg.boostTicks() > boostMin(1);
                if (offPad || longer && (boostOn > 0 || err > REST_AIM)) score += OFF_PAD;
                int miss = offPad ? MISS_OFF_PAD : !longer ? 0 : boostOn > 0 ? MISS_BOOSTED : err > REST_AIM ? MISS_PAST
                    : near[0] >= OFF_PAD ? MISS_NEAR_ROOF : 0;
                return new Choice(first, score, -err, t, h > cfg.maxLandSpeed() || n.vy() < -SINK_LIMIT || boostOn > 0, miss);
            }
        }
        return null;
    }

    /**
     * The tick's move as vanilla makes it ({@code Entity.collide}): the height first (down onto the floor under the box
     * where it was, then a step-up of {@link #STEP_UP}; up into a block over its head there: a bump, the climb lost),
     * then the longer horizontal axis, then the other, each at that height: the box sweeps an L, not the straight line
     * {@link #sweep}'s steps look at. True when a column the box enters on the way stands over its bottom (a wall; met
     * under {@link #EDGE_SPEED} at most {@link FlightPlanner#SPOT_LEVEL_EPS} over it, a touch-down's stop: no), puts a
     * roof into the box, or lies out of the profile; the columns under the box where it was do not count. From the
     * look-ahead's second tick on, at {@link #EDGE_SPEED} or more, a wall within {@link #WALL_MARGIN} ahead of either
     * leg or beside it counts too (the prediction's error: the simulation 2026-10-25, a spot landing's line passed a
     * pillar's corner 0.004 clear in the look-ahead and met it at 1.35 b/t).
     * Local 2026-10-25 14:44: a brake's first tick at 1.71 b/t on a final line about 20 degrees off an axis met the
     * corner of a glass pillar beside the line, 0.06 inside the L's way across (0.57 b/t left, 4-5 HP); the straight
     * line passed it.
     */
    private boolean lPathWall(ElytraPhysics.State s, ElytraPhysics.State n, int t) {
        double r = ElytraPhysics.BOX_HALF;
        int sx0 = (int) Math.floor(s.x() - r);
        int sx1 = (int) Math.floor(s.x() + r);
        int sz0 = (int) Math.floor(s.z() - r);
        int sz1 = (int) Math.floor(s.z() + r);
        double floor = Double.NEGATIVE_INFINITY;
        double head = Double.POSITIVE_INFINITY;
        for (int cx = sx0; cx <= sx1; cx++) {
            for (int cz = sz0; cz <= sz1; cz++) {
                double top = p.top(cx, cz);
                if (!Double.isNaN(top)) floor = Math.max(floor, top);
                head = Math.min(head, p.roof(cx, cz));
            }
        }
        // the height first: up into a block over the head where the box is (simulation 2026-10-25: a boost's climb
        // under an end rod, the straight line's steps out from under it before the head came up to it)
        if (n.y() > s.y() && n.y() + ElytraPhysics.BOX_HEIGHT > head) return true;
        double y = n.y();
        double step = 0;
        if (y <= floor) {
            y = floor;
            step = STEP_UP;
        }
        boolean xFirst = Math.abs(n.x() - s.x()) >= Math.abs(n.z() - s.z());
        double mx = xFirst ? n.x() : s.x();
        double mz = xFirst ? s.z() : n.z();
        double h = n.hSpeed();
        double m = t > 1 && h >= EDGE_SPEED ? WALL_MARGIN : 0;
        return legWall(s.x(), s.z(), mx, mz, y, step, h, m, sx0, sx1, sz0, sz1)
            || legWall(mx, mz, n.x(), n.z(), y, step, h, m, sx0, sx1, sz0, sz1);
    }

    /**
     * {@link #lPathWall}'s fast moves from the look-ahead's second tick: a wall this near ahead of the box or beside it
     * counts as met (blocks).
     */
    static final double WALL_MARGIN = 0.1;

    /**
     * {@link #lPathWall}'s one axis: the columns the box covers from (ax, az) to (bx, bz) at feet {@code y}; {@code m}
     * more ahead along the axis and to either side across it, where only a wall counts.
     */
    private boolean legWall(double ax, double az, double bx, double bz, double y, double step, double h, double m,
                            int sx0, int sx1, int sz0, int sz1) {
        double r = ElytraPhysics.BOX_HALF;
        int x0 = (int) Math.floor(Math.min(ax, bx) - r);
        int x1 = (int) Math.floor(Math.max(ax, bx) + r);
        int z0 = (int) Math.floor(Math.min(az, bz) - r);
        int z1 = (int) Math.floor(Math.max(az, bz) + r);
        boolean alongX = bx != ax;
        if (!alongX && bz == az) m = 0;
        double xLo = alongX ? (bx < ax ? m : 0) : m;
        double xHi = alongX ? (bx > ax ? m : 0) : m;
        double zLo = alongX ? m : (bz < az ? m : 0);
        double zHi = alongX ? m : (bz > az ? m : 0);
        int mx0 = (int) Math.floor(Math.min(ax, bx) - r - xLo);
        int mx1 = (int) Math.floor(Math.max(ax, bx) + r + xHi);
        int mz0 = (int) Math.floor(Math.min(az, bz) - r - zLo);
        int mz1 = (int) Math.floor(Math.max(az, bz) + r + zHi);
        for (int cx = mx0; cx <= mx1; cx++) {
            for (int cz = mz0; cz <= mz1; cz++) {
                if (cx >= sx0 && cx <= sx1 && cz >= sz0 && cz <= sz1) continue;
                double top = p.top(cx, cz);
                if (cx < x0 || cx > x1 || cz < z0 || cz > z1) {
                    if (!Double.isNaN(top) && top > y + step + 1e-9) return true;
                    continue;
                }
                if (Double.isNaN(top)) return true;
                if (y + ElytraPhysics.BOX_HEIGHT > p.roof(cx, cz)) return true;
                if (top > y + step + 1e-9 && !(h < EDGE_SPEED && top - y <= FlightPlanner.SPOT_LEVEL_EPS + 1e-9)) return true;
            }
        }
        return false;
    }

    /** A short landing's fastest touch-down (b/t): its slide stays within a block or so, and a carpet's edge harms nothing. */
    static final double SPOT_MAX_SPEED = 0.35;
    /**
     * The fastest a glide may meet a low step's side ({@link FlightPlanner#SPOT_LEVEL_EPS}) as its touch-down: the server's
     * fly_into_wall damage takes a loss of over 0.3 b/t across.
     */
    static final double EDGE_SPEED = 0.28;
    /** The box at rest keeps this far inside the spot's cells (blocks). */
    static final double SPOT_MARGIN = 0.1;

    /**
     * A short landing's touch-down at feet (x, z) with state {@code n}: its rest point (the slide along the velocity,
     * {@link ElytraPhysics#slide}) scores twice its distance from T plus {@link #TIME_WEIGHT} a tick; off the spot (the
     * touch-down off its cells, the box at rest not within them by {@link #SPOT_MARGIN}, faster than
     * {@link #SPOT_MAX_SPEED}, or still boosted) adds {@link #OFF_PAD}. Its {@code shortBy}: along the flight, as a
     * long pad's.
     */
    private Choice spotScore(float first, double x, double z, ElytraPhysics.State n, double h, int t, int boostOn,
                             boolean offPad, double near) {
        double slide = ElytraPhysics.slide(h, cfg.slideTicks(), boostOn);
        double rx = h > 1e-6 ? x + n.vx() / h * slide : x;
        double rz = h > 1e-6 ? z + n.vz() / h * slide : z;
        double err = Math.hypot(rx - p.restX, rz - p.restZ);
        double score = 2 * err + TIME_WEIGHT * t + (n.vy() < -SINK_LIMIT ? 50 : 0) + (boostOn > 0 ? BOOSTED : 0) + near;
        boolean rest = spotHolds(rx, rz);
        boolean fast = h > SPOT_MAX_SPEED;
        int miss = offPad ? MISS_OFF_PAD : boostOn > 0 ? MISS_BOOSTED : fast ? MISS_FAST : !rest ? MISS_OFF_SPOT
            : near >= OFF_PAD ? MISS_NEAR_ROOF : 0;
        if (offPad || !rest || fast || boostOn > 0) score += OFF_PAD;
        double at = alongOf(rx, rz, n.along());
        return new Choice(first, score, p.length() - at, t, fast || n.vy() < -SINK_LIMIT || boostOn > 0, miss);
    }

    /** The box at rest at feet (x, z) lies within the spot's cells ({@link FlightPlanner.Profile#pad}) by {@link #SPOT_MARGIN}. */
    private boolean spotHolds(double x, double z) {
        double r = ElytraPhysics.BOX_HALF + SPOT_MARGIN;
        for (double ox : new double[]{-r, r}) {
            for (double oz : new double[]{-r, r}) {
                if (!p.pad((int) Math.floor(x + ox), (int) Math.floor(z + oz))) return false;
            }
        }
        return true;
    }

    /**
     * The box touching down at feet (x, y, z), {@code h} b/t along ({@code dx}, {@code dz}) and sinking {@code vy} b/t,
     * meets a column on its way to rest, or leaves the profile. The touch-down counts from {@link #CONTACT} over the
     * floor: until a tick's move reaches the floor the box still flies, and any column standing over its bottom stops
     * it (in the air there is no step-up: the simulation 2026-10-25, a dive onto a candle 0.5 under the next block);
     * then it slides {@code dist} blocks, stepping up {@link #STEP_UP}.
     */
    private boolean slideHits(double x, double y, double z, double dx, double dz, double dist, double extra, double h,
                              double vy) {
        double floor = Double.NEGATIVE_INFINITY;
        for (int bx = (int) Math.floor(x - ElytraPhysics.BOX_HALF); bx <= (int) Math.floor(x + ElytraPhysics.BOX_HALF); bx++) {
            for (int bz = (int) Math.floor(z - ElytraPhysics.BOX_HALF); bz <= (int) Math.floor(z + ElytraPhysics.BOX_HALF); bz++) {
                floor = Math.max(floor, p.top(bx, bz));
            }
        }
        if (Double.isNaN(floor)) return true;
        // the flight on until the move that reaches the floor (ticks of h blocks; no sink: at most AIR_TICKS of them)
        double air = 0;
        double yk = y;
        for (int k = 0; k < AIR_TICKS && yk > floor + 1e-9 && yk + Math.min(vy, 0) > floor + 1e-9; k++) {
            yk += Math.min(vy, 0);
            air += h;
        }
        for (double d = 0; d <= air + dist + extra + ElytraPhysics.BOX_HALF; d += SUB) {
            // past the slide's estimate ({@code extra}): only the columns the profile knows (its edge is no wall)
            boolean margin = d > air + dist + ElytraPhysics.BOX_HALF;
            double cx = x + dx * d;
            double cz = z + dz * d;
            double lim = d < air ? y + Math.min(vy, 0) * Math.floor(d / h) : floor + STEP_UP;
            for (int bx = (int) Math.floor(cx - ElytraPhysics.BOX_HALF); bx <= (int) Math.floor(cx + ElytraPhysics.BOX_HALF); bx++) {
                for (int bz = (int) Math.floor(cz - ElytraPhysics.BOX_HALF); bz <= (int) Math.floor(cz + ElytraPhysics.BOX_HALF); bz++) {
                    double top = p.top(bx, bz);
                    if (Double.isNaN(top) ? !margin : top > lim + 1e-9) return true;
                }
            }
        }
        return false;
    }

    /** {@link #slideHits}: ticks of flight after the touch-down at most (a box that does not sink). */
    static final int AIR_TICKS = 3;

    /** The step-up of a box on the ground (vanilla's 0.6). */
    static final double STEP_UP = 0.6;

    /** Longest step of the look-ahead's sweep, blocks (the end rods are a quarter block wide: cells count whole). */
    static final double SUB = 0.4;
    /**
     * How far a column's floor may stand above the box's bottom before entering it sideways counts as a wall: none. In
     * the air the Bot steps up nothing (vanilla's step-up needs the ground), so a carpet's 0.19 over the soul sand stops
     * a glide that skims the floor (fly3: 1.66 to 0.24 b/t, fly_into_wall damage).
     */
    static final double WALL_EPS = 0;
    /** Score for a path that comes within {@link FlightPlanner#ROOF_MARGIN} of a roof. */
    static final double NEAR_ROOF = 200;

    /** The yaw toward the flight line {@link #PURSUIT} blocks ahead of the bot's projection on it (R at most). */
    float pursuitYaw(double x, double z) {
        if (path != null) return leftWay ? yaw : arcYaw(x, z, pathAt); // off the way: leaveTheWay's
        if (leftWay) return yaw; // a straight flight's bail-out off its line (bailKeys)
        double a = Math.min(p.length(), Math.max(0, p.along(x, z)) + PURSUIT);
        double tx = p.startX + p.dirX * a;
        double tz = p.startZ + p.dirZ * a;
        if (Math.hypot(tx - x, tz - z) < 1.0) {
            tx = x + p.dirX * PURSUIT;
            tz = z + p.dirZ * PURSUIT;
        }
        return ElytraPhysics.yawTo(x, z, tx, tz);
    }

    private Keys bail(String why, In in) {
        bailWhy = why;
        to(Phase.BAIL);
        if (in.onGround()) return land(in);
        return bailKeys(in);
    }

    /** Bail-out ticks with no safe touch-down within reach that braked in place instead ({@link #bailKeys}). */
    private int stalls;

    /** {@link #stalls}, for the flight's line and the tests. */
    int stalls() {
        return stalls;
    }

    /** A bail-out's tick in the air: the soonest, slowest safe touch-down; the plain fall when the glide is off. */
    private Keys bailKeys(In in) {
        if (!in.fallFlying()) return keys(GROUND_PITCH, false, false);
        if (!players.isEmpty()) playerTaken(); // the players' cells this tick: no touch-down there
        yaw = pursuitYaw(in.x(), in.z());
        int[] bw = boostWindow(in);
        ElytraPhysics.State now = new ElytraPhysics.State(in.x(), in.y(), in.z(), in.vx(), in.vy(), in.vz());
        Choice best = leftWay ? null : choose(now, yaw, bw[0], bw[1], pitch, true);
        // arc flights: off the way; a straight flight too when every pitch along its line goes wrong (a bail-out after a
        // burn beside a wall: the simulation 2026-10-25, the dive it chose then touched the sorter wall)
        if (path != null || leftWay || best.score() >= INVALID) best = leaveTheWay(now, in, best, bw[0], bw[1]);
        lastChoice = best;
        double h = in.hSpeed();
        if (best.score() >= INVALID && h > FlightBrake.V_MAX && bw[1] <= 0) {
            // no safe touch-down within reach at all (players on every one, built art, the world beyond the corridor):
            // the brake in place, the look against the way, the slowest any contact can be (a fifth less a tick); never
            // on at full speed out of the corridor (simulation 2026-10-25: a bail-out at 1.6 b/t ran into the art)
            yaw = ElytraPhysics.yawTo(0, 0, -in.vx(), -in.vz());
            stalls++;
            return keys(Math.max(pitch - PITCH_RATE, Math.min(pitch + PITCH_RATE, 0f)), false, false);
        }
        float next = Math.max(pitch - PITCH_RATE, Math.min(pitch + PITCH_RATE, best.pitch()));
        return keys(next, false, false);
    }

    private Keys abort(String why) {
        return new Keys(yaw, pitch, false, false, false, why);
    }

    private void to(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    private Keys keys(float pitchNow, boolean jump, boolean rocket) {
        pitch = pitchNow;
        return new Keys(yaw, pitchNow, jump, rocket, false, null);
    }

    /** The look-ahead's last verdict (debug lines), or null before the flight. */
    @Nullable Choice lastChoice() {
        return lastChoice;
    }

    /** Tests: the controller as just after a touch-down ({@link #land}'s keys from the next tick on). */
    void touchedDown() {
        to(Phase.LANDED);
    }

    /**
     * The server moved the bot (a teleport or a setback) during this flight: before the glide the take-off ends (or, moved
     * {@link #TP_GLIDE_HEIGHT} or more over the floor, the glide starts); in the air the flight goes the burn's way down
     * ({@link #startBurn}: a setback puts the bot back along its way, inside the corridor the look-ahead knows, but the
     * boost's timing is off); off the corridor it holds in place and comes down slowly wherever it is
     * ({@link #holdKeys}). No further rocket. Each teleport is told once.
     */
    void teleported() {
        if (phase == Phase.LANDED && phaseTicks > TP_GROUND_TICKS) return;
        teleported = true;
        tpFresh = true;
        noMoreRockets = true;
        tpFrom = ticks + 1;
        tpUntil = ticks + 1 + TP_GROUND_TICKS;
    }

    /**
     * Bot ticks after a teleport whose ground flag is checked against the corridor's floor (more than
     * {@link #TP_GROUND_SLACK} over it, or off the corridor: in the air), and the touch-downs that close: a landing
     * within them is undone.
     */
    static final int TP_GROUND_TICKS = 2;
    static final double TP_GROUND_SLACK = 0.5;
    private int tpFrom;
    private int tpUntil = -1;

    private boolean teleported;
    /** {@link #teleported}: not yet acted on in the air; off the corridor, holding in place ({@link #holdKeys}). */
    private boolean tpFresh;
    private boolean tpHold;
    /** {@link #teleported} before the glide, high: the jump key pressed every other tick until the glide starts. */
    private boolean tpJump;
    /** {@link #teleported} before the glide: from this high over the corridor's floor (blocks) the glide, not a fall. */
    static final double TP_GLIDE_HEIGHT = 2.5;
    /** After a teleport this many boosted ticks in a row with no way onto the landing start the burn ({@link #fly}). */
    static final int TP_MISSES = 3;
    private int tpMisses;
    /**
     * After a teleport a touch-down's slide is looked at this much longer (blocks): the landing is no longer the planned
     * one (simulation 2026-10-25: a bail-out's slide on the walkway ran 0.3 further than its estimate, into a no-go cell).
     */
    static final double TP_SLIDE_MARGIN = 1.0;

    /** {@link #teleported}: whether it came. */
    boolean wasTeleported() {
        return teleported;
    }

    /**
     * Holding in place with no world to plan in ({@link #teleported} off the corridor): under a boost the spin in place
     * ({@link #spinKeys}: the boost pushes along the look, so a look against the way would fly the bot back at full
     * speed), its height held in the flight's band (clear of the art below and the end rods above on the platform: a
     * spin at pitch 0 sank onto the built art at 0.8 b/t in the simulation, one 10 degrees up climbed into an end rod);
     * then the look against the way at pitch 0, which bleeds a fifth of the speed a tick while the glide sinks at about
     * 0.15 b/t (under the 0.5 that counts as a fall): down slowly onto whatever lies below.
     */
    private Keys holdKeys(In in) {
        if (!in.fallFlying()) return keys(GROUND_PITCH, false, false);
        if (in.boosting()) return spinKeys(in, state(in));
        spinning = false;
        if (in.hSpeed() > 0.05) yaw = ElytraPhysics.yawTo(0, 0, -in.vx(), -in.vz());
        return keys(Math.max(pitch - PITCH_RATE, Math.min(pitch + PITCH_RATE, 0f)), false, false);
    }

    /** The leg's walk ended with the bot in the air: from the next tick on, a bail-out down to the floor. */
    void endInTheAir(String why) {
        if (phase != Phase.LANDED && phase != Phase.BAIL) forcedBail = why;
    }

    /** Why the flight bailed out in the air, or null. */
    @Nullable String bailReason() {
        return bailWhy;
    }
}
