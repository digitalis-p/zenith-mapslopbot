package io.github.digitalisp.mapart.bot.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Click pacing against a simulated 2b2t connection, without the walk: the clicks of one lane, the server's answers and
 * the inventory. Compares the pacer and confirmation logic before this change ({@link Policy#LEGACY}: confirm by the
 * world within 40 ticks, else a miss that doubles the interval, -1 per 32 confirmations, no stack check) with
 * {@link PlaceTracker} + {@link AdaptivePacer} and the used-up-stack check ({@link Policy#NEW}, as in LaneRun), and
 * with the burst pacer ({@link Policy#BURST}: {@link PlacePacing#DEFAULT}, stack ends told apart and retried at once,
 * a click counts against its stack until one tick after its ack).
 *
 * <p><b>Connection.</b> Times in ms, the client ticks every 50 ms. Each direction delivers in order (TCP): a packet
 * arrives no earlier than the one before it. One-way delay: uniform in [min, max], plus the extra delay of a lag
 * spike in progress (spikes start at random, last a random time, add a random delay).
 *
 * <p><b>Server</b> (vanilla handleUseItemOn, as the live replays show it): it handles a click when it arrives, after
 * up to one tick; it answers with the block (placed or the old state) and the slot's new count, then the ack of the
 * highest sequence handled. It refuses a click with an empty hand (the stack used up by earlier clicks), at
 * {@code rejectRate}, and above {@code rateCap} placements in the last second (0 = no cap). At {@code dropRate} it
 * drops a click without any answer (a later click's ack covers its sequence).
 *
 * <p><b>Client.</b> Cells in lane order, each of one of {@code materials} items; the inventory holds exactly what the
 * lane needs, one stack (at most 64) per item in the hotbar, the rest in the main inventory. Reachable: the first
 * {@code reach} unfinished cells (a walk cap limits how far that front may advance per tick). At most one click per
 * tick, when the pacer is ready and no inventory move is in flight; the first reachable cell whose stack is usable
 * (cached count above 0; NEW: above the clicks still in flight). A stack the cache shows empty is refilled from the main
 * inventory by a move (the cache shows the new stack at once, as ZenithProxy's click prediction does; no click for
 * {@link #MOVE_TICKS}). A failed cell is retried once, then skipped.
 */
final class PacingSim {
    enum Policy { LEGACY, NEW, BURST }

    static final int TICK_MS = 50;
    static final int MOVE_TICKS = 2;
    static final int MAX_SENDS = 2;
    static final int STACK = 64;

    /** Connection model (ms). */
    record Net(String name, int oneWayMin, int oneWayMax, double spikesPerMinute, int spikeExtraMin,
               int spikeExtraMax, int spikeLenMin, int spikeLenMax) {
        /** Calibrated to the live replays: ack round trip p50 ~165 ms, p95 ~330 ms, p99 0.4-1.1 s. */
        static Net live2b2t() {
            return new Net("2b2t-like 50-220 ms, spikes", 25, 110, 4, 150, 700, 200, 800);
        }

        /** Rare long stalls: 2.5-4 s without answers (longer than the 40-tick window). */
        static Net stalls() {
            return new Net("2b2t-like + 2.5-4 s stalls", 25, 110, 4, 2500, 4000, 300, 900);
        }

        static Net flat(int oneWay) {
            return new Net("flat " + 2 * oneWay + " ms", oneWay, oneWay, 0, 0, 0, 0, 0);
        }
    }

    /** Server behaviour. */
    record Server(String name, double rejectRate, double dropRate, int rateCap) {
        static Server vanilla() {
            return new Server("stack ends only", 0, 0, 0);
        }
    }

    /** Lane: cells, items, reachable window, walk cap (cells per tick the reachable front advances; 0 = none). */
    record Lane(int cells, int materials, int reach, double walkCap) {
        static Lane live() {
            return new Lane(640, 6, 15, 0);
        }
    }

    record Result(Policy policy, long ticks, int placed, int skipped, int clicks, int serverRejected,
                  int serverDropped, int confirmed, int late, int rejected, int unknown, int finalInterval,
                  int maxInterval, int window, int[] ackRttMs) {
        double perSecond() {
            return placed * 20.0 / ticks;
        }

        int ackQuantile(double q) {
            if (ackRttMs.length == 0) return -1;
            int[] s = ackRttMs.clone();
            Arrays.sort(s);
            return s[Math.max(0, Math.min(s.length - 1, (int) Math.ceil(q * s.length) - 1))];
        }

        String row() {
            return String.format("%-6s %6.2f/s %5d t  placed %3d skipped %2d  clicks %4d  server-rejected %3d"
                    + " dropped %2d  | confirmed %3d late %2d rejected %3d unknown %2d | interval end %d max %d"
                    + " window %d", policy, perSecond(), ticks, placed, skipped, clicks, serverRejected,
                serverDropped, confirmed, late, rejected, unknown, finalInterval, maxInterval, window);
        }
    }

    /** The pacer before this change: doubles on a miss, -1 after 32 confirmations in a row. */
    static final class LegacyPacer {
        int interval;
        final int min;
        final int max;
        int streak;

        LegacyPacer(int start, int min, int max) {
            this.min = Math.max(1, min);
            this.max = Math.max(this.min, max);
            this.interval = Math.max(this.min, Math.min(this.max, start));
        }

        void onMiss() {
            streak = 0;
            interval = Math.min(max, interval * 2);
        }

        void onConfirm() {
            if (++streak >= 32) {
                streak = 0;
                interval = Math.max(min, interval - 1);
            }
        }
    }

    private enum St { TODO, PENDING, DONE, SKIPPED }

    private record Ev(long at, int order, java.util.function.LongConsumer r) { }

    private final Policy policy;
    private final Net net;
    private final Server srv;
    private final Lane lane;
    private final Random rnd;
    private final PriorityQueue<Ev> queue = new PriorityQueue<>((a, b) -> a.at != b.at ? Long.compare(a.at, b.at)
        : Integer.compare(a.order, b.order));
    private int order;
    private final List<long[]> spikes = new ArrayList<>();
    private long upLast;
    private long downLast;

    // lane
    private final int[] material;
    private final St[] st;
    private final int[] sends;
    private final long[] sentAt;
    private final long[] retryAt;
    // client cache
    private final boolean[] seen;
    private final int[] cacheHot;
    private final int[] cacheMain;
    private int ackedSeq = -1;
    // server
    private final boolean[] world;
    private final int[] srvHot;
    private final int[] srvMain;
    private int srvAck = -1;
    private final List<Long> srvPlacedAt = new ArrayList<>();
    // client control
    private final AdaptivePacer pacer;
    private final LegacyPacer legacy;
    private final PlaceTracker<Integer> tracker;
    private int seq;
    private long lastClick = Long.MIN_VALUE / 4;
    private long moveBusyUntil = -1;
    private double front;
    // stats
    private int clicks;
    private int serverRejected;
    private int serverDropped;
    private int maxInterval;
    private int legacyConfirmed;
    private int legacyMisses;
    /** Inventory moves per item sent by the client / applied by the server (a stale slot update is ignored). */
    private final int[] movesSent;
    private final int[] movesApplied;
    /** BURST: stack-end rejections per cell not counted as sends (LaneRun.MAX_DROPS). */
    private final int[] stackEndRetries;
    private final List<Integer> ackRtt = new ArrayList<>();
    private final long[] clickMs = new long[1 << 16];

    private PacingSim(Policy policy, Net net, Server srv, Lane lane, int start, int min, int max, long seed) {
        this.policy = policy;
        this.net = net;
        this.srv = srv;
        this.lane = lane;
        this.rnd = new Random(seed);
        Random layout = new Random(seed * 31 + 7); // same lane and connection for both policies
        material = new int[lane.cells()];
        int m = 0;
        for (int i = 0; i < lane.cells(); i++) {
            if (layout.nextInt(4) == 0) m = layout.nextInt(lane.materials()); // runs of one colour
            material[i] = m;
        }
        st = new St[lane.cells()];
        Arrays.fill(st, St.TODO);
        sends = new int[lane.cells()];
        sentAt = new long[lane.cells()];
        retryAt = new long[lane.cells()];
        seen = new boolean[lane.cells()];
        world = new boolean[lane.cells()];
        int[] need = new int[lane.materials()];
        for (int x : material) need[x]++;
        cacheHot = new int[lane.materials()];
        cacheMain = new int[lane.materials()];
        srvHot = new int[lane.materials()];
        srvMain = new int[lane.materials()];
        movesSent = new int[lane.materials()];
        movesApplied = new int[lane.materials()];
        for (int i = 0; i < need.length; i++) {
            // restock: a partial stack in the hotbar first (what is left of the last lane), full stacks behind
            int first = need[i] == 0 ? 0 : 1 + layout.nextInt(Math.min(STACK, need[i]));
            cacheHot[i] = srvHot[i] = first;
            cacheMain[i] = srvMain[i] = need[i] - first;
        }
        Random sp = new Random(seed * 17 + 3);
        long horizon = 30L * 60 * 1000;
        double perMs = net.spikesPerMinute() / 60_000.0;
        for (long t = 0; t < horizon && perMs > 0; ) {
            t += (long) (-Math.log(1 - sp.nextDouble()) / perMs);
            long len = net.spikeLenMin() + sp.nextInt(Math.max(1, net.spikeLenMax() - net.spikeLenMin() + 1));
            long extra = net.spikeExtraMin() + sp.nextInt(Math.max(1, net.spikeExtraMax() - net.spikeExtraMin() + 1));
            spikes.add(new long[]{t, t + len, extra});
            t += len;
        }
        pacer = new AdaptivePacer(start, min, max, AdaptivePacer.DEFAULT_RECOVER, AdaptivePacer.DEFAULT_MAX_WINDOW);
        legacy = new LegacyPacer(start, min, max);
        if (policy == Policy.BURST) pacer.configure(PlacePacing.DEFAULT);
        tracker = policy == Policy.BURST
            ? new PlaceTracker<>(pacer, 40, PlacePacing.DEFAULT.settleTicks(), true)
            : new PlaceTracker<>(pacer, 40);
        stackEndRetries = new int[lane.cells()];
    }

    static Result run(Policy policy, Net net, Server srv, Lane lane, int start, int min, int max, long seed) {
        return new PacingSim(policy, net, srv, lane, start, min, max, seed).run();
    }

    private long delay(long at) {
        long d = net.oneWayMin() + rnd.nextInt(Math.max(1, net.oneWayMax() - net.oneWayMin() + 1));
        for (long[] s : spikes) {
            if (s[0] > at) break;
            if (at < s[1]) d += s[2] - (at - s[0]) * s[2] / Math.max(1, s[1] - s[0]) / 2; // drains as it ends
        }
        return d;
    }

    /** Client to server, in order; {@code r} gets the arrival time. */
    private void up(long at, java.util.function.LongConsumer r) {
        upLast = Math.max(upLast, at + delay(at));
        queue.add(new Ev(upLast, order++, r));
    }

    /** Server to client, in order; {@code r} gets the arrival time. */
    private void down(long at, java.util.function.LongConsumer r) {
        downLast = Math.max(downLast, at + delay(at));
        queue.add(new Ev(downLast, order++, r));
    }

    private Result run() {
        long tick = 0;
        for (; tick < 20 * 60 * 10; tick++) {
            long now = tick * TICK_MS;
            while (!queue.isEmpty() && queue.peek().at <= now) {
                Ev e = queue.poll();
                e.r.accept(e.at);
            }
            if (clientTick(tick, now)) break;
        }
        int[] rtt = ackRtt.stream().mapToInt(Integer::intValue).toArray();
        int placed = 0;
        int skipped = 0;
        for (St s : st) {
            if (s == St.DONE) placed++;
            if (s == St.SKIPPED) skipped++;
        }
        if (policy == Policy.LEGACY) {
            return new Result(policy, tick, placed, skipped, clicks, serverRejected, serverDropped, legacyConfirmed,
                0, legacyMisses, 0, legacy.interval, maxInterval, 40, rtt);
        }
        return new Result(policy, tick, placed, skipped, clicks, serverRejected, serverDropped,
            tracker.confirmed(), tracker.late(), tracker.rejected(), tracker.unknown(), pacer.interval(), maxInterval,
            tracker.window(), rtt);
    }

    /** One client tick; true when the lane is done. */
    private boolean clientTick(long tick, long now) {
        if (policy == Policy.NEW) tracker.observeAck(ackedSeq, tick);
        if (policy == Policy.BURST) tracker.observeAck(ackedSeq, tick, m -> cacheHot[m] <= 0);
        boolean left = false;
        for (int i = 0; i < st.length; i++) {
            if (st[i] == St.TODO && seen[i]) { // placed by a click given up on (LaneRun: the world matches)
                st[i] = St.DONE;
                if (policy != Policy.LEGACY) tracker.placed(i, tick);
            }
            if (st[i] == St.PENDING) {
                if (seen[i]) {
                    st[i] = St.DONE;
                    if (policy != Policy.LEGACY) {
                        tracker.placed(i, tick);
                    } else {
                        legacy.onConfirm();
                        legacyConfirmed++;
                    }
                } else if (policy != Policy.LEGACY) {
                    PlaceTracker.Outcome o = tracker.check(i, tick);
                    if (o == PlaceTracker.Outcome.STACK_END && stackEndRetries[i] < LaneRun.MAX_DROPS) {
                        stackEndRetries[i]++;
                        sends[i]--; // an empty hand says nothing about the cell: retried at once
                    }
                    if (o != null) st[i] = sends[i] < MAX_SENDS ? St.TODO : St.SKIPPED;
                    if (o == PlaceTracker.Outcome.REJECTED) retryAt[i] = tick + tracker.retryDelay();
                } else if (tick - sentAt[i] >= 40) {
                    legacy.onMiss();
                    legacyMisses++;
                    st[i] = sends[i] < MAX_SENDS ? St.TODO : St.SKIPPED;
                }
            }
            if (st[i] == St.TODO || st[i] == St.PENDING) left = true;
        }
        if (!left) return true;
        int interval = policy != Policy.LEGACY ? pacer.interval() : legacy.interval;
        maxInterval = Math.max(maxInterval, interval);
        // reachable front: the first unfinished cell + reach, advancing at most walkCap cells per tick
        int first = 0;
        while (first < st.length && (st[first] == St.DONE || st[first] == St.SKIPPED)) first++;
        double limit = first + lane.reach();
        front = lane.walkCap() > 0 ? Math.min(limit, front + lane.walkCap()) : limit;
        if (tick < moveBusyUntil || tick - lastClick < interval) return false;
        int refill = -1;
        for (int i = first; i < st.length && i < front; i++) {
            if (st[i] != St.TODO || tick < retryAt[i]) continue;
            int m = material[i];
            int usable = cacheHot[m] - (policy != Policy.LEGACY ? tracker.inFlight(m) : 0);
            if (usable > 0) {
                click(i, tick, now);
                return false;
            }
            if (cacheHot[m] <= 0 && cacheMain[m] > 0 && refill < 0) refill = m;
        }
        if (refill >= 0) move(refill, tick, now);
        return false;
    }

    private void click(int i, long tick, long now) {
        int m = material[i];
        int s = ++seq;
        clicks++;
        sends[i]++;
        sentAt[i] = tick;
        st[i] = St.PENDING;
        lastClick = tick;
        clickMs[s & 0xffff] = now;
        if (policy == Policy.NEW) tracker.sent(i, tick, s, m);
        if (policy == Policy.BURST) tracker.sent(i, tick, s, m, false, cacheHot[m] - tracker.inFlight(m));
        up(now, at -> serverClick(at, i, m, s));
    }

    private void move(int m, long tick, long now) {
        int n = Math.min(STACK, cacheMain[m]);
        cacheHot[m] += n;
        cacheMain[m] -= n;
        moveBusyUntil = tick + MOVE_TICKS;
        movesSent[m]++;
        up(now, at -> {
            int k = Math.min(STACK, srvMain[m]);
            srvHot[m] += k;
            srvMain[m] -= k;
            movesApplied[m]++;
        });
    }

    private void serverClick(long arrived, int cell, int m, int s) {
        long t = arrived + rnd.nextInt(TICK_MS); // handled at the next server tick
        if (srv.dropRate() > 0 && rnd.nextDouble() < srv.dropRate()) {
            serverDropped++;
            return;
        }
        srvAck = Math.max(srvAck, s);
        boolean ok = srvHot[m] > 0 && !world[cell];
        if (ok && srv.rejectRate() > 0 && rnd.nextDouble() < srv.rejectRate()) ok = false;
        if (ok && srv.rateCap() > 0) {
            srvPlacedAt.removeIf(x -> x <= t - 1000);
            if (srvPlacedAt.size() >= srv.rateCap()) ok = false;
        }
        if (ok) {
            srvHot[m]--;
            world[cell] = true;
            srvPlacedAt.add(t);
        } else {
            serverRejected++;
        }
        boolean placed = world[cell];
        int count = srvHot[m];
        int ack = srvAck;
        int moves = movesApplied[m];
        down(t, at -> {
            if (placed) seen[cell] = true;
            if (moves == movesSent[m]) cacheHot[m] = count; // else a move is on its way: the cache predicted it
            if (ack > ackedSeq) {
                for (int q = ackedSeq + 1; q <= ack; q++) ackRtt.add((int) (at - clickMs[q & 0xffff]));
                ackedSeq = ack;
            }
        });
    }
}
