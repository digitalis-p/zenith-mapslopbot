package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.impl.Aim;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.testutil.ContainerSim;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RENAME with the anvil gone from the anvil tower (synthetic coordinates): the engine fetches one from the anvil chest
 * and the production {@code PlaceRun} sneak-clicks it onto the tower's crafting table, one above the walkway.
 */
class AnvilPlacementTest {
    private static final int LONG = 60_000;

    @Test
    void aMissingAnvilIsSneakPlacedOnTheTowersCraftingTable() {
        Sim sim = new Sim();
        Pos anvil = Sim.ANVIL;
        sim.world.set(anvil, BlockSpec.AIR);
        ContainerSim cs = sim.bot.simulateContainerAccess(62);
        int x = anvil.x();
        int y = anvil.y();
        int z = anvil.z();
        cs.fill(x - 8, y - 2, z - 8, x + 8, y - 2, z + 8, ContainerSim.STONE); // the walkway
        cs.set(x, y - 1, z, BlockSpec.of("minecraft:crafting_table"));    // walkway + 1
        cs.fill(x, y - 1, z - 1, x, y + 2, z - 1, BlockSpec.of("minecraft:polished_blackstone_wall"));
        cs.fill(x - 1, y - 1, z, x - 1, y + 2, z, BlockSpec.of("minecraft:polished_blackstone_wall"));
        cs.fill(x, y - 1, z + 1, x, y + 1, z + 1, ContainerSim.chest("east", "single"));

        Job job = sim.addJob("j0062", "Tower", 1, 1, List.of("moss"), (px, pz) -> Sim.single(Sim.WHITE, 1));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());

        assertEquals("minecraft:anvil", sim.world.block(anvil).id());
        assertEquals("minecraft:anvil", cs.block(anvil).id(), "placed in the simulated tower");
        assertEquals(1, cs.placed);
        assertTrue(cs.placedSneaking, "sneak-click on the crafting table");
        assertEquals(0, cs.openedInstead);
        assertEquals(Aim.UP, cs.placedWith.face());
        assertEquals(0, cs.rotateAndClick);
        assertEquals(cs.placedTick - 1, cs.placedAimedTick);
        assertEquals("Tower", sim.output().maps.getFirst().name());
        assertTrue(sim.bot.history.stream().anyMatch(h -> h.startsWith("placeBlock " + anvil)), sim.bot.history.toString());
        // then the rename in the placed anvil's menu (ZenithProxy drops every input request while it is open)
        assertTrue(sim.bot.menus.stream().anyMatch(m -> m.startsWith("rename " + anvil)), sim.bot.menus.toString());
        assertEquals(anvil, cs.opened);
        assertFalse(cs.clickSneaking, "a plain click opens the anvil");
        assertTrue(sim.bot.menuFailures.isEmpty(), sim.bot.menuFailures.toString());
        assertEquals(0, cs.multiActions);
    }
}
