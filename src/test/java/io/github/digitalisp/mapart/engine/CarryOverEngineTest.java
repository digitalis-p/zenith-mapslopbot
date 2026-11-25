package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileStatus;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Carry-over between tiles in local mode: the end of a stored map
 * breaks only the cells the next map on the platform does not share, presses no clear, and the next map's INSPECT
 * resumes from the cells kept and places only the rest.
 */
class CarryOverEngineTest {
    private static final int LONG = 80_000;
    private static final String WHITE = Sim.WHITE, RED = Sim.RED, TERRACOTTA = Sim.TERRACOTTA;

    /** 8 rows of terracotta (a full block the clear cannot push), then 2 rows of {@code carpet}. */
    static TileArt background(String carpet) {
        TileArt a = new TileArt();
        for (int pz = 0; pz < 8; pz++)
            for (int px = 0; px < 128; px++) a.set(px, pz, BlockSpec.of(TERRACOTTA));
        for (int pz = 8; pz < 10; pz++)
            for (int px = 0; px < 128; px++) a.set(px, pz, BlockSpec.of(carpet));
        return a;
    }

    private static Sim sim(RecordingLogger log, boolean carryOver) {
        Sim sim = new Sim();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        sim.config.build.carryOver = carryOver;
        sim.bot.addContainer(new Pos(-90, 61, 100), java.util.Map.of(TERRACOTTA, 1728)); // two maps' terracotta
        return sim;
    }

    private static Pos moss(Sim sim, int px, int pz) {
        return new Pos(sim.moss.minX + px, sim.moss.artY, sim.moss.minZ + pz);
    }

    private static String tail(RecordingLogger log) {
        List<String> l = log.lines;
        return "\n" + String.join("\n", l.subList(Math.max(0, l.size() - 60), l.size()));
    }

    private static long clears(Sim sim) {
        return sim.bot.count("use " + Sim.MOSS_BUTTON);
    }

    @Test
    void theSharedCellsStayAndOnlyTheRestIsBrokenAndPlaced() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true);
        TileArt first = background(WHITE), second = background(RED);
        Job job = sim.addJob("j0101", "Carry", 2, 1, List.of("moss"), (x, z) -> x == 0 ? first : second);
        sim.start();
        boolean[] secondStarted = {false};
        long[] clearsBeforeSecond = {-1};
        assertTrue(sim.runUntil(() -> {
            Job.TileState t1 = job.tiles.get(1);
            if (!secondStarted[0] && t1.status == TileStatus.ACTIVE) {
                secondStarted[0] = true;
                clearsBeforeSecond[0] = clears(sim);
            }
            return job.status == JobStatus.DONE || sim.phase() == Phase.BLOCKED;
        }, LONG), sim.state() + tail(log));
        assertEquals(JobStatus.DONE, job.status, sim.state() + tail(log));
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.IDLE, 2_000), sim.state());

        assertEquals(0, clearsBeforeSecond[0], "no clear between the two maps" + tail(log));
        assertEquals(1, clears(sim), "the last map's end of tile clears as before");
        assertEquals(0, sim.machineBroken, "no full block reached the flying machines");
        assertEquals(0, sim.artBlocks(sim.moss), "platform cleared after the last map");
        assertEquals(2, sim.output().maps.size());
        // the terracotta of the first map was placed once and served the second map too
        for (int px = 0; px < 128; px += 17) assertEquals(1, sim.bot.placeCounts.get(moss(sim, px, 3)), "kept cell placed once");
        assertEquals(2, (int) sim.bot.placeCounts.get(moss(sim, 5, 8)), "a carpet cell: white, then red");
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.contains("carry-over to #j0101 Carry [1,0]")
            && l.contains("keep 1,024 cells") && l.contains("carrying over")), tail(log));
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.contains("breaking 256 blocks on moss that")), tail(log));
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.contains("[1,0]: no carry-over (no other map in the queue)")),
            tail(log));
        assertEquals(1_024, job.tiles.get(1).carried);
        // both maps are announced as started; the second names the cells kept
        assertEquals(2, sim.notifier.of("info").stream().filter(m -> m.title().startsWith("Started")).count(),
            sim.notifier.toString());
        assertTrue(sim.notifier.of("info").stream().anyMatch(m -> m.title().startsWith("Started")
            && m.body().contains("1,024 of them were left in place")), sim.notifier.toString());
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void offEveryMapIsNukedAndClearedAsBefore() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, false);
        TileArt first = background(WHITE), second = background(RED);
        Job job = sim.addJob("j0102", "Plain", 2, 1, List.of("moss"), (x, z) -> x == 0 ? first : second);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log));
        assertEquals(2, clears(sim));
        assertEquals(2, (int) sim.bot.placeCounts.get(moss(sim, 3, 3)), "terracotta placed for each map");
        assertTrue(log.lines.stream().noneMatch(l -> l.contains("carry-over")), tail(log));
        assertEquals(0, sim.machineBroken);
    }

    @Test
    void littleInCommonIsNukedAndCleared() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true);
        // the same 2 rows of carpet, the terracotta of the first map where the second wants nothing
        TileArt first = background(WHITE), second = new TileArt();
        for (int px = 0; px < 128; px++) second.set(px, 8, BlockSpec.of(WHITE));
        Job job = sim.addJob("j0103", "Little", 2, 1, List.of("moss"), (x, z) -> x == 0 ? first : second);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log));
        // keeping 128 carpets saves 12.8 s of placing; the carry-over breaks the 128 others by hand (12.8 s) and saves
        // the clear: 1,024 terracotta + 128 carpets: it carries over
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.contains("carry-over to #j0103 Little [1,0]")), tail(log));
        assertEquals(0, sim.machineBroken);
        assertEquals(2, sim.output().maps.size());
    }

    @Test
    void aRestartInTheMiddleOfTheTeardownCarriesOn() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true);
        TileArt first = background(WHITE), second = background(RED);
        Job job = sim.addJob("j0104", "Restart", 2, 1, List.of("moss"), (x, z) -> x == 0 ? first : second);
        sim.start();
        assertTrue(sim.runUntil(() -> log.lines.stream().anyMatch(l -> l.contains("carry-over: breaking")), LONG),
            sim.state() + tail(log));
        sim.bot.breakTicksPerBlock = 2;
        sim.run(100); // part of the carpets broken
        assertEquals(Phase.NUKE, sim.phase());
        int left = 0;
        for (int px = 0; px < 128; px++) if (!sim.world.block(moss(sim, px, 8)).isAir()) left++;
        assertTrue(left > 0 && left < 128, "mid-teardown: " + left);
        sim.engine.onBotTickStopped();
        sim.engine.shutdown();
        sim.bot.breakTicksPerBlock = 0;
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log));
        assertEquals(1, clears(sim), "only the last map's clear");
        assertEquals(1, (int) sim.bot.placeCounts.get(moss(sim, 9, 3)), "kept cell placed once");
        assertEquals(2, log.at(Level.INFO).stream().filter(l -> l.contains("carry-over to #j0104")
            && l.contains("carrying over")).count(), "decided again after the restart" + tail(log));
        assertEquals(0, sim.machineBroken);
        assertEquals(2, sim.output().maps.size());
    }

    @Test
    void aNextMapCancelledOnceItIsBegunIsCleanedUpByItsOwnNuke() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true);
        TileArt first = background(WHITE), second = background(RED), third = Sim.single(RED, 2);
        Job a = sim.addJob("j0105", "First", 1, 1, List.of("moss"), (x, z) -> first);
        Job b = sim.addJob("j0106", "Second", 1, 1, List.of("moss"), (x, z) -> second);
        Job c = sim.addJob("j0107", "Third", 1, 1, List.of("moss"), (x, z) -> third);
        sim.start();
        assertTrue(sim.runUntil(() -> a.status == JobStatus.DONE, LONG), sim.state() + tail(log));
        // the first map carried its terracotta over to the second map's tile, which is now begun on moss
        assertEquals(TileStatus.ACTIVE, b.tiles.getFirst().status);
        assertEquals("moss", b.tiles.getFirst().platformId);
        assertEquals(0, clears(sim));
        assertTrue(sim.store.cancel(b.id));
        sim.engine.queueChanged();
        assertTrue(sim.runUntil(() -> c.status == JobStatus.DONE, LONG), sim.state() + tail(log));
        // the cancelled map's clean-up nukes the terracotta left for it (nothing clearable is left: no press needed)
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.contains("cancelled #j0106 Second [0,0] cleaned up")), tail(log));
        assertEquals(0, sim.machineBroken, "the terracotta left for the cancelled map was nuked before any clear");
        assertEquals(2, sim.output().maps.size());
        assertEquals(0, sim.artBlocks(sim.moss));
    }

    @Test
    void aNextMapCancelledBeforeItStartsMeetsForeignBlocksAndTheNormalNukeAndClear() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true);
        TileArt first = background(WHITE), second = background(RED);
        // the third map shares nothing with what the carry-over leaves: the terracotta is foreign to it
        TileArt third = new TileArt();
        for (int px = 0; px < 128; px++) third.set(px, 20, BlockSpec.of(RED));
        Job a = sim.addJob("j0110", "First", 1, 1, List.of("moss"), (x, z) -> first);
        Job b = sim.addJob("j0111", "Second", 1, 1, List.of("moss"), (x, z) -> second);
        Job c = sim.addJob("j0112", "Third", 1, 1, List.of("moss"), (x, z) -> third);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.FINISH_TILE && a.tiles.getFirst().status == TileStatus.STORED,
            LONG), sim.state() + tail(log));
        assertTrue(sim.store.cancel(b.id)); // before the first map's FINISH_TILE marks it begun
        sim.engine.queueChanged();
        assertTrue(sim.runUntil(() -> c.status == JobStatus.DONE, LONG), sim.state() + tail(log));
        assertEquals(TileStatus.PENDING, b.tiles.getFirst().status, "a cancelled map is never marked begun");
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.contains("platform moss has foreign blocks: 1024 non-air")),
            tail(log));
        assertEquals(0, sim.machineBroken, "the terracotta was nuked before the clear");
        // the foreign nuke left the layer empty (the carry-over had broken the carpets): only the third map's own clear
        assertEquals(1, clears(sim));
        assertEquals(2, sim.output().maps.size());
        assertEquals(0, sim.artBlocks(sim.moss));
    }

    @Test
    void aNextMapOnAnotherPlatformIsNoCarryOver() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true);
        TileArt first = background(WHITE), second = background(RED);
        Job a = sim.addJob("j0108", "Moss", 1, 1, List.of("moss"), (x, z) -> first);
        Job b = sim.addJob("j0109", "Soul", 1, 1, List.of("soul"), (x, z) -> second);
        sim.start();
        assertTrue(sim.runUntil(() -> b.status == JobStatus.DONE, LONG), sim.state() + tail(log));
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.contains("no carry-over (#j0109 Soul [0,0] goes on platform soul)")),
            tail(log));
        assertEquals(1, clears(sim));
        assertEquals(0, sim.artBlocks(sim.moss));
        assertFalse(a.tiles.getFirst().carried > 0 || b.tiles.getFirst().carried > 0);
    }
}
