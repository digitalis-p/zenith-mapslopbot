package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.RestockPlanner.Costs;
import io.github.digitalisp.mapart.plan.RestockPlanner.Request;
import io.github.digitalisp.mapart.plan.RestockPlanner.Site;
import io.github.digitalisp.mapart.testutil.FakeCatalog;
import io.github.digitalisp.mapart.testutil.Tiles;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Planning time on full 128×128 tiles (16 colours, skewed) with lanes from {@link SerpentineBuildPlanner}, 31 free
 * slots. The budget is well under 100 ms (target 30 ms); the assertion only guards against a blow-up.
 */
class SplitRestockPlannerRuntimeTest {
    private static final PlatformGeometry GEO = new PlatformGeometry(-64, 1984, 100);
    private static final String[] COLOURS = {
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    /** A tile of 16 carpet colours, colour c with weight 0.8^c, in patches so rows repeat colours like real art. */
    static TileArt skewedTile(long seed) {
        Random rnd = new Random(seed);
        double[] w = new double[COLOURS.length];
        double sum = 0;
        for (int c = 0; c < w.length; c++) sum += w[c] = Math.pow(0.8, c);
        int[][] px = new int[TileArt.SIZE][TileArt.SIZE];
        for (int z = 0; z < TileArt.SIZE; z++)
            for (int x = 0; x < TileArt.SIZE; x++) {
                if (x > 0 && rnd.nextDouble() < 0.5) {
                    px[z][x] = px[z][x - 1];
                    continue;
                }
                if (z > 0 && rnd.nextDouble() < 0.4) {
                    px[z][x] = px[z - 1][x];
                    continue;
                }
                double u = rnd.nextDouble() * sum;
                int c = 0;
                while (c < w.length - 1 && (u -= w[c]) > 0) c++;
                px[z][x] = c;
            }
        return Tiles.of((x, z) -> "minecraft:" + COLOURS[px[z][x]] + "_carpet");
    }

    static List<Lane> lanes(TileArt art, int halfWidth) {
        return new SerpentineBuildPlanner().plan(art, GEO, new FakeCatalog(), halfWidth, p -> false);
    }

    /** Depots on a line north of the tile, one per colour, three blocks apart; the bot north-west of the tile. */
    static Site site() {
        return new Site(new Pos(GEO.minX() + 6, GEO.artY(), GEO.minZ() - 9), id -> {
            for (int c = 0; c < COLOURS.length; c++)
                if (id.equals("minecraft:" + COLOURS[c] + "_carpet"))
                    return new Pos(GEO.minX() + 10 + 3 * c, GEO.artY(), GEO.minZ() - 14);
            return null;
        }, Costs.DEFAULT);
    }

    static Request request(List<Lane> lanes, int freeSlots) {
        return new Request(lanes, Map.of(), Map.of(), freeSlots, id -> 64, 2, true, site());
    }

    private static double timeMs(SplitRestockPlanner planner, Request r, int runs) {
        double[] ms = new double[runs];
        for (int i = 0; i < runs; i++) {
            long t0 = System.nanoTime();
            planner.solve(r);
            ms[i] = (System.nanoTime() - t0) / 1e6;
        }
        Arrays.sort(ms);
        return ms[runs / 2];
    }

    private void measure(int halfWidth, boolean lanesFirst) {
        measure(halfWidth, lanesFirst, false);
    }

    /** {@code flip}: the load direction ({@code build.restockLaneFlip}), both directions searched. */
    private void measure(int halfWidth, boolean lanesFirst, boolean flip) {
        List<Lane> lanes = lanes(skewedTile(7), halfWidth);
        Request r = flip ? flipping(request(lanes, 31)) : request(lanes, 31);
        SplitRestockPlanner planner = new SplitRestockPlanner(lanesFirst);
        for (int i = 0; i < 3; i++) planner.solve(r);
        double ms = timeMs(planner, r, 5);
        SplitRestockPlanner.Result res = planner.solve(r);
        System.out.printf("SplitRestockPlanner %d-wide %s%s: %.1f ms (median of 5, warm), %d lanes, %d rounds, %.1f s, %s%n",
            2 * halfWidth + 1, lanesFirst ? "LANES" : "SPLIT", flip ? " with the load direction" : "", ms, lanes.size(),
            res.rounds().size(), res.seconds(), res.stats());
        assertTrue(res.complete());
        assertTrue(ms < 1000, "planning took " + ms + " ms");
    }

    @Test
    void theWorkGuardLosesNothingOnAFullTile() {
        // 9-wide SPLIT: the guard drops most interval cuts; the exact search (every cut, ~20 M row steps) finds the
        // same optimum on this tile (and did on every tile tried).
        Request r = request(lanes(skewedTile(7), 4), 31);
        SplitRestockPlanner.Result guarded = new SplitRestockPlanner(false).solve(r);
        SplitRestockPlanner.Result exact = SplitDp.solve(r, false, 1, 1);
        assertTrue(!guarded.stats().exact() && exact.stats().exact());
        System.out.printf("SplitRestockPlanner guard: %.3f s (%s) against exact %.3f s (%s)%n", guarded.seconds(),
            guarded.stats(), exact.seconds(), exact.stats());
        assertTrue(guarded.seconds() >= exact.seconds() - 1e-9);
        assertTrue(guarded.seconds() <= exact.seconds() + 1e-6, guarded.seconds() + " against " + exact.seconds());
    }

    static Request flipping(Request r) {
        return new Request(r.remaining(), r.held(), r.heldSlots(), r.freeSlots(), r.maxStack(), r.lastLoadReserve(),
            r.topUp(), r.site() == null ? null : r.site().flipping(true));
    }

    @Test
    void theWorkGuardLosesNothingWithTheLoadDirection() {
        Request r = flipping(request(lanes(skewedTile(7), 4), 31));
        SplitRestockPlanner.Result guarded = new SplitRestockPlanner(false).solve(r);
        SplitRestockPlanner.Result exact = SplitDp.solve(r, false, 1, 1);
        SplitRestockPlanner.Result plain = new SplitRestockPlanner(false).solve(request(lanes(skewedTile(7), 4), 31));
        assertTrue(exact.stats().exact());
        System.out.printf("SplitRestockPlanner guard with the load direction: %.3f s (%s) against exact %.3f s (%s),"
            + " without it %.3f s%n", guarded.seconds(), guarded.stats(), exact.seconds(), exact.stats(), plain.seconds());
        assertTrue(guarded.seconds() >= exact.seconds() - 1e-9);
        assertTrue(guarded.seconds() <= exact.seconds() + 1e-6, guarded.seconds() + " against " + exact.seconds());
        assertTrue(guarded.seconds() <= plain.seconds() + 1e-9, "the choice never costs more");
    }

    @Test
    void nineWideSplit() {
        measure(4, false);
    }

    @Test
    void nineWideSplitWithTheLoadDirection() {
        measure(4, false, true);
    }

    @Test
    void nineWideLanesWithTheLoadDirection() {
        measure(4, true, true);
    }

    @Test
    void nineWideLanes() {
        measure(4, true);
    }

    @Test
    void fiveWideSplit() {
        measure(2, false);
    }

    @Test
    void fiveWideLanes() {
        measure(2, true);
    }
}
