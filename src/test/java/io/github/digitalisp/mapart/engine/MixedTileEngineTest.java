package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.plan.SerpentineBuildPlanner;
import io.github.digitalisp.mapart.testutil.LaneSim;
import io.github.digitalisp.mapart.testutil.RecordingLogger;
import io.github.digitalisp.mapart.testutil.TileRunner;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole engine (restock, BUILD, cells left to VERIFY, repair passes, map, store, nuke, clear) with lanes run by
 * {@link io.github.digitalisp.mapart.bot.impl.LaneRun} on a simulated platform with collision and line of sight. Live bug
 * (10x test, 2026-10-11): the 28×18 art of 2-wide columns of full blocks among thin blocks went BLOCKED after 4
 * repair passes with 32 cells never placed ("not reachable from the lane").
 */
class MixedTileEngineTest {
    private static final int LONG = 200_000;

    private static Sim sim(RecordingLogger log, TileArt art, double missRate) {
        Sim sim = new Sim();
        sim.newEngine(new SerpentineBuildPlanner(), new GreedyRestockPlanner(), log);
        // one material chest per block of the art, in the material region
        Map<String, Integer> counts = new TreeMap<>();
        art.blockCounts().forEach((k, v) -> counts.merge(BlockSpec.parse(k).id(), v, Integer::sum));
        int i = 0;
        for (String id : counts.keySet()) {
            Pos chest = new Pos(-100 + 2 * (i % 10), 61, 104 + 2 * (i / 10));
            sim.bot.addContainer(chest, Map.of(id, 1728));
            i++;
        }
        LaneSim ls = sim.bot.simulateLanes(sim.config, sim.moss, 21);
        ls.missRate = missRate;
        return sim;
    }

    private static String tail(RecordingLogger log) {
        List<String> l = log.lines;
        return "\n" + String.join("\n", l.subList(Math.max(0, l.size() - 80), l.size()));
    }

    private static void assertBuiltAndStored(Sim sim, RecordingLogger log, TileArt art, Job job, int maxRepairPasses) {
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
        assertEquals(art.nonEmptyCount(), ls.accepted, "every block was placed by the simulated lanes");
        List<String> unreachable = log.at(Level.DEBUG).stream().filter(l -> l.contains("not reachable")).toList();
        assertEquals(List.of(), unreachable, "cells given up as not reachable" + tail(log));
        long passes = log.at(Level.INFO).stream().filter(l -> l.startsWith("verify pass")).count();
        assertTrue(passes <= maxRepairPasses, "verify passes: " + passes + tail(log));
        assertEquals(0, ls.pocketTicks, "walled in: " + ls.pockets);
        assertEquals(0, ls.boxRejects, "placement into the bot's own box");
        assertEquals(0, ls.wrongClicks, "click without a target or with the wrong item");
        assertEquals(0, ls.fellOff, "walked off the platform");
        assertEquals(0, sim.machineBroken, "every full block was nuked before the clear");
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void liveTestArtIsBuiltVerifiedAndStored() {
        RecordingLogger log = new RecordingLogger();
        TileArt art = TileRunner.liveStripes();
        Sim sim = sim(log, art, 0);
        Job job = sim.addJob("j0010", "complex_small", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertBuiltAndStored(sim, log, art, job, 0);
        for (var e : sim.bot.placeCounts.entrySet()) assertEquals(1, e.getValue(), "placed once: " + e.getKey());
    }

    @Test
    void droppedPlacementsAreRepairedAmongFullBlocks() {
        RecordingLogger log = new RecordingLogger();
        TileArt art = TileRunner.noise(31, 20, 0, 70, 40, 0.45);
        Sim sim = sim(log, art, 0.03);
        Job job = sim.addJob("j0011", "noise", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertBuiltAndStored(sim, log, art, job, 2);
    }
}
