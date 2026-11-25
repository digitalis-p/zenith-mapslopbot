package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.impl.StandSpots;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.testutil.ContainerSim;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The moss clear actuator pressed through the production {@code UseRun} on a simulated block world: a LEVER on a
 * structure at the platform centre, above the flying machines, with {@code standAt}; the engine flips it on, holds it,
 * flips it back. Synthetic coordinates.
 */
class ActuatorAccessTest {
    private static final int LONG = 60_000;

    @Test
    void leverAtTheCentreIsHeldAndReleasedFromStandAtNeverFromTheArtLayer() {
        Sim sim = new Sim();
        int art = sim.moss.artY;
        Pos lever = new Pos(0, art + 5, 0);      // on the structure, 5 above the art layer
        Pos standAt = new Pos(0, art + 5, 2);
        BlockSpec off = BlockSpec.of("minecraft:lever", Map.of("face", "floor", "facing", "north", "powered", "false"));
        sim.world.set(lever, off);
        AtomicInteger presses = new AtomicInteger();
        sim.bot.useHandlers.put(lever, () -> {
            if (presses.getAndIncrement() % 2 == 0) sim.bot.useHandlers.get(Sim.MOSS_BUTTON).run();
        });
        var act = sim.moss.clear.getFirst();
        act.pos = lever;
        act.standAt = standAt;
        act.mode = "LEVER";
        act.holdTicks = 40;

        ContainerSim cs = sim.bot.simulateContainerAccess(41);
        cs.fill(-12, art - 1, -12, 12, art - 1, 12, BlockSpec.of("minecraft:moss_block"));
        cs.fill(-2, art + 4, -2, 2, art + 4, 2, ContainerSim.GLASS); // the structure: feet art + 5
        for (int i = 0; i < 4; i++) cs.fill(6 - i, art, 0, 6 - i, art + i, 0, ContainerSim.STONE); // stairs from the east
        cs.set(lever, off);
        cs.allowedCells = StandSpots.awayFromClearSweeps(List.of(sim.moss.geometry()));

        Job job = sim.addJob("j0041", "Lever", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());

        assertEquals(2, sim.bot.count("use " + lever), "on, then off");
        assertTrue(sim.bot.history.contains("wait 40"), "held between");
        assertEquals("false", sim.world.block(lever).props().get("powered"));
        assertEquals("false", cs.block(lever).props().get("powered"));
        assertEquals(2, cs.uses.size(), cs.uses.toString());
        assertEquals(List.of(standAt, standAt), sim.bot.pressedFrom, "both presses from standAt");
        assertTrue(sim.bot.approachFailures.isEmpty(), sim.bot.approachFailures.toString());
        assertEquals(0, cs.rotateAndClick);
        assertEquals(0, cs.clickWhileMoving);
    }
}
