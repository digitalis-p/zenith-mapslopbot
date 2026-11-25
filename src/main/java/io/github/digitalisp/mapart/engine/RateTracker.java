package io.github.digitalisp.mapart.engine;

import java.util.ArrayDeque;

/**
 * Moving-average placement rate over a sliding window (includes restock trips, so the ETA does too).
 * Tick thread only.
 */
final class RateTracker {
    private static final long BUCKET_MS = 10_000L;
    private final long windowMs;
    private final ArrayDeque<long[]> buckets = new ArrayDeque<>(); // {bucketStart, count}
    private long startedAt = -1;

    RateTracker(long windowMs) {
        this.windowMs = windowMs;
    }

    void reset(long now) {
        buckets.clear();
        startedAt = now;
    }

    void record(long now) {
        if (startedAt < 0) startedAt = now;
        long start = now - Math.floorMod(now, BUCKET_MS);
        long[] last = buckets.peekLast();
        if (last != null && last[0] == start) last[1]++;
        else buckets.addLast(new long[]{start, 1});
        prune(now);
    }

    /** Placements per second over the window, 0 if unknown. */
    double perSecond(long now) {
        prune(now);
        if (startedAt < 0 || buckets.isEmpty()) return 0;
        long n = 0;
        for (long[] b : buckets) n += b[1];
        long from = Math.max(startedAt, now - windowMs);
        long span = Math.max(now - from, 30_000L);
        return n * 1000.0 / span;
    }

    /** Seconds until {@code remaining} placements are done at the current rate, or -1 if unknown. */
    long etaSeconds(int remaining, long now) {
        if (remaining <= 0) return 0;
        double r = perSecond(now);
        return r <= 0 ? -1 : (long) Math.ceil(remaining / r);
    }

    private void prune(long now) {
        while (!buckets.isEmpty() && buckets.peekFirst()[0] + BUCKET_MS <= now - windowMs) buckets.pollFirst();
    }
}
