package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.RestockPlanner.Costs;
import io.github.digitalisp.mapart.plan.RestockPlanner.Plan;
import io.github.digitalisp.mapart.plan.RestockPlanner.Request;
import io.github.digitalisp.mapart.testutil.FakeCatalog;
import io.github.digitalisp.mapart.testutil.RestockModel;
import io.github.digitalisp.mapart.testutil.RestockModel.Config;
import io.github.digitalisp.mapart.testutil.RestockModel.Result;
import io.github.digitalisp.mapart.testutil.SyntheticArt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Restock chest visits that only top up a carried partial stack: can a load leave them out without more restocks? A
 * study, not a switch: no policy here is in the engine.
 *
 * <p>Live j0015/j0016 (2026-10-23/24, 12 maps, soul sand): 14.2 restocks and 76.8 withdraw visits a map for 75.8 planned
 * items (one container per item and load); 20.9 of the visits took no whole stack, only the top-up of a carried partial
 * stack, about 1.1 min a map of menus and hops. Each is the split DP's choice: the load's cells after the item's last
 * carried one need it.
 *
 * <p>Policies against the live configuration ({@link LoadDirectionModelTest#live()}), the planner itself unchanged:
 * <ul>
 *   <li><b>Accept the partial stack</b> ({@link AcceptPartial}): a top-up-only item at most {@code maxShort} cells short
 *       is left out by ending the load before its first uncovered cell, when that drops at most {@code maxLost} of the
 *       load's last cells.</li>
 *   <li><b>Carry ahead</b> ({@link CarryAhead}): a load that leaves slots empty takes one more whole stack of an item it
 *       withdraws anyway, when the next load (estimated: the cells after the cut that fit the same slots) needs more of
 *       it than this load leaves over, so that the next load does not open that chest.</li>
 *   <li><b>Planner chest cost</b>: the split DP priced with 3 s a container and 0.05 s a click (measured live menus)
 *       instead of {@link Costs#DEFAULT}'s 1 s and 0.3 s.</li>
 *   <li><b>29 material slots</b>: a reference, one slot more for the loads (not a policy: the kit has no slot to give).</li>
 * </ul>
 *
 * <p>Tiles: three j0015-like synthetic tiles, and the real tiles of {@code MAPART_RESTOCK_TILES} (TileCodec JSON files;
 * not in git) when set: {@code MAPART_RESTOCK_TILES=/path/j0015 ./gradlew test --tests '*TopUpVisitModelTest' -i}. The
 * table goes to {@code build/top-up-visits-model.md}. 40 j0015 tiles (2026-10-24); the
 * left-out visits come back in the next load, and every policy that changes what is carried changes the later cuts, so
 * restocks per tile move by ±1-3 either way.
 */
class TopUpVisitModelTest {
    private final FakeCatalog catalog = new FakeCatalog();

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void topUpOnlyVisitsBuyCoverage() throws IOException {
        Map<String, TileArt> tiles = new LinkedHashMap<>();
        for (long seed = 1; seed <= 3; seed++)
            tiles.put("j0015-like " + seed, SyntheticArt.banded(seed, MapPaneAtStoreModelTest.J0015, 6, 16, 64, 0.30));
        int synthetic = tiles.size();
        String dir = System.getenv("MAPART_RESTOCK_TILES");
        if (dir != null && Files.isDirectory(Path.of(dir))) {
            try (Stream<Path> s = Files.list(Path.of(dir))) {
                for (Path p : s.filter(f -> f.getFileName().toString().matches("tile_\\d+_\\d+\\.json")).sorted().toList())
                    tiles.put(p.getFileName().toString(), MapPaneAtStoreModelTest.readTile(p));
            }
        }
        Config today = LoadDirectionModelTest.live();
        AcceptPartial.Count small = new AcceptPartial.Count();
        AcceptPartial.Count any = new AcceptPartial.Count();
        CarryAhead.Count carried = new CarryAhead.Count();
        List<Config> configs = List.of(today,
            wrap(today, "accept the partial: 16 short, 36 lost", p -> new AcceptPartial(p, 16, 36, small)),
            wrap(today, "accept the partial: 64 short, 64 lost", p -> new AcceptPartial(p, 64, 64, any)),
            wrap(today, "carry ahead", p -> new CarryAhead(p, carried)),
            today.plannedWith("planner: chest 3 s, click 0.05 s", new Costs(5.77, 0.25, 2.2, 3.0, 0.05, 1.5)),
            today.mapPaneAtStore("29 material slots (reference)", 29));
        int k = configs.size();
        double[][] sum = new double[k][5];
        double[][] real = new double[k][5];
        int[] fewerTrips = new int[k];
        int[] moreTrips = new int[k];
        List<String> lines = new ArrayList<>();
        lines.add("| Tile | Configuration | Trips | Chests | Top-up only | Restock s | Total s |");
        lines.add("|---|---|---:|---:|---:|---:|---:|");
        int n = 0;
        for (var e : tiles.entrySet()) {
            RestockModel model = new RestockModel(e.getValue(), 4, catalog, RestockModel.SOUL_SAND, true);
            Result base = null;
            for (int i = 0; i < k; i++) {
                Result r = model.run(configs.get(i));
                if (base == null) base = r;
                else assertEquals(base.cellsPlaced, r.cellsPlaced, e.getKey() + " " + configs.get(i).name());
                double[] v = {r.trips(), r.chests, r.topUpOnly, r.restockSeconds(), r.totalSeconds()};
                for (int j = 0; j < v.length; j++) {
                    sum[i][j] += v[j];
                    if (n >= synthetic) real[i][j] += v[j];
                }
                if (r.trips() < base.trips()) fewerTrips[i]++;
                if (r.trips() > base.trips()) moreTrips[i]++;
                lines.add(String.format(Locale.ROOT, "| %s | %s | %d | %d | %d | %.0f | %.0f |", i == 0 ? e.getKey() : "",
                    configs.get(i).name(), r.trips(), r.chests, r.topUpOnly, r.restockSeconds(), r.totalSeconds()));
            }
            n++;
        }
        lines.add("");
        lines.add("| Mean over " + n + " tiles (" + (n - synthetic) + " real) | Trips | Chests | Top-up only | Restock s"
            + " | Total s | Tiles with fewer / more trips |");
        lines.add("|---|---:|---:|---:|---:|---:|---:|");
        for (int i = 0; i < k; i++) {
            double[] m = n > synthetic ? real[i] : sum[i];
            int d = n > synthetic ? n - synthetic : n;
            lines.add(String.format(Locale.ROOT, "| %s | %.2f | %.1f | %.1f | %.1f | %.1f | %s |", configs.get(i).name(),
                m[0] / d, m[1] / d, m[2] / d, m[3] / d, m[4] / d, i == 0 ? "" : fewerTrips[i] + " / " + moreTrips[i]));
        }
        lines.add("");
        lines.add(String.format(Locale.ROOT, "(Means over the real tiles when there are any.) Left out: %d top-ups"
            + " (16 short), %d (64 short), %d cells and %d cells dropped from loads; carried ahead: %d stacks.",
            small.skipped, any.skipped, small.cellsLost, any.cellsLost, carried.stacks));
        String md = String.join("\n", lines) + "\n";
        System.out.println(md);
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", "top-up-visits-model.md"), md);
        assertTrue(any.skipped > 0, "the accept-the-partial policy never applied:\n" + md);
        assertTrue(carried.stacks > 0, "the carry-ahead policy never applied:\n" + md);
    }

    /** {@code c} with its planner wrapped. */
    private static Config wrap(Config c, String name, Function<RestockPlanner, RestockPlanner> wrapper) {
        return new Config(name, () -> wrapper.apply(c.planner().get()), c.materialSlots(), c.lastLoadReserve(), c.topUp(),
            c.sweep(), c.returnUnused(), c.withdraw(), c.costs(), c.lazyDropSeconds(), c.keepReused(), c.plannerCosts(),
            c.mapPaneAtStore(), c.flipLanes());
    }

    /** Whether {@code w} withdrawn of an item with {@code h} carried only tops up the partial stack (no new slot). */
    static boolean topUpOnly(int h, int w, int stack) {
        return w > 0 && h % stack > 0 && w <= stack - h % stack;
    }

    /**
     * Accept the partial stack: the planner's load, then while a top-up-only item is at most {@code maxShort} cells
     * short and its first uncovered cell lies within the load's last {@code maxLost} cells, the load ends before that
     * cell (the latest such cut first); the withdrawal is recomputed for the shorter load (top-up or whole stacks).
     */
    static final class AcceptPartial implements RestockPlanner {
        static final class Count {
            int skipped;
            int cellsLost;
        }

        private final RestockPlanner inner;
        private final int maxShort;
        private final int maxLost;
        private final Count count;

        AcceptPartial(RestockPlanner inner, int maxShort, int maxLost, Count count) {
            this.inner = inner;
            this.maxShort = maxShort;
            this.maxLost = maxLost;
            this.count = count;
        }

        @Override
        public Plan plan(List<Lane> remaining, Map<String, Integer> held, int freeSlots, ToIntFunction<String> maxStack) {
            return plan(Request.of(remaining, held, freeSlots, maxStack));
        }

        @Override
        public boolean expensive() {
            return inner.expensive();
        }

        @Override
        public Plan plan(Request r) {
            Plan p = inner.plan(r);
            for (int round = 0; round < 16; round++) {
                List<String> seq = new ArrayList<>();
                for (Lane l : RestockLoads.cutLanes(r.remaining(), p)) for (Placement x : l.placements()) seq.add(x.itemId());
                Map<String, Integer> need = new HashMap<>();
                for (String id : seq) if (id != null) need.merge(id, 1, Integer::sum);
                int cut = -1;
                for (var e : p.withdraw().entrySet()) {
                    String id = e.getKey();
                    int h = r.held().getOrDefault(id, 0);
                    int w = e.getValue() == null ? 0 : e.getValue();
                    if (!topUpOnly(h, w, r.maxStack().applyAsInt(id))) continue;
                    int shortBy = need.getOrDefault(id, 0) - h;
                    if (shortBy <= 0 || shortBy > maxShort) continue;
                    int seen = 0;
                    for (int i = 0; i < seq.size(); i++) {
                        if (!id.equals(seq.get(i)) || ++seen <= h) continue;
                        if (i > 0 && seq.size() - i <= maxLost) cut = Math.max(cut, i);
                        break;
                    }
                }
                if (cut < 0) return p;
                count.skipped++;
                count.cellsLost += seq.size() - cut;
                p = shortened(r, p, seq, cut);
            }
            return p;
        }

        /** {@code p} ending after its first {@code cells} placements in walking order, the withdrawal recomputed. */
        private static Plan shortened(Request r, Plan p, List<String> seq, int cells) {
            List<Lane> walked = LaneFlip.walked(r.remaining(), p);
            int left = cells;
            int a = 0;
            while (a < walked.size() && left >= walked.get(a).placements().size()) left -= walked.get(a++).placements().size();
            Map<String, Integer> n = new HashMap<>();
            for (int i = 0; i < cells; i++) if (seq.get(i) != null) n.merge(seq.get(i), 1, Integer::sum);
            Map<String, Integer> withdraw = new LinkedHashMap<>();
            for (var e : n.entrySet()) {
                int h = r.held().getOrDefault(e.getKey(), 0);
                int s = r.maxStack().applyAsInt(e.getKey());
                int m = e.getValue();
                if (m > h) withdraw.put(e.getKey(), r.topUp() ? s * ((m + s - 1) / s) - h : m - h);
            }
            return new Plan(withdraw, a, a < walked.size() ? left : 0, true, p.rounds(), p.seconds(), p.flipped());
        }
    }

    /**
     * Carry ahead: the planner's load, plus one whole stack of each item it withdraws whose next-load cells exceed what
     * this load leaves over of it, items needing it most first, while the load leaves slots empty. The next load is
     * estimated as the cells after the cut in walking order that fit the same capacity at {@code ceil(n / stack)} slots
     * per item. Nothing on the tile's last load.
     */
    static final class CarryAhead implements RestockPlanner {
        static final class Count {
            int stacks;
        }

        private final RestockPlanner inner;
        private final Count count;

        CarryAhead(RestockPlanner inner, Count count) {
            this.inner = inner;
            this.count = count;
        }

        @Override
        public Plan plan(List<Lane> remaining, Map<String, Integer> held, int freeSlots, ToIntFunction<String> maxStack) {
            return plan(Request.of(remaining, held, freeSlots, maxStack));
        }

        @Override
        public boolean expensive() {
            return inner.expensive();
        }

        @Override
        public Plan plan(Request r) {
            Plan p = inner.plan(r);
            if (p.withdraw().isEmpty()) return p;
            List<Lane> walked = LaneFlip.walked(r.remaining(), p);
            int covered = Math.min(p.lanesCovered(), walked.size());
            if (covered >= walked.size()) return p;
            Map<String, Integer> load = FarStorage.cells(RestockLoads.cutLanes(r.remaining(), p));
            // slots after the load: carried items at least the slots they take now, withdrawn ones their target's
            int capacity = Math.max(0, r.freeSlots());
            Map<String, Integer> target = new HashMap<>();
            for (var e : r.held().entrySet()) {
                int h = e.getValue() == null ? 0 : e.getValue();
                if (h <= 0) continue;
                capacity += heldSlots(r, e.getKey(), h);
                target.put(e.getKey(), h);
            }
            p.withdraw().forEach((id, w) -> {
                if (w != null && w > 0) target.merge(id, w, Integer::sum);
            });
            int used = 0;
            for (var e : target.entrySet()) {
                String id = e.getKey();
                int s = r.maxStack().applyAsInt(id);
                int h = r.held().getOrDefault(id, 0);
                int w = p.withdraw().getOrDefault(id, 0);
                int carriedSlots = h > 0 ? heldSlots(r, id, h) : 0;
                int after = r.topUp() || h <= 0 ? ceil(e.getValue(), s) : carriedSlots + ceil(w, s);
                used += Math.max(carriedSlots, after);
            }
            int empty = capacity - used;
            if (empty <= 0) return p;
            Map<String, Integer> next = nextLoad(walked, covered, p.partialCells(), capacity, r.maxStack());
            List<Map.Entry<String, Integer>> want = new ArrayList<>();
            p.withdraw().forEach((id, w) -> {
                if (w == null || w <= 0) return;
                int leftover = Math.max(0, target.getOrDefault(id, 0) - load.getOrDefault(id, 0));
                int deficit = next.getOrDefault(id, 0) - leftover;
                if (deficit > 0 && deficit <= r.maxStack().applyAsInt(id)) want.add(Map.entry(id, deficit));
            });
            if (want.isEmpty()) return p;
            want.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
            Map<String, Integer> withdraw = new LinkedHashMap<>(p.withdraw());
            for (var e : want) {
                if (empty <= 0) break;
                withdraw.merge(e.getKey(), r.maxStack().applyAsInt(e.getKey()), Integer::sum);
                empty--;
                count.stacks++;
            }
            return new Plan(withdraw, p.lanesCovered(), p.partialCells(), p.stopAtCut(), p.rounds(), p.seconds(),
                p.flipped());
        }

        /** Item → cells of the placements after the cut while they fit {@code capacity} slots at ceil(n / stack). */
        private static Map<String, Integer> nextLoad(List<Lane> walked, int covered, int partial, int capacity,
                                                     ToIntFunction<String> maxStack) {
            Map<String, Integer> cnt = new HashMap<>();
            int slots = 0;
            for (int i = covered; i < walked.size(); i++) {
                List<Placement> ps = walked.get(i).placements();
                for (int t = i == covered ? Math.max(0, partial) : 0; t < ps.size(); t++) {
                    String id = ps.get(t).itemId();
                    if (id == null) continue;
                    int s = maxStack.applyAsInt(id);
                    int c = cnt.getOrDefault(id, 0);
                    int more = ceil(c + 1, s) - ceil(c, s);
                    if (slots + more > capacity) return cnt;
                    slots += more;
                    cnt.put(id, c + 1);
                }
            }
            return cnt;
        }

        private static int heldSlots(Request r, String id, int h) {
            int need = ceil(h, r.maxStack().applyAsInt(id));
            Integer hs = r.heldSlots().get(id);
            return Math.max(hs == null ? need : hs, need);
        }
    }

    private static int ceil(int a, int b) {
        return (a + b - 1) / b;
    }
}
