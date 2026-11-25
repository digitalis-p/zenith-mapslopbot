package io.github.digitalisp.mapart.engine;

import io.netty.channel.DefaultEventLoop;
import io.netty.util.concurrent.EventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.helpers.NOPLogger;
import io.github.digitalisp.mapart.bot.BotTask;
import io.github.digitalisp.mapart.command.CommandLine;
import io.github.digitalisp.mapart.command.QueueOps;
import io.github.digitalisp.mapart.command.Reply;
import io.github.digitalisp.mapart.discord.DiscordCommands;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code !map stop} kill switch end to end: Discord command → QueueOps → the real engine, wired as in production
 * (emergencyStop and the Baritone halt hook on the client tick loop, {@link TickThread}). The test thread plays the
 * JDA thread; the engine is ticked only on the loop.
 */
class ManualStopTest {
    private static final long WAIT_S = 10;
    private static final int LONG = 60_000;

    DefaultEventLoop tickLoop;
    ExecutorService fallback;
    Thread loopThread;
    Sim sim;
    Job job;
    List<Thread> hookThreads;
    List<String> disconnects;
    DiscordCommands discord;

    private <T> T onLoop(Callable<T> c) throws Exception {
        return tickLoop.submit(c).get(WAIT_S, TimeUnit.SECONDS);
    }

    private void tick() throws Exception {
        onLoop(() -> {
            sim.tick();
            return null;
        });
    }

    private Reply say(String text) {
        return discord.handle(CommandLine.parse(text, "!map").orElseThrow(), "!map", "jared2013");
    }

    @BeforeEach
    void setUp() throws Exception {
        tickLoop = new DefaultEventLoop();
        fallback = Executors.newSingleThreadExecutor();
        loopThread = tickLoop.submit(Thread::currentThread).get(WAIT_S, TimeUnit.SECONDS);
        sim = new Sim();
        TileArt art = Sim.stripes(24, 0);
        job = sim.addJob("j0070", "Kill switch", 1, 1, List.of("moss"), (x, z) -> art);
        hookThreads = Collections.synchronizedList(new ArrayList<>());
        disconnects = Collections.synchronizedList(new ArrayList<>());
        // as MapArtModule / MapArtPlugin wire it
        sim.engine.setHaltHook(TickThread.hook(() -> tickLoop, fallback, () -> hookThreads.add(Thread.currentThread())));
        QueueOps ops = new QueueOps(sim.store, () -> sim.engine, sim.clock::millis, () -> {
            disconnects.add(Thread.currentThread().getName());
            return "Disconnected from the server. No auto-reconnect.";
        }, r -> TickThread.run(tickLoop, fallback, r));
        discord = new DiscordCommands(ops, CoordFormat::real, NOPLogger.NOP_LOGGER);

        onLoop(() -> {
            sim.start();
            return null;
        });
        for (int i = 0; i < LONG && !onLoop(() -> sim.bot.placed > 300 && sim.phase() == Phase.BUILD
            && sim.bot.lastTask != null && !sim.bot.lastTask.isFinished()); i++) tick();
        assertTrue(onLoop(() -> sim.phase() == Phase.BUILD && !sim.bot.lastTask.isFinished()), sim.state());
    }

    @AfterEach
    void tearDown() {
        tickLoop.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        fallback.shutdownNow();
    }

    @Test
    @Timeout(60)
    void stopHaltsWithinTheCallWithoutABotTick() throws Exception {
        BotTask running = onLoop(() -> sim.bot.lastTask);
        long ticks = onLoop(() -> sim.ticks);

        Reply r = say("!map stop"); // no bot tick from here on: ticks stopped or not, nothing else drives the engine

        assertEquals("STOPPED", r.title(), r.body());
        assertTrue(r.body().startsWith("Bot halted, Baritone stopped, pause saved. `!map resume` to continue."));
        assertEquals(ticks, onLoop(() -> sim.ticks), "no tick ran");
        assertTrue(running.isFinished(), "task cancelled before the reply");
        assertEquals(List.of(loopThread), hookThreads, "Baritone halted once, on the tick loop");
        assertTrue(sim.config.paused, "pause persisted");
        assertEquals("SAFETY STOP: manual stop by jared2013 (Discord)", sim.config.pauseReason);
        assertTrue(sim.engine.safetyLocked());
        assertTrue(sim.notifier.has("alert", "SAFETY STOP"), sim.notifier.toString());
        assertTrue(disconnects.isEmpty(), "plain stop never disconnects");

        // stays stopped while ticking; resume continues the job
        int placed = onLoop(() -> sim.bot.placed);
        int tasks = onLoop(() -> sim.bot.history.size());
        for (int i = 0; i < 500; i++) tick();
        assertEquals(placed, (int) onLoop(() -> sim.bot.placed), "nothing placed while stopped");
        assertEquals(tasks, (int) onLoop(() -> sim.bot.history.size()), "no task started while stopped");
        assertEquals("Resuming", say("!map resume").title());
        for (int i = 0; i < LONG && onLoop(() -> job.status != JobStatus.DONE); i++) tick();
        assertEquals(JobStatus.DONE, onLoop(() -> job.status), sim.state());
    }

    @Test
    @Timeout(60)
    void stopWhileAPlayerControlsStaysStoppedWhenTheyLeave() throws Exception {
        BotTask running = onLoop(() -> sim.bot.lastTask);
        onLoop(() -> {
            sim.engine.onBotTickStopped(); // a player logs in: ClientBotTick.Stopped on the loop
            return null;
        });
        assertTrue(running.isFinished(), "takeover cancels the task");
        int placed = onLoop(() -> sim.bot.placed);

        Reply r = say("!map kill");
        assertEquals("STOPPED", r.title(), r.body());
        assertTrue(sim.config.paused);
        assertEquals(List.of(loopThread), hookThreads);

        onLoop(() -> {
            sim.engine.onBotTickStarting(); // the player leaves
            return null;
        });
        for (int i = 0; i < 500; i++) tick();
        assertEquals(placed, (int) onLoop(() -> sim.bot.placed), "nothing placed after the player left");
        assertTrue(onLoop(() -> sim.engine.status().paused()));
    }

    @Test
    @Timeout(60)
    void stopDisconnectDisconnectsAfterTheHalt() throws Exception {
        BotTask running = onLoop(() -> sim.bot.lastTask);
        Reply r = say("!map stop disconnect");
        assertEquals("STOPPED", r.title(), r.body());
        assertTrue(running.isFinished());
        assertTrue(sim.config.paused);
        assertEquals(1, disconnects.size(), "disconnected once");
        assertFalse(disconnects.getFirst().equals(loopThread.getName()), "not on the tick loop");
        assertTrue(r.body().contains("Disconnected from the server. No auto-reconnect."), r.body());
    }

    @Test
    @Timeout(60)
    void pauseActsOnTheNextTickStopInTheCall() throws Exception {
        BotTask running = onLoop(() -> sim.bot.lastTask);
        Reply p = say("!map pause");
        assertTrue(p.body().contains("typically within one tick"), p.body());
        assertFalse(running.isFinished(), "pause waits for the next tick");
        tick();
        assertTrue(running.isFinished(), "and acts on it");
        assertTrue(hookThreads.isEmpty(), "pause does not run the halt hook");
        assertFalse(sim.engine.safetyLocked(), "pause is not a stop");
    }
}
