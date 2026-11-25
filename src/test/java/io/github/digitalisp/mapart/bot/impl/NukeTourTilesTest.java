package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.impl.DigServer.BlockType;
import io.github.digitalisp.mapart.model.Pos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code build.nukeTour} and {@code build.nukeEntryCorner} on a real job's tiles, when they are at hand: one file per
 * tile in {@code build/nuke-tiles/targets_X_Z.txt} (the tile's nuke targets as "px pz" lines, tile-relative; made
 * locally from a job's stored tiles, never committed). Skipped without them. Each tile as live on soul sand (Soul Speed,
 * one START per tick, every block mined, 2b2t latency, the sweep ending near the north-east corner): the serpentine
 * from the map station at the centre (as live, plus the walk from the corner to the centre), the tour from the centre,
 * and the tour from the entry corner. Prints one line per tile and the totals; asserts the rules on every run.
 */
class NukeTourTilesTest {
    static final Path DIR = Path.of("build", "nuke-tiles");
    /** Ticks of the walk from the entry corner to the map station live (replay 2026-10-23: 8-15 s of sprint). */
    static final int CORNER_TO_CENTRE_TICKS = 150;

    static Map<Pos, BlockType> read(Path f) throws IOException {
        Map<Pos, BlockType> m = new LinkedHashMap<>();
        for (String l : Files.readAllLines(f)) {
            String[] s = l.trim().split("\\s+");
            if (s.length < 2) continue;
            m.put(new Pos(Integer.parseInt(s[0]), NukeRunTest.Y, Integer.parseInt(s[1])),
                NukeMineMovingTest.COBBLED_DEEPSLATE);
        }
        return m;
    }

    static NukeRunTest.Result run(String name, Map<Pos, BlockType> blocks, boolean tour, double sx, double sz) {
        Map<Pos, BlockType> b = new LinkedHashMap<>(blocks);
        NukeRunTest.Scenario sc = NukeMineMovingTest.soulSand(name, b, false, 0.49);
        double[] ne = NukeSweep.corner("NE", 0, 0, 128);
        sc.endX = ne[0];
        sc.endZ = ne[1];
        sc.tour = tour;
        sc.startX = sx;
        sc.startZ = sz;
        return sc.run();
    }

    @Test
    void realTiles() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(DIR), "no tiles in " + DIR.toAbsolutePath());
        List<Path> files;
        try (Stream<Path> s = Files.list(DIR)) {
            files = s.filter(p -> p.getFileName().toString().startsWith("targets_")).sorted().toList();
        }
        String only = System.getenv("NUKE_TILES_ONLY");
        long[] sum = new long[3];
        List<String> lines = new ArrayList<>();
        double[] ne = NukeSweep.corner("NE", 0, 0, 128);
        for (Path f : files) {
            String tile = f.getFileName().toString().replace("targets_", "").replace(".txt", "");
            if (only != null && !only.isBlank() && !List.of(only.split(",")).contains(tile)) continue;
            Map<Pos, BlockType> blocks = read(f);
            if (blocks.size() < 48) continue;
            blocks.remove(new Pos(63, NukeRunTest.Y, 63));
            blocks.remove(new Pos(124, NukeRunTest.Y, 3));
            NukeRunTest.Result serp = run(tile + " serpentine, center", blocks, false, 63.5, 63.5);
            NukeRunTest.Result tourC = run(tile + " tour, center", blocks, true, 63.5, 63.5);
            NukeRunTest.Result tourE = run(tile + " tour, entry", blocks, true, ne[0] - 3.5, ne[1] + 3.5);
            for (NukeRunTest.Result r : List.of(serp, tourC, tourE)) {
                assertEquals(TaskStatus.SUCCESS, r.status(), r.name() + ": " + r.run().failure());
                assertEquals(r.targets().size(), r.run().done(), r.name());
                r.assertRules(1);
            }
            long a = serp.sim().ticks + CORNER_TO_CENTRE_TICKS;
            long b = tourC.sim().ticks + CORNER_TO_CENTRE_TICKS;
            long c = tourE.sim().ticks;
            // the walk from the end to the corner (the way off), at the sweep's speed
            long ea = (long) (Math.hypot(serp.sim().x - ne[0], serp.sim().z - ne[1]) / 0.49);
            long eb = (long) (Math.hypot(tourC.sim().x - ne[0], tourC.sim().z - ne[1]) / 0.49);
            long ec = (long) (Math.hypot(tourE.sim().x - ne[0], tourE.sim().z - ne[1]) / 0.49);
            sum[0] += a + ea;
            sum[1] += b + eb;
            sum[2] += c + ec;
            lines.add(String.format(Locale.ROOT, "%-4s %5d blocks: serpentine %6d (+%4d to NE), tour %6d (+%4d), tour"
                    + " from the corner %6d (+%4d): %5.1f s less; %s", tile, blocks.size(), a, ea, b, eb, c, ec,
                (a + ea - c - ec) / 20.0, tourE.run().summaryLine("x").replaceAll(".*; sweep", "sweep")
                    .replaceAll("; by block.*", "")));
        }
        lines.forEach(System.out::println);
        System.out.printf(Locale.ROOT, "total ticks: serpentine %d, tour %d, tour from the corner %d (%.1f s a tile less"
            + " over %d tiles)%n", sum[0], sum[1], sum[2], (sum[0] - sum[2]) / 20.0 / Math.max(1, lines.size()),
            lines.size());
    }
}
