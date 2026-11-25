package io.github.digitalisp.mapart.bot.impl;

import java.util.ArrayDeque;

/** Paper's anchored window, on the client loop; only an actual use-item-on advances its clock or count. */
public final class WallClickWindow {
    private boolean started;
    private long anchor;
    private int count;
    private final ArrayDeque<Long> recent = new ArrayDeque<>();

    public boolean allows(long now, int millis, int max) {
        return !started || now - anchor >= millis * 1_000_000L || count < max;
    }

    public long opensAt(int millis) {
        return anchor + millis * 1_000_000L;
    }

    /** Rolling safety for lookahead; history belongs to the shared pacer, including across lane replacement. */
    public long rollingOpensAt(long now, int millis, int max) {
        expire(now, millis);
        if (recent.size() < max) return Long.MIN_VALUE;
        int skip = recent.size() - max;
        for (long time : recent) if (skip-- == 0) return time + millis * 1_000_000L;
        return Long.MIN_VALUE;
    }

    private void expire(long now, int millis) {
        while (!recent.isEmpty() && now - recent.peekFirst() >= millis * 1_000_000L) recent.pollFirst();
    }

    public void sent(long now, int millis) {
        expire(now, millis);
        recent.addLast(now);
        if (!started || now - anchor >= millis * 1_000_000L) {
            started = true;
            anchor = now;
            count = 0;
        }
        count++;
    }
}
