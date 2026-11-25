package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Locale;

/** Paper's arrival-time limiter gets its own feedback, independent of the placement interval and refusal latch. */
public final class AdaptiveClickWindow {
    public static final int SAMPLES = 100;
    private @Nullable PlacePacing pacing;
    private int ticks;
    private int cleanShared;
    private int drops;
    private int sinceWiden = SAMPLES;
    private final ArrayDeque<Boolean> recent = new ArrayDeque<>();

    /** Keep learned timing across lanes; changed window settings start a fresh measurement. */
    public void configure(PlacePacing p) {
        if (pacing == null || pacing.adaptiveWindow() != p.adaptiveWindow()
            || pacing.subTickDispatch() != p.subTickDispatch()
            || (p.subTickDispatch() && pacing.windowMillis() != p.windowMillis())
            || pacing.multiClick() != p.multiClick() || pacing.windowTicks() != p.windowTicks()
            || pacing.windowTicksMin() != p.windowTicksMin() || pacing.windowMax() != p.windowMax()
            || pacing.windowCleanClicks() != p.windowCleanClicks() || pacing.dropRateMax() != p.dropRateMax()) {
            ticks = p.windowTicks();
            recent.clear();
            drops = cleanShared = 0;
            sinceWiden = SAMPLES;
        }
        pacing = p;
    }

    public int ticks() { return ticks; }

    public double dropRate() { return recent.isEmpty() ? 0 : (double) drops / recent.size(); }

    /** One resolved click, including first clicks of a tick. Return a log line only when the window changes. */
    public @Nullable String outcome(PlaceTracker.Outcome outcome, boolean shared) {
        if (pacing == null || !pacing.multiClick() || !pacing.adaptiveWindow() || pacing.subTickDispatch()) return null;
        boolean dropped = outcome == PlaceTracker.Outcome.DROPPED;
        recent.addLast(dropped);
        if (dropped) drops++;
        if (recent.size() > SAMPLES && recent.removeFirst()) drops--;
        sinceWiden = Math.min(SAMPLES, sinceWiden + 1);
        boolean clean = outcome == PlaceTracker.Outcome.CONFIRMED || outcome == PlaceTracker.Outcome.LATE;
        if (clean && shared) cleanShared++;

        int next = ticks;
        // A widening needs fresh evidence before another widening; tightening never delays the first response.
        if (recent.size() == SAMPLES && sinceWiden >= SAMPLES && dropRate() > pacing.dropRateMax()) {
            next = Math.min(pacing.windowTicks() + 1, ticks + 1);
        } else if (cleanShared >= pacing.windowCleanClicks() && dropRate() <= pacing.dropRateMax()) {
            next = Math.max(pacing.windowTicksMin(), ticks - 1);
        }
        if (next == ticks) return null;
        if (next > ticks) sinceWiden = 0;
        ticks = next;
        cleanShared = 0;
        return String.format(Locale.ROOT, "click window %d per %d t (drops %.1f %% of the last %d)",
            pacing.windowMax(), ticks, dropRate() * 100, recent.size());
    }
}
