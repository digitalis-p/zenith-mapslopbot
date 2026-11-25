package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * What became of each placement click. Pure; client tick thread only; one
 * per lane run, keyed by the lane's cells. It feeds the outcomes and latencies to the long-lived {@link AdaptivePacer}.
 *
 * <p>The server answers every use-item-on it handles (vanilla {@code handleUseItemOn}) with a block update for the
 * clicked block and one for the cell next to the clicked face, whether or not anything changed, then a
 * block-changed-ack carrying the highest block prediction sequence it handled in that server tick. 2b2t does so within
 * one round trip (live replays: ack round trip p50 ~165 ms, p99 0.4-1.1 s). A click's sequence is ZenithProxy's
 * prediction counter after the click ({@code clickSequence}), so:
 * <ul>
 *   <li><b>confirmed</b>: the target shows in the world within the confirm window;</li>
 *   <li><b>late</b>: it shows after the window: a success, and its latency widens the window;</li>
 *   <li><b>rejected</b>: the ack covering the click arrived ({@link #REJECT_GRACE_TICKS} ago), the world still does
 *       not show the block, and the click was {@linkplain #answered answered}: the server (or the anti-cheat in front
 *       of it) handled it and kept the old state. The cell may be clicked again after {@link #retryDelay};</li>
 *   <li><b>dropped</b>: the same, but nothing answered the click itself: only the ack of a later click covered it.
 *       Paper's click limiter ({@code spam-limiter}: 9 use-item packets per 300 ms window) returns before the ack and
 *       the block updates, so a dropped click is never acked itself; the next handled click's ack ("up to" its higher
 *       sequence) covers it. A dropped click was never handled and says nothing about the cell ({@code LaneRun} does
 *       not count it against the cell's sends). It is counted separately from refusals and adjusts the adaptive
 *       click window, never the pacer interval. The cell is eligible for retry immediately;</li>
 *   <li><b>unknown</b>: neither ack nor block within the window. Without a sequence the click is given up then
 *       (retried); with one, nothing is sent again for the cell until its ack or block arrives (TCP delivers in order:
 *       a re-click would queue behind the first and could land on top of it), at most until the window's cap.</li>
 * </ul>
 * A click is answered by a block update for its cell that arrived after it was sent (the cell is one of the two
 * positions vanilla echoes: next to the clicked top face of the support, or the clicked block itself for an airplace),
 * or by an ack carrying exactly its sequence: Grim (2.3.73, 1.19+ client) cancels a placement before the server sees
 * it and acks that one sequence itself, without block updates, and vanilla acks, then returns without block updates,
 * for a click out of interaction range or with a hit point off the block. Only a click sent as watched ({@link #sent})
 * can be dropped, and only once some click of this run was answered: with no answer seen (block updates not watched,
 * or a server that does not echo them) an acked click without its block is rejected.
 *
 * <p>It also counts clicks per hotbar slot that the server may not have counted yet ({@link #inFlight}): the slot's
 * count in the cache lags by them until their ack arrives (the slot update comes with the ack: 2b2t sends both in the
 * same server tick, the update at most a few ms after it), and for {@code settleTicks} after it. On 2b2t nearly every
 * rejected click was a click with the last items of a stack already used up by clicks in flight.
 *
 * <p><b>Stack ends</b> ({@link PlacePacing#classifyStackEnds}, the BURST pacer). A rejected click is a
 * {@link Outcome#STACK_END} when its stack was at its end: the cache showed its hotbar slot empty after its ack, or it
 * went out with at most {@link #STACK_END_MARGIN} items left in the stack by the cache less the clicks in flight. It
 * says nothing about the cell or the click rate: the pacer is told so, and the cell may be clicked again at once.
 * Without the classification such a click is {@link Outcome#REJECTED}, as before.
 */
public final class PlaceTracker<K> {
    /** Final classification of a click. */
    public enum Outcome { CONFIRMED, LATE, REJECTED, STACK_END, DROPPED, UNKNOWN }

    /**
     * Items left in the stack (the cache's count less the clicks in flight, before the click) at or below which a
     * rejected click counts as a stack end.
     */
    public static final int STACK_END_MARGIN = 2;
    /** {@link #sent} without a known stack: never a stack end by its count. */
    public static final int UNKNOWN_STACK = Integer.MAX_VALUE;

    /**
     * Ticks after the ack before a click without its block counts as rejected: the block updates precede the ack on
     * the wire, but the ack may be seen first by a tick (different threads).
     */
    public static final int REJECT_GRACE_TICKS = 2;
    /**
     * Least ticks before a rejected cell is clicked again. A rejection that is not a used-up stack (those wait for
     * their acks instead) is the server refusing the click: a rate limit or an anti-cheat cancel. An immediate retry
     * would meet the same limit; this lets the slowed-down pacer act first.
     */
    public static final int MIN_RETRY_TICKS = 20;

    private static final class Click {
        final long sentAt;
        int seq;
        final int slot;
        /** Answers to this click are watched: without one it may be classified as dropped. */
        final boolean watched;
        /** Items left in its stack before it went out (cache less clicks in flight), {@link #UNKNOWN_STACK} if unknown. */
        final int stackLeft;
        long ackedAt = -1;
        /** Tick its answer (block update for its cell, or an ack of exactly its sequence) was seen, -1 none. */
        long answeredAt = -1;
        long resolvedAt = -1;
        boolean overdue;
        /** The cache showed its hotbar slot empty after its ack ({@link #observeAck(int, long, IntPredicate)}). */
        boolean sawEmpty;

        Click(long sentAt, int seq, int slot, boolean watched, int stackLeft) {
            this.sentAt = sentAt;
            this.seq = seq;
            this.slot = slot;
            this.watched = watched;
            this.stackLeft = stackLeft;
        }
    }

    private final AdaptivePacer pacer;
    private final int minWindow;
    /** Ticks after its ack a click stops counting against its hotbar stack ({@link #inFlight}). */
    private final int settleTicks;
    /** Rejections at a used-up stack are told apart ({@link Outcome#STACK_END}). */
    private final boolean classifyStackEnds;
    private final Map<K, Click> pending = new HashMap<>();
    /** Clicks given up as unknown, by cell, until the cell is clicked again (a block that still shows is late). */
    private final Map<K, Click> expired = new HashMap<>();
    /** Clicks whose effect on the inventory the cache may not show yet (until acked + grace). */
    private final List<Click> unsettled = new ArrayList<>();
    private int ackedSeq = -1;
    private boolean ackSeen;
    /** Some click of this run was answered: the server's answers do arrive, so a missing one means a drop. */
    private boolean answerSeen;
    private int clicks;
    private int confirmed;
    private int late;
    private int rejected;
    private int stackEnded;
    private int dropped;
    private int unknown;
    private final List<Integer> confirmLatency = new ArrayList<>();
    private final List<Integer> ackLatency = new ArrayList<>();

    /**
     * {@code minWindow}: build.placeConfirmTicks, the minimum and initial confirm window. The previous rules: a click
     * counts against its stack until {@link #REJECT_GRACE_TICKS} after its ack, no stack ends told apart.
     */
    public PlaceTracker(AdaptivePacer pacer, int minWindow) {
        this(pacer, minWindow, REJECT_GRACE_TICKS, false);
    }

    /**
     * {@code settleTicks}: ticks after its ack a click stops counting against its hotbar stack
     * ({@code build.placeStackSettleTicks}); {@code classifyStackEnds}: rejections at a used-up stack are
     * {@link Outcome#STACK_END} (class javadoc).
     */
    public PlaceTracker(AdaptivePacer pacer, int minWindow, int settleTicks, boolean classifyStackEnds) {
        this.pacer = pacer;
        this.minWindow = Math.max(1, minWindow);
        this.settleTicks = Math.max(0, settleTicks);
        this.classifyStackEnds = classifyStackEnds;
    }

    /** Current confirm window in ticks ({@link AdaptivePacer#window}). */
    public int window() {
        return pacer.window(minWindow);
    }

    /**
     * Highest ack sequence received so far ({@code ackedSequence}, -1 none); call once per tick before {@link #check}.
     * A sequence lower than before means a new connection (the prediction counter restarted): clicks of the old one
     * can no longer be acked and are tracked by the window alone.
     */
    public void observeAck(int acked, long now) {
        observeAck(acked, now, null);
    }

    /**
     * {@link #observeAck(int, long)}; {@code slotEmpty} (hotbar index → the cache shows it empty now, null: not
     * watched) marks every acked click still pending whose slot shows empty: a stack end if it is rejected.
     */
    public void observeAck(int acked, long now, @Nullable IntPredicate slotEmpty) {
        observe(acked, now);
        if (slotEmpty == null || !classifyStackEnds) return;
        for (Click c : pending.values()) {
            if (c.ackedAt >= 0 && !c.sawEmpty && c.slot >= 0 && slotEmpty.test(c.slot)) c.sawEmpty = true;
        }
    }

    private void observe(int acked, long now) {
        if (acked < ackedSeq) {
            for (Click c : unsettled) if (c.ackedAt < 0) c.seq = -1;
            for (Click c : pending.values()) if (c.ackedAt < 0) c.seq = -1;
        }
        ackSeen = true;
        ackedSeq = acked;
        for (Click c : unsettled) {
            if (c.seq >= 0 && c.ackedAt < 0 && c.seq <= acked) {
                c.ackedAt = now;
                int rtt = (int) (now - c.sentAt);
                ackLatency.add(rtt);
                pacer.observeLatency(rtt);
            }
        }
        Iterator<Click> it = unsettled.iterator();
        while (it.hasNext()) {
            Click c = it.next();
            boolean settled = c.ackedAt >= 0 ? now - c.ackedAt >= settleTicks
                : c.seq < 0 ? c.resolvedAt >= 0 && now - c.resolvedAt >= 1
                : now - c.sentAt > pacer.maxWindow();
            if (settled) it.remove();
        }
    }

    /** {@link #sent(Object, long, int, int, boolean)}, answers not watched: acked without the block = rejected. */
    public void sent(K key, long now, int seq, int slot) {
        sent(key, now, seq, slot, false);
    }

    /**
     * A click for {@code key} was sent at {@code now} from hotbar slot {@code slot} with block prediction sequence
     * {@code seq} (-1 unknown). A sequence the last observed ack already covers belongs to a restarted counter (a stale
     * ack): the click is tracked by the window alone. {@code watched}: its answer will be reported ({@link #answered}),
     * so an ack covering it without an answer means the server dropped it.
     */
    public void sent(K key, long now, int seq, int slot, boolean watched) {
        sent(key, now, seq, slot, watched, UNKNOWN_STACK);
    }

    /**
     * {@link #sent(Object, long, int, int, boolean)}; {@code stackLeft}: items left in the stack it went out with, the
     * cache's count less the clicks in flight before it ({@link #UNKNOWN_STACK} if not known).
     */
    public void sent(K key, long now, int seq, int slot, boolean watched, int stackLeft) {
        if (seq >= 0 && ackSeen && seq <= ackedSeq) seq = -1;
        Click c = new Click(now, seq, slot, watched, stackLeft);
        Click old = pending.put(key, c);
        if (old != null) old.resolvedAt = now;
        expired.remove(key);
        unsettled.add(c);
        clicks++;
    }

    /**
     * The server answered {@code key}'s pending click itself: a block update for its cell arrived after it was sent, or
     * an ack carrying exactly its sequence. It was handled (not dropped). Before or after its covering ack.
     */
    public void answered(K key, long now) {
        Click c = pending.get(key);
        if (c == null || c.answeredAt >= 0) return;
        c.answeredAt = now;
        answerSeen = true;
    }

    /** True while a click for {@code key} waits for its answer. */
    public boolean pending(K key) {
        return pending.containsKey(key);
    }

    /**
     * The world shows {@code key}'s block: confirmed (or late) if it was clicked. Returns the outcome, or null if no
     * click of ours was waiting (or had been given up) for it.
     */
    public @Nullable Outcome placed(K key, long now) {
        Click c = pending.remove(key);
        boolean wasExpired = false;
        if (c == null) {
            c = expired.remove(key);
            if (c == null) return null;
            wasExpired = true;
        }
        c.resolvedAt = now;
        int age = (int) (now - c.sentAt);
        confirmLatency.add(age);
        pacer.observeLatency(age);
        if (c.overdue || wasExpired || age > window()) {
            if (c.overdue || wasExpired) unknown--;
            late++;
            pacer.onLate(c.sentAt, now);
            return Outcome.LATE;
        }
        confirmed++;
        pacer.onConfirm(c.sentAt, now);
        return Outcome.CONFIRMED;
    }

    /**
     * The world does not show {@code key}'s block (yet). Returns null while its click still waits, else why it failed:
     * {@link Outcome#REJECTED} (acked and answered without the block), {@link Outcome#STACK_END} (the same, with its
     * stack at its end: class javadoc), {@link Outcome#DROPPED} (acked by a later click only, never answered: the
     * server did not handle it) or {@link Outcome#UNKNOWN} (given up: no answer). The cell is free for a retry then. A
     * click past the window without an ack is counted unknown (the pacer slows by a tick) but keeps waiting while an ack
     * can still come, up to the window's cap.
     */
    public @Nullable Outcome check(K key, long now) {
        Click c = pending.get(key);
        if (c == null) return null;
        if (c.seq >= 0 && c.ackedAt >= 0 && now - c.ackedAt >= REJECT_GRACE_TICKS) {
            pending.remove(key);
            c.resolvedAt = now;
            boolean drop = c.watched && answerSeen && c.answeredAt < 0;
            boolean stackEnd = !drop && classifyStackEnds && (c.sawEmpty || c.stackLeft <= STACK_END_MARGIN);
            AdaptivePacer.Refusal kind = drop ? AdaptivePacer.Refusal.DROPPED
                : stackEnd ? AdaptivePacer.Refusal.STACK_END : AdaptivePacer.Refusal.REFUSED;
            if (c.overdue) unknown--; // acked after all: reclassified (the pacer already slowed down)
            else pacer.onRefusal(c.sentAt, now, kind); // drops are counted separately; stack ends do not slow BURST
            if (drop) {
                dropped++;
                return Outcome.DROPPED;
            }
            rejected++;
            if (stackEnd) {
                stackEnded++;
                return Outcome.STACK_END;
            }
            return Outcome.REJECTED;
        }
        long age = now - c.sentAt;
        if (!c.overdue && age >= window()) {
            c.overdue = true;
            unknown++;
            pacer.onUnknown(c.sentAt, now);
        }
        if (c.overdue && (c.seq < 0 || age >= Math.max(window(), pacer.maxWindow()))) {
            pending.remove(key);
            c.resolvedAt = now;
            expired.put(key, c);
            return Outcome.UNKNOWN;
        }
        return null;
    }

    /** The cell is no longer tracked (skipped, or the lane ends). */
    public void forget(K key) {
        Click c = pending.remove(key);
        if (c != null) c.resolvedAt = Math.max(c.resolvedAt, c.sentAt);
        expired.remove(key);
    }

    /** Ticks a rejected or dropped cell waits before its next click: {@link #MIN_RETRY_TICKS}, or two round trips. */
    public int retryDelay() {
        return Math.max(MIN_RETRY_TICKS, 2 * pacer.rttTicks());
    }

    /** Clicks from hotbar slot {@code slot} whose item the cached stack may still count (not acked yet). */
    public int inFlight(int slot) {
        int n = 0;
        for (Click c : unsettled) if (c.slot == slot) n++;
        return n;
    }

    public int clicks() {
        return clicks;
    }

    public int confirmed() {
        return confirmed;
    }

    public int late() {
        return late;
    }

    /** Rejected clicks, stack ends included ({@link #stackEnds}). */
    public int rejected() {
        return rejected;
    }

    /** Rejected clicks at a used-up stack ({@link Outcome#STACK_END}). */
    public int stackEnds() {
        return stackEnded;
    }

    /** Clicks the server never handled (its click limiter): acked only by a later click's ack, no answer. */
    public int dropped() {
        return dropped;
    }

    public int unknown() {
        return unknown;
    }

    /** Click-to-block latencies in ticks of confirmed and late clicks. */
    public List<Integer> confirmLatencies() {
        return List.copyOf(confirmLatency);
    }

    /** Click-to-ack latencies in ticks. */
    public List<Integer> ackLatencies() {
        return List.copyOf(ackLatency);
    }

    /** {@code q}-quantile (0..1) of {@code v}, nearest rank; -1 if empty. */
    static int quantile(List<Integer> v, double q) {
        if (v.isEmpty()) return -1;
        int[] s = v.stream().mapToInt(Integer::intValue).toArray();
        Arrays.sort(s);
        int i = (int) Math.ceil(q * s.length) - 1;
        return s[Math.max(0, Math.min(s.length - 1, i))];
    }

    /**
     * One line for the lane log: "clicks 648: 640 confirmed, 0 late, 8 rejected, 0 dropped, 0 unknown; confirm p50 3
     * p95 6 t; ack p50 4 p95 7 t; window 40 t", and with stack ends told apart "; 6 of the rejected at stack ends".
     */
    public String summary() {
        return "clicks " + clicks + ": " + confirmed + " confirmed, " + late + " late, " + rejected + " rejected, "
            + dropped + " dropped, " + unknown + " unknown; confirm p50 " + q(confirmLatency, 0.5) + " p95 "
            + q(confirmLatency, 0.95) + " t; ack p50 " + q(ackLatency, 0.5) + " p95 " + q(ackLatency, 0.95)
            + " t; window " + window() + " t" + (classifyStackEnds ? "; " + stackEnded + " of the rejected at stack ends"
            : "");
    }

    private static String q(List<Integer> v, double q) {
        int x = quantile(v, q);
        return x < 0 ? "-" : Integer.toString(x);
    }
}
