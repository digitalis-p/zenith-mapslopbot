package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.FakeCatalog;
import io.github.digitalisp.mapart.testutil.Fixtures;
import io.github.digitalisp.mapart.testutil.RestockModel;
import io.github.digitalisp.mapart.testutil.RestockModel.Config;
import io.github.digitalisp.mapart.testutil.RestockModel.Result;
import io.github.digitalisp.mapart.testutil.RestockModel.Withdraw;
import io.github.digitalisp.mapart.testutil.SyntheticArt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.testutil.RestockModel.Sweep.END_AWARE;

/**
 * The routing study's carry rule in the restock model: per item,
 * withdraw what this load and the following DP rounds that use it in a row need, up to this load's own stack count for
 * it, and nothing that a later load does not use; then no carried material ever crowds a load. That needs exact
 * partial stacks. The study priced one at a single extra click; vanilla clicks take 3 to 34 (one-item right clicks),
 * or 2 to 10 with a right click that picks up half a stack ({@code ExactClicks}). Prints one table per tile and lane
 * width (stdout and {@code build/carry-rule-model.md}):
 * {@code ./gradlew test --offline --tests 'io.github.digitalisp.mapart.plan.CarryRuleModelTest' -i}.
 */
class CarryRuleModelTest {
    private static final int AIRPLACE = 4;
    private static final int LOOK = 2;
    private static final String MIXED = "sizedreisen/0006_sizedreisen_1_1.nbt";
    private static final String MAIN = "today: top-up, crowding carried items go back";
    private static final String NO_RETURNS = "top-up, nothing goes back";
    private static final String FREE = "carry rule, one click per partial stack (the study)";
    private static final String RIGHT = "carry rule, today's clicks";
    private static final String HALF = "carry rule, + pick-up-half click";
    private static final Map<String, String> SECTIONS = new ConcurrentSkipListMap<>();

    private final FakeCatalog catalog = new FakeCatalog();

    /**
     * The carry rule over the split DP's path ({@link SplitRestockPlanner#solve}): the DP's load, with each item's
     * withdrawal raised from the load's own need to the following rounds' as long as they use the item one after another
     * and the total stays within the load's own stacks of it ({@code stack × ceil(n / stack)}). Exact counts.
     */
    static final class CarryRule implements RestockPlanner {
        private final SplitRestockPlanner dp;

        CarryRule(boolean lanesFirst) {
            dp = new SplitRestockPlanner(lanesFirst);
        }

        @Override
        public Plan plan(List<Lane> remaining, Map<String, Integer> held, int freeSlots, ToIntFunction<String> maxStack) {
            return plan(Request.of(remaining, held, freeSlots, maxStack));
        }

        @Override
        public Plan plan(Request r) {
            SplitRestockPlanner.Result res = dp.solve(r);
            Plan p = res.plan();
            List<SplitRestockPlanner.Round> rounds = res.rounds();
            if (rounds.isEmpty()) return p;
            Map<String, Integer> w = new TreeMap<>();
            for (var e : rounds.getFirst().items().entrySet()) {
                String id = e.getKey();
                int n = e.getValue();
                int h = r.held().getOrDefault(id, 0);
                if (n <= h) continue;
                int s = Math.max(1, r.maxStack().applyAsInt(id));
                int cap = s * ((n + s - 1) / s);
                int total = n;
                for (int j = 1; j < rounds.size(); j++) {
                    int m = rounds.get(j).items().getOrDefault(id, 0);
                    if (m <= 0 || total + m > cap) break;
                    total += m;
                }
                w.put(id, total - h);
            }
            return new Plan(w, p.lanesCovered(), p.partialCells(), p.stopAtCut(), p.rounds(), p.seconds());
        }

        @Override
        public boolean expensive() {
            return true;
        }
    }

    private static List<Config> configs(boolean lanesFirst) {
        Supplier<RestockPlanner> today = () -> new ConfiguredRestockPlanner(() -> "AUTO");
        Supplier<RestockPlanner> carry = () -> new CarryRule(lanesFirst);
        List<Config> l = new ArrayList<>();
        l.add(new Config(MAIN, today, 31, 2, true, END_AWARE, true, Withdraw.STACKS));
        l.add(new Config(NO_RETURNS, today, 31, 2, true, END_AWARE, false, Withdraw.STACKS));
        l.add(new Config(FREE, carry, 31, 2, true, END_AWARE, true, Withdraw.EXACT_FREE));
        l.add(new Config(RIGHT, carry, 31, 2, true, END_AWARE, true, Withdraw.EXACT_RIGHT));
        l.add(new Config(HALF, carry, 31, 2, true, END_AWARE, true, Withdraw.EXACT_HALF));
        return l;
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void carpet16() {
        TileArt art = SyntheticArt.carpet16(16);
        scenario(1, "carpet 16", art, AIRPLACE);
        scenario(1, "carpet 16", art, LOOK);
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void mixed24() {
        TileArt art = SyntheticArt.mixed24(24);
        Map<String, Result> r = scenario(2, "mixed 24", art, AIRPLACE);
        // the study's direction holds only with free partial stacks
        assertTrue(r.get(FREE).totalSeconds() < r.get(MAIN).totalSeconds(), "carry rule at one click per stack");
        // without exact counts, the returns are what keeps carried leftovers from crowding the loads
        assertTrue(r.get(NO_RETURNS).totalSeconds() > r.get(MAIN).totalSeconds(), "top-up without returns");
        scenario(2, "mixed 24", art, LOOK);
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void mixedSizedreisen() throws Exception {
        TileArt art = new DefaultSubmissionService().parse(List.of(Fixtures.renamed(MIXED, "sizedreisen-1-1.nbt")))
            .tiles().values().iterator().next();
        scenario(3, "mixed (sizedreisen 1_1)", art, AIRPLACE);
        scenario(3, "mixed (sizedreisen 1_1)", art, LOOK);
    }

    /** Runs every configuration; the carry rule with real clicks is slower than today in every scenario. */
    private Map<String, Result> scenario(int order, String artName, TileArt art, int halfWidth) {
        RestockModel model = new RestockModel(art, halfWidth, catalog);
        boolean lanesFirst = RestockMode.AUTO.resolve(model.initialLanes()) == RestockMode.LANES;
        Map<String, Result> r = new LinkedHashMap<>();
        Map<String, String> failed = new LinkedHashMap<>();
        for (Config c : configs(lanesFirst)) {
            try {
                Result res = model.run(c);
                r.put(c.name(), res);
                if (res.shortCells > 0) failed.put(c.name(), res.shortCells + " cells the plan covered were not carried");
            } catch (RuntimeException e) {
                failed.put(c.name(), String.valueOf(e.getMessage()));
            }
        }
        String width = halfWidth == AIRPLACE ? "AIRPLACE, 9-wide" : "LOOK, 5-wide";
        String text = table(artName + ", " + width, r, failed);
        System.out.println(text);
        SECTIONS.put(order + "-" + (halfWidth == AIRPLACE ? 1 : 2), text);

        for (String name : List.of(MAIN, FREE, RIGHT, HALF))
            assertTrue(!failed.containsKey(name), artName + " " + width + ": " + name + ": " + failed.get(name));
        double main = r.get(MAIN).totalSeconds();
        assertTrue(r.get(RIGHT).totalSeconds() > main, artName + " " + width + ": carry rule with today's clicks");
        assertTrue(r.get(HALF).totalSeconds() > main, artName + " " + width + ": carry rule with half pick-up");
        return r;
    }

    private static String table(String title, Map<String, Result> r, Map<String, String> failed) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(title).append("\n\n");
        sb.append("| Config | Trips | Chests | Clicks | Drops (stacks) | Walk + legs s | Chest s | Click s | Drop s "
            + "| Restock s | Lane-change s | Total s | Δ today |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        Result base = r.get(MAIN);
        for (Config c : configs(false)) {
            Result x = r.get(c.name());
            if (x == null) {
                sb.append("| ").append(c.name()).append(" | failed: ")
                    .append(String.valueOf(failed.get(c.name())).replace('|', '/')).append(" |").append(" |".repeat(11))
                    .append('\n');
                continue;
            }
            String delta = base == null || x == base ? "–" : String.format(Locale.ROOT, "%+.1f s (%+.1f %%)",
                x.totalSeconds() - base.totalSeconds(), 100 * (x.totalSeconds() - base.totalSeconds()) / base.totalSeconds());
            sb.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d (%d) | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %s |%n",
                c.name(), x.trips(), x.chests, x.clicks, x.drops, x.dropStacks, x.walkSeconds + x.legSeconds,
                x.chestSeconds, x.clickSeconds, x.dropSeconds, x.restockSeconds(), x.laneChangeSeconds, x.totalSeconds(),
                delta));
        }
        return sb.toString();
    }

    @AfterAll
    static void writeReport() throws IOException {
        if (SECTIONS.isEmpty()) return;
        StringBuilder sb = new StringBuilder("# Carry rule in the restock model (CarryRuleModelTest)\n\n");
        sb.append("Seconds per tile, restock and lane changes only (RestockModel, Costs.DEFAULT: 0.3 s per click).\n\n");
        SECTIONS.values().forEach(s -> sb.append(s).append('\n'));
        Path out = Path.of("build", "carry-rule-model.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString());
    }
}
