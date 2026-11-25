package io.github.digitalisp.mapart.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.ParsedSubmission;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.model.TileMetrics;
import io.github.digitalisp.mapart.model.ValidationResult;
import io.github.digitalisp.mapart.testutil.Tiles;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tile counters behind the "tile summary" line are saved with the job and survive a restart. */
class TileMetricsStoreTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_760_000_000_000L), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private Job create(FileJobStore s) throws IOException {
        SortedMap<TileCoord, TileArt> tiles = new TreeMap<>();
        tiles.put(new TileCoord(0, 0), Tiles.of((x, z) -> (x + z) % 3 == 0 ? "minecraft:red_carpet" : null));
        ParsedSubmission sub = new ParsedSubmission("metrics", 1, 1, 4189, tiles, List.of());
        return s.create(sub, new ValidationResult(List.of(), List.of(), Map.of("minecraft:red_carpet", 1), List.of("moss")),
            "metrics", "1", "tester");
    }

    @Test
    void countersSurviveARestartAndOldJobsHaveNone() throws IOException {
        FileJobStore s = new FileJobStore(dir, null, CLOCK);
        Job j = create(s);
        assertNull(j.tiles.getFirst().metrics, "a new tile has no counters yet");

        TileMetrics m = new TileMetrics();
        m.placed = 15903;
        m.repaired = 33;
        m.fromStandSpots = 10618;
        m.laneRuns = 97;
        m.restocks = 51;
        m.loads = 49;
        m.withdrawn = 13090;
        m.setbacks = 412;
        m.fromStart = true;
        j.tiles.getFirst().metrics = m;
        s.save(j);

        TileMetrics k = new FileJobStore(dir, null, CLOCK).get(j.id).orElseThrow().tiles.getFirst().metrics;
        assertEquals(List.of(15903, 33, 10618, 97, 51, 49, 412),
            List.of(k.placed, k.repaired, k.fromStandSpots, k.laneRuns, k.restocks, k.loads, k.setbacks));
        assertEquals(13090L, k.withdrawn);
        assertTrue(k.fromStart);
    }
}
