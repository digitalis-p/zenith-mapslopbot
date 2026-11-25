package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.FakeCatalog;
import io.github.digitalisp.mapart.testutil.Fixtures;
import io.github.digitalisp.mapart.testutil.Tiles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerpentineBuildPlannerTest {
    private static final PlatformGeometry GEO = new PlatformGeometry(-64, 1984, 100);

    private final SerpentineBuildPlanner planner = new SerpentineBuildPlanner();
    private final FakeCatalog catalog = new FakeCatalog();

    private List<Lane> plan(TileArt art, int w) {
        return planner.plan(art, GEO, catalog, w, p -> false);
    }

    private static int px(Pos p) {
        return p.x() - GEO.minX();
    }

    private static int pz(Pos p) {
        return p.z() - GEO.minZ();
    }

    @Test
    void everyPixelIsPlacedExactlyOnce() throws Exception {
        TileArt art = new DefaultSubmissionService().parse(List.of(Fixtures.file(Fixtures.G)))
            .tiles().values().iterator().next();
        List<Lane> lanes = plan(art, 2);
        Set<Pos> seen = new HashSet<>();
        int n = 0;
        for (Lane l : lanes)
            for (Placement p : l.placements()) {
                n++;
                assertTrue(seen.add(p.pos()), "placed twice: " + p.pos());
                assertEquals(art.get(px(p.pos()), pz(p.pos())), p.block());
                assertEquals(GEO.artY(), p.pos().y());
                assertEquals(p.block().id(), p.itemId());
            }
        assertEquals(art.nonEmptyCount(), n);
    }

    @Test
    void lanesAreSerpentineAndOrderedFromTheCentreLine() {
        List<Lane> lanes = plan(Tiles.filled("minecraft:white_carpet"), 2);
        assertEquals(26, lanes.size());
        for (int k = 0; k < lanes.size(); k++) {
            Lane l = lanes.get(k);
            assertEquals(k, l.index());
            int lo = 5 * k, hi = Math.min(127, lo + 4), c = Math.min(127, lo + 2);
            boolean south = k % 2 == 0;
            assertEquals((hi - lo + 1) * 128, l.placements().size(), "lane " + k);
            Placement prev = null;
            for (Placement p : l.placements()) {
                int x = px(p.pos());
                assertTrue(x >= lo && x <= hi, "lane " + k + " x " + x);
                if (prev != null) {
                    int dz = pz(p.pos()) - pz(prev.pos());
                    if (dz == 0) {
                        int dPrev = Math.abs(px(prev.pos()) - c), dCur = Math.abs(x - c);
                        assertTrue(dCur > dPrev || (dCur == dPrev && x > px(prev.pos())), "row order in lane " + k);
                    } else {
                        assertEquals(south ? 1 : -1, dz, "walking direction in lane " + k);
                    }
                }
                prev = p;
            }
            assertEquals(c, px(l.from()));
            assertEquals(c, px(l.to()));
            assertEquals(GEO.artY(), l.from().y());
            assertEquals(south ? 0 : 127, pz(l.from()), "from clamped inside the tile");
            assertEquals(south ? 127 : 0, pz(l.to()));
        }
        List<Placement> first = lanes.getFirst().placements();
        assertEquals(List.of(2, 1, 3, 0, 4), first.subList(0, 5).stream().map(p -> px(p.pos())).toList());
        assertEquals(List.of(125, 126, 127), lanes.getLast().placements().subList(0, 3).stream()
            .map(p -> px(p.pos())).sorted().toList());
    }

    @Test
    void fromAndToSitJustOutsideTheFirstAndLastRows() {
        TileArt art = Tiles.of((x, z) -> z >= 10 && z <= 20 ? "minecraft:stone" : null);
        List<Lane> lanes = plan(art, 2);
        assertEquals(GEO.cell(2, 9), lanes.get(0).from());
        assertEquals(GEO.cell(2, 21), lanes.get(0).to());
        assertEquals(GEO.cell(7, 21), lanes.get(1).from());
        assertEquals(GEO.cell(7, 9), lanes.get(1).to());
        assertEquals(10, pz(lanes.get(1).placements().getLast().pos()));
    }

    @Test
    void laneWidthIsClampedAtTheTileEdge() {
        List<Lane> w3 = plan(Tiles.filled("minecraft:stone"), 3);
        assertEquals(19, w3.size());
        Lane last = w3.getLast();
        assertEquals(18, last.index());
        assertEquals(127, px(last.from()), "center 129 clamped to 127");
        assertEquals(2 * 128, last.placements().size());
        assertEquals(Set.of(126, 127), Set.copyOf(last.placements().stream().map(p -> px(p.pos())).toList()));

        List<Lane> w0 = plan(Tiles.filled("minecraft:stone"), 0);
        assertEquals(128, w0.size());
        assertEquals(128, w0.get(5).placements().size());
        assertEquals(5, px(w0.get(5).from()));
        assertEquals(128, plan(Tiles.filled("minecraft:stone"), -4).size(), "negative width acts as 0");

        List<Lane> wide = plan(Tiles.filled("minecraft:stone"), 100);
        assertEquals(1, wide.size());
        assertEquals(100, px(wide.getFirst().from()), "center w stays inside the tile");
        assertEquals(16384, wide.getFirst().placements().size());
    }

    @Test
    void emptyLanesAreOmittedAndDirectionsStillAlternate() {
        TileArt art = Tiles.of((x, z) -> x < 5 || (x >= 10 && x < 15) ? "minecraft:stone" : null);
        List<Lane> lanes = plan(art, 2);
        assertEquals(2, lanes.size());
        assertEquals(0, lanes.get(0).index());
        assertEquals(2, lanes.get(1).index());
        assertEquals(0, pz(lanes.get(0).placements().getFirst().pos()));
        assertEquals(127, pz(lanes.get(1).placements().getFirst().pos()), "second emitted lane walks north");
    }

    @Test
    void donePlacementsAreSkipped() {
        TileArt art = Tiles.filled("minecraft:stone");
        List<Lane> lanes = planner.plan(art, GEO, catalog, 2, p -> pz(p.pos()) < 64);
        int n = 0;
        for (Lane l : lanes)
            for (Placement p : l.placements()) {
                assertTrue(pz(p.pos()) >= 64);
                n++;
            }
        assertEquals(8192, n);
        assertEquals(GEO.cell(2, 63), lanes.getFirst().from());
        assertEquals(List.of(), planner.plan(art, GEO, catalog, 2, p -> true));
        assertEquals(List.of(), plan(new TileArt(), 2));
    }

    /**
     * {@code build.laneEvenWidths}: at every half width the same number of lanes share the 128 columns, each a run of
     * columns next to the one before, widths at most 2w+1 and at most one apart, the walking line w east of the lane's
     * west column and no cell farther than w from it; every cell once, serpentine as before.
     */
    @Test
    void evenLanesShareTheColumnsWithTheSameLaneCount() {
        SerpentineBuildPlanner even = new SerpentineBuildPlanner(() -> true);
        TileArt art = Tiles.filled("minecraft:white_carpet");
        for (int w : new int[] {0, 1, 2, 3, 4, 5, 6, 7, 10, 20, 63, 64, 100}) {
            List<Lane> lanes = even.plan(art, GEO, catalog, w, p -> false);
            assertEquals(plan(art, w).size(), lanes.size(), "w " + w + ": lane count");
            int next = 0;
            int minWidth = Integer.MAX_VALUE, maxWidth = 0;
            Set<Pos> seen = new HashSet<>();
            for (int k = 0; k < lanes.size(); k++) {
                Lane l = lanes.get(k);
                assertEquals(k, l.index());
                int lo = l.placements().stream().mapToInt(p -> px(p.pos())).min().orElseThrow();
                int hi = l.placements().stream().mapToInt(p -> px(p.pos())).max().orElseThrow();
                int c = px(l.from());
                assertEquals(next, lo, "w " + w + " lane " + k + " starts where the last ended");
                assertEquals(c, px(l.to()));
                assertEquals(Math.min(hi, lo + w), c, "w " + w + " lane " + k + ": walking line");
                assertTrue(hi - c <= w && c - lo <= w, "w " + w + " lane " + k + ": a cell beyond w");
                assertEquals((hi - lo + 1) * 128, l.placements().size());
                for (Placement p : l.placements()) assertTrue(seen.add(p.pos()), "placed twice: " + p.pos());
                assertEquals(k % 2 == 0 ? 0 : 127, pz(l.placements().getFirst().pos()), "serpentine");
                minWidth = Math.min(minWidth, hi - lo + 1);
                maxWidth = Math.max(maxWidth, hi - lo + 1);
                next = hi + 1;
            }
            assertEquals(128, next, "w " + w + ": every column");
            assertEquals(16384, seen.size());
            assertTrue(maxWidth <= 2 * w + 1, "w " + w + ": width " + maxWidth);
            assertTrue(maxWidth - minWidth <= 1, "w " + w + ": widths " + minWidth + ".." + maxWidth);
        }
        // the airplace lanes: eight of 9, seven of 8, the last walked 4 in from the east edge (off: 14 of 9 and 2)
        List<Integer> widths = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        for (Lane l : even.plan(art, GEO, catalog, 4, p -> false)) {
            widths.add(l.placements().size() / 128);
            lines.add(px(l.from()));
        }
        assertEquals(List.of(9, 9, 9, 9, 9, 9, 9, 9, 8, 8, 8, 8, 8, 8, 8), widths);
        assertEquals(List.of(4, 13, 22, 31, 40, 49, 58, 67, 76, 84, 92, 100, 108, 116, 124), lines);
        assertEquals(List.of(9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 2),
            plan(art, 4).stream().map(l -> l.placements().size() / 128).toList(), "off: as before");
    }

    @Test
    void evenLanesOffIsThePlannerAsBeforeAndTheSwitchIsReadAtEveryPlan() throws Exception {
        TileArt art = new DefaultSubmissionService().parse(List.of(Fixtures.file(Fixtures.G)))
            .tiles().values().iterator().next();
        AtomicBoolean on = new AtomicBoolean(false);
        SerpentineBuildPlanner switched = new SerpentineBuildPlanner(on::get);
        for (int w : new int[] {2, 4}) {
            assertEquals(plan(art, w), switched.plan(art, GEO, catalog, w, p -> false), "w " + w);
        }
        on.set(true);
        List<Lane> even = switched.plan(art, GEO, catalog, 4, p -> false);
        assertTrue(!even.equals(plan(art, 4)), "switched on at the next plan");
        Set<Pos> seen = new HashSet<>();
        for (Lane l : even)
            for (Placement p : l.placements()) {
                assertTrue(seen.add(p.pos()));
                assertEquals(art.get(px(p.pos()), pz(p.pos())), p.block());
            }
        assertEquals(art.nonEmptyCount(), seen.size());
        on.set(false);
        assertEquals(plan(art, 4), switched.plan(art, GEO, catalog, 4, p -> false));
    }

    @Test
    void itemIdsComeFromTheCatalog() {
        List<Lane> lanes = plan(Tiles.of((x, z) -> x == 0 && z == 0 ? "minecraft:redstone_wire" : null), 2);
        Placement p = lanes.getFirst().placements().getFirst();
        assertEquals("minecraft:redstone", p.itemId());
        assertEquals(new Pos(-64, 100, 1984), p.pos());
    }
}
