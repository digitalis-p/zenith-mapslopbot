package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;

/**
 * Holds elytra take-offs back while the bot's state may not be its own ({@code build.elytraTakeoffHoldSeconds}). Live
 * 2026-10-25 08:33: the owner flew the bot's elytra
 * while in control of the account and left it worn; 2 s after the bot's ticks started again it took off on an arc, and
 * 4 s later the server set it back 20.6 blocks mid-flight (SAFETY STOP). 08:47: the server froze for 37 s; the client
 * went on simulating.
 *
 * <p>Armed by the bot's ticks starting (a handoff from a player, a reconnect, a deploy: {@link #botTicksStarted}), by
 * every server teleport or setback ({@link #teleport}), by the server going silent ({@link #pulse} stopped for
 * {@link #SILENT_MS}) and by any caller's {@link #arm}. Cleared once the hold has passed
 * since the last arming and the bot has moved on the ground for {@link #GROUND_MOVES} ticks since (moves the server
 * took without a teleport back). The first take-off after the bot's ticks started also checks the gear
 * ({@link #handoffPending}). After a login (a reconnect, a deploy, 2b2t's queue to the main server) the server's
 * world time must have come {@link #TIME_UPDATES} times (about 10 s) before a take-off: the TPS estimate and a freeze
 * are seen only then (live 2026-10-25 09:10: a take-off 7 s after a redeploy, "longest recent 0"). Thread-safe: the
 * packet hooks run on the network thread.
 */
public final class TakeoffGuard {
    public static final TakeoffGuard SESSION = new TakeoffGuard();

    /** On-ground ticks of movement (blocks a tick across the ground, not a jump of a teleport) a release needs. */
    static final int GROUND_MOVES = 5;
    static final double MOVE_MIN = 0.02;
    static final double MOVE_MAX = 1.0;
    /** No packet the server sends every second for this long: the server is silent (frozen, or the link stalled). */
    static final long SILENT_MS = 2500;
    /** World-time updates (one a second from the server) a take-off needs since the login. */
    static final int TIME_UPDATES = 10;

    private long armedAt = Long.MIN_VALUE;
    private @Nullable String why;
    private int groundMoves;
    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;
    private boolean handoff;
    private volatile long lastPulse = Long.MIN_VALUE;
    private boolean silentArmed;
    private volatile int timeUpdates;

    /** Arms the hold: {@code reason} for the refusal's words, {@code nowMs} wall time. */
    synchronized void arm(String reason, long nowMs) {
        armedAt = nowMs;
        why = reason;
        groundMoves = 0;
    }

    /** The bot's ticks started (a player handed the account back, a reconnect, a deploy): the hold and the gear check. */
    public synchronized void botTicksStarted(long nowMs) {
        arm("the bot's ticks started (a player's control ended, a reconnect or a deploy)", nowMs);
        handoff = true;
        lastX = Double.NaN;
    }

    /** The server moved the bot (a teleport, a setback). */
    public void teleport(long nowMs) {
        lastTeleport = nowMs;
        arm("a server teleport", nowMs);
    }

    private volatile long lastTeleport = Long.MIN_VALUE;

    /** Wall time of the last server teleport or setback ({@link Long#MIN_VALUE}: none): a flight in the air ends on one. */
    long lastTeleport() {
        return lastTeleport;
    }

    /** A packet the server sends at least every second arrived (its time, a keep-alive, entity moves). */
    public void pulse(long nowMs) {
        lastPulse = nowMs;
    }

    /** The server's world time arrived (a pulse, and one more toward {@link #TIME_UPDATES}). */
    public void timeUpdate(long nowMs) {
        lastPulse = nowMs;
        timeUpdates++;
    }

    /** A login (a new connection, 2b2t's queue to the main server): the world-time count starts again, the hold too. */
    public synchronized void login(long nowMs) {
        timeUpdates = 0;
        arm("a login", nowMs);
    }

    /** Milliseconds since the server's last pulse (0 before the first). */
    long silentMs(long nowMs) {
        long l = lastPulse;
        return l == Long.MIN_VALUE ? 0 : Math.max(0, nowMs - l);
    }

    /** The server sends nothing for {@link #SILENT_MS} or longer. */
    boolean silent(long nowMs) {
        return silentMs(nowMs) >= SILENT_MS;
    }

    /** Every bot tick: on-ground moves since the arming; a silent server arms the hold (once a silence). */
    synchronized void tick(boolean onGround, double x, double z, long nowMs) {
        if (silent(nowMs)) {
            if (!silentArmed) arm("the server went silent (no packets for " + silentMs(nowMs) / 1000 + " s)", nowMs);
            silentArmed = true;
            armedAt = nowMs; // the hold counts from the end of the silence
            groundMoves = 0;
        } else {
            silentArmed = false;
        }
        if (!Double.isNaN(lastX) && onGround) {
            double d = Math.hypot(x - lastX, z - lastZ);
            if (d >= MOVE_MIN && d <= MOVE_MAX) groundMoves++;
        }
        lastX = x;
        lastZ = z;
    }

    /** Why a take-off waits now ({@code holdMs}: the hold), or null; clears the hold once it has passed. */
    synchronized @Nullable String refusal(long nowMs, long holdMs) {
        if (silent(nowMs)) return "the server is silent (no packets for " + silentMs(nowMs) / 1000 + " s)";
        if (timeUpdates < TIME_UPDATES) {
            return String.format("take-off hold: %d of %d world-time updates since the login", timeUpdates, TIME_UPDATES);
        }
        if (why == null) return null;
        long age = nowMs - armedAt;
        if (age < holdMs) {
            return String.format("take-off hold: %s %.0f s ago (%d s hold)", why, age / 1000.0, holdMs / 1000);
        }
        if (groundMoves < GROUND_MOVES) {
            return String.format("take-off hold: %s; %d of %d moves on the ground since", why, groundMoves, GROUND_MOVES);
        }
        why = null;
        return null;
    }

    /** The first take-off since the bot's ticks started still has to check the gear ({@link FlightLeg}). */
    synchronized boolean handoffPending() {
        return handoff;
    }

    synchronized void handoffChecked() {
        handoff = false;
    }

    /** Tests: as new. */
    synchronized void reset() {
        armedAt = Long.MIN_VALUE;
        why = null;
        groundMoves = 0;
        lastX = Double.NaN;
        lastZ = Double.NaN;
        handoff = false;
        lastPulse = Long.MIN_VALUE;
        silentArmed = false;
        timeUpdates = 0;
        lastTeleport = Long.MIN_VALUE;
    }
}
