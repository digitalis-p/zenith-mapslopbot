package io.github.digitalisp.mapart.bot.impl;

/**
 * When the server last set the bot back, and which connection this is, for the fast placement latch
 * ({@link AdaptivePacer#sharedOutcome(boolean, long)}). Places sent while a setback is pending are refused with the same
 * signature as Grim's MultiPlace (an exact ack, no block): live 2026-10-15 a burst of 5 setbacks in 0.3 s turned fast
 * placement off for the rest of the run. A new connection is a new Grim player, whose violation levels start at zero.
 * Written from the Netty threads, read on the tick loop.
 */
public final class SetbackClock {
    public static final SetbackClock SESSION = new SetbackClock();
    static final long NONE = Long.MIN_VALUE;

    private volatile long lastSetbackNanos = NONE;
    private volatile int connection;

    /** A small server setback at {@code nanos} ({@link System#nanoTime}). */
    public void setback(long nanos) {
        lastSetbackNanos = nanos;
    }

    /** A new login: the next connection. */
    public void newConnection() {
        connection++;
    }

    long lastSetbackNanos() {
        return lastSetbackNanos;
    }

    int connection() {
        return connection;
    }
}
