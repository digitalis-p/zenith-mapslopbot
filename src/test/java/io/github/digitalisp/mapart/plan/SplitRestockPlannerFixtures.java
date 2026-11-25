package io.github.digitalisp.mapart.plan;

import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.plan.RestockPlanner.Request;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/** Hand-built lanes and requests for the {@link SplitRestockPlanner} tests. */
final class SplitRestockPlannerFixtures {
    private SplitRestockPlannerFixtures() { }

    static final ToIntFunction<String> STACK64 = id -> 64;

    /**
     * A lane at x {@code centre}; {@code rows[q]} are the items of row z = q (null = no item). Walked N→S (z
     * ascending) when {@code south}, else S→N; within a row the placements go centre, west, east, west 2, ...
     */
    static Lane lane(int index, int centre, boolean south, String[]... rows) {
        List<Placement> ps = new ArrayList<>();
        int n = rows.length;
        for (int q = 0; q < n; q++) {
            int z = south ? q : n - 1 - q;
            String[] row = rows[z];
            for (int c = 0; c < row.length; c++) {
                int dx = c == 0 ? 0 : (c % 2 == 1 ? -(c + 1) / 2 : c / 2);
                ps.add(new Placement(new Pos(centre + dx, 0, z), BlockSpec.of("minecraft:stone"), row[c]));
            }
        }
        int step = south ? 1 : -1;
        int first = south ? 0 : n - 1;
        int last = south ? n - 1 : 0;
        return new Lane(index, new Pos(centre, 0, first - step), new Pos(centre, 0, last + step), ps);
    }

    /** {@code n} copies of {@code item}. */
    static String[] times(int n, String item) {
        String[] a = new String[n];
        Arrays.fill(a, item);
        return a;
    }

    static String[] concat(String[]... parts) {
        List<String> l = new ArrayList<>();
        for (String[] p : parts) l.addAll(Arrays.asList(p));
        return l.toArray(new String[0]);
    }

    /** {@code rows} rows of the same items. */
    static String[][] rows(int rows, String... items) {
        String[][] r = new String[rows][];
        for (int q = 0; q < rows; q++) r[q] = items.clone();
        return r;
    }

    /** Serpentine lanes (N→S, S→N, ...) three blocks apart, each from {@code rows}. */
    static List<Lane> serpentine(String[][]... lanes) {
        List<Lane> out = new ArrayList<>();
        for (int j = 0; j < lanes.length; j++) out.add(lane(j, 1 + 3 * j, j % 2 == 0, lanes[j]));
        return out;
    }

    /** No site, no reserve, the given stacks. */
    static Request request(List<Lane> lanes, Map<String, Integer> held, int free, boolean topUp, ToIntFunction<String> stack) {
        return new Request(lanes, held, Map.of(), free, stack, 0, topUp, null);
    }

    static int placements(List<Lane> lanes) {
        int n = 0;
        for (Lane l : lanes) n += l.placements().size();
        return n;
    }
}
