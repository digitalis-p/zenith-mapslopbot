package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The client side of digging for {@code build.breakMode} DOUBLE: vanilla 1.21.4 {@code MultiPlayerGameMode} timing
 * with the attack key held (as ZenithProxy's own left click), plus the delayed block of a double break.
 * It sends {@code ServerboundPlayerActionPacket}s itself
 * ({@link Io#send}); ZenithProxy's Bot never mines meanwhile, so its own destroy state stays idle and sends nothing.
 * Pure (no ZenithProxy calls), so it is unit-tested and simulated.
 *
 * <p>Per block, as the vanilla client with the key held (and ZenithProxy 3.7.2's {@code Bot.interactionTick}):
 * <ul>
 *   <li><b>START</b> (with a block-prediction sequence) and a swing; no progress in the START tick (by default,
 *       {@link #progressInStartTick}). A block whose speed is ≥ 1 breaks on the START ("insta mine"): nothing else is
 *       sent and no delay follows.</li>
 *   <li>Every following tick: progress += the destroy speed with the item held <i>now</i>, and a swing.</li>
 *   <li><b>STOP</b> once the progress reaches 1, {@code ceil(1/speed)} ticks after the START, then
 *       {@link #DESTROY_DELAY} ticks before the next START.</li>
 * </ul>
 * Why no progress in the START tick: the vanilla client adds one only when the key is pressed on the block
 * ({@code startAttack} then {@code continueAttack} in one tick); Grim 2.0 FastBreak predicts {@code ceil(1/speed) × 50}
 * ms from START to STOP and credits those 50 ms only to the first START of a connection (its target is never cleared),
 * so that cadence drifts +50 ms per block and flags after ~20 blocks, while this one keeps the balance at 0. Cost: one
 * tick per non-instant block (BreakRunSimTest).
 *
 * <p>At most one START per tick, except instant STARTs ({@link #startInstant}, the fast nuke): up to a cap per tick,
 * none while a block is mined or within the delay after a STOP. Nothing is predicted: the world cache shows only what the server sends, so a block
 * counts as broken only when the server shows air.
 *
 * <p><b>The delayed block</b> ({@link #startDelayed}): START, then at once (same tick) STOP for the same block and
 * face, and a swing (with {@link #abortBeforeStop} an ABORT between them; production sends none). The server's STOP
 * finds progress = speed × 1 &lt; 0.7 and keeps the block as its one "delayed destroy", which its own tick breaks once
 * speed(held item now) × (ticks since START + 1) ≥ 1, no packet from the client needed. The next START (the active
 * block of the pair) follows on the next tick. The delayed block shows air when the server breaks it (a plain block
 * update, no ack); if it does not by its deadline (expected break + {@link #DELAYED_GRACE_TICKS}) it is reported
 * {@link Kind#TIMED_OUT} and the caller mines it again on its own.
 *
 * <p>What the server has not answered yet is tracked per block ({@link Flight}): a STOPPED or instant block whose
 * ack arrived ({@link #REJECT_GRACE_TICKS} ago) without air was refused (the old state stays); no answer within
 * {@link #CONFIRM_MAX_TICKS} is unknown. Either is reported for a retry.
 *
 * <p><b>Lambda's OldGrim scheme</b> ({@code build.nukeLambda}, {@link #startLambda}, {@link #tickLambda},
 * {@link #lambdaSecondary}). Break timing ported from
 * Lambda, https://github.com/lambda-client/lambda (GPL-3.0), commit fa59ab1, {@code BreakManager.kt} (startBreaking
 * 790-880, updateBreakProgress 696-781, initNewBreak 496-534, onBlockBreak 605-632), {@code BreakInfo.kt} 137-164 and
 * the defaults of {@code BreakSettings.kt} 45-66; re-implemented on this class's packets and flights (no file copied).
 * A non-instant block: START, a fake START at {@link #OLD_GRIM_Y_OFFSET} blocks above it (out of the world: the server
 * ignores it as too far, acks its sequence), then progress {@code speed × (ticks since START)} each tick (Lambda's
 * fudge factor 1), a swing every tick, at {@link #OLD_GRIM_MIN_TICKS} ticks without the burst a burst of
 * {@link #OLD_GRIM_BURST} fake STARTs, and the STOP once the progress reaches {@link #LAMBDA_THRESHOLD} (the vanilla
 * server breaks at 0.7) and the block has been mined {@link #OLD_GRIM_MIN_TICKS} ticks. After every STOP
 * {@link #OLD_GRIM_BREAK_DELAY} ticks without a START, unless the block's burst went out (then a non-instant block may
 * start in the same tick; instant ones still wait). A block at 0.7..1 per tick: START, fake START, STOP in one tick.
 * Double break: the block just started is turned into the server's delayed destroy by an early STOP and a second one
 * is started as the active block.
 */
final class DigClient<S> {
    /** Vanilla client ticks between a completed (non-instant) break and the next START. */
    static final int DESTROY_DELAY = 5;
    /**
     * Ticks a delayed block may take beyond its expected break before it counts as timed out: one 2b2t round trip at
     * p99 is up to ~22 ticks, and the server ticks a delayed destroy by wall clock (Paper) but only while it runs.
     */
    static final int DELAYED_GRACE_TICKS = 40;
    /** Ticks after the ack before a stopped block that still shows counts as refused (block updates precede acks). */
    static final int REJECT_GRACE_TICKS = 2;
    /** Most ticks after its ack an instant block may wait for its air ({@link #instantAckTicks(int)}). */
    static final int INSTANT_ACK_MAX_TICKS = 20;
    /** A stopped or instant block with neither air nor its ack by then is given up as unknown (retried). */
    static final int CONFIRM_MAX_TICKS = 100;

    // Lambda's OldGrim scheme (ported, see the class doc)
    /** Fake STARTs go this far above the block ({@code BreakManager.OLD_GRIM_Y_OFFSET}, BreakManager.kt:160). */
    static final int OLD_GRIM_Y_OFFSET = 955;
    /**
     * Ticks without a START after a STOP ({@code oldGrimBreakDelay = 6}, BreakManager.kt:525, 628; counted down after
     * each tick, 169): the next START goes 6 ticks after the STOP.
     */
    static final int OLD_GRIM_BREAK_DELAY = 6;
    /** A mined block's STOP no earlier than this many ticks of mining (breakingTicks ≤ 6 waits, BreakManager.kt:768). */
    static final int OLD_GRIM_MIN_TICKS = 7;
    /** Fake STARTs in the burst once a mined block passes 6 ticks (BreakManager.kt:733-738). */
    static final int OLD_GRIM_BURST = 22;
    /** Progress at which the STOP goes out ({@code breakThreshold} 0.70, BreakSettings.kt:52). */
    static final double LAMBDA_THRESHOLD = 0.7;
    /** Ticks taken off the client's progress ({@code fudgeFactor} 1, BreakSettings.kt:53; BreakManager.kt:741). */
    static final int LAMBDA_FUDGE = 1;
    /**
     * A fake START goes only to a height at or above this (1.21 build limit: 319 in the overworld, 255 in the nether
     * and the end), so it never names a block that could exist; ZenithProxy's chunk cache ignores block updates there.
     */
    static final int WORLD_TOP = 320;

    enum Action { START, ABORT, STOP }

    /** What the digger needs from the game. */
    interface Io<S> {
        /**
         * Sends one {@code ServerboundPlayerActionPacket} for {@code pos} on the face {@code shot} hits: START and STOP
         * with a new block-prediction sequence (returned), ABORT with 0 like the vanilla client. -1 if not connected.
         */
        int send(Action action, Pos pos, S shot);

        /** One main-hand arm swing. */
        void swing();

        /** Vanilla destroy progress per tick on {@code pos} with the item in the main hand now, as the bot stands now. */
        double speedNow(Pos pos);

        /** The server's state of {@code pos} (as cached; nothing is predicted) is air. */
        boolean isAir(Pos pos);

        /** Highest block-changed-ack sequence the server sent on this connection, -1 if none. */
        int latestAck();
    }

    enum Kind {
        /** Delayed block of a pair: started and stopped, the server breaks it in its own tick. */
        DELAYED,
        /** Mined to progress 1 and stopped. */
        STOPPED,
        /** Broke on the START. */
        INSTANT
    }

    /** A block the server has not answered yet. */
    static final class Flight {
        final Pos pos;
        final Kind kind;
        final long sentAt;
        final int seq;
        /** DELAYED: the tick by which its air must show; else -1. */
        final long deadline;
        /** DELAYED: the tick the server should break it (START + ticks − 1). */
        final long expectedAt;
        long ackedAt = -1;
        /** A STOP of Lambda's scheme: waits {@link #instantAckTicks(int)} after its ack, as an instant block. */
        boolean lambda;

        Flight(Pos pos, Kind kind, long sentAt, int seq, long deadline, long expectedAt) {
            this.pos = pos;
            this.kind = kind;
            this.sentAt = sentAt;
            this.seq = seq;
            this.deadline = deadline;
            this.expectedAt = expectedAt;
        }
    }

    /** What became of a block in flight (or of the active one). */
    enum Outcome {
        /** The server shows air. */
        BROKEN,
        /** A delayed block that did not break by its deadline: mine it again on its own. */
        TIMED_OUT,
        /** The server acked the STOP/START and kept the block. */
        REFUSED,
        /** Neither air nor ack in time. */
        UNKNOWN,
        /** The active block was not done within its budget: aborted. */
        ABORTED
    }

    record Event(Pos pos, Kind kind, Outcome outcome, long sentAt, long at) { }

    /** The block being mined now. */
    static final class Active<S> {
        final Pos pos;
        final S shot;
        final long startedAt;
        final long budget;
        /** The STOP goes out no earlier than this many ticks after the START ({@link #start(long, Pos, Object, int)}). */
        int minTicks;
        double progress;
        /** Mined with Lambda's scheme ({@link #startLambda}): {@link #tickLambda}, not {@link #tickActive}. */
        boolean lambda;
        /** Lambda: the burst of fake STARTs went out ({@code bypassedDelay}, BreakInfo.kt:70). */
        boolean bypassed;
        /** Lambda: mined to a STOP whatever its speed (a retry). */
        boolean mined;

        Active(Pos pos, S shot, long startedAt, long budget) {
            this.pos = pos;
            this.shot = shot;
            this.startedAt = startedAt;
            this.budget = budget;
        }
    }

    private final Io<S> io;
    /**
     * The delayed block is set with START, ABORT, STOP (true) or START, STOP (false). Both put it into the server's
     * delayed slot (the ABORT does not move the server's destroy position off the block). The ABORT was meant to make
     * Grim FastBreak forget the START before the early STOP; Grim 2.0 no longer clears its target on ABORT, and its
     * PositionBreakB flags an ABORT on a face other than DOWN that is not followed by a START on that face (the
     * simulation counts both).
     */
    private final boolean abortBeforeStop;
    /**
     * The server's mining clock ({@code gameTicks}) per client tick, in (0, 1] ({@link #serverClock}). Paper sets
     * {@code gameTicks} from the wall clock, so a client ticking faster than 20/s (ZenithProxy's
     * {@code client.tickRate}, the local 10x speed mode) sees the server's break progress advance this much per tick.
     */
    private double clock = 1.0;
    /**
     * False (default): no progress in the START tick, STOP {@code ceil(1/speed)} ticks after START: the vanilla client
     * with the key held from one block to the next (the START comes from {@code continueDestroyBlock}), and ZenithProxy's
     * left click. True: the first tick of progress in the START tick, STOP {@code ceil(1/speed) − 1} ticks after START:
     * the vanilla client when the key is pressed on the block; one tick faster per block, but Grim 2.0 FastBreak adds 50
     * ms per block to its balance (see the class doc). For tests.
     */
    private boolean progressInStartTick;
    private @Nullable Active<S> active;
    private final Map<Pos, Flight> flights = new LinkedHashMap<>();
    /** Tick of the last START sent (at most one per tick, except instant STARTs, {@link #startInstant}). */
    private long lastStartAt = Long.MIN_VALUE;
    /** STARTs sent in tick {@link #lastStartAt}. */
    private int startsInTick;
    /** First tick a START may be sent again (the vanilla delay after a completed break). */
    private long nextStartAt = Long.MIN_VALUE;
    /** Client ticks without any START after a STOP ({@link #DESTROY_DELAY} by default, {@link #stopDelay}). */
    private int stopDelay = DESTROY_DELAY;
    /** Ticks after its ack before an instant block that still shows counts as refused ({@link #instantAckTicks(int)}). */
    private int instantAckTicks = REJECT_GRACE_TICKS;
    private final List<Event> pending = new ArrayList<>();

    // statistics
    int starts;
    int delayedSent;
    int delayedBroken;
    int delayedTimedOut;
    /** Sum over broken delayed blocks of (tick air showed − expected break tick). */
    long delayedLateTicks;
    int stopped;
    int instants;
    int refused;
    int unknown;
    int aborted;
    /** Tick a block of ours last showed air (its tool wear reaches the client about then, a slot update). */
    private long lastBrokenAt = Long.MIN_VALUE / 2;

    // Lambda's OldGrim scheme
    /** Send the fake START after each non-instant START (and the burst, {@link #lambdaBurst}). */
    private boolean lambdaFake = true;
    /** Send the burst of fake STARTs at 7 ticks of mining, which lets the next non-instant START skip the delay. */
    private boolean lambdaBurst = true;
    /** The last STOP was of a block whose burst went out ({@code bypassingOldGrimBreakDelay}, BreakManager.kt:629). */
    private boolean lambdaBypass;
    /** Tick of the last swing sent by the Lambda paths or {@link #startInstant} (one swing per tick). */
    private long swungAt = Long.MIN_VALUE;
    int fakeStarts;
    int bursts;
    /** Lambda: blocks mined (the active block), blocks at 0.7..1 broken by START + STOP in one tick, double breaks. */
    int lambdaMined;
    int lambdaQuick;
    int lambdaSecondaries;

    DigClient(Io<S> io, boolean abortBeforeStop) {
        this.io = io;
        this.abortBeforeStop = abortBeforeStop;
    }

    /**
     * Server mining-clock ticks per client tick (1 on a 20 TPS client; 0.1 when the client ticks 10x faster than the
     * wall clock that Paper's {@code gameTicks} follows), clamped to (0, 1]; anything else means 1. The client's
     * progress per tick (also an increment in the START tick, {@link #progressInStartTick}), the delayed block's
     * expected tick and deadline, and the active block's budget scale with it; {@link #DESTROY_DELAY} (client
     * behaviour), the instant rule (speed ≥ 1) and {@link BreakPairs#DELAY_MAX_SPEED} do not (the server's elapsed
     * ticks are 0 for both).
     */
    void serverClock(double serverTicksPerClientTick) {
        double s = serverTicksPerClientTick;
        clock = s > 0 && s < 1 ? s : 1.0;
    }

    double serverClock() {
        return clock;
    }

    /** See {@link #progressInStartTick}. */
    void progressInStartTick(boolean on) {
        progressInStartTick = on;
    }

    /** Client ticks for {@code serverTicks} ticks of the server's mining clock. */
    private long clientTicks(long serverTicks) {
        if (clock >= 1.0) return serverTicks;
        return (long) Math.ceil(serverTicks / clock - 1e-9);
    }

    /**
     * Ticks without a START after a STOP: {@link #DESTROY_DELAY} (vanilla, default), clamped to 0..{@link #DESTROY_DELAY}.
     * Grim FastBreak adds {@code 300 − delay} ms to its balance for a START within 275 ms of a STOP (any START, instant
     * ones too), so less than the vanilla 5 ticks is flagged after a few blocks.
     */
    void stopDelay(int ticks) {
        stopDelay = Math.max(0, Math.min(DESTROY_DELAY, ticks));
    }

    /**
     * Ticks after its ack before an instant block (an instant START, nothing else sent for it) that still shows counts
     * as refused ({@code build.nukeInstantAckTicks}): {@link #REJECT_GRACE_TICKS} by default, clamped to that ..
     * {@link #INSTANT_ACK_MAX_TICKS}. The server sends a broken block's air before the ack on a vanilla tick, but 2b2t
     * sends it a few ticks after the ack now and then (live j0024: 95 instant blocks counted refused, about 83 of
     * them broke on the retry's START before any STOP). Air within the wait ends the block as broken, so no retry START
     * goes out for it; a block still showing at its end is refused and tried again as before. Mined and delayed blocks
     * keep {@link #REJECT_GRACE_TICKS} and their deadline.
     */
    void instantAckTicks(int ticks) {
        instantAckTicks = Math.max(REJECT_GRACE_TICKS, Math.min(INSTANT_ACK_MAX_TICKS, ticks));
    }

    int instantAckTicks() {
        return instantAckTicks;
    }

    /** No block is being mined, the delay after the last break is over and no START went out this tick. */
    boolean canStart(long now) {
        return active == null && now >= nextStartAt && now != lastStartAt;
    }

    /**
     * Another instant START may go out this tick ({@link #startInstant}): no block is being mined, the delay after the
     * last STOP is over, and fewer than {@code perTick} STARTs went out this tick.
     */
    boolean canStartInstant(long now, int perTick) {
        if (active != null || now < nextStartAt) return false;
        return now != lastStartAt || startsInTick < Math.max(1, perTick);
    }

    /** STARTs sent in tick {@code now}. */
    int startsIn(long now) {
        return now == lastStartAt ? startsInTick : 0;
    }

    /**
     * START {@code pos} for an instant break ({@link #canStartInstant} must hold, {@code shot} names the face): only when
     * {@link Io#speedNow} is at least 1 with the item held now (else nothing is sent and false returned). The block is in
     * flight from now, as for {@link #start}; several may go out in one tick ({@code build.nukeStartsPerTick}). The swing
     * is the caller's ({@code swing} true: one now, as the vanilla client's per START).
     */
    boolean startInstant(long now, Pos pos, S shot, boolean swing) {
        if (io.speedNow(pos) < 1.0) return false;
        int seq = io.send(Action.START, pos, shot);
        if (swing) {
            io.swing();
            swungAt = now;
        }
        countStart(now);
        instants++;
        flights.put(pos, new Flight(pos, Kind.INSTANT, now, seq, -1, now));
        return true;
    }

    private void countStart(long now) {
        if (now == lastStartAt) {
            startsInTick++;
        } else {
            lastStartAt = now;
            startsInTick = 1;
        }
        starts++;
    }

    /** A block is being mined. */
    boolean digging() {
        return active != null;
    }

    @Nullable Active<S> active() {
        return active;
    }

    /** A delayed block was sent and has not shown air nor timed out: the held item must not change meanwhile. */
    boolean delayedPending() {
        for (Flight f : flights.values()) if (f.kind == Kind.DELAYED) return true;
        return false;
    }

    /**
     * A STOP (of a mined or a delayed block) has not been answered yet. A STOP that reaches the server early (network
     * jitter shortens START to STOP below 70% of the break time) becomes the server's delayed destroy, which progresses
     * with the item held each tick; an instant START never does.
     */
    boolean stopPending() {
        for (Flight f : flights.values()) if (f.kind != Kind.INSTANT) return true;
        return false;
    }

    /**
     * The server's one delayed slot should be free at tick {@code now}: every delayed block sent should have been broken
     * by the server {@code margin} ticks ago (its expected tick), whether or not its air has reached the client yet. A
     * second early STOP while the slot is busy is ignored by the server (the block would time out).
     */
    boolean delayedSlotFree(long now, int margin) {
        for (Flight f : flights.values()) if (f.kind == Kind.DELAYED && f.expectedAt + margin > now) return false;
        return true;
    }

    /** The delayed block sent and not answered yet (shown air or timed out), or null. */
    @Nullable Flight pendingDelayed() {
        for (Flight f : flights.values()) if (f.kind == Kind.DELAYED) return f;
        return null;
    }

    /** Tick a block of ours last showed air ({@link Event} BROKEN). */
    long lastBrokenAt() {
        return lastBrokenAt;
    }

    /** Number of blocks the server has not answered yet (each may have cost the held tool a use). */
    int flights() {
        return flights.size();
    }

    /** Blocks the server has not answered yet. */
    boolean waiting() {
        return !flights.isEmpty();
    }

    boolean inFlight(Pos p) {
        return flights.containsKey(p) || (active != null && active.pos.equals(p));
    }

    /**
     * The delayed block of a pair ({@link #canStart} must hold, the rotation in effect hits {@code shot}): START,
     * [ABORT,] STOP and a swing in this tick. {@code speed}: its destroy progress per tick with the tool held for the
     * pair (below 0.7, {@link BreakPairs#DELAY_MAX_SPEED}).
     */
    void startDelayed(long now, Pos pos, S shot, double speed) {
        startDelayed(now, pos, shot, speed, false);
    }

    /**
     * {@link #startDelayed(long, Pos, Object, double)}; {@code window}: the early STOP opens the same {@link #stopDelay}
     * ticks without any START as a mined block's STOP (Grim FastBreak counts every STOP for its delay balance). The fast
     * nuke sends slow blocks this way one after the other ({@link #delayedSlotFree}); the double break did not wait.
     */
    void startDelayed(long now, Pos pos, S shot, double speed, boolean window) {
        if (window) nextStartAt = now + stopDelay + 1;
        int seq = io.send(Action.START, pos, shot);
        if (abortBeforeStop) io.send(Action.ABORT, pos, shot);
        int stopSeq = io.send(Action.STOP, pos, shot);
        io.swing();
        countStart(now);
        delayedSent++;
        int ticks = ToolChoice.ticks(speed);
        long expected = now + clientTicks(Math.max(0, ticks - 1));
        long deadline = expected + clientTicks(Math.max(DELAYED_GRACE_TICKS, ticks));
        flights.put(pos, new Flight(pos, Kind.DELAYED, now, Math.max(seq, stopSeq), deadline, expected));
    }

    /**
     * START {@code pos} ({@link #canStart} must hold, the rotation in effect hits {@code shot}) and a swing. An instant
     * block is in flight from now; any other is mined ({@link #tickActive}) from the next tick (from this one with
     * {@link #progressInStartTick}).
     */
    void start(long now, Pos pos, S shot) {
        start(now, pos, shot, 0, false);
    }

    /**
     * {@link #start(long, Pos, Object)}, with the STOP no earlier than {@code minTicks} after the START: for a block the
     * server may see as instant while the client does not count on it (Haste about to run out). If the server broke it
     * on the START its air shows within a round trip and no STOP follows (a STOP on air: Grim AirLiquidBreak).
     */
    void start(long now, Pos pos, S shot, int minTicks) {
        start(now, pos, shot, minTicks, false);
    }

    /**
     * {@link #start(long, Pos, Object, int)}; {@code mined} true: mined to a STOP even when the client counts it as
     * instant (a block the server refused as instant: the STOP then breaks it whatever speed the server sees, and its air
     * ends the dig if the START did after all).
     */
    void start(long now, Pos pos, S shot, int minTicks, boolean mined) {
        double speed = io.speedNow(pos);
        int seq = io.send(Action.START, pos, shot);
        io.swing();
        countStart(now);
        if (speed >= 1.0 && !mined) {
            instants++;
            flights.put(pos, new Flight(pos, Kind.INSTANT, now, seq, -1, now));
            return;
        }
        long budget = clientTicks(Math.min(1200, (long) ToolChoice.ticks(speed) * 2 + 60));
        active = new Active<>(pos, shot, now, budget);
        active.progress = progressInStartTick ? speed * clock : 0;
        active.minTicks = Math.max(0, minTicks);
    }

    /**
     * One tick of the active block: progress with the item held now, then the STOP once it reaches 1 (only when
     * {@code inEffect} is the rotation in effect hitting the block and {@code canAct}; else it waits) and a swing. The
     * active block showing air ends it as broken; past its budget it is ABORTed.
     */
    void tickActive(long now, @Nullable S inEffect, boolean canAct) {
        Active<S> a = active;
        if (a == null) return;
        if (io.isAir(a.pos)) {
            active = null;
            pending.add(new Event(a.pos, Kind.STOPPED, Outcome.BROKEN, a.startedAt, now));
            lastBrokenAt = now;
            return;
        }
        if (now - a.startedAt > a.budget) {
            io.send(Action.ABORT, a.pos, a.shot);
            active = null;
            aborted++;
            pending.add(new Event(a.pos, Kind.STOPPED, Outcome.ABORTED, a.startedAt, now));
            return;
        }
        a.progress += io.speedNow(a.pos) * clock;
        if (a.progress >= 1.0 && now - a.startedAt >= a.minTicks && inEffect != null && canAct) {
            int seq = io.send(Action.STOP, a.pos, inEffect);
            io.swing();
            active = null;
            stopped++;
            nextStartAt = now + stopDelay + 1;
            flights.put(a.pos, new Flight(a.pos, Kind.STOPPED, now, seq, -1, now));
            return;
        }
        io.swing();
    }

    /**
     * Movements (client ticks) from this one, counted, until the active block's STOP goes out: its progress reaches 1
     * with the item held now ({@link #tickActive} adds a tick's in this one) and its {@code minTicks} have passed. The
     * server checks that STOP from where the bot is after them. 0 without an active block, or when it may stop now.
     */
    int ticksToStop(long now) {
        Active<S> a = active;
        if (a == null) return 0;
        double sp = io.speedNow(a.pos) * clock;
        if (a.lambda) {
            // Lambda: STOP once speed × (ticks since START) ≥ 0.7 and the minimum ticks are over (tickLambda)
            long k = sp > 1e-9 ? (long) Math.ceil(LAMBDA_THRESHOLD / sp - 1e-9) : 1000;
            long stopAt = Math.max(now, a.startedAt + Math.max(a.minTicks, k));
            return (int) Math.max(0, Math.min(1000, stopAt - now));
        }
        long k = sp > 1e-9 ? (long) Math.ceil((1.0 - a.progress) / sp - 1e-9) : 1000;
        long stopAt = Math.max(now + Math.max(1, k) - 1, a.startedAt + a.minTicks);
        return (int) Math.max(0, Math.min(1000, stopAt - now));
    }

    // ------------------------------------------------------------------------------------- Lambda's OldGrim scheme

    /** Lambda: send the fake STARTs ({@code fake}) and their burst ({@code burst}, only with {@code fake}). */
    void lambdaOptions(boolean fake, boolean burst) {
        lambdaFake = fake;
        lambdaBurst = fake && burst;
    }

    /** The last STOP was of a block whose burst went out: a non-instant START may skip the delay after it. */
    boolean lambdaBypass() {
        return lambdaBypass;
    }

    /**
     * Lambda: a non-instant block may be started now ({@link #startLambda}): none is being mined and the delay after the
     * last STOP is over, or that STOP's block had its burst ({@code processNewBreak}, BreakManager.kt:466-467: the
     * delay holds unless bypassing). Several may go out in one tick (the double break). Instant STARTs keep to
     * {@link #canStartInstant}: no bypass for them (BreakManager.kt:839).
     */
    boolean canStartLambda(long now) {
        return active == null && (now >= nextStartAt || lambdaBypass);
    }

    /** One swing per tick: Lambda swings on every START and every mining tick (SwingMode.Constant). */
    private void swingOnce(long now) {
        if (swungAt == now) return;
        io.swing();
        swungAt = now;
    }

    /** {@code n} fake STARTs {@link #OLD_GRIM_Y_OFFSET} above {@code pos}, never below {@link #WORLD_TOP}. */
    private void fake(Pos pos, S shot, int n) {
        if (!lambdaFake) return;
        Pos f = fakeOf(pos);
        if (f.y() < WORLD_TOP) return;
        for (int i = 0; i < n; i++) {
            io.send(Action.START, f, shot);
            fakeStarts++;
        }
    }

    /** Where {@code pos}'s fake STARTs go: {@link #OLD_GRIM_Y_OFFSET} above it ({@code BreakInfo.breakPacket}). */
    static Pos fakeOf(Pos pos) {
        return new Pos(pos.x(), pos.y() + OLD_GRIM_Y_OFFSET, pos.z());
    }

    /** {@code p} is out of the world (a fake START's position). */
    static boolean outOfWorld(Pos p) {
        return p.y() >= WORLD_TOP;
    }

    /**
     * Lambda ({@code startBreaking}, BreakManager.kt:833-878): START {@code pos} ({@link #canStartLambda} must hold, the
     * caller has checked the rate cap), its fake START and a swing. At 0.7..1 progress per tick with the item held (and
     * {@code minTicks} 0, not {@code mined}) the STOP follows at once: the server breaks it at {@code f × 1 ≥ 0.7}; then
     * {@link #OLD_GRIM_BREAK_DELAY} ticks without a START and no bypass (a fresh block has no burst). Slower, or
     * {@code mined}: it becomes the active block, mined by {@link #tickLambda} (STOP no earlier than {@code minTicks}
     * and {@link #OLD_GRIM_MIN_TICKS} − 1 ticks after the START). A block at 1 or more (and not {@code mined}) is sent as
     * an instant START (no fake, BreakManager.kt:841). True if anything was sent.
     */
    boolean startLambda(long now, Pos pos, S shot, int minTicks, boolean mined) {
        double speed = io.speedNow(pos);
        // a block instant with the item held waits out the delay even mined (a retry): BreakManager.kt:837-839
        if (speed >= 1.0 && now < nextStartAt) return false;
        if (speed >= 1.0 && !mined) {
            if (!canStartInstant(now, Integer.MAX_VALUE)) return false;
            return startInstant(now, pos, shot, swungAt != now);
        }
        int seq = io.send(Action.START, pos, shot);
        countStart(now);
        fake(pos, shot, 1);
        if (!mined && minTicks <= 0 && speed >= LAMBDA_THRESHOLD) {
            // requiresSecondStop (BreakManager.kt:842, 868-875): broken by the STOP in the START's tick
            int stopSeq = io.send(Action.STOP, pos, shot);
            swingOnce(now);
            stopped++;
            lambdaQuick++;
            nextStartAt = now + OLD_GRIM_BREAK_DELAY;
            lambdaBypass = false;
            Flight f = new Flight(pos, Kind.STOPPED, now, Math.max(seq, stopSeq), -1, now);
            f.lambda = true;
            flights.put(pos, f);
            return true;
        }
        swingOnce(now);
        long budget = clientTicks(Math.min(1200, (long) ToolChoice.ticks(speed) * 2 + 60));
        Active<S> a = new Active<>(pos, shot, now, Math.max(budget, OLD_GRIM_MIN_TICKS + 20));
        a.lambda = true;
        a.mined = mined;
        a.minTicks = Math.max(OLD_GRIM_MIN_TICKS - 1, minTicks);
        active = a;
        lambdaMined++;
        return true;
    }

    /**
     * Lambda's double break ({@code initNewBreak}, BreakManager.kt:501-529): the active block, still below
     * {@link #LAMBDA_THRESHOLD} at the server ({@code breakingTicks × delta}, 518), gets its STOP now, which leaves it in
     * the server's one delayed-destroy slot (vanilla breaks it once {@code f × (ticks + 1) ≥ 1} with the item held
     * then); {@link #OLD_GRIM_BREAK_DELAY} ticks without an instant START follow (525), and {@code partner} is started at
     * once as the new active block ({@link #startLambda}). The caller checks that the slot is free
     * ({@link #delayedSlotFree}), the partner takes the same tool and the rate cap has room. True if the STOP went out.
     */
    boolean lambdaSecondary(long now, Pos partner, S partnerShot) {
        Active<S> a = active;
        if (a == null || !a.lambda || a.mined) return false;
        double sp = io.speedNow(a.pos);
        if (!(sp > 0) || sp * ((now - a.startedAt) * clock + 1) >= LAMBDA_THRESHOLD) return false;
        int stopSeq = io.send(Action.STOP, a.pos, a.shot);
        int ticks = ToolChoice.ticks(sp);
        long expected = a.startedAt + clientTicks(Math.max(0, ticks - 1));
        long deadline = expected + clientTicks(Math.max(DELAYED_GRACE_TICKS, ticks));
        flights.put(a.pos, new Flight(a.pos, Kind.DELAYED, a.startedAt, stopSeq, deadline, expected));
        delayedSent++;
        lambdaSecondaries++;
        lambdaMined--; // counted as the delayed one, not as mined
        nextStartAt = now + OLD_GRIM_BREAK_DELAY;
        active = null;
        startLambda(now, partner, partnerShot, 0, false);
        return true;
    }

    /**
     * One tick of the active Lambda block ({@code updateBreakProgress}, BreakManager.kt:696-781): the burst of
     * {@link #OLD_GRIM_BURST} fake STARTs once it has been mined past 6 ticks ({@code canBurst}: the rate cap has room,
     * on the ground), then the STOP once {@code speed × (ticks since START)} ≥ {@link #LAMBDA_THRESHOLD} and the
     * minimum ticks are over ({@code canStop}: input ours, on the ground, in reach), else a swing. After the STOP
     * {@link #OLD_GRIM_BREAK_DELAY} ticks without a START, bypassed for a non-instant START when the burst went out.
     * Air shown ends it as broken; past its budget it is ABORTed. True if the STOP went out this tick.
     */
    boolean tickLambda(long now, boolean canStop, boolean canBurst) {
        Active<S> a = active;
        if (a == null) return false;
        if (io.isAir(a.pos)) {
            active = null;
            pending.add(new Event(a.pos, Kind.STOPPED, Outcome.BROKEN, a.startedAt, now));
            lastBrokenAt = now;
            return false;
        }
        if (now - a.startedAt > a.budget) {
            io.send(Action.ABORT, a.pos, a.shot);
            active = null;
            aborted++;
            pending.add(new Event(a.pos, Kind.STOPPED, Outcome.ABORTED, a.startedAt, now));
            return false;
        }
        long breakingTicks = now - a.startedAt + 1; // 1 in the START's tick (BreakManager.kt:862), +1 a tick (731)
        if (lambdaBurst && !a.bypassed && breakingTicks > OLD_GRIM_MIN_TICKS - 1 && canBurst) {
            fake(a.pos, a.shot, OLD_GRIM_BURST);
            a.bypassed = true;
            bursts++;
        }
        double progress = io.speedNow(a.pos) * clock * (breakingTicks - LAMBDA_FUDGE);
        if (progress >= LAMBDA_THRESHOLD && now - a.startedAt >= a.minTicks && canStop) {
            int seq = io.send(Action.STOP, a.pos, a.shot);
            swingOnce(now);
            active = null;
            stopped++;
            nextStartAt = now + OLD_GRIM_BREAK_DELAY;
            lambdaBypass = a.bypassed;
            Flight f = new Flight(a.pos, Kind.STOPPED, now, seq, -1, now);
            f.lambda = true;
            flights.put(a.pos, f);
            return true;
        }
        swingOnce(now);
        return false;
    }

    /** ABORT the block being mined (the task ends or parks mid-block; vanilla sends it when the button is released). */
    void abortActive() {
        Active<S> a = active;
        if (a == null) return;
        io.send(Action.ABORT, a.pos, a.shot);
        active = null;
    }

    /**
     * What the server answered since the last call (and what the active block did): air shown, delayed blocks past their
     * deadline, refusals, unknowns. Call once per tick before acting.
     */
    List<Event> poll(long now) {
        List<Event> out = new ArrayList<>(pending);
        pending.clear();
        int ack = io.latestAck();
        for (Iterator<Flight> it = flights.values().iterator(); it.hasNext(); ) {
            Flight f = it.next();
            if (io.isAir(f.pos)) {
                it.remove();
                if (f.kind == Kind.DELAYED) {
                    delayedBroken++;
                    delayedLateTicks += now - f.expectedAt;
                }
                out.add(new Event(f.pos, f.kind, Outcome.BROKEN, f.sentAt, now));
                lastBrokenAt = now;
                continue;
            }
            if (f.kind == Kind.DELAYED) {
                if (now > f.deadline) {
                    it.remove();
                    delayedTimedOut++;
                    out.add(new Event(f.pos, f.kind, Outcome.TIMED_OUT, f.sentAt, now));
                }
                continue;
            }
            if (f.ackedAt < 0 && f.seq >= 0 && ack >= f.seq) f.ackedAt = now;
            int grace = f.kind == Kind.INSTANT || f.lambda ? instantAckTicks : REJECT_GRACE_TICKS;
            if (f.ackedAt >= 0 && now - f.ackedAt >= grace) {
                it.remove();
                refused++;
                out.add(new Event(f.pos, f.kind, Outcome.REFUSED, f.sentAt, now));
            } else if (now - f.sentAt > clientTicks(CONFIRM_MAX_TICKS)) {
                it.remove();
                unknown++;
                out.add(new Event(f.pos, f.kind, Outcome.UNKNOWN, f.sentAt, now));
            }
        }
        return out;
    }
}
