package io.github.digitalisp.mapart.plan;

import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * A lane band of short runs (live 2026-10-31, map "haruhi", lanes 6-7) and the partial stacks carried for further on,
 * for the crowded-load and carried-stacks tests.
 */
final class CrowdedBandFixtures {
    private CrowdedBandFixtures() { }

    /**
     * 9-wide serpentine lanes of {@code rows[j]} rows each, in runs of 1 to {@code maxRun} whole rows of one of 40 items
     * ("i0".."i39").
     */
    static List<Lane> band(long seed, int maxRun, int... rows) {
        Random r = new Random(seed);
        String[][][] lanes = new String[rows.length][][];
        for (int j = 0; j < rows.length; j++) {
            String[][] rs = new String[rows[j]][];
            int z = 0;
            while (z < rows[j]) {
                int run = 1 + r.nextInt(maxRun);
                String id = "i" + r.nextInt(40);
                for (int q = z; q < Math.min(rows[j], z + run); q++) rs[q] = SplitRestockPlannerFixtures.times(9, id);
                z += run;
            }
            lanes[j] = rs;
        }
        return SplitRestockPlannerFixtures.serpentine(lanes);
    }

    /** The items of {@code lanes} in the order they are first used. */
    static List<String> firstUseOrder(List<Lane> lanes) {
        Map<String, Integer> first = new LinkedHashMap<>();
        int i = 0;
        for (Lane l : lanes) for (Placement p : l.placements()) first.putIfAbsent(p.itemId(), i++);
        return new ArrayList<>(first.keySet());
    }

    /** Partial stacks (20-59) of the items first used from the {@code from}-th on, at most {@code n} of them. */
    static Map<String, Integer> carried(List<Lane> lanes, int from, int n) {
        List<String> order = firstUseOrder(lanes);
        Map<String, Integer> held = new TreeMap<>();
        for (int k = from; k < Math.min(order.size(), from + n); k++) held.put(order.get(k), 20 + (k * 7) % 40);
        return held;
    }

    /** 25 material slots, the carried stacks in compact slots, top-up, a site with every depot at one spot. */
    static RestockPlanner.Request request(List<Lane> lanes, Map<String, Integer> held) {
        RestockPlanner.Site site = new RestockPlanner.Site(new Pos(0, 0, -20), id -> new Pos(-10, 0, -30),
            RestockPlanner.Costs.DEFAULT);
        return new RestockPlanner.Request(lanes, held, Map.of(), 25 - held.size(), id -> 64, 0, true, site);
    }
}
