package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.BotActions;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The fast nuker, {@code build.breakMode} FAST. Pure decisions, tick by
 * tick; {@link BreakBlocksTask} runs it on ZenithProxy ({@link Io}), the tests against a simulated server.
 *
 * <p><b>Breaking.</b> Every tick, among the targets in reach (eye to the nearest point of a visible face ≤
 * {@code reach}) the most urgent ones come first. Instant blocks (destroy progress per tick ≥ 1 with the tool held, on
 * the ground) of one tool group get a START each, up to {@code perTick} in the tick, the look (this tick's rotation,
 * sent in the movement packet after them, as ZenithProxy's own left click) on the first; any other block is mined
 * alone with the vanilla client's timing ({@link DigClient}: START, STOP once done, then {@code stopDelay} ticks
 * without a START), standing still. The tool is a hotbar select in the same tick before the STARTs (vanilla order),
 * never while a delayed block of an earlier double break is still on the server. A block counts as broken only when
 * the server shows air; one refused or unanswered is mined again ({@link #MAX_ATTEMPTS}). When blocks other than the
 * looked-at one are refused again and again ({@link #OFF_LOOK_REFUSALS} of the last {@link #OFF_LOOK_WINDOW} answered:
 * an anti-cheat cancelling them, not the odd refusal), the run drops to one START per tick.
 *
 * <p><b>Walking.</b> A large flat set of targets is swept ({@link NukeSweep}): the bot walks the lane lines with its own
 * movement keys (Baritone never runs while it breaks; its look would be ours) and breaks on the way. It holds still
 * while a floor cell its box is about to enter is not walkable (a target there is the most urgent of all; it waits for
 * the server's air), while a target in reach would leave reach with the next step, and while a block is being mined. A
 * target that holds the walk for {@link #HOLD_GIVE_UP_TICKS} without being started is left for later. Anything else in
 * the way (a block that is no target), or no progress, is walked around by Baritone (a detour to a point beyond).
 * Standing on top of targets (after Baritone), the blocks under the bot go first, so it drops to the floor. Targets the
 * sweep left, and every target of a small or uneven set, are done standing: Baritone to the nearest one until it is in
 * reach and sight, then everything in reach. No movement key goes out that would take the box into a hazard cell (a
 * no-go zone, water or lava, a drop) with the slide after it ({@link NukeGuard}, {@code build.nukeEdgeGuard}).
 *
 * <p><b>Kept art</b> ({@code artCleared} false: a carry-over's NUKE, VERIFY's breaks). Targets scattered among blocks
 * that stay ({@link #KEPT_ROUTE_SHARE} of their box or more holds a block that is no target, at most
 * {@link #ROUTE_MAX} targets) get no sweep: the lanes' walking lines would run into a kept block every few cells (live
 * 2026-11-03, Snowy Angel [8,7] → [9,7]: 2,433 blocks in 22 min, a detour every 2-5 s, the own keys pressing into
 * walls until the server's setbacks cancelled the round). With {@code build.nukeLeftoverRoute} the run walks the
 * leftover route from its first tick ({@link NukeLeftovers}), its stand cells also on top of the kept full blocks,
 * and asks the engine's {@code pause} at every stand as at a lane end.
 *
 * <p><b>Pockets</b> ({@code build.nukePocketGuard}, {@link NukePockets}): floor cells walled in by blocks that are no
 * target and cannot be stepped onto (pointed dripstone, pots). The box never walks or drops into one the bot is not in,
 * a target in one is never broken from on top of it (the bot steps off first), and Baritone's paths may not end in one
 * ({@link #pathCheck}). A walk that fails while the bot stands in one anyway (or the watchdog) breaks the fewest walls
 * on the way out that the clear removes anyway ({@link Io#escapable}), with the usual tool rules, logs one line naming
 * them, and walks out to the nearest cell in no pocket.
 *
 * <p>Protected blocks ({@link Io#refusal}) are never touched, fluids never tried; both are listed in the failure. When
 * the tool the next block needs is at the floor the run fails at once with {@link BotActions#TOOL_AT_FLOOR}; near it,
 * no more STARTs go out than the uses above the floor left after the blocks in flight.
 *
 * <p><b>Pausing.</b> At each lane end of the sweep (the walk turns across to the next line) the run asks the engine's
 * {@code pause} once; with a reason (a tool due for mending) it stands
 * there, breaks what is still in reach, waits for the blocks in flight, and fails with
 * {@link BotActions#BREAK_PAUSED}. The engine mends, then starts a new run from where the bot stands.
 *
 * <p><b>Lambda's OldGrim scheme</b> ({@code build.nukeLambda}, {@link Lambda}; off by default). Break timing ported
 * from Lambda, https://github.com/lambda-client/lambda (GPL-3.0), commit fa59ab1, {@code BreakManager.kt}
 * ({@code processRequest} 320-355, {@code processNewBreak} 463-479, {@code initNewBreak} 496-534) and
 * {@code BreakSettings.kt} 45-66, re-implemented here and in {@link DigClient}: non-instant blocks with fake
 * out-of-world STARTs, the 0.7 threshold, 7 ticks of mining at least, 6 ticks without a START after a STOP (skipped by
 * the next non-instant block after a burst), the double break through the server's delayed slot, and instant breaks
 * up to {@link Lambda#perTick} a tick. Walking, the sweep, the guards, pockets, Haste, the tool floor, pauses and the
 * refusal of protected blocks are this class's own and unchanged. {@link #LAMBDA_FAILS_OFF} refused blocks in a row
 * turn it off for the round.
 */
final class NukeRun<S> {
    /** Tries per block (refused or unanswered, aborted) before it is given up. */
    static final int MAX_ATTEMPTS = 3;
    /**
     * Refused STARTs among the last {@link #OFF_LOOK_WINDOW} answered STARTs of blocks the look did not hit (batches
     * above one) at which the run breaks one per tick. An anti-cheat cancelling them refuses nearly all; a server
     * refusing now and then (an effect running out, a step in the air: 32 of 9,879 in a local 1x run) does not count.
     */
    static final int OFF_LOOK_REFUSALS = 6;
    static final int OFF_LOOK_WINDOW = 20;
    /** Ticks after a block of ours showed air before a round near the floor counts on the tools' cached wear. */
    static final int WEAR_GRACE_TICKS = 3;
    /** A waypoint counts as reached within this horizontal distance of the feet. */
    static final double ARRIVE = 0.3;
    /** How far ahead along the walk the box's floor cells must be walkable. */
    static final double LOOK_AHEAD = 0.55;
    /**
     * Cells of the way up to this far ahead (blocks) are due when the box reaches them less {@link #WAY_MARGIN} (the
     * walk does not hold for them until they are {@link #LOOK_AHEAD} away).
     */
    static final double CLEAR_AHEAD = 3.0;
    /** Blocks of walk kept between a cell of the way being broken and the box reaching it: a 2b2t round trip or so. */
    static final double WAY_MARGIN = 1.25;
    /** A walking step (a little more than vanilla walking, 0.2158 blocks per tick) for the "leaves reach" test. */
    static final double STEP = 0.25;
    /**
     * Clearance of the box from hazards, pockets and blocks that are no target on a leg of the leftover route walked with
     * the own keys ({@link #legClear}); a leg without it goes by Baritone.
     */
    static final double LEG_MARGIN = 0.3;
    /**
     * Leftovers ({@code build.nukeLeftoverRoute}, not counting those already air) that get a route: fewer are walked to
     * one by one (no gain); more, or more than the sweep broke, mean it did not get through (the standing phase, as
     * before; the plan's cost grows with the square of the count).
     */
    static final int ROUTE_MIN = 4;
    static final int ROUTE_MAX = 2500;
    /**
     * Kept art ({@code artCleared} false): the share of the targets' box (cells on their level) holding a block that is
     * no target at which the run takes the route from the start instead of the sweep ({@link #routeFirst}). A
     * carry-over keeps 85-90 % of the layer; a VERIFY of a few wrong blocks has no sweep anyway.
     */
    static final double KEPT_ROUTE_SHARE = 0.3;
    /**
     * Kept art: the cover of a low target in a pit ({@link #pit}), from a stand's centre to the nearest point of its
     * cell: a stand beside it or across a corner (sim, {@code NukeCarryOverTest}: 0.5 walks to more stands, 1.6
     * leaves carpets out of sight).
     */
    static final double PIT_COVER = 0.75;
    /** Kept art: ticks the route stands at most at one stand for the targets it covers ({@link #standAt}). */
    static final int STAND_MAX_TICKS = 60;
    /** Ticks of pressing keys without moving 0.1 block before the way counts as blocked (a detour). */
    static final int STUCK_TICKS = 30;
    /**
     * Walking ticks (not holding) without getting {@link #PROGRESS_STEP} closer to the waypoint before the way counts as
     * blocked (a detour): pressing into something the walkability test passes, such as a pointed dripstone the bot already
     * stands in, while sliding sideways more than the {@link #STUCK_TICKS} test notices (local 10x run, 2026-10-11: the
     * sweep pressed against one for 10 minutes without a log line).
     */
    static final int NO_PROGRESS_TICKS = 60;
    static final double PROGRESS_STEP = 0.5;
    /**
     * Targets level with or behind the bot (they can only leave reach) that the walk lets pile up: this many ticks of
     * STARTs at the cap; more, and it holds until the breaking catches up.
     */
    static final int BACKLOG_TICKS = 6;
    /** A target that holds the walk this long without being started is left for later (standing). */
    static final int HOLD_GIVE_UP_TICKS = 60;
    /** Detours per waypoint before it is skipped. */
    static final int MAX_DETOURS = 2;
    /** Above the floor by this much the bot stands on top of the targets. */
    static final double ELEVATED = 0.5;
    /** Eye height above the feet (standing). */
    static final double EYE = 1.62;
    /** Standing with a block chosen but nothing sent (input not ours, in the air ...) this long: it is given up. */
    static final int STAND_STALL_TICKS = 200;
    /** Vanilla walking speed, blocks per tick (no sprint). */
    static final double WALK_PER_TICK = 0.2158;
    /** Haste ticks kept beyond the walk across an uncovered stretch (the instant rule's margin is about this). */
    static final int HASTE_GATE_MARGIN = 40;
    /** A fresh beacon renewal seen by the client: 340 ticks at level 4, less a round trip. */
    static final int HASTE_FRESH = 330;
    /**
     * While Haste is uncertain ({@link Io#hasteUncertain}) a mined block's STOP waits this long after its START, so the
     * server's air arrives first if the server broke it at once (a 2b2t round trip is 2-6 ticks).
     */
    static final int HASTE_UNSURE_STOP_TICKS = 8;
    /**
     * The standing phase keeps walking to the target it chose unless another one is nearer by this much (blocks):
     * two targets at the same distance must not take turns ({@link BreakRun#WALK_SWITCH_MARGIN}).
     */
    static final double WALK_SWITCH_MARGIN = 1.0;
    /**
     * A slow block (mined, not instant) is taken while the bot could still walk this far before it leaves reach (blocks):
     * the bot slides a few tenths after the walk stops, and a block taken at the last moment (0.5, before 2026-10-11)
     * was then out of reach for its STOP (live: 38 digs aborted at their budget).
     */
    static final double SLOW_PRESS = 1.5;
    /** The bot moved less than this (blocks) since the last tick: it stands still enough to START a mined block. */
    static final double STILL = 0.03;
    /** Ticks after a delayed block's expected break before the server's slot counts as free for the next one. */
    static final int SLOT_MARGIN = 2;
    /** Longest break time (ticks) of a block sent to the delayed slot. */
    static final int DELAYED_MAX_TICKS = 8;
    /** Delayed blocks in a row that never showed (no delayed break between) after which slow blocks are mined. */
    static final int DELAYED_TIMEOUTS_OFF = 3;
    /** Watchdog trips in one run after which it fails (the engine runs the step again with a new plan). */
    static final int WATCHDOG_TRIPS = 4;
    /** Moving this far (blocks, horizontally) counts as progress for the watchdog. */
    static final double WATCHDOG_MOVED = 1.0;
    /** Longest wait at a Haste zone's edge for a renewal (they come every 80 ticks). */
    static final int HASTE_WAIT_MAX = 90;
    /**
     * Distance margin (blocks) between the cheap reach test of a cell's box and the point aimed at (0.06 inside the
     * face's edge, jittered by up to 0.04): a candidate is always one {@link Io#aim} can hit, and the walk holds for
     * a target before it gets out of that range.
     */
    static final double REACH_MARGIN = 0.12;
    /** Longest a pause at a lane end waits for the blocks in reach before it stops anyway. */
    static final int PAUSE_FINISH_TICKS = 100;
    /** What the run knows of the floor's pockets is learnt afresh this often (ticks). */
    static final int POCKETS_FRESH_TICKS = 200;
    /** Walks along an escape route to bring its next wall into reach before the escape is given up. */
    static final int ESCAPE_WALKS = 2;
    /** How far (cells) a detour goal or a step off a pocket's target looks for a cell in no pocket. */
    static final int OPEN_NEAR = 3;
    /**
     * A target in a pocket is broken from a cell whose centre is at most this far (horizontally) from it: the eye 1.62
     * above the floor reaches the top face of a block 4.3 away at reach 4.5, less the bot's 0.2 off the centre.
     */
    static final double OPEN_REACH = 3.5;
    /** A wedged bot breaks the walls within this of its box (the ones it stands on or against). */
    static final double WEDGE_GROW = 0.15;
    /** Ticks in a row the edge guard holds the walk back ({@link NukeGuard}) before Baritone walks around it. */
    static final int GUARD_DETOUR_TICKS = 5;
    /**
     * {@code build.nukeMineMoving}: blocks of reach kept beyond {@link #REACH_MARGIN} where the feet are played forward
     * with the movement keys pressed (the keys' direction changes as the look turns, the walk steers back onto its
     * line): a mined block started on the move is still this far inside reach at its STOP.
     */
    static final double MOVE_MARGIN = 0.3;
    /** {@code build.nukeMineMoving}: the same for a slide without keys (velocity and friction known). */
    static final double SLIDE_MARGIN = 0.05;
    /**
     * {@code build.nukeMineMoving}: ticks of pressing the keys in a row, moving at least 90 % of the fastest tick so far,
     * after which that speed counts as the walk's top speed (the velocity is within 1 % of it after 8 ticks).
     */
    static final int SPEED_SETTLE_TICKS = 8;
    /** {@code build.nukeMineMoving}: ticks a slide is played forward (below 0.003 blocks a tick after about 10). */
    static final int SLIDE_PLAY_TICKS = 12;
    /** {@code build.nukeTour}: the tour is walked only when its walk is shorter than the serpentine's by this share. */
    static final double TOUR_MARGIN = 0.05;
    /**
     * Lambda's scheme: blocks whose STOP the server refused (or that were aborted) among the last
     * {@link #LAMBDA_FAIL_WINDOW} answered ones at which the run goes back to the vanilla timing for the rest of the
     * round. An anti-cheat cancelling its packets refuses nearly all; 2b2t's late air now and then ({@code
     * DigServer.lateAir}: a block's air a few ticks after its ack) does not count.
     */
    static final int LAMBDA_FAILS_OFF = 5;
    static final int LAMBDA_FAIL_WINDOW = 10;

    /** Settings of one run. */
    record Params(int perTick, int maxDigsPerSecond, double reach, int floor, int stopDelay, boolean sweep,
                  int laneHalfWidth, int sweepMinTargets, NukeSweep.@Nullable Axis axis, boolean swingEachStart,
                  @Nullable Predicate<Pos> hasteCovered, long watchdogTicks, boolean pairSlow, boolean pocketGuard,
                  boolean artCleared, boolean wayBreak, boolean mineMoving, double endX, double endZ, boolean tour,
                  double tourMaxDensity, boolean leftoverRoute, int instantAckTicks, boolean holdAtSpeed,
                  Lambda lambda) {
        /** Without Lambda's scheme ({@code build.nukeLambda}; {@link Lambda#OFF}). */
        Params(int perTick, int maxDigsPerSecond, double reach, int floor, int stopDelay, boolean sweep,
               int laneHalfWidth, int sweepMinTargets, NukeSweep.@Nullable Axis axis, boolean swingEachStart,
               @Nullable Predicate<Pos> hasteCovered, long watchdogTicks, boolean pairSlow, boolean pocketGuard,
               boolean artCleared, boolean wayBreak, boolean mineMoving, double endX, double endZ, boolean tour,
               double tourMaxDensity, boolean leftoverRoute, int instantAckTicks, boolean holdAtSpeed) {
            this(perTick, maxDigsPerSecond, reach, floor, stopDelay, sweep, laneHalfWidth, sweepMinTargets, axis,
                swingEachStart, hasteCovered, watchdogTicks, pairSlow, pocketGuard, artCleared, wayBreak, mineMoving,
                endX, endZ, tour, tourMaxDensity, leftoverRoute, instantAckTicks, holdAtSpeed, Lambda.OFF);
        }

        /**
         * Without {@code build.nukeLeftoverRoute}, {@code build.nukeInstantAckTicks} and {@code build.nukeHoldAtSpeed}:
         * the standing phase after the sweep, the 2-tick wait after an instant block's ack and the sweep's hold test
         * 0.25 blocks ahead, as before.
         */
        Params(int perTick, int maxDigsPerSecond, double reach, int floor, int stopDelay, boolean sweep,
               int laneHalfWidth, int sweepMinTargets, NukeSweep.@Nullable Axis axis, boolean swingEachStart,
               @Nullable Predicate<Pos> hasteCovered, long watchdogTicks, boolean pairSlow, boolean pocketGuard,
               boolean artCleared, boolean wayBreak, boolean mineMoving, double endX, double endZ, boolean tour,
               double tourMaxDensity) {
            this(perTick, maxDigsPerSecond, reach, floor, stopDelay, sweep, laneHalfWidth, sweepMinTargets, axis,
                swingEachStart, hasteCovered, watchdogTicks, pairSlow, pocketGuard, artCleared, wayBreak, mineMoving,
                endX, endZ, tour, tourMaxDensity, false, DigClient.REJECT_GRACE_TICKS, false);
        }

        /**
         * Without {@code build.nukeMineMoving} and the sweep's end point ({@code site.platforms[].nukeEndCorner}): as
         * before both.
         */
        Params(int perTick, int maxDigsPerSecond, double reach, int floor, int stopDelay, boolean sweep,
               int laneHalfWidth, int sweepMinTargets, NukeSweep.@Nullable Axis axis, boolean swingEachStart,
               @Nullable Predicate<Pos> hasteCovered, long watchdogTicks, boolean pairSlow, boolean pocketGuard,
               boolean artCleared, boolean wayBreak) {
            this(perTick, maxDigsPerSecond, reach, floor, stopDelay, sweep, laneHalfWidth, sweepMinTargets, axis,
                swingEachStart, hasteCovered, watchdogTicks, pairSlow, pocketGuard, artCleared, wayBreak, false,
                Double.NaN, Double.NaN);
        }

        /** Without {@code build.nukeTour}: the serpentine, as before. */
        Params(int perTick, int maxDigsPerSecond, double reach, int floor, int stopDelay, boolean sweep,
               int laneHalfWidth, int sweepMinTargets, NukeSweep.@Nullable Axis axis, boolean swingEachStart,
               @Nullable Predicate<Pos> hasteCovered, long watchdogTicks, boolean pairSlow, boolean pocketGuard,
               boolean artCleared, boolean wayBreak, boolean mineMoving, double endX, double endZ) {
            this(perTick, maxDigsPerSecond, reach, floor, stopDelay, sweep, laneHalfWidth, sweepMinTargets, axis,
                swingEachStart, hasteCovered, watchdogTicks, pairSlow, pocketGuard, artCleared, wayBreak, mineMoving,
                endX, endZ, false, 0);
        }

        Params {
            perTick = Math.max(1, Math.min(8, perTick));
            maxDigsPerSecond = Math.max(perTick, maxDigsPerSecond);
            floor = Math.max(0, floor);
            stopDelay = Math.max(0, Math.min(DigClient.DESTROY_DELAY, stopDelay));
            laneHalfWidth = Math.max(1, Math.min(6, laneHalfWidth));
            instantAckTicks = Math.max(DigClient.REJECT_GRACE_TICKS,
                Math.min(DigClient.INSTANT_ACK_MAX_TICKS, instantAckTicks));
            if (lambda == null) lambda = Lambda.OFF;
        }
    }

    /**
     * Lambda's OldGrim break scheme ({@code build.nukeLambda};
     * {@link DigClient#startLambda}). {@code perTick}: instant STARTs per tick (1..59, Lambda's {@code breaksPerTick}
     * 59); {@code fakeStarts}: the out-of-world START after each non-instant START; {@code burst}: the 22 fake STARTs
     * at 7 ticks of mining, which let the next non-instant block start without the 6-tick delay; {@code doubleBreak}:
     * the block just started goes to the server's delayed slot and a second one is mined meanwhile.
     */
    record Lambda(boolean on, int perTick, boolean fakeStarts, boolean burst, boolean doubleBreak) {
        static final Lambda OFF = new Lambda(false, 8, true, true, true);

        Lambda {
            perTick = Math.max(1, Math.min(59, perTick));
        }
    }

    /** The game for one run. */
    interface Io<S> extends DigClient.Io<S> {
        boolean isLoaded(Pos p);

        /** The server's block at {@code p} (as cached; never predicted). */
        BlockSpec block(Pos p);

        /** Why {@code p} must never be broken (a protected id, an unbreakable block), or null. */
        @Nullable String refusal(Pos p);

        /** Tool group and speed (on the ground) of breaking {@code p} with what {@link ToolChoice} picks, or null. */
        BreakPairs.@Nullable Info info(Pos p);

        /** What to hold for {@code p} now, with the tools' current wear. */
        BreakRun.Tool tool(Pos p);

        /** True if the main hand holds what {@code t} says now (a hotbar select may happen at once); else switching. */
        boolean hold(BreakRun.Tool t);

        /** The {@link BreakPairs.Info#group} the main hand serves now. */
        String heldGroup();

        double feetX();

        double feetY();

        double feetZ();

        double eyeY();

        boolean onGround();

        /** Ticks the Haste effect has left (the client's estimate), -1 without Haste. */
        int hasteTicksLeft();

        /**
         * Haste is there but about to run out ({@code build.nukeHasteMarginTicks}): the speeds leave it out, while the
         * server may still count it (a block mined then may break on its START).
         */
        boolean hasteUncertain();

        /** A rotation from the eye now that hits a visible face of {@code p} within reach (the nearest point), or null. */
        @Nullable S aim(Pos p);

        /**
         * {@link #aim} from an eye at (ex, ey, ez): where the eye will be after this tick's movement, which the rotation
         * goes out with ({@code build.nukeMineMoving}). Default: {@link #aim}.
         */
        default @Nullable S aimFrom(Pos p, double ex, double ey, double ez) {
            return aim(p);
        }

        /** The rotation in effect now (sent with the last movement packet), if it hits {@code shot}'s block within reach. */
        @Nullable S inEffect(S shot);

        /** This tick's input: turn to {@code look} (null: keep the rotation) and walk towards (dx, dz) (0, 0: still). */
        void input(@Nullable S look, double dx, double dz);

        /** Last tick's input request was executed and this tick's is pending; no container open. */
        boolean canAct();

        /** The bot's box may enter floor cell {@code cell} (feet level): nothing above the step height, a floor under it. */
        boolean walkable(Pos cell);

        /**
         * Why the box must not go (deeper) into feet cell {@code cell}: a no-go zone, water or lava, or open with a drop
         * of two blocks or more below ({@link EdgeDrop#at}); null if it may, and always with {@code build.nukeEdgeGuard}
         * off ({@link NukeGuard}).
         */
        @Nullable String hazard(Pos cell);

        /** The yaw (degrees) {@link #input} with {@code look} chooses its movement keys for; NaN: it walks exactly. */
        double keyYaw(@Nullable S look);

        /** The bot's horizontal velocity (x, z; blocks a tick), which its next movement starts from. */
        double[] velocity();

        /**
         * {@code p} holds a block that is no target which the run may break to get out of a pocket
         * ({@code build.nukePocketGuard}): one the clear removes anyway (the platform's clearable blocks), not protected,
         * in no protected region, and the cell walkable once it is gone.
         */
        boolean escapable(Pos p);

        /**
         * {@code c} holds a full cube the bot and Baritone climb onto and walk on (art that is no target: outside the
         * NUKE, terracotta and the like), with room on top: no wall for the pocket guard.
         */
        boolean climbable(Pos c);

        /** Walks (Baritone) until a face of {@code p} is in reach and sight (or, unloaded, near it). */
        TaskStatus approach(Pos p);

        /** Walks (Baritone) until the feet are within {@code range} blocks (horizontally) of {@code goal}'s cell. */
        TaskStatus walkTo(Pos goal, int range);

        String walkError();

        void stopWalking();

        void log(String line);
    }

    /**
     * A target in reach this tick and how urgent it is (lower first); {@code room}: how far the bot may still walk along
     * the walk before it leaves reach (standing: reach left).
     */
    private record Cand(Pos pos, double urgency, boolean blocker, double room) { }

    private enum Walk { SWEEP, BARITONE_SWEEP, STAND, BARITONE_STAND }

    private final Io<S> io;
    private final Params p;
    private final Consumer<Pos> broken;
    /** Asked once at each lane end: why to stop there (the engine's upkeep), or null to go on. */
    private final @Nullable Supplier<@Nullable String> pause;
    /** Waypoint indices that end a lane ({@link NukeSweep.Plan#laneEnds}); none on the leftover route. */
    private Set<Integer> laneEnds;
    /** Stopping at a lane end for this reason (null: not pausing), since this tick. */
    private @Nullable String pausing;
    private long pausingSince;
    private final DigClient<S> dig;
    private final Set<Pos> remaining;
    private final int total;
    private final int[] levels;
    private final List<String> refused = new ArrayList<>();
    private final List<String> failed = new ArrayList<>();
    private final Map<Pos, Integer> attempts = new HashMap<>();
    /** Left for the standing phase: held the walk too long, or retried. */
    private final Set<Pos> later = new HashSet<>();
    private final Map<Pos, Long> holdingSince = new HashMap<>();
    /** Blocks STARTed while the look was on another block, until answered. */
    private final Set<Pos> offLook = new HashSet<>();
    private int perTick;
    private int offLookRefusals;
    /** Outcomes of the last answered off-look STARTs (true: refused), oldest first. */
    private final java.util.ArrayDeque<Boolean> offLookOutcomes = new java.util.ArrayDeque<>();
    private String failure = "";
    private int done;
    private long ticks;
    private boolean finished;
    private boolean summaryLogged;
    private boolean holdForPark;
    private int standStill;
    /** Mining slow blocks one after the other while the walk holds (a burst), until none is left in reach. */
    private boolean slowBurst;
    /** Slow blocks are mined, not sent to the delayed slot, for the rest of the run (delayed ones never showed). */
    private boolean pairSlowOff;
    private int delayedTimeoutsInRow;
    private int delayedChosen;
    /** Lambda's scheme is off for the rest of the run: its STOPs were refused {@link #LAMBDA_FAILS_OFF} times in a row. */
    private boolean lambdaOff;
    /** Lambda STOPs (mined or one-tick) not answered yet, and the outcomes of the last answered ones (true: failed). */
    private final Set<Pos> lambdaStops = new HashSet<>();
    private final java.util.ArrayDeque<Boolean> lambdaOutcomes = new java.util.ArrayDeque<>();
    /** Feet position of the previous tick (whether the bot still slides). */
    private double prevFx = Double.NaN;
    private double prevFz = Double.NaN;
    /** The standing phase's last walk target, and how often its walk ended "arrived" without the block in reach. */
    private @Nullable Pos lastApproach;
    private int approachArrived;
    /** The walk right next to {@link #lastApproach} after it "arrived" out of reach (a cell beside a pocket's target). */
    private @Nullable Pos besideGoal;
    /** Watchdog: last tick with a block broken or the bot WATCHDOG_MOVED from {@link #anchorX}, {@link #anchorZ}. */
    private long progressTick;
    private double anchorX = Double.NaN;
    private double anchorZ = Double.NaN;
    private int watchdogTrips;
    /** Waiting at a Haste zone's edge since (-1: not waiting); the cell where a wait timed out (no new wait there). */
    private long hasteWaitSince = -1;
    private @Nullable Pos hasteWaitGaveUp;
    private long hasteWaitTicks;
    private int hasteWaits;

    // pockets (build.nukePocketGuard): null when off or the targets lie on several levels
    private final @Nullable NukePockets pockets;
    private long pocketsAt;
    /** Breaking out of a pocket: the walls left to break, then the walk out. */
    private @Nullable Escape escape;
    private int escapes;
    /** The pocket cell where no way out was found (logged once). */
    private @Nullable Pos noEscapeAt;
    /** Targets in pockets the run tried to reach through their walls ({@link #tryOpen}, once each). */
    private final Map<Pos, Integer> opened = new HashMap<>();
    /** Range of the next Baritone walk to {@link #walkGoal} (0 for a walk out of or off a pocket). */
    private int walkRange = 1;
    /** The last target the bot stepped off ({@link #besideFree}), once per target. */
    private @Nullable Pos steppedOffFor;

    // walking
    private Walk walk;
    /** The plan being walked: the sweep's, then the leftover route's ({@code build.nukeLeftoverRoute}). */
    private NukeSweep.@Nullable Plan plan;
    /** The sweep's own plan ({@link #plan()}). */
    private final NukeSweep.@Nullable Plan sweepPlan;

    // build.nukeLeftoverRoute
    /** The route over what the sweep left was planned (once a run), and it is being walked now. */
    private boolean leftoversPlanned;
    private boolean onLeftoverRoute;
    /** Targets left when the sweep ended, those of them whose air already showed, and the route's stand cells. */
    private int leftovers;
    private int leftoversAir;
    private int leftoverStands;
    /** Targets broken on the route; ticks from its start to its end (-1: not started); its blocks of walk. */
    private int leftoversRouted;
    private long leftoverFrom = -1;
    private long leftoverTicks;
    private double leftoverWalk;
    /** The route's waypoint whose leg was looked at ({@link #legClear}), and the legs walked with Baritone. */
    private int legWp = -1;
    private int leftoverBaritoneLegs;
    /** Tests ({@link #leftoversNow}): a route though the sweep broke fewer than it left. */
    private boolean leftoversUnswept;
    /**
     * Kept art: the route from the first tick instead of the sweep ({@link #KEPT_ROUTE_SHARE}); stands on top of the kept
     * full blocks too, the pause asked at each. {@link #keptShare}: the share of the targets' box holding one.
     */
    private final boolean routeFirst;
    private final double keptShare;
    /** The route's stand being stood at ({@link #standAt}) since this tick (-1: none). */
    private int standWp = -1;
    private long standSince;
    /** Blocks given up as refused (or unanswered) whose air showed later, out of reach: counted broken then. */
    private int lateAir;
    /** {@code build.nukeTour}: which route was chosen and why (logged on the first tick), null without the switch. */
    private @Nullable String routeNote;
    private boolean routeLogged;
    /** {@code build.nukeTour}: lane segments skipped because their targets had all broken before the walk got there. */
    private int segmentsSkipped;
    /** Where the bot stood when the tour last skipped to waypoint {@link #skipWp} (the walk there starts from it). */
    private double @Nullable [] skipFrom;
    private int skipWp = -1;
    private int wp;
    private int detoursHere;
    /**
     * Sidestep around a block that is no target on the walking line: the line moves this many columns to the side (left
     * of the walk is +1) until the walk is {@link #sideUntil} blocks along the segment, then back; only on segment
     * {@link #sideWp}.
     */
    private int sideOffset;
    private double sideUntil;
    private int sideWp = -1;
    private int sidesteps;
    /** Edge guard ({@link NukeGuard}): the fastest the feet moved in one tick, and the feet of the tick before. */
    private double topSpeed;
    private double guardFx = Double.NaN;
    private double guardFz = Double.NaN;
    private long guardAt = -1;
    /** Ticks in a row the guard held the walk back; ticks it turned the keys, ticks it held them back. */
    private int guardHeld;
    private int guardTurns;
    private int guardHolds;
    /** The hazard the guard last logged (once each). */
    private @Nullable String guardLogged;
    /** This tick's {@link Io#hazard} answers ("" for none). */
    private final Map<Pos, String> hazardSeen = new HashMap<>();
    private long hazardSeenAt = -1;
    private @Nullable Pos walkGoal;
    private @Nullable Pos approachTarget;
    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;
    private long movedAt;
    private boolean pressedLastTick;
    /** Closest the feet came to waypoint {@link #progressWp}, and walking ticks since they last came closer. */
    private int progressWp = -1;
    private double progressBest;
    private int progressIdle;
    private final int floorY;

    // lingering delayed block of an earlier double break
    private @Nullable Pos lingeringPos;
    private long lingeringUntil;

    // statistics
    private int maxInTick;
    /** Dig packets sent (STARTs, STOPs) up to the end of each of the last 20 ticks, for the rolling cap. */
    private final long[] digWindow = new long[20];
    private long walkTicks;
    private long holdTicks;
    private long backlogTicks;
    private long minedTicks;
    private int selects;
    private int detours;
    private int approaches;
    private int sweptBroken;
    /**
     * Blocks that are no target the sweep breaks where they stand in its way ({@link #breakInWay}): art the clear removes
     * anyway, while the art is cleared afterwards. Never reported as broken targets; dropped when the sweep ends.
     */
    private final Set<Pos> wayTargets = new HashSet<>();
    /** Way blocks that could not be broken: walls from then on (a detour, as before). */
    private final Set<Pos> wayGivenUp = new HashSet<>();
    private int wayBroken;
    private final Map<String, int[]> byId = new java.util.LinkedHashMap<>();

    // build.nukeMineMoving
    /** The block being mined was started in the sweep with {@code build.nukeMineMoving}: the walk may go on. */
    private boolean movingDig;
    /** Ticks in a row with the movement keys pressed, and whether {@link #topSpeed} is the walk's top speed now. */
    private int pressedRun;
    private boolean speedKnown;
    /** How far the feet moved since the guard's last tick (NaN: no tick before). */
    private double lastMoved = Double.NaN;
    /** How far the feet moved in the last tick, as this tick's sweep (or mining) step found (NaN: unknown). */
    private double lastStep = Double.NaN;
    private int movingStarts;
    private int slideStarts;
    private long mineWalkTicks;
    /**
     * Ticks the sweep held (no block being mined) by reason: a block in the way, a target it must stop for, the backlog,
     * the Haste gate, the edge guard; and while a block was mined: no way on (a waypoint near, a cell ahead), the block
     * itself would leave reach, a target to stop for, the backlog or the Haste gate.
     */
    private final long[] holdsBy = new long[5];
    private final long[] mineHoldsBy = new long[5];

    NukeRun(List<Pos> targets, Params params, Io<S> io, Consumer<Pos> broken) {
        this(targets, params, io, broken, null);
    }

    /** {@code pause}: asked at each lane end of the sweep; a reason ends the run there ({@link BotActions#BREAK_PAUSED}). */
    NukeRun(List<Pos> targets, Params params, Io<S> io, Consumer<Pos> broken,
            @Nullable Supplier<@Nullable String> pause) {
        this.io = io;
        this.p = params;
        this.perTick = params.lambda().on() ? params.lambda().perTick() : params.perTick();
        this.broken = broken;
        this.pause = pause;
        this.dig = new DigClient<>(io, false);
        dig.stopDelay(params.stopDelay());
        dig.instantAckTicks(params.instantAckTicks());
        if (params.lambda().on()) dig.lambdaOptions(params.lambda().fakeStarts(), params.lambda().burst());
        this.remaining = new LinkedHashSet<>(targets);
        this.total = remaining.size();
        TreeSet<Integer> ys = new TreeSet<>();
        for (Pos t : remaining) ys.add(t.y());
        this.levels = ys.stream().mapToInt(Integer::intValue).toArray();
        this.floorY = levels.length == 1 ? levels[0] : Integer.MIN_VALUE;
        this.pockets = params.pocketGuard() && floorY != Integer.MIN_VALUE ? new NukePockets(new FloorView()) : null;
        boolean sweep = params.sweep() && levels.length == 1 && total >= params.sweepMinTargets();
        this.keptShare = sweep && !params.artCleared() ? keptShare(remaining, floorY) : 0;
        this.routeFirst = sweep && !params.artCleared() && params.leftoverRoute() && total <= ROUTE_MAX
            && keptShare >= KEPT_ROUTE_SHARE;
        this.walk = sweep ? Walk.SWEEP : Walk.STAND;
        if (sweep) {
            Predicate<Pos> cov = params.hasteCovered();
            int fy = floorY;
            NukeSweep.Axis axis = NukeSweep.axis(remaining, params.axis(), params.laneHalfWidth(),
                cov == null ? null : (x, z) -> cov.test(new Pos(x, fy, z)));
            NukeSweep.Plan serp = NukeSweep.plan(remaining, axis, params.laneHalfWidth(), 2, io.feetX(), io.feetZ(),
                params.endX(), params.endZ());
            this.plan = params.tour() ? route(serp) : serp;
            this.wp = 0;
        }
        NukeSweep.Plan pl = plan;
        this.sweepPlan = pl;
        this.laneEnds = pl == null ? Set.of() : pl.laneEnds();
    }

    /**
     * {@code build.nukeTour}: the tour over lane segments ({@link NukeSweep#tour}, the shorter of the two axes) when the
     * targets fill at most {@code tourMaxDensity} of their box and its whole walk (feet to the start, the tour, its end
     * to the sweep's end point) is shorter than the serpentine's by {@link #TOUR_MARGIN}; else the serpentine. Either
     * way one line says which and both lengths ({@link #routeNote}).
     */
    private NukeSweep.Plan route(NukeSweep.Plan serp) {
        double fx = io.feetX();
        double fz = io.feetZ();
        double serpWalk = serp.walk(fx, fz, p.endX(), p.endZ());
        double density = NukeSweep.density(remaining);
        if (density > p.tourMaxDensity()) {
            routeNote = String.format(Locale.ROOT, "sweep of %d lanes (%.0f blocks of walk): the targets fill %.0f %% of"
                + " their box, above the tour's %.0f %%", serp.lanes().size(), serpWalk, 100 * density,
                100 * p.tourMaxDensity());
            return serp;
        }
        int gap = 2 * p.laneHalfWidth() + 1;
        NukeSweep.Plan best = null;
        double bestWalk = Double.MAX_VALUE;
        for (NukeSweep.Axis a : NukeSweep.Axis.values()) {
            NukeSweep.Plan t = NukeSweep.tour(remaining, a, p.laneHalfWidth(), 2, gap, fx, fz, p.endX(), p.endZ());
            double walk = t.walk(fx, fz, p.endX(), p.endZ());
            if (walk < bestWalk - 1e-9) {
                best = t;
                bestWalk = walk;
            }
        }
        if (best == null || bestWalk >= serpWalk * (1 - TOUR_MARGIN)) {
            routeNote = String.format(Locale.ROOT, "sweep of %d lanes (%.0f blocks of walk): a tour would walk %.0f",
                serp.lanes().size(), serpWalk, bestWalk);
            return serp;
        }
        routeNote = String.format(Locale.ROOT, "tour of %d lane segments along %s (%.0f blocks of walk) instead of"
            + " the sweep of %d lanes (%.0f)", best.lanes().size(), best.axis(), bestWalk, serp.lanes().size(),
            serpWalk);
        return best;
    }

    /**
     * Kept art: the share of the cells of {@code targets}' box on level {@code y} (loaded ones) that hold a block that is
     * no target and that the box cannot walk into (the kept full blocks; a kept carpet is walked over).
     */
    private double keptShare(Set<Pos> targets, int y) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Pos t : targets) {
            minX = Math.min(minX, t.x());
            maxX = Math.max(maxX, t.x());
            minZ = Math.min(minZ, t.z());
            maxZ = Math.max(maxZ, t.z());
        }
        long cells = 0;
        long kept = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Pos c = new Pos(x, y, z);
                if (!io.isLoaded(c)) continue;
                cells++;
                if (!targets.contains(c) && !io.isAir(c) && !io.walkable(c)) kept++;
            }
        }
        return cells == 0 ? 0 : (double) kept / cells;
    }

    /** Kept art: the route from the start instead of the sweep ({@link #KEPT_ROUTE_SHARE}). */
    boolean routeFirst() {
        return routeFirst;
    }

    String failure() {
        return failure;
    }

    /** Targets broken while the sweep walked. */
    int swept() {
        return sweptBroken;
    }

    /** Blocks that were no target, broken where they stood in the sweep's way ({@link #breakInWay}). */
    int wayBroken() {
        return wayBroken;
    }

    int done() {
        return done;
    }

    int total() {
        return total;
    }

    DigClient<S> dig() {
        return dig;
    }

    /** The sweep plan (null when the targets are done standing). */
    NukeSweep.@Nullable Plan plan() {
        return sweepPlan;
    }

    /** The leftover route ({@code build.nukeLeftoverRoute}) once planned, else null. */
    NukeSweep.@Nullable Plan leftoverRoute() {
        return leftoversPlanned && plan != sweepPlan ? plan : null;
    }

    /** Targets left when the sweep ended ({@code build.nukeLeftoverRoute}), and those whose air showed by then. */
    int leftovers() {
        return leftovers;
    }

    int leftoversAir() {
        return leftoversAir;
    }

    /** Ticks from the end of the sweep to the end of the run (0 when no sweep ended). */
    long leftoverTicks() {
        return leftoverFrom < 0 ? 0 : (finished ? leftoverTicks : ticks - leftoverFrom);
    }

    /**
     * Tests: ends the sweep before the first tick, as if it had left every target where it is: the leftover route
     * ({@code build.nukeLeftoverRoute}; though the sweep broke none) or the standing phase from here.
     */
    void leftoversNow() {
        if (ticks != 0 || walk != Walk.SWEEP) return;
        leftoversUnswept = true;
        endSweep();
    }

    /** STARTs allowed per tick now (drops to 1 after {@link #OFF_LOOK_REFUSALS}). */
    int perTick() {
        return perTick;
    }

    /** The configured cap of instant STARTs per tick: Lambda's while it is on, else {@code build.nukeStartsPerTick}. */
    private int basePerTick() {
        return p.lambda().on() ? p.lambda().perTick() : p.perTick();
    }

    /** Lambda's OldGrim scheme is in use ({@code build.nukeLambda}, not switched off by refusals). */
    boolean lambdaOn() {
        return p.lambda().on() && !lambdaOff;
    }

    /** Server mining-clock ticks per client tick ({@link DigClient#serverClock(double)}); 1 by default. */
    void serverClock(double serverTicksPerClientTick) {
        dig.serverClock(serverTicksPerClientTick);
    }

    /** Between blocks, nothing being mined, no STOP unanswered, standing: the engine may park the run (eat). */
    boolean canPark() {
        return !dig.digging() && !dig.stopPending() && !lingeringPending() && walkGoal == null && approachTarget == null;
    }

    /** See {@link BreakRun#lingering}: a delayed block of an earlier double break; no tool switch until it shows. */
    void lingering(Pos pos, long ticksLeft) {
        lingeringPos = pos;
        lingeringUntil = ticks + Math.max(0, ticksLeft);
    }

    private boolean lingeringPending() {
        Pos l = lingeringPos;
        if (l == null) return false;
        if (ticks >= lingeringUntil || (io.isLoaded(l) && io.isAir(l))) {
            lingeringPos = null;
            return false;
        }
        return true;
    }

    /** The engine wants to park: no new block, no walking; parks once {@link #canPark}. */
    void holdForPark(boolean on) {
        holdForPark = on;
    }

    /** After a park: the walk goes on from where the bot stands. */
    void resume() {
        lastX = Double.NaN;
        walkGoal = null;
        approachTarget = null;
    }

    /**
     * A delayed block of this run still unanswered (to hand on when the task ends, as {@link BreakRun#pendingDelayed}),
     * and the client ticks left until its deadline; null if none.
     */
    java.util.Map.@Nullable Entry<Pos, Long> pendingDelayed(long now) {
        DigClient.Flight f = dig.pendingDelayed();
        return f == null ? null : java.util.Map.entry(f.pos, Math.max(0, f.deadline - now));
    }

    /** The task ends: ABORT the block being mined, as the vanilla client does when the button is released. */
    void release() {
        dig.abortActive();
        io.stopWalking();
        if (!summaryLogged) log("cancelled");
    }

    TaskStatus tick(long now) {
        ticks++;
        if (!routeLogged && routeNote != null) {
            routeLogged = true;
            io.log("Fast nuke: " + routeNote + " (build.nukeTour)");
        }
        digWindow[(int) (ticks % digWindow.length)] = digsSent();
        for (DigClient.Event e : dig.poll(now)) handle(e);
        lateAirCheck();
        if (!failure.isEmpty()) return TaskStatus.FAILED;
        if (dig.digging()) {
            minedTicks++;
            DigClient.Active<S> a = dig.active();
            if (movingDig && walk == Walk.SWEEP && !holdForPark && p.mineMoving()) {
                mineWalk(now, a); // build.nukeMineMoving: the walk goes on while it is mined
            } else {
                // aimed afresh every tick: the bot still slides a few tenths after the walk stops, and the look of the
                // START tick may then miss the block, which held its STOP back until the dig's budget ran out (live
                // 2026-10-11: 9 ticks of mining per redstone block instead of 4, 38 aborted)
                S fresh = io.aim(a.pos);
                io.input(fresh != null ? fresh : a.shot, 0, 0);
            }
            if (a.lambda) {
                lambdaTick(now, a);
                if (!dig.digging()) movingDig = false;
                return failure.isEmpty() ? TaskStatus.RUNNING : TaskStatus.FAILED;
            }
            dig.tickActive(now, io.inEffect(a.shot), io.canAct());
            if (!dig.digging()) movingDig = false;
            return TaskStatus.RUNNING;
        }
        movingDig = false;
        if (holdForPark) {
            io.stopWalking();
            walkGoal = null;
            approachTarget = null;
            io.input(null, 0, 0);
            progressTick = ticks; // the engine's park, not ours
            return TaskStatus.RUNNING;
        }
        // stopping at a lane end for the engine's upkeep (a mend) counts as parked, not as a stall
        if (pausing != null) progressTick = ticks;
        if (watchdog()) return failure.isEmpty() ? TaskStatus.RUNNING : TaskStatus.FAILED;
        NukePockets pk = pockets;
        if (pk != null && ticks - pocketsAt >= POCKETS_FRESH_TICKS) {
            pk.forget(); // blocks that came or went without the run (a pocket closed by something else)
            pocketsAt = ticks;
        }
        if (walkGoal != null || approachTarget != null) {
            TaskStatus s = baritone();
            if (s != null) return s;
        }
        if (escape != null) return escapeTick(now);
        if (remaining.isEmpty()) {
            if (dig.waiting()) {
                io.input(null, 0, 0);
                return TaskStatus.RUNNING;
            }
            return finish();
        }
        if (pausing != null) return pauseTick(now);
        if (routeFirst && !leftoversPlanned && walk == Walk.SWEEP) {
            startRoute();
            return afterSweep(now);
        }
        if (walk == Walk.SWEEP) return sweepTick(now);
        return standTick(now);
    }

    /**
     * No block broken and the bot not {@link #WATCHDOG_MOVED} from where it was for {@code watchdogTicks}: one log line,
     * then a new plan. The sweep ends (the targets left are done standing, each walked to with Baritone); standing, the
     * target the run is stuck on is given up for this round. After {@link #WATCHDOG_TRIPS} trips the run fails, and the
     * engine runs the step again. True when it tripped this tick (nothing else is done this tick).
     */
    private boolean watchdog() {
        double fx = io.feetX();
        double fz = io.feetZ();
        if (Double.isNaN(anchorX) || Math.hypot(fx - anchorX, fz - anchorZ) >= WATCHDOG_MOVED) {
            anchorX = fx;
            anchorZ = fz;
            progressTick = ticks;
        }
        if (p.watchdogTicks() <= 0 || ticks - progressTick < p.watchdogTicks()) return false;
        watchdogTrips++;
        String state = (walk == Walk.SWEEP || walk == Walk.BARITONE_SWEEP ? "sweeping, waypoint " + wp : "standing")
            + (walkGoal != null ? ", walking to " + walkGoal : "") + (approachTarget != null ? ", walking to target "
            + approachTarget : "") + (dig.digging() ? ", mining" : "") + ", " + remaining.size() + " left";
        String head = String.format(Locale.ROOT, "Fast nuke watchdog: no block broken and no movement for %d ticks at"
            + " %.1f,%.1f (%s)", ticks - progressTick, fx, fz, state);
        progressTick = ticks;
        dig.abortActive();
        io.stopWalking();
        Pos stuck = approachTarget != null ? approachTarget : lastApproach;
        walkGoal = null;
        approachTarget = null;
        if (watchdogTrips >= WATCHDOG_TRIPS) {
            io.log(head + "; stopping this round after " + watchdogTrips + " trips");
            failure = "no progress (watchdog tripped " + watchdogTrips + " times)";
            log("stopped");
            return true;
        }
        if (escape == null && tryEscape("no progress for " + p.watchdogTicks() + " ticks")) {
            io.log(head + "; walled in, breaking out");
            return true;
        }
        if (escape == null && tryUnwedge()) {
            io.log(head + "; wedged, breaking the walls against it");
            return true;
        }
        if (walk == Walk.SWEEP || walk == Walk.BARITONE_SWEEP) {
            io.log(head + "; ending the " + (onLeftoverRoute ? "leftover route" : "sweep") + ", the rest is done standing");
            leftoversPlanned = true; // the own walk got stuck: Baritone to each target, as before
            endSweep();
            return true;
        }
        if (stuck == null || !remaining.contains(stuck)) stuck = nearest(fx, io.eyeY(), fz);
        if (stuck != null) {
            io.log(head + "; leaving " + stuck + " for later");
            giveUp(stuck, "could not break " + io.block(stuck).id() + " at " + stuck + " (watchdog)");
        } else {
            io.log(head);
        }
        lastApproach = null;
        approachArrived = 0;
        return true;
    }

    /**
     * The remaining target nearest to the eye (not in flight), or null. With {@code build.nukeLeftoverRoute} one whose
     * air already shows counts as broken instead (no walk to it).
     */
    private @Nullable Pos nearest(double ex, double ey, double ez) {
        if (routeOn()) {
            List<Pos> air = null;
            for (Pos t : remaining) {
                if (dig.inFlight(t) || !io.isLoaded(t) || !io.isAir(t)) continue;
                if (air == null) air = new ArrayList<>();
                air.add(t);
            }
            if (air != null) for (Pos t : air) markBroken(t);
        }
        Pos next = null;
        double best = Double.MAX_VALUE;
        for (Pos t : remaining) {
            if (dig.inFlight(t)) continue;
            double d = boxDistSq(ex, ey, ez, t);
            if (d < best) {
                best = d;
                next = t;
            }
        }
        return next;
    }

    /** Watchdog trips so far. */
    int watchdogTrips() {
        return watchdogTrips;
    }

    // ------------------------------------------------------------------------------------------------ outcomes

    private void handle(DigClient.Event e) {
        Pos pos = e.pos();
        boolean off = offLook.remove(pos);
        if (off) {
            offLookOutcomes.addLast(e.outcome() != DigClient.Outcome.BROKEN);
            if (offLookOutcomes.size() > OFF_LOOK_WINDOW) offLookOutcomes.removeFirst();
        }
        if (lambdaStops.remove(pos)) {
            lambdaOutcomes.addLast(e.outcome() != DigClient.Outcome.BROKEN);
            if (lambdaOutcomes.size() > LAMBDA_FAIL_WINDOW) lambdaOutcomes.removeFirst();
            int failed = 0;
            for (boolean f : lambdaOutcomes) if (f) failed++;
            if (failed >= LAMBDA_FAILS_OFF && !lambdaOff) {
                lambdaOff = true;
                perTick = Math.min(perTick, p.perTick());
                io.log("Fast nuke: " + failed + " of the last " + lambdaOutcomes.size() + " blocks broken with Lambda's"
                    + " scheme were refused; the vanilla timing for the rest of this round");
            }
        }
        if (e.kind() == DigClient.Kind.DELAYED) {
            if (e.outcome() == DigClient.Outcome.BROKEN) {
                delayedTimeoutsInRow = 0;
            } else if (++delayedTimeoutsInRow >= DELAYED_TIMEOUTS_OFF && !pairSlowOff) {
                pairSlowOff = true;
                io.log("Fast nuke: " + delayedTimeoutsInRow + " delayed breaks in a row never showed; slow blocks are"
                    + " mined one by one for the rest of this round");
            }
        }
        switch (e.outcome()) {
            case BROKEN -> markBroken(pos);
            case REFUSED, UNKNOWN, ABORTED, TIMED_OUT -> {
                if (off) offLookRefusals++;
                if (off && perTick > 1 && refusedOffLook() >= OFF_LOOK_REFUSALS) {
                    perTick = 1;
                    io.log("Fast nuke: " + refusedOffLook() + " of the last " + offLookOutcomes.size() + " blocks the"
                        + " look was not on were refused; one START per tick for the rest of this round");
                }
                int n = attempts.merge(pos, 1, Integer::sum);
                Escape esc = escape;
                if (n >= MAX_ATTEMPTS && esc != null && esc.left.contains(pos)) {
                    abandonEscape("could not break " + io.block(pos).id() + " at " + pos + " ("
                        + e.outcome().name().toLowerCase(Locale.ROOT) + ")");
                }
                if (n >= MAX_ATTEMPTS && wayTargets.remove(pos)) {
                    remaining.remove(pos);
                    wayGivenUp.add(pos);
                    later.remove(pos);
                    pocketsChanged();
                } else if (n >= MAX_ATTEMPTS && remaining.remove(pos)) {
                    later.remove(pos);
                    pocketsChanged();
                    failed.add("could not break " + io.block(pos).id() + " at " + pos + " ("
                        + e.outcome().name().toLowerCase(Locale.ROOT) + ")");
                }
            }
        }
    }

    /** Refused among the last answered off-look STARTs. */
    private int refusedOffLook() {
        int n = 0;
        for (boolean r : offLookOutcomes) if (r) n++;
        return n;
    }

    private void markBroken(Pos pos) {
        Escape e = escape;
        if (e != null && e.left.remove(pos)) {
            progressTick = ticks;
            return;
        }
        later.remove(pos);
        holdingSince.remove(pos);
        if (wayTargets.remove(pos)) {
            remaining.remove(pos);
            wayBroken++;
            progressTick = ticks;
            return;
        }
        if (!remaining.remove(pos)) return;
        done++;
        progressTick = ticks;
        if (onLeftoverRoute) leftoversRouted++;
        else if (walk == Walk.SWEEP || walk == Walk.BARITONE_SWEEP) sweptBroken++;
        broken.accept(pos);
    }

    private void giveUp(Pos pos, String why) {
        if (wayTargets.remove(pos)) {
            wayGivenUp.add(pos);
            remaining.remove(pos);
            later.remove(pos);
            holdingSince.remove(pos);
            pocketsChanged();
            return;
        }
        failed.add(why);
        remaining.remove(pos);
        later.remove(pos);
        holdingSince.remove(pos);
        pocketsChanged();
    }

    /** A target left without being broken is a wall from now on: what the run knows of the pockets is learnt afresh. */
    private void pocketsChanged() {
        NukePockets pk = pockets;
        if (pk != null) pk.forget();
    }

    // ---------------------------------------------------------------------------------------------- candidates

    /** Squared distance from (ex, ey, ez) to the nearest point of the unit cell {@code c}. */
    static double boxDistSq(double ex, double ey, double ez, Pos c) {
        double dx = Math.max(Math.max(c.x() - ex, 0), ex - (c.x() + 1));
        double dy = Math.max(Math.max(c.y() - ey, 0), ey - (c.y() + 1));
        double dz = Math.max(Math.max(c.z() - ez, 0), ez - (c.z() + 1));
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Targets in reach of the eye now, not in flight; air ones count as broken, protected ones are dropped, fluids given
     * up. {@code dir} (unit, may be 0) orders them: blockers first, then by how far the bot can still walk before each
     * leaves reach (standing: nearest first).
     */
    private List<Cand> candidates(double dx, double dz, Set<Pos> blockers, Map<Pos, Double> soon,
                                  boolean skipLater) {
        double fx = io.feetX();
        double fz = io.feetZ();
        double ex = fx;
        double ey = io.eyeY();
        double ez = fz;
        double r = p.reach() - REACH_MARGIN;
        double r2 = r * r;
        int x0 = (int) Math.floor(ex - r - 1);
        int x1 = (int) Math.floor(ex + r + 1);
        int z0 = (int) Math.floor(ez - r - 1);
        int z1 = (int) Math.floor(ez + r + 1);
        List<Cand> out = new ArrayList<>();
        // on top of targets: one in a pocket is never broken from there, the bot would drop into it
        List<Pos> overhead = pockets != null && io.feetY() >= floorY + ELEVATED ? boxCells(fx, fz) : List.of();
        for (int y : levels) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    Pos c = new Pos(x, y, z);
                    if (!remaining.contains(c) || dig.inFlight(c)) continue;
                    if (skipLater && later.contains(c) && !blockers.contains(c)) continue;
                    if (overhead.contains(c) && avoid(c)) continue;
                    double d2 = boxDistSq(ex, ey, ez, c);
                    if (d2 > r2) continue;
                    if (!io.isLoaded(c)) continue;
                    BlockSpec spec = io.block(c);
                    if (spec.isAir()) {
                        markBroken(c);
                        continue;
                    }
                    if (spec.id().equals("minecraft:water") || spec.id().equals("minecraft:lava")) {
                        giveUp(c, spec.id() + " at " + c + " (fluids cannot be broken)");
                        continue;
                    }
                    String no = io.refusal(c);
                    if (no != null) {
                        refused.add(no);
                        remaining.remove(c);
                        later.remove(c);
                        pocketsChanged();
                        continue;
                    }
                    boolean blocker = blockers.contains(c);
                    double urgency;
                    double room;
                    if (dx == 0 && dz == 0) {
                        urgency = Math.sqrt(d2);
                        room = r - urgency;
                    } else {
                        // how far the bot may still walk along (dx, dz) before the cell's nearest point leaves reach
                        double rx = Math.max(c.x(), Math.min(fx, c.x() + 1)) - fx;
                        double rz = Math.max(c.z(), Math.min(fz, c.z() + 1)) - fz;
                        double vy = Math.max(Math.max(c.y() - ey, 0), ey - (c.y() + 1));
                        double s = rx * dx + rz * dz;
                        double lat2 = rx * rx + rz * rz - s * s;
                        double left = r2 - lat2 - vy * vy;
                        urgency = s + Math.sqrt(Math.max(0, left));
                        room = urgency;
                    }
                    // a cell of the way ahead: due when the box gets there, less a round trip for its air
                    Double at = soon.get(c);
                    if (at != null) urgency = Math.min(urgency, at - LOOK_AHEAD - WAY_MARGIN);
                    out.add(new Cand(c, blocker ? -1e9 + urgency : urgency, blocker, room));
                }
            }
        }
        out.sort((a, b) -> Double.compare(a.urgency(), b.urgency()));
        return out;
    }

    // --------------------------------------------------------------------------------------------------- break

    /** What one tick breaks: instant STARTs (shots in order, the look on the first) or one block mined; or nothing. */
    private final class Choice {
        final List<Pos> batch = new ArrayList<>();
        final List<S> shots = new ArrayList<>();
        @Nullable Pos mine;
        @Nullable S mineShot;
        @Nullable S look;
        BreakRun.@Nullable Tool tool;
        /** The batch is held back this tick (tool switch, wear in flight): look only. */
        boolean waiting;
        /** {@link #mine} goes to the server's delayed slot (START and STOP at once, no hold), not mined. */
        boolean delayed;
        /** Lambda's double break: the block mined after {@link #mine} went to the delayed slot (same tool), or null. */
        @Nullable Pos partner;
        @Nullable S partnerShot;
        /** Uses of the tool above the floor left after the blocks in flight (MAX_VALUE by hand). */
        int room = Integer.MAX_VALUE;
    }

    /**
     * Picks this tick's blocks from {@code cands} (most urgent first). {@code urgentNonInstant}: a block that is not
     * instant is mined now when it is the most urgent (a blocker, or about to leave reach) or nothing instant is left;
     * otherwise it waits for the walk to hold. Fails the run at the tool floor (failure set).
     */
    private Choice choose(List<Cand> cands, long now, boolean walking) {
        Choice ch = new Choice();
        if (cands.isEmpty()) return ch;
        // no switch while the server holds a delayed block (an earlier task's, or ours): it breaks with the item held
        boolean lingering = lingeringPending() || dig.delayedPending();
        String held = io.heldGroup();
        // the group: the most urgent candidate's, unless the held one has instant work and nothing else is pressing
        Cand top = cands.getFirst();
        BreakRun.Tool topTool = io.tool(top.pos());
        if (topTool.kind() == ToolChoice.Kind.WORN) return worn(ch, topTool);
        BreakPairs.Info topInfo = io.info(top.pos());
        String group = topInfo == null ? held : topInfo.group();
        boolean topPressing = top.blocker() || !walking || top.urgency() < 1.5;
        if (!group.equals(held) && (lingering || !topPressing)) {
            for (Cand c : cands) {
                BreakPairs.Info i = io.info(c.pos());
                if (i != null && i.group().equals(held) && i.speed() >= 1.0) {
                    group = held;
                    break;
                }
            }
            if (lingering && !group.equals(held)) return ch; // no switch while the server holds a delayed block
        }
        Pos nonInstant = null;
        boolean anyInstant = false;
        for (Cand c : cands) {
            BreakPairs.Info i = io.info(c.pos());
            if (i == null || !i.group().equals(group)) continue;
            if (i.speed() >= 1.0 && !attempts.containsKey(c.pos())) {
                anyInstant = true;
            } else if (nonInstant == null) {
                nonInstant = c.pos();
            }
        }
        if (!anyInstant && nonInstant == null) return ch;
        // the tool for the group (re-read each tick: it wears)
        BreakRun.Tool tool = topTool;
        for (Cand c : cands) {
            BreakPairs.Info i = io.info(c.pos());
            if (i != null && i.group().equals(group)) {
                tool = io.tool(c.pos());
                break;
            }
        }
        if (tool.kind() == ToolChoice.Kind.WORN) return worn(ch, tool);
        int room = Integer.MAX_VALUE;
        if (tool.kind() == ToolChoice.Kind.TOOL) {
            room = tool.usesLeft() - dig.flights() - p.floor();
            if (room <= perTick && (dig.waiting() || now - dig.lastBrokenAt() < WEAR_GRACE_TICKS)) {
                // near the floor: the wear of the blocks in flight shows a round trip later; wait for it
                ch.waiting = true;
                return ch;
            }
            if (room <= 0) {
                ch.waiting = true;
                return ch;
            }
        }
        ch.tool = tool;
        ch.room = room;
        if (nonInstant == null) slowBurst = false;
        boolean delayable = nonInstant != null && p.pairSlow() && !pairSlowOff && !attempts.containsKey(nonInstant)
            && delaySpeed(nonInstant) && dig.delayedSlotFree(now, SLOT_MARGIN) && !lambdaOn();
        boolean mineNow = nonInstant != null && (!anyInstant || slowBurst || pressing(cands, nonInstant, walking)
            || (delayable && cands.getFirst().pos().equals(nonInstant)));
        if (mineNow) {
            S shot = io.aim(nonInstant);
            if (shot != null) {
                ch.mine = nonInstant;
                ch.mineShot = shot;
                ch.look = shot;
                ch.delayed = delayable;
                if (lambdaOn() && p.lambda().doubleBreak()) partner(ch, cands, group);
                if (!loneWaits(ch, cands, walking)) return ch;
                ch.mine = null; // the walk brings its partner into reach first
                ch.mineShot = null;
                ch.look = null;
            }
        }
        if (!anyInstant) return ch;
        int max = Math.min(perTick, room);
        for (Cand c : cands) {
            if (ch.batch.size() >= max) break;
            BreakPairs.Info i = io.info(c.pos());
            if (i == null || !i.group().equals(group) || i.speed() < 1.0 || attempts.containsKey(c.pos())) continue;
            S shot = io.aim(c.pos());
            if (shot == null) continue;
            ch.batch.add(c.pos());
            ch.shots.add(shot);
        }
        if (!ch.shots.isEmpty()) ch.look = ch.shots.getFirst();
        return ch;
    }

    /**
     * Lambda's double break: the next non-instant candidate of the same tool group in sight (not retried) is mined after
     * {@code ch.mine}, which goes to the server's delayed slot ({@link #lambdaStart}). Only when {@code ch.mine} is done
     * there within {@link #DELAYED_MAX_TICKS} and below 0.7 a tick ({@link #delaySpeed}): nothing may switch the held
     * tool until it shows. {@code stays}: the partner, mined from now on, stays in reach until its STOP the way the bot
     * moves ({@code build.nukeMineMoving}; always true standing).
     */
    private void partner(Choice ch, List<Cand> cands, String group) {
        partner(ch, cands, group, q -> true);
    }

    private void partner(Choice ch, List<Cand> cands, String group, Predicate<Pos> stays) {
        ch.partner = null;
        ch.partnerShot = null;
        Pos mine = ch.mine;
        if (mine == null || attempts.containsKey(mine) || !delaySpeed(mine)) return;
        for (Cand c : cands) {
            if (c.pos().equals(mine)) continue;
            BreakPairs.Info i = io.info(c.pos());
            if (i == null || !i.group().equals(group) || i.speed() >= 1.0 || attempts.containsKey(c.pos())) continue;
            if (!stays.test(c.pos())) continue;
            S shot = io.aim(c.pos());
            if (shot == null) continue;
            ch.partner = c.pos();
            ch.partnerShot = shot;
            return;
        }
    }

    /**
     * Lambda's double break while the sweep walks: a block that could go to the delayed slot but has no partner in reach
     * yet, and is not pressing (no blocker, still {@link #SLOW_PRESS} of walk before it leaves reach), is not started:
     * the walk goes on and brings its partner. Started alone it would hold the walk 7 ticks for one block, and each step
     * of the walk brings the next lone one (simulated on a lapis tile: 82 % of the blocks as pairs without this, 87 %
     * with it).
     */
    private boolean loneWaits(Choice ch, List<Cand> cands, boolean walking) {
        Pos mine = ch.mine;
        if (!walking || mine == null || ch.partner != null || !lambdaOn() || !p.lambda().doubleBreak()) return false;
        if (attempts.containsKey(mine) || !delaySpeed(mine) || dig.digging()) return false;
        return !pressing(cands, mine, true);
    }

    /** The tool for the next block is at the floor: wait for the blocks in flight (their wear), then fail. */
    private Choice worn(Choice ch, BreakRun.Tool tool) {
        if (dig.waiting()) {
            ch.waiting = true;
            return ch; // let the blocks in flight settle (their wear shows) before stopping
        }
        failure = BotActions.TOOL_AT_FLOOR + ": " + tool.wornReason() + " (" + done + "/" + total + " broken)";
        log("stopped");
        return ch;
    }

    /**
     * A non-instant block is pressing: a blocker, standing, or while walking within {@link #SLOW_PRESS} of leaving
     * reach.
     */
    private boolean pressing(List<Cand> cands, Pos nonInstant, boolean walking) {
        for (Cand c : cands) {
            if (!c.pos().equals(nonInstant)) continue;
            return c.blocker() || !walking || c.urgency() < SLOW_PRESS;
        }
        return false;
    }

    /**
     * Slow enough for the server's delayed slot (an early STOP leaves it below 0.7 progress: one tick of mining) and done
     * there within {@link #DELAYED_MAX_TICKS} (no tool switch is allowed until it shows: a warped wart block by hand,
     * 22 ticks, is mined instead).
     */
    private boolean delaySpeed(Pos pos) {
        BreakPairs.Info i = io.info(pos);
        return i != null && i.speed() >= 1.0 / DELAYED_MAX_TICKS && i.speed() < BreakPairs.DELAY_MAX_SPEED;
    }

    /**
     * Sends what {@code ch} chose, after this tick's input went in: the tool select (at once, before the STARTs), then
     * the STARTs (instant) or the START of the mined block. Nothing in the air (the server would see a fifth of the
     * speed), with a container open or when last tick's input was not ours. True if anything was sent.
     */
    private boolean act(Choice ch, long now) {
        if (ch.waiting || ch.tool == null || (ch.batch.isEmpty() && ch.mine == null)) return false;
        if (!io.canAct() || !io.onGround()) return false;
        String before = io.heldGroup();
        if (!io.hold(ch.tool)) return false;
        if (!before.equals(io.heldGroup())) selects++;
        if (ch.mine != null && lambdaOn()) return lambdaStart(ch, now);
        if (ch.mine != null && ch.delayed) {
            // START and STOP at once: the server keeps it in its delayed slot and breaks it with the held tool by itself
            double speed = io.speedNow(ch.mine);
            if (!dig.canStart(now) || ch.mineShot == null || digRoom() < 2 || !(speed > 0)) return false;
            if (speed >= BreakPairs.DELAY_MAX_SPEED) return false; // with the item held it would break at once: mined later
            dig.startDelayed(now, ch.mine, ch.mineShot, speed, true);
            delayedChosen++;
            countType(ch.mine);
            return true;
        }
        if (ch.mine != null) {
            if (!dig.canStart(now) || ch.mineShot == null || digRoom() < 2) return false;
            // a block refused before is mined to a STOP, its STOP no sooner than a round trip (its air ends it if the
            // START broke it after all); likewise while Haste is about to run out
            boolean retried = attempts.containsKey(ch.mine);
            dig.start(now, ch.mine, ch.mineShot, io.hasteUncertain() || retried ? HASTE_UNSURE_STOP_TICKS : 0, retried);
            countType(ch.mine);
            slowBurst = walk == Walk.SWEEP;
            return true;
        }
        int sent = 0;
        int cap = Math.min(ch.batch.size(), digRoom());
        for (int i = 0; i < cap; i++) {
            if (!dig.canStartInstant(now, perTick)) break;
            Pos pos = ch.batch.get(i);
            if (!dig.startInstant(now, pos, ch.shots.get(i), p.swingEachStart() || sent == 0)) continue;
            if (sent > 0) offLook.add(pos);
            sent++;
            countType(pos);
        }
        maxInTick = Math.max(maxInTick, sent);
        return sent > 0;
    }

    /**
     * Lambda's scheme ({@link DigClient#startLambda}) for the non-instant block {@code ch.mine}: START and its fake
     * START (and the STOP at once at 0.7..1 a tick), then, with a partner and the server's delayed slot free, the early
     * STOP that leaves it there and the partner's START (Lambda's double break). A retried block, or one while Haste is
     * about to run out, is mined alone to a STOP no sooner than {@link #HASTE_UNSURE_STOP_TICKS}. True if sent.
     */
    private boolean lambdaStart(Choice ch, long now) {
        Pos mine = ch.mine;
        S shot = ch.mineShot;
        if (mine == null || shot == null || !dig.canStartLambda(now)) return false;
        double speed = io.speedNow(mine);
        if (!(speed > 0)) return false;
        int fakes = p.lambda().fakeStarts() ? 1 : 0;
        if (digRoom() < 2 + fakes) return false;
        boolean retried = attempts.containsKey(mine);
        boolean unsure = io.hasteUncertain();
        boolean instant = speed >= 1.0 && !retried;
        Pos q = ch.partner;
        S qs = ch.partnerShot;
        if (q != null && !slideKeeps(q)) q = null;
        boolean quick = !retried && !unsure && speed >= DigClient.LAMBDA_THRESHOLD;
        // mined from now on (7 ticks at least): it must stay in reach while the bot still slides (after a walk)
        if (!instant && !quick && q == null && !slideKeeps(mine)) return false;
        if (!dig.startLambda(now, mine, shot, retried || unsure ? HASTE_UNSURE_STOP_TICKS : 0, retried)) return false;
        countType(mine);
        if (instant) return true;
        lambdaStops.add(mine);
        if (!dig.digging()) return true; // broken by START + STOP at 0.7..1 a tick
        slowBurst = walk == Walk.SWEEP;
        if (q == null || qs == null || retried || unsure || pairSlowOff || ch.room < 2 || lingeringPending()
            || !remaining.contains(q) || dig.inFlight(q) || !dig.delayedSlotFree(now, SLOT_MARGIN)
            || digRoom() < 2 + fakes) {
            return true;
        }
        double qSpeed = io.speedNow(q);
        if (!(qSpeed > 0) || qSpeed >= 1.0) return true;
        if (dig.lambdaSecondary(now, q, qs)) {
            lambdaStops.remove(mine); // its answer is the delayed slot's air now
            lambdaStops.add(q);
            countType(q);
        }
        return true;
    }

    /**
     * Lambda: {@code c}, mined from now on, stays in reach (and {@link #SLIDE_MARGIN} inside it) until its STOP with the
     * bot's velocity now and no keys: a block started while the bot slides after a walk is mined 7 ticks, long enough
     * to slide out of reach (simulated, j0024-like: one aborted at its budget at 4.57 blocks).
     */
    private boolean slideKeeps(Pos c) {
        double[] v = io.velocity();
        if (Math.hypot(v[0], v[1]) < 1e-6) return true;
        return staysInReach(c, mineMoves(c), 0, new double[]{0, 0}, SLIDE_MARGIN);
    }

    /**
     * One tick of the block mined with Lambda's scheme ({@link DigClient#tickLambda}): its burst, then its STOP once done
     * (input ours, on the ground, in reach). After a STOP whose burst went out Lambda goes on in the same tick
     * ({@code processRequest}'s loop, BreakManager.kt:328-349): the next non-instant block of the held tool in reach is
     * started, with its partner (instant blocks wait out the 6 ticks).
     */
    private void lambdaTick(long now, DigClient.Active<S> a) {
        double r = p.reach();
        boolean inReach = boxDistSq(io.feetX(), io.eyeY(), io.feetZ(), a.pos) <= r * r;
        boolean canStop = io.canAct() && io.onGround() && inReach;
        boolean canBurst = io.onGround() && digRoom() >= DigClient.OLD_GRIM_BURST + 1;
        if (!dig.tickLambda(now, canStop, canBurst)) return;
        if (!dig.lambdaBypass() || holdForPark || escape != null || !lambdaOn()) return;
        List<Cand> cands = candidates(0, 0, Set.of(), Map.of(), walk == Walk.SWEEP);
        String held = io.heldGroup();
        cands.removeIf(c -> {
            BreakPairs.Info i = io.info(c.pos());
            return i == null || !i.group().equals(held) || (i.speed() >= 1.0 && !attempts.containsKey(c.pos()));
        });
        if (cands.isEmpty()) return;
        // moving (build.nukeMineMoving): the block mined from now on stays in reach until its STOP with this tick's
        // velocity, the keys counted as pressed once more (mineWalk then goes on only while it stays in reach)
        double[] v = io.velocity();
        double vl = Math.hypot(v[0], v[1]);
        double[] dir = vl > 1e-6 ? new double[]{v[0] / vl, v[1] / vl} : new double[]{0, 0};
        Predicate<Pos> stays = q -> vl <= 1e-6 || staysInReach(q, mineMoves(q), 1, dir, SLIDE_MARGIN);
        Choice ch = choose(cands, now, false);
        if (!failure.isEmpty() || ch.mine == null) return;
        if (vl > 1e-6) {
            BreakPairs.Info mi = io.info(ch.mine);
            if (p.lambda().doubleBreak() && mi != null) partner(ch, cands, mi.group(), stays);
            if (ch.partner == null && !stays.test(ch.mine)) return; // mined alone it would leave reach
        }
        act(ch, now);
    }

    /** Dig packets sent so far (STARTs, STOPs, Lambda's fake STARTs). */
    private long digsSent() {
        return (long) dig.starts + dig.stopped + dig.delayedSent + dig.fakeStarts;
    }

    /** Dig packets that may still go out this tick under the rolling cap ({@code build.nukeMaxDigsPerSecond}). */
    private int digRoom() {
        long oldest = digWindow[(int) ((ticks + 1) % digWindow.length)];
        return (int) Math.max(0, p.maxDigsPerSecond() - (digsSent() - oldest));
    }

    private void countType(Pos pos) {
        int[] k = byId.computeIfAbsent(io.block(pos).id(), id -> new int[1]);
        k[0]++;
    }

    // ---------------------------------------------------------------------------------------------------- sweep

    private TaskStatus sweepTick(long now) {
        NukeSweep.Plan pl = plan;
        if (pl == null || wp >= pl.waypoints().size()) {
            endSweep();
            return afterSweep(now);
        }
        double fx = io.feetX();
        double fz = io.feetZ();
        int skippedFrom = wp;
        if (pl.isTour() && skipServed(pl, fx, fz)) {
            if (wp >= pl.waypoints().size()) {
                endSweep();
                return afterSweep(now);
            }
            // the walk turns here to the next segment it has not served: a lane end for the pause question (mends),
            // which the skipped end waypoint would have asked
            boolean passedEnd = false;
            for (int i = skippedFrom; i < wp; i++) passedEnd |= laneEnds.contains(i);
            if (passedEnd && askPause(now)) return pauseTick(now);
        }
        double[] w = pl.waypoints().get(wp);
        double toX = w[0] - fx;
        double toZ = w[1] - fz;
        double dist = Math.hypot(toX, toZ);
        if (dist < ARRIVE) {
            // (waypoint 0 is the feet: its targets go on the way, as the sweep's first tick)
            TaskStatus held = routeFirst && onLeftoverRoute && wp > 0 ? standAt(pl, now) : null;
            if (held != null) return held;
            boolean laneEnd = laneEnds.contains(wp);
            wp++;
            detoursHere = 0;
            if (laneEnd && askPause(now)) return pauseTick(now);
            if (wp >= pl.waypoints().size()) {
                endSweep();
                return afterSweep(now);
            }
            w = pl.waypoints().get(wp);
            toX = w[0] - fx;
            toZ = w[1] - fz;
            dist = Math.hypot(toX, toZ);
        }
        if (wp == 0 && dist > 3) {
            // to the start of the first lane: Baritone (it may end on top of the targets)
            walkGoal = new Pos((int) Math.floor(w[0]), floorY, (int) Math.floor(w[1]));
            walk = Walk.BARITONE_SWEEP;
            TaskStatus s = baritone();
            return s == null ? TaskStatus.RUNNING : s;
        }
        if (onLeftoverRoute && wp > 0 && wp != legWp) {
            legWp = wp;
            Pos stand = new Pos((int) Math.floor(w[0]), floorY, (int) Math.floor(w[1]));
            // kept art: a stand on top of a kept block is walked to over the tops with the own keys when every cell
            // the box passes holds one (no hole to drop into), else by Baritone right onto it (range 0: a walk ending
            // beside it would leave the own keys pressing against it)
            boolean onTop = routeFirst && io.isLoaded(stand) && !io.walkable(stand);
            if (onTop && !topLegClear(fx, fz, w[0], w[1])) {
                walkGoal = stand;
                walkRange = 0;
                walk = Walk.BARITONE_SWEEP;
                leftoverBaritoneLegs++;
                TaskStatus s = baritone();
                return s == null ? TaskStatus.RUNNING : s;
            }
            if (!onTop && dist > 3 && !legClear(fx, fz, w[0], w[1])) {
                // a leg of the leftover route along a hazard (an edge, a no-go zone) or across a block that is no
                // target: Baritone to its stand, as the standing phase would walk (the own keys would press against
                // the edge guard until a detour)
                walkGoal = new Pos((int) Math.floor(w[0]), floorY, (int) Math.floor(w[1]));
                walk = Walk.BARITONE_SWEEP;
                leftoverBaritoneLegs++;
                TaskStatus s = baritone();
                return s == null ? TaskStatus.RUNNING : s;
            }
        }
        if (wp != progressWp) {
            progressWp = wp;
            progressBest = dist;
            progressIdle = 0;
        } else if (dist < progressBest - PROGRESS_STEP) {
            progressBest = dist;
            progressIdle = 0;
        }
        if (sideWp != wp) sideOffset = 0;
        double[] d = steer(pl, w, fx, fz, toX, toZ, dist);
        double dx = d[0];
        double dz = d[1];

        // the floor cells the box enters next; elevated: the cells under it (to drop to the floor)
        boolean elevated = io.feetY() >= floorY + ELEVATED;
        Set<Pos> blockers = new LinkedHashSet<>();
        Map<Pos, Double> soon = new HashMap<>();
        boolean obstacle = false;
        if (elevated) {
            // a target in a pocket is not broken from on top: the bot would drop into it (build.nukePocketGuard)
            for (Pos c : boxCells(fx, fz)) if (remaining.contains(c) && !avoid(c)) blockers.add(c);
            elevated = !blockers.isEmpty(); // on top of something that is no target: walk on, it steps off
            if (!elevated && pockets != null) {
                // ... but never down into a pocket
                for (double ahead : new double[]{0.3, LOOK_AHEAD}) {
                    for (Pos c : boxCells(fx + dx * ahead, fz + dz * ahead)) {
                        if (io.isLoaded(c) && io.walkable(c) && avoid(c)) obstacle = true;
                    }
                }
            }
        } else {
            Set<Pos> here = new HashSet<>(boxCells(fx, fz));
            for (double ahead : new double[]{0.3, LOOK_AHEAD}) {
                for (Pos c : boxCells(fx + dx * ahead, fz + dz * ahead)) {
                    if (!io.isLoaded(c)) continue;
                    if (avoid(c)) {
                        // a pocket the box would squeeze into between walls (or reaches into already): walked around
                        obstacle = true;
                        continue;
                    }
                    if (here.contains(c) || io.walkable(c)) continue;
                    if (remaining.contains(c) || breakInWay(c)) blockers.add(c);
                    else obstacle = true;
                }
            }
            // the way a little further ahead goes before everything else (no hold): its air must be back from the
            // server before the box gets there, a round trip on 2b2t is 2-6 ticks
            for (double ahead = 0.75; ahead <= CLEAR_AHEAD + 1e-9; ahead += 0.25) {
                for (Pos c : boxCells(fx + dx * ahead, fz + dz * ahead)) {
                    if (here.contains(c)) continue;
                    if (remaining.contains(c) || (io.isLoaded(c) && !io.walkable(c) && breakInWay(c)))
                        soon.putIfAbsent(c, ahead);
                }
            }
        }
        if (obstacle && !elevated) {
            // around it on a neighbouring column (its cells walkable or targets, broken first), else Baritone
            if (wp > 0 && sideOffset == 0 && sidestep(pl, w, fx, fz, dx, dz)) {
                d = steer(pl, w, fx, fz, toX, toZ, dist);
                dx = d[0];
                dz = d[1];
            } else if (detour(fx, fz, dx, dz, "a block that is no target in the way")) {
                return TaskStatus.RUNNING;
            }
        }
        List<Cand> cands = candidates(dx, dz, blockers, soon, true);
        Choice ch = choose(cands, now, true);
        if (!failure.isEmpty()) return TaskStatus.FAILED;

        // hold: a block in the way, being mined, or a target in reach that the next step would leave behind
        // a mined slow block holds the walk (a delayed one does not: the server breaks it by itself)
        boolean mined = ch.mine != null && !ch.delayed;
        // build.nukeMineMoving: a mined block starts with the walk going on when it stays in reach until its STOP that
        // way, else with the keys released when it stays in reach sliding; the walk then holds only so that every target
        // in reach can still be stopped for (until the top speed is known: it holds for a mined block, as without)
        boolean moving = mined && p.mineMoving();
        boolean moveStart = false;
        boolean slideStart = false;
        // ... and starts only once the bot stands: sliding on after the walk stopped it could leave reach for its STOP
        boolean sliding = !Double.isNaN(prevFx) && Math.hypot(fx - prevFx, fz - prevFz) > STILL;
        lastStep = Double.isNaN(prevFx) ? Double.NaN : Math.hypot(fx - prevFx, fz - prevFz);
        prevFx = fx;
        prevFz = fz;
        double[] keys = NukeGuard.keyMotion(io.keyYaw(ch.look), dx, dz);
        // a START may go out this tick (act() checks the input once it is in)
        boolean free = moving && !ch.waiting && ch.tool != null
            && (lambdaOn() ? dig.canStartLambda(now) : dig.canStart(now)) && io.onGround() && digRoom() >= 2;
        // the block that would start with the walk going on: the one chosen, else the most urgent other one that can
        Pos onTheMove = null;
        if (free && speedKnown && blockers.isEmpty() && !elevated && ch.mine != null) {
            onTheMove = staysInReach(ch.mine, mineMoves(ch.mine), mineMoves(ch.mine), keys, MOVE_MARGIN)
                ? ch.mine : movingCandidate(cands, ch.mine, keys);
        }
        boolean hold = !blockers.isEmpty() || elevated || (mined && (!moving || !speedKnown));
        boolean stopRule = p.mineMoving() && speedKnown;
        // targets in reach the next step (0.25 blocks; build.nukeHoldAtSpeed: the measured top speed) would leave: hold
        // for them. One skipped for HOLD_GIVE_UP_TICKS in a row while the tick had room for more STARTs (out of sight,
        // say) is left for the standing phase; a tick whose batch was full restarts the count (it only waits its turn)
        Map<Pos, Long> holding = new HashMap<>();
        double ey = io.eyeY();
        double stepAhead = p.holdAtSpeed() ? Math.max(STEP, topSpeed) : STEP;
        double nx = fx + dx * stepAhead;
        double nz = fz + dz * stepAhead;
        double rc = p.reach() - REACH_MARGIN;
        double r2 = rc * rc;
        // this tick's batch goes out unless the delay after a STOP, the air or the rate cap holds it back
        boolean sendable = !ch.waiting && ch.mine == null && dig.canStartInstant(now, perTick) && io.onGround()
            && digRoom() > 0;
        boolean full = ch.batch.size() >= perTick || ch.mine != null || !sendable;
        int behind = 0;
        boolean instantsNow = sendable && onTheMove == null;
        for (Cand c : cands) {
            if (sendable && ch.batch.contains(c.pos())) continue;
            // (build.nukeMineMoving: a mined one behind is held for by the stopping rule below, not counted)
            boolean counted = !stopRule || !slow(c.pos());
            if (counted && (c.pos().x() + 0.5 - fx) * dx + (c.pos().z() + 0.5 - fz) * dz <= 0.5) behind++;
            if (c.pos().equals(onTheMove)) continue; // stays in reach, started or not
            if (stopRule && (!instantsNow || slow(c.pos()))) {
                // build.nukeMineMoving: it waits its turn behind the block mined; hold while that still keeps it
                if (!holdSaves(c.pos(), keys)) continue;
            } else if (boxDistSq(nx, ey, nz, c.pos()) <= r2) {
                continue;
            }
            long since = full ? now : holdingSince.getOrDefault(c.pos(), now);
            if (now - since > HOLD_GIVE_UP_TICKS) {
                later.add(c.pos());
                continue;
            }
            holding.put(c.pos(), since);
            hold = true;
        }
        holdingSince.clear();
        holdingSince.putAll(holding);
        if (p.mineMoving()) {
            if (!blockers.isEmpty()) holdsBy[0]++;
            else if (!holding.isEmpty()) holdsBy[1]++;
        }
        // and do not outwalk the breaking: more targets level with or behind the bot than BACKLOG_TICKS ticks of STARTs
        if (behind > perTick * BACKLOG_TICKS) {
            if (p.mineMoving() && !hold) holdsBy[2]++;
            hold = true;
            backlogTicks++;
        }
        if (!hold && !elevated && hasteGate(fx, fz, dx, dz, now)) {
            if (p.mineMoving()) holdsBy[3]++;
            hold = true;
            hasteWaitTicks++;
        }
        if (free && ch.mine != null) {
            if (!hold && onTheMove != null) {
                moveStart = true;
                if (!onTheMove.equals(ch.mine)) {
                    S shot = io.aim(onTheMove);
                    if (shot != null) {
                        ch.mine = onTheMove;
                        ch.mineShot = shot;
                        ch.look = shot;
                    } else {
                        moveStart = false;
                    }
                }
            } else if (hold) {
                // holding: the most urgent one that stays in reach with the keys released (or the bot stands)
                Pos still = slideCandidate(cands, ch.mine, keys, sliding);
                S shot = still == null ? null : still.equals(ch.mine) ? ch.mineShot : io.aim(still);
                if (still != null && shot != null) {
                    ch.mine = still;
                    ch.mineShot = shot;
                    ch.look = shot;
                    slideStart = true;
                }
            }
        }
        Pos m = ch.mine;
        if ((moveStart || slideStart) && m != null && lambdaOn() && p.lambda().doubleBreak()) {
            // Lambda's double break on the move: the first block goes to the server's delayed slot this tick (no reach
            // needed after its STOP), the partner is the one mined from now on and must stay in reach until its STOP
            BreakPairs.Info mi = io.info(m);
            boolean pressedOn = moveStart && !hold;
            if (mi != null) {
                partner(ch, cands, mi.group(), q -> pressedOn
                    ? staysInReach(q, mineMoves(q), mineMoves(q), keys, MOVE_MARGIN)
                    : staysInReach(q, mineMoves(q), 0, keys, SLIDE_MARGIN));
            }
        }
        double mx = hold ? 0 : dx;
        double mz = hold ? 0 : dz;
        if ((moveStart || slideStart) && m != null && ch.mineShot != null) {
            // the rotation goes out with the position after this tick's movement: aimed from there
            S lead = leadAim(m, mx, mz, ch.mineShot);
            ch.mineShot = lead;
            ch.look = lead;
        }
        NukeGuard.Decision g = guard(ch.look, mx, mz, fx, fz, now);
        if (g != null) {
            mx = g.mx();
            mz = g.mz();
            if (g.held()) {
                if (p.mineMoving() && !hold) holdsBy[4]++;
                hold = true;
                guardHolds++;
                if (++guardHeld >= GUARD_DETOUR_TICKS && io.onGround()) {
                    guardHeld = 0;
                    if (detour(fx, fz, dx, dz, "the walk would take the box to " + g.hazard())) {
                        return TaskStatus.RUNNING;
                    }
                }
            } else {
                guardTurns++;
                guardHeld = 0;
            }
        } else {
            guardHeld = 0;
        }
        io.input(ch.look, mx, mz);
        if (moving) {
            if ((moveStart || slideStart) && act(ch, now) && dig.digging()) {
                movingDig = true;
                if (moveStart && !hold) movingStarts++;
                else slideStarts++;
            }
        } else if (!(mined && sliding)) {
            act(ch, now);
        }
        notePressed(!hold && (mx != 0 || mz != 0));
        if (hold) {
            holdTicks++;
            movedAt = now;
        } else {
            walkTicks++;
            if (Double.isNaN(lastX) || Math.hypot(fx - lastX, fz - lastZ) > 0.1 || !pressedLastTick) {
                lastX = fx;
                lastZ = fz;
                movedAt = now;
            } else if (now - movedAt > STUCK_TICKS && io.onGround()) {
                lastX = Double.NaN;
                progressIdle = 0;
                detour(fx, fz, dx, dz, "no progress");
            }
            if (++progressIdle > NO_PROGRESS_TICKS && io.onGround()) {
                progressIdle = 0;
                progressBest = dist;
                lastX = Double.NaN;
                detour(fx, fz, dx, dz, "no progress towards the waypoint");
            }
        }
        pressedLastTick = !hold;
        return TaskStatus.RUNNING;
    }

    // ------------------------------------------------------------------------------- mining on the move

    /**
     * {@code build.nukeMineMoving}: one tick of a block started in the sweep. The walk goes on along the lane while the
     * block stays in reach until its STOP that way ({@link #MOVE_MARGIN}), every other target in reach can still be
     * stopped for, the way ahead is walkable and nothing else holds it (a waypoint near, standing on targets, the
     * backlog, the Haste gate, the edge guard); else the keys are released. Either way the look is aimed from where the
     * eye will be after this tick's movement, which the rotation goes out with, so the STOP's look (checked from there)
     * still hits the block. Nothing else is started meanwhile: one block at a time, as standing.
     */
    private void mineWalk(long now, DigClient.Active<S> a) {
        double fx = io.feetX();
        double fz = io.feetZ();
        lastStep = Double.isNaN(prevFx) ? Double.NaN : Math.hypot(fx - prevFx, fz - prevFz);
        S here = io.aim(a.pos);
        S look0 = here != null ? here : a.shot;
        double mx = 0;
        double mz = 0;
        double[] dir = mineWalkDirection(fx, fz);
        if (dir == null) mineHoldsBy[0]++;
        if (dir != null) {
            double[] k = NukeGuard.keyMotion(io.keyYaw(look0), dir[0], dir[1]);
            int rem = Math.max(1, dig.ticksToStop(now));
            // the keys move up to 22.5° off the walk: that way must be open too
            boolean go = wayOpen(fx, fz, k[0], k[1]);
            if (!go) mineHoldsBy[0]++;
            if (go && !staysInReach(a.pos, rem, rem, k, MOVE_MARGIN)) {
                go = false;
                mineHoldsBy[1]++;
            }
            if (go) {
                int behind = 0;
                for (Cand c : candidates(dir[0], dir[1], Set.of(), Map.of(), true)) {
                    if (!slow(c.pos()) && (c.pos().x() + 0.5 - fx) * dir[0] + (c.pos().z() + 0.5 - fz) * dir[1] <= 0.5) {
                        behind++;
                    }
                    if (holdSaves(c.pos(), k)) {
                        go = false;
                        mineHoldsBy[2]++;
                        break;
                    }
                }
                if (go && behind > perTick * BACKLOG_TICKS) {
                    go = false;
                    mineHoldsBy[3]++;
                }
            }
            if (go && hasteGate(fx, fz, dir[0], dir[1], now)) {
                go = false;
                hasteWaitTicks++;
                mineHoldsBy[4]++;
            }
            if (go) {
                mx = dir[0];
                mz = dir[1];
            }
        }
        S look = leadAim(a.pos, mx, mz, look0);
        NukeGuard.Decision g = guard(look, mx, mz, fx, fz, now);
        boolean held = mx == 0 && mz == 0;
        if (g != null) {
            mx = g.mx();
            mz = g.mz();
            held |= g.held();
            if (g.held()) guardHolds++;
            else guardTurns++;
        }
        io.input(look, mx, mz);
        if (!held) mineWalkTicks++;
        notePressed(!held);
        prevFx = fx;
        prevFz = fz;
    }

    /**
     * The sweep's walking direction for {@link #mineWalk} now, or null when the walk holds while the block is mined:
     * the top speed not known yet, not on a lane (the walk to the first one, a pause), standing on targets, the next
     * waypoint within the walk's stopping distance (the sweep's own tick reaches it: lane ends, turns, pauses), or a
     * floor cell ahead that the box may not enter (a target or other block in the way, a pocket).
     */
    private double @Nullable [] mineWalkDirection(double fx, double fz) {
        NukeSweep.Plan pl = plan;
        if (!speedKnown || pl == null || pausing != null || wp == 0 || wp >= pl.waypoints().size()) return null;
        if (io.feetY() >= floorY + ELEVATED) return null;
        double[] w = pl.waypoints().get(wp);
        double toX = w[0] - fx;
        double toZ = w[1] - fz;
        double dist = Math.hypot(toX, toZ);
        if (dist < ARRIVE + topSpeed / (1 - NukeGuard.FRICTION)) return null;
        if (sideWp != wp) sideOffset = 0;
        double[] d = steer(pl, w, fx, fz, toX, toZ, dist);
        if (d[0] == 0 && d[1] == 0) return null;
        return wayOpen(fx, fz, d[0], d[1]) ? d : null;
    }

    /**
     * The floor cells the box at feet (fx, fz) enters {@code 0.3} and {@link #LOOK_AHEAD} blocks along (ux, uz) are
     * loaded, walkable and in no pocket the bot is not in (or the box is in them already).
     */
    private boolean wayOpen(double fx, double fz, double ux, double uz) {
        if (ux == 0 && uz == 0) return true;
        Set<Pos> here = new HashSet<>(boxCells(fx, fz));
        for (double ahead : new double[]{0.3, LOOK_AHEAD}) {
            for (Pos c : boxCells(fx + ux * ahead, fz + uz * ahead)) {
                if (!io.isLoaded(c) || avoid(c)) return false;
                if (here.contains(c) || io.walkable(c)) continue;
                return false;
            }
        }
        return true;
    }

    /**
     * The feet stay in reach of {@code c} ({@code margin} inside the candidates' reach) after {@code n} movements from
     * now: the keys moving along {@code keys} (unit) pressed for the first {@code pressed} of them at the walk's top
     * speed ({@link NukeGuard#accel}), sliding on after them (velocity × {@link NukeGuard#FRICTION} a tick). Only the
     * end counts: the server checks a STOP from where it goes out, and the distance to a box along a straight walk is
     * largest at one of its ends.
     */
    private boolean staysInReach(Pos c, int n, int pressed, double[] keys, double margin) {
        double[] v = io.velocity();
        double vx = v[0];
        double vz = v[1];
        double acc = NukeGuard.accel(topSpeed);
        double x = io.feetX();
        double z = io.feetZ();
        for (int i = 0; i < Math.min(n, 200); i++) {
            if (i < pressed) {
                vx += acc * keys[0];
                vz += acc * keys[1];
            }
            x += vx;
            z += vz;
            vx *= NukeGuard.FRICTION;
            vz *= NukeGuard.FRICTION;
        }
        double r = p.reach() - REACH_MARGIN - margin;
        return r > 0 && boxDistSq(x, io.eyeY(), z, c) <= r * r;
    }

    /**
     * {@code build.nukeMineMoving}: pressing the keys this tick would take the bot out of reach of {@code c} by the time
     * it stopped (no START can go out for it meanwhile), and holding now keeps it: the walk holds.
     */
    private boolean holdSaves(Pos c, double[] keys) {
        return !staysInReach(c, SLIDE_PLAY_TICKS, 1, keys, MOVE_MARGIN)
            && staysInReach(c, SLIDE_PLAY_TICKS, 0, keys, SLIDE_MARGIN);
    }

    /**
     * {@code build.nukeMineMoving}: the most urgent mined target other than {@code chosen}, of its tool group, that stays
     * in reach until its STOP with the walk going on; null if none.
     */
    private @Nullable Pos movingCandidate(List<Cand> cands, Pos chosen, double[] keys) {
        BreakPairs.Info ci = io.info(chosen);
        if (ci == null) return null;
        for (Cand c : cands) {
            Pos q = c.pos();
            if (q.equals(chosen) || c.blocker() || !slow(q)) continue;
            BreakPairs.Info i = io.info(q);
            if (i == null || !i.group().equals(ci.group())) continue;
            int t = mineMoves(q);
            if (staysInReach(q, t, t, keys, MOVE_MARGIN)) return q;
        }
        return null;
    }

    /**
     * {@code build.nukeMineMoving}, the walk holding: {@code chosen} or else the most urgent other mined target of its
     * tool group that stays in reach until its STOP with the keys released (any, once the bot stands); null if none.
     */
    private @Nullable Pos slideCandidate(List<Cand> cands, Pos chosen, double[] keys, boolean sliding) {
        if (!sliding || staysInReach(chosen, mineMoves(chosen), 0, keys, SLIDE_MARGIN)) return chosen;
        BreakPairs.Info ci = io.info(chosen);
        if (ci == null) return null;
        for (Cand c : cands) {
            Pos q = c.pos();
            if (q.equals(chosen) || !slow(q)) continue;
            BreakPairs.Info i = io.info(q);
            if (i == null || !i.group().equals(ci.group())) continue;
            if (staysInReach(q, mineMoves(q), 0, keys, SLIDE_MARGIN)) return q;
        }
        return null;
    }

    /** Not instant with the tool for it (mined), or one refused before (mined to a STOP). */
    private boolean slow(Pos c) {
        BreakPairs.Info i = io.info(c);
        return i == null || i.speed() < 1.0 || attempts.containsKey(c);
    }

    /**
     * Movements between a START of {@code pos} now and its STOP: the ticks of mining with the tool for it (on the
     * server's clock), at least {@link #HASTE_UNSURE_STOP_TICKS} while Haste is uncertain or for a retried block.
     */
    private int mineMoves(Pos pos) {
        BreakPairs.Info i = io.info(pos);
        int t = ToolChoice.ticks(i == null ? 0 : i.speed() * dig.serverClock());
        if (lambdaOn()) {
            // Lambda: STOP once speed × (ticks since START) ≥ 0.7, no sooner than 6 ticks after it (DigClient.tickLambda)
            double sp = i == null ? 0 : i.speed() * dig.serverClock();
            int k = sp > 1e-9 ? (int) Math.min(200, Math.ceil(DigClient.LAMBDA_THRESHOLD / sp - 1e-9)) : 200;
            t = Math.max(DigClient.OLD_GRIM_MIN_TICKS - 1, k);
        }
        if (io.hasteUncertain() || attempts.containsKey(pos)) t = Math.max(t, HASTE_UNSURE_STOP_TICKS);
        return Math.min(t, 200);
    }

    /**
     * The look at {@code pos} from where the eye will be after this tick's movement: the velocity, plus the keys
     * towards (mx, mz) (0, 0: none) at the walk's top speed; {@code fallback} when no face is in reach from there.
     */
    private S leadAim(Pos pos, double mx, double mz, S fallback) {
        double fx = io.feetX();
        double fz = io.feetZ();
        double[] v = io.velocity();
        double px = fx + v[0];
        double pz = fz + v[1];
        // pressed last tick without moving: against something (the keys move nothing this tick either)
        boolean stuck = pressedRun > 0 && !Double.isNaN(lastStep) && lastStep < 0.02;
        if ((mx != 0 || mz != 0) && !stuck) {
            double[] k = NukeGuard.keyMotion(io.keyYaw(fallback), mx, mz);
            double acc = NukeGuard.accel(topSpeed);
            double qx = px + acc * k[0];
            double qz = pz + acc * k[1];
            // a box that would go into a cell it may not enter stops at it
            Set<Pos> here = new HashSet<>(boxCells(fx, fz));
            boolean open = true;
            for (Pos c : boxCells(qx, qz)) {
                if (!here.contains(c) && (!io.isLoaded(c) || !io.walkable(c))) open = false;
            }
            if (open) {
                px = qx;
                pz = qz;
            }
        }
        S s = io.aimFrom(pos, px, io.eyeY(), pz);
        return s != null ? s : fallback;
    }

    /** The keys were pressed this tick or not: the top speed counts as known after a steady walk. */
    private void notePressed(boolean pressed) {
        pressedRun = pressed ? pressedRun + 1 : 0;
        if (!speedKnown && pressedRun >= SPEED_SETTLE_TICKS && topSpeed > 0.05 && lastMoved >= 0.9 * topSpeed) {
            speedKnown = true;
        }
    }

    /**
     * {@code build.nukeTour}: moves {@link #wp} past the waypoints of lane segments whose targets have all broken (or
     * are left for the standing phase): a segment the walk has not reached yet is not walked at all, the rest of one
     * it is on is not walked to its end. The walk then goes straight from where the bot stands to the next waypoint
     * ({@link #skipFrom}). True if it moved.
     */
    private boolean skipServed(NukeSweep.Plan pl, double fx, double fz) {
        int[] seg = pl.segmentOf();
        List<Set<Pos>> cov = pl.covers();
        if (seg == null || cov == null) return false;
        int start = wp;
        while (wp < seg.length && served(cov.get(seg[wp]))) {
            // from its start: the whole segment (a stand of the leftover route is not counted)
            if (!onLeftoverRoute && (wp == 0 || seg[wp - 1] != seg[wp])) segmentsSkipped++;
            wp++;
        }
        if (wp == start) return false;
        detoursHere = 0;
        skipFrom = new double[]{fx, fz};
        skipWp = wp;
        return true;
    }

    /** None of {@code cover} is left to break in the sweep (broken, or left for the standing phase). */
    private boolean served(Set<Pos> cover) {
        for (Pos t : cover) if (remaining.contains(t) && !later.contains(t)) return false;
        return true;
    }

    /**
     * Where the walk to waypoint {@link #wp} started: the waypoint before it, or where the bot stood when the tour
     * skipped to it ({@link #skipServed}).
     */
    private double[] segmentStart(NukeSweep.Plan pl) {
        double[] s = skipFrom;
        return s != null && skipWp == wp ? s : pl.waypoints().get(wp - 1);
    }

    /** Where the tour last turned to the next segment it had not served ({@link #skipServed}), if the walk is on that leg. */
    double @Nullable [] turnedAt() {
        return skipWp == wp ? skipFrom : null;
    }

    /**
     * The walking direction: towards the waypoint, steered back onto the segment's line (moved {@link #sideOffset}
     * columns to the side while sidestepping; the sidestep ends once the walk is past {@link #sideUntil}).
     */
    private double[] steer(NukeSweep.Plan pl, double[] w, double fx, double fz, double toX, double toZ, double dist) {
        double dx = dist > 1e-9 ? toX / dist : 0;
        double dz = dist > 1e-9 ? toZ / dist : 0;
        if (wp == 0) return new double[]{dx, dz};
        double[] a = segmentStart(pl);
        double sx = w[0] - a[0];
        double sz = w[1] - a[1];
        double sl = Math.hypot(sx, sz);
        if (sl <= 1e-9) return new double[]{dx, dz};
        sx /= sl;
        sz /= sl;
        double along = (fx - a[0]) * sx + (fz - a[1]) * sz;
        if (sideOffset != 0 && along >= sideUntil) sideOffset = 0;
        double lat = (fx - a[0]) * -sz + (fz - a[1]) * sx - sideOffset; // signed distance off the (moved) line
        if (sideOffset != 0 && Math.abs(lat) > 0.2) {
            // onto the side column straight sideways first: going diagonally the box clips the obstacle's corner
            return new double[]{-sz * -Math.signum(lat), sx * -Math.signum(lat)};
        }
        double cx = sx - lat * -sz * 1.5;
        double cz = sz - lat * sx * 1.5;
        double cl = Math.hypot(cx, cz);
        if ((dist > 0.6 || sideOffset != 0) && cl > 1e-9) {
            dx = cx / cl;
            dz = cz / cl;
        }
        return new double[]{dx, dz};
    }

    /**
     * The edge guard ({@link NukeGuard#decide}) on this tick's movement towards (mx, mz): null to press it as asked,
     * else what to press instead (one log line per hazard). Also keeps {@link #topSpeed}.
     */
    private NukeGuard.@Nullable Decision guard(@Nullable S look, double mx, double mz, double fx, double fz, long now) {
        if (guardAt == now - 1 && !Double.isNaN(guardFx)) {
            double moved = Math.hypot(fx - guardFx, fz - guardFz);
            if (moved < NukeGuard.TOP_SPEED_MAX) topSpeed = Math.max(topSpeed, moved);
            lastMoved = moved;
        } else if (guardAt != now) {
            lastMoved = Double.NaN;
        }
        guardFx = fx;
        guardFz = fz;
        guardAt = now;
        double[] v = io.velocity();
        int level = (int) Math.floor(io.feetY() + 0.1251);
        NukeGuard.Decision d = NukeGuard.decide(fx, fz, level, v[0], v[1], NukeGuard.accel(topSpeed), io.keyYaw(look),
            mx, mz, c -> hazardAt(c, now));
        if (d != null && !d.hazard().equals(guardLogged)) {
            guardLogged = d.hazard();
            String what = mx != 0 || mz != 0
                ? String.format(Locale.ROOT, "the keys towards (%.2f, %.2f)", mx, mz)
                : String.format(Locale.ROOT, "sliding at %.2f blocks a tick", Math.hypot(v[0], v[1]));
            io.log(String.format(Locale.ROOT, "Fast nuke: edge guard at %.2f, %.2f: %s would take the box to %s; %s",
                fx, fz, what, d.hazard(), d.how()));
        }
        return d;
    }

    private @Nullable String hazardAt(Pos c, long now) {
        if (hazardSeenAt != now) {
            hazardSeen.clear();
            hazardSeenAt = now;
        }
        String h = hazardSeen.get(c);
        if (h == null) {
            String a = io.hazard(c);
            h = a == null ? "" : a;
            hazardSeen.put(c, h);
        }
        return h.isEmpty() ? null : h;
    }

    /**
     * A block that is no target (pointed dripstone, a decorated pot, a protected fixture) lies on the walking line
     * ahead: the line moves one column to the side for the rows up to one past it, when every cell of that stretch is
     * walkable or a target (targets are in the way then and broken first). Left of the walk first, then right. True if
     * a side was found. Live 2026-10-11 (ellen [2,1], 253 pointed dripstone): Baritone's detours around them took
     * seconds each, skipped lane ends, and one stuck for 15 s ("MovementTraverse has taken too long").
     */
    private boolean sidestep(NukeSweep.Plan pl, double[] w, double fx, double fz, double dx, double dz) {
        double[] a = segmentStart(pl);
        double sx = w[0] - a[0];
        double sz = w[1] - a[1];
        double sl = Math.hypot(sx, sz);
        if (sl <= 1e-9) return false;
        sx /= sl;
        sz /= sl;
        double nx = -sz;
        double nz = sx;
        // the first cell ahead that is no target and not walkable
        Pos obstacle = null;
        Set<Pos> here = new HashSet<>(boxCells(fx, fz));
        for (double ahead = 0.3; ahead <= 2.0 && obstacle == null; ahead += 0.25) {
            for (Pos c : boxCells(fx + dx * ahead, fz + dz * ahead)) {
                if (here.contains(c) || !io.isLoaded(c) || io.walkable(c) || remaining.contains(c)) continue;
                obstacle = c;
                break;
            }
        }
        if (obstacle == null) return false;
        double along = (fx - a[0]) * sx + (fz - a[1]) * sz;
        double obstacleAlong = (obstacle.x() + 0.5 - a[0]) * sx + (obstacle.z() + 0.5 - a[1]) * sz;
        double lineLat = Math.round((fx - a[0]) * nx + (fz - a[1]) * nz);
        for (int side : new int[]{1, -1}) {
            boolean ok = true;
            for (double t = Math.floor(along); t <= obstacleAlong + 1.0 + 1e-9 && ok; t += 1.0) {
                double px = a[0] + sx * t + nx * (lineLat + side);
                double pz = a[1] + sz * t + nz * (lineLat + side);
                Pos c = new Pos((int) Math.floor(px), floorY, (int) Math.floor(pz));
                ok = io.isLoaded(c) && (io.walkable(c) || remaining.contains(c)) && !avoid(c);
            }
            if (!ok) continue;
            sideOffset = side;
            sideUntil = obstacleAlong + 1.0;
            sideWp = wp;
            sidesteps++;
            return true;
        }
        return false;
    }

    /**
     * At the edge of a Haste zone ({@code site.hasteZones}) with targets in the uncovered stretch ahead: true (hold)
     * while the Haste left is less than the walk across it needs (plus {@link #HASTE_GATE_MARGIN}, at most a fresh
     * renewal), for up to {@link #HASTE_WAIT_MAX} ticks. Inside a zone the beacon renews the effect every 80 ticks, so
     * the bot leaves with 330 rather than as few as 260 ticks.
     */
    private boolean hasteGate(double fx, double fz, double dx, double dz, long now) {
        Predicate<Pos> covered = p.hasteCovered();
        if (covered == null || floorY == Integer.MIN_VALUE || (dx == 0 && dz == 0)) return false;
        Pos here = new Pos((int) Math.floor(fx), floorY, (int) Math.floor(fz));
        Pos ahead = new Pos((int) Math.floor(fx + dx), floorY, (int) Math.floor(fz + dz));
        if (!covered.test(here) || covered.test(ahead)) {
            hasteWaitSince = -1;
            if (!here.equals(hasteWaitGaveUp)) hasteWaitGaveUp = null;
            return false;
        }
        if (here.equals(hasteWaitGaveUp)) return false;
        int run = 0;
        boolean work = false;
        int w = p.laneHalfWidth();
        for (int s = 1; s <= 96; s++) {
            double cx = fx + dx * s;
            double cz = fz + dz * s;
            if (covered.test(new Pos((int) Math.floor(cx), floorY, (int) Math.floor(cz)))) break;
            run = s;
            for (int l = -w; l <= w && !work; l++) {
                Pos c = new Pos((int) Math.floor(cx - dz * l), floorY, (int) Math.floor(cz + dx * l));
                work = remaining.contains(c);
            }
        }
        if (!work) return false;
        int need = Math.min(HASTE_FRESH, (int) Math.ceil(run / WALK_PER_TICK) + HASTE_GATE_MARGIN);
        if (io.hasteTicksLeft() >= need) {
            hasteWaitSince = -1;
            return false;
        }
        if (hasteWaitSince < 0) {
            hasteWaitSince = now;
            hasteWaits++;
        }
        if (now - hasteWaitSince > HASTE_WAIT_MAX) {
            hasteWaitGaveUp = here;
            hasteWaitSince = -1;
            return false;
        }
        return true;
    }

    /**
     * Baritone to a point {@code 2.5} blocks beyond the bot along the walk (or the waypoint when nearer); after
     * {@link #MAX_DETOURS} at one waypoint, the waypoint is skipped. True if a walk started.
     */
    private boolean detour(double fx, double fz, double dx, double dz, String why) {
        NukeSweep.Plan pl = plan;
        if (pl == null) return false;
        if (detoursHere >= MAX_DETOURS) {
            io.log("Fast nuke: skipping sweep waypoint " + wp + " (" + why + ")");
            wp++;
            detoursHere = 0;
            return false;
        }
        detoursHere++;
        detours++;
        double[] w = pl.waypoints().get(wp);
        double gx = fx + dx * 2.5;
        double gz = fz + dz * 2.5;
        if (Math.hypot(w[0] - fx, w[1] - fz) < 2.5) {
            gx = w[0];
            gz = w[1];
        }
        Pos goal = new Pos((int) Math.floor(gx), floorY, (int) Math.floor(gz));
        if (pockets != null && (avoid(goal) || !new FloorView().passable(goal))) {
            // not into a pocket (Baritone's path check would refuse the walk), nor onto a wall
            Pos open = openNear(goal, false);
            if (open != null) goal = open;
        }
        walkGoal = goal;
        walk = Walk.BARITONE_SWEEP;
        io.input(null, 0, 0);
        io.log("Fast nuke: detour to " + walkGoal + " (" + why + ")");
        return true;
    }

    /** At a lane end: true (pausing set) when the engine wants the run to stop here. */
    private boolean askPause(long now) {
        Supplier<@Nullable String> ask = pause;
        if (ask == null) return false;
        String why = ask.get();
        if (why == null || why.isBlank()) return false;
        pausing = why;
        pausingSince = now;
        io.log("Fast nuke: stopping at the end of lane " + lanesDone() + " (" + done + "/" + total + " broken): " + why);
        return true;
    }

    /** Lanes whose end the walk has passed. */
    private int lanesDone() {
        int n = 0;
        for (int i : laneEnds) if (i < wp) n++;
        return n;
    }

    /**
     * Stopping at a lane end: standing, everything in reach is broken (nearest first) and the blocks in flight answered,
     * then the run fails with {@link BotActions#BREAK_PAUSED}; after {@link #PAUSE_FINISH_TICKS} it stops anyway once
     * nothing is in flight (what is left goes to the next run).
     */
    /**
     * Kept art ({@link #routeFirst}), arrived at a stand of the route: stands still and breaks what is in reach while a
     * target the stand covers is in flight or can be aimed at from here, for {@link #STAND_MAX_TICKS} at most; null once
     * done (the walk goes on). Without it the walk passed the stand at once, and a carpet in a pit, seen only from
     * there, was out of sight long before the hold rule (it would leave reach) stopped for it.
     */
    private @Nullable TaskStatus standAt(NukeSweep.Plan pl, long now) {
        List<Set<Pos>> covers = pl.covers();
        int[] seg = pl.segmentOf();
        if (covers == null || seg == null || wp >= seg.length) return null;
        if (standWp != wp) {
            standWp = wp;
            standSince = now;
        }
        if (now - standSince > STAND_MAX_TICKS) return null;
        boolean open = false;
        for (Pos c : covers.get(seg[wp])) {
            if (remaining.contains(c) && (dig.inFlight(c) || io.aim(c) != null)) {
                open = true;
                break;
            }
        }
        if (!open) return null;
        List<Cand> cands = candidates(0, 0, Set.of(), Map.of(), false);
        Choice ch = choose(cands, now, false);
        if (!failure.isEmpty()) return TaskStatus.FAILED;
        io.input(ch.look, 0, 0);
        act(ch, now);
        holdTicks++;
        return TaskStatus.RUNNING;
    }

    private TaskStatus pauseTick(long now) {
        io.stopWalking();
        List<Cand> cands = candidates(0, 0, Set.of(), Map.of(), false);
        Choice ch = choose(cands, now, false);
        if (!failure.isEmpty()) return TaskStatus.FAILED;
        io.input(ch.look, 0, 0);
        boolean sent = act(ch, now);
        if (sent || dig.waiting() || dig.digging() || dig.stopPending()) return TaskStatus.RUNNING;
        boolean work = ch.look != null || ch.waiting;
        if (work && now - pausingSince <= PAUSE_FINISH_TICKS) return TaskStatus.RUNNING;
        failure = BotActions.BREAK_PAUSED + ": " + pausing + " (" + done + "/" + total + " broken)";
        log("paused");
        return TaskStatus.FAILED;
    }

    /** Floor cells (at the targets' level) the bot's box covers with its feet at (x, z). */
    private List<Pos> boxCells(double x, double z) {
        List<Pos> out = new ArrayList<>(4);
        double h = Aim.PLAYER_HALF_WIDTH - 1e-3;
        int x0 = (int) Math.floor(x - h);
        int x1 = (int) Math.floor(x + h);
        int z0 = (int) Math.floor(z - h);
        int z1 = (int) Math.floor(z + h);
        for (int cx = x0; cx <= x1; cx++) for (int cz = z0; cz <= z1; cz++) out.add(new Pos(cx, floorY, cz));
        return out;
    }

    /**
     * {@code c} (on the sweep's way, not walkable, no target) may be broken to walk on ({@code build.nukeSweepBreaksWay}):
     * the art is cleared afterwards and the clear removes it anyway ({@link Io#escapable}: the platform's clearable blocks,
     * not protected, a floor under it). It becomes a target of the way, broken like one. Live 2026-10-15 (Galileo [0,0]):
     * decorated pots, 3 % of the art, made the sweep walk around them until it gave its lanes up: 26 of 5,794 blocks
     * broken on the way, the rest standing (1,301 walks to a target, 17 minutes).
     */
    private boolean breakInWay(Pos c) {
        if (!p.wayBreak() || !p.artCleared() || c.y() != floorY || wayGivenUp.contains(c)) return false;
        if (wayTargets.contains(c)) return true;
        if (!io.isLoaded(c) || io.isAir(c) || !io.escapable(c)) return false;
        wayTargets.add(c);
        remaining.add(c);
        pocketsChanged();
        return true;
    }

    private void endSweep() {
        if (!wayTargets.isEmpty()) {
            // what stood in the sweep's way is no target for the standing phase
            remaining.removeAll(wayTargets);
            for (Pos c : wayTargets) {
                later.remove(c);
                holdingSince.remove(c);
            }
            wayTargets.clear();
            pocketsChanged();
        }
        boolean sweeping = walk == Walk.SWEEP || walk == Walk.BARITONE_SWEEP;
        if (sweeping && onLeftoverRoute) {
            io.log(String.format(Locale.ROOT, "Fast nuke: leftover route done, %d of %d broken on it (%d ticks); %d"
                + " left for standing", leftoversRouted, leftovers - leftoversAir, ticks - leftoverFrom,
                remaining.size()));
        } else if (sweeping) {
            NukeSweep.Plan pl = plan;
            int heldOut = 0;
            int retried = 0;
            for (Pos t : remaining) {
                if (later.contains(t)) heldOut++;
                else if (attempts.containsKey(t)) retried++;
            }
            io.log(String.format(Locale.ROOT, "Fast nuke: sweep done, %d/%d broken on the way (%d lanes, %.0f blocks"
                    + " of walk, %d ticks walking, %d holding%s); %d left for %s%s", sweptBroken, total,
                pl == null ? 0 : pl.lanes().size(), pl == null ? 0.0 : pl.length(), walkTicks, holdTicks,
                wayBroken > 0 ? ", " + wayBroken + " blocks in the way broken" : "", remaining.size(),
                routeOn() && !leftoversPlanned && floorY != Integer.MIN_VALUE ? "the leftover route"
                    : "standing",
                remaining.isEmpty() ? "" : String.format(Locale.ROOT, " (%d given up holding, %d refused or unanswered,"
                    + " %d never in reach)", heldOut, retried, remaining.size() - heldOut - retried)));
            leftoverFrom = ticks;
            leftovers = remaining.size();
        }
        walk = Walk.STAND;
        onLeftoverRoute = false;
        later.clear();
        holdingSince.clear();
        if (sweeping && !leftoversPlanned && routeOn()) startLeftovers();
    }

    /** After {@link #endSweep}: the leftover route's first tick when it started, else the standing phase's. */
    private TaskStatus afterSweep(long now) {
        return walk == Walk.SWEEP ? sweepTick(now) : standTick(now);
    }

    /**
     * {@code build.nukeLeftoverRoute}, once the sweep has ended: every target whose air already shows counts as broken,
     * the rest get one route ({@link NukeLeftovers#plan}) from where the bot stands towards the sweep's end point, walked
     * as the sweep walks ({@link #sweepTick}: the own keys, the guards, holds and detours; no lane-end pauses), each
     * stand skipped once its blocks are broken. What it leaves is done standing.
     */
    private void startLeftovers() {
        leftoversPlanned = true;
        if (floorY == Integer.MIN_VALUE) return;
        int air = 0;
        for (Pos t : new ArrayList<>(remaining)) {
            if (dig.inFlight(t) || !io.isLoaded(t) || !io.isAir(t)) continue;
            markBroken(t);
            air++;
        }
        leftoversAir = air;
        if (remaining.isEmpty()) {
            if (air > 0) io.log("Fast nuke: leftovers: all " + air + " already broken, their air came late");
            return;
        }
        int left = remaining.size();
        // a few: the walk to each is as quick; more than the sweep broke: it did not get through (walled in, say)
        String why = left < ROUTE_MIN ? "too few for a route"
            : left > ROUTE_MAX ? "more than a route takes (" + ROUTE_MAX + ")"
            : left > sweptBroken && !leftoversUnswept ? "more than the sweep broke (" + sweptBroken + ")" : null;
        if (why != null) {
            io.log(String.format(Locale.ROOT, "Fast nuke: leftovers: %d left after the sweep, %d of them already broken"
                + " (their air came late); the other %d %s: standing (build.nukeLeftoverRoute)", leftovers, air, left,
                why));
            return;
        }
        double cover = Math.min(NukeLeftovers.COVER, p.reach() - REACH_MARGIN - 0.6);
        double fx = io.feetX();
        double fz = io.feetZ();
        NukeSweep.Plan route = NukeLeftovers.plan(remaining, fx, fz, floorY, p.endX(), p.endZ(), cover,
            this::standable);
        int covered = 0;
        List<Set<Pos>> cov = route.covers();
        if (cov != null) for (Set<Pos> c : cov) covered += c.size();
        leftoverStands = Math.max(0, route.waypoints().size() - 1);
        leftoverWalk = route.walk(fx, fz, Double.NaN, Double.NaN);
        io.log(String.format(Locale.ROOT, "Fast nuke: leftovers: %d left after the sweep, %d of them already broken"
                + " (their air came late); a route over %d stand cells (%.0f blocks of walk)%s (build.nukeLeftoverRoute)",
            leftovers, air, leftoverStands, leftoverWalk,
            covered < remaining.size() ? ", " + (remaining.size() - covered) + " in reach of no stand cell" : ""));
        if (leftoverStands == 0) return; // nothing to walk to: standing, as before
        walkRoute(route, Set.of()); // no lane-end pauses on the route
    }

    /**
     * Kept art ({@link #routeFirst}), on the first tick: the route over every target from where the bot stands, stands
     * also on top of the kept full blocks, the pause asked at each (mends: a carry-over breaks up to
     * {@link #ROUTE_MAX}); what it leaves is done standing.
     */
    private void startRoute() {
        leftoversPlanned = true;
        leftoverFrom = ticks;
        leftovers = remaining.size();
        double rc = p.reach() - REACH_MARGIN;
        // on top of a kept block the eye is a block higher: 1.62 above the targets' top faces
        double top = Math.sqrt(Math.max(0, rc * rc - EYE * EYE)) - ARRIVE;
        double cover = Math.min(Math.min(NukeLeftovers.COVER, rc - 0.6), top);
        double fx = io.feetX();
        double fz = io.feetZ();
        int pits = 0;
        for (Pos t : remaining) if (pit(t)) pits++;
        NukeSweep.Plan route = NukeLeftovers.plan(remaining, fx, fz, floorY, p.endX(), p.endZ(),
            t -> pit(t) ? PIT_COVER : cover, this::standable);
        int covered = 0;
        List<Set<Pos>> cov = route.covers();
        if (cov != null) for (Set<Pos> c : cov) covered += c.size();
        leftoverStands = Math.max(0, route.waypoints().size() - 1);
        leftoverWalk = route.walk(fx, fz, Double.NaN, Double.NaN);
        if (pits > 0) io.log("Fast nuke: " + pits + " of the targets are low blocks in pits between full blocks: a"
            + " stand right beside each");
        NukeSweep.Plan sp = sweepPlan;
        io.log(String.format(Locale.ROOT, "Fast nuke: %d targets among kept blocks (%.0f %% of their box): a route over"
                + " %d stand cells (%.0f blocks of walk) instead of the sweep of %d lanes%s", total, 100 * keptShare,
            leftoverStands, leftoverWalk, sp == null ? 0 : sp.lanes().size(),
            covered < remaining.size() ? ", " + (remaining.size() - covered) + " in reach of no stand cell" : ""));
        if (leftoverStands == 0) {
            walk = Walk.STAND;
            return;
        }
        Set<Integer> stands = new HashSet<>();
        for (int i = 1; i < route.waypoints().size(); i++) stands.add(i);
        walkRoute(route, stands);
    }

    /**
     * A target the box walks into (a carpet: low) next to a full block on its level (a kept block, or a target not
     * broken yet): its top face lies 15/16 below the block's, seen only from right beside it (live 2026-11-03, [0,8] →
     * [1,8]: 8,156 of 12,712 ticks of the round held for targets in reach that never started, 939 of the 1,764 by
     * hand).
     */
    private boolean pit(Pos t) {
        if (!io.isLoaded(t) || !io.walkable(t)) return false;
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            Pos n = new Pos(t.x() + d[0], t.y(), t.z() + d[1]);
            if (io.isLoaded(n) && !io.isAir(n) && !io.walkable(n)) return true;
        }
        return false;
    }

    /** Walks {@code route} as the sweep walks ({@link #sweepTick}), asking the pause at {@code pauseAt}. */
    private void walkRoute(NukeSweep.Plan route, Set<Integer> pauseAt) {
        plan = route;
        laneEnds = pauseAt;
        wp = 0;
        walk = Walk.SWEEP;
        onLeftoverRoute = true;
        detoursHere = 0;
        sideOffset = 0;
        sideWp = -1;
        progressWp = -1;
        skipFrom = null;
        skipWp = -1;
        lastX = Double.NaN;
        lastApproach = null;
        approachArrived = 0;
        legWp = -1;
    }

    /**
     * A floor cell the leftover route may stand in ({@link NukeLeftovers#plan}): walkable now, no target, no hazard
     * (a no-go zone, water or lava, a drop), in no pocket the bot is not in.
     */
    private boolean standable(Pos c) {
        if (c.y() != floorY || remaining.contains(c) || !io.isLoaded(c) || avoid(c)) return false;
        // kept art (routeFirst): on top of a kept full block too, as Baritone climbs it (the kept blocks stay)
        if (!io.walkable(c) && !(routeFirst && io.climbable(c))) return false;
        return io.hazard(c) == null;
    }

    /** {@code build.nukeLeftoverRoute}: after the sweep of a NUKE; on kept art only from the start ({@link #routeFirst}). */
    private boolean routeOn() {
        return p.leftoverRoute() && (p.artCleared() || routeFirst);
    }

    /**
     * The straight walk from (fx, fz) to (tx, tz) keeps the box {@link #LEG_MARGIN} clear of every hazard, pocket and
     * block that is no target (targets on the way are broken as the sweep breaks them): a leg of the leftover route the
     * own keys may walk.
     */
    private boolean legClear(double fx, double fz, double tx, double tz) {
        double len = Math.hypot(tx - fx, tz - fz);
        int n = Math.max(1, (int) Math.ceil(len / 0.25));
        double h = Aim.PLAYER_HALF_WIDTH + LEG_MARGIN;
        for (int i = 0; i <= n; i++) {
            double x = fx + (tx - fx) * i / n;
            double z = fz + (tz - fz) * i / n;
            for (int cx = (int) Math.floor(x - h); cx <= (int) Math.floor(x + h); cx++) {
                for (int cz = (int) Math.floor(z - h); cz <= (int) Math.floor(z + h); cz++) {
                    Pos c = new Pos(cx, floorY, cz);
                    if (!io.isLoaded(c) || io.hazard(c) != null || avoid(c)) return false;
                    if (!remaining.contains(c) && !io.walkable(c)) return false;
                }
            }
        }
        return true;
    }

    /**
     * Kept art: the bot stands on top of the kept blocks and the straight walk from (fx, fz) to (tx, tz) keeps every cell
     * under its box a kept full block ({@link Io#climbable}, no target): a leg over the tops the own keys may walk.
     */
    private boolean topLegClear(double fx, double fz, double tx, double tz) {
        if (io.feetY() < floorY + ELEVATED) return false;
        double len = Math.hypot(tx - fx, tz - fz);
        int n = Math.max(1, (int) Math.ceil(len / 0.25));
        double h = Aim.PLAYER_HALF_WIDTH;
        for (int i = 0; i <= n; i++) {
            double x = fx + (tx - fx) * i / n;
            double z = fz + (tz - fz) * i / n;
            for (int cx = (int) Math.floor(x - h); cx <= (int) Math.floor(x + h); cx++) {
                for (int cz = (int) Math.floor(z - h); cz <= (int) Math.floor(z + h); cz++) {
                    Pos c = new Pos(cx, floorY, cz);
                    if (!io.isLoaded(c) || remaining.contains(c) || !io.climbable(c)) return false;
                }
            }
        }
        return true;
    }

    /**
     * {@code build.nukeLeftoverRoute}: a target given up once as refused or unanswered and not in flight whose air shows
     * now counts as broken, wherever the bot is (its late air came after the walk had taken it out of reach, where
     * nothing looked at it again).
     */
    private void lateAirCheck() {
        if (!routeOn() || attempts.isEmpty()) return;
        List<Pos> air = null;
        for (Pos t : attempts.keySet()) {
            if (!remaining.contains(t) || dig.inFlight(t) || !io.isLoaded(t) || !io.isAir(t)) continue;
            if (air == null) air = new ArrayList<>();
            air.add(t);
        }
        if (air == null) return;
        for (Pos t : air) {
            markBroken(t);
            lateAir++;
        }
    }

    // ---------------------------------------------------------------------------------------------- standing

    private TaskStatus standTick(long now) {
        List<Cand> cands = candidates(0, 0, Set.of(), Map.of(), false);
        Choice ch = choose(cands, now, false);
        if (!failure.isEmpty()) return TaskStatus.FAILED;
        if (ch.look != null || ch.waiting) {
            io.stopWalking();
            io.input(ch.look, 0, 0);
            if (act(ch, now) || ch.waiting || dig.waiting()) standStill = 0;
            else if (++standStill > STAND_STALL_TICKS && ch.look != null) {
                Pos stuck = ch.mine != null ? ch.mine : ch.batch.isEmpty() ? null : ch.batch.getFirst();
                if (stuck != null) giveUp(stuck, "could not break " + io.block(stuck).id() + " at " + stuck
                    + " (nothing sent for " + standStill + " ticks)");
                standStill = 0;
            }
            return TaskStatus.RUNNING;
        }
        if (dig.waiting()) {
            io.input(null, 0, 0); // answers first: a refused block in reach is tried again from here
            return TaskStatus.RUNNING;
        }
        // nothing in reach: walk to the nearest target, keeping the one chosen before unless another is clearly nearer
        double fx = io.feetX();
        double fy = io.eyeY();
        double fz = io.feetZ();
        Pos next = nearest(fx, fy, fz);
        if (next == null) {
            io.input(null, 0, 0);
            return TaskStatus.RUNNING;
        }
        Pos last = lastApproach;
        if (last != null && !last.equals(next) && remaining.contains(last) && !dig.inFlight(last)
            && Math.sqrt(boxDistSq(fx, fy, fz, last)) <= Math.sqrt(boxDistSq(fx, fy, fz, next)) + WALK_SWITCH_MARGIN) {
            next = last;
        }
        boolean breakable = false;
        for (Cand c : cands) if (c.pos().equals(next)) breakable = io.aim(next) != null;
        if (breakable) {
            io.input(null, 0, 0);
            return TaskStatus.RUNNING;
        }
        if (pockets != null && fy - EYE >= floorY + ELEVATED && boxCells(fx, fz).contains(next) && avoid(next)) {
            // standing on a target in a pocket (never broken from there): off it first, onto a cell in no pocket
            Pos off = openNear(new Pos((int) Math.floor(fx), floorY, (int) Math.floor(fz)), true);
            if (off != null) {
                walkGoal = off;
                walkRange = 0;
                TaskStatus s = baritone();
                return s == null ? TaskStatus.RUNNING : s;
            }
        }
        if (!next.equals(steppedOffFor) && boxCells(fx, fz).contains(next)) {
            // the target lies under the bot's box and no face of it is in sight from here: off it onto a free cell
            // beside it first, once per target; Baritone's approach would stop at once, the feet already in its
            // tightest goal (live 2026-10-12 19:20, ellen [1,3]: a wrong carpet under the box in the second last row,
            // no click in 4 VERIFY rounds of 300 ticks)
            Pos off = besideFree(next, fx, fz);
            if (off != null) {
                steppedOffFor = next;
                walkGoal = off;
                walkRange = 0;
                TaskStatus s = baritone();
                return s == null ? TaskStatus.RUNNING : s;
            }
        }
        if (onWall() && tryUnwedge()) return TaskStatus.RUNNING; // off the dripstone it stands on first
        if (avoid(next) && tryOpen(next, "it lies in a pocket walled in by blocks that are no target")) {
            TaskStatus s = escape != null ? TaskStatus.RUNNING : baritone();
            return s == null ? TaskStatus.RUNNING : s;
        }
        if (!next.equals(lastApproach)) {
            lastApproach = next;
            approachArrived = 0;
        }
        if (approachArrived >= 2) {
            // walked there twice, "arrived" each time, and still not breakable from where the bot stands
            giveUp(next, "could not break " + io.block(next).id() + " at " + next + " (not in reach and sight after"
                + " walking to it)");
            lastApproach = null;
            approachArrived = 0;
            io.input(null, 0, 0);
            return TaskStatus.RUNNING;
        }
        approaches++;
        if (approachArrived == 1) {
            walkGoal = next; // the approach stopped at the edge of reach: Baritone right next to it
            NukePockets pk = pockets;
            Pos beside = pk != null && pk.pocket(next) ? openNear(next, true) : null;
            if (beside != null) {
                // next to a target in a pocket are only its walls: the nearest cell in no pocket instead
                walkGoal = beside;
                walkRange = 0;
            }
            besideGoal = walkGoal;
        } else {
            approachTarget = next;
        }
        TaskStatus s = baritone();
        return s == null ? TaskStatus.RUNNING : s;
    }

    /** One tick of a Baritone walk (detour, start of the sweep, or to a target); null when it ended this tick. */
    private @Nullable TaskStatus baritone() {
        Pos goal = walkGoal;
        Pos target = approachTarget;
        if (target != null) {
            if (!remaining.contains(target)) {
                approachTarget = null;
                io.stopWalking();
                return null;
            }
            TaskStatus s = io.approach(target);
            if (s == TaskStatus.RUNNING) return TaskStatus.RUNNING;
            approachTarget = null;
            io.stopWalking();
            if (s == TaskStatus.SUCCESS && target.equals(lastApproach)) approachArrived++;
            if (s == TaskStatus.FAILED && !tryEscape("walk to " + target + " failed: " + io.walkError())
                && !(onWall() && tryUnwedge()) && !tryOpen(target, "the walk to it failed: " + io.walkError())) {
                giveUp(target, "cannot reach " + io.block(target).id() + " at " + target + ": " + io.walkError());
            }
            return null;
        }
        if (goal == null) return null;
        TaskStatus s = io.walkTo(goal, walkRange);
        if (s == TaskStatus.RUNNING) return TaskStatus.RUNNING;
        walkGoal = null;
        walkRange = 1;
        io.stopWalking();
        lastX = Double.NaN;
        if (escape != null) return null; // along the escape route: its next wall is in reach now, or it is given up
        if (walk == Walk.STAND && (goal.equals(lastApproach) || goal.equals(besideGoal))) {
            besideGoal = null;
            Pos t = lastApproach != null ? lastApproach : goal;
            if (s == TaskStatus.SUCCESS) approachArrived++;
            else if (!tryEscape("walk to " + goal + " failed: " + io.walkError())) {
                giveUp(t, "cannot reach " + io.block(t).id() + " at " + t + ": " + io.walkError());
            }
            return null;
        }
        if (walk == Walk.BARITONE_SWEEP) walk = Walk.SWEEP;
        if (s == TaskStatus.FAILED) {
            io.log("Fast nuke: walk to " + goal + " failed (" + io.walkError() + ")");
            if (tryEscape("walk to " + goal + " failed")) return null;
            NukeSweep.Plan pl = plan;
            if (pl != null && wp < pl.waypoints().size()) {
                wp++; // past this waypoint
                detoursHere = 0;
            }
        }
        return null;
    }

    // --------------------------------------------------------------------------------------------------- pockets

    /** The floor at the targets' level for {@link NukePockets}. */
    private final class FloorView implements NukePockets.Floor {
        @Override
        public boolean passable(Pos c) {
            // a full cube that is no target is climbed, no wall (live 2026-10-12 17:32, ellen [0,2] VERIFY: a checkerboard
            // of carpets and correct purple terracotta counted as pockets, every walk to a wrong carpet there refused)
            return c.y() == floorY && io.isLoaded(c) && (remaining.contains(c) || io.walkable(c) || io.climbable(c));
        }

        @Override
        public boolean breakable(Pos c) {
            // a wall: not walkable (a carpet the bot stands on is no wall: live 2026-10-12 17:30, ellen [0,2], the wedge
            // escape broke correct carpets under the bot)
            return c.y() == floorY && !remaining.contains(c) && io.isLoaded(c) && !io.isAir(c) && !io.walkable(c)
                && io.escapable(c);
        }
    }

    /**
     * Breaking walls (blocks that are no target) on {@link #route}: out of a pocket, into one to reach its target, or
     * free of a wedge. The walls not broken yet, then the walk to the route's exit ({@link #walkOut}).
     */
    private static final class Escape {
        /** What it is for, the head of the log line ("walled in at [..] by blocks that are no target (why)"). */
        final String kind;
        final Pos from;
        final NukePockets.Route route;
        final boolean walkOut;
        final Set<Pos> left;
        /** "pointed_dripstone at [x,y,z]" for each wall, for the log line. */
        final List<String> named = new ArrayList<>();
        int walks;

        Escape(String kind, Pos from, NukePockets.Route route, boolean walkOut, List<String> named) {
            this.kind = kind;
            this.from = from;
            this.route = route;
            this.walkOut = walkOut;
            this.left = new LinkedHashSet<>(route.walls());
            this.named.addAll(named);
        }
    }

    /** Starts breaking {@code r}'s walls for {@code kind}; the walk to its exit after them when {@code walkOut}. */
    private void startEscape(String kind, Pos from, NukePockets.Route r, boolean walkOut) {
        List<String> named = new ArrayList<>();
        for (Pos w : r.walls()) named.add(ItemIds.path(io.block(w).id()) + " at " + w);
        escape = new Escape(kind, from, r, walkOut, named);
        escapes++;
        io.stopWalking();
        walkGoal = null;
        approachTarget = null;
    }

    /**
     * Floor cell {@code c} lies in a pocket the bot is not standing in ({@code build.nukePocketGuard}): the box never
     * enters it and the run never breaks it from on top.
     */
    private boolean avoid(Pos c) {
        NukePockets pk = pockets;
        if (pk == null || !pk.pocket(c)) return false;
        // in it only with the feet: a box reaching in from the free part of a dripstone's cell is not (local 10x run,
        // 2026-10-12: that way the sweep slid into a carpet of the checkerboard)
        if (io.feetY() >= floorY + ELEVATED) return true;
        Pos feet = new Pos((int) Math.floor(io.feetX()), floorY, (int) Math.floor(io.feetZ()));
        return !pk.samePocket(c, feet);
    }

    /** The floor cell the bot stands in (a passable one its box covers if the feet's is not); null on top of blocks. */
    private @Nullable Pos floorCell() {
        NukePockets pk = pockets;
        if (pk == null || io.feetY() >= floorY + ELEVATED) return null;
        double fx = io.feetX();
        double fz = io.feetZ();
        Pos feet = new Pos((int) Math.floor(fx), floorY, (int) Math.floor(fz));
        FloorView f = new FloorView();
        if (f.passable(feet)) return feet;
        for (Pos c : boxCells(fx, fz)) if (f.passable(c)) return c;
        return feet;
    }

    /**
     * The cell nearest to {@code center} within {@link #OPEN_NEAR} that is in no pocket and passable ({@code
     * walkableNow}: walkable now, the bot can stand there at once), or null.
     */
    private @Nullable Pos openNear(Pos center, boolean walkableNow) {
        NukePockets pk = pockets;
        if (pk == null) return null;
        FloorView f = new FloorView();
        Pos best = null;
        int bestD = Integer.MAX_VALUE;
        for (int dx = -OPEN_NEAR; dx <= OPEN_NEAR; dx++) {
            for (int dz = -OPEN_NEAR; dz <= OPEN_NEAR; dz++) {
                int d = dx * dx + dz * dz;
                if (d >= bestD) continue;
                Pos c = new Pos(center.x() + dx, floorY, center.z() + dz);
                if (!(walkableNow ? io.isLoaded(c) && io.walkable(c) : f.passable(c)) || pk.pocket(c)) continue;
                best = c;
                bestD = d;
            }
        }
        return best;
    }

    /**
     * The walker's path check for this run's Baritone walks ({@link Walker#pathCheck}): a path whose feet step into a
     * pocket it did not start in is refused (Baritone would leave the bot there, walled in). Null: fine.
     */
    @Nullable String pathCheck(List<Pos> cells) {
        NukePockets pk = pockets;
        if (pk == null || cells.isEmpty()) return null;
        Pos start = cells.getFirst();
        for (int i = 1; i < cells.size(); i++) {
            Pos c = cells.get(i);
            if (c.y() != floorY || !pk.pocket(c) || pk.samePocket(c, start)) continue;
            return "steps into a pocket walled in by blocks that are no target at " + c;
        }
        return null;
    }

    /**
     * Walks keep failing: when the bot stands in a pocket, plans the way out through the fewest walls the run may
     * break ({@link Io#escapable}: what the clear removes anyway) and starts breaking them. True if it did.
     */
    private boolean tryEscape(String why) {
        NukePockets pk = pockets;
        if (pk == null || escape != null) return false;
        pk.forget(); // the world as it is now
        Pos at = floorCell();
        if (at == null || !pk.pocket(at)) return false;
        NukePockets.Route r = pk.escape(at);
        if (r == null || r.walls().isEmpty()) {
            if (!at.equals(noEscapeAt)) {
                noEscapeAt = at;
                io.log("Fast nuke: walled in at " + at + " by blocks that are no target (" + why + "), and no way out"
                    + " through blocks the run may break within " + NukePockets.ESCAPE_RADIUS);
            }
            return false;
        }
        startEscape("walled in at " + at + " by blocks that are no target (" + why + ")", at, r, true);
        return true;
    }

    /**
     * A target in a pocket the bot cannot walk to ({@link #avoid}): the way to a cell within {@link #OPEN_REACH} of it
     * through the fewest walls the run may break (none: just a walk there), once per target. True if a walk or a
     * break-in started. Live 2026-10-12 13:25, ellen [0,6]: 252 targets inside dripstone-walled pockets near the
     * platform centre were left for later again and again until the round was BLOCKED.
     */
    private boolean tryOpen(Pos target, String why) {
        NukePockets pk = pockets;
        if (pk == null || escape != null) return false;
        int tries = opened.merge(target, 1, Integer::sum);
        if (tries > 2) return false;
        pk.forget();
        List<Pos> from = startCells();
        if (from.isEmpty()) return false;
        FloorView f = new FloorView();
        // a low target (a carpet, a plate: its face hides behind the walls' 11/16 from afar) and a second try: from a
        // cell right next to it (live 2026-10-12 17:32, ellen [0,2]: a wrong blue carpet in a pocket was never in sight)
        boolean beside = tries > 1 || io.walkable(target);
        NukePockets.Route r = pk.route(from, c -> f.passable(c) && !c.equals(target)
            && (beside ? Math.abs(c.x() - target.x()) + Math.abs(c.z() - target.z()) == 1
                : horizontalDist(c, target) <= OPEN_REACH),
            Math.max(NukePockets.ESCAPE_RADIUS, chebyshev(from.getFirst(), target) + 8));
        if (r == null) return false;
        if (r.walls().isEmpty()) {
            if (from.contains(r.exit())) return false; // in reach already: not a matter of walls
            walkGoal = r.exit();
            walkRange = 0;
            return true;
        }
        startEscape("breaking into the pocket of " + ItemIds.path(io.block(target).id()) + " at " + target + " ("
            + why + ")", from.getFirst(), r, true);
        return true;
    }

    /**
     * The watchdog tripped and the bot is in no pocket: wedged (live 2026-10-12 12:44, ellen [0,6]: its box against a
     * dripstone cluster, every Baritone step cancelled, "MovementTraverse has taken too long", 0 broken, BLOCKED).
     * Breaks the walls its box stands on or against ({@link #WEDGE_GROW}); with none, from the second trip on, the
     * fewest walls on the way to free floor (a cell in no pocket with no wall beside it). True if it started.
     */
    private boolean tryUnwedge() {
        NukePockets pk = pockets;
        if (pk == null || escape != null) return false;
        pk.forget();
        FloorView f = new FloorView();
        double fx = io.feetX();
        double fz = io.feetZ();
        double h = Aim.PLAYER_HALF_WIDTH + WEDGE_GROW;
        List<Pos> walls = new ArrayList<>();
        for (int x = (int) Math.floor(fx - h); x <= (int) Math.floor(fx + h); x++) {
            for (int z = (int) Math.floor(fz - h); z <= (int) Math.floor(fz + h); z++) {
                Pos c = new Pos(x, floorY, z);
                if (f.breakable(c)) walls.add(c);
            }
        }
        Pos at = new Pos((int) Math.floor(fx), floorY, (int) Math.floor(fz));
        if (!walls.isEmpty()) {
            startEscape("wedged at " + at + " against blocks that are no target", at,
                new NukePockets.Route(walls, List.of(at), at), false);
            return true;
        }
        if (watchdogTrips < 2) return false;
        List<Pos> from = startCells();
        if (from.isEmpty()) return false;
        NukePockets.Route r = pk.route(from, c -> f.passable(c) && !pk.pocket(c) && !from.contains(c)
            && wallFree(f, c), NukePockets.ESCAPE_RADIUS);
        if (r == null || r.walls().isEmpty()) return false;
        startEscape("wedged at " + at + " (no progress), on the way to free floor", from.getFirst(), r, true);
        return true;
    }

    /**
     * The bot stands on top of blocks that are no target and that it may break (a pointed dripstone's tip): no
     * target under its box, a wall among the cells under it. Walks from there fail (the pathfinder neither walks from
     * nor onto dripstone), so the wall under it goes first ({@link #tryUnwedge}).
     */
    private boolean onWall() {
        if (pockets == null || io.feetY() < floorY + ELEVATED) return false;
        FloorView f = new FloorView();
        boolean wall = false;
        for (Pos c : boxCells(io.feetX(), io.feetZ())) {
            if (remaining.contains(c)) return false;
            if (f.breakable(c)) wall = true;
        }
        return wall;
    }

    /** No wall (a cell that is not passable) next to {@code c}. */
    private boolean wallFree(FloorView f, Pos c) {
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            if (!f.passable(new Pos(c.x() + d[0], c.y(), c.z() + d[1]))) return false;
        }
        return true;
    }

    /**
     * The cells the bot's routes start from: its floor cell; on top of blocks, the cells its box is over (it steps
     * down into one or breaks the one it stands on).
     */
    private List<Pos> startCells() {
        Pos at = floorCell();
        if (at != null) return List.of(at);
        return boxCells(io.feetX(), io.feetZ());
    }

    /**
     * A walkable cell next to {@code target} (the four sides first, then the corners, nearest to the feet first) the
     * bot may stand in, in no pocket it would be walled in by; null if none.
     */
    private @Nullable Pos besideFree(Pos target, double fx, double fz) {
        if (floorY == Integer.MIN_VALUE) return null;
        Pos best = null;
        double bestD = Double.MAX_VALUE;
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
            Pos c = new Pos(target.x() + d[0], floorY, target.z() + d[1]);
            if (!io.isLoaded(c) || !io.walkable(c) || remaining.contains(c) || avoid(c)) continue;
            double dist = Math.hypot(c.x() + 0.5 - fx, c.z() + 0.5 - fz) + (d[0] != 0 && d[1] != 0 ? 1.0 : 0.0);
            if (dist < bestD) {
                bestD = dist;
                best = c;
            }
        }
        return best;
    }

    /** Chebyshev distance between two cells (x, z). */
    private static int chebyshev(Pos a, Pos b) {
        return Math.max(Math.abs(a.x() - b.x()), Math.abs(a.z() - b.z()));
    }

    /** Horizontal distance from the centre of cell {@code c} to the nearest point of cell {@code t}. */
    private static double horizontalDist(Pos c, Pos t) {
        double dx = Math.max(Math.max(t.x() - (c.x() + 0.5), 0), (c.x() + 0.5) - (t.x() + 1));
        double dz = Math.max(Math.max(t.z() - (c.z() + 0.5), 0), (c.z() + 0.5) - (t.z() + 1));
        return Math.hypot(dx, dz);
    }

    /**
     * Breaking out: the route's walls in reach, nearest first, standing; one out of reach is brought in reach by a walk
     * along the route. All broken: one log line, and Baritone walks out to the route's exit.
     */
    private TaskStatus escapeTick(long now) {
        Escape e = escape;
        if (e == null) return TaskStatus.RUNNING;
        e.left.removeIf(c -> io.isLoaded(c) && io.isAir(c));
        if (e.left.isEmpty()) {
            escape = null;
            pocketsChanged();
            io.log("Fast nuke: " + e.kind + "; broke " + String.join(", ", e.named) + " (" + e.named.size()
                + (p.artCleared() ? " of them, the clear removes them anyway)" : " of them, art to re-place)")
                + (e.walkOut ? " and walking to " + e.route.exit() : ""));
            if (!e.walkOut) return TaskStatus.RUNNING;
            walkGoal = e.route.exit();
            walkRange = 0;
            if (walk == Walk.SWEEP) walk = Walk.BARITONE_SWEEP;
            TaskStatus s = baritone();
            return s == null ? TaskStatus.RUNNING : s;
        }
        double fx = io.feetX();
        double ey = io.eyeY();
        double fz = io.feetZ();
        double r = p.reach() - REACH_MARGIN;
        List<Cand> cands = new ArrayList<>();
        for (Pos c : e.left) {
            if (dig.inFlight(c)) continue;
            String no = io.refusal(c);
            if (no != null || !io.escapable(c)) {
                abandonEscape(no != null ? "protected: " + no : io.block(c).id() + " at " + c + " may not be broken");
                return TaskStatus.RUNNING;
            }
            double d2 = boxDistSq(fx, ey, fz, c);
            if (d2 <= r * r) cands.add(new Cand(c, -1e9 + Math.sqrt(d2), true, r - Math.sqrt(d2)));
        }
        cands.sort((a, b) -> Double.compare(a.urgency(), b.urgency()));
        Choice ch = choose(cands, now, false);
        if (!failure.isEmpty()) return TaskStatus.FAILED;
        io.input(ch.look, 0, 0);
        if (act(ch, now) || ch.look != null || ch.waiting || dig.waiting()) return TaskStatus.RUNNING;
        // the next wall is out of reach (or out of sight): along the route to the cell before it
        List<Pos> path = e.route.path();
        Pos stand = null;
        for (int i = 1; i < path.size() && stand == null; i++) if (e.left.contains(path.get(i))) stand = path.get(i - 1);
        if (stand == null || ++e.walks > ESCAPE_WALKS) {
            abandonEscape("the next block to break is not in reach and sight");
            return TaskStatus.RUNNING;
        }
        walkGoal = stand;
        walkRange = 0;
        TaskStatus s = baritone();
        return s == null ? TaskStatus.RUNNING : s;
    }

    private void abandonEscape(String why) {
        Escape e = escape;
        if (e == null) return;
        escape = null;
        pocketsChanged();
        io.log("Fast nuke: gave up breaking walls (" + e.kind + "): " + why);
    }

    /** Pockets broken out of so far. */
    int escapes() {
        return escapes;
    }

    // ----------------------------------------------------------------------------------------------------- end

    private TaskStatus finish() {
        if (finished) return failure.isEmpty() ? TaskStatus.SUCCESS : TaskStatus.FAILED;
        if (leftoverFrom >= 0) leftoverTicks = ticks - leftoverFrom;
        finished = true;
        log("done");
        List<String> problems = new ArrayList<>();
        if (!refused.isEmpty()) problems.add("refused (protected): " + summary(refused));
        if (!failed.isEmpty()) problems.add(summary(failed));
        if (problems.isEmpty()) return TaskStatus.SUCCESS;
        failure = String.join("; ", problems);
        return TaskStatus.FAILED;
    }

    private static String summary(List<String> items) {
        if (items.size() <= 5) return String.join(", ", items);
        return String.join(", ", items.subList(0, 5)) + " and " + (items.size() - 5) + " more";
    }

    /** The round's log line: blocks, ticks per block, STARTs per tick, the walk, retries. */
    String summaryLine(String how) {
        StringBuilder b = new StringBuilder();
        b.append("Break round (FAST) ").append(how).append(": ").append(done).append('/').append(total)
            .append(" broken in ").append(ticks).append(" ticks");
        if (done > 0) {
            b.append(String.format(Locale.ROOT, " (%.2f/block, %.1f blocks/s at 20 ticks/s)", (double) ticks / done,
                done * 20.0 / Math.max(1, ticks)));
        }
        b.append("; ").append(dig.instants).append(" instant (up to ").append(maxInTick).append(" per tick, cap ")
            .append(perTick).append(perTick != basePerTick() ? " after " + offLookRefusals + " off-look refusals" : "")
            .append("), ").append(dig.stopped).append(" mined (").append(minedTicks).append(" ticks)");
        if (p.lambda().on()) {
            b.append("; Lambda OldGrim: ").append(dig.lambdaMined).append(" mined, ").append(dig.lambdaQuick)
                .append(" at 0.7 in one tick, ").append(dig.lambdaSecondaries).append(" double breaks, ")
                .append(dig.bursts).append(" bursts, ").append(dig.fakeStarts).append(" fake STARTs")
                .append(lambdaOff ? ", then off (refused)" : "");
        }
        NukeSweep.Plan pl = sweepPlan;
        if (pl != null) {
            b.append(String.format(Locale.ROOT, "; sweep %d lanes, %.0f blocks, %d ticks walking, %d holding (%d for"
                + " the backlog), %d swept%s", pl.lanes().size(), pl.length(), walkTicks, holdTicks, backlogTicks,
                sweptBroken, wayBroken > 0 ? ", " + wayBroken + " blocks in the way broken" : ""));
            if (pl.isTour()) {
                b.append(String.format(Locale.ROOT, " (a tour of lane segments, %d skipped)", segmentsSkipped));
            }
            if (leftoverFrom >= 0 && leftovers > 0) {
                b.append(String.format(Locale.ROOT, "; %d left after the sweep, done in %d ticks", leftovers,
                    leftoverTicks()));
                if (leftoversPlanned && (leftoversAir > 0 || leftoverStands > 0)) {
                    b.append(String.format(Locale.ROOT, " (%d already broken, a route over %d stands, %.0f blocks, %d"
                        + " broken on it%s)", leftoversAir, leftoverStands, leftoverWalk, leftoversRouted,
                        leftoverBaritoneLegs > 0 ? ", " + leftoverBaritoneLegs + " legs by Baritone" : ""));
                }
            }
            if (lateAir > 0) b.append("; ").append(lateAir).append(" retried blocks broken by their late air");
        }
        if (p.mineMoving()) {
            b.append(String.format(Locale.ROOT, "; mining on the move: %d started walking, %d sliding, %d ticks walking"
                + " while mining%s; held %d in the way, %d to stop for one, %d backlog, %d Haste, %d edge; while mining"
                + " %d no way on, %d its reach, %d to stop for one, %d backlog, %d Haste", movingStarts, slideStarts,
                mineWalkTicks, speedKnown ? String.format(Locale.ROOT, " (top speed %.3f)", topSpeed)
                : " (top speed never known)", holdsBy[0], holdsBy[1], holdsBy[2], holdsBy[3], holdsBy[4],
                mineHoldsBy[0], mineHoldsBy[1], mineHoldsBy[2], mineHoldsBy[3], mineHoldsBy[4]));
        }
        if (dig.delayedSent > 0 || pairSlowOff) {
            b.append("; delayed ").append(dig.delayedBroken).append('/').append(dig.delayedSent).append(" broken, ")
                .append(dig.delayedTimedOut).append(" timed out");
            if (pairSlowOff) b.append(", then mined (pairing off)");
        }
        if (hasteWaits > 0) {
            b.append("; ").append(hasteWaits).append(" Haste waits (").append(hasteWaitTicks).append(" ticks)");
        }
        if (sidesteps > 0) b.append("; ").append(sidesteps).append(" sidesteps");
        if (guardTurns + guardHolds > 0) {
            b.append("; edge guard ").append(guardTurns).append(" turns, ").append(guardHolds).append(" holds");
        }
        if (escapes > 0) b.append("; ").append(escapes).append(" pocket escapes");
        if (detours > 0) b.append("; ").append(detours).append(" detours");
        if (approaches > 0) b.append("; ").append(approaches).append(" walks to a target");
        if (selects > 0) b.append("; ").append(selects).append(" tool selects");
        if (dig.refused + dig.unknown + dig.aborted > 0) {
            b.append("; retried ").append(dig.refused).append(" refused, ").append(dig.unknown).append(" unanswered, ")
                .append(dig.aborted).append(" aborted");
        }
        if (!byId.isEmpty()) {
            List<Map.Entry<String, int[]>> kinds = new ArrayList<>(byId.entrySet());
            kinds.sort((x, y) -> Integer.compare(y.getValue()[0], x.getValue()[0]));
            b.append("; by block:");
            int n = 0;
            for (Map.Entry<String, int[]> e : kinds) {
                if (n++ >= 6) {
                    b.append(" ...");
                    break;
                }
                b.append(' ').append(ItemIds.path(e.getKey())).append(' ').append(e.getValue()[0]);
            }
        }
        return b.toString();
    }

    private void log(String how) {
        summaryLogged = true;
        io.log(summaryLine(how));
    }
}
