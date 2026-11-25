package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;

/**
 * After a hotbar move (an inventory click), the target slot is unsafe and nothing else moves until the server has
 * said the target slot holds the moved item ({@code build.hotbarMoveFence}).
 *
 * <p><b>Why.</b> ZenithProxy writes a click's prediction into its cache when the click goes out, then applies every
 * server update when it arrives. Updates the server sent before it handled the click (a placement's count update for
 * the slot, the full inventory 2b2t sends behind slot updates) arrive after the click and put the slot's old item
 * back into the cache until the click's own answer follows. Live 2b2t 2026-10-12 (replay of 13:43-17:54): all 83
 * "placed as" cells came within 0.5 s of a hotbar move the server reported (69 within 0.15 s), none among the 61,000
 * placements later than that; 27 % of the moves had such a stale update between the click and its answer. In that
 * gap the lane clicked the slot for its old item while the server held the new one (runs of 1-6 wrong blocks), or
 * moved the "missing" item in again from where the cache wrongly showed it.
 *
 * <p><b>The answer.</b> The move goes out with a state id the server does not have, so it answers with the whole
 * inventory, as ZenithProxy's shift-click always does (vanilla {@code broadcastFullState}). Server reports are
 * counted ({@link Reports}); the wait ends with a report after the click that shows the expected item in the target
 * slot. A stale report still shows the old item, so it never ends the wait. Without such a report within
 * {@link #TIMEOUT} ticks (the server refused the move, or the cache was wrong about the moved stack) the wait ends
 * anyway for ordinary moves: the caller plans again from the cache. Speculative lookahead moves use the bounded
 * session recovery in InventoryEchoes; an abandoned slot stays unknown to placement until a server report.
 * Pure, tick by tick.
 */
final class MoveFence {
    /** Hotbar contents as the server reported them ({@link InventoryEchoes}). */
    interface Reports {
        /** A mark in the stream of reports, to pass to {@link #reportedSince}. Never decreases. */
        long mark();

        /** The server reported hotbar slot {@code hotbar} after {@code mark}, and its last report shows {@code item}. */
        boolean reportedSince(int hotbar, long mark, @Nullable String item);

        /** The session confirmed, resynced or abandoned this speculative move. */
        default boolean released(int hotbar, long mark) { return false; }
    }

    /** Ticks to wait for the server's answer (2b2t round trips: 3-8 ticks, spikes to 25). */
    static final int TIMEOUT = 40;

    private int slot = -1;
    private @Nullable String expected;
    private long mark;
    private long since;
    private int moves;
    private int timeouts;
    private long waited;
    private @Nullable String lastTimeout;
    private boolean requireAnswer;
    private boolean warned;
    private long checked;

    /** A move into hotbar slot {@code hotbar} of a stack of {@code item} went out at {@code now}; {@code mark}: before it. */
    void arm(int hotbar, @Nullable String item, long mark, long now) {
        arm(hotbar, item, mark, now, false);
    }

    /** Speculative moves keep their slot fenced even after a missing-answer warning. */
    void arm(int hotbar, @Nullable String item, long mark, long now, boolean requireAnswer) {
        this.slot = hotbar;
        this.expected = item;
        this.mark = mark;
        this.since = now;
        this.requireAnswer = requireAnswer;
        this.warned = false;
        this.checked = now;
        moves++;
    }

    /**
     * True while the last move waits for the server's answer. Ends the wait (false) once a report after the move shows
     * the expected item in the slot, or after {@link #TIMEOUT} ticks for ordinary moves only.
     */
    boolean waiting(long now, Reports reports) {
        if (slot < 0) return false;
        checked = Math.max(checked, now);
        if (requireAnswer && now - since >= TIMEOUT && !warned) {
            timeouts++;
            warned = true; // the session recovery emits the warning on its connection clock
        }
        if (reports.reportedSince(slot, mark, expected) || (requireAnswer && reports.released(slot, mark))) {
            waited += Math.max(0, now - since);
            slot = -1;
            return false;
        }
        if (now - since > TIMEOUT) {
            if (!warned) {
                timeouts++;
                lastTimeout = "hotbar slot " + slot + " (" + expected + ")";
                warned = true;
            }
            if (requireAnswer) return true;
            waited += now - since;
            slot = -1;
            return false;
        }
        return true;
    }

    /** The unsafe hotbar slot while its move is pending. */
    int slot() {
        return slot;
    }

    /** The move that just timed out, once (for a log line), else null. */
    @Nullable String takeTimeout() {
        String t = lastTimeout;
        lastTimeout = null;
        return t;
    }

    int moves() {
        return moves;
    }

    int timeouts() {
        return timeouts;
    }

    /** Ticks spent waiting for answers so far. */
    long waited() {
        return waited + (requireAnswer && slot >= 0 ? Math.max(0, checked - since) : 0);
    }

    /** For a lane's summary line: empty without moves. */
    String summary() {
        if (moves == 0) return "";
        return "; " + moves + " hotbar move" + (moves == 1 ? "" : "s") + " waited " + waited() + " t for the server"
            + (timeouts > 0 ? " (" + timeouts + " without an answer in " + TIMEOUT + " t)" : "");
    }
}
