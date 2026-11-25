package io.github.digitalisp.mapart.engine;

import io.netty.channel.DefaultEventLoop;
import io.netty.util.concurrent.EventExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.bot.BotTask;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Safety actions run on the client tick thread: no deadlock between a tick that holds the engine lock while it waits
 * for a send on the channel thread and a guard decision made on that channel thread.
 */
class TickThreadTest {
    private static final long WAIT_S = 10;

    private static <T> T onLoop(EventExecutor loop, Callable<T> c) throws Exception {
        return loop.submit(c).get(WAIT_S, TimeUnit.SECONDS);
    }

    private static void close(DefaultEventLoop loop, ExecutorService... others) {
        loop.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        for (ExecutorService e : others) e.shutdownNow();
    }

    // ---------------------------------------------------------------- TickThread.run

    @Test
    @Timeout(30)
    void inlineWithoutALoopOrOnTheLoop() throws Exception {
        List<Thread> ran = new ArrayList<>();
        TickThread.run(null, r -> { throw new AssertionError("no fallback"); }, () -> ran.add(Thread.currentThread()));
        assertEquals(List.of(Thread.currentThread()), ran, "no client: run in the call");

        DefaultEventLoop loop = new DefaultEventLoop();
        try {
            boolean inline = onLoop(loop, () -> {
                AtomicBoolean done = new AtomicBoolean();
                TickThread.run(loop, r -> { throw new AssertionError("no fallback"); }, () -> done.set(true));
                return done.get();
            });
            assertTrue(inline, "on the loop: run in the call");
        } finally {
            close(loop);
        }
    }

    @Test
    @Timeout(30)
    void fromAnotherThreadQueuedAfterTheRunningTask() throws Exception {
        DefaultEventLoop loop = new DefaultEventLoop();
        try {
            Thread loopThread = onLoop(loop, Thread::currentThread);
            CountDownLatch tickRunning = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            List<String> order = Collections.synchronizedList(new ArrayList<>());
            var tick = loop.submit(() -> {
                tickRunning.countDown();
                release.await(WAIT_S, TimeUnit.SECONDS);
                order.add("tick");
                return null;
            });
            assertTrue(tickRunning.await(WAIT_S, TimeUnit.SECONDS));
            AtomicReference<Thread> ranOn = new AtomicReference<>();
            TickThread.run(loop, r -> { throw new AssertionError("no fallback"); }, () -> {
                ranOn.set(Thread.currentThread());
                order.add("action");
            });
            assertTrue(order.isEmpty(), "queued, not run in the call");
            release.countDown();
            tick.get(WAIT_S, TimeUnit.SECONDS);
            onLoop(loop, () -> null);
            assertEquals(List.of("tick", "action"), order);
            assertSame(loopThread, ranOn.get());
        } finally {
            close(loop);
        }
    }

    @Test
    @Timeout(30)
    void shuttingDownLoopFallsBackOffTheCallingThread() throws Exception {
        DefaultEventLoop loop = new DefaultEventLoop();
        ExecutorService fallback = Executors.newSingleThreadExecutor();
        try {
            onLoop(loop, () -> null);
            loop.shutdownGracefully(0, 0, TimeUnit.SECONDS).await(WAIT_S, TimeUnit.SECONDS);
            Thread fallbackThread = fallback.submit(Thread::currentThread).get(WAIT_S, TimeUnit.SECONDS);
            CountDownLatch ran = new CountDownLatch(1);
            AtomicReference<Thread> ranOn = new AtomicReference<>();
            TickThread.run(loop, fallback, () -> {
                ranOn.set(Thread.currentThread());
                ran.countDown();
            });
            assertTrue(ran.await(WAIT_S, TimeUnit.SECONDS), "the halt is not lost on a disconnect");
            assertSame(fallbackThread, ranOn.get(), "never inline on the calling (Netty) thread");
        } finally {
            close(loop, fallback);
        }
    }

    // ---------------------------------------------------------------- guard decisions vs the engine lock

    /** Guard calls that reach the engine. Teleport/respawn come on the channel thread, death on the async event bus. */
    enum Source {
        TELEPORT(g -> g.teleport(true, 20, true, 0, true, 0, false)),
        RESPAWN(g -> g.respawn(false)),
        DEATH(TeleportGuard::death),
        SETBACK_BURST(g -> {
            for (int i = 0; i < 5; i++) g.teleport(true, 0.5, true, 0, true, 0, false);
        });

        final Consumer<TeleportGuard> fire;

        Source(Consumer<TeleportGuard> fire) {
            this.fire = fire;
        }
    }

    @ParameterizedTest
    @EnumSource(Source.class)
    @Timeout(60)
    void guardOnTheChannelThreadWhileATickWaitsForItDoesNotDeadlock(Source source) throws Exception {
        DefaultEventLoop tickLoop = new DefaultEventLoop();          // ZenithProxy's client event loop
        ExecutorService channel = Executors.newSingleThreadExecutor(); // the Netty channel thread
        ExecutorService fallback = Executors.newSingleThreadExecutor();
        try {
            Thread loopThread = onLoop(tickLoop, Thread::currentThread);
            Sim sim = new Sim();
            sim.bot.placementsPerTick = 8;
            TileArt art = Sim.stripes(24, 0);
            Job job = sim.addJob("j0060", "Locked", 1, 1, List.of("moss"), (x, z) -> art);
            List<Thread> hookThreads = Collections.synchronizedList(new ArrayList<>());
            // the adapter's wiring (MapArtModule)
            sim.engine.setHaltHook(TickThread.hook(() -> tickLoop, fallback, () -> hookThreads.add(Thread.currentThread())));
            TeleportGuard guard = new TeleportGuard(sim.config.safety, List::of, sim.clock::millis,
                TickThread.guardSink(sim.engine, () -> tickLoop, fallback, sim.notifier::info));
            Callable<Void> tick = () -> {   // the module's ClientBotTick handler
                guard.tick(sim.world.x, sim.world.y, sim.world.z, false);
                sim.tick();
                return null;
            };
            onLoop(tickLoop, () -> {
                sim.start();
                guard.botTicksStarted();
                for (int i = 0; i <= sim.config.safety.startGraceTicks; i++)
                    guard.tick(sim.world.x, sim.world.y, sim.world.z, false);
                return null;
            });
            for (int i = 0; i < 60_000 && !onLoop(tickLoop, () -> sim.bot.placed > 300 && sim.phase() == Phase.BUILD
                && sim.bot.lastTask != null && !sim.bot.lastTask.isFinished()); i++) {
                onLoop(tickLoop, tick);
            }

            // inside a lane tick (engine lock held): a packet reaches the guard on the channel thread, then the tick
            // waits for a send on that thread, as Clicker.selectNow's sendAwait does
            AtomicBoolean armed = new AtomicBoolean(true);
            AtomicBoolean sendTimedOut = new AtomicBoolean();
            CountDownLatch guardReturned = new CountDownLatch(1);
            AtomicReference<BotTask> running = new AtomicReference<>();
            sim.bot.unplaceable = p -> {
                if (!armed.getAndSet(false)) return null;
                running.set(sim.bot.lastTask);
                channel.execute(() -> {
                    source.fire.accept(guard);
                    guardReturned.countDown();
                });
                try {
                    channel.submit(() -> { }).get(3, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    sendTimedOut.set(true);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return null;
            };
            for (int i = 0; i < 200 && armed.get(); i++) onLoop(tickLoop, tick);
            assertNotNull(running.get(), "no lane tick reached the hook");
            assertTrue(guardReturned.await(WAIT_S, TimeUnit.SECONDS), "the guard call returned");
            assertFalse(sendTimedOut.get(), "deadlock: the channel thread waited for the engine lock held by the tick");
            int placed = onLoop(tickLoop, () -> sim.bot.placed);

            if (source == Source.SETBACK_BURST) {
                onLoop(tickLoop, tick); // the back-off is applied as the next tick starts
                assertTrue(running.get().isFinished(), "task cancelled on the next tick");
                assertEquals(BuildEngine.BACKOFF_NOTE, onLoop(tickLoop, () -> sim.engine.status().note()));
                assertFalse(onLoop(tickLoop, sim.engine::safetyLocked));
            } else {
                onLoop(tickLoop, () -> null); // queued behind the locked tick: applied before the next one
                assertTrue(onLoop(tickLoop, sim.engine::safetyLocked), sim.state());
                assertTrue(running.get().isFinished(), "task cancelled before the next tick");
                assertTrue(onLoop(tickLoop, () -> sim.config.paused));
                assertTrue(onLoop(tickLoop, () -> sim.notifier.has("alert", "SAFETY STOP")), sim.notifier.toString());
                onLoop(tickLoop, tick);
            }
            for (int i = 0; i < 20; i++) onLoop(tickLoop, tick);
            assertEquals(placed, onLoop(tickLoop, () -> sim.bot.placed), "nothing placed after the stop");
            assertFalse(hookThreads.isEmpty(), "Baritone stop ran");
            for (Thread t : hookThreads) assertSame(loopThread, t, "Baritone is only stopped on the tick thread");

            // and it carries on (resume after a safety stop; on its own after a back-off)
            onLoop(tickLoop, () -> {
                sim.engine.resume();
                return null;
            });
            for (int i = 0; i < 60_000 && onLoop(tickLoop, () -> job.status != JobStatus.DONE); i++) onLoop(tickLoop, tick);
            assertEquals(JobStatus.DONE, onLoop(tickLoop, () -> job.status), sim.state());
            assertEquals(source == Source.SETBACK_BURST ? 1 : 0, onLoop(tickLoop, () -> sim.bot.escapes.size()),
                "an escape after the back-off, none after a SAFETY STOP");
        } finally {
            close(tickLoop, channel, fallback);
        }
    }

    @Test
    @Timeout(30)
    void guardSinkForwardsEscapeBackoffStuckAndSetbacksOnTheRightThreads() throws Exception {
        DefaultEventLoop tickLoop = new DefaultEventLoop();          // ZenithProxy's client event loop
        ExecutorService channel = Executors.newSingleThreadExecutor(); // the Netty channel thread
        ExecutorService fallback = Executors.newSingleThreadExecutor();
        try {
            Thread loopThread = onLoop(tickLoop, Thread::currentThread);
            Thread channelThread = channel.submit(Thread::currentThread).get(WAIT_S, TimeUnit.SECONDS);
            RecordingLogger log = new RecordingLogger();
            Sim sim = new Sim();
            sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
            // the halt hook runs inside the engine call: it shows the thread the guard's decision reached the engine on
            List<Thread> engineCalls = Collections.synchronizedList(new ArrayList<>());
            sim.engine.setHaltHook(() -> engineCalls.add(Thread.currentThread()));
            List<Thread> setbackThreads = Collections.synchronizedList(new ArrayList<>());
            List<Double> setbacks = Collections.synchronizedList(new ArrayList<>());
            TeleportGuard.Sink sink = TickThread.guardSink(sim.engine, () -> tickLoop, fallback, sim.notifier::info, d -> {
                setbackThreads.add(Thread.currentThread());
                setbacks.add(d);
            });
            Callable<Void> tick = () -> {
                sim.tick();
                return null;
            };

            channel.submit(() -> sink.setback(0.25)).get(WAIT_S, TimeUnit.SECONDS);
            assertEquals(List.of(channelThread), setbackThreads, "the setback note runs inline on the calling thread");
            assertEquals(List.of(0.25), setbacks);
            assertTrue(engineCalls.isEmpty(), "a setback alone never reaches the engine");

            // feet on soul sand (y 63.875) are in cell y 64: ZenithProxy's y + 0.1251
            channel.submit(() -> sink.backoff("5 server setbacks in 0.4 s (last 0.0 blocks)", 5_000, 10.7, 63.875, -3.2, 2))
                .get(WAIT_S, TimeUnit.SECONDS);
            onLoop(tickLoop, () -> null);
            assertEquals(List.of(loopThread), engineCalls, "the back-off reached the engine on the tick thread");
            onLoop(tickLoop, tick); // applied as the next tick starts
            assertTrue(log.at(Level.WARN).stream().anyMatch(l -> l.startsWith("backing off: standing still for 5 s")
                && l.endsWith("; then escape rung 2 of 3 from 10,64,-4")), log.lines.toString());

            channel.submit(() -> sink.stuck("repeated server setbacks: test")).get(WAIT_S, TimeUnit.SECONDS);
            onLoop(tickLoop, () -> null);
            assertEquals(List.of(loopThread, loopThread), engineCalls, "stuck reached the engine on the tick thread");
            assertTrue(onLoop(tickLoop, () -> sim.config.paused), "the pause is persisted in the call");
            onLoop(tickLoop, tick);
            assertTrue(onLoop(tickLoop, () -> sim.notifier.has("alert", "Stuck: paused")), sim.notifier.toString());
            assertFalse(onLoop(tickLoop, sim.engine::safetyLocked), "not a SAFETY STOP");
            assertTrue(sim.engine.status().pauseReason().startsWith("stuck: repeated server setbacks: test"),
                sim.engine.status().pauseReason());
        } finally {
            close(tickLoop, channel, fallback);
        }
    }

    @Test
    @Timeout(30)
    void haltFromAnotherThreadStopsBaritoneOnTheTickThread() throws Exception {
        // e.g. a Discord kill switch calling emergencyStop on a JDA thread: the halt hook still runs on the loop
        DefaultEventLoop tickLoop = new DefaultEventLoop();
        ExecutorService fallback = Executors.newSingleThreadExecutor();
        try {
            Thread loopThread = onLoop(tickLoop, Thread::currentThread);
            Sim sim = new Sim();
            List<Thread> hookThreads = Collections.synchronizedList(new ArrayList<>());
            sim.engine.setHaltHook(TickThread.hook(() -> tickLoop, fallback, () -> hookThreads.add(Thread.currentThread())));
            sim.engine.emergencyStop("test");
            onLoop(tickLoop, () -> null);
            assertEquals(List.of(loopThread), hookThreads);
        } finally {
            close(tickLoop, fallback);
        }
    }
}
