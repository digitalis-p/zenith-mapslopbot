package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live 10x run: an open_eyeblossom pixel closed by itself (day/night random tick). VERIFY counted the closed one as
 * a wrong block, broke it and placed an open one again on every repair pass until BLOCKED, and the broken flowers
 * (closed_eyeblossom items) were thrown into the water. Open and closed eyeblossoms are now the same block for
 * every plan-vs-world comparison, so a flipped flower is never broken.
 */
class EyeblossomFlipTest {
    private static final String OPEN = "minecraft:open_eyeblossom";
    private static final String CLOSED = "minecraft:closed_eyeblossom";

    @Test
    void aFlowerThatClosesAfterPlacementIsNeverBrokenAndReplaced() {
        Sim sim = new Sim();
        sim.bot.addContainer(new Pos(-90, 61, 100), Map.of(OPEN, 1728));
        TileArt art = new TileArt();
        for (int px = 0; px < 128; px++) {
            art.set(px, 0, BlockSpec.of(Sim.WHITE));
            art.set(px, 1, BlockSpec.of(OPEN));
        }
        sim.addJob("j0050", "Flowers", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        // night falls the moment a flower is placed: every open eyeblossom on the art layer closes
        boolean reachedMap = sim.runUntil(() -> {
            for (int px = 0; px < 128; px++) {
                Pos p = sim.moss.geometry().cell(px, 1);
                if (sim.world.block(p).id().equals(OPEN)) sim.world.set(p, BlockSpec.of(CLOSED));
            }
            Phase ph = sim.phase();
            return ph == Phase.FILL_MAP || ph == Phase.CHECK_MAP || ph == Phase.BLOCKED;
        }, 60_000);
        assertTrue(reachedMap, sim.state());
        assertTrue(sim.phase() != Phase.BLOCKED, "built and verified without blocking: " + sim.state());

        assertEquals(0, sim.bot.broken, "no flipped flower was broken");
        for (int px = 0; px < 128; px++) {
            Pos p = sim.moss.geometry().cell(px, 1);
            assertEquals(CLOSED, sim.world.block(p).id());
            assertEquals(1, sim.bot.placeCounts.get(p), "placed once at " + px);
        }
    }
}
