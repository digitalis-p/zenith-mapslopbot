package io.github.digitalisp.mapart.bot.impl;

import io.github.digitalisp.mapart.config.MapArtConfig;

import java.util.Locale;

/**
 * How lane clicks are paced and their failures judged ({@code build.placePacer} and
 * the keys below). {@link #LEGACY} is the behaviour before the burst pacer: the kill switch.
 *
 * <ul>
 *   <li>{@code policy}: {@link AdaptivePacer.Policy#BURST} slows down only for a burst of refusals (two refusal events
 *       within {@code burstWindow} ticks); an isolated refusal and a click with a
 *       used-up stack (a stack-end rejection) leave the interval as it is, and a stack-end rejection is retried at once,
 *       not counted against the cell's sends. {@link AdaptivePacer.Policy#LEGACY}: every rejection doubles the
 *       interval, every rejected cell waits the retry delay.</li>
 *   <li>{@code recoverAfter}: successes in a row before the interval halves under LEGACY
 *       ({@code build.placeRecoverConfirms}); {@code burstRecover} the same under BURST.</li>
 *   <li>{@code settleTicks}: a click stops counting against its hotbar stack this many ticks after its ack (the
 *       slot update that shows the click arrives with the ack); 2 is the previous rule.</li>
 *   <li>{@code stackSwitch}: when the held stack is used up by clicks in flight, the next click goes from another hotbar
 *       stack of the same item (a hotbar select in the click tick) instead of waiting for the acks.</li>
 *   <li>{@code belady}: a move from the main inventory replaces the lane's hotbar stack needed last by the cells left
 *       (Belady's rule, {@code build.placeHotbarBelady}); false: round robin, as before.</li>
 *   <li>{@code pacedWalk}: the deadline-paced lane walk ({@code build.pacedLaneWalk}, {@code LaneRun} "Paced walk"):
 *       the walk keeps going while the click queue's deadlines allow instead of stopping for every cell behind;
 *       false (default until a supervised live trial): stop and go, as before.</li>
 *   <li>{@code fenceClicks}: click safe hotbar slots after request execution while the move fence waits
 *       ({@code build.placeDuringMoveFence}); false holds every click as before.</li>
 *   <li>{@code prefetch}: fill an unused hotbar slot for the next missing lane material during an existing paced
 *       standing tick without a click due ({@code build.placeHotbarPrefetch}).</li>
 *   <li>{@code hotbarStacks}: prepare the lane's next materials and popular-item second stacks before placement
 *       ({@code build.placeHotbarStacks}), respecting reserved slots and the full move fence.</li>
 *   <li>{@code airplaceBatch}: one offhand swap pair around a tick's airplaces
 *       ({@code build.placeAirPlaceBatch}), closed before movement even on an exception.</li>
 *   <li>{@code clicksPerTick}: fast placement ({@code build.placeClicksPerTick}, a research
 *       prototype): up to this many airplaces in one tick of a lane walk, all from the held hotbar slot, carpets and
 *       other blocks that neither stand in the way nor hide anything; 1 (default) = one click per tick, as before (the
 *       kill switch). With more than 1 every lane click also keeps to {@code windowMax} clicks in any {@code windowTicks}
 *       ticks in a row ({@code build.placeClickWindowMax}, {@code build.placeClickWindowTicks}): Paper's click limiter
 *       ({@code spam-limiter.incoming-packet-threshold}, 300 ms = 6 ticks) drops every use-item packet after the 9th of
 *       a window, and network jitter can stretch a window over 7 of the bot's ticks. {@code adaptiveWindow} learns
 *       its duration within [windowTicksMin, windowTicks + 1] from clean shared clicks and Paper drops.</li>
 * </ul>
 */
public record PlacePacing(AdaptivePacer.Policy policy, int recoverAfter, int burstWindow, int burstRecover,
                          int settleTicks, boolean stackSwitch, boolean belady, boolean pacedWalk,
                          int clicksPerTick, int windowTicks, int windowMax, boolean fenceClicks, boolean prefetch,
                          boolean adaptiveWindow, int windowTicksMin, int windowCleanClicks, double dropRateMax,
                          int latchFails, int latchWindow, boolean hotbarStacks, boolean airplaceBatch,
                          boolean subTickDispatch, int windowMillis) {
    /** {@code build.placeClicksPerTick}'s default: one click per tick (fast placement off). */
    public static final int DEFAULT_CLICKS_PER_TICK = 1;
    /** {@code build.placeClickWindowTicks}'s default: 7 ticks (a 300 ms window stretched by up to 50 ms of jitter). */
    public static final int DEFAULT_WINDOW_TICKS = 7;
    /** {@code build.placeClickWindowMax}'s default: Paper's 9 use-item packets per window. */
    public static final int DEFAULT_WINDOW_MAX = 9;

    /** The behaviour before the burst pacer (16 confirms to recover, 2 settle ticks, no stack switch, round robin). */
    public static final PlacePacing LEGACY = new PlacePacing(AdaptivePacer.Policy.LEGACY,
        AdaptivePacer.DEFAULT_RECOVER, AdaptivePacer.DEFAULT_BURST_WINDOW, AdaptivePacer.DEFAULT_BURST_RECOVER, 2,
        false, false, false);
    /** The config defaults. */
    public static final PlacePacing DEFAULT = new PlacePacing(AdaptivePacer.Policy.BURST,
        AdaptivePacer.DEFAULT_RECOVER, AdaptivePacer.DEFAULT_BURST_WINDOW, AdaptivePacer.DEFAULT_BURST_RECOVER, 1,
        true, true, false);

    /** Existing callers use the idle-tick fixes by default. */
    public PlacePacing(AdaptivePacer.Policy policy, int recoverAfter, int burstWindow, int burstRecover,
                       int settleTicks, boolean stackSwitch, boolean belady, boolean pacedWalk,
                       int clicksPerTick, int windowTicks, int windowMax) {
        this(policy, recoverAfter, burstWindow, burstRecover, settleTicks, stackSwitch, belady, pacedWalk,
            clicksPerTick, windowTicks, windowMax, true, true);
    }

    /** Previous constructor retains the adaptive config defaults. */
    public PlacePacing(AdaptivePacer.Policy policy, int recoverAfter, int burstWindow, int burstRecover,
                       int settleTicks, boolean stackSwitch, boolean belady, boolean pacedWalk,
                       int clicksPerTick, int windowTicks, int windowMax, boolean fenceClicks, boolean prefetch) {
        this(policy, recoverAfter, burstWindow, burstRecover, settleTicks, stackSwitch, belady, pacedWalk,
            clicksPerTick, windowTicks, windowMax, fenceClicks, prefetch, true, 6, 60, 0.02, 3, 20);
    }

    /** Compatibility constructor: new optimizations follow the config defaults. */
    public PlacePacing(AdaptivePacer.Policy policy, int recoverAfter, int burstWindow, int burstRecover,
                       int settleTicks, boolean stackSwitch, boolean belady, boolean pacedWalk,
                       int clicksPerTick, int windowTicks, int windowMax, boolean fenceClicks, boolean prefetch,
                       boolean adaptiveWindow, int windowTicksMin, int windowCleanClicks, double dropRateMax,
                       int latchFails, int latchWindow) {
        this(policy, recoverAfter, burstWindow, burstRecover, settleTicks, stackSwitch, belady, pacedWalk,
            clicksPerTick, windowTicks, windowMax, fenceClicks, prefetch, adaptiveWindow, windowTicksMin,
            windowCleanClicks, dropRateMax, latchFails, latchWindow, true, true);
    }

    /** Compatibility constructor: sub-tick dispatch remains off. */
    public PlacePacing(AdaptivePacer.Policy policy, int recoverAfter, int burstWindow, int burstRecover,
                       int settleTicks, boolean stackSwitch, boolean belady, boolean pacedWalk,
                       int clicksPerTick, int windowTicks, int windowMax, boolean fenceClicks, boolean prefetch,
                       boolean adaptiveWindow, int windowTicksMin, int windowCleanClicks, double dropRateMax,
                       int latchFails, int latchWindow, boolean hotbarStacks, boolean airplaceBatch) {
        this(policy, recoverAfter, burstWindow, burstRecover, settleTicks, stackSwitch, belady, pacedWalk,
            clicksPerTick, windowTicks, windowMax, fenceClicks, prefetch, adaptiveWindow, windowTicksMin,
            windowCleanClicks, dropRateMax, latchFails, latchWindow, hotbarStacks, airplaceBatch, false, 310);
    }

    public PlacePacing {
        recoverAfter = Math.max(1, recoverAfter);
        burstWindow = Math.max(1, burstWindow);
        burstRecover = Math.max(1, burstRecover);
        settleTicks = Math.max(0, settleTicks);
        clicksPerTick = Math.max(1, clicksPerTick);
        windowTicks = Math.max(1, windowTicks);
        windowMax = Math.max(1, windowMax);
        windowMillis = Math.max(300, Math.min(350, windowMillis));
        windowTicksMin = Math.max(1, Math.min(windowTicks, windowTicksMin));
        windowCleanClicks = Math.max(1, windowCleanClicks);
        dropRateMax = Double.isFinite(dropRateMax) ? Math.max(0, Math.min(1, dropRateMax)) : 0.02;
        latchWindow = Math.max(1, latchWindow);
        latchFails = Math.max(1, Math.min(latchWindow, latchFails));
    }

    /** Fast placement off ({@link #DEFAULT_CLICKS_PER_TICK}): the pacing before {@code build.placeClicksPerTick}. */
    public PlacePacing(AdaptivePacer.Policy policy, int recoverAfter, int burstWindow, int burstRecover,
                       int settleTicks, boolean stackSwitch, boolean belady, boolean pacedWalk) {
        this(policy, recoverAfter, burstWindow, burstRecover, settleTicks, stackSwitch, belady, pacedWalk,
            DEFAULT_CLICKS_PER_TICK, DEFAULT_WINDOW_TICKS, DEFAULT_WINDOW_MAX);
    }

    /** The same with fast placement: {@code clicksPerTick} airplaces per tick at most, {@code max} per {@code ticks}. */
    public PlacePacing withClicksPerTick(int clicksPerTick, int ticks, int max) {
        return new PlacePacing(policy, recoverAfter, burstWindow, burstRecover, settleTicks, stackSwitch, belady,
            pacedWalk, clicksPerTick, ticks, max, fenceClicks, prefetch,
            adaptiveWindow, windowTicksMin, windowCleanClicks, dropRateMax, latchFails, latchWindow, hotbarStacks, airplaceBatch,
            subTickDispatch, windowMillis);
    }

    /** The same pacing with a fixed wall window; false retains the tick gate. */
    public PlacePacing withSubTickDispatch(boolean enabled, int millis) {
        return new PlacePacing(policy, recoverAfter, burstWindow, burstRecover, settleTicks, stackSwitch, belady,
            pacedWalk, clicksPerTick, windowTicks, windowMax, fenceClicks, prefetch, adaptiveWindow, windowTicksMin,
            windowCleanClicks, dropRateMax, latchFails, latchWindow, hotbarStacks, airplaceBatch, enabled, millis);
    }

    /** Fast placement is on: more than one click per tick may go out ({@link #clicksPerTick}). */
    public boolean multiClick() {
        return clicksPerTick > 1;
    }

    /**
     * Clicks per tick the lane can keep up over a window: {@link #clicksPerTick}, at most {@link #windowMax} /
     * {@link #windowTicks} with fast placement on; 1 without.
     */
    public double clickRate() {
        return multiClick() ? Math.min(clicksPerTick,
            subTickDispatch ? windowMax * 50.0 / windowMillis : (double) windowMax / windowTicks) : 1.0;
    }

    /** From the build config. */
    public static PlacePacing of(MapArtConfig.BuildCfg b) {
        return new PlacePacing(policyOf(b.placePacer), b.placeRecoverConfirms, b.placeBurstWindowTicks,
            b.placeBurstRecoverConfirms, b.placeStackSettleTicks, b.placeStackSwitch, b.placeHotbarBelady,
            b.pacedLaneWalk, b.placeClicksPerTick, b.placeClickWindowTicks, b.placeClickWindowMax,
            b.placeDuringMoveFence, b.placeHotbarPrefetch, b.placeAdaptiveWindow, b.placeClickWindowTicksMin,
            b.placeClickWindowCleanClicks, b.placeDropRateMax, b.placeLatchFails, b.placeLatchWindow,
            b.placeHotbarStacks, b.placeAirPlaceBatch, b.placeSubTickDispatch, b.placeClickWindowMillis);
    }

    /** "LEGACY" (any case) is LEGACY; anything else, unknown values included, is BURST. */
    static AdaptivePacer.Policy policyOf(String s) {
        return s != null && s.trim().toUpperCase(Locale.ROOT).equals("LEGACY") ? AdaptivePacer.Policy.LEGACY
            : AdaptivePacer.Policy.BURST;
    }

    /** Successes in a row before the interval halves under this policy. */
    int recover() {
        return policy == AdaptivePacer.Policy.BURST ? burstRecover : recoverAfter;
    }

    /** Stack-end rejections are told apart from refusals for BURST and the fast-placement latch. */
    boolean classifyStackEnds() {
        return policy == AdaptivePacer.Policy.BURST || multiClick();
    }
}
