package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.SuppressionArt;
import io.github.digitalisp.mapart.testutil.Fixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The time model of a colour-suppression print (study of 2026-11-14), on the
 * real export {@code full1214-suppress_crubtech.nbt}: what each pass holds, how its 9-wide lanes fill, how many loads a
 * pass needs, and what the print costs with the passes as they are and combined. The constants are fitted to the first
 * live prints (the four main passes of the third map, each within 5%). Prints its table; asserts the facts the page cites.
 */
class CsPrintModelTest {
    /** Lane walk, blocks per second (walking keys, no sprint; no Soul Speed on glass). */
    static final double WALK = 4.317;
    /** Blocks per second while a lane places (live: 13-20). */
    static final double RATE = 16;
    /** Seconds between two lanes (the walk to the next start, the pacer's fresh clock). */
    static final double LANE_CHANGE = 6;
    /** Seconds of one restock round: the sweep (live 20-45 s), the legs to the chests and back, the leftovers chest. */
    static final double ROUND_TOP = 49, ROUND_BOTTOM = 67;
    /** Seconds of a noobline pass's own round (one or two chests; live 11-47 s) and of each of its one-row lanes. */
    static final double ROUND_ROW = 30, ROW_LANE = 2.6;
    /** Material slots of a load (36 less the kit, {@code build.reservedSlots}). */
    static final int SLOTS = 31;
    /** Blocks a load carried on the live prints (500-950). */
    static final int LOAD = 740;
    static final int LANE = 9;

    /** One level of one layer as lanes see it: its cells' items by column and row (row 0 = the noobline when merged). */
    record Sheet(String name, int layer, String[][] item, int rows) {
        int cells() {
            int n = 0;
            for (String[] col : item) for (String s : col) if (s != null) n++;
            return n;
        }
    }

    static SuppressionArt full() throws Exception {
        MapArtConfig.SiteCfg site = new MapArtConfig.SiteCfg();
        MapArtConfig.PlatformCfg p = new MapArtConfig.PlatformCfg();
        p.id = "cs";
        p.kind = "COLOR_SUPPRESSION";
        p.glassY = List.of(200, 214);
        site.platforms.add(p);
        return new DefaultSubmissionService().parse(List.of(Fixtures.file("png2nbt/full1214-suppress_crubtech.nbt")),
            site).suppression();
    }

    /** Rows {@code from..to} (the noobline is -1) of the given levels of a layer. No column holds both levels. */
    static Sheet sheet(SuppressionArt a, String name, int layer, int[] levels, int from, int to) {
        String[][] item = new String[SuppressionArt.COLS][to - from + 1];
        for (int col = 0; col < SuppressionArt.COLS; col++)
            for (int row = from; row <= to; row++)
                for (int level : levels) {
                    BlockSpec b = a.get(layer, level, col, row);
                    if (b != null) item[col][row - from] = b.id();
                }
        return new Sheet(name, layer, item, to - from + 1);
    }

    /** Slots for the cells of lanes {@code first..last}: whole stacks per item. */
    static int slots(Sheet s, int first, int last) {
        Map<String, Integer> n = new TreeMap<>();
        for (int col = first * LANE; col < Math.min(SuppressionArt.COLS, (last + 1) * LANE); col++)
            for (String it : s.item[col]) if (it != null) n.merge(it, 1, Integer::sum);
        int slots = 0;
        for (int c : n.values()) slots += (c + 63) / 64;
        return slots;
    }

    static int laneCells(Sheet s, int lane) {
        int n = 0;
        for (int col = lane * LANE; col < Math.min(SuppressionArt.COLS, (lane + 1) * LANE); col++)
            for (String it : s.item[col]) if (it != null) n++;
        return n;
    }

    /**
     * Loads of a pass and its lane visits: the live prints carried 500-950 blocks a load (one dense lane, or two to
     * three thinner ones: 14-25 materials fill the slots with part stacks long before 31 x 64 blocks), so a pass takes
     * its cells / {@link #LOAD} rounds, and a lane is entered once more for every load beyond one a lane.
     */
    static int[] loads(Sheet s) {
        int loads = (s.cells() + LOAD - 1) / LOAD;
        return new int[] {loads, Math.max(loads, lanesWithCells(s))};
    }

    /** Slots the fullest lane of a pass needs for its own cells (whole stacks per item). */
    static int fullestLaneSlots(Sheet s) {
        int most = 0;
        for (int k = 0; k < (SuppressionArt.COLS + LANE - 1) / LANE; k++) most = Math.max(most, slots(s, k, k));
        return most;
    }

    /** Seconds of a pass: its lanes (walk or click bound, whichever is longer) and its restock rounds. */
    static double[] seconds(Sheet s) {
        int lanes = (SuppressionArt.COLS + LANE - 1) / LANE;
        double lane = 0;
        for (int k = 0; k < lanes; k++) {
            int n = laneCells(s, k);
            if (n == 0) continue;
            lane += s.rows == 1 ? ROW_LANE : Math.max(SuppressionArt.COLS / WALK, n / RATE) + LANE_CHANGE;
        }
        if (s.rows == 1) return new double[] {lane + 12, ROUND_ROW}; // 12 s: the walk to the row's first lane
        int[] l = loads(s);
        // a cut lane is walked again to its cut: half a lane on average
        lane += (l[1] - lanesWithCells(s)) * (SuppressionArt.COLS / WALK / 2 + LANE_CHANGE);
        double round = l[0] * (s.layer == SuppressionArt.TOP ? ROUND_TOP : ROUND_BOTTOM);
        return new double[] {lane, round};
    }

    static int lanesWithCells(Sheet s) {
        int n = 0;
        for (int k = 0; k < (SuppressionArt.COLS + LANE - 1) / LANE; k++) if (laneCells(s, k) > 0) n++;
        return n;
    }

    static double report(String title, List<Sheet> sheets, StringBuilder out) {
        double total = 0, lanes = 0, rounds = 0;
        out.append(title).append('\n');
        for (Sheet s : sheets) {
            double[] t = seconds(s);
            int[] l = loads(s);
            out.append(String.format(Locale.ROOT, "  %-28s %6d cells  %2d lanes  %2d loads  fullest lane %2d of %d slots  lanes %5.1f min  restock %5.1f min%n",
                s.name, s.cells(), lanesWithCells(s), l[0], fullestLaneSlots(s), SLOTS, t[0] / 60, t[1] / 60));
            lanes += t[0];
            rounds += t[1];
        }
        total = lanes + rounds;
        out.append(String.format(Locale.ROOT, "  total %.1f min (lanes %.1f, restock %.1f)%n", total / 60, lanes / 60, rounds / 60));
        return total;
    }

    @Test
    void whatThePassesCostAndWhatCombiningThemSaves() throws Exception {
        SuppressionArt a = full();
        StringBuilder out = new StringBuilder();
        int[] l0 = {0}, l1 = {1}, both = {0, 1};
        List<Sheet> now = new ArrayList<>(), noob = new ArrayList<>(), levels = new ArrayList<>();
        for (int layer : new int[] {SuppressionArt.TOP, SuppressionArt.BOTTOM}) {
            String n = layer == SuppressionArt.TOP ? "top" : "bottom";
            now.add(sheet(a, n + " y+0 noobline", layer, l0, -1, -1));
            now.add(sheet(a, n + " y+0", layer, l0, 0, 127));
            now.add(sheet(a, n + " y+1 noobline", layer, l1, -1, -1));
            now.add(sheet(a, n + " y+1", layer, l1, 0, 127));
            noob.add(sheet(a, n + " y+0 with its noobline", layer, l0, -1, 127));
            noob.add(sheet(a, n + " y+1 with its noobline", layer, l1, -1, 127));
            levels.add(sheet(a, n + " both levels, noobline", layer, both, -1, 127));
        }
        double t0 = report("8 passes (as printed today)", now, out);
        double t1 = report("4 passes (noobline rows in their level's pass)", noob, out);
        double t2 = report("2 passes (both levels of a layer in one sweep)", levels, out);
        System.out.println(out);
        assertEquals(23_708, a.count(), "the export's blocks");
        // no column of a layer holds both levels: a layer is one block per column, at one of two heights
        for (int layer = 0; layer < SuppressionArt.LAYERS; layer++)
            for (int row = -1; row < 128; row++)
                for (int col = 0; col < 128; col++)
                    assertTrue(a.get(layer, 0, col, row) == null || a.get(layer, 1, col, row) == null);
        // live: the four noobline passes of the third print took 4.5 minutes (BUILD 155 s, RESTOCK 114 s)
        assertEquals(4.5, (t0 - t1) / 60, 1.0, "the noobline rows' own passes, minutes a map");
        assertTrue(t1 - t2 > 10 * 60, "the raised levels' own sweeps cost more than 10 minutes a map: " + (t1 - t2) / 60);
    }

    /** A pass of {@code cells} spread evenly over the 15 lanes of a layer. */
    static double even(int cells, int layer) {
        double lane = 15 * (Math.max(SuppressionArt.COLS / WALK, cells / 15.0 / RATE) + LANE_CHANGE);
        int loads = (cells + LOAD - 1) / LOAD;
        lane += Math.max(0, loads - 15) * (SuppressionArt.COLS / WALK / 2 + LANE_CHANGE);
        return lane + loads * (layer == SuppressionArt.TOP ? ROUND_TOP : ROUND_BOTTOM);
    }

    /** The third live print (2b2t, real speed, 2026-11-14): its four main passes, minutes, against the model. */
    @Test
    void theModelFitsTheLiveMainPasses() {
        double[][] live = {
            {11_355, SuppressionArt.BOTTOM, 31.7}, {2_218, SuppressionArt.BOTTOM, 12.3},
            {7_141, SuppressionArt.TOP, 18.0}, {831, SuppressionArt.TOP, 9.6}};
        for (double[] p : live) {
            double model = even((int) p[0], (int) p[1]) / 60;
            System.out.printf(Locale.ROOT, "live pass of %5.0f cells: %.1f min, model %.1f min%n", p[0], p[2], model);
            assertEquals(p[2], model, p[2] * 0.12, "pass of " + (int) p[0] + " cells");
        }
    }
}
