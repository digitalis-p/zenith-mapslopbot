package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.RestockPlanner.Costs;
import io.github.digitalisp.mapart.testutil.FakeCatalog;
import io.github.digitalisp.mapart.testutil.RestockModel;
import io.github.digitalisp.mapart.testutil.RestockModel.Config;
import io.github.digitalisp.mapart.testutil.RestockModel.Result;
import io.github.digitalisp.mapart.testutil.SyntheticArt;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.testutil.RestockModel.Sweep.END_AWARE;

/**
 * The map and pane of a tile fetched at the previous tile's STORE ({@code build.mapPaneAtStore}) against today's
 * last-load fetch, on the soul sand platform's layout ({@link RestockModel#SOUL_SAND}).
 *
 * <p>Live j0015 (2026-10-23, 8 maps without the clay balls, logs): the tile's last load walks from the sorter to
 * the station 244 blocks east of the tile (the map and pane chests by the output chest) and back to its first lane:
 * 74 s of RESTOCK and 32 s to the lane start against 35 s + 6 s for the other loads, about 65 s a map more; j0011
 * about 101 s, j0014 about 122 s a map. Fetched at STORE (the bot stands by those chests then), the next tile's loads
 * carry the two items instead: 2 material slots fewer on every load (the live kit's 6 kept slots, the pickaxe, axe,
 * shovel, food, XP bottles and anvil, fill {@code build.reservedSlots} = 6, so each kept slot more is a material slot
 * less). The third row is the owner's option without the slot cost: a map and pane chest by the sorter's west end.
 *
 * <p>Scored with the soul sand leg fit (live sprint legs, 2026-10-21..23, 2,943 legs over 30 blocks:
 * {@code t = d / 7.8}, about 0.7 s a Baritone leg; containers 1.76 s each with their clicks); the planner plans with
 * {@link Costs#DEFAULT} as the engine does. Tiles: three j0015-like synthetic tiles (its 9 items, Zipf shares), and the
 * real tiles of {@code MAPART_RESTOCK_TILES} (a directory of TileCodec JSON files; not in git) when set:
 * {@code MAPART_RESTOCK_TILES=/path/j0015 ./gradlew test --tests '*MapPaneAtStoreModelTest' -i}. The table goes to
 * {@code build/map-pane-at-store-model.md}.
 */
class MapPaneAtStoreModelTest {
    /** j0015's items, most used first (its tile 2,0). */
    static final List<String> J0015 = List.of("minecraft:gray_carpet", "minecraft:black_carpet",
        "minecraft:cobbled_deepslate", "minecraft:stone_pressure_plate", "minecraft:light_gray_carpet",
        "minecraft:heavy_weighted_pressure_plate", "minecraft:pale_oak_pressure_plate", "minecraft:white_carpet",
        "minecraft:white_candle");

    /** The soul sand fit: sprint legs d / 7.8 b/s + 0.7 s; a container 1.0 s + 0.1 s a click (1-tick clicks). */
    static final Costs SOUL = new Costs(7.8, 0.7, Costs.DEFAULT.unwindSeconds(), 1.0, 0.1,
        Costs.DEFAULT.laneRunSeconds());

    /** Where the near map and pane chests would be used from: the walkway's west end by the drop point. */
    static final Pos NEAR_STAND = new Pos(-3, RestockModel.ART_Y, -4);

    private final FakeCatalog catalog = new FakeCatalog();

    /** Live: AUTO (SPLIT for 9-wide lanes), 30 material slots, the map and pane on the last load, lazy drop. */
    static Config today() {
        return new Config("today", () -> new ConfiguredRestockPlanner(() -> "AUTO"), 30, 2, true, END_AWARE, true,
            RestockModel.Withdraw.STACKS, SOUL).restockRules("today", 6.0, true).plannedWith("today", Costs.DEFAULT);
    }

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void mapAndPaneAtStoreBeatTheLastLoadsStationTrip() throws IOException {
        Map<String, TileArt> tiles = new LinkedHashMap<>();
        for (long seed = 1; seed <= 3; seed++)
            tiles.put("j0015-like " + seed, SyntheticArt.banded(seed, J0015, 6, 16, 64, 0.30));
        String dir = System.getenv("MAPART_RESTOCK_TILES");
        if (dir != null && Files.isDirectory(Path.of(dir))) {
            try (Stream<Path> s = Files.list(Path.of(dir))) {
                for (Path p : s.filter(f -> f.getFileName().toString().matches("tile_\\d+_\\d+\\.json")).sorted().toList())
                    tiles.put(p.getFileName().toString(), readTile(p));
            }
        }
        Config today = today();
        Config store = today.mapPaneAtStore("map+pane at STORE", 28);
        List<String> lines = new ArrayList<>();
        lines.add("| Tile | Config | Trips | Map/pane fetches | Restock s | Lane change s | Total s | vs today |");
        lines.add("|---|---|---:|---:|---:|---:|---:|---:|");
        double sumToday = 0;
        double sumStore = 0;
        double sumNear = 0;
        int synthetic = 0;
        double synToday = 0;
        double synStore = 0;
        for (var e : tiles.entrySet()) {
            RestockModel soul = new RestockModel(e.getValue(), 4, catalog, RestockModel.SOUL_SAND);
            RestockModel near = new RestockModel(e.getValue(), 4, catalog,
                RestockModel.SOUL_SAND.mapPaneAt("soul sand, near map/pane", NEAR_STAND));
            Result a = soul.run(today);
            Result b = soul.run(store);
            Result c = near.run(today);
            assertEquals(1, a.mapPaneFetches, a.toString());
            assertEquals(1, b.mapPaneFetches, b.toString());
            assertTrue(b.maxSlots <= 28, b.toString());
            for (Result r : List.of(a, b, c))
                lines.add(String.format(Locale.ROOT, "| %s | %s | %d | %d | %.0f | %.0f | %.0f | %+.0f |", e.getKey(),
                    r == c ? "today, map/pane chest by the sorter" : r.name, r.trips(), r.mapPaneFetches,
                    r.restockSeconds(), r.laneChangeSeconds, r.totalSeconds(), r.totalSeconds() - a.totalSeconds()));
            sumToday += a.totalSeconds();
            sumStore += b.totalSeconds();
            sumNear += c.totalSeconds();
            if (e.getKey().startsWith("j0015-like")) {
                synthetic++;
                synToday += a.totalSeconds();
                synStore += b.totalSeconds();
            }
        }
        int n = tiles.size();
        lines.add("");
        lines.add(String.format(Locale.ROOT, "Mean over %d tiles: today %.0f s, map+pane at STORE %.0f s (%+.0f s a map), "
                + "near map/pane chest %.0f s (%+.0f s a map).", n, sumToday / n, sumStore / n, (sumStore - sumToday) / n,
            sumNear / n, (sumNear - sumToday) / n));
        String md = String.join("\n", lines) + "\n";
        System.out.println(md);
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", "map-pane-at-store-model.md"), md);
        // the station trip outweighs the two slots on the synthetic j0015 tiles, and the chest by the sorter beats both
        assertTrue(synStore < synToday, md);
        assertTrue(sumNear < sumStore, md);
        assertEquals(3, synthetic);
    }

    /** A TileCodec JSON tile (palette + base64 little-endian shorts, -1 = empty). */
    static TileArt readTile(Path p) throws IOException {
        String json = Files.readString(p);
        List<String> palette = new ArrayList<>();
        int i0 = json.indexOf('[', json.indexOf("\"palette\""));
        assertTrue(i0 > 0, "palette in " + p);
        for (int i = i0 + 1; i < json.length() && json.charAt(i) != ']'; i++) {
            if (json.charAt(i) != '"') continue;
            int end = json.indexOf('"', i + 1);
            palette.add(json.substring(i + 1, end));
            i = end;
        }
        Matcher cells = Pattern.compile("\"cells\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        assertTrue(cells.find(), "cells in " + p);
        ByteBuffer b = ByteBuffer.wrap(Base64.getDecoder().decode(cells.group(1))).order(ByteOrder.LITTLE_ENDIAN);
        TileArt art = new TileArt();
        for (int i = 0; i < TileArt.SIZE * TileArt.SIZE; i++) {
            short v = b.getShort(2 * i);
            if (v < 0) continue;
            art.set(i % TileArt.SIZE, i / TileArt.SIZE, BlockSpec.parse(palette.get(v)));
        }
        return art;
    }
}
