package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

import java.util.function.Predicate;

/**
 * A delayed block of ours that the server may still break after the break task that sent it ended: the task was
 * cancelled with it pending (the setback escape ladder, a stop, a timeout), and
 * nothing can take it back; the server breaks it with whatever is held at the time. Until it shows air or its deadline
 * passes:
 * <ul>
 *   <li>the next break task starts no round and switches nothing ({@link #take}, {@link BreakRun#lingering});</li>
 *   <li>the bot layer counts as busy ({@link ZenithBotActions#isBusy}), so the engine's idle keep-alive does not turn
 *       or swing meanwhile.</li>
 * </ul>
 * Pure apart from being one instance per ZenithProxy run ({@link #SESSION}); the clock and the world are passed in.
 * Client tick thread (synchronized anyway).
 */
public final class PendingDelayed {
    /** The ZenithProxy run's hand-off. */
    public static final PendingDelayed SESSION = new PendingDelayed();

    /** The block, and the wall-clock time by which the server breaks it at the latest. */
    record Handoff(Pos pos, long untilMillis) { }

    private @Nullable Handoff pending;

    /** A break task ended with its delayed block {@code pos} on the server, which breaks it by {@code untilMillis}. */
    synchronized void set(Pos pos, long untilMillis) {
        pending = new Handoff(pos, untilMillis);
    }

    /**
     * For the next break task: the block still pending at {@code nowMillis} (null if none or past its deadline). It
     * stays recorded (the bot stays busy) until it shows air or its deadline passes.
     */
    synchronized @Nullable Handoff take(long nowMillis) {
        Handoff h = pending;
        if (h == null) return null;
        if (nowMillis >= h.untilMillis()) {
            pending = null;
            return null;
        }
        return h;
    }

    /** The server may still break our delayed block at {@code nowMillis}: recorded, before its deadline, not air yet. */
    synchronized boolean pending(long nowMillis, Predicate<Pos> air) {
        Handoff h = pending;
        if (h == null) return false;
        if (nowMillis >= h.untilMillis() || air.test(h.pos())) {
            pending = null;
            return false;
        }
        return true;
    }

    /** Forget it (a new connection: the server's player, and its delayed slot, are new). */
    public synchronized void clear() {
        pending = null;
    }
}
