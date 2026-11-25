package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileStatus;
import io.github.digitalisp.mapart.testutil.ContainerSim;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A whole map with every container of the {@link Sim} site in a simulated block world (synthetic coordinates): each
 * scan, withdraw, lock, rename and store gets to its block with the production {@code Approach}, clicks it, and runs
 * the production {@code ContainerRun} in the menu, while the simulated ZenithProxy completes every input request
 * FALSE as long as a menu is open (ZenithProxy 3.7.2 Bot.tick). Gate B: every one of them failed with "input not held
 * still" because the still tick waited for an executed request.
 */
class MenuInputRuleTest {
    private static final int LONG = 80_000;

    /** Stone floor at y 60 under the storage row (containers stand on it, feet y 61), every container a block. */
    private static ContainerSim storageInTheSim(Sim sim) {
        ContainerSim cs = sim.bot.simulateContainerAccess(81);
        cs.fill(-106, 60, 90, -30, 60, 110, ContainerSim.STONE);
        for (Pos p : sim.bot.containers.keySet()) cs.set(p, ContainerSim.chest("north", "single"));
        cs.set(Sim.TABLE, BlockSpec.of("minecraft:cartography_table"));
        cs.set(Sim.ANVIL, BlockSpec.of("minecraft:anvil", Map.of("facing", "north")));
        return cs;
    }

    @Test
    void scanWithdrawLockRenameAndStoreAllWorkWhileOpenMenusDropTheInput() {
        Sim sim = new Sim();
        ContainerSim cs = storageInTheSim(sim);
        Job job = sim.addJob("j0081", "Menus", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(2, 0));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + "\n" + sim.bot.menuFailures);

        assertEquals(TileStatus.STORED, job.tiles.getFirst().status);
        assertEquals("Menus", sim.output().maps.getFirst().name(), "renamed at the anvil, stored in the output chest");
        assertTrue(sim.bot.menuFailures.isEmpty(), sim.bot.menuFailures.toString());
        assertTrue(sim.bot.approachFailures.isEmpty(), sim.bot.approachFailures.toString());
        for (String kind : List.of("scan ", "withdraw ", "lockMap " + Sim.TABLE, "rename " + Sim.ANVIL,
            "depositMap " + Sim.OUTPUT)) {
            assertTrue(sim.bot.menus.stream().anyMatch(m -> m.startsWith(kind)), kind + " not in " + sim.bot.menus);
        }
        assertTrue(cs.droppedInputs > 0, "input requests were completed false in the menus");
        assertEquals(0, cs.multiActions, "no click request while the bot moved (Grim MultiActionsC)");
        assertEquals(0, cs.openMenuId(), "no menu left open");
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void foodIsFetchedThroughTheMenuWhenTheBotRunsOut() {
        Sim sim = new Sim();
        ContainerSim cs = storageInTheSim(sim);
        sim.inv.remove("minecraft:golden_carrot", sim.inv.count("minecraft:golden_carrot"));
        sim.inv.foodLevel = 4;
        Job job = sim.addJob("j0082", "Food", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(1, 0));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.eats > 0, LONG), sim.state() + "\n" + sim.bot.menuFailures);
        assertTrue(sim.bot.menus.stream().anyMatch(m -> m.startsWith("withdraw " + Sim.FOOD)), sim.bot.menus.toString());
        assertTrue(sim.bot.menuFailures.isEmpty(), sim.bot.menuFailures.toString());
        assertTrue(sim.inv.foodLevel > 4);
        assertEquals(0, cs.multiActions);
        assertTrue(job.status != JobStatus.FAILED);
    }
}
