package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.engine.Sim.WHITE;

/**
 * {@code build.junkNukeDrops}: the clay balls the NUKE brought back from clay art go to the junk spot like listed junk
 * (live 2026-10-21..23: 404 of them held 7 slots for 31 hours), never into the drop point; clay balls of a base where
 * clay is no material, and every one with the switch off, stay.
 */
class NukeByProductsEngineTest {
    private static final int LONG = 200_000;
    private static final int CELLS = 2 * 128;
    private static final String CLAY = "minecraft:clay";
    private static final String CLAY_BALL = "minecraft:clay_ball";
    private static final String BRICK = "minecraft:brick";
    private static final Pos JUNK_STAND = new Pos(-60, 64, -66);
    private static final Pos JUNK_AT = new Pos(-56, 64, -66);

    /** A 2-row white job; the bot carries clay balls and bricks; clay was a material chest's item when {@code clayKnown}. */
    private static Sim sim(RecordingLogger log, boolean clayKnown, boolean on) {
        Sim sim = new Sim();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        sim.config.build.junkNukeDrops = on;
        sim.config.site.storage.returnChests.clear();
        MapArtConfig.DropPointCfg drop = new MapArtConfig.DropPointCfg();
        drop.standAt = new Pos(-65, 64, -66);
        drop.target = new Pos(-65, 60, -62);
        sim.config.site.storage.returnDrops.add(drop);
        sim.moss.junkDropStand = JUNK_STAND;
        sim.moss.junkDropAt = JUNK_AT;
        if (clayKnown) sim.store.stock.seen.add(CLAY);
        sim.inv.add(WHITE, CELLS);
        sim.inv.add(CLAY_BALL, 404);
        sim.inv.add(BRICK, 3);
        return sim;
    }

    @Test
    void clayBallsOfAClayBaseAreThrownAtTheJunkSpot() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true, true);
        Job job = sim.addJob("j0320", "Clay", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.droppedItems.containsKey(CLAY_BALL), LONG), sim.state() + log.lines);
        assertEquals(Map.of(CLAY_BALL, 404), sim.bot.droppedItems, "the clay balls, not the bricks (no pot here)");
        assertEquals(0, sim.inv.count(CLAY_BALL));
        assertEquals(3, sim.inv.count(BRICK), "decorated pots are no material of this base");
        assertTrue(sim.bot.dropStands.contains(JUNK_STAND), sim.bot.dropStands.toString());
        assertFalse(log.at(Level.INFO).stream().anyMatch(l -> l.startsWith("dropping ") && l.contains(CLAY_BALL)),
            "never into the drop point: " + log.lines);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
    }

    @Test
    void clayBallsStayWhereClayIsNoMaterial() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, false, true);
        Job job = sim.addJob("j0321", "NoClay", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(404, sim.inv.count(CLAY_BALL), "the owner's clay balls");
        assertFalse(sim.bot.droppedItems.containsKey(CLAY_BALL));
    }

    @Test
    void withTheSwitchOffTheClayBallsStayAsBefore() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true, false);
        Job job = sim.addJob("j0322", "Off", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(404, sim.inv.count(CLAY_BALL));
        assertFalse(sim.bot.droppedItems.containsKey(CLAY_BALL));
    }

    @Test
    void aJobThatListsTheItemTakesItAsAMaterial() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true, true);
        Job job = sim.addJob("j0323", "Clay", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        Job other = sim.addJob("j0324", "Balls", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        other.materials = new TreeMap<>(Map.of(CLAY_BALL, 10, WHITE, 10));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertFalse(log.at(Level.INFO).stream().anyMatch(l -> l.startsWith("throwing junk") && l.contains(CLAY_BALL)),
            "a queued job's material is never junk: " + log.lines);
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.startsWith("dropping ") && l.contains(CLAY_BALL)),
            "it goes back as a leftover, as before: " + log.lines);
    }
}
