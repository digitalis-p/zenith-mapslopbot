package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.engine.SimFakes.SerpentinePlanner;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileMetrics;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The "tile summary" line at Map done: one line per tile, exact counters, no coordinates (map_metrics.py reads it). */
class TileSummaryTest {
    private static final int LONG = 60_000;
    private static final Pattern LINE = Pattern.compile("^tile summary (#.+?): placed (\\d+) \\(build (\\d+), repair (\\d+)\\),"
        + " from stand spots (\\d+), walking (\\d+); lane runs (\\d+); restocks (\\d+) \\((\\d+) loads, (\\d+) items\\);"
        + " setbacks (\\d+)(; counted from mid-tile only)?$");

    private static Matcher summary(RecordingLogger log) {
        List<String> lines = log.at(Level.INFO).stream().filter(l -> l.startsWith("tile summary ")).toList();
        assertEquals(1, lines.size(), "one summary line: " + lines);
        Matcher m = LINE.matcher(lines.getFirst());
        assertTrue(m.matches(), lines.getFirst());
        assertFalse(Pattern.compile("-?\\d+,\\s*-?\\d+,\\s*-?\\d+").matcher(lines.getFirst()).find(), "no coordinates");
        return m;
    }

    private static void assertDone(Sim sim, Job job) {
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), "job not done: " + sim.state());
    }

    @Test
    void everyBlockCountsOnceAcrossARestartWithStandSpotsAndSetbacks() {
        Sim sim = new Sim();
        RecordingLogger log = new RecordingLogger();
        sim.newEngine(new SerpentinePlanner(), new GreedyRestockPlanner(), log);
        TileArt art = Sim.stripes(24, 0);
        Job job = sim.addJob("j0010", "Summary", 1, 1, List.of("moss"), (x, z) -> art);
        sim.bot.reportPlacedCells = true; // like LaneRun: a re-run reports the cells placed before it as placed
        sim.bot.standSpotEvery = 5;
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.placed > 600 && sim.phase() == Phase.BUILD, LONG), sim.state());
        sim.engine.noteSetback();
        sim.engine.noteSetback();
        sim.engine.noteSetback();
        sim.engine.onBotTickStopped();
        sim.engine.shutdown();
        sim.newEngine(new SerpentinePlanner(), new GreedyRestockPlanner(), log); // restart: counters from the job
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state());
        sim.engine.noteSetback();
        sim.engine.noteSetback();
        assertDone(sim, job);

        Matcher m = summary(log);
        int total = art.nonEmptyCount();
        assertEquals("#j0010 Summary [0,0]", m.group(1));
        assertEquals(total, sim.bot.placed, "the fake placed each block once");
        assertEquals(total, Integer.parseInt(m.group(2)), "placed: every block once, cut-short run included");
        assertEquals(total, Integer.parseInt(m.group(3)));
        assertEquals(0, Integer.parseInt(m.group(4)), "no repairs");
        assertEquals(total / 5, Integer.parseInt(m.group(5)), "from stand spots");
        assertEquals(total - total / 5, Integer.parseInt(m.group(6)), "walking");
        assertTrue(Integer.parseInt(m.group(7)) > sim.bot.lanesRun.size() / 2, "lane runs: " + m.group(7));
        assertTrue(Integer.parseInt(m.group(8)) >= 2, "restocks: " + m.group(8));
        assertTrue(Integer.parseInt(m.group(9)) >= 1, "loads: " + m.group(9));
        assertTrue(Long.parseLong(m.group(10)) >= total, "items withdrawn: " + m.group(10));
        assertEquals(5, Integer.parseInt(m.group(11)), "setbacks before and after the restart");
        assertEquals(null, m.group(12), "counted from the start");

        TileMetrics t = job.tiles.getFirst().metrics;
        assertNotNull(t);
        assertEquals(sim.bot.lanesRun.size(), t.laneRuns, "every placeLane counted");
        assertTrue(t.fromStart);
    }

    @Test
    void countersMissingAtAResumeSayMidTile() {
        Sim sim = new Sim();
        RecordingLogger log = new RecordingLogger();
        sim.newEngine(new SerpentinePlanner(), new GreedyRestockPlanner(), log);
        TileArt art = Sim.stripes(24, 0);
        Job job = sim.addJob("j0011", "Late", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.placed > 600 && sim.phase() == Phase.BUILD, LONG), sim.state());
        sim.engine.onBotTickStopped();
        sim.engine.shutdown();
        job.tiles.getFirst().metrics = null; // a plugin build without the counters had the tile until now
        sim.newEngine(new SerpentinePlanner(), new GreedyRestockPlanner(), log);
        sim.start();
        assertDone(sim, job);

        Matcher m = summary(log);
        assertEquals("; counted from mid-tile only", m.group(12));
        assertTrue(Integer.parseInt(m.group(2)) < art.nonEmptyCount(), "only what came after: " + m.group(2));
    }
}
