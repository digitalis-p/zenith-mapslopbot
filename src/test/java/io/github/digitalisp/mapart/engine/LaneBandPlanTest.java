package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.coord.TileUnitSpec;
import io.github.digitalisp.mapart.engine.SimFakes.FakeCatalog;
import io.github.digitalisp.mapart.engine.SimFakes.FakeWorld;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.SerpentineBuildPlanner;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A lane band's plan is the tile's plan filtered to the band's cells. */
class LaneBandPlanTest {
    private static final PlatformGeometry GEO = new PlatformGeometry(-64, -64, 64);
    private static final BlockSpec STONE = BlockSpec.of("minecraft:stone");

    @Test
    void aBandsPlanLooksAtItsCellsOnlyAndItsLanesStayInside() {
        FakeCatalog catalog = new FakeCatalog();
        TileArt art = Sim.stripes(10, 2);
        TileUnitSpec.Band band = new TileUnitSpec.Band(2, 3, 18, 35, 0, 127);
        TilePlan whole = new TilePlan(art, GEO, catalog, Set.of());
        TilePlan plan = new TilePlan(art, GEO, catalog, Set.of(), band);
        assertEquals(128 * 12, whole.total);
        assertEquals(18 * 12, plan.total, "the band's cells");
        assertEquals(18 * 12, plan.allNeeds().values().stream().mapToInt(Integer::intValue).sum());

        FakeWorld w = new FakeWorld();
        w.set(GEO.cell(0, 0), STONE);               // foreign, outside
        w.set(GEO.cell(100, 60), STONE);            // on an empty pixel, outside
        w.set(GEO.cell(50, 3), art.get(50, 3));     // another band's build
        TilePlan.Survey s = plan.survey(w);
        assertEquals(0, s.nonAir(), "nothing outside the band is looked at");
        assertTrue(s.wrong().isEmpty());
        assertEquals(18 * 12, s.missing().size());
        assertTrue(plan.nukeTargets(w, Set.of()).isEmpty(), "and nothing outside is a target");
        w.set(GEO.cell(20, 1), STONE);              // wrong, inside
        w.set(GEO.cell(30, 70), STONE);             // on an empty pixel, inside
        w.set(GEO.cell(25, 2), art.get(25, 2));     // built, inside
        s = plan.survey(w);
        assertEquals(3, s.nonAir());
        assertEquals(1, s.correct());
        assertEquals(2, s.wrong().size());
        assertEquals(1, plan.correctCount(w));
        assertEquals(18 * 12 - 1, plan.remainingNeeds(w).values().stream().mapToInt(Integer::intValue).sum());

        assertNull(plan.placement(GEO.cell(50, 3)), "outside the band: never placed (no later-lane airplace either)");
        assertNotNull(plan.placement(GEO.cell(25, 2)));
        assertTrue(plan.outside(GEO.cell(17, 0)) && !plan.outside(GEO.cell(18, 0)) && !plan.outside(GEO.cell(35, 127))
            && plan.outside(GEO.cell(36, 0)));

        // the planner's lanes, clipped to the band: lanes 2 and 3 of the 9-wide airplace lanes, indexes kept
        List<Lane> lanes = new SerpentineBuildPlanner().plan(art, GEO, catalog, 4, plan.notBuildableIn(w));
        assertEquals(List.of(2, 3), lanes.stream().map(Lane::index).toList());
        int cells = 0;
        for (Lane l : lanes)
            for (Placement p : l.placements()) {
                int px = p.pos().x() - GEO.minX();
                assertTrue(px >= 18 && px <= 35, "inside the band: " + px);
                cells++;
            }
        assertEquals(18 * 12 - 2, cells, "every buildable cell of the band (the built one and the wrong one left out)");
        // a narrower geometry than the band's (the bot's own lane geometry): clipped, nothing outside, nothing lost
        lanes = new SerpentineBuildPlanner().plan(art, GEO, catalog, 2, plan.doneIn(w));
        int clipped = 0;
        for (Lane l : lanes)
            for (Placement p : l.placements()) {
                int px = p.pos().x() - GEO.minX();
                assertTrue(px >= 18 && px <= 35, "inside the band: " + px);
                clipped++;
            }
        assertEquals(18 * 12 - 1, clipped);
        assertEquals(Map.of(), new TilePlan(new TileArt(), GEO, catalog, Set.of(), band).allNeeds(), "an empty band");
    }
}
