package io.github.digitalisp.mapart.plan;

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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.testutil.RestockModel.Sweep.END_AWARE;

/**
 * Where loads end, and whether the split DP's leg costs matter. Live (section 8):
 * 9 of 10 trips start from a cut 82-114 blocks south of the sorter. The model scores every run with the live leg fit
 * ({@link RestockModel#LIVE}: 0.69 s + d / 5.99 b/s) and plans it with {@link RestockPlanner.Costs#DEFAULT} (0.25 s +
 * d / 5.77, today) or with the live fit: the DP's choices, and the time, hardly change.
 */
class LegFitModelTest {
    private static final int AIRPLACE = 4;
    private final FakeCatalog catalog = new FakeCatalog();

    /** The configured planner, planning with {@code costs} whatever the request's site says. */
    record PlannedWith(RestockPlanner.Costs costs) implements RestockPlanner {
        @Override
        public Plan plan(List<Lane> remaining, Map<String, Integer> held, int freeSlots, ToIntFunction<String> maxStack) {
            return plan(Request.of(remaining, held, freeSlots, maxStack));
        }

        @Override
        public Plan plan(Request r) {
            Site s = r.site();
            Request q = s == null ? r : new Request(r.remaining(), r.held(), r.heldSlots(), r.freeSlots(), r.maxStack(),
                r.lastLoadReserve(), r.topUp(), new Site(s.bot(), s.depot(), costs));
            return new ConfiguredRestockPlanner(() -> "AUTO").plan(q);
        }

        @Override
        public boolean expensive() {
            return true;
        }
    }

    private static Config config(String name, RestockPlanner.Costs planning) {
        return new Config(name, () -> new PlannedWith(planning), 31, 2, true, END_AWARE, true, Withdraw.STACKS,
            RestockModel.LIVE);
    }

    private static double meanEndZ(Result r) {
        return r.loadEndZ.stream().mapToInt(Integer::intValue).average().orElse(0);
    }

    private static long deepEnds(Result r) {
        return r.loadEndZ.stream().filter(z -> z >= 64).count();
    }

    /** Planned with today's costs and with the live fit, scored live: within 3 %. */
    private Result[] compare(String name, TileArt art) {
        RestockModel model = new RestockModel(art, AIRPLACE, catalog);
        Result today = model.run(config("today", RestockPlanner.Costs.DEFAULT));
        Result live = model.run(config("live fit", RestockModel.LIVE));
        System.out.printf(Locale.ROOT, "%s, 9-wide, scored live: today %d trips, %.1f s, loads end at z %.0f on average"
                + " (%d of %d at 64 or deeper); planned with the live fit %d trips, %.1f s (%+.1f s)%n", name,
            today.trips(), today.totalSeconds(), meanEndZ(today), deepEnds(today), today.loadEndZ.size(), live.trips(),
            live.totalSeconds(), live.totalSeconds() - today.totalSeconds());
        assertTrue(Math.abs(live.totalSeconds() - today.totalSeconds()) <= 0.03 * today.totalSeconds(),
            name + ": " + today.totalSeconds() + " s against " + live.totalSeconds() + " s");
        return new Result[] {today, live};
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void mixed24LoadsEndDeepInTheTileWhateverTheLegFit() {
        Result[] r = compare("mixed 24", SyntheticArt.mixed24(24));
        // as live: a 9-wide lane takes 20-29 slots, a lane pair never fits 31, so most loads end in the second lane's
        // southern half (walked south to north from the tile's far edge until the slots run out)
        assertTrue(deepEnds(r[0]) * 2 >= r[0].loadEndZ.size(), r[0].loadEndZ.toString());
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void sizedreisen() throws Exception {
        compare("sizedreisen 1_1", new DefaultSubmissionService().parse(List.of(Fixtures.renamed(
            "sizedreisen/0006_sizedreisen_1_1.nbt", "sizedreisen-1-1.nbt"))).tiles().values().iterator().next());
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void carpet16() {
        compare("carpet 16", SyntheticArt.carpet16(16));
    }
}
