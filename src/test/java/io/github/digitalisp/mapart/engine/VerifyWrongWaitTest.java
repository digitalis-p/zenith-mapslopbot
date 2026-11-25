package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;


import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VERIFY does not let one wrong block it cannot break use up its repair passes (live 2026-10-12 17:29-17:33, ellen
 * [0,2]: "65 wrong, 3 missing"; one wrong blue carpet in a dripstone pocket could not be reached, passes 2-4 each failed
 * to break it and never placed the 77 missing cells, BLOCKED). The wrong blocks left after the break rounds wait for the
 * next pass; the missing cells are placed in this one.
 */
class VerifyWrongWaitTest {
    static final String WHITE = "minecraft:white_carpet";
    static final String RED = "minecraft:red_carpet";

    @Test
    void missingCellsArePlacedWhileAnUnreachableWrongBlockWaits() {
        Sim sim = new Sim();
        TileArt art = Sim.single(WHITE, 4);
        Job job = sim.addJob("j0040", "Wait", 1, 1, List.of("moss"), (x, z) -> art);
        Pos victim = sim.moss.geometry().cell(10, 2);
        Set<Pos> missing = Set.of(sim.moss.geometry().cell(50, 1), sim.moss.geometry().cell(51, 1),
            sim.moss.geometry().cell(52, 1));
        AtomicInteger wrongPlaced = new AtomicInteger();
        sim.bot.placeOverride = p -> p.pos().equals(victim) && wrongPlaced.getAndIncrement() == 0
            ? new Placement(p.pos(), BlockSpec.of(RED), RED) : null;
        AtomicInteger skipped = new AtomicInteger();
        sim.bot.unplaceable = p -> missing.contains(p.pos()) && skipped.getAndIncrement() < missing.size()
            ? "rejected" : null;
        // the wrong block cannot be reached by the first four break tasks (two passes' worth of rounds)
        AtomicInteger tries = new AtomicInteger();
        sim.bot.unbreakable = p -> p.equals(victim) && tries.incrementAndGet() <= 4;
        AtomicBoolean placedWhileWrong = new AtomicBoolean();
        sim.start();
        assertTrue(sim.runUntil(() -> {
            boolean allPlaced = missing.stream().allMatch(m -> sim.world.block(m).id().equals(WHITE));
            if (allPlaced && sim.world.block(victim).id().equals(RED)) placedWhileWrong.set(true);
            return job.status == JobStatus.DONE;
        }, 60_000), sim.state());
        assertTrue(placedWhileWrong.get(), "the missing cells waited for the wrong block");
        assertTrue(tries.get() >= 5, "tries " + tries.get());
        assertEquals(2, sim.bot.placeCounts.get(victim), "the wrong block broken and placed again");
        assertEquals(1, sim.bot.createdMaps);
    }

    /**
     * Live 2026-10-12 19:19-19:21, ellen [1,3]: 1 wrong block the breaks did not reach and 7 missing cells whose items
     * the bot no longer held; every pass went to the break, none to RESTOCK, BLOCKED (on a jar without the wait). The
     * wait leads to RESTOCK: the missing cells are fetched and placed while the wrong block still waits, and the passes
     * end once it breaks.
     */
    @Test
    void theWaitStillRestocksForTheMissingCells() {
        Sim sim = new Sim();
        TileArt art = Sim.single(WHITE, 4);
        Job job = sim.addJob("j0041", "Wait restock", 1, 1, List.of("moss"), (x, z) -> art);
        Pos victim = sim.moss.geometry().cell(72, 2);
        Set<Pos> missing = Set.of(sim.moss.geometry().cell(20, 1), sim.moss.geometry().cell(40, 3),
            sim.moss.geometry().cell(90, 0));
        AtomicInteger wrongPlaced = new AtomicInteger();
        sim.bot.placeOverride = p -> p.pos().equals(victim) && wrongPlaced.getAndIncrement() == 0
            ? new Placement(p.pos(), BlockSpec.of(RED), RED) : null;
        AtomicInteger skipped = new AtomicInteger();
        sim.bot.unplaceable = p -> missing.contains(p.pos()) && skipped.getAndIncrement() < missing.size()
            ? "rejected" : null;
        // the wrong block is not reached by the first two rounds of each of the first two passes
        AtomicInteger tries = new AtomicInteger();
        sim.bot.unbreakable = p -> p.equals(victim) && tries.incrementAndGet() <= 4;
        AtomicBoolean emptied = new AtomicBoolean();
        AtomicBoolean restockWhileWrong = new AtomicBoolean();
        AtomicBoolean placedWhileWrong = new AtomicBoolean();
        List<Phase> phases = new ArrayList<>();
        sim.start();
        assertTrue(sim.runUntil(() -> {
            Phase ph = sim.phase();
            if (phases.isEmpty() || phases.getLast() != ph) phases.add(ph);
            boolean wrong = sim.world.block(victim).id().equals(RED);
            if (!emptied.get() && ph == Phase.VERIFY) {
                // the bot holds none of the missing cells' item any more (the leftovers went back)
                sim.bot.inv.items.remove(WHITE);
                emptied.set(true);
            }
            if (emptied.get() && ph == Phase.RESTOCK && wrong) restockWhileWrong.set(true);
            boolean allPlaced = missing.stream().allMatch(m -> sim.world.block(m).id().equals(WHITE));
            if (allPlaced && wrong) placedWhileWrong.set(true);
            return job.status == JobStatus.DONE;
        }, 80_000), sim.state() + "\nphases " + phases);
        assertTrue(restockWhileWrong.get(), "no RESTOCK while the wrong block waited: " + phases);
        assertTrue(placedWhileWrong.get(), "the missing cells waited for the wrong block: " + phases);
        assertEquals(5, tries.get(), "break tries: " + phases);
        assertEquals(1, sim.bot.createdMaps);
    }
}
