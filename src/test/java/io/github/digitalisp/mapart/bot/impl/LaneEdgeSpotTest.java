package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.TileRunner;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edge stops in the lane placer ({@link LaneRun} "Edge stops"): a stand spot beside a drop, water or a no-go zone
 * ({@link LaneRun.Io#edgeHazard}) ranks after every other, and one that is taken is centred on sneaking.
 */
class LaneEdgeSpotTest {
    /** VERIFY of a checkerboard whose full blocks are all placed: every carpet is a hole placed from a stand spot. */
    private static TileRunner holes(TileArt art, Predicate<Pos> edge, boolean spotsLast, boolean sneak) {
        TileRunner r = new TileRunner(12);
        for (int x = 30; x < 60; x++)
            for (int z = 20; z < 50; z++) {
                BlockSpec b = art.get(x, z);
                if (b.id().endsWith("terracotta")) r.world.put(r.geo.cell(x, z), b);
            }
        r.sim.edgeCells = edge;
        r.sim.edgeSpotsLast = spotsLast;
        r.sim.edgeSneak = sneak;
        assertTrue(r.build(art, 1), "tile not built\n" + r.report(art));
        return r;
    }

    /** Art columns 40-41 (world x -24..-23): a strip of feet cells beside a hazard, from the floor to a block's top. */
    private static boolean strip(Pos p) {
        return p.x() == -24 || p.x() == -23;
    }

    @Test
    void edgeSpotsRankAfterEveryOther() {
        TileArt art = TileRunner.checkerboard(30, 20, 60, 50);
        TileRunner free = holes(art, LaneEdgeSpotTest::strip, false, false);
        long before = free.sim.spotGoals.stream().filter(LaneEdgeSpotTest::strip).count();
        assertTrue(before > 0, "without the rule some stand spots lie in the strip: " + free.sim.spotGoals.size());

        TileRunner r = holes(art, LaneEdgeSpotTest::strip, true, false);
        assertEquals(List.of(), r.mismatches(art), r.report(art));
        assertEquals(0, r.failures, r.report(art));
        long after = r.sim.spotGoals.stream().filter(LaneEdgeSpotTest::strip).count();
        assertEquals(0, after, "every hole has a spot outside the strip: " + r.sim.spotGoals);
        assertTrue(r.sim.spotGoals.size() > 0, r.report(art));
    }

    @Test
    void anEdgeSpotTakenIsCentredOnSneaking() {
        TileArt art = TileRunner.checkerboard(30, 20, 60, 50);
        // every cell is an edge cell: the rule cannot rank any spot first, the spots are still used, each sneaking
        TileRunner r = holes(art, p -> true, true, true);
        assertEquals(List.of(), r.mismatches(art), r.report(art));
        assertEquals(0, r.failures, r.report(art));
        assertTrue(r.sim.spotGoals.size() > 0, r.report(art));
        assertTrue(r.sim.sneakTicks > 0, "centering sneaked");
        assertEquals(0, r.sim.fellOff, r.report(art));
        // without the sneak switch the centring does not sneak
        TileRunner off = holes(art, p -> true, true, false);
        assertEquals(0, off.sim.sneakTicks);
    }
}
