package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.engine.SimFakes.FakeCatalog;
import io.github.digitalisp.mapart.engine.SimFakes.FakeMaps;
import io.github.digitalisp.mapart.engine.SimFakes.FakeWorld;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TilePlanTest {
    private static final PlatformGeometry GEO = new PlatformGeometry(-64, -64, 64);

    @Test
    void matchesDelegatesToTheCatalog() {
        TilePlan plan = new TilePlan(new TileArt(), GEO, new FakeCatalog());
        BlockSpec plate = BlockSpec.of("minecraft:stone_pressure_plate", Map.of("powered", "false"));
        assertTrue(plan.matches(plate, BlockSpec.of("minecraft:stone_pressure_plate", Map.of("powered", "true"))));
        assertTrue(plan.matches(BlockSpec.of("minecraft:stone_pressure_plate"), plate), "target omits the property");
        BlockSpec log = BlockSpec.parse("minecraft:oak_log[axis=y]");
        assertFalse(plan.matches(log, BlockSpec.parse("minecraft:oak_log[axis=x]")));
        assertTrue(plan.matches(null, BlockSpec.AIR));
        assertFalse(plan.matches(null, BlockSpec.of("minecraft:red_carpet")));

        // a catalog that accepts anything non-air makes every non-air cell "correct"
        FakeCatalog lenient = new FakeCatalog() {
            @Override
            public boolean matches(BlockSpec target, BlockSpec actual) {
                return target == null ? actual.isAir() : !actual.isAir();
            }
        };
        TileArt art = new TileArt();
        art.set(0, 0, BlockSpec.of("minecraft:white_carpet"));
        FakeWorld w = new FakeWorld();
        w.set(GEO.cell(0, 0), BlockSpec.of("minecraft:black_carpet"));
        assertEquals(1, new TilePlan(art, GEO, lenient).survey(w).correct());
        assertEquals(0, new TilePlan(art, GEO, new FakeCatalog()).survey(w).correct());
    }

    @Test
    void volatilePropertiesDoNotMakeABlockWrong() {
        TileArt art = new TileArt();
        art.set(0, 0, BlockSpec.parse("minecraft:oak_leaves[distance=7,persistent=true,waterlogged=false]"));
        art.set(1, 0, BlockSpec.parse("minecraft:stone_pressure_plate[powered=false]"));
        TilePlan plan = new TilePlan(art, GEO, new FakeCatalog());
        FakeWorld w = new FakeWorld();
        w.set(GEO.cell(0, 0), BlockSpec.parse("minecraft:oak_leaves[distance=2,persistent=true,waterlogged=false]"));
        w.set(GEO.cell(1, 0), BlockSpec.parse("minecraft:stone_pressure_plate[powered=true]"));
        TilePlan.Survey s = plan.survey(w);
        assertTrue(s.complete(), s.toString());
        assertEquals(2, plan.correctCount(w));
        assertTrue(plan.remainingNeeds(w).isEmpty());
    }

    @Test
    void surveyClassifiesWrongMissingAndForeign() {
        TileArt art = new TileArt();
        art.set(0, 0, BlockSpec.of("minecraft:white_carpet"));
        art.set(1, 0, BlockSpec.of("minecraft:red_carpet"));
        art.set(2, 0, BlockSpec.of("minecraft:black_carpet"));
        TilePlan plan = new TilePlan(art, GEO, new FakeCatalog());
        FakeWorld w = new FakeWorld();
        assertTrue(plan.survey(w).clear());
        w.set(GEO.cell(0, 0), BlockSpec.of("minecraft:white_carpet"));      // correct
        w.set(GEO.cell(1, 0), BlockSpec.of("minecraft:black_carpet"));      // wrong
        w.set(GEO.cell(50, 50), BlockSpec.of("minecraft:white_terracotta")); // on an empty pixel
        TilePlan.Survey s = plan.survey(w);
        assertEquals(3, s.nonAir());
        assertEquals(1, s.correct());
        assertEquals(List.of(GEO.cell(1, 0), GEO.cell(50, 50)), s.wrong());
        assertEquals(List.of(GEO.cell(2, 0)), s.missing());
        assertEquals(1.0 / 3, s.matchRatio(), 1e-9);
        assertEquals(Map.of("minecraft:red_carpet", 1, "minecraft:black_carpet", 1), plan.remainingNeeds(w));
        assertEquals(List.of(GEO.cell(50, 50)), plan.nukeTargets(w, List.of("minecraft:white_carpet", "minecraft:black_carpet")));
        w.fluid.add(GEO.cell(5, 5));
        assertFalse(plan.survey(w).clear());
    }

    @Test
    void expectedMapSkipsEmptyPixels() {
        TileArt art = new TileArt();
        art.set(3, 4, BlockSpec.of("minecraft:white_carpet"));
        art.set(3, 3, BlockSpec.of("minecraft:red_carpet"));
        TilePlan plan = new TilePlan(art, GEO, new FakeCatalog());
        FakeWorld w = new FakeWorld();
        w.set(GEO.cell(3, 3), BlockSpec.of("minecraft:red_carpet"));
        w.set(GEO.cell(3, 4), BlockSpec.of("minecraft:white_carpet"));
        FakeMaps maps = new FakeMaps();
        TilePlan.ExpectedMap e = plan.expectedMap(new FakeCatalog(), maps, w);
        byte[] actual = new byte[TilePlan.CELLS];
        actual[4 * 128 + 3] = maps.expectedPacked(8);                        // carpet north of it: normal shade
        actual[3 * 128 + 3] = (byte) ((maps.expectedPacked(28) & 0xFC) | 2);  // floor north of it: high shade
        actual[0] = 77; // floor shading on an empty pixel is ignored
        assertEquals(0, e.mismatches(actual));
        actual[4 * 128 + 3] = 1;
        assertEquals(1, e.mismatches(actual));
        assertEquals(77, e.forDiff(actual)[0]);
    }

    @Test
    void firstRowIsShadedAgainstTheNooblineRow() {
        TileArt art = new TileArt();
        art.set(0, 0, BlockSpec.of("minecraft:white_carpet"));
        art.set(1, 0, BlockSpec.of("minecraft:white_carpet"));
        TilePlan plan = new TilePlan(art, GEO, new FakeCatalog());
        FakeWorld w = new FakeWorld();
        w.set(GEO.cell(0, 0).offset(0, 0, -1), BlockSpec.of("minecraft:cobblestone")); // noobline only above x=0
        FakeMaps maps = new FakeMaps();
        TilePlan.ExpectedMap e = plan.expectedMap(new FakeCatalog(), maps, w);
        assertEquals(TilePlan.NORMAL, e.packed()[0] & 3);
        assertEquals(TilePlan.HIGH, e.packed()[1] & 3);
    }
}
