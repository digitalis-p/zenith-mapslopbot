package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.TileRunner;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-reach greedy ({@code build.placeInReachGreedy}) against today's lanes on whole
 * tiles in {@link TileRunner} (AIRPLACE defaults, acks 5 ticks late, loads of whole lanes up to 1,500 cells, each load
 * started from a sorter spot off the tile, the Baritone stand-in moving the bot along its route), paced walk off and
 * on. Prints a table (stdout and {@code build/in-reach-greedy-model.md}). The ellen tile of the routing study is read
 * from {@code MAPART_ROUTING_TILE=/path/tile_0_0.json} (TileCodec JSON) when set:
 * {@code MAPART_ROUTING_TILE=... ./gradlew test --tests '*InReachGreedyModelTest' -i}.
 */
class InReachGreedyModelTest {
    private static final Pattern GREEDY = Pattern.compile(
        "in-reach greedy: (\\d+) cells of later lanes, (\\d+) of this lane while walking to it, (\\d+) clicks");

    /** One whole-tile run; returns ticks, walking ticks... as a row. */
    static Row run(TileArt art, boolean greedy, boolean paced, long seed, int loadCells) {
        TileRunner r = new TileRunner(seed);
        r.build.placeMode = "AIRPLACE";
        r.build.rotatePlayer = true;
        r.build.reach = 4.5;
        r.build.pacedLaneWalk = paced;
        r.build.placeInReachGreedy = greedy;
        r.sim.confirmDelay = 5;
        r.sim.countLag = 5;
        r.sim.echoes = true;
        r.sim.baritoneMoves = true;
        r.loadCells = loadCells;
        r.sorter = new double[]{r.geo.minX() + 20.5, r.geo.minZ() - 12.5};
        boolean ok = r.build(art, 2);
        Row row = new Row();
        row.greedy = greedy;
        row.paced = paced;
        row.ok = ok;
        row.ticks = r.ticks;
        row.cells = art.nonEmptyCount();
        row.accepted = r.sim.accepted;
        row.extras = r.sim.extrasPlaced;
        row.walkingClicks = r.sim.walkingClicks;
        row.pathBlocked = r.sim.pathBlocked;
        row.boxRejects = r.sim.boxRejects;
        row.wrongClicks = r.sim.wrongClicks;
        row.farRejects = r.sim.farRejects;
        row.spotLanes = r.spotLanes();
        row.passes = r.passes;
        row.loads = r.loads;
        row.pockets = r.sim.pocketTicks;
        row.narrow = r.sim.narrowStandTicks;
        for (String line : r.sim.log) {
            Matcher m = GREEDY.matcher(line);
            if (m.find()) row.early += Integer.parseInt(m.group(2));
        }
        row.report = r.report(art);
        return row;
    }

    static final class Row {
        boolean greedy;
        boolean paced;
        boolean ok;
        long ticks;
        int cells;
        int accepted;
        int extras;
        int early;
        int walkingClicks;
        int pathBlocked;
        int boxRejects;
        int wrongClicks;
        int farRejects;
        long spotLanes;
        int passes;
        int loads;
        int pockets;
        int narrow;
        String report;

        /** {@code o}'s counts added (another seed). */
        void add(Row o) {
            ok &= o.ok;
            ticks += o.ticks;
            cells += o.cells;
            accepted += o.accepted;
            extras += o.extras;
            early += o.early;
            walkingClicks += o.walkingClicks;
            pathBlocked += o.pathBlocked;
            boxRejects += o.boxRejects;
            wrongClicks += o.wrongClicks;
            farRejects += o.farRejects;
            spotLanes += o.spotLanes;
            passes = Math.max(passes, o.passes);
            loads += o.loads;
            pockets += o.pockets;
            narrow += o.narrow;
        }

        String line(String tile, Row base) {
            double rate = cells * 20.0 / Math.max(1, ticks);
            String change = base == null ? "" : String.format(Locale.ROOT, "%+.1f %%", 100.0 * (ticks - base.ticks) / base.ticks);
            return String.format(Locale.ROOT, "| %s | %s | %s | %,d | %s | %.2f | %,d | %,d | %,d | %d | %d | %d | %d |",
                tile, paced ? "paced" : "stop-go", greedy ? "P1" : "P0", ticks, change, rate, extras, early,
                walkingClicks, spotLanes, Math.max(0, passes - 1), pathBlocked, pockets);
        }
    }

    /** TileCodec JSON (palette + little-endian shorts, row-major z then x, -1 = empty) → TileArt. */
    static TileArt readTile(Path p) throws IOException {
        String json = Files.readString(p);
        List<String> palette = new ArrayList<>();
        int i0 = json.indexOf('[', json.indexOf("\"palette\""));
        assertTrue(i0 > 0, "palette in " + p);
        for (int i = i0 + 1; i < json.length() && json.charAt(i) != ']'; i++) {
            if (json.charAt(i) != '"') continue;
            int end = json.indexOf('"', i + 1); // state strings hold no quotes
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

    /** P0 and P1, stop-go and paced, summed over {@code seeds} seeds; P1 never slower when {@code faster}. */
    private static List<String> table(String tile, TileArt art, int seeds, int loadCells, boolean faster) {
        List<String> out = new ArrayList<>();
        for (boolean paced : new boolean[]{false, true}) {
            Row[] sum = new Row[2];
            for (int k = 0; k < 2; k++) {
                for (long seed = 7; seed < 7 + seeds; seed++) {
                    Row r = run(art, k == 1, paced, seed, loadCells);
                    assertTrue(r.ok, tile + " " + (r.greedy ? "P1" : "P0") + " built\n" + r.report);
                    assertEquals(0, r.wrongClicks, r.report);
                    assertEquals(0, r.boxRejects, r.report);
                    assertEquals(0, r.farRejects, r.report);
                    assertEquals(0, r.pathBlocked, "never a full block on Baritone's way\n" + r.report);
                    assertEquals(0, r.pockets, "never walled in\n" + r.report);
                    if (sum[k] == null) sum[k] = r;
                    else sum[k].add(r);
                }
            }
            String name = tile + ", loads " + (loadCells > 0 ? "≤ " + loadCells : "whole tile") + ", " + seeds + " seed"
                + (seeds == 1 ? "" : "s");
            out.add(sum[0].line(name, null));
            out.add(sum[1].line(name, sum[0]));
            if (faster) assertTrue(sum[1].ticks < sum[0].ticks, out.getLast());
        }
        return out;
    }

    @Test
    void greedyAgainstTodayOnWholeTiles() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("| Tile | Walk | Policy | Ticks | Change | Cells/s | Extras | Early | Walking clicks | Spot lanes"
            + " | Repair passes | Path blocked | Pocket ticks |");
        lines.add("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|");
        lines.addAll(table("noise 60 % full", TileRunner.noise(11, 0, 0, 128, 128, 0.6), 1, 1500, true));
        String tile = System.getenv("MAPART_ROUTING_TILE");
        if (tile != null && Files.isRegularFile(Path.of(tile))) {
            TileArt ellen = readTile(Path.of(tile));
            for (int load : new int[]{1500, 2500}) lines.addAll(table("ellen tile_0_0", ellen, 3, load, false));
        }
        String md = String.join("\n", lines) + "\n";
        System.out.println(md);
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", "in-reach-greedy-model.md"), md);
    }
}
