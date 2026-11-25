package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.PlacementListener;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.RestockLoads;
import io.github.digitalisp.mapart.testutil.LaneSim;
import io.github.digitalisp.mapart.testutil.TileRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A partly built AIRPLACE lane entered again after a restock, on the simulated platform
 * ({@link io.github.digitalisp.mapart.testutil.LaneSim}: collision, step-up jumps, falling, the noobline, a Baritone stand-in).
 *
 * <p>Live 2b2t, job "ellen", 2026-10-11 (mixed carpets, terracotta and other full blocks, 9-wide lanes): a lane's first
 * pass placed part of it, the load ran out, and every re-entry logged "walking line walled by full blocks" and placed
 * the rest from stand spots: 72 % of the blocks at 3.8/s against 9.4/s walking (13/s on a clean lane). The full blocks
 * the first pass placed on the walking line behind the bot walled it. With the split restock planner a lane is cut at
 * a cell count and finished on the next load (from the same end or the other one); the wall is then the one or two
 * full blocks of the line at the cut. Now the walk steps up onto them ({@code build.walkWalledLanes}).
 */
class LaneReentryTest {
    private static final String[] CARPETS = {"minecraft:white_carpet", "minecraft:black_carpet",
        "minecraft:red_carpet", "minecraft:pink_carpet", "minecraft:yellow_carpet"};
    private static final String[] FULL = {"minecraft:white_terracotta", "minecraft:red_terracotta",
        "minecraft:mushroom_stem", "minecraft:packed_ice"};
    private static final String PLATE = "minecraft:oak_pressure_plate";

    private static final Pattern FINISH = Pattern.compile("Lane \\d+: (\\d+) placed, (\\d+) skipped of (\\d+) in");
    private static final Pattern SPOTS = Pattern.compile("; (\\d+) from (\\d+) stand spots");
    private static final PlacementListener NONE = new PlacementListener() {
        @Override public void placed(Placement placement) { }
        @Override public void skipped(Placement placement, String reason) { }
    };

    /** Mixed art over columns [x0, x1) of every row: about {@code solidShare} full blocks, the rest carpets and plates. */
    static TileArt mixed(long seed, int x0, int x1, double solidShare) {
        Random r = new Random(seed);
        TileArt a = new TileArt();
        for (int x = x0; x < x1; x++) {
            for (int z = 0; z < TileArt.SIZE; z++) {
                double v = r.nextDouble();
                String id = v < solidShare ? FULL[r.nextInt(FULL.length)]
                    : v < solidShare + 0.08 ? PLATE : CARPETS[r.nextInt(CARPETS.length)];
                a.set(x, z, BlockSpec.of(id));
            }
        }
        return a;
    }

    /** AIRPLACE runner with the live defaults (9-wide lanes, reach 4.5, silent looks) and a finite load. */
    static TileRunner runner(long seed, boolean walkWalled) {
        TileRunner r = new TileRunner(seed);
        r.build.placeMode = "AIRPLACE";
        r.build.rotatePlayer = false;
        r.build.grimRotate = true;
        r.build.reach = 4.5;
        r.build.walkWalledLanes = walkWalled;
        // the previous behaviour without the walk over the line: no line-end trim either (LaneEndTrimTest covers it)
        r.build.trimLaneEnds = walkWalled;
        r.build.laneOpenSegment = walkWalled;
        r.finiteStock = true;
        return r;
    }

    /** One run of a lane: its cells, placed, ticks, cells from stand spots, walled (stand spots only), jumps, pockets. */
    record Pass(int cells, int placed, long ticks, int spotPlaced, boolean walled, boolean walkedOver, int jumps,
                int pocketTicks, int narrowTicks, int lineNarrowTicks, TaskStatus status, String log) {
        double rate() {
            return placed * 20.0 / Math.max(1, ticks);
        }

        @Override
        public String toString() {
            return String.format("cells %4d placed %4d ticks %5d rate %5.1f/s spot %4d jumps %3d pocket %d narrow %d"
                    + " (by the line %d)%s%s", cells, placed, ticks, rate(), spotPlaced, jumps, pocketTicks, narrowTicks,
                lineNarrowTicks, walled ? " WALLED" : "", walkedOver ? " walked over" : "");
        }
    }

    static Lane lane(TileRunner r, TileArt art, int index) {
        for (Lane l : r.plan(art)) if (l.index() == index) return l;
        return null;
    }

    /** Remaining need per item of lane {@code index} (planned against the world). */
    static Map<String, Integer> need(TileRunner r, TileArt art, int index) {
        Map<String, Integer> n = new TreeMap<>();
        Lane l = lane(r, art, index);
        if (l != null) for (Placement p : l.placements()) n.merge(p.itemId(), 1, Integer::sum);
        return n;
    }

    /** Runs {@code l} with the current stock, the bot coming back from the chests north of the tile. */
    static Pass run(TileRunner r, Lane l) {
        r.sim.teleport(l.from().x() + 0.5, r.geo.artY(), r.geo.minZ() - 2.5);
        int from = r.sim.log.size();
        int jumps = r.sim.jumps;
        int pocket = r.sim.pocketTicks;
        int narrow = r.sim.narrowStandTicks;
        r.sim.narrowStands.clear();
        // still on the floor in a cell with two or more full-block sides, one of them a block of the walking line
        int lineX = l.from().x();
        int[] lineNarrow = {0};
        double[] last = {Double.NaN, Double.NaN, Double.NaN};
        TaskStatus st = r.sim.runLane(l, r.params(), NONE, 4000 + 400 * l.placements().size(), (t, run) -> {
            int fx = (int) Math.floor(r.sim.x);
            int fz = (int) Math.floor(r.sim.z);
            boolean still = r.sim.x == last[0] && r.sim.y == last[1] && r.sim.z == last[2];
            if (still && r.sim.y < r.geo.artY() + 0.5 && fx == lineX && r.sim.walledSides(fx, fz) >= 2
                && (solid(r, lineX, fz - 1) || solid(r, lineX, fz + 1))) {
                lineNarrow[0]++;
            }
            last[0] = r.sim.x;
            last[1] = r.sim.y;
            last[2] = r.sim.z;
            return true;
        });
        List<String> log = new ArrayList<>(r.sim.log.subList(from, r.sim.log.size()));
        int placed = 0;
        int spot = 0;
        boolean walled = false;
        boolean over = false;
        for (String line : log) {
            if (line.contains("walking line walled")) walled = true;
            if (line.endsWith("; walking over them")) over = true;
            Matcher m = FINISH.matcher(line);
            if (m.find()) placed = Integer.parseInt(m.group(1));
            Matcher s = SPOTS.matcher(line);
            if (s.find()) spot = Integer.parseInt(s.group(1));
        }
        return new Pass(l.placements().size(), placed, r.sim.lastTicks, spot, walled, over, r.sim.jumps - jumps,
            r.sim.pocketTicks - pocket, r.sim.narrowStandTicks - narrow, lineNarrow[0], st, String.join("\n", log)
            + (r.sim.narrowStands.isEmpty() ? "" : "\nnarrow stands " + r.sim.narrowStands));
    }

    private static boolean solid(TileRunner r, int x, int z) {
        return LaneSim.collisionTopOf(r.world.getOrDefault(new Pos(x, r.geo.artY(), z), BlockSpec.AIR)) > 0.6;
    }

    /** Runs {@code l} with exactly its own cells in stock (a load planned for it). */
    static Pass runLoaded(TileRunner r, Lane l) {
        r.stock.clear();
        for (Placement p : l.placements()) r.stock.merge(p.itemId(), 1, Integer::sum);
        return run(r, l);
    }

    /** No placement into the bot's box, no wrong click, no airplace out of range, never off the platform, no setback. */
    static void assertClean(TileRunner r, String what) {
        String rep = what + "\n" + r.report(new TileArt());
        assertEquals(0, r.sim.boxRejects, "placement into the bot's own box\n" + rep);
        assertEquals(0, r.sim.wrongClicks, "click without a target or with the wrong item\n" + rep);
        assertEquals(0, r.sim.farRejects, "airplace refused beyond the server's range\n" + rep);
        assertEquals(0, r.sim.fellOff, "walked off the platform\n" + rep);
        assertEquals(0, r.sim.pocketTicks, "stood on the floor walled in on all four sides\n" + rep);
        assertEquals(0, r.sim.setbacks, rep);
        assertEquals(1, r.sim.maxPlacementsPerTick, "placements per tick\n" + rep);
    }

    /**
     * A walk over a walled line never rests in a pocket or a 1-wide slot of the line's own blocks (a floor cell with two
     * or more full-block sides, one of them a block of the line), but for a tick here and there (a landing, waiting for
     * the last acks at the lane's end): under 2 % of its ticks. Cells narrow only by blocks beside the line are the floor walk's on any lane (in
     * AIRPLACE mode inner full blocks go up ahead of the bot): not counted here.
     */
    static void assertRarelyNarrow(Pass p, String what) {
        assertTrue(p.lineNarrowTicks() * 50 <= p.ticks(), "stood still in a pocket or a 1-wide slot of the line "
            + p.lineNarrowTicks() + " ticks\n" + what + "\n" + p.log());
    }

    // ------------------------------------------------------------------------------------------ cut lanes

    /**
     * The split restock planner's cut ({@link RestockLoads#cut}): load 1 runs lane {@code cutLane} up to {@code share}
     * of its cells (lane 0 whole before it when cutLane is 1, so lane 1 walks S→N); load 2 re-plans from the world: the
     * first remaining lane walks N→S, so lane 0 goes on from the cut and lane 1 comes back from its other end. Returns
     * the passes; the runner in {@code out[0]}.
     */
    static List<Pass> cutRounds(long seed, int cutLane, double share, boolean walkWalled, TileRunner[] out) {
        TileArt art = mixed(seed, 0, 9 * (cutLane + 1), 0.4);
        TileRunner r = runner(seed, walkWalled);
        List<Pass> passes = new ArrayList<>();
        for (Lane l : r.plan(art)) {
            if (l.index() < cutLane) passes.add(runLoaded(r, l));
            else if (l.index() == cutLane) {
                passes.add(runLoaded(r, RestockLoads.cut(l, (int) (l.placements().size() * share))));
            }
        }
        passes.add(runLoaded(r, lane(r, art, cutLane)));
        assertTrue(r.matches(art) || lane(r, art, cutLane) == null, "lane " + cutLane + " finished\n" + r.report(art));
        out[0] = r;
        return passes;
    }

    @Test
    void aCutLaneIsFinishedByWalkingFromEitherEnd() {
        TileRunner[] r = new TileRunner[1];
        long before = 0;
        long after = 0;
        int jumps = 0;
        StringBuilder sb = new StringBuilder();
        for (int cutLane = 0; cutLane <= 1; cutLane++) {
            for (long seed = 1; seed <= 3; seed++) {
                Pass old = cutRounds(seed, cutLane, 0.3, false, r).getLast();
                List<Pass> ps = cutRounds(seed, cutLane, 0.3, true, r);
                Pass now = ps.getLast();
                String what = "cut lane " + cutLane + " seed " + seed + ": before " + old + "\n  after " + now;
                sb.append(what).append('\n');
                assertClean(r[0], what);
                assertTrue(old.walled(), "the line at the cut holds a full block: walled before\n" + what);
                assertFalse(now.walled(), what + "\n" + now.log());
                assertTrue(now.walkedOver(), what);
                assertEquals(TaskStatus.SUCCESS, now.status(), what);
                assertEquals(now.cells(), now.placed(), what + "\n" + now.log());
                assertTrue(now.spotPlaced() <= 2, "stand spots only for a cell the walk passed\n" + what);
                // a few jumps: the full blocks at the cut. One pass's count moves with the click timing (24 cut passes,
                // seeds 1-12: 65 jumps with the LEGACY pacer, 68 with BURST, single passes up to 12 either way), so
                // each pass is held to 12 and the six together to 8 on average
                assertTrue(now.jumps() <= 12, "a few jumps: the full blocks at the cut\n" + what);
                jumps += now.jumps();
                assertRarelyNarrow(now, what);
                before += old.ticks();
                after += now.ticks();
            }
        }
        System.out.println(sb + "re-entry ticks: before " + before + ", after " + after + "; jumps " + jumps);
        assertTrue(jumps <= 6 * 8, "a few jumps per pass: " + jumps + " in 6 passes\n" + sb);
        assertTrue(after * 2 < before, "re-entries at least twice as fast: " + after + " vs " + before + " ticks\n" + sb);
    }

    // ------------------------------------------------------------------------------------------ loads that run out

    /** Lane 0 in rounds: a load with each item's share in [lo, hi] of what the lane still needs, the last round all. */
    static List<Pass> rounds(long seed, double[][] shares, boolean walkWalled, TileRunner[] out) {
        TileArt art = mixed(seed, 0, 9, 0.4);
        TileRunner r = runner(seed, walkWalled);
        Random rnd = new Random(seed * 31 + 7);
        List<Pass> passes = new ArrayList<>();
        for (double[] sh : shares) {
            r.stock.clear();
            need(r, art, 0).forEach((item, n) -> r.stock.put(item,
                (int) Math.round(n * (sh[0] + (sh[1] - sh[0]) * rnd.nextDouble()))));
            Lane l = lane(r, art, 0);
            if (l != null) passes.add(run(r, l));
        }
        assertTrue(r.matches(art), "built\n" + r.report(art));
        out[0] = r;
        return passes;
    }

    @Test
    void lanesReenteredAfterTheLoadRanOutAreWalked() {
        // as live before the split planner: 10-50 % of each item, then 30-70 % of the rest, then everything
        double[][] shares = {{0.1, 0.5}, {0.3, 0.7}, {1.0, 1.0}};
        TileRunner[] r = new TileRunner[1];
        long before = 0;
        long after = 0;
        StringBuilder sb = new StringBuilder();
        for (long seed = 1; seed <= 3; seed++) {
            List<Pass> old = rounds(seed, shares, false, r);
            List<Pass> now = rounds(seed, shares, true, r);
            assertClean(r[0], "seed " + seed + " " + now);
            sb.append("seed ").append(seed).append('\n');
            for (int i = 0; i < now.size(); i++) {
                sb.append("  before ").append(old.get(i)).append("\n  after  ").append(now.get(i)).append('\n');
                if (i > 0) {
                    assertTrue(old.get(i).walled(), "walled before\n" + sb);
                    assertFalse(now.get(i).walled(), sb.toString());
                    // at most a cell the walk passed (as the cut lanes): over 12 seeds x 4 re-entries, 1 such cell with
                    // the LEGACY pacer, 0 with BURST, 1 with BURST and Belady moves, whichever pass it lands in
                    assertTrue(now.get(i).spotPlaced() <= 1, "re-entry from stand spots\n" + sb);
                    assertRarelyNarrow(now.get(i), sb.toString());
                }
            }
            before += sum(old, p -> (int) p.ticks());
            after += sum(now, p -> (int) p.ticks());
        }
        System.out.println(sb + "lane ticks over three rounds: before " + before + ", after " + after);
        assertTrue(after * 2 < before, "at least twice as fast: " + after + " vs " + before + " ticks\n" + sb);
    }

    private static int sum(List<Pass> ps, ToIntFunction<Pass> f) {
        int n = 0;
        for (Pass p : ps) n += f.applyAsInt(p);
        return n;
    }

    // ------------------------------------------------------------------------------------------ walled lanes

    /** Lane 0 of {@code art} built except a random {@code leftShare} of its cells, then run with everything loaded. */
    static Pass prebuilt(TileArt art, long seed, double leftShare, boolean walkWalled, TileRunner[] out) {
        TileRunner r = runner(seed, walkWalled);
        Random rnd = new Random(seed);
        for (Placement p : lane(r, art, 0).placements()) if (rnd.nextDouble() >= leftShare) r.world.put(p.pos(), p.block());
        Pass p = runLoaded(r, lane(r, art, 0));
        assertTrue(r.matches(art), "built\n" + r.report(art));
        out[0] = r;
        return p;
    }

    @Test
    void aLineFullOfBlocksIsWalkedOver() {
        // a repair pass, or a lane left by the old code: holes all along a line that holds dozens of full blocks
        TileRunner[] r = new TileRunner[1];
        StringBuilder sb = new StringBuilder();
        for (double left : new double[]{0.1, 0.3, 0.6}) {
            Pass old = prebuilt(mixed(1, 0, 9, 0.4), 1, left, false, r);
            Pass now = prebuilt(mixed(1, 0, 9, 0.4), 1, left, true, r);
            String what = "left " + left + ": before " + old + "\n  after  " + now;
            sb.append(what).append('\n');
            assertClean(r[0], what);
            assertTrue(old.walled() && !now.walled() && now.walkedOver(), what);
            assertEquals(0, now.spotPlaced(), what);
            assertTrue(now.jumps() >= 10, "stepped up onto the line's blocks\n" + what);
            assertTrue(now.ticks() * 2 < old.ticks(), what);
            assertRarelyNarrow(now, what);
        }
        System.out.println(sb);
    }

    @Test
    void pocketsOnTheLineAreJumpedAcross() {
        // a checkerboard whose full blocks stand and whose carpets are missing: every other cell of the line is a
        // pocket (full blocks on all four sides). The walk jumps from block to block, never drops into one
        for (boolean w : new boolean[]{false, true}) {
            TileArt art = TileRunner.checkerboard(0, 0, 9, 128);
            TileRunner r = runner(5, w);
            for (Placement p : lane(r, art, 0).placements()) {
                if (p.block().id().endsWith("terracotta")) r.world.put(p.pos(), p.block());
            }
            Pass p = runLoaded(r, lane(r, art, 0));
            String what = "walkWalled " + w + ": " + p + "\n" + p.log();
            System.out.println("checkerboard holes, " + what.lines().findFirst().orElse(""));
            assertTrue(r.matches(art), what + "\n" + r.report(art));
            assertClean(r, what);
            if (w) {
                assertEquals(0, p.spotPlaced(), what);
                assertRarelyNarrow(p, what);
                assertTrue(p.ticks() < 1200, what);
            } else {
                assertTrue(p.walled() && p.spotPlaced() == p.cells(), "the previous behavior: stand spots\n" + what);
            }
        }
    }

    @Test
    void aLineThatIsOneWallIsWalkedOnTop() {
        // a repair pass over a lane whose whole line is full blocks (AirPlaceStandSpotTest's lane, all 128 rows)
        TileArt art = mixed(9, 0, 9, 0.4);
        TileRunner r = runner(9, true);
        Random rnd = new Random(9);
        for (Placement p : lane(r, art, 0).placements()) {
            boolean line = p.pos().x() == r.geo.minX() + 4;
            if (line) {
                art.set(4, p.pos().z() - r.geo.minZ(), BlockSpec.of(FULL[0]));
                r.world.put(p.pos(), BlockSpec.of(FULL[0]));
            } else if (rnd.nextDouble() >= 0.3) {
                r.world.put(p.pos(), p.block());
            }
        }
        Pass p = runLoaded(r, lane(r, art, 0));
        String what = p + "\n" + p.log();
        System.out.println("one-wall line: " + p);
        assertTrue(r.matches(art), what + "\n" + r.report(art));
        assertClean(r, what);
        assertTrue(p.walkedOver() && p.spotPlaced() == 0, what);
        assertTrue(r.sim.maxFeetY >= r.geo.artY() + 1, "on top of the wall\n" + what);
        assertRarelyNarrow(p, what);
    }

    @Test
    void setbackCellsNearTheLineKeepStandSpots() {
        // the engine's setback cells (PlaceLaneTask's avoid): the walk never goes past one, the stand spots keep away
        TileArt art = mixed(1, 0, 9, 0.4);
        TileRunner r = runner(1, true);
        Random rnd = new Random(1);
        for (Placement p : lane(r, art, 0).placements()) if (rnd.nextDouble() >= 0.3) r.world.put(p.pos(), p.block());
        Lane l = lane(r, art, 0);
        r.sim.avoid.add(new Pos(l.from().x() + 1, r.geo.artY(), l.from().z() + 40));
        Pass p = runLoaded(r, l);
        String what = p + "\n" + p.log();
        assertTrue(p.walled() && !p.walkedOver(), what);
        assertTrue(p.log().contains("next to a setback cell; not walking over them"), what);
        for (Pos g : r.sim.spotGoals) {
            assertTrue(LaneRun.nearAvoid(g, r.sim.avoid) == null, "stand spot next to a setback cell: " + g);
        }
    }

    @Test
    void aLineCellWalledInOnThreeSidesAtTheLaneEndIsPlacedFromTheBlockBeforeIt() {
        // live local 10x run (c930229, job mixed24-s24, lane 10): the last cell of a re-entered lane was a full block of
        // the line between a line block, two full blocks beside it and the lane's end cell (a slot: the cell beyond is a
        // full block). The walk passed it to place it behind, could not place it from the end cell (it would wall the bot
        // in), walked back out of the slot onto the block before it and forward again: three task timeouts, 600 jumps
        String[] rows = {"##c#####c", "####X####", "##c#c#c#c", "#######c#"};
        TileArt art = new TileArt();
        for (int i = 0; i < rows.length; i++) {
            for (int x = 0; x < 9; x++) {
                char c = rows[i].charAt(x);
                art.set(x, 20 + i, BlockSpec.of(c == '#' ? FULL[0] : c == 'c' ? CARPETS[0] : "minecraft:dripstone_block"));
            }
        }
        TileRunner r = runner(3, true);
        for (Placement p : lane(r, art, 0).placements()) {
            if (!p.block().id().equals("minecraft:dripstone_block")) r.world.put(p.pos(), p.block());
        }
        Lane l = lane(r, art, 0);
        assertEquals(1, l.placements().size());
        Pass p = runLoaded(r, l);
        String what = p + "\n" + p.log();
        assertEquals(TaskStatus.SUCCESS, p.status(), what);
        assertTrue(r.matches(art), what);
        assertEquals(0, p.spotPlaced(), what);
        assertTrue(p.ticks() < 1000 && p.jumps() <= 4, what);
    }

    @Test
    void lookLanesAndLanesWithoutAirplaceKeepStandSpots() {
        // the walk over a line needs airplace: LOOK cells could be hidden behind the line's blocks
        for (int c = 0; c < 2; c++) {
            TileRunner r = runner(7, true);
            if (c == 0) {
                r.build.placeMode = "LOOK";
                r.build.rotatePlayer = true;
                r.build.reach = 4.3;
            } else {
                r.sim.airPlaceReady = false;
            }
            TileArt art = mixed(7, 0, PlaceMode.laneHalfWidth(r.build) * 2 + 1, 0.4);
            Random rnd = new Random(7);
            for (Placement p : lane(r, art, 0).placements()) if (rnd.nextDouble() >= 0.2) r.world.put(p.pos(), p.block());
            Pass p = runLoaded(r, lane(r, art, 0));
            assertTrue(p.walled() && !p.walkedOver(), (c == 0 ? "LOOK" : "no airplace") + ": " + p + "\n" + p.log());
        }
    }

    @Test
    void hopLengthMatchesTheSimulatedJump() {
        assertEquals(2.62, LaneWalk.hopLength(0.2158), 0.01, "walking speed");
        assertEquals(1.14, LaneWalk.hopLength(0), 0.01, "from a standstill");
        // a jump at walking speed from the near half of a block lands on the middle of the block beyond the pocket
        assertTrue(-0.5 + LaneWalk.hopLength(0.2158) <= LaneWalk.HOP_LAND_MAX);
        assertTrue(LaneWalk.HOP_LAST + LaneWalk.hopLength(0.0) >= 1.5, "the last-moment jump still clears the pocket");
    }
}
