package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.RestockPlanner.Costs;
import io.github.digitalisp.mapart.testutil.FakeCatalog;
import io.github.digitalisp.mapart.testutil.RestockModel;
import io.github.digitalisp.mapart.testutil.RestockModel.Config;
import io.github.digitalisp.mapart.testutil.RestockModel.Result;
import io.github.digitalisp.mapart.testutil.SyntheticArt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.testutil.RestockModel.Sweep.END_AWARE;

/**
 * The load direction ({@code build.restockLaneFlip}) on the soul sand platform's
 * layout ({@link RestockModel#SOUL_SAND}): the restock planner may walk a load's lanes the other way (its first lane
 * S→N), against today's every-load-starts-N→S.
 *
 * <p>Live j0015 (2026-10-23, 11 maps, logs): the walk from the last chest to the first lane start 102 s a map
 * (69 s to a lane start on the north edge, 32 s to one deep in the tile: the rest of a lane cut walking N→S), lane
 * changes 83 s (68 s of them dead walks over 6 s: after a lane rest of north rows only, to the next lane's south end),
 * and the walk back from the cut to the first chest 175 s. A load is about 1.4 of the 9-wide lanes, so most loads end
 * inside a lane, and the direction of the next load decides whether its first lane part is walked from the walkway side
 * or from the cut.
 *
 * <p>Live kit (the map and pane at STORE: 28 material slots), AUTO (SPLIT on 9-wide lanes), even lanes, lazy drop,
 * scored with the soul sand leg fit of {@link MapPaneAtStoreModelTest#SOUL}; the planner plans with
 * {@link Costs#DEFAULT} as the engine does. Tiles: three j0015-like synthetic tiles, three two-colour carpet tiles, and
 * the real tiles of {@code MAPART_RESTOCK_TILES} (TileCodec JSON files; not in git) when set:
 * {@code MAPART_RESTOCK_TILES=/path/j0015 ./gradlew test --tests '*LoadDirectionModelTest' -i}. The table goes to
 * {@code build/load-direction-model.md}.
 */
class LoadDirectionModelTest {
    private static final List<String> TWO = List.of("minecraft:gray_carpet", "minecraft:black_carpet");

    private final FakeCatalog catalog = new FakeCatalog();

    /** Live since 2026-10-23: AUTO, the map and pane carried from STORE (28 material slots), lazy drop. */
    static Config live() {
        return new Config("today", () -> new ConfiguredRestockPlanner(() -> "AUTO"), 30, 2, true, END_AWARE, true,
            RestockModel.Withdraw.STACKS, MapPaneAtStoreModelTest.SOUL).restockRules("today", 6.0, true)
            .plannedWith("today", Costs.DEFAULT).mapPaneAtStore("today", 28);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void walkingALoadTheOtherWayShortensTheWalksToAndFromItsLanes() throws IOException {
        Map<String, TileArt> tiles = new LinkedHashMap<>();
        for (long seed = 1; seed <= 3; seed++)
            tiles.put("j0015-like " + seed, SyntheticArt.banded(seed, MapPaneAtStoreModelTest.J0015, 6, 16, 64, 0.30));
        for (long seed = 1; seed <= 3; seed++) tiles.put("two carpets " + seed, SyntheticArt.banded(seed, TWO, 6, 16, 64, 0.30));
        int synthetic = tiles.size();
        String dir = System.getenv("MAPART_RESTOCK_TILES");
        if (dir != null && Files.isDirectory(Path.of(dir))) {
            try (Stream<Path> s = Files.list(Path.of(dir))) {
                for (Path p : s.filter(f -> f.getFileName().toString().matches("tile_\\d+_\\d+\\.json")).sorted().toList())
                    tiles.put(p.getFileName().toString(), MapPaneAtStoreModelTest.readTile(p));
            }
        }
        Config today = live();
        Config flip = today.flipping("load direction", true);
        List<String> lines = new ArrayList<>();
        lines.add("| Tile | Trips | Flipped loads | Lane change blocks | Walk blocks | Restock s | Lane change s | Total s | Saved s |");
        lines.add("|---|---:|---:|---:|---:|---:|---:|---:|---:|");
        double sumA = 0;
        double sumB = 0;
        double synA = 0;
        double synB = 0;
        double worst = Double.NEGATIVE_INFINITY;
        int n = 0;
        int faster = 0;
        int slower = 0;
        double msA = 0;
        double msB = 0;
        double maxA = 0;
        double maxB = 0;
        for (var e : tiles.entrySet()) {
            RestockModel model = new RestockModel(e.getValue(), 4, catalog, RestockModel.SOUL_SAND, true);
            Result a = model.run(today);
            Result b = model.run(flip);
            assertEquals(a.cellsPlaced, b.cellsPlaced, e.getKey());
            int flipped = 0;
            for (RestockModel.Trip t : b.trips) if (t.flipped()) flipped++;
            for (Result r : List.of(a, b)) {
                lines.add(String.format(Locale.ROOT, "| %s | %d | %s | %.0f | %.0f | %.0f | %.0f | %.0f | %s |",
                    r == a ? e.getKey() : "  with the load direction", r.trips(), r == a ? "-" : String.valueOf(flipped),
                    r.laneChangeBlocks, r.walkBlocks, r.restockSeconds(), r.laneChangeSeconds, r.totalSeconds(),
                    r == a ? "" : String.format(Locale.ROOT, "%.0f", a.totalSeconds() - b.totalSeconds())));
            }
            sumA += a.totalSeconds();
            sumB += b.totalSeconds();
            if (n < synthetic) {
                synA += a.totalSeconds();
                synB += b.totalSeconds();
            }
            worst = Math.max(worst, b.totalSeconds() - a.totalSeconds());
            if (b.totalSeconds() < a.totalSeconds() - 0.5) faster++;
            if (b.totalSeconds() > a.totalSeconds() + 0.5) slower++;
            msA += a.planMeanMillis();
            msB += b.planMeanMillis();
            maxA = Math.max(maxA, a.planMaxMillis());
            maxB = Math.max(maxB, b.planMaxMillis());
            n++;
        }
        lines.add("");
        lines.add(String.format(Locale.ROOT, "Mean over %d tiles: today %.0f s, with the load direction %.0f s (%.0f s a map"
            + " saved, %.1f %%); %d tiles faster, %d slower, worst tile %+.0f s. Plan time (this machine, each plan):"
            + " mean %.1f ms → %.1f ms, max %.0f ms → %.0f ms.", n, sumA / n, sumB / n, (sumA - sumB) / n,
            100 * (sumA - sumB) / sumA, faster, slower, worst, msA / n, msB / n, maxA, maxB));
        String md = String.join("\n", lines) + "\n";
        System.out.println(md);
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", "load-direction-model.md"), md);
        assertTrue(synB < synA, md);
    }
}
