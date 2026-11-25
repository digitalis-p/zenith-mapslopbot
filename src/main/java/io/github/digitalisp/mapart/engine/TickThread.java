package io.github.digitalisp.mapart.engine;

import io.netty.util.concurrent.EventExecutor;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/**
 * Runs safety actions on the client tick thread (ZenithProxy's client event loop), so no other thread takes the
 * engine lock or touches Baritone for them.
 *
 * <p>Why: the teleport guard is called on the Netty channel thread (teleport and respawn packets) and on the async
 * event bus (ClientDeathEvent). A tick holds the engine lock and can wait for a send on the channel thread
 * ({@code sendAwait} of the hotbar select), so a channel thread waiting for the engine lock deadlocks both, and an
 * event-bus thread doing so while holding the guard lock blocks the channel thread's next guard call. Baritone is
 * not thread-safe: {@code Baritone.stop()} clears its process list and completes path futures without locks, racing
 * its own tick on the event loop.
 *
 * <p>The loop runs its tasks in order and each client tick is one task, so a queued action runs before the next tick
 * (after the current one, if a tick is running) - no later than a synchronized call would get the lock.
 */
public final class TickThread {
    private TickThread() { }

    /**
     * Runs {@code r} on {@code loop}: inline when there is no loop (no client: no ticks, no channel) or the caller is on
     * it, else queued. A loop that is shutting down (disconnect) may never run a new task, so {@code fallback} runs it
     * then: never inline on the calling thread, unlike ZenithProxy's {@code executeInEventLoop}.
     */
    public static void run(@Nullable EventExecutor loop, Executor fallback, Runnable r) {
        if (loop == null || loop.inEventLoop()) {
            r.run();
            return;
        }
        if (!loop.isShuttingDown()) {
            try {
                loop.execute(r);
                return;
            } catch (RejectedExecutionException e) {
                // shut down meanwhile
            }
        }
        fallback.execute(r);
    }

    /** {@code stop} (e.g. Baritone's) as a halt hook that always runs on the tick thread. */
    public static Runnable hook(Supplier<@Nullable EventExecutor> loop, Executor fallback, Runnable stop) {
        return () -> run(loop.get(), fallback, stop);
    }

    /** {@link #guardSink(BuildEngine, Supplier, Executor, BiConsumer, DoubleConsumer)} without a setback callback. */
    public static TeleportGuard.Sink guardSink(BuildEngine engine, Supplier<@Nullable EventExecutor> loop,
                                               Executor fallback, BiConsumer<String, String> info) {
        return guardSink(engine, loop, fallback, info, d -> { });
    }

    /**
     * The teleport guard's decisions applied to the engine on the tick thread: {@code halt} is
     * {@link BuildEngine#emergencyStop} (its config saver persists the pause), {@code backoff} is
     * {@link BuildEngine#backoff(String, long, Pos, int)} with the burst's feet cell (ZenithProxy's convention:
     * {@code y + 0.1251}, for soul sand), {@code stuck} is {@link BuildEngine#stuck}, {@code resync} is {@link
     * BuildEngine#resync} with the spot's feet cell ("Setbacks onto the bot's path").
     * Warnings (also a setback onto the bot's path) only post a line through {@code info} (non-blocking) on the calling
     * thread; the plugin passes the notifier's {@code detail}, which reaches Discord with {@code discord.verbose} only.
     * Every setback goes to {@code setback} inline on the calling (Netty) thread, inside the guard's lock: it must never
     * block or take the engine lock.
     */
    public static TeleportGuard.Sink guardSink(BuildEngine engine, Supplier<@Nullable EventExecutor> loop,
                                               Executor fallback, BiConsumer<String, String> info,
                                               DoubleConsumer setback) {
        return new TeleportGuard.Sink() {
            @Override
            public void halt(String reason) {
                run(loop.get(), fallback, () -> engine.emergencyStop(reason));
            }

            @Override
            public void warn(String title, String body) {
                info.accept(title, body);
            }

            @Override
            public void backoff(String reason, long millis) {
                run(loop.get(), fallback, () -> engine.backoff(reason, millis));
            }

            @Override
            public void backoff(String reason, long millis, double x, double y, double z, int rung) {
                Pos feet = new Pos((int) Math.floor(x), (int) Math.floor(y + 0.1251), (int) Math.floor(z));
                run(loop.get(), fallback, () -> engine.backoff(reason, millis, feet, rung));
            }

            @Override
            public void stuck(String reason) {
                run(loop.get(), fallback, () -> engine.stuck(reason));
            }

            @Override
            public void setback(double distance) {
                setback.accept(distance);
            }

            @Override
            public void resync(String reason, double x, double y, double z) {
                Pos feet = new Pos((int) Math.floor(x), (int) Math.floor(y + 0.1251), (int) Math.floor(z));
                run(loop.get(), fallback, () -> engine.resync(reason, feet));
            }
        };
    }
}
