package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.ConfiguredRestockPlanner;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.plan.RestockPlanner;
import io.github.digitalisp.mapart.plan.SerpentineBuildPlanner;
import io.github.digitalisp.mapart.testutil.LaneSim;
import io.github.digitalisp.mapart.testutil.RecordingLogger;
import io.github.digitalisp.mapart.testutil.TileRunner;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-reach greedy ({@code build.placeInReachGreedy}) in the whole engine: restock loads (the split planner, which
 * cuts lanes, and the greedy one), BUILD rounds whose lanes start without the cells an earlier lane placed early,
 * VERIFY, map and store, with lanes run by {@link io.github.digitalisp.mapart.bot.impl.LaneRun} on the simulated platform and the
 * Baritone stand-in moving the bot. The bookkeeping: every cell placed once, reported once (the tile's counters show
 * each cell once), no repair pass and no cell given up, and the restock planner sees what the world holds.
 */
class InReachGreedyEngineTest {
    private static final int LONG = 400_000;

    private static Sim sim(RecordingLogger log, TileArt art, RestockPlanner planner, boolean greedy) {
        Sim sim = new Sim();
        sim.newEngine(new SerpentineBuildPlanner(), planner, log);
        sim.config.build.placeInReachGreedy = greedy;
        Map<String, Integer> counts = new TreeMap<>();
        art.blockCounts().forEach((k, v) -> counts.merge(BlockSpec.parse(k).id(), v, Integer::sum));
        int i = 0;
        for (String id : counts.keySet()) {
            Pos chest = new Pos(-100 + 2 * (i % 10), 61, 104 + 2 * (i / 10));
            sim.bot.addContainer(chest, Map.of(id, 1728));
            i++;
        }
        LaneSim ls = sim.bot.simulateLanes(sim.config, sim.moss, 21);
        ls.baritoneMoves = true;
        ls.confirmDelay = 5;
        ls.countLag = 5;
        ls.echoes = true;
        return sim;
    }

    private static String tail(RecordingLogger log) {
        List<String> l = log.lines;
        return "\n" + String.join("\n", l.subList(Math.max(0, l.size() - 60), l.size()));
    }

    /** Builds {@code art} to a stored map; returns the simulated platform's counters. */
    private static LaneSim build(TileArt art, Supplier<RestockPlanner> planner, boolean greedy, RecordingLogger log) {
        Sim sim = sim(log, art, planner.get(), greedy);
        Job job = sim.addJob("j0020", "greedy", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        AtomicBoolean sawFullArt = new AtomicBoolean();
        AtomicBoolean blocked = new AtomicBoolean();
        assertTrue(sim.runUntil(() -> {
            if (sim.phase() == Phase.BLOCKED) blocked.set(true);
            if (sim.phase() == Phase.STORE && sim.matchesArt(sim.moss, art)) sawFullArt.set(true);
            return job.status == JobStatus.DONE || blocked.get();
        }, LONG), sim.state() + tail(log));
        assertFalse(blocked.get(), "BLOCKED: " + sim.state() + tail(log));
        assertTrue(sawFullArt.get(), "the art was complete before the map was stored" + tail(log));
        LaneSim ls = sim.bot.laneSim;
        String what = (greedy ? "greedy" : "off") + tail(log);
        assertEquals(art.nonEmptyCount(), ls.accepted, "every block placed once" + what);
        for (var e : sim.bot.placeCounts.entrySet()) assertEquals(1, e.getValue(), "placed once: " + e.getKey());
        // a partly loaded lane skips the cells its load has no items for (they wait for the next): nothing else
        assertEquals(List.of(), log.at(Level.DEBUG).stream()
            .filter(l -> l.startsWith("skipped") && !l.contains("missing item")).toList(), "no cell given up" + what);
        assertEquals(0, log.at(Level.INFO).stream().filter(l -> l.startsWith("verify pass")).count(),
            "no repair pass" + what);
        // the tile's counters count each cell once: as placed by the lane that placed it, early or not
        String summary = log.at(Level.INFO).stream().filter(l -> l.startsWith("tile summary")).findFirst().orElse("");
        assertTrue(summary.contains("placed " + art.nonEmptyCount() + " (build " + art.nonEmptyCount() + ", repair 0)"),
            summary + what);
        assertEquals(0, ls.pathBlocked, "never a full block on Baritone's way: " + ls.pathBlocks + what);
        assertEquals(0, ls.boxRejects, what);
        assertEquals(0, ls.wrongClicks, what);
        assertEquals(0, ls.farRejects, what);
        assertEquals(0, ls.pocketTicks, "walled in: " + ls.pockets);
        assertEquals(0, ls.fellOff, what);
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
        if (greedy) {
            assertTrue(ls.walkingClicks > 0, "cells placed while Baritone walked" + what);
            List<List<Lane>> loads = sim.bot.laneLoads;
            assertTrue(loads.stream().anyMatch(l -> !l.isEmpty()), "lanes got their load" + what);
        } else {
            assertEquals(0, ls.walkingClicks, what);
            assertTrue(sim.bot.laneLoads.isEmpty(), "off: the 3-argument placeLane, as before");
        }
        System.out.printf("%s: %d cells, %d engine ticks, %d clicks while walking, %d cells of later lanes%n",
            greedy ? "greedy" : "off", art.nonEmptyCount(), sim.ticks, ls.walkingClicks, ls.extrasPlaced);
        return ls;
    }

    @Test
    void theSplitPlannersCutLoadsAreBuiltCleanly() {
        TileArt art = TileRunner.noise(31, 20, 0, 70, 40, 0.45);
        for (boolean greedy : new boolean[]{false, true}) {
            build(art, () -> new ConfiguredRestockPlanner(() -> "AUTO"), greedy, new RecordingLogger());
        }
    }

    @Test
    void theGreedyPlannersLoadsAreBuiltCleanly() {
        TileArt art = TileRunner.noise(32, 10, 20, 64, 60, 0.5);
        for (boolean greedy : new boolean[]{false, true}) build(art, GreedyRestockPlanner::new, greedy, new RecordingLogger());
    }
}
