package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Getting into position to use a block and clicking it once. Pure decisions,
 * tick by tick, like {@link LaneRun}: {@link ContainerTask} runs it on ZenithProxy, the tests on a simulated world
 * with wall signs.
 *
 * <ol>
 *   <li>If the centre of a face of the target is in reach and in sight from where the bot stands, it stays: when the
 *       cell is {@linkplain StandSpots.World#allowed allowed} and, if stand cells are configured ({@code first}: an
 *       actuator's {@code standAt}), only when it is one of them.</li>
 *   <li>Otherwise {@link StandSpots} ranks the cells in reach with a face centre in sight that the bot can walk to
 *       (the configured cells, the bot's own cell, the cell that worked last time, then the nearest by walking
 *       distance), never one of the {@linkplain #avoid cells to avoid}. Baritone walks to the first one with an
 *       exact-cell goal, the bot centres itself with its own movement keys and holds still. A spot that cannot be
 *       walked to or does not see a face centre after all is logged and the next is tried, up to {@link #MAX_SPOTS}; a
 *       spot the reachability flood reached gets one Baritone attempt ({@link #REACHABLE_WALK_RETRIES}), others
 *       Baritone's usual retries.</li>
 *   <li>Look, then click on the next tick (Grim RotationPlace): one tick requests the rotation to the visible face
 *       centre, the next sends the use-item-on only if the rotation in effect still hits that face of the target
 *       from the eye, within reach, with no movement keys and the rotation unchanged in the click tick.</li>
 * </ol>
 * {@link #tick} returns SUCCESS on the tick the click was sent; the caller waits for the menu and calls {@link #retry}
 * when it did not open. A {@linkplain #clickOnlyWhen click gate} holds the click back (a button still pressed) without
 * changing where the bot stands or counting as a miss.
 */
public final class Approach<S extends PlacePipeline.Rotation> {
    /** Stand spots tried per approach. */
    public static final int MAX_SPOTS = 4;
    /** Ticks to centre on a stand spot after Baritone arrived. */
    static final int CENTER_TICKS = 20;
    /** The bot is centred within this of the cell centre, on each horizontal axis. */
    static final double CENTRED = 0.15;
    /**
     * Horizontal speed a walking player keeps each tick on the ground without keys (block slipperiness 0.6 × air drag
     * 0.91): from speed v it slides {@code v × FRICTION / (1 − FRICTION)}, about 1.2 v, before it stops.
     */
    static final double FRICTION = 0.546;
    /** Blocks per tick under which the bot counts as standing still. */
    static final double STILL = 0.02;
    /** A move longer than this in one tick is no walk (a teleport or a setback): the speed counts as unknown. */
    static final double MAX_SPEED = 1.0;
    /**
     * Times per spot the bot centres itself again when it came to rest off the centre, or when no face centre was in
     * sight from there, before the spot counts as failed.
     */
    static final int MAX_RECENTRES = 2;
    /** At most this many ticks waiting for the bot to come to rest before aiming. */
    static final int SETTLE_TICKS = 10;
    /** Aims whose rotation no longer hit the target on the click tick, before the spot counts as failed. */
    static final int MAX_REAIMS = 5;
    /** Ticks on the click step without being able to click (input owned elsewhere) before aiming afresh. */
    static final int CLICK_WAIT_TICKS = 20;
    /**
     * Baritone retries for a stand spot {@link StandSpots} found reachable: none. One failed path there moves on to the
     * next spot (gate B: 4 failures to a walled-in pit cost 37 s while the bot starved).
     */
    static final int REACHABLE_WALK_RETRIES = 0;

    /** The game for one approach. Positions are feet positions. Client tick thread. */
    public interface Io<S> extends StandSpots.World, StandSpots.Sight {
        double x();

        double y();

        double z();

        boolean onGround();

        /** Yaw in effect now (the rotation of the last movement packet). */
        float yaw();

        /** A shot at a face centre of the target in reach and in sight from the eye now, or null. */
        @Nullable S aim(double reach);

        /** The rotation in effect still hits the target on {@code aimed}'s face from the eye now, within reach. */
        @Nullable S recheck(S aimed, double reach);

        /** Walks with Baritone towards {@code goal} until {@code arrived}. */
        TaskStatus walk(GoalNearXZ goal, BooleanSupplier arrived);

        /**
         * {@link #walk(GoalNearXZ, BooleanSupplier)}, giving up after Baritone failed {@code retries} + 1 times (path
         * not found, or stalled). Default: the plain walk with its own retries.
         */
        default TaskStatus walk(GoalNearXZ goal, BooleanSupplier arrived, int retries) {
            return walk(goal, arrived);
        }

        String walkError();

        void stopWalking();

        /** This tick's rotation (null = keep) and movement keys: forward 1 = W / -1 = S, strafe 1 = A / -1 = D. */
        void submit(@Nullable Float yaw, @Nullable Float pitch, int forward, int strafe);

        /** This tick's input: no keys, rotation unchanged. */
        void holdStill();

        /** After this tick's submit: accepted, and the previous one executed. */
        boolean canClick();

        /** Sends the use-item-on for the shot. True if sent. */
        boolean click(S shot);

        /**
         * Nothing about the bot itself stops a click now (default: always). A placement needs the bot's box clear of
         * the cell it fills, and a sneak-click needs the bot crouched.
         */
        default boolean readyToClick() {
            return true;
        }

        void log(String line);
    }

    private enum Step { START, PICK, WALK, CENTER, SETTLE, AIM, CLICK }

    private final String what;
    private final List<Pos> targets;
    private final double reach;
    private final List<Pos> first;
    private final @Nullable Pos preferred;
    private final Io<S> io;

    private Step step = Step.START;
    private StandSpots.@Nullable Search search;
    private @Nullable Deque<StandSpots.Spot> queue;
    private StandSpots.@Nullable Spot spot;
    private final List<String> misses = new ArrayList<>();
    private long since;
    private @Nullable S aimed;
    private int reaims;
    private double px = Double.NaN;
    private double py;
    private double pz;
    private int walks;
    private String failure = "";
    private BooleanSupplier clickGate = () -> true;
    private Set<Pos> avoid = Set.of();
    private boolean brake = true;
    private boolean coast;
    private int coastClicks;
    private final List<Pos> sightMisses = new ArrayList<>();
    private int recentres;
    /** Position and tick of the last {@link #tick}, for the speed. */
    private double lastX = Double.NaN;
    private double lastZ;
    private long lastTick = Long.MIN_VALUE;
    private double vx;
    private double vz;
    private boolean speedKnown;

    /**
     * @param what      the target for log lines ("chest at [x,y,z]")
     * @param targets   blocks that open it (both halves of a double chest)
     * @param preferred feet cell that worked last time, or null
     */
    public Approach(String what, List<Pos> targets, double reach, @Nullable Pos preferred, Io<S> io) {
        this(what, targets, reach, List.of(), preferred, io);
    }

    /** @param first configured stand cells, tried first and the only ones the bot clicks from without walking */
    public Approach(String what, List<Pos> targets, double reach, List<Pos> first, @Nullable Pos preferred, Io<S> io) {
        this.what = what;
        this.targets = List.copyOf(targets);
        this.reach = reach;
        this.first = List.copyOf(first);
        this.preferred = preferred;
        this.io = io;
    }

    public String failure() {
        return failure;
    }

    /**
     * The click is sent only while {@code gate} holds (default: always), asked on the click tick after the bot's own
     * checks. While it does not, the bot holds still with its aim and aims afresh every {@link #CLICK_WAIT_TICKS}, like
     * a click the input does not allow yet: the spot is kept (not a miss, no walk), and the stay-here check before any
     * walk does not ask it. The caller bounds the wait.
     */
    public void clickOnlyWhen(BooleanSupplier gate) {
        this.clickGate = gate;
    }

    /** Baritone walks to stand spots so far. */
    public int walks() {
        return walks;
    }

    /**
     * Cells not to stand in for this target (default none): ones that did not see a face centre of it before, though
     * the search said they would. Set before the first {@link #tick}.
     */
    public Approach<S> avoid(Set<Pos> cells) {
        this.avoid = Set.copyOf(cells);
        return this;
    }

    /**
     * Centring with the bot's speed (default on): it holds still while it coasts to the centre, presses towards the
     * cell centre only while it would come to rest off it, and counts as centred only once it stands still there.
     * Off: as before, centred as soon as it passes within {@link #CENTRED} of the centre, also at walking speed
     * (live: it then slid on 0.25–0.6 block, and every miss of a stand spot came from there).
     */
    public Approach<S> brake(boolean on) {
        this.brake = on;
        return this;
    }

    /**
     * Coast-click (default off, {@code build.coastClick}): once the bot would come to rest within {@link #CENTRED} of
     * the stand spot's centre without keys, it lets go of the keys, aims and clicks while it still slides there, instead
     * of walking in, braking, standing still and settling first. Same checks on the click tick (the aim hits a face
     * centre from the eye now, in reach, no movement keys, on the ground). Grim checks keys and sprint on container
     * clicks (MultiActionsC/D), not motion.
     */
    public Approach<S> coast(boolean on) {
        this.coast = on;
        return this;
    }

    /** Clicks sent by {@link #coast} while sliding, for log lines. */
    public int coastClicks() {
        return coastClicks;
    }

    /**
     * Stand cells from which no face centre of the target was in sight after all (or the aim kept missing), though
     * the search said one would be: candidates for {@link #avoid} next time.
     */
    public List<Pos> sightMisses() {
        return List.copyOf(sightMisses);
    }

    /** The feet cell the bot stands in now (to remember after the menu opened). */
    public Pos standingCell() {
        return StandSpots.feetCell(io.x(), io.y(), io.z());
    }

    /** The click did not open the menu: aim and click again from where the bot stands (or the next spot). */
    public void retry() {
        step = Step.AIM;
        reaims = 0;
    }

    /** Baritone walks to a stand spot: nothing aimed or clicked yet (a safe point to park at). */
    public boolean walking() {
        return step == Step.WALK;
    }

    /** Parks the walk (the caller holds still); the next {@link #tick} walks on to the same spot from where it is. */
    public void pauseWalk() {
        io.stopWalking();
    }

    public String progress() {
        return " (approach " + step + (spot != null ? " spot " + spot.cell() : "") + ")";
    }

    public TaskStatus tick(long now) {
        double x = io.x();
        double z = io.z();
        speedKnown = lastTick == now - 1 && !Double.isNaN(lastX) && Math.hypot(x - lastX, z - lastZ) <= MAX_SPEED;
        // unknown on the first tick, after a gap, or after a jump no walk makes (a teleport, a setback)
        vx = speedKnown ? x - lastX : 0;
        vz = speedKnown ? z - lastZ : 0;
        TaskStatus s = switch (step) {
            case START -> start(now);
            case PICK -> pick(now);
            case WALK -> walk(now);
            case CENTER -> centre(now);
            case SETTLE -> settle(now);
            case AIM -> aim(now);
            case CLICK -> click(now);
        };
        // where the bot is when this tick's input goes out (ZenithProxy moves it after the task's tick)
        lastX = io.x();
        lastZ = io.z();
        lastTick = now;
        return s;
    }

    private TaskStatus start(long now) {
        if (io.onGround() && mayStayHere() && io.readyToClick() && io.aim(reach) != null) return aim(now); // no walk
        step = Step.PICK;
        return pick(now);
    }

    /**
     * The bot may click from the cell it stands in without walking: a configured cell, or an allowed one that the stand
     * spot search would also pick (no water or lava at the feet or head, no fire, berry bush, powder snow, bubble column,
     * no magma or cactus under it, nothing solid at the head: {@link StandSpots#feetY}).
     */
    private boolean mayStayHere() {
        Pos here = standingCell();
        if (first.isEmpty()) return io.allowed(here) && !Double.isNaN(StandSpots.feetY(io, here.x(), here.y(), here.z()));
        for (Pos f : first) {
            if (here.equals(StandSpots.firstCell(io, f))) return true;
        }
        return false;
    }

    private TaskStatus pick(long now) {
        io.holdStill();
        if (queue == null) {
            search = StandSpots.search(io, io, targets, reach, io.x(), io.y(), io.z(), first, preferred, avoid,
                MAX_SPOTS);
            queue = new ArrayDeque<>(search.spots());
            for (String r : search.firstRejected()) io.log("Cannot use " + what + " from its configured stand cell " + r);
        }
        spot = queue.poll();
        if (spot == null) {
            if (misses.isEmpty()) return fail("no stand spot: " + search.describe(reach));
            return fail("tried " + misses.size() + " stand spots: " + String.join("; ", misses));
        }
        step = Step.WALK;
        since = now;
        walks++;
        recentres = 0;
        return TaskStatus.RUNNING;
    }

    private TaskStatus walk(long now) {
        StandSpots.Spot s = spot;
        TaskStatus coasting = coastAim(now);
        if (coasting != null) return coasting;
        // a spot the flood reached that Baritone still cannot path to is not worth 4 tries: the next one is
        TaskStatus st = io.walk(new GoalNearXZ(s.x(), s.y(), s.z(), 0, 0), () -> atSpot(s),
            s.reachable() ? REACHABLE_WALK_RETRIES : Walker.MAX_FAILURES);
        if (st == TaskStatus.RUNNING) return st;
        if (st == TaskStatus.FAILED) {
            miss(s, "cannot walk there: " + io.walkError());
            step = Step.PICK;
            io.holdStill();
            return TaskStatus.RUNNING;
        }
        step = Step.CENTER;
        since = now;
        return centre(now);
    }

    private boolean atSpot(StandSpots.Spot s) {
        return StandSpots.feetCell(io.x(), io.y(), io.z()).equals(s.cell());
    }

    private TaskStatus centre(long now) {
        StandSpots.Spot s = spot;
        TaskStatus coasting = coastAim(now);
        if (coasting != null) return coasting;
        double dx = s.x() + 0.5 - io.x();
        double dz = s.z() + 0.5 - io.z();
        boolean still = !brake || (speedKnown && vx * vx + vz * vz < STILL * STILL);
        if ((Math.abs(dx) <= CENTRED && Math.abs(dz) <= CENTRED && still && io.onGround()) || now - since > CENTER_TICKS) {
            io.holdStill();
            step = Step.SETTLE;
            since = now;
            px = Double.NaN;
            return TaskStatus.RUNNING;
        }
        if (brake) {
            // where it comes to rest without keys: coast there when that is the centre, else press towards the centre
            // from there (against the slide when it would overshoot)
            double slide = FRICTION / (1 - FRICTION);
            dx -= vx * slide;
            dz -= vz * slide;
            if (Math.abs(dx) <= CENTRED && Math.abs(dz) <= CENTRED) {
                io.holdStill();
                return TaskStatus.RUNNING;
            }
        }
        int[] keys = Aim.movementKeys(io.yaw(), dx, dz);
        io.submit(null, null, keys[0], keys[1]);
        return TaskStatus.RUNNING;
    }

    /**
     * {@link #coast}: when the bot, sliding without keys, comes to rest within {@link #CENTRED} of the spot's centre and
     * a face centre of the target is in reach and in sight from the eye now, stop walking and aim (the click follows on
     * the next tick, as from {@link #aim}). Null: not yet, go on walking or centring.
     */
    private @Nullable TaskStatus coastAim(long now) {
        StandSpots.Spot s = spot;
        if (!coast || s == null || !speedKnown || !io.onGround() || !io.readyToClick()) return null;
        double slide = FRICTION / (1 - FRICTION);
        double dx = s.x() + 0.5 - (io.x() + vx * slide);
        double dz = s.z() + 0.5 - (io.z() + vz * slide);
        if (Math.abs(dx) > CENTRED || Math.abs(dz) > CENTRED) return null;
        if (StandSpots.feetCell(io.x(), io.y(), io.z()).y() != s.cell().y()) return null; // on the spot's level only
        S shot = io.aim(reach);
        if (shot == null) return null;
        io.stopWalking();
        io.submit(shot.yaw(), shot.pitch(), 0, 0);
        aimed = shot;
        since = now;
        reaims = 0;
        coastClicks++;
        step = Step.CLICK;
        return TaskStatus.RUNNING;
    }

    /** The bot stands further than {@link #CENTRED} (plus a little) from the spot's centre and may centre again. */
    private boolean mayRecentre() {
        if (!brake || spot == null || recentres >= MAX_RECENTRES || !atSpot(spot)) return false;
        double dx = spot.x() + 0.5 - io.x();
        double dz = spot.z() + 0.5 - io.z();
        return Math.abs(dx) > CENTRED + 0.05 || Math.abs(dz) > CENTRED + 0.05;
    }

    private TaskStatus recentre(long now) {
        recentres++;
        step = Step.CENTER;
        since = now;
        return centre(now);
    }

    private TaskStatus settle(long now) {
        io.holdStill();
        boolean still = !Double.isNaN(px) && io.onGround() && Aim.distSq(io.x(), io.y(), io.z(), px, py, pz) < 1e-6;
        px = io.x();
        py = io.y();
        pz = io.z();
        if (still || now - since > SETTLE_TICKS) {
            if (still && mayRecentre()) return recentre(now);
            step = Step.AIM;
            reaims = 0;
        }
        return TaskStatus.RUNNING;
    }

    /** Requests the rotation to a visible face centre; the click follows on the next tick. */
    private TaskStatus aim(long now) {
        S shot = io.aim(reach);
        if (shot == null) {
            if (mayRecentre()) return recentre(now);
            io.holdStill();
            if (spot != null && atSpot(spot)) {
                sightMisses.add(spot.cell());
                miss(spot, "no face center in sight after all ("
                    + io.hiddenFrom(io.x(), io.y() + io.eyeHeight(), io.z(), reach) + ")");
            }
            step = Step.PICK;
            return TaskStatus.RUNNING;
        }
        io.submit(shot.yaw(), shot.pitch(), 0, 0);
        aimed = shot;
        since = now;
        step = Step.CLICK;
        return TaskStatus.RUNNING;
    }

    /** Clicks with the rotation sent on the previous tick, if it still hits the target's face from the eye now. */
    private TaskStatus click(long now) {
        S ok = io.recheck(aimed, reach);
        if (ok == null) {
            if (++reaims > MAX_REAIMS) {
                io.holdStill();
                if (spot != null) {
                    sightMisses.add(spot.cell());
                    miss(spot, "the aim kept missing the face");
                }
                step = Step.PICK;
                return TaskStatus.RUNNING;
            }
            return aim(now);
        }
        io.holdStill();
        if (io.onGround() && io.readyToClick() && clickGate.getAsBoolean() && io.canClick() && io.click(ok)) {
            return TaskStatus.SUCCESS;
        }
        if (now - since > CLICK_WAIT_TICKS) step = Step.AIM;
        return TaskStatus.RUNNING;
    }

    private void miss(StandSpots.Spot s, String why) {
        misses.add(s.cell() + ": " + why);
        io.log("Cannot use " + what + " from stand spot " + s.cell() + ": " + why);
    }

    private TaskStatus fail(String why) {
        failure = why;
        return TaskStatus.FAILED;
    }
}
