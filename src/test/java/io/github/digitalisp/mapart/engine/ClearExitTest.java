package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.bot.impl.StandSpots;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate B: after a NUKE the bot stood on the art layer and the clear button's walk started there, over the carpets and
 * plates the machines were about to sweep; "pressing clear" was logged when the step began, 2.4 s before the click.
 * Now the bot leaves the art layer before CLEAR (centre structure, else a clear standAt outside the sweeps), never
 * starts a clear press from the art layer or a sweep, and logs the press when it went out. Synthetic coordinates.
 */
class ClearExitTest {
    private static final int LONG = 60_000;

    private static Sim sim(RecordingLogger log) {
        Sim sim = new Sim();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        return sim;
    }

    /** Foreign blocks: terracotta for the nuke, red carpet for the piston clear. */
    private static void foreignBlocks(Sim sim) {
        for (int i = 0; i < 20; i++) {
            sim.world.set(sim.moss.geometry().cell(100 + i % 10, 100 + i / 10), BlockSpec.of(Sim.TERRACOTTA));
            sim.world.set(sim.moss.geometry().cell(50 + i % 10, 60 + i / 10), BlockSpec.of(Sim.RED));
        }
    }

    /** While a NUKE break task runs, the bot walks on the art layer (lane 1, row 8), as it does live. */
    private static boolean breaksOnTheArtLayer(Sim sim, Pos onLayer) {
        if (sim.phase() == Phase.NUKE && sim.bot.lastTask != null && !sim.bot.lastTask.isFinished()
            && sim.bot.lastTask.name().startsWith("break")) {
            sim.world.moveTo(onLayer);
            return true;
        }
        return false;
    }

    private static Predicate<Pos> away(Sim sim) {
        return StandSpots.awayFromClearSweeps(List.of(sim.moss.geometry(), sim.soul.geometry()));
    }

    private static boolean onArtLayer(PlatformGeometry g, FakeBot.UseIssued u) {
        Pos f = StandSpots.feetCell(u.x(), u.y(), u.z());
        return g.contains(f.x(), f.z()) && f.y() >= g.artY() - 1 && f.y() <= g.artY() + 2;
    }

    /** A walk to {@code to} after the last break task and before the first clear press. */
    private static boolean walkedAfterTheBreaksBeforeThePress(Sim sim, Pos to) {
        List<String> h = sim.bot.history;
        int use = h.indexOf("use " + Sim.MOSS_BUTTON);
        if (use < 0) return false;
        int lastBreak = -1;
        for (int i = 0; i < use; i++) if (h.get(i).startsWith("break")) lastBreak = i;
        for (int i = lastBreak + 1; i < use; i++) if (h.get(i).equals("walkTo " + to)) return lastBreak >= 0;
        return false;
    }

    private static int indexOf(RecordingLogger log, String prefix) {
        for (int i = 0; i < log.lines.size(); i++) if (log.lines.get(i).startsWith(prefix)) return i;
        return -1;
    }

    private static String tail(RecordingLogger log) {
        List<String> l = log.lines;
        return "\n" + String.join("\n", l.subList(Math.max(0, l.size() - 40), l.size()));
    }

    @Test
    void afterTheNukeTheBotWalksToTheCentreStructureBeforeTheClearIsPressed() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        int art = sim.moss.artY;
        PlatformGeometry g = sim.moss.geometry();
        sim.moss.center = new Pos(0, art + 6, 0); // the bridge over the art layer, reached by its stairs
        foreignBlocks(sim);
        AtomicInteger pressedAtLine = new AtomicInteger(-1);
        Runnable clear = sim.bot.useHandlers.get(Sim.MOSS_BUTTON);
        sim.bot.useHandlers.put(Sim.MOSS_BUTTON, () -> {
            pressedAtLine.compareAndSet(-1, log.lines.size());
            clear.run();
        });
        sim.addJob("j0300", "Exit", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 2));
        Pos onLayer = g.cell(6, 8);
        AtomicInteger breakTicks = new AtomicInteger();
        sim.start();
        assertTrue(sim.runUntil(() -> {
            if (breaksOnTheArtLayer(sim, onLayer)) breakTicks.incrementAndGet();
            return sim.phase() == Phase.BUILD;
        }, LONG), sim.state() + tail(log));

        assertTrue(breakTicks.get() > 0, "the nuke broke from the art layer");
        List<FakeBot.UseIssued> presses = sim.bot.useIssued.stream().filter(u -> u.pos().equals(Sim.MOSS_BUTTON)).toList();
        assertEquals(1, presses.size(), sim.bot.history.toString());
        for (FakeBot.UseIssued u : presses) {
            assertFalse(onArtLayer(g, u), "clear press started from the art layer: " + u);
            assertTrue(away(sim).test(StandSpots.feetCell(u.x(), u.y(), u.z())), "clear press started in a sweep: " + u);
        }
        assertTrue(walkedAfterTheBreaksBeforeThePress(sim, sim.moss.center),
            "walked to the center structure between the breaks and the press: " + sim.bot.history);
        assertTrue(indexOf(log, "INFO leaving the art layer of moss after the nuke") >= 0, tail(log));

        // the press is logged when it went out, not when the CLEAR step began
        int going = indexOf(log, "INFO clear on moss (attempt 1): going to press");
        int pressing = indexOf(log, "INFO pressing clear on moss (attempt 1): BUTTON");
        assertTrue(going >= 0 && pressing >= 0, tail(log));
        assertTrue(going < pressedAtLine.get(), "step start logged before the press" + tail(log));
        assertTrue(pressing >= pressedAtLine.get(), "\"pressing clear\" logged after the press" + tail(log));
        // the clear time: one decimal, and what it measures
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.matches(
            "platform moss clear \\d+\\.\\d s after the clear was pressed \\(art layer read all air\\)")), tail(log));
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void withTheCentreOnTheArtLayerTheBotGoesToTheButtonsStandAt() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        int art = sim.moss.artY;
        PlatformGeometry g = sim.moss.geometry();
        Pos standAt = new Pos(-1, art + 6, 0); // on the centre structure, beside the button
        sim.moss.clear.getFirst().standAt = standAt;
        foreignBlocks(sim);
        sim.addJob("j0301", "Exit", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 2));
        Pos onLayer = g.cell(6, 8);
        sim.start();
        assertTrue(sim.runUntil(() -> {
            breaksOnTheArtLayer(sim, onLayer);
            return sim.phase() == Phase.BUILD;
        }, LONG), sim.state() + tail(log));

        for (FakeBot.UseIssued u : sim.bot.useIssued) {
            if (!u.pos().equals(Sim.MOSS_BUTTON)) continue;
            assertFalse(onArtLayer(g, u), "clear press started from the art layer: " + u);
        }
        assertTrue(walkedAfterTheBreaksBeforeThePress(sim, standAt),
            "walked to the standAt between the breaks and the press: " + sim.bot.history);
    }

    @Test
    void stuckOnTheArtLayerBlocksInsteadOfPressing() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        int art = sim.moss.artY;
        PlatformGeometry g = sim.moss.geometry();
        sim.moss.center = new Pos(0, art + 6, 0);
        foreignBlocks(sim);
        sim.addJob("j0302", "Stuck", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 2));
        Pos onLayer = g.cell(6, 8);
        sim.start();
        assertTrue(sim.runUntil(() -> {
            // no way off: every walk fails (the stairs are blocked)
            if (breaksOnTheArtLayer(sim, onLayer)) sim.bot.failNext.put("walk", 100);
            return sim.phase() == Phase.BLOCKED;
        }, LONG), sim.state() + tail(log));

        assertTrue(sim.engine.status().note().contains("cannot get off the art layer of moss before the clear"),
            sim.state());
        assertEquals(0, sim.bot.count("use " + Sim.MOSS_BUTTON), "never pressed from the art layer");
    }

    @Test
    void withoutAnExitOutsideTheSweepsItWarnsOnceAndStillClears() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log); // centre on the art layer, no standAt: nothing to walk to
        PlatformGeometry g = sim.moss.geometry();
        foreignBlocks(sim);
        sim.addJob("j0303", "NoExit", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 2));
        Pos onLayer = g.cell(6, 8);
        sim.start();
        assertTrue(sim.runUntil(() -> {
            breaksOnTheArtLayer(sim, onLayer);
            return sim.phase() == Phase.BUILD;
        }, LONG), sim.state() + tail(log));
        assertEquals(1, log.at(Level.WARN).stream().filter(l -> l.startsWith("no way off the art layer of moss")).count(),
            tail(log));
        assertEquals(1, sim.bot.count("use " + Sim.MOSS_BUTTON));
    }
}
