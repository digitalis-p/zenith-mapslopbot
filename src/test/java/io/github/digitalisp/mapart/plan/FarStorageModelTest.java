package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.FakeCatalog;
import io.github.digitalisp.mapart.testutil.RestockModel;
import io.github.digitalisp.mapart.testutil.RestockModel.Config;
import io.github.digitalisp.mapart.testutil.RestockModel.Far;
import io.github.digitalisp.mapart.testutil.RestockModel.Result;
import io.github.digitalisp.mapart.testutil.SyntheticArt;
import io.github.digitalisp.mapart.testutil.Tiles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.testutil.RestockModel.Sweep.END_AWARE;

/**
 * Far storage ({@link FarStorage}) in the restock model: trips and restock time of
 * one tile whose items split between the walkway sorter by the platform and a far sorter. Three engines: before the
 * far rules, with the leave-out of a load's few far cells only ({@code build.farStorageBatching} false), and with
 * batching too (the defaults). Geometry in tile-relative coordinates (no real ones): the tile at x, z 0..127, the
 * walkway sorter along z −7 (x 0..60), the far sorter used from (−190, 64, −5), about 190 blocks west, like the soul
 * sand platform and the moss sorter (live 2026-10-13: about 4 minutes per far trip).
 * {@code ./gradlew test --offline --tests 'io.github.digitalisp.mapart.plan.FarStorageModelTest' -i} prints the tables.
 */
class FarStorageModelTest {
    private static final int LOOK = 2;
    private static final int AIRPLACE = 4;
    private static final Pos FAR_STAND = new Pos(-190, RestockModel.ART_Y, -5);
    private static final String GRAY = "minecraft:gray_terracotta";
    private final FakeCatalog catalog = new FakeCatalog();

    private static Config config(String name, Supplier<RestockPlanner> planner) {
        return new Config(name, planner, 31, 2, true, END_AWARE);
    }

    private static Config auto() {
        return config("AUTO", () -> new ConfiguredRestockPlanner(() -> "AUTO"));
    }

    private static Config greedy() {
        return config("GREEDY", GreedyRestockPlanner::new);
    }

    /** The engine before the far rules, with the leave-out only, and with batching too. */
    private enum Engine { BEFORE, LEAVE_OUT, BATCHING }

    private Result run(TileArt art, int halfWidth, Config cfg, Set<String> farItems, Engine e) {
        Far far = switch (e) {
            case BEFORE -> Far.before(farItems, FAR_STAND);
            case LEAVE_OUT -> new Far(farItems, FAR_STAND, 64, null);
            case BATCHING -> Far.rules(farItems, FAR_STAND);
        };
        return new RestockModel(art, halfWidth, catalog).far(far).run(cfg);
    }

    private static String cell(Result r, Result before) {
        return String.format(Locale.ROOT, "%.0f s (%+.1f %%), %d far / %d trips", r.totalSeconds(),
            100 * (r.totalSeconds() - before.totalSeconds()) / before.totalSeconds(), r.farTrips, r.trips());
    }

    private record Scenario(String name, TileArt art, Set<String> far) { }

    /**
     * Six tiles × two lane widths × two planners: the defaults against the engine before the far rules. Batching keeps
     * the loads until the next far trip healthy (the margin), so it never costs much and mostly saves; batching
     * everything the rest of the tile needs from far cost up to +20 % in this table (5-wide lanes, the split planner:
     * many loads of a few dozen cells).
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void acrossTilesWidthsAndPlanners() {
        TileArt m24 = SyntheticArt.mixed24(24);
        List<Scenario> scenarios = List.of(
            new Scenario("mixed 24, 3 far", m24, Set.of(GRAY, "minecraft:cyan_carpet", "minecraft:oak_pressure_plate")),
            new Scenario("mixed 24, 1 far", m24, Set.of(GRAY)),
            new Scenario("mixed 24, 6 far", m24, Set.of(GRAY, "minecraft:cyan_carpet", "minecraft:oak_pressure_plate",
                "minecraft:black_terracotta", "minecraft:yellow_carpet", "minecraft:red_terracotta")),
            new Scenario("mixed 24, the largest far", m24, Set.of(SyntheticArt.histogram(m24).keySet().iterator().next(),
                GRAY, "minecraft:oak_pressure_plate")),
            new Scenario("carpet 16, 2 far", SyntheticArt.carpet16(16), Set.of("minecraft:lime_carpet",
                "minecraft:cyan_carpet")),
            new Scenario("stripes, 1 far in every lane", stripes(), Set.of(GRAY)));
        StringBuilder sb = new StringBuilder("| Tile | Lanes | Planner | Before | Leave-out only | Defaults (batching) |\n"
            + "|---|---|---|---:|---:|---:|\n");
        List<Double> leaveOut = new ArrayList<>();
        List<Double> batching = new ArrayList<>();
        int farBefore = 0;
        int farAfter = 0;
        for (Scenario s : scenarios) {
            for (int width : new int[]{LOOK, AIRPLACE}) {
                for (Config cfg : new Config[]{auto(), greedy()}) {
                    Map<Engine, Result> r = new LinkedHashMap<>();
                    for (Engine e : Engine.values()) r.put(e, run(s.art(), width, cfg, s.far(), e));
                    Result before = r.get(Engine.BEFORE);
                    for (Result x : r.values()) assertEquals(0, x.shortCells, s.name());
                    leaveOut.add(change(r.get(Engine.LEAVE_OUT), before));
                    batching.add(change(r.get(Engine.BATCHING), before));
                    farBefore += before.farTrips;
                    farAfter += r.get(Engine.BATCHING).farTrips;
                    sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %.0f s, %d far / %d trips | %s | %s |%n",
                        s.name(), width == LOOK ? "5-wide" : "9-wide", cfg.name(), before.totalSeconds(), before.farTrips,
                        before.trips(), cell(r.get(Engine.LEAVE_OUT), before), cell(r.get(Engine.BATCHING), before)));
                }
            }
        }
        double meanLeaveOut = leaveOut.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double meanBatching = batching.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double worstBatching = batching.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        sb.append(String.format(Locale.ROOT, "%nTime against before: leave-out only %+.1f %% mean (worst %+.1f %%); "
                + "defaults %+.1f %% mean (worst %+.1f %%). Far trips %d -> %d.%n", meanLeaveOut,
            leaveOut.stream().mapToDouble(Double::doubleValue).max().orElse(0), meanBatching, worstBatching, farBefore,
            farAfter));
        System.out.println("[far storage model]\n" + sb);
        assertTrue(meanBatching < -5, "defaults mean " + meanBatching + " %");
        // 6 %, was 5 %: the carried-stacks guard (SplitDp) took 102 s off "before" on mixed 24, 3 far, 5-wide AUTO
        // and 12 s off the defaults (2138 -> 2036 s, 2155 -> 2143 s): +0.8 % became +5.3 % with both faster
        assertTrue(worstBatching < 6, "defaults worst " + worstBatching + " %");
        assertTrue(meanBatching < meanLeaveOut, "batching saves on top of the leave-out");
        assertTrue(farAfter < farBefore * 0.8, farAfter + " far trips vs " + farBefore);
    }

    private static double change(Result r, Result before) {
        return 100 * (r.totalSeconds() - before.totalSeconds()) / before.totalSeconds();
    }

    /** Six carpets in bands, gray terracotta in about one cell of 41 (every lane, a few dozen per load). */
    private static TileArt stripes() {
        return Tiles.of((x, z) -> (x * 7 + z * 3) % 41 == 0 ? GRAY : SyntheticArt.CARPETS.get(((x / 8) + (z / 16)) % 6));
    }

    /**
     * The live case: near storage holds most items, one far item spread over every lane (ellen's light blue
     * terracotta, 2026-10-13 07:15-07:22: a far trip every load). Far trips 7 → 1.
     */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void oneFarItemInEveryLane() {
        Result before = run(stripes(), LOOK, auto(), Set.of(GRAY), Engine.BEFORE);
        Result now = run(stripes(), LOOK, auto(), Set.of(GRAY), Engine.BATCHING);
        System.out.println("[far storage model] one far item in every lane, AUTO, 5-wide: " + cell(now, before)
            + " (before " + before.farTrips + " far / " + before.trips() + " trips)");
        assertTrue(before.farTrips >= 5, "before: " + before.farTrips);
        assertEquals(1, now.farTrips);
        assertTrue(now.totalSeconds() < before.totalSeconds());
    }

    /**
     * 16 far cells spread over the tile, and nothing else ever goes far: the load that first needs them takes them, as
     * before the far rules. Deferring them to the end (the leave-out without the later-trip condition) cost +50 % here:
     * the same far trip, plus a last round walking back over their lanes.
     */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void aFewFarCellsWithNoLaterFarTripGoNow() {
        String lime = "minecraft:lime_carpet";
        TileArt art = Tiles.of((x, z) -> x % 32 == 5 && z % 32 == 7 ? lime
            : SyntheticArt.CARPETS.get(((x / 8) + (z / 16)) % 6));
        Result before = run(art, LOOK, auto(), Set.of(lime), Engine.BEFORE);
        Result now = run(art, LOOK, auto(), Set.of(lime), Engine.BATCHING);
        System.out.println("[far storage model] 16 far cells, AUTO, 5-wide: " + cell(now, before) + " (before "
            + before.farTrips + " far / " + before.trips() + " trips)");
        assertEquals(1, before.farTrips);
        assertEquals(1, now.farTrips);
        assertEquals(0, now.farLeftOut, "nothing deferred");
        assertTrue(now.totalSeconds() <= before.totalSeconds() * 1.01, now.totalSeconds() + " vs " + before.totalSeconds());
    }
}
