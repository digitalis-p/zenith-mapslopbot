package io.github.digitalisp.mapart.discord;

import net.dv8tion.jda.api.OnlineStatus;
import org.slf4j.Logger;
import io.github.digitalisp.mapart.config.MapArtConfig;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Keeps the bot's Discord presence in line with {@link PresenceText}. {@link #tick()} runs every
 * {@value #POLL_MS} ms on a scheduler and sends only when the line or the online status changed, and at most
 * once per {@code discord.presenceIntervalSeconds} (never below {@value #MIN_INTERVAL_SECONDS} s). A change
 * inside that window (a new step, a new %) goes out at the first poll after it; states in between are
 * skipped. Rate: Discord allows 120 gateway commands per 60 s per connection (JDA keeps itself to 115), so 4
 * presence updates per minute leave plenty of room.
 *
 * <p>Nothing is sent while the client is not connected. A new client (after a reload) gets the current line
 * right away; JDA itself re-sends the last presence when it reconnects. With {@code discord.presence} off, a
 * custom status set earlier is cleared once. Never throws into the scheduler, JDA or the engine; it only
 * schedules one task on the given scheduler, so stop/reload cannot leak threads.
 */
public final class PresenceUpdater {
    /** Presence transport; implemented over JDA by {@link IntakeBot#presence()} and by fakes in tests. */
    public interface Target {
        /** Identifies the connected client (compared by identity), or null while not connected. */
        Object connection();

        /** Sets status and custom status ({@code text} null clears it). Must not block. */
        void apply(OnlineStatus status, String text);
    }

    static final long POLL_MS = 5_000;
    static final int MIN_INTERVAL_SECONDS = 15;
    private static final PresenceText.Presence CLEARED = new PresenceText.Presence(OnlineStatus.ONLINE, null);

    private final Target target;
    private final Supplier<PresenceText.Input> source;
    private final MapArtConfig.DiscordCfg cfg;
    private final Logger log;
    private final LongSupplier clock;

    // guarded by this
    private Object session;
    /** What this client shows because of us; null = nothing set yet (or cleared). */
    private PresenceText.Presence shown;
    private boolean sentThisSession;
    private long sentAt;
    private String lastProblem;
    private ScheduledFuture<?> task;

    public PresenceUpdater(Target target, Supplier<PresenceText.Input> source, MapArtConfig.DiscordCfg cfg,
                           Logger log, LongSupplier clock) {
        this.target = target;
        this.source = source;
        this.cfg = cfg;
        this.log = log;
        this.clock = clock;
    }

    /** Polls every {@value #POLL_MS} ms on {@code scheduler}. Idempotent. */
    public synchronized void start(ScheduledExecutorService scheduler) {
        if (task != null && !task.isDone()) return;
        task = scheduler.scheduleWithFixedDelay(this::safeTick, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
    }

    /** Stops polling. The presence already set stays until the client disconnects. */
    public synchronized void stop() {
        if (task != null) task.cancel(false);
        task = null;
    }

    /** Minimum time between two updates, from the config (at least {@value #MIN_INTERVAL_SECONDS} s). */
    static long intervalMs(MapArtConfig.DiscordCfg cfg) {
        return Math.max(MIN_INTERVAL_SECONDS, cfg.presenceIntervalSeconds) * 1000L;
    }

    private void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            problem("Map art Discord presence update failed: " + t);
        }
    }

    /** One poll: compute the wanted presence and send it if it changed and the rate limit allows. */
    public synchronized void tick() {
        Object conn;
        try {
            conn = target.connection();
        } catch (RuntimeException e) {
            problem("Map art Discord presence: client check failed: " + e);
            return;
        }
        if (conn == null) return;
        if (conn != session) {
            session = conn;
            shown = null;
            sentThisSession = false;
        }

        PresenceText.Presence want;
        if (!cfg.presence) {
            if (shown == null) return;
            want = CLEARED;
        } else {
            try {
                want = PresenceText.describe(source.get());
            } catch (RuntimeException e) {
                problem("Map art Discord presence: status read failed: " + e);
                return;
            }
        }
        if (Objects.equals(want, shown)) {
            lastProblem = null; // all good, nothing to send
            return;
        }
        long now = clock.getAsLong();
        if (sentThisSession && now - sentAt < intervalMs(cfg)) return; // next poll after the window

        sentThisSession = true;
        sentAt = now;
        try {
            target.apply(want.status(), want.text());
            shown = want == CLEARED ? null : want;
            lastProblem = null;
            log.debug("Map art Discord presence: {} {}", want.status(), want.text());
        } catch (RuntimeException e) {
            problem("Map art Discord presence update failed: " + e);
        }
    }

    /** Logs a failure once until something works again (polls every few seconds). */
    private void problem(String message) {
        synchronized (this) {
            if (message.equals(lastProblem)) return;
            lastProblem = message;
        }
        log.warn(message);
    }
}
