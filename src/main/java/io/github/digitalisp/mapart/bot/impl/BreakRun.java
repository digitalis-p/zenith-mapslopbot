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
import java.util.function.Consumer;

/**
 * Breaks a list of blocks in rounds, {@code build.breakMode} DOUBLE.
 * Pure decisions, tick by tick; {@link BreakBlocksTask} runs it on ZenithProxy ({@link Io}), the tests against a
 * simulated server. With {@code pairing} false it breaks one block per round with the same vanilla client timing (the
 * simulation's SINGLE baseline; production SINGLE stays ZenithProxy's own left click).
 *
 * <p>A round:
 * <ol>
 *   <li>The next target: the nearest, except that one within reach wanting what the hand holds goes first
 *       ({@link ToolRack#next}). Walk until a face of it is in reach and sight ({@link Io#approach}).</li>
 *   <li>Its partner ({@link BreakPairs}): a target in reach and sight from where the bot stands with the same tool group
 *       whose break times fit; the slower of the two is the delayed block. None in sight, but one within
 *       {@link #PAIR_SPAN} blocks of the target: walk to a stand between the two first ({@link Io#approachPair},
 *       once per target). None (or the target is instant, or was just
 *       re-mined after a timeout, or the tool has fewer than 2 uses above {@code build.toolSafetyFloor}): a single.</li>
 *   <li>The tool ({@link ToolChoice} through {@link Io#tool}) in the main hand: a hotbar select, never while a delayed
 *       block is still progressing on the server (its progress uses the item held each tick), nor to another tool group
 *       while a STOP is unanswered (network jitter can make any STOP the server's delayed destroy). The tools were put
 *       in their own hotbar slots before the first round ({@link ToolRack#arrange}). Near the floor the round waits
 *       until the wear of the blocks in flight showed (it arrives a round trip later).</li>
 *   <li>Look at the first block on one tick; on the next, if that rotation is in effect and hits it: the delayed block's
 *       START, [ABORT,] STOP and a swing, and the look turns to the active block; on the tick after, its START.</li>
 *   <li>The active block is mined to progress 1 (with the item held each tick) and stopped ({@link DigClient}); then the
 *       vanilla 5-tick delay. A block counts as broken only when the server shows air. A delayed block that does not
 *       break in time is mined again on its own; a refused or unanswered STOP is retried ({@link #MAX_ATTEMPTS}).
 *       After {@link #TIMEOUTS_BEFORE_PAIRING_OFF} delayed timeouts in a row the run pairs no more.</li>
 * </ol>
 * Protected blocks ({@link Io#refusal}) are never touched, fluids never tried; both are listed in the failure. When the
 * tool the next block needs is at the floor the run fails at once with {@link BotActions#TOOL_AT_FLOOR}.
 */
final class BreakRun<S> {
    /** Tries per block (a refused or unanswered STOP, an aborted dig, a delayed block that never broke) before it is given up. */
    static final int MAX_ATTEMPTS = 3;
    /** Ticks a started pair may wait for the look at its active block; then the active block is left for later. */
    static final int LOOK_TIMEOUT = 20;
    /** Raycasts spent per round looking for a partner in sight. */
    static final int PARTNER_TRIES = 6;
    /**
     * Delayed blocks in a row that never showed (no delayed block broke in between) after which the run pairs no more:
     * the server is not keeping them (an anti-cheat cancelling the early STOP), so pairs would only cost timeouts.
     */
    static final int TIMEOUTS_BEFORE_PAIRING_OFF = 3;
    /**
     * Pair stand: a same-tool partner whose centre lies within this many blocks (horizontally) of the round's first
     * target but out of sight from where the bot stopped is worth walking for ({@link Io#approachPair}): both then lie
     * within about 1.5 blocks of the stand cell, well inside reach.
     */
    static final double PAIR_SPAN = 3.0;
    /**
     * Ticks after a block of ours showed air before a round near the floor counts on the tools' cached wear: the slot
     * update with the use it cost may follow the block update by a tick.
     */
    static final int WEAR_GRACE_TICKS = 3;
    /**
     * A target being walked to is kept unless another one is nearer by this much (blocks, eye to centre). Without it two
     * targets at the same distance on either side of the bot swapped places every tick (the smallest drift decides), and
     * each swap restarted Baritone's path, so the bot never moved (local 10x run, 2026-10-11: 1,000 path searches each
     * in 31 s).
     */
    static final double WALK_SWITCH_MARGIN = 1.0;

    /** What to hold for one block ({@link ToolChoice}). */
    record Tool(ToolChoice.Kind kind, String group, int usesLeft, String wornReason) {
        static Tool hand() {
            return new Tool(ToolChoice.Kind.HAND, ToolRack.HAND, Integer.MAX_VALUE, "");
        }
    }

    /** The game for one run. */
    interface Io<S> extends DigClient.Io<S> {
        boolean isLoaded(Pos p);

        /** The server's block at {@code p} (as cached; never predicted). */
        BlockSpec block(Pos p);

        /** Why {@code p} must never be broken (a protected id, an unbreakable block), or null. */
        @Nullable String refusal(Pos p);

        /**
         * Group and speed of breaking {@code p} with what {@link ToolChoice} picks (may be cached per block state), or
         * null when unloaded, air, a fluid, refused, or the tool for it is worn.
         */
        BreakPairs.@Nullable Info info(Pos p);

        /** What to hold for {@code p} now, with the tools' current wear. */
        Tool tool(Pos p);

        /** True if the main hand holds what {@code t} says (and a held tool is above the floor); else starts the switch. */
        boolean hold(Tool t);

        /** The {@link Info#group} the main hand serves now. */
        String heldGroup();

        /** Squared distance from the eye to the centre of {@code p}. */
        double distSq(Pos p);

        /** Walks until a face of {@code p} is in reach and sight (or, unloaded, near it). FAILED: {@link #walkError}. */
        TaskStatus approach(Pos p);

        /**
         * Walks until faces of both {@code a} and {@code b} are in reach and sight (a stand cell between them): SUCCESS
         * there, FAILED when no such cell is reached ({@link #walkError}); the run then breaks {@code a} alone.
         */
        TaskStatus approachPair(Pos a, Pos b);

        String walkError();

        void stopWalking();

        /** A rotation from the eye now that hits a visible face of {@code p} within reach, or null. */
        @Nullable S aim(Pos p);

        /** The rotation in effect now (sent with the last movement packet), if it hits {@code shot}'s block within reach. */
        @Nullable S inEffect(S shot);

        /** This tick's input: turn to {@code shot} (null: keep the rotation), no movement keys. */
        void look(@Nullable S shot);

        /** Last tick's input request was executed and this tick's is pending; no container open. */
        boolean canAct();

        void log(String line);
    }

    private enum Phase { FIRST, SECOND }

    /** The round being started. */
    private final class Round {
        final BreakPairs.Round<Pos> plan;
        final Map<Pos, S> shots = new HashMap<>();
        Phase phase = Phase.FIRST;
        long since;

        Round(BreakPairs.Round<Pos> plan, long now) {
            this.plan = plan;
            this.since = now;
        }
    }

    private final Io<S> io;
    private final boolean pairing;
    private final double reach;
    private final int floor;
    private final Consumer<Pos> broken;
    private final DigClient<S> dig;
    private final Set<Pos> remaining;
    private final int total;
    private final List<String> refused = new ArrayList<>();
    private final List<String> failed = new ArrayList<>();
    /** Mined again on their own: a delayed block that timed out, a refused or unanswered STOP. */
    private final Set<Pos> noPair = new HashSet<>();
    private final Map<Pos, Integer> attempts = new HashMap<>();
    /** Single-break estimate of each block when its round started (for the log). */
    private final Map<Pos, Integer> singleEstimate = new HashMap<>();
    private @Nullable Round round;
    private String failure = "";
    private int done;
    private long ticks;
    private long digTicks;
    private int pairs;
    private int singles;
    private int estimateBroken;
    /** The latest round plan of each block (for the per-block-type part of the log line). */
    private final Map<Pos, Planned> planned = new HashMap<>();
    /** Block id → {broken, sum of speeds, paired, instant}, in order of first break. */
    private final Map<String, double[]> byId = new java.util.LinkedHashMap<>();
    private int estimated;
    private boolean finished;
    /** Delayed blocks timed out since the last delayed block that broke. */
    private int timeoutsInRow;
    /** Delayed timeouts when pairing was switched off ({@link #TIMEOUTS_BEFORE_PAIRING_OFF}); 0 while it is on. */
    private int pairingOffAfter;
    /** The pair being walked to ({@link #PAIR_SPAN}), or null. */
    private @Nullable Pos detourFirst;
    private @Nullable Pos detourPartner;
    /** First targets a pair stand was walked for already (once each). */
    private final Set<Pos> detoured = new HashSet<>();
    /**
     * A delayed block an earlier run left on the server (that task was cancelled with it pending: the setback escape,
     * a stop), and the run tick until which it may still break ({@link #lingering}); null when none.
     */
    private @Nullable Pos lingeringPos;
    private long lingeringUntil;
    private boolean summaryLogged;
    /** The engine wants to park: no new round or walk; parks once nothing is pending ({@link #holdForPark}). */
    private boolean holdForPark;
    /**
     * Blocks to mine again first (a delayed block that timed out, a refused or unanswered STOP): the server may still
     * hold them, so they are done with the tool in hand before anything else.
     */
    private final Set<Pos> retryFirst = new LinkedHashSet<>();
    /** Pair stands walked to, and those that failed (logged). */
    private int detours;
    private int detoursFailed;
    /** The target the bot is walking to ({@link #WALK_SWITCH_MARGIN}), null when none. */
    private @Nullable Pos walkTarget;

    BreakRun(List<Pos> targets, boolean pairing, double reach, int floor, boolean abortBeforeStop, Io<S> io,
             Consumer<Pos> broken) {
        this.io = io;
        this.pairing = pairing;
        this.reach = reach;
        this.floor = Math.max(0, floor);
        this.broken = broken;
        this.dig = new DigClient<>(io, abortBeforeStop);
        this.remaining = new LinkedHashSet<>(targets);
        this.total = remaining.size();
    }

    String failure() {
        return failure;
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

    int pairs() {
        return pairs;
    }

    int singles() {
        return singles;
    }

    /** Ticks run so far. */
    long ticks() {
        return ticks;
    }

    /** Pairing was switched off for the rest of the run ({@link #TIMEOUTS_BEFORE_PAIRING_OFF}). */
    boolean pairingOff() {
        return pairingOffAfter > 0;
    }

    /** Server mining-clock ticks per client tick ({@link DigClient#serverClock(double)}); 1 by default. */
    void serverClock(double serverTicksPerClientTick) {
        dig.serverClock(serverTicksPerClientTick);
    }

    /**
     * Between two rounds with no delayed block progressing on the server: the engine may park the run here (eat,
     * stand still). Never mid-block, never while the server still ticks a delayed block with the held tool.
     */
    boolean canPark() {
        return round == null && !dig.digging() && !dig.delayedPending() && !dig.stopPending() && !lingeringPending();
    }

    /**
     * The task before this one ended with its delayed block {@code p} still on the server, which may break it (with the
     * item held then) within {@code ticksLeft} client ticks: until it shows air or that time passed, this run starts no
     * round and switches nothing, as for its own delayed blocks. The server has one delayed slot: a pair sent meanwhile
     * would not get it.
     */
    void lingering(Pos p, long ticksLeft) {
        lingeringPos = p;
        lingeringUntil = ticks + Math.max(0, ticksLeft);
    }

    /**
     * The delayed block of this run still unanswered (to hand on when the task ends: {@link #lingering}), and the
     * client ticks left until its deadline; null if none.
     */
    java.util.Map.@Nullable Entry<Pos, Long> pendingDelayed(long now) {
        DigClient.Flight f = dig.pendingDelayed();
        return f == null ? null : java.util.Map.entry(f.pos, Math.max(0, f.deadline - now));
    }

    private boolean lingeringPending() {
        Pos p = lingeringPos;
        if (p == null) return false;
        if (ticks >= lingeringUntil || (io.isLoaded(p) && io.isAir(p))) {
            lingeringPos = null;
            return false;
        }
        return true;
    }

    /**
     * The engine asks to park (eat, stand still): while set, no new round, walk or pair stand starts and a round that
     * has sent nothing yet is dropped, so {@link #canPark} turns true once the blocks in flight are answered (with pairs
     * back to back it otherwise never would: the delayed block's air comes after the next round started).
     */
    void holdForPark(boolean on) {
        holdForPark = on;
    }

    /** After a park: the round is chosen again from where the bot stands. */
    void resume() {
        round = null;
    }

    /** The task ends: ABORT the block being mined, as the vanilla client does when the button is released. */
    void release() {
        dig.abortActive();
        if (!summaryLogged) log("cancelled");
    }

    TaskStatus tick(long now) {
        ticks++;
        for (DigClient.Event e : dig.poll(now)) handle(e);
        if (!failure.isEmpty()) return TaskStatus.FAILED;
        if (dig.digging() || round != null || dig.waiting()) digTicks++;
        if (dig.digging()) {
            DigClient.Active<S> a = dig.active();
            io.look(a.shot);
            dig.tickActive(now, io.inEffect(a.shot), io.canAct());
            return TaskStatus.RUNNING;
        }
        if (round != null && holdForPark && round.phase == Phase.FIRST) {
            if (round.plan.pair()) pairs--;
            else singles--;
            round = null; // nothing sent yet: chosen again after the park
        }
        if (round != null) {
            tickRound(now);
            return failure.isEmpty() ? TaskStatus.RUNNING : TaskStatus.FAILED;
        }
        if (holdForPark) {
            io.stopWalking();
            io.look(null);
            return TaskStatus.RUNNING;
        }
        return pick(now);
    }

    private void handle(DigClient.Event e) {
        Pos p = e.pos();
        switch (e.outcome()) {
            case BROKEN -> {
                if (e.kind() == DigClient.Kind.DELAYED) timeoutsInRow = 0;
                markBroken(p);
            }
            case TIMED_OUT -> {
                noPair.add(p);
                retryFirst.add(p);
                io.log("Delayed break of " + p + " did not show within " + (e.at() - e.sentAt())
                    + " ticks; mining it again on its own");
                retry(p, "the delayed break of " + p + " never showed");
                if (++timeoutsInRow >= TIMEOUTS_BEFORE_PAIRING_OFF && pairingOffAfter == 0) {
                    pairingOffAfter = dig.delayedTimedOut;
                    io.log("Double break: " + TIMEOUTS_BEFORE_PAIRING_OFF
                        + " delayed breaks in a row never showed; pairing off for the rest of this round");
                }
            }
            case REFUSED, UNKNOWN, ABORTED -> {
                noPair.add(p);
                retryFirst.add(p);
                retry(p, "could not break " + io.block(p).id() + " at " + p + " ("
                    + e.outcome().name().toLowerCase(Locale.ROOT) + ")");
            }
        }
    }

    private void retry(Pos p, String why) {
        int n = attempts.merge(p, 1, Integer::sum);
        if (n >= MAX_ATTEMPTS && remaining.remove(p)) {
            failed.add(why);
            retryFirst.remove(p);
        }
    }

    private void markBroken(Pos p) {
        retryFirst.remove(p);
        if (!remaining.remove(p)) return;
        done++;
        Integer est = singleEstimate.get(p);
        if (est != null) {
            estimateBroken += est;
            estimated++;
        }
        Planned pl = planned.get(p);
        if (pl != null) {
            double[] k = byId.computeIfAbsent(pl.id(), id -> new double[4]);
            k[0]++;
            k[1] += pl.speed();
            if (pl.paired()) k[2]++;
            if (pl.speed() >= 1.0) k[3]++;
        }
        broken.accept(p);
    }

    /** What a block's round planned: its id, its speed per tick with the tool for it, whether it was paired. */
    private record Planned(String id, double speed, boolean paired) { }

    /** Chooses the next round, or walks to its first block. Targets found air, refused or given up cost no tick. */
    private TaskStatus pick(long now) {
        for (int n = 0; n < 64; n++) {
            TaskStatus s = pickOnce(now);
            if (s != null) return s;
        }
        io.look(null);
        return TaskStatus.RUNNING;
    }

    /** One try of {@link #pick}; null when a target was settled without acting (try the next one). */
    private @Nullable TaskStatus pickOnce(long now) {
        List<Pos> open = new ArrayList<>();
        for (Pos p : remaining) if (!dig.inFlight(p)) open.add(p);
        if (open.isEmpty()) {
            if (dig.waiting()) {
                io.stopWalking();
                io.look(null);
                return TaskStatus.RUNNING;
            }
            return finish();
        }
        if (detourFirst != null && detourPartner != null) {
            Pos a = detourFirst;
            Pos b = detourPartner;
            if (stillThere(a) && stillThere(b) && !dig.inFlight(a) && !dig.inFlight(b)) {
                TaskStatus s = io.approachPair(a, b);
                if (s == TaskStatus.RUNNING) return TaskStatus.RUNNING;
                if (s == TaskStatus.FAILED) {
                    detoursFailed++;
                    io.stopWalking();
                }
            }
            detourFirst = null; // there (or not): the round is chosen from this stand as usual
            detourPartner = null;
        }
        Pos again = null;
        for (Pos p : retryFirst) {
            if (remaining.contains(p) && !dig.inFlight(p)) {
                again = p; // the server may still hold it (a delayed slot): mine it now, with the tool in hand
                break;
            }
        }
        Pos pick = again != null ? again : ToolRack.next(open, io::distSq, this::group, io.heldGroup(), reach);
        Pos w = walkTarget;
        if (again == null && pick != null && w != null && !pick.equals(w)) {
            // keep walking to the target already chosen unless the new one is clearly nearer (or in reach already)
            if (open.contains(w) && stillThere(w) && io.aim(pick) == null
                && Math.sqrt(io.distSq(pick)) > Math.sqrt(io.distSq(w)) - WALK_SWITCH_MARGIN) {
                pick = w;
            } else {
                walkTarget = null;
            }
        }
        Pos t = pick;
        if (t == null) return finish();
        if (!io.isLoaded(t)) return walk(t);
        BlockSpec spec = io.block(t);
        if (spec.isAir()) {
            markBroken(t);
            return null;
        }
        if (spec.id().equals("minecraft:water") || spec.id().equals("minecraft:lava")) {
            giveUp(t, spec.id() + " at " + t + " (fluids cannot be broken)");
            return null;
        }
        String no = io.refusal(t);
        if (no != null) {
            refused.add(no);
            remaining.remove(t);
            return null;
        }
        S shot = io.aim(t);
        if (shot == null) return walk(t);
        io.stopWalking();
        if (dig.delayedPending() || lingeringPending()) {
            // the server still ticks a delayed block with the held item: no switch, no new round until it shows
            io.look(shot);
            return TaskStatus.RUNNING;
        }
        Tool tool = io.tool(t);
        if (tool.kind() == ToolChoice.Kind.WORN) {
            if (dig.waiting()) {
                io.look(shot); // let the blocks in flight settle (their wear shows) before stopping
                return TaskStatus.RUNNING;
            }
            return toolAtFloor(tool, spec, t);
        }
        BreakPairs.Info ti = io.info(t);
        BreakPairs.Round<Pos> plan = new BreakPairs.Round<>(null, t);
        S partnerShot = null;
        boolean mayPair = pairing && pairingOffAfter == 0 && ti != null && !noPair.contains(t) && ti.canBeActive()
            && twoUses(tool);
        if (mayPair) {
            double r = reach + 0.9;
            List<Pos> near = new ArrayList<>();
            for (Pos c : open) {
                if (!c.equals(t) && !noPair.contains(c) && io.distSq(c) <= r * r) near.add(c);
            }
            int tries = 0;
            for (Pos c : BreakPairs.partners(t, ti, near, io::info, c -> c.distSq(t.x() + 0.5, t.y() + 0.5, t.z() + 0.5))) {
                if (tries++ >= PARTNER_TRIES) break;
                S cs = io.aim(c);
                if (cs == null) continue;
                BreakPairs.Round<Pos> r2 = BreakPairs.round(t, ti, c, io.info(c));
                if (r2.pair()) {
                    plan = r2;
                    partnerShot = cs;
                }
                break;
            }
        }
        if (!plan.pair() && mayPair && detoured.add(t)) {
            // no partner in sight from here: one just out of it is worth a few steps (a stand between the two)
            List<Pos> nearT = new ArrayList<>();
            for (Pos c : open) {
                if (c.equals(t) || noPair.contains(c)) continue;
                double dx = c.x() - t.x();
                double dz = c.z() - t.z();
                if (dx * dx + dz * dz <= PAIR_SPAN * PAIR_SPAN && Math.abs(c.y() - t.y()) <= 1) nearT.add(c);
            }
            List<Pos> far = BreakPairs.partners(t, ti, nearT, io::info,
                c -> c.distSq(t.x() + 0.5, t.y() + 0.5, t.z() + 0.5));
            if (!far.isEmpty()) {
                detours++;
                detourFirst = t;
                detourPartner = far.getFirst();
                io.look(null);
                return TaskStatus.RUNNING; // walked from the next tick on
            }
        }
        Round rd = new Round(plan, now);
        rd.shots.put(t, shot);
        if (plan.pair()) {
            Pos other = plan.delayed().equals(t) ? plan.active() : plan.delayed();
            rd.shots.put(other, partnerShot);
            pairs++;
            estimate(plan.delayed(), true);
            estimate(plan.active(), true);
        } else {
            singles++;
            estimate(t, false);
        }
        round = rd;
        walkTarget = null;
        tickRound(now);
        return failure.isEmpty() ? TaskStatus.RUNNING : TaskStatus.FAILED;
    }

    private void estimate(Pos p, boolean paired) {
        BreakPairs.Info i = io.info(p);
        if (i == null) return;
        planned.put(p, new Planned(io.block(p).id(), i.speed(), paired));
        if (!singleEstimate.containsKey(p)) singleEstimate.put(p, BreakPairs.singleTicks(i, dig.serverClock()));
    }

    /** A tool may break two blocks and stay above the floor (the hand always may). */
    private boolean twoUses(Tool t) {
        return t.kind() != ToolChoice.Kind.TOOL || t.usesLeft() - 1 > floor;
    }

    private @Nullable String group(Pos p) {
        if (!io.isLoaded(p)) return null;
        BreakPairs.Info i = io.info(p);
        return i == null ? "" : i.group();
    }

    private TaskStatus walk(Pos p) {
        walkTarget = p;
        TaskStatus s = io.approach(p);
        if (s == TaskStatus.FAILED) giveUp(p, "cannot reach " + p + ": " + io.walkError());
        return TaskStatus.RUNNING;
    }

    /** The target the run walks to now (committed), or null. */
    @Nullable Pos walkTarget() {
        return walkTarget;
    }

    /**
     * The task's watchdog saw no block broken and no movement for a long time: gives up the target the run is stuck on
     * (the one it walks to, else the round's first block), so the next tick picks another. Returns it, or null.
     */
    @Nullable Pos giveUpStuck(String why) {
        Pos p = walkTarget;
        if (p == null && round != null) p = round.plan.first();
        if (p == null || !remaining.contains(p)) return null;
        round = null;
        walkTarget = null;
        io.stopWalking();
        giveUp(p, "could not break " + io.block(p).id() + " at " + p + " (" + why + ")");
        return p;
    }

    private void giveUp(Pos p, String why) {
        failed.add(why);
        io.stopWalking();
        remaining.remove(p);
        retryFirst.remove(p);
    }

    private TaskStatus toolAtFloor(Tool tool, BlockSpec spec, Pos p) {
        failure = BotActions.TOOL_AT_FLOOR + ": " + tool.wornReason() + " (" + done + "/" + total + " broken; next "
            + spec.id() + " at " + p + ")";
        log("stopped");
        return TaskStatus.FAILED;
    }

    /** Hold the tool, look, then START the round's first block (and on the next tick a pair's active one). */
    private void tickRound(long now) {
        Round rd = round;
        if (rd == null) return;
        BreakPairs.Round<Pos> plan = rd.plan;
        if (rd.phase == Phase.SECOND) {
            Pos a = plan.active();
            S shot = rd.shots.get(a);
            if (!remaining.contains(a) || io.block(a).isAir()) {
                round = null;
                io.look(null);
                return;
            }
            S eff = dig.canStart(now) ? io.inEffect(shot) : null;
            io.look(shot);
            if (eff == null || !io.canAct()) {
                if (now - rd.since > LOOK_TIMEOUT) round = null; // left for a later round
                return;
            }
            dig.start(now, a, eff);
            round = null;
            return;
        }
        // FIRST: both blocks still there? (a partner that went meanwhile makes it a single)
        Pos first = plan.first();
        if (plan.pair()) {
            Pos other = plan.delayed().equals(first) ? plan.active() : plan.delayed();
            if (!stillThere(first) || !stillThere(other)) {
                Pos keep = stillThere(first) ? first : stillThere(other) ? other : null;
                round = null;
                pairs--;
                if (keep == null) return;
                singles++;
                Round single = new Round(new BreakPairs.Round<>(null, keep), now);
                single.shots.put(keep, rd.shots.get(keep));
                round = single;
                rd = single;
                plan = single.plan;
                first = keep;
            }
        } else if (!stillThere(first)) {
            round = null;
            return;
        }
        S shot = rd.shots.get(first);
        Tool tool = io.tool(plan.active());
        if (tool.kind() == ToolChoice.Kind.WORN) {
            if (dig.waiting()) {
                io.look(shot); // let the blocks in flight settle (their wear shows) before stopping
                return;
            }
            round = null;
            toolAtFloor(tool, io.block(plan.active()), plan.active());
            return;
        }
        if (tool.kind() == ToolChoice.Kind.TOOL
            && tool.usesLeft() - dig.flights() - (plan.pair() ? 2 : 1) <= floor
            && (dig.waiting() || now - dig.lastBrokenAt() < WEAR_GRACE_TICKS)) {
            // the wear of the blocks in flight shows only with their answer (a round trip), the slot update up to a tick
            // after the block's air: near the floor, wait for it rather than count on uses that may be gone already
            io.look(shot);
            return;
        }
        if (plan.pair() && !twoUses(tool)) {
            // one use left above the floor: the first block alone
            pairs--;
            singles++;
            Pos keep = plan.active();
            Round single = new Round(new BreakPairs.Round<>(null, keep), now);
            single.shots.put(keep, rd.shots.get(keep));
            round = single;
            rd = single;
            plan = single.plan;
            first = keep;
            shot = rd.shots.get(first);
        }
        if (dig.stopPending() && !tool.group().equals(io.heldGroup())) {
            // a STOP that reached the server early (jitter) became its delayed destroy, which progresses with the held
            // item: the hand changes to another tool group only once every STOP in flight showed or was answered
            io.look(shot);
            return;
        }
        if (!io.hold(tool)) {
            io.look(shot);
            return;
        }
        S eff = dig.canStart(now) ? io.inEffect(shot) : null;
        io.look(eff != null && plan.pair() ? rd.shots.get(plan.active()) : shot);
        if (eff == null || !io.canAct()) {
            if (eff == null && now - rd.since > LOOK_TIMEOUT && dig.canStart(now)) {
                S again = io.aim(first);
                if (again == null) {
                    round = null; // out of sight now: chosen again (and walked to) on the next tick
                } else {
                    rd.shots.put(first, again);
                    rd.since = now;
                }
            }
            return;
        }
        if (plan.pair() && io.speedNow(plan.delayed()) >= BreakPairs.DELAY_MAX_SPEED) {
            // with the item held now the early STOP would break it on the spot (a plain fast break): mine it alone
            pairs--;
            singles++;
            Pos keep = plan.delayed();
            Round single = new Round(new BreakPairs.Round<>(null, keep), now);
            single.shots.put(keep, rd.shots.get(keep));
            round = single;
            rd = single;
            plan = single.plan;
            first = keep;
        }
        if (plan.pair()) {
            BreakPairs.Info di = io.info(plan.delayed());
            double speed = di != null ? di.speed() : io.speedNow(plan.delayed());
            dig.startDelayed(now, plan.delayed(), eff, speed);
            rd.phase = Phase.SECOND;
            rd.since = now;
        } else {
            dig.start(now, first, eff);
            round = null;
        }
    }

    private boolean stillThere(Pos p) {
        return remaining.contains(p) && io.isLoaded(p) && !io.block(p).isAir();
    }

    private TaskStatus finish() {
        if (finished) return failure.isEmpty() ? TaskStatus.SUCCESS : TaskStatus.FAILED;
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

    /** The round's log line: blocks, pairs, delayed breaks and timeouts, ticks per block against vanilla single. */
    String summaryLine(String how) {
        StringBuilder b = new StringBuilder();
        b.append("Break round (").append(pairing ? "DOUBLE" : "SINGLE").append(") ").append(how).append(": ")
            .append(done).append('/').append(total).append(" broken in ").append(ticks).append(" ticks");
        if (done > 0) {
            b.append(String.format(Locale.ROOT, " (%.1f/block; mining %.1f/block", (double) ticks / done,
                (double) digTicks / done));
            if (estimated > 0) {
                b.append(String.format(Locale.ROOT, " vs vanilla single %.1f/block", (double) estimateBroken / estimated));
            }
            b.append(')');
        }
        b.append("; ").append(pairs).append(" pairs, ").append(singles).append(" singles (")
            .append(dig.instants).append(" instant)");
        b.append("; delayed ").append(dig.delayedBroken).append('/').append(dig.delayedSent).append(" broken");
        if (dig.delayedBroken > 0) {
            b.append(String.format(Locale.ROOT, " (%+.1f ticks vs expected)",
                (double) dig.delayedLateTicks / dig.delayedBroken));
        }
        b.append(", ").append(dig.delayedTimedOut).append(" timed out");
        if (detours > 0) {
            b.append("; ").append(detours).append(" pair stands walked to");
            if (detoursFailed > 0) b.append(" (").append(detoursFailed).append(" not reached)");
        }
        if (pairingOffAfter > 0) b.append("; pairing off after ").append(pairingOffAfter).append(" timeouts");
        if (dig.refused + dig.unknown + dig.aborted > 0) {
            b.append("; retried ").append(dig.refused).append(" refused, ").append(dig.unknown).append(" unanswered, ")
                .append(dig.aborted).append(" aborted");
        }
        if (!byId.isEmpty()) {
            // per block type: broken, mean speed per tick with its tool, paired, instant (the biggest 8)
            List<Map.Entry<String, double[]>> kinds = new ArrayList<>(byId.entrySet());
            kinds.sort((x, y) -> Double.compare(y.getValue()[0], x.getValue()[0]));
            b.append("; by block:");
            int n = 0;
            for (Map.Entry<String, double[]> e : kinds) {
                if (n++ >= 8) {
                    b.append(" ...");
                    break;
                }
                double[] k = e.getValue();
                b.append(String.format(Locale.ROOT, " %s %d (%.2f/t, %d paired%s)", ItemIds.path(e.getKey()),
                    (int) k[0], k[1] / k[0], (int) k[2], k[3] > 0 ? ", " + (int) k[3] + " instant" : ""));
            }
        }
        return b.toString();
    }

    private void log(String how) {
        summaryLogged = true;
        io.log(summaryLine(how));
    }
}
