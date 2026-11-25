package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.PlacementListener;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.TileRunner;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Line ends ({@link LaneRun} class javadoc "Line ends", {@code build.trimLaneEnds}) on the simulated platform.
 *
 * <p>Live 2b2t, job j0002 "ellen", 2026-10-12: the offhand held clay, so airplace was off and tiles [0,3], [1,2] and
 * [0,4] were built in 5-wide LOOK lanes. The restock planner cut lanes at a cell count; the engine re-planned the rest
 * from the world, the line one row past the cells at each end, in either direction. Every walled re-entry of those
 * tiles (9 lane runs, 2,232 of their 2,283 stand-spot cells) had full blocks on its line only in the first pass's last
 * row: one block at the line's end (after its cells) or start (before them), and in two cases the last row's own centre
 * as well. The whole rest went to stand spots at 1-6 b/s against 10-12 b/s walking. The neighbourhoods below are the
 * live art (tile-relative columns and rows of the job's tile files), built as the first pass left them.
 */
class LaneEndTrimTest {
    /** [1,2] lane 2, columns 10-14, rows 88-127. First pass northward from row 127, 100 cells (rows 108-127). */
    private static final String[] LEGEND_1_2 = {
        "a", "minecraft:stone_pressure_plate", "b", "minecraft:cyan_carpet", "c", "minecraft:glow_lichen[down=true]",
        "d", "minecraft:gray_carpet", "e", "minecraft:light_gray_carpet", "f", "minecraft:cyan_terracotta",
        "g", "minecraft:purple_terracotta", "h", "minecraft:light_gray_terracotta", "i", "minecraft:pointed_dripstone",
        "j", "minecraft:gray_terracotta"};
    private static final String[] ROWS_1_2 = {
        "aabcd", "aaacd", "aabef", "aaaef", "aaabf", "faaaf", "ffaaa", "fffaa", "fffff", "fgfff",
        "fffff", "fffff", "fffff", "ffafa", "gffaa", "fgafa", "gffha", "ffgfh", "gfffa", "fgfgf",
        "gfgff", "fgfgf", "gfffg", "gfgff", "gfgfg", "fgfgf", "gfgfg", "gdgff", "dgdgf", "dgfgf",
        "ddgdg", "gdddg", "dgddg", "ddddd", "idgdg", "jdddd", "didgd", "fiidg", "fdiid", "aadid"};

    /** [0,4] lane 12, columns 60-64, rows 76-127. First pass S from row 0, 420 cells (rows 0-83). */
    private static final String[] LEGEND_0_4 = {
        "a", "minecraft:pink_terracotta", "b", "minecraft:white_candle", "c", "minecraft:white_carpet",
        "d", "minecraft:pink_carpet", "e", "minecraft:pale_oak_pressure_plate", "f", "minecraft:cherry_pressure_plate",
        "g", "minecraft:crimson_pressure_plate", "h", "minecraft:purple_terracotta", "i", "minecraft:magenta_terracotta",
        "j", "minecraft:gray_carpet", "k", "minecraft:red_carpet", "l", "minecraft:light_blue_terracotta",
        "m", "minecraft:cyan_carpet", "n", "minecraft:warped_pressure_plate", "o", "minecraft:glow_lichen[down=true]",
        "p", "minecraft:clay", "q", "minecraft:light_blue_carpet", "r", "minecraft:light_gray_carpet"};
    private static final String[] ROWS_0_4 = {
        "abccc", "adecc", "aabcc", "aafec", "gaafc", "hiadb", "jiakd", "jhiga", "jjjhi", "lmnll",
        "obepp", "ccepp", "cbppp", "bpppq", "prqlr", "lrrlr", "rpppp", "ppppp", "ppppp", "ppppp",
        "ppppp", "ppppp", "ppppp", "ppppp", "ppppp", "ppppp", "ppppp", "ppppp", "ppppp", "ppppp",
        "ppppp", "ppppp", "ppppp", "ppppp", "ppppp", "ppppp", "ppppp", "ppppp", "ppppp", "ppppp",
        "pppbb", "ppbcc", "fbccc", "bcccc", "bcccc", "dbecc", "idbbb", "rrlpp", "ppppp", "ppppp",
        "ppppp", "ppppp"};

    /** [0,3] lane 22, columns 110-114, rows 0-12: all light gray terracotta. First pass N from row 127, 613 cells. */
    private static final String[] LEGEND_0_3 = {"a", "minecraft:light_gray_terracotta"};
    private static final String[] ROWS_0_3 = {
        "aaaaa", "aaaaa", "aaaaa", "aaaaa", "aaaaa", "aaaaa", "aaaaa", "aaaaa", "aaaaa", "aaaaa",
        "aaaaa", "aaaaa", "aaaaa"};

    /** The live run's settings: AIRPLACE with airplace off (LOOK cells, 5-wide lanes), real rotation, reach 4.5. */
    private static TileRunner runner(long seed, boolean trim) {
        TileRunner r = new TileRunner(seed);
        r.build.placeMode = "AIRPLACE";
        r.build.rotatePlayer = true;
        r.build.grimRotate = true;
        r.build.reach = 4.5;
        r.build.pacedLaneWalk = true;
        r.build.airPlaceLaneHalfWidth = 2; // PlaceMode.laneHalfWidth with airplace unavailable: the LOOK width
        r.build.trimLaneEnds = trim;
        r.build.laneOpenSegment = false; // isolate the line-end trim and its historical stand-spot baseline
        r.sim.airPlaceReady = false;
        r.finiteStock = true;
        return r;
    }

    private static TileArt art(String[] legend, String[] rows, int x0, int z0) {
        Map<Character, BlockSpec> m = new HashMap<>();
        for (int i = 0; i < legend.length; i += 2) m.put(legend[i].charAt(0), BlockSpec.parse(legend[i + 1]));
        TileArt a = new TileArt();
        for (int i = 0; i < rows.length; i++) {
            for (int j = 0; j < rows[i].length(); j++) a.set(x0 + j, z0 + i, m.get(rows[i].charAt(j)));
        }
        return a;
    }

    /** One run of the lane: cells, placed, ticks, cells from stand spots, walled, walked over, trimmed, jumps, log. */
    record Pass(int cells, int placed, long ticks, int spotPlaced, boolean walled, boolean walkedOver, boolean trimmed,
                int jumps, TaskStatus status, String log) {
        @Override
        public String toString() {
            return String.format("cells %3d placed %3d ticks %4d rate %4.1f/s spot %3d jumps %d%s%s%s", cells, placed,
                ticks, placed * 20.0 / Math.max(1, ticks), spotPlaced, jumps, walled ? " WALLED" : "",
                trimmed ? " trimmed" : "", walkedOver ? " walked over" : "");
        }
    }

    private static final Pattern FINISH = Pattern.compile("Lane \\d+: (\\d+) placed, (\\d+) skipped of (\\d+) in");
    private static final Pattern SPOTS = Pattern.compile("; (\\d+) from (\\d+) stand spots");
    private static final PlacementListener NONE = new PlacementListener() {
        @Override public void placed(Placement placement) { }
        @Override public void skipped(Placement placement, String reason) { }
    };

    /**
     * The re-entry of lane {@code index} after the first pass left {@code built} (art column, art row) in the world,
     * with exactly its cells loaded; the bot comes from the line of the lane before, level with the lane's start (a
     * lane change: the approach is short and the same with and without the trim).
     */
    private static Pass reentry(TileRunner r, TileArt art, int index, BiPredicate<Integer, Integer> built) {
        for (int z = 0; z < TileArt.SIZE; z++) {
            for (int x = 0; x < TileArt.SIZE; x++) {
                BlockSpec b = art.get(x, z);
                if (b != null && built.test(x, z)) r.world.put(r.geo.cell(x, z), b);
            }
        }
        Lane l = LaneReentryTest.lane(r, art, index);
        assertNotNull(l, "lane " + index + " left to build");
        r.stock.clear();
        for (Placement p : l.placements()) r.stock.merge(p.itemId(), 1, Integer::sum);
        r.sim.teleport(l.from().x() - 5 + 0.5, r.geo.artY(), l.from().z() + 0.5);
        int from = r.sim.log.size();
        int jumps = r.sim.jumps;
        TaskStatus st = r.sim.runLane(l, r.params(), NONE, 4000 + 400 * l.placements().size());
        List<String> log = List.copyOf(r.sim.log.subList(from, r.sim.log.size()));
        int placed = 0;
        int spot = 0;
        for (String line : log) {
            Matcher m = FINISH.matcher(line);
            if (m.find()) placed = Integer.parseInt(m.group(1));
            Matcher s = SPOTS.matcher(line);
            if (s.find()) spot = Integer.parseInt(s.group(1));
        }
        String text = String.join("\n", log);
        return new Pass(l.placements().size(), placed, r.sim.lastTicks, spot, text.contains("walking line walled"),
            text.contains("; walking over them"), text.contains("walking line ends in full blocks"),
            r.sim.jumps - jumps, st, text);
    }

    private record Result(Pass before, Pass after) { }

    /** The case with the trim off (as live) and on; both build the lane; the trimmed walk is clean. */
    private static Result both(String name, TileArt art, int index, BiPredicate<Integer, Integer> built, int cells) {
        TileRunner off = runner(7, false);
        Pass before = reentry(off, art, index, built);
        TileRunner on = runner(7, true);
        Pass after = reentry(on, art, index, built);
        String what = name + ": before " + before + "\n  after  " + after + "\n" + after.log();
        System.out.println(name + ": before " + before + "\n  after  " + after);
        assertEquals(cells, after.cells(), "the live re-entry's cells\n" + what);
        assertTrue(off.matches(art), "built before\n" + off.report(art));
        assertTrue(on.matches(art), "built after\n" + on.report(art));
        assertTrue(before.walled() && !before.trimmed(), "as live: walled, every cell from stand spots\n" + what);
        assertEquals(before.cells(), before.spotPlaced(), what);
        assertTrue(!after.walled() && after.trimmed() && !after.walkedOver(), what);
        assertEquals(TaskStatus.SUCCESS, after.status(), what);
        assertEquals(after.cells(), after.placed(), what);
        LaneReentryTest.assertClean(on, what);
        return new Result(before, after);
    }

    @Test
    void lineEndingInTheFirstPassRowIsWalked() {
        // [1,2] lane 2 (live: 540 cells from stand spots in 2,903 ticks): the line ends in row 108, purple terracotta
        TileArt art = art(LEGEND_1_2, ROWS_1_2, 10, 88);
        Result r = both("[1,2] lane 2", art, 2, (x, z) -> z >= 108, 100);
        assertTrue(r.after().log().contains("walking line cells 0..20 of 0..21"), r.after().log());
        assertTrue(r.after().spotPlaced() <= 2, "at most a cell or two at the end from stand spots\n" + r.after().log());
        assertTrue(r.after().ticks() * 3 < r.before().ticks() * 2, r.before() + "\n" + r.after());
    }

    @Test
    void lineStartingInTheFirstPassRowIsWalked() {
        // [0,4] lane 12 (live: 220 cells from stand spots in 1,109 ticks): the line starts in row 83, magenta
        // terracotta; the rest is mostly clay
        TileArt art = art(LEGEND_0_4, ROWS_0_4, 60, 76);
        Result r = both("[0,4] lane 12", art, 12, (x, z) -> z <= 83, 220);
        assertTrue(r.after().log().contains("walking line cells 1..44 of 0..44"), r.after().log());
        assertTrue(r.after().spotPlaced() <= 2, "at most a cell or two from stand spots\n" + r.after().log());
        assertTrue(r.after().ticks() * 3 < r.before().ticks() * 2, r.before() + "\n" + r.after());
    }

    @Test
    void partlyBuiltLastRowIsPlacedFromTheRowBeforeIt() {
        // [0,3] lane 22 (live: 27 cells from stand spots in 781 ticks): the first pass ended in row 5 after its centre
        // and the two cells beside it, so the line holds full blocks in rows 5 and 6; all of it terracotta
        TileArt art = art(LEGEND_0_3, ROWS_0_3, 110, 0);
        Result r = both("[0,3] lane 22", art, 22, (x, z) -> z >= 6 || (z == 5 && Math.abs(x - 112) <= 1), 27);
        assertTrue(r.after().log().contains("walking line cells 0..4 of 0..6"), r.after().log());
        // row 5's two outer cells stay for stand spots (beside a wall of the line, in the slot it leaves)
        assertTrue(r.after().spotPlaced() <= 2, r.after().log());
    }

    @Test
    void wallsInsideTheLaneStillWallIt() {
        // a full block of the line between the lane's rows (a cell skipped for a missing item, live [0,4] lanes 2 and
        // 3) is no line end: stand spots as before
        TileArt art = art(LEGEND_1_2, ROWS_1_2, 10, 88);
        TileRunner r = runner(7, true);
        Pass p = reentry(r, art, 2, (x, z) -> z >= 108 || (z == 98 && x == 12));
        assertTrue(p.walled() && !p.trimmed(), p + "\n" + p.log());
        assertTrue(r.matches(art), r.report(art));
    }

    @Test
    void walkOverAirplaceLanesIsUnchanged() {
        // airplace on: the walk steps onto the line's blocks as before (LaneReentryTest); no trim
        TileArt art = art(LEGEND_1_2, ROWS_1_2, 10, 88);
        TileRunner r = runner(7, true);
        r.sim.airPlaceReady = true;
        Pass p = reentry(r, art, 2, (x, z) -> z >= 108);
        assertTrue(p.walkedOver() && !p.trimmed() && !p.walled(), p + "\n" + p.log());
        assertTrue(r.matches(art), r.report(art));
    }

    @Test
    void killSwitchKeepsTheStandSpots() {
        TileArt art = art(LEGEND_1_2, ROWS_1_2, 10, 88);
        TileRunner r = runner(7, false);
        Pass p = reentry(r, art, 2, (x, z) -> z >= 108);
        assertTrue(p.walled(), p.log());
        assertFalse(p.trimmed(), p.log());
        assertEquals(p.cells(), p.spotPlaced(), p.log());
    }
}
