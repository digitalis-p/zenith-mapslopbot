package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.FakeCatalog;
import io.github.digitalisp.mapart.testutil.Fixtures;
import io.github.digitalisp.mapart.testutil.SyntheticArt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slot demand of whole lanes and of adjacent lane pairs (re-derived for the
 * lanes the engine plans now): the material slots a load needs to carry them, Σ ceil(n_k / stack), at AIRPLACE's 9-wide
 * and LOOK's 5-wide lanes. The roadmap's two-lane rule needs a pair to fit in one load (31 slots with the trimmed kit);
 * at 9 wide a pair is 2,304 cells, more than 31 stacks of 64 hold, so on a full tile it never fits.
 */
class LaneSlotDemandTest {
    private static final PlatformGeometry GEO = new PlatformGeometry(0, 0, 64);
    private static final int SLOTS = 31;

    record Demand(List<Integer> lanes, List<Integer> pairs, List<Integer> items, List<Integer> cells) {
        static int max(List<Integer> l) {
            return l.stream().mapToInt(Integer::intValue).max().orElse(0);
        }

        static double mean(List<Integer> l) {
            return l.stream().mapToInt(Integer::intValue).average().orElse(0);
        }

        long fitting(List<Integer> l) {
            return l.stream().filter(n -> n <= SLOTS).count();
        }
    }

    private static Map<String, Integer> counts(Lane lane) {
        Map<String, Integer> m = new HashMap<>();
        for (Placement p : lane.placements()) if (p.itemId() != null) m.merge(p.itemId(), 1, Integer::sum);
        return m;
    }

    private static int slots(Map<String, Integer> counts, FakeCatalog catalog) {
        int n = 0;
        for (var e : counts.entrySet()) {
            int s = Math.max(1, catalog.maxStackSize(e.getKey()));
            n += (e.getValue() + s - 1) / s;
        }
        return n;
    }

    static Demand demand(TileArt art, int halfWidth) {
        FakeCatalog catalog = new FakeCatalog();
        List<Lane> lanes = new SerpentineBuildPlanner().plan(art, GEO, catalog, halfWidth, p -> false);
        List<Integer> perLane = new ArrayList<>();
        List<Integer> perPair = new ArrayList<>();
        List<Integer> items = new ArrayList<>();
        List<Integer> cells = new ArrayList<>();
        for (int i = 0; i < lanes.size(); i++) {
            Map<String, Integer> c = counts(lanes.get(i));
            perLane.add(slots(c, catalog));
            items.add(c.size());
            cells.add(lanes.get(i).placements().size());
            if (i % 2 == 0 && i + 1 < lanes.size()) {
                Map<String, Integer> pair = new HashMap<>(c);
                counts(lanes.get(i + 1)).forEach((k, v) -> pair.merge(k, v, Integer::sum));
                perPair.add(slots(pair, catalog));
            }
        }
        return new Demand(perLane, perPair, items, cells);
    }

    private static String row(String name, String width, Demand d) {
        return String.format("| %s | %s | %d | %.1f | %.1f / %d | %d of %d | %s | %.1f / %d | %d of %d | %s |", name, width,
            d.lanes().size(), Demand.mean(d.items()), Demand.mean(d.lanes()), Demand.max(d.lanes()), d.fitting(d.lanes()),
            d.lanes().size(), d.lanes(), Demand.mean(d.pairs()), Demand.max(d.pairs()), d.fitting(d.pairs()),
            d.pairs().size(), d.pairs());
    }

    @Test
    void slotDemandPerLaneAndLanePair() throws Exception {
        Map<String, TileArt> arts = new TreeMap<>();
        arts.put("1 carpet 16 (synthetic)", SyntheticArt.carpet16(1));
        arts.put("2 dithered 6", SyntheticArt.dithered6(42));
        for (String f : List.of("sizedreisen/0006_sizedreisen_1_1.nbt", "sizedreisen/0003_sizedreisen_2_0.nbt")) {
            String name = f.substring(f.lastIndexOf('_') - 1, f.lastIndexOf('.'));
            TileArt art = new DefaultSubmissionService().parse(List.of(Fixtures.renamed(f, "mixed-" + name + ".nbt")))
                .tiles().values().iterator().next();
            arts.put("3 mixed sizedreisen " + name, art);
        }
        StringBuilder sb = new StringBuilder("\n| Art | Lanes | n | Items per lane | Slots per lane mean / max | Lanes ≤ "
            + SLOTS + " | Per lane | Slots per pair mean / max | Pairs ≤ " + SLOTS + " | Per pair |\n"
            + "|---|---|---:|---:|---:|---:|---|---:|---:|---|\n");
        for (var e : arts.entrySet()) {
            Demand air = demand(e.getValue(), 4);
            Demand look = demand(e.getValue(), 2);
            sb.append(row(e.getKey(), "AIRPLACE 9", air)).append('\n');
            sb.append(row(e.getKey(), "LOOK 5", look)).append('\n');
            assertTrue(air.lanes().size() <= 15 && look.lanes().size() <= 26, e.getKey());
            int maxPairCells = 0;
            for (int i = 0; i + 1 < air.cells().size(); i += 2) maxPairCells = Math.max(maxPairCells, air.cells().get(i) + air.cells().get(i + 1));
            if (maxPairCells == 2 * 1152) {
                assertTrue(Demand.max(air.pairs()) > SLOTS, "a full 9-wide pair (2,304 cells) never fits 31 stacks of 64");
            }
        }
        System.out.println("lane slot demand (" + SLOTS + " material slots):" + sb);
    }
}
