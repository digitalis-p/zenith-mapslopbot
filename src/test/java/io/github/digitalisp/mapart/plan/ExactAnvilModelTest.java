package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import io.github.digitalisp.mapart.model.TileArt;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The slot a carried anvil stack holds ({@code build.exactAnvil}), on the soul sand layout ({@link RestockModel#SOUL_SAND})
 * with the map and pane carried from STORE ({@code build.mapPaneAtStore}): the live kit then is the pickaxe, axe,
 * shovel, food, XP bottles, map, pane and 47 anvils, 8 slots against {@code build.reservedSlots} = 6, so 28 material
 * slots; one anvil at a time (none carried between renames) makes it 29. Scored with the soul sand leg fit
 * ({@link MapPaneAtStoreModelTest#SOUL}); tiles as there (three j0015-like synthetic ones, and the real ones of
 * {@code MAPART_RESTOCK_TILES} when set). The table goes to {@code build/exact-anvil-model.md}.
 */
class ExactAnvilModelTest {
    private final FakeCatalog catalog = new FakeCatalog();

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void oneMaterialSlotMoreOnEveryLoad() throws IOException {
        Map<String, TileArt> tiles = new LinkedHashMap<>();
        for (long seed = 1; seed <= 3; seed++)
            tiles.put("j0015-like " + seed, SyntheticArt.banded(seed, MapPaneAtStoreModelTest.J0015, 6, 16, 64, 0.30));
        String dir = System.getenv("MAPART_RESTOCK_TILES");
        if (dir != null && Files.isDirectory(Path.of(dir))) {
            try (Stream<Path> s = Files.list(Path.of(dir))) {
                for (Path p : s.filter(f -> f.getFileName().toString().matches("tile_\\d+_\\d+\\.json")).sorted().toList())
                    tiles.put(p.getFileName().toString(), MapPaneAtStoreModelTest.readTile(p));
            }
        }
        Config today = MapPaneAtStoreModelTest.today();
        Config stack = today.mapPaneAtStore("map+pane at STORE, anvil stack", 28);
        Config exact = today.mapPaneAtStore("map+pane at STORE, one anvil at a time", 29);
        List<String> lines = new ArrayList<>();
        lines.add("| Tile | Config | Trips | Restock s | Lane change s | Total s | vs stack |");
        lines.add("|---|---|---:|---:|---:|---:|---:|");
        double sumStack = 0;
        double sumExact = 0;
        double synStack = 0;
        double synExact = 0;
        int tripsStack = 0;
        int tripsExact = 0;
        for (var e : tiles.entrySet()) {
            RestockModel soul = new RestockModel(e.getValue(), 4, catalog, RestockModel.SOUL_SAND);
            Result a = soul.run(stack);
            Result b = soul.run(exact);
            assertTrue(b.maxSlots <= 29, b.toString());
            for (Result r : List.of(a, b))
                lines.add(String.format(Locale.ROOT, "| %s | %s | %d | %.0f | %.0f | %.0f | %+.0f |", e.getKey(), r.name,
                    r.trips(), r.restockSeconds(), r.laneChangeSeconds, r.totalSeconds(), r.totalSeconds() - a.totalSeconds()));
            sumStack += a.totalSeconds();
            sumExact += b.totalSeconds();
            tripsStack += a.trips();
            tripsExact += b.trips();
            if (e.getKey().startsWith("j0015-like")) {
                synStack += a.totalSeconds();
                synExact += b.totalSeconds();
            }
        }
        int n = tiles.size();
        lines.add("");
        lines.add(String.format(Locale.ROOT, "Mean over %d tiles: anvil stack %.0f s (%.2f trips), one anvil %.0f s (%.2f trips):"
            + " %+.1f s a map.", n, sumStack / n, (double) tripsStack / n, sumExact / n, (double) tripsExact / n,
            (sumExact - sumStack) / n));
        String md = String.join("\n", lines) + "\n";
        System.out.println(md);
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", "exact-anvil-model.md"), md);
        assertTrue(synExact < synStack, md);
    }
}
