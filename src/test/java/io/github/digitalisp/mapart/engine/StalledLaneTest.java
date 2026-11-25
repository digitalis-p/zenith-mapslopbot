package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.plan.SerpentineBuildPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lane cell the lane task skips although its item is in the inventory (live: "not reachable from the lane" for a
 * cell beside the corridor on the tile's edge row). Before the fix the engine ping-ponged BUILD↔RESTOCK with
 * "load covers 1, withdraw {}" and "0 placed, load used up", then forced a full rescan of every material container
 * ("scanning N material containers (requested)", ~15 min at 1x) and finally went BLOCKED as "materials cannot be
 * obtained". Now such a cell is left to VERIFY: BUILD carries on with the other lanes and no rescan is forced.
 *
 * <p>Production planners (trimmed, clamped lanes; slot-budget restock) and a 2-slot material budget keep the loads
 * small, so the stalled cell's lane comes up again in many restock rounds. (Before the restock capacity fix the held
 * item also crowded out the next lane's load: the live "load covers 1, withdraw {}" round.)
 */
class StalledLaneTest {
    private static final int LONG = 120_000;
    private static final int ROWS = 13; // 65 cells per lane: the next lane never fits beside a held item

    private static Sim sim(RecordingLogger log) {
        Sim sim = new Sim();
        sim.config.build.reservedSlots = 34; // 36 slots - pickaxe - carrots - 32 reserved = 2 slots for materials
        sim.newEngine(new SerpentineBuildPlanner(), new GreedyRestockPlanner(), log);
        return sim;
    }

    /** Lane 0 (columns 0-4, walked along column 2): the cell beside the corridor on the north edge row. */
    private static Pos victim(Sim sim) {
        return sim.moss.geometry().cell(1, 0);
    }

    private static long requestedScans(RecordingLogger log) {
        return log.at(Level.INFO).stream().filter(l -> l.contains("material containers (requested)")).count();
    }

    /** BUILD rounds that placed nothing and went back to RESTOCK for more ("load used up"). */
    private static long stalledRounds(RecordingLogger log) {
        return log.at(Level.INFO).stream()
            .filter(l -> l.startsWith("build round:") && l.contains(" 0 placed,") && l.endsWith("load used up")).count();
    }

    private static String tail(RecordingLogger log) {
        List<String> l = log.lines;
        return "\n" + String.join("\n", l.subList(Math.max(0, l.size() - 60), l.size()));
    }

    @Test
    void cellSkippedWithItsItemInHandDoesNotPingPongOrForceAFullRescan() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        Job job = sim.addJob("j0040", "Edge", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, ROWS));
        Pos victim = victim(sim);
        AtomicInteger attempts = new AtomicInteger();
        // like the live run: skipped in its lane and on two retries, placeable from the fourth try on
        sim.bot.unplaceable = p -> p.pos().equals(victim) && attempts.incrementAndGet() <= 3
            ? "not reachable from the lane" : null;
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log));

        assertEquals(0, requestedScans(log), "no full rescan forced by the engine" + tail(log));
        assertTrue(stalledRounds(log) <= 1, "BUILD↔RESTOCK rounds without a placement: " + stalledRounds(log) + tail(log));
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.startsWith("build round:") && l.contains("1 left to VERIFY")),
            "the stalled cell was handed to VERIFY" + tail(log));
        assertEquals(1, sim.bot.placeCounts.get(victim), "placed once, later");
        assertEquals(4, attempts.get(), "three skips, then placed");
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void cellThatNeverPlacesEndsInVerifyNotInARescanLoop() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        Job job = sim.addJob("j0041", "Never", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, ROWS));
        Pos victim = victim(sim);
        AtomicInteger attempts = new AtomicInteger();
        sim.bot.unplaceable = p -> {
            if (!p.pos().equals(victim)) return null;
            attempts.incrementAndGet();
            return "not reachable from the lane";
        };
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state() + tail(log));

        String note = sim.engine.status().note();
        assertTrue(note.contains("repair passes") && note.contains("0 wrong, 1 missing"), note + tail(log));
        assertEquals(JobStatus.ACTIVE, job.status);
        assertEquals(ROWS * 128 - 1, sim.artBlocks(sim.moss), "everything else was built");
        assertEquals(0, requestedScans(log), "no full rescan forced by the engine" + tail(log));
        assertTrue(stalledRounds(log) <= 1, "BUILD↔RESTOCK rounds without a placement: " + stalledRounds(log) + tail(log));
        int passes = sim.config.build.maxRepairPasses;
        assertTrue(attempts.get() <= 2 * (passes + 1), "bounded tries at the cell: " + attempts.get() + tail(log));
    }
}
