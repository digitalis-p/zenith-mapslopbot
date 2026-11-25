package io.github.digitalisp.mapart.engine;

import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.PlatformGeometry;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Teleport safeguard. Watches server-forced position changes while the bot is in control and halts the engine
 * when one looks wrong: a teleport further than {@code safety.teleportStopDistance}, a respawn or dimension
 * change, a death, or being far from every platform once logged in. Small anti-cheat setbacks raise an alert when
 * frequent. Pure and thread-safe: packet handlers call it from Netty threads, ticks from the event loop.
 *
 * <p><b>Setback back-off, escape ladder, pause</b>. Every small setback
 * (armed, at most {@code teleportStopDistance}) goes to {@link Sink#setback} (the silent-look monitor's input). A burst
 * of them ({@code setbackBurstCount} within {@code setbackBurstWindowMillis}) means the server keeps rejecting the
 * bot's movement where it stands (2b2t's Grim: a spot walled in by placed blocks, a look it does not accept); acting on
 * regardless piles up anti-cheat flags, and the 2026-10-11 incident showed that the same task restarting at the same
 * spot is set back again at once. So a burst asks the sink to back off
 * ({@link Sink#backoff(String, long, double, double, double, int)}) with where it happened and its rung: stop, stand
 * still for {@code setbackBackoffSeconds}, then step away from that spot (the escape) before the phase carries on.
 * Setbacks while it stands still are the stopped movement's in-flight replies and start no new burst. The rung counts
 * the back-offs within {@link #BACKOFF_ESCALATION_WINDOW_MS}, this one included. Once
 * {@code setbackBackoffMaxPerWindow} rungs were used (the 4th burst by default) the next burst is
 * {@link Sink#stuck stuck}: stop and pause with an alert. Not a SAFETY STOP: nothing moved the bot far, it only could
 * not get on where it stood.
 *
 * <p><b>Setbacks onto the bot's own path</b>. A teleport further
 * than {@code teleportStopDistance} whose target is a position the bot stood at (within {@link #PATH_MATCH} blocks)
 * within the last {@code setbackRecentSeconds}, at most {@code setbackRecentMaxDistance} away, is the anti-cheat
 * putting the bot back where it last accepted it: live 2b2t 2026-10-11 23:36, the server sent nothing for 3-11 s at a
 * time while it kept ticking, and each stall ended with a setback 7-27 blocks back along the walk. It is judged as a
 * setback (logged through {@link Sink#warn}, then as any small one), not a SAFETY STOP. {@code setbackRecentPath}
 * false halts on it as before. A longer one, up to {@code setbackRecentFarMaxDistance}, is a setback as well when the
 * bot could have walked that far since it stood at the target ({@code setbackRecentSpeed} blocks per second of that
 * time; live 2026-10-20: 66.0 and 118.7 blocks, 15 and 12 s). The track restarts with bot ticks and after a respawn (a
 * dimension change).
 *
 * <p><b>Repeats at one spot.</b> {@code setbackResyncRepeats} setbacks onto the same spot (within {@link #SAME_SPOT}
 * blocks; setbacks within {@link #REPEAT_MERGE_MS} of the first at a spot are one) within {@link #RESYNC_WINDOW_MS}
 * ask the sink to re-sync the blocks around it ({@link Sink#resync}).
 *
 * <p>The first {@code startGraceTicks} bot ticks after control starts are the login position sync (and the 2b2t
 * queue-to-server transfer), so teleports then only update the known position; the base-distance check runs
 * once the grace period ends.
 *
 * <p>Halt and stuck texts carry real positions written by {@link CoordFormat#point}, so the log keeps them; with
 * {@code discord.coordinates} RELATIVE, Discord shows them relative to the base (or as far from base).
 */
public final class TeleportGuard {
    /** Back-offs within this window are the rungs of the escape ladder; one burst more than allowed is stuck. */
    public static final long BACKOFF_ESCALATION_WINDOW_MS = 120_000L;
    /** A teleport target within this many blocks of a position on the bot's track counts as on its path. */
    public static final double PATH_MATCH = 1.0;
    /** The track keeps a new position once the bot moved this far from the last one kept. */
    static final double TRACK_STEP = 0.5;
    /** Setback targets within this many blocks of each other are the same spot. */
    public static final double SAME_SPOT = 1.5;
    /** Setbacks at one spot within this long of the first are one (the server re-sending a pending teleport). */
    public static final long REPEAT_MERGE_MS = 2_000L;
    /** Repeats at one spot count within this window. */
    public static final long RESYNC_WINDOW_MS = 120_000L;

    /** Receives decisions. {@code halt} must stop all bot activity immediately. */
    public interface Sink {
        void halt(String reason);

        void warn(String title, String body);

        /**
         * A burst of small setbacks: stop all bot activity now and stand still for {@code millis}, then carry on.
         * Not a pause (nothing persisted, no resume needed). Called from Netty threads: must not block. The guard calls
         * the 6-argument form; its default comes here.
         */
        void backoff(String reason, long millis);

        /**
         * {@link #backoff(String, long)} with where the burst happened (the bot's feet position as of the last setback)
         * and its rung on the escape ladder (1-based, within {@link #BACKOFF_ESCALATION_WINDOW_MS}): the engine stands
         * still, then runs the escape. Default: the plain back-off.
         */
        default void backoff(String reason, long millis, double x, double y, double z, int rung) {
            backoff(reason, millis);
        }

        /**
         * The escape ladder is used up (a burst after {@code setbackBackoffMaxPerWindow} back-offs within
         * {@link #BACKOFF_ESCALATION_WINDOW_MS}): stop and pause with an alert. Not a SAFETY STOP. Default: halt.
         */
        default void stuck(String reason) {
            halt(reason);
        }

        /**
         * Every setback the guard judged (armed: at most {@code teleportStopDistance}, or onto the bot's recent path).
         * Any thread; never blocks.
         */
        default void setback(double distance) {
        }

        /**
         * {@code safety.setbackResyncRepeats} setbacks onto the same spot (the feet position {@code x, y, z} of the last
         * one): re-sync the blocks around it with the server. Called from Netty threads: must not block. Default:
         * nothing.
         */
        default void resync(String reason, double x, double y, double z) {
        }
    }

    private final MapArtConfig.SafetyCfg cfg;
    private final Supplier<List<PlatformGeometry>> bases;
    private final LongSupplier clock;
    private final Sink sink;

    /**
     * The bot rides, boards or leaves the muncher's cart ({@link #rideMode}): the machines and the anti-cheat move it,
     * so a far teleport is a warning, never a SAFETY STOP, and counts as no setback (live 2026-11-14: a setback to
     * the start rail 75 blocks away, then the dismount beside the cart, halted the bot while its cart went on).
     */
    private volatile boolean rideMode;

    private boolean ticking;
    private long ticksSinceStart;
    private boolean baseChecked;
    private boolean posKnown;
    private double x;
    private double y;
    private double z;
    private final ArrayDeque<Long> setbacks = new ArrayDeque<>();
    private long lastSetbackAlert = -1;   // -1 = never
    /** Setbacks of the current burst window (back-off detection). */
    private final ArrayDeque<Long> burst = new ArrayDeque<>();
    /** Back-offs within {@link #BACKOFF_ESCALATION_WINDOW_MS} (escalation). */
    private final ArrayDeque<Long> backoffs = new ArrayDeque<>();
    /** Clock time the last back-off ends; setbacks before it start no new burst. */
    private long backoffEnd;
    /**
     * The bot's track: {millis it last stood there, x, y, z}, oldest first, a new entry per {@link #TRACK_STEP} moved
     * (standing still refreshes the last one). Entries older than {@code setbackRecentSeconds} are dropped.
     */
    private final ArrayDeque<double[]> track = new ArrayDeque<>();
    /** Setbacks per spot: {millis of the first, x, y, z}, oldest first, within {@link #RESYNC_WINDOW_MS}. */
    private final ArrayDeque<double[]> spots = new ArrayDeque<>();

    public TeleportGuard(MapArtConfig.SafetyCfg cfg, Supplier<List<PlatformGeometry>> bases, LongSupplier clock, Sink sink) {
        this.cfg = cfg;
        this.bases = bases;
        this.clock = clock;
        this.sink = sink;
    }

    /** Any thread: the engine is in a ride phase (RIDE_BOARD to RIDE_END) or not. */
    public void rideMode(boolean on) {
        rideMode = on;
    }

    /** Bot ticks started (login, reconnect, or a controlling player left). */
    public synchronized void botTicksStarted() {
        ticking = true;
        ticksSinceStart = 0;
        baseChecked = false;
        setbacks.clear();
        burst.clear(); // back-off history stays: it is time-windowed, and a reconnect must not reset the escalation
        track.clear(); // a new session (or the player's moves): the bot's own path starts here
        spots.clear(); // fresh chunks on a reconnect
    }

    /** Bot ticks stopped (player took control or disconnect): the player's own movement is not ours to judge. */
    public synchronized void botTicksStopped() {
        ticking = false;
    }

    /**
     * Every bot tick with the bot's feet position. A bot tick means the bot is in control, so this also arms the
     * guard when the start event was missed (builder switched on while bot ticks were already running).
     */
    public synchronized void tick(double px, double py, double pz, boolean inQueue) {
        if (!ticking) botTicksStarted();
        x = px;
        y = py;
        z = pz;
        posKnown = true;
        remember(px, py, pz, clock.getAsLong());
        ticksSinceStart++;
        if (!baseChecked && !inQueue && ticksSinceStart >= cfg.startGraceTicks) {
            baseChecked = true;
            if (cfg.teleportGuard) checkBase(px, pz);
        }
    }

    /**
     * Server teleport packet (before the client applies it). Relative components are resolved against the last
     * known position.
     */
    public synchronized void teleport(boolean relX, double px, boolean relY, double py, boolean relZ, double pz,
                                      boolean inQueue) {
        double tx = (relX ? x : 0) + px;
        double ty = (relY ? y : 0) + py;
        double tz = (relZ ? z : 0) + pz;
        boolean armed = cfg.teleportGuard && ticking && posKnown && !inQueue && ticksSinceStart >= cfg.startGraceTicks;
        if (armed && rideMode) {
            double d = Math.sqrt((tx - x) * (tx - x) + (ty - y) * (ty - y) + (tz - z) * (tz - z));
            if (d > cfg.teleportStopDistance)
                sink.warn("Teleport during the ride", "the server moved the bot " + CoordFormat.distance(d) + " to "
                    + CoordFormat.point(tx, ty, tz) + " during the ride (anti-cheat setback, lag?); no SAFETY STOP: out"
                    + " of the cart the bot gets back in (from " + CoordFormat.point(x, y, z) + ")");
        } else if (armed) {
            double d = Math.sqrt((tx - x) * (tx - x) + (ty - y) * (ty - y) + (tz - z) * (tz - z));
            long ago = d > cfg.teleportStopDistance ? onRecentPath(d, tx, ty, tz) : -1;
            if (d > cfg.teleportStopDistance && ago < 0) {
                sink.halt("server teleported the bot " + CoordFormat.distance(d) + ": " + CoordFormat.point(x, y, z)
                    + " -> " + CoordFormat.point(tx, ty, tz));
            } else {
                if (ago >= 0)
                    sink.warn("Setback onto the bot's path", String.format(Locale.ROOT,
                        "the server moved the bot %s back to %s, where it stood %.1f s ago (anti-cheat setback, lag?); "
                            + "not a SAFETY STOP, carrying on (from %s)", CoordFormat.distance(d),
                        CoordFormat.point(tx, ty, tz), ago / 1000.0, CoordFormat.point(x, y, z)));
                setback(d, tx, ty, tz);
            }
        }
        x = tx;
        y = ty;
        z = tz;
        posKnown = true;
    }

    /** Respawn packet: death respawn or dimension change. */
    public synchronized void respawn(boolean inQueue) {
        track.clear(); // maybe another dimension: no position before it is on the bot's path
        spots.clear();
        if (cfg.teleportGuard && ticking && !inQueue && ticksSinceStart >= cfg.startGraceTicks)
            sink.halt("respawn or dimension change while building");
    }

    public synchronized void death() {
        if (cfg.teleportGuard && ticking) sink.halt("the bot died");
    }

    private void checkBase(double px, double pz) {
        List<PlatformGeometry> list = bases.get();
        if (list.isEmpty()) return;
        double best = Double.MAX_VALUE;
        for (PlatformGeometry g : list) {
            double dx = g.center().x() + 0.5 - px, dz = g.center().z() + 0.5 - pz;
            best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
        }
        if (best > cfg.maxDistanceFromBase)
            sink.halt("bot is " + CoordFormat.distance(best) + " from the nearest platform (at "
                + CoordFormat.point(px, y, pz) + ")");
    }

    /** Keeps the bot's position on its track (see {@link #track}). */
    private void remember(double px, double py, double pz, long now) {
        double[] last = track.peekLast();
        if (last != null && dist(last, px, py, pz) < TRACK_STEP) last[0] = now; // still there
        else track.addLast(new double[]{now, px, py, pz});
        long keep = Math.max(0, cfg.setbackRecentSeconds) * 1000L;
        while (!track.isEmpty() && track.peekFirst()[0] < now - keep) track.pollFirst();
    }

    /**
     * A teleport of {@code d} blocks to ({@code tx}, {@code ty}, {@code tz}), more than {@code teleportStopDistance}:
     * the millis since the bot stood at the target when it lies on the bot's recent path (see the class doc), else -1.
     */
    private long onRecentPath(double d, double tx, double ty, double tz) {
        if (!cfg.setbackRecentPath || cfg.setbackRecentSeconds <= 0) return -1;
        // further than setbackRecentMaxDistance: only a rollback the bot's own walk since then can explain
        boolean far = d > cfg.setbackRecentMaxDistance;
        if (far && (cfg.setbackRecentSpeed <= 0 || d > cfg.setbackRecentFarMaxDistance)) return -1;
        long now = clock.getAsLong();
        long keep = cfg.setbackRecentSeconds * 1000L;
        long best = -1;
        for (double[] p : track) {
            if (p[0] < now - keep || dist(p, tx, ty, tz) > PATH_MATCH) continue;
            best = (long) p[0]; // the newest match wins (oldest first)
        }
        if (best < 0) return -1;
        long ago = now - best;
        // the newest match is the strictest: there a moment ago and this far now is no walk (a pearl, a /tp)
        if (far && d > cfg.setbackRecentSpeed * ago / 1000.0) return -1;
        return ago;
    }

    private static double dist(double[] p, double px, double py, double pz) {
        double dx = p[1] - px, dy = p[2] - py, dz = p[3] - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** A setback of {@code d} blocks to ({@code tx}, {@code ty}, {@code tz}): where it puts the bot's feet. */
    private void setback(double d, double tx, double ty, double tz) {
        long now = clock.getAsLong();
        sink.setback(d); // every one, also while a back-off stands still: the silent-look monitor counts them all
        alertOnFrequentSetbacks(now, d);
        backoffOnBurst(now, d, tx, ty, tz);
        resyncOnRepeats(now, tx, ty, tz);
    }

    /**
     * The {@code setbackResyncRepeats}-th setback onto the same spot within {@link #RESYNC_WINDOW_MS} (those within
     * {@link #REPEAT_MERGE_MS} of the first at a spot count once) asks for a re-sync there; the spot's count restarts.
     */
    private void resyncOnRepeats(long now, double tx, double ty, double tz) {
        int need = cfg.setbackResyncRepeats;
        if (need <= 0) return;
        while (!spots.isEmpty() && spots.peekFirst()[0] < now - RESYNC_WINDOW_MS) spots.pollFirst();
        int count = 1;
        for (double[] s : spots) {
            if (dist(s, tx, ty, tz) > SAME_SPOT) continue;
            if (now - (long) s[0] <= REPEAT_MERGE_MS) return; // the same repeat (a re-sent teleport)
            count++;
        }
        if (count < need) {
            spots.addLast(new double[]{now, tx, ty, tz});
            return;
        }
        long first = now;
        for (var it = spots.iterator(); it.hasNext(); ) {
            double[] s = it.next();
            if (dist(s, tx, ty, tz) > SAME_SPOT) continue;
            first = Math.min(first, (long) s[0]);
            it.remove();
        }
        sink.resync(String.format(Locale.ROOT, "%d server setbacks to %s within %.0f s", count,
            CoordFormat.point(tx, ty, tz), (now - first) / 1000.0), tx, ty, tz);
    }

    private void alertOnFrequentSetbacks(long now, double d) {
        if (cfg.setbackAlertCount <= 0) return;
        long window = cfg.setbackAlertWindowSeconds * 1000L;
        setbacks.addLast(now);
        while (!setbacks.isEmpty() && setbacks.peekFirst() < now - window) setbacks.pollFirst();
        if (setbacks.size() >= cfg.setbackAlertCount && (lastSetbackAlert < 0 || now - lastSetbackAlert >= window)) {
            lastSetbackAlert = now;
            sink.warn("Frequent setbacks", String.format(Locale.ROOT,
                "%d small server teleports in %ds (last %.1f blocks). Possible lag or anti-cheat flags; still building.",
                setbacks.size(), cfg.setbackAlertWindowSeconds, d));
        }
    }

    /**
     * Back-off and escape (rung 1, 2, ...) on a burst of setbacks that ended at ({@code tx}, {@code ty}, {@code tz});
     * the burst after {@code setbackBackoffMaxPerWindow} back-offs within {@link #BACKOFF_ESCALATION_WINDOW_MS} is
     * stuck.
     */
    private void backoffOnBurst(long now, double d, double tx, double ty, double tz) {
        if (cfg.setbackBurstCount <= 0 || cfg.setbackBurstWindowMillis <= 0 || cfg.setbackBackoffSeconds <= 0) return;
        if (now < backoffEnd) return; // standing still: replies to the movement sent before the stop
        burst.addLast(now);
        while (!burst.isEmpty() && burst.peekFirst() < now - cfg.setbackBurstWindowMillis) burst.pollFirst();
        if (burst.size() < cfg.setbackBurstCount) return;
        int n = burst.size();
        long span = now - burst.peekFirst();
        burst.clear();
        while (!backoffs.isEmpty() && backoffs.peekFirst() < now - BACKOFF_ESCALATION_WINDOW_MS) backoffs.pollFirst();
        if (cfg.setbackBackoffMaxPerWindow > 0 && backoffs.size() >= cfg.setbackBackoffMaxPerWindow) {
            int done = backoffs.size();
            backoffs.clear(); // the ladder starts over after the pause
            sink.stuck(String.format(Locale.ROOT,
                "repeated server setbacks: %d back-offs and escapes in %d min did not help (last at %s)",
                done, BACKOFF_ESCALATION_WINDOW_MS / 60_000L, CoordFormat.point(tx, ty, tz)));
            return;
        }
        backoffs.addLast(now);
        int rung = backoffs.size();
        long millis = cfg.setbackBackoffSeconds * 1000L;
        backoffEnd = now + millis;
        sink.backoff(String.format(Locale.ROOT, "%d server setbacks in %.1f s (last %.1f blocks)",
            n, span / 1000.0, d), millis, tx, ty, tz, rung);
    }
}
