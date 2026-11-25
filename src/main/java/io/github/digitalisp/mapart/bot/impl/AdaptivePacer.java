package io.github.digitalisp.mapart.bot.impl;

/**
 * Adaptive placement interval: ticks between two use-item-on packets. The lane placer is pipelined (it requests the
 * next target's rotation in the tick it clicks), so interval 1 is a click every tick (up to 20/s at 20 TPS); at 2 or
 * more the ticks in between hold the aim without clicking. Always within [min, max]. Pure; client tick thread only.
 * Long-lived (one per bot, {@link ZenithBotActions}): its latency memory carries over from lane to lane.
 *
 * <p>It reacts to the outcome of each click as {@link PlaceTracker} classifies it:
 * <ul>
 *   <li><b>rejected</b> (the server answered and kept the old state): the interval doubles;</li>
 *   <li><b>unknown</b> (nothing came back within the confirm window): the interval grows by one tick only, a lag
 *       spike being the likelier cause than the click rate;</li>
 *   <li><b>confirmed</b> or <b>late</b>: a success. After {@code recoverAfter} successes in a row the interval halves
 *       (rounded up), down to min.</li>
 * </ul>
 * One event moves the interval once: an outcome of a click sent before the last change of the interval says nothing
 * about the current interval, so it neither slows the pacer again (a burst of rejections of clicks that were already
 * in flight doubles the interval once, not once per click) nor counts towards the next recovery. That makes the
 * recovery wait at least one round trip; it also waits for at least the clicks one round trip holds at the current
 * interval ({@link #recoverStreak}).
 *
 * <p>The confirm window ({@link #window}) is the higher of the configured minimum and the highest latency of the
 * last {@link #SAMPLES} answers plus {@link #WINDOW_MARGIN}, capped at the configured maximum: a lag spike that
 * delivered a placement late widens it, and it narrows again as the spike leaves the sample.
 *
 * <p><b>Policies</b> ({@link PlacePacing}, {@code build.placePacer}). {@link Policy#LEGACY} is the list above: every
 * rejection doubles. {@link Policy#BURST} (the default) judges a failed click by its kind ({@link #onRefusal}):
 * <ul>
 *   <li>a <b>stack end</b> (the click went out with a stack the clicks in flight had used up: the server answers with
 *       an empty hand) is no rate signal: no change;</li>
 *   <li>a <b>refusal</b> (answered, kept the old state) is an event; refusals of clicks sent within one round trip of
 *       the first one of an event belong to it. An isolated event changes nothing; a second event within
 *       {@code burstWindow} ticks of the last one doubles the interval (a rate limit, a setback burst). On 2b2t
 *       (live replays 2026-10-11, 14,537 clicks at up to 20/s) refusals away from stack ends and setbacks came as 14
 *       isolated events of 1-3 clicks, about one per 1,000 clicks, whatever the click rate;</li>
 *   <li>a <b>drop</b> (the server's click limiter never handled it) is counted separately; only the click window reacts.</li>
 * </ul>
 * Under BURST the recovery takes {@code burstRecover} successes instead of {@code recoverAfter}, twice as many after
 * each slow-down that came right after a speed-up (a rate limit, {@link #burstSlowDown}).
 *
 * <p><b>Runs.</b> The pacer outlives the lanes, but each lane task counts its own ticks from 0. {@link #newRun} (at the
 * start of every lane) forgets the tick of the last change and of the last refusal event: a tick of the previous lane
 * says nothing in the new lane's clock. Before it existed (live 2026-10-11) the tick of the last change came from the
 * previous lane, so every outcome of the next lane looked older than that change until its own clock passed it: the
 * interval stood still across lanes (at 1, 2 or 4) for up to a whole lane.
 */
public final class AdaptivePacer {
    /** How failed clicks move the interval (class javadoc "Policies"). */
    public enum Policy { LEGACY, BURST }

    /** Why a click failed, as {@link PlaceTracker} tells it. */
    public enum Refusal {
        /** Answered with the old state while its stack was used up by the clicks in flight (an empty hand). */
        STACK_END,
        /** Answered with the old state otherwise: the server or its anti-cheat refused it. */
        REFUSED,
        /** Never handled: the server's click limiter dropped it (only a later click's ack covered it). */
        DROPPED
    }

    /** Default successes in a row (of clicks sent at the current interval) before the interval halves. */
    public static final int DEFAULT_RECOVER = 16;
    /** BURST: default ticks within which a second refusal event makes a burst. */
    public static final int DEFAULT_BURST_WINDOW = 100;
    /** BURST: default successes in a row before the interval halves. */
    public static final int DEFAULT_BURST_RECOVER = 8;
    /** BURST: the recovery takes at most this many times {@code burstRecover} successes after failed speed-ups. */
    public static final int MAX_BOOST = 8;
    /** BURST: a slow-down this soon after a speed-up means the speed-up did not hold. */
    static final int FAILED_SPEED_UP_TICKS = 25;
    private static final long NONE = Long.MIN_VALUE;
    /** Default cap of the confirm window, ticks (10 s). */
    public static final int DEFAULT_MAX_WINDOW = 200;
    /** Ticks added to the highest recent latency for the confirm window. */
    public static final int WINDOW_MARGIN = 10;
    /** Latency samples kept (confirmations and ack round trips, in ticks). */
    public static final int SAMPLES = 64;

    private int min;
    private int max;
    private int interval;
    private int recoverAfter;
    private final int maxWindow;
    private Policy policy = Policy.LEGACY;
    private int burstWindow = DEFAULT_BURST_WINDOW;
    private int streak;
    /** Tick of the last change (or no-change event) of the interval; outcomes of older clicks do not move it. */
    private long changedAt = NONE;
    /** BURST: tick the last refusal event was classified, and the send tick of its first click. */
    private long eventAt = NONE;
    private long eventSent = NONE;
    /** BURST: tick of the last halving; the recovery multiplier after speed-ups that did not hold. */
    private long speedUpAt = NONE;
    private int boost = 1;
    private long confirmations;
    private long late;
    private long rejections;
    private long drops;
    private long unknowns;
    private long stackEnds;
    private long isolated;
    private long bursts;
    private final int[] latency = new int[SAMPLES];
    private int latencyCount;
    private int latencyNext;

    /** Pacer with the default recovery and window cap. */
    public AdaptivePacer(int start, int min, int max) {
        this(start, min, max, DEFAULT_RECOVER, DEFAULT_MAX_WINDOW);
    }

    /**
     * {@code recoverAfter}: successes in a row before the interval halves (at least 1). {@code maxWindow}: cap of the
     * confirm window in ticks.
     */
    public AdaptivePacer(int start, int min, int max, int recoverAfter, int maxWindow) {
        this.interval = start;
        this.recoverAfter = Math.max(1, recoverAfter);
        this.maxWindow = Math.max(1, maxWindow);
        setBounds(min, max);
    }

    /** Re-applies bounds (config may change between lanes). Keeps the current interval when it still fits. */
    public void setBounds(int min, int max) {
        this.min = Math.max(1, min);
        this.max = Math.max(this.min, max);
        this.interval = clamp(interval);
    }

    /**
     * Applies the policy and its recovery ({@link PlacePacing}; config may change between lanes). Keeps the interval,
     * the streak and the counters.
     */
    public void configure(PlacePacing p) {
        policy = p.policy();
        recoverAfter = p.recover();
        burstWindow = p.burstWindow();
        latchFails = p.latchFails();
        latchWindow = p.latchWindow();
        while (shared.size() > latchWindow) if (shared.removeFirst()) sharedFailed--;
        clickWindow.configure(p);
    }

    /**
     * A new lane run starts its own tick count: forgets the ticks of the last change and of the last refusal event
     * (class javadoc "Runs"). Keeps the interval, the streak, the latencies and the counters.
     */
    public void newRun() {
        changedAt = NONE;
        eventAt = NONE;
        eventSent = NONE;
        speedUpAt = NONE;
    }

    public Policy policy() {
        return policy;
    }

    public int interval() {
        return interval;
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    public int streak() {
        return streak;
    }

    /** Clicks that slowed the pacer: rejected plus unknown (the old "misses"). */
    public long misses() {
        return rejections + unknowns;
    }

    public long confirmations() {
        return confirmations;
    }

    public long late() {
        return late;
    }

    public long rejections() {
        return rejections;
    }

    public long unknowns() {
        return unknowns;
    }

    /** BURST: rejections at a used-up stack (counted among {@link #rejections}; they never slow it down). */
    public long stackEnds() {
        return stackEnds;
    }

    /** BURST: refusal events that came alone (no slow-down). */
    public long isolated() {
        return isolated;
    }

    /** BURST: refusal events that came within the burst window of the one before (the interval doubled). */
    public long bursts() {
        return bursts;
    }

    /** True if at least {@link #interval()} ticks passed since {@code lastClickTick}. */
    public boolean ready(long nowTick, long lastClickTick) {
        return nowTick - lastClickTick >= interval;
    }

    // fast placement's latch: the pacer outlives the lanes, so it keeps it
    /** Outcomes judged in a row of clicks that shared a tick with an earlier click: the window of the latch. */
    public static final int SHARED_WINDOW = 20;
    /** Refusals (not at a stack end) among the last {@link #SHARED_WINDOW} that turn fast placement off. */
    public static final int SHARED_FAILS = 3;
    private final java.util.ArrayDeque<Boolean> shared = new java.util.ArrayDeque<>();
    private int sharedFailed;
    private int latchFails = SHARED_FAILS;
    private int latchWindow = SHARED_WINDOW;
    private final AdaptiveClickWindow clickWindow = new AdaptiveClickWindow();

    private final WallClickWindow wallWindow = new WallClickWindow();

    public WallClickWindow wallWindow() { return wallWindow; }
    public AdaptiveClickWindow clickWindow() { return clickWindow; }

    public long drops() { return drops; }
    private @org.jspecify.annotations.Nullable String fastOff;
    /**
     * Refusals this close to a server setback are the setback's, not Grim's MultiPlace: they never count, and a latch
     * that tripped this close to one is undone ({@link SetbackClock}).
     */
    static final long SETBACK_EXCUSE_NANOS = 2_000_000_000L;
    private SetbackClock setbacks = SetbackClock.SESSION;
    /** When and on which connection the latch tripped; {@link SetbackClock#NONE} when tripped without a time. */
    private long tripNanos = SetbackClock.NONE;
    private int tripConnection;
    private @org.jspecify.annotations.Nullable String restored;

    /** Tests: a clock of their own instead of the session's. */
    void useSetbackClock(SetbackClock clock) {
        setbacks = clock;
    }

    /**
     * The outcome of a click that went out as the second or later click of its tick (fast placement): {@code failed}
     * = refused with an exact ack and no block update, away from a stack end. Once
     * {@link #SHARED_FAILS} of the last {@link #SHARED_WINDOW} failed, fast placement is off for as long as this pacer
     * lives (the ZenithProxy run): Grim's MultiPlace cancels every later place that shares a tick once it has flagged
     * five. Paper drops never feed this latch. True when this outcome turned it off.
     */
    public boolean sharedOutcome(boolean failed) {
        return sharedOutcome(failed, SetbackClock.NONE);
    }

    /**
     * {@link #sharedOutcome(boolean)} at {@code nowNanos} ({@link System#nanoTime}; {@link SetbackClock#NONE}: no time):
     * a refusal within {@link #SETBACK_EXCUSE_NANOS} after a server setback is left out (the server refuses places while
     * its teleport is pending; live 2026-10-15 a setback burst tripped the latch).
     */
    public boolean sharedOutcome(boolean failed, long nowNanos) {
        if (fastOff != null) return false;
        long sb = setbacks.lastSetbackNanos();
        if (failed && nowNanos != SetbackClock.NONE && sb != SetbackClock.NONE && nowNanos - sb >= 0
            && nowNanos - sb < SETBACK_EXCUSE_NANOS) return false;
        shared.addLast(failed);
        if (failed) sharedFailed++;
        while (shared.size() > latchWindow) if (shared.pollFirst()) sharedFailed--;
        if (sharedFailed < latchFails) return false;
        fastOff = sharedFailed + " of the last " + shared.size() + " clicks that shared a tick were refused";
        tripNanos = nowNanos;
        tripConnection = setbacks.connection();
        return true;
    }

    /**
     * Fast placement may send more than one click in a tick: the latch ({@link #sharedOutcome}) has not tripped, or its
     * trip was undone: a new connection (a new Grim player, violation levels at zero), or a server setback within
     * {@link #SETBACK_EXCUSE_NANOS} of the trip (the refusals were the setback's; the setback may arrive after them).
     */
    public boolean fastPlacementOn() {
        if (fastOff == null) return true;
        String why = null;
        long sb = setbacks.lastSetbackNanos();
        if (setbacks.connection() != tripConnection) why = "a new connection";
        else if (tripNanos != SetbackClock.NONE && sb != SetbackClock.NONE && Math.abs(sb - tripNanos) < SETBACK_EXCUSE_NANOS)
            why = "a server setback within 2 s of it: the refusals were the setback's";
        if (why == null) return false;
        restored = "fast placement on again (it went off for " + fastOff + "; " + why + ")";
        fastOff = null;
        shared.clear();
        sharedFailed = 0;
        tripNanos = SetbackClock.NONE;
        return true;
    }

    /** Once, after {@link #fastPlacementOn} undid the latch: the line to log; else null. */
    public @org.jspecify.annotations.Nullable String takeRestored() {
        String r = restored;
        restored = null;
        return r;
    }

    /** Why the latch turned fast placement off, or null. */
    public @org.jspecify.annotations.Nullable String fastPlacementOff() {
        return fastOff;
    }

    // ------------------------------------------------------------------------------------------------- outcomes

    /** The server placed the block of a click sent at {@code sentAt}, within the confirm window. */
    public void onConfirm(long sentAt, long now) {
        confirmations++;
        success(sentAt, now);
    }

    /** The block of a click sent at {@code sentAt} showed only after the confirm window: a success all the same. */
    public void onLate(long sentAt, long now) {
        late++;
        success(sentAt, now);
    }

    /** The server answered a click sent at {@code sentAt} and kept the old state: double the interval. */
    public void onReject(long sentAt, long now) {
        rejections++;
        slowDown(sentAt, now, (int) Math.min(Integer.MAX_VALUE, interval * 2L));
    }

    /**
     * A click sent at {@code sentAt} failed, by kind. Drops never change the interval. LEGACY: other kinds
     * act as {@link #onReject}. BURST: a stack end changes nothing; a refusal doubles only as the second event within
     * the burst window (refusals of clicks sent within one round trip of the event's first click belong to it).
     */
    public void onRefusal(long sentAt, long now, Refusal kind) {
        if (kind == Refusal.DROPPED) {
            drops++;
            return;
        }
        if (policy == Policy.LEGACY) {
            onReject(sentAt, now);
            return;
        }
        rejections++;
        if (kind == Refusal.STACK_END) {
            stackEnds++;
            return;
        }
        if (eventSent != NONE && Math.abs(sentAt - eventSent) <= Math.max(2, rttTicks())) return; // the same event
        boolean burst = eventAt != NONE && now - eventAt <= burstWindow;
        eventAt = now;
        eventSent = sentAt;
        if (burst) {
            bursts++;
            burstSlowDown(sentAt, now);
        } else {
            isolated++;
        }
    }

    /**
     * BURST: doubles the interval (once per event, {@link #slowDown}). A slow-down within {@link #FAILED_SPEED_UP_TICKS}
     * of the last speed-up means that speed-up did not hold (a rate limit answers it within a round trip or two): the
     * next recovery takes twice as many successes, up to {@link #MAX_BOOST} times {@code burstRecover}; any later
     * slow-down starts again from one time. Simulated (PacingSimTest): a 12/s server cap costs no more cells than the
     * previous pacer, while random refusals (1.2 %) keep their full speed-up.
     */
    private void burstSlowDown(long sentAt, long now) {
        if (sentAt < changedAt) return;
        boost = speedUpAt != NONE && now - speedUpAt <= FAILED_SPEED_UP_TICKS ? Math.min(MAX_BOOST, boost * 2) : 1;
        change(now, (int) Math.min(Integer.MAX_VALUE, interval * 2L));
    }

    /** Nothing came back for a click sent at {@code sentAt} within the confirm window: one tick slower. */
    public void onUnknown(long sentAt, long now) {
        unknowns++;
        slowDown(sentAt, now, interval + 1);
    }

    /** A rejection of a click sent at the current interval (no tick information): doubles the interval. */
    public void onMiss() {
        rejections++;
        streak = 0;
        interval = clamp((int) Math.min(Integer.MAX_VALUE, interval * 2L));
    }

    /** A confirmation of a click sent at the current interval (no tick information). */
    public void onConfirm() {
        confirmations++;
        if (++streak >= recoverStreak()) {
            streak = 0;
            interval = clamp((interval + 1) / 2);
        }
    }

    private void success(long sentAt, long now) {
        if (sentAt < changedAt) return; // sent before the last change: says nothing about the current interval
        if (++streak >= recoverStreak()) {
            int before = interval;
            change(now, (interval + 1) / 2);
            if (interval < before) speedUpAt = now;
        }
    }

    private void slowDown(long sentAt, long now, int to) {
        if (sentAt < changedAt) return; // sent before the last change: this event was already answered
        change(now, to);
    }

    private void change(long now, int to) {
        streak = 0;
        changedAt = now;
        interval = clamp(to);
    }

    /**
     * Successes in a row needed before the interval halves: {@code recoverAfter} (BURST: times the boost of
     * {@link #burstSlowDown}), or the clicks one round trip ({@link #rttTicks}) holds at the current interval if that is
     * more.
     */
    public int recoverStreak() {
        int rtt = rttTicks();
        int need = policy == Policy.BURST ? recoverAfter * boost : recoverAfter;
        return Math.max(need, (rtt + interval - 1) / interval);
    }

    // ------------------------------------------------------------------------------------------------- latency

    /** A latency in ticks: an ack round trip, or click-to-block of a confirmation. */
    public void observeLatency(long ticks) {
        latency[latencyNext] = (int) Math.max(0, Math.min(Integer.MAX_VALUE, ticks));
        latencyNext = (latencyNext + 1) % SAMPLES;
        if (latencyCount < SAMPLES) latencyCount++;
    }

    /** Median of the recent latencies in ticks (0 before the first answer). */
    public int rttTicks() {
        if (latencyCount == 0) return 0;
        int[] s = java.util.Arrays.copyOf(latency, latencyCount);
        java.util.Arrays.sort(s);
        return s[latencyCount / 2];
    }

    /** Highest recent latency in ticks (0 before the first answer). */
    public int maxLatency() {
        int m = 0;
        for (int i = 0; i < latencyCount; i++) m = Math.max(m, latency[i]);
        return m;
    }

    /**
     * Ticks to wait for the answer to a click before it counts as unknown: {@code minWindow} (build.placeConfirmTicks),
     * widened to the highest recent latency + {@link #WINDOW_MARGIN}, at most the configured cap.
     */
    public int window(int minWindow) {
        int lo = Math.max(1, minWindow);
        return Math.max(lo, Math.min(maxWindow, maxLatency() + WINDOW_MARGIN));
    }

    /** Cap of the confirm window, ticks. */
    public int maxWindow() {
        return Math.max(1, maxWindow);
    }

    private int clamp(int v) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    public String toString() {
        return "AdaptivePacer[interval=" + interval + " in [" + min + "," + max + "], streak=" + streak + "/"
            + recoverStreak() + ", confirmed=" + confirmations + ", late=" + late + ", rejected=" + rejections
            + ", dropped=" + drops + ", unknown=" + unknowns + ", rtt=" + rttTicks() + "t"
            + (policy == Policy.BURST ? ", " + policy + ": stack ends=" + stackEnds + ", isolated=" + isolated
            + ", bursts=" + bursts + (boost > 1 ? ", recovery x" + boost : "") : "") + "]";
    }
}
