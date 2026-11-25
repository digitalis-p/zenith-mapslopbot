package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.WithdrawMode;
import io.github.digitalisp.mapart.engine.SimFakes.SerpentinePlanner;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One anvil at a time ({@code build.exactAnvil}). Live 2026-10-22 18:57 (soul
 * sand): the anvil block was gone at RENAME, the replacement came from the anvil chest as a whole stack, one was placed
 * and 47 rode along in a kept slot for the next 30+ maps (the owner's inventory dump 10-23 06:46: "anvil (x47)"). With the map
 * and pane carried from STORE ({@code build.mapPaneAtStore}) the kit fills {@code build.reservedSlots}, so that slot was
 * a material slot less on every load.
 */
class ExactAnvilTest {
    private static final int LONG = 80_000;
    private static final String ANVIL = "minecraft:anvil";

    private static Sim sim(boolean on, int chestAnvils) {
        Sim sim = new Sim();
        sim.config.build.exactAnvil = on;
        sim.bot.wholeStackWithdrawals = true; // a whole-stack withdrawal moves the stack, as the production pattern does
        sim.bot.containers.get(Sim.ANVILS).items.put(ANVIL, chestAnvils);
        sim.newEngine(new SerpentinePlanner(), new GreedyRestockPlanner(), new RecordingLogger());
        return sim;
    }

    private static void build(Sim sim, String id) {
        Job job = sim.addJob(id, "Anvil", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 1));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals("Anvil", sim.output().maps.getFirst().name(), "renamed and stored");
    }

    @Test
    void theReplacementAnvilComesAloneNotAsAStack() {
        Sim before = sim(false, 48);
        before.world.set(Sim.ANVIL, BlockSpec.AIR);
        build(before, "j0970");
        assertEquals(ANVIL, before.world.block(Sim.ANVIL).id(), "placed");
        assertEquals(47, before.inv.count(ANVIL), "before: the rest of the stack rides along");

        Sim now = sim(true, 48);
        now.world.set(Sim.ANVIL, BlockSpec.AIR);
        build(now, "j0971");
        assertEquals(ANVIL, now.world.block(Sim.ANVIL).id(), "placed");
        assertEquals(0, now.inv.count(ANVIL), "nothing carried after the rename");
        assertEquals(47, now.bot.containers.get(Sim.ANVILS).items.getOrDefault(ANVIL, 0));
        assertTrue(now.bot.withdrawModes.contains(WithdrawMode.EXACT), now.bot.withdrawModes.toString());
        System.out.println("[exact anvil] anvils carried after a replacement: " + before.inv.count(ANVIL) + " -> "
            + now.inv.count(ANVIL));
    }

    @Test
    void anvilsAlreadyCarriedGoBackAtStore() {
        Sim sim = sim(true, 1);
        sim.inv.add(ANVIL, 47); // the live leftover of the old whole-stack replacement
        build(sim, "j0972");
        assertEquals(0, sim.inv.count(ANVIL), "put back at STORE");
        assertEquals(48, sim.bot.containers.get(Sim.ANVILS).items.getOrDefault(ANVIL, 0));
        assertEquals(Map.of(ANVIL, 47), Map.of(ANVIL, sim.bot.returnedWorking.getOrDefault(ANVIL, 0)));

        Sim off = sim(false, 1);
        off.inv.add(ANVIL, 47);
        build(off, "j0973");
        assertEquals(47, off.inv.count(ANVIL), "off: kept, as before");
    }
}
