package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Turns silent looks ({@link SilentRotation}) off for the rest of the run once the server answers them with setbacks.
 * Pure (the clock is passed in) and unit-tested; one
 * instance per ZenithProxy run, {@link #SESSION}.
 *
 * <p>Why: on 2b2t (live 2026-10-11, job "ellen") 22 of 26 LOOK placements, i.e. the cells that use a silent look
 * (pointed dripstone, glow lichen, a candle: an extra PlayerInput + MovePlayerPosRot before Bot's own movement packet),
 * drew a server setback within 1 s, against 12 of 200 random placements (6 %); a carpet-only recording had no LOOK
 * placement and no setback. Once that happens, LOOK cells go with the real rotation, as with {@code build.rotatePlayer}
 * true: the plain vanilla packet order.
 *
 * <p>The cause, in Grim 2.3.73's source: {@code TickTimer} is {@code @CheckData(setback = 1)} and flags a second flying
 * packet before {@code CLIENT_TICK_END}, which the silent look's MovePlayerPosRot is (Bot's own movement packet follows
 * in the same tick); and {@code CheckManagerListener} cancels every block place while a setback is pending
 * ({@code shouldBlockMovement}), so a flagged look also costs the placements after it until the setback is accepted.
 *
 * <ul>
 *   <li>{@link #sent}: a silent look went out (client tick thread).</li>
 *   <li>{@link #setback}: a small server setback, the teleport guard's judgement (Netty thread). It counts when it
 *       arrives within {@link #LOOK_WINDOW_MILLIS} after a silent look that has not drawn one yet: a look draws at
 *       most one counted setback, so one look followed by a burst of setbacks counts once.</li>
 *   <li>{@link #THRESHOLD} counted setbacks within {@link #COUNT_WINDOW_MILLIS}: silent looks are off until the
 *       ZenithProxy run ends (never back on: a restart, or {@code build.rotatePlayer} true, decides again).</li>
 * </ul>
 * Lane tasks ask {@link SilentRotation#usable} when they start; a lane task already running keeps its silent looks
 * until it ends (its clicks are checked against the look it sent), which after a burst is the engine's back-off.
 *
 * <p>Thread-safe: every method is synchronized (looks arrive on the client tick thread, setbacks on the Netty thread).
 */
final class SilentLookGuard {
    /**
     * A setback arriving this long after a silent look counts against it: the setback comes one round trip after the
     * look (2b2t: 25 of 26 LOOK placements had one within 3 s, 22 within 1 s), with room for a slow round trip.
     */
    static final long LOOK_WINDOW_MILLIS = 1500;
    /** Counted setbacks are kept this long. */
    static final long COUNT_WINDOW_MILLIS = 60_000;
    /**
     * Counted setbacks within {@link #COUNT_WINDOW_MILLIS} that turn silent looks off: with 22 of 26 looked placements
     * drawing a setback and 6 % of the others, two in a minute is not chance, and one alone may be.
     */
    static final int THRESHOLD = 2;
    /** Looks remembered at most (a look per tick while a LOOK cell is aimed: 30 is 1.5 s). */
    static final int MAX_LOOKS = 64;

    /** The ZenithProxy run's guard (static: it outlives the engine's jobs and the module's restarts). */
    static final SilentLookGuard SESSION = new SilentLookGuard();

    /** Times of the silent looks within {@link #LOOK_WINDOW_MILLIS} of the latest one that drew no setback yet. */
    private final ArrayDeque<Long> looks = new ArrayDeque<>();
    /** Times of the counted setbacks within {@link #COUNT_WINDOW_MILLIS}. */
    private final ArrayDeque<Long> counted = new ArrayDeque<>();
    private @Nullable String reason;
    private @Nullable String notice;

    /** A silent look went out at {@code nowMillis}. */
    synchronized void sent(long nowMillis) {
        if (reason != null) return;
        looks.addLast(nowMillis);
        while (!looks.isEmpty() && (nowMillis - looks.peekFirst() > LOOK_WINDOW_MILLIS || looks.size() > MAX_LOOKS)) {
            looks.removeFirst();
        }
    }

    /**
     * A small server setback arrived at {@code nowMillis}. It counts when a silent look that drew none yet went out at
     * most {@link #LOOK_WINDOW_MILLIS} before it (the latest such look draws it). True when this setback turned silent
     * looks off.
     */
    synchronized boolean setback(long nowMillis) {
        if (reason != null) return false;
        if (!claimLook(nowMillis)) return false;
        counted.addLast(nowMillis);
        while (!counted.isEmpty() && nowMillis - counted.peekFirst() > COUNT_WINDOW_MILLIS) counted.removeFirst();
        if (counted.size() < THRESHOLD) return false;
        reason = counted.size() + " server setbacks within " + seconds(LOOK_WINDOW_MILLIS) + " of a silent look in the"
            + " last " + seconds(COUNT_WINDOW_MILLIS) + ": LOOK cells (pointed dripstone, glow lichen, candles, ...) now"
            + " turn the bot's real rotation; set build.rotatePlayer true to keep it so after a restart";
        notice = reason;
        looks.clear();
        counted.clear();
        return true;
    }

    /** Removes the latest look at most {@link #LOOK_WINDOW_MILLIS} before {@code nowMillis}; false if none. */
    private boolean claimLook(long nowMillis) {
        Iterator<Long> it = looks.descendingIterator();
        while (it.hasNext()) {
            long t = it.next();
            long age = nowMillis - t;
            if (age < 0) continue; // a look after the setback did not cause it
            if (age > LOOK_WINDOW_MILLIS) return false; // older ones are older still
            it.remove();
            return true;
        }
        return false;
    }

    /** Silent looks are off for the rest of the run. */
    synchronized boolean disabled() {
        return reason != null;
    }

    /** Why silent looks are off, or null while they are on. */
    synchronized @Nullable String reason() {
        return reason;
    }

    /** The reason once, right after silent looks were turned off (the Discord alert); null otherwise. */
    synchronized @Nullable String notice() {
        String n = notice;
        notice = null;
        return n;
    }

    /** "1.5 s", "60 s". */
    private static String seconds(long millis) {
        return millis % 1000 == 0 ? millis / 1000 + " s" : millis / 1000.0 + " s";
    }
}
