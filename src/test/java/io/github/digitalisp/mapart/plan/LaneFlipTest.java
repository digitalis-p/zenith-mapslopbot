package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.FakeCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LaneFlip}: a lane walked the other way, for {@code build.restockLaneFlip}. */
class LaneFlipTest {
    private static final PlatformGeometry GEO = new PlatformGeometry(-64, -64, 64);
    private static final List<String> SIX = List.of("minecraft:white_carpet", "minecraft:red_carpet",
        "minecraft:black_carpet", "minecraft:lime_carpet", "minecraft:blue_carpet", "minecraft:yellow_carpet");

    /** The serpentine planner's lanes of a dithered tile with holes (9-wide airplace lanes, even widths). */
    private static List<Lane> lanes(long seed) {
        Random r = new Random(seed);
        TileArt a = new TileArt();
        for (int pz = 0; pz < 128; pz++)
            for (int px = 0; px < 128; px++)
                if (r.nextInt(10) > 0) a.set(px, pz, BlockSpec.of(SIX.get(r.nextInt(SIX.size()))));
        return new SerpentineBuildPlanner(() -> true).plan(a, GEO, new FakeCatalog(), 4, x -> false);
    }

    @Test
    void aFlippedLaneWalksItsRowsBackwardsAndKeepsEachRowsOrder() {
        for (Lane lane : lanes(3)) {
            Lane f = LaneFlip.flip(lane);
            assertEquals(lane.index(), f.index());
            assertEquals(lane.from(), f.to());
            assertEquals(lane.to(), f.from());
            assertEquals(lane.placements().size(), f.placements().size());
            assertEquals(new java.util.HashSet<>(lane.placements()), new java.util.HashSet<>(f.placements()));
            // rows in the flipped walk order, each row's cells in the lane's own order
            int step = Integer.signum(f.to().z() - f.from().z());
            List<Placement> ps = f.placements();
            for (int i = 1; i < ps.size(); i++)
                assertTrue(Integer.signum(ps.get(i).pos().z() - ps.get(i - 1).pos().z()) != -step, "row order");
            Map<Integer, List<Placement>> rowsA = rows(lane.placements());
            Map<Integer, List<Placement>> rowsB = rows(f.placements());
            assertEquals(rowsA, rowsB, "each row's cells in the same order");
            assertEquals(lane, LaneFlip.flip(f), "flipped twice: the lane");
        }
    }

    private static Map<Integer, List<Placement>> rows(List<Placement> ps) {
        Map<Integer, List<Placement>> out = new java.util.TreeMap<>();
        for (Placement p : ps) out.computeIfAbsent(p.pos().z(), z -> new ArrayList<>()).add(p);
        return out;
    }

    @Test
    void theLanesOfAPlanThatIsNotFlippedAreTheLanes() {
        List<Lane> lanes = lanes(5);
        assertSame(lanes, LaneFlip.walked(lanes, null));
        assertSame(lanes, LaneFlip.walked(lanes, new RestockPlanner.Plan(Map.of(), 1, 0, true, 1, 1.0)));
        assertEquals(lanes.getFirst().from(), LaneFlip.start(lanes, null));
        RestockPlanner.Plan up = new RestockPlanner.Plan(Map.of(), 1, 0, true, 1, 1.0, true);
        List<Lane> f = LaneFlip.walked(lanes, up);
        assertEquals(lanes.size(), f.size());
        for (int i = 0; i < lanes.size(); i++) assertEquals(LaneFlip.flip(lanes.get(i)), f.get(i));
        assertEquals(lanes.getFirst().to(), LaneFlip.start(lanes, up));
        assertEquals(f.getFirst().from(), LaneFlip.start(lanes, up));
    }
}
