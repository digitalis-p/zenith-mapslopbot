package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.engine.Sim.LIME;
import static io.github.digitalisp.mapart.engine.Sim.WHITE;

/**
 * {@code site.protectedRegions} and the machinery block types: NUKE and VERIFY never break a block there, whatever the
 * block; the engine goes BLOCKED with the region named instead.
 */
class ProtectedRegionTest {
    private static final int LONG = 60_000;

    private static MapArtConfig.RegionCfg region(String name, Pos a, Pos b) {
        MapArtConfig.RegionCfg r = new MapArtConfig.RegionCfg();
        r.name = name;
        r.min = a;
        r.max = b;
        return r;
    }

    private static long breakTasks(Sim sim) {
        return sim.bot.history.stream().filter(h -> h.startsWith("breakBlocks")).count();
    }

    @Test
    void nukeNeverBreaksABlockInAProtectedRegion() {
        Sim sim = new Sim();
        Pos machine = sim.moss.geometry().cell(100, 100);
        // terracotta is a nuke block: without the region the bot would break it before the clear
        sim.world.set(machine, BlockSpec.of(Sim.TERRACOTTA));
        sim.world.set(sim.moss.geometry().cell(101, 100), BlockSpec.of(Sim.TERRACOTTA));
        sim.config.site.protectedRegions.add(region("redstone", machine, machine));
        sim.addJob("j0101", "Guarded", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        String note = sim.engine.status().note();
        assertTrue(note.contains("protected blocks on the art layer of moss"), note);
        assertTrue(note.contains("at " + machine + " (protected region #1 \"redstone\")"), note);
        assertEquals(BlockSpec.of(Sim.TERRACOTTA), sim.world.block(machine), "the protected block is still there");
        assertEquals(0, breakTasks(sim), "nothing was broken: " + sim.bot.history);
        assertEquals(0, sim.bot.count("use " + Sim.MOSS_BUTTON), "no clear pressed with the block on the art layer");
    }

    @Test
    void machineryTypesAreProtectedEvenWhenAnOldConfigListsNone() {
        Sim sim = new Sim();
        sim.config.site.protectedBlocks = new java.util.ArrayList<>(List.of("minecraft:obsidian")); // an old, short list
        Pos wire = sim.moss.geometry().cell(10, 10);
        sim.world.set(wire, BlockSpec.of("minecraft:redstone_wire"));
        sim.addJob("j0102", "Wired", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        String note = sim.engine.status().note();
        assertTrue(note.contains("minecraft:redstone_wire at " + wire + " (protected block)"), note);
        assertEquals(BlockSpec.of("minecraft:redstone_wire"), sim.world.block(wire));
        assertEquals(0, breakTasks(sim), sim.bot.history.toString());
    }

    @Test
    void verifyNeverBreaksAWrongBlockInAProtectedRegion() {
        Sim sim = new Sim();
        TileArt art = Sim.single(WHITE, 4);
        Pos victim = sim.moss.geometry().cell(1, 3); // in the first lane
        AtomicInteger injected = new AtomicInteger();
        // the server puts a wrong block there (a rollback, a griefer): VERIFY would break and replace it
        sim.bot.placeOverride = p -> p.pos().equals(victim) && injected.getAndIncrement() == 0
            ? new Placement(p.pos(), BlockSpec.of(LIME), LIME) : null;
        sim.config.site.protectedRegions.add(region(null, victim, victim));
        sim.addJob("j0103", "Verify", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        String note = sim.engine.status().note();
        assertTrue(note.contains("cannot break 1 blocks on the art layer"), note);
        assertTrue(note.contains("at " + victim + " (protected region #1)"), note);
        assertEquals(BlockSpec.of(LIME), sim.world.block(victim), "the wrong block in the region was left alone");
        assertEquals(0, breakTasks(sim), sim.bot.history.toString());
    }
}
